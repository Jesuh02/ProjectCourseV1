package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthViewModel(application: Application) : AndroidViewModel(application) {
    private val sessionManager: SessionManager

    private val _loginResult = MutableLiveData<LoginResult>()
    val loginResult: LiveData<LoginResult> = _loginResult

    private val _currentUserId = MutableLiveData<Long?>()
    val currentUserId: LiveData<Long?> = _currentUserId

    init {
        // Initialize BackendApiService
        BackendApiService.initialize(application.applicationContext)
        sessionManager = SessionManager.getInstance(application.applicationContext)
        _currentUserId.value = sessionManager.getUserId()
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            try {
                Log.d("AuthViewModel", "Attempting login for user: $username via BackendApiService")

                val result = withContext(Dispatchers.IO) {
                    BackendApiService.login(username, password)
                }

                when (result) {
                    is ApiResult.Success -> {
                        val authResponse = result.data
                        if (authResponse?.effectiveToken() != null && authResponse.user != null) {
                            val user = authResponse.user
                            val userId = user.get("id")?.asLong ?: -1L
                            val personaId = user.get("persona_id")?.asLong ?: -1L
                            val avatarUri = user.get("avatar")?.let {
                                if (it.isJsonNull) null else it.asString
                            }
                            val roleName = user.get("rolNombre")?.let {
                                if (it.isJsonNull) "" else it.asString
                            } ?: ""

                            Log.d("AuthViewModel", "Login successful. UserId=$userId, PersonaId=$personaId, Role=$roleName")

                            // Save session with user details
                            sessionManager.createLoginSession(
                                username,
                                userId,
                                personaId,
                                roleName,
                                avatarUri
                            )

                            // Add legacy role ID from user object
                            val roleId = user.get("rol_id")?.asInt ?: 1
                            // Do NOT add the legacy rol_id from the usuarios table to the role set.
                            // Roles are populated exclusively from the usuarios_roles table via getUserRoles().
                            // Only set the admin shortcut here when the rolNombre field indicates admin.
                            if (roleName.equals("admin", ignoreCase = true) || roleId == 3) {
                                sessionManager.addRole(3)
                            }

                            // Fetch ALL roles from backend
                            try {
                                val rolesResult = withContext(Dispatchers.IO) {
                                    BackendApiService.getUserRoles(userId)
                                }
                                if (rolesResult is ApiResult.Success) {
                                    val allRoleIds = rolesResult.data ?: emptyList()
                                    Log.d("AuthViewModel", "All roles from backend for userId=$userId: $allRoleIds")
                                    for (rid in allRoleIds) {
                                        sessionManager.addRole(rid.toInt())
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("AuthViewModel", "Could not fetch roles from backend: ${e.message}")
                            }

                            _loginResult.value = LoginResult(
                                success = true,
                                userId = userId,
                                userRole = roleName
                            )
                            _currentUserId.value = userId
                        } else {
                            Log.d("AuthViewModel", "Login response missing token or user data")
                            _loginResult.value = LoginResult(success = false)
                            _currentUserId.value = null
                        }
                    }
                    is ApiResult.Error -> {
                        Log.d("AuthViewModel", "Login failed: ${result.message} (code=${result.code})")
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

    // Method to fetch Usuario by username from backend
    suspend fun getUsuarioByUsername(username: String): Usuario? {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.getUserByUsername(username)
            result.getOrNull()
        }
    }

    // Method to fetch UsuarioWithRole by username from backend
    suspend fun getUsuarioWithRoleByUsername(username: String): com.example.tareamov.data.dao.UsuarioWithRole? {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.getUserByUsername(username)
            val user = result.getOrNull() ?: return@withContext null
            val rolesResult = BackendApiService.getUserRoles(user.id)
            val roleIds = (rolesResult as? ApiResult.Success)?.data ?: emptyList()
            val roleName = if (roleIds.contains(3L)) "admin"
                          else if (roleIds.contains(2L)) "docente"
                          else "estudiante"
            val rolNivel = if (roleIds.contains(3L)) 3.0f
                           else if (roleIds.contains(2L)) 2.0f
                           else 1.0f
            com.example.tareamov.data.dao.UsuarioWithRole(
                id = user.id,
                username = user.usuario,
                contrasena = user.contrasena,
                persona_id = user.persona_id,
                rol_id = user.rol_id,
                email = user.email,
                avatar = user.avatar,
                rolNombre = roleName,
                rolNivel = rolNivel
            )
        }
    }

    fun logout() {
        sessionManager.logout()
        BackendApiService.logout()
        _currentUserId.value = null
        _loginResult.value = LoginResult(success = false)
    }
}

data class LoginResult(val success: Boolean, val userId: Long? = null, val userRole: String? = null)