package com.example.tareamov.data.sync

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.data.dao.PersonaDao
import com.example.tareamov.data.dao.TopicDao
import com.example.tareamov.data.dao.ContentItemDao
import com.example.tareamov.data.dao.TaskDao
import com.example.tareamov.data.dao.SubscriptionDao
import com.example.tareamov.data.dao.TaskSubmissionDao
import com.example.tareamov.data.dao.VideoDao
import com.example.tareamov.data.dao.CourseDao
import com.example.tareamov.data.dao.RolDao
import com.example.tareamov.data.dao.RecursoDao
import com.example.tareamov.data.dao.RolRecursoDao
import com.example.tareamov.data.dao.VideoCommentDao
import com.example.tareamov.data.dao.LikeDao
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.data.entity.Rol
import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.Recurso
import com.example.tareamov.data.entity.RolRecurso
import okhttp3.MediaType.Companion.toMediaType
import com.example.tareamov.data.entity.VideoComment
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Notification
import com.example.tareamov.data.entity.Like
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.example.tareamov.data.repository.SupabaseRepository
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class SyncRepository(
    private val usuarioDao: UsuarioDao,
    private val personaDao: PersonaDao,
    private val topicDao: TopicDao,
    private val contentItemDao: ContentItemDao,
    private val taskDao: TaskDao,
    private val subscriptionDao: SubscriptionDao,
    private val taskSubmissionDao: TaskSubmissionDao,
    private val videoDao: VideoDao,
    private val courseDao: CourseDao,
    private val rolDao: RolDao,
    private val recursoDao: RecursoDao,
    private val rolRecursoDao: RolRecursoDao,
    private val chatMessageDao: com.example.tareamov.data.dao.ChatMessageDao,
    private val fileContextDao: com.example.tareamov.data.dao.FileContextDao,
    private val progresoEstudianteDao: com.example.tareamov.data.dao.ProgresoEstudianteDao,
    private val likeDao: LikeDao? = null, // Polymorphic likes DAO (replaces videoLikeDao)
    private val videoCommentDao: VideoCommentDao? = null
) {
    // SharedPreferences-based cache to store last remote 'updated_at' per table
    private var prefs: android.content.SharedPreferences? = null

    // Optional: initialize with Context to enable caching
    fun initWithContext(context: android.content.Context) {
        try {
            prefs = context.getSharedPreferences("supabase_sync_cache", android.content.Context.MODE_PRIVATE)
        } catch (e: Exception) {
            Log.w("SyncRepository", "Could not initialize prefs for caching", e)
        }
    }

    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val supabaseRepo = SupabaseRepository()
    private val supabaseClient = com.example.tareamov.service.SupabaseClient

    // Execute raw queries via backend (deprecated — prefer specific API calls)
    @Deprecated("Use specific BackendApiService methods instead of raw SQL")
    suspend fun executeRawQuery(sql: String): List<Map<String, Any?>> {
        return supabaseRepo.executeRawQuery(sql)
    }

    // Fetch usuario plus role info from Supabase for a given username
    suspend fun fetchUsuarioWithRoleFromSupabase(username: String): com.example.tareamov.data.dao.UsuarioWithRole? {
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured according to isConfigured(); attempting fetchUsuarioWithRoleByUsername anyway for username=$username")
            }
            val (u, r) = try {
                withContext(Dispatchers.IO) { supabaseClient.fetchUsuarioWithRoleByUsername(username) }
            } catch (e: Exception) {
                Log.w("SyncRepository", "fetchUsuarioWithRoleFromSupabase remote call failed for $username: ${e.message}")
                Pair(null, null)
            }
            if (u == null) return null
            val rolNombre = r?.nombre ?: ""
            val rolNivel = r?.nivel ?: 0.0f
            com.example.tareamov.data.dao.UsuarioWithRole(
                id = u.id,
                username = u.usuario,
                contrasena = u.contrasena,
                persona_id = u.persona_id,
                rol_id = u.rol_id,
                email = u.email,
                avatar = u.avatar,
                isActive = u.isActive,
                emailVerified = u.emailVerified,
                lastLogin = u.lastLogin,
                createdAt = u.createdAt,
                rolNombre = rolNombre,
                rolNivel = rolNivel
            )
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchUsuarioWithRoleFromSupabase failed for $username", e)
            null
        }
    }

    suspend fun isUserAdmin(userId: Long): Boolean {
        // User requirement: Check ONLY usuarios_roles table for role 3 (Admin)
        // Using SupabaseClient standard REST to avoid 'execute_sql' RPC issues in production.
        return try {
            val roleIds = supabaseClient.fetchUserRoleIds(userId)
            roleIds.contains(3)
        } catch (e: Exception) {
            android.util.Log.e("SyncRepository", "Error checking admin role for user $userId", e)
            false
        }
    }

    /**
     * Checks if a user has a specific role, either in the primary 'usuarios.rol_id' column
     * or in the 'usuarios_roles' many-to-many table.
     */
    suspend fun hasUserRole(userId: Long, roleId: Long): Boolean {
        try {
            if (userId <= 0) return false

            // 1. Check usuarios_roles using standard REST
            val roleIds = supabaseClient.fetchUserRoleIds(userId)
            if (roleIds.contains(roleId.toInt())) return true

            // 2. Check usuarios table (Primary role) using fetchUsuarioById
            val user = supabaseClient.fetchUsuarioById(userId)
            return user?.rol_id == roleId
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error checking user role $roleId for user $userId", e)
            return false
        }
    }

    // Wrapper to fetch role by id from Supabase
    suspend fun fetchRolByIdFromSupabase(id: Long): com.example.tareamov.data.entity.Rol? {
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured according to isConfigured(); attempting fetchRolById anyway for id=$id")
            }
            try {
                withContext(Dispatchers.IO) { supabaseClient.fetchRolById(id) }
            } catch (e: Exception) {
                 Log.w("SyncRepository", "fetchRolByIdFromSupabase failed for id=$id: ${e.message}")
                null
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchRolByIdFromSupabase failed for id=$id", e)
            null
        }
    }

    // Utility: check if a username exists in Supabase (case-insensitive)
    suspend fun isUsuarioExistsInSupabase(username: String): Boolean {
        try {
            if (!supabaseClient.isConfigured()) {
                Log.d("SyncRepository", "SupabaseClient.isConfigured() returned false for username=$username; will still attempt remote check")
            }

            fun maskSecret(s: String?): String {
                if (s.isNullOrEmpty()) return "<empty>"
                if (s.length <= 2) return "*".repeat(s.length)
                return s.first() + "*".repeat(s.length - 2) + s.last()
            }

            Log.d("SyncRepository", "isUsuarioExistsInSupabase: checking username=$username")
            val u = try {
                withContext(Dispatchers.IO) { supabaseClient.fetchUsuarioByUsername(username) }
            } catch (e: Exception) {
                Log.w("SyncRepository", "fetchUsuarioByUsername failed: ${e.message}")
                null
            }

            if (u != null) {
                Log.d("SyncRepository", "isUsuarioExistsInSupabase: found remote user id=${u.id} stored_password_mask=${maskSecret(u.contrasena)}")
                return true
            } else {
                Log.d("SyncRepository", "isUsuarioExistsInSupabase: user not found on Supabase for username=$username")
                return false
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "isUsuarioExistsInSupabase failed", e)
            return false
        }
    }

    suspend fun saveReinforcementHistory(userId: Long, courseId: Long, topicId: Long = -1L, taskId: Long = -1L, questions: List<Any>) {
        try {
            if (userId > 0 && courseId > 0 && questions.isNotEmpty()) {
                val questionMaps = questions.map { q ->
                    if (q is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        q as Map<String, Any?>
                    } else {
                        mapOf("question" to q.toString())
                    }
                }
                val result = BackendApiService.saveReinforcementSession(
                    userId, courseId, questionMaps,
                    if (topicId > 0) topicId else null,
                    if (taskId > 0) taskId else null
                )
                if (result.isSuccess) {
                    Log.d("SyncRepository", "Reinforcement history saved via backend for user $userId course $courseId")
                } else {
                    Log.w("SyncRepository", "Failed to save reinforcement history via backend: ${result.errorMessage()}")
                }
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Exception saving reinforcement history", e)
        }
    }

    /**
     * Upsert a course via the backend API.
     * If the course has an ID, it updates; otherwise it creates.
     */
    fun upsertCourseToSupabase(course: Course) {
        syncScope.launch {
            try {
                val result = if (course.id != null && course.id > 0) {
                    val updates = buildCoursePayload(course)
                    BackendApiService.updateCourse(course.id, updates)
                } else {
                    BackendApiService.createCourse(course)
                }

                if (result.isSuccess) {
                    Log.i("SyncRepository", "Course '${course.title}' synced via backend.")
                    if (course.creatorUserId > 0) {
                        ensureCreatorRole(course.creatorUserId)
                    }
                } else {
                    Log.e("SyncRepository", "Failed to sync course '${course.title}': ${result.errorMessage()}")
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception during upsertCourseToSupabase", e)
            }
        }
    }

    private fun buildCoursePayload(course: Course): Map<String, Any?> {
        return mapOf(
            "title" to course.title,
            "description" to course.description,
            "imageUrl" to course.thumbnailUri,
            "isPremium" to course.isPremium,
            "price" to course.price,
            "creatorUserId" to course.creatorUserId
        )
    }

    /**
     * Ensures that a user who created a course has the 'Creator' role (ID 2).
     * This updates both the 'usuarios_roles' table and the 'usuarios' table (if applicable).
     */
    suspend fun ensureCreatorRole(userId: Long) {
        try {
            if (userId <= 0) return
            val result = BackendApiService.ensureCreatorRole(userId)
            if (result.isSuccess) {
                Log.i("SyncRepository", "Creator role ensured for user $userId via backend")
            } else {
                Log.w("SyncRepository", "Failed to ensure creator role for user $userId: ${result.errorMessage()}")
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error ensuring creator role for user $userId", e)
        }
    }

    /**
     * Scans all courses and ensures their creators have the Creator role.
     * Can be called periodically or on app start to fix existing data.
     */
    suspend fun syncAllCreatorRoles() {
        withContext(Dispatchers.IO) {
            try {
                Log.i("SyncRepository", "Starting syncAllCreatorRoles via backend...")
                val result = BackendApiService.syncAllCreatorRoles()
                if (result.isSuccess) {
                    Log.i("SyncRepository", "syncAllCreatorRoles completed via backend")
                } else {
                    Log.w("SyncRepository", "syncAllCreatorRoles failed: ${result.errorMessage()}")
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Error in syncAllCreatorRoles", e)
            }
        }
    }

    // Public helper: delete a course remotely by id (fire-and-forget). Logs result.
    fun deleteCourseRemoteById(courseId: Long) {
        syncScope.launch {
            try {
                val result = BackendApiService.deleteCourse(courseId)
                if (result.isSuccess) {
                    Log.i("SyncRepository", "Course id=$courseId deleted via backend")
                } else {
                    Log.w("SyncRepository", "Failed to delete course id=$courseId: ${result.errorMessage()}")
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception during deleteCourseRemoteById for id=$courseId", e)
            }
        }
    }

    // Public helper: check a table for remote changes (by updated_at) and refresh local table if changed.
    suspend fun refreshTableIfRemoteChanged(table: String, field: String = "updated_at") {
        try {
            if (!supabaseClient.isConfigured()) return
            val remoteTs = supabaseClient.fetchTableMaxUpdatedAt(table, field)
            val cached = prefs?.getString("${table}_last_ts", null)
            if (remoteTs == null) {
                Log.d("SyncRepository", "No remote timestamp for $table; skipping refresh")
                return
            }
            if (remoteTs == cached) {
                Log.d("SyncRepository", "No changes detected for $table (ts=$remoteTs)")
                return
            }
            Log.i("SyncRepository", "Change detected for $table (remote=$remoteTs cached=$cached). Refreshing local data.")
            // Perform targeted fetch & insert for known tables
            when (table) {
                "videos" -> {
                    val list = supabaseClient.fetchVideos()
                    list.forEach { v -> try { videoDao.insertVideo(v) } catch (e: Exception) { Log.w("SyncRepository","Failed to insert video ${v.id}", e) } }
                }
                "usuarios" -> {
                    val list = supabaseClient.fetchUsuarios()
                    list.forEach { u -> try { usuarioDao.insertUsuario(u) } catch (e: Exception) { Log.w("SyncRepository","Failed to insert usuario ${u.id}", e) } }
                }
                "personas" -> {
                    val list = supabaseClient.fetchPersonas()
                    list.forEach { p -> try { personaDao.insertPersona(p) } catch (e: Exception) { Log.w("SyncRepository","Failed to insert persona ${p.id}", e) } }
                }
                else -> {
                    // Generic fetch using SupabaseClient methods if available
                    when (table) {
                        "topics" -> supabaseClient.fetchTopics().forEach { t -> try { topicDao.insertTopic(t) } catch (e: Exception) { Log.w("SyncRepository","Failed to insert topic ${t.id}", e) } }
                        "content_items" -> supabaseClient.fetchContentItems().forEach { ci -> try { contentItemDao.insertContentItem(ci) } catch (e: Exception) { Log.w("SyncRepository","Failed to insert content item ${ci.id}", e) } }
                        "tasks" -> supabaseClient.fetchTasks().forEach { ta -> try { taskDao.insertTask(ta) } catch (e: Exception) { Log.w("SyncRepository","Failed to insert task ${ta.id}", e) } }
                        else -> Log.w("SyncRepository", "No targeted refresh implemented for table $table")
                    }
                }
            }

            // update cache
            prefs?.edit()?.putString("${table}_last_ts", remoteTs)?.apply()
            Log.i("SyncRepository", "Cache updated for $table -> $remoteTs")
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error in refreshTableIfRemoteChanged for $table", e)
        }
    }

    // Convenience suspend function to refresh videos if changed
    suspend fun refreshVideosIfChanged() {
        refreshTableIfRemoteChanged("videos")
    }

    // Get the maximum video ID from Supabase
    suspend fun getMaxVideoIdFromSupabase(): Long {
        return try {
            if (!supabaseClient.isConfigured()) return 0L
            withContext(Dispatchers.IO) { supabaseClient.getMaxVideoIdFromSupabase() }
        } catch (e: Exception) {
            Log.e("SyncRepository", "getMaxVideoIdFromSupabase failed", e)
            0L
        }
    }

    // Check if a video/course title exists in Supabase (case-insensitive)
    suspend fun isVideoTitleExistsInSupabase(title: String): Boolean {
        try {
            if (!supabaseClient.isConfigured()) return false
            val list = fetchVideosFromSupabase()
            return list.any { it.title?.equals(title, ignoreCase = true) == true }
        } catch (e: Exception) {
            Log.w("SyncRepository", "isVideoTitleExistsInSupabase failed", e)
            return false
        }
    }

    // Check if a title exists in either 'videos' or 'courses' tables in Supabase (case-insensitive)
    suspend fun isTitleExistsInSupabase(title: String): Boolean {
        try {
            if (!supabaseClient.isConfigured()) return false

            // Check videos
            val videos = fetchVideosFromSupabase()
            if (videos.any { it.title?.equals(title, ignoreCase = true) == true }) return true

            // Check courses (use SupabaseClient.fetchCourses which returns Course objects)
            val courses = withContext(Dispatchers.IO) { supabaseClient.fetchCourses() }
            if (courses.any { it.title?.equals(title, ignoreCase = true) == true }) return true

            return false
        } catch (e: Exception) {
            Log.w("SyncRepository", "isTitleExistsInSupabase failed", e)
            return false
        }
    }

    // Fetch videos directly from Supabase and return ordered by newest first.
    suspend fun fetchVideosFromSupabase(): List<com.example.tareamov.data.entity.VideoData> {
        // Removed try-catch to allow UI to detect connection errors
        if (!supabaseClient.isConfigured()) return emptyList()
        // Try the typed fetch first
        var list = supabaseClient.fetchVideosOrThrow()
        // If the typed fetch yielded items but their videoUriString fields are null/empty,
        // try a defensive JSON mapping to populate fields coming from different column names.
        val needsDefensiveRepair = list.any { it.videoUriString.isNullOrEmpty() && it.localFilePath.isNullOrEmpty() }
        if (list.isEmpty() || needsDefensiveRepair) {
            val jsonArr = supabaseClient.fetchTableJson("videos")
            if (jsonArr.size() > 0) {
                val repaired = mutableListOf<com.example.tareamov.data.entity.VideoData>()
                for (elem in jsonArr) {
                    try {
                        val obj = elem.asJsonObject
                        val id = if (obj.has("id") && !obj.get("id").isJsonNull) obj.get("id").asLong else 0L
                        val username = when {
                            obj.has("username") && !obj.get("username").isJsonNull -> obj.get("username").asString
                            obj.has("creator_username") && !obj.get("creator_username").isJsonNull -> obj.get("creator_username").asString
                            obj.has("user") && !obj.get("user").isJsonNull -> obj.get("user").asString
                            else -> "unknown"
                        }
                        val description = if (obj.has("description") && !obj.get("description").isJsonNull) obj.get("description").asString else ""
                        val title = if (obj.has("title") && !obj.get("title").isJsonNull) obj.get("title").asString else ""
                        // Prefer server `course_id` so UI can resolve creator username via Supabase
                        val courseId = when {
                            obj.has("course_id") && !obj.get("course_id").isJsonNull -> obj.get("course_id").asLong
                            obj.has("courseId") && !obj.get("courseId").isJsonNull -> obj.get("courseId").asLong
                            else -> null
                        }
                        val remoteId = when {
                            obj.has("remote_id") && !obj.get("remote_id").isJsonNull -> obj.get("remote_id").asLong
                            obj.has("remoteId") && !obj.get("remoteId").isJsonNull -> obj.get("remoteId").asLong
                            else -> null
                        }
                        val videoUriString = when {
                            obj.has("video_uri_string") && !obj.get("video_uri_string").isJsonNull -> obj.get("video_uri_string").asString
                            obj.has("video_uri") && !obj.get("video_uri").isJsonNull -> obj.get("video_uri").asString
                            obj.has("video_url") && !obj.get("video_url").isJsonNull -> obj.get("video_url").asString
                            else -> null
                        }
                        val localFilePath = if (obj.has("local_file_path") && !obj.get("local_file_path").isJsonNull) obj.get("local_file_path").asString else null
                        val thumbnailUri = if (obj.has("thumbnail_uri") && !obj.get("thumbnail_uri").isJsonNull) obj.get("thumbnail_uri").asString else if (obj.has("thumbnail") && !obj.get("thumbnail").isJsonNull) obj.get("thumbnail").asString else null
                        val timestamp = try { if (obj.has("timestamp") && !obj.get("timestamp").isJsonNull) obj.get("timestamp").asLong else if (obj.has("created_at") && !obj.get("created_at").isJsonNull) java.time.Instant.parse(obj.get("created_at").asString).toEpochMilli() else System.currentTimeMillis() } catch (t: Exception) { System.currentTimeMillis() }
                        val isPaid = if (obj.has("is_paid") && !obj.get("is_paid").isJsonNull) obj.get("is_paid").asBoolean else false
                        val price = if (obj.has("price") && !obj.get("price").isJsonNull) try { obj.get("price").asDouble } catch (t: Exception) { null } else null

                        val v = com.example.tareamov.data.entity.VideoData(
                            id = id,
                            username = username,
                            description = description,
                            title = title,
                            videoUriString = videoUriString,
                            localFilePath = localFilePath,
                            timestamp = timestamp,
                            isPaid = isPaid,
                            thumbnailUri = thumbnailUri,
                            price = price,
                            courseId = courseId,
                            remoteId = remoteId
                        )
                        repaired.add(v)
                    } catch (t: Exception) {
                        Log.w("SyncRepository", "Failed to parse video json element", t)
                    }
                }
                if (repaired.isNotEmpty()) list = repaired
            }
        }
        // Normalize: if course_id is null, resolve creator username using remote_id from Supabase
        val normalized = try {
            list.map { v ->
                val noCourse = v.courseId == null || v.courseId <= 0
                val rid = v.remoteId ?: 0L
                if (noCourse && rid > 0) {
                    val resolved = supabaseClient.getUsernameFromUserId(rid)
                    if (!resolved.isNullOrBlank() && !resolved.equals(v.username, ignoreCase = true)) {
                        v.copy(username = resolved)
                    } else {
                        v
                    }
                } else {
                    v
                }
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "Failed to normalize video usernames from remote_id", e)
            list
        }

        // Sort by timestamp string descending where possible; fallback to id desc
        val sorted = normalized.sortedWith(compareByDescending<com.example.tareamov.data.entity.VideoData> { v ->
            // timestamp is a Long in our model; use it directly
            v.timestamp
        }.thenByDescending { v -> v.id })
        return sorted
    }

    // Fetch courses from Supabase (all users). Returns empty list on failure or if not configured.
    suspend fun fetchCoursesFromSupabase(): List<Course> {
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.d("SyncRepository", "Supabase not configured - fetchCoursesFromSupabase returning empty list")
                return emptyList()
            }
            val list = withContext(Dispatchers.IO) { supabaseClient.fetchCourses() }
            Log.d("SyncRepository", "fetchCoursesFromSupabase: fetched ${list.size} courses from Supabase")
            list
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchCoursesFromSupabase failed", e)
            emptyList()
        }
    }

    // Fetch FREE courses from Supabase (server-side filter)
    suspend fun fetchFreeCoursesFromSupabase(): List<Course> {
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.d("SyncRepository", "Supabase not configured - fetchFreeCoursesFromSupabase returning empty list")
                return emptyList()
            }
            val list = withContext(Dispatchers.IO) { supabaseClient.fetchFreeCourses() }
            Log.d("SyncRepository", "fetchFreeCoursesFromSupabase: fetched ${list.size} courses from Supabase")
            list
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchFreeCoursesFromSupabase failed", e)
            emptyList()
        }
    }
    
    // Fetch courses with pagination
    suspend fun fetchCoursesPaginated(
        limit: Int = 10,
        offset: Int = 0,
        orderBy: String = "enrollment_count",
        direction: String = "desc"
    ): Pair<List<Course>, Int> {
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.d("SyncRepository", "Supabase not configured - returning empty")
                return Pair(emptyList(), 0)
            }
            withContext(Dispatchers.IO) {
                supabaseClient.fetchCoursesSummary(limit, offset, orderBy, direction)
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchCoursesPaginated failed", e)
            Pair(emptyList(), 0)
        }
    }
    
    // Search videos by query and type
    suspend fun searchVideos(
        query: String,
        searchType: String = "all",
        limit: Int = 50
    ): List<com.example.tareamov.data.entity.VideoData> {
        return try {
            withContext(Dispatchers.IO) {
                supabaseClient.searchVideos(query, searchType, limit)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error searching videos", e)
            emptyList()
        }
    }

    // Fetch videos with pagination
    suspend fun fetchVideosPaginated(
        limit: Int = 10,
        offset: Int = 0
    ): Pair<List<com.example.tareamov.data.entity.VideoData>, Int> {
        // Removed try-catch to allow UI to detect connection errors
        if (!supabaseClient.isConfigured()) {
            Log.d("SyncRepository", "Supabase not configured - returning empty")
            return Pair(emptyList(), 0)
        }
        return withContext(Dispatchers.IO) {
            val (videosRaw, total) = supabaseClient.fetchVideosPaginatedOrThrow(offset = offset, limit = limit)

            // Requirement: when we have remote_id, don't surface the numeric id as the username.
            // Instead, resolve the username from Supabase using that remote_id.
            val remoteIdsToResolve = videosRaw
                .asSequence()
                .filter { (it.courseId == null || it.courseId <= 0L) && (it.remoteId ?: 0L) > 0L }
                .filter { it.username.isBlank() || it.username.equals("unknown", ignoreCase = true) }
                .map { it.remoteId!! }
                .distinct()
                .toList()

            val idToUsername: Map<Long, String> = try {
                if (remoteIdsToResolve.isEmpty()) {
                    emptyMap()
                } else {
                    // Resolve sequentially (page size is small) to keep it simple and stable.
                    val map = mutableMapOf<Long, String>()
                    for (rid in remoteIdsToResolve) {
                        val uname = supabaseClient.getUsernameFromUserId(rid)?.trim().orEmpty()
                        if (uname.isNotBlank()) {
                            map[rid] = uname
                        }
                    }
                    map
                }
            } catch (e: Exception) {
                Log.w("SyncRepository", "fetchVideosPaginated: failed to resolve usernames from remote_id", e)
                emptyMap()
            }

            val videos = if (idToUsername.isEmpty()) {
                videosRaw
            } else {
                videosRaw.map { v ->
                    val noCourse = v.courseId == null || v.courseId <= 0L
                    val rid = v.remoteId ?: 0L
                    if (noCourse && rid > 0L) {
                        val uname = idToUsername[rid]
                        if (!uname.isNullOrBlank() && !uname.equals(v.username, ignoreCase = true)) {
                            // Defensive: some fields may be null due to Gson unsafe allocation.
                            // Ensure non-nullable properties are provided safe defaults when copying.
                            v.copy(username = uname, description = v.description ?: "")
                        } else {
                            // Also ensure description is non-null even when not changing username
                            if (v.description == null) v.copy(description = "") else v
                        }
                    } else {
                        // Ensure description non-null for videos coming from typed mapping
                        if (v.description == null) v.copy(description = "") else v
                    }
                }
            }

            try {
                val debug = videos.firstOrNull { it.id == 98L }
                if (debug != null) {
                    Log.d(
                        "SyncRepository",
                        "fetchVideosPaginated(SUPABASE normalized): id=98 courseId=${debug.courseId} remoteId=${debug.remoteId} username='${debug.username}'"
                    )
                }
            } catch (_: Exception) {
                // ignore
            }

            Pair(videos, total)
        }
    }

    // Fetch courses from Supabase created by a specific username (server-side filter)
    suspend fun fetchCoursesByCreatorFromSupabase(username: String): List<Course> {
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.d("SyncRepository", "Supabase not configured - fetchCoursesByCreatorFromSupabase returning empty list for $username")
                return emptyList()
            }
            var list = withContext(Dispatchers.IO) { supabaseClient.fetchCoursesByCreator(username) }
            Log.d("SyncRepository", "fetchCoursesByCreatorFromSupabase: server returned ${list.size} courses for creator=$username")

            if (list.isNotEmpty()) {
                // Ensure ordering by timestamp desc, fallback to created_at
                val sorted = list.sortedWith(compareByDescending<Course> { it.timestamp }.thenByDescending { it.creationDate })
                return sorted
            }

            // Fallback: fetch all courses ordered and filter client-side (handles server filter failures or column name mismatches)
            Log.d("SyncRepository", "fetchCoursesByCreatorFromSupabase: server-side filter returned empty, falling back to client-side filtering for user_id=$username")
            val all = withContext(Dispatchers.IO) { supabaseClient.fetchCourses() }
            // Convert username to userId for filtering
            val userId = try {
                withContext(Dispatchers.IO) { usuarioDao.getUsuarioByUsername(username) }?.id ?: -1L
            } catch (e: Exception) {
                -1L
            }
            val filtered = all.filter { c ->
                c.creatorUserId == userId
            }.sortedWith(compareByDescending<Course> { it.timestamp }.thenByDescending { it.creationDate })
            Log.d("SyncRepository", "fetchCoursesByCreatorFromSupabase: client-side filtered ${filtered.size} courses for creator=$username (userId=$userId)")
            filtered
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchCoursesByCreatorFromSupabase failed for $username", e)
            emptyList()
        }
    }

    // Fetch a single Course by id from Supabase. Returns null if not configured or not found.
    suspend fun fetchCourseById(id: Long): Course? {
        return try {
            if (!supabaseClient.isConfigured()) return null
            val found = withContext(Dispatchers.IO) { supabaseClient.fetchCourseById(id) }
            if (found != null) Log.d("SyncRepository", "fetchCourseById: found course id=${found.id} title=${found.title}")
            found
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchCourseById failed for id=$id", e)
            null
        }
    }

    // Fetch creator name/username for a course with exact title. Returns null if not found.
    suspend fun fetchCreatorNameByCourseTitle(title: String): String? {
        return try {
            if (!supabaseClient.isConfigured()) return null
            val course = withContext(Dispatchers.IO) { supabaseClient.fetchCourseByTitle(title) }
            if (course == null) {
                Log.d("SyncRepository", "fetchCreatorNameByCourseTitle: no course found with title='$title'")
                return null
            }
            // Fetch username from creator_user_id
            val creatorUsername = withContext(Dispatchers.IO) {
                com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId)
            }
            return if (!creatorUsername.isNullOrBlank()) creatorUsername else null
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchCreatorNameByCourseTitle failed for title=$title", e)
            null
        }
    }

    /**
     * Initiate payment transaction on the backend and get the Wompi Checkout URL.
     * Amount will be fetched from courses table on backend for security.
     */
    suspend fun initiatePayment(userId: Long, courseId: Long): String? {
        return withContext(Dispatchers.IO) {
            try {
                val jsonBody = org.json.JSONObject().apply {
                    put("userId", userId)
                    put("courseId", courseId)
                }
                
                val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url("${com.example.tareamov.BuildConfig.BACKEND_URL}/api/payment/initiate")
                    .post(body)
                    .build()
                
                val client = okhttp3.OkHttpClient()
                client.newCall(request).execute().use { response ->
                    val responseString = response.body?.string()
                    if (response.isSuccessful && responseString != null) {
                        val responseJson = org.json.JSONObject(responseString)
                        if (responseJson.optBoolean("success")) {
                            return@withContext responseJson.optString("payment_url")
                        }
                    }
                    Log.e("SyncRepo", "Payment Init Failed: $responseString")
                    null
                }
            } catch (e: Exception) {
                Log.e("SyncRepo", "Error initiating payment", e)
                null
            }
        }
    }


    // Verify transaction status (wrapper for SupabaseRepository)
    suspend fun verifyTransactionStatus(transactionId: String): Map<String, Any> {
         return supabaseRepo.verifyTransactionStatus(transactionId)
    }

    // Get pending transaction id (wrapper for SupabaseRepository)
    suspend fun getPendingTransactionId(userId: Long, courseId: Long): String? {
        return supabaseRepo.getPendingTransactionId(userId, courseId)
    }

    // Public wrapper: fetch a single Course by exact title from Supabase (case-insensitive exact match).
    suspend fun fetchCourseByTitleFromSupabase(title: String): Course? {
        return try {
            if (!supabaseClient.isConfigured()) return null
            withContext(Dispatchers.IO) { supabaseClient.fetchCourseByTitle(title) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchCourseByTitleFromSupabase failed for title=$title", e)
            null
        }
    }

    // Wrapper to perform a broader search for courses on Supabase (used as fallback).
    suspend fun searchCoursesInSupabase(query: String): List<Course> {
        return try {
            if (!supabaseClient.isConfigured()) return emptyList()
            withContext(Dispatchers.IO) { supabaseClient.searchCourses(query) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "searchCoursesInSupabase failed for query=$query", e)
            emptyList()
        }
    }

    // New wrappers that use SupabaseClient server-side filters when available
    suspend fun fetchTopicsByCourseFromSupabase(courseId: Long): List<Topic> {
        Log.d("SyncRepository", "📚 fetchTopicsByCourseFromSupabase called for courseId=$courseId")
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "⚠️ Supabase not configured!")
                return emptyList()
            }
            val topics = withContext(Dispatchers.IO) { supabaseClient.fetchTopicsByCourse(courseId) }
            Log.d("SyncRepository", "📚 fetchTopicsByCourseFromSupabase: returned ${topics.size} topics for courseId=$courseId")
            topics
        } catch (e: Exception) {
            Log.w("SyncRepository", "❌ fetchTopicsByCourseFromSupabase failed for courseId=$courseId", e)
            emptyList()
        }
    }

    // Fetch single topic by id from Supabase
    suspend fun fetchTopicByIdFromSupabase(topicId: Long): Topic? {
        Log.d("SyncRepository", "📚 fetchTopicByIdFromSupabase called for topicId=$topicId")
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "⚠️ Supabase not configured!")
                return null
            }
            val topic = withContext(Dispatchers.IO) { supabaseClient.fetchTopicById(topicId) }
            Log.d("SyncRepository", "📚 fetchTopicByIdFromSupabase: returned topic=${topic?.name} for topicId=$topicId")
            topic
        } catch (e: Exception) {
            Log.w("SyncRepository", "❌ fetchTopicByIdFromSupabase failed for topicId=$topicId", e)
            null
        }
    }

    suspend fun fetchTasksByTopicIdsFromSupabase(topicIds: List<Long>): List<Task> {
        return try {
            if (!supabaseClient.isConfigured() || topicIds.isEmpty()) return emptyList()
            withContext(Dispatchers.IO) { supabaseClient.fetchTasksByTopicIds(topicIds) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchTasksByTopicIdsFromSupabase failed for topicIds=$topicIds", e)
            emptyList()
        }
    }

    // Fetch single task by id from Supabase
    suspend fun fetchTaskByIdFromSupabase(taskId: Long): Task? {
        return try {
            if (!supabaseClient.isConfigured()) return null
            withContext(Dispatchers.IO) { supabaseClient.fetchTaskById(taskId) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchTaskByIdFromSupabase failed for id=$taskId", e)
            null
        }
    }

    // Fetch single video by id from Supabase
    suspend fun fetchVideoByIdFromSupabase(videoId: Long): VideoData? {
        return try {
            if (!supabaseClient.isConfigured()) return null
            withContext(Dispatchers.IO) { supabaseClient.fetchVideoById(videoId) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchVideoByIdFromSupabase failed for id=$videoId", e)
            null
        }
    }

    suspend fun fetchContentItemsByTopicIdsFromSupabase(topicIds: List<Long>): List<ContentItem> {
        Log.d("SyncRepository", "📚 fetchContentItemsByTopicIdsFromSupabase called with topicIds=$topicIds")
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "⚠️ Supabase not configured!")
                return emptyList()
            }
            if (topicIds.isEmpty()) {
                Log.w("SyncRepository", "⚠️ Empty topicIds list!")
                return emptyList()
            }
            val result = withContext(Dispatchers.IO) { supabaseClient.fetchContentItemsByTopicIds(topicIds) }
            Log.d("SyncRepository", "📚 fetchContentItemsByTopicIdsFromSupabase returned ${result.size} items")
            result
        } catch (e: Exception) {
            Log.e("SyncRepository", "❌ fetchContentItemsByTopicIdsFromSupabase failed for topicIds=$topicIds", e)
            emptyList()
        }
    }
    
    // Fetch content items for a specific task from Supabase
    suspend fun fetchContentItemsByTaskIdFromSupabase(taskId: Long): List<ContentItem> {
        return try {
            if (!supabaseClient.isConfigured() || taskId <= 0) return emptyList()
            Log.d("SyncRepository", "Fetching content items for taskId=$taskId from Supabase")
            val items = withContext(Dispatchers.IO) { supabaseClient.fetchContentItemsByTaskId(taskId) }
            Log.d("SyncRepository", "Fetched ${items.size} content items for taskId=$taskId")
            items
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchContentItemsByTaskIdFromSupabase failed for taskId=$taskId", e)
            emptyList()
        }
    }

    /**
     * Gather course topics & tasks, build a prompt and call the local microservice
     * to generate multiple-choice reinforcement questions.
     * Returns an empty list on error.
     */
    suspend fun requestReinforcementQuiz(courseId: Long, courseName: String, questionCount: Int = 5, userId: Long? = null): List<com.example.tareamov.ui.compose.QuizQuestion> {
        try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "requestReinforcementQuiz: Supabase not configured, aborting")
                return emptyList()
            }

            // Fetch topics and tasks from Supabase
            val topics = fetchTopicsByCourseFromSupabase(courseId)
            val topicIds = topics.map { it.id }
            val tasks = if (topicIds.isNotEmpty()) fetchTasksByTopicIdsFromSupabase(topicIds) else emptyList()
            val contentItems = if (topicIds.isNotEmpty()) fetchContentItemsByTopicIdsFromSupabase(topicIds) else emptyList()

            if (topics.isEmpty() && tasks.isEmpty() && contentItems.isEmpty()) return emptyList()

            val contextBuilder = StringBuilder()
            contextBuilder.append("Curso: $courseName\n")
            contextBuilder.append("Temas:\n")
            topics.forEach { contextBuilder.append("- ${it.name}: ${it.description ?: ""}\n") }
            contextBuilder.append("\nTareas:\n")
            tasks.forEach { contextBuilder.append("- ${it.name}: ${it.description ?: ""}\n") }

            // Prepare jsonContent with course files for LLM analysis
            val jsonContentList = contentItems.map { item ->
                mapOf(
                    "uri" to item.uriString,
                    "name" to (item.name ?: "archivo_sin_nombre"),
                    "type" to (item.contentType ?: "unknown")
                )
            }
            val jsonContentString = com.google.gson.Gson().toJson(jsonContentList)

            val prompt = """
                Genera $questionCount preguntas de selección múltiple (A, B, C, D) para reforzar el conocimiento de PROGRAMACIÓN de este curso.
                IMPORTANTE: Solo genera preguntas basadas en el contenido proporcionado por el docente (temas, tareas y archivos adjuntos).
                NO uses ni menciones ningún contenido de entregas de estudiantes.
                Todo el refuerzo debe ser exclusivamente del material docente.

               
                Contexto del curso:
                $contextBuilder

                Materiales (Documentos/Videos/Imágenes) adjuntos para análisis: ${jsonContentList.size} archivos

                Formato de respuesta estrictamente JSON array, sin markdown, sin texto extra:
                [
                  {
                    "question": "¿Pregunta técnica sobre programación?",
                    "options": ["Opción A (código correcto)", "Opción B (error común)", "Opción C (alternativa válida)", "Opción D (incorrecta)"],
                    "correctIndex": 0,
                    "explanation": "Explicación técnica detallada con referencia al código o concepto."
                  }
                ]
            """.trimIndent()

            // Call local microservice (assume emulator default)
            val serviceUrl = "http://10.0.2.2:3001/procesar-prompt"
            val client = okhttp3.OkHttpClient.Builder().build()
            val gson = com.google.gson.Gson()
            val reqBody = mapOf(
                "prompt" to prompt,
                "ollamaUrl" to "",
                "model" to "",
                "jsonContent" to jsonContentString,
                "courseId" to courseId,
                "userId" to userId
            )
            val bodyJson = gson.toJson(reqBody)
            val request = okhttp3.Request.Builder()
                .url(serviceUrl)
                .post(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val resp = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            resp.use { r ->
                if (!r.isSuccessful) {
                    Log.w("SyncRepository", "requestReinforcementQuiz: microservice returned ${r.code}")
                    return emptyList()
                }
                val body = r.body?.string() ?: return emptyList()
                val map = gson.fromJson(body, Map::class.java)
                val respuesta = map["respuesta_texto"]?.toString() ?: map["respuestaText"]?.toString() ?: body
                val cleanJson = respuesta.replace("```json", "").replace("```", "").trim()

                return try {
                    val type = com.google.gson.reflect.TypeToken.getParameterized(List::class.java, com.example.tareamov.ui.compose.QuizQuestion::class.java).type
                    val rawQuestions = gson.fromJson<List<com.example.tareamov.ui.compose.QuizQuestion>>(cleanJson, type)
                    // Sanitize explanations - Gson may set null even with default values
                    rawQuestions?.map { q ->
                        val safeExplanation = when {
                            q.explanation.isNullOrBlank() || q.explanation == "null" -> {
                                val correctOpt = q.options.getOrElse(q.correctIndex) { "la opción correcta" }
                                "La respuesta correcta es: \"$correctOpt\". Explicación auto-generada."
                            }
                            else -> q.explanation
                        }
                        q.copy(explanation = safeExplanation)
                    } ?: emptyList()
                } catch (e: Exception) {
                    Log.e("SyncRepository", "requestReinforcementQuiz: failed to parse JSON", e)
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "requestReinforcementQuiz failed", e)
            return emptyList()
        }
    }


    /**
     * Save reinforcement history via backend API.
     */
    suspend fun saveReinforcementHistory(userId: Long, courseId: Long, questionsJson: String): Boolean {
        return try {
            Log.d("SyncRepository", "Saving reinforcement history for user=$userId course=$courseId")
            val gson = com.google.gson.Gson()
            val listType = object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
            val questions: List<Map<String, Any?>> = try {
                gson.fromJson(questionsJson, listType) ?: emptyList()
            } catch (e: Exception) {
                listOf(mapOf("raw" to questionsJson))
            }
            val result = BackendApiService.saveReinforcementSession(userId, courseId, questions)
            val ok = result.isSuccess
            Log.d("SyncRepository", "Reinforcement history saved via backend: $ok")
            ok
        } catch (e: Exception) {
            Log.e("SyncRepository", "saveReinforcementHistory failed", e)
            false
        }
    }

    // Fetch videos for a specific username from Supabase (server-side filter)
    suspend fun fetchVideosByUsernameFromSupabase(username: String): List<com.example.tareamov.data.entity.VideoData> {
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.d("SyncRepository", "Supabase not configured - fetchVideosByUsernameFromSupabase returning empty list for $username")
                return emptyList()
            }
            var list = withContext(Dispatchers.IO) { supabaseClient.fetchVideosByUsername(username) }
            Log.d("SyncRepository", "fetchVideosByUsernameFromSupabase: server returned ${list.size} videos for username=$username")

            if (list.isNotEmpty()) {
                // Sort by timestamp desc then by created_at if available
                val sorted = list.sortedWith(compareByDescending<com.example.tareamov.data.entity.VideoData> { it.timestamp }.thenByDescending { it.thumbnailUri })
                return sorted
            }

            // Fallback: fetch all videos and filter client-side
            Log.d("SyncRepository", "fetchVideosByUsernameFromSupabase: server-side filter returned empty, falling back to client-side filtering for $username")
            val all = withContext(Dispatchers.IO) { supabaseClient.fetchVideos() }
            val target = username.trim().lowercase()
            val filtered = all.filter { v ->
                val vu = (v.username ?: "").trim().lowercase()
                vu == target
            }.sortedByDescending { it.timestamp }
            Log.d("SyncRepository", "fetchVideosByUsernameFromSupabase: client-side filtered ${filtered.size} videos for username=$username")
            filtered
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchVideosByUsernameFromSupabase failed for $username", e)
            emptyList()
        }
    }

    // Fetch videos by creator user ID from Supabase
    // This method fetches videos from TWO sources:
    // 1. Videos linked via course_id (courses created by this user)
    // 2. Videos with remote_id = userId (videos directly associated with the creator)
    suspend fun fetchVideosByCreatorUserIdFromSupabase(userId: Long): List<com.example.tareamov.data.entity.VideoData> {
        return try {
            if (!supabaseClient.isConfigured()) {
                return emptyList()
            }
            
            // Get username for the videos
            val username = withContext(Dispatchers.IO) { supabaseClient.getUsernameFromUserId(userId) } ?: ""
            
            // Fetch videos from both sources sequentially (simpler and avoids coroutine scope issues)
            val videosByCourse = withContext(Dispatchers.IO) {
                // Step 1: Fetch courses for this user
                val courses = supabaseClient.fetchCoursesByCreatorUserId(userId)
                val courseIds = courses.map { it.id }
                
                if (courseIds.isEmpty()) {
                    emptyList()
                } else {
                    // Step 2: Fetch videos for these courses
                    supabaseClient.fetchVideosByCourseIds(courseIds)
                }
            }
            
            val videosByRemoteId = withContext(Dispatchers.IO) {
                // Fetch videos where remote_id = userId (direct creator association)
                supabaseClient.fetchVideosByRemoteId(userId)
            }
            
            // Combine and deduplicate by video ID
            val allVideos = (videosByCourse + videosByRemoteId)
                .distinctBy { video -> video.id }
                .map { video ->
                    // Defensive: ensure non-nullable fields are satisfied (Gson may have produced nulls)
                    val desc = video.description ?: ""
                    val v = if (video.description == null) video.copy(description = desc) else video
                    v.copy(username = username, remoteId = userId, description = desc)
                }
                .sortedByDescending { video -> video.timestamp }
            
            Log.d("SyncRepository", "fetchVideosByCreatorUserIdFromSupabase: Found ${videosByCourse.size} via courses, ${videosByRemoteId.size} via remote_id, ${allVideos.size} total unique videos for userId=$userId")
            
            allVideos
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchVideosByCreatorUserIdFromSupabase failed for userId=$userId", e)
            emptyList()
        }
    }

    // Subscriptions helpers
    suspend fun insertSubscriptionRemote(sub: Subscription): Boolean {
        return try {
            val result = BackendApiService.subscribe(sub.creatorId)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Subscription created via backend for ${sub.subscriberId} -> ${sub.creatorId}")
                true
            } else {
                Log.w("SyncRepository", "insertSubscriptionRemote failed: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "insertSubscriptionRemote failed", e)
            false
        }
    }
    
    // Insert a Topic via backend API and return remote id (or null)
    suspend fun insertTopicRemote(topic: com.example.tareamov.data.entity.Topic): Long? {
        return try {
            val result = BackendApiService.createTopic(topic)
            if (result.isSuccess) {
                val created = result.getOrNull()
                Log.d("SyncRepository", "Topic created via backend: id=${created?.id}")
                created?.id
            } else {
                Log.w("SyncRepository", "insertTopicRemote failed: ${result.errorMessage()}")
                null
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "insertTopicRemote failed", e)
            null
        }
    }

    /**
     * Insert a topic via backend API. The courseTitle parameter is kept for
     * API compatibility but the backend resolves course association server-side.
     */
    suspend fun insertTopicRemoteUsingTrigger(topic: com.example.tareamov.data.entity.Topic, courseTitle: String?): Long? {
        return insertTopicRemote(topic)
    }
    
    // Insert a Task into Supabase and return remote id (or null)
    // NOTE: Creator parameters are accepted for compatibility but ignored since tasks table doesn't have those columns
    /**
     * Insert a task via backend API and return remote id (or null).
     * The backend handles topic/course resolution and ID assignment server-side.
     */
    suspend fun insertTaskRemote(
        task: com.example.tareamov.data.entity.Task,
        fallbackCreatorUsername: String? = null,
        fallbackCreatorUserId: Long? = null
    ): Long? {
        return try {
            if (task.topicId <= 0) {
                Log.e("SyncRepository", "Invalid topicId=${task.topicId} for task: name=${task.name}")
                return null
            }

            // Check if already exists remotely via backend
            val existingResult = BackendApiService.getTasksByTopic(task.topicId)
            if (existingResult.isSuccess) {
                val existing = existingResult.getOrNull()
                    ?.firstOrNull { (it.name ?: "").equals(task.name, ignoreCase = true) }
                if (existing != null) {
                    Log.d("SyncRepository", "Task already exists remotely id=${existing.id}")
                    return existing.id
                }
            }

            val result = BackendApiService.createTask(task)
            if (result.isSuccess) {
                val created = result.getOrNull()
                Log.i("SyncRepository", "Task created via backend: id=${created?.id}, name='${task.name}'")
                created?.id
            } else {
                Log.w("SyncRepository", "insertTaskRemote failed: ${result.errorMessage()}")
                null
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "insertTaskRemote exception", e)
            null
        }
    }

    /**
     * Resolve a local topic ID to its remote counterpart via backend API.
     * If the topic doesn't exist remotely it is created through the backend.
     */
    private suspend fun resolveRemoteTopicId(localTopicId: Long): Long? {
        if (localTopicId <= 0) return null

        // Try direct fetch by ID
        val directResult = BackendApiService.getTopicById(localTopicId)
        if (directResult.isSuccess) {
            val topic = directResult.getOrNull()
            if (topic != null && topic.id > 0) return topic.id
        }

        // Fallback: match by name + course
        val localTopic = withContext(Dispatchers.IO) { topicDao.getTopicById(localTopicId) } ?: run {
            Log.e("SyncRepository", "resolveRemoteTopicId: local topic $localTopicId not found")
            return null
        }

        val remoteCourseId = resolveRemoteCourseId(localTopic.courseId) ?: run {
            Log.e("SyncRepository", "resolveRemoteTopicId: could not resolve course for topic $localTopicId")
            return null
        }

        val topicsResult = BackendApiService.getTopicsByCourse(remoteCourseId)
        if (topicsResult.isSuccess) {
            val match = topicsResult.getOrNull()?.firstOrNull {
                it.id == localTopic.id || it.name.equals(localTopic.name, ignoreCase = true)
            }
            if (match != null) return match.id
        }

        // Create remotely
        val topicForInsert = localTopic.copy(courseId = remoteCourseId)
        val createResult = BackendApiService.createTopic(topicForInsert)
        return if (createResult.isSuccess) createResult.getOrNull()?.id else null
    }

    /**
     * Resolve a local course ID to its remote counterpart via backend API.
     * If the course doesn't exist remotely it is created through the backend.
     */
    private suspend fun resolveRemoteCourseId(localCourseId: Long): Long? {
        if (localCourseId <= 0) return null

        // Fast path
        val directResult = BackendApiService.getCourseById(localCourseId)
        if (directResult.isSuccess) {
            val course = directResult.getOrNull()
            if (course != null && course.id > 0) return course.id
        }

        val localCourse = withContext(Dispatchers.IO) { courseDao.getCourseById(localCourseId) } ?: run {
            Log.e("SyncRepository", "resolveRemoteCourseId: local course $localCourseId not found")
            return null
        }

        // Create remotely as last resort
        val createResult = BackendApiService.createCourse(localCourse)
        return if (createResult.isSuccess) {
            val id = createResult.getOrNull()?.id
            Log.i("SyncRepository", "resolveRemoteCourseId: created course '${localCourse.title}' id=$id")
            id
        } else {
            Log.e("SyncRepository", "resolveRemoteCourseId: failed to create course '${localCourse.title}'")
            null
        }
    }

    // Update a Task remotely via backend API
    suspend fun updateTaskRemote(task: com.example.tareamov.data.entity.Task): Boolean {
        return try {
            Log.d("SyncRepository", "updateTaskRemote: id=${task.id}, name='${task.name}'")
            val updates = mutableMapOf<String, Any?>(
                "name" to task.name,
                "description" to task.description,
                "topic_id" to task.topicId,
                "due_date" to task.dueDate
            )
            val result = BackendApiService.updateTask(task.id, updates)
            if (result.isSuccess) {
                Log.i("SyncRepository", "Task ${task.id} updated via backend")
                true
            } else {
                // If update fails because task doesn't exist, create it
                Log.w("SyncRepository", "Update failed for task ${task.id}, attempting create")
                val createResult = BackendApiService.createTask(task)
                createResult.isSuccess
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "updateTaskRemote failed for task ${task.id}", e)
            false
        }
    }

    // Update Persona remotely via backend API
    suspend fun updatePersonaRemote(persona: Persona): Boolean {
        return try {
            val updates = mapOf<String, Any?>(
                "nombre" to persona.nombres,
                "apellido" to persona.apellidos,
                "telefono" to persona.telefono
            )
            val result = BackendApiService.updatePersona(persona.id, updates)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Persona ${persona.id} updated via backend")
                true
            } else {
                Log.e("SyncRepository", "updatePersonaRemote failed: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "updatePersonaRemote failed for persona ${persona.id}", e)
            false
        }
    }

    // Update Usuario remotely via backend API
    suspend fun updateUsuarioRemote(usuario: Usuario): Boolean {
        return try {
            val updates = mapOf<String, Any?>(
                "username" to usuario.usuario,
                "email" to usuario.email,
                "avatar_url" to usuario.avatar
            )
            val result = BackendApiService.updateMyProfile(updates)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Usuario ${usuario.id} updated via backend")
                true
            } else {
                Log.e("SyncRepository", "updateUsuarioRemote failed: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "updateUsuarioRemote failed for usuario ${usuario.id}", e)
            false
        }
    }

    // Update Usuario profile (username, avatar) remotely via backend API
    suspend fun updateUsuarioProfileRemote(userId: Long, username: String, avatarUrl: String?): Boolean {
        return try {
            val updates = mutableMapOf<String, Any?>("username" to username)
            if (avatarUrl != null) updates["avatar_url"] = avatarUrl
            val result = BackendApiService.updateMyProfile(updates)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Usuario profile $userId updated via backend")
                true
            } else {
                Log.e("SyncRepository", "updateUsuarioProfileRemote failed: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "updateUsuarioProfileRemote failed for usuario $userId", e)
            false
        }
    }
    
    // Insert a ContentItem via backend API and return remote id (or null)
    suspend fun insertContentItemRemote(contentItem: com.example.tareamov.data.entity.ContentItem): Long? {
        return try {
            Log.d("SyncRepository", "Inserting ContentItem: name=${contentItem.name}, type=${contentItem.contentType}")
            val result = BackendApiService.createContentItem(contentItem)
            if (result.isSuccess) {
                val created = result.getOrNull()
                Log.d("SyncRepository", "ContentItem created via backend: id=${created?.id}")
                created?.id
            } else {
                Log.w("SyncRepository", "insertContentItemRemote failed: ${result.errorMessage()}")
                null
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "Failed to insert content item via backend", e)
            null
        }
    }

    suspend fun deleteSubscriptionRemote(subscriberId: Long, creatorId: Long): Boolean {
        return try {
            val result = BackendApiService.unsubscribe(creatorId)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Subscription deleted via backend for $subscriberId -> $creatorId")
                true
            } else {
                Log.w("SyncRepository", "deleteSubscriptionRemote failed: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "deleteSubscriptionRemote failed", e)
            false
        }
    }

    suspend fun isSubscribedRemote(subscriberId: Long, creatorId: Long): Boolean {
        return try {
            val result = BackendApiService.checkSubscription(creatorId)
            result.isSuccess && result.getOrNull() == true
        } catch (e: Exception) {
            false
        }
    }

    // Fetch subscriber count for a creator via backend API
    suspend fun fetchSubscriberCountFromSupabase(creatorId: Long): Long {
        return try {
            val result = BackendApiService.getSubscriberCount(creatorId)
            if (result.isSuccess) {
                result.getOrNull()?.toLong() ?: 0L
            } else 0L
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchSubscriberCount failed", e)
            0L
        }
    }

    // New: sincronizar a Supabase via Backend API
    fun syncLocalToSupabase() {
        syncScope.launch {
            try {
                Log.i("SyncRepository", "Starting syncLocalToSupabase via backend API...")

                // --- Courses ---
                courseDao.getAllCoursesSync().forEach { course ->
                    try {
                        val result = BackendApiService.createCourse(course)
                        if (result.isSuccess) Log.i("SyncRepository", "Course ${course.id} synced via backend")
                        else {
                            // Try update if create fails (already exists)
                            val updateResult = BackendApiService.updateCourse(course.id, buildCoursePayload(course))
                            if (updateResult.isSuccess) Log.i("SyncRepository", "Course ${course.id} updated via backend")
                            else Log.w("SyncRepository", "Failed to sync course ${course.id}")
                        }
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception syncing course ${course.id}", e)
                    }
                }

                // --- Topics ---
                topicDao.getAllTopics().forEach { topic ->
                    try {
                        val result = BackendApiService.createTopic(topic)
                        if (result.isSuccess) Log.i("SyncRepository", "Topic ${topic.id} synced via backend")
                        else Log.w("SyncRepository", "Failed to sync topic ${topic.id}: ${result.errorMessage()}")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception syncing topic ${topic.id}", e)
                    }
                }

                // --- Tasks ---
                taskDao.getAllTasks().forEach { task ->
                    try {
                        val result = BackendApiService.createTask(task)
                        if (result.isSuccess) Log.i("SyncRepository", "Task ${task.id} synced via backend")
                        else Log.w("SyncRepository", "Failed to sync task ${task.id}: ${result.errorMessage()}")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception syncing task ${task.id}", e)
                    }
                }

                // --- ContentItems (skip if already exists on server) ---
                contentItemDao.getAllContentItems().forEach { item ->
                    try {
                        // Check if content items already exist for this topic/task before creating
                        val alreadyExists = if (item.topicId != null && item.topicId > 0) {
                            val existing = BackendApiService.getContentItemsByTopic(item.topicId)
                            if (existing.isSuccess) {
                                existing.getOrNull()?.any { it.name == item.name && it.uriString == item.uriString } == true
                            } else false
                        } else if (item.taskId != null && item.taskId > 0) {
                            val existing = BackendApiService.getContentItemsByTask(item.taskId)
                            if (existing.isSuccess) {
                                existing.getOrNull()?.any { it.name == item.name && it.uriString == item.uriString } == true
                            } else false
                        } else false

                        if (alreadyExists) {
                            Log.i("SyncRepository", "ContentItem ${item.id} already exists on server, skipping")
                        } else {
                            val result = BackendApiService.createContentItem(item)
                            if (result.isSuccess) Log.i("SyncRepository", "ContentItem ${item.id} synced via backend")
                            else Log.w("SyncRepository", "Failed to sync contentItem ${item.id}: ${result.errorMessage()}")
                        }
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception syncing contentItem ${item.id}", e)
                    }
                }

                // --- TaskSubmissions ---
                taskSubmissionDao.getAllTaskSubmissions().forEach { submission ->
                    try {
                        val hasGrade = submission.grade != null || !submission.feedback.isNullOrBlank()
                        if (hasGrade) {
                            BackendApiService.gradeSubmission(submission.id, submission.grade ?: 0f, submission.feedback)
                        } else {
                            val data = mapOf<String, Any?>(
                                "task_id" to submission.taskId,
                                "student_id" to submission.studentId,
                                "file_url" to submission.fileUri
                            )
                            BackendApiService.submitWork(data)
                        }
                        Log.i("SyncRepository", "TaskSubmission ${submission.id} synced via backend")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception syncing submission ${submission.id}", e)
                    }
                }

                // --- Subscriptions ---
                subscriptionDao.getAllSubscriptions().forEach { sub ->
                    try {
                        val result = BackendApiService.subscribe(sub.creatorId)
                        if (result.isSuccess) Log.i("SyncRepository", "Subscription ${sub.subscriberId}->${sub.creatorId} synced")
                        else Log.w("SyncRepository", "Failed to sync subscription ${sub.subscriberId}->${sub.creatorId}")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception syncing subscription", e)
                    }
                }

                // --- Videos ---
                videoDao.getAllVideos().forEach { video ->
                    try {
                        val result = BackendApiService.createVideo(video)
                        if (result.isSuccess) Log.i("SyncRepository", "Video ${video.id} synced via backend")
                        else Log.w("SyncRepository", "Failed to sync video ${video.id}: ${result.errorMessage()}")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception syncing video ${video.id}", e)
                    }
                }

                Log.i("SyncRepository", "syncLocalToSupabase completed via backend API")
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception during syncLocalToSupabase", e)
            }
        }
    }

    // Public helper to upload a single VideoData to Supabase via Backend API (non-blocking)
    fun uploadVideoToSupabase(video: com.example.tareamov.data.entity.VideoData) {
        syncScope.launch {
            try {
                val success = uploadVideoViaBackendApi(video)
                if (success) {
                    Log.i("SyncRepository", "Video uploaded via backend API title=${video.title}")
                } else {
                    Log.w("SyncRepository", "Video upload via backend failed for video id=${video.id}")
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception uploading video via backend", e)
            }
        }
    }

    // Suspend version that returns success/failure via Backend API
    suspend fun uploadVideoToSupabaseSuspend(video: com.example.tareamov.data.entity.VideoData): Boolean {
        return try {
            uploadVideoViaBackendApi(video)
        } catch (e: Exception) {
            Log.e("SyncRepository", "Exception in uploadVideoToSupabaseSuspend", e)
            false
        }
    }
    
    /**
     * Upload video to Supabase via backend API endpoint
     * This centralizes database operations on the backend
     */
    private suspend fun uploadVideoViaBackendApi(video: com.example.tareamov.data.entity.VideoData): Boolean = withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val baseUrl = com.example.tareamov.service.ServerEndpointResolver.RAILWAY_API_URL
            val url = "$baseUrl/video/insert"
            
            // Build JSON payload
            val jsonPayload = org.json.JSONObject().apply {
                put("title", video.title)
                put("description", video.description)
                put("videoUriString", video.videoUriString)
                put("localFilePath", video.localFilePath)
                put("timestamp", video.timestamp)
                put("isPaid", video.isPaid)
                put("thumbnailUri", video.thumbnailUri)
                put("price", video.price)
                put("remoteId", video.remoteId)
                if (video.courseId != null) {
                    put("courseId", video.courseId)
                }
            }
            
            Log.d("SyncRepository", "📤 Sending video insert to backend: $url")
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = okhttp3.RequestBody.Companion.create(mediaType, jsonPayload.toString())
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d("SyncRepository", "📥 Backend response: code=${response.code}, body=$responseBody")
                
                if (response.isSuccessful && responseBody != null) {
                    val json = org.json.JSONObject(responseBody)
                    if (json.optBoolean("success", false)) {
                        val videoId = json.optLong("videoId", -1L)
                        Log.d("SyncRepository", "✅ Video inserted via backend: ID=$videoId")
                        return@withContext true
                    }
                }
                Log.e("SyncRepository", "❌ Backend video insert failed: ${response.code}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "❌ Error uploading video via backend", e)
            return@withContext false
        }
    }

    // New: traer datos desde Supabase y guardarlos localmente en Room
    fun syncSupabaseToLocal() {
        syncScope.launch {
            if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                Log.w("SyncRepository", "Supabase not configured. Skipping syncSupabaseToLocal.")
                return@launch
            }

            try {
                Log.i("SyncRepository", "Starting syncSupabaseToLocal()")

                // Personas
                val personas = com.example.tareamov.service.SupabaseClient.fetchPersonas()
                personas.forEach { p ->
                    try {
                        personaDao.insertPersona(p)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert persona ${p.id}", e)
                    }
                }

                // Usuarios
                val usuarios = com.example.tareamov.service.SupabaseClient.fetchUsuarios()
                usuarios.forEach { u ->
                    try {
                        usuarioDao.insertUsuario(u)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert usuario ${u.id}", e)
                    }
                }

                // Roles
                val roles = com.example.tareamov.service.SupabaseClient.fetchRoles()
                roles.forEach { r ->
                    try {
                        rolDao.insertRol(r)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert rol ${r.id}", e)
                    }
                }

                // Recursos
                val recursos = com.example.tareamov.service.SupabaseClient.fetchRecursos()
                recursos.forEach { rc ->
                    try {
                        recursoDao.insertRecurso(rc)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert recurso ${rc.id}", e)
                    }
                }

                // RolRecursos
                val rolRecursos = com.example.tareamov.service.SupabaseClient.fetchRolRecursos()
                rolRecursos.forEach { rr ->
                    try {
                        rolRecursoDao.insertRolRecurso(rr)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert rol_recurso ${rr.rolId}-${rr.recursoId}", e)
                    }
                }

                // Courses: skipped (no courseDao available in this repository). Add courseDao to SyncRepository if needed.

                // Topics
                val topics = com.example.tareamov.service.SupabaseClient.fetchTopics()
                topics.forEach { t ->
                    try {
                        topicDao.insertTopic(t)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert topic ${t.id}", e)
                    }
                }

                // Content Items
                val contentItems = com.example.tareamov.service.SupabaseClient.fetchContentItems()
                contentItems.forEach { ci ->
                    try {
                        contentItemDao.insertContentItem(ci)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert content item ${ci.id}", e)
                    }
                }

                // Tasks
                val tasks = com.example.tareamov.service.SupabaseClient.fetchTasks()
                tasks.forEach { task ->
                    try {
                        taskDao.insertTask(task)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert task ${task.id}", e)
                    }
                }

                // Subscriptions
                val subs = com.example.tareamov.service.SupabaseClient.fetchSubscriptions()
                subs.forEach { s ->
                    try {
                        subscriptionDao.insertSubscription(s)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert subscription", e)
                    }
                }

                // TaskSubmissions
                // IMPORTANT: Task submissions are treated as remote-authoritative and should NOT be
                // inserted into the local SQLite/Room database to avoid FK constraint issues and
                // duplication. Keep the local DB read-only for submissions or migrate other code to
                // always read from Supabase instead.
                val submissions = com.example.tareamov.service.SupabaseClient.fetchTaskSubmissions()
                if (submissions.isNotEmpty()) {
                    Log.i("SyncRepository", "Fetched ${submissions.size} task_submissions from Supabase — skipping local insert (remote-authoritative)")
                }

                // Chat messages
                val chats = com.example.tareamov.service.SupabaseClient.fetchChatMessages()
                chats.forEach { cm ->
                    try {
                        chatMessageDao.insertMessage(cm)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert chat message ${cm.id}", e)
                    }
                }

                // File contexts
                val files = com.example.tareamov.service.SupabaseClient.fetchFileContexts()
                files.forEach { fc ->
                    try {
                        fileContextDao.insertFileContext(fc)
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Failed to insert file context ${fc.id}", e)
                    }
                }

                Log.i("SyncRepository", "syncSupabaseToLocal() completed")
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception during syncSupabaseToLocal", e)
            }
        }
    }

    // --- Supabase fallback helpers for task submissions ---
    // Fetch a single user's submission for a specific task from Supabase (remote-authoritative)
    suspend fun fetchUserSubmissionForTaskFromSupabase(taskId: Long, username: String): TaskSubmission? {
        return try {
            if (!supabaseClient.isConfigured()) return null
            // Resolve username -> user id on Supabase, then match by numeric studentId
            val remoteUser = try {
                withContext(Dispatchers.IO) { supabaseClient.fetchUsuarioByUsername(username) }
            } catch (e: Exception) {
                Log.w("SyncRepository", "fetchUsuarioByUsername failed for $username: ${e.message}")
                null
            }
            val userId = remoteUser?.id ?: return null
            val all = withContext(Dispatchers.IO) { supabaseClient.fetchTaskSubmissions() }
            all.firstOrNull { it.taskId == taskId && it.studentId == userId }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchUserSubmissionForTaskFromSupabase failed for taskId=$taskId username=$username", e)
            null
        }
    }

    // Fetch all submissions for a student within a given course from Supabase (using userId)
    suspend fun fetchStudentSubmissionsForCourseFromSupabase(userId: Long, courseId: Long): List<TaskSubmission> {
        return try {
            if (!supabaseClient.isConfigured()) return emptyList()
            val all = withContext(Dispatchers.IO) { supabaseClient.fetchTaskSubmissions() }
            // Determine taskIds belonging to the course using Supabase (remote-authoritative)
            val remoteTopicIds = try {
                withContext(Dispatchers.IO) {
                    supabaseClient.fetchTopicsByCourse(courseId).map { it.id }
                }
            } catch (e: Exception) {
                Log.w("SyncRepository", "Failed to fetch topics from Supabase for courseId=$courseId", e)
                emptyList<Long>()
            }

            val remoteTaskIds = if (remoteTopicIds.isNotEmpty()) {
                try {
                    withContext(Dispatchers.IO) { supabaseClient.fetchTasksByTopicIds(remoteTopicIds).map { it.id }.toSet() }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Failed to fetch tasks from Supabase for topicIds=$remoteTopicIds", e)
                    emptySet<Long>()
                }
            } else emptySet()

            Log.d("SyncRepository", "fetchStudentSubmissionsForCourseFromSupabase: fetched allSubs=${all.size}, remoteTopics=${remoteTopicIds.size}, remoteTasks=${remoteTaskIds.size}")
            
            val filtered = all.filter { it.studentId == userId && remoteTaskIds.contains(it.taskId) }
            Log.d("SyncRepository", "fetchStudentSubmissionsForCourseFromSupabase: filteredSubs=${filtered.size}")
            filtered
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchStudentSubmissionsForCourseFromSupabase failed for userId=$userId courseId=$courseId", e)
            emptyList()
        }
    }

    // Fetch all submissions for a student within a given course from Supabase
    suspend fun fetchStudentSubmissionsForCourseFromSupabase(username: String, courseId: Long): List<TaskSubmission> {
        return try {
            if (!supabaseClient.isConfigured()) return emptyList()
            val all = withContext(Dispatchers.IO) { supabaseClient.fetchTaskSubmissions() }
            // Determine taskIds belonging to the course using Supabase (remote-authoritative)
            val remoteTopicIds = try {
                withContext(Dispatchers.IO) {
                    supabaseClient.fetchTopicsByCourse(courseId).map { it.id }
                }
            } catch (e: Exception) {
                Log.w("SyncRepository", "Failed to fetch topics from Supabase for courseId=$courseId", e)
                emptyList<Long>()
            }

            val remoteTaskIds = if (remoteTopicIds.isNotEmpty()) {
                try {
                    withContext(Dispatchers.IO) { supabaseClient.fetchTasksByTopicIds(remoteTopicIds).map { it.id }.toSet() }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Failed to fetch tasks from Supabase for topicIds=$remoteTopicIds", e)
                    emptySet<Long>()
                }
            } else emptySet()

            Log.d("SyncRepository", "fetchStudentSubmissionsForCourseFromSupabase: fetched allSubs=${all.size}, remoteTopics=${remoteTopicIds.size}, remoteTasks=${remoteTaskIds.size}")
            // Resolve username -> user id and filter by studentId
            val remoteUser = try {
                withContext(Dispatchers.IO) { supabaseClient.fetchUsuarioByUsername(username) }
            } catch (e: Exception) {
                Log.w("SyncRepository", "fetchUsuarioByUsername failed for $username: ${e.message}")
                null
            }
            val userId = remoteUser?.id
            val filtered = if (userId != null) all.filter { it.studentId == userId && remoteTaskIds.contains(it.taskId) } else emptyList()
            Log.d("SyncRepository", "fetchStudentSubmissionsForCourseFromSupabase: filteredSubs=${filtered.size}")
            filtered
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchStudentSubmissionsForCourseFromSupabase failed for username=$username courseId=$courseId", e)
            emptyList()
        }
    }

    // --- Sincronización de Firebase a Room para todas las entidades ---
    
    /**
     * Obtiene todos los progresos de un curso desde Supabase
     */
    suspend fun fetchProgresosByCursoFromSupabase(courseId: Long): List<com.example.tareamov.data.entity.ProgresoEstudiante> {
        return try {
            supabaseClient.fetchProgresosByCurso(courseId)
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error fetching progresos by curso from Supabase", e)
            emptyList()
        }
    }

    /**
     * Obtiene los progresos de un usuario (usuario_estudiante) desde Supabase
     */
    suspend fun fetchProgresosByUsuarioFromSupabase(usuarioId: Long): List<com.example.tareamov.data.entity.ProgresoEstudiante> {
        return try {
            withContext(Dispatchers.IO) { supabaseClient.fetchProgresosByUsuario(usuarioId) }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error fetching progresos by usuario from Supabase", e)
            emptyList()
        }
    }

    /**
     * Devuelve la lista de `Course` en los que el usuario está registrado según `progreso_estudiante`.
     */
    suspend fun fetchEnrolledCoursesForUserFromSupabase(usuarioId: Long): List<Course> {
        return try {
            if (!supabaseClient.isConfigured()) return emptyList()
            val progresos = fetchProgresosByUsuarioFromSupabase(usuarioId)
            val courses = mutableListOf<Course>()
            for (p in progresos) {
                try {
                    val cursoId = p.cursoId.takeIf { it != null } ?: continue
                    val course = fetchCourseById(cursoId!!)
                    if (course != null) courses.add(course)
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Failed to resolve course for progreso entry", e)
                }
            }
            courses
        } catch (e: Exception) {
            Log.e("SyncRepository", "fetchEnrolledCoursesForUserFromSupabase failed", e)
            emptyList()
        }
    }

    /**
     * Busca cursos en los que el usuario está inscrito y filtra por `query` (título, descripción o creador).
     * Realiza las llamadas necesarias a SupabaseClient/DB y devuelve Course coincidientes.
     */
    suspend fun searchEnrolledCoursesForUserFromSupabase(usuarioId: Long, query: String): List<Course> {
        try {
            if (!supabaseClient.isConfigured()) return emptyList()
            val lowerQ = query.trim().lowercase()
            if (lowerQ.isEmpty()) return fetchEnrolledCoursesForUserFromSupabase(usuarioId)

            val progresos = fetchProgresosByUsuarioFromSupabase(usuarioId)
            val results = mutableListOf<Course>()
            for (p in progresos) {
                try {
                    val cursoId = p.cursoId ?: continue
                    val course = fetchCourseById(cursoId)
                    if (course == null) continue
                    val matches = listOfNotNull(
                        course.title.lowercase(),
                        course.description?.lowercase()
                    ).any { it.contains(lowerQ) }
                    if (matches) results.add(course)
                } catch (e: Exception) {
                    Log.w("SyncRepository", "searchEnrolledCourses: failed resolving course for progreso", e)
                }
            }
            return results
        } catch (e: Exception) {
            Log.e("SyncRepository", "searchEnrolledCoursesForUserFromSupabase failed", e)
            return emptyList()
        }
    }

    companion object {
        // Lightweight wrapper so UI code can update a TaskSubmission remotely without
        // instantiating the full SyncRepository. Delegates to BackendApiService.
        suspend fun updateTaskSubmissionToSupabase(submission: TaskSubmission): Boolean {
            return try {
                if (submission.grade != null || !submission.feedback.isNullOrBlank()) {
                    BackendApiService.gradeSubmission(submission.id, submission.grade ?: 0f, submission.feedback).isSuccess
                } else {
                    val data = mapOf<String, Any?>(
                        "task_id" to submission.taskId,
                        "student_id" to submission.studentId,
                        "file_url" to submission.fileUri
                    )
                    BackendApiService.submitWork(data).isSuccess
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "updateTaskSubmissionToSupabase failed: ${e.message}")
                false
            }
        }
    }
    
    /**
     * Sincroniza un ProgresoEstudiante a Supabase
     */
    suspend fun syncProgresoToSupabase(progreso: com.example.tareamov.data.entity.ProgresoEstudiante): Boolean {
        return try {
            val data = mapOf<String, Any?>(
                "usuario_estudiante" to progreso.usuarioEstudiante,
                "curso_id" to progreso.cursoId,
                "tareas_completadas" to progreso.tareasCompletadas,
                "tareas_totales" to progreso.tareasTotales,
                "porcentaje_progreso" to progreso.porcentajeProgreso,
                "calificacion_ponderada" to progreso.calificacionPonderada,
                "estado" to progreso.estado,
                "ultima_calculada_en" to progreso.ultimaCalculadaEn
            )
            val result = BackendApiService.upsertProgress(data)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Progreso synced via backend for user=${progreso.usuarioEstudiante} course=${progreso.cursoId}")
                true
            } else {
                Log.e("SyncRepository", "syncProgresoToSupabase failed: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error syncing progreso via backend", e)
            false
        }
    }
    
    /**
     * Obtiene progreso desde Supabase
     */
    suspend fun fetchProgresoFromSupabase(username: String, courseId: Long): com.example.tareamov.data.entity.ProgresoEstudiante? {
        return try {
            if (!supabaseClient.isConfigured()) return null
            val userId = supabaseClient.getUserIdFromUsername(username) ?: return null
            supabaseClient.fetchProgresoEstudiante(userId, courseId)
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error fetching progreso from Supabase", e)
            null
        }
    }
    
    /**
     * Migración masiva: calcula y sube el progreso de todos los estudiantes en todos los cursos
     * Este método debe ser llamado una vez para migrar datos históricos
     */
    suspend fun migrateAllStudentProgressToSupabase(): Int = withContext(kotlinx.coroutines.Dispatchers.IO) {
        var migratedCount = 0
        try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "Supabase not configured, skipping migration")
                return@withContext 0
            }
            
            Log.d("SyncRepository", "Starting student progress migration...")
            
            // Obtener todos los cursos
            val courses = courseDao.getAllCoursesSync()
            Log.d("SyncRepository", "Found ${courses.size} courses to process")
            
            // Obtener todas las task submissions (para identificar estudiantes únicos)
            val allSubmissions = taskSubmissionDao.getAllSubmissionsSync()
            val uniqueStudents = allSubmissions.map { it.studentId }.distinct()
            Log.d("SyncRepository", "Found ${uniqueStudents.size} unique students with submissions")
            
            // Por cada combinación curso-estudiante, calcular y subir progreso
            for (course in courses) {
                val topics = topicDao.getTopicsByCourse(course.id)
                if (topics.isEmpty()) continue
                
                val topicIds = topics.map { it.id }
                val courseTasks = taskDao.getTasksByTopicIds(topicIds)
                if (courseTasks.isEmpty()) continue
                
                val courseTaskIds = courseTasks.map { it.id }
                
                // Filtrar estudiantes que tienen submissions en este curso
                val courseSubmissions = allSubmissions.filter { it.taskId in courseTaskIds }
                val courseStudents = courseSubmissions.map { it.studentId }.distinct()
                
                for (studentId in courseStudents) {
                    try {
                        val studentSubmissions = courseSubmissions.filter { it.studentId == studentId }
                        
                        // Calcular progreso
                        val completedTasks = courseTasks.count { task ->
                            studentSubmissions.any { it.taskId == task.id && it.grade != null }
                        }
                        
                        val totalTasks = courseTasks.size
                        val porcentaje = if (totalTasks > 0) {
                            (completedTasks.toFloat() / totalTasks.toFloat()) * 100f
                        } else {
                            0f
                        }
                        
                        val calificaciones = studentSubmissions.mapNotNull { it.grade }
                        val calificacionPonderada = if (calificaciones.isNotEmpty()) {
                            calificaciones.average().toFloat()
                        } else {
                            null
                        }
                        
                        // Use studentId directly
                        val userId = studentId
                        
                        val progreso = com.example.tareamov.data.entity.ProgresoEstudiante(
                            usuarioEstudiante = userId,
                            cursoId = course.id,
                            tareasCompletadas = completedTasks,
                            tareasTotales = totalTasks,
                            porcentajeProgreso = porcentaje,
                            calificacionPonderada = calificacionPonderada,
                            estado = if (calificacionPonderada != null && calificacionPonderada >= 6f) "Ganado" else "Perdido",
                            ultimaCalculadaEn = System.currentTimeMillis()
                        )
                        
                        // Guardar localmente
                        progresoEstudianteDao.insertProgreso(progreso)
                        
                        // Sincronizar con backend API
                        val data = mapOf<String, Any?>(
                            "usuario_estudiante" to progreso.usuarioEstudiante,
                            "curso_id" to progreso.cursoId,
                            "tareas_completadas" to progreso.tareasCompletadas,
                            "tareas_totales" to progreso.tareasTotales,
                            "porcentaje_progreso" to progreso.porcentajeProgreso,
                            "calificacion_ponderada" to progreso.calificacionPonderada,
                            "estado" to progreso.estado,
                            "ultima_calculada_en" to progreso.ultimaCalculadaEn
                        )
                        val success = BackendApiService.upsertProgress(data).isSuccess
                        if (success) {
                            migratedCount++
                            Log.d("SyncRepository", "Migrated progress: $userId in course ${course.title} (${course.id})")
                        } else {
                            Log.w("SyncRepository", "Failed to migrate progress: $userId in course ${course.id}")
                        }
                        
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Error migrating progress for $studentId in course ${course.id}", e)
                    }
                }
            }
            
            Log.d("SyncRepository", "Migration completed: $migratedCount progreso records migrated")
            return@withContext migratedCount
            
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error in migration process", e)
            return@withContext migratedCount
        }
    }

    /**
     * Crea automáticamente submissions con calificación 0 para todos los estudiantes
     * inscritos en el curso cuando se crea una nueva tarea.
     * Esto asegura que todas las tareas aparezcan en el cálculo de progreso desde el inicio.
     */
    suspend fun createDefaultSubmissionsForTask(taskId: Long, courseId: Long): Int = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            Log.d("SyncRepository", "Creating default submissions for task=$taskId in course=$courseId")
            
            // IMPORTANTE: Verificar que la tarea exista localmente antes de crear submissions
            // La tarea se guarda en Supabase pero puede no existir localmente, causando FOREIGN KEY constraint
            var localTask = taskDao.getTaskById(taskId)
            if (localTask == null) {
                Log.d("SyncRepository", "Task $taskId not found locally, attempting to sync from Supabase...")
                
                // Intentar obtener la tarea desde Supabase
                val remoteTask = fetchTaskByIdFromSupabase(taskId)
                if (remoteTask != null) {
                    try {
                        taskDao.insertTask(remoteTask)
                        localTask = remoteTask
                        Log.d("SyncRepository", "Successfully synced task $taskId from Supabase to local DB")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Failed to insert task $taskId locally", e)
                    }
                } else {
                    Log.e("SyncRepository", "Task $taskId not found in Supabase either, cannot create submissions")
                    return@withContext 0
                }
            }
            
            // Verificación final: si la tarea aún no existe localmente, no podemos continuar
            if (localTask == null) {
                Log.e("SyncRepository", "Task $taskId still not available locally after sync attempt")
                return@withContext 0
            }
            
            // Obtener todos los estudiantes inscritos en el curso
            val enrolledStudents = progresoEstudianteDao.getProgresosByCurso(courseId)
            Log.d("SyncRepository", "Found ${enrolledStudents.size} enrolled students")
            
            if (enrolledStudents.isEmpty()) {
                Log.w("SyncRepository", "No enrolled students found for course $courseId")
                return@withContext 0
            }
            
            var successCount = 0
            
            for (progreso in enrolledStudents) {
                val userId = progreso.usuarioEstudiante
                
                // Verificar si ya existe una submission para este estudiante y tarea
                val existingSubmission = taskSubmissionDao.getUserSubmissionForTask(taskId, userId)
                
                if (existingSubmission == null) {
                    // Crear submission con calificación 0 por defecto
                    val defaultSubmission = TaskSubmission(
                        id = 0,
                        taskId = taskId,
                        studentId = userId,
                        submissionDate = System.currentTimeMillis(),
                        fileUri = "", // Sin archivo adjunto inicialmente
                        fileName = "", // Sin nombre de archivo inicialmente
                        grade = 0f, // Calificación inicial de 0
                        feedback = "Tarea pendiente de entrega"
                    )
                    
                    try {
                        // Insertar en base de datos local
                        val localId = taskSubmissionDao.insertSubmission(defaultSubmission)
                        Log.d("SyncRepository", "Created local submission id=$localId for studentId=$userId")
                        
                        // Intentar sincronizar con backend API
                        try {
                            val data = mapOf<String, Any?>(
                                "task_id" to taskId,
                                "student_id" to userId,
                                "content" to "",
                                "file_url" to ""
                            )
                            val remoteResult = BackendApiService.submitWork(data)
                            if (remoteResult.isSuccess) {
                                Log.d("SyncRepository", "Synced submission via backend for studentId=$userId")
                            } else {
                                Log.w("SyncRepository", "Failed to sync submission via backend for studentId=$userId")
                            }
                        } catch (e: Exception) {
                            Log.w("SyncRepository", "Error syncing submission via backend", e)
                        }
                        
                        successCount++
                        
                        // Actualizar el progreso del estudiante
                        updateStudentProgressAfterTaskCreation(userId, courseId)
                        
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Error creating submission for studentId=$userId", e)
                    }
                } else {
                    Log.d("SyncRepository", "Submission already exists for studentId=$userId, task=$taskId")
                }
            }
            
            Log.i("SyncRepository", "Created $successCount default submissions for task $taskId")
            
            // Recalcular el progreso de todos los estudiantes en Supabase
            // Recalcular progreso vía backend
            try {
                val result = com.example.tareamov.service.BackendApiService.recalculateProgress(courseId)
                if (result.isSuccess) {
                    Log.i("SyncRepository", "Successfully recalculated progress via backend for course $courseId")
                } else {
                    Log.w("SyncRepository", "Failed to recalculate progress via backend: ${result.errorMessage()}")
                }
            } catch (e: Exception) {
                Log.w("SyncRepository", "Error recalculating progress via backend", e)
            }
            
            return@withContext successCount
            
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error creating default submissions for task", e)
            return@withContext 0
        }
    }
    
    /**
     * Actualiza el progreso del estudiante después de crear una nueva tarea.
     * Recalcula el promedio, tareas totales, completadas y porcentaje de progreso.
     */
    private suspend fun updateStudentProgressAfterTaskCreation(userId: Long, courseId: Long) {
        try {
            val progreso = progresoEstudianteDao.getProgreso(userId, courseId)
            if (progreso != null) {
                // Obtener todas las submissions del estudiante en el curso
                val submissions = taskSubmissionDao.getStudentSubmissionsForCourse(userId, courseId)
                
                // Obtener todas las tareas del curso (a través de topics)
                val topics = topicDao.getTopicsByCourse(courseId)
                val topicIds = topics.map { it.id }
                val allTasks = if (topicIds.isNotEmpty()) {
                    taskDao.getTasksByTopicIds(topicIds)
                } else {
                    emptyList()
                }
                
                // Calcular tareas totales
                val tareasTotales = allTasks.size
                
                // Calcular tareas completadas (submissions con nota registrada)
                val tareasCompletadas = submissions.count { it.grade != null }
                
                // Calcular porcentaje de progreso
                val porcentajeProgreso = if (tareasTotales > 0) {
                    (tareasCompletadas.toFloat() / tareasTotales.toFloat()) * 100f
                } else {
                    0f
                }
                
                // Calcular nuevo promedio incluyendo todas las tareas (incluso las de calificación 0)
                val totalGrade = submissions.mapNotNull { it.grade }.sum()
                val taskCount = submissions.size
                val newPromedio = if (taskCount > 0) totalGrade / taskCount else 0f
                
                // Actualizar progreso con todos los campos
                val updatedProgreso = progreso.copy(
                    tareasTotales = tareasTotales,
                    tareasCompletadas = tareasCompletadas,
                    porcentajeProgreso = porcentajeProgreso,
                    promedio = newPromedio,
                    calificacionPonderada = newPromedio,
                    ultimaCalculadaEn = System.currentTimeMillis()
                )
                
                progresoEstudianteDao.updateProgreso(updatedProgreso)
                
                // Sincronizar con Supabase
                syncProgresoToSupabase(updatedProgreso)
                
                Log.d("SyncRepository", "Updated progress for studentId=$userId: total=$tareasTotales, completed=$tareasCompletadas, progress=$porcentajeProgreso%, avg=$newPromedio")
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error updating student progress", e)
        }
    }

    /**
     * Recalcula y sincroniza el progreso de TODOS los estudiantes inscritos en un curso.
     * Este método debe ser llamado después de cualquier CRUD en tareas.
     * 
     * @param courseId ID del curso
     * @return número de estudiantes actualizados
     */
    suspend fun recalculateAllStudentProgressForCourse(courseId: Long): Int = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            Log.d("SyncRepository", "🔄 Recalculating progress for all students in course $courseId")
            
            // Obtener todos los estudiantes inscritos en el curso
            val enrolledStudents = progresoEstudianteDao.getProgresosByCurso(courseId)
            if (enrolledStudents.isEmpty()) {
                Log.w("SyncRepository", "No students enrolled in course $courseId")
                return@withContext 0
            }
            
            // Obtener todas las tareas del curso desde Supabase
            val topics = try {
                supabaseClient.fetchTopicsByCourse(courseId)
            } catch (e: Exception) {
                Log.e("SyncRepository", "Error fetching topics", e)
                emptyList()
            }
            
            val topicIds = topics.map { it.id }
            val allTasks = if (topicIds.isNotEmpty()) {
                try {
                    supabaseClient.fetchTasksByTopicIds(topicIds)
                } catch (e: Exception) {
                    Log.e("SyncRepository", "Error fetching tasks", e)
                    emptyList()
                }
            } else {
                emptyList()
            }
            
            val tareasTotales = allTasks.size
            if (tareasTotales == 0) {
                Log.w("SyncRepository", "No tasks found in course $courseId")
                return@withContext 0
            }
            
            Log.d("SyncRepository", "📚 Found $tareasTotales tasks in course")
            
            // Obtener todas las submissions del curso desde Supabase
            val allSubmissions = try {
                val submissions = supabaseClient.fetchTaskSubmissions()
                val taskIds = allTasks.map { it.id }.toSet()
                submissions.filter { it.taskId in taskIds }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Error fetching submissions", e)
                emptyList()
            }
            
            var updatedCount = 0
            
            // Recalcular para cada estudiante
            for (student in enrolledStudents) {
                try {
                    val userId = student.usuarioEstudiante
                    
                    // Filtrar submissions del estudiante
                    val studentSubmissions = allSubmissions.filter { 
                        it.studentId == userId
                    }
                    
                    // Calcular métricas
                    val tareasCompletadas = studentSubmissions.count { it.grade != null }
                    val porcentajeProgreso = if (tareasTotales > 0) {
                        (tareasCompletadas.toFloat() / tareasTotales.toFloat()) * 100f
                    } else {
                        0f
                    }
                    
                    // IMPORTANTE: Calcular promedio considerando TODAS las tareas
                    // Las tareas sin submission cuentan como 0
                    val submissionMap = studentSubmissions.associateBy { it.taskId }
                    var totalGrade = 0f
                    for (task in allTasks) {
                        val grade = submissionMap[task.id]?.grade ?: 0f
                        totalGrade += grade
                    }
                    val promedio = totalGrade / tareasTotales
                    
                    Log.d("SyncRepository", "📊 StudentId=$userId: total=$tareasTotales, completed=$tareasCompletadas, avg=$promedio")
                    
                    // Actualizar progreso
                    val updatedProgreso = student.copy(
                        tareasTotales = tareasTotales,
                        tareasCompletadas = tareasCompletadas,
                        porcentajeProgreso = porcentajeProgreso,
                        promedio = promedio,
                        calificacionPonderada = promedio, // Esto determina el estado
                        ultimaCalculadaEn = System.currentTimeMillis()
                    )
                    
                    // Guardar localmente
                    progresoEstudianteDao.updateProgreso(updatedProgreso)
                    
                    // Sincronizar a Supabase
                    val synced = try {
                        syncProgresoToSupabase(updatedProgreso)
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Error syncing progress via backend for studentId=$userId", e)
                        false
                    }
                    
                    if (synced) {
                        updatedCount++
                        Log.d("SyncRepository", "✅ Updated progress for studentId=$userId")
                    }
                } catch (e: Exception) {
                    Log.e("SyncRepository", "Error updating progress for student=${student.usuarioEstudiante}", e)
                }
            }
            
            Log.i("SyncRepository", "✅ Updated progress for $updatedCount/${enrolledStudents.size} students")
            return@withContext updatedCount
            
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error recalculating student progress", e)
            return@withContext 0
        }
    }

    /**
     * Elimina una tarea y recalcula el progreso de todos los estudiantes inscritos en el curso.
     * Este método debe ser llamado cuando se elimina una tarea para mantener el progreso actualizado.
     * 
     * @param taskId ID de la tarea a eliminar
     * @return true si la eliminación fue exitosa
     */
    suspend fun deleteTaskAndUpdateProgress(taskId: Long): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            // Obtener información de la tarea antes de eliminarla
            val task = taskDao.getTaskById(taskId)
            if (task == null) {
                Log.w("SyncRepository", "Task not found: taskId=$taskId")
                return@withContext false
            }
            
            val topic = topicDao.getTopicById(task.topicId)
            if (topic == null) {
                Log.w("SyncRepository", "Topic not found for task: taskId=$taskId, topicId=${task.topicId}")
                return@withContext false
            }
            
            val courseId = topic.courseId
            
            // Obtener lista de estudiantes inscritos antes de eliminar la tarea
            val enrolledStudents = progresoEstudianteDao.getProgresosByCurso(courseId)
            val studentUserIds = enrolledStudents.map { it.usuarioEstudiante }
            
            Log.d("SyncRepository", "Deleting task $taskId from course $courseId. Will update progress for ${studentUserIds.size} students")
            
            // Eliminar tarea de la base de datos local (CASCADE debería eliminar submissions relacionadas)
            taskDao.deleteTask(taskId)
            
            // Eliminar tarea del backend (cascade en servidor)
            try {
                val deleteResult = BackendApiService.deleteTask(taskId)
                if (deleteResult.isSuccess) {
                    Log.d("SyncRepository", "Task $taskId deleted from backend")
                } else {
                    Log.w("SyncRepository", "Failed to delete task $taskId from backend: ${deleteResult.errorMessage()}")
                }
            } catch (e: Exception) {
                Log.w("SyncRepository", "Error deleting task from backend", e)
            }
            
            // Recalcular y sincronizar progreso para cada estudiante inscrito
            for (userId in studentUserIds) {
                try {
                    // Obtener todas las submissions del estudiante en el curso (ya no incluye la tarea eliminada)
                    val submissions = taskSubmissionDao.getStudentSubmissionsForCourse(userId, courseId)
                    
                    // Obtener todas las tareas restantes del curso
                    val topics = topicDao.getTopicsByCourse(courseId)
                    val topicIds = topics.map { it.id }
                    val allTasks = if (topicIds.isNotEmpty()) {
                        taskDao.getTasksByTopicIds(topicIds)
                    } else {
                        emptyList()
                    }
                    
                    // Calcular métricas actualizadas
                    val tareasTotales = allTasks.size
                    val tareasCompletadas = submissions.count { it.grade != null }
                    val porcentajeProgreso = if (tareasTotales > 0) {
                        (tareasCompletadas.toFloat() / tareasTotales.toFloat()) * 100f
                    } else {
                        0f
                    }
                    
                    val totalGrade = submissions.mapNotNull { it.grade }.sum()
                    val taskCount = submissions.size
                    val promedio = if (taskCount > 0) totalGrade / taskCount else 0f
                    
                    // Actualizar progreso local
                    val existingProgreso = progresoEstudianteDao.getProgreso(userId, courseId)
                    if (existingProgreso != null) {
                        val updatedProgreso = existingProgreso.copy(
                            tareasTotales = tareasTotales,
                            tareasCompletadas = tareasCompletadas,
                            porcentajeProgreso = porcentajeProgreso,
                            promedio = promedio,
                            calificacionPonderada = promedio,
                            ultimaCalculadaEn = System.currentTimeMillis()
                        )
                        
                        progresoEstudianteDao.updateProgreso(updatedProgreso)
                        
                        // Sincronizar con Supabase
                        syncProgresoToSupabase(updatedProgreso)
                        
                        Log.d("SyncRepository", "Updated progress after task deletion for studentId=$userId: total=$tareasTotales, completed=$tareasCompletadas, progress=$porcentajeProgreso%, avg=$promedio")
                    }
                } catch (e: Exception) {
                    Log.e("SyncRepository", "Error updating progress for studentId=$userId after task deletion", e)
                }
            }
            
            Log.i("SyncRepository", "Successfully deleted task $taskId and updated progress for ${studentUserIds.size} students")
            return@withContext true
            
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error deleting task and updating progress", e)
            return@withContext false
        }
    }

    /**
     * Migration to disable problematic database triggers.
     * NOTE: DDL migrations should be managed server-side, not from the client.
     * This method is kept for backward compatibility but is now a no-op.
     */
    @Deprecated("Database migrations should be managed server-side")
    suspend fun applyTriggerDisableMigration(): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        Log.i("SyncRepository", "⚠️ applyTriggerDisableMigration is deprecated — DDL changes are managed server-side")
        true
    }

    suspend fun getSubmissionAndContextForTask(taskId: Long, username: String): Pair<TaskSubmission?, com.example.tareamov.data.entity.FileContext?> {
        return withContext(Dispatchers.IO) {
            // Get userId from username
            val userId = supabaseClient.getUserIdFromUsername(username) ?: return@withContext Pair(null, null)
            
            // Try local first
            var submission = taskSubmissionDao.getSubmissionsByTask(taskId).firstOrNull { it.studentId == userId }
            var fileContext: com.example.tareamov.data.entity.FileContext? = null
            
            if (submission == null) {
                // Try remote
                submission = supabaseClient.fetchTaskSubmissionByTaskId(taskId, userId)
            }
            
            if (submission != null) {
                fileContext = fileContextDao.getFileContextBySubmission(submission.id)
                if (fileContext == null) {
                    // Try remote
                    fileContext = supabaseClient.fetchFileContextBySubmissionId(submission.id)
                }
            }
            
            Pair(submission, fileContext)
        }
    }

    // --- Local Database Access Wrappers ---

    suspend fun getUsuarioByUsernameLocal(username: String): Usuario? {
        return withContext(Dispatchers.IO) {
            usuarioDao.getUsuarioByUsername(username)
        }
    }

    suspend fun isSubscribedLocal(subscriberId: Long, creatorId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            subscriptionDao.isSubscribed(subscriberId, creatorId)
        }
    }

    suspend fun insertSubscriptionLocal(subscription: Subscription) {
        withContext(Dispatchers.IO) {
            subscriptionDao.insertSubscription(subscription)
            // Also try to sync to remote
            insertSubscriptionRemote(subscription)
        }
    }

    suspend fun deleteSubscriptionLocal(subscriberId: Long, creatorId: Long) {
        withContext(Dispatchers.IO) {
            subscriptionDao.deleteSubscription(subscriberId, creatorId)
            // Also try to sync to remote
            deleteSubscriptionRemote(subscriberId, creatorId)
        }
    }

    // Fetch courses created by user that have at least one graded submission (grade > 0)
    suspend fun fetchCoursesWithGradedSubmissions(username: String): List<Course> {
        // 1. Fetch all courses by creator
        val courses = fetchCoursesByCreatorFromSupabase(username)
        if (courses.isEmpty()) return emptyList()
        
        // 2. Filter courses that have graded submissions
        // This is N+1 but unavoidable without complex backend RPC or view
        val result = mutableListOf<Course>()
        for (course in courses) {
            val submissions = supabaseClient.fetchGradedSubmissionsForCourse(course.id)
            if (submissions.isNotEmpty()) {
                result.add(course)
            }
        }
        return result
    }

    // Fetch graded submissions for a course with student info
    suspend fun fetchGradedSubmissionsWithDetails(courseId: Long): List<Map<String, Any>> {
        return supabaseClient.fetchGradedSubmissionsForCourse(courseId)
    }
    
    // Fetch ALL submissions for a course (both graded and ungraded)
    // REMOVED: conflicting overload. Use the one with fetchCourseSubmissionsWithUsernames logic instead.
    // suspend fun fetchAllSubmissionsForCourse(courseId: Long): List<Map<String, Any>> {
    //    return supabaseClient.fetchAllSubmissionsForCourse(courseId)
    // }

    /**
     * Toggle like on a video comment.
     * Uses the normalized video_comment_likes table via SupabaseClient REST API.
     * Returns Pair(isNowLiked, newLikeCount)
     */
    suspend fun toggleVideoCommentLike(commentId: Long, videoId: Long, userId: Long, authorId: Long): Pair<Boolean, Int> {
        return withContext(Dispatchers.IO) {
            try {
                val result = BackendApiService.toggleLike("video_comment", commentId)
                val isLiked = result.isSuccess && (result.getOrNull()?.get("liked")?.asBoolean ?: false)
                val newCount = result.getOrNull()?.get("likeCount")?.asInt ?: 0
                
                // Send notification if user liked (not unliked) and it's not their own comment
                if (isLiked && authorId != userId) {
                    try {
                        val localUser = usuarioDao.getUsuarioById(userId)
                        val localVideo = videoDao.getVideoById(videoId)
                        
                        val notification = Notification(
                            userId = authorId,
                            type = Notification.TYPE_LIKE,
                            title = "Me gusta en tu comentario",
                            message = "${localUser?.usuario ?: "Alguien"} reaccionó a tu comentario",
                            senderUsername = localUser?.usuario,
                            senderAvatarUrl = localUser?.avatar,
                            thumbnailUrl = localVideo?.thumbnailUri ?: localVideo?.videoUriString,
                            relatedId = videoId,
                            metadata = "{\"comment_id\": $commentId}"
                        )
                        BackendApiService.sendNotification(userId = notification.userId, title = notification.title, message = notification.message, type = notification.type, relatedId = notification.relatedId, senderUsername = notification.senderUsername, thumbnailUrl = notification.thumbnailUrl, metadata = notification.metadata, senderAvatarUrl = notification.senderAvatarUrl)
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Error sending like notification", e)
                    }
                }
                return@withContext Pair(isLiked, newCount)
            } catch (e: Exception) {
                Log.e("SyncRepository", "toggleVideoCommentLike failed", e)
                Pair(false, 0)
            }
        }
    }
    
    /**
     * Like a video comment (legacy method, use toggleVideoCommentLike for toggle behavior)
     */
    suspend fun likeVideoComment(commentId: Long, videoId: Long, userId: Long, authorId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val result = BackendApiService.toggleLike("video_comment", commentId)
                val success = result.isSuccess
                
                if (success && authorId != userId) {
                    try {
                        val localUser = usuarioDao.getUsuarioById(userId)
                        val localVideo = videoDao.getVideoById(videoId)
                        
                        val notification = Notification(
                            userId = authorId,
                            type = Notification.TYPE_LIKE,
                            title = "Me gusta en tu comentario",
                            message = "${localUser?.usuario ?: "Alguien"} reaccionó a tu comentario",
                            senderUsername = localUser?.usuario,
                            senderAvatarUrl = localUser?.avatar,
                            thumbnailUrl = localVideo?.thumbnailUri ?: localVideo?.videoUriString,
                            relatedId = videoId,
                            metadata = "{\"comment_id\": $commentId}"
                        )
                        BackendApiService.sendNotification(userId = notification.userId, title = notification.title, message = notification.message, type = notification.type, relatedId = notification.relatedId, senderUsername = notification.senderUsername, thumbnailUrl = notification.thumbnailUrl, metadata = notification.metadata, senderAvatarUrl = notification.senderAvatarUrl)
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Error sending like notification", e)
                    }
                }
                return@withContext success
            } catch (e: Exception) {
                Log.e("SyncRepository", "likeVideoComment failed", e)
                false
            }
        }
    }
    
    /**
     * Unlike a video comment via backend API
     */
    suspend fun unlikeVideoComment(commentId: Long, userId: Long): Boolean {
        return try {
            val result = BackendApiService.toggleLike("video_comment", commentId)
            result.isSuccess
        } catch (e: Exception) {
            Log.e("SyncRepository", "unlikeVideoComment failed", e)
            false
        }
    }
    
    /**
     * Get like count for a video comment
     */
    suspend fun getCommentLikeCount(commentId: Long): Int {
        return withContext(Dispatchers.IO) {
            supabaseClient.getVideoCommentLikeCount(commentId)
        }
    }
    
    /**
     * Check if user has liked a video comment
     */
    suspend fun hasUserLikedComment(commentId: Long, userId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            supabaseClient.hasUserLikedVideoComment(commentId, userId)
        }
    }
    
    /**
     * Get like counts for multiple comments at once
     */
    suspend fun getCommentLikeCounts(commentIds: List<Long>): Map<Long, Int> {
        return withContext(Dispatchers.IO) {
            supabaseClient.getVideoCommentLikeCounts(commentIds)
        }
    }
    
    /**
     * Get which comments the user has liked from a list
     */
    suspend fun getUserLikedComments(userId: Long, commentIds: List<Long>): Set<Long> {
        return withContext(Dispatchers.IO) {
            supabaseClient.getUserLikedComments(userId, commentIds)
        }
    }

    /**
     * Check if a user has a specific role via backend API
     */
    suspend fun hasUserRole(userId: Long, roleId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = com.example.tareamov.service.BackendApiService.getUserRoles(userId)
            if (result.isSuccess) {
                val roleIds = result.getOrNull() ?: emptyList()
                return@withContext roleIds.contains(roleId.toLong())
            }
            false
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error checking user role $roleId", e)
            false
        }
    }

    /**
     * Get all roles for a user via backend API
     */
    suspend fun getUserRoles(userId: Long): List<Int> = withContext(Dispatchers.IO) {
        try {
            val result = com.example.tareamov.service.BackendApiService.getUserRoles(userId)
            if (result.isSuccess) {
                return@withContext (result.getOrNull() ?: emptyList()).map { it.toInt() }
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error getting user roles", e)
            emptyList()
        }
    }

    // ========== VIDEO LIKES SYNC OPERATIONS (Using Polymorphic Likes Table) ==========
    
    /**
     * Get like count for a video (from Supabase polymorphic likes table)
     */
    suspend fun getVideoLikeCount(videoId: Long): Int = withContext(Dispatchers.IO) {
        try {
            // Get count directly from Supabase polymorphic likes table
            val remoteCount = supabaseClient.getVideoLikeCount(videoId)
            return@withContext remoteCount ?: 0
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error getting like count for video $videoId", e)
            0
        }
    }
    
    /**
     * Check if user has liked a video (using polymorphic likes table)
     */
    suspend fun hasUserLikedVideo(videoId: Long, usuarioId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SyncRepository", "hasUserLikedVideo: video=$videoId, user=$usuarioId")
            
            // Check Supabase directly (polymorphic likes table)
            val remoteLiked = supabaseClient.hasUserLikedVideo(videoId, usuarioId)
            Log.d("SyncRepository", "Supabase check: remoteLiked=$remoteLiked")
            
            return@withContext remoteLiked
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error checking user like status", e)
            false
        }
    }
    
    /**
     * Toggle like on a video (using polymorphic likes table)
     */
    suspend fun toggleVideoLike(videoId: Long, usuarioId: Long, isLiked: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = BackendApiService.toggleLike("video", videoId)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Video like toggled via backend for video $videoId")
                true
            } else {
                Log.w("SyncRepository", "toggleVideoLike failed: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error toggling like for video $videoId", e)
            false
        }
    }
    
    /**
     * Sync user video likes for a specific user (from polymorphic likes table)
     * Returns list of video IDs that user has liked
     */
    suspend fun syncUserVideoLikesFromSupabase(userId: Long): List<Long> = withContext(Dispatchers.IO) {
        try {
            Log.d("SyncRepository", "Starting sync of user video likes for user $userId")
            val remoteUserLikes = supabaseClient.fetchUserVideoLikes(userId)
            Log.d("SyncRepository", "Fetched ${remoteUserLikes.size} video likes from Supabase")
            
            // Return list of video IDs the user has liked
            val likedVideoIds = remoteUserLikes.map { it.entityId }
            Log.d("SyncRepository", "User $userId has liked videos: $likedVideoIds")
            return@withContext likedVideoIds
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error syncing user video likes from Supabase", e)
            emptyList()
        }
    }
    
    // ========== VIDEO COMMENTS SYNC OPERATIONS ==========
    
    /**
     * Add a comment to a video
     */
    suspend fun addVideoComment(videoId: Long, usuarioId: Long, comment: String, parentId: Long? = null): Long? = withContext(Dispatchers.IO) {
        try {
            // Ensure video exists locally to satisfy FK constraint
            var localVideo = videoDao.getVideoById(videoId)
            if (localVideo == null) {
                val remoteVideo = supabaseClient.fetchVideoById(videoId)
                if (remoteVideo != null) {
                    // Sanitize remote video to prevent NPE on non-null fields
                    val safeVideo = remoteVideo.copy(
                        username = remoteVideo.username ?: "Unknown",
                        description = remoteVideo.description ?: "",
                        title = remoteVideo.title ?: "Untitled Video"
                    )
                    videoDao.insertVideo(safeVideo)
                    localVideo = safeVideo
                } else {
                    Log.e("SyncRepository", "Cannot add comment: Video $videoId not found locally or remotely")
                    return@withContext null
                }
            }

            // Ensure user exists locally to satisfy FK constraint
            var localUser = usuarioDao.getUsuarioById(usuarioId)
            if (localUser == null) {
                val remoteUser = supabaseClient.fetchUsuarioById(usuarioId)
                if (remoteUser != null) {
                    usuarioDao.insertUsuario(remoteUser)
                    localUser = remoteUser
                } else {
                    Log.e("SyncRepository", "Cannot add comment: User $usuarioId not found locally or remotely")
                    return@withContext null
                }
            }

            // Add to backend API first
            val commentResult = BackendApiService.createComment(videoId, comment, parentId)
            val remoteId = if (commentResult.isSuccess) commentResult.getOrNull()?.id else null
            
            if (remoteId != null) {
                // Add to local with the remote ID
                val localComment = VideoComment(
                    id = remoteId,
                    videoId = videoId,
                    usuarioId = usuarioId,
                    comment = comment,
                    parentId = parentId
                )
                videoCommentDao?.insertComment(localComment)

                // --- NOTIFICATION LOGIC ---
                try {
                    // 1. Notify Video Owner (for ALL comments - top-level and replies)
                    Log.d("SyncRepository", "🔔 Comment notification check: parentId=$parentId, localVideo=${localVideo != null}, username=${localVideo?.username}, remoteId=${localVideo?.remoteId}, courseId=${localVideo?.courseId}")
                    
                    if (localVideo != null) {
                        // Strategy to find video owner:
                        // 1. Try remoteId from video (creator's user ID)
                        // 2. Try courseId -> course -> creator_user_id  
                        // 3. Fallback to username lookup
                        
                        var ownerId: Long? = localVideo.remoteId
                        var owner: Usuario? = null
                        
                        // Strategy 1: Use remoteId directly (creator's user ID stored in video)
                        if (ownerId != null && ownerId > 0) {
                            owner = usuarioDao.getUsuarioById(ownerId) ?: supabaseClient.fetchUsuarioById(ownerId)
                            Log.d("SyncRepository", "🔍 Strategy 1 - Found owner by video.remoteId: ${owner?.usuario}, id=$ownerId")
                        }
                        
                        // Strategy 2: Get owner from course's creator_user_id
                        val courseId = localVideo.courseId
                        if (owner == null && courseId != null && courseId > 0) {
                            Log.d("SyncRepository", "🔍 Strategy 2 - Looking for course $courseId to get creator_user_id")
                            try {
                                // Fetch course from Supabase to get creator_user_id
                                val course = supabaseClient.fetchCourseById(courseId)
                                if (course != null) {
                                    val creatorUserId = course.creatorUserId
                                    Log.d("SyncRepository", "📚 Course found: creator_user_id=$creatorUserId")
                                    if (creatorUserId > 0) {
                                        ownerId = creatorUserId
                                        owner = usuarioDao.getUsuarioById(creatorUserId) ?: supabaseClient.fetchUsuarioById(creatorUserId)
                                        Log.d("SyncRepository", "🔍 Strategy 2 - Found owner by course.creator_user_id: ${owner?.usuario}, id=$ownerId")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("SyncRepository", "⚠️ Strategy 2 failed: ${e.message}")
                            }
                        }
                        
                        // Strategy 3: Fallback to username lookup
                        if (owner == null && !localVideo.username.isNullOrEmpty() && localVideo.username != "Unknown" && localVideo.username != "unknown") {
                            val ownerUsername = localVideo.username
                            Log.d("SyncRepository", "🔍 Strategy 3 - Looking for video owner by username: $ownerUsername")
                            owner = usuarioDao.getUsuarioByUsername(ownerUsername) 
                                ?: supabaseClient.fetchUsuarioByUsername(ownerUsername)
                            ownerId = owner?.id
                            Log.d("SyncRepository", "🔍 Strategy 3 - Found owner by username: ${owner?.usuario}, id=$ownerId")
                        }
                        
                        Log.d("SyncRepository", "👤 Final owner result: found=${owner != null}, ownerId=$ownerId, commenterId=$usuarioId, sameUser=${ownerId == usuarioId}")
                            
                        if (owner != null && ownerId != null && ownerId != usuarioId) {
                            // Determine message based on whether it's a reply or top-level comment
                            val notificationMessage = if (parentId != null) {
                                "${localUser?.usuario ?: "Alguien"} respondió un comentario en tu video"
                            } else {
                                "${localUser?.usuario ?: "Alguien"} comentó tu video"
                            }
                            
                            val notification = Notification(
                                userId = ownerId,
                                type = Notification.TYPE_COMMENT,
                                title = "Nuevo comentario",
                                message = notificationMessage,
                                senderUsername = localUser?.usuario,
                                senderAvatarUrl = localUser?.avatar,
                                thumbnailUrl = localVideo.thumbnailUri ?: localVideo.videoUriString,
                                relatedId = videoId,
                                metadata = "{\"comment_id\": $remoteId}"
                            )
                            Log.d("SyncRepository", "📬 Creating notification for video owner $ownerId with comment_id=$remoteId (parentId=$parentId)")
                            val insertResult = BackendApiService.sendNotification(userId = notification.userId, title = notification.title, message = notification.message, type = notification.type, relatedId = notification.relatedId, senderUsername = notification.senderUsername, thumbnailUrl = notification.thumbnailUrl, metadata = notification.metadata, senderAvatarUrl = notification.senderAvatarUrl)
                            Log.d("SyncRepository", "📬 Notification insert result: $insertResult")
                        } else {
                            Log.d("SyncRepository", "⚠️ NOT notifying video owner: owner=${owner != null}, ownerId=$ownerId, sameUser=${ownerId == usuarioId}")
                        }
                        
                        // 2. Notify parent comment author if this is a reply
                        if (parentId != null && parentId > 0) {
                            Log.d("SyncRepository", "📝 This is a reply to comment $parentId, notifying parent author")
                            val parentComment = videoCommentDao?.getCommentById(parentId) 
                                ?: supabaseClient.getVideoCommentById(parentId)
                            
                            if (parentComment != null && parentComment.usuarioId != usuarioId && parentComment.usuarioId != ownerId) {
                                val parentAuthor = usuarioDao.getUsuarioById(parentComment.usuarioId)
                                    ?: supabaseClient.fetchUsuarioById(parentComment.usuarioId)
                                
                                if (parentAuthor != null) {
                                    val replyNotification = Notification(
                                        userId = parentComment.usuarioId,
                                        type = Notification.TYPE_COMMENT,
                                        title = "Respuesta a tu comentario",
                                        message = "${localUser?.usuario ?: "Alguien"} respondió a tu comentario",
                                        senderUsername = localUser?.usuario,
                                        senderAvatarUrl = localUser?.avatar,
                                        thumbnailUrl = localVideo.thumbnailUri ?: localVideo.videoUriString,
                                        relatedId = videoId,
                                        metadata = "{\"comment_id\": $remoteId}"
                                    )
                                    Log.d("SyncRepository", "📬 Creating reply notification for comment author ${parentComment.usuarioId}")
                                    BackendApiService.sendNotification(userId = replyNotification.userId, title = replyNotification.title, message = replyNotification.message, type = replyNotification.type, relatedId = replyNotification.relatedId, senderUsername = replyNotification.senderUsername, thumbnailUrl = replyNotification.thumbnailUrl, metadata = replyNotification.metadata, senderAvatarUrl = replyNotification.senderAvatarUrl)
                                }
                            }
                        }
                    }

                    // 2. Notify Mentions (@username)
                    val mentions = Regex("@(\\w+)").findAll(comment).map { it.groupValues[1] }.toList()
                    if (mentions.isNotEmpty()) {
                        for (username in mentions) {
                            val mentionedUser = usuarioDao.getUsuarioByUsername(username) 
                                ?: supabaseClient.fetchUsuarioByUsername(username)
                                
                            if (mentionedUser != null && mentionedUser.id != usuarioId) {
                                 val notification = Notification(
                                     userId = mentionedUser.id,
                                     type = Notification.TYPE_COMMENT, // Or create TYPE_MENTION if desired, reusing COMMENT for now
                                     title = "Mención en comentario",
                                     message = "${localUser?.usuario ?: "Alguien"} te mencionó en un comentario",
                                     senderUsername = localUser?.usuario,
                                     senderAvatarUrl = localUser?.avatar,
                                     thumbnailUrl = localVideo?.thumbnailUri ?: localVideo?.videoUriString,
                                     relatedId = videoId,
                                     metadata = "{\"comment_id\": $remoteId}" // Include comment_id for navigation
                                 )
                                 Log.d("SyncRepository", "📬 Creating mention notification with comment_id=$remoteId for @$username")
                                 BackendApiService.sendNotification(userId = notification.userId, title = notification.title, message = notification.message, type = notification.type, relatedId = notification.relatedId, senderUsername = notification.senderUsername, thumbnailUrl = notification.thumbnailUrl, metadata = notification.metadata, senderAvatarUrl = notification.senderAvatarUrl)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SyncRepository", "Error sending comment notifications", e)
                }
            }
            
            return@withContext remoteId
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error adding comment to video $videoId", e)
            null
        }
    }
    
    /**
     * Get comments for a video
     */
    suspend fun getVideoComments(videoId: Long): List<VideoComment> = withContext(Dispatchers.IO) {
        try {
            // Try from Supabase first for fresh data
            val remoteComments = supabaseClient.getVideoComments(videoId)
            
            if (remoteComments.isNotEmpty()) {
                // Ensure video exists locally
                val localVideo = videoDao.getVideoById(videoId)
                if (localVideo == null) {
                    val remoteVideo = supabaseClient.fetchVideoById(videoId)
                    if (remoteVideo != null) {
                        // Sanitize remote video to prevent NPE on non-null fields
                        val safeVideo = remoteVideo.copy(
                            username = remoteVideo.username ?: "Unknown",
                            description = remoteVideo.description ?: "",
                            title = remoteVideo.title ?: "Untitled Video"
                        )
                        videoDao.insertVideo(safeVideo)
                    }
                }

                // Ensure all users exist locally
                val userIds = remoteComments.map { it.usuarioId }.distinct()
                userIds.forEach { uid ->
                    if (usuarioDao.getUsuarioById(uid) == null) {
                         val u = supabaseClient.fetchUsuarioById(uid)
                         if (u != null) usuarioDao.insertUsuario(u)
                    }
                }

                // Update local cache
                videoCommentDao?.insertAllComments(remoteComments)
                return@withContext remoteComments
            }
            
            // Fallback to local
            return@withContext videoCommentDao?.getCommentsByVideoId(videoId) ?: emptyList()
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error getting comments for video $videoId", e)
            videoCommentDao?.getCommentsByVideoId(videoId) ?: emptyList()
        }
    }
    
    /**
     * Get comment count for a video
     */
    suspend fun getVideoCommentCount(videoId: Long): Int = withContext(Dispatchers.IO) {
        try {
            // Try from Supabase first
            val remoteCount = supabaseClient.getVideoCommentCount(videoId)
            if (remoteCount > 0) {
                return@withContext remoteCount
            }
            
            // Fallback to local
            return@withContext videoCommentDao?.getCommentCount(videoId) ?: 0
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error getting comment count for video $videoId", e)
            videoCommentDao?.getCommentCount(videoId) ?: 0
        }
    }
    
    /**
     * Delete a comment
     */
    suspend fun deleteVideoComment(commentId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = BackendApiService.deleteComment(commentId)
            if (result.isSuccess) {
                // Delete from local
                videoCommentDao?.deleteCommentById(commentId)
                Log.d("SyncRepository", "Comment $commentId deleted via backend")
                true
            } else {
                Log.w("SyncRepository", "deleteVideoComment failed: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error deleting comment $commentId", e)
            false
        }
    }
    
    /**
     * Sync all video comments from Supabase to local database
     */
    suspend fun syncVideoCommentsFromSupabase() = withContext(Dispatchers.IO) {
        try {
            val remoteComments = supabaseClient.fetchAllVideoComments()
            videoCommentDao?.insertAllComments(remoteComments)
            Log.d("SyncRepository", "Synced ${remoteComments.size} video comments from Supabase")
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error syncing video comments from Supabase", e)
        }
    }
    
    // ========== DOCENTE ROLE OPERATIONS ==========
    
    /**
     * Check if user is docente or higher
     */
    suspend fun isUserDocente(userId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // Check from Supabase
            return@withContext supabaseClient.isUserDocente(userId)
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error checking docente status for user $userId", e)
            false
        }
    }
    
    /**
     * Promote user to docente role
     */
    suspend fun promoteUserToDocente(userId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = BackendApiService.ensureCreatorRole(userId)
            if (result.isSuccess) {
                // Update local
                val docenteRole = rolDao.getDocenteRole()
                if (docenteRole != null) {
                    val user = usuarioDao.getUsuarioById(userId)
                    if (user != null) {
                        usuarioDao.updateUsuario(user.copy(rol_id = docenteRole.id))
                    }
                }
            }
            
            return@withContext result.isSuccess
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error promoting user $userId to docente", e)
            false
        }
    }
    
    /**
     * Initialize docente role in database if not exists
     */
    suspend fun initializeDocenteRole() = withContext(Dispatchers.IO) {
        try {
            if (!rolDao.roleExists("docente")) {
                val docenteRole = Rol.createDocenteRole()
                rolDao.insertRol(docenteRole)
                Log.d("SyncRepository", "Created docente role")
            } else {
                // Return unit if role exists
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error initializing docente role", e)
        }
    }

    // Helper to get user by ID locally
    suspend fun getUsuarioByIdLocal(userId: Long): Usuario? {
        return usuarioDao.getUsuarioById(userId)
    }

    /**
     * Fetch all submissions for a course including student usernames
     * This uses a raw SQL query to join task_submissions, tasks, topics, and usuarios tables
     */
    suspend fun fetchCourseSubmissionsWithUsernames(courseId: Long): List<Map<String, Any?>> {
        return try {
            if (supabaseClient.isConfigured()) {
                // Use supabaseRepo (the class instance) which has the method
                return supabaseRepo.fetchCourseSubmissionsWithUsernames(courseId)
            }
            
            // Local fallback logic
            val topics = topicDao.getTopicsByCourse(courseId)
            val submissionsList = mutableListOf<Map<String, Any?>>()
            
            for (topic in topics) {
                val tasks = taskDao.getTasksByTopicId(topic.id)
                for (task in tasks) {
                    val submissions = taskSubmissionDao.getSubmissionsByTask(task.id)
                    for (submission in submissions) {
                        var studentUsername = "Unknown"
                        val studentId = submission.studentId
                        
                        if (studentId > 0) {
                            val user = usuarioDao.getUsuarioById(studentId)
                            if (user != null) {
                                studentUsername = user.usuario
                            }
                        }
                        // Removed fallback to studentUsername property as it does not exist in Entity
                        
                        val map = mapOf(
                            "submission_id" to submission.id,
                            "task_id" to task.id,
                            "task_title" to task.name,
                            "student_id" to studentId,
                            "student_username" to studentUsername,
                            "grade" to submission.grade,
                            "submission_date" to submission.submissionDate,
                            "file_uri" to submission.fileUri
                        )
                        submissionsList.add(map)
                    }
                }
            }
            return submissionsList
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error fetching course submissions with usernames", e)
            emptyList()
        }
    }

    /**
     * Notify all subscribers of a creator about a new course.
     * Delegates to the backend notification endpoint which handles
     * both in-app notifications AND push notifications server-side.
     * @param course The newly created course
     * @return Pair of (in-app notifications sent, push notifications sent)
     */
    suspend fun notifySubscribersOfNewCourse(course: Course): Pair<Int, Int> = withContext(Dispatchers.IO) {
        try {
            val creatorUserId = course.creatorUserId
            if (creatorUserId <= 0) {
                Log.w("SyncRepository", "Invalid creator user ID for course ${course.id}")
                return@withContext Pair(0, 0)
            }

            // Fetch subscriber IDs via backend
            val subscribersResult = com.example.tareamov.service.BackendApiService.getMySubscribers()
            val subscriberIds = if (subscribersResult.isSuccess) {
                subscribersResult.getOrNull()?.map { it.subscriberId } ?: emptyList()
            } else {
                emptyList()
            }

            if (subscriberIds.isEmpty()) {
                Log.d("SyncRepository", "No subscribers to notify for course '${course.title}'")
                return@withContext Pair(0, 0)
            }

            // Send bulk notification via backend
            val result = com.example.tareamov.service.BackendApiService.sendNotificationToMultiple(
                userIds = subscriberIds,
                title = "📚 Nuevo curso disponible",
                message = "Se ha publicado un nuevo curso: ${course.title ?: "Nuevo curso"}",
                type = "new_course",
                data = mapOf(
                    "courseId" to course.id,
                    "courseTitle" to (course.title ?: ""),
                    "thumbnailUrl" to (course.thumbnailUri ?: "")
                )
            )

            val count = subscriberIds.size
            Log.d("SyncRepository", "Notified $count subscribers about new course '${course.title}' via backend")
            return@withContext Pair(count, count)
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error notifying subscribers of new course", e)
            Pair(0, 0)
        }
    }

    /**
     * Fire-and-forget version of notifySubscribersOfNewCourse
     * Launches in background scope
     */
    fun notifySubscribersOfNewCourseAsync(course: Course) {
        syncScope.launch {
            notifySubscribersOfNewCourse(course)
        }
    }

    /**
     * Notify enrolled students about a new task in their course.
     * Delegates to the backend which handles notifications server-side.
     * @param taskId The ID of the newly created task
     * @param taskName The name of the task
     * @param courseId The course ID the task belongs to
     * @param courseName The course name
     * @param creatorUserId The creator's user ID
     * @param creatorUsername The creator's username
     * @param creatorAvatarUrl The creator's avatar URL (optional)
     */
    suspend fun notifyEnrolledStudentsOfNewTask(
        taskId: Long,
        taskName: String,
        courseId: Long,
        courseName: String,
        creatorUserId: Long,
        creatorUsername: String,
        creatorAvatarUrl: String? = null
    ): Int = withContext(Dispatchers.IO) {
        try {
            // Fetch enrolled student IDs via backend
            val progressResult = com.example.tareamov.service.BackendApiService.getAllProgressByCourse(courseId)
            val enrolledUserIds = if (progressResult.isSuccess) {
                progressResult.getOrNull()
                    ?.map { it.usuarioEstudiante }
                    ?.filter { it != creatorUserId }
                    ?: emptyList()
            } else {
                emptyList()
            }

            if (enrolledUserIds.isEmpty()) {
                Log.d("SyncRepository", "No enrolled students to notify for task '$taskName'")
                return@withContext 0
            }

            // Send bulk notification via backend
            val result = com.example.tareamov.service.BackendApiService.sendNotificationToMultiple(
                userIds = enrolledUserIds,
                title = "📝 Nueva tarea en $courseName",
                message = "$creatorUsername publicó: $taskName",
                type = "new_task",
                data = mapOf(
                    "taskId" to taskId,
                    "courseId" to courseId,
                    "taskName" to taskName,
                    "courseName" to courseName
                )
            )

            val count = enrolledUserIds.size
            Log.i("SyncRepository", "✅ Notified $count students about new task '$taskName' via backend")
            return@withContext count
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error notifying enrolled students of new task", e)
            0
        }
    }

    /**
     * Fire-and-forget version of notifyEnrolledStudentsOfNewTask
     * Launches in background scope
     */
    fun notifyEnrolledStudentsOfNewTaskAsync(
        taskId: Long,
        taskName: String,
        courseId: Long,
        courseName: String,
        creatorUserId: Long,
        creatorUsername: String,
        creatorAvatarUrl: String? = null
    ) {
        syncScope.launch {
            notifyEnrolledStudentsOfNewTask(
                taskId = taskId,
                taskName = taskName,
                courseId = courseId,
                courseName = courseName,
                creatorUserId = creatorUserId,
                creatorUsername = creatorUsername,
                creatorAvatarUrl = creatorAvatarUrl
            )
        }
    }

    suspend fun fetchCreatorUsernameByCourseId(courseId: Long): String? = withContext(Dispatchers.IO) {
        try {
            // 1. Get course from local DB
            val course = courseDao.getCourseById(courseId)
            if (course != null) {
                val userId = course.creatorUserId
                // 2. Get user from local DB
                val user = usuarioDao.getUsuarioById(userId)
                if (user != null && !user.usuario.isNullOrBlank()) {
                    return@withContext user.usuario
                }
            }
            null
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error fetching creator username for course $courseId", e)
            null
        }
    }
    
    // Delete topic via backend API
    suspend fun deleteTopicFromSupabase(topicId: Long): Boolean {
        return try {
            val result = BackendApiService.deleteTopic(topicId)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Topic $topicId deleted via backend")
                true
            } else {
                Log.w("SyncRepository", "Failed to delete topic $topicId: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error deleting topic $topicId", e)
            false
        }
    }
    
    // Delete task via backend API
    suspend fun deleteTaskFromSupabase(taskId: Long): Boolean {
        return try {
            val result = BackendApiService.deleteTask(taskId)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Task $taskId deleted via backend")
                true
            } else {
                Log.w("SyncRepository", "Failed to delete task $taskId: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error deleting task $taskId", e)
            false
        }
    }
    
    // Delete all content items for a specific task via backend API
    suspend fun deleteContentItemsByTaskIdFromSupabase(taskId: Long): Boolean {
        return try {
            val result = BackendApiService.deleteContentItemsByTask(taskId)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Content items for taskId=$taskId deleted via backend")
                true
            } else {
                Log.w("SyncRepository", "Failed to delete content items for taskId=$taskId: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error deleting content items for taskId=$taskId", e)
            false
        }
    }
    
    // Delete content item via backend API
    suspend fun deleteContentItemFromSupabase(contentItemId: Long): Boolean {
        return try {
            val result = BackendApiService.deleteContentItem(contentItemId)
            if (result.isSuccess) {
                Log.d("SyncRepository", "Content item $contentItemId deleted via backend")
                true
            } else {
                Log.w("SyncRepository", "Failed to delete content item $contentItemId: ${result.errorMessage()}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error deleting content item $contentItemId", e)
            false
        }
    }

    // ========== PURCHASED COURSES (SUCCESSFUL TRANSACTIONS) OPERATIONS ==========
    
    /**
     * Fetch count of courses with successful transactions
     */
    suspend fun fetchPurchasedCoursesCount(): Long = withContext(Dispatchers.IO) {
        try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured for purchased courses count")
                return@withContext 0L
            }
            supabaseClient.fetchPurchasedCoursesCount()
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error fetching purchased courses count", e)
            0L
        }
    }
    
    /**
     * Fetch all courses that have successful transactions (purchased courses)
     */
    suspend fun fetchPurchasedCoursesFromSupabase(): List<Course> = withContext(Dispatchers.IO) {
        try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured for purchased courses")
                return@withContext emptyList()
            }
            supabaseClient.fetchPurchasedCourses()
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error fetching purchased courses from Supabase", e)
            emptyList()
        }
    }
    
    /**
     * Check if a specific user has purchased a course
     */
    suspend fun hasUserPurchasedCourse(userId: Long, courseId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured for user purchase check")
                return@withContext false
            }
            supabaseClient.hasUserPurchasedCourse(userId, courseId)
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error checking user purchase", e)
            false
        }
    }
    
    /**
     * Fetch courses purchased by a specific user
     */
    suspend fun fetchCoursesPurchasedByUser(userId: Long): List<Course> = withContext(Dispatchers.IO) {
        try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured for user purchased courses")
                return@withContext emptyList()
            }
            supabaseClient.fetchCoursesPurchasedByUser(userId)
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error fetching courses purchased by user", e)
            emptyList()
        }
    }

}
