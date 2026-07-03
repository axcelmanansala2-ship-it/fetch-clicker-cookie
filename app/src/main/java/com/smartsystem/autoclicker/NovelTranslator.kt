package com.smartsystem.autoclicker

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * On-device English → Tagalog (Filipino) translation using Google's ML Kit
 * Translate (neural machine translation), shared by NovelReaderActivity and
 * NovelReaderOverlayService so both stay consistent.
 *
 * The Tagalog model (~30 MB) is downloaded once — needs internet for that —
 * then all translation runs fully offline on-device.
 */
object NovelTranslator {

    private val translator: Translator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.TAGALOG)
            .build()
        Translation.getClient(options)
    }

    /** Downloads the Tagalog model if not already present. Returns true if ready to translate. */
    suspend fun ensureModelReady(): Boolean = try {
        suspendCancellableCoroutine { cont ->
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions)
                .addOnSuccessListener { if (cont.isActive) cont.resume(true) }
                .addOnFailureListener { if (cont.isActive) cont.resume(false) }
        }
    } catch (_: Exception) { false }

    /** Translates [text] to Tagalog. Returns null on failure (caller should fall back to original text). */
    suspend fun translate(text: String): String? {
        if (text.isBlank()) return text
        return try {
            suspendCancellableCoroutine { cont ->
                translator.translate(text)
                    .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                    .addOnFailureListener { if (cont.isActive) cont.resume(null) }
            }
        } catch (_: Exception) { null }
    }
}
