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
            childColumns = ["topic_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Task::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
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
        Index(value = ["topic_id"]), 
        Index(value = ["task_id"]),
        Index(value = ["creator_usuario_id"]),
        Index(value = ["creator_username"])
    ]
)
data class ContentItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "topic_id")
    @SerializedName("topic_id") val topicId: Long = 0,
    @androidx.room.ColumnInfo(name = "task_id")
    @SerializedName("task_id") val taskId: Long? = null,
    @androidx.room.ColumnInfo(name = "title")
    @SerializedName("title") val title: String? = null,
    @androidx.room.ColumnInfo(name = "body")
    @SerializedName("body") val body: String = "",
    @androidx.room.ColumnInfo(name = "content_type")
    @SerializedName("content_type") val contentType: String = "",
    @androidx.room.ColumnInfo(name = "order_index")
    @SerializedName("order_index") val orderIndex: Int? = 0,
    @androidx.room.ColumnInfo(name = "creator_usuario_id")
    val creator_usuario_id: Long? = null,  // ID del usuario creador (FK a usuarios)
    @androidx.room.ColumnInfo(name = "creator_username")
    val creator_username: String? = null,  // Username del creador (para búsquedas rápidas)
    @androidx.room.ColumnInfo(name = "created_at")
    val created_at: Long? = System.currentTimeMillis()
) 