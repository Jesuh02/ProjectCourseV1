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
            childColumns = ["topicId"], // Changed from courseId
            onDelete = ForeignKey.CASCADE // Delete tasks if the parent topic is deleted
        )
    ],
    indices = [Index(value = ["topicId"])] // Index for faster queries by topicId
)
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @SerializedName("topic_id")
    val topicId: Long = 0,
    @SerializedName("title") val name: String = "",
    val description: String? = null,
    @SerializedName("order_index")
    val orderIndex: Int = 0,
    @SerializedName("due_date")
    val dueDate: String? = null
)