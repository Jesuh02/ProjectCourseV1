package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "notifications")
data class Notification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @SerializedName("user_id")
    val userId: Long,
    
    val type: String,
    val title: String,
    val message: String,
    
    @SerializedName("sender_username")
    val senderUsername: String? = null,
    
    @SerializedName("sender_avatar_url")
    val senderAvatarUrl: String? = null,
    
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String? = null,
    
    @SerializedName("related_id")
    val relatedId: Long? = null,
    
    @SerializedName("is_read")
    val isRead: Boolean = false,
    
    @SerializedName("created_at")
    val createdAt: String? = null,
    
    @SerializedName("updated_at")
    val updatedAt: String? = null
) {
    companion object {
        const val TYPE_NEW_COURSE = "new_course"
        const val TYPE_NEW_VIDEO = "new_video"
        const val TYPE_TASK_GRADED = "task_graded"
        const val TYPE_COMMENT = "comment"
        const val TYPE_LIKE = "like"
        const val TYPE_NEW_TASK = "new_task"
        const val TYPE_TASK_SUBMISSION = "task_submission"
    }
}
