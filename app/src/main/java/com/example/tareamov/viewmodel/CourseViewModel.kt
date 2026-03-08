package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.ProgresoEstudiante
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-memory Single Source of Truth for a course's full topic/content/task tree.
 * L1 memory cache with TTL, keyed per courseId.
 */
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

/**
 * Scoped to the Activity so the cache survives Fragment destruction and re-creation
 * (e.g. pressing Back and then re-entering a course detail screen).
 *
 * Stores topic/content/task data keyed by courseId, so navigating between different
 * courses never returns stale data from another course.
 */
class CourseViewModel(application: Application) : AndroidViewModel(application) {

    private val _course = MutableLiveData<Course?>()
    val course: LiveData<Course?> = _course

    // L1 memory cache: per requested courseId, survives Fragment view cycles within the same Activity
    private val courseDetailCache = mutableMapOf<Long, CourseDetailSnapshot>()
    private val dirtyCourseIds = mutableSetOf<Long>()

    companion object {
        private const val CACHE_TTL_MS = 30_000L
        private const val INSTANT_RENDER_WINDOW_MS = 15_000L
    }

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
            if (course != null) {
                updateCachedCourse(courseId, course)
            }
        }
    }

    fun getCourseDetailSnapshot(courseId: Long): CourseDetailSnapshot? = courseDetailCache[courseId]

    fun canRenderCourseDetailSnapshot(courseId: Long): Boolean {
        val snapshot = courseDetailCache[courseId] ?: return false
        if (dirtyCourseIds.contains(courseId)) return false
        return System.currentTimeMillis() - snapshot.fetchedAt < INSTANT_RENDER_WINDOW_MS
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

        val matchingKeys = courseDetailCache.keys.filter { requestedId ->
            val snapshot = courseDetailCache[requestedId]
            requestedId == courseId || snapshot?.effectiveCourseId == course.id
        }

        matchingKeys.forEach { key ->
            val snapshot = courseDetailCache[key] ?: return@forEach
            courseDetailCache[key] = snapshot.copy(course = course)
        }
    }

    fun removeTopicFromSnapshot(courseId: Long, topicId: Long): CourseDetailSnapshot? {
        val snapshot = courseDetailCache[courseId] ?: return null
        val topics = snapshot.topics.filterNot { it.id == topicId }
        val contentByTopic = snapshot.contentByTopic.filterKeys { it != topicId }
        val tasksByTopic = snapshot.tasksByTopic.filterKeys { it != topicId }
        val updated = snapshot.copy(
            topics = topics,
            contentByTopic = contentByTopic,
            tasksByTopic = tasksByTopic,
            fetchedAt = System.currentTimeMillis()
        )
        courseDetailCache[courseId] = updated
        dirtyCourseIds.remove(courseId)
        return updated
    }

    fun removeTaskFromSnapshot(courseId: Long, taskId: Long): CourseDetailSnapshot? {
        val snapshot = courseDetailCache[courseId] ?: return null
        val tasksByTopic = snapshot.tasksByTopic.mapValues { (_, tasks) ->
            tasks.filterNot { it.id == taskId }
        }
        val updated = snapshot.copy(
            tasksByTopic = tasksByTopic,
            fetchedAt = System.currentTimeMillis()
        )
        courseDetailCache[courseId] = updated
        dirtyCourseIds.remove(courseId)
        return updated
    }

    /** Returns cached topic tree for [courseId], or null if not present. */
    fun getCourseTopicData(courseId: Long): CourseTopicData? =
        courseDetailCache[courseId]?.let {
            CourseTopicData(
                topics = it.topics,
                contentByTopic = it.contentByTopic,
                tasksByTopic = it.tasksByTopic,
                effectiveCourseId = it.effectiveCourseId,
                fetchedAt = it.fetchedAt
            )
        }

    /** Returns true if cached data exists and is within TTL for [courseId]. */
    fun isCourseTopicDataFresh(courseId: Long): Boolean {
        val data = courseDetailCache[courseId] ?: return false
        if (dirtyCourseIds.contains(courseId)) return false
        return System.currentTimeMillis() - data.fetchedAt < CACHE_TTL_MS
    }

    fun setCourseTopicData(data: CourseTopicData) {
        val currentSnapshot = courseDetailCache[data.effectiveCourseId]
        courseDetailCache[data.effectiveCourseId] = CourseDetailSnapshot(
            requestedCourseId = data.effectiveCourseId,
            effectiveCourseId = data.effectiveCourseId,
            course = currentSnapshot?.course,
            topics = data.topics,
            contentByTopic = data.contentByTopic,
            tasksByTopic = data.tasksByTopic,
            progress = currentSnapshot?.progress,
            fetchedAt = data.fetchedAt
        )
        dirtyCourseIds.remove(data.effectiveCourseId)
    }

    /**
     * Invalidate cached data.
     * @param courseId invalidates only this course; null clears the entire cache.
     */
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