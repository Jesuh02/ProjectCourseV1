package com.example.tareamov.data.entity

import com.google.gson.annotations.SerializedName

data class Subject(
    val id: Long = 0,
    @SerializedName(value = "courseId", alternate = ["course_id"])
    val courseId: Long = 0,
    val name: String = "",
    val description: String = "",
    val code: String? = null,
    @SerializedName(value = "thumbnailUrl", alternate = ["thumbnail_url"])
    val thumbnailUrl: String? = null,
    @SerializedName(value = "orderIndex", alternate = ["order_index"])
    val orderIndex: Int = 0,
    @SerializedName(value = "isActive", alternate = ["is_active"])
    val isActive: Boolean = true,
    @SerializedName(value = "createdAt", alternate = ["created_at"])
    val createdAt: String? = null,
    @SerializedName(value = "updatedAt", alternate = ["updated_at"])
    val updatedAt: String? = null,
    @SerializedName(value = "createdBy", alternate = ["created_by"])
    val createdBy: Long? = null,
    @SerializedName(value = "updatedBy", alternate = ["updated_by"])
    val updatedBy: Long? = null,
    val blocked: Boolean = false,
    @SerializedName(value = "blockReason", alternate = ["block_reason"])
    val blockReason: String? = null
)
