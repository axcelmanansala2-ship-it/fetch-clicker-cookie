package com.smartsystem.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * Transparent overlay drawn on top of each manhwa page ImageView.
 *
 * Each detected bubble is rendered as an ESP-style fluid border that follows
 * the bubble's actual shape (via an outline polygon from BubbleDetector):
 *   • Outer soft glow   — wide, semi-transparent green stroke
 *   • Inner crisp line  — narrow, opaque green stroke
 *   • 4 corner tick marks (ESP style, positioned at the bounding-box corners)
 *   • Active bubble (currently being read) gets a filled tint + brighter border
 *
 * Coordinate mapping:
 *   Outline points are in bitmap space.  The overlay sits on top of an
 *   ImageView with adjustViewBounds=true + FIT_CENTER, so the image fills
 *   the view's full width proportionally — mapping is a simple scale.
 */
class BubbleOverlayView(context: Context) : View(context) {

    // ── Paints ────────────────────────────────────────────────────────────────
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 0, 255, 80); style = Paint.Style.STROKE; strokeWidth = 16f
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 255, 60); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 60); style = Paint.Style.STROKE
        strokeWidth = 4f; strokeCap = Paint.Cap.ROUND
    }
    private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 0, 255, 80); style = Paint.Style.FILL
    }
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 255, 60); style = Paint.Style.STROKE; strokeWidth = 4.5f
    }
    private val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 0, 255, 80); style = Paint.Style.STROKE; strokeWidth = 22f
    }
    private val activeCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 60, 255, 120); style = Paint.Style.STROKE
        strokeWidth = 5f; strokeCap = Paint.Cap.ROUND
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private var bubbles: List<BubbleInfo> = emptyList()
    private var activeBubbleIndex: Int = -1
    var srcWidth:  Int = 1
    var srcHeight: Int = 1

    // ── Public API ────────────────────────────────────────────────────────────
    fun setBubbles(infos: List<BubbleInfo>) { bubbles = infos; invalidate() }
    fun setActiveBubble(index: Int)         { activeBubbleIndex = index; invalidate() }

    // ── Drawing ───────────────────────────────────────────────────────────────
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isEmpty()) return
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || srcWidth <= 0 || srcHeight <= 0) return

        val sx = vw / srcWidth
        val sy = vh / srcHeight

        for ((i, info) in bubbles.withIndex()) {
            val isActive = i == activeBubbleIndex

            // Build the outline Path (fluid shape matching the bubble)
            val path = buildPath(info.outline, sx, sy)

            // Bounding box in view space (for corner ticks)
            val bboxPad = if (isActive) 8f else 5f
            val bbox = RectF(
                info.rect.left   * sx - bboxPad,
                info.rect.top    * sy - bboxPad,
                info.rect.right  * sx + bboxPad,
                info.rect.bottom * sy + bboxPad
            )

            if (isActive) {
                canvas.drawPath(path, activeFillPaint)
                canvas.drawPath(path, activeGlowPaint)
                canvas.drawPath(path, activeBorderPaint)
                drawCorners(canvas, bbox, 22f, activeCornerPaint)
            } else {
                canvas.drawPath(path, glowPaint)
                canvas.drawPath(path, borderPaint)
                drawCorners(canvas, bbox, 18f, cornerPaint)
            }
        }
    }

    /**
     * Build a smooth closed Path from an outline polygon (PointF list).
     * Uses midpoint quadratic Bezier for smoothness.
     * Falls back to a bounding-box oval if there are not enough points.
     */
    private fun buildPath(outline: List<PointF>, sx: Float, sy: Float): Path {
        val path = Path()
        if (outline.size < 3) return path   // caller will draw nothing for degenerate case

        // Scale outline points to view coordinates
        val pts = Array(outline.size) { i ->
            PointF(outline[i].x * sx, outline[i].y * sy)
        }

        // Start at midpoint between last and first (for smooth wrapping)
        val startMidX = (pts.last().x + pts[0].x) / 2f
        val startMidY = (pts.last().y + pts[0].y) / 2f
        path.moveTo(startMidX, startMidY)

        // Midpoint quadratic Bezier through each point
        for (i in pts.indices) {
            val curr = pts[i]
            val next = pts[(i + 1) % pts.size]
            val midX = (curr.x + next.x) / 2f
            val midY = (curr.y + next.y) / 2f
            path.quadTo(curr.x, curr.y, midX, midY)
        }
        path.close()
        return path
    }

    /** Draw 4-corner ESP tick marks around rect [r] with arm length [size]. */
    private fun drawCorners(canvas: Canvas, r: RectF, size: Float, paint: Paint) {
        // Top-left
        canvas.drawLine(r.left, r.top + size, r.left, r.top, paint)
        canvas.drawLine(r.left, r.top, r.left + size, r.top, paint)
        // Top-right
        canvas.drawLine(r.right - size, r.top, r.right, r.top, paint)
        canvas.drawLine(r.right, r.top, r.right, r.top + size, paint)
        // Bottom-left
        canvas.drawLine(r.left, r.bottom - size, r.left, r.bottom, paint)
        canvas.drawLine(r.left, r.bottom, r.left + size, r.bottom, paint)
        // Bottom-right
        canvas.drawLine(r.right - size, r.bottom, r.right, r.bottom, paint)
        canvas.drawLine(r.right, r.bottom - size, r.right, r.bottom, paint)
    }
}
