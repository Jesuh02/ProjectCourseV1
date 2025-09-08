package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "file_contexts")
data class FileContext(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val submissionId: Long,
    val fileName: String,
    val fileType: String,
    val fileContent: String,
    val extractedText: String? = null,
    val metadata: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val jsonContent: String? = null,
    val contentSummary: String? = null
)
