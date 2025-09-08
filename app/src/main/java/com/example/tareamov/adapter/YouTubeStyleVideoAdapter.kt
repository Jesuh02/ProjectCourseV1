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
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.VideoData
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Adaptador para mostrar videos con estilo similar a YouTube
 */
class YouTubeStyleVideoAdapter(
    private val context: Context,
    private var videos: MutableList<VideoData>,
    private val onVideoClickListener: (VideoData) -> Unit
) : RecyclerView.Adapter<YouTubeStyleVideoAdapter.VideoViewHolder>() {

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
        videos.clear()
        videos.addAll(newVideos)
        notifyDataSetChanged()
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

            // Configurar botón de más opciones (opcional)
            moreOptionsImageView.setOnClickListener {
                // Implementar menú de opciones si es necesario
            }
        }          private fun loadVideoThumbnail(video: VideoData) {
            try {
                // Añadir efecto de carga con transición suave
                Glide.with(context)
                    .load(R.drawable.placeholder_image)
                    .centerCrop()
                    .into(thumbnailImageView)
                
                // Intentar cargar thumbnail desde diferentes fuentes con mejor transición
                when {
                    // Si hay thumbnail específico y es válido
                    !video.thumbnailUri.isNullOrEmpty() && isValidUri(video.thumbnailUri) -> {
                        val thumbnailFile = File(video.thumbnailUri)
                        if (thumbnailFile.exists() && thumbnailFile.canRead()) {
                            Glide.with(context)
                                .load(thumbnailFile)
                                .placeholder(R.drawable.placeholder_image)
                                .error(R.drawable.placeholder_image)
                                .centerCrop()
                                .thumbnail(0.5f)
                                .into(thumbnailImageView)
                        } else {
                            // Intentar cargar como URI/URL solo si es válida
                            try {
                                val uri = Uri.parse(video.thumbnailUri)
                                Glide.with(context)
                                    .load(uri)
                                    .placeholder(R.drawable.placeholder_image)
                                    .error(R.drawable.placeholder_image)
                                    .centerCrop()
                                    .thumbnail(0.5f)
                                    .into(thumbnailImageView)
                            } catch (e: SecurityException) {
                                Log.w("YouTubeStyleVideoAdapter", "Permission denied for thumbnail URI: ${video.thumbnailUri}")
                                thumbnailImageView.setImageResource(R.drawable.placeholder_image)
                            }
                        }
                    }
                    
                    // Si hay archivo de video local, generar thumbnail
                    !video.localFilePath.isNullOrEmpty() -> {
                        val videoFile = File(video.localFilePath)
                        if (videoFile.exists() && videoFile.canRead()) {
                            try {
                                val retriever = MediaMetadataRetriever()
                                retriever.setDataSource(videoFile.absolutePath)                            
                                // Intentar extraer frames a diferentes tiempos del video para elegir el mejor thumbnail
                                // Probar con varios frames y elegir uno más representativo
                                val candidateFrames = listOf(
                                    retriever.getFrameAtTime(3000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC),
                                    retriever.getFrameAtTime(5000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC),
                                    retriever.getFrameAtTime(8000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC),
                                    retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                )
                                
                                // Elegir el primer frame no nulo
                                val bitmap = candidateFrames.firstOrNull { it != null }
                                retriever.release()
                                
                                if (bitmap != null) {
                                    Glide.with(context)
                                        .load(bitmap)
                                        .placeholder(R.drawable.placeholder_image)
                                        .centerCrop()
                                        .thumbnail(0.5f)
                                        .into(thumbnailImageView)
                                } else {
                                    // Cargar directamente el video como fuente
                                    Glide.with(context)
                                        .load(videoFile)
                                        .placeholder(R.drawable.placeholder_image)
                                        .error(R.drawable.placeholder_image)
                                        .centerCrop()
                                        .thumbnail(0.5f)
                                        .into(thumbnailImageView)
                                }
                            } catch (e: Exception) {
                                Log.e("YouTubeStyleVideoAdapter", "Error extracting thumbnail from local file: ${e.message}")
                                thumbnailImageView.setImageResource(R.drawable.placeholder_image)
                            }
                        } else {
                            Log.w("YouTubeStyleVideoAdapter", "Local video file not found or not readable: ${video.localFilePath}")
                            thumbnailImageView.setImageResource(R.drawable.placeholder_image)
                        }
                    }
                    
                    // Si hay URI de video y es válida
                    !video.videoUriString.isNullOrEmpty() && isValidUri(video.videoUriString) -> {
                        try {
                            val uri = Uri.parse(video.videoUriString)
                            Glide.with(context)
                                .load(uri)
                                .placeholder(R.drawable.placeholder_image)
                                .error(R.drawable.placeholder_image)
                                .centerCrop()
                                .thumbnail(0.5f)
                                .into(thumbnailImageView)
                        } catch (e: SecurityException) {
                            Log.w("YouTubeStyleVideoAdapter", "Permission denied for video URI: ${video.videoUriString}")
                            thumbnailImageView.setImageResource(R.drawable.placeholder_image)
                        } catch (e: Exception) {
                            Log.e("YouTubeStyleVideoAdapter", "Error loading video URI: ${e.message}")
                            thumbnailImageView.setImageResource(R.drawable.placeholder_image)
                        }
                    }
                    
                    else -> {
                        thumbnailImageView.setImageResource(R.drawable.placeholder_image)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                thumbnailImageView.setImageResource(R.drawable.placeholder_image)
            }
        }

        private fun getDurationAndDisplay(video: VideoData) {
            try {
                val videoSource = when {
                    !video.localFilePath.isNullOrEmpty() && File(video.localFilePath).exists() -> {
                        video.localFilePath
                    }
                    !video.videoUriString.isNullOrEmpty() -> {
                        video.videoUriString
                    }
                    else -> null
                }

                if (videoSource != null) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(videoSource)
                        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        retriever.release()
                        
                        duration?.toLongOrNull()?.let { durationMs ->
                            val durationFormatted = formatDuration(durationMs)
                            durationTextView.text = durationFormatted
                            durationTextView.visibility = View.VISIBLE
                        } ?: run {
                            durationTextView.visibility = View.GONE
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        retriever.release()
                        durationTextView.visibility = View.GONE
                    }
                } else {
                    durationTextView.visibility = View.GONE
                }
            } catch (e: Exception) {
                e.printStackTrace()
                durationTextView.visibility = View.GONE
            }
        }        private fun formatDuration(durationMs: Long): String {
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / (1000 * 60)) % 60
            val hours = (durationMs / (1000 * 60 * 60))

            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }
        
        private fun getTimeAgoString(timestamp: Long): String {
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
