package com.example.tareamov.util

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import kotlin.math.cos
import kotlin.math.sin

/**
 * Animador profesional para la pantalla de carga con cerebro procesando información
 */
class BrainLoadingAnimator(
    private val brainIcon: ImageView,
    private val pulsingCircle: View,
    private val particle1: View,
    private val particle2: View,
    private val particle3: View,
    private val particle4: View
) {
    
    private var isAnimating = false
    private val animators = mutableListOf<ValueAnimator>()
    
    /**
     * Inicia todas las animaciones
     */
    fun startAnimations() {
        if (isAnimating) return
        isAnimating = true
        
        startBrainPulseAnimation()
        startCirclePulseAnimation()
        startParticleOrbitAnimations()
    }
    
    /**
     * Detiene todas las animaciones
     */
    fun stopAnimations() {
        isAnimating = false
        animators.forEach { it.cancel() }
        animators.clear()
    }
    
    /**
     * Animación de pulso del cerebro (escala)
     */
    private fun startBrainPulseAnimation() {
        val scaleX = ObjectAnimator.ofFloat(brainIcon, "scaleX", 1f, 1.15f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        val scaleY = ObjectAnimator.ofFloat(brainIcon, "scaleY", 1f, 1.15f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            start()
        }
        
        animators.add(scaleX)
        animators.add(scaleY)
    }
    
    /**
     * Animación de pulso del círculo de fondo
     */
    private fun startCirclePulseAnimation() {
        val scaleX = ObjectAnimator.ofFloat(pulsingCircle, "scaleX", 1f, 1.3f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        val scaleY = ObjectAnimator.ofFloat(pulsingCircle, "scaleY", 1f, 1.3f, 1f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        val alpha = ObjectAnimator.ofFloat(pulsingCircle, "alpha", 0.3f, 0.1f, 0.3f).apply {
            duration = 3000
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        
        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            start()
        }
        
        animators.add(scaleX)
        animators.add(scaleY)
        animators.add(alpha)
    }
    
    /**
     * Animación de órbita de partículas alrededor del cerebro
     */
    private fun startParticleOrbitAnimations() {
        val particles = listOf(
            particle1 to 0f,
            particle2 to 90f,
            particle3 to 180f,
            particle4 to 270f
        )
        
        particles.forEach { (particle, startAngle) ->
            startParticleOrbit(particle, startAngle)
        }
    }
    
    /**
     * Anima una partícula individual en órbita circular
     */
    private fun startParticleOrbit(particle: View, startAngle: Float) {
        val radius = 70f // Radio de la órbita
        val centerX = brainIcon.width / 2f
        val centerY = brainIcon.height / 2f
        
        val animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            
            addUpdateListener { animation ->
                val angle = ((animation.animatedValue as Float) + startAngle) * Math.PI / 180f
                
                val x = centerX + radius * cos(angle).toFloat()
                val y = centerY + radius * sin(angle).toFloat()
                
                particle.translationX = x - particle.width / 2f
                particle.translationY = y - particle.height / 2f
                
                // Efecto de parpadeo
                val alpha = 0.5f + 0.5f * sin(angle * 3).toFloat()
                particle.alpha = alpha
            }
        }
        
        animator.start()
        animators.add(animator)
    }
    
    /**
     * Limpia recursos
     */
    fun cleanup() {
        stopAnimations()
    }
}

