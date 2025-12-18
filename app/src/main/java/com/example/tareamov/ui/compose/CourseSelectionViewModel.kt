package com.example.tareamov.ui.compose

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.sync.SyncRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseSelectionViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()
    
    private val _currentUsername = MutableStateFlow<String?>(null)
    val currentUsername: StateFlow<String?> = _currentUsername.asStateFlow()
    
    // Master list of all enrolled courses
    private var allEnrolledCourses = listOf<Course>()
    
    private val _enrolledCourses = MutableStateFlow<List<Course>>(emptyList())
    val enrolledCourses: StateFlow<List<Course>> = _enrolledCourses.asStateFlow()
    
    private val _completedCourseIds = MutableStateFlow<Set<Long>>(emptySet())
    val completedCourseIds: StateFlow<Set<Long>> = _completedCourseIds.asStateFlow()
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    
    private val database by lazy { AppDatabase.getDatabase(getApplication()) }
    
    // Create SyncRepository instance manually since we don't have DI
    private val syncRepo by lazy {
        SyncRepository(
            database.usuarioDao(),
            database.personaDao(),
            database.topicDao(),
            database.contentItemDao(),
            database.taskDao(),
            database.subscriptionDao(),
            database.taskSubmissionDao(),
            database.videoDao(),
            database.courseDao(),
            database.rolDao(),
            database.recursoDao(),
            database.rolRecursoDao(),
            database.chatMessageDao(),
            database.fileContextDao(),
            database.progresoEstudianteDao(),
            database.videoLikeDao(),
            database.videoCommentDao()
        ).apply {
            initWithContext(getApplication())
        }
    }
    
    init {
        loadEnrolledCourses()
    }
    
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        filterCourses(query)
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
                
                if (userId > 0L) {
                    // 1. Fetch ALL courses first (to have full data, consistent with ExploreFragment)
                    val allCourses = try {
                        withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.fetchCourses()
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }

                    // 2. Fetch enrolled IDs
                    val enrolledIds = try {
                        withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.fetchEnrolledCourseIds(userId)
                        }
                    } catch (e: Exception) {
                        emptyList()
                    }

                    // 3. Filter courses
                    val enrolledCoursesList = allCourses.filter { course ->
                        enrolledIds.contains(course.id) && course.creatorUserId != userId
                    }.sortedByDescending { it.timestamp }
                    
                    // Update master list and displayed list
                    allEnrolledCourses = enrolledCoursesList
                    _enrolledCourses.value = enrolledCoursesList
                    
                    // 4. Update completion status
                    updateCompletedStatus(enrolledCoursesList)
                } else {
                    allEnrolledCourses = emptyList()
                    _enrolledCourses.value = emptyList()
                    _completedCourseIds.value = emptySet()
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
            
            // For each course, check if progress is 100%
            val progresos = try {
                withContext(Dispatchers.IO) {
                    syncRepo.fetchProgresosByUsuarioFromSupabase(userId)
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
            e.printStackTrace()
        }
    }
}