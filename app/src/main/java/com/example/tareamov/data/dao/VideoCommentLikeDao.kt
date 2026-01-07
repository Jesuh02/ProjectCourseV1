package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.tareamov.data.entity.Like
import com.example.tareamov.data.entity.VideoCommentWithLikes

/**
 * DAO for polymorphic likes table.
 * Supports likes on any entity type: videos, comments, courses, tasks, etc.
 */
@Dao
interface LikeDao {
    
    // ========== GENERIC LIKE OPERATIONS ==========
    
    /**
     * Insert a like for any entity
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLike(like: Like): Long
    
    /**
     * Remove a like
     */
    @Query("DELETE FROM likes WHERE usuario_id = :usuarioId AND entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteLike(usuarioId: Long, entityType: String, entityId: Long)
    
    /**
     * Check if a user has liked a specific entity
     */
    @Query("SELECT EXISTS(SELECT 1 FROM likes WHERE usuario_id = :usuarioId AND entity_type = :entityType AND entity_id = :entityId)")
    suspend fun hasUserLiked(usuarioId: Long, entityType: String, entityId: Long): Boolean
    
    /**
     * Get like count for a specific entity
     */
    @Query("SELECT COUNT(*) FROM likes WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun getLikeCount(entityType: String, entityId: Long): Int
    
    /**
     * Get all likes for a specific entity
     */
    @Query("SELECT * FROM likes WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun getLikesForEntity(entityType: String, entityId: Long): List<Like>
    
    /**
     * Get all likes by a user
     */
    @Query("SELECT * FROM likes WHERE usuario_id = :usuarioId")
    suspend fun getLikesByUser(usuarioId: Long): List<Like>
    
    /**
     * Get all likes by a user for a specific entity type
     */
    @Query("SELECT * FROM likes WHERE usuario_id = :usuarioId AND entity_type = :entityType")
    suspend fun getLikesByUserAndType(usuarioId: Long, entityType: String): List<Like>
    
    /**
     * Toggle like: if liked, remove; if not liked, add
     * Returns true if now liked, false if now unliked
     */
    @Transaction
    suspend fun toggleLike(usuarioId: Long, entityType: String, entityId: Long): Boolean {
        val isCurrentlyLiked = hasUserLiked(usuarioId, entityType, entityId)
        if (isCurrentlyLiked) {
            deleteLike(usuarioId, entityType, entityId)
            return false
        } else {
            insertLike(Like(usuarioId, entityType, entityId))
            return true
        }
    }
    
    // ========== VIDEO COMMENT SPECIFIC (convenience methods) ==========
    
    /**
     * Like a video comment
     */
    suspend fun likeComment(usuarioId: Long, commentId: Long): Long {
        return insertLike(Like(usuarioId, Like.TYPE_COMMENT, commentId))
    }
    
    /**
     * Unlike a video comment
     */
    suspend fun unlikeComment(usuarioId: Long, commentId: Long) {
        deleteLike(usuarioId, Like.TYPE_COMMENT, commentId)
    }
    
    /**
     * Check if user liked a comment
     */
    suspend fun hasUserLikedComment(usuarioId: Long, commentId: Long): Boolean {
        return hasUserLiked(usuarioId, Like.TYPE_COMMENT, commentId)
    }
    
    /**
     * Get comment like count
     */
    suspend fun getCommentLikeCount(commentId: Long): Int {
        return getLikeCount(Like.TYPE_COMMENT, commentId)
    }
    
    /**
     * Toggle comment like
     */
    suspend fun toggleCommentLike(usuarioId: Long, commentId: Long): Boolean {
        return toggleLike(usuarioId, Like.TYPE_COMMENT, commentId)
    }
    
    // ========== VIDEO SPECIFIC (convenience methods) ==========
    
    /**
     * Like a video
     */
    suspend fun likeVideo(usuarioId: Long, videoId: Long): Long {
        return insertLike(Like(usuarioId, Like.TYPE_VIDEO, videoId))
    }
    
    /**
     * Unlike a video
     */
    suspend fun unlikeVideo(usuarioId: Long, videoId: Long) {
        deleteLike(usuarioId, Like.TYPE_VIDEO, videoId)
    }
    
    /**
     * Check if user liked a video
     */
    suspend fun hasUserLikedVideo(usuarioId: Long, videoId: Long): Boolean {
        return hasUserLiked(usuarioId, Like.TYPE_VIDEO, videoId)
    }
    
    /**
     * Get video like count
     */
    suspend fun getVideoLikeCount(videoId: Long): Int {
        return getLikeCount(Like.TYPE_VIDEO, videoId)
    }
    
    /**
     * Toggle video like
     */
    suspend fun toggleVideoLike(usuarioId: Long, videoId: Long): Boolean {
        return toggleLike(usuarioId, Like.TYPE_VIDEO, videoId)
    }
    
    // ========== COURSE SPECIFIC (convenience methods) ==========
    
    /**
     * Like a course
     */
    suspend fun likeCourse(usuarioId: Long, courseId: Long): Long {
        return insertLike(Like(usuarioId, Like.TYPE_COURSE, courseId))
    }
    
    /**
     * Unlike a course
     */
    suspend fun unlikeCourse(usuarioId: Long, courseId: Long) {
        deleteLike(usuarioId, Like.TYPE_COURSE, courseId)
    }
    
    /**
     * Check if user liked a course
     */
    suspend fun hasUserLikedCourse(usuarioId: Long, courseId: Long): Boolean {
        return hasUserLiked(usuarioId, Like.TYPE_COURSE, courseId)
    }
    
    /**
     * Get course like count
     */
    suspend fun getCourseLikeCount(courseId: Long): Int {
        return getLikeCount(Like.TYPE_COURSE, courseId)
    }
    
    // ========== BATCH OPERATIONS ==========
    
    /**
     * Get like counts for multiple entities of the same type
     */
    @Query("SELECT entity_id FROM likes WHERE entity_type = :entityType AND entity_id IN (:entityIds)")
    suspend fun getLikedEntityIds(entityType: String, entityIds: List<Long>): List<Long>
    
    /**
     * Get which entities the user has liked from a list
     */
    @Query("SELECT entity_id FROM likes WHERE usuario_id = :usuarioId AND entity_type = :entityType AND entity_id IN (:entityIds)")
    suspend fun getUserLikedEntityIds(usuarioId: Long, entityType: String, entityIds: List<Long>): List<Long>
    
    /**
     * Delete all likes for an entity (used when entity is deleted)
     */
    @Query("DELETE FROM likes WHERE entity_type = :entityType AND entity_id = :entityId")
    suspend fun deleteAllLikesForEntity(entityType: String, entityId: Long)
    
    /**
     * Insert multiple likes at once (for syncing)
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllLikes(likes: List<Like>)
    
    /**
     * Get all likes (for syncing)
     */
    @Query("SELECT * FROM likes")
    suspend fun getAllLikes(): List<Like>
    
    // ========== COMPLEX QUERIES FOR COMMENTS WITH LIKES ==========
    
    /**
     * Get comments with like counts and user's like status for a video
     */
    @Query("""
        SELECT 
            vc.id,
            vc.video_id as videoId,
            vc.usuario_id as usuarioId,
            vc.comment,
            vc.parent_id as parentId,
            vc.created_at as createdAt,
            u.username,
            u.avatar,
            (SELECT COUNT(*) FROM likes WHERE entity_type = 'comment' AND entity_id = vc.id) as likeCount,
            EXISTS(SELECT 1 FROM likes WHERE entity_type = 'comment' AND entity_id = vc.id AND usuario_id = :currentUserId) as isLikedByCurrentUser
        FROM video_comments vc
        LEFT JOIN usuarios u ON vc.usuario_id = u.id
        WHERE vc.video_id = :videoId
        ORDER BY vc.created_at DESC
    """)
    suspend fun getCommentsWithLikesForVideo(videoId: Long, currentUserId: Long): List<VideoCommentWithLikes>
    
    /**
     * Get a single comment with like info
     */
    @Query("""
        SELECT 
            vc.id,
            vc.video_id as videoId,
            vc.usuario_id as usuarioId,
            vc.comment,
            vc.parent_id as parentId,
            vc.created_at as createdAt,
            u.username,
            u.avatar,
            (SELECT COUNT(*) FROM likes WHERE entity_type = 'comment' AND entity_id = vc.id) as likeCount,
            EXISTS(SELECT 1 FROM likes WHERE entity_type = 'comment' AND entity_id = vc.id AND usuario_id = :currentUserId) as isLikedByCurrentUser
        FROM video_comments vc
        LEFT JOIN usuarios u ON vc.usuario_id = u.id
        WHERE vc.id = :commentId
    """)
    suspend fun getCommentWithLikes(commentId: Long, currentUserId: Long): VideoCommentWithLikes?
}

// Legacy type alias for backward compatibility
@Deprecated("Use LikeDao instead", ReplaceWith("LikeDao"))
typealias VideoCommentLikeDao = LikeDao
