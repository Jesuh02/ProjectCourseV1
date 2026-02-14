package com.example.tareamov.ui.compose

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tareamov.network.MicroservicioApi
import com.example.tareamov.network.MicroservicioPromptRequest
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ServerEndpointResolver
import com.example.tareamov.service.network.FallbackDnsResolver
import com.example.tareamov.work.BackgroundTaskManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String? = null  // Changed to nullable - sanitization happens during parsing
) {
    // Safe getter that NEVER returns null
    fun getExplanationSafe(): String {
        return when {
            explanation.isNullOrBlank() || explanation == "null" -> {
                val correctOpt = options.getOrElse(correctIndex) { "la opción correcta" }
                "La respuesta correcta es: \"$correctOpt\". Explicación auto-generada."
            }
            else -> explanation
        }
    }
}

data class AnalyzedFile(
    val name: String,
    val url: String?,
    val type: String?
)

data class LearningContextInfo(
    val topicName: String?,
    val taskName: String?,
    val files: List<AnalyzedFile>
)

sealed class ReinforcementState {
    object Initial : ReinforcementState()
    object Loading : ReinforcementState()
    data class Success(val questions: List<QuizQuestion>) : ReinforcementState()
    data class Error(val message: String) : ReinforcementState()
}

class ReinforcementLearningViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ReinforcementState>(ReinforcementState.Initial)
    val uiState: StateFlow<ReinforcementState> = _uiState.asStateFlow()

    private val _currentScore = MutableStateFlow(0)
    val currentScore: StateFlow<Int> = _currentScore.asStateFlow()
    
    // Background task tracking
    private var isGeneratingQuestions = false
    private var pendingCourseId: Long = -1L
    private var pendingCourseName: String = ""
    private var pendingTopicId: Long = -1L
    private var pendingTaskId: Long = -1L

    private val _selectedTopicName = MutableStateFlow<String?>(null)
    val selectedTopicName: StateFlow<String?> = _selectedTopicName.asStateFlow()

    private val _selectedTaskName = MutableStateFlow<String?>(null)
    val selectedTaskName: StateFlow<String?> = _selectedTaskName.asStateFlow()

    private val _analyzedFiles = MutableStateFlow<List<AnalyzedFile>>(emptyList())
    val analyzedFiles: StateFlow<List<AnalyzedFile>> = _analyzedFiles.asStateFlow()

    // Configuration: STRICTLY use BuildConfig.BACKEND_URL per build variant (QA → QA server, Production → Production server)
    // No local network scanning — each build variant MUST only communicate with its own server.
    private val API_KEY = "tareamov-mcp-api-key-2025-secure"
    private val BASE_URL: String by lazy {
        val railwayUrl = com.example.tareamov.BuildConfig.BACKEND_URL.ifBlank { "https://mcp-backenddeploy-production.up.railway.app" }
        if (railwayUrl.endsWith("/")) railwayUrl else "$railwayUrl/"
    }
    private val OLLAMA_URL = BASE_URL.trimEnd('/')
    private val FALLBACK_BACKEND_URLS = listOf(
        "https://mcp-backenddeploy-production.up.railway.app/"
    )

    init {
        // Initialize BackendApiService and ServerEndpointResolver
        BackendApiService.initialize(application.applicationContext)
        ServerEndpointResolver.initialize(application)
        // STRICT BUILD VARIANT ROUTING: Always use BuildConfig.BACKEND_URL
        Log.i("ReinforcementVM", "Build variant backend URL: ${com.example.tareamov.BuildConfig.BACKEND_URL}")
        Log.i("ReinforcementVM", "Using strict build variant routing (no local discovery)")
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return BASE_URL
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun createApi(baseUrl: String = BASE_URL): MicroservicioApi {
        val effectiveBase = normalizeBaseUrl(baseUrl)

        val okHttpClient = OkHttpClient.Builder()
            .dns(FallbackDnsResolver)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val builder = originalRequest.newBuilder()
                    .header("X-API-Key", API_KEY)
                // Per-request Supabase routing for production build variant
                val supaUrl = com.example.tareamov.BuildConfig.SUPABASE_URL
                val supaKey = com.example.tareamov.BuildConfig.SUPABASE_ANON_KEY
                if (supaUrl.isNotBlank()) builder.header("X-Supabase-Url", supaUrl)
                if (supaKey.isNotBlank()) builder.header("X-Supabase-Key", supaKey)
                chain.proceed(builder.build())
            }
            .build()

        // STRICT: Always use the build variant's backend URL (no local/cloud switching)
        val retrofitBase = effectiveBase
        Log.d("ReinforcementVM", "Retrofit base URL (strict per build variant): $retrofitBase")

        val retrofit = Retrofit.Builder()
            .baseUrl(retrofitBase)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(MicroservicioApi::class.java)
    }

    fun loadQuestions(courseId: Long, courseName: String, topicId: Long = -1L, taskId: Long = -1L) {
        loadQuestionsInternal(courseId, courseName, topicId, taskId, retryAttempt = 0, previouslyExcluded = emptyList())
    }

    /**
     * Internal question loading with retry support.
     * If all generated questions are duplicates, retries with existing questions
     * explicitly excluded in the prompt for semantic differentiation.
     * @param retryAttempt current retry (max 1 retry)
     * @param previouslyExcluded question texts from DB that MUST NOT be repeated
     */
    private fun loadQuestionsInternal(
        courseId: Long,
        courseName: String,
        topicId: Long = -1L,
        taskId: Long = -1L,
        retryAttempt: Int = 0,
        previouslyExcluded: List<String> = emptyList()
    ) {
        if (courseId == -1L) {
            _uiState.value = ReinforcementState.Error("ID de curso inválido.")
            return
        }

        // REQUIRE Topic or Task selection. Disable "Course-only" generation.
        if (topicId == -1L && taskId == -1L) {
            _uiState.value = ReinforcementState.Error("Debes seleccionar un Tópico o una Tarea para generar preguntas.")
            return
        }

        // Set Loading state immediately to avoid race conditions in UI
        _uiState.value = ReinforcementState.Loading
        
        // Track pending data for background processing
        isGeneratingQuestions = true
        pendingCourseId = courseId
        pendingCourseName = courseName
        pendingTopicId = topicId
        pendingTaskId = taskId

        viewModelScope.launch {
            try {
                // Get User ID from SessionManager
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(getApplication())
                val userId = sessionManager.getUserId()

                // 2. Fetch Context from BackendApiService
                // Use IO dispatcher explicitly for network operations
                val (topics, tasks, contentItems) = withContext(Dispatchers.IO) {
                    var t = when (val result = BackendApiService.getTopicsByCourse(courseId)) {
                        is ApiResult.Success -> result.data ?: emptyList()
                        is ApiResult.Error -> emptyList()
                    }

                    // Filter by Topic if selected
                    if (topicId != -1L) {
                        t = t.filter { it.id == topicId }
                    }

                    val tIds = t.map { it.id }
                    var k = if (tIds.isNotEmpty()) {
                        // Fetch tasks for each topic
                        tIds.flatMap { tid ->
                            when (val result = BackendApiService.getTasksByTopic(tid)) {
                                is ApiResult.Success -> result.data ?: emptyList()
                                is ApiResult.Error -> emptyList()
                            }
                        }
                    } else {
                        emptyList()
                    }

                    // Filter by Task if selected
                    if (taskId != -1L) {
                        k = k.filter { it.id == taskId }
                    }

                    // Fetch Content Items (Files) from backend
                    val c = if (taskId != -1L) {
                        val taskItems = when (val result = BackendApiService.getContentItemsByTask(taskId)) {
                            is ApiResult.Success -> result.data ?: emptyList()
                            is ApiResult.Error -> emptyList()
                        }
                        // Also get topic-level items
                        val topicItems = tIds.flatMap { tid ->
                            // Get tasks for this topic, then content items
                            when (val tResult = BackendApiService.getTasksByTopic(tid)) {
                                is ApiResult.Success -> (tResult.data ?: emptyList()).flatMap { task ->
                                    when (val ciResult = BackendApiService.getContentItemsByTask(task.id)) {
                                        is ApiResult.Success -> ciResult.data ?: emptyList()
                                        is ApiResult.Error -> emptyList()
                                    }
                                }
                                is ApiResult.Error -> emptyList()
                            }
                        }
                        val relevantTopicItems = topicItems.filter { it.taskId == null || it.taskId == 0L || it.taskId == taskId }
                        (taskItems + relevantTopicItems).distinctBy { it.id }
                    } else if (tIds.isNotEmpty()) {
                        tIds.flatMap { tid ->
                            when (val tResult = BackendApiService.getTasksByTopic(tid)) {
                                is ApiResult.Success -> (tResult.data ?: emptyList()).flatMap { task ->
                                    when (val ciResult = BackendApiService.getContentItemsByTask(task.id)) {
                                        is ApiResult.Success -> ciResult.data ?: emptyList()
                                        is ApiResult.Error -> emptyList()
                                    }
                                }
                                is ApiResult.Error -> emptyList()
                            }
                        }
                    } else {
                        emptyList()
                    }

                    Triple(t, k, c)
                }

                // Update UI with files being analyzed
                val analyzedFileList = contentItems.map {
                    AnalyzedFile(
                        name = it.name ?: "Archivo sin nombre",
                        url = it.uriString,
                        type = it.contentType
                    )
                }
                _analyzedFiles.value = analyzedFileList

                if (topics.isEmpty() && tasks.isEmpty() && contentItems.isEmpty()) {
                    _uiState.value = ReinforcementState.Error("Este curso no tiene contenido suficiente (temas, tareas o materiales) para generar preguntas.")
                    return@launch
                }

                // ═══════════════════════════════════════════════════════════
                // 🔥 RAG INGESTION: Ensure content_items are ingested into
                // rag_documents BEFORE generating questions.
                // First time: downloads files, chunks, embeds, stores.
                // Second time (same task+topic): skips automatically.
                // ═══════════════════════════════════════════════════════════
                if (taskId > 0) {
                    try {
                        Log.d("ReinforcementVM", "📥 Triggering RAG ingestion for task=$taskId, topic=$topicId, course=$courseId...")
                        val ingestResult = withContext(Dispatchers.IO) {
                            BackendApiService.ingestTaskContent(
                                taskId = taskId,
                                topicId = if (topicId > 0) topicId else null,
                                courseId = if (courseId > 0) courseId else null
                            )
                        }
                        when (ingestResult) {
                            is ApiResult.Success -> {
                                val data = ingestResult.data
                                val skipped = data?.get("skipped")?.asBoolean ?: false
                                if (skipped) {
                                    Log.d("ReinforcementVM", "ℹ️ RAG ingestion skipped (content already exists)")
                                } else {
                                    val filesProcessed = data?.get("filesProcessed")?.asInt ?: 0
                                    val chunksStored = data?.get("totalChunksStored")?.asInt ?: 0
                                    Log.d("ReinforcementVM", "✅ RAG ingestion complete: $filesProcessed files, $chunksStored chunks")
                                }
                            }
                            is ApiResult.Error -> {
                                Log.w("ReinforcementVM", "⚠️ RAG ingestion failed (non-blocking): ${ingestResult.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ReinforcementVM", "⚠️ RAG ingestion error (non-blocking): ${e.message}")
                    }
                }

                // ═══════════════════════════════════════════════════════════
                // 📄 FETCH RAG CONTENT: Get the actual document text from
                // rag_documents to include directly in the prompt.
                // This ensures the LLM only uses verified content (no hallucination).
                // ═══════════════════════════════════════════════════════════
                var ragDocumentContent = ""
                var ragFileNames = emptyList<String>()
                try {
                    Log.d("ReinforcementVM", "📄 Fetching RAG content for grounding (course=$courseId, topic=$topicId, task=$taskId)...")
                    val ragResult = withContext(Dispatchers.IO) {
                        BackendApiService.getRagContent(
                            courseId = courseId,
                            topicId = if (topicId > 0) topicId else null,
                            taskId = if (taskId > 0) taskId else null
                        )
                    }
                    when (ragResult) {
                        is ApiResult.Success -> {
                            val data = ragResult.data
                            ragDocumentContent = data?.get("content")?.asString ?: ""
                            val chunks = data?.get("chunks")?.asInt ?: 0
                            ragFileNames = try {
                                data?.getAsJsonArray("files")?.map { it.asString } ?: emptyList()
                            } catch (_: Exception) { emptyList() }
                            Log.d("ReinforcementVM", "✅ RAG content fetched: ${ragDocumentContent.length} chars, $chunks chunks, ${ragFileNames.size} files")
                        }
                        is ApiResult.Error -> {
                            Log.w("ReinforcementVM", "⚠️ RAG content fetch failed (non-blocking): ${ragResult.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ReinforcementVM", "⚠️ RAG content fetch error (non-blocking): ${e.message}")
                }

                // Fetch existing questions (full objects) to avoid repetition
                val existingQuestionTexts: List<String>
                val existingQuestionsForPrompt: List<String>

                if (userId > 0) {
                    // Use the new endpoint that returns full question objects filtered by topic+task
                    val existingResult = withContext(Dispatchers.IO) {
                        BackendApiService.getExistingQuestions(
                            courseId = courseId,
                            topicId = if (topicId > 0) topicId else null,
                            taskId = if (taskId > 0) taskId else null
                        )
                    }
                    val existingQuestions = when (existingResult) {
                        is ApiResult.Success -> {
                            val data = existingResult.data
                            val questionsArray = data?.getAsJsonArray("questions")
                            questionsArray?.mapNotNull { elem ->
                                val obj = elem.asJsonObject
                                obj?.get("question")?.asString
                            } ?: emptyList()
                        }
                        is ApiResult.Error -> emptyList()
                    }
                    // Merge with any previously excluded questions from retry
                    existingQuestionTexts = (existingQuestions + previouslyExcluded).distinct()
                    existingQuestionsForPrompt = existingQuestionTexts
                    Log.d("ReinforcementVM", "📋 Found ${existingQuestionTexts.size} existing questions for dedup (topic=$topicId, task=$taskId)")
                } else {
                    existingQuestionTexts = previouslyExcluded
                    existingQuestionsForPrompt = previouslyExcluded
                }

                // 3. Build Prompt (Concise)
                val contextBuilder = StringBuilder()
                contextBuilder.append("Curso: $courseName\n")

                // Find selected Topic/Task details for thematic focus
                val selectedTopic = topics.find { it.id == topicId }
                val selectedTask = tasks.find { it.id == taskId }

                if (selectedTopic != null) {
                    _selectedTopicName.value = selectedTopic.name
                    contextBuilder.append("TEMA PRINCIPAL: ${selectedTopic.name}\n")
                    contextBuilder.append("DESCRIPCIÓN DEL TEMA: ${selectedTopic.description}\n")
                } else {
                    _selectedTopicName.value = "General"
                }

                if (selectedTask != null) {
                    _selectedTaskName.value = selectedTask.name
                    contextBuilder.append("TAREA ESPECÍFICA (FOCO CENTRAL): ${selectedTask.name}\n")
                    contextBuilder.append("DESCRIPCIÓN DE LA TAREA: ${selectedTask.description ?: "Sin descripción"}\n")
                } else {
                    _selectedTaskName.value = "General"
                }

                // Include RAG document content directly for strict grounding
                if (ragDocumentContent.isNotBlank()) {
                    contextBuilder.append("\n═══════════════════════════════════════════════════\n")
                    contextBuilder.append("MATERIAL DE REFERENCIA VERIFICADO (rag_documents):\n")
                    contextBuilder.append("═══════════════════════════════════════════════════\n")
                    // Limit to ~12000 chars to avoid excessive prompt size
                    val truncatedContent = if (ragDocumentContent.length > 12000) {
                        ragDocumentContent.take(12000) + "\n[... contenido truncado por longitud ...]"
                    } else {
                        ragDocumentContent
                    }
                    contextBuilder.append(truncatedContent)
                    contextBuilder.append("\n═══════════════════════════════════════════════════\n")
                    if (ragFileNames.isNotEmpty()) {
                        contextBuilder.append("Archivos fuente: ${ragFileNames.joinToString(", ")}\n")
                    }
                } else if (contentItems.isNotEmpty()) {
                    contextBuilder.append("\nMATERIAL DE REFERENCIA (ARCHIVOS ADJUNTOS):\n")
                    contentItems.forEach { item ->
                        contextBuilder.append("- Archivo: ${item.name} (${item.contentType})\n")
                    }
                } else {
                    Log.w("ReinforcementVM", "⚠️ No RAG content nor files found.")
                    contextBuilder.append("\nNOTA: No se encontró material de referencia. Genera preguntas basándote en el nombre y descripción de la tarea/tema.\n")
                }

                if (existingQuestionsForPrompt.isNotEmpty()) {
                    contextBuilder.append("\n\n═══════════════════════════════════════════════════\n")
                    contextBuilder.append("PREGUNTAS YA EXISTENTES EN LA BASE DE DATOS (PROHIBIDO REPETIR O PARAFRASEAR):\n")
                    contextBuilder.append("Cada pregunta nueva DEBE ser semánticamente DISTINTA a TODAS las siguientes:\n")
                    existingQuestionsForPrompt.takeLast(50).forEachIndexed { idx, q ->
                        contextBuilder.append("${idx + 1}. $q\n")
                    }
                    contextBuilder.append("═══════════════════════════════════════════════════\n")
                }

                // Add a unique timestamp to force fresh generation and avoid caching
                contextBuilder.append("\n(Generación ID: ${System.currentTimeMillis()})\n")

                // Serialize content items to JSON for backend processing
                val contentList = contentItems.map {
                    mapOf(
                        "name" to (it.name ?: "Sin nombre"),
                        "uri" to (it.uriString ?: ""),
                        "type" to (it.contentType ?: "application/octet-stream"),
                        "id" to it.id
                    )
                }
                val jsonContentString = Gson().toJson(contentList)
                Log.d("ReinforcementVM", "Enviando ${contentList.size} archivos al backend. JSON: $jsonContentString")

                // Determine grounding instruction based on RAG availability
                val hasRagContent = ragDocumentContent.isNotBlank()
                val groundingInstruction = if (hasRagContent) {
                    """
                    REGLA DE GROUNDING ESTRICTO (ANTI-ALUCINACIÓN):
                    - TODAS las preguntas, respuestas y explicaciones DEBEN basarse EXCLUSIVAMENTE en el MATERIAL DE REFERENCIA VERIFICADO incluido arriba.
                    - Cada respuesta correcta DEBE poder verificarse directamente en el texto del material.
                    - NO inventes, infieras ni añadas información que NO aparezca EXPLÍCITAMENTE en el material.
                    - Si un concepto NO está en el material, NO generes preguntas sobre él.
                    - Las explicaciones DEBEN citar o parafrasear directamente frases del material de referencia.
                    - Genera preguntas variadas que cubran DIFERENTES secciones y conceptos del material.
                    """.trimIndent()
                } else {
                    """
                    FUENTE DE INFORMACIÓN:
                    Genera preguntas basándote en el nombre y descripción de la tarea/tema proporcionados.
                    Los documentos RAG asociados serán procesados por el backend.
                    """.trimIndent()
                }

                val prompt = """
                    Eres un profesor experto generando preguntas de repaso.
                    
                    OBJETIVO: Generar EXACTAMENTE 10 preguntas de opción múltiple basadas en el material de referencia.
                    
                    TEMÁTICA:
                    - TAREA: "${selectedTask?.name ?: "General"}"
                    - TEMA: "${selectedTopic?.name ?: ""}"
                    
                    $groundingInstruction
                    
                    DISTRIBUCIÓN DE DIFICULTAD (10 PREGUNTAS):
                    - 3 Introductorias (conceptos y definiciones del material)
                    - 4 Técnicas (detalles específicos, procesos o datos del material)
                    - 3 Avanzadas (relaciones entre conceptos, análisis o aplicaciones del material)
                    
                    RESTRICCIONES:
                    1. Genera EXACTAMENTE 10 preguntas. Ni una menos.
                    2. PROHIBIDO calificar, evaluar o dar feedback. Solo genera preguntas.
                    3. Tu ÚNICA salida debe ser el array JSON.
                    4. Cada pregunta debe ser semánticamente DISTINTA a las existentes (${existingQuestionsForPrompt.size} previas).
                    5. Si hay preguntas existentes, aborda aspectos DIFERENTES del material no cubiertos.
                    
                    FORMATO JSON ESTRICTO (completa CADA objeto antes del siguiente):
                    [
                      {"question": "¿Pregunta?", "options": ["A", "B", "C", "D"], "correctIndex": 2, "explanation": "Según el material: [cita o paráfrasis del documento]"},
                      {"question": "¿Pregunta?", "options": ["A", "B", "C", "D"], "correctIndex": 0, "explanation": "El documento establece que: [referencia directa]"}
                    ]
                    - Cada objeto COMPLETO antes de iniciar el siguiente
                    - Campos en ESTE ORDEN: question, options, correctIndex, explanation
                    - Varía correctIndex (0, 1, 2 o 3)
                    - Genera exactamente 10 objetos
                    
                    Contexto:
                    $contextBuilder
                """.trimIndent()

                // 4. Call LLM via Backend
                Log.d("ReinforcementVM", "Invocando MicroservicioPromptRequest con userId=$userId, courseId=$courseId, topicId=$topicId, taskId=$taskId")

                // STRICT BUILD VARIANT ROUTING: Call ONLY the cloud API matching this build variant
                // No local network scanning — QA build → QA server, Production build → Production server
                var jsonText: String? = null
                var lastError: String? = null

                val candidateBaseUrls = linkedSetOf(
                    normalizeBaseUrl(BASE_URL),
                    normalizeBaseUrl(BackendApiService.baseUrl)
                ).apply {
                    FALLBACK_BACKEND_URLS.mapTo(this) { normalizeBaseUrl(it) }
                }.toList()

                Log.d("ReinforcementVM", "🔒 Strict routing with safe fallback hosts: $candidateBaseUrls")

                for (candidateBaseUrl in candidateBaseUrls) {
                    if (!jsonText.isNullOrBlank()) break

                    try {
                        Log.d("ReinforcementVM", "➡️ Attempting /procesar-prompt on $candidateBaseUrl")

                        val api = createApi(candidateBaseUrl)
                        val requestBody = MicroservicioPromptRequest(
                            prompt = prompt,
                            jsonContent = jsonContentString,
                            ollamaUrl = candidateBaseUrl.trimEnd('/'),
                            model = "qwen/qwen3-embedding-8b",
                            userId = if (userId > 0) userId else null,
                            courseId = if (courseId > 0) courseId else null,
                            topicId = if (topicId > -1L) topicId else null,
                            taskId = if (taskId > -1L) taskId else null
                        )

                        val cloudRespWrapper = withContext(Dispatchers.IO) { api.procesarPrompt(requestBody) }

                        if (!cloudRespWrapper.success || cloudRespWrapper.data == null) {
                            lastError = cloudRespWrapper.error ?: "Unknown error from server wrapper"
                            Log.e("ReinforcementVM", "API wrapper invalid on $candidateBaseUrl: $lastError")
                            continue
                        }

                        val cloudResp = cloudRespWrapper.data
                        jsonText = cloudResp.respuesta_texto
                        lastError = cloudResp.error
                        Log.d("ReinforcementVM", "✅ API responded on $candidateBaseUrl; response error=${cloudResp.error}")
                    } catch (e: Exception) {
                        lastError = e.message
                        val isDnsError = e is UnknownHostException ||
                            e.cause is UnknownHostException ||
                            (e.message?.contains("Unable to resolve host", ignoreCase = true) == true)

                        if (isDnsError) {
                            Log.w("ReinforcementVM", "⚠️ DNS failure on $candidateBaseUrl, trying fallback host. Error=${e.message}")
                        } else {
                            Log.e("ReinforcementVM", "❌ API call failed on $candidateBaseUrl: ${e.message}")
                        }
                    }
                }

                if (jsonText.isNullOrBlank()) {
                    Log.e("ReinforcementVM", "Respuesta del servidor vacía o nula. Error: ${lastError}")
                    throw Exception("El servidor devolvió una respuesta vacía: ${lastError}")
                }

                Log.d("ReinforcementVM", "Raw LLM response: $jsonText")

                // Robust JSON extraction
                val startIndex = jsonText.indexOf('[')
                val endIndex = jsonText.lastIndexOf(']')

                var questions: List<QuizQuestion> = emptyList()

                if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
                    var cleanJson = jsonText.substring(startIndex, endIndex + 1)
                    
                    // First attempt: parse as-is
                    try {
                        val type = object : TypeToken<List<QuizQuestion>>() {}.type
                        val rawQuestions: List<QuizQuestion>? = Gson().fromJson(cleanJson, type)
                        
                        // CRITICAL: Sanitize explanation field - Gson may set it to null even with default value
                        questions = rawQuestions?.map { q ->
                            val safeExplanation = when {
                                q.explanation == null || q.explanation == "null" || q.explanation.isBlank() -> {
                                    val correctOpt = q.options.getOrElse(q.correctIndex) { "la opción correcta" }
                                    Log.w("ReinforcementVM", "🔧 Auto-generating explanation for: ${q.question.take(50)}...")
                                    "La respuesta correcta es: \"$correctOpt\". Esta explicación fue generada automáticamente."
                                }
                                else -> q.explanation
                            }
                            q.copy(explanation = safeExplanation)
                        } ?: emptyList()
                        
                        Log.d("ReinforcementVM", "✅ Parsed ${questions.size} questions, all with valid explanations")
                    } catch (e: Exception) {
                        Log.w("ReinforcementVM", "Failed to parse JSON directly: ${e.message}")
                        Log.d("ReinforcementVM", "Attempting JSON repair...")
                    }
                    
                    // Second attempt: repair malformed JSON by extracting fields with regex
                    if (questions.size < 2) {
                        try {
                            val repairedQuestions = repairMalformedQuizJson(cleanJson)
                            if (repairedQuestions.size > questions.size) {
                                Log.d("ReinforcementVM", "🔧 JSON repair recovered ${repairedQuestions.size} questions (was ${questions.size})")
                                questions = repairedQuestions.map { q ->
                                    val safeExplanation = when {
                                        q.explanation == null || q.explanation == "null" || q.explanation.isNullOrBlank() -> {
                                            val correctOpt = q.options.getOrElse(q.correctIndex) { "la opción correcta" }
                                            "La respuesta correcta es: \"$correctOpt\". Esta explicación fue generada automáticamente."
                                        }
                                        else -> q.explanation
                                    }
                                    q.copy(explanation = safeExplanation)
                                }
                            }
                        } catch (repairEx: Exception) {
                            Log.w("ReinforcementVM", "JSON repair also failed: ${repairEx.message}")
                        }
                    }
                }

                // If parsing failed, returned empty (deduplication), or no JSON found -> Use Fallback
                if (questions.isEmpty()) {
                    Log.w("ReinforcementVM", "No valid questions from LLM (empty or parse error); using fallback. Raw: $jsonText")

                    // FALLBACK GENERATOR IMPROVED
                    val fallback = mutableListOf<QuizQuestion>()
                    val seeds: List<String> = when {
                        tasks.isNotEmpty() && taskId != -1L -> tasks.filter { it.id == taskId }.map { it.name }
                        topics.isNotEmpty() && topicId != -1L -> topics.filter { it.id == topicId }.map { it.name }
                        else -> emptyList()
                    }

                    val effectiveSeeds = if (seeds.isNotEmpty()) seeds else listOf("Conceptos Generales", "Fundamentos", "Práctica", "Teoría", "Análisis")

                    // Generate exactly 10 fallback questions if possible
                    for (i in 1..10) {
                        val seedIndex = (i - 1) % effectiveSeeds.size
                        val rawSeed = effectiveSeeds[seedIndex].trim()
                        val seedLabel = if (rawSeed.isEmpty()) "este tema" else rawSeed

                        // Randomize content to avoid "preguntas iguales"
                        val uniqueSuffix = (System.nanoTime() % 1000).toString()

                        val variants = listOf(
                            "Considerando '$seedLabel', ¿cuál es su propósito principal?",
                            "En el ámbito de '$seedLabel', selecciona la afirmación verdadera:",
                            "Analiza el concepto de '$seedLabel' y elige la opción correcta:",
                            "¿Qué elemento es crucial para entender '$seedLabel'?",
                            "Desde una perspectiva técnica, ¿cómo se define mejor '$seedLabel'?"
                        )
                        val questionText = "${variants[i % variants.size]} (Ref: $uniqueSuffix)"

                        val correctOption = "Definición técnica precisa sobre $seedLabel"
                        val distractorBase = listOf(
                            "Concepto erróneo común sobre $seedLabel",
                            "Información no relacionada directamente",
                            "Definición opuesta al concepto",
                            "Detalle superficial irrelevante"
                        )

                        val options = mutableListOf<String>()
                        options.add(correctOption)
                        options.addAll(distractorBase.shuffled().take(3))
                        options.shuffle()

                        val correctIndex = options.indexOf(correctOption)

                        fallback.add(QuizQuestion(
                            question = questionText,
                            options = options,
                            correctIndex = correctIndex,
                            explanation = "La respuesta correcta es '${options[correctIndex]}' porque es la definición técnica más precisa sobre $seedLabel en este contexto."
                        ))
                    }
                    questions = fallback
                }

                if (questions.isEmpty()) {
                    _uiState.value = ReinforcementState.Error("El modelo no generó preguntas válidas y no hay contenido suficiente para el respaldo.")
                } else {
                    val sanitizedGenerated = sanitizeQuestionsForUniqueness(questions)

                    // ── Local dedup: filter against existing questions ──
                    val uniqueQuestions = filterDuplicateQuestions(sanitizedGenerated, existingQuestionTexts)
                    Log.d("ReinforcementVM", "🔍 Local dedup: ${sanitizedGenerated.size} generated → ${uniqueQuestions.size} unique (${sanitizedGenerated.size - uniqueQuestions.size} local dupes removed)")

                    // Retry if we still don't have a full set of 10 unique questions.
                    if (uniqueQuestions.size < 10 && retryAttempt < 2) {
                        Log.w("ReinforcementVM", "⚠️ Only ${uniqueQuestions.size}/10 unique questions after local dedup. Retrying (attempt ${retryAttempt + 1})...")
                        val allExcluded = (existingQuestionTexts + sanitizedGenerated.map { it.question }).distinct()
                        loadQuestionsInternal(courseId, courseName, topicId, taskId, retryAttempt + 1, allExcluded)
                        return@launch
                    }

                    val selectedTask = tasks.find { it.id == taskId }
                    val selectedTopic = topics.find { it.id == topicId }
                    val fallbackSeeds = listOfNotNull(selectedTask?.name, selectedTopic?.name).ifEmpty {
                        listOf("Conceptos Generales", "Fundamentos", "Práctica", "Teoría", "Análisis")
                    }

                    var finalQuestions = uniqueQuestions.take(10)

                    if (finalQuestions.size < 10) {
                        val fallbackCandidates = sanitizeQuestionsForUniqueness(generateFallbackQuestionsFromSeeds(fallbackSeeds))
                        val combinedExisting = (existingQuestionTexts + finalQuestions.map { it.question }).distinct()
                        val fallbackUnique = filterDuplicateQuestions(fallbackCandidates, combinedExisting)
                        finalQuestions = (finalQuestions + fallbackUnique)
                            .distinctBy { normalizeForComparison(it.question) }
                            .take(10)
                    }

                    if (finalQuestions.size < 10) {
                        _uiState.value = ReinforcementState.Error("No fue posible construir 10 preguntas únicas. Intenta nuevamente.")
                        clearPendingTaskData()
                        return@launch
                    }

                    // ── Save to backend BEFORE showing to user (to catch server-side duplicates) ──
                    var shouldRetry = false
                    var retryExcluded = emptyList<String>()
                    
                    if (userId > 0) {
                        try {
                            // NonCancellable: save must complete even if user navigates away
                            val saveResult = withContext(NonCancellable + Dispatchers.IO) {
                                BackendApiService.saveReinforcementSession(
                                    userId,
                                    courseId,
                                    finalQuestions.map { q ->
                                        mapOf(
                                            "question" to q.question,
                                            "options" to q.options,
                                            "correctIndex" to q.correctIndex,
                                            "explanation" to (q.explanation ?: "")
                                        )
                                    },
                                    topicId = if (topicId > 0) topicId else null,
                                    taskId = if (taskId > 0) taskId else null
                                )
                            }

                            when (saveResult) {
                                is ApiResult.Success -> {
                                    val data = saveResult.data
                                    val savedCount = data?.get("savedCount")?.asInt ?: 0
                                    val allDuplicates = data?.get("allDuplicates")?.asBoolean ?: false
                                    val requiredCount = data?.get("requiredCount")?.asInt ?: 10
                                    val isShortBatch = savedCount < requiredCount

                                    if ((allDuplicates || isShortBatch) && retryAttempt < 2) {
                                        Log.w("ReinforcementVM", "⚠️ Backend accepted only $savedCount/$requiredCount unique questions. Retrying (attempt ${retryAttempt + 1})...")
                                        val backendExisting = try {
                                            data?.getAsJsonArray("existingQuestions")?.mapNotNull { elem ->
                                                elem.asJsonObject?.get("question")?.asString
                                            } ?: emptyList()
                                        } catch (_: Exception) { emptyList() }
                                        retryExcluded = (existingQuestionTexts + finalQuestions.map { it.question } + backendExisting).distinct()
                                        shouldRetry = true
                                    } else {
                                        Log.d("ReinforcementVM", "✅ Saved $savedCount/$requiredCount questions via /save-questions")
                                    }
                                }
                                is ApiResult.Error -> {
                                    Log.w("ReinforcementVM", "⚠️ Save to /save-questions failed: ${saveResult.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("ReinforcementVM", "⚠️ Save to /save-questions failed: ${e.message}")
                        }
                    }

                    // If backend flagged duplicates, retry BEFORE showing questions to user
                    if (shouldRetry) {
                        loadQuestionsInternal(courseId, courseName, topicId, taskId, retryAttempt + 1, retryExcluded)
                        return@launch
                    }

                    // All checks passed — show unique questions to the user
                    _uiState.value = ReinforcementState.Success(finalQuestions)
                }
                
                // Clear pending data - task completed successfully
                clearPendingTaskData()

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled (user navigated away) — do NOT show error
                Log.w("ReinforcementVM", "Question loading cancelled (navigation or scope cleared)")
                clearPendingTaskData()
                throw e // Re-throw to respect structured concurrency
            } catch (e: Exception) {
                Log.e("ReinforcementVM", "Error loading questions", e)
                _uiState.value = ReinforcementState.Error("Error: ${e.message}")
                clearPendingTaskData()
            }
        }
    }

    fun loadPreloadedQuestions(questionsJson: String) {
        viewModelScope.launch {
            _uiState.value = ReinforcementState.Loading
            try {
                // Try parsing as a list of QuizQuestion
                var questions: List<QuizQuestion> = try {
                    Gson().fromJson(questionsJson, object : TypeToken<List<QuizQuestion>>() {}.type)
                } catch (_: Exception) { null } ?: emptyList()

                // If it's not a list, try parsing as an object with a `questions` field
                if (questions.isEmpty()) {
                    try {
                        val root = com.google.gson.JsonParser.parseString(questionsJson).asJsonObject
                        if (root.has("questions")) {
                            val arr = root.getAsJsonArray("questions")
                            questions = Gson().fromJson(arr, object : TypeToken<List<QuizQuestion>>() {}.type) ?: emptyList()
                        }
                    } catch (_: Exception) {
                        // ignore
                    }
                }

                // Sanitize explanations and ensure options/correctIndex validity
                questions = questions.mapNotNull { q ->
                    try {
                        val opts = q.options ?: listOf("Opción A", "Opción B", "Opción C", "Opción D")
                        val idx = when {
                            q.correctIndex < 0 -> 0
                            q.correctIndex >= opts.size -> 0
                            else -> q.correctIndex
                        }
                        val safeExplanation = when {
                            q.explanation.isNullOrBlank() || q.explanation == "null" -> {
                                val correctOpt = opts.getOrElse(idx) { "la opción correcta" }
                                "La respuesta correcta es: \"$correctOpt\". Explicación auto-generada."
                            }
                            else -> q.explanation
                        }
                        QuizQuestion(q.question ?: "Pregunta sin texto", opts, idx, safeExplanation)
                    } catch (e: Exception) {
                        null
                    }
                }

                questions = sanitizeQuestionsForUniqueness(questions).take(10)

                if (questions.size < 10) {
                    val seeds = questions.map { it.question }.ifEmpty { listOf("Conceptos Generales", "Fundamentos", "Práctica", "Teoría", "Análisis") }
                    val fallback = sanitizeQuestionsForUniqueness(generateFallbackQuestionsFromSeeds(seeds))
                    val combined = (questions + fallback)
                        .distinctBy { normalizeForComparison(it.question) }
                        .take(10)
                    questions = combined
                }

                if (questions.size < 10) {
                    Log.w("ReinforcementVM", "No fue posible completar 10 preguntas únicas en datos pre-cargados; regenerando respaldo.")
                    val seeds = listOf("Conceptos Generales", "Fundamentos", "Práctica", "Teoría", "Análisis")
                    questions = sanitizeQuestionsForUniqueness(generateFallbackQuestionsFromSeeds(seeds)).take(10)
                }

                if (questions.size < 10) {
                    _uiState.value = ReinforcementState.Error("No fue posible reconstruir 10 preguntas únicas desde datos pre-cargados.")
                } else {
                    _uiState.value = ReinforcementState.Success(questions)
                }
            } catch (e: Exception) {
                Log.e("ReinforcementVM", "Error parsing preloaded questions", e)
                _uiState.value = ReinforcementState.Error("Error al cargar preguntas pre-cargadas: ${e.message}")
            }
        }
    }

    private fun generateFallbackQuestionsFromSeeds(seeds: List<String>): List<QuizQuestion> {
        val fallback = mutableListOf<QuizQuestion>()
        val effectiveSeeds = if (seeds.isNotEmpty()) seeds else listOf("Tema")
        for (i in 1..10) {
            val seedIndex = (i - 1) % effectiveSeeds.size
            val rawSeed = effectiveSeeds[seedIndex].trim()
            val seedLabel = if (rawSeed.isEmpty()) "este tema" else rawSeed
            val uniqueSuffix = (System.nanoTime() % 1000).toString()
            val variants = listOf(
                "Considerando '$seedLabel', ¿cuál es su propósito principal?",
                "En el ámbito de '$seedLabel', selecciona la afirmación verdadera:",
                "Analiza el concepto de '$seedLabel' y elige la opción correcta:",
                "¿Qué elemento es crucial para entender '$seedLabel'?",
                "Desde una perspectiva técnica, ¿cómo se define mejor '$seedLabel'?"
            )
            val questionText = "${variants[i % variants.size]} (Ref: $uniqueSuffix)"
            val correctOption = "Definición técnica precisa sobre $seedLabel"
            val distractorBase = listOf(
                "Concepto erróneo común sobre $seedLabel",
                "Información no relacionada directamente",
                "Interpretación parcial o incompleta",
                "Ejemplo práctico simplificado"
            )
            val options = listOf(correctOption) + distractorBase.shuffled().take(3)
            val shuffled = options.shuffled()
            val correctIndex = shuffled.indexOfFirst { it == correctOption }.coerceAtLeast(0)
            val explanation = "La respuesta correcta es: \"$correctOption\". Esta explicación fue generada automáticamente."
            fallback.add(QuizQuestion(questionText, shuffled, correctIndex, explanation))
        }
        return fallback
    }

    private fun sanitizeQuestionsForUniqueness(questions: List<QuizQuestion>): List<QuizQuestion> {
        if (questions.isEmpty()) return emptyList()

        val sanitized = mutableListOf<QuizQuestion>()
        val seenQuestions = mutableSetOf<String>()

        for (question in questions) {
            val normalizedQuestion = normalizeForComparison(question.question)
            if (normalizedQuestion.isBlank() || seenQuestions.contains(normalizedQuestion)) {
                continue
            }

            val rawOptions = question.options
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val uniqueOptions = rawOptions
                .distinctBy { normalizeForComparison(it) }

            if (uniqueOptions.size < 4) {
                continue
            }

            val trimmedOptions = uniqueOptions.take(4)
            val originalCorrect = question.options.getOrNull(question.correctIndex)?.trim()
            val normalizedCorrect = originalCorrect?.let { normalizeForComparison(it) }

            val resolvedCorrectIndex = trimmedOptions.indexOfFirst { normalizeForComparison(it) == normalizedCorrect }
                .let { if (it >= 0) it else 0 }

            val safeExplanation = when {
                question.explanation.isNullOrBlank() || question.explanation == "null" -> {
                    val correctOpt = trimmedOptions.getOrElse(resolvedCorrectIndex) { "la opción correcta" }
                    "La respuesta correcta es: \"$correctOpt\". Explicación auto-generada."
                }
                else -> question.explanation
            }

            sanitized.add(
                QuizQuestion(
                    question = question.question.trim(),
                    options = trimmedOptions,
                    correctIndex = resolvedCorrectIndex,
                    explanation = safeExplanation
                )
            )
            seenQuestions.add(normalizedQuestion)
        }

        return sanitized
    }

    /**
     * Filters generated questions against existing ones using text similarity.
     * Uses normalized Jaccard similarity on word sets to detect semantic duplicates
     * beyond exact string matching.
     * @param generated list of newly generated questions
     * @param existingTexts list of existing question text strings from DB
     * @return filtered list containing only questions that are sufficiently distinct
     */
    private fun filterDuplicateQuestions(
        generated: List<QuizQuestion>,
        existingTexts: List<String>
    ): List<QuizQuestion> {
        if (existingTexts.isEmpty()) return generated

        val existingNormalized = existingTexts.map { normalizeForComparison(it) }

        return generated.filter { q ->
            val normalizedQ = normalizeForComparison(q.question)
            val isDuplicate = existingNormalized.any { existing ->
                // Exact match
                if (normalizedQ == existing) return@any true
                // Jaccard similarity on word sets (threshold 0.70 = 70% word overlap)
                val similarity = jaccardSimilarity(normalizedQ, existing)
                similarity >= 0.70
            }
            if (isDuplicate) {
                Log.d("ReinforcementVM", "🚫 Local dup filtered: ${q.question.take(60)}...")
            }
            !isDuplicate
        }
    }

    /** Normalize text for comparison: lowercase, remove punctuation, trim */
    private fun normalizeForComparison(text: String): String {
        return text.lowercase()
            .replace(Regex("[¿¡?!.,;:\"'()\\[\\]{}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /** Jaccard similarity coefficient on word sets */
    private fun jaccardSimilarity(a: String, b: String): Double {
        val setA = a.split(" ").filter { it.length > 2 }.toSet()
        val setB = b.split(" ").filter { it.length > 2 }.toSet()
        if (setA.isEmpty() && setB.isEmpty()) return 1.0
        if (setA.isEmpty() || setB.isEmpty()) return 0.0
        val intersection = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        return if (union > 0) intersection / union else 0.0
    }

    /**
     * Repairs malformed quiz JSON from LLM responses.
     * Uses position-aware field extraction: groups fields by proximity to their
     * nearest "question" field, correctly reconstructing objects even when
     * fields from different questions are interleaved.
     *
     * @param jsonText The raw JSON string (should start with '[' and end with ']')
     * @return List of reconstructed QuizQuestion objects
     */
    private fun repairMalformedQuizJson(jsonText: String): List<QuizQuestion> {
        Log.d("ReinforcementVM", "🔧 repairMalformedQuizJson: Position-aware extraction from malformed JSON")

        data class FieldMatch(val type: String, val value: String, val pos: Int)

        val fieldMatches = mutableListOf<FieldMatch>()

        // Extract all fields with their positions in the text
        val questionPattern = Regex(""""question"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val optionsPattern = Regex(""""options"\s*:\s*\[((?:[^\]]*?))\]""")
        val correctIndexPattern = Regex(""""correctIndex"\s*:\s*(\d+)""")
        val explanationPattern = Regex(""""explanation"\s*:\s*"((?:[^"\\]|\\.)*)"""")

        questionPattern.findAll(jsonText).forEach {
            fieldMatches.add(FieldMatch("question", it.groupValues[1], it.range.first))
        }
        optionsPattern.findAll(jsonText).forEach {
            fieldMatches.add(FieldMatch("options", it.groupValues[1], it.range.first))
        }
        correctIndexPattern.findAll(jsonText).forEach {
            fieldMatches.add(FieldMatch("correctIndex", it.groupValues[1], it.range.first))
        }
        explanationPattern.findAll(jsonText).forEach {
            fieldMatches.add(FieldMatch("explanation", it.groupValues[1], it.range.first))
        }

        // Sort by position in text
        fieldMatches.sortBy { it.pos }

        val questionCount = fieldMatches.count { it.type == "question" }
        Log.d("ReinforcementVM", "🔧 Found fields: total=${fieldMatches.size}, questions=$questionCount")

        if (questionCount < 2) {
            Log.w("ReinforcementVM", "🔧 Not enough question fields found for repair ($questionCount)")
            return emptyList()
        }

        // Group fields into question objects using question boundaries
        data class QuestionBuilder(
            var question: String,
            var options: String? = null,
            var correctIndex: Int? = null,
            var explanation: String? = null
        )

        val questionObjects = mutableListOf<QuestionBuilder>()
        var current: QuestionBuilder? = null

        for (field in fieldMatches) {
            when (field.type) {
                "question" -> {
                    // Save previous question if it exists
                    current?.let { questionObjects.add(it) }
                    current = QuestionBuilder(question = field.value)
                }
                "options" -> {
                    if (current != null && current!!.options == null) {
                        current!!.options = field.value
                    }
                }
                "correctIndex" -> {
                    if (current != null && current!!.correctIndex == null) {
                        current!!.correctIndex = field.value.toIntOrNull() ?: 0
                    }
                }
                "explanation" -> {
                    if (current != null && current!!.explanation == null) {
                        current!!.explanation = field.value
                    }
                }
            }
        }
        // Push the last question
        current?.let { questionObjects.add(it) }

        // Build QuizQuestion list
        val repaired = questionObjects.map { qb ->
            val questionText = qb.question
                .replace("\\\"", "\"")
                .replace("\\n", "\n")

            val options = try {
                val type = object : TypeToken<List<String>>() {}.type
                Gson().fromJson<List<String>>("[${qb.options ?: "\"A\",\"B\",\"C\",\"D\""}]", type)
            } catch (e: Exception) {
                listOf("A", "B", "C", "D")
            }

            val correctIndex = (qb.correctIndex ?: 0).coerceIn(0, options.size - 1)

            val explanation = (qb.explanation ?: "")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .ifBlank {
                    "La respuesta correcta es: \"${options.getOrElse(correctIndex) { "la opción correcta" }}\". Explicación auto-generada."
                }

            QuizQuestion(
                question = questionText,
                options = options,
                correctIndex = correctIndex,
                explanation = explanation
            )
        }

        Log.d("ReinforcementVM", "🔧 Successfully repaired ${repaired.size} questions (position-aware grouping)")
        return repaired
    }

    fun addScore(points: Int) {
        _currentScore.value += points
    }

    fun resetScore() {
        _currentScore.value = 0
    }

    /**
     * Reset the UI state to Initial - used when quiz is completed
     * to allow the user to generate new questions
     */
    fun resetToInitial() {
        Log.d("ReinforcementVM", "🔄 resetToInitial() - Resetting state to Initial for new question generation")
        _uiState.value = ReinforcementState.Initial
        _currentScore.value = 0
    }

    /**
     * Force regeneration of questions - clears previous state and generates new questions
     * This is called when user finishes a quiz and wants to generate new questions
     */
    fun forceRegenerateQuestions(courseId: Long, courseName: String, topicId: Long = -1L, taskId: Long = -1L) {
        Log.d("ReinforcementVM", "═══════════════════════════════════════════════════")
        Log.d("ReinforcementVM", "🔄 forceRegenerateQuestions() CALLED!")
        Log.d("ReinforcementVM", "   📚 courseId: $courseId")
        Log.d("ReinforcementVM", "   📖 courseName: $courseName")
        Log.d("ReinforcementVM", "   📑 topicId: $topicId")
        Log.d("ReinforcementVM", "   📝 taskId: $taskId")
        Log.d("ReinforcementVM", "═══════════════════════════════════════════════════")
        
        // 1. Reset state to Initial to clear previous questions
        _uiState.value = ReinforcementState.Initial
        
        // 2. Reset score
        _currentScore.value = 0
        
        // 3. Clear analyzed files to force refresh
        _analyzedFiles.value = emptyList()
        
        // 4. Generate new questions by calling loadQuestions
        // This will make a fresh call to the backend
        loadQuestions(courseId, courseName, topicId, taskId)
    }

    /**
     * Load context information (topic, task, files) without generating questions
     */
    fun loadContextInfo(courseId: Long, topicId: Long = -1L, taskId: Long = -1L) {
        viewModelScope.launch {
            try {
                // Fetch Context from BackendApiService
                val (topics, tasks, contentItems) = withContext(Dispatchers.IO) {
                    var t = when (val result = BackendApiService.getTopicsByCourse(courseId)) {
                        is ApiResult.Success -> result.data ?: emptyList()
                        is ApiResult.Error -> emptyList()
                    }

                    // Filter by Topic if selected
                    if (topicId != -1L) {
                        t = t.filter { it.id == topicId }
                    }

                    val tIds = t.map { it.id }
                    var k = if (tIds.isNotEmpty()) {
                        tIds.flatMap { tid ->
                            when (val result = BackendApiService.getTasksByTopic(tid)) {
                                is ApiResult.Success -> result.data ?: emptyList()
                                is ApiResult.Error -> emptyList()
                            }
                        }
                    } else {
                        emptyList()
                    }

                    // Filter by Task if selected
                    if (taskId != -1L) {
                        k = k.filter { it.id == taskId }
                    }

                    // Fetch Content Items (Files) from backend
                    val c = if (taskId != -1L) {
                        val taskItems = when (val result = BackendApiService.getContentItemsByTask(taskId)) {
                            is ApiResult.Success -> result.data ?: emptyList()
                            is ApiResult.Error -> emptyList()
                        }
                        val topicItems = tIds.flatMap { tid ->
                            when (val tResult = BackendApiService.getTasksByTopic(tid)) {
                                is ApiResult.Success -> (tResult.data ?: emptyList()).flatMap { task ->
                                    when (val ciResult = BackendApiService.getContentItemsByTask(task.id)) {
                                        is ApiResult.Success -> ciResult.data ?: emptyList()
                                        is ApiResult.Error -> emptyList()
                                    }
                                }
                                is ApiResult.Error -> emptyList()
                            }
                        }
                        val relevantTopicItems = topicItems.filter { it.taskId == null || it.taskId == 0L || it.taskId == taskId }
                        (taskItems + relevantTopicItems).distinctBy { it.id }
                    } else if (tIds.isNotEmpty()) {
                        tIds.flatMap { tid ->
                            when (val tResult = BackendApiService.getTasksByTopic(tid)) {
                                is ApiResult.Success -> (tResult.data ?: emptyList()).flatMap { task ->
                                    when (val ciResult = BackendApiService.getContentItemsByTask(task.id)) {
                                        is ApiResult.Success -> ciResult.data ?: emptyList()
                                        is ApiResult.Error -> emptyList()
                                    }
                                }
                                is ApiResult.Error -> emptyList()
                            }
                        }
                    } else {
                        emptyList()
                    }

                    Triple(t, k, c)
                }

                // Update context information
                val analyzedFileList = contentItems.map {
                    AnalyzedFile(
                        name = it.name ?: "Archivo sin nombre",
                        url = it.uriString,
                        type = it.contentType
                    )
                }
                _analyzedFiles.value = analyzedFileList

                // Set topic and task names
                val selectedTopic = topics.find { it.id == topicId }
                val selectedTask = tasks.find { it.id == taskId }

                _selectedTopicName.value = selectedTopic?.name ?: "No seleccionado (General)"
                _selectedTaskName.value = selectedTask?.name ?: "No seleccionada (General)"

                Log.d("ReinforcementVM", "Context loaded: Topic=${_selectedTopicName.value}, Task=${_selectedTaskName.value}, Files=${analyzedFileList.size}")
            } catch (e: Exception) {
                Log.e("ReinforcementVM", "Error loading context info", e)
            }
        }
    }
    
    /**
     * Clear pending task data after completion
     */
    private fun clearPendingTaskData() {
        isGeneratingQuestions = false
        pendingCourseId = -1L
        pendingCourseName = ""
        pendingTopicId = -1L
        pendingTaskId = -1L
    }
    
    /**
     * Schedule task to continue in background if app is being stopped
     * Call this from Fragment's onStop()
     */
    fun scheduleBackgroundTaskIfNeeded() {
        if (isGeneratingQuestions && pendingCourseId > 0) {
            Log.d("ReinforcementVM", "🔄 Scheduling reinforcement task to continue in background")
            
            val sessionManager = com.example.tareamov.util.SessionManager.getInstance(getApplication())
            val userId = sessionManager.getUserId() ?: -1L
            val username = sessionManager.getUsername() ?: "unknown"
            
            if (userId > 0) {
                BackgroundTaskManager.scheduleReinforcementQuestions(
                    context = getApplication(),
                    courseId = pendingCourseId,
                    courseName = pendingCourseName,
                    topicId = pendingTopicId,
                    taskId = pendingTaskId,
                    userId = userId,
                    username = username
                )
            }
            
            clearPendingTaskData()
        }
    }
    
    /**
     * Check for pending background results when app resumes
     * @return true if results were loaded from background
     */
    fun checkForPendingBackgroundResults(courseId: Long): Boolean {
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(getApplication())
        val userId = sessionManager.getUserId() ?: return false
        
        val pendingJson = BackgroundTaskManager.getPendingReinforcementQuestions(
            getApplication(), userId, courseId
        )
        
        if (pendingJson != null) {
            Log.d("ReinforcementVM", "📬 Found pending background questions, loading...")
            loadPreloadedQuestions(pendingJson)
            BackgroundTaskManager.clearReinforcementQuestions(getApplication(), userId, courseId)
            return true
        }
        
        return false
    }
}
