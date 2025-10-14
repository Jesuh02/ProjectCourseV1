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
            semanticTags = listOf("usuario", "login", "cuenta", "autenticacion", "acceso"),
            description = "Cuentas de usuario para autenticación"
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
                val topic = supabase.fetchTopicById(task.topicId.toInt())
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
                Log.d(tag, "  ✓ Found course: ${course.title} (creator=${course.creatorUsername})")
                
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
                    
                    ✅ Usuario dueño/creador del curso: ${course.creatorUsername}
                """.trimIndent()
                
                // Build SQL script showing all steps
                val sqlScript = """
-- Consulta SQL con JOINs para obtener el creador del curso de una tarea
SELECT c.creator_username AS username_dueno_curso,
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
                val topic = supabase.fetchTopicById(task.topicId.toInt())
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
                    🔹 Creador del curso: ${course.creatorUsername}
                    
                    👥 Estudiantes que enviaron esta tarea: $studentsList
                """.trimIndent()
                
                val sqlScript = """
-- Consulta SQL con JOINs para obtener curso y entregas de una tarea
SELECT c.creator_username AS username_dueno_curso,
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
            
            try {
                val (specificData, sqlScript) = getDataById(tableName, idFilter.toInt())
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
     * @return Pair<String, String> - (datos formateados, SQL script)
     */
    private suspend fun getDataById(tableName: String, id: Int): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "🔎 Fetching single record from $tableName with id=$id")
            
            // Generar el SQL script
            val sqlScript = "SELECT * FROM $tableName WHERE id = $id;"
            
            val result = when (tableName) {
                "videos" -> {
                    val video = supabase.fetchVideoById(id)
                    if (video != null) {
                        "Video encontrado:\nID: ${video.id}\nTítulo: ${video.title}\nDescripción: ${video.description}\nCreador: ${video.username}\nPrecio: $${video.price}\nPago requerido: ${if (video.isPaid) "Sí" else "No"}"
                    } else {
                        "No se encontró video con id=$id"
                    }
                }
                "courses" -> {
                    val course = supabase.fetchCourseById(id.toLong())
                    if (course != null) {
                        "Curso encontrado:\nID: ${course.id}\nTítulo: ${course.title}\nDescripción: ${course.description}\nCreador: ${course.creatorUsername}\nPrecio: $${course.price}"
                    } else {
                        "No se encontró curso con id=$id"
                    }
                }
                "topics" -> {
                    val topic = supabase.fetchTopicById(id)
                    if (topic != null) {
                        "Tema encontrado:\nID: ${topic.id}\nNombre: ${topic.name}\nDescripción: ${topic.description}\nID del curso: ${topic.courseId}"
                    } else {
                        "No se encontró tema con id=$id"
                    }
                }
                "tasks" -> {
                    val task = supabase.fetchTaskById(id.toLong())
                    if (task != null) {
                        "Tarea encontrada:\nID: ${task.id}\nNombre: ${task.name}\nDescripción: ${task.description}\nID del tema: ${task.topicId}"
                    } else {
                        "No se encontró tarea con id=$id"
                    }
                }
                "content_items" -> {
                    val item = supabase.fetchContentItemById(id)
                    if (item != null) {
                        "Item de contenido encontrado:\nID: ${item.id}\nTítulo: ${item.name ?: "Sin título"}\nContenido: ${item.uriString.take(200)}...\nTipo: ${item.contentType}\nID del tema: ${item.topicId}"
                    } else {
                        "No se encontró item de contenido con id=$id"
                    }
                }
                else -> {
                    // Para otras tablas, buscar en la lista completa
                    val allData = when (tableName) {
                        "personas" -> supabase.fetchPersonas().find { it.id == id.toLong() }?.let {
                            "Persona encontrada:\nID: ${it.id}\nNombres: ${it.nombres}\nApellidos: ${it.apellidos}\nEmail: ${it.email}"
                        }
                        "usuarios" -> supabase.fetchUsuarios().find { it.id == id.toLong() }?.let {
                            "Usuario encontrado:\nID: ${it.id}\nUsuario: ${it.usuario}\nID de persona: ${it.personaId}"
                        }
                        "chat_messages" -> supabase.fetchChatMessages().find { it.id == id.toLong() }?.let {
                            "Mensaje encontrado:\nID: ${it.id}\nMensaje: ${it.message}\nEs usuario: ${it.isFromUser}"
                        }
                        "file_contexts" -> supabase.fetchFileContexts().find { it.id == id.toLong() }?.let {
                            "Archivo encontrado:\nID: ${it.id}\nNombre: ${it.fileName}\nTipo: ${it.fileType}"
                        }
                        "roles" -> supabase.fetchRoles().find { it.id == id.toLong() }?.let {
                            "Rol encontrado:\nID: ${it.id}\nNombre: ${it.nombre}"
                        }
                        "recursos" -> supabase.fetchRecursos().find { it.id == id.toLong() }?.let {
                            "Recurso encontrado:\nID: ${it.id}\nNombre: ${it.nombre}\nIcono: ${it.icono}\nOrden: ${it.orden}"
                        }
                        "task_submissions" -> supabase.fetchTaskSubmissions().find { it.id == id.toLong() }?.let {
                            "Entrega encontrada:\nID: ${it.id}\nID de tarea: ${it.taskId}\nUsuario: ${it.studentUsername}\nFecha: ${it.submissionDate}"
                        }
                        else -> null
                    }
                    allData ?: "No se encontró registro con id=$id en $tableName"
                }
            }
            
            Log.d(tag, "  ✅ Result: ${result.take(100)}...")
            Log.d(tag, "  📜 SQL: $sqlScript")
            return@withContext Pair(result, sqlScript)
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Error fetching data by id from $tableName", e)
            return@withContext Pair("Error obteniendo datos: ${e.message}", "SELECT * FROM $tableName WHERE id = $id;")
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
                                "subscriber_username" -> if (direction == "desc") list.sortedByDescending { it.subscriberUsername } else list.sortedBy { it.subscriberUsername }
                                "creator_username" -> if (direction == "desc") list.sortedByDescending { it.creatorUsername } else list.sortedBy { it.creatorUsername }
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
                            val creatorUsername = jsonObj.optString("username_dueno_curso", "N/A")
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
                            result.appendLine("   - Dueño/Creador: $creatorUsername")
                            result.appendLine()
                            if (grade >= 0) {
                                result.appendLine("📊 CALIFICACIÓN: $grade")
                            }
                            if (feedback.isNotBlank() && feedback != "Sin retroalimentación") {
                                result.appendLine("💬 RETROALIMENTACIÓN: $feedback")
                            }
                            
                            Log.d(tag, "Successfully retrieved creator: $creatorUsername")
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

            // Otherwise return successful MSP result
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
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL. Nunca uses inglés.

Eres un experto en bases de datos relacionales PostgreSQL/Supabase. El usuario te ha pedido información COMPLETA sobre el esquema de la base de datos.

═══════════════════════════════════════════════════════════════════
ESQUEMA COMPLETO DE LA BASE DE DATOS (PostgreSQL/Supabase):
═══════════════════════════════════════════════════════════════════
$dbSchema

═══════════════════════════════════════════════════════════════════
DATOS ACTUALES DE LA BASE DE DATOS (JSON):
═══════════════════════════════════════════════════════════════════
$relevantData

CONSULTA DEL USUARIO: 
"$originalQuery"

INSTRUCCIONES CRÍTICAS - DEBES SEGUIR ESTRICTAMENTE:

**PASO 1: EXTRAE TODAS LAS FOREIGN KEYS DEL ESQUEMA**
Busca en el esquema la sección "🔗 RESUMEN DE RELACIONES (Grafo de dependencias)".
Esta sección lista TODAS las tablas que tienen Foreign Keys.

**PASO 2: CUENTA LAS FOREIGN KEYS**
Para cada tabla listada en la sección de resumen, cuenta cuántos "├─→" tiene.
Ejemplo:
```
chat_messages:
  ├─→ usuarios (via usuario_id)           <-- 1 FK
content_items:
  ├─→ usuarios (via creator_usuario_id)   <-- 1 FK
  ├─→ tasks (via task_id)                 <-- 1 FK (total: 2 FKs para content_items)
```

**PASO 3: LISTA TODAS LAS RELACIONES SIN OMITIR NINGUNA**
Formato OBLIGATORIO para cada relación:
[TABLA_ORIGEN].[columna_fk] → [TABLA_DESTINO].[columna] [REGLA_DELETE]

Ejemplo:
- chat_messages.usuario_id → usuarios.id [SET NULL]
- content_items.creator_usuario_id → usuarios.id [SET NULL]
- content_items.task_id → tasks.id [CASCADE]

**PASO 4: VERIFICA TU CONTEO**
Cuenta cuántas relaciones listaste. El número DEBE coincidir con el total de "├─→" en el esquema.

❌ **PROHIBIDO:**
- Decir "hay más relaciones pero no las listo"
- Omitir relaciones por brevedad
- Agrupar múltiples relaciones en una sola línea
- Resumir o simplificar la respuesta

✅ **OBLIGATORIO:**
- Listar TODAS las Foreign Keys encontradas en "🔗 RESUMEN DE RELACIONES"
- Usar el formato especificado
- Incluir la regla ON DELETE para cada FK
- Numerar cada relación (1., 2., 3., etc.)

**FORMATO DE RESPUESTA OBLIGATORIO:**
```
La base de datos TareaMov tiene X Foreign Keys (relaciones):

1. [tabla1].[col] → [tabla2].[col] [REGLA]
2. [tabla1].[col] → [tabla2].[col] [REGLA]
...
X. [tabla1].[col] → [tabla2].[col] [REGLA]

VERIFICACIÓN: He listado todas las X relaciones presentes en el esquema.
```

RESPONDE AHORA SIGUIENDO ESTE FORMATO EXACTO.
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
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL. Nunca uses inglés.

Eres un asistente de base de datos experto, preciso y comunicativo.

═══════════════════════════════════════════════════════════════════
ESQUEMA DE LA BASE DE DATOS (PostgreSQL/Supabase):
═══════════════════════════════════════════════════════════════════
$dbSchema

═══════════════════════════════════════════════════════════════════
CONTEXTO DE LA CONSULTA:
═══════════════════════════════════════════════════════════════════
El usuario preguntó específicamente por un registro con ID=$idValue en la tabla ${context.targetTables.firstOrNull() ?: "desconocida"}.

DATOS RECUPERADOS DE SUPABASE:
$relevantData$sqlScript

CONSULTA ORIGINAL DEL USUARIO: 
"$originalQuery"

ATRIBUTOS SOLICITADOS: 
${if (context.requestedAttributes.isEmpty()) "Todos los campos disponibles" else context.requestedAttributes.joinToString(", ")}

INSTRUCCIONES PARA TU RESPUESTA:
1. **USA EL ESQUEMA DE LA BASE DE DATOS** de arriba para entender las relaciones entre tablas
2. Si la consulta requiere datos de múltiples tablas relacionadas, **GENERA UNA CONSULTA SQL CON JOIN**
3. ARGUMENTA brevemente qué consulta se realizó (ej: "He consultado la base de datos para obtener...")
4. Presenta ÚNICAMENTE la información del registro encontrado
5. Si el usuario pidió campos específicos (título, username, etc.), muestra SOLO esos campos
6. Si pidió "todos los datos" o no especificó, muestra todos los campos relevantes
7. Usa un formato claro y legible:
   - Para un solo campo: "El [campo] del [entidad] con id=$idValue es: [valor]"
   - Para múltiples campos: Lista estructurada con viñetas o formato limpio
8. NO inventes información que no esté en los datos
9. NO menciones otros registros
10. Sé conciso pero informativo
11. **AL FINAL, MUESTRA EL SCRIPT SQL** usado para obtener estos datos

IMPORTANTE PARA CONSULTAS CON RELACIONES:
- Si la consulta menciona "dueño", "creador", "pertenece", necesitas hacer JOIN entre tablas
- **ANALIZA EL ESQUEMA DE LA BASE DE DATOS de arriba** para identificar las Foreign Keys correctas
- **NO inventes relaciones**, usa SOLO las que aparecen en el esquema
- Busca en la sección "🔗 RESUMEN DE RELACIONES" y "⚡ CADENA DE RELACIONES CRÍTICA"

FORMATO OBLIGATORIO DE RESPUESTA:
[Tu explicación argumentada]
[Datos encontrados]

**Script SQL usado:**
\`\`\`sql
${context.sqlScript}
\`\`\`

EJEMPLOS DE RESPUESTAS CORRECTAS:
- Query: "¿cuál es el título del curso con id=1?"
  Respuesta: "He consultado la tabla de cursos en Supabase. El título del curso con id=1 es: '[TÍTULO DEL CURSO]'
  
  **Script SQL usado:**
  \`\`\`sql
  SELECT * FROM courses WHERE id = 1;
  \`\`\`"
  
- Query: "dame todos los datos del video con id=5"
  Respuesta: "He recuperado la información completa del video con id=5:
  • ID: 5
  • Título: [TÍTULO]
  • Descripción: [DESCRIPCIÓN]
  • Creador: [USERNAME]
  • Precio: $[PRECIO]
  
  **Script SQL usado:**
  \`\`\`sql
  SELECT * FROM videos WHERE id = 5;
  \`\`\`"

RESPONDE AHORA:
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
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL. Nunca uses inglés.

Eres un asistente experto en bases de datos relacionales y SQL con JOINs.

═══════════════════════════════════════════════════════════════════
ESQUEMA DE LA BASE DE DATOS (PostgreSQL/Supabase):
═══════════════════════════════════════════════════════════════════
$dbSchema

═══════════════════════════════════════════════════════════════════
CONTEXTO DE LA CONSULTA:
═══════════════════════════════════════════════════════════════════
El usuario hizo una consulta que requiere relacionar múltiples tablas: ${context.targetTables.joinToString(", ")}

DATOS RELACIONADOS RECUPERADOS:
$relevantData$sqlScript

CONSULTA ORIGINAL: "$originalQuery"

INSTRUCCIONES IMPORTANTES:
1. **ANALIZA EL ESQUEMA DE LA BASE DE DATOS** de arriba para identificar las Foreign Keys correctas
2. **GENERA UNA CONSULTA SQL CON JOINs** basada en las relaciones del esquema
3. EXPLICA la consulta SQL realizada:
   - Menciona las tablas relacionadas mediante JOINs
   - Explica cómo se conectan las tablas usando las Foreign Keys del esquema
   - Indica qué columnas se recuperaron
4. Presenta el RESULTADO FINAL de forma clara y concisa
5. El SQL debe usar JOINs eficientes, NO múltiples consultas separadas
6. Usa alias de tabla para claridad (t, tp, c, etc.)
7. Sé preciso con los datos mostrados
8. **AL FINAL, MUESTRA EL SCRIPT SQL CON JOINS** usado para la consulta

FORMATO OBLIGATORIO:
[Explicación de la consulta SQL con JOINs basada en el esquema]
[Resultado final]

**Script SQL usado:**
\`\`\`sql
${context.sqlScript}
\`\`\`

IMPORTANTE: 
- **CONSULTA EL ESQUEMA DE LA BASE DE DATOS de arriba** antes de generar SQL
- Busca en las secciones "🔗 Relaciones (Foreign Keys)" para cada tabla
- Verifica la sección "🔗 RESUMEN DE RELACIONES" para el grafo completo
- **NO uses relaciones que no estén explícitamente en el esquema**
- Si hay ejemplo en "⚡ CADENA DE RELACIONES CRÍTICA", úsalo como guía

RESPONDE AHORA:
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
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL. Nunca uses inglés.

Eres un asistente de base de datos que presenta información ordenada de forma clara.

CONTEXTO:
El usuario solicitó datos ordenados por: $column ($direction)
Tablas consultadas: ${context.targetTables.joinToString(", ")}

DATOS RECUPERADOS Y ORDENADOS:
$relevantData$sqlScript

CONSULTA ORIGINAL: "$originalQuery"

ATRIBUTOS A MOSTRAR:
${if (context.requestedAttributes.isEmpty()) "Todos los campos disponibles" else context.requestedAttributes.joinToString(", ")}

INSTRUCCIONES:
1. MENCIONA que los datos están ordenados por $column en orden ${if (direction == "asc") "ascendente" else "descendente"}
2. Presenta los datos en una lista clara y numerada
3. Si pidió solo ciertos atributos (ej: "títulos"), muestra SOLO esos
4. Mantén el orden proporcionado
5. Usa formato limpio (viñetas, numeración, o tabla simple)
6. **AL FINAL, MUESTRA EL SCRIPT SQL** usado para la consulta

FORMATO OBLIGATORIO:
[Explicación del ordenamiento]
[Lista de datos ordenados]

**Script SQL usado:**
\`\`\`sql
${context.sqlScript}
\`\`\`

EJEMPLO:
Query: "dame todos los títulos de videos ordenados por id"
Respuesta: "He consultado todos los videos de Supabase, ordenados por ID ascendente. Aquí están los títulos:

1. (ID: 1) Introducción a Python
2. (ID: 2) Bases de datos relacionales
3. (ID: 3) Desarrollo web moderno
...

**Script SQL usado:**
\`\`\`sql
SELECT id, title, description, username, timestamp, is_paid, thumbnail_uri, price 
FROM videos 
ORDER BY id ASC;
\`\`\`"

RESPONDE AHORA:
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
1. ARGUMENTA brevemente qué datos recuperaste (ej: "He consultado la tabla de [X] en Supabase...")
2. MUESTRA TODOS LOS DATOS proporcionados sin restricciones de longitud
3. NO limites el número de elementos mostrados 
4. Presenta CADA REGISTRO completo con todos sus campos
5. Usa el formato estructurado proporcionado en los datos
6. NO resumas ni omitas información
7. **AL FINAL, MUESTRA EL SCRIPT SQL** usado para obtener estos datos
            """.trimIndent()
        } else {
            """
INSTRUCCIONES:
1. ARGUMENTA brevemente tu respuesta (ej: "He consultado [tabla/tablas] y encontré...")
2. Responde de manera concisa y directa (máximo ${RAGConfig.MAX_RESPONSE_LENGTH} caracteres)
3. Usa SOLO la información proporcionada de Supabase
4. Si pides una lista, presenta máximo ${RAGConfig.MAX_LIST_ITEMS} elementos
5. Si es un conteo, da el número específico y justifícalo
6. Si no hay datos suficientes, indícalo claramente
7. Menciona la fuente: "según los datos de Supabase..." o similar
8. **AL FINAL, MUESTRA EL SCRIPT SQL** usado para obtener estos datos
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
🌐 IDIOMA OBLIGATORIO: Responde SIEMPRE en ESPAÑOL. Nunca uses inglés.

$systemPrompt

═══════════════════════════════════════════════════════════════════
ESQUEMA COMPLETO DE LA BASE DE DATOS (PostgreSQL/Supabase):
═══════════════════════════════════════════════════════════════════
$dbSchema

═══════════════════════════════════════════════════════════════════
CONTEXTO DE LA CONSULTA:
═══════════════════════════════════════════════════════════════════
- Tablas consultadas: ${context.targetTables.joinToString(", ")}
- Tipo de consulta: ${context.intent}
- Filtros aplicados: ${if (context.filters.isEmpty()) "ninguno" else context.filters.entries.joinToString(", ") { "${it.key}=${it.value}" }}

ESQUEMA RELEVANTE (resumen):
${getRelevantSchemaInfo(context.targetTables)}

DATOS RECUPERADOS DE SUPABASE:
$limitedData$sqlScriptSection

CONSULTA ORIGINAL DEL USUARIO: 
"$originalQuery"

ATRIBUTOS SOLICITADOS:
${if (context.requestedAttributes.isEmpty()) "Todos los campos" else context.requestedAttributes.joinToString(", ")}

$responseTemplate

$instructions

IMPORTANTE:
- USA EL ESQUEMA DE LA BASE DE DATOS de arriba para entender la estructura y relaciones
- Si necesitas consultar múltiples tablas relacionadas, GENERA UNA CONSULTA SQL CON JOINs
- Identifica las Foreign Keys correctas del esquema para relacionar tablas
- Si la consulta actual no tiene el esquema SQL, GENERA UNO basado en el esquema de la BD

FORMATO DE RESPUESTA ESPERADO:
1. Breve argumentación: "He consultado [tablas] en Supabase y [resultado]..."
2. Datos específicos solicitados
3. Conclusión o resumen si aplica
4. **Script SQL usado:**
   \`\`\`sql
   ${context.sqlScript}
   \`\`\`

RESPONDE AHORA:
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
