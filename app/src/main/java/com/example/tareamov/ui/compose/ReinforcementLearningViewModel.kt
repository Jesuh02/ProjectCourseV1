package com.example.tareamov.ui.compose

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.sync.SyncRepository
import com.example.tareamov.network.MicroservicioApi
import com.example.tareamov.network.MicroservicioPromptRequest
import com.example.tareamov.service.ServerEndpointResolver
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

data class QuizQuestion(
    val question: String,
    val options: List<String>,
    val correctIndex: Int,
    val explanation: String
)

sealed class ReinforcementState {
    object Initial : ReinforcementState()
    object Loading : ReinforcementState()
    data class Success(val questions: List<QuizQuestion>) : ReinforcementState()
    data class Error(val message: String) : ReinforcementState()
}

class ReinforcementLearningViewModel(
    application: Application,
    private val syncRepository: SyncRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<ReinforcementState>(ReinforcementState.Initial)
    val uiState: StateFlow<ReinforcementState> = _uiState.asStateFlow()

    private val _currentScore = MutableStateFlow(0)
    val currentScore: StateFlow<Int> = _currentScore.asStateFlow()

    // Configuration matching ChatBotFragment
    private val BASE_URL = "https://mcp-backenddeploy-production.up.railway.app/"
    private val OLLAMA_URL = "https://mcp-backenddeploy-production.up.railway.app"
    private val API_KEY = "tareamov-mcp-api-key-2025-secure"

    init {
        // Initialize the ServerEndpointResolver with the application context
        ServerEndpointResolver.initialize(application)
    }

    private fun createApi(): MicroservicioApi {
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val requestWithApiKey = originalRequest.newBuilder()
                    .header("X-API-Key", API_KEY)
                    .build()
                chain.proceed(requestWithApiKey)
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit.create(MicroservicioApi::class.java)
    }

    fun loadQuestions(courseId: Long, courseName: String) {
        if (courseId == -1L) {
            _uiState.value = ReinforcementState.Error("ID de curso inválido.")
            return
        }

        viewModelScope.launch {
            _uiState.value = ReinforcementState.Loading
            try {
                // 1. Create API using production configuration
                val api = createApi()
                
                // 2. Fetch Context from Repository
                // Use IO dispatcher explicitly for DB operations
                val (topics, tasks, contentItems) = withContext(Dispatchers.IO) {
                    val t = syncRepository.fetchTopicsByCourseFromSupabase(courseId)
                    val tIds = t.map { it.id }
                    val k = if (tIds.isNotEmpty()) {
                        syncRepository.fetchTasksByTopicIdsFromSupabase(tIds)
                    } else {
                        emptyList()
                    }
                    val c = if (tIds.isNotEmpty()) {
                        syncRepository.fetchContentItemsByTopicIdsFromSupabase(tIds)
                    } else {
                        emptyList()
                    }
                    Triple(t, k, c)
                }

                if (topics.isEmpty() && tasks.isEmpty() && contentItems.isEmpty()) {
                    _uiState.value = ReinforcementState.Error("Este curso no tiene contenido suficiente (temas, tareas o materiales) para generar preguntas.")
                    return@launch
                }

                // 3. Build Prompt (Concise)
                val contextBuilder = StringBuilder()
                contextBuilder.append("Curso: $courseName\n")
                
                // Add content items (Files) FIRST to give them priority in context
                if (contentItems.isNotEmpty()) {
                    contextBuilder.append("Materiales del Docente (Documentos/Videos/Imágenes) adjuntos - PRIORIDAD ALTA:\n")
                    // Note: Actual content is appended by backend, this is just a marker
                }

                if (topics.isNotEmpty()) {
                    contextBuilder.append("Temas (Plan de estudios): ${topics.joinToString { it.name }}\n")
                }
                if (tasks.isNotEmpty()) {
                    // FILTERED: Only include tasks that belong to the course structure (Instructions), 
                    // NOT user submissions. These tasks come from 'fetchTasksByTopicIdsFromSupabase'
                    // which retrieves Teacher-defined tasks, so this is already correct by definition of the repository method.
                    contextBuilder.append("Tareas (Instrucciones del docente) - PRIORIDAD BAJA: ${tasks.joinToString { it.name }}\n")
                }

                // Explicitly EXCLUDE any user submission content or other external context
                // The 'contentItems' here are strictly Course Materials (PDFs/Docs uploaded by teacher)
                // retrieved via 'fetchContentItemsByTopicIdsFromSupabase'.

                // Serialize content items to JSON for backend processing
                // NOTE: 'contentItems' comes from fetchContentItemsByTopicIdsFromSupabase (Teacher materials)
                // It does NOT include FileContext (User submissions).
                val contentList = contentItems.map { 
                    mapOf(
                        "name" to (it.name ?: "Sin nombre"),
                        "uri" to (it.uriString ?: ""),
                        "type" to (it.contentType ?: "application/octet-stream")
                    )
                }
                val jsonContentString = Gson().toJson(contentList)

                // LOGGING FILES FOR DEBUGGING (User Request)
                Log.d("ReinforcementVM", "📂 Files prepared for LLM context: ${contentList.size} files")
                contentList.forEachIndexed { index, file ->
                    Log.d("ReinforcementVM", "   [$index] Name: ${file["name"]}, URI: ${file["uri"]}")
                }

                val prompt = """
                    Eres un profesor experto en Programación y Desarrollo de Software. Tu tarea es crear una progresión de 10 preguntas de opción múltiple, divididas en 3 niveles de dificultad creciente (3 Introductivas, 4 Técnicas, 3 Avanzadas), basadas EXCLUSIVAMENTE en el material proporcionado.
                    
                    ESTRUCTURA DE DIFICULTAD REQUERIDA (ORDEN ESTRICTO):
                    1. Preguntas 1-3 (Nivel Introductorio): Conceptos básicos y definiciones técnicas.
                    2. Preguntas 4-7 (Nivel Técnico): Sintaxis específica, lógica de código y estructuras de datos.
                    3. Preguntas 8-10 (Nivel Avanzado): Análisis complejo, optimización y casos borde.
                    
                    ENFOQUE PRIORITARIO:
                    1. BASA TUS PREGUNTAS ÚNICA Y EXCLUSIVAMENTE EN EL CONTENIDO DE LOS ARCHIVOS ADJUNTOS.
                    2. Si hay código, las preguntas técnicas y avanzadas deben retar al estudiante a entender qué hace.
                    3. SI EL CONTEXTO INCLUYE ARCHIVOS, IGNORA LOS NOMBRES DE LAS TAREAS.
                    
                    RESTRICCIONES ESTRICTAS:
                    1. NO utilices datos de estudiantes.
                    2. NO generes preguntas sobre pedagogía.
                    3. Las preguntas deben ser 100% TÉCNICAS.
                    4. Solo utiliza la información del contexto abajo.
                    
                    REGLAS DE FORMATO:
                    1. Responde ÚNICAMENTE con el JSON. Nada de texto antes ni después.
                    2. NO uses bloques de código markdown.
                    3. El formato debe ser un array de objetos JSON exacto con 10 elementos.
                    
                    Estructura requerida:
                    [
                      {
                        "question": "¿Pregunta 1?",
                        "options": ["A) ...", "B) ...", "C) ...", "D) ..."],
                        "correctIndex": 0,
                        "explanation": "Explicación..."
                      },
                      ...
                    ]
                    
                    Contexto (Material del Docente):
                    $contextBuilder
                """.trimIndent()

                // 4. Call LLM via Backend
                // Get User ID from SessionManager
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(getApplication())
                val userId = sessionManager.getUserId()

                val response = api.procesarPrompt(
                    MicroservicioPromptRequest(
                        prompt = prompt,
                        jsonContent = jsonContentString, // Pass file metadata here
                        ollamaUrl = OLLAMA_URL,
                        model = "llama3", // Use llama3 for better JSON reliability if deepseek is unstable
                        userId = if (userId > 0) userId else null,
                        courseId = if (courseId > 0) courseId else null
                    )
                )

                val jsonText = response.respuesta_texto 
                    ?: throw Exception(response.error ?: "Respuesta vacía del servidor")
                
                Log.d("ReinforcementVM", "Raw LLM response: $jsonText")

                // Robust JSON extraction
                val startIndex = jsonText.indexOf('[')
                val endIndex = jsonText.lastIndexOf(']')

                var questions: List<QuizQuestion> = emptyList()
                var attemptedParse = false

                if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
                    try {
                        val cleanJson = jsonText.substring(startIndex, endIndex + 1)
                        val type = object : TypeToken<List<QuizQuestion>>() {}.type
                        questions = Gson().fromJson(cleanJson, type)
                        attemptedParse = true
                    } catch (e: Exception) {
                        Log.w("ReinforcementVM", "Failed to parse JSON: ${e.message}")
                    }
                }

                // If parsing failed, returned empty (deduplication), or no JSON found -> Use Fallback
                if (questions.isEmpty()) {
                    Log.w("ReinforcementVM", "No valid questions from LLM (empty or parse error); using fallback. Raw: $jsonText")

                    val fallback = mutableListOf<QuizQuestion>()
                    val seeds: List<String> = when {
                        topics.isNotEmpty() -> topics.map { it.name }
                        tasks.isNotEmpty() -> tasks.map { it.name }
                        else -> emptyList()
                    }

                    if (seeds.isNotEmpty()) {
                        val limit = kotlin.math.min(5, seeds.size)
                        for (i in 0 until limit) {
                            val rawSeed = seeds[i].trim()
                            // Sanitize seed: avoid numeric-only labels
                            val seedLabel = if (rawSeed.matches(Regex("^\\d+$")) || rawSeed.isEmpty()) {
                                "este tema"
                            } else {
                                rawSeed
                            }

                            // Build one correct textual option and three distinct distractors
                            val correctOption = "Descripción correcta sobre $seedLabel"
                            val distractorBase = listOf(
                                "Ejemplo práctico relacionado con $seedLabel",
                                "Aplicación típica de $seedLabel",
                                "Definición comúnmente asociada a $seedLabel"
                            )

                            // Ensure uniqueness and produce options list
                            val options = mutableListOf<String>()
                            options.addAll(distractorBase.take(3))
                            options.add(correctOption)

                            // Ensure options are unique (append suffix if needed)
                            val uniqueOptions = mutableListOf<String>()
                            for (s in options) {
                                var candidate = s
                                var suffix = 1
                                while (uniqueOptions.contains(candidate)) {
                                    candidate = "$s ($suffix)"
                                    suffix++
                                }
                                uniqueOptions.add(candidate)
                            }

                            // Shuffle and compute correct index
                            uniqueOptions.shuffle()
                            val correctIndex = uniqueOptions.indexOfFirst { it == correctOption }
                                .takeIf { it >= 0 } ?: 0

                            fallback.add(
                                QuizQuestion(
                                    question = "Sobre el tema '$seedLabel', ¿cuál opción lo describe mejor?",
                                    options = uniqueOptions.toList(),
                                    correctIndex = correctIndex,
                                    explanation = "Pregunta de respaldo generada localmente."
                                )
                            )
                        }
                        questions = fallback
                    }
                }

                if (questions.isEmpty()) {
                    _uiState.value = ReinforcementState.Error("El modelo no generó preguntas válidas y no hay contenido suficiente para el respaldo.")
                } else {
                    _uiState.value = ReinforcementState.Success(questions)
                    
                    // Client-side saving removed to avoid duplication. 
                    // Backend (llmRoutes.js) now handles saving valid, non-duplicate questions directly.
                }

            } catch (e: Exception) {
                Log.e("ReinforcementVM", "Error loading questions", e)
                _uiState.value = ReinforcementState.Error("Error: ${e.message}")
            }
        }
    }

    fun loadPreloadedQuestions(questionsJson: String) {
        viewModelScope.launch {
            _uiState.value = ReinforcementState.Loading
            try {
                val questions: List<QuizQuestion> = Gson().fromJson(questionsJson, object : TypeToken<List<QuizQuestion>>() {}.type)
                if (questions.isEmpty()) {
                    _uiState.value = ReinforcementState.Error("No se encontraron preguntas válidas en los datos pre-cargados.")
                } else {
                    _uiState.value = ReinforcementState.Success(questions)
                }
            } catch (e: Exception) {
                Log.e("ReinforcementVM", "Error parsing preloaded questions", e)
                _uiState.value = ReinforcementState.Error("Error al cargar preguntas pre-cargadas: ${e.message}")
            }
        }
    }

    fun addScore(points: Int) {
        _currentScore.value += points
    }
    
    fun resetScore() {
        _currentScore.value = 0
    }
}
