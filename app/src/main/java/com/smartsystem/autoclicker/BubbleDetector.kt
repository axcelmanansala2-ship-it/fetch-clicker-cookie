package com.smartsystem.autoclicker

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect

/**
 * Detects speech bubbles in a manhwa/comic page bitmap using pure pixel analysis.
 * No ML — fast BFS-based enclosed-region detection.
 *
 * Handles all bubble types seen in manhwa:
 *  • White round/oval bubbles (most common)
 *  • White spiky/starburst bubbles
 *  • Colored fill bubbles (red, yellow, dark, etc.)
 *  • Jagged-border bubbles with white interior
 *
 * Algorithm:
 *  1. Scale bitmap down to WORK_WIDTH for speed.
 *  2. Classify every pixel as a "bubble-interior candidate":
 *       – bright (white/near-white)   → white bubbles
 *       – saturated non-dark color    → colored bubbles (red, yellow …)
 *  3. Flood-fill from all 4 image edges to mark "background" — any candidate
 *     pixel reachable from an edge is outside all bubbles.
 *  4. BFS connected-components on remaining (enclosed) candidates.
 *     Components inside size/aspect-ratio bounds → speech bubbles.
 *  5. Scale bounding rects back to original coordinates and return sorted
 *     in top-to-bottom, left-to-right reading order.
 */
object BubbleDetector {

    // ── Tuning constants ─────────────────────────────────────────────────────

    /** Bitmap is scaled to this width for analysis (height proportional). */
    private const val WORK_WIDTH = 360

    /** Min connected-component area (px²) at work-scale to count as a bubble. */
    private const val MIN_AREA = 700

    /** Components larger than this fraction of total pixels are ignored (whole panel). */
    private const val MAX_AREA_RATIO = 0.55f

    /** Min and max aspect ratio (w/h) for a bubble bounding box. */
    private const val MIN_ASPECT = 0.12f
    private const val MAX_ASPECT = 8.0f

    /** Minimum bounding-box dimension (px at work-scale). */
    private const val MIN_DIM = 14

    /** Brightness threshold (0-255) — pixels above this are "bright" candidates. */
    private const val BRIGHT_THRESH = 158

    /** Saturation threshold (0-255) for the colored-bubble path. */
    private const val SAT_THRESH = 55

    /**
     * Minimum brightness (0-255) for a pixel to qualify via the saturation path.
     * Kept low (40) so dark-filled colored bubbles (e.g. deep red) are captured.
     */
    private const val SAT_BRIGHT = 40

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Detect all speech bubbles in [src] and return their bounding [Rect]s
     * in the original bitmap's coordinate space, sorted top→bottom, left→right.
     *
     * Safe to call on any thread; allocates and recycles a small working bitmap.
     */
    fun detect(src: Bitmap): List<Rect> {
        val srcW = src.width
        val srcH = src.height
        if (srcW == 0 || srcH == 0) return emptyList()

        // ── 1. Scale down ────────────────────────────────────────────────────
        val scale = WORK_WIDTH.toFloat() / srcW
        val ww = WORK_WIDTH
        val wh = (srcH * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, ww, wh, false)
        val pixels = IntArray(ww * wh)
        small.getPixels(pixels, 0, ww, 0, 0, ww, wh)
        small.recycle()

        val size = ww * wh

        // ── 2. Classify pixels ───────────────────────────────────────────────
        val isCandidate = BooleanArray(size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val brightness = (r * 0.299f + g * 0.587f + b * 0.114f).toInt()
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val sat = if (max == 0) 0 else (max - min) * 255 / max
            // White/near-white bubble interior — OR — saturated colored bubble
            isCandidate[i] = brightness > BRIGHT_THRESH ||
                    (sat > SAT_THRESH && brightness > SAT_BRIGHT)
        }

        // ── 3. Flood-fill background from all 4 edges ────────────────────────
        val isBg = BooleanArray(size)
        val queue = ArrayDeque<Int>(minOf(size / 4, 65536))

        fun enqueue(idx: Int) {
            if (idx < 0 || idx >= size || isBg[idx] || !isCandidate[idx]) return
            isBg[idx] = true
            queue.addLast(idx)
        }

        for (x in 0 until ww) {
            enqueue(x)                    // top row
            enqueue((wh - 1) * ww + x)   // bottom row
        }
        for (y in 0 until wh) {
            enqueue(y * ww)               // left column
            enqueue(y * ww + ww - 1)      // right column
        }

        while (queue.isNotEmpty()) {
            val idx = queue.removeFirst()
            val x = idx % ww
            val y = idx / ww
            if (x > 0)      enqueue(idx - 1)
            if (x < ww - 1) enqueue(idx + 1)
            if (y > 0)      enqueue(idx - ww)
            if (y < wh - 1) enqueue(idx + ww)
        }

        // ── 4. Connected components of enclosed candidates ───────────────────
        val visited = BooleanArray(size)
        val maxArea = (size * MAX_AREA_RATIO).toInt()
        val invScale = 1f / scale
        val bubbles = mutableListOf<Rect>()

        for (start in 0 until size) {
            if (visited[start] || isBg[start] || !isCandidate[start]) continue

            // BFS — collect the component
            val q2 = ArrayDeque<Int>()
            q2.addLast(start)
            visited[start] = true

            var minX = ww; var maxX = 0
            var minY = wh; var maxY = 0
            var area = 0

            while (q2.isNotEmpty()) {
                val idx = q2.removeFirst()
                area++
                val x = idx % ww
                val y = idx / ww
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y

                fun tryAdd(ni: Int) {
                    if (ni < 0 || ni >= size || visited[ni] || !isCandidate[ni]) return
                    visited[ni] = true
                    q2.addLast(ni)
                }
                tryAdd(idx - 1); tryAdd(idx + 1)
                tryAdd(idx - ww); tryAdd(idx + ww)
            }

            // ── Filter ───────────────────────────────────────────────────────
            if (area < MIN_AREA || area > maxArea) continue
            val bw = (maxX - minX).coerceAtLeast(1)
            val bh = (maxY - minY).coerceAtLeast(1)
            if (bw < MIN_DIM || bh < MIN_DIM) continue
            val aspect = bw.toFloat() / bh
            if (aspect < MIN_ASPECT || aspect > MAX_ASPECT) continue

            // Scale bounding rect back to source coordinates
            bubbles.add(Rect(
                (minX * invScale).toInt(),
                (minY * invScale).toInt(),
                (maxX * invScale).toInt(),
                (maxY * invScale).toInt()
            ))
        }

        // ── 5. Sort reading order (top→bottom, left→right) ──────────────────
        return bubbles.sortedWith(compareBy({ it.top }, { it.left }))
    }
}
