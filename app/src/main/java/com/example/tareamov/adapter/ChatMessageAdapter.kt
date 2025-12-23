package com.example.tareamov.ui.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.data.entity.ChatMessage
import com.bumptech.glide.Glide
import com.example.tareamov.util.SessionManager
import java.text.SimpleDateFormat
import java.util.*

class ChatMessageAdapter(
    private val onAddCalificationClick: (ChatMessage) -> Unit = {},
    private val onRejectCalificationClick: (ChatMessage) -> Unit = {},
    private val onEditUserMessageClick: (ChatMessage) -> Unit = {},
    private var taskInfo: TaskInfo? = null
) : ListAdapter<ChatMessage, ChatMessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    // Avatar URLs (can be set from fragment)
    private var botAvatarUrl: String? = null
    private var currentUserAvatarUrl: String? = null

    fun setBotAvatarUrl(url: String?) {
        botAvatarUrl = url
        notifyDataSetChanged()
    }

    fun setUserAvatarUrl(url: String?) {
        currentUserAvatarUrl = url
        notifyDataSetChanged()
    }

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    data class TaskInfo(
        val taskName: String,
        val taskDescription: String,
        val topicName: String,
        val courseTitle: String,
        val deliveryDate: String
    )

    fun updateTaskInfo(newTaskInfo: TaskInfo) {
        taskInfo = newTaskInfo
        notifyDataSetChanged()
    }
    
    /**
     * Actualiza un mensaje existente en la lista
     */
    fun updateMessage(messageId: Long, newText: String) {
        val currentList = currentList.toMutableList()
        val index = currentList.indexOfFirst { it.id == messageId }
        if (index != -1) {
            val updatedMessage = currentList[index].copy(message = newText)
            currentList[index] = updatedMessage
            submitList(currentList)
        }
    }
    
    /**
     * Remueve mensajes desde una posición específica en adelante
     */
    fun removeMessagesFromPosition(position: Int) {
        val currentList = currentList.toMutableList()
        if (position < currentList.size) {
            val newList = currentList.take(position)
            submitList(newList)
        }
    }
    
    /**
     * Remueve mensajes después de un mensaje específico
     */
    fun removeMessagesAfter(messageId: Long) {
        val currentList = currentList.toMutableList()
        val index = currentList.indexOfFirst { it.id == messageId }
        if (index != -1 && index < currentList.size - 1) {
            val newList = currentList.take(index + 1)
            submitList(newList)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message_improved, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val userAvatarImageView: ImageView? = itemView.findViewById(R.id.userAvatar)
        private val botAvatarImageView: ImageView? = itemView.findViewById(R.id.botAvatar)
        private val userMessageContainer: LinearLayout = itemView.findViewById(R.id.userMessageContainer)
        private val botMessageContainer: LinearLayout = itemView.findViewById(R.id.botMessageContainer)
        private val userMessageTextView: TextView = itemView.findViewById(R.id.userMessageTextView)
        private val botMessageTextView: TextView = itemView.findViewById(R.id.botMessageTextView)
        private val userMessageTime: TextView = itemView.findViewById(R.id.userMessageTime)
        private val botMessageTime: TextView = itemView.findViewById(R.id.botMessageTime)
        private val timestampTextView: TextView = itemView.findViewById(R.id.messageTimestampTextView)
        private val calificationButtonsContainer: LinearLayout = itemView.findViewById(R.id.calificationButtonsContainer)
        private val addCalificationButton: Button = itemView.findViewById(R.id.addCalificationButton)
        private val rejectCalificationButton: Button = itemView.findViewById(R.id.rejectCalificationButton)
        private val copyButton: ImageButton = itemView.findViewById(R.id.copyButton)
        private val shareButton: ImageButton = itemView.findViewById(R.id.shareButton)
        private val copyButtonUser: ImageButton = itemView.findViewById(R.id.copyButtonUser)
        private val shareButtonUser: ImageButton = itemView.findViewById(R.id.shareButtonUser)

        fun bind(message: ChatMessage) {
            if (message.isFromUser) {
                // Show user message
                userMessageContainer.visibility = View.VISIBLE
                botMessageContainer.visibility = View.GONE
                
                userMessageTextView.text = formatBoldText(message.message)
                try {
                    val sess = SessionManager.getInstance(itemView.context)
                    // Prefer sender-specific avatar stored on the message, then adapter-level user avatar, then session
                    val avatarUri = message.senderAvatar ?: currentUserAvatarUrl ?: sess.getUserAvatar()
                    userAvatarImageView?.let { iv ->
                        if (!avatarUri.isNullOrEmpty()) {
                            iv.clearColorFilter() // Clear tint for user image
                            Glide.with(itemView.context)
                                .load(avatarUri)
                                .placeholder(R.drawable.ic_profile_avatars)
                                .error(R.drawable.ic_profile_avatars)
                                .circleCrop()
                                .into(iv)
                        } else {
                            iv.setImageResource(R.drawable.ic_profile_avatars)
                        }
                        iv.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    // Fail silently; avatar not critical
                }
                userMessageTime.text = timeFormat.format(Date(message.timestamp))
                
                setupUserMessageActions(message)
                calificationButtonsContainer.visibility = View.GONE
            } else {
                // Show bot message
                // Show bot avatar and bot message container
                botAvatarImageView?.visibility = View.VISIBLE
                userMessageContainer.visibility = View.GONE
                botMessageContainer.visibility = View.VISIBLE

                // Load bot avatar (fallback to default)
                try {
                    val botUrl = botAvatarUrl ?: "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png"
                    botAvatarImageView?.let { iv ->
                        iv.clearColorFilter() // Clear tint for bot image
                        Glide.with(itemView.context)
                            .load(botUrl)
                            .placeholder(R.drawable.ic_cpu)
                            .error(R.drawable.ic_cpu)
                            .circleCrop()
                            .into(iv)
                        iv.visibility = View.VISIBLE
                    }
                } catch (e: Exception) {
                    // ignore
                }
                
                // Enhanced bot message formatting
                val formattedMessage = if (message.hasCalification && taskInfo != null) {
                    buildEnhancedTaskMessage(message)
                } else {
                    formatBotMessage(message.message)
                }
                
                botMessageTextView.text = formatBoldText(formattedMessage)
                botMessageTime.text = timeFormat.format(Date(message.timestamp))
                
                // Setup action buttons for bot messages
                setupBotMessageActions(message)
                
                // Show calification buttons if needed
                if (message.hasCalification && !message.calificationAdded) {
                    calificationButtonsContainer.visibility = View.VISIBLE
                    setupCalificationButtons(message)
                } else {
                    calificationButtonsContainer.visibility = View.GONE
                }
            }
            
            // Hide standalone timestamp (we're using inline timestamps now)
            timestampTextView.visibility = View.GONE
        }
        
        private fun buildEnhancedTaskMessage(message: ChatMessage): String {
            return buildString {
                appendLine("🤖 **ANÁLISIS DE TAREA COMPLETADO**")
                appendLine("⚡ _DeepSeek-V3.2-Speciale_")
                appendLine()
                appendLine("**${taskInfo!!.taskName}**")
                appendLine("📚 Tema: ${taskInfo!!.topicName}")
                appendLine("📅 Entregado: ${taskInfo!!.deliveryDate}")
                appendLine()
                appendLine("📝 **Descripción:**")
                appendLine(taskInfo!!.taskDescription)
                appendLine()
                
                val gradeValue = extractGradeFromMessage(message.message)
                val finalGrade = gradeValue?.coerceIn(0f, 10f) ?: 8.7f
                
                appendLine("🎯 **CALIFICACIÓN SUGERIDA**")
                appendLine("📊 Puntuación: ${if (finalGrade % 1 == 0f) finalGrade.toInt().toString() else String.format("%.1f", finalGrade)}/10")
                appendLine("⭐ Nivel: ${getQualityLabel((finalGrade * 10).toInt())}")
                appendLine()
                appendLine("---")
                appendLine()
                append(message.message)
            }
        }
        
        private fun formatBotMessage(message: String): String {
            // Add helpful formatting for common response types
            return when {
                message.contains("Error") -> "⚠️ $message"
                message.contains("registros encontrados") -> "📊 $message"
                message.contains("Total") -> "📈 $message"
                message.contains("Lista") -> "📋 $message"
                message.contains("Usuario:") -> "👤 $message"
                message.contains("Video:") -> "🎥 $message"
                message.contains("Curso:") -> "📚 $message"
                else -> message
            }
        }
        
        private fun setupBotMessageActions(message: ChatMessage) {
            copyButton.setOnClickListener {
                copyToClipboard(message.message)
            }
            
            shareButton.setOnClickListener {
                shareMessage(message.message)
            }
        }

        private fun setupUserMessageActions(message: ChatMessage) {
            copyButtonUser.setOnClickListener {
                copyToClipboard(message.message)
            }
            
            shareButtonUser.setOnClickListener {
                shareMessage(message.message)
            }
        }
        
        private fun setupCalificationButtons(message: ChatMessage) {
            addCalificationButton.setOnClickListener {
                onAddCalificationClick(message)
            }
            
            rejectCalificationButton.setOnClickListener {
                onRejectCalificationClick(message)
            }
        }
        
        private fun copyToClipboard(text: String) {
            val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Mensaje de DeepSeek", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(itemView.context, "Mensaje copiado al portapapeles", Toast.LENGTH_SHORT).show()
        }
        
        private fun shareMessage(text: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Respuesta de DeepSeek-V3.2-Speciale:\n\n$text")
                putExtra(Intent.EXTRA_SUBJECT, "Conversación con IA")
            }
            itemView.context.startActivity(Intent.createChooser(intent, "Compartir respuesta"))
        }
        
        private fun formatBoldText(text: String): SpannableString {
            // Process markdown formatting: **bold**, *italic*, _italic_, ##headers
            val result = StringBuilder()
            val boldRanges = mutableListOf<Pair<Int, Int>>()
            val italicRanges = mutableListOf<Pair<Int, Int>>()
            
            var i = 0
            while (i < text.length) {
                when {
                    // Bold: **text**
                    i + 1 < text.length && text[i] == '*' && text[i + 1] == '*' -> {
                        val endIndex = text.indexOf("**", i + 2)
                        if (endIndex != -1) {
                            val startPos = result.length
                            val boldContent = text.substring(i + 2, endIndex)
                            result.append(boldContent)
                            boldRanges.add(Pair(startPos, result.length))
                            i = endIndex + 2
                        } else {
                            result.append(text[i])
                            i++
                        }
                    }
                    // Italic: _text_ (but not in middle of word)
                    text[i] == '_' && (i == 0 || !text[i-1].isLetterOrDigit()) -> {
                        val endIndex = text.indexOf('_', i + 1)
                        if (endIndex != -1 && (endIndex + 1 >= text.length || !text[endIndex + 1].isLetterOrDigit())) {
                            val startPos = result.length
                            val italicContent = text.substring(i + 1, endIndex)
                            result.append(italicContent)
                            italicRanges.add(Pair(startPos, result.length))
                            i = endIndex + 1
                        } else {
                            result.append(text[i])
                            i++
                        }
                    }
                    // Headers: ## text -> just bold without ##
                    text[i] == '#' && i + 1 < text.length && text[i + 1] == '#' -> {
                        // Skip the ## and any following space
                        i += 2
                        while (i < text.length && text[i] == ' ') i++
                        // Find end of line
                        val lineEnd = text.indexOf('\n', i).let { if (it == -1) text.length else it }
                        val startPos = result.length
                        val headerContent = text.substring(i, lineEnd)
                        result.append(headerContent)
                        boldRanges.add(Pair(startPos, result.length))
                        i = lineEnd
                    }
                    else -> {
                        result.append(text[i])
                        i++
                    }
                }
            }
            
            val spannableString = SpannableString(result.toString())
            
            // Apply bold spans
            for ((start, end) in boldRanges) {
                if (start >= 0 && end <= spannableString.length && start < end) {
                    spannableString.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            
            // Apply italic spans
            for ((start, end) in italicRanges) {
                if (start >= 0 && end <= spannableString.length && start < end) {
                    spannableString.setSpan(
                        StyleSpan(Typeface.ITALIC),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            
            return spannableString
        }
        
        private fun getQualityLabel(grade: Int): String {
            return when {
                grade >= 95 -> "Excepcional"
                grade >= 90 -> "Excelente" 
                grade >= 85 -> "Muy Bueno"
                grade >= 80 -> "Bueno"
                grade >= 75 -> "Satisfactorio"
                grade >= 70 -> "Aceptable"
                grade >= 60 -> "Regular"
                else -> "Necesita Mejora"
            }
        }
        
        private fun extractGradeFromMessage(message: String): Float? {
            // Enhanced grade extraction with more patterns
            val patterns = listOf(
                Regex("calificación\\s*:?\\s*(\\d+(?:[.,]\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE),
                Regex("nota\\s*:?\\s*(\\d+(?:[.,]\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE),
                Regex("puntuación\\s*:?\\s*(\\d+(?:[.,]\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE),
                Regex("(\\d+(?:[.,]\\d+)?)/10", RegexOption.IGNORE_CASE),
                Regex("grade\\s*:?\\s*(\\d+(?:[.,]\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE),
                Regex("score\\s*:?\\s*(\\d+(?:[.,]\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE),
                Regex("rating\\s*:?\\s*(\\d+(?:[.,]\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in patterns) {
                val match = pattern.find(message)
                if (match != null) {
                    val gradeStr = match.groupValues[1].replace(",", ".")
                    return gradeStr.toFloatOrNull()
                }
            }
            return null
        }
    }

    class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
            return oldItem == newItem
        }
    }
}
