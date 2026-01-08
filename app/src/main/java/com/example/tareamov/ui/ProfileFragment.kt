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
import androidx.activity.result.contract.ActivityResultContracts
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
import java.io.FileOutputStream
import android.util.Log

class ProfileFragment : Fragment() {
    private lateinit var usernameTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var subscribersTextView: TextView
    private lateinit var profileImage: CircleImageView
    private lateinit var editProfileButton: Button
    private lateinit var avatarContainer: FrameLayout
    private lateinit var skeletonLayout: FrameLayout

    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleImageSelection(it) }
    }

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
        subscribersTextView = view.findViewById(R.id.subscribersTextView)
        profileImage = view.findViewById(R.id.profileImage)
        editProfileButton = view.findViewById(R.id.editProfileButton)
        avatarContainer = view.findViewById(R.id.avatarContainer)
        skeletonLayout = view.findViewById(R.id.skeletonLayout)

        // Show immediate connection state while data is loading
        try {
            val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
            if (sess.isLoggedIn()) {
                statusTextView.text = getString(R.string.status_connected)
                statusTextView.setTextColor(android.graphics.Color.parseColor("#2ECC71"))
            } else {
                statusTextView.text = getString(R.string.status_disconnected)
                statusTextView.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            }
        } catch (e: Exception) {
            // ignore
        }

        // Enable clicking on avatar to change it
        profileImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }
        avatarContainer.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // Set up navigation for bottom buttons usando ComponentBottomNavigationBinding 
        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        val bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        // Set up admin button with database verification
        setupAdminButton(bottomNavBinding)
        
        // Actualizar badge de notificaciones
        updateNotificationBadge(bottomNavBinding)

        // Resaltar solo el icono de perfil en morado (ahora con fondo pill)
        val activeBackground = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.nav_item_background_active)
        bottomNavBinding.profileIconContainer.background = activeBackground
        
        // Ensure icons are white
        val whiteColor = android.graphics.Color.WHITE
        bottomNavBinding.homeIconImageView.setColorFilter(whiteColor)
        bottomNavBinding.exploreIconImageView.setColorFilter(whiteColor)
        bottomNavBinding.activityIconImageView.setColorFilter(whiteColor)
        bottomNavBinding.profileIconImageView.setColorFilter(whiteColor)

        bottomNavBinding.homeNavLayout.setOnClickListener {
            updateBottomNavSelection(bottomNavBinding, "home")
            findNavController().navigate(R.id.action_profileFragment_to_videoHomeFragment)
        }
        bottomNavBinding.exploreButton.setOnClickListener {
            updateBottomNavSelection(bottomNavBinding, "explore")
            findNavController().navigate(R.id.action_profileFragment_to_exploreFragment)
        }
        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.action_profileFragment_to_contentUploadFragment)
        }
        bottomNavBinding.activityButton.setOnClickListener {
            updateBottomNavSelection(bottomNavBinding, "activity")
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

    private fun handleImageSelection(uri: Uri) {
        // Show initial feedback
        Toast.makeText(requireContext(), "Procesando imagen...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                val context = requireContext()
                
                // 1. Upload to Cloudflare R2
                Toast.makeText(context, "Subiendo a la nube...", Toast.LENGTH_SHORT).show()
                
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(context)
                val username = sessionManager.getUsername() ?: "user_${System.currentTimeMillis()}"
                val timestamp = System.currentTimeMillis()
                val customFileName = "avatar_${username}_$timestamp"
                
                // Upload image to Cloudflare R2
                val uploadResult = com.example.tareamov.service.CloudflareR2Service.uploadImage(
                    context = context,
                    imageUri = uri,
                    customFileName = customFileName
                )
                
                if (uploadResult is com.example.tareamov.service.CloudflareR2Service.UploadResult.Success) {
                    val publicUrl = uploadResult.url
                    Log.d("ProfileFragment", "Avatar uploaded to R2: $publicUrl")
                    
                    // 2. Update Supabase
                    val userId = sessionManager.getUserId()
                    if (userId != -1L) {
                        val currentUser = com.example.tareamov.service.SupabaseClient.fetchUsuarioById(userId)
                        
                        if (currentUser != null) {
                            val updatedUser = currentUser.copy(avatar = publicUrl)
                            val success = com.example.tareamov.service.SupabaseClient.updateUsuario(updatedUser)
                            
                            if (success) {
                                // 3. Update Local Session
                                sessionManager.saveUserAvatar(publicUrl)
                                
                                // 4. Update UI
                                Glide.with(this@ProfileFragment)
                                    .load(publicUrl)
                                    .circleCrop()
                                    .into(profileImage)
                                    
                                Toast.makeText(context, "Avatar actualizado correctamente", Toast.LENGTH_SHORT).show()
                                
                                // Notify other components
                                requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                    .edit().putBoolean("profile_updated", true).apply()
                            } else {
                                Toast.makeText(context, "Error actualizando base de datos", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                             Toast.makeText(context, "Usuario no encontrado", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Error: Sesión inválida", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errorMsg = (uploadResult as? com.example.tareamov.service.CloudflareR2Service.UploadResult.Error)?.message ?: "Error desconocido"
                    Toast.makeText(context, "Error subiendo imagen: $errorMsg", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error updating avatar", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBottomNavSelection(bottomNavBinding: ComponentBottomNavigationBinding, selected: String) {
        val activeBackground = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.nav_item_background_active)
        
        bottomNavBinding.homeIconContainer.background = if (selected == "home") activeBackground else null
        bottomNavBinding.exploreIconContainer.background = if (selected == "explore") activeBackground else null
        bottomNavBinding.activityIconContainer.background = if (selected == "activity") activeBackground else null
        bottomNavBinding.profileIconContainer.background = if (selected == "profile") activeBackground else null
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
        val texts = listOf(usernameTextView, statusTextView, subscribersTextView)
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
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        
        val menuItems = mapOf(
            R.id.myChannelItem to "Mis cursos",
            R.id.creatorDashboardItem to "Panel de control del creador",
            R.id.subscriptionsItem to "Suscripciones",
            R.id.dropsItem to "Cursos gratuitos",
            R.id.turboItem to "Premium",
            R.id.accountSettingsItem to "Configuración de la cuenta"
        )

        menuItems.forEach { (id, message) ->
            val itemView = view.findViewById<LinearLayout>(id) ?: return@forEach
            
            // Ocultar "Panel de control del creador" si el usuario no tiene rol 2
            if (id == R.id.creatorDashboardItem && !sessionManager.hasRole(2)) {
                itemView.visibility = View.GONE
                return@forEach
            }
            
            if (id == R.id.myChannelItem) {
                itemView.setOnClickListener {
                    animateButtonPress(it)
                    // Navigate to ExploreFragment and request "Mis cursos" filter (index 1)
                    val bundle = Bundle().apply { putInt("filter_index", 1) }
                    findNavController().navigate(R.id.action_profileFragment_to_exploreFragment, bundle)
                }
            } else if (id == R.id.creatorDashboardItem) {
                itemView.setOnClickListener {
                    animateButtonPress(it)
                    // Navegar al Panel de Administrador
                    findNavController().navigate(R.id.adminDashboardFragment)
                }
            } else if (id == R.id.subscriptionsItem) {
                itemView.setOnClickListener {
                    animateButtonPress(it)
                    // Navigate to SubscriptionsFragment
                    try {
                        findNavController().navigate(R.id.action_profileFragment_to_subscriptionsFragment)
                    } catch (e: Exception) {
                        // Fallback if action not defined yet
                        try {
                            findNavController().navigate(R.id.subscriptionsFragment)
                        } catch (e2: Exception) {
                            Toast.makeText(requireContext(), "Error de navegación", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else if (id == R.id.dropsItem) {
                itemView.setOnClickListener {
                    animateButtonPress(it)
                    // Navigate to ExploreFragment and request "Free courses" filter (index 4)
                    val bundle = Bundle().apply { putInt("filter_index", 4) }
                    findNavController().navigate(R.id.action_profileFragment_to_exploreFragment, bundle)
                }
            } else {
                itemView.setOnClickListener {
                    animateButtonPress(it)
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun loadUserData() {
        startSkeletonAnimation()
        lifecycleScope.launch {
            try {
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val currentUsername = sessionManager.getUsername()
                
                if (currentUsername != null) {
                    // 1. Try to get avatar from SessionManager first (fastest)
                    val cachedAvatar = sessionManager.getUserAvatar()
                    if (!cachedAvatar.isNullOrEmpty()) {
                        Glide.with(this@ProfileFragment)
                            .load(cachedAvatar)
                            .placeholder(R.drawable.ic_profile_placeholder)
                            .error(R.drawable.ic_profile_placeholder)
                            .into(profileImage)
                    }

                    // 2. Fetch fresh data from Supabase
                    val usuario = com.example.tareamov.service.SupabaseClient.fetchUsuarioByUsername(currentUsername)
                    
                    if (usuario != null) {
                        // Update UI with user data
                        usernameTextView.text = usuario.usuario
                        
                        // Load avatar from Supabase result (fresher than session)
                        if (!usuario.avatar.isNullOrEmpty()) {
                            Glide.with(this@ProfileFragment)
                                .load(usuario.avatar)
                                .placeholder(R.drawable.ic_profile_placeholder)
                                .error(R.drawable.ic_profile_placeholder)
                                .into(profileImage)
                        }
                        
                        // Fetch subscriber count
                        val subscriberCount = com.example.tareamov.service.SupabaseClient.fetchSubscriberCount(usuario.id)
                        updateUI(usuario, null, subscriberCount)
                    } else {
                        // Fallback to local DB if offline
                        loadLocalUserData(currentUsername)
                    }
                } else {
                    stopSkeletonAnimation()
                    Toast.makeText(context, "No hay sesión activa", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error loading user data", e)
                stopSkeletonAnimation()
            }
        }
    }

    private suspend fun loadLocalUserData(username: String) {
        try {
            val database = AppDatabase.getDatabase(requireContext())
            val usuario = database.usuarioDao().getUsuarioByUsername(username)
            
            if (usuario != null) {
                val persona = database.personaDao().getPersonaById(usuario.persona_id)
                updateUI(usuario, persona, 0)
            } else {
                stopSkeletonAnimation()
                Toast.makeText(context, "Usuario no encontrado", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("ProfileFragment", "Error loading local user data", e)
            stopSkeletonAnimation()
        }
    }

    private fun updateUI(usuario: Usuario?, persona: Persona?, subscriberCount: Long) {
        if (usuario == null && !isNetworkAvailable(requireContext())) {
            return
        }
        stopSkeletonAnimation()

        // Use usuario?.usuario for username if that's the correct field
        usernameTextView.text = usuario?.usuario ?: getString(R.string.default_username)

        // Update status based on session state
        try {
            val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
            if (sess.isLoggedIn()) {
                statusTextView.text = getString(R.string.status_connected)
                statusTextView.setTextColor(android.graphics.Color.parseColor("#2ECC71")) // green
            } else {
                statusTextView.text = getString(R.string.status_disconnected)
                statusTextView.setTextColor(android.graphics.Color.parseColor("#AAAAAA")) // gray
            }
        } catch (e: Exception) {
            statusTextView.text = getString(R.string.status_disconnected)
        }

        // Update subscribers count
        // Use string resource with placeholder if available, otherwise manual concatenation
        try {
            subscribersTextView.text = getString(R.string.followers_count, subscriberCount)
        } catch (e: Exception) {
            subscribersTextView.text = "$subscriberCount suscriptores"
        }

        // Update profile image with usuario's avatar
        if (usuario != null && !usuario.avatar.isNullOrEmpty()) {
            Log.d("ProfileFragment", "Loading avatar: ${usuario.avatar}")
            try {
                when {
                    usuario.avatar.startsWith("http") -> {
                        // Load as URL with cache disabled for fresh image
                        Glide.with(requireContext())
                            .load(usuario.avatar)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .skipMemoryCache(true) // Skip memory cache
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE) // Skip disk cache
                            .circleCrop()
                            .into(profileImage)
                        Log.d("ProfileFragment", "Loaded avatar from URL (no cache)")
                    }
                    usuario.avatar.startsWith("file:") -> {
                        // Load as file URI
                        val fileUri = Uri.parse(usuario.avatar)
                        Glide.with(requireContext())
                            .load(fileUri)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .skipMemoryCache(true)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                            .circleCrop()
                            .into(profileImage)
                        Log.d("ProfileFragment", "Loaded avatar from file URI (no cache)")
                    }
                    usuario.avatar.startsWith("/") -> {
                        // Load as file path
                        val file = File(usuario.avatar)
                        Glide.with(requireContext())
                            .load(file)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .skipMemoryCache(true)
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.NONE)
                            .circleCrop()
                            .into(profileImage)
                        Log.d("ProfileFragment", "Loaded avatar from file path (no cache)")
                    }
                    else -> {
                        // Try to load as resource ID first
                        try {
                            val resourceId = usuario.avatar.toInt()
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
                                usuario.avatar, "drawable", requireContext().packageName
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
         // Mostrar el botón de admin solo si el usuario tiene rol 3 (administrador)
        val adminSlot = bottomNavBinding.adminSlot
        val goToAdminButton = bottomNavBinding.goToAdminButton

        // Inicializa como INVISIBLE para evitar salto al inflar
        goToAdminButton.visibility = View.INVISIBLE

        val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        if (!sess.hasRole(3)) {
            // Usuario no tiene rol 3: ocultar el botón de admin
            adminSlot.visibility = View.GONE
            return
        }

        // Usuario tiene rol 3: mostrar el botón y asignar listener
        adminSlot.visibility = View.VISIBLE
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

            // Force reload user data from Supabase (not cache)
            Log.d("ProfileFragment", "Profile was updated, forcing reload from server")
            loadUserData()
        }
    }

    private fun startSkeletonAnimation() {
        skeletonLayout.visibility = View.VISIBLE
        skeletonLayout.alpha = 1f
    }

    private fun stopSkeletonAnimation() {
        skeletonLayout.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                skeletonLayout.visibility = View.GONE
            }
            .start()
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }
    

    /**
     * Actualiza el badge de notificaciones no leídas
     */
    private fun updateNotificationBadge(bottomNavBinding: ComponentBottomNavigationBinding) {
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val userId = sessionManager.getUserId()
        if (userId == -1L) {
            bottomNavBinding.notificationBadge.visibility = View.GONE
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val unreadCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.countUnreadNotifications(userId)
                }
                
                if (unreadCount > 0) {
                    bottomNavBinding.notificationBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                    bottomNavBinding.notificationBadge.visibility = View.VISIBLE
                } else {
                    bottomNavBinding.notificationBadge.visibility = View.GONE
                }
            } catch (e: Exception) {
                android.util.Log.w("ProfileFragment", "Error updating notification badge", e)
                bottomNavBinding.notificationBadge.visibility = View.GONE
            }
        }
    }
}