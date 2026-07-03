package com.smartsystem.autoclicker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import kotlin.math.atan2

/**
 * Detected speech bubble: bounding rectangle + an ordered outline polygon
 * (boundary pixels sorted by angle from centroid, in source-bitmap coordinates).
 * The outline is used to draw a fluid ESP border that matches the bubble's shape.
 */
data class BubbleInfo(
    val rect: Rect,
    val outline: List<PointF>   // clockwise, source-bitmap coordinates
)

/**
 * Detects speech bubbles in a manhwa/comic page bitmap using pure pixel analysis.
 *
 * Strategy:
 *  1. Scale to a small working bitmap for speed.
 *  2. Classify each pixel as a "bubble-interior candidate":
 *       • Bright (≥ BRIGHT_THRESH)      → white/near-white bubbles
 *       • Saturated non-dark            → colored bubbles (red, yellow …)
 *  3. BFS flood-fill from all 4 image edges to mark the background.
 *  4. BFS connected-components on remaining enclosed candidates.
 *     Filters applied:  min/max area, aspect ratio, fill ratio.
 *     Fill ratio  =  component_pixels / bounding_box_area. This is the
 *     main guard against action-artwork splatters and sparse SFX text,
 *     which have very low fill ratios while real speech bubbles are solid.
 *  5. For each passing component, collect its boundary pixels (those adjacent
 *     to a non-component neighbour), sort them by angle from the centroid to
 *     obtain an ordered outline polygon, then scale back to source coordinates.
 */
object BubbleDetector {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private const val WORK_WIDTH    = 360
    private const val MIN_AREA      = 1500          // pixels at work scale
    private const val MAX_AREA_PCT  = 0.55f
    private const val MIN_ASPECT    = 0.15f
    private const val MAX_ASPECT    = 5.0f
    private const val MIN_DIM       = 20            // min bounding-box side (work scale)
    private const val MIN_FILL_RATIO = 0.22f        // component / bbox area (KEY false-pos guard)
    private const val BRIGHT_THRESH = 160
    private const val SAT_THRESH    = 55
    private const val SAT_BRIGHT    = 40
    private const val MAX_OUTLINE_PTS = 48          // max points in returned outline

    // ── Public API ────────────────────────────────────────────────────────────

    fun detect(src: Bitmap): List<BubbleInfo> {
        val srcW = src.width; val srcH = src.height
        if (srcW == 0 || srcH == 0) return emptyList()

        // 1. Scale down
        val scale = WORK_WIDTH.toFloat() / srcW
        val ww = WORK_WIDTH
        val wh = (srcH * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, ww, wh, false)
        val pixels = IntArray(ww * wh)
        small.getPixels(pixels, 0, ww, 0, 0, ww, wh)
        small.recycle()
        val size = ww * wh

        // 2. Classify pixels
        val isCandidate = BooleanArray(size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val brightness = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
            val max = maxOf(r, g, b); val min = minOf(r, g, b)
            val sat = if (max == 0) 0 else (max - min) * 255 / max
            isCandidate[i] = brightness > BRIGHT_THRESH ||
                    (sat > SAT_THRESH && brightness > SAT_BRIGHT)
        }

        // 3. Flood-fill background from edges
        val isBg = BooleanArray(size)
        val queue = ArrayDeque<Int>(minOf(size / 4, 65536))
        fun enqueue(idx: Int) {
            if (idx < 0 || idx >= size || isBg[idx] || !isCandidate[idx]) return
            isBg[idx] = true; queue.addLast(idx)
        }
        for (x in 0 until ww) { enqueue(x); enqueue((wh - 1) * ww + x) }
        for (y in 0 until wh) { enqueue(y * ww); enqueue(y * ww + ww - 1) }
        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % ww; val y = idx / ww
            if (x > 0)      enqueue(idx - 1)
            if (x < ww - 1) enqueue(idx + 1)
            if (y > 0)      enqueue(idx - ww)
            if (y < wh - 1) enqueue(idx + ww)
        }

        // 4. Connected components of enclosed candidates
        val visited  = BooleanArray(size)
        val maxArea  = (size * MAX_AREA_PCT).toInt()
        val invScale = 1f / scale
        val results  = mutableListOf<BubbleInfo>()

        for (start in 0 until size) {
            if (visited[start] || isBg[start] || !isCandidate[start]) continue

            val q2 = ArrayDeque<Int>()
            q2.addLast(start); visited[start] = true

            var minX = ww; var maxX = 0; var minY = wh; var maxY = 0
            var area = 0
            val boundaryPx = mutableListOf<Int>()   // boundary pixel indices

            while (q2.isNotEmpty()) {
                val idx = q2.removeFirst()
                area++
                val x = idx % ww; val y = idx / ww
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y

                // Is this a boundary pixel? (any 4-connected neighbour is outside component)
                val onBoundary =
                    x == 0 || x == ww - 1 || y == 0 || y == wh - 1 ||
                    !isCandidate[idx - 1]  || isBg[idx - 1]  ||
                    !isCandidate[idx + 1]  || isBg[idx + 1]  ||
                    !isCandidate[idx - ww] || isBg[idx - ww] ||
                    !isCandidate[idx + ww] || isBg[idx + ww]
                if (onBoundary) boundaryPx.add(idx)

                fun tryAdd(ni: Int) {
                    if (ni < 0 || ni >= size || visited[ni] || !isCandidate[ni]) return
                    visited[ni] = true; q2.addLast(ni)
                }
                tryAdd(idx - 1); tryAdd(idx + 1)
                tryAdd(idx - ww); tryAdd(idx + ww)
            }

            // Filters
            if (area < MIN_AREA || area > maxArea) continue
            val bw = (maxX - minX).coerceAtLeast(1)
            val bh = (maxY - minY).coerceAtLeast(1)
            if (bw < MIN_DIM || bh < MIN_DIM) continue
            val aspect = bw.toFloat() / bh
            if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue
            val fillRatio = area.toFloat() / (bw * bh)
            if (fillRatio < MIN_FILL_RATIO) continue         // ACTION SPLATTER / SFX text guard

            // 5. Build outline from boundary pixels sorted by angle from centroid
            val outline = buildOutline(boundaryPx, ww, invScale)

            val rect = Rect(
                (minX * invScale).toInt(), (minY * invScale).toInt(),
                (maxX * invScale).toInt(), (maxY * invScale).toInt()
            )
            results.add(BubbleInfo(rect, outline))
        }

        return results.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    // ── Outline building ──────────────────────────────────────────────────────

    /**
     * Takes boundary pixel indices in work-scale coordinates, sorts them by angle
     * from their centroid (producing a clockwise polygon), downsamples to at most
     * [MAX_OUTLINE_PTS] points, and scales to source-bitmap coordinates.
     */
    private fun buildOutline(boundaryPx: List<Int>, ww: Int, invScale: Float): List<PointF> {
        if (boundaryPx.isEmpty()) return emptyList()

        // Centroid of boundary pixels
        var sumX = 0L; var sumY = 0L
        for (idx in boundaryPx) { sumX += idx % ww; sumY += idx / ww }
        val cx = sumX.toFloat() / boundaryPx.size
        val cy = sumY.toFloat() / boundaryPx.size

        // Sort by polar angle from centroid
        val sorted = boundaryPx.sortedBy { idx ->
            atan2(((idx / ww) - cy).toDouble(), ((idx % ww) - cx).toDouble()).toFloat()
        }

        // Downsample evenly to MAX_OUTLINE_PTS
        val step = maxOf(1, sorted.size / MAX_OUTLINE_PTS)
        val outline = mutableListOf<PointF>()
        var i = 0
        while (i < sorted.size) {
            val idx = sorted[i]
            outline.add(PointF((idx % ww) * invScale, (idx / ww) * invScale))
            i += step
        }
        return outline
    }
}
