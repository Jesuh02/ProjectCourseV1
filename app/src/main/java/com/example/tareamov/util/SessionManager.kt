
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
        private const val KEY_LAST_ACTIVE_USER = "last_active_user" // Para detectar cambios de usuario
        private const val KEY_USER_SESSION_TIMESTAMP = "user_session_timestamp" // Timestamp de sesión

        @Volatile
        private var instance: SessionManager? = null
        
        // Listeners para cambios de usuario
        private val userChangeListeners = mutableListOf<UserChangeListener>()

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
        }
        
        /**
         * Interface para escuchar cambios de usuario
         */
        interface UserChangeListener {
            fun onUserChanged(previousUser: String?, newUser: String?)
            fun onUserLoggedOut(previousUser: String?)
        }
        
        /**
         * Añadir listener para cambios de usuario
         */
        fun addUserChangeListener(listener: UserChangeListener) {
            synchronized(userChangeListeners) {
                userChangeListeners.add(listener)
            }
        }
        
        /**
         * Remover listener para cambios de usuario
         */
        fun removeUserChangeListener(listener: UserChangeListener) {
            synchronized(userChangeListeners) {
                userChangeListeners.remove(listener)
            }
        }
    }

    /**
     * Save user login session
     */
    fun createLoginSession(username: String, userId: Long, personaId: Long = userId, roleName: String, avatarUri: String?) { 
        val previousUser = getLastActiveUser()
        
        editor.putString(KEY_USERNAME, username)
        editor.putLong(KEY_USER_ID, userId)
        editor.putLong(KEY_PERSONA_ID, personaId)
        editor.putString(KEY_USER_ROLE, roleName) // Store role name for compatibility
        editor.putString(KEY_USER_AVATAR, avatarUri) // Store avatar URI
        editor.putString(KEY_LAST_ACTIVE_USER, username) // Track last active user
        editor.putLong(KEY_USER_SESSION_TIMESTAMP, System.currentTimeMillis()) // Session timestamp
        editor.putBoolean(KEY_IS_LOGGED_IN, true)
        editor.apply()
        
        // Notificar cambio de usuario si es diferente
        if (previousUser != username) {
            notifyUserChanged(previousUser, username)
        }
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
        val previousUser = getUsername()
        editor.clear()
        editor.apply()
        
        // Notificar logout
        if (previousUser != null) {
            notifyUserLoggedOut(previousUser)
        }
    }
    
    /**
     * Get last active user
     */
    fun getLastActiveUser(): String? {
        return sharedPreferences.getString(KEY_LAST_ACTIVE_USER, null)
    }
    
    /**
     * Get user session timestamp
     */
    fun getUserSessionTimestamp(): Long {
        return sharedPreferences.getLong(KEY_USER_SESSION_TIMESTAMP, 0)
    }
    
    /**
     * Check if current user is different from last active user
     */
    fun hasUserChanged(): Boolean {
        val currentUser = getUsername()
        val lastActiveUser = getLastActiveUser()
        return currentUser != lastActiveUser
    }
    
    /**
     * Generate unique chat preference key for current user
     */
    fun getChatPreferenceKey(baseKey: String): String {
        val username = getUsername() ?: "anonymous"
        return "${baseKey}_user_${username}"
    }
    
    /**
     * Get SharedPreferences for chat persistence per user
     */
    fun getChatPreferences(context: Context): android.content.SharedPreferences {
        val username = getUsername() ?: "anonymous"
        return context.getSharedPreferences("chat_persistence_$username", Context.MODE_PRIVATE)
    }
    
    /**
     * Clear chat data for current user only
     */
    fun clearUserChatData(context: Context) {
        val chatPrefs = getChatPreferences(context)
        chatPrefs.edit().clear().apply()
    }
    
    /**
     * Notify listeners of user change
     */
    private fun notifyUserChanged(previousUser: String?, newUser: String?) {
        synchronized(userChangeListeners) {
            userChangeListeners.forEach { listener ->
                try {
                    listener.onUserChanged(previousUser, newUser)
                } catch (e: Exception) {
                    android.util.Log.e("SessionManager", "Error notifying user change", e)
                }
            }
        }
    }
    
    /**
     * Notify listeners of user logout
     */
    private fun notifyUserLoggedOut(previousUser: String) {
        synchronized(userChangeListeners) {
            userChangeListeners.forEach { listener ->
                try {
                    listener.onUserLoggedOut(previousUser)
                } catch (e: Exception) {
                    android.util.Log.e("SessionManager", "Error notifying user logout", e)
                }
            }
        }
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