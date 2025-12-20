package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tareamov.data.entity.ContentItem

@Dao
interface ContentItemDao {
    // Add this method if it doesn't exist
    @Query("SELECT * FROM content_items")
    suspend fun getAllContentItems(): List<ContentItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContentItem(contentItem: ContentItem): Long

    @Update
    suspend fun updateContentItem(contentItem: ContentItem)

    @Query("SELECT * FROM content_items WHERE topic_id = :topicId ORDER BY order_index ASC")
    suspend fun getContentItemsByTopic(topicId: Long): List<ContentItem>

    @Query("SELECT * FROM content_items WHERE id = :contentItemId")
    suspend fun getContentItemById(contentItemId: Long): ContentItem?

    @Query("DELETE FROM content_items WHERE id = :contentItemId")
    suspend fun deleteContentItem(contentItemId: Long)

    @Query("DELETE FROM content_items WHERE topic_id = :topicId")
    suspend fun deleteContentItemsByTopic(topicId: Long)

    // Add this alias method to match the name used in CourseTopicFragment
    @Query("DELETE FROM content_items WHERE topic_id = :topicId")
    suspend fun deleteContentItemsByTopicId(topicId: Long)

    // Select only columns from content_items to avoid CURSOR_MISMATCH warning
    @Query("SELECT ci.* FROM content_items ci INNER JOIN topics t ON ci.topic_id = t.id WHERE t.course_id = :courseId ORDER BY t.order_index, ci.order_index")
    suspend fun getContentItemsByCourse(courseId: Long): List<ContentItem>

    @Query("SELECT * FROM content_items WHERE topic_id IN (:topicIds)")
    suspend fun getContentItemsByTopicIds(topicIds: List<Long>): List<ContentItem>

    // Get content items by single topic ID
    @Query("SELECT * FROM content_items WHERE topic_id = :topicId ORDER BY order_index ASC")
    suspend fun getContentItemsByTopicId(topicId: Long): List<ContentItem>

    // Add method to get content items by task ID
    @Query("SELECT * FROM content_items WHERE task_id = :taskId ORDER BY order_index ASC")
    suspend fun getContentItemsByTaskId(taskId: Long): List<ContentItem>

    // Add method to get total content item count
    @Query("SELECT COUNT(*) FROM content_items")
    suspend fun getContentItemCount(): Int

    // Add method to get content items by topic ID without task parameter
    @Query("SELECT * FROM content_items WHERE topic_id = :topicId ORDER BY order_index ASC")
    suspend fun getContentItemsByTopicOnly(topicId: Long): List<ContentItem>

    // Add this method to delete content items by task ID
    @Query("DELETE FROM content_items WHERE task_id = :taskId")
    suspend fun deleteContentItemsByTaskId(taskId: Long)
}