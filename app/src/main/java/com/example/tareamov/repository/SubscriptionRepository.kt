package com.example.tareamov.repository

import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.service.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubscriptionRepository(private val database: AppDatabase) {
    private val subscriptionDao = database.subscriptionDao()

    suspend fun toggleSubscription(subscriberId: Long, creatorId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            val isSubscribed = subscriptionDao.isSubscribed(subscriberId, creatorId)

            if (isSubscribed) {
                // Desuscribir
                subscriptionDao.deleteSubscription(subscriberId, creatorId)
                false
            } else {
                // Suscribir
                val subscription = Subscription(
                    subscriberId = subscriberId,
                    creatorId = creatorId
                )
                subscriptionDao.insertSubscription(subscription)
                true
            }
        }
    }

    suspend fun getSubscriberCount(creatorId: Long): Int {
        return withContext(Dispatchers.IO) {
            try {
                // Fetch from Supabase only
                SupabaseClient.fetchSubscriberCount(creatorId).toInt()
            } catch (e: Exception) {
                android.util.Log.e("SubscriptionRepository", "Error fetching subscriber count from Supabase for creatorId=$creatorId", e)
                0
            }
        }
    }

    suspend fun getSubscriptionCount(subscriberId: Long): Int {
        return withContext(Dispatchers.IO) {
            try {
                // Fetch from Supabase only
                SupabaseClient.fetchSubscriptionCount(subscriberId).toInt()
            } catch (e: Exception) {
                android.util.Log.e("SubscriptionRepository", "Error fetching subscription count from Supabase for subscriberId=$subscriberId", e)
                0
            }
        }
    }

    suspend fun isUserSubscribed(subscriberId: Long, creatorId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            subscriptionDao.isSubscribed(subscriberId, creatorId)
        }
    }

    suspend fun getSubscriptionsByUser(subscriberId: Long): List<Subscription> {
        return withContext(Dispatchers.IO) {
            subscriptionDao.getSubscriptionsBySubscriber(subscriberId)
        }
    }

    // Fix the method that's causing type mismatch errors
    suspend fun getSubscribersByCreator(creatorId: Long): List<Subscription> {
        return withContext(Dispatchers.IO) {
            subscriptionDao.getSubscribersByCreator(creatorId)
        }
    }
}