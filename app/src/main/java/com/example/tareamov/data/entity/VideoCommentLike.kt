package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.google.gson.annotations.SerializedName

/**
 * Polymorphic Like entity - supports likes on any entity type.
 * Table: likes
 * 
 * Supported entity types:
 * - "video" -> likes on videos (entity_id = video.id)
 * - "comment" -> likes on video comments (entity_id = video_comment.id)
 * - "course" -> likes on courses (entity_id = course.id)
 * - "task" -> likes on tasks (entity_id = task.id)
 * 
 * Primary key is composite (usuario_id, entity_type, entity_id) to prevent duplicate likes.
 */
@Entity(
    tableName = "likes",
    primaryKeys = ["usuario_id", "entity_type", "entity_id"],
    foreignKeys = [
        ForeignKey(
            entity = Usuario::class,
            parentColumns = ["id"],
            childColumns = ["usuario_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["usuario_id"]),
        Index(value = ["entity_type", "entity_id"])
    ]
)
data class Like(
    @ColumnInfo(name = "usuario_id")
    @SerializedName("usuario_id")
    val usuarioId: Long,
    
    @ColumnInfo(name = "entity_type")
    @SerializedName("entity_type")
    val entityType: String,
    
    @ColumnInfo(name = "entity_id")
    @SerializedName("entity_id")
    val entityId: Long,
    
    @ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    val createdAt: String? = null
) {
    companion object {
        // Entity type constants
        const val TYPE_VIDEO = "video"
        const val TYPE_COMMENT = "comment"
        const val TYPE_COURSE = "course"
        const val TYPE_TASK = "task"
    }
}

/**
 * Data class for comment with like count and user's like status
 */
data class VideoCommentWithLikes(
    val id: Long,
    val videoId: Long,
    val usuarioId: Long,
    val comment: String,
    val parentId: Long?,
    val createdAt: String?,
    val username: String?,
    val avatar: String?,
    val likeCount: Int,
    val isLikedByCurrentUser: Boolean
)

/**
 * Generic data class for any entity with like info
 */
data class EntityWithLikes<T>(
    val entity: T,
    val likeCount: Int,
    val isLikedByCurrentUser: Boolean
)

// Legacy type alias for backward compatibility
@Deprecated("Use Like instead", ReplaceWith("Like"))
typealias VideoCommentLike = Like
