package com.example.tareamov.service

import android.content.Context
import android.util.Log
import com.example.tareamov.data.repository.SupabaseRepository
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP Tool Service - Model Context Protocol Tools Implementation
 * 
 * This service implements the MCP protocol similar to VS Code's MCP extension.
 * It provides tools that the LLM can use to query the Supabase database directly.
 * 
 * Features:
 * - Tool discovery (list available tools)
 * - Tool execution (execute queries on Supabase)
 * - Real-time query results display
 * - SQL script visibility
 */
class MCPToolService(private val context: Context) {
    
    private val tag = "MCPToolService"
    private val supabaseRepo = SupabaseRepository()
    private val gson = Gson()
    
    companion object {
        const val TOOL_QUERY_DATABASE = "query_database"
        const val TOOL_GET_SCHEMA = "get_database_schema"
        const val TOOL_EXECUTE_SQL = "execute_sql"
        const val TOOL_GET_TABLE_DATA = "get_table_data"
    }
    
    /**
     * Tool definition similar to MCP tools in VS Code
     */
    data class MCPTool(
        val name: String,
        val description: String,
        val inputSchema: Map<String, Any>
    )
    
    /**
     * Tool execution result
     */
    data class MCPToolResult(
        val success: Boolean,
        val data: Any?,
        val sqlScript: String? = null,
        val error: String? = null,
        val metadata: Map<String, Any>? = null
    )
    
    /**
     * Get list of available MCP tools
     */
    fun getAvailableTools(): List<MCPTool> {
        return listOf(
            MCPTool(
                name = TOOL_QUERY_DATABASE,
                description = "Execute natural language queries on the TareaMov Supabase database. Returns real-time data.",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf(
                            "type" to "string",
                            "description" to "Natural language query in Spanish or English"
                        )
                    ),
                    "required" to listOf("query")
                )
            ),
            MCPTool(
                name = TOOL_GET_SCHEMA,
                description = "Get the complete database schema with all tables, columns, and relationships",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>()
                )
            ),
            MCPTool(
                name = TOOL_EXECUTE_SQL,
                description = "Execute a SQL query directly on Supabase and return results",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "sql" to mapOf(
                            "type" to "string",
                            "description" to "SQL query to execute"
                        ),
                        "params" to mapOf(
                            "type" to "array",
                            "description" to "Optional parameters for prepared statement"
                        )
                    ),
                    "required" to listOf("sql")
                )
            ),
            MCPTool(
                name = TOOL_GET_TABLE_DATA,
                description = "Get all data from a specific table in Supabase",
                inputSchema = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "tableName" to mapOf(
                            "type" to "string",
                            "description" to "Name of the table to query"
                        ),
                        "limit" to mapOf(
                            "type" to "number",
                            "description" to "Maximum number of rows to return (default: 100)"
                        )
                    ),
                    "required" to listOf("tableName")
                )
            )
        )
    }
    
    /**
     * Execute a tool by name with given arguments
     */
    suspend fun executeTool(toolName: String, arguments: Map<String, Any?>): MCPToolResult = withContext(Dispatchers.IO) {
        Log.d(tag, "🔧 Executing tool: $toolName with args: $arguments")
        
        try {
            when (toolName) {
                TOOL_QUERY_DATABASE -> executeQueryDatabase(arguments)
                TOOL_GET_SCHEMA -> executeGetSchema()
                TOOL_EXECUTE_SQL -> executeSQL(arguments)
                TOOL_GET_TABLE_DATA -> executeGetTableData(arguments)
                else -> MCPToolResult(
                    success = false,
                    data = null,
                    error = "Unknown tool: $toolName"
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "❌ Tool execution error: ${e.message}", e)
            MCPToolResult(
                success = false,
                data = null,
                error = "Error executing tool: ${e.message}"
            )
        }
    }
    
    /**
     * Execute natural language query on database
     */
    private suspend fun executeQueryDatabase(args: Map<String, Any?>): MCPToolResult {
        val query = args["query"] as? String ?: return MCPToolResult(
            success = false,
            data = null,
            error = "Missing 'query' parameter"
        )
        
        Log.d(tag, "📊 Executing natural language query: $query")
        
        // Use RAGDatabaseService to process the query
        val ragService = RAGDatabaseService(context)
        val result = ragService.processQueryWithMetadata(query)
        
        return MCPToolResult(
            success = true,
            data = result.data,
            sqlScript = result.sqlScript,
            metadata = mapOf<String, Any>(
                "query" to query,
                "timestamp" to System.currentTimeMillis(),
                "rowCount" to ((result.data as? List<*>)?.size ?: 0)
            )
        )
    }
    
    /**
     * Get complete database schema using MCP tool
     */
    private suspend fun executeGetSchema(): MCPToolResult {
        Log.d(tag, "📋 Fetching database schema via MCP tool")
        
        try {
            // Use MCP client to get schema from the Node.js MCP server
            val mcpClient = MCPStdioClient(context)
            val schemaJson = mcpClient.getDatabaseSchema()
            mcpClient.close()
            
            if (schemaJson != null) {
                // Parse the JSON response
                val schemaData = gson.fromJson(schemaJson, Map::class.java)
                
                return MCPToolResult(
                    success = true,
                    data = schemaData,
                    metadata = mapOf(
                        "timestamp" to System.currentTimeMillis(),
                        "source" to "mcp_tool"
                    )
                )
            } else {
                Log.w(tag, "⚠️ No schema data returned from MCP")
                return MCPToolResult(
                    success = false,
                    data = null,
                    error = "No schema data returned from MCP server"
                )
            }
        } catch (e: Exception) {
            Log.e(tag, "❌ Error getting schema via MCP: ${e.message}", e)
            return MCPToolResult(
                success = false,
                data = null,
                error = "Error fetching schema: ${e.message}"
            )
        }
    }
    
    /**
     * Execute SQL query directly
     */
    private suspend fun executeSQL(args: Map<String, Any?>): MCPToolResult {
        val sql = args["sql"] as? String ?: return MCPToolResult(
            success = false,
            data = null,
            error = "Missing 'sql' parameter"
        )
        
        Log.d(tag, "🔍 Executing SQL: $sql")
        
        try {
            val result = supabaseRepo.executeRawQuery(sql)
            
            return MCPToolResult(
                success = true,
                data = result,
                sqlScript = sql,
                metadata = mapOf<String, Any>(
                    "timestamp" to System.currentTimeMillis(),
                    "rowCount" to result.size
                )
            )
        } catch (e: Exception) {
            return MCPToolResult(
                success = false,
                data = null,
                sqlScript = sql,
                error = "SQL execution error: ${e.message}"
            )
        }
    }
    
    /**
     * Get data from a specific table
     */
    private suspend fun executeGetTableData(args: Map<String, Any?>): MCPToolResult {
        val tableName = args["tableName"] as? String ?: return MCPToolResult(
            success = false,
            data = null,
            error = "Missing 'tableName' parameter"
        )
        
        val limit = (args["limit"] as? Number)?.toInt() ?: 100
        
        Log.d(tag, "📊 Fetching data from table: $tableName (limit: $limit)")
        
        val sql = "SELECT * FROM public.$tableName LIMIT $limit"
        
        try {
            val result = supabaseRepo.executeRawQuery(sql)
            
            return MCPToolResult(
                success = true,
                data = result,
                sqlScript = sql,
                metadata = mapOf<String, Any>(
                    "tableName" to tableName,
                    "limit" to limit,
                    "timestamp" to System.currentTimeMillis(),
                    "rowCount" to result.size
                )
            )
        } catch (e: Exception) {
            return MCPToolResult(
                success = false,
                data = null,
                sqlScript = sql,
                error = "Error fetching table data: ${e.message}"
            )
        }
    }
    
    /**
     * Format tool result as markdown for display
     */
    fun formatToolResult(result: MCPToolResult, toolName: String): String {
        val sb = StringBuilder()
        
        sb.appendLine("## 🔧 Tool: `$toolName`")
        sb.appendLine()
        
        if (result.success) {
            sb.appendLine("### ✅ Execution Successful")
            sb.appendLine()
            
            // Show SQL script if available
            if (result.sqlScript != null) {
                sb.appendLine("#### 📜 SQL Script Executed:")
                sb.appendLine("```sql")
                sb.appendLine(result.sqlScript)
                sb.appendLine("```")
                sb.appendLine()
            }
            
            // Show metadata
            if (result.metadata != null) {
                sb.appendLine("#### ℹ️ Metadata:")
                result.metadata.forEach { (key, value) ->
                    sb.appendLine("- **$key**: $value")
                }
                sb.appendLine()
            }
            
            // Show data
            sb.appendLine("#### 📊 Results:")
            sb.appendLine("```json")
            sb.appendLine(gson.toJson(result.data))
            sb.appendLine("```")
        } else {
            sb.appendLine("### ❌ Execution Failed")
            sb.appendLine()
            sb.appendLine("**Error**: ${result.error}")
            
            if (result.sqlScript != null) {
                sb.appendLine()
                sb.appendLine("#### 📜 SQL Script:")
                sb.appendLine("```sql")
                sb.appendLine(result.sqlScript)
                sb.appendLine("```")
            }
        }
        
        return sb.toString()
    }
}
