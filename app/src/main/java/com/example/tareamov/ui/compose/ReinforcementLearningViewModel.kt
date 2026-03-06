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

    companion object {
        /** Maximum number of retry attempts when all generated questions are duplicates. */
        private const val MAX_RETRY_ATTEMPTS = 5
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
        targetCount: Int = 10
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

                // 2. Fetch unified Learning Context from backend (single API call)
                // Hybrid Retrieval: on retries, pass an explicit sessionIndex to
                // force the backend to return a DIFFERENT section of the document.
                // Also pass a query derived from topic/task for semantic search.
                // On the first attempt (retryAttempt=0), let the backend auto-calculate
                // the page based on how many questions already exist in history.
                val sessionIndex = if (retryAttempt > 0) retryAttempt else null
                
                // Build semantic search query from course/topic/task context
                val semanticQuery = buildSemanticQuery(courseName, topicId, taskId)
                // Hybrid retrieval v2: semantic + window expansion ONLY.
                // Progressive is used ONLY as fallback when semantic returns 0 results.
                // sessionIndex is passed for progressive fallback page calculation.
                val retrievalMode = "hybrid"
                
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

                // Parse the unified context response
                val contextData = when (learningContext) {
                    is ApiResult.Success -> learningContext.data
                    is ApiResult.Error -> {
                        Log.e("ReinforcementVM", "Failed to fetch learning context: ${learningContext.message}")
                        null
                    }
                }

                if (contextData == null) {
                    _uiState.value = ReinforcementState.Error("No se pudo obtener el contexto de aprendizaje del servidor.")
                    return@launch
                }

                // Extract topic details
                val topicObj = contextData.getAsJsonObject("topic")
                val topicName = topicObj?.get("name")?.asString ?: ""
                val topicDescription = topicObj?.get("description")?.asString ?: ""

                // Extract task details
                val taskObj = contextData.getAsJsonObject("task")
                val taskName = taskObj?.get("title")?.asString ?: ""
                val taskDescription = taskObj?.get("description")?.asString ?: ""

                // Extract RAG content (already ingested by the backend)
                val ragDocumentContent = contextData.get("ragContent")?.asString ?: ""
                val ragChunks = contextData.get("ragChunks")?.asInt ?: 0
                val ragPage = contextData.get("ragPage")?.asInt ?: 0
                val ragTotalPages = contextData.get("ragTotalPages")?.asInt ?: 1
                val ragTotalChunks = contextData.get("ragTotalChunks")?.asInt ?: ragChunks
                val ragFileNames = try {
                    contextData.getAsJsonArray("ragFiles")?.map { it.asString } ?: emptyList()
                } catch (_: Exception) { emptyList() }
                
                // Hybrid Retrieval metadata
                val activeRetrievalMode = contextData.get("retrievalMode")?.asString ?: "progressive"
                val semanticHits = contextData.get("semanticHits")?.asInt ?: 0
                val expandedChunks = contextData.get("expandedChunks")?.asInt ?: 0
                val usedProgressiveFallback = contextData.get("usedProgressiveFallback")?.asBoolean ?: false
                val wasReranked = contextData.get("reranked")?.asBoolean ?: false
                val rerankMethod = contextData.get("rerankMethod")?.asString ?: "none"

                // Extract content items metadata (combined — backward compatible)
                val contentItemsArray = try {
                    contextData.getAsJsonArray("contentItems") ?: com.google.gson.JsonArray()
                } catch (_: Exception) { com.google.gson.JsonArray() }

                // Extract content items separated by origin: topic vs task
                val topicContentItemsArray = try {
                    contextData.getAsJsonArray("topicContentItems") ?: com.google.gson.JsonArray()
                } catch (_: Exception) { com.google.gson.JsonArray() }
                val taskContentItemsArray = try {
                    contextData.getAsJsonArray("taskContentItems") ?: com.google.gson.JsonArray()
                } catch (_: Exception) { com.google.gson.JsonArray() }

                // Extract existing questions for deduplication
                val existingQuestionsArray = try {
                    contextData.getAsJsonArray("existingQuestions") ?: com.google.gson.JsonArray()
                } catch (_: Exception) { com.google.gson.JsonArray() }

                val existingQuestions = existingQuestionsArray.mapNotNull { elem ->
                    elem.asJsonObject?.get("question")?.asString
                }
                val existingQuestionTexts = (existingQuestions + previouslyExcluded)
                    .distinct()
                    .takeLast(80)
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

                // Validate sufficient content
                if (topicName.isBlank() && taskName.isBlank() && ragDocumentContent.isBlank()) {
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
                    - Genera preguntas variadas que cubran DIFERENTES conceptos, párrafos y datos del material proporcionado.
                    - Cada pregunta debe extraer información de un PÁRRAFO o SECCIÓN DISTINTA del texto.
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
                        ESTRATEGIA OBLIGATORIA: Genera preguntas sobre DETALLES NUMÉRICOS, EJEMPLOS CONCRETOS y DATOS ESPECÍFICOS de ESTA NUEVA sección.
                        Enfócate en cifras, nombres propios, fechas, cantidades, unidades y valores exactos mencionados en el texto.
                        NO preguntes sobre definiciones generales — esas YA EXISTEN.
                    """.trimIndent()
                    2 -> """
                        $shortfallNote
                        ⚠️ INTENTO DE REGENERACIÓN #$retryAttempt: TODAS las preguntas previas fueron duplicadas.
                        NOTA: Estás viendo la SECCIÓN ${ragPage + 1}/$ragTotalPages del documento — contenido NUEVO respecto al intento anterior.
                        ESTRATEGIA OBLIGATORIA: Genera preguntas de APLICACIÓN y ESCENARIOS PRÁCTICOS.
                        Cada pregunta debe plantear una SITUACIÓN HIPOTÉTICA donde el estudiante aplique los conceptos.
                        Formato sugerido: "Si un estudiante necesita...", "En una situación donde...", "¿Qué pasaría si...?"
                        NO repitas el estilo de preguntas de conocimiento directo.
                    """.trimIndent()
                    3 -> """
                        $shortfallNote
                        ⚠️ INTENTO DE REGENERACIÓN #$retryAttempt: AÚN se detectaron duplicados.
                        ESTRATEGIA OBLIGATORIA: Genera preguntas de COMPARACIÓN y CONTRASTE entre conceptos del material.
                        Cada pregunta debe comparar DOS o más elementos del texto.
                        Formato sugerido: "¿Cuál es la diferencia entre X e Y?", "¿En qué se parecen X e Y?", "Comparando X con Y..."
                        PROHIBIDO preguntar sobre un solo concepto aislado.
                    """.trimIndent()
                    4 -> """
                        $shortfallNote
                        ⚠️ INTENTO DE REGENERACIÓN #$retryAttempt: ÚLTIMO INTENTO.
                        ESTRATEGIA OBLIGATORIA: Genera preguntas de VERDADERO/FALSO reformuladas como opción múltiple.
                        Cada pregunta debe presentar una AFIRMACIÓN y preguntar si es correcta o incorrecta según el material.
                        Formato: "¿Cuál de las siguientes afirmaciones sobre [concepto] es CORRECTA/INCORRECTA?"
                        Usa afirmaciones que mezclen datos reales del material con datos inventados sutilmente incorrectos.
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
                    - 3 preguntas sobre conceptos del TEMA "$topicName" (definiciones, teoría, fundamentos)
                    - 3 preguntas sobre la TAREA "$taskName" (requisitos, procedimientos, aplicación práctica)
                    - 4 preguntas que INTEGREN AMBOS: apliquen conceptos del tema a la tarea o relacionen
                      la práctica de la tarea con la teoría del tema
                    
                    IMPORTANTE: Las preguntas integradoras deben mencionar elementos de AMBAS fuentes.
                    Ejemplo de pregunta integradora: "Según el tema '$topicName', ¿cómo se aplica [concepto] en la tarea '$taskName'?"
                    
                    ARCHIVOS FUENTE:
                    ${if (topicContentItemsArray.size() > 0) "- Archivos del Tema: ${(0 until topicContentItemsArray.size()).map { topicContentItemsArray[it].asJsonObject?.get("title")?.asString ?: "?" }.joinToString(", ")}" else "- Sin archivos del Tema"}
                    ${if (taskContentItemsArray.size() > 0) "- Archivos de la Tarea: ${(0 until taskContentItemsArray.size()).map { taskContentItemsArray[it].asJsonObject?.get("title")?.asString ?: "?" }.joinToString(", ")}" else "- Sin archivos de la Tarea"}
                    """.trimIndent()
                } else if (topicName.isNotBlank()) {
                    """
                    ENFOQUE: Genera preguntas centradas en el TEMA "$topicName".
                    Cubre conceptos, definiciones, procesos y aplicaciones del tema.
                    ${if (topicContentItemsArray.size() > 0) "Archivos del Tema: ${(0 until topicContentItemsArray.size()).map { topicContentItemsArray[it].asJsonObject?.get("title")?.asString ?: "?" }.joinToString(", ")}" else ""}
                    """.trimIndent()
                } else {
                    """
                    ENFOQUE: Genera preguntas centradas en la TAREA "$taskName".
                    Cubre requisitos, procedimientos y aplicación práctica de la tarea.
                    ${if (taskContentItemsArray.size() > 0) "Archivos de la Tarea: ${(0 until taskContentItemsArray.size()).map { taskContentItemsArray[it].asJsonObject?.get("title")?.asString ?: "?" }.joinToString(", ")}" else ""}
                    """.trimIndent()
                }

                val prompt = """
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
                    $retryDifferentiationInstruction
                    
                    ═══════════════════════════════════════════════════
                    INSTRUCCIÓN PEDAGÓGICA — NIVEL 10/10 (OBLIGATORIO ESTRICTO)
                    ═══════════════════════════════════════════════════
                    
                    PRINCIPIO #1 — EL ESTUDIANTE DEBE PENSAR, NO BUSCAR:
                    - PROHIBIDO preguntas donde el estudiante solo localiza un dato en el material.
                      Ejemplo PROHIBIDO: "¿Qué modelo tiene menor tiempo?" → solo buscar un número.
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
                    9. PROHIBIDO preguntas de puro recuerdo ("¿Qué es...?", "¿Cuál es...?", "¿Cómo se define...?", "¿Qué valor tiene...?").
                    10. Cada pregunta DEBE requerir que el estudiante ENTIENDA un mecanismo o implicación para poder responder.
                    
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

                    // Retry if we still don't have enough unique questions for the current targetCount.
                    // Uses escalating prompt strategies on each retry to force different question styles.
                    if (uniqueQuestions.size < targetCount && retryAttempt < MAX_RETRY_ATTEMPTS) {
                        Log.w("ReinforcementVM", "⚠️ Only ${uniqueQuestions.size}/$targetCount unique questions after local dedup. Retrying with escalated strategy (attempt ${retryAttempt + 1}/$MAX_RETRY_ATTEMPTS)...")
                        val allExcluded = (existingQuestionTexts + sanitizedGenerated.map { it.question }).distinct()
                        loadQuestionsInternal(courseId, courseName, topicId, taskId, retryAttempt + 1, allExcluded, accumulatedQuestions, targetCount)
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
                        _uiState.value = ReinforcementState.Error(
                            "No fue posible construir preguntas válidas para este material. Intenta con otro tópico o tarea."
                        )
                        clearPendingTaskData()
                        return@launch
                    }

                    // ── Save to backend BEFORE showing to user (to catch server-side duplicates) ──
                    var shouldRetry = false
                    var retryExcluded = emptyList<String>()
                    var retryShortfall = targetCount
                    var retryAccumulated = accumulatedQuestions
                    
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
                                    // shortfall = how many more unique questions are needed to complete the batch
                                    val shortfall = data?.get("shortfall")?.asInt
                                        ?: if (savedCount < requiredCount) (requiredCount - savedCount) else 0
                                    val isShortBatch = shortfall > 0

                                    if ((allDuplicates || isShortBatch) && retryAttempt < MAX_RETRY_ATTEMPTS) {
                                        Log.w("ReinforcementVM", "⚠️ Backend accepted only $savedCount/$requiredCount unique questions. " +
                                            "Shortfall=$shortfall — generating only missing questions (attempt ${retryAttempt + 1}/$MAX_RETRY_ATTEMPTS)...")
                                        val backendExisting = try {
                                            data?.getAsJsonArray("existingQuestions")?.mapNotNull { elem ->
                                                elem.asJsonObject?.get("question")?.asString
                                            } ?: emptyList()
                                        } catch (_: Exception) { emptyList() }
                                        retryExcluded = (existingQuestionTexts + finalQuestions.map { it.question } + backendExisting).distinct()
                                        // Accumulate the questions already saved so they are shown to the user at the end
                                        val newAccumulated = (accumulatedQuestions + finalQuestions)
                                            .distinctBy { normalizeForComparison(it.question) }
                                        retryShortfall = shortfall
                                        retryAccumulated = newAccumulated
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

                    // If backend flagged duplicates, retry generating ONLY the shortfall (not all 10)
                    if (shouldRetry) {
                        loadQuestionsInternal(
                            courseId, courseName, topicId, taskId,
                            retryAttempt + 1, retryExcluded,
                            retryAccumulated, retryShortfall
                        )
                        return@launch
                    }

                    // All checks passed — show unique questions to the user
                    // Combine accumulated questions (from previous rounds) with the new batch
                    val questionsToShow = (accumulatedQuestions + finalQuestions)
                        .distinctBy { normalizeForComparison(it.question) }
                        .take(10)
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
                Log.e("ReinforcementVM", "Error loading questions", e)
                _uiState.value = ReinforcementState.Error("Error: ${e.message}")
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
