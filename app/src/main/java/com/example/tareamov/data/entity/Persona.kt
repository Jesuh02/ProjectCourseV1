package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "personas",
    indices = [Index(value = ["identificacion"], unique = true)]
)
data class Persona(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val identificacion: String = "",
    val nombres: String = "",
    val apellidos: String = "",
    val telefono: String? = null,
    val direccion: String? = null,
    @ColumnInfo(name = "fecha_nacimiento")
    @SerializedName("fecha_nacimiento")
    val fechaNacimiento: String? = null,
    @ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    val createdAt: String? = null
)