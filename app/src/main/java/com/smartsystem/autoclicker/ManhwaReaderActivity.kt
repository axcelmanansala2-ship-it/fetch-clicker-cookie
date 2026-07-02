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
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
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
    private lateinit var readingHighlight: View

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isReading = false
    private var autoScrollEnabled = true
    private var readingJob: Job? = null
    private val pageBitmaps = mutableListOf<Bitmap>()

    private var currentPageIndex = 0
    private var currentChunkOffset = 0
    private var lastSpokenText = ""

    private var ttsSpeed = 1.0f
    private var scrollDuration = 1500L

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manhwa_reader)

        scrollView       = findViewById(R.id.manhwaScrollView)
        pagesContainer   = findViewById(R.id.pagesContainer)
        tvStatus         = findViewById(R.id.tvManhwaStatus)
        tvPageCount      = findViewById(R.id.tvPageCount)
        btnReadPause     = findViewById(R.id.btnReadPause)
        btnStop          = findViewById(R.id.btnManhwaStop)
        btnAutoScroll    = findViewById(R.id.btnAutoScroll)
        btnSettings      = findViewById(R.id.btnSettings)
        readingHighlight = findViewById(R.id.readingHighlight)

        tts = TextToSpeech(this, this)
        loadFileFromIntent()

        btnReadPause.setOnClickListener  { if (isReading) pauseReading() else startReading() }
        btnStop.setOnClickListener       { stopReading() }
        btnAutoScroll.setOnCheckedChangeListener { _, c -> autoScrollEnabled = c }
        btnSettings.setOnClickListener   { showSettingsDialog() }
    }

    // ── File loading ──────────────────────────────────────────────────────────

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
                val isPdf = mime.contains("pdf", ignoreCase = true) ||
                    uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true
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
        tvPageCount.text = if (total > 0) "${index + 1}/${total}" else ""
    }

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
            // return whatever loaded so far
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

    // ── Settings ──────────────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        if (isReading) pauseReading()

        val speedLabels  = arrayOf("Slow (0.75x)", "Normal (1.0x)", "Fast (1.5x)", "Very Fast (2.0x)")
        val speedValues  = floatArrayOf(0.75f, 1.0f, 1.5f, 2.0f)
        val scrollLabels = arrayOf("Very Slow (4s)", "Slow (2.5s)", "Normal (1.5s)", "Fast (0.7s)")
        val scrollValues = longArrayOf(4000L, 2500L, 1500L, 700L)

        var curSpeedIdx = 1
        for (i in speedValues.indices) { if (speedValues[i] == ttsSpeed) { curSpeedIdx = i; break } }
        var curScrollIdx = 2
        for (i in scrollValues.indices) { if (scrollValues[i] == scrollDuration) { curScrollIdx = i; break } }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }
        layout.addView(TextView(this).apply {
            text = "Reading Speed"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, 4)
        })
        val speedGroup = RadioGroup(this)
        for (i in speedLabels.indices) {
            val rb = RadioButton(this)
            rb.text = speedLabels[i]
            rb.id = 100 + i
            rb.isChecked = (i == curSpeedIdx)
            speedGroup.addView(rb)
        }
        layout.addView(speedGroup)

        layout.addView(TextView(this).apply {
            text = "Auto-Scroll Speed"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 20, 0, 4)
        })
        val scrollGroup = RadioGroup(this)
        for (i in scrollLabels.indices) {
            val rb = RadioButton(this)
            rb.text = scrollLabels[i]
            rb.id = 200 + i
            rb.isChecked = (i == curScrollIdx)
            scrollGroup.addView(rb)
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

    // ── Playback controls ─────────────────────────────────────────────────────

    private fun startReading() {
        if (!ttsReady) { Toast.makeText(this, "TTS not ready", Toast.LENGTH_SHORT).show(); return }
        if (pageBitmaps.isEmpty()) { Toast.makeText(this, "No pages loaded", Toast.LENGTH_SHORT).show(); return }
        syncPositionFromScroll()
        isReading = true
        btnReadPause.text = "\u23F8"
        readingHighlight.visibility = View.VISIBLE
        readingJob = lifecycleScope.launch { readAllPages() }
    }

    private fun pauseReading() {
        isReading = false
        tts?.stop()
        readingJob?.cancel()
        btnReadPause.text = "\u25B6"
        tvStatus.text = "Paused"
        readingHighlight.visibility = View.GONE
        clearPageHighlight()
    }

    private fun stopReading() {
        isReading = false
        tts?.stop()
        readingJob?.cancel()
        btnReadPause.text = "\u25B6"
        currentPageIndex = 0
        currentChunkOffset = 0
        lastSpokenText = ""
        tvStatus.text = "Stopped"
        updatePageCounter(0)
        readingHighlight.visibility = View.GONE
        clearPageHighlight()
        scrollView.smoothScrollTo(0, 0)
    }

    private fun syncPositionFromScroll() {
        val sy = scrollView.scrollY
        for (i in pageBitmaps.indices) {
            val v = pagesContainer.getChildAt(i) ?: continue
            if (sy >= v.top && sy < v.top + v.height) {
                currentPageIndex = i
                currentChunkOffset = if (sy > v.top) sy - v.top else 0
                updatePageCounter(i)
                return
            }
        }
    }

    private fun highlightPage(index: Int) {
        for (i in 0 until pagesContainer.childCount) {
            val color = if (i == index) Color.argb(28, 0, 229, 255) else Color.TRANSPARENT
            pagesContainer.getChildAt(i)?.setBackgroundColor(color)
        }
    }

    private fun clearPageHighlight() {
        for (i in 0 until pagesContainer.childCount) {
            pagesContainer.getChildAt(i)?.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    // ── Core reading loop ────────────────────────────────────────────────────
    // FULL AUTO-SCROLL: never stops automatically. Scrolls continuously through
    // every chunk (with overlap so no text is missed at chunk boundaries).
    // Whenever text is detected in the visible chunk, it pauses scrolling just
    // long enough to speak it, then resumes scrolling. Panels with no text are
    // skipped instantly — no waiting for the user.

    private suspend fun readAllPages() {
        while (isReading && currentPageIndex < pageBitmaps.size) {
            val pageCompleted = readCurrentPage()
            if (!pageCompleted) return  // manually paused / stopped
        }
        if (isReading) {
            tvStatus.text = "Done!"
            isReading = false
            currentPageIndex = 0
            currentChunkOffset = 0
            lastSpokenText = ""
            btnReadPause.text = "\u25B6"
            readingHighlight.visibility = View.GONE
            clearPageHighlight()
        }
    }

    // Returns true if page was fully traversed, false if paused/stopped mid-way
    private suspend fun readCurrentPage(): Boolean {
        val pageIdx = currentPageIndex
        val pageBmp = pageBitmaps.getOrNull(pageIdx) ?: run {
            currentPageIndex++
            currentChunkOffset = 0
            return true
        }
        val pageView = pagesContainer.getChildAt(pageIdx) ?: run {
            currentPageIndex++
            currentChunkOffset = 0
            return true
        }

        if (pageView.height == 0) {
            delay(500)
            if (pageView.height == 0) {
                currentPageIndex++
                currentChunkOffset = 0
                return true
            }
        }

        val pageH   = pageView.height
        val screenH = scrollView.height.coerceAtLeast(1)
        // 20% overlap between consecutive chunks so text sitting on a boundary
        // is never split/missed between two reads
        val overlap = (screenH * 0.2f).toInt().coerceAtLeast(1)
        val step = (screenH - overlap).coerceAtLeast(1)

        highlightPage(pageIdx)
        updatePageCounter(pageIdx)

        var chunkOffset = currentChunkOffset

        while (isReading && chunkOffset < pageH) {
            val chunkEnd = if (chunkOffset + screenH < pageH) chunkOffset + screenH else pageH

            // Scroll so content becomes visible before it is read
            val scrollTarget = if (pageView.top + chunkOffset > 0) pageView.top + chunkOffset else 0
            if (scrollView.scrollY != scrollTarget) {
                animatedScrollTo(scrollTarget, scrollDuration)
            }

            if (!isReading) return false

            // OCR the visible slice (with overlap already baked into chunkOffset math)
            val bmpH   = pageBmp.height
            val bmpTop = ((chunkOffset.toFloat() / pageH) * bmpH).toInt().let { if (it < 0) 0 else if (it >= bmpH) bmpH - 1 else it }
            val bmpEnd = ((chunkEnd.toFloat() / pageH) * bmpH).toInt().let { if (it <= bmpTop) bmpTop + 1 else if (it > bmpH) bmpH else it }
            val sliceH = bmpEnd - bmpTop

            tvStatus.text = "Scanning..."
            val slice = Bitmap.createBitmap(pageBmp, 0, bmpTop, pageBmp.width, sliceH)
            val text  = withContext(Dispatchers.Default) { recognizeText(slice) }
            slice.recycle()

            if (!isReading) return false

            val cleanText = text.trim()
            val isDuplicate = cleanText.isNotBlank() &&
                (cleanText == lastSpokenText ||
                 lastSpokenText.contains(cleanText) ||
                 cleanText.contains(lastSpokenText))

            if (cleanText.isNotBlank() && !isDuplicate) {
                tvStatus.text = "Reading..."
                lastSpokenText = cleanText
                speakAndWait(cleanText)
                if (!isReading) return false
            }
            // No text OR duplicate (overlap re-read) — do NOT stop, just continue

            if (!autoScrollEnabled) {
                tvStatus.text = "Scroll & tap \u25B6"
                isReading = false
                btnReadPause.text = "\u25B6"
                readingHighlight.visibility = View.GONE
                return false
            }

            // Always advance — full auto scroll never halts on empty panels
            chunkOffset += step
            currentChunkOffset = chunkOffset
        }

        // All chunks of this page done
        clearPageHighlight()
        currentPageIndex++
        currentChunkOffset = 0
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun animatedScrollTo(targetY: Int, durationMs: Long): Unit =
        suspendCoroutine { cont ->
            val startY = scrollView.scrollY
            if (startY == targetY) { cont.resume(Unit); return@suspendCoroutine }
            val anim = ValueAnimator.ofInt(startY, targetY).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                addUpdateListener { anim -> scrollView.scrollTo(0, anim.animatedValue as Int) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator)    { cont.resume(Unit) }
                    override fun onAnimationCancel(a: Animator) { cont.resume(Unit) }
                })
            }
            scrollView.post { anim.start() }
        }

    private suspend fun recognizeText(bmp: Bitmap): String = suspendCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result -> cont.resume(result.text) }
            .addOnFailureListener { cont.resume("") }
    }

    private suspend fun speakAndWait(text: String): Unit = suspendCoroutine { cont ->
        val uid = "chunk_${System.currentTimeMillis()}"
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
        for (bmp in pageBitmaps) { bmp.recycle() }
        pageBitmaps.clear()
    }
}
