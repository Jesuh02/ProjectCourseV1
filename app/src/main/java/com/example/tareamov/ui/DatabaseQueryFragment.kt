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


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import com.example.tareamov.ui.adapter.DatabaseChatAdapter
import com.example.tareamov.util.SessionManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import android.graphics.Rect
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.example.tareamov.service.ServerEndpointResolver
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.provider.OpenableColumns

class DatabaseQueryFragment : Fragment(), SessionManager.UserChangeListener {

    private var _binding: FragmentDatabaseQueryBinding? = null
    private val binding get() = _binding!!

    private lateinit var resultTextView: TextView
    private lateinit var database: AppDatabase
    private var currentChart: View? = null
    private lateinit var chatAdapter: DatabaseChatAdapter

    private lateinit var sessionManager: SessionManager
    
    // MCP HTTP Client for CourseV by YisusFactory MCP Server (connects via HTTP to Node.js server)
    private lateinit var mcpHttpClient: com.example.tareamov.service.MCPHttpClient
    
    // Enhanced chat state management per user
    private val chatHistory = mutableListOf<ChatMessage>()
    private var isProcessingQuery = false
    private var currentConversationContext = mutableListOf<String>()
    private var totalMessageCount = 0
    private var isScrolledToBottom = true
    private var currentUser: String? = null
    private var currentUserAvatar: String? = null
    // Keep the last Supabase GET URL for display with final results
    private var lastSupabaseUrl: String? = null
    
    // User-specific SharedPreferences for better persistence
    private val chatPrefs by lazy {
        sessionManager.getChatPreferences(requireContext())
    }

    // Session management per user
    private var currentSessionId: String = ""
    private val maxMessagesPerSession = 1000 // Prevent memory issues
    
    // Keyboard visibility listener
    private var keyboardLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    
    // Excel mode flag removed from UI (option eliminated)

    // Attached files
    data class AttachedFile(
        val uri: android.net.Uri, 
        val name: String, 
        val type: String, 
        var remoteUrl: String? = null,
        val isPreUploaded: Boolean = false // Flag for files that are already on the server (e.g. generated)
    )
    private val attachedFiles = mutableListOf<AttachedFile>()
    private lateinit var attachedFilesAdapter: AttachedFilesAdapter

    // Launcher for picking Excel files
    private val excelPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            // Persist permission to access the file
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to take persistable permission", e)
            }

            // Get file name
            var fileName = "Unknown file"
            try {
                val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            fileName = it.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting file name", e)
            }
            
            // Add to list and UI
            val file = AttachedFile(uri, fileName, "excel")
            addAttachedFile(file)
            
            // Upload immediately
            uploadAttachedFile(file)
        }
    }

    private fun addAttachedFile(file: AttachedFile) {
        attachedFiles.clear() // Limit to 1 file as per request implication (single attachment shown)
        attachedFiles.add(file)
        updateAttachedFilesUI()
    }

    private fun removeAttachedFile(file: AttachedFile) {
        attachedFiles.remove(file)
        updateAttachedFilesUI()
    }

    private fun removeAttachedFileWithRemote(file: AttachedFile) {
        // Remove from UI immediately for better UX
        removeAttachedFile(file)

        // If there's a remote URL, try to delete from Cloudflare R2 in background
        val remote = file.remoteUrl
        if (remote.isNullOrEmpty()) {
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            var deletedRemotely = false

            try {
                withContext(Dispatchers.IO) {
                    try {
                        val uri = java.net.URI(remote)
                        var path = uri.path ?: ""
                        if (!path.startsWith("/")) path = "/$path"

                        val hostPrefix = uri.host?.split(".")?.getOrNull(0)

                        val candidates = mutableListOf<String>()
                        if (!hostPrefix.isNullOrEmpty() && path.startsWith("/$hostPrefix/")) {
                            candidates.add(path.substringAfter("/$hostPrefix/"))
                        }
                        if (path.startsWith("/")) candidates.add(path.substring(1))
                        candidates.add(path.trimStart('/'))

                        for (candidate in candidates) {
                            try {
                                if (candidate.isEmpty()) continue
                                val ok = com.example.tareamov.service.CloudflareR2Service.deleteFile(candidate)
                                if (ok) {
                                    deletedRemotely = true
                                    break
                                }
                            } catch (t: Throwable) {
                                // try next candidate
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            } catch (e: Exception) {
                // ignore
            }

            withContext(Dispatchers.Main) {
                if (deletedRemotely) {
                    Log.d(TAG, "Archivo remoto eliminado: $remote")
                } else {
                    Log.w(TAG, "No se pudo eliminar archivo remoto: $remote")
                }
            }
        }
    }

    private fun updateAttachedFilesUI() {
        val recyclerView = binding.root.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.attachedFilesRecyclerView)
        if (attachedFiles.isEmpty()) {
            recyclerView.visibility = View.GONE
        } else {
            recyclerView.visibility = View.VISIBLE
            attachedFilesAdapter.notifyDataSetChanged()
        }
    }
    
    private fun uploadAttachedFile(file: AttachedFile) {
        // If file is already pre-uploaded (e.g. generated from backend), skip upload
        if (file.isPreUploaded && !file.remoteUrl.isNullOrEmpty()) {
            Log.d(TAG, "File is pre-uploaded: ${file.remoteUrl}")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context ?: return@launch
            try {
                // Use CloudflareR2Service for direct upload (Better Performance/Good Practice)
                val result = com.example.tareamov.service.CloudflareR2Service.uploadFile(
                    context = ctx,
                    fileUri = file.uri,
                    folder = "uploads/chat_attachments",
                    customFileName = file.name
                )

                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.example.tareamov.service.CloudflareR2Service.UploadResult.Success -> {
                            file.remoteUrl = result.url
                            Toast.makeText(requireContext(), "Archivo subido correctamente", Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "✅ File uploaded to R2: ${result.url}")
                        }
                        is com.example.tareamov.service.CloudflareR2Service.UploadResult.Error -> {
                            Toast.makeText(requireContext(), "Error al subir archivo: ${result.message}", Toast.LENGTH_SHORT).show()
                            Log.e(TAG, "❌ Upload failed: ${result.message}")
                            // Remove failed file from list
                            removeAttachedFile(file)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    removeAttachedFile(file)
                }
            }
        }
    }

    // Inner Adapter class
    inner class AttachedFilesAdapter : androidx.recyclerview.widget.RecyclerView.Adapter<AttachedFilesAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view) {
            val fileName: TextView = view.findViewById(R.id.fileName)
            val fileType: TextView = view.findViewById(R.id.fileType)
            val removeButton: View = view.findViewById(R.id.removeFileButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_attached_file, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = attachedFiles[position]
            holder.fileName.text = file.name
            holder.fileType.text = if (file.type == "excel") "Hoja de cálculo" else "Archivo"
            holder.removeButton.setOnClickListener {
                removeAttachedFileWithRemote(file)
            }
        }

        override fun getItemCount() = attachedFiles.size
    }

    companion object {
        private const val TAG = "DatabaseQueryFragment"
        private const val CHAT_HISTORY_KEY = "saved_chat_messages"
        private const val SESSION_ID_KEY = "current_session_id"
        private const val MESSAGE_COUNT_KEY = "total_message_count"
        private const val MAX_CONTEXT_MESSAGES = 50 // Increased context history
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


        database = AppDatabase.getDatabase(requireContext())

        
        // Initialize MCP HTTP Client for tareamov-mcp-server
        mcpHttpClient = com.example.tareamov.service.MCPHttpClient(requireContext())

        // Initialize MCP connection in background with ChatBot-like fallback
        viewLifecycleOwner.lifecycleScope.launch {
            var initialized = mcpHttpClient.initialize()
            if (initialized) {
                val active = mcpHttpClient.getActiveBaseUrl() ?: "http://10.0.2.2:3000"
                Log.d(TAG, "✅ MCP HTTP client connected to server at $active")
                withContext(Dispatchers.Main) {
                    addMessageToChat("✅ Conectado a servidor MCP CourseV by YisusFactory (HTTP) \n$active", false)
                }
            } else {
                Log.w(TAG, "⚠️ MCP HTTP client initial initialization failed, attempting ChatBotFragment-style host detection...")

                // Try ChatBotFragment style detection: gateway + common hosts
                try {
                    val wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                    val dhcpInfo = wifiManager?.dhcpInfo
                    val fallbackIps = mutableListOf<String>()

                    if (dhcpInfo != null) {
                        val gatewayInt = dhcpInfo.gateway
                        val gateway = String.format(
                            "%d.%d.%d.%d",
                            gatewayInt and 0xff,
                            gatewayInt shr 8 and 0xff,
                            gatewayInt shr 16 and 0xff,
                            gatewayInt shr 24 and 0xff
                        )

                        val myIpInt = dhcpInfo.ipAddress
                        val myIp = String.format(
                            "%d.%d.%d.%d",
                            myIpInt and 0xff,
                            myIpInt shr 8 and 0xff,
                            myIpInt shr 16 and 0xff,
                            myIpInt shr 24 and 0xff
                        )

                        val networkBase = myIp.substringBeforeLast(".")
                        fallbackIps.addAll(listOf("$networkBase.90", "$networkBase.100", "$networkBase.1", "$networkBase.2", gateway))
                    }

                    // Add a few common LAN candidates and more likely host addresses
                    fallbackIps.addAll(listOf("192.168.1.90", "192.168.1.100", "192.168.1.10", "192.168.1.254"))
                    // Try also typical small office IPs near gateway
                    ServerEndpointResolver.getGatewayAddress()?.let { gw ->
                        val base = gw.substringBeforeLast('.')
                        fallbackIps.addAll(listOf("$base.1", "$base.2", "$base.10", "$base.50", "$base.100", "$base.254"))
                    }

                    // Try each candidate on port 3000 (MCP HTTP) then 3001 (API)
                    var found: String? = null
                    val socketTimeout = 1500 // ms - give slightly more time on busy networks
                    for (ip in fallbackIps) {
                        try {
                            val socket = java.net.Socket()
                            socket.connect(java.net.InetSocketAddress(ip, 3000), socketTimeout)
                            socket.close()
                            Log.d(TAG, "Fallback scan: reachable $ip:3000")
                            found = "http://$ip:3000"
                            break
                        } catch (e: Exception) {
                            Log.d(TAG, "Fallback scan: $ip:3000 not reachable (${e.message})")
                        }
                    }

                    if (found == null) {
                        for (ip in fallbackIps) {
                            try {
                                val socket = java.net.Socket()
                                socket.connect(java.net.InetSocketAddress(ip, 3001), socketTimeout)
                                socket.close()
                                Log.d(TAG, "Fallback scan: reachable $ip:3001")
                                found = "http://$ip:3001"
                                break
                            } catch (e: Exception) {
                                Log.d(TAG, "Fallback scan: $ip:3001 not reachable (${e.message})")
                            }
                        }
                    }

                    if (found != null) {
                        Log.i(TAG, "Forcing MCP base URL to $found and re-initializing client")
                        mcpHttpClient.setForcedBaseUrl(found)
                        initialized = mcpHttpClient.initialize(force = true)
                        if (initialized) {
                            withContext(Dispatchers.Main) {
                                addMessageToChat("✅ Conectado a servidor MCP (forzado): $found", false)
                            }
                        } else {
                            Log.w(TAG, "Forced initialization failed for $found")
                        }
                    } else {
                        Log.w(TAG, "No fallback host found via gateway scan")
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Error during fallback host detection: ${e.message}", e)
                }

                if (!initialized) {
                    Log.w(TAG, "⚠️ MCP HTTP client connection ultimately failed")
                    withContext(Dispatchers.Main) {
                        addMessageToChat("⚠️ No se detectó conexión con el servidor MCP. Usa IP LAN del host.", false)

                        // Prompt user to enter LAN IP manually to help testing from physical devices
                        try {
                            val input = EditText(requireContext())
                            input.hint = "e.g. 192.168.1.90:3000"
                            AlertDialog.Builder(requireContext())
                                .setTitle("Ingresar IP del servidor MCP")
                                .setMessage("Si la detección automática falló, introduce manualmente la IP LAN del host (incluye :puerto si no es 3000)")
                                .setView(input)
                                .setPositiveButton("Conectar") { _, _ ->
                                    val text = input.text.toString().trim()
                                    if (text.isNotBlank()) {
                                        // Normalize and force base URL
                                        val url = if (text.startsWith("http")) text else "http://$text"
                                        mcpHttpClient.setForcedBaseUrl(url)
                                        // Re-attempt initialization in background
                                        viewLifecycleOwner.lifecycleScope.launch {
                                            val ok = mcpHttpClient.initialize(force = true)
                                            withContext(Dispatchers.Main) {
                                                if (ok) {
                                                    addMessageToChat("✅ Conectado a servidor MCP (manual): $url", false)
                                                } else {
                                                    addMessageToChat("❌ No se pudo conectar a $url", false)
                                                }
                                            }
                                        }
                                    }
                                }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error showing manual IP dialog: ${e.message}")
                        }
                    }
                }
            }
        }

        // Initialize UI components
        setupUIComponents()
        
        // Setup chat RecyclerView with enhanced scrolling
        setupChatRecyclerView()
        
        // Setup send button
        setupSendButton()

        // Attempt to fetch current user's avatar and set it on the adapter
        lifecycleScope.launch {
                try {
                    currentUser?.let { username ->
                        val avatar = com.example.tareamov.service.SupabaseClient.fetchUsuarioAvatarByUsername(username)
                        avatar?.let { 
                            currentUserAvatar = it
                            chatAdapter.setUserAvatarUrl(it) 
                        }
                    }
                } catch (_: Exception) {
                }
            }

        // Setup floating action buttons
        setupFloatingActionButtons()

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

        // Initialize Attached Files Adapter
        attachedFilesAdapter = AttachedFilesAdapter()
        binding.attachedFilesRecyclerView.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context, androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
            adapter = attachedFilesAdapter
        }

        // Set up attachment button
        setupAttachmentButton()

        // Set up Excel button
        setupExcelButton()

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
        
        // Setup keyboard handling para que no tape el contenido (estilo ChatGPT)
        setupKeyboardHandling(view)
    }
    
    /**
     * Configura el manejo del teclado estilo ChatGPT:
     * - El header permanece fijo arriba
     * - El input se eleva con el teclado
     * - El contenido del chat se ajusta entre ambos
     */
    private fun setupKeyboardHandling(view: View) {
        val inputContainer = view.findViewById<LinearLayout>(R.id.inputContainer)
        
        // Usar WindowInsets para detectar el teclado y ajustar solo el área de input
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val navigationBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            
            // Mover el inputContainer hacia arriba cuando aparece el teclado
            val bottomPadding = if (imeInsets.bottom > 0) {
                imeInsets.bottom
            } else {
                navigationBars.bottom
            }
            
            // Aplicar el padding solo al área de input
            inputContainer.setPadding(
                inputContainer.paddingLeft,
                inputContainer.paddingTop,
                inputContainer.paddingRight,
                bottomPadding
            )
            
            // Scroll al último mensaje cuando aparece el teclado
            if (imeInsets.bottom > 0) {
                _binding?.chatRecyclerView?.post {
                    val itemCount = chatAdapter.itemCount
                    if (itemCount > 0) {
                        _binding?.chatRecyclerView?.scrollToPosition(itemCount - 1)
                    }
                }
            }
            
            insets
        }
        
        // Solicitar insets
        ViewCompat.requestApplyInsets(view)
    }
    
    private fun setupKeyboardListener() {
        keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            _binding?.let { binding ->
                val rootView = binding.root
                val rect = Rect()
                rootView.getWindowVisibleDisplayFrame(rect)
                
                val screenHeight = rootView.rootView.height
                val keypadHeight = screenHeight - rect.bottom
                
                // If keyboard is showing (more than 15% of screen height)
                if (keypadHeight > screenHeight * 0.15) {
                    // Keyboard is visible - scroll to bottom
                    if (chatAdapter.itemCount > 0) {
                        binding.chatRecyclerView.post {
                            binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)
                        }
                    }
                }
            }
        }
        
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
    }

    private fun setupUIComponents() {
        // Find the result TextView directly using findViewById with the resource ID
        resultTextView = binding.resultText

        // Set initial text
        resultTextView.text = "Sistema MCP - Consulta Inteligente\n\nUtiliza lenguaje natural para consultar la base de datos. El sistema procesará tu consulta y mostrará los resultados relevantes."

        // Update user indicator
        updateUserIndicator()

        // Make brain icon visible initially
        binding.emptyStateContainer.visibility = View.VISIBLE

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
        val roles = listOf("admin", "usuario")
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
        
        // Set user avatar from session
        val userAvatar = sessionManager.getUserAvatar()
        chatAdapter.setUserAvatarUrl(userAvatar)

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
        
        // Chat history button (now in header)
        binding.historyButton.setImageResource(R.drawable.ic_history_minimal) // Use minimalist icon
        binding.historyButton.setOnClickListener {
            showChatHistoryDialog()
        }
        
        // Clear history button in header
        binding.clearHistoryButton.setImageResource(R.drawable.ic_delete_minimal) // Use minimalist icon
        binding.clearHistoryButton.setOnClickListener {
            showClearHistoryDialog()
        }
        
        // Connection test can be triggered via history button long press
        binding.historyButton.setOnLongClickListener {
            testLLMConnection()
            true
        }
        
        // Delete/New conversation button (create new chat)
        binding.deleteConversationButton.setOnClickListener {
            showClearHistoryDialog()
        }
        
        // Export/More options button (show options menu)
        binding.exportConversationButton.setOnClickListener {
            showChatHistoryDialog()
        }
    }
    
    private fun testLLMConnection() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Show testing message
                val testMessageId = addMessageToChat("🔍 Probando conexión con servidor LLM...", false)
                
                withContext(Dispatchers.IO) {
                    // First try MCP HTTP server (preferred bridge)
                    val mcpClient = com.example.tareamov.service.MCPHttpClient(requireContext())
                    val mcpAvailable = mcpClient.initialize()

                    val activeUrl = mcpClient.getActiveBaseUrl() ?: com.example.tareamov.service.ServerEndpointResolver.RAILWAY_MCP_URL.ifEmpty { "http://10.0.2.2:3000" }

                    val status = if (mcpAvailable) {
                        "✓ MCP HTTP server disponible ($activeUrl). El LLM se puede usar a través del MCP bridge."
                    } else {
                        "❌ MCP HTTP server no disponible. Asegúrate de que el servidor Node.js esté corriendo."
                    }
                    
                    withContext(Dispatchers.Main) {
                        // Remove test message
                        chatAdapter.removeMessageById(testMessageId)
                        
                        // Show connection status
                        addMessageToChat(status, false)
                        
                        // Update connection indicator
                        val isConnected = status.contains("✓ MCP HTTP server disponible") || status.contains("✓ SERVIDOR OLLAMA CONECTADO")
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

    private fun addMessageToChat(text: String, isUser: Boolean, attachedFile: AttachedFile? = null): String {
        val message = if (isUser) {
            ChatMessage.createUserMessage(
                text, 
                senderAvatar = currentUserAvatar,
                attachedFileUrl = attachedFile?.remoteUrl ?: attachedFile?.uri?.toString(),
                attachedFileName = attachedFile?.name,
                attachedFileType = attachedFile?.type
            )
        } else {
            ChatMessage.createSystemMessage(
                text,
                attachedFileUrl = attachedFile?.remoteUrl ?: attachedFile?.uri?.toString(),
                attachedFileName = attachedFile?.name,
                attachedFileType = attachedFile?.type
            )
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
            binding.emptyStateContainer.visibility = View.GONE
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

    private fun setupSendButton() {
        binding.sendButton.setOnClickListener {
            val userInput = binding.queryInput.text.toString().trim()
            if (userInput.isNotEmpty()) {
                // Animate send button press
                val pressAnim = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.send_button_press)
                val releaseAnim = android.view.animation.AnimationUtils.loadAnimation(
                    requireContext(), R.anim.send_button_release)
                
                binding.sendButton.startAnimation(pressAnim)
                binding.sendButton.postDelayed({
                    binding.sendButton.startAnimation(releaseAnim)
                }, 200)
                
                // Clear input field first
                binding.queryInput.setText("")
                
                // Process query directly (processQuery handles message display and typing indicator)
                processQuery(userInput)
            }
        }
    }

    private fun setupExcelButton() {
        val excelButton = binding.root.findViewById<android.widget.Button>(R.id.excelButton)
        excelButton.setOnClickListener {
            val userInput = binding.queryInput.text.toString().trim()
            if (userInput.isNotEmpty()) {
                generateExcel(userInput)
                // Clear input
                binding.queryInput.setText("")
            } else {
                Toast.makeText(requireContext(), "Escribe una consulta para generar el Excel", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateExcel(query: String) {
        // Show loading state
        addMessageToChat("📊 Generando Excel para: \"$query\"...", false)
        binding.loadingSpinner.visibility = View.VISIBLE
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val baseUrl = com.example.tareamov.service.ServerEndpointResolver.RAILWAY_MCP_URL.ifEmpty {
                    "http://10.0.2.2:3000"
                }
                
                val payload = JSONObject().apply {
                    put("query", query)
                    put("userId", sessionManager.getUsername() ?: "user")
                }
                
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                    .writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = okhttp3.Request.Builder()
                    .url("$baseUrl/excel/generate-excel")
                    .post(body)
                    .build()
                
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string()
                
                withContext(Dispatchers.Main) {
                    binding.loadingSpinner.visibility = View.GONE
                    binding.sendButton.visibility = View.VISIBLE
                    
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = JSONObject(responseBody)
                            if (json.has("url")) {
                                val url = json.getString("url")
                                val filename = json.optString("filename", "reporte.xlsx")
                                val rows = json.optInt("rows", 0)
                                
                                // Create a single message with the attachment info
                                val uri = android.net.Uri.parse(url)
                                val file = AttachedFile(
                                    uri = uri,
                                    name = filename,
                                    type = "excel",
                                    remoteUrl = url,
                                    isPreUploaded = true
                                )
                                
                                // Add message with attachment
                                addMessageToChat("✅ Excel generado ($rows filas). Descargar aquí: $url", false, file)
                                
                                // Attach the generated file to the input area for "mixing with prompt"
                                try {
                                    addAttachedFile(file)
                                    addMessageToChat("📎 Archivo adjuntado automáticamente a la barra de escritura. Puedes hacer preguntas sobre él.", false)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error attaching generated file", e)
                                }
                                
                                // Open URL
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                intent.data = android.net.Uri.parse(url)
                                startActivity(intent)
                            } else {
                                addMessageToChat("⚠️ Respuesta inesperada del servidor.", false)
                            }
                        } catch (e: Exception) {
                            addMessageToChat("❌ Error procesando respuesta: ${e.message}", false)
                        }
                    } else {
                        val errorMsg = try {
                            val errJson = JSONObject(responseBody ?: "")
                            errJson.optString("error", "Error ${response.code}")
                        } catch (e: Exception) {
                            "Error ${response.code}"
                        }
                        addMessageToChat("❌ Error al generar Excel: $errorMsg", false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.loadingSpinner.visibility = View.GONE
                    addMessageToChat("❌ Error de conexión: ${e.message}", false)
                    Log.e(TAG, "Excel generation failed", e)
                }
            }
        }
    }

    private fun setupAttachmentButton() {
        binding.attachFileButton.setOnClickListener {
            // Launch file picker filtering for Excel files
            // MIME types for Excel: .xls, .xlsx
            excelPickerLauncher.launch(arrayOf(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            ))
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
                // Check MCP Server (Node.js) connection first
                val mcpConnected = mcpHttpClient.initialize()
                
                if (mcpConnected) {
                    updateConnectionStatus(true, "Conectado (MCP)")
                    Log.d("DatabaseQueryFragment", "✓ MCP Server is reachable")
                } else {
                    updateConnectionStatus(false, "Desconectado")
                    Log.w("DatabaseQueryFragment", "⚠️ No servers reachable")
                    // Optionally show a message to the user
                    addMessageToChat("""
                        ⚠️ No se detectó conexión con el servidor MCP.
                        
                        Asegúrate de que el servidor Node.js esté corriendo (puerto 3000).
                        
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
        // Connection status UI removed for minimalist design
        // Status is shown via Toast or chat messages instead
        Log.d("DatabaseQueryFragment", "Connection status: ${if (isConnected) statusText ?: "Conectado" else statusText ?: "Desconectado"}")
    }

    private fun isExcelRequest(lowerQuery: String): Boolean {
        // Palabras clave directas de Excel
        val excelKeywords = listOf(
            "excel", "hoja de cálculo", "hoja de calculo", "spreadsheet", "xls", "xlsx", "csv"
        )
        if (excelKeywords.any { lowerQuery.contains(it) }) return true

        // Detección de intención de exportación/reporte (ej: "dame un reporte", "descargar datos")
        // Esto evita que el LLM responda con texto cuando el usuario quiere un archivo
        val exportActions = listOf("generar", "crear", "dame", "descargar", "exportar", "necesito", "quiero")
        val exportObjects = listOf("reporte", "informe", "listado", "tabla", "archivo", "documento")
        
        val hasAction = exportActions.any { lowerQuery.contains(it) }
        val hasObject = exportObjects.any { lowerQuery.contains(it) }
        
        // Si hay acción + objeto (ej: "generar reporte"), asumimos que quiere un Excel
        // especialmente si pide "datos"
        if (hasAction && hasObject) return true
        
        // Caso específico: "necesito los datos de..."
        if (lowerQuery.contains("datos de") && (lowerQuery.contains("necesito") || lowerQuery.contains("dame"))) {
            return true
        }

        return false
    }

    private fun processQuery(query: String) {
        Log.d("DatabaseQueryFragment", "=== MAIN QUERY PROCESSING ===")
        Log.d("DatabaseQueryFragment", "User Query: $query")
        
        // Add user message to chat first
        val attachedFile = if (attachedFiles.isNotEmpty()) attachedFiles[0] else null
        addMessageToChat(query, true, attachedFile)
        
        // Immediately clear attached files from UI after sending
        if (attachedFiles.isNotEmpty()) {
            attachedFiles.clear()
            updateAttachedFilesUI()
        }
        
        // Show spinner instead of typing indicator
        binding.sendButton.visibility = View.GONE
        binding.loadingSpinner.visibility = View.VISIBLE
        scrollToBottom(smooth = true)

        binding.chartContainer.visibility = View.GONE // Hide chart container
        removeCurrentChart() // Remove previous chart if any

        // Check if attached files exist (using captured variable)
        if (attachedFile != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val remoteUrl = attachedFile.remoteUrl
                    
                    if (remoteUrl != null) {
                        Log.d("DatabaseQueryFragment", "📎 Processing query with attached file: ${attachedFile.name}")
                        
                        // Construct JSON Content
                        val jsonContent = JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "file")
                                put("uri", remoteUrl)
                                put("name", attachedFile.name)
                            })
                        }.toString()
                        
                        val result = mcpHttpClient.processPromptWithAttachments(query, jsonContent)
                        
                        // Hide spinner
                        binding.loadingSpinner.visibility = View.GONE
                        binding.sendButton.visibility = View.VISIBLE
                        
                        if (result.success) {
                            // Display response
                            val responseText = if (result.data is JSONObject) {
                                result.data.optString("response", result.data.toString())
                            } else {
                                result.data?.toString() ?: "Respuesta vacía"
                            }
                            addMessageToChat(responseText, false)
                        } else {
                            addMessageToChat("❌ Error: ${result.error}", false)
                        }
                    } else {
                         // Fallback if upload failed or pending
                        binding.loadingSpinner.visibility = View.GONE
                        binding.sendButton.visibility = View.VISIBLE
                        addMessageToChat("⚠️ El archivo adjunto aún no se ha subido o falló la subida. Inténtalo de nuevo.", false)
                    }
                } catch (e: Exception) {
                    binding.loadingSpinner.visibility = View.GONE
                    binding.sendButton.visibility = View.VISIBLE
                    addMessageToChat("❌ Error procesando archivo: ${e.message}", false)
                }
            }
            return
        }

        // Check if this is an Excel generation request
        val lowerQuery = query.lowercase().trim()
        if (isExcelRequest(lowerQuery)) {
            Log.d("DatabaseQueryFragment", "📊 Excel generation request detected")
            generateExcel(query)
            return
        }

        // Check if this is a Business Intelligence query
        if (isBIQuery(lowerQuery)) {
            Log.d("DatabaseQueryFragment", "🎯 Business Intelligence query detected - using VS Code style response")
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val result = handleBIQuery(query)
                    
                    // Hide spinner
                    binding.loadingSpinner.visibility = View.GONE
                    binding.sendButton.visibility = View.VISIBLE
                    
                    if (result.isNullOrBlank()) {
                        addMessageToChat("⚠️ No se pudo generar el análisis de Business Intelligence.", false)
                    } else {
                        addMessageToChat(result, false)
                    }
                } catch (e: Exception) {
                    // Hide spinner
                    binding.loadingSpinner.visibility = View.GONE
                    binding.sendButton.visibility = View.VISIBLE
                    
                    Log.e("DatabaseQueryFragment", "Error processing BI query", e)
                    addMessageToChat("❌ Error generando análisis BI: ${e.message}", false)
                } finally {
                    isProcessingQuery = false
                    saveChatHistory()
                }
            }
            return
        }

        // ALWAYS use MCP Server (DeepSeek) for ALL queries
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("DatabaseQueryFragment", "Delegating query to MCP Server (DeepSeek)...")
                
                // Use MCP Server directly (Server-side Agent)
                val result = processQueryWithMCPServer(query)
                
                // Enviar notificación por email automáticamente después de obtener la respuesta
                sendNotificationAfterResponse(result)

                Log.d("DatabaseQueryFragment", "=== FINAL RESULT LOG ===")
                Log.d("DatabaseQueryFragment", "Result Length: ${result.length} characters")
                Log.d("DatabaseQueryFragment", "Result Content: $result")
                Log.d("DatabaseQueryFragment", "=======================")

                // Check if the response is a graph request
                if (result.startsWith("GRAPH_REQUEST:")) {
                    // Hide spinner
                    binding.loadingSpinner.visibility = View.GONE
                    binding.sendButton.visibility = View.VISIBLE
                    
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
                    // Check if binding is still valid before accessing it
                    if (_binding != null) {
                        // Hide spinner
                        binding.loadingSpinner.visibility = View.GONE
                        binding.sendButton.visibility = View.VISIBLE
                    }
                    
                    // Display the text result in chat
                    if (result.isNullOrBlank()) {
                        addMessageToChat("⚠️ No se recibió respuesta del sistema. Intente reformular su consulta.", false)
                    } else {
                        // Display the response exactly as received (VS Code style)
                        addMessageToChat(result, false)
                    }
                }

            } catch (e: Exception) {
                // Check if binding is still valid before accessing it
                if (_binding != null) {
                    // Hide spinner
                    binding.loadingSpinner.visibility = View.GONE
                    binding.sendButton.visibility = View.VISIBLE
                }
                
                Log.e("DatabaseQueryFragment", "Error processing query", e)
                val errorMessage = "❌ Error procesando la consulta: ${e.message}"
                addMessageToChat(errorMessage, false)
            } finally {
                isProcessingQuery = false
                // Save updated chat history
                saveChatHistory()
            }
        }

    }
    
    /**
     * Detect if query is asking for Business Intelligence analysis
     */
    /**
     * Send email notification to current user after LLM response
     * 🔒 SEGURIDAD: NO envía la respuesta completa por email
     * Solo notifica que hay una respuesta disponible
     * 
     * NUEVO: También envía notificación push si la app está en background
     */
    private suspend fun sendNotificationAfterResponse(responsePreview: String) = withContext(Dispatchers.IO) {
        try {
            val currentUserId = sessionManager.getUserId()
            if (currentUserId == null || currentUserId <= 0) {
                Log.w(TAG, "⚠️ No user ID available for notification")
                return@withContext
            }
            
            Log.d(TAG, "📧 Sending email notification to user $currentUserId (without sensitive data)")
            
            // Obtener la URL base del backend (priorizar la conexión activa del cliente MCP)
            val activeUrl = mcpHttpClient.getActiveBaseUrl()
            val baseUrl = if (!activeUrl.isNullOrBlank()) {
                activeUrl
            } else {
                com.example.tareamov.service.ServerEndpointResolver.RAILWAY_MCP_URL.ifEmpty {
                    "http://10.0.2.2:3000"  // Fallback para emulador
                }
            }
            
            // 🔒 SEGURIDAD: NO enviar la respuesta completa
            // El backend solo notifica que hay una respuesta disponible
            val payload = JSONObject().apply {
                put("userId", currentUserId)
                // responsePreview se ignora en el backend por seguridad
                put("responsePreview", "")  // Vacío por seguridad
            }
            
            // Hacer la petición HTTP POST al backend (email)
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/notify-chat-response")
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Email notification sent successfully to user $currentUserId (secure mode)")
            } else {
                Log.w(TAG, "⚠️ Email notification failed: ${response.code} - ${response.message}")
            }
            
            response.close()
            
            // 📱 NUEVO: Enviar notificación push si la app está en background
            sendPushNotification(currentUserId, responsePreview)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending notification: ${e.message}", e)
        }
    }
    
    /**
     * Envía notificación push cuando la app está en background
     * Utiliza Firebase Cloud Messaging via backend
     */
    private suspend fun sendPushNotification(userId: Long, responsePreview: String) = withContext(Dispatchers.IO) {
        try {
            // Verificar si la app está en background
            val isInBackground = !isAppInForeground()
            
            if (!isInBackground) {
                Log.d(TAG, "📱 App en foreground, skip push notification")
                return@withContext
            }
            
            Log.d(TAG, "📱 App en background, enviando push notification para userId $userId")
            
            val baseUrl = com.example.tareamov.service.ServerEndpointResolver.RAILWAY_MCP_URL.ifEmpty {
                "http://10.0.2.2:3000"
            }
            
            // Preparar datos de la notificación
            val notificationPayload = JSONObject().apply {
                put("userId", userId)
                put("title", "🤖 Nueva respuesta del chat")
                put("body", "Tu consulta ha sido procesada. Toca para ver la respuesta.")
                put("data", JSONObject().apply {
                    put("type", "chat_response")
                    put("userId", userId)
                    put("action", "OPEN_CHAT")
                })
            }
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val body = notificationPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = okhttp3.Request.Builder()
                .url("$baseUrl/send-push-notification")
                .post(body)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                Log.d(TAG, "✅ Push notification enviada exitosamente")
            } else {
                Log.w(TAG, "⚠️ Push notification failed: ${response.code} - ${response.message}")
            }
            
            response.close()
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error sending push notification: ${e.message}", e)
        }
    }
    
    /**
     * Detecta si la aplicación está en foreground
     */
    private fun isAppInForeground(): Boolean {
        val activityManager = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val appProcesses = activityManager.runningAppProcesses ?: return false
        
        val packageName = requireContext().packageName
        for (appProcess in appProcesses) {
            if (appProcess.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND 
                && appProcess.processName == packageName) {
                return true
            }
        }
        return false
    }
    
    private fun isBIQuery(lowerQuery: String): Boolean {
        val biKeywords = listOf(
            "inteligencia", "kpi", "business intelligence", "indicador",
            "decisiones críticas", "decisiones criticas", 
            "mejorar", "opciones empresariales", "estrategia",
            "análisis", "analisis", "dashboard", "métricas", "metricas"
        )
        return biKeywords.any { lowerQuery.contains(it) }
    }
    
    /**
     * Handle Business Intelligence queries with VS Code style responses
     * OPTIMIZED: Delegates to MCP Server (Node.js) to avoid slow on-device inference
     */
    private suspend fun handleBIQuery(query: String): String = withContext(Dispatchers.IO) {
        Log.d("DatabaseQueryFragment", "handleBIQuery: Delegating to MCP Server for speed")
        
        // Build a prompt that instructs the Server Agent to generate the report
        // We do NOT fetch schema here to save bandwidth/time; the server has the schema.
        val vsCodePrompt = """
CONSULTA DE BUSINESS INTELLIGENCE: $query

INSTRUCCIONES:
Eres un experto en Business Intelligence y Marketing.
Tu objetivo es analizar la base de datos y dar recomendaciones estratégicas.

1. El esquema de la base de datos YA ESTÁ en tu contexto. NO llames a 'get_database_schema'.
2. Usa 'query_database' para obtener métricas reales (usuarios, cursos, ventas, etc.).
3. Genera un reporte con el siguiente formato EXACTO (Markdown):

## Resumen ejecutivo
[Objetivo y hallazgos principales]

## Decisiones críticas
1. [Decisión 1]
2. [Decisión 2]

## KPIs priorizados
1. [KPI 1]
2. [KPI 2]

## Ejemplos de SQL
```sql
-- Ejemplo relevante
SELECT ...
```

## Acción inmediata
[Acción concreta]

IMPORTANTE: Basa tus respuestas en DATOS REALES de la base de datos.
        """.trimIndent()
        
        Log.d("DatabaseQueryFragment", "Sending BI prompt to MCP Server...")
        
        // Use the server-side agent which is much faster than on-device LLM
        return@withContext processQueryWithMCPServer(vsCodePrompt)
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
            
            // Create arguments for the tool
            val args = JSONObject().put("query", query)
            
            // Call the tool 'query_database' on the MCP server
            // This triggers the backend agent (DeepSeek) if the query is natural language
            var result = mcpHttpClient.executeTool("query_database", args)
            
            // Auto-retry if not reachable or connection failed
            if (!result.success && (result.error?.contains("reachable") == true || result.error?.contains("failed") == true)) {
                Log.w(TAG, "⚠️ First attempt failed (${result.error}), re-initializing MCP client and retrying...")
                // Force re-initialization to find a valid host
                if (mcpHttpClient.initialize(force = true)) {
                    Log.i(TAG, "✅ Re-initialization successful, retrying query...")
                    result = mcpHttpClient.executeTool("query_database", args)
                } else {
                    Log.e(TAG, "❌ Re-initialization failed")
                }
            }

            // Retry for "timeout" errors specifically (often due to cold start)
            if (!result.success && result.error?.contains("timeout", ignoreCase = true) == true) {
                 Log.w(TAG, "⚠️ Request timed out, retrying once...")
                 result = mcpHttpClient.executeTool("query_database", args)
            }
            
            if (result.success && result.data != null) {
                val data = result.data
                
                // If data is a JSONObject, check if it has a "data" field (common pattern in this app)
                if (data is JSONObject) {
                    if (data.has("data")) {
                        val innerData = data.get("data")
                        if (innerData is String) return@withContext innerData
                        return@withContext innerData.toString()
                    }
                    // If no "data" field, return the whole object as string
                    return@withContext formatMCPJsonObjectResult(data)
                }
                
                // If data is a JSONArray, format it
                if (data is JSONArray) {
                    return@withContext formatMCPJsonArrayResults(data)
                }
                
                // If it's a string or anything else
                return@withContext data.toString()
            }
            
            return@withContext result.error ?: "Respuesta vacía del servidor."
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception using MCP HTTP server: ${e.message}", e)
            return@withContext "Error al conectar con el servidor MCP: ${e.message}"
        }
    }
    
    /**
     * Format JSON array results from MCP - Estilo cards limpias como ChatGPT
     */
    private fun formatMCPJsonArrayResults(data: JSONArray): String {
        if (data.length() == 0) return "No se encontraron resultados."
        
        // Check if it's a simple count result
        if (data.length() == 1) {
            val item = data.getJSONObject(0)
            if (item.length() == 1 && (item.has("count") || item.has("COUNT"))) {
                val count = item.optString("count", item.optString("COUNT"))
                return "📊 Total: $count registros"
            }
        }
        
        val sb = StringBuilder()
        
        // Get all columns from first item
        val firstItem = data.getJSONObject(0)
        val columns = mutableListOf<String>()
        val keys = firstItem.keys()
        while (keys.hasNext()) {
            columns.add(keys.next())
        }
        
        // Build column labels (friendly names)
        val columnLabels = columns.map { col ->
            when (col.lowercase()) {
                "id" -> "ID"
                "usuario" -> "Usuario"
                "email" -> "Email"
                "nombre_completo" -> "Nombre"
                "telefono" -> "Teléfono"
                "rol" -> "Rol"
                "activo" -> "Activo"
                "fecha_registro" -> "Registro"
                "title", "titulo" -> "Título"
                "description", "descripcion" -> "Descripción"
                "created_at" -> "Creado"
                "updated_at" -> "Actualizado"
                "name", "nombre" -> "Nombre"
                else -> col.replaceFirstChar { it.uppercase() }.replace("_", " ")
            }
        }
        
        // Formato de cards verticales - más limpio en móvil
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            
            // Número de registro
            sb.append("━━━ ${i + 1} ━━━\n")
            
            // Cada campo en una línea
            columns.forEachIndexed { index, col ->
                val value = item.opt(col)?.toString() ?: "-"
                val label = columnLabels[index]
                sb.append("$label: $value\n")
            }
            
            // Espaciado entre registros
            if (i < data.length() - 1) {
                sb.append("\n")
            }
        }
        
        // Resumen final
        sb.append("\n━━━━━━━━━━━━━━\n")
        sb.append("Total: ${data.length()} resultados")
        
        return sb.toString()
    }
    
    /**
     * Format JSON object result from MCP - Estilo card minimalista
     */
    private fun formatMCPJsonObjectResult(data: JSONObject): String {
        if (data.length() == 0) return "No se encontraron resultados."
        
        // Check for count result
        if (data.length() == 1 && (data.has("count") || data.has("COUNT"))) {
            val count = data.optString("count", data.optString("COUNT"))
            return "📊 Total: $count registros"
        }
        
        val sb = StringBuilder()
        sb.append("━━━ Resultado ━━━\n")
        
        val keys = data.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val label = when (key.lowercase()) {
                "id" -> "ID"
                "usuario" -> "Usuario"
                "email" -> "Email"
                "nombre_completo" -> "Nombre"
                "telefono" -> "Teléfono"
                "rol" -> "Rol"
                "activo" -> "Activo"
                "fecha_registro" -> "Registro"
                else -> key.replaceFirstChar { it.uppercase() }.replace("_", " ")
            }
            sb.append("$label: ${data.get(key)}\n")
        }
        return sb.toString().trim()
    }
    
    /**
     * Format list results from MCP - Estilo cards limpias como ChatGPT
     */
    private fun formatMCPListResults(data: List<*>): String {
        if (data.isEmpty()) return "No se encontraron resultados."
        
        // Check if it's a simple count result
        if (data.size == 1 && data[0] is Map<*, *>) {
            val map = data[0] as Map<*, *>
            if (map.size == 1 && (map.containsKey("count") || map.containsKey("COUNT"))) {
                return "📊 Total: ${map.values.first()} registros"
            }
        }
        
        val sb = StringBuilder()
        
        // Get columns from first item
        val firstItem = data[0]
        if (firstItem is Map<*, *>) {
            val columns = firstItem.keys.map { it.toString() }
            
            // Build column labels (friendly names)
            val columnLabels = columns.map { col ->
                when (col.lowercase()) {
                    "id" -> "ID"
                    "usuario" -> "Usuario"
                    "email" -> "Email"
                    "nombre_completo" -> "Nombre"
                    "telefono" -> "Teléfono"
                    "rol" -> "Rol"
                    "activo" -> "Activo"
                    "fecha_registro" -> "Registro"
                    else -> col.replaceFirstChar { it.uppercase() }.replace("_", " ")
                }
            }
            
            // Formato de cards verticales - más limpio en móvil
            data.forEachIndexed { i, item ->
                if (item is Map<*, *>) {
                    // Número de registro
                    sb.append("━━━ ${i + 1} ━━━\n")
                    
                    // Cada campo en una línea
                    columns.forEachIndexed { index, col ->
                        val value = item[col]?.toString() ?: "-"
                        val label = columnLabels[index]
                        sb.append("$label: $value\n")
                    }
                    
                    // Espaciado entre registros
                    if (i < data.size - 1) {
                        sb.append("\n")
                    }
                }
            }
            
            // Resumen final
            sb.append("\n━━━━━━━━━━━━━━\n")
            sb.append("Total: ${data.size} resultados")
        } else {
            // Lista simple numerada
            data.forEachIndexed { index, item ->
                sb.append("${index + 1}. $item\n")
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
                val creatorCounts = subscriptions.groupBy { it.creatorId }
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

    /**
     * Attempt to extract the first JSON object found anywhere in a text blob.
     * This is useful because some LLMs emit prose around a JSON tool call.
     */
    private fun parseFirstJsonObject(text: String): JSONObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        for (i in start until text.length) {
            val c = text[i]
            if (c == '{') depth++
            else if (c == '}') depth--

            if (depth == 0) {
                val candidate = text.substring(start, i + 1)
                try {
                    return JSONObject(candidate)
                } catch (e: Exception) {
                    // If parse failed, continue searching for next '{'
                    val nextStart = text.indexOf('{', start + 1)
                    if (nextStart <= start) return null
                    return parseFirstJsonObject(text.substring(nextStart))
                }
            }
        }
        return null
    }

    /**
     * Build a compact JSON-like schema summary by querying local Room DAOs
     * This runs on IO dispatcher and is a best-effort fallback when MCP is unreachable.
     */
    private suspend fun getLocalSchemaSummary(): String = withContext(Dispatchers.IO) {
        val schemaObj = JSONObject()
        val tables = JSONObject()

        try {
            try {
                val usuarios = database.usuarioDao().getAllUsuarios()
                val obj = JSONObject()
                obj.put("count", usuarios.size)
                obj.put("exists", true)
                tables.put("usuarios", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val personas = database.personaDao().getAllPersonasList()
                val obj = JSONObject()
                obj.put("count", personas.size)
                obj.put("exists", true)
                tables.put("personas", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val videos = database.videoDao().getAllVideos()
                val obj = JSONObject()
                obj.put("count", videos.size)
                obj.put("exists", true)
                tables.put("videos", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val courses = database.courseDao().getAllCourses()
                val obj = JSONObject()
                obj.put("count", courses.size)
                obj.put("exists", true)
                tables.put("courses", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val topics = database.topicDao().getAllTopics()
                val obj = JSONObject()
                obj.put("count", topics.size)
                obj.put("exists", true)
                tables.put("topics", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val contentItems = database.contentItemDao().getAllContentItems()
                val obj = JSONObject()
                obj.put("count", contentItems.size)
                obj.put("exists", true)
                tables.put("content_items", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val tasks = database.taskDao().getAllTasks()
                val obj = JSONObject()
                obj.put("count", tasks.size)
                obj.put("exists", true)
                tables.put("tasks", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val submissions = database.taskSubmissionDao().getAllTaskSubmissions()
                val obj = JSONObject()
                obj.put("count", submissions.size)
                obj.put("exists", true)
                tables.put("task_submissions", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val subs = database.subscriptionDao().getAllSubscriptions()
                val obj = JSONObject()
                obj.put("count", subs.size)
                obj.put("exists", true)
                tables.put("subscriptions", obj)
            } catch (_: Exception) { /* ignore */ }

            // Add timestamp
            schemaObj.put("schema", tables)
            schemaObj.put("generated_at", System.currentTimeMillis())
        } catch (e: Exception) {
            // If anything goes wrong, return an empty schema message
            return@withContext "{\"schema\": {}, \"note\": \"failed to introspect local DB: ${e.message}\"}"
        }

        return@withContext schemaObj.toString(2)
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
                    binding.emptyStateContainer.visibility = View.GONE
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
            
        binding.emptyStateContainer.visibility = View.VISIBLE
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
        binding.emptyStateContainer.visibility = View.VISIBLE
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
        
        val options = arrayOf("Exportar Chat", "Nueva Sesión")

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Opciones")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> exportChatHistory()
                    1 -> startNewSession()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // toggleExcelMode removed
    
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
        
        val welcomeText = """
🎯 ¡Bienvenido/a, $username!

Estás conectado al sistema de Consulta Inteligente con IA (DeepSeek-V3.2-Speciale).

💬 **¿Cómo funciona?**
Simplemente escribe tu consulta en lenguaje natural. El modelo DeepSeek ejecutándose en nuestros servidores seguros analizará tu petición y consultará la base de datos automáticamente.

📊 **Capacidades:**
  • Consultas a la base de datos en tiempo real
  • Análisis de esquemas y relaciones
  • Generación de gráficos y visualizaciones
  • Respuestas contextuales
  • Privacidad total (Servidores Seguros)

✨ Escribe tu consulta para empezar.
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
    val roles = "admin, usuario"
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
            // Clear the input field first
            binding.queryInput.setText("")

            // Process query through the unified LLM flow (not RAG-only)
            processQuery(query)
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
                    
                    // Show spinner for new response
                    binding.sendButton.visibility = View.GONE
                    binding.loadingSpinner.visibility = View.VISIBLE
                    scrollToBottom(smooth = true)
                    
                    try {
                        val result = processQueryWithMCPServer(newText)
                        
                        // Hide spinner
                        binding.loadingSpinner.visibility = View.GONE
                        binding.sendButton.visibility = View.VISIBLE
                        
                        // Add new bot response
                        addMessageToChat(result, false)
                        
                    } catch (e: Exception) {
                        // Hide spinner
                        binding.loadingSpinner.visibility = View.GONE
                        binding.sendButton.visibility = View.VISIBLE
                        
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
        
        // Remove keyboard listener to avoid memory leaks
        keyboardLayoutListener?.let { listener ->
            binding.root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        keyboardLayoutListener = null
        
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