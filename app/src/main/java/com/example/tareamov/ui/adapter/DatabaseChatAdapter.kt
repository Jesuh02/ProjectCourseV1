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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.card.MaterialCardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.ui.ChatMessage
import java.text.SimpleDateFormat
import java.util.*

class DatabaseChatAdapter(
    private val onEditUserMessageClick: (ChatMessage) -> Unit = {}
) : RecyclerView.Adapter<DatabaseChatAdapter.MessageViewHolder>() {
    
    private val messages = mutableListOf<ChatMessage>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val animatedPositions = mutableSetOf<Int>() // Track already animated positions
    private var currentUserAvatarUrl: String? = null

    fun setUserAvatarUrl(url: String?) {
        currentUserAvatarUrl = url
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message_improved, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position], shouldAnimate = !animatedPositions.contains(position))
        animatedPositions.add(position) // Mark as animated after first bind
    }
    
    override fun onBindViewHolder(holder: MessageViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            // Update without animation when payload is present
            holder.bind(messages[position], shouldAnimate = false)
        }
    }

    override fun getItemCount() = messages.size

    fun addMessage(message: ChatMessage) {
        messages.add(message)
        val position = messages.size - 1
        animatedPositions.add(position) // Mark as already animated
        notifyItemInserted(position)
    }

    fun updateMessage(messageId: String, newText: String) {
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index != -1) {
            val oldMessage = messages[index]
            messages[index] = oldMessage.copy(text = newText)
            // Keep the position in animatedPositions to prevent re-animation
            notifyItemChanged(index, "NO_ANIMATION") // Use payload to skip animation
        }
    }

    fun updateLastMessage(newText: String) {
        if (messages.isNotEmpty() && !messages.last().isUser) {
            val lastIndex = messages.size - 1
            val lastMessage = messages[lastIndex]
            messages[lastIndex] = lastMessage.copy(text = newText)
            // Use payload to skip animation on update
            notifyItemChanged(lastIndex, "NO_ANIMATION")
        }
    }

    fun removeTypingIndicator() {
        val typingIndex = messages.indexOfFirst { it.isTyping }
        if (typingIndex != -1) {
            messages.removeAt(typingIndex)
            // Rebuild animated positions set after removal
            val newAnimatedPositions = mutableSetOf<Int>()
            animatedPositions.forEach { pos ->
                when {
                    pos < typingIndex -> newAnimatedPositions.add(pos)
                    pos > typingIndex -> newAnimatedPositions.add(pos - 1)
                }
            }
            animatedPositions.clear()
            animatedPositions.addAll(newAnimatedPositions)
            notifyItemRemoved(typingIndex)
        }
    }

    fun addTypingIndicator() {
        removeTypingIndicator()
        val typingMessage = ChatMessage.createTypingIndicator()
        messages.add(typingMessage)
        val position = messages.size - 1
        animatedPositions.add(position) // Mark as already animated
        notifyItemInserted(position)
    }

    fun getMessages(): List<ChatMessage> = messages.toList()

    fun restoreMessages(savedMessages: List<ChatMessage>) {
        messages.clear()
        messages.addAll(savedMessages)
        animatedPositions.clear() // Reset animation tracking
        // Mark all restored messages as already animated to prevent re-animation
        for (i in savedMessages.indices) {
            animatedPositions.add(i)
        }
        notifyDataSetChanged()
    }

    fun clear() {
        val size = messages.size
        messages.clear()
        animatedPositions.clear() // Reset animation tracking
        notifyItemRangeRemoved(0, size)
    }

    fun removeMessageById(messageId: String) {
        val index = messages.indexOfFirst { it.messageId == messageId }
        if (index != -1) {
            messages.removeAt(index)
            // Rebuild animated positions set after removal
            val newAnimatedPositions = mutableSetOf<Int>()
            animatedPositions.forEach { pos ->
                when {
                    pos < index -> newAnimatedPositions.add(pos)
                    pos > index -> newAnimatedPositions.add(pos - 1)
                    // Skip pos == index (the removed item)
                }
            }
            animatedPositions.clear()
            animatedPositions.addAll(newAnimatedPositions)
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
        // Make editButtonUser nullable and safe to avoid crashes if not found in layout
        private val editButtonUser: ImageButton? = try {
            itemView.findViewById(R.id.editButtonUser)
        } catch (e: Exception) { null }
        private val userAvatarImageView: ImageView? = itemView.findViewById(R.id.userAvatar)
        private val botAvatarImageView: ImageView? = itemView.findViewById(R.id.botAvatar)

        // Attached File Views (Make nullable and safe)
        private val attachedFileContainer: androidx.cardview.widget.CardView? = try {
            itemView.findViewById(R.id.attachedFileContainer)
        } catch (e: Exception) { null }
        
        private val attachedFileName: TextView? = try {
            itemView.findViewById(R.id.attachedFileName)
        } catch (e: Exception) { null }
        
        private val attachedFileType: TextView? = try {
            itemView.findViewById(R.id.attachedFileType)
        } catch (e: Exception) { null }

        fun bind(message: ChatMessage, shouldAnimate: Boolean = true) {
            // Only animate new messages, not updates
            if (shouldAnimate) {
                animateMessage(itemView)
            } else {
                // Reset view state for updates without animation
                itemView.alpha = 1f
                itemView.translationY = 0f
            }
            
            if (message.isUser) {
                // Show user message
                userMessageContainer.visibility = View.VISIBLE
                botMessageContainer.visibility = View.GONE
                
                userMessageTextView.text = formatBoldText(message.text)
                userMessageTime.text = timeFormat.format(Date(message.timestamp))
                
                // Show attached file if present
                if (message.attachedFileUrl != null && message.attachedFileName != null) {
                    attachedFileContainer?.visibility = View.VISIBLE
                    attachedFileName?.text = message.attachedFileName
                    attachedFileType?.text = message.attachedFileType ?: "Archivo adjunto"
                } else {
                    attachedFileContainer?.visibility = View.GONE
                }
                
                // Load user avatar
                userAvatarImageView?.let { iv ->
                    val avatarUri = message.senderAvatar.takeUnless { it.isNullOrEmpty() } ?: currentUserAvatarUrl
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

                setupUserMessageActions(message)
            } else {
                // Show bot message
                userMessageContainer.visibility = View.GONE
                botMessageContainer.visibility = View.VISIBLE
                
                // Load bot avatar
                botAvatarImageView?.let { iv ->
                    iv.clearColorFilter() // Clear tint for bot image
                    Glide.with(itemView.context)
                        .load("https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/data/deepseek-color.png")
                        .placeholder(R.drawable.ic_cpu)
                        .error(R.drawable.ic_cpu)
                        .circleCrop()
                        .into(iv)
                    iv.visibility = View.VISIBLE
                }

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
            editButtonUser?.setImageResource(R.drawable.ic_edit_minimal)

            copyButtonUser.setOnClickListener {
                copyToClipboard(message.text)
            }
            
            shareButtonUser.setOnClickListener {
                shareMessage(message.text)
            }
            
            // Safe call on nullable editButtonUser
            editButtonUser?.setOnClickListener {
                onEditUserMessageClick(message)
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
        
        /**
         * Format text with Markdown bold (**text**) and remove asterisks
         * Converts **bold** to actual bold spans and removes the asterisks
         */
        private fun formatBoldText(text: String): SpannableString {
            var processedText = text
            
            // Convert markdown tables to clean format if present
            if (text.contains("|") && text.contains("---")) {
                processedText = formatMarkdownTable(text)
            }
            
            // Process Markdown bold (**text**) - remove asterisks and track positions
            val boldPattern = Regex("\\*\\*(.+?)\\*\\*")
            val boldRanges = mutableListOf<Pair<Int, Int>>()
            
            // First pass: find all bold sections and calculate their final positions
            var offset = 0
            var tempText = processedText
            
            boldPattern.findAll(processedText).forEach { match ->
                val adjustedStart = match.range.first - offset
                val contentLength = match.groupValues[1].length
                boldRanges.add(Pair(adjustedStart, adjustedStart + contentLength))
                offset += 4 // Remove 4 asterisks (2 on each side)
            }
            
            // Remove all ** markers
            tempText = tempText.replace("**", "")
            processedText = tempText
            
            val spannableString = SpannableString(processedText)
            
            // Apply bold to all tracked ranges from Markdown
            boldRanges.forEach { (start, end) ->
                if (start >= 0 && end <= spannableString.length && start < end) {
                    spannableString.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
            
            // Also apply bold to lines with ":" (existing functionality)
            val lines = processedText.split("\n")
            var currentPos = 0
            
            for (line in lines) {
                // Bold for card separators and labels before ":"
                if (line.contains(":") && !line.startsWith("━") && !line.startsWith("Total") && !line.startsWith("*")) {
                    val colonIndex = line.indexOf(":")
                    if (colonIndex > 0 && currentPos + colonIndex <= spannableString.length) {
                        // Only apply if not already in a bold range
                        val rangeStart = currentPos
                        val rangeEnd = currentPos + colonIndex
                        val alreadyBold = boldRanges.any { (s, e) -> 
                            (rangeStart >= s && rangeStart < e) || (rangeEnd > s && rangeEnd <= e)
                        }
                        if (!alreadyBold) {
                            spannableString.setSpan(
                                StyleSpan(Typeface.BOLD),
                                rangeStart,
                                rangeEnd,
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                            )
                        }
                    }
                }
                currentPos += line.length + 1 // +1 for newline
            }
            
            return spannableString
        }
        
        /**
         * Format markdown table to a clean card format
         */
        private fun formatMarkdownTable(text: String): String {
            val lines = text.split("\n")
            val result = StringBuilder()
            var headers = listOf<String>()
            var rowNumber = 0
            
            for (line in lines) {
                val trimmedLine = line.trim()
                
                // Skip separator lines
                if (trimmedLine.matches(Regex("^\\|[\\s\\-:|]+\\|$")) || 
                    trimmedLine.matches(Regex("^[━─\\s]+$"))) {
                    continue
                }
                
                // Process table rows with | delimiters
                if (trimmedLine.startsWith("|") && trimmedLine.endsWith("|")) {
                    val cells = trimmedLine
                        .removePrefix("|")
                        .removeSuffix("|")
                        .split("|")
                        .map { it.trim() }
                    
                    if (headers.isEmpty()) {
                        // Store headers
                        headers = cells
                    } else {
                        // Format as card
                        rowNumber++
                        result.append("━━━ $rowNumber ━━━\n")
                        cells.forEachIndexed { index, value ->
                            val label = if (index < headers.size) headers[index] else "Campo"
                            result.append("$label: $value\n")
                        }
                        result.append("\n")
                    }
                } else if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("━")) {
                    // Non-table content
                    result.append(trimmedLine)
                    result.append("\n")
                }
            }
            
            if (rowNumber > 0) {
                result.append("━━━━━━━━━━━━━━\n")
                result.append("Total: $rowNumber resultados")
            }
            
            return result.toString().trimEnd()
        }
    }
}
