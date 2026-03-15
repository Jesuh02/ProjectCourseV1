package com.example.tareamov.repository

import android.util.Log
import com.example.tareamov.data.dao.ContentItemDao
import com.example.tareamov.data.dao.CourseDao
import com.example.tareamov.data.dao.TaskDao
import com.example.tareamov.data.dao.TopicDao
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.viewmodel.CourseDetailSnapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CourseDetailRepository(
    private val courseDao: CourseDao,
    private val topicDao: TopicDao,
    private val taskDao: TaskDao,
    private val contentItemDao: ContentItemDao
) {
    suspend fun getLocalSnapshot(courseId: Long): CourseDetailSnapshot? = withContext(Dispatchers.IO) {
        val course = courseDao.getCourseById(courseId) ?: return@withContext null
        val topics = topicDao.getTopicsByCourse(courseId).sortedBy { it.orderIndex }
        if (topics.isEmpty()) return@withContext CourseDetailSnapshot(
            requestedCourseId = courseId,
            effectiveCourseId = courseId,
            course = course,
            topics = emptyList(),
            contentByTopic = emptyMap(),
            tasksByTopic = emptyMap()
        )
        val tasks = taskDao.getTasksByCourse(courseId).sortedBy { it.orderIndex }
        val content = contentItemDao.getContentItemsByCourse(courseId).sortedBy { it.orderIndex ?: 0 }
        CourseDetailSnapshot(
            requestedCourseId = courseId,
            effectiveCourseId = courseId,
            course = course,
            topics = topics,
            contentByTopic = content.groupBy { it.topicId },
            tasksByTopic = tasks.groupBy { it.topicId }
        )
    }

    suspend fun fetchAndCacheSnapshot(
        courseId: Long,
        courseName: String,
        userId: Long,
        isCreator: Boolean
    ): CourseDetailSnapshot? = withContext(Dispatchers.IO) {
        var resolvedCourse = BackendApiService.getCourseById(courseId).getOrNull()
        var effectiveCourseId = courseId

        if (resolvedCourse == null && courseName.isNotBlank()) {
            val matched = BackendApiService.searchCourses(courseName).getOrNull()
                ?.firstOrNull { it.title.trim().equals(courseName.trim(), ignoreCase = true) }
            if (matched != null) {
                resolvedCourse = matched
                effectiveCourseId = matched.id
            }
        }

        val finalId = effectiveCourseId
        val (topics, allTasks, allContent, progress) = coroutineScope {
            val topicsDeferred = async { BackendApiService.getTopicsByCourse(finalId).getOrNull().orEmpty().sortedBy { it.orderIndex } }
            val tasksDeferred = async { BackendApiService.getTasksByCourse(finalId).getOrNull().orEmpty().sortedBy { it.orderIndex } }
            val contentDeferred = async { BackendApiService.getContentItemsByCourse(finalId).getOrNull().orEmpty().sortedBy { it.orderIndex ?: 0 } }
            val progressDeferred = async {
                if (userId > 0 && !isCreator) (BackendApiService.getProgressByCourse(finalId) as? ApiResult.Success)?.data else null
            }
            data class FetchResult(
                val topics: List<Topic>,
                val tasks: List<Task>,
                val content: List<ContentItem>,
                val progress: com.example.tareamov.data.entity.ProgresoEstudiante?
            )
            FetchResult(topicsDeferred.await(), tasksDeferred.await(), contentDeferred.await(), progressDeferred.await())
        }

        persistToRoom(effectiveCourseId, resolvedCourse, topics, allTasks, allContent)

        CourseDetailSnapshot(
            requestedCourseId = courseId,
            effectiveCourseId = effectiveCourseId,
            course = resolvedCourse,
            topics = topics,
            contentByTopic = allContent.groupBy { it.topicId },
            tasksByTopic = allTasks.groupBy { it.topicId },
            progress = progress
        )
    }

    private fun sanitizeCourse(c: Course): Course {
        val t = c.title as String?
        val d = c.description as String?
        val cd = c.creationDate as String?
        val lm = c.lastModifiedDate as String?
        if (t == null || d == null || cd == null || lm == null) {
            return c.copy(
                title = t ?: "",
                description = d ?: "",
                creationDate = cd ?: "",
                lastModifiedDate = lm ?: ""
            )
        }
        return c
    }

    private fun sanitizeTopics(list: List<Topic>): List<Topic> = list.map { t ->
        val n = t.name as String?
        val d = t.description as String?
        if (n == null || d == null) t.copy(name = n ?: "", description = d ?: "") else t
    }

    private fun sanitizeTasks(list: List<Task>): List<Task> = list.map { t ->
        val n = t.name as String?
        if (n == null) t.copy(name = n ?: "") else t
    }

    private fun sanitizeContent(list: List<ContentItem>): List<ContentItem> = list.map { c ->
        val u = c.uriString as String?
        val ct = c.contentType as String?
        if (u == null || ct == null) c.copy(uriString = u ?: "", contentType = ct ?: "") else c
    }

    private suspend fun persistToRoom(
        courseId: Long,
        course: Course?,
        topics: List<Topic>,
        tasks: List<Task>,
        content: List<ContentItem>
    ) {
        try {
            if (course != null) courseDao.insertCourse(sanitizeCourse(course))
            topicDao.deleteTopicsByCourse(courseId)
            if (topics.isNotEmpty()) topicDao.insertAll(sanitizeTopics(topics))
            if (tasks.isNotEmpty()) taskDao.insertAll(sanitizeTasks(tasks))
            if (content.isNotEmpty()) contentItemDao.insertAll(sanitizeContent(content))
        } catch (e: Exception) {
            Log.w("CourseDetailRepo", "Room persist failed (non-fatal): ${e.message}")
        }
    }

    suspend fun deleteTopicLocally(topicId: Long) = withContext(Dispatchers.IO) {
        topicDao.deleteTopic(topicId)
    }

    suspend fun deleteTopicRemote(topicId: Long): Boolean = withContext(Dispatchers.IO) {
        BackendApiService.deleteTopic(topicId) is ApiResult.Success
    }

    suspend fun deleteTaskLocally(taskId: Long) = withContext(Dispatchers.IO) {
        taskDao.deleteTask(taskId)
    }

    suspend fun deleteTaskRemote(taskId: Long): Boolean = withContext(Dispatchers.IO) {
        BackendApiService.deleteTask(taskId) is ApiResult.Success
    }

    suspend fun deleteContentLocally(contentId: Long) = withContext(Dispatchers.IO) {
        contentItemDao.deleteContentItem(contentId)
    }

    suspend fun deleteContentRemote(contentId: Long): Boolean = withContext(Dispatchers.IO) {
        val result = BackendApiService.deleteContentItem(contentId)
        result is ApiResult.Success
    }
}
