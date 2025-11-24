package com.example.tareamov.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import com.example.tareamov.config.RAGConfig
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.min
import kotlin.math.sqrt

@OptIn(kotlin.experimental.ExperimentalTypeInference::class)
class RAGDatabaseService(private val context: Context) {
    private val tag = "RAGDatabaseService"
    // IMPORTANTE: Supabase es la ÚNICA fuente de datos para RAG queries (NO se usa Room)
    private val supabase = SupabaseClient
    
    // DatabaseSchemaService para obtener esquema dinámico
    private val schemaService = DatabaseSchemaService(context)
    
    // Vector store simulation for semantic search
    private val documentChunks = mutableMapOf<String, List<DocumentChunk>>()
    
    // Schema definitions with semantic tags - ACTUALIZADO CON TODAS LAS 15 TABLAS
    // Schema definitions matching ACTUAL Supabase/Postgres structure from Estructura.sql
    private val schemaDefinitions = mapOf(
        "personas" to SchemaInfo(
            table = "personas",
            columns = listOf("id", "identificacion", "nombres", "apellidos", "email", "telefono", "direccion", "fechaNacimiento", "avatar", "esUsuario", "created_at"),
            semanticTags = listOf("usuario", "persona", "gente", "contacto", "perfil"),
            description = "Información personal de usuarios del sistema"
        ),
        "usuarios" to SchemaInfo(
            table = "usuarios", 
            columns = listOf("id", "usuario", "contrasena", "persona_id", "rol_id", "created_at"),
            semanticTags = listOf("usuario", "login", "cuenta", "autenticacion", "acceso", "password", "contraseña", "clave", "credenciales"),
            description = "Cuentas de usuario para autenticación (el campo 'contrasena' almacena la contraseña/password/clave)"
        ),
        "videos" to SchemaInfo(
            table = "videos",
            columns = listOf("id", "username", "description", "title", "video_uri_string", "local_file_path", "timestamp", "is_paid", "thumbnail_uri", "price", "remote_id", "created_at"),
            semanticTags = listOf("video", "contenido", "multimedia", "curso", "leccion"),
            description = "Videos educativos y contenido multimedia"
        ),
        "topics" to SchemaInfo(
            table = "topics",
            columns = listOf("id", "course_id", "name", "description", "order_index", "created_at"),
            semanticTags = listOf("tema", "topico", "categoria", "materia", "asunto"),
            description = "Temas organizacionales vinculados a cursos"
        ),
        "content_items" to SchemaInfo(
            table = "content_items",
            columns = listOf("id", "topic_id", "title", "body", "content_type", "created_at"),
            semanticTags = listOf("contenido", "item", "material", "recurso"),
            description = "Elementos de contenido organizados por temas"
        ),
        "tasks" to SchemaInfo(
            table = "tasks",
            columns = listOf("id", "topic_id", "title", "description", "due_date", "created_at"),
            semanticTags = listOf("tarea", "actividad", "ejercicio", "trabajo", "asignacion"),
            description = "Tareas asociadas a temas específicos"
        ),
        "subscriptions" to SchemaInfo(
            table = "subscriptions",
            columns = listOf("subscriber_username", "creator_username", "subscription_date"),
            semanticTags = listOf("suscripcion", "seguimiento", "seguidor", "subscriptor"),
            description = "Relaciones de suscripción entre usuarios"
        ),
        "task_submissions" to SchemaInfo(
            table = "task_submissions",
            columns = listOf("id", "task_id", "student_username", "file_uri", "file_name", "submission_date", "grade", "feedback", "created_at"),
            semanticTags = listOf("entrega", "envio", "submission", "respuesta"),
            description = "Entregas de tareas por parte de usuarios"
        ),
        "chat_messages" to SchemaInfo(
            table = "chat_messages",
            columns = listOf("id", "message", "is_from_user", "timestamp", "session_id", "created_at"),
            semanticTags = listOf("chat", "mensaje", "conversacion", "comunicacion"),
            description = "Mensajes del chat del sistema"
        ),
        "file_contexts" to SchemaInfo(
            table = "file_contexts",
            columns = listOf("id", "submission_id", "file_name", "file_type", "file_content", "extracted_text", "metadata", "timestamp", "json_content", "content_summary", "created_at"),
            semanticTags = listOf("archivo", "contexto", "documento", "file"),
            description = "Contextos de archivos subidos al sistema"
        ),
        "courses" to SchemaInfo(
            table = "courses",
            columns = listOf("id", "title", "description", "creator_username", "thumbnail_uri", "video_uri", "local_file_path", "duration", "category", "price", "is_premium", "is_published", "creation_date", "last_modified_date", "enrollment_count", "rating", "tags", "timestamp", "created_at"),
            semanticTags = listOf("curso", "cursos", "formacion", "educacion"),
            description = "Cursos estructurados con contenido educativo"
        ),
        "roles" to SchemaInfo(
            table = "roles",
            columns = listOf("id", "nombre", "nivel", "default", "created_at"),
            semanticTags = listOf("rol", "roles", "permiso", "autoridad"),
            description = "Roles y permisos del sistema"
        ),
        "recursos" to SchemaInfo(
            table = "recursos",
            columns = listOf("id", "nombre", "icono", "orden", "padre_id", "interfaz", "created_at"),
            semanticTags = listOf("recurso", "recursos", "herramienta", "material"),
            description = "Recursos disponibles en el sistema"
        ),
        "rol_recursos" to SchemaInfo(
            table = "rol_recursos",
            columns = listOf("rol_id", "recurso_id"),
            semanticTags = listOf("permisos", "acceso", "autorizacion", "rol_recurso"),
            description = "Relación entre roles y recursos (many-to-many)"
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
        val semanticQuery: String,
        val requestedAttributes: List<String> = emptyList(),
        val orderBy: Pair<String, String>? = null, // (column, direction)
        var sqlScript: String = "" // SQL script usado en la consulta (mutable)
    )

    enum class QueryIntent {
        LIST_ALL,          // "dame todos los usuarios"
        SEARCH_SPECIFIC,   // "buscar usuario por email"
        COUNT_AGGREGATE,   // "cuántos videos hay"
        RELATIONSHIP,      // "videos de un usuario"
        ANALYTICAL,        // "tendencias, estadísticas"
        COMPARISON,        // "comparar datos"
        RECENT_DATA,       // "datos recientes"
        GENERAL_ADVICE     // "dame recomendaciones", "cómo hacer", preguntas conceptuales sin SQL
    }

    /**
     * Main entry point for RAG-based query processing
     */
    suspend fun processRAGQuery(userQuery: String): String = withContext(Dispatchers.IO) {
        Log.d(tag, "═══════════════════════════════════════════════")
        Log.d(tag, "🔍 RAG QUERY START")
        Log.d(tag, "Query: $userQuery")
        Log.d(tag, "Supabase configured: ${supabase.isConfigured()}")
        Log.d(tag, "═══════════════════════════════════════════════")
        
        // Special handling for "all tables" requests - MORE SPECIFIC detection
        val isRequestingAllTables = userQuery.lowercase().let { query ->
            // Only trigger if explicitly asking for table list or schema structure
            (query.contains("todas las tablas") && !query.contains(" de la tabla ")) || 
            (query.contains("toda la base") && query.contains("de datos")) ||
            (query.contains("listar") && query.contains("tablas")) ||
            (query.contains("lista de") && query.contains("tablas")) ||
            (query.matches(Regex(".*qu[eé]\\s+tablas.*"))) ||
            (query.matches(Regex(".*cu[aá]ntas\\s+tablas.*"))) ||
            (query.contains("muestra") && query.contains("todas") && query.contains("tablas")) ||
            (query.contains("dame") && query.contains("todas") && query.contains("tablas")) ||
            (query == "esquema" || query.contains("esquema completo") || query.contains("esquema de la base") ||
             query.contains("relaciones") && (query.contains("tablas") || query.contains("base")))
        }
        
        if (isRequestingAllTables) {
            Log.d(tag, "Detected request for all tables, preparing data for LLM to generate response")
            try {
                // Get the dynamic schema from Supabase
                val dbSchema = try {
                    schemaService.getDatabaseSchema(forceRefresh = false)
                } catch (e: Exception) {
                    Log.e(tag, "Error fetching database schema", e)
                    "ESQUEMA NO DISPONIBLE - Error al obtener esquema de Supabase"
                }
                
                // Use the existing DatabaseQueryService to obtain a fresh JSON snapshot
                val dbService = DatabaseQueryService(context)
                val jsonStr = dbService.generateDatabaseJson()
                
                // Combine schema + data for complete context
                val completeData = """
                    ═══════════════════════════════════════════════════════════════════
                    ESQUEMA COMPLETO DE LA BASE DE DATOS:
                    ═══════════════════════════════════════════════════════════════════
                    $dbSchema
                    
                    ═══════════════════════════════════════════════════════════════════
                    DATOS ACTUALES (JSON):
                    ═══════════════════════════════════════════════════════════════════
                    $jsonStr
                """.trimIndent()
                
                // Create a query context for schema exploration
                val schemaContext = QueryContext(
                    intent = QueryIntent.LIST_ALL,
                    targetTables = listOf("personas", "usuarios", "videos", "topics", "content_items", 
                                         "tasks", "subscriptions", "task_submissions", "chat_messages", 
                                         "file_contexts", "courses", "roles", "recursos", "rol_recursos"),
                    relevantColumns = emptyList(),
                    filters = emptyMap(),
                    semanticQuery = userQuery,
                    requestedAttributes = emptyList(),
                    orderBy = null,
                    sqlScript = ""
                )
                
                // Pass the schema + data to the LLM to generate a natural response
                val llmResponse = generateResponse(schemaContext, completeData, userQuery)
                return@withContext llmResponse
                
            } catch (e: Exception) {
                Log.e(tag, "Error processing schema request for LLM", e)
                return@withContext "Error procesando la consulta del esquema: ${e.message}"
            }
        }
        
        try {
            // Quick deterministic shortcut: "tema llamado <name>" or "tema llamado: <name>" -> query Supabase directly
            val temaLlamadoRegex = "tema llamado[:]?\\s*([\\w\\-]+)".toRegex(RegexOption.IGNORE_CASE)
            temaLlamadoRegex.find(userQuery)?.let { match ->
                val topicName = match.groupValues[1]
                Log.d(tag, "🔎 Direct shortcut detected: tema llamado -> $topicName")
                try {
                    val topic = supabase.fetchTopicByName(topicName)
                    if (topic != null) {
                        Log.d(tag, "  ✓ Topic found: id=${topic.id} courseId=${topic.courseId} name=${topic.name}")
                        val course = supabase.fetchCourseById(topic.courseId)
                        val sb = StringBuilder()
                        sb.appendLine("🔎 Resultado directo:")
                        sb.appendLine("Tema: ${topic.name} (ID: ${topic.id})")
                        if (course != null) {
                            sb.appendLine("Pertenece al curso: ${course.title} (ID: ${course.id})")
                            if (course.description.isNotBlank()) sb.appendLine("Descripción del curso: ${course.description}")
                        } else {
                            sb.appendLine("Pertenece al curso con ID: ${topic.courseId} (Información de curso no encontrada)")
                        }
                        return@withContext sb.toString()
                    } else {
                        Log.w(tag, "No se encontró topic con name=$topicName via Supabase")
                        // fallthrough to regular RAG flow
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error resolving topic shortcut", e)
                }
            }

            // 1. Analyze query intent and extract context
            val queryContext = analyzeQueryIntent(userQuery)
            Log.d(tag, "📊 Query Context Detected:")
            Log.d(tag, "  - Intent: ${queryContext.intent}")
            Log.d(tag, "  - Target Tables: ${queryContext.targetTables}")
            Log.d(tag, "  - Relevant Columns: ${queryContext.relevantColumns}")
            Log.d(tag, "  - Filters: ${queryContext.filters}")
            
            // 🆕 Si es una pregunta conceptual/recomendación, no consultar la BD
            if (queryContext.intent == QueryIntent.GENERAL_ADVICE) {
                Log.d(tag, "💡 Consulta de consejo/recomendación detectada - sin consulta SQL")
                val response = generateAdviceResponse(userQuery)
                return@withContext response
            }
            
            // 2. Retrieve relevant database content FROM SUPABASE
            Log.d(tag, "🌐 Fetching data from Supabase...")
            val relevantData = retrieveRelevantData(queryContext)
            Log.d(tag, "✅ Data retrieved: ${relevantData.length} characters")
            
            // 3. Generate context-aware response
            val response = generateResponse(queryContext, relevantData, userQuery)
            
            return@withContext response
            
        } catch (e: Exception) {
            Log.e(tag, "Error processing RAG query", e)
            return@withContext "Error procesando la consulta: ${e.message}"
        }
    }
    
    /**
     * Data class for MCP tool results with metadata
     */
    data class QueryResultWithMetadata(
        val data: Any?,
        val sqlScript: String?,
        val metadata: Map<String, Any>? = null
    )

    /**
     * Process query and return result with metadata for MCP tools
     * This is used by MCPToolService to get structured results
     */
    suspend fun processQueryWithMetadata(userQuery: String): QueryResultWithMetadata = withContext(Dispatchers.IO) {
        Log.d(tag, "🔧 MCP Query Processing: $userQuery")
        
        try {
            // Analyze query intent
            val queryContext = analyzeQueryIntent(userQuery)
            
            // If it's general advice, return text response
            if (queryContext.intent == QueryIntent.GENERAL_ADVICE) {
                val response = generateAdviceResponse(userQuery)
                return@withContext QueryResultWithMetadata(
                    data = mapOf("response" to response),
                    sqlScript = null,
                    metadata = mapOf(
                        "intent" to "GENERAL_ADVICE",
                        "requiresSql" to false
                    )
                )
            }
            
            // Retrieve data from Supabase
            val relevantData = retrieveRelevantData(queryContext)
            
            // Parse the data as JSON
            val dataList = try {
                val jsonArray = org.json.JSONArray(relevantData)
                val list = mutableListOf<Map<String, Any?>>()
                
                for (i in 0 until jsonArray.length()) {
                    val jsonObject = jsonArray.getJSONObject(i)
                    val map = mutableMapOf<String, Any?>()
                    
                    jsonObject.keys().forEach { key ->
                        map[key] = jsonObject.get(key)
                    }
                    
                    list.add(map)
                }
                
                list
            } catch (e: Exception) {
                Log.w(tag, "Could not parse data as JSON array: ${e.message}")
                listOf(mapOf("raw_data" to relevantData))
            }
            
            return@withContext QueryResultWithMetadata(
                data = dataList,
                sqlScript = queryContext.sqlScript.takeIf { it.isNotBlank() },
                metadata = mapOf(
                    "intent" to queryContext.intent.name,
                    "targetTables" to queryContext.targetTables,
                    "rowCount" to dataList.size,
                    "timestamp" to System.currentTimeMillis()
                )
            )
            
        } catch (e: Exception) {
            Log.e(tag, "Error processing MCP query", e)
            return@withContext QueryResultWithMetadata(
                data = null,
                sqlScript = null,
                metadata = mapOf(
                    "error" to (e.message ?: "Unknown error")
                )
            )
        }
    }

    /**
     * Analyze user query to understand intent and extract relevant information using RAGConfig
     * 🆕 DETECCIÓN DINÁMICA: Analiza semánticamente si la pregunta requiere SQL o es conceptual
     */
    private fun analyzeQueryIntent(query: String): QueryContext {
        val normalizedQuery = query.lowercase().trim()
        
        // 🔍 PASO 1: Detectar si la pregunta requiere DATOS ESPECÍFICOS de la BD (necesita SQL)
        val needsDataRetrieval = detectsDataRetrievalIntent(normalizedQuery)
        
        // 🧠 PASO 2: Si NO necesita datos, es una pregunta conceptual (GENERAL_ADVICE)
        if (!needsDataRetrieval) {
            Log.d(tag, "🧠 Pregunta conceptual detectada (no requiere SQL)")
            return QueryContext(
                intent = QueryIntent.GENERAL_ADVICE,
                targetTables = emptyList(),
                relevantColumns = emptyList(),
                filters = emptyMap(),
                semanticQuery = normalizedQuery,
                requestedAttributes = emptyList(),
                orderBy = null,
                sqlScript = ""
            )
        }
        
        Log.d(tag, "📊 Pregunta de datos detectada (requiere SQL)")
        
        // ⚠️ CRÍTICO: Detectar si se refiere a task_submissions (entregas) o tasks (definiciones)
        val isTaskSubmissionQuery = normalizedQuery.contains("enviada") || 
                                     normalizedQuery.contains("enviado") ||
                                     normalizedQuery.contains("entregada") ||
                                     normalizedQuery.contains("entregado") ||
                                     normalizedQuery.contains("entrega") ||
                                     normalizedQuery.contains("envío") ||
                                     normalizedQuery.contains("envio") ||
                                     normalizedQuery.contains("submission") ||
                                     normalizedQuery.contains("ha enviado") ||
                                     normalizedQuery.contains("han enviado")
        
        // Detect query intent using configured keywords
        val intent = when {
            RAGConfig.LIST_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.LIST_ALL
            RAGConfig.SEARCH_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.SEARCH_SPECIFIC
            RAGConfig.COUNT_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.COUNT_AGGREGATE
            RAGConfig.RECENT_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.RECENT_DATA
            RAGConfig.ANALYTICS_KEYWORDS.any { normalizedQuery.contains(it) } -> QueryIntent.ANALYTICAL
            normalizedQuery.contains("de") && (normalizedQuery.contains("usuario") || 
            normalizedQuery.contains("creador")) -> QueryIntent.RELATIONSHIP
            isTaskSubmissionQuery -> QueryIntent.RELATIONSHIP // Entregas requieren joins
            else -> QueryIntent.SEARCH_SPECIFIC
        }
        
        // Identify target tables usando semántica mejorada
        var targetTables = identifyRelevantTablesWithConfig(normalizedQuery)
        
        // ⚠️ CORRECCIÓN CRÍTICA: Si detectamos que es una consulta de entrega, 
        // asegurar que task_submissions esté en targetTables, NO tasks
        if (isTaskSubmissionQuery) {
            targetTables = targetTables.toMutableList().apply {
                // Remover 'tasks' si existe y agregar 'task_submissions'
                remove("tasks")
                if (!contains("task_submissions")) {
                    add("task_submissions")
                }
                // Si preguntan por el curso/creador, asegurar que courses esté incluido
                if (normalizedQuery.contains("curso") || normalizedQuery.contains("creador") || normalizedQuery.contains("dueño")) {
                    if (!contains("courses")) add("courses")
                    if (!contains("topics")) add("topics")
                    if (!contains("tasks")) add("tasks") // Necesario para el JOIN
                }
            }
        }
        
        // Extract relevant columns
        val relevantColumns = extractRelevantColumns(normalizedQuery, targetTables)
        
        // Extract filters - MEJORADO para detectar id= en el contexto correcto
        val filters = mutableMapOf<String, String>()
        if (isTaskSubmissionQuery) {
            // Si menciona "id=" en contexto de entrega, es task_submissions.id
            val idMatch = """id\s*=\s*(\d+)""".toRegex().find(normalizedQuery)
            if (idMatch != null) {
                filters["task_submissions.id"] = idMatch.groupValues[1]
            }
        } else {
            // En otros contextos, extraer filtros normalmente
            filters.putAll(extractFilters(normalizedQuery))
        }
        
        // Extract requested attributes (what user wants to see)
        val requestedAttributes = extractRequestedAttributes(normalizedQuery)
        
        // Extract ordering requirements
        val orderBy = extractOrderBy(normalizedQuery)
        
        return QueryContext(
            intent = intent,
            targetTables = targetTables,
            relevantColumns = relevantColumns,
            filters = filters,
            semanticQuery = normalizedQuery,
            requestedAttributes = requestedAttributes,
            orderBy = orderBy
        )
    }

    /**
     * 🆕 DETECCIÓN DINÁMICA: Determina si la pregunta requiere recuperar datos de la BD (SQL)
     * o si es una pregunta conceptual/recomendación (sin SQL).
     * 
     * CRITERIOS PARA NECESITAR DATOS (true = requiere SQL):
     * 1. Verbos de recuperación: dame, muestra, busca, lista, obtener, cuál, cuántos
     * 2. Identificadores específicos: id=, id:, con id, username=, email=
     * 3. Referencias a entidades concretas: "el curso", "la tarea", "los usuarios"
     * 4. Agregaciones: total, suma, promedio, estadísticas
     * 5. Comparaciones entre datos: mayor que, menor que, comparar
     * 
     * CRITERIOS PARA NO NECESITAR DATOS (false = conceptual):
     * 1. Preguntas sobre capacidades: qué puedes, qué cambios, es posible
     * 2. Solicitudes de explicación: explica, qué es, para qué sirve, cómo funciona
     * 3. Recomendaciones/consejos: recomienda, aconseja, sugiere, mejor práctica
     * 4. Diseño/estructura: normalización, diseño de BD, modelado, arquitectura
     * 5. Preguntas abstractas sin referencia a datos específicos
     */
    private fun detectsDataRetrievalIntent(query: String): Boolean {
        val lower = query.lowercase()
        
        // 🔴 CRITERIO 1: Preguntas explícitamente conceptuales (NO requieren datos)
        val conceptualPatterns = listOf(
            // Capacidades del sistema
            Regex("""qu[eé]\s+(puedes|cambios|se\s+puede|podr[ií]a|har[ií]a)"""),
            Regex("""es\s+posible|puedo\s+hacer|capacidades|funcionalidades"""),
            
            // Solicitudes de explicación
            Regex("""(explica|qu[eé]\s+es|para\s+qu[eé]|c[oó]mo\s+funciona|qu[eé]\s+significa)"""),
            Regex("""(ayuda|gu[ií]a|tutorial|aprende|entender)"""),
            
            // Recomendaciones y mejores prácticas
            Regex("""(recomiend|consej|suger|mejor\s+pr[aá]ctic|buen|estrategi)"""),
            Regex("""(optimiz|mejor|debo|deber[ií]a|conviene)"""),
            
            // Diseño y arquitectura de BD
            Regex("""(forma\s+normal|normalizaci[oó]n|normalizar|1fn|2fn|3fn|4fn|bcnf)"""),
            Regex("""(dise[ñn]o\s+de\s+(base|bd)|estructur|model|arquitectura)"""),
            Regex("""(redundancia|dependencia\s+funcional|integridad|clave\s+candidata)"""),
            Regex("""(relaciones\s+entre|c[oó]mo\s+estructurar|c[oó]mo\s+dise[ñn]ar)"""),
            
            // Auditoría y seguridad (conceptual)
            Regex("""auditor[ií]a|seguridad|protecci[oó]n|validaci[oó]n|buenas\s+pr[aá]cticas""")
        )
        
        for (pattern in conceptualPatterns) {
            if (pattern.containsMatchIn(lower)) {
                Log.d(tag, "🧠 Patrón conceptual detectado: ${pattern.pattern}")
                
                // EXCEPCIÓN: Si menciona datos específicos, SÍ necesita SQL
                val hasSpecificData = lower.matches(""".*\b(id\s*[=:]\s*\d+|username\s*=|email\s*@).*""".toRegex()) ||
                                     lower.contains("dame todos") ||
                                     lower.contains("lista de") ||
                                     lower.contains("muestra los")
                
                if (hasSpecificData) {
                    Log.d(tag, "  ⚠️ Pero tiene referencia a datos específicos, requiere SQL")
                    return true
                }
                
                return false // Es conceptual pura
            }
        }
        
        // 🟢 CRITERIO 2: Verbos de recuperación de datos (SÍ requieren SQL)
        val dataRetrievalVerbs = listOf(
            "dame", "muestra", "busca", "encuentra", "obtener", "recupera",
            "lista", "listar", "ver", "consulta", "consultar",
            "cu[aá]l", "cu[aá]ntos", "cu[aá]ntas", "qui[eé]n", "qui[eé]nes",
            "selecciona", "trae", "dime"
        )
        
        val hasRetrievalVerb = dataRetrievalVerbs.any { verb ->
            lower.matches(""".*\b$verb\b.*""".toRegex())
        }
        
        // 🟢 CRITERIO 3: Identificadores específicos (SÍ requieren SQL)
        val hasSpecificIdentifier = lower.matches(""".*\b(id\s*[=:]\s*\d+|con\s+id\s+\d+|username\s*=|email\s*@).*""".toRegex())
        
        // 🟢 CRITERIO 4: Referencias a entidades concretas con artículos (SÍ requieren SQL)
        val hasEntityReference = lower.matches(""".*\b(el|la|los|las)\s+(curso|video|tarea|usuario|tema|mensaje).*""".toRegex())
        
        // 🟢 CRITERIO 5: Agregaciones numéricas (SÍ requieren SQL)
        val hasAggregation = listOf(
            "total", "suma", "promedio", "media", "estadísticas", "estad[ií]stica",
            "cantidad", "número", "n[uú]mero", "contar", "count"
        ).any { lower.contains(it) }
        
        // 🟢 CRITERIO 6: Comparaciones (SÍ requieren SQL)
        val hasComparison = listOf(
            "mayor", "menor", "m[aá]s", "menos", "entre",
            "comparar", "diferencia", "ordenar", "ordenado"
        ).any { lower.contains(it) }
        
        // 🟢 CRITERIO 7: Patrones de listado (SÍ requieren SQL)
        val hasListingPattern = lower.matches(""".*\b(todos|todas|toda|todo)\s+(los|las|el|la)\s+\w+.*""".toRegex())
        
        // DECISIÓN FINAL
        val needsData = hasRetrievalVerb || hasSpecificIdentifier || hasEntityReference || 
                       hasAggregation || hasComparison || hasListingPattern
        
        Log.d(tag, """
            📊 Análisis de intención de datos:
              - Verbo de recuperación: $hasRetrievalVerb
              - Identificador específico: $hasSpecificIdentifier
              - Referencia a entidad: $hasEntityReference
              - Agregación: $hasAggregation
              - Comparación: $hasComparison
              - Patrón de listado: $hasListingPattern
              ➡️ REQUIERE SQL: $needsData
        """.trimIndent())
        
        return needsData
    }

    /**
     * Use semantic similarity with RAGConfig to identify relevant database tables
     */
    private fun identifyRelevantTablesWithConfig(query: String): List<String> {
        val lower = query.lowercase()
        
        // PRIORITY 1: Entity singular + ID pattern (e.g., "video con id=1", "usuario id 5")
        // This is the MOST specific pattern - user wants ONE record from ONE table
        val entityIdPatterns = mapOf(
            "video" to "videos",
            "curso" to "courses",
            "usuario" to "usuarios",
            "persona" to "personas",
            "topic" to "topics",
            "tarea" to "tasks",
            "task" to "tasks",
            "suscripcion" to "subscriptions",
            "subscription" to "subscriptions",
            "mensaje" to "chat_messages",
            "chat" to "chat_messages",
            "archivo" to "file_contexts",
            "file" to "file_contexts",
            "rol" to "roles",
            "recurso" to "recursos"
        )
        
        for ((entity, tableName) in entityIdPatterns) {
            // Match patterns like "video con id=1", "usuario id 5", "curso de id:3"
            val idPattern = Regex("""\b$entity\b.{0,30}\bid\s*[=:]\s*\d+""")
            if (idPattern.containsMatchIn(lower)) {
                Log.d(tag, "🎯 Entity+ID pattern detected: $tableName (from '$entity' with id filter)")
                return listOf(tableName)
            }
        }
        
        // PRIORITY 2: Explicit table name detection using regex patterns
        // When user says "tabla X", "de la tabla X", "datos de X", etc., they mean ONLY that table
        val explicitTablePatterns = listOf(
            Regex("""(?:tabla|table)\s+([a-z_]+)"""),
            Regex("""(?:de|from)\s+(?:la\s+)?tabla\s+([a-z_]+)"""),
            Regex("""datos\s+(?:de|from)\s+(?:la\s+)?(?:tabla\s+)?([a-z_]+)"""),
            Regex("""(?:de|in|en)\s+([a-z_]+)\b""")
        )
        
        for (pattern in explicitTablePatterns) {
            val match = pattern.find(lower)
            if (match != null) {
                val explicitTable = match.groupValues[1]
                // Verify it's a known table from our schema
                if (schemaDefinitions.containsKey(explicitTable)) {
                    Log.d(tag, "🎯 Explicit table detected: $explicitTable (ignoring semantic matching)")
                    return listOf(explicitTable)
                }
            }
        }
        
        // PRIORITY 3: Single entity detection (singular form without plural context)
        // If user mentions ONE entity type in singular form and no other tables, use only that table
        val mentionedEntities = entityIdPatterns.keys.filter { entity ->
            Regex("""\b$entity\b""").containsMatchIn(lower)
        }
        
        if (mentionedEntities.size == 1) {
            val targetTable = entityIdPatterns[mentionedEntities[0]]!!
            // Check that no other plural table names are explicitly mentioned
            val otherTablesExplicit = schemaDefinitions.keys.any { tableName ->
                tableName != targetTable && lower.contains(tableName)
            }
            
            if (!otherTablesExplicit) {
                Log.d(tag, "🎯 Single entity detected: $targetTable (from singular '${mentionedEntities[0]}')")
                return listOf(targetTable)
            }
        }
        
        // PRIORITY 4: Semantic similarity matching (only if no explicit table found)
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
     * MEJORADO: Detecta múltiples patrones de ID con mayor precisión
     */
    private fun extractFilters(query: String): Map<String, String> {
        val filters = mutableMapOf<String, String>()
        val lowerQuery = query.lowercase()
        
        Log.d(tag, "🔍 Extracting filters from: $query")
        
        // Extract common filter patterns
        val emailPattern = """[\w.-]+@[\w.-]+\.\w+""".toRegex()
        emailPattern.find(query)?.let { match ->
            filters["email"] = match.value
            Log.d(tag, "  ✓ Email filter detected: ${match.value}")
        }
        
        // Extract username mentions
        val usernamePattern = """@(\w+)""".toRegex()
        usernamePattern.find(query)?.let { match ->
            filters["usuario"] = match.groupValues[1]
            Log.d(tag, "  ✓ Username filter detected: ${match.groupValues[1]}")
        }
        
        // MEJORADO: Extract specific IDs with multiple patterns
        // Patterns: "id=1", "id:1", "id 1", "con id=1", "con id 1", "video con id=1", etc.
        val idPatterns = listOf(
            """\bid\s*[=:]\s*(\d+)""".toRegex(),           // id=1, id:1, id =1
            """\bid\s+(\d+)""".toRegex(),                   // id 1
            """con\s+id\s*[=:]?\s*(\d+)""".toRegex(),      // con id=1, con id 1
            """de\s+id\s*[=:]?\s*(\d+)""".toRegex(),       // de id=1, de id 1
            """del\s+id\s*[=:]?\s*(\d+)""".toRegex()       // del id=1, del id 1
        )
        
        for (pattern in idPatterns) {
            val match = pattern.find(lowerQuery)
            if (match != null) {
                val idValue = match.groupValues[1]
                if (idValue.isNotEmpty()) {
                    filters["id"] = idValue
                    Log.d(tag, "  ✅ ID filter detected: $idValue (pattern: ${pattern.pattern})")
                    break // Use first match
                }
            }
        }
        
        if (filters.isEmpty()) {
            Log.d(tag, "  ⚠️ No filters detected")
        }
        
        return filters
    }
    
    /**
     * Extrae los atributos/columnas específicos que el usuario quiere ver en el resultado
     * Ej: "dame el título del curso" -> ["title"]
     * Ej: "dame el username y email" -> ["username", "email"]
     */
    private fun extractRequestedAttributes(query: String): List<String> {
        val attributes = mutableSetOf<String>()
        val lowerQuery = query.lowercase()
        
        // Mapeo de términos naturales a nombres de columnas
        val attributeMapping = mapOf(
            // Titles and names
            "título" to "title",
            "titulo" to "title",
            "nombre" to "name",
            // Descriptions
            "descripción" to "description",
            "descripcion" to "description",
            // User info
            "username" to "username",
            "usuario" to "username",
            "email" to "email",
            "correo" to "email",
            "teléfono" to "telefono",
            "telefono" to "telefono",
            "apellido" to "apellido",
            // Ownership
            "creador" to "creator_username",
            "creator" to "creator_username",
            "dueño" to "creator_username",
            "dueno" to "creator_username",
            "propietario" to "creator_username",
            // Content
            "contenido" to "content",
            "tipo" to "content_type",
            "url" to "uri_string",
            // Dates
            "fecha" to "fecha_creacion",
            // Grades and feedback
            "calificación" to "grade",
            "calificacion" to "grade",
            "nota" to "grade",
            "feedback" to "feedback",
            "comentario" to "feedback",
            // Other
            "precio" to "price",
            "orden" to "order_index",
            "mensaje" to "message",
            "rol" to "rol",
            "nivel" to "nivel",
            "icono" to "icono"
        )
        
        // Detectar atributos mencionados
        for ((term, column) in attributeMapping) {
            if (lowerQuery.contains(term)) {
                attributes.add(column)
                Log.d(tag, "📋 Detected attribute: $term -> $column")
            }
        }
        
        // Si no se detectaron atributos específicos, devolver lista vacía (traer todos)
        val result = attributes.toList()
        Log.d(tag, "📋 Requested attributes: $result")
        return result
    }
    
    /**
     * Detecta si la consulta requiere ordenamiento y extrae el criterio
     * Retorna un par: (columna, dirección) o null si no hay ordenamiento
     */
    private fun extractOrderBy(query: String): Pair<String, String>? {
        val lowerQuery = query.lowercase()
        
        // Patrones de ordenamiento
        val orderPatterns = listOf(
            Regex("""ordenad[oa]s?\s+(?:por|en\s+base\s+a)\s+(?:su\s+)?(?:el\s+)?(\w+)\s*(asc|desc|ascendente|descendente)?"""),
            Regex("""order\s+by\s+(\w+)\s*(asc|desc)?"""),
            Regex("""sorted?\s+by\s+(\w+)\s*(asc|desc)?""")
        )
        
        for (pattern in orderPatterns) {
            pattern.find(lowerQuery)?.let { match ->
                var column = match.groupValues[1]
                
                // Mapear términos naturales a columnas reales
                column = when (column) {
                    "titulo", "título" -> "title"
                    "nombre" -> "name"
                    "fecha" -> "fecha_creacion"
                    "precio" -> "price"
                    "orden" -> "order_index"
                    else -> column
                }
                
                val direction = match.groupValues.getOrNull(2)?.let {
                    when {
                        it.contains("desc") -> "desc"
                        it.contains("asc") -> "asc"
                        else -> "asc"
                    }
                } ?: "asc"
                
                Log.d(tag, "📊 Order BY detected: $column $direction")
                return Pair(column, direction)
            }
        }
        
        return null
    }
    
    /**
     * Resuelve consultas con relaciones complejas (joins implícitos)
     * 
     * ESTRUCTURA DE RELACIONES EN SUPABASE (según Estructura.sql):
     * - tasks.topic_id → topics.id
     * - topics.course_id → courses.id  
     * - courses.creator_username (texto, no FK)
     * - task_submissions.task_id → tasks.id
     * - task_submissions.student_username (texto, no FK)
     * 
     * Ejemplo: "username del usuario dueño del curso de la tarea con id=2"
     * Pasos: 
     * 1. SELECT * FROM tasks WHERE id=2; → obtener task.topic_id
     * 2. SELECT * FROM topics WHERE id=task.topic_id; → obtener topic.course_id
     * 3. SELECT * FROM courses WHERE id=topic.course_id; → obtener course.creator_username
     * 
     * @return Pair<String, String>? - (datos formateados, SQL script) o null si no aplica
     */
    private suspend fun resolveComplexRelationship(query: String): Pair<String, String>? = withContext(Dispatchers.IO) {
        val lowerQuery = query.lowercase()
        
        // Patrón: "usuario/creador/dueño del curso de/al que... tarea con id=X"
        val taskToCourseOwnerPattern = Regex("""(usuario|creador|due[ñn]o|propietario).*curso.*tarea.*id[=:]?\s*(\d+)""")
        val match = taskToCourseOwnerPattern.find(lowerQuery)
        
        if (match != null) {
            val taskId = match.groupValues[2].toLongOrNull() ?: return@withContext null
            Log.d(tag, "🔗 Complex relationship detected: Task $taskId -> Course -> Owner")
            
            try {
                // Step 1: Get task
                val task = supabase.fetchTaskById(taskId)
                if (task == null) {
                    Log.w(tag, "  ⚠️ Task with id=$taskId not found")
                    return@withContext Pair("No se encontró la tarea con id=$taskId", "SELECT * FROM tasks WHERE id = $taskId;")
                }
                Log.d(tag, "  ✓ Found task: ${task.name} (topicId=${task.topicId})")
                
                // Step 2: Get topic to find courseId
                val topic = supabase.fetchTopicById(task.topicId)
                if (topic == null) {
                    Log.w(tag, "  ⚠️ Topic with id=${task.topicId} not found")
                    return@withContext Pair("No se encontró el tema asociado a la tarea", 
                        "-- Paso 1\nSELECT * FROM tasks WHERE id = $taskId;\n\n-- Paso 2 (falló)\nSELECT * FROM topics WHERE id = ${task.topicId};")
                }
                Log.d(tag, "  ✓ Found topic: ${topic.name} (courseId=${topic.courseId})")
                
                // Step 3: Get course to find owner
                val course = supabase.fetchCourseById(topic.courseId)
                if (course == null) {
                    Log.w(tag, "  ⚠️ Course with id=${topic.courseId} not found")
                    return@withContext Pair("No se encontró el curso asociado al tema",
                        "-- Paso 1\nSELECT * FROM tasks WHERE id = $taskId;\n\n-- Paso 2\nSELECT * FROM topics WHERE id = ${task.topicId};\n\n-- Paso 3 (falló)\nSELECT * FROM courses WHERE id = ${topic.courseId};")
                }
                Log.d(tag, "  ✓ Found course: ${course.title} (creator_user_id=${course.creatorUserId})")
                
                // Build detailed response
                val result = """
                    📋 RELACIÓN COMPLETA ENCONTRADA:
                    
                    🔹 Tarea (ID: $taskId)
                       - Nombre: ${task.name}
                       - Descripción: ${task.description ?: "Sin descripción"}
                    
                    🔹 Tema asociado (ID: ${topic.id})
                       - Nombre: ${topic.name}
                       - Descripción: ${topic.description ?: "Sin descripción"}
                    
                    🔹 Curso asociado (ID: ${course.id})
                       - Título: ${course.title}
                       - Descripción: ${course.description ?: "Sin descripción"}
                       - Precio: ${'$'}${course.price}
                    
                    ✅ ID del usuario creador del curso: ${course.creatorUserId}
                """.trimIndent()
                
                // Build SQL script showing all steps
                val sqlScript = """
-- Consulta SQL con JOINs para obtener el creador del curso de una tarea
SELECT c.creator_user_id AS id_dueno_curso,
       c.title AS titulo_curso,
       c.id AS curso_id,
       t.title AS titulo_tarea,
       tp.name AS nombre_tema
FROM public.tasks t
JOIN public.topics tp ON t.topic_id = tp.id
JOIN public.courses c ON tp.course_id = c.id
WHERE t.id = $taskId;
                """.trimIndent()
                
                return@withContext Pair(result, sqlScript)
                
            } catch (e: Exception) {
                Log.e(tag, "❌ Error resolving complex relationship", e)
                return@withContext Pair("Error resolviendo la relación: ${e.message}", 
                    "-- Error al ejecutar consulta compleja\nSELECT * FROM tasks WHERE id = $taskId;")
            }
        }
        
        // Patrón: "curso del usuario que envió/entregó la tarea con id=X"
        val taskSubmissionToCoursePattern = Regex("""curso.*usuario.*(?:envi[oó]|entreg[oó]).*tarea.*id[=:]?\s*(\d+)""")
        val submissionMatch = taskSubmissionToCoursePattern.find(lowerQuery)
        
        if (submissionMatch != null) {
            val taskId = submissionMatch.groupValues[1].toLongOrNull() ?: return@withContext null
            Log.d(tag, "🔗 Complex relationship detected: TaskSubmission -> Task -> Topic -> Course")
            
            try {
                // Get all submissions for this task
                val submissions = supabase.fetchTaskSubmissions().filter { it.taskId == taskId }
                if (submissions.isEmpty()) {
                    return@withContext Pair("No hay entregas para la tarea con id=$taskId",
                        "SELECT * FROM public.task_submissions WHERE task_id = $taskId;")
                }
                
                // Get task details
                val task = supabase.fetchTaskById(taskId)
                if (task == null) {
                    return@withContext Pair("No se encontró la tarea con id=$taskId",
                        "SELECT * FROM public.tasks WHERE id = $taskId;")
                }
                
                // Get topic
                val topic = supabase.fetchTopicById(task.topicId)
                if (topic == null) {
                    return@withContext Pair("No se encontró el tema asociado",
                        "-- Consulta fallida\nSELECT * FROM public.topics WHERE id = ${task.topicId};")
                }
                
                // Get course
                val course = supabase.fetchCourseById(topic.courseId)
                if (course == null) {
                    return@withContext Pair("No se encontró el curso asociado",
                        "-- Consulta fallida\nSELECT * FROM public.courses WHERE id = ${topic.courseId};")
                }
                
                val studentsList = submissions.joinToString(", ") { it.studentUsername }
                
                val result = """
                    📋 INFORMACIÓN COMPLETA:
                    
                    🔹 Tarea (ID: $taskId): ${task.name}
                    🔹 Tema (ID: ${topic.id}): ${topic.name}
                    🔹 Curso (ID: ${course.id}): ${course.title}
                    🔹 ID del creador del curso: ${course.creatorUserId}
                    
                    👥 Estudiantes que enviaron esta tarea: $studentsList
                """.trimIndent()
                
                val sqlScript = """
-- Consulta SQL con JOINs para obtener curso y entregas de una tarea
SELECT c.creator_user_id AS id_dueno_curso,
       c.title AS titulo_curso,
       c.id AS curso_id,
       t.title AS titulo_tarea,
       tp.name AS nombre_tema,
       ts.student_username AS estudiante,
       ts.submission_date AS fecha_entrega,
       ts.grade AS calificacion
FROM public.task_submissions ts
JOIN public.tasks t ON ts.task_id = t.id
JOIN public.topics tp ON t.topic_id = tp.id
JOIN public.courses c ON tp.course_id = c.id
WHERE ts.task_id = $taskId;
                """.trimIndent()
                
                return@withContext Pair(result, sqlScript)
                
            } catch (e: Exception) {
                Log.e(tag, "❌ Error resolving submission relationship", e)
                return@withContext Pair("Error: ${e.message}",
                    "SELECT * FROM task_submissions WHERE task_id = $taskId;")
            }
        }
        
        return@withContext null
    }

    /**
     * Retrieve relevant data based on query context
     * MEJORADO: Aplica filtros de ID ANTES de recuperar datos
     */
    private suspend fun retrieveRelevantData(context: QueryContext): String = withContext(Dispatchers.IO) {
        val result = StringBuilder()
        
        Log.d(tag, "📥 Retrieving data with context:")
        Log.d(tag, "  - Intent: ${context.intent}")
        Log.d(tag, "  - Tables: ${context.targetTables}")
        Log.d(tag, "  - Filters: ${context.filters}")
        
        // 🔗 PRIORIDAD 1: Intentar resolver relaciones complejas
        val complexResult = resolveComplexRelationship(context.semanticQuery)
        if (complexResult != null) {
            Log.d(tag, "✅ Complex relationship resolved successfully")
            val (data, sqlScript) = complexResult
            context.sqlScript = sqlScript
            return@withContext data
        }
        
        // 🎯 PRIORIDAD 2: Si hay filtro de ID, buscar SOLO ese registro específico
        val idFilter = context.filters["id"]
        if (idFilter != null && context.targetTables.size == 1) {
            val tableName = context.targetTables[0]
            Log.d(tag, "🎯 ID FILTER DETECTED: Fetching specific record from $tableName with id=$idFilter")
            Log.d(tag, "  📋 Requested attributes: ${context.requestedAttributes}")
            
            try {
                val (specificData, sqlScript) = getDataById(tableName, idFilter.toInt(), context.requestedAttributes)
                context.sqlScript = sqlScript
                if (specificData.isNotBlank()) {
                    Log.d(tag, "  ✅ Found specific record: ${specificData.take(100)}...")
                    return@withContext specificData
                } else {
                    Log.w(tag, "  ⚠️ No record found with id=$idFilter in $tableName")
                    return@withContext "No se encontró ningún registro con id=$idFilter en la tabla $tableName"
                }
            } catch (e: Exception) {
                Log.e(tag, "  ❌ Error fetching specific record", e)
                return@withContext "Error buscando el registro con id=$idFilter: ${e.message}"
            }
        }
        
        // Si no hay filtro de ID, continuar con lógica normal
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
                    
                    Log.d(tag, "  📋 Fetching list from $tableName (limit=$limit)")
                    
                    // Aplicar ordenamiento si está especificado
                    val (data, sqlScript) = if (context.orderBy != null) {
                        getTableData(
                            tableName, 
                            limit = limit,
                            orderBy = context.orderBy.first,
                            direction = context.orderBy.second
                        )
                    } else {
                        getTableData(tableName, limit = limit)
                    }
                    
                    // Guardar el SQL script en el context
                    if (context.sqlScript.isEmpty()) {
                        context.sqlScript = sqlScript
                    } else {
                        context.sqlScript += "\n\n$sqlScript"
                    }
                    
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
                // Quick pattern: if user asks who has uploaded the most videos, compute locally and return username
                val qLower = context.semanticQuery.lowercase()
                val asksTopUploader = listOf("mas ha subido", "más ha subido", "quien ha subido más", "quien ha subido mas", "usuario que mas", "usuario que más", "el usuario que más").any { qLower.contains(it) }
                if (asksTopUploader && context.targetTables.contains("videos")) {
                    try {
                        val videos = supabase.fetchVideos()
                        if (videos.isEmpty()) {
                            result.append("No hay videos disponibles para analizar.")
                        } else {
                            val counts = videos.groupingBy { it.username ?: "(sin_usuario)" }.eachCount()
                            val top = counts.maxByOrNull { it.value }
                            if (top != null) {
                                result.append("El usuario que más ha subido videos es '${top.key}' con ${top.value} videos.")
                            } else {
                                result.append("No se pudo determinar el usuario con más videos.")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error computing top uploader", e)
                        result.append("Error al calcular el usuario con más videos: ${e.message}")
                    }
                } else {
                    val relationshipData = getRelationshipData(context)
                    result.append(relationshipData)
                }
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
            
            QueryIntent.GENERAL_ADVICE -> {
                // Este caso no debería llegar aquí porque se maneja antes en processRAGQuery
                // Pero lo agregamos por completitud del when exhaustivo
                result.append("Esta consulta requiere recomendaciones conceptuales, no datos de la BD.")
            }
        }
        
        return@withContext result.toString()
    }

    /**
     * NUEVO: Get specific data by ID from any table
     * Esta función recupera UN SOLO registro específico por ID
     * @param tableName Nombre de la tabla
     * @param id ID del registro
     * @param requestedAttributes Lista de atributos específicos que el usuario pidió (vacío = todos)
     * @return Pair<String, String> - (datos formateados, SQL script)
     */
    private suspend fun getDataById(tableName: String, id: Int, requestedAttributes: List<String> = emptyList()): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "🔎 Fetching single record from $tableName with id=$id using SQL")
            Log.d(tag, "  📋 Requested attributes: $requestedAttributes")
            
            // Generar el SQL script correcto con esquema public
            val sqlScript = "SELECT * FROM public.$tableName WHERE id = $id;"
            
            Log.d(tag, "  📜 Executing SQL: $sqlScript")
            
            // Usar executeRawSql para obtener datos directamente de Supabase
            val queryResult = supabase.executeRawSql(sqlScript)
            
            Log.d(tag, "  📊 Query returned ${queryResult.size} rows")
            
            if (queryResult.isEmpty()) {
                return@withContext Pair(
                    "⚠️ No se encontró ningún registro con id=$id en la tabla $tableName\n\n**Consulta SQL ejecutada:**\n```sql\n$sqlScript\n```",
                    sqlScript
                )
            }
            
            // Obtener el primer (y único) registro
            val record = queryResult[0]
            val formatted = StringBuilder()
            
            // 🎯 FILTRAR CAMPOS: Si el usuario pidió campos específicos, mostrar SOLO esos
            if (requestedAttributes.isNotEmpty()) {
                Log.d(tag, "  🎯 Filtering to show only requested attributes: $requestedAttributes")
                formatted.appendLine("✅ **Datos solicitados del registro con id=$id en $tableName:**")
                formatted.appendLine()
                
                // Mapeo de términos naturales a nombres de columnas (mismo mapeo que extractRequestedAttributes)
                val attributeMapping = mapOf(
                    "título" to "title",
                    "titulo" to "title",
                    "nombre" to "nombre",
                    "name" to "name",
                    "usuario" to "usuario",
                    "username" to "username",
                    "email" to "email",
                    "correo" to "email",
                    "contraseña" to "contrasena",
                    "password" to "contrasena",
                    "clave" to "contrasena",
                    "descripción" to "description",
                    "descripcion" to "description",
                    "fecha" to "created_at",
                    "id" to "id",
                    "precio" to "price",
                    "calificación" to "grade",
                    "calificacion" to "grade",
                    "rol" to "rol_id"
                )
                
                var foundAny = false
                for (requestedAttr in requestedAttributes) {
                    // Buscar la columna correspondiente
                    val columnName = attributeMapping[requestedAttr.lowercase()] ?: requestedAttr
                    
                    // Buscar en el record (puede estar en snake_case o camelCase)
                    val value = record[columnName] ?: record[columnName.replace("_", "")] ?: 
                               record.entries.find { it.key.equals(columnName, ignoreCase = true) }?.value
                    
                    if (value != null) {
                        formatted.appendLine("• **$requestedAttr**: $value")
                        foundAny = true
                    } else {
                        Log.w(tag, "  ⚠️ Requested attribute '$requestedAttr' (mapped to '$columnName') not found in record")
                    }
                }
                
                if (!foundAny) {
                    Log.w(tag, "  ⚠️ None of the requested attributes were found in the record")
                    formatted.appendLine("⚠️ No se encontraron los campos solicitados. Mostrando todos los datos disponibles:")
                    formatted.appendLine()
                    record.forEach { (key, value) ->
                        formatted.appendLine("• **$key**: ${value ?: "null"}")
                    }
                }
            } else {
                // Si no pidió campos específicos, mostrar TODO
                Log.d(tag, "  📋 No specific attributes requested, showing all fields")
                formatted.appendLine("✅ **Registro encontrado en $tableName (id=$id):**")
                formatted.appendLine()
                
                record.forEach { (key, value) ->
                    formatted.appendLine("• **$key**: ${value ?: "null"}")
                }
            }
            
            // Agregar el SQL al final
            formatted.appendLine()
            formatted.appendLine("**Consulta SQL ejecutada:**")
            formatted.appendLine("```sql")
            formatted.appendLine(sqlScript)
            formatted.appendLine("```")
            
            Log.d(tag, "  ✅ Data formatted successfully")
            return@withContext Pair(formatted.toString(), sqlScript)
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Error fetching data by id from $tableName: ${e.message}", e)
            val sqlScript = "SELECT * FROM public.$tableName WHERE id = $id;"
            return@withContext Pair(
                "❌ Error obteniendo datos: ${e.message}\n\n" +
                "Posibles causas:\n" +
                "• La tabla no existe en Supabase\n" +
                "• No hay permisos para acceder a la tabla\n" +
                "• La conexión con Supabase falló\n" +
                "• El registro con id=$id no existe\n\n" +
                "**Consulta SQL que falló:**\n```sql\n$sqlScript\n```",
                sqlScript
            )
        }
    }
    
    /**
     * Get table data with optional limit and ordering using RAGConfig
     * @param orderBy Column name to order by (null = default ordering by id)
     * @param direction "asc" or "desc"
     * @return Pair<String, String> - (datos formateados, SQL script)
     */
    private suspend fun getTableData(
        tableName: String, 
        limit: Int = RAGConfig.MAX_RETRIEVED_ITEMS,
        orderBy: String? = null,
        direction: String = "asc"
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            val orderInfo = if (orderBy != null) " (order by: $orderBy $direction)" else ""
            Log.d(tag, "📥 Fetching from Supabase table: $tableName (limit=$limit)$orderInfo")
            
            // Generar el SQL script
            val sqlScript = if (orderBy != null) {
                "SELECT * FROM $tableName ORDER BY $orderBy ${direction.uppercase()} LIMIT $limit;"
            } else {
                "SELECT * FROM $tableName LIMIT $limit;"
            }
            
            val result = when (tableName) {
                "personas" -> {
                    val personas = supabase.fetchPersonas().let { list ->
                        if (orderBy != null) {
                            when (orderBy) {
                                "id" -> if (direction == "desc") list.sortedByDescending { it.id } else list.sortedBy { it.id }
                                "nombre", "nombres" -> if (direction == "desc") list.sortedByDescending { it.nombres } else list.sortedBy { it.nombres }
                                "apellido", "apellidos" -> if (direction == "desc") list.sortedByDescending { it.apellidos } else list.sortedBy { it.apellidos }
                                else -> list.sortedBy { it.id }
                            }
                        } else {
                            list.sortedBy { it.id }
                        }
                    }.take(limit)
                    Log.d(tag, "  ✓ Fetched ${personas.size} personas from Supabase")
                    formatPersonasData(personas)
                }
                "usuarios" -> {
                    val usuarios = supabase.fetchUsuarios().let { list ->
                        if (orderBy != null) {
                            when (orderBy) {
                                "id" -> if (direction == "desc") list.sortedByDescending { it.id } else list.sortedBy { it.id }
                                "username", "usuario" -> if (direction == "desc") list.sortedByDescending { it.usuario } else list.sortedBy { it.usuario }
                                else -> list.sortedBy { it.id }
                            }
                        } else {
                            list.sortedBy { it.id }
                        }
                    }.take(limit)
                    Log.d(tag, "  ✓ Fetched ${usuarios.size} usuarios from Supabase")
                    formatUsuariosData(usuarios)
                }
                "videos" -> {
                    val videos = if (orderBy != null) {
                        supabase.fetchVideosOrdered(orderBy, direction).take(limit)
                    } else {
                        supabase.fetchVideos().sortedBy { it.id }.take(limit)
                    }
                    Log.d(tag, "  ✓ Fetched ${videos.size} videos from Supabase$orderInfo")
                    formatVideosData(videos)
                }
                "topics" -> {
                    val topics = if (orderBy != null) {
                        supabase.fetchTopicsOrdered(orderBy, direction).take(limit)
                    } else {
                        supabase.fetchTopics().sortedBy { it.id }.take(limit)
                    }
                    Log.d(tag, "  ✓ Fetched ${topics.size} topics from Supabase$orderInfo")
                    formatTopicsData(topics)
                }
                "content_items" -> {
                    val contentItems = if (orderBy != null) {
                        supabase.fetchContentItemsOrdered(orderBy, direction).take(limit)
                    } else {
                        supabase.fetchContentItems().sortedBy { it.id }.take(limit)
                    }
                    Log.d(tag, "  ✓ Fetched ${contentItems.size} content_items from Supabase$orderInfo")
                    formatContentItemsData(contentItems)
                }
                "tasks" -> {
                    val tasks = if (orderBy != null) {
                        supabase.fetchTasksOrdered(orderBy, direction).take(limit)
                    } else {
                        supabase.fetchTasks().sortedBy { it.id }.take(limit)
                    }
                    Log.d(tag, "  ✓ Fetched ${tasks.size} tasks from Supabase$orderInfo")
                    formatTasksData(tasks)
                }
                "subscriptions" -> {
                    // Subscription entity has no `id`; fetch from Supabase and sort by subscriptionDate
                    val subscriptions = supabase.fetchSubscriptions().let { list ->
                        if (orderBy != null) {
                            when (orderBy) {
                                "subscriber_id" -> if (direction == "desc") list.sortedByDescending { it.subscriberId } else list.sortedBy { it.subscriberId }
                                "creator_id" -> if (direction == "desc") list.sortedByDescending { it.creatorId } else list.sortedBy { it.creatorId }
                                else -> list.sortedBy { it.subscriptionDate }
                            }
                        } else {
                            list.sortedBy { it.subscriptionDate }
                        }
                    }.take(limit)
                    formatSubscriptionsData(subscriptions)
                }
                "task_submissions" -> {
                    val submissions = supabase.fetchTaskSubmissions().let { list ->
                        if (orderBy != null) {
                            when (orderBy) {
                                "id" -> if (direction == "desc") list.sortedByDescending { it.id } else list.sortedBy { it.id }
                                "task_id" -> if (direction == "desc") list.sortedByDescending { it.taskId } else list.sortedBy { it.taskId }
                                "grade" -> if (direction == "desc") list.sortedByDescending { it.grade ?: 0f } else list.sortedBy { it.grade ?: 0f }
                                else -> list.sortedBy { it.id }
                            }
                        } else {
                            list.sortedBy { it.id }
                        }
                    }.take(limit)
                    formatTaskSubmissionsData(submissions)
                }
                "chat_messages" -> {
                    val messages = supabase.fetchChatMessages().sortedBy { it.id }.take(limit)
                    formatChatMessagesData(messages)
                }
                "file_contexts" -> {
                    val contexts = supabase.fetchFileContexts().sortedBy { it.id }.take(limit)
                    formatFileContextsData(contexts)
                }
                "courses" -> {
                    val courses = if (orderBy != null) {
                        supabase.fetchCoursesOrdered(orderBy, direction).take(limit)
                    } else {
                        supabase.fetchCourses().sortedBy { it.id }.take(limit)
                    }
                    Log.d(tag, "  ✓ Fetched ${courses.size} courses from Supabase$orderInfo")
                    formatCoursesData(courses)
                }
                "roles" -> {
                    val roles = supabase.fetchRoles().let { list ->
                        if (orderBy != null) {
                            when (orderBy) {
                                "id" -> if (direction == "desc") list.sortedByDescending { it.id } else list.sortedBy { it.id }
                                "nombre", "name" -> if (direction == "desc") list.sortedByDescending { it.nombre } else list.sortedBy { it.nombre }
                                "nivel" -> if (direction == "desc") list.sortedByDescending { it.nivel } else list.sortedBy { it.nivel }
                                else -> list.sortedBy { it.id }
                            }
                        } else {
                            list.sortedBy { it.id }
                        }
                    }.take(limit)
                    formatRolesData(roles)
                }
                "recursos" -> {
                    val recursos = supabase.fetchRecursos().sortedBy { it.id }.take(limit)
                    formatRecursosData(recursos)
                }
                "rol_recursos" -> {
                    // RolRecurso has composite keys (rolId, recursoId). Fetch from Supabase and sort by those keys.
                    val rolRecursos = supabase.fetchRolRecursos().sortedWith(compareBy({ it.rolId }, { it.recursoId }))
                    formatRolRecursosData(rolRecursos.take(limit))
                }
                else -> {
                    Log.w(tag, "  ⚠️ Tabla desconocida: $tableName")
                    "Tabla no encontrada: $tableName. Tablas disponibles: personas, usuarios, videos, topics, content_items, tasks, subscriptions, task_submissions, chat_messages, file_contexts, courses, roles, recursos, rol_recursos"
                }
            }
            Log.d(tag, "📤 Returning formatted data for $tableName")
            Log.d(tag, "  📜 SQL: $sqlScript")
            return@withContext Pair(result, sqlScript)
        } catch (e: Exception) {
            Log.e(tag, "❌ Error getting data from $tableName", e)
            val fallbackSql = if (orderBy != null) {
                "SELECT * FROM $tableName ORDER BY $orderBy ${direction.uppercase()} LIMIT $limit;"
            } else {
                "SELECT * FROM $tableName LIMIT $limit;"
            }
            return@withContext Pair("Error obteniendo datos de $tableName: ${e.message}", fallbackSql)
        }
    }

    /**
     * Get table count
     */
    private suspend fun getTableCount(tableName: String): Int = withContext(Dispatchers.IO) {
        try {
            when (tableName) {
                "personas" -> supabase.fetchPersonas().size
                "usuarios" -> supabase.fetchUsuarios().size
                "videos" -> supabase.fetchVideos().size
                "topics" -> supabase.fetchTopics().size
                "content_items" -> supabase.fetchContentItems().size
                "tasks" -> supabase.fetchTasks().size
                "subscriptions" -> supabase.fetchSubscriptions().size
                "task_submissions" -> supabase.fetchTaskSubmissions().size
                "chat_messages" -> supabase.fetchChatMessages().size
                "file_contexts" -> supabase.fetchFileContexts().size
                "courses" -> supabase.fetchCourses().size
                "roles" -> supabase.fetchRoles().size
                "recursos" -> supabase.fetchRecursos().size
                "rol_recursos" -> supabase.fetchRolRecursos().size
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
                    val usuarios = supabase.fetchUsuarios()
                    val filtered = usuarios.filter { usuario ->
                        val usuarioFilter = filters["usuario"]
                        if (usuarioFilter != null) {
                            usuario.usuario.contains(usuarioFilter, ignoreCase = true)
                        } else {
                            // General text search
                            query.split(" ").any { term ->
                                usuario.usuario.contains(term, ignoreCase = true)
                            }
                        }
                    }
                    formatUsuariosData(filtered)
                }
                "videos" -> {
                    val videos = supabase.fetchVideos()
                    val filtered = videos.filter { video ->
                        query.split(" ").any { term ->
                            (video.title ?: "").contains(term, ignoreCase = true) ||
                            (video.description ?: "").contains(term, ignoreCase = true)
                        }
                    }
                    formatVideosData(filtered)
                }
                "topics" -> {
                    Log.d(tag, "🔍 Searching topics table for query: $query")
                    val topics = supabase.fetchTopics().sortedBy { it.id }
                    Log.d(tag, "  ✓ Fetched ${topics.size} topics from Supabase")
                    
                    // Extract search terms from query (e.g., "tema llamado 778" -> "778")
                    val searchTerms = query.split(" ").filter { it.isNotBlank() }
                    Log.d(tag, "  🔎 Search terms: $searchTerms")
                    
                    val filtered = topics.filter { topic ->
                        searchTerms.any { term ->
                            topic.name.contains(term, ignoreCase = true) ||
                            (topic.description ?: "").contains(term, ignoreCase = true)
                        }
                    }
                    Log.d(tag, "  📊 Filtered to ${filtered.size} matching topics")
                    
                    if (filtered.isEmpty()) {
                        Log.w(tag, "  ⚠️ No topics found matching search terms")
                        return@withContext "No se encontraron temas que coincidan con: ${searchTerms.joinToString(", ")}"
                    }
                    
                    // For each matching topic, resolve the course information
                    val result = StringBuilder()
                    result.appendLine("📚 Temas encontrados (${filtered.size}):")
                    result.appendLine()
                    
                    filtered.forEach { topic ->
                        result.appendLine("🔹 Tema: ${topic.name}")
                        if (topic.description.isNotBlank()) {
                            result.appendLine("   Descripción: ${topic.description}")
                        }
                        result.appendLine("   ID del tema: ${topic.id}")
                        result.appendLine("   Orden: ${topic.orderIndex}")
                        
                        // Resolve course information
                        try {
                            val course = supabase.fetchCourseById(topic.courseId)
                            if (course != null) {
                                Log.d(tag, "  ✓ Resolved course: id=${course.id}, title=${course.title}")
                                result.appendLine("   ✅ Pertenece al curso:")
                                result.appendLine("      - ID: ${course.id}")
                                result.appendLine("      - Título: ${course.title}")
                                if (course.description.isNotBlank()) {
                                    result.appendLine("      - Descripción: ${course.description}")
                                }
                                if (course.price > 0) {
                                    result.appendLine("      - Precio: $${course.price}")
                                }
                            } else {
                                Log.w(tag, "  ⚠️ Course with id=${topic.courseId} not found")
                                result.appendLine("   ⚠️ Curso no encontrado (ID: ${topic.courseId})")
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "  ❌ Error fetching course ${topic.courseId}", e)
                            result.appendLine("   ❌ Error al obtener información del curso: ${e.message}")
                        }
                        result.appendLine()
                    }
                    
                    result.toString()
                }
                // Add more table-specific search logic as needed
                else -> {
                    // getTableData returns Pair(formattedData, sqlScript). We only need the formatted data here.
                    val (data, sql) = getTableData(tableName, 20)
                    // Note: searchInTable doesn't have access to QueryContext, so we cannot aggregate SQL here.
                    data
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error searching in $tableName", e)
            "Error buscando en $tableName: ${e.message}"
        }
    }

    /**
     * Get relationship data - MEJORADO para manejar task_submissions correctamente
     */
    private suspend fun getRelationshipData(context: QueryContext): String = withContext(Dispatchers.IO) {
        val result = StringBuilder()
        val sqlScripts = StringBuilder()
        
        try {
            // ⚠️ CASO CRÍTICO: Consulta sobre ENTREGAS de tareas (task_submissions)
            if (context.targetTables.contains("task_submissions") && 
                (context.targetTables.contains("courses") || 
                 context.semanticQuery.contains("curso") ||
                 context.semanticQuery.contains("creador") ||
                 context.semanticQuery.contains("dueño"))) {
                
                Log.d(tag, "Detected task_submission relationship query")
                
                // Extraer el ID de la entrega desde los filtros
                val submissionId = context.filters["task_submissions.id"]?.toLongOrNull() 
                    ?: context.filters["id"]?.toLongOrNull()
                
                if (submissionId != null) {
                    Log.d(tag, "Querying task_submission with id=$submissionId")
                    
                    // Generar SQL correcto con JOINs desde task_submissions
                    val sql = """
                        SELECT c.creator_username AS username_dueno_curso,
                               c.title AS titulo_curso,
                               c.id AS curso_id,
                               t.title AS titulo_tarea,
                               tp.name AS nombre_tema,
                               ts.student_username AS estudiante,
                               ts.submission_date AS fecha_entrega,
                               ts.grade AS calificacion,
                               ts.feedback AS retroalimentacion
                        FROM public.task_submissions ts
                        JOIN public.tasks t ON ts.task_id = t.id
                        JOIN public.topics tp ON t.topic_id = tp.id
                        JOIN public.courses c ON tp.course_id = c.id
                        WHERE ts.id = $submissionId;
                    """.trimIndent()
                    
                    // Guardar el SQL para mostrarlo al final
                    sqlScripts.appendLine(sql)
                    context.sqlScript = sql
                    
                    // Ejecutar la consulta en Supabase
                    try {
                        val queryResult = supabase.executeRawSql(sql)
                        Log.d(tag, "SQL executed successfully. Result: $queryResult")
                        
                        // Parsear resultado
                        val jsonArray = org.json.JSONArray(queryResult)
                        if (jsonArray.length() > 0) {
                            val jsonObj = jsonArray.getJSONObject(0)
                            val creatorUserId = jsonObj.optLong("id_dueno_curso", -1)
                            val courseTitle = jsonObj.optString("titulo_curso", "N/A")
                            val courseId = jsonObj.optLong("curso_id", -1)
                            val taskTitle = jsonObj.optString("titulo_tarea", "N/A")
                            val topicName = jsonObj.optString("nombre_tema", "N/A")
                            val studentUsername = jsonObj.optString("estudiante", "N/A")
                            val submissionDate = jsonObj.optLong("fecha_entrega", 0)
                            val grade = jsonObj.optDouble("calificacion", -1.0)
                            val feedback = jsonObj.optString("retroalimentacion", "Sin retroalimentación")
                            
                            result.appendLine("✅ ENTREGA DE TAREA (ID: $submissionId)")
                            result.appendLine()
                            result.appendLine("👤 ESTUDIANTE: $studentUsername")
                            result.appendLine()
                            result.appendLine("📝 TAREA ENTREGADA: $taskTitle")
                            result.appendLine("📚 TEMA: $topicName")
                            result.appendLine()
                            result.appendLine("🎓 CURSO:")
                            result.appendLine("   - Título: $courseTitle")
                            result.appendLine("   - ID: $courseId")
                            result.appendLine("   - ID Dueño/Creador: $creatorUserId")
                            result.appendLine()
                            if (grade >= 0) {
                                result.appendLine("📊 CALIFICACIÓN: $grade")
                            }
                            if (feedback.isNotBlank() && feedback != "Sin retroalimentación") {
                                result.appendLine("💬 RETROALIMENTACIÓN: $feedback")
                            }
                            
                            Log.d(tag, "Successfully retrieved creator_user_id: $creatorUserId")
                        } else {
                            result.appendLine("⚠️ No se encontró la entrega con ID: $submissionId")
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error executing SQL for task_submission", e)
                        result.appendLine("❌ Error ejecutando consulta SQL: ${e.message}")
                    }
                } else {
                    result.appendLine("⚠️ No se especificó ID de entrega en la consulta")
                }
            }
            // Caso original: Videos por usuario
            else if (context.targetTables.contains("videos") && context.filters.containsKey("usuario")) {
                val username = context.filters["usuario"]!!
                val userVideos = supabase.fetchVideosByUsername(username)
                result.append("Videos creados por $username:\n")
                result.append(formatVideosData(userVideos))
            }
            // Caso original: Tareas en topics  
            else if (context.targetTables.contains("tasks") && context.targetTables.contains("topics")) {
                val topics = supabase.fetchTopics().take(10)
                topics.forEach { topic ->
                    val tasks = supabase.fetchTasks().filter { it.topicId == topic.id }
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
                    val usuarios = supabase.fetchUsuarios()
                        .sortedByDescending { it.id }
                        .take(10)
                    formatUsuariosData(usuarios)
                }
                "videos" -> {
                    val videos = supabase.fetchVideos()
                        .sortedByDescending { it.timestamp }
                        .take(10)
                    formatVideosData(videos)
                }
                "subscriptions" -> {
                    val subscriptions = supabase.fetchSubscriptions()
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
                val videos = supabase.fetchVideos()
                val usuarios = supabase.fetchUsuarios()
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
     * MEJORADO: Siempre usa el LLM para generar respuestas naturales, eliminando atajos determinísticos
     */
    private suspend fun generateResponse(context: QueryContext, relevantData: String, originalQuery: String): String {
        val mspClient = MSPClient(this.context)
        val localLlamaService = LocalLlamaService(this.context)

        Log.d(tag, "🤖 Generating LLM response for query: ${originalQuery.take(50)}...")
        Log.d(tag, "  - Intent: ${context.intent}")
        Log.d(tag, "  - Data length: ${relevantData.length} chars")
        Log.d(tag, "  - Has ID filter: ${context.filters.containsKey("id")}")
        
        // 🎯 SIEMPRE usar el LLM para generar respuestas naturales
        // Esto elimina el problema de respuestas prefabricadas
        
        // Create optimized prompt with retrieved context
        val prompt = buildOptimizedPrompt(context, relevantData, originalQuery)
        Log.d(tag, "  📝 Prompt length: ${prompt.length} chars")
        
        // Log warning for very large prompts but allow them (Ollama can handle it)
        if (prompt.length > 100000) {
            Log.w(tag, "⚠️ Large prompt (${prompt.length} chars) - Ollama should handle this fine")
        }

        return try {
            Log.d(tag, "  🔄 Attempting MSPClient (primary LLM)...")
            
            // Try MSP client first. MSPClient may return an error-string instead of throwing.
            val mspResult = try { 
                mspClient.sendPrompt(prompt, includeHistory = false, includeDatabaseContext = false)
            } catch (e: Exception) {
                Log.w(tag, "  ❌ MSPClient threw an exception: ${e.javaClass.simpleName} - ${e.message}")
                "Error: ${e.message}"
            }

            // If MSP returned an explicit error message, consider it a failure and fallback
            if (mspResult.isBlank() || mspResult.startsWith("Error:") || mspResult.contains("No se pudo conectar al servidor LLM")) {
                Log.w(tag, "  ⚠️ MSPClient failed or returned error: ${mspResult.take(100)}")
                Log.d(tag, "  🔄 Attempting LocalLlamaService (fallback LLM)...")
                
                try {
                    val local = localLlamaService.generateResponse(prompt)
                    if (local.isNotBlank() && !local.startsWith("Error:")) {
                        Log.d(tag, "  ✓ LocalLlamaService succeeded")
                        return local
                    }
                    Log.w(tag, "  ⚠️ LocalLlamaService also failed: ${local.take(100)}")
                } catch (e2: Exception) {
                    Log.e(tag, "  ❌ LocalLlama threw exception: ${e2.javaClass.simpleName} - ${e2.message}")
                }

                // If both LLMs fail or provided no useful output, return the structured direct response
                Log.w(tag, "  ⚠️ Both LLMs failed, using direct formatted response")
                val directResponse = formatDirectResponse(context, relevantData, originalQuery)
                
                // Add diagnostic information
                return """
                    🌐 IDIOMA: Respuesta en ESPAÑOL
                    ⚠️ No se pudo conectar al servidor LLM. Mostrando datos sin procesar:
                    
                    $directResponse
                    
                    💡 Para obtener respuestas procesadas por IA:
                    1. Verifica que Ollama esté ejecutándose
                    2. Ejecuta: ollama serve
                    3. Confirma la conexión de red
                """.trimIndent()
            }

            // 🆕 CRÍTICO: SIEMPRE extraer SQL del resultado del LLM y ejecutarlo
            Log.d(tag, "  🔍 Checking for SQL in LLM response...")
            val sqlExecutedResult = executeSqlFromLlmResponse(mspResult, originalQuery)
            if (sqlExecutedResult != null) {
                Log.d(tag, "  ✅ SQL extracted and executed successfully - returning REAL DATA from Supabase")
                return sqlExecutedResult
            } else {
                Log.d(tag, "  ⚠️ No executable SQL found in LLM response, returning LLM text as-is")
            }

            // Otherwise return successful MSP result (if no SQL was found)
            Log.d(tag, "  ✓ MSPClient succeeded with response length: ${mspResult.length} chars")
            mspResult
        } catch (e: Exception) {
            Log.e(tag, "❌ Unexpected error generating response: ${e.javaClass.simpleName} - ${e.message}", e)
            val directResponse = formatDirectResponse(context, relevantData, originalQuery)
            return """
                🌐 IDIOMA: Respuesta en ESPAÑOL
                ⚠️ Error inesperado al procesar la consulta: ${e.message}
                
                Datos recuperados:
                $directResponse
            """.trimIndent()
        }
    }
    
    /**
     * 🆕 Extrae SQL del response del LLM, lo ejecuta en Supabase y devuelve datos reales
     */
    private suspend fun executeSqlFromLlmResponse(llmResponse: String, originalQuery: String): String? = withContext(Dispatchers.IO) {
        try {
            // Buscar bloques SQL en la respuesta del LLM
            val sqlPattern = """```sql\s*(.*?)\s*```""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val sqlMatch = sqlPattern.find(llmResponse) ?: return@withContext null
            
            val sqlQuery = sqlMatch.groupValues[1].trim()
            if (sqlQuery.isBlank() || !sqlQuery.lowercase().contains("select")) {
                Log.w(tag, "  ⚠️ No valid SELECT query found in LLM response")
                return@withContext null
            }
            
            Log.d(tag, "  🔍 SQL extracted from LLM: ${sqlQuery.take(100)}...")
            
            // Ejecutar el SQL en Supabase
            val queryResult = supabase.executeRawSql(sqlQuery)
            Log.d(tag, "  ✅ SQL executed, result count: ${queryResult.size} rows")
            
            // Convertir resultado a JSON
            val jsonArray = org.json.JSONArray(queryResult)
            
            if (jsonArray.length() == 0) {
                Log.d(tag, "  ⚠️ Query returned 0 results")
                return@withContext """
🔍 CONSULTA EJECUTADA EN SUPABASE

La consulta se ejecutó correctamente, pero **no se encontraron resultados**.

**Script SQL ejecutado:**
```sql
$sqlQuery
```

❌ **No se encontraron datos que coincidan con los criterios especificados.**

💡 Posibles razones:
   • El registro no existe en la base de datos
   • Los filtros no coinciden con ningún dato (ej: username incorrecto)
   • La tabla está vacía

🔎 Sugerencias:
   • Verifica que los valores de búsqueda sean correctos
   • Consulta primero qué datos existen: "dame todos los usuarios"
   • Revisa si el nombre está bien escrito (es sensible a mayúsculas/minúsculas)
                """.trimIndent()
            }
            
            // Formatear el resultado de forma legible
            val formattedData = StringBuilder()
            formattedData.appendLine("✅ DATOS OBTENIDOS DE SUPABASE")
            formattedData.appendLine()
            
            // Detectar el tipo de consulta para formatear apropiadamente
            val isTaskQuery = originalQuery.lowercase().contains("tarea")
            val isUserQuery = originalQuery.lowercase().contains("usuario")
            
            // Formatear cada resultado
            for (i in 0 until jsonArray.length()) {
                val jsonObj = jsonArray.getJSONObject(i)
                
                if (i > 0) formattedData.appendLine("─".repeat(50))
                formattedData.appendLine("📋 REGISTRO ${i + 1}:")
                
                // Iterar todas las claves del objeto JSON
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = jsonObj.opt(key)
                    
                    // Formatear valores null
                    val displayValue = when {
                        value == null || value == org.json.JSONObject.NULL -> "N/A"
                        value is String && value.isBlank() -> "(vacío)"
                        else -> value.toString()
                    }
                    
                    // Formato legible para cada campo
                    formattedData.appendLine("  • $key: $displayValue")
                }
                formattedData.appendLine()
            }
            
            formattedData.appendLine()
            formattedData.appendLine("**Script SQL usado:**")
            formattedData.appendLine("```sql")
            formattedData.appendLine(sqlQuery)
            formattedData.appendLine("```")
            
            return@withContext formattedData.toString()
            
        } catch (e: Exception) {
            Log.e(tag, "  ❌ Error executing SQL from LLM response", e)
            return@withContext null
        }
    }

    /**
     * Build optimized prompt for LLM using RAGConfig templates
     * MEJORADO: Crea prompts específicos con mayor contexto y argumentación
     * INCLUYE: Esquema dinámico de la base de datos de Supabase
     */
    private suspend fun buildOptimizedPrompt(context: QueryContext, relevantData: String, originalQuery: String): String {
        // Obtener esquema dinámico de la base de datos
        val dbSchema = try {
            schemaService.getDatabaseSchema(forceRefresh = false)
        } catch (e: Exception) {
            Log.e(tag, "Error fetching database schema, using fallback", e)
            "ESQUEMA NO DISPONIBLE - Error al obtener esquema de Supabase"
        }
        
        // 🎯 Prompt especial para consultas de esquema completo y relaciones
        val isSchemaQuery = originalQuery.lowercase().let { query ->
            query.contains("esquema") || 
            (query.contains("todas") && query.contains("tablas")) ||
            (query.contains("relaciones") && (query.contains("tablas") || query.contains("1 a m") || query.contains("1-a-muchos"))) ||
            query.contains("muestra") && query.contains("base de datos") ||
            query.contains("lista de tablas")
        }
        
        if (isSchemaQuery) {
            Log.d(tag, "  🗂️ Building schema-specific prompt")
            
            return """
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL.

ESQUEMA COMPLETO DE LA BASE DE DATOS (PostgreSQL/Supabase):
$dbSchema

DATOS ACTUALES DE LA BASE DE DATOS:
$relevantData

CONSULTA DEL USUARIO: "$originalQuery"

INSTRUCCIONES CRÍTICAS - FORMATO DE RESPUESTA:

Para consultas de ESQUEMA o RELACIONES:
- Lista directamente las tablas o relaciones
- NO agregues encabezados decorativos
- Formato simple y directo

EJEMPLOS:

Query: "¿cuántas tablas hay?"
Respuesta: "14"

Query: "lista todas las tablas"
Respuesta: "1. personas
2. usuarios
3. videos
4. topics
5. content_items
6. tasks
7. subscriptions
8. task_submissions
9. chat_messages
10. file_contexts
11. courses
12. roles
13. recursos
14. rol_recursos"

Query: "lista las foreign keys de la tabla usuarios"
Respuesta: "usuarios.rol_id → roles.id [CASCADE]"

Query: "¿cuántas relaciones hay en total?"
Respuesta: "X" (donde X es el número total de foreign keys)

RESPONDE AHORA CON ESTE FORMATO DIRECTO Y SIMPLE.
            """.trimIndent()
        }
        
        // 🎯 Prompt específico para consultas con ID (más directo y preciso)
        val hasIdFilter = context.filters.containsKey("id")
        
        if (hasIdFilter) {
            val idValue = context.filters["id"]
            Log.d(tag, "  🎯 Building ID-specific prompt for id=$idValue")
            
            val sqlScript = if (context.sqlScript.isNotEmpty()) {
                """

📜 SCRIPT SQL EJECUTADO:
```sql
${context.sqlScript}
```
""".trimIndent()
            } else ""
            
            return """
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL.

DATOS RECUPERADOS DE SUPABASE:
$relevantData

CONSULTA ORIGINAL: "$originalQuery"

INSTRUCCIONES CRÍTICAS - FORMATO DE RESPUESTA OBLIGATORIO:
1. **NO agregues encabezados decorativos** como "✅ DATOS OBTENIDOS", "📋 REGISTRO", emojis, separadores, etc.
2. **SOLO responde con los datos directamente**, sin explicaciones previas
3. **Formato SIMPLE y DIRECTO:**
   - Si es una consulta de CONTEO: responde SOLO el número
   - Si es una consulta de un campo específico: responde SOLO el valor
   - Si son múltiples campos: lista solo los valores solicitados, uno por línea

EJEMPLOS DE RESPUESTAS CORRECTAS:

Query: "¿cuántos usuarios hay?"
Respuesta: "9"

Query: "¿cuál es el título del curso con id=1?"
Respuesta: "Introducción a Python"

Query: "dame el username y email del usuario con id=5"
Respuesta: "username: jesus
email: jesus@example.com"

Query: "lista los títulos de todos los videos"
Respuesta: "lol
Mi video
sub
subj
load"

4. **AL FINAL** (después de los datos), agrega en una nueva línea:

**Script SQL:**
\`\`\`sql
${context.sqlScript ?: "N/A"}
\`\`\`

RESPONDE AHORA CON ESTE FORMATO EXACTO:
            """.trimIndent()
        }
        
        // 🔗 Prompt para consultas con relaciones complejas
        val hasRelationship = context.targetTables.size > 1 || 
                             originalQuery.lowercase().let { it.contains("dueño") || it.contains("creador") || it.contains("pertenece") }
        
        if (hasRelationship) {
            val sqlScript = if (context.sqlScript.isNotEmpty()) {
                """

📜 SCRIPTS SQL EJECUTADOS:
```sql
${context.sqlScript}
```
""".trimIndent()
            } else ""
            
            return """
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL.

ESQUEMA DE LA BASE DE DATOS (PostgreSQL/Supabase):
$dbSchema

DATOS RELACIONADOS RECUPERADOS:
$relevantData$sqlScript

CONSULTA ORIGINAL: "$originalQuery"

INSTRUCCIONES CRÍTICAS - FORMATO DE RESPUESTA OBLIGATORIO:
1. **NO agregues encabezados decorativos** como "✅ DATOS OBTENIDOS", emojis, separadores
2. **RESPONDE DIRECTAMENTE** con los datos sin explicaciones previas
3. **Formato SIMPLE:**
   - Si es conteo: solo el número
   - Si es un valor específico: solo el valor
   - Si son múltiples registros: lista limpia sin decoración

EJEMPLOS:

Query: "¿cuántos usuarios hay en el curso con id=1?"
Respuesta: "5"

Query: "¿cuál es el nombre del creador del video con id=10?"
Respuesta: "jesus"

Query: "lista los títulos de las tareas del tema con id=3"
Respuesta: "Tarea de matemáticas
Tarea de física
Ejercicio de cálculo"

4. **AL FINAL**, agrega el script SQL:

**Script SQL:**
\`\`\`sql
${context.sqlScript}
\`\`\`

RESPONDE AHORA CON ESTE FORMATO EXACTO:
            """.trimIndent()
        }
        
        // 📊 Prompt para consultas con ordenamiento
        if (context.orderBy != null) {
            val (column, direction) = context.orderBy
            val sqlScript = if (context.sqlScript.isNotEmpty()) {
                """

📜 SCRIPT SQL EJECUTADO:
```sql
${context.sqlScript}
```
""".trimIndent()
            } else ""
            
            return """
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL.

DATOS RECUPERADOS Y ORDENADOS:
$relevantData$sqlScript

CONSULTA ORIGINAL: "$originalQuery"

INSTRUCCIONES CRÍTICAS - FORMATO DE RESPUESTA OBLIGATORIO:
1. **NO agregues encabezados decorativos**, emojis, separadores, ni explicaciones
2. **RESPONDE DIRECTAMENTE** con la lista de datos ordenados
3. **Formato SIMPLE:**
   - Lista numerada si son múltiples elementos
   - Solo los campos solicitados (ej: si pidió "títulos", solo títulos)
   - Mantén el orden de los datos

EJEMPLOS:

Query: "dame los títulos ordenados por id"
Respuesta: "1. lol
2. Mi video
3. sub
4. subj
5. load"

Query: "lista los usuarios ordenados alfabéticamente"
Respuesta: "1. jesus
2. jesus1
3. nuevo
4. prueba
5. pruebe"

4. **AL FINAL**, agrega el script:

**Script SQL:**
\`\`\`sql
${context.sqlScript}
\`\`\`

RESPONDE AHORA CON ESTE FORMATO EXACTO:
            """.trimIndent()
        }
        
        // Prompt normal para otras consultas
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
1. GENERA UNA CONSULTA SQL válida para PostgreSQL/Supabase que obtenga EXACTAMENTE lo que el usuario pidió
2. Encierra el SQL en un bloque de código: ```sql ... ```
3. El SQL será ejecutado automáticamente en Supabase para obtener los datos reales
4. DESPUÉS del bloque SQL, explica brevemente qué datos recuperaste
5. NO limites el número de elementos - el SQL debe obtener todos los datos solicitados
6. Usa JOINs si la consulta involucra múltiples tablas relacionadas
7. Consulta el esquema de arriba para identificar las relaciones correctas
            """.trimIndent()
        } else {
            """
INSTRUCCIONES:
1. GENERA UNA CONSULTA SQL válida para PostgreSQL/Supabase que responda EXACTAMENTE la pregunta del usuario
2. Encierra el SQL en un bloque de código: ```sql ... ```
3. El SQL será ejecutado automáticamente en Supabase y los datos reales serán devueltos al usuario
4. Si la consulta requiere datos de múltiples tablas, usa JOINs apropiados consultando el esquema de arriba
5. DESPUÉS del bloque SQL, explica brevemente qué datos se están consultando
6. Usa SOLO las tablas y columnas que existen en el esquema proporcionado
7. Si es un conteo, usa COUNT(*); si es búsqueda específica, usa WHERE con los filtros apropiados
8. Para consultas con relaciones (ej: "dueño del curso de la tarea"), consulta el esquema para identificar las FK correctas
            """.trimIndent()
        }
        
        val sqlScriptSection = if (context.sqlScript.isNotEmpty()) {
            """

📜 SCRIPT SQL EJECUTADO:
```sql
${context.sqlScript}
```
""".trimIndent()
        } else ""

        return """
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL.

ESQUEMA DE LA BASE DE DATOS (PostgreSQL/Supabase):
$dbSchema

DATOS RECUPERADOS DE SUPABASE:
$limitedData$sqlScriptSection

CONSULTA ORIGINAL: "$originalQuery"

INSTRUCCIONES CRÍTICAS - FORMATO DE RESPUESTA OBLIGATORIO:
1. **NO agregues encabezados decorativos** como "✅ DATOS OBTENIDOS", emojis, separadores
2. **RESPONDE DIRECTAMENTE** con los datos solicitados sin explicaciones previas
3. **Formato SIMPLE:**
   - Conteos: solo el número
   - Valores específicos: solo el valor
   - Listas: formato limpio sin decoración
   - Múltiples registros: lista numerada simple

EJEMPLOS DE RESPUESTAS CORRECTAS:

Query: "¿cuántos usuarios hay?"
Respuesta: "9"

Query: "¿cuál es el título del curso con id=1?"
Respuesta: "Introducción a Python"

Query: "dame el username del usuario con id=5"
Respuesta: "jesus"

Query: "lista los títulos de todos los videos"
Respuesta: "1. lol
2. Mi video
3. sub
4. subj
5. load"

Query: "¿cuál es el email del usuario 'jesus'?"
Respuesta: "jesus@example.com"

4. **AL FINAL**, agrega el script SQL usado:

**Script SQL:**
\`\`\`sql
${context.sqlScript ?: "SELECT * FROM ${context.targetTables.firstOrNull() ?: "tabla"}"}
\`\`\`

RESPONDE AHORA CON ESTE FORMATO EXACTO Y DIRECTO.
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
            "Suscriptor ID: ${sub.subscriberId}, Creador ID: ${sub.creatorId}"
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
    
    /**
     * Genera una respuesta para consultas de consejo/recomendación sin consultar la BD.
     * Usa el LLM con contexto del esquema de la base de datos para dar recomendaciones inteligentes.
     */
    private suspend fun generateAdviceResponse(userQuery: String): String = withContext(Dispatchers.IO) {
        try {
            // Obtener el esquema de la base de datos para contexto
            val dbSchema = schemaService.getDatabaseSchema()
            
            val prompt = """
Eres un asistente experto en sistemas de información educativa, bases de datos y buenas prácticas.

═══════════════════════════════════════════════════════════════════
ESQUEMA COMPLETO DE LA BASE DE DATOS DEL SISTEMA:
═══════════════════════════════════════════════════════════════════
$dbSchema

═══════════════════════════════════════════════════════════════════
CONSULTA DEL USUARIO:
═══════════════════════════════════════════════════════════════════
"$userQuery"

INSTRUCCIONES PARA TU RESPUESTA:
1. El usuario te está pidiendo recomendaciones, consejos o explicaciones conceptuales
2. ⚠️ CRÍTICO: NO generes consultas SQL inventadas - esta NO es una pregunta de datos
3. NO inventes JOINs ni estructuras SQL que no existen en el esquema
4. Si el usuario pregunta sobre capacidades o cambios posibles, explica:
   - Qué operaciones puede realizar el sistema (consultas, análisis, reportes)
   - Qué NO puedes hacer (modificar estructura, ejecutar INSERT/UPDATE/DELETE)
   - Qué tipo de información puede obtener de cada tabla
5. Usa el ESQUEMA DE LA BASE DE DATOS de arriba para entender la estructura del sistema
6. Proporciona recomendaciones específicas y prácticas basadas en:
   - Las tablas existentes (personas, usuarios, courses, tasks, etc.)
   - Las relaciones entre entidades (Foreign Keys)
   - Los campos disponibles (timestamps, grades, feedback, etc.)
7. **Si la pregunta es sobre NORMALIZACIÓN DE BASE DE DATOS:**
   - Analiza el esquema actual identificando violaciones a formas normales
   - Identifica dependencias funcionales problemáticas
   - Sugiere divisiones de tablas con ejemplos concretos
   - Explica beneficios y costos de normalizar a cada forma normal
   - Proporciona el diseño de las nuevas tablas que resolverían las violaciones
   - Menciona qué campos mover a qué tabla nueva
   - NO generes SQL de modificación, solo describe el diseño conceptual
8. Si la pregunta es sobre auditoría, sugiere:
   - Qué tablas revisar
   - Qué campos son críticos
   - Qué relaciones verificar
   - Consultas SQL de ejemplo para auditar (basadas en el esquema real)
9. Si la pregunta es sobre seguridad:
   - Identifica campos sensibles (contrasena, email, identificacion)
   - Sugiere validaciones en roles y permisos (tabla roles, usuarios.rol_id)
10. Si la pregunta es sobre optimización:
    - Identifica índices necesarios
    - Sugiere mejoras en relaciones
11. Si la pregunta es sobre capacidades del sistema:
    - Explica que puedes consultar y analizar datos (SELECT)
    - NO puedes modificar datos (INSERT/UPDATE/DELETE)
    - NO puedes modificar estructura (CREATE/ALTER/DROP)
    - Puedes generar reportes, estadísticas y análisis
12. Sé específico, práctico y cita las tablas/campos relevantes del esquema
13. Usa formato claro con viñetas o listas numeradas
14. NO digas "no tengo acceso a la base de datos" - SÍ TIENES el esquema completo arriba

FORMATO DE RESPUESTA:
[Breve introducción contextualizando la pregunta]

**Análisis del esquema actual:**
[Si es pregunta de normalización, analiza violaciones actuales]

**Recomendaciones principales:**
1. [Primera recomendación específica con tablas/campos citados]
2. [Segunda recomendación con diseño propuesto si aplica]
3. [Tercera recomendación]

**Ejemplos de diseño mejorado (si aplica):**
Tabla actual: [nombre_tabla]
- Problema: [descripción de violación]
- Solución: Dividir en [tabla1] y [tabla2]
  * [tabla1]: [campos que va aquí]
  * [tabla2]: [campos que va aquí]

**Conclusión:**
[Resumen y siguientes pasos sugeridos]

RESPONDE AHORA:
            """.trimIndent()
            
            // Llamar al LLM para generar la respuesta
            val mspClient = MSPClient(context)
            val llmResponse = mspClient.sendPrompt(prompt)
            
            return@withContext if (llmResponse.isNotEmpty()) {
                llmResponse
            } else {
                "No pude generar recomendaciones. Por favor, intenta reformular tu pregunta."
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Error generando respuesta de consejo", e)
            return@withContext "Error generando recomendaciones: ${e.message}"
        }
    }
}
