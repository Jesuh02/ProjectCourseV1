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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
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
    
    // Duration estimation for videos with corrupt PTS timestamps or missing metadata
    private var maxPositionReached: Long = 0L
    private var estimatedDuration: Long = 0L
    private var isDurationKnown: Boolean = false
    private var contentLengthBytes: Long = 0L // File size from HEAD request
    private var videoBitrate: Long = 0L // Estimated bitrate for duration calculation
    private var lastSeekTime: Long = 0L // Track when user last seeked to avoid false loop detection
    private var lastKnownPlayerPosition: Long = 0L // Track actual player position for loop detection
    private var pendingSeekTarget: Long = -1L // Track pending seek position for recovery
    private var seekRecoveryAttempts: Int = 0 // Track seek recovery attempts
    private val maxSeekRecoveryAttempts = 3 // Max seek recovery attempts
    private var isRecoverySeek: Boolean = false // Flag to ignore SEEK_ADJUSTMENT during recovery
    private var lastRecoverySeekTarget: Long = -1L // Track the target of the last recovery seek to detect failures in STATE_READY
    
    // Store URI and media source factory for reloading video at different positions
    private var currentVideoUri: Uri? = null
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private var progressiveMediaSourceFactory: androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory? = null
    private var seekFailureCount: Int = 0 // Track consecutive seek failures
    private val maxSeekFailures = 1 // After 1 failure, switch to fast-forward method (these videos don't support seeking)
    
    // Fast-forward simulation for videos that don't support seeking
    private var isFastForwarding: Boolean = false
    private var fastForwardTargetPosition: Long = -1L
    private var originalPlaybackSpeed: Float = 1.0f
    private val fastForwardSpeed: Float = 4.0f // 4x speed for fast-forwarding (not too fast for short videos)
    private var fastForwardRunnable: Runnable? = null
    
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

        val videoId = intent.getLongExtra("videoId", -1L)
        
        Log.d("VideoPlayerActivity", "Final processed URI: $uri")

        // Mostrar spinner mientras se carga el video
        binding.loadingSpinner.visibility = View.VISIBLE
        
        // Use ExoPlayer for R2 URLs (more robust with problematic metadata)
        // Use VideoView for local files (simpler)
        val isRemoteUrl = uri.scheme == "http" || uri.scheme == "https"
        useExoPlayer = isRemoteUrl
        
        if (isRemoteUrl && videoId != -1L && username != null) {
            fetchStreamingInfo(username, videoId, uri, savedPosition)
        } else if (useExoPlayer) {
            Log.d("VideoPlayerActivity", "Using ExoPlayer for remote URL (Direct): $uri")
            setupExoPlayer(uri, savedPosition)
        } else {
            Log.d("VideoPlayerActivity", "Using VideoView for local file: $uri")
            setupVideoView(uri, savedPosition)
        }
        
        // Setup all control listeners
        setupControlListeners(pathOrUri)
    }

    private fun fetchStreamingInfo(username: String, videoId: Long, originalUri: Uri, savedPosition: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            val streamUri = withContext(Dispatchers.IO) {
                try {
                    val client = OkHttpClient()
                    val baseUrl = "https://mcp-backenddeploy-production.up.railway.app"
                    val url = "$baseUrl/video/stream-info?username=$username&videoId=$videoId&directUrl=${originalUri}"
                    
                    Log.d("VideoPlayerActivity", "Fetching stream info from: $url")
                    val request = Request.Builder().url(url).build()
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            val json = org.json.JSONObject(body)
                            if (json.optBoolean("success")) {
                                val info = json.getJSONObject("streamingInfo")
                                val newUrl = info.optString("url")
                                Log.d("VideoPlayerActivity", "Backend returned stream URL: $newUrl (Type: ${info.optString("type")})")
                                if (newUrl.isNotEmpty()) Uri.parse(newUrl) else null
                            } else null
                        } else null
                    } else {
                        Log.e("VideoPlayerActivity", "Backend stream info failed: ${response.code}")
                        null
                    }
                } catch (e: Exception) {
                    Log.e("VideoPlayerActivity", "Error fetching stream info", e)
                    null
                }
            }
            
            val finalUri = streamUri ?: originalUri
            
            // Ensure UI is ready
            if (!isFinishing && !isDestroyed) {
                setupExoPlayer(finalUri, savedPosition)
            }
        }
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
            
            // OPTIMIZED BUFFERING: Reduce initial buffer to 500ms for faster start (INSTANT PLAYBACK)
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    3000,  // Low min buffer
                    50000, // Max buffer
                    500,   // Buffer to start playback (500ms = Quick start)
                    1000   // Min buffer to resume (1s)
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
            
            // Create tolerant extractors for problematic videos (VP8, VP9, WebM, etc.)
            // This enables seeking in videos without proper keyframe indexes
            @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
            val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
                .setConstantBitrateSeekingEnabled(true)
                .setConstantBitrateSeekingAlwaysEnabled(true)
                .setMp4ExtractorFlags(androidx.media3.extractor.mp4.Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
            
            // Create DataSource factory for network requests
            @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this)
            
            // Create ProgressiveMediaSource directly with extractors for proper CBR seeking
            // This ensures the extractors are actually used (unlike DefaultMediaSourceFactory)
            // Store factory and URI for potential reload-based seeking
            @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
            val mediaSourceFactory = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                dataSourceFactory,
                extractorsFactory
            )
            this.progressiveMediaSourceFactory = mediaSourceFactory
            this.currentVideoUri = uri
            
            // Create ExoPlayer instance with custom config
            // Use DEFAULT seek parameters - more lenient than CLOSEST_SYNC for problematic videos
            exoPlayer = ExoPlayer.Builder(this, renderersFactory)
                .setLoadControl(loadControl)
                .setHandleAudioBecomingNoisy(false) // Don't pause on audio focus loss
                .setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
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
                
                // Create ProgressiveMediaSource with CBR seeking extractors
                // This is critical for videos without proper keyframe indexes
                @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(uri))
                player.setMediaSource(mediaSource)
                
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
                                Log.d("VideoPlayerActivity", "ExoPlayer: STATE_READY at position ${player.currentPosition}")
                                mediaPlayerPrepared = true
                                errorRecoveryAttempts = 0 // Reset on successful playback
                                binding.loadingSpinner.visibility = View.GONE
                                
                                // Check if seek failed and we need to recover
                                val currentPos = player.currentPosition
                                val timeSinceSeek = System.currentTimeMillis() - lastSeekTime
                                
                                // IMPROVED: Detect when the seek target was beyond the actual video duration
                                // If we just had a SEEK_ADJUSTMENT to 0 and loop detection fired,
                                // the seek didn't fail per se - the target was just past the end
                                val targetWasPastEnd = pendingSeekTarget > 0 && 
                                    isDurationKnown && 
                                    estimatedDuration > 0 && 
                                    pendingSeekTarget > estimatedDuration
                                
                                if (targetWasPastEnd && currentPos < 2000) {
                                    // User tried to seek past the end - not a failure, just past end
                                    Log.d("VideoPlayerActivity", "⏭️ Seek target was past video end. Target=$pendingSeekTarget, Duration=$estimatedDuration")
                                    seekRecoveryAttempts = 0
                                    pendingSeekTarget = -1L
                                    // Don't increment seekFailureCount - this is expected behavior
                                    
                                    uiHandler.post {
                                        android.widget.Toast.makeText(
                                            this@VideoPlayerActivity,
                                            "Fin del video",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else if ((pendingSeekTarget > 2000 || lastRecoverySeekTarget > 2000) && currentPos < 1000 && timeSinceSeek < 10000) {
                                    // Seek likely failed - video is at start but we wanted to be further
                                    // Also check lastRecoverySeekTarget for cases where recovery "appeared" to succeed but actually failed
                                    val targetPos = if (pendingSeekTarget > 0) pendingSeekTarget else lastRecoverySeekTarget
                                    Log.w("VideoPlayerActivity", "⚠️ Seek FAILED! Target=$targetPos, Current=$currentPos, seekFailures=$seekFailureCount, pendingTarget=$pendingSeekTarget, lastRecoveryTarget=$lastRecoverySeekTarget")
                                    
                                    seekFailureCount++
                                    lastRecoverySeekTarget = -1L // Clear after use
                                    
                                    // ALWAYS trigger fast-forward on first seek failure for these problematic videos
                                    // Don't waste time with multiple recovery attempts - they won't work
                                    Log.w("VideoPlayerActivity", "❌ Seek failed (count=$seekFailureCount) - this video doesn't support native seeking, using fast-forward")
                                    seekRecoveryAttempts = 0
                                    pendingSeekTarget = -1L
                                    
                                    // Trigger fast-forward simulation immediately
                                    if (targetPos > currentPos) {
                                        uiHandler.post {
                                            Toast.makeText(this@VideoPlayerActivity, "Adelantando video...", Toast.LENGTH_SHORT).show()
                                            startFastForward(targetPos)
                                        }
                                    }
                                } else if ((pendingSeekTarget > 0 && currentPos >= pendingSeekTarget - 2000) || 
                                           (lastRecoverySeekTarget > 0 && currentPos >= lastRecoverySeekTarget - 2000)) {
                                    // Seek actually succeeded! Reset failure counter
                                    val actualTarget = if (pendingSeekTarget > 0) pendingSeekTarget else lastRecoverySeekTarget
                                    Log.d("VideoPlayerActivity", "✅ Seek ACTUALLY succeeded! Target=$actualTarget, Current=$currentPos")
                                    seekFailureCount = 0
                                    seekRecoveryAttempts = 0
                                    pendingSeekTarget = -1L
                                    lastRecoverySeekTarget = -1L
                                } else {
                                    // No pending seek or position is acceptable
                                    Log.d("VideoPlayerActivity", "No pending seek to verify. pendingTarget=$pendingSeekTarget, lastRecoveryTarget=$lastRecoverySeekTarget, currentPos=$currentPos")
                                    seekRecoveryAttempts = 0
                                    if (lastRecoverySeekTarget <= 0) {
                                        pendingSeekTarget = -1L
                                    }
                                    lastRecoverySeekTarget = -1L
                                }
                                
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
                                    
                                    // Fetch video metadata via HEAD request to estimate duration from file size
                                    fetchVideoMetadata(uri)
                                    
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
                                // Player reached end - capture the actual duration!
                                val finalPosition = player.currentPosition
                                if (finalPosition > maxPositionReached) {
                                    maxPositionReached = finalPosition
                                }
                                
                                if (!isDurationKnown && maxPositionReached > 0) {
                                    // This is the ACTUAL duration since video reached end
                                    estimatedDuration = maxPositionReached
                                    isDurationKnown = true // Now we know for sure!
                                    binding.seekBar.max = estimatedDuration.toInt()
                                    binding.totalTime.text = TimeUtils.formatTime(estimatedDuration.toInt())
                                    Log.d("VideoPlayerActivity", "🎯 STATE_ENDED: ACTUAL duration = $estimatedDuration ms")
                                    
                                    // Calculate bitrate if we have file size
                                    if (contentLengthBytes > 0) {
                                        videoBitrate = (contentLengthBytes * 8 * 1000) / estimatedDuration
                                        Log.d("VideoPlayerActivity", "Calculated bitrate from end: $videoBitrate bps")
                                    }
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
                    
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int
                    ) {
                        // Log seek operations to debug seek issues
                        val reasonStr = when(reason) {
                            Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                            Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                            Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                            Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                            Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                            Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                            else -> "UNKNOWN($reason)"
                        }
                        Log.d("VideoPlayerActivity", "Position discontinuity: $reasonStr, " +
                                "old=${oldPosition.positionMs}ms -> new=${newPosition.positionMs}ms" +
                                ", isRecoverySeek=$isRecoverySeek")
                        
                        // If this is a recovery seek that "appears" to succeed, DON'T clear pendingSeekTarget yet
                        // Wait for STATE_READY to confirm the actual position before declaring success
                        if (isRecoverySeek && reason == Player.DISCONTINUITY_REASON_SEEK && newPosition.positionMs > 1000) {
                            Log.d("VideoPlayerActivity", "🔄 Recovery seek initiated to ${newPosition.positionMs}ms - waiting for STATE_READY to confirm")
                            // Store the target so we can verify in STATE_READY
                            lastRecoverySeekTarget = pendingSeekTarget
                            // Mark the time to ignore SEEK_ADJUSTMENT for a short window
                            lastSeekTime = 0 // Reset to prevent re-triggering recovery
                            isRecoverySeek = false
                            // DON'T clear pendingSeekTarget - we need it in STATE_READY
                            return
                        }
                        
                        // If seek adjustment happens during recovery, ignore it
                        if (isRecoverySeek && reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                            Log.d("VideoPlayerActivity", "⏩ Ignoring SEEK_ADJUSTMENT during recovery seek")
                            return
                        }
                        
                        // CRITICAL: Detect video loop via AUTO_TRANSITION
                        // This happens when video naturally loops (REPEAT_MODE_ONE)
                        // The oldPosition contains the actual duration!
                        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && 
                            oldPosition.positionMs > 5000 && 
                            newPosition.positionMs < 1000 &&
                            !isDurationKnown) {
                            
                            val actualDuration = oldPosition.positionMs
                            Log.d("VideoPlayerActivity", "🎯 AUTO_TRANSITION loop! ACTUAL duration: $actualDuration ms")
                            isDurationKnown = true
                            estimatedDuration = actualDuration
                            maxPositionReached = actualDuration
                            
                            uiHandler.post {
                                binding.seekBar.max = actualDuration.toInt()
                                binding.totalTime.text = TimeUtils.formatTime(actualDuration.toInt())
                            }
                            
                            // Calculate bitrate if we have file size
                            if (contentLengthBytes > 0 && actualDuration > 0) {
                                videoBitrate = (contentLengthBytes * 8 * 1000) / actualDuration
                                Log.d("VideoPlayerActivity", "Calculated bitrate: $videoBitrate bps")
                            }
                            return
                        }
                        
                        // If seek adjustment happens and jumps to near 0, the video doesn't support seeking
                        // IMMEDIATELY trigger fast-forward instead of wasting time with recovery attempts
                        val timeSinceLastSeek = System.currentTimeMillis() - lastSeekTime
                        val isSeekFailure = reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT && 
                            timeSinceLastSeek < 2000 &&
                            newPosition.positionMs < 1000 &&
                            (oldPosition.positionMs > 2000 || pendingSeekTarget > 2000)

                        if (isSeekFailure) {
                            
                            val targetPosition = when {
                                pendingSeekTarget > 0 -> pendingSeekTarget
                                oldPosition.positionMs > 1000 -> oldPosition.positionMs
                                else -> lastKnownPlayerPosition
                            }
                            
                            // If we already know the actual duration, don't try to seek past it
                            if (isDurationKnown && estimatedDuration > 0 && targetPosition > estimatedDuration) {
                                Log.d("VideoPlayerActivity", "⏭️ Target $targetPosition exceeds known duration $estimatedDuration - showing end message")
                                pendingSeekTarget = -1L
                                lastRecoverySeekTarget = -1L
                                seekRecoveryAttempts = 0
                                uiHandler.post {
                                    Toast.makeText(
                                        this@VideoPlayerActivity,
                                        "Fin del video",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                return
                            }
                            
                            // CRITICAL: This video DOES NOT support native seeking
                            // Don't waste time with recovery attempts - go directly to fast-forward
                            seekFailureCount++
                            Log.w("VideoPlayerActivity", "⚠️ SEEK_ADJUSTMENT detected! Seek failed (count=$seekFailureCount). " +
                                    "Target=${targetPosition}ms -> Adjusted to ${newPosition.positionMs}ms. Using fast-forward!")
                            
                            pendingSeekTarget = -1L
                            lastRecoverySeekTarget = -1L
                            seekRecoveryAttempts = 0
                            
                            // Trigger fast-forward immediately for forward seeks
                            val currentPos = newPosition.positionMs
                            if (targetPosition > currentPos + 1000) {
                                uiHandler.post {
                                    Toast.makeText(this@VideoPlayerActivity, "Adelantando video...", Toast.LENGTH_SHORT).show()
                                    startFastForward(targetPosition)
                                }
                            }
                        }
                        // Note: Don't reset pendingSeekTarget on initial SEEK event because
                        // SEEK_ADJUSTMENT may follow and we need the target for recovery
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
                // Stop fast-forwarding if user manually interacts
                if (isFastForwarding) {
                    stopFastForward()
                }
                
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
                        // Mark that user is seeking to prevent false loop detection
                        lastSeekTime = System.currentTimeMillis()
                        seekRecoveryAttempts = 0 // Reset recovery attempts for new seek
                        // Store target position to help recovery if seek fails
                        lastKnownPlayerPosition = target.toLong()
                        pendingSeekTarget = target.toLong() // Track for recovery after buffering
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
                
                // If duration is already known (from player, loop detection, or file size), stop polling
                if (isDurationKnown) {
                    Log.d("VideoPlayerActivity", "Duration already known ($estimatedDuration ms), stopping poll")
                    return
                }
                
                pollAttempts++
                val duration = player.duration
                
                if (duration != androidx.media3.common.C.TIME_UNSET && duration > 0) {
                    // Duration finally available from metadata!
                    isDurationKnown = true
                    estimatedDuration = duration
                    val durationInt = duration.toInt()
                    binding.seekBar.max = durationInt
                    binding.totalTime.text = TimeUtils.formatTime(durationInt)
                    Log.d("VideoPlayerActivity", "✅ Duration poll success after $pollAttempts attempts: $durationInt ms")
                } else if (pollAttempts < maxPollAttempts) {
                    // Keep polling, and track max position for estimation
                    val currentPos = player.currentPosition
                    if (currentPos > maxPositionReached) {
                        maxPositionReached = currentPos
                    }
                    
                    // Update seekBar max to allow seeking ahead, but don't constantly update text
                    // if we already have a file-size based estimate
                    if (maxPositionReached > 1000) {
                        val newMax = if (contentLengthBytes > 0 && estimatedDuration > maxPositionReached) {
                            estimatedDuration.toInt()
                        } else {
                            (maxPositionReached + 30000).toInt()
                        }
                        
                        if (binding.seekBar.max < newMax) {
                            binding.seekBar.max = newMax
                        }
                        
                        // Only update text if we don't have a file-size based estimate yet
                        if (estimatedDuration <= 0 || contentLengthBytes <= 0) {
                            binding.totalTime.text = "~${TimeUtils.formatTime(maxPositionReached.toInt())}"
                        }
                    }
                    uiHandler.postDelayed(this, 500)
                } else {
                    // Poll exhausted - keep current estimate or use fallback
                    Log.w("VideoPlayerActivity", "Duration poll exhausted after $pollAttempts attempts")
                    
                    if (estimatedDuration > 0) {
                        // We have an estimate from file size, keep it
                        Log.d("VideoPlayerActivity", "Keeping file-size estimate: $estimatedDuration ms")
                    } else if (maxPositionReached > 1000) {
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
                    
                    // Use actual player position history for loop detection, NOT seekBar progress
                    val prevPlayerPos = lastKnownPlayerPosition
                    lastKnownPlayerPosition = currentPos
                    
                    // Track max position for duration estimation (VP8 videos with corrupt PTS)
                    if (currentPos > maxPositionReached) {
                        maxPositionReached = currentPos
                    }
                    
                    // Check if user recently seeked (within last 2 seconds) - ignore loop detection
                    val timeSinceLastSeek = System.currentTimeMillis() - lastSeekTime
                    val userRecentlySeeked = timeSinceLastSeek < 2000
                    
                    // IMPORTANT: If duration is already known, don't try to update it again
                    // This prevents overwriting a good duration with a bad estimate
                    if (!isDurationKnown) {
                        // Try to get duration from player
                        val durationLong = player.duration
                        if (durationLong != androidx.media3.common.C.TIME_UNSET && durationLong > 0) {
                            // Player finally reported duration!
                            isDurationKnown = true
                            estimatedDuration = durationLong
                            val duration = durationLong.toInt()
                            binding.seekBar.max = duration
                            binding.totalTime.text = TimeUtils.formatTime(duration)
                            Log.d("VideoPlayerActivity", "✅ Progress updater: Duration from player = $duration ms")
                        } else if (!userRecentlySeeked && !isScrubbing) {
                            // Duration still unknown - use estimation based on max position or file size
                            // BUT ONLY if user hasn't recently seeked (to avoid false positives)
                            
                            // IMPROVED LOOP DETECTION: Video looped when position suddenly drops
                            // This happens when player.repeatMode is REPEAT_MODE_ONE and video ends
                            // Use actual player position history, not seekBar progress
                            val positionDropped = prevPlayerPos > 5000 && currentPos < 2000
                            val wasNearMaxPosition = prevPlayerPos >= maxPositionReached - 2000
                            val significantProgress = maxPositionReached > 5000
                            
                            if (positionDropped && wasNearMaxPosition && significantProgress) {
                                // Video looped! Max position is the actual duration
                                val actualDuration = maxPositionReached
                                estimatedDuration = actualDuration
                                isDurationKnown = true // Now we know the real duration!
                                binding.seekBar.max = actualDuration.toInt()
                                binding.totalTime.text = TimeUtils.formatTime(actualDuration.toInt())
                                Log.d("VideoPlayerActivity", "🎯 Video loop detected! ACTUAL duration: $actualDuration ms (prev=$prevPlayerPos, cur=$currentPos)")
                                
                                // Also refine bitrate estimate if we have file size
                                if (contentLengthBytes > 0) {
                                    videoBitrate = (contentLengthBytes * 8 * 1000) / actualDuration
                                    Log.d("VideoPlayerActivity", "Calculated bitrate: $videoBitrate bps (${videoBitrate / 1000} kbps)")
                                }
                            } else if (maxPositionReached > 1000) {
                                // Still playing - update seekBar max based on best estimate
                                val newMax = if (contentLengthBytes > 0 && estimatedDuration > maxPositionReached) {
                                    // Use file-size based estimate if it's larger than current position
                                    estimatedDuration.toInt()
                                } else {
                                    // Otherwise allow seeking 30s ahead of current max
                                    (maxPositionReached + 30000).toInt()
                                }
                                
                                if (binding.seekBar.max < newMax) {
                                    binding.seekBar.max = newMax
                                    // Only update text if we have a file-size based estimate
                                    if (contentLengthBytes > 0 && estimatedDuration > 0) {
                                        binding.totalTime.text = "~${TimeUtils.formatTime(estimatedDuration.toInt())}"
                                    } else {
                                        binding.totalTime.text = "~${TimeUtils.formatTime(maxPositionReached.toInt())}"
                                    }
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
        
        // Mark that user is seeking to prevent false loop detection
        lastSeekTime = System.currentTimeMillis()
        seekRecoveryAttempts = 0 // Reset recovery attempts for new seek
        
        try {
            if (useExoPlayer) {
                val player = exoPlayer ?: return
                val playerDuration = player.duration // Long
                val currentPos = player.currentPosition // Long
                
                // Use the best available duration estimate (in priority order):
                // 1. Player duration if known (most reliable)
                // 2. ACTUAL duration from loop detection (when isDurationKnown=true)
                // 3. maxPositionReached + small buffer (more reliable than file-size estimate)
                // 4. File-size based estimate (allows forward seeking but may be inaccurate)
                val effectiveDuration = when {
                    playerDuration != androidx.media3.common.C.TIME_UNSET && playerDuration > 0 -> playerDuration
                    isDurationKnown && estimatedDuration > 0 -> estimatedDuration // Actual detected duration
                    // If we've played some of the video, use max position + buffer
                    maxPositionReached > 5000 -> maxPositionReached + 5000L
                    // Use file-size based estimate as fallback
                    estimatedDuration > 0 -> estimatedDuration
                    else -> -1L // Unknown - will not clamp
                }
                
                Log.d("VideoPlayerActivity", "seekBy: deltaMs=$deltaMs, currentPos=$currentPos, " +
                        "playerDuration=$playerDuration, effectiveDuration=$effectiveDuration, " +
                        "isDurationKnown=$isDurationKnown, estimatedDuration=$estimatedDuration, maxReached=$maxPositionReached, seekFailures=$seekFailureCount")
                
                var newPos = currentPos + deltaMs
                if (newPos < 0) newPos = 0
                
                // Clamp to effective duration if we have a reliable duration
                // Only strictly clamp if isDurationKnown (actual duration detected) or player reports duration
                val shouldStrictClamp = isDurationKnown || 
                    (playerDuration != androidx.media3.common.C.TIME_UNSET && playerDuration > 0)
                
                if (effectiveDuration > 0 && newPos > effectiveDuration) {
                    if (shouldStrictClamp) {
                        // We know the real duration - clamp and notify user
                        val clampedPos = (effectiveDuration - 500).coerceAtLeast(0L)
                        Log.d("VideoPlayerActivity", "seekBy: Clamped $newPos to $clampedPos (actual duration=$effectiveDuration)")
                        if (newPos - effectiveDuration > 2000) {
                            // User tried to seek significantly beyond end
                            Toast.makeText(this, "Fin del video", Toast.LENGTH_SHORT).show()
                        }
                        newPos = clampedPos
                    } else {
                        // Duration is estimated - allow seeking but cap at estimate
                        val clampedPos = (effectiveDuration - 1000).coerceAtLeast(0L)
                        Log.d("VideoPlayerActivity", "seekBy: Soft clamp $newPos to $clampedPos (estimated duration)")
                        newPos = clampedPos
                    }
                }
                
                // If the new position equals or is very close to current, nothing to do
                if (kotlin.math.abs(newPos - currentPos) < 500) {
                    Log.d("VideoPlayerActivity", "seekBy: Already at target position")
                    showControls()
                    return
                }
                
                // CRITICAL: Check if this video has proven to not support seeking
                // If seekFailureCount >= maxSeekFailures, use fast-forward simulation for forward seeks
                val isForwardSeek = deltaMs > 0
                val videoDoesNotSupportSeeking = seekFailureCount >= maxSeekFailures
                
                if (isForwardSeek && videoDoesNotSupportSeeking) {
                    Log.d("VideoPlayerActivity", "seekBy: Using fast-forward simulation (seekFailureCount=$seekFailureCount >= $maxSeekFailures)")
                    Toast.makeText(this, "Adelantando video...", Toast.LENGTH_SHORT).show()
                    startFastForward(newPos)
                    showControls()
                    return
                }
                
                Log.d("VideoPlayerActivity", "seekBy: Attempting native seek to newPos=$newPos")
                
                // Store target position before seeking to help recovery if seek fails
                lastKnownPlayerPosition = newPos
                pendingSeekTarget = newPos // Track for recovery after buffering
                
                player.seekTo(newPos)
                binding.currentTime.text = TimeUtils.formatTime(newPos.toInt())
                binding.seekBar.progress = newPos.toInt()
                
                // Update seekBar max based on effective duration
                if (effectiveDuration > 0) {
                    binding.seekBar.max = effectiveDuration.toInt()
                } else if (newPos.toInt() > binding.seekBar.max) {
                    binding.seekBar.max = newPos.toInt() + 60000
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
    
    /**
     * Start fast-forwarding to target position using increased playback speed.
     * This is a workaround for videos that don't support native seeking.
     */
    private fun startFastForward(targetPosition: Long) {
        val player = exoPlayer ?: return
        
        // Cancel any existing fast-forward
        stopFastForward()
        
        val currentPos = player.currentPosition
        if (targetPosition <= currentPos) {
            Log.d("VideoPlayerActivity", "startFastForward: Target $targetPosition is not ahead of current $currentPos")
            return
        }
        
        // If we know the actual duration and target exceeds it, clamp to end
        val effectiveTarget = if (isDurationKnown && estimatedDuration > 0 && targetPosition > estimatedDuration) {
            Log.d("VideoPlayerActivity", "startFastForward: Clamping target $targetPosition to duration $estimatedDuration")
            (estimatedDuration - 500).coerceAtLeast(currentPos + 1000)
        } else {
            targetPosition
        }
        
        // Store original speed and mark as fast-forwarding
        originalPlaybackSpeed = player.playbackParameters.speed
        isFastForwarding = true
        fastForwardTargetPosition = effectiveTarget
        
        val secondsToFastForward = (effectiveTarget - currentPos) / 1000
        Log.d("VideoPlayerActivity", "startFastForward: From $currentPos to $effectiveTarget (~${secondsToFastForward}s at ${fastForwardSpeed}x)")
        
        // Ensure video is playing
        if (!player.isPlaying) {
            player.play()
        }
        
        // Increase playback speed
        player.setPlaybackSpeed(fastForwardSpeed)
        
        // Track last known position to detect loops
        var lastCheckedPosition = currentPos
        var stuckCounter = 0
        
        // Start monitoring position to stop at target
        fastForwardRunnable = object : Runnable {
            override fun run() {
                val player = exoPlayer ?: return
                val pos = player.currentPosition
                
                // Detect if video looped (position went backwards significantly)
                if (pos < lastCheckedPosition - 1000) {
                    // Video looped - we've reached the end!
                    Log.d("VideoPlayerActivity", "startFastForward: Video looped at $lastCheckedPosition, stopping")
                    stopFastForward()
                    Toast.makeText(this@VideoPlayerActivity, "Fin del video", Toast.LENGTH_SHORT).show()
                    return
                }
                
                // Detect if position is stuck (video ended or buffering issue)
                if (kotlin.math.abs(pos - lastCheckedPosition) < 50) {
                    stuckCounter++
                    if (stuckCounter > 20) { // 2 seconds stuck
                        Log.d("VideoPlayerActivity", "startFastForward: Position stuck at $pos, stopping")
                        stopFastForward()
                        return
                    }
                } else {
                    stuckCounter = 0
                }
                lastCheckedPosition = pos
                
                if (pos >= fastForwardTargetPosition - 500 || !isFastForwarding) {
                    // Reached target or was cancelled
                    stopFastForward()
                    Log.d("VideoPlayerActivity", "startFastForward: Reached target at $pos")
                } else {
                    // Update UI while fast-forwarding
                    binding.currentTime.text = TimeUtils.formatTime(pos.toInt())
                    binding.seekBar.progress = pos.toInt()
                    
                    // Track max position for duration estimation
                    if (pos > maxPositionReached) {
                        maxPositionReached = pos
                    }
                    
                    // Check again in 100ms
                    uiHandler.postDelayed(this, 100)
                }
            }
        }
        uiHandler.postDelayed(fastForwardRunnable!!, 100)
    }
    
    /**
     * Stop fast-forwarding and restore normal playback speed.
     */
    private fun stopFastForward() {
        if (!isFastForwarding) return
        
        val player = exoPlayer
        isFastForwarding = false
        fastForwardTargetPosition = -1L
        
        // Cancel the monitoring runnable
        fastForwardRunnable?.let { uiHandler.removeCallbacks(it) }
        fastForwardRunnable = null
        
        // Restore original playback speed
        player?.setPlaybackSpeed(originalPlaybackSpeed)
        
        Log.d("VideoPlayerActivity", "stopFastForward: Restored playback speed to $originalPlaybackSpeed")
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
        fastForwardRunnable?.let { uiHandler.removeCallbacks(it) }
        stopFastForward()
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
    
    /**
     * Fetch video metadata via HEAD request to get Content-Length for duration estimation.
     * This is useful for videos that don't have proper duration metadata in their headers.
     */
    private fun fetchVideoMetadata(uri: Uri) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = uri.toString()
                Log.d("VideoPlayerActivity", "Fetching video metadata for: $url")
                
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
                
                val request = Request.Builder()
                    .url(url)
                    .head() // HEAD request - only gets headers, not the full video
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0L
                    val contentType = response.header("Content-Type") ?: ""
                    
                    Log.d("VideoPlayerActivity", "Video metadata: Content-Length=$contentLength, Content-Type=$contentType")
                    
                    if (contentLength > 0) {
                        contentLengthBytes = contentLength
                        
                        // Estimate duration based on typical video bitrates
                        // MP4 videos from R2 typically have bitrate between 1-5 Mbps
                        // Conservative estimate: 2 Mbps = 250 KB/s
                        val estimatedBytesPerSecond = when {
                            contentType.contains("webm") -> 200_000L  // WebM is typically more compressed
                            contentType.contains("mp4") -> 250_000L  // MP4 average
                            else -> 300_000L // Conservative fallback
                        }
                        
                        val estimatedDurationMs = (contentLength * 1000) / estimatedBytesPerSecond
                        
                        withContext(Dispatchers.Main) {
                            if (!isDurationKnown && estimatedDurationMs > 1000) {
                                estimatedDuration = estimatedDurationMs
                                binding.seekBar.max = estimatedDurationMs.toInt()
                                binding.totalTime.text = "~${TimeUtils.formatTime(estimatedDurationMs.toInt())}"
                                Log.d("VideoPlayerActivity", "Estimated duration from file size: ${estimatedDurationMs}ms (${contentLength} bytes)")
                            }
                        }
                    }
                } else {
                    Log.w("VideoPlayerActivity", "HEAD request failed: ${response.code}")
                }
                
                response.close()
            } catch (e: Exception) {
                Log.e("VideoPlayerActivity", "Error fetching video metadata", e)
            }
        }
    }
    
    /**
     * Refine duration estimate based on actual playback bitrate.
     * Call this after some playback time to get a more accurate estimate.
     */
    private fun refineDurationEstimate(currentPosition: Long, bufferedBytes: Long) {
        if (contentLengthBytes <= 0 || currentPosition <= 5000) return
        
        // Calculate actual bitrate based on playback
        // bufferedBytes / currentPosition gives bytes per millisecond
        val actualBytesPerMs = if (currentPosition > 0 && bufferedBytes > 0) {
            bufferedBytes.toDouble() / currentPosition.toDouble()
        } else {
            0.0
        }
        
        if (actualBytesPerMs > 0) {
            val refinedDuration = (contentLengthBytes / actualBytesPerMs).toLong()
            if (refinedDuration > currentPosition && refinedDuration > 0) {
                estimatedDuration = refinedDuration
                videoBitrate = (actualBytesPerMs * 8000).toLong() // Convert to bits per second
                
                if (!isDurationKnown) {
                    binding.seekBar.max = refinedDuration.toInt()
                    binding.totalTime.text = "~${TimeUtils.formatTime(refinedDuration.toInt())}"
                    Log.d("VideoPlayerActivity", "Refined duration estimate: ${refinedDuration}ms (bitrate: ${videoBitrate} bps)")
                }
            }
        }
    }
}