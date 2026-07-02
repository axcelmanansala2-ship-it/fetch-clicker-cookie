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
import android.graphics.Point
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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ArrayAdapter
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
import kotlin.math.atan2
import kotlin.math.abs

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
    // Stores NORMALISED keys (see normalizeForDedup) so that minor OCR variations
    // of the same visual text — different whitespace, stray punctuation, slight
    // capitalisation differences between two scans of the same bubble — are still
    // recognised as "already spoken" and never re-read.
    private val spokenLines = mutableSetOf<String>()
    // Bottom-most line of the previous chunk that looked cut off mid-sentence
    // (no ending punctuation). Held back instead of spoken so the next,
    // overlapping chunk can bring in the rest of the sentence before it is read.
    private var heldLine: String? = null

    private var ttsSpeed = 1.0f
    private var scrollDuration = 1500L
    private var selectedVoice: Voice? = null

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

    // Decodes the source image at a resolution close to the device's own screen
    // width instead of a fixed inSampleSize=2. Long manhwa strips are often far
    // wider/taller than any phone screen; decoding them at full (or half) size
    // wastes memory and makes every scroll/slice/OCR pass on that bitmap slower.
    // Matches the same target-width approach already used for PDF pages.
    private fun loadImagePage(uri: Uri): List<Bitmap> {
        return try {
            val targetWidth = minOf(resources.displayMetrics.widthPixels, 720)
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
            val srcWidth = boundsOpts.outWidth.coerceAtLeast(1)
            var sampleSize = 1
            while (srcWidth / (sampleSize * 2) >= targetWidth) sampleSize *= 2

            val stream = contentResolver.openInputStream(uri) ?: return emptyList()
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
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

        val innerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(56, 32, 56, 16)
        }

        // ── Reading Speed ──
        innerLayout.addView(TextView(this).apply {
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
        innerLayout.addView(speedGroup)

        // ── Scroll Speed ──
        innerLayout.addView(TextView(this).apply {
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
        innerLayout.addView(scrollGroup)

        // ── Voice ── (show all English voices from the device TTS engine)
        val englishVoices = tts?.voices
            ?.filter { v -> v.locale.language == "en" }
            ?.sortedBy { it.name }
            ?: emptyList()
        var voiceSpinner: Spinner? = null
        if (englishVoices.isNotEmpty()) {
            innerLayout.addView(TextView(this).apply {
                text = "Voice"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setPadding(0, 20, 0, 4)
            })
            val spinner = Spinner(this)
            val voiceNames = englishVoices.map { v ->
                val tag = v.locale.toLanguageTag()
                "${v.name.replace(Regex("[-_]"), " ")} ($tag)"
            }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, voiceNames)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
            val curVoiceIdx = englishVoices.indexOfFirst { it.name == selectedVoice?.name }.coerceAtLeast(0)
            spinner.setSelection(curVoiceIdx)
            innerLayout.addView(spinner)
            voiceSpinner = spinner
        }

        // Wrap in ScrollView so the dialog is scrollable on small screens
        val dialogScroll = ScrollView(this).apply { addView(innerLayout) }

        AlertDialog.Builder(this)
            .setTitle("Reader Settings")
            .setView(dialogScroll)
            .setPositiveButton("Apply") { _, _ ->
                val si = speedGroup.checkedRadioButtonId - 100
                if (si in speedValues.indices) {
                    ttsSpeed = speedValues[si]
                    tts?.setSpeechRate(ttsSpeed)
                }
                val sc = scrollGroup.checkedRadioButtonId - 200
                if (sc in scrollValues.indices) scrollDuration = scrollValues[sc]
                voiceSpinner?.let { sp ->
                    val vi = sp.selectedItemPosition
                    if (vi in englishVoices.indices) {
                        selectedVoice = englishVoices[vi]
                        tts?.voice = selectedVoice
                    }
                }
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
        spokenLines.clear()
        heldLine = null
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
            spokenLines.clear()
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
        // Only reset the "already spoken" history when actually starting this page
        // from the top. readCurrentPage() can be re-entered mid-page (e.g. user
        // paused in manual-scroll mode, or pressed play again partway through),
        // and currentChunkOffset preserves exactly where it left off — but wiping
        // spokenLines/heldLine on every re-entry made the overlap region at that
        // resume point look "new" again, so it got read out loud a second time.
        // Only clear when this is a genuinely fresh page (offset 0).
        if (currentChunkOffset == 0) {
            spokenLines.clear()
            heldLine = null
        }

        var chunkOffset = currentChunkOffset

        while (isReading && chunkOffset < pageH) {
            val chunkEnd = if (chunkOffset + screenH < pageH) chunkOffset + screenH else pageH

            // Scroll target + OCR slice bounds are both known up-front (they only
            // depend on chunkOffset/chunkEnd, not on the scroll actually finishing),
            // so run the scroll animation and OCR at the same time instead of one
            // after another. Previously we waited for the full scroll to finish
            // before even starting OCR, which is exactly the 1-3s "dead air" delay
            // after each scroll — now OCR overlaps with the scroll and the wait is
            // only whichever of the two takes longer.
            val scrollTarget = if (pageView.top + chunkOffset > 0) pageView.top + chunkOffset else 0

            val bmpH   = pageBmp.height
            val bmpTop = ((chunkOffset.toFloat() / pageH) * bmpH).toInt().let { if (it < 0) 0 else if (it >= bmpH) bmpH - 1 else it }
            val bmpEnd = ((chunkEnd.toFloat() / pageH) * bmpH).toInt().let { if (it <= bmpTop) bmpTop + 1 else if (it > bmpH) bmpH else it }
            val sliceH = bmpEnd - bmpTop

            tvStatus.text = "Scanning..."
            // Fire the scroll animation in the background instead of waiting for it
            // to finish before reading — previously this call blocked here for the
            // full scrollDuration (e.g. 1.5s) even when OCR/text was ready almost
            // instantly, which is exactly the "delay before it reads" lag. Now
            // speech starts the moment OCR completes, while the scroll keeps
            // animating underneath it; we only wait for the scroll to truly finish
            // right before computing the NEXT chunk's target, so animations never
            // overlap/collide with each other.
            val scrollJob: Job? = if (scrollView.scrollY != scrollTarget) {
                lifecycleScope.launch { animatedScrollTo(scrollTarget, scrollDuration) }
            } else null
            // Create the bitmap slice on IO — Bitmap.createBitmap() can block the
            // main thread for tens of milliseconds on large pages, causing visible
            // frame drops while the scroll animation is running at the same time.
            val slice = withContext(Dispatchers.IO) {
                Bitmap.createBitmap(pageBmp, 0, bmpTop, pageBmp.width, sliceH)
            }
            val text = recognizeText(slice)
            slice.recycle()

            if (!isReading) { scrollJob?.cancel(); return false }

            // Skip blank lines, known UI/SFX noise words, anything drawn at an angle
            // (tilted/curved text), AND text that is noticeably smaller than the
            // dialogue in this chunk. Real dialogue is drawn large enough to read
            // comfortably; tiny background UI (phone-mockup nav bars, app labels,
            // watermarks) or title text is much smaller by comparison. The size
            // threshold is computed per-chunk from the median line height instead
            // of a fixed pixel value, so it adapts to each manhwa's own art/font
            // scale automatically.
            val candidates = text
                .map { OcrLine(it.text.trim(), it.rotatedDeg, it.heightPx) }
                .filter { it.text.isNotBlank() }
            val knownHeights = candidates.map { it.heightPx }.filter { it > 0 }.sorted()
            // Threshold lowered from 0.45→0.25 so narration boxes (smaller text than
            // speech-bubble dialogue) are not silently filtered out. We still catch
            // truly tiny watermarks / status-bar OCR artefacts (< 25 % of median).
            val minDialogueHeight =
                if (knownHeights.isNotEmpty()) (knownHeights[knownHeights.size / 2] * 0.25f).toInt() else 0
            val allLines = candidates
                .filter { !isNoiseLine(it.text) && abs(it.rotatedDeg) < ROTATED_NOISE_DEG }
                .filter { it.heightPx <= 0 || it.heightPx >= minDialogueHeight }
                .map { it.text }
                .toMutableList()

            // A phrase held back from the PREVIOUS chunk (cut off by the screen edge)
            // must be brought back in now, or it is silently lost forever. The
            // overlapping scroll region normally re-detects the same bubble as a
            // single, now-complete line — if so, that merged line already contains
            // the held text plus its continuation, so we just let it through as-is.
            // If no matching line is found this round (OCR line-split differences
            // between chunks), speak the held text directly so it is never dropped.
            // Tracks whether the held phrase was reinserted "raw" (no continuation
            // found yet) — if so, it must NOT be immediately re-held below, or it
            // would loop forever: reinserted, still lacks terminal punctuation,
            // held again, next chunk reinserted again, held again... forever, so
            // it never actually gets spoken (feels like the line was "skipped").
            var resumedRaw = false
            // True when a previously-held line was found merged into a longer line in
            // this chunk. In that case the bottom line of THIS chunk must NOT be held
            // again even if it still lacks terminal punctuation — we already waited
            // one chunk for it, holding it a second time would cause the same
            // infinite-hold loop the resumedRaw guard protects against above.
            var wasHeldAndMerged = false
            heldLine?.let { held ->
                val alreadyMerged = allLines.any {
                    it.startsWith(held, ignoreCase = true) || it.contains(held, ignoreCase = true)
                }
                if (!alreadyMerged) {
                    allLines.add(0, held)
                    // The held line was removed from allLines before spokenLines was
                    // updated, so it should never already be in spokenLines — but the
                    // overlap region can re-detect it and add it there before this
                    // chunk runs. Evict it now so the re-inserted line is always spoken.
                    spokenLines.remove(normalizeForDedup(held))
                    resumedRaw = true
                } else {
                    wasHeldAndMerged = true
                }
                heldLine = null
            }

            // The bottom-most line of a chunk may be a sentence sliced in half by the
            // chunk/screen edge. If it doesn't end with terminal punctuation and this
            // isn't the last chunk of the page, hold it back instead of speaking it —
            // the next (overlapping) chunk will bring in the rest of the sentence so
            // it gets read ONCE, in full, instead of being split into two separate
            // readings (a broken half now + the same sentence again later).
            val isFinalChunk = chunkEnd >= pageH
            if (allLines.isNotEmpty()) {
                val last = allLines.last()
                val isRawResumedLineOnly = resumedRaw && allLines.size == 1
                val looksComplete = last.isEmpty() || last.last() in ".!?,\u2026\"'\u201d\u2019)]\u3002"
                // Don't re-hold a line that was already held once and came back merged —
                // wasHeldAndMerged means we already gave it one extra chunk to complete.
                if (!looksComplete && !isFinalChunk && !isRawResumedLineOnly && !wasHeldAndMerged) {
                    allLines.removeAt(allLines.lastIndex)
                    heldLine = last
                }
            }

            // Wait for the scroll animation to reach this chunk's position BEFORE
            // speaking — OCR ran in parallel (bitmap slice, no scroll needed), but
            // the user should see the text on screen before hearing it.
            // Previously scrollJob?.join() was placed AFTER speakAndWait, which meant
            // TTS started while the screen was still scrolling into position, making
            // it look like the reader was speaking content ahead of the visual scroll.
            scrollJob?.join()
            if (!isReading) return false

            // Only speak lines not yet spoken on this page (handles overlap re-detection).
            // Use normalised keys so minor OCR variations of the same bubble text
            // (extra space, stray punctuation, slight case difference between two
            // scans of the same visible region) are still recognised as duplicates
            // and never re-read.
            val newLines = allLines.filter { line -> normalizeForDedup(line) !in spokenLines }

            if (newLines.isNotEmpty()) {
                tvStatus.text = "Reading..."
                speakAndWait(normalizeForTts(newLines.joinToString(" ")))
                if (!isReading) return false
            }

            // Mark this chunk's lines as spoken using normalised keys so that the
            // next overlapping chunk's slightly-different OCR result still matches.
            spokenLines.addAll(allLines.map { normalizeForDedup(it) })
            // No new text OR all lines already read — continue scrolling

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
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim -> scrollView.scrollTo(0, anim.animatedValue as Int) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator)    { cont.resume(Unit) }
                    override fun onAnimationCancel(a: Animator) { cont.resume(Unit) }
                })
            }
            // NOTE: do NOT set LAYER_TYPE_HARDWARE on pagesContainer — it forces the
            // entire container (all pages stacked) to be rasterised into a single GPU
            // texture on every scroll frame. For a 10-page strip that texture can be
            // hundreds of MB, causing exactly the "10 fps" stutter the user reported.
            // The Activity is already hardware-accelerated (manifest flag), so Android's
            // RenderThread already composites each ImageView tile efficiently without
            // any explicit layer hint. Just start the animator directly.
            scrollView.post { anim.start() }
        }

    // A recognized line of text plus how tilted its source block is.
    // Real dialogue sits flat inside a speech bubble; decorative SFX / reaction
    // labels (STARE, MURMUR, sound effects, etc.) are almost always drawn at an
    // angle, curved, or stylized — regardless of their color, box shape, or the
    // specific word used. Using rotation as a signal lets us auto-ignore noise
    // we've never seen the exact wording of before, instead of relying only on
    // an ever-growing word list.
    private data class OcrLine(val text: String, val rotatedDeg: Float, val heightPx: Int)

    // Sort text blocks top-to-bottom then left-to-right so multi-column panels
    // (speech bubbles side-by-side) are read in the correct visual sequence.
    private suspend fun recognizeText(bmp: Bitmap): List<OcrLine> = suspendCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                val sorted = result.textBlocks
                    .sortedWith(compareBy(
                        { it.boundingBox?.top  ?: 0 },
                        { it.boundingBox?.left ?: 0 }
                    ))
                val lines = mutableListOf<OcrLine>()
                for (block in sorted) {
                    val angle = blockRotationDegrees(block.cornerPoints)
                    val subLines = block.text.split("\n")
                    val nonBlankCount = subLines.count { it.isNotBlank() }.coerceAtLeast(1)
                    val perLineHeight = (block.boundingBox?.height() ?: 0) / nonBlankCount
                    subLines.forEach { lines.add(OcrLine(it, angle, perLineHeight)) }
                }
                cont.resume(lines)
            }
            .addOnFailureListener { cont.resume(emptyList()) }
    }

    // Angle (in degrees) of the top edge of a text block relative to horizontal.
    // 0° = perfectly flat (normal dialogue). Large values = tilted/curved/rotated
    // decorative text (typical of onomatopoeia and reaction labels).
    private fun blockRotationDegrees(corners: Array<Point>?): Float {
        if (corners == null || corners.size < 2) return 0f
        val dx = (corners[1].x - corners[0].x).toFloat()
        val dy = (corners[1].y - corners[0].y).toFloat()
        if (dx == 0f && dy == 0f) return 0f
        return Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    }

    // Any recognized text block tilted more than this many degrees from
    // horizontal is treated as decorative SFX/reaction text, not dialogue.
    private val ROTATED_NOISE_DEG = 15f

    // ── Noise filter: lines matching any of these patterns are silently skipped ──
    //
    // 1. Gesture / UI interaction overlays + social / nav buttons
    private val noiseUiAction = Regex(
        """^(SWIPE|TAP|SCROLL|CLICK|HOLD|DRAG|PINCH|NEXT|PREV|PREVIOUS|HOME|BACK|FORWARD|RETRY|REFRESH|LOAD|LOADING|FOLLOW|LIKE|SHARE|SUBSCRIBE|REPORT|BOOKMARK|SAVE)(\s+(SWIPE|TAP|SCROLL|NEXT|PREV|PREVIOUS|HOME|BACK|FOLLOW|LIKE|SHARE))*$""",
        setOf(RegexOption.IGNORE_CASE)
    )
    // 2. App / website metadata embedded in manhwa panels
    //    e.g. "READ EPISODE 3125 COMMENTS: 1 VIEWS: 1"
    //         "NEXT EPISODE"  "© 2024 WEBTOON"
    private val noiseUiMeta = Regex(
        """(READ\s+EPISODE|NEXT\s+EPISODE|NEXT\s+CHAPTER|PREV(IOUS)?\s+(EPISODE|CHAPTER)|COMMENTS?\s*:|\bVIEWS?\s*:|\bLIKES?\s*:|\bSUBSCRIBERS?\s*:|EPISODE\s+\d|\bCHAPTER\s+\d|\bEP\.\s*\d|ALL\s+RIGHTS\s+RESERVED|COPYRIGHT|\bWEBTOON\b|\bTAPAS\b)""",
        setOf(RegexOption.IGNORE_CASE)
    )
    // 3. Pure SFX / onomatopoeia standing alone (not embedded in dialogue)
    private val noiseSfx = Regex(
        """^(BOOM|BANG|CRASH|CRACK|THUD|SLAM|SMASH|WHOOSH|SWOOSH|WHACK|THWACK|CLANG|CLATTER|POP|SNAP|WHAM|POW|KABOOM|POOF|PUFF|ZAP|ZING|FWOOSH|FWOOM|BWOOM|CLUNK|BONK|BOING|CREAK|RATTLE|RUMBLE|ROAR|GROWL|HISS|SQUEAK|THUMP|GASP|SNIFF|GULP|PANT|WHEEZE|MURMUR|SHUDDER|RUSTLE|GLEAM|SPARKLE|FLASH|TREMBLE|FLICKER|SHING|SLASH|FWAP|THWAP|STOMP|SKID|VROOM|WHOMP|CRUNCH|MUNCH|DING|DONG|BEEP|BUZZ|RING|CHIME|KNOCK|TICK|TOCK|TICKTOCK|SPLASH|SPLOSH|DRIP|PLOP|SIZZLE|CRACKLE|SLURP|CHOMP|GULP|SLURRRP|SCREECH|SHRIEK|HOWL|CHIRP|TWEET|MEOW|WOOF|NEIGH|OINK|MOO|BAABAA|CLOP|JINGLE|CLINK|TING|WHIR|HUM|BUZZ|SPARK|CRACKLE|FIZZ|GURGLE|SLOSH)(\s+(BOOM|BANG|CRASH|THUD|POP|SNAP|ZAP|POOF|WHAM|POW|GASP|SOB|HA|HEH|HUE))*$""",
        setOf(RegexOption.IGNORE_CASE)
    )
    // 4. Repetitive short syllables — fixed to {1,} so "HA HA" (2x) is also caught
    //    HA HA, HA HA HA, SOB SOB, HEH HEH HEH, etc.
    private val noiseRepeat = Regex(
        """^([A-Z]{1,5})(\s+\1){1,}$""",
        setOf(RegexOption.IGNORE_CASE)
    )
    // 5. Text wrapped in decoration symbols: *poof*, *laughs*, [smile], [THE END]
    private val noiseSymbol = Regex(
        """^\*[^*]{1,40}\*$|^\[[^\]]{1,40}\]$"""
    )
    // 6. Lines with ONLY punctuation / symbols — no letters or digits at all
    //    e.g. "...", "!!!", "???", "!?", "♡", "~", "—"
    private val noisePunctOnly = Regex(
        """^[^a-zA-Z0-9]+$"""
    )
    // 7. Single-word emotion / expression labels placed beside characters
    //    e.g. "SMILE", "BLUSH", "GLARE" — visual annotations, not dialogue
    private val noiseEmotion = Regex(
        """^(SMILE|SMILING|SMIRK|SMIRKING|GRIN|GRINNING|LAUGH|LAUGHING|CHUCKLE|CHUCKLING|CRY|CRYING|WEEP|WEEPING|BLUSH|BLUSHING|WINK|WINKING|GLARE|GLARING|STARE|STARING|POUT|POUTING|YAWN|YAWNING|FREEZE|FROZEN|SHOCK|SHOCKED|SURPRISED|TREMBLING|SWEATING|SCREAMING|SHRUG|SHRUGGING|NOD|NODDING|PANIC|PANICKING|RAGE|SHIVER|SHIVERING|FIDGET|SULK|SULKING|SQUINT|SQUINTING|FROWN|FROWNING|SNIFFLE|SNIFFLING|SNEER|SNEERING|SCOFF|SCOFFING|TWITCH|TWITCHING|TENSE|TWITCHY|NERVOUS|EMBARRASSED|FLINCH|FLINCHING|WINCE|WINCING|STARTLE|STARTLED|DAZE|DAZED|PHEW|SIGH|SIGHING|GULP|GULPING)$""",
        setOf(RegexOption.IGNORE_CASE)
    )
    // 8. Laughter/reaction without spaces: HAHA, HAHAHA, HEHEHE, KEKEKE, PFFT, LOL…
    //    Fixed to {2,} repeats so 3+ syllables (HAHAHA, HEHEHEHE, KEKEKEKE, etc.)
    //    are caught too, not just exactly two.
    //    Optional trailing punctuation (.!?~…) is allowed.
    //    e.g. "HAHA...", "HAHAHA", "KEKEKE", "PFFT", "LOL"
    private val noiseLaugh = Regex(
        """^(A*(?:HA+){2,}|(?:HE+){2,}|(?:HO+){2,}|(?:KE+){2,}|(?:FU+){2,}|KY+A*HA+|AHA+|PFFT+|LMAO|LOL+|MUHA+|BWAHA+|GYAHA+|NYAHA+)[!?.~\u2026]*$""",
        setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * Returns true if the line is visual/UI noise that should never be spoken.
     * Covers: gesture/nav overlays, app metadata, pure SFX, repetitive syllables,
     * symbol-wrapped text, pure punctuation, bare numeric/timestamp lines, and
     * garbled "censor scream" text (mostly symbols, unreadable as words).
     */
    private fun isNoiseLine(line: String): Boolean {
        val t = line.trim()
        if (t.length in 1..6) {
            val letterCount = t.count { it.isLetter() }
            // Pure-letter short words (MOVE, YES, NO, RUN, GO, STOP, WAIT, etc.) are
            // real dialogue — a speech bubble's first word often lands on its own OCR
            // line and must not be silently dropped just because it's short.
            if (letterCount >= 2 && t.all { it.isLetter() }) return false
            // Expressive reactions ending with ? or ! (huh?, oh!, huh!, no!, wow?)
            // are real dialogue even when short.
            val isExpressive = t.last() == '?' || t.last() == '!'
            if (letterCount >= 2 && isExpressive) return false
            // Everything else at this length is an OCR artifact / stray label.
            return true
        }
        if (noisePunctOnly.matches(t)) return true        // ..., !!!, ♡, ~, —
        if (noiseUiAction.matches(t)) return true         // SWIPE, NEXT, FOLLOW...
        if (noiseUiMeta.containsMatchIn(t)) return true   // READ EPISODE, COMMENTS:...
        if (noiseSfx.matches(t)) return true              // BOOM, CRASH, POOF...
        if (noiseRepeat.matches(t)) return true           // HA HA, SOB SOB SOB...
        if (noiseSymbol.matches(t)) return true           // *action*, [emotion]
        if (noiseEmotion.matches(t)) return true          // SMILE, BLUSH, GLARE...
        if (noiseLaugh.matches(t)) return true            // HAHA, KEKE, PFFT...
        if (isSymbolGarble(t)) return true                // &아#@!&아#! — censor-style cursing/shouting
        // Pure numeric / timestamp / percentage (page numbers, status bar, battery)
        if (t.replace(Regex("[0-9:./%\\-\\s]"), "").isEmpty() && t.length <= 12) return true
        // Clock time with AM/PM (phone-mockup status bars), e.g. "7:00 PM"
        if (Regex("""^\d{1,2}:\d{2}\s*(AM|PM)$""", RegexOption.IGNORE_CASE).matches(t)) return true
        return false
    }

    // Garbled "censor scream" text: comics often represent unintelligible
    // shouting/cursing as a jumble of symbols mixed with stray characters,
    // e.g. "&아#@!&아#!", "#$%&!!", "@!#$%^". This can't be meaningfully
    // spoken by TTS, so treat any line where symbols outnumber real letters
    // as noise, regardless of the exact characters involved.
    //
    // Exception: a real word followed by expressive punctuation like "HELL...?!",
    // "NO!!!", "WHY?!", "STOP!!" — the word part is all letters/spaces so TTS
    // can read it; the trailing punctuation is just emphasis, not garble.
    private fun isSymbolGarble(t: String): Boolean {
        val letters = t.count { it.isLetter() }
        val symbols = t.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        if (symbols < 3 || symbols <= letters) return false
        // Check if stripping trailing expressive punctuation (., !, ?, …, ~)
        // leaves a pure-word string — if so it's a real word + emphasis, not garble.
        val wordPart = t.trimEnd('.', '!', '?', '\u2026', '~', ' ')
        if (wordPart.isNotEmpty() && wordPart.all { it.isLetter() || it.isWhitespace() }) return false
        return true
    }

    // Normalises a spoken line into a deduplication key so that minor OCR
    // variations of the same visual text are treated as identical:
    //   • lowercase        — "STRONG!" == "strong!"
    //   • collapse spaces  — "BECOME  STRONG" == "BECOME STRONG"
    //   • strip punctuation — "STRONG!" == "STRONG" == "STRONG !"
    // Stripping punctuation is intentionally aggressive here: the goal is only
    // dedup (have-we-read-this-bubble-before?), not TTS accuracy.
    private fun normalizeForDedup(text: String): String =
        text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")   // remove all punctuation/symbols
            .replace(Regex("\\s+"), " ")            // collapse whitespace
            .trim()

    // Stammer/stutter dialogue like "W-What", "I-I", "S-Stop" reads badly if the
    // TTS spells out the lone leading letter(s) as an abbreviation (e.g. "double
    // U, what") instead of a stammer sound. Since real stammering can't be
    // synthesized reliably, drop the repeated stutter prefix and speak the
    // intended word cleanly instead — "W-What" -> "What".
    // Only matches when the letter(s) before the hyphen repeat the start of the
    // following word, so real hyphenated words (e-mail, X-ray, T-shirt) are left
    // untouched.
    private val stutterPrefix = Regex("""\b([A-Za-z]{1,2})-(?=\1)""", RegexOption.IGNORE_CASE)

    // Converts ALL-CAPS sequences (2+ letters) to lowercase so TTS reads them
    // as words, never as abbreviations spelled letter-by-letter.
    // DOKJA -> dokja, AXCEL -> axcel, RATTLE -> rattle
    // Single uppercase letters (pronoun 'I') are left unchanged.
    private fun normalizeForTts(text: String): String =
        text.replace(stutterPrefix, "")
            // Expand common abbreviations that TTS reads as letter-sequences.
            // "etc." / "etc" → "etcetera" (TTS reads "etc" as "ee-tee-see" otherwise).
            .replace(Regex("""\betc\.""", RegexOption.IGNORE_CASE), "etcetera")
            .replace(Regex("""\betc\b""", RegexOption.IGNORE_CASE), "etcetera")
            .replace(Regex("[A-Z]{2,}")) { m -> m.value.lowercase() }

    private suspend fun speakAndWait(text: String) {
        // Guard: if TTS is gone or not yet ready, skip silently rather than hanging.
        if (tts == null || !ttsReady || text.isBlank()) return
        // 60-second hard timeout — if the TTS engine never fires onDone/onError
        // (e.g. engine crash, null utterance ID, QUEUE_FLUSH race), the coroutine
        // would block forever without this. The timeout stops the stall and lets
        // the reading loop advance to the next chunk automatically.
        withTimeoutOrNull(60_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val uid = "chunk_${System.currentTimeMillis()}"
                // AtomicBoolean guard prevents multiple cont.resume() calls.
                // onDone, onError, and the speak()==ERROR fast-path can all fire
                // within the same tick; resuming an already-completed continuation
                // throws IllegalStateException and crashes the coroutine.
                val done = AtomicBoolean(false)
                fun finishOnce() { if (done.compareAndSet(false, true)) cont.resume(Unit) }

                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?)  { if (id == uid) finishOnce() }
                    override fun onError(id: String?) { if (id == uid) finishOnce() }
                })
                val result = tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
                // speak() returning ERROR means the callback will never fire — resume now.
                if (result == TextToSpeech.ERROR) finishOnce()
                // If the coroutine is cancelled (user hits Stop/Pause), stop TTS immediately
                // so audio cuts off at once instead of finishing the current utterance.
                cont.invokeOnCancellation { tts?.stop() }
            }
        } ?: tts?.stop()  // timeout hit — kill audio and continue
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
