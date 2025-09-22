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

                    fun maskSecret(s: String?): String {
                        if (s.isNullOrEmpty()) return "<empty>"
                        if (s.length <= 2) return "*".repeat(s.length)
                        return s.first() + "*".repeat(s.length - 2) + s.last()
                    }

                    val storedMaskLocal = maskSecret(usuarioWithRole.contrasena)
                    Log.d("AuthViewModel", "Stored password mask (local): $storedMaskLocal")

                    // Verify bcrypt hashed password so user enters plain password
                    val verifyResult = try {
                        at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(password.toCharArray(), usuarioWithRole.contrasena)
                    } catch (e: Exception) {
                        // If verify throws, fall back to plain comparison for legacy or malformed values
                        Log.w("AuthViewModel", "BCrypt verify failed (local), will try plain comparison: ${e.message}")
                        null
                    }

                    val passwordMatches = if (verifyResult?.verified == true) {
                        true
                    } else {
                        // Even if verifyResult exists but is not verified, still allow plaintext fallback
                        val plainMatch = usuarioWithRole.contrasena == password
                        Log.d("AuthViewModel", "BCrypt verified: ${verifyResult?.verified}, fallback plainMatch: $plainMatch")
                        plainMatch
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
                    Log.d("AuthViewModel", "User not found in local database, trying Supabase if configured")

                    try {
                        val supabaseClient = com.example.tareamov.service.SupabaseClient
                        if (supabaseClient.isConfigured()) {
                                val remoteUsuario = withContext(Dispatchers.IO) {
                                    try {
                                        supabaseClient.fetchUsuarioByUsername(username)
                                    } catch (e: Exception) {
                                        null
                                    }
                                }

                            if (remoteUsuario != null) {
                                fun maskSecret(s: String?): String {
                                    if (s.isNullOrEmpty()) return "<empty>"
                                    if (s.length <= 2) return "*".repeat(s.length)
                                    return s.first() + "*".repeat(s.length - 2) + s.last()
                                }

                                val storedMaskRemote = maskSecret(remoteUsuario.contrasena)
                                Log.d("AuthViewModel", "Stored password mask (remote): $storedMaskRemote")

                                val verifyResult = try {
                                    at.favre.lib.crypto.bcrypt.BCrypt.verifyer().verify(password.toCharArray(), remoteUsuario.contrasena)
                                } catch (e: Exception) {
                                    Log.w("AuthViewModel", "BCrypt verify (remote) failed, will try plain comparison: ${e.message}")
                                    null
                                }

                                val passwordMatches = if (verifyResult?.verified == true) {
                                    true
                                } else {
                                    val plainMatch = remoteUsuario.contrasena == password
                                    Log.d("AuthViewModel", "Remote BCrypt verified: ${verifyResult?.verified}, fallback plainMatch: $plainMatch")
                                    plainMatch
                                }

                                if (passwordMatches) {
                                    val persona = withContext(Dispatchers.IO) {
                                        try {
                                            supabaseClient.fetchPersonas().firstOrNull { p -> p.id == remoteUsuario.persona_id }
                                        } catch (e: Exception) { null }
                                    }
                                    val avatarUri = persona?.avatar
                                    val roleName = withContext(Dispatchers.IO) {
                                        try {
                                            supabaseClient.fetchRoles().firstOrNull { r -> r.id == remoteUsuario.rol_id }?.nombre ?: ""
                                        } catch (e: Exception) { "" }
                                    }

                                    sessionManager.createLoginSession(
                                        remoteUsuario.usuario,
                                        remoteUsuario.id,
                                        remoteUsuario.persona_id,
                                        roleName,
                                        avatarUri
                                    )

                                    _loginResult.value = LoginResult(success = true, userId = remoteUsuario.id, userRole = roleName)
                                    _currentUserId.value = remoteUsuario.id
                                } else {
                                    Log.d("AuthViewModel", "Remote password match failed")
                                    _loginResult.value = LoginResult(success = false)
                                    _currentUserId.value = null
                                }
                            } else {
                                Log.d("AuthViewModel", "User not found on Supabase")
                                _loginResult.value = LoginResult(success = false)
                                _currentUserId.value = null
                            }
                        } else {
                            Log.d("AuthViewModel", "Supabase not configured")
                            _loginResult.value = LoginResult(success = false)
                            _currentUserId.value = null
                        }
                    } catch (e: Exception) {
                        Log.e("AuthViewModel", "Error while trying Supabase auth: ${e.message}", e)
                        _loginResult.value = LoginResult(success = false)
                        _currentUserId.value = null
                    }
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