package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "instituciones",
    indices = [Index(value = ["nombre"], unique = true)]
)
data class Institucion(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(defaultValue = "''")
    val nombre: String = "",
    val codigo: String? = null,
    val ciudad: String? = null,
    val departamento: String? = null,
    @ColumnInfo(name = "is_active", defaultValue = "1")
    @SerializedName("is_active")
    val isActive: Boolean = true,
    @ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    val createdAt: String? = null
)
