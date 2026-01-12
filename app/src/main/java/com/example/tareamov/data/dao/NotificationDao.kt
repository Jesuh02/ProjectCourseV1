package com.example.tareamov.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.tareamov.data.entity.Notification

@Dao
interface NotificationDao {
    @Query("SELECT * FROM notifications WHERE user_id = :userId ORDER BY created_at DESC")
    fun getAllByUserId(userId: Long): LiveData<List<Notification>>
    
    @Query("SELECT * FROM notifications WHERE user_id = :userId ORDER BY created_at DESC")
    suspend fun getAllByUserIdSuspend(userId: Long): List<Notification>
    
    @Query("SELECT * FROM notifications WHERE id = :id")
    suspend fun getById(id: Long): Notification?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(notification: Notification): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(notifications: List<Notification>)
    
    @Update
    suspend fun update(notification: Notification)
    
    @Delete
    suspend fun delete(notification: Notification)
    
    @Query("DELETE FROM notifications WHERE user_id = :userId")
    suspend fun deleteAllByUserId(userId: Long)
    
    @Query("UPDATE notifications SET is_read = 1 WHERE id = :id")
    suspend fun markAsRead(id: Long)
    
    @Query("SELECT COUNT(*) FROM notifications WHERE user_id = :userId AND is_read = 0")
    fun getUnreadCount(userId: Long): LiveData<Int>
    
    @Query("SELECT COUNT(*) FROM notifications WHERE user_id = :userId AND is_read = 0")
    suspend fun getUnreadCountSuspend(userId: Long): Int
}
