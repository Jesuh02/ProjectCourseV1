package com.example.tareamov.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.first
import com.example.tareamov.data.AppDatabase
import org.json.JSONObject
import org.json.JSONArray
import com.google.gson.Gson // <-- Add this import

/**
 * Service that handles natural language queries to the database using RAG
 * NOW WITH MCP TOOLS INTEGRATION - Like VS Code MCP Extension
 */
class DatabaseQueryService(private val context: Context) {
    private val tag = "DatabaseQueryService"
    // Use dependency injection or a singleton for MSPClient and LocalLlamaService if appropriate
    private val mspClient by lazy { MSPClient(context) } // Lazy initialization
    private val localLlamaService by lazy { LocalLlamaService(context) } // Lazy initialization
    private val database = AppDatabase.getDatabase(context)
    
    // RAG service for efficient database querying
    private val ragService by lazy { RAGDatabaseService(context) }
    
    // MCP Tool Service for protocol-based tool execution
    private val mcpToolService by lazy { MCPToolService(context) }
    
    // MCP HTTP Client for remote Node.js MCP server (Supabase connection)
    private val mcpHttpClient by lazy { MCPHttpClient(context) }
    
    // Flag to track if remote MCP server is available
    private var remoteMcpAvailable: Boolean? = null

    // Add schema definitions for Task and Subscription
    private val taskSchema = """
        {
            "tableName": "tasks",
            "columns": {
                "id": "INTEGER PRIMARY KEY AUTOINCREMENT",
                "topicId": "INTEGER NOT NULL (Foreign Key to topics.id)",
                "name": "TEXT NOT NULL",
                "description": "TEXT",
                "orderIndex": "INTEGER NOT NULL",
                "completed": "INTEGER NOT NULL DEFAULT 0"
            },
            "relationships": "Each Task belongs to a Topic (tasks.topicId -> topics.id)"
        }
    """.trimIndent()

    private val subscriptionSchema = """
        {
            "tableName": "subscriptions",
            "columns": {
                "subscriberUsername": "TEXT NOT NULL (Part of Primary Key)",
                "creatorUsername": "TEXT NOT NULL (Part of Primary Key)",
                "subscriptionDate": "LONG NOT NULL (timestamp)"
            },
            "relationships": "Connects a subscriber user to a creator user"
        }
    """.trimIndent()

    private val databaseJson = Mutex()
    @Volatile
    private var currentDatabaseJson: String = "{}"

    init {
        // Inicializa el JSON al arrancar
        updateDatabaseJson()
    }

    public fun updateDatabaseJson() {
        // Llama a la función de actualización en un hilo de fondo
        GlobalScope.launch(Dispatchers.IO) {
            val json = generateDatabaseJson()
            databaseJson.lock()
            try {
                currentDatabaseJson = json
            } finally {
                databaseJson.unlock()
            }
        }
    }

    suspend fun getDatabaseJson(): String {
        databaseJson.lock()
        try {
            return currentDatabaseJson
        } finally {
            databaseJson.unlock()
        }
    }

    /**
     * Get available MCP tools for display in UI
     */
    fun getAvailableMCPTools(): List<MCPToolService.MCPTool> {
        return mcpToolService.getAvailableTools()
    }
    
    /**
     * Execute MCP tool directly (bypassing LLM)
     */
    suspend fun executeMCPTool(toolName: String, arguments: Map<String, Any?>): MCPToolService.MCPToolResult {
        return mcpToolService.executeTool(toolName, arguments)
    }
    
    /**
     * Check if remote MCP server (Node.js) is available
     */
    private suspend fun isRemoteMcpAvailable(): Boolean {
        if (remoteMcpAvailable != null) {
            return remoteMcpAvailable!!
        }
        
        return try {
            Log.d(tag, "🔍 Checking remote MCP server availability...")
            val available = mcpHttpClient.initialize()
            remoteMcpAvailable = available
            if (available) {
                Log.d(tag, "✅ Remote MCP server (Node.js) is available and connected to Supabase")
            } else {
                Log.d(tag, "⚠️ Remote MCP server not available, will use local database")
            }
            available
        } catch (e: Exception) {
            Log.e(tag, "❌ Error checking remote MCP availability: ${e.message}")
            remoteMcpAvailable = false
            false
        }
    }
    
    /**
     * Process query with MCP tools - Uses remote Node.js MCP server when available
     * Falls back to local RAG processing if remote server is not available
     * 
     * Priority:
     * 1. Remote MCP Server (Node.js) -> Supabase (real-time, centralized)
     * 2. Local RAG Service -> Local SQLite (fallback)
     * 
     * NEW: Multi-step reasoning approach like GitHub Copilot
     */
    suspend fun processQueryWithMCP(query: String): String = withContext(Dispatchers.IO) {
        Log.i(tag, "🔧 Processing MCP-enabled query: '$query'")
        
        try {
            val normalizedQuery = query.lowercase().trim()
            
            // Try remote MCP server first (connects to Supabase via Node.js)
            if (isRemoteMcpAvailable()) {
                Log.d(tag, "🌐 Using remote MCP server (Node.js + Supabase)")
                
                return@withContext try {
                    // Check if asking for schema
                    if (normalizedQuery.contains("esquema") || normalizedQuery.contains("schema") || 
                        normalizedQuery.contains("estructura") || normalizedQuery.contains("tablas")) {
                        Log.d(tag, "📋 Fetching database schema from remote MCP server")
                        val schemaResult = mcpHttpClient.getDatabaseSchema()
                        
                        if (schemaResult.success && schemaResult.schema != null) {
                            "**Esquema de la base de datos (Supabase):**\n\n${schemaResult.schema}"
                        } else {
                            "❌ No se pudo obtener el esquema de la base de datos: ${schemaResult.error}"
                        }
                    } else {
                        // NEW: Multi-step reasoning for complex queries
                        Log.d(tag, "🔍 Executing multi-step query analysis on remote MCP server")
                        processQueryWithMultiStepReasoning(query, normalizedQuery)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "❌ Remote MCP error, falling back to local: ${e.message}")
                    // Mark as unavailable and fall through to local processing
                    remoteMcpAvailable = false
                    processWithLocalMcp(normalizedQuery, query)
                }
            }
            
            // Fallback to local processing
            Log.d(tag, "💾 Using local RAG service")
            return@withContext processWithLocalMcp(normalizedQuery, query)
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Error in MCP query processing", e)
            return@withContext "❌ Error procesando consulta: ${e.message}"
        }
    }
    
    /**
     * Multi-step reasoning approach for complex queries
     * Sends query to MCP server and formats the response
     */
    private suspend fun processQueryWithMultiStepReasoning(query: String, normalizedQuery: String): String {
        Log.d(tag, "🔍 Sending query to MCP server: $query")
        
        // Send the natural language query directly to MCP server
        // The server will parse it and execute the appropriate SQL
        val result = mcpHttpClient.queryDatabase(query)
        
        if (!result.success) {
            return "❌ Error ejecutando consulta: ${result.error ?: "Error desconocido"}"
        }

        // REMOVED: Direct formatted_summary passthrough - LLM will analyze raw data instead
        // Old behavior: if (formattedSummary) return formattedSummary + metrics + SQL
        // New behavior: Return raw data for LocalLlamaService to analyze
        
        // Build formatted response from RAW DATA
        val response = StringBuilder()
        
        // Format the data based on type
        when (val data = result.data) {
            is JSONArray -> {
                if (data.length() == 0) {
                    response.appendLine("**Resultado:** No se encontraron registros")
                } else {
                    response.appendLine("**Resultado:** ${data.length()} registro(s) encontrado(s)")
                    response.appendLine()
                    
                    // Display data in a readable format
                    for (i in 0 until minOf(data.length(), 50)) { // Limit to 50 for readability
                        val item = data.optJSONObject(i)
                        if (item != null) {
                            response.appendLine("**${i + 1}.** ${formatJsonObject(item)}")
                        }
                    }
                    
                    if (data.length() > 50) {
                        response.appendLine()
                        response.appendLine("... y ${data.length() - 50} registro(s) más")
                    }
                }
            }
            is JSONObject -> {
                // Special handling for BI-style responses from remote MCP server
                if (data.has("bi_suggestions") || data.has("schema")) {
                    response.appendLine("**Análisis BI sugerido:**")
                    // Suggestions
                    if (data.has("bi_suggestions")) {
                        val suggestions = data.optJSONArray("bi_suggestions")
                        if (suggestions != null) {
                            for (i in 0 until suggestions.length()) {
                                response.appendLine("- ${suggestions.optString(i)}")
                            }
                        }
                    }

                    // Schema summary (table counts)
                    if (data.has("schema")) {
                        response.appendLine()
                        response.appendLine("**Esquema (resumen):**")
                        val schemaObj = data.optJSONObject("schema")
                        if (schemaObj != null) {
                            val keys = schemaObj.keys()
                            while (keys.hasNext()) {
                                val table = keys.next()
                                val info = schemaObj.optJSONObject(table)
                                if (info != null) {
                                    val cnt = info.optInt("count", 0)
                                    response.appendLine("• $table: $cnt registros")
                                }
                            }
                        }
                    }
                } else {
                    response.appendLine("**Resultado:**")
                    response.appendLine(formatJsonObject(data))
                }
            }
            else -> {
                response.appendLine("**Resultado:** ${data?.toString() ?: "Sin datos"}")
            }
        }
        
        // Add SQL script at the end
        if (!result.sqlScript.isNullOrEmpty()) {
            response.appendLine()
            response.appendLine("**Consulta SQL ejecutada:**")
            response.appendLine("```sql")
            response.appendLine(result.sqlScript)
            response.appendLine("```")
        }
        
        return response.toString()
    }
    
    /**
     * Format JSONObject for display
     */
    private fun formatJsonObject(obj: JSONObject): String {
        val parts = mutableListOf<String>()
        val keys = obj.keys()
        
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            
            // Skip null values and complex nested objects
            if (value != null && value !is JSONObject && value !is JSONArray) {
                parts.add("$key: $value")
            }
        }
        
        return parts.joinToString(", ")
    }
    
    /**
     * Process query with local MCP tools (SQLite via RAG)
     */
    private suspend fun processWithLocalMcp(normalizedQuery: String, originalQuery: String): String {
        // Check if asking for schema - handle directly
        if (normalizedQuery.contains("esquema") || normalizedQuery.contains("schema") || 
            normalizedQuery.contains("estructura") || normalizedQuery.contains("tablas")) {
            Log.d(tag, "📋 Using local get_database_schema tool")
            val result = mcpToolService.executeTool("get_database_schema", emptyMap())
            return formatMCPResult(result, "get_database_schema")
        }
        
        // For ALL other queries, use RAGDatabaseService to ensure proper filtering
        Log.d(tag, "🎯 Using RAGDatabaseService for intelligent query processing")
        
        // Process query through RAG to get filtered results
        val ragResult = ragService.processQueryWithMetadata(originalQuery)
        
        // Build response with results and SQL
        val response = StringBuilder()
        
        // Add the data
        response.appendLine(ragResult.data)
        
        // Add SQL script at the end if available
        val sqlScript = ragResult.sqlScript ?: ""
        if (sqlScript.isNotEmpty()) {
            response.appendLine()
            response.appendLine("**Consulta SQL ejecutada:**")
            response.appendLine("```sql")
            response.appendLine(sqlScript)
            response.appendLine("```")
        }
        
        return response.toString()
    }
    
    /**
     * Format remote MCP data for display
     */
    private fun formatRemoteMcpData(data: Any?): String {
        return when (data) {
            is JSONArray -> {
                if (data.length() == 0) {
                    "Sin resultados"
                } else {
                    val sb = StringBuilder()
                    for (i in 0 until data.length()) {
                        val item = data.optJSONObject(i)
                        if (item != null) {
                            sb.appendLine("${i + 1}. ${formatJsonObject(item)}")
                        }
                    }
                    sb.toString()
                }
            }
            is org.json.JSONObject -> {
                formatJsonObject(data)
            }
            is String -> {
                data
            }
            else -> {
                data?.toString() ?: "Sin datos"
            }
        }
    }
    
    /**
     * Format MCP tool result for display
     */
    private fun formatMCPResult(result: MCPToolService.MCPToolResult, toolName: String): String {
        val sb = StringBuilder()
        
        if (result.success) {
            sb.appendLine("✅ **Consulta ejecutada exitosamente**")
            sb.appendLine()
            
            // Show SQL script if available
            if (!result.sqlScript.isNullOrBlank()) {
                sb.appendLine("### 📜 Script SQL Ejecutado:")
                sb.appendLine("```sql")
                sb.appendLine(result.sqlScript)
                sb.appendLine("```")
                sb.appendLine()
            }
            
            // Show metadata
            if (result.metadata != null) {
                val rowCount = result.metadata["rowCount"] as? Int ?: 0
                sb.appendLine("ℹ️ **Resultados:** $rowCount registros encontrados")
                sb.appendLine()
            }
            
            // Show data in a readable format
            when (val data = result.data) {
                is List<*> -> {
                    if (data.isEmpty()) {
                        sb.appendLine("⚠️ No se encontraron resultados.")
                    } else {
                        sb.appendLine("### 📊 Datos obtenidos:")
                        sb.appendLine()
                        data.forEachIndexed { index, item ->
                            sb.appendLine("**Registro ${index + 1}:**")
                            if (item is Map<*, *>) {
                                item.forEach { (key, value) ->
                                    sb.appendLine("  • **$key**: $value")
                                }
                            } else {
                                sb.appendLine("  $item")
                            }
                            sb.appendLine()
                        }
                    }
                }
                is Map<*, *> -> {
                    sb.appendLine("### 📊 Datos obtenidos:")
                    sb.appendLine()
                    data.forEach { (key, value) ->
                        sb.appendLine("• **$key**: $value")
                    }
                }
                else -> {
                    sb.appendLine("### 📊 Resultado:")
                    sb.appendLine(data.toString())
                }
            }
        } else {
            sb.appendLine("❌ **Error en la ejecución**")
            sb.appendLine()
            sb.appendLine("**Error**: ${result.error}")
            
            if (!result.sqlScript.isNullOrBlank()) {
                sb.appendLine()
                sb.appendLine("### 📜 Script SQL intentado:")
                sb.appendLine("```sql")
                sb.appendLine(result.sqlScript)
                sb.appendLine("```")
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Build tools context description for LLM
     */
    private fun buildToolsContext(tools: List<MCPToolService.MCPTool>): String {
        return tools.joinToString("\n\n") { tool ->
            """
            🔧 **${tool.name}**
            Descripción: ${tool.description}
            Parámetros: ${tool.inputSchema["properties"]}
            """.trimIndent()
        }
    }
    
    /**
     * Process a natural language query and return a response using RAG
     */
    suspend fun processQuery(query: String): String = withContext(Dispatchers.IO) {
        val normalizedQuery = query.lowercase().trim()
        Log.i(tag, "Processing RAG-enhanced query: '$normalizedQuery'")

        try {
            // QUICK SHORTCUT: If this is a casual greeting or very short chat, respond directly
            // without invoking the RAG engine which can return noisy DB listings.
            val greetingPattern = """^(hola+|holaa+|buenas|buenos\s+d[ií]as|buenas\s+tardes|buenas\s+noches|qué\s+tal|q\.?t\.?|hey|hi|hello)\b""".toRegex(RegexOption.IGNORE_CASE)
            if (normalizedQuery.length <= 12 && greetingPattern.containsMatchIn(normalizedQuery)) {
                Log.d(tag, "Detected greeting input - using lightweight response path")
                try {
                    val local = localLlamaService.generateResponse(normalizedQuery)
                    if (local.isNotBlank()) return@withContext local
                } catch (e: Exception) {
                    Log.w(tag, "LocalLlama greeting response failed: ${e.message}")
                }

                // Fallback canned reply
                return@withContext "¡Hola! ¿En qué puedo ayudarte hoy? Puedes pedirme datos de la base como 'dame todos los videos' o consultas específicas como 'video con id=1'"
            }

            // Check for graph requests first
            val graphRequest = checkForGraphRequest(normalizedQuery)
            if (graphRequest != null) {
                return@withContext "GRAPH_REQUEST:$graphRequest"
            }

            // Use RAG service for intelligent query processing
            val ragResponse = ragService.processRAGQuery(normalizedQuery)
            
            // If RAG service returns a meaningful response, use it
            if (ragResponse.isNotBlank() && !ragResponse.startsWith("Error")) {
                Log.d(tag, "RAG service provided response: ${ragResponse.take(100)}...")
                return@withContext ragResponse
            }
            
            // Fallback to traditional database context approach
            Log.w(tag, "RAG service failed or empty response, falling back to traditional approach")
            val dbJson = generateDatabaseJson() // Fresh data
            return@withContext processNaturalLanguageQueryWithJson(normalizedQuery, dbJson)
            
        } catch (e: Exception) {
            Log.e(tag, "Error processing query with RAG", e)
            // Ultimate fallback to basic query handling
            val fallbackResult = handleBasicDatabaseQuery(normalizedQuery)
            return@withContext "Error al procesar la consulta: ${e.message}. Intentando fallback básico...\n$fallbackResult"
        }
    }

    // New function to always include JSON context - optimized for payload size
    private suspend fun processNaturalLanguageQueryWithJson(query: String, dbJson: String): String {
        // Check and limit the size of the database JSON to avoid payload issues
        // Significantly reduced maximum size to avoid "Payload size exceeded" errors
        val maxJsonSize = 100 * 1024 // Limit to 100KB
        
        // Log the original size for debugging
        Log.d(tag, "Original database JSON size: ${dbJson.length} bytes")
        
        // Always summarize the database JSON to avoid payload issues
        val limitedDbJson = summarizeDatabaseJson(dbJson)
        Log.d(tag, "Summarized database JSON size: ${limitedDbJson.length} bytes")
        
        // Build a more focused schema-only description rather than including the full JSON
        val tablesList = """
            - usuarios: Credenciales y roles de usuarios
            - personas: Datos personales (nombre, email, etc.)
            - videos: Cursos multimedia disponibles
            - topics: Temas asociados a cursos
            - contentItems: Elementos de contenido educativo
            - tasks: Tareas asignadas por tema
            - subscriptions: Suscripciones entre usuarios
            - taskSubmissions: Entregas de tareas
            - purchases: Compras de cursos
        """.trimIndent()
        
        // Create a much more compact prompt
        val prompt = """
            Eres un asistente experto en bases de datos educativas.
            
            ESQUEMA DE BASE DE DATOS:
            $tablesList
            
            DATOS RESUMIDOS:
            $limitedDbJson
            
            RELACIONES CLAVE:
            1. Usuario → Persona (1:1)
            2. Video (Curso) → Topics (1:N)
            3. Topic → ContentItems, Tasks (1:N)
            4. Subscription: subscriberUsername → creatorUsername
            
            CONSULTA: $query
            
            Responde de manera concisa usando solo la información disponible.
        """.trimIndent()

        // Log the prompt size for debugging
        val promptSize = prompt.length
        Log.d(tag, "Prompt size: $promptSize bytes")
        
        // Safety check - if somehow the prompt is still too large, truncate it further
        val finalPrompt = if (promptSize > 500000) {
            Log.w(tag, "Prompt still too large at $promptSize bytes. Emergency truncation applied.")
            """
            Consulta la base de datos de la plataforma educativa.
            Las tablas son: usuarios, personas, videos, topics, tasks, subscriptions.
            
            Consulta: ${query.take(100)}
            """.trimIndent()
        } else {
            prompt
        }

        // Try MSPClient first with retry mechanism for better reliability
        for (attempt in 1..3) {
            try {
                Log.d(tag, "MSPClient attempt $attempt")
                // Include database context where possible so the LLM can use retrieved RAG data
                val response = mspClient.sendPrompt(finalPrompt, includeHistory = false, includeDatabaseContext = true)
                if (response.isNotBlank() && !response.contains("Error:", ignoreCase = true)) {
                    return response
                }
                Log.w(tag, "MSPClient attempt $attempt failed with response: $response")
            } catch (e: Exception) {
                Log.e(tag, "MSPClient attempt $attempt failed with exception", e)
            }
        }
        
        // Try LocalLlamaService as fallback
        try {
            Log.d(tag, "Falling back to LocalLlamaService")
            val response = localLlamaService.generateResponse(finalPrompt)
            if (response.isNotBlank() && !response.contains("Error:", ignoreCase = true)) {
                return response
            }
            Log.w(tag, "LocalLlamaService failed: $response")
        } catch (e: Exception) {
            Log.e(tag, "LocalLlamaService failed with exception", e)
        }
        
        // If all LLM attempts fail, fall back to basic database query
        Log.w(tag, "All LLM attempts failed. Falling back to basic database query.")
        return handleBasicDatabaseQuery(query)
    }

    /**
     * Summarizes the database JSON to reduce payload size
     */
    private fun summarizeDatabaseJson(fullJson: String): String {
        try {
            val jsonObj = JSONObject(fullJson)
            val summary = StringBuilder()
            
            // Create an ultra-compact summary with just record counts and minimal sample data
            summary.appendLine("RESUMEN DE TABLAS:")
            
            jsonObj.keys().forEach { tableName ->
                val tableData = jsonObj.optJSONArray(tableName)
                val recordCount = tableData?.length() ?: 0
                
                summary.appendLine("- $tableName: $recordCount registros")
                
                // For each table with data, show at most 1 record as example
                if (recordCount > 0) {
                    val sampleRecord = tableData!!.getJSONObject(0)
                    
                    // Extract just key fields (limit to 5 fields maximum)
                    val keyFields = sampleRecord.keys().asSequence().take(5).toList()
                    val sampleData = keyFields.joinToString(", ") { field -> 
                        "$field: ${sampleRecord.optString(field, "").take(15)}"
                    }
                    
                    summary.appendLine("  Ejemplo: { $sampleData }")
                }
            }
            
            return summary.toString()
        } catch (e: Exception) {
            Log.e(tag, "Error summarizing database JSON", e)
            // Return an extremely simplified version as fallback
            return "Error al resumir la base de datos. Consulta más específica."
        }
    }

    /**
     * Checks if the query is a request for a graph and returns the request type or null.
     */
    private fun checkForGraphRequest(query: String): String? {
        // More robust graph detection
        val isGraphQuery = query.contains("gráfico") || query.contains("grafico") ||
                query.contains("visualiza") || query.contains("muestra") && (query.contains("gráficamente") || query.contains("visual"))

        if (!isGraphQuery) return null

        Log.d(tag, "Potential graph query detected: $query")

        // Specific graph types
        return when {
            query.contains("usuario") && query.contains("video") ||
                    query.contains("usuarios") && query.contains("videos") -> "GRAPH_REQUEST:USER_VIDEOS"

            query.contains("tema") && query.contains("contenido") ||
                    query.contains("temas") && query.contains("contenidos") -> "GRAPH_REQUEST:TOPIC_CONTENT"

            query.contains("curso") && query.contains("tema") ||
                    query.contains("video") && query.contains("tema") ||
                    query.contains("videos") && query.contains("temas") -> "GRAPH_REQUEST:COURSE_TOPICS"

            query.contains("persona") && query.contains("usuario") ||
                    query.contains("personas") && query.contains("usuarios") -> "GRAPH_REQUEST:PERSONAS_USERS"

            // Add more specific graph types here if needed

            // Generic interactive graph request
            query.contains("interactivo") || query.contains("dinámico") -> "GRAPH_REQUEST:INTERACTIVE"

            // Fallback for unrecognized graph queries
            else -> {
                Log.d(tag, "Unrecognized graph type for query: $query")
                // Return null to let it be processed by LLM or basic fallback
                null
                // Or return a specific message:
                // "No se pudo identificar qué tipo de gráfico deseas. Prueba con:\n" +
                // "- Gráfico de usuarios por videos\n" +
                // "- Gráfico de temas por contenido\n" +
                // "- Gráfico de cursos por temas\n" +
                // "- Gráfico de personas y usuarios"
            }
        }
    }

    /**
     * Process a natural language query using the LLM, with fallback to local LLM and basic queries.
     */
    private suspend fun processNaturalLanguageQuery(query: String): String {
        val dbJson = getDatabaseJson()
        val jsonObj = JSONObject(dbJson)
        val tableNames = jsonObj.keys().asSequence()
            .filter { it != "schema" }
            .toList()
        val tableList = tableNames.mapIndexed { idx, name -> "${idx + 1}. `${name}`" }.joinToString("\n")
        val contextMessage = """
            Tengo contexto completo sobre las siguientes tablas de la base de datos:
            $tableList

            Puedes preguntarme cualquier cosa sobre estas tablas.
        """.trimIndent()
        Log.d(tag, "Attempting natural language query processing for: $query")

        return try {
            // Prepare database context for better LLM responses
            // Check if the database context is too large
            val maxContextSize = 5 * 1024  // 5KB limit for context
            val databaseContext = getDatabaseContext()
            
            // If context is too large, use a summarized version
            val optimizedContext = if (databaseContext.length > maxContextSize) {
                Log.w(tag, "Database context too large (${databaseContext.length} chars). Using summarized version.")
                summarizeDatabaseContext(databaseContext)
            } else {
                databaseContext
            }
            
            Log.v(tag, "Optimized Database Context size: ${optimizedContext.length} chars")

            // Construct a more robust prompt
            val systemPrompt = "Eres un asistente experto en bases de datos SQL. Tu tarea es responder preguntas sobre una base de datos específica basándote únicamente en el esquema y los datos proporcionados. Sé conciso y preciso. Si la pregunta es ambigua, pide aclaraciones. Si la información no está disponible, indícalo claramente. No inventes información."
            val fullPrompt = """
            $systemPrompt

            $contextMessage

            Contexto de la Base de Datos:
            $optimizedContext

            La tabla 'usuarios' contiene la lista completa de todos los usuarios registrados en el sistema.
            La tabla 'subscriptions' contiene información sobre las relaciones de suscripción entre usuarios, no el total de usuarios.

            Consulta del Usuario: $query
            Responde usando únicamente la información del JSON. Si la información no está disponible, indícalo claramente.
            """.trimIndent()

            // 1. Try MSPClient (Ollama)
            try {
                Log.d(tag, "Attempting query with MSPClient (Ollama)")
                val response = mspClient.sendPrompt(fullPrompt, includeHistory = true, includeDatabaseContext = false) // Context is already in the prompt
                Log.i(tag, "Response from MSPClient: $response")
                // Basic validation of response
                if (response.isNotBlank() && !response.contains("Error:", ignoreCase = true) && !response.contains("no se pudo conectar", ignoreCase = true)) {
                    return response
                }
                Log.w(tag, "MSPClient response was empty or indicated an error.")
                // Fall through to the next attempt if response is invalid
            } catch (e: Exception) {
                Log.e(tag, "Error querying MSPClient (Ollama). Trying LocalLlamaService.", e)
                // Fall through to the next attempt
            }

            // 2. Try LocalLlamaService
            try {
                Log.d(tag, "Attempting query with LocalLlamaService")
                val localResponse = localLlamaService.generateResponse(fullPrompt)
                Log.i(tag, "Response from LocalLlamaService: $localResponse")
                // Basic validation of response
                if (localResponse.isNotBlank() && !localResponse.contains("Error:", ignoreCase = true)) {
                    return localResponse
                }
                Log.w(tag, "LocalLlamaService response was empty or indicated an error.")
                // Fall through to basic fallback
            } catch (e: Exception) {
                Log.e(tag, "Error querying LocalLlamaService. Falling back to basic handler.", e)
                // Fall through to basic fallback
            }

            // 3. Fallback to basic database queries
            Log.w(tag, "LLM attempts failed. Falling back to basic database query handler.")
            handleBasicDatabaseQuery(query)

        } catch (e: Exception) {
            Log.e(tag, "Critical error during natural language query processing for '$query'", e)
            // Final fallback if even basic handler fails
            "Ocurrió un error inesperado al procesar tu consulta. Por favor, intenta de nuevo más tarde. Detalles: ${e.message}"
        }
    }
    
    /**
     * Creates a summarized version of the database context
     */
    private fun summarizeDatabaseContext(fullContext: String): String {
        // Extract the most important parts of the context
        val schemaSection = extractSection(fullContext, "ESQUEMA DE LA BASE DE DATOS", "RESUMEN DE DATOS")
        val summarySection = extractSection(fullContext, "RESUMEN DE DATOS", "DATOS RECIENTES/IMPORTANTES")
        
        // Create a shorter version
        return """
            # ESQUEMA DE LA BASE DE DATOS (Resumido)
            ${schemaSection?.take(1000) ?: "Información de esquema no disponible"}
            
            # RESUMEN DE DATOS
            ${summarySection?.take(500) ?: "Información de resumen no disponible"}
            
            [Nota: Contexto resumido por limitaciones de tamaño]
        """.trimIndent()
    }
    
    /**
     * Helper function to extract a section from text
     */
    private fun extractSection(text: String, startMarker: String, endMarker: String): String? {
        val startIndex = text.indexOf(startMarker)
        if (startIndex < 0) return null
        
        val endIndex = text.indexOf(endMarker, startIndex)
        return if (endIndex > startIndex) {
            text.substring(startIndex, endIndex).trim()
        } else {
            text.substring(startIndex).trim()
        }
    }

    /**
     * Handle basic database queries when LLM is not available or fails.
     * Tries to interpret the query and provide a direct answer from the database.
     * This acts as a robust fallback mechanism.
     */
    /**
     * Fallback handler for basic, direct database queries based on keywords.
     * This is used when LLM attempts fail.
     */
    private suspend fun handleBasicDatabaseQuery(query: String): String = withContext(Dispatchers.IO) {
        Log.d(tag, "Executing basic database query handler for: '$query'")
        val normalizedQuery = query.lowercase().trim()

        return@withContext try {
            // --- List Queries ---
            if (normalizedQuery.startsWith("lista") || normalizedQuery.startsWith("muestra") || normalizedQuery.startsWith("dame")) {
                when {
                    normalizedQuery.contains("usuario") || normalizedQuery.contains("usuarios") -> {
                        val users = database.usuarioDao().getAllUsuarios()
                        if (users.isEmpty()) {
                            "No hay usuarios registrados."
                        } else {
                            "Usuarios:\n" + users.joinToString("\n") { it.toString() }
                        }
                    }
                    normalizedQuery.contains("video") || normalizedQuery.contains("videos") || normalizedQuery.contains("curso") || normalizedQuery.contains("cursos") -> {
                        val videos = database.videoDao().getAllVideos()
                        if (videos.isEmpty()) {
                            "No hay videos (cursos) registrados."
                        } else {
                            "Lista de Videos (Cursos):\n" + videos.joinToString("\n") { it.toString() }
                        }
                    }
                    normalizedQuery.contains("tema") || normalizedQuery.contains("temas") -> {
                        val topics = database.topicDao().getAllTopics()
                        if (topics.isEmpty()) {
                            "No hay temas registrados."
                        } else {
                            "Lista de Temas:\n" + topics.joinToString("\n") { it.toString() }
                        }
                    }
                    normalizedQuery.contains("contenido") || normalizedQuery.contains("contenidos") -> {
                        val items = database.contentItemDao().getAllContentItems()
                        if (items.isEmpty()) {
                            "No hay elementos de contenido."
                        } else {
                            "Lista de Contenido:\n" + items.joinToString("\n") { it.toString() }
                        }
                    }
                    normalizedQuery.contains("persona") || normalizedQuery.contains("personas") -> {
                        val personas = database.personaDao().getAllPersonasList()
                        if (personas.isEmpty()) {
                            "No hay personas registradas."
                        } else {
                            "Lista de Personas:\n" + personas.joinToString("\n") { it.toString() }
                        }
                    }
                    // Agrega aquí más tablas si lo necesitas
                    else -> "No puedo listar eso específicamente. Por favor, sé más claro (ej: 'lista de usuarios')."
                }
            }
            // Si no es una consulta de lista, puedes manejar otros casos aquí o devolver un mensaje por defecto
            "No se reconoció la consulta como una petición de lista."
        } catch (e: Exception) {
            Log.e(tag, "Error executing basic database query for '$query'", e)
            "Ocurrió un error al intentar consultar la base de datos directamente: ${e.message}"
        }
    }


    /**
     * Generates a fresh JSON snapshot of the database (always up-to-date)
     */
    // Change this:
    // private suspend fun generateDatabaseJson(): String = withContext(Dispatchers.IO) {
    // To this:
    suspend fun generateDatabaseJson(): String = withContext(Dispatchers.IO) {
        try {
            val gson = Gson()
            val json = JSONObject()
            
            // OPTIMIZATION: Do NOT load all data into memory. 
            // Just provide schema and counts. The LLM should use tools to query data.
            
            // Statistics (Counts) - This is much lighter if DAOs support count, 
            // but even getAll().size is better than serializing everything if we discard the list immediately.
            // Ideally we should add count() methods to DAOs.
            // For now, we assume we have to load lists to count, but we won't put them in JSON.
            
            val stats = JSONObject()
            
            // Helper to get count safely - suspend version
            suspend fun getCount(loader: suspend () -> List<Any>): Int {
                return try { loader().size } catch (e: Exception) { 0 }
            }

            stats.put("total_usuarios", getCount { database.usuarioDao().getAllUsuarios() })
            stats.put("total_videos", getCount { database.videoDao().getAllVideos() })
            stats.put("total_topics", getCount { database.topicDao().getAllTopics() })
            stats.put("total_content_items", getCount { database.contentItemDao().getAllContentItems() })
            stats.put("total_personas", getCount { database.personaDao().getAllPersonasList() })
            stats.put("total_tasks", getCount { database.taskDao().getAllTasks() })
            stats.put("total_subscriptions", getCount { database.subscriptionDao().getAllSubscriptions() })
            stats.put("total_task_submissions", getCount { database.taskSubmissionDao().getAllTaskSubmissions() })
            stats.put("total_chat_messages", getCount { database.chatMessageDao().getAllMessages().first() })
            stats.put("total_file_contexts", getCount { database.fileContextDao().getAllFileContexts().first() })
            stats.put("total_courses", getCount { database.courseDao().getAllCourses() })
            stats.put("total_roles", getCount { database.rolDao().getAllRoles() })
            stats.put("total_recursos", getCount { database.recursoDao().getAllRecursos() })
            stats.put("total_rol_recursos", getCount { database.rolRecursoDao().getAllRolRecursos() })
            
            json.put("statistics", stats)

            // Empty data lists to save memory/bandwidth
            // The LLM must use tools to fetch actual data
            json.put("usuarios", JSONArray())
            json.put("videos", JSONArray())
            json.put("topics", JSONArray())
            json.put("contentItems", JSONArray())
            json.put("personas", JSONArray())
            json.put("tasks", JSONArray())
            json.put("subscriptions", JSONArray())
            json.put("taskSubmissions", JSONArray())
            json.put("chatMessages", JSONArray())
            json.put("fileContexts", JSONArray())
            json.put("courses", JSONArray())
            json.put("roles", JSONArray())
            json.put("recursos", JSONArray())
            json.put("rolRecursos", JSONArray())

            // Esquema completo de TODAS las tablas
            val schema = JSONObject()
            schema.put("usuarios", "usuario, contrasena, persona_id, rol_id")
            schema.put("personas", "id, identificacion, nombres, apellidos, email, telefono, direccion, fechaNacimiento, avatar, esUsuario")
            schema.put("videos", "id, username, description, title, videoUriString, timestamp, localFilePath, isPaid, thumbnailUri, price")
            schema.put("topics", "id, courseId, name, description, orderIndex, videoId")
            schema.put("contentItems", "id, topicId, name, uriString, contentType, orderIndex")
            schema.put("tasks", "id, topicId, name, description, orderIndex, completed")
            schema.put("subscriptions", "subscriberUsername, creatorUsername, subscriptionDate")
            schema.put("taskSubmissions", "id, taskId, username, submissionText, submissionDate, isCompleted")
            schema.put("chatMessages", "id, mensaje, esUsuario, timestamp, calificacion, esPositiva")
            schema.put("fileContexts", "id, fileName, fileType, jsonContent, uploadDate, isActive")
            schema.put("courses", "id, title, description, creatorUsername, price, createdDate")
            schema.put("roles", "id, nombre, descripcion, permisos")
            schema.put("recursos", "id, nombre, descripcion, tipo, url")
            schema.put("rolRecursos", "id, rol_id, recurso_id, puede_leer, puede_escribir, puede_eliminar")
            json.put("schema", schema)

            return@withContext json.toString()
        } catch (e: Exception) {
            Log.e(tag, "Error generating database JSON", e)
            return@withContext "{}"
        }
    }

    /**
     * Builds and returns the database context string
     * Contains schema and summary information for use with the LLM
     */
    private suspend fun getDatabaseContext(): String = withContext(Dispatchers.IO) {
        try {
            // Get fresh database JSON
            val dbJson = generateDatabaseJson()
            
            // Convert JSON to more readable format
            val jsonObj = JSONObject(dbJson)
            val tableNames = jsonObj.keys().asSequence().toList()
            
            // Build a structured context with table schema and sample data
            val context = StringBuilder()
            
            // Add database overview
            context.appendLine("# ESQUEMA DE LA BASE DE DATOS")
            context.appendLine("## Tablas disponibles")
            tableNames.forEach { tableName -> 
                context.appendLine("- $tableName")
            }
            context.appendLine()
            
            // Add table details with row counts
            tableNames.forEach { tableName ->
                val tableArray = jsonObj.optJSONArray(tableName)
                val rowCount = tableArray?.length() ?: 0
                
                context.appendLine("## Tabla: $tableName (${rowCount} registros)")
                
                // Add schema information from the first row if available
                if (rowCount > 0) {
                    val firstRow = tableArray!!.getJSONObject(0)
                    val columns = firstRow.keys().asSequence().toList()
                    
                    context.appendLine("### Columnas:")
                    columns.forEach { column ->
                        val value = firstRow.opt(column)
                        val type = when(value) {
                            is Int, is Long -> "Número"
                            is Boolean -> "Boolean"
                            else -> "Texto"
                        }
                        context.appendLine("- $column: $type")
                    }
                    
                    // Add a few sample records
                    context.appendLine("### Ejemplo de datos:")
                    val sampleSize = minOf(3, rowCount)
                    for (i in 0 until sampleSize) {
                        val row = tableArray.getJSONObject(i)
                        context.appendLine("- Registro ${i+1}: ${row.toString().take(100)}")
                    }
                } else {
                    context.appendLine("*Tabla vacía*")
                }
                context.appendLine()
            }
            
            // Add special schema information
            context.appendLine("## Esquemas específicos")
            context.appendLine("### Tasks:")
            context.appendLine(taskSchema)
            context.appendLine()
            context.appendLine("### Subscriptions:")
            context.appendLine(subscriptionSchema)
            
            return@withContext context.toString()
        } catch (e: Exception) {
            Log.e(tag, "Error generating database context", e)
            return@withContext "Error al generar contexto de base de datos: ${e.message}"
        }
    }
}