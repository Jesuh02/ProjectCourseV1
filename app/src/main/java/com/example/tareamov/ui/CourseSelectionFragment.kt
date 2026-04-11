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
                    },
                    onCreatorClick = { username ->
                        // Navigate to user profile when clicking on creator
                        val bundle = android.os.Bundle().apply { putString("username", username) }
                        findNavController().navigate(R.id.action_courseSelectionFragment_to_userProfileViewFragment, bundle)
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

        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        if (sessionManager.hasRole(3) || sessionManager.hasRole(4) || sessionManager.isAdmin()) {
            navigateToReinforcementLearning(course)
            return
        }

        // Get current user ID
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.example.tareamov.service.BackendApiService.initialize(requireContext())
                val userResult = com.example.tareamov.service.BackendApiService.getUserByUsername(currentUsername!!)
                val currentUserId = if (userResult is com.example.tareamov.service.ApiResult.Success) userResult.data?.id else null
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

                // Check if user is subscribed to the course creator
                val subResult = com.example.tareamov.service.BackendApiService.checkSubscription(creatorUserId)
                val isSubscribed = subResult is com.example.tareamov.service.ApiResult.Success && subResult.data == true

                withContext(Dispatchers.Main) {
                    when {
                        isSubscribed -> {
                            // User is subscribed to creator, allow access
                            navigateToReinforcementLearning(course)
                        }
                        course.price > 0 -> {
                            // Paid course and not subscribed
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
        val bundle = android.os.Bundle().apply {
            putLong("courseId", course.id)
            putString("courseName", course.title)
        }
        findNavController().navigate(R.id.action_courseSelectionFragment_to_subjectSelectionFragment, bundle)
    }

    /**
     * Auto-enroll user in a free course
     */
    private fun autoEnrollInCourse(course: Course, userId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.example.tareamov.service.BackendApiService.initialize(requireContext())
                // Subscribe to the course creator to mark enrollment
                val result = com.example.tareamov.service.BackendApiService.subscribe(course.creatorUserId)
                if (result is com.example.tareamov.service.ApiResult.Success) {
                    android.util.Log.d("CourseSelectionFragment", "✅ Auto-enrolled in course ${course.id}")
                } else if (result is com.example.tareamov.service.ApiResult.Error) {
                    android.util.Log.e("CourseSelectionFragment", "Error auto-enrolling: ${result.message}")
                }
            } catch (e: Exception) {
                android.util.Log.e("CourseSelectionFragment", "Error auto-enrolling in course", e)
            }
        }
    }
}