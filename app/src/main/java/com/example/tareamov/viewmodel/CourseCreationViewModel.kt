package com.example.tareamov.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.VideoData

class CourseCreationViewModel : ViewModel() {
    // Course data
    var courseId: Long = -1L
    var courseName: String = ""
    var courseCategory: String = ""
    var courseDescription: String = ""
    var isCourseSaved: Boolean = false

    // Topics data
    val topics = mutableListOf<TemporaryTopic>()

    // Current topic being edited
    var currentTopicIndex: Int = -1

    // Get current topic or create a new one
    fun getCurrentTopic(): TemporaryTopic {
        if (currentTopicIndex == -1 || currentTopicIndex >= topics.size) {
            val newTopic = TemporaryTopic()
            topics.add(newTopic)
            currentTopicIndex = topics.size - 1
            return newTopic
        }
        return topics[currentTopicIndex]
    }

    // Create a new topic and set it as current
    fun createNewTopic(): TemporaryTopic {
        val newTopic = TemporaryTopic()
        topics.add(newTopic)
        currentTopicIndex = topics.size - 1
        return newTopic
    }

    // Set a specific topic as current by index
    fun setCurrentTopicByIndex(index: Int) {
        if (index >= 0 && index < topics.size) {
            currentTopicIndex = index
        }
    }

    // Current task being edited
    var currentTaskIndex: Int = -1

    // Get current task from current topic or create a new one
    fun getCurrentTask(): TemporaryTask? {
        val currentTopic = getCurrentTopic()
        if (currentTaskIndex == -1 || currentTaskIndex >= currentTopic.tasks.size) {
            return null
        }
        return currentTopic.tasks[currentTaskIndex]
    }

    // Create a new task in the current topic and set it as current
    fun createNewTask(): TemporaryTask {
        val currentTopic = getCurrentTopic()
        val newTask = TemporaryTask()
        currentTopic.tasks.add(newTask)
        currentTaskIndex = currentTopic.tasks.size - 1
        return newTask
    }

    // Set a specific task as current by index
    fun setCurrentTaskByIndex(index: Int) {
        val currentTopic = getCurrentTopic()
        if (index >= 0 && index < currentTopic.tasks.size) {
            currentTaskIndex = index
        }
    }

    // Clear all temporary data
    fun clearAll() {
        courseId = -1L
        courseName = ""
        courseCategory = ""
        courseDescription = ""
        isCourseSaved = false
        topics.clear()
        currentTopicIndex = -1
        currentTaskIndex = -1
    }

    // Convert to VideoData entity
    fun toCourseEntity(): VideoData {
        return VideoData(
            id = courseId,
            username = "current_user",
            title = courseName,
            description = "$courseDescription\nCategoría: $courseCategory",
            videoUriString = "content://media/external/video/dummy_${System.currentTimeMillis()}",
            timestamp = System.currentTimeMillis(),
            localFilePath = null
        )
    }

    // Inner class for temporary topic data
    class TemporaryTopic {
        var id: Long = -1L
        var name: String = ""
        var description: String = ""
        var orderIndex: Int = 0
        val contentItems = mutableListOf<TemporaryContentItem>()
        val tasks = mutableListOf<TemporaryTask>()

        // Convert to Topic entity
        fun toTopicEntity(courseId: Long): Topic {
            return Topic(
                id = id,
                courseId = courseId,
                name = name,
                description = description,
                orderIndex = orderIndex
            )
        }
    }

    // Inner class for temporary task data
    class TemporaryTask {
        var id: Long = -1L
        var name: String = ""
        var description: String = ""
        var orderIndex: Int = 0
        val contentItems = mutableListOf<TemporaryContentItem>()

        // Convert to Task entity
        fun toTaskEntity(topicId: Long): Task {
            return Task(
                id = id,
                topicId = topicId,
                name = name,
                description = if (description.isBlank()) null else description,
                orderIndex = orderIndex
            )
        }
    }

    // Inner class for temporary content item data
    class TemporaryContentItem {
        var id: Long = -1L
        var name: String = ""
        var uriString: String = ""
        var contentType: String = ""
        var orderIndex: Int = 0

        // Convert to ContentItem entity
        fun toContentItemEntity(topicId: Long, taskId: Long? = null): ContentItem {
            return ContentItem(
                id = id,
                topicId = topicId,
                taskId = taskId,
                name = name,
                uriString = uriString,
                contentType = contentType,
                orderIndex = orderIndex
            )
        }
    }
}