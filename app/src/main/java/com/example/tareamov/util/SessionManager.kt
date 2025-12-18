
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
        private const val KEY_USER_ROLES = "user_roles" // Store role IDs as a set of strings
        private const val KEY_IS_ADMIN = "is_admin" // Explicit admin flag
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
        // First check explicit admin flag
        val explicit = sharedPreferences.getBoolean(KEY_IS_ADMIN, false)
        if (explicit) {
            android.util.Log.d("SessionManager", "isAdmin() called - explicit admin flag = true")
            return true
        }

        // Then check role IDs (e.g., role id 3 is admin in the app)
        val roles = sharedPreferences.getStringSet(KEY_USER_ROLES, emptySet()) ?: emptySet()
        if (roles.contains("3")) {
            android.util.Log.d("SessionManager", "isAdmin() called - role id 3 present in roles set")
            return true
        }

        // Fallback to role name check for backward compatibility
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
     * Check whether the stored roles contain the given role id.
     */
    fun hasRole(roleId: Int): Boolean {
        val roles = sharedPreferences.getStringSet(KEY_USER_ROLES, emptySet()) ?: emptySet()
        return roles.contains(roleId.toString())
    }

    /**
     * Add a numeric role id to the stored roles set.
     */
    fun addRole(roleId: Int) {
        val existing = HashSet(sharedPreferences.getStringSet(KEY_USER_ROLES, emptySet()) ?: emptySet())
        if (existing.add(roleId.toString())) {
            editor.putStringSet(KEY_USER_ROLES, existing)
            editor.apply()
        }
    }

    /**
     * Remove a numeric role id from the stored roles set.
     */
    fun removeRole(roleId: Int) {
        val existing = HashSet(sharedPreferences.getStringSet(KEY_USER_ROLES, emptySet()) ?: emptySet())
        if (existing.remove(roleId.toString())) {
            editor.putStringSet(KEY_USER_ROLES, existing)
            editor.apply()
        }
    }

    /**
     * Explicitly set/unset admin status. This sets a boolean flag used by quick checks.
     */
    fun setAdminStatus(isAdmin: Boolean) {
        editor.putBoolean(KEY_IS_ADMIN, isAdmin)
        editor.apply()
    }

    /**
     * Refresh the stored session info from Supabase for the current username.
     * This will fetch the usuario and its role and update SharedPreferences.
     * Returns true if refresh succeeded and data was updated.
     */
    suspend fun refreshFromSupabase(): Boolean {
        val current = getUsername() ?: return false
        try {
            // Call SupabaseClient directly to avoid circular dependency on SyncRepository
            val (u, r) = com.example.tareamov.service.SupabaseClient.fetchUsuarioWithRoleByUsername(current)
            if (u == null) return false

            val roleName = r?.nombre ?: getUserRole() ?: ""
            val avatar = u.avatar

            editor.putString(KEY_USERNAME, u.usuario)
            editor.putLong(KEY_USER_ID, u.id)
            editor.putLong(KEY_PERSONA_ID, u.persona_id)
            editor.putString(KEY_USER_ROLE, roleName)
            if (avatar != null) editor.putString(KEY_USER_AVATAR, avatar)
            editor.apply()

            notifyUserChanged(getLastActiveUser(), u.usuario)
            return true
        } catch (e: Exception) {
            android.util.Log.w("SessionManager", "refreshFromSupabase failed for user=$current", e)
            return false
        }
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