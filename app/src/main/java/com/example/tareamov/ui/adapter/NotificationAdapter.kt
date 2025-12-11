package com.example.tareamov.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.Notification
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class NotificationAdapter(
    private val onNotificationClick: (Notification) -> Unit
) : ListAdapter<Notification, NotificationAdapter.NotificationViewHolder>(NotificationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return NotificationViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        val notification = getItem(position)
        holder.bind(notification, onNotificationClick)
    }

    class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val senderAvatar: ImageView = itemView.findViewById(R.id.senderAvatar)
        private val titleText: TextView = itemView.findViewById(R.id.titleText)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val timeText: TextView = itemView.findViewById(R.id.timeText)
        private val thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnailImage)
        private val unreadIndicator: View = itemView.findViewById(R.id.unreadIndicator)

        fun bind(notification: Notification, onClick: (Notification) -> Unit) {
            titleText.text = notification.title
            messageText.text = notification.message
            timeText.text = formatTime(notification.createdAt)

            // Show/hide unread indicator
            unreadIndicator.visibility = if (notification.isRead) View.GONE else View.VISIBLE

            // Load sender avatar with Glide
            Glide.with(itemView.context)
                .load(notification.senderAvatarUrl)
                .placeholder(R.drawable.default_avatar)
                .error(R.drawable.default_avatar)
                .circleCrop()
                .into(senderAvatar)

            // Load thumbnail with Glide
            Glide.with(itemView.context)
                .load(notification.thumbnailUrl)
                .placeholder(R.drawable.placeholder_image)
                .error(R.drawable.placeholder_image)
                .centerCrop()
                .into(thumbnailImage)

            itemView.setOnClickListener {
                onClick(notification)
            }
        }

        private fun formatTime(createdAt: String?): String {
            if (createdAt == null) return ""

            try {
                val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                val date = sdf.parse(createdAt) ?: return ""

                val now = System.currentTimeMillis()
                val diff = now - date.time

                return when {
                    diff < TimeUnit.MINUTES.toMillis(1) -> "hace menos de 1 minuto"
                    diff < TimeUnit.HOURS.toMillis(1) -> {
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                        "hace $minutes minuto${if (minutes > 1) "s" else ""}"
                    }
                    diff < TimeUnit.DAYS.toMillis(1) -> {
                        val hours = TimeUnit.MILLISECONDS.toHours(diff)
                        "hace $hours hora${if (hours > 1) "s" else ""}"
                    }
                    diff < TimeUnit.DAYS.toMillis(7) -> {
                        val days = TimeUnit.MILLISECONDS.toDays(diff)
                        "hace $days día${if (days > 1) "s" else ""}"
                    }
                    else -> {
                        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(date)
                    }
                }
            } catch (e: Exception) {
                return ""
            }
        }
    }

    class NotificationDiffCallback : DiffUtil.ItemCallback<Notification>() {
        override fun areItemsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Notification, newItem: Notification): Boolean {
            return oldItem == newItem
        }
    }
}
