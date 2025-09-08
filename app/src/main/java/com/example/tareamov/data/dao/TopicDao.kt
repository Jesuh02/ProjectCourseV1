package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tareamov.data.entity.Topic

@Dao
interface TopicDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopic(topic: Topic): Long

    @Update
    suspend fun updateTopic(topic: Topic)

    @Query("SELECT * FROM topics ORDER BY orderIndex ASC")
    suspend fun getAllTopics(): List<Topic>

    @Query("SELECT * FROM topics WHERE courseId = :courseId")
    fun getTopicsByCourse(courseId: Long): List<Topic>

    @Query("SELECT * FROM topics WHERE id = :topicId")
    suspend fun getTopicById(topicId: Long): Topic?

    @Query("SELECT * FROM topics WHERE id IN (:topicIds)")
    suspend fun getTopicsByIds(topicIds: List<Long>): List<Topic>

    @Query("DELETE FROM topics WHERE id = :topicId")
    suspend fun deleteTopic(topicId: Long)

    @Query("DELETE FROM topics WHERE courseId = :courseId")
    suspend fun deleteTopicsByCourse(courseId: Long)

    @Query("SELECT COUNT(*) FROM topics WHERE courseId = :courseId")
    suspend fun getTopicCountForCourse(courseId: Long): Int

    @Query("SELECT COUNT(*) FROM topics")
    suspend fun getTopicCount(): Int

    @Query("SELECT * FROM topics WHERE courseId = :courseId AND orderIndex = :orderIndex LIMIT 1")
    suspend fun getTopicByCourseIdAndOrderIndex(courseId: Long, orderIndex: Int): Topic?
}