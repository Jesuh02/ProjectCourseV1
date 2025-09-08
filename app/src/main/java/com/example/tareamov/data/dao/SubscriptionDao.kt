package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tareamov.data.entity.Subscription

@Dao
interface SubscriptionDao {
    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE subscriberUsername = :subscriberUsername AND creatorUsername = :creatorUsername)")
    suspend fun isSubscribed(subscriberUsername: String, creatorUsername: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: Subscription)

    @Query("DELETE FROM subscriptions WHERE subscriberUsername = :subscriberUsername AND creatorUsername = :creatorUsername")
    suspend fun deleteSubscription(subscriberUsername: String, creatorUsername: String)

    @Query("SELECT COUNT(*) FROM subscriptions WHERE creatorUsername = :creatorUsername")
    suspend fun getSubscriptionCountForCreator(creatorUsername: String): Int

    @Query("SELECT * FROM subscriptions WHERE subscriberUsername = :subscriberUsername")
    suspend fun getSubscriptionsBySubscriber(subscriberUsername: String): List<Subscription>

    @Query("SELECT * FROM subscriptions WHERE creatorUsername = :creatorUsername")
    fun getSubscribersByCreator(creatorUsername: String): List<Subscription>

    // Add this method to get all subscriptions
    @Query("SELECT * FROM subscriptions")
    suspend fun getAllSubscriptions(): List<Subscription>

    // Add this method to get the total count of subscriptions
    @Query("SELECT COUNT(*) FROM subscriptions")
    suspend fun getSubscriptionCount(): Int
}