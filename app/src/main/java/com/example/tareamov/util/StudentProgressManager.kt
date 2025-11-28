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
import com.example.tareamov.util.SessionManager
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
        val certificateIssuedDateTextView = progressContainer.findViewById<TextView?>(R.id.certificateIssuedDateTextView)

        CoroutineScope(Dispatchers.Main).launch {
            Log.d("StudentProgressManager", "loadStudentProgress called: courseId=$courseId username=$username SupabaseConfigured=${com.example.tareamov.service.SupabaseClient.isConfigured()}")
            try {
                val db = AppDatabase.getDatabase(context)

                // Prefer Supabase for topics/tasks (remote-authoritative), fall back to local DAOs
                val tasks = withContext(Dispatchers.IO) {
                    try {
                        val actActivity = (context as? android.app.Activity)
                        if (actActivity is com.example.tareamov.MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                            // Fetch topics from Supabase then tasks for those topics
                            val topics = actActivity.syncRepository.fetchTopicsByCourseFromSupabase(courseId)
                            Log.d("StudentProgressManager", "Supabase topics fetched for course=$courseId count=${topics?.size ?: 0}")
                            val topicIds = topics?.map { it.id } ?: emptyList()
                            if (topicIds.isNotEmpty()) {
                                val fetchedTasks = actActivity.syncRepository.fetchTasksByTopicIdsFromSupabase(topicIds)
                                Log.d("StudentProgressManager", "Supabase tasks fetched for topicCount=${topicIds.size} tasks=${fetchedTasks?.size ?: 0}")
                                return@withContext (fetchedTasks ?: emptyList())
                            }
                            // No topics or tasks found remotely -> fall through to local
                        }
                    } catch (e: Exception) {
                        Log.w("StudentProgressManager", "Supabase fetch for tasks failed, falling back to local", e)
                    }

                    // Local DAO fallback
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

                // Always prefer Supabase for submissions (remote-authoritative)
                val submissions = withContext(Dispatchers.IO) {
                    try {
                        val actActivity = (context as? android.app.Activity)
                        if (actActivity is com.example.tareamov.MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                            // Resolve userId from username for the repo call
                            val userId = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(username)
                            if (userId != null) {
                                val remoteSubs = actActivity.syncRepository.fetchStudentSubmissionsForCourseFromSupabase(userId, courseId)
                                Log.d("StudentProgressManager", "Supabase submissions fetched for user=$username (id=$userId) course=$courseId count=${remoteSubs.size}")
                                // Log a sample of returned submissions (first 5 ids)
                                val sampleIds = remoteSubs.take(5).map { it.id }
                                Log.d("StudentProgressManager", "Sample remote submission ids: $sampleIds")
                                if (remoteSubs.isNotEmpty()) return@withContext remoteSubs
                            } else {
                                Log.w("StudentProgressManager", "Could not resolve userId for username=$username, skipping Supabase fetch")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("StudentProgressManager", "Error fetching submissions from Supabase for $username course=$courseId", e)
                    }

                    // Local fallback if Supabase not available or empty
                    // Resolve username -> userId for Room DAO
                    val studentId = db.usuarioDao().getUsuarioByUsername(username)?.id
                        ?: SessionManager.getInstance(context).getUserId()
                    val localSubs = db.taskSubmissionDao().getStudentSubmissionsForCourse(studentId, courseId)
                    Log.d("StudentProgressManager", "Local submissions count for user=$username course=$courseId=${localSubs?.size ?: 0}")
                    localSubs
                }

                // Log a JSON sample of submissions for debugging
                try {
                    val gson = com.google.gson.Gson()
                    val sample = submissions.take(5)
                    Log.d("StudentProgressManager", "Submissions sample JSON: ${gson.toJson(sample)}")
                } catch (e: Exception) {
                    Log.w("StudentProgressManager", "Failed to serialize submissions sample to JSON", e)
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

                        // Get user ID from username
                        val userId = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(username)
                        }
                        
                        if (userId == null) {
                            Log.e("StudentProgressManager", "Failed to get user ID for username: $username")
                            return@launch
                        }

                        // Check if certificate was already issued
                        val progreso = withContext(Dispatchers.IO) {
                            db.progresoEstudianteDao().getProgresoByUsuarioAndCurso(userId, courseId)
                        }
                        
                        progreso?.certificadoEmitidoEn?.let { timestamp ->
                            // Show certificate issued date
                            val dateFormat = java.text.SimpleDateFormat("dd 'de' MMMM 'de' yyyy", java.util.Locale("es", "ES"))
                            val date = dateFormat.format(java.util.Date(timestamp))
                            certificateIssuedDateTextView?.text = "✓ Certificado emitido el $date"
                            certificateIssuedDateTextView?.visibility = View.VISIBLE
                            certificateButton?.text = "Regenerar Certificado"
                        } ?: run {
                            certificateIssuedDateTextView?.visibility = View.GONE
                            certificateButton?.text = "Generar Certificado"
                        }

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
                                            df.format(weightedGrade).toString(),
                                            courseId
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
                                            df.format(weightedGrade).toString(),
                                            courseId
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
        // IMPORTANTE: El promedio debe incluir TODAS las tareas
        // Las tareas sin calificación cuentan como 0
        if (tasks.isEmpty()) return 0f
        
        // Crear mapa de entregas por taskId
        val submissionMap = submissions.associateBy { it.taskId }
        
        // Calcular promedio incluyendo todas las tareas (ungraded = 0)
        var totalGrade = 0f
        for (task in tasks) {
            val grade = submissionMap[task.id]?.grade ?: 0f
            totalGrade += grade
        }
        
        val avg = totalGrade / tasks.size
        Log.d("StudentProgressManager", "Calculated average grade: $avg (totalGrade=$totalGrade, totalTasks=${tasks.size})")
        return avg
    }
}
