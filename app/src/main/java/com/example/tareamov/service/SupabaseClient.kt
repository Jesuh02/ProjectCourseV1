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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll

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
    private val apiKey = BuildConfig.SUPABASE_ANON_KEY
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

    // Convenience helper: fetch only the avatar URL for a username
    suspend fun fetchUsuarioAvatarByUsername(username: String): String? = withContext(Dispatchers.IO) {
        try {
            val usuario = fetchUsuarioByUsername(username)
            return@withContext usuario?.avatar
        } catch (e: Exception) {
            return@withContext null
        }
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
            
            // When updating an existing task, we should NOT update topic_id
            // The task already has the correct topic_id in Supabase
            // Only update title and description to avoid FK constraint violations
            val map = mutableMapOf<String, Any?>()
            // REMOVED: if (task.topicId != 0L) map["topic_id"] = task.topicId
            // The topic_id should not be changed during an update - it's set at creation time
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

    /**
     * Fetch a Persona by its identificacion field (unique constraint).
     * Returns null if not found.
     */
    suspend fun fetchPersonaByIdentificacion(identificacion: String): Persona? = withContext(Dispatchers.IO) {
        try {
            val encodedId = java.net.URLEncoder.encode(identificacion, "UTF-8")
            val path = "personas?identificacion=eq.$encodedId&limit=1"
            val request = buildGetRequest(path)
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            if (response.isSuccessful && !body.isNullOrEmpty()) {
                val array = gson.fromJson(body, Array<Persona>::class.java)
                if (array.isNotEmpty()) {
                    return@withContext array[0]
                }
            }
            null
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching persona by identificacion: ${e.message}", e)
            null
        }
    }

    /**
     * Check if a user with the given email already exists in Supabase.
     * Returns the Usuario if found, null otherwise.
     */
    suspend fun fetchUsuarioByEmail(email: String): Usuario? = withContext(Dispatchers.IO) {
        try {
            val encodedEmail = java.net.URLEncoder.encode(email, "UTF-8")
            val path = "usuarios?email=eq.$encodedEmail&limit=1"
            val request = buildGetRequest(path)
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            
            if (response.isSuccessful && !body.isNullOrEmpty()) {
                val array = underscoredGson.fromJson(body, Array<Usuario>::class.java)
                if (array.isNotEmpty()) {
                    return@withContext array[0]
                }
            }
            null
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching usuario by email: ${e.message}", e)
            null
        }
    }

    /**
     * Check if a user with the given username already exists in Supabase.
     */


    suspend fun insertUsuario(usuario: Usuario): Long? {
        Log.d("SupabaseClient", "insertUsuario called for username: ${usuario.usuario}, email: ${usuario.email}, persona_id: ${usuario.persona_id}")
        // Only include fields that exist in the Supabase usuarios table
        // Note: rol_id is NOT in the remote usuarios table - roles are managed separately
        val payload = mapOf(
            "username" to usuario.usuario,
            "contrasena" to usuario.contrasena,
            "persona_id" to usuario.persona_id,
            "email" to usuario.email,
            "avatar" to usuario.avatar,
            "is_active" to usuario.isActive
            // Note: email_verified, last_login, created_at, rol_id are managed by Supabase or don't exist in remote table
        )
        val result = insertRecord("usuarios", payload)
        Log.d("SupabaseClient", "insertUsuario result for ${usuario.usuario}: $result")
        return result
    }

    /**
     * @deprecated Video insertion should be handled by the backend API.
     * Use the backend endpoint POST /video/insert instead.
     * This method is kept for backward compatibility only.
     * @see SyncRepository.uploadVideoViaBackendApi
     */
    @Deprecated("Use backend API /video/insert instead", ReplaceWith("uploadVideoViaBackendApi(video)"))
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
                "price" to video.price,
                "remote_id" to video.remoteId // Store creator ID
            )
            
            // Include course_id if provided
            if (video.courseId != null) {
                map["course_id"] = video.courseId
            }
            
            // Do NOT include id - let database auto-generate it
            // if (video.id > 0) { map["id"] = video.id }

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            
            Log.d("SupabaseClient", "insertVideo payload: ${gson.toJson(map)}")
            
            // Simple POST insert (no ON CONFLICT since videos table lacks unique constraint on remote_id)
            val url = "$baseUrl/rest/v1/videos"
            val effectiveKey = effectiveApiKey()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", effectiveKey)
                .addHeader("Authorization", "Bearer $effectiveKey")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                Log.d("SupabaseClient", "insertVideo response: code=${resp.code}, body=$respBody")
                
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
                    // Handle duplicate key error (23505) gracefully - video already exists
                    if (resp.code == 409 && bodyStr.contains("23505")) {
                        Log.w("SupabaseClient", "Video already exists (duplicate key), skipping insert")
                        // Try to fetch the existing video ID by title and course_id
                        val existingId = fetchExistingVideoId(video.title, video.courseId)
                        if (existingId != null) {
                            Log.d("SupabaseClient", "Found existing video ID: $existingId")
                            return@withContext existingId
                        }
                        return@withContext null
                    }
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
                        val obj = jsonArray[0].asJsonObject
                        val idElem = obj.get("id")
                        // Handle both numeric and string ID formats
                        val returnedId = when {
                            idElem == null || idElem.isJsonNull -> null
                            idElem.isJsonPrimitive -> {
                                val prim = idElem.asJsonPrimitive
                                if (prim.isNumber) prim.asLong
                                else prim.asString.toLongOrNull()
                            }
                            else -> null
                        }
                        Log.d("SupabaseClient", "insertVideo success: video ID = $returnedId")
                        return@withContext returnedId
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseClient", "Error parsing insertVideo response: $respBody", e)
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
    
    // Helper to fetch existing video ID when duplicate is detected
    private suspend fun fetchExistingVideoId(title: String, courseId: Long?): Long? = withContext(Dispatchers.IO) {
        try {
            val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
            val filter = if (courseId != null) {
                "title=eq.$encodedTitle&course_id=eq.$courseId"
            } else {
                "title=eq.$encodedTitle"
            }
            val url = "$baseUrl/rest/v1/videos?$filter&select=id&limit=1"
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", apiKey)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Accept", "application/json")
                .build()
                
            client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string()
                    if (!body.isNullOrEmpty()) {
                        val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                        if (jsonArray.size() > 0) {
                            return@withContext jsonArray[0].asJsonObject.get("id")?.asLong
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching existing video ID", e)
        }
        return@withContext null
    }

    /**
     * Insert a new reinforcement question history record for a user in a course.
     * This creates a new row instead of updating an existing one, preserving history.
     * 🛠️ AUTO-REPAIRS: Auto-generates explanations if missing before inserting
     */
    suspend fun insertReinforcementHistory(
        userId: Long, 
        courseId: Long, 
        topicId: Long = -1L,
        taskId: Long = -1L,
        newQuestions: List<Any>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // ═══════════════════════════════════════════════════════════════════════════
            // VALIDACIÓN Y AUTO-REPARACIÓN: Garantizar que TODAS tengan explanation
            // ═══════════════════════════════════════════════════════════════════════════
            Log.i("SupabaseClient", "🔍 Validando ${newQuestions.size} preguntas antes de insertar...")
            
            val repairedQuestions = newQuestions.mapIndexed { index, question ->
                when (question) {
                    // Handle QuizQuestion objects (from ViewModel)
                    is com.example.tareamov.ui.compose.QuizQuestion -> {
                        val quizQ = question
                        // 🛡️ Safe check against GSON deserialization issues where non-null field handles null
                        val explanation = quizQ.explanation
                        val safeExplanation = if (explanation != null) explanation else ""
                        // Relaxed validation to 5 chars to match backend
                        val isValid = safeExplanation.isNotBlank() && safeExplanation.length >= 5
                        
                        if (!isValid) {
                            // AUTO-GENERAR explanation para QuizQuestion
                            val explLen = safeExplanation.length
                            Log.w("SupabaseClient", "🔧 Auto-reparando QuizQuestion $index: explanation=$explLen chars (insuficiente)")
                            
                            val correctOption = quizQ.options.getOrNull(quizQ.correctIndex) ?: "la opción correcta"
                            
                            val generatedExplanation = "La respuesta correcta es: \"$correctOption\". " +
                                "Esta opción es correcta según el contenido y material de referencia proporcionado en el curso. " +
                                "Las demás opciones no cumplen con los criterios establecidos en el material educativo."
                            
                            Log.i("SupabaseClient", "✨ Explanation auto-generada para QuizQuestion: ${generatedExplanation.length} chars")
                            
                            // Return as Map with fixed explanation (QuizQuestion is immutable)
                            return@mapIndexed mapOf(
                                "question" to quizQ.question,
                                "options" to quizQ.options,
                                "correctIndex" to quizQ.correctIndex,
                                "explanation" to generatedExplanation
                            )
                        } else {
                            Log.d("SupabaseClient", "✅ QuizQuestion $index: explanation válida (${safeExplanation.length} chars)")
                            // Return as Map to ensure consistent serialization
                            return@mapIndexed mapOf(
                                "question" to quizQ.question,
                                "options" to quizQ.options,
                                "correctIndex" to quizQ.correctIndex,
                                "explanation" to safeExplanation
                            )
                        }
                    }
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        val questionMap = question as? Map<String, Any?> ?: return@mapIndexed question
                        
                        val explanation = questionMap["explanation"] as? String
                        val isValid = explanation != null && explanation.length >= 50
                        
                        if (!isValid) {
                            // AUTO-GENERAR explanation como último recurso
                            val explLen = explanation?.length ?: 0
                            Log.w("SupabaseClient", "🔧 Auto-reparando Map $index: explanation=$explLen chars (insuficiente)")
                            
                            val correctIndex = (questionMap["correctIndex"] as? Number)?.toInt() ?: 0
                            val options = questionMap["options"] as? List<*>
                            val correctOption = options?.getOrNull(correctIndex)?.toString() ?: "la opción correcta"
                            
                            val mutableMap = questionMap.toMutableMap()
                            mutableMap["explanation"] = "La respuesta correcta es: \"$correctOption\". " +
                                "Esta opción es correcta según el contenido y material de referencia proporcionado en el curso. " +
                                "Las demás opciones no cumplen con los criterios establecidos en el material educativo."
                            
                            Log.i("SupabaseClient", "✨ Explanation auto-generada para Map: ${(mutableMap["explanation"] as String).length} chars")
                            return@mapIndexed mutableMap
                        } else {
                            Log.d("SupabaseClient", "✅ Map $index: explanation válida (${explanation?.length ?: 0} chars)")
                        }
                        
                        question
                    }
                    else -> {
                        // Try to convert to Map via Gson for any other type
                        Log.w("SupabaseClient", "⚠️ Pregunta $index: tipo ${question?.javaClass?.simpleName}, intentando conversión...")
                        try {
                            val json = gson.toJson(question)
                            val map = gson.fromJson<Map<String, Any?>>(json, Map::class.java)
                            val explanation = map["explanation"] as? String
                            
                            if (explanation.isNullOrBlank() || explanation.length < 50) {
                                val correctIndex = (map["correctIndex"] as? Number)?.toInt() ?: 0
                                val options = map["options"] as? List<*>
                                val correctOption = options?.getOrNull(correctIndex)?.toString() ?: "la opción correcta"
                                
                                val mutableMap = map.toMutableMap()
                                mutableMap["explanation"] = "La respuesta correcta es: \"$correctOption\". " +
                                    "Esta opción es correcta según el contenido y material de referencia proporcionado en el curso. " +
                                    "Las demás opciones no cumplen con los criterios establecidos en el material educativo."
                                
                                Log.i("SupabaseClient", "✨ Explanation auto-generada via Gson: ${(mutableMap["explanation"] as String).length} chars")
                                return@mapIndexed mutableMap
                            }
                            map
                        } catch (e: Exception) {
                            Log.e("SupabaseClient", "❌ No se pudo convertir pregunta $index: ${e.message}")
                            question
                        }
                    }
                }
            }
            
            Log.i("SupabaseClient", "✅ Validación completada: ${repairedQuestions.size} preguntas procesadas")
            
            // Simply insert the new batch of questions as a new record
            val payload = mutableMapOf(
                "user_id" to userId,
                "course_id" to courseId,
                "questions" to repairedQuestions
            )
            if (topicId > 0) payload["topic_id"] = topicId
            if (taskId > 0) payload["task_id"] = taskId
            
            val body = gson.toJson(payload).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/reinforcement_question_history"
            
            val requestPost = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(requestPost).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val b = resp.body?.string()
                    Log.e("SupabaseClient", "insertReinforcementHistory failed: ${resp.code} ${resp.message} body=$b")
                    return@withContext false
                }
                Log.i("SupabaseClient", "✅ Reinforcement history insertado exitosamente con ${repairedQuestions.size} preguntas")
                return@withContext true
            }

        } catch (e: Exception) {
            Log.e("SupabaseClient", "insertReinforcementHistory exception", e)
            return@withContext false
        }
    }

    /**
     * Fetch previous reinforcement questions to avoid repetition.
     */
    suspend fun fetchReinforcementHistory(userId: Long, courseId: Long, topicId: Long = -1L, taskId: Long = -1L): List<String> = withContext(Dispatchers.IO) {
        // OPTIMIZATION: Return empty list. History is now managed server-side (MCPService).
        // This prevents transferring massive JSON data and overflowing tokens.
        // The MCP Service (Backend) will automatically fetch the recent history context.
        return@withContext emptyList()
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

    // Delete a video by id from the videos table
    suspend fun deleteVideoById(id: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/videos?id=eq.${id}"
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
                    Log.w("SupabaseClient", "deleteVideoById failed: ${resp.code} ${resp.message} body=$bodyStr")
                    return@withContext false
                }
                Log.d("SupabaseClient", "deleteVideoById successful for id=$id")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "deleteVideoById exception", e)
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
            Log.d("SupabaseClient", "📤   - name: '${contentItem.name}'")
            Log.d("SupabaseClient", "📤   - uriString: '${contentItem.uriString}'")
            Log.d("SupabaseClient", "📤   - contentType: '${contentItem.contentType}'")
            Log.d("SupabaseClient", "📤   - orderIndex: ${contentItem.orderIndex}")
            
            // Map local ContentItem fields to Supabase content_items columns (title/body)
            val map = mutableMapOf<String, Any?>(
                "topic_id" to contentItem.topicId,
                "title" to (contentItem.name ?: ""),
                "body" to contentItem.uriString,
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

    /**
     * Delete a ContentItem by ID from the content_items table
     * @param id The ID of the content item to delete
     * @return true on success, false on failure
     */
    suspend fun deleteContentItem(id: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "🗑️ Deleting content item id=$id")
            val url = "$baseUrl/rest/v1/content_items?id=eq.$id"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            client.newCall(request).execute().use { resp ->
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "❌ deleteContentItem failed: ${resp.code} ${resp.message} body=$respBody")
                    return@withContext false
                }
                Log.d("SupabaseClient", "✅ ContentItem id=$id deleted successfully")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ deleteContentItem exception for id=$id", e)
            return@withContext false
        }
    }

    /**
     * Delete a Task by ID from the tasks table
     * @param id The ID of the task to delete
     * @return true on success, false on failure
     */
    suspend fun deleteTask(id: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "🗑️ Deleting task id=$id")
            val url = "$baseUrl/rest/v1/tasks?id=eq.$id"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "❌ deleteTask failed: ${resp.code} ${resp.message}")
                    return@withContext false
                }
                Log.d("SupabaseClient", "✅ Task id=$id deleted successfully")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ deleteTask exception for id=$id", e)
            return@withContext false
        }
    }

    /**
     * Delete a Topic by ID from the topics table
     * @param id The ID of the topic to delete
     * @return true on success, false on failure
     */
    suspend fun deleteTopic(id: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "🗑️ Deleting topic id=$id")
            val url = "$baseUrl/rest/v1/topics?id=eq.$id"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "❌ deleteTopic failed: ${resp.code} ${resp.message}")
                    return@withContext false
                }
                Log.d("SupabaseClient", "✅ Topic id=$id deleted successfully")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ deleteTopic exception for id=$id", e)
            return@withContext false
        }
    }

    /**
     * Delete all ContentItems for a specific Task
     * @param taskId The ID of the parent task
     * @return true on success
     */
    suspend fun deleteContentItemsByTaskId(taskId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "🗑️ Deleting content items for task_id=$taskId")
            val url = "$baseUrl/rest/v1/content_items?task_id=eq.$taskId"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "❌ deleteContentItemsByTaskId failed: ${resp.code} ${resp.message}")
                    return@withContext false
                }
                Log.d("SupabaseClient", "✅ Content items for task_id=$taskId deleted successfully")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "❌ deleteContentItemsByTaskId exception", e)
            return@withContext false
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
            map["rol_id"] = usuario.rol_id
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

    // Update only profile fields (username, avatar) to avoid issues with other fields
    suspend fun updateUsuarioProfile(userId: Long, username: String, avatarUrl: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>()
            map["username"] = username
            if (avatarUrl != null) {
                map["avatar"] = avatarUrl
            }
            
            val body = gson.toJson(map).toRequestBody(jsonMedia)
            val url = "$baseUrl/rest/v1/usuarios?id=eq.$userId"

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
                    Log.w("SupabaseClient", "updateUsuarioProfile failed status=${resp.code} body=${resp.body?.string()}")
                    return@withContext false
                }
                Log.d("SupabaseClient", "updateUsuarioProfile success for id: $userId")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "updateUsuarioProfile exception", e)
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
            .addHeader("Cache-Control", "no-cache, no-store, must-revalidate") // Disable cache
            .addHeader("Pragma", "no-cache") // HTTP 1.0 backward compatibility
            .addHeader("Expires", "0") // Proxies

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

    /**
     * Build a PATCH request for updating records
     * @param path The relative path (e.g., "topics?id=eq.123")
     * @param jsonBody The JSON body for the PATCH request
     * @return Request configured with authentication headers
     */
    private fun buildPatchRequest(path: String, jsonBody: String): Request {
        val url = "$baseUrl/rest/v1/$path"
        val key = effectiveApiKey()
        val body = jsonBody.toRequestBody(jsonMedia)
        
        val builder = Request.Builder()
            .url(url)
            .patch(body)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .addHeader("Prefer", "return=representation")

        if (key.isNotEmpty()) {
            builder.addHeader("apikey", key)
            builder.addHeader("Authorization", "Bearer $key")
        } else {
            android.util.Log.w("SupabaseClient", "buildPatchRequest: no Supabase API key available; request to $path will likely be rejected (401).")
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
        val user = fetchUsuarioById(userId)
        return@withContext user?.isAdmin == true
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

        android.util.Log.d("SupabaseClient", "searchCourses: query='$trimmed', encoded='$encodedTerm'")

        // Strategy: Search in two phases
        // 1. Search courses by title, description, category, tags, creator_username
        // 2. Search users by username and get their courses
        
        val results = mutableSetOf<Course>() // Use set to avoid duplicates

        // Phase 1: Search courses directly
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

        try {
            val courseResults = fetchList(path, Array<Course>::class.java)
            android.util.Log.d("SupabaseClient", "searchCourses: Phase 1 found ${courseResults.size} courses")
            results.addAll(courseResults)
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "searchCourses Phase 1 failed", e)
        }

        // Phase 2: Search for users matching the query and get their courses
        try {
            val userPath = "usuarios?or=(username.ilike.*$encodedTerm*,nombre.ilike.*$encodedTerm*)&select=id,username&limit=10"
            val userResults = fetchList(userPath, Array<Usuario>::class.java)
            android.util.Log.d("SupabaseClient", "searchCourses: Phase 2 found ${userResults.size} matching users")
            
            // For each matching user, get their courses
            for (user in userResults) {
                try {
                    val userCoursesPath = "courses?creator_user_id=eq.${user.id}&order=timestamp.desc&limit=50"
                    val userCourses = fetchList(userCoursesPath, Array<Course>::class.java)
                    android.util.Log.d("SupabaseClient", "searchCourses: User '${user.usuario}' has ${userCourses.size} courses")
                    results.addAll(userCourses)
                } catch (e: Exception) {
                    android.util.Log.w("SupabaseClient", "searchCourses: Failed to fetch courses for user ${user.usuario}", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("SupabaseClient", "searchCourses Phase 2 (user search) failed", e)
        }

        // If no results from remote search, fallback to local filtering
        if (results.isEmpty()) {
            android.util.Log.d("SupabaseClient", "searchCourses: No remote results, falling back to local search")
            try {
                val localResults = fetchCourses().filter { course ->
                    course.title.contains(trimmed, ignoreCase = true) ||
                            course.description.contains(trimmed, ignoreCase = true) ||
                            (course.category?.contains(trimmed, ignoreCase = true) == true) ||
                            (course.tags?.contains(trimmed, ignoreCase = true) == true)
                }
                android.util.Log.d("SupabaseClient", "searchCourses: Local fallback found ${localResults.size} courses")
                return localResults.take(limit)
            } catch (fallback: Exception) {
                android.util.Log.w("SupabaseClient", "searchCourses fallback filtering failed", fallback)
                return emptyList()
            }
        }

        // Sort by timestamp and return unique results
        val finalResults = results.distinctBy { it.id }
            .sortedByDescending { it.timestamp }
            .take(limit)
        
        android.util.Log.d("SupabaseClient", "searchCourses: Returning ${finalResults.size} unique courses")
        return finalResults
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
                        ?: obj.get("courseId")?.takeIf { !it.isJsonNull }?.asLong
                    val remoteId = obj.get("remote_id")?.takeIf { !it.isJsonNull }?.asLong
                        ?: obj.get("remoteId")?.takeIf { !it.isJsonNull }?.asLong
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
                            courseId = courseId,
                            remoteId = remoteId
                        )
                    )
                } catch (t: Exception) {
                    Log.w("SupabaseClient", "Failed to parse video element", t)
                }
            }
            repaired
        }
    }

    suspend fun fetchVideoById(id: Long): VideoData? = withContext(Dispatchers.IO) {
        try {
            // Try typed mapping first
            val list = fetchList("videos?id=eq.$id", Array<VideoData>::class.java)
            val video = list.firstOrNull()
            
            // Validate critical fields that might be null due to Gson unsafe allocation
            // Even though Kotlin type says non-null, Gson can put null there
            val isInvalid = video != null && (video.username == null || video.title == null)
            
            if (!isInvalid && video != null) {
                return@withContext video
            }
            
            if (isInvalid) Log.w("SupabaseClient", "fetchVideoById: Gson returned null fields, falling back to manual parse")
            
        } catch (e: Exception) {
            Log.w("SupabaseClient", "fetchVideoById: typed fetch failed, falling back to manual parse", e)
        }

        // Manual fallback
        try {
            val req = buildGetRequest("videos?id=eq.$id")
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                if (!resp.isSuccessful || body.isNullOrEmpty()) return@withContext null
                
                val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                if (arr.size() == 0) return@withContext null
                
                val obj = arr.get(0).asJsonObject
                val username = when {
                    obj.has("username") && !obj.get("username").isJsonNull -> obj.get("username").asString
                    obj.has("creator_username") && !obj.get("creator_username").isJsonNull -> obj.get("creator_username").asString
                    obj.has("user") && !obj.get("user").isJsonNull -> obj.get("user").asString
                    else -> "unknown"
                }
                val description = obj.get("description")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val title = obj.get("title")?.takeIf { !it.isJsonNull }?.asString ?: ""
                val courseId = obj.get("course_id")?.takeIf { !it.isJsonNull }?.asLong
                    ?: obj.get("courseId")?.takeIf { !it.isJsonNull }?.asLong
                val remoteId = obj.get("remote_id")?.takeIf { !it.isJsonNull }?.asLong
                    ?: obj.get("remoteId")?.takeIf { !it.isJsonNull }?.asLong
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
                    courseId = courseId,
                    remoteId = remoteId
                )
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "fetchVideoById manual parse failed", e)
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
                                ?: obj.get("courseId")?.takeIf { !it.isJsonNull }?.asLong
                            val remoteId = obj.get("remote_id")?.takeIf { !it.isJsonNull }?.asLong
                                ?: obj.get("remoteId")?.takeIf { !it.isJsonNull }?.asLong
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
                                    courseId = courseId,
                                    remoteId = remoteId
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

    // Fetch creators that a specific user is subscribed to
    suspend fun fetchSubscribedCreators(subscriberId: Long): List<Usuario> = withContext(Dispatchers.IO) {
        try {
            // 1. Get subscriptions to find creator_ids
            val response = client.newCall(
                buildGetRequest("subscriptions?subscriber_id=eq.$subscriberId&select=creator_id")
            ).execute()
            
            if (!response.isSuccessful) {
                Log.e("SupabaseClient", "Error fetching subscriptions: ${response.code}")
                return@withContext emptyList()
            }
            
            val json = response.body?.string() ?: return@withContext emptyList()
            
            // Helper class for parsing
            data class SubItem(val creator_id: Long)
            val subs = gson.fromJson(json, Array<SubItem>::class.java)
            val creatorIds = subs.map { it.creator_id }
            
            if (creatorIds.isEmpty()) return@withContext emptyList()
            
            // 2. Get users details for these creator_ids
            // Supabase "in" filter: id=in.(1,2,3)
            val idsStr = creatorIds.joinToString(",")
            val usersResponse = client.newCall(
                buildGetRequest("usuarios?id=in.($idsStr)")
            ).execute()
            
            if (!usersResponse.isSuccessful) {
                Log.e("SupabaseClient", "Error fetching subscribed users: ${usersResponse.code}")
                return@withContext emptyList()
            }
            
            val usersJson = usersResponse.body?.string() ?: return@withContext emptyList()
            gson.fromJson(usersJson, Array<Usuario>::class.java).toList()
            
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching subscribed creators", e)
            emptyList()
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
     * Fetch top students globally based on completed courses (estado='Ganado').
     * Returns a list of maps with keys: user_id, username, avg_grade, courses_count.
     * Ordered by courses_count desc, then avg_grade desc.
     */
    suspend fun fetchTopStudentsGlobal(limit: Int = 5): List<Map<String, Any?>> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()

            // Step 1: fetch ALL progreso_estudiante rows where estado=Ganado
            val path = "progreso_estudiante?estado=eq.Ganado&select=usuario_estudiante,promedio,calificacion_ponderada,curso_id"
            val rows = fetchListMap(path)

            if (rows.isEmpty()) return@withContext emptyList()

            // Aggregate by usuario_estudiante
            val agg = mutableMapOf<Long, MutableMap<String, Any>>() // userId -> { sum, count, completed_courses }

            rows.forEach { row ->
                val userIdAny = row["usuario_estudiante"] ?: row["usuarioEstudiante"] ?: return@forEach
                val userId = when (userIdAny) {
                    is Number -> userIdAny.toLong()
                    is String -> userIdAny.toLongOrNull() ?: return@forEach
                    else -> return@forEach
                }

                val calificacionAny = row["calificacion_ponderada"] ?: row["calificacionPonderada"]
                val calificacion = when (calificacionAny) {
                    is Number -> calificacionAny.toDouble()
                    is String -> calificacionAny.toDoubleOrNull() ?: 0.0
                    else -> 0.0
                }
                
                // Fallback to promedio if calificacion_ponderada is 0
                val finalCalificacion = if (calificacion == 0.0) {
                     val promedioAny = row["promedio"] ?: row["promedio"]
                     when (promedioAny) {
                        is Number -> promedioAny.toDouble()
                        is String -> promedioAny.toDoubleOrNull() ?: 0.0
                        else -> 0.0
                     }
                } else calificacion

                val cursoAny = row["curso_id"] ?: row["cursoId"]
                val cursoId = when (cursoAny) {
                    is Number -> cursoAny.toLong()
                    is String -> cursoAny.toLongOrNull()
                    else -> null
                }

                val entry = agg.getOrPut(userId) { mutableMapOf("sum" to 0.0, "count" to 0, "completed_courses" to mutableSetOf<Long>()) }
                
                if (cursoId != null) {
                    val completedSet = entry["completed_courses"] as MutableSet<Long>
                    if (!completedSet.contains(cursoId)) {
                        completedSet.add(cursoId)
                        
                        val sum = (entry["sum"] as Double) + finalCalificacion
                        val cnt = (entry["count"] as Int) + 1
                        entry["sum"] = sum
                        entry["count"] = cnt
                    }
                }
            }

            if (agg.isEmpty()) return@withContext emptyList()

            // Step 2: fetch usernames for involved user ids
            val userIds = agg.keys.toList()
            val idsUsersStr = userIds.joinToString(",")
            val usersPath = "usuarios?id=in.($idsUsersStr)&select=id,username"
            val usersList = fetchListMap(usersPath)
            val usernameById = usersList.associate { row ->
                val idAny = row["id"] ?: row["id"]
                val id = when (idAny) {
                    is Number -> idAny.toLong()
                    is String -> idAny.toLongOrNull()
                    else -> null
                }
                val username = (row["username"] ?: row["usuario"] ?: "") as String
                id to username
            }.filterKeys { it != null } as Map<Long, String>

            // Build result list
            val result = agg.map { (userId, data) ->
                val sum = data["sum"] as Double
                val cnt = data["count"] as Int
                val completedCoursesSet = data["completed_courses"] as MutableSet<Long>
                val avg = if (cnt > 0) sum / cnt else 0.0

                mapOf<String, Any?>(
                    "user_id" to userId,
                    "username" to (usernameById[userId] ?: "Usuario $userId"),
                    "avg_grade" to avg,
                    "courses_count" to completedCoursesSet.size
                )
            }.sortedWith(compareByDescending<Map<String, Any?>> { (it["courses_count"] as Int) }
                .thenByDescending { (it["avg_grade"] as Number).toDouble() })
             .take(limit)

            return@withContext result
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching global top students", e)
            emptyList()
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

    // Fetch FREE courses from Supabase (server-side filter is_premium=false)
    suspend fun fetchFreeCourses(): List<Course> = withContext(Dispatchers.IO) {
        val pageSize = 1000 // safe chunk size for PostgREST
        var offset = 0
        var page = 0
        val all = mutableListOf<Course>()
        try {
            while (true) {
                // Added is_premium=eq.false filter
                val path = "courses?select=*&is_premium=eq.false&order=timestamp.desc&limit=$pageSize&offset=$offset"
                android.util.Log.d("SupabaseClient", "fetchFreeCourses[p$page]: Requesting $path")
                val request = buildGetRequest(path)
                var shouldStop = false
                client.newCall(request).execute().use { resp ->
                    val body = resp.body?.string()
                    
                    if (!resp.isSuccessful) {
                        android.util.Log.w("SupabaseClient", "GET $path failed: ${resp.code}")
                        shouldStop = true
                    }

                    if (!shouldStop && body.isNullOrEmpty()) {
                        shouldStop = true
                    }

                    val pageItems = if (!shouldStop) {
                        try {
                            val arr = underscoredGson.fromJson(body, Array<Course>::class.java)
                            arr?.toList() ?: emptyList()
                        } catch (e: Exception) {
                            android.util.Log.e("SupabaseClient", "fetchFreeCourses[p$page]: JSON parse error: ${e.message}", e)
                            emptyList()
                        }
                    } else emptyList()

                    all.addAll(pageItems)

                    if (pageItems.size < pageSize) {
                        shouldStop = true
                    }
                }
                if (shouldStop) {
                    break
                }
                page += 1
                offset += pageSize

                if (page > 500) {
                    break
                }
            }

            val deduped = all.distinctBy { it.id }
            android.util.Log.d("SupabaseClient", "fetchFreeCourses: DONE -> total=${deduped.size}")
            return@withContext deduped
        } catch (e: Exception) {
            android.util.Log.e("SupabaseClient", "Error fetching free courses: ${e.message}", e)
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
    
    // Fetch a single course by exact title (server-side filter, case-insensitive).
    // Uses `ilike` without wildcards so the match is case-insensitive but exact.
    suspend fun fetchCourseByTitle(title: String): Course? = withContext(Dispatchers.IO) {
        try {
            val table = "courses"
            // Encode safely (spaces -> %20)
            val escaped = java.net.URLEncoder.encode(title, "UTF-8").replace("+", "%20")
            val path = "$table?title=ilike.$escaped&select=*"
            requestListener?.invoke("$baseUrl/rest/v1/$path")
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
                    Log.d("SupabaseClient", "📦 Parsed ContentItem: id=${item.id}, topicId=${item.topicId}, taskId=${item.taskId}, name='${item.name}', uri='${item.uriString.take(50)}...', type=${item.contentType}")
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
    
    // Manual parser for ContentItem to ensure 'body' maps to 'uriString' correctly
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
            name = safeGetString("title"),
            uriString = safeGetString("body") ?: "",  // Explicitly map 'body' to uriString
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
                    Log.d("SupabaseClient", "📋 DEBUG item: id=${item.id}, topicId=${item.topicId}, taskId=${item.taskId}, name='${item.name}'")
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
                    Log.d("SupabaseClient", "📦 Parsed ContentItem: id=${item.id}, name=${item.name}, uriString='${item.uriString}', type=${item.contentType}")
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

    // Fetch courses by multiple creator user IDs (server-side filter)
    suspend fun fetchCoursesByCreatorUserIds(userIds: List<Long>): List<Course> = withContext(Dispatchers.IO) {
        if (userIds.isEmpty()) return@withContext emptyList()
        try {
            val idsStr = userIds.joinToString(",")
            val path = "courses?creator_user_id=in.($idsStr)&order=timestamp.desc,created_at.desc.nullslast"
            fetchList(path, Array<Course>::class.java)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching courses by creator user IDs", e)
            emptyList()
        }
    }

    // Fetch courses by multiple course IDs (server-side filter using 'in')
    suspend fun fetchCoursesByIds(courseIds: List<Long>): List<Course> = withContext(Dispatchers.IO) {
        if (courseIds.isEmpty()) return@withContext emptyList()
        try {
            val idsStr = courseIds.joinToString(",")
            val path = "courses?id=in.($idsStr)&order=timestamp.desc,created_at.desc.nullslast"
            Log.d("SupabaseClient", "Fetching courses by IDs: $idsStr")
            val result = fetchList(path, Array<Course>::class.java)
            Log.d("SupabaseClient", "Found ${result.size} courses for ${courseIds.size} IDs")
            result
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching courses by IDs", e)
            emptyList()
        }
    }

    // Search users by username (partial match)
    suspend fun searchUsersByUsername(query: String): List<Usuario> = withContext(Dispatchers.IO) {
        try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val path = "usuarios?username=ilike.*$encoded*"
            fetchList(path, Array<Usuario>::class.java)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error searching users by username", e)
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
    
    // Fetch videos by remote_id (creator user ID stored directly in videos table)
    suspend fun fetchVideosByRemoteId(userId: Long): List<VideoData> = withContext(Dispatchers.IO) {
        try {
            if (userId <= 0) return@withContext emptyList()
            val path = "videos?remote_id=eq.$userId&order=timestamp.desc,created_at.desc.nullslast"
            Log.d("SupabaseClient", "Fetching videos by remote_id=$userId, path=$path")
            val videos = fetchList(path, Array<VideoData>::class.java)
            Log.d("SupabaseClient", "Found ${videos.size} videos with remote_id=$userId")
            videos
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching videos by remote_id=$userId", e)
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
     * Update Topic in Supabase
     */
    suspend fun updateTopic(topic: Topic): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = "topics?id=eq.${topic.id}"
            val jsonBody = """
                {
                    "name": "${topic.name.replace("\"", "\\\"")}",
                    "description": "${topic.description.replace("\"", "\\\"").replace("\n", "\\n")}",
                    "order_index": ${topic.orderIndex}
                }
            """.trimIndent()
            
            Log.d("SupabaseClient", "📝 Updating topic ${topic.id}: name='${topic.name}'")
            
            val req = buildPatchRequest(path, jsonBody)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "❌ updateTopic failed status=${resp.code}, body=${resp.body?.string()}")
                    return@withContext false
                }
                Log.d("SupabaseClient", "✅ Topic ${topic.id} updated successfully")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error updating topic ${topic.id}", e)
            return@withContext false
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
     * Fetch Usuario by email with Role in parallel for faster Google login.
     * Returns Triple(Usuario?, Rol?, Persona?) for complete session setup.
     */
    suspend fun fetchUsuarioWithRoleByEmail(email: String): Triple<Usuario?, Rol?, com.example.tareamov.data.entity.Persona?> = withContext(Dispatchers.IO) {
        try {
            // First fetch user by email efficiently with server-side filter
            val encodedEmail = java.net.URLEncoder.encode(email, "UTF-8")
            val userPath = "usuarios?email=ilike.$encodedEmail&select=*"
            val users = fetchList(userPath, Array<Usuario>::class.java)
            val user = users.firstOrNull { it.email.equals(email, ignoreCase = true) }
            
            if (user == null) {
                return@withContext Triple(null, null, null)
            }
            
            // Fetch role and persona in parallel for speed
            val rolDeferred = async { 
                if (user.rol_id > 0) fetchRolById(user.rol_id) else null 
            }
            val personaDeferred = async { 
                if (user.persona_id > 0) {
                    try {
                        val personaPath = "personas?id=eq.${user.persona_id}"
                        fetchList(personaPath, Array<com.example.tareamov.data.entity.Persona>::class.java).firstOrNull()
                    } catch (e: Exception) {
                        Log.w("SupabaseClient", "Error fetching persona: ${e.message}")
                        null
                    }
                } else null
            }
            
            val rol = rolDeferred.await()
            val persona = personaDeferred.await()
            
            Log.d("SupabaseClient", "fetchUsuarioWithRoleByEmail: user=${user.usuario}, rol=${rol?.nombre}, persona=${persona?.nombres}")
            return@withContext Triple(user, rol, persona)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error in fetchUsuarioWithRoleByEmail: ${e.message}", e)
            return@withContext Triple(null, null, null)
        }
    }

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
     * Check if user has a specific role in usuarios_roles table
     */
    suspend fun userHasRole(userId: Long, roleId: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/usuarios_roles?usuario_id=eq.$userId&rol_id=eq.$roleId"
            
            val request = Request.Builder()
                .url(url)
                .header("apikey", effectiveApiKey())
                .header("Authorization", "Bearer ${effectiveApiKey()}")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val items = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    return@withContext items.size() > 0
                } else {
                    Log.w("SupabaseClient", "Error checking user role: ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error in userHasRole", e)
            false
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
        orderBy: String = "timestamp",
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
                                                    ?: obj.get("courseId")?.takeIf { !it.isJsonNull }?.asLong
                                                val remoteId = obj.get("remote_id")?.takeIf { !it.isJsonNull }?.asLong
                                                    ?: obj.get("remoteId")?.takeIf { !it.isJsonNull }?.asLong
                                                
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
                                                    courseId = courseId,
                                                    remoteId = remoteId
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
                            ?: obj.get("courseId")?.takeIf { !it.isJsonNull }?.asLong
                        val remoteId = obj.get("remote_id")?.takeIf { !it.isJsonNull }?.asLong
                            ?: obj.get("remoteId")?.takeIf { !it.isJsonNull }?.asLong
                        
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
                            courseId = courseId,
                            remoteId = remoteId
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

    suspend fun fetchVideosPaginatedOrThrow(offset: Int, limit: Int): Pair<List<VideoData>, Int> = withContext(Dispatchers.IO) {
        val path = "videos?select=*&offset=$offset&limit=$limit&order=timestamp.desc"
        var videos = fetchListOrThrow(path, Array<VideoData>::class.java).toList()

        // Debug (requested): verify remote_id is present for a known record
        try {
            val debug = videos.firstOrNull { it.id == 98L }
            if (debug != null) {
                Log.d(
                    "SupabaseClient",
                    "fetchVideosPaginatedOrThrow typed: id=98 courseId=${debug.courseId} remoteId=${debug.remoteId} username='${debug.username}'"
                )
            }
        } catch (_: Exception) {
            // ignore
        }

        // If remoteId is missing for no-course videos, fall back to raw JSON parsing.
        // This avoids issues when the backend returns different key casing or Gson unsafe allocation drops fields.
        val missingRemoteIdForNoCourse = videos.any { it.courseId == null && (it.remoteId == null || it.remoteId == 0L) }
        if (missingRemoteIdForNoCourse) {
            Log.w("SupabaseClient", "fetchVideosPaginatedOrThrow: remoteId missing in typed mapping; trying raw JSON parse")
            try {
                val req = buildGetRequest(path)
                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string()
                    if (resp.isSuccessful && !body.isNullOrEmpty()) {
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
                                    ?: obj.get("courseId")?.takeIf { !it.isJsonNull }?.asLong
                                val remoteId = obj.get("remote_id")?.takeIf { !it.isJsonNull }?.asLong
                                    ?: obj.get("remoteId")?.takeIf { !it.isJsonNull }?.asLong

                                if (id == 98L) {
                                    Log.d(
                                        "SupabaseClient",
                                        "fetchVideosPaginatedOrThrow raw: id=98 has_remote_id=${obj.has("remote_id")} has_remoteId=${obj.has("remoteId")} remoteId=$remoteId courseId=$courseId"
                                    )
                                }
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
                                        courseId = courseId,
                                        remoteId = remoteId
                                    )
                                )
                            } catch (t: Exception) {
                                Log.w("SupabaseClient", "fetchVideosPaginatedOrThrow: failed to parse element", t)
                            }
                        }
                        if (repaired.isNotEmpty()) videos = repaired
                    }
                }
            } catch (e: Exception) {
                Log.w("SupabaseClient", "fetchVideosPaginatedOrThrow: raw JSON fallback failed", e)
            }
        }
        
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
    }

    /**
     * Fetch Videos Paginated
     */
    suspend fun fetchVideosPaginated(offset: Int, limit: Int): Pair<List<VideoData>, Int> = withContext(Dispatchers.IO) {
        try {
            val path = "videos?select=*&offset=$offset&limit=$limit&order=timestamp.desc"
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
            map["video_uri_string"] = video.videoUriString
            map["thumbnail_uri"] = video.thumbnailUri
            // map["username"] = video.username // Username should not be updated
            map["timestamp"] = video.timestamp
            map["is_paid"] = video.isPaid
            map["price"] = video.price
            
            // Include course_id if present
            if (video.courseId != null) {
                map["course_id"] = video.courseId
            }
            
            val body = gson.toJson(map).toRequestBody(jsonMedia)
            Log.d("SupabaseClient", "updateVideo payload: ${gson.toJson(map)}")
            
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
                val bodyStr = resp.body?.string()
                if (!resp.isSuccessful) {
                    Log.e("SupabaseClient", "updateVideo failed: ${resp.code} ${resp.message} body=$bodyStr")
                    return@withContext false
                }
                Log.d("SupabaseClient", "updateVideo success: $bodyStr")
                return@withContext true
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
     * Count students enrolled in a course (from progreso_estudiante table)
     */
    suspend fun countStudentsInCourse(courseId: Long): Int = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext 0
            
            val url = "$baseUrl/rest/v1/progreso_estudiante?curso_id=eq.$courseId&select=usuario_estudiante"
            
            val request = Request.Builder()
                .url(url)
                .header("apikey", effectiveApiKey())
                .header("Authorization", "Bearer ${effectiveApiKey()}")
                .get()
                .build()
            
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    return@withContext jsonArray.size()
                } else {
                    Log.w("SupabaseClient", "Error counting students: ${response.code}")
                    0
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error in countStudentsInCourse", e)
            0
        }
    }

    /**
     * Fetch TOP 5 most popular courses (courses with most students enrolled in progreso_estudiante)
     * This method queries ALL courses in the database and returns only the top 5 by enrollment count
     */
    suspend fun fetchTopPopularCourses(limit: Int = 5): List<Course> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()
            
            Log.d("SupabaseClient", "Fetching top $limit popular courses from ALL courses in database")
            
            // Step 1: Get enrollment counts grouped by curso_id from progreso_estudiante
            // We'll fetch all progreso_estudiante records and group them manually
            val progresoUrl = "$baseUrl/rest/v1/progreso_estudiante?select=curso_id"
            val progresoRequest = Request.Builder()
                .url(progresoUrl)
                .header("apikey", effectiveApiKey())
                .header("Authorization", "Bearer ${effectiveApiKey()}")
                .get()
                .build()
            
            val courseIdCounts = mutableMapOf<Long, Int>()
            
            client.newCall(progresoRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    
                    // Count occurrences of each curso_id
                    jsonArray.forEach { element ->
                        val obj = element.asJsonObject
                        val cursoId = obj.get("curso_id")?.asLong ?: return@forEach
                        courseIdCounts[cursoId] = (courseIdCounts[cursoId] ?: 0) + 1
                    }
                    
                    Log.d("SupabaseClient", "Found ${courseIdCounts.size} courses with students")
                } else {
                    Log.w("SupabaseClient", "Error fetching progreso_estudiante: ${response.code}")
                    return@withContext emptyList()
                }
            }
            
            // Step 2: Sort by count descending and take top N course IDs
            val topCourseIds = courseIdCounts.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { it.key }
            
            if (topCourseIds.isEmpty()) {
                Log.d("SupabaseClient", "No courses with students found")
                return@withContext emptyList()
            }
            
            Log.d("SupabaseClient", "Top $limit course IDs by enrollment: $topCourseIds")
            
            // Step 3: Fetch the actual Course records for these IDs
            val courseIdsFilter = topCourseIds.joinToString(",")
            val coursesUrl = "$baseUrl/rest/v1/courses?id=in.($courseIdsFilter)&select=*"
            val coursesRequest = Request.Builder()
                .url(coursesUrl)
                .header("apikey", effectiveApiKey())
                .header("Authorization", "Bearer ${effectiveApiKey()}")
                .get()
                .build()
            
            client.newCall(coursesRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val courses = gson.fromJson(body, Array<Course>::class.java).toList()
                    
                    // Sort courses by their enrollment count to maintain top-to-bottom order (most popular first)
                    // and ensure we only return exactly 'limit' courses
                    val sortedCourses = courses
                        .sortedByDescending { course -> courseIdCounts[course.id] ?: 0 }
                        .take(limit)  // Explicitly limit to requested number
                    
                    // Log the sorted order for debugging
                    sortedCourses.forEachIndexed { index, course ->
                        val count = courseIdCounts[course.id] ?: 0
                        Log.d("SupabaseClient", "Popular #${index + 1}: '${course.title}' with $count students")
                    }
                    
                    Log.d("SupabaseClient", "Returning ${sortedCourses.size} popular courses (requested: $limit), sorted from most to least popular")
                    return@withContext sortedCourses
                } else {
                    Log.w("SupabaseClient", "Error fetching courses: ${response.code}")
                    return@withContext emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error in fetchTopPopularCourses", e)
            return@withContext emptyList()
        }
    }

    /**
     * Fetch TOP popular courses with their enrollment counts.
     * Returns a list of Pair<Course, Int> where Int is the enrollment count.
     */
    suspend fun fetchTopPopularCoursesWithCounts(limit: Int = 5): List<Pair<Course, Int>> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()
            
            // Step 1: Get enrollment counts grouped by curso_id from progreso_estudiante
            val courseIdCounts = mutableMapOf<Long, Int>()
            
            // Fetch all progreso_estudiante to count (optimized: select only curso_id)
            val progresoUrl = "$baseUrl/rest/v1/progreso_estudiante?select=curso_id"
            val progresoRequest = Request.Builder()
                .url(progresoUrl)
                .header("apikey", effectiveApiKey())
                .header("Authorization", "Bearer ${effectiveApiKey()}")
                .get()
                .build()
            
            client.newCall(progresoRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    
                    jsonArray.forEach { element ->
                        val obj = element.asJsonObject
                        if (obj.has("curso_id") && !obj.get("curso_id").isJsonNull) {
                            val cursoId = obj.get("curso_id").asLong
                            courseIdCounts[cursoId] = (courseIdCounts[cursoId] ?: 0) + 1
                        }
                    }
                } else {
                    return@withContext emptyList()
                }
            }
            
            // Step 2: Sort by count descending and take top N
            val topEntries = courseIdCounts.entries
                .sortedByDescending { it.value }
                .take(limit)
            
            if (topEntries.isEmpty()) return@withContext emptyList()
            
            val topCourseIds = topEntries.map { it.key }
            
            // Step 3: Fetch Course details
            val courseIdsFilter = topCourseIds.joinToString(",")
            val coursesUrl = "$baseUrl/rest/v1/courses?id=in.($courseIdsFilter)&select=*"
            val coursesRequest = Request.Builder()
                .url(coursesUrl)
                .header("apikey", effectiveApiKey())
                .header("Authorization", "Bearer ${effectiveApiKey()}")
                .get()
                .build()
            
            client.newCall(coursesRequest).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: "[]"
                    val courses = gson.fromJson(body, Array<Course>::class.java).toList()
                    
                    // Map courses to their counts and sort again to maintain order
                    return@withContext courses
                        .map { course -> course to (courseIdCounts[course.id] ?: 0) }
                        .sortedByDescending { it.second }
                } else {
                    return@withContext emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error in fetchTopPopularCoursesWithCounts", e)
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
            val url = "$baseUrl/rest/v1/task_submissions?select=id,grade,student_id,task_id,submission_date,tasks!inner(title,topics!inner(course_id))&tasks.topics.course_id=eq.$courseId&grade=is.not.null"
            
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
     * Get like count for a video from Supabase (using polymorphic likes table)
     */
    suspend fun getVideoLikeCount(videoId: Long): Int? = withContext(Dispatchers.IO) {
        try {
            return@withContext getLikeCount("video", videoId)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error getting like count for video $videoId", e)
            null
        }
    }
    
    /**
     * Increment like count for a video (DEPRECATED - use addLike with entity_type='video')
     * Kept for backward compatibility, now uses polymorphic likes table
     */
    suspend fun incrementVideoLike(videoId: Long): Boolean = withContext(Dispatchers.IO) {
        // No-op: With polymorphic likes, the count is derived from COUNT(*)
        // This method is kept for backward compatibility but does nothing
        Log.d("SupabaseClient", "incrementVideoLike called - using polymorphic likes table (no separate counter needed)")
        true
    }
    
    /**
     * Decrement like count for a video (DEPRECATED - use removeLike with entity_type='video')
     * Kept for backward compatibility, now uses polymorphic likes table
     */
    suspend fun decrementVideoLike(videoId: Long): Boolean = withContext(Dispatchers.IO) {
        // No-op: With polymorphic likes, the count is derived from COUNT(*)
        Log.d("SupabaseClient", "decrementVideoLike called - using polymorphic likes table (no separate counter needed)")
        true
    }

    /**
     * Add a user like to a video (using polymorphic likes table)
     */
    suspend fun addUserVideoLike(videoId: Long, usuarioId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "Adding like for video $videoId by user $usuarioId")
            return@withContext addLike(usuarioId, "video", videoId)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error adding user like", e)
            false
        }
    }

    /**
     * Remove a user like from a video (using polymorphic likes table)
     */
    suspend fun removeUserVideoLike(videoId: Long, usuarioId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "Removing like for video $videoId by user $usuarioId")
            return@withContext removeLike(usuarioId, "video", videoId)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error removing user like", e)
            false
        }
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
     * Fetch all video likes from Supabase (using polymorphic likes table)
     * Returns list of Like entities with entity_type='video'
     */
    suspend fun fetchAllVideoLikes(): List<com.example.tareamov.data.entity.Like> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/likes?entity_type=eq.video&select=*"
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
                    com.example.tareamov.data.entity.Like(
                        usuarioId = obj.get("usuario_id")?.asLong ?: 0,
                        entityType = obj.get("entity_type")?.asString ?: "video",
                        entityId = obj.get("entity_id")?.asLong ?: 0,
                        createdAt = if (obj.has("created_at") && !obj.get("created_at").isJsonNull) 
                            obj.get("created_at").asString else null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching all video likes", e)
            emptyList()
        }
    }

    /**
     * Fetch all video likes for a specific user (using polymorphic likes table)
     */
    suspend fun fetchUserVideoLikes(userId: Long): List<com.example.tareamov.data.entity.Like> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/likes?usuario_id=eq.$userId&entity_type=eq.video&select=*"
            val key = effectiveApiKey()
            
            Log.d("SupabaseClient", "Fetching user video likes for userId=$userId")
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e("SupabaseClient", "Failed to fetch user likes: ${response.code}")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string() ?: return@withContext emptyList()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                Log.d("SupabaseClient", "Fetched ${jsonArray.size()} video likes from Supabase")
                
                jsonArray.map { elem ->
                    val obj = elem.asJsonObject
                    com.example.tareamov.data.entity.Like(
                        usuarioId = obj.get("usuario_id")?.asLong ?: 0,
                        entityType = obj.get("entity_type")?.asString ?: "video",
                        entityId = obj.get("entity_id")?.asLong ?: 0,
                        createdAt = if (obj.has("created_at") && !obj.get("created_at").isJsonNull) 
                            obj.get("created_at").asString else null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching user video likes", e)
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
     * Get a single comment by ID
     */
    suspend fun getVideoCommentById(commentId: Long): com.example.tareamov.data.entity.VideoComment? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/video_comments?id=eq.$commentId"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                
                val body = response.body?.string() ?: return@withContext null
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                if (jsonArray.size() == 0) return@withContext null
                
                val obj = jsonArray[0].asJsonObject
                com.example.tareamov.data.entity.VideoComment(
                    id = obj.get("id")?.asLong ?: 0,
                    videoId = obj.get("video_id")?.asLong ?: 0,
                    usuarioId = obj.get("usuario_id")?.asLong ?: 0,
                    comment = obj.get("comment")?.asString ?: "",
                    parentId = if (obj.has("parent_id") && !obj.get("parent_id").isJsonNull) obj.get("parent_id").asLong else null,
                    createdAt = if (obj.has("created_at") && !obj.get("created_at").isJsonNull) 
                        obj.get("created_at").asString else null
                )
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching comment by id $commentId", e)
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
                        createdAt = if (obj.has("created_at") && !obj.get("created_at").isJsonNull) 
                            obj.get("created_at").asString else null
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
     * Find the most recent comment_id for a specific user on a specific video
     * Used as fallback when notification metadata is null
     */
    suspend fun findCommentIdByVideoAndUsername(videoId: Long, username: String): Long? = withContext(Dispatchers.IO) {
        try {
            // First get user_id from username
            val userId = getUserIdFromUsername(username) ?: run {
                Log.w("SupabaseClient", "Could not find user_id for username: $username")
                return@withContext null
            }
            
            Log.d("SupabaseClient", "🔍 Searching for comment by userId=$userId (username=$username) on videoId=$videoId")
            
            // Query video_comments for most recent comment by this user on this video
            val url = "$baseUrl/rest/v1/video_comments?video_id=eq.$videoId&usuario_id=eq.$userId&select=id,created_at,comment&order=created_at.desc&limit=1"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "Failed to query video_comments: ${response.code}")
                    return@withContext null
                }
                
                val body = response.body?.string() ?: return@withContext null
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                if (jsonArray.size() > 0) {
                    val commentObj = jsonArray[0].asJsonObject
                    val commentId = commentObj.get("id")?.asLong
                    val commentPreview = commentObj.get("comment")?.asString?.take(50) ?: ""
                    Log.d("SupabaseClient", "✅ Found comment_id=$commentId (preview: $commentPreview)")
                    return@withContext commentId
                } else {
                    Log.w("SupabaseClient", "No comments found for userId=$userId on videoId=$videoId")
                    return@withContext null
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error finding comment_id by video and username", e)
            null
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
                        createdAt = if (obj.has("created_at") && !obj.get("created_at").isJsonNull) 
                            obj.get("created_at").asString else null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching all video comments", e)
            emptyList()
        }
    }
    
    // ========== POLYMORPHIC LIKES TABLE ==========
    // Supports likes on any entity type: video, comment, course, task, etc.
    
    /**
     * Add a like to any entity (polymorphic)
     * Returns true if like was inserted, false if already exists or error
     */
    suspend fun addLike(usuarioId: Long, entityType: String, entityId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "Adding like: user=$usuarioId, type=$entityType, id=$entityId")
            
            val payload = mapOf(
                "usuario_id" to usuarioId,
                "entity_type" to entityType,
                "entity_id" to entityId
            )
            val jsonBody = gson.toJson(payload)
            
            val url = "$baseUrl/rest/v1/likes"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody(jsonMedia))
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            
            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful || response.code == 201
                if (response.code == 409) {
                    Log.d("SupabaseClient", "Like already exists")
                    return@withContext false
                }
                Log.d("SupabaseClient", "Add like result: ${response.code}, success: $success")
                return@withContext success
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error adding like", e)
            false
        }
    }
    
    /**
     * Remove a like from any entity
     */
    suspend fun removeLike(usuarioId: Long, entityType: String, entityId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d("SupabaseClient", "Removing like: user=$usuarioId, type=$entityType, id=$entityId")
            
            val url = "$baseUrl/rest/v1/likes?usuario_id=eq.$usuarioId&entity_type=eq.$entityType&entity_id=eq.$entityId"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .build()
            
            client.newCall(request).execute().use { response ->
                val success = response.isSuccessful || response.code == 204
                Log.d("SupabaseClient", "Remove like result: ${response.code}, success: $success")
                return@withContext success
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error removing like", e)
            false
        }
    }
    
    /**
     * Toggle like on any entity
     * Returns Pair(isNowLiked, newLikeCount)
     */
    suspend fun toggleLike(usuarioId: Long, entityType: String, entityId: Long): Pair<Boolean, Int> = withContext(Dispatchers.IO) {
        try {
            val isCurrentlyLiked = hasUserLiked(usuarioId, entityType, entityId)
            
            val success = if (isCurrentlyLiked) {
                removeLike(usuarioId, entityType, entityId)
            } else {
                addLike(usuarioId, entityType, entityId)
            }
            
            if (success) {
                val newCount = getLikeCount(entityType, entityId)
                return@withContext Pair(!isCurrentlyLiked, newCount)
            }
            
            val currentCount = getLikeCount(entityType, entityId)
            return@withContext Pair(isCurrentlyLiked, currentCount)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error toggling like", e)
            Pair(false, 0)
        }
    }
    
    /**
     * Check if user has liked an entity
     */
    suspend fun hasUserLiked(usuarioId: Long, entityType: String, entityId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/likes?usuario_id=eq.$usuarioId&entity_type=eq.$entityType&entity_id=eq.$entityId&select=usuario_id"
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
                return@withContext jsonArray.size() > 0
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error checking like status", e)
            false
        }
    }
    
    /**
     * Get like count for an entity
     */
    suspend fun getLikeCount(entityType: String, entityId: Long): Int = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/likes?entity_type=eq.$entityType&entity_id=eq.$entityId&select=usuario_id"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .addHeader("Range", "0-0")
                .addHeader("Prefer", "count=exact")
                .build()
            
            client.newCall(request).execute().use { response ->
                val rangeHeader = response.header("Content-Range")
                if (rangeHeader != null && rangeHeader.contains("/")) {
                    return@withContext rangeHeader.substringAfter("/").toIntOrNull() ?: 0
                }
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext 0
                    val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                    return@withContext jsonArray.size()
                }
                0
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error getting like count", e)
            0
        }
    }
    
    /**
     * Get all likes for an entity
     */
    suspend fun getLikesForEntity(entityType: String, entityId: Long): List<com.example.tareamov.data.entity.Like> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/likes?entity_type=eq.$entityType&entity_id=eq.$entityId&select=*"
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
                    com.example.tareamov.data.entity.Like(
                        usuarioId = obj.get("usuario_id")?.asLong ?: 0,
                        entityType = obj.get("entity_type")?.asString ?: "",
                        entityId = obj.get("entity_id")?.asLong ?: 0,
                        createdAt = if (obj.has("created_at") && !obj.get("created_at").isJsonNull) 
                            obj.get("created_at").asString else null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching likes for entity", e)
            emptyList()
        }
    }
    
    /**
     * Fetch all likes (for syncing)
     */
    suspend fun fetchAllLikes(): List<com.example.tareamov.data.entity.Like> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/rest/v1/likes?select=*"
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
                    com.example.tareamov.data.entity.Like(
                        usuarioId = obj.get("usuario_id")?.asLong ?: 0,
                        entityType = obj.get("entity_type")?.asString ?: "",
                        entityId = obj.get("entity_id")?.asLong ?: 0,
                        createdAt = if (obj.has("created_at") && !obj.get("created_at").isJsonNull) 
                            obj.get("created_at").asString else null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching all likes", e)
            emptyList()
        }
    }
    
    /**
     * Get like counts for multiple entities of the same type (batch)
     */
    suspend fun getLikeCounts(entityType: String, entityIds: List<Long>): Map<Long, Int> = withContext(Dispatchers.IO) {
        if (entityIds.isEmpty()) return@withContext emptyMap()
        
        try {
            val idsFilter = entityIds.joinToString(",")
            val url = "$baseUrl/rest/v1/likes?entity_type=eq.$entityType&entity_id=in.($idsFilter)&select=entity_id"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext entityIds.associateWith { 0 }
                val body = response.body?.string() ?: return@withContext entityIds.associateWith { 0 }
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                val likeCounts = mutableMapOf<Long, Int>()
                jsonArray.forEach { elem ->
                    val entityId = elem.asJsonObject.get("entity_id")?.asLong ?: return@forEach
                    likeCounts[entityId] = (likeCounts[entityId] ?: 0) + 1
                }
                
                return@withContext entityIds.associateWith { likeCounts[it] ?: 0 }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error getting batch like counts", e)
            entityIds.associateWith { 0 }
        }
    }
    
    /**
     * Get which entities the user has liked from a list
     */
    suspend fun getUserLikedEntityIds(usuarioId: Long, entityType: String, entityIds: List<Long>): Set<Long> = withContext(Dispatchers.IO) {
        if (entityIds.isEmpty()) return@withContext emptySet()
        
        try {
            val idsFilter = entityIds.joinToString(",")
            val url = "$baseUrl/rest/v1/likes?usuario_id=eq.$usuarioId&entity_type=eq.$entityType&entity_id=in.($idsFilter)&select=entity_id"
            val key = effectiveApiKey()
            
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", key)
                .addHeader("Authorization", "Bearer $key")
                .addHeader("Accept", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptySet()
                val body = response.body?.string() ?: return@withContext emptySet()
                val jsonArray = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                jsonArray.mapNotNull { elem ->
                    elem.asJsonObject.get("entity_id")?.asLong
                }.toSet()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error getting user's liked entities", e)
            emptySet()
        }
    }
    
    // ========== CONVENIENCE METHODS FOR SPECIFIC ENTITY TYPES ==========
    
    // Video Comment Likes
    suspend fun likeVideoComment(commentId: Long, usuarioId: Long) = addLike(usuarioId, "comment", commentId)
    suspend fun unlikeVideoComment(commentId: Long, usuarioId: Long) = removeLike(usuarioId, "comment", commentId)
    suspend fun toggleVideoCommentLike(commentId: Long, usuarioId: Long) = toggleLike(usuarioId, "comment", commentId)
    suspend fun hasUserLikedVideoComment(commentId: Long, usuarioId: Long) = hasUserLiked(usuarioId, "comment", commentId)
    suspend fun getVideoCommentLikeCount(commentId: Long) = getLikeCount("comment", commentId)
    suspend fun getVideoCommentLikeCounts(commentIds: List<Long>) = getLikeCounts("comment", commentIds)
    suspend fun getUserLikedComments(usuarioId: Long, commentIds: List<Long>) = getUserLikedEntityIds(usuarioId, "comment", commentIds)
    
    // Video Likes (polymorphic - can replace video_likes table)
    suspend fun likeVideo(videoId: Long, usuarioId: Long) = addLike(usuarioId, "video", videoId)
    suspend fun unlikeVideo(videoId: Long, usuarioId: Long) = removeLike(usuarioId, "video", videoId)
    suspend fun toggleVideoLike(videoId: Long, usuarioId: Long) = toggleLike(usuarioId, "video", videoId)
    suspend fun hasUserLikedVideo(videoId: Long, usuarioId: Long) = hasUserLiked(usuarioId, "video", videoId)
    suspend fun getVideoLikeCountPolymorphic(videoId: Long) = getLikeCount("video", videoId)
    
    // Course Likes
    suspend fun likeCourse(courseId: Long, usuarioId: Long) = addLike(usuarioId, "course", courseId)
    suspend fun unlikeCourse(courseId: Long, usuarioId: Long) = removeLike(usuarioId, "course", courseId)
    suspend fun toggleCourseLike(courseId: Long, usuarioId: Long) = toggleLike(usuarioId, "course", courseId)
    suspend fun hasUserLikedCourse(courseId: Long, usuarioId: Long) = hasUserLiked(usuarioId, "course", courseId)
    suspend fun getCourseLikeCount(courseId: Long) = getLikeCount("course", courseId)
    
    // Task Likes
    suspend fun likeTask(taskId: Long, usuarioId: Long) = addLike(usuarioId, "task", taskId)
    suspend fun unlikeTask(taskId: Long, usuarioId: Long) = removeLike(usuarioId, "task", taskId)
    suspend fun toggleTaskLike(taskId: Long, usuarioId: Long) = toggleLike(usuarioId, "task", taskId)
    suspend fun hasUserLikedTask(taskId: Long, usuarioId: Long) = hasUserLiked(usuarioId, "task", taskId)
    suspend fun getTaskLikeCount(taskId: Long) = getLikeCount("task", taskId)
    
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
     * Fetch a single page of courses using limit/offset (server-side pagination)
     */
    suspend fun fetchCoursesPage(limit: Int, offset: Int): List<Course> = withContext(Dispatchers.IO) {
        try {
            val path = "courses?select=*&order=timestamp.desc&limit=$limit&offset=$offset"
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w("SupabaseClient", "fetchCoursesPage failed status=${resp.code}")
                    return@withContext emptyList()
                }
                val body = resp.body?.string() ?: return@withContext emptyList()
                val arr = underscoredGson.fromJson(body, Array<Course>::class.java)
                return@withContext arr?.toList() ?: emptyList()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "fetchCoursesPage exception", e)
            emptyList()
        }
    }

    /**
     * Get total count of courses using Prefer: count=exact and Content-Range header
     */
    suspend fun fetchCoursesCount(): Int = withContext(Dispatchers.IO) {
        try {
            val path = "courses?select=id"
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/$path")
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Prefer", "count=exact")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "fetchCoursesCount failed: ${response.code}")
                    return@withContext 0
                }
                val contentRange = response.header("Content-Range")
                if (contentRange != null) {
                    return@withContext contentRange.substringAfter("/").toIntOrNull() ?: 0
                }
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val arr = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    return@withContext arr.size()
                }
                0
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching courses count", e)
            0
        }
    }

    /** Count popular courses (fallback: count published courses) server-side */
    suspend fun countPopularCourses(): Int = withContext(Dispatchers.IO) {
        try {
            // Use a safe query that exists on the current DB schema
            val path = "courses?is_published=eq.true&select=id"
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/$path")
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Prefer", "count=exact")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0
                val cr = response.header("Content-Range")
                if (cr != null) return@withContext cr.substringAfter("/").toIntOrNull() ?: 0
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val arr = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    return@withContext arr.size()
                }
                0
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching popular courses count", e)
            0
        }
    }

    /** Count courses created in the last `days` days server-side */
    suspend fun countNewCourses(days: Int = 30): Int = withContext(Dispatchers.IO) {
        try {
            val threshold = System.currentTimeMillis() - days * 24L * 60L * 60L * 1000L
            val path = "courses?timestamp=gte.$threshold&select=id"
            val request = Request.Builder()
                .url("$baseUrl/rest/v1/$path")
                .get()
                .addHeader("apikey", effectiveApiKey())
                .addHeader("Authorization", "Bearer ${effectiveApiKey()}")
                .addHeader("Prefer", "count=exact")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext 0
                val cr = response.header("Content-Range")
                if (cr != null) return@withContext cr.substringAfter("/").toIntOrNull() ?: 0
                val body = response.body?.string()
                if (!body.isNullOrEmpty()) {
                    val arr = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                    return@withContext arr.size()
                }
                0
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "countNewCourses error", e)
            0
        }
    }

    /**
     * Count unique students enrolled in a list of courses.
     * Returns the number of distinct usuario_estudiante values in progreso_estudiante
     * for the given course IDs.
     */
    suspend fun countUniqueStudentsInCourses(courseIds: List<Long>): Int = withContext(Dispatchers.IO) {
        try {
            if (courseIds.isEmpty()) return@withContext 0
            
            // Build the IN filter for course IDs
            val courseIdsStr = courseIds.joinToString(",") { it.toString() }
            val path = "progreso_estudiante?curso_id=in.($courseIdsStr)&select=usuario_estudiante"
            
            val request = buildGetRequest(path)
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "countUniqueStudentsInCourses failed: ${response.code}")
                    return@withContext 0
                }
                
                val body = response.body?.string()
                if (body.isNullOrEmpty()) return@withContext 0
                
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                
                // Extract unique student IDs
                val uniqueStudents = mutableSetOf<Long>()
                jsonArray.forEach { element ->
                    val obj = element.asJsonObject
                    if (obj.has("usuario_estudiante") && !obj.get("usuario_estudiante").isJsonNull) {
                        uniqueStudents.add(obj.get("usuario_estudiante").asLong)
                    }
                }
                
                Log.d("SupabaseClient", "Found ${uniqueStudents.size} unique students in ${courseIds.size} courses")
                return@withContext uniqueStudents.size
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error counting unique students in courses", e)
            0
        }
    }

    /**
     * Get top students by average grade across ALL their enrolled courses.
     * First finds students enrolled in the creator's courses, then calculates their overall average.
     * Returns a list of students ordered by their global average grade (descending).
     */
    suspend fun fetchTopStudentsByProgress(courseIds: List<Long>, limit: Int = 5): List<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            if (courseIds.isEmpty()) {
                Log.d("SupabaseClient", "No course IDs provided for top students")
                return@withContext emptyList()
            }
            
            Log.d("SupabaseClient", "Fetching top students for courses: $courseIds")
            
            // Step 1: Get unique students enrolled in the creator's courses
            val courseIdsStr = courseIds.joinToString(",") { it.toString() }
            val studentsPath = "progreso_estudiante?curso_id=in.($courseIdsStr)&select=usuario_estudiante"
            
            Log.d("SupabaseClient", "Students query path: $studentsPath")
            
            val studentsRequest = buildGetRequest(studentsPath)
            val uniqueStudentIds = mutableSetOf<Long>()
            
            client.newCall(studentsRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "Failed to fetch students: ${response.code} - ${response.message}")
                    val errorBody = response.body?.string()
                    Log.w("SupabaseClient", "Error body: $errorBody")
                    return@withContext emptyList()
                }
                
                val body = response.body?.string()
                Log.d("SupabaseClient", "Students response body: $body")
                
                if (body.isNullOrEmpty()) {
                    Log.d("SupabaseClient", "No students found in creator's courses - empty response")
                    return@withContext emptyList()
                }
                
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                Log.d("SupabaseClient", "Found ${jsonArray.size()} progreso_estudiante records")
                
                jsonArray.forEach { element ->
                    val obj = element.asJsonObject
                    if (obj.has("usuario_estudiante") && !obj.get("usuario_estudiante").isJsonNull) {
                        val studentId = obj.get("usuario_estudiante").asLong
                        uniqueStudentIds.add(studentId)
                        Log.d("SupabaseClient", "Added student ID: $studentId")
                    }
                }
            }
            
            if (uniqueStudentIds.isEmpty()) {
                Log.d("SupabaseClient", "No unique students found after processing")
                return@withContext emptyList()
            }
            
            Log.d("SupabaseClient", "Found ${uniqueStudentIds.size} unique students: $uniqueStudentIds")
            
            // Step 2: For each student, get ALL their course grades to calculate global average
            val studentGlobalStats = mutableListOf<Map<String, Any>>()
            
            for (studentId in uniqueStudentIds) {
                try {
                    val allCoursesPath = "progreso_estudiante?usuario_estudiante=eq.$studentId&select=calificacion_promedio"
                    val gradesRequest = buildGetRequest(allCoursesPath)
                    
                    Log.d("SupabaseClient", "Fetching all grades for student $studentId")
                    
                    client.newCall(gradesRequest).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w("SupabaseClient", "Failed to fetch grades for student $studentId: ${response.code}")
                            return@use
                        }
                        
                        val body = response.body?.string()
                        if (body.isNullOrEmpty()) {
                            Log.w("SupabaseClient", "No grades found for student $studentId")
                            return@use
                        }
                        
                        val gradesArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                        val allGrades = mutableListOf<Double>()
                        
                        gradesArray.forEach { element ->
                            val obj = element.asJsonObject
                            if (obj.has("calificacion_promedio") && !obj.get("calificacion_promedio").isJsonNull) {
                                val grade = obj.get("calificacion_promedio").asDouble
                                allGrades.add(grade)
                                Log.d("SupabaseClient", "Student $studentId - Grade: $grade")
                            }
                        }
                        
                        if (allGrades.isNotEmpty()) {
                            val globalAverage = allGrades.average()
                            val username = getUsernameFromUserId(studentId) ?: "Usuario $studentId"
                            
                            Log.d("SupabaseClient", "Student $studentId ($username) - Average: $globalAverage from ${allGrades.size} courses")
                            
                            studentGlobalStats.add(
                                mapOf(
                                    "userId" to studentId,
                                    "username" to username,
                                    "averageProgress" to globalAverage,
                                    "coursesEnrolled" to allGrades.size
                                )
                            )
                        } else {
                            Log.w("SupabaseClient", "Student $studentId has no valid grades")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseClient", "Error fetching grades for student $studentId", e)
                }
            }
            
            // Step 3: Sort by global average and take top N
            val topStudents = studentGlobalStats
                .sortedByDescending { it["averageProgress"] as Double }
                .take(limit)
            
            Log.d("SupabaseClient", "Top ${topStudents.size} students by global average grade:")
            topStudents.forEachIndexed { index, student ->
                Log.d("SupabaseClient", "#${index + 1}: ${student["username"]} - ${student["averageProgress"]} (${student["coursesEnrolled"]} courses)")
            }
            
            return@withContext topStudents
            
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching top students by progress", e)
            emptyList()
        }
    }

    /**
     * Debug function to check what data exists in progreso_estudiante table
     * This helps identify if the table has data and what fields are available
     */
    suspend fun debugProgresoEstudiante(): String = withContext(Dispatchers.IO) {
        try {
            val path = "progreso_estudiante?limit=10"
            val request = buildGetRequest(path)
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext "Error: ${response.code} - ${response.message}"
                }
                
                val body = response.body?.string()
                if (body.isNullOrEmpty()) {
                    return@withContext "Tabla vacía - No hay registros en progreso_estudiante"
                }
                
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                val result = StringBuilder()
                result.append("Registros encontrados: ${jsonArray.size()}\n\n")
                
                jsonArray.take(5).forEachIndexed { index, element ->
                    val obj = element.asJsonObject
                    result.append("Registro #${index + 1}:\n")
                    result.append("  - usuario_estudiante: ${obj.get("usuario_estudiante")}\n")
                    result.append("  - curso_id: ${obj.get("curso_id")}\n")
                    result.append("  - calificacion_promedio: ${obj.get("calificacion_promedio")}\n")
                    result.append("  - porcentaje_completado: ${obj.get("porcentaje_completado")}\n")
                    result.append("\n")
                }
                
                return@withContext result.toString()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error debugging progreso_estudiante", e)
            "Error al consultar: ${e.message}"
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

            val map = mutableMapOf<String, Any?>(
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
            
            // Include metadata if present
            if (notification.metadata != null) {
                map["metadata"] = notification.metadata
                Log.d("SupabaseClient", "📦 Including metadata in notification: ${notification.metadata}")
            }

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
     * Combined method: Notify subscribers using the Backend Endpoint (Email + In-App + Push)
     */
    suspend fun notifySubscribersOfNewCourseWithPush(
        creatorUserId: Long,
        creatorUsername: String,
        creatorAvatarUrl: String?,
        courseId: Long,
        courseTitle: String,
        courseThumbnailUrl: String?
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        try {
            val jsonBody = com.google.gson.JsonObject().apply {
                addProperty("creatorUserId", creatorUserId)
                addProperty("creatorUsername", creatorUsername)
                addProperty("courseId", courseId)
                addProperty("courseTitle", courseTitle)
                addProperty("courseThumbnailUrl", courseThumbnailUrl)
            }

            // Use the Backend URL for notifications (PaymentApi.BASE_URL points to the backend)
            val backendUrl = "${com.example.tareamov.network.PaymentApi.BASE_URL}notify-course-creation"
            
            Log.d("SupabaseClient", "Sending notification request to: $backendUrl")

            val request = Request.Builder()
                .url(backendUrl) 
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.d("SupabaseClient", "✅ Backend notification successful")
                    // Backend returns success count, but for client compatibility we return dummy positive values
                    // or parse response if needed.
                    return@withContext Pair(1, 1) 
                } else {
                    Log.e("SupabaseClient", "❌ Backend notification failed: ${response.code} ${response.message}")
                    return@withContext Pair(0, 0)
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error calling notify-course-creation backend", e)
            Pair(0, 0)
        }
    }

    suspend fun fetchAverageGradeForCreator(creatorId: Long): Float = withContext(Dispatchers.IO) {
        try {
            // task_submissions -> tasks -> topics -> courses -> creator_id
            // We select only the grade.
            val path = "task_submissions?select=grade,tasks!inner(topics!inner(courses!inner(creator_id)))&tasks.topics.courses.creator_id=eq.$creatorId&grade=not.is.null"
            
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0f
                val body = resp.body?.string() ?: return@withContext 0f
                val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                if (arr.size() == 0) return@withContext 0f
                
                var totalGrade = 0.0
                var count = 0
                for (i in 0 until arr.size()) {
                    val obj = arr.get(i).asJsonObject
                    if (obj.has("grade") && !obj.get("grade").isJsonNull) {
                        totalGrade += obj.get("grade").asDouble
                        count++
                    }
                }
                
                if (count == 0) 0f else (totalGrade / count).toFloat()
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching average grade for creator", e)
            0f
        }
    }

    // Fetch total unique students enrolled in courses created by a specific creator
    suspend fun fetchTotalStudentsForCreator(creatorId: Long): Int = withContext(Dispatchers.IO) {
        try {
            // progreso_estudiante -> courses -> creator_id
            // We want to count unique usuario_id
            val path = "progreso_estudiante?select=usuario_id,courses!inner(creator_id)&courses.creator_id=eq.$creatorId"
            
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext 0
                val body = resp.body?.string() ?: return@withContext 0
                val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                val uniqueStudents = mutableSetOf<Long>()
                for (i in 0 until arr.size()) {
                    val obj = arr.get(i).asJsonObject
                    if (obj.has("usuario_id") && !obj.get("usuario_id").isJsonNull) {
                        uniqueStudents.add(obj.get("usuario_id").asLong)
                    }
                }
                uniqueStudents.size
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching total students for creator", e)
            0
        }
    }

    // Fetch completion stats for a creator: (Total Enrollments, Completed Enrollments)
    // Completed means estado = 'Ganado' (or 'Completado')
    suspend fun fetchCreatorCompletionStats(creatorId: Long): Pair<Int, Int> = withContext(Dispatchers.IO) {
        try {
            val path = "progreso_estudiante?select=estado,courses!inner(creator_id)&courses.creator_id=eq.$creatorId"
            
            val req = buildGetRequest(path)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Pair(0, 0)
                val body = resp.body?.string() ?: return@withContext Pair(0, 0)
                val arr = com.google.gson.JsonParser.parseString(body).asJsonArray
                
                var total = 0
                var completed = 0
                
                for (i in 0 until arr.size()) {
                    val obj = arr.get(i).asJsonObject
                    total++
                    if (obj.has("estado") && !obj.get("estado").isJsonNull) {
                        val estado = obj.get("estado").asString
                        if (estado.equals("Ganado", ignoreCase = true) || estado.equals("Completado", ignoreCase = true)) {
                            completed++
                        }
                    }
                }
                Pair(total, completed)
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching completion stats for creator", e)
            Pair(0, 0)
        }
    }

    // ==================== ANALYTICS HELPERS ====================

    suspend fun fetchUserCount(): Int = withContext(Dispatchers.IO) {
        try {
            fetchTableJson("usuarios").size()
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d("SupabaseClient", "User count fetch cancelled")
            throw e
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Error fetching user count: ${e.message}")
            0
        }
    }

    suspend fun fetchCourseCount(publishedOnly: Boolean = false): Int = withContext(Dispatchers.IO) {
        try {
            val courses = fetchTableJson("courses")
            if (publishedOnly) {
                var count = 0
                for (i in 0 until courses.size()) {
                    val course = courses.get(i).asJsonObject
                    if (course.has("is_published") && !course.get("is_published").isJsonNull && course.get("is_published").asBoolean) {
                        count++
                    }
                }
                count
            } else {
                courses.size()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d("SupabaseClient", "Course count fetch cancelled")
            throw e
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Error fetching course count: ${e.message}")
            0
        }
    }

    suspend fun fetchSubmissionCount(): Int = withContext(Dispatchers.IO) {
        try {
            fetchTableJson("task_submissions").size()
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d("SupabaseClient", "Submission count fetch cancelled")
            throw e
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Error fetching submission count: ${e.message}")
            0
        }
    }

    suspend fun fetchCertificatesIssuedCount(): Int = withContext(Dispatchers.IO) {
        try {
            val progress = fetchTableJson("progreso_estudiante")
            var count = 0
            for (i in 0 until progress.size()) {
                val p = progress.get(i).asJsonObject
                // Check if certificate_url is present and not null/empty, or status is 'Ganado'
                val hasCert = (p.has("certificado_url") && !p.get("certificado_url").isJsonNull && p.get("certificado_url").asString.isNotEmpty())
                val isWon = (p.has("estado") && !p.get("estado").isJsonNull && p.get("estado").asString.equals("Ganado", ignoreCase = true))
                
                if (hasCert || isWon) {
                    count++
                }
            }
            count
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d("SupabaseClient", "Certificates count fetch cancelled")
            throw e
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Error fetching certificates count: ${e.message}")
            0
        }
    }

    data class TopCreator(
        val id: Long,
        val username: String,
        val avatarUrl: String?,
        val certifications: Int,
        val subscribers: Int,
        val coursesCount: Int = 0
    )

    suspend fun fetchTopCreators(limit: Int = 5): List<TopCreator> = withContext(Dispatchers.IO) {
        try {
            // Run fetches in parallel
            val subscriptionsDeferred = async { 
                // Fetch only creator_id
                val json = fetchTableJson("subscriptions?select=creator_id") 
                // Count subscribers per creator
                val counts = mutableMapOf<Long, Int>()
                for (i in 0 until json.size()) {
                    val obj = json.get(i).asJsonObject
                    if (obj.has("creator_id") && !obj.get("creator_id").isJsonNull) {
                        val creatorId = obj.get("creator_id").asLong
                        counts[creatorId] = counts.getOrDefault(creatorId, 0) + 1
                    }
                }
                counts
            }

            val certificationsDeferred = async {
                // Fetch curso_id where certificate is likely issued
                val json = fetchTableJson("progreso_estudiante?select=curso_id,certificado_url,estado")
                val courseCounts = mutableMapOf<Long, Int>()
                for (i in 0 until json.size()) {
                    val obj = json.get(i).asJsonObject
                    val hasCert = (obj.has("certificado_url") && !obj.get("certificado_url").isJsonNull && obj.get("certificado_url").asString.isNotEmpty())
                    val isWon = (obj.has("estado") && !obj.get("estado").isJsonNull && obj.get("estado").asString.equals("Ganado", ignoreCase = true))
                    if (hasCert || isWon) {
                        if (obj.has("curso_id") && !obj.get("curso_id").isJsonNull) {
                            val courseId = obj.get("curso_id").asLong
                            courseCounts[courseId] = courseCounts.getOrDefault(courseId, 0) + 1
                        }
                    }
                }
                courseCounts
            }

            val coursesDeferred = async {
                // Map course_id to creator_id and count courses per creator
                val json = fetchTableJson("courses?select=id,creator_user_id")
                val courseToCreator = mutableMapOf<Long, Long>() // courseId -> creatorId
                val coursesCountByCreator = mutableMapOf<Long, Int>() // creatorId -> course count
                for (i in 0 until json.size()) {
                    val obj = json.get(i).asJsonObject
                    if (obj.has("id") && obj.has("creator_user_id") && !obj.get("creator_user_id").isJsonNull) {
                        val courseId = obj.get("id").asLong
                        val creatorId = obj.get("creator_user_id").asLong
                        courseToCreator[courseId] = creatorId
                        coursesCountByCreator[creatorId] = coursesCountByCreator.getOrDefault(creatorId, 0) + 1
                    }
                }
                Pair(courseToCreator, coursesCountByCreator)
            }

            val usersDeferred = async {
                 fetchTableJson("usuarios?select=id,username,avatar")
            }

            val subCounts = subscriptionsDeferred.await()
            val certCountsByCourse = certificationsDeferred.await()
            val (courseToCreator, coursesCountByCreator) = coursesDeferred.await()
            val usersJson = usersDeferred.await()

            // Aggregate certifications by creator
            val certCountsByCreator = mutableMapOf<Long, Int>()
            certCountsByCourse.forEach { (courseId, count) ->
                val creatorId = courseToCreator[courseId]
                if (creatorId != null) {
                    certCountsByCreator[creatorId] = certCountsByCreator.getOrDefault(creatorId, 0) + count
                }
            }

            // Combine metrics
            val creators = mutableListOf<TopCreator>()
            for (i in 0 until usersJson.size()) {
                val obj = usersJson.get(i).asJsonObject
                val id = obj.get("id").asLong
                val username = if (obj.has("username") && !obj.get("username").isJsonNull) obj.get("username").asString else "Unknown"
                val avatar = if (obj.has("avatar") && !obj.get("avatar").isJsonNull) obj.get("avatar").asString else null
                
                val coursesCount = coursesCountByCreator.getOrDefault(id, 0)
                val subs = subCounts.getOrDefault(id, 0)
                val certs = certCountsByCreator.getOrDefault(id, 0)
                
                // Solo incluir creadores con al menos 1 curso
                if (coursesCount > 0) {
                    creators.add(TopCreator(id, username, avatar, certs, subs, coursesCount))
                }
            }

            // Sort by certifications (primary) and subscribers (secondary)
            creators.sortedWith(compareByDescending<TopCreator> { it.certifications }.thenByDescending { it.subscribers })
                .take(limit)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching top creators", e)
            emptyList()
        }
    }

    /**
     * Fetch the count of certifications (students who have completed) for a specific course.
     * Returns the number of students who have earned certificates in the given course.
     */
    suspend fun fetchCertificationsForCourse(courseId: Long): Int = withContext(Dispatchers.IO) {
        try {
            val json = fetchTableJson("progreso_estudiante?curso_id=eq.$courseId&select=certificado_url,estado")
            var count = 0
            for (i in 0 until json.size()) {
                val obj = json.get(i).asJsonObject
                val hasCert = (obj.has("certificado_url") && !obj.get("certificado_url").isJsonNull && obj.get("certificado_url").asString.isNotEmpty())
                val isWon = (obj.has("estado") && !obj.get("estado").isJsonNull && obj.get("estado").asString.equals("Ganado", ignoreCase = true))
                if (hasCert || isWon) {
                    count++
                }
            }
            count
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Error fetching certifications for course $courseId: ${e.message}")
            0
        }
    }

    /**
     * Fetch the count of submissions for a specific course.
     * Returns the total number of task submissions for the given course.
     */
    suspend fun fetchSubmissionCountForCourse(courseId: Long): Int = withContext(Dispatchers.IO) {
        try {
            // First, get all topics for this course
            val topics = fetchTopicsByCourse(courseId)
            val topicIds = topics.map { it.id }
            
            if (topicIds.isEmpty()) {
                return@withContext 0
            }
            
            // Then get all tasks for these topics
            val tasks = fetchTasksByTopicIds(topicIds)
            val taskIds = tasks.map { it.id }
            
            if (taskIds.isEmpty()) {
                return@withContext 0
            }
            
            // Finally, count submissions for these tasks
            val taskIdsStr = taskIds.joinToString(",")
            val json = fetchTableJson("task_submissions?task_id=in.($taskIdsStr)&select=id")
            json.size()
        } catch (e: Exception) {
            Log.w("SupabaseClient", "Error fetching submission count for course $courseId: ${e.message}")
            0
        }
    }

    /**
     * OPTIMIZACIÓN: Obtener todas las métricas de cursos en una sola operación paralela
     * Retorna un mapa de courseId a métricas (enrollments, certifications, submissions)
     */
    data class CourseMetrics(
        val enrollments: Int,
        val certifications: Int,
        val submissions: Int,
        val uniqueUsers: Int = 0 // Usuarios únicos inscritos
    )

    suspend fun fetchCourseMetricsBatch(courseIds: List<Long>): Map<Long, CourseMetrics> = withContext(Dispatchers.IO) {
        if (courseIds.isEmpty()) return@withContext emptyMap()
        
        try {
            // Usar async para paralelizar todas las llamadas
            val metricsMap = mutableMapOf<Long, CourseMetrics>()
            
            // Procesar todos los cursos en paralelo
            val jobs = courseIds.map { courseId ->
                async {
                    try {
                        val enrollments = async { fetchEnrolledCount(courseId).toInt() }
                        val certifications = async { fetchCertificationsForCourse(courseId) }
                        val submissions = async { fetchSubmissionCountForCourse(courseId) }
                        
                        courseId to CourseMetrics(
                            enrollments = enrollments.await(),
                            certifications = certifications.await(),
                            submissions = submissions.await()
                        )
                    } catch (e: Exception) {
                        Log.w("SupabaseClient", "Error fetching metrics for course $courseId", e)
                        courseId to CourseMetrics(0, 0, 0)
                    }
                }
            }
            
            jobs.awaitAll().forEach { (courseId, metrics) ->
                metricsMap[courseId] = metrics
            }
            
            Log.d("SupabaseClient", "Fetched metrics for ${metricsMap.size} courses in batch")
            metricsMap
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error in fetchCourseMetricsBatch", e)
            emptyMap()
        }
    }

    /**
     * OPTIMIZACIÓN: Obtener métricas agregadas de múltiples cursos de forma eficiente
     */
    suspend fun fetchAggregatedMetrics(courseIds: List<Long>): CourseMetrics = withContext(Dispatchers.IO) {
        if (courseIds.isEmpty()) return@withContext CourseMetrics(0, 0, 0, 0)
        
        try {
            val batchMetrics = fetchCourseMetricsBatch(courseIds)
            
            val totalEnrollments = batchMetrics.values.sumOf { it.enrollments }
            val totalCertifications = batchMetrics.values.sumOf { it.certifications }
            val totalSubmissions = batchMetrics.values.sumOf { it.submissions }
            
            // Obtener usuarios únicos inscritos en cualquiera de los cursos
            val uniqueUsers = try {
                fetchUniqueEnrolledUsers(courseIds)
            } catch (e: Exception) {
                Log.w("SupabaseClient", "Error fetching unique users, falling back to enrollments", e)
                totalEnrollments // Fallback
            }
            
            CourseMetrics(totalEnrollments, totalCertifications, totalSubmissions, uniqueUsers)
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error in fetchAggregatedMetrics", e)
            CourseMetrics(0, 0, 0, 0)
        }
    }
    
    /**
     * Obtiene la cantidad de usuarios únicos inscritos en los cursos especificados
     * usando la tabla progreso_estudiante
     */
    private data class SubscriptionUserId(val user_id: Long)
    
    suspend fun fetchUniqueEnrolledUsers(courseIds: List<Long>): Int = withContext(Dispatchers.IO) {
        if (courseIds.isEmpty()) return@withContext 0
        
        try {
            // Usar la tabla progreso_estudiante para contar usuarios únicos inscritos
            val courseIdsStr = courseIds.joinToString(",") { it.toString() }
            val path = "progreso_estudiante?curso_id=in.($courseIdsStr)&select=usuario_estudiante"
            
            val request = buildGetRequest(path)
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("SupabaseClient", "fetchUniqueEnrolledUsers failed: ${response.code}")
                    return@withContext 0
                }
                
                val body = response.body?.string()
                if (body.isNullOrEmpty()) return@withContext 0
                
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                
                // Extract unique student IDs
                val uniqueStudents = mutableSetOf<Long>()
                jsonArray.forEach { element ->
                    val obj = element.asJsonObject
                    if (obj.has("usuario_estudiante") && !obj.get("usuario_estudiante").isJsonNull) {
                        uniqueStudents.add(obj.get("usuario_estudiante").asLong)
                    }
                }
                
                Log.d("SupabaseClient", "Found ${uniqueStudents.size} unique users enrolled in ${courseIds.size} courses from progreso_estudiante")
                return@withContext uniqueStudents.size
            }
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error in fetchUniqueEnrolledUsers", e)
            0
        }
    }

    /**
     * Fetch total count of courses with successful/APPROVED transactions
     */
    suspend fun fetchPurchasedCoursesCount(): Long = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext 0L
            
            val uniqueCourseIds = mutableSetOf<Long>()
            
            // Check 'successful' status
            val requestSuccessful = buildGetRequest("transactions?status=eq.successful&select=course_id")
            val responseSuccessful = client.newCall(requestSuccessful).execute()
            if (responseSuccessful.isSuccessful) {
                val responseBody = responseSuccessful.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val jsonArray = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                    jsonArray.forEach { element ->
                        val jsonObject = element.asJsonObject
                        val courseId = jsonObject.get("course_id")?.asLong
                        if (courseId != null) {
                            uniqueCourseIds.add(courseId)
                        }
                    }
                }
            }
            
            // Check 'APPROVED' status (Wompi returns uppercase)
            val requestApproved = buildGetRequest("transactions?status=eq.APPROVED&select=course_id")
            val responseApproved = client.newCall(requestApproved).execute()
            if (responseApproved.isSuccessful) {
                val responseBody = responseApproved.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val jsonArray = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                    jsonArray.forEach { element ->
                        val jsonObject = element.asJsonObject
                        val courseId = jsonObject.get("course_id")?.asLong
                        if (courseId != null) {
                            uniqueCourseIds.add(courseId)
                        }
                    }
                }
            }
            
            Log.d("SupabaseClient", "Found ${uniqueCourseIds.size} courses with successful/APPROVED transactions")
            return@withContext uniqueCourseIds.size.toLong()
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching purchased courses count", e)
            0L
        }
    }
    
    /**
     * Fetch courses that have successful/APPROVED transactions (purchased courses)
     */
    suspend fun fetchPurchasedCourses(): List<Course> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()
            
            val uniqueCourseIds = mutableSetOf<Long>()
            
            // Get course IDs from 'successful' transactions
            val requestSuccessful = buildGetRequest("transactions?status=eq.successful&select=course_id")
            val responseSuccessful = client.newCall(requestSuccessful).execute()
            if (responseSuccessful.isSuccessful) {
                val responseBody = responseSuccessful.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val jsonArray = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                    jsonArray.forEach { element ->
                        val jsonObject = element.asJsonObject
                        val courseId = jsonObject.get("course_id")?.asLong
                        if (courseId != null) {
                            uniqueCourseIds.add(courseId)
                        }
                    }
                }
            }
            
            // Get course IDs from 'APPROVED' transactions (Wompi returns uppercase)
            val requestApproved = buildGetRequest("transactions?status=eq.APPROVED&select=course_id")
            val responseApproved = client.newCall(requestApproved).execute()
            if (responseApproved.isSuccessful) {
                val responseBody = responseApproved.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val jsonArray = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                    jsonArray.forEach { element ->
                        val jsonObject = element.asJsonObject
                        val courseId = jsonObject.get("course_id")?.asLong
                        if (courseId != null) {
                            uniqueCourseIds.add(courseId)
                        }
                    }
                }
            }
            
            if (uniqueCourseIds.isEmpty()) {
                Log.d("SupabaseClient", "No courses found with successful/APPROVED transactions")
                return@withContext emptyList()
            }
            
            Log.d("SupabaseClient", "Found ${uniqueCourseIds.size} courses with successful/APPROVED transactions")
            
            // Now fetch the actual courses using the IDs
            val courseIds = uniqueCourseIds.toList()
            return@withContext fetchCoursesByIds(courseIds)
            
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching purchased courses", e)
            emptyList()
        }
    }
    
    /**
     * Check if a specific user has purchased a course (has successful/APPROVED transaction)
     */
    suspend fun hasUserPurchasedCourse(userId: Long, courseId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext false
            
            // Check 'successful' status
            val requestSuccessful = buildGetRequest("transactions?user_id=eq.$userId&course_id=eq.$courseId&status=eq.successful&select=id")
            val responseSuccessful = client.newCall(requestSuccessful).execute()
            if (responseSuccessful.isSuccessful) {
                val responseBody = responseSuccessful.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val jsonArray = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                    if (jsonArray.size() > 0) {
                        Log.d("SupabaseClient", "User $userId purchased course $courseId: true (successful)")
                        return@withContext true
                    }
                }
            }
            
            // Check 'APPROVED' status (Wompi returns uppercase)
            val requestApproved = buildGetRequest("transactions?user_id=eq.$userId&course_id=eq.$courseId&status=eq.APPROVED&select=id")
            val responseApproved = client.newCall(requestApproved).execute()
            if (responseApproved.isSuccessful) {
                val responseBody = responseApproved.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val jsonArray = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                    if (jsonArray.size() > 0) {
                        Log.d("SupabaseClient", "User $userId purchased course $courseId: true (APPROVED)")
                        return@withContext true
                    }
                }
            }
            
            Log.d("SupabaseClient", "User $userId purchased course $courseId: false")
            false
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error checking user purchase", e)
            false
        }
    }
    
    /**
     * Fetch courses purchased by a specific user (successful/APPROVED transactions)
     */
    suspend fun fetchCoursesPurchasedByUser(userId: Long): List<Course> = withContext(Dispatchers.IO) {
        try {
            if (!isConfigured()) return@withContext emptyList()
            
            val courseIds = mutableSetOf<Long>()
            
            // Get course IDs from 'successful' transactions for this user
            val requestSuccessful = buildGetRequest("transactions?user_id=eq.$userId&status=eq.successful&select=course_id")
            val responseSuccessful = client.newCall(requestSuccessful).execute()
            if (responseSuccessful.isSuccessful) {
                val responseBody = responseSuccessful.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val jsonArray = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                    jsonArray.forEach { element ->
                        val jsonObject = element.asJsonObject
                        val courseId = jsonObject.get("course_id")?.asLong
                        if (courseId != null) {
                            courseIds.add(courseId)
                        }
                    }
                }
            }
            
            // Get course IDs from 'APPROVED' transactions for this user (Wompi returns uppercase)
            val requestApproved = buildGetRequest("transactions?user_id=eq.$userId&status=eq.APPROVED&select=course_id")
            val responseApproved = client.newCall(requestApproved).execute()
            if (responseApproved.isSuccessful) {
                val responseBody = responseApproved.body?.string()
                if (!responseBody.isNullOrEmpty()) {
                    val jsonArray = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
                    jsonArray.forEach { element ->
                        val jsonObject = element.asJsonObject
                        val courseId = jsonObject.get("course_id")?.asLong
                        if (courseId != null) {
                            courseIds.add(courseId)
                        }
                    }
                }
            }
            
            if (courseIds.isEmpty()) {
                Log.d("SupabaseClient", "No courses purchased by user $userId")
                return@withContext emptyList()
            }
            
            Log.d("SupabaseClient", "User $userId has purchased ${courseIds.size} courses")
            
            // Fetch the actual courses
            return@withContext fetchCoursesByIds(courseIds.toList())
            
        } catch (e: Exception) {
            Log.e("SupabaseClient", "Error fetching courses purchased by user", e)
            emptyList()
        }
    }
}
