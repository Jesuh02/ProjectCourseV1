package com.example.tareamov.ui.compose

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.Course
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseSelectionViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _currentUsername = MutableStateFlow<String?>(null)
    val currentUsername: StateFlow<String?> = _currentUsername.asStateFlow()
    
    // Current user ID for adapter subscription checks
    private val _currentUserId = MutableStateFlow<Long?>(null)
    val currentUserId: StateFlow<Long?> = _currentUserId.asStateFlow()
    
    // Master list of all enrolled courses
    private var allEnrolledCourses = listOf<Course>()
    
    private val _enrolledCourses = MutableStateFlow<List<Course>>(emptyList())
    val enrolledCourses: StateFlow<List<Course>> = _enrolledCourses.asStateFlow()
    
    private val _completedCourseIds = MutableStateFlow<Set<Long>>(emptySet())
    val completedCourseIds: StateFlow<Set<Long>> = _completedCourseIds.asStateFlow()
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    // Cache for subscription status
    private val _subscriptionStatus = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
    val subscriptionStatus: StateFlow<Map<Long, Boolean>> = _subscriptionStatus.asStateFlow()
    
    // Refresh trigger to force adapter update
    private val _refreshTrigger = MutableStateFlow(0)
    val refreshTrigger: StateFlow<Int> = _refreshTrigger.asStateFlow()
    
    init {
        BackendApiService.initialize(application.applicationContext)
        loadCurrentUserId()
        loadEnrolledCourses()
    }
    
    /**
     * Load current user ID from session
     */
    private fun loadCurrentUserId() {
        viewModelScope.launch {
            try {
                val session = com.example.tareamov.util.SessionManager.getInstance(getApplication())
                val username = session.getUsername()
                _currentUsername.value = username
                
                // Use session user ID directly (set during login via BackendApiService)
                val userId = session.getUserId()
                if (userId > 0L) {
                    _currentUserId.value = userId
                }
            } catch (e: Exception) {
                Log.e("CourseSelectionVM", "Error loading user ID: ${e.message}")
            }
        }
    }
    
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        filterCourses(query)
    }
    
    /**
     * Load subscription status for enrolled courses from backend
     */
    private fun loadSubscriptionStatus() {
        viewModelScope.launch {
            try {
                val userId = _currentUserId.value ?: return@launch
                
                if (userId > 0L) {
                    val subscriptionMap = mutableMapOf<Long, Boolean>()
                    
                    // Check subscription status for each course creator from backend
                    allEnrolledCourses.forEach { course ->
                        val isSubscribed = withContext(Dispatchers.IO) {
                            val result = BackendApiService.checkSubscription(course.creatorUserId)
                            result.getOrNull() ?: false
                        }
                        subscriptionMap[course.creatorUserId] = isSubscribed
                    }
                    
                    _subscriptionStatus.value = subscriptionMap
                }
            } catch (e: Exception) {
                Log.e("CourseSelectionVM", "Error loading subscription status: ${e.message}")
            }
        }
    }
    
    /**
     * Check if user is subscribed to a specific course creator
     */
    fun isUserSubscribedToCreator(creatorUserId: Long): Boolean {
        return _subscriptionStatus.value[creatorUserId] ?: false
    }
    
    /**
     * Handle subscription button click via backend
     */
    fun handleSubscriptionClick(course: Course, isCurrentlySubscribed: Boolean) {
        viewModelScope.launch {
            try {
                val userId = _currentUserId.value ?: return@launch
                
                if (userId <= 0L) return@launch
                
                val creatorUserId = course.creatorUserId
                
                // Prevent self-subscription
                if (userId == creatorUserId) return@launch
                
                withContext(Dispatchers.IO) {
                    if (isCurrentlySubscribed) {
                        BackendApiService.unsubscribe(creatorUserId)
                    } else {
                        BackendApiService.subscribe(creatorUserId)
                    }
                }
                
                // Update local subscription status immediately (optimistic update)
                val updatedStatus = _subscriptionStatus.value.toMutableMap()
                updatedStatus[creatorUserId] = !isCurrentlySubscribed
                _subscriptionStatus.value = updatedStatus
                
                // Trigger adapter refresh
                _refreshTrigger.value = _refreshTrigger.value + 1
                
            } catch (e: Exception) {
                Log.e("CourseSelectionVM", "Error toggling subscription: ${e.message}")
            }
        }
    }
    
    private fun filterCourses(query: String) {
        if (query.isBlank()) {
            _enrolledCourses.value = allEnrolledCourses
        } else {
            val lowerQuery = query.lowercase()
            _enrolledCourses.value = allEnrolledCourses.filter { course ->
                course.title.lowercase().contains(lowerQuery) ||
                course.description?.lowercase()?.contains(lowerQuery) == true ||
                course.category?.lowercase()?.contains(lowerQuery) == true
            }
        }
    }
    
    private fun loadEnrolledCourses() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val session = com.example.tareamov.util.SessionManager.getInstance(getApplication())
                val userId = session.getUserId()
                _currentUsername.value = session.getUsername()
                
                if (_currentUserId.value == null && userId > 0L) {
                    _currentUserId.value = userId
                }
                
                if (userId > 0L) {
                    // Fetch ALL courses from backend
                    val allCourses = try {
                        withContext(Dispatchers.IO) {
                            val result = BackendApiService.getCourses(page = 1, limit = 200)
                            when (result) {
                                is ApiResult.Success -> result.data ?: emptyList()
                                is ApiResult.Error -> {
                                    Log.e("CourseSelectionVM", "Error fetching courses: ${result.message}")
                                    emptyList()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CourseSelectionVM", "Exception fetching courses: ${e.message}")
                        emptyList()
                    }

                    // Show ALL courses sorted by newest first
                    val coursesList = allCourses.sortedByDescending { it.timestamp }
                    
                    allEnrolledCourses = coursesList
                    _enrolledCourses.value = coursesList
                    
                    // Update subscription status after loading courses
                    loadSubscriptionStatus()
                    
                    // Update completion status
                    updateCompletedStatus(coursesList)
                } else {
                    allEnrolledCourses = emptyList()
                    _enrolledCourses.value = emptyList()
                    _completedCourseIds.value = emptySet()
                }
            } catch (e: Exception) {
                Log.e("CourseSelectionVM", "Error loading courses: ${e.message}")
                _enrolledCourses.value = emptyList()
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    private suspend fun updateCompletedStatus(courses: List<Course>) {
        val session = com.example.tareamov.util.SessionManager.getInstance(getApplication())
        val userId = session.getUserId()
        if (userId == -1L) return
        
        try {
            val completedIds = mutableSetOf<Long>()
            
            // Fetch progress from backend
            val progresos = try {
                withContext(Dispatchers.IO) {
                    val result = BackendApiService.getMyProgress()
                    when (result) {
                        is ApiResult.Success -> result.data ?: emptyList()
                        is ApiResult.Error -> emptyList()
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
            
            progresos.forEach { p ->
                if ((p.porcentajeProgreso ?: 0f) >= 100f && p.cursoId != null) {
                    completedIds.add(p.cursoId!!)
                }
            }
            
            _completedCourseIds.value = completedIds
        } catch (e: Exception) {
            Log.e("CourseSelectionVM", "Error updating completed status: ${e.message}")
        }
    }
}
