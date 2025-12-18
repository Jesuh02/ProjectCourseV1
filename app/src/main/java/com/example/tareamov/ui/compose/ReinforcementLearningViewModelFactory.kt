package com.example.tareamov.ui.compose

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.tareamov.data.sync.SyncRepository

class ReinforcementLearningViewModelFactory(
    private val application: Application,
    private val syncRepository: SyncRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReinforcementLearningViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReinforcementLearningViewModel(application, syncRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
