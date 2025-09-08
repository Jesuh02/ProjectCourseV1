package com.example.tareamov.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.ContentType
import com.example.tareamov.data.entity.UserContent
import java.text.SimpleDateFormat
import java.util.Locale

class UserContentAdapter(
    private var contentList: List<UserContent>,
    private val onItemClick: (UserContent) -> Unit
) : RecyclerView.Adapter<UserContentAdapter.ContentViewHolder>() {

    class ContentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnailImageView: ImageView = itemView.findViewById(R.id.contentThumbnailImageView)
        val titleTextView: TextView = itemView.findViewById(R.id.contentTitleTextView)
        val descriptionTextView: TextView = itemView.findViewById(R.id.contentDescriptionTextView)
        val typeTextView: TextView = itemView.findViewById(R.id.contentTypeTextView)
        val dateTextView: TextView = itemView.findViewById(R.id.contentDateTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ContentViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_content, parent, false)
        return ContentViewHolder(view)
    }

    override fun onBindViewHolder(holder: ContentViewHolder, position: Int) {
        val content = contentList[position]
        val context = holder.itemView.context
        
        // Configurar título
        holder.titleTextView.text = content.title
        
        // Configurar descripción
        holder.descriptionTextView.text = content.description ?: "Sin descripción"
        
        // Configurar tipo de contenido
        when (content.type) {
            ContentType.COURSE -> {
                holder.typeTextView.text = "CURSO"
                holder.typeTextView.setBackgroundResource(R.drawable.content_type_badge)
            }
            ContentType.VIDEO -> {
                holder.typeTextView.text = "VIDEO"
                holder.typeTextView.setBackgroundResource(R.drawable.content_type_badge)
            }
        }
        
        // Configurar fecha
        val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        holder.dateTextView.text = dateFormat.format(content.createdDate)
        
        // Configurar thumbnail
        if (!content.thumbnailPath.isNullOrEmpty()) {
            Glide.with(context)
                .load(content.thumbnailPath)
                .placeholder(getDefaultThumbnail(content.type))
                .error(getDefaultThumbnail(content.type))
                .into(holder.thumbnailImageView)
        } else {
            holder.thumbnailImageView.setImageResource(getDefaultThumbnail(content.type))
        }
        
        // Configurar click listener
        holder.itemView.setOnClickListener {
            onItemClick(content)
        }
    }

    override fun getItemCount(): Int = contentList.size

    fun updateContent(newContentList: List<UserContent>) {
        contentList = newContentList
        notifyDataSetChanged()
    }

    private fun getDefaultThumbnail(type: ContentType): Int {
        return when (type) {
            ContentType.COURSE -> R.drawable.ic_courses
            ContentType.VIDEO -> R.drawable.ic_play_arrow
        }
    }
}
