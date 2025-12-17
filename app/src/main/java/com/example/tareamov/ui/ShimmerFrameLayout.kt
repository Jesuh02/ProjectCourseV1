package com.example.tareamov.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout

class ShimmerFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val shimmerPaint = Paint()
    private var shimmerTranslate = 0f
    private var shimmerAnimator: ValueAnimator? = null
    private var linearGradient: LinearGradient? = null
    private val matrix = Matrix()
    private var isShimmering = false

    init {
        setWillNotDraw(false)
        shimmerPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val width = w.toFloat()
            // Gradient for Dark Mode Shimmer (Shadow Effect)
            // Base color is #2A2A2A (Dark Grey).
            // User requested a "dark tone line", so we use a darker color (#181818)
            // to create a subtle shadow wave passing through the skeleton.
            val shimmerColor = Color.parseColor("#181818")
            val colors = intArrayOf(Color.TRANSPARENT, shimmerColor, Color.TRANSPARENT)
            
            // Widen the positions slightly for better visibility
            val positions = floatArrayOf(0.2f, 0.5f, 0.8f)
            
            // Create gradient that is as wide as the view, starting to the left
            linearGradient = LinearGradient(
                -width, 0f, 0f, 0f,
                colors, positions, Shader.TileMode.CLAMP
            )
            shimmerPaint.shader = linearGradient
            
            // Restart animation if needed to update bounds
            if (isShimmering) {
                 startShimmer()
            }
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (isShimmering && linearGradient != null) {
            // 1. Draw children normally (base layer)
            super.dispatchDraw(canvas)
            
            // 2. Draw shimmer overlay
            // We want to draw the gradient ONLY where the children are drawn.
            // We can achieve this by drawing the children again into a new layer, 
            // and then using SRC_IN to draw the gradient onto them.
            
            // Save a layer to draw the mask and gradient
            val saveCount = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            
            // Draw children again (as the mask)
            super.dispatchDraw(canvas)
            
            // Update the gradient position
            matrix.setTranslate(shimmerTranslate, 0f)
            linearGradient?.setLocalMatrix(matrix)
            
            // Draw the gradient rect, masked by the children (SRC_IN)
            // This replaces the children's pixels with the gradient's pixels where they overlap
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shimmerPaint)
            
            // Restore the layer, which composites the result (shimmer) onto the canvas (over the base layer)
            canvas.restoreToCount(saveCount)
        } else {
            super.dispatchDraw(canvas)
        }
    }

    fun startShimmer() {
        if (shimmerAnimator?.isRunning == true) return
        
        isShimmering = true
        // If width is 0 (view not laid out yet), we can't start animation properly.
        // It will be started in onSizeChanged or next layout pass if isShimmering is true.
        if (width == 0) return
        
        val width = width.toFloat()
        
        // Animate from 0 to 2*width to move the gradient across the view
        shimmerAnimator?.cancel()
        shimmerAnimator = ValueAnimator.ofFloat(0f, width * 2).apply {
            duration = 1500
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                shimmerTranslate = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
            start()
        }
    }

    fun stopShimmer() {
        isShimmering = false
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        invalidate()
    }
    
    override fun onDetachedFromWindow() {
        stopShimmer()
        super.onDetachedFromWindow()
    }
}
