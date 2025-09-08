package com.example.tareamov.util

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.tareamov.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class CourseProgressManager(private val context: Context) {

    suspend fun calculateAndDisplayCourseProgress(
        courseId: Long,
        username: String,
        progressContainer: LinearLayout,
        progressBar: ProgressBar,
        progressPercentTextView: TextView,
        progressStatusTextView: TextView
    ): Float {
        try {
            val db = AppDatabase.getDatabase(context)

            // Get all topics for this course
            val topics = withContext(Dispatchers.IO) {
                db.topicDao().getTopicsByCourse(courseId)
            }

            if (topics.isEmpty()) {
                progressContainer.visibility = View.GONE
                return 0f
            }

            // Get all tasks for these topics
            val topicIds = topics.map { it.id }
            val tasks = withContext(Dispatchers.IO) {
                db.taskDao().getTasksByTopicIds(topicIds)
            }

            if (tasks.isEmpty()) {
                progressContainer.visibility = View.GONE
                return 0f
            }

            // Get all submissions for this student in this course
            val submissions = withContext(Dispatchers.IO) {
                db.taskSubmissionDao().getStudentSubmissionsForCourse(username, courseId)
            }

            // Calculate progress
            val totalTasks = tasks.size
            val completedTasks = submissions.distinctBy { it.taskId }.size
            val progressPercent = if (totalTasks > 0) {
                (completedTasks * 100) / totalTasks
            } else {
                0
            }

            // Calculate average grade
            var totalGrade = 0f
            var gradedSubmissionsCount = 0

            for (submission in submissions) {
                submission.grade?.let { grade ->
                    totalGrade += grade
                    gradedSubmissionsCount++
                }
            }

            val averageGrade = if (gradedSubmissionsCount > 0) {
                totalGrade / gradedSubmissionsCount
            } else {
                0f
            }

            // Update UI
            progressContainer.visibility = View.VISIBLE
            progressBar.progress = progressPercent

            // Update progress text
            progressPercentTextView.text = "$progressPercent% completado"

            // Format the grade with one decimal place
            val df = DecimalFormat("#.#")

            // Update status text with average grade
            val gradeColor = if (averageGrade >= 6.0f) {
                android.graphics.Color.parseColor("#4CAF50") // Green for passing
            } else if (averageGrade > 0) {
                android.graphics.Color.parseColor("#F44336") // Red for failing
            } else {
                android.graphics.Color.parseColor("#AAAAAA") // Gray for no grades yet
            }

            progressStatusTextView.text = "Calificación: ${df.format(averageGrade)}/10"
            progressStatusTextView.setTextColor(gradeColor)

            // Add pass/fail status if there are graded submissions
            if (gradedSubmissionsCount > 0) {
                val passFailText = if (averageGrade >= 6.0f) {
                    "Aprobando"
                } else {
                    "Reprobando"
                }
                progressStatusTextView.text = "${progressStatusTextView.text} ($passFailText)"
            }

            return averageGrade

        } catch (e: Exception) {
            Log.e("CourseProgressManager", "Error calculating progress", e)
            progressContainer.visibility = View.GONE
            return 0f
        }
    }
}