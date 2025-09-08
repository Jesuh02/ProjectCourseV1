package com.example.tareamov.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.util.SessionManager

class SplashFragment : Fragment() {
    
    private val splashTimeOut: Long = 3000 // Exactly 3 seconds as requested
    private lateinit var letterViews: List<TextView>
    private lateinit var particleViews: List<ImageView>
    private lateinit var codeElements: List<TextView>
    private lateinit var binaryElements: List<TextView>
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Log to verify the fragment is being created
        Log.d("SplashFragment", "onCreateView called")
        return inflater.inflate(R.layout.fragment_splash, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Log.d("SplashFragment", "onViewCreated called")

        initializeViews(view)
        initializeKeyboardSound()
        startSpectacularAnimation()
        
        // Navigate after animation sequence
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToNextScreen()
        }, splashTimeOut)
    }

    private fun initializeKeyboardSound() {
        try {
            mediaPlayer = MediaPlayer.create(requireContext(), R.raw.keyboard_typing_sound)
            mediaPlayer?.isLooping = false
            mediaPlayer?.setVolume(0.3f, 0.3f) // Set volume to 30%
        } catch (e: Exception) {
            Log.e("SplashFragment", "Error initializing keyboard sound: ${e.message}")
            mediaPlayer = null
        }
    }

    private fun playKeyboardSound() {
        try {
            mediaPlayer?.let { player ->
                if (!player.isPlaying) {
                    player.seekTo(0)
                    player.start()
                }
            }
        } catch (e: Exception) {
            Log.e("SplashFragment", "Error playing keyboard sound: ${e.message}")
        }
    }
    private fun initializeViews(view: View) {
        // Initialize letter views for "CourseV"
        letterViews = listOf(
            view.findViewById(R.id.letter_c),
            view.findViewById(R.id.letter_o1),
            view.findViewById(R.id.letter_u),
            view.findViewById(R.id.letter_r),
            view.findViewById(R.id.letter_s),
            view.findViewById(R.id.letter_e),
            view.findViewById(R.id.letter_v)
        )

        // Initialize particle views (coding theme)
        particleViews = listOf(
            view.findViewById(R.id.codeParticle1),
            view.findViewById(R.id.codeParticle2),
            view.findViewById(R.id.codeParticle3),
            view.findViewById(R.id.codeParticle4),
            view.findViewById(R.id.codeParticle5),
            view.findViewById(R.id.codeParticle6)
        )

        // Initialize floating code elements
        codeElements = listOf(
            view.findViewById(R.id.floatingCode1),
            view.findViewById(R.id.floatingCode2),
            view.findViewById(R.id.floatingCode3)
        )

        // Initialize binary rain elements
        binaryElements = listOf(
            view.findViewById(R.id.binaryRain1),
            view.findViewById(R.id.binaryRain2)
        )
    }

    private fun startSpectacularAnimation() {
        val logoImage = view?.findViewById<ImageView>(R.id.splashLogo)
        val progressBar = view?.findViewById<ProgressBar>(R.id.loadingProgressBar)
        val loadingTextView = view?.findViewById<TextView>(R.id.loadingTextView)
        val subtitleText = view?.findViewById<TextView>(R.id.subtitleText)

        // Step 1: Start binary rain effect (0ms)
        startBinaryRainEffect()

        // Step 2: Animate logo entrance with burst effect (200ms)
        Handler(Looper.getMainLooper()).postDelayed({
            logoImage?.let { logo ->
                animateNetflixStyleLogo(logo)
            }
        }, 200)

        // Step 3: Animate particles with orbit effect (400ms)
        Handler(Looper.getMainLooper()).postDelayed({
            animateCodeParticlesOrbit()
        }, 400)

        // Step 4: Animate floating code elements (600ms)
        Handler(Looper.getMainLooper()).postDelayed({
            animateFloatingCodeElements()
        }, 600)

        // Step 5: Netflix-style letter animation sequence (800ms)
        Handler(Looper.getMainLooper()).postDelayed({
            animateNetflixLetterSequence()
        }, 800)

        // Step 6: Show subtitle with typing effect (1800ms)
        Handler(Looper.getMainLooper()).postDelayed({
            subtitleText?.let { animateTypingEffect(it) }
        }, 1800)

        // Step 7: Show progress bar with Netflix style (2200ms)
        Handler(Looper.getMainLooper()).postDelayed({
            progressBar?.let { animateNetflixProgressBar(it) }
            loadingTextView?.let { animateLoadingTextWithCursor(it) }
        }, 2200)
    }

    private fun startBinaryRainEffect() {
        binaryElements.forEachIndexed { index, element ->
            val fallAnimator = ObjectAnimator.ofFloat(element, "translationY", -200f, view?.height?.toFloat() ?: 1000f)
            fallAnimator.duration = 2000 + index * 300L
            fallAnimator.repeatCount = ObjectAnimator.INFINITE
            fallAnimator.interpolator = android.view.animation.LinearInterpolator()
            
            val alphaAnimator = ObjectAnimator.ofFloat(element, "alpha", 0f, 0.7f, 0f)
            alphaAnimator.duration = 2000 + index * 300L
            alphaAnimator.repeatCount = ObjectAnimator.INFINITE
            
            fallAnimator.start()
            alphaAnimator.start()
        }
    }

    private fun animateNetflixStyleLogo(logo: ImageView) {
        // Netflix-style dramatic entrance with burst effect
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0f, 1.3f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f, 1.3f, 1f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)
        val rotation = PropertyValuesHolder.ofFloat(View.ROTATION, -180f, 0f)

        val logoAnimator = ObjectAnimator.ofPropertyValuesHolder(logo, scaleX, scaleY, alpha, rotation)
        logoAnimator.duration = 800
        logoAnimator.interpolator = OvershootInterpolator(1.8f)
        
        // Add flash effect
        logoAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                startLogoFlashEffect(logo)
            }
            override fun onAnimationEnd(animation: Animator) {
                startLogoContinuousPulse(logo)
            }
        })
        
        logoAnimator.start()
    }

    private fun startLogoFlashEffect(logo: ImageView) {
        val flashAnimator = ObjectAnimator.ofFloat(logo, "alpha", 1f, 0.3f, 1f)
        flashAnimator.duration = 150
        flashAnimator.repeatCount = 3
        flashAnimator.start()
    }

    private fun startLogoContinuousPulse(logo: ImageView) {
        val pulseX = ObjectAnimator.ofFloat(logo, "scaleX", 1f, 1.05f, 1f)
        val pulseY = ObjectAnimator.ofFloat(logo, "scaleY", 1f, 1.05f, 1f)
        
        pulseX.duration = 1500
        pulseY.duration = 1500
        pulseX.repeatCount = ObjectAnimator.INFINITE
        pulseY.repeatCount = ObjectAnimator.INFINITE
        
        pulseX.start()
        pulseY.start()
    }

    private fun animateCodeParticlesOrbit() {
        particleViews.forEachIndexed { index, particle ->
            // Fade in with burst
            val alphaAnimator = ObjectAnimator.ofFloat(particle, "alpha", 0f, 0.9f)
            alphaAnimator.duration = 600
            alphaAnimator.startDelay = index * 100L
            alphaAnimator.start()

            // Orbital movement around the center
            val centerX = view?.width?.div(2f) ?: 0f
            val centerY = view?.height?.div(2f) ?: 0f
            val radius = 150f + index * 20f
            val duration = 4000L + index * 500L

            val rotationAnimator = ValueAnimator.ofFloat(0f, 360f)
            rotationAnimator.duration = duration
            rotationAnimator.repeatCount = ValueAnimator.INFINITE
            rotationAnimator.interpolator = android.view.animation.LinearInterpolator()
            
            rotationAnimator.addUpdateListener { animator ->
                val angle = Math.toRadians((animator.animatedValue as Float).toDouble())
                val x = centerX + radius * Math.cos(angle).toFloat()
                val y = centerY + radius * Math.sin(angle).toFloat()
                particle.x = x - particle.width / 2
                particle.y = y - particle.height / 2
            }
            
            rotationAnimator.startDelay = index * 100L
            rotationAnimator.start()
        }
    }

    private fun animateFloatingCodeElements() {
        codeElements.forEachIndexed { index, element ->
            // Netflix-style entrance
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0f, 1.2f, 1f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0f, 1.2f, 1f)
            val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 0.8f)
            val rotation = PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 360f)

            val entranceAnimator = ObjectAnimator.ofPropertyValuesHolder(element, scaleX, scaleY, alpha, rotation)
            entranceAnimator.duration = 800
            entranceAnimator.startDelay = index * 200L
            entranceAnimator.interpolator = OvershootInterpolator(1.5f)
            
            entranceAnimator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    startFloatingMotion(element)
                }
            })
            
            entranceAnimator.start()
        }
    }

    private fun startFloatingMotion(element: TextView) {
        val floatY = ObjectAnimator.ofFloat(element, "translationY", 0f, -30f, 0f)
        val floatX = ObjectAnimator.ofFloat(element, "translationX", 0f, 20f, 0f)
        val rotate = ObjectAnimator.ofFloat(element, "rotation", 0f, 360f)
        
        floatY.duration = 3000
        floatX.duration = 4000
        rotate.duration = 8000
        
        floatY.repeatCount = ObjectAnimator.INFINITE
        floatX.repeatCount = ObjectAnimator.INFINITE
        rotate.repeatCount = ObjectAnimator.INFINITE
        
        floatY.start()
        floatX.start()
        rotate.start()
    }

    private fun animateNetflixLetterSequence() {
        letterViews.forEachIndexed { index, letter ->
            Handler(Looper.getMainLooper()).postDelayed({
                animateNetflixStyleLetter(letter, index)
            }, index * 100L) // Faster sequence than original
        }
    }

    private fun animateNetflixStyleLetter(letter: TextView, index: Int) {
        // Netflix dramatic letter entrance
        val translateY = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 80f, -20f, 0f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 0f, 1f)
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 0.5f, 1.2f, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 0.5f, 1.2f, 1f)
        val rotation = PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 360f)

        val letterAnimator = ObjectAnimator.ofPropertyValuesHolder(letter, translateY, alpha, scaleX, scaleY, rotation)
        letterAnimator.duration = 700
        letterAnimator.interpolator = OvershootInterpolator(2.5f)
        
        // Add flash effect for dramatic impact
        letterAnimator.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationStart(animation: Animator) {
                // Flash effect
                val flashAnimator = ObjectAnimator.ofFloat(letter, "alpha", 0f, 1f, 0.5f, 1f)
                flashAnimator.duration = 200
                flashAnimator.start()
            }
            override fun onAnimationEnd(animation: Animator) {
                // Special glow effect for first and last letters (C and V)
                if (index == 0 || index == letterViews.size - 1) {
                    startLetterGlowEffect(letter)
                }
            }
        })
        
        letterAnimator.start()
    }

    private fun startLetterGlowEffect(letter: TextView) {
        val glowAnimator = ObjectAnimator.ofFloat(letter, "alpha", 1f, 0.6f, 1f)
        glowAnimator.duration = 1000
        glowAnimator.repeatCount = ObjectAnimator.INFINITE
        glowAnimator.start()
        
        // Add subtle scale pulsing
        val pulseX = ObjectAnimator.ofFloat(letter, "scaleX", 1f, 1.1f, 1f)
        val pulseY = ObjectAnimator.ofFloat(letter, "scaleY", 1f, 1.1f, 1f)
        pulseX.duration = 1000
        pulseY.duration = 1000
        pulseX.repeatCount = ObjectAnimator.INFINITE
        pulseY.repeatCount = ObjectAnimator.INFINITE
        pulseX.start()
        pulseY.start()
    }

    private fun animateTypingEffect(textView: TextView) {
        val originalText = textView.text.toString()
        textView.text = ""
        textView.alpha = 1f
        
        // Play keyboard sound when typing starts
        playKeyboardSound()
        
        val typingHandler = Handler(Looper.getMainLooper())
        var currentIndex = 0
        
        val typingRunnable = object : Runnable {
            override fun run() {
                if (currentIndex <= originalText.length) {
                    textView.text = originalText.substring(0, currentIndex)
                    currentIndex++
                    typingHandler.postDelayed(this, 50) // Typing speed
                }
            }
        }
        
        typingHandler.post(typingRunnable)
    }

    private fun animateNetflixProgressBar(progressBar: ProgressBar) {
        // Dramatic entrance
        val alpha = ObjectAnimator.ofFloat(progressBar, "alpha", 0f, 1f)
        val scaleX = ObjectAnimator.ofFloat(progressBar, "scaleX", 0f, 1f)
        
        alpha.duration = 500
        scaleX.duration = 500
        alpha.start()
        scaleX.start()

        // Netflix-style progress animation
        val progressAnimator = ValueAnimator.ofInt(0, 100)
        progressAnimator.duration = 800
        progressAnimator.startDelay = 300
        progressAnimator.interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        progressAnimator.addUpdateListener { animator ->
            progressBar.progress = animator.animatedValue as Int
        }
        progressAnimator.start()
    }

    private fun animateLoadingTextWithCursor(loadingText: TextView) {
        val alpha = ObjectAnimator.ofFloat(loadingText, "alpha", 0f, 1f)
        alpha.duration = 400
        alpha.start()
        
        // Add blinking cursor effect
        Handler(Looper.getMainLooper()).postDelayed({
            startCursorBlinkEffect(loadingText)
        }, 400)
    }

    private fun startCursorBlinkEffect(textView: TextView) {
        val originalText = textView.text.toString()
        val handler = Handler(Looper.getMainLooper())
        var showCursor = true
        
        val blinkRunnable = object : Runnable {
            override fun run() {
                textView.text = if (showCursor) "$originalText|" else originalText
                showCursor = !showCursor
                handler.postDelayed(this, 500)
            }
        }
        
        handler.post(blinkRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e("SplashFragment", "Error releasing MediaPlayer: ${e.message}")
        }
    }

    private fun navigateToNextScreen() {
        if (isAdded && !isDetached && !isRemoving) {
            try {
                val sessionManager = SessionManager.getInstance(requireContext())
                if (sessionManager.isLoggedIn()) {
                    Log.d("SplashFragment", "Session found, navigating to videoHomeFragment")
                    findNavController().navigate(R.id.action_splashFragment_to_videoHomeFragment)
                } else {
                    Log.d("SplashFragment", "No session, navigating to loginFragment")
                    findNavController().navigate(R.id.action_splashFragment_to_loginFragment)
                }
            } catch (e: Exception) {
                Log.e("SplashFragment", "Navigation error: ${e.message}")
                try {
                    findNavController().navigate(R.id.loginFragment)
                } catch (e: Exception) {
                    Log.e("SplashFragment", "Direct navigation error: ${e.message}")
                }            }
        }
    }
}