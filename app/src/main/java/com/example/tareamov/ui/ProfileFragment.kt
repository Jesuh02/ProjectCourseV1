package com.example.tareamov.ui 
import com.example.tareamov.databinding.ComponentBottomNavigationBinding 

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log

class ProfileFragment : Fragment() {
    private lateinit var usernameTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var followersTextView: TextView
    private lateinit var profileImage: CircleImageView
    private lateinit var editProfileButton: Button

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        usernameTextView = view.findViewById(R.id.usernameTextView)
        statusTextView = view.findViewById(R.id.statusTextView)
        followersTextView = view.findViewById(R.id.followersTextView)
        profileImage = view.findViewById(R.id.profileImage)
        editProfileButton = view.findViewById(R.id.editProfileButton)

        // Set up navigation for bottom buttons usando ComponentBottomNavigationBinding 
        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        val bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        // Set up admin button with database verification
        setupAdminButton(bottomNavBinding)

        // Resaltar solo el icono de perfil en morado
        bottomNavBinding.profileIconImageView.setColorFilter(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.purple_500)
        )        // Ocultar botón Admin si el usuario no es admin
        // (Este código se mueve al método setupAdminButton)

        bottomNavBinding.homeNavLayout.setOnClickListener {
            findNavController().navigate(R.id.action_profileFragment_to_videoHomeFragment)
        }
        bottomNavBinding.exploreButton.setOnClickListener {
            findNavController().navigate(R.id.action_profileFragment_to_exploreFragment)
        }
        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.action_profileFragment_to_contentUploadFragment)
        }
        bottomNavBinding.activityButton.setOnClickListener {
            findNavController().navigate(R.id.action_profileFragment_to_notificacionesFragment)
        }
        bottomNavBinding.profileNavButton.setOnClickListener {
            // Ya estás en Perfil, puedes dejarlo vacío o recargar
        }

        // Set up menu item clicks
        setupMenuItems(view)

        // Load user data
        loadUserData()

        // Set up edit profile button
        // Update the editProfileButton click listener in onViewCreated method
        editProfileButton.setOnClickListener {
            findNavController().navigate(R.id.action_profileFragment_to_editProfileFragment)
        }
    }

    // Eliminado: setupBottomNavigation(view) porque ahora se usa BottomNavigationBinding

    private fun setupMenuItems(view: View) {
        // My Courses
        view.findViewById<LinearLayout>(R.id.myChannelItem).setOnClickListener {
            Toast.makeText(requireContext(), "Mis cursos", Toast.LENGTH_SHORT).show()
        }

        // Creator Dashboard
        view.findViewById<LinearLayout>(R.id.creatorDashboardItem).setOnClickListener {
            Toast.makeText(requireContext(), "Panel de control del creador", Toast.LENGTH_SHORT).show()
        }

        // Analytics
        view.findViewById<LinearLayout>(R.id.analyticsItem).setOnClickListener {
            Toast.makeText(requireContext(), "Analíticas", Toast.LENGTH_SHORT).show()
        }

        // Subscriptions
        view.findViewById<LinearLayout>(R.id.subscriptionsItem).setOnClickListener {
            Toast.makeText(requireContext(), "Suscripciones", Toast.LENGTH_SHORT).show()
        }

        // Free Courses
        view.findViewById<LinearLayout>(R.id.dropsItem).setOnClickListener {
            Toast.makeText(requireContext(), "Cursos gratuitos", Toast.LENGTH_SHORT).show()
        }

        // Premium
        view.findViewById<LinearLayout>(R.id.turboItem).setOnClickListener {
            Toast.makeText(requireContext(), "Premium", Toast.LENGTH_SHORT).show()
        }

        // Account Settings
        view.findViewById<LinearLayout>(R.id.accountSettingsItem).setOnClickListener {
            Toast.makeText(requireContext(), "Configuración de la cuenta", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            try {
                // Get current user ID from SharedPreferences
                val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val currentUserId = sharedPrefs.getLong("current_user_id", -1L)

                if (currentUserId != -1L) {
                    val db = AppDatabase.getDatabase(requireContext())
                    // Fetch Usuario by ID
                    val usuario = withContext(Dispatchers.IO) {
                        db.usuarioDao().getUsuarioById(currentUserId)
                    }
                    if (usuario != null) {
                        // Fetch Persona by usuario.persona_id
                        val persona = withContext(Dispatchers.IO) {
                            db.personaDao().getPersonaById(usuario.persona_id)
                        }
                        // Show username from Usuario and avatar from Persona
                        updateUI(usuario, persona)
                    } else {
                        updateUI(null, null)
                    }
                } else {
                    updateUI(null, null)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateUI(null, null)
            }
        }
    }

    private fun updateUI(usuario: Usuario?, persona: Persona?) {
        // Use usuario?.usuario for username if that's the correct field
        usernameTextView.text = usuario?.usuario ?: getString(R.string.default_username)

        // Update status
        statusTextView.text = getString(R.string.status_offline)

        // Update followers count
        followersTextView.text = getString(R.string.followers_count, 0)

        // Update profile image with persona's avatar
        if (persona != null && !persona.avatar.isNullOrEmpty()) {
            Log.d("ProfileFragment", "Loading avatar: ${persona.avatar}")
            try {
                when {
                    persona.avatar.startsWith("http") -> {
                        // Load as URL
                        Glide.with(requireContext())
                            .load(persona.avatar)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .circleCrop()
                            .into(profileImage)
                        Log.d("ProfileFragment", "Loaded avatar from URL")
                    }
                    persona.avatar.startsWith("file:") -> {
                        // Load as file URI
                        val fileUri = Uri.parse(persona.avatar)
                        Glide.with(requireContext())
                            .load(fileUri)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .circleCrop()
                            .into(profileImage)
                        Log.d("ProfileFragment", "Loaded avatar from file URI")
                    }
                    persona.avatar.startsWith("/") -> {
                        // Load as file path
                        val file = File(persona.avatar)
                        Glide.with(requireContext())
                            .load(file)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .circleCrop()
                            .into(profileImage)
                        Log.d("ProfileFragment", "Loaded avatar from file path")
                    }
                    else -> {
                        // Try to load as resource ID first
                        try {
                            val resourceId = persona.avatar.toInt()
                            Glide.with(requireContext())
                                .load(resourceId)
                                .placeholder(R.drawable.ic_profile)
                                .error(R.drawable.ic_profile)
                                .circleCrop()
                                .into(profileImage)
                            Log.d("ProfileFragment", "Loaded avatar from resource ID: $resourceId")
                        } catch (e: NumberFormatException) {
                            // Try to load as drawable resource name
                            val drawableId = resources.getIdentifier(
                                persona.avatar, "drawable", requireContext().packageName
                            )
                            if (drawableId != 0) {
                                Glide.with(requireContext())
                                    .load(drawableId)
                                    .placeholder(R.drawable.ic_profile)
                                    .error(R.drawable.ic_profile)
                                    .circleCrop()
                                    .into(profileImage)
                                Log.d("ProfileFragment", "Loaded avatar from drawable name: $drawableId")
                            } else {
                                // Default image if all else fails
                                profileImage.setImageResource(R.drawable.ic_profile)
                                Log.d("ProfileFragment", "Failed to load avatar, using default")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("ProfileFragment", "Error loading avatar: ${e.message}")
                // If loading fails, use default profile image
                profileImage.setImageResource(R.drawable.ic_profile)
            }
        } else {
            // No avatar available, use default profile image
            Log.d("ProfileFragment", "No avatar available, using default")
            profileImage.setImageResource(R.drawable.ic_profile)
        }
    }

    private fun setupAdminButton(bottomNavBinding: ComponentBottomNavigationBinding) {
        android.util.Log.d("ProfileFragment", "setupAdminButton called, button found: ${bottomNavBinding.goToAdminButton != null}")
        
    // Initially hide the admin button
    bottomNavBinding.goToAdminButton.visibility = View.INVISIBLE
        
        // Check admin status
        checkAdminStatus { isAdmin ->
            android.util.Log.d("ProfileFragment", "Admin status received: $isAdmin")
            if (isAdmin) {
                bottomNavBinding.goToAdminButton.visibility = View.VISIBLE
                bottomNavBinding.goToAdminButton.setOnClickListener {
                    android.util.Log.d("ProfileFragment", "Admin button clicked, navigating to homeFragment")
                    findNavController().navigate(R.id.action_profileFragment_to_homeFragment)
                }
            } else {
                bottomNavBinding.goToAdminButton.visibility = View.INVISIBLE
            }
        }
    }

    private fun checkAdminStatus(callback: (Boolean) -> Unit) {
        lifecycleScope.launch {
            try {
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val username = sessionManager.getUsername()
                android.util.Log.d("ProfileFragment", "Checking admin status for user: $username")
                
                if (username != null) {
                    val db = AppDatabase.getDatabase(requireContext())
                    val usuarioWithRole = withContext(Dispatchers.IO) {
                        db.usuarioDao().getUsuarioWithRoleByUsername(username)
                    }
                    
                    val isAdmin = usuarioWithRole?.isAdmin == true
                    android.util.Log.d("ProfileFragment", "User $username is admin: $isAdmin (role: ${usuarioWithRole?.rolNombre})")
                    
                    // Ensure UI updates happen on main thread
                    withContext(Dispatchers.Main) {
                        callback(isAdmin)
                    }
                } else {
                    android.util.Log.d("ProfileFragment", "No username found in session")
                    withContext(Dispatchers.Main) {
                        callback(false)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ProfileFragment", "Error checking admin status", e)
                withContext(Dispatchers.Main) {
                    callback(false)
                }
            }
        }
    }

    // Add this method to ProfileFragment class
    override fun onResume() {
        super.onResume()

        // Check if profile was updated
        val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val profileUpdated = sharedPrefs.getBoolean("profile_updated", false)

        if (profileUpdated) {
            // Reset the flag
            sharedPrefs.edit().putBoolean("profile_updated", false).apply()

            // Reload user data
            loadUserData()
        }
    }
}