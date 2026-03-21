package com.example.tareamov.ui

import android.os.Bundle
import android.text.Spannable
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.data.entity.ChatMessage
import com.example.tareamov.data.entity.FileContext
import com.example.tareamov.data.entity.TaskSubmission

import com.example.tareamov.service.TTSService
import com.example.tareamov.service.ServerEndpointResolver
import okhttp3.OkHttpClient
import okhttp3.Request
import com.google.gson.Gson
import com.example.tareamov.ui.adapter.ChatMessageAdapter
import com.example.tareamov.adapter.TaskOverlayAdapter
import com.example.tareamov.adapter.TaskItem
import com.example.tareamov.adapter.GradedTaskOverlayAdapter
import com.example.tareamov.adapter.GradedTaskItem
import com.example.tareamov.work.BackgroundTaskManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*


import com.example.tareamov.network.MicroservicioApi
import com.example.tareamov.network.MicroservicioPromptResponse
import com.example.tareamov.util.CalificationManager
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.HttpException
import java.net.SocketTimeoutException
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.tareamov.ui.widget.VoiceVisualizerView

class ChatBotFragment : Fragment() {

    // Local Room database – kept only for chat message storage (local-only data)
    private val database by lazy { com.example.tareamov.data.AppDatabase.getDatabase(requireContext()) }

    // Voice Recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceIntent: Intent? = null
    private var isListening = false
    private lateinit var voiceInputLayout: LinearLayout
    private lateinit var messageInputContainer: LinearLayout
    private lateinit var voiceVisualizer: VoiceVisualizerView
    private lateinit var stopRecordingButton: ImageButton
    private lateinit var cancelVoiceButton: ImageButton
    private lateinit var voiceStatusText: TextView
    private lateinit var micButton: ImageButton

    // Background task tracking
    private var isProcessingLLM = false
    private var pendingPrompt: String? = null
    private var pendingTaskDescription: String? = null
    private var pendingFileContent: String? = null
    private var pendingJsonContent: String? = null
    private var pendingMetadata: String? = null
    private var pendingSubmissionId: Long? = null
    private var pendingTaskId: Long? = null

    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startListening()
        } else {
            Toast.makeText(context, "Permiso de micrófono requerido para voz", Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearChat() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                database.chatMessageDao().clearAllMessages()
            }
            Toast.makeText(context, "Chat limpiado", Toast.LENGTH_SHORT).show()
            chatAdapter.submitList(emptyList())

            // Limpiar también la lista de tareas calificadas en memoria
            gradedTasksList.clear()
            gradedTaskOverlayAdapter.updateGradedTasks(emptyList())
        }
    }

    /**
     * Sincroniza un ChatMessage al backend de forma asíncrona (fire-and-forget).
     * Reemplaza los upserts directos a Supabase.
     */
    private fun syncChatMessageToBackend(chatMessage: ChatMessage, savedId: Long) {
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val toSync = chatMessage.copy(id = savedId)
                val result = BackendApiService.upsertChatMessage(toSync)
                if (result.isSuccess) {
                    Log.i("ChatBotFragment", "ChatMessage $savedId synced to backend.")
                } else {
                    Log.w("ChatBotFragment", "Failed to sync ChatMessage $savedId: ${result.errorMessage()}")
                }
            } catch (e: Exception) {
                Log.w("ChatBotFragment", "Exception syncing chat message to backend: ${e.message}")
            }
        }
    }

    /**
     * Envía la entrega al microservicio de análisis y luego consulta el feedback conversacional.
     * Usa submissionId para cache y eficiencia.
     */
    private suspend fun analizarYFeedbackRAG(userMessage: String, fileContext: FileContext?): String {
        val submissionId = fileContext?.submissionId ?: UUID.randomUUID().mostSignificantBits
        val fileContent = fileContext?.fileContent ?: ""
        val contentSummary = fileContext?.contentSummary ?: ""
        val ollamaUrl = getOllamaUrl()
        try {
            val analizarRequest = com.example.tareamov.network.AnalizarEntregaRequest(
                submissionId = submissionId,
                fileContent = fileContent,
                contentSummary = contentSummary,
                ollamaUrl = ollamaUrl
            )
            val analizarRes = microservicioApi.analizarEntrega(analizarRequest)
            val feedbackRequest = com.example.tareamov.network.FeedbackEntregaRequest(
                submissionId = submissionId,
                pregunta = userMessage,
                ollamaUrl = ollamaUrl
            )
            val feedbackRes = microservicioApi.feedbackEntrega(feedbackRequest)
            return feedbackRes.feedback ?: "No se pudo obtener feedback."
        } catch (e: Exception) {
            return "Error en RAG: ${e.message}"
        }
    }

    private lateinit var messagesRecyclerView: RecyclerView
    private lateinit var messageEditText: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var gradedTasksButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var clearChatButton: ImageButton
    private lateinit var loadingProgressBar: ProgressBar
    private lateinit var activeContextValue: TextView
    private lateinit var activeContextIcon: ImageView
    private lateinit var fabScrollToBottom: com.google.android.material.floatingactionbutton.FloatingActionButton

    // Voice components initialized in onViewCreated

    private lateinit var chatAdapter: ChatMessageAdapter

    private lateinit var ttsService: TTSService
    private lateinit var sessionManager: com.example.tareamov.util.SessionManager
    // Listener instance so we can remove it in onDestroyView
    private var sessionChangeListener: com.example.tareamov.util.SessionManager.UserChangeListener? = null

    private lateinit var mcpHttpClient: com.example.tareamov.service.MCPHttpClient

    override fun onAttach(context: android.content.Context) {
        super.onAttach(context)
        try {
            mcpHttpClient = com.example.tareamov.service.MCPHttpClient(requireContext())
        } catch (e: Exception) {
            Log.w("ChatBotFragment", "Failed to init MCPHttpClient: ${e.message}")
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // If a forced URL was saved (via prefs), honor it first
                ServerEndpointResolver.getForcedMcpBaseUrl()?.let { forced ->
                    try {
                        if (ServerEndpointResolver.isServiceReachable(forced)) {
                            mcpHttpClient.setForcedBaseUrl(forced)
                            Log.i("ChatBotFragment", "Using forced MCP URL from prefs: $forced")
                            return@launch
                        } else {
                            Log.w("ChatBotFragment", "Forced MCP URL not reachable: $forced")
                        }
                    } catch (_: Exception) {}
                }

                // Fast-resolve (gateway + subnet probes) with ServerEndpointResolver (short bounded wait)
                try {
                    val resolved = kotlinx.coroutines.withTimeoutOrNull(120) { ServerEndpointResolver.fastResolveMcpBaseUrl() }
                    if (!resolved.isNullOrBlank()) {
                        mcpHttpClient.setForcedBaseUrl(resolved)
                        Log.i("ChatBotFragment", "Fast-resolved MCP base URL: $resolved")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.d("ChatBotFragment", "fastResolve failed: ${e.message}")
                }

                // Fallback to cloud
                mcpHttpClient.setForcedBaseUrl(ServerEndpointResolver.RAILWAY_MCP_URL)
                Log.i("ChatBotFragment", "Falling back to cloud MCP: ${ServerEndpointResolver.RAILWAY_MCP_URL}")
            } catch (e: Exception) {
                Log.w("ChatBotFragment", "Error resolving MCP base: ${e.message}")
            }
        }
    }

    // Runtime host selection: choose correct host for emulator vs physical device
    private fun isRunningOnEmulator(): Boolean {
        val fingerprint = android.os.Build.FINGERPRINT ?: ""
        val model = android.os.Build.MODEL ?: ""
        return (fingerprint.contains("generic") || fingerprint.contains("unknown")
                || model.contains("Emulator") || model.contains("Android SDK built for"))
    }

    /**
     * Obtiene la IP del host automáticamente usando DNS lookup
     * Para emulador siempre usa 10.0.2.2 (mapeo especial de Android Emulator)
     * Para dispositivo físico, intenta detectar la IP del servidor mediante múltiples estrategias
     */
    private fun getHostIpAddress(): String {
        return if (isRunningOnEmulator()) {
            "10.0.2.2"  // IP especial del emulador Android para el host
        } else {
            // Para dispositivos físicos, intentar varias estrategias para encontrar el servidor
            // Estrategia 1: Obtener la IP de la puerta de enlace (gateway) y probar IPs cercanas
            try {
                val wifiManager = requireContext().applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as android.net.wifi.WifiManager
                val dhcpInfo = wifiManager.dhcpInfo

                // Convertir la IP del gateway a formato legible
                val gatewayInt = dhcpInfo.gateway
                val gateway = String.format(
                    "%d.%d.%d.%d",
                    gatewayInt and 0xff,
                    gatewayInt shr 8 and 0xff,
                    gatewayInt shr 16 and 0xff,
                    gatewayInt shr 24 and 0xff
                )

                Log.d("ChatBotFragment", "Gateway IP detected: $gateway")

                // La IP del PC suele estar en la misma subred que el gateway
                // Intentar con la IP del dispositivo actual para deducir la red
                val myIpInt = dhcpInfo.ipAddress
                val myIp = String.format(
                    "%d.%d.%d.%d",
                    myIpInt and 0xff,
                    myIpInt shr 8 and 0xff,
                    myIpInt shr 16 and 0xff,
                    myIpInt shr 24 and 0xff
                )

                Log.d("ChatBotFragment", "Device IP: $myIp")

                // Extraer la red base (primeros 3 octetos)
                val networkBase = myIp.substringBeforeLast(".")

                // Probar IPs comunes en redes locales donde suele estar el servidor
                // Generalmente los PCs tienen IPs más bajas que los móviles
                val commonServerIps = listOf(
                    "$networkBase.90",  // IP común para PCs
                    "$networkBase.100",
                    "$networkBase.1",   // A veces el router asigna .1 al primer PC
                    "$networkBase.2",
                    gateway             // El gateway mismo en algunos casos
                )

                // Retornar la primera IP que funcione (en el futuro se puede hacer ping)
                // Por ahora retornamos la más probable (.90 o .100)
                return commonServerIps.firstOrNull { testConnection(it, 3001, 1000) }
                    ?: "$networkBase.90"  // Fallback a .90

            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error detecting host IP: ${e.message}")
                // Fallback: usar IP común en redes 192.168.1.x
                return "192.168.1.90"
            }
        }
    }

    /**
     * Prueba si hay conexión al servidor en la IP y puerto especificados
     */
    private fun testConnection(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(ip, port), timeoutMs)
            socket.close()
            Log.d("ChatBotFragment", "✅ Connection successful to $ip:$port")
            true
        } catch (e: Exception) {
            Log.d("ChatBotFragment", "❌ Connection failed to $ip:$port - ${e.message}")
            false
        }
    }

    private fun getMicroserviceBaseUrl(): String {
        // Prefer a locally-resolved MCP host when available (fast path for physical devices).
        // `peekMcpBaseUrl()` is non-suspending and returns the last-known good host (if any).
        return try {
            val fast = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeoutOrNull(200) {
                    ServerEndpointResolver.fastResolveMcpBaseUrl()
                }
            }
            if (!fast.isNullOrBlank()) {
                if (fast.endsWith("/")) fast else "$fast/"
            } else {
                // Fallback to Railway Cloud per build variant
                val url = com.example.tareamov.BuildConfig.BACKEND_URL
                if (url.endsWith("/")) url else "$url/"
            }
        } catch (e: Exception) {
            val url = com.example.tareamov.BuildConfig.BACKEND_URL
            if (url.endsWith("/")) url else "$url/"
        }
    }

    private fun getOllamaUrl(): String {
        // Ollama is now replaced by DeepSeek in the cloud backend
        return com.example.tareamov.BuildConfig.BACKEND_URL
    }
    // Build a MicroservicioApi instance on demand using the currently resolved base URL.
    private fun buildMicroservicioApi(): MicroservicioApi {
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS) // fail fast on connect
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val builder = originalRequest.newBuilder()
                    .header("X-API-Key", "tareamov-mcp-api-key-2025-secure")
                    .header("Content-Type", "application/json")
                    .header("Connection", "close")
                // Per-request Supabase routing for production build variant
                val supaUrl = com.example.tareamov.BuildConfig.SUPABASE_URL
                val supaKey = com.example.tareamov.BuildConfig.SUPABASE_ANON_KEY
                if (supaUrl.isNotBlank()) builder.header("X-Supabase-Url", supaUrl)
                if (supaKey.isNotBlank()) builder.header("X-Supabase-Key", supaKey)
                chain.proceed(builder.build())
            }
            .build()

        val base = getMicroserviceBaseUrl()
        val retrofit = Retrofit.Builder()
            .baseUrl(if (base.endsWith("/")) base else "$base/")
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
        return retrofit.create(MicroservicioApi::class.java)
    }

    // Expose a property-like accessor so existing call sites can use `microservicioApi`.
    private val microservicioApi: MicroservicioApi
        get() = buildMicroservicioApi()

    private val sessionId = UUID.randomUUID().toString()
    private var currentFileContext: FileContext? = null
    private var fallbackArgumentFileName: String? = null

    private fun isInvalidFileName(value: String?): Boolean {
        return value.isNullOrBlank() ||
            value.equals("null", ignoreCase = true) ||
            value.equals("undefined", ignoreCase = true)
    }

    private fun resolveDisplayFileName(primary: String?, fallback: String? = fallbackArgumentFileName): String {
        val safePrimary = primary?.trim()?.takeIf { !isInvalidFileName(it) }
        if (safePrimary != null) return safePrimary

        val safeFallback = fallback?.trim()?.takeIf { !isInvalidFileName(it) }
        if (safeFallback != null) return safeFallback

        return "archivo sin nombre"
    }

    private fun sanitizeFileContext(fileContext: FileContext): FileContext {
        val safeFileName = resolveDisplayFileName((fileContext.fileName as String?)?.trim())
        val safeFileType = (fileContext.fileType as String?)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "unknown"

        return FileContext(
            id = fileContext.id,
            submissionId = fileContext.submissionId,
            fileName = safeFileName,
            fileType = safeFileType,
            fileContent = fileContext.fileContent,
            extractedText = fileContext.extractedText,
            metadata = fileContext.metadata,
            timestamp = fileContext.timestamp,
            jsonContent = fileContext.jsonContent,
            contentSummary = fileContext.contentSummary
        )
    }

    // Variables para monitorear el cursor y mostrar la lista de tareas
    private var lastCursorPosition = -1
    private val cursorCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cursorCheckRunnable = object : Runnable {
        override fun run() {
            if (::messageEditText.isInitialized) {
                val currentCursorPosition = messageEditText.selectionStart
                if (currentCursorPosition != lastCursorPosition) {
                    lastCursorPosition = currentCursorPosition
                    onCursorPositionChanged()
                }
                cursorCheckHandler.postDelayed(this, 50) // Verificar cada 50ms para respuesta instantánea
            }
        }
    }

    // Variables para almacenar información de la tarea, tema y curso
    private var taskName: String = ""
    private var taskDescription: String = ""
    private var topicName: String = ""
    private var courseTitle: String = ""
    private var deliveryDate: String = ""
    private var courseId: Long = -1L // ID del curso actual para obtener tareas

    // 🔥 Variables de respaldo del TaskItem seleccionado (no dependen de FileContext)
    private var selectedTaskSubmissionId: Long? = null
    private var selectedTaskStudentId: Long? = null
    private var selectedTaskFileUri: String? = null
    private var selectedTaskRemoteTaskId: Long? = null

    // UI Components for task overlay
    private lateinit var taskListOverlay: androidx.cardview.widget.CardView
    private lateinit var taskListOverlayBackground: View
    private lateinit var taskListRecyclerView: RecyclerView
    private lateinit var courseNameTextView: TextView
    private lateinit var closeTaskListButton: ImageButton
    private lateinit var backToCoursesButton: ImageButton
    private lateinit var overlayTitleTextView: TextView
    private lateinit var overlaySearchEditText: EditText
    private lateinit var taskOverlayAdapter: com.example.tareamov.adapter.TaskOverlayAdapter
    private val currentCourseTasks: MutableList<TaskItem> = mutableListOf()
    private val allCoursesList: MutableList<TaskItem> = mutableListOf() // Lista completa de cursos para filtrar
    private var isSelectingCourse = true
    private var selectedCourseId: Long = -1L
    private var selectedCourseTitle: String = ""

    // UI Components for graded tasks overlay
    private lateinit var gradedTasksOverlay: androidx.cardview.widget.CardView
    private lateinit var gradedTasksOverlayBackground: View
    private lateinit var gradedTasksRecyclerView: RecyclerView
    private lateinit var gradedCourseNameTextView: TextView
    private lateinit var closeGradedTasksButton: ImageButton
    private lateinit var gradedTaskOverlayAdapter: com.example.tareamov.adapter.GradedTaskOverlayAdapter
    private val gradedTasksList: MutableList<GradedTaskItem> = mutableListOf()
    // Track most-recently graded submission so the overlay can show it directly
    private var lastGradedSubmissionId: Long? = null

    private var isUpdatingTextSpans: Boolean = false

    private suspend fun analizarEntregaYFeedback(userMessage: String, fileContext: FileContext?): String {
        val taskDescription = fileContext?.contentSummary ?: ""
        val fileContent = fileContext?.fileContent ?: ""
        val preguntaLower = userMessage.lowercase()
        val esPreguntaNota = preguntaLower.contains("nota") || preguntaLower.contains("calificación") || preguntaLower.contains("feedback") || preguntaLower.contains("tarea")
        val ollamaUrl = getOllamaUrl()
        return try {
            // Obtener el ID del usuario autenticado para notificaciones
            val currentUserId = sessionManager.getUserId()

            // Utilizar el microservicio directamente, sin lógica local
            val request = com.example.tareamov.network.MicroservicioPromptRequest(
                prompt = userMessage,
                ollamaUrl = ollamaUrl,
                taskDescription = taskDescription.ifEmpty { null },
                fileContent = fileContent.ifEmpty { null },
                userId = currentUserId
            )
            val responseWrapper = microservicioApi.procesarPrompt(request)
            responseWrapper.data?.respuesta_texto ?: "No se pudo obtener respuesta: ${responseWrapper.error ?: "Error desconocido"}"

        } catch (e: Exception) {
            "Error al procesar la entrega: ${e.message}"
        }
    }



    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_chatbot, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        ttsService = TTSService.getInstance(requireContext())
        sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        BackendApiService.initialize(requireContext())

        // Pre-warm MCP endpoint selection so phone uses LAN host quickly or falls back to cloud
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val mcpClient = com.example.tareamov.service.MCPHttpClient(requireContext())
                val base = try { kotlinx.coroutines.withTimeoutOrNull(120) { ServerEndpointResolver.fastResolveMcpBaseUrl() } } catch (e: Exception) { null }
                if (!base.isNullOrBlank() && base != ServerEndpointResolver.RAILWAY_MCP_URL) {
                    mcpClient.setForcedBaseUrl(base)
                    android.util.Log.i("ChatBotFrag", "Using local MCP base: $base")
                } else {
                    // Force immediate cloud fallback to avoid long waits on phone
                    mcpClient.setForcedBaseUrl(ServerEndpointResolver.RAILWAY_MCP_URL)
                    android.util.Log.i("ChatBotFrag", "Falling back to cloud MCP: ${ServerEndpointResolver.RAILWAY_MCP_URL}")
                }
            } catch (e: Exception) {
                android.util.Log.w("ChatBotFrag", "Pre-warm MCP failed: ${e.message}")
            }
        }

        initializeViews(view)
        setupRecyclerView()

        // Try to fetch the current user's avatar and set it on the chat adapter
        lifecycleScope.launch {
            try {
                val username = sessionManager.getUsername()
                if (!username.isNullOrBlank()) {
                    val userResult = BackendApiService.getUserByUsername(username)
                    val avatar = userResult.getOrNull()?.avatar
                    if (!avatar.isNullOrBlank()) {
                        sessionManager.saveUserAvatar(avatar) // Persist so getUserAvatar() works next time
                        chatAdapter.setUserAvatarUrl(avatar)
                    }
                }
            } catch (_: Exception) {
            }
        }
        setupClickListeners()
        loadMessages()
        loadFileContextFromArguments()



        // Inicializar monitoreo del cursor para mostrar lista de tareas
        cursorCheckHandler.post(cursorCheckRunnable)

        // Cargar tareas calificadas persistidas al inicializar
        loadGradedTasksOnStart()

        // Setup keyboard handling para que no tape el contenido
        setupKeyboardHandling(view)
    }

    /**
     * Configura el manejo del teclado estilo ChatGPT:
     * - El topbar permanece fijo arriba
     * - El input se eleva con el teclado
     * - El contenido del chat se ajusta entre ambos
     */
    private fun setupKeyboardHandling(view: View) {
        val rootLayout = view.findViewById<androidx.constraintlayout.widget.ConstraintLayout>(R.id.chatBotRootLayout)
        val inputAreaWrapper = view.findViewById<LinearLayout>(R.id.inputAreaWrapper)

        // Usar WindowInsets para detectar el teclado y ajustar solo el área de input
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())

            // Mover el inputAreaWrapper hacia arriba cuando aparece el teclado
            val bottomPadding = if (imeInsets.bottom > 0) {
                imeInsets.bottom
            } else {
                navigationBars.bottom
            }

            // Aplicar el padding solo al área de input (no a toda la vista)
            inputAreaWrapper.setPadding(
                inputAreaWrapper.paddingLeft,
                inputAreaWrapper.paddingTop,
                inputAreaWrapper.paddingRight,
                bottomPadding
            )

            // Scroll al último mensaje cuando aparece el teclado
            if (imeInsets.bottom > 0) {
                messagesRecyclerView.post {
                    if (::chatAdapter.isInitialized && chatAdapter.itemCount > 0) {
                        messagesRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                    }
                }
            }

            insets
        }

        // Solicitar insets
        ViewCompat.requestApplyInsets(rootLayout)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detener el monitoreo del cursor para evitar memory leaks
        cursorCheckHandler.removeCallbacks(cursorCheckRunnable)
        // Eliminar el listener de sesión para evitar fugas
        sessionChangeListener?.let { com.example.tareamov.util.SessionManager.removeUserChangeListener(it) }
        // Detener reproducción TTS si está activa
        ttsService.stopPlayback()
    }

    override fun onStop() {
        super.onStop()

        // If there's an ongoing LLM request, schedule it as background task
        if (isProcessingLLM && pendingPrompt != null) {
            Log.d("ChatBotFragment", "🔄 App going to background - scheduling LLM task to continue")

            val userId = sessionManager.getUserId() ?: -1L
            val username = sessionManager.getUsername() ?: "unknown"

            if (userId > 0) {
                BackgroundTaskManager.scheduleChatMessage(
                    context = requireContext(),
                    prompt = pendingPrompt!!,
                    userId = userId,
                    username = username,
                    sessionId = sessionId,
                    taskDescription = pendingTaskDescription ?: "",
                    fileContent = pendingFileContent ?: "",
                    jsonContent = pendingJsonContent ?: "",
                    metadata = pendingMetadata ?: "",
                    submissionId = pendingSubmissionId,
                    taskId = pendingTaskId
                )

                Toast.makeText(context, "📋 La tarea continuará en segundo plano", Toast.LENGTH_SHORT).show()
            }

            // Clear pending data
            clearPendingTaskData()
        }
    }

    private fun clearPendingTaskData() {
        isProcessingLLM = false
        pendingPrompt = null
        pendingTaskDescription = null
        pendingFileContent = null
        pendingJsonContent = null
        pendingMetadata = null
        pendingSubmissionId = null
        pendingTaskId = null
    }

    private fun initializeViews(view: View) {
        // Handle TopBar Insets for status bar only - with minimal padding
        val topBar = view.findViewById<LinearLayout?>(R.id.topBar)

        // topBar may be null in some layout inflations; guard against it to avoid crashes
        topBar?.let { tb ->
            ViewCompat.setOnApplyWindowInsetsListener(tb) { v, insets ->
                val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                // Solo agregar el padding de la barra de estado, sin padding adicional
                v.setPadding(v.paddingLeft, statusBars.top, v.paddingRight, v.paddingBottom)
                insets
            }

            // Forzar que el topBar siempre esté arriba incluso durante animaciones
            tb.post {
                tb.bringToFront()
            }
        }

        messagesRecyclerView = view.findViewById(R.id.messagesRecyclerView)
        messageEditText = view.findViewById(R.id.messageEditText)
        sendButton = view.findViewById(R.id.sendButton)
        gradedTasksButton = view.findViewById(R.id.gradedTasksButton)
        backButton = view.findViewById(R.id.backButton)
        clearChatButton = view.findViewById(R.id.clearChatButton)
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        activeContextValue = view.findViewById(R.id.activeContextValue)
        activeContextIcon = view.findViewById(R.id.activeContextIcon)
        fabScrollToBottom = view.findViewById(R.id.fabScrollToBottom)

        // Voice UI
        voiceInputLayout = view.findViewById(R.id.voiceInputLayout)
        messageInputContainer = view.findViewById(R.id.messageInputContainer)
        voiceVisualizer = view.findViewById(R.id.voiceVisualizer)
        stopRecordingButton = view.findViewById(R.id.stopRecordingButton)
        cancelVoiceButton = view.findViewById(R.id.cancelVoiceButton)
        voiceStatusText = view.findViewById(R.id.voiceStatusText)
        micButton = view.findViewById(R.id.micButton)

        // Initialize task overlay components
        taskListOverlay = view.findViewById(R.id.taskListOverlay)
        taskListOverlayBackground = view.findViewById(R.id.taskListOverlayBackground)
        taskListRecyclerView = view.findViewById(R.id.taskListRecyclerView)
        courseNameTextView = view.findViewById(R.id.courseNameTextView)
        closeTaskListButton = view.findViewById(R.id.closeTaskListButton)
        backToCoursesButton = view.findViewById(R.id.backToCoursesButton)
        overlayTitleTextView = view.findViewById(R.id.overlayTitleTextView)
        overlaySearchEditText = view.findViewById(R.id.overlaySearchEditText)

        // Initialize graded tasks overlay components
        gradedTasksOverlay = view.findViewById(R.id.gradedTasksOverlay)
        gradedTasksOverlayBackground = view.findViewById(R.id.gradedTasksOverlayBackground)
        gradedTasksRecyclerView = view.findViewById(R.id.gradedTasksRecyclerView)
        gradedCourseNameTextView = view.findViewById(R.id.gradedCourseNameTextView)
        closeGradedTasksButton = view.findViewById(R.id.closeGradedTasksButton)

        // Setup task overlay adapter
        taskOverlayAdapter = TaskOverlayAdapter(emptyList()) { task ->
            onTaskSelected(task)
        }
        taskListRecyclerView.apply {
            adapter = taskOverlayAdapter
            layoutManager = LinearLayoutManager(context)
        }

        // Setup graded tasks overlay adapter
        gradedTaskOverlayAdapter = GradedTaskOverlayAdapter(emptyList()) { gradedTask ->
            onGradedTaskSelected(gradedTask)
        }
        gradedTasksRecyclerView.apply {
            adapter = gradedTaskOverlayAdapter
            layoutManager = LinearLayoutManager(context)
        }
    }

    private fun loadFileContextFromArguments() {
        arguments?.let { args ->
            if (args.getBoolean("clearContext", false)) {
                Log.d("ChatBotFragment", "🧹 Clearing context as requested")
                taskName = ""
                taskDescription = ""
                courseTitle = ""
                
                selectedTaskSubmissionId = null
                selectedTaskStudentId = null
                selectedTaskFileUri = null
                selectedTaskRemoteTaskId = null
                
                if (::activeContextValue.isInitialized) {
                    activeContextValue.text = ""
                }
                return@let
            }

            val submissionId = args.getLong("submissionId", -1L)
            val errorMessage = args.getString("errorMessage")
            val fileName = args.getString("fileName")
            fallbackArgumentFileName = fileName?.trim()?.takeIf { !isInvalidFileName(it) }

            // LOGGING DETALLADO PARA DEBUGGING
            Log.d("ChatBotFragment", "==============================================")
            Log.d("ChatBotFragment", "📋 LOADING FILE CONTEXT FROM ARGUMENTS:")
            Log.d("ChatBotFragment", "==============================================")
            Log.d("ChatBotFragment", "submissionId recibido: $submissionId")
            Log.d("ChatBotFragment", "errorMessage: $errorMessage")
            Log.d("ChatBotFragment", "fileName: $fileName")
            Log.d("ChatBotFragment", "==============================================")

            // 🔥 CRÍTICO: Recuperar taskId, studentId, fileUri para backup vars
            val argTaskId = if (args.containsKey("taskId")) args.getLong("taskId", -1L) else -1L
            val argStudentId = if (args.containsKey("studentId")) args.getLong("studentId", -1L) else -1L
            val argFileUri = args.getString("fileUri")

            if (argTaskId > 0) selectedTaskRemoteTaskId = argTaskId
            if (argStudentId > 0) selectedTaskStudentId = argStudentId
            if (!argFileUri.isNullOrEmpty()) selectedTaskFileUri = argFileUri
            if (submissionId > 0) selectedTaskSubmissionId = submissionId

            Log.d("ChatBotFragment", "🔥 Backup vars from args: taskId=$argTaskId, studentId=$argStudentId, fileUri=${argFileUri?.take(60)}, submissionId=$submissionId")

            // Retrieve task info from arguments to update UI immediately
            val argTaskName = args.getString("taskName")
            val argTaskDescription = args.getString("taskDescription")

            if (!argTaskName.isNullOrEmpty()) {
                taskName = argTaskName
                if (::activeContextValue.isInitialized) {
                    activeContextValue.text = taskName
                    activeContextValue.setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                    if (::activeContextIcon.isInitialized) {
                        activeContextIcon.setColorFilter(android.graphics.Color.parseColor("#DDDDDD"))
                    }
                }
            }

            if (!argTaskDescription.isNullOrEmpty()) {
                taskDescription = argTaskDescription
            }

            if (errorMessage != null) {
                // Mostrar mensaje de error del archivo
                lifecycleScope.launch {
                    val safeFileName = resolveDisplayFileName(fileName)
                    val errorChatMessage = ChatMessage(
                        message = "⚠️ **Error con el archivo**\n\n" +
                                "📁 Archivo: $safeFileName\n" +
                                "❌ Error: $errorMessage\n\n" +
                                "💬 Puedes seguir usando el chat, pero sin el contexto completo del archivo.\n" +
                                "Para obtener mejor ayuda, intenta subir el archivo localmente.",
                        isFromUser = false,
                        sessionId = sessionId
                        , senderUsername = "DeepSeek",
                        senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                    )

                    withContext(Dispatchers.IO) {
                        val savedId = database.chatMessageDao().insertMessage(errorChatMessage)
                        syncChatMessageToBackend(errorChatMessage, savedId)
                    }
                }

                // A pesar del error, intentamos cargar el contexto si existe
                if (submissionId != -1L) {
                    loadFileContextById(submissionId, true)
                }

                return
            }

            if (submissionId != -1L) {
                loadFileContextById(submissionId, false)
            }
        }
    }

    /**
     * Carga el contexto del archivo por ID y muestra mensajes apropiados
     */
    private fun loadFileContextById(submissionId: Long, hasError: Boolean) {
        lifecycleScope.launch {
            // Fetch FileContext from BackendApiService
            currentFileContext = withContext(Dispatchers.IO) {
                try {
                    val fcs = BackendApiService.getFileContextsBySubmission(submissionId).getOrNull() ?: emptyList()
                    fcs.firstOrNull()?.let(::sanitizeFileContext)
                } catch (e: Exception) {
                    Log.w("ChatBotFragment", "Exception fetching FileContext for submissionId=$submissionId: ${e.message}")
                    null
                }
            }

            // Cargar información de la tarea, tema y curso
            updateCourseInfo(submissionId)

            // 🔥 CRÍTICO: Poblar backup vars desde la submission si aún no están establecidas
            if (selectedTaskSubmissionId == null && submissionId > 0) selectedTaskSubmissionId = submissionId
            if (selectedTaskStudentId == null || selectedTaskFileUri == null || selectedTaskRemoteTaskId == null) {
                try {
                    val sub = withContext(Dispatchers.IO) {
                        BackendApiService.getSubmissionById(submissionId).getOrNull()
                    }
                    if (sub != null) {
                        if (selectedTaskStudentId == null) selectedTaskStudentId = sub.studentId
                        if (selectedTaskFileUri == null && sub.fileUri.isNotEmpty()) selectedTaskFileUri = sub.fileUri
                        if (selectedTaskRemoteTaskId == null) selectedTaskRemoteTaskId = sub.taskId
                        Log.d("ChatBotFragment", "🔥 Backup vars from submission lookup: taskId=${sub.taskId}, studentId=${sub.studentId}, fileUri=${sub.fileUri.take(60)}")
                    }
                } catch (e: Exception) {
                    Log.w("ChatBotFragment", "⚠️ Error populating backup vars from submission: ${e.message}")
                }
            }

            // Logging mínimo
            Log.d("ChatBotFragment", "🔍 FileContext cargado - submissionId: $submissionId, presente: ${currentFileContext != null}")
            if (currentFileContext != null) {
                Log.d("ChatBotFragment", "   - fileName: '${currentFileContext!!.fileName}', fileType: '${currentFileContext!!.fileType}'")
            }

            if (currentFileContext != null) {
                val displayFileName = resolveDisplayFileName(currentFileContext!!.fileName)
                // Verificar si es un error específico de Google Drive
                val isGoogleDriveError = currentFileContext!!.fileType == "google_drive_error"

                // Mostrar mensaje inicial con contexto del archivo
                val contextMessage = if (isGoogleDriveError) {
                    ChatMessage(
                        message = "📱 **Archivo de Google Drive detectado**\n\n" +
                                "📄 Nombre: $displayFileName\n" +
                                "⚠️ **No se puede acceder directamente a este archivo**\n\n" +
                                "Para poder analizar este archivo, necesitas:\n" +
                                "1. Abrir Google Drive\n" +
                                "2. Descargar el archivo a tu dispositivo\n" +
                                "3. Volver a subir el archivo desde tu almacenamiento local\n\n" +
                                "Mientras tanto, puedo ayudarte con preguntas generales.",
                        isFromUser = false,
                        sessionId = sessionId,
                        senderUsername = "DeepSeek",
                        senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                    )
                } else if (hasError) {
                    ChatMessage(
                        message = "📄 **Archivo parcialmente accesible**\n\n" +
                                "📁 Nombre: $displayFileName\n" +
                                "🔧 Tipo: ${currentFileContext!!.fileType}\n" +
                                "⚠️ El archivo tiene problemas de acceso, pero intentaré ayudarte con la información disponible.\n\n" +
                                "Puedes hacerme preguntas y haré lo mejor posible con los datos limitados.",
                        isFromUser = false,
                        sessionId = sessionId,
                        senderUsername = "DeepSeek",
                        senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                    )
                } else {
                    // Mostrar solo el nombre del archivo sin el contenido
                    ChatMessage(
                        message = "📁 **Archivo cargado:** $displayFileName\n\n" +
                                "✅ Puedes hacerme preguntas sobre este archivo.",
                        isFromUser = false,
                        sessionId = sessionId,
                        senderUsername = "DeepSeek",
                        senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                    )
                }

                withContext(Dispatchers.IO) {
                    val savedIdCtx = database.chatMessageDao().insertMessage(contextMessage)
                    syncChatMessageToBackend(contextMessage, savedIdCtx)
                }
            }
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatMessageAdapter(
            onAddCalificationClick = { message ->
                // When the user selects the "check" / add calification action on a bot message,
                // show the graded tasks button so they can view/select graded tasks.
                try {
                    gradedTasksButton.visibility = View.VISIBLE
                } catch (e: Exception) { /* ignore if view not ready */ }
                handleAddCalification(message)
            },
            onRejectCalificationClick = { message ->
                // Hide the graded tasks button if user rejects grading
                try {
                    gradedTasksButton.visibility = View.GONE
                } catch (e: Exception) { /* ignore if view not ready */ }
                handleRejectCalification(message)
            },
            onTTSClick = { message ->
                handleTTSClick(message)
            },
            onEditClick = { message ->
                showEditMessageDialog(message)
            },
            taskInfo = null // Se actualizará dinámicamente cuando se cargue la información
        )
        // Set bot avatar to DeepSeek image and current user avatar from session
        val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val userAvatar = sess.getUserAvatar()
        chatAdapter.setUserAvatarUrl(userAvatar)
        chatAdapter.setBotAvatarUrl("https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png")

        // Update adapter avatar when session changes (store listener to remove later)
        sessionChangeListener = object : com.example.tareamov.util.SessionManager.UserChangeListener {
            override fun onUserChanged(previousUser: String?, newUser: String?) {
                val updated = com.example.tareamov.util.SessionManager.getInstance(requireContext()).getUserAvatar()
                chatAdapter.setUserAvatarUrl(updated)

                // Also try to fetch fresh avatar from Supabase
                lifecycleScope.launch {
                    try {
                        if (!newUser.isNullOrBlank()) {
                            val userResult = BackendApiService.getUserByUsername(newUser)
                            val avatar = userResult.getOrNull()?.avatar
                            if (!avatar.isNullOrBlank()) {
                                com.example.tareamov.util.SessionManager.getInstance(requireContext()).saveUserAvatar(avatar)
                                chatAdapter.setUserAvatarUrl(avatar)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            override fun onUserLoggedOut(previousUser: String?) {
                chatAdapter.setUserAvatarUrl(null)
            }
        }
        sessionChangeListener?.let { com.example.tareamov.util.SessionManager.addUserChangeListener(it) }
        messagesRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true // Start from bottom
            }
            // Optimización para mantener la posición al cambiar el tamaño (teclado)
            addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
                if (bottom < oldBottom) {
                    postDelayed({
                        if (adapter != null && adapter!!.itemCount > 0) {
                            smoothScrollToPosition(adapter!!.itemCount - 1)
                        }
                    }, 100)
                }
            }

            // Listener para mostrar/ocultar FAB según posición del scroll
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)

                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                    if (layoutManager != null && chatAdapter.itemCount > 0) {
                        val lastVisiblePosition = layoutManager.findLastCompletelyVisibleItemPosition()
                        val lastItemPosition = chatAdapter.itemCount - 1

                        // Mostrar FAB si no estamos en el último mensaje
                        if (lastVisiblePosition < lastItemPosition) {
                            showScrollToBottomFab()
                        } else {
                            hideScrollToBottomFab()
                        }
                    }
                }
            })
        }

        // Fix for keyboard covering content using WindowInsets
        ViewCompat.setOnApplyWindowInsetsListener(messagesRecyclerView) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            // Aplicar padding inferior si es necesario, aunque adjustResize suele manejarlo
            // Aquí nos aseguramos de que el scroll sea suave
            if (imeInsets.bottom > 0) {
                // No es necesario aplicar padding si usamos adjustResize en el manifiesto,
                // pero forzamos el scroll para asegurar visibilidad
            }
            insets
        }
    }

    private fun setupClickListeners() {
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        sendButton.setOnClickListener {
            sendMessage()
        }

        micButton.setOnClickListener {
            checkAudioPermissionAndStart()
        }

        stopRecordingButton.setOnClickListener {
            stopListening()
        }

        cancelVoiceButton.setOnClickListener {
            stopListening()
        }

        gradedTasksButton.setOnClickListener {
            showGradedTasksOverlay()
        }

        clearChatButton.setOnClickListener {
            clearChat()
        }

        fabScrollToBottom.setOnClickListener {
            scrollToBottomSmooth()
        }

        closeTaskListButton.setOnClickListener {
            hideTaskListOverlay()
        }

        closeGradedTasksButton.setOnClickListener {
            hideGradedTasksOverlay()
        }

        taskListOverlayBackground.setOnClickListener {
            hideTaskListOverlay()
        }

        gradedTasksOverlayBackground.setOnClickListener {
            hideGradedTasksOverlay()
        }

        // Scroll to bottom when keyboard opens
        messagesRecyclerView.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom < oldBottom) {
                messagesRecyclerView.postDelayed({
                    if (chatAdapter.itemCount > 0) {
                        messagesRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)
                    }
                }, 100)
            }
        }

        messageEditText.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }

        // Detect "#" character to show task list and highlight references
        messageEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // El monitoreo del cursor se hace en el Handler, aquí solo detectamos cambios inmediatos
            }
            override fun afterTextChanged(s: android.text.Editable?) {
                // Aplicar resaltado azul a referencias de tarea con #
                applyHashtagHighlighting()
            }
        })
    }

    private fun loadMessages() {
        lifecycleScope.launch {
            database.chatMessageDao().getAllMessages().collect { messages ->
                chatAdapter.submitList(messages) {
                    // Scroll to bottom when new messages are added
                    if (messages.isNotEmpty()) {
                        messagesRecyclerView.smoothScrollToPosition(messages.size - 1)

                        // Ocultar FAB cuando hay scroll automático al final
                        messagesRecyclerView.postDelayed({
                            hideScrollToBottomFab()
                        }, 300)
                    }
                }
            }
        }
    }

    /**
     * Envía el mensaje del usuario y el contexto completo del archivo al microservicio.
     * El backend orquesta dos modelos:
     * 1. Analiza si la tarea cumple con la descripción (veredicto).
     * 2. llama3:latest: califica (1-10) y da retroalimentación al usuario en base al veredicto.
     * El cliente solo envía el mensaje del usuario y el contenido completo del archivo (descripcionTarea).
     */
    private fun showEditMessageDialog(message: ChatMessage) {
        val context = context ?: return

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_message, null)
        val editMessageInput = dialogView.findViewById<EditText>(R.id.editMessageInput)
        val saveButton = dialogView.findViewById<View>(R.id.saveButton)
        val cancelButton = dialogView.findViewById<View>(R.id.cancelButton)

        editMessageInput.setText(message.message)
        editMessageInput.setSelection(message.message.length)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        saveButton.setOnClickListener {
            val newText = editMessageInput.text.toString().trim()
            if (newText.isNotEmpty() && newText != message.message) {
                handleEditAndResend(message, newText)
            }
            dialog.dismiss()
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun handleEditAndResend(message: ChatMessage, newText: String) {
        lifecycleScope.launch {
            // 1. Update message in DB and remove subsequent messages
            withContext(Dispatchers.IO) {
                // Update the message text
                val updatedMessage = message.copy(message = newText)
                database.chatMessageDao().updateMessage(updatedMessage)

                // Delete all messages AFTER this one
                val allMessages = database.chatMessageDao().getAllMessages().first()
                val messageIndex = allMessages.indexOfFirst { it.id == message.id }

                if (messageIndex != -1 && messageIndex < allMessages.size - 1) {
                    val messagesToDelete = allMessages.subList(messageIndex + 1, allMessages.size)
                    messagesToDelete.forEach {
                        database.chatMessageDao().deleteMessage(it)
                    }
                }
            }

            // 2. Refresh UI
            loadMessages()

            // 3. Resend to LLM (Reuse sendMessage logic but without creating new user message)
            // We'll call a modified version of the logic inside sendMessage
            processMessageWithLLM(newText)
        }
    }

    private fun processMessageWithLLM(messageText: String) {
        lifecycleScope.launch {
            loadingProgressBar.visibility = View.VISIBLE

            // Prepare context variables (similar to sendMessage)
            val effectiveTaskDescription = taskDescription
            val effectiveFileContent = currentFileContext?.fileContent ?: ""
            val effectiveJsonContent = currentFileContext?.jsonContent ?: ""
            val effectiveMetadata = currentFileContext?.metadata ?: ""
            val currentSubmissionId = currentFileContext?.submissionId
            val currentTaskIdForRequest = currentSubmissionId

            // Track pending task data for background processing if app goes to background
            isProcessingLLM = true
            pendingPrompt = messageText
            pendingTaskDescription = effectiveTaskDescription
            pendingFileContent = effectiveFileContent
            pendingJsonContent = effectiveJsonContent
            pendingMetadata = effectiveMetadata
            pendingSubmissionId = currentSubmissionId
            pendingTaskId = currentTaskIdForRequest

            // Data class local para capturar tanto el texto como la nota del backend
            data class LLMResponse(val text: String, val nota: Float?, val esCalificacion: Boolean = false)

            try {
                val llmResponse = withContext(Dispatchers.IO) {
                    try {
                        val currentStudentId = sessionManager.getUserId()

                        val body = com.example.tareamov.network.MicroservicioPromptRequest(
                            prompt = messageText,
                            ollamaUrl = getOllamaUrl(),
                            taskDescription = effectiveTaskDescription.ifEmpty { null },
                            fileContent = effectiveFileContent.ifEmpty { null },
                            jsonContent = if (effectiveJsonContent.isNotEmpty()) effectiveJsonContent else null,
                            metadata = if (effectiveMetadata.isNotEmpty()) effectiveMetadata else null,
                            userId = sessionManager.getUserId(),
                            submissionId = currentSubmissionId,
                            taskId = currentTaskIdForRequest,
                            studentId = currentStudentId,
                            fileUri = null
                        )

                        val currentUserId = sessionManager.getUserId()
                        val bodyWithUserId = body.copy(userId = currentUserId)
                        val resWrapper = microservicioApi.procesarPrompt(bodyWithUserId)
                        
                        val res = resWrapper.data

                        if (!resWrapper.success || res == null || res.respuesta_texto.isNullOrBlank()) {
                            if (effectiveFileContent.isBlank() || effectiveFileContent.length < 50) {
                                LLMResponse("El archivo enviado está vacío o no se pudo leer su contenido.", 0f, true)
                            } else {
                                LLMResponse("Hubo un problema al procesar tu solicitud: ${resWrapper.error}", null, false)
                            }
                        } else {
                            LLMResponse(res.respuesta_texto, res.nota, res.esCalificacion == true)
                        }
                    } catch (e: Exception) {
                        LLMResponse("Error al procesar la solicitud: ${e.message}", null, false)
                    }
                }

                // Clear pending data - task completed successfully
                clearPendingTaskData()

                val response = llmResponse.text.replace("#", "").replace("**", "")

                // 🎯 SEMANTIC: Solo el LLM decide si mostrar botones de calificación (via esCalificacion del backend)
                val hasCalification = llmResponse.esCalificacion
                // Solo extraer valor de calificación si el LLM señalizó que es calificación
                val calificationValue = if (hasCalification && llmResponse.nota != null) {
                    val notaValue = llmResponse.nota
                    Log.d("ChatBotFragment", "📊 Usando nota del backend: $notaValue (esCalificacion=true)")
                    if (notaValue % 1 == 0f) "${notaValue.toInt()}/10" else String.format("%.1f/10", notaValue)
                } else if (hasCalification) {
                    // Fallback: extraer del texto solo si el LLM marcó como calificación
                    extractCalificationValue(response)
                } else {
                    // No es calificación - no extraer nota del texto para evitar falsos positivos
                    null
                }

                val botMessage = ChatMessage(
                    message = response,
                    isFromUser = false,
                    sessionId = sessionId,
                    hasCalification = hasCalification,
                    calificationValue = calificationValue,
                    calificationAdded = false,
                    senderUsername = "DeepSeek",
                    senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                )
                withContext(Dispatchers.IO) {
                    val savedBotId = database.chatMessageDao().insertMessage(botMessage)
                    syncChatMessageToBackend(botMessage, savedBotId)
                }

                loadMessages()

            } catch (e: Exception) {
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                loadingProgressBar.visibility = View.GONE
            }
        }
    }

    private fun sendMessage() {
        val messageText = messageEditText.text.toString().trim()
        if (messageText.isEmpty()) return

        lifecycleScope.launch {
            messageEditText.text.clear()
            val userMessage = ChatMessage(
                message = messageText,
                isFromUser = true,
                sessionId = sessionId,
                senderUsername = sessionManager.getUsername(),
                senderAvatar = sessionManager.getUserAvatar() ?: chatAdapter.getCurrentUserAvatarUrl()
            )
            withContext(Dispatchers.IO) {
                val savedUserId = database.chatMessageDao().insertMessage(userMessage)
                syncChatMessageToBackend(userMessage, savedUserId)
            }
            loadingProgressBar.visibility = View.VISIBLE

            // 🚀 CRITICAL FIX: Clear task context if user doesn't reference the task
            // Detect if message is about the specific task/submission or a general question
            val hasTaskReference = messageText.contains("#") ||
                    messageText.contains("tarea", ignoreCase = true) ||
                    messageText.contains("archivo", ignoreCase = true) ||
                    messageText.contains("entrega", ignoreCase = true) ||
                    messageText.contains("califica", ignoreCase = true) ||
                    messageText.contains("nota", ignoreCase = true)

            // 🔥 CRÍTICO: Si el mensaje contiene # y taskName está establecido, esperar a que el contexto termine de cargar
            val needsContextLoad = messageText.contains("#") && taskName.isNotEmpty()

            // Contexto por defecto: la tarea/archivo actualmente cargado en el chat
            // PERO SOLO si el usuario hace referencia explícita a la tarea
            var effectiveTaskDescription = if (hasTaskReference) currentFileContext?.contentSummary ?: "" else ""
            var effectiveFileContent = if (hasTaskReference) currentFileContext?.fileContent ?: "" else ""
            var effectiveJsonContent = if (hasTaskReference) currentFileContext?.jsonContent ?: "" else ""
            var effectiveMetadata = if (hasTaskReference) currentFileContext?.metadata ?: "" else ""

            if (!hasTaskReference && currentFileContext != null) {
                Log.d("ChatBotFragment", "🚫 Clearing task context - message doesn't reference task")
            }

            if (needsContextLoad) {
                Log.d("ChatBotFragment", "⏳ Detectado # con taskName='$taskName', esperando a que currentFileContext se cargue...")

                // Esperar hasta que currentFileContext esté cargado o timeout de 5 segundos
                var waitCount = 0
                while (currentFileContext == null && waitCount < 50) { // 50 * 100ms = 5 segundos
                    delay(100)
                    waitCount++
                }

                if (currentFileContext != null) {
                    Log.d("ChatBotFragment", "✅ currentFileContext cargado después de ${waitCount * 100}ms")
                    // Actualizar todos los contextos efectivos desde currentFileContext
                    effectiveFileContent = currentFileContext!!.fileContent ?: ""
                    effectiveJsonContent = currentFileContext!!.jsonContent ?: ""
                    effectiveMetadata = currentFileContext!!.metadata ?: ""
                    effectiveTaskDescription = currentFileContext!!.contentSummary ?: taskDescription
                } else {
                    Log.w("ChatBotFragment", "⚠️ Timeout esperando currentFileContext después de 5 segundos")
                }
            }

            // Agregar información del tipo de archivo para que el LLM lo entienda mejor
            if (currentFileContext != null && effectiveFileContent.isNotEmpty()) {
                val fileName = currentFileContext!!.fileName
                val fileType = currentFileContext!!.fileType
                val fileExtension = fileName.substringAfterLast('.', "")

                // Detectar si el archivo no pudo ser procesado (lógica mejorada)
                // Un archivo es no procesable solo si tiene mensajes de error Y contenido muy corto
                val tieneIndicadoresError = effectiveFileContent.contains("Tipo de archivo no soportado") ||
                        effectiveFileContent.contains("no pudo ser procesado") ||
                        effectiveFileContent.contains("ESTADO DEL ANÁLISIS")

                val contenidoMuyCorto = effectiveFileContent.length < 200

                // Solo marcar como no procesable si tiene errores específicos O es muy corto
                val archivoNoProcessable = (tieneIndicadoresError && contenidoMuyCorto) ||
                        (fileType.equals("UNKNOWN", ignoreCase = true) && contenidoMuyCorto)

                if (archivoNoProcessable) {
                    // Marcar claramente que el archivo no es procesable
                    val fileMetadata = """
                        |⚠️ ARCHIVO NO PROCESABLE - NO SE PUEDE CALIFICAR ⚠️
                        |=== INFORMACIÓN DEL ARCHIVO ENVIADO ===
                        |Nombre del archivo: $fileName
                        |Tipo de archivo: $fileType
                        |Extensión: ${if (fileExtension.isNotEmpty()) fileExtension else "sin extensión"}
                        |Estado: NO PROCESABLE - El archivo no pudo ser leído o está en formato no compatible
                        |=== MENSAJE DEL SISTEMA ===
                        |
                    """.trimMargin()
                    effectiveFileContent = fileMetadata + effectiveFileContent
                } else {
                    // Prepend file metadata to help LLM understand the content
                    val fileMetadata = """
                        |=== INFORMACIÓN DEL ARCHIVO ENVIADO ===
                        |Nombre del archivo: $fileName
                        |Tipo de archivo: $fileType
                        |Extensión: ${if (fileExtension.isNotEmpty()) fileExtension else "sin extensión"}
                        |=== CONTENIDO DEL ARCHIVO ===
                        |
                    """.trimMargin()

                    effectiveFileContent = fileMetadata + effectiveFileContent
                }

                Log.d("ChatBotFragment", "📎 Metadata agregada al archivo: Nombre=$fileName, Tipo=$fileType, Extensión=$fileExtension, Procesable=${!archivoNoProcessable}")
            }

            // FALLBACK: Si taskDescription está vacío, intentar obtener el último contentSummary desde BackendApiService
            if (effectiveTaskDescription.isEmpty()) {
                Log.d("ChatBotFragment", "🔄 taskDescription vacío, intentando fallback con último contentSummary desde BackendApiService")
                effectiveTaskDescription = withContext(Dispatchers.IO) {
                    try {
                        val remoteFileContexts = BackendApiService.getFileContexts().getOrNull() ?: emptyList()
                        val latest = remoteFileContexts.maxByOrNull { it.submissionId ?: 0L }
                        val summary = latest?.contentSummary
                        Log.d("ChatBotFragment", "📋 Fallback: contentSummary obtenido desde BackendApiService")
                        summary ?: ""
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "❌ Error obteniendo contentSummary: ${e.message}")
                        ""
                    }
                }
            }

            // Si el mensaje referencia una tarea con #<índice> o #<nombre>, usar el contexto de esa tarea
            // También verificar si hay una tarea seleccionada manualmente (taskName no vacío)
            val refMatch = Regex("#(\\d+)").find(messageText)
            val refNameMatch = Regex("#([^\\s]+)").find(messageText)

            // 🔥 Prioridad 1: Tarea seleccionada manualmente (variables de instancia) o currentFileContext cargado
            // Si currentFileContext está presente, SIEMPRE usarlo primero (fue cargado por onTaskSelected)
            if (currentFileContext != null) {
                Log.d("ChatBotFragment", "🔗 Usando currentFileContext establecido por onTaskSelected")

                // 🔥 CRÍTICO: fileContent contiene el contenido del archivo del estudiante
                effectiveFileContent = currentFileContext!!.fileContent ?: ""

                // 🔥 CRÍTICO: Si el fileContent está vacío, intentar usar contentSummary o metadata
                if (effectiveFileContent.isBlank()) {
                    Log.d("ChatBotFragment", "⚠️ fileContent está vacío, buscando alternativas...")
                    // Intentar usar extractedText si está disponible
                    if (!currentFileContext!!.extractedText.isNullOrBlank()) {
                        effectiveFileContent = currentFileContext!!.extractedText!!
                        Log.d("ChatBotFragment", "📄 Usando extractedText como fileContent: ${effectiveFileContent.length} caracteres")
                    }
                }

                // 🔥 CRÍTICO: taskDescription debe combinar la descripción de la tarea + info del archivo
                // taskDescription (variable de instancia) contiene la descripción del profesor
                // contentSummary contiene un resumen del archivo
                effectiveTaskDescription = buildString {
                    append("📋 TAREA: $taskName\n")
                    if (taskDescription.isNotBlank() && taskDescription != "Sin descripción") {
                        append("📝 DESCRIPCIÓN DEL PROFESOR:\n$taskDescription\n")
                    } else if (!currentFileContext!!.contentSummary.isNullOrBlank()) {
                        // Si no hay taskDescription pero hay contentSummary, usarlo
                        append("📝 DESCRIPCIÓN/REQUISITOS:\n${currentFileContext!!.contentSummary}\n")
                    }
                    // Safely check fileName even if technically non-null (might be null at runtime via Gson)
                    val sFileName = currentFileContext!!.fileName as? String
                    if (!sFileName.isNullOrBlank()) {
                        append("\n📎 ARCHIVO ENVIADO POR EL ESTUDIANTE: ${'$'}sFileName")
                    }
                    if (!currentFileContext!!.contentSummary.isNullOrBlank() &&
                        currentFileContext!!.contentSummary != taskDescription) {
                        append("\n📄 RESUMEN DEL CONTENIDO: ${currentFileContext!!.contentSummary}")
                    }
                    // 🔥 CRÍTICO: Indicar si el contenido del archivo no está disponible
                    if (effectiveFileContent.isBlank()) {
                        append("\n\n⚠️ NOTA IMPORTANTE: El contenido del archivo del estudiante NO está disponible para análisis detallado.")
                        append("\n   El sistema solo puede proporcionar información basada en la descripción de la tarea y el resumen.")
                    }

                    // 🔥 LOGIC TO CONTROL GRADING VS Q&A (uses word-boundary detection)
                    // REMOVED STATIC INJECTION: Let backend decide via LLM signal
                    /*
                    val isGradingRequest = isGradingIntent(messageText)

                    if (isGradingRequest) {
                        append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                    } else {
                        append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                    }
                    */
                }

                // También cargar jsonContent y metadata si están disponibles
                effectiveJsonContent = currentFileContext!!.jsonContent ?: ""
                effectiveMetadata = currentFileContext!!.metadata ?: ""

                Log.d("ChatBotFragment", "✅ fileContent de currentFileContext: ${effectiveFileContent.length} caracteres")
                Log.d("ChatBotFragment", "✅ taskDescription construido: ${effectiveTaskDescription.length} caracteres")
                Log.d("ChatBotFragment", "✅ Tarea asociada: $taskName")
                Log.d("ChatBotFragment", "📋 TaskDescription preview: ${effectiveTaskDescription.take(300)}...")
            }
            // Prioridad 2: Si taskName está establecido pero currentFileContext es null
            else if (taskName.isNotEmpty() && taskDescription.isNotEmpty()) {
                Log.d("ChatBotFragment", "🔗 Usando contexto de tarea seleccionada manualmente: $taskName")

                // 🔥 CRÍTICO: Construir effectiveTaskDescription con formato de emojis
                // para que el microservicio pueda extraer la información estructurada
                effectiveTaskDescription = buildString {
                    append("📋 TAREA: $taskName\n")
                    if (taskDescription.isNotBlank() && taskDescription != "Sin descripción") {
                        append("📝 DESCRIPCIÓN DEL PROFESOR:\n$taskDescription\n")
                    }
                    // Indicar que no hay archivo del estudiante disponible
                    append("\n⚠️ NOTA: No se encontró archivo del estudiante para esta tarea")

                    // 🔥 LOGIC TO CONTROL GRADING VS Q&A (uses word-boundary detection)
                    // REMOVED STATIC INJECTION: Let backend decide via LLM signal
                    /*
                    val isGradingRequest = isGradingIntent(messageText)

                    if (isGradingRequest) {
                        append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                    } else {
                        append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                    }
                    */
                }

                Log.d("ChatBotFragment", "✅ taskDescription construido: ${effectiveTaskDescription.length} caracteres")
                Log.d("ChatBotFragment", "📋 TaskDescription preview: ${effectiveTaskDescription.take(300)}...")

                // Si TODAVÍA no hay contenido de archivo pero hay tarea seleccionada, intentar cargar contexto asociado
                if (effectiveFileContent.isEmpty() && courseId != -1L) {
                    val username = com.example.tareamov.util.SessionManager.getInstance(requireContext()).getUsername()
                    val userId = com.example.tareamov.util.SessionManager.getInstance(requireContext()).getUserId()

                    if (!username.isNullOrEmpty() && userId != -1L) {
                        withContext(Dispatchers.IO) {
                            try {
                                val api = BackendApiService

                                // 1. Buscar tarea por nombre en el curso via API
                                var task: com.example.tareamov.data.entity.Task? = null
                                val topics = api.getTopicsByCourse(courseId).getOrNull() ?: emptyList()
                                for (topic in topics) {
                                    val tasks = api.getTasksByTopic(topic.id).getOrNull() ?: emptyList()
                                    task = tasks.firstOrNull { it.name == taskName }
                                    if (task != null) break
                                }

                                if (task != null) {
                                    val finalTask = task
                                    // 2. Buscar submission via API
                                    val submission = api.getSubmissionByUserAndTask(finalTask.id, userId).getOrNull()

                                    if (submission != null) {
                                        // 3. Buscar FileContext via API
                                        val fcs = api.getFileContextsBySubmission(submission.id).getOrNull() ?: emptyList()
                                        val fc = fcs.firstOrNull()?.let(::sanitizeFileContext)

                                        if (fc != null) {
                                            effectiveFileContent = fc.fileContent ?: ""
                                            effectiveJsonContent = fc.jsonContent ?: ""
                                            effectiveMetadata = fc.metadata ?: ""

                                            // 🔥 CRÍTICO: Reconstruir effectiveTaskDescription con formato de emojis
                                            // incluyendo información del archivo encontrado
                                            effectiveTaskDescription = buildString {
                                                append("📋 TAREA: $taskName\n")
                                                if (taskDescription.isNotBlank() && taskDescription != "Sin descripción") {
                                                    append("📝 DESCRIPCIÓN DEL PROFESOR:\n$taskDescription\n")
                                                }
                                                if (fc.fileName.isNotBlank()) {
                                                    append("\n📎 ARCHIVO ENVIADO POR EL ESTUDIANTE: ${fc.fileName}")
                                                }
                                                if (!fc.contentSummary.isNullOrBlank()) {
                                                    append("\n📄 RESUMEN DEL CONTENIDO: ${fc.contentSummary}")
                                                }
                                                // Si fileContent está vacío, indicarlo
                                                if (fc.fileContent.isNullOrBlank()) {
                                                    append("\n⚠️ NOTA: El contenido del archivo no está disponible para análisis detallado")
                                                }

                                                // 🔥 LOGIC TO CONTROL GRADING VS Q&A (uses word-boundary detection)
                                                // REMOVED STATIC INJECTION: Let backend decide via LLM signal
                                                /*
                                                val isGradingRequest = isGradingIntent(messageText)

                                                if (isGradingRequest) {
                                                    append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                                                } else {
                                                    append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                                                }
                                                */
                                            }
                                            Log.d("ChatBotFragment", "📄 Contexto de archivo cargado para tarea seleccionada: ${fc.fileName}")
                                            Log.d("ChatBotFragment", "📋 effectiveTaskDescription: ${effectiveTaskDescription.take(300)}...")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("ChatBotFragment", "Error cargando contexto adicional para tarea seleccionada", e)
                            }
                            Unit
                        }
                    }
                }
            }
            // Prioridad 3: Referencia por índice #1, #2, etc.
            else if (refMatch != null && courseId != -1L) {
                val idx = refMatch.groupValues[1].toIntOrNull()
                if (idx != null) {
                    // Asegurar que tenemos el cache de tareas del curso
                    if (currentCourseTasks.isEmpty()) {
                        withContext(Dispatchers.IO) {
                            try {
                                val tasks = loadCourseTasksForOverlay()
                                currentCourseTasks.clear()
                                currentCourseTasks.addAll(tasks)
                            } catch (_: Exception) {}
                        }
                    }

                    val referencedTask = currentCourseTasks.firstOrNull { it.index == idx }
                    if (referencedTask != null) {
                        Log.d("ChatBotFragment", "🔗 Tarea referenciada por #: ${referencedTask.taskName} (id=${referencedTask.taskId})")

                        // 🔥 CONSTRUIR CONTEXTO COMPLETO DE LA TAREA
                        val taskTitle = referencedTask.taskName
                        val taskDescFromProf = referencedTask.taskDescription ?: ""

                        val baseTaskContext = buildString {
                            append("📋 TAREA: $taskTitle\n")
                            if (taskDescFromProf.isNotBlank()) {
                                append("📝 DESCRIPCIÓN DEL PROFESOR:\n$taskDescFromProf\n")
                            }

                            // 🔥 LOGIC TO CONTROL GRADING VS Q&A (uses word-boundary detection)
                            // REMOVED STATIC INJECTION: Let backend decide via LLM signal
                            /*
                            val isGradingRequest = isGradingIntent(messageText)

                            if (isGradingRequest) {
                                append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                            } else {
                                append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                            }
                            */
                        }
                        effectiveTaskDescription = baseTaskContext

                        val username = com.example.tareamov.util.SessionManager.getInstance(requireContext()).getUsername()
                        if (!username.isNullOrEmpty()) {
                            withContext(Dispatchers.IO) {
                                try {
                                    val api = BackendApiService
                                    val userId = com.example.tareamov.util.SessionManager.getInstance(requireContext()).getUserId()

                                    // 🔥 RESOLVER taskId por nombre de tarea via API
                                    var resolvedTaskId = referencedTask.taskId
                                    val allTasks = api.getTasks().getOrNull() ?: emptyList()
                                    val matchedTask = allTasks.firstOrNull { it.name == referencedTask.taskName }
                                    if (matchedTask != null) {
                                        resolvedTaskId = matchedTask.id
                                        Log.d("ChatBotFragment", "✅ TaskId resuelto via API: $resolvedTaskId (local: ${referencedTask.taskId})")
                                    }

                                    // 🔥 BUSCAR SUBMISSION via API
                                    val submission = api.getSubmissionByUserAndTask(resolvedTaskId, userId).getOrNull()
                                    Log.d("ChatBotFragment", "📊 Submission encontrada: ${submission?.id}")

                                    if (submission != null) {
                                        val currentSubmission = submission
                                        Log.d("ChatBotFragment", "✅ Submission encontrada: id=${currentSubmission.id}, file='${currentSubmission.fileName}'")

                                        // Buscar FileContext via API
                                        val fcs = api.getFileContextsBySubmission(currentSubmission.id).getOrNull() ?: emptyList()
                                        val fc: FileContext? = fcs.firstOrNull()?.let(::sanitizeFileContext)
                                        Log.d("ChatBotFragment", "📄 FileContext: ${fc?.fileName}")

                                        fc?.let { currentFc ->
                                            // 🔥 CARGAR CONTENIDO DEL ARCHIVO DEL ESTUDIANTE
                                            effectiveFileContent = currentFc.fileContent ?: ""
                                            effectiveJsonContent = currentFc.jsonContent ?: ""
                                            effectiveMetadata = currentFc.metadata ?: ""

                                            // 🔥 COMBINAR: contexto de tarea + info del archivo
                                            effectiveTaskDescription = buildString {
                                                append(baseTaskContext)
                                                if (currentFc.fileName.isNotBlank()) {
                                                    append("\n📎 ARCHIVO ENVIADO: ${currentFc.fileName}")
                                                }
                                                if (!currentFc.contentSummary.isNullOrBlank() && currentFc.contentSummary != taskDescFromProf) {
                                                    append("\n📄 RESUMEN DEL CONTENIDO:\n${currentFc.contentSummary}")
                                                }

                                                // 🔥 LOGIC TO CONTROL GRADING VS Q&A (uses word-boundary detection)
                                                // REMOVED STATIC INJECTION: Let backend decide via LLM signal
                                                /*
                                                val isGradingRequest = isGradingIntent(messageText)

                                                if (isGradingRequest) {
                                                    append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                                                } else {
                                                    append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                                                }
                                                */
                                            }
                                            currentFileContext = currentFc
                                            Log.d("ChatBotFragment", "✅ FileContext cargado con contexto completo para #$idx")
                                        }
                                        // Si no hay FileContext, effectiveTaskDescription ya tiene título + descripción
                                    }
                                    // Si no hay submission, effectiveTaskDescription ya tiene título + descripción
                                } catch (e: Exception) {
                                    Log.e("ChatBotFragment", "Error resolviendo contexto de tarea referenciada", e)
                                    // Mantener contexto base (título + descripción)
                                }
                                Unit
                            }
                        }
                        // Si no hay usuario, effectiveTaskDescription ya tiene título + descripción
                    }
                }
            }
            // Prioridad 4: Referencia por nombre #NombreTarea
            else if (refNameMatch != null && courseId != -1L) {
                val nameRef = refNameMatch.groupValues[1]
                // Intentar buscar tarea por nombre aproximado en las tareas cargadas
                if (currentCourseTasks.isEmpty()) {
                    withContext(Dispatchers.IO) {
                        try {
                            val tasks = loadCourseTasksForOverlay()
                            currentCourseTasks.clear()
                            currentCourseTasks.addAll(tasks)
                        } catch (_: Exception) {}
                    }
                }

                val referencedTask = currentCourseTasks.firstOrNull {
                    it.taskName.equals(nameRef, ignoreCase = true) || it.taskName.contains(nameRef, ignoreCase = true)
                }

                if (referencedTask != null) {
                    Log.d("ChatBotFragment", "🔗 Tarea referenciada por nombre: ${referencedTask.taskName} (id=${referencedTask.taskId})")

                    // 🔥 CONSTRUIR CONTEXTO COMPLETO DE LA TAREA
                    // Incluir: título, descripción del profesor, y contenido del estudiante
                    val taskTitle = referencedTask.taskName
                    val taskDescFromProf = referencedTask.taskDescription ?: ""

                    // Contexto base con título y descripción del profesor
                    val baseTaskContext = buildString {
                        append("📋 TAREA: $taskTitle\n")
                        if (taskDescFromProf.isNotBlank()) {
                            append("📝 DESCRIPCIÓN DEL PROFESOR:\n$taskDescFromProf\n")
                        }

                        // 🔥 LOGIC TO CONTROL GRADING VS Q&A (uses word-boundary detection)
                        // REMOVED STATIC INJECTION: Let backend decide via LLM signal
                        /*
                        val isGradingRequest = isGradingIntent(messageText)

                        if (isGradingRequest) {
                            append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                        } else {
                            append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                        }
                        */
                    }

                    effectiveTaskDescription = baseTaskContext

                    // 🔥 CRÍTICO: También cargar el FileContext asociado a esta tarea
                    val userId = sessionManager.getUserId()
                    val username = sessionManager.getUsername() ?: ""

                    if (userId != -1L) {
                        withContext(Dispatchers.IO) {
                            try {
                                val api = BackendApiService

                                // 🔥 RESOLVER taskId por nombre de tarea via API
                                var resolvedTaskId = referencedTask.taskId
                                val allTasks = api.getTasks().getOrNull() ?: emptyList()
                                val matchedTask = allTasks.firstOrNull { it.name == referencedTask.taskName }
                                if (matchedTask != null) {
                                    resolvedTaskId = matchedTask.id
                                    Log.d("ChatBotFragment", "✅ TaskId resuelto via API: $resolvedTaskId (local: ${referencedTask.taskId})")
                                }

                                // Buscar submission del usuario para esta tarea via API
                                val submission = api.getSubmissionByUserAndTask(resolvedTaskId, userId).getOrNull()
                                Log.d("ChatBotFragment", "📊 Submission encontrada para #${referencedTask.taskName}: ${submission?.id}")

                                if (submission != null) {
                                    val currentSubmission = submission
                                    // Buscar FileContext via API
                                    val fcs = api.getFileContextsBySubmission(currentSubmission.id).getOrNull() ?: emptyList()
                                    var fc: FileContext? = fcs.firstOrNull()?.let(::sanitizeFileContext)
                                    Log.d("ChatBotFragment", "📄 FileContext: ${fc?.fileName}")

                                    fc?.let { currentFc ->
                                        // 🔥 CARGAR CONTENIDO DEL ARCHIVO DEL ESTUDIANTE
                                        effectiveFileContent = currentFc.fileContent ?: ""
                                        effectiveJsonContent = currentFc.jsonContent ?: ""
                                        effectiveMetadata = currentFc.metadata ?: ""

                                        // 🔥 COMBINAR: contexto de tarea + contenido del archivo del estudiante
                                        // El taskDescription ahora incluye título + descripción del profesor
                                        // El fileContent tiene el contenido del archivo del estudiante
                                        effectiveTaskDescription = buildString {
                                            append(baseTaskContext)
                                            if (currentFc.fileName.isNotBlank()) {
                                                append("\n📎 ARCHIVO ENVIADO: ${currentFc.fileName}")
                                            }
                                            if (!currentFc.contentSummary.isNullOrBlank() && currentFc.contentSummary != taskDescFromProf) {
                                                append("\n📄 RESUMEN DEL CONTENIDO:\n${currentFc.contentSummary}")
                                            }
                                        }

                                        // Establecer como contexto actual para futuras referencias
                                        currentFileContext = currentFc
                                        Log.d("ChatBotFragment", "✅ FileContext cargado para #${referencedTask.taskName}: ${(currentFc.fileContent ?: "").length} caracteres")
                                        Log.d("ChatBotFragment", "📋 TaskDescription completo: ${effectiveTaskDescription.take(200)}...")
                                    } ?: run {
                                        Log.w("ChatBotFragment", "⚠️ No se encontró FileContext para submission ${currentSubmission.id}")
                                        // Aunque no hay FileContext, el effectiveTaskDescription ya tiene título + descripción
                                    }
                                } else {
                                    Log.w("ChatBotFragment", "⚠️ Usuario no tiene submission para tarea #${referencedTask.taskName}")
                                    // Aunque no hay submission, el effectiveTaskDescription ya tiene título + descripción
                                }
                            } catch (e: Exception) {
                                Log.e("ChatBotFragment", "Error cargando FileContext para tarea referenciada por nombre", e)
                            }
                        }
                    }
                }
            }

            // Logging mínimo (sin contenido sensible ni longitudes)
            Log.d("ChatBotFragment", "📋 Enviando contexto al microservicio...")
            Log.d("ChatBotFragment", "   - FileContext presente: ${currentFileContext != null}")
            if (currentFileContext != null) {
                Log.d("ChatBotFragment", "   - SubmissionId: ${currentFileContext!!.submissionId}")
                Log.d("ChatBotFragment", "   - FileName: '${currentFileContext!!.fileName}'")
            }

            // Data class local para capturar tanto el texto como la nota del backend
            data class LLMResponse(val text: String, val nota: Float?, val esCalificacion: Boolean = false)

            try {
                val llmResponse = withContext(Dispatchers.IO) {
                    try {
                        // 🔥 FIX: Usar datos reales del TaskItem seleccionado (fallback chain)
                        val currentSubmissionId = currentFileContext?.submissionId ?: selectedTaskSubmissionId
                        val currentTaskIdForRequest = selectedTaskRemoteTaskId // taskId real de la tarea, NO submissionId
                        val currentStudentIdForRequest = selectedTaskStudentId // ID del estudiante, NO del profesor
                        val currentFileUriForRequest = selectedTaskFileUri // URL del archivo en R2

                        Log.d("ChatBotFragment", "🔥 sendMessage datos finales: submId=$currentSubmissionId, taskId=$currentTaskIdForRequest, studentId=$currentStudentIdForRequest, fileUri=${currentFileUriForRequest?.take(60)}")

                        val body = com.example.tareamov.network.MicroservicioPromptRequest(
                            prompt = messageText,
                            ollamaUrl = getOllamaUrl(),
                            taskDescription = effectiveTaskDescription.ifEmpty { null },
                            fileContent = effectiveFileContent.ifEmpty { null },
                            jsonContent = if (effectiveJsonContent.isNotEmpty()) effectiveJsonContent else null,
                            metadata = if (effectiveMetadata.isNotEmpty()) effectiveMetadata else null,
                            userId = sessionManager.getUserId(),
                            submissionId = currentSubmissionId,
                            taskId = currentTaskIdForRequest,
                            studentId = currentStudentIdForRequest,
                            fileUri = currentFileUriForRequest // URL directa del archivo en R2
                        )
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "📤 ENVIANDO AL MICROSERVICIO:")
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "prompt: '$messageText'")
                        Log.d("ChatBotFragment", "ollamaUrl: '${getOllamaUrl()}'")
                        Log.d("ChatBotFragment", "taskDescription (descripción): '$effectiveTaskDescription'")
                        Log.d("ChatBotFragment", "fileContent (archivo): ${effectiveFileContent.length} caracteres")
                        Log.d("ChatBotFragment", "jsonContent (JSON estructurado): ${effectiveJsonContent.length} caracteres")
                        Log.d("ChatBotFragment", "metadata (metadatos): ${effectiveMetadata.length} caracteres")
                        Log.d("ChatBotFragment", "submissionId: $currentSubmissionId")
                        Log.d("ChatBotFragment", "taskId: $currentTaskIdForRequest")
                        Log.d("ChatBotFragment", "studentId: $currentStudentIdForRequest")
                        Log.d("ChatBotFragment", "fileUri: ${currentFileUriForRequest?.take(80)}")
                        Log.d("ChatBotFragment", "==============================================")

                        // Usar suspend function en lugar de .execute() para mejor manejo de timeouts
                        // Agregar userId para notificaciones
                        val currentUserId = sessionManager.getUserId()
                        val bodyWithUserId = body.copy(userId = currentUserId)
                        val resWrapper = microservicioApi.procesarPrompt(bodyWithUserId)
                        
                        // Extract inner response
                        val res = resWrapper.data

                        Log.d("ChatBotFragment", "✅ RESPUESTA RECIBIDA DEL MICROSERVICIO:")
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "📥 RESPUESTA COMPLETA DEL MODELO:")
                        Log.d("ChatBotFragment", "Success: ${resWrapper.success}")
                        Log.d("ChatBotFragment", "respuesta_texto completa: '${res?.respuesta_texto}'")
                        Log.d("ChatBotFragment", "nota del backend: ${res?.nota}")
                        Log.d("ChatBotFragment", "esCalificacion: ${res?.esCalificacion}")
                        Log.d("ChatBotFragment", "Longitud total: ${res?.respuesta_texto?.length ?: 0} caracteres")
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "✅ ENVIANDO RESPUESTA COMPLETA AL CHAT (SIN FILTROS)")
                        Log.d("ChatBotFragment", "==============================================")

                        if (!resWrapper.success || res == null) {
                             LLMResponse("Error del servidor: ${resWrapper.error ?: "Respuesta vacía"}", null, false)
                        } else {
                            // Devolver la respuesta COMPLETA tal como la envía el modelo, incluyendo formato
                            // Si la respuesta es nula y el fileContent está vacío, dar mensaje específico
                            if (res.respuesta_texto.isNullOrBlank()) {
                                // Verificar si el contenido del archivo estaba vacío
                                if (effectiveFileContent.isBlank() || effectiveFileContent.length < 50) {
                                    LLMResponse("""📊 **CALIFICACIÓN: 0/10**

❌ **RESULTADO:** No aprobado

⚠️ **MOTIVO:** La entrega no contiene contenido que pueda ser evaluado.

El archivo enviado está vacío o no se pudo leer su contenido.

💡 **PARA MEJORAR TU CALIFICACIÓN:**
1. Asegúrate de que el archivo contenga tu trabajo completo
2. Verifica que el contenido sea visible y legible
3. Si usaste un formato especial, conviértelo a PDF o TXT
4. Vuelve a subir la tarea con el contenido completo

📝 **Feedback:** Una entrega vacía siempre recibe nota 0.""", 0f, true)
                                } else {
                                    LLMResponse("Hubo un problema al procesar tu solicitud. Por favor, intenta nuevamente.", null, false)
                                }
                            } else {
                                // Capturar la nota y señal de calificación del backend directamente
                                LLMResponse(res.respuesta_texto, res.nota, res.esCalificacion == true)
                            }
                        }
                    } catch (e: HttpException) {
                        try {
                            val errorBody = e.response()?.errorBody()?.string()
                            LLMResponse("Error del microservicio (HTTP ${e.code()}): $errorBody", null)
                        } catch (ex: Exception) {
                            LLMResponse("Error al conectar con el microservicio (HTTP ${e.code()}): ${e.message()}", null)
                        }
                    } catch (e: SocketTimeoutException) {
                        // Try immediate cloud fallback when local model times out
                        try {
                            val ok = okhttp3.OkHttpClient.Builder()
                                .connectTimeout(800, java.util.concurrent.TimeUnit.MILLISECONDS)
                                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .writeTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                .addInterceptor { chain ->
                                    val orig = chain.request()
                                    val b = orig.newBuilder()
                                        .header("X-API-Key", "tareamov-mcp-api-key-2025-secure")
                                    val sUrl = com.example.tareamov.BuildConfig.SUPABASE_URL
                                    val sKey = com.example.tareamov.BuildConfig.SUPABASE_ANON_KEY
                                    if (sUrl.isNotBlank()) b.header("X-Supabase-Url", sUrl)
                                    if (sKey.isNotBlank()) b.header("X-Supabase-Key", sKey)
                                    chain.proceed(b.build())
                                }
                                .build()

                            val retrofit = Retrofit.Builder()
                                .baseUrl(if (ServerEndpointResolver.RAILWAY_MCP_URL.endsWith("/")) ServerEndpointResolver.RAILWAY_MCP_URL else ServerEndpointResolver.RAILWAY_MCP_URL + "/")
                                .addConverterFactory(GsonConverterFactory.create())
                                .client(ok)
                                .build()

                            val api = retrofit.create(com.example.tareamov.network.MicroservicioApi::class.java)

                            val fallbackSubmissionId = currentFileContext?.submissionId
                            val fallbackTaskId = fallbackSubmissionId // backend resolves taskId from submission

                            val fallbackBody = com.example.tareamov.network.MicroservicioPromptRequest(
                                prompt = messageText,
                                ollamaUrl = getOllamaUrl(),
                                taskDescription = effectiveTaskDescription.ifEmpty { null },
                                fileContent = effectiveFileContent.ifEmpty { null },
                                jsonContent = if (effectiveJsonContent.isNotEmpty()) effectiveJsonContent else null,
                                metadata = if (effectiveMetadata.isNotEmpty()) effectiveMetadata else null,
                                userId = sessionManager.getUserId(),
                                submissionId = fallbackSubmissionId,
                                taskId = fallbackTaskId,
                                studentId = sessionManager.getUserId(),
                                fileUri = null
                            )

                            val cloudResWrapper = api.procesarPrompt(fallbackBody)
                            val cloudRes = cloudResWrapper.data
                            
                            if (cloudResWrapper.success && cloudRes != null && !cloudRes.respuesta_texto.isNullOrBlank()) {
                                LLMResponse(cloudRes.respuesta_texto, cloudRes.nota, cloudRes.esCalificacion == true)
                            } else {
                                LLMResponse("El modelo en la nube no devolvió respuesta válida: ${cloudResWrapper.error}", null)
                            }
                        } catch (ce: Exception) {
                            LLMResponse("El modelo está tardando más de lo esperado. Intenta nuevamente en unos minutos.", null)
                        }
                    } catch (e: java.net.ConnectException) {
                        LLMResponse("No se puede conectar con el microservicio. Verifica que esté ejecutándose en ${getMicroserviceBaseUrl()}", null)
                    } catch (e: Exception) {
                        LLMResponse("Error inesperado: ${e.message}", null)
                    }
                }

                val response = llmResponse.text

                // 🎯 SEMANTIC: Solo el LLM decide si mostrar botones de calificación (via esCalificacion del backend)
                // NO usamos heurísticas locales que causan falsos positivos (ej: "que se nota al hablar de Marie Curie?")
                val hasCalification = llmResponse.esCalificacion
                
                // 🎯 Solo extraer valor de calificación si el LLM señalizó que es calificación
                val calificationValue = if (hasCalification && llmResponse.nota != null) {
                    // Usar la nota del backend directamente (solo cuando esCalificacion=true)
                    val notaValue = llmResponse.nota
                    Log.d("ChatBotFragment", "📊 Usando nota del backend: $notaValue (esCalificacion=true)")
                    if (notaValue % 1 == 0f) "${notaValue.toInt()}/10" else String.format("%.1f/10", notaValue)
                } else if (hasCalification) {
                    // Fallback: extraer del texto solo si el LLM marcó como calificación
                    extractCalificationValue(response)
                } else {
                    // No es calificación - no extraer nota del texto para evitar falsos positivos
                    null
                }

                val botMessage = ChatMessage(
                    message = response,
                    isFromUser = false,
                    sessionId = sessionId,
                    hasCalification = hasCalification,
                    calificationValue = calificationValue,
                    calificationAdded = false,
                    senderUsername = "DeepSeek",
                    senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                )
                withContext(Dispatchers.IO) {
                    val savedBotId = database.chatMessageDao().insertMessage(botMessage)
                    syncChatMessageToBackend(botMessage, savedBotId)
                }
            } catch (e: Exception) {
                val errorMessage = ChatMessage(
                    message = "Lo siento, tuve un problema al procesar tu mensaje. ${generateFallbackResponse(messageText)}",
                    isFromUser = false,
                    sessionId = sessionId,
                    senderUsername = "DeepSeek",
                    senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                )
                withContext(Dispatchers.IO) {
                    val savedErrId = database.chatMessageDao().insertMessage(errorMessage)
                    syncChatMessageToBackend(errorMessage, savedErrId)
                }
            } finally {
                loadingProgressBar.visibility = View.GONE
            }
        }
    }

    private fun generateFallbackResponse(userMessage: String): String {
        // Simple bot responses - you can enhance this with actual AI integration
        return when {
            userMessage.contains("hola", ignoreCase = true) ||
                    userMessage.contains("buenos días", ignoreCase = true) ||
                    userMessage.contains("buenas tardes", ignoreCase = true) -> {
                "¡Hola! Soy tu asistente virtual. ¿En qué puedo ayudarte con tus tareas y estudios?"
            }
            userMessage.contains("tarea", ignoreCase = true) -> {
                "Puedo ayudarte con información sobre tus tareas. ¿Qué necesitas saber específicamente?"
            }
            userMessage.contains("calificación", ignoreCase = true) ||
                    userMessage.contains("nota", ignoreCase = true) -> {
                "Para consultar tus calificaciones, revisa la sección de entregas en cada curso. ¿Hay alguna calificación específica que te preocupe?"
            }
            userMessage.contains("curso", ignoreCase = true) -> {
                "Puedo proporcionarte información sobre los cursos disponibles. ¿Qué curso te interesa?"
            }
            userMessage.contains("ayuda", ignoreCase = true) -> {
                "Estoy aquí para ayudarte. Puedo responder preguntas sobre:\n• Tareas y entregas\n• Calificaciones\n• Cursos disponibles\n• Navegación en la aplicación\n\n¿Qué necesitas?"
            }
            userMessage.contains("gracias", ignoreCase = true) -> {
                "¡De nada! Estoy aquí cuando me necesites. ¿Hay algo más en lo que pueda ayudarte?"
            }
            userMessage.contains("adiós", ignoreCase = true) ||
                    userMessage.contains("hasta luego", ignoreCase = true) -> {
                "¡Hasta luego! Que tengas un excelente día de estudios. 📚"
            }
            else -> {
                val responses = listOf(
                    "Entiendo tu consulta. ¿Podrías ser más específico para poder ayudarte mejor?",
                    "Interesante pregunta. Te sugiero que revises la documentación del curso o contactes a tu profesor para más detalles.",
                    "Puedo ayudarte con eso. ¿Podrías proporcionarme más contexto sobre lo que necesitas?",
                    "Esa es una buena pregunta. Te recomiendo explorar los recursos del curso o buscar en la biblioteca digital.",
                    "Para obtener la mejor respuesta, te sugiero que consultes con tu instructor o revises el material del curso."
                )
                responses.random()
            }
        }
    }





    /**
     * Detecta si el mensaje del usuario solicita una calificación/evaluación formal.
     * Usa word-boundary regex para evitar falsos positivos como "se nota al hablar de Marie Curie".
     * La palabra "nota" solo se detecta en contextos de calificación ("mi nota", "dame nota", "la nota", etc.)
     */
    private fun isGradingIntent(message: String): Boolean {
        // 🔥 STATIC REGEX DISABLED: Let LLM decide contextually
        return false
        /*
        val lower = message.lowercase()
        // Explicit negation: user says "don't grade"
        if (lower.contains("no quiero nota") || lower.contains("sin nota") || lower.contains("no califiques")) return false
        // Strong grading keywords (word boundaries to avoid substring matches)
        val gradingPatterns = listOf(
            Regex("\\bcalifi[ck]a"),          // califica, calificar, calificación
            Regex("\\bpuntaje\\b"),
            Regex("\\bevalua"),               // evalua, evaluar, evaluación 
            Regex("\\bgrade\\b"),
            Regex("\\bscore\\b"),
            // "nota" solo en contexto de calificación, NO como verbo "se nota"
            Regex("\\bmi nota\\b"),
            Regex("\\bla nota\\b"),
            Regex("\\bdame.*nota"),
            Regex("\\bpon(er|ga|le)?.*nota"),
            Regex("\\bnota\\s*:?\\s*\\d"),   // "nota: 8" or "nota 8" 
            Regex("\\bcuánto saq"),
            Regex("\\brevisar entrega")
        )
        return gradingPatterns.any { it.containsMatchIn(lower) }
        */
    }

    /**
     * Detecta si el mensaje del usuario solicita calificación y si la respuesta del bot contiene una calificación
     * NOTA: Este método legacy no controla los botones de calificación.
     * Los botones son controlados por esCalificacion del backend (señal del LLM).
     */
    private fun detectCalification(userMessage: String, botResponse: String): Boolean {
        val userMessageLower = userMessage.lowercase()
        val botResponseLower = botResponse.lowercase()

        // 1. Detección FUERTE: Si el bot usa el formato estándar de calificación del backend
        // Esto asegura que siempre aparezcan los botones si el backend envió una nota explícita
        if (botResponseLower.contains("**calificación actual:") ||
            botResponseLower.contains("**calificacion actual:") ||
            botResponseLower.contains("📊 **calificación actual:") ||
            botResponseLower.contains("calificación actual:") ||
            botResponseLower.contains("**calificación:") ||
            botResponseLower.contains("**calificacion:") ||
            botResponseLower.contains("📊 **calificación:") ||
            botResponseLower.contains("**nota:") ||
            botResponseLower.contains("📊 **nota:")) {
            return true
        }

        // Palabras clave que indican solicitud de calificación
        // SE ELIMINARON términos genéricos como "feedback", "revisar", "retroalimentación" para evitar falsos positivos
        val calificationPatterns = listOf(
            Regex("\\bcalifi[ck]aci[óo]n\\b"),
            Regex("\\bnota\\b"),
            Regex("\\bpuntaje\\b"),
            Regex("\\bpuntuaci[óo]n\\b"),
            Regex("\\bscore\\b"),
            Regex("\\brating\\b"),
            Regex("\\bevaluaci[óo]n\\b"),
            Regex("\\bcu[áa]nto saqu[ée]\\b"),
            Regex("\\bmi nota\\b")
        )

        // Verificar si el usuario pidió calificación
        val userAskedForCalification = calificationPatterns.any { pattern ->
            pattern.containsMatchIn(userMessageLower)
        }
        
        // Si el usuario pide explícitamente NO calificar, respetamos
        if (userMessageLower.contains("no quiero nota") || userMessageLower.contains("sin nota")) {
            return false
        }

        // Verificar si la respuesta contiene formato de calificación
        val botHasCalification = 
                Regex("califi[ck]aci[óo]n.*:?\\s*\\d+").containsMatchIn(botResponseLower) ||
                Regex("nota.*:?\\s*\\d+").containsMatchIn(botResponseLower) ||
                Regex("puntaje.*:?\\s*\\d+").containsMatchIn(botResponseLower)

        // IMPORTANTE: userAskedForCalification es OBLIGATORIO. El bot no debe mostrar botones
        // solo porque menciona una nota en su respuesta (semántica), a menos que el usuario lo haya pedido.
        // O si la detección "FUERTE" arriba (backend format) ya retornó true.
        return userAskedForCalification && botHasCalification
    }

    /**
     * Extrae el valor de la calificación de la respuesta del bot
     */
    private fun extractCalificationValue(botResponse: String): String? {
        // Buscar patrones de calificación comunes
        val patterns = listOf(
            // Nuevos formatos específicos (con y sin asteriscos al final)
            Regex("📊 \\*\\*CALIFICACIÓN ACTUAL: (\\d+/\\d+)\\*\\*", RegexOption.IGNORE_CASE),
            Regex("📊 \\*\\*CALIFICACIÓN ACTUAL: (\\d+/\\d+)", RegexOption.IGNORE_CASE),
            Regex("📊 \\*\\*CALIFICACIÓN ACTUAL:\\*\\* (\\d+/\\d+)", RegexOption.IGNORE_CASE),
            Regex("calificación actual:\\s*(\\d+/\\d+)", RegexOption.IGNORE_CASE),
            Regex("calificacion actual:\\s*(\\d+/\\d+)", RegexOption.IGNORE_CASE),

            // Formatos anteriores
            Regex("calificación:\\s*(\\d+/\\d+)", RegexOption.IGNORE_CASE),
            Regex("calificacion:\\s*(\\d+/\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+/\\d+)"),
            Regex("nota:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("calificación:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("calificacion:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("nota:\\s*(\\d+/\\d+)", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(botResponse)
            if (match != null) {
                return match.groupValues[1]
            }
        }

        return null
    }

    /**
     * Extrae la calificación numérica para el campo gradeEditText (solo el número)
     */
    private fun extractGradeFromMessage(message: String): String? {
        // 🔥 VALIDACIÓN CRÍTICA: Si el mensaje contiene explícitamente "NO proporciones una calificación numérica"
        // o indicadores de modo consulta, IGNORAR cualquier número que parezca una nota.
        if (message.contains("NO proporciones una calificación numérica", ignoreCase = true) ||
            message.contains("MODO CONSULTA", ignoreCase = true) ||
            message.contains("📊 **CALIFICACIÓN ACTUAL: 0/10**", ignoreCase = true) && message.contains("TEMA ENTREGADO: Archivo no recuperable", ignoreCase = true)) {
            Log.d("ChatBotFragment", "🚫 Modo consulta o error de archivo detectado, ignorando extracción de nota.")
            return null
        }

        // Buscar patrones para extraer solo el número de la calificación
        val patterns = listOf(
            "calificación:\\s*(\\d+(?:[.,]\\d+)?)".toRegex(RegexOption.IGNORE_CASE),
            "nota:\\s*(\\d+(?:[.,]\\d+)?)".toRegex(RegexOption.IGNORE_CASE),
            "puntaje:\\s*(\\d+(?:[.,]\\d+)?)".toRegex(RegexOption.IGNORE_CASE),
            "score:\\s*(\\d+(?:[.,]\\d+)?)".toRegex(RegexOption.IGNORE_CASE),
            "grade:\\s*(\\d+(?:[.,]\\d+)?)".toRegex(RegexOption.IGNORE_CASE),
            "(\\d+(?:[.,]\\d+)?)/10".toRegex(RegexOption.IGNORE_CASE),
            "\\*\\*calificación\\s+actual:\\s*(\\d+(?:[.,]\\d+)?)".toRegex(RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(message)
            if (match != null) {
                val grade = match.groupValues[1]

                // Log para debugging
                Log.d("ChatBotFragment", "🔍 Grade extraído: '$grade'")

                // Normalizar el formato decimal: reemplazar coma por punto
                val normalizedGrade = grade.replace(",", ".")

                // La nota ya viene en escala 0-10 del backend
                val gradeValue = try {
                    normalizedGrade.toFloat()
                } catch (e: NumberFormatException) {
                    Log.e("ChatBotFragment", "❌ No se pudo convertir grade a Float: $grade (normalizado: $normalizedGrade)", e)
                    null
                }

                return if (gradeValue != null) {
                    String.format("%.1f", gradeValue.coerceIn(0f, 10f))
                } else null
            }
        }

        return null
    }

    /**
     * Extrae el feedback del mensaje del bot
     * Devuelve el mensaje completo para enviarlo al feedbackEditText
     */
    private fun extractFeedbackFromMessage(message: String): String? {
        // Limpiar el mensaje pero conservar todo el contenido
        val cleanedMessage = message
            .replace("\\*\\*".toRegex(), "") // Quitar formato **negrita**
            .replace("\\*".toRegex(), "")   // Quitar formato *cursiva*
            .replace("📚|📝|🔄|📊|⭐|💬|⚡".toRegex(), "") // Quitar emojis comunes
            .replace("TAREA PARA CALIFICAR", "")
            .replace("Análisis IA Completado", "")
            .replace("CALIFICACIÓN IA", "")
            .lines()
            .filter { it.trim().isNotEmpty() } // Filtrar líneas vacías
            .joinToString("\n")
            .trim()

        // Devolver el mensaje completo limpio (sin límite de caracteres)
        return if (cleanedMessage.isNotEmpty()) cleanedMessage else message
    }

    /**
     * Extrae el ID de TaskSubmission del mensaje basándose en referencias #<número>
     */
    private fun extractTaskSubmissionIdFromMessage(message: String): Long? {
        try {
            // Buscar patrones #<número> en el mensaje
            val pattern = Regex("#(\\d+)")
            val matches = pattern.findAll(message)

            // Tomar la primera referencia encontrada
            val firstMatch = matches.firstOrNull()
            if (firstMatch != null) {
                val taskSubmissionId = firstMatch.groupValues[1].toLongOrNull()
                Log.d("ChatBotFragment", "ID de TaskSubmission extraído del mensaje: $taskSubmissionId")
                return taskSubmissionId
            }

            Log.d("ChatBotFragment", "No se encontró referencia #<número> en el mensaje")
            return null
        } catch (e: Exception) {
            Log.e("ChatBotFragment", "Error extrayendo TaskSubmission ID del mensaje: ${e.message}")
            return null
        }
    }

    /**
     * Busca el ID de TaskSubmission en el contexto del chat reciente de manera suspendida
     */
    private suspend fun findTaskSubmissionIdInContext(): Long? {
        return withContext(Dispatchers.IO) {
            try {
                val recentMessages = database.chatMessageDao().getRecentMessages(sessionId, limit = 10)
                val pattern = Regex("#(\\d+)")

                // Buscar en mensajes del usuario (isFromUser = true) en orden inverso
                for (chatMessage in recentMessages.reversed()) {
                    if (chatMessage.isFromUser) {
                        val contextMatches = pattern.findAll(chatMessage.message)
                        val contextMatch = contextMatches.firstOrNull()
                        if (contextMatch != null) {
                            val contextTaskSubmissionId = contextMatch.groupValues[1].toLongOrNull()
                            Log.d("ChatBotFragment", "ID de TaskSubmission extraído del contexto: $contextTaskSubmissionId")
                            return@withContext contextTaskSubmissionId
                        }
                    }
                }

                Log.d("ChatBotFragment", "No se encontró referencia #<número> en el contexto del chat")
                null
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error buscando en contexto del chat: ${e.message}")
                null
            }
        }
    }

    /**
     * Maneja el click en el botón "Agregar calificación"
     */
    private fun handleAddCalification(message: ChatMessage) {
        lifecycleScope.launch {
            try {
                // Extraer la calificación numérica y el feedback del mensaje del bot
                // PRIORITY: Usar el valor estructurado que viene del backend (nota precisa)
                val rawGrade = if (!message.calificationValue.isNullOrBlank()) {
                    val calVal = message.calificationValue!!
                    val parts = calVal.split("/")
                    val numerator = parts[0].trim().replace(",", ".").toFloatOrNull()
                    val denominator = parts.getOrNull(1)?.trim()?.toFloatOrNull() ?: 10f
                    if (numerator != null && denominator > 0f) {
                        val normalized = (numerator / denominator) * 10f
                        val clamped = normalized.coerceIn(0f, 10f)
                        if (clamped % 1f == 0f) clamped.toInt().toString() else String.format("%.1f", clamped)
                    } else parts[0].trim()
                } else null

                val grade = rawGrade ?: extractGradeFromMessage(message.message)
                val feedback = extractFeedbackFromMessage(message.message)

                if (grade != null && feedback != null) {
                    // Almacenar la calificación en el CalificationManager
                    val calificationManager = CalificationManager.getInstance(requireContext())

                    // Intentar extraer el número del # del mensaje para determinar qué TaskSubmission actualizar
                    val taskSubmissionId = extractTaskSubmissionIdFromMessage(message.message)
                        ?: findTaskSubmissionIdInContext() // Buscar en contexto si no se encuentra en el mensaje
                    val targetSubmissionId = taskSubmissionId?.takeIf { it > 0 }
                        ?: currentFileContext?.submissionId?.takeIf { it > 0 }
                        ?: selectedTaskSubmissionId // Fallback: usar el submission seleccionado previamente

                    calificationManager.storePendingCalification(
                        grade = grade,
                        feedback = feedback,
                        submissionId = targetSubmissionId
                    )

                    Log.d("ChatBotFragment", "Calificación almacenada: Grade=$grade, Feedback=$feedback, TargetSubmissionId=$targetSubmissionId")

                    // Actualizar directamente la tabla TaskSubmission
                    if (targetSubmissionId != null) {
                        // Normalizar el formato decimal: reemplazar coma por punto antes de convertir
                        val normalizedGrade = grade.replace(",", ".")
                        val gradeFloat = try {
                            normalizedGrade.toFloat().coerceIn(0f, 10f)
                        } catch (e: NumberFormatException) {
                            Log.e("ChatBotFragment", "❌ Error convirtiendo grade '$grade' (normalizado: '$normalizedGrade') a Float", e)
                            null
                        }

                        if (gradeFloat != null) {
                            withContext(Dispatchers.IO) {
                                // Obtener la entrega por ID desde el backend
                                val taskSubmission = BackendApiService.getSubmissionById(targetSubmissionId).getOrNull()

                                if (taskSubmission != null) {
                                    // Calificar la submission via backend
                                    val gradeResult = BackendApiService.gradeSubmission(targetSubmissionId, gradeFloat, feedback)

                                    // Obtener información de la tarea
                                    val task = BackendApiService.getTaskById(taskSubmission.taskId).getOrNull()
                                    val effectiveTaskName = task?.name ?: "Tarea desconocida"

                                    Log.d("ChatBotFragment", "✅ TaskSubmission actualizada:")
                                    Log.d("ChatBotFragment", "   - ID: $targetSubmissionId")
                                    Log.d("ChatBotFragment", "   - Tarea: $effectiveTaskName")
                                    val studentName = try {
                                        BackendApiService.getUserById(taskSubmission.studentId).getOrNull()?.usuario ?: taskSubmission.studentId.toString()
                                    } catch (e: Exception) {
                                        taskSubmission.studentId.toString()
                                    }
                                    Log.d("ChatBotFragment", "   - Estudiante: $studentName")
                                    Log.d("ChatBotFragment", "   - Grade: $gradeFloat")
                                    Log.d("ChatBotFragment", "   - Feedback: $feedback")

                                    if (gradeResult.isSuccess) {
                                        Log.i("ChatBotFragment", "✅ TaskSubmission $targetSubmissionId actualizado en backend")
                                        com.example.tareamov.util.AppCache.invalidateAdmin()
                                        com.example.tareamov.util.AppCache.invalidateNotifications()

                                        // 📧📱 NOTIFICAR AL ESTUDIANTE que recibió una calificación
                                        val graderUsername = sessionManager.getUsername() ?: "Profesor"

                                        // Notificación al estudiante deshabilitada desde la UI de chat

                                        // Añadir la tarea calificada a la lista en memoria y actualizar UI
                                        try {
                                            val topicForTask = BackendApiService.getTopicById(task?.topicId ?: -1L).getOrNull()
                                            val courseTitleForThis = if (topicForTask?.courseId != null && topicForTask.courseId != 0L) {
                                                BackendApiService.getCourseById(topicForTask.courseId).getOrNull()?.title
                                            } else null

                                            val gradeDisplayForUI = if (gradeFloat > 10) String.format("%.1f/10", gradeFloat / 10) else String.format("%.1f/10", gradeFloat)
                                            val feedbackForUI = feedback ?: "Sin feedback disponible"

                                            val gradedTaskItem = GradedTaskItem(
                                                taskId = task?.id ?: taskSubmission.taskId,
                                                taskName = task?.name ?: effectiveTaskName,
                                                taskDescription = task?.description ?: "Sin descripción",
                                                topicName = topicForTask?.name ?: "Sin tema",
                                                index = taskSubmission.id.toInt(),
                                                grade = gradeDisplayForUI,
                                                feedback = feedbackForUI
                                            )

                                            withContext(Dispatchers.Main) {
                                                gradedTasksList.removeAll { it.index == gradedTaskItem.index }
                                                gradedTasksList.add(0, gradedTaskItem)
                                                lastGradedSubmissionId = gradedTaskItem.index.toLong()
                                                gradedTaskOverlayAdapter.updateGradedTasks(gradedTasksList.toList())
                                                courseTitleForThis?.let { gradedCourseNameTextView.text = it }
                                                try { gradedTasksButton.visibility = View.VISIBLE } catch (e: Exception) {}
                                            }
                                        } catch (e: Exception) {
                                            Log.w("ChatBotFragment", "No se pudo añadir la tarea calificada en memoria: ${e.message}")
                                        }
                                    } else {
                                        Log.w("ChatBotFragment", "⚠️ No se pudo actualizar TaskSubmission $targetSubmissionId en backend")
                                    }
                                } else {
                                    Log.w("ChatBotFragment", "❌ No se encontró TaskSubmission con ID: $targetSubmissionId")
                                }
                            }
                        } else {
                            Log.e("ChatBotFragment", "❌ No se pudo convertir grade a Float: $grade")
                        }
                    } else {
                        Log.w("ChatBotFragment", "❌ No hay submissionId disponible para actualizar TaskSubmission")
                    }
                }

                // Marcar la calificación como agregada
                val updatedMessage = message.copy(calificationAdded = true)

                withContext(Dispatchers.IO) {
                    database.chatMessageDao().updateMessage(updatedMessage)
                }

                // Mostrar mensaje de confirmación mejorado
                val taskSubmissionId = extractTaskSubmissionIdFromMessage(message.message)
                val confirmationText = if (taskSubmissionId != null) {
                    "✅ **Calificación agregada a TaskSubmission #$taskSubmissionId**\n\nLa calificación ${message.calificationValue ?: "obtenida"} ha sido guardada en la base de datos.\n\n📌 **Información:** Grade: ${grade ?: "N/A"} | Feedback aplicado correctamente."
                } else {
                    "✅ **Calificación agregada**\n\nLa calificación ${message.calificationValue ?: "obtenida"} ha sido guardada en la base de datos.\n\n📌 **Información:** Grade: ${grade ?: "N/A"} | Feedback aplicado correctamente."
                }

                val confirmationMessage = ChatMessage(
                    message = confirmationText,
                    isFromUser = false,
                    sessionId = sessionId
                    , senderUsername = "DeepSeek",
                    senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                )

                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(confirmationMessage)
                }

                // Recargar mensajes para actualizar la UI
                loadMessages()

                // Agregar a la lista de tareas calificadas en memoria
                addToGradedTasksList(message, grade, feedback)

                val toastMessage = if (taskSubmissionId != null) {
                    "Calificación guardada en TaskSubmission #$taskSubmissionId"
                } else {
                    "Calificación guardada en TaskSubmission"
                }
                Toast.makeText(context, toastMessage, Toast.LENGTH_LONG).show()

            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error agregando calificación: ${e.message}")
                Toast.makeText(context, "Error al agregar calificación", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Maneja el click en el botón "No agregar"
     */
    private fun handleRejectCalification(message: ChatMessage) {
        lifecycleScope.launch {
            try {
                // Marcar la calificación como rechazada (agregada = true para ocultar botones)
                val updatedMessage = message.copy(calificationAdded = true)

                withContext(Dispatchers.IO) {
                    database.chatMessageDao().updateMessage(updatedMessage)
                }
                //
                // Recargar mensajes para actualizar la UI
                loadMessages()

                Toast.makeText(context, "Calificación no agregada", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error rechazando calificación: ${e.message}")
                Toast.makeText(context, "Error al procesar la acción", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Maneja la edición de un mensaje del usuario
     */


    /**
     * Maneja el click en el botón TTS para reproducir el mensaje con voz
     */
    private fun handleTTSClick(message: ChatMessage) {
        lifecycleScope.launch {
            try {
                if (message.isPlaying) {
                    // Toggle Pause/Resume
                    if (ttsService.isCurrentlyPlaying()) {
                        ttsService.pausePlayback()
                        message.isPaused = true
                        if (isAdded && context != null) {
                            Toast.makeText(requireContext(), "⏸️ Pausado", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        ttsService.resumePlayback()
                        message.isPaused = false
                        if (isAdded && context != null) {
                            Toast.makeText(requireContext(), "▶️ Reanudando", Toast.LENGTH_SHORT).show()
                        }
                    }
                    chatAdapter.notifyDataSetChanged()
                    return@launch
                }

                // Stop any current playback and reset states
                ttsService.stopPlayback()
                chatAdapter.currentList.forEach {
                    it.isPlaying = false
                    it.isPaused = false
                }

                // Set playing state
                message.isPlaying = true
                message.isPaused = false
                chatAdapter.notifyDataSetChanged()

                // Mostrar feedback visual
                if (isAdded && context != null) {
                    Toast.makeText(
                        requireContext(),
                        "🔊 Reproduciendo mensaje...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Reproducir el mensaje INMEDIATAMENTE con TTS nativo (sin esperar red)
                ttsService.speakImmediate(
                    text = message.message,
                    onStart = {
                        Log.d("ChatBotFragment", "TTS playback started IMMEDIATELY")
                    },
                    onComplete = {
                        lifecycleScope.launch {
                            withContext(Dispatchers.Main) {
                                message.isPlaying = false
                                message.isPaused = false
                                chatAdapter.notifyDataSetChanged()
                                if (isAdded && context != null) {
                                    Toast.makeText(
                                        requireContext(),
                                        "✅ Reproducción completada",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    },
                    onError = { error ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.Main) {
                                message.isPlaying = false
                                message.isPaused = false
                                chatAdapter.notifyDataSetChanged()
                                if (isAdded && context != null) {
                                    Toast.makeText(
                                        requireContext(),
                                        "❌ Error de TTS: $error",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                                Log.e("ChatBotFragment", "TTS error: $error")
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                message.isPlaying = false
                message.isPaused = false
                chatAdapter.notifyDataSetChanged()
                if (isAdded && context != null) {
                    Toast.makeText(
                        requireContext(),
                        "❌ Error al reproducir: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }



    /**
     * Muestra el overlay con las tareas calificadas
     */
    private fun showGradedTasksOverlay() {
        // Allow showing graded tasks even when courseId is unknown (-1).
        // In that case we load all graded submissions for the current user across courses.
        lifecycleScope.launch {
            try {
                loadingProgressBar.visibility = View.VISIBLE
                // Siempre recargar desde Supabase/DB para tener datos actualizados
                val loadResult = loadGradedTasksFromChat()
                gradedTasksList.clear()
                gradedTasksList.addAll(loadResult.items)

                loadingProgressBar.visibility = View.GONE

                if (gradedTasksList.isEmpty()) {
                    Toast.makeText(context, "No hay tareas calificadas aún", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                Log.d("ChatBotFragment", "Mostrando ${gradedTasksList.size} tareas calificadas")

                // If we just graded a specific submission, prefer to show only that item
                if (lastGradedSubmissionId != null) {
                    val filtered = gradedTasksList.filter { it.index == lastGradedSubmissionId!!.toInt() }
                    if (filtered.isNotEmpty()) {
                        // If the loader discovered a single course title, use it; otherwise try to infer from the item
                        gradedCourseNameTextView.text = loadResult.courseTitle ?: filtered.firstOrNull()?.topicName ?: "Tarea calificada"
                        gradedTaskOverlayAdapter.updateGradedTasks(filtered)
                        // clear the pointer so next open shows full list
                        lastGradedSubmissionId = null
                    } else {
                        gradedCourseNameTextView.text = loadResult.courseTitle ?: if (courseId == -1L) "Tareas calificadas (todas)" else courseTitle.ifEmpty { "Curso Actual" }
                        gradedTaskOverlayAdapter.updateGradedTasks(gradedTasksList.toList()) // Crear copia para evitar modificaciones concurrentes
                    }
                } else {
                    // Prefer explicit course title detected during load, else fall back to current course context or a generic label
                    gradedCourseNameTextView.text = loadResult.courseTitle ?: if (courseId == -1L) "Tareas calificadas (todas)" else courseTitle.ifEmpty { "Curso Actual" }
                    gradedTaskOverlayAdapter.updateGradedTasks(gradedTasksList.toList()) // Crear copia para evitar modificaciones concurrentes
                }

                gradedTasksOverlayBackground.visibility = View.VISIBLE
                gradedTasksOverlay.visibility = View.VISIBLE

                gradedTasksOverlayBackground.alpha = 0f
                gradedTasksOverlay.alpha = 0f
                gradedTasksOverlay.translationY = 50f

                gradedTasksOverlayBackground.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()

                gradedTasksOverlay.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(250)
                    .start()

            } catch (e: Exception) {
                loadingProgressBar.visibility = View.GONE
                Log.e("ChatBotFragment", "Error cargando tareas calificadas", e)
                Toast.makeText(context, "Error al cargar tareas calificadas", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Oculta el overlay de tareas calificadas
     */
    private fun hideGradedTasksOverlay() {
        gradedTasksOverlayBackground.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                gradedTasksOverlayBackground.visibility = View.GONE
            }
            .start()
        gradedTasksOverlay.animate()
            .alpha(0f)
            .translationY(50f)
            .setDuration(250)
            .withEndAction {
                gradedTasksOverlay.visibility = View.GONE
            }
            .start()
    }

    /**
     * Carga las tareas calificadas desde TaskSubmissionDao usando IDs únicos
     */
    /**
     * Resultado de carga de tareas calificadas: lista de items y título de curso si se identifica uno solo
     */
    private data class GradedTasksLoadResult(
        val items: List<GradedTaskItem>,
        val courseTitle: String?
    )

    /**
     * Carga las tareas calificadas desde Supabase (prioridad) o TaskSubmissionDao
     */
    private suspend fun loadGradedTasksFromChat(): GradedTasksLoadResult {
        return withContext(Dispatchers.IO) {
            try {
                val gradedTasks = mutableListOf<GradedTaskItem>()
                val processedTaskSubmissionIds = mutableSetOf<Long>()
                val encounteredCourseIds = mutableSetOf<Long>()
                val courseTitleMap = mutableMapOf<Long, String>()

                Log.d("ChatBotFragment", "Cargando tareas calificadas...")

                val userId = try {
                    sessionManager.getUserId()
                } catch (e: Exception) {
                    null
                }

                // Obtener submissions desde el backend
                var allSubmissions: List<TaskSubmission> = emptyList()

                if (courseId != -1L && userId != null) {
                    try {
                        Log.d("ChatBotFragment", "Obteniendo submissions desde backend para curso $courseId y usuario $userId")
                        val courseSubmissions = BackendApiService.getSubmissionsByCourse(courseId).getOrNull() ?: emptyList()
                        allSubmissions = courseSubmissions.filter { it.studentId == userId }
                        Log.d("ChatBotFragment", "Submissions obtenidas del backend: ${allSubmissions.size}")
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "Error fetching submissions from backend: ${e.message}")
                    }
                }

                Log.d("ChatBotFragment", "Total submissions a procesar: ${allSubmissions.size}")

                for (submission in allSubmissions) {
                    // Evitar duplicados por submission ID
                    if (processedTaskSubmissionIds.contains(submission.id)) {
                        continue
                    }

                    try {
                        if (submission.grade != null) {
                            val task = BackendApiService.getTaskById(submission.taskId).getOrNull()

                            if (task != null) {
                                val topic = BackendApiService.getTopicById(task.topicId).getOrNull()
                                val courseIdFromTopic = topic?.courseId ?: -1L
                                if (courseIdFromTopic != -1L) {
                                    encounteredCourseIds.add(courseIdFromTopic)
                                    try {
                                        val course = BackendApiService.getCourseById(courseIdFromTopic).getOrNull()
                                        if (course != null) {
                                            courseTitleMap[courseIdFromTopic] = course.title
                                        }
                                    } catch (e: Exception) {
                                        // ignore missing course
                                    }
                                }
                                // Nota: Si el topic no está, podríamos buscarlo también, pero por ahora "Sin tema" es aceptable

                                val gradeValue = submission.grade
                                val gradeDisplay = if (gradeValue > 10) {
                                    String.format("%.1f/10", gradeValue / 10)
                                } else {
                                    String.format("%.1f/10", gradeValue)
                                }

                                val feedback = submission.feedback ?: "Sin feedback disponible"

                                val gradedTask = GradedTaskItem(
                                    taskId = task.id,
                                    taskName = task.name,
                                    taskDescription = task.description ?: "Sin descripción",
                                    topicName = topic?.name ?: "Sin tema",
                                    index = submission.id.toInt(),
                                    grade = gradeDisplay,
                                    feedback = feedback
                                )

                                gradedTasks.add(gradedTask)
                                processedTaskSubmissionIds.add(submission.id)
                                Log.d("ChatBotFragment", "Tarea calificada cargada: ${task.name} - Grade: $gradeDisplay")
                            } else {
                                Log.w("ChatBotFragment", "Submission ${submission.id} tiene taskId ${submission.taskId} pero no se encontró la tarea")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "Error procesando submission ${submission.id}: ${e.message}")
                    }
                }

                Log.d("ChatBotFragment", "Total de tareas calificadas cargadas: ${gradedTasks.size}")
                // Determinar si todas las tareas pertenecen a un único curso
                val singleCourseTitle = if (encounteredCourseIds.size == 1) {
                    val id = encounteredCourseIds.first()
                    courseTitleMap[id]
                } else null

                GradedTasksLoadResult(items = gradedTasks, courseTitle = singleCourseTitle)
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error cargando tareas calificadas desde TaskSubmissionDao", e)
                GradedTasksLoadResult(items = emptyList(), courseTitle = null)
            }
        }
    }

    /**
     * Carga las tareas calificadas al inicializar el fragmento
     */
    private fun loadGradedTasksOnStart() {
        lifecycleScope.launch {
            try {
                Log.d("ChatBotFragment", "Cargando tareas calificadas al inicializar fragmento...")

                // Cargar las tareas calificadas desde la base de datos
                val loadResult = loadGradedTasksFromChat()

                // Limpiar la lista actual y agregar las tareas cargadas
                gradedTasksList.clear()
                gradedTasksList.addAll(loadResult.items)

                // Actualizar el adaptador
                gradedTaskOverlayAdapter.updateGradedTasks(gradedTasksList.toList())

                // Si se detectó un único curso, mostrar su título en el header del overlay
                if (loadResult.courseTitle != null) {
                    gradedCourseNameTextView.text = loadResult.courseTitle
                }

                Log.d("ChatBotFragment", "Tareas calificadas cargadas al inicializar: ${gradedTasksList.size}")

            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error cargando tareas calificadas al inicializar: ${e.message}")
            }
        }
    }

    /**
     * Maneja la selección de una tarea calificada
     */
    private fun onGradedTaskSelected(gradedTask: GradedTaskItem) {
        // Insertar el contexto de la tarea calificada en el chat
        val taskContext = "Información de la tarea calificada:\n" +
                "📝 Tarea: ${gradedTask.taskName}\n" +
                "📚 Tema: ${gradedTask.topicName}\n" +
                "📊 Calificación: ${gradedTask.grade}\n" +
                "💬 Pregúntame sobre esta calificación..."

        messageEditText.setText(taskContext)
        messageEditText.setSelection(taskContext.length)

        // Ocultar el overlay
        hideGradedTasksOverlay()

        Toast.makeText(context, "Tarea calificada seleccionada: ${gradedTask.taskName}", Toast.LENGTH_SHORT).show()
    }

    /**
     * Muestra el overlay con la lista de cursos del usuario
     */
    private fun showTaskListOverlay(initialQuery: String = "") {
        // Show background and overlay immediately for instant feedback
        taskListOverlayBackground.visibility = View.VISIBLE
        taskListOverlayBackground.alpha = 0f
        taskListOverlayBackground.animate()
            .alpha(1f)
            .setDuration(100)
            .start()

        // Show overlay with skeleton immediately
        if (taskListOverlay.visibility != View.VISIBLE) {
            taskListOverlay.visibility = View.VISIBLE
            taskListOverlay.alpha = 0f
            taskListOverlay.animate()
                .alpha(1f)
                .setDuration(100)
                .start()
        }

        // Reset to course selection mode
        isSelectingCourse = true
        selectedCourseId = -1L
        selectedCourseTitle = ""
        backToCoursesButton.visibility = View.GONE
        overlayTitleTextView.text = "Seleccionar Curso"
        overlaySearchEditText.hint = "Buscar por nombre o categoría..."
        overlaySearchEditText.text.clear()

        // Show skeleton loading state immediately
        courseNameTextView.text = "Cargando cursos..."
        taskOverlayAdapter.setLoading(true)

        // Setup search listener
        setupSearchListener()

        // Setup back button listener
        backToCoursesButton.setOnClickListener {
            navigateBackToCourses()
        }

        lifecycleScope.launch {
            try {
                // Load ALL courses for the user
                val courses = loadAllUserCourses()

                courseNameTextView.text = "${courses.size} cursos encontrados"
                allCoursesList.clear()
                allCoursesList.addAll(courses)
                currentCourseTasks.clear()
                currentCourseTasks.addAll(courses)

                val filtered = if (initialQuery.isNotEmpty()) {
                    courses.filter {
                        it.taskName.contains(initialQuery, ignoreCase = true) ||
                                it.topicName.contains(initialQuery, ignoreCase = true) // topicName = category
                    }
                } else {
                    courses
                }

                // Hide skeleton and show real data
                taskOverlayAdapter.setLoading(false)
                taskOverlayAdapter.updateTasks(filtered)

                if (courses.isEmpty()) {
                    Toast.makeText(context, "No se encontraron cursos", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error loading courses for overlay", e)
                Toast.makeText(context, "Error al cargar los cursos", Toast.LENGTH_SHORT).show()
                taskOverlayAdapter.setLoading(false)
                taskListOverlayBackground.visibility = View.GONE
            }
        }
    }

    /**
     * Configura el listener de búsqueda para filtrar cursos/tareas
     */
    private fun setupSearchListener() {
        overlaySearchEditText.removeTextChangedListener(searchTextWatcher)
        overlaySearchEditText.addTextChangedListener(searchTextWatcher)
    }

    private val searchTextWatcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) {
            val query = s?.toString()?.trim() ?: ""
            filterOverlayList(query)
        }
    }

    /**
     * Filtra la lista del overlay según la búsqueda
     */
    private fun filterOverlayList(query: String) {
        val sourceList = if (isSelectingCourse) allCoursesList else currentCourseTasks

        val filtered = if (query.isEmpty()) {
            sourceList
        } else {
            sourceList.filter { item ->
                item.taskName.contains(query, ignoreCase = true) ||
                        item.topicName.contains(query, ignoreCase = true) ||
                        item.taskDescription.contains(query, ignoreCase = true) ||
                        (item.studentUsername?.contains(query, ignoreCase = true) == true)
            }
        }

        taskOverlayAdapter.updateTasks(filtered)

        // Update subtitle
        if (isSelectingCourse) {
            courseNameTextView.text = if (query.isEmpty()) {
                "${allCoursesList.size} cursos encontrados"
            } else {
                "${filtered.size} de ${allCoursesList.size} cursos"
            }
        }
    }

    /**
     * Navega de vuelta a la lista de cursos
     */
    private fun navigateBackToCourses() {
        isSelectingCourse = true
        selectedCourseId = -1L
        selectedCourseTitle = ""
        backToCoursesButton.visibility = View.GONE
        overlayTitleTextView.text = "Seleccionar Curso"
        overlaySearchEditText.hint = "Buscar por nombre o categoría..."
        overlaySearchEditText.text.clear()
        courseNameTextView.text = "${allCoursesList.size} cursos encontrados"

        currentCourseTasks.clear()
        currentCourseTasks.addAll(allCoursesList)
        taskOverlayAdapter.updateTasks(allCoursesList)
    }

    /**
     * Oculta el overlay de la lista de tareas
     */
    private fun hideTaskListOverlay() {
        // Reset state when hiding
        isSelectingCourse = true
        selectedCourseId = -1L
        selectedCourseTitle = ""
        overlaySearchEditText.text.clear()
        taskListOverlayBackground.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                taskListOverlayBackground.visibility = View.GONE
            }
            .start()
        taskListOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                taskListOverlay.visibility = View.GONE
            }
            .start()
    }

    /**
     * Carga cursos relevantes para el usuario:
     * 1. Cursos CREADOS por el usuario que tengan entregas de estudiantes (modo profesor)
     * 2. Cursos donde el usuario está INSCRITO y ha entregado tareas (modo estudiante)
     * Ambos conjuntos se combinan sin duplicados.
     */
    private suspend fun loadAllUserCourses(): List<TaskItem> {
        return withContext(Dispatchers.IO) {
            val userId = sessionManager.getUserId()
            if (userId == -1L) {
                Log.w("ChatBotFragment", "No user ID found")
                return@withContext emptyList()
            }

            try {
                val coursesMap = mutableMapOf<Long, com.example.tareamov.data.entity.Course>()

                // ── 1. Cursos CREADOS por el usuario que tengan entregas ──
                val myCourses = BackendApiService.getCoursesByCreatorId(userId).getOrNull() ?: emptyList()
                for (course in myCourses) {
                    try {
                        val submissions = BackendApiService.getSubmissionsByCourse(course.id).getOrNull() ?: emptyList()
                        if (submissions.isNotEmpty()) {
                            coursesMap[course.id] = course
                        }
                    } catch (e: Exception) {
                        Log.w("ChatBotFragment", "Error checking submissions for created course ${course.id}: ${e.message}")
                    }
                }
                Log.d("ChatBotFragment", "Found ${coursesMap.size} created courses with submissions for user $userId")

                // ── 2. Cursos donde el usuario ENTREGÓ tareas (modo estudiante) ──
                try {
                    // Obtener las submissions del usuario actual
                    val mySubmissions = BackendApiService.getMySubmissions(page = 1, limit = 200).getOrNull() ?: emptyList()
                    if (mySubmissions.isNotEmpty()) {
                        // Extraer taskIds únicos y resolver courseIds vía Task → Topic → Course
                        val taskIds = mySubmissions.map { it.taskId }.distinct()
                        val courseIdsFromSubmissions = mutableSetOf<Long>()

                        for (taskId in taskIds) {
                            try {
                                val task = BackendApiService.getTaskById(taskId).getOrNull()
                                if (task != null && task.topicId > 0) {
                                    val topic = BackendApiService.getTopicById(task.topicId).getOrNull()
                                    if (topic != null) {
                                        courseIdsFromSubmissions.add(topic.courseId)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("ChatBotFragment", "Error resolving course for task $taskId: ${e.message}")
                            }
                        }

                        // Obtener los cursos por IDs (solo los que no tenemos ya)
                        val missingCourseIds = courseIdsFromSubmissions.filter { it !in coursesMap }
                        if (missingCourseIds.isNotEmpty()) {
                            val enrolledCourses = BackendApiService.getCoursesByIds(missingCourseIds).getOrNull() ?: emptyList()
                            for (course in enrolledCourses) {
                                coursesMap[course.id] = course
                            }
                        }
                        Log.d("ChatBotFragment", "Found ${courseIdsFromSubmissions.size} courses from student submissions for user $userId")
                    }
                } catch (e: Exception) {
                    Log.w("ChatBotFragment", "Error loading student submissions for courses: ${e.message}")
                }

                Log.d("ChatBotFragment", "Total ${coursesMap.size} courses with submissions for user $userId (created + enrolled)")

                coursesMap.values.sortedBy { it.title }.mapIndexed { index, course ->
                    TaskItem(
                        taskId = course.id,
                        taskName = course.title,
                        taskDescription = course.description ?: "Sin descripción",
                        topicName = course.category ?: "Sin categoría",
                        index = index + 1
                    )
                }
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error loading courses with submissions", e)
                emptyList()
            }
        }
    }

    private suspend fun loadCoursesWithGradedSubmissions(): List<TaskItem> {
        return loadAllUserCourses() // Now just delegates to loadAllUserCourses
    }

    /**
     * Carga TODAS las tareas enviadas por usuarios en un curso específico
     */
    private suspend fun loadSubmissionsForCourse(courseId: Long): List<TaskItem> {
        return withContext(Dispatchers.IO) {
            try {
                // Fetch ALL submissions for the course from backend
                val submissions = BackendApiService.getSubmissionsByCourse(courseId).getOrNull() ?: emptyList()

                Log.d("ChatBotFragment", "Loaded ${submissions.size} submissions for course $courseId")

                // Resolve usernames for each submission and group by student
                val usernameCache = mutableMapOf<Long, String>()
                val taskCache = mutableMapOf<Long, com.example.tareamov.data.entity.Task?>()

                // Pre-resolve usernames and tasks
                for (sub in submissions) {
                    if (!usernameCache.containsKey(sub.studentId)) {
                        val user = BackendApiService.getUserById(sub.studentId).getOrNull()
                        usernameCache[sub.studentId] = user?.usuario ?: "Unknown"
                    }
                    if (!taskCache.containsKey(sub.taskId)) {
                        taskCache[sub.taskId] = BackendApiService.getTaskById(sub.taskId).getOrNull()
                    }
                }

                val submissionsByStudent = submissions
                    .groupBy { usernameCache[it.studentId] ?: "Unknown" }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)

                val taskItems = mutableListOf<TaskItem>()
                var index = 1

                for (entry in submissionsByStudent.entries) {
                    val username = entry.key
                    val studentSubmissions = entry.value

                    // Calculate student average for context
                    val progressManager = com.example.tareamov.util.StudentProgressManager(requireContext())
                    val avgGrade = try {
                        progressManager.calculateStudentAverageForCourse(courseId, username)
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "Error calculating average for $username", e)
                        0f
                    }
                    val formattedAvg = String.format("%.1f", avgGrade)

                    // Add each submission for this student
                    for (sub in studentSubmissions) {
                        val task = taskCache[sub.taskId]
                        val taskTitle = task?.name ?: "Tarea sin título"
                        val grade = sub.grade ?: 0f

                        // Format grade info
                        val gradeInfo = if (grade > 0) {
                            val gradeFormatted = if (grade % 1 == 0f) grade.toInt().toString() else String.format("%.1f", grade)
                            "✅ Nota: $gradeFormatted/10"
                        } else {
                            "⏳ Pendiente de calificar"
                        }

                        taskItems.add(
                            TaskItem(
                                taskId = sub.taskId,
                                taskName = taskTitle,
                                taskDescription = "$gradeInfo • Promedio: $formattedAvg",
                                topicName = username,
                                index = index++,
                                studentUsername = username,
                                averageGrade = formattedAvg,
                                submissionId = sub.id,
                                studentId = sub.studentId,
                                fileUri = sub.fileUri
                            )
                        )
                    }
                }

                taskItems
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error loading submissions for course $courseId", e)
                emptyList()
            }
        }
    }

    /**
     * Carga las tareas del curso para mostrar en el overlay del comando #
     * Busca todas las tareas del curso (o todas si no hay curso definido)
     * e intenta asociarlas con una entrega si existe.
     */
    private suspend fun loadCourseTasksForOverlay(): List<TaskItem> {
        return withContext(Dispatchers.IO) {
            try {
                val taskItems = mutableListOf<TaskItem>()
                var tasks: List<com.example.tareamov.data.entity.Task> = emptyList()
                val topicMap = mutableMapOf<Long, String>()

                // Obtener tareas desde BackendApiService
                if (courseId != -1L) {
                    val topics = BackendApiService.getTopicsByCourse(courseId).getOrNull() ?: emptyList()
                    val courseTasks = mutableListOf<com.example.tareamov.data.entity.Task>()
                    for (topic in topics) {
                        val topicTasks = BackendApiService.getTasksByTopic(topic.id).getOrNull() ?: emptyList()
                        courseTasks.addAll(topicTasks)
                        topicMap[topic.id] = topic.name
                    }
                    tasks = courseTasks
                } else {
                    tasks = BackendApiService.getTasks().getOrNull() ?: emptyList()
                }

                Log.d("ChatBotFragment", "Cargando tareas... encontradas: ${tasks.size}")

                // Usar índice secuencial para referencias con #1, #2, etc.
                var sequentialIndex = 1

                for (task in tasks) {
                    try {
                        // Resolver nombre del tema
                        var topicName = topicMap[task.topicId]
                        if (topicName == null) {
                            val topic = BackendApiService.getTopicById(task.topicId).getOrNull()
                            if (topic != null) {
                                topicName = topic.name
                                topicMap[task.topicId] = topicName
                            } else {
                                topicName = "Sin tema"
                            }
                        }

                        // Descripción ya viene del API
                        var taskDescription = task.description ?: ""
                        if (taskDescription.isBlank()) {
                            taskDescription = "Sin descripción"
                        }

                        taskItems.add(
                            TaskItem(
                                taskId = task.id,
                                taskName = task.name,
                                taskDescription = taskDescription,
                                topicName = topicName,
                                index = sequentialIndex++ // Índice secuencial 1, 2, 3...
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "Error procesando tarea ${task.id}: ${e.message}")
                    }
                }

                // Actualizar cache para referencias por #
                currentCourseTasks.clear()
                currentCourseTasks.addAll(taskItems)

                Log.d("ChatBotFragment", "Total de tareas cargadas para overlay: ${taskItems.size}")
                taskItems
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error loading course tasks", e)
                emptyList()
            }
        }
    }

    /**
     * Maneja la selección de una tarea del overlay
     * Ahora también carga el contexto del archivo (contentSummary y fileContent)
     */
    private fun onTaskSelected(task: TaskItem) {
        if (isSelectingCourse) {
            // User selected a course, load ALL submissions with skeleton
            lifecycleScope.launch {
                try {
                    isSelectingCourse = false
                    selectedCourseId = task.taskId
                    selectedCourseTitle = task.taskName

                    // Update UI for course mode
                    overlayTitleTextView.text = "Entregas del Curso"
                    backToCoursesButton.visibility = View.VISIBLE
                    courseNameTextView.text = task.taskName
                    overlaySearchEditText.hint = "Buscar por estudiante o tarea..."
                    overlaySearchEditText.text.clear()

                    // Show skeleton while loading submissions
                    taskOverlayAdapter.setLoading(true)

                    val submissions = loadSubmissionsForCourse(task.taskId) // taskId is courseId here
                    currentCourseTasks.clear()
                    currentCourseTasks.addAll(submissions)

                    // Update subtitle with count
                    courseNameTextView.text = "${task.taskName} • ${submissions.size} entregas"

                    // Hide skeleton and show real data
                    taskOverlayAdapter.setLoading(false)
                    taskOverlayAdapter.updateTasks(currentCourseTasks)

                    if (submissions.isEmpty()) {
                        Toast.makeText(context, "No hay entregas en este curso", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("ChatBotFragment", "Error loading submissions for course", e)
                    Toast.makeText(context, "Error al cargar las entregas", Toast.LENGTH_SHORT).show()
                    taskOverlayAdapter.setLoading(false)
                }
            }
            return
        }

        lifecycleScope.launch {
            try {
                // ✅ CRÍTICO: Actualizar SIEMPRE las variables de instancia para que sendMessage las use
                taskName = task.taskName
                taskDescription = task.taskDescription ?: ""

                // 🔥 CRÍTICO: Guardar datos del TaskItem como respaldo inmediato
                selectedTaskSubmissionId = task.submissionId
                selectedTaskStudentId = task.studentId
                selectedTaskFileUri = task.fileUri
                selectedTaskRemoteTaskId = task.taskId
                Log.d("ChatBotFragment", "🔒 Backup guardado: submId=${task.submissionId}, studentId=${task.studentId}, fileUri=${task.fileUri?.take(60)}, taskId=${task.taskId}")

                // Actualizar UI del contexto activo
                if (::activeContextValue.isInitialized) {
                    activeContextValue.text = taskName
                    activeContextValue.setTextColor(android.graphics.Color.parseColor("#DDDDDD")) // Highlight
                    if (::activeContextIcon.isInitialized) {
                        activeContextIcon.setColorFilter(android.graphics.Color.parseColor("#DDDDDD"))
                    }
                }

                Log.d("ChatBotFragment", "🎯 Tarea seleccionada: $taskName")
                Log.d("ChatBotFragment", "📝 Descripción: $taskDescription")

                // Insertar el nombre de la tarea en el input
                val currentText = messageEditText.text.toString()
                val cursorPosition = messageEditText.selectionStart

                // Encontrar el inicio del hashtag actual
                var start = cursorPosition - 1
                while (start >= 0 && currentText[start] != '#') {
                    start--
                }

                if (start >= 0) {
                    val newText = currentText.substring(0, start) +
                            "#${task.taskName} " +
                            currentText.substring(cursorPosition)

                    // Evitar que el TextWatcher dispare la búsqueda de nuevo
                    isUpdatingTextSpans = true
                    messageEditText.setText(newText)
                    messageEditText.setSelection(start + task.taskName.length + 2) // +2 por # y espacio
                    isUpdatingTextSpans = false
                } else {
                    // Fallback
                    messageEditText.setText("#${task.taskName} ")
                    messageEditText.setSelection(messageEditText.text.length)
                }

                // Intentar cargar submission y contexto usando el taskId y usuario actual
                // Use studentUsername from task if available (instructor view), otherwise current user
                val username = task.studentUsername ?: sessionManager.getUsername() ?: ""
                val userId = if (task.studentUsername != null) {
                    // If viewing a student submission, we need their ID.
                    withContext(Dispatchers.IO) {
                        val uid = BackendApiService.getUserByUsername(username).getOrNull()?.id
                        uid ?: sessionManager.getUserId()
                    }
                } else {
                    sessionManager.getUserId()
                }

                Log.d("ChatBotFragment", "🔍 Buscando submission para:")
                Log.d("ChatBotFragment", "   - taskId: ${task.taskId}")
                Log.d("ChatBotFragment", "   - username: '$username'")
                Log.d("ChatBotFragment", "   - userId: $userId")

                // Lógica mejorada para obtener la submission y el contexto
                var fileContext: FileContext? = null
                var submission: TaskSubmission? = null

                withContext(Dispatchers.IO) {
                    // Log de búsqueda inicial
                    Log.d("ChatBotFragment", "🔍 Buscando submission para:")
                    Log.d("ChatBotFragment", "   - taskId local: ${task.taskId}")
                    Log.d("ChatBotFragment", "   - taskName: '${task.taskName}'")
                    Log.d("ChatBotFragment", "   - username: '$username'")
                    Log.d("ChatBotFragment", "   - userId: $userId")

                    // Resolver el taskId usando BackendApiService
                    var remoteTaskId = task.taskId
                    try {
                        val allTasks = BackendApiService.getTasks().getOrNull() ?: emptyList()
                        val remoteTask = allTasks.firstOrNull { it.name == task.taskName }
                        if (remoteTask != null) {
                            remoteTaskId = remoteTask.id
                            Log.d("ChatBotFragment", "✅ TaskId remoto resuelto: $remoteTaskId (local era: ${task.taskId})")

                            if (taskDescription.isBlank() || taskDescription == "Sin descripción") {
                                val remoteDescription = remoteTask.description
                                if (!remoteDescription.isNullOrBlank()) {
                                    taskDescription = remoteDescription
                                    Log.d("ChatBotFragment", "📝 Descripción actualizada desde API: ${taskDescription.take(100)}...")
                                }
                            }
                        } else {
                            Log.w("ChatBotFragment", "⚠️ No se encontró tarea remota con nombre '${task.taskName}', usando taskId local")
                        }
                    } catch (e: Exception) {
                        Log.w("ChatBotFragment", "⚠️ Error resolviendo taskId remoto: ${e.message}")
                    }

                    // Buscar submission via BackendApiService
                    var foundSubmissions: List<TaskSubmission> = emptyList()
                    try {
                        Log.d("ChatBotFragment", "🌐 Consultando BackendApiService por task_id=$remoteTaskId y student_id=$userId...")
                        val submissionResult = BackendApiService.getSubmissionByUserAndTask(remoteTaskId, userId)
                        val sub = submissionResult.getOrNull()
                        if (sub != null) {
                            foundSubmissions = listOf(sub)
                        }
                        Log.d("ChatBotFragment", "📊 Submissions encontradas: ${foundSubmissions.size}")
                    } catch (e: Exception) {
                        Log.w("ChatBotFragment", "⚠️ Error consultando submissions: ${e.message}")
                    }

                    // Fallback: buscar todas las submissions de la tarea
                    if (foundSubmissions.isEmpty()) {
                        try {
                            val allSubmissions = BackendApiService.getSubmissionsByTask(remoteTaskId).getOrNull() ?: emptyList()
                            foundSubmissions = allSubmissions.filter { it.studentId == userId }
                            Log.d("ChatBotFragment", "📊 Submissions filtradas por studentId=$userId: ${foundSubmissions.size}")
                        } catch (e: Exception) {
                            Log.w("ChatBotFragment", "⚠️ Error en fallback submissions: ${e.message}")
                        }
                    }

                    // Tomar la submission más reciente
                    val sortedSubmissions = foundSubmissions.sortedByDescending { it.submissionDate }

                    Log.d("ChatBotFragment", "✅ Submissions del usuario actual: ${sortedSubmissions.size}")

                    if (sortedSubmissions.isNotEmpty()) {
                        submission = sortedSubmissions.first()
                        Log.d("ChatBotFragment", "📝 Submission encontrada: id=${submission!!.id}, file='${submission!!.fileName}'")

                        // Buscar FileContext via BackendApiService
                        try {
                            fileContext = BackendApiService
                                .getFileContextsBySubmission(submission!!.id)
                                .getOrNull()
                                ?.firstOrNull()
                                ?.let(::sanitizeFileContext)
                            if (fileContext != null) {
                                Log.d("ChatBotFragment", "📄 FileContext: ENCONTRADO")
                                Log.d("ChatBotFragment", "   - fileName: ${fileContext!!.fileName}")
                                Log.d("ChatBotFragment", "   - fileContent length: ${(fileContext!!.fileContent ?: "").length}")
                                Log.d("ChatBotFragment", "   - contentSummary: ${fileContext!!.contentSummary?.take(100)}")
                            } else {
                                Log.d("ChatBotFragment", "📄 FileContext: NO ENCONTRADO")
                            }
                        } catch (e: Exception) {
                            Log.w("ChatBotFragment", "⚠️ Error consultando FileContext: ${e.message}")
                        }
                    } else {
                        Log.w("ChatBotFragment", "⚠️ No se encontraron submissions para este usuario y tarea")
                    }

                    // 3. Fallback final: Si tenemos submission pero no FileContext, intentar leer el archivo
                    if (fileContext == null && submission != null) {
                        Log.w("ChatBotFragment", "⚠️ FileContext no encontrado, intentando crear contexto desde archivo...")

                        var actualFileContent: String? = null

                        // Intentar leer el contenido real del archivo
                        try {
                            val rawFileUri = submission!!.fileUri ?: ""
                            Log.d("ChatBotFragment", "🔍 Intentando leer archivo desde URI: $rawFileUri")

                            if (rawFileUri.startsWith("http")) {
                                // 🔥 FIX: Usar OkHttp para URLs HTTP (R2, etc.) en lugar de ContentResolver
                                Log.d("ChatBotFragment", "🌐 URL HTTP detectada, usando OkHttp para descargar...")
                                val httpClient = okhttp3.OkHttpClient.Builder()
                                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                                    .build()
                                val httpRequest = okhttp3.Request.Builder().url(rawFileUri).get().build()
                                val httpResponse = httpClient.newCall(httpRequest).execute()
                                if (httpResponse.isSuccessful) {
                                    actualFileContent = httpResponse.body?.string()
                                    Log.d("ChatBotFragment", "✅ Archivo descargado vía OkHttp: ${actualFileContent?.length ?: 0} caracteres")
                                } else {
                                    Log.w("ChatBotFragment", "⚠️ OkHttp respuesta no exitosa: ${httpResponse.code}")
                                }
                                httpResponse.close()
                            } else if (rawFileUri.isNotEmpty()) {
                                // URI local — usar ContentResolver
                                val fileUri = android.net.Uri.parse(rawFileUri)
                                requireContext().contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                    actualFileContent = inputStream.bufferedReader().use { it.readText() }
                                    Log.d("ChatBotFragment", "✅ Archivo leído desde URI local: ${actualFileContent!!.length} caracteres")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("ChatBotFragment", "⚠️ No se pudo leer el archivo: ${e.message}")
                        }

                        val fallbackContent = if (actualFileContent != null) {
                            """
                            |=== INFORMACIÓN DEL ARCHIVO (LEÍDO DESDE ALMACENAMIENTO) ===
                            |Nombre del archivo: ${submission!!.fileName}
                            |Estudiante ID: ${submission!!.studentId}
                            |Fecha de entrega: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(submission!!.submissionDate)}
                            |=== CONTENIDO DEL ARCHIVO ===
                            |
                            |$actualFileContent
                        """.trimMargin()
                        } else {
                            """
                            |=== INFORMACIÓN DE LA ENTREGA (CONTEXTO LIMITADO) ===
                            |⚠️ ADVERTENCIA: El contenido completo del archivo no está disponible
                            |
                            |Estudiante ID: ${submission!!.studentId}
                            |Fecha: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault()).format(submission!!.submissionDate)}
                            |Archivo adjunto: ${submission!!.fileName}
                            |URI del archivo: ${submission!!.fileUri}
                            |
                            |NOTA: El archivo fue enviado pero no se pudo recuperar su contenido completo.
                            |Recomendación: Vuelve a cargar el archivo desde TaskSubmissionsFragment para análisis completo.
                        """.trimMargin()
                        }

                        Log.d("ChatBotFragment", "📦 Creando FileContext temporal con ${fallbackContent.length} caracteres")

                        fileContext = FileContext(
                            submissionId = submission!!.id,
                            fileName = submission!!.fileName,
                            fileType = submission!!.fileName.substringAfterLast('.', "unknown"),
                            fileContent = fallbackContent,
                            contentSummary = "Entrega realizada: ${submission!!.fileName}"
                        )

                        // FileContext temporal creado en memoria (no se guarda en DB local)
                    }
                }

                if (fileContext != null) {
                    // ✅ CRÍTICO: Establecer este contexto como el contexto actual
                    currentFileContext = fileContext

                    // 🔥 CRÍTICO: Si el fileContent está vacío pero tenemos contentSummary,
                    // intentar obtener contenido adicional de la descripción de la tarea
                    if (fileContext!!.fileContent.isNullOrBlank()) {
                        Log.d("ChatBotFragment", "⚠️ FileContext tiene fileContent vacío, usando contentSummary como fallback")
                        // El contentSummary generalmente contiene la descripción de la tarea
                        if (!fileContext!!.contentSummary.isNullOrBlank()) {
                            // Actualizar taskDescription si está vacía
                            if (taskDescription.isBlank() || taskDescription == "Sin descripción") {
                                taskDescription = fileContext!!.contentSummary!!
                                Log.d("ChatBotFragment", "📝 taskDescription actualizado desde contentSummary: ${taskDescription.take(100)}...")
                            }
                        }
                    }

                    Log.d("ChatBotFragment", "✅ currentFileContext establecido:")
                    Log.d("ChatBotFragment", "   - FileName: ${fileContext!!.fileName}")
                    Log.d("ChatBotFragment", "   - FileContent length: ${(fileContext!!.fileContent ?: "").length}")
                    Log.d("ChatBotFragment", "   - ContentSummary: ${fileContext!!.contentSummary?.take(100)}")
                    Log.d("ChatBotFragment", "   - TaskDescription actual: ${taskDescription.take(100)}...")

                    // Actualizar información del curso
                    if (submission != null) {
                        updateCourseInfo(submission!!.id)
                    }

                    // Mostrar mensaje informativo sobre el contexto cargado
                    val contextMessage = ChatMessage(
                        message = "📄 **Contexto de tarea cargado**\n\n" +
                                "📝 Tarea: ${task.taskName}\n" +
                                "📁 Archivo: ${fileContext!!.fileName}\n" +
                                "🔧 Tipo: ${fileContext!!.fileType}\n" +
                                "📊 Contenido: ${(fileContext!!.fileContent ?: "").length} caracteres\n\n" +
                                "✅ Ahora puedes hacer preguntas sobre esta tarea y su entrega.",
                        isFromUser = false,
                        sessionId = sessionId,
                        timestamp = System.currentTimeMillis()
                        , senderUsername = "DeepSeek",
                        senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                    )

                    // Insertar mensaje en la base de datos
                    withContext(Dispatchers.IO) {
                        database.chatMessageDao().insertMessage(contextMessage)
                    }

                    // Actualizar UI (automático por Flow)
                    // chatAdapter.addMessage(contextMessage) 
                    // messagesRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)
                } else {
                    Log.d("ChatBotFragment", "⚠️ No se encontró contexto de archivo para la tarea ${task.taskName}")
                    Log.d("ChatBotFragment", "   Pero taskName y taskDescription SÍ están establecidos para sendMessage")

                    // ✅ NO limpiar currentFileContext aquí, dejarlo como null
                    // Las variables taskName y taskDescription YA están establecidas arriba
                    currentFileContext = null

                    // Logic for task without submission or context
                    updateTaskInfoByTaskId(task.taskId)

                    // Show message
                    val contextMessage = ChatMessage(
                        message = "📝 **Tarea seleccionada**\n\n" +
                                "📌 Tarea: ${task.taskName}\n" +
                                "ℹ️ Descripción: ${task.taskDescription}",
                        isFromUser = false,
                        sessionId = sessionId,
                        timestamp = System.currentTimeMillis()
                        , senderUsername = "DeepSeek",
                        senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                    )
                    withContext(Dispatchers.IO) {
                        database.chatMessageDao().insertMessage(contextMessage)
                    }
                    // chatAdapter.addMessage(contextMessage)
                    // messagesRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)
                }

                hideTaskListOverlay()

            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error selecting task", e)
                Toast.makeText(context, "Error al seleccionar la tarea", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Actualiza la información del curso cuando se carga el contexto del archivo
     */
    private fun updateCourseInfo(submissionId: Long) {
        lifecycleScope.launch {
            try {
                val taskInfo = withContext(Dispatchers.IO) {
                    // Obtener la entrega
                    val submission = BackendApiService.getSubmissionById(submissionId).getOrNull()
                    if (submission != null) {
                        // Obtener la tarea
                        val task = BackendApiService.getTaskById(submission.taskId).getOrNull()
                        if (task != null) {
                            // Obtener el tema
                            val topic = BackendApiService.getTopicById(task.topicId).getOrNull()
                            if (topic != null) {
                                // Obtener el curso
                                val course = BackendApiService.getCourseById(topic.courseId).getOrNull()
                                if (course != null) {
                                    // Formatear la fecha de entrega
                                    val dateFormat = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                                    val deliveryDateFormatted = dateFormat.format(submission.submissionDate)

                                    mapOf(
                                        "taskName" to task.name,
                                        "taskDescription" to (task.description ?: "Sin descripción"),
                                        "topicName" to topic.name,
                                        "courseTitle" to course.title,
                                        "deliveryDate" to deliveryDateFormatted,
                                        "courseId" to topic.courseId
                                    )
                                } else null
                            } else null
                        } else null
                    } else null
                }

                if (taskInfo != null) {
                    taskName = taskInfo["taskName"] as String
                    taskDescription = taskInfo["taskDescription"] as String
                    topicName = taskInfo["topicName"] as String
                    courseTitle = taskInfo["courseTitle"] as String
                    deliveryDate = taskInfo["deliveryDate"] as String
                    courseId = taskInfo["courseId"] as Long

                    Log.d("ChatBotFragment", "Información de tarea cargada: $taskName - $topicName - $courseTitle (courseId: $courseId)")

                    // Actualizar UI del contexto activo
                    if (::activeContextValue.isInitialized) {
                        activeContextValue.text = taskName
                        activeContextValue.setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                        if (::activeContextIcon.isInitialized) {
                            activeContextIcon.setColorFilter(android.graphics.Color.parseColor("#DDDDDD"))
                        }
                    }

                    // Actualizar el adaptador con la nueva información
                    val taskInfoForAdapter = ChatMessageAdapter.TaskInfo(
                        taskName = taskName,
                        taskDescription = taskDescription,
                        topicName = topicName,
                        courseTitle = courseTitle,
                        deliveryDate = deliveryDate
                    )
                    chatAdapter.updateTaskInfo(taskInfoForAdapter)
                } else {
                    // Fallback: load task info via BackendApiService
                    try {
                        Log.d("ChatBotFragment", "Attempting to load task info from BackendApiService for submissionId=$submissionId")
                        val remoteSubmission = withContext(Dispatchers.IO) { BackendApiService.getSubmissionById(submissionId).getOrNull() }
                        if (remoteSubmission != null) {
                            val remoteTask = withContext(Dispatchers.IO) { BackendApiService.getTaskById(remoteSubmission.taskId).getOrNull() }
                            val remoteTopic = if (remoteTask != null) withContext(Dispatchers.IO) { BackendApiService.getTopicById(remoteTask.topicId).getOrNull() } else null
                            val remoteCourse = if (remoteTopic != null) withContext(Dispatchers.IO) { BackendApiService.getCourseById(remoteTopic.courseId).getOrNull() } else null

                            if (remoteTask != null && remoteTopic != null && remoteCourse != null) {
                                taskName = remoteTask.name
                                taskDescription = remoteTask.description ?: "Sin descripción"
                                topicName = remoteTopic.name ?: ""
                                courseTitle = remoteCourse.title ?: ""
                                deliveryDate = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(remoteSubmission.submissionDate)
                                courseId = remoteTopic.courseId

                                Log.i("ChatBotFragment", "Loaded task info from BackendApiService for submissionId=$submissionId: $taskName - $topicName - $courseTitle")

                                val taskInfoForAdapter = ChatMessageAdapter.TaskInfo(
                                    taskName = taskName,
                                    taskDescription = taskDescription,
                                    topicName = topicName,
                                    courseTitle = courseTitle,
                                    deliveryDate = deliveryDate
                                )
                                chatAdapter.updateTaskInfo(taskInfoForAdapter)
                            } else {
                                Log.w("ChatBotFragment", "BackendApiService returned incomplete task/topic/course data for submissionId=$submissionId")
                            }
                        } else {
                            Log.w("ChatBotFragment", "No se pudo cargar la información de la tarea para submissionId: $submissionId")
                        }
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "Error cargando información de la tarea desde BackendApiService", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error cargando información de la tarea", e)
            }
        }
    }

    /**
     * Carga la información de la tarea directamente por ID (cuando no hay submission)
     */
    private fun updateTaskInfoByTaskId(taskId: Long) {
        lifecycleScope.launch {
            try {
                val taskInfo = withContext(Dispatchers.IO) {
                    val task = BackendApiService.getTaskById(taskId).getOrNull()
                    if (task != null) {
                        val topic = BackendApiService.getTopicById(task.topicId).getOrNull()
                        if (topic != null) {
                            val course = BackendApiService.getCourseById(topic.courseId).getOrNull()
                            if (course != null) {
                                mapOf(
                                    "taskName" to task.name,
                                    "taskDescription" to (task.description ?: "Sin descripción"),
                                    "topicName" to topic.name,
                                    "courseTitle" to course.title,
                                    "deliveryDate" to "Sin entrega",
                                    "courseId" to topic.courseId
                                )
                            } else null
                        } else null
                    } else null
                }

                if (taskInfo != null) {
                    taskName = taskInfo["taskName"] as String
                    taskDescription = taskInfo["taskDescription"] as String
                    topicName = taskInfo["topicName"] as String
                    courseTitle = taskInfo["courseTitle"] as String
                    deliveryDate = taskInfo["deliveryDate"] as String
                    courseId = taskInfo["courseId"] as Long

                    Log.d("ChatBotFragment", "Información de tarea cargada (sin submission): $taskName")

                    val taskInfoForAdapter = ChatMessageAdapter.TaskInfo(
                        taskName = taskName,
                        taskDescription = taskDescription,
                        topicName = topicName,
                        courseTitle = courseTitle,
                        deliveryDate = deliveryDate
                    )
                    chatAdapter.updateTaskInfo(taskInfoForAdapter)
                }
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error cargando información de la tarea por ID", e)
            }
        }
    }

    // Aplica color azul a referencias de tarea marcadas con #<n>
    private fun applyHashtagHighlighting() {
        if (!this::messageEditText.isInitialized) return
        if (isUpdatingTextSpans) return
        val editable = messageEditText.text ?: return
        try {
            isUpdatingTextSpans = true
            // Limpiar spans previos
            editable.getSpans(0, editable.length, ForegroundColorSpan::class.java).forEach { span ->
                editable.removeSpan(span)
            }

            val pattern = Regex("#(\\d+)")
            val blue = try {
                ContextCompat.getColor(requireContext(), R.color.purple_500)
            } catch (_: Exception) {
                android.graphics.Color.parseColor("#1E88E5")
            }
            val matches = pattern.findAll(editable)
            for (m in matches) {
                val start = m.range.first
                val end = m.range.last + 1
                editable.setSpan(
                    ForegroundColorSpan(blue),
                    start,
                    end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        } finally {
            isUpdatingTextSpans = false
        }
    }

    /**
     * Agrega una tarea a la lista de tareas calificadas en memoria
     */
    private fun addToGradedTasksList(message: ChatMessage, grade: String?, feedback: String?) {
        lifecycleScope.launch {
            try {
                currentFileContext?.let { fileContext ->
                    val taskSubmission = withContext(Dispatchers.IO) {
                        BackendApiService.getSubmissionById(fileContext.submissionId).getOrNull()
                    }

                    taskSubmission?.let { submission ->
                        val task = withContext(Dispatchers.IO) {
                            BackendApiService.getTaskById(submission.taskId).getOrNull()
                        }

                        val topic = task?.let { t ->
                            withContext(Dispatchers.IO) {
                                BackendApiService.getTopicById(t.topicId).getOrNull()
                            }
                        }

                        if (task != null) {
                            val finalGrade = grade ?: message.calificationValue ?: "N/A"
                            val finalFeedback = feedback ?: message.message

                            val gradedTask = GradedTaskItem(
                                taskId = task.id,
                                taskName = task.name,
                                taskDescription = task.description ?: "Sin descripción",
                                topicName = topic?.name ?: "Sin tema",
                                index = submission.id.toInt(), // USAR EL ID DE LA SUBMISSION EN LUGAR DE gradedTasksList.size + 1
                                grade = finalGrade,
                                feedback = finalFeedback
                            )

                            // Verificar que no esté ya en la lista usando submission ID como identificador único
                            // Esto evita duplicados cuando se modifica la calificación de una tarea
                            val existingTaskIndex = gradedTasksList.indexOfFirst {
                                it.taskId == gradedTask.taskId
                            }

                            if (existingTaskIndex == -1) {
                                // Nueva tarea, agregar a la lista
                                gradedTasksList.add(gradedTask)
                                Log.d("ChatBotFragment", "Nueva tarea agregada a lista calificadas: ${task.name} - Grade: $finalGrade - SubmissionId: ${submission.id}")
                            } else {
                                // Tarea existente, actualizar con nueva calificación
                                gradedTasksList[existingTaskIndex] = gradedTask
                                Log.d("ChatBotFragment", "Tarea actualizada en lista calificadas: ${task.name} - Grade: $finalGrade - SubmissionId: ${submission.id}")
                            }

                            // Actualizar el adapter con la lista completa
                            gradedTaskOverlayAdapter.updateGradedTasks(gradedTasksList.toList())
                            Log.d("ChatBotFragment", "Total tareas calificadas en memoria: ${gradedTasksList.size}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error agregando a lista de tareas calificadas: ${e.message}")
            }
        }
    }

    /**
     * Se ejecuta cuando cambia la posición del cursor
     */
    private fun onCursorPositionChanged() {
        if (!::messageEditText.isInitialized) return

        val text = messageEditText.text?.toString() ?: ""
        val cursorPosition = messageEditText.selectionStart

        // Primero verificar si hay una referencia completa a tarea calificada (#<número>)
        val gradedTaskNumber = checkForGradedTaskReference(text, cursorPosition)
        if (gradedTaskNumber != null) {
            Log.d("ChatBotFragment", "Referencia a tarea calificada detectada: #$gradedTaskNumber")
            handleGradedTaskReference(gradedTaskNumber)
            return
        }

        val shouldShowTaskList = checkCursorAfterHashtag(text, cursorPosition)

        // Debug logging (comentar en producción)
        // Log.d("ChatBotFragment", "Cursor position: $cursorPosition, Text: '$text', Should show: $shouldShowTaskList")

        if (shouldShowTaskList) {
            val query = extractHashtagContent(text, cursorPosition)
            if (taskListOverlay.visibility != View.VISIBLE) {
                Log.d("ChatBotFragment", "Mostrando lista de tareas con filtro: '$query'")
                showTaskListOverlay(query)
            } else {
                Log.d("ChatBotFragment", "Filtrando lista de tareas: '$query'")
                filterTaskList(query)
            }
        } else if (taskListOverlay.visibility == View.VISIBLE) {
            Log.d("ChatBotFragment", "Ocultando lista de tareas")
            hideTaskListOverlay()
        }
    }

    private fun extractHashtagContent(text: String, cursorPosition: Int): String {
        var start = cursorPosition - 1
        while (start >= 0) {
            if (text[start] == '#') return text.substring(start + 1, cursorPosition)
            if (!text[start].isLetterOrDigit() && text[start] != '_') break
            start--
        }
        return ""
    }

    private fun filterTaskList(query: String) {
        val filtered = if (query.isEmpty()) {
            currentCourseTasks.toList()
        } else {
            currentCourseTasks.filter {
                it.taskName.contains(query, ignoreCase = true) ||
                        it.index.toString().contains(query)
            }
        }
        taskOverlayAdapter.updateTasks(filtered)
    }

    /**
     * Verifica si el cursor está posicionado justo después de un símbolo '#'
     * @param text El texto completo del EditText
     * @param cursorPosition La posición actual del cursor
     * @return true si el cursor está justo después de un '#'
     */
    private fun checkCursorAfterHashtag(text: String, cursorPosition: Int): Boolean {
        if (text.isEmpty() || cursorPosition <= 0) return false

        // Caso 1: El cursor está justo después de un '#' recién escrito
        if (cursorPosition > 0 && text[cursorPosition - 1] == '#') {
            return true
        }

        // Caso 2: El cursor está en medio o al final de una referencia existente
        if (cursorPosition > 1) {
            // Buscar hacia atrás para encontrar un '#'
            var position = cursorPosition - 1
            var foundContent = false

            // Retroceder mientras encontremos caracteres válidos (letras o dígitos)
            while (position >= 0 && (text[position].isLetterOrDigit() || text[position] == '_')) {
                foundContent = true
                position--
            }

            // Si encontramos contenido y el carácter anterior es '#'
            if (foundContent && position >= 0 && text[position] == '#') {
                // Verificar que estamos dentro o al final de esta referencia
                val hashtagStart = position
                val hashtagEnd = hashtagStart + 1

                // Encontrar el final de la referencia
                var referenceEnd = hashtagEnd
                while (referenceEnd < text.length && (text[referenceEnd].isLetterOrDigit() || text[referenceEnd] == '_')) {
                    referenceEnd++
                }

                // El cursor debe estar entre el '#' y el final de la referencia (inclusive)
                return cursorPosition >= hashtagEnd && cursorPosition <= referenceEnd
            }
        }

        return false
    }

    /**
     * Muestra el FAB de scroll con animación suave
     */
    private fun showScrollToBottomFab() {
        if (fabScrollToBottom.visibility != View.VISIBLE) {
            fabScrollToBottom.visibility = View.VISIBLE
            fabScrollToBottom.alpha = 0f
            fabScrollToBottom.scaleX = 0.5f
            fabScrollToBottom.scaleY = 0.5f

            fabScrollToBottom.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    /**
     * Oculta el FAB de scroll con animación suave
     */
    private fun hideScrollToBottomFab() {
        if (fabScrollToBottom.visibility == View.VISIBLE) {
            fabScrollToBottom.animate()
                .alpha(0f)
                .scaleX(0.5f)
                .scaleY(0.5f)
                .setDuration(150)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    fabScrollToBottom.visibility = View.GONE
                }
                .start()
        }
    }

    /**
     * Hace scroll suave hasta el último mensaje
     */
    private fun scrollToBottomSmooth() {
        if (chatAdapter.itemCount > 0) {
            // Animación de pulso en el FAB
            fabScrollToBottom.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .setDuration(100)
                .withEndAction {
                    fabScrollToBottom.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                }
                .start()

            // Scroll suave al último mensaje
            messagesRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)

            // Ocultar el FAB después de hacer scroll
            messagesRecyclerView.postDelayed({
                hideScrollToBottomFab()
            }, 400)
        }
    }

    /**
     * Verifica si hay una referencia completa a una tarea calificada (#<número>)
     * @param text El texto completo del EditText
     * @param cursorPosition La posición actual del cursor
     * @return El número de la tarea si se encuentra una referencia completa, null en caso contrario
     */
    private fun checkForGradedTaskReference(text: String, cursorPosition: Int): Int? {
        if (text.isEmpty()) return null

        // Buscar patrones #<número> en el texto
        val pattern = Regex("#(\\d+)")
        val matches = pattern.findAll(text)

        for (match in matches) {
            val matchStart = match.range.first
            val matchEnd = match.range.last + 1

            // Verificar si el cursor está al final de esta referencia o 
            // si acabamos de escribir el último dígito
            if (cursorPosition == matchEnd ||
                (cursorPosition == matchEnd && cursorPosition > 0 && text[cursorPosition - 1].isDigit())) {

                val number = match.groupValues[1].toIntOrNull()
                if (number != null) {
                    Log.d("ChatBotFragment", "Referencia completa encontrada: #$number en posición $matchStart-$matchEnd, cursor en $cursorPosition")

                    // Verificar que no hay más dígitos después (para evitar activar en #123 cuando escribimos #1234)
                    val nextCharPosition = matchEnd
                    if (nextCharPosition >= text.length || !text[nextCharPosition].isDigit()) {
                        return number
                    }
                }
            }
        }

        return null
    }

    /**
     * Maneja la referencia a una tarea calificada específica
     * @param taskNumber El número de la tarea calificada referenciada (ahora es el ID de TaskSubmission)
     */
    private fun handleGradedTaskReference(taskNumber: Int) {
        lifecycleScope.launch {
            try {
                // Si la lista en memoria está vacía, cargar desde la base de datos
                if (gradedTasksList.isEmpty()) {
                    val loadResult = loadGradedTasksFromChat()
                    gradedTasksList.clear()
                    gradedTasksList.addAll(loadResult.items)
                }

                // Buscar la tarea calificada por su ID de submission (ahora es index)
                val gradedTask = gradedTasksList.find { it.index == taskNumber }

                if (gradedTask != null) {
                    Log.d("ChatBotFragment", "Tarea calificada encontrada: ${gradedTask.taskName} (submission ID: ${gradedTask.index})")

                    // Cargar el FileContext de esta submission
                    val submissionId = taskNumber.toLong()
                    val fileContext = withContext(Dispatchers.IO) {
                        BackendApiService
                            .getFileContextsBySubmission(submissionId)
                            .getOrNull()
                            ?.firstOrNull()
                            ?.let(::sanitizeFileContext)
                    }

                    if (fileContext != null) {
                        // Establecer este contexto como el contexto actual
                        currentFileContext = fileContext

                        // Actualizar información del curso
                        updateCourseInfo(submissionId)

                        Log.d("ChatBotFragment", "Contexto de archivo cargado para tarea referenciada:")
                        Log.d("ChatBotFragment", "- FileName: ${fileContext.fileName}")
                        Log.d("ChatBotFragment", "- ContentSummary length: ${fileContext.contentSummary?.length ?: 0}")
                        Log.d("ChatBotFragment", "- FileContent length: ${(fileContext.fileContent ?: "").length}")

                        // Mostrar mensaje informativo sobre el contexto cargado
                        val contextMessage = ChatMessage(
                            message = "🔗 **Contexto de tarea referenciada cargado**\n\n" +
                                    "📝 Tarea: ${gradedTask.taskName}\n" +
                                    "📁 Archivo: ${fileContext.fileName}\n" +
                                    "📊 Calificación: ${gradedTask.grade}\n" +
                                    "🔧 Tipo: ${fileContext.fileType}\n" +
                                    "📊 Contenido: ${(fileContext.fileContent ?: "").length} caracteres\n\n" +
                                    "✅ Contexto actualizado. Puedes hacer preguntas sobre esta entrega.",
                            isFromUser = false,
                            sessionId = sessionId,
                            timestamp = System.currentTimeMillis()
                            , senderUsername = "DeepSeek",
                            senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                        )

                        // Insertar mensaje en la base de datos
                        withContext(Dispatchers.IO) {
                            database.chatMessageDao().insertMessage(contextMessage)
                        }
                    } else {
                        Log.w("ChatBotFragment", "No se encontró FileContext para submission ID: $submissionId")
                    }

                    // Reemplazar la referencia #<número> con la información completa de la tarea
                    val currentText = messageEditText.text?.toString() ?: ""
                    val pattern = Regex("#$taskNumber\\b")
                    val taskContext = "#${gradedTask.index} ${gradedTask.taskName} (${gradedTask.grade})"
                    val newText = pattern.replace(currentText, taskContext)

                    messageEditText.setText(newText)
                    messageEditText.setSelection(newText.length)

                    Toast.makeText(context, "Tarea calificada #$taskNumber cargada: ${gradedTask.taskName}", Toast.LENGTH_SHORT).show()
                } else {
                    Log.d("ChatBotFragment", "No se encontró tarea calificada con submission ID #$taskNumber")

                    // Si no existe, mostrar el overlay para que el usuario pueda ver las tareas disponibles
                    if (gradedTasksList.isNotEmpty()) {
                        showGradedTasksOverlay()
                        Toast.makeText(context, "Tarea #$taskNumber no encontrada. Mostrando tareas disponibles.", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "No hay tareas calificadas disponibles", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error al manejar referencia a tarea calificada", e)
                Toast.makeText(context, "Error al buscar tarea calificada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 📧📱 Notifica al ESTUDIANTE cuando recibe una calificación en su tarea.
     *
     * IMPORTANTE: Esta notificación va al ESTUDIANTE (dueño de la entrega),
     * NO al creador del curso.
     *
     * Ejemplo: Si usuario 5 le pone un 8 a usuario 6 en su tarea,
     * la notificación le llega al usuario 6 (el estudiante).
     *
     * @param studentId ID del estudiante que recibirá la notificación
     * @param taskName Nombre de la tarea calificada
     * @param grade Calificación numérica (0-10)
     * @param feedback Retroalimentación del profesor
     * @param gradedByUsername Nombre del usuario que calificó
     */
    // Notificación de calificación deshabilitada desde la UI de chat.

    // ============================================================================================
    // VOICE RECOGNITION LOGIC
    // ============================================================================================

    private fun checkAudioPermissionAndStart() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (isListening) return

        try {
            // Hide keyboard
            val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.hideSoftInputFromWindow(messageEditText.windowToken, 0)

            // Toggle UI
            messageInputContainer.visibility = View.GONE
            voiceInputLayout.visibility = View.VISIBLE

            if (speechRecognizer == null) {
                createSpeechRecognizer()
            }

            voiceIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            speechRecognizer?.startListening(voiceIntent)
            isListening = true

            voiceStatusText.text = "Escuchando..."
            voiceVisualizer.startAnimating()

        } catch (e: Exception) {
            Toast.makeText(context, "Error al iniciar voz: ${e.message}", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
            // Restore UI on error
            messageInputContainer.visibility = View.VISIBLE
            voiceInputLayout.visibility = View.GONE
        }
    }

    private fun stopListening() {
        if (!isListening) {
            // Ensure UI is reset even if we weren't "listening" but UI was showing
            if (voiceInputLayout.visibility == View.VISIBLE) {
                voiceInputLayout.visibility = View.GONE
                messageInputContainer.visibility = View.VISIBLE
            }
            return
        }

        try {
            speechRecognizer?.stopListening()
            isListening = false
            voiceVisualizer.stopAnimating()

            // Restore UI
            voiceInputLayout.visibility = View.GONE
            messageInputContainer.visibility = View.VISIBLE

            // Focus input
            messageEditText.requestFocus()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d("ChatBotFragment", "Voice: onReadyForSpeech")
            }

            override fun onBeginningOfSpeech() {
                Log.d("ChatBotFragment", "Voice: onBeginningOfSpeech")
                voiceStatusText.text = "Te escucho..."
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Update visualizer
                voiceVisualizer.updateAmplitude(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d("ChatBotFragment", "Voice: onEndOfSpeech")
                voiceStatusText.text = "Procesando..."
            }

            override fun onError(error: Int) {
                Log.e("ChatBotFragment", "Voice error: $error")
                isListening = false
                voiceVisualizer.stopAnimating()

                // Hide overlay and restore input
                voiceInputLayout.visibility = View.GONE
                messageInputContainer.visibility = View.VISIBLE

                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No te entendí"
                    SpeechRecognizer.ERROR_NETWORK -> "Error de conexión"
                    SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
                    else -> "Error de voz ($error)"
                }

                if (error != SpeechRecognizer.ERROR_CLIENT) {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                Log.d("ChatBotFragment", "Voice: onResults")
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    messageEditText.setText(text)
                    messageEditText.setSelection(text.length)
                }

                // Hide overlay and restore input
                voiceInputLayout.visibility = View.GONE
                messageInputContainer.visibility = View.VISIBLE

                isListening = false
                voiceVisualizer.stopAnimating()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    voiceStatusText.text = matches[0]
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }
}