package com.example.tareamov.data.dao

import androidx.room.*
import com.example.tareamov.data.entity.ChatMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatMessageDao {
    
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessage>>
    
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: String): Flow<List<ChatMessage>>
    
    @Insert
    suspend fun insertMessage(message: ChatMessage): Long
    
    @Update
    suspend fun updateMessage(message: ChatMessage)
    
    @Delete
    suspend fun deleteMessage(message: ChatMessage)
    
    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()
    
    @Query("DELETE FROM chat_messages WHERE session_id = :sessionId")
    suspend fun clearSessionMessages(sessionId: String)
    
    @Query("SELECT COUNT(*) FROM chat_messages")
    suspend fun getMessageCount(): Int

    // Último mensaje del usuario antes de un timestamp (para resolver referencias #)
    @Query("SELECT * FROM chat_messages WHERE is_from_user = 1 AND timestamp <= :beforeTs ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastUserMessageBefore(beforeTs: Long): ChatMessage?
    
    // Obtener mensajes con calificaciones
    @Query("SELECT * FROM chat_messages WHERE has_calification = 1 AND calification_value IS NOT NULL ORDER BY timestamp DESC")
    suspend fun getMessagesWithCalifications(): List<ChatMessage>
    
    // Obtener mensajes recientes de una sesión (para buscar referencias #)
    @Query("SELECT * FROM chat_messages WHERE session_id = :sessionId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(sessionId: String, limit: Int): List<ChatMessage>
}
