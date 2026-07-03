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
 * Fixes applied (July 2026):
 *  1. ESP false positives: OCR-filter during load — only bubbles with real text
 *     are kept in pageBubbles and shown in the overlay.
 *  2. Auto-scroll: always scrolls to each page on transition; then scrolls to
 *     each individual bubble as it is read (bubble-by-bubble, not page-at-a-time).
 *  3. Dark bubble detection: BubbleDetector now finds black boxes; OCR pipeline
 *     auto-inverts dark crops before recognition for better accuracy.
 *  4. Single-letter collapse: cleanOcrText uses a 70%-single-char heuristic so
 *     punctuation-adjacent tokens (e.g. "I,") don't break the collapse.
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
    /** Only contains bubbles confirmed to have real OCR text (no false positives). */
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
            tvStatus.text = "Scanning bubbles…"

            // Detect bubbles on each page, then OCR-filter to remove false positives.
            // Only bubbles that contain real text are kept — this eliminates ESP boxes
            // around artwork / gradient regions that have no speech text.
            for ((idx, bmp) in bitmaps.withIndex()) {
                val detected = withContext(Dispatchers.IO) { BubbleDetector.detect(bmp) }

                // OCR-filter: keep only bubbles with real readable text
                val confirmed = mutableListOf<BubbleInfo>()
                for (bubble in detected) {
                    val rawText = withContext(Dispatchers.IO) { ocrBubbleCropRaw(bmp, bubble.rect) }
                    val text    = cleanOcrText(rawText)
                    if (hasRealContent(text)) confirmed.add(bubble)
                }

                pageBubbles[idx] = confirmed
                overlayViews.getOrNull(idx)?.setBubbles(confirmed)

                val donePages = idx + 1
                tvStatus.text = "Scanned $donePages/${bitmaps.size}…"
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

        // Determine current page from scroll position
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
            withContext(Dispatchers.Main) { updatePageCounter(currentPageIndex) }
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
     * Read speech bubbles on [pageIdx] one by one, scrolling to each bubble
     * before speaking it.
     *
     * Flow:
     *   1. Always smooth-scroll to show the current page (even if it has no bubbles).
     *   2. For each confirmed bubble (pre-filtered to only text bubbles):
     *        a. Scroll to center the bubble on screen.
     *        b. Highlight it in the overlay.
     *        c. OCR the crop (with auto-enhancement for dark bubbles).
     *        d. Speak the cleaned text.
     *        e. Remove highlight.
     *   3. Brief gap, then caller increments to the next page.
     *
     * Empty pages (no bubbles) scroll into view, pause briefly, and continue.
     */
    private suspend fun readBubblesOnPage(pageIdx: Int) {
        val bubbles   = pageBubbles.getOrNull(pageIdx) ?: return
        val overlay   = overlayViews.getOrNull(pageIdx) ?: return
        val srcBitmap = pageBitmaps.getOrNull(pageIdx)  ?: return
        val pageFrame = pagesContainer.getChildAt(pageIdx) ?: return

        // Wait for the page frame to be laid out (first-run / large pages)
        withContext(Dispatchers.Main) {
            var waited = 0
            while (pageFrame.height == 0 && waited < 2000) {
                delay(50); waited += 50
            }
        }

        // ALWAYS scroll to bring the top of this page into view — no page skipping
        if (autoScrollEnabled) {
            val pageTop = pageFrame.top.coerceAtLeast(0)
            withContext(Dispatchers.Main) { animatedScrollTo(pageTop, scrollDuration) }
        }

        if (bubbles.isEmpty()) {
            // Page has no text bubbles — pause briefly and move on without skipping
            delay(400)
            return
        }

        withContext(Dispatchers.Main) {
            tvStatus.text = "Page ${pageIdx + 1} — ${bubbles.size} bubble(s)"
        }

        // Read each bubble individually with scroll + highlight + TTS
        for ((bubbleIdx, bubbleInfo) in bubbles.withIndex()) {
            if (!isReading) return

            // Scroll to center this bubble on screen
            if (autoScrollEnabled) {
                withContext(Dispatchers.Main) {
                    val frameH = pageFrame.height.coerceAtLeast(1)
                    val scaleY = frameH.toFloat() / srcBitmap.height.coerceAtLeast(1)
                    val bubbleCentre = ((bubbleInfo.rect.top + bubbleInfo.rect.bottom) / 2f * scaleY).toInt()
                    val targetY = (pageFrame.top + bubbleCentre - scrollView.height / 2).coerceAtLeast(0)
                    animatedScrollTo(targetY, scrollDuration)
                }
            }

            if (!isReading) return

            // Highlight this bubble in the overlay
            withContext(Dispatchers.Main) { overlay.setActiveBubble(bubbleIdx) }

            // OCR with auto-enhancement (handles dark/black bubbles automatically)
            val rawText = withContext(Dispatchers.IO) { ocrBubbleCropRaw(srcBitmap, bubbleInfo.rect) }
            val text    = cleanOcrText(rawText)

            if (!hasRealContent(text)) {
                // Bubble was filtered at load time but re-checking here is safe
                withContext(Dispatchers.Main) { overlay.setActiveBubble(-1) }
                continue
            }

            if (!isReading) return

            // Speak the bubble text
            speakAndWait(text)

            withContext(Dispatchers.Main) { overlay.setActiveBubble(-1) }

            if (!isReading) return
            delay(120) // brief natural gap between bubbles
        }

        delay(250) // gap before next page
    }

    // ── OCR helpers ───────────────────────────────────────────────────────────

    /**
     * Crop the bubble region from [src], auto-enhance for dark bubbles (invert),
     * then run ML Kit OCR and return the raw text string.
     *
     * Dark bubble enhancement: if the sampled center brightness of the crop is
     * below 80, the image is inverted (white text on black → black text on white)
     * which dramatically improves ML Kit text recognition accuracy.
     */
    private suspend fun ocrBubbleCropRaw(src: Bitmap, bubbleRect: Rect): String {
        val pad = 14
        val cropRect = Rect(
            (bubbleRect.left  - pad).coerceAtLeast(0),
            (bubbleRect.top   - pad).coerceAtLeast(0),
            (bubbleRect.right + pad).coerceAtMost(src.width),
            (bubbleRect.bottom + pad).coerceAtMost(src.height)
        )
        if (cropRect.width() <= 0 || cropRect.height() <= 0) return ""

        val rawCrop = Bitmap.createBitmap(
            src, cropRect.left, cropRect.top, cropRect.width(), cropRect.height()
        )

        // Auto-detect dark bubble and invert for better OCR accuracy
        val crop = autoEnhanceForOcr(rawCrop)

        return try {
            suspendCancellableCoroutine { cont ->
                val image = InputImage.fromBitmap(crop, 0)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (cont.isActive) cont.resume(visionText.text.trim())
                    }
                    .addOnFailureListener {
                        if (cont.isActive) cont.resume("")
                    }
                    .addOnCanceledListener {
                        if (cont.isActive) cont.resume("")
                    }
            }
        } catch (_: Exception) {
            ""
        } finally {
            if (crop !== rawCrop) crop.recycle()
            rawCrop.recycle()
        }
    }

    /**
     * If the crop's center region is dark (avg brightness < 80), return a
     * pixel-inverted copy so that white-on-black text becomes black-on-white,
     * which ML Kit handles far better.  Otherwise returns [src] unchanged
     * (no copy, no recycle — caller recycles [src]).
     */
    private fun autoEnhanceForOcr(src: Bitmap): Bitmap {
        val cx = src.width / 2; val cy = src.height / 2
        val sw = maxOf(1, src.width / 4);  val sh = maxOf(1, src.height / 4)
        var sum = 0L; var count = 0
        val xStep = maxOf(1, sw / 5); val yStep = maxOf(1, sh / 5)
        var y = (cy - sh).coerceAtLeast(0)
        while (y <= (cy + sh).coerceAtMost(src.height - 1)) {
            var x = (cx - sw).coerceAtLeast(0)
            while (x <= (cx + sw).coerceAtMost(src.width - 1)) {
                val p = src.getPixel(x, y)
                sum += (Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f).toLong()
                count++
                x += xStep
            }
            y += yStep
        }
        val avgBright = if (count > 0) sum / count else 128L
        if (avgBright >= 80L) return src   // bright bubble — no enhancement needed

        // Dark bubble: invert pixels
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = Color.argb(
                Color.alpha(p),
                255 - Color.red(p),
                255 - Color.green(p),
                255 - Color.blue(p)
            )
        }
        val inverted = Bitmap.createBitmap(src.width, src.height,
            src.config ?: Bitmap.Config.ARGB_8888)
        inverted.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return inverted
    }

    /**
     * Collapse letter-by-letter OCR artifacts where stylised fonts cause ML Kit
     * to return each character as a separate token, e.g.:
     *   "V I K I R V A N"    →  "VIKIR VAN"
     *   "H O U N D"          →  "HOUND"
     *   "I , V I K I R"      →  "I,VIKIR"   (handles punctuation-adjacent tokens)
     *
     * Detection heuristic: a line is collapsed when ≥ 70% of its tokens are
     * single characters (letters OR punctuation).  This tolerates tokens like
     * "I," (letter + comma = length 2) that break the old all-length-1 check.
     *
     * Collapse strategy: merge consecutive runs of single-letter tokens into
     * one word; single-punctuation tokens attach to the previous group; multi-
     * character tokens (if any survive) are kept as-is separated by spaces.
     */
    /**
     * Clean raw ML Kit OCR output into natural, speakable text.
     *
     * Handles all common manhwa font OCR artifacts in order:
     *  1. Char-per-line  — ML Kit returns "V\nI\nK\nI\nR" → collapsed to "VIKIR"
     *  2. Space-per-char — "V I K I R" on one line → "VIKIR"
     *  3. Stutter        — "f-find", "a-ahh", "w-wait" → "find", "ahh", "wait"
     *  4. Ellipsis       — "...", "…" → removed (not read aloud)
     *  5. Lone letters   — any remaining isolated single chars stripped from output
     *
     * hasRealContent() is the final gate: text with no real word is discarded.
     */
    private fun cleanOcrText(raw: String): String {
        if (raw.isBlank()) return ""

        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return ""

        // ── Step 1: Detect char-per-line (one glyph per line from stylised fonts) ──
        // e.g. ML Kit returns "V\nI\nK\nI\nR" instead of "VIKIR"
        val letterLineCount = lines.count { it.length == 1 && it[0].isLetter() }
        val processedLines: List<String> =
            if (lines.size >= 3 && letterLineCount.toFloat() / lines.size >= 0.55f) {
                // Entire text is char-per-line → collapse all into words
                listOf(collapseCharLines(lines))
            } else {
                // Normal text → try per-line space-collapse ("V I K I R" → "VIKIR")
                lines.map { collapseSpacedLetters(it) }
            }

        // ── Step 2: Join lines, clean up TTS-unfriendly artifacts ───────────────
        val joined  = processedLines.filter { it.isNotBlank() }.joinToString(" ")
        val cleaned = cleanupOcrArtifacts(joined)

        // ── Step 3: Strip remaining lone single letters ───────────────────────────
        // After all collapsing a leftover "V" or "I" token is never valid speech.
        val finalTokens = cleaned.split(Regex("\\s+")).filter { token ->
            token.isNotEmpty() &&
            !(token.length == 1 && token[0].isLetter())   // drop lone letter tokens
        }
        return finalTokens.joinToString(" ").trim()
    }

    /**
     * Collapse one-letter-per-line runs into words.
     *
     *   ["V","I","K","I","R"]      → "VIKIR"
     *   ["N","O","NEED","T","O"]   → "NO NEED TO"
     *   ["V","I","K","I","R","!"]  → "VIKIR!"
     *
     * Multi-char lines are treated as full words and passed through
     * [collapseSpacedLetters] for a second-pass space-collapse.
     */
    private fun collapseCharLines(lines: List<String>): String {
        val words      = mutableListOf<String>()
        val currentRun = StringBuilder()

        fun flushRun() {
            if (currentRun.isNotEmpty()) {
                words.add(currentRun.toString())
                currentRun.clear()
            }
        }

        for (line in lines) {
            when {
                line.length == 1 && line[0].isLetter() ->
                    // Single letter: add to running word
                    currentRun.append(line[0])

                line.length == 1 && !line[0].isLetter() -> {
                    // Single punctuation: attach to current run, then flush
                    if (currentRun.isNotEmpty()) {
                        currentRun.append(line[0])
                        flushRun()
                    }
                    // lone punctuation with no preceding run → skip
                }

                else -> {
                    // Multi-char line: flush any pending run, add line as word
                    flushRun()
                    val collapsed = collapseSpacedLetters(line)
                    if (collapsed.isNotBlank()) words.add(collapsed)
                }
            }
        }
        flushRun()
        return words.joinToString(" ")
    }

    /**
     * Collapse space-separated single letters on ONE line: "V I K I R" → "VIKIR".
     *
     * Triggers when ≥55% of tokens are single-char (length 1, or length-2 mixed
     * letter+punctuation like "I,") and there are ≥3 tokens.
     */
    private fun collapseSpacedLetters(line: String): String {
        val tokens = line.trim().split(" ").filter { it.isNotEmpty() }
        if (tokens.size < 3) return line

        val singleCount = tokens.count { t ->
            t.length == 1 ||
            (t.length == 2 && t.any { it.isLetter() } && t.any { !it.isLetterOrDigit() })
        }
        if (singleCount.toFloat() / tokens.size < 0.55f) return line

        val sb = StringBuilder()
        for (t in tokens) {
            val isOneLetter = t.length == 1 && t[0].isLetter()
            val isOnePunct  = t.length == 1 && !t[0].isLetter()
            val isMixed     = t.length == 2 &&
                t.any { it.isLetter() } && t.any { !it.isLetterOrDigit() }
            when {
                isOneLetter -> sb.append(t[0])          // merge into letter run
                isOnePunct  -> sb.append(t[0])          // attach punctuation directly
                isMixed     -> sb.append(t)             // "I," → attach as-is
                else        -> {                        // full word
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                    sb.append(t).append(' ')
                }
            }
        }
        return sb.toString().trim()
    }

    /**
     * Remove OCR artifacts that are unpleasant or meaningless when spoken aloud:
     *   • Stutter  — "f-find" → "find",  "w-wait" → "wait",  "a-ahh" → "ahh"
     *   • Ellipsis — "..." / ".…" / "…" → removed (TTS says "dot dot dot" otherwise)
     *   • Line-break noise and multiple spaces → single space
     */
    private fun cleanupOcrArtifacts(text: String): String {
        var s = text
        // Stutter: single letter + hyphen + word (keep the word, drop the stutter)
        s = s.replace(Regex("""(?i)\b[a-z]-([a-z]{2,})\b"""), "$1")
        // Any run of 2+ dots or Unicode ellipsis → nothing
        s = s.replace(Regex("""[.…]{2,}"""), "")
        // Normalise line breaks and excess whitespace
        s = s.replace(Regex("""[\n\r]+"""), " ")
        s = s.replace(Regex("""\s{2,}"""), " ")
        return s.trim()
    }

    /**
     * Returns true only when [text] contains at least one real word
     * (three or more consecutive ASCII letters).
     * Rejects lone chars, punctuation strings, and short OCR noise from artwork.
     */
    private fun hasRealContent(text: String): Boolean =
        text.contains(Regex("[A-Za-z]{3,}"))

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
