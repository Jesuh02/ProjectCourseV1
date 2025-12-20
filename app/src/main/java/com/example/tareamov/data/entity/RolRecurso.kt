package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "rol_recursos",
    primaryKeys = ["rol_id", "recurso_id"],
    foreignKeys = [
        ForeignKey(
            entity = Rol::class,
            parentColumns = ["id"],
            childColumns = ["rol_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Recurso::class,
            parentColumns = ["id"],
            childColumns = ["recurso_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["rol_id"]),
        Index(value = ["recurso_id"])
    ]
)
data class RolRecurso(
    @androidx.room.ColumnInfo(name = "rol_id")
    val rolId: Long,      // FK a tabla roles
    @androidx.room.ColumnInfo(name = "recurso_id")
    val recursoId: Long   // FK a tabla recursos
)
