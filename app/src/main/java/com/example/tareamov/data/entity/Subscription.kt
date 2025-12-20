package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "subscriptions",
    primaryKeys = ["subscriber_id", "creator_id"],
    indices = [
        Index(value = ["subscriber_id"]),
        Index(value = ["creator_id"])
    ],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = Usuario::class,
            parentColumns = ["id"],
            childColumns = ["subscriber_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        ),
        androidx.room.ForeignKey(
            entity = Usuario::class,
            parentColumns = ["id"],
            childColumns = ["creator_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ]
)
data class Subscription(
    @androidx.room.ColumnInfo(name = "subscriber_id")
    val subscriberId: Long = 0,
    @androidx.room.ColumnInfo(name = "creator_id")
    val creatorId: Long = 0,
    @androidx.room.ColumnInfo(name = "subscription_date")
    val subscriptionDate: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) 