package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

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
        Index("studentId")
    ]
)
data class TaskSubmission(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @SerializedName("task_id")
    val taskId: Long = 0,
    @SerializedName("student_id")
    val studentId: Long = 0,
    @SerializedName("submission_date")
    val submissionDate: Long = 0,
    @SerializedName("file_uri")
    val fileUri: String = "",
    @SerializedName("file_name")
    val fileName: String = "",
    val grade: Float? = null,
    val feedback: String? = null,
    @SerializedName("graded_by")
    val gradedBy: Long? = null,
    @SerializedName("graded_at")
    val gradedAt: String? = null
) 