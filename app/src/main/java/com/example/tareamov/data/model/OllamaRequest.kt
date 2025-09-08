package com.example.tareamov.data.model

/**
 * Clase para la solicitud a la API de Ollama
 */
data class OllamaRequest(
    val model: String = "granite-code",
    val prompt: String = "",
    val system: String = "Eres un asistente de programación experto. Proporciona análisis detallados y útiles.",
    val stream: Boolean = false,
    val raw: Boolean = false,
    val format: String = "json",
    val options: OllamaOptions = OllamaOptions()
)
