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
                "task_id" to submission.taskId,
                "student_username" to submission.studentUsername,
                "file_uri" to submission.fileUri,
                "file_name" to submission.fileName,
                "submission_date" to submission.submissionDate,
                "grade" to submission.grade,
                "feedback" to submission.feedback
            )

            val body = gson.toJson(map).toRequestBody(jsonMedia)
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
}
