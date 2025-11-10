package com.example.tareamov.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP STDIO Client
 * Connects to the tareamov-mcp-server Node.js process via stdio
 * Implements JSON-RPC 2.0 protocol for MCP communication
 */
class MCPStdioClient(private val context: Context) {
    private val tag = "MCPStdioClient"
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val requestId = AtomicInteger(0)
    private var isInitialized = false
    
    companion object {
        private const val NODE_COMMAND = "node"
        private const val MCP_SCRIPT_PATH = "distribucion_de_contexto/src/mcp-stdio.js"
        private const val SUPABASE_URL = "https://vxuksizvwrkctrvpciyp.supabase.co"
        private const val SUPABASE_SERVICE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InZ4dWtzaXp2d3JrY3RydnBjaXlwIiwicm9sZSI6InNlcnZpY2Vfcm9sZSIsImlhdCI6MTc0OTM5ODgwMSwiZXhwIjoyMDY0OTc0ODAxfQ.I_y-ifs0hO5ur8IsSG6RLvFbuE2o3DcWlGwlNx2GFrU"
    }
    
    /**
     * Initialize MCP server connection
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) {
                Log.d(tag, "MCP client already initialized")
                return@withContext true
            }
            
            Log.d(tag, "🚀 Starting MCP server process...")
            
            // Get workspace path (assuming it's in the app's files directory or external storage)
            val workspacePath = context.filesDir.parent ?: context.filesDir.absolutePath
            val scriptPath = "$workspacePath/$MCP_SCRIPT_PATH"
            
            Log.d(tag, "Workspace path: $workspacePath")
            Log.d(tag, "Script path: $scriptPath")
            
            // Start Node.js process with MCP server
            val processBuilder = ProcessBuilder(
                NODE_COMMAND,
                scriptPath
            )
            
            // Set environment variables
            processBuilder.environment().apply {
                put("SUPABASE_URL", SUPABASE_URL)
                put("SUPABASE_SERVICE_KEY", SUPABASE_SERVICE_KEY)
                put("RAG_ENABLED", "true")
            }
            
            processBuilder.redirectErrorStream(false)
            process = processBuilder.start()
            
            // Setup streams
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            
            Log.d(tag, "✅ MCP server process started")
            
            // Send initialize request
            val initRequest = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId.incrementAndGet())
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", JSONObject())
                    put("clientInfo", JSONObject().apply {
                        put("name", "TareaMov-Android")
                        put("version", "1.0.0")
                    })
                })
            }
            
            val response = sendRequest(initRequest)
            
            if (response != null && !response.has("error")) {
                isInitialized = true
                Log.d(tag, "✅ MCP server initialized successfully")
                return@withContext true
            } else {
                Log.e(tag, "❌ MCP initialization failed: ${response?.optString("error")}")
                return@withContext false
            }
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Error initializing MCP client", e)
            return@withContext false
        }
    }
    
    /**
     * Query database using MCP query_database tool
     */
    suspend fun queryDatabase(query: String): MCPQueryResult = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                val initialized = initialize()
                if (!initialized) {
                    return@withContext MCPQueryResult(
                        success = false,
                        error = "MCP server not initialized"
                    )
                }
            }
            
            Log.d(tag, "📊 Querying database via MCP: $query")
            
            // Call tools/call with query_database tool
            val toolRequest = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId.incrementAndGet())
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", "query_database")
                    put("arguments", JSONObject().apply {
                        put("query", query)
                    })
                })
            }
            
            val response = sendRequest(toolRequest)
            
            if (response != null && !response.has("error")) {
                val result = response.optJSONObject("result")
                val content = result?.optJSONArray("content")
                
                if (content != null && content.length() > 0) {
                    val textContent = content.getJSONObject(0).optString("text")
                    
                    // Parse the response to extract data and SQL
                    val jsonData = JSONObject(textContent)
                    
                    val formattedSummary = jsonData.optString("formatted_summary", "").takeIf { it.isNotBlank() }
                    val metadata = jsonData.optJSONObject("metadata")

                    return@withContext MCPQueryResult(
                        success = true,
                        data = jsonData.opt("data"),
                        sqlScript = jsonData.optString("sql_script", null) ?: jsonData.optString("sql", null),
                        formattedSummary = formattedSummary,
                        metadata = metadata
                    )
                }
            }
            
            return@withContext MCPQueryResult(
                success = false,
                error = response?.optJSONObject("error")?.optString("message") ?: "Unknown error"
            )
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Error querying database via MCP", e)
            return@withContext MCPQueryResult(
                success = false,
                error = "Exception: ${e.message}"
            )
        }
    }
    
    /**
     * Get database schema using MCP get_database_schema tool
     */
    suspend fun getDatabaseSchema(): String? = withContext(Dispatchers.IO) {
        try {
            if (!isInitialized) {
                initialize()
            }
            
            Log.d(tag, "📋 Getting database schema via MCP")
            
            val toolRequest = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", requestId.incrementAndGet())
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", "get_database_schema")
                    put("arguments", JSONObject())
                })
            }
            
            val response = sendRequest(toolRequest)
            
            if (response != null && !response.has("error")) {
                val result = response.optJSONObject("result")
                val content = result?.optJSONArray("content")
                
                if (content != null && content.length() > 0) {
                    return@withContext content.getJSONObject(0).optString("text")
                }
            }
            
            return@withContext null
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Error getting schema via MCP", e)
            return@withContext null
        }
    }
    
    /**
     * Send JSON-RPC request and wait for response
     */
    private fun sendRequest(request: JSONObject): JSONObject? {
        try {
            val requestStr = request.toString()
            Log.d(tag, "→ Sending: ${requestStr.take(200)}")
            
            writer?.write(requestStr)
            writer?.newLine()
            writer?.flush()
            
            // Read response
            val responseLine = reader?.readLine()
            
            if (responseLine != null) {
                Log.d(tag, "← Received: ${responseLine.take(200)}")
                return JSONObject(responseLine)
            }
            
            return null
            
        } catch (e: Exception) {
            Log.e(tag, "❌ Error sending request", e)
            return null
        }
    }
    
    /**
     * Close MCP connection
     */
    fun close() {
        try {
            writer?.close()
            reader?.close()
            process?.destroy()
            isInitialized = false
            Log.d(tag, "✅ MCP client closed")
        } catch (e: Exception) {
            Log.e(tag, "Error closing MCP client", e)
        }
    }
    
    /**
     * Data class for query results
     */
    data class MCPQueryResult(
        val success: Boolean,
        val data: Any? = null,
        val sqlScript: String? = null,
        val error: String? = null,
        val formattedSummary: String? = null,
        val metadata: JSONObject? = null
    )
}
