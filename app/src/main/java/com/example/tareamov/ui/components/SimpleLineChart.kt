package com.example.tareamov.ui.components

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Professional line chart with animated reveal, gradient fill, grid lines and dot markers.
 */
class SimpleLineChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26a69a")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(8f, 7f), 0f)
    }

    private val dotRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#26a69a")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val dotFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val dotHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3026a69a")
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val fillPath = Path()
    private val points = mutableListOf<Float>()
    private var animProgress = 0f
    private var dotsAlpha = 0f
    private var fillShader: LinearGradient? = null

    fun setData(data: List<Float>) {
        points.clear()
        points.addAll(data)
        fillShader = null
        startAnimation()
    }

    private fun startAnimation() {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            interpolator = DecelerateInterpolator()
            addUpdateListener { animProgress = animatedValue as Float; invalidate() }
            start()
        }
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 850
            duration = 400
            addUpdateListener { dotsAlpha = animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        super.onSizeChanged(w, h, oldW, oldH)
        fillShader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Color.parseColor("#5026a69a"), Color.parseColor("#0026a69a")),
            null, Shader.TileMode.CLAMP
        )
    }

    private fun buildScreenPoints(w: Float, h: Float): List<PointF> {
        if (points.size < 2) return emptyList()
        val padL = 16f; val padR = 16f
        val padTop = 20f; val padBot = 28f
        val drawW = w - padL - padR
        val drawH = h - padTop - padBot
        val maxVal = maxOf(points.maxOrNull() ?: 1f, 1f)
        val step = drawW / (points.size - 1)
        return points.mapIndexed { i, v ->
            PointF(padL + i * step, padTop + drawH * (1f - v / maxVal))
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val pts = buildScreenPoints(w, h)

        // Horizontal grid lines
        val padTop = 20f; val padBot = 28f
        val drawH = h - padTop - padBot
        for (i in 0..4) {
            val y = padTop + drawH * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        // Build smooth bezier paths
        linePath.reset()
        fillPath.reset()
        linePath.moveTo(pts[0].x, pts[0].y)
        fillPath.moveTo(pts[0].x, h)
        fillPath.lineTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) {
            val cpX = (pts[i - 1].x + pts[i].x) / 2f
            linePath.cubicTo(cpX, pts[i - 1].y, cpX, pts[i].y, pts[i].x, pts[i].y)
            fillPath.cubicTo(cpX, pts[i - 1].y, cpX, pts[i].y, pts[i].x, pts[i].y)
        }
        fillPath.lineTo(pts.last().x, h)
        fillPath.close()

        // Clip for reveal animation
        canvas.save()
        canvas.clipRect(0f, 0f, w * animProgress, h)

        // Gradient fill
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = fillShader
        }
        canvas.drawPath(fillPath, fp)
        canvas.drawPath(linePath, linePaint)
        canvas.restore()

        // Dot markers (fade in after line draws)
        if (dotsAlpha > 0f) {
            val alpha = (dotsAlpha * 255).toInt()
            for (pt in pts) {
                dotHaloPaint.alpha = (dotsAlpha * 55).toInt()
                canvas.drawCircle(pt.x, pt.y, 13f, dotHaloPaint)
                dotFillPaint.alpha = alpha
                canvas.drawCircle(pt.x, pt.y, 5.5f, dotFillPaint)
                dotRingPaint.alpha = alpha
                canvas.drawCircle(pt.x, pt.y, 5.5f, dotRingPaint)
            }
        }
    }
}
