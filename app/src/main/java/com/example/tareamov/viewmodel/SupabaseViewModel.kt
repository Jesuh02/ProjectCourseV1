package com.example.tareamov.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.repository.SupabaseRepository
import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.data.dao.PersonaDao
import kotlinx.coroutines.launch
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData

class SupabaseViewModel(
    private val usuarioDao: UsuarioDao,
    private val personaDao: PersonaDao
    ) : ViewModel() {
    private val repository = SupabaseRepository()

    private val _loginResult = MutableLiveData<String?>()
    val loginResult: LiveData<String?> = _loginResult

    fun loginConUsuario(personaId: Long, password: String) {
        viewModelScope.launch {
            // 1. Buscar usuario por personaId
            val usuarioEntity = usuarioDao.getUsuarioByPersonaId(personaId)
            val persona = personaDao.getPersonaById(personaId)
            val email = persona?.email

            // 2. Si se encontró el email, intenta login en Supabase
            if (usuarioEntity != null && email != null) {
                val token = repository.loginConEmail(email, password)
                _loginResult.postValue(token)
            } else {
                _loginResult.postValue(null) // Usuario o email no encontrado
            }
        }
    }
}