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
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Usuario::class,
            parentColumns = ["id"],
            childColumns = ["student_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("task_id"),
        Index("student_id")
    ]
)
data class TaskSubmission(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @androidx.room.ColumnInfo(name = "task_id")
    @SerializedName("task_id")
    val taskId: Long = 0,
    @androidx.room.ColumnInfo(name = "student_id")
    @SerializedName("student_id")
    val studentId: Long = 0,
    @androidx.room.ColumnInfo(name = "submission_date")
    @SerializedName("submission_date")
    val submissionDate: Long = 0,
    @androidx.room.ColumnInfo(name = "file_uri")
    @SerializedName("file_uri")
    val fileUri: String = "",
    @androidx.room.ColumnInfo(name = "file_name")
    @SerializedName("file_name")
    val fileName: String = "",
    val grade: Float? = null,
    val feedback: String? = null,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) 