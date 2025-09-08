package com.example.tareamov.data.model

/**
 * Modelo para representar el contenido de un archivo para procesamiento por IA
 */
data class FileContent(
    val fileName: String,
    val fileType: String,
    val content: String,
    val metadata: Map<String, Any> = mapOf(),
    val structure: Map<String, Any> = mapOf(),
    val sections: List<FileSection> = listOf()
)

/**
 * Sección de un archivo para mejor estructuración
 */
data class FileSection(
    val title: String? = null,
    val content: String,
    val level: Int = 0,
    val type: String = "text", // "text", "image", "table", "code", etc.
    val metadata: Map<String, Any> = mapOf()
)
