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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP HTTP Client
 * Connects to the tareamov-mcp-server Node.js HTTP server
 * Implements JSON-RPC 2.0 protocol for MCP communication
 * 
 * Uses 10.0.2.2 for Android Emulator (maps to host's localhost)
 */
class MCPHttpClient(private val context: Context) {
    private val tag = "MCPHttpClient"
    private val requestId = AtomicInteger(0)
    private var isInitialized = false
    
    companion object {
        // Special IP for Android Emulator to access host machine's localhost
        private const val MCP_SERVER_URL = "http://10.0.2.2:3000"
        private const val CONNECT_TIMEOUT = 10L // seconds
        private const val READ_TIMEOUT = 60L // seconds
        private const val WRITE_TIMEOUT = 30L // seconds
    }
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
        .build()
    
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    
    data class MCPQueryResult(
        val success: Boolean,
        val data: Any?,
        val sqlScript: String?,
        val error: String?
    )
    
    /**
     * Initialize MCP server connection
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) {
                Log.d(tag, "MCP client already initialized")
                return@withContext true
            }
            
            Log.d(tag, "🚀 Connecting to MCP HTTP server at $MCP_SERVER_URL...")
            
            // Test health endpoint first
            val healthRequest = Request.Builder()
                .url("$MCP_SERVER_URL/health")
                .get()
                .build()
            
            val healthResponse = httpClient.newCall(healthRequest).execute()
            if (!healthResponse.isSuccessful) {
                Log.e(tag, "❌ MCP server health check failed: ${healthResponse.code}")
                return@withContext false
            }
            
            val healthBody = healthResponse.body?.string()
            Log.d(tag, "✅ MCP server is healthy: $healthBody")
            
            // Send initialize request
            val initRequest = buildJsonRpcRequest("initialize", JSONObject())
            val initRequestBody = Request.Builder()
                .url("$MCP_SERVER_URL/initialize")
                .post(initRequest.toString().toRequestBody(jsonMediaType))
                .build()
            
            val initResponse = httpClient.newCall(initRequestBody).execute()
            if (!initResponse.isSuccessful) {
                Log.e(tag, "❌ MCP initialize failed: ${initResponse.code}")
                return@withContext false
            }
            
            val initResult = initResponse.body?.string()
            Log.d(tag, "✅ MCP initialized: $initResult")
            
            isInitialized = true
            return@withContext true
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Failed to initialize MCP client", e)
            return@withContext false
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
            val requestBody = Request.Builder()
                .url("$MCP_SERVER_URL/tools/call")
                .post(request.toString().toRequestBody(jsonMediaType))
                .build()
            
            val response = httpClient.newCall(requestBody).execute()
            if (!response.isSuccessful) {
                Log.e(tag, "❌ Query failed with HTTP ${response.code}")
                return@withContext MCPQueryResult(
                    success = false,
                    data = null,
                    sqlScript = null,
                    error = "HTTP ${response.code}: ${response.message}"
                )
            }
            
            val responseBody = response.body?.string() ?: ""
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
            
            // Extract data and SQL script
            val data = mcpResult.opt("data")
            val sqlScript = mcpResult.optString("sql_script", null)
            
            Log.d(tag, "✅ Query successful, data type: ${data?.javaClass?.simpleName}")
            
            return@withContext MCPQueryResult(
                success = true,
                data = data,
                sqlScript = sqlScript,
                error = null
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
    suspend fun getDatabaseSchema(): String? = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                Log.w(tag, "MCP client not initialized")
                return@withContext null
            }
            
            Log.d(tag, "📋 Getting database schema")
            
            val params = JSONObject().apply {
                put("name", "get_database_schema")
                put("arguments", JSONObject())
            }
            
            val request = buildJsonRpcRequest("tools/call", params)
            val requestBody = Request.Builder()
                .url("$MCP_SERVER_URL/tools/call")
                .post(request.toString().toRequestBody(jsonMediaType))
                .build()
            
            val response = httpClient.newCall(requestBody).execute()
            if (!response.isSuccessful) {
                Log.e(tag, "❌ Schema request failed: ${response.code}")
                return@withContext null
            }
            
            val responseBody = response.body?.string() ?: return@withContext null
            val jsonResponse = JSONObject(responseBody)
            
            if (jsonResponse.has("error")) {
                return@withContext null
            }
            
            val result = jsonResponse.optJSONObject("result") ?: return@withContext null
            val content = result.optJSONArray("content") ?: return@withContext null
            
            if (content.length() > 0) {
                return@withContext content.getJSONObject(0).optString("text")
            }
            
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Schema error", e)
            return@withContext null
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
            val requestBody = Request.Builder()
                .url("$MCP_SERVER_URL/tools/list")
                .post(request.toString().toRequestBody(jsonMediaType))
                .build()
            
            val response = httpClient.newCall(requestBody).execute()
            if (!response.isSuccessful) {
                Log.e(tag, "❌ Tools list failed: ${response.code}")
                return@withContext emptyList()
            }
            
            val responseBody = response.body?.string() ?: return@withContext emptyList()
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
                Log.w(tag, "MCP client not initialized")
                return@withContext MCPQueryResult(
                    success = false,
                    data = null,
                    sqlScript = null,
                    error = "MCP server not available"
                )
            }
            
            Log.d(tag, "🔧 Executing tool: $toolName")
            
            val params = JSONObject().apply {
                put("name", toolName)
                put("arguments", arguments)
            }
            
            val request = buildJsonRpcRequest("tools/call", params)
            val requestBody = Request.Builder()
                .url("$MCP_SERVER_URL/tools/call")
                .post(request.toString().toRequestBody(jsonMediaType))
                .build()
            
            val response = httpClient.newCall(requestBody).execute()
            if (!response.isSuccessful) {
                return@withContext MCPQueryResult(
                    success = false,
                    data = null,
                    sqlScript = null,
                    error = "HTTP ${response.code}: ${response.message}"
                )
            }
            
            val responseBody = response.body?.string() ?: ""
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
                
                return@withContext MCPQueryResult(
                    success = true,
                    data = textContent,
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
