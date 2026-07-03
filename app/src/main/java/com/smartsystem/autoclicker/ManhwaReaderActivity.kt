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
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Manhwa Reader Activity — bubble-aware TTS reader.
 *
 * After loading a PDF or image file:
 *   1. Each page is rendered and displayed.
 *   2. BubbleDetector runs on every page (background IO) and detected speech
 *      bubbles are immediately outlined with green ESP-style borders via
 *      BubbleOverlayView.
 *   3. Pressing ▶ walks through every page in reading order:
 *        – OCRs ALL bubbles on the page first (no TTS yet)
 *        – Concatenates the non-empty texts into ONE utterance per page
 *        – Auto-scrolls to the first bubble with text
 *        – Speaks the whole page as a single smooth TTS utterance
 *        – Then moves to the next page
 *
 *   This "page-at-a-time" speech strategy eliminates choppy, interrupted
 *   voice caused by many short per-bubble TTS calls on action-heavy pages.
 */
class ManhwaReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── Views ─────────────────────────────────────────────────────────────────
    private lateinit var scrollView:     ScrollView
    private lateinit var pagesContainer: LinearLayout
    private lateinit var tvStatus:       TextView
    private lateinit var tvPageCount:    TextView
    private lateinit var btnReadPause:   Button
    private lateinit var btnStop:        Button
    private lateinit var btnAutoScroll:  ToggleButton
    private lateinit var btnSettings:    Button
    private lateinit var readingHighlight: View

    // ── TTS ───────────────────────────────────────────────────────────────────
    private var tts:           TextToSpeech? = null
    private var ttsReady       = false
    private var ttsSpeed       = 1.0f
    private var scrollDuration = 1500L
    private var selectedVoice: Voice? = null

    // ── Playback state ────────────────────────────────────────────────────────
    private var isReading        = false
    private var autoScrollEnabled = true
    private var readingJob: Job? = null

    // ── Page data ─────────────────────────────────────────────────────────────
    private val pageBitmaps  = mutableListOf<Bitmap>()
    private var currentPageIndex = 0

    // ── Bubble data ───────────────────────────────────────────────────────────
    private val pageBubbles   = mutableListOf<List<BubbleInfo>>()
    private val overlayViews  = mutableListOf<BubbleOverlayView>()
    private val pageImageViews = mutableListOf<ImageView>()

    // ── ML Kit ────────────────────────────────────────────────────────────────
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ─────────────────────────────────────────────────────────────────────────

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
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(Intent.EXTRA_STREAM)

        if (uri == null) { tvStatus.text = "No file"; return }
        tvStatus.text = "Loading…"

        lifecycleScope.launch {
            val bitmaps = withContext(Dispatchers.IO) {
                val mime   = contentResolver.getType(uri) ?: ""
                val isPdf  = mime.contains("pdf", ignoreCase = true) ||
                             uri.lastPathSegment?.endsWith(".pdf", ignoreCase = true) == true
                if (isPdf) loadPdfPages(uri) else loadImagePage(uri)
            }

            if (bitmaps.isEmpty()) { tvStatus.text = "Load failed"; return@launch }

            pageBitmaps.clear()
            pageBitmaps.addAll(bitmaps)
            pageBubbles.clear()
            overlayViews.clear()
            pageImageViews.clear()
            pagesContainer.removeAllViews()

            for (bmp in bitmaps) {
                val iv = ImageView(this@ManhwaReaderActivity).apply {
                    setImageBitmap(bmp)
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }

                val overlay = BubbleOverlayView(this@ManhwaReaderActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    srcWidth  = bmp.width
                    srcHeight = bmp.height
                }

                val frame = FrameLayout(this@ManhwaReaderActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    addView(iv)
                    addView(overlay)
                }

                pagesContainer.addView(frame)
                pageImageViews.add(iv)
                overlayViews.add(overlay)
                pageBubbles.add(emptyList())
            }

            updatePageCounter(0)
            tvStatus.text = "Detecting bubbles…"

            for ((idx, bmp) in bitmaps.withIndex()) {
                val detected = withContext(Dispatchers.IO) { BubbleDetector.detect(bmp) }
                pageBubbles[idx] = detected
                overlayViews.getOrNull(idx)?.setBubbles(detected)
            }

            val total = pageBubbles.sumOf { it.size }
            tvStatus.text = if (total > 0) "$total bubbles — tap ▶ to read" else "Tap ▶ to read"
        }
    }

    private fun updatePageCounter(index: Int) {
        val total = pageBitmaps.size
        tvPageCount.text = if (total > 0) "${index + 1}/$total" else ""
    }

    // ── PDF / Image loading ───────────────────────────────────────────────────

    private fun loadPdfPages(uri: Uri): List<Bitmap> {
        val bitmaps  = mutableListOf<Bitmap>()
        var fd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer?    = null
        try {
            fd       = openPdfDescriptor(uri) ?: return emptyList()
            renderer = PdfRenderer(fd)
            val targetW = minOf(resources.displayMetrics.widthPixels, 720)
            for (i in 0 until renderer.pageCount) {
                val page  = renderer.openPage(i)
                val scale = targetW.toFloat() / page.width.coerceAtLeast(1)
                val pageH = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp   = Bitmap.createBitmap(targetW, pageH, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmaps.add(bmp)
            }
        } catch (_: Exception) {
        } finally {
            renderer?.close()
            fd?.close()
        }
        return bitmaps
    }

    private fun loadImagePage(uri: Uri): List<Bitmap> {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            val targetW = minOf(resources.displayMetrics.widthPixels, 720)
            val sample  = maxOf(1, opts.outWidth / targetW)
            val decOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp     = contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, decOpts) }
            if (bmp != null) listOf(bmp) else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun openPdfDescriptor(uri: Uri): ParcelFileDescriptor? {
        return try {
            contentResolver.openFileDescriptor(uri, "r")
        } catch (_: Exception) {
            try {
                val tmp = File(cacheDir, "manhwa_tmp.pdf")
                contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
                ParcelFileDescriptor.open(tmp, ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (_: Exception) { null }
        }
    }

    // ── Playback controls ─────────────────────────────────────────────────────

    private fun startReading() {
        if (!ttsReady) { Toast.makeText(this, "TTS not ready", Toast.LENGTH_SHORT).show(); return }
        if (pageBitmaps.isEmpty()) { Toast.makeText(this, "No pages loaded", Toast.LENGTH_SHORT).show(); return }

        val sy = scrollView.scrollY
        for (i in pageBitmaps.indices) {
            val v = pagesContainer.getChildAt(i) ?: continue
            if (sy >= v.top && sy < v.top + v.height) { currentPageIndex = i; break }
        }

        isReading = true
        btnReadPause.text = "\u23F8"
        readingHighlight.visibility = View.VISIBLE
        tvStatus.text = "Reading…"
        readingJob = lifecycleScope.launch { readAllBubbles() }
    }

    private fun pauseReading() {
        isReading = false
        tts?.stop()
        readingJob?.cancel()
        btnReadPause.text = "\u25B6"
        tvStatus.text = "Paused"
        readingHighlight.visibility = View.GONE
        clearAllActiveHighlights()
    }

    private fun stopReading() {
        isReading = false
        tts?.stop()
        readingJob?.cancel()
        btnReadPause.text = "\u25B6"
        currentPageIndex = 0
        tvStatus.text = "Stopped"
        updatePageCounter(0)
        readingHighlight.visibility = View.GONE
        clearAllActiveHighlights()
        scrollView.smoothScrollTo(0, 0)
    }

    private fun clearAllActiveHighlights() {
        for (ov in overlayViews) ov.setActiveBubble(-1)
    }

    // ── Core reading loop ─────────────────────────────────────────────────────

    private suspend fun readAllBubbles() {
        while (isReading && currentPageIndex < pageBitmaps.size) {
            updatePageCounter(currentPageIndex)
            readBubblesOnPage(currentPageIndex)
            if (!isReading) return
            currentPageIndex++
        }

        if (isReading) {
            withContext(Dispatchers.Main) {
                tvStatus.text = "Done! ✓"
                isReading = false
                btnReadPause.text = "\u25B6"
                readingHighlight.visibility = View.GONE
                currentPageIndex = 0
            }
        }
    }

    /**
     * Read all speech bubbles on [pageIdx] as a SINGLE smooth TTS utterance.
     *
     * Flow:
     *   1. OCR every detected bubble on the page (IO, sequential).
     *   2. Collect non-empty texts in reading order (top→bottom, left→right).
     *   3. Highlight all bubbles that have text simultaneously.
     *   4. Auto-scroll to the first bubble with text.
     *   5. Speak the joined text as ONE utterance → no choppy interruptions.
     *   6. Clear highlights and move to next page.
     *
     * Pages with zero detected bubbles get a brief pause and are skipped.
     * Pages where all detected bubbles produce empty OCR are also skipped silently.
     */
    private suspend fun readBubblesOnPage(pageIdx: Int) {
        val bubbles   = pageBubbles.getOrNull(pageIdx) ?: return
        val overlay   = overlayViews.getOrNull(pageIdx) ?: return
        val srcBitmap = pageBitmaps.getOrNull(pageIdx)  ?: return
        val pageFrame = pagesContainer.getChildAt(pageIdx) ?: return

        if (bubbles.isEmpty()) {
            delay(300)
            return
        }

        // ── Step 1: OCR all bubbles on this page ──────────────────────────────
        withContext(Dispatchers.Main) {
            tvStatus.text = "Page ${pageIdx + 1}: reading…"
        }

        val ocrResults = bubbles.mapIndexed { idx, bubbleInfo ->
            if (!isReading) return
            val rawText = withContext(Dispatchers.IO) {
                ocrBubbleCrop(srcBitmap, bubbleInfo.rect)
            }
            // Collapse "V I C T O R I O U S" → "VICTORIOUS" from letter-by-letter OCR
            val text = cleanOcrText(rawText)
            idx to text
        }

        if (!isReading) return

        // ── Step 2: Collect bubbles with real text (≥3 consecutive letters) ───
        // hasRealContent rejects single chars, punctuation, and OCR garbage from
        // gradient/artwork regions that contain no actual speech text.
        val nonEmpty = ocrResults.filter { (_, t) -> hasRealContent(t) }
        if (nonEmpty.isEmpty()) {
            delay(200)
            return
        }

        // ── Step 3: Highlight all bubbles that have text ───────────────────────
        withContext(Dispatchers.Main) {
            for ((idx, _) in nonEmpty) {
                overlay.setActiveBubble(idx)   // overlay supports one "active" at a time;
                                                // show the first one
                break
            }
        }

        // ── Step 4: Scroll to the first bubble with text ──────────────────────
        if (autoScrollEnabled) {
            val firstBubbleIdx = nonEmpty.first().first
            val firstRect = bubbles[firstBubbleIdx].rect
            // Wait for the page frame to be laid out if it hasn't been measured yet
            withContext(Dispatchers.Main) {
                if (pageFrame.height == 0) delay(350)
            }
            val frameH = pageFrame.height.coerceAtLeast(1)
            val scaleY = frameH.toFloat() / srcBitmap.height.coerceAtLeast(1)
            val bubbleCentreInPage = ((firstRect.top + firstRect.bottom) / 2f * scaleY).toInt()
            val targetScrollY = (pageFrame.top + bubbleCentreInPage - scrollView.height / 2)
                .coerceAtLeast(0)
            withContext(Dispatchers.Main) {
                animatedScrollTo(targetScrollY, scrollDuration)
            }
        }

        if (!isReading) return

        // ── Step 5: Speak ALL bubble text as ONE smooth utterance ─────────────
        // Texts are joined with a comma-space so TTS produces a natural pause
        // between bubbles without restarting the engine.
        val fullText = nonEmpty.joinToString(", ") { (_, t) -> t }
        withContext(Dispatchers.Main) {
            tvStatus.text = "Page ${pageIdx + 1} — ${nonEmpty.size} bubble(s)"
        }
        speakAndWait(fullText)

        // ── Step 6: Clear highlights ──────────────────────────────────────────
        withContext(Dispatchers.Main) {
            overlay.setActiveBubble(-1)
        }

        delay(300) // brief gap before next page
    }

    // ── OCR helpers ───────────────────────────────────────────────────────────

    /**
     * Collapse letter-by-letter OCR artifacts where stylised fonts cause ML Kit
     * to return each character as a separate token, e.g.:
     *   "V I C T O R I O U S"  →  "VICTORIOUS"
     *   "H O U N D"            →  "HOUND"
     * A line is collapsed only when every space-separated token is a single
     * character (at least 3 tokens), so normal words are never affected.
     */
    private fun cleanOcrText(raw: String): String {
        return raw.lines().joinToString("\n") { line ->
            val tokens = line.trim().split(" ").filter { it.isNotEmpty() }
            if (tokens.size >= 3 && tokens.all { it.length == 1 }) {
                tokens.joinToString("")
            } else {
                line
            }
        }.trim()
    }

    /**
     * Returns true if [text] contains at least one real word — three or more
     * consecutive ASCII letters.  Rejects random single characters, punctuation
     * strings, or short OCR garbage from gradient/artwork regions.
     */
    private fun hasRealContent(text: String): Boolean =
        text.contains(Regex("[A-Za-z]{3,}"))

    private suspend fun ocrBubbleCrop(src: Bitmap, bubbleRect: Rect): String {
        val pad = 12
        val cropRect = Rect(
            (bubbleRect.left  - pad).coerceAtLeast(0),
            (bubbleRect.top   - pad).coerceAtLeast(0),
            (bubbleRect.right + pad).coerceAtMost(src.width),
            (bubbleRect.bottom + pad).coerceAtMost(src.height)
        )
        if (cropRect.width() <= 0 || cropRect.height() <= 0) return ""

        val crop = Bitmap.createBitmap(
            src, cropRect.left, cropRect.top, cropRect.width(), cropRect.height()
        )

        return try {
            val result = suspendCancellableCoroutine<String> { cont ->
                val image = InputImage.fromBitmap(crop, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        val text = visionText.text.trim()
                        if (cont.isActive) cont.resume(text)
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume("")
                    }
                    .addOnCanceledListener {
                        if (cont.isActive) cont.resume("")
                    }
            }
            result
        } catch (_: Exception) {
            ""
        } finally {
            crop.recycle()
        }
    }

    // ── Auto-scroll ───────────────────────────────────────────────────────────

    private suspend fun animatedScrollTo(targetY: Int, durationMs: Long): Unit =
        suspendCancellableCoroutine { cont ->
            val startY = scrollView.scrollY
            if (startY == targetY) { cont.resume(Unit); return@suspendCancellableCoroutine }
            val resumed = AtomicBoolean(false)
            fun finishOnce() { if (resumed.compareAndSet(false, true)) cont.resume(Unit) }

            val anim = ValueAnimator.ofInt(startY, targetY).apply {
                duration = durationMs
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { scrollView.scrollTo(0, it.animatedValue as Int) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator)    { finishOnce() }
                    override fun onAnimationCancel(a: Animator) { finishOnce() }
                })
            }
            cont.invokeOnCancellation { anim.cancel() }
            scrollView.post { anim.start() }
        }

    // ── TTS ───────────────────────────────────────────────────────────────────

    private suspend fun speakAndWait(text: String) {
        if (tts == null || !ttsReady || text.isBlank()) return
        withTimeoutOrNull(120_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val uid  = "bubble_${System.currentTimeMillis()}"
                val done = AtomicBoolean(false)
                fun finishOnce() { if (done.compareAndSet(false, true)) cont.resume(Unit) }

                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?)  { if (id == uid) finishOnce() }
                    override fun onError(id: String?) { if (id == uid) finishOnce() }
                })
                val result = tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
                if (result == TextToSpeech.ERROR) finishOnce()
                cont.invokeOnCancellation { tts?.stop() }
            }
        } ?: tts?.stop()
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

    // ── Settings dialog ───────────────────────────────────────────────────────

    private fun showSettingsDialog() {
        if (isReading) pauseReading()

        val speedLabels  = arrayOf("Slow (0.75×)", "Normal (1.0×)", "Fast (1.5×)", "Very Fast (2.0×)")
        val speedValues  = floatArrayOf(0.75f, 1.0f, 1.5f, 2.0f)
        val scrollLabels = arrayOf("Very Slow (4 s)", "Slow (2.5 s)", "Normal (1.5 s)", "Fast (0.7 s)")
        val scrollValues = longArrayOf(4000L, 2500L, 1500L, 700L)

        var curSpeedIdx = 1
        for (i in speedValues.indices) { if (speedValues[i] == ttsSpeed) { curSpeedIdx = i; break } }
        var curScrollIdx = 2
        for (i in scrollValues.indices) { if (scrollValues[i] == scrollDuration) { curScrollIdx = i; break } }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }

        inner.addView(TextView(this).apply {
            text = "Reading Speed"; textSize = 13f
            setTypeface(null, Typeface.BOLD); setPadding(0, 0, 0, 4)
        })
        val speedGroup = RadioGroup(this)
        for (i in speedLabels.indices) {
            speedGroup.addView(RadioButton(this).apply {
                text = speedLabels[i]; id = 100 + i; isChecked = (i == curSpeedIdx)
            })
        }
        inner.addView(speedGroup)

        inner.addView(TextView(this).apply {
            text = "Auto-Scroll Speed"; textSize = 13f
            setTypeface(null, Typeface.BOLD); setPadding(0, 20, 0, 4)
        })
        val scrollGroup = RadioGroup(this)
        for (i in scrollLabels.indices) {
            scrollGroup.addView(RadioButton(this).apply {
                text = scrollLabels[i]; id = 200 + i; isChecked = (i == curScrollIdx)
            })
        }
        inner.addView(scrollGroup)

        val englishVoices = tts?.voices
            ?.filter { it.locale.language == "en" }
            ?.sortedBy { it.name }
            ?: emptyList()
        var voiceSpinner: Spinner? = null
        if (englishVoices.isNotEmpty()) {
            inner.addView(TextView(this).apply {
                text = "Voice"; textSize = 13f
                setTypeface(null, Typeface.BOLD); setPadding(0, 20, 0, 4)
            })
            val spinner = Spinner(this)
            val names = englishVoices.map { v ->
                "${v.name.replace(Regex("[-_]"), " ")} (${v.locale.toLanguageTag()})"
            }
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinner.setSelection(
                englishVoices.indexOfFirst { it.name == selectedVoice?.name }.coerceAtLeast(0)
            )
            inner.addView(spinner)
            voiceSpinner = spinner
        }

        AlertDialog.Builder(this)
            .setTitle("Reader Settings")
            .setView(ScrollView(this).apply { addView(inner) })
            .setPositiveButton("Apply") { _, _ ->
                val si = speedGroup.checkedRadioButtonId - 100
                if (si in speedValues.indices) { ttsSpeed = speedValues[si]; tts?.setSpeechRate(ttsSpeed) }
                val sc = scrollGroup.checkedRadioButtonId - 200
                if (sc in scrollValues.indices) scrollDuration = scrollValues[sc]
                voiceSpinner?.let { sp ->
                    val vi = sp.selectedItemPosition
                    if (vi in englishVoices.indices) { selectedVoice = englishVoices[vi]; tts?.voice = selectedVoice }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        readingJob?.cancel()
        tts?.shutdown()
        recognizer.close()
        for (bmp in pageBitmaps) bmp.recycle()
        pageBitmaps.clear()
    }
}
