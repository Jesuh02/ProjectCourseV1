package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "task_submissions",
    foreignKeys = [
        ForeignKey(
            entity = Task::class,
            parentColumns = ["id"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("taskId"),
        Index("studentUsername")
    ]
)
data class TaskSubmission(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val taskId: Long = 0,
    val studentUsername: String = "",
    val submissionDate: Long = 0,
    val fileUri: String = "",
    val fileName: String = "",
    val grade: Float? = null,
    val feedback: String? = null
) {
    // Constructor sin argumentos para Firestore
    constructor() : this(0, 0, "", 0, "", "", null, null)
}