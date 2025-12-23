package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "notifications",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = Usuario::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index("user_id")]
)
data class Notification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @androidx.room.ColumnInfo(name = "user_id")
    @SerializedName("user_id")
    val userId: Long,
    
    val type: String,
    val title: String,
    val message: String,
    
    @androidx.room.ColumnInfo(name = "sender_username")
    @SerializedName("sender_username")
    val senderUsername: String? = null,
    
    @androidx.room.ColumnInfo(name = "sender_avatar_url")
    @SerializedName("sender_avatar_url")
    val senderAvatarUrl: String? = null,
    
    @androidx.room.ColumnInfo(name = "thumbnail_url")
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String? = null,
    
    @androidx.room.ColumnInfo(name = "related_id")
    @SerializedName("related_id")
    val relatedId: Long? = null,
    
    @androidx.room.ColumnInfo(name = "is_read")
    @SerializedName("is_read")
    val isRead: Boolean = false,
    
    @androidx.room.ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    val createdAt: String? = null,
    
    @androidx.room.ColumnInfo(name = "updated_at")
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
