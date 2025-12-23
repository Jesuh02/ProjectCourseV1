package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tareamov.data.entity.Subscription

@Dao
interface SubscriptionDao {
    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE subscriber_id = :subscriberId AND creator_id = :creatorId)")
    suspend fun isSubscribed(subscriberId: Long, creatorId: Long): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscription(subscription: Subscription)

    @Query("DELETE FROM subscriptions WHERE subscriber_id = :subscriberId AND creator_id = :creatorId")
    suspend fun deleteSubscription(subscriberId: Long, creatorId: Long)

    @Query("SELECT COUNT(*) FROM subscriptions WHERE creator_id = :creatorId")
    suspend fun getSubscriptionCountForCreator(creatorId: Long): Int

    @Query("SELECT COUNT(*) FROM subscriptions WHERE subscriber_id = :subscriberId")
    suspend fun getSubscriptionCountForSubscriber(subscriberId: Long): Int

    @Query("SELECT * FROM subscriptions WHERE subscriber_id = :subscriberId")
    suspend fun getSubscriptionsBySubscriber(subscriberId: Long): List<Subscription>

    @Query("SELECT * FROM subscriptions WHERE creator_id = :creatorId")
    fun getSubscribersByCreator(creatorId: Long): List<Subscription>

    // Add this method to get all subscriptions
    @Query("SELECT * FROM subscriptions")
    suspend fun getAllSubscriptions(): List<Subscription>

    // Add this method to get the total count of subscriptions
    @Query("SELECT COUNT(*) FROM subscriptions")
    suspend fun getSubscriptionCount(): Int

    // Convenience methods for subscription management
    suspend fun subscribeToCreator(subscriberId: Long, creatorId: Long) {
        insertSubscription(Subscription(
            subscriberId = subscriberId,
            creatorId = creatorId,
            subscriptionDate = System.currentTimeMillis()
        ))
    }

    suspend fun unsubscribeFromCreator(subscriberId: Long, creatorId: Long) {
        deleteSubscription(subscriberId, creatorId)
    }

    suspend fun isUserSubscribedToCreator(subscriberId: Long, creatorId: Long): Boolean {
        return isSubscribed(subscriberId, creatorId)
    }
}