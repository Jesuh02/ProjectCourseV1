package com.example.tareamov.ui
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderScriptBlur
import android.view.ViewOutlineProvider
import android.animation.ObjectAnimator
import android.view.animation.AccelerateDecelerateInterpolator

import android.app.Activity
import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.animation.AnimatorSet
import androidx.appcompat.app.AlertDialog
import android.widget.ImageView
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
// LayoutInflater already available via android.view import above
import android.view.View
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.adapter.CourseAdapter
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Course
import com.example.tareamov.service.CloudflareR2Service
import com.example.tareamov.util.VideoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.google.android.material.bottomsheet.BottomSheetDialog
// ImageView already imported earlier

class ExploreFragment : Fragment() {
    private lateinit var videoManager: VideoManager
    private lateinit var coursesAdapter: CourseAdapter
    private val coursesList = mutableListOf<Course>()
    private var currentUsername: String? = null
    // private lateinit var searchEditText: EditText // Removed in favor of Compose state
    private lateinit var courseRepository: com.example.tareamov.repository.CourseRepository

    private lateinit var skeletonContainer: View
    private var skeletonAnimator: ObjectAnimator? = null

    // Compose State
    private val _totalCourses = androidx.compose.runtime.mutableStateOf(0)
    private val _popularCourses = androidx.compose.runtime.mutableStateOf(0)
    private val _newCourses = androidx.compose.runtime.mutableStateOf(0)
    private val _searchText = androidx.compose.runtime.mutableStateOf("")
    private val _activeFilterName = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val _isHeaderCollapsed = androidx.compose.runtime.mutableStateOf(true)

    // Store all courses for filtering and search
    private var allCoursesList = mutableListOf<Course>()
    
    // Paginación
    private var currentPage = 0
    private val pageSize = 10
    private var totalCourses = 0
    private var isLoadingCourses = false
    private var hasTriggeredLoadAtPosition5 = false // Evita cargar múltiples veces al pasar curso 5
    // When an explicit search/filter is active, prevent infinite/pagination loads
    private var isFilterActive = false

    // Variables for thumbnail change functionality
    private var currentCourseForThumbnailChange: VideoData? = null
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var thumbnailExtractor: com.example.tareamov.util.VideoThumbnailExtractor
    
    // Current filter index (0=All, 1=My Created, 2=Other, 3=Premium, 4=Free, 5=Enrolled)
    private var currentFilterIndex = 0

    // Network monitoring
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Video Preview Logic
    private val previewHandler = Handler(Looper.getMainLooper())
    private var currentPreviewPosition = -1
    private val previewRunnable = Runnable {
        startPreviewForCenterItem()
    }

    private fun startPreviewForCenterItem() {
        val view = view ?: return
        val coursesRecyclerView = view.findViewById<RecyclerView>(R.id.coursesRecyclerView) ?: return
        val layoutManager = coursesRecyclerView.layoutManager as? LinearLayoutManager ?: return
        
        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()
        
        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return
        
        // Find center item
        val recyclerViewCenter = coursesRecyclerView.height / 2
        var minDistance = Int.MAX_VALUE
        var centerPosition = -1
        
        for (i in firstVisible..lastVisible) {
            val itemView = layoutManager.findViewByPosition(i) ?: continue
            val itemCenter = (itemView.top + itemView.bottom) / 2
            val distance = Math.abs(recyclerViewCenter - itemCenter)
            if (distance < minDistance) {
                minDistance = distance
                centerPosition = i
            }
        }
        
        if (centerPosition != -1 && centerPosition != currentPreviewPosition) {
            stopCurrentPreview()
            
            val holder = coursesRecyclerView.findViewHolderForAdapterPosition(centerPosition) as? CourseAdapter.CourseViewHolder
            if (holder != null) {
                val course = coursesAdapter.getItem(centerPosition)
                if (course != null) {
                    val videoUri = course.localFilePath ?: course.videoUri
                    if (!videoUri.isNullOrEmpty()) {
                        holder.playPreview(videoUri)
                        currentPreviewPosition = centerPosition
                    }
                }
            }
        }
    }

    private fun stopCurrentPreview() {
        val view = view ?: return
        val coursesRecyclerView = view.findViewById<RecyclerView>(R.id.coursesRecyclerView) ?: return
        
        if (currentPreviewPosition != -1) {
            val holder = coursesRecyclerView.findViewHolderForAdapterPosition(currentPreviewPosition) as? CourseAdapter.CourseViewHolder
            holder?.stopPreview()
            currentPreviewPosition = -1
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_explore, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize image picker launcher for thumbnail change
        initializeImagePickerLauncher()

        // Inicializar VideoManager y CourseRepository
        videoManager = VideoManager(requireContext())
        courseRepository = com.example.tareamov.repository.CourseRepository(requireContext())
        thumbnailExtractor = com.example.tareamov.util.VideoThumbnailExtractor(requireContext())

        // Get current username from shared preferences
        currentUsername = getCurrentUsername()
        Log.d("ExploreFragment", "Initialized with currentUsername: $currentUsername")

        // Respect incoming filter from navigation (e.g., from ProfileFragment -> ExploreFragment)
        val initialFilter = arguments?.getInt("filter_index") ?: 0
        if (initialFilter != 0) {
            currentFilterIndex = initialFilter
            Log.d("ExploreFragment", "Initial filter from args: $currentFilterIndex")
        }

        // Configurar RecyclerViews para cursos
        setupRecyclerViews(view)

        // Initialize skeleton container
        skeletonContainer = view.findViewById(R.id.skeletonContainer)

        // Setup BlurView for header section
        val headerSection = view.findViewById<BlurView>(R.id.headerSection)
        val radius = 20f
        val decorView = requireActivity().window.decorView
        // Use the fragment's root view as the blur source
        val rootView = view as ViewGroup
        val windowBackground = decorView.background

        headerSection.setupWith(rootView, RenderScriptBlur(requireContext()))
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(radius)
            
        headerSection.outlineProvider = ViewOutlineProvider.BACKGROUND
        headerSection.clipToOutline = true

        // Dynamic padding adjustment for RecyclerView based on Header height to animate content position
        headerSection.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val newHeight = v.height
            val coursesRecyclerView = view.findViewById<RecyclerView>(R.id.coursesRecyclerView)
            if (coursesRecyclerView.paddingTop != newHeight) {
                coursesRecyclerView.setPadding(
                    coursesRecyclerView.paddingLeft,
                    newHeight,
                    coursesRecyclerView.paddingRight,
                    coursesRecyclerView.paddingBottom
                )
            }
        }

        // Setup Compose Header
        val composeHeader = view.findViewById<androidx.compose.ui.platform.ComposeView>(R.id.composeHeader)
        composeHeader.setContent {
            com.example.tareamov.ui.components.ExploreHeader(
                totalCourses = _totalCourses.value,
                popularCourses = _popularCourses.value,
                newCourses = _newCourses.value,
                searchText = _searchText.value,
                onSearchTextChanged = { text ->
                    _searchText.value = text
                    filterCourses(text)
                },
                activeFilterName = _activeFilterName.value,
                onFilterClicked = { showFilterOptions() },
                onClearFilter = { clearActiveFilter() },
                onPopularCoursesClicked = {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val topPopular = withContext(Dispatchers.IO) {
                            try {
                                com.example.tareamov.service.SupabaseClient.fetchTopPopularCourses(5)
                            } catch (e: Exception) {
                                emptyList<com.example.tareamov.data.entity.Course>()
                            }
                        }
                        showPopularCourses(topPopular)
                    }
                },
                onNewCoursesClicked = {
                    // Logic from old newCoursesCount click listener
                    viewLifecycleOwner.lifecycleScope.launch {
                        val currentTime = System.currentTimeMillis()
                        val thirtyDaysAgo = currentTime - (30L * 24 * 60 * 60 * 1000)
                        
                        val allAvailableCourses = try {
                            withContext(Dispatchers.IO) {
                                com.example.tareamov.service.SupabaseClient.fetchCourses()
                            }
                        } catch (e: Exception) {
                            if (allCoursesList.isNotEmpty()) allCoursesList else coursesList
                        }
                        
                        val newCoursesList = allAvailableCourses.filter { course ->
                            val courseTime = course.timestamp
                            val creationDate = course.creationDate?.let { parseDate(it) } ?: 0
                            maxOf(courseTime, creationDate) > thirtyDaysAgo
                        }
                        
                        showNewCourses(newCoursesList)
                    }
                },
                isCollapsed = _isHeaderCollapsed.value,
                onToggleCollapse = { _isHeaderCollapsed.value = !_isHeaderCollapsed.value }
            )
        }

        // Setup course observation
        setupCourseObservation()

        // Setup network monitoring to retry loading when internet returns
        setupNetworkMonitoring()

        // Mostrar estadísticas inmediatamente (agregados server-side con fallback offline)
        fetchAndDisplayCourseStats()

    // Cargar los cursos (forzar fetch remoto al entrar en el fragment)
    loadCourses(forceRemote = true)

        // Panel de navegación inferior usando ComponentBottomNavigationBinding
        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        val bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        // Resaltar solo el icono de explorar en morado (ahora con fondo pill)
        val activeBackground = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.nav_item_background_active)
        bottomNavBinding.exploreIconContainer.background = activeBackground
        
        // Ensure icons are white
        val whiteColor = android.graphics.Color.WHITE
        bottomNavBinding.homeIconImageView.setColorFilter(whiteColor)
        bottomNavBinding.exploreIconImageView.setColorFilter(whiteColor)
        bottomNavBinding.activityIconImageView.setColorFilter(whiteColor)
        bottomNavBinding.profileIconImageView.setColorFilter(whiteColor)

        setupAdminButton()
        setupBottomNavigation(bottomNavBinding)
        updateNotificationBadge(bottomNavBinding)
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
                android.util.Log.w("ExploreFragment", "Error updating notification badge", e)
                bottomNavBinding.notificationBadge.visibility = View.GONE
            }
        }
    }

    private fun setupAdminButton() {
        val bottomNavView: View = view?.findViewById(R.id.bottomNavigation) ?: return
        val bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        val adminSlot = bottomNavBinding.adminSlot
        val goToAdminButton = bottomNavBinding.goToAdminButton

        // Inicializa como INVISIBLE para evitar salto al inflar
        goToAdminButton.visibility = View.INVISIBLE

        val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        
        // Verificar específicamente el rol 3
        if (!sess.hasRole(3)) {
            // Ocultar el slot antes del render para que no quede hueco visible
            adminSlot.visibility = View.GONE
            return
        }

        // Usuario tiene rol 3: mostrar botón y asignar listener
        adminSlot.visibility = View.VISIBLE
        goToAdminButton.visibility = View.VISIBLE
        goToAdminButton.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_homeFragment)
        }
    }

    private fun updateBottomNavSelection(bottomNavBinding: ComponentBottomNavigationBinding, selected: String) {
        val activeBackground = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.nav_item_background_active)
        
        bottomNavBinding.homeIconContainer.background = if (selected == "home") activeBackground else null
        bottomNavBinding.exploreIconContainer.background = if (selected == "explore") activeBackground else null
        bottomNavBinding.activityIconContainer.background = if (selected == "activity") activeBackground else null
        bottomNavBinding.profileIconContainer.background = if (selected == "profile") activeBackground else null
    }

    private fun setupBottomNavigation(bottomNavBinding: ComponentBottomNavigationBinding) {
        bottomNavBinding.homeNavLayout.setOnClickListener {
            updateBottomNavSelection(bottomNavBinding, "home")
            findNavController().navigate(R.id.action_exploreFragment_to_videoHomeFragment)
        }
        bottomNavBinding.exploreButton.setOnClickListener {
            // Ya estás en Explorar, puedes dejarlo vacío o recargar
        }
        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_contentUploadFragment)
        }
        bottomNavBinding.activityButton.setOnClickListener {
            updateBottomNavSelection(bottomNavBinding, "activity")
            findNavController().navigate(R.id.action_exploreFragment_to_notificacionesFragment)
        }
        bottomNavBinding.profileNavButton.setOnClickListener {
            updateBottomNavSelection(bottomNavBinding, "profile")
            findNavController().navigate(R.id.action_exploreFragment_to_profileFragment)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun setupNetworkMonitoring() {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d("ExploreFragment", "Network available, retrying loadCourses if empty")
                // Use lifecycleScope to ensure we're on main thread and fragment is active
                lifecycleScope.launch(Dispatchers.Main) {
                    if (coursesList.isEmpty()) {
                        loadCourses(forceRemote = true)
                    }
                }
            }
        }
        
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
            
        try {
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.e("ExploreFragment", "Error registering network callback", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-register network callback if it was unregistered or null
        if (networkCallback == null) {
            setupNetworkMonitoring()
        }
        
        // If list is empty, ensure skeleton is visible immediately and try to load
        if (coursesList.isEmpty()) {
            startSkeletonAnimation()
            // Try to load regardless of connection state check - let the loader handle the error/skeleton persistence
            loadCourses(forceRemote = true)
        }
    }

    override fun onPause() {
        super.onPause()
        // Unregister network callback to avoid leaks
        networkCallback?.let {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Ensure callback is unregistered
        networkCallback?.let {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }

    // Setup real-time observation of course changes - DISABLED to force Supabase loading
    private fun setupCourseObservation() {
        // Observation disabled - we now load all courses directly from Supabase
        // This avoids showing stale Room data (only 7 courses) instead of full Supabase data (104+ courses)
        Log.d("ExploreFragment", "Course observation from Room DISABLED - using Supabase direct fetch")
    }

    // Generate thumbnails preventively for videos without them - REMOVED (Obsolete)
    // private fun generatePreventiveThumbnails(videoDataList: List<VideoData>) { ... }

    // Convert Course to VideoData for adapter compatibility
    private suspend fun convertCourseToVideoData(course: Course): VideoData {
        // Fetch creator username from user_id
        val creatorUsername = withContext(Dispatchers.IO) {
            com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId)
        } ?: ""
        
        if (creatorUsername.isEmpty()) {
            Log.w("ExploreFragment", "convertCourseToVideoData: course.id=${course.id} creatorUserId=${course.creatorUserId} has no username, using empty")
        }
        
        return VideoData(
            id = course.id,
            username = creatorUsername,
            description = course.description ?: "", // Asegurar que no sea null
            title = course.title,
            videoUriString = course.videoUri ?: "",
            localFilePath = course.localFilePath,
            timestamp = course.timestamp,
            isPaid = course.isPremium,
            thumbnailUri = course.thumbnailUri,
            price = if (course.price > 0.0) course.price else null
        )
    }

    private fun getCurrentUsername(): String? {
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val username = sessionManager.getUsername()
        Log.d("ExploreFragment", "Current username from SessionManager: $username")
        return username
    }

    /**
     * Show custom dark themed Toast message
     */
    private fun showDarkToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val toast = Toast.makeText(requireContext(), message, duration)
        val view = toast.view
        view?.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.dark_toast_background)
        view?.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(android.graphics.Color.WHITE)
            textSize = 16f
            setPadding(32, 16, 32, 16)
        }
        toast.show()
    }

    /**
     * Show a short floating message dialog with a professional animation
     * Uses `bg_header_gradient` as background (set in layout)
     */
    private fun showFloatingMessage(message: String, durationMs: Long = 1800L) {
        val dialog = Dialog(requireContext())
        val view = layoutInflater.inflate(R.layout.dialog_floating_message, null)
        val messageTv = view.findViewById<TextView>(R.id.floatingMessageTextView)
        val iconIv = view.findViewById<ImageView>(R.id.floatingIconImageView)
        messageTv.text = message

        dialog.setContentView(view)
        dialog.setCancelable(false)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
        val params = dialog.window?.attributes
        params?.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        // small offset from top to avoid overlap with status bar
        params?.y = (48 * resources.displayMetrics.density).toInt()
        dialog.window?.attributes = params
        dialog.show()

        // Entrance animation
        view.alpha = 0f
        view.scaleX = 0.92f
        view.scaleY = 0.92f
        val alphaIn = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
        val sx = ObjectAnimator.ofFloat(view, "scaleX", 0.92f, 1f)
        val sy = ObjectAnimator.ofFloat(view, "scaleY", 0.92f, 1f)
        val inSet = AnimatorSet()
        inSet.playTogether(alphaIn, sx, sy)
        inSet.duration = 360
        inSet.interpolator = AccelerateDecelerateInterpolator()
        inSet.start()

        // Dismiss with exit animation after duration
        Handler(Looper.getMainLooper()).postDelayed({
            val alphaOut = ObjectAnimator.ofFloat(view, "alpha", 1f, 0f)
            val sxOut = ObjectAnimator.ofFloat(view, "scaleX", 1f, 0.96f)
            val syOut = ObjectAnimator.ofFloat(view, "scaleY", 1f, 0.96f)
            val outSet = AnimatorSet()
            outSet.playTogether(alphaOut, sxOut, syOut)
            outSet.duration = 260
            outSet.interpolator = AccelerateDecelerateInterpolator()
            outSet.start()
            outSet.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    try { dialog.dismiss() } catch (_: Exception) {}
                }
            })
        }, durationMs)
    }

    /**
     * Handle subscription button click
     */
    private fun handleSubscriptionClick(course: Course, isCurrentlySubscribed: Boolean) {
        if (currentUsername == null) {
            showDarkToast("⚠️ Debes iniciar sesión para suscribirte")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get current user ID
                val currentUserId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
                }

                if (currentUserId == null) {
                    showDarkToast("❌ Error: No se pudo obtener tu ID de usuario")
                    return@launch
                }

                val creatorUserId = course.creatorUserId
                
                if (currentUserId == creatorUserId) {
                    showDarkToast("❌ No puedes suscribirte a tu propio curso")
                    return@launch
                }
                
                val db = AppDatabase.getDatabase(requireContext())
                
                if (isCurrentlySubscribed) {
                    // UNSUBSCRIBE - Delete from Supabase first (authoritative)
                    val unsubscribeSuccess = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.deleteSubscriptionFromSupabase(currentUserId, creatorUserId)
                    }
                    
                    if (unsubscribeSuccess) {
                        // Also remove from local database
                        db.subscriptionDao().unsubscribeFromCreator(currentUserId, creatorUserId)
                        
                        showDarkToast("✅ Te has desuscrito correctamente")
                        Log.d("ExploreFragment", "User $currentUserId unsubscribed from creator $creatorUserId")
                    } else {
                        showDarkToast("❌ Error al desuscribirse, intenta de nuevo")
                    }
                } else {
                    // SUBSCRIBE - Insert to Supabase first (authoritative)
                    val subscribeSuccess = withContext(Dispatchers.IO) {
                        val subscription = com.example.tareamov.data.entity.Subscription(
                            subscriberId = currentUserId,
                            creatorId = creatorUserId,
                            subscriptionDate = System.currentTimeMillis()
                        )
                        com.example.tareamov.service.SupabaseClient.insertSubscriptionToSupabase(subscription)
                    }
                    
                    if (subscribeSuccess) {
                        // Also save to local database
                        db.subscriptionDao().subscribeToCreator(currentUserId, creatorUserId)
                        
                        showDarkToast("🎉 ¡Te has suscrito exitosamente!")
                        Log.d("ExploreFragment", "User $currentUserId subscribed to creator $creatorUserId")
                    } else {
                        showDarkToast("❌ Error al suscribirse, intenta de nuevo")
                    }
                }
                
                // Refresh the courses list to update subscription states
                loadCourses(forceRemote = true)
                
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error handling subscription", e)
                showDarkToast("❌ Error al procesar la suscripción: ${e.message}")
            }
        }
    }
    
    /**
     * Handle enrollment click - Create initial progress record when student enrolls
     */
    private fun handleEnrollmentClick(course: Course) {
        if (currentUsername == null) {
            showDarkToast("¡Debes iniciar sesión para inscribirte!")
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get creator username from user_id
                val creatorUsername = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId)
                }
                
                if (creatorUsername == null) {
                    showDarkToast("❌ Error: No se pudo obtener el nombre del creador")
                    return@launch
                }
                
                if (currentUsername == creatorUsername) {
                    showDarkToast("No puedes inscribirte en tu propio curso")
                    return@launch
                }
        
        // Block enrollment for paid courses (price > 0)
        if (course.price > 0) {
            showDarkToast("❌ Este es un curso de pago. Debes realizar el pago para acceder.", Toast.LENGTH_LONG)
            return@launch
        }
        
                val db = AppDatabase.getDatabase(requireContext())
                
                // Get user ID from username
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
                }
                
                if (userId == null) {
                    showDarkToast("Error: Usuario no encontrado")
                    Log.e("ExploreFragment", "Failed to get user ID for username: $currentUsername")
                    return@launch
                }
                
                // Check if already enrolled
                val existingProgreso = withContext(Dispatchers.IO) {
                    db.progresoEstudianteDao().getProgreso(userId, course.id)
                }
                
                if (existingProgreso != null) {
                    showDarkToast("Ya estás inscrito en este curso")
                    return@launch
                }
                
                // Ensure course exists in local DB before creating progress
                withContext(Dispatchers.IO) {
                    val existingCourse = db.courseDao().getCourseById(course.id)
                    if (existingCourse == null) {
                        Log.d("ExploreFragment", "Course not in local DB, inserting: ${course.title}")
                        db.courseDao().insertCourse(course)
                    }
                }
                
                // Get total tasks for this course
                val topics = withContext(Dispatchers.IO) {
                    db.topicDao().getTopicsByCourse(course.id)
                }
                
                val topicIds = topics.map { it.id }
                val totalTasks = if (topicIds.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        db.taskDao().getTasksByTopicIds(topicIds).size
                    }
                } else {
                    0
                }
                
                // Create initial progress record
                val progreso = com.example.tareamov.data.entity.ProgresoEstudiante(
                    usuarioEstudiante = userId,
                    cursoId = course.id,
                    tareasCompletadas = 0,
                    tareasTotales = totalTasks,
                    porcentajeProgreso = 0f,
                    calificacionPonderada = null,
                    promedio = null,
                    estado = "Perdido",
                    ultimaCalculadaEn = System.currentTimeMillis()
                )
                
                // Save locally
                withContext(Dispatchers.IO) {
                    db.progresoEstudianteDao().insertProgreso(progreso)
                }
                
                Log.d("ExploreFragment", "✅ Progress record created locally for $currentUsername in course ${course.id}")
                
                // Sync to Supabase
                val syncRepo = getSyncRepository()
                val syncSuccess = withContext(Dispatchers.IO) {
                    syncRepo.syncProgresoToSupabase(progreso)
                }
                
                if (syncSuccess) {
                    Log.d("ExploreFragment", "✅ Enrollment synced to Supabase for $currentUsername in course ${course.id}")
                    showDarkToast("✅ ¡Inscrito exitosamente en ${course.title}!")
                    
                    // Update course enrollment count in local Course table
                    withContext(Dispatchers.IO) {
                        val db = AppDatabase.getDatabase(requireContext())
                        val updatedCourse = course.copy(enrollmentCount = course.enrollmentCount + 1)
                        db.courseDao().updateCourse(updatedCourse)
                    }
                    
                    // Refresh the adapter to update button state and hide enrollment section
                    coursesAdapter.notifyDataSetChanged()
                } else {
                    Log.w("ExploreFragment", "⚠️ Failed to sync enrollment to Supabase, but saved locally")
                    showDarkToast("✅ ¡Inscrito localmente en ${course.title}!")
                    coursesAdapter.notifyDataSetChanged()
                }
                
            } catch (e: Exception) {
                Log.e("ExploreFragment", "❌ Error enrolling in course", e)
                showDarkToast("❌ Error al inscribirse: ${e.message}")
            }
        }
    }

    private fun setupRecyclerViews(view: View) {
        // Setup for "My Courses" RecyclerView
        val coursesRecyclerView = view.findViewById<RecyclerView>(R.id.coursesRecyclerView)
        coursesRecyclerView.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
        // coursesRecyclerView.setHasFixedSize(true) // Removed to fix lint error: InvalidSetHasFixedSize
        coursesRecyclerView.setItemViewCacheSize(100)

        // Add scroll listener for video preview
        coursesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    previewHandler.postDelayed(previewRunnable, 1000)
                } else {
                    previewHandler.removeCallbacks(previewRunnable)
                    stopCurrentPreview()
                }
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (Math.abs(dy) > 0) {
                    previewHandler.removeCallbacks(previewRunnable)
                    stopCurrentPreview()
                }
            }
        })

        Log.d("ExploreFragment", "Setting up adapter with currentUsername: $currentUsername")

        coursesAdapter = CourseAdapter(
            requireContext(),
            coursesList,
            onCourseClickListener = { course ->
                navigateToCourseDetail(course)
            },
            currentUsername = currentUsername,
            onSubscriptionClickListener = { course, isCurrentlySubscribed ->
                handleSubscriptionClick(course, isCurrentlySubscribed)
            },
            onEditClickListener = { course ->
                val bundle = Bundle().apply {
                    putLong("courseId", course.id)
                    putBoolean("isEditing", true)
                }
                findNavController().navigate(R.id.action_exploreFragment_to_courseCreationFragment, bundle)
            },
            onDeleteClickListener = { course ->
                // Custom floating confirmation dialog with animated entrance/exit
                val dlg = Dialog(requireContext())
                val dlgView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
                // Apply gradient background (bg_header_gradient) to the dialog root to avoid white box
                try {
                    dlgView.setBackgroundResource(R.drawable.bg_liquid_glass)
                    dlgView.clipToOutline = true
                } catch (t: Throwable) {
                    Log.w("ExploreFragment", "Failed to set bg_liquid_glass on dlgView", t)
                }
                val titleTv = dlgView.findViewById<TextView>(R.id.confirmDeleteTitle)
                val msgTv = dlgView.findViewById<TextView>(R.id.confirmDeleteMessage)
                val btnCancel = dlgView.findViewById<TextView>(R.id.cancelDeleteButton)
                val btnDelete = dlgView.findViewById<TextView>(R.id.confirmDeleteButton)

                titleTv.text = "Eliminar curso"
                msgTv.text = "¿Deseas eliminar el curso \"${course.title}\"? Esta acción no se puede deshacer."

                dlg.setContentView(dlgView)
                dlg.setCancelable(true)
                dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
                dlg.window?.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT)
                dlg.window?.attributes = dlg.window?.attributes?.apply { gravity = Gravity.CENTER }
                // Dim background behind dialog for stronger visual focus
                dlg.window?.setDimAmount(0.75f)
                // Ensure text contrast when using the gradient background
                try {
                    titleTv.setTextColor(android.graphics.Color.WHITE)
                    msgTv.setTextColor(android.graphics.Color.WHITE)
                    btnCancel.setTextColor(android.graphics.Color.WHITE)
                    btnDelete.setTextColor(android.graphics.Color.WHITE)
                } catch (_: Exception) {}

                // Entrance animation: slide + fade + subtle pop
                dlgView.alpha = 0f
                dlgView.translationY = -24f * resources.displayMetrics.density
                dlgView.scaleX = 0.96f
                dlgView.scaleY = 0.96f
                val alphaIn = ObjectAnimator.ofFloat(dlgView, "alpha", 0f, 1f)
                val transIn = ObjectAnimator.ofFloat(dlgView, "translationY", dlgView.translationY, 0f)
                val sx = ObjectAnimator.ofFloat(dlgView, "scaleX", 0.96f, 1f)
                val sy = ObjectAnimator.ofFloat(dlgView, "scaleY", 0.96f, 1f)
                AnimatorSet().apply {
                    playTogether(alphaIn, transIn, sx, sy)
                    duration = 360
                    interpolator = AccelerateDecelerateInterpolator()
                    start()
                }

                fun dismissWithAnimation(onEnd: (() -> Unit)? = null) {
                    val aOut = ObjectAnimator.ofFloat(dlgView, "alpha", 1f, 0f)
                    val transOut = ObjectAnimator.ofFloat(dlgView, "translationY", 0f, -12f * resources.displayMetrics.density)
                    val sxOut = ObjectAnimator.ofFloat(dlgView, "scaleX", 1f, 0.98f)
                    val syOut = ObjectAnimator.ofFloat(dlgView, "scaleY", 1f, 0.98f)
                    AnimatorSet().apply {
                        playTogether(aOut, transOut, sxOut, syOut)
                        duration = 260
                        interpolator = AccelerateDecelerateInterpolator()
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                try { dlg.dismiss() } catch (_: Exception) {}
                                onEnd?.invoke()
                            }
                        })
                        start()
                    }
                }

                btnCancel.setOnClickListener { dismissWithAnimation() }

                btnDelete.setOnClickListener {
                    btnDelete.isEnabled = false
                    btnCancel.isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val creatorUsername = withContext(Dispatchers.IO) {
                                com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId)
                            } ?: ""

                            deleteCourseFromTable(course.id, creatorUsername) {
                                dismissWithAnimation { showFloatingMessage("Curso eliminado") }
                            }
                        } catch (e: Exception) {
                            Log.e("ExploreFragment", "Error deleting course", e)
                            showDarkToast("❌ Error al eliminar el curso")
                            btnDelete.isEnabled = true
                            btnCancel.isEnabled = true
                        }
                    }
                }

                dlg.show()
            },
            onEnrollClickListener = { course -> handleEnrollmentClick(course) },
            onCreatorClickListener = { username ->
                val bundle = Bundle().apply { putString("username", username) }
                findNavController().navigate(R.id.action_exploreFragment_to_userProfileViewFragment, bundle)
            }
        )
        coursesRecyclerView.adapter = coursesAdapter

        // Resolve current user id once and pass to adapter (no blocking per row)
        if (currentUsername != null) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val uid = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
                    withContext(Dispatchers.Main) {
                        coursesAdapter.setCurrentUserId(uid)
                        Log.d("ExploreFragment", "Cached current user id in adapter: $uid")
                    }
                } catch (t: Throwable) {
                    Log.w("ExploreFragment", "Failed to resolve current user id for $currentUsername", t)
                }
            }
        }

        // Agregar ScrollListener para paginación
        coursesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                if (layoutManager != null) {
                    val totalItemCount = layoutManager.itemCount
                    val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()

                    // Cargar más cuando el usuario vea el 5to desde el final
                    if (!isLoadingCourses && totalItemCount > 0 && lastVisibleItemPosition >= totalItemCount - 5) {
                        // mark trigger to avoid duplicate calls while loading
                        if (!hasTriggeredLoadAtPosition5) {
                            hasTriggeredLoadAtPosition5 = true
                            loadMoreCourses()
                        }
                    }
                }
            }
        })
    }

    private fun navigateToCourseDetail(course: Course) {
        lifecycleScope.launch {
            // Check if current user is the course creator by comparing user IDs
            val isCreator = if (currentUsername != null) {
                val currentUserId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
                }
                currentUserId != null && currentUserId == course.creatorUserId
            } else {
                false
            }

            val bundle = Bundle().apply {
                putLong("courseId", course.id)
                putString("courseName", course.title)
                putBoolean("isCreator", isCreator)
            }
            findNavController().navigate(R.id.action_exploreFragment_to_courseDetailFragment, bundle)
        }
    }

    // Method to add new course to Course table
    private fun addCourseToTable(videoData: VideoData) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val course = courseRepository.convertVideoDataToCoursePublic(videoData)
                withContext(Dispatchers.IO) {
                    courseRepository.saveCourse(course)
                }
                Log.d("ExploreFragment", "Course added to Course table: ${course.title}")
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error adding course to table", e)
            }
        }
    }

    // Method to update course in Course table - Only for course creators
    private fun updateCourseInTable(videoData: VideoData) {
        // Check if current user is the creator before allowing update
        if (currentUsername != null && currentUsername == videoData.username) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val course = courseRepository.convertVideoDataToCoursePublic(videoData)
                    withContext(Dispatchers.IO) {
                        courseRepository.updateCourse(course)
                    }
                    Log.d("ExploreFragment", "Course updated in Course table: ${course.title}")
                } catch (e: Exception) {
                    Log.e("ExploreFragment", "Error updating course in table", e)
                }
            }
        } else {
            Log.w("ExploreFragment", "Update denied: User is not the course creator")
        }
    }

    // Method to delete course - Only for course creators
    private fun deleteCourseFromTable(courseId: Long, creatorUsername: String, onDeleted: (() -> Unit)? = null) {
        // Check if current user is the creator before allowing deletion
        if (currentUsername != null && currentUsername == creatorUsername) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Show loading indicator
                    Toast.makeText(requireContext(), "Eliminando curso...", Toast.LENGTH_SHORT).show()

                    withContext(Dispatchers.IO) {
                        // Delete from both Course table and VideoData table for complete cleanup
                        courseRepository.deleteCourseById(courseId)

                        // Also delete from VideoData table to ensure complete removal
                        val videoData = courseRepository.getVideoById(courseId)
                        if (videoData != null) {
                            courseRepository.deleteVideo(videoData)
                            Log.d("ExploreFragment", "Course also deleted from VideoData table: $courseId")
                        }

                        // Clean up any related thumbnails - REMOVED (Obsolete)
                        // val thumbnailManager = com.example.tareamov.util.ThumbnailManager(requireContext())
                        // thumbnailManager.deleteThumbnail(courseId)
                    }

                    // Update UI on main thread
                    withContext(Dispatchers.Main) {
                        // Remove from local lists immediately for faster UI response
                        val courseToRemove = allCoursesList.find { it.id == courseId }
                        if (courseToRemove != null) {
                            allCoursesList.remove(courseToRemove)
                            coursesList.remove(courseToRemove)
                            if (::coursesAdapter.isInitialized) {
                                coursesAdapter.updateCourses(coursesList)
                            } else {
                                Log.w("ExploreFragment", "coursesAdapter not initialized when removing course")
                            }
                        }

                        // Show success message
                        Toast.makeText(requireContext(), "Curso eliminado exitosamente", Toast.LENGTH_SHORT).show()
                        Log.d("ExploreFragment", "Course deleted successfully: $courseId")

                        // Invoke callback after successful deletion (UI thread)
                        try { onDeleted?.invoke() } catch (t: Throwable) { /* ignore */ }

                        // Trigger remote delete (non-blocking)
                        try {
                            val syncRepo = getSyncRepository()
                            syncRepo.deleteCourseRemoteById(courseId)
                        } catch (e: Exception) {
                            Log.w("ExploreFragment", "Failed to trigger remote delete for course id=$courseId", e)
                        }

                        // Reload courses to ensure consistency
                        loadCourses()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Error al eliminar el curso: ${e.message}", Toast.LENGTH_LONG).show()
                        Log.e("ExploreFragment", "Error deleting course from table: $courseId", e)
                    }
                }
            }
        } else {
            Toast.makeText(requireContext(), "Solo el creador puede eliminar el curso", Toast.LENGTH_SHORT).show()
            Log.w("ExploreFragment", "Deletion denied: User '$currentUsername' is not the course creator '$creatorUsername'")
        }
    }

    // Method to edit course - Only for course creators
    private fun editCourse(course: VideoData) {
        // Check if current user is the creator before allowing edit
        if (currentUsername != null && currentUsername == course.username) {
            Log.d("ExploreFragment", "Edit course requested: ${course.title}")

            // Create edit dialog with dark theme
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_course, null)
            val titleEdit = dialogView.findViewById<android.widget.EditText>(R.id.editCourseTitle)
            val descEdit = dialogView.findViewById<android.widget.EditText>(R.id.editCourseDescription)

            // Set current values
            titleEdit.setText(course.title)
            descEdit.setText(course.description)

            // Set text color to white for better visibility in dark theme
            titleEdit.setTextColor(android.graphics.Color.WHITE)
            descEdit.setTextColor(android.graphics.Color.WHITE)
            titleEdit.setHintTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            descEdit.setHintTextColor(android.graphics.Color.parseColor("#CCCCCC"))

            // Create dialog with dark theme
            val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(
                androidx.appcompat.view.ContextThemeWrapper(requireContext(), R.style.DarkAlertDialogTheme)
            )

            dialogBuilder
                .setTitle("✏️ Editar Curso")
                    .setView(dialogView as View)
                .setPositiveButton("💾 Guardar") { _, _ ->
                    val newTitle = titleEdit.text.toString().trim()
                    val newDesc = descEdit.text.toString().trim()

                    if (newTitle.isNotEmpty()) {
                        updateCourseDetails(course, newTitle, newDesc)
                        Toast.makeText(requireContext(), "✅ Curso actualizado: $newTitle", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "❌ El título no puede estar vacío", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("❌ Cancelar", null)

            val dialog = dialogBuilder.create()

            // Apply additional dark theme styling
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawableResource(R.drawable.dark_dialog_background)
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                    setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Green for save
                    textSize = 16f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                    setTextColor(android.graphics.Color.parseColor("#A259FF")) // Purple for cancel
                    textSize = 16f
                }
            }

            dialog.show()
        } else {
            Toast.makeText(requireContext(), "❌ Solo el creador puede editar el curso", Toast.LENGTH_SHORT).show()
            Log.w("ExploreFragment", "Edit denied: User is not the course creator")
        }
    }

    // Method to update course details
    private fun updateCourseDetails(course: VideoData, newTitle: String, newDescription: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updatedCourse = course.copy(title = newTitle, description = newDescription)

                withContext(Dispatchers.IO) {
                    // Update in VideoData table
                    videoManager.updateVideo(updatedCourse)

                    // Update in Course table
                    updateCourseInTable(updatedCourse)
                }

                Log.d("ExploreFragment", "Course updated: $newTitle")
                // Immediately update in-memory lists and adapter so UI reflects the change without reload
                try {
                    // Update allCoursesList entries
                    val courseFromVideoData = courseRepository.convertVideoDataToCoursePublic(updatedCourse)
                    val idxAll = allCoursesList.indexOfFirst { it.id == updatedCourse.id }
                    if (idxAll >= 0) {
                        allCoursesList[idxAll] = courseFromVideoData
                    }

                    // Update coursesList (filtered list currently shown)
                    val idxFiltered = coursesList.indexOfFirst { it.id == updatedCourse.id }
                    if (idxFiltered >= 0) {
                        coursesList[idxFiltered] = courseFromVideoData
                    }

                    // Notify adapter of the immediate change
                    if (::coursesAdapter.isInitialized) {
                        coursesAdapter.updateCourses(coursesList)
                    }
                } catch (e: Exception) {
                    Log.w("ExploreFragment", "Failed to apply immediate UI update after editing course", e)
                }

                // Show a short progress indicator while we sync remotely (non-blocking)
                val progressToast = Toast.makeText(requireContext(), "Sincronizando cambios...", Toast.LENGTH_SHORT)
                progressToast.show()

                // After local update, attempt to upsert to Supabase (non-blocking)
                try {
                    val syncRepo = getSyncRepository()
                    // Convert VideoData to Course and upsert remotely
                    val courseEntity = courseRepository.convertVideoDataToCoursePublic(updatedCourse)
                    Log.d("ExploreFragment", "Triggering upsertCourseToSupabase for courseEntity.id=${courseEntity.id} title='${courseEntity.title}'")
                    syncRepo.upsertCourseToSupabase(courseEntity)
                } catch (e: Exception) {
                    Log.w("ExploreFragment", "Failed to trigger remote upsert for course update", e)
                } finally {
                    // Dismiss the transient toast by showing a quick confirmation toast
                    Toast.makeText(requireContext(), "Cambio guardado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error updating course details", e)
            }
        }
    }

    // Method to check if current user can perform CRUD operations on a course
    private fun canUserModifyCourse(course: VideoData): Boolean {
        val canModify = currentUsername != null && currentUsername == course.username
        Log.d("ExploreFragment", "Can user modify course '${course.title}'? $canModify (Current: '$currentUsername', Course Creator: '${course.username}')")
        return canModify
    }

    // Method to check if current user can perform CRUD operations on a Course entity
    private suspend fun canUserModifyCourse(course: Course): Boolean {
        if (currentUsername == null) return false
        
        val currentUserId = withContext(Dispatchers.IO) {
            com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
        }
        
        val canModify = currentUserId != null && currentUserId == course.creatorUserId
        Log.d("ExploreFragment", "Can user modify course entity '${course.title}'? $canModify (Current user_id: '$currentUserId', Course creator_user_id: '${course.creatorUserId}')")
        return canModify
    }

    // Get courses created by current user only
    private suspend fun getUserOwnedCourses(): List<Course> {
        if (currentUsername == null) return emptyList()
        
        val currentUserId = withContext(Dispatchers.IO) {
            com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
        }
        
        return try {
            if (currentUserId == null) return emptyList()
            // Prefer authoritative server-side list of courses created by this user
            withContext(Dispatchers.IO) {
                com.example.tareamov.service.SupabaseClient.fetchCoursesByCreatorUserId(currentUserId)
            }
        } catch (e: Exception) {
            // Fallback to locally loaded list when network or server fails
            if (currentUserId != null) {
                allCoursesList.filter { it.creatorUserId == currentUserId }
            } else {
                emptyList()
            }
        }
    }

    // Get courses NOT created by current user (for viewing only)
    private suspend fun getOtherUsersCourses(): List<Course> {
        // Prefer authoritative server-side data: fetch all courses and exclude those
        // created by the current session user. This avoids relying on the locally
        // loaded page which may not contain all items and may include the user's
        // own courses even when the intent is to show "others".
        val session = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val sessionUserId = session.getUserId()

        return try {
            val serverCourses = withContext(Dispatchers.IO) {
                com.example.tareamov.service.SupabaseClient.fetchCourses()
            }
            if (sessionUserId > 0L) {
                serverCourses.filter { it.creatorUserId != sessionUserId }
            } else {
                // If no active session, return all server courses (can't distinguish owner)
                serverCourses
            }
        } catch (e: Exception) {
            // Fallback: filter the locally loaded list if network or server call fails
            val fallbackUserId = withContext(Dispatchers.IO) {
                if (currentUsername != null) com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!) else null
            }
            if (fallbackUserId != null) {
                allCoursesList.filter { it.creatorUserId != fallbackUserId }
            } else {
                allCoursesList
            }
        }
    }

    // Method to sync any changes from RecyclerView to Course table
    private fun syncCoursesToTable() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    // Use the repository's automatic sync method
                    courseRepository.syncVideoDataToCoursesTable()
                }
                Log.d("ExploreFragment", "Course sync completed")
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error syncing courses to table", e)
            }
        }
    }

    // Public method to force sync - can be called from outside (only sync user's own courses)
    fun forceSyncToCoursesTable() {
        if (currentUsername != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        // Only sync courses created by current user
                        val userCourses = getUserOwnedCourses()
                        userCourses.forEach { course ->
                            if (canUserModifyCourse(course)) {
                                courseRepository.updateCourse(course)
                            }
                        }
                    }
                    Log.d("ExploreFragment", "User-specific course sync completed")
                } catch (e: Exception) {
                    Log.e("ExploreFragment", "Error in user-specific course sync", e)
                }
            }
        } else {
            Log.w("ExploreFragment", "Sync denied: No user logged in")
        }
    }

    // Public method to force thumbnail generation for all courses - REMOVED (Obsolete)
    // fun forceRegenerateThumbnails() { ... }

    // Initialize image picker launcher for thumbnail change
    private fun initializeImagePickerLauncher() {
        imagePickerLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleThumbnailSelection(uri)
                }
            }
        }
    }

    // Handle thumbnail image selection
    private fun handleThumbnailSelection(imageUri: Uri) {
        currentCourseForThumbnailChange?.let { course ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Subir miniatura a Cloudflare R2 si está configurado
                    var finalThumbnailUri = imageUri.toString()
                    if (CloudflareR2Service.isConfigured()) {
                        Toast.makeText(requireContext(), "Subiendo miniatura a la nube...", Toast.LENGTH_SHORT).show()
                        val result = withContext(Dispatchers.IO) {
                            CloudflareR2Service.uploadFile(
                                context = requireContext(),
                                fileUri = imageUri,
                                folder = "thumbnails/courses",
                                customFileName = "course_${course.id}_${System.currentTimeMillis()}"
                            )
                        }
                        when (result) {
                            is CloudflareR2Service.UploadResult.Success -> {
                                finalThumbnailUri = result.url
                                Log.d("ExploreFragment", "☁️ Thumbnail uploaded to R2: $finalThumbnailUri")
                            }
                            is CloudflareR2Service.UploadResult.Error -> {
                                Log.e("ExploreFragment", "❌ Failed to upload thumbnail: ${result.message}")
                                Toast.makeText(requireContext(), "Error subiendo a nube, usando local", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    // Update the course with new thumbnail
                    val updatedCourse = course.copy(thumbnailUri = finalThumbnailUri)

                    withContext(Dispatchers.IO) {
                        // Update in VideoData table
                        videoManager.updateVideo(updatedCourse)

                        // Update in Course table
                        updateCourseInTable(updatedCourse)
                    }

                    // Immediately update UI lists and adapter so the new thumbnail is visible
                    try {
                        val converted = courseRepository.convertVideoDataToCoursePublic(updatedCourse)

                        val idxAll = allCoursesList.indexOfFirst { it.id == updatedCourse.id }
                        if (idxAll >= 0) allCoursesList[idxAll] = converted

                        val idxFiltered = coursesList.indexOfFirst { it.id == updatedCourse.id }
                        if (idxFiltered >= 0) coursesList[idxFiltered] = converted

                        if (::coursesAdapter.isInitialized) {
                            coursesAdapter.updateCourses(coursesList)
                        }
                    } catch (e: Exception) {
                        Log.w("ExploreFragment", "Failed immediate UI update after thumbnail change", e)
                    }

                    // Show progress toast while syncing remotely
                    Toast.makeText(requireContext(), "Actualizando miniatura...", Toast.LENGTH_SHORT).show()

                    // Trigger remote upsert (non-blocking)
                    try {
                        val syncRepo = getSyncRepository()
                        val courseEntity = courseRepository.convertVideoDataToCoursePublic(updatedCourse)
                        Log.d("ExploreFragment", "Triggering upsertCourseToSupabase for thumbnail change courseEntity.id=${courseEntity.id} title='${courseEntity.title}'")
                        syncRepo.upsertCourseToSupabase(courseEntity)
                    } catch (e: Exception) {
                        Log.w("ExploreFragment", "Failed to trigger remote upsert for thumbnail change", e)
                    }

                    // Show confirmation
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Miniatura actualizada", Toast.LENGTH_SHORT).show()
                    }

                } catch (e: Exception) {
                    Log.e("ExploreFragment", "Error updating thumbnail", e)
                    Toast.makeText(requireContext(), "Error al actualizar miniatura", Toast.LENGTH_SHORT).show()
                } finally {
                    currentCourseForThumbnailChange = null
                }
            }
        }
    }

    // Show thumbnail change options
    private fun showThumbnailChangeOptions(course: VideoData) {
        // Check if current user is the creator before allowing thumbnail change
        if (currentUsername != null && currentUsername == course.username) {
            currentCourseForThumbnailChange = course

            // Create dialog with dark theme
            val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(
                androidx.appcompat.view.ContextThemeWrapper(requireContext(), R.style.DarkAlertDialogTheme)
            )

            // Check if course has video to offer auto-generation
            val hasVideo = !course.videoUriString.isNullOrEmpty()

            dialogBuilder
                .setTitle("🖼️ Cambiar Miniatura")
                .setMessage(buildString {
                    append("¿Qué deseas hacer con la miniatura del curso \"${course.title}\"?\n\n")
                    append("📱 Seleccionar: Elige una imagen de tu galería")
                    if (hasVideo) {
                        append("\n🎬 Generar: Crea miniatura desde el video")
                    }
                })
                .setPositiveButton("📱 Seleccionar Imagen") { _, _ ->
                    openImagePicker()
                }
                .setNeutralButton(if (hasVideo) "🎬 Desde Video" else null) { _, _ ->
                    generateThumbnailFromVideo(course)
                }
                .setNegativeButton("❌ Cancelar") { _, _ ->
                    currentCourseForThumbnailChange = null
                }

            val dialog = dialogBuilder.create()

            // Apply additional dark theme styling
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawableResource(R.drawable.dark_dialog_background)
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                    setTextColor(android.graphics.Color.parseColor("#4CAF50")) // Green for select
                    textSize = 16f
                }
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                    setTextColor(android.graphics.Color.parseColor("#A259FF")) // Purple for cancel
                    textSize = 16f
                }
            }

            dialog.show()
        } else {
            Toast.makeText(requireContext(), "❌ Solo el creador puede cambiar la miniatura", Toast.LENGTH_SHORT).show()
        }
    }

    // Open image picker for thumbnail selection
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        imagePickerLauncher.launch(intent)
    }

    /**
     * Genera miniatura automáticamente desde el video del curso
     */
    private fun generateThumbnailFromVideo(course: VideoData) {
        if (course.videoUriString.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "❌ Este curso no tiene video asociado", Toast.LENGTH_SHORT).show()
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Toast.makeText(requireContext(), "🎬 Generando miniatura desde el video...", Toast.LENGTH_SHORT).show()
                
                // Generar miniatura desde el video
                val videoUri = Uri.parse(course.videoUriString)
                val thumbnailUri = withContext(Dispatchers.IO) {
                    thumbnailExtractor.extractThumbnailFromVideo(videoUri)
                }
                
                if (thumbnailUri == null) {
                    Toast.makeText(requireContext(), "❌ No se pudo generar la miniatura", Toast.LENGTH_SHORT).show()
                    currentCourseForThumbnailChange = null
                    return@launch
                }
                
                Log.d("ExploreFragment", "✅ Miniatura generada: $thumbnailUri")
                
                // Procesar la miniatura generada como si fuera seleccionada
                handleThumbnailSelection(thumbnailUri)
                
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error generando miniatura desde video", e)
                Toast.makeText(requireContext(), "❌ Error: ${e.message}", Toast.LENGTH_SHORT).show()
                currentCourseForThumbnailChange = null
            }
        }
    }

    private fun startSkeletonAnimation() {
        skeletonContainer.animate().cancel()
        skeletonContainer.visibility = View.VISIBLE
        skeletonContainer.alpha = 1f
        
        skeletonAnimator?.cancel()
        skeletonAnimator = ObjectAnimator.ofFloat(skeletonContainer, "alpha", 0.4f, 1.0f).apply {
            duration = 800
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopSkeletonAnimation() {
        skeletonAnimator?.cancel()
        skeletonAnimator = null
        
        skeletonContainer.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction { skeletonContainer.visibility = View.GONE }
            .start()
    }

    /**
     * Load courses with pagination (10 at a time)
     * Uses Supabase pagination for better performance
     */
    private fun loadCourses(forceRemote: Boolean = false) {
        if (isLoadingCourses) {
            Log.d("ExploreFragment", "Already loading courses, skipping")
            return
        }

        isLoadingCourses = true
        
        // Show skeleton only if list is empty (initial load or full refresh)
        if (coursesList.isEmpty()) {
            startSkeletonAnimation()
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("ExploreFragment", "loadCourses: Starting to load courses from Supabase (forceRemote=$forceRemote)")
                
                // Load first page only (server-side pagination)
                val firstPage = withContext(Dispatchers.IO) {
                    Log.d("ExploreFragment", "loadCourses: Calling SupabaseClient.fetchCoursesPage(pageSize,0)")
                    com.example.tareamov.service.SupabaseClient.fetchCoursesPage(pageSize, 0)
                }

                // Fetch total counts and stats server-side (so UI stats remain accurate)
                val fetchedTotal = withContext(Dispatchers.IO) {
                    try { com.example.tareamov.service.SupabaseClient.fetchCoursesCount() } catch (t: Throwable) { 0 }
                }

                totalCourses = fetchedTotal
                currentPage = 0
                hasTriggeredLoadAtPosition5 = false

                withContext(Dispatchers.Main) {
                    // Store only the loaded page; further pages will be appended on demand
                    allCoursesList.clear()
                    allCoursesList.addAll(firstPage.sortedByDescending { it.timestamp })

                    // Display first page
                    coursesList.clear()
                    coursesList.addAll(firstPage.sortedByDescending { it.timestamp })

                    if (::coursesAdapter.isInitialized) {
                        coursesAdapter.updateCourses(coursesList)
                        Log.d("ExploreFragment", "loadCourses: Adapter updated with ${coursesList.size} courses (first page)")
                    } else {
                        Log.w("ExploreFragment", "loadCourses: coursesAdapter not initialized yet!")
                    }

                    // Apply active filter (if any) after initial load so that
                    // navigation with `filter_index` shows the correct filtered
                    // list and the header counts reflect server-side totals.
                    if (currentFilterIndex != 0) {
                        when (currentFilterIndex) {
                            1 -> filterMyCoursesOnly()
                            2 -> filterOtherCoursesOnly()
                            3 -> filterPremiumCourses()
                            4 -> filterFreeCourses()
                            5 -> filterEnrolledCourses()
                            else -> fetchAndDisplayCourseStats()
                        }
                    } else {
                        // No active filter: fetch global server-side stats
                        fetchAndDisplayCourseStats()
                    }

                    // Stop skeleton if we have at least one page or network
                    if (coursesList.isNotEmpty() || isNetworkAvailable()) {
                        stopSkeletonAnimation()
                    } else {
                        startSkeletonAnimation()
                    }

                    // Generate thumbnails for the first page in background
                    generateMissingThumbnails(firstPage)
                }

            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error loading courses", e)
                // Only stop skeleton if we have data to show
                if (coursesList.isNotEmpty()) {
                    stopSkeletonAnimation()
                    Toast.makeText(context, "Error cargando cursos: ${e.message}", Toast.LENGTH_SHORT).show()
                } else {
                    // List is empty. Keep skeleton visible regardless of error type or network status.
                    // This ensures the skeleton persists until we successfully load data.
                    Log.d("ExploreFragment", "Load failed and list empty. Keeping skeleton animation.")
                    startSkeletonAnimation()
                }
            } finally {
                isLoadingCourses = false
            }
        }
    }
    
    /**
     * Load more courses (next page) from the already-loaded allCoursesList
     * Note: Currently all courses are loaded at once, but this is kept for future pagination
     */
    private fun loadMoreCourses() {
        // Do not load more while a manual search/filter is active
        if (isFilterActive) {
            Log.d("ExploreFragment", "loadMoreCourses: filter active, skipping load")
            return
        }
        // Load next page from Supabase when available
        if (isLoadingCourses) return
        // If we already loaded all known courses, nothing to do
        if (totalCourses > 0 && coursesList.size >= totalCourses) {
            Log.d("ExploreFragment", "No more courses to load: displayed=${coursesList.size}, total=$totalCourses")
            return
        }

        isLoadingCourses = true
        val nextOffset = (currentPage + 1) * pageSize

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val nextPage = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.fetchCoursesPage(pageSize, nextOffset)
                }

                if (nextPage.isEmpty()) {
                    Log.d("ExploreFragment", "loadMoreCourses: no items returned for offset=$nextOffset")
                } else {
                    val sorted = nextPage.sortedByDescending { it.timestamp }
                    allCoursesList.addAll(sorted)
                    coursesList.addAll(sorted)
                    withContext(Dispatchers.Main) {
                        if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                    }
                    currentPage += 1
                    Log.d("ExploreFragment", "Loaded page ${currentPage} with ${sorted.size} courses; displayed=${coursesList.size}")
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error loading next page of courses", e)
            } finally {
                isLoadingCourses = false
                // allow the trigger to re-fire on next scroll
                hasTriggeredLoadAtPosition5 = false
                // Las estadísticas ya están calculadas correctamente en fetchAndDisplayCourseStats()
                // No llamar updateCourseStats() que sobrescribe el TOP-5 correcto
            }
        }
    }

    /**
     * Helper function to display courses in the UI
     * Shows all loaded courses and updates adapter
     */
    private fun displayCourses(courses: List<Course>) {
        // Sort by timestamp DESC (most recent first)
        val sortedCourses = courses.sortedByDescending { it.timestamp }
        
        // Store ALL courses for search (only when showing all and NOT during an active filter)
        if (currentFilterIndex == 0 && !isFilterActive) {
            allCoursesList.clear()
            allCoursesList.addAll(sortedCourses)
        }
        
        // Show loaded courses in UI
        coursesList.clear()
        coursesList.addAll(sortedCourses)
        
        // Update adapter
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(sortedCourses)
            // Only log adapter updates when NOT showing a filter/search to avoid noisy logs
            if (!isFilterActive) {
                Log.d("ExploreFragment", "displayCourses: Updated adapter with ${sortedCourses.size} courses")
            }
        }

        // If a filter/search is active, update the total count in the header to reflect the search results
        if (isFilterActive) {
            _totalCourses.value = sortedCourses.size
        }
        
        // Las estadísticas ya están calculadas correctamente en fetchAndDisplayCourseStats()
        // No llamar updateCourseStats() que sobrescribe el TOP-5 correcto
    }
    
    // Helper to obtain a SyncRepository instance (uses current AppDatabase)
    private fun getSyncRepository(): com.example.tareamov.data.sync.SyncRepository {
        val db = AppDatabase.getDatabase(requireContext())
        return com.example.tareamov.data.sync.SyncRepository(
            db.usuarioDao(), db.personaDao(), db.topicDao(), db.contentItemDao(), db.taskDao(),
            db.subscriptionDao(), db.taskSubmissionDao(), db.videoDao(), db.courseDao(), db.rolDao(),
            db.recursoDao(), db.rolRecursoDao(), db.chatMessageDao(), db.fileContextDao(), db.progresoEstudianteDao()
        )
    }

    // Filter courses by name, category, or creator username
    private fun filterCourses(query: String) {
        val q = query.trim()

        // If query is empty, clear filter and show all
        if (q.isEmpty()) {
            isFilterActive = false
            val sorted = allCoursesList.sortedByDescending { it.timestamp }
            displayCourses(sorted)
            // Restore original stats when filter is cleared
            fetchAndDisplayCourseStats()
            return
        }

        // Mark filter active to prevent further pagination
        isFilterActive = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1) Exact local match by title (case-insensitive)
                val exactLocal = allCoursesList.filter { it.title.equals(q, ignoreCase = true) }
                if (exactLocal.isNotEmpty()) {
                    displayCourses(exactLocal)
                    showDarkToast("Mostrando ${exactLocal.size} resultado(s)")
                    return@launch
                }

                // 2) Remote exact match by title via SyncRepository wrapper
                val repo = getSyncRepository()
                val serverExact = withContext(Dispatchers.IO) {
                    try {
                        repo.fetchCourseByTitleFromSupabase(q)
                    } catch (e: Exception) {
                        Log.w("ExploreFragment", "repo.fetchCourseByTitleFromSupabase failed", e)
                        null
                    }
                }

                if (serverExact != null) {
                    displayCourses(listOf(serverExact))
                    showDarkToast("Mostrando 1 resultado (servidor)")
                    return@launch
                }

                // 3) Search by creator username (partial match supported)
                // First find users matching the query, then fetch courses for those user IDs
                val matchingUsers = withContext(Dispatchers.IO) {
                    try {
                        com.example.tareamov.service.SupabaseClient.searchUsersByUsername(q)
                    } catch (e: Exception) {
                        Log.w("ExploreFragment", "searchUsersByUsername failed for '$q'", e)
                        emptyList<com.example.tareamov.data.entity.Usuario>()
                    }
                }

                if (matchingUsers.isNotEmpty()) {
                    val userIds = matchingUsers.map { it.id }
                    
                    val coursesByCreators = withContext(Dispatchers.IO) {
                        try {
                            com.example.tareamov.service.SupabaseClient.fetchCoursesByCreatorUserIds(userIds)
                        } catch (e: Exception) {
                            Log.w("ExploreFragment", "fetchCoursesByCreatorUserIds failed", e)
                            emptyList<Course>()
                        }
                    }

                    if (coursesByCreators.isNotEmpty()) {
                        displayCourses(coursesByCreators)
                        showDarkToast("📚 ${coursesByCreators.size} curso(s) de creadores como '$q'")
                        return@launch
                    }
                }

                // 4) Remote broad search fallback (searches title, description, category, tags)
                val remoteMatches = withContext(Dispatchers.IO) {
                    try {
                        repo.searchCoursesInSupabase(q)
                    } catch (e: Exception) {
                        Log.w("ExploreFragment", "repo.searchCoursesInSupabase failed", e)
                        emptyList<Course>()
                    }
                }

                if (remoteMatches.isNotEmpty()) {
                    displayCourses(remoteMatches)
                    showDarkToast("Mostrando ${remoteMatches.size} resultado(s) remotos")
                    return@launch
                }

                // 5) Local contains fallback (title, description, category)
                val containsLocal = allCoursesList.filter {
                    it.title.contains(q, ignoreCase = true) ||
                            (it.description?.contains(q, ignoreCase = true) == true) ||
                            (it.category?.contains(q, ignoreCase = true) == true)
                }.sortedByDescending { it.timestamp }

                displayCourses(containsLocal)
                if (containsLocal.isNotEmpty()) {
                    showDarkToast("Mostrando ${containsLocal.size} resultado(s) local(es)")
                } else {
                    showDarkToast("❌ No se encontraron resultados para '$q'")
                }

            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filterCourses", e)
                showDarkToast("❌ Error en la búsqueda")
            }
        }
    }

    // Show filter options dialog with modern BottomSheet
    private fun showFilterOptions() {
        val bottomSheetDialog = BottomSheetDialog(requireContext(), R.style.DarkBottomSheetDialogTheme)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_filter_courses, null)
        bottomSheetDialog.setContentView(view)

        // Make background transparent to show rounded corners
        (view.parent as? View)?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // Setup BlurView for BottomSheet
        val blurView = view.findViewById<eightbitlab.com.blurview.BlurView>(R.id.blurView)
        val decorView = requireActivity().window.decorView
        val rootView = requireActivity().window.decorView.findViewById<ViewGroup>(android.R.id.content)
        val windowBackground = decorView.background

        blurView.setupWith(rootView, RenderScriptBlur(requireContext()))
            .setFrameClearDrawable(windowBackground)
            .setBlurRadius(20f)
            .setBlurAutoUpdate(true)
            .setOverlayColor(android.graphics.Color.parseColor("#CC1E1E1E")) // Match item background color with transparency

        blurView.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val radius = view.resources.displayMetrics.density * 24f
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        blurView.clipToOutline = true

        val recyclerView = view.findViewById<RecyclerView>(R.id.filterOptionsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val options = listOf(
            FilterOption("📚", "Todos los cursos", 0) { 
                showAllCourses()
            },
            FilterOption("🎓", "Mis inscripciones", 5) { 
                filterEnrolledCourses()
                updateActiveFilterUI("Mis Inscripciones")
            },
            FilterOption("👤", "Mis cursos (Creados)", 1) { 
                filterMyCoursesOnly()
            },
            FilterOption("🌟", "Cursos de otros", 2) { 
                filterOtherCoursesOnly()
            },
            FilterOption("💰", "Cursos premium", 3) { 
                filterPremiumCourses()
            },
            FilterOption("🆓", "Cursos gratuitos", 4) { 
                filterFreeCourses()
            }
        )

        val adapter = FilterAdapter(options, currentFilterIndex) { selectedIndex ->
            currentFilterIndex = selectedIndex
            bottomSheetDialog.dismiss()
        }
        recyclerView.adapter = adapter
        
        // Add staggered animation to RecyclerView items
        recyclerView.alpha = 0f
        recyclerView.translationY = 50f
        
        // Show dialog first
        bottomSheetDialog.show()
        
        // Then animate RecyclerView
        recyclerView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setStartDelay(100)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        
        // Animate each item with stagger effect
        recyclerView.post {
            for (i in 0 until recyclerView.childCount) {
                val child = recyclerView.getChildAt(i)
                child?.alpha = 0f
                child?.translationX = -50f
                child?.animate()
                    ?.alpha(1f)
                    ?.translationX(0f)
                    ?.setDuration(250)
                    ?.setStartDelay(150L + (i * 50L))
                    ?.setInterpolator(android.view.animation.DecelerateInterpolator())
                    ?.start()
            }
        }
    }

    data class FilterOption(
        val icon: String,
        val title: String,
        val index: Int,
        val action: () -> Unit
    )

    inner class FilterAdapter(
        private val options: List<FilterOption>,
        private val selectedIndex: Int,
        private val onOptionSelected: (Int) -> Unit
    ) : RecyclerView.Adapter<FilterAdapter.FilterViewHolder>() {

        inner class FilterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val icon: TextView = itemView.findViewById(R.id.filterIcon)
            val title: TextView = itemView.findViewById(R.id.filterTitle)
            val check: ImageView = itemView.findViewById(R.id.filterCheck)
            val container: LinearLayout = itemView as LinearLayout
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FilterViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_filter_option, parent, false)
            return FilterViewHolder(view)
        }

        override fun onBindViewHolder(holder: FilterViewHolder, position: Int) {
            val option = options[position]
            holder.icon.text = option.icon
            holder.title.text = option.title
            
            val isSelected = option.index == selectedIndex
            holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            
            if (isSelected) {
                holder.title.setTextColor(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.purple_500))
                holder.icon.alpha = 1.0f
            } else {
                holder.title.setTextColor(android.graphics.Color.WHITE)
                holder.icon.alpha = 0.7f
            }

            holder.itemView.setOnClickListener {
                // Add scale animation on click
                holder.itemView.animate()
                    .scaleX(0.95f)
                    .scaleY(0.95f)
                    .setDuration(100)
                    .withEndAction {
                        holder.itemView.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(100)
                            .start()
                    }
                    .start()
                
                // Invoke action after short delay for animation feedback
                holder.itemView.postDelayed({
                    option.action.invoke()
                    onOptionSelected(option.index)
                }, 200)
            }
        }

        override fun getItemCount() = options.size
    }

    // Filter premium courses
    private fun filterPremiumCourses() {
        // Prefer server-side authoritative list for premium courses and exclude session user
        currentFilterIndex = 3
        isFilterActive = true
        lifecycleScope.launch {
            try {
                if (isNetworkAvailable()) {
                    val session = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                    val sessionUserId = session.getUserId()
                    val serverCourses = withContext(Dispatchers.IO) {
                        try {
                            com.example.tareamov.service.SupabaseClient.fetchCourses()
                        } catch (t: Throwable) {
                            emptyList<com.example.tareamov.data.entity.Course>()
                        }
                    }

                    val premium = serverCourses.filter { it.isPremium == true && (sessionUserId <= 0L || it.creatorUserId != sessionUserId) }
                        .sortedByDescending { it.timestamp }

                    coursesList.clear()
                    coursesList.addAll(premium)
                    if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                    
                    // Actualizar estadísticas con el total de cursos premium
                    updateFilteredCourseStats(
                        totalCount = premium.size,
                        filterType = "premium"
                    )
                    
                    updateActiveFilterUI("Cursos Premium")
                    if (premium.isEmpty()) showDarkToast("No hay cursos premium disponibles") else showDarkToast("Mostrando ${premium.size} cursos premium")
                    Log.d("ExploreFragment", "Filtered to show premium courses (server): ${premium.size} courses")
                    return@launch
                }

                // Offline fallback: use local list
                val premiumCourses = allCoursesList.filter { it.isPremium == true }.sortedByDescending { it.timestamp }
                coursesList.clear(); coursesList.addAll(premiumCourses)
                if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                
                // Actualizar estadísticas con el total de cursos premium (offline)
                updateFilteredCourseStats(
                    totalCount = premiumCourses.size,
                    filterType = "premium"
                )
                
                updateActiveFilterUI("Cursos Premium")
                if (premiumCourses.isEmpty()) showDarkToast("No hay cursos premium disponibles (offline)") else showDarkToast("Mostrando ${premiumCourses.size} cursos premium (offline)")
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering premium courses", e)
                Toast.makeText(context, "Error filtrando cursos premium", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Filter free courses
    private fun filterFreeCourses() {
        // Prefer server-side authoritative list for free courses and exclude session user
        currentFilterIndex = 4
        isFilterActive = true
        lifecycleScope.launch {
            try {
                if (isNetworkAvailable()) {
                    val session = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                    val sessionUserId = session.getUserId()
                    
                    // Use server-side filtered fetch for free courses
                    val freeCourses = withContext(Dispatchers.IO) {
                        getSyncRepository().fetchFreeCoursesFromSupabase()
                    }

                    // Show all free courses including own courses
                    val free = freeCourses.sortedByDescending { it.timestamp }

                    coursesList.clear()
                    coursesList.addAll(free)
                    if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                    
                    // Actualizar estadísticas con el total de cursos gratuitos
                    updateFilteredCourseStats(
                        totalCount = free.size,
                        filterType = "free"
                    )
                    
                    updateActiveFilterUI("Cursos Gratis")
                    if (free.isEmpty()) showDarkToast("No hay cursos gratuitos disponibles") else showDarkToast("Mostrando ${free.size} cursos gratis")
                    Log.d("ExploreFragment", "Filtered to show free courses (server-side filter): ${free.size} courses")
                    return@launch
                }

                // Offline fallback: use local list
                val freeCourses = allCoursesList.filter { it.isPremium != true }.sortedByDescending { it.timestamp }
                coursesList.clear(); coursesList.addAll(freeCourses)
                if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                
                // Actualizar estadísticas con el total de cursos gratuitos (offline)
                updateFilteredCourseStats(
                    totalCount = freeCourses.size,
                    filterType = "free"
                )
                
                updateActiveFilterUI("Cursos Gratis")
                if (freeCourses.isEmpty()) showDarkToast("No hay cursos gratuitos disponibles (offline)") else showDarkToast("Mostrando ${freeCourses.size} cursos gratis (offline)")
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering free courses", e)
                Toast.makeText(context, "Error filtrando cursos gratis", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Filter enrolled courses
    private fun filterEnrolledCourses() {
        // Set filter index for "Enrolled" and refresh authoritative stats
        currentFilterIndex = 5
        isLoadingCourses = true
        // Show loading state if possible
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val userId = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername ?: "")
                if (userId == null) {
                    withContext(Dispatchers.Main) {
                        showDarkToast("Debes iniciar sesión para ver tus inscripciones")
                        isLoadingCourses = false
                    }
                    return@launch
                }

                // Fetch enrolled course IDs from progreso_estudiante table
                val enrolledIds = com.example.tareamov.service.SupabaseClient.fetchEnrolledCourseIds(userId)
                Log.d("ExploreFragment", "Usuario $userId tiene ${enrolledIds.size} inscripciones: $enrolledIds")
                
                if (enrolledIds.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        displayCourses(emptyList())
                        showDarkToast("No tienes cursos inscritos")
                        updateFilteredCourseStats(0, "enrolled")
                        isFilterActive = true
                        isLoadingCourses = false
                    }
                    return@launch
                }
                
                // Fetch all enrolled courses directly from Supabase (not from local list)
                val enrolledCourses = com.example.tareamov.service.SupabaseClient.fetchCoursesByIds(enrolledIds)
                Log.d("ExploreFragment", "Se obtuvieron ${enrolledCourses.size} cursos inscritos de Supabase")
                
                withContext(Dispatchers.Main) {
                    displayCourses(enrolledCourses)
                    
                    val count = enrolledCourses.size
                    showDarkToast("Mostrando $count cursos inscritos")
                    // Update header counts to reflect enrolled filter (use filtered count, not global)
                    updateFilteredCourseStats(count, "enrolled")
                    isFilterActive = true
                    isLoadingCourses = false
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering enrolled courses", e)
                withContext(Dispatchers.Main) {
                    showDarkToast("Error al cargar inscripciones")
                    isLoadingCourses = false
                }
            }
        }
    }

    /**
     * Actualizar estadísticas cuando hay un filtro activo aplicado
     * Muestra el total real de cursos según el filtro, no solo los mostrados en pantalla
     */
    private fun updateFilteredCourseStats(totalCount: Int, filterType: String) {
        // Mostrar el conteo total de cursos del filtro
        _totalCourses.value = totalCount
        
        // Calcular populares y nuevos dentro del conjunto filtrado
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val popular = withContext(Dispatchers.IO) {
                    coursesList.count { course ->
                        try {
                            com.example.tareamov.service.SupabaseClient.countStudentsInCourse(course.id) >= 10
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
                
                val currentTime = System.currentTimeMillis()
                val thirtyDaysAgo = currentTime - (30L * 24 * 60 * 60 * 1000)
                val newCount = coursesList.count { course ->
                    val courseTime = course.timestamp
                    val creationTime = course.creationDate?.let { parseDate(it) } ?: 0
                    maxOf(courseTime, creationTime) > thirtyDaysAgo
                }
                
                _popularCourses.value = popular
                _newCourses.value = newCount
                
                Log.d("ExploreFragment", "Filtered stats ($filterType): Total=$totalCount, Popular=$popular, New=$newCount")
            } catch (e: Exception) {
                Log.w("ExploreFragment", "Error calculating filtered stats", e)
            }
        }
    }

    // Update course statistics in header based on currently displayed courses
    // DEPRECATED: Este método ya no se usa. Se reemplazó por fetchAndDisplayCourseStats() que usa TOP-5
    @Deprecated("Usar fetchAndDisplayCourseStats() en su lugar que calcula TOP-5 correctamente")
    private fun updateCourseStats() {
        // Este método está obsoleto y ya no debe llamarse
        // fetchAndDisplayCourseStats() es el método correcto que usa TOP-5
        Log.w("ExploreFragment", "updateCourseStats() está obsoleto, usar fetchAndDisplayCourseStats() en su lugar")
    }

    /**
     * Fetch aggregated stats (total, popular, new) and display them immediately.
     * Always queries Supabase for counts - does NOT use local course data.
     * Only fetches GLOBAL stats when no filter is active (currentFilterIndex == 0).
     */
    private fun fetchAndDisplayCourseStats() {
        // If a filter is active, don't override with global stats
        // Each filter function should call updateFilteredCourseStats() instead
        if (isFilterActive && currentFilterIndex != 0) {
            Log.d("ExploreFragment", "Filter active (index=$currentFilterIndex), skipping global stats fetch")
            return
        }
        
        // Initialize with 0 - will be updated from Supabase
        _totalCourses.value = 0
        _popularCourses.value = 0
        _newCourses.value = 0

        // Fetch all stats from Supabase in a single coroutine
        viewLifecycleOwner.lifecycleScope.launch {
            if (!isNetworkAvailable()) {
                Log.w("ExploreFragment", "Network not available, cannot fetch stats")
                return@launch
            }
            
            try {
                // Fetch total courses count from Supabase
                val serverTotal = withContext(Dispatchers.IO) {
                    try { com.example.tareamov.service.SupabaseClient.fetchCoursesCount() } catch (t: Throwable) { 0 }
                }
                if (serverTotal > 0) {
                    totalCourses = serverTotal
                    _totalCourses.value = serverTotal
                }
                
                // Fetch popular courses count (TOP-5 by enrollment)
                val topPopular = withContext(Dispatchers.IO) {
                    try {
                        com.example.tareamov.service.SupabaseClient.fetchTopPopularCourses(5)
                    } catch (e: Exception) {
                        emptyList<com.example.tareamov.data.entity.Course>()
                    }
                }
                _popularCourses.value = topPopular.size
                
                // Fetch new courses count (last 30 days) from Supabase
                val newCount = withContext(Dispatchers.IO) {
                    try { com.example.tareamov.service.SupabaseClient.countNewCourses(30) } catch (t: Throwable) { 0 }
                }
                _newCourses.value = newCount
                
                Log.d("ExploreFragment", "Global stats fetched from Supabase: total=$serverTotal, popular=${topPopular.size}, new=$newCount")
            } catch (e: Exception) {
                Log.w("ExploreFragment", "Failed to fetch stats from Supabase", e)
            }
        }
    }
    
    // Helper to parse date string to timestamp
    private fun parseDate(dateString: String): Long {
        return try {
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.getDefault())
            format.parse(dateString)?.time ?: 0
        } catch (e: Exception) {
            Log.w("ExploreFragment", "Failed to parse date: $dateString", e)
            0
        }
    }

    // Recursively set text color for all TextView children in a ViewGroup
    private fun setAllChildTextColors(root: ViewGroup, color: Int) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            when (child) {
                is TextView -> child.setTextColor(color)
                is ViewGroup -> setAllChildTextColors(child, color)
            }
        }
    }

    // Filter courses to show only user's own courses
    private fun filterMyCoursesOnly() {
        lifecycleScope.launch {
            try {
                // Filter index for "My Created" and refresh authoritative stats
                currentFilterIndex = 1
                isFilterActive = true
                val myCoursesOnly = getUserOwnedCourses()
                val sorted = myCoursesOnly.sortedByDescending { it.timestamp }
                coursesList.clear()
                coursesList.addAll(sorted)
                if (::coursesAdapter.isInitialized) {
                    coursesAdapter.updateCourses(coursesList)
                } else {
                    Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
                }
                // Use filtered stats instead of global
                updateFilteredCourseStats(sorted.size, "my_courses")
                updateActiveFilterUI("Mis Cursos Creados")
                Log.d("ExploreFragment", "Filtered to show only user's courses: ${sorted.size} courses")
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering user's courses", e)
                Toast.makeText(context, "Error filtrando cursos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Filter courses to show only other users' courses
    private fun filterOtherCoursesOnly() {
        lifecycleScope.launch {
            try {
                // Filter index for "Other Users'" and refresh authoritative stats
                currentFilterIndex = 2
                isFilterActive = true
                val otherCourses = getOtherUsersCourses()
                val sorted = otherCourses.sortedByDescending { it.timestamp }
                coursesList.clear()
                coursesList.addAll(sorted)
                if (::coursesAdapter.isInitialized) {
                    coursesAdapter.updateCourses(coursesList)
                } else {
                    Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
                }
                // Use filtered stats instead of global
                updateFilteredCourseStats(sorted.size, "other_courses")
                updateActiveFilterUI("Cursos de Otros")
                Log.d("ExploreFragment", "Filtered to show only other users' courses: ${sorted.size} courses")
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering other users' courses", e)
                Toast.makeText(context, "Error filtrando cursos", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Sync subscription to Supabase
     */
    private suspend fun syncSubscriptionToSupabase(subscriberId: Long, creatorId: Long) {
        try {
            val supabaseClient = com.example.tareamov.service.SupabaseClient
            supabaseClient.subscribeToCreator(subscriberId, creatorId)
            Log.d("ExploreFragment", "Subscription synced to Supabase: $subscriberId -> $creatorId")
        } catch (e: Exception) {
            Log.e("ExploreFragment", "Error syncing subscription to Supabase", e)
            // Don't throw - local subscription is more important
        }
    }

    /**
     * Sync unsubscription to Supabase
     */
    private suspend fun syncUnsubscriptionToSupabase(subscriberId: Long, creatorId: Long) {
        try {
            val supabaseClient = com.example.tareamov.service.SupabaseClient
            supabaseClient.unsubscribeFromCreator(subscriberId, creatorId)
            Log.d("ExploreFragment", "Unsubscription synced to Supabase: $subscriberId -> $creatorId")
        } catch (e: Exception) {
            Log.e("ExploreFragment", "Error syncing unsubscription to Supabase", e)
            // Don't throw - local unsubscription is more important
        }
    }

    private fun clearActiveFilter() {
        // Clear any active filter and reload all courses
        isFilterActive = false
        currentFilterIndex = 0
        _searchText.value = "" // Clear search text
        showAllCourses()
        updateActiveFilterUI(null)
        // Refresh header counts to reflect unfiltered/global view
        fetchAndDisplayCourseStats()
    }

    private fun showDebugStatsInfo() {
        // Show debug stats info
        Toast.makeText(context, "Debug Stats Info", Toast.LENGTH_SHORT).show()
    }

    private fun showAllCourses() {
        // Reload all courses without filter
        loadCourses(forceRemote = false)
    }

    private fun updateActiveFilterUI(filterName: String?) {
        // Update UI based on active filter
        _activeFilterName.value = filterName
    }

    // Show the supplied courses as an active "popular" filter
    private fun showPopularCourses(popularList: List<Course>) {
        if (popularList.isEmpty()) {
            showDarkToast("No hay cursos populares disponibles")
            return
        }

        isFilterActive = true
        currentFilterIndex = 6

        // Do not log popular list entries to avoid noisy output when a filter is active

        // Replace displayed list with the popular subset (preserving order)
        coursesList.clear()
        coursesList.addAll(popularList)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(popularList)
        }

        updateActiveFilterUI("Más populares")
        updateFilteredCourseStats(popularList.size, "popular")
    }

    // Show the supplied courses as an active "new" filter
    private fun showNewCourses(newList: List<Course>) {
        if (newList.isEmpty()) {
            showDarkToast("No hay cursos nuevos disponibles")
            return
        }

        isFilterActive = true
        currentFilterIndex = 7

        // Replace displayed list with the new subset
        coursesList.clear()
        coursesList.addAll(newList)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(newList)
        }

        updateActiveFilterUI("Nuevos")
        updateFilteredCourseStats(newList.size, "new")
    }
    
    /**
     * Genera miniaturas automáticamente para cursos que tienen video pero no miniatura
     * Se ejecuta en segundo plano sin bloquear la UI
     */
    private fun generateMissingThumbnails(courses: List<Course>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                var generatedCount = 0
                
                courses.forEach { course ->
                    // Solo generar si tiene video pero no miniatura
                    if (!course.videoUri.isNullOrEmpty() && course.thumbnailUri.isNullOrEmpty()) {
                        try {
                            val videoUri = course.videoUri!!
                            
                            // Omitir videos con rutas de caché local que pueden no existir
                            if (videoUri.contains("/cache/") || videoUri.contains("/data/user/")) {
                                val file = java.io.File(videoUri.removePrefix("file://"))
                                if (!file.exists()) {
                                    Log.d("ExploreFragment", "⏭️ Omitiendo video con caché eliminada: ${course.title}")
                                    return@forEach
                                }
                            }
                            
                            Log.d("ExploreFragment", "🎨 Generando miniatura para curso: ${course.title}")
                            
                            val parsedUri = Uri.parse(videoUri)
                            val thumbnailUri = thumbnailExtractor.extractThumbnailFromVideo(parsedUri)
                            
                            if (thumbnailUri != null) {
                                // Subir a Cloudflare R2 si está configurado
                                var finalThumbnailUri = thumbnailUri.toString()
                                
                                if (CloudflareR2Service.isConfigured()) {
                                    val uploadResult = CloudflareR2Service.uploadFile(
                                        context = requireContext(),
                                        fileUri = thumbnailUri,
                                        folder = "thumbnails/courses/auto",
                                        customFileName = "auto_thumb_${course.id}_${System.currentTimeMillis()}"
                                    )
                                    
                                    when (uploadResult) {
                                        is CloudflareR2Service.UploadResult.Success -> {
                                            finalThumbnailUri = uploadResult.url
                                            Log.d("ExploreFragment", "☁️ Miniatura automática subida a R2: $finalThumbnailUri")
                                        }
                                        is CloudflareR2Service.UploadResult.Error -> {
                                            Log.w("ExploreFragment", "⚠️ Error subiendo miniatura a R2, usando local")
                                        }
                                    }
                                }
                                
                                // Actualizar el curso con la nueva miniatura
                                val updatedCourse = course.copy(thumbnailUri = finalThumbnailUri)
                                
                                // Guardar en Supabase
                                val success = com.example.tareamov.service.SupabaseClient.updateCourseById(
                                    course.id,
                                    updatedCourse
                                )
                                
                                if (success) {
                                    generatedCount++
                                    Log.d("ExploreFragment", "✅ Miniatura automática guardada para curso ${course.id}")
                                    
                                    // Actualizar en las listas locales
                                    withContext(Dispatchers.Main) {
                                        val idxAll = allCoursesList.indexOfFirst { it.id == course.id }
                                        if (idxAll >= 0) allCoursesList[idxAll] = updatedCourse
                                        
                                        val idxFiltered = coursesList.indexOfFirst { it.id == course.id }
                                        if (idxFiltered >= 0) {
                                            coursesList[idxFiltered] = updatedCourse
                                            // Actualizar solo ese ítem en el adapter
                                            coursesAdapter.notifyItemChanged(idxFiltered)
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("ExploreFragment", "Error generando miniatura para curso ${course.id}", e)
                        }
                    }
                }
                
                if (generatedCount > 0) {
                    withContext(Dispatchers.Main) {
                        Log.d("ExploreFragment", "✅ Generadas $generatedCount miniaturas automáticas")
                    }
                }
                
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error en proceso de generación de miniaturas", e)
            }
        }
    }
}