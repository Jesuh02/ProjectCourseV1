package com.example.tareamov.ui.adapter

import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
            .inflate(R.layout.item_chat_message, parent, false)
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
        private val timestampTextView: TextView = itemView.findViewById(R.id.messageTimestampTextView)
        private val calificationButtonsContainer: LinearLayout = itemView.findViewById(R.id.calificationButtonsContainer)
        private val addCalificationButton: CardView = itemView.findViewById(R.id.addCalificationButton)
        private val rejectCalificationButton: CardView = itemView.findViewById(R.id.rejectCalificationButton)

        fun bind(message: ChatMessage) {
            if (message.isFromUser) {
                // Show user message
                userMessageContainer.visibility = View.VISIBLE
                botMessageContainer.visibility = View.GONE
                userMessageTextView.text = formatBoldText(message.message)
                calificationButtonsContainer.visibility = View.GONE
            } else {
                // Show bot message
                userMessageContainer.visibility = View.GONE
                botMessageContainer.visibility = View.VISIBLE
                
                // Mostrar información de la tarea cuando hay calificación
                if (message.hasCalification && taskInfo != null) {
                    // Usar solo texto formateado - sin elementos visuales
                    val messageWithTaskInfo = buildString {
                        appendLine("📚 **TAREA PARA CALIFICAR** ⚡ *Análisis IA Completado*")
                        appendLine()
                        appendLine("**${taskInfo!!.taskName}**")
                        appendLine("📚 ${taskInfo!!.topicName}")
                        appendLine("Entregado: ${taskInfo!!.deliveryDate}")
                        appendLine()
                        appendLine("📝 **Descripción de la tarea:**")
                        appendLine(taskInfo!!.taskDescription)
                        appendLine()
                        appendLine("🔄 **CALIFICACIÓN IA**")
                        
                        val gradeValue = extractGradeFromMessage(message.message)
                        val gradePercentage = if (gradeValue != null) (gradeValue * 10).toInt() else 87
                        appendLine("📊 Calificación: $gradePercentage/100")
                        appendLine("⭐ ${getQualityLabel(gradePercentage)}")
                        appendLine()
                        appendLine("---")
                        appendLine()
                        append(message.message)
                    }
                    botMessageTextView.text = formatBoldText(messageWithTaskInfo)
                } else {
                    botMessageTextView.text = formatBoldText(message.message)
                }
                
                // Mostrar botones de calificación solo si el mensaje tiene calificación y no ha sido agregada
                if (message.hasCalification && !message.calificationAdded) {
                    calificationButtonsContainer.visibility = View.VISIBLE
                    
                    addCalificationButton.setOnClickListener {
                        onAddCalificationClick(message)
                    }
                    
                    rejectCalificationButton.setOnClickListener {
                        onRejectCalificationClick(message)
                    }
                } else {
                    calificationButtonsContainer.visibility = View.GONE
                }
            }
            
            // Set timestamp
            timestampTextView.text = timeFormat.format(Date(message.timestamp))
        }
        
        private fun formatBoldText(text: String): SpannableString {
            // Primero remover los asteriscos y crear el texto limpio
            val cleanText = text.replace(Regex("\\*\\*(.*?)\\*\\*")) { matchResult ->
                matchResult.groupValues[1]
            }
            
            val spannableString = SpannableString(cleanText)
            val regex = Regex("\\*\\*(.*?)\\*\\*")
            var offset = 0
            
            // Buscar todas las coincidencias en el texto original
            regex.findAll(text).forEach { match ->
                val originalStart = match.range.first
                val originalEnd = match.range.last + 1
                val boldText = match.groupValues[1]
                
                // Calcular la posición en el texto limpio
                val cleanStart = originalStart - offset
                val cleanEnd = cleanStart + boldText.length
                
                // Aplicar estilo negrita
                if (cleanStart >= 0 && cleanEnd <= cleanText.length) {
                    spannableString.setSpan(
                        StyleSpan(Typeface.BOLD),
                        cleanStart,
                        cleanEnd,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                
                // Actualizar el offset por los 4 asteriscos removidos
                offset += 4
            }
            
            return spannableString
        }
        
        private fun getQualityLabel(grade: Int): String {
            return when {
                grade >= 90 -> "Excelente"
                grade >= 80 -> "Muy Bueno"
                grade >= 70 -> "Bueno"
                grade >= 60 -> "Regular"
                else -> "Necesita Mejora"
            }
        }
        
        private fun extractGradeFromMessage(message: String): Float? {
            // Buscar patrones de calificación en el mensaje
            val patterns = listOf(
                Regex("calificación\\s*:?\\s*(\\d+(?:\\.\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE),
                Regex("nota\\s*:?\\s*(\\d+(?:\\.\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE),
                Regex("puntuación\\s*:?\\s*(\\d+(?:\\.\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE),
                Regex("(\\d+(?:\\.\\d+)?)/10", RegexOption.IGNORE_CASE),
                Regex("grade\\s*:?\\s*(\\d+(?:\\.\\d+)?)(?:/10)?", RegexOption.IGNORE_CASE)
            )
            
            for (pattern in patterns) {
                val match = pattern.find(message)
                if (match != null) {
                    return match.groupValues[1].toFloatOrNull()
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
