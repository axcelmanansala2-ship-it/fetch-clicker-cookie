package com.smartsystem.autoclicker

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream
import kotlin.coroutines.resume

/**
 * Novel Reader — paragraph-by-paragraph TTS reader for .txt and .epub files.
 *
 * Features:
 *  • Smooth auto-scroll to current paragraph while reading
 *  • Paragraph highlight (purple glow) tracks TTS position
 *  • Play / Pause / Stop / Prev-para / Next-para controls
 *  • Reading progress bar + "para X / Y" counter
 *  • Voice picker, reading speed, font size — all in Settings
 *  • Supports plain .txt and basic .epub (ZIP → HTML strip)
 */
class NovelReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    // ── colours (novel dark theme) ────────────────────────────────────────────
    private val C_BG        = 0xFF0D0F18.toInt()   // near-black page
    private val C_SURFACE   = 0xFF161929.toInt()   // toolbar / controls panel
    private val C_ACCENT    = 0xFF7C6AF7.toInt()   // purple – play button, progress
    private val C_ACCENT2   = 0xFF5549D3.toInt()   // darker purple for ripple
    private val C_TEXT      = 0xFFE4E4E8.toInt()   // body text (soft white)
    private val C_HEADING   = 0xFFBBA9FF.toInt()   // chapter headings (lilac)
    private val C_MUTED     = 0xFF8888A0.toInt()   // status / secondary labels
    private val C_HIGHLIGHT = 0xFF1F1A40.toInt()   // active paragraph background
    private val C_RED       = 0xFFFF4F4F.toInt()   // stop button

    // ── views ─────────────────────────────────────────────────────────────────
    private lateinit var scrollView: ScrollView
    private lateinit var paraContainer: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvVoiceInfo: TextView
    private lateinit var btnPlayPause: ImageButton
    private lateinit var btnStop: ImageButton
    private lateinit var btnPrev: ImageButton
    private lateinit var btnNext: ImageButton
    private lateinit var progressBar: ProgressBar

    // ── TTS ───────────────────────────────────────────────────────────────────
    private var tts: TextToSpeech? = null
    private var ttsReady    = false
    private var ttsSpeed    = 1.0f
    private var selectedVoice: Voice? = null

    // ── content ───────────────────────────────────────────────────────────────
    private val paragraphs = mutableListOf<String>()
    private val paraViews  = mutableListOf<TextView>()

    // ── state ─────────────────────────────────────────────────────────────────
    private var currentPara   = 0
    private var isReading     = false
    private var readingJob: Job? = null
    private var scrollMs      = 500L
    private var fontSize      = 17f

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═════════════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        buildLayout()
        tts = TextToSpeech(this, this)
        loadFromIntent()
    }

    override fun onDestroy() {
        super.onDestroy()
        readingJob?.cancel()
        tts?.shutdown()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UI construction
    // ═════════════════════════════════════════════════════════════════════════

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_BG)
        }
        setContentView(root)
        root.addView(buildToolbar())
        root.addView(buildProgressStrip())
        root.addView(buildReadingArea())   // weight=1
        root.addView(buildControlsPanel())
    }

    private fun buildToolbar(): View {
        val bar = RelativeLayout(this).apply {
            setBackgroundColor(C_SURFACE)
            elevation = dp(5f)
            layoutParams = lp(MATCH_PARENT, dp(58).toInt())
        }

        val back = iconBtn(android.R.drawable.ic_media_previous, C_MUTED).apply {
            id = View.generateViewId()
            setOnClickListener { finish() }
        }
        back.layoutParams = RelativeLayout.LayoutParams(dp(44).toInt(), dp(44).toInt()).also {
            it.addRule(RelativeLayout.CENTER_VERTICAL)
            it.marginStart = dp(6).toInt()
        }

        tvTitle = TextView(this).apply {
            text = "Novel Reader"
            setTextColor(C_TEXT)
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setSingleLine()
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tvTitle.layoutParams = RelativeLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).also {
            it.addRule(RelativeLayout.CENTER_IN_PARENT)
        }

        val gear = iconBtn(android.R.drawable.ic_menu_preferences, C_MUTED).apply {
            setOnClickListener { showSettings() }
        }
        gear.layoutParams = RelativeLayout.LayoutParams(dp(44).toInt(), dp(44).toInt()).also {
            it.addRule(RelativeLayout.CENTER_VERTICAL)
            it.addRule(RelativeLayout.ALIGN_PARENT_END)
            it.marginEnd = dp(6).toInt()
        }

        bar.addView(back); bar.addView(tvTitle); bar.addView(gear)
        return bar
    }

    private fun buildProgressStrip(): ProgressBar {
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
            progressTintList        = ColorStateList.valueOf(C_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF252040.toInt())
            layoutParams = lp(MATCH_PARENT, dp(3).toInt())
        }
        return progressBar
    }

    private fun buildReadingArea(): ScrollView {
        scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        paraContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22).toInt(), dp(28).toInt(), dp(22).toInt(), dp(100).toInt())
        }
        scrollView.addView(paraContainer)
        return scrollView
    }

    private fun buildControlsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_SURFACE)
            elevation = dp(24f)
            setPadding(dp(20).toInt(), dp(14).toInt(), dp(20).toInt(), dp(20).toInt())
            layoutParams = lp(MATCH_PARENT, WRAP_CONTENT)
        }

        // Status + progress counter row
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.bottomMargin = dp(12).toInt()
            }
        }
        tvStatus = TextView(this).apply {
            text = "Open a .txt or .epub file to begin"
            setTextColor(C_MUTED); textSize = 11.5f
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        tvProgress = TextView(this).apply {
            text = ""; setTextColor(C_ACCENT); textSize = 11.5f
            setTypeface(null, Typeface.BOLD); gravity = Gravity.END
        }
        statusRow.addView(tvStatus); statusRow.addView(tvProgress)
        panel.addView(statusRow)

        // Button row: [⏮] [space] [large ▶/⏸] [space] [⏭] [gap] [■]
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.bottomMargin = dp(10).toInt()
            }
        }

        btnPrev = roundBtn(android.R.drawable.ic_media_previous, C_MUTED, dp(48).toInt(), filled = false)
        btnPrev.setOnClickListener { skipPrev() }

        btnPlayPause = roundBtn(android.R.drawable.ic_media_play, C_ACCENT, dp(66).toInt(), filled = true)
        btnPlayPause.setOnClickListener { onPlayPause() }

        btnNext = roundBtn(android.R.drawable.ic_media_next, C_MUTED, dp(48).toInt(), filled = false)
        btnNext.setOnClickListener { skipNext() }

        btnStop = roundBtn(android.R.drawable.ic_delete, C_RED, dp(48).toInt(), filled = false)
        btnStop.setOnClickListener { stopReading() }

        listOf(
            btnPrev, gap(dp(14).toInt()),
            btnPlayPause, gap(dp(14).toInt()),
            btnNext, gap(dp(22).toInt()),
            btnStop
        ).forEach { btnRow.addView(it) }
        panel.addView(btnRow)

        // Voice / tap-to-change label
        tvVoiceInfo = TextView(this).apply {
            text = "🔊 Tap to change voice & speed"
            setTextColor(C_MUTED); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(2).toInt(), 0, 0)
            isClickable = true; isFocusable = true
            foreground = rippleFg()
            setOnClickListener { showSettings() }
            layoutParams = lp(MATCH_PARENT, WRAP_CONTENT)
        }
        panel.addView(tvVoiceInfo)
        return panel
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun iconBtn(iconRes: Int, tint: Int) = ImageButton(this).apply {
        setImageResource(iconRes); setColorFilter(tint); background = null
        setPadding(dp(8).toInt(), dp(8).toInt(), dp(8).toInt(), dp(8).toInt())
    }

    private fun roundBtn(iconRes: Int, color: Int, size: Int, filled: Boolean): ImageButton {
        val bgColor  = if (filled) color else 0xFF252040.toInt()
        val iconTint = if (filled) Color.WHITE else color
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(bgColor)
        }
        val ripple = RippleDrawable(
            ColorStateList.valueOf(if (filled) Color.WHITE else color),
            bg, null
        )
        return ImageButton(this).apply {
            setImageResource(iconRes); setColorFilter(iconTint)
            background = ripple; elevation = if (filled) dp(6f) else dp(2f)
            setPadding(dp(11).toInt(), dp(11).toInt(), dp(11).toInt(), dp(11).toInt())
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(size, size)
        }
    }

    private fun gap(w: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(w, 1) }
    private fun rippleFg() = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, null)
    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dp(v: Int)   = dp(v.toFloat())
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    // ═════════════════════════════════════════════════════════════════════════
    // File loading
    // ═════════════════════════════════════════════════════════════════════════

    private fun loadFromIntent() {
        val uri: Uri? = intent?.data
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(Intent.EXTRA_STREAM)

        if (uri == null) { showPlaceholder(); return }
        tvStatus.text = "Loading…"

        lifecycleScope.launch {
            val rawText = withContext(Dispatchers.IO) { readContent(uri) }
            if (rawText.isNullOrBlank()) { tvStatus.text = "Could not read file"; return@launch }

            val paras = withContext(Dispatchers.Default) { parseParagraphs(rawText) }
            if (paras.isEmpty()) { tvStatus.text = "No readable text found"; return@launch }

            // Title from filename
            val name = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.replace(Regex("\\.(txt|epub|html?)$", RegexOption.IGNORE_CASE), "")
                ?.trim() ?: "Novel Reader"
            tvTitle.text = if (name.length > 26) name.take(24) + "…" else name

            displayParagraphs(paras)
        }
    }

    private fun readContent(uri: Uri): String? {
        val mime = contentResolver.getType(uri) ?: ""
        val name = uri.lastPathSegment ?: ""
        return when {
            mime.contains("epub") || name.endsWith(".epub", true) -> readEpub(uri)
            else -> readText(uri)
        }
    }

    private fun readText(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
    } catch (_: Exception) {
        try {
            val tmp = File(cacheDir, "novel_tmp.txt")
            contentResolver.openInputStream(uri)?.use { it.copyTo(tmp.outputStream()) }
            tmp.readText()
        } catch (_: Exception) { null }
    }

    private fun readEpub(uri: Uri): String? = try {
        val sb = StringBuilder()
        val entries = mutableListOf<Pair<String, String>>()
        contentResolver.openInputStream(uri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val n = entry.name.lowercase()
                    if ((n.endsWith(".html") || n.endsWith(".xhtml") || n.endsWith(".htm"))
                        && !n.contains("nav") && !n.contains("toc") && !n.contains("cover")) {
                        val html = zip.bufferedReader().readText()
                        val text = stripHtml(html)
                        if (text.length > 100) entries += entry.name to text
                    }
                    zip.closeEntry(); entry = zip.nextEntry
                }
            }
        }
        entries.sortBy { it.first }
        entries.forEach { sb.append(it.second).append("\n\n") }
        sb.toString().takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    private fun stripHtml(html: String): String = html
        .replace(Regex("<br\\s*/?>",      RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<p[^>]*>",        RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>",            RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<h[1-6][^>]*>",   RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("</h[1-6]>",       RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("&#[0-9]+;"), "")
        .replace(Regex("\n{3,}"), "\n\n").trim()

    // ═════════════════════════════════════════════════════════════════════════
    // Text parsing
    // ═════════════════════════════════════════════════════════════════════════

    private fun parseParagraphs(raw: String): List<String> {
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')

        // Prefer double-newline splits (standard novel format)
        val byDouble = text.split(Regex("\n{2,}"))
        val paras: List<String> = if (byDouble.size > 3) {
            byDouble.map { it.replace('\n', ' ').replace(Regex("  +"), " ").trim() }
        } else {
            // Single-newline format — each non-empty line is a paragraph
            text.lines().map { it.trim() }
        }

        return paras.filter { it.length >= 10 }
    }

    private fun isHeading(text: String): Boolean {
        val t = text.trim()
        return t.length < 80 &&
            (t.matches(Regex("(?i)(chapter|part|section|prologue|epilogue|interlude|volume).*"))
             || (t.uppercase() == t && t.length < 40 && t.contains(Regex("[A-Z]"))))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Display
    // ═════════════════════════════════════════════════════════════════════════

    private fun displayParagraphs(paras: List<String>) {
        paragraphs.clear(); paragraphs.addAll(paras)
        paraViews.clear(); paraContainer.removeAllViews()

        for (para in paras) {
            val heading = isHeading(para)
            val tv = TextView(this).apply {
                text = para
                setTextColor(if (heading) C_HEADING else C_TEXT)
                textSize = if (heading) fontSize + 2.5f else fontSize
                setTypeface(null, if (heading) Typeface.BOLD else Typeface.NORMAL)
                setLineSpacing(0f, if (heading) 1.4f else 1.65f)
                setPadding(dp(12).toInt(), dp(5).toInt(), dp(12).toInt(), dp(5).toInt())
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                    it.bottomMargin = dp(if (heading) 10 else 14).toInt()
                }
            }
            paraViews.add(tv)
            paraContainer.addView(tv)
        }

        currentPara = 0
        updateProgress()
        tvStatus.text = "${paras.size} paragraphs — tap ▶ to start"
    }

    private fun showPlaceholder() {
        paraContainer.removeAllViews()
        val msg = TextView(this).apply {
            text = "📖\n\nShare or open any\n.txt or .epub novel file\nwith this app"
            setTextColor(C_MUTED); textSize = 15f; gravity = Gravity.CENTER
            setLineSpacing(0f, 1.6f)
            setPadding(dp(40).toInt(), dp(80).toInt(), dp(40).toInt(), 0)
        }
        paraContainer.addView(msg)
    }

    // ─── highlight ────────────────────────────────────────────────────────────

    private fun setHighlight(idx: Int) {
        paraViews.forEachIndexed { i, tv ->
            tv.animate().cancel()
            if (i == idx) {
                tv.setBackgroundColor(C_HIGHLIGHT)
                tv.animate().alpha(1f).setDuration(120).start()
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT)
            }
        }
    }

    private fun clearHighlight() = paraViews.forEach {
        it.setBackgroundColor(Color.TRANSPARENT)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Playback controls
    // ═════════════════════════════════════════════════════════════════════════

    private fun onPlayPause() {
        when {
            paragraphs.isEmpty() ->
                Toast.makeText(this, "No text loaded yet", Toast.LENGTH_SHORT).show()
            !ttsReady ->
                Toast.makeText(this, "TTS engine not ready", Toast.LENGTH_SHORT).show()
            isReading -> pauseReading()
            else      -> startReading()
        }
    }

    private fun startReading() {
        isReading = true
        btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        tvStatus.text = "Reading…"
        readingJob = lifecycleScope.launch { readLoop() }
    }

    private fun pauseReading() {
        isReading = false; tts?.stop(); readingJob?.cancel()
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        tvStatus.text = "Paused — tap ▶ to continue"
        clearHighlight()
    }

    private fun stopReading() {
        isReading = false; tts?.stop(); readingJob?.cancel()
        currentPara = 0
        btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        tvStatus.text = if (paragraphs.isNotEmpty()) "${paragraphs.size} paragraphs" else "Stopped"
        clearHighlight(); updateProgress()
        scrollView.post { scrollView.smoothScrollTo(0, 0) }
    }

    private fun skipPrev() {
        val wasReading = isReading
        if (wasReading) { isReading = false; tts?.stop(); readingJob?.cancel() }
        currentPara = (currentPara - 1).coerceAtLeast(0)
        updateProgress(); scrollToPara(currentPara)
        if (wasReading) startReading()
    }

    private fun skipNext() {
        val wasReading = isReading
        if (wasReading) { isReading = false; tts?.stop(); readingJob?.cancel() }
        currentPara = (currentPara + 1).coerceAtMost((paragraphs.size - 1).coerceAtLeast(0))
        updateProgress(); scrollToPara(currentPara)
        if (wasReading) startReading()
    }

    // ─── reading loop ─────────────────────────────────────────────────────────

    private suspend fun readLoop() {
        while (isReading && currentPara < paragraphs.size) {
            val para = paragraphs[currentPara]
            withContext(Dispatchers.Main) {
                setHighlight(currentPara)
                updateProgress()
                scrollToPara(currentPara)
            }
            speakAndWait(para)
            if (!isReading) break
            currentPara++
            delay(180)
        }
        if (isReading) {
            withContext(Dispatchers.Main) {
                isReading = false
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                tvStatus.text = "✓  Finished reading"
                clearHighlight()
                currentPara = 0; updateProgress()
            }
        }
    }

    // ─── smooth scroll ────────────────────────────────────────────────────────

    private fun scrollToPara(idx: Int) {
        val view = paraViews.getOrNull(idx) ?: return
        // Target: show the paragraph in the upper third of the screen
        val target = (view.top - scrollView.height / 4).coerceAtLeast(0)
        val start  = scrollView.scrollY
        if (kotlin.math.abs(target - start) < dp(20)) return   // already close enough

        val anim = ValueAnimator.ofInt(start, target).apply {
            duration = scrollMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { scrollView.scrollTo(0, it.animatedValue as Int) }
        }
        scrollView.post { anim.start() }
    }

    // ─── progress ─────────────────────────────────────────────────────────────

    private fun updateProgress() {
        val total = paragraphs.size
        if (total == 0) { tvProgress.text = ""; progressBar.progress = 0; return }
        tvProgress.text = "${currentPara + 1} / $total"
        val pct = ((currentPara + 1).toFloat() / total * 100).toInt()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            progressBar.setProgress(pct, true)
        else
            progressBar.progress = pct
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TTS
    // ═════════════════════════════════════════════════════════════════════════

    private suspend fun speakAndWait(text: String) {
        if (tts == null || !ttsReady || text.isBlank()) return
        withTimeoutOrNull(240_000L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val uid  = "novel_${System.nanoTime()}"
                val done = AtomicBoolean(false)
                fun finish() { if (done.compareAndSet(false, true)) cont.resume(Unit) }

                tts!!.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?)  { if (id == uid) finish() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { if (id == uid) finish() }
                })
                if (tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid) == TextToSpeech.ERROR)
                    finish()
                cont.invokeOnCancellation { tts?.stop() }
            }
        } ?: tts?.stop()
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.ENGLISH
            tts?.setSpeechRate(ttsSpeed)
            refreshVoiceLabel()
        } else {
            tvStatus.text = "TTS engine failed to start"
        }
    }

    private fun refreshVoiceLabel() {
        val v = selectedVoice ?: tts?.defaultVoice
        val name = v?.name?.replace(Regex("[-_]"), " ") ?: "Default voice"
        tvVoiceInfo.text = "🔊  $name   •   ${speedLabel()}   —   Tap to change"
    }

    private fun speedLabel() = when (ttsSpeed) {
        0.75f -> "Slow"
        1.5f  -> "Fast"
        2.0f  -> "Very Fast"
        else  -> "Normal"
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Settings dialog
    // ═════════════════════════════════════════════════════════════════════════

    private fun showSettings() {
        val wasReading = isReading
        if (wasReading) pauseReading()

        val speedLabels  = arrayOf("Slow (0.75×)", "Normal (1×)", "Fast (1.5×)", "Very Fast (2×)")
        val speedValues  = floatArrayOf(0.75f, 1.0f, 1.5f, 2.0f)
        val fontLabels   = arrayOf("Small (15 sp)", "Normal (17 sp)", "Large (19 sp)", "X-Large (22 sp)")
        val fontValues   = floatArrayOf(15f, 17f, 19f, 22f)
        val scrollLabels = arrayOf("Instant", "Smooth (0.5 s)", "Relaxed (1 s)", "Slow (1.8 s)")
        val scrollValues = longArrayOf(0L, 500L, 1000L, 1800L)

        var si = speedValues.indexOfFirst { it == ttsSpeed }.coerceAtLeast(1)
        var fi = fontValues.indexOfFirst  { it == fontSize }.coerceAtLeast(1)
        var sc = scrollValues.indexOfFirst { it == scrollMs }.coerceAtLeast(1)

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C_SURFACE)
            setPadding(dp(24).toInt(), dp(16).toInt(), dp(24).toInt(), dp(12).toInt())
        }

        fun sectionLabel(t: String) = TextView(this).apply {
            text = t; textSize = 12.5f; setTextColor(C_ACCENT)
            setTypeface(null, Typeface.BOLD); letterSpacing = 0.08f
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).also {
                it.topMargin = dp(16).toInt(); it.bottomMargin = dp(4).toInt()
            }
        }

        fun radioGroup(labels: Array<String>, selected: Int, startId: Int): RadioGroup {
            return RadioGroup(this).apply {
                labels.forEachIndexed { i, lbl ->
                    addView(RadioButton(this@NovelReaderActivity).apply {
                        text = lbl; id = startId + i; isChecked = (i == selected)
                        setTextColor(C_TEXT); textSize = 13.5f
                    })
                }
            }
        }

        inner.addView(sectionLabel("READING SPEED"))
        val speedGroup = radioGroup(speedLabels, si, 100); inner.addView(speedGroup)

        inner.addView(sectionLabel("FONT SIZE"))
        val fontGroup  = radioGroup(fontLabels,  fi, 200); inner.addView(fontGroup)

        inner.addView(sectionLabel("SCROLL SPEED"))
        val scrollGroup = radioGroup(scrollLabels, sc, 300); inner.addView(scrollGroup)

        // Voice spinner
        val voices = tts?.voices?.filter { it.locale.language == "en" }
            ?.sortedWith(compareBy({ it.locale.country }, { it.name })) ?: emptyList()
        var voiceSpin: Spinner? = null
        if (voices.isNotEmpty()) {
            inner.addView(sectionLabel("VOICE"))
            val names = voices.map { v ->
                val flag = when (v.locale.country) {
                    "US" -> "🇺🇸" ; "GB" -> "🇬🇧" ; "AU" -> "🇦🇺"
                    "IN" -> "🇮🇳" ; "CA" -> "🇨🇦" ; else -> "🌐"
                }
                "$flag  ${v.name.replace(Regex("[-_]"), " ")}"
            }
            val spin = Spinner(this).apply {
                adapter = ArrayAdapter(this@NovelReaderActivity,
                    android.R.layout.simple_spinner_item, names).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                setSelection(voices.indexOfFirst { it.name == selectedVoice?.name }.coerceAtLeast(0))
            }
            inner.addView(spin); voiceSpin = spin
        }

        AlertDialog.Builder(this)
            .setTitle("Reader Settings")
            .setView(ScrollView(this).apply { addView(inner) })
            .setPositiveButton("Apply") { _, _ ->
                // speed
                val newSi = speedGroup.checkedRadioButtonId - 100
                if (newSi in speedValues.indices) { ttsSpeed = speedValues[newSi]; tts?.setSpeechRate(ttsSpeed) }
                // font
                val newFi = fontGroup.checkedRadioButtonId - 200
                if (newFi in fontValues.indices) {
                    fontSize = fontValues[newFi]
                    paraViews.forEachIndexed { i, tv ->
                        val h = isHeading(paragraphs.getOrElse(i) { "" })
                        tv.textSize = if (h) fontSize + 2.5f else fontSize
                    }
                }
                // scroll
                val newSc = scrollGroup.checkedRadioButtonId - 300
                if (newSc in scrollValues.indices) scrollMs = scrollValues[newSc]
                // voice
                voiceSpin?.selectedItemPosition?.let { idx ->
                    if (idx in voices.indices) { selectedVoice = voices[idx]; tts?.voice = selectedVoice }
                }
                refreshVoiceLabel()
                if (wasReading) startReading()
            }
            .setNegativeButton("Cancel") { _, _ -> if (wasReading) startReading() }
            .show()
    }
}
