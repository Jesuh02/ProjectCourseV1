package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.Course
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseViewModel(application: Application) : AndroidViewModel(application) {

    private val _course = MutableLiveData<Course?>()
    val course: LiveData<Course?> = _course

    init {
        BackendApiService.initialize(application.applicationContext)
    }

    fun getCourseById(courseId: Long) {
        viewModelScope.launch {
            val course = withContext(Dispatchers.IO) {
                try {
                    val result = BackendApiService.getCourseById(courseId)
                    result.getOrNull()
                } catch (e: Exception) {
                    Log.e("CourseViewModel", "Error fetching course: ${e.message}")
                    null
                }
            }
            _course.value = course
        }
    }
}