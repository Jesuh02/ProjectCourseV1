package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "subscriptions",
    primaryKeys = ["subscriberId", "creatorId"],
    indices = [
        Index(value = ["subscriberId"]),
        Index(value = ["creatorId"])
    ]
)
data class Subscription(
    val subscriberId: Long = 0,
    val creatorId: Long = 0,
    val subscriptionDate: Long = System.currentTimeMillis()
) 