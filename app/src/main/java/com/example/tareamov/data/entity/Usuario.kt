package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "usuarios",
    foreignKeys = [
        ForeignKey(
            entity = Persona::class,
            parentColumns = ["id"],
            childColumns = ["persona_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Rol::class,
            parentColumns = ["id"],
            childColumns = ["rol_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("persona_id"), Index("rol_id")]
)
data class Usuario(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val usuario: String = "",
    val contrasena: String = "",
    val persona_id: Long = 0,
    val rol_id: Long = 1 // Default to estudiante role (ID 1)
) {
    // Property to match the reference in VideoHomeFragment
    val personaId: Long
        get() = persona_id

    // Add nombreUsuario property that returns the usuario field
    val nombreUsuario: String
        get() = usuario

    companion object {
        // Deprecated: Use Rol entity instead
        @Deprecated("Use Rol.NOMBRE_ESTUDIANTE instead")
        const val ROL_ESTUDIANTE = "estudiante"
        @Deprecated("Use Rol.NOMBRE_ADMIN instead") 
        const val ROL_ADMIN = "admin"
    }
}