package com.example.tareamov.service

import android.content.Context
import android.util.Log
import com.example.tareamov.data.entity.FileContext
import com.example.tareamov.data.model.AIAnalysisResult
import com.example.tareamov.data.model.OllamaRequest
import com.example.tareamov.data.model.OllamaOptions
import com.example.tareamov.data.model.OllamaMessage
import com.example.tareamov.data.model.OllamaResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import java.net.NetworkInterface
import java.net.InetAddress

/**
 * Servicio para análisis de archivos usando IA con Ollama
 *
 * Este servicio detecta automáticamente las direcciones IP disponibles del dispositivo
 * y prueba múltiples endpoints de Ollama hasta encontrar uno que funcione.
 *
 * Características:
 * - Detección automática de endpoints basada en interfaces de red del dispositivo
 * - Soporte para emulador Android (10.0.2.2), localhost y redes locales
 * - Cache de endpoint funcionando para mejores tiempos de respuesta
 * - Análisis robusto con fallbacks en caso de errores
 * - Logging detallado para debugging
 */
class AIAnalysisService(private val context: Context) {

    companion object {
        private const val TAG = "AIAnalysisService"
        private const val OLLAMA_PORT = 11435
        private var workingEndpoint: String? = null
        private var detectedEndpoints: List<String>? = null

        // URL del modelo Granite para descargar/referencia
        const val GRANITE_MODEL_URL = "https://huggingface.co/mradermacher/granite-3b-code-instruct-128k-GGUF/resolve/main/granite-3b-code-instruct-128k.Q4_K_M.gguf"

        // Número máximo de intentos de conexión
        private const val MAX_RETRY_ATTEMPTS = 3
    }

    // Cambia aquí el modelo por defecto
    private val DEFAULT_MODEL = "gemma3n"

    // Lista de modelos alternativos en orden de preferencia
    // Lista de modelos a usar - prioritariamente solo gemma3n
    private val FALLBACK_MODELS = listOf(
        "gemma3n"
    )

    // Lista de modelos para solo verificar conectividad con el servidor
    private val CONNECTIVITY_TEST_MODELS = listOf(
        "llama3:8b",
        "llama3:latest",
        "gemma:7b",
        "mistral"
    )

    private suspend fun getOllamaApi(): OllamaApiService = withContext(Dispatchers.IO) {
        for (attempt in 1..MAX_RETRY_ATTEMPTS) {
            try {
                val endpoint = detectWorkingEndpoint() ?: generateOllamaEndpoints().firstOrNull() ?: "http://localhost:11435/"
                Log.d(TAG, "🔄 Intento $attempt de $MAX_RETRY_ATTEMPTS para conectar a Ollama: $endpoint")

                val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Connection", "close")
                            .build()
                        chain.proceed(request)
                    }
                    .addInterceptor(HttpLoggingInterceptor().apply {
                        level = HttpLoggingInterceptor.Level.HEADERS // Reducir logging para mejor rendimiento
                    })
                    .build()

                val api = Retrofit.Builder()
                    .baseUrl(endpoint)
                    .client(okHttpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(OllamaApiService::class.java)

                // Solo probar la conectividad con el servidor, no el modelo específico
                try {
                    // Intentar primero con el modelo Granite (incluso si falla con 404)
                    val testRequest = OllamaRequest(
                        model = DEFAULT_MODEL,
                        prompt = "test connection",
                        stream = false,
                        options = OllamaOptions(temperature = 0.1f, num_predict = 1)
                    )

                    val response = api.generateResponse(testRequest)
                    val responseCode = response.code()

                    // Si recibimos 404, significa que el servidor está funcionando pero el modelo no está
                    // Esto es suficiente para confirmar conectividad
                    if (responseCode == 404 || response.isSuccessful) {
                        Log.d(TAG, "✅ API de Ollama conectada correctamente (código: $responseCode)")
                        return@withContext api
                    }
                } catch (e: Exception) {
                    // Si falló con Granite, probar con un modelo más básico solo para confirmar
                    // que el servidor está respondiendo
                    Log.d(TAG, "Granite no disponible, probando con modelo básico para verificar conectividad")

                    try {
                        // Usar un modelo simple para probar conectividad
                        for (testModel in CONNECTIVITY_TEST_MODELS) {
                            val connectivityRequest = OllamaRequest(
                                model = testModel,
                                prompt = "test connection",
                                stream = false,
                                options = OllamaOptions(temperature = 0.1f, num_predict = 1)
                            )

                            val response = api.generateResponse(connectivityRequest)
                            if (response.isSuccessful || response.code() == 404) {
                                Log.d(TAG, "✅ Servidor Ollama conectado, pero necesita instalar Granite")
                                return@withContext api
                            }
                        }
                    } catch (inner: Exception) {
                        Log.e(TAG, "❌ Error verificando conectividad básica: ${inner.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error general en el intento $attempt: ${e.message}")
                if (attempt == MAX_RETRY_ATTEMPTS) {
                    throw e
                }
            }

            // Pequeña pausa antes del siguiente intento
            kotlinx.coroutines.delay(1000)
        }

        // Si llegamos aquí, todos los intentos fallaron
        throw Exception("No se pudo establecer conexión con Ollama después de $MAX_RETRY_ATTEMPTS intentos")
    }

    suspend fun analyzeFileWithAI(
        fileContext: FileContext,
        userQuestion: String,
        model: String = DEFAULT_MODEL
    ): AIAnalysisResult = withContext(Dispatchers.IO) {
        try {
            // Verificar si tenemos contenido válido del archivo
            if (fileContext.fileContent.isBlank()) {
                Log.e(TAG, "Error: Contenido del archivo vacío o inaccesible")
                return@withContext AIAnalysisResult(
                    success = false,
                    analysis = "No se pudo acceder al contenido del archivo. Por favor verifica que el archivo esté accesible.",
                    error = "Contenido del archivo inaccesible o vacío",
                    model = "error_handler"
                )
            }

            Log.d(TAG, "Iniciando análisis de archivo: ${fileContext.fileName}")
            val prompt = buildPromptWithContext(userQuestion, fileContext)
            Log.d(TAG, "Prompt construido (${prompt.length} caracteres)")

            // Asegurarse de que el modelo existe o usar uno disponible
            val modelToUse = validateModel(model)
            Log.d(TAG, "Usando modelo: $modelToUse (solicitado: $model)")

            val request = OllamaRequest(
                model = modelToUse,
                prompt = prompt,
                stream = false,
                options = OllamaOptions(
                    temperature = 0.7f,
                    top_p = 0.9f,
                    num_predict = 800
                )
            )

            Log.d(TAG, "Enviando solicitud a Ollama con modelo: $modelToUse")
            val ollamaApi = getOllamaApi()
            val response = ollamaApi.generateResponse(request)
            Log.d(TAG, "Respuesta recibida - Código: ${response.code()}")

            if (response.isSuccessful) {
                val responseBody = response.body()
                if (responseBody != null && responseBody.message?.content?.isNotBlank() == true) {
                    Log.d(TAG, "Respuesta exitosa de Ollama (${responseBody.message.content.length} caracteres)")
                    return@withContext AIAnalysisResult(
                        success = true,
                        analysis = responseBody.message.content,
                        confidence = 0.85f,
                        model = modelToUse,
                        context = fileContext.fileName
                    )
                } else {
                    Log.w(TAG, "Respuesta vacía de Ollama")
                    return@withContext generateFallbackAnalysis(fileContext, userQuestion)
                }
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Error en respuesta de Ollama: ${response.code()} - $errorBody")

                // Verificar si el error es por el modelo no encontrado
                if (errorBody?.contains("model") == true && errorBody.contains("not found") == true) {
                    Log.e(TAG, "Modelo $modelToUse no encontrado. Sugerencia: ollama pull $modelToUse")
                    return@withContext AIAnalysisResult(
                        success = false,
                        analysis = "⚠️ **MODELO NO ENCONTRADO** ⚠️\n\n" +
                                "El modelo '**$modelToUse**' no está disponible en el servidor Ollama.\n\n" +
                                "Para utilizar este modelo, debes instalarlo con el siguiente comando:\n\n" +
                                "```bash\nollama run granite-code\n```\n\n" +
                                "Este modelo es **OBLIGATORIO** para que la aplicación funcione correctamente. " +
                                "No se utilizarán modelos alternativos.\n\n" +
                                "Si el comando anterior no funciona, intenta con:\n" +
                                "```bash\nollama pull granite-code\n```",
                        error = "Modelo no encontrado: $modelToUse. Ejecuta: ollama run granite-code",
                        model = "error_handler"
                    )
                }

                return@withContext generateFallbackAnalysis(fileContext, userQuestion)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error llamando a Ollama: ${e.message}", e)
            return@withContext generateFallbackAnalysis(fileContext, userQuestion)
        }
    }

    /**
     * Valida que el modelo solicitado exista o devuelve uno alternativo disponible
     * Siempre prioriza el modelo gemma3n
     */
    private fun validateModel(requestedModel: String): String {
        // Siempre intentamos usar gemma3n primero, sin fallback a otros modelos
        return if (requestedModel.contains("gemma3n", ignoreCase = true) ||
                   requestedModel.contains("gemma", ignoreCase = true)) {
            "gemma3n"
        } else {
            // Si se solicitó otro modelo específicamente, intentamos usarlo
            requestedModel
        }
    }

    suspend fun analyzeWithContext(
        userMessage: String,
        fileContext: FileContext? = null,
        model: String = DEFAULT_MODEL
    ): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Iniciando análisis - Contexto: ${if (fileContext != null) "Con archivo" else "Sin archivo"}")

            if (fileContext != null) {
                Log.d(TAG, "Analizando con contexto de archivo: ${fileContext.fileName}")
                val result = analyzeFileWithAI(fileContext, userMessage, model)
                return@withContext if (result.success) {
                    Log.d(TAG, "Análisis exitoso con IA")
                    result.analysis
                } else {
                    Log.w(TAG, "Análisis falló, usando fallback: ${result.error}")
                    result.error ?: "Error en el análisis"
                }
            } else {
                Log.d(TAG, "Analizando sin contexto de archivo")
                // Análisis sin contexto de archivo
                val prompt = buildPromptWithoutContext(userMessage)
                Log.d(TAG, "Prompt sin contexto construido")

                val request = OllamaRequest(
                    model = model,
                    prompt = prompt,
                    stream = false,
                    options = OllamaOptions(
                        temperature = 0.7f,
                        top_p = 0.9f,
                        num_predict = 500
                    )
                )

                Log.d(TAG, "Enviando solicitud a Ollama sin contexto")
                val ollamaApi = getOllamaApi()
                val response = ollamaApi.generateResponse(request)
                Log.d(TAG, "Respuesta recibida - Código: ${response.code()}")

                if (response.isSuccessful) {
                    val responseText = response.body()?.message?.content
                    if (!responseText.isNullOrBlank()) {
                        Log.d(TAG, "Respuesta exitosa sin contexto")
                        return@withContext responseText
                    } else {
                        Log.w(TAG, "Respuesta vacía, usando fallback")
                        return@withContext generateSimpleFallback(userMessage)
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Error en respuesta sin contexto: ${response.code()} - $errorBody")
                    return@withContext generateSimpleFallback(userMessage)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error en análisis con contexto: ${e.message}", e)
            return@withContext if (fileContext != null) {
                generateFallbackAnalysis(fileContext, userMessage).analysis
            } else {
                generateSimpleFallback(userMessage)
            }
        }
    }

    private fun buildPromptWithContext(userMessage: String, fileContext: FileContext): String {
        // Contexto SIEMPRE dinámico: se genera en tiempo real con los datos actuales
        val descripcionTarea = fileContext.metadata ?: ""
        val contenidoArchivo = fileContext.fileContent ?: ""
        val nombreArchivo = fileContext.fileName ?: ""
        val tipoArchivo = fileContext.fileType ?: ""
        return buildString {
            append("INSTRUCCIONES CRÍTICAS:\n")
            append("- RESPONDE SIEMPRE EN ESPAÑOL\n")
            append("- USA TERMINOLOGÍA EN ESPAÑOL\n")
            append("- NO USES PALABRAS EN INGLÉS SALVO TÉRMINOS TÉCNICOS NECESARIOS\n")
            append("- FORMATO EDUCATIVO Y PROFESIONAL\n\n")
            append("Pregunta del usuario: \n$userMessage\n")
            append("Descripción de la tarea: \n$descripcionTarea\n")
            append("Nombre del archivo: $nombreArchivo\n")
            append("Tipo de archivo: $tipoArchivo\n")
            append("Contenido del archivo:\n$contenidoArchivo\n")
        }
    }

    /**
     * Construye un prompt especial para manejar errores de Google Drive
     */
    private fun buildGoogleDriveErrorPrompt(userMessage: String, fileContext: FileContext): String {
        return buildString {
            append("INSTRUCCIONES CRÍTICAS:\n")
            append("- RESPONDE SIEMPRE EN ESPAÑOL\n")
            append("- USA TERMINOLOGÍA EN ESPAÑOL\n")
            append("- NO USES PALABRAS EN INGLÉS SALVO TÉRMINOS TÉCNICOS NECESARIOS\n")
            append("- FORMATO EDUCATIVO Y PROFESIONAL\n\n")
            append("Eres un asistente educativo amigable especializado en ayudar con archivos académicos. ")
            append("Un estudiante está intentando acceder a un archivo de Google Drive pero no puede acceder ")
            append("directamente desde la aplicación.\n\n")

            append("=== INFORMACIÓN DEL ARCHIVO ===\n")
            append("Nombre del archivo: ${fileContext.fileName}\n")
            append("Tipo: Archivo de Google Drive (inaccesible directamente)\n")
            append("Error: No se puede acceder directamente a archivos de Google Drive desde la aplicación\n\n")

            append("=== CONTENIDO DEL ERROR (JSON) ===\n")
            append("```json\n${fileContext.fileContent}\n```\n\n")

            append("=== PREGUNTA DEL USUARIO ===\n")
            append("$userMessage\n\n")

            append("=== INSTRUCCIONES ===\n")
            append("Por favor proporciona una respuesta EN ESPAÑOL que:\n")
            append("1. Explique amablemente que no se puede acceder directamente al archivo de Google Drive\n")
            append("2. Proporcione instrucciones claras sobre cómo descargar el archivo a su dispositivo primero\n")
            append("3. Explique que una vez descargado, puede volver a subirlo para analizarlo\n")
            append("4. Sea paciente y comprensivo, ya que el usuario puede estar frustrado\n")
            append("5. Ofrezca pasos específicos para resolver el problema\n\n")

            append("Si el usuario pregunta algo no relacionado con el error de acceso, primero informa sobre ")
            append("la limitación de acceso y luego intenta responder su pregunta lo mejor posible.\n\n")

            append("Tu respuesta:")
        }
    }

    /**
     * Convierte el contenido del archivo a formato JSON para mejor análisis por el modelo
     */
    private fun buildJsonFileContent(fileContext: FileContext): String {
        return try {
            val contentMap = mapOf(
                "fileName" to fileContext.fileName,
                "fileType" to fileContext.fileType,
                "content" to fileContext.fileContent,
                "metadata" to (fileContext.metadata ?: ""),
                "extractedText" to (fileContext.extractedText ?: ""),
                "timestamp" to fileContext.timestamp.toString()
            )

            // Usar Gson para convertir el mapa a JSON formateado
            val gson = com.google.gson.GsonBuilder().setPrettyPrinting().create()
            gson.toJson(contentMap)
        } catch (e: Exception) {
            Log.e("AIAnalysisService", "Error creando JSON del contenido: ${e.message}")
            // Fallback en caso de error
            """
            {
                "fileName": "${fileContext.fileName}",
                "fileType": "${fileContext.fileType}",
                "content": "${fileContext.fileContent.replace("\"", "\\\"").replace("\n", "\\n")}",
                "error": "Error formateando JSON completo: ${e.message}"
            }
            """.trimIndent()
        }
    }

    private fun buildPromptWithoutContext(userMessage: String): String {
        return buildString {
            append("INSTRUCCIONES CRÍTICAS:\n")
            append("- RESPONDE SIEMPRE EN ESPAÑOL\n")
            append("- USA TERMINOLOGÍA EN ESPAÑOL\n")
            append("- NO USES PALABRAS EN INGLÉS SALVO TÉRMINOS TÉCNICOS NECESARIOS\n")
            append("- FORMATO EDUCATIVO Y PROFESIONAL\n\n")
            append("Eres un asistente virtual amigable y útil que puede responder a cualquier tipo de pregunta en español. ")
            append("Puedes hablar sobre temas generales, responder saludos, proporcionar información, ")
            append("ofrecer ayuda técnica o simplemente mantener una conversación amistosa.\n\n")
            append("Pregunta: $userMessage\n\n")
            append("Responde de manera amigable, informativa y conversacional EN ESPAÑOL. ")
            append("Si el usuario te saluda o hace una pregunta casual, responde naturalmente como en una conversación normal.")
        }
    }

    private fun generateFallbackAnalysis(fileContext: FileContext, _userQuestion: String): AIAnalysisResult {
        // Si el contenido está vacío, probablemente hubo un error de acceso al archivo
        if (fileContext.fileContent.isBlank()) {
            return AIAnalysisResult(
                success = false,
                analysis = "**Error de acceso al archivo**\n\n" +
                        "No se pudo acceder al contenido del archivo '${fileContext.fileName}'. " +
                        "Esto puede ocurrir por varias razones:\n\n" +
                        "• Si el archivo está en Google Drive, descárgalo primero a tu dispositivo\n" +
                        "• Verifica los permisos de lectura del archivo\n" +
                        "• Asegúrate que el archivo no esté dañado o corrupto\n" +
                        "• Si es un archivo binario, intenta con un archivo de texto o código\n\n" +
                        "Para resolver este problema:\n" +
                        "1. Descarga el archivo a tu dispositivo\n" +
                        "2. Utiliza la opción 'Seleccionar desde almacenamiento' en lugar de Google Drive\n" +
                        "3. Verifica que puedas abrir el archivo con otras aplicaciones",
                error = "No se pudo acceder al archivo. Intenta descargarlo primero si es de Google Drive.",
                confidence = 0.0f,
                model = "error_handler"
            )
        }

        val analysis = buildString {
            append("📄 **Análisis del archivo: ${fileContext.fileName}**\n\n")

            // Análisis básico basado en tipo de archivo y contenido
            when {
                fileContext.fileType.contains("java", ignoreCase = true) ||
                fileContext.fileType.contains("kotlin", ignoreCase = true) -> {
                    append("**Tipo de archivo:** Código fuente (${fileContext.fileType})\n")
                    append("**Características detectadas:**\n")

                    val content = fileContext.fileContent
                    if (content.contains("class ")) append("• Contiene definiciones de clases\n")
                    if (content.contains("interface ")) append("• Contiene definiciones de interfaces\n")
                    if (content.contains("fun ") || content.contains("function ")) append("• Contiene definiciones de funciones\n")
                    if (content.contains("public ") || content.contains("private ")) append("• Usa modificadores de acceso\n")
                    if (content.contains("import ")) append("• Incluye importaciones de librerías\n")
                    if (content.contains("//") || content.contains("/*")) append("• Incluye comentarios\n")

                    append("\n**Respuesta a tu pregunta:**\n")
                    append("Lo siento, no pude conectarme al modelo de IA para un análisis completo. ")
                    append("Por favor, verifica que el servidor Ollama esté ejecutándose correctamente con el modelo ")
                    append("'granite-3b-code-instruct'. Puedes instalar el modelo con: ")
                    append("`ollama pull granite-3b-code-instruct`\n\n")

                    append("También puedes descargarlo manualmente desde:\n")
                    append(GRANITE_MODEL_URL + "\n\n")

                    append("Mientras tanto, puedo decirte que este archivo parece ser ")

                    if (content.contains("class") && content.contains("fun")) {
                        append("una clase Kotlin ")
                    } else if (content.contains("class") && content.contains("public") && content.contains("void")) {
                        append("una clase Java ")
                    } else {
                        append("un archivo de código ")
                    }

                    if (content.contains("extends") || content.contains("implements") || content.contains(": ")) {
                        append("que extiende o implementa otras clases/interfaces.")
                    } else {
                        append("con funcionalidad independiente.")
                    }
                }
                fileContext.fileType.contains("sql", ignoreCase = true) -> {
                    append("**Tipo de archivo:** SQL (${fileContext.fileType})\n")
                    append("Lo siento, no pude conectarme al modelo de IA. Este parece ser un archivo SQL.")
                }
                fileContext.fileType.contains("json", ignoreCase = true) -> {
                    append("**Tipo de archivo:** JSON (${fileContext.fileType})\n")
                    append("Lo siento, no pude conectarme al modelo de IA. Este parece ser un archivo JSON.")
                }
                else -> {
                    append("**Tipo de archivo:** ${fileContext.fileType}\n")
                    append("Lo siento, no pude conectarme al modelo de IA para analizar este archivo.")
                }
            }
        }

        return AIAnalysisResult(
            success = false,
            analysis = analysis,
            error = "No se pudo conectar con el servidor Ollama. Asegúrate de que Ollama esté ejecutándose con el modelo adecuado.",
            confidence = 0.0f,
            model = "fallback-local",
            context = fileContext.fileName
        )
    }

    private fun generateSimpleFallback(userMessage: String): String {
        return when {
            userMessage.contains("hola", ignoreCase = true) || 
            userMessage.contains("buenos días", ignoreCase = true) ||
            userMessage.contains("buenas tardes", ignoreCase = true) -> {
                "¡Hola! Soy tu asistente virtual. ¿En qué puedo ayudarte hoy?"
            }
            userMessage.contains("ayuda", ignoreCase = true) -> {
                "Puedo ayudarte con:\n• Análisis de código y documentos\n• Responder preguntas generales\n• Mantener una conversación\n• Proporcionar información\n• Asistencia técnica básica"
            }
            userMessage.contains("gracias", ignoreCase = true) -> {
                "¡De nada! Estoy aquí para ayudarte. Si necesitas algo más, no dudes en preguntar."
            }
            userMessage.contains("adiós", ignoreCase = true) || 
            userMessage.contains("hasta luego", ignoreCase = true) -> {
                "¡Hasta luego! Ha sido un placer chatear contigo. Vuelve cuando quieras."
            }
            userMessage.contains("cómo estás", ignoreCase = true) -> {
                "¡Estoy funcionando perfectamente! Listo para ayudarte con cualquier cosa que necesites. ¿Cómo estás tú?"
            }
            userMessage.length < 10 -> {
                "He recibido tu mensaje. ¿Podrías darme más detalles sobre lo que necesitas para poder ayudarte mejor?"
            }
            else -> {
                val responses = listOf(
                    "Entiendo tu consulta, pero en este momento estoy funcionando en modo limitado. ¿Podrías intentarlo de nuevo más tarde?",
                    "Parece una pregunta interesante. Normalmente podría ayudarte con esto, pero ahora mismo tengo una conexión limitada.",
                    "Me gustaría ayudarte con eso. ¿Podrías formular tu pregunta de otra manera mientras mejoro mi conexión?",
                    "Estoy procesando tu consulta. Para obtener una mejor respuesta, intenta cuando la conexión con el modelo de IA esté disponible.",
                    "Tu pregunta es importante. Cuando el servidor Ollama esté funcionando correctamente, podré darte una respuesta más completa."
                )
                responses.random()
            }
        }
    }

    /**
     * Método de prueba para verificar la conectividad con Ollama
     * y específicamente si el modelo Granite está disponible
     */
    suspend fun testOllamaConnection(): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Probando conexión con Ollama...")

            // Limpiar cache para forzar nueva detección
            clearEndpointCache()

            var serverConnected = false
            var graniteAvailable = false

            // Primero intentamos con el modelo Granite
            try {
                Log.d(TAG, "Probando conexión con modelo: $DEFAULT_MODEL")
                val endpoint = detectWorkingEndpoint()

                if (endpoint == null) {
                    Log.e(TAG, "No se pudo detectar ningún endpoint funcionando")
                    return@withContext Pair(false, false)
                }

                Log.d(TAG, "Usando endpoint: $endpoint")
                val ollamaApi = Retrofit.Builder()
                    .baseUrl(endpoint)
                    .client(OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(15, TimeUnit.SECONDS)
                        .writeTimeout(15, TimeUnit.SECONDS)
                        .addInterceptor { chain ->
                            val request = chain.request().newBuilder()
                                .header("Connection", "close")
                                .build()
                            chain.proceed(request)
                        }
                        .build())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(OllamaApiService::class.java)

                val testRequest = OllamaRequest(
                    model = DEFAULT_MODEL,
                    prompt = "Hola, ¿puedes responder? Prueba de conexión.",
                    stream = false,
                    options = OllamaOptions(
                        temperature = 0.1f,
                        num_predict = 50
                    )
                )

                val response = ollamaApi.generateResponse(testRequest)
                Log.d(TAG, "Respuesta de prueba - Código: ${response.code()}")

                if (response.isSuccessful) {
                    val body = response.body()
                    Log.d(TAG, "✅ Conexión exitosa con $DEFAULT_MODEL. Respuesta: ${body?.message?.content?.take(50)}")
                    serverConnected = true
                    graniteAvailable = true
                    return@withContext Pair(serverConnected, graniteAvailable)
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "Error en conexión de prueba: ${response.code()} - $errorBody")

                    // Si el error es 404 pero el servidor responde, es que el modelo no está disponible
                    if (response.code() == 404) {
                        Log.d(TAG, "Servidor Ollama funcionando pero modelo $DEFAULT_MODEL no encontrado")
                        serverConnected = true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error probando conexión con modelo $DEFAULT_MODEL: ${e.message}", e)
            }

            // Si no pudimos conectar con el modelo principal, vamos a probar conectividad básica
            if (!serverConnected) {
                // Probar con modelos básicos solo para verificar conectividad del servidor
                for (model in CONNECTIVITY_TEST_MODELS) {
                    try {
                        Log.d(TAG, "Probando conectividad básica con modelo: $model")
                        val endpoint = detectWorkingEndpoint() ?: continue

                        val ollamaApi = Retrofit.Builder()
                            .baseUrl(endpoint)
                            .client(OkHttpClient.Builder()
                                .connectTimeout(10, TimeUnit.SECONDS)
                                .readTimeout(10, TimeUnit.SECONDS)
                                .writeTimeout(10, TimeUnit.SECONDS)
                                .addInterceptor { chain ->
                                    val request = chain.request().newBuilder()
                                        .header("Connection", "close")
                                        .build()
                                    chain.proceed(request)
                                }
                                .build())
                            .addConverterFactory(GsonConverterFactory.create())
                            .build()
                            .create(OllamaApiService::class.java)

                        val testRequest = OllamaRequest(
                            model = model,
                            prompt = "test",
                            stream = false,
                            options = OllamaOptions(temperature = 0.1f, num_predict = 5)
                        )

                        val response = ollamaApi.generateResponse(testRequest)

                        if (response.isSuccessful || response.code() == 404) {
                            Log.d(TAG, "Servidor Ollama conectado usando $model (código: ${response.code()})")
                            serverConnected = true
                            break
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error probando conectividad básica con $model: ${e.message}")
                    }
                }
            }

            return@withContext Pair(serverConnected, graniteAvailable)
        } catch (e: Exception) {
            Log.e(TAG, "Error general probando conexión con Ollama: ${e.message}", e)
            return@withContext Pair(false, false)
        }
    }

    /**
     * Detecta automáticamente qué endpoint de Ollama funciona
     *
     * Prueba cada endpoint con un timeout corto y una solicitud de prueba.
     * Cachea el resultado para evitar repetir la detección en futuras llamadas.
     */
    private suspend fun detectWorkingEndpoint(): String? = withContext(Dispatchers.IO) {
        if (workingEndpoint != null) {
            Log.d(TAG, "📌 Usando endpoint previamente detectado: $workingEndpoint")
            return@withContext workingEndpoint
        }

        Log.d(TAG, "🔍 Iniciando detección automática de endpoints...")
        val endpoints = generateOllamaEndpoints()

        if (endpoints.isEmpty()) {
            Log.e(TAG, "❌ No se generaron endpoints para probar")
            return@withContext null
        }

        Log.d(TAG, "🧪 Probando ${endpoints.size} endpoints...")

        for ((index, endpoint) in endpoints.withIndex()) {
            try {
                Log.d(TAG, "⏳ [$index/${endpoints.size}] Probando: $endpoint")

                val testClient = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS) // Aumentado a 10 segundos
                    .readTimeout(15, TimeUnit.SECONDS)    // Aumentado a 15 segundos
                    .writeTimeout(15, TimeUnit.SECONDS)   // Aumentado a 15 segundos
                    .addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                            .header("Connection", "close")
                            .build()
                        chain.proceed(request)
                    }
                    .build()

                val testApi = Retrofit.Builder()
                    .baseUrl(endpoint)
                    .client(testClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(OllamaApiService::class.java)

                // Usando un modelo más simple para la prueba
                val testRequest = OllamaRequest(
                    model = "llama3:8b", // Modelo más básico para prueba, luego usaremos el correcto
                    prompt = "test",
                    stream = false,
                    options = OllamaOptions(temperature = 0.1f, num_predict = 1)
                )

                try {
                    val response = testApi.generateResponse(testRequest)
                    val responseCode = response.code()

                    // 200 = éxito, 404 = servidor responde pero modelo no encontrado (aún válido)
                    if (response.isSuccessful || responseCode == 404) {
                        workingEndpoint = endpoint
                        Log.d(TAG, "✅ ¡Endpoint funcionando detectado! $endpoint (código: $responseCode)")
                        return@withContext endpoint
                    } else {
                        Log.d(TAG, "❌ [$index/${endpoints.size}] Falló: $endpoint (código: $responseCode)")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "❌ [$index/${endpoints.size}] Error: $endpoint - ${e.message}")
                }
            } catch (e: Exception) {
                Log.d(TAG, "❌ [$index/${endpoints.size}] Error general: $endpoint - ${e.message}")
            }
        }

        Log.e(TAG, "❌ Ningún endpoint de Ollama funcionó después de probar ${endpoints.size} opciones")
        return@withContext null
    }

    /**
     * Detecta automáticamente las IPs disponibles del host para endpoints de Ollama
     *
     * Detecta:
     * - IPs estándar (emulador, localhost)
     * - Todas las interfaces de red IPv4 activas del dispositivo
     * - IPs comunes de redes privadas como fallback
     */
    private fun getHostIPs(): List<String> {
        val hostIPs = mutableListOf<String>()

        try {
            Log.d(TAG, "🔍 Iniciando detección automática de IPs...")

            // Agregar IPs estándar primero (alta prioridad)
            // Preferir la dirección del emulador 10.0.2.2 para pruebas en emulador
            val standardIPs = listOf("10.0.2.2", "localhost", "127.0.0.1", "0.0.0.0")
            hostIPs.addAll(standardIPs)
            Log.d(TAG, "✓ IPs estándar agregadas: $standardIPs")

            // Detectar IPs de la red local automáticamente
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            var detectedCount = 0

            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()

                // Saltar interfaces inactivas o loopback
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()

                    // Solo agregar IPv4 válidas no loopback
                    if (!address.isLoopbackAddress &&
                        address is java.net.Inet4Address &&
                        !address.isLinkLocalAddress) {

                        val ip = address.hostAddress
                        if (ip != null && !hostIPs.contains(ip)) {
                            Log.d(TAG, "🌐 IP de red detectada: $ip (interfaz: ${networkInterface.name})")
                            hostIPs.add(ip)
                            detectedCount++
                        }
                    }
                }
            }

            Log.d(TAG, "✓ Total de IPs de red detectadas: $detectedCount")

            // Agregar IPs comunes de redes privadas como fallback
            val commonPrivateIPs = listOf(
                "10.218.57.181", // IP Wi-Fi actual (nueva configuración)
                "10.218.57.109", // Puerta de enlace actual
                "172.17.112.1", // IP de WSL
                "192.168.1.158", // IP local anterior de este dispositivo
                "192.168.1.254", // Puerta de enlace anterior
                "192.168.1.1", "192.168.0.1", "192.168.1.100", "192.168.1.101",
                "192.168.0.100", "192.168.0.101", "10.0.0.1", "172.16.0.1"
            )

            var fallbackCount = 0
            commonPrivateIPs.forEach { ip ->
                if (!hostIPs.contains(ip)) {
                    hostIPs.add(ip)
                    fallbackCount++
                }
            }

            Log.d(TAG, "✓ IPs fallback agregadas: $fallbackCount")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error detectando IPs del host: ${e.message}", e)
            // En caso de error, asegurar IPs mínimas
            if (hostIPs.isEmpty()) {
                hostIPs.addAll(listOf("10.0.2.2", "localhost", "127.0.0.1"))
                Log.d(TAG, "📌 Usando IPs mínimas por error")
            }
        }

        Log.d(TAG, "🎯 Total de IPs detectadas: ${hostIPs.size} -> $hostIPs")
        return hostIPs
    }

    /**
     * Genera endpoints de Ollama basados en IPs detectadas automáticamente
     *
     * Utiliza cache para evitar regenerar la lista en cada llamada
     * y construye URLs completas con el puerto estándar de Ollama (11435)
     */
    private fun generateOllamaEndpoints(): List<String> {
        if (detectedEndpoints != null) {
            Log.d(TAG, "📋 Usando endpoints en cache (${detectedEndpoints!!.size} endpoints)")
            return detectedEndpoints!!
        }

        Log.d(TAG, "🔨 Generando nuevos endpoints de Ollama...")
        val hostIPs = getHostIPs()
        val endpoints = mutableListOf<String>()

        // Generar URLs con todas las IPs detectadas
        hostIPs.forEach { ip ->
            val endpoint = if (ip == "localhost" || ip.contains("localhost")) {
                "http://localhost:$OLLAMA_PORT/"
            } else {
                "http://$ip:$OLLAMA_PORT/"
            }
            endpoints.add(endpoint)
        }

        // Agregar endpoints específicos que podrían funcionar con Ollama
        val customEndpoints = listOf(
            "http://host.docker.internal:$OLLAMA_PORT/",
            "http://192.168.1.2:$OLLAMA_PORT/",
            "http://192.168.1.3:$OLLAMA_PORT/",
            "http://192.168.1.4:$OLLAMA_PORT/",
            "http://192.168.1.5:$OLLAMA_PORT/"
        )

        for (endpoint in customEndpoints) {
            if (!endpoints.contains(endpoint)) {
                endpoints.add(endpoint)
            }
        }

        detectedEndpoints = endpoints
        Log.d(TAG, "✅ Endpoints generados: ${endpoints.size} total")
        endpoints.forEachIndexed { index, endpoint ->
            Log.d(TAG, "  [$index] $endpoint")
        }
        return endpoints
    }

    /**
     * Limpia el cache de endpoints para forzar una nueva detección
     * Útil cuando la configuración de red cambia o para debugging
     */
    fun clearEndpointCache() {
        Log.d(TAG, "🧹 Limpiando cache de endpoints...")
        workingEndpoint = null
        detectedEndpoints = null
        Log.d(TAG, "✅ Cache limpiado, próxima conexión detectará endpoints automáticamente")
    }

    /**
     * Obtiene el endpoint actualmente en uso (si hay uno)
     */
    fun getCurrentEndpoint(): String? {
        return workingEndpoint
    }

    /**
     * Obtiene todos los endpoints detectados automáticamente
     */
    fun getDetectedEndpoints(): List<String> {
        return detectedEndpoints ?: generateOllamaEndpoints()
    }

    /**
     * Analiza un documento con un contexto estructurado y responde a preguntas generales
     *
     * @param fileContext El contexto del archivo con su estructura en JSON
     * @param userQuestion La pregunta del usuario sobre el archivo
     * @return El resultado del análisis con IA
     */
    suspend fun analyzeDocumentWithAI(
        fileContext: FileContext,
        userQuestion: String,
        model: String = DEFAULT_MODEL
    ): AIAnalysisResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Analizando documento: ${fileContext.fileName} con modelo: $model")

            // Valida y obtiene el modelo adecuado
            val finalModel = validateModel(model)

            // Construye el prompt incluyendo el contexto del archivo y la pregunta
            val prompt = buildPromptWithContext(userQuestion, fileContext)

            // Configura opciones avanzadas para mejor análisis de documentos
            val options = OllamaOptions(
                temperature = 0.2f, // Temperatura más baja para respuestas más precisas
                top_p = 0.9f,
                top_k = 40,
                num_predict = 1024, // Respuestas más extensas
                stop = listOf("\n\n\n"),
                repeat_penalty = 1.1f,
                num_ctx = 16384, // Contexto ampliado para documentos largos
                num_thread = 4
            )

            // Configura la solicitud para el modelo
            val request = OllamaRequest(
                model = finalModel,
                prompt = prompt,
                system = "Eres un asistente experto en análisis de documentos que responde preguntas sobre archivos en un formato conversacional y natural. Proporciona respuestas concisas pero completas basadas en el contenido del archivo.",
                stream = false,
                format = "json",
                options = options
            )

            try {
                val ollamaApi = getOllamaApi()
                val response = ollamaApi.generateResponse(request)

                if (response.isSuccessful && response.body() != null) {
                    val result = response.body()!!
                    val content = result.message?.content ?: "No se pudo obtener una respuesta del modelo."

                    return@withContext AIAnalysisResult(
                        success = true,
                        analysis = content,
                        model = finalModel,
                        confidence = 0.95f,
                        response = content,
                        context = fileContext.contentSummary
                    )
                } else {
                    Log.e(TAG, "Error al obtener respuesta: ${response.errorBody()?.string()}")
                    throw Exception("Error al comunicarse con el servicio de IA: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en la comunicación con Ollama", e)
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error general en análisis de documento", e)
            return@withContext AIAnalysisResult(
                success = false,
                error = "Error al analizar el documento: ${e.message}",
                model = model,
                context = fileContext.contentSummary
            )
        }
    }
}
