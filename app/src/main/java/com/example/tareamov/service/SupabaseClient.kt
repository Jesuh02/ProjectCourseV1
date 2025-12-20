package com.example.tareamov.service

import com.example.tareamov.BuildConfig
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.FieldNamingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.lang.Exception
import java.util.concurrent.TimeUnit
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.data.entity.ChatMessage
import com.example.tareamov.data.entity.FileContext
import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.Rol
import com.example.tareamov.data.entity.Recurso
import com.example.tareamov.data.entity.RolRecurso

object SupabaseClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    // Gson configured to map snake_case JSON (typical Postgres/Supabase) to camelCase Kotlin fields
    private val underscoredGson = GsonBuilder().setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create()
    // Fallback public Supabase project URL (useful when developer hasn't populated local.properties).
    // NOTE: keep this value in sync with your Supabase project if you want the app to reach a specific instance.
    private const val DEFAULT_SUPABASE_URL = "https://vxuksizvwrkctrvpciyp.supabase.co"

    // Raw values from BuildConfig (may be empty when local.properties isn't set on the developer machine)
    private val rawBaseUrl = BuildConfig.SUPABASE_URL.trim()
    // Use provided URL when available, otherwise fall back to DEFAULT_SUPABASE_URL
    private val baseUrl = if (rawBaseUrl.isNotEmpty()) rawBaseUrl.trimEnd('/') else DEFAULT_SUPABASE_URL
    private val apiKey = BuildConfig.SUPABASE_KEY
    // Optional: runtime-injected API key (useful when BuildConfig wasn't populated)
    @Volatile
    private var runtimeApiKey: String? = null

    /**
     * Call this at app startup if you want to inject the Supabase key at runtime
     * (for example, read from secure storage, assets, or an environment variable).
     */
    fun setApiKeyAtRuntime(key: String?) {
        runtimeApiKey = key?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun clearRuntimeApiKey() { runtimeApiKey = null }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // Optional listener that will be notified with the full request URL when the client
    // builds a GET request. The listener is invoked on the caller thread, so UI callers
    // should marshal to the main thread when updating views.
    @Volatile
    private var requestListener: ((String) -> Unit)? = null

    /**
     * Register a listener to receive Supabase request URLs (mostly useful for debugging).
     * Pass null to unregister.
     */
    fun setRequestListener(listener: ((String) -> Unit)?) {
        requestListener = listener
    }

    private fun effectiveApiKey(): String {
        val b = apiKey.trim()
        if (b.isNotEmpty()) return b
        val r = runtimeApiKey?.trim()
        if (!r.isNullOrEmpty()) return r
        // Try common JVM/Android fallbacks
        val env = try { System.getenv("SUPABASE_KEY") } catch (_: Throwable) { null }
        if (!env.isNullOrEmpty()) return env
        val prop = try { System.getProperty("SUPABASE_KEY") } catch (_: Throwable) { null }
        if (!prop.isNullOrEmpty()) return prop
        return ""
    }

    fun isConfigured(): Boolean {
        val url = baseUrl.trim()
        val key = effectiveApiKey().trim()
        val configured = key.isNotEmpty() && url.isNotEmpty()
        if (!configured) {
            try {
                val usingDefault = rawBaseUrl.isEmpty()
                val maskedUrl = if (url.length <= 24) url else url.substring(0, 24) + "..."
                val maskedKey = if (key.isEmpty()) "(missing)" else if (key.length <= 8) "(hidden)" else key.substring(0, 6) + "..." + key.takeLast(4)
                if (usingDefault) {
                    android.util.Log.w("SupabaseClient", "Supabase URL not provided in BuildConfig; using default fallback URL. Effective SUPABASE_URL=$maskedUrl SUPABASE_KEY=$maskedKey HOST_IP=${BuildConfig.HOST_IP}. If this is unintended, set SUPABASE_URL in local.properties and rebuild.")
                } else {
                    android.util.Log.w("SupabaseClient", "Supabase not configured: SUPABASE_URL=$maskedUrl SUPABASE_KEY=$maskedKey HOST_IP=${BuildConfig.HOST_IP}. Check local.properties and rebuild.")
                }
            } catch (t: Throwable) {
                // ignore logging failures
            }
        }
        return configured
    }

    /**
     * Generic POST request helper to reduce code duplication
     * @param table The table name (e.g., "personas", "usuarios")
     * @param payload The data to insert as a Map
     * @return The ID of the inserted record, or null on failure
     */
    private suspend fun insertRecord(table: String, payload: Map<String, Any?>): Long? = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(payload).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/$table"
            val key = effectiveApiKey()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
                    Log.e("SupabaseClient", "insertRecord failed for $table: ${resp.code} ${resp.message} body=$bodyStr")
                    return@withContext null
                }

                if (respBody.isNullOrEmpty()) return@withContext null

                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                    if (jsonArray.size() > 0) {
                        return@withContext jsonArray[0].asJsonObject.get("id")?.asLong
                    }
                } catch (e: Exception) {
                    Log.w("SupabaseClient", "Failed to parse ID from response for $table", e)
                }

                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception during insertRecord for $table", e)
            return@withContext null
        }
    }

    /**
     * Generic PATCH request helper to reduce code duplication
     * @param table The table name
     * @param id The record ID to update
     * @param payload The data to update
     * @return true on success, false on failure
     */
    private suspend fun updateRecord(table: String, id: Long, payload: Map<String, Any?>): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = gson.toJson(payload).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/$table?id=eq.$id"
            val key = effectiveApiKey()

            val request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("SupabaseClient", "updateRecord failed for $table id=$id: ${resp.code} ${resp.message}")
                    return@withContext false
                }
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception during updateRecord for $table id=$id", e)
            return@withContext false
        }
    }

    // Update an existing Task by id. Returns true on success.
    suspend fun updateTask(task: com.example.tareamov.data.entity.Task): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "updateTask called: id=${task.id}, topicId=${task.topicId}, name='${task.name}', desc='${task.description}'")
            
            val map = mutableMapOf<String, Any?>()
            if (task.topicId != 0L) map["topic_id"] = task.topicId
            map["title"] = task.name
            map["description"] = task.description

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/tasks?id=eq.${task.id}"
            
            Log.d("SupabaseClient", "updateTask URL: $url")
            Log.d("SupabaseClient", "updateTask body: ${gson.toJson(map)}")

            val request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.e("SupabaseClient", "updateTask failed: ${resp.code} ${resp.message} body=$bodyStr")
                    return@withContext false
                }
                Log.d("SupabaseClient", "updateTask success: ${resp.code} body=$bodyStr")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "updateTask exception", e)
            return@withContext false
        }
    }

    suspend fun insertPersona(persona: Persona): Long? {
        val payload = mapOf(
            "identificacion" to persona.identificacion,
            "nombres" to persona.nombres,
            "apellidos" to persona.apellidos,
            "telefono" to persona.telefono,
            "direccion" to persona.direccion,
            "fecha_nacimiento" to persona.fechaNacimiento
        )
        return insertRecord("personas", payload)
    }

    suspend fun insertUsuario(usuario: Usuario): Long? {
        Log.d("SupabaseClient", "insertUsuario called for username: ${usuario.usuario}, email: ${usuario.email}, persona_id: ${usuario.persona_id}")
        // Only include fields that exist in the Supabase usuarios table
        val payload = mapOf(
            "username" to usuario.usuario,
            "contrasena" to usuario.contrasena,
            "persona_id" to usuario.persona_id,
            "email" to usuario.email,
            "avatar" to usuario.avatar,
            "is_active" to usuario.isActive
            // Note: email_verified, last_login, created_at are managed by Supabase or don't exist in remote table
        )
        val result = insertRecord("usuarios", payload)
        Log.d("SupabaseClient", "insertUsuario result for ${usuario.usuario}: $result")
        return result
    }

    suspend fun insertVideo(video: com.example.tareamov.data.entity.VideoData): Long? = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>(
                // NO incluir username - se obtiene desde courses via course_id
                "description" to video.description,
                "title" to video.title,
                "video_uri_string" to video.videoUriString,
                "local_file_path" to video.localFilePath,
                "timestamp" to video.timestamp,
                "is_paid" to video.isPaid,
                "thumbnail_uri" to video.thumbnailUri,
                "price" to video.price
            )
            
            // Include course_id if provided
            if (video.courseId != null) {
                map["course_id"] = video.courseId
            }
            
            // Include id if provided (for manual ID assignment)
            if (video.id > 0) {
                map["id"] = video.id
            }

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/videos"

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
                    Log.e("SupabaseClient", "insertVideo failed: ${resp.code} ${resp.message} body=$bodyStr")
                    throw Exception("Supabase insertVideo failed: ${resp.code} ${resp.message} body=$bodyStr")
                }

                if (respBody.isNullOrEmpty()) {
                    Log.w("SupabaseClient", "insertVideo returned empty response")
                    return@withContext null
                }

                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                    if (jsonArray.size() > 0) {
                        val idElem = jsonArray[0].asJsonObject.get("id")
                        val returnedId = idElem?.asLong
                        Log.d("SupabaseClient", "insertVideo success: video ID = $returnedId")
                        return@withContext returnedId
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseClient", "Error parsing insertVideo response", e)
                    e.printStackTrace()
                }

                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception in insertVideo", e)
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun insertCourse(course: com.example.tareamov.data.entity.Course): Long? = withContext(Dispatchers.IO) {
        try {
            // Build map with creator_user_id as foreign key (NOT NULL required)
            val map = mapOf(
                "title" to course.title,
                "description" to course.description,
                "creator_user_id" to course.creatorUserId, // Required FK to usuarios.id
                "thumbnail_uri" to course.thumbnailUri,
                "video_uri" to course.videoUri,
                "local_file_path" to course.localFilePath,
                "duration" to course.duration,
                "category" to course.category,
                "price" to course.price,
                "is_premium" to course.isPremium,
                "is_published" to course.isPublished,
                "creation_date" to course.creationDate,
                "last_modified_date" to course.lastModifiedDate,
                "enrollment_count" to course.enrollmentCount,
                "rating" to course.rating,
                "tags" to course.tags,
                "timestamp" to course.timestamp
            )

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/courses"

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
                    throw Exception("Supabase insertCourse failed: ${'$'}{resp.code} ${'$'}{resp.message} body=$bodyStr")
                }

                if (respBody.isNullOrEmpty()) return@withContext null

                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                    if (jsonArray.size() > 0) {
                        val idElem = jsonArray[0].asJsonObject.get("id")
                        return@withContext idElem?.asLong
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                return@withContext null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    // Update a course by id (PATCH). Returns true on success.
    suspend fun updateCourseById(id: Long, course: com.example.tareamov.data.entity.Course): Boolean = withContext(Dispatchers.IO) {
        try {
            val map = mapOf(
                "title" to course.title,
                "description" to course.description,
                "creator_user_id" to course.creatorUserId,
                "thumbnail_uri" to course.thumbnailUri,
                "video_uri" to course.videoUri,
                "local_file_path" to course.localFilePath,
                "duration" to course.duration,
                "category" to course.category,
                "price" to course.price,
                "is_premium" to course.isPremium,
                "is_published" to course.isPublished,
                "creation_date" to course.creationDate,
                "last_modified_date" to course.lastModifiedDate,
                "enrollment_count" to course.enrollmentCount,
                "rating" to course.rating,
                "tags" to course.tags,
                "timestamp" to course.timestamp
            )

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            // Use proper Kotlin string interpolation so the numeric id is embedded in the URL
            val url = "$baseUrl/rest/v1/courses?id=eq.${id}"

            val request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "updateCourseById failed: ${resp.code} ${resp.message} body=$bodyStr")
                    return@withContext false
                }
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "updateCourseById exception", e)
            return@withContext false
        }
    }

    // Delete a course by id
    suspend fun deleteCourseById(id: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // Embed the numeric id value directly into the request URL
            val url = "$baseUrl/rest/v1/courses?id=eq.${id}"
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
                    Log.w("SupabaseClient", "deleteCourseById failed: ${resp.code} ${resp.message} body=$bodyStr")
                    return@withContext false
                }
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "deleteCourseById exception", e)
            return@withContext false
        }
    }

    suspend fun insertTopic(topic: com.example.tareamov.data.entity.Topic): Long? = withContext(Dispatchers.IO) {
        try {
            val map = mapOf(
                "course_id" to topic.courseId,
                "name" to topic.name,
                "description" to topic.description,
                "order_index" to topic.orderIndex
            )

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/topics"

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
                    Log.w("SupabaseClient", "insertTopic failed: ${'$'}{resp.code} ${'$'}{resp.message} body=$bodyStr")
                    return@withContext null
                }

                if (respBody.isNullOrEmpty()) return@withContext null

                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                    if (jsonArray.size() > 0) {
                        val idElem = jsonArray[0].asJsonObject.get("id")
                        return@withContext idElem?.asLong
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseClient","insertTopic parse error", e)
                }

                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient","insertTopic error", e)
            return@withContext null
        }
    }

    // Insert a Task (belongs to a Topic) with optional creator metadata
    // NOTE: Creator fields are accepted but ignored since tasks table doesn't have those columns
    suspend fun insertTask(
        task: com.example.tareamov.data.entity.Task,
        creatorUsername: String? = null,
        creatorUserId: Long? = null
    ): Long? = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.w("SupabaseClient", "insertTask skipped: Supabase not configured")
            return@withContext null
        }

        if (task.topicId <= 0) {
            Log.w("SupabaseClient", "insertTask skipped: invalid topicId=${task.topicId}")
            return@withContext null
        }

        val sanitizedTitle = task.name.trim()
        if (sanitizedTitle.isEmpty()) {
            Log.w("SupabaseClient", "insertTask skipped: empty task title")
            return@withContext null
        }

        // Build payload with ONLY fields that exist in tasks table
        // Note: tasks table does NOT have order_index column (only topics does)
        val payload = mutableMapOf<String, Any?>(
            "topic_id" to task.topicId,
            "title" to sanitizedTitle
        )
        
        // Add optional fields that exist in Supabase tasks table
        if (!task.description.isNullOrBlank()) {
            payload["description"] = task.description
        }

        val insertUrl = "$baseUrl/rest/v1/tasks"

        try {
            val key = effectiveApiKey()
            val bodyJson = gson.toJson(payload)
            
            Log.d("SupabaseClient", "📤 Attempting insertTask: topicId=${task.topicId}, title='$sanitizedTitle', payload=$bodyJson")
            
            val request = Request.Builder()
                .url(insertUrl)
                .post(bodyJson.toRequestBody(jsonMedia))
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            requestListener?.invoke("POST $insertUrl")
            
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                
                Log.d("SupabaseClient", "📥 insertTask response: code=${resp.code}, body=${respBody?.take(500)}")
                
                if (resp.isSuccessful) {
                    // Try to parse the returned ID
                    parseTaskIdFromResponse(respBody)?.let { 
                        Log.i("SupabaseClient", "✅ Task inserted successfully with id=$it")
                        return@withContext it 
                    }

                    // Fallback: query for the task we just created
                    val fallbackId = fetchTaskIdByTopicAndTitle(task.topicId, sanitizedTitle)
                    if (fallbackId != null) {
                        Log.i("SupabaseClient", "✅ Task found after insert with id=$fallbackId")
                        return@withContext fallbackId
                    }

                    Log.w("SupabaseClient", "⚠️ insertTask succeeded but couldn't get ID for topicId=${task.topicId} title='$sanitizedTitle'")
                    return@withContext null
                }

                // Handle duplicate key error
                if (resp.code == 409 || (respBody?.contains("duplicate key", ignoreCase = true) == true)) {
                    Log.w("SupabaseClient", "⚠️ Duplicate task detected, attempting to fetch existing")
                    val fallbackId = fetchTaskIdByTopicAndTitle(task.topicId, sanitizedTitle)
                    if (fallbackId != null) return@withContext fallbackId
                }

                Log.e(
                    "SupabaseClient",
                    "❌ insertTask failed: code=${resp.code}, body=${respBody ?: "<empty>"}"
                )
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ insertTask exception for topicId=${task.topicId} title='$sanitizedTitle'", e)
            return@withContext null
        }
    }

    private fun parseTaskIdFromResponse(body: String?): Long? {
        if (body.isNullOrBlank()) return null
        return try {
            val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
            if (jsonArray.size() == 0) return null
            val obj = jsonArray[0].asJsonObject
            obj.get("id")?.asLong
        } catch (e: Exception) {
            Log.w("SupabaseClient", "parseTaskIdFromResponse failed", e)
            null
        }
    }

    private fun bodySuggestsMissingCreatorColumns(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val lower = body.lowercase()
        return lower.contains("creator_username") || lower.contains("creator_user_id")
    }

    private suspend fun fetchTaskIdByTopicAndTitle(topicId: Long, title: String): Long? {
        if (topicId <= 0 || title.isBlank()) return null
        return try {
            // PostgREST expects text values without URL encoding for the filter value itself
            // Use direct string interpolation for topic_id (numeric) and title (text)
            val escapedTitle = title.replace("'", "''") // Escape single quotes for SQL
            val encodedTitle = java.net.URLEncoder.encode(escapedTitle, "UTF-8")
            val path = "tasks?topic_id=eq.$topicId&title=eq.$encodedTitle&select=id&limit=1"
            
            Log.d("SupabaseClient", "🔍 Fetching task: topicId=$topicId, title='$title', path=$path")
            
            client.newCall(buildGetRequest(path)).execute().use { resp ->
                val body = resp.body?.string()
                
                Log.d("SupabaseClient", "🔍 Response: code=${resp.code}, body=$body")
                
                if (!resp.isSuccessful || body.isNullOrEmpty()) return@use null
                val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                if (arr.size() == 0) return@use null
                arr[0].asJsonObject.get("id")?.asLong
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ fetchTaskIdByTopicAndTitle failed for topicId=$topicId title='$title'", e)
            null
        }
    }

    // Insert a ContentItem (belongs to a Task/Topic)
    suspend fun insertContentItem(contentItem: com.example.tareamov.data.entity.ContentItem): Long? = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "📤 ========== INSERT CONTENT ITEM ==========")
            Log.d("SupabaseClient", "📤 Input ContentItem:")
            Log.d("SupabaseClient", "📤   - topicId: ${contentItem.topicId}")
            Log.d("SupabaseClient", "📤   - taskId: ${contentItem.taskId}")
            Log.d("SupabaseClient", "📤   - title: '${contentItem.title}'")
            Log.d("SupabaseClient", "📤   - body: '${contentItem.body}'")
            Log.d("SupabaseClient", "📤   - contentType: '${contentItem.contentType}'")
            Log.d("SupabaseClient", "📤   - orderIndex: ${contentItem.orderIndex}")
            
            // Map local ContentItem fields to Supabase content_items columns (title/body)
            val map = mutableMapOf<String, Any?>(
                "topic_id" to contentItem.topicId,
                "title" to (contentItem.title ?: ""),
                "body" to contentItem.body,
                "content_type" to contentItem.contentType,
                "order_index" to (contentItem.orderIndex ?: 0)
            )
            
            // Only include task_id if it's not null and greater than 0
            if (contentItem.taskId != null && contentItem.taskId!! > 0) {
                map["task_id"] = contentItem.taskId!!
                Log.d("SupabaseClient", "📤 Including task_id=${contentItem.taskId}")
            } else {
                // Don't include task_id at all for topic content (let DB use default null)
                Log.d("SupabaseClient", "📤 NOT including task_id (topic content)")
            }
            
            // Add creator fields if available
            if (contentItem.creator_usuario_id != null && contentItem.creator_usuario_id!! > 0) {
                map["creator_usuario_id"] = contentItem.creator_usuario_id!!
            }
            if (!contentItem.creator_username.isNullOrBlank()) {
                map["creator_username"] = contentItem.creator_username!!
            }

            val jsonPayload = gson.toJson(map)
            Log.d("SupabaseClient", "📤 JSON payload: $jsonPayload")
            
            val body = jsonPayload.toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/content_items"
            
            Log.d("SupabaseClient", "📤 Sending POST to: $url")

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                Log.d("SupabaseClient", "📤 Response code: ${resp.code}, message: ${resp.message}")
                Log.d("SupabaseClient", "📤 Response body: $respBody")
                
                if (!resp.isSuccessful) {
                    Log.e("SupabaseClient", "❌ insertContentItem failed: ${resp.code} ${resp.message} body=$respBody")
                    return@withContext null
                }

                if (respBody.isNullOrEmpty()) {
                    Log.e("SupabaseClient", "❌ insertContentItem: Empty response body")
                    return@withContext null
                }

                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                    if (jsonArray.size() > 0) {
                        val insertedObj = jsonArray[0].asJsonObject
                        val idElem = insertedObj.get("id")?.asLong
                        val insertedTopicId = insertedObj.get("topic_id")?.asLong
                        Log.d("SupabaseClient", "✅ ContentItem inserted successfully!")
                        Log.d("SupabaseClient", "✅   - Returned id: $idElem")
                        Log.d("SupabaseClient", "✅   - Returned topic_id: $insertedTopicId")
                        Log.d("SupabaseClient", "📤 ========== END INSERT ==========")
                        return@withContext idElem
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseClient","❌ insertContentItem parse error", e)
                }

                Log.d("SupabaseClient", "📤 ========== END INSERT ==========")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient","❌ insertContentItem error", e)
            return@withContext null
        }
    }

    // Update an existing Persona by id. Returns true on success.
    suspend fun updatePersona(persona: Persona): Boolean = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>()
            map["identificacion"] = persona.identificacion
            map["nombres"] = persona.nombres
            map["apellidos"] = persona.apellidos
            map["telefono"] = persona.telefono
            map["direccion"] = persona.direccion
            map["fecha_nacimiento"] = persona.fechaNacimiento

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/personas?id=eq.${persona.id}"

            val request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    android.util.Log.w("SupabaseClient", "updatePersona failed status=${resp.code} body=${resp.body?.string()}")
                    return@withContext false
                }
                return@withContext true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    // Update an existing Usuario by id. Returns true on success.
    suspend fun updateUsuario(usuario: Usuario): Boolean = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>()
            map["username"] = usuario.usuario
            map["contrasena"] = usuario.contrasena
            map["persona_id"] = usuario.persona_id
            map["email"] = usuario.email
            map["avatar"] = usuario.avatar
            map["is_active"] = usuario.isActive
            // Note: email_verified, last_login, created_at are managed by Supabase or don't exist in remote table

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/usuarios?id=eq.${usuario.id}"

            val request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "updateUsuario failed status=${resp.code} body=${resp.body?.string()}")
                    return@withContext false
                }
                Log.d("SupabaseClient", "updateUsuario success for id: ${usuario.id}")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "updateUsuario exception", e)
            return@withContext false
        }
    }

    suspend fun insertTaskSubmission(submission: com.example.tareamov.data.entity.TaskSubmission): Long? = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                android.util.Log.e("SupabaseClient", "❌ Supabase not configured, cannot insert task submission")
                return@withContext null
            }
            
            // Validate required fields
            if (submission.taskId == 0L) {
                android.util.Log.e("SupabaseClient", "❌ Invalid taskId (0) for task submission")
                return@withContext null
            }
            
            if (submission.studentId == 0L) {
                android.util.Log.e("SupabaseClient", "❌ Invalid studentId (0) for task submission")
                return@withContext null
            }
            
            // ⚠️ VERIFICAR SI YA EXISTE UNA ENTREGA PARA ESTE ESTUDIANTE Y TAREA
            // Esto evita duplicados en la base de datos
            val existingSubmissions = fetchTaskSubmissionsByTaskAndStudentId(submission.taskId, submission.studentId)
            if (existingSubmissions.isNotEmpty()) {
                android.util.Log.w("SupabaseClient", "⚠️ Ya existe una entrega para taskId=${submission.taskId} y studentId=${submission.studentId}. Retornando ID existente.")
                val existingId = existingSubmissions.first().id
                android.util.Log.i("SupabaseClient", "📋 Usando entrega existente con id=$existingId")
                return@withContext existingId
            }
            
            // Do NOT send local 'id' to server - let Postgres sequence generate primary key
            // Defensive check: avoid creating duplicate task submissions for same (taskId, studentId)
            try {
                val existing = fetchTaskSubmissionByTaskId(submission.taskId, submission.studentId)
                if (existing != null) {
                    android.util.Log.i("SupabaseClient", "🔎 Found existing remote submission for task=${submission.taskId} student=${submission.studentId} id=${existing.id} - skipping insert")
                    return@withContext existing.id
                }
            } catch (t: Exception) {
                android.util.Log.w("SupabaseClient", "Warning: failed to check existing submission before insert: ${t.message}")
            }

            val map = mutableMapOf<String, Any?>()
            if (submission.id != null && submission.id != 0L) {
                android.util.Log.w("SupabaseClient", "Not sending local id=${submission.id} to server for task_submissions (will let DB assign id)")
            }
            map["task_id"] = submission.taskId
            // Note: task_submissions table only has student_id (integer), not student_username
            map["student_id"] = submission.studentId
            map["file_uri"] = submission.fileUri
            map["file_name"] = submission.fileName
            map["submission_date"] = submission.submissionDate
            
            // CRÍTICO: NO incluir grade ni feedback en el INSERT inicial
            // El trigger de PostgreSQL intenta calcular progreso y usa round() que falla con double precision
            // Al omitir grade completamente, evitamos que el trigger intente procesar ese campo
            // La columna grade tiene DEFAULT NULL en la tabla, así que no es necesario enviarlo
            // SOLO incluir grade cuando se actualice después con una calificación real

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            // Log the outgoing payload for debugging FK errors (avoid logging secrets)
            android.util.Log.d("SupabaseClient", "📤 insertTaskSubmission payload: ${gson.toJson(map)}")
            val url = "$baseUrl/rest/v1/task_submissions"

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                // Request the inserted row back in the response
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                var respBody = resp.body?.string()
                android.util.Log.d("SupabaseClient", "📥 insertTaskSubmission response: code=${resp.code}, body=${respBody?.take(500)}")
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
                    android.util.Log.e("SupabaseClient", "❌ insertTaskSubmission failed: code=${resp.code} message=${resp.message}")
                    android.util.Log.e("SupabaseClient", "❌ Response body: $bodyStr")
                    
                    // Detectar errores específicos de RLS (Row Level Security)
                    if (bodyStr.contains("42501") || bodyStr.contains("row-level security policy", ignoreCase = true)) {
                        android.util.Log.e("SupabaseClient", "🔒 RLS POLICY ERROR: Las políticas de seguridad de Supabase están bloqueando el INSERT")
                        android.util.Log.e("SupabaseClient", "🔒 Solución: Ejecutar el script supabase/migrations/20251120_fix_rls_policies.sql en Supabase SQL Editor")
                        android.util.Log.e("SupabaseClient", "🔒 Usuario ID: ${submission.studentId}, TaskId: ${submission.taskId}")
                    }

                    // If Postgres sequence is out-of-sync, PostgREST may return 23505 duplicate key error.
                    if (bodyStr.contains("23505") || bodyStr.contains("duplicate key value violates unique constraint", ignoreCase = true)) {
                        try {
                            // Compute safe next id by inspecting remote rows
                            var maxId: Long = 0L
                            try {
                                val all = fetchTaskSubmissions()
                                for (s in all) {
                                    val idv = s.id ?: 0L
                                    if (idv > maxId) maxId = idv
                                }
                            } catch (t: Exception) {
                                android.util.Log.w("SupabaseClient", "Failed to compute max id for task_submissions: ${t.message}")
                            }

                            val nextId = maxId + 1
                            android.util.Log.w("SupabaseClient", "Detected duplicate-pkey on insert. Retrying with computed id=$nextId")

                            // Retry once with explicit id
                            map["id"] = nextId
                            val retryBody = gson.toJson(map).toRequestBody(jsonMedia)
                            val retryReq = Request.Builder()
                                .url(url)
                                .post(retryBody)
                                .addHeader("apikey", apiKey)
                                .addHeader("Authorization", "Bearer $apiKey")
                                .addHeader("Accept", "application/json")
                                .addHeader("Content-Type", "application/json")
                                .addHeader("Prefer", "return=representation")
                                .build()

                            client.newCall(retryReq).execute().use { r2 ->
                                respBody = r2.body?.string()
                                if (!r2.isSuccessful) {
                                    val b2 = respBody ?: ""
                                    throw Exception("Supabase insertTaskSubmission retry failed: ${r2.code} ${r2.message} body=$b2")
                                }

                                if (respBody.isNullOrEmpty()) return@withContext null
                                try {
                                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                                    if (jsonArray.size() > 0) {
                                        val idElem = jsonArray[0].asJsonObject.get("id")
                                        return@withContext idElem?.asLong
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        } catch (t: Exception) {
                            t.printStackTrace()
                            throw Exception("Supabase insertTaskSubmission failed and retry recovery failed: $bodyStr")
                        }
                    }

                    throw Exception("Supabase insertTaskSubmission failed: ${resp.code} ${resp.message} body=$bodyStr")
                }

                if (respBody.isNullOrEmpty()) {
                    android.util.Log.w("SupabaseClient", "⚠️ insertTaskSubmission succeeded but response body is empty")
                    return@withContext null
                }

                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                    if (jsonArray.size() > 0) {
                        val idElem = jsonArray[0].asJsonObject.get("id")
                        val insertedId = idElem?.asLong
                        android.util.Log.i("SupabaseClient", "✅ Task submission inserted successfully with id=$insertedId")
                        return@withContext insertedId
                    }
                } catch (e: Exception) {
                    android.util.Log.e("SupabaseClient", "❌ Failed to parse insertTaskSubmission response", e)
                    e.printStackTrace()
                }

                return@withContext null
            }
        } catch (e: Exception) {
            android.util.Log.e("SupabaseClient", "❌ Exception in insertTaskSubmission: ${e.message}", e)
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun insertFileContext(fileContext: com.example.tareamov.data.entity.FileContext): Long? = withContext(Dispatchers.IO) {
        try {
            // Do NOT send local 'id' to server - let Postgres sequence generate primary key
            val map = mutableMapOf<String, Any?>()
            if (fileContext.id != 0L) {
                Log.w("SupabaseClient", "Not sending local id=${fileContext.id} to server for file_contexts (will let DB assign id)")
            }
            map["submission_id"] = fileContext.submissionId
            map["file_name"] = fileContext.fileName
            map["file_type"] = fileContext.fileType
            map["file_content"] = fileContext.fileContent
            map["extracted_text"] = fileContext.extractedText
            map["metadata"] = fileContext.metadata
            map["created_at"] = fileContext.createdAt
            map["json_content"] = fileContext.jsonContent
            map["content_summary"] = fileContext.contentSummary

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            Log.d("SupabaseClient", "insertFileContext payload: submissionId=${fileContext.submissionId}, fileName=${fileContext.fileName}, fileType=${fileContext.fileType}")
            val url = "$baseUrl/rest/v1/file_contexts"

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val responseBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    Log.e("SupabaseClient", "insertFileContext failed: ${resp.code} ${resp.message}")
                    Log.e("SupabaseClient", "Response body: $responseBody")
                    
                    // Detectar errores específicos de RLS (Row Level Security)
                    if (responseBody.contains("42501") || responseBody.contains("row-level security policy", ignoreCase = true)) {
                        Log.e("SupabaseClient", "🔒 RLS POLICY ERROR: Las políticas de seguridad de Supabase están bloqueando el INSERT")
                        Log.e("SupabaseClient", "🔒 Solución: Ejecutar el script supabase/migrations/20251120_fix_rls_policies.sql en Supabase SQL Editor")
                        Log.e("SupabaseClient", "🔒 SubmissionId: ${fileContext.submissionId}, FileName: ${fileContext.fileName}")
                    }
                    
                    return@withContext null
                }

                // Parse response to extract assigned ID
                val jsonArray = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                if (jsonArray != null && jsonArray.size() > 0) {
                    val obj = jsonArray[0].asJsonObject
                    val remoteId = obj.get("id")?.asLong
                    Log.d("SupabaseClient", "insertFileContext success: remoteId=$remoteId for submissionId=${fileContext.submissionId}")
                    return@withContext remoteId
                } else {
                    Log.w("SupabaseClient", "insertFileContext returned empty array")
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "insertFileContext exception", e)
            return@withContext null
        }
    }

    suspend fun updateTaskSubmissionRemote(submissionId: Long, grade: Float?, feedback: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>()
            // Convert grade to Double to avoid PostgreSQL type issues
            if (grade != null) map["grade"] = grade.toDouble()
            if (feedback != null) map["feedback"] = feedback

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            android.util.Log.d("SupabaseClient", "updateTaskSubmissionRemote payload: ${gson.toJson(map)} for id=$submissionId")

            // First try PATCH by id
            var url = "$baseUrl/rest/v1/task_submissions?id=eq.$submissionId"
            var request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) return@withContext true
                // if not successful, fall through to try matching by task/student/date
                android.util.Log.w("SupabaseClient", "PATCH by id failed for submissionId=$submissionId code=${resp.code}")
            }

            // Fallback: try to PATCH by task_id, student_username and submission_date
            try {
                // We need to build a new body again (request bodies are one-time use)
                val fallbackBody = gson.toJson(map).toRequestBody(jsonMedia)
                // As we don't have submission details here, attempt to query by id may have failed because remote id differs.
                // The caller can optionally re-call with more context; here we attempt a best-effort using submissionId as task_id if needed.
                // For a reliable fallback, callers should provide taskId/studentUsername/submissionDate; we will attempt a broad update by id alternative.
                    url = "$baseUrl/rest/v1/task_submissions?id=eq.$submissionId"
                request = Request.Builder()
                    .url(url)
                    .patch(fallbackBody)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=representation")
                    .build()

                client.newCall(request).execute().use { resp2 ->
                    if (resp2.isSuccessful) return@withContext true
                    android.util.Log.e("SupabaseClient", "updateTaskSubmissionRemote fallback failed: ${resp2.code} body=${resp2.body?.string()}")
                }
            } catch (e: Exception) {
                android.util.Log.e("SupabaseClient", "Exception in fallback updateTaskSubmissionRemote", e)
            }

            return@withContext false
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    // Overload that accepts the full TaskSubmission object and attempts to PATCH by id first,
    // then falls back to matching by task_id, student_username and submission_date.
    suspend fun updateTaskSubmissionRemote(submission: com.example.tareamov.data.entity.TaskSubmission): Boolean = withContext(Dispatchers.IO) {
        try {
            val grade = submission.grade
            val feedback = submission.feedback
            val map = mutableMapOf<String, Any?>()
            // Convert grade to Double to avoid PostgreSQL type issues
            if (grade != null) map["grade"] = grade.toDouble()
            if (feedback != null) map["feedback"] = feedback

            if (map.isEmpty()) {
                // Nothing to update
                return@withContext true
            }

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            android.util.Log.d("SupabaseClient", "updateTaskSubmissionRemote payload: ${gson.toJson(map)} for submission id=${submission.id}")

            // Try PATCH by id first
            var url = "$baseUrl/rest/v1/task_submissions?id=eq.${submission.id}"
            var request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) return@withContext true
                android.util.Log.w("SupabaseClient", "PATCH by id failed for submissionId=${submission.id} code=${resp.code}")
            }

            // Fallback: try to PATCH by task_id, student_username and submission_date
            try {
                val fallbackBody = gson.toJson(map).toRequestBody(jsonMedia)
                // Build filter query - string values should be quoted
                    // Use numeric student_id in the filter
                    url = "$baseUrl/rest/v1/task_submissions?task_id=eq.${submission.taskId}&student_id=eq.${submission.studentId}&submission_date=eq.${submission.submissionDate}"
                request = Request.Builder()
                    .url(url)
                    .patch(fallbackBody)
                    .addHeader("apikey", apiKey)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("Accept", "application/json")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=representation")
                    .build()

                client.newCall(request).execute().use { resp2 ->
                    if (resp2.isSuccessful) return@withContext true
                    android.util.Log.e("SupabaseClient", "updateTaskSubmissionRemote fallback failed: ${resp2.code} body=${resp2.body?.string()}")
                }
            } catch (e: Exception) {
                android.util.Log.e("SupabaseClient", "Exception in fallback updateTaskSubmissionRemote", e)
            }

            return@withContext false
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    // Generic GET helper
    /**
     * Construye una petici├│n GET a Supabase REST API
     * 
     * IMPORTANTE: Esta funci├│n notifica la URL completa al listener registrado,
     * lo que permite que DatabaseQueryFragment muestre al usuario exactamente
     * qu├® endpoint de Supabase se consult├│.
     * 
     * Ejemplo de URL generada:
     * https://[project].supabase.co/rest/v1/courses?id=eq.11&select=creator_username
     * 
     * @param path El path relativo (ej: "courses?id=eq.11&select=creator_username")
     * @return Request configurado con headers de autenticaci├│n
     */
    private fun buildGetRequest(path: String): Request {
        val url = "$baseUrl/rest/v1/$path"
        val key = effectiveApiKey()
        val builder = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")
            .addHeader("Prefer", "count=exact") // Request exact count for pagination

        if (key.isNotEmpty()) {
            builder.addHeader("apikey", key)
            builder.addHeader("Authorization", "Bearer $key")
        } else {
            android.util.Log.w("SupabaseClient", "buildGetRequest: no Supabase API key available; request to $path will likely be rejected (401). Set SUPABASE_KEY in local.properties or call SupabaseClient.setApiKeyAtRuntime(key).")
        }

        // Notify listener (for debug/UI) with the full URL being requested
        try {
            requestListener?.invoke(url)
        } catch (t: Throwable) {
            android.util.Log.w("SupabaseClient", "requestListener threw", t)
        }

        return builder.build()
    }

    private suspend fun <T> fetchListOrThrow(path: String, clazz: Class<Array<T>>): List<T> = withContext(Dispatchers.IO) {
        val request = buildGetRequest(path)
        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) {
                throw Exception("GET $path failed: ${resp.code} body=$body")
            }
            if (body.isNullOrEmpty()) return@withContext emptyList()
            // Use underscoredGson for parsing so snake_case keys (Postgres) map to camelCase Kotlin fields
            val parser = underscoredGson
            val arr = parser.fromJson(body, clazz)
            val list = arr?.toList() ?: emptyList()
            try {
                android.util.Log.d("SupabaseClient", "GET $path parsed ${list.size} items")
            } catch (_: Exception) {}
            return@withContext list
        }
    }

    private suspend fun <T> fetchList(path: String, clazz: Class<Array<T>>): List<T> = withContext(Dispatchers.IO) {
        try {
            val request = buildGetRequest(path)
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) {
                    android.util.Log.w("SupabaseClient", "GET $path failed: ${resp.code} body=$body")
                    return@withContext emptyList()
                }
                if (body.isNullOrEmpty()) return@withContext emptyList()
                try {
                    // Use underscoredGson for parsing so snake_case keys (Postgres) map to camelCase Kotlin fields
                    val parser = underscoredGson
                    val arr = parser.fromJson(body, clazz)
                    val list = arr?.toList() ?: emptyList()
                    try {
                        android.util.Log.d("SupabaseClient", "GET $path parsed ${list.size} items")
                    } catch (_: Exception) {}
                    return@withContext list
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@withContext emptyList()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    suspend fun fetchPersonas(): List<Persona> = fetchList("personas", Array<Persona>::class.java)
    suspend fun fetchUsuarios(): List<Usuario> = fetchList("usuarios", Array<Usuario>::class.java)
    // Fetch a single Usuario by username using Supabase filter (case-sensitive on DB side).
    // We perform a case-insensitive match in the client by filtering the returned result.
    suspend fun fetchUsuarioByUsername(username: String): Usuario? = withContext(Dispatchers.IO) {
        fun maskSecret(s: String?): String {
            if (s == null) return "null"
            val len = s.length
            return when {
                len <= 2 -> "*".repeat(len)
                else -> s.first() + "*".repeat(len - 2) + s.last()
            }
        }

        fun normalizeForCompare(s: String?): String {
            if (s == null) return ""
            try {
                val trimmed = s.trim().lowercase()
                val normalized = java.text.Normalizer.normalize(trimmed, java.text.Normalizer.Form.NFKD)
                return normalized.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            } catch (t: Throwable) {
                return s.trim().lowercase()
            }
        }

        android.util.Log.d("SupabaseClient", "fetchUsuarioByUsername requested for username=$username")
        var result: Usuario? = null

        try {
            val escaped = username.replace("'", "''")

            // Try a case-insensitive server-side match first (ilike)
            val pathIlike = "usuarios?username=ilike.'${escaped}'"
            client.newCall(buildGetRequest(pathIlike)).execute().use { resp ->
                val body = resp.body?.string()
                android.util.Log.d("SupabaseClient", "GET $pathIlike code=${resp.code} body_len=${body?.length ?: 0}")
                if (resp.isSuccessful && !body.isNullOrEmpty()) {
                    try {
                        val arr = gson.fromJson(body, Array<Usuario>::class.java)
                        val list = arr?.toList() ?: emptyList()
                        val targetNorm = normalizeForCompare(username)
                        val found = list.firstOrNull { u -> normalizeForCompare(u.usuario) == targetNorm }
                        if (found != null) {
                            android.util.Log.d("SupabaseClient", "fetchUsuarioByUsername(ilike): found id=${found.id} stored_password_mask=${maskSecret(found.contrasena)}")
                            result = found
                        } else {
                            android.util.Log.d("SupabaseClient", "fetchUsuarioByUsername(ilike): no exact case-insensitive match in returned list for username=$username")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("SupabaseClient", "Failed parsing ilike response", e)
                    }
                }
            }

            // If not found yet, try exact eq filter
            if (result == null) {
                val pathEq = "usuarios?username=eq.'${escaped}'"
                client.newCall(buildGetRequest(pathEq)).execute().use { resp2 ->
                    val body2 = resp2.body?.string()
                    android.util.Log.d("SupabaseClient", "GET $pathEq code=${resp2.code} body_len=${body2?.length ?: 0}")
                    if (resp2.isSuccessful && !body2.isNullOrEmpty()) {
                        try {
                            val arr2 = gson.fromJson(body2, Array<Usuario>::class.java)
                            val list2 = arr2?.toList() ?: emptyList()
                            val targetNorm = normalizeForCompare(username)
                            val found2 = list2.firstOrNull { u -> normalizeForCompare(u.usuario) == targetNorm }
                            if (found2 != null) {
                                android.util.Log.d("SupabaseClient", "fetchUsuarioByUsername(eq): found id=${found2.id} stored_password_mask=${maskSecret(found2.contrasena)}")
                                result = found2
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("SupabaseClient", "Failed parsing eq response", e)
                        }
                    }
                }
            }

            // Last-resort fallback: fetch full usuarios and match client-side
            if (result == null) {
                android.util.Log.d("SupabaseClient", "fetchUsuarioByUsername: falling back to full list fetch for username=$username")
                val all = fetchUsuarios()
                val targetNorm = normalizeForCompare(username)
                val foundFull = all.firstOrNull { u -> normalizeForCompare(u.usuario) == targetNorm }
                if (foundFull != null) {
                    android.util.Log.d("SupabaseClient", "fetchUsuarioByUsername(fallback full): found id=${foundFull.id} stored_password_mask=${maskSecret(foundFull.contrasena)}")
                    result = foundFull
                } else {
                    android.util.Log.d("SupabaseClient", "fetchUsuarioByUsername: no user found for username=$username after full fetch")
                }
            }

            return@withContext result
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "fetchUsuarioByUsername exception: ${e.message}", e)
            return@withContext null
        }
    }

    suspend fun fetchUsuarioById(userId: Long): Usuario? = withContext(Dispatchers.IO) {
        if (userId <= 0) return@withContext null
        return@withContext try {
            val path = "usuarios?id=eq.$userId&limit=1"
            client.newCall(buildGetRequest(path)).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrEmpty()) {
                    android.util.Log.d("SupabaseClient", "fetchUsuarioById failed for id=$userId code=${resp.code}")
                    return@use null
                }
                try {
                    val arr = gson.fromJson(body, Array<Usuario>::class.java)
                    arr?.firstOrNull()
                } catch (parse: Exception) {
                    android.util.Log.w("SupabaseClient", "fetchUsuarioById parse error for id=$userId", parse)
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "fetchUsuarioById exception for id=$userId: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch a Usuario by email from Supabase.
     * Returns the Usuario if found, null otherwise.
     */


    suspend fun isUserAdmin(userId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/usuarios?id=eq.$userId&select=rol_id,roles(nombre,nivel)"
            val key = effectiveApiKey()

            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false

                val body = response.body?.string() ?: return@withContext false
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray

                if (jsonArray.size() > 0) {
                    val user = jsonArray[0].asJsonObject
                    val roles = user.getAsJsonObject("roles")
                    if (roles != null) {
                        val nombre = roles.get("nombre")?.asString
                        val nivel = roles.get("nivel")?.asFloat ?: 0f
                        return@withContext nombre == "admin" || nivel >= 2.0f
                    }
                }

                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error checking admin status for user $userId", e)
            false
        }
    }

    suspend fun getUserIdFromUsername(username: String): Long? {
        val trimmed = username.trim()
        if (trimmed.isEmpty()) return null
        return try {
            fetchUsuarioByUsername(trimmed)?.id?.takeIf { it > 0 }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "getUserIdFromUsername exception for username=$trimmed: ${e.message}", e)
            null
        }
    }

    suspend fun getUsernameFromUserId(userId: Long): String? {
        if (userId <= 0) return null
        return try {
            fetchUsuarioById(userId)?.usuario?.takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "getUsernameFromUserId exception for id=$userId: ${e.message}", e)
            null
        }
    }

    suspend fun getUserAvatarUrl(userId: Long): String? {
        if (userId <= 0) return null
        return try {
            fetchUsuarioById(userId)?.avatar?.takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "getUserAvatarUrl exception for id=$userId: ${e.message}", e)
            null
        }
    }

    /**
     * Obtiene el username del creador desde un course_id.
     * Primero obtiene el curso, luego su creator_user_id, y finalmente el username.
     */
    suspend fun getUsernameFromCourseId(courseId: Long): String? {
        if (courseId <= 0) return null
        return try {
            val course = fetchCourseById(courseId)
            if (course != null && course.creatorUserId > 0) {
                getUsernameFromUserId(course.creatorUserId)
            } else {
                android.util.Log.w("SupabaseClient", "getUsernameFromCourseId: course not found or invalid creatorUserId for courseId=$courseId")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "getUsernameFromCourseId exception for courseId=$courseId: ${e.message}", e)
            null
        }
    }

    suspend fun searchCourses(query: String, limit: Int = 50): List<Course> {
        if (!isConfigured()) {
            android.util.Log.w("SupabaseClient", "searchCourses skipped: Supabase not configured")
            return emptyList()
        }

        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return try {
                fetchCourses().sortedByDescending { it.timestamp }.take(limit)
            } catch (e: Exception) {
                android.util.Log.w("SupabaseClient", "searchCourses fallback fetchCourses failed", e)
                emptyList()
            }
        }

        val encodedTerm = try {
            java.net.URLEncoder.encode(trimmed, "UTF-8")
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "searchCourses encode failed for query='$trimmed'", e)
            trimmed.replace(" ", "+")
        }

        val path = buildString {
            append("courses?or=(")
            append("title.ilike.*$encodedTerm*")
            append(",description.ilike.*$encodedTerm*")
            append(",creator_username.ilike.*$encodedTerm*")
            append(",category.ilike.*$encodedTerm*")
            append(",tags.ilike.*$encodedTerm*")
            append(")&order=timestamp.desc,created_at.desc.nullslast")
            append("&limit=$limit")
        }

        return try {
            val remote = fetchList(path, Array<Course>::class.java)
            if (remote.isNotEmpty()) {
                remote
            } else {
                fetchCourses().filter { course ->
                    course.title.contains(trimmed, ignoreCase = true) ||
                            course.description.contains(trimmed, ignoreCase = true) ||
                            (course.category?.contains(trimmed, ignoreCase = true) == true) ||
                            (course.tags?.contains(trimmed, ignoreCase = true) == true)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "searchCourses remote search failed for query='$trimmed'", e)
            try {
                fetchCourses().filter { course ->
                    course.title.contains(trimmed, ignoreCase = true) ||
                            course.description.contains(trimmed, ignoreCase = true) ||
                            (course.category?.contains(trimmed, ignoreCase = true) == true) ||
                            (course.tags?.contains(trimmed, ignoreCase = true) == true)
                }
            } catch (fallback: Exception) {
                android.util.Log.w("SupabaseClient", "searchCourses fallback filtering failed", fallback)
                emptyList()
            }
        }
    }
    suspend fun fetchVideosOrThrow(): List<VideoData> = withContext(Dispatchers.IO) {
        // Try typed mapping first
        try {
            val videos = fetchListOrThrow("videos", Array<VideoData>::class.java)
            if (videos.isNotEmpty()) return@withContext videos
        } catch (e: Exception) {
            // If typed fetch fails, try defensive fallback but rethrow if that also fails
            Log.w("SupabaseClient", "Typed fetchVideosOrThrow failed, trying fallback", e)
        }

        // Defensive fallback: raw JSON mapping to remain resilient to schema changes
        val req = buildGetRequest("videos")
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful) throw Exception("fetchVideosOrThrow fallback failed: ${resp.code}")
            if (body.isNullOrEmpty()) return@withContext emptyList<VideoData>()
            
            val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
            val repaired = mutableListOf<VideoData>()
            for (elem in arr) {
                try {
                    val obj = elem.asJsonObject
                    val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                    val username = when {
                        obj.has("username") && !obj.get("username").isJsonNull -> obj.get("username").asString
                        obj.has("creator_username") && !obj.get("creator_username").isJsonNull -> obj.get("creator_username").asString
                        obj.has("user") && !obj.get("user").isJsonNull -> obj.get("user").asString
                        else -> "unknown"
                    }
                    val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    val courseId = obj.get("course_id")?.takeIf { !it.isJsonNull }?.asLong
                    val videoUriString = when {
                        obj.has("video_uri_string") && !obj.get("video_uri_string").isJsonNull -> obj.get("video_uri_string").asString
                        obj.has("video_uri") && !obj.get("video_uri").isJsonNull -> obj.get("video_uri").asString
                        obj.has("video_url") && !obj.get("video_url").isJsonNull -> obj.get("video_url").asString
                        else -> null
                    }
                    val localFilePath = obj.get("local_file_path")?.takeIf { !it.isJsonNull }?.asString
                    val thumbnailUri = when {
                        obj.has("thumbnail_uri") && !obj.get("thumbnail_uri").isJsonNull -> obj.get("thumbnail_uri").asString
                        obj.has("thumbnail") && !obj.get("thumbnail").isJsonNull -> obj.get("thumbnail").asString
                        else -> null
                    }
                    val timestamp = try {
                        obj.get("timestamp")?.takeIf { !it.isJsonNull }?.asLong
                            ?: obj.get("created_at")?.takeIf { !it.isJsonNull }?.asString?.let { java.time.Instant.parse(it).toEpochMilli() }
                            ?: System.currentTimeMillis()
                    } catch (_: Exception) { System.currentTimeMillis() }
                    val isPaid = obj.get("is_paid")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                    val price = try { obj.get("price")?.takeIf { !it.isJsonNull }?.asDouble } catch (_: Exception) { null }

                    val v = VideoData(
                        id = id,
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
                    v.username = username
                    repaired.add(v)
                } catch (t: Exception) {
                    Log.w("SupabaseClient", "Failed to parse video element", t)
                }
            }
            repaired
        }
    }

    suspend fun fetchVideoById(id: Long): VideoData? = withContext(Dispatchers.IO) {
        try {
            val list = fetchList("videos?id=eq.$id", Array<VideoData>::class.java)
            list.firstOrNull()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching video by id $id", e)
            null
        }
    }

    suspend fun fetchVideos(): List<VideoData> = withContext(Dispatchers.IO) {
        try {
            // Try typed mapping first
            var videos = fetchList("videos", Array<VideoData>::class.java)
            if (videos.isNotEmpty()) return@withContext videos

            // Defensive fallback: raw JSON mapping to remain resilient to schema changes
            return@withContext try {
                val req = buildGetRequest("videos")
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (!resp.isSuccessful || body.isNullOrEmpty()) return@use emptyList<VideoData>()
                    val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                    val repaired = mutableListOf<VideoData>()
                    for (elem in arr) {
                        try {
                            val obj = elem.asJsonObject
                            val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                            val username = when {
                                obj.has("username") && !obj.get("username").isJsonNull -> obj.get("username").asString
                                obj.has("creator_username") && !obj.get("creator_username").isJsonNull -> obj.get("creator_username").asString
                                obj.has("user") && !obj.get("user").isJsonNull -> obj.get("user").asString
                                else -> "unknown"
                            }
                            val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            val courseId = obj.get("course_id")?.takeIf { !it.isJsonNull }?.asLong
                            val videoUriString = when {
                                obj.has("video_uri_string") && !obj.get("video_uri_string").isJsonNull -> obj.get("video_uri_string").asString
                                obj.has("video_uri") && !obj.get("video_uri").isJsonNull -> obj.get("video_uri").asString
                                obj.has("video_url") && !obj.get("video_url").isJsonNull -> obj.get("video_url").asString
                                else -> null
                            }
                            val localFilePath = obj.get("local_file_path")?.takeIf { !it.isJsonNull }?.asString
                            val thumbnailUri = when {
                                obj.has("thumbnail_uri") && !obj.get("thumbnail_uri").isJsonNull -> obj.get("thumbnail_uri").asString
                                obj.has("thumbnail") && !obj.get("thumbnail").isJsonNull -> obj.get("thumbnail").asString
                                else -> null
                            }
                            val timestamp = try {
                                obj.get("timestamp")?.takeIf { !it.isJsonNull }?.asLong
                                    ?: obj.get("created_at")?.takeIf { !it.isJsonNull }?.asString?.let { java.time.Instant.parse(it).toEpochMilli() }
                                    ?: System.currentTimeMillis()
                            } catch (_: Exception) { System.currentTimeMillis() }
                            val isPaid = obj.get("is_paid")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                            val price = try { obj.get("price")?.takeIf { !it.isJsonNull }?.asDouble } catch (_: Exception) { null }

                            val v = VideoData(
                                id = id,
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
                            v.username = username
                            repaired.add(v)
                        } catch (t: Exception) {
                            Log.w("SupabaseClient", "Failed to parse video element", t)
                        }
                    }
                    repaired
                }
            } catch (inner: Exception) {
                Log.w("SupabaseClient", "Defensive fetchVideos fallback failed", inner)
                emptyList()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    // Fetch videos for a specific username (server-side filter). Attempts exact eq match.
    suspend fun fetchVideosByUsername(username: String): List<VideoData> = withContext(Dispatchers.IO) {
        try {
            // Since 'videos' table doesn't have 'username' column, we must go through courses -> creator_user_id
            val userId = getUserIdFromUsername(username)
            if (userId != null) {
                val courses = fetchCoursesByCreatorUserId(userId)
                val courseIds = courses.map { it.id }
                if (courseIds.isNotEmpty()) {
                    val videos = fetchVideosByCourseIds(courseIds)
                    // Manually set the username since it's not in the video table
                    return@withContext videos.map { v -> v.copy().apply { this.username = username } }
                }
            }
            
            // Fallback: fetch all videos ordered and filter client-side (inefficient but safe)
            val allPath = "videos?order=timestamp.desc,created_at.desc.nullslast"
            val all = fetchList(allPath, Array<VideoData>::class.java)
            return@withContext all.filter { v -> (v.username ?: "").trim().equals(username.trim(), ignoreCase = true) }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }
    
    // Fetch raw JSON array for a table; useful when we need defensive mapping
    suspend fun fetchTableJson(table: String): com.google.gson.JsonArray = withContext(Dispatchers.IO) {
        try {
            val request = buildGetRequest(table)
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful) {
                    android.util.Log.w("SupabaseClient", "GET $table failed: ${resp.code} body=$body")
                    return@withContext com.google.gson.JsonArray()
                }
                if (body.isNullOrEmpty()) return@withContext com.google.gson.JsonArray()
                try {
                    val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                    return@withContext arr
                } catch (e: Exception) {
                    e.printStackTrace()
                    return@withContext com.google.gson.JsonArray()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext com.google.gson.JsonArray()
        }
    }

    /**
     * Fetch a generic snapshot from any table and return it as a list of key/value maps.
     * This is convenient for dynamically generated queries (e.g., LLM tooling).
     */
    suspend fun fetchTableSnapshot(table: String, limit: Int = 50): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        val safeLimit = if (limit <= 0) 50 else limit
        val path = if (safeLimit > 0) "$table?limit=$safeLimit" else table
        val jsonArray = fetchTableJson(path)
        val results = mutableListOf<Map<String, Any?>>()

        for (element in jsonArray) {
            if (element.isJsonObject) {
                @Suppress("UNCHECKED_CAST")
                val map = underscoredGson.fromJson(element, Map::class.java) as Map<String, Any?>
                results.add(map)
            }
        }

        results
    }
    suspend fun fetchTopics(): List<Topic> = fetchList("topics", Array<Topic>::class.java)
    
    /**
     * Fetch a single Topic by exact name using server-side filter. Returns null if not found.
     */
    suspend fun fetchTopicByName(name: String): Topic? = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(name, "UTF-8")
            val url = "$baseUrl/rest/v1/topics?name=eq.$encoded&select=*"
            requestListener?.invoke(url)

            val req = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Accept", "application/json")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w("SupabaseClient", "fetchTopicByName failed status=${resp.code}")
                return@withContext null
            }

            val body = resp.body?.string() ?: return@withContext null
            val arr = underscoredGson.fromJson(body, Array<Topic>::class.java)
            return@withContext arr.firstOrNull()
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchTopicByName exception", e)
            return@withContext null
        }
    }
    
    // Fetch all content items using manual parsing to ensure 'body' maps to 'uriString'
    suspend fun fetchContentItems(): List<ContentItem> = withContext(Dispatchers.IO) {
        try {
            val path = "content_items"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchContentItems failed status=${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                val items = mutableListOf<ContentItem>()
                
                jsonArray.forEach { element ->
                    val item = parseContentItemFromJson(element.asJsonObject)
                    items.add(item)
                }
                
                Log.d("SupabaseClient", "fetchContentItems: Found ${items.size} items")
                return@withContext items
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchContentItems exception", e)
            emptyList()
        }
    }
    
    suspend fun fetchTasks(): List<Task> = fetchList("tasks", Array<Task>::class.java)
    // Fetch a single task by id using server-side filter
    suspend fun fetchTaskById(id: Long): Task? = withContext(Dispatchers.IO) {
        try {
            val path = "tasks?id=eq.${id}"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchTaskById failed status=${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val arr = underscoredGson.fromJson(body, Array<Task>::class.java)
                return@withContext arr.firstOrNull()
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchTaskById exception", e)
            null
        }
    }
    
    /**
     * Fetch a task by its name (title) using case-insensitive search.
     * Returns the first matching task or null if not found.
     */
    suspend fun fetchTaskByName(taskName: String): Task? = withContext(Dispatchers.IO) {
        try {
            val encodedName = java.net.URLEncoder.encode(taskName, "UTF-8")
            // Use ilike for case-insensitive match
            val path = "tasks?title=ilike.$encodedName&limit=1"
            Log.d("SupabaseClient", "🔍 Fetching task by name: $taskName")
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchTaskByName failed status=${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                Log.d("SupabaseClient", "📊 fetchTaskByName response: $body")
                val arr = underscoredGson.fromJson(body, Array<Task>::class.java)
                val task = arr.firstOrNull()
                if (task != null) {
                    Log.d("SupabaseClient", "✅ Task found: id=${task.id}, name=${task.name}")
                } else {
                    Log.d("SupabaseClient", "⚠️ No task found with name: $taskName")
                }
                return@withContext task
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchTaskByName exception", e)
            null
        }
    }
    
    suspend fun fetchSubscriptions(): List<Subscription> = fetchList("subscriptions", Array<Subscription>::class.java)

    // Fetch progreso_estudiante rows for a given usuario_estudiante (user id)
    suspend fun fetchProgresosByUsuario(usuarioId: Long): List<com.example.tareamov.data.entity.ProgresoEstudiante> =
        withContext(Dispatchers.IO) {
            try {
                fetchList("progreso_estudiante?usuario_estudiante=eq.$usuarioId", Array<com.example.tareamov.data.entity.ProgresoEstudiante>::class.java)
            } catch (e: Exception) {
                Log.w("SupabaseClient", "fetchProgresosByUsuario failed", e)
                emptyList()
            }
        }

    // Insert a subscription record into Supabase
    suspend fun insertSubscriptionToSupabase(sub: Subscription): Boolean = withContext(Dispatchers.IO) {
        try {
            val map = mapOf(
                "subscriber_id" to sub.subscriberId,
                "creator_id" to sub.creatorId,
                "subscription_date" to sub.subscriptionDate
            )
            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/subscriptions"
            val request = Request.Builder()
                .url(url)
                .addHeader("apiKey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val code = resp.code
                val success = resp.isSuccessful
                // 409 Conflict likely means the subscription already exists (unique constraint)
                if (code == 409) {
                    android.util.Log.d("SupabaseClient", "insertSubscriptionToSupabase status=$code conflict -> treated as success (already exists)")
                    return@withContext true
                }
                android.util.Log.d("SupabaseClient", "insertSubscriptionToSupabase status=$code success=$success")
                return@withContext success
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "insertSubscriptionToSupabase failed", e)
            return@withContext false
        }
    }



    // Delete a subscription (unsubscribe) from Supabase
    suspend fun deleteSubscriptionFromSupabase(subscriberId: Long, creatorId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/subscriptions?subscriber_id=eq.$subscriberId&creator_id=eq.$creatorId"
            val request = Request.Builder()
                .url(url)
                .addHeader("apiKey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .delete()
                .build()

            client.newCall(request).execute().use { resp ->
                val success = resp.isSuccessful
                android.util.Log.d("SupabaseClient", "deleteSubscriptionFromSupabase status=${resp.code} success=$success")
                return@withContext success
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "deleteSubscriptionFromSupabase failed", e)
            return@withContext false
        }
    }

    // Check if a user is subscribed to a creator via Supabase
    suspend fun isSubscribedRemote(subscriberId: Long, creatorId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/subscriptions?subscriber_id=eq.$subscriberId&creator_id=eq.$creatorId&select=*"
            val request = buildGetRequest(url) // Using helper if possible, but buildGetRequest takes path not full url?
            // buildGetRequest takes path. Let's check buildGetRequest implementation.
            // private fun buildGetRequest(path: String): Request { val url = "$baseUrl/rest/v1/$path" ... }
            // So we should pass "subscriptions?subscriber_id=eq.$subscriberId&creator_id=eq.$creatorId&select=*"
            
            val path = "subscriptions?subscriber_id=eq.$subscriberId&creator_id=eq.$creatorId&select=*"
            val req = buildGetRequest(path)
            
            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body?.string() ?: return@withContext false
                val list = gson.fromJson(body, Array<Any>::class.java)
                return@withContext list.isNotEmpty()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error checking subscription status", e)
            false
        }
    }

    // Fetch subscriber count for a creator
    suspend fun fetchSubscriberCount(creatorId: Long): Long = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "Fetching subscriber count for creatorId: $creatorId")
            // Use HEAD request or GET with count=exact
            // We use GET with limit=1 and Prefer: count=exact to get the total count in Content-Range header
            val path = "subscriptions?creator_id=eq.$creatorId&select=subscriber_id&limit=1"
            val url = "$baseUrl/rest/v1/$path"
            
            Log.d("SupabaseClient", "Request URL: $url")
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Range", "0-0")
                .addHeader("Prefer", "count=exact")
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("SupabaseClient", "Response code: ${response.code}")
                val rangeHeader = response.header("Content-Range")
                Log.d("SupabaseClient", "Content-Range header: $rangeHeader")
                // Format: 0-0/5 (where 5 is the total)
                if (rangeHeader != null && rangeHeader.contains("/")) {
                    val total = rangeHeader.substringAfter("/").toLongOrNull() ?: 0L
                    Log.d("SupabaseClient", "Subscriber count for creatorId $creatorId: $total")
                    return@withContext total
                }
                Log.w("SupabaseClient", "No Content-Range header found or invalid format")
                0L
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching subscriber count for creatorId $creatorId", e)
            0L
        }
    }

    // Fetch enrolled student count for a course
    suspend fun fetchEnrolledCount(courseId: Long): Long = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "Fetching enrolled count for courseId: $courseId")
            // Use GET with limit=1 and Prefer: count=exact to get the total count in Content-Range header
            val path = "progreso_estudiante?course_id=eq.$courseId&select=id&limit=1"
            val url = "$baseUrl/rest/v1/$path"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Range", "0-0")
                .addHeader("Prefer", "count=exact")
                .build()

            client.newCall(request).execute().use { response ->
                val rangeHeader = response.header("Content-Range")
                // Format: 0-0/5 (where 5 is the total)
                if (rangeHeader != null && rangeHeader.contains("/")) {
                    val total = rangeHeader.substringAfter("/").toLongOrNull() ?: 0L
                    return@withContext total
                }
                0L
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching enrolled count for courseId $courseId", e)
            0L
        }
    }

    suspend fun fetchTaskSubmissions(): List<TaskSubmission> = fetchList("task_submissions", Array<TaskSubmission>::class.java)

    // Fetch a single TaskSubmission by taskId and studentId
    suspend fun fetchTaskSubmissionByTaskId(taskId: Long, studentId: Long): TaskSubmission? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/task_submissions?task_id=eq.$taskId&student_id=eq.$studentId&select=*"
            val request = buildGetRequest(url)
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                val list = underscoredGson.fromJson(json, Array<TaskSubmission>::class.java)
                list.firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching submission: ${e.message}")
            null
        }
    }

    /**
     * Fetch submissions by taskId and studentId (using student_id integer column)
     * Returns all submissions for the given task and student, ordered by submission_date desc
     */
    suspend fun fetchTaskSubmissionsByTaskAndStudentId(taskId: Long, studentId: Long): List<TaskSubmission> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/task_submissions?task_id=eq.$taskId&student_id=eq.$studentId&select=*&order=submission_date.desc"
            Log.d("SupabaseClient", "🔍 Fetching submissions by student_id: $url")
            val request = buildGetRequest(url)
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                Log.d("SupabaseClient", "📊 Response: $json")
                val list = underscoredGson.fromJson(json, Array<TaskSubmission>::class.java)
                list?.toList() ?: emptyList()
            } else {
                Log.w("SupabaseClient", "⚠️ Failed to fetch submissions: ${response.code} ${response.message}")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching submissions by student_id: ${e.message}")
            emptyList()
        }
    }

    // Fetch FileContext by submissionId - returns the one with actual file_content if multiple exist
    suspend fun fetchFileContextBySubmissionId(submissionId: Long): FileContext? = withContext(Dispatchers.IO) {
        try {
            // Order by id DESC to get the most recent first, and filter for non-empty file_content
            val url = "$baseUrl/rest/v1/file_contexts?submission_id=eq.$submissionId&select=*&order=id.desc"
            Log.d("SupabaseClient", "🔍 Fetching FileContext for submissionId=$submissionId: $url")
            val request = buildGetRequest(url)
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                Log.d("SupabaseClient", "📄 FileContext response: ${json?.take(500)}")
                val list = underscoredGson.fromJson(json, Array<FileContext>::class.java)
                
                if (list.isNullOrEmpty()) {
                    Log.d("SupabaseClient", "⚠️ No FileContext found for submissionId=$submissionId")
                    return@withContext null
                }
                
                // Prioritize FileContext with non-empty file_content
                val withContent = list.filter { it.fileContent.isNotBlank() }
                val result = if (withContent.isNotEmpty()) {
                    Log.d("SupabaseClient", "✅ Found ${withContent.size} FileContext(s) with content, using first")
                    withContent.first()
                } else {
                    Log.d("SupabaseClient", "⚠️ No FileContext with content, using first available")
                    list.first()
                }
                
                Log.d("SupabaseClient", "📄 Selected FileContext: id=${result.id}, fileName=${result.fileName}, contentLength=${result.fileContent.length}")
                result
            } else {
                Log.w("SupabaseClient", "⚠️ Failed to fetch FileContext: ${response.code} ${response.message}")
                null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching file context: ${e.message}")
            null
        }
    }

    suspend fun fetchChatMessages(): List<ChatMessage> = fetchList("chat_messages", Array<ChatMessage>::class.java)
    suspend fun fetchFileContexts(): List<FileContext> = fetchList("file_contexts", Array<FileContext>::class.java)
    
    // Fetch ALL courses from Supabase using robust server-side pagination until exhaustion
    suspend fun fetchCourses(): List<Course> = withContext(Dispatchers.IO) {
        val pageSize = 1000 // safe chunk size for PostgREST
        var offset = 0
        var page = 0
        val all = mutableListOf<Course>()
        try {
            while (true) {
                val path = "courses?select=*&order=timestamp.desc&limit=$pageSize&offset=$offset"
                android.util.Log.d("SupabaseClient", "fetchCourses[p$page]: Requesting $path")
                val request = buildGetRequest(path)
                var shouldStop = false
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                    val contentRange = resp.header("Content-Range")
                    android.util.Log.d(
                        "SupabaseClient",
                        "fetchCourses[p$page]: status=${resp.code}, Content-Range=${contentRange ?: "<none>"}, bodyLen=${body?.length ?: 0}"
                    )

                    if (!resp.isSuccessful) {
                        android.util.Log.w("SupabaseClient", "GET $path failed: ${resp.code} body=${body?.take(300)}")
                        shouldStop = true
                    }

                    if (!shouldStop && body.isNullOrEmpty()) {
                        android.util.Log.w("SupabaseClient", "fetchCourses[p$page]: Empty body, stopping")
                        shouldStop = true
                    }

                    val pageItems = if (!shouldStop) {
                        try {
                            val arr = underscoredGson.fromJson(body, Array<Course>::class.java)
                            arr?.toList() ?: emptyList()
                        } catch (e: Exception) {
                            android.util.Log.e("SupabaseClient", "fetchCourses[p$page]: JSON parse error: ${e.message}", e)
                            android.util.Log.e("SupabaseClient", "fetchCourses[p$page]: Body preview: ${body?.take(500)}")
                            emptyList()
                        }
                    } else emptyList()

                    android.util.Log.d(
                        "SupabaseClient",
                        "fetchCourses[p$page]: parsed=${pageItems.size}, accumulated=${all.size + pageItems.size}"
                    )

                    // Log a small preview for the first page
                    if (page == 0 && pageItems.isNotEmpty()) {
                        pageItems.take(10).forEachIndexed { index, course ->
                            android.util.Log.d(
                                "SupabaseClient",
                                "  #$index: ${course.title} (ID: ${course.id}, creatorUserId: ${course.creatorUserId})"
                            )
                        }
                    }

                    all.addAll(pageItems)

                    if (pageItems.size < pageSize) {
                        // last page reached
                        shouldStop = true
                    }
                }
                if (shouldStop) {
                    break
                }
                page += 1
                offset += pageSize

                // Safety stop to avoid infinite loops if server behaves oddly
                if (page > 500) {
                    android.util.Log.w("SupabaseClient", "fetchCourses: safety break after $page pages")
                    break
                }
            }

            // Deduplicate by id in case of overlapping pages
            val deduped = all.distinctBy { it.id }

            // Final analytics
            val uniqueTitles = deduped.map { it.title }.distinct().size
            val uniqueCreators = deduped.map { it.creatorUserId }.distinct().size
            android.util.Log.d(
                "SupabaseClient",
                "fetchCourses: DONE -> total=${deduped.size}, uniqueTitles=$uniqueTitles, uniqueCreators=$uniqueCreators"
            )
            return@withContext deduped
        } catch (e: Exception) {
            android.util.Log.e("SupabaseClient", "Error fetching courses (paged): ${e.message}", e)
            return@withContext emptyList()
        }
    }
    // Fetch a single Course by id using server-side filter to avoid downloading entire table
    suspend fun fetchCourseById(id: Long): Course? = withContext(Dispatchers.IO) {
        try {
            val path = "courses?id=eq.$id"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchCourseById failed status=${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val arr = underscoredGson.fromJson(body, Array<Course>::class.java)
                return@withContext arr.firstOrNull()
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchCourseById exception", e)
            null
        }
    }
    
    // Fetch a single course by exact title (server-side filter). Returns null if not found.
    suspend fun fetchCourseByTitle(title: String): Course? = withContext(Dispatchers.IO) {
        try {
            val table = "courses"
            // Use eq for exact match. URL-encode value to be safe.
            val escaped = java.net.URLEncoder.encode(title, "UTF-8")
            val path = "$table?title=eq.$escaped&select=*"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchCourseByTitle failed status=${'$'}{resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                try {
                    val arr = underscoredGson.fromJson(body, Array<Course>::class.java)
                    return@withContext arr.firstOrNull()
                } catch (e: Exception) {
                    Log.w("SupabaseClient", "fetchCourseByTitle parse failed", e)
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchCourseByTitle exception", e)
            return@withContext null
        }
    }
    // Fetch topics for a specific course using server-side filter
    suspend fun fetchTopicsByCourse(courseId: Long): List<Topic> = withContext(Dispatchers.IO) {
        try {
            val path = "topics?course_id=eq.$courseId&order=order_index.asc"
            Log.d("SupabaseClient", "📚 fetchTopicsByCourse: Querying topics for courseId=$courseId")
            Log.d("SupabaseClient", "📚 fetchTopicsByCourse: URL=$baseUrl/rest/v1/$path")
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "❌ fetchTopicsByCourse failed status=${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                Log.d("SupabaseClient", "📚 fetchTopicsByCourse: Response body length=${body.length}")
                val arr = underscoredGson.fromJson(body, Array<Topic>::class.java)
                val topics = arr.toList()
                Log.d("SupabaseClient", "✅ fetchTopicsByCourse: Found ${topics.size} topics for courseId=$courseId")
                topics.forEachIndexed { index, topic ->
                    Log.d("SupabaseClient", "   📘 Topic[$index]: id=${topic.id}, name='${topic.name}', courseId=${topic.courseId}")
                }
                return@withContext topics
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "❌ fetchTopicsByCourse exception", e)
            emptyList()
        }
    }
    // Fetch content items for a list of topic IDs using server-side 'in' filter
    suspend fun fetchContentItemsByTopicIds(topicIds: List<Long>): List<ContentItem> = withContext(Dispatchers.IO) {
        if (topicIds.isEmpty()) {
            Log.w("SupabaseClient", "🔍 fetchContentItemsByTopicIds: Empty topicIds list!")
            return@withContext emptyList()
        }
        try {
            val ids = topicIds.joinToString(",")
            // Fetch ALL content items for these topics (including those with task_id)
            // We'll return all and let the caller filter if needed
            val path = "content_items?topic_id=in.($ids)&order=order_index.asc"
            Log.d("SupabaseClient", "🔍 fetchContentItemsByTopicIds: Querying URL: $baseUrl/rest/v1/$path")
            Log.d("SupabaseClient", "🔍 fetchContentItemsByTopicIds: Looking for topicIds: $ids")
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                Log.d("SupabaseClient", "🔍 fetchContentItemsByTopicIds: Response code=${resp.code}, message=${resp.message}")
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "❌ fetchContentItemsByTopicIds failed status=${resp.code}, message=${resp.message}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                
                // Log raw JSON for debugging
                Log.d("SupabaseClient", "🔍 fetchContentItemsByTopicIds RAW JSON: $body")
                
                if (body == "[]" || body.isBlank()) {
                    Log.w("SupabaseClient", "⚠️ fetchContentItemsByTopicIds: Empty response from Supabase for topicIds=$ids")
                    return@withContext emptyList()
                }
                
                // Use manual parsing to ensure 'body' maps to 'uriString'
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                val items = mutableListOf<ContentItem>()
                
                Log.d("SupabaseClient", "📚 Found ${jsonArray.size()} content items in Supabase response")
                
                jsonArray.forEach { element ->
                    val item = parseContentItemFromJson(element.asJsonObject)
                    items.add(item)
                    Log.d("SupabaseClient", "📦 Parsed ContentItem: id=${item.id}, topicId=${item.topicId}, taskId=${item.taskId}, title='${item.title}', body='${item.body.take(50)}...', type=${item.contentType}")
                }
                
                // Return ALL items for the topic - don't filter by taskId here
                // The topic content should have taskId=null, but we include all to debug
                Log.d("SupabaseClient", "✅ fetchContentItemsByTopicIds: Returning ${items.size} content items for topicIds=$ids")
                
                return@withContext items
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ fetchContentItemsByTopicIds exception for topicIds=$topicIds", e)
            emptyList()
        }
    }
    
    // Manual parser for ContentItem to ensure JSON fields map safely
    // Handles null JSON values safely
    private fun parseContentItemFromJson(json: com.google.gson.JsonObject): ContentItem {
        // Helper function to safely get string from JSON, handling nulls
        fun safeGetString(key: String): String? {
            val element = json.get(key)
            return if (element != null && !element.isJsonNull) element.asString else null
        }
        
        // Helper function to safely get long from JSON, handling nulls
        fun safeGetLong(key: String, default: Long = 0): Long {
            val element = json.get(key)
            return if (element != null && !element.isJsonNull) element.asLong else default
        }
        
        // Helper function to safely get nullable long from JSON
        fun safeGetLongOrNull(key: String): Long? {
            val element = json.get(key)
            return if (element != null && !element.isJsonNull) element.asLong else null
        }
        
        // Helper function to safely get int from JSON, handling nulls
        fun safeGetInt(key: String, default: Int = 0): Int {
            val element = json.get(key)
            return if (element != null && !element.isJsonNull) element.asInt else default
        }
        
        // Helper function to parse ISO 8601 timestamp string to millis
        fun parseTimestamp(key: String): Long {
            val element = json.get(key)
            if (element == null || element.isJsonNull) return System.currentTimeMillis()
            return try {
                // Supabase returns timestamps as ISO 8601 strings like "2025-12-13T04:52:22.792595+00:00"
                val dateString = element.asString
                val formatter = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                val instant = java.time.OffsetDateTime.parse(dateString, formatter).toInstant()
                instant.toEpochMilli()
            } catch (e: Exception) {
                // If parsing fails, try as Long (backward compatibility)
                try {
                    element.asLong
                } catch (e2: Exception) {
                    System.currentTimeMillis()
                }
            }
        }
        
        return ContentItem(
            id = safeGetLong("id", 0),
            topicId = safeGetLong("topic_id", 0),
            taskId = safeGetLongOrNull("task_id"),
            title = safeGetString("title"),
            body = safeGetString("body") ?: "",
            contentType = safeGetString("content_type") ?: "",
            orderIndex = safeGetInt("order_index", 0),
            creator_usuario_id = safeGetLongOrNull("creator_usuario_id"),
            creator_username = safeGetString("creator_username"),
            created_at = parseTimestamp("created_at")
        )
    }
    
    // DEBUG: Fetch ALL content items to see what's in the database
    suspend fun debugFetchAllContentItems(): List<ContentItem> = withContext(Dispatchers.IO) {
        try {
            val path = "content_items?order=id.desc&limit=50"
            Log.d("SupabaseClient", "🔍 DEBUG: Fetching ALL content items from: $baseUrl/rest/v1/$path")
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "❌ DEBUG: Failed to fetch all content items: ${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                Log.d("SupabaseClient", "📋 DEBUG ALL content_items RAW: $body")
                
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                val items = mutableListOf<ContentItem>()
                jsonArray.forEach { element ->
                    val item = parseContentItemFromJson(element.asJsonObject)
                    items.add(item)
                    Log.d("SupabaseClient", "📋 DEBUG item: id=${item.id}, topicId=${item.topicId}, taskId=${item.taskId}, title='${item.title}'")
                }
                return@withContext items
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ DEBUG: Error fetching all content items", e)
            emptyList()
        }
    }
    
    // Fetch content items for a specific task using server-side filter
    suspend fun fetchContentItemsByTaskId(taskId: Long): List<ContentItem> = withContext(Dispatchers.IO) {
        if (taskId <= 0) return@withContext emptyList()
        try {
            val path = "content_items?task_id=eq.$taskId&order=order_index.asc"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchContentItemsByTaskId failed status=${resp.code} for taskId=$taskId")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                
                // Log raw JSON for debugging
                Log.d("SupabaseClient", "🔍 fetchContentItemsByTaskId RAW JSON for taskId=$taskId: $body")
                
                // Use manual parsing to ensure 'body' maps to 'uriString'
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                val items = mutableListOf<ContentItem>()
                
                jsonArray.forEach { element ->
                    val item = parseContentItemFromJson(element.asJsonObject)
                    items.add(item)
                    Log.d("SupabaseClient", "📦 Parsed ContentItem: id=${item.id}, title=${item.title}, body='${item.body}', type=${item.contentType}")
                }
                
                Log.d("SupabaseClient", "fetchContentItemsByTaskId: Found ${items.size} items for taskId=$taskId")
                
                return@withContext items
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchContentItemsByTaskId exception for taskId=$taskId", e)
            emptyList()
        }
    }
    
    // Fetch tasks for a specific topic or for many topics using 'in' filter
    suspend fun fetchTasksByTopicIds(topicIds: List<Long>): List<Task> = withContext(Dispatchers.IO) {
        if (topicIds.isEmpty()) return@withContext emptyList()
        try {
            val ids = topicIds.joinToString(",")
            val path = "tasks?topic_id=in.($ids)&order=due_date.asc"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchTasksByTopicIds failed status=${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                val arr = underscoredGson.fromJson(body, Array<Task>::class.java)
                return@withContext arr.toList()
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchTasksByTopicIds exception", e)
            emptyList()
        }
    }
    // Fetch courses created by a specific username (server-side filter on creator_username).
    suspend fun fetchCoursesByCreator(username: String): List<Course> = withContext(Dispatchers.IO) {
        try {
            val escaped = username.replace("'", "''")
            // Request server to return newest courses first (prefer timestamp then created_at)
            val path = "courses?creator_username=eq.'${escaped}'&order=timestamp.desc,created_at.desc.nullslast"
            var list = fetchList(path, Array<Course>::class.java)
            if (list.isNotEmpty()) return@withContext list

            // Fallback to ilike for case-insensitive matching, still ordered
            val pathIlike = "courses?creator_username=ilike.'${escaped}'&order=timestamp.desc,created_at.desc.nullslast"
            list = fetchList(pathIlike, Array<Course>::class.java)
            if (list.isNotEmpty()) return@withContext list

            // Last resort: fetch all courses ordered and filter client-side
            val allPath = "courses?order=timestamp.desc,created_at.desc.nullslast"
            val all = fetchList(allPath, Array<Course>::class.java)
            if (all.isEmpty()) android.util.Log.d("SupabaseClient", "fetchCoursesByCreator: server returned no courses at all for paths: $path / $pathIlike / $allPath")
            
            // Filter by userId instead of username
            val userId = getUserIdFromUsername(username) ?: return@withContext emptyList()
            return@withContext all.filter { c -> c.creatorUserId == userId }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    // Fetch courses by creator user ID (server-side filter)
    suspend fun fetchCoursesByCreatorUserId(userId: Long): List<Course> = withContext(Dispatchers.IO) {
        try {
            val path = "courses?creator_user_id=eq.$userId&order=timestamp.desc,created_at.desc.nullslast"
            fetchList(path, Array<Course>::class.java)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching courses by creator user ID", e)
            emptyList()
        }
    }

    // Fetch videos by a list of course IDs (server-side filter using 'in')
    suspend fun fetchVideosByCourseIds(courseIds: List<Long>): List<VideoData> = withContext(Dispatchers.IO) {
        try {
            if (courseIds.isEmpty()) return@withContext emptyList()
            val idsStr = courseIds.joinToString(",")
            val path = "videos?course_id=in.($idsStr)&order=timestamp.desc,created_at.desc.nullslast"
            fetchList(path, Array<VideoData>::class.java)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching videos by course IDs", e)
            emptyList()
        }
    }
    
    /**
     * Call RPC function with parameters
     */
    fun callRpcFunction(functionName: String, params: Map<String, Any?>): String? {
        try {
            val url = "$baseUrl/rest/v1/rpc/$functionName"
            val bodyJson = gson.toJson(params)
            
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMedia))
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "RPC call failed: ${response.code} body=$body")
                    return null
                }
                return body
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error calling RPC function", e)
            return null
        }
    }

    /**
     * Execute a raw SQL query (actually fetches from a table with filters)
     * Mimics raw query by allowing selection and filtering
     * This is NOT a true SQL executor due to REST API limits, but used for flexibility
     * For actual raw SQL, you need an RPC function like 'exec_sql'
     */
    suspend fun executeRawQuery(queryOrPath: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        // If it starts with SELECT, it's SQL. We can't run SQL directly without RPC.
        // Assuming we have an RPC function 'exec_sql' or similar, or we map it to REST.
        
        // For the specific use case of checking roles: "SELECT 1 FROM usuarios_roles WHERE..."
        // We can map this to a REST request: /usuarios_roles?select=1&usuario_id=eq.X&rol_id=eq.Y
        
        if (queryOrPath.trim().startsWith("SELECT", ignoreCase = true)) {
             // Basic SQL parser for specific known queries
             if (queryOrPath.contains("FROM usuarios_roles", ignoreCase = true)) {
                 // Extract params
                 // Example: "SELECT 1 FROM usuarios_roles WHERE usuario_id = 123 AND rol_id = 2"
                 var userId = -1L
                 var roleId = -1
                 
                 val userIdMatch = Regex("usuario_id\\s*=\\s*(\\d+)").find(queryOrPath)
                 if (userIdMatch != null) userId = userIdMatch.groupValues[1].toLong()
                 
                 val roleIdMatch = Regex("rol_id\\s*=\\s*(\\d+)").find(queryOrPath)
                 if (roleIdMatch != null) roleId = roleIdMatch.groupValues[1].toInt()
                 
                 if (userId != -1L) {
                     val query = StringBuilder("usuarios_roles?usuario_id=eq.$userId")
                     if (roleId != -1) {
                         query.append("&rol_id=eq.$roleId")
                     }
                     if (queryOrPath.contains("SELECT rol_id", ignoreCase = true)) {
                         query.append("&select=rol_id")
                     } else {
                         query.append("&select=usuario_id") // Just select something to check existence
                     }
                     
                     return@withContext fetchListMap(query.toString())
                 }
             }
             else if (queryOrPath.contains("FROM video_comment_likes", ignoreCase = true)) {
                 // "SELECT id FROM video_comment_likes WHERE comment_id = X AND usuario_id = Y"
                 // "SELECT COUNT(*) as count FROM video_comment_likes WHERE comment_id = X"
                 
                 var commentId = -1L
                 var userId = -1L
                 
                 val commentIdMatch = Regex("comment_id\\s*=\\s*(\\d+)").find(queryOrPath)
                 if (commentIdMatch != null) commentId = commentIdMatch.groupValues[1].toLong()
                 
                 val userIdMatch = Regex("usuario_id\\s*=\\s*(\\d+)").find(queryOrPath)
                 if (userIdMatch != null) userId = userIdMatch.groupValues[1].toLong()
                 
                 if (commentId != -1L) {
                     val query = StringBuilder("video_comment_likes?comment_id=eq.$commentId")
                     
                     if (userId != -1L) {
                        query.append("&usuario_id=eq.$userId")
                     }
                     
                     // If it's a count query
                     if (queryOrPath.contains("COUNT(*)", ignoreCase = true)) {
                         // We need to get the count from headers or fetch all (inefficient but works for small sets)
                         // Better: REST API supports count=exact in header
                         // But fetchListMap returns list.
                         // Let's just fetch items and count them locally for now
                         val items = fetchListMap(query.toString())
                         return@withContext listOf(mapOf("count" to items.size))
                     }
                     
                     return@withContext fetchListMap(query.toString())
                 }
             }
        }
        
        // Default fallthrough (assumes it's a REST path)
        return@withContext fetchListMap(queryOrPath)
    }

    private suspend fun fetchListMap(path: String): List<Map<String, Any?>> {
        try {
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any?>>>() {}.type
                return gson.fromJson(body, type) ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "fetchListMap failed for $path", e)
            return emptyList()
        }
    }

    /**
     * Execute raw SQL query via REST API de PostgREST
     * Parsea el SQL y lo convierte en una consulta REST a Supabase
     */
    /**
     * Legacy method for raw SQL (kept to avoid breaking changes if used elsewhere)
     * Calls executeRawQuery internally
     */
    suspend fun executeRawSql(sql: String): List<Map<String, Any?>> {
         return executeRawQuery(sql)
    }

    /**
     * Fetch Topic by ID - accepts Long for Supabase bigserial compatibility
     */
    suspend fun fetchTopicById(id: Long): Topic? = withContext(Dispatchers.IO) {
        try {
            val path = "topics?id=eq.$id&select=*&limit=1"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchTopicById failed status=${resp.code}")
                    return@withContext null
                }
                val body = resp.body?.string() ?: return@withContext null
                val arr = underscoredGson.fromJson(body, Array<Topic>::class.java)
                return@withContext arr.firstOrNull()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching topic by ID=$id", e)
            return@withContext null
        }
    }

    /**
     * Fetch Rol by ID
     */
    suspend fun fetchRolById(id: Long): Rol? = withContext(Dispatchers.IO) {
        try {
            val path = "roles?id=eq.$id"
            val list = fetchList(path, Array<Rol>::class.java)
            return@withContext list.firstOrNull()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching rol by ID", e)
            return@withContext null
        }
    }

    /**
     * Fetch all Roles
     */
    suspend fun fetchRoles(): List<Rol> = fetchList("roles", Array<Rol>::class.java)

    /**
     * Fetch all Recursos
     */
    suspend fun fetchRecursos(): List<Recurso> = fetchList("recursos", Array<Recurso>::class.java)

    /**
     * Fetch all RolRecursos
     */
    suspend fun fetchRolRecursos(): List<RolRecurso> = fetchList("rol_recursos", Array<RolRecurso>::class.java)

    /**
     * Fetch Usuario and their Role by username
     */
    suspend fun fetchUsuarioWithRoleByUsername(username: String): Pair<Usuario?, Rol?> = withContext(Dispatchers.IO) {
        val user = fetchUsuarioByUsername(username)
        if (user == null) return@withContext Pair(null, null)

        val roleId = try {
            val rows = executeRawQuery("SELECT rol_id FROM usuarios_roles WHERE usuario_id = ${user.id}")
            val value = rows.firstOrNull()?.get("rol_id")
            when (value) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull()
                else -> null
            }
        } catch (_: Exception) {
            null
        }

        val rol = try {
            roleId?.let { fetchRolById(it) }
        } catch (_: Exception) {
            null
        }

        return@withContext Pair(user, rol)
    }

    /**
     * Fetch max updated_at for a table
     */
    suspend fun fetchTableMaxUpdatedAt(table: String, field: String = "updated_at"): String? = withContext(Dispatchers.IO) {
        try {
            val path = "$table?select=$field&order=$field.desc&limit=1"
            val request = buildGetRequest(path)
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrEmpty()) return@withContext null
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                if (jsonArray.size() > 0) {
                    val obj = jsonArray[0].asJsonObject
                    if (obj.has(field) && !obj.get(field).isJsonNull) {
                        return@withContext obj.get(field).asString
                    }
                }
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching max updated_at for $table", e)
            return@withContext null
        }
    }

    /**
     * Get max video ID from Supabase
     */
    suspend fun getMaxVideoIdFromSupabase(): Long = withContext(Dispatchers.IO) {
        try {
            val path = "videos?select=id&order=id.desc&limit=1"
            val request = buildGetRequest(path)
            
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrEmpty()) {
                    return@withContext 0L
                }
                
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                if (jsonArray.size() > 0) {
                    val obj = jsonArray[0].asJsonObject
                    return@withContext obj.get("id")?.asLong ?: 0L
                }
                
                return@withContext 0L
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error getting max video ID", e)
            return@withContext 0L
        }
    }

    /**
     * Fetch Courses Summary with pagination
     */
    suspend fun fetchCoursesSummary(
        limit: Int = 10,
        offset: Int = 0,
        orderBy: String = "enrollment_count",
        direction: String = "desc"
    ): Pair<List<Course>, Int> = withContext(Dispatchers.IO) {
        try {
            val path = "courses?offset=$offset&limit=$limit&order=$orderBy.$direction"
            val courses = fetchList(path, Array<Course>::class.java).toList()
            
            // Get total count
            val countPath = "courses?select=count"
            val countRequest = buildGetRequest(countPath)
            var totalCount = courses.size
            
            try {
                client.newCall(countRequest).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrEmpty()) {
                        val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                        if (jsonArray.size() > 0) {
                            totalCount = jsonArray[0].asJsonObject.get("count")?.asInt ?: courses.size
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SupabaseClient", "Could not get total count", e)
            }
            
            return@withContext Pair(courses, totalCount)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching courses summary", e)
            return@withContext Pair(emptyList(), 0)
        }
    }

    /**
     * Search videos by title, username, or category
     */
    suspend fun searchVideos(
        query: String,
        searchType: String = "all",
        limit: Int = 50
    ): List<VideoData> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            Log.w("SupabaseClient", "searchVideos: not configured, returning empty list")
            return@withContext emptyList()
        }

        try {
            val searchQuery = query.trim().lowercase()
            if (searchQuery.isEmpty()) {
                Log.d("SupabaseClient", "Empty search query, fetching all videos")
                return@withContext fetchVideos().take(limit)
            }

            // Special handling for username search since 'videos' table lacks username column
            if (searchType == "username") {
                try {
                    // 1. Find users matching the query (partial match) using 'username' column
                    val users = fetchList<Usuario>("usuarios?username=ilike.*${searchQuery}*", Array<Usuario>::class.java)
                    if (users.isNotEmpty()) {
                        val userIds = users.map { it.id }
                        val userIdToNameMap = users.associate { it.id to it.usuario }
                        
                        // 2. Find courses created by these users
                        val courses = fetchList<Course>("courses?creator_user_id=in.(${userIds.joinToString(",")})", Array<Course>::class.java)
                        if (courses.isNotEmpty()) {
                            val courseIds = courses.map { it.id }
                            val courseIdToUsernameMap = courses.associate { it.id to (userIdToNameMap[it.creatorUserId] ?: "unknown") }
                            
                            // 3. Find videos in these courses
                            val urlPath = "videos?course_id=in.(${courseIds.joinToString(",")})&order=id.desc&limit=${limit}"
                            val request = buildGetRequest(urlPath)
                            
                            client.newCall(request).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val body = resp.body?.string()
                                    if (body != null) {
                                        val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                                        val results = mutableListOf<VideoData>()
                                        for (element in jsonArray) {
                                            try {
                                                val obj = element.asJsonObject
                                                val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                                                val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                                val courseId = obj.get("course_id")?.takeIf { !it.isJsonNull }?.asLong
                                                
                                                val videoUriString = when {
                                                    obj.has("video_uri_string") && !obj.get("video_uri_string").isJsonNull -> obj.get("video_uri_string").asString
                                                    obj.has("video_uri") && !obj.get("video_uri").isJsonNull -> obj.get("video_uri").asString
                                                    obj.has("video_url") && !obj.get("video_url").isJsonNull -> obj.get("video_url").asString
                                                    else -> null
                                                }
                                                val localFilePath = obj.get("local_file_path")?.takeIf { !it.isJsonNull }?.asString
                                                val thumbnailUri = when {
                                                    obj.has("thumbnail_uri") && !obj.get("thumbnail_uri").isJsonNull -> obj.get("thumbnail_uri").asString
                                                    obj.has("thumbnail") && !obj.get("thumbnail").isJsonNull -> obj.get("thumbnail").asString
                                                    else -> null
                                                }
                                                val timestamp = try {
                                                    obj.get("timestamp")?.takeIf { !it.isJsonNull }?.asLong
                                                        ?: obj.get("created_at")?.takeIf { !it.isJsonNull }?.asString?.let { java.time.Instant.parse(it).toEpochMilli() }
                                                        ?: System.currentTimeMillis()
                                                } catch (_: Exception) { System.currentTimeMillis() }
                                                val isPaid = obj.get("is_paid")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                                val price = try { obj.get("price")?.takeIf { !it.isJsonNull }?.asDouble } catch (_: Exception) { null }

                                                // Inject username
                                                val uName = courseId?.let { courseIdToUsernameMap[it] } ?: "unknown"
                                                
                                                val video = VideoData(
                                                    id = id,
                                                    description = description,
                                                    title = title,
                                                    videoUriString = videoUriString,
                                                    localFilePath = localFilePath,
                                                    timestamp = timestamp,
                                                    isPaid = isPaid,
                                                    thumbnailUri = thumbnailUri,
                                                    price = price,
                                                    courseId = courseId
                                                ).apply {
                                                    this.username = uName
                                                }
                                                results.add(video)
                                            } catch (e: Exception) {
                                                Log.e("SupabaseClient", "Error parsing video in username search", e)
                                            }
                                        }
                                        return@withContext results
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseClient", "Error in username search", e)
                }
                return@withContext emptyList()
            }

            val urlPath = when (searchType) {
                "title" -> "videos?title=ilike.*${searchQuery}*&order=id.desc&limit=${limit}"
                "category" -> "videos?description=ilike.*${searchQuery}*&order=id.desc&limit=${limit}"
                // For 'all', include username search as well
                else -> "videos?or=(title.ilike.*${searchQuery}*,description.ilike.*${searchQuery}*)&order=id.desc&limit=${limit}"
            }
            
            val request = buildGetRequest(urlPath)
            
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                
                val results = mutableListOf<VideoData>()
                for (element in jsonArray) {
                    try {
                        val obj = element.asJsonObject
                        
                        // Robust parsing logic similar to fetchVideos
                        val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                        val username = when {
                            obj.has("username") && !obj.get("username").isJsonNull -> obj.get("username").asString
                            obj.has("creator_username") && !obj.get("creator_username").isJsonNull -> obj.get("creator_username").asString
                            obj.has("user") && !obj.get("user").isJsonNull -> obj.get("user").asString
                            else -> "unknown"
                        }
                        val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                        val courseId = obj.get("course_id")?.takeIf { !it.isJsonNull }?.asLong
                        
                        val videoUriString = when {
                            obj.has("video_uri_string") && !obj.get("video_uri_string").isJsonNull -> obj.get("video_uri_string").asString
                            obj.has("video_uri") && !obj.get("video_uri").isJsonNull -> obj.get("video_uri").asString
                            obj.has("video_url") && !obj.get("video_url").isJsonNull -> obj.get("video_url").asString
                            else -> null
                        }
                        
                        val localFilePath = obj.get("local_file_path")?.takeIf { !it.isJsonNull }?.asString
                        
                        val thumbnailUri = when {
                            obj.has("thumbnail_uri") && !obj.get("thumbnail_uri").isJsonNull -> obj.get("thumbnail_uri").asString
                            obj.has("thumbnail") && !obj.get("thumbnail").isJsonNull -> obj.get("thumbnail").asString
                            else -> null
                        }
                        
                        val timestamp = try {
                            obj.get("timestamp")?.takeIf { !it.isJsonNull }?.asLong
                                ?: obj.get("created_at")?.takeIf { !it.isJsonNull }?.asString?.let { java.time.Instant.parse(it).toEpochMilli() }
                                ?: System.currentTimeMillis()
                        } catch (_: Exception) { System.currentTimeMillis() }
                        
                        val isPaid = obj.get("is_paid")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                        val price = try { obj.get("price")?.takeIf { !it.isJsonNull }?.asDouble } catch (_: Exception) { null }

                        val video = VideoData(
                            id = id,
                            description = description,
                            title = title,
                            videoUriString = videoUriString,
                            localFilePath = localFilePath,
                            timestamp = timestamp,
                            isPaid = isPaid,
                            thumbnailUri = thumbnailUri,
                            price = price,
                            courseId = courseId
                        ).apply {
                            this.username = username
                        }
                        results.add(video)
                    } catch (e: Exception) {
                        Log.w("SupabaseClient", "Failed to parse video in search results", e)
                    }
                }
                return@withContext results
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception in searchVideos", e)
            return@withContext emptyList()
        }
    }

    suspend fun fetchVideosPaginatedOrThrow(offset: Int, limit: Int): Pair<List<VideoData>, Int> = withContext(Dispatchers.IO) {
        // Use embedding to fetch username in a single request: video -> course -> user
        // Syntax: videos?select=*,courses(usuarios(username))
        // Note: We need to handle the mapping manually since Gson won't flatten the nested object
        val path = "videos?select=*,courses(usuarios(username))&offset=$offset&limit=$limit&order=timestamp.desc"
        
        val request = buildGetRequest(path)
        val videos = mutableListOf<VideoData>()
        
        try {
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                        for (element in jsonArray) {
                            try {
                                val obj = element.asJsonObject
                                
                                // Extract basic fields
                                val id = obj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                                val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                val courseId = obj.get("course_id")?.takeIf { !it.isJsonNull }?.asLong
                                
                                // Extract nested username
                                var username = "unknown"
                                if (obj.has("courses") && !obj.get("courses").isJsonNull) {
                                    val courseObj = obj.get("courses").asJsonObject
                                    if (courseObj.has("usuarios") && !courseObj.get("usuarios").isJsonNull) {
                                        val userObj = courseObj.get("usuarios").asJsonObject
                                        if (userObj.has("username") && !userObj.get("username").isJsonNull) {
                                            username = userObj.get("username").asString
                                        }
                                    }
                                }
                                
                                val videoUriString = when {
                                    obj.has("video_uri_string") && !obj.get("video_uri_string").isJsonNull -> obj.get("video_uri_string").asString
                                    obj.has("video_uri") && !obj.get("video_uri").isJsonNull -> obj.get("video_uri").asString
                                    obj.has("video_url") && !obj.get("video_url").isJsonNull -> obj.get("video_url").asString
                                    else -> null
                                }
                                
                                val localFilePath = obj.get("local_file_path")?.takeIf { !it.isJsonNull }?.asString
                                val thumbnailUri = obj.get("thumbnail_uri")?.takeIf { !it.isJsonNull }?.asString
                                val timestamp = obj.get("timestamp")?.takeIf { !it.isJsonNull }?.asLong ?: System.currentTimeMillis()
                                val isPaid = obj.get("is_paid")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                                val price = try { obj.get("price")?.takeIf { !it.isJsonNull }?.asDouble } catch (_: Exception) { null }

                                val v = VideoData(
                                    id = id,
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
                                v.username = username
                                videos.add(v)
                            } catch (e: Exception) {
                                Log.e("SupabaseClient", "Error parsing video with user", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
             Log.e("SupabaseClient", "Error fetching videos with users", e)
        }
        
        // Get total count (separate request still needed for count)
        val countPath = "videos?select=count"
        val countRequest = buildGetRequest(countPath)
        var totalCount = videos.size

        try {
            client.newCall(countRequest).execute().use { response ->
                val body = response.body?.string()
                if (response.isSuccessful && !body.isNullOrEmpty()) {
                    val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                    if (jsonArray.size() > 0) {
                        totalCount = jsonArray[0].asJsonObject.get("count")?.asInt ?: videos.size
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Could not get total count", e)
        }

        return@withContext Pair(videos, totalCount)
    }

    /**
     * Fetch Videos Paginated
     */
    suspend fun fetchVideosPaginated(offset: Int, limit: Int): Pair<List<VideoData>, Int> = withContext(Dispatchers.IO) {
        try {
            val path = "videos?offset=$offset&limit=$limit&order=timestamp.desc"
            var videos = fetchList(path, Array<VideoData>::class.java).toList()
            
            // Get total count
            val countPath = "videos?select=count"
            val countRequest = buildGetRequest(countPath)
            var totalCount = videos.size

            try {
                client.newCall(countRequest).execute().use { response ->
                    val body = response.body?.string()
                    if (response.isSuccessful && !body.isNullOrEmpty()) {
                        val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                        if (jsonArray.size() > 0) {
                            totalCount = jsonArray[0].asJsonObject.get("count")?.asInt ?: videos.size
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SupabaseClient", "Could not get total count", e)
            }

            return@withContext Pair(videos, totalCount)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching videos paginated", e)
            return@withContext Pair(emptyList(), 0)
        }
    }

    /**
     * Insert Topic using trigger (returns generated ID)
     */
    suspend fun insertTopicUsingTrigger(topic: Topic, courseTitle: String? = null): Long? = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>(
                "course_id" to topic.courseId,
                "name" to topic.name,
                "description" to topic.description,
                "order_index" to topic.orderIndex
            )
            
            if (courseTitle != null) {
                map["course_title"] = courseTitle
            }
            
            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/topics"
            
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()
            
            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) return@withContext null
                
                if (respBody.isNullOrEmpty()) return@withContext null
                
                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                    if (jsonArray.size() > 0) {
                        val idElem = jsonArray[0].asJsonObject.get("id")
                        return@withContext idElem?.asLong
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseClient", "Error parsing insert response", e)
                }
                
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error inserting topic", e)
            return@withContext null
        }
    }

    /**
     * Update Video
     */
    suspend fun updateVideo(video: VideoData): Boolean = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>()
            map["title"] = video.title
            map["description"] = video.description
            map["video_uri"] = video.videoUri
            map["thumbnail_uri"] = video.thumbnailUri
            map["username"] = video.username
            map["timestamp"] = video.timestamp
            
            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/videos?id=eq.${video.id}"
            
            val request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()
            
            client.newCall(request).execute().use { resp ->
                return@withContext resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "updateVideo exception", e)
            return@withContext false
        }
    }

    /**
     * Upsert student progress to Supabase progreso_estudiante table
     */
    suspend fun upsertProgresoEstudiante(progreso: com.example.tareamov.data.entity.ProgresoEstudiante): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext false
            
            val payload = mapOf(
                "usuario_estudiante" to progreso.usuarioEstudiante,
                "curso_id" to progreso.cursoId,
                "tareas_completadas" to progreso.tareasCompletadas,
                "tareas_totales" to progreso.tareasTotales,
                "porcentaje_progreso" to progreso.porcentajeProgreso,
                "calificacion_ponderada" to progreso.calificacionPonderada,
                "promedio" to (progreso.promedio ?: progreso.calificacionPonderada ?: 0f),
                "ultima_calculada_en" to java.time.Instant.ofEpochMilli(progreso.ultimaCalculadaEn).toString(),
                "certificado_emitido_en" to progreso.certificadoEmitidoEn?.let { java.time.Instant.ofEpochMilli(it).toString() },
                "creado_en" to java.time.Instant.ofEpochMilli(progreso.creadoEn).toString()
            )
            
            val json = gson.toJson(payload)
            val body = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/progreso_estudiante")
                .post(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .build()

            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful || response.code == 201
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "upsertProgresoEstudiante exception", e)
            return@withContext false
        }
    }
    
    /**
     * Fetch student progress from Supabase
     */
    suspend fun fetchProgresoEstudiante(userId: Long, courseId: Long): com.example.tareamov.data.entity.ProgresoEstudiante? = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext null
            
            val request = buildGetRequest("progreso_estudiante?usuario_estudiante=eq.$userId&curso_id=eq.$courseId&limit=1")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                val json = response.body?.string() ?: return@withContext null
                val jsonArray = gson.fromJson(json, com.google.gson.JsonArray::class.java)
                
                if (jsonArray.size() == 0) return@withContext null
                
                val obj = jsonArray[0].asJsonObject
                
                // Helper to parse timestamp
                fun parseTs(s: String?): Long? = try { java.time.Instant.parse(s).toEpochMilli() } catch(e: Exception) { null }

                return@withContext com.example.tareamov.data.entity.ProgresoEstudiante(
                    usuarioEstudiante = userId,
                    cursoId = obj.get("curso_id")?.asLong ?: courseId,
                    tareasCompletadas = obj.get("tareas_completadas")?.asInt ?: 0,
                    tareasTotales = obj.get("tareas_totales")?.asInt ?: 0,
                    porcentajeProgreso = obj.get("porcentaje_progreso")?.asFloat ?: 0f,
                    calificacionPonderada = obj.get("calificacion_ponderada")?.asFloat,
                    promedio = obj.get("promedio")?.asFloat,
                    estado = obj.get("estado")?.asString,
                    ultimaCalculadaEn = parseTs(obj.get("ultima_calculada_en")?.asString) ?: System.currentTimeMillis(),
                    certificadoEmitidoEn = parseTs(obj.get("certificado_emitido_en")?.asString),
                    creadoEn = parseTs(obj.get("creado_en")?.asString) ?: System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "fetchProgresoEstudiante exception", e)
            return@withContext null
        }
    }
    
    /**
     * Fetch all student progress for a course from Supabase
     */
    suspend fun fetchProgresosByCurso(courseId: Long): List<com.example.tareamov.data.entity.ProgresoEstudiante> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()
            
            val request = buildGetRequest("progreso_estudiante?curso_id=eq.$courseId")
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                
                val json = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = gson.fromJson(json, com.google.gson.JsonArray::class.java)
                
                fun parseTs(s: String?): Long? = try { java.time.Instant.parse(s).toEpochMilli() } catch(e: Exception) { null }

                return@withContext jsonArray.map { element ->
                    val obj = element.asJsonObject
                    com.example.tareamov.data.entity.ProgresoEstudiante(
                        usuarioEstudiante = obj.get("usuario_estudiante")?.asLong ?: 0L,
                        cursoId = obj.get("curso_id")?.asLong ?: courseId,
                        tareasCompletadas = obj.get("tareas_completadas")?.asInt ?: 0,
                        tareasTotales = obj.get("tareas_totales")?.asInt ?: 0,
                        porcentajeProgreso = obj.get("porcentaje_progreso")?.asFloat ?: 0f,
                        calificacionPonderada = obj.get("calificacion_ponderada")?.asFloat,
                        promedio = obj.get("promedio")?.asFloat,
                        estado = obj.get("estado")?.asString,
                        ultimaCalculadaEn = parseTs(obj.get("ultima_calculada_en")?.asString) ?: System.currentTimeMillis(),
                        certificadoEmitidoEn = parseTs(obj.get("certificado_emitido_en")?.asString),
                        creadoEn = parseTs(obj.get("creado_en")?.asString) ?: System.currentTimeMillis()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "fetchProgresosByCurso exception", e)
            return@withContext emptyList()
        }
    }

    /**
     * Fetch Videos ordered
     */
    suspend fun fetchVideosOrdered(orderBy: String, direction: String = "asc"): List<VideoData> = withContext(Dispatchers.IO) {
        try {
            val path = "videos?order=$orderBy.$direction"
            return@withContext fetchList(path, Array<VideoData>::class.java).toList()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching videos ordered", e)
            return@withContext emptyList()
        }
    }

    /**
     * Fetch Topics ordered
     */
    suspend fun fetchTopicsOrdered(orderBy: String, direction: String = "asc"): List<Topic> = withContext(Dispatchers.IO) {
        try {
            val path = "topics?order=$orderBy.$direction"
            return@withContext fetchList(path, Array<Topic>::class.java).toList()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching topics ordered", e)
            return@withContext emptyList()
        }
    }

    /**
     * Fetch ContentItems ordered - using manual parsing for proper body->uriString mapping
     */
    suspend fun fetchContentItemsOrdered(orderBy: String, direction: String = "asc"): List<ContentItem> = withContext(Dispatchers.IO) {
        try {
            val path = "content_items?order=$orderBy.$direction"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchContentItemsOrdered failed status=${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                val items = mutableListOf<ContentItem>()
                
                jsonArray.forEach { element ->
                    val item = parseContentItemFromJson(element.asJsonObject)
                    items.add(item)
                }
                
                return@withContext items
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching content items ordered", e)
            return@withContext emptyList()
        }
    }

    /**
     * Fetch Tasks ordered
     */
    suspend fun fetchTasksOrdered(orderBy: String, direction: String = "asc"): List<Task> = withContext(Dispatchers.IO) {
        try {
            val path = "tasks?order=$orderBy.$direction"
            return@withContext fetchList(path, Array<Task>::class.java).toList()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching tasks ordered", e)
            return@withContext emptyList()
        }
    }

    /**
     * Fetch Courses ordered
     */
    suspend fun fetchCoursesOrdered(orderBy: String, direction: String = "asc"): List<Course> = withContext(Dispatchers.IO) {
        try {
            val path = "courses?order=$orderBy.$direction"
            return@withContext fetchList(path, Array<Course>::class.java).toList()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching courses ordered", e)
            return@withContext emptyList()
        }
    }

    /**
     * Get next available video ID (alias de getMaxVideoIdFromSupabase + 1)
     */
    suspend fun getNextVideoId(): Long = withContext(Dispatchers.IO) {
        try {
            val maxId = getMaxVideoIdFromSupabase()
            return@withContext maxOf(maxId + 1, 83L) // Start from 83 minimum
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error getting next video ID", e)
            return@withContext 83L
        }
    }

    /**
     * Insert a subscription to Supabase
     */
    suspend fun subscribeToCreator(subscriberId: Long, creatorId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val subscription = Subscription(
                subscriberId = subscriberId,
                creatorId = creatorId,
                subscriptionDate = System.currentTimeMillis()
            )
            
            val json = underscoredGson.toJson(subscription)
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/subscriptions")
                .post(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()

            requestListener?.invoke("POST $baseUrl/rest/v1/subscriptions")
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d("SupabaseClient", "Subscription inserted successfully")
                return@withContext true
            } else {
                Log.e("SupabaseClient", "Failed to insert subscription: ${response.code} ${response.message}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "subscribeToCreator exception", e)
            return@withContext false
        }
    }

    /**
     * Delete a subscription from Supabase
     */
    suspend fun unsubscribeFromCreator(subscriberId: Long, creatorId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/subscriptions?subscriber_id=eq.$subscriberId&creator_id=eq.$creatorId")
                .delete()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            requestListener?.invoke("DELETE $baseUrl/rest/v1/subscriptions")
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                Log.d("SupabaseClient", "Subscription deleted successfully")
                return@withContext true
            } else {
                Log.e("SupabaseClient", "Failed to delete subscription: ${response.code} ${response.message}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "unsubscribeFromCreator exception", e)
            return@withContext false
        }
    }

    /**
     * Actualiza el campo certificado_emitido_en cuando se genera un certificado
     */
    suspend fun updateCertificateIssuedDate(
        studentUserId: Long,
        courseId: Long
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                Log.e("SupabaseClient", "Supabase not configured, cannot update certificate date")
                return@withContext false
            }
            
            val now = java.time.Instant.now().toString()
            val map = mapOf("certificado_emitido_en" to now)
            val body = gson.toJson(map).toRequestBody(jsonMedia)
            
            // PATCH usando composite key en query params
            val url = "$baseUrl/rest/v1/progreso_estudiante?usuario_estudiante=eq.$studentUserId&curso_id=eq.$courseId"
            
            val request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()
            
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: ""
                    Log.e("SupabaseClient", "Failed to update certificate date: ${resp.code} $bodyStr")
                    return@withContext false
                }
                
                Log.i("SupabaseClient", "✅ Certificate issued date updated for userId=$studentUserId in course $courseId")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception updating certificate date", e)
            return@withContext false
        }
    }

    /**
     * Actualiza la URL del certificado en progreso_estudiante
     */
    suspend fun updateCertificateUrl(
        studentUserId: Long,
        courseId: Long,
        certificateUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                Log.e("SupabaseClient", "Supabase not configured, cannot update certificate URL")
                return@withContext false
            }
            
            val map = mapOf("certificado_url" to certificateUrl)
            val body = gson.toJson(map).toRequestBody(jsonMedia)
            
            // PATCH usando composite key en query params
            val url = "$baseUrl/rest/v1/progreso_estudiante?usuario_estudiante=eq.$studentUserId&curso_id=eq.$courseId"
            
            val request = Request.Builder()
                .url(url)
                .patch(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()
            
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: ""
                    Log.e("SupabaseClient", "Failed to update certificate URL: ${resp.code} $bodyStr")
                    return@withContext false
                }
                
                Log.i("SupabaseClient", "✅ Certificate URL updated for userId=$studentUserId in course $courseId: $certificateUrl")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception updating certificate URL", e)
            return@withContext false
        }
    }

    // Check if a user is enrolled in a course (exists in progreso_estudiante)
    suspend fun isUserEnrolled(userId: Long, courseId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // Query progreso_estudiante table for matching usuario_estudiante and curso_id
            // We use the path relative to /rest/v1/ as buildGetRequest prepends the base URL
            // Use select=curso_id to be safe, as we know it exists
            val path = "progreso_estudiante?usuario_estudiante=eq.$userId&curso_id=eq.$courseId&select=curso_id"
            val request = buildGetRequest(path)
            
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("SupabaseClient", "isUserEnrolled failed: ${resp.code} ${resp.message}")
                    return@withContext false
                }
                val body = resp.body?.string() ?: return@withContext false
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                val isEnrolled = jsonArray.size() > 0
                Log.d("SupabaseClient", "isUserEnrolled(userId=$userId, courseId=$courseId) = $isEnrolled")
                return@withContext isEnrolled
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error checking enrollment status", e)
            return@withContext false
        }
    }

    // Fetch all course IDs where the user is enrolled
    suspend fun fetchEnrolledCourseIds(userId: Long): List<Long> = withContext(Dispatchers.IO) {
        try {
            val path = "progreso_estudiante?usuario_estudiante=eq.$userId&select=curso_id"
            val request = buildGetRequest(path)
            
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                val ids = mutableListOf<Long>()
                for (element in jsonArray) {
                    val obj = element.asJsonObject
                    if (obj.has("curso_id") && !obj.get("curso_id").isJsonNull) {
                        ids.add(obj.get("curso_id").asLong)
                    }
                }
                return@withContext ids
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching enrolled course IDs", e)
            return@withContext emptyList()
        }
    }

    // Fetch subscription count for a user (following)
    suspend fun fetchSubscriptionCount(subscriberId: Long): Long = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "Fetching subscription count (following) for subscriberId: $subscriberId")
            val path = "subscriptions?subscriber_id=eq.$subscriberId&select=creator_id&limit=1"
            val url = "$baseUrl/rest/v1/$path"
            
            Log.d("SupabaseClient", "Request URL: $url")
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Range", "0-0")
                .addHeader("Prefer", "count=exact")
                .build()

            client.newCall(request).execute().use { response ->
                Log.d("SupabaseClient", "Response code: ${response.code}")
                val rangeHeader = response.header("Content-Range")
                Log.d("SupabaseClient", "Content-Range header: $rangeHeader")
                if (rangeHeader != null && rangeHeader.contains("/")) {
                    val total = rangeHeader.substringAfter("/").toLongOrNull() ?: 0L
                    Log.d("SupabaseClient", "Subscription count for subscriberId $subscriberId: $total")
                    return@withContext total
                }
                Log.w("SupabaseClient", "No Content-Range header found or invalid format")
                0L
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching subscription count for subscriberId $subscriberId", e)
            0L
        }
    }

    // Fetch graded submissions for a specific course (grade > 0)
    // Returns a list of maps containing submission data + task title + student username
    suspend fun fetchGradedSubmissionsForCourse(courseId: Long): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()

            // Step 1: Fetch submissions with task info (without usuarios join to avoid FK issues)
            val url = "$baseUrl/rest/v1/task_submissions?select=id,grade,student_id,task_id,submission_date,tasks!inner(title,topics!inner(course_id))&tasks.topics.course_id=eq.$courseId&grade=gt.0"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val submissions = mutableListOf<MutableMap<String, Any>>()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e("SupabaseClient", "fetchGradedSubmissionsForCourse failed: ${response.code} - $errorBody")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                for (elem in jsonArray) {
                    val obj = elem.asJsonObject
                    val map = mutableMapOf<String, Any>()
                    
                    map["id"] = obj.get("id").asLong
                    map["grade"] = if (obj.has("grade") && !obj.get("grade").isJsonNull) obj.get("grade").asFloat else 0f
                    map["student_id"] = obj.get("student_id").asLong
                    map["task_id"] = obj.get("task_id").asLong
                    if (obj.has("submission_date") && !obj.get("submission_date").isJsonNull) {
                        map["submission_date"] = obj.get("submission_date").asString
                    }
                    
                    // Extract Task Title
                    if (obj.has("tasks") && !obj.get("tasks").isJsonNull) {
                        val taskObj = obj.get("tasks").asJsonObject
                        if (taskObj.has("title")) {
                            map["task_title"] = taskObj.get("title").asString
                        }
                    }
                    
                    submissions.add(map)
                }
            }
            
            // Step 2: Fetch usernames separately for all unique student_ids
            if (submissions.isNotEmpty()) {
                val studentIds = submissions.mapNotNull { it["student_id"] as? Long }.distinct()
                val usernames = mutableMapOf<Long, String>()
                
                // Fetch all usuarios and create a map - use 'username' column name from DB
                try {
                    val usersUrl = "$baseUrl/rest/v1/usuarios?select=id,username&id=in.(${studentIds.joinToString(",")})"
                    val usersRequest = Request.Builder()
                        .url(usersUrl)
                        .get()
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .build()
                    
                    client.newCall(usersRequest).execute().use { usersResponse ->
                        if (usersResponse.isSuccessful) {
                            val usersBody = usersResponse.body?.string() ?: "[]"
                            Log.d("SupabaseClient", "Users response: $usersBody")
                            val usersArray = com.google.gson.JsonParser.parseString(usersBody).asJsonArray
                            for (userElem in usersArray) {
                                val userObj = userElem.asJsonObject
                                val userId = userObj.get("id").asLong
                                // Try 'username' first (actual DB column), then 'usuario' as fallback
                                val username = when {
                                    userObj.has("username") && !userObj.get("username").isJsonNull -> userObj.get("username").asString
                                    userObj.has("usuario") && !userObj.get("usuario").isJsonNull -> userObj.get("usuario").asString
                                    else -> "Usuario_$userId"
                                }
                                usernames[userId] = username
                            }
                        } else {
                            Log.e("SupabaseClient", "Failed to fetch users: ${usersResponse.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SupabaseClient", "Failed to fetch usernames for submissions", e)
                }
                
                // Step 3: Add usernames to submissions
                for (submission in submissions) {
                    val studentId = submission["student_id"] as? Long
                    if (studentId != null) {
                        submission["student_username"] = usernames[studentId] ?: "Usuario_$studentId"
                    }
                }
            }
            
            return@withContext submissions
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching graded submissions for course $courseId", e)
            emptyList()
        }
    }
    
    // Fetch ALL submissions for a specific course (both graded and ungraded)
    // Excludes submissions from the course creator
    suspend fun fetchAllSubmissionsForCourse(courseId: Long): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()

            // First, get the course creator_user_id to exclude their submissions
            var creatorUserId: Long? = null
            try {
                val courseUrl = "$baseUrl/rest/v1/courses?select=creator_user_id&id=eq.$courseId"
                val courseRequest = Request.Builder()
                    .url(courseUrl)
                    .get()
                    .addHeader("apikey", effectiveApiKey())
                    .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                    .build()
                
                client.newCall(courseRequest).execute().use { courseResponse ->
                    if (courseResponse.isSuccessful) {
                        val courseBody = courseResponse.body?.string() ?: "[]"
                        val courseArray = com.google.gson.JsonParser.parseString(courseBody).asJsonArray
                        if (courseArray.size() > 0) {
                            val courseObj = courseArray[0].asJsonObject
                            if (courseObj.has("creator_user_id") && !courseObj.get("creator_user_id").isJsonNull) {
                                creatorUserId = courseObj.get("creator_user_id").asLong
                                Log.d("SupabaseClient", "Course $courseId creator_user_id: $creatorUserId")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("SupabaseClient", "Could not fetch course creator_user_id", e)
            }

            // Fetch ALL submissions with task info (not just grade > 0)
            val url = "$baseUrl/rest/v1/task_submissions?select=id,grade,student_id,task_id,submission_date,file_name,tasks!inner(title,topics!inner(course_id))&tasks.topics.course_id=eq.$courseId"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()

            val submissions = mutableListOf<MutableMap<String, Any>>()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e("SupabaseClient", "fetchAllSubmissionsForCourse failed: ${response.code} - $errorBody")
                    return@withContext emptyList()
                }
                val body = response.body?.string() ?: return@withContext emptyList()
                Log.d("SupabaseClient", "All submissions response: $body")
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                for (elem in jsonArray) {
                    val obj = elem.asJsonObject
                    val map = mutableMapOf<String, Any>()
                    
                    map["id"] = obj.get("id").asLong
                    map["grade"] = if (obj.has("grade") && !obj.get("grade").isJsonNull) obj.get("grade").asFloat else 0f
                    map["student_id"] = if (obj.has("student_id") && !obj.get("student_id").isJsonNull) obj.get("student_id").asLong else 0L
                    map["task_id"] = obj.get("task_id").asLong
                    if (obj.has("submission_date") && !obj.get("submission_date").isJsonNull) {
                        map["submission_date"] = obj.get("submission_date").asLong
                    }
                    if (obj.has("file_name") && !obj.get("file_name").isJsonNull) {
                        map["file_name"] = obj.get("file_name").asString
                    }
                    
                    // Extract Task Title
                    if (obj.has("tasks") && !obj.get("tasks").isJsonNull) {
                        val taskObj = obj.get("tasks").asJsonObject
                        if (taskObj.has("title")) {
                            map["task_title"] = taskObj.get("title").asString
                        }
                    }
                    
                    submissions.add(map)
                }
            }
            
            // Fetch usernames for all student_ids
            if (submissions.isNotEmpty()) {
                val studentIds = submissions.mapNotNull { (it["student_id"] as? Long)?.takeIf { id -> id > 0 } }.distinct()
                val usernames = mutableMapOf<Long, String>()
                
                if (studentIds.isNotEmpty()) {
                    try {
                        val usersUrl = "$baseUrl/rest/v1/usuarios?select=id,usuario&id=in.(${studentIds.joinToString(",")})"
                        val usersRequest = Request.Builder()
                            .url(usersUrl)
                            .get()
                            .addHeader("apikey", apiKey)
                            .addHeader("Authorization", "Bearer $apiKey")
                            .build()
                        
                        client.newCall(usersRequest).execute().use { usersResponse ->
                            if (usersResponse.isSuccessful) {
                                val usersBody = usersResponse.body?.string() ?: "[]"
                                val usersArray = com.google.gson.JsonParser.parseString(usersBody).asJsonArray
                                for (userElem in usersArray) {
                                    val userObj = userElem.asJsonObject
                                    val userId = userObj.get("id").asLong
                                    val username = when {
                                        userObj.has("usuario") && !userObj.get("usuario").isJsonNull -> userObj.get("usuario").asString
                                        else -> "Usuario_$userId"
                                    }
                                    usernames[userId] = username
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("SupabaseClient", "Failed to fetch usernames for all submissions", e)
                    }
                }
                
                // Add usernames to submissions and filter out course creator
                val filteredSubmissions = mutableListOf<MutableMap<String, Any>>()
                for (submission in submissions) {
                    val studentId = submission["student_id"] as? Long
                    
                    // Skip submissions from course creator
                    if (creatorUserId != null && studentId == creatorUserId) {
                        Log.d("SupabaseClient", "Filtering out submission from course creator (student_id=$studentId)")
                        continue
                    }
                    
                    if (studentId != null && studentId > 0) {
                        submission["student_username"] = usernames[studentId] ?: "Usuario_$studentId"
                    } else {
                        submission["student_username"] = "Usuario desconocido"
                    }
                    filteredSubmissions.add(submission)
                }
                submissions.clear()
                submissions.addAll(filteredSubmissions)
            }
            
            Log.d("SupabaseClient", "Loaded ${submissions.size} total submissions for course $courseId")
            return@withContext submissions
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching all submissions for course $courseId", e)
            emptyList()
        }
    }
    
    // Fetch courses by creator user ID
    suspend fun fetchCoursesByCreator(creatorUserId: Long): List<Course> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()
            
            val url = "$baseUrl/rest/v1/courses?select=*&creator_user_id=eq.$creatorUserId&order=created_at.desc"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e("SupabaseClient", "fetchCoursesByCreator failed: ${response.code} - $errorBody")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                val courses = mutableListOf<Course>()
                for (elem in jsonArray) {
                    val obj = elem.asJsonObject
                    courses.add(
                        Course(
                            id = obj.get("id").asLong,
                            title = obj.get("title")?.asString ?: "",
                            description = if (obj.has("description") && !obj.get("description").isJsonNull) obj.get("description").asString else "",
                            creatorUserId = creatorUserId,
                            category = if (obj.has("category") && !obj.get("category").isJsonNull) obj.get("category").asString else null,
                            isPublished = if (obj.has("is_published") && !obj.get("is_published").isJsonNull) obj.get("is_published").asBoolean else true,
                            thumbnailUri = if (obj.has("thumbnail_uri") && !obj.get("thumbnail_uri").isJsonNull) obj.get("thumbnail_uri").asString else null
                        )
                    )
                }
                
                Log.d("SupabaseClient", "Loaded ${courses.size} courses for creator $creatorUserId")
                return@withContext courses
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching courses by creator $creatorUserId", e)
            emptyList()
        }
    }

    // ========== VIDEO LIKES OPERATIONS ==========
    
    /**
     * Get like count for a video from Supabase
     */
    suspend fun getVideoLikeCount(videoId: Long): Int? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/video_likes?video_id=eq.$videoId&select=like_count"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            requestListener?.invoke(url)
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("SupabaseClient", "getVideoLikeCount failed: ${response.code}")
                    return@withContext null
                }
                
                val body = response.body?.string() ?: return@withContext null
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                if (jsonArray.size() > 0) {
                    return@withContext jsonArray[0].asJsonObject.get("like_count")?.asInt ?: 0
                }
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error getting like count for video $videoId", e)
            null
        }
    }
    
    /**
     * Increment like count for a video (or create entry if not exists)
     */
    suspend fun incrementVideoLike(videoId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val key = effectiveApiKey()
            
            // First try to update existing record
            val updateUrl = "$baseUrl/rest/v1/rpc/increment_video_like"
            val updateBody = gson.toJson(mapOf("p_video_id" to videoId)).toRequestBody(jsonMedia)
            
            val updateRequest = Request.Builder()
                .url(updateUrl)
                .post(updateBody)
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .build()
            
            client.newCall(updateRequest).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("SupabaseClient", "Incremented like for video $videoId via RPC")
                    return@withContext true
                }
            }
            
            // Fallback: Check if record exists and upsert
            val existingCount = getVideoLikeCount(videoId)
            if (existingCount != null) {
                // Update existing
                val patchUrl = "$baseUrl/rest/v1/video_likes?video_id=eq.$videoId"
                val patchBody = gson.toJson(mapOf("like_count" to (existingCount + 1))).toRequestBody(jsonMedia)
                
                val patchRequest = Request.Builder()
                    .url(patchUrl)
                    .patch(patchBody)
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .build()
                
                client.newCall(patchRequest).execute().use { resp ->
                    return@withContext resp.isSuccessful
                }
            } else {
                // Insert new
                val insertUrl = "$baseUrl/rest/v1/video_likes"
                val insertBody = gson.toJson(mapOf(
                    "video_id" to videoId,
                    "like_count" to 1
                )).toRequestBody(jsonMedia)
                
                val insertRequest = Request.Builder()
                    .url(insertUrl)
                    .post(insertBody)
                    .addHeader("apikey", key)
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Prefer", "return=minimal")
                    .build()
                
                client.newCall(insertRequest).execute().use { resp ->
                    return@withContext resp.isSuccessful
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error incrementing like for video $videoId", e)
            false
        }
    }
    
    /**
     * Decrement like count for a video
     */
    suspend fun decrementVideoLike(videoId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val key = effectiveApiKey()
            val existingCount = getVideoLikeCount(videoId) ?: return@withContext false
            
            if (existingCount <= 0) return@withContext true
            
            val patchUrl = "$baseUrl/rest/v1/video_likes?video_id=eq.$videoId"
            val patchBody = gson.toJson(mapOf("like_count" to (existingCount - 1))).toRequestBody(jsonMedia)
            
            val request = Request.Builder()
                .url(patchUrl)
                .patch(patchBody)
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .build()
            
            client.newCall(request).execute().use { resp ->
                return@withContext resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error decrementing like for video $videoId", e)
            false
        }
    }
    
    /**
     * Add a user like to a video
     */
    suspend fun addUserVideoLike(videoId: Long, usuarioId: Long): Boolean = withContext(Dispatchers.IO) {
        // Table 'user_video_likes' does not exist in Supabase and we cannot create it.
        // We only sync the count via incrementVideoLike.
        Log.w("SupabaseClient", "addUserVideoLike: Skipped because 'user_video_likes' table is missing.")
        return@withContext true
    }

    suspend fun registerFcmToken(userId: Long, token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/user_fcm_tokens"
            // Use ISO 8601 format for timestamp
            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", java.util.Locale.US)
                .format(java.util.Date())
                
            val payload = mapOf(
                "user_id" to userId,
                "token" to token,
                "device_type" to "android",
                "last_updated" to timestamp
            )
            
            val requestBody = gson.toJson(payload).toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$url?on_conflict=user_id,token")
                .header("apikey", apiKey)
                .header("Authorization", "Bearer $apiKey")
                .header("Prefer", "resolution=merge-duplicates")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e("SupabaseClient", "Error registering FCM token: ${response.code} ${response.body?.string()}")
                return@withContext false
            }
            Log.d("SupabaseClient", "FCM token registered successfully for user $userId")
            return@withContext true
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception registering FCM token", e)
            return@withContext false
        }
    }

    /**
     * Remove a user like from a video
     */
    suspend fun removeUserVideoLike(videoId: Long, usuarioId: Long): Boolean = withContext(Dispatchers.IO) {
        // Table 'user_video_likes' does not exist in Supabase.
        Log.w("SupabaseClient", "removeUserVideoLike: Skipped because 'user_video_likes' table is missing.")
        return@withContext true
    }

    /**
     * Check if user has liked a video
     */
    suspend fun hasUserLikedVideo(videoId: Long, usuarioId: Long): Boolean = withContext(Dispatchers.IO) {
        // Table 'user_video_likes' does not exist in Supabase.
        // We cannot check remote status for specific user.
        return@withContext false
    }

    /**
     * Fetch all video likes from Supabase
     */
    suspend fun fetchAllVideoLikes(): List<com.example.tareamov.data.entity.VideoLike> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/video_likes?select=*"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                jsonArray.map { elem ->
                    val obj = elem.asJsonObject
                    com.example.tareamov.data.entity.VideoLike(
                        id = obj.get("id")?.asLong ?: 0,
                        videoId = obj.get("video_id")?.asLong ?: 0,
                        likeCount = obj.get("like_count")?.asInt ?: 0
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching all video likes", e)
            emptyList()
        }
    }

    // ========== VIDEO COMMENTS OPERATIONS ==========
    
    /**
     * Add a comment to a video
     */
    suspend fun addVideoComment(videoId: Long, usuarioId: Long, comment: String, parentId: Long? = null): Long? = withContext(Dispatchers.IO) {
        try {
            val payload = mutableMapOf<String, Any>(
                "video_id" to videoId,
                "usuario_id" to usuarioId,
                "comment" to comment
            )
            if (parentId != null) {
                payload["parent_id"] = parentId
            }
            return@withContext insertRecord("video_comments", payload)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error adding comment to video $videoId", e)
            null
        }
    }
    
    /**
     * Get comments for a video
     */
    suspend fun getVideoComments(videoId: Long): List<com.example.tareamov.data.entity.VideoComment> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/video_comments?video_id=eq.$videoId&order=created_at.desc"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                jsonArray.map { elem ->
                    val obj = elem.asJsonObject
                    com.example.tareamov.data.entity.VideoComment(
                        id = obj.get("id")?.asLong ?: 0,
                        videoId = obj.get("video_id")?.asLong ?: 0,
                        usuarioId = obj.get("usuario_id")?.asLong ?: 0,
                        comment = obj.get("comment")?.asString ?: "",
                        parentId = if (obj.has("parent_id") && !obj.get("parent_id").isJsonNull) obj.get("parent_id").asLong else null,
                        createdAt = if (obj.has("created_at") && !obj.get("created_at").isJsonNull) {
                            val raw = obj.get("created_at").asString
                            try {
                                java.time.OffsetDateTime.parse(raw, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                    .toInstant()
                                    .toEpochMilli()
                            } catch (_: Exception) {
                                try {
                                    obj.get("created_at").asLong
                                } catch (_: Exception) {
                                    System.currentTimeMillis()
                                }
                            }
                        } else {
                            System.currentTimeMillis()
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching comments for video $videoId", e)
            emptyList()
        }
    }
    
    /**
     * Get comment count for a video
     */
    suspend fun getVideoCommentCount(videoId: Long): Int = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/video_comments?video_id=eq.$videoId&select=id"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .addHeader("Prefer", "count=exact")
                .build()
            
            client.newCall(request).execute().use { response ->
                // Try to get count from header first
                val countHeader = response.header("content-range")
                if (countHeader != null) {
                    val count = countHeader.substringAfter("/").toIntOrNull()
                    if (count != null) return@withContext count
                }
                
                // Fallback: count array elements
                if (!response.isSuccessful) return@withContext 0
                val body = response.body?.string() ?: return@withContext 0
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                return@withContext jsonArray.size()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error getting comment count for video $videoId", e)
            0
        }
    }
    
    /**
     * Delete a comment
     */
    suspend fun deleteVideoComment(commentId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/video_comments?id=eq.$commentId"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .build()
            
            client.newCall(request).execute().use { resp ->
                return@withContext resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error deleting comment $commentId", e)
            false
        }
    }
    
    /**
     * Fetch all video comments from Supabase
     */
    suspend fun fetchAllVideoComments(): List<com.example.tareamov.data.entity.VideoComment> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/video_comments?select=*&order=created_at.desc"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                jsonArray.map { elem ->
                    val obj = elem.asJsonObject
                    com.example.tareamov.data.entity.VideoComment(
                        id = obj.get("id")?.asLong ?: 0,
                        videoId = obj.get("video_id")?.asLong ?: 0,
                        usuarioId = obj.get("usuario_id")?.asLong ?: 0,
                        comment = obj.get("comment")?.asString ?: "",
                        parentId = if (obj.has("parent_id") && !obj.get("parent_id").isJsonNull) obj.get("parent_id").asLong else null,
                        createdAt = if (obj.has("created_at") && !obj.get("created_at").isJsonNull) {
                            val raw = obj.get("created_at").asString
                            try {
                                java.time.OffsetDateTime.parse(raw, java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                                    .toInstant()
                                    .toEpochMilli()
                            } catch (_: Exception) {
                                try {
                                    obj.get("created_at").asLong
                                } catch (_: Exception) {
                                    System.currentTimeMillis()
                                }
                            }
                        } else {
                            System.currentTimeMillis()
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching all video comments", e)
            emptyList()
        }
    }
    
    // ========== DOCENTE ROLE OPERATIONS ==========
    
    /**
     * Check if a user has docente role or higher
     */
    suspend fun isUserDocente(userId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/usuarios?id=eq.$userId&select=rol_id,roles(nombre,nivel)"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                
                val body = response.body?.string() ?: return@withContext false
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                if (jsonArray.size() > 0) {
                    val user = jsonArray[0].asJsonObject
                    val roles = user.getAsJsonObject("roles")
                    if (roles != null) {
                        val nivel = roles.get("nivel")?.asFloat ?: 0f
                        return@withContext nivel >= 1.5f // NIVEL_DOCENTE
                    }
                }
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error checking docente status for user $userId", e)
            false
        }
    }
    
    /**
     * Promote user to docente role
     */
    suspend fun promoteToDocente(userId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // First get the docente role id
            val rolesUrl = "$baseUrl/rest/v1/roles?nombre=eq.docente&select=id"
            val key = effectiveApiKey()
            
            val rolesRequest = Request.Builder()
                .url(rolesUrl)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            var docenteRoleId: Long? = null
            client.newCall(rolesRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                        if (jsonArray.size() > 0) {
                            docenteRoleId = jsonArray[0].asJsonObject.get("id")?.asLong
                        }
                    }
                }
            }
            
            if (docenteRoleId == null) {
                Log.e("SupabaseClient", "Docente role not found in database")
                return@withContext false
            }
            
            // Update user's role
            return@withContext updateRecord("usuarios", userId, mapOf("rol_id" to docenteRoleId))
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error promoting user $userId to docente", e)
            false
        }
    }

    /**
     * Fetch notifications for a specific user from Supabase
     * Returns a list ordered by created_at descending (newest first)
     */
    suspend fun fetchNotifications(userId: Long): List<com.example.tareamov.data.entity.Notification> = withContext(Dispatchers.IO) {
        try {
            val path = "notifications?user_id=eq.$userId&order=created_at.desc"
            val request = buildGetRequest(path)
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "Failed to fetch notifications: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    return@withContext emptyList()
                }
                
                val notifications = underscoredGson.fromJson(body, Array<com.example.tareamov.data.entity.Notification>::class.java)
                return@withContext notifications.toList()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching notifications for user $userId", e)
            emptyList()
        }
    }

    /**
     * Mark a notification as read
     */
    suspend fun markNotificationAsRead(notificationId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            return@withContext updateRecord("notifications", notificationId, mapOf("is_read" to true))
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error marking notification $notificationId as read", e)
            false
        }
    }

    /**
     * Get the course thumbnail URL for a given task ID
     * Traverses: task -> topic -> course -> thumbnail_uri
     * Used for task-related notifications to show the course image
     */
    suspend fun getCourseThumbnailForTask(taskId: Long): String? = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext null
            
            // Use nested select to get task -> topic -> course in one query
            val url = "$baseUrl/rest/v1/tasks?id=eq.$taskId&select=topic_id,topics!inner(course_id,courses!inner(thumbnail_uri))"
            
            Log.d("SupabaseClient", "🖼️ Fetching course thumbnail for task $taskId")
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "Failed to fetch course thumbnail for task $taskId: ${response.code}")
                    return@withContext null
                }
                
                val body = response.body?.string()
                if (body.isNullOrEmpty() || body == "[]") {
                    Log.d("SupabaseClient", "No data found for task $taskId")
                    return@withContext null
                }
                
                // Parse the nested JSON response
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                if (jsonArray.size() == 0) return@withContext null
                
                val taskObj = jsonArray[0].asJsonObject
                val topicsObj = taskObj.getAsJsonObject("topics") ?: return@withContext null
                val coursesObj = topicsObj.getAsJsonObject("courses") ?: return@withContext null
                val thumbnailUri = coursesObj.get("thumbnail_uri")?.asString
                
                Log.d("SupabaseClient", "✅ Found course thumbnail for task $taskId: ${thumbnailUri?.take(50)}...")
                return@withContext thumbnailUri
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching course thumbnail for task $taskId", e)
            null
        }
    }

    /**
     * Count unread notifications for a specific user
     * Returns the count of notifications where is_read = false
     */
    suspend fun countUnreadNotifications(userId: Long): Int = withContext(Dispatchers.IO) {
        try {
            val path = "notifications?user_id=eq.$userId&is_read=eq.false&select=id"
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/$path")
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Prefer", "count=exact")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "Failed to count unread notifications: ${response.code}")
                    return@withContext 0
                }
                
                // El header Content-Range contiene el count: "0-X/TOTAL"
                val contentRange = response.header("Content-Range")
                if (contentRange != null) {
                    val count = contentRange.substringAfter("/").toIntOrNull() ?: 0
                    Log.d("SupabaseClient", "🔔 Unread notifications for user $userId: $count")
                    return@withContext count
                }
                
                // Fallback: contar los elementos del array
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val array = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    return@withContext array.size()
                }
                
                0
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error counting unread notifications for user $userId", e)
            0
        }
    }

    /**
     * Fetch courses where the user has submitted tasks (as a student).
     * This uses the task_submissions table to find unique course_ids for the given student.
     */
    suspend fun fetchCoursesWithUserSubmissions(studentId: Long): List<Course> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()
            
            // Step 1: Get all submissions for this student with task/topic/course info
            val submissionsUrl = "$baseUrl/rest/v1/task_submissions?select=task_id,tasks!inner(topic_id,topics!inner(course_id))&student_id=eq.$studentId"
            Log.d("SupabaseClient", "🔍 Fetching submissions for student $studentId: $submissionsUrl")
            
            val request = Request.Builder()
                .url(submissionsUrl)
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .build()
            
            val courseIds = mutableSetOf<Long>()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    Log.e("SupabaseClient", "fetchCoursesWithUserSubmissions failed: ${response.code} - $errorBody")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                Log.d("SupabaseClient", "📊 Submissions response: ${body.take(500)}")
                
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                for (elem in jsonArray) {
                    try {
                        val obj = elem.asJsonObject
                        if (obj.has("tasks") && !obj.get("tasks").isJsonNull) {
                            val taskObj = obj.get("tasks").asJsonObject
                            if (taskObj.has("topics") && !taskObj.get("topics").isJsonNull) {
                                val topicObj = taskObj.get("topics").asJsonObject
                                if (topicObj.has("course_id") && !topicObj.get("course_id").isJsonNull) {
                                    courseIds.add(topicObj.get("course_id").asLong)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("SupabaseClient", "Error parsing submission entry: ${e.message}")
                    }
                }
            }
            
            Log.d("SupabaseClient", "📚 Found ${courseIds.size} unique courses with submissions: $courseIds")
            
            if (courseIds.isEmpty()) {
                return@withContext emptyList()
            }
            
            // Step 2: Fetch course details for these course_ids
            val coursesUrl = "$baseUrl/rest/v1/courses?select=*&id=in.(${courseIds.joinToString(",")})"
            Log.d("SupabaseClient", "🔍 Fetching course details: $coursesUrl")
            
            val coursesRequest = Request.Builder()
                .url(coursesUrl)
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .build()
            
            client.newCall(coursesRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("SupabaseClient", "Failed to fetch course details: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val courses = underscoredGson.fromJson(body, Array<Course>::class.java)
                Log.d("SupabaseClient", "✅ Fetched ${courses.size} courses with user submissions")
                return@withContext courses.toList()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching courses with user submissions for student $studentId", e)
            emptyList()
        }
    }

    // Backend URL for sending push notifications and emails
    private const val BACKEND_URL = "https://mcp-backenddeploy-production.up.railway.app"

    /**
     * Insert a notification into Supabase
     * Returns the notification ID on success, null on failure
     * After successful insertion, also sends push notification and email to the user
     */
    suspend fun insertNotification(notification: com.example.tareamov.data.entity.Notification): Long? = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                Log.w("SupabaseClient", "Supabase not configured, cannot insert notification")
                return@withContext null
            }

            val map = mapOf(
                "user_id" to notification.userId,
                "type" to notification.type,
                "title" to notification.title,
                "message" to notification.message,
                "sender_username" to notification.senderUsername,
                "sender_avatar_url" to notification.senderAvatarUrl,
                "thumbnail_url" to notification.thumbnailUrl,
                "related_id" to notification.relatedId,
                "is_read" to notification.isRead
            )

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/notifications"

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    Log.e("SupabaseClient", "insertNotification failed: ${resp.code} - $respBody")
                    return@withContext null
                }

                if (respBody.isNullOrEmpty()) return@withContext null

                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                    if (jsonArray.size() > 0) {
                        val idElem = jsonArray[0].asJsonObject.get("id")
                        val id = idElem?.asLong
                        Log.d("SupabaseClient", "✅ Notification inserted with id: $id")
                        
                        // Send push notification and email via backend
                        sendPushAndEmailNotification(
                            userId = notification.userId,
                            title = notification.title,
                            message = notification.message,
                            type = notification.type,
                            relatedId = notification.relatedId?.toString(),
                            senderUsername = notification.senderUsername
                        )
                        
                        return@withContext id
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseClient", "Error parsing notification response", e)
                }
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception inserting notification", e)
            return@withContext null
        }
    }

    /**
     * Send push notification and email to a user via the backend
     * This is called automatically after inserting a notification to Supabase
     */
    private suspend fun sendPushAndEmailNotification(
        userId: Long,
        title: String,
        message: String,
        type: String,
        relatedId: String?,
        senderUsername: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val notificationData = mapOf(
                "userId" to userId,
                "title" to title,
                "message" to message,
                "type" to type,
                "relatedId" to (relatedId ?: ""),
                "senderUsername" to (senderUsername ?: "")
            )

            val requestBody = gson.toJson(notificationData).toRequestBody(jsonMedia)
            val url = "$BACKEND_URL/send-notification"

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-API-Key", "tareamov-mcp-api-key-2025-secure")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (resp.isSuccessful) {
                    Log.d("SupabaseClient", "✅ Push/Email notification sent successfully: $respBody")
                } else {
                    Log.w("SupabaseClient", "⚠️ Push/Email notification failed: ${resp.code} - $respBody")
                }
            }
        } catch (e: Exception) {
            // Don't fail the main notification insert if push/email fails
            Log.w("SupabaseClient", "⚠️ Exception sending push/email notification (non-fatal)", e)
        }
    }

    /**
     * Fetch all subscriber user IDs for a creator
     * Returns a list of user IDs who are subscribed to the given creator
     */
    suspend fun fetchSubscriberIdsByCreatorId(creatorId: Long): List<Long> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                Log.w("SupabaseClient", "Supabase not configured, cannot fetch subscribers")
                return@withContext emptyList()
            }

            val path = "subscriptions?creator_id=eq.$creatorId&select=subscriber_id"
            val request = buildGetRequest(path)

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "Failed to fetch subscribers: ${response.code}")
                    return@withContext emptyList()
                }

                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    return@withContext emptyList()
                }

                val subscriberIds = mutableListOf<Long>()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                for (elem in jsonArray) {
                    val obj = elem.asJsonObject
                    if (obj.has("subscriber_id") && !obj.get("subscriber_id").isJsonNull) {
                        subscriberIds.add(obj.get("subscriber_id").asLong)
                    }
                }
                Log.d("SupabaseClient", "✅ Found ${subscriberIds.size} subscribers for creator $creatorId")
                return@withContext subscriberIds
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching subscribers for creator $creatorId", e)
            emptyList()
        }
    }

    /**
     * Notify all subscribers of a creator about a new course
     * Creates notifications for each subscriber
     */
    suspend fun notifySubscribersOfNewCourse(
        creatorUserId: Long,
        creatorUsername: String,
        creatorAvatarUrl: String?,
        courseId: Long,
        courseTitle: String,
        courseThumbnailUrl: String?
    ): Int = withContext(Dispatchers.IO) {
        try {
            val subscriberIds = fetchSubscriberIdsByCreatorId(creatorUserId)
            if (subscriberIds.isEmpty()) {
                Log.d("SupabaseClient", "No subscribers to notify for creator $creatorUserId")
                return@withContext 0
            }

            var notifiedCount = 0
            for (subscriberId in subscriberIds) {
                val notification = com.example.tareamov.data.entity.Notification(
                    userId = subscriberId,
                    type = com.example.tareamov.data.entity.Notification.TYPE_NEW_COURSE,
                    title = "Nuevo curso de $creatorUsername",
                    message = "¡$creatorUsername ha publicado un nuevo curso: \"$courseTitle\"!",
                    senderUsername = creatorUsername,
                    senderAvatarUrl = creatorAvatarUrl,
                    thumbnailUrl = courseThumbnailUrl,
                    relatedId = courseId,
                    isRead = false
                )
                val result = insertNotification(notification)
                if (result != null) {
                    notifiedCount++
                }
            }

            Log.d("SupabaseClient", "✅ Notified $notifiedCount subscribers about new course '$courseTitle'")
            return@withContext notifiedCount
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error notifying subscribers of new course", e)
            0
        }
    }

    /**
     * Notify all enrolled students of a course about a new task
     * Creates notifications for each enrolled student (excluding the creator)
     */
    suspend fun notifyEnrolledStudentsOfNewTask(
        courseId: Long,
        creatorUserId: Long,
        creatorUsername: String,
        creatorAvatarUrl: String?,
        taskId: Long,
        taskName: String,
        courseName: String
    ): Int = withContext(Dispatchers.IO) {
        try {
            // Fetch all enrolled students from progreso_estudiante table
            val progresos = fetchProgresosByCurso(courseId)
            if (progresos.isEmpty()) {
                Log.d("SupabaseClient", "No enrolled students to notify for course $courseId")
                return@withContext 0
            }

            // Get student IDs, excluding the course creator
            val studentIds = progresos.map { it.usuarioEstudiante }.filter { it != creatorUserId }
            if (studentIds.isEmpty()) {
                Log.d("SupabaseClient", "No students (other than creator) to notify")
                return@withContext 0
            }

            var notifiedCount = 0
            for (studentId in studentIds) {
                val notification = com.example.tareamov.data.entity.Notification(
                    userId = studentId,
                    type = com.example.tareamov.data.entity.Notification.TYPE_NEW_TASK,
                    title = "Nueva tarea en $courseName",
                    message = "$creatorUsername ha creado una nueva tarea: \"$taskName\"",
                    senderUsername = creatorUsername,
                    senderAvatarUrl = creatorAvatarUrl,
                    thumbnailUrl = null,
                    relatedId = taskId,
                    isRead = false
                )
                val result = insertNotification(notification)
                if (result != null) {
                    notifiedCount++
                }
            }

            Log.d("SupabaseClient", "✅ Notified $notifiedCount enrolled students about new task '$taskName' in course '$courseName'")
            return@withContext notifiedCount
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error notifying enrolled students of new task", e)
            0
        }
    }

    /**
     * Notify the course creator when a student submits a task
     * Creates a notification for the course creator
     */
    suspend fun notifyCourseCreatorOfSubmission(
        taskId: Long,
        taskName: String,
        studentUsername: String,
        studentAvatarUrl: String?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Fetch task to get topic_id
            val task = fetchTaskById(taskId)
            if (task == null) {
                Log.w("SupabaseClient", "Task not found for notification: $taskId")
                return@withContext false
            }

            // Fetch topic to get course_id
            val topicId = task.topicId
            if (topicId == null) {
                Log.w("SupabaseClient", "Topic ID not found for task: $taskId")
                return@withContext false
            }

            val topic = fetchTopics().firstOrNull { it.id == topicId }
            if (topic == null) {
                Log.w("SupabaseClient", "Topic not found: $topicId")
                return@withContext false
            }

            // Fetch course to get creator_user_id
            val courseId = topic.courseId
            val course = fetchCourseById(courseId)
            if (course == null) {
                Log.w("SupabaseClient", "Course not found: $courseId")
                return@withContext false
            }

            val creatorUserId = course.creatorUserId
            
            // Create notification for the course creator
            val notification = com.example.tareamov.data.entity.Notification(
                userId = creatorUserId,
                type = com.example.tareamov.data.entity.Notification.TYPE_TASK_SUBMISSION,
                title = "Nueva entrega de tarea",
                message = "$studentUsername ha entregado la tarea \"$taskName\" en tu curso \"${course.title}\"",
                senderUsername = studentUsername,
                senderAvatarUrl = studentAvatarUrl,
                thumbnailUrl = course.thumbnailUri,
                relatedId = taskId,
                isRead = false
            )

            val result = insertNotification(notification)
            if (result != null) {
                Log.d("SupabaseClient", "✅ Course creator (userId=$creatorUserId) notified about submission from $studentUsername for task '$taskName'")
                return@withContext true
            } else {
                Log.w("SupabaseClient", "Failed to insert submission notification")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error notifying course creator of submission", e)
            false
        }
    }

    /**
     * Fetch FCM tokens for a list of user IDs
     * Returns a map of userId to list of FCM tokens (users may have multiple devices)
     */
    suspend fun fetchFcmTokensByUserIds(userIds: List<Long>): Map<Long, List<String>> = withContext(Dispatchers.IO) {
        if (userIds.isEmpty()) return@withContext emptyMap()
        
        try {
            val userIdsParam = userIds.joinToString(",") { "($it)" }
            val url = "$baseUrl/rest/v1/user_fcm_tokens?user_id=in.$userIdsParam&select=user_id,token"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                val body = response.body?.string()
                if (!response.isSuccessful || body.isNullOrEmpty()) {
                    Log.e("SupabaseClient", "Error fetching FCM tokens: ${response.code}")
                    return@withContext emptyMap()
                }

                val tokenMap = mutableMapOf<Long, MutableList<String>>()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                for (elem in jsonArray) {
                    val obj = elem.asJsonObject
                    val userId = obj.get("user_id")?.asLong ?: continue
                    val token = obj.get("token")?.asString ?: continue
                    tokenMap.getOrPut(userId) { mutableListOf() }.add(token)
                }
                Log.d("SupabaseClient", "✅ Fetched FCM tokens for ${tokenMap.size} users")
                return@withContext tokenMap
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching FCM tokens", e)
            emptyMap()
        }
    }

    /**
     * Send push notification to subscribers when a new course is created
     * Uses FCM HTTP v1 API via a server-side function or direct call
     */
    suspend fun sendPushNotificationsToSubscribers(
        creatorUserId: Long,
        creatorUsername: String,
        courseId: Long,
        courseTitle: String,
        courseThumbnailUrl: String?
    ): Int = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch subscriber IDs
            val subscriberIds = fetchSubscriberIdsByCreatorId(creatorUserId)
            if (subscriberIds.isEmpty()) {
                Log.d("SupabaseClient", "No subscribers to send push notifications to")
                return@withContext 0
            }

            // 2. Fetch FCM tokens for subscribers
            val tokenMap = fetchFcmTokensByUserIds(subscriberIds)
            if (tokenMap.isEmpty()) {
                Log.d("SupabaseClient", "No FCM tokens found for subscribers")
                return@withContext 0
            }

            // 3. Collect all tokens
            val allTokens = tokenMap.values.flatten()
            Log.d("SupabaseClient", "Found ${allTokens.size} FCM tokens for ${tokenMap.size} subscribers")

            // 4. Send push notifications using FCM legacy HTTP API
            var sentCount = 0
            for (token in allTokens) {
                val success = sendFcmPushNotification(
                    token = token,
                    title = "📚 Nuevo curso de $creatorUsername",
                    body = "¡$creatorUsername ha publicado: \"$courseTitle\"!",
                    data = mapOf(
                        "type" to "new_course",
                        "course_id" to courseId.toString(),
                        "creator_username" to creatorUsername,
                        "thumbnail_url" to (courseThumbnailUrl ?: "")
                    )
                )
                if (success) sentCount++
            }

            Log.d("SupabaseClient", "✅ Sent $sentCount push notifications for new course '$courseTitle'")
            return@withContext sentCount
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error sending push notifications to subscribers", e)
            0
        }
    }

    /**
     * Send a single FCM push notification using legacy HTTP API
     * Note: For production, consider using FCM HTTP v1 API with OAuth2
     */
    private suspend fun sendFcmPushNotification(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap()
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // FCM Legacy HTTP API endpoint
            val fcmUrl = "https://fcm.googleapis.com/fcm/send"
            
            // FCM Server Key - should be stored securely
            // For now, we'll call a Supabase Edge Function that handles FCM sending
            // This is more secure as it keeps the server key on the server side
            
            val payload = mapOf(
                "to" to token,
                "notification" to mapOf(
                    "title" to title,
                    "body" to body,
                    "sound" to "default",
                    "click_action" to "OPEN_COURSE_DETAIL"
                ),
                "data" to data,
                "priority" to "high"
            )

            // Call Supabase Edge Function to send FCM notification
            val edgeFunctionUrl = "$baseUrl/functions/v1/send-fcm-notification"
            val requestBody = gson.toJson(payload).toRequestBody(jsonMedia)
            val key = effectiveApiKey()

            val request = Request.Builder()
                .url(edgeFunctionUrl)
                .post(requestBody)
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("SupabaseClient", "✅ FCM notification sent to token: ${token.take(20)}...")
                    return@withContext true
                } else {
                    val errorBody = response.body?.string()
                    Log.e("SupabaseClient", "Error sending FCM notification: ${response.code} - $errorBody")
                    
                    // If edge function doesn't exist, try direct FCM call (requires server key in client - not recommended for production)
                    if (response.code == 404) {
                        Log.w("SupabaseClient", "Edge function not found, FCM notification not sent")
                    }
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception sending FCM notification", e)
            false
        }
    }

    /**
     * Combined method: Notify subscribers with both in-app notifications AND push notifications
     */
    suspend fun notifySubscribersOfNewCourseWithPush(
        creatorUserId: Long,
        creatorUsername: String,
        creatorAvatarUrl: String?,
        courseId: Long,
        courseTitle: String,
        courseThumbnailUrl: String?
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        // Send in-app notifications
        val inAppCount = notifySubscribersOfNewCourse(
            creatorUserId = creatorUserId,
            creatorUsername = creatorUsername,
            creatorAvatarUrl = creatorAvatarUrl,
            courseId = courseId,
            courseTitle = courseTitle,
            courseThumbnailUrl = courseThumbnailUrl
        )

        // Send push notifications
        val pushCount = sendPushNotificationsToSubscribers(
            creatorUserId = creatorUserId,
            creatorUsername = creatorUsername,
            courseId = courseId,
            courseTitle = courseTitle,
            courseThumbnailUrl = courseThumbnailUrl
        )

        Log.d("SupabaseClient", "✅ Total notifications: $inAppCount in-app, $pushCount push")
        return@withContext Pair(inAppCount, pushCount)
    }
}
