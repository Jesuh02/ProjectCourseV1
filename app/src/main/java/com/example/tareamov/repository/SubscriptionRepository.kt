package com.example.tareamov.repository

import android.content.Context
import android.util.Log
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository for handling subscription operations via BackendApiService.
 */
class SubscriptionRepository(context: Context) {

    init {
        BackendApiService.initialize(context)
    }

    suspend fun toggleSubscription(subscriberId: Long, creatorId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            // Check current subscription status
            val checkResult = BackendApiService.checkSubscription(creatorId)
            val isSubscribed = when (checkResult) {
                is ApiResult.Success -> checkResult.data == true
                is ApiResult.Error -> false
            }

            if (isSubscribed) {
                // Unsubscribe
                val result = BackendApiService.unsubscribe(creatorId)
                if (result is ApiResult.Error) {
                    Log.e("SubscriptionRepository", "Error unsubscribing: ${result.message}")
                }
                false
            } else {
                // Subscribe
                val result = BackendApiService.subscribe(creatorId)
                if (result is ApiResult.Error) {
                    Log.e("SubscriptionRepository", "Error subscribing: ${result.message}")
                }
                true
            }
        }
    }

    suspend fun getSubscriberCount(creatorId: Long): Int {
        return withContext(Dispatchers.IO) {
            try {
                when (val result = BackendApiService.getSubscriberCount(creatorId)) {
                    is ApiResult.Success -> (result.data as? Number)?.toInt() ?: 0
                    is ApiResult.Error -> {
                        Log.e("SubscriptionRepository", "Error fetching subscriber count: ${result.message}")
                        0
                    }
                }
            } catch (e: Exception) {
                Log.e("SubscriptionRepository", "Error fetching subscriber count for creatorId=$creatorId", e)
                0
            }
        }
    }

    suspend fun getSubscriptionCount(subscriberId: Long): Int {
        return withContext(Dispatchers.IO) {
            try {
                when (val result = BackendApiService.getMySubscriptions()) {
                    is ApiResult.Success -> (result.data as? List<*>)?.size ?: 0
                    is ApiResult.Error -> {
                        Log.e("SubscriptionRepository", "Error fetching subscription count: ${result.message}")
                        0
                    }
                }
            } catch (e: Exception) {
                Log.e("SubscriptionRepository", "Error fetching subscription count for subscriberId=$subscriberId", e)
                0
            }
        }
    }

    suspend fun isUserSubscribed(subscriberId: Long, creatorId: Long): Boolean {
        return withContext(Dispatchers.IO) {
            when (val result = BackendApiService.checkSubscription(creatorId)) {
                is ApiResult.Success -> result.data == true
                is ApiResult.Error -> {
                    Log.e("SubscriptionRepository", "Error checking subscription: ${result.message}")
                    false
                }
            }
        }
    }

    suspend fun getSubscriptionsByUser(subscriberId: Long): List<Subscription> {
        return withContext(Dispatchers.IO) {
            when (val result = BackendApiService.getMySubscriptions()) {
                is ApiResult.Success -> result.data ?: emptyList()
                is ApiResult.Error -> {
                    Log.e("SubscriptionRepository", "Error fetching subscriptions: ${result.message}")
                    emptyList()
                }
            }
        }
    }

    /**
     * Devuelve los perfiles completos de los creadores a los que el usuario actual está suscrito.
     * Primero obtiene los IDs desde /subscriptions/my y luego resuelve los usuarios con /users/by-ids.
     */
    suspend fun getSubscribedCreatorUsers(): List<Usuario> {
        return withContext(Dispatchers.IO) {
            when (val directResult = BackendApiService.getMySubscribedCreators()) {
                is ApiResult.Success -> {
                    val users = directResult.data.orEmpty().filter { it.id > 0L }
                    if (users.isNotEmpty()) return@withContext users
                }
                is ApiResult.Error -> {
                    Log.w("SubscriptionRepository", "Direct subscribed creators fetch failed, using fallback: ${directResult.message}")
                }
            }

            val subscriptions = getSubscriptionsByUser(0)
            val creatorIds = subscriptions.map { it.creatorId }.filter { it > 0 }.distinct()
            if (creatorIds.isEmpty()) return@withContext emptyList()
            when (val result = BackendApiService.getUsersByIds(creatorIds)) {
                is ApiResult.Success -> result.data ?: emptyList()
                is ApiResult.Error -> {
                    Log.e("SubscriptionRepository", "Error fetching subscribed creator users: ${result.message}")
                    emptyList()
                }
            }
        }
    }

    suspend fun getSubscribersByCreator(creatorId: Long): List<Subscription> {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.getMySubscribers()
            when (result) {
                is ApiResult.Success -> result.data ?: emptyList()
                is ApiResult.Error -> {
                    Log.e("SubscriptionRepository", "Error fetching subscribers: ${result.message}")
                    emptyList()
                }
                else -> emptyList()
            }
        }
    }
}