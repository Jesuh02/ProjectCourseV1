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
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
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
import com.example.tareamov.work.BackgroundTaskManager
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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.example.tareamov.service.ServerEndpointResolver
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import android.provider.OpenableColumns
import android.widget.EditText
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.widget.ImageButton
import com.example.tareamov.ui.widget.VoiceVisualizerView

class DatabaseQueryFragment : Fragment(), SessionManager.UserChangeListener {

    // Voice Recognition
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceIntent: Intent? = null
    private var isListening = false
    private lateinit var voiceInputLayout: LinearLayout
    private lateinit var queryInputLayout: LinearLayout
    private lateinit var voiceVisualizer: VoiceVisualizerView
    private lateinit var stopRecordingButton: ImageButton
    private lateinit var cancelVoiceButton: ImageButton
    private lateinit var voiceStatusText: TextView
    private lateinit var micButton: ImageButton
    
    // Permission
    private val requestAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startListening()
        } else {
            Toast.makeText(context, "Permiso de micrófono requerido para voz", Toast.LENGTH_SHORT).show()
        }
    }

    private var _binding: FragmentDatabaseQueryBinding? = null
    private val binding get() = _binding!!

    private lateinit var resultTextView: TextView
    // database property removed – all data now fetched via BackendApiService
    private var currentChart: View? = null
    private lateinit var chatAdapter: DatabaseChatAdapter

    private lateinit var sessionManager: SessionManager
    
    // MCP HTTP Client for CourseV by YisusFactory MCP Server (connects via HTTP to Node.js server)
    private lateinit var mcpHttpClient: com.example.tareamov.service.MCPHttpClient
    
    // TTS Service
    private lateinit var ttsService: com.example.tareamov.service.TTSService
    
    // Background task tracking - also used for chat state
    private var isProcessingQuery = false
    private var pendingQuery: String? = null
    private var targetMessageId: String? = null
    
    // Enhanced chat state management per user
    private val chatHistory = mutableListOf<ChatMessage>()
    private var currentConversationContext = mutableListOf<String>()
    private var totalMessageCount = 0
    private var isScrolledToBottom = true
    private var currentUser: String? = null
    private var currentUserAvatar: String? = null
    // Keep the last Supabase GET URL for display with final results
    private var lastSupabaseUrl: String? = null
    
    // User-specific SharedPreferences for better persistence
    // Use nullable to avoid crash when context is not available
    private var _chatPrefs: android.content.SharedPreferences? = null
    private val chatPrefs: android.content.SharedPreferences
        get() {
            if (_chatPrefs == null && isAdded && context != null) {
                _chatPrefs = sessionManager.getChatPreferences(requireContext())
            }
            return _chatPrefs ?: context?.getSharedPreferences("fallback_chat_prefs", Context.MODE_PRIVATE)
                ?: throw IllegalStateException("Context not available")
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

    override fun onAttach(context: Context) {
        super.onAttach(context)
        try {
            mcpHttpClient = com.example.tareamov.service.MCPHttpClient(requireContext())
        } catch (e: Exception) {
            Log.w("DatabaseQueryFragment", "Failed to init MCPHttpClient: ${e.message}")
        }

        // Quickly prefer local cached host if reachable, otherwise fall back to cloud
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Honor forced URL from prefs first
                ServerEndpointResolver.getForcedMcpBaseUrl()?.let { forced ->
                    try {
                        if (ServerEndpointResolver.isServiceReachable(forced)) {
                            mcpHttpClient.setForcedBaseUrl(forced)
                            Log.i("DatabaseQueryFragment", "Using forced MCP URL from prefs: $forced")
                            return@launch
                        } else {
                            Log.w("DatabaseQueryFragment", "Forced MCP URL not reachable: $forced")
                        }
                    } catch (_: Exception) {}
                }

                // Try fast resolve (gateway + subnet candidates) with short timeout
                try {
                    val resolved = kotlinx.coroutines.withTimeoutOrNull(120) { ServerEndpointResolver.fastResolveMcpBaseUrl() }
                    if (!resolved.isNullOrBlank()) {
                        mcpHttpClient.setForcedBaseUrl(resolved)
                        Log.i("DatabaseQueryFragment", "Fast-resolved MCP base URL: $resolved")
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.d("DatabaseQueryFragment", "fastResolve failed: ${e.message}")
                }

                // Cloud fallback
                mcpHttpClient.setForcedBaseUrl(ServerEndpointResolver.RAILWAY_MCP_URL)
                Log.i("DatabaseQueryFragment", "Falling back to cloud MCP: ${ServerEndpointResolver.RAILWAY_MCP_URL}")
            } catch (e: Exception) {
                Log.w("DatabaseQueryFragment", "Error resolving MCP base: ${e.message}")
            }
        }
    }

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
                                val ok = com.example.tareamov.service.StorageHelper.deleteFile(candidate)
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
                // Use StorageHelper for upload via backend
                val result = com.example.tareamov.service.StorageHelper.uploadFile(
                    context = ctx,
                    fileUri = file.uri,
                    folder = "uploads/chat_attachments",
                    customFileName = file.name
                )

                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.example.tareamov.service.StorageHelper.UploadResult.Success -> {
                            file.remoteUrl = result.url
                            Toast.makeText(requireContext(), "Archivo subido correctamente", Toast.LENGTH_SHORT).show()
                            Log.d(TAG, "✅ File uploaded: ${result.url}")
                        }
                        is com.example.tareamov.service.StorageHelper.UploadResult.Error -> {
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
        
        arguments?.getString("messageId")?.let { id ->
            targetMessageId = id
            // Log for debugging
            Log.d("DatabaseQueryFragment", "🔔 Notification navigation: target messageId=$targetMessageId")
        }

        // Initialize SessionManager and register for user changes
        sessionManager = SessionManager.getInstance(requireContext())
        SessionManager.addUserChangeListener(this)
        currentUser = sessionManager.getUsername()


        // BackendApiService initialized globally (no local database needed)
        
        // Initialize TTS Service
        ttsService = com.example.tareamov.service.TTSService.getInstance(requireContext())

        
        // Initialize MCP HTTP Client for tareamov-mcp-server
        mcpHttpClient = com.example.tareamov.service.MCPHttpClient(requireContext())

        // Initialize MCP connection in background with ChatBot-like fallback
        viewLifecycleOwner.lifecycleScope.launch {
            var initialized = mcpHttpClient.initialize()
            if (initialized) {
                val active = mcpHttpClient.getActiveBaseUrl() ?: "http://10.0.2.2:3000"
                Log.d(TAG, "✅ MCP HTTP client connected to server at $active")
                withContext(Dispatchers.Main) {
                    addMessageToChat("✅ Conectado a servidor MCP CourseV by YisusFactory (HTTP)", false)
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
                                addMessageToChat("✅ Conectado a servidor MCP (forzado)", false)
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
                                                    addMessageToChat("✅ Conectado a servidor MCP (manual)", false)
                                                } else {
                                                    addMessageToChat("❌ No se pudo conectar al servidor MCP", false)
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
                        val userResult = withContext(Dispatchers.IO) { BackendApiService.getUserByUsername(username) }
                        val avatar = if (userResult is ApiResult.Success) userResult.data?.avatar else null
                        if (!avatar.isNullOrBlank()) {
                            currentUserAvatar = avatar
                            sessionManager.saveUserAvatar(avatar) // Persist so getUserAvatar() works next time
                            chatAdapter.setUserAvatarUrl(avatar)
                        }
                    }
                } catch (_: Exception) {
                }
            }

        // Setup floating action buttons
        setupFloatingActionButtons()

        // Request listener removed – no longer using SupabaseClient directly;
        // all data is fetched via BackendApiService.

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

        // Welcome message is shown by loadHistoryFromBackend / restoreChatHistory if no history

        




























        
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
        
        setupVoiceRecognition()

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

    private fun showEditMessageDialog(message: ChatMessage) {
        val context = context ?: return
        
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_message, null)
        val editMessageInput = dialogView.findViewById<EditText>(R.id.editMessageInput)
        val saveButton = dialogView.findViewById<View>(R.id.saveButton)
        val cancelButton = dialogView.findViewById<View>(R.id.cancelButton)
        
        editMessageInput.setText(message.text)
        editMessageInput.setSelection(message.text.length)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(true)
            .create()
            
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        saveButton.setOnClickListener {
            val newText = editMessageInput.text.toString().trim()
            if (newText.isNotEmpty() && newText != message.text) {
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
        // 1. Update message in list
        val index = chatHistory.indexOfFirst { it.messageId == message.messageId }
        if (index != -1) {
            // Update the message
            val updatedMessage = message.copy(text = newText)
            chatHistory[index] = updatedMessage
            
            // Remove subsequent messages
            if (index < chatHistory.size - 1) {
                val countToRemove = chatHistory.size - 1 - index
                repeat(countToRemove) {
                    chatHistory.removeAt(chatHistory.size - 1)
                }
            }
            
            // Reload adapter
            chatAdapter.clear()
            chatHistory.forEach { chatAdapter.addMessage(it) }
            
            // 2. Resend to LLM (skip adding user message again)
            processQuery(newText, skipUserMessage = true)
        }
    }

    private fun setupChatRecyclerView() {
        chatAdapter = DatabaseChatAdapter(
            onTTSClick = { message ->
                handleTTSClick(message)
            },
            onEditClick = { message ->
                showEditMessageDialog(message)
            }
        )
        
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
                        "✓ MCP HTTP server disponible. El LLM se puede usar a través del MCP bridge."
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

    private fun addMessageToChat(text: String, isUser: Boolean, attachedFile: AttachedFile? = null, excelUrl: String? = null): String {
        val message = if (isUser) {
            ChatMessage.createUserMessage(
                text, 
                senderAvatar = currentUserAvatar,
                attachedFileUrl = attachedFile?.remoteUrl ?: attachedFile?.uri?.toString(),
                attachedFileName = attachedFile?.name,
                attachedFileType = attachedFile?.type,
                excelUrl = excelUrl
            )
        } else {
            ChatMessage.createSystemMessage(
                text,
                attachedFileUrl = attachedFile?.remoteUrl ?: attachedFile?.uri?.toString(),
                attachedFileName = attachedFile?.name,
                attachedFileType = attachedFile?.type,
                senderAvatar = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png",
                excelUrl = excelUrl
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

        // Sync to backend for permanent persistence
        syncMessageToBackend(text, isUser, excelUrl)

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
                // Using centralized service
                val result = BackendApiService.generateExcel(query)
                
                withContext(Dispatchers.Main) {
                    binding.loadingSpinner.visibility = View.GONE
                    binding.sendButton.visibility = View.VISIBLE
                    
                    if (result is ApiResult.Success && result.data != null) {
                        try {
                            val json = result.data
                            if (json.has("url")) {
                                val url = json.get("url").asString
                                val filename = if (json.has("filename")) json.get("filename").asString else "reporte.xlsx"
                                val rows = if (json.has("rows")) json.get("rows").asInt else 0
                                
                                // Create a single message with the attachment info (WITHOUT adding to input bar)
                                val uri = android.net.Uri.parse(url)
                                val file = AttachedFile(
                                    uri = uri,
                                    name = filename,
                                    type = "excel",
                                    remoteUrl = url,
                                    isPreUploaded = true
                                )
                                
                                // Add message with attachment (file only in message metadata, NOT in input bar)
                                val msgText = if (rows == 0) {
                                    "⚠️ Excel generado pero sin filas (archivo vacío). Revisa la consulta o abre el archivo para detalles. $url"
                                } else {
                                    "✅ Excel generado ($rows filas). Descargar aquí: $url"
                                }
                                addMessageToChat(msgText, false, file, excelUrl = url)
                                
                                // Open URL regardless so user can inspect file
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
                        val errorMsg = if (result is ApiResult.Error) result.message else "Error desconocido"
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
        // SOLO activar cuando se mencione EXPLÍCITAMENTE "excel"
        // Esto evita que reportes/listados normales se conviertan en Excel automáticamente
        return lowerQuery.contains("excel")
    }

    private fun processQuery(query: String, skipUserMessage: Boolean = false) {
        Log.d("DatabaseQueryFragment", "=== MAIN QUERY PROCESSING ===")
        Log.d("DatabaseQueryFragment", "User Query: $query")
        
        // Track pending query for background processing if app goes to background
        isProcessingQuery = true
        pendingQuery = query
        
        // Add user message to chat first (only if not skipped)
        val attachedFile = if (attachedFiles.isNotEmpty()) attachedFiles[0] else null
        if (!skipUserMessage) {
            addMessageToChat(query, true, attachedFile)
        }
        
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
                                put("fileType", attachedFile.type)
                                put("uri", remoteUrl)
                                put("name", attachedFile.name)
                            })
                        }.toString()

                        // First try local MCP /procesar-prompt endpoints directly (fast, short timeouts)
                        val localResp = tryLocalProcesarPrompt(query, jsonContent, "https://api.deepseek.com", "deepseek-reasoner")
                        if (localResp != null) {
                            // Local container answered — use it and skip cloud
                            isProcessingQuery = false
                            pendingQuery = null
                            binding.loadingSpinner.visibility = View.GONE
                            binding.sendButton.visibility = View.VISIBLE
                            addMessageToChat(localResp, false)
                        } else {
                            val result = mcpHttpClient.processPromptWithAttachments(query, jsonContent, "https://api.deepseek.com", "deepseek-reasoner")

                            // Clear pending data - task completed
                            isProcessingQuery = false
                            pendingQuery = null

                            // Hide spinner
                            binding.loadingSpinner.visibility = View.GONE
                            binding.sendButton.visibility = View.VISIBLE

                            if (result.success) {
                                // Display response
                                val responseText = when (result.data) {
                                    is JSONObject -> result.data.optString("response", result.data.toString())
                                    is JSONArray -> result.data.toString()
                                    else -> result.data?.toString() ?: "Respuesta vacía"
                                }
                                addMessageToChat(responseText, false)
                            } else {
                                addMessageToChat("❌ Error: ${result.error}", false)
                            }
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
                
                Log.d("DatabaseQueryFragment", "=== FINAL RESULT LOG ===")
                Log.d("DatabaseQueryFragment", "Result Length: ${result.length} characters")
                Log.d("DatabaseQueryFragment", "Result Content: $result")
                Log.d("DatabaseQueryFragment", "=======================")

                var messageId: String? = null

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
                    messageId = addMessageToChat(graphMessage, false)
                } else {
                    // Check if binding is still valid before accessing it
                    if (_binding != null) {
                        // Hide spinner
                        binding.loadingSpinner.visibility = View.GONE
                        binding.sendButton.visibility = View.VISIBLE
                    }
                    
                    // Display the text result in chat
                    if (result.isNullOrBlank()) {
                        messageId = addMessageToChat("⚠️ No se recibió respuesta del sistema. Intente reformular su consulta.", false)
                    } else {
                        // Display the response exactly as received (VS Code style)
                        messageId = addMessageToChat(result, false)
                    }
                }
                
                // Notificaciones deshabilitadas para respuestas de chat en el cliente

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
                // Clear pending data - task completed
                isProcessingQuery = false
                pendingQuery = null
                // Save updated chat history
                saveChatHistory()
            }
        }

    }
    
    /**
     * Detect if query is asking for Business Intelligence analysis
     */
    // Notificaciones deshabilitadas desde el cliente para respuestas de chat.
    
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

REGLA DE PRESENTACIÓN — NUNCA MOSTRAR IDs NUMÉRICOS:
- NUNCA muestres IDs numéricos de base de datos (user_id, course_id, id, student_id, creator_user_id, topic_id, task_id, etc.) al usuario.
- NUNCA incluyas columnas de IDs en el SELECT de tus consultas SQL.
- Para personas: muestra siempre el username o nombre (first_name, last_name). Haz JOIN con la tabla usuarios.
- Para cursos: muestra siempre el título (title). Haz JOIN con courses.
- Para temas: muestra el nombre (name). Para tareas: muestra el título (title).
- Si una columna es FK (ej: creator_user_id, student_id, subscriber_id), haz JOIN con la tabla correspondiente y selecciona el campo legible.
- Ejemplo PROHIBIDO: "Usuario 42 tiene 3 cursos" o SELECT user_id FROM ...
- Ejemplo CORRECTO: "El usuario juanperez tiene 3 cursos" o SELECT u.username FROM ... JOIN usuarios u ON ...
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
            
            // Auto-retry if not reachable, connection failed, or server not available
            if (!result.success && (result.error?.contains("reachable") == true 
                    || result.error?.contains("failed") == true
                    || result.error?.contains("not available") == true
                    || result.error?.contains("not reachable") == true)) {
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
                var data = result.data

                // FIX: If data is a String that looks like JSON, parse it first
                // (Server often returns stringified JSON in 'text' field of tool response)
                if (data is String) {
                    val trimmed = data.trim()
                    try {
                        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                            data = JSONObject(trimmed)
                        } else if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                            data = JSONArray(trimmed)
                        }
                    } catch (e: Exception) {
                        Log.v(TAG, "Response string is not JSON: ${e.message}")
                    }
                }
                
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
            val key = keys.next()
            if (key == "id" || key.endsWith("_id")) continue
            columns.add(key)
        }
        
        // Build column labels (friendly names)
        val columnLabels = columns.map { col ->
            when (col.lowercase()) {
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
            if (key == "id" || key.endsWith("_id")) continue
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
            val columns = firstItem.keys.map { it.toString() }.filter { it != "id" && !it.endsWith("_id") }
            
            val columnLabels = columns.map { col ->
                when (col.lowercase()) {
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
            val k = key.toString()
            if (k == "id" || k.endsWith("_id")) return@forEach
            sb.append("$k: $value\n")
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
     *    - **CONSULTA BACKEND API** (BackendApiService calls)
     *    - Obtiene JSON real ordenado por ID
     *    - Filtra datos relevantes
     *    - Genera respuesta con LLM
     * 4. BackendApiService returns data from the backend
     * 5. Este fragment muestra respuesta
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
                val videosResult = BackendApiService.getVideos(limit = 500)
                val videos = if (videosResult is ApiResult.Success) videosResult.data ?: emptyList() else emptyList()
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
                // Aggregate topics and content items from all courses via API
                val coursesResult = BackendApiService.getCourses()
                val allCourses = if (coursesResult is ApiResult.Success) coursesResult.data ?: emptyList() else emptyList()
                val topics = mutableListOf<com.example.tareamov.data.entity.Topic>()
                for (c in allCourses) {
                    val tr = BackendApiService.getTopicsByCourse(c.id)
                    if (tr is ApiResult.Success) topics.addAll(tr.data ?: emptyList())
                }
                val contentItems = mutableListOf<com.example.tareamov.data.entity.ContentItem>()
                for (t in topics) {
                    val tasksR = BackendApiService.getTasksByTopic(t.id)
                    val tasks = if (tasksR is ApiResult.Success) tasksR.data ?: emptyList() else emptyList()
                    for (task in tasks) {
                        val ciR = BackendApiService.getContentItemsByTask(task.id)
                        if (ciR is ApiResult.Success) contentItems.addAll(ciR.data ?: emptyList())
                    }
                }

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
                val videosResult = BackendApiService.getVideos(limit = 500)
                val videos = if (videosResult is ApiResult.Success) videosResult.data ?: emptyList() else emptyList()
                val videoTopicCounts = ArrayList<Pair<String, Int>>()

                videos.forEach { video ->
                    val videoTitle = video.title ?: "Video ${video.id}"
                    val topicsResult = BackendApiService.getTopicsByCourse(video.id)
                    val topicCount = if (topicsResult is ApiResult.Success) topicsResult.data?.size ?: 0 else 0
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
                // Aggregate topics and tasks from all courses via API
                val coursesResult = BackendApiService.getCourses()
                val allCourses = if (coursesResult is ApiResult.Success) coursesResult.data ?: emptyList() else emptyList()
                val topics = mutableListOf<com.example.tareamov.data.entity.Topic>()
                for (c in allCourses) {
                    val tr = BackendApiService.getTopicsByCourse(c.id)
                    if (tr is ApiResult.Success) topics.addAll(tr.data ?: emptyList())
                }
                val tasks = mutableListOf<com.example.tareamov.data.entity.Task>()
                for (t in topics) {
                    val tkR = BackendApiService.getTasksByTopic(t.id)
                    if (tkR is ApiResult.Success) tasks.addAll(tkR.data ?: emptyList())
                }
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
                val subsResult = BackendApiService.getMySubscriptions()
                // TODO: getMySubscriptions returns JsonObject list; adapt field access as needed
                val subscriptionCount = if (subsResult is ApiResult.Success) subsResult.data?.size ?: 0 else 0
                val creatorCounts = listOf(Pair("My Subscriptions", subscriptionCount))

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
            // --- usuarios (no list-all endpoint) ---
            try {
                // TODO: No getAllUsuarios endpoint; profile endpoint only returns current user
                val myProfile = BackendApiService.getMyProfile()
                val obj = JSONObject()
                obj.put("count", if (myProfile is ApiResult.Success) 1 else 0)
                obj.put("exists", true)
                obj.put("note", "Only current user available via API")
                tables.put("usuarios", obj)
            } catch (_: Exception) { /* ignore */ }

            // --- personas (no list-all endpoint) ---
            try {
                // TODO: No getAllPersonas endpoint available
                val obj = JSONObject()
                obj.put("count", -1)
                obj.put("exists", true)
                obj.put("note", "No bulk personas endpoint")
                tables.put("personas", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val videosResult = BackendApiService.getVideos(limit = 500)
                val videos = if (videosResult is ApiResult.Success) videosResult.data ?: emptyList() else emptyList()
                val obj = JSONObject()
                obj.put("count", videos.size)
                obj.put("exists", true)
                tables.put("videos", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val coursesResult = BackendApiService.getCourses()
                val courses = if (coursesResult is ApiResult.Success) coursesResult.data ?: emptyList() else emptyList()
                val obj = JSONObject()
                obj.put("count", courses.size)
                obj.put("exists", true)
                tables.put("courses", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                // Aggregate topics from all courses
                val cResult = BackendApiService.getCourses()
                val allC = if (cResult is ApiResult.Success) cResult.data ?: emptyList() else emptyList()
                var topicCount = 0
                for (c in allC) {
                    val tr = BackendApiService.getTopicsByCourse(c.id)
                    if (tr is ApiResult.Success) topicCount += (tr.data?.size ?: 0)
                }
                val obj = JSONObject()
                obj.put("count", topicCount)
                obj.put("exists", true)
                tables.put("topics", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                // Aggregate content items from all courses -> topics -> tasks
                val cResult = BackendApiService.getCourses()
                val allC = if (cResult is ApiResult.Success) cResult.data ?: emptyList() else emptyList()
                var ciCount = 0
                for (c in allC) {
                    val tr = BackendApiService.getTopicsByCourse(c.id)
                    val topics = if (tr is ApiResult.Success) tr.data ?: emptyList() else emptyList()
                    for (t in topics) {
                        val tkR = BackendApiService.getTasksByTopic(t.id)
                        val tasks = if (tkR is ApiResult.Success) tkR.data ?: emptyList() else emptyList()
                        for (task in tasks) {
                            val ciR = BackendApiService.getContentItemsByTask(task.id)
                            if (ciR is ApiResult.Success) ciCount += (ciR.data?.size ?: 0)
                        }
                    }
                }
                val obj = JSONObject()
                obj.put("count", ciCount)
                obj.put("exists", true)
                tables.put("content_items", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                // Aggregate tasks from all courses -> topics
                val cResult = BackendApiService.getCourses()
                val allC = if (cResult is ApiResult.Success) cResult.data ?: emptyList() else emptyList()
                var taskCount = 0
                for (c in allC) {
                    val tr = BackendApiService.getTopicsByCourse(c.id)
                    val topics = if (tr is ApiResult.Success) tr.data ?: emptyList() else emptyList()
                    for (t in topics) {
                        val tkR = BackendApiService.getTasksByTopic(t.id)
                        if (tkR is ApiResult.Success) taskCount += (tkR.data?.size ?: 0)
                    }
                }
                val obj = JSONObject()
                obj.put("count", taskCount)
                obj.put("exists", true)
                tables.put("tasks", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                // Aggregate submissions from all courses
                val cResult = BackendApiService.getCourses()
                val allC = if (cResult is ApiResult.Success) cResult.data ?: emptyList() else emptyList()
                var subCount = 0
                for (c in allC) {
                    val sr = BackendApiService.getSubmissionsByCourse(c.id)
                    if (sr is ApiResult.Success) subCount += (sr.data?.size ?: 0)
                }
                val obj = JSONObject()
                obj.put("count", subCount)
                obj.put("exists", true)
                tables.put("task_submissions", obj)
            } catch (_: Exception) { /* ignore */ }

            try {
                val subsResult = BackendApiService.getMySubscriptions()
                val subs = if (subsResult is ApiResult.Success) subsResult.data ?: emptyList() else emptyList()
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

    // Try local /procesar-prompt endpoints quickly before falling back to MCP server
    private suspend fun tryLocalProcesarPrompt(prompt: String, jsonContent: String, ollamaUrl: String, model: String): String? = withContext(Dispatchers.IO) {
        try {
            val candidates = listOf(
                "http://10.0.2.2:3000",
                "http://127.0.0.1:3000",
                "http://localhost:3000",
                "http://host.docker.internal:3000",
                "http://172.18.0.2:3000"
            )

            val client = OkHttpClient.Builder()
                .connectTimeout(1200, TimeUnit.MILLISECONDS)
                .readTimeout(3000, TimeUnit.MILLISECONDS)
                .build()

            val mediaType = "application/json; charset=utf-8".toMediaType()

            val payload = JSONObject().apply {
                put("prompt", prompt)
                put("jsonContent", jsonContent)
                put("ollamaUrl", ollamaUrl)
                put("model", model)
            }.toString()

                    for (base in candidates) {
                try {
                    val url = "$base/procesar-prompt"
                    val req = Request.Builder()
                        .url(url)
                        .post(payload.toRequestBody(mediaType))
                        .header("X-API-Key", "tareamov-mcp-api-key-2025-secure")
                        .header("Connection", "close")
                        .header("X-Supabase-Url", com.example.tareamov.BuildConfig.SUPABASE_URL)
                        .header("X-Supabase-Key", com.example.tareamov.BuildConfig.SUPABASE_ANON_KEY)
                        .build()

                    client.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            try {
                                val j = JSONObject(body)
                                val text = j.optString("respuesta_texto", j.optString("respuesta", body))
                                return@withContext text
                            } catch (e: Exception) {
                                return@withContext body
                            }
                        } else {
                            Log.d("DatabaseQueryFragment", "Local attempt failed $url -> ${resp.code}")
                        }
                    }
                } catch (e: Exception) {
                    Log.d("DatabaseQueryFragment", "Local attempt error for $base: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.d("DatabaseQueryFragment", "tryLocalProcesarPrompt error: ${e.message}")
        }
        return@withContext null
    }

    private fun initializeSession() {
        val userId = if (::sessionManager.isInitialized) sessionManager.getUserId() ?: -1L else -1L
        // Deterministic session ID: db_query_{userId} — persists across app restarts
        currentSessionId = if (userId > 0) "db_query_$userId" else {
            val username = sessionManager.getUsername() ?: "anonymous"
            chatPrefs.getString(SESSION_ID_KEY, null) ?: "${username}_${UUID.randomUUID()}"
        }
        totalMessageCount = chatPrefs.getInt(MESSAGE_COUNT_KEY, 0)

        chatPrefs.edit()
            .putString(SESSION_ID_KEY, currentSessionId)
            .putInt(MESSAGE_COUNT_KEY, totalMessageCount)
            .apply()

        // Load chat history: try backend first, fall back to SharedPreferences
        viewLifecycleOwner.lifecycleScope.launch {
            loadHistoryFromBackend()
        }
    }

    /**
     * Carga el historial del chat desde el backend (fuente de verdad).
     * Si falla o no hay mensajes, cae a SharedPreferences como respaldo rápido.
     * Si tampoco hay nada, muestra el mensaje de bienvenida.
     */
    private suspend fun loadHistoryFromBackend() {
        try {
            // Try loading by origin (per-account) first, then fallback to session
            val userId = if (::sessionManager.isInitialized) sessionManager.getUserId() else null
            val result = withContext(Dispatchers.IO) {
                if (userId != null && userId > 0L) {
                    BackendApiService.getChatMessagesByOrigin("db_query")
                } else {
                    BackendApiService.getChatMessagesBySession(currentSessionId)
                }
            }
            if (result is com.example.tareamov.service.ApiResult.Success) {
                val backendMessages = (result as com.example.tareamov.service.ApiResult.Success).data
                if (!backendMessages.isNullOrEmpty()) {
                    // Helper to safely read fields that may arrive as JsonNull (not Java null)
                fun com.google.gson.JsonObject.strOrNull(key: String): String? =
                    get(key)?.takeIf { !it.isJsonNull }?.asString
                fun com.google.gson.JsonObject.boolOr(key: String, default: Boolean = false): Boolean =
                    get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: default
                fun com.google.gson.JsonObject.longOr(key: String, default: Long): Long =
                    get(key)?.takeIf { !it.isJsonNull }?.asLong ?: default

                val uiMessages = backendMessages.mapNotNull { json ->
                        try {
                            val text = json.strOrNull("message") ?: return@mapNotNull null
                            val isUser = json.boolOr("isFromUser")
                            val timestamp = json.longOr("timestamp", System.currentTimeMillis())
                            val username = json.strOrNull("username")
                            val avatar = json.strOrNull("senderAvatar")
                            val attachedUrl = json.strOrNull("attachedFileUrl")
                            val attachedName = json.strOrNull("attachedFileName")
                            val attachedType = json.strOrNull("attachedFileType")
                            val excelUrl = json.strOrNull("excelUrl")
                            // If an excel URL was stored, show it as file attachment
                            val effectiveAttachedUrl = attachedUrl ?: excelUrl
                            val effectiveAttachedName = attachedName ?: if (excelUrl != null) "reporte.xlsx" else null
                            val effectiveAttachedType = attachedType ?: if (excelUrl != null) "excel" else null
                            ChatMessage(
                                text = text,
                                isUser = isUser,
                                timestamp = timestamp,
                                username = username,
                                senderAvatar = avatar ?: if (!isUser) "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png" else currentUserAvatar,
                                excelUrl = excelUrl,
                                attachedFileUrl = effectiveAttachedUrl,
                                attachedFileName = effectiveAttachedName,
                                attachedFileType = effectiveAttachedType
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Error mapping backend message: ${e.message}")
                            null
                        }
                    }
                    if (uiMessages.isNotEmpty()) {
                        chatAdapter.clear()
                        chatHistory.clear()
                        chatAdapter.restoreMessages(uiMessages)
                        chatHistory.addAll(uiMessages)
                        totalMessageCount = uiMessages.size
                        updateMessageCountDisplay()
                        if (_binding != null) {
                            binding.emptyStateContainer.visibility = View.GONE
                            binding.chatHistoryHeader.visibility = View.VISIBLE
                        }
                        view?.post { scrollToBottom(smooth = false) }
                        Log.i(TAG, "Loaded ${uiMessages.size} messages from backend for session $currentSessionId")
                        return
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend history load failed: ${e.message}")
        }
        // Fallback: SharedPreferences
        restoreChatHistory()
    }

    private fun syncMessageToBackend(text: String, isUser: Boolean, excelUrl: String? = null) {
        if (!::sessionManager.isInitialized) return
        val userId = sessionManager.getUserId()
        val username = sessionManager.getUsername()
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val entity = com.example.tareamov.data.entity.ChatMessage(
                    message = text,
                    isFromUser = isUser,
                    sessionId = currentSessionId,
                    senderUsername = if (isUser) username else "DeepSeek",
                    senderAvatar = if (isUser) currentUserAvatar else "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                )
                BackendApiService.upsertChatMessage(
                    entity,
                    if (userId > 0) userId else null,
                    origin = "db_query",
                    excelUrl = excelUrl
                )
            } catch (e: Exception) {
                Log.w("DatabaseQueryFragment", "Failed to sync message to backend: ${e.message}")
            }
        }
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
                    
                    // Log restoration for debugging
                    Log.d("DatabaseQueryFragment", "Restored ${messageList.size} messages for user: ${sessionManager.getUsername()}")
                    
                    // Scroll to bottom or specific message
                    view?.post {
                        val targetId = targetMessageId
                        if (targetId != null) {
                            val position = chatAdapter.getPositionOfMessage(targetId)
                            if (position >= 0) {
                                binding.chatRecyclerView.scrollToPosition(position)
                                targetMessageId = null // Consume the target
                            } else {
                                scrollToBottom(smooth = false)
                            }
                        } else {
                            scrollToBottom(smooth = false)
                        }
                    }
                    
                    messageList.forEach { message ->
                        Log.d("DatabaseQueryFragment", "Restored message - User: ${message.isUser}, Text: ${message.text.take(50)}...")
                    }
                } else {
                    Log.d("DatabaseQueryFragment", "No messages to restore for user: ${sessionManager.getUsername()}")
                    addWelcomeMessage()
                }
            } else {
                Log.d("DatabaseQueryFragment", "No saved messages found for user: ${sessionManager.getUsername()}")
                addWelcomeMessage()
            }
        } catch (e: Exception) {
            Log.e("DatabaseQueryFragment", "Error restoring chat history for user: ${sessionManager.getUsername()}", e)
            // Create new session on restore failure
            createNewSession()
        }
    }
    
    private fun createNewSession() {
        val oldSessionId = currentSessionId
        val uid = if (::sessionManager.isInitialized) sessionManager.getUserId() ?: -1L else -1L
        currentSessionId = if (uid > 0) "db_query_$uid" else {
            val username = sessionManager.getUsername() ?: "anonymous"
            "${username}_${UUID.randomUUID()}"
        }
        // Delete backend session history
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { BackendApiService.deleteChatSession(oldSessionId) } catch (_: Exception) {}
        }
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
        
        Log.d("DatabaseQueryFragment", "Created new session for user: ${if (::sessionManager.isInitialized) sessionManager.getUsername() ?: "anonymous" else "anonymous"}")
    }

    private fun saveChatHistory() {
        // Safety checks before accessing context-dependent resources
        if (!isAdded || context == null || !::sessionManager.isInitialized) {
            Log.d("DatabaseQueryFragment", "saveChatHistory: Fragment not ready, skipping save")
            return
        }
        
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
            Log.e("DatabaseQueryFragment", "Error saving chat history", e)
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
            // Welcome message is handled inside loadHistoryFromBackend / restoreChatHistory
        }
        
        // Fetch avatar for new user
        lifecycleScope.launch {
            try {
                currentUser?.let { username ->
                    val userResult = withContext(Dispatchers.IO) { BackendApiService.getUserByUsername(username) }
                    val avatar = if (userResult is ApiResult.Success) userResult.data?.avatar else null
                    if (!avatar.isNullOrBlank()) {
                        currentUserAvatar = avatar
                        sessionManager.saveUserAvatar(avatar)
                        chatAdapter.setUserAvatarUrl(avatar)
                    }
                }
            } catch (_: Exception) {
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
        currentUserAvatar = null
        chatAdapter.setUserAvatarUrl(null)
        
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
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_liquid_glass, null)
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Limpiar historial"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = 
            "¿Estás seguro de que quieres eliminar todo el historial de chat? Esta acción no se puede deshacer."
        
        dialogView.findViewById<TextView>(R.id.positiveButton).apply {
            text = "Sí, limpiar"
            setOnClickListener {
                clearChatHistory()
                Toast.makeText(requireContext(), "Historial limpiado", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        
        dialogView.findViewById<TextView>(R.id.negativeButton).setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
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
        
        val dialogView = layoutInflater.inflate(R.layout.dialog_chat_options_liquid_glass, null)
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Opciones"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = message
        
        dialogView.findViewById<LinearLayout>(R.id.exportChatOption).setOnClickListener {
            exportChatHistory()
            dialog.dismiss()
        }
        
        dialogView.findViewById<LinearLayout>(R.id.newSessionOption).setOnClickListener {
            startNewSession()
            dialog.dismiss()
        }
        
        dialogView.findViewById<TextView>(R.id.cancelButton).setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }

    // toggleExcelMode removed
    
    private fun startNewSession() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_liquid_glass, null)
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialogView.findViewById<TextView>(R.id.dialogTitle).text = "Nueva sesión"
        dialogView.findViewById<TextView>(R.id.dialogMessage).text = 
            "¿Quieres empezar una nueva sesión? La conversación actual se guardará."
        
        dialogView.findViewById<TextView>(R.id.positiveButton).apply {
            text = "Sí"
            setOnClickListener {
                saveChatHistory() // Save current session
                createNewSession()
                addWelcomeMessage()
                Toast.makeText(requireContext(), "Nueva sesión iniciada", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
        
        dialogView.findViewById<TextView>(R.id.negativeButton).setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
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

    private fun checkAndShowInstitutionPicker(onSelected: () -> Unit) {
        val tenants = BackendApiService.decodeAvailableTenantsFromJWT()
        if (tenants.size <= 1) {
            onSelected()
            return
        }
        val currentId = BackendApiService.queryTenantId
        // If the user has a valid selection AND the picker was shown within 24h, skip.
        if (currentId != null && tenants.any { it.first == currentId } && BackendApiService.wasInstitutionAskedRecently()) {
            onSelected()
            return
        }
        val items = tenants.map { it.second }.toTypedArray()
        BackendApiService.markInstitutionAsked()
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("¿De qué institución deseas consultar?")
            .setItems(items) { _, which ->
                BackendApiService.queryTenantId = tenants[which].first
                onSelected()
            }
            .setCancelable(false)
            .show()
    }

    private fun sendMessage() {
        val queryText = binding.queryInput.text
        val query = queryText?.toString()?.trim() ?: ""
        if (query.isNotEmpty()) {
            checkAndShowInstitutionPicker {
                // Clear the input field first
                binding.queryInput.setText("")
                // Process query through the unified LLM flow (not RAG-only)
                processQuery(query)
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


    


    override fun onDestroyView() {
        try {
            // Stop TTS playback
            if (::ttsService.isInitialized) {
                ttsService.stopPlayback()
            }

            // Save chat history before destroying view (with safety check)
            if (isAdded && context != null && ::sessionManager.isInitialized) {
                try {
                    saveChatHistory()
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving chat history", e)
                }
            }
            
            // Remove any pending callbacks
            view?.removeCallbacks(autoSaveRunnable)
            
            // Remove keyboard listener to avoid memory leaks
            keyboardLayoutListener?.let { listener ->
                try {
                    _binding?.root?.viewTreeObserver?.removeOnGlobalLayoutListener(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing keyboard listener", e)
                }
            }
            keyboardLayoutListener = null
            
            // Unregister from user change notifications
            SessionManager.removeUserChangeListener(this)
            
            // Close MCP HTTP client connection
            if (::mcpHttpClient.isInitialized) {
                try {
                    mcpHttpClient.close()
                    Log.d(TAG, "✅ MCP HTTP client closed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error closing MCP HTTP client", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroyView", e)
        }

    // No SupabaseClient listener to unregister – using BackendApiService
        
        super.onDestroyView()
        _binding = null
        _chatPrefs = null // Clean up SharedPreferences reference
    }
    
    override fun onStop() {
        super.onStop()
        
        // Safety check - ensure fragment is still attached
        if (!isAdded || context == null) {
            Log.d(TAG, "⚠️ onStop: Fragment not attached, skipping background task scheduling")
            return
        }
        
        // If there's an ongoing query, schedule it as background task
        if (isProcessingQuery && pendingQuery != null) {
            Log.d(TAG, "🔄 App going to background - scheduling database query to continue")
            
            try {
                val userId = if (::sessionManager.isInitialized) sessionManager.getUserId() ?: -1L else -1L
                val username = if (::sessionManager.isInitialized) sessionManager.getUsername() ?: "unknown" else "unknown"
                
                if (userId > 0) {
                    context?.let { ctx ->
                        BackgroundTaskManager.scheduleDatabaseQuery(
                            context = ctx,
                            query = pendingQuery!!,
                            userId = userId,
                            username = username
                        )
                        
                        Toast.makeText(ctx, "📋 La consulta continuará en segundo plano", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling background task", e)
            }
            
            // Clear pending data
            isProcessingQuery = false
            pendingQuery = null
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Save chat when app goes to background to preserve user messages
        // Add safety check to prevent crash
        if (isAdded && context != null && ::sessionManager.isInitialized) {
            try {
                saveChatHistory()
            } catch (e: Exception) {
                Log.e(TAG, "Error saving chat history in onPause", e)
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        
        // Safety check - ensure fragment is still attached and view exists
        if (!isAdded || context == null || _binding == null) {
            Log.d(TAG, "⚠️ onResume: Fragment not ready, skipping operations")
            return
        }
        
        try {
            // Check for pending background results
            if (::sessionManager.isInitialized) {
                val userId = sessionManager.getUserId() ?: -1L
                if (userId > 0) {
                    context?.let { ctx ->
                        val pendingResult = BackgroundTaskManager.getPendingDatabaseQueryResult(ctx, userId)
                        if (pendingResult != null) {
                            Log.d(TAG, "📬 Found pending background result, displaying...")
                            addMessageToChat(pendingResult, false)
                            BackgroundTaskManager.clearDatabaseQueryResult(ctx, userId)
                        }
                    }
                }
                
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
        } catch (e: Exception) {
            Log.e(TAG, "Error in onResume", e)
        }
    }

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
                
                // Reset all messages
                chatAdapter.getMessages().forEach { 
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
                    text = message.text,
                    onStart = {
                        Log.d("DatabaseQueryFragment", "TTS playback started IMMEDIATELY")
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
                                Log.e("DatabaseQueryFragment", "TTS error: $error")
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

    // ============================================================================================
    // VOICE RECOGNITION LOGIC
    // ============================================================================================

    private fun setupVoiceRecognition() {
        voiceInputLayout = binding.voiceInputLayout
        queryInputLayout = binding.queryInputLayout
        voiceVisualizer = binding.voiceVisualizer
        stopRecordingButton = binding.stopRecordingButton
        cancelVoiceButton = binding.cancelVoiceButton
        voiceStatusText = binding.voiceStatusText
        micButton = binding.micButton

        micButton.setOnClickListener {
            checkAudioPermissionAndStart()
        }

        stopRecordingButton.setOnClickListener {
            stopListening()
        }
        
        cancelVoiceButton.setOnClickListener {
            stopListening()
        }
    }

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
            imm.hideSoftInputFromWindow(binding.queryInput.windowToken, 0)
            
            // Toggle UI
            queryInputLayout.visibility = View.GONE
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
            // Restore UI
            queryInputLayout.visibility = View.VISIBLE
            voiceInputLayout.visibility = View.GONE
        }
    }

    private fun stopListening() {
        if (!isListening) {
             if (voiceInputLayout.visibility == View.VISIBLE) {
                 voiceInputLayout.visibility = View.GONE
                 queryInputLayout.visibility = View.VISIBLE
             }
             return
        }
        
        try {
            speechRecognizer?.stopListening()
            isListening = false
            voiceVisualizer.stopAnimating()
            
            // Restore UI
            voiceInputLayout.visibility = View.GONE
            queryInputLayout.visibility = View.VISIBLE
            
            // Focus input
            binding.queryInput.requestFocus()
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(requireContext())
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {
                voiceStatusText.text = "Te escucho..."
            }

            override fun onRmsChanged(rmsdB: Float) {
                // Update visualizer
                voiceVisualizer.updateAmplitude(rmsdB)
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                voiceStatusText.text = "Procesando..."
            }

            override fun onError(error: Int) {
                isListening = false
                voiceVisualizer.stopAnimating()
                
                // Hide overlay and restore input
                voiceInputLayout.visibility = View.GONE
                queryInputLayout.visibility = View.VISIBLE
                
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No te entendí"
                    SpeechRecognizer.ERROR_NETWORK -> "Error de conexión"
                    else -> null
                }
                
                if (errorMessage != null) {
                    Toast.makeText(context, errorMessage, Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    binding.queryInput.setText(text)
                    binding.queryInput.setSelection(text.length)
                }
                
                // Hide overlay
                voiceInputLayout.visibility = View.GONE
                queryInputLayout.visibility = View.VISIBLE
                
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