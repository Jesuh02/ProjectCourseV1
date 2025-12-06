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
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.ui.ChatMessage
import java.text.SimpleDateFormat
import java.util.*

class DatabaseChatAdapter(
    private val onEditUserMessageClick: (ChatMessage) -> Unit = {}
) : RecyclerView.Adapter<DatabaseChatAdapter.MessageViewHolder>() {
    
    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message_improved, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }

    override fun getItemCount() = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }

    fun updateMessage(messageId: String, newText: String) {
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index != -1) {
            val oldMessage = messages[index]
            messages[index] = oldMessage.copy(text = newText)
            notifyItemChanged(index)
        }
    }

    fun updateLastMessage(newText: String) {
        if (messages.isNotEmpty() && !messages.last().isUser) {
            val lastIndex = messages.size - 1
            val lastMessage = messages[lastIndex]
            messages[lastIndex] = lastMessage.copy(text = newText)
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
        removeTypingIndicator()
        val typingMessage = ChatMessage.createTypingIndicator()
        messages.add(typingMessage)
        notifyItemInserted(messages.size - 1)
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

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

    inner class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val userMessageContainer: ViewGroup = itemView.findViewById(R.id.userMessageContainer)
        private val botMessageContainer: ViewGroup = itemView.findViewById(R.id.botMessageContainer)
        private val userMessageTextView: TextView = itemView.findViewById(R.id.userMessageTextView)
        private val botMessageTextView: TextView = itemView.findViewById(R.id.botMessageTextView)
        private val userMessageTime: TextView = itemView.findViewById(R.id.userMessageTime)
        private val botMessageTime: TextView = itemView.findViewById(R.id.botMessageTime)
        private val timestampTextView: TextView = itemView.findViewById(R.id.messageTimestampTextView)
        private val copyButton: ImageButton = itemView.findViewById(R.id.copyButton)
        private val shareButton: ImageButton = itemView.findViewById(R.id.shareButton)
        private val copyButtonUser: ImageButton = itemView.findViewById(R.id.copyButtonUser)
        private val shareButtonUser: ImageButton = itemView.findViewById(R.id.shareButtonUser)

        fun bind(message: ChatMessage) {
            // Animate the message appearance
            animateMessage(itemView)
            
            if (message.isUser) {
                // Show user message
                userMessageContainer.visibility = View.VISIBLE
                botMessageContainer.visibility = View.GONE
                
                userMessageTextView.text = formatBoldText(message.text)
                userMessageTime.text = timeFormat.format(Date(message.timestamp))
                
                setupUserMessageActions(message)
            } else {
                // Show bot message
                userMessageContainer.visibility = View.GONE
                botMessageContainer.visibility = View.VISIBLE
                
                // Enhanced bot message formatting
                val formattedMessage = formatBotMessage(message)
                botMessageTextView.text = formatBoldText(formattedMessage)
                botMessageTime.text = timeFormat.format(Date(message.timestamp))
                
                // Setup action buttons for bot messages
                setupBotMessageActions(message)
            }
            
            // Hide standalone timestamp (we're using inline timestamps now)
            timestampTextView.visibility = View.GONE
        }
        
        private fun animateMessage(view: View) {
            view.alpha = 0f
            view.translationY = 30f
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        
        private fun formatBotMessage(message: ChatMessage): String {
            // Special handling for typing indicator
            if (message.isTyping) {
                return "🤖 DeepSeek-V3.2-Speciale está procesando tu consulta..."
            }
            
            // Add helpful formatting for common response types
            return when {
                message.isError -> "⚠️ ${message.text}"
                message.isGraphResponse -> "📊 ${message.text}"
                message.text.contains("Error") -> "⚠️ ${message.text}"
                message.text.contains("registros encontrados") -> "📊 ${message.text}"
                message.text.contains("Total") -> "📈 ${message.text}"
                message.text.contains("Lista") -> "📋 ${message.text}"
                message.text.contains("Usuario:") -> "👤 ${message.text}"
                message.text.contains("Video:") -> "🎥 ${message.text}"
                message.text.contains("Curso:") -> "📚 ${message.text}"
                message.text.contains("base de datos") && !message.text.contains("¿") -> "💾 ${message.text}"
                else -> message.text
            }
        }
        
        private fun setupBotMessageActions(message: ChatMessage) {
            copyButton.setImageResource(R.drawable.ic_copy_minimal)
            shareButton.setImageResource(R.drawable.ic_share_minimal)

            copyButton.setOnClickListener {
                copyToClipboard(message.text)
            }
            
            shareButton.setOnClickListener {
                shareMessage(message.text)
            }
        }

        private fun setupUserMessageActions(message: ChatMessage) {
            // Use minimal icons for user actions too
            copyButtonUser.setImageResource(R.drawable.ic_copy_minimal)
            shareButtonUser.setImageResource(R.drawable.ic_share_minimal)

            copyButtonUser.setOnClickListener {
                copyToClipboard(message.text)
            }
            
            shareButtonUser.setOnClickListener {
                shareMessage(message.text)
            }
        }
        
        private fun copyToClipboard(text: String) {
            val clipboard = itemView.context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Mensaje de DeepSeek", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(itemView.context, "💾 Mensaje copiado al portapapeles", Toast.LENGTH_SHORT).show()
        }
        
        private fun shareMessage(text: String) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "🤖 Respuesta de DeepSeek-V3.2-Speciale:\n\n$text")
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
    }
}
