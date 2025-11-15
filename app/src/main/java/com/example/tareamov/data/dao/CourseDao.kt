package com.example.tareamov.data.dao

import androidx.room.*
import com.example.tareamov.data.entity.Course
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY creationDate DESC")
    suspend fun getAllCourses(): List<Course>
    
    @Query("SELECT * FROM courses ORDER BY creationDate DESC")
    fun getAllCoursesSync(): List<Course>

    @Query("SELECT * FROM courses WHERE creatorUsername = :username ORDER BY creationDate DESC")
    suspend fun getCoursesByCreator(username: String): List<Course>

    @Query("SELECT * FROM courses WHERE id = :courseId")
    suspend fun getCourseById(courseId: Long): Course?

    @Query("SELECT * FROM courses WHERE title = :title LIMIT 1")
    suspend fun getCourseByTitle(title: String): Course?

    @Query("SELECT * FROM courses WHERE isPublished = 1 ORDER BY creationDate DESC")
    suspend fun getPublishedCourses(): List<Course>

    @Query("SELECT * FROM courses WHERE title LIKE :query OR description LIKE :query OR category LIKE :query")
    suspend fun searchCourses(query: String): List<Course>

    @Query("SELECT * FROM courses WHERE category = :category AND isPublished = 1")
    suspend fun getCoursesByCategory(category: String): List<Course>

    @Query("SELECT * FROM courses WHERE isPremium = 0 AND isPublished = 1")
    suspend fun getFreeCourses(): List<Course>

    @Query("SELECT * FROM courses WHERE isPremium = 1 AND isPublished = 1")
    suspend fun getPremiumCourses(): List<Course>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course): Long

    @Update
    suspend fun updateCourse(course: Course)

    @Delete
    suspend fun deleteCourse(course: Course)

    @Query("DELETE FROM courses WHERE id = :courseId")
    suspend fun deleteCourseById(courseId: Long)

    @Query("UPDATE courses SET enrollmentCount = enrollmentCount + 1 WHERE id = :courseId")
    suspend fun incrementEnrollmentCount(courseId: Long)

    @Query("UPDATE courses SET rating = :rating WHERE id = :courseId")
    suspend fun updateCourseRating(courseId: Long, rating: Float)

    // Flow-based queries for real-time updates
    @Query("SELECT * FROM courses ORDER BY creationDate DESC")
    fun getAllCoursesFlow(): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE creatorUsername = :username ORDER BY creationDate DESC")
    fun getCoursesByCreatorFlow(username: String): Flow<List<Course>>

    // Add this method to get the maximum course ID for consecutive ID generation
    @Query("SELECT MAX(id) FROM courses")
    suspend fun getMaxCourseId(): Long?
}
