package com.example.tareamov.util

import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import com.example.tareamov.R
import com.example.tareamov.data.sync.SyncRepository
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Helper class to standardize Bottom Navigation logic across fragments.
 * Ensures the "Admin" button visibility is handled consistently and automatically.
 */
object BottomNavigationHelper {

    fun setupBottomNavigation(
        lifecycleOwner: LifecycleOwner,
        navController: NavController,
        view: View,
        sessionManager: SessionManager,
        syncRepository: SyncRepository? = null,
        onAdminClick: (() -> Unit)? = null
    ) {
        val binding = ComponentBottomNavigationBinding.bind(view.findViewById(R.id.bottomNavigation))

        setupAdminButton(lifecycleOwner, navController, binding, sessionManager, syncRepository, onAdminClick)
    }

    private fun setupAdminButton(
        lifecycleOwner: LifecycleOwner,
        navController: NavController,
        binding: ComponentBottomNavigationBinding,
        sessionManager: SessionManager,
        syncRepository: SyncRepository?,
        onAdminClick: (() -> Unit)?
    ) {
        val adminSlot = binding.adminSlot
        val goToAdminButton = binding.goToAdminButton

        // 1. Fast Synchronous Check (from SessionManager cache)
        val isCachedAdmin = sessionManager.isAdmin()
        updateAdminVisibility(adminSlot, goToAdminButton, isCachedAdmin)

        // 2. Async Check (if repository provided) - Updates cache for next time
        if (syncRepository != null) {
            val userId = sessionManager.getUserId()
            if (userId != -1L) {
                lifecycleOwner.lifecycleScope.launch {
                    try {
                        // Check robust admin status (Role 3)
                        val isAdminRemote = withContext(Dispatchers.IO) {
                            syncRepository.isUserAdmin(userId)
                        }
                        
                        // Update cache and UI if different
                        if (isAdminRemote != isCachedAdmin) {
                            sessionManager.setAdminStatus(isAdminRemote)
                            updateAdminVisibility(adminSlot, goToAdminButton, isAdminRemote)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Click Listener
        goToAdminButton.setOnClickListener {
            if (onAdminClick != null) {
                onAdminClick()
            } else {
                // Default navigation to HomeFragment (Admin Dashboard)
                try {
                    // Attempt to navigate to homeFragment (ID must match graph)
                    navController.navigate(R.id.homeFragment)
                } catch (e: Exception) {
                    // Fallback if direct ID navigation fails (e.g. need specific action)
                    android.util.Log.e("BottomNavHelper", "Could not navigate to homeFragment directly", e)
                }
            }
        }
    }

    private fun updateAdminVisibility(
        adminSlot: FrameLayout,
        goToAdminButton: LinearLayout,
        isVisible: Boolean
    ) {
        val visibility = if (isVisible) View.VISIBLE else View.GONE
        if (adminSlot.visibility != visibility) {
            adminSlot.visibility = visibility
            goToAdminButton.visibility = visibility
        }
    }
}
