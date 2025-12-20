package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String,
    @androidx.room.ColumnInfo(name = "creator_user_id")
    @SerializedName("creator_user_id")
    val creatorUserId: Long, // Foreign key to usuarios.id
    @androidx.room.ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String? = null,
    @androidx.room.ColumnInfo(name = "video_uri")
    val videoUri: String? = null, // Maps to VideoData.videoUriString
    @androidx.room.ColumnInfo(name = "local_file_path")
    val localFilePath: String? = null,
    val duration: String? = null,
    val category: String? = null,
    val price: Double = 0.0,
    @androidx.room.ColumnInfo(name = "is_premium")
    val isPremium: Boolean = false, // Maps to VideoData.isPaid
    @androidx.room.ColumnInfo(name = "is_published")
    val isPublished: Boolean = true,
    @androidx.room.ColumnInfo(name = "creation_date")
    val creationDate: String = "", // Maps to VideoData.timestamp
    @androidx.room.ColumnInfo(name = "last_modified_date")
    val lastModifiedDate: String = "",
    @androidx.room.ColumnInfo(name = "enrollment_count")
    val enrollmentCount: Int = 0,
    val rating: Float = 0.0f,
    val tags: String? = null, // Comma-separated tags
    val timestamp: Long = System.currentTimeMillis(), // Direct mapping from VideoData.timestamp
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
