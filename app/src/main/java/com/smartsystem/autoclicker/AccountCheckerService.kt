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
import com.smartsystem.autoclicker.databinding.OverlayCheckerBinding
import com.smartsystem.autoclicker.models.AccountRepository
import com.smartsystem.autoclicker.models.AccountStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * COD Mobile Account Checker Service.
 *
 * Two concurrent coroutines run independently:
 *
 * A) SSO KEY WATCHER (always active while service is alive)
 *    - Polls every second for "Paste SSO key here" text on screen.
 *    - When detected → taps browser lock icon → View Cookies → reads sso_key
 *      → fills "Paste SSO key here" with only the hex value.
 *
 * B) ACCOUNT CHECKER LOOP (started/stopped by the overlay button)
 *    - Gets next PENDING account → opens GARENA → fills login → checks result.
 */
class AccountCheckerService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var binding: OverlayCheckerBinding
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var repo: AccountRepository

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var checkerJob: Job? = null
    private var ssoWatcherJob: Job? = null
    private var isRunning = false

    override fun onCreate() {
        super.onCreate()
        repo = AccountRepository(this)
        startForeground(NOTIF_ID, buildNotification())
        setupOverlay()
        startSsoKeyWatcher()   // ← always-on SSO watcher
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP  -> stopSelf()
            ACTION_START -> startChecker()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopChecker()
        ssoWatcherJob?.cancel()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay ──────────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        binding = OverlayCheckerBinding.inflate(LayoutInflater.from(this))
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
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 160
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
                    layoutParams.x = initX + (ev.rawX - touchX).toInt()
                    layoutParams.y = initY + (ev.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    true
                }
                else -> false
            }
        }

        binding.btnCheckerToggle.setOnClickListener {
            if (isRunning) stopChecker() else startChecker()
        }

        binding.btnGetSso.setOnClickListener {
            val svc = AutoClickAccessibilityService.instance
            if (svc == null) {
                Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            scope.launch { fetchSsoKeyFromBrowser(svc) }
        }

        updateOverlay("Ready", "", "", "")
    }

    private fun updateOverlay(status: String, account: String, counts: String, ssoStatus: String) {
        binding.btnCheckerToggle.text = if (isRunning) "STOP" else "START"
        binding.btnCheckerToggle.setBackgroundColor(
            if (isRunning) getColor(R.color.colorOverlayStop)
            else getColor(R.color.colorCheckerStart)
        )
        binding.tvCheckerStatus.text = status
        binding.tvCheckerAccount.text = account
        binding.tvCheckerCounts.text = counts
        if (ssoStatus.isNotBlank()) binding.tvSsoStatus.text = ssoStatus
    }

    private fun buildCountsText(): String {
        val all = repo.getAll()
        val pending  = all.count { it.status == AccountStatus.PENDING }
        val banned   = all.count { it.status == AccountStatus.BANNED }
        val newAcc   = all.count { it.status == AccountStatus.NEW_ACCOUNT }
        val good     = all.count { it.status == AccountStatus.GOOD }
        return "P:$pending B:$banned N:$newAcc G:$good"
    }

    // ─── SSO Key Watcher ──────────────────────────────────────────────────────

    /**
     * Always-on coroutine — polls the screen every second for "Paste SSO key here".
     * When detected, automatically extracts the sso_key from the browser cookies
     * and fills the field with only the hex value.
     */
    private fun startSsoKeyWatcher() {
        ssoWatcherJob?.cancel()
        ssoWatcherJob = scope.launch {
            Log.d(TAG, "SSO watcher started")
            while (isActive) {
                val svc = AutoClickAccessibilityService.instance
                if (svc != null) {
                    val found = withContext(Dispatchers.IO) {
                        svc.hasAnyText(
                            "paste sso key here",
                            "paste sso key",
                            "sso key here"
                        )
                    }
                    if (found != null) {
                        Log.d(TAG, "SSO field detected on screen — starting cookie fetch")
                        withContext(Dispatchers.Main) {
                            binding.tvSsoStatus.text = "SSO field detected!"
                        }
                        fetchSsoKeyFromBrowser(svc)
                        // Cooldown after a successful trigger so we don't loop endlessly
                        delay(5000L)
                        continue
                    }
                }
                delay(1000L)
            }
        }
    }

    /**
     * Full cookie-extraction flow:
     *  1. Tap browser lock / security icon
     *  2. Tap "View Cookies"
     *  3. Read all screen text
     *  4. Extract sso_key value (strip prefix)
     *  5. Go back
     *  6. Fill "Paste SSO key here" with only the hex value
     */
    private suspend fun fetchSsoKeyFromBrowser(svc: AutoClickAccessibilityService) {
        withContext(Dispatchers.Main) {
            binding.tvSsoStatus.text = "Fetching cookies…"
        }

        // ── Step 1: Tap the browser security / lock icon ──────────────────────
        // Different browsers expose this with different content descriptions.
        val lockPoint = withContext(Dispatchers.IO) {
            svc.findNodeCenter("Connection is secure")
                ?: svc.findNodeCenter("connection is secure")
                ?: svc.findNodeCenter("Site information")
                ?: svc.findNodeCenter("Not secure")
                ?: svc.findNodeCenter("View site information")
                ?: svc.findNodeCenter("Security info")
        }

        if (lockPoint == null) {
            withContext(Dispatchers.Main) { binding.tvSsoStatus.text = "Lock icon not found" }
            Log.w(TAG, "fetchSsoKey: browser lock icon not found")
            return
        }

        svc.tap(lockPoint.x, lockPoint.y)
        delay(900)

        // ── Step 2: Tap "View Cookies" ────────────────────────────────────────
        val tappedCookies = withContext(Dispatchers.IO) {
            svc.tapByText("View Cookies") || svc.tapByText("Cookies")
        }

        if (!tappedCookies) {
            svc.pressBack()
            delay(300)
            withContext(Dispatchers.Main) { binding.tvSsoStatus.text = "View Cookies not found" }
            Log.w(TAG, "fetchSsoKey: 'View Cookies' not found in popup")
            return
        }

        delay(1200)

        // ── Step 3: Read all on-screen text to extract sso_key ────────────────
        val screenText = withContext(Dispatchers.IO) { svc.readAllScreenText() }
        Log.d(TAG, "fetchSsoKey: screenText snippet = ${screenText.take(300)}")

        val ssoValue = SsoKeyHelper.extractSsoKey(screenText)

        // ── Step 4: Press back to close cookie viewer ─────────────────────────
        svc.pressBack()
        delay(600)

        if (ssoValue == null) {
            withContext(Dispatchers.Main) { binding.tvSsoStatus.text = "sso_key not found" }
            Log.w(TAG, "fetchSsoKey: sso_key not found in screen text")
            return
        }

        Log.d(TAG, "fetchSsoKey: extracted sso_key = ${SsoKeyHelper.mask(ssoValue)}")

        // ── Step 5: Fill "Paste SSO key here" with only the hex VALUE ─────────
        val filled = withContext(Dispatchers.IO) {
            svc.fillTextField("paste sso key here", ssoValue)
                || svc.fillTextField("paste sso key", ssoValue)
                || svc.fillTextField("sso key here", ssoValue)
                || svc.fillTextField("sso key", ssoValue)
        }

        if (!filled) {
            // Fallback: copy value to clipboard then tap the field and paste
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
            binding.tvSsoStatus.text = "✓ ${SsoKeyHelper.mask(ssoValue)}"
        }
        Log.d(TAG, "fetchSsoKey: done — filled with ${SsoKeyHelper.mask(ssoValue)}")
    }

    // ─── Account Checker Loop ─────────────────────────────────────────────────

    private fun startChecker() {
        if (!AutoClickAccessibilityService.isConnected) {
            Toast.makeText(this, "Enable Accessibility Service first", Toast.LENGTH_LONG).show()
            return
        }
        if (repo.getNextPending() == null) {
            Toast.makeText(this, "No pending accounts to check", Toast.LENGTH_SHORT).show()
            return
        }

        isRunning = true
        updateOverlay("Starting…", "", buildCountsText(), "")

        checkerJob = scope.launch {
            while (isActive && isRunning) {
                val account = repo.getNextPending()
                if (account == null) {
                    withContext(Dispatchers.Main) {
                        updateOverlay("Done! ✓", "All accounts checked", buildCountsText(), "")
                        isRunning = false
                    }
                    break
                }

                repo.setStatus(account.id, AccountStatus.IN_PROGRESS)
                val shortUser = account.username.take(14)

                // ── Step 1: Open GARENA ──────────────────────────────────────
                withContext(Dispatchers.Main) {
                    updateOverlay("Opening GARENA…", shortUser, buildCountsText(), "")
                }

                val svc = AutoClickAccessibilityService.instance ?: break

                val garenaPoint = withContext(Dispatchers.IO) { svc.findNodeCenter("GARENA") }
                if (garenaPoint != null) {
                    svc.tap(garenaPoint.x, garenaPoint.y)
                } else {
                    val launched = withContext(Dispatchers.Main) { tryLaunchCODM() }
                    if (!launched) {
                        Log.w(TAG, "Could not find GARENA or launch CODM")
                        delay(2000)
                        continue
                    }
                }

                // ── Step 2: Wait for Garena login screen ─────────────────────
                withContext(Dispatchers.Main) {
                    updateOverlay("Waiting login…", shortUser, buildCountsText(), "")
                }

                var loginReady = false
                repeat(20) {
                    if (!loginReady) {
                        val found = withContext(Dispatchers.IO) {
                            svc.hasAnyText("Garena Username", "Login Now", "Email or Phone")
                        }
                        if (found != null) loginReady = true else delay(500)
                    }
                }

                if (!loginReady) {
                    Log.w(TAG, "Login screen not found, skipping account")
                    repo.setStatus(account.id, AccountStatus.PENDING)
                    delay(2000)
                    continue
                }

                delay(600)

                // ── Step 3: Fill username ─────────────────────────────────────
                withContext(Dispatchers.Main) {
                    updateOverlay("Filling login…", shortUser, buildCountsText(), "")
                }

                var filled = withContext(Dispatchers.IO) {
                    svc.fillTextField("Garena Username", account.username)
                }
                if (!filled) {
                    withContext(Dispatchers.IO) {
                        svc.fillTextField("Email or Phone", account.username)
                    }
                }

                delay(400)

                // ── Step 4: Fill password ─────────────────────────────────────
                withContext(Dispatchers.IO) {
                    svc.fillTextField("Password", account.password)
                }

                delay(400)

                // ── Step 5: Tap Login Now ─────────────────────────────────────
                withContext(Dispatchers.Main) {
                    updateOverlay("Logging in…", shortUser, buildCountsText(), "")
                }

                val loginPoint = withContext(Dispatchers.IO) { svc.findNodeCenter("Login Now") }
                if (loginPoint != null) svc.tap(loginPoint.x, loginPoint.y)

                delay(600)

                // ── Step 6: Wait for result (up to 25 seconds) ───────────────
                withContext(Dispatchers.Main) {
                    updateOverlay("Checking…", shortUser, buildCountsText(), "")
                }

                var resultHandled = false
                repeat(50) { // 50 × 500ms = 25s
                    if (!resultHandled) {
                        val hit = withContext(Dispatchers.IO) {
                            svc.hasAnyText("violated", "abnormal", "CREATE CHARACTER")
                        }
                        when {
                            hit != null && (hit.contains("violated") || hit.contains("abnormal")) -> {
                                Log.d(TAG, "BANNED: ${account.username}")
                                repo.setStatus(account.id, AccountStatus.BANNED, hit)
                                withContext(Dispatchers.Main) {
                                    updateOverlay("Banned ✗", shortUser, buildCountsText(), "")
                                }
                                withContext(Dispatchers.IO) { svc.findNodeCenter("OK") }?.let { pt ->
                                    svc.tap(pt.x, pt.y)
                                }
                                delay(1500)
                                resultHandled = true
                            }
                            hit != null && hit.contains("create character") -> {
                                Log.d(TAG, "NEW ACCOUNT: ${account.username}")
                                repo.setStatus(account.id, AccountStatus.NEW_ACCOUNT)
                                withContext(Dispatchers.Main) {
                                    updateOverlay("New acct ◆", shortUser, buildCountsText(), "")
                                }
                                svc.pressBack()
                                delay(1500)
                                resultHandled = true
                            }
                        }
                        if (!resultHandled) delay(500)
                    }
                }

                if (!resultHandled) {
                    Log.d(TAG, "GOOD: ${account.username}")
                    repo.setStatus(account.id, AccountStatus.GOOD)
                    withContext(Dispatchers.Main) {
                        updateOverlay("Good ✓", shortUser, buildCountsText(), "")
                    }
                    svc.pressHome()
                    delay(2000)
                }

                delay(1500)
            }
        }
    }

    private fun stopChecker() {
        checkerJob?.cancel()
        checkerJob = null
        isRunning = false
        updateOverlay("Stopped", "", buildCountsText(), "")
    }

    // ─── App launch fallback ──────────────────────────────────────────────────

    private fun tryLaunchCODM(): Boolean {
        val codmPackages = listOf(
            "com.activision.callofduty.shooter",
            "com.garena.game.codm"
        )
        for (pkg in codmPackages) {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                startActivity(intent)
                return true
            }
        }
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val match = apps.firstOrNull { app ->
            try {
                val label = pm.getApplicationLabel(app).toString().lowercase()
                label.contains("call of duty") || label.contains("garena")
            } catch (_: Exception) { false }
        }
        if (match != null) {
            val intent = pm.getLaunchIntentForPackage(match.packageName) ?: return false
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return true
        }
        return false
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Account Checker", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(this, 1,
            Intent(this, AccountCheckerService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("COD Account Checker")
            .setContentText("Monitoring for SSO key & account checking")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "AccountCheckerService"
        private const val NOTIF_ID = 1002
        private const val NOTIF_CHANNEL = "account_checker"
        const val ACTION_START = "com.smartsystem.autoclicker.CHECKER_START"
        const val ACTION_STOP  = "com.smartsystem.autoclicker.CHECKER_STOP"
    }
}
