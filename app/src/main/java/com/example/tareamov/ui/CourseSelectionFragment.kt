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

class CourseSelectionFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val vm = androidx.lifecycle.ViewModelProvider(
            this,
            androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
        ).get(com.example.tareamov.ui.compose.CourseSelectionViewModel::class.java)

        return ComposeView(requireContext()).apply {
            setContent {
                CourseSelectionScreen(
                    viewModel = vm,
                    onBackClick = {
                        findNavController().popBackStack()
                    },
                    onCourseSelected = { course ->
                        // Navigate to the actual reinforcement learning screen with the selected course
                        val bundle = Bundle().apply {
                            putLong("courseId", course.id)
                            putString("courseName", course.title)
                            putString("instructorName", "Docente no especificado")
                        }
                        findNavController().navigate(
                            R.id.action_courseSelectionFragment_to_reinforcementLearningFragment,
                            bundle
                        )
                    }
                )
            }
        }
    }
}