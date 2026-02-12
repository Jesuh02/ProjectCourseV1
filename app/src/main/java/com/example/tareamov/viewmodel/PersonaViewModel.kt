package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PersonaViewModel(application: Application) : AndroidViewModel(application) {

    private val _allPersonas = MutableLiveData<List<Persona>>(emptyList())
    val allPersonas: LiveData<List<Persona>> = _allPersonas

    init {
        BackendApiService.initialize(application.applicationContext)
        refreshPersonas()
    }

    private fun refreshPersonas() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = BackendApiService.getPersonas()
                if (result is ApiResult.Success) {
                    _allPersonas.postValue(result.data ?: emptyList())
                }
            } catch (e: Exception) {
                Log.e("PersonaViewModel", "Error loading personas: ${e.message}")
            }
        }
    }

    suspend fun getUserPersonaIds(): List<Long> {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.getPersonas()
            (result.getOrNull() ?: emptyList()).map { it.id }
        }
    }

    suspend fun getPersonaByIdSync(id: Long): Persona? {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.getPersonaById(id)
            result.getOrNull()
        }
    }

    suspend fun checkUsernameExists(username: String): Boolean {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.getUserByUsername(username)
            result.isSuccess && result.getOrNull() != null
        }
    }

    fun insertPersonaWithUsuario(persona: Persona, username: String, password: String) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val personaResult = BackendApiService.createPersona(persona)
            if (personaResult is ApiResult.Success && personaResult.data != null) {
                val personaId = personaResult.data.id
                val registerResult = BackendApiService.register(
                    username = username,
                    password = password,
                    email = "${username}@app.local",
                    personaId = personaId
                )
                if (registerResult is ApiResult.Error) {
                    Log.e("PersonaViewModel", "Error registering user: ${registerResult.message}")
                }
            } else {
                Log.e("PersonaViewModel", "Error creating persona: ${(personaResult as? ApiResult.Error)?.message}")
            }
            refreshPersonas()
        } catch (e: Exception) {
            Log.e("PersonaViewModel", "Error in insertPersonaWithUsuario: ${e.message}")
        }
    }

    suspend fun insertAndGetId(persona: Persona): Long {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.createPersona(persona)
            result.getOrNull()?.id ?: -1L
        }
    }

    suspend fun insertUsuario(usuario: Usuario) {
        withContext(Dispatchers.IO) {
            BackendApiService.register(
                username = usuario.usuario,
                password = usuario.contrasena,
                email = "${usuario.usuario}@app.local",
                personaId = usuario.persona_id
            )
        }
    }

    fun update(persona: Persona) = viewModelScope.launch(Dispatchers.IO) {
        try {
            BackendApiService.updatePersona(persona.id, mapOf(
                "nombres" to persona.nombres,
                "apellidos" to persona.apellidos,
                "identificacion" to persona.identificacion,
                "telefono" to persona.telefono
            ))
            refreshPersonas()
        } catch (e: Exception) {
            Log.e("PersonaViewModel", "Error updating persona: ${e.message}")
        }
    }

    fun delete(persona: Persona) = viewModelScope.launch(Dispatchers.IO) {
        try {
            BackendApiService.deletePersona(persona.id)
            refreshPersonas()
        } catch (e: Exception) {
            Log.e("PersonaViewModel", "Error deleting persona: ${e.message}")
        }
    }

    suspend fun searchPersonasByUsername(query: String): List<Persona> {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.searchUsers(query)
            if (result is ApiResult.Success) {
                val users = result.data ?: emptyList()
                users.mapNotNull { user ->
                    try {
                        val personaResult = BackendApiService.getPersonaById(user.persona_id)
                        personaResult.getOrNull()
                    } catch (_: Exception) { null }
                }
            } else emptyList()
        }
    }

    suspend fun insert(persona: Persona): Long {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.createPersona(persona)
            result.getOrNull()?.id ?: -1L
        }
    }

    suspend fun getUsuarioByPersonaId(personaId: Long): Usuario? {
        return withContext(Dispatchers.IO) {
            try {
                val personaResult = BackendApiService.getPersonaById(personaId)
                if (personaResult is ApiResult.Success) {
                    // Try to find user linked to this persona via search
                    val usersResult = BackendApiService.getPersonas()
                    null // Backend handles user-persona mapping server-side
                } else null
            } catch (_: Exception) { null }
        }
    }

    fun updateUsuario(usuario: Usuario) = viewModelScope.launch(Dispatchers.IO) {
        try {
            BackendApiService.updateMyProfile(mapOf(
                "usuario" to usuario.usuario,
                "avatar" to usuario.avatar
            ))
        } catch (e: Exception) {
            Log.e("PersonaViewModel", "Error updating usuario: ${e.message}")
        }
    }

    suspend fun getPersonaById(id: Long): Persona? {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.getPersonaById(id)
            result.getOrNull()
        }
    }

    suspend fun updatePersona(persona: Persona) {
        withContext(Dispatchers.IO) {
            BackendApiService.updatePersona(persona.id, mapOf(
                "nombres" to persona.nombres,
                "apellidos" to persona.apellidos,
                "identificacion" to persona.identificacion,
                "telefono" to persona.telefono
            ))
            refreshPersonas()
        }
    }

    fun getAllRoles(): Array<String> {
        return arrayOf(
            "estudiante",
            "admin"
        )
    }
}
