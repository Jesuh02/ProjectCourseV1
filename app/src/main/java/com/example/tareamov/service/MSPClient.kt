package com.example.tareamov.service

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.URL
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Client for interacting with the Model Serving Platform (MSP)
 * This class handles communication with Ollama or other LLM services
 */
class MSPClient(private val context: Context) {
    private val tag = "MSPClient"
    
    // Dynamic context cache for better performance
    private val contextCache = mutableMapOf<String, Pair<String, Long>>()
    private val cacheTimeoutMs = 5 * 60 * 1000L // 5 minutes
    
    // Lista de IPs posibles (ordenadas por prioridad)
    // Lista de IPs posibles (ordenadas por prioridad, incluyendo la IP de Wi-Fi y gateway de la última configuración)
    private val possibleBaseUrls = listOf(
        "http://192.168.1.224:11435",   // IP Wi-Fi actual (ipconfig)
        "http://192.168.1.254:11435",   // Gateway predeterminado (ipconfig)
        "http://192.168.1.17:11435",    // Anterior IP Wi-Fi
        "http://192.168.1.158:11435",   // Previous IP from ipconfig
        "http://localhost:11435",       // Localhost - High priority
        "http://127.0.0.1:11435",       // Loopback - High priority
        "http://0.0.0.0:11435",         // Bind address from Ollama logs
        "http://172.17.112.1:11435"     // WSL IP from ipconfig
    )
    private val emulatorUrl = "http://10.0.2.2:11435"
    private val modelName = "llama3"

    // Enhanced OkHttpClient with better timeout handling for large payloads
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)  // Increased read timeout for large responses
            .writeTimeout(60, TimeUnit.SECONDS)  // Increased write timeout for large requests
            .build()
    }

    private fun getBaseUrl(): String {
        if (isEmulator()) {
            Log.d(tag, "Using emulator URL: $emulatorUrl")
            return emulatorUrl
        }
        
        // Always try the current IP first (from ipconfig)
        if (isServerRunning("http://192.168.1.158:11435")) {
            Log.d(tag, "Connected to Ollama at current IP URL: http://192.168.1.158:11435")
            return "http://192.168.1.158:11435"
        }
        
        // Try each other URL in order of priority
        for (url in possibleBaseUrls.drop(1)) { // Skip the first one as we already tried it
            if (isServerRunning(url)) {
                Log.d(tag, "Connected to Ollama at URL: $url")
                return url
            }
        }
        
        // If none respond, use the current IP as fallback for future attempts
        Log.w(tag, "No Ollama server found, using current IP as fallback")
        return "http://192.168.1.158:11435"
    }

    // Improved server running check with better connection handling
    fun isServerRunning(urlToCheck: String? = null): Boolean {
        val url = urlToCheck ?: getBaseUrl()
        Log.d(tag, "Checking server status at: $url/api/tags")
        var connection: HttpURLConnection? = null
        
        return try {
            val urlObj = URL("$url/api/tags")
            connection = urlObj.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000  // Increased timeout for more reliable checking
            connection.readTimeout = 5000     // Increased timeout for more reliable checking
            connection.connect()
            
            val responseCode = connection.responseCode
            Log.d(tag, "Server check response code: $responseCode for $url")
            val isOk = responseCode == HttpURLConnection.HTTP_OK
            
            if (isOk) {
                Log.i(tag, "Successfully connected to Ollama server at $url")
            } else {
                Log.w(tag, "Server at $url returned non-OK response: $responseCode")
            }
            
            isOk
        } catch (e: Exception) {
            Log.e(tag, "Failed to connect to Ollama server at $url: ${e.message}")
            false
        } finally {
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                Log.e(tag, "Error disconnecting from $url", e)
            }
        }
    }

    private fun isEmulator(): Boolean {
        return (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")
                || Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.MANUFACTURER.contains("Genymotion")
                || Build.PRODUCT.contains("sdk_google")
                || Build.PRODUCT.contains("google_sdk")
                || Build.PRODUCT.contains("sdk")
                || Build.PRODUCT.contains("sdk_x86")
                || Build.PRODUCT.contains("sdk_gphone64_arm64")
                || Build.PRODUCT.contains("vbox86p")
                || Build.PRODUCT.contains("emulator")
                || Build.PRODUCT.contains("simulator"))
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
            SISTEMA EDUCATIVO TAREAMOV - CONSULTA DINÁMICA
            
            CONTEXTO RELEVANTE (recuperado dinámicamente):
            $dbContext
            
            CONSULTA DEL USUARIO:
            $prompt
            
            INSTRUCCIONES:
            - Usa solo la información del contexto proporcionado
            - Responde de forma concisa y precisa
            - Si necesitas más información, indícalo claramente
            """.trimIndent()
            
            Log.d(tag, "=== OLLAMA FULL PROMPT LOG ===")
            Log.d(tag, "Enhanced Prompt Size: ${fullPrompt.length} characters")
            Log.d(tag, "Enhanced Prompt Content:")
            Log.d(tag, fullPrompt)
            Log.d(tag, "============================")
            
            fullPrompt
        } else {
            Log.d(tag, "=== OLLAMA SIMPLE PROMPT LOG ===")
            Log.d(tag, "Simple Prompt Size: ${prompt.length} characters")
            Log.d(tag, "Simple Prompt Content: $prompt")
            Log.d(tag, "==============================")
            prompt
        }
    
        var currentBaseUrl = ""
        var response = ""
        var success = false
        var lastError: Exception? = null
    
        // Try each possible base URL until one works
        for (baseUrl in possibleBaseUrls) {
            currentBaseUrl = baseUrl
            try {
                Log.d(tag, "Trying to connect to $baseUrl...")
                
                // Construct the request URL
                val url = URL("$baseUrl/api/generate")
                val connection = url.openConnection() as HttpURLConnection
    
                // Set up the connection with increased timeouts
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 15000  // 15 seconds
                connection.readTimeout = 60000    // 60 seconds
                connection.doOutput = true
    
                // Create the request body
                val requestBody = JSONObject().apply {
                    put("model", modelName)
                    put("prompt", enhancedPrompt)
                    put("stream", false)
    
                    // Add options for context handling
                    val options = JSONObject().apply {
                        put("include_history", includeHistory)
                        put("include_database_context", false)  // We've already added it if needed
                    }
                    put("options", options)
                }
    
                try {
                    // Send the request
                    val outputStream = OutputStreamWriter(connection.outputStream)
                    outputStream.write(requestBody.toString())
                    outputStream.flush()
                    outputStream.close()
        
                    // Get the response
                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val responseJson = JSONObject(reader.readText())
                        response = responseJson.getString("response")
                        
                        Log.d(tag, "=== OLLAMA RESPONSE LOG ===")
                        Log.d(tag, "Server URL: $baseUrl")
                        Log.d(tag, "Response Code: $responseCode")
                        Log.d(tag, "Response Length: ${response.length} characters")
                        Log.d(tag, "Response Content: $response")
                        Log.d(tag, "=========================")
                        
                        success = true
                        connection.disconnect()
                        break
                    } else {
                        Log.e(tag, "Error response from $baseUrl: $responseCode")
                        // Try to read error message if available
                        try {
                            val errorReader = BufferedReader(InputStreamReader(connection.errorStream))
                            val errorResponse = errorReader.readText()
                            Log.e(tag, "Error details: $errorResponse")
                        } catch (e: Exception) {
                            Log.e(tag, "Could not read error details", e)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error sending request to $baseUrl", e)
                    lastError = e
                } finally {
                    connection.disconnect()
                }
            } catch (e: ConnectException) {
                Log.e(tag, "Failed to connect to $baseUrl", e)
                lastError = e
            } catch (e: Exception) {
                Log.e(tag, "Error sending prompt to $baseUrl", e)
                lastError = e
            }
        }
        
        if (!success) {
            Log.e(tag, "=== OLLAMA CONNECTION FAILED ===")
            Log.e(tag, "All base URLs failed", lastError)
            Log.e(tag, "Last error: ${lastError?.message}")
            Log.e(tag, "==============================")
            return@withContext "Error: No se pudo conectar al servidor LLM. ${lastError?.message ?: ""}"
        }
        
        Log.d(tag, "=== OLLAMA PROCESSING COMPLETE ===")
        Log.d(tag, "Final response delivered successfully")
        Log.d(tag, "Final response length: ${response.length} characters")
        Log.d(tag, "=================================")
        
        return@withContext response
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
        
        // Now send this truncated prompt
        var response = ""
        var success = false
        var lastError: Exception? = null
        
        // Try each possible base URL until one works
        for (baseUrl in possibleBaseUrls) {
            try {
                Log.d(tag, "Trying truncated prompt on $baseUrl...")
                val url = URL("$baseUrl/api/generate")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.doOutput = true
                
                val requestBody = JSONObject().apply {
                    put("model", modelName)
                    put("prompt", truncatedPrompt)
                    put("stream", false)
                    
                    val options = JSONObject().apply {
                        put("include_history", includeHistory)
                        put("include_database_context", false)
                    }
                    put("options", options)
                }
                
                try {
                    OutputStreamWriter(connection.outputStream).use { writer ->
                        writer.write(requestBody.toString())
                        writer.flush()
                    }
                    
                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val responseJson = JSONObject(reader.readText())
                        response = responseJson.optString("response", "")
                        
                        // Add a note about truncation
                        response = """
                            [Nota: Debido al tamaño de la consulta, se utilizó una versión resumida del contexto]
                            
                            $response
                        """.trimIndent()
                        
                        success = true
                        break
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error in request to $baseUrl: ${e.message}")
                    lastError = e
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e(tag, "Error sending truncated prompt to $baseUrl", e)
                lastError = e
            }
        }
        
        if (!success) {
            return@withContext "Error: La consulta es demasiado grande para procesar. Por favor, simplifica tu pregunta o especifica exactamente qué información necesitas. ${lastError?.message ?: ""}"
        }
        
        return@withContext response
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
     * Internal implementation of prompt sending with size validation
     * Used by preloadLocalModel for warmup and other internal operations
     */
    private suspend fun sendPromptInternal(prompt: String, isWarmup: Boolean = false): String = withContext(Dispatchers.IO) {
        // Don't log the full prompt if it's a warmup to avoid log spam
        if (isWarmup) {
            Log.d(tag, "Sending internal warmup prompt")
        } else {
            Log.d(tag, "Sending internal prompt: ${prompt.take(50)}...")
        }
        
        for (baseUrl in possibleBaseUrls) {
            try {
                Log.d(tag, "Trying internal prompt on $baseUrl...")
                val url = URL("$baseUrl/api/generate")
                val connection = url.openConnection() as HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 5000   // Shorter timeout for warmup
                connection.readTimeout = 10000     // Shorter timeout for warmup
                connection.doOutput = true
                
                val requestBody = JSONObject().apply {
                    put("model", modelName)
                    put("prompt", prompt)
                    put("stream", false)
                }
                
                try {
                    OutputStreamWriter(connection.outputStream).use { writer ->
                        writer.write(requestBody.toString())
                        writer.flush()
                    }
                    
                    val responseCode = connection.responseCode
                    if (responseCode == HttpURLConnection.HTTP_OK) {
                        val reader = BufferedReader(InputStreamReader(connection.inputStream))
                        val responseJson = JSONObject(reader.readText())
                        Log.d(tag, "Internal prompt successful on $baseUrl")
                        return@withContext responseJson.optString("response", "")
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error in internal request to $baseUrl: ${e.message}")
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                Log.e(tag, "Error sending internal prompt to $baseUrl: ${e.message}")
            }
        }
        
        return@withContext "Error: No se pudo conectar a ningún servidor disponible."
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
                        context.append("- ${sub.subscriberUsername} → ${sub.creatorUsername}\n")
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
        var response = ""
        var success = false
        var lastError: Exception? = null
        
        for (baseUrl in possibleBaseUrls) {
            if (success) break
            
            try {
                val urlObj = URL("$baseUrl/api/generate")
                val connection = urlObj.openConnection() as HttpURLConnection
                
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 20000
                connection.readTimeout = 120000
                
                val requestBody = JSONObject().apply {
                    put("model", modelName)
                    put("prompt", prompt)
                    put("stream", false)
                    put("options", JSONObject().apply {
                        put("temperature", 0.7)
                        put("max_tokens", 1024)
                    })
                }
                
                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(requestBody.toString())
                    writer.flush()
                }
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                        val responseText = reader.readText()
                        val jsonResponse = JSONObject(responseText)
                        response = jsonResponse.getString("response")
                        success = true
                    }
                }
                
            } catch (e: Exception) {
                lastError = e
            }
        }
        
        if (!success) {
            return "Error en optimización RAG: ${lastError?.message}"
        }
        
        return response
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
}