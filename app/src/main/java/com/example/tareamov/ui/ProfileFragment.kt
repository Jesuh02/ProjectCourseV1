package com.example.tareamov.ui 
import com.example.tareamov.databinding.ComponentBottomNavigationBinding 

import android.animation.ObjectAnimator
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
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
    private lateinit var avatarContainer: FrameLayout

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
        avatarContainer = view.findViewById(R.id.avatarContainer)

        // Set up navigation for bottom buttons usando ComponentBottomNavigationBinding 
        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        val bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        // Set up admin button with database verification
        setupAdminButton(bottomNavBinding)

        // Resaltar solo el icono de perfil en morado
        bottomNavBinding.profileIconImageView.setColorFilter(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.purple_500)
        )

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

        // Set up edit profile button with animation
        editProfileButton.setOnClickListener {
            animateButtonPress(it)
            findNavController().navigate(R.id.action_profileFragment_to_editProfileFragment)
        }

        // Initial entrance animation
        animateEntrance()
    }

    private fun animateEntrance() {
        // Animate Avatar Pop
        avatarContainer.alpha = 0f
        avatarContainer.scaleX = 0.5f
        avatarContainer.scaleY = 0.5f
        avatarContainer.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(600)
            .setInterpolator(OvershootInterpolator())
            .start()

        // Animate Text Fade In
        val texts = listOf(usernameTextView, statusTextView, followersTextView)
        texts.forEachIndexed { index, view ->
            view.translationY = 50f
            view.alpha = 0f
            view.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(500)
                .setStartDelay(200L + (index * 100))
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        // Animate Menu Groups Slide Up
        view?.findViewById<LinearLayout>(R.id.menuContainer)?.let { container ->
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                child.translationY = 100f
                child.alpha = 0f
                child.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(500)
                    .setStartDelay(400L + (i * 100))
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    private fun animateButtonPress(view: View) {
        view.animate()
            .scaleX(0.95f)
            .scaleY(0.95f)
            .setDuration(100)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(100)
                    .start()
            }
            .start()
    }

    // Eliminado: setupBottomNavigation(view) porque ahora se usa BottomNavigationBinding

    private fun setupMenuItems(view: View) {
        val menuItems = mapOf(
            R.id.myChannelItem to "Mis cursos",
            R.id.creatorDashboardItem to "Panel de control del creador",
            R.id.analyticsItem to "Analíticas",
            R.id.subscriptionsItem to "Suscripciones",
            R.id.dropsItem to "Cursos gratuitos",
            R.id.turboItem to "Premium",
            R.id.accountSettingsItem to "Configuración de la cuenta"
        )

        menuItems.forEach { (id, message) ->
            view.findViewById<LinearLayout>(id)?.setOnClickListener {
                animateButtonPress(it)
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            try {
                // Prefer SessionManager for active user identification
                val session = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val activeUsername = session.getUsername()

                // If we have a username from the active session, try Supabase first
                if (!activeUsername.isNullOrEmpty() && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                    try {
                        val remoteUsuario = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.fetchUsuarioByUsername(activeUsername)
                        }
                        if (remoteUsuario != null) {
                            // Try fetching Persona remotely as well (by persona_id) if available
                            val remotePersona = withContext(Dispatchers.IO) {
                                try {
                                    val personaId = remoteUsuario.persona_id ?: -1L
                                    if (personaId > 0) {
                                        com.example.tareamov.service.SupabaseClient.fetchPersonas().firstOrNull { p -> p.id == personaId }
                                    } else null
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            updateUI(remoteUsuario, remotePersona)
                            return@launch
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ProfileFragment", "Supabase lookup failed, falling back to local DB", e)
                    }
                }

                // Fallback: use local SharedPreferences-stored user id or Room DB lookup
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
                            usuario.persona_id?.let { id -> db.personaDao().getPersonaById(id) }
                        }
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
         // Mostrar el botón de admin solo si el usuario es admin
        val adminSlot = bottomNavBinding.adminSlot
        val goToAdminButton = bottomNavBinding.goToAdminButton

        // Inicializa como INVISIBLE para evitar salto al inflar
        goToAdminButton.visibility = View.INVISIBLE

        val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        if (!sess.isAdmin()) {
            adminSlot.visibility = View.GONE
            return
        }

        // Usuario es admin según SessionManager: mostrar el botón y asignar listener
        goToAdminButton.visibility = View.VISIBLE
        goToAdminButton.setOnClickListener {
            findNavController().navigate(R.id.action_profileFragment_to_homeFragment)
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