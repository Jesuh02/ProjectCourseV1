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
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
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
                if (topics.isNotEmpty()) {
                    contextBuilder.append("Temas (Plan de estudios): ${topics.joinToString { it.name }}\n")
                }
                if (tasks.isNotEmpty()) {
                    contextBuilder.append("Tareas (Instrucciones del docente): ${tasks.joinToString { it.name }}\n")
                }
                if (contentItems.isNotEmpty()) {
                    contextBuilder.append("Materiales del Docente (Documentos/Videos/Imágenes) adjuntos.\n")
                }

                // Serialize content items to JSON for backend processing
                val contentList = contentItems.map { 
                    mapOf(
                        "name" to (it.name ?: "Sin nombre"),
                        "uri" to (it.uriString ?: ""),
                        "type" to (it.contentType ?: "application/octet-stream")
                    )
                }
                val jsonContentString = Gson().toJson(contentList)

                val prompt = """
                    Eres un profesor experto en Programación y Desarrollo de Software. Tu tarea es crear 5 preguntas de opción múltiple EXCLUSIVAMENTE basadas en el material proporcionado por el docente (Temas, Tareas y Archivos adjuntos).
                    
                    ENFOQUE PRIORITARIO (IMPORTANTE):
                    1. Céntrate fuertemente en conceptos técnicos de programación, lógica de código, sintaxis y arquitectura de software presentes en el material.
                    2. Prioriza preguntas que evalúen la comprensión del código o la teoría técnica sobre preguntas administrativas o generales.
                    3. Si hay código en los materiales, haz preguntas sobre su funcionamiento, errores potenciales o salida esperada.
                    
                    RESTRICCIONES ESTRICTAS:
                    1. NO utilices, menciones ni analices ninguna entrega, respuesta o trabajo de estudiantes.
                    2. Solo utiliza la información contenida en el contexto proporcionado abajo.
                    3. Si el contexto incluye ejemplos de entregas (lo cual no debería), ignóralos por completo.
                    
                    REGLAS DE FORMATO:
                    1. Responde ÚNICAMENTE con el JSON. Nada de texto antes ni después.
                    2. NO uses bloques de código markdown (no uses ```json).
                    3. El formato debe ser un array de objetos JSON exacto.
                    
                    Estructura requerida:
                    [
                      {
                        "question": "¿Pregunta técnica?",
                        "options": ["A) ...", "B) ...", "C) ...", "D) ..."],
                        "correctIndex": 0,
                        "explanation": "Explicación técnica detallada..."
                      }
                    ]
                    
                    Contexto (Material del Docente):
                    $contextBuilder
                """.trimIndent()

                // 4. Call LLM via Backend
                val response = api.procesarPrompt(
                    MicroservicioPromptRequest(
                        prompt = prompt,
                        jsonContent = jsonContentString, // Pass file metadata here
                        ollamaUrl = OLLAMA_URL,
                        model = "llama3" // Use llama3 for better JSON reliability if deepseek is unstable
                    )
                )

                val jsonText = response.respuesta_texto 
                    ?: throw Exception(response.error ?: "Respuesta vacía del servidor")
                
                Log.d("ReinforcementVM", "Raw LLM response: $jsonText")

                // Robust JSON extraction
                val startIndex = jsonText.indexOf('[')
                val endIndex = jsonText.lastIndexOf(']')

                val questions: List<QuizQuestion>

                if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) {
                    // Backend returned a human-readable message (e.g., grading report) instead of the required JSON array.
                    // Log and attempt a safe local fallback: generate simple questions from available topics/tasks.
                    Log.w("ReinforcementVM", "No JSON array found in LLM response; using fallback. Raw response: $jsonText")

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
                    } else {
                        throw Exception("No se encontró un array JSON válido en la respuesta y no hay contenido local para generar preguntas. Respuesta cruda: $jsonText")
                    }
                } else {
                    val cleanJson = jsonText.substring(startIndex, endIndex + 1)
                    val type = object : TypeToken<List<QuizQuestion>>() {}.type
                    questions = Gson().fromJson(cleanJson, type)
                }

                if (questions.isEmpty()) {
                    _uiState.value = ReinforcementState.Error("El modelo no generó preguntas válidas.")
                } else {
                    _uiState.value = ReinforcementState.Success(questions)
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
