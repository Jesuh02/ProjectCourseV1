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

        val topics = BackendApiService.getTopicsByCourse(effectiveCourseId)
            .getOrNull().orEmpty().sortedBy { it.orderIndex }
        val allTasks = BackendApiService.getTasksByCourse(effectiveCourseId)
            .getOrNull().orEmpty().sortedBy { it.orderIndex }
        val allContent = BackendApiService.getContentItemsByCourse(effectiveCourseId)
            .getOrNull().orEmpty().sortedBy { it.orderIndex ?: 0 }
        val progress = if (userId > 0 && !isCreator) {
            (BackendApiService.getProgressByCourse(effectiveCourseId) as? ApiResult.Success)?.data
        } else null

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

    private suspend fun persistToRoom(
        courseId: Long,
        course: Course?,
        topics: List<Topic>,
        tasks: List<Task>,
        content: List<ContentItem>
    ) {
        try {
            if (course != null) courseDao.insertCourse(course)
            topicDao.deleteTopicsByCourse(courseId)
            if (topics.isNotEmpty()) topicDao.insertAll(topics)
            if (tasks.isNotEmpty()) taskDao.insertAll(tasks)
            if (content.isNotEmpty()) contentItemDao.insertAll(content)
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
