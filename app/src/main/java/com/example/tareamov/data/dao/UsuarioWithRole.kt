package com.example.tareamov.data.dao

import androidx.room.ColumnInfo

data class UsuarioWithRole(
    val id: Long,
    @ColumnInfo(name = "username")
    val username: String,
    val contrasena: String,
    @ColumnInfo(name = "persona_id")
    val persona_id: Long?,
    @ColumnInfo(name = "rol_id")
    val rol_id: Long,
    val email: String?,
    val avatar: String?,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
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
