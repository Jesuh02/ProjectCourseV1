package com.example.tareamov.ui

import java.util.*

/**
 * Clase de datos para representar un mensaje en el chat
 * Incluye características mejoradas para persistencia y gestión del estado
 */
data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val isTyping: Boolean = false,
    val messageId: String = UUID.randomUUID().toString(),
    val isError: Boolean = false,
    val isGraphResponse: Boolean = false
) {
    /**
     * Convierte el mensaje a formato de cadena para persistencia
     */
    fun toStorageString(): String {
        return "$text:::$isUser:::$timestamp:::$isTyping:::$messageId:::$isError:::$isGraphResponse"
    }
    
    companion object {
        /**
         * Crea un ChatMessage desde una cadena almacenada
         */
        fun fromStorageString(storageString: String): ChatMessage? {
            return try {
                val parts = storageString.split(":::")
                when {
                    // Formato completo (v2)
                    parts.size >= 7 -> ChatMessage(
                        text = parts[0],
                        isUser = parts[1].toBoolean(),
                        timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
                        isTyping = parts[3].toBoolean(),
                        messageId = parts[4],
                        isError = parts[5].toBoolean(),
                        isGraphResponse = parts[6].toBoolean()
                    )
                    // Formato legacy (v1) - para compatibilidad
                    parts.size >= 3 -> ChatMessage(
                        text = parts[0],
                        isUser = parts[1].toBoolean(),
                        timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis()
                    )
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
        
        /**
         * Crea un mensaje de sistema con formato estándar
         */
        fun createSystemMessage(text: String, isError: Boolean = false, isGraphResponse: Boolean = false): ChatMessage {
            return ChatMessage(
                text = text,
                isUser = false,
                isError = isError,
                isGraphResponse = isGraphResponse
            )
        }
        
        /**
         * Crea un mensaje de usuario
         */
        fun createUserMessage(text: String): ChatMessage {
            return ChatMessage(
                text = text,
                isUser = true
            )
        }
        
        /**
         * Crea un indicador de escritura
         */
        fun createTypingIndicator(): ChatMessage {
            return ChatMessage(
                text = "",
                isUser = false,
                isTyping = true
            )
        }
    }
    
    /**
     * Verifica si el mensaje es válido para mostrar
     */
    fun isValid(): Boolean {
        return text.isNotBlank() || isTyping
    }
    
    /**
     * Obtiene un texto formateado para mostrar
     */
    fun getDisplayText(): String {
        return when {
            isTyping -> "Sistema MCP está escribiendo..."
            isError -> "⚠️ $text"
            isGraphResponse -> "📊 $text"
            else -> text
        }
    }
}
