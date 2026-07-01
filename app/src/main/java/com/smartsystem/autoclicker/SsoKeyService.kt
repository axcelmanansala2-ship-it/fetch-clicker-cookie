package com.smartsystem.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.smartsystem.autoclicker.databinding.OverlaySsoBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Standalone SSO Key Service — completely separate from the Account Checker.
 *
 * Responsibilities:
 *  - Floating overlay with "Get from Cookies" button and SSO status.
 *  - Always-on watcher: polls every second for "Paste SSO key here" on screen.
 *    When detected → taps lock icon by screen % coords → View Cookies → reads sso_key
 *    → fills the field with only the hex value.
 *  - Manual trigger via the "Get from Cookies" button on the overlay.
 *
 * Lock icon position (Garena Account Center WebView toolbar):
 *   x ≈ 6% of screen width,  y ≈ 8% of screen height
 *   Calculated at runtime via DisplayMetrics so it adapts to any screen size.
 */
class SsoKeyService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var binding: OverlaySsoBinding
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watcherJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlay()
        startWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_STICKY
    }

    override fun onDestroy() {
        watcherJob?.cancel()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Screen size helper ───────────────────────────────────────────────────

    /**
     * Returns actual screen pixel dimensions, compatible with API 26+.
     * Used to convert percentage-based coords → real tap coordinates.
     */
    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    // ─── Overlay ──────────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        binding = OverlaySsoBinding.inflate(LayoutInflater.from(this))
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
            y = 300
        }

        windowManager.addView(overlayView, layoutParams)

        // Drag support
        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
        overlayView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x; initY = layoutParams.y
                    touchX = ev.rawX; touchY = ev.rawY; true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initX + (ev.rawX - touchX).toInt()
                    layoutParams.y = initY + (ev.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }

        binding.btnGetSso.setOnClickListener {
            val svc = AutoClickAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scope.launch { fetchSsoKeyFromBrowser(svc) }
        }

        setStatus("Watching…")
    }

    private fun setStatus(text: String) {
        binding.tvSsoStatus.text = text
    }

    // ─── Auto-watcher ─────────────────────────────────────────────────────────

    private fun startWatcher() {
        watcherJob?.cancel()
        watcherJob = scope.launch {
            Log.d(TAG, "SSO watcher started")
            while (isActive) {
                val svc = AutoClickAccessibilityService.instance
                if (svc != null) {
                    val found = withContext(Dispatchers.IO) {
                        svc.hasAnyText("paste sso key here", "paste sso key", "sso key here")
                    }
                    if (found != null) {
                        Log.d(TAG, "SSO field detected — starting cookie fetch")
                        withContext(Dispatchers.Main) { setStatus("SSO field detected!") }
                        fetchSsoKeyFromBrowser(svc)
                        delay(3000L)
                        continue
                    }
                }
                delay(500L)
            }
        }
    }

    // ─── Cookie fetch flow ────────────────────────────────────────────────────

    /**
     * Full cookie extraction flow using fixed % coordinates for the lock icon
     * (the icon is an image, not text, so it cannot be found via accessibility node text).
     *
     * Lock icon position in the Garena Account Center WebView toolbar:
     *   x ≈ 6% of screen width  (left side of the top bar)
     *   y ≈ 8% of screen height  (inside the Garena Account Center title bar)
     */
    private suspend fun fetchSsoKeyFromBrowser(svc: AutoClickAccessibilityService) {
        withContext(Dispatchers.Main) { setStatus("Fetching cookies…") }

        // ── Step 1: Tap the Garena lock/shield icon by screen % coordinates ──
        // The icon is at ~6% x, 8% y of the screen in the Garena Account Center bar.
        val (screenW, screenH) = withContext(Dispatchers.Main) { getScreenSize() }
        val lockX = screenW * LOCK_ICON_X_PCT
        val lockY = screenH * LOCK_ICON_Y_PCT

        Log.d(TAG, "Tapping lock icon at ($lockX, $lockY) on ${screenW}×${screenH} screen")
        svc.tap(lockX, lockY)
        delay(300)

        // ── Step 2: Tap "View Cookies" or "Cookies" (text element in the popup) ─
        val tappedCookies = withContext(Dispatchers.IO) {
            svc.tapByText("View Cookies") || svc.tapByText("Cookies")
        }

        if (!tappedCookies) {
            svc.pressBack()
            delay(150)
            withContext(Dispatchers.Main) { setStatus("View Cookies not found") }
            Log.w(TAG, "'View Cookies' not found in popup after tapping lock icon")
            return
        }

        delay(400)

        // ── Step 3: Read all screen text and extract sso_key ─────────────────
        val screenText = withContext(Dispatchers.IO) { svc.readAllScreenText() }
        Log.d(TAG, "screenText snippet = ${screenText.take(300)}")

        val ssoValue = SsoKeyHelper.extractSsoKey(screenText)

        // ── Step 4: Press back to close cookie viewer ─────────────────────────
        svc.pressBack()
        delay(200)

        if (ssoValue == null) {
            withContext(Dispatchers.Main) { setStatus("sso_key not found") }
            Log.w(TAG, "sso_key not found in screen text")
            return
        }

        Log.d(TAG, "Extracted sso_key = ${SsoKeyHelper.mask(ssoValue)}")

        // ── Step 5: Fill "Paste SSO key here" with only the hex VALUE ─────────
        val filled = withContext(Dispatchers.IO) {
            svc.fillTextField("paste sso key here", ssoValue)
                || svc.fillTextField("paste sso key", ssoValue)
                || svc.fillTextField("sso key here", ssoValue)
                || svc.fillTextField("sso key", ssoValue)
        }

        if (!filled) {
            // Fallback: copy to clipboard and paste
            withContext(Dispatchers.Main) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("sso_key", ssoValue))
            }
            delay(200)
            val fieldPoint = withContext(Dispatchers.IO) {
                svc.findNodeCenter("paste sso key here")
                    ?: svc.findNodeCenter("paste sso key")
                    ?: svc.findNodeCenter("sso key here")
            }
            if (fieldPoint != null) {
                svc.tap(fieldPoint.x, fieldPoint.y)
                delay(300)
                svc.paste()
            }
        }

        withContext(Dispatchers.Main) {
            setStatus("✓ ${SsoKeyHelper.mask(ssoValue)}")
        }
        Log.d(TAG, "Done — filled with ${SsoKeyHelper.mask(ssoValue)}")
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "SSO Key Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, SsoKeyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("SSO Key Watcher")
            .setContentText("Watching for SSO key field on screen")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "SsoKeyService"
        private const val NOTIF_ID = 1003
        private const val NOTIF_CHANNEL = "sso_key_service"
        const val ACTION_STOP = "com.smartsystem.autoclicker.SSO_STOP"

        /**
         * Garena Account Center WebView — lock/shield icon position as screen percentage.
         * Measured from the attached screenshot: icon is in the top toolbar, left side.
         * Adjust these constants if the icon position differs on other devices.
         */
        const val LOCK_ICON_X_PCT = 0.06f   // 6% from left
        const val LOCK_ICON_Y_PCT = 0.08f   // 8% from top
    }
}
