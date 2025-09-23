package com.example.tareamov.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.repository.PersonaRepository
import com.example.tareamov.repository.UsuarioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonaViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: PersonaRepository
    private val usuarioRepository: UsuarioRepository
    val allPersonas: LiveData<List<Persona>>

    init {
        val database = AppDatabase.getDatabase(application)
        // Update to pass both DAOs to the repository
        repository = PersonaRepository(database.personaDao(), database.usuarioDao())
        usuarioRepository = UsuarioRepository(database.usuarioDao())
        allPersonas = repository.allPersonas
    }

    // Add this method to get user persona IDs
    // Add this method to your PersonaViewModel class if it doesn't exist already

    suspend fun getUserPersonaIds(): List<Long> {
        return repository.getUserPersonaIds()
    }

    // Add this method to get a Persona by ID synchronously
    suspend fun getPersonaByIdSync(id: Long): Persona? {
        return repository.getPersonaById(id)
    }

    // Add this method to check if a username already exists
    suspend fun checkUsernameExists(username: String): Boolean {
        val database = AppDatabase.getDatabase(getApplication())
        val usuarios = database.usuarioDao().getAllUsuarios()
        return usuarios.any { it.usuario == username }
    }

    // Add this method to insert both Persona and Usuario
    fun insertPersonaWithUsuario(persona: Persona, username: String, password: String) = viewModelScope.launch(Dispatchers.IO) {
        val personaId = repository.insert(persona)
        val usuario = Usuario(
            usuario = username,
            contrasena = password, // store plain here; UsuarioRepository will hash before insert
            persona_id = personaId,
            rol_id = 1 // Default to estudiante role (ID 1)
        )
        usuarioRepository.insert(usuario)

    }

    // Add these methods to your PersonaViewModel class

    // Insert a persona and return its ID
    suspend fun insertAndGetId(persona: Persona): Long {
        return withContext(Dispatchers.IO) {
            val id = repository.insert(persona)
            id
        }
    }

    // Insert a usuario directamente
    suspend fun insertUsuario(usuario: Usuario) {
        withContext(Dispatchers.IO) {
            usuarioRepository.insert(usuario)

        }
    }

    fun update(persona: Persona) = viewModelScope.launch(Dispatchers.IO) {
        repository.update(persona)

    }

    fun delete(persona: Persona) = viewModelScope.launch(Dispatchers.IO) {
        repository.delete(persona)

    }

    // Fixed method to use repository instead of direct personaDao access
    suspend fun searchPersonasByUsername(query: String): List<Persona> {
        return withContext(Dispatchers.IO) {
            repository.searchPersonasByUsername(query)
        }
    }

    // Add this method to your PersonaViewModel if it doesn't exist
    suspend fun insert(persona: Persona): Long {
        return repository.insert(persona)
    }

    // Add these methods to the PersonaViewModel class

    // Get usuario by persona ID
    suspend fun getUsuarioByPersonaId(personaId: Long): Usuario? {
        return withContext(Dispatchers.IO) {
            usuarioRepository.getUsuarioByPersonaId(personaId)
        }
    }

    // Update usuario
    fun updateUsuario(usuario: Usuario) = viewModelScope.launch(Dispatchers.IO) {
        usuarioRepository.update(usuario)
    }

    // Add method to get persona by ID
    suspend fun getPersonaById(id: Long): Persona? {
        return withContext(Dispatchers.IO) {
            repository.getPersonaById(id)
        }
    }

    // Add method to update persona
    suspend fun updatePersona(persona: Persona) {
        withContext(Dispatchers.IO) {
            repository.update(persona)
        }
    }

    // Get all roles - updated to use Rol entity
    fun getAllRoles(): Array<String> {
        return arrayOf(
            "estudiante",
            "admin"
        )
    }


}