package com.smartsystem.autoclicker

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.smartsystem.autoclicker.models.DetectionTarget
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class DetectionEngine {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    data class DetectionResult(
        val target: DetectionTarget,
        val tapPoint: PointF,
        val allDetectedText: String
    )

    /**
     * Looks ONLY for [target] text in the screenshot.
     * Returns null if not found, along with all detected text for debugging.
     */
    suspend fun findTarget(
        bitmap: Bitmap,
        target: DetectionTarget
    ): Pair<PointF?, String> {
        val visionResult = runOcr(bitmap) ?: return Pair(null, "(OCR failed)")
        val allText = buildDebugText(visionResult.textBlocks)
        val query = target.textQuery.trim()
        val hit = findTextInBlocks(visionResult.textBlocks, query)
        return if (hit != null) {
            Pair(rectCenter(hit), allText)
        } else {
            Pair(null, allText)
        }
    }

    private fun buildDebugText(blocks: List<Text.TextBlock>): String {
        val words = mutableListOf<String>()
        for (block in blocks) {
            for (line in block.lines) {
                words.add(line.text)
            }
        }
        return words.joinToString(" | ").take(200)
    }

    private suspend fun runOcr(bitmap: Bitmap): Text? =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { text -> cont.resume(text) }
                .addOnFailureListener { e ->
                    Log.e(TAG, "OCR failed", e)
                    cont.resume(null)
                }
        }

    private fun findTextInBlocks(blocks: List<Text.TextBlock>, query: String): RectF? {
        val q = query.lowercase().trim()
        // Search in order: elements → lines → blocks (smallest → largest)
        for (block in blocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    if (element.text.lowercase().trim().contains(q)) {
                        return element.boundingBox?.let {
                            RectF(it.left.toFloat(), it.top.toFloat(),
                                it.right.toFloat(), it.bottom.toFloat())
                        }
                    }
                }
                if (line.text.lowercase().trim().contains(q)) {
                    return line.boundingBox?.let {
                        RectF(it.left.toFloat(), it.top.toFloat(),
                            it.right.toFloat(), it.bottom.toFloat())
                    }
                }
            }
            if (block.text.lowercase().trim().contains(q)) {
                return block.boundingBox?.let {
                    RectF(it.left.toFloat(), it.top.toFloat(),
                        it.right.toFloat(), it.bottom.toFloat())
                }
            }
        }
        return null
    }

    private fun rectCenter(rect: RectF) = PointF(rect.centerX(), rect.centerY())

    fun close() = recognizer.close()

    companion object {
        private const val TAG = "DetectionEngine"
    }
}
