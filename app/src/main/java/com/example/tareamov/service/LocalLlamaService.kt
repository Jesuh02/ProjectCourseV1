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
import com.example.tareamov.util.NetworkUtils

/**
 * Servicio para ejecutar Llama 3:8b localmente en el dispositivo Android
 */
class LocalLlamaService(private val context: Context) {
    private val TAG = "LocalLlamaService"
    private val isModelLoaded = AtomicBoolean(false)
    private val modelFileName = "llama3-8b-q4_0.gguf"

    companion object {
        /**
         * Obtiene URLs de Ollama dinámicamente basadas en la IP del dispositivo
         * Esta función debe ser llamada con un contexto válido
         */
        fun getFallbackLlamaUrls(context: Context): List<String> {
            return NetworkUtils.buildServerUrls(context, 11435)
        }
    }

    /**
     * Inicializa el modelo Llama 3:8b
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
            Log.d(TAG, "Simulando inicializaci�n del modelo Llama 3:8b")
            isModelLoaded.set(true)

            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar el modelo Llama 3:8b", e)
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
        maxToolIterations: Int = 5  // Reduced to 5 to prevent infinite loops/long waits
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
                
                // Try LLM (DeepSeek/Ollama) first, fallback to intelligent response
                val llmResponse = sendPromptToLLM(optimizedPrompt)
                return@withContext if (llmResponse != null && llmResponse.isNotBlank() && !llmResponse.startsWith("Error:")) {
                    Log.d(TAG, "✓ Got direct response from LLM (${llmResponse.length} chars)")
                    llmResponse
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
            
            // Add SQL generation guidance for data queries - ALWAYS include if MCP is available
            // Block removed to reduce noise and rely on createDynamicPromptWithMCPCapability
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
                
                // Try to connect to LLM (DeepSeek/Ollama) using enriched prompt
                val llmResponse = sendPromptToLLM(enrichedPrompt)
                val response = if (llmResponse != null && llmResponse.isNotBlank() && !llmResponse.startsWith("Error:")) {
                    Log.d(TAG, "✓ Got response from LLM (${llmResponse.length} chars)")
                    Log.d(TAG, "Response preview (first 300 chars): ${llmResponse.take(300)}")
                    llmResponse
                } else {
                    Log.w(TAG, "LLM not available; using intelligent fallback")
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
                    
                    // --- NUEVA LÓGICA DE AUTOCORRECCIÓN ---
                    if (toolResult.contains("❌ Error") || toolResult.contains("SQL Error")) {
                        Log.w(TAG, "⚠️ Tool execution failed. Feeding error back to LLM for correction.")
                        
                        enrichedPrompt = """
$enrichedPrompt

❌ ERROR AL EJECUTAR LA HERRAMIENTA ANTERIOR:
$toolResult

⚠️ INSTRUCCIONES DE CORRECCIÓN:
1. Analiza el mensaje de error SQL de arriba.
2. Corrige tu consulta SQL basándote en el error (ej: si falta una tabla, revisa el esquema; si falta un alias, agrégalo).
3. Vuelve a ejecutar la herramienta query_database con la consulta CORREGIDA.
4. NO te disculpes, solo ejecuta la herramienta corregida.
                        """.trimIndent()
                        
                        // Forzamos otra iteración para que intente de nuevo
                        iteration++
                        continue 
                    }
                    // ---------------------------------------
                    
                    // Update prompt with tool result and REQUEST ANALYSIS
                    enrichedPrompt = """
$enrichedPrompt

HERRAMIENTA EJECUTADA: ${toolCall.toolName}
ARGUMENTOS: ${toolCall.arguments}
RESULTADO DE LA BASE DE DATOS:
$toolResult

🎯 INSTRUCCIONES FINALES - MUY IMPORTANTE:
Ahora que tienes los datos REALES de la base de datos, proporciona una respuesta ARGUMENTADA Y NATURAL:

1. **Resumen Ejecutivo**: ¿Qué encontraste? (números clave)
2. **Análisis Detallado**: Explica qué significan estos datos
3. **Contexto**: ¿Por qué es importante? ¿Qué patrones observas?
4. **Insights**: Comparaciones, tendencias, observaciones relevantes
5. **Recomendaciones**: Acciones sugeridas basadas en los datos

⛔ NO DEVUELVAS JSON CRUDO
✅ USA LENGUAJE NATURAL Y CONVERSACIONAL
✅ EXPLICA, NO SOLO MUESTRES DATOS
✅ PROPORCIONA VALOR AGREGADO CON TU ANÁLISIS

Responde en español de forma clara y profesional.
                    """.trimIndent()
                    
                    iteration++
                } else {
                    // No tool call or no MCP client - validate response quality before accepting
                    if (isRawSnapshotResponse(response) && iteration + 1 < effectiveMaxToolIterations) {
                        Log.w(TAG, "LLM devolvió un snapshot sin análisis; reforzando instrucciones")
                        enrichedPrompt = reinforcePromptForAnalysis(enrichedPrompt, response)
                        iteration++
                        continue
                    }

                    finalResponse = response
                }
            }
            
            // Return final response with tool execution history if applicable
            val result = finalResponse ?: "⚠️ Error: Se alcanzó el límite de iteraciones de herramientas"
            
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
                // Return only the LLM's argumentative response
                // The response should already be natural and explanatory
                result
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
            // We use greedy matching (.*) for arguments to handle nested parentheses in SQL like COUNT(*)
            // We assume the tool call is on a single line or the main part of the response
            val patterns = listOf(
                // Use [\s\S] to match newlines in arguments (critical for SQL queries)
                Regex("""TOOL_CALL:\s*(\w+)\(([\s\S]*)\)""", RegexOption.IGNORE_CASE),
                Regex("""usar.*?herramienta.*?(\w+)\(\)""", RegexOption.IGNORE_CASE),
                Regex("""ejecutar.*?(\w+)\(""", RegexOption.IGNORE_CASE),
                Regex("""necesito.*?(\w+)\(""", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in patterns) {
                val match = pattern.find(response)
                if (match != null) {
                    val toolName = match.groupValues[1]
                    // For the first pattern, group 2 is the arguments string
                    // For others, it might not exist or be different, but our main target is TOOL_CALL
                    val argsString = if (match.groupValues.size > 2) match.groupValues[2] else ""
                    
                    Log.d(TAG, "🔍 Tool call detected: $toolName with args: '$argsString'")
                    
                    // Parse arguments robustly handling SQL with commas/quotes
                    val arguments = mutableMapOf<String, String>()
                    if (argsString.isNotBlank()) {
                        // Match key="value" or key='value'
                        // This regex captures the key and the value inside quotes
                        // We use [\s\S]*? to match content across lines (non-greedy)
                        val argPattern = Regex("""(\w+)\s*=\s*(["'])([\s\S]*?)\2""")
                        
                        var foundArgs = false
                        argPattern.findAll(argsString).forEach { argMatch ->
                            foundArgs = true
                            val key = argMatch.groupValues[1]
                            // groupValues[2] is the quote type
                            val value = argMatch.groupValues[3].trim()
                            arguments[key] = value
                            Log.d(TAG, "  👉 Argument: $key = $value")
                        }
                        
                        // Fallback: if no named arguments found, check if it's a single quoted string
                        // This handles cases where LLM outputs: TOOL_CALL: query_database("SELECT ...")
                        if (!foundArgs) {
                            val fallbackMatch = Regex("""^["']([\s\S]*)["']$""").find(argsString.trim())
                            if (fallbackMatch != null) {
                                val value = fallbackMatch.groupValues[1].trim()
                                // Default to "query" if tool is query_database, otherwise "default"
                                val key = if (toolName == "query_database") "query" else "default"
                                arguments[key] = value
                                Log.d(TAG, "  👉 Fallback Argument: $key = $value")
                            }
                        }
                    }
                    
                    return ToolCall(toolName, arguments)
                }
            }
            
            Log.d(TAG, "⚠️ No tool call pattern matched in response")
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
            Log.d(TAG, "🛠️ Executing MCP tool: ${toolCall.toolName}")
            
            val result = when (toolCall.toolName) {
                "query_database" -> {
                    val query = toolCall.arguments["query"] ?: return@withContext "⚠️ Error: falta el parámetro 'query'"
                    
                    Log.d(TAG, "🔍 Querying database: $query")
                    val queryResult = mcpClient.queryDatabase(query)

                    if (queryResult.success) {
                        val sql = queryResult.sqlScript ?: "N/A"
                        val formattedSummary = queryResult.formattedSummary?.let { truncateForPrompt(it, 3000) }
                        
                        // VS Code style: Detect if result is a generic snapshot when specific data was requested
                        val isGenericSnapshot = isGenericSnapshotResult(queryResult.data, query)
                        
                        // Check for explicit error in data
                        val dataObj = queryResult.data as? org.json.JSONObject
                        val isError = dataObj?.optBoolean("error") == true
                        
                        buildString {
                            if (isError) {
                                append("⚠️ Error en la ejecución SQL\n\n")
                            } else {
                                append("✅ Consulta ejecutada exitosamente\n\n")
                            }
                            append("**SQL generado:**\n")
                            append("```sql\n$sql\n```\n\n")
                            append("**Datos obtenidos:**\n")
                            append(formatMCPData(queryResult.data))
                            
                            if (!formattedSummary.isNullOrBlank()) {
                                append("\n\n**Análisis adicional:**\n")
                                append(formattedSummary)
                            }
                            
                            // VS Code behavior: If snapshot is generic but query was specific, suggest precise SQL
                            if (isGenericSnapshot) {
                                append("\n\n💡 **Nota:** El resultado es un snapshot genérico. ")
                                val preciseSql = generatePreciseSqlForQuery(query)
                                if (preciseSql != null) {
                                    append("Para obtener la fila exacta, ejecuta:\n\n")
                                    append("```sql\n$preciseSql\n```\n")
                                }
                            }
                        }.trim()
                    } else {
                        "❌ Error en la consulta: ${queryResult.error}"
                    }
                }

                "get_database_schema" -> {
                    Log.d(TAG, "📋 Getting database schema")
                    val schemaResult = mcpClient.getDatabaseSchema()

                    if (schemaResult.success) {
                        "✅ Esquema obtenido exitosamente:\n\n${schemaResult.schema}"
                    } else {
                        "❌ Error obteniendo esquema: ${schemaResult.error}"
                    }
                }

                else -> "⚠️ Error: Herramienta desconocida '${toolCall.toolName}'"
            }

            Log.d(TAG, "✅ Tool execution completed: ${toolCall.toolName}")
            return@withContext result
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error executing tool via MCP", e)
            return@withContext "❌ Error ejecutando herramienta ${toolCall.toolName}: ${e.message}"
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
                append(". IMPORTANTE: Devuélveme la fila exacta del usuario con LEFT JOIN a la tabla 'roles' para incluir el nombre del rol. ")
                append("Campos requeridos: usuarios.id, usuarios.usuario, usuarios.persona_id, usuarios.rol_id, roles.name AS rol_nombre, usuarios.created_at. ")
                append("NO devuelvas solo un snapshot genérico, ejecuta el JOIN específico.")
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
            "marketing", "growth", "ventas", "retencion", "retención", "estrategia", "funnel",
            "conversion", "conversión", "campana", "campaña", "churn", "retention"
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
            "hola", "hi", "hello", "buenos días", "buenas tardes",
            "qué puedes hacer", "ayuda", "help", "cómo funciona",
            "explica", "qué es", "gracias", "thanks"
        )
        
        // If it's a simple greeting/help, don't require data
        if (conversationalPatterns.any { lower.startsWith(it) || lower == it }) {
            return false
        }
        
        // Data query indicators - these ALWAYS need database access
        val dataIndicators = listOf(
            "cuántos", "cuantos", "how many", "count",
            "usuarios", "users", "cursos", "courses", "videos",
            "lista", "list", "dame", "give me", "show", "muestra",
            "todos", "all", "qué", "que", "what", "which",
            "estadística", "statistics", "análisis", "analysis",
            "nunca", "never", "sin", "without", "no tienen",
            "top", "mejor", "best", "más", "mas", "most",
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
                    val sb = StringBuilder()
                    sb.append("${data.length()} registro(s) encontrado(s):\n\n")
                    
                    val firstItem = data.optJSONObject(0)
                    if (firstItem != null) {
                        // Table Header
                        val keys = firstItem.keys().asSequence().take(6).toList()
                        sb.append("| ")
                        keys.forEach { sb.append("**${it.uppercase()}** | ") }
                        sb.appendLine()
                        
                        // Separator
                        sb.append("| ")
                        keys.forEach { sb.append(":--- | ") }
                        sb.appendLine()
                        
                        // Rows
                        for (i in 0 until minOf(data.length(), 50)) {
                            val item = data.getJSONObject(i)
                            sb.append("| ")
                            keys.forEach { key ->
                                val valStr = item.optString(key, "").replace("\n", " ").take(50)
                                sb.append("$valStr | ")
                            }
                            sb.appendLine()
                        }
                    }
                    
                    if (data.length() > 50) {
                        sb.append("\n  ... (${data.length() - 50} registros adicionales omitidos)")
                    }
                    sb.toString()
                }
            }
            is org.json.JSONObject -> {
                if (data.has("error") && data.optBoolean("error")) {
                    val msg = data.optString("message", "Error desconocido")
                    val hint = data.optString("hint", "")
                    buildString {
                        append("❌ ERROR DE EJECUCIÓN:\n$msg")
                        if (hint.isNotEmpty()) {
                            append("\n\n💡 SUGERENCIA:\n$hint")
                        }
                    }
                } else {
                    // Format single object as a vertical table or list
                    val sb = StringBuilder()
                    sb.append("| Campo | Valor |\n")
                    sb.append("| :--- | :--- |\n")
                    val keys = data.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val value = data.optString(key, "").replace("\n", " ").take(100)
                        sb.append("| **${key.uppercase()}** | $value |\n")
                    }
                    sb.toString()
                }
            }
            is List<*> -> {
                if (data.isEmpty()) {
                    "[]  (sin registros)"
                } else {
                    val sb = StringBuilder()
                    sb.append("${data.size} registro(s):\n\n")
                    
                    val firstItem = data.firstOrNull()
                    if (firstItem is Map<*, *>) {
                        // Table Header
                        val keys = firstItem.keys.map { it.toString() }.take(6)
                        sb.append("| ")
                        keys.forEach { sb.append("**${it.uppercase()}** | ") }
                        sb.appendLine()
                        
                        // Separator
                        sb.append("| ")
                        keys.forEach { sb.append(":--- | ") }
                        sb.appendLine()
                        
                        // Rows
                        data.take(50).forEach { item ->
                            if (item is Map<*, *>) {
                                sb.append("| ")
                                keys.forEach { key ->
                                    val valStr = item[key]?.toString()?.replace("\n", " ")?.take(50) ?: ""
                                    sb.append("$valStr | ")
                                }
                                sb.appendLine()
                            }
                        }
                    } else {
                        // Simple list
                        data.take(50).forEach { item ->
                            sb.append("- $item\n")
                        }
                    }
                    sb.toString()
                }
            }
            is Map<*, *> -> {
                if (data.isEmpty()) {
                    "{} (sin datos)"
                } else {
                    val sb = StringBuilder()
                    sb.append("| Campo | Valor |\n")
                    sb.append("| :--- | :--- |\n")
                    data.entries.forEach { (key, value) ->
                        val valStr = value?.toString()?.replace("\n", " ")?.take(100) ?: ""
                        sb.append("| **${key.toString().uppercase()}** | $valStr |\n")
                    }
                    sb.toString()
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
        // Disable hardcoded queries that use JOINs as they are not supported by the current MCP backend
        // The LLM will handle these using multiple simple queries as per the system prompt
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
            append("2. Asegúrate de que el modelo llama3:8b esté disponible\n")
            append("3. Comprueba la conexión de red entre el emulador/dispositivo y el servidor\n\n")
            append("**Direcciones probadas:**\n")
            getFallbackLlamaUrls(context).forEach { url ->
                append("- $url\n")
            }
            append("\n")
            append("Mientras tanto, puedes usar las herramientas MCP directamente desde el botón 🔧 ")
            append("para ejecutar consultas a la base de datos.\n")
        }
    }

    /**
     * Try to connect to DeepSeek API
     */
    private suspend fun tryDeepSeekConnection(prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            // Access BuildConfig via fully qualified name to avoid import issues
            val apiKey = com.example.tareamov.BuildConfig.DEEPSEEK_API_KEY
            if (apiKey.isBlank()) return@withContext null
            
            Log.d(TAG, "Trying DeepSeek API...")
            val url = java.net.URL("https://api.deepseek.com/chat/completions")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.connectTimeout = 30000
            connection.readTimeout = 60000
            connection.doOutput = true
            
            val requestBody = org.json.JSONObject().apply {
                put("model", "deepseek-chat")
                
                // Split prompt into system and user for better adherence to instructions
                val messagesArray = org.json.JSONArray()
                val splitMarker = "🎯 CONSULTA DEL USUARIO:"
                
                if (prompt.contains(splitMarker)) {
                    val parts = prompt.split(splitMarker, limit = 2)
                    // System message (Instructions)
                    messagesArray.put(org.json.JSONObject().apply {
                        put("role", "system")
                        put("content", parts[0].trim())
                    })
                    // User message (The actual query)
                    messagesArray.put(org.json.JSONObject().apply {
                        put("role", "user")
                        put("content", parts[1].trim())
                    })
                } else {
                    // Fallback to single user message
                    messagesArray.put(org.json.JSONObject().apply {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
                
                put("messages", messagesArray)
                put("temperature", 0.7)
                put("stream", false)
            }
            
            val writer = java.io.OutputStreamWriter(connection.outputStream)
            writer.write(requestBody.toString())
            writer.flush()
            writer.close()
            
            if (connection.responseCode == 200) {
                val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                val responseJson = org.json.JSONObject(reader.readText())
                val choices = responseJson.optJSONArray("choices")
                if (choices != null && choices.length() > 0) {
                    val content = choices.getJSONObject(0).optJSONObject("message")?.optString("content")
                    if (!content.isNullOrBlank()) {
                        Log.d(TAG, "✓ Got response from DeepSeek")
                        return@withContext content
                    }
                }
            } else {
                 Log.e(TAG, "DeepSeek error: ${connection.responseCode} ${connection.responseMessage}")
                 // Try to read error body
                 try {
                     val errorReader = java.io.BufferedReader(java.io.InputStreamReader(connection.errorStream))
                     Log.e(TAG, "DeepSeek error body: ${errorReader.readText()}")
                 } catch (e: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "DeepSeek connection failed", e)
        }
        return@withContext null
    }

    /**
     * Send prompt to available LLM (DeepSeek -> Ollama)
     */
    private suspend fun sendPromptToLLM(prompt: String): String? {
        // 1. Try DeepSeek API first (Fastest & Smartest)
        val deepSeekResponse = tryDeepSeekConnection(prompt)
        if (deepSeekResponse != null) return deepSeekResponse
        
        // 2. Fallback to local Ollama
        return tryLocalOllamaConnection(prompt)
    }

    /**
     * Try to connect to a local Ollama instance
     */
    private suspend fun tryLocalOllamaConnection(prompt: String): String? = withContext(Dispatchers.IO) {
        val fallbackUrls = getFallbackLlamaUrls(context)
        for (url in fallbackUrls) {
            try {
                Log.d(TAG, "  Trying local Ollama at: $url")
                
                val apiUrl = java.net.URL("$url/api/generate")
                val connection = apiUrl.openConnection() as java.net.HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 30000   // 30 seconds for connection
                connection.readTimeout = 60000      // 60 seconds for reading response
                connection.doOutput = true
                
                val requestBody = org.json.JSONObject().apply {
                    put("model", "llama3:8b")
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
     * Enhanced to emphasize SQL generation capabilities
     */
    private fun createDynamicPromptWithMCPCapability(optimizedPrompt: String, hasToolAccess: Boolean, requiresData: Boolean = false): String {
        return if (hasToolAccess) {
            """
Eres un analista de datos experto en SQL y PostgreSQL para la app TareaMov.
Tu objetivo es NO SOLO ejecutar consultas, sino ANALIZAR y EXPLICAR los resultados.

🔧 HERRAMIENTAS DISPONIBLES:
1. get_database_schema() - Obtiene el esquema completo de la base de datos. ÚSALO SIEMPRE al inicio si no conoces la estructura o nombres de tablas.
2. query_database(query="SQL") - Ejecuta consultas SQL en Supabase.

📋 ESQUEMA DE BASE DE DATOS (Resumen):
- usuarios: id, usuario, rol_id, persona_id, email, avatar
- personas: id, identificacion, nombres, apellidos, telefono
- courses: id, title, creator_user_id (FK a usuarios.id), description, price
- task_submissions: id, student_id (FK a usuarios.id), task_id, submission_date, grade
- subscriptions: subscriber_id, creator_id (sin columna id)
- videos: id, title, course_id, duration
- progreso_estudiante: usuario_estudiante (FK a usuarios.id), curso_id (FK a courses.id), certificado_emitido_en (TIMESTAMP), porcentaje_progreso

💡 REGLAS SQL:
1. ✅ USA JOINs para cruzar tablas (ej: usuarios JOIN personas ON usuarios.persona_id = personas.id).
2. ✅ USA WHERE para filtrar (ej: certificado_emitido_en IS NOT NULL).
3. ✅ USA agregaciones (COUNT, SUM, AVG) para análisis.
4. ⛔ NO inventes columnas. Usa solo las del esquema.
5. ⛔ NO devuelvas JSON crudo. SIEMPRE explica los resultados.
6. ✅ Para LISTAS (dame usuarios, lista de tareas, etc.), selecciona TODAS las columnas relevantes.

📝 FORMATO DE RESPUESTA:
Después de ejecutar la consulta, proporciona una respuesta ARGUMENTADA:

            **Para LISTAS de datos (usuarios, cursos, tareas, etc.):**
            OBLIGATORIO: Presenta los datos en TABLA MARKDOWN ESTÉTICA con este formato EXACTO:

            | **Columna 1** | **Columna 2** | **Columna 3** | **Columna 4** |
            | :--- | :--- | :--- | :--- |
            | valor1 | valor2 | valor3 | valor4 |

            REGLAS DE FORMATO:
            1. Headers en **negrita** (entre asteriscos dobles)
            2. Nombres de columna DESCRIPTIVOS en español (ej: "Nombre Usuario" no "usuario")
            3. Alineación izquierda con :---
            4. Sin espacios extra en celdas
            5. Máximo 20 filas, indicar "... y X más" si hay más

EJEMPLO:
| **ID** | **Nombre Usuario** | **Email** | **Rol** |
| :--- | :--- | :--- | :--- |
| 1 | admin | admin@example.com | Administrador |
| 2 | prueba | test@test.com | Usuario |

**Para ANÁLISIS:**
1. **Resumen**: ¿Qué encontraste? (en números)
2. **Análisis**: ¿Qué significa esto?
3. **Contexto**: ¿Por qué es importante?
4. **Insights**: Patrones, tendencias, comparaciones
5. **Recomendaciones**: Acciones sugeridas (si aplica)

EJEMPLO MALO:
[{"id":2,"usuario":"prueba"},{"id":3,"usuario":"prueba1"}]

EJEMPLO BUENO:
"Encontré 11 usuarios registrados en la plataforma TareaMov. De estos:
- 9 usuarios (82%) utilizan emails temporales, lo que indica cuentas de prueba o registros incompletos
- 2 usuarios (18%) tienen emails reales verificados: 'ambiental' y 'argelio'

**Análisis**: La mayoría de las cuentas son de prueba, lo que sugiere una fase de testing activa.

**Recomendación**: 
1. Implementar verificación de email obligatoria para cuentas productivas
2. Diferenciar claramente entre usuarios de prueba y usuarios reales en la base de datos
3. Considerar una campaña de onboarding para los 2 usuarios con emails reales"

🎯 CONSULTA DEL USUARIO:
$optimizedPrompt

${if (requiresData) "⚡ PRIMERO ejecuta query_database, LUEGO analiza y explica los resultados detalladamente." else "💭 Si necesitas datos, usa query_database y proporciona análisis completo."}
            """.trimIndent()
        } else {
            """
Asistente TareaMov (sin herramientas MCP disponibles).
Estás ejecutándote en los servidores seguros de TareaMov usando Llama 3:8b.

CONSULTA: $optimizedPrompt

⚠️ Sin acceso a herramientas MCP. Proporcionaré información general.
            """.trimIndent()
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
                Log.d(TAG, "Modelo Llama 3:8b liberado correctamente")
            } catch (e: Exception) {
                Log.e(TAG, "Error al liberar el modelo Llama 3:8b", e)
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
