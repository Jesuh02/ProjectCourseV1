package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tareamov.data.entity.VideoData

@Dao
interface VideoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideo(video: VideoData): Long

    @Update
    suspend fun updateVideo(video: VideoData)

    @Query("SELECT * FROM videos WHERE id = :videoId")
    suspend fun getVideoById(videoId: Long): VideoData?

    @Query("SELECT * FROM videos WHERE username = :username ORDER BY timestamp DESC")
    suspend fun getVideosByUsername(username: String): List<VideoData>

    @Query("SELECT * FROM videos ORDER BY timestamp DESC")
    suspend fun getAllVideos(): List<VideoData>

    @Query("DELETE FROM videos WHERE id = :videoId")
    suspend fun deleteVideo(videoId: Long)

    @Query("DELETE FROM videos WHERE username = :username")
    suspend fun deleteVideosByUsername(username: String)

    @Query("SELECT COUNT(*) FROM videos WHERE username = :username")
    suspend fun getVideoCountByUsername(username: String): Int

    @Query("SELECT COUNT(*) FROM videos")
    suspend fun getTotalVideoCount(): Int

    // Add this method to fix the iteration error
    @Query("SELECT * FROM videos WHERE id IN (:videoIds)")
    suspend fun getVideosByIds(videoIds: List<Long>): List<VideoData>

    // Add method to get video count
    @Query("SELECT COUNT(*) FROM videos")
    suspend fun getVideoCount(): Int

    // Add method to get video by title (case-insensitive)
    @Query("SELECT * FROM videos WHERE LOWER(title) = LOWER(:title) LIMIT 1")
    suspend fun getVideoByTitle(title: String): VideoData?

    // Add this method to check if a video exists by ID
    @Query("SELECT EXISTS(SELECT 1 FROM videos WHERE id = :videoId LIMIT 1)")
    suspend fun videoExistsById(videoId: Long): Boolean

    // Add this method to get the first video ID (useful for default references)
    @Query("SELECT id FROM videos ORDER BY id ASC LIMIT 1")
    suspend fun getFirstVideoId(): Long?
}