package com.example.tareamov.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Course
import com.example.tareamov.repository.CourseRepository
import kotlinx.coroutines.launch

/**
 * ViewModel for handling course-related data and business logic
 */
class CursoViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = CourseRepository(application)

    // LiveData for courses - using Course entity
    private val _cursoData = MutableLiveData<List<Course>>()
    val cursoData: LiveData<List<Course>> = _cursoData

    // LiveData for a single course - using Course entity
    private val _selectedCurso = MutableLiveData<Course?>()
    val selectedCurso: LiveData<Course?> = _selectedCurso

    // LiveData for loading state
    private val _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> = _isLoading

    // LiveData for error messages
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    /**
     * Load all courses
     */
    fun loadCursoData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                val courses = repository.getAllCourses()
                _cursoData.value = courses
            } catch (e: Exception) {
                _errorMessage.value = "Error loading courses: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Load a specific course by ID
     */
    fun loadCursoById(courseId: Long) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                val course = repository.getCourseById(courseId)
                _selectedCurso.value = course
            } catch (e: Exception) {
                _errorMessage.value = "Error loading course: ${e.message}"
                _selectedCurso.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Save a new course
     */
    fun saveCurso(course: Course, onSuccess: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                val savedCourseId = repository.saveCourse(course)
                onSuccess(savedCourseId)
            } catch (e: Exception) {
                _errorMessage.value = "Error saving course: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Update an existing course
     */
    fun updateCurso(course: Course, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                repository.updateCourse(course)
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = "Error updating course: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Delete a course
     */
    fun deleteCurso(courseId: Long, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                repository.deleteCourseById(courseId)
                onSuccess()
            } catch (e: Exception) {
                _errorMessage.value = "Error deleting course: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    // Legacy VideoData operations for backward compatibility
    
    /**
     * Load all videos (legacy method)
     */
    fun loadVideoData() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _errorMessage.value = null

                val videos = repository.getAllVideos()
                // Convert VideoData to Course for consistency
                val courses = videos.map { video ->
                    // Get user ID from username
                    val userId = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(video.username) ?: 0L
                    
                    Course(
                        id = video.id,
                        title = video.title,
                        description = video.description,
                        creatorUserId = userId,
                        thumbnailUri = video.thumbnailUri,
                        videoUri = video.videoUriString,
                        localFilePath = video.localFilePath,
                        duration = null,
                        category = null,
                        price = video.price ?: 0.0,
                        isPremium = video.isPaid,
                        isPublished = true,
                        creationDate = "",
                        lastModifiedDate = "",
                        enrollmentCount = 0,
                        rating = 0.0f,
                        tags = null
                    )
                }
                _cursoData.value = courses
            } catch (e: Exception) {
                _errorMessage.value = "Error loading videos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}