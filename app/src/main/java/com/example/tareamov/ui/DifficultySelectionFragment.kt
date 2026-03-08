package com.example.tareamov.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.ui.compose.DifficultyLevel
import com.example.tareamov.ui.compose.DifficultySelectionScreen

class DifficultySelectionFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val courseId    = arguments?.getLong("courseId") ?: -1L
        val courseName  = arguments?.getString("courseName") ?: ""
        val topicId     = arguments?.getLong("topicId") ?: -1L
        val taskId      = arguments?.getLong("taskId") ?: -1L
        val taskName    = arguments?.getString("taskName")

        return ComposeView(requireContext()).apply {
            setContent {
                DifficultySelectionScreen(
                    courseName = courseName,
                    taskName = taskName,
                    onDifficultySelected = { level, freeLearning ->
                        val bundle = Bundle().apply {
                            putLong("courseId", courseId)
                            putString("courseName", courseName)
                            putLong("topicId", topicId)
                            putLong("taskId", taskId)
                            putString("instructorName", "")
                            putString("difficulty", level.name)
                            putBoolean("freeLearning", freeLearning)
                        }
                        findNavController().navigate(
                            R.id.action_selectDifficultyFragment_to_reinforcementLearningFragment,
                            bundle
                        )
                    },
                    onBackClick = { findNavController().popBackStack() }
                )
            }
        }
    }
}
