package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
            entity = Task::class, // Add foreign key for Task
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE // Delete content if the parent task is deleted
        )
    ],
    // Index both topicId and taskId for efficient querying
    indices = [Index(value = ["topicId"]), Index(value = ["taskId"])]
)
data class ContentItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val topicId: Long = 0,
    val taskId: Long? = null,
    val name: String? = null,
    val uriString: String = "",
    val contentType: String = "",
    val orderIndex: Int? = 0
) {
    // Constructor sin argumentos para Firestore
    constructor() : this(0, 0, null, null, "", "", 0)
}