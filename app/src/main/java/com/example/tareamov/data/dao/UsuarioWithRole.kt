package com.example.tareamov.data.dao

data class UsuarioWithRole(
    val id: Long,
    val usuario: String,
    val contrasena: String,
    val persona_id: Long,
    val rol_id: Long,
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
