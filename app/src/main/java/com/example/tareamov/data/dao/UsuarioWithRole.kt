package com.example.tareamov.data.dao

import androidx.room.ColumnInfo

data class UsuarioWithRole(
    val id: Long,
    val username: String,
    val contrasena: String,
    val persona_id: Long,
    val rol_id: Long,
    val email: String?,
    val avatar: String?,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "email_verified")
    val emailVerified: Boolean = false,
    @ColumnInfo(name = "last_login")
    val lastLogin: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: String? = null,
    val rolNombre: String,
    val rolNivel: Float
) {
    // Helper properties for backward compatibility
    val isAdmin: Boolean
        get() = rolNombre.equals("admin", ignoreCase = true)
    
    val isEstudiante: Boolean
        get() = rolNombre.equals("estudiante", ignoreCase = true)
        
    val rolLevel: Float
        get() = rolNivel
}
