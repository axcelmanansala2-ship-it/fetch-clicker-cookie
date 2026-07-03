package com.smartsystem.autoclicker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect

/**
 * Detected speech bubble: bounding rectangle + convex-hull outline polygon
 * in source-bitmap coordinates. The outline is used to draw an ESP border.
 */
data class BubbleInfo(
    val rect: Rect,
    val outline: List<PointF>   // convex hull, source-bitmap coordinates
)

/**
 * Detects speech bubbles in a manhwa/comic page bitmap using pure pixel analysis.
 *
 * Strategy:
 *  1. Scale to a small working bitmap for speed.
 *  2. Classify each pixel as a "bubble-interior candidate":
 *       • Bright (≥ BRIGHT_THRESH)   → white/near-white bubbles
 *       • Saturated non-dark         → colored bubbles (light yellow, pink …)
 *  3. BFS flood-fill from all 4 image edges → marks background.
 *  4. BFS connected-components on remaining enclosed candidates.
 *     Five filters applied in order:
 *       (a) min/max area, aspect ratio, min dimension
 *       (b) fill ratio    — component_pixels / bbox_area   (action-splatter guard)
 *       (c) interior brightness ≥ 140  — rejects dark action panels
 *       (d) interior saturation ≤ 100  — rejects vivid gradient artwork
 *           (speech bubbles are white/low-saturation; cyan/purple gradients fail)
 *  5. Convex hull of boundary pixels → clean, non-self-intersecting outline.
 */
object BubbleDetector {

    // ── Tuning ────────────────────────────────────────────────────────────────
    private const val WORK_WIDTH          = 360
    private const val MIN_AREA            = 2000
    private const val MAX_AREA_PCT        = 0.50f
    private const val MIN_ASPECT          = 0.15f
    private const val MAX_ASPECT          = 4.5f
    private const val MIN_DIM             = 22
    private const val MIN_FILL_RATIO      = 0.38f
    private const val MIN_INTERIOR_BRIGHT = 140     // avg brightness of center 50% bbox
    private const val MAX_INTERIOR_SAT    = 100     // avg HSV-sat of center 50% bbox
                                                     // white=0, light-yellow≈75, cyan≈255
    private const val BRIGHT_THRESH       = 160
    private const val SAT_THRESH          = 55
    private const val SAT_BRIGHT          = 60
    private const val MAX_HULL_INPUT      = 200     // downsample boundary before hull

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

        // 4. Connected components
        val visited = BooleanArray(size)
        val maxArea = (size * MAX_AREA_PCT).toInt()
        val invScale = 1f / scale
        val results = mutableListOf<BubbleInfo>()

        for (start in 0 until size) {
            if (visited[start] || isBg[start] || !isCandidate[start]) continue

            val q2 = ArrayDeque<Int>()
            q2.addLast(start); visited[start] = true

            var minX = ww; var maxX = 0; var minY = wh; var maxY = 0
            var area = 0
            val boundaryPx = mutableListOf<Int>()

            while (q2.isNotEmpty()) {
                val idx = q2.removeFirst()
                area++
                val x = idx % ww; val y = idx / ww
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y

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

            // ── Filter (a): area / dimension ──────────────────────────────────
            if (area < MIN_AREA || area > maxArea) continue
            val bw = (maxX - minX + 1).coerceAtLeast(1)
            val bh = (maxY - minY + 1).coerceAtLeast(1)
            if (bw < MIN_DIM || bh < MIN_DIM) continue
            val aspect = bw.toFloat() / bh
            if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue

            // ── Filter (b): fill ratio ────────────────────────────────────────
            val fillRatio = area.toFloat() / (bw * bh)
            if (fillRatio < MIN_FILL_RATIO) continue

            // ── Filters (c) + (d): sample center 50% of bbox ─────────────────
            val smX = minX + bw / 4; val emX = (maxX + 1) - bw / 4
            val smY = minY + bh / 4; val emY = (maxY + 1) - bh / 4
            val xStep = maxOf(1, (emX - smX) / 12)
            val yStep = maxOf(1, (emY - smY) / 12)
            var brightSum = 0L; var satSum = 0L; var sampleCount = 0
            var sy = smY
            while (sy <= emY) {
                var sx = smX
                while (sx <= emX) {
                    val pi = sy * ww + sx
                    if (pi in pixels.indices) {
                        val p  = pixels[pi]
                        val r  = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                        brightSum += (r * 0.299f + g * 0.587f + b * 0.114f).toLong()
                        val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
                        satSum += if (mx == 0) 0 else ((mx - mn) * 255 / mx).toLong()
                        sampleCount++
                    }
                    sx += xStep
                }
                sy += yStep
            }
            if (sampleCount == 0) continue
            val avgBright = brightSum / sampleCount
            val avgSat    = satSum    / sampleCount
            if (avgBright < MIN_INTERIOR_BRIGHT) continue   // dark action panel
            if (avgSat    > MAX_INTERIOR_SAT)    continue   // vivid gradient/artwork

            // ── Step 5: convex hull of downsampled boundary pixels ─────────────
            val outline = buildConvexHullOutline(boundaryPx, ww, invScale)

            val rect = Rect(
                (minX * invScale).toInt(),
                (minY * invScale).toInt(),
                ((maxX + 1) * invScale).toInt().coerceAtMost(srcW),
                ((maxY + 1) * invScale).toInt().coerceAtMost(srcH)
            )
            results.add(BubbleInfo(rect, outline))
        }

        return results.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
    }

    // ── Convex-hull outline ───────────────────────────────────────────────────

    /**
     * Downsample boundary pixels, compute their convex hull in source coords.
     * Result: a clean, non-self-intersecting polygon that tightly wraps the bubble.
     */
    private fun buildConvexHullOutline(boundaryPx: List<Int>, ww: Int, invScale: Float): List<PointF> {
        if (boundaryPx.isEmpty()) return emptyList()

        // Downsample so hull input is manageable
        val step = maxOf(1, boundaryPx.size / MAX_HULL_INPUT)
        val pts  = mutableListOf<PointF>()
        var i = 0
        while (i < boundaryPx.size) {
            val idx = boundaryPx[i]
            pts.add(PointF((idx % ww) * invScale, (idx / ww) * invScale))
            i += step
        }

        return convexHull(pts)
    }

    /**
     * Andrew's monotone chain algorithm — O(n log n).
     * Returns points in counter-clockwise order.
     */
    private fun convexHull(pts: List<PointF>): List<PointF> {
        if (pts.size <= 2) return pts
        val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))
        val hull = mutableListOf<PointF>()

        // Lower hull
        for (p in sorted) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f)
                hull.removeAt(hull.size - 1)
            hull.add(p)
        }
        // Upper hull
        val lowerSize = hull.size + 1
        for (p in sorted.reversed()) {
            while (hull.size >= lowerSize && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0f)
                hull.removeAt(hull.size - 1)
            hull.add(p)
        }
        hull.removeAt(hull.size - 1)   // last == first
        return hull
    }

    /** 2-D cross product of vectors OA and OB. Positive = left turn. */
    private fun cross(o: PointF, a: PointF, b: PointF): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
}
