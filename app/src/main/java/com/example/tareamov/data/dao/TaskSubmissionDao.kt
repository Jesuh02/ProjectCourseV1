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

    @Query("SELECT * FROM task_submissions WHERE taskId = :taskId AND studentId = :studentId LIMIT 1")
    fun getUserSubmissionForTask(taskId: Long, studentId: Long): TaskSubmission?

    @Query("SELECT * FROM task_submissions WHERE id = :submissionId")
    suspend fun getSubmissionById(submissionId: Long): TaskSubmission?

    @Query("SELECT * FROM task_submissions WHERE studentId = :studentId")
    suspend fun getSubmissionsByStudent(studentId: Long): List<TaskSubmission>

    @Query("SELECT * FROM task_submissions WHERE taskId IN (SELECT id FROM tasks WHERE topicId IN (SELECT id FROM topics WHERE courseId = :courseId))")
    suspend fun getSubmissionsByCourse(courseId: Long): List<TaskSubmission>

    // New method to get all submissions for a specific student in a course
    @Query("SELECT * FROM task_submissions WHERE studentId = :studentId AND taskId IN (SELECT id FROM tasks WHERE topicId IN (SELECT id FROM topics WHERE courseId = :courseId))")
    suspend fun getStudentSubmissionsForCourse(studentId: Long, courseId: Long): List<TaskSubmission>

    @Query("SELECT * FROM task_submissions")
    suspend fun getAllTaskSubmissions(): List<TaskSubmission>
}