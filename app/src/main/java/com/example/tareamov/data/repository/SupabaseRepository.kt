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
        return try {
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
            
            val sql = """
                DO $$
                DECLARE
                    enrolled_student RECORD;
                    total_tasks INTEGER;
                    completed_tasks INTEGER;
                    progress_pct REAL;
                    total_grade NUMERIC;
                    task_count INTEGER;
                    avg_grade NUMERIC;
                BEGIN
                    -- Calcular total de tareas en el curso
                    SELECT COUNT(*) INTO total_tasks
                    FROM tasks tk
                    JOIN topics t ON tk.topic_id = t.id
                    WHERE t.course_id = $courseId;
                    
                    -- Para cada estudiante inscrito
                    FOR enrolled_student IN 
                        SELECT usuario_estudiante 
                        FROM progreso_estudiante 
                        WHERE curso_id = $courseId
                    LOOP
                        -- Calcular tareas completadas (grade > 0)
                        SELECT COUNT(*) INTO completed_tasks
                        FROM task_submissions ts
                        JOIN tasks tk ON ts.task_id = tk.id
                        JOIN topics t ON tk.topic_id = t.id
                        WHERE ts.student_username = enrolled_student.usuario_estudiante
                        AND t.course_id = $courseId
                        AND ts.grade > 0;
                        
                        -- Calcular porcentaje de progreso
                        IF total_tasks > 0 THEN
                            progress_pct := (completed_tasks::REAL / total_tasks::REAL) * 100;
                        ELSE
                            progress_pct := 0;
                        END IF;
                        
                        -- Calcular promedio
                        SELECT 
                            COALESCE(SUM(ts.grade), 0),
                            COUNT(*)
                        INTO total_grade, task_count
                        FROM task_submissions ts
                        JOIN tasks tk ON ts.task_id = tk.id
                        JOIN topics t ON tk.topic_id = t.id
                        WHERE ts.student_username = enrolled_student.usuario_estudiante
                        AND t.course_id = $courseId;
                        
                        IF task_count > 0 THEN
                            avg_grade := total_grade / task_count;
                        ELSE
                            avg_grade := 0;
                        END IF;
                        
                        -- Actualizar progreso
                        UPDATE progreso_estudiante
                        SET 
                            tareas_totales = total_tasks,
                            tareas_completadas = completed_tasks,
                            porcentaje_progreso = progress_pct,
                            promedio = avg_grade,
                            calificacion_ponderada = avg_grade,
                            ultima_calculada_en = NOW()
                        WHERE usuario_estudiante = enrolled_student.usuario_estudiante
                        AND curso_id = $courseId;
                        
                        RAISE NOTICE 'Updated % - Tasks: %/%, Progress: %%, Avg: %',
                            enrolled_student.usuario_estudiante, completed_tasks, total_tasks, progress_pct, avg_grade;
                    END LOOP;
                END $$;
            """.trimIndent()
            
            executeRawQuery(sql)
            Log.i("SupabaseRepository", "Successfully recalculated progress for course $courseId")
            true
        } catch (e: Exception) {
            Log.e("SupabaseRepository", "Error recalculating student progress", e)
            false
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


}
