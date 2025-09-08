package com.example.tareamov.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Topic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectTopicViewModel(application: Application) : AndroidViewModel(application) {

    private val topicDao = AppDatabase.getDatabase(application).topicDao()

    private val _topics = MutableLiveData<List<Topic>>()
    val topics: LiveData<List<Topic>> = _topics

    // Function to fetch topics for a specific course
    fun fetchTopicsForCourse(courseId: Long) {
        viewModelScope.launch {
            val topicList = withContext(Dispatchers.IO) {
                topicDao.getTopicsByCourse(courseId)
            }
            _topics.postValue(topicList.sortedBy { it.orderIndex }) // Post sorted list
        }
    }
}