package com.example.tareamov.ui.adapter

import android.util.Log
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
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class NotificationAdapter(
    private val onNotificationClick: (Notification) -> Unit
) : ListAdapter<Notification, NotificationAdapter.NotificationViewHolder>(NotificationDiffCallback()) {

    // Cache for course thumbnails by taskId to avoid repeated network calls
    private val courseThumbnailCache = java.util.concurrent.ConcurrentHashMap<Long, String?>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NotificationViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notification, parent, false)
        return NotificationViewHolder(view)
    }

    override fun onBindViewHolder(holder: NotificationViewHolder, position: Int) {
        val notification = getItem(position)
        holder.bind(notification, onNotificationClick, courseThumbnailCache)
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

        fun bind(
            notification: Notification, 
            onClick: (Notification) -> Unit,
            thumbnailCache: java.util.concurrent.ConcurrentHashMap<Long, String?>
        ) {
            // Use the actual notification title or fallback to "Notificación"
            titleText.text = notification.title.ifEmpty { "Notificación" }
            
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
                Notification.TYPE_TASK_SUBMISSION -> {
                    notificationIcon.setImageResource(R.drawable.ic_assignment)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon_task)
                }
                Notification.TYPE_NEW_TASK -> {
                    notificationIcon.setImageResource(R.drawable.ic_assignment)
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

            // Determine thumbnail URL based on notification type
            val isTaskRelated = notification.type in listOf(
                Notification.TYPE_TASK_GRADED,
                Notification.TYPE_TASK_SUBMISSION,
                Notification.TYPE_NEW_TASK
            )
            
            if (isTaskRelated && notification.relatedId != null) {
                // For task-related notifications, load the course thumbnail
                loadCourseThumbnailForTask(notification.relatedId, thumbnailCache)
            } else {
                // For other notifications, use the provided thumbnail or avatar
                val thumbnailUrl = notification.thumbnailUrl ?: notification.senderAvatarUrl
                loadThumbnail(thumbnailUrl)
            }

            itemView.setOnClickListener {
                onClick(notification)
            }
        }
        
        /**
         * Load course thumbnail for task-related notifications
         * Uses cache to avoid repeated network calls
         */
        private fun loadCourseThumbnailForTask(
            taskId: Long,
            thumbnailCache: java.util.concurrent.ConcurrentHashMap<Long, String?>
        ) {
            // Check cache first
            if (thumbnailCache.containsKey(taskId)) {
                val cachedUrl = thumbnailCache[taskId]
                loadThumbnail(cachedUrl)
                return
            }
            
            // Load placeholder while fetching
            thumbnailImage.setImageResource(R.drawable.placeholder_image)
            
            // Fetch course thumbnail asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Get task -> topic -> course -> thumbnail
                    val task = BackendApiService.getTaskById(taskId).getOrNull()
                    val topic = task?.let { BackendApiService.getTopicById(it.topicId).getOrNull() }
                    val course = topic?.let { BackendApiService.getCourseById(it.courseId).getOrNull() }
                    val courseThumbnail = course?.thumbnailUri
                    
                    // Cache the result (even if null)
                    thumbnailCache[taskId] = courseThumbnail
                    
                    // Update UI on main thread
                    withContext(Dispatchers.Main) {
                        loadThumbnail(courseThumbnail)
                    }
                } catch (e: Exception) {
                    Log.e("NotificationAdapter", "Error loading course thumbnail for task $taskId", e)
                    // Cache null to avoid repeated failed requests
                    thumbnailCache[taskId] = null
                }
            }
        }
        
        /**
         * Load thumbnail image with Glide
         */
        private fun loadThumbnail(url: String?) {
            Glide.with(itemView.context)
                .load(url)
                .apply(RequestOptions().transform(RoundedCorners(16)))
                .placeholder(R.drawable.placeholder_image)
                .error(R.drawable.placeholder_image)
                .centerCrop()
                .into(thumbnailImage)
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
