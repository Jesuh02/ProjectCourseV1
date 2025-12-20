package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * Entidad que representa un tema dentro de un curso
 */
@Entity(
    tableName = "topics",
    foreignKeys = [
        ForeignKey(
            entity = Course::class,
            parentColumns = ["id"],
            childColumns = ["course_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("course_id")]
)
data class Topic(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @androidx.room.ColumnInfo(name = "course_id")
    @SerializedName("course_id")
    val courseId: Long = 0,
    val name: String = "",
    val description: String = "",
    @androidx.room.ColumnInfo(name = "order_index")
    @SerializedName("order_index")
    val orderIndex: Int = 0,
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) 