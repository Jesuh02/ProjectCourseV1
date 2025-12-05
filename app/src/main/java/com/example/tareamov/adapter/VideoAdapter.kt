package com.example.tareamov.adapter

import android.media.MediaPlayer // Added for MediaPlayer
import android.net.Uri
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
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.VideoData
import java.io.File
import kotlinx.coroutines.*
import kotlin.math.abs // Use kotlin.math.abs to avoid ambiguity

/**
 * Adaptador para mostrar videos en un ViewPager2 con estilo TikTok
 */
class VideoAdapter(
    private var videos: List<VideoData>,
    private val onProfileClick: ((String) -> Unit)? = null,
    private val onUsernameClick: ((VideoData) -> Unit)? = null,
    private val onSubscribeToggle: ((Long, Boolean) -> Unit)? = null,
    private val checkSubscriptionStatus: (suspend (Long) -> Boolean)? = null
) : RecyclerView.Adapter<VideoAdapter.VideoViewHolder>() {

    private var currentUserId: Long = -1L

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
        private val usernameText: TextView = itemView.findViewById(R.id.usernameText)
        private val descriptionText: TextView = itemView.findViewById(R.id.videoDescription)
        private val titleText: TextView = itemView.findViewById(R.id.gameTitle)
        private val errorPlaceholder: TextView = itemView.findViewById(R.id.errorPlaceholder)
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
        
        private var currentJob: Job? = null
        private var mediaPlayer: MediaPlayer? = null
    private var mediaPlayerPrepared: Boolean = false
        private var isVideoPaused = false
        private var isLiked = false
        private var isMuted = false
        private var isSubscribed = false
        private var overlayHandler = android.os.Handler(android.os.Looper.getMainLooper())
        private var overlayRunnable: Runnable? = null
        private var currentCreatorId: Long = -1L

        private fun showErrorPlaceholder() {
            videoView.visibility = View.GONE
            loadingProgressBar?.visibility = View.GONE
            errorPlaceholder.visibility = View.VISIBLE
            Log.e("VideoAdapter", "Showing error placeholder")
        }        fun bind(videoData: VideoData) {
            descriptionText.text = videoData.description
            titleText.text = videoData.title

            // Reset views and states
            videoView.visibility = View.VISIBLE
            errorPlaceholder.visibility = View.GONE
            loadingProgressBar?.visibility = View.VISIBLE
            playPauseOverlay?.visibility = View.GONE
            isVideoPaused = false
            isLiked = false
            isMuted = false
            isSubscribed = false
            
            // Reset button states
            updateLikeButton()
            updateSoundButton()
            updateSubscribeButton()
            
            // Set random counts for demo (in production, fetch from server)
            likeCountText?.text = "0"
            commentCountText?.text = "0"

            // Setup button listeners
            setupButtonListeners()

            // --- OBTENER USERNAME DESDE COURSE_ID ---
            currentJob?.cancel()
            profileButton.setImageResource(R.drawable.ic_profile)
            usernameText.text = "Cargando..." // Placeholder mientras se carga
            currentCreatorId = -1L // Reset creator ID

            currentJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Obtener username desde course_id
                    val username = if (videoData.courseId != null && videoData.courseId!! > 0) {
                        withContext(Dispatchers.IO) {
                            // Try to get creator ID from Supabase directly for validation
                            try {
                                val course = com.example.tareamov.service.SupabaseClient.fetchCourseById(videoData.courseId!!)
                                if (course != null) {
                                    currentCreatorId = course.creatorUserId
                                }
                            } catch (e: Exception) {
                                Log.e("VideoAdapter", "Error fetching course from Supabase", e)
                                // Fallback to local
                                val context = itemView.context.applicationContext
                                val db = AppDatabase.getDatabase(context)
                                val course = db.courseDao().getCourseById(videoData.courseId!!)
                                if (course != null) {
                                    currentCreatorId = course.creatorUserId
                                }
                            }
                            com.example.tareamov.service.SupabaseClient.getUsernameFromCourseId(videoData.courseId!!)
                        }
                    } else {
                        videoData.username // Fallback para compatibilidad
                    }

                    // If creatorId is still not set but we have a username, try to find the user
                    if (currentCreatorId == -1L && !username.isNullOrEmpty()) {
                        withContext(Dispatchers.IO) {
                            val context = itemView.context.applicationContext
                            val db = AppDatabase.getDatabase(context)
                            val user = db.usuarioDao().getUsuarioByUsername(username)
                            if (user != null) {
                                currentCreatorId = user.id
                            }
                        }
                    }
                    
                    // Check subscription status if we have a valid creatorId
                    if (currentCreatorId != -1L && checkSubscriptionStatus != null) {
                        isSubscribed = checkSubscriptionStatus.invoke(currentCreatorId)
                        updateSubscribeButton()
                    }

                    // Actualizar UI con el username obtenido - format like username
                    usernameText.text = "${username ?: "usuario"}"
                    
                    // Update audio text with username
                    audioText?.text = "Original Audio - ${username ?: "usuario"}"

                    // Setup profile button click con el username correcto
                    profileButton.setOnClickListener {
                        onProfileClick?.invoke(username ?: "")
                    }

                    // Setup username text click to navigate to course
                    usernameText.setOnClickListener {
                        onUsernameClick?.invoke(videoData)
                    }

                    // Also make the title clickable to navigate to the course details
                    titleText.setOnClickListener {
                        onUsernameClick?.invoke(videoData)
                    }

                    // --- AVATAR LOADING LOGIC ---
                    val usuario = withContext(Dispatchers.IO) {
                        try {
                            var user: com.example.tareamov.data.entity.Usuario? = null
                            val client = com.example.tareamov.service.SupabaseClient
                            
                            // 1. Try Supabase first (Priority)
                            if (client.isConfigured()) {
                                // Try by ID first if available (most reliable)
                                if (currentCreatorId != -1L) {
                                    user = client.fetchUsuarioById(currentCreatorId)
                                }
                                // Fallback to username if ID didn't work
                                if (user == null && !username.isNullOrEmpty()) {
                                    user = client.fetchUsuarioByUsername(username)
                                }
                            }
                            
                            // 2. Fallback to local DB if Supabase failed or not configured
                            if (user == null) {
                                val context = itemView.context.applicationContext
                                val db = AppDatabase.getDatabase(context)
                                if (currentCreatorId != -1L) {
                                    user = db.usuarioDao().getUsuarioById(currentCreatorId)
                                }
                                if (user == null && !username.isNullOrEmpty()) {
                                    user = db.usuarioDao().getUsuarioByUsername(username)
                                }
                            }
                            
                            // 3. Update local cache if found in Supabase
                            if (user != null && client.isConfigured()) {
                                try {
                                    val context = itemView.context.applicationContext
                                    val db = AppDatabase.getDatabase(context)
                                    db.usuarioDao().insertUsuario(user)
                                } catch (e: Exception) {
                                    Log.w("VideoAdapter", "Failed to cache user", e)
                                }
                            }
                            
                            user
                        } catch (e: Exception) {
                            Log.e("VideoAdapter", "Error fetching avatar user", e)
                            null
                        }
                    }

                    if (usuario != null && !usuario.avatar.isNullOrEmpty()) {
                        Log.d("VideoAdapter", "Loading avatar for ${usuario.usuario}: ${usuario.avatar}")
                        Glide.with(itemView)
                            .load(usuario.avatar)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .into(profileButton)
                    } else {
                        Log.d("VideoAdapter", "No avatar found for username=$username, id=$currentCreatorId")
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
                    
                    // Check if it's a local file URI and if the file exists
                    if (bestUri.scheme == "file") {
                        val file = File(bestUri.path ?: "")
                        if (!file.exists()) {
                            Log.e("VideoAdapter", "Local video file does not exist: ${bestUri.path}")
                            showErrorPlaceholder()
                            errorPlaceholder.text = "Video no disponible en este dispositivo"
                            return
                        }
                    }
                    
                    videoView.setVideoURI(bestUri)

                    videoView.setOnPreparedListener { mp ->
                        loadingProgressBar?.visibility = View.GONE
                        this.mediaPlayer = mp
                        mediaPlayerPrepared = true
                        val videoWidth = mp.videoWidth
                        val videoHeight = mp.videoHeight
                        if (videoWidth > 0 && videoHeight > 0) {
                            val parentWidth = (videoView.parent as View).width
                            val aspectRatio = videoWidth.toFloat() / videoHeight.toFloat()
                            val newHeight = (parentWidth.toFloat() / aspectRatio).toInt()
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
                        mediaPlayerPrepared = false
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
                Log.e("VideoAdapter", "No valid video URI available. videoUriString='${videoData.videoUriString}', localFilePath='${videoData.localFilePath}'")
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
            // update desired mute state immediately
            isMuted = mute

            // If MediaPlayer is prepared, apply volume immediately. If not, it will be applied
            // inside the onPreparedListener when the player becomes ready.
            val volume = if (isMuted) 0f else 1f
            if (mediaPlayerPrepared) {
                try {
                    mediaPlayer?.setVolume(volume, volume)
                } catch (e: IllegalStateException) {
                    Log.e("VideoAdapter", "Error setting volume, MediaPlayer might not be ready.", e)
                    // keep isMuted flag; volume will be applied later when prepared
                    mediaPlayerPrepared = false
                }
            } else {
                // not prepared yet: volume will be applied when onPreparedListener runs
                Log.d("VideoAdapter", "MediaPlayer not prepared yet; saved mute state=$isMuted")
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
                    // Mostrar botón de pantalla completa durante 2 segundos al pausar
                    showFullscreenButtonTemporarily()
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
            // Like button
            likeButton?.setOnClickListener {
                isLiked = !isLiked
                updateLikeButton()
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
                android.widget.Toast.makeText(itemView.context, "Comentarios próximamente", android.widget.Toast.LENGTH_SHORT).show()
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
                    // Obtener username desde course_id para el intent
                    val username = if (videoData.courseId != null && videoData.courseId!! > 0) {
                        runBlocking {
                            com.example.tareamov.service.SupabaseClient.getUsernameFromCourseId(videoData.courseId!!)
                        }
                    } else {
                        videoData.username
                    }
                    intent.putExtra("username", username ?: "")
                    
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