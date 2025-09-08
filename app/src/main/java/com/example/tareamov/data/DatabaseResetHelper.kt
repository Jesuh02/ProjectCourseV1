package com.example.tareamov.data

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

object DatabaseResetHelper {
    
    private const val TAG = "DatabaseResetHelper"
    
    /**
     * Reset database if integrity issues are detected
     */
    fun resetDatabaseIfNeeded(context: Context): Boolean {
        return try {
            // Try to access the database first
            val db = Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "app_database"
            ).build()
            
            // Try a simple query to check integrity
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sqlite_master")
            db.close()
            
            Log.i(TAG, "Database integrity check passed")
            false // No reset needed
            
        } catch (e: Exception) {
            Log.e(TAG, "Database integrity issue detected: ${e.message}")
            
            // Reset the database
            try {
                AppDatabase.resetDatabase(context)
                Log.i(TAG, "Database reset successfully")
                true // Reset performed
            } catch (resetError: Exception) {
                Log.e(TAG, "Failed to reset database: ${resetError.message}")
                false
            }
        }
    }
    
    /**
     * Create fresh database with all tables
     */
    fun createFreshDatabase(context: Context) {
        try {
            // Delete existing database
            context.deleteDatabase("app_database")
            
            // Create new instance - this will trigger all migrations from scratch
            val db = AppDatabase.getDatabase(context)
            
            // Verify the database was created successfully using runBlocking for suspend function
            runBlocking {
                try {
                    val chatMessageDao = db.chatMessageDao()
                    val messageCount = chatMessageDao.getMessageCount()
                    Log.i(TAG, "Fresh database created successfully. Message count: $messageCount")
                } catch (e: Exception) {
                    Log.w(TAG, "Could not verify message count, but database was created: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create fresh database: ${e.message}")
            throw e
        }
    }

    /**
     * Async version of createFreshDatabase for use in coroutines
     */
    suspend fun createFreshDatabaseAsync(context: Context) {
        try {
            // Delete existing database
            context.deleteDatabase("app_database")
            
            // Create new instance - this will trigger all migrations from scratch
            val db = AppDatabase.getDatabase(context)
            
            // Verify the database was created successfully
            try {
                val chatMessageDao = db.chatMessageDao()
                val messageCount = chatMessageDao.getMessageCount()
                Log.i(TAG, "Fresh database created successfully. Message count: $messageCount")
            } catch (e: Exception) {
                Log.w(TAG, "Could not verify message count, but database was created: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create fresh database: ${e.message}")
            throw e
        }
    }
}
