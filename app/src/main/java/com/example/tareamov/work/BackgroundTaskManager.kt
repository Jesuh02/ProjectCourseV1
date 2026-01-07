package com.example.tareamov.work

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * BackgroundTaskManager - Helper class to manage background LLM tasks
 * 
 * Provides methods to:
 * - Schedule tasks to run in background
 * - Check for pending results when app reopens
 * - Observe task progress
 */
object BackgroundTaskManager {
    private const val TAG = "BackgroundTaskManager"
    private const val PREFS_NAME = "llm_background_results"
    
    // Result validity period (24 hours)
    private const val RESULT_VALIDITY_MS = 24 * 60 * 60 * 1000L
    
    /**
     * Schedule a chat message to be processed in background
     */
    fun scheduleChatMessage(
        context: Context,
        prompt: String,
        userId: Long,
        username: String,
        sessionId: String,
        taskDescription: String = "",
        fileContent: String = "",
        jsonContent: String = "",
        metadata: String = "",
        submissionId: Long? = null,
        taskId: Long? = null
    ): String {
        Log.d(TAG, "📋 Scheduling chat message for background processing")
        
        return LLMBackgroundWorker.scheduleChatTask(
            context = context,
            prompt = prompt,
            userId = userId,
            username = username,
            sessionId = sessionId,
            taskDescription = taskDescription,
            fileContent = fileContent,
            jsonContent = jsonContent,
            metadata = metadata,
            submissionId = submissionId,
            taskId = taskId
        )
    }
    
    /**
     * Schedule a database query to be processed in background
     */
    fun scheduleDatabaseQuery(
        context: Context,
        query: String,
        userId: Long,
        username: String
    ): String {
        Log.d(TAG, "📋 Scheduling database query for background processing")
        
        return LLMBackgroundWorker.scheduleDatabaseQueryTask(
            context = context,
            query = query,
            userId = userId,
            username = username
        )
    }
    
    /**
     * Schedule reinforcement question generation in background
     */
    fun scheduleReinforcementQuestions(
        context: Context,
        courseId: Long,
        courseName: String,
        topicId: Long,
        taskId: Long,
        userId: Long,
        username: String,
        jsonContent: String = ""
    ): String {
        Log.d(TAG, "📋 Scheduling reinforcement questions for background processing")
        
        return LLMBackgroundWorker.scheduleReinforcementTask(
            context = context,
            courseId = courseId,
            courseName = courseName,
            topicId = topicId,
            taskId = taskId,
            userId = userId,
            username = username,
            jsonContent = jsonContent
        )
    }
    
    /**
     * Check for pending database query results
     */
    fun getPendingDatabaseQueryResult(context: Context, userId: Long): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong("last_db_query_timestamp_$userId", 0L)
        
        // Check if result is still valid
        if (System.currentTimeMillis() - timestamp > RESULT_VALIDITY_MS) {
            clearDatabaseQueryResult(context, userId)
            return null
        }
        
        return prefs.getString("last_db_query_result_$userId", null)
    }
    
    /**
     * Clear database query result after reading
     */
    fun clearDatabaseQueryResult(context: Context, userId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("last_db_query_result_$userId")
            .remove("last_db_query_timestamp_$userId")
            .apply()
    }
    
    /**
     * Check for pending reinforcement questions
     */
    fun getPendingReinforcementQuestions(context: Context, userId: Long, courseId: Long): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong("reinforcement_timestamp_${userId}_$courseId", 0L)
        
        // Check if result is still valid
        if (System.currentTimeMillis() - timestamp > RESULT_VALIDITY_MS) {
            clearReinforcementQuestions(context, userId, courseId)
            return null
        }
        
        return prefs.getString("reinforcement_questions_${userId}_$courseId", null)
    }
    
    /**
     * Clear reinforcement questions after reading
     */
    fun clearReinforcementQuestions(context: Context, userId: Long, courseId: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("reinforcement_questions_${userId}_$courseId")
            .remove("reinforcement_timestamp_${userId}_$courseId")
            .apply()
    }
    
    /**
     * Observe work status by unique work name
     */
    fun observeWorkStatus(context: Context, workName: String): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosForUniqueWorkLiveData(workName)
    }
    
    /**
     * Observe all tasks by tag
     */
    fun observeTasksByTag(context: Context, tag: String): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosByTagLiveData(tag)
    }
    
    /**
     * Observe all tasks for a specific user
     */
    fun observeUserTasks(context: Context, userId: Long): LiveData<List<WorkInfo>> {
        return WorkManager.getInstance(context).getWorkInfosByTagLiveData("user_$userId")
    }
    
    /**
     * Cancel all pending tasks for a user
     */
    fun cancelUserTasks(context: Context, userId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag("user_$userId")
        Log.d(TAG, "🚫 Cancelled all tasks for user $userId")
    }
    
    /**
     * Cancel a specific task by work name
     */
    fun cancelTask(context: Context, workName: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName)
        Log.d(TAG, "🚫 Cancelled task: $workName")
    }
    
    /**
     * Check if there are any running tasks
     */
    suspend fun hasRunningTasks(context: Context, userId: Long): Boolean {
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosByTag("user_$userId")
            .get()
        
        return workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }
}
