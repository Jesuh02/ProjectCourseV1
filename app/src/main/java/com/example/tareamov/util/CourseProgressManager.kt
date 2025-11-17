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
import com.example.tareamov.service.SupabaseClient
import com.example.tareamov.data.sync.SyncRepository
import com.google.gson.Gson

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

            // Prefer Supabase for topics/tasks/submissions if configured and SyncRepository is available
            var topics = emptyList<com.example.tareamov.data.entity.Topic>()
            var tasks = emptyList<com.example.tareamov.data.entity.Task>()
            var submissions = emptyList<com.example.tareamov.data.entity.TaskSubmission>()

            if (SupabaseClient.isConfigured()) {
                try {
                    val act = (context as? android.app.Activity)
                    if (act is com.example.tareamov.MainActivity) {
                        val repo = act.syncRepository
                        topics = withContext(Dispatchers.IO) { repo.fetchTopicsByCourseFromSupabase(courseId) }
                        val topicIds = topics.map { it.id }
                        tasks = if (topicIds.isNotEmpty()) withContext(Dispatchers.IO) { repo.fetchTasksByTopicIdsFromSupabase(topicIds) } else emptyList()
                        submissions = withContext(Dispatchers.IO) { repo.fetchStudentSubmissionsForCourseFromSupabase(username, courseId) }
                        android.util.Log.d("CourseProgressManager", "Supabase: topics=${topics.size} tasks=${tasks.size} subs=${submissions.size}")
                        try {
                            val gson = Gson()
                            android.util.Log.d("CourseProgressManager", "Submissions sample: ${gson.toJson(submissions.take(5))}")
                        } catch (e: Exception) {
                            android.util.Log.w("CourseProgressManager", "Failed to json-serialize submissions sample", e)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CourseProgressManager", "Supabase fetch failed, falling back to local DAOs", e)
                }
            }

            // Fallback to local DB if Supabase did not yield results
            if (topics.isEmpty()) {
                topics = withContext(Dispatchers.IO) { db.topicDao().getTopicsByCourse(courseId) }
            }

            if (topics.isEmpty()) {
                progressContainer.visibility = View.GONE
                return 0f
            }

            if (tasks.isEmpty()) {
                val topicIds = topics.map { it.id }
                tasks = withContext(Dispatchers.IO) { db.taskDao().getTasksByTopicIds(topicIds) }
            }

            if (tasks.isEmpty()) {
                progressContainer.visibility = View.GONE
                return 0f
            }

            if (submissions.isEmpty()) {
                submissions = withContext(Dispatchers.IO) { db.taskSubmissionDao().getStudentSubmissionsForCourse(username, courseId) }
                android.util.Log.d("CourseProgressManager", "Local fallback submissions=${submissions.size}")
            }

            // Calculate progress
            val totalTasks = tasks.size
            val completedTasks = submissions.distinctBy { it.taskId }.size
            val progressPercent = if (totalTasks > 0) {
                (completedTasks * 100) / totalTasks
            } else {
                0
            }

            // Calculate average grade - Ahora considera TODAS las tareas, incluso sin submission (calificación 0)
            var totalGrade = 0f
            val taskSubmissionMap = submissions.associateBy { it.taskId }
            
            // Para cada tarea, usar la calificación de la submission o 0 si no existe
            for (task in tasks) {
                val submission = taskSubmissionMap[task.id]
                val grade = submission?.grade ?: 0f // Si no hay submission, calificación es 0
                totalGrade += grade
            }

            // El promedio se calcula sobre TODAS las tareas (no solo las que tienen submission)
            val averageGrade = if (totalTasks > 0) {
                totalGrade / totalTasks
            } else {
                0f
            }
            
            // Contar cuántas tareas tienen calificación diferente de 0 (para mostrar info adicional)
            val gradedSubmissionsCount = submissions.count { (it.grade ?: 0f) > 0f }

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