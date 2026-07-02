package com.smartsystem.autoclicker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
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
    private lateinit var btnReadPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnAutoScroll: ToggleButton

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isReading = false
    private var autoScrollEnabled = true
    private var readingJob: Job? = null
    private val pageBitmaps = mutableListOf<Bitmap>()
    private var currentPageIndex = 0

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manhwa_reader)

        scrollView     = findViewById(R.id.manhwaScrollView)
        pagesContainer = findViewById(R.id.pagesContainer)
        tvStatus       = findViewById(R.id.tvManhwaStatus)
        btnReadPause   = findViewById(R.id.btnReadPause)
        btnStop        = findViewById(R.id.btnManhwaStop)
        btnAutoScroll  = findViewById(R.id.btnAutoScroll)

        tts = TextToSpeech(this, this)
        loadFileFromIntent()

        btnReadPause.setOnClickListener {
            if (isReading) pauseReading() else startReading()
        }
        btnStop.setOnClickListener { stopReading() }
        btnAutoScroll.setOnCheckedChangeListener { _, checked -> autoScrollEnabled = checked }
    }

    @Suppress("DEPRECATION")
    private fun loadFileFromIntent() {
        val uri: Uri? = intent?.data
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else
                intent?.getParcelableExtra(Intent.EXTRA_STREAM)

        if (uri == null) { tvStatus.text = "No file received"; return }

        tvStatus.text = "Loading..."
        lifecycleScope.launch {
            val bitmaps = withContext(Dispatchers.IO) {
                val mimeType = contentResolver.getType(uri) ?: ""
                val isPdf = mimeType.contains("pdf", ignoreCase = true)
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

            val count = bitmaps.size
            tvStatus.text = "$count pages\nTap Play"
        }
    }

    // ── Loaders ──────────────────────────────────────────────────────────────

    private fun loadImagePage(uri: Uri): List<Bitmap> {
        return try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val stream = contentResolver.openInputStream(uri) ?: return emptyList()
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
            val screenWidth = resources.displayMetrics.widthPixels

            for (i in 0 until renderer.pageCount) {
                val page = renderer.openPage(i)
                val scale = screenWidth.toFloat() / page.width.coerceAtLeast(1)
                val pageH = (page.height * scale).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(screenWidth, pageH, Bitmap.Config.ARGB_8888)
                Canvas(bmp).drawColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                bitmaps.add(bmp)
            }
        } catch (e: Exception) {
            // return whatever pages loaded so far
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

    // ── Reading logic ─────────────────────────────────────────────────────────

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
        val pg = currentPageIndex + 1
        tvStatus.text = "Paused p.$pg"
    }

    private fun stopReading() {
        isReading = false
        tts?.stop()
        readingJob?.cancel()
        btnReadPause.text = "\u25B6"
        currentPageIndex = 0
        tvStatus.text = "Stopped"
        scrollView.smoothScrollTo(0, 0)
    }

    private suspend fun readPages(startIndex: Int) {
        val total = pageBitmaps.size
        for (i in startIndex until total) {
            if (!isReading) break
            currentPageIndex = i
            val pg = i + 1
            tvStatus.text = "Scan $pg/$total"

            val text = withContext(Dispatchers.IO) { recognizeText(pageBitmaps[i]) }
            if (!isReading) break

            if (text.isNotBlank()) {
                tvStatus.text = "Read $pg/$total"
                speakAndWait(text)
                if (!isReading) break

                if (autoScrollEnabled && i + 1 < total) {
                    val nextView = pagesContainer.getChildAt(i + 1)
                    if (nextView != null) {
                        scrollView.smoothScrollTo(0, nextView.top)
                        delay(800)
                    }
                }
            } else {
                currentPageIndex = i + 1
                tvStatus.text = "No text p.$pg\nScroll & Play"
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
            if (pageBitmaps.isNotEmpty()) tvStatus.text = "Ready\nTap Play"
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
