package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.ProgresoEstudiante
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.repository.CourseDetailRepository
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CourseTopicData(
    val topics: List<Topic>,
    val contentByTopic: Map<Long, List<ContentItem>>,
    val tasksByTopic: Map<Long, List<Task>>,
    val effectiveCourseId: Long,
    val fetchedAt: Long = System.currentTimeMillis()
)

data class CourseDetailSnapshot(
    val requestedCourseId: Long,
    val effectiveCourseId: Long,
    val course: Course?,
    val topics: List<Topic>,
    val contentByTopic: Map<Long, List<ContentItem>>,
    val tasksByTopic: Map<Long, List<Task>>,
    val progress: ProgresoEstudiante? = null,
    val fetchedAt: Long = System.currentTimeMillis()
)

class CourseViewModel(application: Application) : AndroidViewModel(application) {

    private val _course = MutableLiveData<Course?>()
    val course: LiveData<Course?> = _course

    private val courseDetailCache = mutableMapOf<Long, CourseDetailSnapshot>()
    private val dirtyCourseIds = mutableSetOf<Long>()

    val repository: CourseDetailRepository

    companion object {
        private const val CACHE_TTL_MS = 60_000L
    }

    init {
        BackendApiService.initialize(application.applicationContext)
        val db = AppDatabase.getDatabase(application.applicationContext)
        repository = CourseDetailRepository(
            db.courseDao(), db.topicDao(), db.taskDao(), db.contentItemDao()
        )
    }

    fun getCourseById(courseId: Long) {
        viewModelScope.launch {
            val course = withContext(Dispatchers.IO) {
                try { BackendApiService.getCourseById(courseId).getOrNull() }
                catch (e: Exception) { Log.e("CourseViewModel", "Error fetching course: ${e.message}"); null }
            }
            _course.value = course
            if (course != null) updateCachedCourse(courseId, course)
        }
    }

    fun getCourseDetailSnapshot(courseId: Long): CourseDetailSnapshot? = courseDetailCache[courseId]

    fun canRenderCourseDetailSnapshot(courseId: Long): Boolean {
        courseDetailCache[courseId] ?: return false
        return !dirtyCourseIds.contains(courseId)
    }

    fun shouldRefreshCourseDetailSnapshot(courseId: Long): Boolean {
        val snapshot = courseDetailCache[courseId] ?: return true
        if (dirtyCourseIds.contains(courseId)) return true
        return System.currentTimeMillis() - snapshot.fetchedAt >= CACHE_TTL_MS
    }

    fun setCourseDetailSnapshot(snapshot: CourseDetailSnapshot) {
        courseDetailCache[snapshot.requestedCourseId] = snapshot
        dirtyCourseIds.remove(snapshot.requestedCourseId)
        _course.postValue(snapshot.course)
    }

    fun markCourseDetailDirty(courseId: Long) {
        dirtyCourseIds.add(courseId)
    }

    fun updateCachedCourse(courseId: Long, course: Course) {
        _course.value = course
        courseDetailCache.keys
            .filter { it == courseId || courseDetailCache[it]?.effectiveCourseId == course.id }
            .forEach { key ->
                courseDetailCache[key]?.let { courseDetailCache[key] = it.copy(course = course) }
            }
    }

    fun removeTopicFromSnapshot(courseId: Long, topicId: Long): CourseDetailSnapshot? {
        val snapshot = courseDetailCache[courseId] ?: return null
        val updated = snapshot.copy(
            topics = snapshot.topics.filterNot { it.id == topicId },
            contentByTopic = snapshot.contentByTopic.filterKeys { it != topicId },
            tasksByTopic = snapshot.tasksByTopic.filterKeys { it != topicId },
            fetchedAt = System.currentTimeMillis()
        )
        courseDetailCache[courseId] = updated
        dirtyCourseIds.remove(courseId)
        viewModelScope.launch(Dispatchers.IO) { repository.deleteTopicLocally(topicId) }
        return updated
    }

    fun removeTaskFromSnapshot(courseId: Long, taskId: Long): CourseDetailSnapshot? {
        val snapshot = courseDetailCache[courseId] ?: return null
        val updated = snapshot.copy(
            tasksByTopic = snapshot.tasksByTopic.mapValues { (_, tasks) -> tasks.filterNot { it.id == taskId } },
            fetchedAt = System.currentTimeMillis()
        )
        courseDetailCache[courseId] = updated
        dirtyCourseIds.remove(courseId)
        viewModelScope.launch(Dispatchers.IO) { repository.deleteTaskLocally(taskId) }
        return updated
    }

    fun removeContentFromSnapshot(courseId: Long, contentId: Long): CourseDetailSnapshot? {
        val snapshot = courseDetailCache[courseId] ?: return null
        val updated = snapshot.copy(
            contentByTopic = snapshot.contentByTopic.mapValues { (_, items) -> items.filterNot { it.id == contentId } },
            fetchedAt = System.currentTimeMillis()
        )
        courseDetailCache[courseId] = updated
        dirtyCourseIds.remove(courseId)
        viewModelScope.launch(Dispatchers.IO) { repository.deleteContentLocally(contentId) }
        return updated
    }

    fun loadLocalSnapshot(courseId: Long, onResult: (CourseDetailSnapshot?) -> Unit) {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { repository.getLocalSnapshot(courseId) }
            if (snapshot != null && courseDetailCache[courseId] == null) {
                courseDetailCache[courseId] = snapshot
                _course.postValue(snapshot.course)
            }
            onResult(snapshot)
        }
    }

    fun getCourseTopicData(courseId: Long): CourseTopicData? =
        courseDetailCache[courseId]?.let {
            CourseTopicData(it.topics, it.contentByTopic, it.tasksByTopic, it.effectiveCourseId, it.fetchedAt)
        }

    fun isCourseTopicDataFresh(courseId: Long): Boolean {
        val data = courseDetailCache[courseId] ?: return false
        if (dirtyCourseIds.contains(courseId)) return false
        return System.currentTimeMillis() - data.fetchedAt < CACHE_TTL_MS
    }

    fun setCourseTopicData(data: CourseTopicData) {
        val current = courseDetailCache[data.effectiveCourseId]
        courseDetailCache[data.effectiveCourseId] = CourseDetailSnapshot(
            requestedCourseId = data.effectiveCourseId,
            effectiveCourseId = data.effectiveCourseId,
            course = current?.course,
            topics = data.topics,
            contentByTopic = data.contentByTopic,
            tasksByTopic = data.tasksByTopic,
            progress = current?.progress,
            fetchedAt = data.fetchedAt
        )
        dirtyCourseIds.remove(data.effectiveCourseId)
    }

    fun invalidateCourseTopicData(courseId: Long? = null) {
        if (courseId == null) {
            courseDetailCache.clear()
            dirtyCourseIds.clear()
        } else {
            courseDetailCache.remove(courseId)
            dirtyCourseIds.remove(courseId)
        }
    }
}