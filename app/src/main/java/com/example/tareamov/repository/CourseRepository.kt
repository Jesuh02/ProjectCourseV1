package com.example.tareamov.repository

import android.content.Context
import android.util.Log
import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for handling course-related data operations via BackendApiService.
 * All data is fetched from the backend Node.js API.
 */
class CourseRepository(private val context: Context) {

    init {
        BackendApiService.initialize(context)
    }

    // ═══════════════════════════════════════════════════════════
    // COURSE OPERATIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun getAllCourses(): List<Course> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getCourses()) {
            is ApiResult.Success -> result.data ?: emptyList()
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching courses: ${result.message}")
                emptyList()
            }
        }
    }

    suspend fun getCourseById(courseId: Long): Course? = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getCourseById(courseId)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching course $courseId: ${result.message}")
                null
            }
        }
    }

    suspend fun getCoursesByCreator(username: String): List<Course> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getCoursesByCreator(username)) {
            is ApiResult.Success -> result.data ?: emptyList()
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching courses by creator: ${result.message}")
                emptyList()
            }
        }
    }

    suspend fun getCoursesByCreatorId(userId: Long): List<Course> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getCoursesByCreatorId(userId)) {
            is ApiResult.Success -> result.data ?: emptyList()
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching courses by creator ID: ${result.message}")
                emptyList()
            }
        }
    }

    suspend fun searchCourses(query: String): List<Course> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.searchCourses(query)) {
            is ApiResult.Success -> result.data ?: emptyList()
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error searching courses: ${result.message}")
                emptyList()
            }
        }
    }

    suspend fun getFreeCourses(): List<Course> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getFreeCourses()) {
            is ApiResult.Success -> result.data ?: emptyList()
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching free courses: ${result.message}")
                emptyList()
            }
        }
    }

    suspend fun getPublishedCourses(): List<Course> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getCourses()) {
            is ApiResult.Success -> (result.data ?: emptyList()).filter { it.isPublished }
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching published courses: ${result.message}")
                emptyList()
            }
        }
    }

    suspend fun insertCourse(course: Course): Long = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.createCourse(course)) {
            is ApiResult.Success -> result.data?.id ?: -1L
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error creating course: ${result.message}")
                -1L
            }
        }
    }

    suspend fun updateCourse(course: Course) = withContext(Dispatchers.IO) {
        val updates = mapOf(
            "title" to course.title,
            "description" to course.description,
            "category" to course.category,
            "thumbnailUri" to course.thumbnailUri,
            "price" to course.price,
            "isPublished" to course.isPublished
        )
        val result = BackendApiService.updateCourse(course.id, updates)
        if (result is ApiResult.Error) {
            Log.e("CourseRepository", "Error updating course: ${result.message}")
        }
    }

    suspend fun deleteCourse(courseId: Long) = withContext(Dispatchers.IO) {
        val result = BackendApiService.deleteCourse(courseId)
        if (result is ApiResult.Error) {
            Log.e("CourseRepository", "Error deleting course: ${result.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // VIDEO OPERATIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun getAllVideos(): List<VideoData> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getVideos()) {
            is ApiResult.Success -> result.data ?: emptyList()
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching videos: ${result.message}")
                emptyList()
            }
        }
    }

    suspend fun getVideoById(videoId: Long): VideoData? = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getVideoById(videoId)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching video: ${result.message}")
                null
            }
        }
    }

    suspend fun getVideosByCreator(username: String): List<VideoData> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getVideosByCreator(username)) {
            is ApiResult.Success -> result.data ?: emptyList()
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching creator videos: ${result.message}")
                emptyList()
            }
        }
    }

    suspend fun getVideosByCourse(courseId: Long): List<VideoData> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.getVideosByCourse(courseId)) {
            is ApiResult.Success -> result.data ?: emptyList()
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error fetching course videos: ${result.message}")
                emptyList()
            }
        }
    }

    suspend fun insertVideo(video: VideoData): Long = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.createVideo(video)) {
            is ApiResult.Success -> result.data?.id ?: -1L
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error creating video: ${result.message}")
                -1L
            }
        }
    }

    suspend fun updateVideo(video: VideoData) = withContext(Dispatchers.IO) {
        val updates = mapOf(
            "title" to video.title,
            "description" to video.description,
            "videoUriString" to video.videoUriString,
            "thumbnailUri" to video.thumbnailUri
        )
        val result = BackendApiService.updateVideo(video.id, updates)
        if (result is ApiResult.Error) {
            Log.e("CourseRepository", "Error updating video: ${result.message}")
        }
    }

    suspend fun deleteVideo(videoId: Long) = withContext(Dispatchers.IO) {
        val result = BackendApiService.deleteVideo(videoId)
        if (result is ApiResult.Error) {
            Log.e("CourseRepository", "Error deleting video: ${result.message}")
        }
    }

    suspend fun searchVideos(query: String): List<VideoData> = withContext(Dispatchers.IO) {
        return@withContext when (val result = BackendApiService.searchVideos(query)) {
            is ApiResult.Success -> result.data ?: emptyList()
            is ApiResult.Error -> {
                Log.e("CourseRepository", "Error searching videos: ${result.message}")
                emptyList()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // UTILITY
    // ═══════════════════════════════════════════════════════════

    /**
     * Converts a VideoData object into a Course object for display purposes.
     */
    fun convertVideoDataToCoursePublic(videoData: VideoData): Course {
        return Course(
            id = videoData.courseId ?: videoData.id,
            title = videoData.title,
            description = videoData.description ?: "",
            creatorUserId = 0L, // Will be resolved separately if needed
            thumbnailUri = videoData.thumbnailUri,
            videoUri = videoData.videoUriString,
            localFilePath = videoData.localFilePath,
            price = videoData.price ?: 0.0,
            isPremium = videoData.isPaid,
            timestamp = videoData.timestamp
        )
    }

    /**
     * Saves a course to the backend (creates or updates).
     */
    suspend fun saveCourse(course: Course) = withContext(Dispatchers.IO) {
        if (course.id > 0) {
            val existing = BackendApiService.getCourseById(course.id)
            if (existing is ApiResult.Success && existing.data != null) {
                updateCourse(course)
            } else {
                insertCourse(course)
            }
        } else {
            insertCourse(course)
        }
    }

    /**
     * Deletes a course by its ID.
     */
    suspend fun deleteCourseById(courseId: Long) = withContext(Dispatchers.IO) {
        deleteCourse(courseId)
    }

    /**
     * Deletes a video by VideoData object.
     */
    suspend fun deleteVideo(videoData: VideoData) = withContext(Dispatchers.IO) {
        deleteVideo(videoData.id)
    }

    /**
     * Syncs VideoData entries to courses table via backend.
     * Since all data now comes from the backend, this is effectively a no-op.
     */
    suspend fun syncVideoDataToCoursesTable() = withContext(Dispatchers.IO) {
        // Data is managed via the backend API; no local sync needed.
        Log.d("CourseRepository", "syncVideoDataToCoursesTable called - backend manages data")
    }

    suspend fun getCourseCount(): Int = withContext(Dispatchers.IO) {
        getAllCourses().size
    }

    suspend fun getVideoCount(): Int = withContext(Dispatchers.IO) {
        getAllVideos().size
    }
}
