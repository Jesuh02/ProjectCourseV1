package com.example.tareamov.data.model

/**
 * Clase para definir información sobre modelos de Ollama
 */
data class OllamaModel(
    val id: String,
    val name: String,
    val size: String = "",
    val quantization: String = "",
    val family: String = "",
    val capabilities: List<String> = listOf(),
    val contextSize: Int = 4096,
    val isInstalled: Boolean = false
)

/**
 * Clase para representar la respuesta de modelos de Ollama
 */
data class OllamaResponse(
    val model: String,
    val created_at: String? = null,
    val message: OllamaMessage? = null,
    val done: Boolean = true,
    val total_duration: Long? = null,
    val load_duration: Long? = null,
    val prompt_eval_count: Int? = null,
    val prompt_eval_duration: Long? = null,
    val eval_count: Int? = null,
    val eval_duration: Long? = null
)

/**
 * Clase para representar mensajes en la respuesta de Ollama
 */
data class OllamaMessage(
    val role: String,
    val content: String
)