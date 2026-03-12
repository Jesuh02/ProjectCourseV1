package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(tableName = "file_contexts")
data class FileContext(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @SerializedName("submission_id")
    val submissionId: Long = 0,
    val fileName: String = "",
    val fileType: String = "",
    val fileContent: String? = null,
    val extractedText: String? = null,
    val metadata: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val jsonContent: String? = null,
    val contentSummary: String? = null
)
