package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "personas")
data class Persona(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val identificacion: String = "",
    val nombres: String = "",
    val apellidos: String = "",
    val email: String = "",
    val telefono: String = "",
    val direccion: String = "",
    val fechaNacimiento: String = "",
    val avatar: String? = null,
    val esUsuario: Boolean = false
) {
    // Constructor sin argumentos para Firestore
    constructor() : this(0, "", "", "", "", "", "", "", null, false)
}