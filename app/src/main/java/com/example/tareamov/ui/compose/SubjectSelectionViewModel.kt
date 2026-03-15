package com.example.tareamov.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.Subject
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class SubjectSelectionViewModel(application: Application) : AndroidViewModel(application) {

    private val _allSubjects = MutableStateFlow<List<Subject>>(emptyList())
    private val _searchQuery = MutableStateFlow("")
    private val _isLoading = MutableStateFlow(false)

    val searchQuery: StateFlow<String> = _searchQuery
    val isLoading: StateFlow<Boolean> = _isLoading

    val subjects: StateFlow<List<Subject>> = combine(_allSubjects, _searchQuery) { subjects, query ->
        if (query.isBlank()) subjects
        else subjects.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.description.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun loadSubjects(courseId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            BackendApiService.initialize(getApplication())
            when (val result = BackendApiService.getSubjectsByCourse(courseId)) {
                is ApiResult.Success -> _allSubjects.value = result.data.filter { it.isActive }.sortedBy { it.orderIndex }
                is ApiResult.Error -> _allSubjects.value = emptyList()
            }
            _isLoading.value = false
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }
}
