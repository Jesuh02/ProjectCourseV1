package com.example.tareamov.adapter

import android.content.Context
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
import com.example.tareamov.data.entity.ContentType
import com.example.tareamov.data.entity.VideoData
import java.io.File

class SearchResultAdapter(
    private val context: Context,
    private var searchResults: MutableList<Pair<VideoData, ContentType>> = mutableListOf(),
    private val onItemClickListener: (VideoData, ContentType) -> Unit
) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (item, type) = searchResults[position]
        holder.bind(item, type)
    }

    override fun getItemCount(): Int = searchResults.size

    fun updateResults(newResults: List<Pair<VideoData, ContentType>>) {
        searchResults.clear()
        searchResults.addAll(newResults)
        notifyDataSetChanged()
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnailImageView: ImageView = itemView.findViewById(R.id.searchItemThumbnail)
        private val titleTextView: TextView = itemView.findViewById(R.id.searchItemTitle)
        private val usernameTextView: TextView = itemView.findViewById(R.id.searchItemUsername)
        private val typeIconImageView: ImageView = itemView.findViewById(R.id.searchItemTypeIcon)

        init {
            itemView.setOnClickListener {
                val position = adapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val (item, type) = searchResults[position]
                    onItemClickListener(item, type)
                }
            }
        }        fun bind(item: VideoData, type: ContentType) {
            titleTextView.text = item.title
            
            // Show more detailed information including category info
            val categoryInfo = when (type) {
                ContentType.COURSE -> if (item.isPaid) "Curso (Pago)" else "Curso (Gratis)"
                ContentType.VIDEO -> "Video"
            }
            usernameTextView.text = "${item.username} • $categoryInfo"

            // Set type icon based on content type
            val typeIconRes = when (type) {
                ContentType.COURSE -> R.drawable.ic_course_type
                ContentType.VIDEO -> R.drawable.ic_video_type
            }
            typeIconImageView.setImageResource(typeIconRes)

            // Load thumbnail
            loadThumbnail(item)
        }        private fun loadThumbnail(item: VideoData) {
            try {
                // First, try to load from thumbnail URI if it's a local file or valid content URI
                if (item.thumbnailUri != null && isValidUri(item.thumbnailUri)) {
                    Glide.with(context)
                        .load(item.thumbnailUri)
                        .centerCrop()
                        .placeholder(R.drawable.placeholder_image)
                        .error(R.drawable.placeholder_image)
                        .into(thumbnailImageView)
                    return
                }

                // Fallback to video URI if it's valid
                if (item.videoUri != null && isValidUri(item.videoUri.toString())) {
                    Glide.with(context)
                        .load(item.videoUri)
                        .centerCrop()
                        .placeholder(R.drawable.placeholder_image)
                        .error(R.drawable.placeholder_image)
                        .into(thumbnailImageView)
                    return
                }

                // Try local file path if available
                if (item.localFilePath != null && item.localFilePath.isNotEmpty()) {
                    val file = File(item.localFilePath)
                    if (file.exists() && file.canRead()) {
                        Glide.with(context)
                            .load(file)
                            .centerCrop()
                            .placeholder(R.drawable.placeholder_image)
                            .error(R.drawable.placeholder_image)
                            .into(thumbnailImageView)
                        return
                    }
                }            } catch (e: SecurityException) {
                // Handle permission denied errors
                Log.w("SearchResultAdapter", "Permission denied when loading thumbnail: ${e.message}")
            } catch (e: Exception) {
                // Handle any other errors
                Log.w("SearchResultAdapter", "Error loading thumbnail: ${e.message}")
            }

            // Default placeholder if all loading attempts fail
            thumbnailImageView.setImageResource(R.drawable.placeholder_image)
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
