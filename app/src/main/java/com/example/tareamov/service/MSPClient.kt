package com.example.tareamov.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import java.util.LinkedHashSet

/**
 * Client for interacting with the Model Serving Platform (MSP)
 * This class handles communication with Ollama or other LLM services
 */
class MSPClient(private val context: Context) {
    private val tag = "MSPClient"
    // Publicly available valid roles for the system
    companion object {
        val VALID_ROLES = listOf("usuario", "admin")
    }
    
    // Dynamic context cache for better performance
    private val contextCache = mutableMapOf<String, Pair<String, Long>>()
    private val cacheTimeoutMs = 5 * 60 * 1000L // 5 minutes
    private val modelName = "llama3"

    init {
        ServerEndpointResolver.initialize(context.applicationContext)
    }

    // Enhanced OkHttpClient with better timeout handling for large payloads
    // Increased timeouts to handle Ollama's model loading time (7+ seconds)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)      // Increased for initial connection
            .readTimeout(300, TimeUnit.SECONDS)        // 5 minutes for model loading + response generation
            .writeTimeout(120, TimeUnit.SECONDS)       // 2 minutes for large requests
            .callTimeout(360, TimeUnit.SECONDS)        // 6 minutes total call timeout
            .build()
    }

    private suspend fun resolveOllamaBaseUrl(forceDiscovery: Boolean = false): String? {
        return ServerEndpointResolver.getOllamaBaseUrl(forceDiscovery)
    }

    private suspend fun resolveMcpBaseUrl(forceDiscovery: Boolean = false): String? {
        return ServerEndpointResolver.getMcpBaseUrl(forceDiscovery)
    }

    private fun peekOllamaBaseUrl(): String? = ServerEndpointResolver.peekOllamaBaseUrl()
    private fun peekMcpBaseUrl(): String? = ServerEndpointResolver.peekMcpBaseUrl()

    suspend fun isServerRunning(urlToCheck: String? = null): Boolean {
        return if (!urlToCheck.isNullOrBlank()) {
            ServerEndpointResolver.isServiceReachable(urlToCheck, "/api/tags")
        } else {
            resolveOllamaBaseUrl() != null
        }
    }

    private fun performOllamaRequest(baseUrl: String, jsonPayload: String): String {
        val connection = (URL("$baseUrl/api/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 60000  // 60 seconds for connection
            readTimeout = 300000    // 300 seconds (5 minutes) for reading response
            doOutput = true
        }

        return try {
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonPayload)
                writer.flush()
            }

            val responseCode = connection.responseCode
            val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }

            if (responseCode in 200..299) {
                body ?: ""
            } else {
                val message = body?.takeIf { it.isNotBlank() } ?: "sin detalles"
                throw IOException("HTTP $responseCode desde $baseUrl: $message")
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun collectOllamaCandidates(includeDiagnostics: Boolean = true): LinkedHashSet<String> {
        val candidates = LinkedHashSet<String>()
        resolveOllamaBaseUrl()?.let { candidates.add(it) }
        resolveOllamaBaseUrl(forceDiscovery = true)?.let { candidates.add(it) }
        peekOllamaBaseUrl()?.let { candidates.add(it) }
        if (includeDiagnostics) {
            ServerEndpointResolver.collectOllamaDiagnostics().keys.forEach { candidates.add(it) }
        }
        return candidates
    }

    private suspend fun collectMcpCandidates(): LinkedHashSet<String> {
        val candidates = LinkedHashSet<String>()
        resolveMcpBaseUrl()?.let { candidates.add(it) }
        resolveMcpBaseUrl(forceDiscovery = true)?.let { candidates.add(it) }
        peekMcpBaseUrl()?.let { candidates.add(it) }
        ServerEndpointResolver.collectMcpDiagnostics().keys.forEach { candidates.add(it) }
        return candidates
    }

    /**
     * Run diagnostics across discovered endpoints for Ollama.
     */
    suspend fun testAllConnections(): Map<String, Boolean> {
        val results = ServerEndpointResolver.collectOllamaDiagnostics()
        Log.d(tag, "=== TESTING OLLAMA CONNECTIONS (${results.size}) ===")
        results.forEach { (endpoint, reachable) ->
            Log.d(tag, "  $endpoint: ${if (reachable) "✓" else "✗"}")
        }
        Log.d(tag, "========================================")
        return results
    }

    /**
     * Provide a human-readable status summary for the UI.
     */
    suspend fun getConnectionStatus(): String {
        val results = ServerEndpointResolver.collectOllamaDiagnostics()
        val reachable = results.filterValues { it }

        return if (reachable.isEmpty()) {
            """
                ❌ NO SE PUDO CONECTAR AL SERVIDOR OLLAMA

                Endpoints evaluados (${results.size}):
                ${results.entries.joinToString("\n") { "  • ${it.key}: ${if (it.value) "✓" else "✗"}" }}

                Sugerencias rápidas:
                1. Asegúrate de que Ollama esté ejecutándose en tu computadora
                2. Confirma que el dispositivo esté en la misma red local
                3. Revisa reglas de firewall/antivirus que bloqueen el puerto 11435
                4. Si usas un emulador, verifica que 10.0.2.2 sea accesible
            """.trimIndent()
        } else {
            """
                ✓ SERVIDOR OLLAMA CONECTADO

                Endpoints disponibles:
                ${reachable.keys.joinToString("\n") { "  ✓ $it" }}

                El modelo local debería responder con normalidad.
            """.trimIndent()
        }
    }

    suspend fun preloadLocalModel() = withContext(Dispatchers.IO) {
        try {
            val warmupPrompt = "Hola, responde con 'Modelo listo'."
            sendPromptInternal(warmupPrompt, isWarmup = true)
            Log.d(tag, "Local Llama 3 model preloaded/warmed up successfully")
        } catch (e: Exception) {
            Log.e(tag, "Failed to preload/warmup local Llama 3 model", e)
        }
    }

    /**
     * Send a prompt to the LLM and get a response
     */
    // Only ONE definition of sendPrompt should exist:
    suspend fun sendPrompt(
        prompt: String,
        includeHistory: Boolean = false,
        includeDatabaseContext: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        // Calculate prompt size and log
        val promptSize = prompt.length * 2  // Rough estimation of bytes
        Log.d(tag, "Prompt size: approximately ${promptSize / 1024}KB")
        
        // Check if prompt is too large
        val maxAllowedSize = 12 * 1024 * 1024  // 12MB limit (increased for RAG)
        if (promptSize > maxAllowedSize) {
            Log.w(tag, "Prompt too large for standard processing, using RAG optimization")
            return@withContext sendPromptWithRAGOptimization(prompt, includeHistory, includeDatabaseContext)
        }
        
        // For regular sized prompts, use dynamic database context based on query
        val dbContext = if (includeDatabaseContext) {
            val context = buildQuerySpecificContext(prompt)
            Log.d(tag, "=== OLLAMA CONTEXT LOG ===")
            Log.d(tag, "Original Query: $prompt")
            Log.d(tag, "Database Context Size: ${context.length} characters")
            Log.d(tag, "Database Context Content: $context")
            Log.d(tag, "========================")
            context
        } else {
            ""
        }
        
        val enhancedPrompt = if (includeDatabaseContext) {
            val fullPrompt = """
            SISTEMA EDUCATIVO CourseV - CONSULTA DINÁMICA
            
            ⚠️ REGLAS CRÍTICAS SOBRE ROLES DEL SISTEMA:
            - SOLO EXISTEN 2 ROLES VÁLIDOS: "usuario" y "admin"
            - NO menciones NUNCA otros roles como: profesor, docente, instructor, estudiante, moderador, etc.
            - Cuando pregunten por roles disponibles, responde ÚNICAMENTE: usuario, admin
            - Si preguntan por otros roles, aclara que NO EXISTEN en este sistema
            
            CONTEXTO RELEVANTE (recuperado dinámicamente):
            $dbContext
            
            CONSULTA DEL USUARIO:
            $prompt
            
            INSTRUCCIONES:
            - Usa solo la información del contexto proporcionado
            - Responde de forma concisa y precisa
            - RESPETA ESTRICTAMENTE las reglas de roles mencionadas arriba
            - Si necesitas más información, indícalo claramente
            """.trimIndent()
            
            Log.d(tag, "=== OLLAMA FULL PROMPT LOG ===")
            Log.d(tag, "Enhanced Prompt Size: ${fullPrompt.length} characters")
            Log.d(tag, "Enhanced Prompt Content:")
            Log.d(tag, fullPrompt)
            Log.d(tag, "============================")
            
            fullPrompt
        } else {
            val simplePrompt = """
⚠️ IMPORTANTE: En este sistema SOLO existen 2 roles: "usuario" y "admin". NO menciones otros roles.

$prompt
            """.trimIndent()
            
            Log.d(tag, "=== OLLAMA SIMPLE PROMPT LOG ===")
            Log.d(tag, "Simple Prompt Size: ${simplePrompt.length} characters")
            Log.d(tag, "Simple Prompt Content: $simplePrompt")
            Log.d(tag, "==============================")
            simplePrompt
        }
    
        val attemptedEndpoints = mutableListOf<String>()
        var lastError: Exception? = null

        // Try MCP bridge first if available
        resolveMcpBaseUrl()?.let { mcpUrl ->
            attemptedEndpoints.add("$mcpUrl/tools/call")
            try {
                val viaMcp = sendPromptViaMcp(mcpUrl, enhancedPrompt)
                if (!viaMcp.isNullOrEmpty()) {
                    Log.d(tag, "Prompt served via MCP bridge at $mcpUrl")
                    return@withContext viaMcp
                } else {
                    Log.d(tag, "MCP bridge returned empty response")
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(tag, "Failed sending prompt via MCP bridge", e)
            }
        }

        val requestPayload = JSONObject().apply {
            put("model", modelName)
            put("prompt", enhancedPrompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("include_history", includeHistory)
                put("include_database_context", false)
                put("num_predict", 512)        // Limit response tokens to prevent long generation times
                put("temperature", 0.7)        // Reasonable creativity balance
                put("top_k", 40)               // Sampling parameter
                put("top_p", 0.9)              // Nucleus sampling
            })
        }.toString()

        suspend fun tryDirect(baseUrl: String): String? {
            return try {
                val raw = performOllamaRequest(baseUrl, requestPayload)
                val responseJson = JSONObject(raw)
                val reply = responseJson.optString("response")

                if (reply.isNullOrBlank()) {
                    Log.w(tag, "Respuesta vacía desde $baseUrl")
                    null
                } else {
                    Log.d(tag, "=== OLLAMA RESPONSE LOG ===")
                    Log.d(tag, "Server URL: $baseUrl")
                    Log.d(tag, "Response Length: ${reply.length} characters")
                    Log.d(tag, "=========================")
                    reply
                }
            } catch (e: Exception) {
                lastError = e
                Log.e(tag, "Direct Ollama request failed for $baseUrl", e)
                null
            }
        }

        val primaryBase = resolveOllamaBaseUrl()
        primaryBase?.let { attemptedEndpoints.add("$it/api/generate") }
        val primaryResponse = primaryBase?.let { tryDirect(it) }
        if (!primaryResponse.isNullOrEmpty()) {
            Log.d(tag, "=== OLLAMA PROCESSING COMPLETE ===")
            Log.d(tag, "Final response length: ${primaryResponse.length} characters")
            Log.d(tag, "=================================")
            return@withContext primaryResponse
        }

        val fallbackBase = resolveOllamaBaseUrl(forceDiscovery = true)?.takeIf { it != primaryBase }
        fallbackBase?.let { attemptedEndpoints.add("$it/api/generate") }
        val fallbackResponse = fallbackBase?.let { tryDirect(it) }
        if (!fallbackResponse.isNullOrEmpty()) {
            Log.d(tag, "=== OLLAMA PROCESSING COMPLETE ===")
            Log.d(tag, "Final response length: ${fallbackResponse.length} characters")
            Log.d(tag, "=================================")
            return@withContext fallbackResponse
        }

        val diagnostics = ServerEndpointResolver.collectOllamaDiagnostics()
        val attemptSummary = if (attemptedEndpoints.isEmpty()) {
            "  • Sin endpoints detectados"
        } else {
            attemptedEndpoints.joinToString("\n") { "  • $it" }
        }

        val diagSummary = diagnostics.entries.joinToString("\n") { (endpoint, ok) ->
            "  • $endpoint -> ${if (ok) "✓" else "✗"}"
        }

        val detailMessage = lastError?.let { err ->
            when (err) {
                is ConnectException -> "No se pudo establecer conexión con el servidor"
                is java.net.SocketTimeoutException -> "Tiempo de espera agotado al solicitar respuesta"
                else -> err.message ?: "Error desconocido"
            }
        } ?: "No se detectó ningún servidor accesible"

        return@withContext """
            Error: No se pudo conectar al servidor LLM.

            Intentos realizados:
$attemptSummary

            Diagnóstico de red:
$diagSummary

            Detalles: $detailMessage

            Sugerencias:
            1. Asegúrate de que Ollama esté ejecutándose (ollama serve)
            2. Verifica que el dispositivo esté en la misma red que el servidor
            3. Revisa reglas de firewall o antivirus que bloqueen los puertos 11435 y 3000
        """.trimIndent()
    }

    // Helper: send prompt via MCP HTTP bridge (/tools/call). Returns response text or null.
    private suspend fun sendPromptViaMcp(mcpBase: String, prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = URL("${mcpBase.trimEnd('/')}/tools/call")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 8000
            conn.readTimeout = 60000
            conn.doOutput = true

            // JSON-RPC body calling tool 'query_database' as a passthrough for the prompt
            val rpc = JSONObject()
            rpc.put("jsonrpc", "2.0")
            rpc.put("id", 1)
            val params = JSONObject()
            params.put("name", "query_database")
            val args = JSONObject()
            args.put("query", prompt)
            params.put("arguments", args)
            rpc.put("params", params)

            val out = OutputStreamWriter(conn.outputStream)
            out.write(rpc.toString())
            out.flush()
            out.close()

            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val text = reader.readText()
                reader.close()
                // The MCP server wraps result.content[0].text
                try {
                    val obj = JSONObject(text)
                    val result = obj.optJSONObject("result")
                    if (result != null) {
                        val content = result.optJSONArray("content")
                        if (content != null && content.length() > 0) {
                            val first = content.getJSONObject(0)
                            return@withContext first.optString("text", null)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(tag, "Failed to parse MCP response JSON: ${e.message}")
                    return@withContext text
                }
            } else {
                Log.d(tag, "MCP bridge returned HTTP $code")
            }

            return@withContext null
        } catch (e: Exception) {
            Log.d(tag, "sendPromptViaMcp failed: ${e.message}")
            return@withContext null
        }
    }

    /**
     * Handles large prompts by truncating and summarizing
     */
    private suspend fun sendPromptWithTruncation(
        originalPrompt: String, 
        includeHistory: Boolean,
        includeDatabaseContext: Boolean
    ): String = withContext(Dispatchers.IO) {
        // Create a simplified database context
        val simplifiedContext = """
            La base de datos contiene tablas para usuarios, personas, videos, topics, tasks y subscriptions.
            El sistema es una plataforma educativa donde los usuarios pueden suscribirse a creadores
            y acceder a sus contenidos organizados en topics con tareas asociadas.
        """.trimIndent()
        
        // Create a shorter prompt
        val truncatedPrompt = """
            $simplifiedContext
            
            INSTRUCCIONES: Responde de manera concisa a la siguiente consulta basándote en tu conocimiento general
            sobre sistemas educativos y bases de datos con las tablas mencionadas.
            
            CONSULTA DEL USUARIO:
            ${originalPrompt.take(2000)}
        """.trimIndent()
        
        val payload = JSONObject().apply {
            put("model", modelName)
            put("prompt", truncatedPrompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("include_history", includeHistory)
                put("include_database_context", false)
            })
        }.toString()

        suspend fun attempt(baseUrl: String): String? {
            return try {
                val raw = performOllamaRequest(baseUrl, payload)
                val responseJson = JSONObject(raw)
                val reply = responseJson.optString("response")
                if (reply.isNullOrBlank()) {
                    null
                } else {
                    """
                        [Nota: Debido al tamaño de la consulta, se utilizó una versión resumida del contexto]

$reply
                    """.trimIndent()
                }
            } catch (e: Exception) {
                Log.e(tag, "Truncated prompt failed on $baseUrl", e)
                null
            }
        }

        val primaryBase = resolveOllamaBaseUrl()
        val primaryResponse = primaryBase?.let { attempt(it) }
        if (!primaryResponse.isNullOrBlank()) {
            return@withContext primaryResponse
        }

        val fallbackBase = resolveOllamaBaseUrl(forceDiscovery = true)?.takeIf { it != primaryBase }
        val fallbackResponse = fallbackBase?.let { attempt(it) }
        if (!fallbackResponse.isNullOrBlank()) {
            return@withContext fallbackResponse
        }

        val lastBase = fallbackBase ?: primaryBase ?: peekOllamaBaseUrl()
        val detail = if (lastBase == null) {
            "No se detectó ningún endpoint accesible."
        } else {
            "No se pudo obtener respuesta de $lastBase."
        }

        return@withContext "Error: La consulta es demasiado grande para procesar. $detail"
    }

    /**
     * Build dynamic and asynchronous database context using RAG principles
     * Always retrieves relevant data based on user query intent from the actual database
     */
    suspend fun buildDynamicDatabaseContext(userQuery: String = ""): String = withContext(Dispatchers.IO) {
        val ragService = com.example.tareamov.service.RAGDatabaseService(context)
        
        try {
            // Always use RAG service to analyze query and retrieve relevant data dynamically
            val queryToAnalyze = if (userQuery.isNotBlank()) {
                userQuery
            } else {
                // If no specific query, use a general overview query
                "muéstrame un resumen general de toda la información disponible en la base de datos"
            }
            
            Log.d(tag, "Building dynamic context for query: $queryToAnalyze")
            ragService.processRAGQuery(queryToAnalyze)
            
        } catch (e: Exception) {
            Log.e(tag, "Error building dynamic database context", e)
            buildFallbackContext()
        }
    }

    /**
     * Legacy method - now delegates to dynamic context builder
     */
    suspend fun buildDatabaseContext(): String = withContext(Dispatchers.IO) {
        return@withContext buildDynamicDatabaseContext()
    }

    /**
     * Fallback context when RAG system fails - now also dynamic
     */
    private suspend fun buildFallbackContext(): String = withContext(Dispatchers.IO) {
        val db = com.example.tareamov.data.AppDatabase.getDatabase(context)
        
        return@withContext try {
            // Even fallback tries to get real data from database
            val userCount = db.usuarioDao().getAllUsuarios().size
            val videoCount = db.videoDao().getAllVideos().size
            val topicCount = db.topicDao().getAllTopics().size
            
            """
            # SISTEMA EDUCATIVO TAREAMOV - DATOS EN TIEMPO REAL
            
            📊 ESTADÍSTICAS ACTUALES:
            - Usuarios registrados: $userCount
            - Videos disponibles: $videoCount  
            - Topics/Temas: $topicCount
            
            ⚠️ Sistema RAG temporalmente no disponible, pero datos reales recuperados de la base de datos.
            Haz preguntas específicas para obtener información detallada.
            """.trimIndent()
            
        } catch (e: Exception) {
            Log.e(tag, "Even fallback failed to get real data", e)
            """
            # SISTEMA EDUCATIVO TAREAMOV
            
            Sistema temporalmente no disponible.
            Por favor, intenta tu consulta más tarde.
            """.trimIndent()
        }
    }

    /**
     * Build query-specific context using RAG analysis
     * Only includes full database schema when explicitly requested
     */
    suspend fun buildQuerySpecificContext(query: String): String = withContext(Dispatchers.IO) {
        Log.d(tag, "=== RAG CONTEXT BUILDING LOG ===")
        Log.d(tag, "Input Query: $query")
        
        try {
            // Check if user is specifically asking for database schema/structure
            val isRequestingDatabaseInfo = isExplicitDatabaseSchemaRequest(query)
            
            Log.d(tag, "Is explicit database request: $isRequestingDatabaseInfo")
            
            // Use RAG service to get dynamic context based on the query
            val ragService = RAGDatabaseService(context)
            val ragResponse = ragService.processRAGQuery(query)
            
            Log.d(tag, "RAG Service Response Length: ${ragResponse.length} characters")
            Log.d(tag, "RAG Service Response Content: $ragResponse")
            
            val finalContext = if (isRequestingDatabaseInfo) {
                // Only when explicitly requested, include full schema
                val fullSchemaContext = buildComprehensiveDatabaseContext()
                """
# CONTEXTO COMPLETO DE BASE DE DATOS TAREAMOV

## ESQUEMA COMPLETO DE TODAS LAS TABLAS (14 TABLAS):

$fullSchemaContext

## DATOS ESPECÍFICOS PARA TU CONSULTA:
$ragResponse

## INSTRUCCIONES IMPORTANTES:
- La base de datos TareaMov tiene exactamente 14 tablas
- SIEMPRE usa la información del esquema completo mostrado arriba
- Cuando pregunten por "todas las tablas", lista las 14 tablas completas
- No limites las respuestas, muestra todos los datos disponibles
- Proporciona información detallada y completa
- ROLES DEL SISTEMA: Solo existen 2 roles válidos: "usuario" y "admin"
- NO menciones otros roles como "profesor", "docente", "instructor", "estudiante" - estos NO existen en el sistema

*Información obtenida dinámicamente desde la base de datos usando RAG*
                """.trimIndent()
            } else {
                // For regular queries, only include relevant RAG data
                """
# CONTEXTO ESPECÍFICO PARA TU CONSULTA

## DATOS RELEVANTES:
$ragResponse

## INSTRUCCIONES:
- Usa solo la información proporcionada para responder
- Si necesitas información adicional sobre la estructura de la base de datos, pídela específicamente
- Responde de manera concisa y precisa

*Información específica obtenida usando RAG*
                """.trimIndent()
            }
            
            Log.d(tag, "Final Context Length: ${finalContext.length} characters")
            Log.d(tag, "Final Context Content: $finalContext")
            Log.d(tag, "===============================")
            
            return@withContext finalContext
            
        } catch (e: Exception) {
            Log.e(tag, "Error in RAG context building", e)
            // In error case, only provide schema if explicitly requested
            val isRequestingDatabaseInfo = isExplicitDatabaseSchemaRequest(query)
            
            if (isRequestingDatabaseInfo) {
                val fallbackContext = buildComprehensiveDatabaseContext()
                return@withContext """
# CONTEXTO DE BASE DE DATOS (MODO EMERGENCIA)

$fallbackContext

## ERROR EN CONTEXTO DINÁMICO
Se produjo un error al obtener datos dinámicos de la base de datos.
Error: ${e.message}

Pero tienes acceso al esquema completo de las 14 tablas mostrado arriba.

*Usa el esquema para responder preguntas sobre la estructura de la base de datos*
                """.trimIndent()
            } else {
                return@withContext """
Error al procesar la consulta: ${e.message}

Si necesitas información específica sobre la estructura de la base de datos, 
puedes preguntar explícitamente: "¿Cuál es la estructura de la base de datos?" o "Muéstrame todas las tablas"
                """.trimIndent()
            }
        }
    }

    /**
     * Determines if the user is explicitly requesting database schema information
     */
    private fun isExplicitDatabaseSchemaRequest(query: String): Boolean {
        val queryLower = query.lowercase().trim()
        
        // Explicit requests for database structure/schema
        val explicitDatabaseKeywords = listOf(
            "estructura de la base de datos",
            "esquema de la base de datos", 
            "todas las tablas",
            "tablas de la base de datos",
            "muestra la base de datos",
            "muéstrame la base de datos",
            "información de la base de datos",
            "describe la base de datos",
            "qué tablas hay",
            "qué contiene la base de datos",
            "estructura completa",
            "esquema completo",
            "database schema",
            "show tables",
            "describe database",
            "table structure"
        )
        
        // Check for explicit database schema requests
        val hasExplicitRequest = explicitDatabaseKeywords.any { keyword ->
            queryLower.contains(keyword)
        }
        
        // Exclude casual mentions of "base de datos" in context
        val casualMentions = listOf(
            "consulta la base de datos",
            "busca en la base de datos", 
            "encuentra en la base de datos",
            "información de",
            "datos de",
            "registros de"
        )
        
        val isCasualMention = casualMentions.any { casual ->
            queryLower.contains(casual)
        } && !hasExplicitRequest
        
        Log.d(tag, "Query analysis - Explicit request: $hasExplicitRequest, Casual mention: $isCasualMention")
        
        return hasExplicitRequest && !isCasualMention
    }
    /**
     * Send prompt with dynamic database context based on query analysis
     */
    suspend fun sendPromptWithDatabaseContext(prompt: String): String {
        val dynamicContext = buildQuerySpecificContext(prompt)
        val fullPrompt = """
            SISTEMA TAREAMOV - CONSULTA CON CONTEXTO DINÁMICO
            
            CONTEXTO ESPECÍFICO PARA TU CONSULTA:
            $dynamicContext
            
            CONSULTA DEL USUARIO:
            $prompt
            
            INSTRUCCIONES:
            - Responde basándote únicamente en el contexto proporcionado arriba
            - Si necesitas información adicional que no está en el contexto, indícalo
            - Sé preciso y conciso en tu respuesta
        """.trimIndent()
        
        return sendPrompt(fullPrompt, includeHistory = false, includeDatabaseContext = false)
    }

    /**
     * Send prompt with RAG-enhanced dynamic context
     */
    suspend fun sendPromptWithRAGContext(prompt: String): String {
        return try {
            // First try with RAG service for optimal results
            val ragService = com.example.tareamov.service.RAGDatabaseService(context)
            val ragResult = ragService.processRAGQuery(prompt)
            
            if (ragResult.isNotBlank() && !ragResult.startsWith("Error")) {
                return ragResult
            }
            
            // Fallback to dynamic context method
            sendPromptWithDatabaseContext(prompt)
            
        } catch (e: Exception) {
            Log.e(tag, "Error in RAG-enhanced prompt", e)
            // Final fallback to basic prompt
            sendPrompt(prompt, includeHistory = false, includeDatabaseContext = true)
        }
    }

    /**
     * Send a prompt with MCP tool calling capability
     * The LLM can request to use MCP tools and receive their results
     */
    suspend fun sendPromptWithToolCalling(
        prompt: String,
        mcpHttpClient: MCPHttpClient?,
        includeHistory: Boolean = false,
        includeDatabaseContext: Boolean = false,
        maxToolIterations: Int = 3
    ): String = withContext(Dispatchers.IO) {
        if (mcpHttpClient == null) {
            // Fall back to regular prompt if no MCP client
            return@withContext sendPrompt(prompt, includeHistory, includeDatabaseContext)
        }

        // Build initial prompt with tool capability instructions
        val dbContext = if (includeDatabaseContext) {
            buildQuerySpecificContext(prompt)
        } else {
            ""
        }

        var enhancedPrompt = if (includeDatabaseContext) {
            """
Eres un asistente con acceso a herramientas para consultar una base de datos educativa.

⚠️ REGLAS: En este sistema SOLO existen 2 ROLES: "usuario" y "admin"

🛠️ HERRAMIENTAS DISPONIBLES:

1. get_database_schema() - Obtiene el esquema completo
   Uso: TOOL_CALL: get_database_schema()

2. query_database(query="consulta") - Ejecuta consultas
   Uso: TOOL_CALL: query_database(query="dame todos los usuarios")

CONTEXTO ACTUAL:
$dbContext

📋 CONSULTA DEL USUARIO:
$prompt

⚠️ INSTRUCCIONES:
1. Para BI/análisis/decisiones/KPIs: USA PRIMERO get_database_schema()
2. Para datos específicos: USA query_database(query="...")
3. Formato EXACTO: TOOL_CALL: herramienta(parametro="valor")
4. NO inventes datos, USA LAS HERRAMIENTAS

¿Qué herramienta necesitas?
            """.trimIndent()
        } else {
            """
Eres un asistente con acceso a herramientas de base de datos.

⚠️ SISTEMA: Solo 2 roles válidos: "usuario" y "admin"

🛠️ HERRAMIENTAS:
1. get_database_schema() - Obtiene esquema
2. query_database(query="consulta") - Consulta datos

📋 CONSULTA:
$prompt

FORMATO: TOOL_CALL: herramienta(parametro="valor")

¿Qué herramienta usarás?
            """.trimIndent()
        }

        // Tool calling loop
        var iteration = 0
        var finalResponse: String? = null
        val toolExecutionHistory = StringBuilder()

        while (iteration < maxToolIterations && finalResponse == null) {
            Log.d(tag, "🔄 MSPClient tool calling iteration ${iteration + 1}/$maxToolIterations")

            // Send prompt to Ollama
            val response = sendPromptInternal(enhancedPrompt, isWarmup = false)

            // Check if LLM wants to use a tool
            val toolCall = parseToolCall(response)

            if (toolCall != null) {
                Log.d(tag, "🛠️ MSPClient LLM requested tool: ${toolCall.first}")

                // Execute the tool via MCP
                val toolResult = executeToolViaMCP(toolCall, mcpHttpClient)
                toolExecutionHistory.append("\n\n---\n")
                toolExecutionHistory.append("TOOL: ${toolCall.first}\n")
                toolExecutionHistory.append("ARGUMENTS: ${toolCall.second}\n")
                toolExecutionHistory.append("RESULT: $toolResult\n")

                // Update prompt with tool result
                enhancedPrompt = """
$enhancedPrompt

HERRAMIENTA EJECUTADA: ${toolCall.first}
ARGUMENTOS: ${toolCall.second}
RESULTADO:
$toolResult

Con esta información, proporciona tu respuesta final al usuario.
Si necesitas usar otra herramienta, especifícalo usando TOOL_CALL.
                """.trimIndent()

                iteration++
            } else {
                // No tool call - this is the final response
                finalResponse = response
            }
        }

        // Return final response with tool execution history if applicable
        val result = finalResponse ?: "Error: Se alcanzó el límite de iteraciones de herramientas"

        return@withContext if (toolExecutionHistory.isNotEmpty()) {
            "$result\n\n--- Historial de ejecución de herramientas ---$toolExecutionHistory"
        } else {
            result
        }
    }

    /**
     * Parse tool call from LLM response
     * Format: TOOL_CALL: tool_name(arg1=value1, arg2=value2)
     */
    private fun parseToolCall(response: String): Pair<String, Map<String, String>>? {
        try {
            val toolCallPattern = Regex("""TOOL_CALL:\s*(\w+)\((.*?)\)""", RegexOption.IGNORE_CASE)
            val match = toolCallPattern.find(response) ?: return null

            val toolName = match.groupValues[1]
            val argsString = match.groupValues[2]

            // Parse arguments
            val arguments = mutableMapOf<String, String>()
            if (argsString.isNotBlank()) {
                val argPattern = Regex("""(\w+)=["']?([^,"']+)["']?""")
                argPattern.findAll(argsString).forEach { argMatch ->
                    val key = argMatch.groupValues[1]
                    val value = argMatch.groupValues[2]
                    arguments[key] = value
                }
            }

            return Pair(toolName, arguments)
        } catch (e: Exception) {
            Log.e(tag, "Error parsing tool call", e)
            return null
        }
    }

    /**
     * Execute tool via MCP HTTP client
     */
    private suspend fun executeToolViaMCP(
        toolCall: Pair<String, Map<String, String>>,
        mcpClient: MCPHttpClient
    ): String = withContext(Dispatchers.IO) {
        try {
            val (toolName, arguments) = toolCall
            return@withContext when (toolName.lowercase()) {
                "query_database" -> {
                    val query = arguments["query"] ?: return@withContext "Error: falta argumento 'query'"
                    val result = mcpClient.queryDatabase(query)

                    if (result.success) {
                        val data = result.data
                        val sql = result.sqlScript ?: "N/A"

                        """
Consulta ejecutada exitosamente.
SQL: $sql
Datos: ${formatMCPData(data)}
                        """.trimIndent()
                    } else {
                        "Error: ${result.error}"
                    }
                }

                "get_database_schema" -> {
                    val schema = mcpClient.getDatabaseSchema()

                    if (schema.success) {
                        "Esquema obtenido:\n${schema.schema}"
                    } else {
                        "Error: ${schema.error}"
                    }
                }

                else -> "Error: Herramienta desconocida '$toolName'"
            }
        } catch (e: Exception) {
            Log.e(tag, "Error executing tool via MCP", e)
            return@withContext "Error ejecutando herramienta: ${e.message}"
        }
    }

    /**
     * Format MCP data for LLM consumption
     */
    private fun formatMCPData(data: Any?): String {
        return when (data) {
            is JSONArray -> {
                val items = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    items.add(data.getJSONObject(i).toString())
                }
                items.joinToString("\n")
            }
            is JSONObject -> data.toString(2)
            is List<*> -> data.joinToString("\n")
            is Map<*, *> -> data.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            else -> data.toString()
        }
    }

    /**
     * Internal implementation of prompt sending with size validation
     * Used by preloadLocalModel for warmup and other internal operations
     */
    private suspend fun sendPromptInternal(prompt: String, isWarmup: Boolean = false): String = withContext(Dispatchers.IO) {
        if (isWarmup) {
            Log.d(tag, "Sending internal warmup prompt")
        } else {
            Log.d(tag, "Sending internal prompt: ${prompt.take(50)}...")
        }

        val payload = JSONObject().apply {
            put("model", modelName)
            put("prompt", prompt)
            put("stream", false)
        }.toString()

        val candidates = collectOllamaCandidates()

        for (candidate in candidates) {
            try {
                val raw = performOllamaRequest(candidate, payload)
                val response = JSONObject(raw).optString("response", "")
                if (response.isNotBlank()) {
                    Log.d(tag, "Internal prompt successful on $candidate")
                    return@withContext response
                }
            } catch (e: Exception) {
                Log.d(tag, "Internal prompt failed for $candidate", e)
            }
        }

        "Error: No se pudo conectar a ningún servidor disponible."
    }

    /**
     * Handles large prompts using RAG optimization techniques
     */
    private suspend fun sendPromptWithRAGOptimization(
        originalPrompt: String, 
        includeHistory: Boolean,
        includeDatabaseContext: Boolean
    ): String = withContext(Dispatchers.IO) {
        Log.d(tag, "Applying RAG optimization for large prompt")
        
        // Create a highly optimized context using RAG principles
        val optimizedContext = if (includeDatabaseContext) {
            buildRAGOptimizedContext(originalPrompt)
        } else {
            ""
        }
        
        // Extract key information from the original prompt
        val keyQuery = extractKeyQuery(originalPrompt)
        
        // Create optimized prompt with essential information only
        val optimizedPrompt = """
        SISTEMA EDUCATIVO TAREAMOV - Consulta Optimizada
        
        CONTEXTO RELEVANTE:
        $optimizedContext
        
        CONSULTA PRINCIPAL:
        $keyQuery
        
        INSTRUCCIONES:
        - Respuesta concisa y directa
        - Usar solo información del contexto
        - Máximo 500 palabras
        """.trimIndent()
        
        // Send optimized prompt
        return@withContext sendOptimizedPrompt(optimizedPrompt)
    }

    /**
     * Build RAG-optimized context based on query analysis (dynamic and async)
     */
    private suspend fun buildRAGOptimizedContext(query: String): String {
        val normalizedQuery = query.lowercase()
        val db = com.example.tareamov.data.AppDatabase.getDatabase(context)
        val context = StringBuilder()
        
        Log.d(tag, "Building RAG-optimized context for: $query")
        
        try {
            // Analyze query intent and extract only relevant data dynamically
            when {
                normalizedQuery.contains("usuario") || normalizedQuery.contains("user") -> {
                    context.append("USUARIOS: ")
                    val userCount = db.usuarioDao().getAllUsuarios().size
                    context.append("Total: $userCount\n")
                    
                    // Get specific user data if query is more specific
                    if (normalizedQuery.contains("@") || normalizedQuery.contains("email")) {
                        val users = db.usuarioDao().getAllUsuarios().take(10)
                        users.forEach { user ->
                            if (normalizedQuery.contains(user.usuario.lowercase())) {
                                context.append("Encontrado: ${user.usuario} (ID: ${user.id})\n")
                            }
                        }
                    }
                }
                
                normalizedQuery.contains("video") || normalizedQuery.contains("curso") -> {
                    context.append("VIDEOS/CURSOS: ")
                    val videoCount = db.videoDao().getAllVideos().size
                    context.append("Total: $videoCount\n")
                    
                    // Get recent or specific videos
                    if (normalizedQuery.contains("reciente") || normalizedQuery.contains("último")) {
                        val recentVideos = db.videoDao().getAllVideos()
                            .sortedByDescending { it.timestamp }
                            .take(5)
                        context.append("Videos recientes:\n")
                        recentVideos.forEach { video ->
                            context.append("- ${video.title} (${video.username})\n")
                        }
                    }
                    
                    // Get videos by specific creator if mentioned
                    val creatorMatch = """creador\s+(\w+)|de\s+(\w+)|por\s+(\w+)""".toRegex()
                        .find(normalizedQuery)
                    if (creatorMatch != null) {
                        val creator = creatorMatch.groupValues.first { it.isNotBlank() }
                        val creatorVideos = db.videoDao().getAllVideos()
                            .filter { it.username.contains(creator, ignoreCase = true) }
                            .take(5)
                        if (creatorVideos.isNotEmpty()) {
                            context.append("Videos de $creator:\n")
                            creatorVideos.forEach { video ->
                                context.append("- ${video.title}\n")
                            }
                        }
                    }
                }
                
                normalizedQuery.contains("tema") || normalizedQuery.contains("topic") -> {
                    context.append("TEMAS: ")
                    val topicCount = db.topicDao().getAllTopics().size
                    context.append("Total: $topicCount\n")
                    
                    // Get topics with most content
                    if (normalizedQuery.contains("más") || normalizedQuery.contains("mayor")) {
                        val topics = db.topicDao().getAllTopics().take(10)
                        val topicsWithTasks = topics.map { topic ->
                            val taskCount = db.taskDao().getAllTasks()
                                .count { it.topicId == topic.id }
                            topic to taskCount
                        }.sortedByDescending { it.second }.take(5)
                        
                        context.append("Temas con más contenido:\n")
                        topicsWithTasks.forEach { (topic, taskCount) ->
                            context.append("- ${topic.name}: $taskCount tareas\n")
                        }
                    }
                }
                
                normalizedQuery.contains("tarea") || normalizedQuery.contains("task") -> {
                    context.append("TAREAS: ")
                    val taskCount = db.taskDao().getAllTasks().size
                    context.append("Total: $taskCount tareas\n")
                    
                    // Get all tasks if asked
                    if (normalizedQuery.contains("pendiente") || normalizedQuery.contains("incompleta")) {
                        val allTasks = db.taskDao().getAllTasks()
                            .take(5)
                        context.append("Tareas disponibles:\n")
                        allTasks.forEach { task ->
                            context.append("- ${task.name} (Tema ID: ${task.topicId})\n")
                        }
                    }
                }
                
                normalizedQuery.contains("suscripción") || normalizedQuery.contains("subscription") -> {
                    context.append("SUSCRIPCIONES: ")
                    val subscriptionCount = db.subscriptionDao().getAllSubscriptions().size
                    context.append("Total: $subscriptionCount\n")
                    
                    // Get recent subscriptions
                    val recentSubs = db.subscriptionDao().getAllSubscriptions()
                        .sortedByDescending { it.subscriptionDate }
                        .take(5)
                    context.append("Suscripciones recientes:\n")
                    recentSubs.forEach { sub ->
                        context.append("- ${sub.subscriberId} → ${sub.creatorId}\n")
                    }
                }
                
                normalizedQuery.contains("curso") || normalizedQuery.contains("course") -> {
                    context.append("CURSOS: ")
                    val courses = db.courseDao().getAllCourses()
                    context.append("Total: ${courses.size}\n")
                    
                    if (courses.isNotEmpty()) {
                        val recentCourses = courses.take(3)
                        context.append("Cursos recientes:\n")
                        recentCourses.forEach { course ->
                            context.append("- Título: ${course.title}, Descripción: ${course.description ?: "Sin descripción"}\n")
                        }
                    }
                }
                
                // General statistics for count queries
                normalizedQuery.contains("cuántos") || normalizedQuery.contains("total") -> {
                    context.append("ESTADÍSTICAS GENERALES:\n")
                    context.append("- Usuarios: ${db.usuarioDao().getAllUsuarios().size}\n")
                    context.append("- Videos: ${db.videoDao().getAllVideos().size}\n")
                    context.append("- Temas: ${db.topicDao().getAllTopics().size}\n")
                    context.append("- Tareas: ${db.taskDao().getAllTasks().size}\n")
                    context.append("- Suscripciones: ${db.subscriptionDao().getAllSubscriptions().size}\n")
                }
            }
            
        } catch (e: Exception) {
            Log.e(tag, "Error building dynamic RAG context", e)
            context.clear()
            context.append("Error al acceder a datos específicos. Consulta general disponible.")
        }
        
        return context.toString().take(2048) // Limit context size for efficiency
    }

    /**
     * Extract key query from large prompt
     */
    private fun extractKeyQuery(prompt: String): String {
        val lines = prompt.split("\n")
        
        // Look for user query markers
        val queryMarkers = listOf("consulta", "query", "usuario:", "pregunta")
        
        for (line in lines) {
            for (marker in queryMarkers) {
                if (line.lowercase().contains(marker)) {
                    return line.substringAfter(":").trim().take(200)
                }
            }
        }
        
        // If no markers found, take the last non-empty line
        return lines.lastOrNull { it.trim().isNotEmpty() }?.take(200) ?: prompt.take(200)
    }

    /**
     * Send optimized prompt with better error handling
     */
    private suspend fun sendOptimizedPrompt(prompt: String): String {
        val payload = JSONObject().apply {
            put("model", modelName)
            put("prompt", prompt)
            put("stream", false)
            put("options", JSONObject().apply {
                put("temperature", 0.7)
                put("max_tokens", 1024)
            })
        }.toString()

        val candidates = collectOllamaCandidates()

        var lastError: Exception? = null

        for (candidate in candidates) {
            try {
                val raw = performOllamaRequest(candidate, payload)
                val response = JSONObject(raw).optString("response")
                if (!response.isNullOrBlank()) {
                    return response
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        return "Error en optimización RAG: ${lastError?.message ?: "No se encontraron endpoints accesibles"}"
    }

    /**
     * Build optimized database context for regular queries (lightweight and efficient)
     */
    private suspend fun buildOptimizedDatabaseContext(): String {
        return try {
            val db = com.example.tareamov.data.AppDatabase.getDatabase(context)
            
            buildString {
                append("ESQUEMA TAREAMOV (optimizado):\n")
                append("Sistema educativo con las siguientes entidades principales:\n\n")
                
                // Get only essential counts asynchronously
                val stats = withContext(Dispatchers.IO) {
                    mapOf(
                        "usuarios" to db.usuarioDao().getAllUsuarios().size,
                        "videos" to db.videoDao().getAllVideos().size,
                        "topics" to db.topicDao().getAllTopics().size,
                        "tasks" to db.taskDao().getAllTasks().size,
                        "subscriptions" to db.subscriptionDao().getAllSubscriptions().size
                    )
                }
                
                append("ESTADÍSTICAS ACTUALES:\n")
                stats.forEach { (table, count) ->
                    append("- $table: $count registros\n")
                }
                
                append("\nRELACIONES PRINCIPALES:\n")
                append("- Usuario ↔ Persona (datos personales)\n")
                append("- Topic → Tasks (organización de actividades)\n")
                append("- Video → Topics (contenido estructurado)\n")
                append("- Subscription: Usuario → Creador (seguimiento)\n")
                append("\n📌 Para consultas específicas, se recuperarán datos relevantes automáticamente.")
            }
        } catch (e: Exception) {
            Log.e(tag, "Error building optimized context", e)
            """
            ESQUEMA TAREAMOV:
            Base de datos educativa con tablas principales.
            Error al obtener estadísticas específicas.
            Consultas básicas disponibles.
            """.trimIndent()
        }
    }

    /**
     * Get real-time table statistics asynchronously
     */
    private suspend fun getTableStatistics(): Map<String, Int> = withContext(Dispatchers.IO) {
        val db = com.example.tareamov.data.AppDatabase.getDatabase(context)
        
        return@withContext try {
            mapOf(
                "usuarios" to db.usuarioDao().getAllUsuarios().size,
                "personas" to db.personaDao().getAllPersonasList().size,
                "videos" to db.videoDao().getAllVideos().size,
                "topics" to db.topicDao().getAllTopics().size,
                "tasks" to db.taskDao().getAllTasks().size,
                "subscriptions" to db.subscriptionDao().getAllSubscriptions().size,
                "courses" to db.courseDao().getAllCourses().size
            )
        } catch (e: Exception) {
            Log.e(tag, "Error getting table statistics", e)
            emptyMap()
        }
    }

    /**
     * Build context summary for analytics queries
     */
    suspend fun buildAnalyticsContext(): String = withContext(Dispatchers.IO) {
        val stats = getTableStatistics()
        
        return@withContext buildString {
            appendLine("📊 ANÁLISIS DEL SISTEMA TAREAMOV")
            appendLine()
            
            if (stats.isNotEmpty()) {
                appendLine("MÉTRICAS PRINCIPALES:")
                stats.forEach { (table, count) ->
                    val emoji = when (table) {
                        "usuarios" -> "👥"
                        "videos" -> "🎥"
                        "topics" -> "📚"
                        "tasks" -> "📝"
                        "subscriptions" -> "🔗"
                        "purchases" -> "💰"
                        "courses" -> "🎓"
                        else -> "📊"
                    }
                    appendLine("$emoji $table: $count")
                }
                
                // Calculate some basic insights
                val userCount = stats["usuarios"] ?: 0
                val videoCount = stats["videos"] ?: 0
                val subscriptionCount = stats["subscriptions"] ?: 0
                
                appendLine()
                appendLine("INSIGHTS RÁPIDOS:")
                if (userCount > 0 && videoCount > 0) {
                    val avgVideosPerUser = videoCount.toDouble() / userCount
                    appendLine("📈 Promedio de videos por usuario: %.1f".format(avgVideosPerUser))
                }
                
                if (userCount > 0 && subscriptionCount > 0) {
                    val avgSubsPerUser = subscriptionCount.toDouble() / userCount
                    appendLine("🔗 Promedio de suscripciones por usuario: %.1f".format(avgSubsPerUser))
                }
            } else {
                appendLine("Error al obtener métricas del sistema.")
            }
        }
    }

    /**
     * Get cached context or build new one if expired
     */
    private suspend fun getCachedContext(queryKey: String, builder: suspend () -> String): String {
        val now = System.currentTimeMillis()
        val cached = contextCache[queryKey]
        
        return if (cached != null && (now - cached.second) < cacheTimeoutMs) {
            Log.d(tag, "Using cached context for: $queryKey")
            cached.first
        } else {
            Log.d(tag, "Building fresh context for: $queryKey")
            val newContext = builder()
            contextCache[queryKey] = newContext to now
            
            // Clean old cache entries
            cleanExpiredCache()
            
            newContext
        }
    }

    /**
     * Clean expired cache entries
     */
    private fun cleanExpiredCache() {
        val now = System.currentTimeMillis()
        val iterator = contextCache.iterator()
        
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if ((now - entry.value.second) > cacheTimeoutMs) {
                iterator.remove()
            }
        }
    }

    /**
     * Clear all cached contexts (useful for testing or when data changes)
     */
    fun clearContextCache() {
        contextCache.clear()
        Log.d(tag, "Context cache cleared")
    }

    /**
     * Get cache statistics for debugging
     */
    fun getCacheStats(): String {
        val now = System.currentTimeMillis()
        val valid = contextCache.count { (now - it.value.second) < cacheTimeoutMs }
        val expired = contextCache.size - valid
        
        return "Cache: $valid valid, $expired expired entries"
    }

    /**
     * Build context with intelligent caching
     */
    suspend fun buildCachedQueryContext(query: String): String {
        val queryHash = query.lowercase().hashCode().toString()
        
        return getCachedContext("query_$queryHash") {
            buildQuerySpecificContext(query)
        }
    }

    /**
     * Build cached analytics context
     */
    suspend fun buildCachedAnalyticsContext(): String {
        return getCachedContext("analytics") {
            buildAnalyticsContext()
        }
    }

    /**
     * Get intelligent prompt with the best available context
     */
    suspend fun getIntelligentPrompt(userQuery: String): String {
        val normalizedQuery = userQuery.lowercase()
        
        return when {
            // For analytics queries, use cached analytics context
            normalizedQuery.contains("estadística") || normalizedQuery.contains("análisis") ||
            normalizedQuery.contains("cuántos") || normalizedQuery.contains("total") -> {
                val analyticsContext = buildCachedAnalyticsContext()
                """
                $analyticsContext
                
                CONSULTA ESPECÍFICA: $userQuery
                
                Responde basándote en las métricas mostradas arriba.
                """.trimIndent()
            }
            
            // For specific queries, use dynamic context
            else -> {
                val dynamicContext = buildCachedQueryContext(userQuery)
                """
                CONTEXTO DINÁMICO PARA TU CONSULTA:
                $dynamicContext
                
                CONSULTA: $userQuery
                
                Responde usando solo la información del contexto.
                """.trimIndent()
            }
        }
    }
    
    /**
     * Build comprehensive database context with ALL 14 tables
     */
    private suspend fun buildComprehensiveDatabaseContext(): String = withContext(Dispatchers.IO) {
          return@withContext try {
                val dbService = com.example.tareamov.service.DatabaseQueryService(context)
                val jsonStr = dbService.generateDatabaseJson()
                val json = org.json.JSONObject(jsonStr)

                // Safely extract counts and schema pieces
                val stats = json.optJSONObject("statistics")
                val schemaObj = json.optJSONObject("schema")

                val personasCount = stats?.optInt("total_personas") ?: json.optJSONArray("personas")?.length() ?: 0
                val usuariosCount = stats?.optInt("total_usuarios") ?: json.optJSONArray("usuarios")?.length() ?: 0
                val videosCount = stats?.optInt("total_videos") ?: json.optJSONArray("videos")?.length() ?: 0
                val topicsCount = stats?.optInt("total_topics") ?: json.optJSONArray("topics")?.length() ?: 0
                val contentItemsCount = stats?.optInt("total_content_items") ?: json.optJSONArray("contentItems")?.length() ?: 0
                val tasksCount = stats?.optInt("total_tasks") ?: json.optJSONArray("tasks")?.length() ?: 0
                val subscriptionsCount = stats?.optInt("total_subscriptions") ?: json.optJSONArray("subscriptions")?.length() ?: 0
                val taskSubmissionsCount = stats?.optInt("total_task_submissions") ?: json.optJSONArray("taskSubmissions")?.length() ?: 0
                val coursesCount = stats?.optInt("total_courses") ?: json.optJSONArray("courses")?.length() ?: 0
                val rolesCount = stats?.optInt("total_roles") ?: json.optJSONArray("roles")?.length() ?: 0
                val recursosCount = stats?.optInt("total_recursos") ?: json.optJSONArray("recursos")?.length() ?: 0
                val rolRecursosCount = stats?.optInt("total_rol_recursos") ?: json.optJSONArray("rolRecursos")?.length() ?: 0

                val chatMessagesCount = stats?.optInt("total_chat_messages") ?: json.optJSONArray("chatMessages")?.length() ?: 0
                val fileContextsCount = stats?.optInt("total_file_contexts") ?: json.optJSONArray("fileContexts")?.length() ?: 0

                """
1. PERSONAS ($personasCount registros)
    - Tabla: personas
    - Descripción: Información personal de usuarios del sistema
    - Campos principales: id, nombre, apellido, email, telefono, fecha_nacimiento
    - Función: Almacena datos personales de los usuarios

2. USUARIOS ($usuariosCount registros)
    - Tabla: usuarios
    - Descripción: Cuentas de usuario para autenticación
    - Campos principales: id, persona_id, username, password_hash, rol, fecha_creacion
    - Función: Gestiona acceso y autenticación al sistema
    - IMPORTANTE: El campo "rol" solo puede tener 2 valores válidos:
    * "usuario" - Usuarios estándar con permisos básicos
    * "admin" - Administradores con permisos completos
    - NO existen otros roles como "profesor", "docente", "instructor", "estudiante", etc.

⚠️ ROLES DEL SISTEMA - INFORMACIÓN CRÍTICA:
🔑 ÚNICAMENTE EXISTEN 2 ROLES: "usuario" y "admin"
❌ NO existen roles como: profesor, docente, instructor, estudiante, moderador, coordinador, etc.
✅ Si te preguntan por roles, menciona SOLAMENTE: usuario, admin

3. VIDEOS ($videosCount registros)
    - Tabla: videos
    - Descripción: Videos educativos y contenido multimedia
    - Campos principales: id, titulo, descripcion, url, duracion, creator_id, precio
    - Función: Almacena contenido audiovisual educativo

4. TOPICS ($topicsCount registros)
    - Tabla: topics
    - Descripción: Temas organizacionales para agrupar contenido
    - Campos principales: id, nombre, descripcion, creator_id, fecha_creacion
    - Función: Organiza contenido por temas/categorías

5. CONTENT_ITEMS ($contentItemsCount registros)
    - Tabla: content_items
    - Descripción: Elementos de contenido organizados por temas
    - Campos principales: id, titulo, descripcion, tipo, topic_id, orden
    - Función: Contenido específico dentro de cada tema

6. TASKS ($tasksCount registros)
    - Tabla: tasks
    - Descripción: Tareas asociadas a temas específicos
    - Campos principales: id, titulo, descripcion, topic_id, fecha_limite, tipo
    - Función: Actividades y ejercicios para los estudiantes

7. SUBSCRIPTIONS ($subscriptionsCount registros)
    - Tabla: subscriptions
    - Descripción: Relaciones de suscripción entre usuarios
    - Campos principales: id, follower_id, creator_id, fecha_suscripcion
    - Función: Gestiona seguimientos entre usuarios

8. TASK_SUBMISSIONS ($taskSubmissionsCount registros)
    - Tabla: task_submissions
    - Descripción: Entregas de tareas por parte de usuarios
    - Campos principales: id, task_id, usuario_id, respuesta, fecha_entrega, calificacion
    - Función: Almacena las respuestas de los estudiantes

9. CHAT_MESSAGES ($chatMessagesCount registros)
    - Tabla: chat_messages
    - Descripción: Mensajes del sistema de chat
    - Campos principales: id, usuario_id, mensaje, timestamp, tipo, calificacion
    - Función: Comunicación dentro de la plataforma

10. FILE_CONTEXTS ($fileContextsCount registros)
     - Tabla: file_contexts
     - Descripción: Contextos de archivos subidos al sistema
     - Campos principales: id, nombre_archivo, contenido_json, tipo_mime, usuario_id
     - Función: Gestiona archivos y documentos subidos

11. COURSES ($coursesCount registros)
     - Tabla: courses
     - Descripción: Cursos estructurados con contenido educativo
     - Campos principales: id, titulo, descripcion, creator_id, precio, fecha_creacion
     - Función: Organiza contenido en cursos completos

12. ROLES ($rolesCount registros)
     - Tabla: roles
     - Descripción: Roles y permisos del sistema
     - Campos principales: id, nombre, descripcion, nivel_acceso
     - Función: Define tipos de usuarios y sus permisos
     - ⚠️ ROLES VÁLIDOS EN EL SISTEMA (SOLO 2):
         * "usuario" (ID: 1) - Usuario estándar con permisos básicos
         * "admin" (ID: 2) - Administrador con permisos completos
     - ❌ NO existen otros roles como "profesor", "docente", "instructor", "estudiante", etc.
     - ✅ Cuando pregunten por roles, menciona ÚNICAMENTE: usuario, admin

13. RECURSOS ($recursosCount registros)
     - Tabla: recursos
     - Descripción: Recursos disponibles en el sistema
     - Campos principales: id, nombre, descripcion, tipo, url
     - Función: Herramientas y materiales adicionales

14. ROL_RECURSOS ($rolRecursosCount registros)
     - Tabla: rol_recursos
     - Descripción: Relación entre roles y recursos con permisos específicos
     - Campos principales: id, rol_id, recurso_id, puede_leer, puede_escribir, puede_eliminar
     - Función: Define qué recursos puede acceder cada rol

TOTAL DE TABLAS: 14
REGISTROS CONTABILIZADOS: ${personasCount + usuariosCount + videosCount + topicsCount + contentItemsCount + tasksCount + subscriptionsCount + taskSubmissionsCount + chatMessagesCount + fileContextsCount + coursesCount + rolesCount + recursosCount + rolRecursosCount}
                """.trimIndent()
            
        } catch (e: Exception) {
            Log.e(tag, "Error building comprehensive database context", e)
            """
BASE DE DATOS TAREAMOV - ESQUEMA COMPLETO (14 TABLAS):

1. PERSONAS - Información personal de usuarios
2. USUARIOS - Cuentas de usuario para autenticación  
3. VIDEOS - Videos educativos y contenido multimedia
4. TOPICS - Temas organizacionales para agrupar contenido
5. CONTENT_ITEMS - Elementos de contenido organizados por temas
6. TASKS - Tareas asociadas a temas específicos
7. SUBSCRIPTIONS - Relaciones de suscripción entre usuarios
8. TASK_SUBMISSIONS - Entregas de tareas por parte de usuarios
9. CHAT_MESSAGES - Mensajes del sistema de chat
10. FILE_CONTEXTS - Contextos de archivos subidos al sistema
11. COURSES - Cursos estructurados con contenido educativo
12. ROLES - Roles y permisos del sistema
13. RECURSOS - Recursos disponibles en el sistema
14. ROL_RECURSOS - Relación entre roles y recursos con permisos

NOTA: Error al obtener estadísticas en tiempo real, pero el esquema está disponible.
            """.trimIndent()
        }
    }
    
    // ==================== MCP OFFICIAL PROTOCOL METHODS ====================
    
    /**
     * MCP Protocol Version
     */
    private val MCP_PROTOCOL_VERSION = "2024-11-05"
    private var mcpInitialized = false
    private var mcpServerCapabilities: JSONObject? = null
    
    /**
     * Initialize MCP connection with server (JSON-RPC 2.0)
     * Must be called before using MCP tools
     */
    suspend fun initializeMCP(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(tag, "🔌 Initializing MCP connection...")
            
            for (mcpUrl in collectMcpCandidates()) {
                try {
                    val request = buildMCPRequest(
                        method = "initialize",
                        params = JSONObject().apply {
                            put("protocolVersion", MCP_PROTOCOL_VERSION)
                            put("capabilities", JSONObject().apply {
                                put("roots", JSONObject().apply {
                                    put("listChanged", false)
                                })
                                put("sampling", JSONObject())
                            })
                            put("clientInfo", JSONObject().apply {
                                put("name", "TareaMov-Android")
                                put("version", "1.0.0")
                            })
                        }
                    )
                    
                    val response = sendMCPRequest("${mcpUrl.trimEnd('/')}/initialize", request)
                    
                    if (!response.has("error")) {
                        val result = response.getJSONObject("result")
                        mcpServerCapabilities = result.optJSONObject("capabilities")
                        mcpInitialized = true
                        
                        Log.d(tag, "✅ MCP initialized successfully at $mcpUrl")
                        Log.d(tag, "   Protocol: ${result.optString("protocolVersion")}")
                        Log.d(tag, "   Server: ${result.optJSONObject("serverInfo")?.optString("name")}")
                        
                        return@withContext true
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to initialize MCP at $mcpUrl: ${e.message}")
                    continue
                }
            }
            
            Log.e(tag, "❌ Failed to initialize MCP on any server")
            return@withContext false
            
        } catch (e: Exception) {
            Log.e(tag, "❌ MCP initialization error", e)
            return@withContext false
        }
    }
    
    /**
     * List available MCP tools (JSON-RPC 2.0)
     */
    suspend fun listMCPTools(): List<MCPTool> = withContext(Dispatchers.IO) {
        try {
            if (!mcpInitialized) {
                Log.w(tag, "⚠️ MCP not initialized, attempting to initialize...")
                if (!initializeMCP()) {
                    return@withContext emptyList()
                }
            }
            
            Log.d(tag, "📋 Listing MCP tools...")
            
            val request = buildMCPRequest(
                method = "tools/list",
                params = JSONObject()
            )
            
            for (mcpUrl in collectMcpCandidates()) {
                try {
                    val response = sendMCPRequest("${mcpUrl.trimEnd('/')}/tools/list", request)
                    
                    if (!response.has("error")) {
                        val result = response.getJSONObject("result")
                        val toolsArray = result.getJSONArray("tools")
                        
                        val tools = mutableListOf<MCPTool>()
                        for (i in 0 until toolsArray.length()) {
                            val toolObj = toolsArray.getJSONObject(i)
                            tools.add(
                                MCPTool(
                                    name = toolObj.getString("name"),
                                    description = toolObj.optString("description", ""),
                                    inputSchema = toolObj.getJSONObject("inputSchema")
                                )
                            )
                        }
                        
                        Log.d(tag, "✅ Found ${tools.size} MCP tools")
                        return@withContext tools
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Failed to list tools at $mcpUrl: ${e.message}")
                    continue
                }
            }
            
            return@withContext emptyList()
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Error listing MCP tools", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * Call an MCP tool (JSON-RPC 2.0)
     */
    suspend fun callMCPTool(
        toolName: String,
        arguments: Map<String, Any> = emptyMap()
    ): MCPToolResult = withContext(Dispatchers.IO) {
        try {
            if (!mcpInitialized) {
                Log.w(tag, "⚠️ MCP not initialized, attempting to initialize...")
                if (!initializeMCP()) {
                    return@withContext MCPToolResult(
                        content = listOf(MCPContent(type = "text", text = "MCP not initialized")),
                        isError = true
                    )
                }
            }
            
            Log.d(tag, "🛠️ Calling MCP tool: $toolName")
            Log.d(tag, "   Arguments: $arguments")
            
            val request = buildMCPRequest(
                method = "tools/call",
                params = JSONObject().apply {
                    put("name", toolName)
                    put("arguments", JSONObject(arguments))
                }
            )
            
            for (mcpUrl in collectMcpCandidates()) {
                try {
                    val response = sendMCPRequest("${mcpUrl.trimEnd('/')}/tools/call", request)
                    
                    if (response.has("error")) {
                        val error = response.getJSONObject("error")
                        Log.e(tag, "❌ Tool call failed: ${error.optString("message")}")
                        continue
                    }
                    
                    val result = response.getJSONObject("result")
                    val contentArray = result.getJSONArray("content")
                    
                    val contents = mutableListOf<MCPContent>()
                    for (i in 0 until contentArray.length()) {
                        val contentObj = contentArray.getJSONObject(i)
                        contents.add(
                            MCPContent(
                                type = contentObj.getString("type"),
                                text = contentObj.optString("text", null),
                                data = contentObj.optString("data", null),
                                mimeType = contentObj.optString("mimeType", null)
                            )
                        )
                    }
                    
                    Log.d(tag, "✅ Tool executed successfully")
                    return@withContext MCPToolResult(content = contents, isError = false)
                    
                } catch (e: Exception) {
                    Log.w(tag, "Failed to call tool at $mcpUrl: ${e.message}")
                    continue
                }
            }
            
            return@withContext MCPToolResult(
                content = listOf(MCPContent(type = "text", text = "Failed to call tool on any MCP server")),
                isError = true
            )
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Error calling MCP tool", e)
            return@withContext MCPToolResult(
                content = listOf(MCPContent(type = "text", text = "Error: ${e.message}")),
                isError = true
            )
        }
    }
    
    /**
     * Get database schema using MCP
     */
    suspend fun getMCPDatabaseSchema(): String = withContext(Dispatchers.IO) {
        val result = callMCPTool("get_database_schema")
        if (result.isError) {
            return@withContext result.content.firstOrNull()?.text ?: "Error getting schema"
        }
        result.content.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }
    }
    
    /**
     * Query database using MCP
     */
    suspend fun queryMCPDatabase(query: String): String = withContext(Dispatchers.IO) {
        val result = callMCPTool("query_database", mapOf("query" to query))
        if (result.isError) {
            return@withContext result.content.firstOrNull()?.text ?: "Error querying database"
        }
        result.content.filter { it.type == "text" }.joinToString("\n") { it.text ?: "" }
    }
    
    // ==================== MCP Helper Methods ====================
    
    /**
     * Build JSON-RPC 2.0 request for MCP
     */
    private fun buildMCPRequest(method: String, params: JSONObject): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis())
            put("method", method)
            put("params", params)
        }
    }
    
    /**
     * Send JSON-RPC request to MCP server
     */
    private fun sendMCPRequest(mcpUrl: String, jsonRpcRequest: JSONObject): JSONObject {
        val url = URL(mcpUrl)
        val conn = url.openConnection() as HttpURLConnection
        
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.doInput = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            
            // Send request
            OutputStreamWriter(conn.outputStream).use { writer ->
                writer.write(jsonRpcRequest.toString())
                writer.flush()
            }
            
            // Read response
            val responseCode = conn.responseCode
            val responseBody = if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            } else {
                BufferedReader(InputStreamReader(conn.errorStream)).use { it.readText() }
            }
            
            return JSONObject(responseBody)
            
        } finally {
            conn.disconnect()
        }
    }
    
    /**
     * MCP Tool definition
     */
    data class MCPTool(
        val name: String,
        val description: String,
        val inputSchema: JSONObject
    )
    
    /**
     * MCP Tool result
     */
    data class MCPToolResult(
        val content: List<MCPContent>,
        val isError: Boolean = false
    )
    
    /**
     * MCP Content (text, image, resource, etc.)
     */
    data class MCPContent(
        val type: String,
        val text: String? = null,
        val data: String? = null,
        val mimeType: String? = null
    )
}