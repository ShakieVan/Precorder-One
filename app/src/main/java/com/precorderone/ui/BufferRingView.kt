package com.precorderone.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Buffer fill as a ring around the shutter, drawn directly: always centered in the view, and a full buffer gives a
 * closed circle (Material's progress indicator added insets and a gap at the start that showed as a notch).
 */
class BufferRingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val strokeWidth = 4f * resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@BufferRingView.strokeWidth
        color = 0x33FFFFFF
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = this@BufferRingView.strokeWidth
        strokeCap = Paint.Cap.ROUND
    }
    private val ring = RectF()
    private var progress = 0f

    /** Fill level 0..1. */
    fun setProgress(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        if (clamped == progress) return
        progress = clamped
        invalidate()
    }

    fun setColor(color: Int) {
        if (progressPaint.color == color) return
        progressPaint.color = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // The stroke is centered on the path, so the path runs half a stroke inside the view edge.
        val diameter = min(w, h) - strokeWidth
        ring.set((w - diameter) / 2f, (h - diameter) / 2f, (w + diameter) / 2f, (h + diameter) / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawOval(ring, trackPaint)
        when {
            progress >= 0.995f -> canvas.drawOval(ring, progressPaint)
            progress > 0f -> canvas.drawArc(ring, -90f, 360f * progress, false, progressPaint)
        }
    }
}
