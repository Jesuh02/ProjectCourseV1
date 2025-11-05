package com.example.tareamov.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * DatabaseSchemaService
 * 
 * Este servicio obtiene dinámicamente el esquema completo de la base de datos de Supabase
 * consultando las tablas information_schema de PostgreSQL.
 * 
 * Proporciona al LLM contexto actualizado sobre:
 * - Tablas existentes
 * - Columnas y sus tipos de datos
 * - Relaciones (Foreign Keys)
 * - Constraints (Primary Keys, Unique, etc.)
 * 
 * IMPORTANTE: Si la estructura de la BD en Supabase cambia, este contexto
 * se actualiza automáticamente en la siguiente consulta.
 */
class DatabaseSchemaService(private val context: Context) {
    
    companion object {
        private const val TAG = "DatabaseSchemaService"
        // ⚡ Cache reducido a 30 segundos para mayor frescura de datos
        private const val CACHE_DURATION_MS = 30 * 1000L // Cache por 30 segundos (antes 5 minutos)
    }
    
    // Cache del esquema para evitar consultas repetidas
    private var cachedSchema: String? = null
    private var lastFetchTime: Long = 0
    // Hash del esquema para detectar cambios estructurales
    private var lastSchemaHash: Int = 0
    
    /**
     * Obtiene el esquema completo de la base de datos de Supabase.
     * Usa cache de 30 segundos para optimizar rendimiento.
     * Detecta cambios estructurales automáticamente mediante hash.
     * 
     * @param forceRefresh Si es true, ignora el cache y consulta Supabase nuevamente
     * @return String con el DDL completo en formato legible para el LLM
     */
    suspend fun getDatabaseSchema(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        try {
            val now = System.currentTimeMillis()
            
            // 🔍 DEBUG: Verificar configuración de Supabase
            val isConfigured = SupabaseClient.isConfigured()
            Log.d(TAG, "🔍 DEBUG - Supabase isConfigured: $isConfigured")
            
            // Verificar si hay cache válido y no se solicita refresh forzado
            if (!forceRefresh && cachedSchema != null && (now - lastFetchTime) < CACHE_DURATION_MS) {
                Log.d(TAG, "✓ Returning cached schema (age: ${(now - lastFetchTime) / 1000}s)")
                return@withContext cachedSchema!!
            }
            
            Log.d(TAG, "⟳ Fetching fresh schema from Supabase...")
            
            // Construir esquema detallado consultando Supabase
            val schema = buildDetailedSchema()
            val schemaHash = schema.hashCode()
            
            // 🔍 DEBUG: Verificar si está usando fallback
            val isFallback = schema.contains("FALLBACK MODE")
            val isRealTime = schema.contains("TIEMPO REAL")
            Log.d(TAG, "🔍 DEBUG - Schema contains 'FALLBACK MODE': $isFallback")
            Log.d(TAG, "🔍 DEBUG - Schema contains 'TIEMPO REAL': $isRealTime")
            Log.d(TAG, "🔍 DEBUG - Schema length: ${schema.length} characters")
            
            // Detectar cambios estructurales
            if (lastSchemaHash != 0 && lastSchemaHash != schemaHash) {
                Log.w(TAG, "⚠️ SCHEMA CHANGE DETECTED! Database structure has changed in Supabase")
            }
            
            // Guardar en cache
            cachedSchema = schema
            lastFetchTime = now
            lastSchemaHash = schemaHash
            
            Log.d(TAG, "✓ Schema refreshed and cached (${schema.length} chars, hash=$schemaHash)")
            return@withContext schema
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching database schema", e)
            e.printStackTrace()  // 🔍 DEBUG: Mostrar stacktrace completo
            // Si falla, intentar devolver cache aunque esté vencido
            if (cachedSchema != null) {
                Log.w(TAG, "⚠️ Returning stale cached schema due to error")
                return@withContext cachedSchema!!
            }
            // Si no hay cache, devolver esquema mínimo de fallback
            Log.w(TAG, "⚠️ Using fallback schema")
            return@withContext getFallbackSchema()
        }
    }
    
    /**
     * Construye el esquema detallado consultando directamente a Supabase.
     * Este método consulta information_schema de PostgreSQL en tiempo real.
     * 
     * ⚡ ACTUALIZACIÓN DINÁMICA: Si añades tablas, columnas o relaciones en Supabase,
     * este método las detectará automáticamente en la siguiente consulta (30 segundos).
     * 
     * @return String con el esquema completo en formato optimizado para LLM
     */
    private suspend fun buildDetailedSchema(): String = withContext(Dispatchers.IO) {
        return@withContext buildString {
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine("ESQUEMA DE LA BASE DE DATOS (PostgreSQL/Supabase)")
            appendLine("⚡ Consultado en TIEMPO REAL desde Supabase")
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine()
            
            // ⚠️ CRÍTICO: Información sobre tasks vs task_submissions SIEMPRE al inicio
            appendLine("⚠️⚠️⚠️ DIFERENCIA CRÍTICA: tasks vs task_submissions ⚠️⚠️⚠️")
            appendLine()
            appendLine("• tasks = DEFINICIONES/PLANTILLAS de tareas (lo que el profesor crea)")
            appendLine("• task_submissions = ENTREGAS de estudiantes (lo que el alumno envía)")
            appendLine()
            appendLine("REGLA DE ORO:")
            appendLine("  'tarea ENVIADA con id=X' → task_submissions.id = X")
            appendLine("  'entrega con id=X' → task_submissions.id = X")
            appendLine("  'usuario ha ENVIADO tarea con id=X' → task_submissions.id = X")
            appendLine()
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine()
            
            // 1. Obtener todas las tablas del esquema 'public' desde Supabase
            val tables = fetchTables()
            appendLine("📊 TABLAS DISPONIBLES (${tables.size} tablas detectadas):")
            tables.forEachIndexed { index, table ->
                appendLine("  ${index + 1}. $table")
            }
            appendLine()
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine()
            
            // 2. Para cada tabla, obtener sus columnas y relaciones desde Supabase
            tables.forEach { tableName ->
                appendLine("📋 TABLA: public.$tableName")
                appendLine("${"─".repeat(70)}")
                
                val columns = fetchColumns(tableName)
                appendLine("Columnas (${columns.size}):")
                columns.forEach { col ->
                    val pkMarker = if (col.isPrimaryKey) " 🔑 PRIMARY KEY" else ""
                    val nullMarker = if (col.isNullable) "NULL" else "NOT NULL"
                    val defaultInfo = if (col.defaultValue != null) " DEFAULT ${col.defaultValue}" else ""
                    appendLine("  • ${col.name}: ${col.dataType} $nullMarker$pkMarker$defaultInfo")
                }
                appendLine()
                
                // 3. Obtener Foreign Keys de esta tabla desde Supabase
                val foreignKeys = fetchForeignKeys(tableName)
                if (foreignKeys.isNotEmpty()) {
                    appendLine("🔗 Relaciones (Foreign Keys):")
                    foreignKeys.forEach { fk ->
                        val deleteRule = if (fk.onDelete != null) " [ON DELETE ${fk.onDelete}]" else ""
                        appendLine("  • ${fk.columnName} ─→ ${fk.referencedTable}.${fk.referencedColumn}$deleteRule")
                    }
                    appendLine()
                }
                
                appendLine()
            }
            
            // 4. Resumen de relaciones entre tablas
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine("🔗 RESUMEN DE RELACIONES (Grafo de dependencias)")
            appendLine("═══════════════════════════════════════════════════════════════════")
            tables.forEach { tableName ->
                val fks = fetchForeignKeys(tableName)
                if (fks.isNotEmpty()) {
                    appendLine("$tableName:")
                    fks.forEach { fk ->
                        appendLine("  ├─→ ${fk.referencedTable} (via ${fk.columnName})")
                    }
                }
            }
            appendLine()
            
            // 5. Cadena de relaciones crítica para task_submissions
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine("⚡ CADENA DE RELACIONES CRÍTICA (para consultas de entregas)")
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine()
            appendLine("task_submissions.task_id → tasks.id")
            appendLine("         ↓")
            appendLine("tasks.topic_id → topics.id")
            appendLine("         ↓")
            appendLine("topics.course_id → courses.id (contiene creator_username)")
            appendLine()
            appendLine("SQL EJEMPLO (obtener dueño del curso de una entrega):")
            appendLine("SELECT c.creator_username")
            appendLine("FROM public.task_submissions ts")
            appendLine("JOIN public.tasks t ON ts.task_id = t.id")
            appendLine("JOIN public.topics tp ON t.topic_id = tp.id")
            appendLine("JOIN public.courses c ON tp.course_id = c.id")
            appendLine("WHERE ts.id = ?;  -- ID de la ENTREGA, NO de la tarea")
            appendLine()
            appendLine("═══════════════════════════════════════════════════════════════════")
            appendLine("✓ FIN DEL ESQUEMA - Actualizado desde Supabase en tiempo real")
            appendLine("═══════════════════════════════════════════════════════════════════")
        }
    }
    
    /**
     * Consulta las tablas del esquema 'public' dinámicamente usando RPC de Supabase.
     * Si falla, usa lista estática como fallback.
     */
    private suspend fun fetchTables(): List<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "⟳ Fetching tables from Supabase via RPC...")
            
            // Verificar que Supabase esté configurado
            if (!SupabaseClient.isConfigured()) {
                Log.w(TAG, "⚠️ Supabase not configured, using fallback static list")
                return@withContext getFallbackTableList()
            }
            
            // Intentar consultar dinámicamente usando RPC
            val response = SupabaseClient.callRpcFunction("get_all_tables", emptyMap())
            
            Log.d(TAG, "RPC Response for get_all_tables: ${response?.take(500) ?: "null"}")
            
            if (response != null && response.isNotEmpty() && response != "null") {
                val tables = parseTableNames(response)
                if (tables.isNotEmpty()) {
                    Log.d(TAG, "✓ Fetched ${tables.size} tables dynamically from Supabase: $tables")
                    return@withContext tables
                } else {
                    Log.w(TAG, "⚠️ RPC returned data but parsing resulted in empty list")
                }
            } else {
                Log.w(TAG, "⚠️ RPC returned null/empty - Function might not exist in Supabase")
            }
            
            // Fallback: lista estática
            Log.w(TAG, "⚠️ Using fallback static list")
            getFallbackTableList()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error fetching tables dynamically, using fallback", e)
            e.printStackTrace()
            getFallbackTableList()
        }
    }
    
    /**
     * Lista de fallback con las 14 tablas conocidas del sistema
     */
    private fun getFallbackTableList(): List<String> {
        val knownTables = listOf(
            "personas",
            "usuarios", 
            "videos",
            "topics",
            "content_items",
            "tasks",
            "subscriptions",
            "task_submissions",
            "chat_messages",
            "file_contexts",
            "courses",
            "roles",
            "recursos",
            "rol_recursos"
        )
        Log.d(TAG, "✓ Using fallback table list (${knownTables.size} tables)")
        return knownTables
    }
    
    /**
     * Consulta las columnas dinámicamente usando RPC de Supabase.
     * Si falla, usa mapeo estático como fallback.
     */
    private suspend fun fetchColumns(tableName: String): List<ColumnInfo> = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "⟳ Fetching columns for $tableName from Supabase...")
            
            // Verificar que Supabase esté configurado
            if (!SupabaseClient.isConfigured()) {
                Log.w(TAG, "  ⚠️ Supabase not configured for $tableName, using fallback")
                return@withContext getFallbackColumns(tableName)
            }
            
            // Intentar consultar dinámicamente usando RPC
            val params = mapOf("p_table_name" to tableName)
            val response = SupabaseClient.callRpcFunction("get_table_columns", params)
            
            Log.d(TAG, "  RPC Response for $tableName columns: ${response?.take(200) ?: "null"}")
            
            if (response != null && response.isNotEmpty() && response != "null") {
                val columns = parseColumns(response)
                if (columns.isNotEmpty()) {
                    Log.d(TAG, "  ✓ $tableName: ${columns.size} columns fetched dynamically")
                    return@withContext columns
                } else {
                    Log.w(TAG, "  ⚠️ RPC returned data but parsing resulted in empty columns for $tableName")
                }
            } else {
                Log.w(TAG, "  ⚠️ RPC returned null/empty for $tableName columns")
            }
            
            // Fallback: mapeo estático
            Log.w(TAG, "  ⚠️ Using fallback for $tableName")
            getFallbackColumns(tableName)
        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Error fetching columns for $tableName, using fallback", e)
            e.printStackTrace()
            getFallbackColumns(tableName)
        }
    }
    
    /**
     * Mapeo estático de columnas como fallback
     */
    private fun getFallbackColumns(tableName: String): List<ColumnInfo> {
        val tableColumns = when(tableName) {
            "personas" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("identificacion", "text", false, null, false),
                    ColumnInfo("nombres", "text", false, null, false),
                    ColumnInfo("apellidos", "text", false, null, false),
                    ColumnInfo("email", "text", false, null, false),
                    ColumnInfo("telefono", "text", true, null, false),
                    ColumnInfo("direccion", "text", true, null, false),
                    ColumnInfo("fecha_nacimiento", "text", true, null, false),
                    ColumnInfo("avatar", "text", true, null, false),
                    ColumnInfo("es_usuario", "boolean", true, "false", false)
            )
            "usuarios" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("usuario", "text", false, null, false),
                    ColumnInfo("contrasena", "text", false, null, false),
                    ColumnInfo("persona_id", "bigint", true, null, false),
                    ColumnInfo("rol_id", "bigint", true, null, false)
            )
            "roles" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("nombre", "text", false, null, false),
                    ColumnInfo("nivel", "integer", true, null, false),
                    ColumnInfo("default", "boolean", true, "false", false)
            )
            "recursos" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("nombre", "text", false, null, false),
                    ColumnInfo("descripcion", "text", true, null, false)
            )
            "rol_recursos" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("rol_id", "bigint", false, null, false),
                    ColumnInfo("recurso_id", "bigint", false, null, false),
                    ColumnInfo("puede_leer", "boolean", true, "true", false),
                    ColumnInfo("puede_escribir", "boolean", true, "false", false),
                    ColumnInfo("puede_eliminar", "boolean", true, "false", false)
            )
            "videos" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("username", "text", false, null, false),
                    ColumnInfo("description", "text", true, null, false),
                    ColumnInfo("title", "text", false, null, false),
                    ColumnInfo("video_uri_string", "text", true, null, false),
                    ColumnInfo("local_file_path", "text", true, null, false),
                    ColumnInfo("timestamp", "text", true, null, false),
                    ColumnInfo("is_paid", "boolean", true, "false", false),
                    ColumnInfo("thumbnail_uri", "text", true, null, false),
                    ColumnInfo("price", "double precision", true, "0.0", false)
            )
            "courses" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("title", "text", false, null, false),
                    ColumnInfo("description", "text", true, null, false),
                    ColumnInfo("creator_username", "text", false, null, false),
                    ColumnInfo("thumbnail_uri", "text", true, null, false),
                    ColumnInfo("video_uri", "text", true, null, false),
                    ColumnInfo("price", "double precision", true, "0.0", false),
                    ColumnInfo("is_premium", "boolean", true, "false", false),
                    ColumnInfo("is_published", "boolean", true, "true", false),
                    ColumnInfo("timestamp", "text", true, null, false)
            )
            "topics" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("course_id", "bigint", false, null, false),
                    ColumnInfo("name", "text", false, null, false),
                    ColumnInfo("description", "text", true, null, false),
                    ColumnInfo("order_index", "integer", true, "0", false)
            )
            "tasks" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("topic_id", "bigint", false, null, false),
                    ColumnInfo("title", "text", false, null, false),
                    ColumnInfo("description", "text", true, null, false),
                    ColumnInfo("due_date", "text", true, null, false)
            )
            "task_submissions" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("task_id", "bigint", false, null, false),
                    ColumnInfo("student_username", "text", false, null, false),
                    ColumnInfo("file_uri", "text", true, null, false),
                    ColumnInfo("file_name", "text", true, null, false),
                    ColumnInfo("submission_date", "text", true, null, false),
                    ColumnInfo("grade", "real", true, null, false),
                    ColumnInfo("feedback", "text", true, null, false)
            )
            "content_items" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("topic_id", "bigint", false, null, false),
                    ColumnInfo("title", "text", false, null, false),
                    ColumnInfo("body", "text", true, null, false),
                    ColumnInfo("content_type", "text", true, "'text'", false)
            )
            "subscriptions" -> listOf(
                    ColumnInfo("subscriber_username", "text", false, null, true),
                    ColumnInfo("creator_username", "text", false, null, true),
                    ColumnInfo("subscription_date", "text", true, null, false)
            )
            "chat_messages" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("message", "text", false, null, false),
                    ColumnInfo("is_from_user", "boolean", true, "true", false),
                    ColumnInfo("timestamp", "text", true, null, false),
                    ColumnInfo("session_id", "text", true, null, false)
            )
            "file_contexts" -> listOf(
                    ColumnInfo("id", "bigint", false, null, true),
                    ColumnInfo("submission_id", "bigint", true, null, false),
                    ColumnInfo("file_name", "text", false, null, false),
                    ColumnInfo("file_type", "text", true, null, false),
                    ColumnInfo("file_content", "text", true, null, false),
                    ColumnInfo("extracted_text", "text", true, null, false),
                    ColumnInfo("json_content", "text", true, null, false),
                    ColumnInfo("content_summary", "text", true, null, false)
            )
            else -> emptyList()
        }
        
        Log.d(TAG, "  ✓ $tableName: ${tableColumns.size} columns (fallback)")
        return tableColumns
    }
    
    /**
     * Consulta los Foreign Keys dinámicamente usando RPC de Supabase.
     * Si falla, usa mapeo estático como fallback.
     */
    private suspend fun fetchForeignKeys(tableName: String): List<ForeignKeyInfo> = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "⟳ Fetching foreign keys for $tableName from Supabase...")
            
            // Verificar que Supabase esté configurado
            if (!SupabaseClient.isConfigured()) {
                Log.w(TAG, "  ⚠️ Supabase not configured for $tableName FKs, using fallback")
                return@withContext getFallbackForeignKeys(tableName)
            }
            
            // Intentar consultar dinámicamente usando RPC
            val params = mapOf("p_table_name" to tableName)
            val response = SupabaseClient.callRpcFunction("get_table_foreign_keys", params)
            
            Log.d(TAG, "  RPC Response for $tableName FKs: ${response?.take(200) ?: "null"}")
            
            if (response != null) {
                val fks = parseForeignKeys(response)
                // Nota: response == "[]" es válido (tabla sin FKs)
                if (fks.isNotEmpty() || response == "[]" || response == "null") {
                    Log.d(TAG, "  ✓ $tableName: ${fks.size} foreign keys fetched dynamically")
                    return@withContext fks
                } else {
                    Log.w(TAG, "  ⚠️ RPC returned unexpected format for $tableName FKs")
                }
            } else {
                Log.w(TAG, "  ⚠️ RPC returned null for $tableName FKs")
            }
            
            // Fallback: mapeo estático
            Log.w(TAG, "  ⚠️ Using fallback for $tableName FKs")
            getFallbackForeignKeys(tableName)
        } catch (e: Exception) {
            Log.e(TAG, "  ❌ Error fetching FKs for $tableName, using fallback", e)
            e.printStackTrace()
            getFallbackForeignKeys(tableName)
        }
    }
    
    /**
     * Mapeo estático de Foreign Keys como fallback
     * IMPORTANTE: Estas relaciones deben coincidir EXACTAMENTE con Estructura.sql
     */
    private fun getFallbackForeignKeys(tableName: String): List<ForeignKeyInfo> {
        val foreignKeys = when(tableName) {
            "chat_messages" -> listOf(
                ForeignKeyInfo("usuario_id", "usuarios", "id", "NO ACTION")
            )
            "content_items" -> listOf(
                ForeignKeyInfo("creator_usuario_id", "usuarios", "id", "NO ACTION"),
                ForeignKeyInfo("task_id", "tasks", "id", "NO ACTION")
            )
            "courses" -> listOf(
                ForeignKeyInfo("creator_username", "usuarios", "usuario", "NO ACTION")
            )
            "file_contexts" -> listOf(
                ForeignKeyInfo("submission_id", "task_submissions", "id", "NO ACTION")
            )
            "recursos" -> listOf(
                ForeignKeyInfo("padre_id", "recursos", "id", "NO ACTION")
            )
            "rol_recursos" -> listOf(
                ForeignKeyInfo("rol_id", "roles", "id", "NO ACTION"),
                ForeignKeyInfo("recurso_id", "recursos", "id", "NO ACTION")
            )
            "subscriptions" -> listOf(
                ForeignKeyInfo("subscriber_username", "usuarios", "usuario", "NO ACTION"),
                ForeignKeyInfo("creator_username", "usuarios", "usuario", "NO ACTION")
            )
            "task_submissions" -> listOf(
                ForeignKeyInfo("task_id", "tasks", "id", "NO ACTION")
            )
            "tasks" -> listOf(
                ForeignKeyInfo("topic_id", "topics", "id", "NO ACTION")
            )
            "topics" -> listOf(
                // ⚠️ IMPORTANTE: topics.course_id → videos.id (NO courses.id)
                ForeignKeyInfo("course_id", "videos", "id", "NO ACTION")
            )
            "usuarios" -> listOf(
                ForeignKeyInfo("rol_id", "roles", "id", "NO ACTION"),
                ForeignKeyInfo("persona_id", "personas", "id", "NO ACTION")
            )
            // "personas", "roles", "videos" no tienen FKs
            else -> emptyList()
        }
        
        Log.d(TAG, "  ✓ $tableName: ${foreignKeys.size} foreign keys (fallback)")
        return foreignKeys
    }
    
    // === Métodos de parsing ===
    
    private fun parseTableNames(jsonResult: String): List<String> {
        return try {
            val jsonArray = JsonParser.parseString(jsonResult).asJsonArray
            jsonArray.map { it.asJsonObject.get("table_name").asString }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing table names", e)
            emptyList()
        }
    }
    
    private fun parseColumns(jsonResult: String): List<ColumnInfo> {
        return try {
            val jsonArray = JsonParser.parseString(jsonResult).asJsonArray
            jsonArray.map { obj ->
                val json = obj.asJsonObject
                
                // Helper para extraer string de forma segura (maneja JsonNull)
                val defaultVal = json.get("column_default")?.let { element ->
                    if (element.isJsonNull) null else element.asString
                }
                
                ColumnInfo(
                    name = json.get("column_name").asString,
                    dataType = json.get("data_type").asString,
                    isNullable = json.get("is_nullable").asString == "YES",
                    defaultValue = defaultVal,
                    isPrimaryKey = json.get("is_primary_key")?.asBoolean ?: false
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing columns", e)
            emptyList()
        }
    }
    
    private fun parseForeignKeys(jsonResult: String): List<ForeignKeyInfo> {
        return try {
            val jsonArray = JsonParser.parseString(jsonResult).asJsonArray
            jsonArray.map { obj ->
                val json = obj.asJsonObject
                
                // Helper para extraer string de forma segura (maneja JsonNull)
                val deleteRule = json.get("delete_rule")?.let { element ->
                    if (element.isJsonNull) null else element.asString
                }
                
                ForeignKeyInfo(
                    columnName = json.get("column_name").asString,
                    referencedTable = json.get("referenced_table").asString,
                    referencedColumn = json.get("referenced_column").asString,
                    onDelete = deleteRule
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing foreign keys", e)
            emptyList()
        }
    }
    
    /**
     * Esquema de fallback en caso de que falle la consulta a Supabase.
     * Contiene las tablas principales del sistema con información crítica sobre tasks vs task_submissions.
     */
    private fun getFallbackSchema(): String {
        return """
═══════════════════════════════════════════════════════════════════
ESQUEMA DE LA BASE DE DATOS (PostgreSQL/Supabase) - FALLBACK MODE
═══════════════════════════════════════════════════════════════════

⚠️⚠️⚠️ DIFERENCIA CRÍTICA: tasks vs task_submissions ⚠️⚠️⚠️

8. TABLA: tasks
   Propósito: DEFINICIÓN/PLANTILLA de tareas asignadas a un tema
   ⚠️ Esta tabla contiene la DEFINICIÓN de la tarea, NO las entregas
   Columnas principales: id, topic_id, title, description, due_date

9. TABLA: task_submissions
   Propósito: ENTREGAS/ENVÍOS de tareas realizadas por estudiantes
   ⚠️ Esta tabla contiene las ENTREGAS individuales de estudiantes
   ⚠️ El 'id' aquí es el ID de la ENTREGA, NO de la definición
   Columnas principales: id, task_id, student_username, file_uri, 
                        file_name, submission_date, grade, feedback

═══════════════════════════════════════════════════════════════════
REGLA DE ORO PARA INTERPRETAR CONSULTAS:
═══════════════════════════════════════════════════════════════════

Si la consulta menciona:
- 'tarea ENVIADA con id=X' → task_submissions.id = X
- 'entrega con id=X' → task_submissions.id = X
- 'el usuario ha ENVIADO la tarea con id=X' → task_submissions.id = X

SQL CORRECTO para obtener dueño del curso de una ENTREGA:
SELECT c.creator_username
FROM public.task_submissions ts
JOIN public.tasks t ON ts.task_id = t.id
JOIN public.topics tp ON t.topic_id = tp.id
JOIN public.courses c ON tp.course_id = c.id
WHERE ts.id = ?;  -- ID de la ENTREGA

═══════════════════════════════════════════════════════════════════
TODAS LAS TABLAS (14 TABLAS):
═══════════════════════════════════════════════════════════════════

1. personas (id, identificacion, nombres, apellidos, email, telefono, 
             direccion, fechaNacimiento, avatar, esUsuario)
2. usuarios (id, usuario, contrasena, persona_id, rol_id)
3. roles (id, nombre, nivel, default)
4. recursos (id, nombre, descripcion)
5. rol_recursos (id, rol_id, recurso_id, puede_leer, puede_escribir, puede_eliminar)
6. courses (id, title, description, creator_username, thumbnail_uri, 
            video_uri, price, is_premium, is_published, timestamp)
7. topics (id, course_id, name, description, order_index)
8. tasks (id, topic_id, title, description, due_date)
9. task_submissions (id, task_id, student_username, file_uri, file_name, 
                     submission_date, grade, feedback)
10. content_items (id, topic_id, title, body, content_type)
11. videos (id, username, description, title, video_uri_string, 
            local_file_path, timestamp, is_paid, thumbnail_uri, price)
12. subscriptions (subscriber_username, creator_username, subscription_date)
13. chat_messages (id, message, is_from_user, timestamp, session_id)
14. file_contexts (id, submission_id, file_name, file_type, file_content, 
                   extracted_text, json_content, content_summary)

═══════════════════════════════════════════════════════════════════
CADENA DE RELACIONES:
═══════════════════════════════════════════════════════════════════

task_submissions (entrega) → tasks (definición) → topics (tema) → courses (curso con creator_username)

═══════════════════════════════════════════════════════════════════
NOTA: Este es un esquema de fallback. Reintente para obtener esquema completo.
═══════════════════════════════════════════════════════════════════
        """.trimIndent()
    }
    
    /**
     * Limpia el cache del esquema.
     * Útil cuando se sabe que la estructura ha cambiado.
     */
    fun clearCache() {
        cachedSchema = null
        lastFetchTime = 0
        lastSchemaHash = 0
        Log.d(TAG, "✓ Schema cache cleared - next call will fetch fresh from Supabase")
    }
    
    /**
     * Verifica si hay cambios en la estructura de la BD de Supabase sin usar cache.
     * Útil para detectar cambios (nuevas tablas, columnas, relaciones, etc).
     * 
     * @return true si la estructura cambió desde la última consulta, false si es igual
     */
    suspend fun hasSchemaChanged(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            Log.d(TAG, "⟳ Checking for schema changes in Supabase...")
            val freshSchema = buildDetailedSchema()
            val freshHash = freshSchema.hashCode()
            
            val changed = lastSchemaHash != 0 && lastSchemaHash != freshHash
            
            if (changed) {
                Log.w(TAG, "⚠️ SCHEMA CHANGED! Database structure modified in Supabase")
                Log.d(TAG, "   Old hash: $lastSchemaHash")
                Log.d(TAG, "   New hash: $freshHash")
            } else {
                Log.d(TAG, "✓ Schema unchanged (hash=$freshHash)")
            }
            
            changed
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error checking schema changes", e)
            false
        }
    }
    
    /**
     * Fuerza una actualización inmediata del esquema desde Supabase.
     * Equivale a clearCache() + getDatabaseSchema().
     * 
     * @return El esquema actualizado
     */
    suspend fun forceRefresh(): String {
        Log.d(TAG, "⚡ Force refresh requested")
        clearCache()
        return getDatabaseSchema(forceRefresh = true)
    }
    
    // === Data classes para estructurar la información ===
    
    data class ColumnInfo(
        val name: String,
        val dataType: String,
        val isNullable: Boolean,
        val defaultValue: String?,
        val isPrimaryKey: Boolean
    )
    
    data class ForeignKeyInfo(
        val columnName: String,
        val referencedTable: String,
        val referencedColumn: String,
        val onDelete: String?
    )
}
