package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "courses")
data class Course(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String = "",
    val description: String = "",

    @SerializedName("creator_user_id")
    @ColumnInfo(name = "creator_user_id")
    val creatorUserId: Long = 0,

    @SerializedName(value = "thumbnailUri", alternate = ["thumbnail_uri", "thumbnail_url"])
    @ColumnInfo(name = "thumbnail_uri")
    val thumbnailUri: String? = null,

    @SerializedName(value = "video_uri", alternate = ["videoUri", "video_uri_string"])
    @ColumnInfo(name = "video_uri")
    val videoUri: String? = null, // Maps to VideoData.videoUriString

    @SerializedName("local_file_path")
    @ColumnInfo(name = "local_file_path")
    val localFilePath: String? = null,

    val duration: String? = null,

    val category: String? = null,

    val price: Double = 0.0,

    @SerializedName("is_premium")
    @ColumnInfo(name = "is_premium")
    val isPremium: Boolean = false, // Maps to VideoData.isPaid

    @SerializedName("is_published")
    @ColumnInfo(name = "is_published")
    val isPublished: Boolean = true,

    @SerializedName(value = "isActive", alternate = ["is_active"])
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,

    @SerializedName("creation_date")
    @ColumnInfo(name = "creation_date")
    val creationDate: String = "", // Server-side text date

    @SerializedName("last_modified_date")
    @ColumnInfo(name = "last_modified_date")
    val lastModifiedDate: String = "",

    // Server-side timestamp (epoch seconds as bigint)
    val timestamp: Long = System.currentTimeMillis(),

    @SerializedName("created_at")
    @ColumnInfo(name = "created_at")
    val createdAt: String? = null,

    // Local-only properties kept for app logic/UI
    val enrollmentCount: Int = 0,
    val rating: Float = 0.0f,
    val tags: String? = null, // Comma-separated tags

    @SerializedName("deadline")
    @ColumnInfo(name = "deadline")
    val deadline: String? = null,

    @SerializedName(value = "creator_username", alternate = ["creatorUsername"])
    @ColumnInfo(name = "creator_username")
    val creatorUsername: String? = null
)
