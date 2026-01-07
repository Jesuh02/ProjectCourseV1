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
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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

    // ExoPlayer for robust video playback
    private var exoPlayer: ExoPlayer? = null
    private var useExoPlayer: Boolean = true // Default to ExoPlayer
    
    // Legacy MediaPlayer (for VideoView fallback)
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
    
    // Duration estimation for videos with corrupt PTS timestamps
    private var maxPositionReached: Long = 0L
    private var estimatedDuration: Long = 0L
    private var isDurationKnown: Boolean = false
    
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
        
        // Use ExoPlayer for R2 URLs (more robust with problematic metadata)
        // Use VideoView for local files (simpler)
        val isRemoteUrl = uri.scheme == "http" || uri.scheme == "https"
        useExoPlayer = isRemoteUrl
        
        if (useExoPlayer) {
            Log.d("VideoPlayerActivity", "Using ExoPlayer for remote URL: $uri")
            setupExoPlayer(uri, savedPosition)
        } else {
            Log.d("VideoPlayerActivity", "Using VideoView for local file: $uri")
            setupVideoView(uri, savedPosition)
        }
        
        // Setup all control listeners
        setupControlListeners(pathOrUri)
    }
    
    /**
     * Setup ExoPlayer for robust video playback (handles problematic R2 videos)
     */
    private fun setupExoPlayer(uri: Uri, savedPosition: Int) {
        try {
            // Show ExoPlayer view, hide VideoView
            binding.playerView.visibility = View.VISIBLE
            binding.videoView.visibility = View.GONE
            
            // Detect if running on emulator (audio issues are common on emulators)
            val isEmulator = Build.FINGERPRINT.contains("generic") || 
                    Build.FINGERPRINT.contains("unknown") ||
                    Build.MODEL.contains("google_sdk") ||
                    Build.MODEL.contains("Emulator") ||
                    Build.MODEL.contains("Android SDK built for x86") ||
                    Build.MANUFACTURER.contains("Genymotion") ||
                    Build.BRAND.startsWith("generic") ||
                    Build.DEVICE.startsWith("generic") ||
                    "google_sdk" == Build.PRODUCT ||
                    Build.HARDWARE.contains("ranchu") ||
                    Build.HARDWARE.contains("goldfish")
            
            if (isEmulator) {
                Log.w("VideoPlayerActivity", "Running on emulator - audio may be muted to avoid hardware issues")
            }
            
            // Create ExoPlayer with custom configuration for better error resilience
            val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(this).apply {
                // Enable decoder fallback - if hardware decoder fails, use software
                setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                // Enable floating point audio output to avoid some audio issues
                setEnableAudioFloatOutput(true)
            }
            
            // Create load control with more tolerant buffering
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15000,  // Min buffer before playback starts
                    50000,  // Max buffer size
                    2500,   // Min buffer to resume after rebuffer
                    5000    // Min buffer while playing
                )
                .build()
            
            // Create ExoPlayer instance with custom config
            exoPlayer = ExoPlayer.Builder(this, renderersFactory)
                .setLoadControl(loadControl)
                .setHandleAudioBecomingNoisy(false) // Don't pause on audio focus loss
                .build().also { player ->
                binding.playerView.player = player
                
                // Set audio attributes to allow playback even with audio issues
                val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                player.setAudioAttributes(audioAttributes, false) // false = don't handle audio focus
                
                // On emulator, start muted to avoid audio hardware issues
                if (isEmulator) {
                    isMuted = true
                    player.volume = 0f
                    binding.muteButton.setImageResource(R.drawable.ic_sound_muted_minimal)
                    Log.d("VideoPlayerActivity", "Started muted on emulator to avoid audio issues")
                } else {
                    player.volume = if (isMuted) 0f else 1f
                }
                
                // Set media item
                val mediaItem = MediaItem.fromUri(uri)
                player.setMediaItem(mediaItem)
                
                // Configure playback
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.playWhenReady = true
                
                // Track error recovery attempts
                var errorRecoveryAttempts = 0
                val maxErrorRecoveryAttempts = 3
                var lastKnownPosition = 0L
                
                // Add listener for playback events
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                Log.d("VideoPlayerActivity", "ExoPlayer: STATE_READY")
                                mediaPlayerPrepared = true
                                errorRecoveryAttempts = 0 // Reset on successful playback
                                binding.loadingSpinner.visibility = View.GONE
                                
                                // Setup duration and seek bar
                                val realDuration = player.duration
                                val duration = if (realDuration == androidx.media3.common.C.TIME_UNSET || realDuration <= 0) 0 else realDuration.toInt()
                                
                                if (duration > 0) {
                                    isDurationKnown = true
                                    binding.totalTime.text = TimeUtils.formatTime(duration)
                                    binding.seekBar.max = duration
                                    binding.seekBar.isEnabled = true
                                    Log.d("VideoPlayerActivity", "ExoPlayer: Duration available = $duration ms")
                                } else {
                                    // Duration unknown - poll for it and use estimation
                                    isDurationKnown = false
                                    binding.totalTime.text = "--:--"
                                    binding.seekBar.isEnabled = true // Still allow seeking
                                    Log.w("VideoPlayerActivity", "ExoPlayer: Duration unknown, starting poll and estimation")
                                    pollForDuration(player)
                                }
                                
                                // Restore saved position (only once)
                                if (savedPosition > 0 && lastKnownPosition == 0L) {
                                    player.seekTo(savedPosition.toLong())
                                    lastKnownPosition = savedPosition.toLong()
                                    Log.d("VideoPlayerActivity", "ExoPlayer: Restored position to $savedPosition ms")
                                }
                                
                                // Start progress updater
                                startExoPlayerProgressUpdater()
                                
                                // Start with controls hidden
                                hideControls(immediate = true)
                            }
                            Player.STATE_BUFFERING -> {
                                Log.d("VideoPlayerActivity", "ExoPlayer: STATE_BUFFERING")
                                binding.loadingSpinner.visibility = View.VISIBLE
                                // Save position during buffering
                                lastKnownPosition = player.currentPosition
                            }
                            Player.STATE_ENDED -> {
                                Log.d("VideoPlayerActivity", "ExoPlayer: STATE_ENDED")
                                // Player is set to repeat, so this shouldn't happen often
                                // But when it does, capture the position as estimated duration
                                if (!isDurationKnown && maxPositionReached > 0) {
                                    estimatedDuration = maxPositionReached
                                    binding.seekBar.max = estimatedDuration.toInt()
                                    binding.totalTime.text = "~${TimeUtils.formatTime(estimatedDuration.toInt())}"
                                    Log.d("VideoPlayerActivity", "STATE_ENDED: Estimated duration from max position = $estimatedDuration ms")
                                }
                            }
                            Player.STATE_IDLE -> {
                                Log.d("VideoPlayerActivity", "ExoPlayer: STATE_IDLE")
                            }
                        }
                    }
                    
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        Log.d("VideoPlayerActivity", "ExoPlayer: isPlaying=$isPlaying")
                        if (isPlaying) {
                            lastKnownPosition = player.currentPosition
                        }
                        updatePlayPauseIcon()
                    }

                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        val realDuration = player.duration
                        if (realDuration != androidx.media3.common.C.TIME_UNSET && realDuration > 0) {
                            isDurationKnown = true
                            val duration = realDuration.toInt()
                            if (binding.seekBar.max == 0 || binding.seekBar.max < duration) {
                                binding.totalTime.text = TimeUtils.formatTime(duration)
                                binding.seekBar.max = duration
                                binding.seekBar.isEnabled = true
                                Log.d("VideoPlayerActivity", "onTimelineChanged: Duration updated to $duration ms")
                            }
                        }
                    }
                    
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e("VideoPlayerActivity", "ExoPlayer error: ${error.message}, code=${error.errorCode}", error)
                        
                        // Check if this is an audio-related error that we can recover from
                        val isAudioError = error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                                error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                                error.message?.contains("audio", ignoreCase = true) == true ||
                                error.message?.contains("AudioTrack", ignoreCase = true) == true ||
                                error.message?.contains("pcm", ignoreCase = true) == true
                        
                        if ((isAudioError || isEmulator) && errorRecoveryAttempts < maxErrorRecoveryAttempts) {
                            errorRecoveryAttempts++
                            Log.w("VideoPlayerActivity", "Audio/emulator error detected, attempting recovery #$errorRecoveryAttempts from position $lastKnownPosition")
                            
                            // Try to recover by muting and continuing playback
                            try {
                                player.volume = 0f // Mute to avoid audio issues
                                isMuted = true
                                
                                uiHandler.post {
                                    binding.muteButton.setImageResource(R.drawable.ic_sound_muted_minimal)
                                }
                                
                                // Clear error and restart from last known position
                                player.prepare()
                                if (lastKnownPosition > 0) {
                                    player.seekTo(lastKnownPosition)
                                }
                                player.play()
                                
                                uiHandler.post {
                                    Toast.makeText(this@VideoPlayerActivity, 
                                        "Audio silenciado para continuar reproducción", 
                                        Toast.LENGTH_SHORT).show()
                                }
                                return
                            } catch (recoveryError: Exception) {
                                Log.e("VideoPlayerActivity", "Error during audio recovery", recoveryError)
                            }
                        }
                        
                        // Only mark as fatal error after recovery attempts exhausted
                        hasError = true
                        mediaPlayerPrepared = false
                        binding.loadingSpinner.visibility = View.GONE
                        
                        // ExoPlayer is more tolerant, but some errors are still fatal
                        if (!isFinishing) {
                            Toast.makeText(this@VideoPlayerActivity, "Error de reproducción: ${error.message}", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    }
                })
                
                // Prepare player
                player.prepare()
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error setting up ExoPlayer", e)
            // Fallback to VideoView
            useExoPlayer = false
            setupVideoView(uri, savedPosition)
        }
    }
    
    /**
     * Setup VideoView for local file playback (fallback)
     */
    private fun setupVideoView(uri: Uri, savedPosition: Int) {
        // Show VideoView, hide ExoPlayer view
        binding.videoView.visibility = View.VISIBLE
        binding.playerView.visibility = View.GONE
        
        // Track recoverable errors to avoid infinite loops
        var recoverableErrorCount = 0
        val maxRecoverableErrors = 5

        // Set error listener - be VERY tolerant for R2 videos with incomplete metadata
        binding.videoView.setOnErrorListener { _, what, extra ->
            Log.e("VideoPlayerActivity", "VideoView error: what=$what, extra=$extra")
            
            val isRecoverableError = when {
                what == -2147483648 && extra == 0 -> true
                what == -38 && extra == 0 -> true
                what == MediaPlayer.MEDIA_ERROR_UNKNOWN && extra == -2147483648 -> true
                else -> false
            }
            
            if (isRecoverableError && recoverableErrorCount < maxRecoverableErrors) {
                recoverableErrorCount++
                Log.w("VideoPlayerActivity", "Ignoring recoverable error #$recoverableErrorCount")
                return@setOnErrorListener true
            }
            
            if (hasError) return@setOnErrorListener true
            hasError = true
            mediaPlayerPrepared = false
            progressRunnable?.let { uiHandler.removeCallbacks(it) }
            binding.loadingSpinner.visibility = View.GONE
            
            if (!isFinishing) {
                Toast.makeText(this, "Error de reproducción ($what)", Toast.LENGTH_SHORT).show()
                finish()
            }
            true
        }

        binding.videoView.setVideoURI(uri)
        setupVideoViewListeners(savedPosition)
    }
    
    private fun setupControlListeners(pathOrUri: String?) {
        // Start with controls hidden
        hideControls(immediate = true)

        // Interaction: swipe for brightness (left), tap for controls
        binding.controlsOverlay.setOnTouchListener { _, event ->
            if (brightnessManager.onTouch(event)) {
                return@setOnTouchListener true
            }
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                if (isControlsVisible) hideControls() else showControls()
                return@setOnTouchListener true
            }
            true
        }

        binding.playPauseOverlay.setOnClickListener {
            if (!mediaPlayerPrepared) return@setOnClickListener
            try {
                if (useExoPlayer) {
                    // ExoPlayer toggle
                    exoPlayer?.let { player ->
                        if (player.isPlaying) player.pause() else player.play()
                    }
                } else {
                    // VideoView toggle
                    val mp = mediaPlayer
                    if (mp != null) {
                        if (mp.isPlaying) mp.pause() else mp.start()
                    } else {
                        if (binding.videoView.isPlaying) binding.videoView.pause() else binding.videoView.start()
                    }
                }
                updatePlayPauseIcon()
                scheduleAutoHide()
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error toggling play/pause", e)
            }
        }

        binding.backButton.setOnClickListener {
            try {
                val i = Intent(this, com.example.tareamov.MainActivity::class.java)
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                i.putExtra("open_video_home", true)
                val currentPos = try { 
                    if (useExoPlayer) exoPlayer?.currentPosition?.toInt() ?: 0 
                    else mediaPlayer?.currentPosition ?: binding.videoView.currentPosition 
                } catch (_: Exception) { 0 }
                i.putExtra("video_position", currentPos)
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
                if (pathOrUri.isNullOrEmpty()) {
                    Toast.makeText(this, "URI de video no disponible para modo flotante", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        pipManager.enterPipMode()
                    } catch (pipEx: Exception) {
                        Log.w("VideoPlayerActivity", "PIP failed, falling back to in-app floating", pipEx)
                        val i = Intent(this, com.example.tareamov.MainActivity::class.java)
                        i.putExtra("floating_video_path", pathOrUri)
                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        startActivity(i)
                        finish()
                    }
                } else {
                    val i = Intent(this, com.example.tareamov.MainActivity::class.java)
                    i.putExtra("floating_video_path", pathOrUri)
                    val currentPos = try { mediaPlayer?.currentPosition ?: binding.videoView.currentPosition } catch (_: Exception) { 0 }
                    i.putExtra("video_position", currentPos)
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
                    pendingUserSeekMs = progress
                    binding.currentTime.text = TimeUtils.formatTime(progress)
                    showControls()
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                cancelAutoHide()
                isScrubbing = true
                showControls()
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                pendingUserSeekMs?.let { target ->
                    try {
                        if (useExoPlayer) {
                            exoPlayer?.seekTo(target.toLong())
                        } else {
                            mediaPlayer?.seekTo(target) ?: binding.videoView.seekTo(target)
                        }
                    } catch (_: Exception) {}
                }
                pendingUserSeekMs = null
                isScrubbing = false
                scheduleAutoHide()
            }
        })
    }
    
    private fun setupVideoViewListeners(savedPosition: Int) {
        binding.videoView.setOnPreparedListener { mp ->
            if (hasError || isFinishing) {
                Log.w("VideoPlayerActivity", "onPrepared called but hasError=$hasError isFinishing=$isFinishing, ignoring")
                return@setOnPreparedListener
            }
            try {
                mediaPlayer = mp
                
                // Get duration - use default if invalid (some R2 videos have incomplete metadata)
                val duration = try { 
                    val d = mp.duration
                    if (d <= 0) {
                        Log.w("VideoPlayerActivity", "Invalid duration: $d - using default 0 (video may still play)")
                        0 // Don't reject the video, just use 0 as duration
                    } else d
                } catch (e: Exception) { 
                    Log.w("VideoPlayerActivity", "Error getting duration, using 0: ${e.message}")
                    0 // Don't reject, try to play anyway
                }
                
                // Only mark as prepared after successful duration check
                mediaPlayerPrepared = true
                mp.isLooping = true
                
                binding.totalTime.text = TimeUtils.formatTime(duration)
                binding.seekBar.max = duration

                // Fix for VideoView duration 0 issue: Poll for duration if it's 0
                if (duration <= 0) {
                    val durationCheckRunnable = object : Runnable {
                        override fun run() {
                            try {
                                if (!mediaPlayerPrepared || hasError || isFinishing) return
                                val currentD = mp.duration
                                if (currentD > 0) {
                                    binding.totalTime.text = TimeUtils.formatTime(currentD)
                                    binding.seekBar.max = currentD
                                    Log.d("VideoPlayerActivity", "VideoView duration updated: $currentD")
                                } else {
                                    uiHandler.postDelayed(this, 500)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    uiHandler.postDelayed(durationCheckRunnable, 500)
                }

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
                    if (binding.videoView.isPlaying) {
                        // Update duration if it changed or was 0
                        val duration = binding.videoView.duration
                        if (duration > 0 && binding.seekBar.max != duration) {
                            binding.seekBar.max = duration
                            binding.totalTime.text = TimeUtils.formatTime(duration)
                        }

                        if (!isScrubbing) {
                            val pos = binding.videoView.currentPosition
                            binding.seekBar.progress = pos
                            binding.currentTime.text = TimeUtils.formatTime(pos)
                        }
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
    
    /**
     * Poll for video duration when it's not immediately available (common with R2 videos)
     * For VP8 videos with corrupt PTS timestamps, we estimate duration from playback position
     */
    private fun pollForDuration(player: ExoPlayer) {
        var pollAttempts = 0
        val maxPollAttempts = 20 // Poll for up to 10 seconds (20 * 500ms)
        
        val durationPoller = object : Runnable {
            override fun run() {
                if (isFinishing || !mediaPlayerPrepared) return
                
                pollAttempts++
                val duration = player.duration
                
                if (duration != androidx.media3.common.C.TIME_UNSET && duration > 0) {
                    // Duration finally available from metadata!
                    isDurationKnown = true
                    val durationInt = duration.toInt()
                    binding.seekBar.max = durationInt
                    binding.totalTime.text = TimeUtils.formatTime(durationInt)
                    Log.d("VideoPlayerActivity", "Duration poll success after $pollAttempts attempts: $durationInt ms")
                } else if (pollAttempts < maxPollAttempts) {
                    // Keep polling, and track max position for estimation
                    val currentPos = player.currentPosition
                    if (currentPos > maxPositionReached) {
                        maxPositionReached = currentPos
                        // Update estimated duration (add buffer for remaining content)
                        if (!isDurationKnown && maxPositionReached > 1000) {
                            estimatedDuration = maxPositionReached + 30000 // Estimate: current + 30 seconds
                            binding.seekBar.max = estimatedDuration.toInt()
                            binding.totalTime.text = "~${TimeUtils.formatTime(maxPositionReached.toInt())}"
                        }
                    }
                    uiHandler.postDelayed(this, 500)
                } else {
                    // Poll exhausted - use estimated duration if available
                    Log.w("VideoPlayerActivity", "Duration poll exhausted after $pollAttempts attempts")
                    if (maxPositionReached > 1000) {
                        // Use max position reached as basis for estimate
                        estimatedDuration = maxPositionReached + 30000
                        binding.seekBar.max = estimatedDuration.toInt()
                        binding.totalTime.text = "~${TimeUtils.formatTime(maxPositionReached.toInt())}"
                        Log.d("VideoPlayerActivity", "Using estimated duration based on max position: $maxPositionReached ms")
                    } else {
                        // Fallback: allow seeking up to 10 minutes
                        val fallbackDuration = 600000 // 10 minutes in ms
                        binding.seekBar.max = fallbackDuration
                        binding.totalTime.text = "??:??"
                    }
                }
            }
        }
        uiHandler.postDelayed(durationPoller, 500)
    }
    
    private fun startExoPlayerProgressUpdater() {
        progressRunnable?.let { uiHandler.removeCallbacks(it) }
        progressRunnable = object : Runnable {
            override fun run() {
                if (isFinishing || !mediaPlayerPrepared || exoPlayer == null) return

                try {
                    val player = exoPlayer ?: return
                    val currentPos = player.currentPosition
                    
                    // Track max position for duration estimation (VP8 videos with corrupt PTS)
                    if (currentPos > maxPositionReached) {
                        maxPositionReached = currentPos
                    }
                    
                    // Update duration if it became available or was 0
                    val durationLong = player.duration
                    if (durationLong != androidx.media3.common.C.TIME_UNSET && durationLong > 0) {
                        isDurationKnown = true
                        val duration = durationLong.toInt()
                        if (binding.seekBar.max != duration && binding.seekBar.max < duration) {
                            binding.seekBar.max = duration
                            binding.totalTime.text = TimeUtils.formatTime(duration)
                            Log.d("VideoPlayerActivity", "Progress updater: Duration updated to $duration ms")
                        }
                    } else if (!isDurationKnown) {
                        // Duration still unknown - use estimation based on max position
                        if (maxPositionReached > 1000) {
                            // Detect when video loops (position suddenly drops significantly)
                            val prevPos = (binding.seekBar.progress).toLong()
                            if (prevPos > 5000 && currentPos < 1000 && prevPos > maxPositionReached - 2000) {
                                // Video looped! Max position is likely the actual duration
                                estimatedDuration = maxPositionReached
                                binding.seekBar.max = estimatedDuration.toInt()
                                binding.totalTime.text = "~${TimeUtils.formatTime(estimatedDuration.toInt())}"
                                Log.d("VideoPlayerActivity", "Video loop detected! Estimated duration: $estimatedDuration ms")
                            } else {
                                // Still playing - update seekBar max to allow seeking ahead
                                val newMax = maxPositionReached + 30000 // Allow seeking 30s ahead
                                if (binding.seekBar.max < newMax) {
                                    binding.seekBar.max = newMax.toInt()
                                    binding.totalTime.text = "~${TimeUtils.formatTime(maxPositionReached.toInt())}"
                                }
                            }
                        }
                    }

                    if (!isScrubbing) {
                        val pos = currentPos.toInt()
                        binding.seekBar.progress = pos
                        binding.currentTime.text = TimeUtils.formatTime(pos)
                    }
                } catch (e: Exception) {
                    Log.w("VideoPlayerActivity", "Error updating ExoPlayer progress: ${e.message}")
                } finally {
                    if (!isFinishing && mediaPlayerPrepared) {
                        uiHandler.postDelayed(this, 250)
                    }
                }
            }
        }
        uiHandler.post(progressRunnable!!)
    }

    private fun setMuted(muted: Boolean) {
        // Update desired mute state immediately
        isMuted = muted
        
        if (mediaPlayerPrepared) {
            try {
                val volume = if (muted) 0f else 1f
                if (useExoPlayer) {
                    exoPlayer?.volume = volume
                } else {
                    mediaPlayer?.setVolume(volume, volume)
                }
                binding.muteButton.setImageResource(if (muted) R.drawable.ic_sound_muted_minimal else R.drawable.ic_sound_minimal)
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error setting volume", e)
            }
        } else {
            // Not prepared yet: volume will be applied when player is ready
            Log.d("VideoPlayerActivity", "Player not prepared yet; saved mute state=$isMuted")
            binding.muteButton.setImageResource(if (muted) R.drawable.ic_sound_muted_minimal else R.drawable.ic_sound_minimal)
        }
    }

    private fun seekBy(deltaMs: Int) {
        if (!mediaPlayerPrepared) {
            Log.d("VideoPlayerActivity", "seekBy: Player not prepared, ignoring seek request")
            return
        }
        try {
            if (useExoPlayer) {
                val player = exoPlayer ?: return
                val duration = player.duration // Long
                val currentPos = player.currentPosition // Long
                
                Log.d("VideoPlayerActivity", "seekBy: deltaMs=$deltaMs, currentPos=$currentPos, duration=$duration")
                
                var newPos = currentPos + deltaMs
                if (newPos < 0) newPos = 0
                
                // Only clamp to duration if duration is known and valid
                val isDurationKnown = duration != androidx.media3.common.C.TIME_UNSET && duration > 0
                if (isDurationKnown && newPos > duration) {
                    newPos = duration
                }
                
                Log.d("VideoPlayerActivity", "seekBy: Seeking to newPos=$newPos")
                
                player.seekTo(newPos)
                binding.currentTime.text = TimeUtils.formatTime(newPos.toInt())
                binding.seekBar.progress = newPos.toInt()
                
                // Update seekBar max if needed
                if (newPos.toInt() > binding.seekBar.max) {
                    binding.seekBar.max = newPos.toInt() + 60000
                }
                
                // Update seekBar if duration is known
                if (isDurationKnown && binding.seekBar.max > 0) {
                    binding.seekBar.progress = newPos.toInt()
                }
            } else {
                val duration = binding.videoView.duration // Can be -1 or 0
                val currentPos = binding.videoView.currentPosition
                
                var newPos = currentPos + deltaMs
                if (newPos < 0) newPos = 0
                
                if (duration > 0 && newPos > duration) {
                    newPos = duration
                }
                
                binding.videoView.seekTo(newPos)
                binding.currentTime.text = TimeUtils.formatTime(newPos)
            }
            showControls()
        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error seeking", e)
        }
    }

    private fun updatePlayPauseIcon() {
        if (!mediaPlayerPrepared) return
        try {
            val isPlaying = if (useExoPlayer) {
                exoPlayer?.isPlaying ?: false
            } else {
                try {
                    mediaPlayer?.isPlaying ?: binding.videoView.isPlaying
                } catch (_: Exception) {
                    binding.videoView.isPlaying
                }
            }
            if (isPlaying) {
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
        // If the activity enters background but is in PIP mode, keep playback as-is
        if (!isInPictureInPictureMode) {
            try {
                if (useExoPlayer) {
                    exoPlayer?.pause()
                } else {
                    val isPlaying = mediaPlayer?.isPlaying ?: binding.videoView.isPlaying
                    if (isPlaying) {
                        mediaPlayer?.pause() ?: binding.videoView.pause()
                    }
                }
            } catch (_: Exception) {}
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
                if (mediaPlayerPrepared) {
                    if (useExoPlayer) {
                        startExoPlayerProgressUpdater()
                    } else if (binding.videoView.isPlaying) {
                        startProgressUpdater()
                    }
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
                val currentPos = if (useExoPlayer) {
                    exoPlayer?.currentPosition?.toInt() ?: 0
                } else {
                    binding.videoView.currentPosition
                }
                outState.putInt("video_position", currentPos)
            }
        } catch (e: Exception) {
            Log.e("VideoPlayerActivity", "Error saving state", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        progressRunnable?.let { uiHandler.removeCallbacks(it) }
        pipManager.unregisterReceiver()
        
        // Release ExoPlayer
        try { 
            exoPlayer?.release()
            exoPlayer = null
        } catch (_: Exception) { }
        
        // Release VideoView/MediaPlayer
        try { 
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (_: Exception) { }
        try { binding.videoView.stopPlayback() } catch (_: Exception) { }
    }
}