package com.smartsystem.autoclicker

import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.graphics.PixelFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.smartsystem.autoclicker.databinding.OverlayAimlockBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aim Lock Service — detects moving bullseye targets via screen capture + color analysis,
 * then automatically adjusts the CODM crosshair using swipe gestures on the aim zone.
 *
 * Flow:
 *  1. Started from MainActivity after user grants MediaProjection permission
 *  2. Creates ScreenCaptureManager to grab screen frames at ~12 fps
 *  3. BullseyeDetector finds the red/orange target position in each frame
 *  4. Calculates pixel offset from screen center (crosshair) to target
 *  5. Performs a proportional swipe on the right aim zone to snap crosshair onto target
 *
 * Aim zone (CODM FPS mode): right portion of screen — drag here rotates camera.
 * Drag origin: 75% x, 50% y  (tuned for CODM default right-stick layout)
 */
class AimLockService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var binding: OverlayAimlockBinding
    private lateinit var layoutParams: WindowManager.LayoutParams

    private var screenCapture: ScreenCaptureManager? = null
    private var mediaProjection: MediaProjection? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var aimJob: Job? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (mediaProjection == null) {
            val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                ?: Activity.RESULT_CANCELED
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            if (resultCode == Activity.RESULT_OK && data != null) {
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mgr.getMediaProjection(resultCode, data)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        screenCapture?.release()
                        screenCapture = null
                    }
                }, null)
                screenCapture = ScreenCaptureManager(this, mediaProjection!!)
                Log.d(TAG, "MediaProjection ready")
                updateOverlay("Ready", "Tap START")
            } else {
                Log.w(TAG, "No valid MediaProjection data")
                Toast.makeText(this, "Screen capture permission needed", Toast.LENGTH_SHORT).show()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAimLock()
        screenCapture?.release()
        mediaProjection?.stop()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay ──────────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        binding = OverlayAimlockBinding.inflate(LayoutInflater.from(this))
        overlayView = binding.root

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 450
        }

        windowManager.addView(overlayView, layoutParams)

        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
        overlayView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x; initY = layoutParams.y
                    touchX = ev.rawX; touchY = ev.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initX + (touchX - ev.rawX).toInt()
                    layoutParams.y = initY + (ev.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }

        binding.btnAimToggle.setOnClickListener {
            if (isRunning) stopAimLock() else startAimLock()
        }

        updateOverlay("Init…", "")
    }

    private fun updateOverlay(status: String, detail: String) {
        binding.btnAimToggle.text = if (isRunning) "STOP" else "START"
        binding.btnAimToggle.setBackgroundColor(
            if (isRunning) getColor(R.color.colorOverlayStop)
            else getColor(R.color.colorAimLockStart)
        )
        binding.tvAimStatus.text = status
        binding.tvAimDetail.text = detail
    }

    // ─── Aim Lock Loop ────────────────────────────────────────────────────────

    private fun startAimLock() {
        if (!AutoClickAccessibilityService.isConnected) {
            Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_LONG).show()
            return
        }
        if (screenCapture == null) {
            Toast.makeText(this, "Screen capture not ready — restart the service", Toast.LENGTH_SHORT).show()
            return
        }

        isRunning = true
        updateOverlay("Scanning…", "")

        aimJob = scope.launch {
            var missCount = 0
            val screenW = resources.displayMetrics.widthPixels.toFloat()
            val screenH = resources.displayMetrics.heightPixels.toFloat()

            while (isActive && isRunning) {
                val bitmap = withContext(Dispatchers.IO) { screenCapture?.captureScreen() }

                if (bitmap == null) { delay(POLL_MS); continue }

                val result = withContext(Dispatchers.Default) { BullseyeDetector.detect(bitmap) }
                bitmap.recycle()

                if (result != null) {
                    missCount = 0
                    val dx = result.center.x - screenW / 2f
                    val dy = result.center.y - screenH / 2f

                    withContext(Dispatchers.Main) {
                        updateOverlay("Locked!", "Δ(${dx.toInt()}, ${dy.toInt()})")
                    }

                    // Only nudge if offset is more than 5px
                    if (Math.abs(dx) > 5f || Math.abs(dy) > 5f) {
                        performAimSwipe(screenW, screenH, dx, dy)
                    }
                } else {
                    missCount++
                    if (missCount > 5) {
                        withContext(Dispatchers.Main) { updateOverlay("Scanning…", "") }
                    }
                }

                delay(POLL_MS)
            }
        }
    }

    private fun stopAimLock() {
        aimJob?.cancel()
        aimJob = null
        isRunning = false
        updateOverlay("Stopped", "")
    }

    // ─── Aim Swipe Gesture ────────────────────────────────────────────────────

    /**
     * Swipe on the right aim zone to rotate camera toward the detected target.
     * Origin: fixed at [AIM_ORIGIN_X, AIM_ORIGIN_Y] of screen.
     * Delta: target offset × sensitivity (tunable).
     */
    private fun performAimSwipe(screenW: Float, screenH: Float, dx: Float, dy: Float) {
        val svc = AutoClickAccessibilityService.instance ?: return

        val originX = screenW * AIM_ORIGIN_X
        val originY = screenH * AIM_ORIGIN_Y
        val endX = (originX + dx * SENSITIVITY).coerceIn(screenW * 0.5f, screenW * 0.98f)
        val endY = (originY + dy * SENSITIVITY).coerceIn(screenH * 0.05f, screenH * 0.95f)

        val path = Path().apply { moveTo(originX, originY); lineTo(endX, endY) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, SWIPE_DURATION_MS)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        svc.dispatchGesture(gesture, null, null)
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Aim Lock", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, AimLockService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Aim Lock Active")
            .setContentText("Detecting bullseye targets…")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG             = "AimLockService"
        private const val NOTIF_ID        = 1004
        private const val NOTIF_CHANNEL   = "aim_lock"
        private const val POLL_MS         = 80L     // ~12 fps
        private const val SWIPE_DURATION_MS = 50L
        private const val SENSITIVITY     = 0.25f   // swipe px per target-offset px
        private const val AIM_ORIGIN_X    = 0.75f   // right aim zone x
        private const val AIM_ORIGIN_Y    = 0.50f   // right aim zone y

        const val ACTION_STOP           = "com.smartsystem.autoclicker.AIM_STOP"
        const val EXTRA_RESULT_CODE     = "extra_result_code"
        const val EXTRA_PROJECTION_DATA = "extra_projection_data"
    }
}
