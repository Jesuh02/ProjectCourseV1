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
import java.text.SimpleDateFormat
import java.util.*

class ChatMessageAdapter(
    private val onAddCalificationClick: (ChatMessage) -> Unit = {},
    private val onRejectCalificationClick: (ChatMessage) -> Unit = {},
    private val onEditUserMessageClick: (ChatMessage) -> Unit = {},
    private var taskInfo: TaskInfo? = null
) : ListAdapter<ChatMessage, ChatMessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

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

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message_improved, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
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
        private val editUserMessageButton: ImageButton = itemView.findViewById(R.id.editUserMessageButton)
        private val copyUserMessageButton: ImageButton = itemView.findViewById(R.id.copyUserMessageButton)

        fun bind(message: ChatMessage) {
            if (message.isFromUser) {
                // Show user message
                userMessageContainer.visibility = View.VISIBLE
                botMessageContainer.visibility = View.GONE
                
                userMessageTextView.text = formatBoldText(message.message)
                userMessageTime.text = timeFormat.format(Date(message.timestamp))
                
                // Setup user message action buttons
                setupUserMessageActions(message)
                
                calificationButtonsContainer.visibility = View.GONE
            } else {
                // Show bot message
                userMessageContainer.visibility = View.GONE
                botMessageContainer.visibility = View.VISIBLE
                
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
                appendLine("📚 **ANÁLISIS DE TAREA COMPLETADO**")
                appendLine()
                appendLine("**${taskInfo!!.taskName}**")
                appendLine("📚 Tema: ${taskInfo!!.topicName}")
                appendLine("📅 Entregado: ${taskInfo!!.deliveryDate}")
                appendLine()
                appendLine("📝 **Descripción:**")
                appendLine(taskInfo!!.taskDescription)
                appendLine()
                
                val gradeValue = extractGradeFromMessage(message.message)
                val gradePercentage = if (gradeValue != null) (gradeValue * 10).toInt() else 87
                
                appendLine("🎯 **CALIFICACIÓN SUGERIDA**")
                appendLine("📊 Puntuación: $gradePercentage/100")
                appendLine("⭐ Nivel: ${getQualityLabel(gradePercentage)}")
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
            editUserMessageButton.setOnClickListener {
                onEditUserMessageClick(message)
            }
            
            copyUserMessageButton.setOnClickListener {
                copyToClipboard(message.message)
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
            val clip = ClipData.newPlainText("Mensaje de Llama", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(itemView.context, "Mensaje copiado al portapapeles", Toast.LENGTH_SHORT).show()
        }
        
        private fun shareMessage(text: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "Respuesta de Llama 3.3:\n\n$text")
                putExtra(Intent.EXTRA_SUBJECT, "Conversación con IA")
            }
            itemView.context.startActivity(Intent.createChooser(intent, "Compartir respuesta"))
        }
        
        private fun formatBoldText(text: String): SpannableString {
            // Enhanced text formatting with better markdown support
            val cleanText = text.replace(Regex("\\*\\*(.*?)\\*\\*")) { matchResult ->
                matchResult.groupValues[1]
            }
            
            val spannableString = SpannableString(cleanText)
            val regex = Regex("\\*\\*(.*?)\\*\\*")
            var offset = 0
            
            // Apply bold formatting to text between **
            regex.findAll(text).forEach { match ->
                val originalStart = match.range.first
                val boldText = match.groupValues[1]
                
                val cleanStart = originalStart - offset
                val cleanEnd = cleanStart + boldText.length
                
                if (cleanStart >= 0 && cleanEnd <= cleanText.length && cleanStart < cleanEnd) {
                    spannableString.setSpan(
                        StyleSpan(Typeface.BOLD),
                        cleanStart,
                        cleanEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                
                offset += 4 // Account for removed ** **
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
