package com.example.tareamov.util

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.data.entity.ProgresoEstudiante
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Helper class to load and display course progress
 * Calcula y sincroniza el progreso del estudiante con Supabase
 */
class CourseProgressLoader(private val context: Context) {
    private val TAG = "CourseProgressLoader"

    /**
     * Load and display course progress for a student
     * Calcula el progreso y lo sincroniza con Supabase
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
                    // Calcular progreso
                    val progreso = calcularProgreso(courseId, username, tasks, submissions)
                    
                    // Guardar en la base de datos local
                    withContext(Dispatchers.IO) {
                        db.progresoEstudianteDao().insertProgreso(progreso)
                    }
                    
                    // Sincronizar con Supabase (fire-and-forget)
                    sincronizarProgresoConSupabase(progreso)
                    
                    withContext(Dispatchers.Main) {
                        displayProgressView(container, tasks, submissions, progreso, position)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading course progress", e)
            }
        }
    }
    
    /**
     * Calcula el progreso del estudiante en el curso
     */
    private fun calcularProgreso(
        courseId: Long,
        username: String,
        tasks: List<Task>,
        submissions: List<TaskSubmission>
    ): ProgresoEstudiante {
        // Calcular tareas completadas (con calificación)
        val completedTasks = tasks.count { task ->
            submissions.any { it.taskId == task.id && it.grade != null }
        }
        
        val totalTasks = tasks.size
        val porcentaje = if (totalTasks > 0) {
            (completedTasks.toFloat() / totalTasks.toFloat()) * 100f
        } else {
            0f
        }
        
        // Calcular calificación ponderada (promedio de todas las tareas calificadas)
        val calificaciones = submissions.mapNotNull { it.grade }
        val calificacionPonderada = if (calificaciones.isNotEmpty()) {
            calificaciones.average().toFloat()
        } else {
            null
        }
        
        return ProgresoEstudiante(
            usuarioEstudiante = username,
            cursoId = courseId,
            tareasCompletadas = completedTasks,
            tareasTotales = totalTasks,
            porcentajeProgreso = porcentaje,
            calificacionPonderada = calificacionPonderada,
            estado = if (calificacionPonderada != null && calificacionPonderada >= 6f) "Ganado" else "Perdido",
            ultimaCalculadaEn = System.currentTimeMillis()
        )
    }
    
    /**
     * Sincroniza el progreso con Supabase de forma asíncrona
     */
    private fun sincronizarProgresoConSupabase(progreso: ProgresoEstudiante) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                    Log.w(TAG, "Supabase not configured, skipping sync")
                    return@launch
                }
                
                val exito = com.example.tareamov.service.SupabaseClient.upsertProgresoEstudiante(progreso)
                if (exito) {
                    Log.d(TAG, "Progreso sincronizado exitosamente para ${progreso.usuarioEstudiante} en curso ${progreso.cursoId}")
                } else {
                    Log.w(TAG, "No se pudo sincronizar el progreso con Supabase")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sincronizando progreso con Supabase", e)
            }
        }
    }

    // Simple progress view class to replace the missing CourseProgressView
    private class CourseProgressView(context: Context) : LinearLayout(context) {
        fun updateProgress(tasks: List<Task>, submissions: List<TaskSubmission>, progreso: ProgresoEstudiante) {
            // Implementation of progress display
            removeAllViews()

            // Calculate completed tasks based on grade instead of isCompleted
            val completedTasks = progreso.tareasCompletadas
            val totalTasks = progreso.tareasTotales
            val porcentaje = progreso.porcentajeProgreso
            val calificacion = progreso.calificacionPonderada
            val estado = progreso.estado

            // Create and add a TextView to show progress
            val progressTextView = TextView(context).apply {
                text = "$completedTasks/$totalTasks tareas completadas (${String.format("%.1f", porcentaje)}%)"
                textSize = 16f
                setPadding(16, 8, 16, 4)
                setTextColor(context.getColor(android.R.color.white))
            }
            addView(progressTextView)
            
            // Show grade if available
            if (calificacion != null) {
                val gradeTextView = TextView(context).apply {
                    text = "Calificación: ${String.format("%.2f", calificacion)}/10 - Estado: $estado"
                    textSize = 14f
                    setPadding(16, 4, 16, 8)
                    val color = if (estado == "Ganado") {
                        context.getColor(android.R.color.holo_green_light)
                    } else {
                        context.getColor(android.R.color.holo_red_light)
                    }
                    setTextColor(color)
                }
                addView(gradeTextView)
            }
        }
    }

    private fun displayProgressView(
        container: ViewGroup,
        tasks: List<Task>,
        submissions: List<TaskSubmission>,
        progreso: ProgresoEstudiante,
        position: Int
    ) {
        // Create progress view
        val progressView = CourseProgressView(context)
        progressView.updateProgress(tasks, submissions, progreso)

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