package com.example.tareamov.data.sync

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.tareamov.data.dao.UsuarioDao
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import com.example.tareamov.data.repository.SupabaseRepository
import kotlinx.coroutines.withContext

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
    private val progresoEstudianteDao: com.example.tareamov.data.dao.ProgresoEstudianteDao
) {
    // SharedPreferences-based cache to store last remote 'updated_at' per table
    private val prefs by lazy {
        // Use application context from one of the DAOs by reflection isn't reliable; expect caller to call `initWithContext` if needed.
        null as android.content.SharedPreferences?
    }

    // Optional: initialize with Context to enable caching
    fun initWithContext(context: android.content.Context) {
        try {
            val p = context.getSharedPreferences("supabase_sync_cache", android.content.Context.MODE_PRIVATE)
            (this::class.java.getDeclaredField("prefs")).apply {
                isAccessible = true
                set(this@SyncRepository, p)
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "Could not initialize prefs for caching", e)
        }
    }

    private val syncScope = CoroutineScope(Dispatchers.IO)
    private val supabaseRepo = SupabaseRepository()
    private val supabaseClient = com.example.tareamov.service.SupabaseClient

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
                usuario = u.usuario,
                contrasena = u.contrasena,
                persona_id = u.persona_id,
                rol_id = u.rol_id,
                rolNombre = rolNombre,
                rolNivel = rolNivel
            )
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchUsuarioWithRoleFromSupabase failed for $username", e)
            null
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
    
    // Public helper to upsert a Course to Supabase. This will try the dedicated
    // SupabaseClient.insertCourse first (which returns the remote id), and fall
    // back to SupabaseRepository.upsert if needed.
    fun upsertCourseToSupabase(course: Course) {
        syncScope.launch {
            if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured. Skipping upsertCourseToSupabase.")
                return@launch
            }
            try {
                val supabaseClient = com.example.tareamov.service.SupabaseClient

                // Strategy:
                // 1) If course.id is present, verify remote row exists with that id -> PATCH it.
                // 2) If not found, try to locate a remote row by (creatorUsername + title) and PATCH that.
                // 3) If no candidate found, perform INSERT as before.

                if (course.id != null && course.id > 0) {
                    Log.d("SyncRepository", "Attempting update by id=${course.id} for course='${course.title}'")
                    try {
                        val remoteCandidate = withContext(Dispatchers.IO) { supabaseClient.fetchCourseById(course.id) }
                        if (remoteCandidate != null) {
                            Log.d("SyncRepository", "Remote candidate found id=${remoteCandidate.id} title='${remoteCandidate.title}'")
                            val updated = withContext(Dispatchers.IO) { supabaseClient.updateCourseById(course.id, course) }
                            if (updated) {
                                Log.i("SyncRepository", "Course '${course.title}' updated on Supabase (id=${course.id}).")
                                return@launch
                            } else {
                                Log.w("SyncRepository", "Attempted update by id=${course.id} but it failed; will try other strategies.")
                            }
                        } else {
                            Log.d("SyncRepository", "No remote course found with id=${course.id}; will try matching by creator/title.")
                        }
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Error checking remote course by id=${course.id}", e)
                    }
                }

                // Try to find a remote match by creatorUserId + title as a heuristic
                try {
                    val creatorId = course.creatorUserId
                    if (creatorId > 0 && !course.title.isNullOrEmpty()) {
                        // Fetch username from user ID to use in search
                        val creator = withContext(Dispatchers.IO) { 
                            com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(creatorId) 
                        }
                        if (!creator.isNullOrEmpty()) {
                            val candidates = withContext(Dispatchers.IO) { supabaseClient.fetchCoursesByCreator(creator) }
                            val match = candidates.firstOrNull { (it.title ?: "").trim() == course.title?.trim() }
                            if (match != null) {
                                val updated = withContext(Dispatchers.IO) { supabaseClient.updateCourseById(match.id, course) }
                                if (updated) {
                                    Log.i("SyncRepository", "Course '${course.title}' matched and updated on Supabase (id=${match.id}).")
                                    return@launch
                                } else {
                                    Log.w("SyncRepository", "Matched remote course id=${match.id} but update failed; falling back to insert/upsert.")
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Error attempting creator/title match for course '${course.title}'", e)
                }

                // Fallback: insert (or SupabaseRepository.upsert if insert does not return id)
                val remoteId = withContext(Dispatchers.IO) { supabaseClient.insertCourse(course) }
                if (remoteId != null) {
                    Log.i("SyncRepository", "Course '${course.title}' upserted to Supabase (id=$remoteId).")
                } else {
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("courses", course) }
                    if (ok) Log.i("SyncRepository", "Course '${course.title}' upserted to Supabase via SupabaseRepository.")
                    else Log.e("SyncRepository", "Failed to upsert course '${course.title}' to Supabase.")
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception during upsertCourseToSupabase", e)
            }
        }
    }

    // Public helper: delete a course remotely by id (fire-and-forget). Logs result.
    fun deleteCourseRemoteById(courseId: Long) {
        syncScope.launch {
            try {
                if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                    Log.w("SyncRepository", "SupabaseClient not configured. Skipping deleteCourseRemoteById for id=$courseId")
                    return@launch
                }
                val ok = withContext(Dispatchers.IO) { com.example.tareamov.service.SupabaseClient.deleteCourseById(courseId) }
                if (ok) Log.i("SyncRepository", "Course id=$courseId deleted remotely") else Log.w("SyncRepository", "Failed to delete course id=$courseId remotely")
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
        return try {
            if (!supabaseClient.isConfigured()) return emptyList()
            // Try the typed fetch first
            var list = supabaseClient.fetchVideos()
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
                            val courseId = if (obj.has("course_id") && !obj.get("course_id").isJsonNull) obj.get("course_id").asLong else null
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
                                courseId = courseId
                            )
                            repaired.add(v)
                        } catch (t: Exception) {
                            Log.w("SyncRepository", "Failed to parse video json element", t)
                        }
                    }
                    if (repaired.isNotEmpty()) list = repaired
                }
            }
            // Sort by timestamp string descending where possible; fallback to id desc
            val sorted = list.sortedWith(compareByDescending<com.example.tareamov.data.entity.VideoData> { v ->
                // timestamp is a Long in our model; use it directly
                v.timestamp
            }.thenByDescending { v -> v.id })
            sorted
        } catch (e: Exception) {
            Log.e("SyncRepository", "fetchVideosFromSupabase failed", e)
            emptyList()
        }
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
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.d("SyncRepository", "Supabase not configured - returning empty")
                return Pair(emptyList(), 0)
            }
            withContext(Dispatchers.IO) {
                supabaseClient.fetchVideosPaginated(offset = offset, limit = limit)
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchVideosPaginated failed", e)
            Pair(emptyList(), 0)
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

    // New wrappers that use SupabaseClient server-side filters when available
    suspend fun fetchTopicsByCourseFromSupabase(courseId: Long): List<Topic> {
        return try {
            if (!supabaseClient.isConfigured()) return emptyList()
            withContext(Dispatchers.IO) { supabaseClient.fetchTopicsByCourse(courseId) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchTopicsByCourseFromSupabase failed for courseId=$courseId", e)
            emptyList()
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
    suspend fun fetchTaskByIdFromSupabase(id: Long): Task? {
        return try {
            if (!supabaseClient.isConfigured()) return null
            withContext(Dispatchers.IO) { supabaseClient.fetchTaskById(id) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchTaskByIdFromSupabase failed for id=$id", e)
            null
        }
    }

    suspend fun fetchContentItemsByTopicIdsFromSupabase(topicIds: List<Long>): List<ContentItem> {
        return try {
            if (!supabaseClient.isConfigured() || topicIds.isEmpty()) return emptyList()
            withContext(Dispatchers.IO) { supabaseClient.fetchContentItemsByTopicIds(topicIds) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchContentItemsByTopicIdsFromSupabase failed for topicIds=$topicIds", e)
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

    // Fetch videos by creator user ID from Supabase (using 2-step fetch to avoid raw SQL)
    suspend fun fetchVideosByCreatorUserIdFromSupabase(userId: Long): List<com.example.tareamov.data.entity.VideoData> {
        return try {
            if (!supabaseClient.isConfigured()) {
                return emptyList()
            }
            
            // Step 1: Fetch courses for this user
            val courses = withContext(Dispatchers.IO) { supabaseClient.fetchCoursesByCreatorUserId(userId) }
            val courseIds = courses.map { it.id }
            
            if (courseIds.isEmpty()) {
                return emptyList()
            }
            
            // Step 2: Fetch videos for these courses
            val videos = withContext(Dispatchers.IO) { supabaseClient.fetchVideosByCourseIds(courseIds) }
            
            // We need to inject the username since it's not in the video table.
            // We can get it from the user ID if needed, but for now let's try to get it from SupabaseClient helper
            val username = withContext(Dispatchers.IO) { supabaseClient.getUsernameFromUserId(userId) } ?: ""
            
            val result = videos.map { it.copy(username = username) }
            result
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchVideosByCreatorUserIdFromSupabase failed for userId=$userId", e)
            emptyList()
        }
    }

    // Subscriptions helpers
    suspend fun insertSubscriptionRemote(sub: Subscription): Boolean {
        return try {
            if (!supabaseClient.isConfigured()) return false
            // First check if the subscription already exists remotely
            val exists = withContext(Dispatchers.IO) { supabaseClient.isSubscribedRemote(sub.subscriberId, sub.creatorId) }
            if (exists) {
                Log.d("SyncRepository", "insertSubscriptionRemote: subscription already exists remotely for ${sub.subscriberId} -> ${sub.creatorId}")
                return true
            }
            // Not exists -> try insert
            withContext(Dispatchers.IO) { supabaseClient.insertSubscriptionToSupabase(sub) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "insertSubscriptionRemote failed", e)
            false
        }
    }
    
    // Insert a Topic into Supabase and return remote id (or null)
    suspend fun insertTopicRemote(topic: com.example.tareamov.data.entity.Topic): Long? {
        return try {
            // Ensure new topics start from ID 77
            val adjustedTopic = if (topic.id == 0L) {
                // Get the maximum existing topic ID
                val maxId = withContext(Dispatchers.IO) { 
                    try {
                        val allTopics = supabaseClient.fetchTopics()
                        allTopics.maxOfOrNull { it.id } ?: 0L
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Could not fetch max topic ID", e)
                        0L
                    }
                }
                // Use the greater of maxId+1 or 77 as the starting ID
                val nextId = maxOf(maxId + 1, 77L)
                topic.copy(id = nextId)
            } else {
                topic
            }
            withContext(Dispatchers.IO) { supabaseClient.insertTopic(adjustedTopic) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "insertTopicRemote failed", e)
            null
        }
    }

    /**
     * Insert a topic using the database trigger strategy: do not send course_id, instead
     * include a courseTitle that the DB trigger can use to associate the topic to the
     * correct Course row. This mirrors insertTopicRemote but calls SupabaseClient.insertTopicUsingTrigger.
     */
    suspend fun insertTopicRemoteUsingTrigger(topic: com.example.tareamov.data.entity.Topic, courseTitle: String?): Long? {
        return try {
            // Ensure new topics start from ID 77 (same behavior as insertTopicRemote)
            val adjustedTopic = if (topic.id == 0L) {
                val maxId = withContext(Dispatchers.IO) {
                    try {
                        val allTopics = supabaseClient.fetchTopics()
                        allTopics.maxOfOrNull { it.id } ?: 0L
                    } catch (e: Exception) {
                        Log.w("SyncRepository", "Could not fetch max topic ID", e)
                        0L
                    }
                }
                val nextId = maxOf(maxId + 1, 77L)
                topic.copy(id = nextId)
            } else {
                topic
            }

            withContext(Dispatchers.IO) { supabaseClient.insertTopicUsingTrigger(adjustedTopic, courseTitle) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "insertTopicRemoteUsingTrigger failed", e)
            null
        }
    }
    
    // Insert a Task into Supabase and return remote id (or null)
    // NOTE: Creator parameters are accepted for compatibility but ignored since tasks table doesn't have those columns
    suspend fun insertTaskRemote(
        task: com.example.tareamov.data.entity.Task,
        fallbackCreatorUsername: String? = null,
        fallbackCreatorUserId: Long? = null
    ): Long? {
        return try {
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "Supabase not configured, cannot insert task")
                return null
            }

            val originalTopicId = task.topicId
            if (originalTopicId <= 0) {
                Log.e("SyncRepository", "Invalid topicId=$originalTopicId for task: name=${task.name}")
                return null
            }

            // Resolve the remote topic id (handles mismatched local/remote IDs and missing topics)
            val remoteTopicId = resolveRemoteTopicId(originalTopicId)
            if (remoteTopicId == null || remoteTopicId <= 0) {
                Log.e(
                    "SyncRepository",
                    "❌ Could not resolve remote topic for local topicId=$originalTopicId (task='${task.name}')"
                )
                return null
            }

            val taskForInsert = if (remoteTopicId == originalTopicId) task else task.copy(topicId = remoteTopicId)
            
            Log.d(
                "SyncRepository",
                "📝 Preparing to insert task='${taskForInsert.name}' to remote topicId=$remoteTopicId"
            )

            // First check if a matching task already exists remotely (idempotency)
            val existingRemoteTask = try {
                val candidates = withContext(Dispatchers.IO) {
                    supabaseClient.fetchTasksByTopicIds(listOf(taskForInsert.topicId))
                }
                candidates.firstOrNull { (it.name ?: "").equals(taskForInsert.name, ignoreCase = true) }
            } catch (e: Exception) {
                Log.w("SyncRepository", "Could not check existing remote tasks before insert", e)
                null
            }

            if (existingRemoteTask != null) {
                Log.d(
                    "SyncRepository",
                    "✅ Task already exists remotely with id=${existingRemoteTask.id} (topicId=${taskForInsert.topicId})"
                )
                return existingRemoteTask.id
            }

            // Try direct insert using SupabaseClient
            // Note: creator params are passed but ignored by SupabaseClient since tasks table doesn't have those columns
            val remoteId = withContext(Dispatchers.IO) {
                supabaseClient.insertTask(
                    taskForInsert,
                    null, // creatorUsername - not used
                    null  // creatorUserId - not used
                )
            }
            
            if (remoteId != null && remoteId > 0) {
                Log.i(
                    "SyncRepository",
                    "✅ Task inserted successfully with id=$remoteId, name='${taskForInsert.name}', topicId=${taskForInsert.topicId}"
                )
                return remoteId
            }

            Log.w(
                "SyncRepository",
                "⚠️ Direct insert returned null for task '${taskForInsert.name}' (topicId=${taskForInsert.topicId})"
            )

            // Final fallback: re-check if the task now exists remotely (eventual consistency)
            val eventualTask = try {
                val refreshed = withContext(Dispatchers.IO) {
                    supabaseClient.fetchTasksByTopicIds(listOf(taskForInsert.topicId))
                }
                refreshed.firstOrNull { (it.name ?: "").equals(taskForInsert.name, ignoreCase = true) }
            } catch (e: Exception) {
                Log.w("SyncRepository", "Failed to refresh tasks after insert attempt", e)
                null
            }

            if (eventualTask != null) {
                Log.i(
                    "SyncRepository",
                    "✅ Task appeared after retry with id=${eventualTask.id} (topicId=${taskForInsert.topicId})"
                )
                return eventualTask.id
            }

            Log.e("SyncRepository", "❌ All attempts to insert task failed for '${taskForInsert.name}'")
            null
        } catch (e: Exception) {
            Log.e("SyncRepository", "❌ insertTaskRemote exception", e)
            null
        }
    }

    private suspend fun resolveRemoteTopicId(localTopicId: Long): Long? {
        if (localTopicId <= 0) return null

        // First try to fetch the topic directly from Supabase by ID
        try {
            val remote = withContext(Dispatchers.IO) {
                supabaseClient.fetchTopicById(localTopicId)
            }
            if (remote != null && remote.id > 0) {
                Log.d("SyncRepository", "✅ Found topic in Supabase with id=${remote.id}")
                return remote.id
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "resolveRemoteTopicId: fetch by id failed for $localTopicId", e)
        }

        // Fallback: try to find topic by matching against local topic properties
        val localTopic = withContext(Dispatchers.IO) { topicDao.getTopicById(localTopicId) }
        if (localTopic == null) {
            Log.e("SyncRepository", "resolveRemoteTopicId: local topic $localTopicId not found in Room")
            return null
        }

        // Try to find the topic in Supabase by name and course
        val remoteCourseId = resolveRemoteCourseId(localTopic.courseId)
        if (remoteCourseId == null || remoteCourseId <= 0) {
            Log.e(
                "SyncRepository",
                "resolveRemoteTopicId: could not resolve remote course for local topic ${localTopic.id}"
            )
            return null
        }

        // Try to find a topic with the same name (case-insensitive) under the resolved remote course
        val remoteTopics = try {
            withContext(Dispatchers.IO) { supabaseClient.fetchTopicsByCourse(remoteCourseId) }
        } catch (e: Exception) {
            Log.w(
                "SyncRepository",
                "resolveRemoteTopicId: failed to fetch topics for course $remoteCourseId",
                e
            )
            emptyList()
        }

        remoteTopics.firstOrNull {
            it.id == localTopic.id || it.name.equals(localTopic.name, ignoreCase = true)
        }?.let {
            Log.d("SyncRepository", "✅ Found matching topic in Supabase: id=${it.id}, name=${it.name}")
            return it.id
        }

        // Topic does not exist remotely yet — insert it using the resolved course id
        Log.d("SyncRepository", "📝 Topic not found in Supabase, inserting: name=${localTopic.name}, courseId=$remoteCourseId")
        val topicForInsert = localTopic.copy(courseId = remoteCourseId)
        val insertedTopicId = try {
            withContext(Dispatchers.IO) { supabaseClient.insertTopic(topicForInsert) }
        } catch (e: Exception) {
            Log.e("SyncRepository", "resolveRemoteTopicId: failed to insert topic ${localTopic.name}", e)
            null
        }

        if (insertedTopicId != null && insertedTopicId > 0) {
            Log.i(
                "SyncRepository",
                "resolveRemoteTopicId: inserted topic '${localTopic.name}' with id=$insertedTopicId for course=$remoteCourseId"
            )
            return insertedTopicId
        }

        return null
    }

    private suspend fun resolveRemoteCourseId(localCourseId: Long): Long? {
        if (localCourseId <= 0) return null

        // Fast path: the remote course might already use the same id
        try {
            val remote = withContext(Dispatchers.IO) { supabaseClient.fetchCourseById(localCourseId) }
            if (remote != null && remote.id > 0) {
                return remote.id
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "resolveRemoteCourseId: fetch by id failed for $localCourseId", e)
        }

        val localCourse = withContext(Dispatchers.IO) { courseDao.getCourseById(localCourseId) }
        if (localCourse == null) {
            Log.e("SyncRepository", "resolveRemoteCourseId: local course $localCourseId not found")
            return null
        }

        val remoteByTitle = try {
            fetchCoursesFromSupabase().firstOrNull { remote ->
                val titlesMatch = remote.title?.equals(localCourse.title, ignoreCase = true) == true
                val creatorsMatch = remote.creatorUserId == localCourse.creatorUserId
                titlesMatch && creatorsMatch
            }
        } catch (e: Exception) {
            Log.w("SyncRepository", "resolveRemoteCourseId: failed to fetch courses list", e)
            null
        }

        if (remoteByTitle != null) {
            return remoteByTitle.id
        }

        // As a last resort, create the course remotely so that dependent entities can be stored
        val insertedCourseId = try {
            withContext(Dispatchers.IO) { supabaseClient.insertCourse(localCourse) }
        } catch (e: Exception) {
            Log.e(
                "SyncRepository",
                "resolveRemoteCourseId: failed to insert remote course '${localCourse.title}'",
                e
            )
            null
        }

        if (insertedCourseId != null && insertedCourseId > 0) {
            Log.i(
                "SyncRepository",
                "resolveRemoteCourseId: inserted course '${localCourse.title}' with id=$insertedCourseId"
            )
        }

        return insertedCourseId
    }

    private suspend fun resolveTaskCreatorMetadata(localTopicId: Long, remoteTopicId: Long): Pair<String?, Long?> {
        var creatorUsername: String? = null
        var creatorUserId: Long? = null

        suspend fun populateFromLocalTopic(topicId: Long): Boolean {
            if (topicId <= 0) return false
            return try {
                val topic = withContext(Dispatchers.IO) { topicDao.getTopicById(topicId) } ?: return false
                val course = withContext(Dispatchers.IO) { courseDao.getCourseById(topic.courseId) } ?: return false
                // Fetch username from creator_user_id
                val candidate = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId)
                } ?: ""
                if (candidate.isEmpty()) return false
                creatorUsername = candidate
                creatorUserId = course.creatorUserId
                true
            } catch (e: Exception) {
                Log.w("SyncRepository", "resolveTaskCreatorMetadata: local lookup failed for topicId=$topicId", e)
                false
            }
        }

        if (!populateFromLocalTopic(localTopicId) && remoteTopicId != localTopicId) {
            populateFromLocalTopic(remoteTopicId)
        }

        val needsRemoteLookup = creatorUsername.isNullOrBlank() || creatorUserId == null
        if (needsRemoteLookup && supabaseClient.isConfigured()) {
            val topicIdForRemote = when {
                remoteTopicId > 0 -> remoteTopicId
                localTopicId > 0 -> localTopicId
                else -> null
            }

            val remoteTopic = topicIdForRemote?.let {
                try {
                    withContext(Dispatchers.IO) { supabaseClient.fetchTopicById(it) }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "resolveTaskCreatorMetadata: remote topic fetch failed for id=$it", e)
                    null
                }
            }

            val remoteCourse = remoteTopic?.courseId?.takeIf { it > 0 }?.let { courseId ->
                try {
                    withContext(Dispatchers.IO) { supabaseClient.fetchCourseById(courseId) }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "resolveTaskCreatorMetadata: remote course fetch failed for id=$courseId", e)
                    null
                }
            }

            // Fetch username from remote course's creator_user_id
            val remoteUsername = if (remoteCourse != null) {
                withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(remoteCourse.creatorUserId)
                }?.trim()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
            if (!remoteUsername.isNullOrEmpty()) {
                creatorUsername = remoteUsername
                if (creatorUserId == null && remoteCourse != null) {
                    creatorUserId = remoteCourse.creatorUserId
                }
            }
        }

        if (creatorUserId == null && !creatorUsername.isNullOrEmpty()) {
            creatorUserId = try {
                withContext(Dispatchers.IO) { usuarioDao.getUsuarioByUsername(creatorUsername!!) }?.id
            } catch (e: Exception) {
                Log.w("SyncRepository", "resolveTaskCreatorMetadata: local user lookup failed for $creatorUsername", e)
                null
            }

            if (creatorUserId == null && !creatorUsername.isNullOrEmpty()) {
                creatorUserId = try {
                    withContext(Dispatchers.IO) { supabaseClient.fetchUsuarioByUsername(creatorUsername!!) }?.id
                } catch (e: Exception) {
                    Log.w("SyncRepository", "resolveTaskCreatorMetadata: remote user lookup failed for $creatorUsername", e)
                    null
                }
            }
        }

        if (creatorUsername.isNullOrBlank()) {
            creatorUsername = null
        }

        if (creatorUsername != null) {
            Log.d(
                "SyncRepository",
                "resolveTaskCreatorMetadata -> creator=$creatorUsername userId=${creatorUserId ?: "null"} (localTopic=$localTopicId remoteTopic=$remoteTopicId)"
            )
        } else {
            Log.w(
                "SyncRepository",
                "resolveTaskCreatorMetadata: could not resolve creator metadata (localTopic=$localTopicId remoteTopic=$remoteTopicId)"
            )
        }

        return Pair(creatorUsername, creatorUserId)
    }

    // Update a Task remotely via SupabaseClient
    suspend fun updateTaskRemote(task: com.example.tareamov.data.entity.Task): Boolean {
        return try {
            Log.d("SyncRepository", "updateTaskRemote: id=${task.id}, topicId=${task.topicId}, name='${task.name}'")
            if (!supabaseClient.isConfigured()) {
                Log.e("SyncRepository", "updateTaskRemote: SupabaseClient not configured")
                return false
            }
            
            // First try to update
            val result = withContext(Dispatchers.IO) { supabaseClient.updateTask(task) }
            Log.d("SyncRepository", "updateTaskRemote initial result: $result for task ${task.id}")
            
            if (!result) {
                // Update failed - check if task exists in Supabase
                Log.w("SyncRepository", "Update failed for task ${task.id}, checking if task exists in Supabase...")
                val existingTask = withContext(Dispatchers.IO) { supabaseClient.fetchTaskById(task.id) }
                
                if (existingTask == null) {
                    // Task doesn't exist remotely, insert it instead
                    Log.w("SyncRepository", "Task ${task.id} doesn't exist in Supabase, attempting insert instead")
                    val (creatorUsername, creatorUserId) = resolveTaskCreatorMetadata(task.topicId, task.topicId)
                    val insertedId = withContext(Dispatchers.IO) {
                        supabaseClient.insertTask(task, creatorUsername, creatorUserId)
                    }
                    if (insertedId != null) {
                        Log.i("SyncRepository", "Task inserted successfully with remote id=$insertedId (local was ${task.id})")
                        return true
                    } else {
                        Log.e("SyncRepository", "Failed to insert task ${task.id} as fallback")
                        return false
                    }
                } else {
                    // Task exists but update still failed - might be permissions or validation issue
                    Log.e("SyncRepository", "Task ${task.id} exists in Supabase but update failed. Existing task: ${existingTask.name}")
                    return false
                }
            }
            
            result
        } catch (e: Exception) {
            Log.e("SyncRepository", "updateTaskRemote failed for task ${task.id}", e)
            false
        }
    }
    
    // Insert a ContentItem into Supabase and return remote id (or null)
    suspend fun insertContentItemRemote(contentItem: com.example.tareamov.data.entity.ContentItem): Long? {
        return try {
            Log.d("SyncRepository", "Inserting ContentItem: name=${contentItem.name}, type=${contentItem.contentType}, creator_id=${contentItem.creator_usuario_id}, creator_username=${contentItem.creator_username}")
            val remoteId = withContext(Dispatchers.IO) { supabaseClient.insertContentItem(contentItem) }
            Log.d("SyncRepository", "ContentItem inserted successfully with remote ID: $remoteId")
            remoteId
        } catch (e: Exception) {
            Log.w("SyncRepository", "Failed to insert content item to Supabase", e)
            null
        }
    }

    suspend fun deleteSubscriptionRemote(subscriberId: Long, creatorId: Long): Boolean {
        return try {
            if (!supabaseClient.isConfigured()) return false
            withContext(Dispatchers.IO) { supabaseClient.deleteSubscriptionFromSupabase(subscriberId, creatorId) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "deleteSubscriptionRemote failed", e)
            false
        }
    }

    suspend fun isSubscribedRemote(subscriberId: Long, creatorId: Long): Boolean {
        return try {
            if (!supabaseClient.isConfigured()) return false
            withContext(Dispatchers.IO) { supabaseClient.isSubscribedRemote(subscriberId, creatorId) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "isSubscribedRemote failed", e)
            false
        }
    }

    // New: sincronizar a Supabase via REST
    fun syncLocalToSupabase() {
        syncScope.launch {
            // If Supabase isn't configured in BuildConfig, skip
            if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured. Skipping syncLocalToSupabase.")
                return@launch
            }
            try {
                // Check that at least one usable sync target exists before attempting to sync
                // Previously we required 'personas' and 'usuarios' which blocked syncing file/chat contexts.
                val allowIf = listOf("app_documents", "file_contexts", "chat_messages", "task_submissions", "subscriptions")
                val available = allowIf.any { supabaseRepo.tableExists(it) }
                if (!available) {
                    Log.w("SyncRepository", "No usable Supabase target tables found (checked: ${allowIf.joinToString(",")}). Skipping sync. Apply migrations first.")
                    return@launch
                }
                // Usuarios
                usuarioDao.getAllUsuarios().forEach { usuario ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("usuarios", usuario) }
                    if (ok) Log.i("SyncRepository", "Usuario ${usuario.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync usuario ${usuario.id} to Supabase.")
                }

                // Personas
                personaDao.getAllPersonasList().forEach { persona ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("personas", persona) }
                    if (ok) Log.i("SyncRepository", "Persona ${persona.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync persona ${persona.id} to Supabase.")
                }

                // IMPORTANT: Upsert parents first to satisfy foreign key constraints
                // Videos are intentionally skipped from automatic syncLocalToSupabase here.
                // Video metadata should be uploaded explicitly when the user publishes/finishes
                // editing via the UI (VideoDetailsFragment -> nextButton) by calling
                // `uploadVideoToSupabaseSuspend`. This prevents premature uploads of
                // placeholder or incomplete video records.
                Log.i("SyncRepository", "Skipping automatic video upserts to Supabase. Use uploadVideoToSupabaseSuspend for explicit uploads.")

                // Topics (parents for tasks) - if topic upsert fails due to missing course, try upserting the course parent then retry
                topicDao.getAllTopics().forEach { topic ->
                    var ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("topics", topic) }
                    if (!ok) {
                        Log.w("SyncRepository", "Initial upsert failed for topic ${topic.id}; attempting to ensure parent course ${topic.courseId} exists and retry.")
                        // Try to upsert parent course if available locally
                        try {
                            val course = withContext(Dispatchers.IO) { courseDao.getCourseById(topic.courseId) }
                            if (course != null) {
                                val cOk = withContext(Dispatchers.IO) { supabaseRepo.upsert("courses", course) }
                                if (cOk) {
                                    Log.i("SyncRepository", "Parent course ${course.id} upserted, retrying topic ${topic.id}.")
                                    ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("topics", topic) }
                                } else {
                                    Log.w("SyncRepository", "Failed to upsert parent course ${topic.courseId} while retrying topic ${topic.id}.")
                                }
                            } else {
                                Log.w("SyncRepository", "Local parent course ${topic.courseId} not found for topic ${topic.id}.")
                            }
                        } catch (e: Exception) {
                            Log.e("SyncRepository", "Exception while attempting to upsert parent course for topic ${topic.id}", e)
                        }
                    }
                    if (ok) Log.i("SyncRepository", "Topic ${topic.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync topic ${topic.id} to Supabase after retry.")
                }

                // ContentItems
                contentItemDao.getAllContentItems().forEach { item ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("content_items", item) }
                    if (ok) Log.i("SyncRepository", "ContentItem ${item.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync contentItem ${item.id} to Supabase.")
                }

                // Tasks (children referencing topics). Ensure topics were upserted above; if a task upsert fails due to missing topic, try to upsert the topic parent then retry.
                taskDao.getAllTasks().forEach { task ->
                    var ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("tasks", task) }
                    if (!ok) {
                        Log.w("SyncRepository", "Initial upsert failed for task ${task.id}; attempting to ensure parent topic ${task.topicId} exists and retry.")
                        try {
                            val topic = withContext(Dispatchers.IO) { topicDao.getTopicById(task.topicId) }
                            if (topic != null) {
                                val tOk = withContext(Dispatchers.IO) { supabaseRepo.upsert("topics", topic) }
                                if (tOk) {
                                    Log.i("SyncRepository", "Parent topic ${topic.id} upserted, retrying task ${task.id}.")
                                    ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("tasks", task) }
                                } else {
                                    Log.w("SyncRepository", "Failed to upsert parent topic ${task.topicId} while retrying task ${task.id}.")
                                }
                            } else {
                                Log.w("SyncRepository", "Local parent topic ${task.topicId} not found for task ${task.id}.")
                            }
                        } catch (e: Exception) {
                            Log.e("SyncRepository", "Exception while attempting to upsert parent topic for task ${task.id}", e)
                        }
                    }
                    if (ok) Log.i("SyncRepository", "Task ${task.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync task ${task.id} to Supabase after retry.")
                }

                // TaskSubmissions: use direct SupabaseClient method to ensure payload uses snake_case
                // Try submissions after tasks are upserted so task_id FK exists remotely. If insert fails due to missing task, attempt to upsert the task then retry.
                val supabaseClient = com.example.tareamov.service.SupabaseClient
                taskSubmissionDao.getAllTaskSubmissions().forEach { submission ->
                    try {
                        // If the submission contains grading info, try update; otherwise insert
                        val hasGrade = submission.grade != null || !submission.feedback.isNullOrBlank()
                        var success = false

                        if (hasGrade) {
                            success = withContext(Dispatchers.IO) { supabaseClient.updateTaskSubmissionRemote(submission) }
                        } else {
                            val remoteId = withContext(Dispatchers.IO) { supabaseClient.insertTaskSubmission(submission) }
                            success = remoteId != null
                        }

                        if (!success) {
                            Log.w("SyncRepository", "Initial submission sync failed for ${submission.id}; attempting to upsert parent task ${submission.taskId} and retry.")
                            val taskParent = withContext(Dispatchers.IO) { taskDao.getTaskById(submission.taskId) }
                            if (taskParent != null) {
                                val tOk = withContext(Dispatchers.IO) { supabaseRepo.upsert("tasks", taskParent) }
                                if (tOk) {
                                    Log.i("SyncRepository", "Parent task ${taskParent.id} upserted, retrying submission ${submission.id}.")
                                    if (hasGrade) {
                                        success = withContext(Dispatchers.IO) { supabaseClient.updateTaskSubmissionRemote(submission) }
                                    } else {
                                        val remoteId2 = withContext(Dispatchers.IO) { supabaseClient.insertTaskSubmission(submission) }
                                        success = remoteId2 != null
                                    }
                                } else {
                                    Log.w("SyncRepository", "Failed to upsert parent task ${submission.taskId} while retrying submission ${submission.id}.")
                                }
                            } else {
                                Log.w("SyncRepository", "Local parent task ${submission.taskId} not found for submission ${submission.id}.")
                            }
                        }

                        if (success) Log.i("SyncRepository", "TaskSubmission ${submission.id} synced to Supabase.")
                        else Log.e("SyncRepository", "Failed to sync taskSubmission ${submission.id} to Supabase.")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception while syncing submission ${submission.id}", e)
                    }
                }

                // Subscriptions
                subscriptionDao.getAllSubscriptions().forEach { sub ->
                    try {
                        // Map Room entity fields to snake_case expected by Supabase/Postgres
                        val mapped = mapOf(
                            "subscriber_id" to sub.subscriberId,
                            "creator_id" to sub.creatorId,
                            "subscription_date" to sub.subscriptionDate
                        )
                        val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("subscriptions", mapped) }
                        if (ok) Log.i("SyncRepository", "Subscription ${sub.subscriberId}_${sub.creatorId} synced to Supabase.")
                        else Log.e("SyncRepository", "Failed to sync subscription ${sub.subscriberId}_${sub.creatorId} to Supabase.")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception while syncing subscription ${sub.subscriberId}_${sub.creatorId}", e)
                    }
                }

                // File contexts (metadata/extracted content) - ensure file_contexts table exists in migrations
                try {
                    val fileContexts = withContext(Dispatchers.IO) { fileContextDao.getAllFileContexts().first() }
                    fileContexts.forEach { fc ->
                        val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("file_contexts", fc) }
                        if (ok) Log.i("SyncRepository", "FileContext ${fc.id} synced to Supabase.")
                        else Log.e("SyncRepository", "Failed to sync FileContext ${fc.id} to Supabase.")
                    }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Could not sync file_contexts: ${e.message}")
                }



                // Videos
                videoDao.getAllVideos().forEach { video ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("videos", video) }
                    if (ok) Log.i("SyncRepository", "Video ${video.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync video ${video.id} to Supabase.")
                }

                // Roles
                rolDao.getAllRoles().forEach { rol ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("roles", rol) }
                    if (ok) Log.i("SyncRepository", "Rol ${rol.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync rol ${rol.id} to Supabase.")
                }

                // Chat messages - sync in-app chat messages to Supabase
                try {
                    val messages = withContext(Dispatchers.IO) { chatMessageDao.getAllMessages().first() }
                    messages.forEach { msg ->
                        val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("chat_messages", msg) }
                        if (ok) Log.i("SyncRepository", "ChatMessage ${msg.id} synced to Supabase.")
                        else Log.e("SyncRepository", "Failed to sync ChatMessage ${msg.id} to Supabase.")
                    }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Could not sync chat_messages: ${e.message}")
                }

                // Recursos
                recursoDao.getAllRecursos().forEach { recurso ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("recursos", recurso) }
                    if (ok) Log.i("SyncRepository", "Recurso ${recurso.id} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync recurso ${recurso.id} to Supabase.")
                }

                // Rol-Recursos
                rolRecursoDao.getAllRolRecursos().forEach { rr ->
                    val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("rol_recursos", rr) }
                    if (ok) Log.i("SyncRepository", "RolRecurso ${rr.rolId}-${rr.recursoId} synced to Supabase.")
                    else Log.e("SyncRepository", "Failed to sync rol_recurso ${rr.rolId}-${rr.recursoId} to Supabase.")
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception during syncLocalToSupabase", e)
            }
        }
    }

    // Public helper to upload a single VideoData to Supabase (non-blocking)
    fun uploadVideoToSupabase(video: com.example.tareamov.data.entity.VideoData) {
        syncScope.launch {
            try {
                if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                    Log.w("SyncRepository", "SupabaseClient not configured. Skipping uploadVideoToSupabase.")
                    return@launch
                }
                val supabaseClient = com.example.tareamov.service.SupabaseClient
                val remoteId = withContext(Dispatchers.IO) { supabaseClient.insertVideo(video) }
                if (remoteId != null) {
                    Log.i("SyncRepository", "Video uploaded to Supabase (remote id=$remoteId) username=${video.username} title=${video.title}")
                } else {
                    Log.w("SyncRepository", "Video upload returned null id for video id=${video.id}")
                }
            } catch (e: Exception) {
                Log.e("SyncRepository", "Exception uploading video to Supabase", e)
            }
        }
    }

    // Suspend version that returns success/failure; callers can await and act accordingly
    suspend fun uploadVideoToSupabaseSuspend(video: com.example.tareamov.data.entity.VideoData): Boolean {
        return try {
            if (!com.example.tareamov.service.SupabaseClient.isConfigured()) {
                Log.w("SyncRepository", "SupabaseClient not configured. Skipping uploadVideoToSupabaseSuspend.")
                return false
            }
            val supabaseClient = com.example.tareamov.service.SupabaseClient
            
            // Try to update first (if video has an ID, it likely already exists in Supabase)
            if (video.id > 0L) {
                val updated = withContext(Dispatchers.IO) { supabaseClient.updateVideo(video) }
                if (updated) {
                    Log.i("SyncRepository", "uploadVideoToSupabaseSuspend: Successfully updated video id=${video.id}")
                    return true
                }
                // If update failed, it might not exist yet, so try insert
                Log.d("SyncRepository", "Update failed for video id=${video.id}, attempting insert")
            }
            
            // Try insert (for new videos or if update failed)
            val remoteId = withContext(Dispatchers.IO) { supabaseClient.insertVideo(video) }
            if (remoteId != null) {
                Log.i("SyncRepository", "uploadVideoToSupabaseSuspend success remoteId=$remoteId for video id=${video.id}")
                true
            } else {
                Log.w("SyncRepository", "uploadVideoToSupabaseSuspend returned null id for video id=${video.id}")
                false
            }
        } catch (e: Exception) {
            Log.e("SyncRepository", "Exception in uploadVideoToSupabaseSuspend", e)
            false
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
            val all = withContext(Dispatchers.IO) { supabaseClient.fetchTaskSubmissions() }
            all.firstOrNull { it.taskId == taskId && it.studentUsername.equals(username, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "fetchUserSubmissionForTaskFromSupabase failed for taskId=$taskId username=$username", e)
            null
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
            val filtered = all.filter { it.studentUsername.equals(username, ignoreCase = true) && remoteTaskIds.contains(it.taskId) }
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

    companion object {
        // Lightweight wrapper so UI code can update a TaskSubmission remotely without
        // instantiating the full SyncRepository. Delegates to SupabaseClient.
        suspend fun updateTaskSubmissionToSupabase(submission: TaskSubmission): Boolean {
            return try {
                withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.updateTaskSubmissionRemote(submission)
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
            if (!supabaseClient.isConfigured()) {
                Log.w("SyncRepository", "Supabase not configured, skipping progreso sync")
                return false
            }
            supabaseClient.upsertProgresoEstudiante(progreso)
        } catch (e: Exception) {
            Log.e("SyncRepository", "Error syncing progreso to Supabase", e)
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
            val uniqueStudents = allSubmissions.map { it.studentUsername }.distinct()
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
                val courseStudents = courseSubmissions.map { it.studentUsername }.distinct()
                
                for (student in courseStudents) {
                    try {
                        val studentSubmissions = courseSubmissions.filter { it.studentUsername == student }
                        
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
                        
                        // Get user ID from username
                        val userId = supabaseClient.getUserIdFromUsername(student) ?: continue
                        
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
                        
                        // Sincronizar con Supabase
                        val success = supabaseClient.upsertProgresoEstudiante(progreso)
                        if (success) {
                            migratedCount++
                            Log.d("SyncRepository", "Migrated progress: $student in course ${course.title} (${course.id})")
                        } else {
                            Log.w("SyncRepository", "Failed to migrate progress: $student in course ${course.id}")
                        }
                        
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Error migrating progress for $student in course ${course.id}", e)
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
                
                // Get username from userId
                val username = supabaseClient.getUsernameFromUserId(userId)
                if (username == null) {
                    Log.w("SyncRepository", "Could not find username for userId=$userId, skipping")
                    continue
                }
                
                // Verificar si ya existe una submission para este estudiante y tarea
                val existingSubmission = taskSubmissionDao.getUserSubmissionForTask(taskId, username)
                
                if (existingSubmission == null) {
                    // Crear submission con calificación 0 por defecto
                    val defaultSubmission = TaskSubmission(
                        id = 0,
                        taskId = taskId,
                        studentUsername = username,
                        submissionDate = System.currentTimeMillis(),
                        fileUri = "", // Sin archivo adjunto inicialmente
                        fileName = "", // Sin nombre de archivo inicialmente
                        grade = 0f, // Calificación inicial de 0
                        feedback = "Tarea pendiente de entrega"
                    )
                    
                    try {
                        // Insertar en base de datos local
                        val localId = taskSubmissionDao.insertSubmission(defaultSubmission)
                        Log.d("SyncRepository", "Created local submission id=$localId for student=$username")
                        
                        // Intentar sincronizar con Supabase
                        if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                            try {
                                val remoteSuccess = supabaseRepo.upsert("task_submissions", defaultSubmission.copy(id = localId))
                                if (remoteSuccess) {
                                    Log.d("SyncRepository", "Synced submission to Supabase for student=$username")
                                } else {
                                    Log.w("SyncRepository", "Failed to sync submission to Supabase for student=$username")
                                }
                            } catch (e: Exception) {
                                Log.w("SyncRepository", "Error syncing submission to Supabase", e)
                            }
                        }
                        
                        successCount++
                        
                        // Actualizar el progreso del estudiante
                        updateStudentProgressAfterTaskCreation(username, courseId)
                        
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Error creating submission for student=$username", e)
                    }
                } else {
                    Log.d("SyncRepository", "Submission already exists for student=$username, task=$taskId")
                }
            }
            
            Log.i("SyncRepository", "Created $successCount default submissions for task $taskId")
            
            // Recalcular el progreso de todos los estudiantes en Supabase
            if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                try {
                    val recalcSuccess = supabaseRepo.recalculateAllStudentProgressForCourse(courseId)
                    if (recalcSuccess) {
                        Log.i("SyncRepository", "Successfully recalculated progress in Supabase for course $courseId")
                    } else {
                        Log.w("SyncRepository", "Failed to recalculate progress in Supabase")
                    }
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Error recalculating progress in Supabase", e)
                }
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
    private suspend fun updateStudentProgressAfterTaskCreation(username: String, courseId: Long) {
        try {
            val userId = supabaseClient.getUserIdFromUsername(username) ?: return
            val progreso = progresoEstudianteDao.getProgreso(userId, courseId)
            if (progreso != null) {
                // Obtener todas las submissions del estudiante en el curso
                val submissions = taskSubmissionDao.getStudentSubmissionsForCourse(username, courseId)
                
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
                
                // Calcular tareas completadas (submissions con grade > 0)
                val tareasCompletadas = submissions.count { (it.grade ?: 0f) > 0f }
                
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
                
                Log.d("SyncRepository", "Updated progress for student=$username: total=$tareasTotales, completed=$tareasCompletadas, progress=$porcentajeProgreso%, avg=$newPromedio")
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
                    
                    // Get username from userId
                    val studentUsername = supabaseClient.getUsernameFromUserId(userId) ?: continue
                    
                    // Filtrar submissions del estudiante
                    val studentSubmissions = allSubmissions.filter { 
                        it.studentUsername.equals(studentUsername, ignoreCase = false)
                    }
                    
                    // Calcular métricas
                    val tareasCompletadas = studentSubmissions.count { (it.grade ?: 0f) > 0f }
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
                    
                    Log.d("SyncRepository", "📊 Student=$studentUsername: total=$tareasTotales, completed=$tareasCompletadas, avg=$promedio")
                    
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
                        supabaseClient.upsertProgresoEstudiante(updatedProgreso)
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Error syncing to Supabase for student=$studentUsername", e)
                        false
                    }
                    
                    if (synced) {
                        updatedCount++
                        Log.d("SyncRepository", "✅ Updated progress for student=$studentUsername")
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
            
            // Intentar eliminar de Supabase si está configurado
            if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                try {
                    // Supabase debería tener trigger para actualizar progreso automáticamente
                    val deleteSql = "DELETE FROM tasks WHERE id = $taskId"
                    val result = supabaseRepo.executeRawQuery(deleteSql)
                    Log.d("SyncRepository", "Deleted task from Supabase: taskId=$taskId, result=${result.size}")
                } catch (e: Exception) {
                    Log.w("SyncRepository", "Error deleting task from Supabase", e)
                }
            }
            
            // Recalcular y sincronizar progreso para cada estudiante inscrito
            for (userId in studentUserIds) {
                var username: String? = null
                try {
                    // Get username from userId
                    username = supabaseClient.getUsernameFromUserId(userId)
                    if (username == null) {
                        Log.w("SyncRepository", "Could not find username for userId=$userId, skipping")
                        continue
                    }
                    
                    // Obtener todas las submissions del estudiante en el curso (ya no incluye la tarea eliminada)
                    val submissions = taskSubmissionDao.getStudentSubmissionsForCourse(username, courseId)
                    
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
                    val tareasCompletadas = submissions.count { (it.grade ?: 0f) > 0f }
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
                        
                        Log.d("SyncRepository", "Updated progress after task deletion for student=$username: total=$tareasTotales, completed=$tareasCompletadas, progress=$porcentajeProgreso%, avg=$promedio")
                    }
                } catch (e: Exception) {
                    Log.e("SyncRepository", "Error updating progress for student=$username after task deletion", e)
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
     * Applies the migration to disable problematic database triggers.
     * This should be called once during app initialization to fix the ambiguous column reference error.
     * The migration drops triggers that cause SQL error 42702 and moves progress calculation to app layer.
     */
    suspend fun applyTriggerDisableMigration(): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            Log.i("SyncRepository", "🔄 Applying migration to disable submission triggers...")
            
            val migrationSql = """
                -- Drop the problematic triggers that cause ambiguous column references
                DROP TRIGGER IF EXISTS trigger_update_progress_on_submission_insert ON task_submissions;
                DROP TRIGGER IF EXISTS trigger_update_progress_on_submission_update ON task_submissions;
                DROP TRIGGER IF EXISTS trigger_create_default_submissions ON tasks;
                DROP TRIGGER IF EXISTS trigger_update_task_count_on_insert ON tasks;
                DROP TRIGGER IF EXISTS trigger_update_task_count_on_delete ON tasks;
            """.trimIndent()
            
            // Execute via SupabaseRepository raw SQL
            val supabaseRepo = SupabaseRepository()
            val statements = migrationSql.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            
            for (statement in statements) {
                try {
                    supabaseRepo.executeRawQuery(statement)
                    Log.d("SyncRepository", "✅ Executed: ${statement.take(50)}...")
                } catch (e: Exception) {
                    // Some DROP IF EXISTS may fail if trigger doesn't exist - this is OK
                    Log.w("SyncRepository", "⚠️ Statement execution warning (may be expected): ${e.message}")
                }
            }
            
            Log.i("SyncRepository", "✅ Migration completed successfully")
            true
        } catch (e: Exception) {
            Log.e("SyncRepository", "❌ Error applying trigger disable migration", e)
            false
        }
    }

    suspend fun getSubmissionAndContextForTask(taskId: Long, username: String): Pair<TaskSubmission?, com.example.tareamov.data.entity.FileContext?> {
        return withContext(Dispatchers.IO) {
            // Try local first
            var submission = taskSubmissionDao.getSubmissionsByTask(taskId).firstOrNull { it.studentUsername == username }
            var fileContext: com.example.tareamov.data.entity.FileContext? = null
            
            if (submission == null) {
                // Try remote
                submission = supabaseClient.fetchTaskSubmissionByTaskId(taskId, username)
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

}
