package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val topicId: Long = 0,
    val name: String = "",
    val description: String? = null,
    val orderIndex: Int = 0
) {
    // Constructor sin argumentos para Firestore
    constructor() : this(0, 0, "", null, 0)
}