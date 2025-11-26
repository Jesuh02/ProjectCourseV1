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
    @SerializedName("creator_user_id")
    val creatorUserId: Long, // Foreign key to usuarios.id
    val thumbnailUri: String? = null,
    val videoUri: String? = null, // Maps to VideoData.videoUriString
    val localFilePath: String? = null,
    val duration: String? = null,
    val category: String? = null,
    val price: Double = 0.0,
    val isPremium: Boolean = false, // Maps to VideoData.isPaid
    val isPublished: Boolean = true,
    val creationDate: String = "", // Maps to VideoData.timestamp
    val lastModifiedDate: String = "",
    val enrollmentCount: Int = 0,
    val rating: Float = 0.0f,
    val tags: String? = null, // Comma-separated tags
    val timestamp: Long = System.currentTimeMillis() // Direct mapping from VideoData.timestamp
)
