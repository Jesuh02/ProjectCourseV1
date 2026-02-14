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
            childColumns = ["courseId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("courseId")]
)
data class Topic(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @SerializedName("course_id")
    val courseId: Long = 0,
    @SerializedName("name")
    val name: String = "",
    val description: String = "",
    @SerializedName("order_index")
    val orderIndex: Int = 0
) 