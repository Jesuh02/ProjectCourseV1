package com.example.tareamov.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.databinding.FragmentDatabaseQueryBinding
import com.example.tareamov.service.DatabaseQueryService
import com.example.tareamov.service.LocalLlamaService
import com.example.tareamov.service.MCPService
import com.example.tareamov.service.MSPClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import com.example.tareamov.ui.adapter.DatabaseChatAdapter
import com.example.tareamov.service.LocalLlamaService.ModelDownloadWorker
import com.example.tareamov.util.SessionManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

// Enhanced Chat adapter for RecyclerView with smooth animations
class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val messages = mutableListOf<ChatMessage>()
    private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_SYSTEM = 2
    }

    // ViewHolder for user messages
    class UserMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val messageTime: TextView = view.findViewById(R.id.messageTime)
        val userAvatar: android.widget.ImageView = view.findViewById(R.id.userAvatar)
    }

    // ViewHolder for system messages
    class SystemMessageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val messageTime: TextView = view.findViewById(R.id.messageTime)
        val systemAvatar: android.widget.ImageView = view.findViewById(R.id.systemAvatar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_USER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_user, parent, false)
                UserMessageViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_message_system, parent, false)
                SystemMessageViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val message = messages[position]
        val formattedTime = dateFormat.format(Date(message.timestamp))

        when (holder) {
            is UserMessageViewHolder -> {
                holder.messageText.text = message.text
                holder.messageTime.text = formattedTime
                // Add animation for new messages
                if (position == messages.size - 1) {
                    animateMessage(holder.itemView)
                }
            }
            is SystemMessageViewHolder -> {
                holder.messageText.text = message.text
                holder.messageTime.text = formattedTime
                // Add animation for new messages
                if (position == messages.size - 1) {
                    animateMessage(holder.itemView)
                }
            }
        }
    }

    private fun animateMessage(view: View) {
        view.alpha = 0f
        view.translationY = 50f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    override fun getItemCount() = messages.size

    override fun getItemViewType(position: Int): Int {
        return if (messages[position].isUser) VIEW_TYPE_USER else VIEW_TYPE_SYSTEM
    }

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(newText: String) {
        if (messages.isNotEmpty() && !messages.last().isUser) {
            val lastIndex = messages.size - 1
            messages[lastIndex] = messages[lastIndex].copy(text = newText)
            notifyItemChanged(lastIndex)
        }
    }

    fun removeTypingIndicator() {
        val typingIndex = messages.indexOfFirst { it.isTyping }
        if (typingIndex != -1) {
            messages.removeAt(typingIndex)
            notifyItemRemoved(typingIndex)
        }
    }

    fun addTypingIndicator() {
        removeTypingIndicator() // Remove existing typing indicator
        val typingMessage = ChatMessage("", false, isTyping = true)
        messages.add(typingMessage)
        notifyItemInserted(messages.size - 1)
    }

    fun getMessages(): List<ChatMessage> {
        return messages.toList()
    }

    fun restoreMessages(savedMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(savedMessages)
        notifyDataSetChanged()
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        notifyItemRangeRemoved(0, size)
    }

    fun removeMessageById(messageId: String) {
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index != -1) {
            messages.removeAt(index)
            notifyItemRemoved(index)
        }
    }
}

class DatabaseQueryFragment : Fragment(), SessionManager.Companion.UserChangeListener {

    private var _binding: FragmentDatabaseQueryBinding? = null
    private val binding get() = _binding!!
    private lateinit var mcpService: MCPService
    private lateinit var resultTextView: TextView
    private lateinit var database: AppDatabase
    private var currentChart: View? = null
    private lateinit var localLlamaService: LocalLlamaService
    private lateinit var chatAdapter: DatabaseChatAdapter
    private lateinit var databaseQueryService: DatabaseQueryService
    private lateinit var sessionManager: SessionManager
    
    // MCP HTTP Client for tareamov-mcp-server (connects via HTTP to Node.js server)
    private lateinit var mcpHttpClient: com.example.tareamov.service.MCPHttpClient
    
    // MCP Tools adapter
    private lateinit var mcpToolsAdapter: com.example.tareamov.ui.adapter.MCPToolsAdapter
    private val mcpTools = mutableListOf<com.example.tareamov.ui.model.MCPTool>()
    private var isMCPToolbarVisible = false
    
    // Enhanced chat state management per user
    private val chatHistory = mutableListOf<ChatMessage>()
    private var isProcessingQuery = false
    private var currentConversationContext = mutableListOf<String>()
    private var totalMessageCount = 0
    private var isScrolledToBottom = true
    private var currentUser: String? = null
    // Keep the last Supabase GET URL for display with final results
    private var lastSupabaseUrl: String? = null
    
    // User-specific SharedPreferences for better persistence
    private val chatPrefs by lazy {
        sessionManager.getChatPreferences(requireContext())
    }

    // Session management per user
    private var currentSessionId: String = ""
    private val maxMessagesPerSession = 1000 // Prevent memory issues

    companion object {
        private const val TAG = "DatabaseQueryFragment"
        private const val CHAT_HISTORY_KEY = "saved_chat_messages"
        private const val SESSION_ID_KEY = "current_session_id"
        private const val MESSAGE_COUNT_KEY = "total_message_count"
        private const val MAX_CONTEXT_MESSAGES = 10 // Keep last 10 messages for context
        private const val SCROLL_THRESHOLD = 5 // Show scroll to bottom after 5+ messages
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDatabaseQueryBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var modelDownloadRequest: OneTimeWorkRequest? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize SessionManager and register for user changes
        sessionManager = SessionManager.getInstance(requireContext())
        SessionManager.addUserChangeListener(this)
        currentUser = sessionManager.getUsername()

        mcpService = MCPService(requireContext())
        database = AppDatabase.getDatabase(requireContext())
        databaseQueryService = DatabaseQueryService(requireContext())
        
        // Initialize MCP HTTP Client for tareamov-mcp-server
        mcpHttpClient = com.example.tareamov.service.MCPHttpClient(requireContext())
        
        // Initialize MCP connection in background
        viewLifecycleOwner.lifecycleScope.launch {
            val initialized = mcpHttpClient.initialize()
            if (initialized) {
                Log.d(TAG, "✅ MCP HTTP client connected to server at http://10.0.2.2:3000")
                
                // Load MCP tools
                loadMCPTools()
                
                withContext(Dispatchers.Main) {
                    addMessageToChat("✅ Conectado a servidor MCP tareamov-mcp-server (HTTP)", false)
                }
            } else {
                Log.w(TAG, "⚠️ MCP HTTP client connection failed, using fallback")
            }
        }

        // Initialize UI components
        setupUIComponents()

        // Setup chat RecyclerView with enhanced scrolling
        setupChatRecyclerView()
        
        // Setup MCP toolbar
        setupMCPToolbar()

        // Setup floating action buttons
        setupFloatingActionButtons()

        // Initialize LocalLlamaService and trigger model download if needed
        setupLocalLlamaService()

        // Register SupabaseClient request listener so we can surface the last query URL in the UI
        com.example.tareamov.service.SupabaseClient.setRequestListener { url ->
            // We're possibly on a background thread; post to main thread
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                try {
                    // Store the last Supabase GET URL so it can be appended when results are shown
                    lastSupabaseUrl = url
                } catch (t: Throwable) {
                    Log.w("DatabaseQueryFragment", "Failed to update Supabase URL display", t)
                }
            }
        }

        // Set up enhanced UI interactions
        setupEnhancedUI()

        // Set up send button click listener
        setupSendButton()

        // Set up chart control buttons
        setupChartControls()

        // Check server connection status
        checkServerStatus()

        // Initialize or restore session for current user
        initializeSession()

        // Add welcome message if no history exists for this user
        if (chatAdapter.getMessages().isEmpty()) {
            addWelcomeMessage()
        }
    }

    private fun setupUIComponents() {
        // Find the result TextView directly using findViewById with the resource ID
        resultTextView = binding.resultText

        // Set initial text
        resultTextView.text = "Sistema MCP - Consulta Inteligente\n\nUtiliza lenguaje natural para consultar la base de datos. El sistema procesará tu consulta y mostrará los resultados relevantes."

        // Update user indicator
        updateUserIndicator()

        // Make brain icon visible initially
        binding.centerBrainIcon.visibility = View.VISIBLE

        // Load logo image from drawable resources
        try {
            binding.centerBrainIcon.setImageResource(R.drawable.logo)
            Log.d("DatabaseQueryFragment", "Logo loaded successfully from drawable resources")
        } catch (e: Exception) {
            Log.w("DatabaseQueryFragment", "Could not load logo from drawable: ${e.message}")
        }
    }

    // Show the two valid roles to the user when requested
    private fun showValidRoles() {
        val roles = com.example.tareamov.service.MSPClient.VALID_ROLES
        val message = "Roles válidos en el sistema:\n" + roles.joinToString(separator = "\n") { "• $it" }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Roles del sistema")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
    
    private fun updateUserIndicator() {
        val username = sessionManager.getUsername() ?: "Usuario"
        binding.currentUserText.text = username
        Log.d("DatabaseQueryFragment", "Updated user indicator to: $username")
    }

    private fun setupChatRecyclerView() {
        chatAdapter = DatabaseChatAdapter { message ->
            handleEditUserMessage(message)
        }
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true // Messages appear from bottom
            }
            adapter = chatAdapter
            itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator().apply {
                addDuration = 200
                removeDuration = 200
                moveDuration = 200
                changeDuration = 200
            }
            
            // Enhanced scroll listener for better UX
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    
                    val layoutManager = recyclerView.layoutManager as LinearLayoutManager
                    val lastVisiblePosition = layoutManager.findLastVisibleItemPosition()
                    val totalItems = chatAdapter.itemCount
                    
                    // Update scroll state
                    isScrolledToBottom = lastVisiblePosition >= totalItems - 2
                    
                    // Show/hide scroll to bottom button
                    if (totalItems > SCROLL_THRESHOLD && _binding != null) {
                        binding.fabScrollToBottom.visibility = 
                            if (isScrolledToBottom) View.GONE else View.VISIBLE
                    }
                    
                    // Auto-save chat periodically when scrolling stops
                    view?.removeCallbacks(autoSaveRunnable)
                    view?.postDelayed(autoSaveRunnable, 2000)
                }
            })
            
            // Smooth scroll behavior with better performance
            setHasFixedSize(true)
            isNestedScrollingEnabled = true
        }
    }
    
    private val autoSaveRunnable = Runnable {
        saveChatHistory()
    }

    private fun setupFloatingActionButtons() {
        // Scroll to bottom button
        binding.fabScrollToBottom.setOnClickListener {
            scrollToBottom(smooth = true)
        }
        
        // Chat history button
        binding.fabChatHistory.setOnClickListener {
            showChatHistoryDialog()
        }
        
        // MCP Tools button
        binding.fabMCPTools.setOnClickListener {
            toggleMCPToolbar()
        }
        
        // Clear history button in header
        binding.clearHistoryButton.setOnClickListener {
            showClearHistoryDialog()
        }
        
        // Connection status - make it clickable to test connection
        binding.connectionStatus.setOnClickListener {
            testLLMConnection()
        }
        binding.connectionIndicator.setOnClickListener {
            testLLMConnection()
        }
    }
    
    private fun setupMCPToolbar() {
        // Setup MCP tools adapter
        mcpToolsAdapter = com.example.tareamov.ui.adapter.MCPToolsAdapter(mcpTools) { tool ->
            onMCPToolExecute(tool)
        }
        
        binding.mcpToolsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = mcpToolsAdapter
        }
        
        // Toggle toolbar button
        binding.btnToggleToolbar.setOnClickListener {
            toggleMCPToolbar()
        }
    }
    
    private fun loadMCPTools() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tools = mcpHttpClient.listTools()
                Log.d(TAG, "📋 Loaded ${tools.size} MCP tools")
                
                withContext(Dispatchers.Main) {
                    mcpTools.clear()
                    mcpTools.addAll(tools)
                    mcpToolsAdapter.notifyDataSetChanged()
                    
                    // Update tools count badge
                    binding.toolsCountBadge.text = tools.size.toString()
                    
                    // Show toolbar if tools are available
                    if (tools.isNotEmpty()) {
                        binding.mcpToolbar.visibility = View.VISIBLE
                        isMCPToolbarVisible = true
                    }
                    
                    Log.d(TAG, "✅ MCP tools loaded and displayed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error loading MCP tools", e)
            }
        }
    }
    
    private fun toggleMCPToolbar() {
        isMCPToolbarVisible = !isMCPToolbarVisible
        
        if (isMCPToolbarVisible) {
            binding.mcpToolsRecyclerView.visibility = View.VISIBLE
            binding.btnToggleToolbar.setImageResource(R.drawable.ic_expand_less)
        } else {
            binding.mcpToolsRecyclerView.visibility = View.GONE
            binding.btnToggleToolbar.setImageResource(R.drawable.ic_expand_more)
        }
    }
    
    private fun onMCPToolExecute(tool: com.example.tareamov.ui.model.MCPTool) {
        Log.d(TAG, "🔧 Executing MCP tool: ${tool.name}")
        Log.d(TAG, "📋 Input schema: ${tool.inputSchema}")
        
        // Get required parameters
        val params = tool.getParameters()
        Log.d(TAG, "📝 Parameters found: ${params.size}")
        params.forEach { (name, param) ->
            Log.d(TAG, "  - $name: ${param.description} (required: ${param.required})")
        }
        
        if (params.isEmpty()) {
            // No parameters required, execute directly
            Log.d(TAG, "⚡ Executing without parameters")
            executeMCPTool(tool, org.json.JSONObject())
        } else {
            // Show dialog to input parameters
            Log.d(TAG, "💬 Showing parameters dialog")
            showToolParametersDialog(tool)
        }
    }
    
    private fun showToolParametersDialog(tool: com.example.tareamov.ui.model.MCPTool) {
        val params = tool.getParameters()
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_tool_parameters, null)
        
        val paramsContainer = dialogView.findViewById<LinearLayout>(R.id.paramsContainer)
        val inputViews = mutableMapOf<String, android.widget.EditText>()
        
        // Create input fields for each parameter
        params.forEach { (name, param) ->
            val paramView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_parameter_input, paramsContainer, false)
            
            val paramLabel = paramView.findViewById<TextView>(R.id.paramLabel)
            val paramInput = paramView.findViewById<android.widget.EditText>(R.id.paramInput)
            val paramHint = paramView.findViewById<TextView>(R.id.paramHint)
            
            paramLabel.text = name + if (param.required) " *" else ""
            paramHint.text = param.description
            
            inputViews[name] = paramInput
            paramsContainer.addView(paramView)
        }
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(tool.getDisplayName())
            .setMessage("Ingresa los parámetros para la herramienta:")
            .setView(dialogView)
            .setPositiveButton("Ejecutar") { _, _ ->
                val arguments = org.json.JSONObject()
                inputViews.forEach { (name, editText) ->
                    val value = editText.text.toString()
                    if (value.isNotEmpty()) {
                        arguments.put(name, value)
                    }
                }
                executeMCPTool(tool, arguments)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun executeMCPTool(tool: com.example.tareamov.ui.model.MCPTool, arguments: org.json.JSONObject) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Add user message showing what tool is being executed
                val argsString = if (arguments.length() > 0) {
                    arguments.keys().asSequence().joinToString(", ") { key ->
                        "$key: ${arguments.get(key)}"
                    }
                } else {
                    "sin parámetros"
                }
                
                addMessageToChat("🔧 Ejecutando: ${tool.getDisplayName()} ($argsString)", true)
                
                // Show typing indicator
                chatAdapter.addTypingIndicator()
                scrollToBottom()
                
                // Execute tool
                val result = mcpHttpClient.executeTool(tool.name, arguments)
                
                withContext(Dispatchers.Main) {
                    chatAdapter.removeTypingIndicator()
                    
                    if (result.success) {
                        val resultText = when (result.data) {
                            is String -> result.data
                            else -> result.data.toString()
                        }
                        addMessageToChat("✅ Resultado:\n\n$resultText", false)
                    } else {
                        addMessageToChat("❌ Error: ${result.error}", false)
                    }
                    
                    scrollToBottom()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error executing MCP tool", e)
                withContext(Dispatchers.Main) {
                    chatAdapter.removeTypingIndicator()
                    addMessageToChat("❌ Error: ${e.message}", false)
                }
            }
        }
    }
    
    private fun testLLMConnection() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Show testing message
                val testMessageId = addMessageToChat("🔍 Probando conexión con servidor LLM...", false)
                
                withContext(Dispatchers.IO) {
                    // Create MSPClient to test connection
                    val mspClient = MSPClient(requireContext())
                    val status = mspClient.getConnectionStatus()
                    
                    withContext(Dispatchers.Main) {
                        // Remove test message
                        chatAdapter.removeMessageById(testMessageId)
                        
                        // Show connection status
                        addMessageToChat(status, false)
                        
                        // Update connection indicator
                        val isConnected = status.contains("✓ SERVIDOR OLLAMA CONECTADO")
                        updateConnectionStatus(isConnected, if (isConnected) "Conectado" else "Desconectado")
                    }
                }
            } catch (e: Exception) {
                Log.e("DatabaseQueryFragment", "Error testing connection", e)
                addMessageToChat("❌ Error al probar conexión: ${e.message}", false)
            }
        }
    }
    
    private fun scrollToBottom(smooth: Boolean = false) {
        // Check if binding is still valid before accessing it
        if (_binding == null) return
        
        if (chatAdapter.itemCount > 0) {
            if (smooth) {
                binding.chatRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)
            } else {
                binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
            }
            isScrolledToBottom = true
            binding.fabScrollToBottom.visibility = View.GONE
        }
    }

    private fun addMessageToChat(text: String, isUser: Boolean): String {
        val message = if (isUser) {
            ChatMessage.createUserMessage(text)
        } else {
            ChatMessage.createSystemMessage(text)
        }
        
        // Add to both adapter and internal history
        chatAdapter.addMessage(message)
        chatHistory.add(message)
        totalMessageCount++
        
        // Update message count display (with null check)
        updateMessageCountDisplay()
        
        // Smart scroll behavior (with null check)
        if (isScrolledToBottom || isUser) {
            scrollToBottom(smooth = true)
        }

        // Hide brain icon when chat has messages (with null check)
        if (_binding != null && chatAdapter.itemCount > 0) {
            binding.centerBrainIcon.visibility = View.GONE
            binding.chatHistoryHeader.visibility = View.VISIBLE
        }

        // Auto-save after adding message
        saveChatHistory()
        
        // Manage memory by limiting messages
        if (chatAdapter.itemCount > maxMessagesPerSession) {
            removeOldestMessages(100) // Remove 100 oldest messages
        }

        // Log message addition for debugging
        Log.d("DatabaseQueryFragment", "Added message - User: $isUser, Text: ${text.take(50)}...")

        // Return the message ID
        return message.messageId
    }
    
    private fun updateMessageCountDisplay() {
        // Check if binding is still valid before accessing it
        if (_binding == null) return
        
        binding.messageCountText.text = "$totalMessageCount mensajes en esta conversación"
        binding.messageInfoBar.visibility = if (totalMessageCount > 0) View.VISIBLE else View.GONE
    }
    
    private fun removeOldestMessages(count: Int) {
        repeat(count) {
            if (chatHistory.isNotEmpty()) {
                val removedMessage = chatHistory.removeAt(0) // Remove first element
                chatAdapter.removeMessageById(removedMessage.messageId)
            }
        }
        // Update message count after removal
        totalMessageCount = chatHistory.size
        updateMessageCountDisplay()
    }

    private fun setupLocalLlamaService() {
        localLlamaService = LocalLlamaService(requireContext())
        val modelFile = requireContext().filesDir.resolve("llama3-8b-q4_0.gguf")

        // Update connection status based on model availability
        if (!modelFile.exists()) {
            updateConnectionStatus(false, "Descargando modelo...")
            Toast.makeText(context, "Descargando modelo de IA...", Toast.LENGTH_LONG).show()

            modelDownloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>().build()
            WorkManager.getInstance(requireContext()).enqueue(modelDownloadRequest!!)
            WorkManager.getInstance(requireContext()).getWorkInfoByIdLiveData(modelDownloadRequest!!.id)
                .observe(viewLifecycleOwner) { workInfo ->
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            Toast.makeText(context, "Modelo descargado exitosamente", Toast.LENGTH_LONG).show()
                            updateConnectionStatus(true, "Modelo listo")
                        }
                        WorkInfo.State.FAILED -> {
                            Toast.makeText(context, "Error descargando modelo", Toast.LENGTH_LONG).show()
                            updateConnectionStatus(false, "Error en descarga")
                        }
                        WorkInfo.State.RUNNING -> {
                            updateConnectionStatus(false, "Descargando...")
                        }
                        else -> { }
                    }
                }
        } else {
            updateConnectionStatus(true, "Modelo listo")
        }

        localLlamaService.downloadModelIfNeeded()
    }

    private fun setupSendButton() {
        binding.sendButton.setOnClickListener {
            val userInput = binding.queryInput.text.toString().trim()
            if (userInput.isNotEmpty()) {
                // Add user message to chat (this also updates internal history and persists)
                addMessageToChat(userInput, true)
                // Clear input safely
                binding.queryInput.setText("")

                // Show typing indicator
                chatAdapter.addTypingIndicator()
                binding.chatRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)

                // Process the query in the background
                // Use unified sendMessage flow to ensure RAG is executed once
                sendMessage()
            }
        }
    }

    private fun setupChartControls() {
        // Chart controls are disabled for now to avoid chart library dependency issues
        binding.zoomInButton.setOnClickListener {
            Toast.makeText(requireContext(), "Función de zoom disponible próximamente", Toast.LENGTH_SHORT).show()
        }

        binding.zoomOutButton.setOnClickListener {
            Toast.makeText(requireContext(), "Función de zoom disponible próximamente", Toast.LENGTH_SHORT).show()
        }

        binding.resetChartButton.setOnClickListener {
            Toast.makeText(requireContext(), "Función de reinicio disponible próximamente", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkServerStatus() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Create MSPClient to check Ollama connection
                val mspClient = MSPClient(requireContext())
                val testResults = mspClient.testAllConnections()
                val hasConnection = testResults.any { it.value }
                
                updateConnectionStatus(hasConnection, if (hasConnection) "Conectado" else "Desconectado")
                
                if (hasConnection) {
                    Log.d("DatabaseQueryFragment", "✓ LLM server is reachable")
                } else {
                    Log.w("DatabaseQueryFragment", "⚠️ LLM server is not reachable")
                    // Optionally show a message to the user
                    addMessageToChat("""
                        ⚠️ No se detectó conexión con el servidor LLM.
                        
                        Puedes usar las funciones de consulta, pero las respuestas 
                        no serán procesadas por IA.
                        
                        💡 Toca el indicador de conexión para probar la conectividad.
                    """.trimIndent(), false)
                }
            } catch (e: Exception) {
                Log.e("DatabaseQueryFragment", "Error checking server status", e)
                updateConnectionStatus(false, "Error")
            }
        }
    }

    private fun updateConnectionStatus(isConnected: Boolean, statusText: String? = null) {
        if (isConnected) {
            binding.connectionIndicator.backgroundTintList = resources.getColorStateList(android.R.color.holo_green_light, null)
            binding.connectionStatus.text = statusText ?: "Conectado" // Using hardcoded string instead of R.string.status_connected
        } else {
            binding.connectionIndicator.backgroundTintList = resources.getColorStateList(android.R.color.holo_red_light, null)
            binding.connectionStatus.text = statusText ?: "Desconectado" // Using hardcoded string instead of R.string.status_disconnected
        }
    }

    private fun processQuery(query: String) {
        Log.d("DatabaseQueryFragment", "=== MAIN QUERY PROCESSING ===")
        Log.d("DatabaseQueryFragment", "User Query: $query")
        
        // Show processing message in chat
        addMessageToChat("🔍 Procesando consulta con MCP tareamov-mcp-server...", false)

        binding.chartContainer.visibility = View.GONE // Hide chart container
        removeCurrentChart() // Remove previous chart if any

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("DatabaseQueryFragment", "Starting MCP server query processing...")
                
                // Use MCP tareamov-mcp-server as default tool
                val result = processQueryWithMCPServer(query)

                Log.d("DatabaseQueryFragment", "=== FINAL RESULT LOG ===")
                Log.d("DatabaseQueryFragment", "Result Length: ${result.length} characters")
                Log.d("DatabaseQueryFragment", "Result Content: $result")
                Log.d("DatabaseQueryFragment", "=======================")

                // Check if the response is a graph request
                if (result.startsWith("GRAPH_REQUEST:")) {
                    handleGraphRequest(result)

                    // Add a message about the graph
                    val graphMessage = when {
                        result.contains("USER_VIDEOS") -> "📊 Gráfico de usuarios con más videos generado"
                        result.contains("TOPIC_CONTENT") -> "📊 Gráfico de contenido por tema generado"
                        result.contains("COURSE_TOPICS") -> "📊 Gráfico de temas por curso generado"
                        result.contains("TASKS_TOPICS") -> "📊 Gráfico de tareas por tema generado"
                        result.contains("SUBSCRIPTIONS") -> "📊 Gráfico de suscripciones generado"
                        else -> "📊 Gráfico generado exitosamente"
                    }
                    addMessageToChat(graphMessage, false)
                } else {
                    // Display the text result in chat
                    if (result.isNullOrBlank()) {
                        addMessageToChat("⚠️ No se recibió respuesta del sistema MCP. Intente reformular su consulta.", false)
                    } else {
                        // Format and display the MCP response
                        val formattedResult = formatRAGResponse(result)
                        addMessageToChat(formattedResult, false)
                    }
                }

            } catch (e: Exception) {
                Log.e("DatabaseQueryFragment", "Error processing MCP query", e)
                val errorMessage = "❌ Error procesando la consulta con MCP: ${e.message}"
                addMessageToChat(errorMessage, false)
            } finally {
                isProcessingQuery = false
                // Save updated chat history
                saveChatHistory()
            }
        }
    }

    /**
     * Detect if query is requesting a graph/chart
     */
    private fun detectGraphRequest(query: String): String? {
        val lowerQuery = query.lowercase()
        return when {
            lowerQuery.contains("gráfico") || lowerQuery.contains("grafico") || 
            lowerQuery.contains("chart") -> {
                when {
                    lowerQuery.contains("usuario") && lowerQuery.contains("video") -> "GRAPH_REQUEST:USER_VIDEOS"
                    lowerQuery.contains("tema") && lowerQuery.contains("contenido") -> "GRAPH_REQUEST:TOPIC_CONTENT"
                    lowerQuery.contains("curso") && lowerQuery.contains("tema") -> "GRAPH_REQUEST:COURSE_TOPICS"
                    lowerQuery.contains("tarea") && lowerQuery.contains("tema") -> "GRAPH_REQUEST:TASKS_TOPICS"
                    lowerQuery.contains("suscripcion") || lowerQuery.contains("subscription") -> "GRAPH_REQUEST:SUBSCRIPTIONS"
                    else -> null
                }
            }
            else -> null
        }
    }

    /**
     * Process query using MCP tareamov-mcp-server (default tool)
     * This uses the query_database tool from the MCP server via HTTP
     */
    private suspend fun processQueryWithMCPServer(query: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "🔧 Using MCP tareamov-mcp-server HTTP for query: $query")
            
            // Use MCP HTTP Client to query the database
            val result = mcpHttpClient.queryDatabase(query)
            
            if (result.success) {
                Log.d(TAG, "✅ MCP STDIO query successful")
                
                // Format the result
                val resultText = when (val data = result.data) {
                    is String -> data
                    is JSONArray -> {
                        // Format JSON array results
                        if (data.length() == 0) {
                            "No se encontraron resultados."
                        } else {
                            formatMCPJsonArrayResults(data)
                        }
                    }
                    is JSONObject -> {
                        // Single result from query
                        formatMCPJsonObjectResult(data)
                    }
                    else -> com.google.gson.Gson().toJson(data)
                }
                
                // Append SQL script if available
                return@withContext if (result.sqlScript != null) {
                    """
$resultText

**Script SQL:**
```sql
${result.sqlScript}
```
                    """.trimIndent()
                } else {
                    resultText
                }
            } else {
                Log.e(TAG, "❌ MCP STDIO query failed: ${result.error}")
                // Fallback to RAG service
                Log.d(TAG, "⚠️ Falling back to RAG service due to MCP error")
                return@withContext processRAGEnhancedQuery(query)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception using MCP STDIO server: ${e.message}", e)
            // Fallback to RAG service
            Log.d(TAG, "⚠️ Falling back to RAG service due to exception")
            return@withContext processRAGEnhancedQuery(query)
        }
    }
    
    /**
     * Format JSON array results from MCP
     */
    private fun formatMCPJsonArrayResults(data: JSONArray): String {
        if (data.length() == 0) return "No se encontraron resultados."
        
        // Check if it's a simple count result
        if (data.length() == 1) {
            val item = data.getJSONObject(0)
            if (item.length() == 1 && (item.has("count") || item.has("COUNT"))) {
                return item.optString("count", item.optString("COUNT"))
            }
        }
        
        val sb = StringBuilder()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            
            // Format as simple list
            if (item.length() == 1) {
                // Single field result (e.g., just a name or title)
                val keys = item.keys()
                if (keys.hasNext()) {
                    sb.append("${i + 1}. ${item.get(keys.next())}\n")
                }
            } else {
                // Multiple fields - show key fields
                sb.append("${i + 1}. ")
                val keys = item.keys()
                var count = 0
                while (keys.hasNext() && count < 3) {
                    val key = keys.next()
                    sb.append("$key: ${item.get(key)}, ")
                    count++
                }
                sb.append("\n")
            }
        }
        return sb.toString().trim()
    }
    
    /**
     * Format JSON object result from MCP
     */
    private fun formatMCPJsonObjectResult(data: JSONObject): String {
        if (data.length() == 0) return "No se encontraron resultados."
        
        // Check for count result
        if (data.length() == 1 && (data.has("count") || data.has("COUNT"))) {
            return data.optString("count", data.optString("COUNT"))
        }
        
        val sb = StringBuilder()
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            sb.append("$key: ${data.get(key)}\n")
        }
        return sb.toString().trim()
    }
    
    /**
     * Format list results from MCP in a readable way
     */
    private fun formatMCPListResults(data: List<*>): String {
        if (data.isEmpty()) return "No se encontraron resultados."
        
        // Check if it's a simple count result
        if (data.size == 1 && data[0] is Map<*, *>) {
            val map = data[0] as Map<*, *>
            if (map.size == 1 && (map.containsKey("count") || map.containsKey("COUNT"))) {
                return map.values.first().toString()
            }
        }
        
        val sb = StringBuilder()
        data.forEachIndexed { index, item ->
            when (item) {
                is Map<*, *> -> {
                    // Format as simple list
                    if (item.size == 1) {
                        // Single field result (e.g., just a name or title)
                        sb.append("${index + 1}. ${item.values.first()}\n")
                    } else {
                        // Multiple fields - show key fields
                        sb.append("${index + 1}. ")
                        item.entries.take(3).forEach { (key, value) ->
                            sb.append("$key: $value, ")
                        }
                        sb.append("\n")
                    }
                }
                else -> sb.append("${index + 1}. $item\n")
            }
        }
        return sb.toString().trim()
    }
    
    /**
     * Format map result from MCP
     */
    private fun formatMCPMapResult(data: Map<*, *>): String {
        if (data.isEmpty()) return "No se encontraron resultados."
        
        // Check for count result
        if (data.size == 1 && (data.containsKey("count") || data.containsKey("COUNT"))) {
            return data.values.first().toString()
        }
        
        val sb = StringBuilder()
        data.entries.forEach { (key, value) ->
            sb.append("$key: $value\n")
        }
        return sb.toString().trim()
    }

    /**
     * Process query using RAG-enhanced system
     * 
     * FLUJO COMPLETO:
     * 1. Usuario escribe en lenguaje natural (ej: "dame el creator_username del id 11 de la tabla courses")
     * 2. MCPService.processQuery() detecta shortcuts o delega a RAGDatabaseService
     * 3. RAGDatabaseService:
     *    - Analiza intención de la consulta
     *    - Identifica tablas y columnas relevantes
     *    - **CONSULTA SUPABASE DIRECTAMENTE** (SupabaseClient.fetchXXX())
     *    - Obtiene JSON real ordenado por ID
     *    - Filtra datos relevantes
     *    - Genera respuesta con LLM
     * 4. SupabaseClient notifica la URL usada via requestListener
     * 5. Este fragment muestra respuesta + URL de Supabase
     */
    private suspend fun processRAGEnhancedQuery(query: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d("DatabaseQueryFragment", "=== QUERY PROCESSING LOG ===")
            Log.d("DatabaseQueryFragment", "Input Query: $query")
            Log.d("DatabaseQueryFragment", "Current Conversation Context: $currentConversationContext")
            
            // Add query context for conversation continuity
            currentConversationContext.add(query)
            if (currentConversationContext.size > MAX_CONTEXT_MESSAGES) {
                currentConversationContext.removeAt(0) // Remove first element using removeAt(0)
            }

            Log.d("DatabaseQueryFragment", "Updated Conversation Context: $currentConversationContext")
            Log.d("DatabaseQueryFragment", "Trying DatabaseQueryService with MCP Tools...")

            // Use MCP-enhanced query processing (with tools)
            val ragResult = databaseQueryService.processQueryWithMCP(query)
            
            Log.d("DatabaseQueryFragment", "MCP-RAG Result Length: ${ragResult.length} characters")
            Log.d("DatabaseQueryFragment", "MCP-RAG Result Content: $ragResult")
            
            if (ragResult.isNotBlank() && !ragResult.startsWith("Error")) {
                Log.d("DatabaseQueryFragment", "MCP-RAG service provided result - SUCCESS")
                Log.d("DatabaseQueryFragment", "===========================")
                return@withContext ragResult
            }

            // Fallback to MCPService if RAG fails
            Log.w("DatabaseQueryFragment", "RAG service failed, trying MCP fallback")
            val mcpResult = mcpService.processQuery(query)
            
            Log.d("DatabaseQueryFragment", "MCP Result Length: ${mcpResult.length} characters")
            Log.d("DatabaseQueryFragment", "MCP Result Content: $mcpResult")
            Log.d("DatabaseQueryFragment", "===========================")
            
            return@withContext mcpResult
            
        } catch (e: Exception) {
            Log.e("DatabaseQueryFragment", "Error in RAG-enhanced processing", e)
            return@withContext "Error en el procesamiento RAG: ${e.message}"
        }
    }

    /**
     * Format RAG response for better readability
     */
    private fun formatRAGResponse(response: String): String {
        return when {
            response.contains("No se encontraron") -> "📭 $response"
            response.contains("Total") || response.contains("registros") -> "📊 $response"
            response.contains("ID:") || response.contains("Usuario:") -> "📝 $response"
            response.contains("Error") -> "⚠️ $response"
            response.contains("Lista") || response.contains("Resultados") -> "📋 $response"
            else -> response
        }
    }

    // Simplified handleGraphRequest
    private suspend fun handleGraphRequest(graphRequest: String) {
        // Remove the chart container's previous content
        removeCurrentChart()
        binding.chartContainer.visibility = View.VISIBLE // Make container visible
        binding.chartControls.visibility = View.VISIBLE // Show chart controls
        binding.scrollView.visibility = View.GONE // Hide text scroll view

        val chartGenerated = when (graphRequest) {
            "GRAPH_REQUEST:USER_VIDEOS" -> {
                generateUserVideoChart()
                true
            }
            "GRAPH_REQUEST:TOPIC_CONTENT" -> {
                generateTopicContentChart()
                true
            }
            "GRAPH_REQUEST:COURSE_TOPICS" -> {
                generateVideoTopicChart()
                true
            }
            "GRAPH_REQUEST:TASKS_TOPICS" -> {
                generateTaskTopicChart()
                true
            }
            "GRAPH_REQUEST:SUBSCRIPTIONS" -> {
                generateSubscriptionChart()
                true
            }
            "GRAPH_REQUEST:PERSONAS_USERS" -> {
                // Placeholder - Implement this chart if needed
                resultTextView.text = "Gráfico de personas no implementado aún"
                binding.scrollView.visibility = View.VISIBLE
                false
            }
            "GRAPH_REQUEST:INTERACTIVE" -> {
                // Placeholder - Implement interactive chart if needed
                resultTextView.text = "Gráfico interactivo no implementado aún"
                binding.scrollView.visibility = View.VISIBLE
                false
            }
            else -> {
                // Handle unrecognized graph requests (though DatabaseQueryService might handle this)
                resultTextView.text = "Tipo de gráfico no reconocido: $graphRequest"
                binding.chartContainer.visibility = View.GONE // Hide container if no chart
                binding.scrollView.visibility = View.VISIBLE
                false
            }
        }

        if (chartGenerated) {
            // Update text view to indicate success
            val successMessage = when(graphRequest) {
                "GRAPH_REQUEST:USER_VIDEOS" -> "Gráfico de usuarios con videos generado exitosamente"
                "GRAPH_REQUEST:TOPIC_CONTENT" -> "Gráfico de contenido por tema generado exitosamente"
                "GRAPH_REQUEST:COURSE_TOPICS" -> "Gráfico de temas por curso generado exitosamente"
                else -> "Gráfico generado exitosamente"
            }
            resultTextView.text = successMessage
        } else {
            // If chart generation failed or wasn't implemented, hide the container and controls
            binding.chartContainer.visibility = View.GONE
            binding.chartControls.visibility = View.GONE
        }
    }

    // The chart generation methods remain mostly unchanged
    private suspend fun generateUserVideoChart(): Boolean = withContext(Dispatchers.Main) {
        try {
            binding.chartContainer.removeAllViews()
            binding.chartContainer.visibility = View.VISIBLE
            
            // Create a simple text view showing chart data instead of actual chart
            val textView = TextView(requireContext()).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2A3245"))
                setPadding(16, 16, 16, 16)
            }
            
            withContext(Dispatchers.IO) {
                val videos = database.videoDao().getAllVideos()
                val userVideoCounts = videos.groupBy { it.username }
                    .mapValues { it.value.size }
                    .toList()
                    .sortedByDescending { it.second }
                    .take(10)

                withContext(Dispatchers.Main) {
                    val chartText = buildString {
                        append("📊 Videos por Usuario:\n\n")
                        userVideoCounts.forEach { (username, count) ->
                            append("👤 $username: $count videos\n")
                        }
                    }
                    textView.text = chartText
                    binding.chartContainer.addView(textView)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun generateTopicContentChart(): Boolean = withContext(Dispatchers.Main) {
        try {
            binding.chartContainer.removeAllViews()
            binding.chartContainer.visibility = View.VISIBLE
            
            val textView = TextView(requireContext()).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2A3245"))
                setPadding(16, 16, 16, 16)
            }
            
            withContext(Dispatchers.IO) {
                val topics = database.topicDao().getAllTopics()
                val contentItems = database.contentItemDao().getAllContentItems()

                withContext(Dispatchers.Main) {
                    val chartText = buildString {
                        append("📊 Contenido por Tema:\n\n")
                        for (topic in topics) {
                            val count = contentItems.count { it.topicId == topic.id }
                            if (count > 0) {
                                append("📚 ${topic.name}: $count elementos\n")
                            }
                        }
                    }
                    textView.text = chartText
                    binding.chartContainer.addView(textView)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun generateVideoTopicChart(): Boolean = withContext(Dispatchers.Main) {
        try {
            binding.chartContainer.removeAllViews()
            binding.chartContainer.visibility = View.VISIBLE
            
            val textView = TextView(requireContext()).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2A3245"))
                setPadding(16, 16, 16, 16)
            }
            
            withContext(Dispatchers.IO) {
                val videos = database.videoDao().getAllVideos()
                val videoTopicCounts = ArrayList<Pair<String, Int>>()

                videos.forEach { video ->
                    val videoTitle = video.title ?: "Video ${video.id}"
                    val topicCount = database.topicDao().getTopicsByCourse(video.id).size
                    videoTopicCounts.add(Pair(videoTitle, topicCount))
                }

                val sortedData = videoTopicCounts.sortedByDescending { it.second }.take(10)

                withContext(Dispatchers.Main) {
                    val chartText = buildString {
                        append("📊 Temas por Video:\n\n")
                        sortedData.forEach { (title, count) ->
                            val displayTitle = if (title.length > 30) title.substring(0, 27) + "..." else title
                            append("🎥 $displayTitle: $count temas\n")
                        }
                    }
                    textView.text = chartText
                    binding.chartContainer.addView(textView)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun generateTaskTopicChart(): Boolean = withContext(Dispatchers.Main) {
        try {
            binding.chartContainer.removeAllViews()
            binding.chartContainer.visibility = View.VISIBLE
            
            val textView = TextView(requireContext()).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2A3245"))
                setPadding(16, 16, 16, 16)
            }
            
            withContext(Dispatchers.IO) {
                val topics = database.topicDao().getAllTopics()
                val tasks = database.taskDao().getAllTasks()
                val topicTaskCounts = topics.map { topic ->
                    topic.name to tasks.count { it.topicId == topic.id }
                }.filter { it.second > 0 }
                    .sortedByDescending { it.second }
                    .take(10)

                withContext(Dispatchers.Main) {
                    val chartText = buildString {
                        append("📊 Tareas por Tema:\n\n")
                        topicTaskCounts.forEach { (topicName, count) ->
                            val displayName = if (topicName.length > 30) topicName.substring(0, 27) + "..." else topicName
                            append("📝 $displayName: $count tareas\n")
                        }
                    }
                    textView.text = chartText
                    binding.chartContainer.addView(textView)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun generateSubscriptionChart(): Boolean = withContext(Dispatchers.Main) {
        try {
            binding.chartContainer.removeAllViews()
            binding.chartContainer.visibility = View.VISIBLE
            
            val textView = TextView(requireContext()).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2A3245"))
                setPadding(16, 16, 16, 16)
            }
            
            withContext(Dispatchers.IO) {
                val subscriptions = database.subscriptionDao().getAllSubscriptions()
                val creatorCounts = subscriptions.groupBy { it.creatorUsername }
                    .mapValues { it.value.size }
                    .toList()
                    .sortedByDescending { it.second }
                    .take(10)

                withContext(Dispatchers.Main) {
                    val chartText = buildString {
                        append("📊 Suscripciones por Creador:\n\n")
                        creatorCounts.forEach { (creator, count) ->
                            append("👑 $creator: $count suscripciones\n")
                        }
                    }
                    textView.text = chartText
                    binding.chartContainer.addView(textView)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun removeCurrentChart() {
        if (currentChart != null) {
            binding.chartContainer.removeView(currentChart)
            currentChart = null
        }
        binding.chartControls.visibility = View.GONE // Hide chart controls when removing chart
    }

    private fun processUserQuery(userInput: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = mcpService.processQuery(userInput)
                // Remove typing indicator
                chatAdapter.removeTypingIndicator()
                addMessageToChat(response, false)
            } catch (e: Exception) {
                chatAdapter.removeTypingIndicator()
                addMessageToChat("Error al procesar la consulta: ${e.message}", false)
            }
        }
    }

    private fun setupEnhancedUI() {
        // Set up quick action chips
        binding.chipClearChat.setOnClickListener {
            clearChatHistory()
        }
        
        binding.chipHelp.setOnClickListener {
            addHelpMessage()
            showValidRoles()
        }
        
        binding.chipExamples.setOnClickListener {
            addExampleQueries()
        }
        
        // Toggle quick actions visibility
        binding.queryInput.setOnFocusChangeListener { _, hasFocus ->
            binding.quickActionsLayout.visibility = if (hasFocus) View.VISIBLE else View.GONE
        }
        
        // Enhanced input handling
        binding.queryInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN) {
                if (!event.isShiftPressed) {
                    sendMessage()
                    true
                } else {
                    false // Allow new line with Shift+Enter
                }
            } else {
                false
            }
        }
    }

    private fun initializeSession() {
        val username = sessionManager.getUsername() ?: "anonymous"
        
        // Generate user-specific session ID
    currentSessionId = chatPrefs.getString(SESSION_ID_KEY, null) ?: "${username}_${UUID.randomUUID()}"
        totalMessageCount = chatPrefs.getInt(MESSAGE_COUNT_KEY, 0)
        
        // Save session ID if it's new
        chatPrefs.edit()
            .putString(SESSION_ID_KEY, currentSessionId)
            .putInt(MESSAGE_COUNT_KEY, totalMessageCount)
            .apply()
        
        // Restore chat history for current user
    restoreChatHistory()
    }

    private fun restoreChatHistory() {
        try {
            val key = "${CHAT_HISTORY_KEY}_$currentSessionId"
            val savedMessages = chatPrefs.getString(key, null) ?: chatPrefs.getString(CHAT_HISTORY_KEY, null)
            if (!savedMessages.isNullOrEmpty()) {
                val messageList = savedMessages.split("|||").mapNotNull { messageStr ->
                    ChatMessage.fromStorageString(messageStr)
                }.filter { it != null && it.isValid() }
                
                // Clear both adapter and internal history before restoring
                chatAdapter.clear()
                chatHistory.clear()
                
                // Restore to both adapter and internal history
                chatAdapter.restoreMessages(messageList)
                chatHistory.addAll(messageList)
                totalMessageCount = messageList.size
                
                // Update UI based on restored messages
                if (messageList.isNotEmpty()) {
                    binding.centerBrainIcon.visibility = View.GONE
                    binding.chatHistoryHeader.visibility = View.VISIBLE
                    updateMessageCountDisplay()
                    
                    // Scroll to bottom
                    view?.post {
                        scrollToBottom(smooth = false)
                    }
                    
                    // Log restoration for debugging
                    Log.d("DatabaseQueryFragment", "Restored ${messageList.size} messages for user: ${sessionManager.getUsername()}")
                    messageList.forEach { message ->
                        Log.d("DatabaseQueryFragment", "Restored message - User: ${message.isUser}, Text: ${message.text.take(50)}...")
                    }
                } else {
                    Log.d("DatabaseQueryFragment", "No messages to restore for user: ${sessionManager.getUsername()}")
                }
            } else {
                Log.d("DatabaseQueryFragment", "No saved messages found for user: ${sessionManager.getUsername()}")
            }
        } catch (e: Exception) {
            Log.e("DatabaseQueryFragment", "Error restoring chat history for user: ${sessionManager.getUsername()}", e)
            // Create new session on restore failure
            createNewSession()
        }
    }
    
    private fun createNewSession() {
        val username = sessionManager.getUsername() ?: "anonymous"
        currentSessionId = "${username}_${UUID.randomUUID()}"
        totalMessageCount = 0
        
        // Clear both adapter and internal history
        chatHistory.clear()
        chatAdapter.clear()
        
        chatPrefs.edit()
            .putString(SESSION_ID_KEY, currentSessionId)
            .putInt(MESSAGE_COUNT_KEY, 0)
            .remove(CHAT_HISTORY_KEY)
            .apply()
            
        binding.centerBrainIcon.visibility = View.VISIBLE
        binding.chatHistoryHeader.visibility = View.GONE
        updateMessageCountDisplay()
        
        Log.d("DatabaseQueryFragment", "Created new session for user: $username")
    }

    private fun saveChatHistory() {
        try {
            val messages = chatHistory.takeLast(maxMessagesPerSession) // Limit saved messages
            val messageStrings = messages.map { it.toStorageString() }
            val key = "${CHAT_HISTORY_KEY}_$currentSessionId"
            chatPrefs.edit()
                .putString(key, messageStrings.joinToString("|||"))
                .putString(SESSION_ID_KEY, currentSessionId)
                .putInt(MESSAGE_COUNT_KEY, totalMessageCount)
                .apply()
                
            Log.d("DatabaseQueryFragment", "Saved ${messages.size} messages for user: ${sessionManager.getUsername()}")
        } catch (e: Exception) {
            Log.e("DatabaseQueryFragment", "Error saving chat history for user: ${sessionManager.getUsername()}", e)
        }
    }
    
    // Implementation of UserChangeListener interface
    override fun onUserChanged(previousUser: String?, newUser: String?) {
        Log.d("DatabaseQueryFragment", "User changed from '$previousUser' to '$newUser'")
        
        // Save current chat for previous user
        if (previousUser != null && chatHistory.isNotEmpty()) {
            saveChatHistory()
        }
        
        // Clear current chat display and internal history
        chatHistory.clear()
        chatAdapter.clear()
        totalMessageCount = 0
        
        // Update current user
        currentUser = newUser
        
        // Update user indicator in UI
        updateUserIndicator()
        
        // Initialize new session for new user
        view?.post {
            initializeSession()
            
            // Add welcome message if no history exists for new user
            if (chatAdapter.getMessages().isEmpty()) {
                addWelcomeMessage()
            }
        }
    }
    
    override fun onUserLoggedOut(previousUser: String?) {
        Log.d("DatabaseQueryFragment", "User '$previousUser' logged out")
        
        // Save current chat for logged out user
        if (previousUser != null && chatHistory.isNotEmpty()) {
            saveChatHistory()
        }
        
        // Clear all chat data
        chatHistory.clear()
        chatAdapter.clear()
        totalMessageCount = 0
        currentUser = null
        
        // Update user indicator
        updateUserIndicator()
        
        // Reset UI
        binding.centerBrainIcon.visibility = View.VISIBLE
        binding.chatHistoryHeader.visibility = View.GONE
        updateMessageCountDisplay()
    }

    private fun clearChatHistory() {
        createNewSession()
        addWelcomeMessage()
    }
    
    private fun showClearHistoryDialog() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Limpiar historial")
            .setMessage("¿Estás seguro de que quieres eliminar todo el historial de chat? Esta acción no se puede deshacer.")
            .setPositiveButton("Sí, limpiar") { _, _ ->
                clearChatHistory()
                Toast.makeText(requireContext(), "Historial limpiado", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun showChatHistoryDialog() {
        // Show dialog with chat statistics and options
        val messageCount = totalMessageCount
        val sessionTime = chatPrefs.getLong("session_start_time", System.currentTimeMillis())
        val sessionDuration = (System.currentTimeMillis() - sessionTime) / (1000 * 60) // minutes
        
        val message = """
            📊 Estadísticas de la conversación:
            
            • Mensajes totales: $messageCount
            • Duración de sesión: ${sessionDuration}min
            • ID de sesión: ${currentSessionId.take(8)}...
            
            ¿Qué deseas hacer?
        """.trimIndent()
        
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Historial de Chat")
            .setMessage(message)
            .setPositiveButton("Exportar") { _, _ ->
                exportChatHistory()
            }
            .setNeutralButton("Nueva sesión") { _, _ ->
                startNewSession()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }
    
    private fun startNewSession() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Nueva sesión")
            .setMessage("¿Quieres empezar una nueva sesión? La conversación actual se guardará.")
            .setPositiveButton("Sí") { _, _ ->
                saveChatHistory() // Save current session
                createNewSession()
                addWelcomeMessage()
                Toast.makeText(requireContext(), "Nueva sesión iniciada", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun exportChatHistory() {
        // Simple export functionality
        val messages = chatHistory
        val exportText = buildString {
            appendLine("=== Exportación de Chat MCP ===")
            appendLine("Sesión: $currentSessionId")
            appendLine("Fecha: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
            appendLine("Total mensajes: ${messages.size}")
            appendLine()
            
            messages.forEach { message ->
                val sender = if (message.isUser) "Usuario" else "Sistema MCP"
                val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(message.timestamp))
                appendLine("[$time] $sender: ${message.text}")
                appendLine()
            }
        }
        
        // Create sharing intent
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, exportText)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Chat MCP - Sesión $currentSessionId")
        }
        
        startActivity(android.content.Intent.createChooser(intent, "Exportar chat"))
    }

    private fun addWelcomeMessage() {
        val username = sessionManager.getUsername() ?: "Usuario"
        val toolsCount = mcpTools.size
        val toolsList = if (toolsCount > 0) {
            mcpTools.joinToString("\n") { "  • ${it.getDisplayName()}: ${it.description}" }
        } else {
            "  (Cargando...)"
        }
        
        val welcomeText = """
🎯 ¡Bienvenido/a, $username!

Estás conectado al sistema MCP (Model Context Protocol) con acceso a:

🛠️ **Herramientas MCP disponibles ($toolsCount):**
$toolsList

💬 **Modos de interacción:**
  • Chat natural: Escribe consultas en lenguaje natural
  • Herramientas directas: Usa el botón 🔧 para ejecutar herramientas específicas
  • Consultas SQL: El sistema generará SQL automáticamente

📊 **Capacidades:**
  • Consultas a la base de datos en tiempo real
  • Análisis de esquemas y relaciones
  • Generación de gráficos y visualizaciones
  • Respuestas contextuales con RAG

✨ Escribe tu consulta o usa el botón de herramientas para empezar.
        """.trimIndent()
        
        addMessageToChat(welcomeText, false)
    }

    private fun addHelpMessage() {
        val helpText = """
            🔍 **Comandos disponibles:**
            
            **Consultas generales:**
            • "¿Cuántos [usuarios/videos/temas] hay?"
            • "Muestra información sobre..."
            • "Lista todos los..."
            
            **Gráficos:**
            • "Crear gráfico de..."
            • "Mostrar estadísticas de..."
            • "Visualizar datos de..."
            
            **Ejemplos específicos:**
            • "¿Cuáles son los temas más populares?"
            • "Muestra los usuarios más activos"
            • "Crear gráfico de contenido por tema"
        """.trimIndent()
        
        addMessageToChat(helpText, false)
    // Inform about valid roles
    val roles = com.example.tareamov.service.MSPClient.VALID_ROLES.joinToString(", ")
    addMessageToChat("Roles válidos en el sistema: $roles", false)
    }

    private fun addExampleQueries() {
        val examples = listOf(
            "📊 Dame todos los usuarios registrados",
            "🎥 Muestra los videos de un creador específico",
            "📈 ¿Cuántos videos hay en total?",
            "📋 Lista todas las tareas completadas",
            "👥 Muestra las suscripciones recientes",
            "🎯 ¿Qué temas tienen más contenido?",
            "💰 Lista las compras realizadas",
            "📚 Muestra todos los cursos disponibles",
            "🔍 Buscar usuarios por rol",
            "📊 Crear gráfico de usuarios con más videos"
        )
        
        addMessageToChat("🤖 Ejemplos de consultas que puedes hacer con el sistema RAG:", false)
        examples.forEach { example ->
            addMessageToChat("💡 $example", false)
        }
        
        addMessageToChat("🚀 El sistema RAG optimiza automáticamente tus consultas para obtener información relevante de la base de datos.", false)
    }

    private fun sendMessage() {
        val queryText = binding.queryInput.text
        val query = queryText?.toString()?.trim() ?: ""
        if (query.isNotEmpty()) {
            // Add user message to chat
            addMessageToChat(query, true)

            // Clear the input field safely
            binding.queryInput.setText("")

            // Show typing indicator
            val typingIndicatorId = addMessageToChat("Escribiendo...", false)

            // Launch a coroutine to process the query using the unified RAG flow
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        processRAGEnhancedQuery(query)
                    }

                    // Remove typing indicator and show result
                    chatAdapter.removeMessageById(typingIndicatorId)
                    addMessageToChat(result, false)

                } catch (e: Exception) {
                    // Handle any errors
                    chatAdapter.removeMessageById(typingIndicatorId)
                    addMessageToChat("Error: ${e.message}", false)
                }
            }
        }
    }

    private fun displayQueryResults(result: String) {
        // Update your UI to show the query results
        // For example, if you have a TextView to display results:
        var out = result
        lastSupabaseUrl?.let { url ->
            out += "\n\n[Última consulta Supabase]: ${url}"
        }
        binding.resultText?.text = out
        // Or if you're using a RecyclerView adapter:
        // adapter.submitList(parseResults(result))
    }

    private fun handleEditUserMessage(message: ChatMessage) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("Editar Mensaje")
        
        // Create EditText with current message
        val editText = android.widget.EditText(requireContext()).apply {
            setText(message.text)
            setSelection(text.length) // Place cursor at end
            setPadding(32, 24, 32, 24)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setBackgroundResource(android.R.color.transparent)
            textSize = 16f
        }
        
        // Create container with padding and black background
        val container = android.widget.FrameLayout(requireContext()).apply {
            addView(editText)
            setPadding(48, 32, 48, 32)
            setBackgroundColor(Color.BLACK)
        }
        
        builder.setView(container)
        
        // Style the dialog
        val dialog = builder.create().apply {
            window?.setBackgroundDrawableResource(android.R.color.black)
            setOnShowListener {
                // Style buttons
                getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#4CAF50"))
                    setPadding(32, 16, 32, 16)
                }
                getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.parseColor("#f44336"))
                    setPadding(32, 16, 32, 16)
                }
            }
        }
        
        dialog.setButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE, "Enviar") { _, _ ->
            val newText = editText.text.toString().trim()
            if (newText.isNotEmpty() && newText != message.text) {
                handleMessageEdit(message, newText)
            }
        }
        
        dialog.setButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE, "Cancelar") { _, _ ->
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun handleMessageEdit(originalMessage: ChatMessage, newText: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Find the message in both chat history and adapter
                val historyIndex = chatHistory.indexOfFirst { it.messageId == originalMessage.messageId }
                
                if (historyIndex != -1) {
                    // Update the message text in chat history
                    val updatedMessage = chatHistory[historyIndex].copy(text = newText)
                    chatHistory[historyIndex] = updatedMessage
                    
                    // Update the message in the adapter
                    chatAdapter.updateMessage(originalMessage.messageId, newText)
                    
                    // Remove ALL messages after the edited message (both user and bot responses)
                    val messagesToRemove = chatHistory.drop(historyIndex + 1)
                    messagesToRemove.forEach { messageToRemove ->
                        chatAdapter.removeMessageById(messageToRemove.messageId)
                        totalMessageCount--
                    }
                    // Remove from chat history
                    while (chatHistory.size > historyIndex + 1) {
                        chatHistory.removeAt(historyIndex + 1)
                    }
                    
                    // Show typing indicator for new response
                    chatAdapter.addTypingIndicator()
                    scrollToBottom(smooth = true)
                    
                    try {
                        val result = withContext(Dispatchers.IO) {
                            databaseQueryService.processQuery(newText)
                        }
                        
                        // Remove typing indicator
                        chatAdapter.removeTypingIndicator()
                        
                        // Add new bot response
                        addMessageToChat(result, false)
                        
                    } catch (e: Exception) {
                        // Remove typing indicator on error
                        chatAdapter.removeTypingIndicator()
                        
                        addMessageToChat("Error al procesar la consulta editada: ${e.message}", false)
                    }
                    
                    // Save updated chat history
                    saveChatHistory()
                    
                    Log.d("DatabaseQueryFragment", "Successfully edited message: ${originalMessage.messageId}")
                }
                
            } catch (e: Exception) {
                Log.e("DatabaseQueryFragment", "Error editing message", e)
                Toast.makeText(requireContext(), "Error al editar mensaje: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        // Save chat history before destroying view
        saveChatHistory()
        
        // Remove any pending callbacks
        view?.removeCallbacks(autoSaveRunnable)
        
        // Unregister from user change notifications
        SessionManager.removeUserChangeListener(this)
        
        // Close MCP HTTP client connection
        try {
            mcpHttpClient.close()
            Log.d(TAG, "✅ MCP HTTP client closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing MCP HTTP client", e)
        }

    // Unregister Supabase request listener to avoid leaking fragment
    com.example.tareamov.service.SupabaseClient.setRequestListener(null)
        
        super.onDestroyView()
        _binding = null
    }
    
    override fun onPause() {
        super.onPause()
        // Save chat when app goes to background to preserve user messages
        saveChatHistory()
    }
    
    override fun onResume() {
        super.onResume()
        // Check if user changed while app was in background
        val newUser = sessionManager.getUsername()
        if (currentUser != newUser) {
            Log.d("DatabaseQueryFragment", "User changed during background: '$currentUser' -> '$newUser'")
            onUserChanged(currentUser, newUser)
        } else {
            // Update user indicator even if user didn't change
            updateUserIndicator()
        }
    }
}