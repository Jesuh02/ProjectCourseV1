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
        const val NOMBRE_ESTUDIANTE = "estudiante"
        const val NOMBRE_ADMIN = "admin"
        const val NIVEL_ESTUDIANTE = 1.0f
        const val NIVEL_ADMIN = 2.0f
        
        // Método para crear rol estudiante por defecto
        fun createEstudianteRole(): Rol {
            return Rol(
                nombre = NOMBRE_ESTUDIANTE,
                nivel = NIVEL_ESTUDIANTE,
                default = true
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
    }
}
