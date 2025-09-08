package com.example.tareamov.repository

import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Subscription
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SubscriptionRepository(private val database: AppDatabase) {
    private val subscriptionDao = database.subscriptionDao()

    suspend fun toggleSubscription(subscriberUsername: String, creatorUsername: String): Boolean {
        return withContext(Dispatchers.IO) {
            val isSubscribed = subscriptionDao.isSubscribed(subscriberUsername, creatorUsername)

            if (isSubscribed) {
                // Desuscribir
                subscriptionDao.deleteSubscription(subscriberUsername, creatorUsername)
                false
            } else {
                // Suscribir
                val subscription = Subscription(
                    subscriberUsername = subscriberUsername,
                    creatorUsername = creatorUsername
                )
                subscriptionDao.insertSubscription(subscription)
                true
            }
        }
    }

    suspend fun getSubscriptionCount(creatorUsername: String): Int {
        return withContext(Dispatchers.IO) {
            subscriptionDao.getSubscriptionCountForCreator(creatorUsername)
        }
    }

    suspend fun isUserSubscribed(subscriberUsername: String, creatorUsername: String): Boolean {
        return withContext(Dispatchers.IO) {
            subscriptionDao.isSubscribed(subscriberUsername, creatorUsername)
        }
    }

    suspend fun getSubscriptionsByUser(subscriberUsername: String): List<Subscription> {
        return withContext(Dispatchers.IO) {
            subscriptionDao.getSubscriptionsBySubscriber(subscriberUsername)
        }
    }

    // Fix the method that's causing type mismatch errors
    suspend fun getSubscribersByCreator(creatorUsername: String): List<Subscription> {
        return withContext(Dispatchers.IO) {
            subscriptionDao.getSubscribersByCreator(creatorUsername)
        }
    }
}