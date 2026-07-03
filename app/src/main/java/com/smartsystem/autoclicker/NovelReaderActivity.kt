package com.smartsystem.autoclicker

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.content.ContextCompat
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

    // ── content ──────────────────────────────────────────────────────��────────
    private val paragraphs = mutableListOf<String>()
    private val paraViews  = mutableListOf<TextView>()

    // ── state ─────────────────────────────────────────────────────────────────
    private var currentPara   = 0
    private var isReading     = false
    private var readingJob: Job? = null
    private var scrollMs      = 500L
    private var fontSize      = 17f
    private var currentFileUri: Uri? = null

    // ── Tagalog translation ──────────────────────────────────────────────────
    private val originalParagraphs = mutableListOf<String>()
    private val translatedCache = HashMap<Int, String>()
    private var isTagalogMode = false
    private var translateInProgress = false
    private lateinit var btnTagalog: TextView
    private var voiceBeforeTagalog: Voice? = null
    private var downloadDialog: AlertDialog? = null

    // ── saved reading state ──────────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences

    // ═════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════���═════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        buildLayout()
        tts = TextToSpeech(this, this)
        loadFromIntent()
    }

    override fun onPause() {
        super.onPause()
        saveReadingState()
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

        val floatBtn = TextView(this).apply {
            text = "🗗"
            textSize = 19f
            gravity = Gravity.CENTER
            setTextColor(C_MUTED)
            isClickable = true; isFocusable = true
            foreground = rippleFg()
            setOnClickListener { openOverlayMode() }
        }
        floatBtn.layoutParams = RelativeLayout.LayoutParams(dp(44).toInt(), dp(44).toInt()).also {
            it.addRule(RelativeLayout.CENTER_VERTICAL)
            it.addRule(RelativeLayout.ALIGN_PARENT_END)
            it.marginEnd = dp(52).toInt()
        }

        btnTagalog = TextView(this).apply {
            text = "🇵🇭"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(C_MUTED)
            isClickable = true; isFocusable = true
            foreground = rippleFg()
            setOnClickListener { toggleTagalogMode() }
        }
        btnTagalog.layoutParams = RelativeLayout.LayoutParams(dp(44).toInt(), dp(44).toInt()).also {
            it.addRule(RelativeLayout.CENTER_VERTICAL)
            it.addRule(RelativeLayout.ALIGN_PARENT_END)
            it.marginEnd = dp(98).toInt()
        }

        val gear = iconBtn(android.R.drawable.ic_menu_preferences, C_MUTED).apply {
            setOnClickListener { showSettings() }
        }
        gear.layoutParams = RelativeLayout.LayoutParams(dp(44).toInt(), dp(44).toInt()).also {
            it.addRule(RelativeLayout.CENTER_VERTICAL)
            it.addRule(RelativeLayout.ALIGN_PARENT_END)
            it.marginEnd = dp(6).toInt()
        }

        bar.addView(back); bar.addView(tvTitle); bar.addView(btnTagalog); bar.addView(floatBtn); bar.addView(gear)
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

    // ─── helpers ─────────────────���───────────────────────────────────────────

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
        currentFileUri = uri
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

            withContext(Dispatchers.IO) { cacheFileContent(rawText) }

            displayParagraphs(paras)

            val resumePara = intent?.getIntExtra(EXTRA_START_PARA, -1) ?: -1
            if (resumePara in paragraphs.indices) {
                currentPara = resumePara
                updateProgress()
                scrollToPara(currentPara)
                tvStatus.text = "Resumed from overlay — ${paragraphs.size} paragraphs"
            }
            saveReadingState()
        }
    }

    /** Reloads the last-read novel from the app's internal cache (survives app restarts / back button). */
    private fun loadFromCache(savedPara: Int, wasTagalog: Boolean, title: String) {
        val cacheFile = File(filesDir, CACHE_FILENAME)
        if (!cacheFile.exists()) {
            Toast.makeText(this, "Wala nang naka-save na file", Toast.LENGTH_SHORT).show()
            showPlaceholder()
            return
        }
        currentFileUri = Uri.fromFile(cacheFile)
        tvStatus.text = "Loading…"
        tvTitle.text = if (title.length > 26) title.take(24) + "…" else title

        lifecycleScope.launch {
            val rawText = withContext(Dispatchers.IO) { cacheFile.readText() }
            if (rawText.isBlank()) { tvStatus.text = "Could not read saved file"; return@launch }
            val paras = withContext(Dispatchers.Default) { parseParagraphs(rawText) }
            if (paras.isEmpty()) { tvStatus.text = "No readable text found"; return@launch }

            displayParagraphs(paras)
            currentPara = savedPara.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
            updateProgress()
            scrollToPara(currentPara)
            tvStatus.text = "Resumed — ${paragraphs.size} paragraphs"
            saveReadingState()

            if (wasTagalog) translateToTagalog()
        }
    }

    /** Copies the raw novel text into app-internal storage so it can be reopened after the app is closed. */
    private fun cacheFileContent(rawText: String) {
        try { File(filesDir, CACHE_FILENAME).writeText(rawText) } catch (_: Exception) {}
    }

    private fun saveReadingState() {
        if (paragraphs.isEmpty()) return
        prefs.edit()
            .putString(KEY_TITLE, tvTitle.text.toString())
            .putInt(KEY_PARA, currentPara)
            .putBoolean(KEY_TAGALOG, isTagalogMode)
            .putBoolean(KEY_HAS_CACHE, true)
            .apply()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Overlay mode
    // ═══════════════════════════════════════════════════════���══════════════��══

    private fun openOverlayMode() {
        if (paragraphs.isEmpty()) {
            Toast.makeText(this, "Open a file first", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = currentFileUri
        if (uri == null) {
            Toast.makeText(this, "File not available for overlay", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" first, then tap again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return
        }
        if (isReading) pauseReading()
        val svcIntent = Intent(this, NovelReaderOverlayService::class.java).apply {
            putExtra(NovelReaderOverlayService.EXTRA_FILE_URI, uri.toString())
            putExtra(NovelReaderOverlayService.EXTRA_START_PARA, currentPara)
            putExtra(NovelReaderOverlayService.EXTRA_TAGALOG, isTagalogMode)
        }
        ContextCompat.startForegroundService(this, svcIntent)
        moveTaskToBack(true)
    }

    // ═══════════════════════════════════��═════════════════════════════════════
    // Tagalog auto-translate
    // ═════════════════════════════════════════════════════════════════════════

    private fun toggleTagalogMode() {
        if (translateInProgress) return
        if (paragraphs.isEmpty() && originalParagraphs.isEmpty()) {
            Toast.makeText(this, "Open a file first", Toast.LENGTH_SHORT).show()
            return
        }
        if (isTagalogMode) restoreOriginalParagraphs() else translateToTagalog()
    }

    private fun translateToTagalog() {
        val wasReading = isReading
        if (wasReading) pauseReading()
        translateInProgress = true
        tvStatus.text = "Ini-hahanda ang Tagalog…"
        showDownloadDialog()

        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) { NovelTranslator.ensureModelReady() }
            dismissDownloadDialog()
            if (!ready) {
                translateInProgress = false
                tvStatus.text = "Hindi ma-download ang Tagalog model — check internet"
                Toast.makeText(
                    this@NovelReaderActivity,
                    "Kailangan ng internet minsan lang para i-download ang Tagalog model",
                    Toast.LENGTH_LONG
                ).show()
                return@launch
            }

            val total = originalParagraphs.size
            for (i in originalParagraphs.indices) {
                val translated = translatedCache[i] ?: withContext(Dispatchers.IO) {
                    NovelTranslator.translate(originalParagraphs[i])
                } ?: originalParagraphs[i]
                translatedCache[i] = translated
                if (i < paragraphs.size) paragraphs[i] = translated
                paraViews.getOrNull(i)?.text = translated
                if (i % 5 == 0 || i == total - 1) {
                    tvStatus.text = "Tina-translate… ${i + 1}/$total"
                }
            }

            isTagalogMode = true
            val voiceLabel = applyTagalogVoice()
            tvStatus.text = "🇵🇭 Tagalog mode ($voiceLabel) — ${paragraphs.size} paragraphs"
            btnTagalog.setTextColor(C_ACCENT)
            translateInProgress = false
            saveReadingState()
            if (wasReading) startReading()
        }
    }

    private fun restoreOriginalParagraphs() {
        val wasReading = isReading
        if (wasReading) pauseReading()
        paragraphs.clear(); paragraphs.addAll(originalParagraphs)
        paraViews.forEachIndexed { i, tv -> tv.text = paragraphs.getOrElse(i) { "" } }
        isTagalogMode = false
        restoreOriginalVoice()
        tvStatus.text = "English mode — ${paragraphs.size} paragraphs"
        btnTagalog.setTextColor(C_MUTED)
        saveReadingState()
        if (wasReading) startReading()
    }

    /**
     * Switches TTS to an actual Filipino/Tagalog voice so playback is understandable — not just a
     * text-label switch. Prefers `setLanguage()` (Android's own engine resolves the correct voice
     * for that locale) over manually filtering `tts.voices`, since a language can be *listed* in
     * system Settings without its voice data actually being downloaded on-device.
     * Returns a short status label describing what happened (shown in the reader's status bar).
     */
    private fun applyTagalogVoice(): String {
        val t = tts ?: return "walang TTS"
        voiceBeforeTagalog = t.voice

        val fil = Locale("fil", "PH")
        val tl = Locale("tl", "PH")
        var result = t.setLanguage(fil)
        if (result < TextToSpeech.LANG_AVAILABLE) result = t.setLanguage(tl)

        when (result) {
            TextToSpeech.LANG_MISSING_DATA -> {
                Toast.makeText(
                    this,
                    "Naka-set na ang Filipino bilang wika pero kulang pa ang voice data — bubuksan ang download screen",
                    Toast.LENGTH_LONG
                ).show()
                try { startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) } catch (_: Exception) {}
                return "kulang ang voice data"
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                Toast.makeText(
                    this,
                    "Walang Tagalog voice sa TTS engine mo — subukan mong palitan ang \"Preferred engine\" sa Settings > Text-to-speech (gamitin ang Google engine)",
                    Toast.LENGTH_LONG
                ).show()
                return "walang suportang Tagalog"
            }
        }

        // Language switch succeeded — optionally pick a more specific offline voice if several exist.
        val allVoices = t.voices ?: emptySet()
        val betterVoice = allVoices.firstOrNull {
            it.locale.language in setOf("fil", "tl") && !it.isNetworkConnectionRequired
        } ?: allVoices.firstOrNull { it.locale.language in setOf("fil", "tl") }
        if (betterVoice != null) t.voice = betterVoice

        return t.voice?.name?.replace(Regex("[-_]"), " ") ?: "Filipino"
    }

    private fun restoreOriginalVoice() {
        val t = tts ?: return
        val v = voiceBeforeTagalog
        if (v != null) t.voice = v else t.language = Locale.ENGLISH
        voiceBeforeTagalog = null
    }

    private fun showDownloadDialog() {
        if (downloadDialog?.isShowing == true) return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(28).toInt(), dp(22).toInt(), dp(28).toInt(), dp(22).toInt())
        }
        row.addView(ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(C_ACCENT)
        })
        row.addView(TextView(this).apply {
            text = "  Dina-download ang Tagalog voice pack…"
            setTextColor(C_TEXT); textSize = 14f
        })
        downloadDialog = AlertDialog.Builder(this)
            .setView(row)
            .setCancelable(false)
            .show()
        downloadDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    private fun dismissDownloadDialog() {
        downloadDialog?.dismiss()
        downloadDialog = null
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
    // ════════��════════════════════════════════════════════════════════════════

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

    // ═════���══════════════════════════════════════════════════════════════════��
    // Display
    // ═════════════════════════════════════════════════════════════════════════

    private fun displayParagraphs(paras: List<String>) {
        paragraphs.clear(); paragraphs.addAll(paras)
        originalParagraphs.clear(); originalParagraphs.addAll(paras)
        translatedCache.clear()
        isTagalogMode = false
        if (::btnTagalog.isInitialized) btnTagalog.setTextColor(C_MUTED)
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
        val hasCache = prefs.getBoolean(KEY_HAS_CACHE, false) && File(filesDir, CACHE_FILENAME).exists()

        if (hasCache) {
            val title = prefs.getString(KEY_TITLE, "Novel") ?: "Novel"
            val savedPara = prefs.getInt(KEY_PARA, 0)
            val wasTagalog = prefs.getBoolean(KEY_TAGALOG, false)

            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(40).toInt(), dp(64).toInt(), dp(40).toInt(), 0)
            }
            wrap.addView(TextView(this).apply {
                text = "📖"; textSize = 40f; gravity = Gravity.CENTER
            })
            wrap.addView(TextView(this).apply {
                text = "Continue reading"
                setTextColor(C_MUTED); textSize = 13.5f; gravity = Gravity.CENTER
                setPadding(0, dp(16).toInt(), 0, dp(4).toInt())
            })
            wrap.addView(TextView(this).apply {
                text = title
                setTextColor(C_HEADING); textSize = 18f; gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setSingleLine(); ellipsize = android.text.TextUtils.TruncateAt.END
            })
            wrap.addView(TextView(this).apply {
                text = "Paragraph ${savedPara + 1}" + if (wasTagalog) "   •   🇵🇭 Tagalog" else ""
                setTextColor(C_MUTED); textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(0, dp(6).toInt(), 0, dp(22).toInt())
            })

            val continueBtn = TextView(this).apply {
                text = "▶   Continue Reading"
                setTextColor(Color.WHITE); textSize = 14.5f
                setTypeface(null, Typeface.BOLD); gravity = Gravity.CENTER
                setPadding(dp(28).toInt(), dp(13).toInt(), dp(28).toInt(), dp(13).toInt())
                val bg = GradientDrawable().apply {
                    cornerRadius = dp(28f); setColor(C_ACCENT)
                }
                background = RippleDrawable(ColorStateList.valueOf(Color.WHITE), bg, null)
                isClickable = true; isFocusable = true
                setOnClickListener { loadFromCache(savedPara, wasTagalog, title) }
            }
            continueBtn.layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            wrap.addView(continueBtn)

            wrap.addView(TextView(this).apply {
                text = "or share / open a new .txt or .epub file"
                setTextColor(C_MUTED); textSize = 12f; gravity = Gravity.CENTER
                setPadding(0, dp(26).toInt(), 0, 0)
            })

            paraContainer.addView(wrap)
        } else {
            val msg = TextView(this).apply {
                text = "📖\n\nShare or open any\n.txt or .epub novel file\nwith this app"
                setTextColor(C_MUTED); textSize = 15f; gravity = Gravity.CENTER
                setLineSpacing(0f, 1.6f)
                setPadding(dp(40).toInt(), dp(80).toInt(), dp(40).toInt(), 0)
            }
            paraContainer.addView(msg)
        }
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

    // ══��══════════════════════════════════════════════════════════════════════
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
        saveReadingState()
        if (wasReading) startReading()
    }

    private fun skipNext() {
        val wasReading = isReading
        if (wasReading) { isReading = false; tts?.stop(); readingJob?.cancel() }
        currentPara = (currentPara + 1).coerceAtMost((paragraphs.size - 1).coerceAtLeast(0))
        updateProgress(); scrollToPara(currentPara)
        saveReadingState()
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
            withContext(Dispatchers.Main) { saveReadingState() }
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

    // ═════════════��═══════════════════════════════════════════════════════════
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
    // ════════════════════════════════════════════════���════════════════════════

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

        // Voice spinner — include English AND any installed Filipino/Tagalog voices so the user
        // can see/pick a Tagalog voice here too, not just via the auto Tagalog-mode switch.
        val voices = tts?.voices?.filter {
            it.locale.language == "en" || it.locale.language == "fil" || it.locale.language == "tl"
        }?.sortedWith(compareBy(
            { it.locale.language != "fil" && it.locale.language != "tl" },
            { it.locale.country }, { it.name }
        )) ?: emptyList()
        var voiceSpin: Spinner? = null
        if (voices.isNotEmpty()) {
            inner.addView(sectionLabel("VOICE"))
            val names = voices.map { v ->
                val flag = when {
                    v.locale.language == "fil" || v.locale.language == "tl" -> "🇵🇭"
                    v.locale.country == "US" -> "🇺🇸" ; v.locale.country == "GB" -> "🇬🇧"
                    v.locale.country == "AU" -> "🇦🇺" ; v.locale.country == "IN" -> "🇮🇳"
                    v.locale.country == "CA" -> "🇨🇦" ; else -> "🌐"
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

    companion object {
        const val EXTRA_START_PARA = "extra_start_para"
        private const val PREFS_NAME = "novel_reader_prefs"
        private const val KEY_TITLE = "title"
        private const val KEY_PARA = "para"
        private const val KEY_TAGALOG = "tagalog"
        private const val KEY_HAS_CACHE = "has_cache"
        private const val CACHE_FILENAME = "last_novel_cache.txt"
    }
}
