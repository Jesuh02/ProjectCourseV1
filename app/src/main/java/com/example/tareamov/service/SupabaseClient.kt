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
    private val client = OkHttpClient()
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
            "email" to persona.email,
            "telefono" to persona.telefono,
            "direccion" to persona.direccion,
            "fechaNacimiento" to persona.fechaNacimiento,
            "avatar" to persona.avatar,
            "esUsuario" to persona.esUsuario
        )
        return insertRecord("personas", payload)
    }

    suspend fun insertUsuario(usuario: Usuario): Long? {
        val payload = mapOf(
            "usuario" to usuario.usuario,
            "contrasena" to usuario.contrasena,
            "persona_id" to usuario.persona_id,
            "rol_id" to usuario.rol_id
        )
        return insertRecord("usuarios", payload)
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
            // Map local ContentItem fields to Supabase content_items columns (title/body)
            val map = mutableMapOf(
                "topic_id" to contentItem.topicId,
                "task_id" to contentItem.taskId,
                "title" to (contentItem.name ?: ""),
                "body" to contentItem.uriString,
                "content_type" to contentItem.contentType,
                "order_index" to (contentItem.orderIndex ?: 0)
            )
            
            // Add creator fields if available
            if (contentItem.creator_usuario_id != null && contentItem.creator_usuario_id!! > 0) {
                map["creator_usuario_id"] = contentItem.creator_usuario_id!!
            }
            if (!contentItem.creator_username.isNullOrBlank()) {
                map["creator_username"] = contentItem.creator_username!!
            }

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/content_items"

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
                    Log.w("SupabaseClient", "insertContentItem failed: ${'$'}{resp.code} ${'$'}{resp.message} body=$bodyStr")
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
                    Log.e("SupabaseClient","insertContentItem parse error", e)
                }

                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient","insertContentItem error", e)
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
            map["email"] = persona.email
            map["telefono"] = persona.telefono
            map["direccion"] = persona.direccion
            map["fechaNacimiento"] = persona.fechaNacimiento
            map["avatar"] = persona.avatar
            map["esUsuario"] = persona.esUsuario

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
            map["usuario"] = usuario.usuario
            map["contrasena"] = usuario.contrasena
            map["persona_id"] = usuario.persona_id
            map["rol_id"] = usuario.rol_id

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/usuarios?id=eq.${usuario.id}"

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
                    android.util.Log.w("SupabaseClient", "updateUsuario failed status=${resp.code} body=${resp.body?.string()}")
                    return@withContext false
                }
                return@withContext true
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            
            // Do NOT send local 'id' to server - let Postgres sequence generate primary key
            val map = mutableMapOf<String, Any?>()
            if (submission.id != null && submission.id != 0L) {
                android.util.Log.w("SupabaseClient", "Not sending local id=${submission.id} to server for task_submissions (will let DB assign id)")
            }
                map["task_id"] = submission.taskId
                // Send numeric student_id (long) instead of student_username string
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
                // CRITICAL: Disable triggers to avoid ambiguous column reference error (42702)
                // The database trigger has a bug where "student_username" reference is ambiguous
                // We handle progress updates in the application layer instead (see TaskSubmissionsFragment.triggerProgressUpdateEvent)
                .addHeader("Prefer", "return=representation,resolution=ignore-duplicates,session_replication_role=replica")
                .build()

            client.newCall(request).execute().use { resp ->
                var respBody = resp.body?.string()
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
                                .addHeader("Prefer", "return=representation,resolution=ignore-duplicates,session_replication_role=replica")
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
            android.util.Log.e("SupabaseClient", "❌ Exception in insertTaskSubmission", e)
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
            map["timestamp"] = fileContext.timestamp
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
                .addHeader("Prefer", "return=representation,session_replication_role=replica")
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
                    .addHeader("Prefer", "return=representation,session_replication_role=replica")
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
                .addHeader("Prefer", "return=representation,session_replication_role=replica")
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
                    .addHeader("Prefer", "return=representation,session_replication_role=replica")
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
            val pathIlike = "usuarios?usuario=ilike.'${escaped}'"
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
                val pathEq = "usuarios?usuario=eq.'${escaped}'"
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

                            repaired.add(
                                VideoData(
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
                            )
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
                    return@withContext videos.map { it.copy(username = username) }
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
    suspend fun fetchContentItems(): List<ContentItem> = fetchList("content_items", Array<ContentItem>::class.java)
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
    suspend fun fetchSubscriptions(): List<Subscription> = fetchList("subscriptions", Array<Subscription>::class.java)

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

    // Fetch FileContext by submissionId
    suspend fun fetchFileContextBySubmissionId(submissionId: Long): FileContext? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/file_contexts?submission_id=eq.$submissionId&select=*"
            val request = buildGetRequest(url)
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string()
                val list = underscoredGson.fromJson(json, Array<FileContext>::class.java)
                list.firstOrNull()
            } else {
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
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchTopicsByCourse failed status=${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                val arr = underscoredGson.fromJson(body, Array<Topic>::class.java)
                return@withContext arr.toList()
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchTopicsByCourse exception", e)
            emptyList()
        }
    }
    // Fetch content items for a list of topic IDs using server-side 'in' filter
    suspend fun fetchContentItemsByTopicIds(topicIds: List<Long>): List<ContentItem> = withContext(Dispatchers.IO) {
        if (topicIds.isEmpty()) return@withContext emptyList()
        try {
            val ids = topicIds.joinToString(",")
            val path = "content_items?topic_id=in.($ids)"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchContentItemsByTopicIds failed status=${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                val arr = underscoredGson.fromJson(body, Array<ContentItem>::class.java)
                return@withContext arr.toList()
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchContentItemsByTopicIds exception", e)
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
                val arr = underscoredGson.fromJson(body, Array<ContentItem>::class.java)
                Log.d("SupabaseClient", "fetchContentItemsByTaskId: Found ${arr.size} items for taskId=$taskId")
                return@withContext arr.toList()
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
     * Execute raw SQL query via REST API de PostgREST
     * Parsea el SQL y lo convierte en una consulta REST a Supabase
     */
    suspend fun executeRawSql(sql: String): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "🔍 executeRawSql called with SQL: $sql")
            
            // Parsear el SQL para extraer la tabla y filtros
            val trimmedSql = sql.trim().replace(Regex("\\s+"), " ")
            Log.d("SupabaseClient", "  📝 Normalized SQL: $trimmedSql")
            
            // Regex para capturar: SELECT ... FROM [schema.]table WHERE ...
            val selectPattern = Regex(
                "SELECT\\s+(.+?)\\s+FROM\\s+(?:public\\.)?([\\w_]+)(?:\\s+WHERE\\s+(.+?))?(?:\\s+ORDER\\s+BY\\s+(.+?))?(?:\\s+LIMIT\\s+(\\d+))?\\s*;?",
                RegexOption.IGNORE_CASE
            )
            
            val match = selectPattern.find(trimmedSql)
            if (match == null) {
                Log.e("SupabaseClient", "❌ Could not parse SQL query. Use format: SELECT ... FROM table WHERE ...")
                return@withContext emptyList()
            }
            
            val columns = match.groupValues[1].trim()
            val table = match.groupValues[2].trim()
            val whereClause = match.groupValues[3].trim()
            val orderByClause = match.groupValues[4].trim()
            val limitClause = match.groupValues[5].trim()
            
            Log.d("SupabaseClient", "  📊 Parsed - Table: $table, Columns: $columns, Where: $whereClause, OrderBy: $orderByClause, Limit: $limitClause")
            
            // Construir la URL de PostgREST
            val queryParams = mutableListOf<String>()
            
            // Agregar filtros WHERE como query params de PostgREST
            if (whereClause.isNotEmpty()) {
                // Parsear condiciones simples: "id = 2" o "username = 'test'"
                val conditionPattern = Regex("([\\w_]+)\\s*=\\s*([\\w'\"]+)")
                val conditions = conditionPattern.findAll(whereClause)
                
                conditions.forEach { condMatch ->
                    val column = condMatch.groupValues[1]
                    var value = condMatch.groupValues[2].replace("'", "").replace("\"", "")
                    queryParams.add("$column=eq.$value")
                }
            }
            
            // Agregar ORDER BY
            if (orderByClause.isNotEmpty()) {
                queryParams.add("order=$orderByClause")
            }
            
            // Agregar LIMIT
            if (limitClause.isNotEmpty()) {
                queryParams.add("limit=$limitClause")
            }
            
            // Construir path con query params
            val path = if (queryParams.isNotEmpty()) {
                "$table?" + queryParams.joinToString("&")
            } else {
                table
            }
            
            Log.d("SupabaseClient", "  🌐 REST path: $path")
            
            // Ejecutar GET request usando el helper existente
            val request = buildGetRequest(path)
            
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            Log.d("SupabaseClient", "  📡 Response code: ${response.code}")
            Log.d("SupabaseClient", "  📦 Response body: ${body?.take(500)}")
            
            if (!response.isSuccessful || body.isNullOrEmpty()) {
                Log.e("SupabaseClient", "❌ Request failed or empty response")
                return@withContext emptyList()
            }
            
            // Parsear JSON array
            val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
            val result = mutableListOf<Map<String, Any?>>()
            
            Log.d("SupabaseClient", "  🔢 JSON array size: ${jsonArray.size()}")
            
            for (i in 0 until jsonArray.size()) {
                val jsonObject = jsonArray[i].asJsonObject
                val map = mutableMapOf<String, Any?>()
                
                jsonObject.keySet().forEach { key ->
                    val element = jsonObject.get(key)
                    map[key] = when {
                        element.isJsonNull -> null
                        element.isJsonPrimitive -> {
                            val prim = element.asJsonPrimitive
                            when {
                                prim.isBoolean -> prim.asBoolean
                                prim.isNumber -> prim.asNumber
                                else -> prim.asString
                            }
                        }
                        else -> element.toString()
                    }
                }
                
                result.add(map)
                Log.d("SupabaseClient", "    📋 Row ${i + 1}: $map")
            }
            
            Log.d("SupabaseClient", "  ✅ Successfully parsed ${result.size} rows")
            return@withContext result
            
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ Error executing raw SQL: ${e.message}", e)
            e.printStackTrace()
            return@withContext emptyList()
        }
    }

    /**
     * Fetch Video by ID
     */
    suspend fun fetchVideoById(id: Long): VideoData? = withContext(Dispatchers.IO) {
        try {
            val path = "videos?id=eq.$id"
            val list = fetchList(path, Array<VideoData>::class.java)
            return@withContext list.firstOrNull()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching video by ID", e)
            return@withContext null
        }
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
        if (user != null && user.rol_id != null) {
            val rol = fetchRolById(user.rol_id)
            return@withContext Pair(user, rol)
        } else {
            return@withContext Pair(user, null)
        }
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
                    // 1. Find users matching the query (partial match)
                    val users = fetchList<Usuario>("usuarios?usuario=ilike.*${searchQuery}*", Array<Usuario>::class.java)
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
                                                    username = uName,
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
     * Fetch ContentItems ordered
     */
    suspend fun fetchContentItemsOrdered(orderBy: String, direction: String = "asc"): List<ContentItem> = withContext(Dispatchers.IO) {
        try {
            val path = "content_items?order=$orderBy.$direction"
            return@withContext fetchList(path, Array<ContentItem>::class.java).toList()
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
}
