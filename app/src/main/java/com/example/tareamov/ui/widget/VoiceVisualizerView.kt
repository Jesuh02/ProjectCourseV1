package com.example.tareamov.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

/**
 * A custom view that visualizes voice amplitude with animated bars.
 * Designed to look like a modern AI voice interface.
 */
class VoiceVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    // Number of bars to display
    private val barCount = 5
    // Current amplitudes for each bar (0.0 to 1.0)
    private val amplitudes = FloatArray(barCount) { 0.1f }
    // Target amplitudes for animation smoothing
    private val targetAmplitudes = FloatArray(barCount) { 0.1f }
    
    private var isAnimating = false
    private val random = Random(System.currentTimeMillis())
    
    // Configurable properties
    private val barWidth = 12f // dp to px conversion needed ideally
    private val barSpacing = 8f
    private val minBarHeight = 10f
    private var maxBarHeight = 100f

    init {
        // Convert dp to px for better scaling
        val density = context.resources.displayMetrics.density
        maxBarHeight = 60f * density
    }

    /**
     * Update the visualizer with a new RMS value (0-10 roughly from SpeechRecognizer)
     */
    fun updateAmplitude(db: Float) {
        if (!isAnimating) return
        
        // Normalize db to 0.0 - 1.0 range (roughly)
        // dB usually goes from -2 to 10
        val normalized = ((db + 2) / 12f).coerceIn(0.1f, 1.0f)
        
        // Generate targets for bars based on middle being highest
        // Pattern: [low, med, high, med, low] * normalized
        targetAmplitudes[2] = normalized // Center
        targetAmplitudes[1] = normalized * 0.7f + random.nextFloat() * 0.2f
        targetAmplitudes[3] = normalized * 0.7f + random.nextFloat() * 0.2f
        targetAmplitudes[0] = normalized * 0.4f + random.nextFloat() * 0.2f
        targetAmplitudes[4] = normalized * 0.4f + random.nextFloat() * 0.2f
        
        invalidate()
    }

    fun startAnimating() {
        isAnimating = true
        invalidate()
    }

    fun stopAnimating() {
        isAnimating = false
        for (i in amplitudes.indices) {
            targetAmplitudes[i] = 0.1f
            amplitudes[i] = 0.1f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val totalWidth = (barCount * barWidth * resources.displayMetrics.density) + 
                         ((barCount - 1) * barSpacing * resources.displayMetrics.density)
        var startX = (width - totalWidth) / 2f
        val centerY = height / 2f

        for (i in 0 until barCount) {
            // Lerp amplitude for smoothness
            amplitudes[i] = lerp(amplitudes[i], targetAmplitudes[i], 0.2f)
            
            // Calculate height
            val barH = minBarHeight + (amplitudes[i] * maxBarHeight)
            
            val density = resources.displayMetrics.density
            val currentBarWidth = barWidth * density
            
            val rect = RectF(
                startX,
                centerY - (barH / 2),
                startX + currentBarWidth,
                centerY + (barH / 2)
            )
            
            // Dynamic color based on height/amplitude? 
            // Let's keep it white/cyan for "AI" look
            if (i == 2 && amplitudes[i] > 0.5f) {
                paint.color = Color.parseColor("#00E5FF") // Cyan accent
            } else {
                paint.color = Color.WHITE
            }
            
            canvas.drawRoundRect(rect, 10f, 10f, paint)
            
            startX += currentBarWidth + (barSpacing * density)
        }

        if (isAnimating) {
            postInvalidateOnAnimation()
        }
    }

    private fun lerp(start: Float, stop: Float, amount: Float): Float {
        return start + (stop - start) * amount
    }
}
