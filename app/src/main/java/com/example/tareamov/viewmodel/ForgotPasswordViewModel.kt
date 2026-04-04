package com.example.tareamov.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.launch

class ForgotPasswordViewModel(application: Application) : AndroidViewModel(application) {

    // Current step: 1 = email, 2 = code, 3 = new password, 4 = success
    private val _step = MutableLiveData(1)
    val step: LiveData<Int> = _step

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _errorMsg = MutableLiveData<String?>()
    val errorMsg: LiveData<String?> = _errorMsg

    // Holds the email across steps
    var confirmedEmail: String = ""
        private set

    // Holds the verified code across steps (to submit in resetPassword)
    var confirmedCode: String = ""
        private set

    /** Step 1 → sends the 4-digit code to the email. Always moves to step 2. */
    fun sendCode(email: String) {
        if (email.isBlank()) { _errorMsg.value = "Ingresa tu correo electrónico"; return }
        viewModelScope.launch {
            _loading.value = true
            _errorMsg.value = null
            BackendApiService.initialize(getApplication())
            // Always advance to step 2 regardless of existence (security: don't leak email existence)
            BackendApiService.forgotPassword(email.trim())
            confirmedEmail = email.trim()
            _loading.value = false
            _step.value = 2
        }
    }

    /** Step 2 → verifies the 4-digit code. Advances to step 3 on success. */
    fun verifyCode(code: String) {
        if (code.length != 4) { _errorMsg.value = "El código debe tener 4 dígitos"; return }
        viewModelScope.launch {
            _loading.value = true
            _errorMsg.value = null
            val result = BackendApiService.verifyResetCode(confirmedEmail, code)
            _loading.value = false
            when (result) {
                is ApiResult.Success -> {
                    val valid = result.data?.get("valid")?.asBoolean ?: false
                    if (valid) {
                        confirmedCode = code
                        _step.value = 3
                    } else {
                        _errorMsg.value = "Código incorrecto o expirado. Verifica e intenta de nuevo."
                    }
                }
                is ApiResult.Error -> _errorMsg.value = "Error al verificar el código. Intenta de nuevo."
            }
        }
    }

    /** Step 3 → resets the password. Advances to step 4 on success. */
    fun resetPassword(newPassword: String, confirmPassword: String) {
        if (newPassword.length < 6)          { _errorMsg.value = "La contraseña debe tener al menos 6 caracteres"; return }
        if (!newPassword.any { it.isDigit() }) { _errorMsg.value = "Debe incluir al menos 1 número"; return }
        if (!newPassword.any { it.isUpperCase() }) { _errorMsg.value = "Debe incluir al menos 1 letra mayúscula"; return }
        if (!newPassword.any { it.isLowerCase() }) { _errorMsg.value = "Debe incluir al menos 1 letra minúscula"; return }
        if (!newPassword.any { !it.isLetterOrDigit() }) { _errorMsg.value = "Debe incluir al menos 1 símbolo (ej. !@#\$%)"; return }
        if (newPassword != confirmPassword)  { _errorMsg.value = "Las contraseñas no coinciden"; return }

        viewModelScope.launch {
            _loading.value = true
            _errorMsg.value = null
            val result = BackendApiService.resetPassword(confirmedEmail, confirmedCode, newPassword)
            _loading.value = false
            when (result) {
                is ApiResult.Success -> _step.value = 4
                is ApiResult.Error   -> _errorMsg.value = result.message ?: "Error al cambiar la contraseña."
            }
        }
    }

    fun clearError() { _errorMsg.value = null }
    fun goBack() { if ((_step.value ?: 1) > 1) _step.value = (_step.value ?: 2) - 1; _errorMsg.value = null }
}
