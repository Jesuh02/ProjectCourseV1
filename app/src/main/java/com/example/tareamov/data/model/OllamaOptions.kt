package com.example.tareamov.data.model

/**
 * Clase para configurar opciones de generación de Ollama
 */
data class OllamaOptions(
    val temperature: Float = 0.7f,
    val top_p: Float = 0.9f,
    val top_k: Int = 40,
    val num_predict: Int = 128,
    val stop: List<String> = listOf("\n\n"),
    val repeat_penalty: Float = 1.1f,
    val num_ctx: Int = 4096,
    val num_thread: Int = 4
)
