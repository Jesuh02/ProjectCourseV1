package com.example.tareamov.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.ui.platform.ComposeView
import androidx.compose.runtime.produceState
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.sync.SyncRepository
import com.example.tareamov.ui.compose.ReinforcementLearningScreen
import com.example.tareamov.ui.compose.ReinforcementLearningViewModel
import com.example.tareamov.ui.compose.ReinforcementLearningViewModelFactory

class ReinforcementLearningFragment : Fragment() {

    private lateinit var viewModel: ReinforcementLearningViewModel
    private var fragmentCourseId: Long = -1L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Initialize dependencies manually for simplicity
        val db = AppDatabase.getDatabase(requireContext())
        val syncRepository = SyncRepository(
            usuarioDao = db.usuarioDao(),
            personaDao = db.personaDao(),
            topicDao = db.topicDao(),
            contentItemDao = db.contentItemDao(),
            taskDao = db.taskDao(),
            subscriptionDao = db.subscriptionDao(),
            taskSubmissionDao = db.taskSubmissionDao(),
            videoDao = db.videoDao(),
            courseDao = db.courseDao(),
            rolDao = db.rolDao(),
            recursoDao = db.recursoDao(),
            rolRecursoDao = db.rolRecursoDao(),
            chatMessageDao = db.chatMessageDao(),
            fileContextDao = db.fileContextDao(),
            progresoEstudianteDao = db.progresoEstudianteDao(),
            likeDao = db.likeDao(),
            videoCommentDao = db.videoCommentDao()
        )
        // Init cache if needed
        syncRepository.initWithContext(requireContext())

        // Create ViewModel
        val factory = ReinforcementLearningViewModelFactory(requireActivity().application, syncRepository)
        viewModel = ViewModelProvider(this, factory).get(ReinforcementLearningViewModel::class.java)

        // Get navigation arguments
        val courseId = arguments?.getLong("courseId") ?: -1L
        fragmentCourseId = courseId
        val topicId = arguments?.getLong("topicId") ?: -1L
        val taskId = arguments?.getLong("taskId") ?: -1L
        
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

                // Fetch creator username and avatar asynchronously and expose to Compose
                val creatorInfo = produceState<Pair<String?, String?>>(initialValue = Pair(instructorArg, null)) {
                    val initialUsername = instructorArg
                    var resolvedUsername: String? = initialUsername
                    var avatarUrl: String? = null

                    if ((resolvedUsername.isNullOrBlank() || resolvedUsername == "Docente no especificado") && courseId > 0) {
                        try {
                            // Try new method: fetch by course ID directly
                            val username = syncRepository.fetchCreatorUsernameByCourseId(courseId)
                            if (username != null) {
                                resolvedUsername = username
                            } else {
                                resolvedUsername = syncRepository.fetchCreatorNameByCourseTitle(courseName)
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("ReinforceFrag", "couldn't fetch creator name: ${e.message}")
                        }
                    }

                    if (!resolvedUsername.isNullOrBlank()) {
                        try {
                            val usuario = com.example.tareamov.service.SupabaseClient.fetchUsuarioByUsername(resolvedUsername)
                            avatarUrl = usuario?.avatar
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
                    onBackClick = {
                        findNavController().popBackStack()
                    },
                    onStartClick = {
                        // ALWAYS force regeneration to get NEW questions from the server
                        // This ensures a fresh call to the backend every time
                        android.util.Log.d("ReinforceFrag", "═══════════════════════════════════════════════════")
                        android.util.Log.d("ReinforceFrag", "🚀 onStartClick TRIGGERED!")
                        android.util.Log.d("ReinforceFrag", "   📚 courseId: $courseId")
                        android.util.Log.d("ReinforceFrag", "   📖 courseName: $courseName")
                        android.util.Log.d("ReinforceFrag", "   📑 topicId: $topicId")
                        android.util.Log.d("ReinforceFrag", "   📝 taskId: $taskId")
                        android.util.Log.d("ReinforceFrag", "   🔥 Calling viewModel.forceRegenerateQuestions()...")
                        android.util.Log.d("ReinforceFrag", "═══════════════════════════════════════════════════")
                        viewModel.forceRegenerateQuestions(courseId, courseName, topicId, taskId)
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
                Toast.makeText(
                    requireContext(), 
                    "Preguntas generadas en segundo plano", 
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
