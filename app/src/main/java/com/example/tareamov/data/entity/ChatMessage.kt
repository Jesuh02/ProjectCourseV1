package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = Usuario::class,
            parentColumns = ["id"],
            childColumns = ["usuario_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [androidx.room.Index("usuario_id")]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val message: String,
    @androidx.room.ColumnInfo(name = "is_from_user")
    val isFromUser: Boolean, // Mapped to is_from_user
    val timestamp: Long = System.currentTimeMillis(), // Keep timestamp as it is in DDL
    @androidx.room.ColumnInfo(name = "session_id")
    val sessionId: String? = null, // Mapped to session_id
    @androidx.room.ColumnInfo(name = "has_calification")
    val hasCalification: Boolean = false, // Mapped to has_calification
    @androidx.room.ColumnInfo(name = "calification_value")
    val calificationValue: String? = null, // Mapped to calification_value
    @androidx.room.ColumnInfo(name = "calification_added")
    val calificationAdded: Boolean = false, // Mapped to calification_added
    
    // New fields from DDL
    @androidx.room.ColumnInfo(name = "usuario_id")
    val usuarioId: Long? = null,
    val username: String? = null,
    @androidx.room.ColumnInfo(name = "is_typing")
    val isTyping: Boolean = false,
    @androidx.room.ColumnInfo(name = "is_error")
    val isError: Boolean = false,
    @androidx.room.ColumnInfo(name = "is_graph_response")
    val isGraphResponse: Boolean = false,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(), // DDL timestamptz

    // MCP Tool metadata
    val toolName: String? = null, // Nombre de la herramienta MCP usada
    val sqlScript: String? = null, // Script SQL ejecutado (si aplica)
    val toolMetadata: String? = null // Metadata adicional en formato JSON
    ,
    // Optional sender metadata (used to show avatar/username when available)
    val senderUsername: String? = null,
    val senderAvatar: String? = null
)

//Hola Herazo...