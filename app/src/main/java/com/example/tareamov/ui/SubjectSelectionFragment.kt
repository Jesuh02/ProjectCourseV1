package com.example.tareamov.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.ui.compose.SubjectSelectionScreen
import com.example.tareamov.ui.compose.SubjectSelectionViewModel

class SubjectSelectionFragment : Fragment() {

    private var courseId: Long = -1L
    private var courseName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong("courseId", -1L)
            courseName = it.getString("courseName", "")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val vm = ViewModelProvider(
            this,
            ViewModelProvider.AndroidViewModelFactory.getInstance(requireActivity().application)
        ).get(SubjectSelectionViewModel::class.java)

        vm.loadSubjects(courseId)

        return ComposeView(requireContext()).apply {
            setContent {
                SubjectSelectionScreen(
                    courseName = courseName,
                    onBackClick = { findNavController().popBackStack() },
                    onSubjectSelected = { subject ->
                        val bundle = Bundle().apply {
                            putLong("courseId", courseId)
                            putString("courseName", courseName)
                            putLong("subjectId", subject.id)
                            putString("subjectName", subject.name)
                        }
                        findNavController().navigate(
                            R.id.action_subjectSelectionFragment_to_selectTopicFragment,
                            bundle
                        )
                    },
                    viewModel = vm
                )
            }
        }
    }
}
