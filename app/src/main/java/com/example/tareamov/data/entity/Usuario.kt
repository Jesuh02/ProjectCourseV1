package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "usuarios",
    foreignKeys = [
        ForeignKey(
            entity = Persona::class,
            parentColumns = ["id"],
            childColumns = ["persona_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("persona_id"), 
        Index(value = ["username"], unique = true),
        Index(value = ["email"], unique = true)
    ]
)
data class Usuario(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "username")
    @SerializedName("username")
    val usuario: String = "", // Keeping property name 'usuario' to minimize refactoring, but mapping to 'username' column
    val contrasena: String = "",
    @ColumnInfo(name = "persona_id")
    val persona_id: Long? = null, // Nullable in DDL? DDL says "persona_id bigint". Not NOT NULL. So nullable.
    @ColumnInfo(name = "email")
    val email: String = "",
    val avatar: String? = null,
    @ColumnInfo(name = "is_active")
    @SerializedName("is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    // Alias for password field (for Google Sign-In compatibility)
    var password: String
        get() = contrasena
        set(value) { /* contrasena is val, use constructor */ }
    
    // Property to match the reference in VideoHomeFragment
    val personaId: Long?
        get() = persona_id

    // Add nombreUsuario property that returns the usuario field
    val nombreUsuario: String
        get() = usuario

    companion object {
        const val ROL_ADMIN_ID = 3L

        // Deprecated: Use Rol entity instead
        @Deprecated("Use Rol.NOMBRE_USUARIO instead")
        const val ROL_USUARIO = "usuario"
        @Deprecated("Use Rol.NOMBRE_ADMIN instead") 
        const val ROL_ADMIN = "admin"
    }
}