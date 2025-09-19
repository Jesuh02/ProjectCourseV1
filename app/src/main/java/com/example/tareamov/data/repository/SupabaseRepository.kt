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
                            // Ensure we include local id as the remote id so FK from submissions (task_id) matches
                            m["id"] = coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["taskId"] ?: asMap["task_id"]) ?: coerceToLong(asMap["id"])
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
                            // include local id for topics too so tasks referencing topic.id will match
                            m["id"] = coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["topicId"] ?: asMap["topic_id"]) ?: coerceToLong(asMap["id"])
                            // course_id in DDL (bigint)
                            m["course_id"] = coerceToLong(asMap["courseId"] ?: asMap["course_id"]) ?: coerceToLong(asMap["courseId"] ?: asMap["course_id"]) 
                            m["name"] = (asMap["name"] ?: asMap["title"] ?: "")
                            m["description"] = (asMap["description"] ?: null)
                            // order_index is integer
                            m["order_index"] = coerceToInt(asMap["orderIndex"] ?: asMap["order_index"] ?: 0)
                            m
                        }
                        "content_items" -> {
                            val m = mutableMapOf<String, Any?>()
                            m["id"] = coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["contentItemId"] ?: asMap["content_item_id"]) ?: coerceToLong(asMap["id"])
                            m["topic_id"] = coerceToLong(asMap["topicId"] ?: asMap["topic_id"]) 
                            m["title"] = (asMap["title"] ?: asMap["name"] ?: "")
                            m["body"] = asMap["body"] ?: asMap["content"] ?: null
                            m["content_type"] = (asMap["contentType"] ?: asMap["type"] ?: "unknown")
                            m["created_at"] = java.time.OffsetDateTime.now().toString()
                            m
                        }
                        "videos" -> {
                            val m = mutableMapOf<String, Any?>()
                            m["id"] = coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["videoId"] ?: asMap["video_id"]) ?: coerceToLong(asMap["id"])
                            m["username"] = asMap["username"] ?: asMap["creator_username"] ?: asMap["creator"]
                            m["title"] = (asMap["title"] ?: asMap["name"] ?: "")
                            m["description"] = (asMap["description"] ?: null)
                            m["video_uri_string"] = (asMap["videoUriString"] ?: asMap["remoteUrl"] ?: null)
                            m["local_file_path"] = (asMap["localFilePath"] ?: null)
                            m["thumbnail_uri"] = (asMap["thumbnailUri"] ?: null)
                            m["price"] = when (val p = asMap["price"] ?: asMap["priceUsd"]) {
                                is Number -> p
                                is String -> p.toDoubleOrNull()
                                else -> null
                            }
                            m["created_at"] = java.time.OffsetDateTime.now().toString()
                            m
                        }
                        "file_contexts" -> {
                            val m = mutableMapOf<String, Any?>()
                            coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["fileContextId"] ?: asMap["file_context_id"])?.let { m["id"] = it }
                            coerceToLong(asMap["submissionId"] ?: asMap["submission_id"] ?: asMap["submissionId"])?.let { m["submission_id"] = it }
                            (asMap["fileName"] ?: asMap["file_name"] ?: asMap["fileName"])?.let { m["file_name"] = it }
                            (asMap["fileType"] ?: asMap["file_type"])?.let { m["file_type"] = it }
                            (asMap["fileContent"] ?: asMap["file_content"])?.let { m["file_content"] = it }
                            (asMap["extractedText"] ?: asMap["extracted_text"])?.let { m["extracted_text"] = it }
                            (asMap["metadata"])?.let { m["metadata"] = it }
                            // Do not send `timestamp` column by default to avoid schema mismatch on remote
                            // json_content may be stored as String (json) in Room
                            (asMap["jsonContent"] ?: asMap["json_content"])?.let { m["json_content"] = it }
                            (asMap["contentSummary"] ?: asMap["content_summary"])?.let { m["content_summary"] = it }
                            m["created_at"] = java.time.OffsetDateTime.now().toString()
                            m
                        }
                        "chat_messages" -> {
                            val m = mutableMapOf<String, Any?>()
                            coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["messageId"] ?: asMap["message_id"])?.let { m["id"] = it }
                            // message/text/body
                            (asMap["message"] ?: asMap["text"] ?: asMap["body"])?.let { m["message"] = it }
                            // boolean fields
                            (asMap["isFromUser"] ?: asMap["is_from_user"])?.let { v ->
                                val boolVal = when (v) {
                                    is Boolean -> v
                                    is Number -> v.toInt() != 0
                                    is String -> v.toBoolean()
                                    else -> false
                                }
                                m["is_from_user"] = boolVal
                            }
                            // Do not send `timestamp` column by default to avoid schema mismatch on remote
                            (asMap["sessionId"] ?: asMap["session_id"])?.let { m["session_id"] = it }
                            (asMap["hasCalification"] ?: asMap["has_calification"])?.let { v ->
                                val boolVal = when (v) {
                                    is Boolean -> v
                                    is Number -> v.toInt() != 0
                                    is String -> v.toBoolean()
                                    else -> false
                                }
                                m["has_calification"] = boolVal
                            }
                            (asMap["calificationValue"] ?: asMap["calification_value"])?.let { m["calification_value"] = it }
                            (asMap["calificationAdded"] ?: asMap["calification_added"])?.let { v ->
                                val boolVal = when (v) {
                                    is Boolean -> v
                                    is Number -> v.toInt() != 0
                                    is String -> v.toBoolean()
                                    else -> false
                                }
                                m["calification_added"] = boolVal
                            }
                            m["created_at"] = java.time.OffsetDateTime.now().toString()
                            m
                        }
                        "subscriptions" -> {
                            val m = mutableMapOf<String, Any?>()
                            // composite PK of (subscriber_username, creator_username) — keep as provided
                            m["subscriber_username"] = asMap["subscriberUsername"] ?: asMap["subscriber_username"] ?: asMap["subscriber"] ?: asMap["subscriber_username"]
                            m["creator_username"] = asMap["creatorUsername"] ?: asMap["creator_username"] ?: asMap["creator"] ?: asMap["creator_username"]
                            // coerce subscription_date to Long if possible
                            coerceToLong(asMap["subscriptionDate"] ?: asMap["subscription_date"])?.let { m["subscription_date"] = it }
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
                if (!resp.isSuccessful) {
                    val body = try { resp.body?.string() } catch (_: Exception) { null }
                    Log.w("SupabaseRepository", "tableExists check for $table returned code=${resp.code} body=$body")
                }
                return resp.isSuccessful
            }
        } catch (e: Exception) {
            Log.w("SupabaseRepository", "tableExists check failed for $table", e)
            return false
        }
    }
}
