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