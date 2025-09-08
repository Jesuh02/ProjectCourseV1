package com.example.tareamov.data.model

/**
 * Clase para la respuesta básica de la API de Ollama
 * @deprecated Use OllamaResponse from OllamaModels.kt instead
 */
@Deprecated("Use OllamaResponse from OllamaModels.kt instead")
data class OllamaResponseBasic(
    val model: String = "",
    val created_at: String = "",
    val response: String = "",
    val done: Boolean = true,
    val context: List<Int>? = null,
    val total_duration: Long = 0,
    val load_duration: Long = 0,
    val prompt_eval_count: Int = 0,
    val prompt_eval_duration: Long = 0,
    val eval_count: Int = 0,
    val eval_duration: Long = 0
)
