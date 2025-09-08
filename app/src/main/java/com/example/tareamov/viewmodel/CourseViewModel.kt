package com.example.tareamov.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.VideoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val videoDao = database.videoDao()

    private val _course = MutableLiveData<VideoData?>()
    val course: LiveData<VideoData?> = _course

    fun getCourseById(courseId: Long) {
        viewModelScope.launch {
            val course = withContext(Dispatchers.IO) {
                videoDao.getVideoById(courseId)
            }
            _course.value = course
        }
    }
}