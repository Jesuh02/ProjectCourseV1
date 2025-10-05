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

                // Try to find a remote match by creatorUsername + title as a heuristic
                try {
                    val creator = course.creatorUsername ?: ""
                    if (creator.isNotEmpty() && !course.title.isNullOrEmpty()) {
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
                            val id = if (obj.has("id")) obj.get("id").asLong else 0L
                            val username = when {
                                obj.has("username") -> obj.get("username").asString
                                obj.has("creator_username") -> obj.get("creator_username").asString
                                obj.has("user") -> obj.get("user").asString
                                else -> "unknown"
                            }
                            val description = if (obj.has("description")) obj.get("description").asString else ""
                            val title = if (obj.has("title")) obj.get("title").asString else ""
                            val videoUriString = when {
                                obj.has("video_uri_string") -> obj.get("video_uri_string").asString
                                obj.has("video_uri") -> obj.get("video_uri").asString
                                obj.has("video_url") -> obj.get("video_url").asString
                                else -> null
                            }
                            val localFilePath = if (obj.has("local_file_path")) obj.get("local_file_path").asString else null
                            val thumbnailUri = if (obj.has("thumbnail_uri")) obj.get("thumbnail_uri").asString else if (obj.has("thumbnail")) obj.get("thumbnail").asString else null
                            val timestamp = try { if (obj.has("timestamp")) obj.get("timestamp").asLong else if (obj.has("created_at")) java.time.Instant.parse(obj.get("created_at").asString).toEpochMilli() else System.currentTimeMillis() } catch (t: Exception) { System.currentTimeMillis() }
                            val isPaid = if (obj.has("is_paid")) obj.get("is_paid").asBoolean else false
                            val price = if (obj.has("price")) try { obj.get("price").asDouble } catch (t: Exception) { null } else null

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
                                price = price
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
            Log.d("SyncRepository", "fetchCoursesByCreatorFromSupabase: server-side filter returned empty, falling back to client-side filtering for $username")
            val all = withContext(Dispatchers.IO) { supabaseClient.fetchCourses() }
            val target = username.trim().lowercase()
            val filtered = all.filter { c ->
                val cu = (c.creatorUsername ?: "").trim().lowercase()
                cu == target
            }.sortedWith(compareByDescending<Course> { it.timestamp }.thenByDescending { it.creationDate })
            Log.d("SyncRepository", "fetchCoursesByCreatorFromSupabase: client-side filtered ${filtered.size} courses for creator=$username")
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
            // The app's Course entity exposes creatorUsername. Use that as primary creator identifier.
            return if (!course.creatorUsername.isNullOrBlank()) course.creatorUsername else null
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

    // Subscriptions helpers
    suspend fun insertSubscriptionRemote(sub: Subscription): Boolean {
        return try {
            if (!supabaseClient.isConfigured()) return false
            // First check if the subscription already exists remotely
            val exists = withContext(Dispatchers.IO) { supabaseClient.isSubscribedRemote(sub.subscriberUsername, sub.creatorUsername) }
            if (exists) {
                Log.d("SyncRepository", "insertSubscriptionRemote: subscription already exists remotely for ${sub.subscriberUsername} -> ${sub.creatorUsername}")
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
            withContext(Dispatchers.IO) { supabaseClient.insertTopic(topic) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "insertTopicRemote failed", e)
            null
        }
    }
    
    // Insert a Task into Supabase and return remote id (or null)
    suspend fun insertTaskRemote(task: com.example.tareamov.data.entity.Task): Long? {
        return try {
            if (!supabaseClient.isConfigured()) return null
            // First check if a matching task already exists remotely (idempotency).
            try {
                val candidates = withContext(Dispatchers.IO) { supabaseClient.fetchTasksByTopicIds(listOf(task.topicId)) }
                val found = candidates.firstOrNull { (it.name ?: "") == (task.name ?: "") }
                if (found != null) return found.id
            } catch (e: Exception) {
                Log.w("SyncRepository", "Could not check existing remote tasks before insert", e)
            }

            // Try direct insert
            val remoteId = withContext(Dispatchers.IO) { supabaseClient.insertTask(task) }
            if (remoteId != null) return remoteId

            // If insert failed, it may be due to missing parent topic on remote. Attempt upsert via SupabaseRepository which uses app_documents or direct upsert.
            try {
                val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("tasks", task) }
                if (ok) {
                    // After upsert, try to fetch by a heuristic: tasks with same title under the topic
                    val candidates = withContext(Dispatchers.IO) { supabaseClient.fetchTasksByTopicIds(listOf(task.topicId)) }
                    val found = candidates.firstOrNull { (it.name ?: "") == (task.name ?: "") }
                    if (found != null) return found.id
                }
            } catch (e: Exception) {
                Log.w("SyncRepository", "Fallback upsert for task failed", e)
            }

            // Final fallback: try to ensure parent topic exists remotely then retry insert
            try {
                val topic = withContext(Dispatchers.IO) { topicDao.getTopicById(task.topicId) }
                if (topic != null) {
                    val pushedTopic = withContext(Dispatchers.IO) { supabaseClient.insertTopic(topic) }
                    if (pushedTopic != null) {
                        // retry insert now that parent exists
                        return withContext(Dispatchers.IO) { supabaseClient.insertTask(task) }
                    }
                }
            } catch (e: Exception) {
                Log.w("SyncRepository", "Retry after pushing parent topic failed", e)
            }

            null
        } catch (e: Exception) {
            Log.w("SyncRepository", "insertTaskRemote failed", e)
            null
        }
    }

    // Update a Task remotely via SupabaseClient
    suspend fun updateTaskRemote(task: com.example.tareamov.data.entity.Task): Boolean {
        return try {
            if (!supabaseClient.isConfigured()) return false
            withContext(Dispatchers.IO) { supabaseClient.updateTask(task) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "updateTaskRemote failed", e)
            false
        }
    }
    
    // Insert a ContentItem into Supabase and return remote id (or null)
    suspend fun insertContentItemRemote(contentItem: com.example.tareamov.data.entity.ContentItem): Long? {
        return try {
            withContext(Dispatchers.IO) { supabaseClient.insertContentItem(contentItem) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "Failed to insert content item to Supabase", e)
            null
        }
    }

    suspend fun deleteSubscriptionRemote(subscriber: String, creator: String): Boolean {
        return try {
            if (!supabaseClient.isConfigured()) return false
            withContext(Dispatchers.IO) { supabaseClient.deleteSubscriptionFromSupabase(subscriber, creator) }
        } catch (e: Exception) {
            Log.w("SyncRepository", "deleteSubscriptionRemote failed", e)
            false
        }
    }

    suspend fun isSubscribedRemote(subscriber: String, creator: String): Boolean {
        return try {
            if (!supabaseClient.isConfigured()) return false
            withContext(Dispatchers.IO) { supabaseClient.isSubscribedRemote(subscriber, creator) }
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

                // Topics (parents for tasks) - if topic upsert fails due to missing course, try upserting the video parent then retry
                topicDao.getAllTopics().forEach { topic ->
                    var ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("topics", topic) }
                    if (!ok) {
                        Log.w("SyncRepository", "Initial upsert failed for topic ${topic.id}; attempting to ensure parent video ${topic.courseId} exists and retry.")
                        // Try to upsert parent video if available locally
                        try {
                            val video = withContext(Dispatchers.IO) { videoDao.getVideoById(topic.courseId) }
                            if (video != null) {
                                val vOk = withContext(Dispatchers.IO) { supabaseRepo.upsert("videos", video) }
                                if (vOk) {
                                    Log.i("SyncRepository", "Parent video ${video.id} upserted, retrying topic ${topic.id}.")
                                    ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("topics", topic) }
                                } else {
                                    Log.w("SyncRepository", "Failed to upsert parent video ${topic.courseId} while retrying topic ${topic.id}.")
                                }
                            } else {
                                Log.w("SyncRepository", "Local parent video ${topic.courseId} not found for topic ${topic.id}.")
                            }
                        } catch (e: Exception) {
                            Log.e("SyncRepository", "Exception while attempting to upsert parent video for topic ${topic.id}", e)
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
                            "subscriber_username" to sub.subscriberUsername,
                            "creator_username" to sub.creatorUsername,
                            "subscription_date" to sub.subscriptionDate
                        )
                        val ok = withContext(Dispatchers.IO) { supabaseRepo.upsert("subscriptions", mapped) }
                        if (ok) Log.i("SyncRepository", "Subscription ${sub.subscriberUsername}_${sub.creatorUsername} synced to Supabase.")
                        else Log.e("SyncRepository", "Failed to sync subscription ${sub.subscriberUsername}_${sub.creatorUsername} to Supabase.")
                    } catch (e: Exception) {
                        Log.e("SyncRepository", "Exception while syncing subscription ${sub.subscriberUsername}_${sub.creatorUsername}", e)
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

}
