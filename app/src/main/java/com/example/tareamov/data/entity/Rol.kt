package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "roles")
data class Rol(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val nombre: String,
    val nivel: Float,
    val default: Boolean = false
) {
    companion object {
        const val NOMBRE_USUARIO = "usuario"
        const val NOMBRE_DOCENTE = "docente"
        const val NOMBRE_ADMIN = "admin"
        const val NIVEL_USUARIO = 1.0f
        const val NIVEL_DOCENTE = 1.5f  // Nivel intermedio entre usuario y admin
        const val NIVEL_ADMIN = 2.0f
        
        // Método para crear rol usuario por defecto
        fun createUsuarioRole(): Rol {
            return Rol(
                nombre = NOMBRE_USUARIO,
                nivel = NIVEL_USUARIO,
                default = true
            )
        }
        
        // Método para crear rol docente (creadores de cursos y videos)
        fun createDocenteRole(): Rol {
            return Rol(
                nombre = NOMBRE_DOCENTE,
                nivel = NIVEL_DOCENTE,
                default = false
            )
        }
        
        // Método para crear rol admin
        fun createAdminRole(): Rol {
            return Rol(
                nombre = NOMBRE_ADMIN,
                nivel = NIVEL_ADMIN,
                default = false
            )
        }
        
        // Validación de roles permitidos - usuario, docente y admin
        fun isValidRole(roleName: String): Boolean {
            return roleName == NOMBRE_USUARIO || roleName == NOMBRE_DOCENTE || roleName == NOMBRE_ADMIN
        }
        
        // Lista de todos los roles válidos
        fun getAllValidRoles(): List<String> {
            return listOf(NOMBRE_USUARIO, NOMBRE_DOCENTE, NOMBRE_ADMIN)
        }
        
        // Verificar si un rol puede crear contenido (cursos y videos)
        fun canCreateContent(roleName: String): Boolean {
            return roleName == NOMBRE_DOCENTE || roleName == NOMBRE_ADMIN
        }
        
        // Verificar si un rol es docente o superior
        fun isDocenteOrHigher(nivel: Float): Boolean {
            return nivel >= NIVEL_DOCENTE
        }
    }
}
