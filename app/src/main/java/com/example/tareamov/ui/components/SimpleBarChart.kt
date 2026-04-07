package com.example.tareamov.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Professional bar chart with per-bar gradient fill, value labels,
 * dashed grid lines and bottom axis labels. Animates bars growing upward.
 */
class SimpleBarChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class BarEntry(val value: Float, val label: String, val color: Int)

    private val bgBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1AFFFFFF")
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 27f
        color = Color.parseColor("#7E7E8E")
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 25f
        color = Color.parseColor("#CCFFFFFF")
        typeface = Typeface.DEFAULT_BOLD
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#12FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f, 5f), 0f)
    }

    private val barColors = listOf(
        Color.parseColor("#9B8FFF"),
        Color.parseColor("#00D4FF"),
        Color.parseColor("#30D158"),
        Color.parseColor("#FFD60A"),
        Color.parseColor("#FF6B6B"),
        Color.parseColor("#BF5AF2")
    )

    private val bars = mutableListOf<BarEntry>()
    private var animProgress = 0f
    private val barRect = RectF()
    private val bgRect = RectF()

    fun setData(values: List<Float>, labels: List<String>) {
        bars.clear()
        values.forEachIndexed { i, v ->
            bars.add(BarEntry(v, labels.getOrElse(i) { "" }, barColors[i % barColors.size]))
        }
        startAnimation()
    }

    private fun startAnimation() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000
            interpolator = DecelerateInterpolator()
            addUpdateListener { animProgress = animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (bars.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val labelAreaH = 40f
        val valueAreaH = 30f
        val topPad = valueAreaH + 10f
        val drawH = h - labelAreaH - topPad
        val slotW = w / bars.size
        val barW = slotW * 0.54f
        val maxVal = maxOf(bars.maxOf { it.value }, 1f)

        // Grid lines
        for (i in 0..3) {
            val y = topPad + drawH * i / 3f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        bars.forEachIndexed { i, bar ->
            val cx = slotW * i + slotW / 2f
            val left = cx - barW / 2f
            val right = cx + barW / 2f
            val bottom = topPad + drawH
            val barH = (bar.value / maxVal) * drawH * animProgress
            val top = bottom - barH

            // Background full-height bar
            bgRect.set(left, topPad, right, bottom)
            canvas.drawRoundRect(bgRect, 10f, 10f, bgBarPaint)

            // Gradient fill bar
            if (barH > 0f) {
                val r = Color.red(bar.color)
                val g = Color.green(bar.color)
                val b = Color.blue(bar.color)
                val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL
                    shader = LinearGradient(
                        0f, top, 0f, bottom,
                        intArrayOf(bar.color, Color.argb(0x44, r, g, b)),
                        null, Shader.TileMode.CLAMP
                    )
                }
                barRect.set(left, top, right, bottom)
                canvas.drawRoundRect(barRect, 10f, 10f, barPaint)
            }

            // Value label above bar (fades in after 70% of animation)
            if (animProgress > 0.7f && bar.value > 0) {
                val alpha = ((animProgress - 0.7f) / 0.3f * 255).toInt().coerceIn(0, 255)
                valuePaint.alpha = alpha
                val label = if (bar.value == bar.value.toLong().toFloat()) {
                    bar.value.toLong().toString()
                } else {
                    "%.1f".format(bar.value)
                }
                canvas.drawText(label, cx, top - 8f, valuePaint)
            }

            // X-axis label
            canvas.drawText(bar.label, cx, h - 8f, labelPaint)
        }
    }
}
