package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tareamov.data.entity.VideoLike
import com.example.tareamov.data.entity.UserVideoLike

@Dao
interface VideoLikeDao {
    
    // ========== Video Likes (Total count per video) ==========
    
    @Query("SELECT * FROM video_likes WHERE video_id = :videoId")
    suspend fun getLikesByVideoId(videoId: Long): VideoLike?
    
    @Query("SELECT like_count FROM video_likes WHERE video_id = :videoId")
    suspend fun getLikeCount(videoId: Long): Int?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVideoLike(videoLike: VideoLike): Long
    
    @Update
    suspend fun updateVideoLike(videoLike: VideoLike)
    
    @Query("UPDATE video_likes SET like_count = like_count + 1 WHERE video_id = :videoId")
    suspend fun incrementLikeCount(videoId: Long)
    
    @Query("UPDATE video_likes SET like_count = like_count - 1 WHERE video_id = :videoId AND like_count > 0")
    suspend fun decrementLikeCount(videoId: Long)
    
    @Query("DELETE FROM video_likes WHERE video_id = :videoId")
    suspend fun deleteVideoLikes(videoId: Long)
    
    @Query("SELECT * FROM video_likes")
    suspend fun getAllVideoLikes(): List<VideoLike>
    
    // ========== User Video Likes (Track individual user likes) ==========
    
    @Query("SELECT * FROM user_video_likes WHERE video_id = :videoId AND usuario_id = :usuarioId")
    suspend fun getUserLike(videoId: Long, usuarioId: Long): UserVideoLike?
    
    @Query("SELECT EXISTS(SELECT 1 FROM user_video_likes WHERE video_id = :videoId AND usuario_id = :usuarioId)")
    suspend fun hasUserLikedVideo(videoId: Long, usuarioId: Long): Boolean
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserLike(userVideoLike: UserVideoLike): Long
    
    @Query("DELETE FROM user_video_likes WHERE video_id = :videoId AND usuario_id = :usuarioId")
    suspend fun deleteUserLike(videoId: Long, usuarioId: Long)
    
    @Query("SELECT * FROM user_video_likes WHERE usuario_id = :usuarioId")
    suspend fun getLikedVideosByUser(usuarioId: Long): List<UserVideoLike>
    
    @Query("SELECT video_id FROM user_video_likes WHERE usuario_id = :usuarioId")
    suspend fun getLikedVideoIdsByUser(usuarioId: Long): List<Long>
    
    @Query("DELETE FROM user_video_likes WHERE video_id = :videoId")
    suspend fun deleteAllUserLikesForVideo(videoId: Long)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllVideoLikes(likes: List<VideoLike>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllUserLikes(userLikes: List<UserVideoLike>)
}
