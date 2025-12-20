package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "usuarios_roles",
    primaryKeys = ["usuario_id", "rol_id"],
    foreignKeys = [
        ForeignKey(
            entity = Usuario::class,
            parentColumns = ["id"],
            childColumns = ["usuario_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Rol::class,
            parentColumns = ["id"],
            childColumns = ["rol_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("usuario_id"),
        Index("rol_id")
    ]
)
data class UsuariosRoles(
    @ColumnInfo(name = "usuario_id")
    val usuarioId: Long,
    @ColumnInfo(name = "rol_id")
    val rolId: Long, // Changed from Int to Long to match Rol.id
    @ColumnInfo(name = "asignado_en")
    val asignadoEn: Long = System.currentTimeMillis()
)
