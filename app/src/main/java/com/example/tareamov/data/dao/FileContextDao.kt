package com.example.tareamov.data.dao

import androidx.room.*
import com.example.tareamov.data.entity.FileContext
import kotlinx.coroutines.flow.Flow

@Dao
interface FileContextDao {
    
    @Query("SELECT * FROM file_contexts WHERE submission_id = :submissionId")
    suspend fun getFileContextBySubmission(submissionId: Long): FileContext?
    
    @Query("SELECT * FROM file_contexts ORDER BY created_at DESC")
    fun getAllFileContexts(): Flow<List<FileContext>>
    
    @Query("SELECT content_summary FROM file_contexts WHERE content_summary IS NOT NULL AND content_summary != '' ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestContentSummary(): String?
    
    @Insert
    suspend fun insertFileContext(fileContext: FileContext): Long
    
    @Update
    suspend fun updateFileContext(fileContext: FileContext)
    
    @Query("DELETE FROM file_contexts WHERE submission_id = :submissionId")
    suspend fun deleteFileContextBySubmission(submissionId: Long)
    
    @Query("DELETE FROM file_contexts")
    suspend fun clearAllFileContexts()
}
