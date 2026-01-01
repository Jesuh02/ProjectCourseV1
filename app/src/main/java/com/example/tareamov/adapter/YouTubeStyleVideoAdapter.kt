package com.example.tareamov.adapter

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.VideoData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.tareamov.util.SessionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

import com.example.tareamov.util.TimeUtils

/**
 * Adaptador para mostrar videos con estilo similar a YouTube
 */
class YouTubeStyleVideoAdapter(
    private val context: Context,
    private var videos: MutableList<VideoData>,
    private val onVideoClickListener: (VideoData) -> Unit,
    private val onEditClickListener: ((VideoData) -> Unit)? = null,
    private val onDeleteClickListener: ((VideoData) -> Unit)? = null,
    private var currentUsername: String? = null
) : RecyclerView.Adapter<YouTubeStyleVideoAdapter.VideoViewHolder>() {

    // Cache for video durations to avoid re-extracting
    private val durationCache = mutableMapOf<Long, String>()
    
    init {
        setHasStableIds(true)
    }
    
    override fun getItemId(position: Int): Long = videos[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_youtube_style, parent, false)
        return VideoViewHolder(view)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(videos[position])
    }

    override fun getItemCount(): Int = videos.size

    fun updateVideos(newVideos: List<VideoData>) {
        val diffCallback = VideoDiffCallback(videos, newVideos)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        videos.clear()
        videos.addAll(newVideos)
        diffResult.dispatchUpdatesTo(this)
    }
    
    private class VideoDiffCallback(
        private val oldList: List<VideoData>,
        private val newList: List<VideoData>
    ) : DiffUtil.Callback() {
        override fun getOldListSize() = oldList.size
        override fun getNewListSize() = newList.size
        override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldList[oldPos].id == newList[newPos].id
        override fun areContentsTheSame(oldPos: Int, newPos: Int) = oldList[oldPos] == newList[newPos]
    }

    inner class VideoViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnailImageView: ImageView = itemView.findViewById(R.id.videoThumbnailImageView)
        private val durationTextView: TextView = itemView.findViewById(R.id.videoDurationTextView)
        private val titleTextView: TextView = itemView.findViewById(R.id.videoTitleTextView)
        private val channelNameTextView: TextView = itemView.findViewById(R.id.channelNameTextView)
        private val videoInfoTextView: TextView = itemView.findViewById(R.id.videoInfoTextView)
        private val moreOptionsImageView: ImageView = itemView.findViewById(R.id.moreOptionsImageView)
          fun bind(video: VideoData) {
            // Establecer título del video con máximo 2 líneas
            titleTextView.maxLines = 2
            titleTextView.text = video.title

            // Establecer nombre del canal/usuario con estilo destacado
            channelNameTextView.text = video.username
            
            // Establecer información del video (fecha y vistas) con formato mejorado
            val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            val dateString = dateFormat.format(Date(video.timestamp))
            
            // Calcular tiempo relativo más amigable 
            val timeAgo = getTimeAgoString(video.timestamp)
            
            // Mostrar información con formato más atractivo
            videoInfoTextView.text = "$timeAgo • ${video.title.substringBefore(" ")}"

            // Cargar miniatura del video
            loadVideoThumbnail(video)

            // Obtener y mostrar duración del video
            getDurationAndDisplay(video)            // Configurar click listener con animación
            itemView.setOnClickListener {
                // Añadir efecto de pulsación
                it.animate()
                    .scaleX(0.96f)
                    .scaleY(0.96f)
                    .setDuration(100)
                    .withEndAction {
                        it.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start()
                        
                        // Navegar al video después de la animación
                        onVideoClickListener(video)
                    }
                    .start()
            }

            // Mostrar/ocultar botón de más opciones solo si el usuario actual es el propietario
            val loggedInUsername = try {
                currentUsername ?: SessionManager.getInstance(context).getUsername()
            } catch (e: Exception) {
                null
            }

            moreOptionsImageView.visibility = if (!loggedInUsername.isNullOrEmpty() && loggedInUsername == video.username) View.VISIBLE else View.GONE

            moreOptionsImageView.setOnClickListener {
                if (!loggedInUsername.isNullOrEmpty() && loggedInUsername == video.username) {
                    showPopupMenu(it, video)
                }
            }
        }

        private fun showPopupMenu(anchorView: View, video: VideoData) {
            // Use ContextThemeWrapper to apply dark theme to PopupMenu
            val wrapper = android.view.ContextThemeWrapper(context, R.style.DarkPopupMenuThemeOverlay)
            val popupMenu = android.widget.PopupMenu(wrapper, anchorView, android.view.Gravity.END)
            popupMenu.menu.add(0, 1, 0, "✏️ Modificar")
            popupMenu.menu.add(0, 2, 1, "🗑️ Eliminar")
            
            // Force icons to show if API level supports it
            try {
                val popup = android.widget.PopupMenu::class.java.getDeclaredField("mPopup")
                popup.isAccessible = true
                val menuPopupHelper = popup.get(popupMenu)
                menuPopupHelper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                    .invoke(menuPopupHelper, true)
            } catch (e: Exception) {
                // Ignore if reflection fails
            }
            
            popupMenu.setOnMenuItemClickListener { menuItem ->
                when (menuItem.itemId) {
                    1 -> {
                        onEditClickListener?.invoke(video)
                        true
                    }
                    2 -> {
                        onDeleteClickListener?.invoke(video)
                        true
                    }
                    else -> false
                }
            }
            
            popupMenu.show()
        }          private fun loadVideoThumbnail(video: VideoData) {
            // Set placeholder immediately
            thumbnailImageView.setImageResource(R.drawable.placeholder_image)
            
            try {
                // PRIORIDAD 1: HTTP/HTTPS URL (R2, Supabase, etc.) - Most common case
                val thumbnailUrl = video.thumbnailUri
                if (!thumbnailUrl.isNullOrEmpty() && thumbnailUrl.startsWith("http")) {
                    Glide.with(context)
                        .load(thumbnailUrl)
                        .placeholder(R.drawable.placeholder_image)
                        .error(R.drawable.placeholder_image)
                        .centerCrop()
                        .into(thumbnailImageView)
                    return
                }
                
                // PRIORIDAD 2: Archivo local de miniatura
                if (!thumbnailUrl.isNullOrEmpty()) {
                    val thumbnailFile = File(thumbnailUrl)
                    if (thumbnailFile.exists()) {
                        Glide.with(context)
                            .load(thumbnailFile)
                            .placeholder(R.drawable.placeholder_image)
                            .error(R.drawable.placeholder_image)
                            .centerCrop()
                            .into(thumbnailImageView)
                        return
                    }
                }
                
                // PRIORIDAD 3: URL del video como fuente de thumbnail (Glide lo maneja)
                val videoUrl = video.videoUriString
                if (!videoUrl.isNullOrEmpty() && videoUrl.startsWith("http")) {
                    Glide.with(context)
                        .load(videoUrl)
                        .placeholder(R.drawable.placeholder_image)
                        .error(R.drawable.placeholder_image)
                        .centerCrop()
                        .into(thumbnailImageView)
                    return
                }
                
                // PRIORIDAD 4: Archivo local de video (let Glide handle frame extraction)
                val localPath = video.localFilePath
                if (!localPath.isNullOrEmpty()) {
                    val videoFile = File(localPath)
                    if (videoFile.exists()) {
                        Glide.with(context)
                            .load(videoFile)
                            .placeholder(R.drawable.placeholder_image)
                            .error(R.drawable.placeholder_image)
                            .centerCrop()
                            .into(thumbnailImageView)
                        return
                    }
                }
                
            } catch (e: Exception) {
                Log.e("YouTubeStyleVideoAdapter", "Error loading thumbnail: ${e.message}")
            }
        }

        private fun getDurationAndDisplay(video: VideoData) {
            // Check cache first
            durationCache[video.id]?.let { cachedDuration ->
                durationTextView.text = cachedDuration
                durationTextView.visibility = View.VISIBLE
                return
            }
            
            // Hide duration initially while loading
            durationTextView.visibility = View.GONE
            
            val videoSource = when {
                !video.localFilePath.isNullOrEmpty() && File(video.localFilePath).exists() -> video.localFilePath
                !video.videoUriString.isNullOrEmpty() && !video.videoUriString.startsWith("http") -> video.videoUriString
                else -> null
            }
            
            // Only extract duration for local files (HTTP extraction is too slow)
            if (videoSource != null) {
                // Run on background thread to avoid blocking UI
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(videoSource)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        retriever.release()
                        
                        duration?.toLongOrNull()?.let { durationMs ->
                            val durationFormatted = TimeUtils.formatTime(durationMs)
                            // Cache the result
                            durationCache[video.id] = durationFormatted
                            
                            withContext(Dispatchers.Main) {
                                durationTextView.text = durationFormatted
                                durationTextView.visibility = View.VISIBLE
                            }
                        }
                    } catch (e: Exception) {
                        // Silently fail - duration is not critical
                    }
                }
            }
        }        private fun getTimeAgoString(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp
            
            // Convertir a diferentes unidades de tiempo
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24
            val weeks = days / 7
            val months = days / 30
            val years = days / 365
              return when {
                years > 0 -> "hace $years " + if (years == 1L) "año" else "años"
                months > 0 -> "hace $months " + if (months == 1L) "mes" else "meses"
                weeks > 0 -> "hace $weeks " + if (weeks == 1L) "semana" else "semanas"
                days > 0 -> "hace $days " + if (days == 1L) "día" else "días"
                hours > 0 -> "hace $hours " + if (hours == 1L) "hora" else "horas"
                minutes > 0 -> "hace $minutes " + if (minutes == 1L) "minuto" else "minutos"
                else -> "hace un momento"
            }
        }
        
        private fun isValidUri(uriString: String?): Boolean {
            if (uriString.isNullOrEmpty()) return false
            
            return try {
                val uri = Uri.parse(uriString)
                when (uri.scheme?.lowercase()) {
                    "file" -> {
                        // Check if file exists and is readable
                        val file = File(uri.path ?: "")
                        file.exists() && file.canRead()
                    }
                    "content" -> {
                        // Only allow specific content providers, avoid Google Drive URIs
                        val authority = uri.authority
                        authority != null && 
                        !authority.contains("com.google.android.apps.docs") &&
                        !authority.contains("com.google.android.apps.drive")
                    }
                    "android.resource" -> true
                    "http", "https" -> true
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
