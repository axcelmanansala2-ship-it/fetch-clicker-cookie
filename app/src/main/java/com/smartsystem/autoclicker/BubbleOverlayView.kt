package com.smartsystem.autoclicker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * Transparent overlay drawn on top of each manhwa page ImageView.
 *
 * Renders ESP-style green bounding boxes around every detected speech bubble:
 *   • Outer soft glow (wide, semi-transparent stroke)
 *   • Inner crisp line (narrow, opaque stroke)
 *   • Corner tick marks at all 4 corners (ESP corner style)
 *   • Active bubble (currently being read) gets a filled tint + brighter border
 *
 * Coordinate mapping:
 *   Bubble rects are in bitmap space.  This view sits on top of an ImageView
 *   whose scaleType is FIT_CENTER with adjustViewBounds = true, so the displayed
 *   image fills the view's full width with a proportional height — no letterbox.
 *   Mapping is therefore a simple scale: scaleX = viewWidth / bitmapWidth.
 */
class BubbleOverlayView(context: Context) : View(context) {

    // ── Paints ───────────────────────────────────────────────────────────────

    /** Outer glow — wide, semi-transparent green stroke */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(70, 0, 255, 80)
        style  = Paint.Style.STROKE
        strokeWidth = 14f
    }

    /** Inner crisp border — all non-active bubbles */
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(200, 0, 255, 60)
        style  = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /** Corner tick marks — all non-active bubbles */
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(230, 0, 255, 60)
        style  = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    /** Active bubble — fill tint */
    private val activeFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(35, 0, 255, 80)
        style = Paint.Style.FILL
    }

    /** Active bubble — bright border */
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(255, 0, 255, 60)
        style  = Paint.Style.STROKE
        strokeWidth = 4.5f
    }

    /** Active bubble — bright glow */
    private val activeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(110, 0, 255, 80)
        style  = Paint.Style.STROKE
        strokeWidth = 18f
    }

    /** Active bubble — corner ticks (brighter) */
    private val activeCornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color  = Color.argb(255, 60, 255, 120)
        style  = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    // ── State ────────────────────────────────────────────────────────────────

    /** Bubble rects in bitmap coordinates. */
    private var bubbles: List<Rect> = emptyList()

    /** Index of the currently-active (being-read) bubble, or -1 for none. */
    private var activeBubbleIndex: Int = -1

    /** Source bitmap dimensions — used for coordinate mapping. */
    var srcWidth: Int  = 1
    var srcHeight: Int = 1

    // ── Public API ───────────────────────────────────────────────────────────

    /** Update the full list of detected bubbles and redraw. */
    fun setBubbles(rects: List<Rect>) {
        bubbles = rects
        invalidate()
    }

    /**
     * Mark bubble at [index] as active (currently being read).
     * Pass -1 to clear the active highlight.
     * Must be called from the main thread (or use post{}).
     */
    fun setActiveBubble(index: Int) {
        activeBubbleIndex = index
        invalidate()
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bubbles.isEmpty()) return
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || srcWidth <= 0 || srcHeight <= 0) return

        // Scale factors: bitmap → view (adjustViewBounds+FIT_CENTER fills the view)
        val sx = vw / srcWidth
        val sy = vh / srcHeight

        for ((i, bubble) in bubbles.withIndex()) {
            val isActive = i == activeBubbleIndex
            val r = RectF(
                bubble.left  * sx,
                bubble.top   * sy,
                bubble.right * sx,
                bubble.bottom * sy
            )
            val pad = if (isActive) 8f else 5f
            val dr = RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)
            val rx = 14f  // corner radius

            if (isActive) {
                // Fill + bright layered glow
                canvas.drawRoundRect(dr, rx, rx, activeFillPaint)
                canvas.drawRoundRect(dr, rx, rx, activeGlowPaint)
                canvas.drawRoundRect(dr, rx, rx, activeBorderPaint)
                drawCorners(canvas, dr, 22f, activeCornerPaint)
            } else {
                // Standard ESP: glow + crisp border + corner ticks
                canvas.drawRoundRect(dr, rx, rx, glowPaint)
                canvas.drawRoundRect(dr, rx, rx, borderPaint)
                drawCorners(canvas, dr, 18f, cornerPaint)
            }
        }
    }

    /** Draw 4 corner L-marks around [r] with arm length [size]. */
    private fun drawCorners(canvas: Canvas, r: RectF, size: Float, paint: Paint) {
        // Top-left
        canvas.drawLine(r.left,          r.top + size, r.left,  r.top,          paint)
        canvas.drawLine(r.left,          r.top,        r.left  + size, r.top,   paint)
        // Top-right
        canvas.drawLine(r.right - size,  r.top,        r.right, r.top,          paint)
        canvas.drawLine(r.right,         r.top,        r.right, r.top  + size,  paint)
        // Bottom-left
        canvas.drawLine(r.left,          r.bottom - size, r.left,  r.bottom,    paint)
        canvas.drawLine(r.left,          r.bottom,        r.left  + size, r.bottom, paint)
        // Bottom-right
        canvas.drawLine(r.right - size,  r.bottom,     r.right, r.bottom,       paint)
        canvas.drawLine(r.right,         r.bottom - size, r.right, r.bottom,    paint)
    }
}
