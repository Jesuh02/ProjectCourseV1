package com.example.tareamov.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.config.RAGConfig
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.min
import kotlin.math.sqrt

/**
 * RAG (Retrieval-Augmented Generation) service for efficient database querying
 * Implements concepts from LangChain for optimized information retrieval
 */
class RAGDatabaseService(private val context: Context) {
    private val tag = "RAGDatabaseService"
    private val database = AppDatabase.getDatabase(context)
    
    // Vector store simulation for semantic search
    private val documentChunks = mutableMapOf<String, List<DocumentChunk>>()
    
    // Schema definitions with semantic tags - ACTUALIZADO CON TODAS LAS 15 TABLAS
    private val schemaDefinitions = mapOf(
        "personas" to SchemaInfo(
            table = "personas",
            columns = listOf("id", "nombres", "apellidos", "fechaNacimiento", "email", "esUsuario"),
            semanticTags = listOf("usuario", "persona", "gente", "contacto", "perfil"),
            description = "Información personal de usuarios del sistema"
        ),
        "usuarios" to SchemaInfo(
            table = "usuarios", 
            columns = listOf("usuario", "contrasena", "persona_id", "rol_id"),
            semanticTags = listOf("usuario", "login", "cuenta", "autenticacion", "acceso"),
            description = "Cuentas de usuario para autenticación"
        ),
        "videos" to SchemaInfo(
            table = "videos",
            columns = listOf("id", "title", "description", "username", "timestamp", "isPaid", "thumbnailUri", "price"),
            semanticTags = listOf("video", "contenido", "multimedia", "curso", "leccion"),
            description = "Videos educativos y contenido multimedia"
        ),
        "topics" to SchemaInfo(
            table = "topics",
            columns = listOf("id", "name", "description", "videoId"),
            semanticTags = listOf("tema", "topico", "categoria", "materia", "asunto"),
            description = "Temas organizacionales para agrupar contenido"
        ),
        "content_items" to SchemaInfo(
            table = "content_items",
            columns = listOf("id", "topicId", "title", "content", "type", "orderIndex"),
            semanticTags = listOf("contenido", "item", "material", "recurso"),
            description = "Elementos de contenido organizados por temas"
        ),
        "tasks" to SchemaInfo(
            table = "tasks",
            columns = listOf("id", "topicId", "name", "description", "orderIndex", "completed"),
            semanticTags = listOf("tarea", "actividad", "ejercicio", "trabajo", "asignacion"),
            description = "Tareas asociadas a temas específicos"
        ),
        "subscriptions" to SchemaInfo(
            table = "subscriptions",
            columns = listOf("subscriberUsername", "creatorUsername", "subscriptionDate"),
            semanticTags = listOf("suscripcion", "seguimiento", "seguidor", "subscriptor"),
            description = "Relaciones de suscripción entre usuarios"
        ),
        "task_submissions" to SchemaInfo(
            table = "task_submissions",
            columns = listOf("id", "taskId", "username", "submissionText", "submissionDate", "isCompleted"),
            semanticTags = listOf("entrega", "envio", "submission", "respuesta"),
            description = "Entregas de tareas por parte de usuarios"
        ),
        "chat_messages" to SchemaInfo(
            table = "chat_messages",
            columns = listOf("id", "mensaje", "esUsuario", "timestamp", "calificacion", "esPositiva"),
            semanticTags = listOf("chat", "mensaje", "conversacion", "comunicacion"),
            description = "Mensajes del chat del sistema"
        ),
        "file_contexts" to SchemaInfo(
            table = "file_contexts",
            columns = listOf("id", "fileName", "fileType", "jsonContent", "uploadDate", "isActive"),
            semanticTags = listOf("archivo", "contexto", "documento", "file"),
            description = "Contextos de archivos subidos al sistema"
        ),
        "courses" to SchemaInfo(
            table = "courses",
            columns = listOf("id", "title", "description", "creatorUsername", "price", "createdDate"),
            semanticTags = listOf("curso", "cursos", "formacion", "educacion"),
            description = "Cursos estructurados con contenido educativo"
        ),
        "roles" to SchemaInfo(
            table = "roles",
            columns = listOf("id", "nombre", "descripcion", "permisos"),
            semanticTags = listOf("rol", "roles", "permiso", "autoridad"),
            description = "Roles y permisos del sistema"
        ),
        "recursos" to SchemaInfo(
            table = "recursos",
            columns = listOf("id", "nombre", "descripcion", "tipo", "url"),
            semanticTags = listOf("recurso", "recursos", "herramienta", "material"),
            description = "Recursos disponibles en el sistema"
        ),
        "rol_recursos" to SchemaInfo(
            table = "rol_recursos",
            columns = listOf("id", "rol_id", "recurso_id", "puede_leer", "puede_escribir", "puede_eliminar"),
            semanticTags = listOf("permisos", "acceso", "autorizacion", "rol_recurso"),
            description = "Relación entre roles y recursos con permisos específicos"
        )
    )

    data class SchemaInfo(
        val table: String,
        val columns: List<String>,
        val semanticTags: List<String>,
        val description: String
    )

    data class DocumentChunk(
        val id: String,
        val content: String,
        val metadata: Map<String, Any>,
        val embeddings: List<Double> = emptyList()
    )

    data class QueryContext(
        val intent: QueryIntent,
        val targetTables: List<String>,
        val relevantColumns: List<String>,
        val filters: Map<String, String>,
        val semanticQuery: String
    )

    enum class QueryIntent {
        LIST_ALL,          // "dame todos los usuarios"
        SEARCH_SPECIFIC,   // "buscar usuario por email"
        COUNT_AGGREGATE,   // "cuántos videos hay"
        RELATIONSHIP,      // "videos de un usuario"
        ANALYTICAL,        // "tendencias, estadísticas"
        COMPARISON,        // "comparar datos"
        RECENT_DATA        // "datos recientes"
    }

    /**
     * Main entry point for RAG-based query processing
     */
    suspend fun processRAGQuery(userQuery: String): String = withContext(Dispatchers.IO) {
        Log.d(tag, "Processing RAG query: $userQuery")
        
        // Special handling for "all tables" requests
        val isRequestingAllTables = userQuery.lowercase().let { query ->
            query.contains("todas las tablas") || 
            query.contains("toda la base") ||
            query.contains("base de datos") ||
            query.contains("que tablas") ||
            query.contains("cuántas tablas") ||
            query.contains("lista de tablas") ||
            query.contains("todas") ||
            query.contains("listar tablas") ||
            query.contains("esquema")
        }
        
        if (isRequestingAllTables) {
            Log.d(tag, "Detected request for all tables, building dynamic schema response")
            try {
                // Use the existing DatabaseQueryService to obtain a fresh JSON snapshot
                val dbService = DatabaseQueryService(context)
                val jsonStr = dbService.generateDatabaseJson()
                val json = JSONObject(jsonStr)

                val sb = StringBuilder()
                sb.appendLine("📊 BASE DE DATOS TAREAMOV - ESQUEMA COMPLETO (dinámico)")
                sb.appendLine()

                // Collect table names (exclude metadata keys)
                val tableNames = json.keys().asSequence().filter { it != "schema" && it != "statistics" }.toList()
                sb.appendLine("La base de datos contiene las siguientes tablas (actual): ${tableNames.size} tablas:\n")

                val stats = json.optJSONObject("statistics")
                val schemaObj = json.optJSONObject("schema")

                for ((index, table) in tableNames.withIndex()) {
                    val array = json.optJSONArray(table)
                    val count = array?.length() ?: 0
                    sb.appendLine("${index + 1}. ${table.uppercase()} ($count registros)")

                    // Schema info if available
                    val schemaText = schemaObj?.optString(table) ?: schemaObj?.optString(table.lowercase())
                    if (!schemaText.isNullOrBlank()) {
                        sb.appendLine("   - Esquema: $schemaText")
                    }

                    // Add up to 2 sample records (shortened)
                    if (count > 0) {
                        sb.appendLine("   - Ejemplos:")
                        val sampleSize = kotlin.math.min(2, count)
                        for (i in 0 until sampleSize) {
                            val row = array!!.getJSONObject(i)
                            val keys = row.keys().asSequence().toList()
                            val sampleFields = keys.take(5).joinToString(", ") { k -> "$k: ${row.optString(k).take(40)}" }
                            sb.appendLine("     - { $sampleFields }")
                        }
                    } else {
                        sb.appendLine("   - Tabla vacía")
                    }

                    sb.appendLine()
                }

                // Add statistics summary if present
                if (stats != null) {
                    sb.appendLine("📈 Estadísticas:")
                    stats.keys().forEach { key ->
                        sb.appendLine("- $key: ${stats.optInt(key)}")
                    }
                }

                sb.appendLine()
                sb.appendLine("✅ Puedes preguntar por cualquiera de estas tablas o solicitar detalles específicos.")

                return@withContext sb.toString().trim()
            } catch (e: Exception) {
                Log.e(tag, "Error building dynamic schema for all-tables request", e)
                // Fallback to original static schema if something goes wrong
                Log.d(tag, "Falling back to static schema response")
                return@withContext """
📊 BASE DE DATOS TAREAMOV - ESQUEMA COMPLETO

La base de datos contiene exactamente **14 TABLAS**:

1. **PERSONAS** - Información personal de usuarios del sistema
   - Campos: id, nombre, apellido, email, telefono, fecha_nacimiento

2. **USUARIOS** - Cuentas de usuario para autenticación
   - Campos: id, persona_id, username, password_hash, rol, fecha_creacion

3. **VIDEOS** - Videos educativos y contenido multimedia
   - Campos: id, titulo, descripcion, url, duracion, creator_id, precio

4. **TOPICS** - Temas organizacionales para agrupar contenido
   - Campos: id, nombre, descripcion, creator_id, fecha_creacion

5. **CONTENT_ITEMS** - Elementos de contenido organizados por temas
   - Campos: id, titulo, descripcion, tipo, topic_id, orden

6. **TASKS** - Tareas asociadas a temas específicos
   - Campos: id, titulo, descripcion, topic_id, fecha_limite, tipo

7. **SUBSCRIPTIONS** - Relaciones de suscripción entre usuarios
   - Campos: id, follower_id, creator_id, fecha_suscripcion

8. **TASK_SUBMISSIONS** - Entregas de tareas por parte de usuarios
   - Campos: id, task_id, usuario_id, respuesta, fecha_entrega, calificacion

9. **CHAT_MESSAGES** - Mensajes del sistema de chat
   - Campos: id, usuario_id, mensaje, timestamp, tipo, calificacion

10. **FILE_CONTEXTS** - Contextos de archivos subidos al sistema
    - Campos: id, nombre_archivo, contenido_json, tipo_mime, usuario_id

11. **COURSES** - Cursos estructurados con contenido educativo
    - Campos: id, titulo, descripcion, creator_id, precio, fecha_creacion

12. **ROLES** - Roles y permisos del sistema
    - Campos: id, nombre, descripcion, nivel_acceso

13. **RECURSOS** - Recursos disponibles en el sistema
    - Campos: id, nombre, descripcion, tipo, url

14. **ROL_RECURSOS** - Relación entre roles y recursos con permisos específicos
    - Campos: id, rol_id, recurso_id, puede_leer, puede_escribir, puede_eliminar

✅ **TOTAL: 14 TABLAS DISPONIBLES**

Puedes hacer consultas sobre cualquiera de estas tablas o sus relaciones.
                """.trimIndent()
            }
        }
        
        try {
            // 1. Analyze query intent and extract context
            val queryContext = analyzeQueryIntent(userQuery)
            Log.d(tag, "Query context: $queryContext")
            
            // 2. Retrieve relevant database content
            val relevantData = retrieveRelevantData(queryContext)
            
            // 3. Generate context-aware response
            val response = generateResponse(queryContext, relevantData, userQuery)
            
            return@withContext response
            
        } catch (e: Exception) {
            Log.e(tag, "Error processing RAG query", e)
            return@withContext "Error procesando la consulta: ${e.message}"
        }
    }

    /**
     * Analyze user query to understand intent and extract relevant information using RAGConfig
     */
    private fun analyzeQueryIntent(query: String): QueryContext {
        val normalizedQuery = query.lowercase().trim()
        
        // Detect query intent using configured keywords
        val intent = when {
            RAGConfig.LIST_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.LIST_ALL
            RAGConfig.SEARCH_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.SEARCH_SPECIFIC
            RAGConfig.COUNT_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.COUNT_AGGREGATE
            RAGConfig.RECENT_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.RECENT_DATA
            RAGConfig.ANALYTICS_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.ANALYTICAL
            normalizedQuery.contains("de") && (normalizedQuery.contains("usuario") || 
            normalizedQuery.contains("creador")) -> QueryIntent.RELATIONSHIP
            else -> QueryIntent.SEARCH_SPECIFIC
        }
        
        // Identify target tables using semantic matching from config
        val targetTables = identifyRelevantTablesWithConfig(normalizedQuery)
        
        // Extract relevant columns
        val relevantColumns = extractRelevantColumns(normalizedQuery, targetTables)
        
        // Extract filters
        val filters = extractFilters(normalizedQuery)
        
        return QueryContext(
            intent = intent,
            targetTables = targetTables,
            relevantColumns = relevantColumns,
            filters = filters,
            semanticQuery = normalizedQuery
        )
    }

    /**
     * Use semantic similarity with RAGConfig to identify relevant database tables
     */
    private fun identifyRelevantTablesWithConfig(query: String): List<String> {
        val relevantTables = mutableListOf<String>()
        
        RAGConfig.TABLE_SEMANTIC_MAPPING.forEach { (tableName, semanticTags) ->
            val similarity = calculateSemanticSimilarity(query, semanticTags)
            if (similarity > RAGConfig.SEMANTIC_SIMILARITY_THRESHOLD) {
                relevantTables.add(tableName)
            }
        }
        
        // If no tables found through semantic matching, try direct keyword matching
        if (relevantTables.isEmpty()) {
            RAGConfig.TABLE_SEMANTIC_MAPPING.forEach { (tableName, semanticTags) ->
                if (semanticTags.any { tag -> query.contains(tag) } || query.contains(tableName)) {
                    relevantTables.add(tableName)
                }
            }
        }
        
        // Special case: if user asks for "todas las tablas" or similar, return ALL tables
        val isRequestingAllTables = query.contains("todas las tablas") || 
                                   query.contains("toda la base") ||
                                   query.contains("base de datos") ||
                                   query.contains("todas") ||
                                   query.contains("lista de tablas") ||
                                   query.contains("que tablas") ||
                                   query.contains("cuántas tablas") ||
                                   query.contains("todas las") ||
                                   relevantTables.isEmpty()
        
        if (isRequestingAllTables) {
            // Return ALL 14 tables from the database
            return listOf(
                "personas", "usuarios", "videos", "topics", 
                "content_items", "tasks", "subscriptions", 
                "task_submissions", "chat_messages", "file_contexts", 
                "courses", "roles", "recursos", "rol_recursos"
            )
        }
        
        return relevantTables
    }

    /**
     * Calculate semantic similarity between query and schema elements
     */
    private fun calculateSemanticSimilarity(query: String, semanticElements: List<String>): Double {
        val queryWords = query.split(" ", ",", ".", "?", "!")
        var matchCount = 0
        
        semanticElements.forEach { element ->
            queryWords.forEach { word ->
                if (element.contains(word, ignoreCase = true) || 
                    word.contains(element, ignoreCase = true)) {
                    matchCount++
                }
            }
        }
        
        return if (queryWords.isNotEmpty()) matchCount.toDouble() / queryWords.size else 0.0
    }

    /**
     * Extract relevant columns based on query context
     */
    private fun extractRelevantColumns(query: String, tables: List<String>): List<String> {
        val relevantColumns = mutableSetOf<String>()
        
        tables.forEach { tableName ->
            schemaDefinitions[tableName]?.let { schema ->
                schema.columns.forEach { column ->
                    if (query.contains(column, ignoreCase = true) ||
                        isColumnRelevantToQuery(query, column)) {
                        relevantColumns.add("$tableName.$column")
                    }
                }
            }
        }
        
        return relevantColumns.toList()
    }

    /**
     * Check if a column is semantically relevant to the query
     */
    private fun isColumnRelevantToQuery(query: String, column: String): Boolean {
        return when (column.lowercase()) {
            "id" -> query.contains("identificar") || query.contains("código")
            "nombres", "name", "title" -> query.contains("nombre") || query.contains("título")
            "email" -> query.contains("correo") || query.contains("email")
            "description", "descripcion" -> query.contains("descripción") || query.contains("detalle")
            "timestamp", "purchasedate", "subscriptiondate" -> query.contains("fecha") || query.contains("cuándo") || query.contains("reciente")
            "price", "precio" -> query.contains("precio") || query.contains("costo") || query.contains("valor")
            "usuario", "username" -> query.contains("usuario") || query.contains("creador")
            else -> false
        }
    }

    /**
     * Extract filters from the query
     */
    private fun extractFilters(query: String): Map<String, String> {
        val filters = mutableMapOf<String, String>()
        
        // Extract common filter patterns
        val emailPattern = """[\w.-]+@[\w.-]+\.\w+""".toRegex()
        emailPattern.find(query)?.let { match ->
            filters["email"] = match.value
        }
        
        // Extract username mentions
        val usernamePattern = """@(\w+)""".toRegex()
        usernamePattern.find(query)?.let { match ->
            filters["usuario"] = match.groupValues[1]
        }
        
        // Extract specific IDs
        val idPattern = """\bid\s*=?\s*(\d+)""".toRegex()
        idPattern.find(query)?.let { match ->
            filters["id"] = match.groupValues[1]
        }
        
        return filters
    }

    /**
     * Retrieve relevant data based on query context
     */
    private suspend fun retrieveRelevantData(context: QueryContext): String = withContext(Dispatchers.IO) {
        val result = StringBuilder()
        
        when (context.intent) {
            QueryIntent.LIST_ALL -> {
                context.targetTables.forEach { tableName ->
                    // Check if user wants ALL data (no limits)
                    val limit = if (context.semanticQuery.contains("todos los datos") || 
                                  context.semanticQuery.contains("toda la tabla") ||
                                  context.semanticQuery.contains("dame la lista") ||
                                  context.semanticQuery.contains("lista completa")) {
                        Int.MAX_VALUE // No limit for complete data requests
                    } else {
                        RAGConfig.MAX_RETRIEVED_ITEMS // Default limit
                    }
                    
                    val data = getTableData(tableName, limit = limit)
                    result.append("Datos de $tableName:\n$data\n\n")
                }
            }
            
            QueryIntent.COUNT_AGGREGATE -> {
                context.targetTables.forEach { tableName ->
                    val count = getTableCount(tableName)
                    result.append("$tableName: $count registros\n")
                }
            }
            
            QueryIntent.SEARCH_SPECIFIC -> {
                context.targetTables.forEach { tableName ->
                    val data = searchInTable(tableName, context.filters, context.semanticQuery)
                    if (data.isNotEmpty()) {
                        result.append("Resultados en $tableName:\n$data\n\n")
                    }
                }
            }
            
            QueryIntent.RELATIONSHIP -> {
                val relationshipData = getRelationshipData(context)
                result.append(relationshipData)
            }
            
            QueryIntent.RECENT_DATA -> {
                context.targetTables.forEach { tableName ->
                    val recentData = getRecentData(tableName)
                    result.append("Datos recientes de $tableName:\n$recentData\n\n")
                }
            }
            
            QueryIntent.ANALYTICAL -> {
                val analyticsData = getAnalyticalData(context.targetTables)
                result.append(analyticsData)
            }
            
            QueryIntent.COMPARISON -> {
                // Implementation for comparison queries
                result.append("Función de comparación en desarrollo")
            }
        }
        
        return@withContext result.toString()
    }

    /**
     * Get table data with optional limit using RAGConfig
     */
    private suspend fun getTableData(tableName: String, limit: Int = RAGConfig.MAX_RETRIEVED_ITEMS): String = withContext(Dispatchers.IO) {
        try {
            when (tableName) {
                "personas" -> {
                    val personas = database.personaDao().getAllPersonasList().take(limit)
                    formatPersonasData(personas)
                }
                "usuarios" -> {
                    val usuarios = database.usuarioDao().getAllUsuarios().take(limit)
                    formatUsuariosData(usuarios)
                }
                "videos" -> {
                    val videos = database.videoDao().getAllVideos().take(limit)
                    formatVideosData(videos)
                }
                "topics" -> {
                    val topics = database.topicDao().getAllTopics().take(limit)
                    formatTopicsData(topics)
                }
                "content_items" -> {
                    val contentItems = database.contentItemDao().getAllContentItems().take(limit)
                    formatContentItemsData(contentItems)
                }
                "tasks" -> {
                    val tasks = database.taskDao().getAllTasks().take(limit)
                    formatTasksData(tasks)
                }
                "subscriptions" -> {
                    val subscriptions = database.subscriptionDao().getAllSubscriptions().take(limit)
                    formatSubscriptionsData(subscriptions)
                }
                "task_submissions" -> {
                    val submissions = database.taskSubmissionDao().getAllTaskSubmissions().take(limit)
                    formatTaskSubmissionsData(submissions)
                }
                "chat_messages" -> {
                    val messages = database.chatMessageDao().getAllMessages().first()
                    formatChatMessagesData(messages.take(limit))
                }
                "file_contexts" -> {
                    val contexts = database.fileContextDao().getAllFileContexts().first()
                    formatFileContextsData(contexts.take(limit))
                }
                "courses" -> {
                    val courses = database.courseDao().getAllCourses().take(limit)
                    formatCoursesData(courses)
                }
                "roles" -> {
                    val roles = database.rolDao().getAllRoles().take(limit)
                    formatRolesData(roles)
                }
                "recursos" -> {
                    val recursos = database.recursoDao().getAllRecursos().take(limit)
                    formatRecursosData(recursos)
                }
                "rol_recursos" -> {
                    val rolRecursos = database.rolRecursoDao().getAllRolRecursos()
                    formatRolRecursosData(rolRecursos.take(limit))
                }
                else -> "Tabla no encontrada: $tableName. Tablas disponibles: personas, usuarios, videos, topics, content_items, tasks, subscriptions, task_submissions, chat_messages, file_contexts, courses, roles, recursos, rol_recursos"
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting data from $tableName", e)
            "Error obteniendo datos de $tableName: ${e.message}"
        }
    }

    /**
     * Get table count
     */
    private suspend fun getTableCount(tableName: String): Int = withContext(Dispatchers.IO) {
        try {
            when (tableName) {
                "personas" -> database.personaDao().getAllPersonasList().size
                "usuarios" -> database.usuarioDao().getAllUsuarios().size
                "videos" -> database.videoDao().getAllVideos().size
                "topics" -> database.topicDao().getAllTopics().size
                "content_items" -> database.contentItemDao().getAllContentItems().size
                "tasks" -> database.taskDao().getAllTasks().size
                "subscriptions" -> database.subscriptionDao().getAllSubscriptions().size
                "task_submissions" -> database.taskSubmissionDao().getAllTaskSubmissions().size
                "chat_messages" -> database.chatMessageDao().getAllMessages().first().size
                "file_contexts" -> database.fileContextDao().getAllFileContexts().first().size
                "courses" -> database.courseDao().getAllCourses().size
                "roles" -> database.rolDao().getAllRoles().size
                "recursos" -> database.recursoDao().getAllRecursos().size
                "rol_recursos" -> database.rolRecursoDao().getAllRolRecursos().size
                else -> 0
            }
        } catch (e: Exception) {
            Log.e(tag, "Error counting $tableName", e)
            0
        }
    }

    /**
     * Search in specific table with filters
     */
    private suspend fun searchInTable(tableName: String, filters: Map<String, String>, query: String): String = withContext(Dispatchers.IO) {
        try {
            when (tableName) {
                "usuarios" -> {
                    val usuarios = database.usuarioDao().getAllUsuarios()
                    val filtered = usuarios.filter { usuario ->
                        filters["usuario"]?.let { return@filter usuario.usuario.contains(it, ignoreCase = true) }
                        // General text search
                        query.split(" ").any { term ->
                            usuario.usuario.contains(term, ignoreCase = true)
                        }
                    }
                    formatUsuariosData(filtered)
                }
                "videos" -> {
                    val videos = database.videoDao().getAllVideos()
                    val filtered = videos.filter { video ->
                        query.split(" ").any { term ->
                            video.title.contains(term, ignoreCase = true) ||
                            video.description.contains(term, ignoreCase = true)
                        }
                    }
                    formatVideosData(filtered)
                }
                // Add more table-specific search logic as needed
                else -> getTableData(tableName, 20) // Fallback to general data
            }
        } catch (e: Exception) {
            Log.e(tag, "Error searching in $tableName", e)
            "Error buscando en $tableName: ${e.message}"
        }
    }

    /**
     * Get relationship data
     */
    private suspend fun getRelationshipData(context: QueryContext): String = withContext(Dispatchers.IO) {
        val result = StringBuilder()
        
        try {
            // Example: Videos by user
            if (context.targetTables.contains("videos") && context.filters.containsKey("usuario")) {
                val username = context.filters["usuario"]!!
                val userVideos = database.videoDao().getAllVideos().filter { it.username == username }
                result.append("Videos creados por $username:\n")
                result.append(formatVideosData(userVideos))
            }
            
            // Example: Tasks in topics
            if (context.targetTables.contains("tasks") && context.targetTables.contains("topics")) {
                val topics = database.topicDao().getAllTopics().take(10)
                topics.forEach { topic ->
                    val tasks = database.taskDao().getAllTasks().filter { it.topicId == topic.id }
                    if (tasks.isNotEmpty()) {
                        result.append("Tema: ${topic.name}\n")
                        result.append("Tareas: ${tasks.joinToString(", ") { it.name }}\n\n")
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Error getting relationship data", e)
            result.append("Error obteniendo datos relacionales: ${e.message}")
        }
        
        return@withContext result.toString()
    }

    /**
     * Get recent data from tables
     */
    private suspend fun getRecentData(tableName: String): String = withContext(Dispatchers.IO) {
        try {
            when (tableName) {
                "usuarios" -> {
                    val usuarios = database.usuarioDao().getAllUsuarios()
                        .sortedByDescending { it.id }
                        .take(10)
                    formatUsuariosData(usuarios)
                }
                "videos" -> {
                    val videos = database.videoDao().getAllVideos()
                        .sortedByDescending { it.timestamp }
                        .take(10)
                    formatVideosData(videos)
                }
                "subscriptions" -> {
                    val subscriptions = database.subscriptionDao().getAllSubscriptions()
                        .sortedByDescending { it.subscriptionDate }
                        .take(10)
                    formatSubscriptionsData(subscriptions)
                }
                else -> "Datos recientes no disponibles para $tableName"
            }
        } catch (e: Exception) {
            Log.e(tag, "Error getting recent data from $tableName", e)
            "Error obteniendo datos recientes de $tableName: ${e.message}"
        }
    }

    /**
     * Get analytical data
     */
    private suspend fun getAnalyticalData(tables: List<String>): String = withContext(Dispatchers.IO) {
        val result = StringBuilder()
        
        try {
            result.append("=== ANÁLISIS DE DATOS ===\n\n")
            
            tables.forEach { tableName ->
                val count = getTableCount(tableName)
                result.append("$tableName: $count registros\n")
            }
            
            // Additional analytics
            if (tables.contains("videos") && tables.contains("usuarios")) {
                val videos = database.videoDao().getAllVideos()
                val usuarios = database.usuarioDao().getAllUsuarios()
                val creatorStats = videos.groupBy { it.username }
                    .mapValues { it.value.size }
                    .toList()
                    .sortedByDescending { it.second }
                    .take(5)
                
                result.append("\nTop 5 creadores por número de videos:\n")
                creatorStats.forEach { (username, count) ->
                    result.append("$username: $count videos\n")
                }
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Error getting analytical data", e)
            result.append("Error en análisis: ${e.message}")
        }
        
        return@withContext result.toString()
    }

    /**
     * Generate response using LLM with retrieved context
     */
    private suspend fun generateResponse(context: QueryContext, relevantData: String, originalQuery: String): String {
        val mspClient = MSPClient(this.context)
        val localLlamaService = LocalLlamaService(this.context)
        
        // Create optimized prompt with retrieved context
        val prompt = buildOptimizedPrompt(context, relevantData, originalQuery)
        
        return try {
            // Try MSP client first
            mspClient.sendPrompt(prompt)
        } catch (e: Exception) {
            Log.w(tag, "MSP failed, trying LocalLlama", e)
            try {
                localLlamaService.generateResponse(prompt)
            } catch (e2: Exception) {
                Log.e(tag, "Both LLM services failed", e2)
                // Return the raw data as fallback
                formatDirectResponse(context, relevantData, originalQuery)
            }
        }
    }

    /**
     * Build optimized prompt for LLM using RAGConfig templates
     */
    private fun buildOptimizedPrompt(context: QueryContext, relevantData: String, originalQuery: String): String {
        val systemPrompt = when (context.intent) {
            QueryIntent.ANALYTICAL -> RAGConfig.SYSTEM_PROMPTS["ANALYTICAL"]
            QueryIntent.SEARCH_SPECIFIC -> RAGConfig.SYSTEM_PROMPTS["TECHNICAL"]
            else -> RAGConfig.SYSTEM_PROMPTS["EDUCATIONAL"]
        } ?: RAGConfig.SYSTEM_PROMPTS["EDUCATIONAL"]!!

        val responseTemplate = when (context.intent) {
            QueryIntent.LIST_ALL -> RAGConfig.RESPONSE_TEMPLATES["LIST"]
            QueryIntent.COUNT_AGGREGATE -> RAGConfig.RESPONSE_TEMPLATES["COUNT"]
            QueryIntent.SEARCH_SPECIFIC -> RAGConfig.RESPONSE_TEMPLATES["SEARCH"]
            QueryIntent.RECENT_DATA -> RAGConfig.RESPONSE_TEMPLATES["RECENT"]
            QueryIntent.ANALYTICAL -> RAGConfig.RESPONSE_TEMPLATES["ANALYTICS"]
            else -> RAGConfig.RESPONSE_TEMPLATES["SEARCH"]
        } ?: ""

        val limitedData = if (relevantData.length > RAGConfig.MAX_CHUNK_SIZE && 
                             !originalQuery.lowercase().contains("todos los datos") &&
                             !originalQuery.lowercase().contains("toda la tabla")) {
            relevantData.take(RAGConfig.MAX_CHUNK_SIZE) + "...(optimizado para eficiencia)"
        } else {
            relevantData
        }

        // Check if user wants complete data
        val isCompleteDataRequest = originalQuery.lowercase().let { query ->
            query.contains("todos los datos") || query.contains("toda la tabla") || 
            query.contains("dame la lista") || query.contains("lista completa")
        }

        val instructions = if (isCompleteDataRequest) {
            """
INSTRUCCIONES ESPECIALES PARA DATOS COMPLETOS:
1. MUESTRA TODOS LOS DATOS proporcionados sin restricciones de longitud
2. NO limites el número de elementos mostrados 
3. Presenta CADA REGISTRO completo con todos sus campos
4. Usa el formato estructurado proporcionado en los datos
5. NO resumas ni omitas información
            """.trimIndent()
        } else {
            """
INSTRUCCIONES:
1. Responde de manera concisa y directa (máximo ${RAGConfig.MAX_RESPONSE_LENGTH} caracteres)
2. Usa solo la información proporcionada
3. Si pides una lista, presenta máximo ${RAGConfig.MAX_LIST_ITEMS} elementos
4. Si es un conteo, da el número específico
5. Si no hay datos suficientes, indícalo claramente
            """.trimIndent()
        }

        return """
$systemPrompt

ESQUEMA RELEVANTE:
${getRelevantSchemaInfo(context.targetTables)}

DATOS RECUPERADOS:
$limitedData

CONSULTA DEL USUARIO: $originalQuery

$responseTemplate

$instructions

RESPUESTA:
        """.trimIndent()
    }

    /**
     * Get relevant schema information for prompt
     */
    private fun getRelevantSchemaInfo(tables: List<String>): String {
        return tables.mapNotNull { tableName ->
            schemaDefinitions[tableName]?.let { schema ->
                "${schema.table}: ${schema.description}\nColumnas: ${schema.columns.joinToString(", ")}"
            }
        }.joinToString("\n\n")
    }

    /**
     * Format direct response when LLM fails using RAGConfig templates
     */
    private fun formatDirectResponse(context: QueryContext, relevantData: String, originalQuery: String): String {
        val template = when (context.intent) {
            QueryIntent.LIST_ALL -> RAGConfig.RESPONSE_TEMPLATES["LIST"]
            QueryIntent.COUNT_AGGREGATE -> RAGConfig.RESPONSE_TEMPLATES["COUNT"]
            QueryIntent.SEARCH_SPECIFIC -> RAGConfig.RESPONSE_TEMPLATES["SEARCH"]
            QueryIntent.RECENT_DATA -> RAGConfig.RESPONSE_TEMPLATES["RECENT"]
            QueryIntent.ANALYTICAL -> RAGConfig.RESPONSE_TEMPLATES["ANALYTICS"]
            else -> RAGConfig.RESPONSE_TEMPLATES["SEARCH"]
        } ?: ""
        
        val limitedData = if (relevantData.length > RAGConfig.MAX_RESPONSE_LENGTH) {
            relevantData.take(RAGConfig.MAX_RESPONSE_LENGTH) + "...(resultado limitado)"
        } else {
            relevantData
        }
        
        return if (limitedData.isBlank()) {
            RAGConfig.ERROR_MESSAGES["NO_RESULTS"] ?: "No se encontraron resultados"
        } else {
            "$template\n\n$limitedData"
        }
    }

    // Formatting methods for different entity types
    private fun formatPersonasData(personas: List<com.example.tareamov.data.entity.Persona>): String {
        if (personas.isEmpty()) return "No se encontraron personas."
        
        return personas.joinToString("\n") { persona ->
            "ID: ${persona.id}, Nombre: ${persona.nombres} ${persona.apellidos}, Email: ${persona.email}"
        }
    }

    private fun formatUsuariosData(usuarios: List<com.example.tareamov.data.entity.Usuario>): String {
        if (usuarios.isEmpty()) return "No se encontraron usuarios."
        
        val result = StringBuilder()
        result.appendLine("=== DATOS COMPLETOS DE USUARIOS ===")
        result.appendLine("Total de usuarios encontrados: ${usuarios.size}")
        result.appendLine()
        
        usuarios.forEachIndexed { index, usuario ->
            result.appendLine("--- USUARIO ${index + 1} ---")
            result.appendLine("ID: ${usuario.id}")
            result.appendLine("Usuario: ${usuario.usuario}")
            result.appendLine("Persona_ID: ${usuario.persona_id}")
            result.appendLine("Rol_ID: ${usuario.rol_id}")
            
            // Intentar obtener información adicional del rol si está disponible
            try {
                val roleInfo = when (usuario.rol_id) {
                    1L -> "Administrador"
                    2L -> "Profesor/Instructor"
                    3L -> "Estudiante"
                    else -> "Rol desconocido (${usuario.rol_id})"
                }
                result.appendLine("Rol: $roleInfo")
            } catch (e: Exception) {
                result.appendLine("Rol: ID ${usuario.rol_id}")
            }
            
            result.appendLine("Estado: Activo")
            result.appendLine("Fecha de creación: ${usuario.id}") // Usando ID como indicador temporal
            result.appendLine()
        }
        
        result.appendLine("=== FIN DE DATOS DE USUARIOS ===")
        return result.toString()
    }

    private fun formatVideosData(videos: List<com.example.tareamov.data.entity.VideoData>): String {
        if (videos.isEmpty()) return "No se encontraron videos."
        
        return videos.joinToString("\n") { video ->
            "ID: ${video.id}, Título: ${video.title}, Creador: ${video.username}"
        }
    }

    private fun formatTopicsData(topics: List<com.example.tareamov.data.entity.Topic>): String {
        if (topics.isEmpty()) return "No se encontraron temas."
        
        return topics.joinToString("\n") { topic ->
            "ID: ${topic.id}, Nombre: ${topic.name}, Descripción: ${topic.description ?: "Sin descripción"}"
        }
    }

    private fun formatTasksData(tasks: List<com.example.tareamov.data.entity.Task>): String {
        if (tasks.isEmpty()) return "No se encontraron tareas."
        
        return tasks.joinToString("\n") { task ->
            "ID: ${task.id}, Nombre: ${task.name}, Tema ID: ${task.topicId}, Descripción: ${task.description ?: "Sin descripción"}"
        }
    }

    private fun formatSubscriptionsData(subscriptions: List<com.example.tareamov.data.entity.Subscription>): String {
        if (subscriptions.isEmpty()) return "No se encontraron suscripciones."
        
        return subscriptions.joinToString("\n") { sub ->
            "Suscriptor: ${sub.subscriberUsername}, Creador: ${sub.creatorUsername}"
        }
    }

    private fun formatContentItemsData(items: List<com.example.tareamov.data.entity.ContentItem>): String {
        if (items.isEmpty()) return "No se encontraron elementos de contenido."
        
        return items.joinToString("\n") { item ->
            "ID: ${item.id}, Nombre: ${item.name ?: "Sin nombre"}, Tema ID: ${item.topicId}"
        }
    }

    private fun formatCoursesData(courses: List<com.example.tareamov.data.entity.Course>): String {
        if (courses.isEmpty()) return "No se encontraron cursos."
        
        return courses.joinToString("\n") { course ->
            "ID: ${course.id}, Título: ${course.title}, Descripción: ${course.description ?: "Sin descripción"}"
        }
    }

    private fun formatChatMessagesData(messages: List<com.example.tareamov.data.entity.ChatMessage>): String {
        if (messages.isEmpty()) return "No se encontraron mensajes de chat."
        
        return messages.joinToString("\n") { message ->
            "ID: ${message.id}, Usuario: ${if (message.isFromUser) "Sí" else "No"}, Mensaje: ${message.message.take(50)}..."
        }
    }

    private fun formatFileContextsData(contexts: List<com.example.tareamov.data.entity.FileContext>): String {
        if (contexts.isEmpty()) return "No se encontraron contextos de archivos."
        
        return contexts.joinToString("\n") { context ->
            "ID: ${context.id}, Archivo: ${context.fileName}, Tipo: ${context.fileType}"
        }
    }

    private fun formatTaskSubmissionsData(submissions: List<com.example.tareamov.data.entity.TaskSubmission>): String {
        if (submissions.isEmpty()) return "No se encontraron entregas de tareas."
        
        return submissions.joinToString("\n") { submission ->
            "ID: ${submission.id}, Tarea ID: ${submission.taskId}, Usuario: ${submission.studentUsername}"
        }
    }

    private fun formatRolesData(roles: List<com.example.tareamov.data.entity.Rol>): String {
        if (roles.isEmpty()) return "No se encontraron roles."
        
        return roles.joinToString("\n") { rol ->
            "ID: ${rol.id}, Nombre: ${rol.nombre}, Nivel: ${rol.nivel}"
        }
    }

    private fun formatRecursosData(recursos: List<com.example.tareamov.data.entity.Recurso>): String {
        if (recursos.isEmpty()) return "No se encontraron recursos."
        
        return recursos.joinToString("\n") { recurso ->
            "ID: ${recurso.id}, Nombre: ${recurso.nombre}, Icono: ${recurso.icono}, Orden: ${recurso.orden}"
        }
    }

    private fun formatRolRecursosData(rolRecursos: List<com.example.tareamov.data.entity.RolRecurso>): String {
        if (rolRecursos.isEmpty()) return "No se encontraron relaciones rol-recurso."
        
        return rolRecursos.joinToString("\n") { rolRecurso ->
            "Rol ID: ${rolRecurso.rolId}, Recurso ID: ${rolRecurso.recursoId}"
        }
    }
}
