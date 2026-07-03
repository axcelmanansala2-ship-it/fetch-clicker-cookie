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
 *  2. Classify each pixel as a "bright bubble-interior candidate":
 *       • Bright (≥ BRIGHT_THRESH)   → white/near-white bubbles
 *       • Saturated non-dark         → colored bubbles (light yellow, pink …)
 *  3. BFS flood-fill from all 4 image edges → marks bright background.
 *  4. BFS connected-components on remaining enclosed bright candidates.
 *     Five filters applied in order:
 *       (a) min/max area, aspect ratio, min dimension
 *       (b) fill ratio    — component_pixels / bbox_area   (action-splatter guard)
 *       (c) interior brightness ≥ 140  — rejects dark action panels
 *       (d) interior saturation ≤ 100  — rejects vivid gradient artwork
 *           (speech bubbles are white/low-saturation; cyan/purple gradients fail)
 *  5. Convex hull of boundary pixels → clean, non-self-intersecting outline.
 *
 *  DARK BUBBLE PASS (second pass):
 *  Detects black/dark speech bubbles with white text using the same BFS approach
 *  but inverted brightness criteria. Confirmed by presence of bright text pixels.
 */
object BubbleDetector {

    // ── Bright-bubble tuning ───────────────────────────────────────────────────
    private const val WORK_WIDTH          = 360
    private const val MIN_AREA            = 2000
    private const val MAX_AREA_PCT        = 0.50f
    private const val MIN_ASPECT          = 0.15f
    private const val MAX_ASPECT          = 4.5f
    private const val MIN_DIM             = 22
    private const val MIN_FILL_RATIO      = 0.38f
    private const val MIN_INTERIOR_BRIGHT = 140     // avg brightness of center 50% bbox
    private const val MAX_INTERIOR_SAT    = 100     // avg HSV-sat of center 50% bbox
    private const val BRIGHT_THRESH       = 160
    private const val SAT_THRESH          = 55
    private const val SAT_BRIGHT          = 60
    private const val MAX_HULL_INPUT      = 200     // downsample boundary before hull

    // ── Dark-bubble tuning ────────────────────────────────────────────────────
    private const val DARK_THRESH              = 55    // pixels below this = dark candidate
    private const val MAX_INTERIOR_DARK_BRIGHT = 65    // confirm region is truly dark
    private const val MIN_BRIGHT_PIXEL_RATIO   = 0.05f // ≥5% bright (text) pixels inside

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

        // 2. Classify pixels — bright candidates
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

        // 4. Connected components — bright bubbles
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

        // ─────────────────────────────────────────────────────────────────────
        // DARK BUBBLE PASS — black/dark enclosed boxes with white text inside
        // ─────────────────────────────────────────────────────────────────────

        // Classify dark candidates
        val isDarkCandidate = BooleanArray(size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val brightness = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
            isDarkCandidate[i] = brightness < DARK_THRESH
        }

        // BFS from edges to mark background dark pixels (open-air dark scene areas)
        val isDarkBg = BooleanArray(size)
        val darkQueue = ArrayDeque<Int>(minOf(size / 4, 65536))
        fun enqueueDark(idx: Int) {
            if (idx < 0 || idx >= size || isDarkBg[idx] || !isDarkCandidate[idx]) return
            isDarkBg[idx] = true; darkQueue.addLast(idx)
        }
        for (x in 0 until ww) { enqueueDark(x); enqueueDark((wh - 1) * ww + x) }
        for (y in 0 until wh) { enqueueDark(y * ww); enqueueDark(y * ww + ww - 1) }
        while (darkQueue.isNotEmpty()) {
            val idx = darkQueue.removeFirst()
            val x = idx % ww; val y = idx / ww
            if (x > 0)      enqueueDark(idx - 1)
            if (x < ww - 1) enqueueDark(idx + 1)
            if (y > 0)      enqueueDark(idx - ww)
            if (y < wh - 1) enqueueDark(idx + ww)
        }

        // Connected components on enclosed dark regions
        val darkVisited = BooleanArray(size)
        for (start in 0 until size) {
            if (darkVisited[start] || isDarkBg[start] || !isDarkCandidate[start]) continue

            val q3 = ArrayDeque<Int>()
            q3.addLast(start); darkVisited[start] = true

            var dMinX = ww; var dMaxX = 0; var dMinY = wh; var dMaxY = 0
            var dArea = 0
            val dBoundary = mutableListOf<Int>()

            while (q3.isNotEmpty()) {
                val idx = q3.removeFirst()
                dArea++
                val x = idx % ww; val y = idx / ww
                if (x < dMinX) dMinX = x; if (x > dMaxX) dMaxX = x
                if (y < dMinY) dMinY = y; if (y > dMaxY) dMaxY = y

                val onBoundary =
                    x == 0 || x == ww - 1 || y == 0 || y == wh - 1 ||
                    !isDarkCandidate[idx - 1]  || isDarkBg[idx - 1]  ||
                    !isDarkCandidate[idx + 1]  || isDarkBg[idx + 1]  ||
                    !isDarkCandidate[idx - ww] || isDarkBg[idx - ww] ||
                    !isDarkCandidate[idx + ww] || isDarkBg[idx + ww]
                if (onBoundary) dBoundary.add(idx)

                fun tryAddD(ni: Int) {
                    if (ni < 0 || ni >= size || darkVisited[ni] || !isDarkCandidate[ni]) return
                    darkVisited[ni] = true; q3.addLast(ni)
                }
                tryAddD(idx - 1); tryAddD(idx + 1)
                tryAddD(idx - ww); tryAddD(idx + ww)
            }

            // Same dimension / area / aspect / fill filters as bright pass
            if (dArea < MIN_AREA || dArea > maxArea) continue
            val dbw = (dMaxX - dMinX + 1).coerceAtLeast(1)
            val dbh = (dMaxY - dMinY + 1).coerceAtLeast(1)
            if (dbw < MIN_DIM || dbh < MIN_DIM) continue
            val dAspect = dbw.toFloat() / dbh
            if (dAspect < MIN_ASPECT || dAspect > MAX_ASPECT) continue
            val dFillRatio = dArea.toFloat() / (dbw * dbh)
            if (dFillRatio < MIN_FILL_RATIO) continue

            // Sample the WIDER bounding box for bright text pixels + confirm dark interior
            // Use a slightly larger sample region to catch text near edges
            val dSmX = dMinX + dbw / 6; val dEmX = (dMaxX + 1) - dbw / 6
            val dSmY = dMinY + dbh / 6; val dEmY = (dMaxY + 1) - dbh / 6
            val dXStep = maxOf(1, (dEmX - dSmX) / 14)
            val dYStep = maxOf(1, (dEmY - dSmY) / 14)
            var dBrightPixels = 0; var dDarkSum = 0L; var dSampled = 0
            var dsy = dSmY
            while (dsy <= dEmY) {
                var dsx = dSmX
                while (dsx <= dEmX) {
                    val pi = dsy * ww + dsx
                    if (pi in pixels.indices) {
                        val p = pixels[pi]
                        val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
                        val brightness = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
                        dDarkSum += brightness
                        // Bright pixel = text on dark background
                        if (brightness > 180) dBrightPixels++
                        dSampled++
                    }
                    dsx += dXStep
                }
                dsy += dYStep
            }
            if (dSampled == 0) continue
            val dAvgBright = dDarkSum / dSampled

            // Confirm: interior IS dark (not a bright area that snuck in)
            if (dAvgBright > MAX_INTERIOR_DARK_BRIGHT) continue
            // Confirm: has white/bright text pixels inside
            if (dBrightPixels.toFloat() / dSampled < MIN_BRIGHT_PIXEL_RATIO) continue

            // Build result rect in src-bitmap coordinates
            val darkRect = Rect(
                (dMinX * invScale).toInt(),
                (dMinY * invScale).toInt(),
                ((dMaxX + 1) * invScale).toInt().coerceAtMost(srcW),
                ((dMaxY + 1) * invScale).toInt().coerceAtMost(srcH)
            )

            // Skip if this rect is already substantially covered by a detected bright bubble
            val alreadyCovered = results.any { existing ->
                val inter = Rect(existing.rect)
                inter.intersect(darkRect) && run {
                    val interArea = inter.width().toLong() * inter.height()
                    val darkArea  = darkRect.width().toLong() * darkRect.height()
                    interArea > darkArea * 0.6f
                }
            }
            if (alreadyCovered) continue

            val outline = buildConvexHullOutline(dBoundary, ww, invScale)
            results.add(BubbleInfo(darkRect, outline))
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
