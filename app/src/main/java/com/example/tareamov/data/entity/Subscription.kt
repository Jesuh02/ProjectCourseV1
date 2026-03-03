package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.Index
import com.google.gson.annotations.SerializedName

@Entity(
    tableName = "subscriptions",
    primaryKeys = ["subscriberId", "creatorId"],
    indices = [
        Index(value = ["subscriberId"]),
        Index(value = ["creatorId"])
    ]
)
data class Subscription(
    @SerializedName(value = "subscriberId", alternate = ["subscriber_id"])
    val subscriberId: Long = 0,
    @SerializedName(value = "creatorId", alternate = ["creator_id"])
    val creatorId: Long = 0,
    @SerializedName(value = "subscriptionDate", alternate = ["subscription_date", "created_at"])
    val subscriptionDate: String? = null
)