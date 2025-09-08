package com.example.tareamov.data.entity

import java.util.Date

data class UserContent(
    val id: Long,
    val title: String,
    val description: String?,
    val type: ContentType,
    val thumbnailPath: String?,
    val createdDate: Date,
    val userId: String
)

enum class ContentType {
    COURSE,
    VIDEO
}
