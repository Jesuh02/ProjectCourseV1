
package com.example.tareamov.util

import android.content.Context
import android.content.SharedPreferences

class SessionManager private constructor(private val context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = sharedPreferences.edit()

    companion object {
        private const val PREF_NAME = "UserSessionPref"
        private const val KEY_USERNAME = "username"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PERSONA_ID = "persona_id"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_ROLE = "user_role" // New key for user role
        private const val KEY_USER_AVATAR = "user_avatar" // Key for storing avatar URI
        private const val KEY_SUBSCRIPTIONS_PREFIX = "subscription_"

        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Save user login session
     */
    fun createLoginSession(username: String, userId: Long, personaId: Long = userId, roleName: String, avatarUri: String?) { 
        editor.putString(KEY_USERNAME, username)
        editor.putLong(KEY_USER_ID, userId)
        editor.putLong(KEY_PERSONA_ID, personaId)
        editor.putString(KEY_USER_ROLE, roleName) // Store role name for compatibility
        editor.putString(KEY_USER_AVATAR, avatarUri) // Store avatar URI
        editor.putBoolean(KEY_IS_LOGGED_IN, true)
        editor.apply()
    }

    fun isAdmin(): Boolean {
        val role = getUserRole()
        val isAdmin = role?.equals("admin", ignoreCase = true) == true
        android.util.Log.d("SessionManager", "isAdmin() called - role: '$role', isAdmin: $isAdmin")
        return isAdmin
    }
    /**
     * Get stored username
     */
    fun getUsername(): String? {
        return sharedPreferences.getString(KEY_USERNAME, null)
    }

    /**
     * Get stored user ID
     */
    fun getUserId(): Long {
        return sharedPreferences.getLong(KEY_USER_ID, -1)
    }

    /**
     * Get stored persona ID
     */
    fun getPersonaId(): Long {
        return sharedPreferences.getLong(KEY_PERSONA_ID, -1)
    }

    /**
     * Get stored user role
     */
    fun getUserRole(): String? {
        return sharedPreferences.getString(KEY_USER_ROLE, null)
    }

    /**
     * Get stored user avatar
     */
    fun getUserAvatar(): String? {
        return sharedPreferences.getString(KEY_USER_AVATAR, null)
    }

    /**
     * Check if user is logged in
     */
    fun isLoggedIn(): Boolean {
        return sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false)
    }

    /**
     * Clear session details
     */
    fun logout() {
        editor.clear()
        editor.apply()
    }

    /**
     * Subscribe to a creator
     * @param creatorUsername The username of the creator to subscribe to
     * @return true if subscription was successful, false if already subscribed
     */
    fun subscribeToCreator(creatorUsername: String): Boolean {
        val currentUsername = getUsername() ?: return false

        // Don't allow subscribing to yourself
        if (currentUsername == creatorUsername) {
            return false
        }

        // Check if already subscribed
        if (isSubscribedTo(creatorUsername)) {
            return false
        }

        // Add subscription
        val subscriptionKey = KEY_SUBSCRIPTIONS_PREFIX + creatorUsername
        editor.putBoolean(subscriptionKey, true)
        editor.apply()
        return true
    }

    /**
     * Unsubscribe from a creator
     * @param creatorUsername The username of the creator to unsubscribe from
     * @return true if unsubscription was successful, false if not subscribed
     */
    fun unsubscribeFromCreator(creatorUsername: String): Boolean {
        // Check if subscribed
        if (!isSubscribedTo(creatorUsername)) {
            return false
        }

        // Remove subscription
        val subscriptionKey = KEY_SUBSCRIPTIONS_PREFIX + creatorUsername
        editor.remove(subscriptionKey)
        editor.apply()
        return true
    }

    /**
     * Check if the current user is subscribed to a creator
     * @param creatorUsername The username of the creator
     * @return true if subscribed, false otherwise
     */
    fun isSubscribedTo(creatorUsername: String): Boolean {
        val subscriptionKey = KEY_SUBSCRIPTIONS_PREFIX + creatorUsername
        return sharedPreferences.getBoolean(subscriptionKey, false)
    }

    /**
     * Get all subscriptions for the current user
     * @return List of creator usernames the current user is subscribed to
     */
    fun getAllSubscriptions(): List<String> {
        val subscriptions = mutableListOf<String>()
        val allPrefs = sharedPreferences.all

        for ((key, value) in allPrefs) {
            if (key.startsWith(KEY_SUBSCRIPTIONS_PREFIX) && value == true) {
                val creatorUsername = key.substring(KEY_SUBSCRIPTIONS_PREFIX.length)
                subscriptions.add(creatorUsername)
            }
        }

        return subscriptions
    }
}