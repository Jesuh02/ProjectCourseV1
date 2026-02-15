package com.example.tareamov.util

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.animation.ObjectAnimator
import androidx.fragment.app.FragmentActivity
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.ui.CourseDetailFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Helper class to add student progress to CourseDetailFragment without modifying it directly.
 */
import com.example.tareamov.util.SessionManager

class CourseProgressHelper(private val activity: FragmentActivity) {

    fun setupStudentProgress(courseId: Long) {
        CoroutineScope(Dispatchers.Main).launch {
            val username = SessionManager.getInstance(activity).getUsername() ?: return@launch
            val courseCreator = getCourseCreator(courseId)

            if (username == courseCreator) return@launch

            val fragmentManager = activity.supportFragmentManager
            val courseDetailFragment = fragmentManager.findFragmentById(R.id.nav_host_fragment)
                ?.childFragmentManager?.fragments?.firstOrNull { it is CourseDetailFragment }

            val fragmentView = courseDetailFragment?.view ?: return@launch
            val progressContainer = fragmentView.findViewById<LinearLayout>(R.id.courseProgressContainer) ?: return@launch
            val progressBar = progressContainer.findViewById<ProgressBar>(R.id.courseProgressBar) ?: return@launch
            val progressPercentTextView = progressContainer.findViewById<TextView>(R.id.progressPercentTextView) ?: return@launch
            val progressStatusTextView = progressContainer.findViewById<TextView>(R.id.progressStatusTextView) ?: return@launch

            loadStudentProgress(
                courseId,
                username,
                progressContainer,
                progressBar,
                progressPercentTextView,
                progressStatusTextView
            )
        }
    }

    private fun loadStudentProgress(
        courseId: Long,
        username: String,
        progressContainer: LinearLayout,
        progressBar: ProgressBar,
        progressPercentTextView: TextView,
        progressStatusTextView: TextView
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val db = AppDatabase.getDatabase(activity)
                val roomUserId = withContext(Dispatchers.IO) {
                    db.usuarioDao().getUsuarioByUsername(username)?.id
                } ?: SessionManager.getInstance(activity).getUserId()

                if (BackendApiService.isAuthenticated) {
                    withContext(Dispatchers.IO) {
                        BackendApiService.recalculateProgress(courseId)
                    }
                    val backendProgress = withContext(Dispatchers.IO) {
                        BackendApiService.getProgressByCourseRaw(courseId)
                    }

                    if (backendProgress is ApiResult.Success && backendProgress.data != null) {
                        val data = backendProgress.data
                        val progressPercent = runCatching {
                            when {
                                data.has("progressPercentage") && !data.get("progressPercentage").isJsonNull -> data.get("progressPercentage").asFloat.toInt()
                                data.has("porcentajeProgreso") && !data.get("porcentajeProgreso").isJsonNull -> data.get("porcentajeProgreso").asFloat.toInt()
                                else -> 0
                            }
                        }.getOrDefault(0).coerceIn(0, 100)

                        val averageGrade = runCatching {
                            when {
                                data.has("averageGrade") && !data.get("averageGrade").isJsonNull -> data.get("averageGrade").asFloat
                                data.has("calificacionPonderada") && !data.get("calificacionPonderada").isJsonNull -> data.get("calificacionPonderada").asFloat
                                data.has("promedio") && !data.get("promedio").isJsonNull -> data.get("promedio").asFloat
                                else -> 0f
                            }
                        }.getOrDefault(0f)

                        updateProgressUi(progressContainer, progressBar, progressPercentTextView, progressStatusTextView, progressPercent, averageGrade)
                        return@launch
                    }
                }

                // Get all topics for this course
                val topics = withContext(Dispatchers.IO) {
                    db.topicDao().getTopicsByCourse(courseId)
                }

                if (topics.isEmpty()) {
                    progressContainer.visibility = View.GONE
                    return@launch
                }

                // Get all tasks for these topics
                val topicIds = topics.map { it.id }
                val tasks = withContext(Dispatchers.IO) {
                    db.taskDao().getTasksByTopicIds(topicIds)
                }

                if (tasks.isEmpty()) {
                    progressContainer.visibility = View.GONE
                    return@launch
                }

                // Get all submissions for this student in this course
                val submissions = withContext(Dispatchers.IO) {
                    db.taskSubmissionDao().getStudentSubmissionsForCourse(roomUserId, courseId)
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

                updateProgressUi(progressContainer, progressBar, progressPercentTextView, progressStatusTextView, progressPercent, averageGrade)
            } catch (e: Exception) {
                android.util.Log.e("CourseProgressHelper", "Error calculating progress", e)
                withContext(Dispatchers.Main) {
                    progressContainer.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun getCourseCreator(courseId: Long): String? {
        // Get the course creator from the database
        return withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(activity)
                val video = db.videoDao().getVideoById(courseId)
                video?.username
            } catch (e: Exception) {
                android.util.Log.e("CourseProgressHelper", "Error getting course creator", e)
                null
            }
        }
    }

    private fun updateProgressUi(
        progressContainer: LinearLayout,
        progressBar: ProgressBar,
        progressPercentTextView: TextView,
        progressStatusTextView: TextView,
        progressPercent: Int,
        averageGrade: Float
    ) {
        progressContainer.visibility = View.VISIBLE
        progressBar.max = 100
        val safeProgress = progressPercent.coerceIn(0, 100)
        val startProgress = progressBar.progress.coerceIn(0, 100)
        ObjectAnimator.ofInt(progressBar, "progress", startProgress, safeProgress).apply {
            duration = 500L
            start()
        }

        progressPercentTextView.text = "${safeProgress}% completado"

        val gradeColor = when {
            averageGrade >= 6.0f -> android.graphics.Color.parseColor("#4CAF50")
            averageGrade > 0f -> android.graphics.Color.parseColor("#F44336")
            else -> android.graphics.Color.parseColor("#AAAAAA")
        }

        val statusLabel = when {
            averageGrade >= 6.0f -> "Aprobando"
            averageGrade > 0f -> "Reprobando"
            else -> "Sin calificar"
        }

        progressStatusTextView.text = "Calificación: ${String.format("%.1f", averageGrade)}/10 ($statusLabel)"
        progressStatusTextView.setTextColor(gradeColor)
    }
}