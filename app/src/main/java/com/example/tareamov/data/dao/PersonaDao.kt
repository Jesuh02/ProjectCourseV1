package com.example.tareamov.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.tareamov.data.entity.Persona

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY nombres ASC")
    fun getAllPersonas(): LiveData<List<Persona>>

    @Query("SELECT * FROM personas ORDER BY nombres ASC")
    suspend fun getAllPersonasList(): List<Persona>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPersona(persona: Persona): Long {
        val id = insertPersonaInternal(persona)
        // Removed: com.example.tareamov.data.AppDatabase.getDatabase(persona.context).notifyDatabaseChanged()
        return id
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPersonaInternal(persona: Persona): Long

    @Update
    suspend fun updatePersona(persona: Persona) {
        updatePersonaInternal(persona)
        // Removed: com.example.tareamov.data.AppDatabase.getDatabase(persona.context).notifyDatabaseChanged()
    }
    @Update
    suspend fun updatePersonaInternal(persona: Persona)

    @Delete
    suspend fun deletePersona(persona: Persona) {
        deletePersonaInternal(persona)
        // Removed: com.example.tareamov.data.AppDatabase.getDatabase(persona.context).notifyDatabaseChanged()
    }
    @Delete
    suspend fun deletePersonaInternal(persona: Persona)

    @Query("SELECT * FROM personas WHERE id = :id LIMIT 1")
    suspend fun getPersonaById(id: Long): Persona?

    // Add this query to your PersonaDao interface
    @Query("""
        SELECT p.* FROM personas p
        INNER JOIN usuarios u ON p.id = u.persona_id
        WHERE LOWER(u.usuario) LIKE '%' || LOWER(:query) || '%'
    """)
    suspend fun searchPersonasByUsername(query: String): List<Persona>

    // Add this method to get a persona by username
    // Add this method if it doesn't exist already
    @Query("""
        SELECT p.* FROM personas p
        INNER JOIN usuarios u ON p.id = u.persona_id
        WHERE u.usuario = :username LIMIT 1
    """)
    suspend fun getPersonaByUsername(username: String): Persona?

    // Add method to get total persona count
    @Query("SELECT COUNT(*) FROM personas")
    suspend fun getPersonaCount(): Int
}