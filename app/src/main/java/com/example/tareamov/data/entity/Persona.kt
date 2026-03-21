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
    @ColumnInfo(name = "cedula")
    @SerializedName("cedula")
    val cedula: Long? = null,
    @SerializedName("identificacion")
    val identificacion: Long = 0,
    val nombres: String = "",
    val apellidos: String = "",
    val telefono: String? = null,
    val direccion: String? = null,
    @ColumnInfo(name = "fecha_nacimiento")
    @SerializedName("fecha_nacimiento")
    val fechaNacimiento: String? = null,
    @ColumnInfo(name = "genero")
    @SerializedName("genero")
    val genero: String? = null,
    @ColumnInfo(name = "institucion_id")
    @SerializedName("institucion_id")
    val institucionId: Long? = null,
    @ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    val createdAt: String? = null
) {
    /** Text copy of identification — from API join only, not stored in Room. */
    @androidx.room.Ignore
    @SerializedName("identificacion_original")
    var identificacionOriginal: String? = null
}