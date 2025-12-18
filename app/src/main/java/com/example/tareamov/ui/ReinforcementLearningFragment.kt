package com.example.tareamov.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
            videoLikeDao = db.videoLikeDao(),
            videoCommentDao = db.videoCommentDao()
        )
        // Init cache if needed
        syncRepository.initWithContext(requireContext())

        // Create ViewModel
        val factory = ReinforcementLearningViewModelFactory(requireActivity().application, syncRepository)
        viewModel = ViewModelProvider(this, factory).get(ReinforcementLearningViewModel::class.java)

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
                val instructorArg = arguments?.getString("instructorName")

                // Fetch creator username and avatar asynchronously and expose to Compose
                val creatorInfo = produceState<Pair<String?, String?>>(initialValue = Pair(instructorArg, null)) {
                    val initialUsername = instructorArg
                    var resolvedUsername: String? = initialUsername
                    var avatarUrl: String? = null

                    if (resolvedUsername.isNullOrBlank() && courseId > 0) {
                        try {
                            resolvedUsername = syncRepository.fetchCreatorNameByCourseTitle(courseName)
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
                        if (viewModel.uiState.value is com.example.tareamov.ui.compose.ReinforcementState.Initial ||
                            viewModel.uiState.value is com.example.tareamov.ui.compose.ReinforcementState.Error) {
                            viewModel.loadQuestions(courseId, courseName)
                        }
                        // If already Success, do nothing (quiz will start)
                    },
                    viewModel = viewModel
                )
            }
        }
    }
}
