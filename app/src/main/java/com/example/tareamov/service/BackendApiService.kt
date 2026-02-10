package com.example.tareamov.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.tareamov.BuildConfig
import com.example.tareamov.data.entity.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * BackendApiService — Servicio centralizado que reemplaza todas las llamadas
 * directas a Supabase (PostgREST) y R2 Storage.
 *
 * Todas las operaciones CRUD pasan ahora por el backend Node.js,
 * que implementa arquitectura hexagonal con JWT auth.
 *
 * Uso:
 *   BackendApiService.initialize(context)
 *   BackendApiService.login(username, password)
 *   val courses = BackendApiService.getCourses()
 */
object BackendApiService {

    private const val TAG = "BackendApiService"
    private const val PREFS_NAME = "backend_api_prefs"
    private const val KEY_JWT_TOKEN = "jwt_token"
    private const val KEY_USER_ID = "user_id"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val gson: Gson = GsonBuilder()
        .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .serializeNulls()
        .create()

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: SharedPreferences

    /** Base URL del backend (resuelto via ServerEndpointResolver o BuildConfig) */
    private val baseUrl: String
        get() {
            val url = BuildConfig.BACKEND_URL.ifBlank {
                "https://mcp-backenddeploy-production.up.railway.app"
            }
            return if (url.endsWith("/")) url.dropLast(1) else url
        }

    private val apiBase: String get() = "$baseUrl/api/v1"

    // ─────────────────────────────────────────────────────────
    // Inicialización y Auth
    // ─────────────────────────────────────────────────────────

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var jwtToken: String?
        get() = if (::prefs.isInitialized) prefs.getString(KEY_JWT_TOKEN, null) else null
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putString(KEY_JWT_TOKEN, value).apply()
            }
        }

    var currentUserId: Long
        get() = if (::prefs.isInitialized) prefs.getLong(KEY_USER_ID, 0L) else 0L
        set(value) {
            if (::prefs.isInitialized) {
                prefs.edit().putLong(KEY_USER_ID, value).apply()
            }
        }

    val isAuthenticated: Boolean get() = !jwtToken.isNullOrBlank()

    fun logout() {
        jwtToken = null
        currentUserId = 0L
    }

    // ─────────────────────────────────────────────────────────
    // HTTP Helpers
    // ─────────────────────────────────────────────────────────

    private fun authHeaders(): Headers.Builder {
        return Headers.Builder().apply {
            add("Content-Type", "application/json")
            jwtToken?.let { add("Authorization", "Bearer $it") }
        }
    }

    private fun get(path: String): Request {
        return Request.Builder()
            .url("$apiBase$path")
            .headers(authHeaders().build())
            .get()
            .build()
    }

    private fun post(path: String, body: Any?): Request {
        val json = if (body != null) gson.toJson(body) else "{}"
        return Request.Builder()
            .url("$apiBase$path")
            .headers(authHeaders().build())
            .post(json.toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun put(path: String, body: Any?): Request {
        val json = if (body != null) gson.toJson(body) else "{}"
        return Request.Builder()
            .url("$apiBase$path")
            .headers(authHeaders().build())
            .put(json.toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun delete(path: String): Request {
        return Request.Builder()
            .url("$apiBase$path")
            .headers(authHeaders().build())
            .delete()
            .build()
    }

    /**
     * Ejecuta la request y parsea la respuesta como ApiResponse<T>.
     * El backend siempre retorna { success: Boolean, data: T }
     */
    private inline fun <reified T> execute(request: Request): ApiResult<T> {
        return try {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val obj = JsonParser.parseString(bodyStr).asJsonObject
                    obj.get("error")?.asString ?: obj.get("message")?.asString ?: "Error ${response.code}"
                } catch (_: Exception) { "Error HTTP ${response.code}" }
                return ApiResult.Error(errorMsg, response.code)
            }

            val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
            val success = jsonObj.get("success")?.asBoolean ?: false

            if (!success) {
                val errorMsg = jsonObj.get("error")?.asString ?: "Unknown error"
                return ApiResult.Error(errorMsg, response.code)
            }

            val dataElement = jsonObj.get("data")
            if (dataElement == null || dataElement.isJsonNull) {
                // Para tipos que pueden ser null o Unit
                @Suppress("UNCHECKED_CAST")
                return ApiResult.Success(null as T)
            }

            val data: T = gson.fromJson(dataElement, object : TypeToken<T>() {}.type)
            ApiResult.Success(data)
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}", e)
            ApiResult.Error("Error de red: ${e.message}", 0)
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}", e)
            ApiResult.Error("Error: ${e.message}", 0)
        }
    }

    /** Versión que retorna List<T> */
    private inline fun <reified T> executeList(request: Request): ApiResult<List<T>> {
        return try {
            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: "{}"

            if (!response.isSuccessful) {
                val errorMsg = try {
                    val obj = JsonParser.parseString(bodyStr).asJsonObject
                    obj.get("error")?.asString ?: "Error ${response.code}"
                } catch (_: Exception) { "Error HTTP ${response.code}" }
                return ApiResult.Error(errorMsg, response.code)
            }

            val jsonObj = JsonParser.parseString(bodyStr).asJsonObject
            val dataElement = jsonObj.get("data")

            if (dataElement == null || dataElement.isJsonNull) {
                return ApiResult.Success(emptyList())
            }

            val listType = TypeToken.getParameterized(List::class.java, T::class.java).type
            val data: List<T> = gson.fromJson(dataElement, listType)
            ApiResult.Success(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error: ${e.message}", e)
            ApiResult.Error("Error: ${e.message}", 0)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // AUTH
    // ═══════════════════════════════════════════════════════════

    data class LoginRequest(val username: String, val password: String)
    data class RegisterRequest(
        val username: String,
        val password: String,
        val email: String,
        val personaId: Long? = null
    )
    data class AuthResponse(
        val token: String?,
        val user: JsonObject?
    )

    suspend fun login(username: String, password: String): ApiResult<AuthResponse> {
        val result = execute<AuthResponse>(post("/auth/login", LoginRequest(username, password)))
        if (result is ApiResult.Success && result.data?.token != null) {
            jwtToken = result.data.token
            result.data.user?.get("id")?.asLong?.let { currentUserId = it }
        }
        return result
    }

    suspend fun register(
        username: String,
        password: String,
        email: String,
        personaId: Long? = null
    ): ApiResult<AuthResponse> {
        val result = execute<AuthResponse>(
            post("/auth/register", RegisterRequest(username, password, email, personaId))
        )
        if (result is ApiResult.Success && result.data?.token != null) {
            jwtToken = result.data.token
            result.data.user?.get("id")?.asLong?.let { currentUserId = it }
        }
        return result
    }

    // ═══════════════════════════════════════════════════════════
    // USERS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMyProfile(): ApiResult<Usuario> = execute(get("/users/me"))

    suspend fun updateMyProfile(updates: Map<String, Any?>): ApiResult<Usuario> =
        execute(put("/users/me", updates))

    suspend fun searchUsers(query: String): ApiResult<List<Usuario>> =
        executeList(get("/users/search?q=$query"))

    suspend fun getUserById(id: Long): ApiResult<Usuario> =
        execute(get("/users/$id"))

    suspend fun getUserByUsername(username: String): ApiResult<Usuario> =
        execute(get("/users/by-username/$username"))

    suspend fun getUserByEmail(email: String): ApiResult<Usuario> =
        execute(get("/users/by-email/$email"))

    suspend fun getUsersByIds(ids: List<Long>): ApiResult<List<Usuario>> =
        executeList(get("/users/by-ids?ids=${ids.joinToString(",")}"))

    suspend fun registerFCMToken(token: String): ApiResult<JsonObject> =
        execute(post("/users/fcm-token", mapOf("fcmToken" to token)))

    suspend fun assignRole(userId: Long, roleId: Long): ApiResult<JsonObject> =
        execute(post("/users/$userId/role", mapOf("roleId" to roleId)))

    // ═══════════════════════════════════════════════════════════
    // PERSONAS
    // ═══════════════════════════════════════════════════════════

    suspend fun getPersonas(page: Int = 1, limit: Int = 50): ApiResult<List<Persona>> =
        executeList(get("/personas?page=$page&pageSize=$limit"))

    suspend fun getPersonaById(id: Long): ApiResult<Persona> =
        execute(get("/personas/$id"))

    suspend fun getPersonaByIdentificacion(identificacion: String): ApiResult<Persona> =
        execute(get("/personas/by-identificacion/$identificacion"))

    suspend fun createPersona(persona: Persona): ApiResult<Persona> =
        execute(post("/personas", persona))

    suspend fun updatePersona(id: Long, updates: Map<String, Any?>): ApiResult<Persona> =
        execute(put("/personas/$id", updates))

    suspend fun deletePersona(id: Long): ApiResult<JsonObject> =
        execute(delete("/personas/$id"))

    // ═══════════════════════════════════════════════════════════
    // ROLES
    // ═══════════════════════════════════════════════════════════

    suspend fun getRoles(): ApiResult<List<Rol>> =
        executeList(get("/roles"))

    suspend fun getRolById(id: Long): ApiResult<Rol> =
        execute(get("/roles/$id"))

    suspend fun getUserRoles(userId: Long): ApiResult<List<Long>> =
        executeList(get("/roles/user/$userId"))

    suspend fun isDocente(userId: Long): ApiResult<Boolean> =
        execute(get("/roles/user/$userId/is-docente"))

    suspend fun promoteToDocente(userId: Long): ApiResult<JsonObject> =
        execute(post("/roles/user/$userId/promote-docente", null))

    // ═══════════════════════════════════════════════════════════
    // RECURSOS
    // ═══════════════════════════════════════════════════════════

    suspend fun getRecursos(): ApiResult<List<Recurso>> =
        executeList(get("/roles/recursos/all"))

    suspend fun getRecursosByRol(rolId: Long): ApiResult<List<Recurso>> =
        executeList(get("/roles/recursos/by-rol/$rolId"))

    suspend fun getRolRecursos(): ApiResult<List<RolRecurso>> =
        executeList(get("/roles/rol-recursos/all"))

    suspend fun getRolRecursosByRol(rolId: Long): ApiResult<List<RolRecurso>> =
        executeList(get("/roles/rol-recursos/by-rol/$rolId"))

    // ═══════════════════════════════════════════════════════════
    // COURSES
    // ═══════════════════════════════════════════════════════════

    suspend fun getCourses(page: Int = 1, limit: Int = 50): ApiResult<List<Course>> =
        executeList(get("/courses?page=$page&pageSize=$limit"))

    suspend fun searchCourses(query: String): ApiResult<List<Course>> =
        executeList(get("/courses/search?q=$query"))

    suspend fun getFreeCourses(): ApiResult<List<Course>> =
        executeList(get("/courses/free"))

    suspend fun getCoursesByIds(ids: List<Long>): ApiResult<List<Course>> =
        executeList(get("/courses/by-ids?ids=${ids.joinToString(",")}"))

    suspend fun getCoursesByCreator(username: String): ApiResult<List<Course>> =
        executeList(get("/courses/creator/$username"))

    suspend fun getCoursesByCreatorId(userId: Long): ApiResult<List<Course>> =
        executeList(get("/courses/creator-id/$userId"))

    suspend fun getCourseById(id: Long): ApiResult<Course> =
        execute(get("/courses/$id"))

    suspend fun createCourse(course: Course): ApiResult<Course> =
        execute(post("/courses", course))

    suspend fun updateCourse(id: Long, updates: Map<String, Any?>): ApiResult<Course> =
        execute(put("/courses/$id", updates))

    suspend fun deleteCourse(id: Long): ApiResult<JsonObject> =
        execute(delete("/courses/$id"))

    // ═══════════════════════════════════════════════════════════
    // VIDEOS
    // ═══════════════════════════════════════════════════════════

    suspend fun getVideos(page: Int = 1, limit: Int = 50): ApiResult<List<VideoData>> =
        executeList(get("/videos?page=$page&pageSize=$limit"))

    suspend fun searchVideos(query: String): ApiResult<List<VideoData>> =
        executeList(get("/videos/search?q=$query"))

    suspend fun getVideosByCourseIds(courseIds: List<Long>): ApiResult<List<VideoData>> =
        executeList(get("/videos/by-courses?ids=${courseIds.joinToString(",")}"))

    suspend fun getVideosByCreator(username: String): ApiResult<List<VideoData>> =
        executeList(get("/videos/creator/$username"))

    suspend fun getVideosByCourse(courseId: Long): ApiResult<List<VideoData>> =
        executeList(get("/videos/course/$courseId"))

    suspend fun getVideoById(id: Long): ApiResult<VideoData> =
        execute(get("/videos/$id"))

    suspend fun createVideo(video: VideoData): ApiResult<VideoData> =
        execute(post("/videos", video))

    suspend fun updateVideo(id: Long, updates: Map<String, Any?>): ApiResult<VideoData> =
        execute(put("/videos/$id", updates))

    suspend fun deleteVideo(id: Long): ApiResult<JsonObject> =
        execute(delete("/videos/$id"))

    // ═══════════════════════════════════════════════════════════
    // TOPICS
    // ═══════════════════════════════════════════════════════════

    suspend fun getTopics(page: Int = 1, limit: Int = 50): ApiResult<List<Topic>> =
        executeList(get("/topics?page=$page&pageSize=$limit"))

    suspend fun getTopicsByCourse(courseId: Long): ApiResult<List<Topic>> =
        executeList(get("/topics/course/$courseId"))

    suspend fun getTopicById(id: Long): ApiResult<Topic> =
        execute(get("/topics/$id"))

    suspend fun createTopic(topic: Topic): ApiResult<Topic> =
        execute(post("/topics", topic))

    suspend fun updateTopic(id: Long, updates: Map<String, Any?>): ApiResult<Topic> =
        execute(put("/topics/$id", updates))

    suspend fun deleteTopic(id: Long): ApiResult<JsonObject> =
        execute(delete("/topics/$id"))

    // ═══════════════════════════════════════════════════════════
    // TASKS
    // ═══════════════════════════════════════════════════════════

    suspend fun getTasks(page: Int = 1, limit: Int = 50): ApiResult<List<Task>> =
        executeList(get("/tasks?page=$page&pageSize=$limit"))

    suspend fun getTasksByTopic(topicId: Long): ApiResult<List<Task>> =
        executeList(get("/tasks/topic/$topicId"))

    suspend fun getTasksByCourse(courseId: Long): ApiResult<List<Task>> =
        executeList(get("/tasks/course/$courseId"))

    suspend fun getTaskById(id: Long): ApiResult<Task> =
        execute(get("/tasks/$id"))

    suspend fun createTask(task: Task): ApiResult<Task> =
        execute(post("/tasks", task))

    suspend fun updateTask(id: Long, updates: Map<String, Any?>): ApiResult<Task> =
        execute(put("/tasks/$id", updates))

    suspend fun deleteTask(id: Long): ApiResult<JsonObject> =
        execute(delete("/tasks/$id"))

    // ═══════════════════════════════════════════════════════════
    // CONTENT ITEMS
    // ═══════════════════════════════════════════════════════════

    suspend fun getContentItemsByTask(taskId: Long): ApiResult<List<ContentItem>> =
        executeList(get("/content-items/task/$taskId"))

    suspend fun getContentItemById(id: Long): ApiResult<ContentItem> =
        execute(get("/content-items/$id"))

    suspend fun createContentItem(item: ContentItem): ApiResult<ContentItem> =
        execute(post("/content-items", item))

    suspend fun updateContentItem(id: Long, updates: Map<String, Any?>): ApiResult<ContentItem> =
        execute(put("/content-items/$id", updates))

    suspend fun deleteContentItem(id: Long): ApiResult<JsonObject> =
        execute(delete("/content-items/$id"))

    suspend fun deleteContentItemsByTask(taskId: Long): ApiResult<JsonObject> =
        execute(delete("/content-items/by-task/$taskId"))

    // ═══════════════════════════════════════════════════════════
    // SUBMISSIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMySubmissions(page: Int = 1, limit: Int = 20): ApiResult<List<TaskSubmission>> =
        executeList(get("/submissions/my?page=$page&pageSize=$limit"))

    suspend fun getSubmissionsByTask(taskId: Long, page: Int = 1, limit: Int = 50): ApiResult<List<TaskSubmission>> =
        executeList(get("/submissions/task/$taskId?page=$page&pageSize=$limit"))

    suspend fun getSubmissionByUserAndTask(taskId: Long, studentId: Long? = null): ApiResult<TaskSubmission> {
        val query = if (studentId != null) "?studentId=$studentId" else ""
        return execute(get("/submissions/task/$taskId/student$query"))
    }

    suspend fun getSubmissionsByCourse(courseId: Long, page: Int = 1, limit: Int = 50): ApiResult<List<TaskSubmission>> =
        executeList(get("/submissions/course/$courseId?page=$page&pageSize=$limit"))

    suspend fun getSubmissionById(id: Long): ApiResult<TaskSubmission> =
        execute(get("/submissions/$id"))

    suspend fun submitWork(submission: Map<String, Any?>): ApiResult<TaskSubmission> =
        execute(post("/submissions", submission))

    suspend fun gradeSubmission(id: Long, grade: Float, feedback: String?): ApiResult<TaskSubmission> =
        execute(put("/submissions/$id/grade", mapOf("grade" to grade, "feedback" to feedback)))

    suspend fun deleteSubmission(id: Long): ApiResult<JsonObject> =
        execute(delete("/submissions/$id"))

    // ═══════════════════════════════════════════════════════════
    // SUBSCRIPTIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMySubscriptions(): ApiResult<List<Subscription>> =
        executeList(get("/subscriptions/my"))

    suspend fun getMySubscriptionCount(): ApiResult<Int> =
        execute(get("/subscriptions/my/count"))

    suspend fun getMySubscribedCreators(): ApiResult<List<Long>> =
        executeList(get("/subscriptions/my/creators"))

    suspend fun getMySubscribers(): ApiResult<List<Subscription>> =
        executeList(get("/subscriptions/subscribers"))

    suspend fun checkSubscription(creatorId: Long): ApiResult<Boolean> =
        execute(get("/subscriptions/check/$creatorId"))

    suspend fun getSubscriberCount(creatorId: Long): ApiResult<Int> =
        execute(get("/subscriptions/count/$creatorId"))

    suspend fun subscribe(creatorId: Long): ApiResult<Subscription> =
        execute(post("/subscriptions/$creatorId", null))

    suspend fun unsubscribe(creatorId: Long): ApiResult<JsonObject> =
        execute(delete("/subscriptions/$creatorId"))

    // ═══════════════════════════════════════════════════════════
    // PROGRESS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMyProgress(): ApiResult<List<ProgresoEstudiante>> =
        executeList(get("/progress/my"))

    suspend fun getMyEnrolledCourseIds(): ApiResult<List<Long>> =
        executeList(get("/progress/my/enrolled-course-ids"))

    suspend fun getTopStudents(limit: Int = 10): ApiResult<List<JsonObject>> =
        executeList(get("/progress/top-students?limit=$limit"))

    suspend fun getProgressByCourse(courseId: Long): ApiResult<ProgresoEstudiante> =
        execute(get("/progress/course/$courseId"))

    suspend fun getAllProgressByCourse(courseId: Long): ApiResult<List<ProgresoEstudiante>> =
        executeList(get("/progress/course/$courseId/all"))

    suspend fun getEnrolledCount(courseId: Long): ApiResult<Int> =
        execute(get("/progress/course/$courseId/enrolled-count"))

    suspend fun isEnrolled(courseId: Long): ApiResult<Boolean> =
        execute(get("/progress/course/$courseId/enrolled"))

    suspend fun getLeaderboard(courseId: Long, limit: Int = 10): ApiResult<List<ProgresoEstudiante>> =
        executeList(get("/progress/leaderboard/$courseId?limit=$limit"))

    suspend fun upsertProgress(data: Map<String, Any?>): ApiResult<ProgresoEstudiante> =
        execute(post("/progress/upsert", data))

    suspend fun issueCertificate(courseId: Long, userId: Long? = null): ApiResult<ProgresoEstudiante> {
        val body = if (userId != null) mapOf("userId" to userId) else emptyMap<String, Any>()
        return execute(post("/progress/course/$courseId/certificate", body))
    }

    suspend fun updateCertificateUrl(courseId: Long, certificateUrl: String, userId: Long? = null): ApiResult<ProgresoEstudiante> {
        val body = mutableMapOf<String, Any?>("certificateUrl" to certificateUrl)
        if (userId != null) body["userId"] = userId
        return execute(put("/progress/course/$courseId/certificate-url", body))
    }

    // ═══════════════════════════════════════════════════════════
    // LIKES
    // ═══════════════════════════════════════════════════════════

    suspend fun getLikesByEntity(entityType: String, entityId: Long): ApiResult<JsonObject> =
        execute(get("/likes/count/$entityType/$entityId"))

    suspend fun toggleLike(entityType: String, entityId: Long): ApiResult<JsonObject> =
        execute(post("/likes/toggle", mapOf("entityType" to entityType, "entityId" to entityId)))

    suspend fun checkLike(entityType: String, entityId: Long): ApiResult<Boolean> =
        execute(get("/likes/check/$entityType/$entityId"))

    // ═══════════════════════════════════════════════════════════
    // COMMENTS
    // ═══════════════════════════════════════════════════════════

    suspend fun getCommentsByVideo(videoId: Long, page: Int = 1, limit: Int = 50): ApiResult<List<VideoComment>> =
        executeList(get("/comments/video/$videoId?page=$page&pageSize=$limit"))

    suspend fun getCommentCountByVideo(videoId: Long): ApiResult<JsonObject> =
        execute(get("/comments/video/$videoId/count"))

    suspend fun createComment(videoId: Long, comment: String, parentId: Long? = null): ApiResult<VideoComment> {
        val body = mutableMapOf<String, Any?>("videoId" to videoId, "comment" to comment)
        if (parentId != null) body["parentId"] = parentId
        return execute(post("/comments", body))
    }

    suspend fun updateComment(id: Long, comment: String): ApiResult<VideoComment> =
        execute(put("/comments/$id", mapOf("comment" to comment)))

    suspend fun deleteComment(id: Long): ApiResult<JsonObject> =
        execute(delete("/comments/$id"))

    // ═══════════════════════════════════════════════════════════
    // NOTIFICATIONS
    // ═══════════════════════════════════════════════════════════

    suspend fun getMyNotifications(page: Int = 1, limit: Int = 50): ApiResult<List<Notification>> =
        executeList(get("/notifications/my?page=$page&pageSize=$limit"))

    suspend fun getUnreadNotificationCount(): ApiResult<Int> =
        execute(get("/notifications/unread-count"))

    suspend fun markNotificationAsRead(id: Long): ApiResult<JsonObject> =
        execute(put("/notifications/$id/read", null))

    suspend fun markAllNotificationsAsRead(): ApiResult<JsonObject> =
        execute(put("/notifications/read-all", null))

    suspend fun sendNotification(
        userId: Long,
        title: String,
        message: String,
        type: String? = null,
        relatedId: Long? = null,
        senderUsername: String? = null
    ): ApiResult<JsonObject> {
        val body = mutableMapOf<String, Any?>(
            "userId" to userId,
            "title" to title,
            "message" to message
        )
        type?.let { body["type"] = it }
        relatedId?.let { body["relatedId"] = it }
        senderUsername?.let { body["senderUsername"] = it }
        return execute(post("/notifications/send", body))
    }

    suspend fun sendNotificationToMultiple(
        userIds: List<Long>,
        title: String,
        message: String,
        type: String? = null,
        data: Map<String, Any?>? = null
    ): ApiResult<JsonObject> {
        val body = mutableMapOf<String, Any?>(
            "userIds" to userIds,
            "title" to title,
            "message" to message
        )
        type?.let { body["type"] = it }
        data?.let { body["data"] = it }
        return execute(post("/notifications/send-multiple", body))
    }

    suspend fun deleteNotification(id: Long): ApiResult<JsonObject> =
        execute(delete("/notifications/$id"))

    // ═══════════════════════════════════════════════════════════
    // PAYMENTS
    // ═══════════════════════════════════════════════════════════

    suspend fun initiatePayment(request: Map<String, Any?>): ApiResult<JsonObject> =
        execute(post("/payment/initiate", request))

    suspend fun getPaymentStatus(transactionId: String): ApiResult<JsonObject> =
        execute(get("/payment/status/$transactionId"))

    suspend fun getMyTransactions(): ApiResult<List<JsonObject>> =
        executeList(get("/payment/my"))

    suspend fun getBanks(): ApiResult<List<JsonObject>> =
        executeList(get("/payment/banks"))

    suspend fun hasPurchasedCourse(courseId: Long): ApiResult<Boolean> =
        execute(get("/payment/purchased/$courseId"))

    // ═══════════════════════════════════════════════════════════
    // STORAGE (R2)
    // ═══════════════════════════════════════════════════════════

    suspend fun getUploadUrl(
        fileName: String,
        contentType: String,
        folder: String? = null
    ): ApiResult<JsonObject> {
        val body = mutableMapOf<String, Any?>(
            "fileName" to fileName,
            "contentType" to contentType
        )
        folder?.let { body["folder"] = it }
        return execute(post("/storage/upload-url", body))
    }

    suspend fun getDownloadUrl(key: String): ApiResult<JsonObject> =
        execute(get("/storage/download?key=$key"))

    suspend fun listStorageFiles(prefix: String? = null): ApiResult<List<JsonObject>> {
        val query = if (prefix != null) "?prefix=$prefix" else ""
        return executeList(get("/storage/list$query"))
    }

    suspend fun deleteStorageFile(key: String): ApiResult<JsonObject> =
        execute(delete("/storage?key=$key"))

    /**
     * Sube un archivo directamente al backend (multipart).
     * Útil para archivos pequeños; para grandes usar getUploadUrl + upload directo a R2.
     */
    suspend fun uploadFile(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        folder: String? = null
    ): ApiResult<JsonObject> {
        return try {
            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file", fileName,
                    fileBytes.toRequestBody(mimeType.toMediaType())
                )
                .apply { folder?.let { addFormDataPart("folder", it) } }
                .build()

            val request = Request.Builder()
                .url("$apiBase/storage/upload")
                .headers(Headers.Builder().apply {
                    jwtToken?.let { add("Authorization", "Bearer $it") }
                }.build())
                .post(multipartBody)
                .build()

            execute(request)
        } catch (e: Exception) {
            ApiResult.Error("Upload error: ${e.message}", 0)
        }
    }

    // ═══════════════════════════════════════════════════════════
    // FILE CONTEXTS
    // ═══════════════════════════════════════════════════════════

    suspend fun getFileContexts(): ApiResult<List<FileContext>> =
        executeList(get("/file-contexts"))

    suspend fun getFileContextsBySubmission(submissionId: Long): ApiResult<List<FileContext>> =
        executeList(get("/file-contexts/submission/$submissionId"))

    suspend fun getFileContextById(id: Long): ApiResult<FileContext> =
        execute(get("/file-contexts/$id"))

    suspend fun createFileContext(fileContext: Map<String, Any?>): ApiResult<FileContext> =
        execute(post("/file-contexts", fileContext))

    suspend fun deleteFileContext(id: Long): ApiResult<JsonObject> =
        execute(delete("/file-contexts/$id"))

    // ═══════════════════════════════════════════════════════════
    // REINFORCEMENT (Question Sessions / History)
    // ═══════════════════════════════════════════════════════════

    suspend fun saveReinforcementSession(
        courseId: Long,
        questions: List<Map<String, Any?>>
    ): ApiResult<JsonObject> {
        val body = mapOf("courseId" to courseId, "questions" to questions)
        return execute(post("/reinforcement/session", body))
    }

    suspend fun getReinforcementHistory(courseId: Long): ApiResult<List<JsonObject>> =
        executeList(get("/reinforcement/history/course/$courseId"))

    suspend fun getAllReinforcementHistory(): ApiResult<List<JsonObject>> =
        executeList(get("/reinforcement/history"))
}

// ═══════════════════════════════════════════════════════════
// Result wrapper
// ═══════════════════════════════════════════════════════════

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int) : ApiResult<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.data
    fun errorMessage(): String = (this as? Error)?.message ?: ""

    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
    }

    inline fun onSuccess(action: (T) -> Unit): ApiResult<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (String, Int) -> Unit): ApiResult<T> {
        if (this is Error) action(message, code)
        return this
    }
}
