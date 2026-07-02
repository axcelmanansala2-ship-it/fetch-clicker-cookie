package com.smartsystem.autoclicker

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log

/**
 * v3 fixes:
 *  - Looser color threshold: R>150, G<90, B<80
 *    Catches low-quality compressed bullseye reds that v2 missed (was R>200 — too strict)
 *  - MAX_CLUSTER_PX still filters out the large red container
 *  - ROI_RIGHT widened to 0.78 (catches right-side targets that were cut off)
 *  - Consecutive requirement dropped to 1 (max cluster cap is now the primary noise guard)
 */
object BullseyeDetector {

    private const val TAG            = "BullseyeDetector"
    private const val SAMPLE_SCALE   = 4
    private const val MIN_CLUSTER_PX = 5
    private const val MAX_CLUSTER_PX = 160  // large red objects (container) still blocked
    private const val CLUSTER_RADIUS = 7

    private const val ROI_LEFT   = 0.18f
    private const val ROI_RIGHT  = 0.78f   // widened — was 0.72, right targets were cut off
    private const val ROI_TOP    = 0.15f
    private const val ROI_BOTTOM = 0.72f

    private var consecutiveHits = 0
    private const val REQUIRED_CONSECUTIVE = 1  // dropped from 2 — max cluster cap handles noise

    data class DetectionResult(
        val center: PointF,
        val clusterSize: Int,
        val distanceFromCenter: Float
    )

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
        if (consecutiveHits < REQUIRED_CONSECUTIVE) return null

        val cx = best.first.x * SAMPLE_SCALE.toFloat()
        val cy = best.first.y * SAMPLE_SCALE.toFloat()
        val dist = Math.hypot((cx - origW / 2.0), (cy - origH / 2.0)).toFloat()

        Log.d(TAG, "Bullseye at ($cx,$cy) cluster=${best.second} dist=$dist")
        return DetectionResult(PointF(cx, cy), best.second, dist)
    }

    fun reset() { consecutiveHits = 0 }

    /**
     * v3 color: R>150, G<90, B<80
     * Catches compressed/low-quality reds that v2 missed.
     * MAX_CLUSTER_PX cap compensates for the looser threshold.
     */
    private fun isTargetColor(px: Int): Boolean {
        val r = (px shr 16) and 0xFF
        val g = (px shr 8)  and 0xFF
        val b =  px         and 0xFF
        return r > 150 && g < 90 && b < 80
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
