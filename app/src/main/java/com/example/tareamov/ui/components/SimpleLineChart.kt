package com.example.tareamov.ui.components

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class SimpleLineChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.parseColor("#B8B3FF")
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val fillPaint = Paint().apply {
        color = Color.parseColor("#33B8B3FF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val points = mutableListOf<Float>()
    private val path = Path()
    private val fillPath = Path()
    private var progress = 0f

    fun setData(data: List<Float>) {
        points.clear()
        points.addAll(data)
        startAnimation()
    }

    private fun startAnimation() {
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 1500
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { 
            progress = it.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        // Add some padding
        val padding = 10f
        val drawWidth = width - 2 * padding
        val drawHeight = height - 2 * padding
        
        val maxVal = (points.maxOrNull() ?: 1f) * 1.2f // Add 20% headroom
        val minVal = 0f

        val stepX = drawWidth / (points.size - 1)
        val rangeY = maxVal - minVal

        path.reset()
        // We don't use fillPath for partial animation easily without complex clipping, 
        // so let's just animate the stroke path or clip the canvas.
        // Clipping canvas is easier for "reveal" effect from left to right.
        
        canvas.save()
        canvas.clipRect(0f, 0f, width * progress, height)

        val startY = height - padding - ((points[0] - minVal) / rangeY) * drawHeight
        path.moveTo(padding, startY)
        
        fillPath.reset()
        fillPath.moveTo(padding, height)
        fillPath.lineTo(padding, startY)

        for (i in 1 until points.size) {
            val x = padding + i * stepX
            val y = height - padding - ((points[i] - minVal) / rangeY) * drawHeight
            
            val prevX = padding + (i - 1) * stepX
            val prevY = height - padding - ((points[i - 1] - minVal) / rangeY) * drawHeight
            
            val cp1X = prevX + (x - prevX) / 2
            val cp1Y = prevY
            val cp2X = prevX + (x - prevX) / 2
            val cp2Y = y
            
            path.cubicTo(cp1X, cp1Y, cp2X, cp2Y, x, y)
            fillPath.cubicTo(cp1X, cp1Y, cp2X, cp2Y, x, y)
        }
        
        val lastX = padding + (points.size - 1) * stepX
        fillPath.lineTo(lastX, height)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, paint)
        
        canvas.restore()
    }
}
