package com.example.tareamov.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.viewModels
import com.example.tareamov.ui.compose.CourseSelectionViewModel
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.tareamov.ui.compose.CourseSelectionScreen
import com.example.tareamov.R
import com.example.tareamov.data.entity.Course
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseSelectionFragment : Fragment() {

    private var currentUsername: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val vm = androidx.lifecycle.ViewModelProvider(
            this,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
        ).get(com.example.tareamov.ui.compose.CourseSelectionViewModel::class.java)

        // Get current username for subscription checks
        currentUsername = getCurrentUsername()

        return ComposeView(requireContext()).apply {
            setContent {
                CourseSelectionScreen(
                    viewModel = vm,
                    onBackClick = {
                        findNavController().popBackStack()
                    },
                    onCourseSelected = { course ->
                        // Check if user is subscribed to the course creator before allowing access
                        checkSubscriptionAndNavigate(course)
                    }
                )
            }
        }
    }

    /**
     * Get current username from shared preferences
     */
    private fun getCurrentUsername(): String? {
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val username = sessionManager.getUsername()
        return username
    }

    /**
     * Check if user is subscribed to course creator and navigate accordingly
     */
    private fun checkSubscriptionAndNavigate(course: Course) {
        if (currentUsername == null) {
            android.widget.Toast.makeText(requireContext(), "¡Debes iniciar sesión para acceder al curso!", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        // Get current user ID
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val currentUserId = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
                if (currentUserId == null) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(requireContext(), "❌ Error: No se pudo obtener tu ID de usuario", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val creatorUserId = course.creatorUserId

                // Check if user is the course creator
                if (currentUserId == creatorUserId) {
                    // Course creator has full access
                    withContext(Dispatchers.Main) {
                        navigateToReinforcementLearning(course)
                    }
                    return@launch
                }

                // Check if user is enrolled in the course
                val isEnrolled = com.example.tareamov.service.SupabaseClient.isUserEnrolled(currentUserId, course.id)
                
                // Check if user is subscribed to the course creator
                val db = com.example.tareamov.data.AppDatabase.getDatabase(requireContext())
                val isSubscribed = db.subscriptionDao().isUserSubscribedToCreator(currentUserId, creatorUserId)

                withContext(Dispatchers.Main) {
                    when {
                        isEnrolled -> {
                            // User is enrolled, allow access
                            navigateToReinforcementLearning(course)
                        }
                        isSubscribed -> {
                            // User is subscribed to creator, allow access
                            navigateToReinforcementLearning(course)
                        }
                        course.price > 0 -> {
                            // Paid course and not enrolled/subscribed
                            android.widget.Toast.makeText(requireContext(), "❌ Este es un curso de pago. Debes inscribirte o suscribirte al creador para acceder.", android.widget.Toast.LENGTH_LONG).show()
                        }
                        else -> {
                            // Free course, allow access and auto-enroll
                            navigateToReinforcementLearning(course)
                            // Auto-enroll in background
                            autoEnrollInCourse(course, currentUserId)
                        }
                    }
                }

            } catch (e: Exception) {
                android.util.Log.e("CourseSelectionFragment", "Error checking subscription status", e)
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(requireContext(), "❌ Error al verificar acceso al curso", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Navigate to reinforcement learning screen
     */
    private fun navigateToReinforcementLearning(course: Course) {
        // Navigate to SelectTopicFragment first, passing courseId and courseName
        val bundle = android.os.Bundle().apply {
            putLong("courseId", course.id)
            putString("courseName", course.title)
        }
        findNavController().navigate(R.id.action_courseSelectionFragment_to_selectTopicFragment, bundle)
    }

    /**
     * Auto-enroll user in a free course
     */
    private fun autoEnrollInCourse(course: Course, userId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = com.example.tareamov.data.AppDatabase.getDatabase(requireContext())
                
                // Check if already enrolled
                val existingProgreso = db.progresoEstudianteDao().getProgreso(userId, course.id)
                if (existingProgreso != null) {
                    android.util.Log.d("CourseSelectionFragment", "User already enrolled in course ${course.id}")
                    return@launch
                }
                
                // Ensure course exists in local DB
                val existingCourse = db.courseDao().getCourseById(course.id)
                if (existingCourse == null) {
                    db.courseDao().insertCourse(course)
                }
                
                // Get total tasks for this course
                val topics = db.topicDao().getTopicsByCourse(course.id)
                val topicIds = topics.map { it.id }
                val totalTasks = if (topicIds.isNotEmpty()) {
                    db.taskDao().getTasksByTopicIds(topicIds).size
                } else 0
                
                // Create initial progress record
                val progreso = com.example.tareamov.data.entity.ProgresoEstudiante(
                    usuarioEstudiante = userId,
                    cursoId = course.id,
                    tareasCompletadas = 0,
                    tareasTotales = totalTasks,
                    porcentajeProgreso = 0f,
                    calificacionPonderada = null,
                    promedio = null,
                    estado = "Perdido",
                    ultimaCalculadaEn = System.currentTimeMillis()
                )
                
                db.progresoEstudianteDao().insertProgreso(progreso)
                android.util.Log.d("CourseSelectionFragment", "✅ Auto-enrolled in course ${course.id}")
                
                // Sync to Supabase in background
                val syncRepo = com.example.tareamov.data.sync.SyncRepository(
                    db.usuarioDao(),
                    db.personaDao(),
                    db.topicDao(),
                    db.contentItemDao(),
                    db.taskDao(),
                    db.subscriptionDao(),
                    db.taskSubmissionDao(),
                    db.videoDao(),
                    db.courseDao(),
                    db.rolDao(),
                    db.recursoDao(),
                    db.rolRecursoDao(),
                    db.chatMessageDao(),
                    db.fileContextDao(),
                    db.progresoEstudianteDao()
                )
                syncRepo.syncProgresoToSupabase(progreso)
                
            } catch (e: Exception) {
                android.util.Log.e("CourseSelectionFragment", "Error auto-enrolling in course", e)
            }
        }
    }
}