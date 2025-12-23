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
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.ChatMessage
import com.example.tareamov.data.entity.FileContext
import com.example.tareamov.data.entity.TaskSubmission

import com.example.tareamov.service.FileAnalysisService
import com.example.tareamov.ui.adapter.ChatMessageAdapter
import com.example.tareamov.adapter.TaskOverlayAdapter
import com.example.tareamov.adapter.TaskItem
import com.example.tareamov.adapter.GradedTaskOverlayAdapter
import com.example.tareamov.adapter.GradedTaskItem
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

class ChatBotFragment : Fragment() {
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

    private lateinit var chatAdapter: ChatMessageAdapter
    private lateinit var database: AppDatabase

    private lateinit var fileAnalysisService: FileAnalysisService
    private lateinit var sessionManager: com.example.tareamov.util.SessionManager
    // Listener instance so we can remove it in onDestroyView
    private var sessionChangeListener: com.example.tareamov.util.SessionManager.UserChangeListener? = null
    private lateinit var syncRepository: com.example.tareamov.data.sync.SyncRepository

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
        // Use Railway Cloud in production
        return "https://mcp-backenddeploy-production.up.railway.app/"
    }

    private fun getOllamaUrl(): String {
        // Ollama is now replaced by DeepSeek in the cloud backend
        return "https://mcp-backenddeploy-production.up.railway.app"
    }
    // Aumentar los timeouts para evitar que el chat cierre la espera antes de que el modelo responda
    private val microservicioApi: MicroservicioApi by lazy {
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .addInterceptor { chain ->
                // Agregar el header X-API-Key a todas las peticiones al microservicio
                val originalRequest = chain.request()
                val requestWithApiKey = originalRequest.newBuilder()
                    .header("X-API-Key", "tareamov-mcp-api-key-2025-secure")
                    .header("Content-Type", "application/json")
                    .header("Connection", "close") // Forzar cierre de conexión para evitar hangs
                    .build()
                chain.proceed(requestWithApiKey)
            }
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(getMicroserviceBaseUrl())
            .addConverterFactory(GsonConverterFactory.create())
            .client(okHttpClient)
            .build()
        retrofit.create(MicroservicioApi::class.java)
    }

    private val sessionId = UUID.randomUUID().toString()
    private var currentFileContext: FileContext? = null
    
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
    
    private var isUpdatingTextSpans: Boolean = false

    /**
     * Analiza la entrega y distribuye la carga entre modelos según el tipo de pregunta.
     * Si la pregunta es sobre nota/calificación/tarea/feedback, gemma3n da veredicto y nota, llama3 da feedback.
     * Si no, llama3 responde directamente.
     */
    private suspend fun analizarEntregaYFeedback(userMessage: String, fileContext: FileContext?): String {
        val taskDescription = fileContext?.contentSummary ?: ""
        val fileContent = fileContext?.fileContent ?: ""
        val preguntaLower = userMessage.lowercase()
        val esPreguntaNota = preguntaLower.contains("nota") || preguntaLower.contains("calificación") || preguntaLower.contains("feedback") || preguntaLower.contains("tarea")
        val ollamaUrl = getOllamaUrl()
        return try {
            // Obtener el ID del usuario autenticado para notificaciones
            val currentUserId = sessionManager.getUserId()
            
            if (!esPreguntaNota) {
                // Solo llama3 responde
                val request = com.example.tareamov.network.MicroservicioPromptRequest(
                    prompt = userMessage,
                    ollamaUrl = ollamaUrl,
                    taskDescription = taskDescription,
                    fileContent = fileContent,
                    userId = currentUserId
                )
                val response = microservicioApi.procesarPrompt(request)
                response.respuesta_texto ?: "No se pudo obtener respuesta."
            } else {
                // gemma3n analiza y da veredicto, llama3 da feedback
                val request = com.example.tareamov.network.MicroservicioPromptRequest(
                    prompt = userMessage,
                    ollamaUrl = ollamaUrl,
                    taskDescription = taskDescription,
                    fileContent = fileContent,
                    userId = currentUserId
                )
                val response = microservicioApi.procesarPrompt(request)
                response.respuesta_texto ?: "No se pudo obtener respuesta."
            }
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

        database = AppDatabase.getDatabase(requireContext())

        fileAnalysisService = FileAnalysisService(requireContext())
        sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        syncRepository = com.example.tareamov.data.sync.SyncRepository(
            usuarioDao = database.usuarioDao(),
            personaDao = database.personaDao(),
            topicDao = database.topicDao(),
            contentItemDao = database.contentItemDao(),
            taskDao = database.taskDao(),
            subscriptionDao = database.subscriptionDao(),
            taskSubmissionDao = database.taskSubmissionDao(),
            videoDao = database.videoDao(),
            courseDao = database.courseDao(),
            rolDao = database.rolDao(),
            recursoDao = database.recursoDao(),
            rolRecursoDao = database.rolRecursoDao(),
            chatMessageDao = database.chatMessageDao(),
            fileContextDao = database.fileContextDao(),
            progresoEstudianteDao = database.progresoEstudianteDao()
        )
        syncRepository.initWithContext(requireContext())

        initializeViews(view)
        setupRecyclerView()
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
    }

    private fun initializeViews(view: View) {
        // Handle TopBar Insets for status bar only - with minimal padding
        val topBar = view.findViewById<LinearLayout>(R.id.topBar)
        
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { v, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            // Solo agregar el padding de la barra de estado, sin padding adicional
            v.setPadding(v.paddingLeft, statusBars.top, v.paddingRight, v.paddingBottom)
            insets
        }
        
        // Forzar que el topBar siempre esté arriba incluso durante animaciones
        topBar.post {
            topBar.bringToFront()
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

        database = AppDatabase.getDatabase(requireContext())
    }

    private fun loadFileContextFromArguments() {
        arguments?.let { args ->
            val submissionId = args.getLong("submissionId", -1L)
            val errorMessage = args.getString("errorMessage")
            val fileName = args.getString("fileName")
            
            // LOGGING DETALLADO PARA DEBUGGING
            Log.d("ChatBotFragment", "==============================================")
            Log.d("ChatBotFragment", "📋 LOADING FILE CONTEXT FROM ARGUMENTS:")
            Log.d("ChatBotFragment", "==============================================")
            Log.d("ChatBotFragment", "submissionId recibido: $submissionId")
            Log.d("ChatBotFragment", "errorMessage: $errorMessage")
            Log.d("ChatBotFragment", "fileName: $fileName")
            Log.d("ChatBotFragment", "==============================================")

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
                    val errorChatMessage = ChatMessage(
                        message = "⚠️ **Error con el archivo**\n\n" +
                                "📁 Archivo: ${fileName ?: "desconocido"}\n" +
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
                        try {
                            val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                            val toSend = com.example.tareamov.data.entity.ChatMessage(
                                id = savedId,
                                message = errorChatMessage.message,
                                isFromUser = errorChatMessage.isFromUser,
                                timestamp = errorChatMessage.timestamp,
                                sessionId = errorChatMessage.sessionId,
                                hasCalification = errorChatMessage.hasCalification,
                                calificationValue = errorChatMessage.calificationValue,
                                calificationAdded = errorChatMessage.calificationAdded
                                , senderUsername = errorChatMessage.senderUsername,
                                senderAvatar = errorChatMessage.senderAvatar
                            )
                            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                val ok = supabaseRepo.upsert("chat_messages", toSend)
                                if (ok) Log.i("ChatBotFragment", "ChatMessage $savedId upserted to Supabase.")
                                else Log.w("ChatBotFragment", "Failed to upsert ChatMessage $savedId to Supabase.")
                            }
                        } catch (e: Exception) {
                            Log.w("ChatBotFragment", "Exception sending chat message to Supabase: ${e.message}")
                        }
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
            // Try local DB first
            currentFileContext = withContext(Dispatchers.IO) {
                database.fileContextDao().getFileContextBySubmission(submissionId)
            }

            // If local file context missing, attempt to fetch from Supabase (remote) and use it
            if (currentFileContext == null) {
                try {
                    val supabaseClient = com.example.tareamov.service.SupabaseClient
                    if (supabaseClient.isConfigured()) {
                        Log.d("ChatBotFragment", "currentFileContext missing locally, attempting Supabase fetch for submissionId=$submissionId")
                        val remoteFcs = withContext(Dispatchers.IO) { supabaseClient.fetchFileContexts() }
                        val remoteFc = remoteFcs.firstOrNull { it.submissionId == submissionId }
                        if (remoteFc != null) {
                            Log.i("ChatBotFragment", "Found remote FileContext for submissionId=$submissionId via Supabase")
                            currentFileContext = remoteFc
                        } else {
                            Log.w("ChatBotFragment", "No remote FileContext found in Supabase for submissionId=$submissionId")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("ChatBotFragment", "Exception fetching FileContext from Supabase for submissionId=$submissionId: ${e.message}")
                }
            }
            
            // Cargar información de la tarea, tema y curso
            updateCourseInfo(submissionId)
            
            // Logging mínimo
            Log.d("ChatBotFragment", "🔍 FileContext cargado - submissionId: $submissionId, presente: ${currentFileContext != null}")
            if (currentFileContext != null) {
                Log.d("ChatBotFragment", "   - fileName: '${currentFileContext!!.fileName}', fileType: '${currentFileContext!!.fileType}'")
            }

            if (currentFileContext != null) {
                // Verificar si es un error específico de Google Drive
                val isGoogleDriveError = currentFileContext!!.fileType == "google_drive_error"

                // Mostrar mensaje inicial con contexto del archivo
                val contextMessage = if (isGoogleDriveError) {
                    ChatMessage(
                        message = "📱 **Archivo de Google Drive detectado**\n\n" +
                                "📄 Nombre: ${currentFileContext!!.fileName}\n" +
                                "⚠️ **No se puede acceder directamente a este archivo**\n\n" +
                                "Para poder analizar este archivo, necesitas:\n" +
                                "1. Abrir Google Drive\n" +
                                "2. Descargar el archivo a tu dispositivo\n" +
                                "3. Volver a subir el archivo desde tu almacenamiento local\n\n" +
                                "Mientras tanto, puedo ayudarte con preguntas generales.",
                        isFromUser = false,
                        sessionId = sessionId
                    )
                } else if (hasError) {
                    ChatMessage(
                        message = "📄 **Archivo parcialmente accesible**\n\n" +
                                "📁 Nombre: ${currentFileContext!!.fileName}\n" +
                                "🔧 Tipo: ${currentFileContext!!.fileType}\n" +
                                "⚠️ El archivo tiene problemas de acceso, pero intentaré ayudarte con la información disponible.\n\n" +
                                "Puedes hacerme preguntas y haré lo mejor posible con los datos limitados.",
                        isFromUser = false,
                        sessionId = sessionId
                    )
                } else {
                    // Mostrar solo el nombre del archivo sin el contenido
                    ChatMessage(
                        message = "📁 **Archivo cargado:** ${currentFileContext!!.fileName}\n\n" +
                                "✅ Puedes hacerme preguntas sobre este archivo.",
                        isFromUser = false,
                        sessionId = sessionId
                    )
                }

                withContext(Dispatchers.IO) {
                    val savedIdCtx = database.chatMessageDao().insertMessage(contextMessage)
                    try {
                        val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                        val toSend = contextMessage.copy(id = savedIdCtx, senderUsername = contextMessage.senderUsername, senderAvatar = contextMessage.senderAvatar)
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val ok = supabaseRepo.upsert("chat_messages", toSend)
                            if (ok) Log.i("ChatBotFragment", "ChatMessage $savedIdCtx upserted to Supabase.")
                            else Log.w("ChatBotFragment", "Failed to upsert ChatMessage $savedIdCtx to Supabase.")
                        }
                    } catch (e: Exception) {
                        Log.w("ChatBotFragment", "Exception sending chat context to Supabase: ${e.message}")
                    }
                }
            }
        }
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatMessageAdapter(
            onAddCalificationClick = { message ->
                handleAddCalification(message)
            },
            onRejectCalificationClick = { message ->
                handleRejectCalification(message)
            },
            onEditUserMessageClick = { message ->
                handleEditUserMessage(message)
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
     * 1. gemma3n:latest: analiza si la tarea cumple con la descripción (veredicto).
     * 2. llama3:latest: califica (1-10) y da retroalimentación al usuario en base al veredicto.
     * El cliente solo envía el mensaje del usuario y el contenido completo del archivo (descripcionTarea).
     */
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
                senderAvatar = sessionManager.getUserAvatar()
            )
            withContext(Dispatchers.IO) {
                val savedUserId = database.chatMessageDao().insertMessage(userMessage)
                try {
                    val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                    val toSend = userMessage.copy(
                        id = savedUserId,
                        senderUsername = userMessage.senderUsername,
                        senderAvatar = userMessage.senderAvatar
                    )
                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val ok = supabaseRepo.upsert("chat_messages", toSend)
                        if (ok) Log.i("ChatBotFragment", "ChatMessage $savedUserId upserted to Supabase.")
                        else Log.w("ChatBotFragment", "Failed to upsert ChatMessage $savedUserId to Supabase.")
                    }
                } catch (e: Exception) {
                    Log.w("ChatBotFragment", "Exception sending user chat to Supabase: ${e.message}")
                }
            }
            loadingProgressBar.visibility = View.VISIBLE
            
            // Contexto por defecto: la tarea/archivo actualmente cargado en el chat
            var effectiveTaskDescription = currentFileContext?.contentSummary ?: ""
            var effectiveFileContent = currentFileContext?.fileContent ?: ""
            var effectiveJsonContent = currentFileContext?.jsonContent ?: ""
            var effectiveMetadata = currentFileContext?.metadata ?: ""
            
            // 🔥 CRÍTICO: Si el mensaje contiene # y taskName está establecido, esperar a que el contexto termine de cargar
            // Detectar si hay una referencia a tarea pendiente de procesar
            val hasTaskReference = messageText.contains("#") && taskName.isNotEmpty()
            if (hasTaskReference) {
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
                    effectiveFileContent = currentFileContext!!.fileContent
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
            
            // FALLBACK: Si taskDescription está vacío, intentar obtener el último contentSummary desde Supabase
            if (effectiveTaskDescription.isEmpty()) {
                Log.d("ChatBotFragment", "🔄 taskDescription vacío, intentando fallback con último contentSummary desde Supabase")
                effectiveTaskDescription = withContext(Dispatchers.IO) {
                    try {
                        // Preferir SupabaseClient when configured
                        val supabaseClient = com.example.tareamov.service.SupabaseClient
                        if (supabaseClient.isConfigured()) {
                            val remoteFileContexts = supabaseClient.fetchFileContexts()
                            val latest = remoteFileContexts.maxByOrNull { it.submissionId ?: 0L }
                            val summary = latest?.contentSummary
                            Log.d("ChatBotFragment", "📋 Fallback: contentSummary obtenido desde Supabase")
                            summary ?: ""
                        } else {
                            val latestContentSummary = database.fileContextDao().getLatestContentSummary()
                            Log.d("ChatBotFragment", "📋 Fallback: contentSummary obtenido desde local")
                            latestContentSummary ?: ""
                        }
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
                effectiveFileContent = currentFileContext!!.fileContent
                
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
                    if (currentFileContext!!.fileName.isNotBlank()) {
                        append("\n📎 ARCHIVO ENVIADO POR EL ESTUDIANTE: ${currentFileContext!!.fileName}")
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

                    // 🔥 LOGIC TO CONTROL GRADING VS Q&A
                    val lowerMessage = messageText.lowercase()
                    val isGradingRequest = lowerMessage.contains("calific") || 
                                           lowerMessage.contains("nota") || 
                                           lowerMessage.contains("evalu") || 
                                           lowerMessage.contains("puntaje") ||
                                           lowerMessage.contains("grade") ||
                                           lowerMessage.contains("score") ||
                                           lowerMessage.contains("rate")

                    if (isGradingRequest) {
                            append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                        } else {
                            append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                        }
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

                    // 🔥 LOGIC TO CONTROL GRADING VS Q&A
                    val lowerMessage = messageText.lowercase()
                    val isGradingRequest = lowerMessage.contains("calific") || 
                                           lowerMessage.contains("nota") || 
                                           lowerMessage.contains("evalu") || 
                                           lowerMessage.contains("puntaje") ||
                                           lowerMessage.contains("grade") ||
                                           lowerMessage.contains("score") ||
                                           lowerMessage.contains("rate")

                    if (isGradingRequest) {
                        append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                    } else {
                        append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                    }
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
                                val supabaseClient = com.example.tareamov.service.SupabaseClient
                                
                                // 1. Buscar tarea (local o remota)
                                var task = database.taskDao().getTaskByNameAndCourse(taskName, courseId)
                                
                                if (task == null && supabaseClient.isConfigured()) {
                                     try {
                                         val remoteTopics = supabaseClient.fetchTopicsByCourse(courseId)
                                         if (remoteTopics.isNotEmpty()) {
                                             val remoteTasks = supabaseClient.fetchTasksByTopicIds(remoteTopics.map { it.id })
                                             task = remoteTasks.firstOrNull { it.name == taskName }
                                         }
                                     } catch (e: Exception) {
                                         Log.w("ChatBotFragment", "Error buscando tarea remota: ${e.message}")
                                     }
                                }

                                if (task != null) {
                                    val finalTask = task
                                    // 2. Buscar submission (local o remota)
                                    val localSubmission = database.taskSubmissionDao().getUserSubmissionForTask(finalTask.id, userId)
                                    
                                    val submission = if (localSubmission == null && supabaseClient.isConfigured()) {
                                        try {
                                            val remoteSubs = supabaseClient.fetchTaskSubmissions()
                                            remoteSubs.firstOrNull { it.taskId == finalTask.id && it.studentId == userId }
                                        } catch (e: Exception) {
                                            Log.w("ChatBotFragment", "Error buscando submission remota: ${e.message}")
                                            null
                                        }
                                    } else {
                                        localSubmission
                                    }

                                    if (submission != null) {
                                        // 3. Buscar FileContext (local o remoto)
                                        val localFc = database.fileContextDao().getFileContextBySubmission(submission.id)
                                        
                                        val fc = if (localFc == null && supabaseClient.isConfigured()) {
                                            try {
                                                val remoteFcs = supabaseClient.fetchFileContexts()
                                                remoteFcs.firstOrNull { it.submissionId == submission.id }
                                            } catch (e: Exception) {
                                                Log.w("ChatBotFragment", "Error buscando FileContext remoto: ${e.message}")
                                                null
                                            }
                                        } else {
                                            localFc
                                        }

                                        if (fc != null) {
                                            effectiveFileContent = fc.fileContent
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
                                                if (fc.fileContent.isBlank()) {
                                                    append("\n⚠️ NOTA: El contenido del archivo no está disponible para análisis detallado")
                                                }

                                                // 🔥 LOGIC TO CONTROL GRADING VS Q&A
                                                val lowerMessage = messageText.lowercase()
                                                val isGradingRequest = lowerMessage.contains("calific") || 
                                                                       lowerMessage.contains("nota") || 
                                                                       lowerMessage.contains("evalu") || 
                                                                       lowerMessage.contains("puntaje") ||
                                                                       lowerMessage.contains("grade") ||
                                                                       lowerMessage.contains("score") ||
                                                                       lowerMessage.contains("rate")

                                                if (isGradingRequest) {
                                                    append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                                                } else {
                                                    append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                                                }
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

                            // 🔥 LOGIC TO CONTROL GRADING VS Q&A
                            val lowerMessage = messageText.lowercase()
                            val isGradingRequest = lowerMessage.contains("calific") || 
                                                   lowerMessage.contains("nota") || 
                                                   lowerMessage.contains("evalu") || 
                                                   lowerMessage.contains("puntaje") ||
                                                   lowerMessage.contains("grade") ||
                                                   lowerMessage.contains("score") ||
                                                   lowerMessage.contains("rate")

                            if (isGradingRequest) {
                                append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                            } else {
                                append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                            }
                        }
                        effectiveTaskDescription = baseTaskContext

                        val username = com.example.tareamov.util.SessionManager.getInstance(requireContext()).getUsername()
                        if (!username.isNullOrEmpty()) {
                            withContext(Dispatchers.IO) {
                                try {
                                    val supabaseClient = com.example.tareamov.service.SupabaseClient
                                    val userId = com.example.tareamov.util.SessionManager.getInstance(requireContext()).getUserId()
                                    
                                    // 🔥 RESOLVER taskId REMOTO por nombre de tarea
                                    var remoteTaskId = referencedTask.taskId
                                    if (supabaseClient.isConfigured()) {
                                        try {
                                            val remoteTask = supabaseClient.fetchTaskByName(referencedTask.taskName)
                                            if (remoteTask != null) {
                                                remoteTaskId = remoteTask.id
                                                Log.d("ChatBotFragment", "✅ TaskId remoto resuelto: $remoteTaskId (local: ${referencedTask.taskId})")
                                            }
                                        } catch (e: Exception) {
                                            Log.w("ChatBotFragment", "⚠️ Error resolviendo taskId remoto: ${e.message}")
                                        }
                                    }
                                    
                                    // 🔥 BUSCAR SUBMISSION POR student_id (integer en Supabase)
                                    var submission: TaskSubmission? = null
                                    
                                    if (supabaseClient.isConfigured()) {
                                        Log.d("ChatBotFragment", "🌐 Buscando submission en Supabase por student_id=$userId y taskId=$remoteTaskId")
                                        val remoteSubmissions = supabaseClient.fetchTaskSubmissionsByTaskAndStudentId(remoteTaskId, userId)
                                        submission = remoteSubmissions.firstOrNull()
                                        Log.d("ChatBotFragment", "📊 Submissions encontradas: ${remoteSubmissions.size}")
                                    }
                                    
                                    // Fallback a búsqueda local por studentId
                                    if (submission == null) {
                                        submission = database.taskSubmissionDao().getUserSubmissionForTask(referencedTask.taskId, userId)
                                        Log.d("ChatBotFragment", "📂 Submission local: ${submission?.id}")
                                    }

                                    if (submission != null) {
                                        val currentSubmission = submission!! // Copia local inmutable
                                        Log.d("ChatBotFragment", "✅ Submission encontrada: id=${currentSubmission.id}, file='${currentSubmission.fileName}'")
                                        
                                        // Buscar FileContext
                                        var fc: FileContext? = null
                                        if (supabaseClient.isConfigured()) {
                                            fc = supabaseClient.fetchFileContextBySubmissionId(currentSubmission.id)
                                            Log.d("ChatBotFragment", "📄 FileContext de Supabase: ${fc?.fileName}")
                                        }
                                        if (fc == null) {
                                            fc = database.fileContextDao().getFileContextBySubmission(currentSubmission.id)
                                            Log.d("ChatBotFragment", "📄 FileContext local: ${fc?.fileName}")
                                        }

                                        fc?.let { currentFc ->
                                            // 🔥 CARGAR CONTENIDO DEL ARCHIVO DEL ESTUDIANTE
                                            effectiveFileContent = currentFc.fileContent
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
                                                
                                                // 🔥 LOGIC TO CONTROL GRADING VS Q&A
                                                val lowerMessage = messageText.lowercase()
                                                val isGradingRequest = lowerMessage.contains("calific") || 
                                                                       lowerMessage.contains("nota") || 
                                                                       lowerMessage.contains("evalu") || 
                                                                       lowerMessage.contains("puntaje") ||
                                                                       lowerMessage.contains("grade") ||
                                                                       lowerMessage.contains("score") ||
                                                                       lowerMessage.contains("rate")

                                                if (isGradingRequest) {
                                                    append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                                                } else {
                                                    append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                                                }
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
                        
                        // 🔥 LOGIC TO CONTROL GRADING VS Q&A
                        val lowerMessage = messageText.lowercase()
                        val isGradingRequest = lowerMessage.contains("calific") || 
                                               lowerMessage.contains("nota") || 
                                               lowerMessage.contains("evalu") || 
                                               lowerMessage.contains("puntaje") ||
                                               lowerMessage.contains("grade") ||
                                               lowerMessage.contains("score") ||
                                               lowerMessage.contains("rate")

                        if (isGradingRequest) {
                            append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario ha solicitado explícitamente una calificación. Por favor evalúa la entrega (1-10) y da retroalimentación formal basada en los requisitos.")
                        } else {
                            append("\n\n⚠️ INSTRUCCIÓN DEL SISTEMA: El usuario es un DOCENTE revisando la entrega de un estudiante. El usuario está haciendo una pregunta general o de contexto. NO proporciones una calificación numérica (1-10) ni una evaluación formal en esta respuesta. Responde a la duda del usuario de manera útil basándote en el contexto provisto.")
                        }
                    }
                    
                    effectiveTaskDescription = baseTaskContext
                    
                    // 🔥 CRÍTICO: También cargar el FileContext asociado a esta tarea
                    val userId = sessionManager.getUserId()
                    val username = sessionManager.getUsername() ?: ""
                    
                    if (userId != -1L) {
                        withContext(Dispatchers.IO) {
                            try {
                                val supabaseClient = com.example.tareamov.service.SupabaseClient
                                
                                // 🔥 RESOLVER taskId REMOTO por nombre de tarea
                                var remoteTaskId = referencedTask.taskId
                                if (supabaseClient.isConfigured()) {
                                    try {
                                        val remoteTask = supabaseClient.fetchTaskByName(referencedTask.taskName)
                                        if (remoteTask != null) {
                                            remoteTaskId = remoteTask.id
                                            Log.d("ChatBotFragment", "✅ TaskId remoto resuelto: $remoteTaskId (local: ${referencedTask.taskId})")
                                        }
                                    } catch (e: Exception) {
                                        Log.w("ChatBotFragment", "⚠️ Error resolviendo taskId remoto: ${e.message}")
                                    }
                                }
                                
                                // Buscar submission del usuario para esta tarea
                                var submission: TaskSubmission? = null
                                
                                // 🔥 PRIORIDAD: Buscar en Supabase por student_id (integer)
                                if (supabaseClient.isConfigured()) {
                                    try {
                                        Log.d("ChatBotFragment", "🌐 Buscando submission en Supabase por student_id=$userId y taskId=$remoteTaskId")
                                        val remoteSubmissions = supabaseClient.fetchTaskSubmissionsByTaskAndStudentId(remoteTaskId, userId)
                                        submission = remoteSubmissions.firstOrNull()
                                        Log.d("ChatBotFragment", "📊 Submission encontrada en Supabase para #${referencedTask.taskName}: ${submission?.id}")
                                    } catch (e: Exception) {
                                        Log.w("ChatBotFragment", "Error buscando submission en Supabase: ${e.message}")
                                    }
                                }
                                
                                // Fallback a local por studentId
                                if (submission == null) {
                                    submission = database.taskSubmissionDao().getUserSubmissionForTask(referencedTask.taskId, userId)
                                    Log.d("ChatBotFragment", "📊 Submission encontrada localmente para #${referencedTask.taskName}: ${submission?.id}")
                                }
                                
                                if (submission != null) {
                                    val currentSubmission = submission!! // Copia local inmutable
                                    // Buscar FileContext
                                    var fc: FileContext? = null
                                    
                                    if (supabaseClient.isConfigured()) {
                                        try {
                                            fc = supabaseClient.fetchFileContextBySubmissionId(currentSubmission.id)
                                            Log.d("ChatBotFragment", "📄 FileContext de Supabase: ${fc?.fileName}")
                                        } catch (e: Exception) {
                                            Log.w("ChatBotFragment", "Error buscando FileContext en Supabase: ${e.message}")
                                        }
                                    }
                                    
                                    if (fc == null) {
                                        fc = database.fileContextDao().getFileContextBySubmission(currentSubmission.id)
                                        Log.d("ChatBotFragment", "📄 FileContext local: ${fc?.fileName}")
                                    }
                                    
                                    fc?.let { currentFc ->
                                        // 🔥 CARGAR CONTENIDO DEL ARCHIVO DEL ESTUDIANTE
                                        effectiveFileContent = currentFc.fileContent
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
                                        Log.d("ChatBotFragment", "✅ FileContext cargado para #${referencedTask.taskName}: ${currentFc.fileContent.length} caracteres")
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
            data class LLMResponse(val text: String, val nota: Float?)

            try {
                val llmResponse = withContext(Dispatchers.IO) {
                    try {
                        // Obtener información de la submission para que el backend pueda buscar el contenido desde R2
                        val currentSubmissionId = currentFileContext?.submissionId
                        // 🔥 IMPORTANTE: Extraer el taskId real del contexto del archivo
                        // El submissionId en FileContext está asociado a un task_id en task_submissions
                        // Si no tenemos submissionId, podemos intentar buscar por userId
                        val currentTaskIdForRequest = currentSubmissionId // El backend usará submissionId para encontrar taskId
                        val currentStudentId = sessionManager.getUserId()
                        
                        val body = com.example.tareamov.network.MicroservicioPromptRequest(
                            prompt = messageText,
                            ollamaUrl = getOllamaUrl(),
                            // Always send strings (empty when absent) to avoid undefined on the microservice
                            taskDescription = if (effectiveTaskDescription.isNotEmpty()) effectiveTaskDescription else "",
                            fileContent = if (effectiveFileContent.isNotEmpty()) effectiveFileContent else "",
                            jsonContent = if (effectiveJsonContent.isNotEmpty()) effectiveJsonContent else null,
                            metadata = if (effectiveMetadata.isNotEmpty()) effectiveMetadata else null,
                            userId = sessionManager.getUserId(),
                            // 🔥 NUEVO: Enviar información para que el backend obtenga contenido desde R2/Supabase
                            submissionId = currentSubmissionId,
                            taskId = currentTaskIdForRequest,
                            studentId = currentStudentId,
                            fileUri = null // El backend lo obtiene de task_submissions si es necesario
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
                        Log.d("ChatBotFragment", "studentId: $currentStudentId")
                        Log.d("ChatBotFragment", "==============================================")
                        
                        // Usar suspend function en lugar de .execute() para mejor manejo de timeouts
                        // Agregar userId para notificaciones
                        val currentUserId = sessionManager.getUserId()
                        val bodyWithUserId = body.copy(userId = currentUserId)
                        val res = microservicioApi.procesarPrompt(bodyWithUserId)
                        Log.d("ChatBotFragment", "✅ RESPUESTA RECIBIDA DEL MICROSERVICIO:")
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "📥 RESPUESTA COMPLETA DEL MODELO:")
                        Log.d("ChatBotFragment", "respuesta_texto completa: '${res.respuesta_texto}'")
                        Log.d("ChatBotFragment", "nota del backend: ${res.nota}")
                        Log.d("ChatBotFragment", "esCalificacion: ${res.esCalificacion}")
                        Log.d("ChatBotFragment", "Longitud total: ${res.respuesta_texto?.length ?: 0} caracteres")
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "✅ ENVIANDO RESPUESTA COMPLETA AL CHAT (SIN FILTROS)")
                        Log.d("ChatBotFragment", "==============================================")
                        
                        // Devolver la respuesta COMPLETA tal como la envía el modelo, incluyendo formato
                        // Si la respuesta es nula y el fileContent está vacío, dar mensaje específico
                        if (res.respuesta_texto.isNullOrBlank()) {
                            // Verificar si el contenido del archivo estaba vacío
                            if (effectiveFileContent.isBlank() || effectiveFileContent.length < 50) {
                                LLMResponse("""📊 **CALIFICACIÓN: 0/100**

❌ **RESULTADO:** No aprobado

⚠️ **MOTIVO:** La entrega no contiene contenido que pueda ser evaluado.

El archivo enviado está vacío o no se pudo leer su contenido.

💡 **PARA MEJORAR TU CALIFICACIÓN:**
1. Asegúrate de que el archivo contenga tu trabajo completo
2. Verifica que el contenido sea visible y legible
3. Si usaste un formato especial, conviértelo a PDF o TXT
4. Vuelve a subir la tarea con el contenido completo

📝 **Feedback:** Una entrega vacía siempre recibe nota 0.""", 0f)
                            } else {
                                LLMResponse("Hubo un problema al procesar tu solicitud. Por favor, intenta nuevamente.", null)
                            }
                        } else {
                            // Capturar la nota del backend directamente
                            LLMResponse(res.respuesta_texto, res.nota)
                        }
                    } catch (e: HttpException) {
                        Log.e("ChatBotFragment", "❌ HttpException: ${e.message()}")
                        Log.e("ChatBotFragment", "❌ HTTP Code: ${e.code()}")
                        Log.e("ChatBotFragment", "❌ HTTP Response: ${e.response()}")
                        try {
                            val errorBody = e.response()?.errorBody()?.string()
                            Log.e("ChatBotFragment", "❌ Error Body: $errorBody")
                            LLMResponse("Error del microservicio (HTTP ${e.code()}): $errorBody", null)
                        } catch (ex: Exception) {
                            LLMResponse("Error al conectar con el microservicio (HTTP ${e.code()}): ${e.message()}", null)
                        }
                    } catch (e: SocketTimeoutException) {
                        Log.e("ChatBotFragment", "❌ SocketTimeoutException: ${e.message}")
                        LLMResponse("El modelo está tardando más de lo esperado. Intenta nuevamente en unos minutos.", null)
                    } catch (e: java.net.ConnectException) {
                        Log.e("ChatBotFragment", "❌ ConnectException: ${e.message}")
                        LLMResponse("No se puede conectar con el microservicio. Verifica que esté ejecutándose en ${getMicroserviceBaseUrl()}", null)
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "❌ Exception: ${e.message}")
                        Log.e("ChatBotFragment", "❌ Exception Type: ${e::class.java.simpleName}")
                        Log.e("ChatBotFragment", "❌ Stack Trace: ${e.stackTrace.contentToString()}")
                        LLMResponse("Error inesperado: ${e.message}", null)
                    }
                }
                
                val response = llmResponse.text
                
                // Usar la nota del backend si está disponible, sino extraerla del texto
                val hasCalification = detectCalification(messageText, response) || llmResponse.nota != null
                val calificationValue = if (llmResponse.nota != null) {
                    // Usar la nota del backend directamente
                    val notaValue = llmResponse.nota
                    Log.d("ChatBotFragment", "📊 Usando nota del backend: $notaValue")
                    if (notaValue % 1 == 0f) "${notaValue.toInt()}/10" else String.format("%.1f/10", notaValue)
                } else {
                    // Fallback: extraer del texto
                    extractCalificationValue(response)
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
                    try {
                        val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                        val toSend = botMessage.copy(id = savedBotId, senderUsername = botMessage.senderUsername, senderAvatar = botMessage.senderAvatar)
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val ok = supabaseRepo.upsert("chat_messages", toSend)
                            if (ok) Log.i("ChatBotFragment", "ChatMessage $savedBotId upserted to Supabase.")
                            else Log.w("ChatBotFragment", "Failed to upsert ChatMessage $savedBotId to Supabase.")
                        }
                    } catch (e: Exception) {
                        Log.w("ChatBotFragment", "Exception sending bot chat to Supabase: ${e.message}")
                    }
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
                    try {
                        val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                        val toSend = errorMessage.copy(id = savedErrId, senderUsername = errorMessage.senderUsername, senderAvatar = errorMessage.senderAvatar)
                        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val ok = supabaseRepo.upsert("chat_messages", toSend)
                            if (ok) Log.i("ChatBotFragment", "ChatMessage $savedErrId upserted to Supabase.")
                            else Log.w("ChatBotFragment", "Failed to upsert ChatMessage $savedErrId to Supabase.")
                        }
                    } catch (e: Exception) {
                        Log.w("ChatBotFragment", "Exception sending error chat to Supabase: ${e.message}")
                    }
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
     * Detecta si el mensaje del usuario solicita calificación y si la respuesta del bot contiene una calificación
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
            botResponseLower.contains("📊 **nota:") ||
            botResponseLower.contains("nota:") && Regex("nota:\\s*\\d+").containsMatchIn(botResponseLower)) {
            return true
        }
        
        // Palabras clave que indican solicitud de calificación
        val calificationKeywords = listOf(
            "calificación", "calificacion", "nota", "puntaje", "puntuación", "puntuacion",
            "nota", "score", "rating", "evaluación", "evaluacion", "qué nota", "que nota",
            "cuánto saqué", "cuanto saque", "mi nota", "mi calificación", "mi calificacion",
            "revisar", "corregir", "feedback", "retroalimentación", "retroalimentacion"
        )
        
        // Verificar si el usuario pidió calificación
        val userAskedForCalification = calificationKeywords.any { keyword ->
            userMessageLower.contains(keyword)
        }
        
        // Verificar si la respuesta contiene formato de calificación
        val botHasCalification = botResponseLower.contains("calificación:") ||
                botResponseLower.contains("calificacion:") ||
                Regex("\\d+/\\d+").containsMatchIn(botResponse) ||
                Regex("calificación.*\\d+").containsMatchIn(botResponseLower) ||
                Regex("nota.*\\d+").containsMatchIn(botResponseLower)
        
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
                
                // Convertir a escala de 10 si es necesario
                val gradeValue = try {
                    normalizedGrade.toFloat()
                } catch (e: NumberFormatException) {
                    Log.e("ChatBotFragment", "❌ No se pudo convertir grade a Float: $grade (normalizado: $normalizedGrade)", e)
                    null
                }
                
                return if (gradeValue != null) {
                    if (gradeValue > 10) {
                        // Convertir de escala 100 a 10
                        String.format("%.1f", gradeValue / 10)
                    } else {
                        String.format("%.1f", gradeValue)
                    }
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
                val grade = extractGradeFromMessage(message.message)
                val feedback = extractFeedbackFromMessage(message.message)
                
                if (grade != null && feedback != null) {
                    // Almacenar la calificación en el CalificationManager
                    val calificationManager = CalificationManager.getInstance(requireContext())
                    
                    // Intentar extraer el número del # del mensaje para determinar qué TaskSubmission actualizar
                    val taskSubmissionId = extractTaskSubmissionIdFromMessage(message.message) 
                        ?: findTaskSubmissionIdInContext() // Buscar en contexto si no se encuentra en el mensaje
                    val targetSubmissionId = taskSubmissionId ?: currentFileContext?.submissionId
                    
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
                            normalizedGrade.toFloat()
                        } catch (e: NumberFormatException) {
                            Log.e("ChatBotFragment", "❌ Error convirtiendo grade '$grade' (normalizado: '$normalizedGrade') a Float", e)
                            null
                        }
                        
                        if (gradeFloat != null) {
                            withContext(Dispatchers.IO) {
                                // Obtener la entrega por ID desde la base local
                                var taskSubmission = database.taskSubmissionDao().getSubmissionById(targetSubmissionId)

                                // Si no existe localmente, intentar obtener desde Supabase (remote-authoritative)
                                if (taskSubmission == null) {
                                    try {
                                        Log.d("ChatBotFragment", "TaskSubmission $targetSubmissionId no encontrada localmente, intentando Supabase...")
                                        // Preferir usar SyncRepository helper which delegates to SupabaseClient
                                        val remote = com.example.tareamov.data.sync.SyncRepository
                                        // Try to fetch by ID from SupabaseClient directly
                                        val supabaseClient = com.example.tareamov.service.SupabaseClient
                                        if (supabaseClient.isConfigured()) {
                                            val fetched = withContext(Dispatchers.IO) { supabaseClient.fetchTaskSubmissions().firstOrNull { it.id == targetSubmissionId } }
                                            if (fetched != null) {
                                                taskSubmission = fetched
                                                Log.i("ChatBotFragment", "TaskSubmission $targetSubmissionId encontrada en Supabase")
                                                // Optional: insert into local DB to cache it
                                                try {
                                                    // Insert may fail if id conflicts; use updateSubmission if needed
                                                    database.taskSubmissionDao().insertSubmission(fetched)
                                                    Log.d("ChatBotFragment", "Cached remote TaskSubmission $targetSubmissionId into local Room")
                                                } catch (e: Exception) {
                                                    Log.w("ChatBotFragment", "No se pudo cachear TaskSubmission $targetSubmissionId localmente: ${e.message}")
                                                }
                                            } else {
                                                Log.w("ChatBotFragment", "TaskSubmission $targetSubmissionId no encontrada en Supabase")
                                            }
                                        } else {
                                            Log.w("ChatBotFragment", "Supabase no está configurado, no se puede buscar remoto para TaskSubmission $targetSubmissionId")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("ChatBotFragment", "Error buscando TaskSubmission en Supabase: ${e.message}")
                                    }
                                }

                                if (taskSubmission != null) {
                                    // Actualizar con la nueva calificación y feedback
                                    val updatedSubmission = taskSubmission.copy(
                                        grade = gradeFloat,
                                        feedback = feedback
                                    )

                                    // Update local DB: if original came from local, update; otherwise try insert/update
                                    try {
                                        database.taskSubmissionDao().updateSubmission(updatedSubmission)
                                    } catch (e: Exception) {
                                        try {
                                            database.taskSubmissionDao().insertSubmission(updatedSubmission)
                                        } catch (ex: Exception) {
                                            Log.w("ChatBotFragment", "No se pudo actualizar/insertar TaskSubmission localmente: ${ex.message}")
                                        }
                                    }

                                    // Obtener información de la tarea para logging
                                    val task = database.taskDao().getTaskById(taskSubmission.taskId)
                                    var taskName = task?.name
                                    
                                    if (taskName == null) {
                                        val supabaseClient = com.example.tareamov.service.SupabaseClient
                                        if (supabaseClient.isConfigured()) {
                                            try {
                                                val remoteTask = withContext(Dispatchers.IO) {
                                                    supabaseClient.fetchTaskById(taskSubmission.taskId)
                                                }
                                                taskName = remoteTask?.name
                                                Log.d("ChatBotFragment", "✅ Nombre de tarea recuperado de Supabase: $taskName")
                                            } catch (e: Exception) {
                                                Log.w("ChatBotFragment", "Error fetching task name from Supabase: ${e.message}")
                                            }
                                        }
                                    }
                                    
                                    val effectiveTaskName = taskName ?: "Tarea desconocida"

                                    Log.d("ChatBotFragment", "✅ TaskSubmission actualizada (local/remote seguirá):")
                                    Log.d("ChatBotFragment", "   - ID: $targetSubmissionId")
                                    Log.d("ChatBotFragment", "   - Tarea: $effectiveTaskName")
                                    val studentName = try {
                                        database.usuarioDao().getUsuarioById(taskSubmission.studentId)?.usuario ?: taskSubmission.studentId.toString()
                                    } catch (e: Exception) {
                                        taskSubmission.studentId.toString()
                                    }
                                    Log.d("ChatBotFragment", "   - Estudiante: $studentName")
                                    Log.d("ChatBotFragment", "   - Grade: $gradeFloat")
                                    Log.d("ChatBotFragment", "   - Feedback: $feedback")

                                    // Intentar enviar la actualización a Supabase (remoto)
                                    try {
                                        val okRemote = com.example.tareamov.data.sync.SyncRepository.updateTaskSubmissionToSupabase(updatedSubmission)
                                        if (okRemote) {
                                            Log.i("ChatBotFragment", "✅ TaskSubmission $targetSubmissionId actualizado en Supabase")
                                            
                                            // 📧📱 NOTIFICAR AL ESTUDIANTE que recibió una calificación
                                            // La notificación va al ESTUDIANTE (dueño de la entrega), NO al creador del curso
                                            val graderUsername = sessionManager.getUsername() ?: "Profesor"
                                            
                                            notifyStudentAboutGrade(
                                                studentId = taskSubmission.studentId,
                                                taskName = effectiveTaskName,
                                                grade = gradeFloat,
                                                feedback = feedback,
                                                gradedByUsername = graderUsername
                                            )
                                        } else {
                                            Log.w("ChatBotFragment", "⚠️ No se pudo actualizar TaskSubmission $targetSubmissionId en Supabase")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("ChatBotFragment", "Exception actualizando TaskSubmission en Supabase: ${e.message}")
                                    }
                                } else {
                                    Log.w("ChatBotFragment", "❌ No se encontró TaskSubmission con ID: $targetSubmissionId (local y remoto)")
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
    private fun handleEditUserMessage(message: ChatMessage) {
        // Crear un diálogo para editar el mensaje
        val editText = EditText(requireContext()).apply {
            setText(message.message)
            setSelection(message.message.length) // Poner cursor al final
            hint = "Editar mensaje..."
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white))
            setHintTextColor(ContextCompat.getColor(requireContext(), android.R.color.darker_gray))
            backgroundTintList = ContextCompat.getColorStateList(requireContext(), android.R.color.white)
        }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("✏️ Editar Mensaje")
            .setMessage("Modifica tu mensaje y se volverá a enviar al modelo:")
            .setView(editText)
            .setPositiveButton("🔄 Actualizar y Reenviar") { _, _ ->
                val newMessageText = editText.text.toString().trim()
                if (newMessageText.isNotEmpty() && newMessageText != message.message) {
                    handleMessageEdit(message, newMessageText)
                }
            }
            .setNegativeButton("❌ Cancelar", null)
            .create()
            .apply {
                // Estilo del diálogo
                window?.setBackgroundDrawableResource(android.R.color.black)
                show()
                getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_green_light)
                )
                getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_red_light)
                )
            }
    }

    /**
     * Procesa la edición del mensaje: actualiza el mensaje original y elimina respuestas subsecuentes
     */
    private fun handleMessageEdit(originalMessage: ChatMessage, newMessageText: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // 1. Obtener todos los mensajes después del mensaje editado
                    val allMessages = database.chatMessageDao().getAllMessages().first()
                    var messageIndex = -1
                    for (i in allMessages.indices) {
                        if (allMessages[i].id == originalMessage.id) {
                            messageIndex = i
                            break
                        }
                    }
                    
                    if (messageIndex != -1) {
                        // 2. Eliminar todos los mensajes posteriores (respuestas del bot y otros mensajes)
                        val messagesToDelete = allMessages.drop(messageIndex + 1)
                        messagesToDelete.forEach { msg ->
                            database.chatMessageDao().deleteMessage(msg)
                        }
                        
                        // 3. Actualizar el mensaje original con el nuevo texto
                        val updatedMessage = originalMessage.copy(
                            message = newMessageText,
                            timestamp = System.currentTimeMillis() // Actualizar timestamp
                        )
                        database.chatMessageDao().updateMessage(updatedMessage)
                    }
                }

                // 4. Recargar mensajes en la UI
                loadMessages()
                
                // 5. Procesar el mensaje editado directamente sin duplicar
                withContext(Dispatchers.Main) {
                    // Mostrar indicador de procesamiento
                    loadingProgressBar.visibility = View.VISIBLE
                    
                    // Procesar directamente con el AI
                    try {
                        val botResponse = analizarEntregaYFeedback(newMessageText, currentFileContext)
                        
                        // Crear respuesta del bot
                        val botMessage = ChatMessage(
                            message = botResponse,
                            isFromUser = false,
                            timestamp = System.currentTimeMillis(),
                            sessionId = sessionId,
                            hasCalification = detectCalification(newMessageText, botResponse),
                            calificationAdded = false,
                            senderUsername = "DeepSeek",
                            senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                        )
                        
                        // Guardar respuesta del bot en la base de datos
                        withContext(Dispatchers.IO) {
                            database.chatMessageDao().insertMessage(botMessage)
                        }
                        
                        // Recargar mensajes para mostrar la nueva respuesta
                        loadMessages()
                        
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "Error processing edited message", e)
                        val errorMessage = ChatMessage(
                            message = "Error al procesar el mensaje editado: ${e.message}",
                            isFromUser = false,
                            timestamp = System.currentTimeMillis(),
                            sessionId = sessionId,
                            hasCalification = false,
                            calificationAdded = false,
                            senderUsername = "DeepSeek",
                            senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                        )
                        
                        withContext(Dispatchers.IO) {
                            database.chatMessageDao().insertMessage(errorMessage)
                        }
                        loadMessages()
                    } finally {
                        loadingProgressBar.visibility = View.GONE
                    }
                }
                
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error in handleMessageEdit", e)
                withContext(Dispatchers.Main) {
                    loadingProgressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error al editar mensaje", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Muestra el overlay con las tareas calificadas
     */
    private fun showGradedTasksOverlay() {
        if (courseId == -1L) {
            Toast.makeText(context, "No hay información del curso disponible", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                loadingProgressBar.visibility = View.VISIBLE
                
                // Siempre recargar desde Supabase/DB para tener datos actualizados
                val gradedTasksFromDB = loadGradedTasksFromChat()
                gradedTasksList.clear()
                gradedTasksList.addAll(gradedTasksFromDB)
                
                loadingProgressBar.visibility = View.GONE
                
                if (gradedTasksList.isEmpty()) {
                    Toast.makeText(context, "No hay tareas calificadas aún", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                Log.d("ChatBotFragment", "Mostrando ${gradedTasksList.size} tareas calificadas")
                
                gradedCourseNameTextView.text = courseTitle.ifEmpty { "Curso Actual" }
                gradedTaskOverlayAdapter.updateGradedTasks(gradedTasksList.toList()) // Crear copia para evitar modificaciones concurrentes
                
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
     * Carga las tareas calificadas desde Supabase (prioridad) o TaskSubmissionDao
     */
    private suspend fun loadGradedTasksFromChat(): List<GradedTaskItem> {
        return withContext(Dispatchers.IO) {
            try {
                val gradedTasks = mutableListOf<GradedTaskItem>()
                val processedTaskSubmissionIds = mutableSetOf<Long>()
                
                Log.d("ChatBotFragment", "Cargando tareas calificadas...")
                
                val userId = try {
                    sessionManager.getUserId()
                } catch (e: Exception) {
                    null
                }
                
                // Obtener todas las submissions (intentar Supabase primero si hay curso y usuario)
                var allSubmissions: List<TaskSubmission> = emptyList()
                var loadedFromSupabase = false
                
                if (courseId != -1L && userId != null) {
                    try {
                        Log.d("ChatBotFragment", "Intentando obtener submissions desde Supabase para curso $courseId y usuario $userId")
                        val remoteSubmissions = syncRepository.fetchStudentSubmissionsForCourseFromSupabase(userId, courseId)
                        if (remoteSubmissions.isNotEmpty()) {
                            Log.d("ChatBotFragment", "Submissions obtenidas de Supabase: ${remoteSubmissions.size}")
                            allSubmissions = remoteSubmissions
                            loadedFromSupabase = true
                        } else {
                            Log.d("ChatBotFragment", "Supabase no retornó submissions, intentando local")
                        }
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "Error fetching from Supabase: ${e.message}")
                    }
                }
                
                // Fallback a local si no se cargó de Supabase
                if (!loadedFromSupabase) {
                    Log.d("ChatBotFragment", "Cargando submissions desde base de datos local")
                    allSubmissions = if (courseId != -1L) {
                        database.taskSubmissionDao().getSubmissionsByCourse(courseId)
                    } else {
                        database.taskSubmissionDao().getAllTaskSubmissions()
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
                            // Intentar obtener tarea localmente
                            var task = database.taskDao().getTaskById(submission.taskId)
                            
                            // Si no está local y venimos de Supabase, intentar fetch remoto de la tarea
                            if (task == null && loadedFromSupabase) {
                                try {
                                    task = syncRepository.fetchTaskByIdFromSupabase(submission.taskId)
                                } catch (e: Exception) {
                                    Log.e("ChatBotFragment", "Error fetching task ${submission.taskId} from Supabase: ${e.message}")
                                }
                            }
                            
                            if (task != null) {
                                val topic = database.topicDao().getTopicById(task.topicId)
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
                gradedTasks
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error cargando tareas calificadas desde TaskSubmissionDao", e)
                emptyList()
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
                val gradedTasksFromDB = loadGradedTasksFromChat()
                
                // Limpiar la lista actual y agregar las tareas cargadas
                gradedTasksList.clear()
                gradedTasksList.addAll(gradedTasksFromDB)
                
                // Actualizar el adaptador
                gradedTaskOverlayAdapter.updateGradedTasks(gradedTasksList.toList())
                
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
     * Carga solo los cursos donde el usuario ha enviado tareas (submissions)
     * MODIFICADO: Ahora carga cursos CREADOS por el usuario actual que tengan entregas de estudiantes (para calificar)
     */
    private suspend fun loadAllUserCourses(): List<TaskItem> {
        return withContext(Dispatchers.IO) {
            val userId = sessionManager.getUserId()
            if (userId == -1L) {
                Log.w("ChatBotFragment", "No user ID found")
                return@withContext emptyList()
            }
            
            try {
                // Fetch courses where the current user is the CREATOR (Owner)
                val supabaseClient = com.example.tareamov.service.SupabaseClient
                if (supabaseClient.isConfigured()) {
                    try {
                        // 1. Get courses created by me
                        val myCourses = supabaseClient.fetchCoursesByCreatorUserId(userId)
                        
                        // 2. Filter: only keep courses that have submissions from students
                        val coursesWithSubmissions = mutableListOf<com.example.tareamov.data.entity.Course>()
                        
                        for (course in myCourses) {
                             // Use SyncRepository to check for submissions
                             try {
                                 // fetchCourseSubmissionsWithUsernames returns a list of submissions
                                 // If this list is not empty, it means there are submissions.
                                 val submissions = syncRepository.fetchCourseSubmissionsWithUsernames(course.id)
                                 if (submissions.isNotEmpty()) {
                                     coursesWithSubmissions.add(course)
                                 }
                             } catch (e: Exception) {
                                 Log.w("ChatBotFragment", "Error checking submissions for course ${course.id}: ${e.message}")
                             }
                        }
                        
                        Log.d("ChatBotFragment", "Loaded ${coursesWithSubmissions.size} courses created by user $userId that have submissions")

                        coursesWithSubmissions.mapIndexed { index, course ->
                            TaskItem(
                                taskId = course.id, // Using course.id as taskId/itemId
                                taskName = course.title,
                                taskDescription = course.description ?: "Sin descripción",
                                topicName = course.category ?: "Sin categoría", // category for filtering
                                index = index + 1
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("ChatBotFragment", "Error fetching courses by creator from Supabase: ${e.message}")
                        emptyList()
                    }
                } else {
                    // Local fallback: courses created by user AND having submissions
                    val allCourses = database.courseDao().getAllCourses()
                    val myLocalCourses = allCourses.filter { it.creatorUserId == userId }
                    
                    val filtered = mutableListOf<com.example.tareamov.data.entity.Course>()
                    
                    for (course in myLocalCourses) {
                        val topics = database.topicDao().getTopicsByCourse(course.id)
                        var hasSubs = false
                        for (topic in topics) {
                            val tasks = database.taskDao().getTasksByTopicId(topic.id)
                            for (task in tasks) {
                                val subs = database.taskSubmissionDao().getSubmissionsByTask(task.id)
                                if (subs.isNotEmpty()) {
                                    hasSubs = true
                                    break
                                }
                            }
                            if (hasSubs) break
                        }
                        if (hasSubs) filtered.add(course)
                    }
                    
                    filtered.mapIndexed { index, course ->
                         TaskItem(
                            taskId = course.id,
                            taskName = course.title,
                            taskDescription = course.description ?: "Sin descripción",
                            topicName = course.category ?: "Sin categoría",
                            index = index + 1
                        )
                    }
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
                // Fetch ALL submissions for the course using the new method with usernames
                // The method returns List<Map<String, Any?>> which is compatible
                val submissions = syncRepository.fetchCourseSubmissionsWithUsernames(courseId)
                
                Log.d("ChatBotFragment", "Loaded ${submissions.size} submissions for course $courseId")
                
                // Group submissions by student and sort by username
                // The 'student_username' key is now directly available from the JOIN query
                val submissionsByStudent = submissions
                    .groupBy { 
                         // Use 'student_username' from the query result (from LEFT JOIN usuarios)
                         // Fallback to "Unknown" if null
                         (it["student_username"] as? String) ?: "Unknown"
                    }
                    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
                
                val taskItems = mutableListOf<TaskItem>()
                var index = 1
                
                // Explicitly iterate over entries to avoid inference issues
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
                        val taskTitle = sub["task_title"] as? String ?: "Tarea sin título"
                        val grade = (sub["grade"] as? Number)?.toFloat() ?: 0f
                        // submission_date can be Long or String depending on source
                        val submissionDate = sub["submission_date"]
                        
                        // Format grade info
                        val gradeInfo = if (grade > 0) {
                            val gradeFormatted = if (grade % 1 == 0f) grade.toInt().toString() else String.format("%.1f", grade)
                            "✅ Nota: $gradeFormatted/10"
                        } else {
                            "⏳ Pendiente de calificar"
                        }
                        
                        taskItems.add(
                            TaskItem(
                                taskId = (sub["task_id"] as? Number)?.toLong() ?: 0L,
                                taskName = taskTitle,
                                taskDescription = "$gradeInfo • Promedio: $formattedAvg",
                                topicName = username, // Use the resolved username here for display in overlay
                                index = index++,
                                studentUsername = username,
                                averageGrade = formattedAvg
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
                
                // Obtener tareas (filtradas por curso si es posible)
                if (courseId != -1L) {
                    // Intentar cargar localmente primero
                    val topics = database.topicDao().getTopicsByCourse(courseId)
                    val courseTasks = mutableListOf<com.example.tareamov.data.entity.Task>()
                    for (topic in topics) {
                        courseTasks.addAll(database.taskDao().getTasksByTopicId(topic.id))
                        topicMap[topic.id] = topic.name
                    }
                    
                    if (courseTasks.isNotEmpty()) {
                        tasks = courseTasks
                    } else {
                        // Fallback a Supabase si no hay tareas locales para el curso
                        try {
                            val supabaseClient = com.example.tareamov.service.SupabaseClient
                            if (supabaseClient.isConfigured()) {
                                Log.d("ChatBotFragment", "No local tasks for course $courseId, fetching from Supabase")
                                val remoteTopics = supabaseClient.fetchTopicsByCourse(courseId)
                                remoteTopics.forEach { topicMap[it.id] = it.name }
                                
                                val remoteTopicIds = remoteTopics.map { it.id }
                                if (remoteTopicIds.isNotEmpty()) {
                                    tasks = supabaseClient.fetchTasksByTopicIds(remoteTopicIds)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ChatBotFragment", "Error fetching remote tasks for course", e)
                        }
                    }
                } else {
                    tasks = database.taskDao().getAllTasks()
                    // Fallback global a Supabase si no hay tareas locales
                    if (tasks.isEmpty()) {
                        try {
                            val supabaseClient = com.example.tareamov.service.SupabaseClient
                            if (supabaseClient.isConfigured()) {
                                Log.d("ChatBotFragment", "No local tasks (global), fetching from Supabase")
                                tasks = supabaseClient.fetchTasks()
                                // Intentar cargar topics para mapear nombres
                                if (tasks.isNotEmpty()) {
                                    val topics = supabaseClient.fetchTopics()
                                    topics.forEach { topicMap[it.id] = it.name }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ChatBotFragment", "Error fetching remote tasks (global)", e)
                        }
                    }
                }
                
                Log.d("ChatBotFragment", "Cargando tareas... encontradas: ${tasks.size}")
                
                // Usar índice secuencial para referencias con #1, #2, etc.
                var sequentialIndex = 1
                
                for (task in tasks) {
                    try {
                        // Resolver nombre del tema
                        var topicName = topicMap[task.topicId]
                        if (topicName == null) {
                            val topic = database.topicDao().getTopicById(task.topicId)
                            if (topic != null) {
                                topicName = topic.name
                                topicMap[task.topicId] = topicName
                            } else {
                                topicName = "Sin tema"
                            }
                        }
                        
                        // 🔥 CRÍTICO: Obtener descripción completa de la tarea
                        // Si la descripción local está vacía, intentar obtenerla de Supabase
                        var taskDescription = task.description ?: ""
                        if (taskDescription.isBlank() || taskDescription == "Sin descripción") {
                            val supabaseClient = com.example.tareamov.service.SupabaseClient
                            if (supabaseClient.isConfigured()) {
                                try {
                                    val remoteTask = supabaseClient.fetchTaskByName(task.name)
                                    if (remoteTask != null && !remoteTask.description.isNullOrBlank()) {
                                        taskDescription = remoteTask.description!!
                                        Log.d("ChatBotFragment", "📝 Descripción obtenida de Supabase para '${task.name}': ${taskDescription.take(50)}...")
                                    }
                                } catch (e: Exception) {
                                    Log.w("ChatBotFragment", "Error obteniendo descripción de Supabase: ${e.message}")
                                }
                            }
                        }
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
                        // Try local DB first
                        var uid = database.usuarioDao().getUsuarioByUsername(username)?.id
                        // If not found locally, try remote
                        if (uid == null) {
                            try {
                                val userWithRole = syncRepository.fetchUsuarioWithRoleFromSupabase(username)
                                uid = userWithRole?.id
                            } catch (e: Exception) {
                                Log.e("ChatBotFragment", "Error fetching user id for $username", e)
                            }
                        }
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
                    
                    val supabaseClient = com.example.tareamov.service.SupabaseClient
                    var foundSubmissions: List<TaskSubmission> = emptyList()
                    
                    // 🔥 PASO 0: Resolver el taskId correcto de Supabase usando el nombre de la tarea
                    // y también obtener la descripción completa de la tarea
                    var remoteTaskId = task.taskId
                    if (supabaseClient.isConfigured()) {
                        try {
                            Log.d("ChatBotFragment", "🔍 Resolviendo taskId remoto por nombre: '${task.taskName}'")
                            val remoteTask = supabaseClient.fetchTaskByName(task.taskName)
                            if (remoteTask != null) {
                                remoteTaskId = remoteTask.id
                                Log.d("ChatBotFragment", "✅ TaskId remoto resuelto: $remoteTaskId (local era: ${task.taskId})")
                                
                                // 🔥 CRÍTICO: Actualizar taskDescription con la descripción de Supabase
                                // si la descripción local está vacía o es "Sin descripción"
                                if (taskDescription.isBlank() || taskDescription == "Sin descripción") {
                                    val remoteDescription = remoteTask.description
                                    if (!remoteDescription.isNullOrBlank()) {
                                        taskDescription = remoteDescription
                                        Log.d("ChatBotFragment", "📝 Descripción actualizada desde Supabase: ${taskDescription.take(100)}...")
                                    }
                                }
                            } else {
                                Log.w("ChatBotFragment", "⚠️ No se encontró tarea remota con nombre '${task.taskName}', usando taskId local")
                            }
                        } catch (e: Exception) {
                            Log.w("ChatBotFragment", "⚠️ Error resolviendo taskId remoto: ${e.message}")
                        }
                    }
                    
                    // 1. PRIORIDAD: Buscar en Supabase por taskId remoto y student_id (integer)
                    if (supabaseClient.isConfigured()) {
                        try {
                            Log.d("ChatBotFragment", "🌐 Consultando Supabase por task_id=$remoteTaskId y student_id=$userId...")
                            foundSubmissions = supabaseClient.fetchTaskSubmissionsByTaskAndStudentId(remoteTaskId, userId)
                            Log.d("ChatBotFragment", "📊 Submissions encontradas en Supabase: ${foundSubmissions.size}")
                            
                            foundSubmissions.forEachIndexed { index, sub ->
                                Log.d("ChatBotFragment", "   [$index] id=${sub.id}, file='${sub.fileName}', date=${sub.submissionDate}")
                            }
                        } catch (e: Exception) {
                            Log.w("ChatBotFragment", "⚠️ Error consultando Supabase por student_id: ${e.message}")
                        }
                    }
                    
                    // 2. Fallback: buscar en Supabase todas las submissions y filtrar por studentId
                    if (foundSubmissions.isEmpty() && supabaseClient.isConfigured()) {
                        try {
                            Log.d("ChatBotFragment", "🌐 Fallback: Consultando todas las submissions de Supabase para taskId=$remoteTaskId...")
                            val allSubmissions = supabaseClient.fetchTaskSubmissions()
                                .filter { it.taskId == remoteTaskId }
                            Log.d("ChatBotFragment", "📊 Total submissions para taskId=$remoteTaskId: ${allSubmissions.size}")
                            
                            // Filtrar por studentId o intentar match por otros campos
                            foundSubmissions = allSubmissions.filter { it.studentId == userId }
                            Log.d("ChatBotFragment", "📊 Submissions filtradas por studentId=$userId: ${foundSubmissions.size}")
                        } catch (e: Exception) {
                            Log.w("ChatBotFragment", "⚠️ Error en fallback Supabase: ${e.message}")
                        }
                    }
                    
                    // 3. Fallback final: base de datos local (usar taskId local)
                    if (foundSubmissions.isEmpty()) {
                        Log.d("ChatBotFragment", "📂 Consultando base de datos local...")
                        foundSubmissions = database.taskSubmissionDao().getSubmissionsByTask(task.taskId)
                            .filter { it.studentId == userId }
                        Log.d("ChatBotFragment", "📊 Submissions encontradas localmente: ${foundSubmissions.size}")
                    }
                    
                    // Tomar la submission más reciente
                    val sortedSubmissions = foundSubmissions.sortedByDescending { it.submissionDate }
                    
                    Log.d("ChatBotFragment", "✅ Submissions del usuario actual: ${sortedSubmissions.size}")
                    
                    if (sortedSubmissions.isNotEmpty()) {
                        submission = sortedSubmissions.first()
                        Log.d("ChatBotFragment", "📝 Submission encontrada: id=${submission!!.id}, file='${submission!!.fileName}'")
                        
                        // Guardar en local si vino de Supabase
                        try {
                            database.taskSubmissionDao().insertSubmission(submission!!)
                            Log.d("ChatBotFragment", "💾 Submission guardada en DB local")
                        } catch (e: Exception) {
                            Log.d("ChatBotFragment", "📝 Submission ya existe en DB local")
                        }
                        
                        // Buscar FileContext primero en Supabase
                        if (supabaseClient.isConfigured()) {
                            try {
                                Log.d("ChatBotFragment", "🌐 Buscando FileContext en Supabase...")
                                fileContext = supabaseClient.fetchFileContextBySubmissionId(submission!!.id)
                                
                                if (fileContext != null) {
                                    Log.d("ChatBotFragment", "📄 FileContext: ENCONTRADO (Supabase)")
                                    Log.d("ChatBotFragment", "   - fileName: ${fileContext!!.fileName}")
                                    Log.d("ChatBotFragment", "   - fileContent length: ${fileContext!!.fileContent.length}")
                                    Log.d("ChatBotFragment", "   - contentSummary: ${fileContext!!.contentSummary?.take(100)}")
                                    
                                    // Guardar en local para futuras consultas
                                    try {
                                        database.fileContextDao().insertFileContext(fileContext!!)
                                        Log.d("ChatBotFragment", "💾 FileContext guardado en DB local")
                                    } catch (e: Exception) {
                                        Log.d("ChatBotFragment", "📝 FileContext ya existe en DB local")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("ChatBotFragment", "⚠️ Error consultando FileContext en Supabase: ${e.message}")
                            }
                        }
                        
                        // Fallback a base de datos local si no se encontró en Supabase
                        if (fileContext == null) {
                            Log.d("ChatBotFragment", "📂 Buscando FileContext en DB local...")
                            fileContext = database.fileContextDao().getFileContextBySubmission(submission!!.id)
                            
                            if (fileContext != null) {
                                Log.d("ChatBotFragment", "📄 FileContext: ENCONTRADO (Local)")
                                Log.d("ChatBotFragment", "   - fileName: ${fileContext!!.fileName}")
                                Log.d("ChatBotFragment", "   - fileContent length: ${fileContext!!.fileContent.length}")
                                Log.d("ChatBotFragment", "   - contentSummary: ${fileContext!!.contentSummary?.take(100)}")
                            } else {
                                Log.d("ChatBotFragment", "📄 FileContext: NO ENCONTRADO")
                            }
                        }
                    } else {
                        Log.w("ChatBotFragment", "⚠️ No se encontraron submissions para este usuario y tarea")
                    }
                    
                    // 3. Fallback final: Si tenemos submission pero no FileContext, intentar leer el archivo
                    if (fileContext == null && submission != null) {
                        Log.w("ChatBotFragment", "⚠️ FileContext no encontrado, intentando crear contexto desde archivo...")
                        
                        var actualFileContent: String? = null
                        
                        // Intentar leer el contenido real del archivo si el URI aún es accesible
                        try {
                            val fileUri = android.net.Uri.parse(submission!!.fileUri)
                            Log.d("ChatBotFragment", "🔍 Intentando leer archivo desde URI: $fileUri")
                            
                            requireContext().contentResolver.openInputStream(fileUri)?.use { inputStream ->
                                actualFileContent = inputStream.bufferedReader().use { it.readText() }
                                Log.d("ChatBotFragment", "✅ Archivo leído exitosamente: ${actualFileContent!!.length} caracteres")
                            }
                        } catch (e: Exception) {
                            Log.w("ChatBotFragment", "⚠️ No se pudo leer el archivo desde URI: ${e.message}")
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
                        
                        // Intentar guardar este contexto generado para futuras consultas
                        if (actualFileContent != null) {
                            try {
                                val savedId = database.fileContextDao().insertFileContext(fileContext!!)
                                Log.d("ChatBotFragment", "💾 FileContext temporal guardado en DB local con id=$savedId")
                            } catch (e: Exception) {
                                Log.w("ChatBotFragment", "No se pudo guardar FileContext temporal: ${e.message}")
                            }
                        }
                    }
                }
                
                if (fileContext != null) {
                    // ✅ CRÍTICO: Establecer este contexto como el contexto actual
                    currentFileContext = fileContext
                    
                    // 🔥 CRÍTICO: Si el fileContent está vacío pero tenemos contentSummary,
                    // intentar obtener contenido adicional de la descripción de la tarea
                    if (fileContext!!.fileContent.isBlank()) {
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
                    Log.d("ChatBotFragment", "   - FileContent length: ${fileContext!!.fileContent.length}")
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
                                "📊 Contenido: ${fileContext!!.fileContent.length} caracteres\n\n" +
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
                    val submission = database.taskSubmissionDao().getSubmissionById(submissionId)
                    if (submission != null) {
                        // Obtener la tarea
                        val task = database.taskDao().getTaskById(submission.taskId)
                        if (task != null) {
                            // Obtener el tema
                            val topic = database.topicDao().getTopicById(task.topicId)
                            if (topic != null) {
                                // Obtener el curso
                                val course = database.courseDao().getCourseById(topic.courseId)
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
                        // If we couldn't load task info from local DB, attempt Supabase as fallback
                        try {
                            val supabaseClient = com.example.tareamov.service.SupabaseClient
                            if (supabaseClient.isConfigured()) {
                                Log.d("ChatBotFragment", "Attempting to load task info from Supabase for submissionId=$submissionId")
                                val remoteSubmission = withContext(Dispatchers.IO) { supabaseClient.fetchTaskSubmissions().firstOrNull { it.id == submissionId } }
                                if (remoteSubmission != null) {
                                    val remoteTask = withContext(Dispatchers.IO) { supabaseClient.fetchTaskById(remoteSubmission.taskId) }
                                    val remoteTopic = if (remoteTask != null) withContext(Dispatchers.IO) { supabaseClient.fetchTopics().firstOrNull { it.id == remoteTask.topicId } } else null
                                    val remoteCourse = if (remoteTopic != null) withContext(Dispatchers.IO) { supabaseClient.fetchCourseById(remoteTopic.courseId) } else null

                                    if (remoteTask != null && remoteTopic != null && remoteCourse != null) {
                                        taskName = remoteTask.name
                                        taskDescription = remoteTask.description ?: "Sin descripción"
                                        topicName = remoteTopic.name ?: ""
                                        courseTitle = remoteCourse.title ?: ""
                                        deliveryDate = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(remoteSubmission.submissionDate)
                                        courseId = remoteTopic.courseId

                                        Log.i("ChatBotFragment", "Loaded task info from Supabase for submissionId=$submissionId: $taskName - $topicName - $courseTitle")

                                        val taskInfoForAdapter = ChatMessageAdapter.TaskInfo(
                                            taskName = taskName,
                                            taskDescription = taskDescription,
                                            topicName = topicName,
                                            courseTitle = courseTitle,
                                            deliveryDate = deliveryDate
                                        )
                                        chatAdapter.updateTaskInfo(taskInfoForAdapter)
                                    } else {
                                        Log.w("ChatBotFragment", "Supabase returned incomplete task/topic/course data for submissionId=$submissionId")
                                    }
                                } else {
                                    Log.w("ChatBotFragment", "No se pudo cargar la información de la tarea para submissionId: $submissionId")
                                }
                            } else {
                                Log.w("ChatBotFragment", "No se pudo cargar la información de la tarea para submissionId: $submissionId")
                            }
                        } catch (e: Exception) {
                            Log.e("ChatBotFragment", "Error cargando información de la tarea desde Supabase", e)
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
                    val task = database.taskDao().getTaskById(taskId)
                    if (task != null) {
                        val topic = database.topicDao().getTopicById(task.topicId)
                        if (topic != null) {
                            val course = database.videoDao().getVideoById(topic.courseId)
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
                        database.taskSubmissionDao().getSubmissionById(fileContext.submissionId)
                    }
                    
                    taskSubmission?.let { submission ->
                        val task = withContext(Dispatchers.IO) {
                            database.taskDao().getTaskById(submission.taskId)
                        }
                        
                        val topic = task?.let { t ->
                            withContext(Dispatchers.IO) {
                                database.topicDao().getTopicById(t.topicId)
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
                    val gradedTasksFromDB = loadGradedTasksFromChat()
                    gradedTasksList.clear()
                    gradedTasksList.addAll(gradedTasksFromDB)
                }
                
                // Buscar la tarea calificada por su ID de submission (ahora es index)
                val gradedTask = gradedTasksList.find { it.index == taskNumber }
                
                if (gradedTask != null) {
                    Log.d("ChatBotFragment", "Tarea calificada encontrada: ${gradedTask.taskName} (submission ID: ${gradedTask.index})")
                    
                    // Cargar el FileContext de esta submission
                    val submissionId = taskNumber.toLong()
                    val fileContext = withContext(Dispatchers.IO) {
                        database.fileContextDao().getFileContextBySubmission(submissionId)
                    }
                    
                    if (fileContext != null) {
                        // Establecer este contexto como el contexto actual
                        currentFileContext = fileContext
                        
                        // Actualizar información del curso
                        updateCourseInfo(submissionId)
                        
                        Log.d("ChatBotFragment", "Contexto de archivo cargado para tarea referenciada:")
                        Log.d("ChatBotFragment", "- FileName: ${fileContext.fileName}")
                        Log.d("ChatBotFragment", "- ContentSummary length: ${fileContext.contentSummary?.length ?: 0}")
                        Log.d("ChatBotFragment", "- FileContent length: ${fileContext.fileContent.length}")
                        
                        // Mostrar mensaje informativo sobre el contexto cargado
                        val contextMessage = ChatMessage(
                            message = "🔗 **Contexto de tarea referenciada cargado**\n\n" +
                                    "📝 Tarea: ${gradedTask.taskName}\n" +
                                    "📁 Archivo: ${fileContext.fileName}\n" +
                                    "📊 Calificación: ${gradedTask.grade}\n" +
                                    "🔧 Tipo: ${fileContext.fileType}\n" +
                                    "📊 Contenido: ${fileContext.fileContent.length} caracteres\n\n" +
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
    private fun notifyStudentAboutGrade(
        studentId: Long,
        taskName: String,
        grade: Float,
        feedback: String,
        gradedByUsername: String
    ) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("ChatBotFragment", "📧 Enviando notificación de calificación al estudiante $studentId")
                Log.d("ChatBotFragment", "   📝 Tarea: $taskName")
                Log.d("ChatBotFragment", "   📊 Nota: $grade/10")
                Log.d("ChatBotFragment", "   👤 Calificado por: $gradedByUsername")
                
                val baseUrl = getMicroserviceBaseUrl().trimEnd('/')
                
                // Crear el payload JSON
                // Nota: JSONObject en Android no tiene put(String, Float), usar toDouble()
                val payload = JSONObject().apply {
                    put("studentId", studentId)
                    put("taskName", taskName)
                    put("grade", grade.toDouble())
                    put("feedback", feedback)
                    put("gradedByUsername", gradedByUsername)
                }
                
                // Configurar el cliente HTTP
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = okhttp3.Request.Builder()
                    .url("$baseUrl/notify-grade")
                    .header("X-API-Key", "tareamov-mcp-api-key-2025-secure")
                    .post(body)
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    Log.i("ChatBotFragment", "✅ Notificación de calificación enviada al estudiante $studentId")
                    Log.d("ChatBotFragment", "   Response: $responseBody")
                    
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context, 
                            "📧 Estudiante notificado de su calificación", 
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    val errorBody = response.body?.string()
                    Log.w("ChatBotFragment", "⚠️ Error enviando notificación de calificación: ${response.code} - $errorBody")
                }
                
                response.close()
                
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "❌ Error enviando notificación de calificación: ${e.message}", e)
            }
        }
    }
}