package com.example.tareamov.util

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
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
        val username = SessionManager.getInstance(activity).getUsername() ?: return
        val courseCreator = getCourseCreator(courseId)

        // Only show progress for non-creators
        if (username == courseCreator) {
            return
        }

        // Find the course detail fragment
        val fragmentManager = activity.supportFragmentManager
        val courseDetailFragment = fragmentManager.findFragmentById(R.id.nav_host_fragment)
            ?.childFragmentManager?.fragments?.firstOrNull { it is CourseDetailFragment }

        val fragmentView = courseDetailFragment?.view ?: return

        // Find the progress container in the layout by its ID
        val progressContainer = fragmentView.findViewById<LinearLayout>(R.id.courseProgressContainer)
        if (progressContainer != null) {
            // Get the progress bar and text views
            val progressBar = progressContainer.findViewById<ProgressBar>(R.id.courseProgressBar)
            val progressPercentTextView = progressContainer.findViewById<TextView>(R.id.progressPercentTextView)
            val progressStatusTextView = progressContainer.findViewById<TextView>(R.id.progressStatusTextView)

            if (progressBar != null && progressPercentTextView != null && progressStatusTextView != null) {
                // Load and display progress
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
                withContext(Dispatchers.Main) {
                    // Show progress container
                    progressContainer.visibility = View.VISIBLE

                    // Update progress bar
                    progressBar.progress = progressPercent

                    // Update progress text
                    progressPercentTextView.text = "${progressPercent}% completado"

                    // Update status text with average grade
                    val gradeColor = if (averageGrade >= 6.0f) {
                        android.graphics.Color.parseColor("#4CAF50") // Green for passing
                    } else if (averageGrade > 0) {
                        android.graphics.Color.parseColor("#F44336") // Red for failing
                    } else {
                        android.graphics.Color.parseColor("#AAAAAA") // Gray for no grades yet
                    }

                    progressStatusTextView.text = "Calificación: ${String.format("%.1f", averageGrade)}/10"
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
                }
            } catch (e: Exception) {
                android.util.Log.e("CourseProgressHelper", "Error calculating progress", e)
                withContext(Dispatchers.Main) {
                    progressContainer.visibility = View.GONE
                }
            }
        }
    }

    private fun getCourseCreator(courseId: Long): String? {
        // Get the course creator from the database
        var creator: String? = null
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(activity)
                val video = db.videoDao().getVideoById(courseId)
                creator = video?.username
            } catch (e: Exception) {
                android.util.Log.e("CourseProgressHelper", "Error getting course creator", e)
            }
        }
        return creator
    }
}