package com.example.tareamov.data.repository

import com.example.tareamov.BuildConfig

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

// Minimal Supabase REST client using OkHttp. This avoids pulling a large SDK and keeps control
// over headers. It performs simple upserts to Supabase tables via the PostgREST interface.
class SupabaseRepository(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val supabaseKey: String = BuildConfig.SUPABASE_KEY
) {
    private val client = OkHttpClient()
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    // Helper to coerce various incoming types (Double, Int, String) to Long for bigint columns
    private fun coerceToLong(value: Any?): Long? {
        if (value == null) return null
        return when (value) {
            is Number -> value.toLong()
            is String -> {
                // try parse as long, fall back to double then toLong
                value.toLongOrNull() ?: value.toDoubleOrNull()?.toLong()
            }
            else -> null
        }
    }

    // Helper to coerce to Int for integer columns
    private fun coerceToInt(value: Any?): Int? {
        if (value == null) return null
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: value.toDoubleOrNull()?.toInt()
            else -> null
        }
    }

    // Simple function to upsert an object into a table using PostgREST upsert (on conflict) via RPC headers
    // Upsert wrapper: instead of relying on individual tables existing in Supabase,
    // store each entity in a single 'app_documents' table with columns:
    //  - table_name TEXT
    //  - entity_id TEXT
    //  - data JSONB
    // This avoids schema drift between Room entities and Postgres and requires only
    // creating one table on the Supabase side.
    fun upsert(table: String, payload: Any) : Boolean {
        try {
            // If the helper table `app_documents` exists, wrap payload to the single-table storage
            if (tableExists("app_documents")) {
                // Try to extract an identifier from the payload (common keys: id, uuid, usuario)
                val asMap = gson.fromJson(gson.toJson(payload), Map::class.java)
                val possibleId = (asMap["id"] ?: asMap["uuid"] ?: asMap["usuario"] ?: asMap["username"] ?: asMap["email"])?.toString()

                val wrapper = mapOf(
                    "table_name" to table,
                    "entity_id" to (possibleId ?: java.util.UUID.randomUUID().toString()),
                    "data" to asMap
                )

                val url = "${supabaseUrl}/rest/v1/app_documents"
                val bodyJson = gson.toJson(wrapper)
                val request = Request.Builder()
                    .url(url)
                    .post(bodyJson.toRequestBody(jsonMediaType))
                    .addHeader("apikey", supabaseKey)
                    .addHeader("Authorization", "Bearer $supabaseKey")
                    // Prefer merge duplicates on conflict if the table has unique constraint on (table_name, entity_id)
                    .addHeader("Prefer", "resolution=merge-duplicates")
                    .addHeader("Content-Type", "application/json")
                    .build()

                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.e("SupabaseRepository", "Upsert failed for app_documents code=${resp.code} body=${resp.body?.string()}")
                        return false
                    }
                    return true
                }
            } else {
                // Fallback: try to POST directly to the target table endpoint. This requires that
                // the target table exists and the payload keys match table columns. Use Prefer header
                // to attempt merge-duplicates (upsert) where possible.
                try {
                    // Convert payload to a mutable map to allow remapping keys
                    @Suppress("UNCHECKED_CAST")
                    val asMap = gson.fromJson(gson.toJson(payload), Map::class.java) as Map<String, Any?>

                    // Build a mappedPayload that fits the SQL schema in migrations/0001_create_tables.sql
                    val mappedPayload: Any = when (table) {
                        "tasks" -> {
                            val m = mutableMapOf<String, Any?>()
                            // Postgres DDL uses snake_case: topic_id (bigint) — coerce to Long
                            m["topic_id"] = coerceToLong(asMap["topicId"] ?: asMap["topic_id"])
                            m["title"] = (asMap["name"] ?: asMap["title"] ?: "")
                            m["description"] = (asMap["description"] ?: null)
                            // due_date is timestamptz, keep raw string or null
                            m["due_date"] = asMap["dueDate"] ?: asMap["due_date"]
                            m["created_at"] = java.time.OffsetDateTime.now().toString()
                            m
                        }
                        "topics" -> {
                            val m = mutableMapOf<String, Any?>()
                            // course_id in DDL (bigint)
                            m["course_id"] = coerceToLong(asMap["courseId"] ?: asMap["course_id"])
                            m["name"] = (asMap["name"] ?: asMap["title"] ?: "")
                            m["description"] = (asMap["description"] ?: null)
                            // order_index is integer
                            m["order_index"] = coerceToInt(asMap["orderIndex"] ?: asMap["order_index"] ?: 0)
                            m
                        }
                        "content_items" -> {
                            val m = mutableMapOf<String, Any?>()
                            m["topic_id"] = coerceToLong(asMap["topicId"] ?: asMap["topic_id"])
                            m["title"] = (asMap["title"] ?: asMap["name"] ?: "")
                            m["body"] = asMap["body"] ?: asMap["content"] ?: null
                            m["content_type"] = (asMap["contentType"] ?: asMap["type"] ?: "unknown")
                            m["created_at"] = java.time.OffsetDateTime.now().toString()
                            m
                        }
                        "videos" -> {
                            val m = mutableMapOf<String, Any?>()
                            m["username"] = asMap["username"] ?: asMap["creator_username"] ?: asMap["creator"]
                            m["title"] = (asMap["title"] ?: asMap["name"] ?: "")
                            m["description"] = (asMap["description"] ?: null)
                            m["video_uri_string"] = (asMap["videoUriString"] ?: asMap["remoteUrl"] ?: null)
                            m["local_file_path"] = (asMap["localFilePath"] ?: null)
                            m["thumbnail_uri"] = (asMap["thumbnailUri"] ?: null)
                            m["created_at"] = java.time.OffsetDateTime.now().toString()
                            m
                        }
                        else -> asMap
                    }

                    // Log the mapped payload to help debug type/format issues (do not log secrets)
                    try {
                        val debugPayload = gson.toJson(mappedPayload)
                        Log.d("SupabaseRepository", "Mapped payload for table=$table : $debugPayload")
                    } catch (e: Exception) {
                        Log.w("SupabaseRepository", "Failed to stringify mappedPayload for logging", e)
                    }

                    val bodyJson = gson.toJson(mappedPayload)
                    val url = "${supabaseUrl.trimEnd('/')}/rest/v1/$table"
                    val request = Request.Builder()
                        .url(url)
                        .post(bodyJson.toRequestBody(jsonMediaType))
                        .addHeader("apikey", supabaseKey)
                        .addHeader("Authorization", "Bearer $supabaseKey")
                        .addHeader("Prefer", "resolution=merge-duplicates,return=representation")
                        .addHeader("Content-Type", "application/json")
                        .build()

                    client.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            val bodyStr = resp.body?.string()
                            Log.e("SupabaseRepository", "Direct upsert failed for $table code=${resp.code} body=$bodyStr")
                            return false
                        }
                        return true
                    }
                } catch (e: Exception) {
                    Log.e("SupabaseRepository", "Direct upsert exception for $table", e)
                    return false
                }
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Exception while upserting to app_documents", e)
            return false
        }
    }

    // Basic function to sign in using the Supabase REST auth endpoint. Returns access token or null.
    suspend fun loginConEmail(email: String, password: String): String? {
        try {
            val url = "${supabaseUrl}/auth/v1/token?grant_type=password"
            val payload = mapOf("email" to email, "password" to password)
            val bodyJson = gson.toJson(payload)
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMediaType))
                .addHeader("apikey", supabaseKey)
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("SupabaseRepository", "Login failed code=${resp.code} body=${resp.body?.string()}")
                    return null
                }
                val respStr = resp.body?.string() ?: return null
                val tree = gson.fromJson(respStr, Map::class.java)
                val accessToken = tree["access_token"] as? String
                return accessToken
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Login exception", e)
            return null
        }
    }

    // Check if a REST endpoint (table) exists by making a lightweight HEAD-like request
    fun tableExists(table: String): Boolean {
        try {
            if (supabaseUrl.isBlank()) {
                Log.w("SupabaseRepository", "supabaseUrl is blank, cannot check tableExists for $table")
                return false
            }
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/$table?select=1&limit=1"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { resp ->
                // 200 means the table exists (even if empty). 404 or 400 likely means not found
                return resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("SupabaseRepository", "tableExists check failed for $table", e)
            return false
        }
    }
}
