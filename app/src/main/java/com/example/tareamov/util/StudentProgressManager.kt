package com.example.tareamov.util

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.TaskSubmission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class StudentProgressManager(private val context: Context) {

    fun loadStudentProgress(
        courseId: Long,
        username: String,
        progressContainer: View,
        onProgressLoaded: ((Float) -> Unit)? = null
    ) {
        val progressBar = progressContainer.findViewById<ProgressBar>(R.id.courseProgressBar)
        val progressTextView = progressContainer.findViewById<TextView>(R.id.courseProgressTextView)
        val gradeTextView = progressContainer.findViewById<TextView>(R.id.courseGradeTextView)
        // Add a TextView for status (Ganado/Perdido)
        val statusTextView = progressContainer.findViewById<TextView?>(R.id.courseStatusTextView)
        // Certificate button container
        val certificateButtonContainer = progressContainer.findViewById<FrameLayout?>(R.id.certificateButtonContainer)
        val certificateButton = progressContainer.findViewById<Button?>(R.id.certificateButton)

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val db = AppDatabase.getDatabase(context)

                val tasks = withContext(Dispatchers.IO) {
                    val topics = db.topicDao().getTopicsByCourse(courseId)
                    val allTasks = mutableListOf<Task>()
                    for (topic in topics) {
                        allTasks.addAll(db.taskDao().getTasksByTopic(topic.id))
                    }
                    allTasks
                }

                if (tasks.isEmpty()) {
                    progressContainer.visibility = View.GONE
                    return@launch
                }

                val submissions = withContext(Dispatchers.IO) {
                    db.taskSubmissionDao().getStudentSubmissionsForCourse(username, courseId)
                }

                // Calculate progress percentage and weighted grade directly
                val progressPercentage = calculateProgressPercentage(tasks, submissions)
                val weightedGrade = calculateWeightedAverageGrade(tasks, submissions)

                progressContainer.visibility = View.VISIBLE
                progressBar.max = 100
                progressBar.progress = progressPercentage

                val completedTasks = submissions.map { it.taskId }.distinct().size
                progressTextView.text = "$completedTasks de ${tasks.size} tareas completadas"
                val df = DecimalFormat("#.#")
                gradeTextView.text = "Calificación: ${df.format(weightedGrade)}/10"

                // Show Ganado/Perdido
                statusTextView?.let {
                    if (weightedGrade >= 6f) {
                        it.text = "Estado: Ganado"
                        it.setTextColor(context.getColor(android.R.color.holo_green_light))

                        // Show certificate button only if the student passed the course
                        certificateButtonContainer?.visibility = View.VISIBLE

                        // Get course and user details for certificate
                        val courseDetails = withContext(Dispatchers.IO) {
                            db.videoDao().getVideoById(courseId)
                        }

                        // Set up certificate button click listener
                        certificateButton?.setOnClickListener {
                            // Get course creator's name
                            val creatorUsername = courseDetails?.username ?: ""
                            val courseName = courseDetails?.title ?: "Curso"

                            // Get course topic from the first topic in the course
                            CoroutineScope(Dispatchers.IO).launch {
                                try {
                                    val topics = db.topicDao().getTopicsByCourse(courseId)
                                    // Use 'name' property instead of 'title' since that's what Topic entity has
                                    val courseTopic = if (topics.isNotEmpty()) topics[0].name else "General"

                                    withContext(Dispatchers.Main) {
                                        // Generate and download certificate
                                        CertificateGenerator.generateCertificate(
                                            context,
                                            username,
                                            creatorUsername,
                                            courseName,
                                            courseTopic,
                                            df.format(weightedGrade).toString()
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e("StudentProgressManager", "Error getting course topic", e)
                                    withContext(Dispatchers.Main) {
                                        // Fallback to generate certificate without topic
                                        CertificateGenerator.generateCertificate(
                                            context,
                                            username,
                                            creatorUsername,
                                            courseName,
                                            "General",
                                            df.format(weightedGrade).toString()
                                        )
                                    }
                                }
                            }
                        }

                    } else {
                        it.text = "Estado: Perdido"
                        it.setTextColor(context.getColor(android.R.color.holo_red_light))
                        // Hide certificate button if student failed
                        certificateButtonContainer?.visibility = View.GONE
                    }
                    it.visibility = View.VISIBLE
                }

                onProgressLoaded?.invoke(progressPercentage.toFloat())

            } catch (e: Exception) {
                Log.e("StudentProgressManager", "Error loading student progress", e)
                progressContainer.visibility = View.GONE
            }
        }
    }

    // Calculate the percentage of tasks completed
    private fun calculateProgressPercentage(tasks: List<Task>, submissions: List<TaskSubmission>): Int {
        if (tasks.isEmpty()) return 0

        val completedTasksCount = submissions.map { it.taskId }.distinct().size
        return (completedTasksCount * 100) / tasks.size
    }

    // Calculate the weighted average grade based on completed tasks
    private fun calculateWeightedAverageGrade(tasks: List<Task>, submissions: List<TaskSubmission>): Float {
        if (submissions.isEmpty()) return 0f

        var totalGrade = 0f
        var gradedSubmissionsCount = 0

        // Group submissions by task ID to handle multiple submissions for the same task
        val submissionsByTask = submissions.groupBy { it.taskId }

        for ((taskId, taskSubmissions) in submissionsByTask) {
            // Get the submission with the highest grade for each task
            val bestSubmission = taskSubmissions.maxByOrNull { it.grade ?: 0f }

            bestSubmission?.grade?.let { grade ->
                totalGrade += grade
                gradedSubmissionsCount++
            }
        }

        return if (gradedSubmissionsCount > 0) {
            totalGrade / gradedSubmissionsCount
        } else {
            0f
        }
    }
}
