package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectTopicViewModel(application: Application) : AndroidViewModel(application) {

    private val _topics = MutableLiveData<List<Topic>>()
    val topics: LiveData<List<Topic>> = _topics

    init {
        BackendApiService.initialize(application.applicationContext)
    }

    // Function to fetch topics for a specific course from backend
    fun fetchTopicsForCourse(courseId: Long) {
        viewModelScope.launch {
            val topicList = withContext(Dispatchers.IO) {
                val result = BackendApiService.getTopicsByCourse(courseId)
                when (result) {
                    is ApiResult.Success -> result.data ?: emptyList()
                    is ApiResult.Error -> {
                        Log.e("SelectTopicVM", "Error fetching topics: ${result.message}")
                        emptyList()
                    }
                }
            }
            _topics.postValue(topicList.sortedBy { it.orderIndex })
        }
    }
}