package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * Entity representing the video_likes table in Supabase
 * Tracks the total like count for each video
 */
@Entity(
    tableName = "video_likes",
    foreignKeys = [
        ForeignKey(
            entity = VideoData::class,
            parentColumns = ["id"],
            childColumns = ["video_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["video_id"], unique = true)
    ]
)
data class VideoLike(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "video_id")
    @SerializedName("video_id")
    val videoId: Long,
    
    @ColumnInfo(name = "like_count")
    @SerializedName("like_count")
    val likeCount: Int = 0
)

/**
 * Entity to track individual user likes on videos
 * This is stored locally to know if current user has liked a video
 */
@Entity(
    tableName = "user_video_likes",
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
        Index(value = ["usuario_id"]),
        Index(value = ["video_id", "usuario_id"], unique = true)
    ]
)
data class UserVideoLike(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    @ColumnInfo(name = "video_id")
    @SerializedName("video_id")
    val videoId: Long,
    
    @ColumnInfo(name = "usuario_id")
    @SerializedName("usuario_id")
    val usuarioId: Long,
    
    @ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    val createdAt: String? = null
)
