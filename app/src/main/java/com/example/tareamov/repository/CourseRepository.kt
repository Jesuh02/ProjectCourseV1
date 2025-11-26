package com.example.tareamov.repository

import android.content.Context
import android.util.Log
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.dao.CourseDao
import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.util.ThumbnailManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository for handling course-related data operations
 */
class CourseRepository(private val context: Context) {

    private val database = AppDatabase.getDatabase(context)
    private val videoDao = database.videoDao()
    private val thumbnailManager = ThumbnailManager(context)
    
    // Try to get courseDao, fall back to VideoData if not available
    private val courseDao: CourseDao? by lazy {
        try {
            database.courseDao()
        } catch (e: Exception) {
            null
        }
    }

    // Course operations using new Course entity
    suspend fun getAllCourses(): List<Course> = withContext(Dispatchers.IO) {
        return@withContext if (courseDao != null) {
            courseDao!!.getAllCourses()
        } else {
            // Fallback to VideoData conversion
            val videos = videoDao.getAllVideos()
            videos.map { convertVideoDataToCourse(it) }
        }
    }

    suspend fun getCourseById(courseId: Long): Course? = withContext(Dispatchers.IO) {
        return@withContext if (courseDao != null) {
            courseDao!!.getCourseById(courseId)
        } else {
            val video = videoDao.getVideoById(courseId)
            video?.let { convertVideoDataToCourse(it) }
        }
    }

    suspend fun getCoursesByCreator(userId: Long): List<Course> = withContext(Dispatchers.IO) {
        return@withContext if (courseDao != null) {
            courseDao!!.getCoursesByCreator(userId)
        } else {
            // Fallback: get username from userId, then fetch videos
            val username = com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(userId) ?: ""
            val videos = videoDao.getVideosByUsername(username)
            videos.map { convertVideoDataToCourse(it) }
        }
    }

    suspend fun getPublishedCourses(): List<Course> = withContext(Dispatchers.IO) {
        return@withContext if (courseDao != null) {
            courseDao!!.getPublishedCourses()
        } else {
            val videos = videoDao.getAllVideos()
            videos.map { convertVideoDataToCourse(it) }
        }
    }

    suspend fun searchCourses(query: String): List<Course> = withContext(Dispatchers.IO) {
        return@withContext if (courseDao != null) {
            courseDao!!.searchCourses("%$query%")
        } else {
            val videos = videoDao.getAllVideos()
            val filtered = videos.filter { 
                it.title.contains(query, ignoreCase = true) || 
                it.description.contains(query, ignoreCase = true) 
            }
            filtered.map { convertVideoDataToCourse(it) }
        }
    }

    suspend fun getCoursesByCategory(category: String): List<Course> = withContext(Dispatchers.IO) {
        return@withContext if (courseDao != null) {
            courseDao!!.getCoursesByCategory(category)
        } else {
            emptyList() // VideoData doesn't have category
        }
    }

    suspend fun getFreeCourses(): List<Course> = withContext(Dispatchers.IO) {
        return@withContext if (courseDao != null) {
            courseDao!!.getFreeCourses()
        } else {
            val videos = videoDao.getAllVideos()
            val freeVideos = videos.filter { !it.isPaid }
            freeVideos.map { convertVideoDataToCourse(it) }
        }
    }

    suspend fun getPremiumCourses(): List<Course> = withContext(Dispatchers.IO) {
        return@withContext if (courseDao != null) {
            courseDao!!.getPremiumCourses()
        } else {
            val videos = videoDao.getAllVideos()
            val premiumVideos = videos.filter { it.isPaid }
            premiumVideos.map { convertVideoDataToCourse(it) }
        }
    }

    suspend fun saveCourse(course: Course): Long = withContext(Dispatchers.IO) {
        return@withContext if (courseDao != null) {
            val courseWithDate = course.copy(
                creationDate = getCurrentTimestamp(),
                lastModifiedDate = getCurrentTimestamp()
            )
            courseDao!!.insertCourse(courseWithDate)
        } else {
            // IMPORTANT: Do NOT create a VideoData entry when saving a Course.
            // Creating courses must not write to the videos table. Log and return -1.
            Log.w("CourseRepository", "CourseDao unavailable: refusing to save Course as VideoData to enforce separation (course != video)")
            -1L
        }
    }

    suspend fun updateCourse(course: Course) = withContext(Dispatchers.IO) {
        if (courseDao != null) {
            val updatedCourse = course.copy(lastModifiedDate = getCurrentTimestamp())
            courseDao!!.updateCourse(updatedCourse)
        } else {
            Log.w("CourseRepository", "CourseDao unavailable: cannot update Course; no fallback to VideoData to enforce separation")
        }
    }

    suspend fun deleteCourse(course: Course) = withContext(Dispatchers.IO) {
        if (courseDao != null) {
            courseDao!!.deleteCourse(course)
        } else {
            Log.w("CourseRepository", "CourseDao unavailable: cannot delete Course; no fallback to VideoData")
        }
    }

    suspend fun deleteCourseById(courseId: Long) = withContext(Dispatchers.IO) {
        if (courseDao != null) {
            courseDao!!.deleteCourseById(courseId)
        } else {
            Log.w("CourseRepository", "CourseDao unavailable: cannot delete Course by id; no fallback to VideoData")
        }
    }

    suspend fun incrementEnrollmentCount(courseId: Long) = withContext(Dispatchers.IO) {
        if (courseDao != null) {
            courseDao!!.incrementEnrollmentCount(courseId)
        }
        // else: not available in VideoData
    }

    suspend fun updateCourseRating(courseId: Long, rating: Float) = withContext(Dispatchers.IO) {
        if (courseDao != null) {
            courseDao!!.updateCourseRating(courseId, rating)
        }
        // else: not available in VideoData
    }

    // Flow-based operations for real-time updates
    fun getAllCoursesFlow(): Flow<List<Course>> = 
        courseDao?.getAllCoursesFlow() ?: kotlinx.coroutines.flow.flowOf(emptyList())

    fun getCoursesByCreatorFlow(userId: Long): Flow<List<Course>> = 
        courseDao?.getCoursesByCreatorFlow(userId) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    // Method to observe course changes and convert to VideoData for UI compatibility
    fun observeCoursesAsVideoData(): Flow<List<VideoData>> = 
        kotlinx.coroutines.flow.flow {
            if (courseDao != null) {
                courseDao!!.getAllCoursesFlow().collect { courses ->
                    val videoDataList = courses.map { course ->
                        // Fetch username from user_id
                        val username = com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId) ?: "unknown"
                        VideoData(
                            id = course.id,
                            username = username,
                            description = course.description,
                            title = course.title,
                            videoUriString = course.videoUri,
                            localFilePath = course.localFilePath,
                            timestamp = course.timestamp,
                            isPaid = course.isPremium,
                            thumbnailUri = course.thumbnailUri,
                            price = course.price
                        )
                    }
                    emit(videoDataList)
                }
            } else {
                // Fallback to VideoData flow
                emit(videoDao.getAllVideos())
            }
        }

    // Legacy VideoData operations (for backward compatibility)
    suspend fun getAllVideos(): List<VideoData> = withContext(Dispatchers.IO) {
        return@withContext videoDao.getAllVideos()
    }

    suspend fun getVideoById(courseId: Long): VideoData? = withContext(Dispatchers.IO) {
        return@withContext videoDao.getVideoById(courseId)
    }

    suspend fun getVideosByCreator(username: String): List<VideoData> = withContext(Dispatchers.IO) {
        return@withContext videoDao.getVideosByUsername(username)
    }

    suspend fun saveVideo(course: VideoData): VideoData = withContext(Dispatchers.IO) {
        val id = videoDao.insertVideo(course)
        return@withContext course.copy(id = id)
    }

    suspend fun updateVideo(course: VideoData) = withContext(Dispatchers.IO) {
        videoDao.updateVideo(course)
    }

    suspend fun deleteVideo(course: VideoData) = withContext(Dispatchers.IO) {
        videoDao.deleteVideo(course.id)
    }

    // Migration helpers - Convert VideoData to Course
    suspend fun migrateVideoDataToCourses() = withContext(Dispatchers.IO) {
        if (courseDao != null) {
            val allVideos = videoDao.getAllVideos()
            allVideos.forEach { video ->
                val course = convertVideoDataToCourse(video)
                courseDao!!.insertCourse(course)
            }
        }
    }

    // Method to populate Course table with all VideoData
    suspend fun populateCoursesFromVideoData() = withContext(Dispatchers.IO) {
        if (courseDao != null) {
            // Check if courses table is empty
            val existingCourses = courseDao!!.getAllCourses()
            if (existingCourses.isEmpty()) {
                val allVideos = videoDao.getAllVideos()
                Log.d("CourseRepository", "Migrating ${allVideos.size} videos to courses table")
                allVideos.forEach { video ->
                    try {
                        // Ensure thumbnail exists for this video
                        val thumbnailPath = ensureThumbnailForVideo(video)
                        
                        val course = convertVideoDataToCourseWithCategory(video).copy(
                            thumbnailUri = thumbnailPath
                        )
                        courseDao!!.insertCourse(course)
                        Log.d("CourseRepository", "Migrated course: ${course.title} - Category: ${course.category} - Thumbnail: $thumbnailPath")
                    } catch (e: Exception) {
                        Log.e("CourseRepository", "Error migrating course: ${video.title}", e)
                    }
                }
                Log.d("CourseRepository", "Migration completed")
            } else {
                // If courses exist, ensure they all have thumbnails
                Log.d("CourseRepository", "Courses table not empty, ensuring thumbnails exist")
                existingCourses.forEach { course ->
                    try {
                        if (course.thumbnailUri.isNullOrEmpty()) {
                            val video = videoDao.getVideoById(course.id)
                            if (video != null) {
                                val thumbnailPath = ensureThumbnailForVideo(video)
                                if (thumbnailPath != null) {
                                    val updatedCourse = course.copy(thumbnailUri = thumbnailPath)
                                    courseDao!!.updateCourse(updatedCourse)
                                    Log.d("CourseRepository", "Added thumbnail to existing course: ${course.title}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CourseRepository", "Error adding thumbnail to course: ${course.title}", e)
                    }
                }
            }
        }
    }

    // Enhanced conversion that includes automatic categorization
    private suspend fun convertVideoDataToCourseWithCategory(video: VideoData): Course {
        // Get user ID from username OR from courseId (new approach)
        val userId = if (video.courseId != null && video.courseId!! > 0) {
            // Nuevo: obtener userId desde courseId
            val course = com.example.tareamov.service.SupabaseClient.fetchCourseById(video.courseId!!)
            course?.creatorUserId ?: 0L
        } else if (!video.username.isNullOrEmpty()) {
            // Fallback: obtener userId desde username (compatibilidad)
            com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(video.username) ?: 0L
        } else {
            0L
        }
        
        return Course(
            id = video.id,
            title = video.title,
            description = video.description,
            creatorUserId = userId,
            thumbnailUri = video.thumbnailUri,
            videoUri = video.videoUriString,
            localFilePath = video.localFilePath,
            duration = null,
            category = determineCourseCategory(video.title, video.description), // Auto-categorize
            price = video.price ?: 0.0,
            isPremium = video.isPaid,
            isPublished = true,
            creationDate = formatTimestamp(video.timestamp),
            lastModifiedDate = getCurrentTimestamp(),
            enrollmentCount = 0,
            rating = 0.0f,
            tags = null,
            timestamp = video.timestamp
        )
    }

    // Helper function to determine course category/sector based on title and description
    private fun determineCourseCategory(title: String, description: String): String {
        val content = "${title.lowercase()} ${description.lowercase()}"
        
        return when {
            content.contains("programacion") || content.contains("codigo") || content.contains("desarrollo") || 
            content.contains("software") || content.contains("app") || content.contains("web") -> "Tecnología"
            
            content.contains("marketing") || content.contains("ventas") || content.contains("negocio") || 
            content.contains("emprendimiento") -> "Negocios"
            
            content.contains("diseño") || content.contains("arte") || content.contains("grafico") || 
            content.contains("creativo") -> "Diseño"
            
            content.contains("idioma") || content.contains("ingles") || content.contains("frances") || 
            content.contains("lenguaje") -> "Idiomas"
            
            content.contains("musica") || content.contains("instrumento") || content.contains("canto") || 
            content.contains("audio") -> "Música"
            
            content.contains("salud") || content.contains("fitness") || content.contains("ejercicio") || 
            content.contains("medicina") -> "Salud y Bienestar"
            
            content.contains("cocina") || content.contains("receta") || content.contains("comida") || 
            content.contains("gastronomia") -> "Cocina"
            
            content.contains("fotografia") || content.contains("video") || content.contains("camara") || 
            content.contains("edicion") -> "Fotografía y Video"
            
            else -> "General"
        }
    }

    private suspend fun convertVideoDataToCourse(video: VideoData): Course {
        // Get user ID from username OR from courseId (new approach)
        val userId = if (video.courseId != null && video.courseId!! > 0) {
            // Nuevo: obtener userId desde courseId
            val course = com.example.tareamov.service.SupabaseClient.fetchCourseById(video.courseId!!)
            course?.creatorUserId ?: 0L
        } else if (!video.username.isNullOrEmpty()) {
            // Fallback: obtener userId desde username (compatibilidad)
            com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(video.username) ?: 0L
        } else {
            0L
        }
        
        return Course(
            id = video.id,
            title = video.title,
            description = video.description,
            creatorUserId = userId,
            thumbnailUri = video.thumbnailUri,
            videoUri = video.videoUriString, // VideoData uses 'videoUriString' not 'videoPath'
            localFilePath = video.localFilePath,
            duration = null, // VideoData doesn't have duration field
            category = null, // VideoData doesn't have category
            price = video.price ?: 0.0, // Handle nullable price
            isPremium = video.isPaid, // VideoData uses 'isPaid' not 'isPremium'
            isPublished = true,
            creationDate = formatTimestamp(video.timestamp), // Convert timestamp to string
            lastModifiedDate = getCurrentTimestamp(),
            enrollmentCount = 0,
            rating = 0.0f,
            tags = null,
            timestamp = video.timestamp // Direct mapping from VideoData.timestamp
        )
    }
    
    // Public method for external conversion needs
    suspend fun convertVideoDataToCoursePublic(video: VideoData): Course {
        return convertVideoDataToCourse(video)
    }
    
    // Method to automatically sync new VideoData to Course table
    suspend fun syncVideoDataToCoursesTable() = withContext(Dispatchers.IO) {
        if (courseDao != null) {
            try {
                val allVideos = videoDao.getAllVideos()
                val existingCourses = courseDao!!.getAllCourses()
                val existingCourseIds = existingCourses.map { it.id }.toSet()
                
                // Find VideoData that doesn't exist in Course table
                val newVideos = allVideos.filter { it.id !in existingCourseIds }
                
                Log.d("CourseRepository", "Found ${newVideos.size} new videos to sync to Course table")
                
                newVideos.forEach { video ->
                    try {
                        // Ensure thumbnail exists for this video
                        val thumbnailPath = ensureThumbnailForVideo(video)
                        
                        val course = convertVideoDataToCourseWithCategory(video).copy(
                            thumbnailUri = thumbnailPath
                        )
                        courseDao!!.insertCourse(course)
                        Log.d("CourseRepository", "Synced new video to course: ${course.title} with thumbnail: $thumbnailPath")
                    } catch (e: Exception) {
                        Log.e("CourseRepository", "Error syncing video: ${video.title}", e)
                    }
                }
                
                // Update existing courses that might have changed
                allVideos.filter { it.id in existingCourseIds }.forEach { video ->
                    try {
                        val existingCourse = courseDao!!.getCourseById(video.id)
                        if (existingCourse != null) {
                            // Ensure thumbnail exists if not already set
                            val thumbnailPath = if (existingCourse.thumbnailUri.isNullOrEmpty()) {
                                ensureThumbnailForVideo(video)
                            } else {
                                existingCourse.thumbnailUri
                            }
                            
                            val updatedCourse = convertVideoDataToCourseWithCategory(video).copy(
                                id = existingCourse.id,
                                creationDate = existingCourse.creationDate, // Keep original creation date
                                enrollmentCount = existingCourse.enrollmentCount, // Keep enrollment count
                                rating = existingCourse.rating, // Keep rating
                                thumbnailUri = thumbnailPath
                            )
                            courseDao!!.updateCourse(updatedCourse)
                            Log.d("CourseRepository", "Updated existing course: ${updatedCourse.title} with thumbnail: $thumbnailPath")
                        }
                    } catch (e: Exception) {
                        Log.e("CourseRepository", "Error updating course for video: ${video.title}", e)
                    }
                }
                
                Log.d("CourseRepository", "VideoData to Course sync completed")
            } catch (e: Exception) {
                Log.e("CourseRepository", "Error during VideoData to Course sync", e)
            }
        }
    }
    
    /**
     * Ensure thumbnail exists for a video, generate if necessary
     */
    private suspend fun ensureThumbnailForVideo(video: VideoData): String? {
        return try {
            // Check if video already has a thumbnail
            if (!video.thumbnailUri.isNullOrEmpty()) {
                Log.d("CourseRepository", "Video ${video.id} already has thumbnail: ${video.thumbnailUri}")
                return video.thumbnailUri
            }
            
            // Try to generate thumbnail
            val videoUri = video.getBestVideoUri()?.toString() ?: video.videoUriString
            if (!videoUri.isNullOrEmpty()) {
                val thumbnailPath = thumbnailManager.ensureThumbnailExists(videoUri, video.id)
                if (thumbnailPath != null) {
                    // Update VideoData with the new thumbnail
                    val updatedVideo = video.copy(thumbnailUri = "file://$thumbnailPath")
                    videoDao.updateVideo(updatedVideo)
                    Log.d("CourseRepository", "Generated and updated thumbnail for video ${video.id}: $thumbnailPath")
                    return "file://$thumbnailPath"
                }
            }
            
            Log.w("CourseRepository", "Could not generate thumbnail for video ${video.id}")
            return null
        } catch (e: Exception) {
            Log.e("CourseRepository", "Error ensuring thumbnail for video ${video.id}", e)
            return null
        }
    }

    private suspend fun convertCourseToVideoData(course: Course): VideoData {
        // Fetch username from user_id
        val username = com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId) ?: "unknown"
        
        return VideoData(
            id = course.id,
            username = username,
            description = course.description,
            title = course.title,
            videoUriString = course.videoUri,
            localFilePath = course.localFilePath,
            timestamp = course.timestamp, // Use Course.timestamp instead of current time
            isPaid = course.isPremium,
            thumbnailUri = course.thumbnailUri,
            price = if (course.price > 0.0) course.price else null
        )
    }

    // Public helper methods for external use
    fun getCurrentTimestamp(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date())
    }

    fun formatTimestamp(timestamp: Long): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date(timestamp))
    }

    // Helper methods for filtering and sorting
    suspend fun getCoursesSortedByDate(): List<Course> = withContext(Dispatchers.IO) {
        val courses = getAllCourses()
        return@withContext courses.sortedByDescending { it.creationDate }
    }

    suspend fun getCoursesSortedByRating(): List<Course> = withContext(Dispatchers.IO) {
        val courses = getAllCourses()
        return@withContext courses.sortedByDescending { it.rating }
    }

    suspend fun getCoursesSortedByEnrollment(): List<Course> = withContext(Dispatchers.IO) {
        val courses = getAllCourses()
        return@withContext courses.sortedByDescending { it.enrollmentCount }
    }

    suspend fun getPopularCourses(limit: Int = 10): List<Course> = withContext(Dispatchers.IO) {
        val courses = getAllCourses()
        return@withContext courses
            .sortedByDescending { it.enrollmentCount }
            .take(limit)
    }

    suspend fun getTopRatedCourses(limit: Int = 10): List<Course> = withContext(Dispatchers.IO) {
        val courses = getAllCourses()
        return@withContext courses
            .filter { it.rating > 0 }
            .sortedByDescending { it.rating }
            .take(limit)
    }
}