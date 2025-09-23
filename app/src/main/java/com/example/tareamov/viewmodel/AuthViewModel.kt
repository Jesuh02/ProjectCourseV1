package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.repository.UsuarioRepository
import com.example.tareamov.repository.PersonaRepository
import com.example.tareamov.repository.RolRepository
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val usuarioRepository: UsuarioRepository
    private val sessionManager: SessionManager
    private val personaRepository: PersonaRepository
    private val rolRepository: RolRepository

    private val _loginResult = MutableLiveData<LoginResult>()
    val loginResult: LiveData<LoginResult> = _loginResult

    private val _currentUserId = MutableLiveData<Long?>()
    val currentUserId: LiveData<Long?> = _currentUserId

    init {
        val database = AppDatabase.getDatabase(application)
        usuarioRepository = UsuarioRepository(database.usuarioDao())
        personaRepository = PersonaRepository(database.personaDao(), database.usuarioDao())
        rolRepository = RolRepository(database.rolDao(), database.usuarioDao())
        sessionManager = SessionManager.getInstance(application.applicationContext)

        _currentUserId.value = sessionManager.getUserId()
        
        // Initialize default roles if needed
        viewModelScope.launch {
            rolRepository.initializeDefaultRoles()
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            try {
                Log.d("AuthViewModel", "Attempting login for user: $username")
                val usuarioWithRole = withContext(Dispatchers.IO) {
                    usuarioRepository.getUsuarioWithRoleByUsername(username)
                }
                
                if (usuarioWithRole != null) {
                    Log.d("AuthViewModel", "User found in database: ${usuarioWithRole.usuario}")

                    // Verify bcrypt hashed password so user enters plain password
                    val verifyResult = try {
                        at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(password.toCharArray(), usuarioWithRole.contrasena)
                    } catch (e: Exception) {
                        // If verify throws, fall back to plain comparison for legacy or malformed values
                        Log.w("AuthViewModel", "BCrypt verify failed, falling back to plain comparison: ${e.message}")
                        null
                    }

                    val passwordMatches = when {
                        verifyResult != null -> verifyResult.verified
                        else -> usuarioWithRole.contrasena == password
                    }

                    Log.d("AuthViewModel", "Password match: $passwordMatches")

                    if (passwordMatches) {
                        // Fetch Persona to get avatar
                        val persona = withContext(Dispatchers.IO) {
                            personaRepository.getPersonaById(usuarioWithRole.persona_id)
                        }
                        val avatarUri = persona?.avatar

                        Log.d("AuthViewModel", "Password match successful. Persona ID: ${usuarioWithRole.persona_id}, Avatar URI: $avatarUri, Role: ${usuarioWithRole.rolNombre}")
                        
                        // Save session with user details, including persona_id, avatarUri and role name
                        sessionManager.createLoginSession(
                            usuarioWithRole.usuario, 
                            usuarioWithRole.id, 
                            usuarioWithRole.persona_id, 
                            usuarioWithRole.rolNombre, 
                            avatarUri
                        )

                        _loginResult.value = LoginResult(
                            success = true, 
                            userId = usuarioWithRole.id, 
                            userRole = usuarioWithRole.rolNombre
                        )
                        _currentUserId.value = usuarioWithRole.id
                    } else {
                        Log.d("AuthViewModel", "Password match failed")
                        _loginResult.value = LoginResult(success = false)
                        _currentUserId.value = null
                    }
                } else {
                    Log.d("AuthViewModel", "User not found in database")
                    _loginResult.value = LoginResult(success = false)
                    _currentUserId.value = null
                }
            } catch (e: Exception) {
                Log.e("AuthViewModel", "Login error: ${e.message}", e)
                _loginResult.value = LoginResult(success = false)
                _currentUserId.value = null
            }
        }
    }

    // Method to fetch Usuario by username (if needed elsewhere)
    suspend fun getUsuarioByUsername(username: String): Usuario? {
        return withContext(Dispatchers.IO) {
            usuarioRepository.getUsuarioByUsername(username)
        }
    }

    // Method to fetch UsuarioWithRole by username
    suspend fun getUsuarioWithRoleByUsername(username: String): com.example.tareamov.data.dao.UsuarioWithRole? {
        return withContext(Dispatchers.IO) {
            usuarioRepository.getUsuarioWithRoleByUsername(username)
        }
    }

    // Example: Add a logout function that updates currentUserId
    fun logout() {
        // Perform logout actions (e.g., clear session in SessionManager)
        sessionManager.logout() // Clear the session
        _currentUserId.value = null
        _loginResult.value = LoginResult(success = false) // Optionally update loginResult
    }
}

data class LoginResult(val success: Boolean, val userId: Long? = null, val userRole: String? = null)