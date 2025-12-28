package com.example.tareamov.data.repository

import com.example.tareamov.BuildConfig

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.net.SocketTimeoutException

// Minimal Supabase REST client using OkHttp. This avoids pulling a large SDK and keeps control
// over headers. It performs simple upserts to Supabase tables via the PostgREST interface.
class SupabaseRepository(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val supabaseKey: String = BuildConfig.SUPABASE_ANON_KEY
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    // Execute an OkHttp request with simple retry/backoff handling for transient errors (timeouts)
    private fun executeRequestWithRetries(request: Request, maxAttempts: Int = 3): okhttp3.Response? {
        var attempt = 1
        var backoff = 500L
        while (attempt <= maxAttempts) {
            try {
                val resp = client.newCall(request).execute()
                return resp
            } catch (e: SocketTimeoutException) {
                Log.w("SupabaseRepository", "Request timeout (attempt $attempt/$maxAttempts)", e)
                if (attempt == maxAttempts) return null
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { }
                backoff *= 2
                attempt++
            } catch (e: Exception) {
                Log.e("SupabaseRepository", "Request failed (non-timeout)", e)
                return null
            }
        }
        return null
    }

    /**
     * Upsert reinforcement question history for a user+course.
     * Uses INSERT ... ON CONFLICT to avoid duplicates.
     */
    suspend fun upsertReinforcementHistory(userId: Long, courseId: Long, questionsJson: String): Boolean {
        return try {
            // Try to fetch existing questions for this user+course
            val selectSql = "SELECT questions FROM public.reinforcement_question_history WHERE user_id = $userId AND course_id = $courseId LIMIT 1;"
            val existing = try {
                executeRawQuery(selectSql).firstOrNull()
            } catch (e: Exception) {
                null
            }

            val combinedArray = com.google.gson.JsonArray()

            // Parse existing questions if present
            if (existing != null && existing.containsKey("questions")) {
                val existingVal = existing["questions"]
                try {
                    val existingJson = when (existingVal) {
                        is String -> gson.fromJson(existingVal, com.google.gson.JsonElement::class.java)
                        else -> gson.toJsonTree(existingVal)
                    }
                    if (existingJson != null && existingJson.isJsonArray) {
                        existingJson.asJsonArray.forEach { combinedArray.add(it) }
                    }
                } catch (e: Exception) {
                    Log.w("SupabaseRepository", "Failed to parse existing reinforcement questions", e)
                }
            }

            // Parse incoming questions JSON
            try {
                val incoming = gson.fromJson(questionsJson, com.google.gson.JsonElement::class.java)
                if (incoming != null && incoming.isJsonArray) {
                    incoming.asJsonArray.forEach { combinedArray.add(it) }
                }
            } catch (e: Exception) {
                // If incoming isn't a JSON array, try to wrap it
                try {
                    val single = gson.fromJson(questionsJson, com.google.gson.JsonObject::class.java)
                    combinedArray.add(single)
                } catch (ex: Exception) {
                    Log.w("SupabaseRepository", "Incoming questions JSON invalid", ex)
                }
            }

            // Keep only the most recent 50 entries
            val start = if (combinedArray.size() > 50) combinedArray.size() - 50 else 0
            val recent = com.google.gson.JsonArray()
            for (i in start until combinedArray.size()) {
                recent.add(combinedArray.get(i))
            }

            val combinedJson = gson.toJson(recent)
            val safeJson = combinedJson.replace("'", "''")

            val sql = """
                INSERT INTO public.reinforcement_question_history (user_id, course_id, questions, created_at)
                VALUES ($userId, $courseId, '$safeJson'::jsonb, now())
                ON CONFLICT (user_id, course_id)
                DO UPDATE SET questions = EXCLUDED.questions, created_at = now();
            """.trimIndent()

            executeRawQuery(sql)
            true
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Failed upsertReinforcementHistory", e)
            false
        }
    }

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
    
    // Lightweight SQL validation to avoid sending obviously malformed SQL to the RPC
    private fun validateSql(sql: String): Pair<Boolean, String?> {
        if (sql.isBlank()) return Pair(false, "Empty SQL")

        var depth = 0
        for (ch in sql) {
            if (ch == '(') depth++
            else if (ch == ')') {
                depth--
                if (depth < 0) return Pair(false, "Unbalanced parentheses (extra ')')")
            }
        }
        if (depth != 0) return Pair(false, "Unbalanced parentheses (missing ')')")

        val emptyIn = Regex("\\bIN\\s*\\(\\s*\\)", RegexOption.IGNORE_CASE)
        if (emptyIn.containsMatchIn(sql)) return Pair(false, "Empty IN() clause detected")

        val fromParen = Regex("from\\s*\\)", RegexOption.IGNORE_CASE)
        if (fromParen.containsMatchIn(sql)) return Pair(false, "Unexpected ')' after FROM")

        return Pair(true, null)
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
        // Prevent client from performing upserts when no anon key is configured
        if (supabaseKey.isBlank()) {
            Log.e("SupabaseRepository", "upsert blocked: SUPABASE_ANON_KEY is not set. Perform upserts via a secure backend.")
            return false
        }
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

                val resp = executeRequestWithRetries(request)
                    ?: run {
                        Log.e("SupabaseRepository", "Request failed (timeout) for app_documents after retries")
                        return false
                    }
                resp.use { r ->
                    if (!r.isSuccessful) {
                        Log.e("SupabaseRepository", "Upsert failed for app_documents code=${r.code} body=${r.body?.string()}")
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
                            // Don't send id on insert - let Supabase auto-generate it
                            // Only include id if it's explicitly provided and > 0
                            val taskId = coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["taskId"] ?: asMap["task_id"])
                            if (taskId != null && taskId > 0) {
                                m["id"] = taskId
                            }
                            
                            // Postgres DDL uses snake_case: topic_id (bigint) — coerce to Long
                            val topicId = coerceToLong(asMap["topicId"] ?: asMap["topic_id"])
                            if (topicId != null && topicId > 0) {
                                m["topic_id"] = topicId
                            }
                            
                            m["title"] = (asMap["name"] ?: asMap["title"] ?: "").toString()
                            m["description"] = (asMap["description"]?.toString() ?: null)
                            
                            // due_date is timestamptz, keep raw string or null
                            val dueDate = asMap["dueDate"] ?: asMap["due_date"]
                            if (dueDate != null) {
                                m["due_date"] = dueDate
                            }
                            m
                        }
                        "topics" -> {
                            val m = mutableMapOf<String, Any?>()
                            // Don't send id on insert - let Supabase auto-generate it
                            val topicId = coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["topicId"] ?: asMap["topic_id"])
                            if (topicId != null && topicId > 0) {
                                m["id"] = topicId
                            }
                            
                            // course_id in DDL (bigint)
                            val courseId = coerceToLong(asMap["courseId"] ?: asMap["course_id"])
                            if (courseId != null && courseId > 0) {
                                m["course_id"] = courseId
                            }
                            
                            m["name"] = (asMap["name"] ?: asMap["title"] ?: "").toString()
                            m["description"] = (asMap["description"]?.toString() ?: null)
                            
                            // order_index is integer
                            val orderIdx = coerceToInt(asMap["orderIndex"] ?: asMap["order_index"] ?: 0)
                            if (orderIdx != null) {
                                m["order_index"] = orderIdx
                            }
                            m
                        }
                        "content_items" -> {
                            val m = mutableMapOf<String, Any?>()
                            val contentId = coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["contentItemId"] ?: asMap["content_item_id"])
                            if (contentId != null && contentId > 0) {
                                m["id"] = contentId
                            }
                            
                            val topicId = coerceToLong(asMap["topicId"] ?: asMap["topic_id"])
                            if (topicId != null && topicId > 0) {
                                m["topic_id"] = topicId
                            }
                            
                            m["title"] = (asMap["title"] ?: asMap["name"] ?: "").toString()
                            m["body"] = asMap["body"]?.toString() ?: asMap["content"]?.toString()
                            m["content_type"] = (asMap["contentType"] ?: asMap["type"] ?: "unknown").toString()
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
                        "roles" -> {
                            val m = mutableMapOf<String, Any?>()
                            // Ensure id is properly converted to Long (not String with decimal format)
                            val roleId = coerceToLong(asMap["id"] ?: asMap["Id"] ?: asMap["roleId"] ?: asMap["role_id"])
                            if (roleId != null && roleId > 0) {
                                m["id"] = roleId
                            }
                            m["nombre"] = (asMap["nombre"] ?: asMap["name"] ?: "").toString()
                            // nivel is float but ensure proper format
                            val nivel = when (val n = asMap["nivel"] ?: asMap["level"]) {
                                is Number -> n.toFloat()
                                is String -> n.toFloatOrNull() ?: 1.0f
                                else -> 1.0f
                            }
                            m["nivel"] = nivel
                            // default is boolean
                            val isDefault = when (val d = asMap["default"] ?: asMap["is_default"]) {
                                is Boolean -> d
                                is Number -> d.toInt() != 0
                                is String -> d.toBoolean()
                                else -> false
                            }
                            m["default"] = isDefault
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
                        "task_submissions" -> {
                            val m = mutableMapOf<String, Any?>()
                            // Map camelCase from Room entity to snake_case for Supabase
                            coerceToLong(asMap["id"] ?: asMap["Id"])?.let { if (it > 0) m["id"] = it }
                            coerceToLong(asMap["taskId"] ?: asMap["task_id"])?.let { m["task_id"] = it }
                            coerceToLong(asMap["studentId"] ?: asMap["student_id"])?.let { m["student_id"] = it }
                            coerceToLong(asMap["submissionDate"] ?: asMap["submission_date"])?.let { m["submission_date"] = it }
                            (asMap["fileUri"] ?: asMap["file_uri"])?.let { m["file_uri"] = it.toString() }
                            (asMap["fileName"] ?: asMap["file_name"])?.let { m["file_name"] = it.toString() }
                            // grade can be Float or Number
                            when (val g = asMap["grade"]) {
                                is Number -> m["grade"] = g.toFloat()
                                is String -> g.toFloatOrNull()?.let { m["grade"] = it }
                            }
                            (asMap["feedback"])?.let { m["feedback"] = it.toString() }
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
                    
                    // For tasks table, don't use merge-duplicates to avoid type comparison errors
                    // Just do a simple POST insert and let Supabase handle conflicts via constraints
                    val preferHeader = if (table == "tasks" || table == "topics" || table == "content_items") {
                        "return=representation"
                    } else {
                        "resolution=merge-duplicates,return=representation"
                    }
                    
                    val request = Request.Builder()
                        .url(url)
                        .post(bodyJson.toRequestBody(jsonMediaType))
                        .addHeader("apikey", supabaseKey)
                        .addHeader("Authorization", "Bearer $supabaseKey")
                        .addHeader("Prefer", preferHeader)
                        .addHeader("Content-Type", "application/json")
                        .build()

                    val resp = executeRequestWithRetries(request)
                        ?: run {
                            Log.e("SupabaseRepository", "Request failed (timeout) for $table after retries")
                            return false
                        }
                    resp.use { r ->
                        if (!r.isSuccessful) {
                            val bodyStr = r.body?.string()
                            Log.e("SupabaseRepository", "Direct upsert failed for $table code=${r.code} body=$bodyStr")
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
            // Use select=* to avoid PostgREST interpreting numeric select values as column names
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/$table?select=*&limit=1"
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
    
    /**
     * Execute a raw SQL query via Supabase RPC endpoint
     * This allows direct SQL execution for MCP tools
     */
    suspend fun executeRawQuery(sql: String): List<Map<String, Any?>> {
        // Prevent arbitrary SQL execution from client builds without a configured key
        if (supabaseKey.isBlank()) {
            Log.e("SupabaseRepository", "executeRawQuery blocked: SUPABASE_ANON_KEY is not set. Move raw SQL execution to a trusted backend.")
            throw Exception("Raw SQL execution is disabled in client builds. Use a secure backend endpoint.")
        }
        return try {
            // Basic validation to catch common LLM-generated syntax issues
            val (ok, reason) = validateSql(sql)
            if (!ok) {
                throw Exception("Invalid SQL: $reason")
            }
            
            Log.d("SupabaseRepository", "Executing raw SQL: $sql")
            
            // Use the Supabase RPC endpoint to execute raw SQL
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/execute_sql"
            
            val payload = mapOf("query" to sql)
            val bodyJson = gson.toJson(payload)
            
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMediaType))
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                
                if (!resp.isSuccessful) {
                    Log.e("SupabaseRepository", "SQL execution failed: code=${resp.code} body=$body")
                    throw Exception("SQL execution failed: ${resp.message}")
                }
                
                // Parse the response as JSON array
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                val resultList = mutableListOf<Map<String, Any?>>()
                
                for (element in jsonArray) {
                    if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        val map = mutableMapOf<String, Any?>()
                        
                        for ((key, value) in obj.entrySet()) {
                            map[key] = when {
                                value.isJsonNull -> null
                                value.isJsonPrimitive -> {
                                    val primitive = value.asJsonPrimitive
                                    when {
                                        primitive.isBoolean -> primitive.asBoolean
                                        primitive.isNumber -> primitive.asNumber
                                        primitive.isString -> primitive.asString
                                        else -> value.toString()
                                    }
                                }
                                else -> value.toString()
                            }
                        }
                        
                        resultList.add(map)
                    }
                }
                
                Log.d("SupabaseRepository", "SQL executed successfully: ${resultList.size} rows")
                resultList
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error executing raw SQL", e)
            throw e
        }
    }
    
    /**
     * Ejecuta una migración SQL desde un archivo
     * Útil para aplicar triggers y funciones en Supabase
     */
    suspend fun executeMigrationFile(sqlContent: String): Boolean {
        if (supabaseKey.isBlank()) {
            Log.e("SupabaseRepository", "executeMigrationFile blocked: SUPABASE_ANON_KEY is not set. Migrations must run on backend.")
            return false
        }
        return try {
            Log.d("SupabaseRepository", "Executing migration file...")
            
            // Dividir por statements individuales (separados por ;)
            val statements = sqlContent.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("--") }
            
            var successCount = 0
            for ((index, statement) in statements.withIndex()) {
                try {
                    if (statement.isNotBlank()) {
                        executeRawQuery(statement)
                        successCount++
                        Log.d("SupabaseRepository", "Statement ${index + 1}/${statements.size} executed successfully")
                    }
                } catch (e: Exception) {
                    Log.w("SupabaseRepository", "Statement ${index + 1} failed (may be expected for DROP IF EXISTS): ${e.message}")
                }
            }
            
            Log.i("SupabaseRepository", "Migration completed: $successCount/${statements.size} statements executed")
            true
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error executing migration file", e)
            false
        }
    }
    
    /**
     * Search videos by title, username, or category
     */
    fun searchVideos(
        query: String,
        searchType: String = "all",
        limit: Int = 50
    ): List<Map<String, Any?>> {
        return try {
            val sanitizedQuery = query.trim().replace("'", "''").lowercase()
            if (sanitizedQuery.isEmpty()) {
                Log.d("SupabaseRepository", "Empty search query")
                return emptyList()
            }

            // Prioritize exact matches, then starts-with, then contains.
            // Note: 'username' and 'category' columns may not exist in the 'videos' table,
            // so we fallback to searching title and description to ensure stability.
            
            val whereClause = when (searchType) {
                "title" -> "LOWER(title) LIKE '%$sanitizedQuery%'"
                // Fallback for username/category to avoid crash, search description/title instead
                "username", "category", "all" -> "LOWER(title) LIKE '%$sanitizedQuery%' OR LOWER(description) LIKE '%$sanitizedQuery%'"
                else -> "LOWER(title) LIKE '%$sanitizedQuery%' OR LOWER(description) LIKE '%$sanitizedQuery%'"
            }

            // Relevance scoring:
            // 0: Exact title match
            // 1: Title starts with query
            // 2: Title contains query
            // 3: Description contains query (fallback)
            val orderByClause = """
                CASE 
                    WHEN LOWER(title) = '$sanitizedQuery' THEN 0 
                    WHEN LOWER(title) LIKE '$sanitizedQuery%' THEN 1 
                    WHEN LOWER(title) LIKE '%$sanitizedQuery%' THEN 2
                    ELSE 3 
                END, id DESC
            """.trimIndent()

            val sql = "SELECT * FROM videos WHERE $whereClause ORDER BY $orderByClause LIMIT $limit"

            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/rpc/execute_sql"
            val payload = mapOf("query" to sql)
            val bodyJson = gson.toJson(payload)
            
            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(jsonMediaType))
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e("SupabaseRepository", "searchVideos failed: code=${resp.code}")
                    return emptyList()
                }
                
                val body = resp.body?.string() ?: return emptyList()
                val jsonArray = gson.fromJson(body, com.google.gson.JsonArray::class.java)
                val resultList = mutableListOf<Map<String, Any?>>()
                
                for (element in jsonArray) {
                    if (element.isJsonObject) {
                        val obj = element.asJsonObject
                        val map = mutableMapOf<String, Any?>()
                        
                        for ((key, value) in obj.entrySet()) {
                            map[key] = when {
                                value.isJsonNull -> null
                                value.isJsonPrimitive -> {
                                    val prim = value.asJsonPrimitive
                                    when {
                                        prim.isNumber -> prim.asNumber
                                        prim.isBoolean -> prim.asBoolean
                                        prim.isString -> prim.asString
                                        else -> prim.toString()
                                    }
                                }
                                else -> value.toString()
                            }
                        }
                        
                        resultList.add(map)
                    }
                }
                
                Log.d("SupabaseRepository", "searchVideos found ${resultList.size} results")
                return resultList
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error searching videos", e)
            emptyList()
        }
    }
    
    /**
     * Recalcula el progreso (tareas_totales, tareas_completadas, porcentaje_progreso, promedio)
     * para todos los estudiantes inscritos en un curso específico.
     * Útil después de agregar o eliminar tareas.
     */
    suspend fun recalculateAllStudentProgressForCourse(courseId: Long): Boolean {
        return try {
            Log.d("SupabaseRepository", "Recalculating progress for all students in course $courseId")
            
            // 1. Obtener total de tareas en el curso
            val totalTasksSql = """
                SELECT COUNT(*) as total
                FROM tasks tk
                JOIN topics t ON tk.topic_id = t.id
                WHERE t.course_id = $courseId
            """.trimIndent()
            
            val totalTasksResult = executeRawQuery(totalTasksSql)
            val totalTasks = (totalTasksResult.firstOrNull()?.get("total") as? Number)?.toInt() ?: 0
            Log.d("SupabaseRepository", "Total tasks in course $courseId: $totalTasks")
            
            // 2. Obtener todos los estudiantes inscritos
            val enrolledStudentsSql = """
                SELECT usuario_estudiante 
                FROM progreso_estudiante 
                WHERE curso_id = $courseId
            """.trimIndent()
            
            val enrolledStudents = executeRawQuery(enrolledStudentsSql)
            Log.d("SupabaseRepository", "Found ${enrolledStudents.size} enrolled students")
            
            if (enrolledStudents.isEmpty()) {
                Log.w("SupabaseRepository", "No enrolled students found for course $courseId")
                return true
            }
            
            // 3. Para cada estudiante, calcular y actualizar su progreso
            var updatedCount = 0
            for (student in enrolledStudents) {
                val studentId = (student["usuario_estudiante"] as? Number)?.toLong() ?: continue
                
                try {
                    // Calcular tareas completadas (grade > 0)
                    val completedSql = """
                        SELECT COUNT(*) as completed
                        FROM task_submissions ts
                        JOIN tasks tk ON ts.task_id = tk.id
                        JOIN topics t ON tk.topic_id = t.id
                        WHERE ts.student_id = $studentId
                        AND t.course_id = $courseId
                        AND ts.grade IS NOT NULL
                    """.trimIndent()
                    
                    val completedResult = executeRawQuery(completedSql)
                    val completedTasks = (completedResult.firstOrNull()?.get("completed") as? Number)?.toInt() ?: 0
                    
                    // Calcular promedio de calificaciones
                    val avgSql = """
                        SELECT 
                            COALESCE(SUM(ts.grade), 0) as total_grade,
                            COUNT(*) as task_count
                        FROM task_submissions ts
                        JOIN tasks tk ON ts.task_id = tk.id
                        JOIN topics t ON tk.topic_id = t.id
                        WHERE ts.student_id = $studentId
                        AND t.course_id = $courseId
                    """.trimIndent()
                    
                    val avgResult = executeRawQuery(avgSql)
                    val totalGrade = (avgResult.firstOrNull()?.get("total_grade") as? Number)?.toFloat() ?: 0f
                    val taskCount = (avgResult.firstOrNull()?.get("task_count") as? Number)?.toInt() ?: 0
                    
                    // Calcular porcentaje y promedio
                    val progressPct = if (totalTasks > 0) (completedTasks.toFloat() / totalTasks.toFloat()) * 100 else 0f
                    val avgGrade = if (taskCount > 0) totalGrade / taskCount else 0f
                    
                    // Format floats with dot separator to avoid SQL syntax errors in locales using comma
                    val progressPctStr = String.format(java.util.Locale.US, "%.2f", progressPct)
                    val avgGradeStr = String.format(java.util.Locale.US, "%.2f", avgGrade)
                    
                    // Actualizar progreso del estudiante usando API REST PATCH (no SQL UPDATE)
                    updateProgresoEstudiante(
                        studentId = studentId,
                        courseId = courseId,
                        totalTasks = totalTasks,
                        completedTasks = completedTasks,
                        progressPct = progressPct,
                        avgGrade = avgGrade
                    )
                    updatedCount++
                    Log.d("SupabaseRepository", "Updated student $studentId: $completedTasks/$totalTasks tasks, $progressPct%, avg=$avgGrade")
                    
                } catch (e: Exception) {
                    Log.w("SupabaseRepository", "Error updating progress for student $studentId", e)
                }
            }
            
            Log.i("SupabaseRepository", "Successfully recalculated progress for $updatedCount students in course $courseId")
            true
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error recalculating student progress", e)
            false
        }
    }

    /**
     * Actualiza el progreso de un estudiante en un curso usando la API REST PATCH de Supabase
     * Esto evita usar SQL UPDATE que no funciona bien con el endpoint execute_sql
     */
    private suspend fun updateProgresoEstudiante(
        studentId: Long,
        courseId: Long,
        totalTasks: Int,
        completedTasks: Int,
        progressPct: Float,
        avgGrade: Float
    ): Boolean {
        return try {
            // Use REST API PATCH to update the record
            val url = "${supabaseUrl.trimEnd('/')}/rest/v1/progreso_estudiante?usuario_estudiante=eq.$studentId&curso_id=eq.$courseId"
            
            val payload = mapOf(
                "tareas_totales" to totalTasks,
                "tareas_completadas" to completedTasks,
                "porcentaje_progreso" to progressPct,
                "promedio" to avgGrade,
                "calificacion_ponderada" to avgGrade,
                "ultima_calculada_en" to java.time.OffsetDateTime.now().toString()
            )
            
            val bodyJson = gson.toJson(payload)
            
            val request = Request.Builder()
                .url(url)
                .patch(bodyJson.toRequestBody(jsonMediaType))
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=minimal")
                .build()
            
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val respBody = resp.body?.string()
                    Log.e("SupabaseRepository", "Failed to update progreso_estudiante: code=${resp.code} body=$respBody")
                    return false
                }
                Log.d("SupabaseRepository", "Successfully updated progreso_estudiante for student $studentId in course $courseId")
                true
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error updating progreso_estudiante", e)
            false
        }
    }

    /**
     * Increment like count for a video comment
     * Checks if user already liked the comment to prevent duplicates or toggles it
     * Uses a transaction or direct inserts to `video_comment_likes`
     */
    suspend fun likeVideoComment(commentId: Long, userId: Long): Boolean {
        return try {
            // Check if already liked
            val checkSql = "SELECT id FROM video_comment_likes WHERE comment_id = $commentId AND usuario_id = $userId"
            val existing = executeRawQuery(checkSql)
            
            if (existing.isNotEmpty()) {
                // Already liked -> Unlike (Delete)
                val deleteSql = "DELETE FROM video_comment_likes WHERE comment_id = $commentId AND usuario_id = $userId"
                executeRawQuery(deleteSql)
                Log.d("SupabaseRepository", "Unliked comment $commentId by user $userId")
                return false // Return false to indicate unliked state
            } else {
                // Not liked -> Like (Insert)
                val insertSql = "INSERT INTO video_comment_likes (comment_id, usuario_id) VALUES ($commentId, $userId)"
                executeRawQuery(insertSql)
                Log.d("SupabaseRepository", "Liked comment $commentId by user $userId")
                return true // Return true to indicate liked state
            }
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error toggling comment like", e)
            false // Assume failure means no change, but return false
        }
    }
    
    /**
     * Get like count for a comment
     */
    suspend fun getCommentLikeCount(commentId: Long): Int {
        return try {
            val sql = "SELECT COUNT(*) as count FROM video_comment_likes WHERE comment_id = $commentId"
            val result = executeRawQuery(sql)
            (result.firstOrNull()?.get("count") as? Number)?.toInt() ?: 0
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error getting comment like count", e)
            0
        }
    }

    /**
     * Check if user liked a comment
     */
    suspend fun hasUserLikedComment(commentId: Long, userId: Long): Boolean {
        return try {
            val sql = "SELECT id FROM video_comment_likes WHERE comment_id = $commentId AND usuario_id = $userId"
            val result = executeRawQuery(sql)
            result.isNotEmpty()
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error checking comment like status", e)
            false
        }
    }
    
    /**
     * Fetch all submissions for a course including student usernames
     * This uses a raw SQL query to join task_submissions, tasks, topics, and usuarios tables
     */
    suspend fun fetchCourseSubmissionsWithUsernames(courseId: Long): List<Map<String, Any?>> {
        return try {
            val sql = """
                SELECT 
                    ts.id as submission_id,
                    ts.task_id,
                    tk.title as task_title,
                    ts.student_id,
                    u.username as student_username,
                    ts.grade,
                    ts.submission_date,
                    ts.file_uri
                FROM task_submissions ts
                JOIN tasks tk ON ts.task_id = tk.id
                JOIN topics t ON tk.topic_id = t.id
                LEFT JOIN usuarios u ON ts.student_id = u.id
                WHERE t.course_id = $courseId
                ORDER BY ts.submission_date DESC
            """.trimIndent()
            
            executeRawQuery(sql)
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error fetching course submissions with usernames", e)
            emptyList()
        }
    }

    /**
     * Fetch videos created by a specific user (via courses relation) using a JOIN
     */
    suspend fun fetchVideosByCreatorUsername(username: String): List<Map<String, Any?>> {
        return try {
            val sanitizedUsername = username.trim().replace("'", "''")
            
            val sql = """
                SELECT v.*, c.title as course_title, u.username as creator_username
                FROM videos v
                JOIN courses c ON v.course_id = c.id
                JOIN usuarios u ON c.creator_user_id = u.id
                WHERE u.username = '$sanitizedUsername'
                ORDER BY v.id DESC
            """.trimIndent()
            
            executeRawQuery(sql)
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error fetching videos by creator", e)
            emptyList()
        }
    }

    /**
     * Delete a topic from Supabase
     */
    suspend fun deleteTopic(topicId: Long): Boolean {
        return try {
            val url = "$supabaseUrl/rest/v1/topics?id=eq.$topicId"
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Prefer", "return=minimal")
                .build()

            val response = executeRequestWithRetries(request)
            val success = response?.isSuccessful == true
            response?.close()
            
            if (success) {
                Log.d("SupabaseRepository", "Successfully deleted topic $topicId from Supabase")
            } else {
                Log.w("SupabaseRepository", "Failed to delete topic $topicId: ${response?.code}")
            }
            success
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error deleting topic $topicId", e)
            false
        }
    }

    /**
     * Delete a task from Supabase
     */
    suspend fun deleteTask(taskId: Long): Boolean {
        return try {
            val url = "$supabaseUrl/rest/v1/tasks?id=eq.$taskId"
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Prefer", "return=minimal")
                .build()

            val response = executeRequestWithRetries(request)
            val success = response?.isSuccessful == true
            response?.close()
            
            if (success) {
                Log.d("SupabaseRepository", "Successfully deleted task $taskId from Supabase")
            } else {
                Log.w("SupabaseRepository", "Failed to delete task $taskId: ${response?.code}")
            }
            success
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error deleting task $taskId", e)
            false
        }
    }

    /**
     * Delete a content item from Supabase
     */
    suspend fun deleteContentItem(contentItemId: Long): Boolean {
        return try {
            val url = "$supabaseUrl/rest/v1/content_items?id=eq.$contentItemId"
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Prefer", "return=minimal")
                .build()

            val response = executeRequestWithRetries(request)
            val success = response?.isSuccessful == true
            response?.close()
            
            if (success) {
                Log.d("SupabaseRepository", "Successfully deleted content item $contentItemId from Supabase")
            } else {
                Log.w("SupabaseRepository", "Failed to delete content item $contentItemId: ${response?.code}")
            }
            success
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error deleting content item $contentItemId", e)
            false
        }
    }

}
