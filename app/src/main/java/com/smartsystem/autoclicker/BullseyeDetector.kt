package com.smartsystem.autoclicker

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log

/**
 * Detects CODM bullseye targets via color-based pixel scanning.
 *
 * v2 fixes:
 *  - Stricter color threshold: only pure saturated red (R>200, G<55, B<55)
 *    eliminates false positives from orange containers, red buildings, CODM logo
 *  - Tighter ROI: center strip only (x 22-72%, y 18-70%)
 *    cuts out the right-side shipping container and bottom HUD
 *  - Max cluster cap: clusters > MAX_CLUSTER_PX treated as background objects
 *  - Requires 2 consecutive detections before reporting a hit (reduces reaction to noise)
 */
object BullseyeDetector {

    private const val TAG = "BullseyeDetector"
    private const val SAMPLE_SCALE   = 4    // scan at 1/4 resolution
    private const val MIN_CLUSTER_PX = 5    // too few pixels = noise
    private const val MAX_CLUSTER_PX = 180  // too many pixels = background object (container, building)
    private const val CLUSTER_RADIUS = 7

    // Tighter ROI — cuts out right-side container, CODM logo, and HUD strips
    private const val ROI_LEFT   = 0.22f
    private const val ROI_RIGHT  = 0.72f   // was 0.90 — now excludes right container area
    private const val ROI_TOP    = 0.18f
    private const val ROI_BOTTOM = 0.70f   // was 0.85 — now excludes bottom HUD

    // Consecutive-detection requirement before reporting (reduces noise reactions)
    private var consecutiveHits = 0
    private const val REQUIRED_CONSECUTIVE = 2

    data class DetectionResult(
        val center: PointF,
        val clusterSize: Int,
        val distanceFromCenter: Float
    )

    /** Scan [bitmap] for bullseye. Returns coords in original pixel space, or null. */
    fun detect(bitmap: Bitmap): DetectionResult? {
        val origW = bitmap.width
        val origH = bitmap.height

        val scaledW = origW / SAMPLE_SCALE
        val scaledH = origH / SAMPLE_SCALE
        val scaled = Bitmap.createScaledBitmap(bitmap, scaledW, scaledH, false)

        val roiLeft   = (scaledW * ROI_LEFT).toInt()
        val roiRight  = (scaledW * ROI_RIGHT).toInt()
        val roiTop    = (scaledH * ROI_TOP).toInt()
        val roiBottom = (scaledH * ROI_BOTTOM).toInt()

        val hotPixels = mutableListOf<Pair<Int, Int>>()
        for (y in roiTop until roiBottom) {
            for (x in roiLeft until roiRight) {
                if (isTargetColor(scaled.getPixel(x, y))) hotPixels.add(Pair(x, y))
            }
        }
        scaled.recycle()

        if (hotPixels.size < MIN_CLUSTER_PX) {
            consecutiveHits = 0
            return null
        }

        val best = findDensestCluster(hotPixels, scaledW, scaledH)
        if (best == null || best.second < MIN_CLUSTER_PX || best.second > MAX_CLUSTER_PX) {
            consecutiveHits = 0
            return null
        }

        consecutiveHits++
        if (consecutiveHits < REQUIRED_CONSECUTIVE) return null   // wait for next frame to confirm

        val cx = best.first.x * SAMPLE_SCALE.toFloat()
        val cy = best.first.y * SAMPLE_SCALE.toFloat()
        val dist = Math.hypot(
            (cx - origW / 2.0),
            (cy - origH / 2.0)
        ).toFloat()

        Log.d(TAG, "Bullseye confirmed at ($cx,$cy) cluster=${best.second} dist=$dist hits=$consecutiveHits")
        return DetectionResult(PointF(cx, cy), best.second, dist)
    }

    /** Reset consecutive counter (call when service stops). */
    fun reset() { consecutiveHits = 0 }

    /**
     * Strict red-only threshold.
     * Excludes orange (high G), pink (high B), and dark reds to avoid
     * containers, UI elements, and the CODM red logo.
     */
    private fun isTargetColor(px: Int): Boolean {
        val r = (px shr 16) and 0xFF
        val g = (px shr 8)  and 0xFF
        val b =  px         and 0xFF
        // Pure saturated red only — bullseye center circle color
        return r > 200 && g < 55 && b < 55
    }

    private fun findDensestCluster(
        pixels: List<Pair<Int, Int>>,
        maxW: Int,
        maxH: Int
    ): Pair<PointF, Int>? {
        if (pixels.isEmpty()) return null
        val grid = Array(maxH) { BooleanArray(maxW) }
        for ((x, y) in pixels) if (y < maxH && x < maxW) grid[y][x] = true

        var bestCount = 0
        var bestCx = 0f; var bestCy = 0f

        for ((px, py) in pixels) {
            var count = 0; var sumX = 0; var sumY = 0
            for (dy in -CLUSTER_RADIUS..CLUSTER_RADIUS) {
                for (dx in -CLUSTER_RADIUS..CLUSTER_RADIUS) {
                    val nx = px + dx; val ny = py + dy
                    if (nx in 0 until maxW && ny in 0 until maxH && grid[ny][nx]) {
                        count++; sumX += nx; sumY += ny
                    }
                }
            }
            if (count > bestCount) {
                bestCount = count
                bestCx = sumX.toFloat() / count
                bestCy = sumY.toFloat() / count
            }
        }

        return if (bestCount >= MIN_CLUSTER_PX) Pair(PointF(bestCx, bestCy), bestCount) else null
    }
}
