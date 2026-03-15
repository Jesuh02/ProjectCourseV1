package com.example.tareamov.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.example.tareamov.MainActivity
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.ChatMessage
import com.example.tareamov.network.MicroservicioApi
import com.example.tareamov.network.MicroservicioPromptRequest
import com.example.tareamov.service.MCPHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * LLMBackgroundWorker - Processes LLM tasks in the background
 * 
 * This worker allows LLM requests to continue even when the app is closed.
 * It handles three types of tasks:
 * - CHAT: Chat messages in ChatBotFragment
 * - DATABASE_QUERY: Database queries in DatabaseQueryFragment  
 * - REINFORCEMENT: Quiz question generation in ReinforcementLearningFragment
 */
class LLMBackgroundWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "LLMBackgroundWorker"
        
        // Input data keys
        const val KEY_TASK_TYPE = "task_type"
        const val KEY_PROMPT = "prompt"
        const val KEY_USER_ID = "user_id"
        const val KEY_USERNAME = "username"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_TASK_DESCRIPTION = "task_description"
        const val KEY_FILE_CONTENT = "file_content"
        const val KEY_JSON_CONTENT = "json_content"
        const val KEY_METADATA = "metadata"
        const val KEY_SUBMISSION_ID = "submission_id"
        const val KEY_TASK_ID = "task_id"
        const val KEY_COURSE_ID = "course_id"
        const val KEY_COURSE_NAME = "course_name"
        const val KEY_TOPIC_ID = "topic_id"
        
        // Output data keys
        const val KEY_RESULT = "result"
        const val KEY_ERROR = "error"
        const val KEY_NOTA = "nota"
        
        // Task types
        const val TASK_TYPE_CHAT = "chat"
        const val TASK_TYPE_DATABASE_QUERY = "database_query"
        const val TASK_TYPE_REINFORCEMENT = "reinforcement"
        
        // Notification
        const val NOTIFICATION_CHANNEL_ID = "llm_background_channel"
        const val NOTIFICATION_ID = 1001
        
        // Unique work names
        fun getUniqueWorkName(taskType: String, userId: Long): String {
            return "llm_${taskType}_${userId}_${System.currentTimeMillis()}"
        }
        
        /**
         * Schedule a chat LLM task to run in background
         */
        fun scheduleChatTask(
            context: Context,
            prompt: String,
            userId: Long,
            username: String,
            sessionId: String,
            taskDescription: String = "",
            fileContent: String = "",
            jsonContent: String = "",
            metadata: String = "",
            submissionId: Long? = null,
            taskId: Long? = null
        ): String {
            val workName = getUniqueWorkName(TASK_TYPE_CHAT, userId)
            
            val inputData = workDataOf(
                KEY_TASK_TYPE to TASK_TYPE_CHAT,
                KEY_PROMPT to prompt,
                KEY_USER_ID to userId,
                KEY_USERNAME to username,
                KEY_SESSION_ID to sessionId,
                KEY_TASK_DESCRIPTION to taskDescription,
                KEY_FILE_CONTENT to fileContent,
                KEY_JSON_CONTENT to jsonContent,
                KEY_METADATA to metadata,
                KEY_SUBMISSION_ID to (submissionId ?: -1L),
                KEY_TASK_ID to (taskId ?: -1L)
            )
            
            val workRequest = OneTimeWorkRequestBuilder<LLMBackgroundWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TASK_TYPE_CHAT)
                .addTag("user_$userId")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
            
            Log.d(TAG, "📋 Scheduled chat task: $workName")
            return workName
        }
        
        /**
         * Schedule a database query task to run in background
         */
        fun scheduleDatabaseQueryTask(
            context: Context,
            query: String,
            userId: Long,
            username: String
        ): String {
            val workName = getUniqueWorkName(TASK_TYPE_DATABASE_QUERY, userId)
            
            val inputData = workDataOf(
                KEY_TASK_TYPE to TASK_TYPE_DATABASE_QUERY,
                KEY_PROMPT to query,
                KEY_USER_ID to userId,
                KEY_USERNAME to username
            )
            
            val workRequest = OneTimeWorkRequestBuilder<LLMBackgroundWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TASK_TYPE_DATABASE_QUERY)
                .addTag("user_$userId")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
            
            Log.d(TAG, "📋 Scheduled database query task: $workName")
            return workName
        }
        
        /**
         * Schedule a reinforcement learning question generation task
         */
        fun scheduleReinforcementTask(
            context: Context,
            courseId: Long,
            courseName: String,
            topicId: Long,
            taskId: Long,
            userId: Long,
            username: String,
            jsonContent: String = ""
        ): String {
            val workName = getUniqueWorkName(TASK_TYPE_REINFORCEMENT, userId)
            
            val inputData = workDataOf(
                KEY_TASK_TYPE to TASK_TYPE_REINFORCEMENT,
                KEY_COURSE_ID to courseId,
                KEY_COURSE_NAME to courseName,
                KEY_TOPIC_ID to topicId,
                KEY_TASK_ID to taskId,
                KEY_USER_ID to userId,
                KEY_USERNAME to username,
                KEY_JSON_CONTENT to jsonContent
            )
            
            val workRequest = OneTimeWorkRequestBuilder<LLMBackgroundWorker>()
                .setInputData(inputData)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(TASK_TYPE_REINFORCEMENT)
                .addTag("user_$userId")
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniqueWork(workName, ExistingWorkPolicy.APPEND_OR_REPLACE, workRequest)
            
            Log.d(TAG, "📋 Scheduled reinforcement task: $workName")
            return workName
        }
    }

    private val database by lazy { AppDatabase.getDatabase(applicationContext) }
    
    private val microservicioApi: MicroservicioApi by lazy {
        val baseUrl = kotlinx.coroutines.runBlocking { com.example.tareamov.service.ServerEndpointResolver.getMcpBaseUrl() }
        
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        
        Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MicroservicioApi::class.java)
    }

    override suspend fun doWork(): Result {
        val taskType = inputData.getString(KEY_TASK_TYPE) ?: return Result.failure()
        
        Log.d(TAG, "🚀 Starting background LLM task: $taskType")
        
        // Show foreground notification
        setForeground(createForegroundInfo(taskType))
        
        return try {
            val result = when (taskType) {
                TASK_TYPE_CHAT -> processChatTask()
                TASK_TYPE_DATABASE_QUERY -> processDatabaseQueryTask()
                TASK_TYPE_REINFORCEMENT -> processReinforcementTask()
                else -> Result.failure(workDataOf(KEY_ERROR to "Unknown task type"))
            }
            
            // Show completion notification
            showCompletionNotification(taskType, result is Result.Success)
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Background task failed", e)
            showCompletionNotification(taskType, false)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Unknown error")))
        }
    }

    private suspend fun processChatTask(): Result = withContext(Dispatchers.IO) {
        val prompt = inputData.getString(KEY_PROMPT) ?: return@withContext Result.failure()
        val userId = inputData.getLong(KEY_USER_ID, -1L)
        val sessionId = inputData.getString(KEY_SESSION_ID) ?: ""
        val taskDescription = inputData.getString(KEY_TASK_DESCRIPTION) ?: ""
        val fileContent = inputData.getString(KEY_FILE_CONTENT) ?: ""
        val jsonContent = inputData.getString(KEY_JSON_CONTENT) ?: ""
        val metadata = inputData.getString(KEY_METADATA) ?: ""
        val submissionId = inputData.getLong(KEY_SUBMISSION_ID, -1L).takeIf { it > 0 }
        val taskId = inputData.getLong(KEY_TASK_ID, -1L).takeIf { it > 0 }
        
        try {
            Log.d(TAG, "📝 Processing chat task for user $userId")
            
            val request = MicroservicioPromptRequest(
                prompt = prompt,
                ollamaUrl = "",
                taskDescription = taskDescription,
                fileContent = fileContent,
                jsonContent = jsonContent,
                metadata = metadata,
                userId = userId,
                submissionId = submissionId,
                taskId = taskId,
                studentId = userId,
                fileUri = null
            )
            
            val responseWrapper = microservicioApi.procesarPrompt(request)
            
            // Extract inner data from wrapper
            val response = responseWrapper.data
            
            val responseText = response?.respuesta_texto ?: "Sin respuesta del servidor: ${responseWrapper.error ?: "API error"}"
            val nota = response?.nota
            val isGradingResponse = response?.esCalificacion == true
            
            // Save bot response to database
            // 🎯 SEMANTIC: Use esCalificacion from backend (LLM decides) instead of nota != null
            val botMessage = ChatMessage(
                message = responseText.replace("#", "").replace("**", ""),
                isFromUser = false,
                sessionId = sessionId,
                hasCalification = isGradingResponse,
                calificationValue = if (isGradingResponse) nota?.let { if (it % 1 == 0f) "${it.toInt()}/10" else String.format("%.1f/10", it) } else null,
                calificationAdded = false,
                senderUsername = "DeepSeek",
                senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
            )
            
            database.chatMessageDao().insertMessage(botMessage)
            
            Log.d(TAG, "✅ Chat task completed successfully")
            
            Result.success(workDataOf(
                KEY_RESULT to responseText,
                KEY_NOTA to (nota ?: -1f)
            ))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Chat task failed", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Error processing chat")))
        }
    }

    private suspend fun processDatabaseQueryTask(): Result = withContext(Dispatchers.IO) {
        val query = inputData.getString(KEY_PROMPT) ?: return@withContext Result.failure()
        val userId = inputData.getLong(KEY_USER_ID, -1L)
        
        try {
            Log.d(TAG, "🔍 Processing database query task for user $userId")
            
            val mcpClient = MCPHttpClient(applicationContext)
            mcpClient.initialize()
            val result = mcpClient.executeTool("query_database", org.json.JSONObject().put("query", query))
            
            val responseText = if (result.success) {
                result.data?.toString() ?: "Consulta completada sin datos"
            } else {
                "Error: ${result.error}"
            }
            
            Log.d(TAG, "✅ Database query task completed")
            
            // Store result in SharedPreferences for retrieval when app reopens
            val prefs = applicationContext.getSharedPreferences("llm_background_results", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("last_db_query_result_$userId", responseText)
                .putLong("last_db_query_timestamp_$userId", System.currentTimeMillis())
                .apply()
            
            Result.success(workDataOf(KEY_RESULT to responseText))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Database query task failed", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Error processing query")))
        }
    }

    private suspend fun processReinforcementTask(): Result = withContext(Dispatchers.IO) {
        val courseId = inputData.getLong(KEY_COURSE_ID, -1L)
        val courseName = inputData.getString(KEY_COURSE_NAME) ?: ""
        val topicId = inputData.getLong(KEY_TOPIC_ID, -1L)
        val taskId = inputData.getLong(KEY_TASK_ID, -1L)
        val userId = inputData.getLong(KEY_USER_ID, -1L)
        val jsonContent = inputData.getString(KEY_JSON_CONTENT) ?: ""
        
        try {
            Log.d(TAG, "📚 Processing reinforcement task for course $courseId")
            
            val prompt = """
                Genera EXACTAMENTE 10 preguntas de opción múltiple de NIVEL MÁXIMO (10/10) para el curso "$courseName".
                
                ═══ INSTRUCCIÓN PEDAGÓGICA 10/10 (OBLIGATORIO) ═══
                
                PRINCIPIO #1 — PENSAR, NO BUSCAR:
                - PROHIBIDO preguntas donde el estudiante solo localiza un dato ("¿Cuál es el valor de X?").
                - OBLIGATORIO: El estudiante debe ANALIZAR, INTERPRETAR y DECIDIR.
                - Cada pregunta DEBE presentar un ESCENARIO concreto (mín. 1 oración de contexto).
                  Usa: "Un ingeniero debe decidir...", "Al comparar dos enfoques...", "Si se elimina el componente X..."
                
                PRINCIPIO #2 — OPCIONES SIN COINCIDENCIA VISUAL:
                - PROHIBIDO opciones que sean datos literales del material (ej: "Model A – 41h30m").
                - Las opciones deben ser CONCEPTOS o INTERPRETACIONES al mismo nivel de plausibilidad.
                - Un estudiante que no entienda debe encontrar TODAS las opciones tentadoras.
                
                PRINCIPIO #3 — EVALUAR CONSECUENCIAS (★ MÁS IMPORTANTE ★):
                - PROHIBIDO: "¿Cuál es el tiempo?" (dato).
                - OBLIGATORIO: "¿Qué IMPLICA/CAUSA/PRODUCE ese tiempo?"
                - El estudiante debe entender MECANISMOS, no solo números.
                
                PRINCIPIO #4 — TRAMPAS CONCEPTUALES SUTILES:
                - Los 3 distractores deben ser TODOS plausibles a primera vista.
                - Tipos: inversión causa-efecto, concepto correcto en contexto equivocado,
                  verdad general que no responde la pregunta específica, confusión entre conceptos del mismo dominio.
                - PROHIBIDO distractores absurdos o fuera de tema.
                
                DISTRIBUCIÓN: 4 aplicación+consecuencia, 3 análisis+comparación, 3 evaluación+decisión.
                
                La explicación DEBE indicar por qué la correcta es correcta Y por qué CADA distractor es incorrecto.
                
                FORMATO JSON REQUERIDO:
                [
                  {
                    "question": "Un ingeniero debe elegir entre dos enfoques [escenario del material]. ¿Qué consecuencia directa tiene elegir el enfoque A?",
                    "options": ["Opción plausible A", "Opción plausible B", "Opción plausible C", "Opción plausible D"],
                    "correctIndex": 0,
                    "explanation": "Correcta: A porque [mecanismo]. B incorrecta: [invierte causa-efecto]. C incorrecta: [confunde conceptos]. D incorrecta: [verdad general que no aplica aquí]."
                  }
                ]
                
                Genera preguntas que sean IMPOSIBLES de responder sin comprender el material.
            """.trimIndent()
            
            val request = MicroservicioPromptRequest(
                prompt = prompt,
                jsonContent = jsonContent,
                ollamaUrl = "",
                model = "deepseek-chat",
                userId = userId,
                courseId = courseId,
                topicId = topicId,
                taskId = taskId
            )
            
            val responseWrapper = microservicioApi.procesarPrompt(request)
            val jsonText = responseWrapper.data?.respuesta_texto ?: ""
            
            Log.d(TAG, "✅ Reinforcement task completed")
            
            // Store result for retrieval
            val prefs = applicationContext.getSharedPreferences("llm_background_results", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("reinforcement_questions_${userId}_$courseId", jsonText)
                .putLong("reinforcement_timestamp_${userId}_$courseId", System.currentTimeMillis())
                .apply()
            
            Result.success(workDataOf(KEY_RESULT to jsonText))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Reinforcement task failed", e)
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Error generating questions")))
        }
    }

    private fun createForegroundInfo(taskType: String): ForegroundInfo {
        createNotificationChannel()
        
        val title = when (taskType) {
            TASK_TYPE_CHAT -> "Procesando mensaje..."
            TASK_TYPE_DATABASE_QUERY -> "Ejecutando consulta..."
            TASK_TYPE_REINFORCEMENT -> "Generando preguntas..."
            else -> "Procesando..."
        }
        
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText("La tarea continuará en segundo plano")
            .setSmallIcon(R.drawable.ic_stat_coursev)
            .setColor(0xFF673AB7.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        if (Build.VERSION.SDK_INT >= 34) {
             return ForegroundInfo(
                 NOTIFICATION_ID, 
                 notification, 
                 android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
             )
        }
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(taskType: String, success: Boolean) {
        if (!success) return
        createNotificationChannel()
        
        val (title, text) = when (taskType) {
            TASK_TYPE_CHAT -> "Mensaje procesado" to "Tu respuesta está lista"
            TASK_TYPE_DATABASE_QUERY -> "Consulta completada" to "Los resultados están listos"
            TASK_TYPE_REINFORCEMENT -> "Preguntas generadas" to "Tu quiz está listo"
            else -> "Tarea completada" to "Abre la app para ver el resultado"
        }
        
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("from_background_task", true)
            putExtra("task_type", taskType)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(applicationContext, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_coursev)
            .setColor(0xFF673AB7.toInt())
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Procesamiento en segundo plano",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificaciones de tareas de IA en segundo plano"
            }
            
            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
}
