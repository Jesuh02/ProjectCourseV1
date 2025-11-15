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

    suspend fun insertPersona(persona: Persona): Long? = withContext(Dispatchers.IO) {
        try {
            val map = mapOf(
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

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/personas"

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
                    // Log response body to help debugging permissions/RLS issues
                    val bodyStr = respBody ?: ""
                    throw Exception("Supabase insertPersona failed: ${'$'}{resp.code} ${'$'}{resp.message} body=$bodyStr")
                }

                if (respBody.isNullOrEmpty()) return@withContext null

                try {
                    val jsonArray = com.google.gson.JsonParser.parseString(respBody).asJsonArray
                    if (jsonArray.size() > 0) {
                        val idElem = jsonArray[0].asJsonObject.get("id")
                        return@withContext idElem?.asLong
                    }
                } catch (e: Exception) {
                    // ignore parse errors but log
                    e.printStackTrace()
                }

                return@withContext null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    suspend fun insertUsuario(usuario: Usuario): Long? = withContext(Dispatchers.IO) {
        try {
            val map = mapOf(
                "usuario" to usuario.usuario,
                "contrasena" to usuario.contrasena,
                "persona_id" to usuario.persona_id,
                "rol_id" to usuario.rol_id
            )

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/usuarios"

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
                    throw Exception("Supabase insertUsuario failed: ${'$'}{resp.code} ${'$'}{resp.message} body=$bodyStr")
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

    suspend fun insertVideo(video: com.example.tareamov.data.entity.VideoData): Long? = withContext(Dispatchers.IO) {
        try {
            val map = mapOf(
                "username" to video.username,
                "description" to video.description,
                "title" to video.title,
                "video_uri_string" to video.videoUriString,
                "local_file_path" to video.localFilePath,
                "timestamp" to video.timestamp,
                "is_paid" to video.isPaid,
                "thumbnail_uri" to video.thumbnailUri,
                "price" to video.price
            )

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
                    throw Exception("Supabase insertVideo failed: ${'$'}{resp.code} ${'$'}{resp.message} body=$bodyStr")
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

    suspend fun insertCourse(course: com.example.tareamov.data.entity.Course): Long? = withContext(Dispatchers.IO) {
        try {
            val map = mapOf(
                "title" to course.title,
                "description" to course.description,
                "creator_username" to course.creatorUsername,
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
                "creator_username" to course.creatorUsername,
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
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
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

    // Insert a Task (belongs to a Topic)
    suspend fun insertTask(task: com.example.tareamov.data.entity.Task): Long? = withContext(Dispatchers.IO) {
        try {
            // Map local Task fields to Supabase table columns (tasks.title)
            val map = mapOf(
                "topic_id" to task.topicId,
                "title" to task.name,
                "description" to task.description
            )

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/tasks"

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
                    Log.w("SupabaseClient", "insertTask failed: ${'$'}{resp.code} ${'$'}{resp.message} body=$bodyStr")
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
                    Log.e("SupabaseClient","insertTask parse error", e)
                }

                // Insert returned no id. As a fallback, try to find a matching task by topic+title
                try {
                    val encodedTitle = java.net.URLEncoder.encode(task.name ?: "", "UTF-8")
                    val queryUrl = "$baseUrl/rest/v1/tasks?topic_id=eq.${task.topicId}&title=eq.$encodedTitle&select=id"
                    val qreq = Request.Builder().url(queryUrl).get()
                        .addHeader("apikey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .addHeader("Accept", "application/json").build()
                    client.newCall(qreq).execute().use { qresp ->
                        val qb = qresp.body?.string()
                        if (!qresp.isSuccessful) return@withContext null
                        if (!qb.isNullOrEmpty()) {
                            val arr = com.google.gson.JsonParser.parseString(qb).asJsonArray
                            if (arr.size() > 0) {
                                val idElem2 = arr[0].asJsonObject.get("id")
                                return@withContext idElem2?.asLong
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("SupabaseClient", "Fallback query after insertTask failed", e)
                }

                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient","insertTask error", e)
            return@withContext null
        }
    }

    // Insert a ContentItem (belongs to a Task/Topic)
    suspend fun insertContentItem(contentItem: com.example.tareamov.data.entity.ContentItem): Long? = withContext(Dispatchers.IO) {
        try {
            // Map local ContentItem fields to Supabase content_items columns (title/body)
            val map = mapOf(
                "topic_id" to contentItem.topicId,
                "task_id" to contentItem.taskId,
                "title" to (contentItem.name ?: ""),
                "body" to contentItem.uriString,
                "content_type" to contentItem.contentType
            )

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
            
            if (submission.studentUsername.isBlank()) {
                android.util.Log.e("SupabaseClient", "❌ Empty studentUsername for task submission")
                return@withContext null
            }
            
            // Do NOT send local 'id' to server - let Postgres sequence generate primary key
            val map = mutableMapOf<String, Any?>()
            if (submission.id != null && submission.id != 0L) {
                android.util.Log.w("SupabaseClient", "Not sending local id=${submission.id} to server for task_submissions (will let DB assign id)")
            }
            map["task_id"] = submission.taskId
            map["student_username"] = submission.studentUsername
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
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                var respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
                    android.util.Log.e("SupabaseClient", "❌ insertTaskSubmission failed: code=${resp.code} message=${resp.message}")
                    android.util.Log.e("SupabaseClient", "❌ Response body: $bodyStr")

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
                val studentEscaped = submission.studentUsername.replace("'", "''")
                url = "$baseUrl/rest/v1/task_submissions?task_id=eq.${submission.taskId}&student_username=eq.'${studentEscaped}'&submission_date=eq.${submission.submissionDate}"
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
    suspend fun fetchVideos(): List<VideoData> = fetchList("videos", Array<VideoData>::class.java)
    // Fetch videos for a specific username (server-side filter). Attempts exact eq match.
    suspend fun fetchVideosByUsername(username: String): List<VideoData> = withContext(Dispatchers.IO) {
        try {
            val escaped = username.replace("'", "''")
            // Ask the server to order newest first by timestamp or created_at (fallback)
            val path = "videos?username=eq.'${escaped}'&order=timestamp.desc,created_at.desc.nullslast"
            var list = fetchList(path, Array<VideoData>::class.java)
            if (list.isNotEmpty()) return@withContext list

            // Fallback to ilike for case-insensitive matches, still ordered
            val pathIlike = "videos?username=ilike.'${escaped}'&order=timestamp.desc,created_at.desc.nullslast"
            list = fetchList(pathIlike, Array<VideoData>::class.java)
            if (list.isNotEmpty()) return@withContext list

            // Last resort: fetch all videos ordered and filter client-side
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
                "subscriber_username" to sub.subscriberUsername,
                "creator_username" to sub.creatorUsername,
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
    suspend fun deleteSubscriptionFromSupabase(subscriber: String, creator: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/subscriptions?subscriber_username=eq.'${subscriber.replace("'", "''")}'&creator_username=eq.'${creator.replace("'", "''")}'"
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
    suspend fun isSubscribedRemote(subscriber: String, creator: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try exact match first (eq). If none found, fall back to ilike case-insensitive search.
            val escapedSubscriber = subscriber.replace("'", "''")
            val escapedCreator = creator.replace("'", "''")
            var url = "$baseUrl/rest/v1/subscriptions?subscriber_username=eq.'${escapedSubscriber}'&creator_username=eq.'${escapedCreator}'&select=subscriber_username"
            val request = Request.Builder()
                .url(url)
                .addHeader("apiKey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body?.string() ?: return@withContext false
                val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                if (arr.size() > 0) return@withContext true
                // Fallback: try ilike for case-insensitive match
                try {
                    val urlIlike = "$baseUrl/rest/v1/subscriptions?subscriber_username=ilike.'%${escapedSubscriber}%'&creator_username=ilike.'%${escapedCreator}%'&select=subscriber_username"
                    val req2 = Request.Builder()
                        .url(urlIlike)
                        .addHeader("apiKey", apiKey)
                        .addHeader("Authorization", "Bearer $apiKey")
                        .get()
                        .build()
                    client.newCall(req2).execute().use { resp2 ->
                        if (!resp2.isSuccessful) return@withContext false
                        val body2 = resp2.body?.string() ?: return@withContext false
                        val arr2 = com.google.gson.JsonParser.parseString(body2).asJsonArray
                        return@withContext arr2.size() > 0
                    }
                } catch (t: Exception) {
                    Log.w("SupabaseClient", "isSubscribedRemote ilike fallback failed", t)
                    return@withContext false
                }
            }
        } catch (e: Exception) {
            Log.w("SupabaseClient", "isSubscribedRemote failed", e)
            return@withContext false
        }
    }
    suspend fun fetchTaskSubmissions(): List<TaskSubmission> = fetchList("task_submissions", Array<TaskSubmission>::class.java)
    suspend fun fetchChatMessages(): List<ChatMessage> = fetchList("chat_messages", Array<ChatMessage>::class.java)
    suspend fun fetchFileContexts(): List<FileContext> = fetchList("file_contexts", Array<FileContext>::class.java)
    suspend fun fetchCourses(): List<Course> = fetchList("courses", Array<Course>::class.java)
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
            return@withContext all.filter { c -> (c.creatorUsername ?: "").trim().equals(username.trim(), ignoreCase = true) }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
    }
    suspend fun fetchRoles(): List<Rol> = fetchList("roles", Array<Rol>::class.java)
    suspend fun fetchRecursos(): List<Recurso> = fetchList("recursos", Array<Recurso>::class.java)
    suspend fun fetchRolRecursos(): List<RolRecurso> = fetchList("rol_recursos", Array<RolRecurso>::class.java)

    // Fetch a single role by id
    suspend fun fetchRolById(id: Long): Rol? = withContext(Dispatchers.IO) {
        try {
            val path = "roles?id=eq.$id"
            val list = fetchList(path, Array<Rol>::class.java)
            return@withContext list.firstOrNull()
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    // Fetch a Usuario by username and include its role via separate request (safe for PostgREST)
    suspend fun fetchUsuarioWithRoleByUsername(username: String): Pair<Usuario?, Rol?> = withContext(Dispatchers.IO) {
        try {
            val u = fetchUsuarioByUsername(username)
            if (u == null) return@withContext Pair(null, null)
            val role = if (u.rol_id != null && u.rol_id > 0) {
                fetchRolById(u.rol_id)
            } else null
            return@withContext Pair(u, role)
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext Pair(null, null)
        }
    }

    // Lightweight helper to fetch the most recent timestamp value for a table.
    // This can be used to detect remote changes without downloading entire tables.
    suspend fun fetchTableMaxUpdatedAt(table: String, field: String = "updated_at"): String? = withContext(Dispatchers.IO) {
        try {
            // Try a small set of commonly used timestamp fields when the default is not present.
            val candidates = listOf(field, "timestamp", "last_modified", "last_modified_date", "updatedat", "modified_at")
                .distinct()

            for (f in candidates) {
                try {
                    val path = "${table}?select=${f}&order=${f}.desc&limit=1"
                    val request = buildGetRequest(path)
                    client.newCall(request).execute().use { resp ->
                        val body = resp.body?.string()
                        if (!resp.isSuccessful) {
                            // If server returns 400 (bad request) it's likely the field doesn't exist ÔÇö try next candidate.
                            if (resp.code == 400) {
                                android.util.Log.w("SupabaseClient", "fetchTableMaxUpdatedAt: field '$f' not valid for table '$table' (400). Trying next candidate.")
                                return@use
                            }
                            android.util.Log.w("SupabaseClient", "fetchTableMaxUpdatedAt failed for $table (field=$f): ${resp.code} body=${body}")
                            return@withContext null
                        }

                        if (body.isNullOrEmpty()) return@use
                        try {
                            val json = com.google.gson.JsonParser.parseString(body).asJsonArray
                            if (json.size() == 0) return@use
                            val obj = json[0].asJsonObject
                            if (!obj.has(f)) return@use
                            return@withContext obj.get(f).asString
                        } catch (e: Exception) {
                            e.printStackTrace()
                            return@use
                        }
                    }
                } catch (inner: Exception) {
                    // Continue trying other candidates on transient failures
                    android.util.Log.w("SupabaseClient", "Error trying field '$f' for table '$table': ${inner.message}")
                }
            }

            // No candidate produced a value
            return@withContext null
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }

    // ========== NUEVOS MÉTODOS PARA MCP Y RAG ==========

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
    suspend fun fetchVideoById(id: Int): VideoData? = withContext(Dispatchers.IO) {
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
     * Fetch Topic by ID - Acepta Int o Long
     */
    suspend fun fetchTopicById(id: Int): Topic? = withContext(Dispatchers.IO) {
        try {
            val path = "topics?id=eq.$id"
            val list = fetchList(path, Array<Topic>::class.java)
            return@withContext list.firstOrNull()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching topic by ID", e)
            return@withContext null
        }
    }

    /**
     * Fetch ContentItem by ID - Acepta Int o Long
     */
    suspend fun fetchContentItemById(id: Int): ContentItem? = withContext(Dispatchers.IO) {
        try {
            val path = "content_items?id=eq.$id"
            val list = fetchList(path, Array<ContentItem>::class.java)
            return@withContext list.firstOrNull()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching content item by ID", e)
            return@withContext null
        }
    }

    /**
     * Fetch Task by ID
     */
    suspend fun fetchTaskById(id: Int): Task? = withContext(Dispatchers.IO) {
        try {
            val path = "tasks?id=eq.$id"
            val list = fetchList(path, Array<Task>::class.java)
            return@withContext list.firstOrNull()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching task by ID", e)
            return@withContext null
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
     * Fetch Videos Paginated
     */
    suspend fun fetchVideosPaginated(offset: Int, limit: Int): Pair<List<VideoData>, Int> = withContext(Dispatchers.IO) {
        try {
            val path = "videos?offset=$offset&limit=$limit&order=timestamp.desc"
            val videos = fetchList(path, Array<VideoData>::class.java).toList()
            
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
            
            // Añadir courseTitle si se proporciona
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
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "insertTopicUsingTrigger failed: ${resp.code} body=$respBody")
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
                if (!resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: ""
                    Log.w("SupabaseClient", "updateVideo failed: ${resp.code} ${resp.message} body=$bodyStr")
                    return@withContext false
                }
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "updateVideo exception", e)
            return@withContext false
        }
    }

    /**
     * Insert a subscription to Supabase
     */
    suspend fun subscribeToCreator(subscriberUsername: String, creatorUsername: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val subscription = Subscription(
                subscriberUsername = subscriberUsername,
                creatorUsername = creatorUsername,
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
    suspend fun unsubscribeFromCreator(subscriberUsername: String, creatorUsername: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/subscriptions?subscriber_username=eq.$subscriberUsername&creator_username=eq.$creatorUsername")
                .delete()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
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
     * Upsert student progress to Supabase progreso_estudiante table
     * Uses UPSERT (INSERT ... ON CONFLICT UPDATE) via Prefer header
     */
    suspend fun upsertProgresoEstudiante(progreso: com.example.tareamov.data.entity.ProgresoEstudiante): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                Log.w("SupabaseClient", "Supabase not configured, cannot upsert progreso")
                return@withContext false
            }
            
            Log.d("SupabaseClient", "🔄 Upserting progreso: student=${progreso.usuarioEstudiante}, course=${progreso.cursoId}")
            Log.d("SupabaseClient", "📊 Values: totales=${progreso.tareasTotales}, completadas=${progreso.tareasCompletadas}, progreso=${progreso.porcentajeProgreso}%, promedio=${progreso.promedio}")
            
            // Map entity to Supabase format (snake_case)
            val payload = mapOf(
                "usuario_estudiante" to progreso.usuarioEstudiante,
                "curso_id" to progreso.cursoId,
                "tareas_completadas" to progreso.tareasCompletadas,
                "tareas_totales" to progreso.tareasTotales,
                "porcentaje_progreso" to progreso.porcentajeProgreso,
                "calificacion_ponderada" to progreso.calificacionPonderada,
                "promedio" to (progreso.promedio ?: progreso.calificacionPonderada ?: 0f), // Usar promedio o calificacionPonderada
                "ultima_calculada_en" to java.time.Instant.ofEpochMilli(progreso.ultimaCalculadaEn).toString(),
                "certificado_emitido_en" to progreso.certificadoEmitidoEn?.let { 
                    java.time.Instant.ofEpochMilli(it).toString() 
                },
                "creado_en" to java.time.Instant.ofEpochMilli(progreso.creadoEn).toString()
            )
            
            val json = gson.toJson(payload)
            Log.d("SupabaseClient", "📤 Sending payload: $json")
            
            val body = json.toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/progreso_estudiante")
                .post(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "resolution=merge-duplicates")
                .build()

            requestListener?.invoke("POST $baseUrl/rest/v1/progreso_estudiante")
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful || response.code == 201) {
                Log.d("SupabaseClient", "✅ Progreso upserted successfully for ${progreso.usuarioEstudiante} in course ${progreso.cursoId}")
                return@withContext true
            } else {
                val errorBody = response.body?.string()
                Log.e("SupabaseClient", "❌ Failed to upsert progreso: ${response.code} ${response.message} - $errorBody")
                Log.e("SupabaseClient", "❌ Payload was: $json")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ upsertProgresoEstudiante exception", e)
            return@withContext false
        }
    }
    
    /**
     * Fetch student progress from Supabase
     */
    suspend fun fetchProgresoEstudiante(username: String, courseId: Long): com.example.tareamov.data.entity.ProgresoEstudiante? = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) {
                Log.w("SupabaseClient", "Supabase not configured")
                return@withContext null
            }
            
            val request = buildGetRequest("progreso_estudiante?usuario_estudiante=eq.$username&curso_id=eq.$courseId&limit=1")
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e("SupabaseClient", "Failed to fetch progreso: ${response.code}")
                return@withContext null
            }
            
            val json = response.body?.string() ?: return@withContext null
            val jsonArray = gson.fromJson(json, com.google.gson.JsonArray::class.java)
            
            if (jsonArray.size() == 0) {
                return@withContext null
            }
            
            val obj = jsonArray[0].asJsonObject
            
            return@withContext com.example.tareamov.data.entity.ProgresoEstudiante(
                usuarioEstudiante = obj.get("usuario_estudiante")?.asString ?: username,
                cursoId = obj.get("curso_id")?.asLong ?: courseId,
                tareasCompletadas = obj.get("tareas_completadas")?.asInt ?: 0,
                tareasTotales = obj.get("tareas_totales")?.asInt ?: 0,
                porcentajeProgreso = obj.get("porcentaje_progreso")?.asFloat ?: 0f,
                calificacionPonderada = obj.get("calificacion_ponderada")?.asFloat,
                promedio = obj.get("promedio")?.asFloat ?: obj.get("calificacion_ponderada")?.asFloat,
                estado = obj.get("estado")?.asString,
                ultimaCalculadaEn = parseTimestamp(obj.get("ultima_calculada_en")?.asString)
                    ?: System.currentTimeMillis(),
                certificadoEmitidoEn = parseTimestamp(obj.get("certificado_emitido_en")?.asString),
                creadoEn = parseTimestamp(obj.get("creado_en")?.asString) ?: System.currentTimeMillis()
            )
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
            if (!isConfigured()) {
                return@withContext emptyList()
            }
            
            val request = buildGetRequest("progreso_estudiante?curso_id=eq.$courseId")
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e("SupabaseClient", "Failed to fetch progresos: ${response.code}")
                return@withContext emptyList()
            }
            
            val json = response.body?.string() ?: return@withContext emptyList()
            val jsonArray = gson.fromJson(json, com.google.gson.JsonArray::class.java)
            
            return@withContext jsonArray.map { element ->
                val obj = element.asJsonObject
                com.example.tareamov.data.entity.ProgresoEstudiante(
                    usuarioEstudiante = obj.get("usuario_estudiante")?.asString ?: "",
                    cursoId = obj.get("curso_id")?.asLong ?: courseId,
                    tareasCompletadas = obj.get("tareas_completadas")?.asInt ?: 0,
                    tareasTotales = obj.get("tareas_totales")?.asInt ?: 0,
                    porcentajeProgreso = obj.get("porcentaje_progreso")?.asFloat ?: 0f,
                    calificacionPonderada = obj.get("calificacion_ponderada")?.asFloat,
                    promedio = obj.get("promedio")?.asFloat ?: obj.get("calificacion_ponderada")?.asFloat,
                    estado = obj.get("estado")?.asString,
                    ultimaCalculadaEn = parseTimestamp(obj.get("ultima_calculada_en")?.asString)
                        ?: System.currentTimeMillis(),
                    certificadoEmitidoEn = parseTimestamp(obj.get("certificado_emitido_en")?.asString),
                    creadoEn = parseTimestamp(obj.get("creado_en")?.asString) ?: System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "fetchProgresosByCurso exception", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * Helper to parse ISO timestamp to epoch millis
     */
    private fun parseTimestamp(timestampStr: String?): Long? {
        if (timestampStr.isNullOrBlank()) return null
        return try {
            java.time.Instant.parse(timestampStr).toEpochMilli()
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Failed to parse timestamp: $timestampStr", e)
            null
        }
    }
    
    /**
     * Actualiza el campo certificado_emitido_en cuando se genera un certificado
     */
    suspend fun updateCertificateIssuedDate(
        studentUsername: String,
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
            val url = "$baseUrl/rest/v1/progreso_estudiante?usuario_estudiante=eq.$studentUsername&curso_id=eq.$courseId"
            
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
                
                Log.i("SupabaseClient", "✅ Certificate issued date updated for $studentUsername in course $courseId")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Exception updating certificate date", e)
            return@withContext false
        }
    }
}
