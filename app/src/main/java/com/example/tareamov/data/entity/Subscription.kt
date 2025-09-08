package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "subscriptions",
    primaryKeys = ["subscriberUsername", "creatorUsername"],
    indices = [
        Index(value = ["subscriberUsername"]),
        Index(value = ["creatorUsername"])
    ]
)
data class Subscription(
    val subscriberUsername: String = "",
    val creatorUsername: String = "",
    val subscriptionDate: Long = System.currentTimeMillis()
) {
    // Constructor sin argumentos para Firestore
    constructor() : this("", "", System.currentTimeMillis())
}