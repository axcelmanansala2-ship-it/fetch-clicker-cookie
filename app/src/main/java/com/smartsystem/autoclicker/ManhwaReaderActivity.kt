package com.smartsystem.autoclicker

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ManhwaReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var scrollView: ScrollView
    private lateinit var pagesContainer: LinearLayout
    private lateinit var tvStatus: TextView
    private lateinit var tvPageCount: TextView
    private lateinit var btnReadPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnAutoScroll: ToggleButton
    private lateinit var btnSettings: Button

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isReading = false
    private var autoScrollEnabled = true
    private var readingJob: Job? = null
    private val pageBitmaps = mutableListOf<Bitmap>()
    private var currentPageIndex = 0

    // Settings
    private var ttsSpeed = 1.0f
    private var scrollDuration = 1500L

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manhwa_reader)

        scrollView     = findViewById(R.id.manhwaScrollView)
        pagesContainer = findViewById(R.id.pagesContainer)
        tvStatus       = findViewById(R.id.tvManhwaStatus)
        tvPageCount    = findViewById(R.id.tvPageCount)
        btnReadPause   = findViewById(R.id.btnReadPause)
        btnStop        = findViewById(R.id.btnManhwaStop)
        btnAutoScroll  = findViewById(R.id.btnAutoScroll)
        btnSettings    = findViewById(R.id.btnSettings)

        tts = TextToSpeech(this, this)
        loadFileFromIntent()

        btnReadPause.setOnClickListener { if (isReading) pauseReading() else startReading() }
        btnStop.setOnClickListener { stopReading() }
        btnAutoScroll.setOnCheckedChangeListener { _, checked -> autoScrollEnabled = checked }
        btnSettings.setOnClickListener { showSettingsDialog() }
    }

    @Suppress("DEPRECATION")
    private fun loadFileFromIntent() {
        val uri: Uri? = intent?.data
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else
                intent?.getParcelableExtra(Intent.EXTRA_STREAM)

        if (uri == null) { tvStatus.text = "No file"; return }
        tvStatus.text = "Loading..."

        lifecycleScope.launch {
            val bitmaps = withContext(Dispatchers.IO) {
                val mime = contentResolver.getType(uri) ?: ""
                val isPdf = mime.contains("pdf", ignoreCase = true)
                    || uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true
                if (isPdf) loadPdfPages(uri) else loadImagePage(uri)
            }

            if (bitmaps.isEmpty()) { tvStatus.text = "Load failed"; return@launch }

            pageBitmaps.clear()
            pageBitmaps.addAll(bitmaps)
            pagesContainer.removeAllViews()

            for (bmp in bitmaps) {
                val iv = ImageView(this@ManhwaReaderActivity).apply {
                    setImageBitmap(bmp)
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                pagesContainer.addView(iv)
            }

            updatePageCounter(0)
            tvStatus.text = "Tap \u25B6 to read"
        }
    }

    private fun updatePageCounter(index: Int) {
        val total = pageBitmaps.size
        tvPageCount.text = if (total > 0) "${index + 1} / $total" else ""
    }

    // ── Loaders ──────────────────────────────────────────────────────────────

    private fun loadImagePage(uri: Uri): List<Bitmap> {
        return try {
            val stream = contentResolver.openInputStream(uri) ?: return emptyList()
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = BitmapFactory.decodeStream(stream, null, opts)
            stream.close()
            if (bmp != null) listOf(bmp) else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    private fun loadPdfPages(uri: Uri): List<Bitmap> {
        val bitmaps = mutableListOf<Bitmap>()
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            fd = openPdfDescriptor(uri) ?: return emptyList()
            renderer = PdfRenderer(fd)
            val targetWidth = minOf(resources.displayMetrics.widthPixels, 720)
            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val scale = targetWidth.toFloat() / page.width.coerceAtLeast(1)
                val pageH = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(targetWidth, pageH, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmaps.add(bmp)
            }
        } catch (e: Exception) {
            // return pages loaded so far
        } finally {
            renderer?.close()
            fd?.close()
        }
        return bitmaps
    }

    private fun openPdfDescriptor(uri: Uri): ParcelFileDescriptor? {
        return try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (e: Exception) {
            try {
                val tmp = File(cacheDir, "manhwa_tmp.pdf")
                contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e2: Exception) { null }
        }
    }

    // ── Settings dialog ───────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        if (isReading) pauseReading()

        val speedLabels = arrayOf("Slow (0.75x)", "Normal (1.0x)", "Fast (1.5x)", "Very Fast (2.0x)")
        val speedValues = floatArrayOf(0.75f, 1.0f, 1.5f, 2.0f)
        val scrollLabels = arrayOf("Very Slow (4s)", "Slow (2.5s)", "Normal (1.5s)", "Fast (0.7s)")
        val scrollValues = longArrayOf(4000L, 2500L, 1500L, 700L)

        val curSpeedIdx  = speedValues.indexOfFirst { it == ttsSpeed }.takeIf { it >= 0 } ?: 1
        val curScrollIdx = scrollValues.indexOfFirst { it == scrollDuration }.takeIf { it >= 0 } ?: 2

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }

        layout.addView(TextView(this).apply {
            text = "Reading Speed (TTS)"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        })
        val speedGroup = RadioGroup(this)
        speedLabels.forEachIndexed { i, label ->
            RadioButton(this).apply {
                text = label
                id = 100 + i
                isChecked = (i == curSpeedIdx)
                speedGroup.addView(this)
            }
        }
        layout.addView(speedGroup)

        layout.addView(TextView(this).apply {
            text = "Auto-Scroll Speed"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 4)
        })
        val scrollGroup = RadioGroup(this)
        scrollLabels.forEachIndexed { i, label ->
            RadioButton(this).apply {
                text = label
                id = 200 + i
                isChecked = (i == curScrollIdx)
                scrollGroup.addView(this)
            }
        }
        layout.addView(scrollGroup)

        AlertDialog.Builder(this)
            .setTitle("Reader Settings")
            .setView(layout)
            .setPositiveButton("Apply") { _, _ ->
                val si = speedGroup.checkedRadioButtonId - 100
                if (si in speedValues.indices) {
                    ttsSpeed = speedValues[si]
                    tts?.setSpeechRate(ttsSpeed)
                }
                val sc = scrollGroup.checkedRadioButtonId - 200
                if (sc in scrollValues.indices) scrollDuration = scrollValues[sc]
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Reading ───────────────────────────────────────────────────────────────

    private fun startReading() {
        if (!ttsReady) { Toast.makeText(this, "TTS not ready", Toast.LENGTH_SHORT).show(); return }
        if (pageBitmaps.isEmpty()) { Toast.makeText(this, "No pages loaded", Toast.LENGTH_SHORT).show(); return }
        isReading = true
        btnReadPause.text = "\u23F8"
        readingJob = lifecycleScope.launch { readPages(currentPageIndex) }
    }

    private fun pauseReading() {
        isReading = false
        tts?.stop()
        readingJob?.cancel()
        btnReadPause.text = "\u25B6"
        tvStatus.text = "Paused"
    }

    private fun stopReading() {
        isReading = false
        tts?.stop()
        readingJob?.cancel()
        btnReadPause.text = "\u25B6"
        currentPageIndex = 0
        tvStatus.text = "Stopped"
        updatePageCounter(0)
        scrollView.smoothScrollTo(0, 0)
    }

    private suspend fun readPages(startIndex: Int) {
        val total = pageBitmaps.size
        for (i in startIndex until total) {
            if (!isReading) break
            currentPageIndex = i
            updatePageCounter(i)
            tvStatus.text = "Scanning..."

            val text = withContext(Dispatchers.IO) { recognizeText(pageBitmaps[i]) }
            if (!isReading) break

            if (text.isNotBlank()) {
                tvStatus.text = "Reading..."
                speakAndWait(text)
                if (!isReading) break

                if (autoScrollEnabled && i + 1 < total) {
                    tvStatus.text = "Scrolling..."
                    val nextView = pagesContainer.getChildAt(i + 1)
                    if (nextView != null) animatedScrollTo(nextView.top, scrollDuration)
                }
            } else {
                currentPageIndex = i + 1
                updatePageCounter((i + 1).coerceAtMost(total - 1))
                tvStatus.text = "No text. Scroll manually then tap Play"
                isReading = false
                btnReadPause.text = "\u25B6"
                break
            }
        }

        if (isReading) {
            tvStatus.text = "Done!"
            isReading = false
            currentPageIndex = 0
            btnReadPause.text = "\u25B6"
        }
    }

    private suspend fun animatedScrollTo(targetY: Int, durationMs: Long): Unit =
        suspendCoroutine { cont ->
            val startY = scrollView.scrollY
            if (startY == targetY) { cont.resume(Unit); return@suspendCoroutine }
            val animator = ValueAnimator.ofInt(startY, targetY).apply {
                duration = durationMs
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener { scrollView.scrollTo(0, it.animatedValue as Int) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { cont.resume(Unit) }
                    override fun onAnimationCancel(animation: Animator) { cont.resume(Unit) }
                })
            }
            scrollView.post { animator.start() }
        }

    private suspend fun recognizeText(bmp: Bitmap): String = suspendCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resume("") }
    }

    private suspend fun speakAndWait(text: String): Unit = suspendCoroutine { cont ->
        val uid = "page_${System.currentTimeMillis()}"
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?)  { if (id == uid) cont.resume(Unit) }
            override fun onError(id: String?) { if (id == uid) cont.resume(Unit) }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.ENGLISH
            tts?.setSpeechRate(ttsSpeed)
            if (pageBitmaps.isNotEmpty()) tvStatus.text = "Ready"
        } else {
            tvStatus.text = "TTS failed"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        readingJob?.cancel()
        tts?.shutdown()
        recognizer.close()
        pageBitmaps.forEach { it.recycle() }
        pageBitmaps.clear()
    }
}
