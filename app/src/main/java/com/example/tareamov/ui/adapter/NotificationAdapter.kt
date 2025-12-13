package com.example.tareamov.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
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
        private val iconContainer: FrameLayout = itemView.findViewById(R.id.iconContainer)
        private val notificationIcon: ImageView = itemView.findViewById(R.id.notificationIcon)
        private val senderAvatar: ImageView = itemView.findViewById(R.id.senderAvatar)
        private val titleText: TextView = itemView.findViewById(R.id.titleText)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val timeText: TextView = itemView.findViewById(R.id.timeText)
        private val thumbnailImage: ImageView = itemView.findViewById(R.id.thumbnailImage)
        private val unreadIndicator: View = itemView.findViewById(R.id.unreadIndicator)

        fun bind(notification: Notification, onClick: (Notification) -> Unit) {
            // Set "Para ti" as the category label
            titleText.text = "Para ti"
            
            // Set the notification message
            messageText.text = notification.message
            timeText.text = formatTime(notification.createdAt)

            // Show/hide unread indicator
            unreadIndicator.visibility = if (notification.isRead) View.GONE else View.VISIBLE

            // Set icon based on notification type
            when (notification.type) {
                Notification.TYPE_NEW_COURSE -> {
                    notificationIcon.setImageResource(R.drawable.ic_school)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon_course)
                }
                Notification.TYPE_NEW_VIDEO -> {
                    notificationIcon.setImageResource(R.drawable.ic_play_circle)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon_video)
                }
                Notification.TYPE_TASK_GRADED -> {
                    notificationIcon.setImageResource(R.drawable.ic_assignment_turned_in)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon_task)
                }
                Notification.TYPE_COMMENT -> {
                    notificationIcon.setImageResource(R.drawable.ic_comment)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon)
                }
                Notification.TYPE_LIKE -> {
                    notificationIcon.setImageResource(R.drawable.ic_favorite)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon)
                }
                else -> {
                    notificationIcon.setImageResource(R.drawable.ic_notifications)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon)
                }
            }

            // Load thumbnail with rounded corners
            val thumbnailUrl = notification.thumbnailUrl ?: notification.senderAvatarUrl
            Glide.with(itemView.context)
                .load(thumbnailUrl)
                .apply(RequestOptions().transform(RoundedCorners(16)))
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
                    diff < TimeUnit.MINUTES.toMillis(1) -> "hace menos de 1 min"
                    diff < TimeUnit.HOURS.toMillis(1) -> {
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                        "hace $minutes min"
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
