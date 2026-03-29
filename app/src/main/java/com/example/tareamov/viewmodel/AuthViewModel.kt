package com.example.tareamov.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.config.TenantConfig
import com.example.tareamov.config.TenantManager
import com.example.tareamov.config.TenantResolver
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.util.AppCache
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

    /** Emitted when the user exists on multiple tenants; the UI must show a picker. */
    private val _pendingTenantSelection = MutableLiveData<List<TenantResolver.ResolvedLogin>?>()
    val pendingTenantSelection: LiveData<List<TenantResolver.ResolvedLogin>?> = _pendingTenantSelection

    /** Cached credentials while waiting for tenant selection. */
    private var pendingUsername: String? = null
    private var pendingPassword: String? = null

    init {
        // Initialize BackendApiService
        BackendApiService.initialize(application.applicationContext)
        sessionManager = SessionManager.getInstance(application.applicationContext)
        _currentUserId.value = sessionManager.getUserId()
    }

    fun login(username: String, password: String, cedula: String? = null) {
        viewModelScope.launch {
            try {
                Log.d("AuthViewModel", "Attempting login for user: $username via TenantResolver")

                val probeResult = withContext(Dispatchers.IO) {
                    TenantResolver.probeLogin(
                        getApplication<Application>().applicationContext,
                        username,
                        password,
                        cedula
                    )
                }

                when (probeResult) {
                    is TenantResolver.ResolveResult.Multiple -> {
                        Log.d("AuthViewModel", "User found on ${probeResult.matches.size} tenants")
                        pendingUsername = username
                        pendingPassword = password
                        _pendingTenantSelection.value = probeResult.matches
                    }
                    is TenantResolver.ResolveResult.Single -> {
                        completeLogin(probeResult.resolved, username, password)
                    }
                    is TenantResolver.ResolveResult.None -> {
                        _loginResult.value = LoginResult(success = false, errorMessage = "Credenciales inválidas")
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

    /** Called after the user picks a tenant from the selection dialog. */
    fun commitLogin(resolved: TenantResolver.ResolvedLogin) {
        val username = pendingUsername ?: return
        val password = pendingPassword ?: return
        _pendingTenantSelection.value = null
        viewModelScope.launch {
            try {
                completeLogin(resolved, username, password)
            } catch (e: Exception) {
                Log.e("AuthViewModel", "commitLogin error: ${e.message}", e)
                _loginResult.value = LoginResult(success = false)
            } finally {
                pendingUsername = null
                pendingPassword = null
            }
        }
    }

    fun dismissTenantSelection() {
        _pendingTenantSelection.value = null
        pendingUsername = null
        pendingPassword = null
    }

    private suspend fun completeLogin(
        resolved: TenantResolver.ResolvedLogin,
        username: String,
        password: String
    ) {
        val context = getApplication<Application>().applicationContext
        val result = withContext(Dispatchers.IO) {
            TenantResolver.commitAndLogin(context, resolved, username, password)
        }

        when (result) {
            is ApiResult.Success -> handleSuccessfulLogin(result, username)
            is ApiResult.Error -> {
                Log.d("AuthViewModel", "Login failed: ${result.message} (code=${result.code})")
                _loginResult.value = LoginResult(success = false, errorMessage = result.message)
                _currentUserId.value = null
            }
        }
    }

    private suspend fun handleSuccessfulLogin(
        result: ApiResult.Success<BackendApiService.AuthResponse>,
        username: String
    ) {
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

            AppCache.clearAll()

            sessionManager.createLoginSession(
                username,
                userId,
                personaId,
                roleName,
                avatarUri
            )

            val roleId = user.get("rol_id")?.asInt ?: 1
            if (roleName.equals("admin", ignoreCase = true) || roleId == 3) {
                sessionManager.addRole(3)
            }

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
                    sessionManager.setAdminStatus(allRoleIds.contains(3L))
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
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                BackendApiService.logoutAndUnregisterFCM()
            }
            AppCache.clearAll()
            sessionManager.logout()
            _currentUserId.value = null
            _loginResult.value = LoginResult(success = false)
        }
    }
}

data class LoginResult(val success: Boolean, val userId: Long? = null, val userRole: String? = null, val errorMessage: String? = null)