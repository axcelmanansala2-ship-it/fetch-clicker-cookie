package com.smartsystem.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.smartsystem.autoclicker.databinding.OverlayControlBinding
import com.smartsystem.autoclicker.models.TargetRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground overlay service — sequential detection + tap loop.
 *
 * Detection strategy (tried in order for each step):
 *  1. AccessibilityService.findNodeCenter(text) — reads real UI tree (fast, accurate)
 *  2. If not found → tryLaunchApp(text) — finds installed app whose label contains
 *     the text and launches it with FLAG_ACTIVITY_NEW_TASK (handles launcher icons
 *     that don't appear in the accessibility tree)
 *
 * This handles both in-app UI elements (like a "Homepage" button) AND
 * launcher app icons (like the "Via" browser icon) reliably.
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var binding: OverlayControlBinding
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var detectionJob: Job? = null
    private var isDetecting = false
    private var currentStepIndex = 0

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        stopDetection()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay ──────────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        binding = OverlayControlBinding.inflate(LayoutInflater.from(this))
        overlayView = binding.root

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 160
        }

        windowManager.addView(overlayView, layoutParams)
        setupDrag()

        binding.btnToggle.setOnClickListener {
            if (isDetecting) stopDetection() else startDetection()
        }
        updateUI("Idle", "")
    }

    private fun setupDrag() {
        var initX = 0; var initY = 0
        var touchX = 0f; var touchY = 0f
        overlayView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x; initY = layoutParams.y
                    touchX = event.rawX; touchY = event.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initX + (touchX - event.rawX).toInt()
                    layoutParams.y = initY + (event.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun updateUI(status: String, step: String) {
        binding.btnToggle.text = if (isDetecting) "STOP" else "START"
        binding.btnToggle.setBackgroundColor(
            if (isDetecting) getColor(R.color.colorOverlayStop)
            else getColor(R.color.colorOverlayStart)
        )
        binding.tvStatus.text = status
        binding.tvStep.text = step
    }

    // ─── App-launch fallback ──────────────────────────────────────────────────

    /**
     * Tries to find an installed app whose label contains [query] (case-insensitive)
     * and launches it. Returns true if launched, false if no matching app found.
     *
     * This is the fallback for launcher icons that are NOT exposed in the
     * accessibility tree — e.g. some launchers don't surface icon text as nodes.
     */
    private fun tryLaunchApp(query: String): Boolean {
        val q = query.lowercase().trim()
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

        val match = apps.firstOrNull { app ->
            try {
                pm.getApplicationLabel(app).toString().lowercase().contains(q)
            } catch (_: Exception) { false }
        } ?: return false

        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        Log.d(TAG, "Launching app '${match.packageName}' for query '$query'")
        startActivity(launchIntent)
        return true
    }

    // ─── Sequential Detection Loop ────────────────────────────────────────────

    private fun startDetection() {
        if (!AutoClickAccessibilityService.isConnected) {
            Toast.makeText(this, getString(R.string.msg_accessibility_needed), Toast.LENGTH_LONG).show()
            return
        }
        val targets = TargetRepository(this).getAll().filter { it.enabled }
        if (targets.isEmpty()) {
            Toast.makeText(this, getString(R.string.msg_no_targets), Toast.LENGTH_SHORT).show()
            return
        }

        isDetecting = true
        currentStepIndex = 0
        updateUI("Running…", "Step 1/${targets.size}")

        detectionJob = scope.launch {
            while (isActive && isDetecting) {
                val enabledTargets = TargetRepository(this@FloatingOverlayService)
                    .getAll().filter { it.enabled }
                if (enabledTargets.isEmpty()) break

                val stepIdx = currentStepIndex % enabledTargets.size
                val target = enabledTargets[stepIdx]
                val total = enabledTargets.size

                withContext(Dispatchers.Main) {
                    updateUI("Searching…", "Step ${stepIdx + 1}/$total")
                }

                // ── Strategy 1: AccessibilityService node finding ──────────────
                val tapPoint = withContext(Dispatchers.IO) {
                    AutoClickAccessibilityService.instance?.findNodeCenter(target.textQuery)
                }

                if (tapPoint != null) {
                    Log.d(TAG, "Step $stepIdx '${target.textQuery}' → node tap at $tapPoint")
                    withContext(Dispatchers.Main) {
                        updateUI("Tapped!", "Step ${stepIdx + 1}/$total")
                    }
                    AutoClickAccessibilityService.instance?.tap(tapPoint.x, tapPoint.y)
                    currentStepIndex++
                    delay(target.delayAfterMs)
                    continue
                }

                // ── Strategy 2: App launch fallback (for launcher icons) ────────
                val launched = withContext(Dispatchers.Main) {
                    tryLaunchApp(target.textQuery)
                }

                if (launched) {
                    Log.d(TAG, "Step $stepIdx '${target.textQuery}' → launched app")
                    withContext(Dispatchers.Main) {
                        updateUI("Launched!", "Step ${stepIdx + 1}/$total")
                    }
                    currentStepIndex++
                    // Give the app time to open before next step
                    delay(maxOf(target.delayAfterMs, 1500L))
                    continue
                }

                // ── Nothing found — keep polling ────────────────────────────────
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopDetection() {
        detectionJob?.cancel()
        detectionJob = null
        isDetecting = false
        currentStepIndex = 0
        updateUI("Stopped", "")
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, FloatingOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
    }

    companion object {
        private const val TAG = "FloatingOverlayService"
        private const val NOTIF_CHANNEL_ID = "smart_auto_clicker"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 500L

        const val ACTION_STOP = "com.smartsystem.autoclicker.STOP"
    }
}
