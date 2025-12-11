package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import com.example.tareamov.data.entity.VideoComment
import com.example.tareamov.data.entity.VideoCommentWithUser

@Dao
interface VideoCommentDao {
    
    @Query("SELECT * FROM video_comments WHERE video_id = :videoId ORDER BY created_at DESC")
    suspend fun getCommentsByVideoId(videoId: Long): List<VideoComment>
    
    @Query("SELECT COUNT(*) FROM video_comments WHERE video_id = :videoId")
    suspend fun getCommentCount(videoId: Long): Int
    
    @Query("SELECT * FROM video_comments WHERE id = :commentId")
    suspend fun getCommentById(commentId: Long): VideoComment?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComment(comment: VideoComment): Long
    
    @Update
    suspend fun updateComment(comment: VideoComment)
    
    @Delete
    suspend fun deleteComment(comment: VideoComment)
    
    @Query("DELETE FROM video_comments WHERE id = :commentId")
    suspend fun deleteCommentById(commentId: Long)
    
    @Query("DELETE FROM video_comments WHERE video_id = :videoId")
    suspend fun deleteAllCommentsForVideo(videoId: Long)
    
    @Query("SELECT * FROM video_comments WHERE usuario_id = :usuarioId ORDER BY created_at DESC")
    suspend fun getCommentsByUser(usuarioId: Long): List<VideoComment>
    
    @Query("SELECT * FROM video_comments ORDER BY created_at DESC LIMIT :limit")
    suspend fun getRecentComments(limit: Int = 50): List<VideoComment>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllComments(comments: List<VideoComment>)
    
    @Query("""
        SELECT vc.id, vc.video_id as videoId, vc.usuario_id as usuarioId, 
               vc.comment, vc.created_at as createdAt, 
               u.username as username, u.avatar as avatar
        FROM video_comments vc
        LEFT JOIN usuarios u ON vc.usuario_id = u.id
        WHERE vc.video_id = :videoId
        ORDER BY vc.created_at DESC
    """)
    suspend fun getCommentsWithUserByVideoId(videoId: Long): List<VideoCommentWithUser>
}
