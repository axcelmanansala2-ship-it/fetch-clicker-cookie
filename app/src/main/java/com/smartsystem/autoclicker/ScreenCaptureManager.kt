package com.smartsystem.autoclicker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager

/**
 * Manages the [MediaProjection] virtual display and exposes [captureScreen]
 * to grab a [Bitmap] snapshot of the current screen content.
 */
class ScreenCaptureManager(
    private val context: Context,
    private val mediaProjection: MediaProjection
) {

    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val metrics: DisplayMetrics = DisplayMetrics()

    init {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        setupVirtualDisplay()
    }

    private fun setupVirtualDisplay() {
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels,
            PixelFormat.RGBA_8888, 2
        )
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "SmartAutoClickerCapture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )
        Log.d(TAG, "VirtualDisplay created ${metrics.widthPixels}x${metrics.heightPixels}")
    }

    /**
     * Grab the latest screen frame as a [Bitmap], or null if no frame is ready.
     * Call from a background thread.
     */
    fun captureScreen(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        return try {
            image = reader.acquireLatestImage() ?: return null
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * metrics.widthPixels

            val bitmap = Bitmap.createBitmap(
                metrics.widthPixels + rowPadding / pixelStride,
                metrics.heightPixels,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            // Crop away any row padding
            if (rowPadding > 0) {
                Bitmap.createBitmap(bitmap, 0, 0, metrics.widthPixels, metrics.heightPixels)
            } else {
                bitmap
            }
        } catch (e: Exception) {
            Log.e(TAG, "captureScreen failed", e)
            null
        } finally {
            image?.close()
        }
    }

    fun release() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection.stop()
        virtualDisplay = null
        imageReader = null
        Log.d(TAG, "ScreenCaptureManager released")
    }

    companion object {
        private const val TAG = "ScreenCaptureManager"
    }
}
