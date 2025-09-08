package com.example.tareamov.adapter

import android.media.MediaPlayer // Added for MediaPlayer
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.VideoView
import android.widget.ImageView
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.VideoData
import java.io.File
import kotlinx.coroutines.*

/**
 * Adaptador para mostrar videos en un ViewPager2 con estilo TikTok
 */
class VideoAdapter(
    private var videos: List<VideoData>,
    private val onProfileClick: ((String) -> Unit)? = null,
    private val onUsernameClick: ((VideoData) -> Unit)? = null
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

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
        private val usernameText: TextView = itemView.findViewById(R.id.usernameText)
        private val descriptionText: TextView = itemView.findViewById(R.id.videoDescription)
        private val titleText: TextView = itemView.findViewById(R.id.gameTitle)
        private val errorPlaceholder: TextView = itemView.findViewById(R.id.errorPlaceholder)
        private val profileButton: de.hdodenhof.circleimageview.CircleImageView = itemView.findViewById(R.id.profileButton)
        private val playPauseOverlay: android.widget.ImageView? = itemView.findViewById(R.id.playPauseOverlay)
        private val fullscreenButton: android.widget.ImageView? = itemView.findViewById(R.id.fullscreenButton)
        private val likeButton: android.widget.ImageView? = itemView.findViewById(R.id.likeButton)
        private val shareButton: android.widget.ImageView? = itemView.findViewById(R.id.shareButton)
        private val soundButton: android.widget.ImageView? = itemView.findViewById(R.id.soundButton)
        private var currentJob: Job? = null
        private var mediaPlayer: MediaPlayer? = null
        private var isVideoPaused = false
        private var isLiked = false
        private var isMuted = false
        private var overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var overlayRunnable: Runnable? = null

        private fun showErrorPlaceholder() {
            videoView.visibility = View.GONE
            errorPlaceholder.visibility = View.VISIBLE
            Log.e("VideoAdapter", "Showing error placeholder")
        }        fun bind(videoData: VideoData) {
            usernameText.text = videoData.username
            descriptionText.text = videoData.description
            titleText.text = videoData.title

            // Reset views and states
            videoView.visibility = View.VISIBLE
            errorPlaceholder.visibility = View.GONE
            playPauseOverlay?.visibility = View.GONE
            isVideoPaused = false
            isLiked = false
            isMuted = false
            
            // Reset button states
            updateLikeButton()
            updateSoundButton()

            // Setup button listeners
            setupButtonListeners()

            // Setup profile button click
            profileButton.setOnClickListener {
                onProfileClick?.invoke(videoData.username)
            }

            // Setup username text click to navigate to course
            usernameText.setOnClickListener {
                onUsernameClick?.invoke(videoData)
            }

            // --- AVATAR LOADING LOGIC ---
            currentJob?.cancel()
            profileButton.setImageResource(R.drawable.ic_profile)

            currentJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    val context = itemView.context.applicationContext
                    val db = AppDatabase.getDatabase(context)
                    val persona = withContext(Dispatchers.IO) {
                        db.personaDao().getPersonaByUsername(videoData.username)
                    }
                    if (persona != null && !persona.avatar.isNullOrEmpty()) {
                        Glide.with(itemView)
                            .load(persona.avatar)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .into(profileButton)
                    } else {
                        profileButton.setImageResource(R.drawable.ic_profile)
                    }
                } catch (e: Exception) {
                    profileButton.setImageResource(R.drawable.ic_profile)
                }
            }

            // Setup video playback
            val bestUri = videoData.getBestVideoUri()
            if (bestUri != null) {
                try {
                    Log.d("VideoAdapter", "Setting video URI: $bestUri")
                    videoView.setVideoURI(bestUri)

                    videoView.setOnPreparedListener { mp ->
                        this.mediaPlayer = mp
                        val videoWidth = mp.videoWidth
                        val videoHeight = mp.videoHeight
                        if (videoWidth > 0 && videoHeight > 0) {
                            val parentWidth = (videoView.parent as View).width
                            val aspectRatio = videoWidth.toFloat() / videoHeight
                            val newHeight = (parentWidth / aspectRatio).toInt()
                            val params = videoView.layoutParams
                            params.width = parentWidth
                            params.height = newHeight
                            videoView.layoutParams = params
                        }
                        mp.setVolume(if (isMuted) 0f else 1f, if (isMuted) 0f else 1f)
                        mp.isLooping = true
                        videoView.start()
                    }

                    videoView.setOnErrorListener { _, what, extra ->
                        Log.e("VideoAdapter", "Video playback error: what=$what, extra=$extra")
                        showErrorPlaceholder()
                        true
                    }

                    videoView.setOnClickListener {
                        togglePlayPause()
                    }
                } catch (e: Exception) {
                    Log.e("VideoAdapter", "Error setting video URI", e)
                    showErrorPlaceholder()
                }
            } else {
                Log.e("VideoAdapter", "No valid video URI available")
                showErrorPlaceholder()
            }
        }        /**
         * Pausa la reproducción del video
         */
        fun pauseVideo() {
            if (videoView.isPlaying) {
                videoView.pause()
                isVideoPaused = true
            }
        }/**
         * Inicia la reproducción del video
         */
        fun playVideo() {
            try {
                if (!videoView.isPlaying && !isVideoPaused) {
                    // Only auto-play if the user hasn't manually paused it
                    videoView.start()
                }
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error playing video", e)
            }
        }/**
         * Sets the mute state of the video.
         */
        fun setMuteState(mute: Boolean) {
            val volume = if (mute) 0f else 1f
            try {
                mediaPlayer?.setVolume(volume, volume)
            } catch (e: IllegalStateException) {
                Log.e("VideoAdapter", "Error setting volume, MediaPlayer might not be ready.", e)
                // Optionally, store desired mute state and apply it in onPreparedListener
            }
        }

        /**
         * Toggles between play and pause state of the video
         */
        private fun togglePlayPause() {
            try {
                if (videoView.isPlaying) {
                    videoView.pause()
                    isVideoPaused = true
                    showPlayPauseOverlay(R.drawable.ic_play_overlay)
                    Log.d("VideoAdapter", "Video paused by user tap")
                } else {
                    videoView.start()
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
                overlay.alpha = 0.8f
                
                // Remove any existing callback
                overlayRunnable?.let { overlayHandler.removeCallbacks(it) }
                
                // Hide overlay after 1.5 seconds
                overlayRunnable = Runnable {
                    overlay.visibility = View.GONE
                }
                overlayHandler.postDelayed(overlayRunnable!!, 1500)
            }
        }

        private fun setupButtonListeners() {
            // Like button
            likeButton?.setOnClickListener {
                isLiked = !isLiked
                updateLikeButton()
            }

            // Share button
            shareButton?.setOnClickListener {
                shareVideo()
            }

            // Sound button
            soundButton?.setOnClickListener {
                isMuted = !isMuted
                updateSoundButton()
                setMuteState(isMuted)
            }

            // Fullscreen button
            fullscreenButton?.setOnClickListener {
                // Navigate to fullscreen activity
                navigateToFullscreen()
            }

            // Setup gesture detector for swipe left to navigate to course
            setupVideoGestureDetector()
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
                        if (timeDiff < 300 && Math.abs(diffX) > 100) {
                            // Check if it's a horizontal swipe (left)
                            if (Math.abs(diffX) > Math.abs(diffY) && diffX < -100) {
                                // Swipe left detected - navigate to course
                                val position = adapterPosition
                                if (position != RecyclerView.NO_POSITION && position < videos.size) {
                                    val videoData = videos[position]
                                    onUsernameClick?.invoke(videoData)
                                    return@setOnTouchListener true
                                }
                            }
                        } else if (timeDiff < 200 && Math.abs(diffX) < 50 && Math.abs(diffY) < 50) {
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
                    button.setColorFilter(android.graphics.Color.parseColor("#FF6B6B"), android.graphics.PorterDuff.Mode.SRC_IN)
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
                    intent.putExtra("username", videoData.username)
                    
                    // Pause current video before switching
                    pauseVideo()
                    
                    context.startActivity(intent)
                    Log.d("VideoAdapter", "Navigating to fullscreen with video: ${videoData.title}")
                } else {
                    Log.e("VideoAdapter", "Invalid position for fullscreen navigation")
                }
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error navigating to fullscreen", e)
            }
        }

        private fun shareVideo() {
            try {
                val context = itemView.context
                val currentPosition = adapterPosition
                if (currentPosition != RecyclerView.NO_POSITION && currentPosition < videos.size) {
                    val videoData = videos[currentPosition]
                    
                    if (videoData.localFilePath != null) {
                        val videoFile = java.io.File(videoData.localFilePath)
                        if (videoFile.exists()) {
                            val videoUri: android.net.Uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                videoFile
                            )

                            val shareText = "Mira este video: ${videoData.title}\n${videoData.description}"

                            val sendIntent = android.content.Intent().apply {
                                action = android.content.Intent.ACTION_SEND
                                putExtra(android.content.Intent.EXTRA_STREAM, videoUri)
                                putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, videoData.title)
                                type = context.contentResolver.getType(videoUri) ?: "video/*"
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }

                            val shareIntent = android.content.Intent.createChooser(sendIntent, "Compartir video vía")
                            context.startActivity(shareIntent)
                            Log.d("VideoAdapter", "Sharing video: ${videoData.title}")
                        } else {
                            android.widget.Toast.makeText(context, "Archivo de video no encontrado.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        android.widget.Toast.makeText(context, "No hay video para compartir o ruta no válida.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoAdapter", "Error sharing video", e)
                android.widget.Toast.makeText(itemView.context, "Error al compartir video", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onViewAttachedToWindow(holder: VideoViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.playVideo()
    }

    override fun onViewDetachedFromWindow(holder: VideoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.pauseVideo()
    }
}