package com.example.tareamov.ui.model

import org.json.JSONObject

/**
 * Represents an MCP (Model Context Protocol) tool definition.
 */
data class MCPTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject = JSONObject()
) {
    /**
     * Returns a human-readable display name for the tool.
     */
    fun getDisplayName(): String {
        return name.replace("_", " ")
            .replaceFirstChar { it.uppercase() }
    }

    /**
     * Extracts parameters from the inputSchema.
     */
    fun getParameters(): Map<String, ToolParameter> {
        val params = mutableMapOf<String, ToolParameter>()
        val properties = inputSchema.optJSONObject("properties") ?: return params
        val required = inputSchema.optJSONArray("required")
        val requiredSet = mutableSetOf<String>()
        if (required != null) {
            for (i in 0 until required.length()) {
                requiredSet.add(required.getString(i))
            }
        }

        val keys = properties.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val prop = properties.optJSONObject(key)
            if (prop != null) {
                params[key] = ToolParameter(
                    name = key,
                    type = prop.optString("type", "string"),
                    description = prop.optString("description", ""),
                    required = key in requiredSet
                )
            }
        }
        return params
    }
}

/**
 * Represents a parameter of an MCP tool.
 */
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = false
)
