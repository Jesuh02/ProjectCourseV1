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
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.random.Random
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
    private val QA_LOCKED_BACKEND_URL = "https://mcp-backenddeploy-production-4ed0.up.railway.app/"
    private val isQaBuild: Boolean by lazy {
        com.example.tareamov.BuildConfig.FLAVOR.equals("qa", ignoreCase = true) ||
            com.example.tareamov.BuildConfig.APPLICATION_ID.endsWith(".qa")
    }
    private val BASE_URL: String by lazy {
        val configured = com.example.tareamov.BuildConfig.BACKEND_URL.ifBlank {
            if (isQaBuild) QA_LOCKED_BACKEND_URL else "https://mcp-backenddeploy-production.up.railway.app"
        }
        val normalized = normalizeBaseUrl(configured)
        if (isQaBuild) enforceQaBackendUrl(normalized) else normalized
    }
    private val OLLAMA_URL = BASE_URL.trimEnd('/')

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
        if (trimmed.isBlank()) return ""
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun enforceQaBackendUrl(url: String): String {
        val locked = normalizeBaseUrl(QA_LOCKED_BACKEND_URL)
        val candidate = normalizeBaseUrl(url)
        return if (candidate == locked) {
            locked
        } else {
            Log.w("ReinforcementVM", "QA backend override blocked: $candidate -> $locked")
            locked
        }
    }

    private fun createApi(baseUrl: String = BASE_URL, extendedTimeout: Boolean = false): MicroservicioApi {
        val effectiveBase = normalizeBaseUrl(baseUrl)

        // LLM generation can take 2-4 min; use extended timeout on retry after SocketTimeoutException
        val readTimeoutSec = if (extendedTimeout) 300L else 180L
        val writeTimeoutSec = if (extendedTimeout) 300L else 180L

        val okHttpClient = OkHttpClient.Builder()
            .dns(FallbackDnsResolver)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(readTimeoutSec, TimeUnit.SECONDS)
            .writeTimeout(writeTimeoutSec, TimeUnit.SECONDS)
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

    private var currentDifficulty: String = "HARD"
    private var isFreeLearning: Boolean = false

    fun setDifficulty(difficulty: String) {
        currentDifficulty = difficulty
    }

    fun setFreeLearning(enabled: Boolean) {
        isFreeLearning = enabled
    }

    fun loadQuestions(courseId: Long, courseName: String, topicId: Long = -1L, taskId: Long = -1L) {
        loadQuestionsInternal(courseId, courseName, topicId, taskId, retryAttempt = 0, previouslyExcluded = emptyList())
    }

    companion object {
        /** Maximum number of retry attempts when all generated questions are duplicates. */
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val DUPLICATE_SIMILARITY_THRESHOLD = 0.85
    }

    /**
     * Internal question loading with retry support.
     * If all generated questions are duplicates, retries with existing questions
     * explicitly excluded in the prompt for semantic differentiation.
     * Uses escalating prompt strategies on each retry to force the LLM to
     * produce fundamentally different questions.
     * @param retryAttempt current retry (max [MAX_RETRY_ATTEMPTS])
     * @param previouslyExcluded question texts from DB that MUST NOT be repeated
     */
    private fun loadQuestionsInternal(
        courseId: Long,
        courseName: String,
        topicId: Long = -1L,
        taskId: Long = -1L,
        retryAttempt: Int = 0,
        previouslyExcluded: List<String> = emptyList(),
        accumulatedQuestions: List<QuizQuestion> = emptyList(),
        targetCount: Int = 10,
        cachedContextData: com.google.gson.JsonObject? = null
    ) {
        if (courseId == -1L) {
            _uiState.value = ReinforcementState.Error("ID de curso inválido.")
            return
        }

        Log.d("ReinforcementVM", "🔄 CICLO ACUMULATIVO: attempt=$retryAttempt, targetCount=$targetCount, accumulated=${accumulatedQuestions.size}, excluded=${previouslyExcluded.size}")

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

                // 2. Fetch unified Learning Context from backend (single API call)
                // On retries, reuse cached context to avoid redundant network round-trips.
                // Only the LLM prompt changes on retries (escalating strategy).
                val sessionIndex = if (retryAttempt > 0) retryAttempt else null
                
                val semanticQuery = buildSemanticQuery(courseName, topicId, taskId)
                val retrievalMode = "hybrid"
                
                val contextData: com.google.gson.JsonObject? = if (cachedContextData != null && retryAttempt > 0) {
                    // Reuse cached context from previous attempt — skip network call
                    Log.d("ReinforcementVM", "♻️ Reusing cached learning context for retry #$retryAttempt")
                    cachedContextData
                } else {
                    val learningContext = withContext(Dispatchers.IO) {
                        BackendApiService.getLearningContext(
                            courseId = courseId,
                            topicId = if (topicId > 0) topicId else null,
                            taskId = if (taskId > 0) taskId else null,
                            sessionIndex = sessionIndex,
                            query = semanticQuery,
                        retrievalMode = retrievalMode
                    )
                }
                    when (learningContext) {
                        is ApiResult.Success -> learningContext.data
                        is ApiResult.Error -> {
                            Log.e("ReinforcementVM", "Failed to fetch learning context: ${learningContext.message}")
                            null
                        }
                    }
                }

                // Fallback: if backend is unreachable, build minimal context from local info
                val effectiveContextData: com.google.gson.JsonObject = if (contextData != null) {
                    contextData
                } else {
                    Log.w("ReinforcementVM", "⚠️ Learning context unavailable — building minimal local context for fallback generation")
                    com.google.gson.JsonObject().apply {
                        add("topic", com.google.gson.JsonObject().apply {
                            addProperty("name", _selectedTopicName.value ?: "")
                            addProperty("description", "")
                        })
                        add("task", com.google.gson.JsonObject().apply {
                            addProperty("title", _selectedTaskName.value ?: "")
                            addProperty("description", "")
                        })
                        addProperty("ragContent", "")
                        addProperty("ragChunks", 0)
                        addProperty("ragPage", 0)
                        addProperty("ragTotalPages", 1)
                        addProperty("ragTotalChunks", 0)
                        add("ragFiles", com.google.gson.JsonArray())
                        add("contentItems", com.google.gson.JsonArray())
                        add("topicContentItems", com.google.gson.JsonArray())
                        add("taskContentItems", com.google.gson.JsonArray())
                        add("existingQuestions", com.google.gson.JsonArray())
                    }
                }

                // Extract topic details
                val topicObj = effectiveContextData.getAsJsonObject("topic")
                val topicName = topicObj?.get("name")?.asString ?: ""
                val topicDescription = topicObj?.get("description")?.asString ?: ""

                // Extract task details
                val taskObj = effectiveContextData.getAsJsonObject("task")
                val taskName = taskObj?.get("title")?.asString ?: ""
                val taskDescription = taskObj?.get("description")?.asString ?: ""

                // Extract RAG content (already ingested by the backend)
                val ragDocumentContent = effectiveContextData.get("ragContent")?.asString ?: ""
                val ragChunks = effectiveContextData.get("ragChunks")?.asInt ?: 0
                val ragPage = effectiveContextData.get("ragPage")?.asInt ?: 0
                val ragTotalPages = effectiveContextData.get("ragTotalPages")?.asInt ?: 1
                val ragTotalChunks = effectiveContextData.get("ragTotalChunks")?.asInt ?: ragChunks
                val ragFileNames = try {
                    effectiveContextData.getAsJsonArray("ragFiles")?.map { it.asString } ?: emptyList()
                } catch (_: Exception) { emptyList() }
                
                // Hybrid Retrieval metadata
                val activeRetrievalMode = effectiveContextData.get("retrievalMode")?.asString ?: "progressive"
                val semanticHits = effectiveContextData.get("semanticHits")?.asInt ?: 0
                val expandedChunks = effectiveContextData.get("expandedChunks")?.asInt ?: 0
                val usedProgressiveFallback = effectiveContextData.get("usedProgressiveFallback")?.asBoolean ?: false
                val wasReranked = effectiveContextData.get("reranked")?.asBoolean ?: false
                val rerankMethod = effectiveContextData.get("rerankMethod")?.asString ?: "none"

                // Extract content items metadata (combined — backward compatible)
                val contentItemsArray = try {
                    effectiveContextData.getAsJsonArray("contentItems") ?: com.google.gson.JsonArray()
                } catch (_: Exception) { com.google.gson.JsonArray() }

                // Extract content items separated by origin: topic vs task
                val topicContentItemsArray = try {
                    effectiveContextData.getAsJsonArray("topicContentItems") ?: com.google.gson.JsonArray()
                } catch (_: Exception) { com.google.gson.JsonArray() }
                val taskContentItemsArray = try {
                    effectiveContextData.getAsJsonArray("taskContentItems") ?: com.google.gson.JsonArray()
                } catch (_: Exception) { com.google.gson.JsonArray() }

                // Extract existing questions for deduplication.
                // EASY level: allow some repetition (skip dedup entirely).
                // INTERMEDIATE and HARD: enforce strict deduplication.
                val existingQuestionsArray = try {
                    effectiveContextData.getAsJsonArray("existingQuestions") ?: com.google.gson.JsonArray()
                } catch (_: Exception) { com.google.gson.JsonArray() }

                val existingQuestions = existingQuestionsArray.mapNotNull { elem ->
                    elem.asJsonObject?.get("question")?.asString
                }
                val existingQuestionTexts = (existingQuestions + previouslyExcluded).distinct().takeLast(80)
                val existingQuestionsForPrompt = existingQuestionTexts

                Log.d("ReinforcementVM", "📚 Learning context received: topic='$topicName', task='$taskName', " +
                    "mode=$activeRetrievalMode, semanticHits=$semanticHits, expanded=$expandedChunks, " +
                    "reranked=$wasReranked ($rerankMethod), " +
                    "progressiveFallback=$usedProgressiveFallback, " +
                    "ragChunks=$ragChunks/$ragTotalChunks (page ${ragPage + 1}/$ragTotalPages), " +
                    "files=${ragFileNames.size}, topicFiles=${topicContentItemsArray.size()}, taskFiles=${taskContentItemsArray.size()}, " +
                    "existingQ=${existingQuestionTexts.size}, retryAttempt=$retryAttempt")

                // Update UI state with topic/task names
                _selectedTopicName.value = topicName.ifBlank { "General" }
                _selectedTaskName.value = taskName.ifBlank { "General" }

                // Update analyzed files list — label each file with its source (topic/task)
                val topicFileNames = (0 until topicContentItemsArray.size()).mapNotNull { i ->
                    val item = topicContentItemsArray[i].asJsonObject
                    val title = item?.get("title")?.asString ?: return@mapNotNull null
                    AnalyzedFile(name = "📖 [Tema] $title", url = null, type = item.get("contentType")?.asString)
                }
                val taskFileNames = (0 until taskContentItemsArray.size()).mapNotNull { i ->
                    val item = taskContentItemsArray[i].asJsonObject
                    val title = item?.get("title")?.asString ?: return@mapNotNull null
                    AnalyzedFile(name = "📝 [Tarea] $title", url = null, type = item.get("contentType")?.asString)
                }
                val ragAnalyzedFiles = ragFileNames.map { fileName ->
                    AnalyzedFile(name = fileName, url = null, type = null)
                }
                _analyzedFiles.value = (topicFileNames + taskFileNames + ragAnalyzedFiles).distinctBy { it.name }

                // Validate sufficient content — when backend was unreachable, use courseName as minimum seed
                if (topicName.isBlank() && taskName.isBlank() && ragDocumentContent.isBlank() && courseName.isBlank()) {
                    _uiState.value = ReinforcementState.Error(
                        "Este curso no tiene contenido suficiente (temas, tareas o materiales) para generar preguntas."
                    )
                    return@launch
                }

                // 3. Build Prompt using structured context from backend
                //    Blend BOTH topic and task context for comprehensive question generation.
                val contextBuilder = StringBuilder()
                // NOTE: Course name is for reference only — questions must NOT be based on it
                contextBuilder.append("(Curso de referencia: $courseName — NO generar preguntas basadas en el nombre del curso)\n")

                // ═══ CONTEXTO DEL TEMA ═══
                if (topicName.isNotBlank()) {
                    contextBuilder.append("\n╔══════════════════════════════════════════════╗\n")
                    contextBuilder.append("║  TEMA: $topicName\n")
                    contextBuilder.append("╚══════════════════════════════════════════════╝\n")
                    if (topicDescription.isNotBlank()) {
                        contextBuilder.append("Descripción del tema: $topicDescription\n")
                    }
                    // List topic-specific files
                    if (topicContentItemsArray.size() > 0) {
                        contextBuilder.append("Archivos del tema (${topicContentItemsArray.size()}):\n")
                        for (i in 0 until topicContentItemsArray.size()) {
                            val item = topicContentItemsArray[i].asJsonObject
                            val title = item?.get("title")?.asString ?: "Sin nombre"
                            val type = item?.get("contentType")?.asString ?: "unknown"
                            contextBuilder.append("  - $title ($type)\n")
                        }
                    }
                }

                // ═══ CONTEXTO DE LA TAREA ═══
                if (taskName.isNotBlank()) {
                    contextBuilder.append("\n╔══════════════════════════════════════════════╗\n")
                    contextBuilder.append("║  TAREA: $taskName\n")
                    contextBuilder.append("╚══════════════════════════════════════════════╝\n")
                    contextBuilder.append("Descripción de la tarea: ${taskDescription.ifBlank { "Sin descripción" }}\n")
                    // List task-specific files
                    if (taskContentItemsArray.size() > 0) {
                        contextBuilder.append("Archivos de la tarea (${taskContentItemsArray.size()}):\n")
                        for (i in 0 until taskContentItemsArray.size()) {
                            val item = taskContentItemsArray[i].asJsonObject
                            val title = item?.get("title")?.asString ?: "Sin nombre"
                            val type = item?.get("contentType")?.asString ?: "unknown"
                            contextBuilder.append("  - $title ($type)\n")
                        }
                    }
                }

                // ═══ INSTRUCCIÓN DE MEZCLA TEMÁTICA ═══
                val hasBothSources = topicName.isNotBlank() && taskName.isNotBlank()
                if (hasBothSources) {
                    contextBuilder.append("\n═══════════════════════════════════════════════════\n")
                    contextBuilder.append("INSTRUCCIÓN DE MEZCLA TEMÁTICA:\n")
                    contextBuilder.append("Las preguntas DEBEN combinar AMBAS fuentes:\n")
                    contextBuilder.append("  • Tema: \"$topicName\" — conceptos teóricos, definiciones y fundamentos\n")
                    contextBuilder.append("  • Tarea: \"$taskName\" — aplicación práctica, requisitos y ejercicios\n")
                    contextBuilder.append("Genera preguntas que integren los conceptos del TEMA con la práctica de la TAREA.\n")
                    contextBuilder.append("═══════════════════════════════════════════════════\n")
                }

                // Include RAG document content directly for strict grounding
                // Content is retrieved via Hybrid Retrieval Architecture:
                //   - Semantic search finds the most relevant chunks
                //   - Reranking reorders by TRUE relevance (LLM cross-encoder or TF-IDF)
                //   - Window expansion adds surrounding context
                //   - Progressive coverage rotates through different document sections
                if (ragDocumentContent.isNotBlank()) {
                    contextBuilder.append("\n═══════════════════════════════════════════════════\n")
                    contextBuilder.append("MATERIAL DE REFERENCIA VERIFICADO — Hybrid Retrieval + Reranking\n")
                    if (activeRetrievalMode == "hybrid" || activeRetrievalMode == "semantic") {
                        contextBuilder.append("  Búsqueda semántica: $semanticHits coincidencias relevantes\n")
                        contextBuilder.append("  Contexto expandido: $expandedChunks fragmentos con ventana contextual\n")
                        if (wasReranked) {
                            contextBuilder.append("  Reranking: activado ($rerankMethod) — fragmentos reordenados por relevancia REAL\n")
                        }
                    }
                    if (ragTotalPages > 1) {
                        contextBuilder.append("  Cobertura: Sección ${ragPage + 1} de $ragTotalPages\n")
                    }
                    contextBuilder.append("═══════════════════════════════════════════════════\n")
                    contextBuilder.append(ragDocumentContent)
                    contextBuilder.append("\n═══════════════════════════════════════════════════\n")
                    if (ragFileNames.isNotEmpty()) {
                        contextBuilder.append("Archivos fuente: ${ragFileNames.joinToString(", ")}\n")
                    }
                    contextBuilder.append("Fragmentos: $ragChunks de $ragTotalChunks totales (ventana progresiva)\n")
                } else if (contentItemsArray.size() > 0) {
                    contextBuilder.append("\nMATERIAL DE REFERENCIA (ARCHIVOS ADJUNTOS):\n")
                    for (i in 0 until contentItemsArray.size()) {
                        val item = contentItemsArray[i].asJsonObject
                        val title = item?.get("title")?.asString ?: "Sin nombre"
                        val type = item?.get("contentType")?.asString ?: "unknown"
                        contextBuilder.append("- Archivo: $title ($type)\n")
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

                contextBuilder.append("\n(Generación ID: ${System.currentTimeMillis()})\n")

                // Serialize content items metadata for backend processing
                val contentList = (0 until contentItemsArray.size()).map { i ->
                    val item = contentItemsArray[i].asJsonObject
                    mapOf(
                        "name" to (item?.get("title")?.asString ?: "Sin nombre"),
                        "uri" to "",
                        "type" to (item?.get("contentType")?.asString ?: "application/octet-stream"),
                        "id" to (item?.get("id")?.asLong ?: 0L)
                    )
                }
                val jsonContentString = Gson().toJson(contentList)
                Log.d("ReinforcementVM", "Enviando ${contentList.size} archivos al backend. JSON: $jsonContentString")

                // Determine grounding instruction based on RAG availability
                val hasRagContent = ragDocumentContent.isNotBlank()
                val sectionInfo = if (ragTotalPages > 1) {
                    "\n- Estás viendo la SECCIÓN ${ragPage + 1} de $ragTotalPages del documento. Genera preguntas SOLO sobre el contenido de ESTA sección."
                } else ""
                
                val hybridInfo = if (activeRetrievalMode == "hybrid" || activeRetrievalMode == "semantic") {
                    val rerankInfo = if (wasReranked) " y reranking ($rerankMethod) para máxima precisión" else ""
                    "\n- El contenido ha sido seleccionado mediante búsqueda semántica ($semanticHits coincidencias) con contexto expandido ($expandedChunks fragmentos)$rerankInfo."
                } else ""

                val groundingInstruction = if (hasRagContent) {
                    """
                    REGLA DE GROUNDING ESTRICTO (ANTI-ALUCINACIÓN):
                    - TODAS las preguntas, respuestas y explicaciones DEBEN basarse EXCLUSIVAMENTE en el MATERIAL DE REFERENCIA VERIFICADO incluido arriba.
                    - Cada respuesta correcta DEBE poder verificarse directamente en el texto del material.$sectionInfo$hybridInfo
                    - NO inventes, infieras ni añadas información que NO aparezca EXPLÍCITAMENTE en el material.
                    - Si un concepto NO está en el material, NO generes preguntas sobre él.
                    - Las explicaciones DEBEN citar o parafrasear directamente frases del material de referencia.
                    - Genera preguntas variadas que cubran DIFERENTES conceptos, relaciones y mecanismos del material proporcionado.
                    - Cada pregunta debe abordar un CONCEPTO o RELACIÓN DISTINTA del texto, nunca un evento o dato aislado.
                    - PROHIBIDO ABSOLUTO preguntas de memorización de eventos: NO preguntar "¿Qué ocurrió?", "¿Quién hizo X?", "¿Cuándo sucedió?", "¿Dónde se realizó?". El estudiante NUNCA debe responder recordando un hecho, fecha, nombre o evento del documento.
                    - TODAS las preguntas deben premiar COMPRENSIÓN y PENSAMIENTO CRÍTICO: el estudiante demuestra que ENTIENDE relaciones, mecanismos, consecuencias e implicaciones.
                    """.trimIndent()
                } else {
                    """
                    FUENTE DE INFORMACIÓN:
                    Genera preguntas basándote en el nombre y descripción de la tarea/tema proporcionados.
                    Los documentos RAG asociados serán procesados por el backend.
                    """.trimIndent()
                }

                // ═══════════════════════════════════════════════════════════
                // 🔄 ESCALATING PROMPT STRATEGY: On each retry, change the
                // question style to force the LLM to generate fundamentally
                // different questions instead of paraphrasing the same ones.
                // ═══════════════════════════════════════════════════════════
                val shortfallNote = if (targetCount < 10)
                    "\n⚠️ ATENCIÓN: Solo necesitas generar $targetCount ${if (targetCount == 1) "pregunta nueva" else "preguntas nuevas"} (las demás ya fueron guardadas)."
                else ""

                val retryDifferentiationInstruction = when (retryAttempt) {
                    0 -> shortfallNote
                    1 -> """
                        $shortfallNote
                        ⚠️ INTENTO DE REGENERACIÓN #$retryAttempt: Las preguntas anteriores fueron RECHAZADAS por ser duplicadas.
                        NOTA: El material de referencia ha sido ACTUALIZADO con una NUEVA SECCIÓN del documento (sección ${ragPage + 1}/$ragTotalPages).
                        ESTRATEGIA OBLIGATORIA: Genera preguntas de CAUSA-EFECTO y CONSECUENCIAS basadas en ESTA NUEVA sección.
                        Enfócate en relaciones entre conceptos, implicaciones prácticas y efectos de decisiones.
                        PROHIBIDO preguntas de recuerdo de datos, cifras o nombres. El estudiante debe RAZONAR, no memorizar.
                        NO preguntes sobre definiciones generales — esas YA EXISTEN.
                    """.trimIndent()
                    2 -> """
                        $shortfallNote
                        ⚠️ INTENTO DE REGENERACIÓN #$retryAttempt: TODAS las preguntas previas fueron duplicadas.
                        NOTA: Estás viendo la SECCIÓN ${ragPage + 1}/$ragTotalPages del documento — contenido NUEVO respecto al intento anterior.
                        ESTRATEGIA OBLIGATORIA: Genera preguntas de APLICACIÓN y ESCENARIOS PRÁCTICOS.
                        Cada pregunta debe plantear una SITUACIÓN HIPOTÉTICA donde el estudiante aplique los conceptos.
                        Formato sugerido: "Te encuentras ante la decisión de...", "En una situación donde...", "¿Qué consecuencia tendría...?"
                        PROHIBIDO preguntas de recuerdo. El estudiante debe PENSAR CRÍTICAMENTE para responder.
                    """.trimIndent()
                    3 -> """
                        $shortfallNote
                        ⚠️ INTENTO DE REGENERACIÓN #$retryAttempt: AÚN se detectaron duplicados.
                        ESTRATEGIA OBLIGATORIA: Genera preguntas de EVALUACIÓN y JUICIO CRÍTICO entre enfoques del material.
                        Cada pregunta debe pedir al estudiante EVALUAR ventajas/desventajas o JUSTIFICAR una elección.
                        Formato sugerido: "¿Por qué es preferible X sobre Y cuando...?", "¿Qué limitación tendría aplicar X en este caso?"
                        PROHIBIDO preguntar sobre datos aislados o definiciones.
                    """.trimIndent()
                    4 -> """
                        $shortfallNote
                        ⚠️ INTENTO DE REGENERACIÓN #$retryAttempt: ÚLTIMO INTENTO.
                        ESTRATEGIA OBLIGATORIA: Genera preguntas que presenten AFIRMACIONES RAZONADAS y pidan evaluar su validez.
                        Cada pregunta debe presentar un ARGUMENTO o CONCLUSIÓN y preguntar si es válido según el material.
                        Formato: "Un colega argumenta que [afirmación]. Según lo estudiado, ¿es correcto este razonamiento y por qué?"
                        Las opciones deben requerir comprensión profunda para distinguir argumentos válidos de falacias sutiles.
                    """.trimIndent()
                    else -> """
                        $shortfallNote
                        ⚠️ INTENTO DE REGENERACIÓN #$retryAttempt: Genera preguntas COMPLETAMENTE DIFERENTES a todo lo anterior.
                        Usa un enfoque creativo: preguntas de secuencia, de causa-efecto, o de clasificación.
                    """.trimIndent()
                }

                // Build the thematic distribution instruction based on available sources
                val thematicDistribution = if (hasBothSources) {
                    """
                    DISTRIBUCIÓN TEMÁTICA OBLIGATORIA (MEZCLA TEMA + TAREA):
                    Las preguntas DEBEN distribuirse así:
                    - 3 preguntas sobre comprensión de PRINCIPIOS del TEMA "$topicName" (relaciones causa-efecto, propósito, implicaciones)
                    - 3 preguntas sobre APLICACIÓN RAZONADA de la TAREA "$taskName" (decisiones, consecuencias, justificaciones)
                    - 4 preguntas que INTEGREN AMBOS: escenarios donde el estudiante aplique principios del tema
                      para resolver situaciones de la tarea o evalúe consecuencias cruzadas
                    
                    IMPORTANTE: Las preguntas integradoras deben mencionar elementos de AMBAS fuentes.
                    Ejemplo de pregunta integradora: "Según el tema '$topicName', ¿cómo se aplica [concepto] en la tarea '$taskName'?"
                    
                    ARCHIVOS FUENTE:
                    ${if (topicContentItemsArray.size() > 0) "- Archivos del Tema: ${(0 until topicContentItemsArray.size()).map { topicContentItemsArray[it].asJsonObject?.get("title")?.asString ?: "?" }.joinToString(", ")}" else "- Sin archivos del Tema"}
                    ${if (taskContentItemsArray.size() > 0) "- Archivos de la Tarea: ${(0 until taskContentItemsArray.size()).map { taskContentItemsArray[it].asJsonObject?.get("title")?.asString ?: "?" }.joinToString(", ")}" else "- Sin archivos de la Tarea"}
                    """.trimIndent()
                } else if (topicName.isNotBlank()) {
                    """
                    ENFOQUE: Genera preguntas centradas en el TEMA "$topicName".
                    Cubre comprensión de principios, relaciones causa-efecto, implicaciones y aplicaciones del tema. PROHIBIDO preguntas de definición o recuerdo de eventos.
                    ${if (topicContentItemsArray.size() > 0) "Archivos del Tema: ${(0 until topicContentItemsArray.size()).map { topicContentItemsArray[it].asJsonObject?.get("title")?.asString ?: "?" }.joinToString(", ")}" else ""}
                    """.trimIndent()
                } else {
                    """
                    ENFOQUE: Genera preguntas centradas en la TAREA "$taskName".
                    Cubre decisiones de aplicación, consecuencias de enfoques y razonamiento sobre la tarea. PROHIBIDO preguntas de recuerdo de pasos o eventos.
                    ${if (taskContentItemsArray.size() > 0) "Archivos de la Tarea: ${(0 until taskContentItemsArray.size()).map { taskContentItemsArray[it].asJsonObject?.get("title")?.asString ?: "?" }.joinToString(", ")}" else ""}
                    """.trimIndent()
                }

                val freeLearningInstruction = if (isFreeLearning) """
                    ═══════════════════════════════════════════════════
                    MODO APRENDIZAJE LIBRE — EXPLORACIÓN COMPLETA:
                    ═══════════════════════════════════════════════════
                    - Las preguntas DEBEN cubrir CUALQUIER parte del contenido del documento adjunto.
                    - NO te limites al título ni a la descripción del tema/tarea.
                    - Explora secciones, detalles, ejemplos, datos y conceptos de TODO el material.
                    - Cada pregunta debe abordar un aspecto DIFERENTE del documento.
                    ═══════════════════════════════════════════════════
                """.trimIndent() else ""

                val prompt = when (currentDifficulty) {

                    // ══════════════════════════════════════════════════════
                    // 🌱 EASY — Comprensión básica y razonamiento guiado
                    // El estudiante demuestra que ENTIENDE, no que memoriza.
                    // ══════════════════════════════════════════════════════
                    "EASY" -> """
                        Eres un profesor generando preguntas de NIVEL INTRODUCTORIO (comprensión básica).

                        OBJETIVO: Generar EXACTAMENTE $targetCount preguntas de opción múltiple que evalúen COMPRENSIÓN, no memorización.

                        TEMA: "${topicName.ifBlank { "General" }}"
                        TAREA: "${taskName.ifBlank { "General" }}"

                        $groundingInstruction
                        $freeLearningInstruction

                        ═══════════════════════════════════════════════════
                        PRINCIPIO FUNDAMENTAL — COMPRENSIÓN, NUNCA MEMORIZACIÓN:
                        ═══════════════════════════════════════════════════
                        - PROHIBIDO preguntas de recuerdo puro: "¿Qué es X?", "¿Cuál es el nombre de...?", "¿En qué año...?", "¿Cuántos...?"
                        - PROHIBIDO preguntas donde la respuesta sea un dato, fecha, nombre o valor que se localiza directamente en el texto.
                        - PROHIBIDO preguntas de memorización de EVENTOS: "¿Qué ocurrió?", "¿Quién hizo X?", "¿Cuándo sucedió?", "¿Qué evento causó X?"
                        - El estudiante NUNCA debe poder responder simplemente recordando un hecho o evento del documento.
                        - OBLIGATORIO: El estudiante debe COMPRENDER un concepto para poder responder.
                        - Las preguntas deben evaluar si el estudiante ENTIENDE el POR QUÉ o el PARA QUÉ, no si recuerda un dato o evento.
                        - Usa Taxonomía de Bloom nivel COMPRENDER mínimo: ¿por qué?, ¿para qué?, ¿qué pasaría si...?

                        REGLAS DEL NIVEL INTRODUCTORIO:
                        - Presenta situaciones sencillas y cotidianas donde el estudiante aplique comprensión básica.
                        - Preguntas del tipo: "¿Por qué es importante X?", "¿Cuál es el propósito de...?", "¿Qué pasaría si no se aplica...?"
                        - La respuesta correcta debe ser identificable por quien ENTIENDE el concepto, no por quien lo memorizó.
                        - Las opciones incorrectas deben ser razonablemente plausibles pero distinguibles con comprensión básica.
                        - Usa lenguaje accesible pero que requiera pensar.
                        ${if (existingQuestionsForPrompt.isNotEmpty()) "\nPREGUNTAS YA EXISTENTES — PROHIBIDO REPETIR O PARAFRASEAR:\n${existingQuestionsForPrompt.takeLast(50).mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n")}" else ""}

                        RESTRICCIONES:
                        1. Genera EXACTAMENTE $targetCount preguntas. Ni una más ni una menos.
                        2. Tu ÚNICA salida debe ser el array JSON.
                        3. Varía el correctIndex entre 0, 1, 2 y 3.
                        4. CERO preguntas de memorización ni de recuerdo de eventos. Cada pregunta debe requerir COMPRENSIÓN de relaciones, mecanismos o consecuencias.

                        FORMATO JSON:
                        [
                          {"question": "¿Por qué es importante [concepto] cuando se trabaja con [contexto]?", "options": ["A", "B", "C", "D"], "correctIndex": 0, "explanation": "La respuesta correcta es A porque... B es incorrecto porque..."}
                        ]

                        Contexto:
                        $contextBuilder
                    """.trimIndent()

                    // ══════════════════════════════════════════════════════
                    // ⚡ INTERMEDIATE — Análisis aplicado y pensamiento crítico
                    // El estudiante analiza escenarios y justifica decisiones.
                    // ══════════════════════════════════════════════════════
                    "INTERMEDIATE" -> """
                        Eres un profesor generando preguntas de NIVEL INTERMEDIO (análisis y pensamiento crítico).

                        OBJETIVO: Generar EXACTAMENTE $targetCount preguntas de opción múltiple que evalúen RAZONAMIENTO, no memorización.

                        TEMA: "${topicName.ifBlank { "General" }}"
                        TAREA: "${taskName.ifBlank { "General" }}"

                        $groundingInstruction
                        $freeLearningInstruction
                        $retryDifferentiationInstruction

                        ═══════════════════════════════════════════════════
                        PRINCIPIO FUNDAMENTAL — PENSAMIENTO CRÍTICO, NUNCA MEMORIZACIÓN:
                        ═══════════════════════════════════════════════════
                        - PROHIBIDO preguntas donde la respuesta sea un dato que se localiza en el texto.
                        - PROHIBIDO: "¿Qué es X?", "¿Cuántos...?", "¿Cuál es el nombre de...?", "¿En qué fecha...?"
                        - PROHIBIDO preguntas de memorización de EVENTOS del documento: "¿Qué ocurrió?", "¿Quién realizó X?", "¿Cuándo sucedió?", "¿Qué evento causó X?"
                        - El estudiante NUNCA debe poder responder simplemente recordando un hecho, evento o dato del documento.
                        - OBLIGATORIO: El estudiante debe ANALIZAR, RAZONAR y APLICAR conceptos.
                        - Cada pregunta DEBE presentar un ESCENARIO o SITUACIÓN PRÁCTICA donde el estudiante demuestre COMPRENSIÓN PROFUNDA.
                        - Taxonomía de Bloom: solo niveles ANALIZAR, EVALUAR, APLICAR. PROHIBIDO nivel RECORDAR.

                        REGLAS DEL NIVEL INTERMEDIO:
                        - El estudiante debe COMPRENDER el concepto y saber CUÁNDO/CÓMO/POR QUÉ aplicarlo.
                        - Cada pregunta presenta un escenario donde el estudiante debe DECIDIR o JUSTIFICAR.
                        - Usa situaciones concretas: "Si necesitas resolver X considerando Y, ¿qué enfoque es mejor y por qué?"
                        - Las 4 opciones deben ser plausibles y requerir ANÁLISIS para distinguirlas.
                        - PROHIBIDO opciones que sean datos literales del material.
                        - Incluye preguntas de CAUSA-EFECTO: "¿Qué consecuencia tendría aplicar X en este contexto?"
                        - Incluye preguntas de COMPARACIÓN: "Comparando estos dos enfoques, ¿cuál es más adecuado cuando...?"
                        - La explicación DEBE justificar por qué CADA opción incorrecta falla.
                        ${if (existingQuestionsForPrompt.isNotEmpty()) "\nPREGUNTAS YA RESPONDIDAS — NO REPETIR:\n${existingQuestionsForPrompt.takeLast(50).mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n")}" else ""}

                        DISTRIBUCIÓN OBLIGATORIA ($targetCount preguntas):
                        - 40% APLICACIÓN PRÁCTICA: "En este escenario, ¿qué enfoque resuelve mejor el problema?"
                        - 30% CAUSA-EFECTO: "¿Qué consecuencia produce aplicar X en lugar de Y?"
                        - 30% COMPARACIÓN RAZONADA: "¿Por qué es preferible X sobre Y en esta situación?"

                        RESTRICCIONES:
                        1. Genera EXACTAMENTE $targetCount preguntas. Ni una más ni una menos.
                        2. Tu ÚNICA salida debe ser el array JSON.
                        3. Varía el correctIndex entre 0, 1, 2 y 3.
                        4. NO repitas preguntas ya existentes — cambia el enfoque.
                        5. CERO preguntas de memorización ni de recuerdo de eventos. Cada pregunta debe requerir RAZONAMIENTO sobre relaciones, mecanismos o consecuencias.

                        FORMATO JSON:
                        [
                          {"question": "Te encuentras desarrollando X y necesitas decidir entre dos enfoques. Considerando [contexto], ¿cuál es la mejor estrategia?", "options": ["A", "B", "C", "D"], "correctIndex": 1, "explanation": "B es correcto porque... A es incorrecto porque... C falla porque... D no aplica porque..."}
                        ]

                        Contexto:
                        $contextBuilder
                    """.trimIndent()

                    "FREE" -> """
                        Eres un profesor generando preguntas de APRENDIZAJE LIBRE.

                        OBJETIVO: Generar EXACTAMENTE $targetCount preguntas de opción múltiple basadas en TODO el contenido del documento, no solo en el título o descripción del tema/tarea.

                        TEMA: "${topicName.ifBlank { "General" }}"
                        TAREA: "${taskName.ifBlank { "General" }}"

                        $groundingInstruction

                        ═══════════════════════════════════════════════════
                        MODO LIBRE — EXPLORACIÓN COMPLETA DEL DOCUMENTO:
                        ═══════════════════════════════════════════════════
                        - Las preguntas DEBEN cubrir CUALQUIER parte del contenido del documento adjunto.
                        - NO te limites al título ni a la descripción del tema/tarea.
                        - Explora secciones, detalles, ejemplos, datos y conceptos de TODO el material.
                        - Varía entre niveles: algunas de comprensión, otras de análisis, otras de aplicación.
                        - Cada pregunta debe abordar un aspecto DIFERENTE del documento.
                        - PROHIBIDO preguntas de memorización pura: el estudiante debe comprender, no solo recordar.
                        ${if (existingQuestionsForPrompt.isNotEmpty()) "\nPREGUNTAS YA EXISTENTES — PROHIBIDO REPETIR:\n${existingQuestionsForPrompt.takeLast(50).mapIndexed { i, q -> "${i + 1}. $q" }.joinToString("\n")}" else ""}

                        RESTRICCIONES:
                        1. Genera EXACTAMENTE $targetCount preguntas.
                        2. Tu ÚNICA salida debe ser el array JSON.
                        3. Varía el correctIndex entre 0, 1, 2 y 3.
                        4. Cubre diferentes secciones y aspectos del documento completo.

                        FORMATO JSON:
                        [
                          {"question": "Según el contenido del documento, ¿por qué...?", "options": ["A", "B", "C", "D"], "correctIndex": 0, "explanation": "La respuesta correcta es A porque..."}
                        ]

                        Contexto:
                        $contextBuilder
                    """.trimIndent()

                    else -> """
                    Eres un profesor experto generando preguntas de nivel MÁXIMO (10/10).

                    OBJETIVO: Generar EXACTAMENTE $targetCount ${if (targetCount == 1) "pregunta" else "preguntas"} de opción múltiple basadas EXCLUSIVAMENTE en el TEMA y la TAREA.
                    
                    ⚠️ REGLA FUNDAMENTAL — FOCO EN TEMA Y TAREA (NO en el curso):
                    Las preguntas deben estar 100% basadas en el TÍTULO y DESCRIPCIÓN del TEMA y la TAREA.
                    PROHIBIDO generar preguntas generales basadas en el nombre del curso.
                    El nombre del curso es solo contexto organizativo — NO es contenido para preguntas.
                    
                    TEMÁTICA COMBINADA:
                    - TEMA: "${topicName.ifBlank { "General" }}"${if (topicDescription.isNotBlank()) "\n                      Descripción: $topicDescription" else ""}
                    - TAREA: "${taskName.ifBlank { "General" }}"${if (taskDescription.isNotBlank()) "\n                      Descripción: $taskDescription" else ""}
                    
                    $thematicDistribution
                    
                    $groundingInstruction
                    $freeLearningInstruction
                    $retryDifferentiationInstruction
                    
                    ═══════════════════════════════════════════════════
                    INSTRUCCIÓN PEDAGÓGICA — NIVEL 10/10 (OBLIGATORIO ESTRICTO)
                    ═══════════════════════════════════════════════════
                    
                    PRINCIPIO #1 — EL ESTUDIANTE DEBE PENSAR, NO BUSCAR NI RECORDAR:
                    - PROHIBIDO preguntas donde el estudiante solo localiza un dato en el material.
                    - PROHIBIDO preguntas de memorización de EVENTOS: "¿Qué ocurrió?", "¿Quién hizo X?", "¿Cuándo sucedió?", "¿Qué evento causó X?"
                    - El estudiante NUNCA debe poder responder recordando un hecho, evento, fecha o nombre del documento.
                      Ejemplo PROHIBIDO: "¿Qué modelo tiene menor tiempo?" → solo buscar un número.
                      Ejemplo PROHIBIDO: "¿Qué evento motivó la creación de X?" → solo recordar un hecho.
                    - OBLIGATORIO: El estudiante debe ANALIZAR, INTERPRETAR y DECIDIR.
                                            Ejemplo CORRECTO: "Te encuentras en el caso de elegir un modelo que mantenga arquitectura MoE
                                            y reduzca el tiempo respecto a SMoE. ¿Cuál cumple esa condición?"
                      → Debe entender qué es SMoE, comparar y elegir. No copiar.
                    - Cada pregunta DEBE presentar un ESCENARIO o SITUACIÓN concreta (mín. 1 oración).
                                            Usa: "Te encuentras en el caso de...", "Durante tu análisis de...", "Al comparar dos enfoques..."
                                        - OBLIGATORIO: en escenarios hipotéticos, redacta en SEGUNDA PERSONA ("tú", "te", "tu").
                                        - PROHIBIDO iniciar escenarios con tercera persona genérica: "un investigador", "un estudiante", "un ingeniero".
                    
                    PRINCIPIO #2 — OPCIONES QUE REQUIEREN COMPRENSIÓN, NO COINCIDENCIA VISUAL:
                    - PROHIBIDO opciones que sean datos literales del material (ej: "Dense – 41h30m").
                      El estudiante solo buscaría coincidencia visual. Eso es nivel medio.
                    - Las opciones deben ser CONCEPTOS, ESTRATEGIAS o INTERPRETACIONES.
                    - Las 4 opciones deben estar al MISMO NIVEL de plausibilidad visual.
                      Ejemplo CORRECTO: "¿Qué enfoque demuestra mayor eficiencia dentro de su categoría?"
                      Opciones: 4 nombres de modelos/técnicas sin métricas → obliga a pensar qué significa eficiencia.
                    
                    PRINCIPIO #3 — EVALUAR CONSECUENCIAS E IMPLICACIONES, NO DATOS:
                    ★ ESTE ES EL CRITERIO MÁS IMPORTANTE ★
                    - PROHIBIDO: "¿Cuál es el tiempo de X?" (solo dato).
                    - OBLIGATORIO: "¿Qué IMPLICA ese tiempo?" / "¿Qué EFECTO produce?" / "¿Qué CONSECUENCIA tiene?"
                    - Ejemplo: "Si un modelo elimina cálculo de routing por token, ¿qué efecto directo
                      tiene en el entrenamiento?"
                      Opciones: "reduce tiempo ✓", "aumenta parámetros", "reduce GPU", "aumenta dataset"
                      → Debe entender el MECANISMO, no solo el número.
                    - Verbos obligatorios: implica, causa, produce, permite, impide, optimiza, degrada, predice.
                    
                    PRINCIPIO #4 — TRAMPAS CONCEPTUALES SUTILES:
                    - Los 3 distractores DEBEN ser TODOS plausibles a primera vista.
                    - Solo quien ENTIENDE el concepto puede distinguir la correcta.
                    - Tipos de trampas efectivas:
                      a) Suena técnicamente correcto pero INVIERTE causa-efecto.
                      b) Aplica el concepto correcto al CONTEXTO equivocado.
                      c) Es verdadero en general pero NO responde la pregunta ESPECÍFICA.
                      d) Confunde dos conceptos DEL MISMO DOMINIO (ej: optimización kernel vs arquitectura).
                    - PROHIBIDO opciones descartables por absurdas o fuera de tema.
                    - La explicación DEBE decir por qué CADA distractor es incorrecto, no solo la correcta.
                    
                    DISTRIBUCIÓN OBLIGATORIA ($targetCount ${if (targetCount == 1) "pregunta" else "preguntas"}):
                    ${if (targetCount >= 10) "- 4 de APLICACIÓN+CONSECUENCIA: \"Si haces X, ¿qué efecto produce?\" / \"¿Qué implica elegir Y?\"\n                    - 3 de ANÁLISIS+COMPARACIÓN: \"Comparando X e Y, ¿cuál demuestra mayor Z y por qué?\"\n                    - 3 de EVALUACIÓN+DECISIÓN: \"Te encuentras en el caso de decidir entre A y B considerando C. ¿Cuál es mejor?\"" else "- Cada pregunta debe ser de tipo ANÁLISIS, APLICACIÓN o EVALUACIÓN (elige el tipo más adecuado al contenido disponible)."}
                    
                    ══════════════════════════════════════════════════
                    RESUMEN: NIVEL MEDIO (PROHIBIDO) vs NIVEL 10/10 (OBLIGATORIO)
                    ══════════════════════════════════════════════════
                    ❌ Buscar dato → ✅ Analizar datos
                    ❌ Coincidencia visual en opciones → ✅ Interpretación conceptual
                    ❌ "¿Cuál es el valor?" → ✅ "¿Qué IMPLICA ese valor?"
                    ❌ Distractores obvios → ✅ Todo parece válido, solo comprensión distingue
                    ❌ Responder sin entender → ✅ Imposible responder sin comprender
                    ══════════════════════════════════════════════════
                    
                    RESTRICCIONES:
                    1. Genera EXACTAMENTE $targetCount ${if (targetCount == 1) "pregunta" else "preguntas"}. Ni una más ni una menos.
                    2. PROHIBIDO calificar, evaluar o dar feedback. Solo genera preguntas.
                    3. Tu ÚNICA salida debe ser el array JSON.
                    4. Cada pregunta debe ser semánticamente DISTINTA a las existentes (${existingQuestionsForPrompt.size} previas).
                    5. Si hay preguntas existentes, aborda aspectos DIFERENTES del material no cubiertos.
                    6. NUNCA parafrasees una pregunta existente — cambia completamente el enfoque y la estructura.
                    7. Las preguntas DEBEN reflejar los TÍTULOS del tema y la tarea por nombre cuando corresponda.
                    8. PROHIBIDO hacer preguntas genéricas sobre el curso — TODAS deben ser específicas al TEMA y TAREA indicados.
                    9. PROHIBIDO preguntas de puro recuerdo ("¿Qué es...?", "¿Cuál es...?", "¿Cómo se define...?", "¿Qué valor tiene...?") y de memorización de EVENTOS ("¿Qué ocurrió?", "¿Quién hizo X?", "¿Cuándo sucedió?"). El estudiante NUNCA debe responder recordando un hecho o evento.
                    10. Cada pregunta DEBE requerir que el estudiante ENTIENDA un mecanismo, implicación o relación causa-efecto para poder responder. Taxonomía de Bloom: solo APLICAR, ANALIZAR, EVALUAR.
                    
                    FORMATO JSON ESTRICTO (completa CADA objeto antes del siguiente):
                    [
                                            {"question": "Te encuentras en el caso de elegir entre dos arquitecturas para optimizar el entrenamiento. Considerando que el modelo A elimina routing por token y el modelo B usa routing dinámico, ¿qué consecuencia directa tiene elegir A?", "options": ["Reduce el tiempo de entrenamiento al eliminar cómputo de selección de expertos", "Aumenta la cantidad de parámetros activos por inferencia", "Requiere más memoria GPU por la eliminación del routing", "Mejora la calidad del dataset de entrenamiento"], "correctIndex": 0, "explanation": "La respuesta correcta es la primera porque según el material: [cita]. La segunda es incorrecta porque eliminar routing no cambia parámetros activos. La tercera invierte el efecto: menos cómputo = menos memoria, no más. La cuarta confunde optimización de arquitectura con calidad de datos."},
                      {"question": "Al comparar dos modelos de la misma categoría MoE, se observa que uno logra resultados similares en la mitad del tiempo. ¿Qué factor del material explica mejor esta diferencia de eficiencia?", "options": ["A", "B", "C", "D"], "correctIndex": 2, "explanation": "Explicación que analiza cada opción y su relación con el material."}
                    ]
                    - Cada objeto COMPLETO antes de iniciar el siguiente
                    - Campos en ESTE ORDEN: question, options, correctIndex, explanation
                    - Varía correctIndex (0, 1, 2 o 3)
                    - Genera exactamente $targetCount ${if (targetCount == 1) "objeto" else "objetos"}
                    
                    Contexto:
                    $contextBuilder
                """.trimIndent()
                }

                // 4. Call LLM via Backend
                Log.d("ReinforcementVM", "Invocando MicroservicioPromptRequest con userId=$userId, courseId=$courseId, topicId=$topicId, taskId=$taskId")

                // STRICT BUILD VARIANT ROUTING: Call ONLY the cloud API matching this build variant
                // No local network scanning — QA build → QA server, Production build → Production server
                var jsonText: String? = null
                var lastError: String? = null

                val candidateBaseUrls = linkedSetOf<String>().apply {
                    add(if (isQaBuild) enforceQaBackendUrl(BASE_URL) else normalizeBaseUrl(BASE_URL))

                    val backendApiBase = normalizeBaseUrl(BackendApiService.baseUrl)
                    if (backendApiBase.isNotBlank()) {
                        add(if (isQaBuild) enforceQaBackendUrl(backendApiBase) else backendApiBase)
                    }
                }.toList()

                Log.d("ReinforcementVM", "🔒 Strict routing with safe fallback hosts: $candidateBaseUrls")

                for (candidateBaseUrl in candidateBaseUrls) {
                    if (!jsonText.isNullOrBlank()) break

                    // Retry up to 2 attempts per URL: normal timeout, then extended on SocketTimeout
                    for (timeoutAttempt in 0..1) {
                        if (!jsonText.isNullOrBlank()) break

                    try {
                        val useExtended = timeoutAttempt > 0
                        if (useExtended) {
                            Log.w("ReinforcementVM", "⏱️ Retrying with extended timeout (300s) on $candidateBaseUrl")
                        } else {
                            Log.d("ReinforcementVM", "➡️ Attempting /procesar-prompt on $candidateBaseUrl")
                        }

                        val api = createApi(candidateBaseUrl, extendedTimeout = useExtended)
                        val requestBody = MicroservicioPromptRequest(
                            prompt = prompt,
                            jsonContent = jsonContentString,
                            ollamaUrl = candidateBaseUrl.trimEnd('/'),
                            model = "qwen/qwen3-embedding-8b",
                            userId = if (userId > 0) userId else null,
                            courseId = if (courseId > 0) courseId else null,
                            topicId = if (topicId > -1L) topicId else null,
                            taskId = if (taskId > -1L) taskId else null,
                            freeLearning = if (isFreeLearning) true else null
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
                        val isTimeout = e is SocketTimeoutException ||
                            e.cause is SocketTimeoutException ||
                            (e.message?.contains("timeout", ignoreCase = true) == true)

                        if (isDnsError) {
                            Log.w("ReinforcementVM", "⚠️ DNS failure on $candidateBaseUrl, trying fallback host. Error=${e.message}")
                            break // DNS error won't be fixed by retrying same URL
                        } else if (isTimeout && timeoutAttempt == 0) {
                            Log.w("ReinforcementVM", "⏱️ Timeout on $candidateBaseUrl (attempt ${timeoutAttempt + 1}), will retry with extended timeout")
                            // Continue inner loop to retry with extended timeout
                        } else {
                            Log.e("ReinforcementVM", "❌ API call failed on $candidateBaseUrl: ${e.message}")
                            break // Non-retryable or already retried
                        }
                    }
                    } // end timeoutAttempt loop
                }

                if (jsonText.isNullOrBlank()) {
                    Log.w("ReinforcementVM", "⚠️ Respuesta del servidor vacía o nula. Error: ${lastError} — se usarán preguntas de respaldo")
                    // Don't throw — let the fallback question generator below handle it
                }

                Log.d("ReinforcementVM", "Raw LLM response: $jsonText")

                // Robust JSON extraction
                val jsonPayload = jsonText.orEmpty()
                val startIndex = jsonPayload.indexOf('[')
                val endIndex = jsonPayload.lastIndexOf(']')

                var questions: List<QuizQuestion> = emptyList()

                if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
                    var cleanJson = jsonPayload.substring(startIndex, endIndex + 1)
                    
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

                    // FALLBACK GENERATOR: Blend topic and task titles for seed variety
                    val fallback = mutableListOf<QuizQuestion>()
                    val seeds: List<String> = buildList {
                        if (topicName.isNotBlank()) add(topicName)
                        if (taskName.isNotBlank()) add(taskName)
                        // Blended seed combining both
                        if (topicName.isNotBlank() && taskName.isNotBlank()) {
                            add("$topicName aplicado en $taskName")
                        }
                    }

                    val effectiveSeeds = if (seeds.isNotEmpty()) seeds else listOf("Conceptos Generales", "Fundamentos", "Práctica", "Teoría", "Análisis")

                    // Generate exactly targetCount fallback questions if possible
                    for (i in 1..targetCount) {
                        val seedIndex = (i - 1) % effectiveSeeds.size
                        val rawSeed = effectiveSeeds[seedIndex].trim()
                        val seedLabel = if (rawSeed.isEmpty()) "este tema" else rawSeed

                        // Randomize content to avoid "preguntas iguales"
                        val uniqueSuffix = (System.nanoTime() % 1000).toString()

                        val variants = listOf(
                            "¿Por qué es importante comprender '$seedLabel' antes de aplicarlo en la práctica?",
                            "Si tuvieras que explicar '$seedLabel' a un compañero, ¿cuál sería el aspecto más relevante a destacar?",
                            "¿Qué consecuencia tendría ignorar los principios fundamentales de '$seedLabel' en un proyecto real?",
                            "Comparando diferentes enfoques dentro de '$seedLabel', ¿cuál demuestra mayor utilidad práctica y por qué?",
                            "¿Qué problema resuelve '$seedLabel' que no podría resolverse sin este concepto?"
                        )
                        val questionText = "${variants[i % variants.size]} (Ref: $uniqueSuffix)"

                        val correctOption = "Comprensión aplicada del propósito central de $seedLabel"
                        val distractorBase = listOf(
                            "Interpretación superficial que confunde causa con efecto",
                            "Enfoque que ignora el contexto práctico de aplicación",
                            "Razonamiento que invierte la relación entre conceptos",
                            "Conclusión basada en una generalización incorrecta"
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
                            explanation = "La respuesta correcta es '${options[correctIndex]}' porque requiere comprender el propósito y la aplicación de $seedLabel, no simplemente recordar un dato."
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

                    // Retry if we still don't have enough unique questions for the current targetCount.
                    // Uses escalating prompt strategies on each retry to force different question styles.
                    if (uniqueQuestions.size < targetCount && retryAttempt < MAX_RETRY_ATTEMPTS) {
                        Log.w("ReinforcementVM", "⚠️ Only ${uniqueQuestions.size}/$targetCount unique questions after local dedup. Retrying with escalated strategy (attempt ${retryAttempt + 1}/$MAX_RETRY_ATTEMPTS)...")
                        val allExcluded = (existingQuestionTexts + sanitizedGenerated.map { it.question }).distinct()
                        loadQuestionsInternal(courseId, courseName, topicId, taskId, retryAttempt + 1, allExcluded, accumulatedQuestions, targetCount, cachedContextData = effectiveContextData)
                        return@launch
                    }

                    val fallbackSeeds = buildList {
                        if (topicName.isNotBlank()) add(topicName)
                        if (taskName.isNotBlank()) add(taskName)
                        if (topicName.isNotBlank() && taskName.isNotBlank()) {
                            add("$topicName en $taskName")
                        }
                    }.ifEmpty {
                        listOf("Conceptos Generales", "Fundamentos", "Práctica", "Teoría", "Análisis")
                    }

                    var finalQuestions = uniqueQuestions.take(targetCount)

                    if (finalQuestions.size < targetCount) {
                        val fallbackCandidates = sanitizeQuestionsForUniqueness(generateFallbackQuestionsFromSeeds(fallbackSeeds))
                        val combinedExisting = (existingQuestionTexts + finalQuestions.map { it.question }).distinct()
                        val fallbackUnique = filterDuplicateQuestions(fallbackCandidates, combinedExisting)
                        finalQuestions = (finalQuestions + fallbackUnique)
                            .distinctBy { normalizeForComparison(it.question) }
                            .take(targetCount)

                        if (finalQuestions.size < targetCount) {
                            val relaxedPool = (sanitizedGenerated + fallbackCandidates)
                                .distinctBy { normalizeForComparison(it.question) }
                                .filterNot { candidate ->
                                    finalQuestions.any { existing ->
                                        normalizeForComparison(existing.question) == normalizeForComparison(candidate.question)
                                    }
                                }

                            finalQuestions = (finalQuestions + relaxedPool)
                                .distinctBy { normalizeForComparison(it.question) }
                                .take(targetCount)
                        }
                    }

                    if (finalQuestions.size < targetCount) {
                        val attemptsUsed = retryAttempt + 1
                        Log.w("ReinforcementVM", "⚠️ Could not build $targetCount unique-after-history questions after $attemptsUsed attempts; continuing with ${finalQuestions.size} available " +
                            "(ragPage=${ragPage + 1}/$ragTotalPages, totalChunks=$ragTotalChunks, existing=${existingQuestionTexts.size})")
                    }

                    if (finalQuestions.isEmpty()) {
                        // Last resort: generate fallback questions instead of showing error
                        Log.w("ReinforcementVM", "⚠️ No unique questions after all attempts — generating fallback")
                        val lastResortSeeds = buildList {
                            if (topicName.isNotBlank()) add(topicName)
                            if (taskName.isNotBlank()) add(taskName)
                            if (topicName.isNotBlank() && taskName.isNotBlank()) add("$topicName en $taskName")
                        }.ifEmpty { listOf(courseName.ifBlank { "Conceptos Generales" }) }
                        val lastResortQuestions = redistributeCorrectOptionPositions(
                            sanitizeQuestionsForUniqueness(generateFallbackQuestionsFromSeeds(lastResortSeeds))
                        )
                        if (lastResortQuestions.isNotEmpty()) {
                            _uiState.value = ReinforcementState.Success(lastResortQuestions)
                        } else {
                            _uiState.value = ReinforcementState.Error(
                                "No fue posible construir preguntas válidas para este material. Intenta con otro tópico o tarea."
                            )
                        }
                        clearPendingTaskData()
                        return@launch
                    }

                    finalQuestions = redistributeCorrectOptionPositions(finalQuestions)

                    var shouldRetry = false
                    var retryExcluded = emptyList<String>()
                    var retryShortfall = targetCount
                    var retryAccumulated = accumulatedQuestions
                    var serverAccumulatedQuestions: List<QuizQuestion>? = null
                    
                    if (userId > 0) {
                        try {
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
                                    val cumulativeCount = data?.get("cumulativeCount")?.asInt ?: savedCount
                                    val allDuplicates = data?.get("allDuplicates")?.asBoolean ?: false
                                    val requiredCount = data?.get("requiredCount")?.asInt ?: 10
                                    val shortfall = data?.get("shortfall")?.asInt
                                        ?: if (cumulativeCount < requiredCount) (requiredCount - cumulativeCount) else 0
                                    val isComplete = cumulativeCount >= requiredCount

                                    Log.d("ReinforcementVM", "🔢 ACUMULATIVO: savedThisCycle=$savedCount, cumulative=$cumulativeCount/$requiredCount, shortfall=$shortfall, isComplete=$isComplete")

                                    val serverAccQ = try {
                                        data?.getAsJsonArray("accumulatedQuestions")?.mapNotNull { elem ->
                                            val obj = elem.asJsonObject ?: return@mapNotNull null
                                            val q = obj.get("question")?.asString ?: return@mapNotNull null
                                            val opts = obj.getAsJsonArray("options")?.map { it.asString } ?: return@mapNotNull null
                                            val ci = obj.get("correctIndex")?.asInt ?: 0
                                            val exp = obj.get("explanation")?.asString ?: ""
                                            QuizQuestion(q, opts, ci, exp)
                                        }
                                    } catch (_: Exception) { null }

                                    if (isComplete && !serverAccQ.isNullOrEmpty()) {
                                        serverAccumulatedQuestions = serverAccQ
                                        Log.d("ReinforcementVM", "✅ LOTE COMPLETO: $cumulativeCount/$requiredCount preguntas únicas acumuladas. Mostrando las ${serverAccQ.size} del servidor.")
                                    } else if (shortfall > 0 && retryAttempt < MAX_RETRY_ATTEMPTS) {
                                        Log.w("ReinforcementVM", "⚠️ Ciclo parcial: $savedCount nuevas, $cumulativeCount/$requiredCount acumuladas. Faltan $shortfall — regenerando (intento ${retryAttempt + 1}/$MAX_RETRY_ATTEMPTS)")
                                        val backendExisting = try {
                                            (data?.getAsJsonArray("accumulatedQuestions") ?: data?.getAsJsonArray("existingQuestions"))?.mapNotNull { elem ->
                                                elem.asJsonObject?.get("question")?.asString
                                            } ?: emptyList()
                                        } catch (_: Exception) { emptyList() }
                                        retryExcluded = (existingQuestionTexts + finalQuestions.map { it.question } + backendExisting).distinct()
                                        retryShortfall = shortfall
                                        retryAccumulated = serverAccQ ?: accumulatedQuestions
                                        shouldRetry = true
                                    } else if (isComplete) {
                                        serverAccumulatedQuestions = (accumulatedQuestions + finalQuestions)
                                            .distinctBy { normalizeForComparison(it.question) }
                                        Log.d("ReinforcementVM", "✅ Completo sin accumulatedQuestions del server, usando local: ${serverAccumulatedQuestions?.size}")
                                    } else {
                                        Log.d("ReinforcementVM", "✅ Acumulativo $cumulativeCount/$requiredCount (sin más reintentos)")
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

                    if (shouldRetry) {
                        loadQuestionsInternal(
                            courseId, courseName, topicId, taskId,
                            retryAttempt + 1, retryExcluded,
                            retryAccumulated, retryShortfall,
                            cachedContextData = effectiveContextData
                        )
                        return@launch
                    }

                    val questionsToShow = if (!serverAccumulatedQuestions.isNullOrEmpty()) {
                        Log.d("ReinforcementVM", "📋 Mostrando ${serverAccumulatedQuestions!!.size} preguntas acumuladas del servidor (sin repetidas)")
                        redistributeCorrectOptionPositions(serverAccumulatedQuestions!!.take(10))
                    } else {
                        val combined = (accumulatedQuestions + finalQuestions)
                            .distinctBy { normalizeForComparison(it.question) }
                            .take(10)
                        Log.d("ReinforcementVM", "📋 Mostrando ${combined.size} preguntas (accumulated=${accumulatedQuestions.size} + batch=${finalQuestions.size})")
                        redistributeCorrectOptionPositions(combined)
                    }
                    _uiState.value = ReinforcementState.Success(questionsToShow)
                }
                
                // Clear pending data - task completed successfully
                clearPendingTaskData()

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Coroutine cancelled (user navigated away) — do NOT show error
                Log.w("ReinforcementVM", "Question loading cancelled (navigation or scope cleared)")
                clearPendingTaskData()
                throw e // Re-throw to respect structured concurrency
            } catch (e: Exception) {
                Log.e("ReinforcementVM", "Error loading questions — generating fallback", e)
                // Instead of showing error to user, generate fallback questions
                val fallbackTopicName = _selectedTopicName.value ?: ""
                val fallbackTaskName = _selectedTaskName.value ?: ""
                val seeds = buildList {
                    if (fallbackTopicName.isNotBlank() && fallbackTopicName != "General") add(fallbackTopicName)
                    if (fallbackTaskName.isNotBlank() && fallbackTaskName != "General") add(fallbackTaskName)
                    if (fallbackTopicName.isNotBlank() && fallbackTaskName.isNotBlank()
                        && fallbackTopicName != "General" && fallbackTaskName != "General") {
                        add("$fallbackTopicName aplicado en $fallbackTaskName")
                    }
                }.ifEmpty { listOf(courseName.ifBlank { "Conceptos Generales" }) }
                val fallbackQuestions = redistributeCorrectOptionPositions(
                    sanitizeQuestionsForUniqueness(generateFallbackQuestionsFromSeeds(seeds))
                )
                if (fallbackQuestions.isNotEmpty()) {
                    Log.w("ReinforcementVM", "✅ Fallback: showing ${fallbackQuestions.size} locally-generated questions")
                    _uiState.value = ReinforcementState.Success(fallbackQuestions)
                } else {
                    _uiState.value = ReinforcementState.Error("No fue posible generar preguntas. Verifica tu conexión e intenta de nuevo.")
                }
                clearPendingTaskData()
            }
        }
    }

    /**
     * Builds a composite semantic search query from available context.
     * Used for Hybrid Retrieval to find the most relevant chunks via embedding similarity.
     * Combines course name, topic name, and task name for optimal retrieval
     * that covers material from BOTH sources.
     */
    private fun buildSemanticQuery(courseName: String, topicId: Long, taskId: Long): String {
        val parts = mutableListOf<String>()
        // DO NOT include courseName — it causes overly general retrieval.
        // Topic/task names may not be available yet on first call;
        // the backend will build a proper query from DB topic/task details.

        val topic = _selectedTopicName.value
        val task = _selectedTaskName.value

        if (!topic.isNullOrBlank() && topic != "General") parts.add(topic)
        if (!task.isNullOrBlank() && task != "General") parts.add(task)

        // Duplicate both names together for higher semantic weight
        if (!topic.isNullOrBlank() && topic != "General" && !task.isNullOrBlank() && task != "General") {
            parts.add("$topic $task")
        }

        // Return empty string if no topic/task names available yet;
        // the backend LearningContextService._buildSearchQuery() will
        // construct the proper query from topic/task titles in the DB.
        return parts.joinToString(" ").trim()
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
                    _uiState.value = ReinforcementState.Success(redistributeCorrectOptionPositions(questions))
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
                "¿Por qué es importante comprender '$seedLabel' antes de aplicarlo en la práctica?",
                "Si tuvieras que explicar '$seedLabel' a un compañero, ¿cuál sería el aspecto más relevante a destacar?",
                "¿Qué consecuencia tendría ignorar los principios fundamentales de '$seedLabel' en un proyecto real?",
                "Comparando diferentes enfoques dentro de '$seedLabel', ¿cuál demuestra mayor utilidad práctica y por qué?",
                "¿Qué problema resuelve '$seedLabel' que no podría resolverse sin este concepto?"
            )
            val questionText = "${variants[i % variants.size]} (Ref: $uniqueSuffix)"
            val correctOption = "Comprensión aplicada del propósito central de $seedLabel"
            val distractorBase = listOf(
                "Interpretación superficial que confunde causa con efecto",
                "Enfoque que ignora el contexto práctico de aplicación",
                "Razonamiento que invierte la relación entre conceptos",
                "Conclusión basada en una generalización incorrecta"
            )
            val options = listOf(correctOption) + distractorBase.shuffled().take(3)
            val shuffled = options.shuffled()
            val correctIndex = shuffled.indexOfFirst { it == correctOption }.coerceAtLeast(0)
            val explanation = "La respuesta correcta es: \"$correctOption\" porque requiere comprender el propósito y la aplicación de $seedLabel, no simplemente recordar un dato."
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
     * Redistributes the correct option position across A/B/C/D so it doesn't stay fixed on A.
     * Keeps the same correct answer text and recalculates correctIndex.
     */
    private fun redistributeCorrectOptionPositions(questions: List<QuizQuestion>): List<QuizQuestion> {
        if (questions.isEmpty()) return emptyList()

        val startIndex = Random.nextInt(4)

        return questions.mapIndexed { questionIndex, question ->
            val normalizedOptions = question.options
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { normalizeForComparison(it) }
                .take(4)

            if (normalizedOptions.size < 4) return@mapIndexed question

            val sourceCorrectIndex = question.correctIndex.coerceIn(0, normalizedOptions.lastIndex)
            val correctOption = normalizedOptions[sourceCorrectIndex]
            val distractors = normalizedOptions
                .filterIndexed { idx, _ -> idx != sourceCorrectIndex }
                .shuffled()

            val targetCorrectIndex = (startIndex + questionIndex) % 4
            val redistributed = MutableList(4) { "" }
            redistributed[targetCorrectIndex] = correctOption

            var distractorCursor = 0
            for (i in redistributed.indices) {
                if (i == targetCorrectIndex) continue
                redistributed[i] = distractors[distractorCursor++]
            }

            question.copy(options = redistributed, correctIndex = targetCorrectIndex)
        }
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
                isLikelyDuplicate(normalizedQ, existing)
            }
            if (isDuplicate) {
                Log.d("ReinforcementVM", "🚫 Local dup filtered: ${q.question.take(60)}...")
            }
            !isDuplicate
        }
    }

    private fun isLikelyDuplicate(a: String, b: String): Boolean {
        if (a == b) return true

        val similarity = jaccardSimilarity(a, b)
        if (similarity < DUPLICATE_SIMILARITY_THRESHOLD) return false

        val tokensA = a.split(" ").filter { it.length > 2 }
        val tokensB = b.split(" ").filter { it.length > 2 }
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false

        val minSize = minOf(tokensA.size, tokensB.size).toDouble()
        val maxSize = maxOf(tokensA.size, tokensB.size).toDouble()
        val sizeRatio = if (maxSize > 0) minSize / maxSize else 0.0

        return sizeRatio >= 0.75
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
    fun forceRegenerateQuestions(courseId: Long, courseName: String, topicId: Long = -1L, taskId: Long = -1L, difficulty: String = currentDifficulty) {
        Log.d("ReinforcementVM", "═══════════════════════════════════════════════════")
        Log.d("ReinforcementVM", "🔄 forceRegenerateQuestions() CALLED!")
        Log.d("ReinforcementVM", "   📚 courseId: $courseId")
        Log.d("ReinforcementVM", "   📖 courseName: $courseName")
        Log.d("ReinforcementVM", "   📑 topicId: $topicId")
        Log.d("ReinforcementVM", "   📝 taskId: $taskId")
        Log.d("ReinforcementVM", "   🎯 difficulty: $difficulty")
        Log.d("ReinforcementVM", "═══════════════════════════════════════════════════")

        currentDifficulty = difficulty
        
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
