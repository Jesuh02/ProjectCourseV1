package com.example.tareamov.repository

import androidx.lifecycle.LiveData
import com.example.tareamov.data.dao.PersonaDao
import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.data.entity.Persona

// Update constructor to match how it's being used in PersonaViewModel
class PersonaRepository(
    private val personaDao: PersonaDao,
    private val usuarioDao: UsuarioDao
) {
    val allPersonas: LiveData<List<Persona>> = personaDao.getAllPersonas()

    // Add this method to get all personas as a list
    suspend fun getAllPersonas(): List<Persona> {
        return personaDao.getAllPersonasList()
    }

    suspend fun insert(persona: Persona): Long {
        return personaDao.insertPersona(persona)
    }

    suspend fun update(persona: Persona) {
        personaDao.updatePersona(persona)
    }

    suspend fun delete(persona: Persona) {
        personaDao.deletePersona(persona)
    }

    suspend fun getPersonaById(id: Long): Persona? {
        return personaDao.getPersonaById(id)
    }

    // Add this method to get a persona by username
    suspend fun getPersonaByUsername(username: String): Persona? {
        return personaDao.getPersonaByUsername(username)
    }

    // Add this method to your PersonaRepository class
    suspend fun searchPersonasByUsername(query: String): List<Persona> {
        return personaDao.searchPersonasByUsername(query)
    }

    // Add method to get user persona IDs
    suspend fun getUserPersonaIds(): List<Long> {
        return try {
            usuarioDao.getAllUserPersonaIds()
        } catch (e: Exception) {
            // Log the error and return an empty list if there's an issue
            e.printStackTrace()
            emptyList()
        }
    }

    // Add method to update profile information
    suspend fun updateProfile(personaId: Long, nombres: String, apellidos: String, avatar: String?): Boolean {
        val persona = getPersonaById(personaId)
        return if (persona != null) {
            val updatedPersona = persona.copy(
                nombres = nombres,
                apellidos = apellidos,
                avatar = avatar ?: persona.avatar
            )
            update(updatedPersona)
            true
        } else {
            false
        }
    }
}