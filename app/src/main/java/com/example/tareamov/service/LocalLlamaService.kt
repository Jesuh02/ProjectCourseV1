package com.example.tareamov.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio para ejecutar Llama 3 localmente en el dispositivo Android
 */
class LocalLlamaService(private val context: Context) {
    private val TAG = "LocalLlamaService"
    private val isModelLoaded = AtomicBoolean(false)
    private val modelFileName = "llama3-8b-q4_0.gguf"

    companion object {
        // Fallback host addresses reported by the Windows ipconfig output (updated with latest)
        val FALLBACK_LLAMA_URLS = listOf(
            "http://10.218.57.181:11435",  // Wi-Fi IP actual (ipconfig más reciente)
            "http://10.218.57.109:11435",  // Gateway predeterminado (ipconfig más reciente)
            "http://172.17.112.1:11435",   // WSL / Hyper-V virtual adapter
            "http://127.0.0.1:11435",      // Localhost
            "http://localhost:11435"       // Localhost alternative
        )
    }

    /**
     * Inicializa el modelo Llama 3
     */
    suspend fun initializeModel(): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded.get()) return@withContext true

        try {
            // Verificar si el modelo existe en el almacenamiento interno
            val modelFile = File(context.filesDir, modelFileName)

            if (!modelFile.exists()) {
                Log.e(TAG, "Modelo no encontrado. Debe copiarse el archivo $modelFileName al directorio de la aplicación")
                return@withContext false
            }

            // Aquí iría la inicialización real del modelo con llama.cpp
            // Por ahora, simulamos que el modelo se cargó correctamente
            Log.d(TAG, "Simulando inicialización del modelo Llama 3")
            isModelLoaded.set(true)

            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Error al inicializar el modelo Llama 3", e)
            return@withContext false
        }
    }

    /**
     * Envía un prompt al modelo local y obtiene una respuesta
     */
    private var databaseContext: String = ""

    /**
     * Set the database context for better LLM responses with RAG optimization
     */
    fun setDatabaseContext(context: String) {
        // Optimize context size for local model limitations
        databaseContext = if (context.length > 4096) {
            // Extract key schema information and recent data only
            extractKeyContext(context)
        } else {
            context
        }
        Log.d(TAG, "Database context set for LocalLlamaService (${databaseContext.length} chars)")
    }

    /**
     * Extract key context information for local model efficiency
     */
    private fun extractKeyContext(fullContext: String): String {
        val lines = fullContext.split("\n")
        val keyLines = mutableListOf<String>()
        
        // Extract schema definitions
        var inSchemaSection = false
        lines.forEach { line ->
            when {
                line.contains("ESQUEMA") || line.contains("Schema") -> {
                    inSchemaSection = true
                    keyLines.add(line)
                }
                line.contains("DATOS") && !line.contains("ESQUEMA") -> {
                    inSchemaSection = false
                }
                inSchemaSection && (line.contains("table") || line.contains("columns") || line.contains("relationships")) -> {
                    keyLines.add(line)
                }
                line.contains("COUNT") || line.contains("registros") -> {
                    keyLines.add(line)
                }
            }
        }
        
        return keyLines.joinToString("\n").take(3072) // 3KB limit for local model
    }

    /**
     * Generate a response using the local Llama model with RAG optimization
     */
    suspend fun generateResponse(prompt: String): String = withContext(Dispatchers.IO) {
        if (!isModelLoaded.get()) {
            val initialized = initializeModel()
            if (!initialized) {
                return@withContext "Error: El modelo Llama 3 no está inicializado."
            }
        }

        try {
            // Optimize prompt size for local model limitations
            val maxPromptSize = 6 * 1024  // 6KB for local model
            val optimizedPrompt = optimizePromptForLocalModel(prompt, maxPromptSize)
            
            // Create enhanced prompt with optimized context
            val enhancedPrompt = createEnhancedPrompt(optimizedPrompt)

            // Here would be the actual call to the Llama model
            Log.d(TAG, "Generating response for optimized prompt: ${enhancedPrompt.take(100)}...")

            // Return a more intelligent simulated response based on prompt analysis
            return@withContext generateIntelligentResponse(optimizedPrompt)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response", e)
            return@withContext "Error: ${e.message}"
        }
    }

    /**
     * Optimize prompt for local model constraints
     */
    private fun optimizePromptForLocalModel(prompt: String, maxSize: Int): String {
        if (prompt.length <= maxSize) return prompt
        
        Log.w(TAG, "Prompt too large (${prompt.length} chars). Optimizing for local model.")
        
        // Extract key components
        val userQuery = extractUserQuery(prompt)
        val schemaInfo = extractSchemaInfo(prompt)
        val relevantData = extractRelevantData(prompt, maxSize - userQuery.length - schemaInfo.length - 500)
        
        return """
        ESQUEMA: $schemaInfo
        
        DATOS RELEVANTES: $relevantData
        
        CONSULTA: $userQuery
        """.trimIndent()
    }

    /**
     * Extract user query from full prompt
     */
    private fun extractUserQuery(prompt: String): String {
        val lines = prompt.split("\n")
        return lines.find { 
            it.contains("Consulta") || it.contains("CONSULTA") || it.contains("Usuario")
        }?.substringAfter(":")?.trim() ?: prompt.split("\n").last().take(200)
    }

    /**
     * Extract schema information
     */
    private fun extractSchemaInfo(prompt: String): String {
        val lines = prompt.split("\n")
        val schemaLines = mutableListOf<String>()
        var inSchema = false
        
        lines.forEach { line ->
            when {
                line.contains("ESQUEMA") || line.contains("Schema") -> inSchema = true
                line.contains("DATOS") && !line.contains("ESQUEMA") -> inSchema = false
                inSchema -> schemaLines.add(line)
            }
        }
        
        return schemaLines.joinToString("\n").take(1024)
    }

    /**
     * Extract most relevant data within size limits
     */
    private fun extractRelevantData(prompt: String, maxSize: Int): String {
        val dataStart = prompt.indexOf("DATOS")
        if (dataStart < 0) return ""
        
        val dataSection = prompt.substring(dataStart)
        return if (dataSection.length > maxSize) {
            dataSection.take(maxSize) + "\n[Datos truncados por limitaciones del modelo local]"
        } else {
            dataSection
        }
    }

    /**
     * Create enhanced prompt with optimized context
     */
    private fun createEnhancedPrompt(optimizedPrompt: String): String {
        val contextualPrompt = if (databaseContext.isNotBlank() && !optimizedPrompt.contains("ESQUEMA")) {
            """
            Contexto de Base de Datos (optimizado):
            $databaseContext
            
            Consulta del Usuario:
            $optimizedPrompt
            
            Instrucciones:
            - Responde de forma concisa y directa
            - Usa solo la información proporcionada
            - Si es una lista, presenta máximo 10 elementos
            - Si es un conteo, da el número específico
            """.trimIndent()
        } else {
            optimizedPrompt
        }
        
        return contextualPrompt
    }

    /**
     * Generate intelligent response based on prompt analysis (for simulation)
     */
    private fun generateIntelligentResponse(prompt: String): String {
        val normalizedPrompt = prompt.lowercase()
        
        return when {
            normalizedPrompt.contains("usuarios") && (normalizedPrompt.contains("todos") || normalizedPrompt.contains("listar")) -> {
                "Simulación: Lista de usuarios encontrados en la base de datos. El modelo local procesaría los datos de usuarios disponibles."
            }
            normalizedPrompt.contains("videos") && normalizedPrompt.contains("creador") -> {
                "Simulación: Videos del creador especificado. El modelo local buscaría videos por creador en la base de datos."
            }
            normalizedPrompt.contains("cuántos") || normalizedPrompt.contains("cantidad") -> {
                "Simulación: Conteo de registros. El modelo local calcularía el número de elementos solicitados."
            }
            normalizedPrompt.contains("tareas") || normalizedPrompt.contains("tasks") -> {
                "Simulación: Información sobre tareas. El modelo local procesaría las tareas y sus relaciones con temas."
            }
            normalizedPrompt.contains("suscripciones") || normalizedPrompt.contains("subscriptions") -> {
                "Simulación: Datos de suscripciones. El modelo local mostraría las relaciones entre usuarios suscriptores y creadores."
            }
            else -> {
                "Simulación del modelo Llama 3 local: Procesando consulta '${prompt.take(50)}...' con contexto de base de datos optimizado."
            }
        }
    }

    /**
     * Libera recursos del modelo
     */
    fun releaseModel() {
        if (isModelLoaded.get()) {
            try {
                // Aquí iría la liberación real de recursos
                isModelLoaded.set(false)
                Log.d(TAG, "Modelo Llama 3 liberado correctamente")
            } catch (e: Exception) {
                Log.e(TAG, "Error al liberar el modelo Llama 3", e)
            }
        }
    }

    /**
     * Worker para descargar el modelo en segundo plano
     */
    class ModelDownloadWorker(
        context: Context,
        params: WorkerParameters
    ) : CoroutineWorker(context, params) {

        override suspend fun doWork(): Result {
            // URL oficial del modelo GGUF (Q4_0) desde Hugging Face
            val modelUrl = "https://huggingface.co/QuantFactory/Meta-Llama-3-8B-Instruct-GGUF/resolve/main/Meta-Llama-3-8B-Instruct.Q4_0.gguf"
            val modelFile = File(applicationContext.filesDir, "llama3-8b-instruct-q4_0.gguf")
            val maxRetries = 3
            var attempt = 0
            while (attempt < maxRetries) {
                try {
                    val url = java.net.URL(modelUrl)
                    url.openStream().use { input ->
                        modelFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (modelFile.exists() && modelFile.length() > 0) {
                        return Result.success()
                    }
                } catch (e: Exception) {
                    Log.e("ModelDownloadWorker", "Error descargando el modelo (intento ${attempt + 1})", e)
                    if (modelFile.exists()) modelFile.delete()
                }
                attempt++
            }
            return Result.failure()
        }
    }

    /**
     * Inicia la descarga del modelo si no existe
     */
    fun downloadModelIfNeeded() {
        val modelFile = File(context.filesDir, modelFileName)
        if (!modelFile.exists()) {
            val downloadWorkRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .build()
            WorkManager.getInstance(context).enqueue(downloadWorkRequest)
        }
    }
}
