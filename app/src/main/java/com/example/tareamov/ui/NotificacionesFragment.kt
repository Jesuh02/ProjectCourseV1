package com.example.tareamov.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tareamov.R
import com.example.tareamov.databinding.FragmentNotificacionesBinding
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import com.example.tareamov.data.entity.Notification
import com.example.tareamov.service.SupabaseClient
import com.example.tareamov.ui.adapter.NotificationAdapter
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.launch

class NotificacionesFragment : Fragment() {

    private var _binding: FragmentNotificacionesBinding? = null
    private val binding get() = _binding!!
    private lateinit var bottomNavBinding: ComponentBottomNavigationBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var notificationAdapter: NotificationAdapter

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

        sessionManager = SessionManager.getInstance(requireContext())

        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        bottomNavBinding.activityIconImageView.setColorFilter(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.purple_500)
        )

        setupRecyclerView()
        setupAdminButton()
        setupNavigation()
        setupTabs()
        
        loadNotifications()
    }

    private fun setupRecyclerView() {
        notificationAdapter = NotificationAdapter { notification ->
            onNotificationClick(notification)
        }
        binding.notificationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = notificationAdapter
        }
    }

    private fun loadNotifications() {
        val userId = sessionManager.getUserId()
        if (userId == -1L) {
            Log.w("NotificacionesFragment", "No user ID available")
            return
        }

        lifecycleScope.launch {
            try {
                val notifications = SupabaseClient.fetchNotifications(userId)
                
                if (notifications.isNotEmpty()) {
                    binding.notificationsRecyclerView.visibility = View.VISIBLE
                    binding.emptyStateLayout.visibility = View.GONE
                    notificationAdapter.submitList(notifications)
                    
                    val unreadCount = notifications.count { !it.isRead }
                    binding.notificacionesTab.text = "Notificaciones ($unreadCount)"
                } else {
                    binding.notificationsRecyclerView.visibility = View.GONE
                    binding.emptyStateLayout.visibility = View.VISIBLE
                    binding.notificacionesTab.text = "Notificaciones (0)"
                }
            } catch (e: Exception) {
                Log.e("NotificacionesFragment", "Error loading notifications", e)
                binding.notificationsRecyclerView.visibility = View.GONE
                binding.emptyStateLayout.visibility = View.VISIBLE
            }
        }
    }

    private fun onNotificationClick(notification: Notification) {
        if (!notification.isRead) {
            lifecycleScope.launch {
                SupabaseClient.markNotificationAsRead(notification.id)
                loadNotifications()
            }
        }

        when (notification.type) {
            Notification.TYPE_NEW_COURSE -> {
                Log.d("Notificaciones", "Course notification clicked: ${notification.relatedId}")
            }
            Notification.TYPE_NEW_VIDEO -> {
                Log.d("Notificaciones", "Video notification clicked: ${notification.relatedId}")
            }
        }
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
            binding.notificationsRecyclerView.visibility = View.VISIBLE
            binding.emptyStateLayout.visibility = View.GONE
            loadNotifications()
        } else {
            binding.notificacionesTab.setTextColor(resources.getColor(R.color.white, null))
            binding.susurrosTab.setTextColor(resources.getColor(R.color.purple_500, null))
            binding.notificationsRecyclerView.visibility = View.GONE
            binding.emptyStateLayout.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupAdminButton() {
        // Usar la propiedad de clase bottomNavBinding inicializada en onViewCreated
        val adminSlot = bottomNavBinding.adminSlot
        val goToAdminButton = bottomNavBinding.goToAdminButton

        // Inicializa como INVISIBLE para evitar salto al inflar
        goToAdminButton.visibility = View.INVISIBLE

        // Si SessionManager ya conoce el rol del usuario, podemos decidir antes del primer render
        val sess = SessionManager.getInstance(requireContext())
        if (!sess.isAdmin()) {
            // Ocultar completamente el slot antes de que se dibuje para que no quede hueco
            adminSlot.visibility = View.GONE
            return
        }

        // Si llegó aquí, el usuario es admin según SessionManager: mostrar y asignar listener
        goToAdminButton.visibility = View.VISIBLE
        goToAdminButton.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_homeFragment)
        }
    }
}