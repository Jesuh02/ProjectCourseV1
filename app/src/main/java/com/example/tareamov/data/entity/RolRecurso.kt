package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "rol_recursos",
    primaryKeys = ["rolId", "recursoId"],
    foreignKeys = [
        ForeignKey(
            entity = Rol::class,
            parentColumns = ["id"],
            childColumns = ["rolId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Recurso::class,
            parentColumns = ["id"],
            childColumns = ["recursoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["rolId"]),
        Index(value = ["recursoId"])
    ]
)
data class RolRecurso(
    val rolId: Long,      // FK a tabla roles
    val recursoId: Long   // FK a tabla recursos
)
