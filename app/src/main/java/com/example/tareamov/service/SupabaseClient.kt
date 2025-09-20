package com.example.tareamov.service

import com.example.tareamov.BuildConfig
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val baseUrl = BuildConfig.SUPABASE_URL.trimEnd('/')
    private val apiKey = BuildConfig.SUPABASE_KEY
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    fun isConfigured(): Boolean {
        val url = baseUrl.trim()
        val key = apiKey.trim()
        val configured = url.isNotEmpty() && key.isNotEmpty()
        if (!configured) {
            try {
                val maskedUrl = if (url.length <= 12) url else url.substring(0, 12) + "..."
                val maskedKey = if (key.length <= 8) "(hidden)" else key.substring(0, 6) + "..." + key.takeLast(4)
                android.util.Log.w("SupabaseClient", "Supabase not configured: SUPABASE_URL=$maskedUrl SUPABASE_KEY=$maskedKey HOST_IP=${BuildConfig.HOST_IP}. Check local.properties and rebuild.")
            } catch (t: Throwable) {
                // ignore logging failures
            }
        }
        return configured
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
                    throw Exception("Supabase insertTopic failed: ${'$'}{resp.code} ${'$'}{resp.message} body=$bodyStr")
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

    suspend fun insertTaskSubmission(submission: com.example.tareamov.data.entity.TaskSubmission): Long? = withContext(Dispatchers.IO) {
        try {
            val map = mapOf(
                "id" to submission.id,
                "task_id" to submission.taskId,
                "student_username" to submission.studentUsername,
                "file_uri" to submission.fileUri,
                "file_name" to submission.fileName,
                "submission_date" to submission.submissionDate,
                "grade" to submission.grade,
                "feedback" to submission.feedback
            )

            val body = gson.toJson(map).toRequestBody(jsonMedia)
            // Log the outgoing payload for debugging FK errors (avoid logging secrets)
            android.util.Log.d("SupabaseClient", "insertTaskSubmission payload: ${gson.toJson(map)}")
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
                val respBody = resp.body?.string()
                if (!resp.isSuccessful) {
                    val bodyStr = respBody ?: ""
                    throw Exception("Supabase insertTaskSubmission failed: ${'$'}{resp.code} ${'$'}{resp.message} body=$bodyStr")
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

    suspend fun updateTaskSubmissionRemote(submissionId: Long, grade: Float?, feedback: String?): Boolean = withContext(Dispatchers.IO) {
        try {
            val map = mutableMapOf<String, Any?>()
            if (grade != null) map["grade"] = grade
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
            if (grade != null) map["grade"] = grade
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
    private fun buildGetRequest(path: String): Request {
        val url = "$baseUrl/rest/v1/$path"
        return Request.Builder()
            .url(url)
            .get()
            .addHeader("apikey", apiKey)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "application/json")
            .build()
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
                    val arr = gson.fromJson(body, clazz)
                    return@withContext arr?.toList() ?: emptyList()
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
    suspend fun fetchVideos(): List<VideoData> = fetchList("videos", Array<VideoData>::class.java)
    suspend fun fetchTopics(): List<Topic> = fetchList("topics", Array<Topic>::class.java)
    suspend fun fetchContentItems(): List<ContentItem> = fetchList("content_items", Array<ContentItem>::class.java)
    suspend fun fetchTasks(): List<Task> = fetchList("tasks", Array<Task>::class.java)
    suspend fun fetchSubscriptions(): List<Subscription> = fetchList("subscriptions", Array<Subscription>::class.java)
    suspend fun fetchTaskSubmissions(): List<TaskSubmission> = fetchList("task_submissions", Array<TaskSubmission>::class.java)
    suspend fun fetchChatMessages(): List<ChatMessage> = fetchList("chat_messages", Array<ChatMessage>::class.java)
    suspend fun fetchFileContexts(): List<FileContext> = fetchList("file_contexts", Array<FileContext>::class.java)
    suspend fun fetchCourses(): List<Course> = fetchList("courses", Array<Course>::class.java)
    suspend fun fetchRoles(): List<Rol> = fetchList("roles", Array<Rol>::class.java)
    suspend fun fetchRecursos(): List<Recurso> = fetchList("recursos", Array<Recurso>::class.java)
    suspend fun fetchRolRecursos(): List<RolRecurso> = fetchList("rol_recursos", Array<RolRecurso>::class.java)
}
