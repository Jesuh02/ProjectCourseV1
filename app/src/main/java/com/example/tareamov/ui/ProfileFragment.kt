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
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.util.Log
import android.app.AlertDialog
import android.widget.EditText

class ProfileFragment : Fragment() {
    private lateinit var usernameTextView: TextView
    private lateinit var statusTextView: TextView
    private lateinit var subscribersTextView: TextView
    private lateinit var profileImage: CircleImageView
    private lateinit var editProfileButton: Button
    private lateinit var avatarContainer: FrameLayout
    private lateinit var skeletonLayout: FrameLayout
    private var requestedUserId: Long = -1L
    private var isViewingExternalProfile: Boolean = false
    
    // WhatsApp Views
    private var whatsappStatusText: TextView? = null
    private var whatsappStatusBadge: TextView? = null
    private var whatsappSubtitleText: TextView? = null
    private var whatsappIconView: ImageView? = null
    private var isWhatsAppChannelAvailable: Boolean = true

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

        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        requestedUserId = arguments?.getLong("userId", -1L) ?: -1L
        isViewingExternalProfile = requestedUserId > 0L && requestedUserId != sessionManager.getUserId()

        // Show immediate connection state while data is loading
        try {
            if (isViewingExternalProfile) {
                statusTextView.text = "Perfil de usuario"
                statusTextView.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            } else if (sessionManager.isLoggedIn()) {
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
            if (!isViewingExternalProfile) {
                pickImageLauncher.launch("image/*")
            }
        }
        avatarContainer.setOnClickListener {
            if (!isViewingExternalProfile) {
                pickImageLauncher.launch("image/*")
            }
        }

        // Set up navigation for bottom buttons usando ComponentBottomNavigationBinding 
        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        val bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

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

        val canUploadContent = sessionManager.hasRole(2) || sessionManager.hasRole(3) || sessionManager.hasRole(4)
        val goToHomeContainer = bottomNavBinding.goToHomeButton.parent as? View
        bottomNavBinding.goToHomeButton.visibility = if (canUploadContent) View.VISIBLE else View.GONE
        goToHomeContainer?.visibility = if (canUploadContent) View.VISIBLE else View.GONE
        if (canUploadContent) {
            bottomNavBinding.goToHomeButton.setOnClickListener {
                findNavController().navigate(R.id.action_profileFragment_to_contentUploadFragment)
            }
        } else {
            bottomNavBinding.goToHomeButton.setOnClickListener(null)
        }

        bottomNavBinding.activityButton.setOnClickListener {
            updateBottomNavSelection(bottomNavBinding, "activity")
            findNavController().navigate(R.id.action_profileFragment_to_notificacionesFragment)
        }
        bottomNavBinding.profileNavButton.setOnClickListener {
            // Ya estás en Perfil, puedes dejarlo vacío o recargar
        }

        if (!isViewingExternalProfile) {
            // Set up menu item clicks
            setupMenuItems(view)

            // Set up WhatsApp link/unlink only for admin users (role 3)
            if (userHasAdminRole()) {
                setupWhatsAppItem(view)
            } else {
                view.findViewById<LinearLayout>(R.id.whatsappItem)?.visibility = View.GONE
            }

            // Set up logout button
            setupLogoutItem(view)
        } else {
            editProfileButton.visibility = View.GONE
            view.findViewById<LinearLayout>(R.id.whatsappItem)?.visibility = View.GONE
        }

        // Load user data
        loadUserData()

        // Observe reactive cache invalidation for profile
        if (!isViewingExternalProfile) {
            viewLifecycleOwner.lifecycleScope.launch {
                com.example.tareamov.util.AppCache.profileRefresh.collect {
                    loadUserData()
                }
            }
        }

        // Set up edit profile button with animation
        if (!isViewingExternalProfile) {
            editProfileButton.setOnClickListener {
                animateButtonPress(it)
                findNavController().navigate(R.id.action_profileFragment_to_editProfileFragment)
            }
        }

        // Listeners for WhatsApp
        if (!isViewingExternalProfile) {
            setupFragmentListeners()
        }

        // Initial entrance animation
        animateEntrance()
    }

    private fun setupFragmentListeners() {
        parentFragmentManager.setFragmentResultListener("whatsapp_link_request", viewLifecycleOwner) { _, bundle ->
            val phoneNumber = bundle.getString("input")
            val normalizedPhone = normalizePhoneToE164(phoneNumber)
            if (normalizedPhone == null) {
                Toast.makeText(
                    requireContext(),
                    "Número inválido. Usa formato internacional, por ejemplo: +573001234567",
                    Toast.LENGTH_LONG
                ).show()
                return@setFragmentResultListener
            }

            if (whatsappStatusText != null && whatsappStatusBadge != null) {
                linkWhatsApp(normalizedPhone, whatsappStatusText!!, whatsappStatusBadge!!)
            }
        }

        parentFragmentManager.setFragmentResultListener("whatsapp_verify_request", viewLifecycleOwner) { _, bundle ->
            val code = bundle.getString("input")
            if (!code.isNullOrEmpty() && whatsappStatusText != null && whatsappStatusBadge != null) {
                verifyWhatsAppOtp(code, whatsappStatusText!!, whatsappStatusBadge!!)
            }
        }
    }

    private fun handleImageSelection(uri: Uri) {
        // Show initial feedback
        val initCtx = context ?: return
        Toast.makeText(initCtx, "Procesando imagen...", Toast.LENGTH_SHORT).show()
        
        lifecycleScope.launch {
            try {
                val context = context ?: return@launch
                
                // 1. Upload directly via Backend API (Logic moved to backend)
                Toast.makeText(context, "Subiendo avatar...", Toast.LENGTH_SHORT).show()
                
                BackendApiService.initialize(context)
                val result = BackendApiService.uploadAvatar(context, uri)
                
                when (result) {
                    is ApiResult.Success -> {
                        val publicUrl = result.data
                        Log.d("ProfileFragment", "Avatar uploaded: $publicUrl")
                        
                        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(context)
                        sessionManager.saveUserAvatar(publicUrl)
                        com.example.tareamov.util.AppCache.invalidateProfile()
                        
                        Glide.with(this@ProfileFragment)
                            .load(publicUrl)
                            .circleCrop()
                            .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                            .into(profileImage)
                            
                        Toast.makeText(context, "Avatar actualizado correctamente", Toast.LENGTH_SHORT).show()
                        
                        requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("profile_updated", true).apply()
                    }
                    is ApiResult.Error -> {
                        Toast.makeText(context, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error updating avatar", e)
                Toast.makeText(context ?: return@launch, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateBottomNavSelection(bottomNavBinding: ComponentBottomNavigationBinding, selected: String) {
        val ctx = context ?: return
        val activeBackground = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.nav_item_background_active)
        
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
            R.id.creatorDashboardItem to "Panel de creador",
            R.id.subscriptionsItem to "Suscripciones",
            R.id.dropsItem to "Cursos gratuitos"

        )

        menuItems.forEach { (id, message) ->
            val itemView = view.findViewById<LinearLayout>(id) ?: return@forEach
            
            // "Panel de creador" visible para roles 1, 2 y 3 (todos)
            if (id == R.id.creatorDashboardItem) {
                itemView.visibility = View.VISIBLE
            }

            // Ocultar "Panel de administración" heredado de la vista (XML)
            val adminPanel = view.findViewById<LinearLayout>(R.id.adminPanelItem)
            adminPanel?.visibility = View.GONE
            
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

    private fun setupLogoutItem(view: View) {
        val logoutItem = view.findViewById<LinearLayout>(R.id.logoutItem) ?: return
        logoutItem.setOnClickListener {
            animateButtonPress(it)
            AlertDialog.Builder(requireContext(), R.style.DarkAlertDialogTheme)
                .setTitle("Cerrar sesión")
                .setMessage("¿Estás seguro de que deseas cerrar sesión?")
                .setPositiveButton("Cerrar sesión") { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            BackendApiService.logoutAndUnregisterFCM()
                        }
                        com.example.tareamov.util.AppCache.clearAll()

                        // Clear video caches to prevent stale data from previous tenant
                        com.example.tareamov.util.VideoCacheManager.clearCache()
                        try {
                            androidx.lifecycle.ViewModelProvider(requireActivity())[com.example.tareamov.viewmodel.VideoHomeViewModel::class.java].clearFeed()
                        } catch (e: Exception) {
                            Log.e("ProfileFragment", "Error clearing video feed", e)
                        }
                        com.example.tareamov.config.TenantManager.clearTenant(requireContext())

                        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                        sessionManager.logout()
                        requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                            .edit().clear().apply()

                        // Navigate to login and clear back stack
                        try {
                            findNavController().navigate(R.id.action_global_loginFragment)
                        } catch (e: Exception) {
                            Log.e("ProfileFragment", "Error navigating to login", e)
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // WhatsApp Integration — Vincular / Desvincular
    // ═══════════════════════════════════════════════════════════

    private fun setupWhatsAppItem(view: View) {
        val whatsappItem = view.findViewById<LinearLayout>(R.id.whatsappItem) ?: return
        whatsappStatusText = view.findViewById<TextView>(R.id.whatsappItemText)
        whatsappStatusBadge = view.findViewById<TextView>(R.id.whatsappStatusBadge)
        whatsappSubtitleText = view.findViewById<TextView>(R.id.whatsappItemSubtitle)
        whatsappIconView = view.findViewById<ImageView>(R.id.whatsappIcon)

        if (whatsappStatusText == null || whatsappStatusBadge == null) return

        // Check current WhatsApp status
        checkWhatsAppStatus(whatsappStatusText!!, whatsappStatusBadge!!)

        whatsappItem.setOnClickListener { clickedView ->
            animateButtonPress(clickedView)

            viewLifecycleOwner.lifecycleScope.launch {
                val currentText = whatsappStatusText!!.text.toString()

                // Unlink must always work regardless of channel availability
                if (currentText.contains("Desvincular")) {
                    showUnlinkWhatsAppDialog(whatsappStatusText!!, whatsappStatusBadge!!)
                    return@launch
                }

                // Si el canal está marcado como no disponible, reintentar comprobación
                if (!isWhatsAppChannelAvailable) {
                    val available = fetchAndUpdateWhatsAppStatus(whatsappStatusText!!, whatsappStatusBadge!!)
                    if (!available) {
                        // Permitir al usuario continuar bajo su responsabilidad
                        AlertDialog.Builder(requireContext(), R.style.Theme_TareaMov_Dialog)
                            .setTitle("WhatsApp temporalmente no disponible")
                            .setMessage("El servicio de WhatsApp parece no estar disponible en este momento. ¿Deseas intentar vincular de todas formas?")
                            .setPositiveButton("Intentar") { _, _ ->
                                showLinkWhatsAppDialog()
                            }
                            .setNegativeButton("Cancelar", null)
                            .show()
                        return@launch
                    }
                }

                val updatedText = whatsappStatusText!!.text.toString()
                if (updatedText.contains("Desvincular")) {
                    showUnlinkWhatsAppDialog(whatsappStatusText!!, whatsappStatusBadge!!)
                } else if (updatedText.contains("Verificar")) {
                    showLinkWhatsAppDialog()
                } else {
                    showLinkWhatsAppDialog()
                }
            }
        }
    }

    private fun checkWhatsAppStatus(textView: TextView, badge: TextView) {
        // Legacy wrapper for non-suspending callers
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                fetchAndUpdateWhatsAppStatus(textView, badge)
            } catch (e: Exception) {
                isWhatsAppChannelAvailable = true
                Log.w("ProfileFragment", "Error checking WhatsApp status", e)
            }
        }
    }

    /**
     * Suspender que consulta el backend y actualiza la UI en consecuencia.
     * Retorna `true` si el canal está disponible.
     */
    private suspend fun fetchAndUpdateWhatsAppStatus(textView: TextView, badge: TextView): Boolean {
        return try {
            BackendApiService.initialize(requireContext())
            val result = withContext(Dispatchers.IO) {
                BackendApiService.getWhatsAppStatus()
            }

            when (result) {
                is ApiResult.Success -> {
                    val data = result.data
                    val isLinked = runCatching {
                        data?.get("isLinked")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                    }.getOrDefault(false)
                    val phone = runCatching {
                        data?.get("phoneNumber")?.takeIf { !it.isJsonNull }?.asString ?: ""
                    }.getOrDefault("")
                    // Provider info (may be null or different shapes). Use runCatching to avoid crashes.
                    val providerName = runCatching {
                        val p = data?.get("provider")
                        if (p == null || p.isJsonNull) return@runCatching ""
                        val obj = p.asJsonObject
                        when {
                            obj.has("name") && !obj.get("name").isJsonNull -> obj.get("name").asString
                            obj.has("provider") && !obj.get("provider").isJsonNull -> obj.get("provider").asString
                            else -> ""
                        }
                    }.getOrDefault("")

                    val providerPhone = runCatching {
                        val p = data?.get("provider")
                        if (p == null || p.isJsonNull) return@runCatching ""
                        val obj = p.asJsonObject
                        when {
                            obj.has("phoneNumber") && !obj.get("phoneNumber").isJsonNull -> obj.get("phoneNumber").asString
                            obj.has("phone") && !obj.get("phone").isJsonNull -> obj.get("phone").asString
                            else -> ""
                        }
                    }.getOrDefault("")
                    val providerConnected = runCatching {
                        val p = data?.get("provider")
                        if (p == null || p.isJsonNull) return@runCatching false
                        val obj = p.asJsonObject
                        if (obj.has("connected") && !obj.get("connected").isJsonNull) obj.get("connected").asBoolean else false
                    }.getOrDefault(false)
                    val otpPending = runCatching {
                        data?.get("otpPending")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
                    }.getOrDefault(false)
                    val channelAvailable = runCatching {
                        data?.get("channelAvailable")?.takeIf { !it.isJsonNull }?.asBoolean ?: true
                    }.getOrDefault(true)

                    isWhatsAppChannelAvailable = channelAvailable

                    if (isLinked) {
                        // Always show unlink option regardless of channel availability
                        textView.text = "Desvincular WhatsApp"
                        val linkedPhone = if (phone.isNotEmpty()) phone else providerPhone
                        val viaInfo = if (providerName.isNotEmpty()) " · via $providerName" else ""
                        val channelNote = if (!channelAvailable) " · servicio no disponible" else ""
                        whatsappSubtitleText?.text = if (linkedPhone.isNotEmpty()) "$linkedPhone$viaInfo$channelNote" else "Cuenta vinculada$viaInfo$channelNote"
                        badge.text = "VINCULADO"
                        badge.setTextColor(android.graphics.Color.parseColor("#25D366"))
                        badge.visibility = View.VISIBLE
                        whatsappIconView?.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF3B30"))
                    } else if (!channelAvailable) {
                        textView.text = "WhatsApp no disponible"
                        whatsappSubtitleText?.text = "El servicio no está disponible ahora"
                        badge.text = "NO DISPONIBLE"
                        badge.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                        badge.visibility = View.VISIBLE
                        whatsappIconView?.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#555555"))
                        return channelAvailable
                    } else if (otpPending && phone.isNotEmpty()) {
                        textView.text = "Verificar WhatsApp"
                        whatsappSubtitleText?.text = "Código enviado a $phone — toca para verificar"
                        badge.text = "PENDIENTE"
                        badge.setTextColor(android.graphics.Color.parseColor("#FF9500"))
                        badge.visibility = View.VISIBLE
                        whatsappIconView?.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9500"))
                    } else {
                        val via = if (providerName.isNotEmpty()) " · $providerName" else ""
                        textView.text = "Vincular WhatsApp"
                        if (providerConnected) {
                            whatsappSubtitleText?.text = "Disponible$via · toca para conectar"
                            badge.text = "DISPONIBLE"
                            badge.setTextColor(android.graphics.Color.parseColor("#25D366"))
                            whatsappIconView?.backgroundTintList =
                                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#25D366"))
                        } else {
                            whatsappSubtitleText?.text = "Conecta tu cuenta para recibir notificaciones"
                            badge.text = "NO VINCULADO"
                            badge.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                            whatsappIconView?.backgroundTintList =
                                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#25D366"))
                        }
                        badge.visibility = View.VISIBLE
                    }
                    channelAvailable
                }
                is ApiResult.Error -> {
                    // Keep channel available by default on errors (transient/backend auth issues)
                    isWhatsAppChannelAvailable = true
                    textView.text = "Vincular WhatsApp"
                    whatsappSubtitleText?.text = "Conecta tu cuenta para recibir notificaciones"
                    badge.text = "NO VINCULADO"
                    badge.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                    whatsappIconView?.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#25D366"))
                    true
                }
            }
        } catch (e: Exception) {
            isWhatsAppChannelAvailable = true
            Log.w("ProfileFragment", "Error fetching WhatsApp status", e)
            true
        }
    }

    private fun showLinkWhatsAppDialog() {
        WhatsAppInputDialogFragment.newInstance("whatsapp_link_request", isVerify = false)
            .show(parentFragmentManager, "whatsapp_link")
    }

    private fun normalizePhoneToE164(rawPhone: String?): String? {
        if (rawPhone.isNullOrBlank()) return null

        var normalized = rawPhone.trim().replace(Regex("[^0-9+]"), "")
        if (!normalized.startsWith("+") && normalized.length == 10 && normalized.startsWith("3")) {
            normalized = "+57$normalized"
        }
        if (normalized.startsWith("00")) {
            normalized = "+" + normalized.substring(2)
        }
        if (!normalized.startsWith("+")) {
            normalized = "+$normalized"
        }

        val e164Regex = Regex("^\\+[1-9]\\d{6,14}$")
        return if (e164Regex.matches(normalized)) normalized else null
    }

    private fun linkWhatsApp(phoneNumber: String, textView: TextView, badge: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                Toast.makeText(ctx, "Enviando código de verificación...", Toast.LENGTH_SHORT).show()
                BackendApiService.initialize(ctx)

                val result = withContext(Dispatchers.IO) {
                    BackendApiService.linkWhatsApp(phoneNumber)
                }

                when (result) {
                    is ApiResult.Success -> {
                        // Show provider response details to help debugging delivery
                        val data = result.data
                        var providerInfo = ""
                        try {
                            val prov = data?.getAsJsonObject("providerResponse")
                            if (prov != null) {
                                val pname = prov.get("provider")?.asString ?: ""
                                val presp = prov.get("response")
                                var msgId = ""
                                if (presp != null && presp.isJsonObject) {
                                    msgId = presp.asJsonObject.get("id")?.asString ?: presp.asJsonObject.get("messageId")?.asString ?: ""
                                }
                                providerInfo = if (pname.isNotEmpty()) "$pname${if (msgId.isNotEmpty()) " (id: $msgId)" else ""}" else ""
                                    // Try to read delivery status if available
                                    try {
                                        val deliveryObj = prov.get("delivery")
                                        if (deliveryObj != null && deliveryObj.isJsonObject) {
                                            val dstatus = deliveryObj.asJsonObject.get("status")?.asString
                                                ?: deliveryObj.asJsonObject.get("state")?.asString
                                                ?: deliveryObj.asJsonObject.get("delivery_status")?.asString
                                                ?: deliveryObj.asJsonObject.get("deliveryStatus")?.asString
                                                ?: null
                                            if (!dstatus.isNullOrEmpty()) {
                                                providerInfo += " — delivery: $dstatus"
                                            }
                                        }
                                    } catch (_: Exception) { /* ignore */ }
                            }
                        } catch (_: Exception) { /* ignore parse errors */ }

                        Toast.makeText(context ?: return@launch, "✅ Código enviado a WhatsApp${if (providerInfo.isNotEmpty()) ": $providerInfo" else ""}", Toast.LENGTH_SHORT).show()
                        textView.text = "Verificar WhatsApp"
                        whatsappSubtitleText?.text = "Código enviado a $phoneNumber — toca para verificar"
                        badge.text = "PENDIENTE"
                        badge.setTextColor(android.graphics.Color.parseColor("#FF9500"))
                        badge.visibility = View.VISIBLE
                        whatsappIconView?.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF9500"))
                        showVerifyOtpDialog()
                    }
                    is ApiResult.Error -> {
                        if (result.message.contains("no disponible", ignoreCase = true)) {
                            isWhatsAppChannelAvailable = false
                            textView.text = "WhatsApp no disponible"
                            badge.text = "INTENTA MÁS TARDE"
                            badge.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                            badge.visibility = View.VISIBLE
                        } else if (result.message.contains("cuenta de WhatsApp activa", ignoreCase = true) ||
                            result.message.contains("account not registered", ignoreCase = true)) {
                            badge.text = "SIN WHATSAPP"
                            badge.setTextColor(android.graphics.Color.parseColor("#E74C3C"))
                            badge.visibility = View.VISIBLE
                        }
                        Toast.makeText(context ?: return@launch, "❌ ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error linking WhatsApp", e)
                Toast.makeText(context ?: return@launch, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showVerifyOtpDialog() {
        WhatsAppInputDialogFragment.newInstance("whatsapp_verify_request", isVerify = true)
            .show(parentFragmentManager, "whatsapp_verify")
    }

    private fun verifyWhatsAppOtp(code: String, textView: TextView, badge: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                BackendApiService.initialize(ctx)
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.verifyWhatsApp(code)
                }

                when (result) {
                    is ApiResult.Success -> {
                        Toast.makeText(ctx, "✅ WhatsApp vinculado correctamente", Toast.LENGTH_LONG).show()
                        // If backend returned canUseMCP, enable admin/MCP capabilities in SessionManager
                        try {
                            val dataObj = result.data
                            val canUse = dataObj?.get("canUseMCP")?.asBoolean ?: false
                            if (canUse) {
                                val sm = com.example.tareamov.util.SessionManager.getInstance(ctx)
                                sm.setAdminStatus(true)
                                Toast.makeText(ctx, "Funciones MCP habilitadas", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            // ignore parsing errors
                        }
                        checkWhatsAppStatus(textView, badge)
                    }
                    is ApiResult.Error -> {
                        Toast.makeText(ctx, "❌ ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error verifying WhatsApp", e)
                Toast.makeText(context ?: return@launch, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showUnlinkWhatsAppDialog(textView: TextView, badge: TextView) {
        val ctx = context ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_whatsapp_unlink, null)

        // Show the linked phone if available
        val phoneChip = dialogView.findViewById<TextView>(R.id.unlinkPhoneChip)
        val currentSubtitle = whatsappSubtitleText?.text?.toString() ?: ""
        val phoneFromSubtitle = currentSubtitle.substringBefore(" ·").trim()
        if (phoneFromSubtitle.startsWith("+") || phoneFromSubtitle.matches(Regex("\\d{7,}.*"))) {
            phoneChip.text = phoneFromSubtitle
            phoneChip.visibility = View.VISIBLE
        } else {
            phoneChip.visibility = View.GONE
        }

        val dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogView)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        val dlgW = (resources.displayMetrics.widthPixels * 0.88).toInt()
        dialog.window?.setLayout(dlgW, android.view.WindowManager.LayoutParams.WRAP_CONTENT)

        dialogView.findViewById<TextView>(R.id.unlinkCancelButton).setOnClickListener {
            dialog.dismiss()
        }
        dialogView.findViewById<TextView>(R.id.unlinkConfirmButton).setOnClickListener {
            dialog.dismiss()
            unlinkWhatsApp(textView, badge)
        }

        // Entry animation
        dialogView.alpha = 0f
        dialogView.scaleX = 0.92f
        dialogView.scaleY = 0.92f
        dialog.show()
        dialogView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun applyWhatsAppUnlinkedState(textView: TextView, badge: TextView) {
        textView.text = "Vincular WhatsApp"
        badge.text = "NO VINCULADO"
        badge.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        badge.visibility = View.VISIBLE
        whatsappSubtitleText?.text = "Conecta tu cuenta para recibir notificaciones"
        whatsappIconView?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#25D366"))
        isWhatsAppChannelAvailable = true
    }

    private fun unlinkWhatsApp(textView: TextView, badge: TextView) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                BackendApiService.initialize(ctx)
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.unlinkWhatsApp()
                }

                if (!isAdded) return@launch
                when (result) {
                    is ApiResult.Success -> {
                        applyWhatsAppUnlinkedState(textView, badge)
                        Toast.makeText(context ?: return@launch, "✅ WhatsApp desvinculado", Toast.LENGTH_SHORT).show()
                    }
                    is ApiResult.Error -> {
                        Log.e("ProfileFragment", "Unlink WhatsApp error: ${result.message} (code=${result.code})")
                        Toast.makeText(context ?: return@launch, "❌ ${result.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error unlinking WhatsApp", e)
                Toast.makeText(context ?: return@launch, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private suspend fun fetchSubscriberCount(userId: Long): Long {
        if (userId <= 0L) return 0L

        return when (val result = withContext(Dispatchers.IO) {
            BackendApiService.getSubscriberCount(userId)
        }) {
            is ApiResult.Success -> result.data?.toLong() ?: 0L
            is ApiResult.Error -> {
                Log.w("ProfileFragment", "Error loading subscriber count for user $userId: ${result.message}")
                0L
            }
        }
    }

    private fun resolveSubscriberTargetUserId(profileUserId: Long, fallbackUserId: Long): Long {
        return when {
            profileUserId > 0L -> profileUserId
            fallbackUserId > 0L -> fallbackUserId
            else -> -1L
        }
    }

    private fun renderSubscriberCount(subscriberCount: Long) {
        subscribersTextView.text = try {
            getString(R.string.followers_count, subscriberCount)
        } catch (e: Exception) {
            if (subscriberCount == 1L) "1 suscriptor" else "$subscriberCount suscriptores"
        }
    }

    private fun loadUserData() {
        val context = requireContext()
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(context)
        val sessionUserId = sessionManager.getUserId()
        val cachedSubscriberOwnerId = if (isViewingExternalProfile) requestedUserId else sessionUserId
        BackendApiService.initialize(context)

        if (!isViewingExternalProfile) {
            val cached = com.example.tareamov.util.AppCache.getProfileOrStale()
            val cachedCount = if (cachedSubscriberOwnerId > 0L) {
                com.example.tareamov.util.AppCache.getSubscriberCountOrStale(cachedSubscriberOwnerId)
            } else {
                null
            }
            if (cached != null) {
                updateUI(cached, null, cachedCount ?: 0L)
            } else {
                val cachedAvatar = sessionManager.getUserAvatar()
                if (!cachedAvatar.isNullOrEmpty()) {
                    Glide.with(this@ProfileFragment)
                        .load(cachedAvatar)
                        .placeholder(R.drawable.ic_profile_placeholder)
                        .error(R.drawable.ic_profile_placeholder)
                        .circleCrop()
                        .into(profileImage)
                }
                val username = sessionManager.getUsername()
                if (!username.isNullOrEmpty()) usernameTextView.text = username
                if (cachedCount != null) renderSubscriberCount(cachedCount)
                startSkeletonAnimation()
            }
        } else {
            startSkeletonAnimation()
        }

        lifecycleScope.launch {
            try {
                if (isViewingExternalProfile && requestedUserId > 0L) {
                    val subscriberCount = fetchSubscriberCount(requestedUserId)
                    val profileResult = withContext(Dispatchers.IO) {
                        BackendApiService.getUserById(requestedUserId)
                    }
                    when (profileResult) {
                        is ApiResult.Success -> {
                            val usuario = profileResult.data
                            val resolvedUserId = resolveSubscriberTargetUserId(usuario.id, requestedUserId)
                            if (resolvedUserId > 0L) {
                                com.example.tareamov.util.AppCache.putSubscriberCount(resolvedUserId, subscriberCount)
                            }
                            updateUI(usuario, null, subscriberCount)
                        }
                        is ApiResult.Error -> {
                            Log.w("ProfileFragment", "API error loading external profile: ${profileResult.message}")
                            renderSubscriberCount(subscriberCount)
                            stopSkeletonAnimation()
                        }
                    }
                    return@launch
                }

                val fallbackSubscriberCount = if (sessionUserId > 0L) {
                    fetchSubscriberCount(sessionUserId)
                } else {
                    0L
                }
                val profileResult = withContext(Dispatchers.IO) { BackendApiService.getMyProfile() }

                when (profileResult) {
                    is ApiResult.Success -> {
                        val usuario = profileResult.data ?: run {
                            stopSkeletonAnimation(); return@launch
                        }
                        if (!usuario.avatar.isNullOrEmpty()) sessionManager.saveUserAvatar(usuario.avatar!!)

                        val resolvedUserId = resolveSubscriberTargetUserId(usuario.id, sessionUserId)
                        val subscriberCount = if (resolvedUserId == sessionUserId) {
                            fallbackSubscriberCount
                        } else {
                            fetchSubscriberCount(resolvedUserId)
                        }

                        com.example.tareamov.util.AppCache.putProfile(usuario)
                        if (resolvedUserId > 0L) {
                            com.example.tareamov.util.AppCache.putSubscriberCount(resolvedUserId, subscriberCount)
                        }

                        updateUI(usuario, null, subscriberCount)
                    }
                    is ApiResult.Error -> {
                        Log.w("ProfileFragment", "API error: ${profileResult.message}")
                        if (sessionUserId > 0L) {
                            com.example.tareamov.util.AppCache.putSubscriberCount(sessionUserId, fallbackSubscriberCount)
                            renderSubscriberCount(fallbackSubscriberCount)
                        }
                        stopSkeletonAnimation()
                    }
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error loading user data", e)
                stopSkeletonAnimation()
            }
        }
    }

    // loadLocalUserData removed — all data now fetched via BackendApiService

    private fun updateUI(usuario: Usuario?, persona: Persona?, subscriberCount: Long) {
        if (usuario == null && !isNetworkAvailable(requireContext())) {
            return
        }
        stopSkeletonAnimation()

        // Use usuario?.usuario for username if that's the correct field
        usernameTextView.text = usuario?.usuario ?: getString(R.string.default_username)

        // Update status based on session state
        try {
            if (isViewingExternalProfile) {
                statusTextView.text = "Perfil de usuario"
                statusTextView.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            } else {
                val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                if (sess.isLoggedIn()) {
                    statusTextView.text = getString(R.string.status_connected)
                    statusTextView.setTextColor(android.graphics.Color.parseColor("#2ECC71")) // green
                } else {
                    statusTextView.text = getString(R.string.status_disconnected)
                    statusTextView.setTextColor(android.graphics.Color.parseColor("#AAAAAA")) // gray
                }
            }
        } catch (e: Exception) {
            statusTextView.text = getString(R.string.status_disconnected)
        }

        // Update subscribers count
        renderSubscriberCount(subscriberCount)

        // Update profile image with usuario's avatar
        if (usuario != null && !usuario.avatar.isNullOrEmpty()) {
            val avatarUrl = usuario.avatar!!.trim()
            Log.d("ProfileFragment", "Loading avatar: $avatarUrl")
            
            try {
                if (!isAdded || isDetached) return
                
                Glide.with(this@ProfileFragment)
                    .load(avatarUrl)
                    .placeholder(R.drawable.ic_profile)
                    .error(R.drawable.ic_profile)
                    .circleCrop()
                    .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL) 
                    .into(profileImage)
                Log.d("ProfileFragment", "Loaded avatar from URL: $avatarUrl")
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error loading avatar: ${e.message}", e)
                profileImage.setImageResource(R.drawable.ic_profile)
            }
        } else {
            Log.d("ProfileFragment", "No avatar available, using default")
            profileImage.setImageResource(R.drawable.ic_profile)
        }
    }



    private fun userHasAdminRole(): Boolean {
        return try {
            val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
            sess.hasRole(3) || sess.hasRole(4)
        } catch (e: Exception) {
            Log.w("ProfileFragment", "Error checking admin role", e)
            false
        }
    }

    // Add this method to ProfileFragment class
    override fun onResume() {
        super.onResume()

        if (isViewingExternalProfile) {
            return
        }

        // Check if profile was updated
        val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val profileUpdated = sharedPrefs.getBoolean("profile_updated", false)

        if (profileUpdated) {
            // Reset the flag
            sharedPrefs.edit().putBoolean("profile_updated", false).apply()

            // Force reload user data from Supabase (not cache)
            Log.d("ProfileFragment", "Profile was updated, forcing reload from server")
            loadUserData()
        } else if (!com.example.tareamov.util.AppCache.isProfileFresh()) {
            // Cache expired — background refresh to keep data current
            Log.d("ProfileFragment", "Profile cache expired, refreshing in background")
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
                BackendApiService.initialize(requireContext())
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    BackendApiService.getUnreadNotificationCount()
                }
                
                when (result) {
                    is ApiResult.Success -> {
                        val unreadCount = result.data ?: 0
                        if (unreadCount > 0) {
                            bottomNavBinding.notificationBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                            bottomNavBinding.notificationBadge.visibility = View.VISIBLE
                        } else {
                            bottomNavBinding.notificationBadge.visibility = View.GONE
                        }
                    }
                    is ApiResult.Error -> {
                        bottomNavBinding.notificationBadge.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.w("ProfileFragment", "Error updating notification badge", e)
                bottomNavBinding.notificationBadge.visibility = View.GONE
            }
        }
    }
}