package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.tareamov.data.entity.TaskSubmission

@Dao
interface TaskSubmissionDao {
    @Insert
    fun insertSubmission(submission: TaskSubmission): Long

    @Update
    suspend fun updateSubmission(submission: TaskSubmission)

    @Query("SELECT * FROM task_submissions WHERE taskId = :taskId ORDER BY submissionDate DESC")
    fun getSubmissionsByTask(taskId: Long): List<TaskSubmission>

    @Query("SELECT * FROM task_submissions WHERE taskId = :taskId AND studentUsername = :username LIMIT 1")
    fun getUserSubmissionForTask(taskId: Long, username: String): TaskSubmission?

    @Query("SELECT * FROM task_submissions WHERE id = :submissionId")
    suspend fun getSubmissionById(submissionId: Long): TaskSubmission?

    @Query("SELECT * FROM task_submissions WHERE studentUsername = :username")
    suspend fun getSubmissionsByStudent(username: String): List<TaskSubmission>

    @Query("SELECT * FROM task_submissions WHERE taskId IN (SELECT id FROM tasks WHERE topicId IN (SELECT id FROM topics WHERE courseId = :courseId))")
    suspend fun getSubmissionsByCourse(courseId: Long): List<TaskSubmission>

    // New method to get all submissions for a specific student in a course
    @Query("SELECT * FROM task_submissions WHERE studentUsername = :username AND taskId IN (SELECT id FROM tasks WHERE topicId IN (SELECT id FROM topics WHERE courseId = :courseId))")
    suspend fun getStudentSubmissionsForCourse(username: String, courseId: Long): List<TaskSubmission>

    @Query("SELECT * FROM task_submissions")
    suspend fun getAllTaskSubmissions(): List<TaskSubmission>
}