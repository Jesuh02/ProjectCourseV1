package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entidad que representa un tema dentro de un curso
 */
@Entity(
    tableName = "topics",
    foreignKeys = [
        ForeignKey(
            entity = VideoData::class,
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
    val courseId: Long = 0,
    val name: String = "",
    val description: String = "",
    val orderIndex: Int = 0
) {
    // Constructor sin argumentos para Firestore
    constructor() : this(0, 0, "", "", 0)

    // Add this secondary constructor to handle default values
    constructor(courseId: Long, name: String, description: String, orderIndex: Int) :
            this(0, courseId, name, description, orderIndex)
}