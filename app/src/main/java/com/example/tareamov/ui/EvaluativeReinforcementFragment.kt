package com.example.tareamov.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.tareamov.ui.compose.EvaluativeReinforcementScreen
import com.example.tareamov.R

class EvaluativeReinforcementFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setContent {
                EvaluativeReinforcementScreen(
                    onContinueClick = {
                        findNavController().popBackStack()
                    },
                    onCourseSelectionClick = {
                        // Navigate to course selection screen
                        findNavController().navigate(R.id.action_evaluativeReinforcementFragment_to_courseSelectionFragment)
                    }
                )
            }
        }
    }
}
