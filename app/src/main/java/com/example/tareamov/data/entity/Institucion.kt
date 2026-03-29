package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "instituciones",
    indices = [Index(value = ["nombre"], unique = true)]
)
data class Institucion(
    @PrimaryKey(autoGenerate = true)
    var id: Long = 0,
    @ColumnInfo(defaultValue = "''")
    var nombre: String = "",
    var codigo: String? = null,
    var ciudad: String? = null,
    var departamento: String? = null,
    @ColumnInfo(name = "is_active", defaultValue = "1")
    @SerializedName("is_active")
    var isActive: Boolean = true,
    @ColumnInfo(name = "created_at")
    @SerializedName("created_at")
    var createdAt: String? = null,
    /** Which tenant Supabase project this institution belongs to. Not stored in Room. */
    @Ignore
    @SerializedName("tenantId")
    val tenantId: String? = null
) {
    // Secondary constructor required by Room when @Ignore fields are present
    constructor(
        id: Long,
        nombre: String,
        codigo: String?,
        ciudad: String?,
        departamento: String?,
        isActive: Boolean,
        createdAt: String?
    ) : this(id, nombre, codigo, ciudad, departamento, isActive, createdAt, null)
}
