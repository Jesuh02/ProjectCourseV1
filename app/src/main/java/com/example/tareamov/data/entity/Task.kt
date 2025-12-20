package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = Topic::class, // Link Task to Topic
            parentColumns = ["id"],
            childColumns = ["topic_id"], // Changed from courseId
            onDelete = ForeignKey.CASCADE // Delete tasks if the parent topic is deleted
        )
    ],
    indices = [Index(value = ["topic_id"])] // Index for faster queries by topicId
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @androidx.room.ColumnInfo(name = "topic_id")
    val topicId: Long = 0,
    @androidx.room.ColumnInfo(name = "title")
    @SerializedName("title") val name: String = "",
    val description: String? = null,
    @androidx.room.ColumnInfo(name = "due_date")
    val dueDate: Long? = null,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)