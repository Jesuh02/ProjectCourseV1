package com.example.tareamov.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.github.mikephil.charting.animation.Easing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.work.WorkManager
import androidx.work.WorkInfo
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import com.example.tareamov.service.LocalLlamaService.ModelDownloadWorker
import java.text.SimpleDateFormat
import java.util.*

// Extension functions for charts
fun BarChart.zoomIn() {
    val centerX = width / 2f
    val centerY = height / 2f
    val matrix = Matrix()
    matrix.set(this.viewPortHandler.matrixTouch)
    matrix.postScale(1.2f, 1.2f, centerX, centerY)
    this.viewPortHandler.refresh(matrix, this, false)
    this.invalidate()
}

fun BarChart.zoomOut() {
    val centerX = width / 2f
    val centerY = height / 2f
    val matrix = Matrix()
    matrix.set(this.viewPortHandler.matrixTouch)
    matrix.postScale(0.8f, 0.8f, centerX, centerY)
    this.viewPortHandler.refresh(matrix, this, false)
    this.invalidate()
}

fun PieChart.zoomIn() {
    val centerX = width / 2f
    val centerY = height / 2f
    val matrix = Matrix()
    matrix.set(this.viewPortHandler.matrixTouch)
    matrix.postScale(1.2f, 1.2f, centerX, centerY)
    this.viewPortHandler.refresh(matrix, this, false)
    this.invalidate()
}

fun PieChart.zoomOut() {
    val centerX = width / 2f
    val centerY = height / 2f
    val matrix = Matrix()
    matrix.set(this.viewPortHandler.matrixTouch)
    matrix.postScale(0.8f, 0.8f, centerX, centerY)
    this.viewPortHandler.refresh(matrix, this, false)
    this.invalidate()
}

// Message data class for chat with enhanced features
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isTyping: Boolean = false,
    val messageId: String = java.util.UUID.randomUUID().toString()
)

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

class DatabaseQueryFragment : Fragment() {

    private var _binding: FragmentDatabaseQueryBinding? = null
    private val binding get() = _binding!!
    private lateinit var mcpService: MCPService
    private lateinit var resultTextView: TextView
    private lateinit var database: AppDatabase
    private var currentChart: View? = null
    private lateinit var localLlamaService: LocalLlamaService
    private lateinit var chatAdapter: ChatAdapter
    private lateinit var databaseQueryService: DatabaseQueryService
    
    // Enhanced chat state management
    private val chatHistory = mutableListOf<ChatMessage>()
    private var isProcessingQuery = false
    private var currentConversationContext = mutableListOf<String>()
    
    // SharedPreferences for chat persistence
    private val chatPrefs by lazy {
        requireContext().getSharedPreferences("chat_history", Context.MODE_PRIVATE)
    }

    companion object {
        private const val CHAT_HISTORY_KEY = "saved_chat_messages"
        private const val MAX_CONTEXT_MESSAGES = 10 // Keep last 10 messages for context
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

        mcpService = MCPService(requireContext())
        database = AppDatabase.getDatabase(requireContext())
        databaseQueryService = DatabaseQueryService(requireContext())

        // Initialize UI components
        setupUIComponents()

        // Setup chat RecyclerView
        setupChatRecyclerView()

        // Initialize LocalLlamaService and trigger model download if needed
        setupLocalLlamaService()

        // Set up enhanced UI interactions
        setupEnhancedUI()

        // Set up send button click listener
        setupSendButton()

        // Set up chart control buttons
        setupChartControls()

        // Check server connection status
        checkServerStatus()

        // Restore chat history
        restoreChatHistory()

        // Add welcome message if no history exists
        if (chatAdapter.getMessages().isEmpty()) {
            addWelcomeMessage()
        }
    }

    private fun setupUIComponents() {
        // Find the result TextView directly using findViewById with the resource ID
        resultTextView = binding.resultText

        // Set initial text
        resultTextView.text = getString(R.string.initial_query_text)

        // Make brain icon visible initially
        binding.centerBrainIcon.visibility = View.VISIBLE
    }

    private fun setupChatRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.chatRecyclerView.apply {
            layoutManager = LinearLayoutManager(context).apply {
                stackFromEnd = true // Messages appear from bottom
            }
            adapter = chatAdapter
        }
    }

    private fun addMessageToChat(text: String, isUser: Boolean): String {
        val message = ChatMessage(text, isUser)
        chatAdapter.addMessage(message)
        binding.chatRecyclerView.scrollToPosition(chatAdapter.itemCount - 1)

        // Hide brain icon when chat has messages
        if (chatAdapter.itemCount > 0) {
            binding.centerBrainIcon.visibility = View.GONE
        }

        // Return the message ID or position as a unique identifier
        return message.messageId
    }

    private fun setupLocalLlamaService() {
        localLlamaService = LocalLlamaService(requireContext())
        val modelFile = requireContext().filesDir.resolve("llama3-8b-q4_0.gguf")

        // Update connection status based on model availability
        if (!modelFile.exists()) {
            updateConnectionStatus(false, getString(R.string.llama_downloading))
            Toast.makeText(context, getString(R.string.llama_download_toast), Toast.LENGTH_LONG).show()

            modelDownloadRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>().build()
            WorkManager.getInstance(requireContext()).enqueue(modelDownloadRequest!!)
            WorkManager.getInstance(requireContext()).getWorkInfoByIdLiveData(modelDownloadRequest!!.id)
                .observe(viewLifecycleOwner) { workInfo ->
                    when (workInfo.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            Toast.makeText(context, getString(R.string.llama_download_success_toast), Toast.LENGTH_LONG).show()
                            updateConnectionStatus(true, getString(R.string.llama_downloaded))
                        }
                        WorkInfo.State.FAILED -> {
                            Toast.makeText(context, getString(R.string.llama_download_failed_toast), Toast.LENGTH_LONG).show()
                            updateConnectionStatus(false, getString(R.string.llama_download_error))
                        }
                        WorkInfo.State.RUNNING -> {
                            updateConnectionStatus(false, getString(R.string.llama_downloading))
                        }
                        else -> { }
                    }
                }
        } else {
            updateConnectionStatus(true, getString(R.string.llama_downloaded))
        }

        localLlamaService.downloadModelIfNeeded()
    }

    private fun setupSendButton() {
        binding.sendButton.setOnClickListener {
            val userInput = binding.queryInput.text.toString().trim()
            if (userInput.isNotEmpty()) {
                // Add user message to chat
                val userMessage = ChatMessage(userInput, true)
                chatAdapter.addMessage(userMessage)
                binding.queryInput.text?.clear()

                // Show typing indicator
                val typingMessage = ChatMessage("Escribiendo...", false)
                chatAdapter.addMessage(typingMessage)
                binding.chatRecyclerView.smoothScrollToPosition(chatAdapter.itemCount - 1)

                // Process the query in the background
                processUserQuery(userInput)
            }
        }
    }

    private fun setupChartControls() {
        binding.zoomInButton.setOnClickListener {
            currentChart?.let {
                when (it) {
                    is BarChart -> {
                        it.zoomIn()
                    }
                    is PieChart -> {
                        it.zoomIn()
                    }
                }
            }
        }

        binding.zoomOutButton.setOnClickListener {
            currentChart?.let {
                when (it) {
                    is BarChart -> {
                        it.zoomOut()
                    }
                    is PieChart -> {
                        it.zoomOut()
                    }
                }
            }
        }

        binding.resetChartButton.setOnClickListener {
            currentChart?.let {
                when (it) {
                    is BarChart -> {
                        it.fitScreen()
                        it.invalidate()
                    }
                    is PieChart -> {
                        it.spin(500, 0f, 0f, Easing.EaseInOutQuad)
                    }
                }
            }
        }
    }

    private fun checkServerStatus() {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val isServerRunning = try {
                // Try to check if server is running
                val modelFile = requireContext().filesDir.resolve("llama3-8b-q4_0.gguf")
                modelFile.exists()
            } catch (e: Exception) {
                Log.e("DatabaseQueryFragment", "Error checking server status", e)
                false
            }

            withContext(Dispatchers.Main) {
                updateConnectionStatus(isServerRunning)
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
        addMessageToChat("🔍 Procesando consulta con sistema RAG...", false)

        binding.chartContainer.visibility = View.GONE // Hide chart container
        removeCurrentChart() // Remove previous chart if any

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("DatabaseQueryFragment", "Starting RAG-enhanced processing...")
                
                // Enhanced query processing with RAG
                val result = processRAGEnhancedQuery(query)

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
                        addMessageToChat("⚠️ No se recibió respuesta del sistema RAG. Intente reformular su consulta.", false)
                    } else {
                        // Format and display the RAG response
                        val formattedResult = formatRAGResponse(result)
                        addMessageToChat(formattedResult, false)
                    }
                }

            } catch (e: Exception) {
                Log.e("DatabaseQueryFragment", "Error processing RAG query", e)
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
     * Process query using RAG-enhanced system
     */
    private suspend fun processRAGEnhancedQuery(query: String): String = withContext(Dispatchers.IO) {
        try {
            Log.d("DatabaseQueryFragment", "=== QUERY PROCESSING LOG ===")
            Log.d("DatabaseQueryFragment", "Input Query: $query")
            Log.d("DatabaseQueryFragment", "Current Conversation Context: $currentConversationContext")
            
            // Add query context for conversation continuity
            currentConversationContext.add(query)
            if (currentConversationContext.size > MAX_CONTEXT_MESSAGES) {
                currentConversationContext.removeFirst()
            }

            Log.d("DatabaseQueryFragment", "Updated Conversation Context: $currentConversationContext")
            Log.d("DatabaseQueryFragment", "Trying DatabaseQueryService with RAG...")

            // Try DatabaseQueryService with RAG first (most efficient)
            val ragResult = databaseQueryService.processQuery(query)
            
            Log.d("DatabaseQueryFragment", "RAG Result Length: ${ragResult.length} characters")
            Log.d("DatabaseQueryFragment", "RAG Result Content: $ragResult")
            
            if (ragResult.isNotBlank() && !ragResult.startsWith("Error")) {
                Log.d("DatabaseQueryFragment", "RAG service provided result - SUCCESS")
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
                resultTextView.text = getString(R.string.graph_not_implemented)
                binding.scrollView.visibility = View.VISIBLE
                false
            }
            "GRAPH_REQUEST:INTERACTIVE" -> {
                // Placeholder - Implement interactive chart if needed
                resultTextView.text = getString(R.string.interactive_graph_not_implemented)
                binding.scrollView.visibility = View.VISIBLE
                false
            }
            else -> {
                // Handle unrecognized graph requests (though DatabaseQueryService might handle this)
                resultTextView.text = getString(R.string.unrecognized_graph_type, graphRequest)
                binding.chartContainer.visibility = View.GONE // Hide container if no chart
                binding.scrollView.visibility = View.VISIBLE
                false
            }
        }

        if (chartGenerated) {
            // Update text view to indicate success
            val successMessage = when(graphRequest) {
                "GRAPH_REQUEST:USER_VIDEOS" -> getString(R.string.user_video_chart_success)
                "GRAPH_REQUEST:TOPIC_CONTENT" -> getString(R.string.topic_content_chart_success)
                "GRAPH_REQUEST:COURSE_TOPICS" -> getString(R.string.course_topics_chart_success)
                else -> getString(R.string.chart_generated)
            }
            resultTextView.text = successMessage
        } else {
            // If chart generation failed or wasn't implemented, hide the container and controls
            binding.chartContainer.visibility = View.GONE
            binding.chartControls.visibility = View.GONE
        }
    }

    // The chart generation methods remain mostly unchanged
    private suspend fun generateUserVideoChart() = withContext(Dispatchers.Main) {
        // Limpiar el contenedor de gráficos
        binding.chartContainer.removeAllViews()
        binding.chartContainer.visibility = View.VISIBLE

        // Crear un nuevo gráfico de barras
        val barChart = BarChart(requireContext())
        binding.chartContainer.addView(barChart)
        currentChart = barChart

        // Configurar el gráfico
        barChart.description.isEnabled = false
        barChart.setDrawGridBackground(false)
        barChart.setDrawBarShadow(false)
        barChart.setDrawValueAboveBar(true)
        barChart.setPinchZoom(false)
        barChart.setDrawGridBackground(false)

        // Apply theme colors to chart
        barChart.setBackgroundColor(Color.parseColor("#2A3245"))
        barChart.axisLeft.textColor = Color.WHITE
        barChart.xAxis.textColor = Color.WHITE
        barChart.legend.textColor = Color.WHITE

        val xAxis = barChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f
        xAxis.setCenterAxisLabels(true)

        barChart.axisLeft.setDrawGridLines(false)
        barChart.axisRight.isEnabled = false
        barChart.legend.isEnabled = true

        // Obtener datos para el gráfico
        withContext(Dispatchers.IO) {
            val videos = database.videoDao().getAllVideos()
            val userVideoCounts = videos.groupBy { it.username }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(10) // Limitar a 10 usuarios para mejor visualización

            val entries = ArrayList<BarEntry>()
            val labels = ArrayList<String>()

            userVideoCounts.forEachIndexed { index, (username, count) ->
                entries.add(BarEntry(index.toFloat(), count.toFloat()))
                labels.add(username)
            }

            withContext(Dispatchers.Main) {
                val dataSet = BarDataSet(entries, "Videos por Usuario")
                dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
                dataSet.valueTextColor = Color.WHITE

                val barData = BarData(dataSet)
                barData.setValueTextSize(10f)
                barData.setValueTextColor(Color.WHITE)

                barChart.data = barData
                xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                xAxis.labelCount = labels.size

                // Add animation
                barChart.animateY(1000, Easing.EaseInOutQuad)
                barChart.invalidate()
            }
        }
    }

    private suspend fun generateTopicContentChart() = withContext(Dispatchers.Main) {
        // Limpiar el contenedor de gráficos
        binding.chartContainer.removeAllViews()
        binding.chartContainer.visibility = View.VISIBLE

        // Crear un nuevo gráfico de pastel
        val pieChart = PieChart(requireContext())
        binding.chartContainer.addView(pieChart)
        currentChart = pieChart

        pieChart.setBackgroundColor(Color.parseColor("#2A3245"))
        pieChart.description.isEnabled = false
        pieChart.setUsePercentValues(true)
        pieChart.setDrawHoleEnabled(true)
        pieChart.setHoleColor(Color.parseColor("#2A3245"))
        pieChart.setTransparentCircleAlpha(110)
        pieChart.holeRadius = 58f
        pieChart.transparentCircleRadius = 61f
        pieChart.setDrawCenterText(true)
        pieChart.centerText = "Contenido por Tema"
        pieChart.setCenterTextColor(Color.WHITE)
        pieChart.legend.textColor = Color.WHITE
        pieChart.isRotationEnabled = true

        // Obtener datos para el gráfico
        withContext(Dispatchers.IO) {
            val topics = database.topicDao().getAllTopics()
            val contentItems = database.contentItemDao().getAllContentItems()

            val entries = ArrayList<PieEntry>()
            val colors = ArrayList<Int>()

            // Fix: Use for loop instead of forEach to avoid type mismatch
            for (topic in topics) {
                val count = contentItems.count { it.topicId == topic.id }
                if (count > 0) {
                    entries.add(PieEntry(count.toFloat(), topic.name))
                    colors.add(ColorTemplate.MATERIAL_COLORS[entries.size % ColorTemplate.MATERIAL_COLORS.size])
                }
            }

            withContext(Dispatchers.Main) {
                val dataSet = PieDataSet(entries, "Temas")
                dataSet.colors = colors
                dataSet.sliceSpace = 3f

                val pieData = PieData(dataSet)
                pieData.setValueTextSize(11f)
                pieData.setValueTextColor(Color.WHITE)

                pieChart.data = pieData
                pieChart.invalidate()
            }
        }
    }

    private suspend fun generateVideoTopicChart() = withContext(Dispatchers.Main) {
        // Limpiar el contenedor de gráficos
        binding.chartContainer.removeAllViews()
        binding.chartContainer.visibility = View.VISIBLE

        // Crear un nuevo gráfico de barras
        val barChart = BarChart(requireContext())
        binding.chartContainer.addView(barChart)
        currentChart = barChart

        barChart.setBackgroundColor(Color.parseColor("#2A3245"))
        barChart.axisLeft.textColor = Color.WHITE
        barChart.xAxis.textColor = Color.WHITE
        barChart.legend.textColor = Color.WHITE

        // Configure xAxis
        val xAxis = barChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f
        xAxis.setCenterAxisLabels(true)

        // Obtener datos para el gráfico
        withContext(Dispatchers.IO) {
            val videos = database.videoDao().getAllVideos()
            val videoTopicCounts = ArrayList<Pair<String, Int>>()

            // Use forEach instead of for loop
            videos.forEach { video ->
                val videoTitle = video.title ?: "Video ${video.id}"
                val topicCount = database.topicDao().getTopicsByCourse(video.id).size
                videoTopicCounts.add(Pair(videoTitle, topicCount))
            }

            // Ordenar y limitar para mejor visualización
            val sortedData = videoTopicCounts.sortedByDescending { it.second }.take(10)

            val entries = ArrayList<BarEntry>()
            val labels = ArrayList<String>()

            // Use forEachIndexed instead of for loop with range
            sortedData.forEachIndexed { i, pair ->
                val title = pair.first
                val count = pair.second
                entries.add(BarEntry(i.toFloat(), count.toFloat()))
                // Truncar títulos largos
                labels.add(if (title.length > 15) title.substring(0, 12) + "..." else title)
            }

            withContext(Dispatchers.Main) {
                val dataSet = BarDataSet(entries, "Temas por Video")
                dataSet.colors = ColorTemplate.COLORFUL_COLORS.toList()

                val barData = BarData(dataSet)
                barData.setValueTextSize(10f)

                barChart.data = barData
                xAxis.valueFormatter = IndexAxisValueFormatter(labels) // Using the local xAxis variable
                xAxis.labelCount = labels.size // Using the local xAxis variable

                barChart.invalidate()
            }
        }
    }

    private suspend fun generateTaskTopicChart() = withContext(Dispatchers.Main) {
        binding.chartContainer.removeAllViews()
        binding.chartContainer.visibility = View.VISIBLE

        val barChart = BarChart(requireContext())
        binding.chartContainer.addView(barChart)
        currentChart = barChart

        barChart.description.isEnabled = false
        barChart.setDrawGridBackground(false)
        barChart.setDrawBarShadow(false)
        barChart.setDrawValueAboveBar(true)
        barChart.setPinchZoom(false)
        barChart.setDrawGridBackground(false)
        barChart.setBackgroundColor(Color.parseColor("#2A3245"))
        barChart.axisLeft.textColor = Color.WHITE
        barChart.xAxis.textColor = Color.WHITE
        barChart.legend.textColor = Color.WHITE

        val xAxis = barChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f
        xAxis.setCenterAxisLabels(true)
        barChart.axisLeft.setDrawGridLines(false)
        barChart.axisRight.isEnabled = false
        barChart.legend.isEnabled = true

        withContext(Dispatchers.IO) {
            val topics = database.topicDao().getAllTopics()
            val tasks = database.taskDao().getAllTasks()
            val topicTaskCounts = topics.map { topic ->
                topic.name to tasks.count { it.topicId == topic.id }
            }.filter { it.second > 0 }
                .sortedByDescending { it.second }
                .take(10)

            val entries = ArrayList<BarEntry>()
            val labels = ArrayList<String>()

            topicTaskCounts.forEachIndexed { index, (topicName, count) ->
                entries.add(BarEntry(index.toFloat(), count.toFloat()))
                labels.add(if (topicName.length > 15) topicName.substring(0, 12) + "..." else topicName)
            }

            withContext(Dispatchers.Main) {
                val dataSet = BarDataSet(entries, "Tareas por Tema")
                dataSet.colors = ColorTemplate.COLORFUL_COLORS.toList()
                dataSet.valueTextColor = Color.WHITE

                val barData = BarData(dataSet)
                barData.setValueTextSize(10f)
                barData.setValueTextColor(Color.WHITE)

                barChart.data = barData
                xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                xAxis.labelCount = labels.size

                barChart.animateY(1000, Easing.EaseInOutQuad)
                barChart.invalidate()
            }
        }
    }

    private suspend fun generateSubscriptionChart() = withContext(Dispatchers.Main) {
        binding.chartContainer.removeAllViews()
        binding.chartContainer.visibility = View.VISIBLE

        val barChart = BarChart(requireContext())
        binding.chartContainer.addView(barChart)
        currentChart = barChart

        barChart.description.isEnabled = false
        barChart.setDrawGridBackground(false)
        barChart.setDrawBarShadow(false)
        barChart.setDrawValueAboveBar(true)
        barChart.setPinchZoom(false)
        barChart.setDrawGridBackground(false)
        barChart.setBackgroundColor(Color.parseColor("#2A3245"))
        barChart.axisLeft.textColor = Color.WHITE
        barChart.xAxis.textColor = Color.WHITE
        barChart.legend.textColor = Color.WHITE

        val xAxis = barChart.xAxis
        xAxis.position = XAxis.XAxisPosition.BOTTOM
        xAxis.granularity = 1f
        xAxis.setCenterAxisLabels(true)
        barChart.axisLeft.setDrawGridLines(false)
        barChart.axisRight.isEnabled = false
        barChart.legend.isEnabled = true

        withContext(Dispatchers.IO) {
            val subscriptions = database.subscriptionDao().getAllSubscriptions()
            val creatorCounts = subscriptions.groupBy { it.creatorUsername }
                .mapValues { it.value.size }
                .toList()
                .sortedByDescending { it.second }
                .take(10)

            val entries = ArrayList<BarEntry>()
            val labels = ArrayList<String>()

            creatorCounts.forEachIndexed { index, (creator, count) ->
                entries.add(BarEntry(index.toFloat(), count.toFloat()))
                labels.add(creator)
            }

            withContext(Dispatchers.Main) {
                val dataSet = BarDataSet(entries, "Suscripciones por Creador")
                dataSet.colors = ColorTemplate.MATERIAL_COLORS.toList()
                dataSet.valueTextColor = Color.WHITE

                val barData = BarData(dataSet)
                barData.setValueTextSize(10f)
                barData.setValueTextColor(Color.WHITE)

                barChart.data = barData
                xAxis.valueFormatter = IndexAxisValueFormatter(labels)
                xAxis.labelCount = labels.size

                barChart.animateY(1000, Easing.EaseInOutQuad)
                barChart.invalidate()
            }
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
        lifecycleScope.launch {
            try {
                val response = mcpService.processQuery(userInput)
                // Remove typing indicator
                chatAdapter.getMessages().lastOrNull()?.let {
                    if (it.text == "Escribiendo..." && !it.isUser) {
                        chatAdapter.clear()
                        chatAdapter.getMessages().forEach { msg ->
                            chatAdapter.addMessage(msg)
                        }
                    }
                }
                addMessageToChat(response, false)
            } catch (e: Exception) {
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

    private fun restoreChatHistory() {
        try {
            val savedMessages = chatPrefs.getString(CHAT_HISTORY_KEY, null)
            if (!savedMessages.isNullOrEmpty()) {
                val messageList = savedMessages.split("|||").mapNotNull { messageStr ->
                    val parts = messageStr.split(":::")
                    if (parts.size >= 3) {
                        ChatMessage(
                            text = parts[0],
                            isUser = parts[1].toBoolean(),
                            timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                        )
                    } else null
                }
                chatAdapter.restoreMessages(messageList)
                chatHistory.addAll(messageList)
                
                // Scroll to bottom if there are messages
                if (messageList.isNotEmpty()) {
                    binding.chatRecyclerView.scrollToPosition(messageList.size - 1)
                    binding.centerBrainIcon.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            Log.e("DatabaseQueryFragment", "Error restoring chat history", e)
        }
    }

    private fun saveChatHistory() {
        try {
            val messages = chatAdapter.getMessages()
            val messageStrings = messages.map { message ->
                "${message.text}:::${message.isUser}:::${message.timestamp}"
            }
            chatPrefs.edit()
                .putString(CHAT_HISTORY_KEY, messageStrings.joinToString("|||"))
                .apply()
        } catch (e: Exception) {
            Log.e("DatabaseQueryFragment", "Error saving chat history", e)
        }
    }

    private fun clearChatHistory() {
        chatAdapter.clear()
        chatHistory.clear()
        currentConversationContext.clear()
        chatPrefs.edit().remove(CHAT_HISTORY_KEY).apply()
        binding.centerBrainIcon.visibility = View.VISIBLE
        addWelcomeMessage()
    }

    private fun addWelcomeMessage() {
        val welcomeText = """
            ¡Hola! 👋 Soy el Sistema MCP.
            
            Puedo ayudarte a consultar la base de datos usando lenguaje natural. 
            
            Algunos ejemplos de lo que puedes preguntarme:
            • "¿Cuántos usuarios hay?"
            • "Muestra los videos más populares"
            • "¿Qué temas tienen más contenido?"
            • "Crear un gráfico de suscripciones"
            
            ¿En qué puedo ayudarte hoy?
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

            // Launch a coroutine to process the query
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Process the query using DatabaseQueryService
                    val result = withContext(Dispatchers.IO) {
                        databaseQueryService.processQuery(query)
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
        binding.resultText?.text = result
        // Or if you're using a RecyclerView adapter:
        // adapter.submitList(parseResults(result))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}