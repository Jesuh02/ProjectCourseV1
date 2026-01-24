package com.example.tareamov.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.LinkedHashSet

/**
 * MCP HTTP Client
 * Connects to the tareamov-mcp-server Node.js HTTP server
 * Implements JSON-RPC 2.0 protocol for MCP communication
 * 
 * Uses 10.0.2.2 for Android Emulator (maps to host's localhost)
 */
class MCPHttpClient(private val context: Context) {
    private val tag = "MCPHttpClient"
    private val API_KEY = "tareamov-mcp-api-key-2025-secure"
    private val requestId = AtomicInteger(0)
    private var isInitialized = false
    @Volatile private var activeBaseUrl: String? = null
    
    fun getActiveBaseUrl(): String? {
        return activeBaseUrl
    }

    /**
     * Force a specific base URL for the MCP server (useful for manual LAN IP overrides)
     */
    fun setForcedBaseUrl(url: String) {
        // Normalize forced URL: ensure scheme and port (default MCP port 3000)
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        try {
            // If host does not include an explicit port, append default MCP port 3000
            // Do NOT append :3000 for HTTPS cloud URLs (they typically use 443)
            val u = java.net.URI(normalized)
            val hasPort = u.port != -1
            if (!hasPort) {
                val host = u.host ?: normalized.removePrefix("http://").removePrefix("https://")
                normalized = if (u.scheme == "https") {
                    // Keep https host without explicit port (use default 443)
                    "${u.scheme}://$host"
                } else {
                    // For http scheme assume local MCP uses port 3000
                    "${u.scheme}://$host:3000"
                }
            }
        } catch (e: Exception) {
            // Fallback: if parsing fails, ensure it at least has http:// prefix
        }
        activeBaseUrl = normalized
        // mark not initialized so next initialize() will try the forced URL
        isInitialized = false
        // Persist forced URL so resolver and other components can reuse it
        try {
            ServerEndpointResolver.setForcedMcpBaseUrl(context.applicationContext, activeBaseUrl)
        } catch (e: Exception) {
            Log.d(tag, "Failed to persist forced MCP URL: ${e.message}")
        }
        Log.i(tag, "Forced MCP base URL set to: $activeBaseUrl")
    }
    
    init {
        ServerEndpointResolver.initialize(context.applicationContext)
    }
    
    companion object {
        private const val CONNECT_TIMEOUT = 30L // seconds (increased)
        private const val READ_TIMEOUT = 180L // seconds (increased for complex LLM queries)
        private const val WRITE_TIMEOUT = 60L // seconds (increased)
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .build()
    
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun <T> withMcpBase(action: suspend (String) -> T?): T? {
        val tried = HashSet<String>()
        var lastException: Exception? = null

        // Simple emulator detection to avoid trying emulator-only hosts from physical devices
        fun runningOnEmulator(): Boolean {
            try {
                val fingerprint = android.os.Build.FINGERPRINT ?: ""
                val model = android.os.Build.MODEL ?: ""
                val brand = android.os.Build.BRAND ?: ""
                val product = android.os.Build.PRODUCT ?: ""
                return fingerprint.startsWith("generic") || fingerprint.startsWith("unknown") ||
                        model.contains("google_sdk") || model.contains("Emulator") ||
                        brand.startsWith("generic") || product.contains("sdk")
            } catch (t: Throwable) {
                return false
            }
        }

        suspend fun tryUrl(url: String): T? {
            if (url.isBlank() || !tried.add(url)) return null
            try {
                Log.d(tag, "Trying MCP connection to: $url")
                val result = action(url)
                if (result != null) {
                    if (url != activeBaseUrl) {
                        activeBaseUrl = url
                        Log.i(tag, "MCP connection established at: $url")
                    }
                    return result
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(tag, "MCP request failed on $url: ${e.message}")
            }
            return null
        }

        // Priority 1: Active/Last successful base URL (fastest if previously discovered)
        if (!activeBaseUrl.isNullOrBlank()) {
            val res = tryUrl(activeBaseUrl!!)
            if (res != null) return res
        }

        // Priority 2: Cached URL (Peek without blocking discovery)
        if (!activeBaseUrl.isNullOrBlank()) {
            val res = tryUrl(activeBaseUrl!!)
            if (res != null) return res
        }

        val cached = ServerEndpointResolver.peekMcpBaseUrl()
        if (!cached.isNullOrBlank()) {
            // Fast probe: perform a very short timeout health check to cached host
            try {
                if (!runningOnEmulator() && cached.contains("10.0.2.2")) {
                    Log.i(tag, "Skipping cached emulator host $cached on physical device")
                } else {
                    Log.d(tag, "Fast-probing cached MCP host: $cached")
                    val fastClient = OkHttpClient.Builder()
                        .connectTimeout(80, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .readTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()

                    var healthUrl = cached
                    if (cached.endsWith("/")) {
                        healthUrl = cached + "health"
                    } else {
                        healthUrl = "$cached/health"
                    }
                    val req = Request.Builder().url(healthUrl).get().header("X-API-Key", API_KEY).header("Connection", "close").build()

                        try {
                            var probeResult: T? = null
                            fastClient.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    Log.d(tag, "Fast probe success for $cached; promoting to activeBaseUrl")
                                    activeBaseUrl = cached
                                    probeResult = tryUrl(cached)
                                } else {
                                    Log.d(tag, "Fast probe failed for $cached -> ${resp.code}")
                                }
                            }
                            if (probeResult != null) return probeResult
                        } catch (e: Exception) {
                            Log.d(tag, "Fast probe exception for $cached: ${e.message}")
                        }
                }
            } catch (e: Exception) {
                Log.d(tag, "Cached probe error: ${e.message}")
            }
        }
        // Very-fast parallel resolve: ask resolver for the quickest candidate and try it (short bounded wait)
        try {
            // Perform a very short bounded fast-resolve; if it doesn't respond quickly, fallback to cloud
            val fastResolved = try {
                kotlinx.coroutines.withTimeoutOrNull(120) { ServerEndpointResolver.fastResolveMcpBaseUrl() }
            } catch (e: Exception) {
                null
            }

            if (!fastResolved.isNullOrBlank()) {
                Log.d(tag, "withMcpBase: fastResolve returned $fastResolved — attempting immediately")
                val resFast = tryUrl(fastResolved)
                if (resFast != null) return resFast
            } else {
                Log.d(tag, "withMcpBase: fastResolve did not return quickly; will continue with discovery/fallbacks")
            }
        } catch (e: Exception) {
            Log.d(tag, "fastResolve quick attempt failed: ${e.message}")
        }
        // Priority 3: Full Discovery (blocking) - ServerEndpointResolver prefers local hosts
        Log.i(tag, "Attempting full network discovery for MCP (local-first)...")
        val discovered = ServerEndpointResolver.getMcpBaseUrl(forceDiscovery = true)
        if (!discovered.isNullOrBlank()) {
            val resDiscovered = tryUrl(discovered)
            if (resDiscovered != null) return resDiscovered
        }

        // Priority 4: Local Emulator (Fallback for emulators only)
        // Try common emulator ports only if running on an emulator to avoid physical-device timeouts
        val hasLanIp = hasLocalLanIp()
        Log.d(tag, "Emulator fallback decision: runningOnEmulator=${runningOnEmulator()} hasLanIp=$hasLanIp")
        if (runningOnEmulator() && !hasLanIp) {
            val resEmulator1 = tryUrl("http://10.0.2.2:3001")
            if (resEmulator1 != null) return resEmulator1
            val resEmulator0 = tryUrl("http://10.0.2.2:3000")
            if (resEmulator0 != null) return resEmulator0
        } else {
            Log.d(tag, "Not running on emulator - skipping 10.0.2.2 fallbacks")
        }
        // Priority 5: Railway Cloud (last resort)
        if (ServerEndpointResolver.RAILWAY_MCP_URL.isNotBlank()) {
            val resCloud = tryUrl(ServerEndpointResolver.RAILWAY_MCP_URL)
            if (resCloud != null) return resCloud
        }

        if (lastException != null) {
            Log.e(tag, "All MCP connection attempts failed. Last error: ${lastException?.message}")
        }

        return null
    }

    private fun buildUrl(base: String, path: String): String {
        return "${base.trimEnd('/')}$path"
    }

    private fun hasLocalLanIp(): Boolean {
        return try {
            val nets = NetworkInterface.getNetworkInterfaces()
            for (netIf in nets) {
                if (!netIf.isUp || netIf.isLoopback) continue
                val addrs = netIf.inetAddresses
                for (addr in addrs) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        // ignore emulator special subnet 10.0.2.x
                        if (!host.startsWith("10.0.2.")) {
                            return true
                        }
                    }
                }
            }
            false
        } catch (e: Exception) {
            Log.d(tag, "hasLocalLanIp check failed: ${e.message}")
            false
        }
    }

    private suspend fun executeRpcRaw(path: String, payload: JSONObject): String? {
        return withMcpBase { base ->
            val fullUrl = buildUrl(base, path)
            try {
                Log.d(tag, "RPC POST url=$fullUrl payload=${payload.toString().take(1000)}")
            } catch (_: Exception) {}

            val request = Request.Builder()
                .url(fullUrl)
                .header("X-API-Key", API_KEY)
                .header("Connection", "close") // Forzar cierre de conexión
                .header("X-MCP-Client", "android")
                .header("X-MCP-Request-Id", payload.optString("id", "-1"))
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                Log.d(tag, "RPC response code=${response.code} bodyPreview=${body.take(600)}")
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${response.message}")
                }
                body
            }
        }
    }
    
    data class MCPQueryResult(
        val success: Boolean,
        val data: Any? = null,
        val sqlScript: String? = null,
        val error: String? = null,
        val formattedSummary: String? = null,
        val metadata: JSONObject? = null
    )
    
    data class MCPSchemaResult(
        val success: Boolean,
        val schema: String?,
        val error: String?
    )
    
    /**
     * Upload a file to the backend
     */
    suspend fun uploadFile(uri: android.net.Uri, filename: String, mimeType: String): String? = withContext(Dispatchers.IO) {
        return@withContext withMcpBase { base ->
            try {
                Log.d(tag, "📤 Uploading file: $filename to $base/upload")
                
                val contentResolver = context.contentResolver
                val inputStream = contentResolver.openInputStream(uri) ?: return@withMcpBase null
                val bytes = inputStream.readBytes()
                inputStream.close()
                
                val requestBody = okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("userId", "android_user") // Replace with actual user ID if available
                    .addFormDataPart(
                        "file",
                        filename,
                        okhttp3.RequestBody.create(mimeType.toMediaType(), bytes)
                    )
                    .build()
                
                val request = Request.Builder()
                    .url(buildUrl(base, "/upload"))
                    .post(requestBody)
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        Log.e(tag, "Upload failed: ${response.code} - $body")
                        return@withMcpBase null
                    }
                    
                    val json = JSONObject(body)
                    if (json.getBoolean("success")) {
                        val url = json.getString("url")
                        Log.d(tag, "✅ File uploaded: $url")
                        return@withMcpBase url
                    } else {
                        Log.e(tag, "Upload returned success=false: $body")
                        return@withMcpBase null
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Error uploading file", e)
                return@withMcpBase null
            }
        }
    }

    /**
     * Process prompt with attachments (files)
     * Calls /procesar-prompt directly
     */
    suspend fun processPromptWithAttachments(prompt: String, jsonContent: String, ollamaUrl: String, model: String): MCPQueryResult = withContext(Dispatchers.IO) {
        return@withContext withMcpBase { base ->
            try {
                Log.d(tag, "🤖 Processing prompt with attachments: $prompt")
                
                val payload = JSONObject().apply {
                    put("prompt", prompt)
                    put("jsonContent", jsonContent)
                    put("ollamaUrl", ollamaUrl)
                    put("model", model)
                }
                
                val request = Request.Builder()
                    .url(buildUrl(base, "/procesar-prompt"))
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        return@withMcpBase MCPQueryResult(
                            success = false,
                            error = "HTTP ${response.code}: ${response.message} - $body"
                        )
                    }
                    
                    val json = JSONObject(body)
                    val responseText = json.optString("respuesta_texto").orEmpty()

                    // If backend returned an empty respuesta_texto treat it as failure
                    if (responseText.isBlank()) {
                        Log.w(tag, "procesar-prompt returned empty respuesta_texto: $body")
                        return@withMcpBase MCPQueryResult(
                            success = false,
                            error = "Empty respuesta_texto from MCP server"
                        )
                    }

                    // Parse response if it's JSON string
                    val data = try {
                        JSONObject(responseText)
                    } catch (e: Exception) {
                        try {
                            JSONArray(responseText)
                        } catch (e2: Exception) {
                            responseText // Return raw string
                        }
                    }

                    return@withMcpBase MCPQueryResult(
                        success = true,
                        data = data
                    )
                }
            } catch (e: Exception) {
                Log.e(tag, "Error processing prompt with attachments", e)
                return@withMcpBase MCPQueryResult(
                    success = false,
                    error = e.message
                )
            }
        } ?: MCPQueryResult(success = false, error = "Failed to connect to MCP server")
    }

    /**
     * Initialize MCP server connection
     */
    suspend fun initialize(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && isInitialized) {
            Log.d(tag, "MCP client already initialized")
            return@withContext true
        }

        // Reset active URL if forcing re-initialization
        if (force) {
            activeBaseUrl = null
            isInitialized = false
        }

        // Quick fast-resolve: try to discover local backend very fast (200ms)
        try {
            val forced = try { ServerEndpointResolver.getForcedMcpBaseUrl() } catch (e: Exception) { null }
            if (!forced.isNullOrBlank()) {
                activeBaseUrl = forced
                Log.d(tag, "initialize: using forced base url: $activeBaseUrl")
            } else {
                val fast = try {
                    kotlinx.coroutines.withTimeoutOrNull(120) { ServerEndpointResolver.fastResolveMcpBaseUrl() }
                } catch (e: Exception) { null }
                if (!fast.isNullOrBlank()) {
                    activeBaseUrl = fast
                    Log.d(tag, "initialize: fast-resolved local backend: $fast")
                } else {
                    // Immediate fallback to cloud to avoid long waits on physical devices
                    activeBaseUrl = ServerEndpointResolver.RAILWAY_MCP_URL
                    Log.d(tag, "initialize: fast-resolve did not find local backend; falling back to cloud: $activeBaseUrl")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "initialize fast-resolve error: ${e.message}")
        }

        val result = withMcpBase { base ->
            Log.d(tag, "🚀 Connecting to MCP HTTP server at $base...")

            val healthRequest = Request.Builder()
                .url(buildUrl(base, "/health"))
                .header("Connection", "close") // Forzar cierre de conexión
                .get()
                .build()

            httpClient.newCall(healthRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Health check HTTP ${response.code}")
                }
                val body = response.body?.string()
                Log.d(tag, "✅ MCP server is healthy: $body")
            }

            val initPayload = buildJsonRpcRequest("initialize", JSONObject())
            val initRequest = Request.Builder()
                .url(buildUrl(base, "/initialize"))
                .header("Connection", "close") // Forzar cierre de conexión
                .post(initPayload.toString().toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(initRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Initialize HTTP ${response.code}")
                }
                val initResult = response.body?.string()
                Log.d(tag, "✅ MCP initialized: $initResult")
            }

            isInitialized = true
            true
        }

        return@withContext when (result) {
            true -> true
            else -> {
                Log.e(tag, "❌ Failed to initialize MCP client")
                false
            }
        }
    }
    
    /**
     * Query database using natural language
     */
    suspend fun queryDatabase(query: String): MCPQueryResult = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                Log.w(tag, "MCP client not initialized, attempting to initialize...")
                if (!initialize()) {
                    return@withContext MCPQueryResult(
                        success = false,
                        data = null,
                        sqlScript = null,
                        error = "MCP server not available"
                    )
                }
            }
            
            Log.d(tag, "🔍 Querying database: $query")
            
            // Build MCP tool call request
            val params = JSONObject().apply {
                put("name", "query_database")
                put("arguments", JSONObject().apply {
                    put("query", query)
                })
            }
            
            val request = buildJsonRpcRequest("tools/call", params)
            val responseBody = executeRpcRaw("/tools/call", request)
            if (responseBody == null) {
                Log.e(tag, "❌ No reachable MCP server for query_database")
                return@withContext MCPQueryResult(
                    success = false,
                    data = null,
                    sqlScript = null,
                    error = "MCP server not reachable (Check logs for connection errors)"
                )
            }

            Log.d(tag, "📦 MCP Response: ${responseBody.take(200)}...")

            val jsonResponse = JSONObject(responseBody)
            
            // Check for JSON-RPC error
            if (jsonResponse.has("error")) {
                val error = jsonResponse.getJSONObject("error")
                return@withContext MCPQueryResult(
                    success = false,
                    data = null,
                    sqlScript = null,
                    error = error.optString("message", "Unknown error")
                )
            }
            
            // Extract result from JSON-RPC response
            val result = jsonResponse.optJSONObject("result")
            if (result == null) {
                return@withContext MCPQueryResult(
                    success = false,
                    data = null,
                    sqlScript = null,
                    error = "Invalid response format"
                )
            }
            
            // Extract content array
            val content = result.optJSONArray("content")
            if (content == null || content.length() == 0) {
                return@withContext MCPQueryResult(
                    success = false,
                    data = null,
                    sqlScript = null,
                    error = "Empty response content"
                )
            }
            
            // Get the text content
            val textContent = content.getJSONObject(0).optString("text", "")
            val mcpResult = JSONObject(textContent)

            // Extract data and SQL script, handling null payloads gracefully
            val rawData = mcpResult.opt("data")
            var data: Any? = null
            if (rawData == null || rawData == JSONObject.NULL) {
                data = null
            } else {
                data = rawData
            }
            val sqlScript = mcpResult.optString("sql_script", null)?.takeIf { it.isNotBlank() }
            val metadata = mcpResult.optJSONObject("metadata")
            val formattedSummary = mcpResult.optString("formatted_summary", "").takeIf { it.isNotBlank() }
            val successFlag = metadata?.optBoolean("success", data != null) ?: (data != null)
            val errorMessage: String? = if (successFlag) {
                null
            } else {
                val metadataMessage = metadata?.optString("message")?.takeIf { it.isNotBlank() }
                val note = mcpResult.optString("note")
                val noteMessage = note.takeIf { it.isNotBlank() }
                metadataMessage ?: noteMessage ?: "No se recuperaron datos de Supabase para la consulta solicitada."
            }

            Log.d(tag, "✅ Query processed | success=$successFlag | dataType=${data?.javaClass?.simpleName ?: "null"}")

            return@withContext MCPQueryResult(
                success = successFlag,
                data = data,
                sqlScript = sqlScript,
                error = errorMessage,
                formattedSummary = formattedSummary,
                metadata = metadata
            )
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Query error", e)
            return@withContext MCPQueryResult(
                success = false,
                data = null,
                sqlScript = null,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Get database schema
     */
    suspend fun getDatabaseSchema(): MCPSchemaResult = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                Log.w(tag, "MCP client not initialized")
                return@withContext MCPSchemaResult(
                    success = false,
                    schema = null,
                    error = "MCP client not initialized"
                )
            }
            
            Log.d(tag, "📋 Getting database schema")
            
            val params = JSONObject().apply {
                put("name", "get_database_schema")
                put("arguments", JSONObject())
            }
            
            val request = buildJsonRpcRequest("tools/call", params)
            val responseBody = executeRpcRaw("/tools/call", request) ?: return@withContext MCPSchemaResult(
                success = false,
                schema = null,
                error = "MCP server not reachable"
            )
            val jsonResponse = JSONObject(responseBody)
            
            if (jsonResponse.has("error")) {
                val error = jsonResponse.getJSONObject("error")
                return@withContext MCPSchemaResult(
                    success = false,
                    schema = null,
                    error = error.optString("message", "Unknown error")
                )
            }
            
            val result = jsonResponse.optJSONObject("result") ?: return@withContext MCPSchemaResult(
                success = false,
                schema = null,
                error = "Invalid response format"
            )
            val content = result.optJSONArray("content") ?: return@withContext MCPSchemaResult(
                success = false,
                schema = null,
                error = "No content in response"
            )
            
            if (content.length() > 0) {
                val schema = content.getJSONObject(0).optString("text")
                return@withContext MCPSchemaResult(
                    success = true,
                    schema = schema,
                    error = null
                )
            }
            
            return@withContext MCPSchemaResult(
                success = false,
                schema = null,
                error = "Empty schema"
            )
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Schema error", e)
            return@withContext MCPSchemaResult(
                success = false,
                schema = null,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * List available MCP tools
     */
    suspend fun listTools(): List<com.example.tareamov.ui.model.MCPTool> = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                Log.w(tag, "MCP client not initialized, attempting to initialize...")
                if (!initialize()) {
                    return@withContext emptyList()
                }
            }
            
            Log.d(tag, "📋 Listing available tools")
            
            val request = buildJsonRpcRequest("tools/list", JSONObject())
            val responseBody = executeRpcRaw("/tools/list", request) ?: return@withContext emptyList()
            
            Log.d(tag, "📦 Tools response: ${responseBody.take(300)}...")
            
            val jsonResponse = JSONObject(responseBody)
            
            if (jsonResponse.has("error")) {
                Log.e(tag, "❌ Tools list error: ${jsonResponse.getJSONObject("error")}")
                return@withContext emptyList()
            }
            
            val result = jsonResponse.optJSONObject("result") ?: return@withContext emptyList()
            val toolsArray = result.optJSONArray("tools") ?: return@withContext emptyList()
            
            val tools = mutableListOf<com.example.tareamov.ui.model.MCPTool>()
            for (i in 0 until toolsArray.length()) {
                val toolJson = toolsArray.getJSONObject(i)
                val toolName = toolJson.getString("name")
                val inputSchema = toolJson.optJSONObject("inputSchema") ?: JSONObject()
                
                Log.d(tag, "🔧 Tool: $toolName")
                Log.d(tag, "  📋 Input Schema: $inputSchema")
                
                val tool = com.example.tareamov.ui.model.MCPTool(
                    name = toolName,
                    description = toolJson.optString("description", ""),
                    inputSchema = inputSchema
                )
                tools.add(tool)
            }
            
            Log.d(tag, "✅ Found ${tools.size} tools")
            return@withContext tools
            
        } catch (e: Exception) {
            Log.e(tag, "❌ List tools error", e)
            return@withContext emptyList()
        }
    }
    
    /**
     * Execute a specific tool with arguments
     */
    suspend fun executeTool(toolName: String, arguments: JSONObject): MCPQueryResult = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                Log.w(tag, "MCP client not initialized, attempting to initialize...")
                if (!initialize()) {
                    return@withContext MCPQueryResult(
                        success = false,
                        data = null,
                        sqlScript = null,
                        error = "MCP server not available"
                    )
                }
            }
            
            Log.d(tag, "🔧 Executing tool: $toolName")
            
            val params = JSONObject().apply {
                put("name", toolName)
                put("arguments", arguments)
            }
            
            val request = buildJsonRpcRequest("tools/call", params)
            val responseBody = executeRpcRaw("/tools/call", request) ?: return@withContext MCPQueryResult(
                success = false,
                data = null,
                error = "MCP server not reachable"
            )
            
            val jsonResponse = JSONObject(responseBody)
            
            if (jsonResponse.has("error")) {
                val error = jsonResponse.getJSONObject("error")
                return@withContext MCPQueryResult(
                    success = false,
                    data = null,
                    sqlScript = null,
                    error = error.optString("message", "Unknown error")
                )
            }
            
            val result = jsonResponse.optJSONObject("result")
            val content = result?.optJSONArray("content")
            
            if (content != null && content.length() > 0) {
                val textContent = content.getJSONObject(0).optString("text", "")

                // Try to parse returned text as JSON (object or array). If parsing fails, return raw string.
                val parsedData = try {
                    // Prefer JSONObject, then JSONArray
                    JSONObject(textContent)
                } catch (je: Exception) {
                    try {
                        JSONArray(textContent)
                    } catch (je2: Exception) {
                        // Not JSON, return raw text
                        textContent
                    }
                }

                return@withContext MCPQueryResult(
                    success = true,
                    data = parsedData,
                    sqlScript = null,
                    error = null
                )
            }
            
            return@withContext MCPQueryResult(
                success = false,
                data = null,
                sqlScript = null,
                error = "Empty response"
            )
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Tool execution error", e)
            return@withContext MCPQueryResult(
                success = false,
                data = null,
                sqlScript = null,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Build JSON-RPC 2.0 request
     */
    private fun buildJsonRpcRequest(method: String, params: JSONObject): JSONObject {
        return JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", requestId.incrementAndGet())
            put("method", method)
            put("params", params)
        }
    }
    
    /**
     * Close HTTP client
     */
    fun close() {
        try {
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
            isInitialized = false
            Log.d(tag, "MCP HTTP client closed")
        } catch (e: Exception) {
            Log.e(tag, "Error closing MCP client", e)
        }
    }
}
