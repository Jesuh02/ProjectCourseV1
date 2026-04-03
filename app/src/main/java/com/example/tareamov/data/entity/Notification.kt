package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "notifications")
data class Notification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "user_id")
    @SerializedName("user_id")
    val userId: Long,
    
    val type: String,
    val title: String,
    val message: String,
    
    @ColumnInfo(name = "sender_username")
    @SerializedName("sender_username")
    val senderUsername: String? = null,
    
    @ColumnInfo(name = "sender_avatar_url")
    @SerializedName("sender_avatar_url")
    val senderAvatarUrl: String? = null,
    
    @ColumnInfo(name = "thumbnail_url")
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String? = null,
    
    @ColumnInfo(name = "related_id")
    @SerializedName("related_id")
    val relatedId: Long? = null,
    
    // Metadata JSON para datos adicionales (ej: comment_id, reply_id, etc.)
    @ColumnInfo(name = "metadata")
    @SerializedName("metadata")
    val metadata: String? = null,
    
    @ColumnInfo(name = "is_read")
    @SerializedName("is_read")
    val isRead: Boolean = false,
    
    @ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    val createdAt: String? = null,
    
    @ColumnInfo(name = "updated_at")
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
        // Additional comment/like subtypes from backend
        const val TYPE_VIDEO_COMMENT = "video_comment"
        const val TYPE_COMMENT_REPLY = "comment_reply"
        const val TYPE_VIDEO_LIKE = "video_like"
        const val TYPE_COMMENT_LIKE = "comment_like"
        const val TYPE_CHAT_RESPONSE = "chat_response"
        const val TYPE_NEW_SUBSCRIBER = "new_subscriber"
        const val TYPE_COLLABORATOR_ADDED = "collaborator_added"
        const val TYPE_ENROLLMENT_REQUEST = "enrollment_request"
        const val TYPE_ENROLLMENT_APPROVED = "enrollment_approved"
        const val TYPE_SUBJECT_ACCESS_BLOCKED = "subject_access_blocked"
        const val TYPE_COURSE_INVITATION = "course_invitation"
        const val TYPE_ENROLLMENT_REVOKED = "enrollment_revoked"
    }
}
