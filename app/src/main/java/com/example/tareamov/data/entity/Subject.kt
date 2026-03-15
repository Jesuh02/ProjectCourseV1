package com.example.tareamov.data.entity

import com.google.gson.annotations.SerializedName

data class Subject(
    val id: Long = 0,
    @SerializedName("course_id")
    val courseId: Long = 0,
    val name: String = "",
    val description: String = "",
    val code: String? = null,
    @SerializedName("thumbnail_url")
    val thumbnailUrl: String? = null,
    @SerializedName("order_index")
    val orderIndex: Int = 0,
    @SerializedName("is_active")
    val isActive: Boolean = true,
    @SerializedName("created_at")
    val createdAt: String? = null,
    @SerializedName("updated_at")
    val updatedAt: String? = null,
    @SerializedName("created_by")
    val createdBy: Long? = null,
    @SerializedName("updated_by")
    val updatedBy: Long? = null
)
