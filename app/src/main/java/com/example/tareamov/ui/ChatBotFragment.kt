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
import com.example.tareamov.service.AIAnalysisService
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
        val ollamaUrl = OLLAMA_URL
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

    private lateinit var chatAdapter: ChatMessageAdapter
    private lateinit var database: AppDatabase
    private lateinit var aiAnalysisService: AIAnalysisService
    private lateinit var fileAnalysisService: FileAnalysisService

    // Retrofit para el microservicio
    // IP y puerto explícitos del microservicio (actualizado con nueva configuración de red)
    private val MICROSERVICIO_BASE_URL = "http://10.218.57.181:3001/"
    private val OLLAMA_URL = "http://10.218.57.181:11435"
    // Aumentar los timeouts para evitar que el chat cierre la espera antes de que el modelo responda
    private val microservicioApi: MicroservicioApi by lazy {
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(20, java.util.concurrent.TimeUnit.MINUTES)  // Aumentado a 20 minutos
            .readTimeout(20, java.util.concurrent.TimeUnit.MINUTES)     // Aumentado a 20 minutos
            .writeTimeout(20, java.util.concurrent.TimeUnit.MINUTES)    // Aumentado a 20 minutos
            .build()
        val retrofit = Retrofit.Builder()
            .baseUrl(MICROSERVICIO_BASE_URL)
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
                cursorCheckHandler.postDelayed(this, 100) // Verificar cada 100ms
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
    private lateinit var taskOverlayAdapter: com.example.tareamov.adapter.TaskOverlayAdapter
    private val currentCourseTasks: MutableList<TaskItem> = mutableListOf()
    
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
        val ollamaUrl = OLLAMA_URL
        return try {
            if (!esPreguntaNota) {
                // Solo llama3 responde
                val request = com.example.tareamov.network.MicroservicioPromptRequest(
                    prompt = userMessage,
                    ollamaUrl = ollamaUrl,
                    taskDescription = taskDescription,
                    fileContent = fileContent
                )
                val response = microservicioApi.procesarPrompt(request)
                response.respuesta_texto ?: "No se pudo obtener respuesta."
            } else {
                // gemma3n analiza y da veredicto, llama3 da feedback
                val request = com.example.tareamov.network.MicroservicioPromptRequest(
                    prompt = userMessage,
                    ollamaUrl = ollamaUrl,
                    taskDescription = taskDescription,
                    fileContent = fileContent
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
        aiAnalysisService = AIAnalysisService(requireContext())
        fileAnalysisService = FileAnalysisService(requireContext())

        initializeViews(view)
        setupRecyclerView()
        setupClickListeners()
        loadMessages()
        loadFileContextFromArguments()

        // Probar conexión con Ollama al iniciar
        testOllamaConnectionOnStart()
        
        // Inicializar monitoreo del cursor para mostrar lista de tareas
        cursorCheckHandler.post(cursorCheckRunnable)
        
        // Cargar tareas calificadas persistidas al inicializar
        loadGradedTasksOnStart()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detener el monitoreo del cursor para evitar memory leaks
        cursorCheckHandler.removeCallbacks(cursorCheckRunnable)
    }

    private fun initializeViews(view: View) {
        messagesRecyclerView = view.findViewById(R.id.messagesRecyclerView)
        messageEditText = view.findViewById(R.id.messageEditText)
        sendButton = view.findViewById(R.id.sendButton)
        gradedTasksButton = view.findViewById(R.id.gradedTasksButton)
        backButton = view.findViewById(R.id.backButton)
        clearChatButton = view.findViewById(R.id.clearChatButton)
        loadingProgressBar = view.findViewById(R.id.loadingProgressBar)
        
        // Initialize task overlay components
        taskListOverlay = view.findViewById(R.id.taskListOverlay)
        taskListOverlayBackground = view.findViewById(R.id.taskListOverlayBackground)
        taskListRecyclerView = view.findViewById(R.id.taskListRecyclerView)
        courseNameTextView = view.findViewById(R.id.courseNameTextView)
        closeTaskListButton = view.findViewById(R.id.closeTaskListButton)
        
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
            currentFileContext = withContext(Dispatchers.IO) {
                database.fileContextDao().getFileContextBySubmission(submissionId)
            }
            
            // Cargar información de la tarea, tema y curso
            updateCourseInfo(submissionId)
            
            // LOGGING DETALLADO PARA DEBUGGING
            Log.d("ChatBotFragment", "==============================================")
            Log.d("ChatBotFragment", "🔍 LOADING FILE CONTEXT BY ID:")
            Log.d("ChatBotFragment", "==============================================")
            Log.d("ChatBotFragment", "submissionId: $submissionId")
            Log.d("ChatBotFragment", "currentFileContext es null?: ${currentFileContext == null}")
            if (currentFileContext != null) {
                Log.d("ChatBotFragment", "currentFileContext.contentSummary: '${currentFileContext!!.contentSummary}'")
                Log.d("ChatBotFragment", "currentFileContext.fileName: '${currentFileContext!!.fileName}'")
                Log.d("ChatBotFragment", "currentFileContext.fileType: '${currentFileContext!!.fileType}'")
                Log.d("ChatBotFragment", "contentSummary length: ${currentFileContext!!.contentSummary?.length ?: 0}")
                Log.d("ChatBotFragment", "¿contentSummary vacío?: ${currentFileContext!!.contentSummary.isNullOrEmpty()}")
            }
            Log.d("ChatBotFragment", "==============================================")

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
                    ChatMessage(
                        message = "📁 **Archivo cargado exitosamente**\n\n" +
                                "📄 Nombre: ${currentFileContext!!.fileName}\n" +
                                "🔧 Tipo: ${currentFileContext!!.fileType}\n" +
                                "📊 Contenido: ${currentFileContext!!.fileContent.length} caracteres\n\n" +
                                "✅ Puedes hacerme preguntas sobre este archivo y te ayudaré con el análisis.",
                        isFromUser = false,
                        sessionId = sessionId
                    )
                }

                withContext(Dispatchers.IO) {
                    val savedIdCtx = database.chatMessageDao().insertMessage(contextMessage)
                    try {
                        val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                        val toSend = contextMessage.copy(id = savedIdCtx)
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
        messagesRecyclerView.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true // Start from bottom
            }
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
                sessionId = sessionId
            )
            withContext(Dispatchers.IO) {
                val savedUserId = database.chatMessageDao().insertMessage(userMessage)
                try {
                    val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                    val toSend = userMessage.copy(id = savedUserId)
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
            
            // FALLBACK: Si taskDescription está vacío, obtener el último contentSummary de la base de datos
            if (effectiveTaskDescription.isEmpty()) {
                Log.d("ChatBotFragment", "🔄 taskDescription vacío, intentando fallback con último contentSummary")
                effectiveTaskDescription = withContext(Dispatchers.IO) {
                    try {
                        val latestContentSummary = database.fileContextDao().getLatestContentSummary()
                        Log.d("ChatBotFragment", "📋 Último contentSummary obtenido: '$latestContentSummary'")
                        latestContentSummary ?: ""
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "❌ Error obteniendo último contentSummary: ${e.message}")
                        ""
                    }
                }
            }

            // Si el mensaje referencia una tarea con #<índice>, usar el contexto de esa tarea
            val refMatch = Regex("#(\\d+)").find(messageText)
            if (refMatch != null && courseId != -1L) {
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
                        val username = com.example.tareamov.util.SessionManager.getInstance(requireContext()).getUsername()
                        if (!username.isNullOrEmpty()) {
                            withContext(Dispatchers.IO) {
                                try {
                                    val submission = database.taskSubmissionDao().getUserSubmissionForTask(referencedTask.taskId, username)
                                    if (submission != null) {
                                        val fc = database.fileContextDao().getFileContextBySubmission(submission.id)
                                        if (fc != null) {
                                            effectiveTaskDescription = fc.contentSummary ?: (referencedTask.taskDescription ?: "")
                                            effectiveFileContent = fc.fileContent
                                        } else {
                                            // Sin FileContext: usar descripción de la tarea al menos
                                            effectiveTaskDescription = referencedTask.taskDescription ?: ""
                                        }
                                    } else {
                                        // Usuario no tiene entrega para esta tarea: usar su descripción
                                        effectiveTaskDescription = referencedTask.taskDescription ?: ""
                                    }
                                } catch (e: Exception) {
                                    Log.e("ChatBotFragment", "Error resolviendo contexto de tarea referenciada", e)
                                    // Mantener contexto por defecto
                                }
                            }
                        } else {
                            // Sin usuario en sesión: usar solo descripción de la tarea
                            effectiveTaskDescription = referencedTask.taskDescription ?: ""
                        }
                    }
                }
            }
            
            Log.d("ChatBotFragment", "==============================================")
            Log.d("ChatBotFragment", "📋 CONTEXTOS QUE SE ENVIARÁN AL MICROSERVICIO:")
            Log.d("ChatBotFragment", "==============================================")
            Log.d("ChatBotFragment", "currentFileContext es null?: ${currentFileContext == null}")
            if (currentFileContext != null) {
                Log.d("ChatBotFragment", "currentFileContext.submissionId: ${currentFileContext!!.submissionId}")
                Log.d("ChatBotFragment", "currentFileContext.contentSummary: '${currentFileContext!!.contentSummary}'")
                Log.d("ChatBotFragment", "currentFileContext.fileName: '${currentFileContext!!.fileName}'")
            }
            Log.d("ChatBotFragment", "taskDescription (descripción de la tarea): '$effectiveTaskDescription'")
            Log.d("ChatBotFragment", "fileContent (contenido del archivo): longitud ${effectiveFileContent.length} caracteres")
            Log.d("ChatBotFragment", "taskDescription después de fallback: '$effectiveTaskDescription'")
            Log.d("ChatBotFragment", "Longitud taskDescription: ${effectiveTaskDescription.length}")
            Log.d("ChatBotFragment", "Longitud fileContent: ${effectiveFileContent.length}")
            Log.d("ChatBotFragment", "==============================================")

            try {
                val response = withContext(Dispatchers.IO) {
                    try {
                        val body = com.example.tareamov.network.MicroservicioPromptRequest(
                            prompt = messageText,
                            ollamaUrl = OLLAMA_URL,
                            taskDescription = if (effectiveTaskDescription.isNotEmpty()) effectiveTaskDescription else null,
                            fileContent = if (effectiveFileContent.isNotEmpty()) effectiveFileContent else null
                        )   
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "📤 ENVIANDO AL MICROSERVICIO:")
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "prompt: '$messageText'")
                        Log.d("ChatBotFragment", "ollamaUrl: '$OLLAMA_URL'")
                        Log.d("ChatBotFragment", "taskDescription (descripción): '$effectiveTaskDescription'")
                        Log.d("ChatBotFragment", "fileContent (archivo): ${effectiveFileContent.length} caracteres")
                        Log.d("ChatBotFragment", "==============================================")
                        
                        // Usar suspend function en lugar de .execute() para mejor manejo de timeouts
                        val res = microservicioApi.procesarPrompt(body)
                        Log.d("ChatBotFragment", "✅ RESPUESTA RECIBIDA DEL MICROSERVICIO:")
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "📥 RESPUESTA COMPLETA DEL MODELO:")
                        Log.d("ChatBotFragment", "respuesta_texto completa: '${res.respuesta_texto}'")
                        Log.d("ChatBotFragment", "Longitud total: ${res.respuesta_texto?.length ?: 0} caracteres")
                        Log.d("ChatBotFragment", "==============================================")
                        Log.d("ChatBotFragment", "✅ ENVIANDO RESPUESTA COMPLETA AL CHAT (SIN FILTROS)")
                        Log.d("ChatBotFragment", "==============================================")
                        
                        // Devolver la respuesta COMPLETA tal como la envía el modelo, incluyendo formato
                        res.respuesta_texto ?: "El modelo no devolvió una respuesta válida"
                    } catch (e: HttpException) {
                        Log.e("ChatBotFragment", "❌ HttpException: ${e.message}")
                        Log.e("ChatBotFragment", "❌ HTTP Code: ${e.code()}")
                        Log.e("ChatBotFragment", "❌ HTTP Response: ${e.response()}")
                        try {
                            val errorBody = e.response()?.errorBody()?.string()
                            Log.e("ChatBotFragment", "❌ Error Body: $errorBody")
                            "Error del microservicio (HTTP ${e.code()}): $errorBody"
                        } catch (ex: Exception) {
                            "Error al conectar con el microservicio (HTTP ${e.code()}): ${e.message()}"
                        }
                    } catch (e: SocketTimeoutException) {
                        Log.e("ChatBotFragment", "❌ SocketTimeoutException: ${e.message}")
                        "El modelo está tardando más de lo esperado. Intenta nuevamente en unos minutos."
                    } catch (e: java.net.ConnectException) {
                        Log.e("ChatBotFragment", "❌ ConnectException: ${e.message}")
                        "No se puede conectar con el microservicio. Verifica que esté ejecutándose en $MICROSERVICIO_BASE_URL"
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "❌ Exception: ${e.message}")
                        Log.e("ChatBotFragment", "❌ Exception Type: ${e::class.java.simpleName}")
                        Log.e("ChatBotFragment", "❌ Stack Trace: ${e.stackTrace.contentToString()}")
                        "Error inesperado: ${e.message}"
                    }
                }
                
                // Detectar si la respuesta contiene una calificación
                val hasCalification = detectCalification(messageText, response)
                val calificationValue = extractCalificationValue(response)
                
                val botMessage = ChatMessage(
                    message = response,
                    isFromUser = false,
                    sessionId = sessionId,
                    hasCalification = hasCalification,
                    calificationValue = calificationValue,
                    calificationAdded = false
                )
                withContext(Dispatchers.IO) {
                    val savedBotId = database.chatMessageDao().insertMessage(botMessage)
                    try {
                        val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                        val toSend = botMessage.copy(id = savedBotId)
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
                    sessionId = sessionId
                )
                withContext(Dispatchers.IO) {
                    val savedErrId = database.chatMessageDao().insertMessage(errorMessage)
                    try {
                        val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                        val toSend = errorMessage.copy(id = savedErrId)
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

    private fun testOllamaConnectionOnStart() {
        lifecycleScope.launch {
            try {
                // Test de conectividad con el microservicio PRIMERO
                Log.d("ChatBotFragment", "🔗 Probando conectividad con microservicio...")
                val microserviceAvailable = withContext(Dispatchers.IO) {
                    try {
                        val okHttpClient = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        val request = okhttp3.Request.Builder()
                            .url(MICROSERVICIO_BASE_URL)
                            .build()
                        val response = okHttpClient.newCall(request).execute()
                        val body = response.body?.string()
                        Log.d("ChatBotFragment", "✅ Microservicio responde: $body")
                        response.isSuccessful
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "❌ Error conectando con microservicio: ${e.message}")
                        false
                    }
                }
                
                if (!microserviceAvailable) {
                    // Mostrar mensaje de error en el chat
                    val errorMessage = ChatMessage(
                        message = "⚠️ **Advertencia de Conectividad**\n\n" +
                                "❌ No se puede conectar con el microservicio de IA\n" +
                                "🌐 URL: $MICROSERVICIO_BASE_URL\n" +
                                "💡 Verifica que el microservicio esté ejecutándose\n\n" +
                                "El chat funcionará en modo básico.",
                        isFromUser = false,
                        sessionId = sessionId
                    )
                    withContext(Dispatchers.IO) {
                        database.chatMessageDao().insertMessage(errorMessage)
                    }
                } else {
                    Log.d("ChatBotFragment", "✅ Microservicio está disponible")
                }

                Log.d("ChatBotFragment", "🔍 Iniciando prueba de conexión con Ollama...")

                // Limpiar cache para forzar nuevos intentos
                aiAnalysisService.clearEndpointCache()

                // Obtener endpoints detectados para mostrar información
                val detectedEndpoints = aiAnalysisService.getDetectedEndpoints()
                Log.d("ChatBotFragment", "📡 Endpoints detectados: ${detectedEndpoints.size}")

                val connectionResult = withContext(Dispatchers.IO) {
                    aiAnalysisService.testOllamaConnection()
                }
                val (serverConnected, graniteAvailable) = connectionResult

                // Ya no se agrega el mensaje de 'Asistente de IA Activado' al chat

            } catch (e: Exception) {
                Log.e("ChatBotFragment", "❌ Error probando conexión con Ollama", e)

                val errorMessage = ChatMessage(
                    message = "🤖 **Error de Conexión**\n\n" +
                            "❌ Error al conectar con el servicio de IA\n" +
                            "📝 Funcionando en modo básico\n" +
                            "🔧 Revisa la configuración de red\n\n" +
                            "Error: ${e.message}",
                    isFromUser = false,
                    sessionId = sessionId
                )

                withContext(Dispatchers.IO) {
                    database.chatMessageDao().insertMessage(errorMessage)
                }
            }
        }
    }

    /**
     * Intenta generar una respuesta utilizando diferentes modelos de Ollama en caso de error
     */
    /**
     * Intenta generar una respuesta con múltiples modelos de IA, fallback en caso de error
     */
    private suspend fun tryMultipleModels(userMessage: String, fileContext: FileContext? = null): String {
        // Verificar primero si Granite está disponible, si no, mostrar mensaje de instalación
        val (serverConnected, graniteAvailable) = aiAnalysisService.testOllamaConnection()

        if (!serverConnected) {
            return "⚠️ **Error de conexión con Ollama**\n\n" +
                    "No se pudo conectar al servidor Ollama. Por favor verifica que:\n\n" +
                    "1. El servidor Ollama esté ejecutándose\n" +
                    "2. El puerto 11435 esté abierto y accesible\n" +
                    "3. La conexión de red entre la app y el servidor funcione correctamente\n\n" +
                    "Ejecuta el siguiente comando para iniciar Ollama:\n" +
                    "```\nollama serve\n```"
        }

        if (!graniteAvailable) {
            return "⚠️ **Modelo Granite no encontrado**\n\n" +
                    "El modelo requerido '**granite-code**' no está instalado.\n\n" +
                    "Por favor instálalo con el siguiente comando:\n" +
                    "```\nollama run granite-code\n```\n\n" +
                    "Esta aplicación está diseñada para funcionar óptimamente con el modelo Granite y no " +
                    "utilizará otros modelos como alternativa."
        }

        // Si Granite está disponible, intentar usarlo
        try {
            Log.d("ChatBotFragment", "Usando el modelo Granite")
            // Siempre analizar con el modelo, independientemente de si es una consulta sobre archivos o no
            return aiAnalysisService.analyzeWithContext(
                userMessage = userMessage,
                fileContext = fileContext,
                model = "granite-code"
            )
        } catch (e: Exception) {
            Log.e("ChatBotFragment", "Error con modelo Granite: ${e.message}")

            // Si el error es específicamente "modelo no encontrado", mostrar mensaje de instalación
            if (e.message?.contains("not found") == true || e.message?.contains("404") == true) {
                return "⚠️ **Modelo Granite no encontrado**\n\n" +
                        "El modelo '**granite-code**' no está disponible en el servidor Ollama.\n\n" +
                        "Por favor instálalo con el siguiente comando:\n" +
                        "```\nollama run granite-code\n```\n\n" +
                        "Esta aplicación está diseñada para funcionar exclusivamente con este modelo."
            }

            // Para otros errores, generar respuesta de fallback
            return "Lo siento, tuve un problema al procesar tu mensaje. Error: ${e.message}\n\n" +
                    generateFallbackResponse(userMessage)
        }
    }

    /**
     * Detecta si el mensaje del usuario solicita calificación y si la respuesta del bot contiene una calificación
     */
    private fun detectCalification(userMessage: String, botResponse: String): Boolean {
        val userMessageLower = userMessage.lowercase()
        val botResponseLower = botResponse.lowercase()
        
        // Palabras clave que indican solicitud de calificación
        val calificationKeywords = listOf(
            "calificación", "calificacion", "nota", "puntaje", "puntuación", "puntuacion",
            "nota", "score", "rating", "evaluación", "evaluacion", "qué nota", "que nota",
            "cuánto saqué", "cuanto saque", "mi nota", "mi calificación", "mi calificacion"
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
            Regex("calificación:\\s*(\\d+/\\d+)", RegexOption.IGNORE_CASE),
            Regex("calificacion:\\s*(\\d+/\\d+)", RegexOption.IGNORE_CASE),
            Regex("(\\d+/\\d+)"),
            Regex("nota:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("calificación:\\s*(\\d+)", RegexOption.IGNORE_CASE),
            Regex("calificacion:\\s*(\\d+)", RegexOption.IGNORE_CASE)
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
                                // Obtener la entrega por ID
                                val taskSubmission = database.taskSubmissionDao().getSubmissionById(targetSubmissionId)
                                if (taskSubmission != null) {
                                    // Actualizar con la nueva calificación y feedback
                                    val updatedSubmission = taskSubmission.copy(
                                        grade = gradeFloat,
                                        feedback = feedback
                                    )
                                    database.taskSubmissionDao().updateSubmission(updatedSubmission)
                                    
                                    // Obtener información de la tarea para logging
                                    val task = database.taskDao().getTaskById(taskSubmission.taskId)
                                    val taskName = task?.name ?: "Tarea desconocida"
                                    
                                    Log.d("ChatBotFragment", "✅ TaskSubmission actualizada exitosamente:")
                                    Log.d("ChatBotFragment", "   - ID: $targetSubmissionId")
                                    Log.d("ChatBotFragment", "   - Tarea: $taskName")
                                    Log.d("ChatBotFragment", "   - Estudiante: ${taskSubmission.studentUsername}")
                                    Log.d("ChatBotFragment", "   - Grade: $gradeFloat")
                                    Log.d("ChatBotFragment", "   - Feedback: $feedback")
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
                    val messageIndex = allMessages.indexOfFirst { it.id == originalMessage.id }
                    
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
                            calificationAdded = false
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
                            calificationAdded = false
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
                // Usar directamente la lista en memoria que se va acumulando
                if (gradedTasksList.isEmpty()) {
                    // Si la lista en memoria está vacía, intentar cargar desde la base de datos
                    val gradedTasksFromDB = loadGradedTasksFromChat()
                    gradedTasksList.clear()
                    gradedTasksList.addAll(gradedTasksFromDB)
                }
                
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
    private suspend fun loadGradedTasksFromChat(): List<GradedTaskItem> {
        return withContext(Dispatchers.IO) {
            try {
                val gradedTasks = mutableListOf<GradedTaskItem>()
                val processedTaskSubmissionIds = mutableSetOf<Long>() // Para evitar duplicados por ID
                
                Log.d("ChatBotFragment", "Cargando tareas calificadas desde TaskSubmissionDao")
                
                // Obtener todas las submissions para el curso actual
                val allSubmissions = if (courseId != -1L) {
                    database.taskSubmissionDao().getSubmissionsByCourse(courseId)
                } else {
                    database.taskSubmissionDao().getAllTaskSubmissions()
                }
                
                Log.d("ChatBotFragment", "Total submissions encontradas: ${allSubmissions.size}")
                
                // Obtener mensajes con calificaciones para mapear con submissions
                val messagesWithGrades = database.chatMessageDao().getMessagesWithCalifications()
                Log.d("ChatBotFragment", "Mensajes con calificaciones: ${messagesWithGrades.size}")
                
                for (submission in allSubmissions) {
                    // Evitar duplicados por submission ID
                    if (processedTaskSubmissionIds.contains(submission.id)) {
                        continue
                    }
                    
                    try {
                        // Buscar si esta submission tiene calificación
                        val fileContext = database.fileContextDao().getFileContextBySubmission(submission.id)
                        var gradeMessage: ChatMessage? = null
                        
                        // Buscar mensaje de calificación asociado usando timestamps y contexto
                        for (message in messagesWithGrades) {
                            if (message.calificationValue != null && !message.calificationValue.isNullOrEmpty()) {
                                // Asociar mensaje con submission basado en proximidad temporal y contexto
                                // Si el mensaje fue creado después de la submission y tiene calificación válida
                                if (fileContext != null && message.timestamp >= submission.submissionDate) {
                                    gradeMessage = message
                                    break
                                } else if (fileContext == null && message.calificationValue.isNotBlank()) {
                                    // Fallback: usar cualquier mensaje con calificación si no hay FileContext
                                    gradeMessage = message
                                    break
                                }
                            }
                        }
                        
                        // Si encontramos una calificación, agregar a la lista
                        if (gradeMessage != null || fileContext != null) {
                            val task = database.taskDao().getTaskById(submission.taskId)
                            val topic = task?.let { database.topicDao().getTopicById(it.topicId) }
                            
                            if (task != null) {
                                val grade = gradeMessage?.calificationValue ?: "Pendiente"
                                val feedback = gradeMessage?.message ?: "Sin feedback disponible"
                                
                                val gradedTask = GradedTaskItem(
                                    taskId = task.id,
                                    taskName = task.name,
                                    taskDescription = task.description ?: "Sin descripción",
                                    topicName = topic?.name ?: "Sin tema",
                                    index = submission.id.toInt(), // USAR EL ID DE LA SUBMISSION EN LUGAR DE taskIndex
                                    grade = grade,
                                    feedback = feedback
                                )
                                
                                gradedTasks.add(gradedTask)
                                processedTaskSubmissionIds.add(submission.id)
                                
                                Log.d("ChatBotFragment", "Tarea calificada agregada: ${task.name} - Grade: $grade - SubmissionId: ${submission.id}")
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
     * Muestra el overlay con la lista de tareas del curso
     */
    private fun showTaskListOverlay() {
        if (courseId == -1L) {
            Toast.makeText(context, "No hay información del curso disponible", Toast.LENGTH_SHORT).show()
            messageEditText.setText("") // Clear the # symbol
            return
        }

        lifecycleScope.launch {
            try {
                val tasks = loadCourseTasksForOverlay()
                taskOverlayAdapter.updateTasks(tasks)
                courseNameTextView.text = courseTitle
                taskListOverlayBackground.visibility = View.VISIBLE
                taskListOverlay.visibility = View.VISIBLE
                
                // Animate the overlay appearance
                taskListOverlayBackground.alpha = 0f
                taskListOverlay.alpha = 0f
                taskListOverlayBackground.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                taskListOverlay.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
                    
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error loading tasks for overlay", e)
                Toast.makeText(context, "Error al cargar las tareas", Toast.LENGTH_SHORT).show()
                messageEditText.setText("") // Clear the # symbol
            }
        }
    }

    /**
     * Oculta el overlay de la lista de tareas
     */
    private fun hideTaskListOverlay() {
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
     * Carga las tareas del curso para mostrar en el overlay del comando #
     * Ahora usa los IDs de TaskSubmission para que coincidan con gradedTasksButton
     */
    private suspend fun loadCourseTasksForOverlay(): List<TaskItem> {
        return withContext(Dispatchers.IO) {
            try {
                // Obtener todas las submissions del curso actual
                val allSubmissions = if (courseId != -1L) {
                    database.taskSubmissionDao().getSubmissionsByCourse(courseId)
                } else {
                    database.taskSubmissionDao().getAllTaskSubmissions()
                }
                
                val taskItems = mutableListOf<TaskItem>()
                val processedTaskIds = mutableSetOf<Long>() // Para evitar duplicados por tarea
                
                Log.d("ChatBotFragment", "Cargando tareas del curso con IDs de submission...")
                
                for (submission in allSubmissions) {
                    try {
                        val task = database.taskDao().getTaskById(submission.taskId)
                        if (task != null && !processedTaskIds.contains(task.id)) {
                            val topic = database.topicDao().getTopicById(task.topicId)
                            
                            taskItems.add(
                                TaskItem(
                                    taskId = task.id,
                                    taskName = task.name,
                                    taskDescription = task.description ?: "Sin descripción",
                                    topicName = topic?.name ?: "Sin tema",
                                    index = submission.id.toInt() // USAR EL ID DE LA SUBMISSION
                                )
                            )
                            
                            processedTaskIds.add(task.id)
                            Log.d("ChatBotFragment", "Tarea agregada: ${task.name} - SubmissionId: ${submission.id}")
                        }
                    } catch (e: Exception) {
                        Log.e("ChatBotFragment", "Error procesando submission ${submission.id} para overlay: ${e.message}")
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
        lifecycleScope.launch {
            try {
                // Buscar el TaskSubmission correspondiente a esta tarea
                val submissionId = task.index.toLong() // El index ahora es el ID de submission
                
                // Cargar el FileContext de esta submission
                val fileContext = withContext(Dispatchers.IO) {
                    database.fileContextDao().getFileContextBySubmission(submissionId)
                }
                
                if (fileContext != null) {
                    // Establecer este contexto como el contexto actual
                    currentFileContext = fileContext
                    
                    // Actualizar información del curso
                    updateCourseInfo(submissionId)
                    
                    Log.d("ChatBotFragment", "Contexto de archivo cargado para tarea seleccionada:")
                    Log.d("ChatBotFragment", "- FileName: ${fileContext.fileName}")
                    Log.d("ChatBotFragment", "- ContentSummary length: ${fileContext.contentSummary?.length ?: 0}")
                    Log.d("ChatBotFragment", "- FileContent length: ${fileContext.fileContent.length}")
                    
                    // Mostrar mensaje informativo sobre el contexto cargado
                    val contextMessage = ChatMessage(
                        message = "📄 **Contexto de tarea cargado**\n\n" +
                                "📝 Tarea: ${task.taskName}\n" +
                                "📁 Archivo: ${fileContext.fileName}\n" +
                                "🔧 Tipo: ${fileContext.fileType}\n" +
                                "📊 Contenido: ${fileContext.fileContent.length} caracteres\n\n" +
                                "✅ Ahora puedes hacer preguntas sobre esta tarea y su entrega.",
                        isFromUser = false,
                        sessionId = sessionId,
                        timestamp = System.currentTimeMillis()
                    )
                    
                    // Insertar mensaje en la base de datos
                    withContext(Dispatchers.IO) {
                        database.chatMessageDao().insertMessage(contextMessage)
                    }
                } else {
                    Log.w("ChatBotFragment", "No se encontró FileContext para submission ID: $submissionId")
                    Toast.makeText(context, "No se encontró contexto para esta tarea", Toast.LENGTH_SHORT).show()
                }
                
                // Clear the # and replace with task context
                val taskContext = "#${task.index} ${task.taskName}"
                messageEditText.setText(taskContext)
                messageEditText.setSelection(taskContext.length) // Move cursor to end
                
                // Hide the overlay
                hideTaskListOverlay()
                
                // Show toast indicating the task was selected
                Toast.makeText(context, "Tarea seleccionada: ${task.taskName}", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error al cargar contexto de tarea seleccionada", e)
                Toast.makeText(context, "Error al cargar contexto de la tarea", Toast.LENGTH_SHORT).show()
                
                // Fallback: al menos establecer el texto de la tarea
                val taskContext = "#${task.index} ${task.taskName}"
                messageEditText.setText(taskContext)
                messageEditText.setSelection(taskContext.length)
                hideTaskListOverlay()
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
                                val course = database.videoDao().getVideoById(topic.courseId)
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
                    Log.w("ChatBotFragment", "No se pudo cargar la información de la tarea para submissionId: $submissionId")
                }
            } catch (e: Exception) {
                Log.e("ChatBotFragment", "Error cargando información de la tarea", e)
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
        Log.d("ChatBotFragment", "Cursor position: $cursorPosition, Text: '$text', Should show: $shouldShowTaskList")
        
        if (shouldShowTaskList && taskListOverlay.visibility != View.VISIBLE) {
            Log.d("ChatBotFragment", "Mostrando lista de tareas")
            showTaskListOverlay()
        } else if (!shouldShowTaskList && taskListOverlay.visibility == View.VISIBLE) {
            Log.d("ChatBotFragment", "Ocultando lista de tareas")
            hideTaskListOverlay()
        }
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
        
        // Caso 2: El cursor está en medio o al final de una referencia existente como "#1", "#2", etc.
        if (cursorPosition > 1) {
            // Buscar hacia atrás para encontrar un '#' seguido de dígitos
            var position = cursorPosition - 1
            var foundDigits = false
            
            // Retroceder mientras encontremos dígitos
            while (position >= 0 && text[position].isDigit()) {
                foundDigits = true
                position--
            }
            
            // Si encontramos dígitos y el carácter anterior es '#'
            if (foundDigits && position >= 0 && text[position] == '#') {
                // Verificar que estamos dentro o al final de esta referencia
                val hashtagStart = position
                val hashtagEnd = hashtagStart + 1
                
                // Encontrar el final de la referencia (después del último dígito)
                var referenceEnd = hashtagEnd
                while (referenceEnd < text.length && text[referenceEnd].isDigit()) {
                    referenceEnd++
                }
                
                // El cursor debe estar entre el '#' y el final de la referencia (inclusive)
                return cursorPosition >= hashtagEnd && cursorPosition <= referenceEnd
            }
        }
        
        return false
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
}