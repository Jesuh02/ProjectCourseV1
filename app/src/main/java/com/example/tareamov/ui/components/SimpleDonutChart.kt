package com.example.tareamov.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

/**
 * Gráfica de dona (donut) con múltiples segmentos, animada y con soporte de etiquetas.
 *
 * Uso:
 *   chart.setSegments(listOf(
 *     DonutSegment(label = "Completados", value = 87f, color = Color.parseColor("#30D158")),
 *     DonutSegment(label = "Aprobados",   value = 92f, color = Color.parseColor("#00D4FF")),
 *     DonutSegment(label = "Satisfacción",value = 78f, color = Color.parseColor("#BF5AF2")),
 *   ))
 */
class SimpleDonutChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class DonutSegment(val label: String, val value: Float, val color: Int)

    private var segments: List<DonutSegment> = emptyList()
    private var animProgress = 0f

    // ── Paint objects ─────────────────────────────────────────────────────
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style   = Paint.Style.STROKE
        color   = Color.parseColor("#1A1A2E")
        strokeWidth = 0f  // set in onSizeChanged
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style   = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 0f
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style   = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeWidth = 0f
        maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
    }

    private val centerValuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize  = 0f
        typeface  = Typeface.DEFAULT_BOLD
    }

    private val centerLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color     = Color.parseColor("#636366")
        textAlign = Paint.Align.CENTER
        textSize  = 0f
    }

    // ── Geometry ──────────────────────────────────────────────────────────
    private val arcRect   = RectF()
    private val glowRect  = RectF()
    private val GAP_DEG   = 5f

    fun setSegments(list: List<DonutSegment>) {
        segments = list
        startRevealAnimation()
    }

    private fun startRevealAnimation() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration     = 1200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                animProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        val strokeW = min(w, h) * 0.135f
        val padding = strokeW / 2f + 2f
        val size = min(w, h).toFloat()
        val left   = (w - size) / 2f + padding
        val top    = (h - size) / 2f + padding
        val right  = left + size - 2 * padding
        val bottom = top  + size - 2 * padding
        arcRect.set(left, top, right, bottom)
        glowRect.set(left - 2f, top - 2f, right + 2f, bottom + 2f)

        trackPaint.strokeWidth  = strokeW
        arcPaint.strokeWidth    = strokeW
        glowPaint.strokeWidth   = strokeW * 0.7f

        centerValuePaint.textSize = size * 0.17f
        centerLabelPaint.textSize = size * 0.075f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (segments.isEmpty()) return

        val total = segments.sumOf { it.value.toDouble() }.toFloat().coerceAtLeast(1f)
        val cx = width / 2f
        val cy = height / 2f

        // Draw background track
        canvas.drawArc(arcRect, 0f, 360f, false, trackPaint)

        // Draw animated segments
        var startAngle = -90f
        segments.forEach { seg ->
            val fraction = seg.value / total
            val fullSweep = fraction * 360f
            val sweep = (fullSweep - GAP_DEG).coerceAtLeast(0f) * animProgress
            val actualStart = startAngle + GAP_DEG / 2f

            // Glow pass
            glowPaint.color = Color.argb(80,
                Color.red(seg.color), Color.green(seg.color), Color.blue(seg.color))
            if (sweep > 0f) canvas.drawArc(glowRect, actualStart, sweep, false, glowPaint)

            // Main arc
            arcPaint.color = seg.color
            if (sweep > 0f) canvas.drawArc(arcRect, actualStart, sweep, false, arcPaint)

            startAngle += fullSweep
        }

        // Center text — average value
        val avgValue = if (segments.isNotEmpty())
            segments.sumOf { it.value.toDouble() }.toFloat() / segments.size
        else 0f
        val avgText = "${avgValue.toInt()}%"

        val fontMetrics = centerValuePaint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        canvas.drawText(avgText, cx, cy - textHeight * 0.1f, centerValuePaint)
        canvas.drawText("Promedio", cx, cy + textHeight * 0.65f, centerLabelPaint)
    }
}
