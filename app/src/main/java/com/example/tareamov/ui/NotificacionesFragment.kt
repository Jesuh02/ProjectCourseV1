package com.example.tareamov.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.databinding.FragmentNotificacionesBinding
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NotificacionesFragment : Fragment() {

    private var _binding: FragmentNotificacionesBinding? = null
    private val binding get() = _binding!!
    private lateinit var bottomNavBinding: ComponentBottomNavigationBinding
    private lateinit var sessionManager: SessionManager

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificacionesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize SessionManager
        sessionManager = SessionManager.getInstance(requireContext())

        // Enlazar el binding del include manualmente
        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        // Resaltar solo el icono de notificaciones (actividad) en morado
        bottomNavBinding.activityIconImageView.setColorFilter(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.purple_500)
        )

        // Setup admin button visibility and functionality
        setupAdminButton()

        setupNavigation()
        setupTabs()
    }

    private fun setupNavigation() {
        bottomNavBinding.homeNavLayout.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_videoHomeFragment)
        }

        bottomNavBinding.exploreButton.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_exploreFragment)
        }

        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_contentUploadFragment)
        }

        bottomNavBinding.activityButton.setOnClickListener {
            // Ya estamos en la actividad, no hacemos nada
        }

        bottomNavBinding.profileNavButton.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_profileFragment)
        }
    }

    private fun setupTabs() {
        binding.notificacionesTab.setOnClickListener {
            updateTabSelection(true)
        }

        binding.susurrosTab.setOnClickListener {
            updateTabSelection(false)
        }
    }

    private fun updateTabSelection(notificacionesSelected: Boolean) {
        if (notificacionesSelected) {
            binding.notificacionesTab.setTextColor(resources.getColor(R.color.purple_500, null))
            binding.susurrosTab.setTextColor(resources.getColor(R.color.white, null))
            binding.tabIndicator.apply {
                val params = layoutParams as ViewGroup.LayoutParams
                layoutParams = params
            }
        } else {
            binding.notificacionesTab.setTextColor(resources.getColor(R.color.white, null))
            binding.susurrosTab.setTextColor(resources.getColor(R.color.purple_500, null))
            binding.tabIndicator.apply {
                val params = layoutParams as ViewGroup.LayoutParams
                layoutParams = params
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupAdminButton() {
        val goToAdminButton = bottomNavBinding.goToAdminButton
        
        // Initially hide the admin button to avoid reflow during async check
        goToAdminButton.visibility = View.INVISIBLE

        // Check if the current user is admin
        checkAdminStatus { isAdmin ->
            if (isAdmin) {
                goToAdminButton.visibility = View.VISIBLE
                goToAdminButton.setOnClickListener {
                    Log.d("NotificacionesFragment", "Admin button clicked, navigating to HomeFragment")
                    findNavController().navigate(R.id.action_notificacionesFragment_to_homeFragment)
                }
            } else {
                goToAdminButton.visibility = View.INVISIBLE
            }
        }
    }

    private fun checkAdminStatus(callback: (Boolean) -> Unit) {
        val username = sessionManager.getUsername()
        if (username == null) {
            callback(false)
            return
        }

        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val usuarioWithRole = withContext(Dispatchers.IO) {
                    db.usuarioDao().getUsuarioWithRoleByUsername(username)
                }
                
                val isAdmin = usuarioWithRole?.isAdmin == true
                Log.d("NotificacionesFragment", "User $username is admin: $isAdmin (role: ${usuarioWithRole?.rolNombre})")
                callback(isAdmin)
            } catch (e: Exception) {
                Log.e("NotificacionesFragment", "Error checking admin status", e)
                callback(false)
            }
        }
    }
}