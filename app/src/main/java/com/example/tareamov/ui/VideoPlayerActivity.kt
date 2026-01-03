package com.example.tareamov.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.tareamov.R
import com.example.tareamov.databinding.ActivityVideoPlayerBinding
import com.example.tareamov.util.TimeUtils
import com.example.tareamov.util.player.BrightnessManager
import com.example.tareamov.util.player.PipManager
// import com.example.tareamov.util.UriPermissionManager

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private lateinit var brightnessManager: BrightnessManager
    private lateinit var pipManager: PipManager
    // private lateinit var uriPermissionManager: UriPermissionManager

    private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerPrepared: Boolean = false
    private var isControlsVisible = false
    private var isMuted = false
    private val uiHandler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private val autoHideDelayMs = 3000L
    private var autoHideRunnable: Runnable? = null
    private var pendingUserSeekMs: Int? = null
    private var isScrubbing: Boolean = false
    private var hasError: Boolean = false
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize Brightness Manager
        brightnessManager = BrightnessManager(this, window, binding) {
            scheduleAutoHide() // Callback when brightness is interacted with
        }
        
        // Initialize Pip Manager
        pipManager = PipManager(this, binding.videoView)

        // Initialize controls overlay background for dimming effect
        binding.controlsOverlay.setBackgroundColor(android.graphics.Color.BLACK)
        binding.controlsOverlay.background.alpha = 0

        // Immersive fullscreen
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN
        )

        // uriPermissionManager = UriPermissionManager(this)

        // Read intent extras from adapter
        val pathOrUri = intent.getStringExtra("video_path")
        val videoTitle = intent.getStringExtra("video_title") ?: getString(R.string.app_name)
        val videoDescription = intent.getStringExtra("video_description")
        val username = intent.getStringExtra("username")
        
        // Determine start position: prefer saved state (recreation), then intent (fresh start)
        val savedPosition = if (savedInstanceState != null && savedInstanceState.containsKey("video_position")) {
            savedInstanceState.getInt("video_position")
        } else {
            intent.getIntExtra("video_position", 0)
        }
        
        binding.titleText.text = videoTitle

        Log.d("VideoPlayerActivity", "Received pathOrUri: $pathOrUri")
        Log.d("VideoPlayerActivity", "Received videoTitle: $videoTitle")
        Log.d("VideoPlayerActivity", "Received saved position: $savedPosition ms")

        val uri = try {
            when {
                pathOrUri.isNullOrBlank() -> {
                    Log.e("VideoPlayerActivity", "pathOrUri is null or blank")
                    null
                }
                pathOrUri.startsWith("http://") || pathOrUri.startsWith("https://") -> {
                    Log.d("VideoPlayerActivity", "Processing HTTP(S) URL: $pathOrUri")
                    Uri.parse(pathOrUri)
                }
                pathOrUri.startsWith("content://") -> {
                    Log.d("VideoPlayerActivity", "Processing content URI: $pathOrUri")
                    Uri.parse(pathOrUri)
                }
                pathOrUri.startsWith("file://") -> {
                    Log.d("VideoPlayerActivity", "Processing file URI: $pathOrUri")
                    Uri.parse(pathOrUri)
                }
                pathOrUri.startsWith("android.resource://") -> {
                    Log.d("VideoPlayerActivity", "Processing resource URI: $pathOrUri")
                    Uri.parse(pathOrUri)
                }
                else -> {
                    Log.d("VideoPlayerActivity", "Processing file path: $pathOrUri")
                    val file = java.io.File(pathOrUri)
                    if (file.exists()) {
                        Uri.fromFile(file)
                    } else {
                        Log.e("VideoPlayerActivity", "File does not exist: $pathOrUri")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error parsing URI: $pathOrUri", e)
            null
        }

        if (uri == null) {
            Log.e("VideoPlayerActivity", "Final URI is null for pathOrUri: $pathOrUri")
            Toast.makeText(this, "URI de video no válida: ${pathOrUri ?: "null"}", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.d("VideoPlayerActivity", "Final processed URI: $uri")

        // Mostrar spinner mientras se carga el video
        binding.loadingSpinner.visibility = View.VISIBLE

        // IMPORTANT: Set error listener BEFORE setting video URI to catch early errors
        binding.videoView.setOnErrorListener { _, what, extra ->
            if (hasError) return@setOnErrorListener true
            hasError = true
            mediaPlayerPrepared = false
            
            Log.e("VideoPlayerActivity", "Error playing video: what=$what, extra=$extra")
            progressRunnable?.let { uiHandler.removeCallbacks(it) }
            
            // Ocultar spinner si hay error
            binding.loadingSpinner.visibility = View.GONE
            
            val errorMsg = when (what) {
                MediaPlayer.MEDIA_ERROR_UNKNOWN -> "Error desconocido o formato no soportado"
                MediaPlayer.MEDIA_ERROR_SERVER_DIED -> "Error de servidor de medios"
                else -> "Error de reproducción ($what)"
            }
            
            if (!isFinishing) {
                Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
                finish()
            }
            true
        }

        binding.videoView.setVideoURI(uri)
        binding.videoView.setOnPreparedListener { mp ->
            if (hasError || isFinishing) {
                Log.w("VideoPlayerActivity", "onPrepared called but hasError=$hasError isFinishing=$isFinishing, ignoring")
                return@setOnPreparedListener
            }
            try {
                mediaPlayer = mp
                
                // Verify MediaPlayer is actually prepared before accessing properties
                val duration = try { 
                    val d = mp.duration
                    if (d <= 0) {
                        Log.e("VideoPlayerActivity", "Invalid duration: $d - video source may be corrupt or inaccessible")
                        hasError = true
                        binding.loadingSpinner.visibility = View.GONE
                        if (!isFinishing) {
                            Toast.makeText(this@VideoPlayerActivity, "El video no se puede reproducir (formato no soportado o archivo corrupto)", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                        return@setOnPreparedListener
                    } else d
                } catch (e: Exception) { 
                    Log.e("VideoPlayerActivity", "Error getting duration", e)
                    hasError = true
                    binding.loadingSpinner.visibility = View.GONE
                    if (!isFinishing) {
                        Toast.makeText(this@VideoPlayerActivity, "Error al cargar el video", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                    return@setOnPreparedListener
                }
                
                // Only mark as prepared after successful duration check
                mediaPlayerPrepared = true
                mp.isLooping = true
                
                binding.totalTime.text = TimeUtils.formatTime(duration)
                binding.seekBar.max = duration

                // Ocultar spinner cuando el video esté listo
                binding.loadingSpinner.visibility = View.GONE

                // Ajuste explícito de aspecto para modo visualización (sin tocar)
                try {
                    val videoW = mp.videoWidth
                    val videoH = mp.videoHeight
                    if (videoW > 0 && videoH > 0) {
                        val dm = resources.displayMetrics
                        val screenW = dm.widthPixels
                        val screenH = dm.heightPixels
                        val videoRatio = videoW.toFloat() / videoH
                        val screenRatio = screenW.toFloat() / screenH

                        val (targetW, targetH) = if (videoRatio > screenRatio) {
                            // Video más ancho: ajusta al ancho de pantalla, deja barras arriba/abajo si toca
                            val h = (screenW / videoRatio).toInt()
                            screenW to h
                        } else {
                            // Video más alto o cuadrado: ajusta a la altura, deja barras a los lados si toca
                            val w = (screenH * videoRatio).toInt()
                            w to screenH
                        }

                        val lp = binding.videoView.layoutParams
                        lp.width = targetW
                        lp.height = targetH
                        binding.videoView.layoutParams = lp
                    }
                } catch (_: Exception) { }

                setMuted(isMuted)
                
                // Restore saved video position from intent
                if (savedPosition > 0) {
                    try {
                        mp.seekTo(savedPosition)
                        binding.currentTime.text = TimeUtils.formatTime(savedPosition)
                        binding.seekBar.progress = savedPosition
                        Log.d("VideoPlayerActivity", "Restored video position to $savedPosition ms")
                    } catch (e: Exception) {
                        Log.e("VideoPlayerActivity", "Error restoring video position", e)
                    }
                }
                
                if (!hasError) {
                    try {
                        mp.start()
                        startProgressUpdater()
                    } catch (e: Exception) {
                        Log.e("VideoPlayerActivity", "Error starting playback", e)
                        hasError = true
                        mediaPlayerPrepared = false
                    }
                }

                // Sync when seek completes
                try {
                    mp.setOnSeekCompleteListener {
                        if (!isScrubbing) {
                            try {
                                val pos = mp.currentPosition
                                binding.currentTime.text = TimeUtils.formatTime(pos)
                                binding.seekBar.progress = pos
                            } catch (e: Exception) { Log.e("VideoPlayerActivity", "Error in OnSeekComplete", e) }
                        }
                    }
                } catch (_: Exception) { }

                // Listener para buffering (muestra spinner mientras se carga más contenido)
                try {
                    mp.setOnInfoListener { _, what, _ ->
                        when (what) {
                            MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                                // Video está buffeando, mostrar spinner
                                binding.loadingSpinner.visibility = View.VISIBLE
                                Log.d("VideoPlayerActivity", "Buffering started")
                            }
                            MediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                                // Buffering terminado, ocultar spinner
                                binding.loadingSpinner.visibility = View.GONE
                                Log.d("VideoPlayerActivity", "Buffering ended")
                            }
                        }
                        false
                    }
                } catch (_: Exception) { }
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error in onPrepared", e)
                mediaPlayerPrepared = false
            }
        }

    // Start with controls hidden
    hideControls(immediate = true)

    // Interaction: swipe for brightness (left), tap for controls
        binding.controlsOverlay.setOnTouchListener { _, event ->
            // Delegate touch to BrightnessManager first
            if (brightnessManager.onTouch(event)) {
                return@setOnTouchListener true
            }
            
            // If not consumed by brightness, handle tap for controls
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                if (isControlsVisible) {
                    hideControls()
                } else {
                    showControls()
                }
                return@setOnTouchListener true
            }
            
            true
        }

        binding.playPauseOverlay.setOnClickListener {
            if (!mediaPlayerPrepared) return@setOnClickListener
            try {
                if (binding.videoView.isPlaying) {
                    binding.videoView.pause()
                } else {
                    binding.videoView.start()
                }
                updatePlayPauseIcon()
                scheduleAutoHide()
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error toggling play/pause", e)
            }
        }

        binding.backButton.setOnClickListener {
            try {
                // Instead of finishing the app, navigate back to the VideoHomeFragment hosted by MainActivity
                val i = Intent(this, com.example.tareamov.MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                i.putExtra("open_video_home", true)
                // Pass current position and path to restore state
                i.putExtra("video_position", binding.videoView.currentPosition)
                i.putExtra("video_path", pathOrUri)
                startActivity(i)
                finish()
            } catch (e: Exception) {
                Log.w("VideoPlayerActivity", "Failed to navigate to VideoHomeFragment via MainActivity", e)
                finish()
            }
        }

        binding.btnFloatingMode.setOnClickListener {
            try {
                val uriToSend = pathOrUri
                if (uriToSend.isNullOrEmpty()) {
                    Toast.makeText(this, "URI de video no disponible para modo flotante", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Prefer system Picture-in-Picture when available (Android O+). This keeps playback
                // visible after leaving the app. Otherwise fallback to the existing in-app floating container.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        pipManager.enterPipMode()
                        // Do not finish activity: leaving it in PIP keeps playback.
                    } catch (pipEx: Exception) {
                        Log.w("VideoPlayerActivity", "PIP failed, falling back to in-app floating", pipEx)
                        // fallback to MainActivity in-app floating
                        val i = Intent(this, com.example.tareamov.MainActivity::class.java)
                        i.putExtra("floating_video_path", uriToSend)
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(i)
                        finish()
                    }
                } else {
                    // Older devices: use the in-app floating fragment pathway
                    val i = Intent(this, com.example.tareamov.MainActivity::class.java)
                    i.putExtra("floating_video_path", uriToSend)
                    i.putExtra("video_position", binding.videoView.currentPosition)
                    i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    startActivity(i)
                    finish()
                }
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error initiating floating mode", e)
                Toast.makeText(this, "No se pudo abrir el modo flotante", Toast.LENGTH_SHORT).show()
            }
        }

        binding.skipBackIcon.setOnClickListener { seekBy(-10_000) }
        binding.skipForwardIcon.setOnClickListener { seekBy(10_000) }

    binding.muteButton.setOnClickListener {
            isMuted = !isMuted
            setMuted(isMuted)
        }
        

        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // No buscar en caliente: solo actualiza tiempo mostrado y guarda posición
                    pendingUserSeekMs = progress
                    binding.currentTime.text = TimeUtils.formatTime(progress)
                    // Keep controls visible while scrubbing
                    showControls()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                // Cancel auto hide while dragging
                cancelAutoHide()
                isScrubbing = true
                showControls()
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                // Realiza el seek definitivo al soltar
                pendingUserSeekMs?.let { target ->
                    try { binding.videoView.seekTo(target) } catch (_: Exception) {}
                }
                pendingUserSeekMs = null
                isScrubbing = false
                scheduleAutoHide()
            }
        })
    }

    private fun showControls() {
        cancelAutoHide()
        updatePlayPauseIcon()
        if (!isControlsVisible) {
            isControlsVisible = true
            fadeVisibility(binding.topBar, true)
            fadeVisibility(binding.bottomBar, true)
            fadeVisibility(binding.skipBackIcon, true)
            fadeVisibility(binding.skipForwardIcon, true)
            fadeVisibility(binding.playPauseOverlay, true)
            
            // Show Brightness Overlay with Controls via Manager
            brightnessManager.syncWithControls()
            
            // Animate background dimming
            animateOverlayBackground(true)
        }
        scheduleAutoHide()
    }

    private fun hideControls(immediate: Boolean = false) {
        cancelAutoHide()
        if (isControlsVisible) {
            isControlsVisible = false
            if (immediate) {
                binding.topBar.apply { alpha = 0f; visibility = View.GONE }
                binding.bottomBar.apply { alpha = 0f; visibility = View.GONE }
                binding.skipBackIcon.apply { alpha = 0f; visibility = View.GONE }
                binding.skipForwardIcon.apply { alpha = 0f; visibility = View.GONE }
                binding.playPauseOverlay.apply { alpha = 0f; visibility = View.GONE }
                brightnessManager.hideOverlay(immediate = true)
                animateOverlayBackground(false, immediate = true)
            } else {
                fadeVisibility(binding.topBar, false)
                fadeVisibility(binding.bottomBar, false)
                fadeVisibility(binding.skipBackIcon, false)
                fadeVisibility(binding.skipForwardIcon, false)
                fadeVisibility(binding.playPauseOverlay, false)
                
                brightnessManager.hideOverlay(immediate = false)
                animateOverlayBackground(false)
            }
        }
    }

    private fun animateOverlayBackground(show: Boolean, immediate: Boolean = false) {
        val targetAlpha = if (show) 102 else 0 // 102 is approx 40% opacity (0x66)
        val background = binding.controlsOverlay.background ?: return
        
        if (immediate) {
            background.alpha = targetAlpha
            return
        }
        
        val currentAlpha = background.alpha
        if (currentAlpha == targetAlpha) return
        
        val animator = android.animation.ValueAnimator.ofInt(currentAlpha, targetAlpha)
        animator.duration = 250
        animator.addUpdateListener { animation ->
            background.alpha = animation.animatedValue as Int
        }
        animator.start()
    }

    private fun fadeVisibility(view: View, show: Boolean) {
        val toAlpha = if (show) 1f else 0f
        val endVisibility = if (show) View.VISIBLE else View.GONE
        if (show) view.visibility = View.VISIBLE
        view.animate()
            .alpha(toAlpha)
            .setDuration(250)
            .withEndAction { view.visibility = endVisibility }
            .start()
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        autoHideRunnable = Runnable { hideControls(immediate = false) }
        uiHandler.postDelayed(autoHideRunnable!!, autoHideDelayMs)
    }

    private fun cancelAutoHide() {
        autoHideRunnable?.let { uiHandler.removeCallbacks(it) }
    }

    private fun startProgressUpdater() {
        progressRunnable?.let { uiHandler.removeCallbacks(it) }
        progressRunnable = object : Runnable {
            override fun run() {
                if (isFinishing || !mediaPlayerPrepared) return

                try {
                    if (!isScrubbing && binding.videoView.isPlaying) {
                        val pos = binding.videoView.currentPosition
                        binding.seekBar.progress = pos
                        binding.currentTime.text = TimeUtils.formatTime(pos)
                    }
                } catch (e: Exception) {
                    Log.w("VideoPlayerActivity", "Error updating progress: ${e.message}")
                } finally {
                    if (!isFinishing) {
                        uiHandler.postDelayed(this, 500)
                    }
                }
            }
        }
        uiHandler.post(progressRunnable!!)
    }

    private fun setMuted(muted: Boolean) {
        // Update desired mute state immediately
        isMuted = muted
        
        // Only apply volume if MediaPlayer is prepared
        if (mediaPlayerPrepared) {
            try {
                val volume = if (muted) 0f else 1f
                mediaPlayer?.setVolume(volume, volume)
                binding.muteButton.setImageResource(if (muted) R.drawable.ic_sound_muted_minimal else R.drawable.ic_sound_minimal)
            } catch (e: IllegalStateException) {
                Log.e("VideoPlayerActivity", "Error setting volume, MediaPlayer might not be ready.", e)
                // Keep isMuted flag; volume will be applied later when prepared
                mediaPlayerPrepared = false
            }
        } else {
            // Not prepared yet: volume will be applied when onPreparedListener runs
            Log.d("VideoPlayerActivity", "MediaPlayer not prepared yet; saved mute state=$isMuted")
            // Still update button appearance
            binding.muteButton.setImageResource(if (muted) R.drawable.ic_sound_muted_minimal else R.drawable.ic_sound_minimal)
        }
    }

    private fun seekBy(deltaMs: Int) {
        if (!mediaPlayerPrepared) return
        try {
            val duration = if (binding.videoView.duration > 0) binding.videoView.duration else binding.seekBar.max
            val newPos = (binding.videoView.currentPosition + deltaMs).coerceIn(0, duration)
            binding.videoView.seekTo(newPos)
            binding.currentTime.text = TimeUtils.formatTime(newPos)
            showControls()
        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error seeking", e)
        }
    }

    private fun updatePlayPauseIcon() {
        if (!mediaPlayerPrepared) return
        try {
            if (binding.videoView.isPlaying) {
                binding.playPauseOverlay.setImageResource(R.drawable.ic_pause_overlay)
            } else {
                binding.playPauseOverlay.setImageResource(R.drawable.ic_play_overlay)
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error updating play/pause icon", e)
        }
    }

    override fun onPause() {
        super.onPause()
        // If the activity enters background but is in PIP mode, keep playback as-is (playing or paused).
        // Only pause playback when the activity is fully paused and NOT in PIP mode.
        if (!isInPictureInPictureMode && binding.videoView.isPlaying) {
            binding.videoView.pause()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Trigger PIP automatically when user leaves the activity (press Home)
        // Works regardless of playback state (playing or paused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            try {
                pipManager.enterPipMode()
            } catch (t: Throwable) {
                Log.w("VideoPlayerActivity", "Auto PIP failed", t)
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        pipManager.onPictureInPictureModeChanged(isInPictureInPictureMode)
        
        // Hide controls in PIP to keep the small window clean
        if (isInPictureInPictureMode) {
            // hide heavy UI
            binding.topBar.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
            binding.controlsOverlay.visibility = View.GONE
        } else {
            // restore UI when returning
            binding.topBar.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.VISIBLE
            binding.controlsOverlay.visibility = View.VISIBLE
            
            // Ensure controls are ready and progress is updating
            showControls()
            try {
                if (mediaPlayerPrepared && binding.videoView.isPlaying) {
                    startProgressUpdater()
                }
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error resuming from PIP", e)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            if (mediaPlayerPrepared) {
                outState.putInt("video_position", binding.videoView.currentPosition)
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error saving state", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressRunnable?.let { uiHandler.removeCallbacks(it) }
        pipManager.unregisterReceiver()
        try { binding.videoView.stopPlayback() } catch (_: Exception) { }
    }
}