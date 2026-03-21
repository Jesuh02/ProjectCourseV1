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
                Notification.TYPE_VIDEO_COMMENT, Notification.TYPE_COMMENT_REPLY -> {
                    notificationIcon.setImageResource(R.drawable.ic_comment)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon)
                }
                Notification.TYPE_LIKE -> {
                    notificationIcon.setImageResource(R.drawable.ic_favorite)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon)
                }
                Notification.TYPE_VIDEO_LIKE, Notification.TYPE_COMMENT_LIKE -> {
                    notificationIcon.setImageResource(R.drawable.ic_favorite)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon)
                }
                Notification.TYPE_CHAT_RESPONSE -> {
                    notificationIcon.setImageResource(R.drawable.ic_ai_robot)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon)
                }
                Notification.TYPE_NEW_SUBSCRIBER -> {
                    notificationIcon.setImageResource(R.drawable.ic_person)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon)
                }
                Notification.TYPE_COLLABORATOR_ADDED -> {
                    notificationIcon.setImageResource(R.drawable.ic_person)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon_collaborator)
                }
                Notification.TYPE_ENROLLMENT_REQUEST, Notification.TYPE_ENROLLMENT_APPROVED -> {
                    notificationIcon.setImageResource(R.drawable.ic_school)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon_enrollment)
                }
                else -> {
                    notificationIcon.setImageResource(R.drawable.ic_notifications)
                    iconContainer.setBackgroundResource(R.drawable.bg_notification_icon)
                }
            }

            // Load sender avatar if available
            if (!notification.senderAvatarUrl.isNullOrEmpty()) {
                senderAvatar.visibility = View.VISIBLE
                var avatarUrl = notification.senderAvatarUrl
                if (avatarUrl!!.startsWith("/")) {
                    avatarUrl = "${BackendApiService.baseUrl}$avatarUrl"
                }
                Glide.with(itemView.context)
                    .load(avatarUrl)
                    .placeholder(R.drawable.ic_profile_placeholder)
                    .error(R.drawable.ic_profile_placeholder)
                    .circleCrop()
                    .into(senderAvatar)
            } else {
                senderAvatar.visibility = View.GONE
            }

            // Determine thumbnail URL based on notification type
            val isTaskRelated = notification.type in listOf(
                Notification.TYPE_TASK_GRADED,
                Notification.TYPE_TASK_SUBMISSION,
                Notification.TYPE_NEW_TASK
            )
            val isVideoInteractionRelated = notification.type in listOf(
                Notification.TYPE_VIDEO_LIKE,
                Notification.TYPE_VIDEO_COMMENT,
                Notification.TYPE_COMMENT_REPLY,
                Notification.TYPE_COMMENT_LIKE,
                Notification.TYPE_COMMENT,
                Notification.TYPE_LIKE
            )
            
            if (!notification.thumbnailUrl.isNullOrEmpty()) {
                // Use the provided thumbnail_url from DB (highest priority)
                Log.d("NotificationAdapter", "Loading thumbnail from notification.thumbnailUrl: ${notification.thumbnailUrl}")
                loadThumbnail(notification.thumbnailUrl)
            } else if (isTaskRelated && notification.relatedId != null) {
                // For task-related notifications, load the course thumbnail
                loadCourseThumbnailForTask(notification.relatedId, thumbnailCache)
            } else if (notification.type in listOf(
                    Notification.TYPE_NEW_COURSE,
                    Notification.TYPE_ENROLLMENT_APPROVED,
                    Notification.TYPE_COLLABORATOR_ADDED
                ) && notification.relatedId != null) {
                // For course-related notifications without thumbnail, fetch from course
                loadCourseThumbnailById(notification.relatedId, thumbnailCache)
            } else if (notification.type == Notification.TYPE_NEW_VIDEO && notification.relatedId != null) {
                // For video notifications without thumbnail, fetch from video
                loadVideoThumbnailById(notification.relatedId, thumbnailCache)
            } else if (isVideoInteractionRelated) {
                // For like/comment notifications, always try to resolve the video thumbnail
                val videoId = notification.relatedId ?: extractVideoIdFromMetadata(notification.metadata)
                if (videoId != null) {
                    loadVideoThumbnailById(videoId, thumbnailCache)
                } else if (!notification.senderAvatarUrl.isNullOrEmpty()) {
                    loadThumbnail(notification.senderAvatarUrl)
                } else {
                    thumbnailImage.setImageResource(R.drawable.bg_course_placeholder_card)
                }
            } else if (!notification.senderAvatarUrl.isNullOrEmpty()) {
                // Fallback to sender avatar
                loadThumbnail(notification.senderAvatarUrl)
            } else {
                // No thumbnail available, show placeholder
                thumbnailImage.setImageResource(R.drawable.bg_course_placeholder_card)
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
                    
                    // Cache the result (ConcurrentHashMap doesn't allow nulls, use empty string)
                    thumbnailCache[taskId] = courseThumbnail ?: ""
                    
                    // Update UI on main thread
                    withContext(Dispatchers.Main) {
                        loadThumbnail(courseThumbnail)
                    }
                } catch (e: Exception) {
                    Log.e("NotificationAdapter", "Error loading course thumbnail for task $taskId", e)
                    // Cache empty string to avoid repeated failed requests
                    thumbnailCache[taskId] = ""
                }
            }
        }

        /**
         * Load course thumbnail by course ID for new_course notifications
         */
        private fun loadCourseThumbnailById(
            courseId: Long,
            thumbnailCache: java.util.concurrent.ConcurrentHashMap<Long, String?>
        ) {
            val cacheKey = -courseId // Negative key to distinguish from task IDs
            if (thumbnailCache.containsKey(cacheKey)) {
                val cachedUrl = thumbnailCache[cacheKey]
                loadThumbnail(cachedUrl)
                return
            }

            thumbnailImage.setImageResource(R.drawable.bg_course_placeholder_card)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val course = BackendApiService.getCourseById(courseId).getOrNull()
                    val courseThumbnail = course?.thumbnailUri
                    thumbnailCache[cacheKey] = courseThumbnail ?: ""

                    withContext(Dispatchers.Main) {
                        loadThumbnail(courseThumbnail)
                    }
                } catch (e: Exception) {
                    Log.e("NotificationAdapter", "Error loading thumbnail for course $courseId", e)
                    thumbnailCache[cacheKey] = ""
                }
            }
        }

        /**
         * Load video thumbnail by video ID for new_video notifications
         */
        private fun loadVideoThumbnailById(
            videoId: Long,
            thumbnailCache: java.util.concurrent.ConcurrentHashMap<Long, String?>
        ) {
            val cacheKey = -(videoId + 1_000_000) // Unique key space to avoid collisions
            if (thumbnailCache.containsKey(cacheKey)) {
                val cachedUrl = thumbnailCache[cacheKey]
                loadThumbnail(cachedUrl)
                return
            }

            thumbnailImage.setImageResource(R.drawable.bg_course_placeholder_card)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = BackendApiService.getVideoById(videoId)
                    val thumbnailUrl = if (result is com.example.tareamov.service.ApiResult.Success) {
                        result.data?.thumbnailUri
                    } else null
                    thumbnailCache[cacheKey] = thumbnailUrl ?: ""

                    withContext(Dispatchers.Main) {
                        loadThumbnail(thumbnailUrl)
                    }
                } catch (e: Exception) {
                    Log.e("NotificationAdapter", "Error loading thumbnail for video $videoId", e)
                    thumbnailCache[cacheKey] = ""
                }
            }
        }
        
        /**
         * Load thumbnail image with Glide
         */
        private fun loadThumbnail(url: String?) {
            var finalUrl = url?.trim()
            if (!finalUrl.isNullOrEmpty()) {
                if (finalUrl!!.startsWith("/")) {
                     finalUrl = "${BackendApiService.baseUrl}$finalUrl"
                } else if (!finalUrl!!.startsWith("http") && !finalUrl!!.startsWith("content://") && !finalUrl!!.startsWith("file://")) {
                     finalUrl = "${BackendApiService.baseUrl}/$finalUrl"
                }
            }

            Glide.with(itemView.context)
                .load(finalUrl)
                .apply(RequestOptions().transform(RoundedCorners(16)))
                .placeholder(R.drawable.bg_course_placeholder_card)
                .error(R.drawable.bg_course_placeholder_card)
                .centerCrop()
                .into(thumbnailImage)
        }

        private fun extractVideoIdFromMetadata(metadata: String?): Long? {
            if (metadata.isNullOrBlank()) return null

            return try {
                val normalized = metadata.trim()
                if (normalized.startsWith("{")) {
                    val json = org.json.JSONObject(normalized)
                    if (json.has("video_id")) {
                        val v = json.get("video_id")
                        return if (v is Number) v.toLong() else v.toString().toLongOrNull()
                    }
                    if (json.has("videoId")) {
                        val v = json.get("videoId")
                        return if (v is Number) v.toLong() else v.toString().toLongOrNull()
                    }
                }

                val regex = Regex("""(?:video_id|videoId)\s*[:=]\s*\"?(\d+)\"?""")
                regex.find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()
            } catch (_: Exception) {
                null
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
