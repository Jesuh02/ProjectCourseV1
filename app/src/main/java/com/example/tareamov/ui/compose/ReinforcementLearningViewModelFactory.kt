package com.example.tareamov.ui.compose

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ReinforcementLearningViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReinforcementLearningViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReinforcementLearningViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
