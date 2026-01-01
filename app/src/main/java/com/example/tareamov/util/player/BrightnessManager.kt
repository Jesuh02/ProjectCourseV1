package com.example.tareamov.util.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.widget.SeekBar
import com.example.tareamov.databinding.ActivityVideoPlayerBinding
import kotlin.math.abs

class BrightnessManager(
    private val context: Context,
    private val window: Window,
    private val binding: ActivityVideoPlayerBinding,
    private val onInteraction: () -> Unit // Callback para notificar interacción (resetear auto-hide de controles)
) {

    private var startY = 0f
    private var startX = 0f
    private var isBrightnessAdjusting = false
    private var initialBrightness = -1f
    
    private val uiHandler = Handler(Looper.getMainLooper())
    private var brightnessHideRunnable: Runnable? = null

    init {
        setupSeekBar()
    }

    private fun setupSeekBar() {
        binding.brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    setWindowBrightness(progress / 100f)
                    onInteraction()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // Opcional: cancelar auto-hide global si fuera necesario
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                onInteraction()
            }
        })
    }

    /**
     * Maneja el evento de toque para ajustar el brillo.
     * Retorna true si el evento fue consumido por el ajuste de brillo, false si debe propagarse (ej. para detectar click).
     */
    fun onTouch(event: MotionEvent): Boolean {
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                
                // Obtener brillo actual
                val lp = window.attributes
                if (lp.screenBrightness < 0) {
                    try {
                        val sys = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                        initialBrightness = sys / 255f
                    } catch (e: Exception) {
                        initialBrightness = 0.5f
                    }
                } else {
                    initialBrightness = lp.screenBrightness
                }
                
                isBrightnessAdjusting = false
                return false // Dejar pasar para detectar click si no es scroll
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaY = startY - event.y // Arriba es positivo (incrementar)
                val deltaX = event.x - startX
                
                // Umbral para detectar scroll vs tap (ej. 30px)
                // Verificar si el swipe es en el lado izquierdo (Brillo)
                if (!isBrightnessAdjusting && startX < screenWidth / 2 && abs(deltaY) > 30 && abs(deltaY) > abs(deltaX)) {
                    isBrightnessAdjusting = true
                }
                
                if (isBrightnessAdjusting) {
                    val change = deltaY / (screenHeight * 0.8f)
                    var newB = initialBrightness + change
                    newB = newB.coerceIn(0.01f, 1.0f)
                    
                    setWindowBrightness(newB)
                    showBrightnessOverlay(newB, isFromControls = false)
                    return true // Consumido
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isBrightnessAdjusting) {
                    isBrightnessAdjusting = false
                    scheduleHideBrightnessOverlay()
                    return true // Consumido
                }
            }
        }
        return false // No consumido
    }

    private fun setWindowBrightness(brightness: Float) {
        val lp = window.attributes
        lp.screenBrightness = brightness
        window.attributes = lp
    }

    fun showBrightnessOverlay(brightness: Float, isFromControls: Boolean) {
        brightnessHideRunnable?.let { uiHandler.removeCallbacks(it) }
        
        val density = context.resources.displayMetrics.density
        // Si se muestra desde los controles, mover más a la derecha para no tapar
        val translationX = if (isFromControls) 120f * density else 40f * density
        
        binding.brightnessOverlay.translationX = translationX
        binding.brightnessOverlay.visibility = View.VISIBLE
        binding.brightnessOverlay.alpha = 1f
        binding.brightnessSeekBar.progress = (brightness * 100).toInt()
    }
    
    fun syncWithControls() {
        val lp = window.attributes
        val brightness = if (lp.screenBrightness < 0) {
            try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (e: Exception) { 0.5f }
        } else {
            lp.screenBrightness
        }
        showBrightnessOverlay(brightness, isFromControls = true)
        
        // Cancelar timer específico de brillo porque los controles principales manejan la visibilidad global
        brightnessHideRunnable?.let { uiHandler.removeCallbacks(it) }
    }
    
    fun hideOverlay(immediate: Boolean = false) {
        brightnessHideRunnable?.let { uiHandler.removeCallbacks(it) }
        if (immediate) {
            binding.brightnessOverlay.alpha = 0f
            binding.brightnessOverlay.visibility = View.GONE
        } else {
             binding.brightnessOverlay.animate()
                .alpha(0f)
                .setDuration(250)
                .withEndAction { binding.brightnessOverlay.visibility = View.GONE }
                .start()
        }
    }

    private fun scheduleHideBrightnessOverlay() {
        brightnessHideRunnable?.let { uiHandler.removeCallbacks(it) }
        
        brightnessHideRunnable = Runnable {
            binding.brightnessOverlay.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction { binding.brightnessOverlay.visibility = View.GONE }
                .start()
        }
        uiHandler.postDelayed(brightnessHideRunnable!!, 1000)
    }
}
