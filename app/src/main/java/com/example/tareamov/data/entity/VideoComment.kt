package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * Entity representing video comments stored in the video_comments table in Supabase
 */
@Entity(
    tableName = "video_comments",
    foreignKeys = [
        ForeignKey(
            entity = VideoData::class,
            parentColumns = ["id"],
            childColumns = ["video_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Usuario::class,
            parentColumns = ["id"],
            childColumns = ["usuario_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["video_id"]),
        Index(value = ["usuario_id"])
    ]
)
data class VideoComment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "video_id")
    @SerializedName("video_id")
    val videoId: Long,
    
    @ColumnInfo(name = "usuario_id")
    @SerializedName("usuario_id")
    val usuarioId: Long,
    
    @ColumnInfo(name = "comment")
    @SerializedName("comment")
    val comment: String,
    
    @ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    val createdAt: String? = null
)

/**
 * Data class for displaying comments with user information
 */
data class VideoCommentWithUser(
    val id: Long,
    val videoId: Long,
    val usuarioId: Long,
    val comment: String,
    val createdAt: String?,
    val username: String?,
    val avatar: String?
)
