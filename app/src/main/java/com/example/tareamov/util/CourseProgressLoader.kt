package com.example.tareamov.util

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.TaskSubmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Helper class to load and display course progress
 */
class CourseProgressLoader(private val context: Context) {
    private val TAG = "CourseProgressLoader"

    /**
     * Load and display course progress for a student
     * @param courseId The course ID
     * @param username The student's username
     * @param container The container to add the progress view to
     * @param position The position to add the view at (or -1 to add at the end)
     */
    fun loadCourseProgress(
        courseId: Long,
        username: String,
        container: ViewGroup,
        position: Int = -1,
        coroutineScope: CoroutineScope
    ) {
        coroutineScope.launch {
            try {
                // Get database instance
                val db = AppDatabase.getDatabase(context)

                // Load tasks for this course - using getAllTasks() and filtering instead
                val allTasks: List<Task> = withContext(Dispatchers.IO) {
                    db.taskDao().getAllTasks()
                }

                // Filter tasks for this course by topicId
                // First get all topics for this course
                val topics = db.topicDao().getTopicsByCourse(courseId)
                val topicIds = topics.map { it.id }

                // Filter tasks that belong to these topics
                val tasks = allTasks.filter { task ->
                    topicIds.contains(task.topicId)
                }

                // Get all task IDs for this course
                val courseTaskIds: List<Long> = tasks.map { it.id }

                // Load all submissions for this student
                val allSubmissions: List<TaskSubmission> = withContext(Dispatchers.IO) {
                    db.taskSubmissionDao().getSubmissionsByStudent(username)
                }

                // Filter submissions to only include those for tasks in this course
                val submissions = allSubmissions.filter { submission ->
                    courseTaskIds.contains(submission.taskId)
                }

                // Only show progress if there are tasks
                if (tasks.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        displayProgressView(container, tasks, submissions, position)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading course progress", e)
            }
        }
    }

    // Simple progress view class to replace the missing CourseProgressView
    private class CourseProgressView(context: Context) : LinearLayout(context) {
        fun updateProgress(tasks: List<Task>, submissions: List<TaskSubmission>) {
            // Implementation of progress display
            removeAllViews()

            // Calculate completed tasks based on grade instead of isCompleted
            val completedTasks = tasks.count { task ->
                submissions.any { it.taskId == task.id && it.grade != null }
            }

            // Create and add a TextView to show progress
            val textView = TextView(context).apply {
                text = "$completedTasks/${tasks.size} tareas completadas"
                textSize = 16f
                setPadding(16, 16, 16, 16)
            }

            addView(textView)
        }
    }

    private fun displayProgressView(
        container: ViewGroup,
        tasks: List<Task>,
        submissions: List<TaskSubmission>,
        position: Int
    ) {
        // Create progress view
        val progressView = CourseProgressView(context)
        progressView.updateProgress(tasks, submissions)

        // Add to container
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        if (position >= 0 && position < container.childCount) {
            container.addView(progressView, position, params)
        } else {
            container.addView(progressView, params)
        }
    }
}