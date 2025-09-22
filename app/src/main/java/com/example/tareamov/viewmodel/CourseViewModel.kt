package com.example.tareamov.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.Course
import com.example.tareamov.repository.CourseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CourseRepository(application.applicationContext)

    private val _course = MutableLiveData<Course?>()
    val course: LiveData<Course?> = _course

    fun getCourseById(courseId: Long) {
        viewModelScope.launch {
            val course = withContext(Dispatchers.IO) {
                try {
                    repository.getCourseById(courseId)
                } catch (e: Exception) {
                    null
                }
            }
            _course.value = course
        }
    }
}