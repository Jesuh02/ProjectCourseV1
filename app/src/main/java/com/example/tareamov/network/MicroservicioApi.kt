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
    val metadata: String? = null,         // Metadatos adicionales del archivo
    val userId: Long? = null,             // ID del usuario para notificaciones push
    val submissionId: Long? = null,       // ID de la submission para obtener contenido desde R2/Supabase
    val taskId: Long? = null,             // ID de la tarea para buscar submission
    val studentId: Long? = null,          // ID del estudiante para buscar submission
    val fileUri: String? = null,          // URI del archivo en R2 para descarga directa
    val requestNonce: String? = null      // Optional nonce to force unique LLM prompts
)

// Data class para la respuesta procesarPrompt
data class MicroservicioPromptResponse(
    val respuesta_texto: String?,
    val nota: Float? = null,
    val esCalificacion: Boolean? = null,
    val contenidoVacio: Boolean? = null,
    val aviso: String? = null,
    val error: String? = null,
    val detalle: String? = null
)

// Request para obtener contenido de una submission
data class ObtenerContenidoRequest(
    val submissionId: Long? = null,
    val taskId: Long? = null,
    val studentId: Long? = null,
    val fileUri: String? = null
)

// Response para obtener contenido de una submission
data class ObtenerContenidoResponse(
    val success: Boolean,
    val submissionId: Long? = null,
    val taskId: Long? = null,
    val studentId: Long? = null,
    val fileName: String? = null,
    val fileUri: String? = null,
    val submissionDate: Long? = null,
    val grade: Float? = null,
    val feedback: String? = null,
    val content: String? = null,
    val contentType: String? = null,
    val fileType: String? = null,
    val source: String? = null,
    val metadata: String? = null,
    val summary: String? = null,
    val error: String? = null
)

// Response para listar submissions
data class ListarSubmissionsResponse(
    val success: Boolean,
    val taskId: Long? = null,
    val count: Int? = null,
    val submissions: List<SubmissionInfo>? = null,
    val error: String? = null
)

data class SubmissionInfo(
    val id: Long,
    val task_id: Long,
    val student_id: Long,
    val file_uri: String?,
    val file_name: String?,
    val submission_date: Long?,
    val grade: Float?,
    val feedback: String?
)

// Request para enviar notificación por push y email
data class SendNotificationRequest(
    val userId: Long,
    val title: String,
    val message: String,
    val type: String? = null,
    val relatedId: Long? = null,
    val senderUsername: String? = null
)

// Response para enviar notificación
data class SendNotificationResponse(
    val success: Boolean,
    val message: String? = null,
    val results: NotificationResults? = null
)

data class NotificationResults(
    val push: NotificationResult? = null,
    val email: NotificationResult? = null
)

data class NotificationResult(
    val success: Boolean,
    val error: String? = null,
    val sentCount: Int? = null
)

interface MicroservicioApi {
    @POST("/procesar-prompt")
    suspend fun procesarPrompt(@Body request: MicroservicioPromptRequest): MicroservicioPromptResponse

    @POST("analizar-entrega")
    suspend fun analizarEntrega(@Body request: AnalizarEntregaRequest): AnalizarEntregaResponse

    @POST("feedback-entrega")
    suspend fun feedbackEntrega(@Body request: FeedbackEntregaRequest): FeedbackEntregaResponse
    
    // Nuevo endpoint para obtener contenido de una submission desde R2/Supabase
    @POST("obtener-contenido-submission")
    suspend fun obtenerContenidoSubmission(@Body request: ObtenerContenidoRequest): ObtenerContenidoResponse
    
    // Nuevo endpoint para listar submissions de una tarea
    @retrofit2.http.GET("listar-submissions/{taskId}")
    suspend fun listarSubmissions(@retrofit2.http.Path("taskId") taskId: Long): ListarSubmissionsResponse
    
    // Endpoint para enviar notificación por push y email
    @POST("send-notification")
    suspend fun sendNotification(@Body request: SendNotificationRequest): SendNotificationResponse
}
