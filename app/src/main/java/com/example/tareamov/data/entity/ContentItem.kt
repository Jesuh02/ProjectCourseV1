package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "content_items",
    foreignKeys = [
        ForeignKey(
            entity = Topic::class,
            parentColumns = ["id"],
            childColumns = ["topicId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Task::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Usuario::class,
            parentColumns = ["id"],
            childColumns = ["creator_usuario_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["topicId"]), 
        Index(value = ["taskId"]),
        Index(value = ["creator_usuario_id"]),
        Index(value = ["creator_username"])
    ]
)
data class ContentItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @SerializedName("topic_id") val topicId: Long = 0,
    @SerializedName("task_id") val taskId: Long? = null,
    @SerializedName("title") val name: String? = null,
    @SerializedName("body") val uriString: String = "",
    @SerializedName("content_type") val contentType: String = "",
    @SerializedName("order_index") val orderIndex: Int? = 0,
    val creator_usuario_id: Long? = null,  // ID del usuario creador (FK a usuarios)
    val creator_username: String? = null,  // Username del creador (para búsquedas rápidas)
    val created_at: Long? = System.currentTimeMillis()
) 