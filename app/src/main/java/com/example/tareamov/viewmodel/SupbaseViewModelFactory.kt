package com.example.tareamov.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.data.dao.PersonaDao

class SupabaseViewModelFactory(
    private val usuarioDao: UsuarioDao,
    private val personaDao: PersonaDao
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SupabaseViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SupabaseViewModel(usuarioDao, personaDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
