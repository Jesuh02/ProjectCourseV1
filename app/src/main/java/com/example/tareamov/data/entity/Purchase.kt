package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "purchases",
    primaryKeys = ["username", "courseId"],
    indices = [Index("courseId")]
)
data class Purchase(
    val username: String = "",
    val courseId: Long = 0,
    val purchaseDate: Long = System.currentTimeMillis(),
    val price: Double? = null
) {
    // Constructor sin argumentos para Firestore
    constructor() : this("", 0, System.currentTimeMillis(), null)
}