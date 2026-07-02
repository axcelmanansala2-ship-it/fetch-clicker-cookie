package com.smartsystem.autoclicker

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.ImageView
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
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ManhwaReaderActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var scrollView: ScrollView
    private lateinit var imageView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var btnReadPause: Button
    private lateinit var btnStop: Button
    private lateinit var btnAutoScroll: ToggleButton

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isReading = false
    private var autoScrollEnabled = true
    private var readingJob: Job? = null
    private var fullBitmap: Bitmap? = null

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_manhwa_reader)

        scrollView    = findViewById(R.id.manhwaScrollView)
        imageView     = findViewById(R.id.manhwaImageView)
        tvStatus      = findViewById(R.id.tvManhwaStatus)
        btnReadPause  = findViewById(R.id.btnReadPause)
        btnStop       = findViewById(R.id.btnManhwaStop)
        btnAutoScroll = findViewById(R.id.btnAutoScroll)

        tts = TextToSpeech(this, this)
        loadImageFromIntent()

        btnReadPause.setOnClickListener {
            if (isReading) pauseReading() else startReading()
        }
        btnStop.setOnClickListener { stopReading() }
        btnAutoScroll.setOnCheckedChangeListener { _, checked -> autoScrollEnabled = checked }
    }

    @Suppress("DEPRECATION")
    private fun loadImageFromIntent() {
        val uri: Uri? = intent?.data
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                intent?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else
                intent?.getParcelableExtra(Intent.EXTRA_STREAM)

        if (uri == null) {
            tvStatus.text = "⚠ No file received"
            return
        }
        try {
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            val stream = contentResolver.openInputStream(uri)
                ?: run { tvStatus.text = "⚠ Cannot open file"; return }
            fullBitmap = BitmapFactory.decodeStream(stream, null, opts)
            stream.close()
            imageView.setImageBitmap(fullBitmap)
            tvStatus.text = "📖 Loaded"
        } catch (e: Exception) {
            tvStatus.text = "⚠ Error loading"
        }
    }

    private fun startReading() {
        if (!ttsReady) { Toast.makeText(this, "TTS not ready yet", Toast.LENGTH_SHORT).show(); return }
        if (fullBitmap == null) { Toast.makeText(this, "No image loaded", Toast.LENGTH_SHORT).show(); return }
        isReading = true
        btnReadPause.text = "⏸"
        readingJob = lifecycleScope.launch { readPanels() }
    }

    private fun pauseReading() {
        isReading = false
        tts?.stop()
        readingJob?.cancel()
        btnReadPause.text = "▶"
        tvStatus.text = "⏸ Paused"
    }

    private fun stopReading() {
        isReading = false
        tts?.stop()
        readingJob?.cancel()
        btnReadPause.text = "▶"
        tvStatus.text = "⏹ Stopped"
        scrollView.smoothScrollTo(0, 0)
    }

    private suspend fun readPanels() {
        if (imageView.height == 0) delay(600)
        val imgH   = imageView.height.takeIf { it > 0 } ?: return
        val screenH = scrollView.height.takeIf { it > 0 } ?: return
        val bmp    = fullBitmap ?: return

        var scrollY  = scrollView.scrollY
        var panelNum = 0

        while (isReading && scrollY < imgH) {
            val panelBottom = (scrollY + screenH).coerceAtMost(imgH)
            val scaleY  = bmp.height.toFloat() / imgH
            val bmpTop  = (scrollY * scaleY).toInt().coerceIn(0, bmp.height - 1)
            val bmpH    = ((panelBottom - scrollY) * scaleY).toInt()
                .coerceAtLeast(1).coerceAtMost(bmp.height - bmpTop)

            val slice = Bitmap.createBitmap(bmp, 0, bmpTop, bmp.width, bmpH)
            tvStatus.text = "🔍 Detecting..."

            val text = withContext(Dispatchers.IO) { recognizeText(slice) }
            slice.recycle()

            if (!isReading) break

            if (text.isNotBlank()) {
                panelNum++
                tvStatus.text = "🔊 Panel $panelNum"
                speakAndWait(text)
                if (!isReading) break

                if (autoScrollEnabled) {
                    val nextY = scrollY + screenH
                    withContext(Dispatchers.Main) { scrollView.smoothScrollTo(0, nextY) }
                    delay(1000)
                    scrollY = withContext(Dispatchers.Main) { scrollView.scrollY }
                } else {
                    tvStatus.text = "✅ Panel $panelNum done\nScroll manually"
                    isReading = false
                    btnReadPause.text = "▶"
                    break
                }
            } else {
                tvStatus.text = "📷 No text here\nScroll & tap ▶"
                isReading = false
                btnReadPause.text = "▶"
                break
            }
        }

        if (isReading) {
            tvStatus.text = "✅ Done!"
            isReading = false
            btnReadPause.text = "▶"
        }
    }

    private suspend fun recognizeText(bmp: Bitmap): String = suspendCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resume("") }
    }

    private suspend fun speakAndWait(text: String): Unit = suspendCoroutine { cont ->
        val uid = "panel_${System.currentTimeMillis()}"
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
            tvStatus.text = "📖 Ready\nTap ▶ to read"
        } else {
            tvStatus.text = "⚠ TTS failed"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        readingJob?.cancel()
        tts?.shutdown()
        recognizer.close()
        fullBitmap?.recycle()
    }
}
