package com.example.tareamov.network

import retrofit2.http.Body
import retrofit2.http.POST

// Request para analizar entrega
data class AnalizarEntregaRequest(
    val submissionId: Long,
    val fileContent: String,
    val contentSummary: String,
    val ollamaUrl: String
)

// Response para analizar entrega
data class AnalizarEntregaResponse(
    val nota: Int?,
    val resumen: String?,
    val cumplimiento: String?
)

// Request para feedback conversacional
data class FeedbackEntregaRequest(
    val submissionId: Long,
    val pregunta: String,
    val ollamaUrl: String
)

// Response para feedback conversacional
data class FeedbackEntregaResponse(
    val feedback: String?
)

// Data class para la petición procesarPrompt
data class MicroservicioPromptRequest(
    val prompt: String,
    val ollamaUrl: String = "http://localhost:11434",
    val model: String = "",
    val descripcionTarea: String? = null,
    val taskDescription: String? = null,  // Descripción específica de la tarea
    val fileContent: String? = null,      // Contenido del archivo (texto plano extraído)
    val jsonContent: String? = null,      // Contenido estructurado en JSON del archivo
    val metadata: String? = null,          // Metadatos adicionales del archivo
    val userId: Long? = null              // ID del usuario para notificaciones push
)

// Data class para la respuesta procesarPrompt
data class MicroservicioPromptResponse(
    val respuesta_texto: String?,
    val aviso: String? = null,
    val error: String? = null,
    val detalle: String? = null
)

interface MicroservicioApi {
    @POST("/procesar-prompt")
    suspend fun procesarPrompt(@Body request: MicroservicioPromptRequest): MicroservicioPromptResponse

    @POST("analizar-entrega")
    suspend fun analizarEntrega(@Body request: AnalizarEntregaRequest): AnalizarEntregaResponse

    @POST("feedback-entrega")
    suspend fun feedbackEntrega(@Body request: FeedbackEntregaRequest): FeedbackEntregaResponse
}
