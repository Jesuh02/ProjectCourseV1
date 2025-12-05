package com.example.tareamov.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tareamov.data.repository.SupabaseRepository
import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.data.dao.PersonaDao
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.LiveData

class SupabaseViewModel(
    private val usuarioDao: UsuarioDao,
    private val personaDao: PersonaDao
    ) : ViewModel() {
    private val repository = SupabaseRepository()

    private val _course = MutableLiveData<com.example.tareamov.data.entity.Course?>()
    val course: LiveData<com.example.tareamov.data.entity.Course?> = _course

    private val _loginResult = MutableLiveData<String?>()
    val loginResult: LiveData<String?> = _loginResult

    fun loginConUsuario(personaId: Long, password: String) {
        viewModelScope.launch {
            // 1. Buscar usuario por personaId
            val usuarioEntity = usuarioDao.getUsuarioByPersonaId(personaId)
            val email = usuarioEntity?.email

            // 2. Si se encontró el email, intenta login en Supabase
            if (usuarioEntity != null && email != null) {
                val token = repository.loginConEmail(email, password)
                _loginResult.postValue(token)
            } else {
                _loginResult.postValue(null) // Usuario o email no encontrado
            }
        }
    }

    fun fetchCourseByIdFromSupabase(courseId: Long, syncRepository: com.example.tareamov.data.sync.SyncRepository) {
        viewModelScope.launch {
            try {
                val fetched = withContext(kotlinx.coroutines.Dispatchers.IO) { syncRepository.fetchCourseById(courseId) }
                _course.postValue(fetched)
            } catch (e: Exception) {
                _course.postValue(null)
            }
        }
    }
}