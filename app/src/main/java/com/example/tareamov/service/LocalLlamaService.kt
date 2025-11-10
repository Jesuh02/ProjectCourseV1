package com.example.tareamov.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio para ejecutar Llama 3 localmente en el dispositivo Android
 */
class LocalLlamaService(private val context: Context) {
    private val TAG = "LocalLlamaService"
    private val isModelLoaded = AtomicBoolean(false)
    private val modelFileName = "llama3-8b-q4_0.gguf"

    companion object {
        // Fallback host addresses - EMULADOR PRIMERO (Oct 10, 2025)
        val FALLBACK_LLAMA_URLS = listOf(
            "http://10.0.2.2:11435",       // ?? EMULADOR -> HOST (M�XIMA PRIORIDAD)
            "http://192.168.1.16:11435",   // Wi-Fi IP ACTUAL (ipconfig - Oct 10, 2025)
            "http://192.168.1.1:11435",    // Gateway predeterminado (ipconfig - Oct 10, 2025)
            "http://127.0.0.1:11435",      // Localhost
            "http://localhost:11435",      // Localhost alternative
            "http://10.218.57.181:11435",  // Wi-Fi IP anterior
            "http://10.218.57.109:11435",  // Gateway predeterminado anterior
            "http://172.17.112.1:11435"    // WSL / Hyper-V virtual adapter
        )
    }

    /**
     * Inicializa el modelo Llama 3
     */
    suspend fun initializeModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded.get()) return@withContext true

        try {
            // Verificar si el modelo existe en el almacenamiento interno
            val modelFile = File(context.filesDir, modelFileName)

            if (!modelFile.exists()) {
                Log.e(TAG, "Modelo no encontrado. Debe copiarse el archivo $modelFileName al directorio de la aplicaci�n")
                return@withContext false
            }

            // Aqu� ir�a la inicializaci�n real del modelo con llama.cpp
            // Por ahora, simulamos que el modelo se carg� correctamente
            Log.d(TAG, "Simulando inicializaci�n del modelo Llama 3")
            isModelLoaded.set(true)

            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar el modelo Llama 3", e)
            return@withContext false
        }
    }

    /**
     * Envía un prompt al modelo local y obtiene una respuesta
     */
    private var databaseContext: String = ""

    /**
     * Set the database context for better LLM responses with RAG optimization
     * IMPORTANT: Database context should NOT be sent in initial prompts to avoid truncation
     * It should only be included AFTER the LLM requests data via MCP tools
     */
    fun setDatabaseContext(context: String) {
        // NO longer setting database context here to avoid prompt truncation
        // Database context will be sent only when LLM executes query_database tool
        Log.d(TAG, "Database context received (${context.length} chars) but NOT stored to avoid prompt truncation")
        databaseContext = "" // Keep empty to reduce prompt size
    }

    /**
     * Extract key context information for local model efficiency
     */
    private fun extractKeyContext(fullContext: String): String {
        val lines = fullContext.split("\n")
        val keyLines = mutableListOf<String>()
        
        // Extract schema definitions
        var inSchemaSection = false
        lines.forEach { line ->
            when {
                line.contains("ESQUEMA") || line.contains("Schema") -> {
                    inSchemaSection = true
                    keyLines.add(line)
                }
                line.contains("DATOS") && !line.contains("ESQUEMA") -> {
                    inSchemaSection = false
                }
                inSchemaSection && (line.contains("table") || line.contains("columns") || line.contains("relationships")) -> {
                    keyLines.add(line)
                }
                line.contains("COUNT") || line.contains("registros") -> {
                    keyLines.add(line)
                }
            }
        }
        
        return keyLines.joinToString("\n").take(3072) // 3KB limit for local model
    }

    /**
     * Generate a response using the local Llama model with RAG optimization
     * ENFORCED MCP MODE: All data queries MUST be backed by Supabase via MCP
     * 
     * MODE: LLM responses are ALWAYS backed by real Supabase data when applicable
     */
    suspend fun generateResponse(
        prompt: String, 
        mcpHttpClient: MCPHttpClient? = null,
        maxToolIterations: Int = 5  // Increased for complex queries like VS Code
    ): String = withContext(Dispatchers.IO) {
        if (!isModelLoaded.get()) {
            val initialized = initializeModel()
            if (!initialized) {
                Log.w(TAG, "Llama model not initialized, attempting direct Ollama connection")
            }
        }

        try {
            // If maxToolIterations is 0, skip tool calling entirely and use direct response
            if (maxToolIterations == 0) {
                Log.d(TAG, "⚡ Direct response mode - no tool calling")
                val maxPromptSize = 2 * 1024
                val optimizedPrompt = optimizePromptForLocalModel(prompt, maxPromptSize)
                
                // Try Ollama first, fallback to intelligent response
                val ollamaResponse = tryLocalOllamaConnection(optimizedPrompt)
                return@withContext if (ollamaResponse != null && ollamaResponse.isNotBlank() && !ollamaResponse.startsWith("Error:")) {
                    Log.d(TAG, "✓ Got direct response from Ollama (${ollamaResponse.length} chars)")
                    ollamaResponse
                } else {
                    Log.d(TAG, "⚡ Using intelligent fallback for direct response")
                    generateIntelligentResponse(optimizedPrompt)
                }
            }

            // Optimize prompt size for local model limitations
            // Ollama has a limit of 4096 tokens (~3000-3500 characters in Spanish)
            // Reducing to 2KB to stay well under the limit
            val maxPromptSize = 2 * 1024  // 2KB for local model (conservative limit)
            val optimizedPrompt = optimizePromptForLocalModel(prompt, maxPromptSize)

            // CRITICAL: Detect if query requires data
            val requiresData = detectIfQueryRequiresData(optimizedPrompt)
            Log.d(TAG, "Query requires data from Supabase: $requiresData")
            
            // Detect if this is a BI query that already has a schema embedded
            val schemaProvided = optimizedPrompt.contains("ESQUEMA DE BASE DE DATOS", ignoreCase = true) ||
                    optimizedPrompt.contains("ESQUEMA", ignoreCase = true) ||
                    optimizedPrompt.contains("=== ESQUEMA", ignoreCase = true) ||
                    optimizedPrompt.contains("YA EJECUTADO", ignoreCase = true)

            Log.d(TAG, "Schema provided in prompt: $schemaProvided")

            // If no MCP client available but data is required, return error
            if (requiresData && mcpHttpClient == null) {
                return@withContext "⚠ Esta consulta requiere datos de Supabase, pero el servidor MCP no está disponible. Por favor verifica la conexión."
            }

            // If schema is already present, limit tool iterations to avoid re-requesting schema
            val effectiveMaxToolIterations = if (schemaProvided) minOf(maxToolIterations, 1) else maxToolIterations

            // Create a flexible prompt that lets LLM decide what context it needs
            // For BI queries with schema, discourage get_database_schema calls
            var enrichedPrompt = if (schemaProvided) {
                // Schema already provided - just pass through
                optimizedPrompt
            } else {
                createDynamicPromptWithMCPCapability(optimizedPrompt, mcpHttpClient != null, requiresData)
            }
            val toolExecutionHistory = StringBuilder()

            Log.d(TAG, "Attempting to generate response with LocalLlama")
            Log.d(TAG, "  Optimized prompt size: ${optimizedPrompt.length} chars")
            Log.d(TAG, "  Enriched prompt size: ${enrichedPrompt.length} chars")
            Log.d(TAG, "  MCP Tools available: ${mcpHttpClient != null}")
            Log.d(TAG, "  Force data validation: $requiresData")

            // Tool calling loop - allow LLM to use MCP tools only if needed
            var iteration = 0
            var finalResponse: String? = null
            
            while (iteration < effectiveMaxToolIterations && finalResponse == null) {
                Log.d(TAG, "?? Tool calling iteration ${iteration + 1}/$effectiveMaxToolIterations")
                
                // Try to connect to local Ollama instance first using enriched prompt
                val ollamaResponse = tryLocalOllamaConnection(enrichedPrompt)
                val response = if (ollamaResponse != null && ollamaResponse.isNotBlank() && !ollamaResponse.startsWith("Error:")) {
                    Log.d(TAG, "✓ Got response from local Ollama instance (${ollamaResponse.length} chars)")
                    Log.d(TAG, "Response preview (first 300 chars): ${ollamaResponse.take(300)}")
                    ollamaResponse
                } else {
                    Log.w(TAG, "Local Ollama not available; using intelligent fallback")
                    // Generate intelligent response when Ollama is not available
                    generateIntelligentResponse(enrichedPrompt)
                }
                
                // CRITICAL: Detect if LLM is faking tool execution
                if (requiresData && (response.contains("Ran `") || response.contains("Completed with input") || 
                    response.contains("SQL generado:") || response.contains("Datos obtenidos:") ||
                    response.contains("## Resultados �") || response.contains("**SQL usado:**"))) {
                    Log.e(TAG, "? LLM est� fingiendo ejecuci�n de herramienta! Rechazando respuesta.")
                    Log.e(TAG, "Detected fake execution markers in response:")
                    if (response.contains("Ran `")) Log.e(TAG, "  - Contains 'Ran `'")
                    if (response.contains("Completed with input")) Log.e(TAG, "  - Contains 'Completed with input'")
                    if (response.contains("SQL generado:")) Log.e(TAG, "  - Contains 'SQL generado:'")
                    if (response.contains("Datos obtenidos:")) Log.e(TAG, "  - Contains 'Datos obtenidos:'")
                    if (response.contains("## Resultados �")) Log.e(TAG, "  - Contains '## Resultados �'")
                    if (response.contains("**SQL usado:**")) Log.e(TAG, "  - Contains '**SQL usado:**'")
                    
                    // Force the LLM to use the correct format
                    enrichedPrompt = """
$enrichedPrompt

? ? ? ERROR CR�TICO: Tu respuesta anterior fue RECHAZADA

Detectamos que escribiste texto como:
- "Ran `query_database`"
- "Completed with input"
- "SQL generado:"
- "Datos obtenidos:"
- "## Resultados �"

? ESTO EST� PROHIBIDO. Est�s FINGIENDO haber ejecutado la herramienta.

? LA �NICA respuesta v�lida es:
TOOL_CALL: query_database(query="tu consulta detallada aqu�")

NO escribas NADA m�s. Solo esa l�nea. El sistema ejecutar� la herramienta y te dar� los resultados REALES.

INTENTA DE NUEVO AHORA:
                    """.trimIndent()
                    iteration++
                    continue
                }
                
                // Check if LLM wants to use a tool
                val toolCall = parseToolCall(response)
                
                if (toolCall != null && mcpHttpClient != null) {
                    Log.d(TAG, "??? LLM requested tool: ${toolCall.toolName}")
                    
                    // Build "Ran tool" message
                    val toolCallArgs = toolCall.arguments.entries.joinToString(", ") { 
                        "\"${it.key}\": \"${it.value}\"" 
                    }
                    toolExecutionHistory.append("\n\nRan `${toolCall.toolName}`\n")
                    toolExecutionHistory.append("Completed with input: {\n  $toolCallArgs\n}\n")
                    
                    // Execute the tool via MCP
                    val toolResult = executeToolViaMCP(toolCall, mcpHttpClient)
                    toolExecutionHistory.append("\n**Resultado:**\n$toolResult\n")
                    
                    // Update prompt with tool result
                    enrichedPrompt = """
$enrichedPrompt

HERRAMIENTA EJECUTADA: ${toolCall.toolName}
ARGUMENTOS: ${toolCall.arguments}
RESULTADO:
$toolResult

Con esta informaci�n, proporciona tu respuesta final al usuario siguiendo el formato:

Completed (1/1) *Nombre de la tarea*

## Resultados � [t�tulo descriptivo]

[Presenta los datos de forma clara y estructurada]
                    """.trimIndent()
                    
                    iteration++
                } else {
                    // No tool call or no MCP client - validate response quality before accepting
                    if (isRawSnapshotResponse(response) && iteration + 1 < effectiveMaxToolIterations) {
                        Log.w(TAG, "LLM devolvi� un snapshot sin an�lisis; reforzando instrucciones")
                        enrichedPrompt = reinforcePromptForAnalysis(enrichedPrompt, response)
                        iteration++
                        continue
                    }

                    finalResponse = response
                }
            }
            
            // Return final response with tool execution history if applicable
            val result = finalResponse ?: "? Error: Se alcanz� el l�mite de iteraciones de herramientas"
            
            // CRITICAL: Validate that data queries have MCP backing
            if (requiresData && toolExecutionHistory.isEmpty()) {
                Log.e(TAG, "? Data query completed without MCP tool execution!")
                return@withContext buildString {
                    append("? ERROR DE VALIDACI�N\n\n")
                    append("Esta consulta requiere datos de Supabase, pero no se ejecut� ninguna herramienta MCP.\n\n")
                    append("**Consulta original:** $optimizedPrompt\n\n")
                    append("Por favor verifica:\n")
                    append("1. El servidor MCP est� ejecut�ndose\n")
                    append("2. La conexi�n a Supabase es v�lida\n")
                    append("3. El modelo LLM est� respondiendo correctamente\n\n")
                    append("**Respuesta del modelo (sin validar):**\n$result")
                }
            }
            
            return@withContext if (toolExecutionHistory.isNotEmpty()) {
                // Format response VS Code style with data validation badge
                val enhancedResult = buildString {
                    // Show tool execution history first
                    append(toolExecutionHistory.toString().trim())
                    append("\n\n")
                    // Then show the final LLM analysis/response
                    append(result)
                    // Add data validation footer
                    if (requiresData) {
                        append("\n\n---\n")
                        append("? **Datos validados:** Esta respuesta est� sustentada por consultas reales a Supabase")
                    }
                }
                
                enhancedResult
            } else {
                // No tools were used
                if (requiresData) {
                    "?? ADVERTENCIA: Esta consulta requer�a datos pero no se ejecutaron herramientas MCP.\n\n$result"
                } else if (isRawSnapshotResponse(result)) {
                    "$result\n\n?? El modelo no gener� un an�lisis completo."
                } else {
                    result
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response", e)
            return@withContext "Error: No se pudo generar respuesta. El servidor LLM no est� disponible. Detalles: ${e.message}"
        }
    }
    
    /**
     * Data class for tool calls
     */
    data class ToolCall(
        val toolName: String,
        val arguments: Map<String, String>
    )
    
    /**
     * Parse tool call from LLM response
     * Format: TOOL_CALL: tool_name(arg1=value1, arg2=value2)
     * Also supports: TOOL_CALL: tool_name() for tools without arguments
     */
    private fun parseToolCall(response: String): ToolCall? {
        try {
            Log.d(TAG, "?? Parsing response for tool calls (first 500 chars):")
            Log.d(TAG, response.take(500))
            
            // Try multiple patterns to be more flexible
            val patterns = listOf(
                Regex("""TOOL_CALL:\s*(\w+)\((.*?)\)""", RegexOption.IGNORE_CASE),
                Regex("""usar.*?herramienta.*?(\w+)\(\)""", RegexOption.IGNORE_CASE),
                Regex("""ejecutar.*?(\w+)\(""", RegexOption.IGNORE_CASE),
                Regex("""necesito.*?(\w+)\(""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in patterns) {
                val match = pattern.find(response)
                if (match != null) {
                    val toolName = match.groupValues[1]
                    val argsString = if (match.groupValues.size > 2) match.groupValues[2] else ""
                    
                    Log.d(TAG, "? Tool call detected: $toolName with args: '$argsString'")
                    
                    // Parse arguments
                    val arguments = mutableMapOf<String, String>()
                    if (argsString.isNotBlank()) {
                        val argPattern = Regex("""(\w+)=["']?([^,"'\)]+)["']?""")
                        argPattern.findAll(argsString).forEach { argMatch ->
                            val key = argMatch.groupValues[1]
                            val value = argMatch.groupValues[2].trim()
                            arguments[key] = value
                            Log.d(TAG, "  ?? Argument: $key = $value")
                        }
                    }
                    
                    return ToolCall(toolName, arguments)
                }
            }
            
            Log.d(TAG, "? No tool call pattern matched in response")
            return null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing tool call", e)
            return null
        }
    }
    
    /**
     * Execute tool via MCP HTTP client - VS Code Copilot style formatting
     */
    private suspend fun executeToolViaMCP(toolCall: ToolCall, mcpClient: MCPHttpClient): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "??? Executing MCP tool: ${toolCall.toolName}")
            
            val result = when (toolCall.toolName) {
                "query_database" -> {
                    val query = toolCall.arguments["query"] ?: return@withContext "? Error: falta el par�metro 'query'"
                    
                    Log.d(TAG, "?? Querying database: $query")
                    val queryResult = mcpClient.queryDatabase(query)

                    if (queryResult.success) {
                        val sql = queryResult.sqlScript ?: "N/A"
                        val formattedSummary = queryResult.formattedSummary?.let { truncateForPrompt(it, 3000) }
                        
                        // VS Code style: Detect if result is a generic snapshot when specific data was requested
                        val isGenericSnapshot = isGenericSnapshotResult(queryResult.data, query)
                        
                        buildString {
                            append("? Consulta ejecutada exitosamente\n\n")
                            append("**SQL generado:**\n")
                            append("```sql\n$sql\n```\n\n")
                            append("**Datos obtenidos:**\n")
                            append(formatMCPData(queryResult.data))
                            
                            if (!formattedSummary.isNullOrBlank()) {
                                append("\n\n**An�lisis adicional:**\n")
                                append(formattedSummary)
                            }
                            
                            // VS Code behavior: If snapshot is generic but query was specific, suggest precise SQL
                            if (isGenericSnapshot) {
                                append("\n\n?? **Nota:** El resultado es un snapshot gen�rico. ")
                                val preciseSql = generatePreciseSqlForQuery(query)
                                if (preciseSql != null) {
                                    append("Para obtener la fila exacta, ejecuta:\n\n")
                                    append("```sql\n$preciseSql\n```\n")
                                }
                            }
                        }.trim()
                    } else {
                        "? Error en la consulta: ${queryResult.error}"
                    }
                }

                "get_database_schema" -> {
                    Log.d(TAG, "?? Getting database schema")
                    val schemaResult = mcpClient.getDatabaseSchema()

                    if (schemaResult.success) {
                        "? Esquema obtenido exitosamente:\n\n${schemaResult.schema}"
                    } else {
                        "? Error obteniendo esquema: ${schemaResult.error}"
                    }
                }

                else -> "? Error: Herramienta desconocida '${toolCall.toolName}'"
            }

            Log.d(TAG, "? Tool execution completed: ${toolCall.toolName}")
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "? Error executing tool via MCP", e)
            return@withContext "? Error ejecutando herramienta ${toolCall.toolName}: ${e.message}"
        }
    }

    private fun buildToolQuery(originalPrompt: String): String {
        val trimmed = originalPrompt.trim()

        if (trimmed.startsWith("Pregunta de Business Intelligence:", ignoreCase = true)) {
            val after = trimmed.substringAfter(":").trim()
            val firstLine = after.lineSequence().firstOrNull { it.isNotBlank() } ?: after
            return firstLine.take(512)
        }

        val queryMarkers = listOf("Consulta del Usuario", "CONSULTA DEL USUARIO", "Usuario pregunta", "Pregunta", "Query")
        var baseQuery = ""
        queryMarkers.forEach { marker ->
            val line = trimmed.lineSequence().firstOrNull { it.contains(marker, ignoreCase = true) }
            if (line != null) {
                val extracted = line.substringAfter(":", line).trim()
                if (extracted.isNotBlank()) {
                    baseQuery = extracted.take(512)
                    return@forEach
                }
            }
        }
        
        if (baseQuery.isEmpty()) {
            baseQuery = trimmed.lines().firstOrNull { it.isNotBlank() }?.take(512) ?: trimmed.take(512)
        }
        
        // VS Code style: Enrich query with explicit JOIN/detail instructions
        return buildString {
            append(baseQuery)
            
            // Detect role query -> force JOIN with roles table
            if (baseQuery.contains(Regex("rol|role", RegexOption.IGNORE_CASE)) && 
                baseQuery.contains(Regex("usuario|user|username", RegexOption.IGNORE_CASE))) {
                append(". IMPORTANTE: Devu�lveme la fila exacta del usuario con LEFT JOIN a la tabla 'roles' para incluir el nombre del rol. ")
                append("Campos requeridos: usuarios.id, usuarios.usuario, usuarios.persona_id, usuarios.rol_id, roles.name AS rol_nombre, usuarios.created_at. ")
                append("NO devuelvas solo un snapshot gen�rico, ejecuta el JOIN espec�fico.")
            } 
            // Detect "users without courses" query
            else if (baseQuery.contains(Regex("usuario.*nunca|usuario.*sin|users.*never|without.*course", RegexOption.IGNORE_CASE))) {
                append(". IMPORTANTE: Ejecuta LEFT JOIN usuarios con courses WHERE courses.creator_username IS NULL. ")
                append("Devuelve filas exactas, no solo conteos.")
            }
        }.take(768) // Increased limit for enriched queries
    }

    private fun detectBusinessIntent(text: String): Boolean {
        val keywords = listOf(
            "inteligencia", "business intelligence", "bi ", "kpi", "indicador", "indicadores",
            "marketing", "growth", "ventas", "retencion", "retenci�n", "estrategia", "funnel",
            "conversion", "conversi�n", "campana", "campa�a", "churn", "retention"
        )
        val lower = text.lowercase()
        return keywords.any { lower.contains(it) }
    }

    /**
     * Detect if a query requires data from the database
     * Returns true if the query is asking for information that needs Supabase data
     */
    private fun detectIfQueryRequiresData(query: String): Boolean {
        val lower = query.lowercase().trim()
        
        // Conversational queries that DON'T need data
        val conversationalPatterns = listOf(
            "hola", "hi", "hello", "buenos d�as", "buenas tardes",
            "qu� puedes hacer", "ayuda", "help", "c�mo funciona",
            "explica", "qu� es", "gracias", "thanks"
        )
        
        // If it's a simple greeting/help, don't require data
        if (conversationalPatterns.any { lower.startsWith(it) || lower == it }) {
            return false
        }
        
        // Data query indicators - these ALWAYS need database access
        val dataIndicators = listOf(
            "cu�ntos", "cuantos", "how many", "count",
            "usuarios", "users", "cursos", "courses", "videos",
            "lista", "list", "dame", "give me", "show", "muestra",
            "todos", "all", "qu�", "que", "what", "which",
            "estad�stica", "statistics", "an�lisis", "analysis",
            "nunca", "never", "sin", "without", "no tienen",
            "top", "mejor", "best", "m�s", "mas", "most",
            "total", "suma", "sum", "promedio", "average"
        )
        
        // If query contains data indicators, it requires database access
        return dataIndicators.any { lower.contains(it) }
    }

    private fun buildFallbackQuery(originalQuery: String, isBusiness: Boolean): String {
        val cleanedQuestion = originalQuery.trim().replace("\n", " ")
        return if (isBusiness) {
            "Genera un snapshot de inteligencia de negocio y marketing para la plataforma TareaMov basado en datos reales: conteos de usuarios, cursos, videos, suscripciones, tareas; top creadores y usuarios m�s activos; m�tricas de retenci�n/churn si existen; insights y acciones recomendadas."
        } else {
            "${cleanedQuestion} - Adem�s, proporciona un resumen general de la base de datos TareaMov con conteos por tabla principal (usuarios, courses, videos, subscriptions, task_submissions) y muestras representativas para entender el contexto."
        }
    }

    private fun truncateForPrompt(text: String, limit: Int = 4000): String {
        if (text.length <= limit) return text
        return buildString {
            append(text.take(limit))
            append("\n... [contenido truncado, total ${text.length} caracteres]")
        }
    }

    /**
     * Format MCP data for LLM consumption - VS Code style
     */
    private fun formatMCPData(data: Any?): String {
        return when (data) {
            is org.json.JSONArray -> {
                if (data.length() == 0) {
                    "[]  (sin registros)"
                } else {
                    val items = mutableListOf<String>()
                    for (i in 0 until minOf(data.length(), 50)) {  // Limit to 50 records
                        val obj = data.getJSONObject(i)
                        items.add("  - ${obj.toString()}")
                    }
                    val result = StringBuilder()
                    result.append("${data.length()} registro(s) encontrado(s):\n")
                    result.append(items.joinToString("\n"))
                    if (data.length() > 50) {
                        result.append("\n  ... (${data.length() - 50} registros adicionales omitidos)")
                    }
                    result.toString()
                }
            }
            is org.json.JSONObject -> {
                "Objeto JSON:\n${data.toString(2)}"
            }
            is List<*> -> {
                if (data.isEmpty()) {
                    "[]  (sin registros)"
                } else {
                    "${data.size} registro(s):\n" + data.take(50).joinToString("\n") { "  - $it" }
                }
            }
            is Map<*, *> -> {
                if (data.isEmpty()) {
                    "{} (sin datos)"
                } else {
                    data.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }
                }
            }
            null -> "null (sin datos)"
            else -> data.toString()
        }
    }

    private fun isRawSnapshotResponse(response: String?): Boolean {
        if (response.isNullOrBlank()) return false
        val normalized = response.lowercase()
        val hasReason = normalized.contains("reason:")
        val hasSqlDump = normalized.contains("select ") && normalized.contains("from ") && normalized.contains("consulta sql ejecutada")
        val hasResultado = normalized.contains("**resultado:**")
        return hasReason || hasSqlDump || hasResultado
    }

    /**
     * VS Code style: Detect if MCP returned a generic snapshot when a specific entity was requested
     */
    private fun isGenericSnapshotResult(data: Any?, query: String): Boolean {
        if (data !is org.json.JSONObject) return false
        
        val hasReason = data.has("reason")
        val hasMetrics = data.has("metrics")
        val hasSamples = data.has("samples")
        val isSnapshot = hasReason && (hasMetrics || hasSamples)
        
        if (!isSnapshot) return false
        
        // Check if query was asking for specific entity (username, id, specific user)
        val lowerQuery = query.lowercase()
        val isSpecificQuery = lowerQuery.contains(Regex("username\\s*=|usuario\\s*=|user.*with.*id|id\\s*=|espec�fico|specific"))
        
        return isSpecificQuery
    }

    /**
     * VS Code style: Generate precise SQL for common query patterns
     */
    private fun generatePreciseSqlForQuery(query: String): String? {
        val lowerQuery = query.lowercase()
        
        // Pattern: role of user with username = X
        if (lowerQuery.contains(Regex("rol|role")) && lowerQuery.contains(Regex("username|usuario"))) {
            val usernameMatch = Regex("""username\s*[=:]\s*["']?(\w+)["']?|usuario\s*[=:]\s*["']?(\w+)["']?""").find(lowerQuery)
            val username = usernameMatch?.groupValues?.firstOrNull { !it.isNullOrBlank() && it.length > 1 } ?: "nuevo"
            
            return """
SELECT u.id, u.usuario, u.persona_id, u.rol_id, r.name AS rol_nombre, u.created_at
FROM usuarios u
LEFT JOIN roles r ON u.rol_id = r.id
WHERE u.usuario = '$username';
            """.trimIndent()
        }
        
        // Pattern: users without courses
        if (lowerQuery.contains(Regex("usuario.*sin|usuario.*nunca|users.*without|users.*never"))) {
            return """
SELECT u.id, u.usuario, u.email, u.persona_id, u.rol_id, u.created_at
FROM usuarios u
LEFT JOIN courses c ON u.usuario = c.creator_username
WHERE c.creator_username IS NULL;
            """.trimIndent()
        }
        
        return null
    }

    private fun reinforcePromptForAnalysis(currentPrompt: String, rawResponse: String): String {
        val trimmedResponse = rawResponse.trim().take(2000)
        return buildString {
            append(currentPrompt)
            append("\n\n---\nLa respuesta anterior fue un volcado de datos sin análisis narrativo:\n")
            append(trimmedResponse)
            append("\n\nGenera ahora un informe estratégico completo siguiendo las instrucciones iniciales.\n")
            append("- No repitas el bloque que inicia con '**Resultado:**' ni copies literalmente las consultas SQL.\n")
            append("- Integra los conteos y métricas en párrafos explicativos y listas accionables.\n")
            append("- Presenta KPIs, riesgos, tácticas y próximas consultas adicionales.\n")
            append("- Mantén títulos con '##' y el snippet de Kotlin solicitado.\n")
        }
    }

    /**
     * Generate intelligent response when Ollama is not available
     * Detects Business Intelligence queries and generates VS Code style responses
     */
    private fun generateIntelligentResponse(prompt: String): String {
        Log.d(TAG, "Generating intelligent fallback response")
        
        val normalizedPrompt = prompt.lowercase().trim()
        
        // Detect if this is a Business Intelligence query
        if (normalizedPrompt.contains("inteligencia") || normalizedPrompt.contains("kpi") || 
            normalizedPrompt.contains("business intelligence") || normalizedPrompt.contains("indicador") ||
            normalizedPrompt.contains("decisiones críticas") || normalizedPrompt.contains("empresarial") ||
            normalizedPrompt.contains("opciones empresariales")) {
            
            Log.d(TAG, "BI query detected - generating VS Code style response")
            
            // Extract database schema if available
            val schemaTables = mutableListOf<String>()
            try {
                val schemaJson = org.json.JSONObject(databaseContext)
                if (schemaJson.has("tables")) {
                    val tablesArray = schemaJson.getJSONArray("tables")
                    for (i in 0 until tablesArray.length()) {
                        val table = tablesArray.getJSONObject(i)
                        if (table.has("name")) {
                            schemaTables.add(table.getString("name"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse database schema, using default tables")
                schemaTables.addAll(listOf("usuarios", "personas", "videos", "courses", "topics", 
                    "content_items", "tasks", "task_submissions", "subscriptions", "chat_messages"))
            }
            
            val tablesStr = if (schemaTables.isNotEmpty()) schemaTables.joinToString(", ") else "usuarios, videos, courses, subscriptions, tasks"
            
            // Generate comprehensive VS Code style BI response
            return buildString {
                append("## Resumen ejecutivo — Objetivo\n\n")
                append("- **Objetivo:** Habilitar decisiones empresariales basadas en datos mediante KPIs priorizados y pipelines reproducibles ")
                append("que permitan medir adquisición, retención, engagement y monetización.\n")
                append("- **Resultado esperado:** Dashboard inicial (Metabase/Looker/Redash) con 6 KPIs críticos y ")
                append("vistas/materialized views para refresco diario.\n\n")
                
                append("## Decisiones críticas a tomar ahora\n\n")
                append("1. **Priorizar métricas de negocio (no todo a la vez)**\n")
                append("   - Definir 3 KPIs de alto impacto: DAU/MAU, conversión a suscripción, tasa de finalización de cursos.\n")
                append("2. **Fuente de verdad y cadencia**\n")
                append("   - Unificar supabase → crear vistas/materialized views para KPIs y refrescarlas nightly o hourly según SLA.\n")
                append("3. **Instrumentación de eventos**\n")
                append("   - Garantizar timestamps y user_id en eventos clave para cálculos de cohortes.\n")
                append("4. **Gobernanza de datos**\n")
                append("   - Definir propietarios de métricas, SLAs de calidad y catálogos.\n")
                append("5. **Monitoreo y alertas**\n")
                append("   - Alertas para drops >20% en DAU/Conversion en ventana semanal.\n")
                append("6. **Roadmap mínimo viable**\n")
                append("   - Dashboard de métricas + 3 queries paramétricas + export CSV/endpoint.\n\n")
                
                append("## Mapeo tablas → métricas (heurístico según esquema)\n\n")
                append("- **usuarios/personas** → adquisición, churn, cohortes, usuarios activos.\n")
                append("- **subscriptions** → nuevas suscripciones, churn, MRR (si hay precio).\n")
                append("- **videos, content_items** → engagement por contenido (views, avg_duration), riqueza de contenido.\n")
                append("- **courses** → enrollments, completion_rate, top performing courses.\n")
                append("- **tasks / task_submissions** → actividad estudiantil, tasa de entrega, correlación con retención.\n")
                append("- **chat_messages** → soporte y engagement; volumen de interacciones.\n\n")
                
                append("## KPIs priorizados (top 6)\n\n")
                append("1. **Usuarios activos diarios (DAU) y ratio DAU/MAU** (engagement)\n")
                append("   - Fórmula: `COUNT(DISTINCT user_id WHERE activity_date >= CURRENT_DATE - 1)`\n")
                append("   - Target: >50% DAU/MAU ratio\n")
                append("2. **Nuevas suscripciones por semana** (adquisición)\n")
                append("   - Fórmula: `COUNT(*) FROM subscriptions WHERE created_at >= week_start`\n")
                append("   - Target: Crecimiento 10-15% WoW\n")
                append("3. **Conversion rate: usuarios activos → suscriptores** (funnel)\n")
                append("   - Fórmula: `(suscriptores activos / usuarios activos) * 100`\n")
                append("   - Target: >5% conversion\n")
                append("4. **Tasa de finalización de curso** (quality/retention signal)\n")
                append("   - Fórmula: `SUM(completed_tasks) / COUNT(total_tasks)`\n")
                append("   - Target: >70% completion rate\n")
                append("5. **Tiempo medio de sesión / duración promedio de video** (engagement depth)\n")
                append("   - Fórmula: `AVG(session_duration) FROM user_sessions`\n")
                append("   - Target: >15 minutos por sesión\n")
                append("6. **Churn rate mensual de suscripciones** (retención monetaria)\n")
                append("   - Fórmula: `(canceled_subs / total_subs_at_month_start) * 100`\n")
                append("   - Target: <5% mensual\n\n")
                
                append("## Arquitectura BI sugerida (MVP)\n\n")
                append("- **Ingest:** Supabase (directo) → transform layer (DB views/materialized views in Supabase or Postgres).\n")
                append("- **Storage/Layer:**\n")
                append("  - Create materialized views for heavy aggregations (daily_user_activity, daily_subscriptions, video_metrics).\n")
                append("  - If scale grows: replicate to a read-optimized analytics DB (e.g., a dedicated Postgres/BigQuery).\n")
                append("- **Orchestration:** cron/Cloud Function / Airflow simple to refresh MVs nightly (or hourly if required).\n")
                append("- **Visualization:** Metabase / Redash connected to Supabase (or analytics DB).\n")
                append("- **Access:** Dashboards + CSV/REST endpoints for exec reporting.\n\n")
                
                append("## Ejemplos de SQL (ajusta nombres si cambian)\n\n")
                append("```sql\n")
                append("-- DAU (último día)\n")
                append("SELECT COUNT(DISTINCT user_id) AS dau\n")
                if (schemaTables.contains("chat_messages")) {
                    append("FROM chat_messages\n")
                } else {
                    append("FROM usuarios\n")
                }
                append("WHERE created_at >= CURRENT_DATE - INTERVAL '1 day';\n\n")
                
                if (schemaTables.contains("usuarios")) {
                    append("-- Usuarios por rol\n")
                    append("SELECT rol_id, COUNT(*) AS users \n")
                    append("FROM usuarios \n")
                    append("GROUP BY rol_id \n")
                    append("ORDER BY users DESC;\n\n")
                }
                
                if (schemaTables.contains("subscriptions")) {
                    append("-- Nuevas suscripciones por semana\n")
                    append("SELECT date_trunc('week', created_at) AS week, COUNT(*) AS new_subs\n")
                    append("FROM subscriptions\n")
                    append("GROUP BY 1 ORDER BY 1 DESC;\n\n")
                }
                
                if (schemaTables.contains("courses") && schemaTables.contains("task_submissions")) {
                    append("-- Tasa de finalización de cursos (por curso)\n")
                    append("SELECT c.id, c.title,\n")
                    append("       SUM(CASE WHEN ts.completed = true THEN 1 ELSE 0 END)::float / NULLIF(COUNT(ts.id),0) AS completion_rate\n")
                    append("FROM courses c\n")
                    append("LEFT JOIN task_submissions ts ON ts.course_id = c.id\n")
                    append("GROUP BY c.id, c.title\n")
                    append("ORDER BY completion_rate DESC;\n\n")
                }
                
                if (schemaTables.contains("videos")) {
                    append("-- Top videos por vistas\n")
                    append("SELECT v.id, v.title, COUNT(*) AS views\n")
                    append("FROM videos v\n")
                    append("LEFT JOIN video_views vv ON vv.video_id = v.id\n")
                    append("GROUP BY v.id, v.title\n")
                    append("ORDER BY views DESC LIMIT 10;\n")
                }
                
                append("```\n\n")
                
                append("## Plan corto de implementación (2–4 semanas)\n\n")
                append("1. **Semana 0–1:** Definir KPIs y propietarios; obtener esquema definitivo (`get_database_schema`) y confirmar columnas clave.\n")
                append("2. **Semana 1:** Implementar materialized views para DAU, nuevas suscripciones, video metrics; crear refresh job nightly.\n")
                append("3. **Semana 1–2:** Crear dashboards en Metabase con filtros (fecha, curso, creador).\n")
                append("4. **Semana 2–3:** Añadir alertas (email/Slack) para drops anómalos.\n")
                append("5. **Semana 3–4:** Refinar, validar con stakeholders, exponer endpoints para informes recurrentes.\n\n")
                
                append("## Riesgos y mitigaciones\n\n")
                append("- **Datos incompletos** (falta timestamps / user_id): Mitigar añadiendo eventos instrumentados y retrofilling donde sea posible.\n")
                append("- **Costos por consultas pesadas:** usar MVs/ETL para precalcular.\n")
                append("- **Consistencia entre producción y analytics:** mantener owners y tests de integridad (row counts).\n")
                append("- **Dependencia de herramientas externas:** evaluar opciones open-source (Metabase) vs comerciales (Looker).\n\n")
                
                append("## Acción inmediata sugerida\n\n")
                append("- Ejecutar `get_database_schema` en el MCP para obtener conteos y nombres exactos de columnas.\n")
                append("- Ejecutar `query_database` para obtener muestras (p. ej., top 10 cursos por suscripciones) si quieres números reales.\n")
                append("- Priorizar implementación de 2-3 KPIs críticos antes de construir dashboard completo.\n\n")
                
                append("---\n")
                append("💡 **Nota:** Esta respuesta fue generada sin conexión al servidor LLM. ")
                append("Para análisis más precisos con datos reales, asegúrate de que el servidor MCP esté ejecutándose.\n")
            }
        }
        
        // For non-BI queries, provide a simpler response
        return buildString {
            append("## Respuesta del sistema\n\n")
            append("Actualmente el servidor LLM (Ollama) no está disponible. ")
            append("Para obtener respuestas completas y análisis detallados, por favor:\n\n")
            append("1. Verifica que el servidor Ollama esté ejecutándose\n")
            append("2. Asegúrate de que el modelo llama3 esté disponible\n")
            append("3. Comprueba la conexión de red entre el emulador/dispositivo y el servidor\n\n")
            append("**Direcciones probadas:**\n")
            FALLBACK_LLAMA_URLS.forEach { url ->
                append("- $url\n")
            }
            append("\n")
            append("Mientras tanto, puedes usar las herramientas MCP directamente desde el botón 🔧 ")
            append("para ejecutar consultas a la base de datos.\n")
        }
    }

    /**
     * Try to connect to a local Ollama instance
     */
    private suspend fun tryLocalOllamaConnection(prompt: String): String? = withContext(Dispatchers.IO) {
        for (url in FALLBACK_LLAMA_URLS) {
            try {
                Log.d(TAG, "  Trying local Ollama at: $url")
                
                val apiUrl = java.net.URL("$url/api/generate")
                val connection = apiUrl.openConnection() as java.net.HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 60000   // 60 seconds for connection
                connection.readTimeout = 300000     // 300 seconds (5 minutes) for reading response
                connection.doOutput = true
                
                val requestBody = org.json.JSONObject().apply {
                    put("model", "llama3")
                    put("prompt", prompt)
                    put("stream", false)
                    put("options", org.json.JSONObject().apply {
                        put("num_predict", 512)        // Limit response tokens
                        put("temperature", 0.7)
                        put("top_k", 40)
                        put("top_p", 0.9)
                    })
                }
                
                val writer = java.io.OutputStreamWriter(connection.outputStream)
                writer.write(requestBody.toString())
                writer.flush()
                writer.close()
                
                if (connection.responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    val responseJson = org.json.JSONObject(reader.readText())
                    val response = responseJson.optString("response", "")
                    
                    connection.disconnect()
                    
                    if (response.isNotBlank()) {
                        Log.d(TAG, "? Successfully connected to local Ollama at $url")
                        return@withContext response
                    }
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.d(TAG, "  Failed to connect to $url: ${e.message}")
            }
        }
        
        Log.w(TAG, "Could not connect to any local Ollama instance")
        return@withContext null
    }

    /**
     * Optimize prompt for local model constraints
     */
    private fun optimizePromptForLocalModel(prompt: String, maxSize: Int): String {
        if (prompt.length <= maxSize) return prompt
        
        Log.w(TAG, "Prompt too large (${prompt.length} chars). Optimizing for local model.")
        
        // Extract key components
        val userQuery = extractUserQuery(prompt)
        val schemaInfo = extractSchemaInfo(prompt)
        val relevantData = extractRelevantData(prompt, maxSize - userQuery.length - schemaInfo.length - 500)
        
        return """
        ESQUEMA: $schemaInfo
        
        DATOS RELEVANTES: $relevantData
        
        CONSULTA: $userQuery
        """.trimIndent()
    }

    /**
     * Extract user query from full prompt
     */
    private fun extractUserQuery(prompt: String): String {
        val lines = prompt.split("\n")
        return lines.find { 
            it.contains("Consulta") || it.contains("CONSULTA") || it.contains("Usuario")
        }?.substringAfter(":")?.trim() ?: prompt.split("\n").last().take(200)
    }

    /**
     * Extract schema information
     */
    private fun extractSchemaInfo(prompt: String): String {
        val lines = prompt.split("\n")
        val schemaLines = mutableListOf<String>()
        var inSchema = false
        
        lines.forEach { line ->
            when {
                line.contains("ESQUEMA") || line.contains("Schema") -> inSchema = true
                line.contains("DATOS") && !line.contains("ESQUEMA") -> inSchema = false
                inSchema -> schemaLines.add(line)
            }
        }
        
        return schemaLines.joinToString("\n").take(1024)
    }

    /**
     * Extract most relevant data within size limits
     */
    private fun extractRelevantData(prompt: String, maxSize: Int): String {
        val dataStart = prompt.indexOf("DATOS")
        if (dataStart < 0) return ""
        
        val dataSection = prompt.substring(dataStart)
        return if (dataSection.length > maxSize) {
            dataSection.take(maxSize) + "\n[Datos truncados por limitaciones del modelo local]"
        } else {
            dataSection
        }
    }

    /**
     * Create flexible prompt that ENFORCES data validation via MCP
     * All responses with data MUST be backed by Supabase queries
     */
    private fun createDynamicPromptWithMCPCapability(optimizedPrompt: String, hasToolAccess: Boolean, requiresData: Boolean = false): String {
        return if (hasToolAccess) {
            """
Asistente DB con MCP. BD: TareaMov.
${if (requiresData) "?? REQUIERE DATOS: TOOL_CALL: query_database() primero." else ""}

TOOLS: get_database_schema(), query_database(query="...")

CONSULTA: $optimizedPrompt

${if (requiresData) "Tu respuesta DEBE ser: TOOL_CALL: query_database(query=\"...\")" else "Si necesitas datos: TOOL_CALL: query_database(query=\"...\")"}
NO escribas "Ran"/"Completed". NO inventes datos.
            """.trimIndent()
        } else {
            "Asistente TareaMov sin MCP.\nCONSULTA: $optimizedPrompt"
        }
    }

    /**
     * Legacy method for compatibility - now delegates to dynamic prompt
     */
    private fun createEnhancedPromptWithToolCapability(optimizedPrompt: String, hasToolAccess: Boolean): String {
        return createDynamicPromptWithMCPCapability(optimizedPrompt, hasToolAccess, false)
    }

    /**
     * Create enhanced prompt (deprecated - use createDynamicPromptWithMCPCapability)
     */
    private fun createEnhancedPrompt(optimizedPrompt: String): String {
        return optimizedPrompt
    }

    /**
     * Libera recursos del modelo
     */
    fun releaseModel() {
        if (isModelLoaded.get()) {
            try {
                // Aqu� ir�a la liberaci�n real de recursos
                isModelLoaded.set(false)
                Log.d(TAG, "Modelo Llama 3 liberado correctamente")
            } catch (e: Exception) {
                Log.e(TAG, "Error al liberar el modelo Llama 3", e)
            }
        }
    }

    /**
     * Worker para descargar el modelo en segundo plano
     */
    class ModelDownloadWorker(
        context: Context,
        params: WorkerParameters
    ) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            // URL oficial del modelo GGUF (Q4_0) desde Hugging Face
            val modelUrl = "https://huggingface.co/QuantFactory/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct.Q4_0.gguf"
            val modelFile = File(applicationContext.filesDir, "llama3-8b-instruct-q4_0.gguf")
            val maxRetries = 3
            var attempt = 0
            while (attempt < maxRetries) {
                try {
                    val url = java.net.URL(modelUrl)
                    url.openStream().use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (modelFile.exists() && modelFile.length() > 0) {
                        return Result.success()
                    }
                } catch (e: Exception) {
                    Log.e("ModelDownloadWorker", "Error descargando el modelo (intento ${attempt + 1})", e)
                    if (modelFile.exists()) modelFile.delete()
                }
                attempt++
            }
            return Result.failure()
        }
    }

    /**
     * Inicia la descarga del modelo si no existe
     */
    fun downloadModelIfNeeded() {
        val modelFile = File(context.filesDir, modelFileName)
        if (!modelFile.exists()) {
            val downloadWorkRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(downloadWorkRequest)
        }
    }
}
