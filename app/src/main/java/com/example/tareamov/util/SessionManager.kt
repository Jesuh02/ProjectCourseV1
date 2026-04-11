
package com.example.tareamov.util

import android.content.Context
import android.content.SharedPreferences

/**
 * SessionManager - singleton for storing simple user session info in SharedPreferences.
 * This implementation centralizes role handling and keeps backward compatibility
 * with legacy keys. It exposes helper methods used across the app.
 */
class SessionManager private constructor(private val context: Context) {
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = sharedPreferences.edit()

    private fun clearStoredRoles(targetEditor: SharedPreferences.Editor = editor) {
        targetEditor.remove(KEY_USER_ROLE_IDS)
        targetEditor.remove(KEY_USER_ROLES)
        targetEditor.remove(KEY_USER_ROLE)
        targetEditor.putBoolean(KEY_IS_ADMIN, false)
    }

    private fun inferLegacyRoleName(roleIds: Collection<Int>): String? {
        return when {
            roleIds.contains(3) -> "admin"
            roleIds.contains(2) -> "docente"
            roleIds.contains(1) -> "user"
            else -> null
        }
    }

    // Listener interface for observing user/session changes
    interface UserChangeListener {
        fun onUserChanged(previousUser: String?, newUser: String?)
        fun onUserLoggedOut(previousUser: String?)
    }

    companion object {
        private const val PREF_NAME = "UserSessionPref"
        private const val KEY_USERNAME = "username"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_PERSONA_ID = "persona_id"
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_ROLE = "user_role" // legacy single-role string (eg "admin")
        private const val KEY_USER_AVATAR = "user_avatar"
        private const val KEY_SUBSCRIPTIONS_PREFIX = "subscription_"
        private const val KEY_LAST_ACTIVE_USER = "last_active_user"
        private const val KEY_USER_SESSION_TIMESTAMP = "user_session_timestamp"

        // Newer keys / canonical keys
        private const val KEY_USER_ROLE_IDS = "user_role_ids" // set of role id strings (backwards-compatible)
        private const val KEY_USER_ROLES = "user_roles" // alias set name used in some code paths
        private const val KEY_IS_ADMIN = "is_admin" // explicit boolean quick-check

        @Volatile
        private var instance: SessionManager? = null

        // Listeners for user changes
        private val userChangeListeners = mutableListOf<UserChangeListener>()

        fun getInstance(context: Context): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager(context.applicationContext).also { instance = it }
            }
        }

        fun addUserChangeListener(listener: UserChangeListener) {
            synchronized(userChangeListeners) {
                userChangeListeners.add(listener)
            }
        }

        fun removeUserChangeListener(listener: UserChangeListener) {
            synchronized(userChangeListeners) {
                userChangeListeners.remove(listener)
            }
        }
    }

    /**
     * Create a login session. roleName is legacy and kept for compatibility.
     */
    fun createLoginSession(username: String, userId: Long, personaId: Long = userId, roleName: String = "", avatarUri: String? = null) {
        val previousUser = getLastActiveUser()
        val previousUserId = getUserId()

        // If switching to a different user, clear ALL old session data first
        // to prevent stale roles/admin flags from leaking into the new session
        if (previousUser != null && previousUser != username || previousUserId != -1L && previousUserId != userId) {
            editor.clear()
            editor.apply()
        }

        clearStoredRoles()
        editor.putString(KEY_USERNAME, username)
        editor.putLong(KEY_USER_ID, userId)
        editor.putLong(KEY_PERSONA_ID, personaId)
        if (roleName.isNotBlank()) {
            editor.putString(KEY_USER_ROLE, roleName)
        }
        if (avatarUri != null) editor.putString(KEY_USER_AVATAR, avatarUri)
        editor.putString(KEY_LAST_ACTIVE_USER, username)
        editor.putLong(KEY_USER_SESSION_TIMESTAMP, System.currentTimeMillis())
        editor.putBoolean(KEY_IS_LOGGED_IN, true)
        editor.apply()

        if (previousUser != username) {
            notifyUserChanged(previousUser, username)
        }
    }

    fun isDocente(): Boolean = hasRole(2)

    fun isAdminOrDocente(): Boolean = isAdmin() || isDocente()

    fun isAdmin(): Boolean {
        if (sharedPreferences.getBoolean(KEY_IS_ADMIN, false)) return true
        val roleIds = sharedPreferences.getStringSet(KEY_USER_ROLE_IDS, emptySet())
        if (roleIds != null && (roleIds.contains("3") || roleIds.contains("4"))) return true
        val roles = sharedPreferences.getStringSet(KEY_USER_ROLES, emptySet())
        if (roles != null && (roles.contains("3") || roles.contains("4"))) return true
        val legacy = getUserRole()
        if (legacy?.equals("admin", ignoreCase = true) == true) return true
        return false
    }

    /**
     * Check whether the stored roles contain the given role id.
     */
    fun hasRole(roleId: Int): Boolean {
        val idStr = roleId.toString()
        val set1 = sharedPreferences.getStringSet(KEY_USER_ROLE_IDS, emptySet()) ?: emptySet()
        if (set1.contains(idStr)) return true
        val set2 = sharedPreferences.getStringSet(KEY_USER_ROLES, emptySet()) ?: emptySet()
        if (set2.contains(idStr)) return true
        // fallback: legacy role name string
        val legacy = getUserRole()
        if (roleId == 3 && legacy?.equals("admin", ignoreCase = true) == true) return true
        if (roleId == 2 && legacy?.equals("docente", ignoreCase = true) == true) return true
        if (roleId == 1 && (legacy?.equals("user", ignoreCase = true) == true || legacy?.equals("usuario", ignoreCase = true) == true)) return true
        return false
    }

    /** Returns the list of stored numeric role IDs for this user session. */
    fun getRoleIds(): List<Int> {
        val set1 = sharedPreferences.getStringSet(KEY_USER_ROLE_IDS, emptySet()) ?: emptySet()
        val set2 = sharedPreferences.getStringSet(KEY_USER_ROLES, emptySet()) ?: emptySet()
        return (set1 + set2).mapNotNull { it.toIntOrNull() }.distinct()
    }

    /** Add role id to canonical sets (keeps backwards compatibility). */
    fun addRole(roleId: Int) {
        val idStr = roleId.toString()
        val set = HashSet(sharedPreferences.getStringSet(KEY_USER_ROLES, emptySet()) ?: emptySet())
        if (set.add(idStr)) {
            editor.putStringSet(KEY_USER_ROLES, set)
            editor.apply()
        }
        // also update legacy/key alias
        val setIds = HashSet(sharedPreferences.getStringSet(KEY_USER_ROLE_IDS, emptySet()) ?: emptySet())
        if (setIds.add(idStr)) {
            editor.putStringSet(KEY_USER_ROLE_IDS, setIds)
            editor.apply()
        }
        // If admin role added, set legacy fields for compatibility
        if (roleId == 3) {
            editor.putString(KEY_USER_ROLE, "admin")
            editor.putBoolean(KEY_IS_ADMIN, true)
            editor.apply()
        }
    }

    /** Replace the stored roles with an authoritative set. */
    fun replaceRoles(roleIds: Collection<Int>, roleName: String? = null) {
        val normalizedRoleIds = roleIds.distinct()
        val roleSet = normalizedRoleIds.map { it.toString() }.toSet()
        val resolvedRoleName = roleName?.takeIf { it.isNotBlank() } ?: inferLegacyRoleName(normalizedRoleIds)

        clearStoredRoles()
        editor.putStringSet(KEY_USER_ROLE_IDS, roleSet)
        editor.putStringSet(KEY_USER_ROLES, roleSet)
        if (!resolvedRoleName.isNullOrBlank()) {
            editor.putString(KEY_USER_ROLE, resolvedRoleName)
        }
        editor.putBoolean(KEY_IS_ADMIN, normalizedRoleIds.contains(3))
        editor.apply()
    }

    /** Remove role id from canonical sets. */
    fun removeRole(roleId: Int) {
        val idStr = roleId.toString()
        val set = HashSet(sharedPreferences.getStringSet(KEY_USER_ROLES, emptySet()) ?: emptySet())
        if (set.remove(idStr)) {
            editor.putStringSet(KEY_USER_ROLES, set)
            editor.apply()
        }
        val setIds = HashSet(sharedPreferences.getStringSet(KEY_USER_ROLE_IDS, emptySet()) ?: emptySet())
        if (setIds.remove(idStr)) {
            editor.putStringSet(KEY_USER_ROLE_IDS, setIds)
            editor.apply()
        }
        if (roleId == 3) {
            val legacy = getUserRole()
            if (legacy == "admin") {
                editor.putString(KEY_USER_ROLE, "user")
            }
            editor.putBoolean(KEY_IS_ADMIN, false)
            editor.apply()
        }
    }

    /** Explicitly set/unset admin status. */
    fun setAdminStatus(isAdmin: Boolean) {
        editor.putBoolean(KEY_IS_ADMIN, isAdmin)
        editor.apply()
        if (isAdmin) addRole(3) else removeRole(3)
    }

    // Simple getters
    fun getUsername(): String? = sharedPreferences.getString(KEY_USERNAME, null)
    fun getUserId(): Long = sharedPreferences.getLong(KEY_USER_ID, -1)
    fun getPersonaId(): Long = sharedPreferences.getLong(KEY_PERSONA_ID, -1)
    fun getUserRole(): String? = sharedPreferences.getString(KEY_USER_ROLE, null)
    fun getUserAvatar(): String? = sharedPreferences.getString(KEY_USER_AVATAR, null)

    fun saveUserAvatar(avatarUri: String) {
        editor.putString(KEY_USER_AVATAR, avatarUri)
        editor.apply()
    }

    /** Refresh session from Supabase (keeps existing logic). */
    suspend fun refreshFromSupabase(): Boolean {
        val current = getUsername() ?: return false
        try {
            val supabase = com.example.tareamov.service.SupabaseClient
            val (u, r) = supabase.fetchUsuarioWithRoleByUsername(current)
            if (u == null) return false
            val roleName = r?.nombre ?: getUserRole() ?: ""
            val fallbackRoleIds = when {
                r?.id != null -> listOf(r.id.toInt())
                roleName.equals("admin", ignoreCase = true) -> listOf(3)
                roleName.equals("docente", ignoreCase = true) -> listOf(2)
                roleName.equals("user", ignoreCase = true) || roleName.equals("usuario", ignoreCase = true) -> listOf(1)
                else -> emptyList()
            }
            val avatar = u.avatar
            editor.putString(KEY_USERNAME, u.usuario)
            editor.putLong(KEY_USER_ID, u.id)
            editor.putLong(KEY_PERSONA_ID, u.persona_id)
            editor.putString(KEY_USER_ROLE, roleName)
            if (avatar != null) editor.putString(KEY_USER_AVATAR, avatar)
            editor.apply()

            // Fetch ALL roles from usuarios_roles junction table (authoritative source)
            // This ensures roles 2, 3, etc. are loaded consistently in both QA and Production
            try {
                val allRoleIds = supabase.fetchUserRoleIds(u.id)
                android.util.Log.d("SessionManager", "refreshFromSupabase: all roles for userId=${u.id}: $allRoleIds")
                replaceRoles(allRoleIds, roleName)
            } catch (e: Exception) {
                android.util.Log.w("SessionManager", "Could not fetch roles from usuarios_roles: ${e.message}")
                replaceRoles(fallbackRoleIds, roleName)
            }

            notifyUserChanged(getLastActiveUser(), u.usuario)
            return true
        } catch (e: Exception) {
            android.util.Log.w("SessionManager", "refreshFromSupabase failed for user=$current", e)
            return false
        }
    }

    fun isLoggedIn(): Boolean = sharedPreferences.getBoolean(KEY_IS_LOGGED_IN, false)

    fun logout() {
        val previousUser = getUsername()
        editor.clear()
        editor.apply()
        // Also clear the separate user_prefs SharedPreferences
        context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
        if (previousUser != null) notifyUserLoggedOut(previousUser)
    }

    fun getLastActiveUser(): String? = sharedPreferences.getString(KEY_LAST_ACTIVE_USER, null)
    fun getUserSessionTimestamp(): Long = sharedPreferences.getLong(KEY_USER_SESSION_TIMESTAMP, 0)
    fun hasUserChanged(): Boolean = getUsername() != getLastActiveUser()

    fun getChatPreferenceKey(baseKey: String): String {
        val username = getUsername() ?: "anonymous"
        return "${baseKey}_user_${username}"
    }

    fun getChatPreferences(context: Context): android.content.SharedPreferences {
        val username = getUsername() ?: "anonymous"
        return context.getSharedPreferences("chat_persistence_$username", Context.MODE_PRIVATE)
    }

    fun clearUserChatData(context: Context) {
        val chatPrefs = getChatPreferences(context)
        chatPrefs.edit().clear().apply()
    }

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

    fun subscribeToCreator(creatorUsername: String): Boolean {
        val currentUsername = getUsername() ?: return false
        if (currentUsername == creatorUsername) return false
        if (isSubscribedTo(creatorUsername)) return false
        val subscriptionKey = KEY_SUBSCRIPTIONS_PREFIX + creatorUsername
        editor.putBoolean(subscriptionKey, true)
        editor.apply()
        return true
    }

    fun unsubscribeFromCreator(creatorUsername: String): Boolean {
        if (!isSubscribedTo(creatorUsername)) return false
        val subscriptionKey = KEY_SUBSCRIPTIONS_PREFIX + creatorUsername
        editor.remove(subscriptionKey)
        editor.apply()
        return true
    }

    fun isSubscribedTo(creatorUsername: String): Boolean {
        val subscriptionKey = KEY_SUBSCRIPTIONS_PREFIX + creatorUsername
        return sharedPreferences.getBoolean(subscriptionKey, false)
    }

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