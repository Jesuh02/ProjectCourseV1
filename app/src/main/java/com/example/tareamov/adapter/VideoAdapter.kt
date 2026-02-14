package com.example.tareamov.adapter

import android.media.MediaPlayer // Added for MediaPlayer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import android.widget.VideoView
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs // Use kotlin.math.abs to avoid ambiguity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Adaptador para mostrar videos en un ViewPager2 con estilo.
 * Uses ExoPlayer with aggressive disk-caching and pre-loading
 * for INSTANT video playback when scrolling.
 *
 * Performance strategy:
 *  1. [VideoCacheManager] pre-fetches first 2 MB of adjacent videos to disk.
 *  2. [VideoPreloader] batch-signs URLs in a single request.
 *  3. ExoPlayer is configured with tiny min-buffer (500 ms) so playback
 *     starts from cache almost instantly.
 *  4. Thumbnails are shown with 0-alpha crossfade the moment the first frame
 *     is decoded.
 */
@UnstableApi
class VideoAdapter(
    private var videos: List<VideoData>,
    private val onProfileClick: ((String) -> Unit)? = null,
    private val onUsernameClick: ((VideoData) -> Unit)? = null,
    private val onSubscribeToggle: ((Long, Boolean) -> Unit)? = null,
    private val checkSubscriptionStatus: (suspend (Long) -> Boolean)? = null,
    private val onLikeToggle: ((VideoData, Boolean) -> Unit)? = null,
    private val onCommentClick: ((VideoData) -> Unit)? = null,
    private val checkUserLikedVideo: (suspend (Long) -> Boolean)? = null,
    private val getLikeCount: (suspend (Long) -> Int)? = null,
    private val getCommentCount: (suspend (Long) -> Int)? = null
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private var currentUserId: Long = -1L
    private val pendingSeeks = mutableMapOf<String, Int>()

    /** Optional preloader to resolve pre-signed URLs instantly */
    var videoPreloader: com.example.tareamov.util.VideoPreloader? = null
    
    // Track the currently active (playing with audio) video position
    private var currentActivePosition: Int = -1
    
    /**
     * Set the active position - only this position will have audio enabled
     */
    fun setActivePosition(position: Int) {
        currentActivePosition = position
        Log.d("VideoAdapter", "Active position set to: $position")
    }
    
    /**
     * Check if a position is the currently active one
     */
    fun isActivePosition(position: Int): Boolean {
        return position == currentActivePosition
    }

    fun setPendingSeek(path: String, position: Int) {
        pendingSeeks[path] = position
    }

    fun setCurrentUserId(userId: Long) {
        this.currentUserId = userId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun getItemCount(): Int = videos.size

    /**
     * Actualiza la lista de videos y notifica al adaptador
     */
    fun updateVideos(newVideos: List<VideoData>) {
        videos = newVideos
        notifyDataSetChanged()
    }

    /**
     * ViewHolder para mostrar un video individual
     */    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val videoView: VideoView = itemView.findViewById(R.id.videoView)
        private val thumbnailView: ImageView? = itemView.findViewById(R.id.thumbnailView)
        private val usernameText: TextView = itemView.findViewById(R.id.usernameText)
        private val descriptionText: TextView = itemView.findViewById(R.id.videoDescription)
        private val titleText: TextView = itemView.findViewById(R.id.gameTitle)
        private val loadingProgressBar: android.widget.ProgressBar? = itemView.findViewById(R.id.loadingProgressBar)
        private val profileButton: de.hdodenhof.circleimageview.CircleImageView = itemView.findViewById(R.id.profileButton)
        private val playPauseOverlay: android.widget.ImageView? = itemView.findViewById(R.id.playPauseOverlay)
        private val fullscreenButtonContainer: android.widget.LinearLayout? = itemView.findViewById(R.id.fullscreenButtonContainer)
        private val fullscreenButton: android.widget.ImageView? = itemView.findViewById(R.id.fullscreenButton)
        private val likeButton: android.widget.ImageView? = itemView.findViewById(R.id.likeButton)
        private val shareButton: android.widget.ImageView? = itemView.findViewById(R.id.shareButton)
        private val soundButton: android.widget.ImageView? = itemView.findViewById(R.id.soundButton)
        
        // New UI elements for professional design
        private val subscribeButton: android.widget.ImageView? = itemView.findViewById(R.id.subscribeButton)
        private val commentButton: android.widget.ImageView? = itemView.findViewById(R.id.commentButton)
        private val likeCountText: TextView? = itemView.findViewById(R.id.likeCountText)
        private val commentCountText: TextView? = itemView.findViewById(R.id.commentCountText)
        private val followLabel: TextView? = itemView.findViewById(R.id.followLabel)
        private val audioText: TextView? = itemView.findViewById(R.id.audioText)
        private val premiumBadge: TextView? = itemView.findViewById(R.id.premiumBadge)
        
        private var currentJob: Job? = null
        // Managed scope for all coroutines in this ViewHolder — cancelled on rebind
        private var viewHolderScope: CoroutineScope? = null
        private var mediaPlayer: MediaPlayer? = null
        private var mediaPlayerPrepared: Boolean = false
        private var isVideoPaused = false
        private var isLiked = false
        private var isMuted = false
        private var isSubscribed = false
        private var overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var overlayRunnable: Runnable? = null
        private var currentCreatorId: Long = -1L
        private var currentVideoData: VideoData? = null
        
        // ExoPlayer for reliable looping on remote URLs
        private var exoPlayer: ExoPlayer? = null
        private val playerView: PlayerView? = itemView.findViewById(R.id.playerView)
        private var useExoPlayer: Boolean = false
        
        // Track current video URI to prevent re-binding same video
        private var currentBoundVideoUri: String? = null
        private var isVideoSetup: Boolean = false

        fun bind(videoData: VideoData) {
            // Check if we're binding the same video - if so, skip re-setup
            val newVideoUri = videoData.getBestVideoUri()?.toString()
            if (currentBoundVideoUri == newVideoUri && isVideoSetup && currentVideoData?.id == videoData.id) {
                Log.d("VideoAdapter", "Skipping re-bind for same video: ${videoData.title}")
                return
            }
            
            // Cancel all previous coroutines from the old bind
            viewHolderScope?.cancel()
            viewHolderScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            
            currentVideoData = videoData
            currentBoundVideoUri = newVideoUri
            isVideoSetup = false
            descriptionText.text = videoData.description
            titleText.text = videoData.title
            
            // Show premium badge optimistically from local data
            premiumBadge?.visibility = if (videoData.isPaid) View.VISIBLE else View.GONE

            // Reset views and states
            videoView.visibility = View.VISIBLE
            loadingProgressBar?.visibility = View.GONE
            playPauseOverlay?.visibility = View.GONE
            isVideoPaused = false
            isMuted = false
            isSubscribed = false
            isLiked = false

            // Reset button states
            updateSoundButton()
            updateSubscribeButton()

            // Initialize counts
            likeCountText?.text = "0"
            commentCountText?.text = "0"

            // OPTIMIZATION: Show thumbnail IMMEDIATELY for instant perceived load
            thumbnailView?.visibility = View.VISIBLE
            thumbnailView?.alpha = 1f
            if (!videoData.thumbnailUri.isNullOrEmpty()) {
                Glide.with(itemView)
                    .load(videoData.thumbnailUri)
                    .placeholder(android.R.color.black)
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                    .priority(com.bumptech.glide.Priority.IMMEDIATE)
                    .override(1080, 1920)
                    .format(com.bumptech.glide.load.DecodeFormat.PREFER_RGB_565)
                    .into(thumbnailView!!)
            } else {
                thumbnailView?.setImageResource(android.R.color.black)
            }

            // Setup button listeners (synchronous, no network)
            setupButtonListeners()

            // ── METADATA: single coroutine, ALL API calls in parallel ─────
            // Eliminates duplicate calls (was 9-12 calls, now max 4 unique).
            // Uses async for parallel execution so metadata loads in ~1 RTT.
            currentJob?.cancel()
            profileButton.setImageResource(R.drawable.ic_profile)
            usernameText.text = videoData.username ?: "usuario"
            currentCreatorId = -1L

            val userId = currentUserId
            currentJob = viewHolderScope?.launch {
                try {
                    // ── Fire ALL independent API calls in parallel ──
                    val courseDeferred = if (videoData.courseId != null && videoData.courseId!! > 0) {
                        async(Dispatchers.IO) {
                            try { BackendApiService.getCourseById(videoData.courseId!!).getOrNull() }
                            catch (_: CancellationException) { throw CancellationException() }
                            catch (_: Exception) { null }
                        }
                    } else null

                    val likeStateDeferred = if (userId > 0) {
                        async(Dispatchers.IO) {
                            try { checkUserLikedVideo?.invoke(videoData.id) ?: false }
                            catch (_: CancellationException) { throw CancellationException() }
                            catch (_: Exception) { false }
                        }
                    } else null

                    val likeCountDeferred = async(Dispatchers.IO) {
                        try { getLikeCount?.invoke(videoData.id) ?: 0 }
                        catch (_: CancellationException) { throw CancellationException() }
                        catch (_: Exception) { 0 }
                    }

                    val commentCountDeferred = async(Dispatchers.IO) {
                        try { getCommentCount?.invoke(videoData.id) ?: 0 }
                        catch (_: CancellationException) { throw CancellationException() }
                        catch (_: Exception) { 0 }
                    }

                    // ── Await likes & comments first (fast, no dependencies) ──
                    val likeCount = likeCountDeferred.await()
                    val commentCount = commentCountDeferred.await()
                    val liked = likeStateDeferred?.await() ?: false

                    likeCountText?.text = formatCount(likeCount)
                    commentCountText?.text = formatCount(commentCount)
                    commentButton?.isActivated = commentCount > 0
                    isLiked = liked
                    updateLikeButton()
                    Log.d("VideoAdapter", "Video ${videoData.id}: liked=$isLiked, likes=$likeCount, comments=$commentCount")

                    // ── Await course (needed for creatorId, premium badge) ──
                    val course = courseDeferred?.await()
                    if (course != null) {
                        currentCreatorId = course.creatorUserId
                        val isPremium = course.isPremium == true || (course.price ?: 0.0) > 0
                        premiumBadge?.visibility = if (isPremium) View.VISIBLE else View.GONE
                    }

                    // ── Resolve creator user (username + avatar) — single call ──
                    val creatorUser: com.example.tareamov.data.entity.Usuario? = withContext(Dispatchers.IO) {
                        try {
                            // Priority 1: from course.creatorUserId
                            if (currentCreatorId > 0) {
                                val result = BackendApiService.getUserById(currentCreatorId).getOrNull()
                                if (result != null) return@withContext result
                            }
                            // Priority 2: from videoData.remoteId
                            if (videoData.remoteId != null && videoData.remoteId!! > 0) {
                                currentCreatorId = videoData.remoteId!!
                                val result = BackendApiService.getUserById(videoData.remoteId!!).getOrNull()
                                if (result != null) return@withContext result
                            }
                            // Priority 3: from username string
                            val fallbackName = videoData.username
                            if (!fallbackName.isNullOrEmpty()) {
                                val result = BackendApiService.getUserByUsername(fallbackName).getOrNull()
                                if (result != null) {
                                    currentCreatorId = result.id
                                    return@withContext result
                                }
                            }
                            null
                        } catch (_: CancellationException) { throw CancellationException() }
                        catch (_: Exception) { null }
                    }

                    // ── Apply resolved user data to UI ──
                    val resolvedUsername = creatorUser?.usuario ?: videoData.username ?: "usuario"
                    usernameText.text = resolvedUsername
                    audioText?.text = "Original Audio - $resolvedUsername"

                    profileButton.setOnClickListener { onProfileClick?.invoke(resolvedUsername) }
                    usernameText.setOnClickListener { onUsernameClick?.invoke(videoData) }
                    titleText.setOnClickListener { onUsernameClick?.invoke(videoData) }

                    // Avatar
                    if (creatorUser != null && !creatorUser.avatar.isNullOrEmpty()) {
                        Glide.with(itemView)
                            .load(creatorUser.avatar)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                            .override(200, 200)
                            .circleCrop()
                            .into(profileButton)
                    }

                    // Subscription status (needs creatorId resolved above)
                    if (currentCreatorId > 0 && checkSubscriptionStatus != null) {
                        isSubscribed = checkSubscriptionStatus.invoke(currentCreatorId)
                        updateSubscribeButton()
                    }

                } catch (_: CancellationException) {
                    // ViewHolder recycled — silently cancel
                } catch (e: Exception) {
                    Log.w("VideoAdapter", "Metadata load error for video ${videoData.id}", e)
                    profileButton.setImageResource(R.drawable.ic_profile)
                }
            }

            // Setup video playback
            // OPTIMIZATION: Resolve video URL with tiered strategy for instant loading.
            // Priority:
            //  1. VideoPreloader signed URL cache (instant, no network)
            //  2. HTTP URL already in VideoData (from streaming endpoint, already signed)
            //  3. StorageHelper fallback (last resort, individual signing)
            viewHolderScope?.launch {
                val context = itemView.context.applicationContext
                var bestUri: Uri? = null

                // Tier 1: VideoPreloader cache (instant — no coroutine switch needed)
                val preSigned = videoPreloader?.getPreSignedUrl(videoData.id)
                if (preSigned != null) {
                    bestUri = Uri.parse(preSigned)
                    Log.d("VideoAdapter", "⚡ Instant URL from VideoPreloader cache")
                }

                // Tier 2: HTTP URL already in the model (streaming endpoint returned pre-signed)
                if (bestUri == null) {
                    val modelUrl = videoData.videoUriString
                    if (modelUrl != null && modelUrl.startsWith("http")) {
                        bestUri = Uri.parse(modelUrl)
                        Log.d("VideoAdapter", "⚡ Using pre-signed HTTP URL from model")
                    }
                }

                // Tier 3: getBestVideoUri may resolve to a local file or R2 fallback
                if (bestUri == null) {
                    bestUri = videoData.getBestVideoUri()
                }

                // Tier 4: If we still have a non-HTTP URI (relative R2 key), sign it
                if (bestUri != null) {
                    val uriStr = bestUri.toString()
                    val needsSigning = !uriStr.startsWith("http") && !uriStr.startsWith("file") && !uriStr.startsWith("content")
                    val isUnsignedR2 = uriStr.startsWith("http") && com.example.tareamov.service.StorageHelper.isR2Url(uriStr) && !uriStr.contains("X-Amz-Signature")

                    if (needsSigning || isUnsignedR2) {
                        val signedUrl = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.StorageHelper.getVideoStreamUrl(context, uriStr)
                        }
                        if (signedUrl != null) {
                            bestUri = Uri.parse(signedUrl)
                            Log.d("VideoAdapter", "✅ URL signed via StorageHelper")
                        }
                    }
                } else if (!videoData.videoUriString.isNullOrEmpty()) {
                    // Last resort: try to sign the raw videoUriString
                    val signedUrl = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.StorageHelper.getVideoStreamUrl(context, videoData.videoUriString)
                    }
                    if (signedUrl != null) {
                        bestUri = Uri.parse(signedUrl)
                        Log.d("VideoAdapter", "✅ URL signed via fallback for: ${videoData.videoUriString}")
                    }
                }

                if (bestUri != null) {
                    try {
                        Log.d("VideoAdapter", "Setting video URI: $bestUri (scheme: ${bestUri!!.scheme})")
                        
                        // Determine if we should use ExoPlayer (remote URLs) or VideoView (local files)
                        val isRemoteUrl = bestUri!!.scheme == "http" || bestUri!!.scheme == "https"
                        useExoPlayer = isRemoteUrl
                        
                        val safeUri = bestUri!!
                        
                        if (useExoPlayer) {
                            setupExoPlayer(safeUri)
                        } else {
                            setupVideoView(safeUri)
                        }
                    } catch (e: Exception) {
                        Log.e("VideoAdapter", "Error setting video URI", e)
                        loadingProgressBar?.visibility = View.GONE
                    }
                } else {
                    Log.e("VideoAdapter", "No valid video URI. videoUriString='${videoData.videoUriString}', localFilePath='${videoData.localFilePath}'")
                    loadingProgressBar?.visibility = View.GONE
                }
            }
        }
        
        /**
         * Setup ExoPlayer for remote URLs - more reliable looping
         */
        private fun setupExoPlayer(uri: Uri) {
            // Show ExoPlayer view, hide VideoView
            playerView?.visibility = View.VISIBLE
            videoView.visibility = View.GONE
            
            // Release any existing player
            releaseExoPlayer()
            
            // Detect emulator for audio handling
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
                Log.w("VideoAdapter", "Running on emulator - starting muted to avoid audio issues")
                isMuted = true
                updateSoundButton()
            }
            
            try {
                val appContext = itemView.context.applicationContext

                // Initialize video cache with application context
                com.example.tareamov.util.VideoCacheManager.initialize(appContext)

                // AGGRESSIVE LoadControl for INSTANT playback:
                //  - minBufferMs   = 500  → start preparing with only 500 ms buffered
                //  - maxBufferMs   = 15000 → buffer up to 15 s for smooth playback
                //  - bufferForPlaybackMs = 100  → start playing after only 100 ms  
                //  - bufferForPlaybackAfterRebufferMs = 500 → quick recovery after stall
                // With VideoCacheManager pre-fetching 2 MB, the first 100 ms is
                // already on disk → effectively zero network wait.
                val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(500, 15_000, 100, 500)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()

                exoPlayer = ExoPlayer.Builder(appContext)
                    .setLoadControl(loadControl)
                    .setHandleAudioBecomingNoisy(false)
                    .setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC) // Tolerant seek for problematic videos
                    .build().also { player ->
                    playerView?.player = player
                    
                    // Set audio attributes
                    val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build()
                    player.setAudioAttributes(audioAttributes, false)
                    
                    // Check if this is the active position - if not, start muted
                    val position = bindingAdapterPosition
                    val isActive = isActivePosition(position)
                    
                    // Set volume - MUTE if not active position OR if user has muted
                    player.volume = if (!isActive || isMuted) 0f else 1f
                    Log.d("VideoAdapter", "ExoPlayer setup: position=$position, isActive=$isActive, volume=${player.volume}")
                    
                    // Use cached MediaSource for INSTANT loading
                    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
                    val mediaSource = com.example.tareamov.util.VideoCacheManager.createCachedMediaSource(appContext, uri.toString())
                    player.setMediaSource(mediaSource)
                    
                    // CRITICAL: Enable seamless looping
                    player.repeatMode = Player.REPEAT_MODE_ONE
                    
                    // IMPORTANT: Do NOT auto-play here - let onViewAttachedToWindow control playback
                    // This prevents double video start (bind + attach both trying to play)
                    player.playWhenReady = false
                    
                    // Prepare player IMMEDIATELY for instant start
                    player.prepare()
                    
                    // Mark video as setup
                    isVideoSetup = true
                    
                    // Track error recovery
                    var errorRecoveryAttempts = 0
                    val maxErrorRecoveryAttempts = 5
                    
                    // Add playback listener
                    player.addListener(object : Player.Listener {
                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e("VideoAdapter", "ExoPlayer error: ${error.message}, code=${error.errorCode}", error)
                            
                            // 1. Check for 401 Unauthorized
                            val cause = error.cause
                            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException && cause.responseCode == 401) {
                                Log.e("VideoAdapter", "401 Unauthorized - Token expired or missing. Stopping playback retry.")
                                errorRecoveryAttempts = maxErrorRecoveryAttempts // Stop retries
                                isVideoPaused = true
                                loadingProgressBar?.visibility = View.GONE
                                
                                // Optional: Reset player logic or notify user?
                                // For now, we just stop the loop.
                                return
                            }

                            // 2. Check for Audio/Emulator errors
                            val isAudioError = error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                                    error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                                    error.message?.contains("audio", ignoreCase = true) == true ||
                                    error.message?.contains("pcm", ignoreCase = true) == true

                            if ((isAudioError || isEmulator) && errorRecoveryAttempts < maxErrorRecoveryAttempts) {
                                errorRecoveryAttempts++
                                Log.w("VideoAdapter", "Audio error detected, attempting recovery #$errorRecoveryAttempts")
                                try {
                                    player.volume = 0f
                                    isMuted = true
                                    updateSoundButton()
                                    
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        try {
                                            player.prepare()
                                            player.playWhenReady = isActivePosition(bindingAdapterPosition)
                                        } catch (_: Exception) {}
                                    }, 200)
                                } catch (e: Exception) {
                                    Log.e("VideoAdapter", "Recovery failed", e)
                                }
                            } else if (errorRecoveryAttempts < maxErrorRecoveryAttempts) {
                                // 3. Generic recovery
                                errorRecoveryAttempts++
                                Log.d("VideoAdapter", "Retrying video playback... attempt $errorRecoveryAttempts")
                                player.prepare()
                                player.playWhenReady = isActivePosition(bindingAdapterPosition)
                            } else {
                                Log.e("VideoAdapter", "Max recovery attempts reached, showing error")
                                loadingProgressBar?.visibility = View.GONE
                            }
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            when (playbackState) {
                                Player.STATE_READY -> {
                                    Log.d("VideoAdapter", "ExoPlayer: STATE_READY - looping enabled")
                                    mediaPlayerPrepared = true
                                    errorRecoveryAttempts = 0
                                    loadingProgressBar?.visibility = View.GONE
                                    // OPTIMIZATION: INSTANT thumbnail hide for seamless transition
                                    // Using 50ms fade for smooth but fast transition
                                    thumbnailView?.animate()?.alpha(0f)?.setDuration(50)?.withEndAction {
                                        thumbnailView?.visibility = View.GONE
                                    }?.start()
                                    
                                    // Check for pending seek
                                    val uriString = uri.toString()
                                    val pathString = uri.path
                                    val seekPos = pendingSeeks[uriString] ?: (if (pathString != null) pendingSeeks[pathString] else null)
                                    if (seekPos != null && seekPos > 0) {
                                        player.seekTo(seekPos.toLong())
                                        pendingSeeks.remove(uriString)
                                        if (pathString != null) pendingSeeks.remove(pathString)
                                        Log.d("VideoAdapter", "ExoPlayer: Restored position to $seekPos ms")
                                    }
                                    
                                    // ExoPlayer uses playWhenReady to control auto-play
                                    // If playWhenReady is true (set by playVideo()), it will start automatically
                                    // If false (initial state or set by pauseVideo()), it stays paused
                                    val currentPosition = bindingAdapterPosition
                                    Log.d("VideoAdapter", "ExoPlayer READY: position=$currentPosition, isActive=${isActivePosition(currentPosition)}, playWhenReady=${player.playWhenReady}, isPlaying=${player.isPlaying}")
                                    
                                    // If playWhenReady is true but not playing yet, ensure proper volume
                                    if (player.playWhenReady && !player.isPlaying) {
                                        player.volume = if (isMuted) 0f else 1f
                                        Log.d("VideoAdapter", "ExoPlayer will start with volume=${player.volume}")
                                    }
                                }
                                Player.STATE_BUFFERING -> {
                                    Log.d("VideoAdapter", "ExoPlayer: STATE_BUFFERING")
                                    // Only show loading if thumbnail is hidden (video was playing)
                                    if (thumbnailView?.visibility == View.GONE) {
                                        loadingProgressBar?.visibility = View.VISIBLE
                                    }
                                }
                                Player.STATE_ENDED -> {
                                    // This shouldn't happen with REPEAT_MODE_ONE, but just in case
                                    Log.d("VideoAdapter", "ExoPlayer: STATE_ENDED (backup restart)")
                                    player.seekTo(0)
                                    player.play()
                                }
                                Player.STATE_IDLE -> {
                                    Log.d("VideoAdapter", "ExoPlayer: STATE_IDLE")
                                }
                            }
                        }
                        
                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            // Dynamically adjust resize mode based on video orientation
                            val videoWidth = videoSize.width
                            val videoHeight = videoSize.height
                            
                            if (videoWidth > 0 && videoHeight > 0) {
                                val isVertical = videoHeight > videoWidth
                                
                                if (isVertical) {
                                    // Vertical video: FIXED_HEIGHT to fill screen height responsively
                                    // This shows the complete video scaled to fill the height
                                    playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT
                                    Log.d("VideoAdapter", "Vertical video detected (${videoWidth}x${videoHeight}), using FIXED_HEIGHT mode")
                                } else {
                                    // Horizontal video: FIT to show complete video (letterbox)
                                    playerView?.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                    Log.d("VideoAdapter", "Horizontal video detected (${videoWidth}x${videoHeight}), using FIT mode")
                                }
                            }
                        }
                        
                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            Log.d("VideoAdapter", "ExoPlayer: isPlaying=$isPlaying")
                        }

                        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                            val duration = player.duration
                            if (duration != androidx.media3.common.C.TIME_UNSET) {
                                Log.d("VideoAdapter", "ExoPlayer duration updated: $duration")
                            }
                        }
                    })
                    
                    // Player is already prepared above for instant start
                }
                
                // Set click listener on PlayerView for pause/play
                playerView?.setOnClickListener {
                    togglePlayPause()
                }
                
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error setting up ExoPlayer, falling back to VideoView", e)
                useExoPlayer = false
                setupVideoView(uri)
            }
        }
        
        /**
         * Setup VideoView for local files
         */
        private fun setupVideoView(bestUri: Uri) {
            // Show VideoView, hide ExoPlayer
            videoView.visibility = View.VISIBLE
            playerView?.visibility = View.GONE
            
            // Release ExoPlayer if it was used
            releaseExoPlayer()
            
            // Check if it's a local file and handle accordingly
            if (bestUri.scheme == "file") {
                val file = File(bestUri.path ?: "")
                if (!file.exists()) {
                    Log.w("VideoAdapter", "Local video file does not exist: ${bestUri.path}, trying R2 signed URL fallback")
                    val fileName = bestUri.path?.substringAfterLast("/")
                    if (!fileName.isNullOrEmpty()) {
                        // Use signed URL via coroutine instead of public URL
                        val objectKey = "videos/$fileName"
                        Log.d("VideoAdapter", "Resolving signed URL for R2 fallback: $objectKey")
                        viewHolderScope?.launch {
                            val context = itemView.context.applicationContext
                            val signedUrl = withContext(Dispatchers.IO) {
                                com.example.tareamov.service.StorageHelper.getVideoStreamUrl(context, objectKey)
                            }
                            if (signedUrl != null) {
                                useExoPlayer = true
                                setupExoPlayer(Uri.parse(signedUrl))
                            } else {
                                Log.e("VideoAdapter", "R2 signed URL fallback failed for: $objectKey")
                            }
                        }
                        return
                    } else {
                        Log.e("VideoAdapter", "Video not available and no fallback: $fileName")
                        return
                    }
                } else {
                    videoView.setVideoURI(bestUri)
                }
            } else {
                videoView.setVideoURI(bestUri)
            }

            videoView.setOnPreparedListener { mp ->
                loadingProgressBar?.visibility = View.GONE
                this.mediaPlayer = mp
                mediaPlayerPrepared = true
                
                // OPTIMIZATION: INSTANT thumbnail hide for seamless transition
                thumbnailView?.animate()?.alpha(0f)?.setDuration(50)?.withEndAction {
                    thumbnailView?.visibility = View.GONE
                }?.start()
                
                // Get video duration
                val duration = try {
                    val d = mp.duration
                    if (d <= 0 || d == Int.MIN_VALUE) -1 else d
                } catch (e: Exception) { -1 }
                Log.d("VideoAdapter", "VideoView prepared, duration: $duration ms")
                
                // Check for pending seek
                val uriString = bestUri.toString()
                val pathString = bestUri.path
                val seekPos = pendingSeeks[uriString] ?: (if (pathString != null) pendingSeeks[pathString] else null)
                if (seekPos != null && seekPos > 0) {
                    mp.seekTo(seekPos)
                    pendingSeeks.remove(uriString)
                    if (pathString != null) pendingSeeks.remove(pathString)
                }

                // Configure video sizing
                val videoWidth = mp.videoWidth
                val videoHeight = mp.videoHeight
                if (videoWidth > 0 && videoHeight > 0) {
                    val parentWidth = (videoView.parent as View).width
                    val parentHeight = (videoView.parent as View).height
                    val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
                    val screenRatio = parentWidth.toFloat() / parentHeight.toFloat()
                    val params = videoView.layoutParams
                    
                    if (videoWidth > videoHeight) {
                        params.width = parentWidth
                        params.height = (parentWidth / videoRatio).toInt()
                    } else {
                        if (videoRatio > screenRatio) {
                            params.height = parentHeight
                            params.width = (parentHeight * videoRatio).toInt()
                        } else {
                            params.width = parentWidth
                            params.height = (parentWidth / videoRatio).toInt()
                        }
                    }
                    videoView.layoutParams = params
                }
                
                // Check if this is the active position - if not, start muted
                val position = bindingAdapterPosition
                val isActive = isActivePosition(position)
                
                // Set volume - MUTE if not active position OR if user has muted
                val volume = if (!isActive || isMuted) 0f else 1f
                mp.setVolume(volume, volume)
                Log.d("VideoAdapter", "VideoView setup: position=$position, isActive=$isActive, volume=$volume")
                
                // Enable native looping
                mp.isLooping = true
                Log.d("VideoAdapter", "VideoView native looping enabled")
                
                // Buffering listener
                mp.setOnInfoListener { _, what, _ ->
                    when (what) {
                        MediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                            loadingProgressBar?.visibility = View.VISIBLE
                        }
                        MediaPlayer.MEDIA_INFO_BUFFERING_END, 
                        MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                            loadingProgressBar?.visibility = View.GONE
                        }
                    }
                    true
                }
                
                // Completion listener as backup
                mp.setOnCompletionListener { player ->
                    Log.d("VideoAdapter", "VideoView onCompletion (backup)")
                    Handler(Looper.getMainLooper()).post {
                        try {
                            if (mediaPlayerPrepared && !isVideoPaused) {
                                player.seekTo(0)
                                player.start()
                            }
                        } catch (e: Exception) {
                            Log.e("VideoAdapter", "Error in completion restart", e)
                        }
                    }
                }
                
                // IMPORTANT: Do NOT auto-start here unconditionally - let onViewAttachedToWindow control playback
                // However, if this is the active position and view is attached, start now
                // This handles the case where onViewAttachedToWindow was called before video was prepared
                Log.d("VideoAdapter", "VideoView prepared for position $position")
                
                // Mark video as setup
                isVideoSetup = true
                
                // Start playback if this is the active position and not paused
                if (isActivePosition(position) && !isVideoPaused) {
                    val volume = if (isMuted) 0f else 1f
                    mp.setVolume(volume, volume)
                    videoView.start()
                    Log.d("VideoAdapter", "VideoView auto-started for active position $position")
                }
            }

            // Error listener
            var recoverableErrorCount = 0
            val maxRecoverableErrors = 10
            var lastErrorTime = 0L
            
            videoView.setOnErrorListener { _, what, extra ->
                Log.e("VideoAdapter", "VideoView error: what=$what, extra=$extra")
                
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastErrorTime > 5000) {
                    recoverableErrorCount = 0
                }
                lastErrorTime = currentTime
                
                val isRecoverableError = when {
                    what == -2147483648 && extra == 0 -> true
                    what == -38 && extra == 0 -> true
                    what == MediaPlayer.MEDIA_ERROR_UNKNOWN && extra == -2147483648 -> true
                    what == MediaPlayer.MEDIA_ERROR_UNKNOWN && extra == 0 -> true
                    what == 1 && extra == -2147483648 -> true
                    else -> false
                }
                
                if (isRecoverableError && recoverableErrorCount < maxRecoverableErrors) {
                    recoverableErrorCount++
                    Log.w("VideoAdapter", "Recoverable error #$recoverableErrorCount, continuing")
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            if (!isVideoPaused) {
                                videoView.seekTo(0)
                                videoView.start()
                            }
                        } catch (_: Exception) {}
                    }, 100)
                    
                    return@setOnErrorListener true
                }
                
                Log.e("VideoAdapter", "Non-recoverable error")
                mediaPlayerPrepared = false
                loadingProgressBar?.visibility = View.GONE
                true
            }

            videoView.setOnClickListener {
                togglePlayPause()
            }
        }
        
        /**
         * Release ExoPlayer resources
         */
        private fun releaseExoPlayer() {
            try {
                // Clear the PlayerView's reference first to avoid leaking the view/activity
                try { playerView?.player = null } catch (_: Exception) {}
                exoPlayer?.release()
                exoPlayer = null
            } catch (_: Exception) {}
        }
        
        /**
         * Pausa la reproducción del video y silencia el audio INMEDIATAMENTE
         */
        fun pauseVideo() {
            isVideoPaused = true
            Log.d("VideoAdapter", "pauseVideo called for position ${bindingAdapterPosition}")
            
            if (useExoPlayer) {
                try {
                    exoPlayer?.let { player ->
                        // CRITICAL: Mute FIRST, then pause to prevent audio leak
                        player.volume = 0f
                        // Set playWhenReady to false to prevent auto-play when ready
                        player.playWhenReady = false
                        player.pause()
                    }
                    Log.d("VideoAdapter", "ExoPlayer paused and muted")
                } catch (e: Exception) {
                    Log.e("VideoAdapter", "Error pausing ExoPlayer", e)
                }
            } else {
                try {
                    // CRITICAL: Mute FIRST, then pause
                    mediaPlayer?.setVolume(0f, 0f)
                    if (videoView.isPlaying) {
                        videoView.pause()
                    }
                    Log.d("VideoAdapter", "VideoView paused and muted")
                } catch (e: Exception) {
                    Log.e("VideoAdapter", "Error pausing VideoView", e)
                }
            }
        }
        
        /**
         * Stops and releases all video resources
         */
        fun releasePlayer() {
            isVideoPaused = true
            viewHolderScope?.cancel()
            viewHolderScope = null
            currentJob?.cancel()
            
            // Release ExoPlayer
            try {
                try { playerView?.player = null } catch (_: Exception) {}
                exoPlayer?.stop()
                exoPlayer?.release()
                exoPlayer = null
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error releasing ExoPlayer", e)
            }
            
            // Release VideoView/MediaPlayer
            try {
                if (videoView.isPlaying) {
                    videoView.stopPlayback()
                }
                mediaPlayer?.setVolume(0f, 0f)
                mediaPlayer?.stop()
                mediaPlayer?.release()
                mediaPlayer = null
                mediaPlayerPrepared = false
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error releasing VideoView/MediaPlayer", e)
            }
        }
        
        /**
         * Inicia la reproducción del video
         * Solo reproduce con audio si esta es la posición activa
         * Idempotent - safe to call multiple times, won't restart if already playing
         */
        fun playVideo() {
            try {
                isVideoPaused = false
                val position = bindingAdapterPosition
                Log.d("VideoAdapter", "playVideo called for position $position, useExoPlayer=$useExoPlayer")
                
                if (useExoPlayer) {
                    exoPlayer?.let { player ->
                        // Restore volume based on mute state
                        player.volume = if (isMuted) 0f else 1f
                        
                        // Check if already playing to avoid restarting
                        if (player.isPlaying) {
                            Log.d("VideoAdapter", "ExoPlayer already playing for position $position, skipping")
                            return
                        }
                        
                        // Set playWhenReady to true - this will start playback when ready
                        // This handles both cases: player ready now OR player preparing
                        player.playWhenReady = true
                        
                        // If player is in READY state but not playing, explicitly start
                        if (player.playbackState == Player.STATE_READY) {
                            player.play()
                            Log.d("VideoAdapter", "ExoPlayer started (was READY) for position $position")
                        } else {
                            Log.d("VideoAdapter", "ExoPlayer will auto-play when ready (state=${player.playbackState}) for position $position")
                        }
                    } ?: run {
                        Log.w("VideoAdapter", "ExoPlayer is null for position $position")
                    }
                } else {
                    // Check if MediaPlayer is prepared
                    if (!mediaPlayerPrepared) {
                        Log.d("VideoAdapter", "VideoView not prepared yet for position $position, skipping playVideo")
                        return
                    }
                    // Check if already playing to avoid restart
                    if (videoView.isPlaying) {
                        Log.d("VideoAdapter", "VideoView already playing for position $position, skipping")
                        // Just ensure volume is correct
                        val volume = if (isMuted) 0f else 1f
                        mediaPlayer?.setVolume(volume, volume)
                        return
                    }
                    // Restore volume based on mute state
                    val volume = if (isMuted) 0f else 1f
                    mediaPlayer?.setVolume(volume, volume)
                    videoView.start()
                    Log.d("VideoAdapter", "VideoView started for position $position")
                }
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error playing video", e)
            }
        }
        
        /**
         * Sets the mute state of the video.
         */
        fun setMuteState(mute: Boolean) {
            isMuted = mute
            val volume = if (isMuted) 0f else 1f
            
            if (useExoPlayer) {
                try {
                    exoPlayer?.volume = volume
                } catch (e: Exception) {
                    Log.e("VideoAdapter", "Error setting ExoPlayer volume", e)
                }
            } else if (mediaPlayerPrepared) {
                try {
                    mediaPlayer?.setVolume(volume, volume)
                } catch (e: IllegalStateException) {
                    Log.e("VideoAdapter", "Error setting volume, MediaPlayer might not be ready.", e)
                    mediaPlayerPrepared = false
                }
            } else {
                Log.d("VideoAdapter", "Player not prepared yet; saved mute state=$isMuted")
            }
        }

        /**
         * Toggles between play and pause state of the video
         */
        private fun togglePlayPause() {
            try {
                val isPlaying = if (useExoPlayer) {
                    exoPlayer?.isPlaying ?: false
                } else {
                    videoView.isPlaying
                }
                
                if (isPlaying) {
                    if (useExoPlayer) {
                        exoPlayer?.pause()
                    } else {
                        videoView.pause()
                    }
                    isVideoPaused = true
                    showPlayPauseOverlay(R.drawable.ic_play_overlay)
                    showFullscreenButtonTemporarily()
                    Log.d("VideoAdapter", "Video paused by user tap")
                } else {
                    if (useExoPlayer) {
                        exoPlayer?.play()
                    } else {
                        videoView.start()
                    }
                    isVideoPaused = false
                    showPlayPauseOverlay(R.drawable.ic_pause_overlay)
                    Log.d("VideoAdapter", "Video resumed by user tap")
                }
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error toggling play/pause", e)
            }
        }

        private fun showPlayPauseOverlay(iconRes: Int) {
            playPauseOverlay?.let { overlay ->
                overlay.setImageResource(iconRes)
                overlay.visibility = View.VISIBLE
                overlay.alpha = 1.0f // Increased alpha for better visibility with background
                
                // Remove any existing callback
                overlayRunnable?.let { overlayHandler.removeCallbacks(it) }
                
                // Hide overlay after 1.5 seconds
                overlayRunnable = Runnable {
                    overlay.visibility = View.GONE
                }
                overlayHandler.postDelayed(overlayRunnable!!, 1500)
            }
        }

        /**
         * Muestra el botón de pantalla completa transparente durante 2 segundos al pausar
         */
        private fun showFullscreenButtonTemporarily() {
            fullscreenButtonContainer?.let { container ->
                // Hacer el contenedor semi-transparente y visible
                container.alpha = 0.0f
                container.visibility = View.VISIBLE
                container.animate()
                    .alpha(0.9f)
                    .setDuration(200)
                    .start()
                
                // Ocultar después de 2 segundos
                overlayHandler.postDelayed({
                    container.animate()
                        .alpha(0.0f)
                        .setDuration(300)
                        .withEndAction {
                            container.visibility = View.GONE
                        }
                        .start()
                }, 2000)
            }
        }

        private fun setupButtonListeners() {
            // Like button with protection against multiple clicks
            likeButton?.setOnClickListener {
                // Prevent multiple rapid clicks
                if (likeButton.tag == "processing") {
                    Log.d("VideoAdapter", "Like button click ignored - already processing")
                    return@setOnClickListener
                }
                
                // Mark as processing
                likeButton.tag = "processing"
                likeButton.isEnabled = false
                
                val newLikeState = !isLiked
                val previousLikeState = isLiked
                
                Log.d("VideoAdapter", "===== LIKE BUTTON CLICKED =====")
                Log.d("VideoAdapter", "Video ID: ${currentVideoData?.id}")
                Log.d("VideoAdapter", "Previous state: $previousLikeState → New state: $newLikeState")
                
                // Update UI optimistically
                isLiked = newLikeState
                updateLikeButton()
                
                // Update like count locally
                val currentCount = likeCountText?.text?.toString()?.let { 
                    parseCount(it) 
                } ?: 0
                val newCount = if (isLiked) currentCount + 1 else maxOf(0, currentCount - 1)
                likeCountText?.text = formatCount(newCount)
                
                Log.d("VideoAdapter", "Like count: $currentCount → $newCount")
                
                // Animate the like button
                likeButton.animate()
                    .scaleX(1.3f)
                    .scaleY(1.3f)
                    .setDuration(100)
                    .withEndAction {
                        likeButton.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
                
                // Sync with database in background
                currentVideoData?.let { videoData ->
                    viewHolderScope?.launch {
                        try {
                            Log.d("VideoAdapter", "Calling onLikeToggle callback for video ${videoData.id}")
                            // Call the toggle callback
                            onLikeToggle?.invoke(videoData, newLikeState)
                            
                            // Verify the like was actually saved by checking the database
                            delay(200) // Increased delay to ensure DB write completes
                            
                            if (checkUserLikedVideo != null) {
                                val actualLikeState = checkUserLikedVideo.invoke(videoData.id)
                                
                                Log.d("VideoAdapter", "Verification check: Expected=$newLikeState, Actual=$actualLikeState")
                                
                                // If the actual state doesn't match what we tried to set, revert
                                if (actualLikeState != newLikeState) {
                                    Log.w("VideoAdapter", "⚠️ LIKE STATE MISMATCH! Reverting. Expected: $newLikeState, Actual: $actualLikeState")
                                    isLiked = actualLikeState
                                    updateLikeButton()
                                    
                                    // Revert count
                                    val revertedCount = if (actualLikeState) currentCount + 1 else maxOf(0, currentCount - 1)
                                    likeCountText?.text = formatCount(revertedCount)
                                } else {
                                    Log.d("VideoAdapter", "✅ Like state verified and persisted correctly")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("VideoAdapter", "❌ Error toggling like, reverting state", e)
                            // Revert on error
                            isLiked = previousLikeState
                            updateLikeButton()
                            likeCountText?.text = formatCount(currentCount)
                            
                            android.widget.Toast.makeText(
                                itemView.context, 
                                "Error al actualizar like", 
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } finally {
                            // Re-enable button
                            likeButton?.tag = null
                            likeButton?.isEnabled = true
                            Log.d("VideoAdapter", "===== LIKE OPERATION COMPLETE =====")
                        }
                    }
                } ?: run {
                    // No video data, re-enable immediately
                    likeButton?.tag = null
                    likeButton?.isEnabled = true
                    Log.w("VideoAdapter", "No video data available for like")
                }
            }

            // Share button with advanced options on long press
            shareButton?.setOnClickListener {
                shareVideo()
            }
            
            shareButton?.setOnLongClickListener {
                showShareOptionsMenu()
                true
            }

            // Sound button
            soundButton?.setOnClickListener {
                isMuted = !isMuted
                updateSoundButton()
                setMuteState(isMuted)
            }
            
            // Subscribe button
            subscribeButton?.setOnClickListener {
                if (currentCreatorId != -1L) {
                    isSubscribed = !isSubscribed
                    updateSubscribeButton()
                    // Animate the subscribe button
                    subscribeButton.animate()
                        .scaleX(1.2f)
                        .scaleY(1.2f)
                        .setDuration(100)
                        .withEndAction {
                            subscribeButton.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(100)
                                .start()
                        }
                        .start()
                    
                    onSubscribeToggle?.invoke(currentCreatorId, isSubscribed)
                } else {
                    android.widget.Toast.makeText(itemView.context, "No se puede suscribir", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            
            // Comment button
            commentButton?.setOnClickListener {
                currentVideoData?.let { videoData ->
                    onCommentClick?.invoke(videoData)
                } ?: run {
                    android.widget.Toast.makeText(itemView.context, "Error al cargar comentarios", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            
            // Follow label click
            followLabel?.setOnClickListener {
                if (currentCreatorId != -1L) {
                    isSubscribed = !isSubscribed
                    updateSubscribeButton()
                    followLabel.text = if (isSubscribed) " • Suscrito" else " • Suscribirse"
                    onSubscribeToggle?.invoke(currentCreatorId, isSubscribed)
                }
            }

            // Fullscreen button container
            fullscreenButtonContainer?.setOnClickListener {
                // Navigate to fullscreen activity
                navigateToFullscreen()
            }

            // Setup gesture detector for swipe left to navigate to course
            setupVideoGestureDetector()
        }
        
        private fun updateSubscribeButton() {
            // If it's the current user's own video, hide subscription controls
            if (currentCreatorId != -1L && currentUserId != -1L && currentCreatorId == currentUserId) {
                subscribeButton?.visibility = View.GONE
                followLabel?.visibility = View.GONE
                return
            }

            subscribeButton?.let { button ->
                if (isSubscribed) {
                    button.visibility = View.GONE
                    followLabel?.visibility = View.VISIBLE
                    followLabel?.text = " • Suscrito"
                    followLabel?.setTextColor(android.graphics.Color.parseColor("#9C27B0"))
                } else {
                    button.visibility = View.VISIBLE
                    followLabel?.visibility = View.VISIBLE
                    followLabel?.text = " • Suscribirse"
                    followLabel?.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                }
            }
        }
        
        private fun formatCount(count: Int): String {
            return when {
                count >= 1000000 -> String.format("%.1fM", count / 1000000.0)
                count >= 1000 -> String.format("%.1fK", count / 1000.0).replace(".0K", "K")
                else -> count.toString()
            }
        }
        
        private fun parseCount(text: String): Int {
            return try {
                when {
                    text.endsWith("M") -> (text.dropLast(1).toDouble() * 1000000).toInt()
                    text.endsWith("K") -> (text.dropLast(1).toDouble() * 1000).toInt()
                    else -> text.toIntOrNull() ?: 0
                }
            } catch (e: Exception) {
                0
            }
        }

        private fun setupVideoGestureDetector() {
            var startX = 0f
            var startY = 0f
            var startTime = 0L

            videoView.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.x
                        startY = event.y
                        startTime = System.currentTimeMillis()
                    }
                    MotionEvent.ACTION_UP -> {
                        val endX = event.x
                        val endY = event.y
                        val endTime = System.currentTimeMillis()
                        
                        val diffX = endX - startX
                        val diffY = endY - startY
                        val timeDiff = endTime - startTime
                        
                        // Check if it's a swipe gesture (not just a tap)
                        if (timeDiff < 300 && abs(diffX) > 100) {
                            // Check if it's a horizontal swipe (left)
                            if (abs(diffX) > abs(diffY) && diffX < -100) {
                                // Swipe left detected - navigate to course
                                val position = adapterPosition
                                if (position != RecyclerView.NO_POSITION && position < videos.size) {
                                    val videoData = videos[position]
                                    onUsernameClick?.invoke(videoData)
                                    return@setOnTouchListener true
                                }
                            }
                        } else if (timeDiff < 200 && abs(diffX) < 50 && abs(diffY) < 50) {
                            // Single tap detected - toggle play/pause
                            togglePlayPause()
                        }
                    }
                }
                true
            }
        }

        private fun updateLikeButton() {
            likeButton?.let { button ->
                if (isLiked) {
                    button.setImageResource(R.drawable.ic_heart_minimal)
                    button.setColorFilter(android.graphics.Color.RED, android.graphics.PorterDuff.Mode.SRC_IN)
                } else {
                    button.setImageResource(R.drawable.ic_heart_minimal)
                    button.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }
        }

        private fun updateSoundButton() {
            soundButton?.let { button ->
                if (isMuted) {
                    button.setImageResource(R.drawable.ic_sound_muted_minimal)
                    button.setColorFilter(android.graphics.Color.GRAY, android.graphics.PorterDuff.Mode.SRC_IN)
                } else {
                    button.setImageResource(R.drawable.ic_sound_minimal)
                    button.setColorFilter(android.graphics.Color.WHITE, android.graphics.PorterDuff.Mode.SRC_IN)
                }
            }
        }

        private fun navigateToFullscreen() {
            try {
                val context = itemView.context
                val intent = android.content.Intent(context, com.example.tareamov.ui.VideoPlayerActivity::class.java)
                
                // Get current video data to pass to fullscreen activity
                val currentPosition = adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION && currentPosition < videos.size) {
                    val videoData = videos[currentPosition]
                    intent.putExtra("video_path", videoData.localFilePath ?: videoData.videoUriString)
                    intent.putExtra("video_title", videoData.title)
                    intent.putExtra("video_description", videoData.description)
                    // Fetch data asynchronously to prevent UI freeze
                    viewHolderScope?.launch {
                        // Obtener username desde course_id via backend
                        val username = if (videoData.courseId != null && videoData.courseId!! > 0) {
                            withContext(Dispatchers.IO) {
                                try {
                                    val courseResult = BackendApiService.getCourseById(videoData.courseId!!)
                                    val course = courseResult.getOrNull()
                                    if (course != null) {
                                        val uName = if (course.creatorUserId > 0) {
                                            val userResult = BackendApiService.getUserById(course.creatorUserId)
                                            userResult.getOrNull()?.usuario
                                        } else null
                                        uName ?: videoData.username
                                    } else {
                                        videoData.username
                                    }
                                } catch (e: Exception) {
                                    videoData.username
                                }
                            }
                        } else {
                            videoData.username
                        }
                        intent.putExtra("username", username ?: "")
                        
                        // Save current video position to restore in VideoPlayerActivity
                        val currentVideoPosition = try {
                            if (useExoPlayer) {
                                exoPlayer?.currentPosition?.toInt() ?: 0
                            } else {
                                videoView.currentPosition
                            }
                        } catch (e: Exception) {
                            0
                        }
                        intent.putExtra("video_position", currentVideoPosition)
                        Log.d("VideoAdapter", "Passing video position to fullscreen: $currentVideoPosition ms")
                        
                        // Pause current video before switching
                        pauseVideo()
                        
                        context.startActivity(intent)
                        Log.d("VideoAdapter", "Navigating to fullscreen with video: ${videoData.title}")
                    }
                } else {
                    Log.e("VideoAdapter", "Invalid position for fullscreen navigation")
                }
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error navigating to fullscreen", e)
            }
        }

        /**
         * Muestra un menú contextual con opciones avanzadas de compartir
         */
        private fun showShareOptionsMenu() {
            viewHolderScope?.launch {
                try {
                    val context = itemView.context
                    val currentPosition = adapterPosition
                    if (currentPosition == RecyclerView.NO_POSITION || currentPosition >= videos.size) return@launch
                    
                    val videoData = videos[currentPosition]
                    val r2PublicUrl = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.StorageHelper.getVideoStreamUrl(context, videoData.videoUriString)
                    }
                    
                    val options = mutableListOf<String>()
                    val actions = mutableListOf<() -> Unit>()
                
                // Opción 1: Compartir con enlace (si hay URL pública)
                if (r2PublicUrl != null && (r2PublicUrl.startsWith("http://") || r2PublicUrl.startsWith("https://"))) {
                    options.add("🔗 Compartir enlace del video")
                    actions.add {
                        shareVideo() // Usa el método principal que ya maneja URLs
                    }
                    
                    // Opción 2: Solo copiar URL al portapapeles
                    options.add("📋 Copiar URL al portapapeles")
                    actions.add {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("URL del video", r2PublicUrl)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "✅ URL copiada: $r2PublicUrl", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    
                    // Opción 3: Abrir en navegador
                    options.add("🌐 Abrir en navegador")
                    actions.add {
                        try {
                            val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(r2PublicUrl))
                            context.startActivity(browserIntent)
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Error al abrir navegador", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                
                // Opción 4: Compartir archivo local (si está disponible)
                if (videoData.localFilePath != null && java.io.File(videoData.localFilePath).exists()) {
                    options.add("📁 Compartir archivo local")
                    actions.add {
                        shareVideoFileLocally(videoData)
                    }
                }
                
                // Opción 5: Información del video
                options.add("ℹ️ Información del video")
                actions.add {
                    showVideoInfo(videoData, r2PublicUrl)
                }
                
                // Mostrar diálogo con opciones
                android.app.AlertDialog.Builder(context)
                    .setTitle("Opciones de compartir")
                    .setItems(options.toTypedArray()) { dialog, which ->
                        actions[which].invoke()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
                    
                } catch (e: Exception) {
                    Log.e("VideoAdapter", "❌ Error mostrando menú de opciones: ${e.message}", e)
                }
            }
        }

        /**
         * Comparte el archivo de video local utilizando un Intent
         */
        private fun shareVideoFileLocally(videoData: VideoData) {
            try {
                val context = itemView.context
                val path = videoData.localFilePath
                if (path == null) return
                
                val videoFile = java.io.File(path)
                
                if (!videoFile.exists()) {
                    android.widget.Toast.makeText(context, "❌ Archivo no encontrado", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }
                
                val videoUri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    videoFile
                )
                
                val shareText = buildString {
                    appendLine("🎥 ${videoData.title}")
                    if (!videoData.description.isNullOrEmpty()) {
                        appendLine()
                        appendLine(videoData.description)
                    }
                    appendLine()
                    appendLine("👤 Por: ${videoData.username ?: "Anónimo"}")
                }
                
                val sendIntent = android.content.Intent().apply {
                    action = android.content.Intent.ACTION_SEND
                    putExtra(android.content.Intent.EXTRA_STREAM, videoUri)
                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                    type = "video/*"
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                context.startActivity(android.content.Intent.createChooser(sendIntent, "Compartir archivo de video"))
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error compartiendo archivo local", e)
                android.widget.Toast.makeText(itemView.context, "Error al compartir archivo", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        
        /**
         * Muestra información detallada del video en un diálogo
         */
        private fun showVideoInfo(videoData: VideoData, r2PublicUrl: String?) {
            try {
                val context = itemView.context
                val info = buildString {
                    appendLine("🎥 ${videoData.title}")
                    appendLine()
                    appendLine("👤 Creador: ${videoData.username ?: "Desconocido"}")
                    appendLine()
                    if (!videoData.description.isNullOrEmpty()) {
                        appendLine("📝 Descripción:")
                        appendLine(videoData.description)
                        appendLine()
                    }
                    appendLine("🆔 ID del video: ${videoData.id}")
                    appendLine()
                    if (r2PublicUrl != null) {
                        appendLine("🔗 URL pública:")
                        appendLine(r2PublicUrl)
                        appendLine()
                        appendLine("✅ Disponible para compartir")
                    } else {
                        appendLine("⚠️ Sin URL pública disponible")
                        appendLine("Solo se puede ver en la app")
                    }
                    appendLine()
                    val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(videoData.timestamp))
                    appendLine("📅 Publicado: $date")
                    
                    if (videoData.localFilePath != null) {
                        val file = java.io.File(videoData.localFilePath)
                        if (file.exists()) {
                            val sizeMB = file.length() / (1024.0 * 1024.0)
                            appendLine("💾 Tamaño: ${String.format("%.2f", sizeMB)} MB")
                        }
                    }
                }
                
                android.app.AlertDialog.Builder(context)
                    .setTitle("Información del Video")
                    .setMessage(info)
                    .setPositiveButton("Cerrar", null)
                    .setNeutralButton("Copiar Info") { _, _ ->
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Información del video", info)
                        clipboard.setPrimaryClip(clip)
                        android.widget.Toast.makeText(context, "✅ Información copiada", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .show()
                
            } catch (e: Exception) {
                Log.e("VideoAdapter", "❌ Error mostrando info del video: ${e.message}", e)
            }
        }
        
        private fun shareVideo() {
            viewHolderScope?.launch {
                try {
                    val context = itemView.context
                    val currentPosition = adapterPosition
                    if (currentPosition != RecyclerView.NO_POSITION && currentPosition < videos.size) {
                        val videoData = videos[currentPosition]
                        
                        // ESTRATEGIA 1: Intentar obtener URL firmada del backend
                        val r2PublicUrl = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.StorageHelper.getVideoStreamUrl(context, videoData.videoUriString)
                        }
                    
                        if (r2PublicUrl != null && (r2PublicUrl.startsWith("http://") || r2PublicUrl.startsWith("https://"))) {
                            // Compartir usando URL pública de R2 - MÉTODO PREFERIDO
                            Log.d("VideoAdapter", "📤 Compartiendo video usando URL pública de R2: $r2PublicUrl")
                            
                            val shareText = buildString {
                                appendLine("🎥 ${videoData.title}")
                                appendLine()
                                if (!videoData.description.isNullOrEmpty()) {
                                    appendLine(videoData.description)
                                    appendLine()
                                }
                                appendLine("👤 Por: ${videoData.username ?: "Anónimo"}")
                                appendLine()
                                appendLine("🔗 Ver video:")
                                appendLine(r2PublicUrl)
                                appendLine()
                                appendLine("📱 Compartido desde TareaMov")
                            }
                            
                            // Copiar URL al portapapeles automáticamente para facilitar el compartir
                            try {
                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("URL del video", r2PublicUrl)
                                clipboard.setPrimaryClip(clip)
                                Log.d("VideoAdapter", "📋 URL copiada al portapapeles: $r2PublicUrl")
                            } catch (e: Exception) {
                                Log.w("VideoAdapter", "⚠️ No se pudo copiar URL al portapapeles: ${e.message}")
                            }
                            
                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "🎥 ${videoData.title}")
                                type = "text/plain" // Usar text/plain para compatibilidad con más apps
                            }
                            
                            val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartir video vía")
                            context.startActivity(shareIntent)
                            
                            android.widget.Toast.makeText(
                                context, 
                                "✅ Enlace del video listo para compartir\n📋 URL copiada al portapapeles", 
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            
                            Log.d("VideoAdapter", "✅ Video compartido exitosamente con URL pública")
                            return@launch
                        }
                        
                        // ESTRATEGIA 2: Compartir usando archivo local si está disponible
                        if (videoData.localFilePath != null) {
                            val videoFile = java.io.File(videoData.localFilePath)
                            if (videoFile.exists()) {
                                Log.d("VideoAdapter", "📤 Compartiendo video usando archivo local: ${videoData.localFilePath}")
                                
                                val videoUri: android.net.Uri = try {
                                    androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        videoFile
                                    )
                                } catch (e: Exception) {
                                    Log.e("VideoAdapter", "❌ Error creando URI con FileProvider: ${e.message}")
                                    // Fallback a file:// URI (menos compatible pero funciona en algunos casos)
                                    android.net.Uri.fromFile(videoFile)
                                }

                                val shareText = buildString {
                                    appendLine("🎥 ${videoData.title}")
                                    if (!videoData.description.isNullOrEmpty()) {
                                        appendLine()
                                        appendLine(videoData.description)
                                    }
                                    appendLine()
                                    appendLine("👤 Por: ${videoData.username ?: "Anónimo"}")
                                    appendLine("📱 Compartido desde TareaMov")
                                }

                                val sendIntent = android.content.Intent().apply {
                                    action = android.content.Intent.ACTION_SEND
                                    putExtra(android.content.Intent.EXTRA_STREAM, videoUri)
                                    putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    putExtra(android.content.Intent.EXTRA_SUBJECT, videoData.title)
                                    type = "video/*"
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }

                                val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartir video vía")
                                context.startActivity(shareIntent)
                                
                                Log.d("VideoAdapter", "✅ Compartiendo video desde archivo local: ${videoData.title}")
                                return@launch
                            } else {
                                Log.w("VideoAdapter", "⚠️ Archivo local no existe: ${videoData.localFilePath}")
                            }
                        }
                        
                        // ESTRATEGIA 3: Si no hay archivo local ni URL pública, compartir solo información del video
                        Log.d("VideoAdapter", "⚠️ No se encontró archivo local ni URL pública, compartiendo solo información")
                        
                        val shareText = buildString {
                            appendLine("🎥 ${videoData.title}")
                            appendLine()
                            if (!videoData.description.isNullOrEmpty()) {
                                appendLine(videoData.description)
                                appendLine()
                            }
                            appendLine("👤 Por: ${videoData.username ?: "Anónimo"}")
                            appendLine()
                            appendLine("📱 Video de TareaMov")
                            appendLine()
                            appendLine("⚠️ Para ver el video completo, descarga la app TareaMov")
                        }
                        
                        val sendIntent = android.content.Intent().apply {
                            action = android.content.Intent.ACTION_SEND
                            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, videoData.title)
                            type = "text/plain"
                        }
                        
                        val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartir información del video")
                        context.startActivity(shareIntent)
                        
                        android.widget.Toast.makeText(
                            context,
                            "ℹ️ Se compartió la información del video (el archivo no está disponible públicamente)",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                } catch (e: Exception) {
                    Log.e("VideoAdapter", "❌ Error sharing video: ${e.message}", e)
                    android.widget.Toast.makeText(
                        itemView.context, 
                        "Error al compartir video: ${e.message}", 
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onViewAttachedToWindow(holder: VideoViewHolder) {
        super.onViewAttachedToWindow(holder)
        val position = holder.bindingAdapterPosition
        Log.d("VideoAdapter", "onViewAttachedToWindow: position=$position, activePosition=$currentActivePosition")
        
        // ONLY play if this is the currently active position
        // This prevents multiple videos from playing audio during scroll
        if (position == currentActivePosition) {
            holder.playVideo()
        } else {
            // For non-active positions, ensure they are paused and muted
            holder.pauseVideo()
        }
    }

    override fun onViewDetachedFromWindow(holder: VideoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val position = holder.bindingAdapterPosition
        Log.d("VideoAdapter", "onViewDetachedFromWindow: position=$position")
        
        // ALWAYS pause and mute when detached - this is critical to prevent audio leaks
        holder.pauseVideo()
    }
    
    /**
     * Pause all videos - call this when fragment is paused
     */
    fun pauseAllVideos() {
        // This will be called by the fragment's onPause
        // Individual holders are paused via onViewDetachedFromWindow
    }
    
    /**
     * Release all video resources - call this when fragment is destroyed
     */
    fun releaseAllPlayers() {
        // This signals that all players should be released
        // Individual holders handle their own cleanup
    }
}