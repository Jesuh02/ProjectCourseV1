package com.example.tareamov.ui.model

import org.json.JSONObject

/**
 * Represents an MCP tool available in the server
 */
data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val isEnabled: Boolean = true
) {
    /**
     * Get required parameters for this tool
     */
    fun getRequiredParams(): List<String> {
        val required = mutableListOf<String>()
        try {
            android.util.Log.d("MCPTool", "🔍 Getting required params for: $name")
            val schema = inputSchema
            android.util.Log.d("MCPTool", "  Has 'required' field? ${schema.has("required")}")
            
            if (schema.has("required")) {
                val requiredArray = schema.getJSONArray("required")
                android.util.Log.d("MCPTool", "  📋 Required array length: ${requiredArray.length()}")
                
                for (i in 0 until requiredArray.length()) {
                    val paramName = requiredArray.getString(i)
                    required.add(paramName)
                    android.util.Log.d("MCPTool", "  ✅ Found required param: $paramName")
                }
            } else {
                android.util.Log.w("MCPTool", "  ⚠️ No 'required' field in schema")
            }
            
            android.util.Log.d("MCPTool", "  📊 Total required params: ${required.size}")
        } catch (e: Exception) {
            android.util.Log.e("MCPTool", "❌ Error getting required params", e)
            e.printStackTrace()
        }
        return required
    }

    /**
     * Get all parameters with their properties
     */
    fun getParameters(): Map<String, ToolParameter> {
        val params = mutableMapOf<String, ToolParameter>()
        try {
            android.util.Log.d("MCPTool", "🔍 Getting parameters for tool: $name")
            android.util.Log.d("MCPTool", "  📋 Full inputSchema: $inputSchema")
            android.util.Log.d("MCPTool", "  Has 'properties'? ${inputSchema.has("properties")}")
            
            if (inputSchema.has("properties")) {
                val properties = inputSchema.getJSONObject("properties")
                android.util.Log.d("MCPTool", "  📦 Properties object: $properties")
                android.util.Log.d("MCPTool", "  🔑 Properties keys: ${properties.keys().asSequence().toList()}")
                
                val requiredParams = getRequiredParams()
                android.util.Log.d("MCPTool", "  ⚠️ Required params: $requiredParams")
                
                properties.keys().forEach { key ->
                    val prop = properties.getJSONObject(key)
                    val param = ToolParameter(
                        name = key,
                        type = prop.optString("type", "string"),
                        description = prop.optString("description", ""),
                        required = requiredParams.contains(key)
                    )
                    params[key] = param
                    android.util.Log.d("MCPTool", "  ✅ Added parameter: $key (required=${param.required})")
                }
            } else {
                android.util.Log.w("MCPTool", "  ⚠️ No 'properties' found in inputSchema")
            }
            
            android.util.Log.d("MCPTool", "  📊 Total parameters extracted: ${params.size}")
        } catch (e: Exception) {
            android.util.Log.e("MCPTool", "❌ Error getting parameters", e)
            e.printStackTrace()
        }
        return params
    }

    /**
     * Get a user-friendly display name
     */
    fun getDisplayName(): String {
        return name.split("_").joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }
}

data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean
)
