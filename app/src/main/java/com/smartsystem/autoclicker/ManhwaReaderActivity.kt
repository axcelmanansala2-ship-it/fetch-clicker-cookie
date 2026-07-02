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
        spokenLines.clear()  // fresh per page — prevents re-reading overlap regions
        heldLine = null      // fresh per page — no carry-over cut-off line across pages

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

            // Split OCR result; skip blank lines and UI noise overlays (SWIPE, TAP, etc.)
            val allLines = text.split("\n")
                .map { it.trim() }
                .filter { it.isNotBlank() && !isNoiseLine(it) }
                .toMutableList()

            // The bottom-most line of a chunk may be a sentence sliced in half by the
            // chunk/screen edge. If it doesn't end with terminal punctuation and this
            // isn't the last chunk of the page, hold it back instead of speaking it —
            // the next (overlapping) chunk will bring in the rest of the sentence so
            // it gets read ONCE, in full, instead of being split into two separate
            // readings (a broken half now + the same sentence again later).
            //
            // IMPORTANT: only hold back for a SINGLE chunk. OCR text can vary slightly
            // between chunks (spacing, capitalization, minor misreads), so comparing
            // exact strings to detect "already held" is unreliable and can silently
            // drop a sentence forever (it gets held, replaced by a slightly different
            // held value next chunk, and the original is never spoken). Capping the
            // hold at one chunk guarantees every line is eventually spoken.
            val isFinalChunk = chunkEnd >= pageH
            if (allLines.isNotEmpty() && heldLine == null) {
                val last = allLines.last()
                val looksComplete = last.isEmpty() || last.last() in ".!?\u2026\"'\u201d\u2019)]"
                if (!looksComplete && !isFinalChunk) {
                    allLines.removeAt(allLines.lastIndex)
                    heldLine = last
                } 
            } else {
                // Either nothing to hold, or we already held one chunk's worth —
                // force everything through this round so nothing is lost.
                heldLine = null
            }

            // Only speak lines not yet spoken on this page (handles overlap re-detection)
            val newLines = allLines.filter { line -> line !in spokenLines }

            if (newLines.isNotEmpty()) {
                tvStatus.text = "Reading..."
                speakAndWait(normalizeForTts(newLines.joinToString(" ")))
                if (!isReading) return false
            }

            // Mark this chunk's lines as spoken (including overlap lines), EXCEPT the
            // held-back cut-off line — it must stay eligible to be read once complete.
            spokenLines.addAll(allLines)
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
                // DecelerateInterpolator feels more natural than Linear
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { anim -> scrollView.scrollTo(0, anim.animatedValue as Int) }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(a: Animator) {
                        // Release GPU cache after scroll completes
                        pagesContainer.setLayerType(View.LAYER_TYPE_NONE, null)
                        cont.resume(Unit)
                    }
                    override fun onAnimationCancel(a: Animator) {
                        pagesContainer.setLayerType(View.LAYER_TYPE_NONE, null)
                        cont.resume(Unit)
                    }
                })
            }
            scrollView.post {
                // GPU-cache the content during scroll: each frame is a texture
                // composite instead of a full software redraw — eliminates lag
                pagesContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                anim.start()
            }
        }

    // Sort text blocks top-to-bottom then left-to-right so multi-column panels
    // (speech bubbles side-by-side) are read in the correct visual sequence.
    private suspend fun recognizeText(bmp: Bitmap): String = suspendCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { result ->
                val sorted = result.textBlocks
                    .sortedWith(compareBy(
                        { it.boundingBox?.top  ?: 0 },
                        { it.boundingBox?.left ?: 0 }
                    ))
                    .joinToString("\n") { it.text }
                cont.resume(sorted)
            }
            .addOnFailureListener { cont.resume("") }
    }

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
     * symbol-wrapped text, pure punctuation, and bare numeric/timestamp lines.
     */
    private fun isNoiseLine(line: String): Boolean {
        val t = line.trim()
        if (t.length <= 1) return true                    // single char — OCR artifact
        if (noisePunctOnly.matches(t)) return true        // ..., !!!, ♡, ~, —
        if (noiseUiAction.matches(t)) return true         // SWIPE, NEXT, FOLLOW...
        if (noiseUiMeta.containsMatchIn(t)) return true   // READ EPISODE, COMMENTS:...
        if (noiseSfx.matches(t)) return true              // BOOM, CRASH, POOF...
        if (noiseRepeat.matches(t)) return true           // HA HA, SOB SOB SOB...
        if (noiseSymbol.matches(t)) return true           // *action*, [emotion]
        if (noiseEmotion.matches(t)) return true          // SMILE, BLUSH, GLARE...
        if (noiseLaugh.matches(t)) return true            // HAHA, KEKE, PFFT...
        // Pure numeric / timestamp / percentage (page numbers, status bar, battery)
        if (t.replace(Regex("[0-9:./%\\-\\s]"), "").isEmpty() && t.length <= 12) return true
        return false
    }

    // Converts ALL-CAPS sequences (2+ letters) to lowercase so TTS reads them
    // as words, never as abbreviations spelled letter-by-letter.
    // DOKJA -> dokja, AXCEL -> axcel, RATTLE -> rattle
    // Single uppercase letters (pronoun 'I') are left unchanged.
    private fun normalizeForTts(text: String): String =
        text.replace(Regex("[A-Z]{2,}")) { m -> m.value.lowercase() }

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
