package com.example.tareamov.ui.components

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class SimpleBarChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint().apply {
        color = Color.parseColor("#8B7FFF")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val barBgPaint = Paint().apply {
        color = Color.parseColor("#20FFFFFF") // More subtle background
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = Color.parseColor("#B8B3FF")
        textSize = 30f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val data = mutableListOf<Float>()
    private val labels = mutableListOf<String>()
    private var progress = 0f

    fun setData(values: List<Float>, labels: List<String>) {
        this.data.clear()
        this.data.addAll(values)
        this.labels.clear()
        this.labels.addAll(labels)
        startAnimation()
    }

    private fun startAnimation() {
        val animator = android.animation.ValueAnimator.ofFloat(0f, 1f)
        animator.duration = 1000
        animator.interpolator = android.view.animation.DecelerateInterpolator()
        animator.addUpdateListener { 
            progress = it.animatedValue as Float
            invalidate()
        }
        animator.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.isEmpty()) return

        val width = width.toFloat()
        val height = height.toFloat()
        val bottomPadding = 50f // Space for labels
        val drawHeight = height - bottomPadding
        
        val barWidth = (width / data.size) * 0.5f
        val spacing = (width / data.size) * 0.5f
        val maxVal = (data.maxOrNull() ?: 1f) * 1.1f

        for (i in data.indices) {
            val left = i * (barWidth + spacing) + spacing / 2
            val right = left + barWidth
            val barHeight = (data[i] / maxVal) * drawHeight * progress
            val top = drawHeight - barHeight
            val bottom = drawHeight

            // Draw background bar
            val bgRect = RectF(left, 0f, right, drawHeight)
            canvas.drawRoundRect(bgRect, 12f, 12f, barBgPaint)

            // Draw actual bar
            val rect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(rect, 12f, 12f, barPaint)
            
            // Draw label
            if (i < labels.size) {
                canvas.drawText(labels[i], left + barWidth/2, height - 10f, textPaint)
            }
        }
    }
}
