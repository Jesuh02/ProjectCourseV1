package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tareamov.data.entity.Task

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: Task): Long

    @Update
    suspend fun updateTask(task: Task)

    @Query("SELECT * FROM tasks WHERE topicId = :topicId")
    fun getTasksByTopic(topicId: Long): List<Task>

    @Query("SELECT * FROM tasks WHERE topicId = :topicId ORDER BY orderIndex ASC")
    suspend fun getTasksByTopicId(topicId: Long): List<Task>

    // Add this method to get tasks by multiple topic IDs
    @Query("SELECT * FROM tasks WHERE topicId IN (:topicIds) ORDER BY orderIndex ASC")
    suspend fun getTasksByTopicIds(topicIds: List<Long>): List<Task>

    @Query("SELECT * FROM tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: Long): Task?

    @Query("DELETE FROM tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: Long)

    @Query("DELETE FROM tasks WHERE topicId = :topicId")
    suspend fun deleteTasksByTopicId(topicId: Long)

    // Add this method to count tasks for a specific topic
    @Query("SELECT COUNT(*) FROM tasks WHERE topicId = :topicId")
    suspend fun getTaskCountByTopicId(topicId: Long): Int

    // Add this method to get all tasks
    @Query("SELECT * FROM tasks ORDER BY topicId, orderIndex ASC")
    suspend fun getAllTasks(): List<Task>

    // Add this method to get task count
    @Query("SELECT COUNT(*) FROM tasks")
    suspend fun getTaskCount(): Int

    @Query("SELECT tasks.* FROM tasks INNER JOIN topics ON tasks.topicId = topics.id WHERE tasks.name = :taskName AND topics.courseId = :courseId LIMIT 1")
    suspend fun getTaskByNameAndCourse(taskName: String, courseId: Long): Task?
    
    // Alias method for getTasksByTopicId
    @Query("SELECT * FROM tasks WHERE topicId = :topicId ORDER BY orderIndex ASC")
    suspend fun getTasksForTopic(topicId: Long): List<Task>

    // Get tasks by course ID (joining through topics table)
    @Query("SELECT tasks.* FROM tasks INNER JOIN topics ON tasks.topicId = topics.id WHERE topics.courseId = :courseId ORDER BY topics.orderIndex, tasks.orderIndex ASC")
    suspend fun getTasksByCourse(courseId: Long): List<Task>
}