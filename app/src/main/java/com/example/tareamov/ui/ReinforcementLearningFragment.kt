package com.example.tareamov.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.example.tareamov.service.ServerEndpointResolver
import androidx.compose.runtime.produceState
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.tareamov.ui.compose.ReinforcementLearningScreen
import com.example.tareamov.ui.compose.ReinforcementLearningViewModel
import com.example.tareamov.ui.compose.ReinforcementLearningViewModelFactory

class ReinforcementLearningFragment : Fragment() {

    private lateinit var viewModel: ReinforcementLearningViewModel
    private var fragmentCourseId: Long = -1L
    private lateinit var mcpHttpClient: com.example.tareamov.service.MCPHttpClient

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        try {
            mcpHttpClient = com.example.tareamov.service.MCPHttpClient(requireContext())
        } catch (e: Exception) {
            android.util.Log.w("ReinforceFrag", "Failed to init MCPHttpClient: ${e.message}")
        }

        // Pre-warm resolver: choose local quickly or fallback to cloud
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val base = try { kotlinx.coroutines.withTimeoutOrNull(120) { ServerEndpointResolver.fastResolveMcpBaseUrl() } } catch (e: Exception) { null }
                if (!base.isNullOrBlank() && base != ServerEndpointResolver.RAILWAY_MCP_URL) {
                    // Quick verification: only force local base if reachable immediately
                    val reachable = try { ServerEndpointResolver.isServiceReachable(base, "/health") } catch (e: Exception) { false }
                    if (reachable) {
                        try { mcpHttpClient.setForcedBaseUrl(base) } catch (_: Exception) {}
                        android.util.Log.i("ReinforceFrag", "Resolved and verified local MCP base for fragment: $base")
                    } else {
                        try { mcpHttpClient.setForcedBaseUrl(ServerEndpointResolver.RAILWAY_MCP_URL) } catch (_: Exception) {}
                        android.util.Log.w("ReinforceFrag", "fastResolve returned local host but it was not reachable - using cloud: ${ServerEndpointResolver.RAILWAY_MCP_URL}")
                    }
                } else {
                    // Fast fallback to cloud if no local backend found or resolver returned cloud
                    try { mcpHttpClient.setForcedBaseUrl(ServerEndpointResolver.RAILWAY_MCP_URL) } catch (_: Exception) {}
                    android.util.Log.w("ReinforceFrag", "No local MCP resolved or resolver returned cloud; falling back to cloud: ${ServerEndpointResolver.RAILWAY_MCP_URL}")
                }
            } catch (e: Exception) {
                android.util.Log.w("ReinforceFrag", "fastResolve failed: ${e.message}")
                try { mcpHttpClient.setForcedBaseUrl(ServerEndpointResolver.RAILWAY_MCP_URL) } catch (_: Exception) {}
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Create ViewModel using BackendApiService (no local DB needed)
        val factory = ReinforcementLearningViewModelFactory(requireActivity().application)
        viewModel = ViewModelProvider(this, factory).get(ReinforcementLearningViewModel::class.java)

        // Get navigation arguments
        val courseId = arguments?.getLong("courseId") ?: -1L
        fragmentCourseId = courseId
        val topicId = arguments?.getLong("topicId") ?: -1L
        val taskId = arguments?.getLong("taskId") ?: -1L
        val difficulty = arguments?.getString("difficulty") ?: "HARD"
        val freeLearning = arguments?.getBoolean("freeLearning", false) ?: false

        // Apply difficulty before any load so the ViewModel uses it from the start
        viewModel.setDifficulty(difficulty)
        viewModel.setFreeLearning(freeLearning)

        // Load context information immediately when fragment is created
        if (courseId != -1L) {
            viewModel.loadContextInfo(courseId, topicId, taskId)
        }
        
        // Check for preloaded questions
        val preloadedJson = findNavController().currentBackStackEntry?.savedStateHandle?.get<String>("preloaded_questions_json")
        if (!preloadedJson.isNullOrBlank()) {
            viewModel.loadPreloadedQuestions(preloadedJson)
            // Clear the handle to avoid reloading on config changes
            findNavController().currentBackStackEntry?.savedStateHandle?.remove<String>("preloaded_questions_json")
        }

        return ComposeView(requireContext()).apply {
            setContent {
                val courseId = arguments?.getLong("courseId") ?: -1L
                val courseName = arguments?.getString("courseName") ?: "Curso sin título"
                val topicId = arguments?.getLong("topicId") ?: -1L
                val taskId = arguments?.getLong("taskId") ?: -1L
                val instructorArg = arguments?.getString("instructorName")
                val difficulty = arguments?.getString("difficulty") ?: "HARD"

                // Fetch creator username and avatar asynchronously and expose to Compose
                val creatorInfo = produceState<Pair<String?, String?>>(initialValue = Pair(instructorArg, null)) {
                    val initialUsername = instructorArg
                    var resolvedUsername: String? = initialUsername
                    var avatarUrl: String? = null

                    if ((resolvedUsername.isNullOrBlank() || resolvedUsername == "Docente no especificado") && courseId > 0) {
                        try {
                            // Fetch course from backend to get creator user ID, then fetch user
                            com.example.tareamov.service.BackendApiService.initialize(requireContext())
                            val courseResult = com.example.tareamov.service.BackendApiService.getCourseById(courseId)
                            if (courseResult is com.example.tareamov.service.ApiResult.Success && courseResult.data != null) {
                                val creatorId = courseResult.data.creatorUserId
                                val userResult = com.example.tareamov.service.BackendApiService.getUserById(creatorId)
                                if (userResult is com.example.tareamov.service.ApiResult.Success && userResult.data != null) {
                                    resolvedUsername = userResult.data.usuario
                                    avatarUrl = userResult.data.avatar
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ReinforceFrag", "couldn't fetch creator name: ${e.message}")
                        }
                    }

                    if (avatarUrl == null && !resolvedUsername.isNullOrBlank()) {
                        try {
                            val userResult = com.example.tareamov.service.BackendApiService.getUserByUsername(resolvedUsername)
                            if (userResult is com.example.tareamov.service.ApiResult.Success) {
                                avatarUrl = userResult.data?.avatar
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ReinforceFrag", "couldn't fetch usuario: ${e.message}")
                        }
                    }

                    value = Pair(resolvedUsername ?: instructorArg ?: "Docente no especificado", avatarUrl)
                }

                ReinforcementLearningScreen(
                    courseName = courseName,
                    instructorName = instructorArg ?: "Docente no especificado",
                    creatorUsername = creatorInfo.value.first,
                    creatorAvatarUrl = creatorInfo.value.second,
                    difficulty = difficulty,
                    onBackClick = {
                        findNavController().popBackStack()
                    },
                    onStartClick = {
                        viewLifecycleOwner.lifecycleScope.launch {
                            android.util.Log.d("ReinforceFrag", "═══════════════════════════════════════════════════")
                            android.util.Log.d("ReinforceFrag", "🚀 onStartClick TRIGGERED!")
                            android.util.Log.d("ReinforceFrag", "   📚 courseId: $courseId")
                            android.util.Log.d("ReinforceFrag", "   📖 courseName: $courseName")
                            android.util.Log.d("ReinforceFrag", "   📑 topicId: $topicId")
                            android.util.Log.d("ReinforceFrag", "   📝 taskId: $taskId")
                            android.util.Log.d("ReinforceFrag", "   🔍 Checking local MCP health before regenerating...")

                            // Use fastResolve to determine quickly whether a local host exists.
                            // Use fastResolve to determine quickly whether a local host exists.
                            val resolved = try { ServerEndpointResolver.fastResolveMcpBaseUrl() } catch (e: Exception) { null }
                            if (!resolved.isNullOrBlank() && resolved != ServerEndpointResolver.RAILWAY_MCP_URL) {
                                val verified = try { ServerEndpointResolver.isServiceReachable(resolved, "/health") } catch (e: Exception) { false }
                                if (verified) {
                                    android.util.Log.d("ReinforceFrag", "Local MCP resolved quickly and verified: $resolved — using local")
                                    try { mcpHttpClient.setForcedBaseUrl(resolved) } catch (_: Exception) {}
                                } else {
                                    android.util.Log.w("ReinforceFrag", "Resolved local MCP not reachable — using cloud immediately: ${ServerEndpointResolver.RAILWAY_MCP_URL}")
                                    try { mcpHttpClient.setForcedBaseUrl(ServerEndpointResolver.RAILWAY_MCP_URL) } catch (_: Exception) {}
                                }
                            } else {
                                android.util.Log.w("ReinforceFrag", "No local MCP resolved quickly — using cloud immediately: ${ServerEndpointResolver.RAILWAY_MCP_URL}")
                                try { mcpHttpClient.setForcedBaseUrl(ServerEndpointResolver.RAILWAY_MCP_URL) } catch (_: Exception) {}
                            }

                            // Always invoke the ViewModel; it will use the forced base URL set above
                            // and will do a very fast local attempt or immediate cloud call accordingly.
                            viewModel.forceRegenerateQuestions(courseId, courseName, topicId, taskId, difficulty)
                            android.util.Log.d("ReinforceFrag", "═══════════════════════════════════════════════════")
                        }
                    },
                    viewModel = viewModel
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // If LLM is generating questions and user leaves, schedule background task
        viewModel.scheduleBackgroundTaskIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        // Check if there are pending background results
        if (fragmentCourseId != -1L) {
            viewModel.checkForPendingBackgroundResults(fragmentCourseId)?.let { json ->
                // Questions loaded silently from background
            }
        }
    }
}
