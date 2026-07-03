package com.smartsystem.autoclicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.smartsystem.autoclicker.databinding.OverlayNovelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Floating overlay version of the Novel Reader — keeps reading aloud with TTS
 * while the user uses another app (e.g. a browser) underneath, same idea as
 * the Auto-Click / SSO Key floating controls.
 *
 * Loads the same file the full-screen NovelReaderActivity was showing and
 * resumes from the same paragraph. Minimal controls: prev / play-pause / next / close.
 * Tapping the paragraph text re-opens the full-screen reader.
 */
class NovelReaderOverlayService : Service(), TextToSpeech.OnInitListener {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var binding: OverlayNovelBinding
    private lateinit var layoutParams: WindowManager.LayoutParams

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var readingJob: Job? = null

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val paragraphs = mutableListOf<String>()
    private var currentPara = 0
    private var isReading = false

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())
        setupOverlay()
        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        val uriString = intent?.getStringExtra(EXTRA_FILE_URI)
        val startPara = intent?.getIntExtra(EXTRA_START_PARA, 0) ?: 0
        if (uriString != null) {
            loadFile(Uri.parse(uriString), startPara)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        readingJob?.cancel()
        tts?.stop(); tts?.shutdown()
        if (::overlayView.isInitialized) {
            try { windowManager.removeView(overlayView) } catch (_: Exception) {}
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Overlay setup ────────────────────────────────────────────────────────

    private fun setupOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        binding = OverlayNovelBinding.inflate(LayoutInflater.from(this))
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
        setupDrag()

        binding.btnOverlayPlayPause.setOnClickListener { onPlayPause() }
        binding.btnOverlayPrev.setOnClickListener { skip(-1) }
        binding.btnOverlayNext.setOnClickListener { skip(1) }
        binding.btnOverlayClose.setOnClickListener { stopSelf() }
        binding.tvOverlayParagraph.setOnClickListener { reopenActivity() }

        binding.tvOverlayParagraph.text = "Loading…"
        binding.tvOverlayProgress.text = ""
    }

    private fun setupDrag() {
        var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
        overlayView.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = layoutParams.x; initY = layoutParams.y
                    touchX = ev.rawX; touchY = ev.rawY; false
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = initX + (ev.rawX - touchX).toInt()
                    layoutParams.y = initY + (ev.rawY - touchY).toInt()
                    windowManager.updateViewLayout(overlayView, layoutParams)
                    false
                }
                else -> false
            }
        }
    }

    private fun reopenActivity() {
        val intent = Intent(this, NovelReaderActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra(NovelReaderActivity.EXTRA_START_PARA, currentPara)
        }
        startActivity(intent)
    }

    // ─── File loading ─────────────────────────────────────────────────────────

    private fun loadFile(uri: Uri, startPara: Int) {
        scope.launch {
            val rawText = withContext(Dispatchers.IO) { NovelParser.readContent(contentResolver, uri) }
            if (rawText.isNullOrBlank()) {
                binding.tvOverlayParagraph.text = "Could not read file"
                return@launch
            }
            val paras = withContext(Dispatchers.Default) { NovelParser.parseParagraphs(rawText) }
            paragraphs.clear(); paragraphs.addAll(paras)
            currentPara = startPara.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
            updateParagraphView()
        }
    }

    private fun updateParagraphView() {
        val total = paragraphs.size
        binding.tvOverlayParagraph.text = paragraphs.getOrNull(currentPara) ?: "No text loaded"
        binding.tvOverlayProgress.text = if (total > 0) "${currentPara + 1} / $total" else ""
    }

    // ─── Playback ─────────────────────────────────────────────────────────────

    private fun onPlayPause() {
        when {
            paragraphs.isEmpty() -> Toast.makeText(this, "No text loaded yet", Toast.LENGTH_SHORT).show()
            !ttsReady -> Toast.makeText(this, "TTS engine not ready", Toast.LENGTH_SHORT).show()
            isReading -> pauseReading()
            else -> startReading()
        }
    }

    private fun startReading() {
        isReading = true
        binding.btnOverlayPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        readingJob = scope.launch { readLoop() }
    }

    private fun pauseReading() {
        isReading = false; tts?.stop(); readingJob?.cancel()
        binding.btnOverlayPlayPause.setImageResource(android.R.drawable.ic_media_play)
    }

    private fun skip(delta: Int) {
        val wasReading = isReading
        if (wasReading) { isReading = false; tts?.stop(); readingJob?.cancel() }
        currentPara = (currentPara + delta).coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
        updateParagraphView()
        if (wasReading) startReading()
    }

    private suspend fun readLoop() {
        while (isReading && currentPara < paragraphs.size) {
            withContext(Dispatchers.Main) { updateParagraphView() }
            speakAndWait(paragraphs[currentPara])
            if (!isReading) break
            currentPara++
            delay(180)
        }
        if (isReading) {
            withContext(Dispatchers.Main) {
                isReading = false
                binding.btnOverlayPlayPause.setImageResource(android.R.drawable.ic_media_play)
                binding.tvOverlayProgress.text = "✓ Done"
            }
        }
    }

    private suspend fun speakAndWait(text: String) {
        if (tts == null || !ttsReady || text.isBlank()) return
        withTimeoutOrNull(240_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val uid = "novel_overlay_${System.nanoTime()}"
                val done = AtomicBoolean(false)
                fun finish() { if (done.compareAndSet(false, true)) cont.resume(Unit) }
                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id == uid) finish() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { if (id == uid) finish() }
                })
                if (tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid) == TextToSpeech.ERROR) finish()
                cont.invokeOnCancellation { tts?.stop() }
            }
        } ?: tts?.stop()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) tts?.language = Locale.ENGLISH
    }

    // ─── Notification ─────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(NOTIF_CHANNEL, "Novel Reader Overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, NovelReaderOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("Novel Reader")
            .setContentText("Reading in floating overlay")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
    }

    companion object {
        private const val NOTIF_ID = 1004
        private const val NOTIF_CHANNEL = "novel_reader_overlay"
        const val ACTION_STOP = "com.smartsystem.autoclicker.NOVEL_OVERLAY_STOP"
        const val EXTRA_FILE_URI = "extra_file_uri"
        const val EXTRA_START_PARA = "extra_start_para"
    }
}
