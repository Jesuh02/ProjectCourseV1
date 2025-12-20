package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_contexts",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = TaskSubmission::class,
            parentColumns = ["id"],
            childColumns = ["submission_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index("submission_id")]
)
data class FileContext(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @androidx.room.ColumnInfo(name = "submission_id")
    val submissionId: Long,
    @androidx.room.ColumnInfo(name = "file_name")
    val fileName: String,
    @androidx.room.ColumnInfo(name = "file_type")
    val fileType: String,
    @androidx.room.ColumnInfo(name = "file_content")
    val fileContent: String,
    @androidx.room.ColumnInfo(name = "extracted_text")
    val extractedText: String? = null,
    val metadata: String? = null,
    @androidx.room.ColumnInfo(name = "json_content")
    val jsonContent: String? = null,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(name = "content_summary")
    val contentSummary: String? = null
)
