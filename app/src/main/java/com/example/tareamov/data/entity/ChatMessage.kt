package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val message: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val sessionId: String? = null, // Optional: to group conversations
    val hasCalification: Boolean = false, // Indica si el mensaje contiene una calificación
    val calificationValue: String? = null, // El valor de la calificación extraída (ej: "90/100")
    val calificationAdded: Boolean = false, // Indica si la calificación ya fue agregada por el usuario
    
    // MCP Tool metadata
    val toolName: String? = null, // Nombre de la herramienta MCP usada
    val sqlScript: String? = null, // Script SQL ejecutado (si aplica)
    val toolMetadata: String? = null // Metadata adicional en formato JSON
    ,
    // Optional sender metadata (used to show avatar/username when available)
    val senderUsername: String? = null,
    val senderAvatar: String? = null,

    // Attached File Metadata
    val attachedFileUrl: String? = null,
    val attachedFileName: String? = null,
    val attachedFileType: String? = null
)

//Hola Herazo...