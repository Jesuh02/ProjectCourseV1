package com.example.tareamov.repository

import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Subscription
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

    suspend fun getSubscriptionCount(creatorId: Long): Int {
        return withContext(Dispatchers.IO) {
            subscriptionDao.getSubscriptionCountForCreator(creatorId)
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