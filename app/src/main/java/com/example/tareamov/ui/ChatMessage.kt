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
    val isGraphResponse: Boolean = false,
    val usuario_id: Long? = null,  // ID del usuario que envió el mensaje (FK a usuarios)
    val username: String? = null,   // Username del usuario (para búsquedas rápidas)
    val senderAvatar: String? = null,
    val attachedFileUrl: String? = null, // URL del archivo adjunto (si existe)
    val attachedFileName: String? = null, // Nombre del archivo adjunto
    val attachedFileType: String? = null, // Tipo de archivo (ej: "excel")
    var isPlaying: Boolean = false, // Estado de reproducción de audio (TTS)
    var isPaused: Boolean = false // Estado de pausa de audio (TTS)
) {
    /**
     * Convierte el mensaje a formato de cadena para persistencia
     */
    fun toStorageString(): String {
        return "$text:::$isUser:::$timestamp:::$isTyping:::$messageId:::$isError:::$isGraphResponse:::$usuario_id:::$username:::$attachedFileUrl:::$attachedFileName:::$attachedFileType:::$senderAvatar"
    }
    
    companion object {
        /**
         * Crea un ChatMessage desde una cadena almacenada
         */
        fun fromStorageString(storageString: String): ChatMessage? {
            return try {
                val parts = storageString.split(":::")
                when {
                    // Formato completo (v5 - con avatar)
                    parts.size >= 13 -> ChatMessage(
                        text = parts[0],
                        isUser = parts[1].toBoolean(),
                        timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
                        isTyping = parts[3].toBoolean(),
                        messageId = parts[4],
                        isError = parts[5].toBoolean(),
                        isGraphResponse = parts[6].toBoolean(),
                        usuario_id = parts[7].toLongOrNull(),
                        username = parts[8].takeIf { it != "null" },
                        attachedFileUrl = parts[9].takeIf { it != "null" && it.isNotEmpty() },
                        attachedFileName = parts[10].takeIf { it != "null" && it.isNotEmpty() },
                        attachedFileType = parts[11].takeIf { it != "null" && it.isNotEmpty() },
                        senderAvatar = parts[12].takeIf { it != "null" && it.isNotEmpty() }
                    )
                    // Formato completo (v4 - con adjuntos)
                    parts.size >= 12 -> ChatMessage(
                        text = parts[0],
                        isUser = parts[1].toBoolean(),
                        timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
                        isTyping = parts[3].toBoolean(),
                        messageId = parts[4],
                        isError = parts[5].toBoolean(),
                        isGraphResponse = parts[6].toBoolean(),
                        usuario_id = parts[7].toLongOrNull(),
                        username = parts[8].takeIf { it != "null" },
                        attachedFileUrl = parts[9].takeIf { it != "null" && it.isNotEmpty() },
                        attachedFileName = parts[10].takeIf { it != "null" && it.isNotEmpty() },
                        attachedFileType = parts[11].takeIf { it != "null" && it.isNotEmpty() }
                    )
                    // Formato completo (v3 - con usuario)
                    parts.size >= 9 -> ChatMessage(
                        text = parts[0],
                        isUser = parts[1].toBoolean(),
                        timestamp = parts[2].toLongOrNull() ?: System.currentTimeMillis(),
                        isTyping = parts[3].toBoolean(),
                        messageId = parts[4],
                        isError = parts[5].toBoolean(),
                        isGraphResponse = parts[6].toBoolean(),
                        usuario_id = parts[7].toLongOrNull(),
                        username = parts[8].takeIf { it != "null" }
                    )
                    // Formato v2 (sin usuario)
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
        fun createSystemMessage(text: String, isError: Boolean = false, isGraphResponse: Boolean = false, attachedFileUrl: String? = null, attachedFileName: String? = null, attachedFileType: String? = null, senderAvatar: String? = null): ChatMessage {
            return ChatMessage(
                text = text,
                isUser = false,
                isError = isError,
                isGraphResponse = isGraphResponse,
                attachedFileUrl = attachedFileUrl,
                attachedFileName = attachedFileName,
                attachedFileType = attachedFileType,
                senderAvatar = senderAvatar
            )
        }
        
        /**
         * Crea un mensaje de usuario
         */
        fun createUserMessage(text: String, attachedFileUrl: String? = null, attachedFileName: String? = null, attachedFileType: String? = null, senderAvatar: String? = null): ChatMessage {
            return ChatMessage(
                text = text,
                isUser = true,
                attachedFileUrl = attachedFileUrl,
                attachedFileName = attachedFileName,
                attachedFileType = attachedFileType,
                senderAvatar = senderAvatar
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
