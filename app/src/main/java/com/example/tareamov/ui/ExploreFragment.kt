package com.example.tareamov.ui
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderScriptBlur
import eightbitlab.com.blurview.RenderEffectBlur
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
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Course
import com.example.tareamov.util.getEnrollmentStatusOrNull
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.VideoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
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
    private val _purchasedCourses = androidx.compose.runtime.mutableStateOf(0)
    private val _searchText = androidx.compose.runtime.mutableStateOf("")
    private val _activeFilterName = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val _isHeaderCollapsed = androidx.compose.runtime.mutableStateOf(true)
    private val _canAddCourse = androidx.compose.runtime.mutableStateOf(false)

    // Store all courses for filtering and search
    private var allCoursesList = mutableListOf<Course>()
    
    private var cachedUserId: Long? = null

    // Variable to track pending payment for redirection
    private var pendingPaymentCourseId: Long? = null
    
    // Dialog for payment initiation
    private var paymentInitiationDialog: AlertDialog? = null
    
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
    private val courseVideoCache = java.util.concurrent.ConcurrentHashMap<Long, String?>()
    private val previewDurationMs = 8000L
    private val previewRunnable = Runnable {
        startPreviewForCenterItem()
    }
    private val stopPreviewRunnable = Runnable {
        stopCurrentPreview()
    }

    private fun startPreviewForCenterItem() {
        val view = view ?: return
        val coursesRecyclerView = view.findViewById<RecyclerView>(R.id.coursesRecyclerView) ?: return
        val layoutManager = coursesRecyclerView.layoutManager as? LinearLayoutManager ?: return

        val firstVisible = layoutManager.findFirstVisibleItemPosition()
        val lastVisible = layoutManager.findLastVisibleItemPosition()

        if (firstVisible == RecyclerView.NO_POSITION || lastVisible == RecyclerView.NO_POSITION) return

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
                    val directVideoUri = resolvePreviewUri(course.localFilePath ?: course.videoUri)
                    if (directVideoUri != null) {
                        playVideoUri(directVideoUri, holder, centerPosition)
                    } else {
                        playPreviewForCourse(course, holder, centerPosition)
                    }
                }
            }
        }
    }

    private fun resolvePreviewUri(videoUri: String?): String? {
        if (videoUri.isNullOrBlank()) return null
        if (videoUri.startsWith("http") || videoUri.startsWith("content://") || videoUri.startsWith("file://")) {
            return videoUri
        }
        val key = if (videoUri.startsWith("/")) videoUri.substring(1) else videoUri
        return BackendApiService.buildProxyFileUrl(key)
    }

    private fun playPreviewForCourse(course: Course, holder: CourseAdapter.CourseViewHolder, position: Int) {
        val videoUri = resolvePreviewUri(course.localFilePath ?: course.videoUri)

        if (videoUri != null) {
            playVideoUri(videoUri, holder, position)
            return
        }

        if (courseVideoCache.containsKey(course.id)) {
            val cachedUri = courseVideoCache[course.id]
            if (!cachedUri.isNullOrEmpty()) {
                playVideoUri(cachedUri, holder, position)
            }
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getVideosByCourse(course.id)
                }

                val videos = (result as? ApiResult.Success)?.data
                val firstVideo = videos?.firstOrNull()
                val fetchedUri = resolvePreviewUri(firstVideo?.localFilePath ?: firstVideo?.videoUriString)

                if (!fetchedUri.isNullOrEmpty()) {
                    courseVideoCache[course.id] = fetchedUri

                    if (currentPreviewPosition == -1) {
                        holder.playPreview(fetchedUri)
                        currentPreviewPosition = position
                        schedulePreviewStop()
                    }
                } else {
                    courseVideoCache[course.id] = ""
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error fetching video for course ${course.id}", e)
                courseVideoCache[course.id] = ""
            }
        }
    }

    private fun playVideoUri(videoUri: String, holder: CourseAdapter.CourseViewHolder, position: Int) {
        val finalUri = resolvePreviewUri(videoUri) ?: return
        holder.playPreview(finalUri)
        currentPreviewPosition = position
        schedulePreviewStop()
    }

    private fun schedulePreviewStop() {
        previewHandler.removeCallbacks(stopPreviewRunnable)
        previewHandler.postDelayed(stopPreviewRunnable, previewDurationMs)
    }

    private fun stopCurrentPreview() {
        previewHandler.removeCallbacks(stopPreviewRunnable)
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

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize image picker launcher for thumbnail change
        initializeImagePickerLauncher()

        // Inicializar VideoManager y CourseRepository
        videoManager = VideoManager(requireContext())
        courseRepository = com.example.tareamov.repository.CourseRepository(requireContext())
        thumbnailExtractor = com.example.tareamov.util.VideoThumbnailExtractor(requireContext())

        currentUsername = getCurrentUsername()
        val sessionUserId = com.example.tareamov.util.SessionManager.getInstance(requireContext()).getUserId()
        if (sessionUserId != -1L) cachedUserId = sessionUserId

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
        val decorView = requireActivity().window.decorView
        val rootView = view as ViewGroup
        val windowBackground = decorView.background

        try {
            val blurAlgorithm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                RenderEffectBlur()
            } else {
                RenderScriptBlur(requireContext())
            }
            headerSection.setupWith(rootView, blurAlgorithm)
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(25f)
                .setBlurAutoUpdate(true)
        } catch (e: Exception) {
            Log.w("ExploreFragment", "BlurView setup failed, disabling blur", e)
            headerSection.setBlurEnabled(false)
        }
            
        headerSection.outlineProvider = ViewOutlineProvider.BACKGROUND
        headerSection.clipToOutline = true

        fun syncBlurWithCollapseState(collapsed: Boolean) {
            if (collapsed) {
                headerSection.setBlurEnabled(false)
                headerSection.setOverlayColor(android.graphics.Color.TRANSPARENT)
            } else {
                headerSection.setBlurEnabled(true)
                headerSection.setOverlayColor(android.graphics.Color.parseColor("#200A0A14"))
            }
        }
        syncBlurWithCollapseState(_isHeaderCollapsed.value)

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
                            val result = BackendApiService.getPopularCourses(1, 50)
                            if (result is ApiResult.Success) {
                                result.data
                            } else {
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
                        
                        val allAvailableCourses = withContext(Dispatchers.IO) {
                            val result = BackendApiService.getCourses(1, 200)
                            if (result is ApiResult.Success) result.data
                            else if (allCoursesList.isNotEmpty()) allCoursesList else coursesList
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
                onToggleCollapse = {
                    _isHeaderCollapsed.value = !_isHeaderCollapsed.value
                    syncBlurWithCollapseState(_isHeaderCollapsed.value)
                }
            )
        }

        // Setup course observation
        setupCourseObservation()

        // Setup network monitoring to retry loading when internet returns
        setupNetworkMonitoring()

        // Mostrar estadísticas inmediatamente (agregados server-side con fallback offline)
        fetchAndDisplayCourseStats()

        // Mostrar cache inmediatamente, refrescar en segundo plano
    loadCoursesWithCache()

    // Setup listeners so ExploreFragment refreshes when a course or video is updated
    try {
        val navController = findNavController()
        val currentBackStackEntry = navController.currentBackStackEntry

        // Listen via SavedStateHandle (preferred)
        currentBackStackEntry?.savedStateHandle?.getLiveData<Boolean>("courseUpdated")?.observe(viewLifecycleOwner) { updated ->
            if (updated == true) {
                Log.d("ExploreFragment", "Course update detected via SavedStateHandle, reloading courses")
                loadCourses(forceRemote = true)
                currentBackStackEntry.savedStateHandle.remove<Boolean>("courseUpdated")
            }
        }

        currentBackStackEntry?.savedStateHandle?.getLiveData<Boolean>("videoUpdated")?.observe(viewLifecycleOwner) { updated ->
            if (updated == true) {
                val saved = currentBackStackEntry.savedStateHandle
                val updatedId = saved.get<Long>("updatedCourseId") ?: saved.get<Long>("updatedVideoId") ?: 0L
                Log.d("ExploreFragment", "Video update detected via SavedStateHandle, id=$updatedId")
                if (updatedId > 0) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val res = withContext(Dispatchers.IO) { BackendApiService.getCourseById(updatedId) }
                            if (res is ApiResult.Success) {
                                val updatedCourse = res.data
                                val idx = allCoursesList.indexOfFirst { it.id == updatedCourse.id }
                                if (idx >= 0) {
                                    allCoursesList[idx] = updatedCourse
                                    displayCourses(allCoursesList)
                                } else {
                                    loadCourses(forceRemote = true)
                                }
                            } else {
                                loadCourses(forceRemote = true)
                            }
                        } catch (e: Exception) {
                            Log.e("ExploreFragment", "Error fetching updated course", e)
                            loadCourses(forceRemote = true)
                        }
                    }
                } else {
                    loadCourses(forceRemote = true)
                }
                currentBackStackEntry.savedStateHandle.remove<Boolean>("videoUpdated")
            }
        }

        // FragmentManager fallback listeners
        requireActivity().supportFragmentManager.setFragmentResultListener("courseUpdated", viewLifecycleOwner) { _, _ ->
            com.example.tareamov.util.AppCache.invalidateCourses()
            loadCourses(forceRemote = true)
        }

        requireActivity().supportFragmentManager.setFragmentResultListener("videoUpdated", viewLifecycleOwner) { _, bundle ->
            val updatedId = bundle.getLong("updatedVideoId", 0L)
            Log.d("ExploreFragment", "FragmentManager videoUpdated received, id=$updatedId")
            if (updatedId > 0) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val res = withContext(Dispatchers.IO) { BackendApiService.getCourseById(updatedId) }
                        if (res is ApiResult.Success) {
                            val updatedCourse = res.data
                            val idx = allCoursesList.indexOfFirst { it.id == updatedCourse.id }
                            if (idx >= 0) {
                                allCoursesList[idx] = updatedCourse
                                displayCourses(allCoursesList)
                            } else {
                                loadCourses(forceRemote = true)
                            }
                        } else {
                            loadCourses(forceRemote = true)
                        }
                    } catch (e: Exception) {
                        Log.e("ExploreFragment", "Error updating course from fragment result", e)
                        loadCourses(forceRemote = true)
                    }
                }
            } else {
                loadCourses(forceRemote = true)
            }
        }
    } catch (e: Exception) {
        Log.e("ExploreFragment", "Error setting up update listeners", e)
    }

        // Observe reactive cache invalidation — auto-reload when any device triggers a CRUD op
        viewLifecycleOwner.lifecycleScope.launch {
            com.example.tareamov.util.AppCache.coursesRefresh.collectLatest {
                Log.d("ExploreFragment", "coursesRefresh event received, reloading courses")
                loadCourses(forceRemote = true)
            }
        }

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

        setupBottomNavigation(bottomNavBinding)
        updateNotificationBadge(bottomNavBinding)
    }

    /**
     * Actualiza el badge de notificaciones no leídas
     */
    private fun updateNotificationBadge(bottomNavBinding: ComponentBottomNavigationBinding) {
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        if (sessionManager.getUserId() == -1L) {
            bottomNavBinding.notificationBadge.visibility = View.GONE
            return
        }

        val cached = com.example.tareamov.util.AppCache.getUnreadCount()
        if (cached != null) showBadge(bottomNavBinding, cached)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    BackendApiService.getUnreadNotificationCount()
                }
                val count = if (result is ApiResult.Success) result.data ?: 0 else 0
                com.example.tareamov.util.AppCache.putUnreadCount(count)
                showBadge(bottomNavBinding, count)
            } catch (e: Exception) {
                android.util.Log.w("ExploreFragment", "Error updating notification badge", e)
            }
        }
    }

    private fun showBadge(bottomNavBinding: ComponentBottomNavigationBinding, count: Int) {
        if (count > 0) {
            bottomNavBinding.notificationBadge.text = if (count > 99) "99+" else count.toString()
            bottomNavBinding.notificationBadge.visibility = View.VISIBLE
        } else {
            bottomNavBinding.notificationBadge.visibility = View.GONE
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
            val nav = findNavController()
            if (nav.currentDestination?.id == R.id.exploreFragment) {
                updateBottomNavSelection(bottomNavBinding, "home")
                nav.navigate(R.id.action_exploreFragment_to_videoHomeFragment)
            }
        }
        bottomNavBinding.exploreButton.setOnClickListener {
            // Ya estás en Explorar, puedes dejarlo vacío o recargar
        }

        val canUploadContent = com.example.tareamov.util.SessionManager
            .getInstance(requireContext())
            .run { hasRole(3) || hasRole(2) }
        _canAddCourse.value = com.example.tareamov.util.SessionManager
            .getInstance(requireContext()).run { hasRole(3) || hasRole(2) }
        val goToHomeContainer = bottomNavBinding.goToHomeButton.parent as? View
        bottomNavBinding.goToHomeButton.visibility = if (canUploadContent) View.VISIBLE else View.GONE
        goToHomeContainer?.visibility = if (canUploadContent) View.VISIBLE else View.GONE
        if (canUploadContent) {
            bottomNavBinding.goToHomeButton.setOnClickListener {
                val nav = findNavController()
                if (nav.currentDestination?.id == R.id.exploreFragment) {
                    nav.navigate(R.id.action_exploreFragment_to_contentUploadFragment)
                }
            }
        } else {
            bottomNavBinding.goToHomeButton.setOnClickListener(null)
        }

        bottomNavBinding.activityButton.setOnClickListener {
            val nav = findNavController()
            if (nav.currentDestination?.id == R.id.exploreFragment) {
                updateBottomNavSelection(bottomNavBinding, "activity")
                nav.navigate(R.id.action_exploreFragment_to_notificacionesFragment)
            }
        }
        bottomNavBinding.profileNavButton.setOnClickListener {
            val nav = findNavController()
            if (nav.currentDestination?.id == R.id.exploreFragment) {
                updateBottomNavSelection(bottomNavBinding, "profile")
                nav.navigate(R.id.action_exploreFragment_to_profileFragment)
            }
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

    private fun checkPendingPayment(courseId: Long) {
        if (currentUsername == null) return
        
        showFloatingMessage("Verificando pago...", 2000)
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Poll for status - Backend updates via Wompi Webhook
                // Webhook can take 5-30 seconds to arrive, so poll multiple times
                var isPaid = false
                // Try up to 12 times (every 3 seconds = ~36 seconds total)
                repeat(12) { i ->
                    if (isPaid) return@repeat
                    if (i > 0) kotlinx.coroutines.delay(3000)
                    
                    isPaid = withContext(Dispatchers.IO) {
                        try {
                             if (isNetworkAvailable()) {
                                 val result = BackendApiService.hasPurchasedCourse(courseId)
                                 (result is ApiResult.Success && result.data) || run {
                                     val enrollResult = BackendApiService.isEnrolled(courseId)
                                     enrollResult is ApiResult.Success && enrollResult.data
                                 }
                             } else false
                        } catch (e: Exception) { false }
                    }
                }
                
                if (isPaid) {
                    pendingPaymentCourseId = null
                    val course = coursesList.find { it.id == courseId } ?: allCoursesList.find { it.id == courseId }
                    
                    if (course != null) {
                         showDarkToast("✅ ¡Pago confirmado! Entrando al curso...")
                         navigateToCourseDetail(course)
                    } else {
                        // Reload and try to find
                        loadCourses(forceRemote = true)
                        // Note: we can't easily navigate if we don't have the object. 
                        // But loadCourses will refresh the UI at least.
                        showDarkToast("✅ ¡Pago confirmado! Selecciona el curso nuevamente.")
                    }
                } else {
                    // Don't clear immediately if we want to allow manual refresh, 
                    // but to avoid loops, let's clear it and ask user to check manually
                    pendingPaymentCourseId = null
                    showDailyMsg("El pago aún no se refleja. Si ya pagaste, espera unos segundos y recarga.")
                }
                
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error verifying payment", e)
                pendingPaymentCourseId = null
            }
        }
    }
    
    // Helper to show msg if toast is annoying
    private fun showDailyMsg(msg: String) {
        showDarkToast(msg)
    }

    override fun onResume() {
        super.onResume()
        
        // Re-register network callback if it was unregistered or null
        if (networkCallback == null) {
            setupNetworkMonitoring()
        }
        
        // Re-evaluate role-based UI in case the user switched accounts
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val canUpload = sessionManager.hasRole(3) || sessionManager.hasRole(2)
        _canAddCourse.value = canUpload
        
        // Update username in case it changed
        val newUsername = getCurrentUsername()
        if (newUsername != currentUsername) {
            currentUsername = newUsername
            // User switched: clear local state and reload
            allCoursesList.clear()
            coursesList.clear()
            currentPage = 0
            val newUserId = sessionManager.getUserId()
            cachedUserId = if (newUserId != -1L) newUserId else null
        }
        
        // Update bottom nav upload button visibility
        view?.findViewById<View>(R.id.bottomNavigation)?.let { bottomNavView ->
            val bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)
            val goToHomeContainer = bottomNavBinding.goToHomeButton.parent as? View
            bottomNavBinding.goToHomeButton.visibility = if (canUpload) View.VISIBLE else View.GONE
            goToHomeContainer?.visibility = if (canUpload) View.VISIBLE else View.GONE
            if (canUpload) {
                bottomNavBinding.goToHomeButton.setOnClickListener {
                    findNavController().navigate(R.id.action_exploreFragment_to_contentUploadFragment)
                }
            } else {
                bottomNavBinding.goToHomeButton.setOnClickListener(null)
            }
        }
        
        // Check for pending payment redirection
        if (pendingPaymentCourseId != null) {
            // Delay slightly to allow network to init
            Handler(Looper.getMainLooper()).postDelayed({
                checkPendingPayment(pendingPaymentCourseId!!)
            }, 500)
        }
        
        if (coursesList.isEmpty()) {
            loadCoursesWithCache()
        } else if (!com.example.tareamov.util.AppCache.isCoursesFresh()) {
            // Only refresh from network if cache TTL expired
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(300)
                loadCourses(forceRemote = true)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        stopCurrentPreview()
        previewHandler.removeCallbacks(previewRunnable)
        // Unregister network callback to avoid leaks
        networkCallback?.let {
            val ctx = context ?: return@let
            val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
        stopCurrentPreview()
        previewHandler.removeCallbacksAndMessages(null)

        // Dismiss payment dialog if it's showing
        paymentInitiationDialog?.dismiss()
        paymentInitiationDialog = null

        // Ensure callback is unregistered
        networkCallback?.let {
            val ctx = context ?: return@let
            val connectivityManager = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }

    // Setup real-time observation of course changes - DISABLED, using BackendApiService
    private fun setupCourseObservation() {
        // Observation disabled - we now load all courses directly from BackendApiService
        // This avoids showing stale local data instead of full server data
        Log.d("ExploreFragment", "Course observation from Room DISABLED - using BackendApiService direct fetch")
    }

    // Generate thumbnails preventively for videos without them - REMOVED (Obsolete)
    // private fun generatePreventiveThumbnails(videoDataList: List<VideoData>) { ... }

    // Convert Course to VideoData for adapter compatibility
    private suspend fun convertCourseToVideoData(course: Course): VideoData {
        // Fetch creator username from user_id
        val creatorUsername = withContext(Dispatchers.IO) {
            val result = BackendApiService.getUserById(course.creatorUserId)
            if (result is ApiResult.Success) result.data.usuario else null
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
        val ctx = context ?: return null
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(ctx)
        val username = sessionManager.getUsername()
        Log.d("ExploreFragment", "Current username from SessionManager: $username")
        return username
    }

    /**
     * Show custom dark themed Toast message
     */
    private fun showDarkToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val ctx = context ?: return
        val toast = Toast.makeText(ctx, message, duration)
        val view = toast.view
        view?.background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.dark_toast_background)
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
        // val iconIv = view.findViewById<ImageView>(R.id.floatingIconImageView) // Unused
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
                    val result = BackendApiService.getUserByUsername(currentUsername!!)
                    if (result is ApiResult.Success) result.data.id else null
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
                
                if (isCurrentlySubscribed) {
                    // UNSUBSCRIBE via BackendApiService
                    val unsubscribeResult = withContext(Dispatchers.IO) {
                        BackendApiService.unsubscribe(creatorUserId)
                    }
                    
                    if (unsubscribeResult is ApiResult.Success) {
                        showDarkToast("✅ Te has desuscrito correctamente")
                        Log.d("ExploreFragment", "User $currentUserId unsubscribed from creator $creatorUserId")
                    } else if (unsubscribeResult is ApiResult.Error && isIgnoredSubscriptionError(unsubscribeResult)) {
                        Log.d("ExploreFragment", "Duplicate unsubscribe ignored for creator $creatorUserId")
                    } else {
                        showDarkToast("❌ Error al desuscribirse, intenta de nuevo")
                    }
                } else {
                    // SUBSCRIBE via BackendApiService
                    val subscribeResult = withContext(Dispatchers.IO) {
                        BackendApiService.subscribe(creatorUserId)
                    }
                    
                    if (subscribeResult is ApiResult.Success) {
                        showDarkToast("🎉 ¡Te has suscrito exitosamente!")
                        Log.d("ExploreFragment", "User $currentUserId subscribed to creator $creatorUserId")
                    } else if (subscribeResult is ApiResult.Error && isIgnoredSubscriptionError(subscribeResult)) {
                        Log.d("ExploreFragment", "Duplicate subscribe ignored for creator $creatorUserId")
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

    private fun isIgnoredSubscriptionError(result: ApiResult.Error): Boolean {
        return result.code == 409 ||
            result.message.contains("en progreso", ignoreCase = true) ||
            result.message.contains("procesada recientemente", ignoreCase = true)
    }
    
    /**
     * Handle enrollment click for any authenticated user who still needs approval.
     */
    private fun handleEnrollmentClick(course: Course) {
        if (currentUsername == null) {
            showDarkToast("¡Debes iniciar sesión para inscribirte!")
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (hasCollaboratorAccess(course.id)) {
                    showDarkToast("Accediendo al curso como colaborador")
                    navigateToCourseDetail(course)
                    return@launch
                }

                // Get creator username from user_id
                val creatorUsername = withContext(Dispatchers.IO) {
                    val result = BackendApiService.getUserById(course.creatorUserId)
                    if (result is ApiResult.Success) result.data.usuario else null
                }
                
                if (creatorUsername == null) {
                    showDarkToast("❌ Error: No se pudo obtener el nombre del creador")
                    return@launch
                }
                
                if (currentUsername == creatorUsername) {
                    showDarkToast("No puedes inscribirte en tu propio curso")
                    return@launch
                }

                // Get user ID from username early, as it is needed for payment check
                val userId = withContext(Dispatchers.IO) {
                    val result = BackendApiService.getUserByUsername(currentUsername!!)
                    if (result is ApiResult.Success) result.data.id else null
                }
                
                if (userId == null) {
                    Log.e("ExploreFragment", "Failed to get user ID for username: $currentUsername")
                    return@launch
                }
        
        // Handle paid courses (price > 0)
        if (course.price > 0) {
            // First check if course has been successfully purchased (check both 'successful' and 'APPROVED' statuses)
            val isPurchased = withContext(Dispatchers.IO) {
                try {
                    val result = BackendApiService.hasPurchasedCourse(course.id)
                    result is ApiResult.Success && result.data
                } catch (e: Exception) {
                    Log.e("ExploreFragment", "Error checking purchase status", e)
                    false
                }
            }
            
            if (isPurchased) {
                // Course already purchased - proceed with enrollment
                showDarkToast("✅ Curso ya comprado, inscribiendo...")
            } else {
                // Check legacy enrollment (old system)
                val isPaid = withContext(Dispatchers.IO) {
                    try {
                        // Check remote enrollment via BackendApiService
                        if (isNetworkAvailable()) {
                             val enrollResult = BackendApiService.isEnrolled(course.id)
                             if (enrollResult is ApiResult.Success && enrollResult.data) return@withContext true
                        }
                        false
                    } catch (e: Exception) {
                         Log.e("ExploreFragment", "Error checking paid status", e)
                         false
                    }
                }

                if (!isPaid) {
                    try {
                        showPaymentInitiationDialog(course) {
                            // Cancel payment - user canceled the dialog
                            Log.d("ExploreFragment", "Payment initiation canceled by user")
                        }
                        val paymentUrl = withContext(Dispatchers.IO) {
                            val paymentResult = BackendApiService.initiatePayment(mapOf(
                                "userId" to userId,
                                "courseId" to course.id
                            ))
                            if (paymentResult is ApiResult.Success) {
                                // Try multiple field names for URL compatibility
                                paymentResult.data.get("urlBankPayment")?.asString
                                    ?: paymentResult.data.get("paymentUrl")?.asString
                                    ?: paymentResult.data.getAsJsonObject("data")?.get("checkoutUrl")?.asString
                            } else {
                                Log.e("ExploreFragment", "Payment initiation failed: $paymentResult")
                                null
                            }
                        }
                        
                        if (!paymentUrl.isNullOrEmpty()) {
                            dismissPaymentInitiationDialog()
                            Log.d("ExploreFragment", "Redirecting to payment URL: $paymentUrl")
                            // Set pending payment flag to check on return
                            pendingPaymentCourseId = course.id
                            
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(paymentUrl))
                            startActivity(intent)
                            showDarkToast("Por favor completa el pago en Wompi", Toast.LENGTH_LONG)
                            return@launch
                        } else {
                             dismissPaymentInitiationDialog()
                             showDarkToast("Error al obtener enlace de pago. Intenta nuevamente.")
                             Log.e("ExploreFragment", "Payment URL was null")
                             return@launch
                        }
                    } catch (e: Exception) {
                        dismissPaymentInitiationDialog()
                        showDarkToast("Error iniciando pago: ${e.message}")
                        Log.e("ExploreFragment", "Error initiating payment", e)
                        return@launch
                    }
                }
            }
        }
                requestEnrollmentOnAccessAttempt(course)
                
            } catch (e: Exception) {
                Log.e("ExploreFragment", "❌ Error enrolling in course", e)
                showDarkToast("❌ Error al inscribirse: ${e.message}")
            }
        }
    }

    private suspend fun hasCollaboratorAccess(courseId: Long): Boolean {
        val sessionManager = SessionManager.getInstance(requireContext())
        if (sessionManager.hasRole(3) || !sessionManager.hasRole(2)) return false

        return try {
            val result = withContext(Dispatchers.IO) {
                BackendApiService.checkCollaboratorAccess(courseId)
            }
            result is ApiResult.Success && (result.data.get("hasAccess")?.asBoolean == true)
        } catch (e: Exception) {
            Log.w("ExploreFragment", "Could not resolve collaborator access for course $courseId", e)
            false
        }
    }

    private fun openCourseSubjects(course: Course, isCreator: Boolean) {
        val navController = findNavController()
        val bundle = Bundle().apply {
            putLong("courseId", course.id)
            putString("courseName", course.title)
            putBoolean("isCreator", isCreator)
            putLong("creatorUserId", course.creatorUserId)
        }
        navController.navigate(R.id.action_exploreFragment_to_subjectsListFragment, bundle)
    }

    private suspend fun requestEnrollmentOnAccessAttempt(course: Course, isRetry: Boolean = false) {
        val enrollResult = withContext(Dispatchers.IO) {
            BackendApiService.requestEnrollment(course.id)
        }

        if (enrollResult is ApiResult.Success) {
            Log.d("ExploreFragment", "✅ Enrollment requested for $currentUsername in course ${course.id}")
            showDarkToast(
                if (isRetry) {
                    "✅ Se envió una nueva solicitud. Un administrador debe aprobar tu acceso."
                } else {
                    "✅ Solicitud enviada. Un administrador debe aprobar tu acceso."
                }
            )
            coursesAdapter.notifyDataSetChanged()
        } else {
            Log.w("ExploreFragment", "⚠️ Failed to request enrollment")
            showDarkToast("❌ Error al solicitar inscripción")
        }
    }

    private fun setupRecyclerViews(view: View) {
        // Setup for "My Courses" RecyclerView
        val coursesRecyclerView = view.findViewById<RecyclerView>(R.id.coursesRecyclerView)
        coursesRecyclerView.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
        // Keep cache small - adapter cancels/restarts work on rebind, so large caches waste memory
        coursesRecyclerView.setItemViewCacheSize(4)

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
                                val result = BackendApiService.getUserById(course.creatorUserId)
                                if (result is ApiResult.Success) result.data.usuario else null
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
            },
            onPaymentClickListener = { course ->
                // Show payment options dialog
                handlePaymentFlow(course)
            },
            // Solo usuarios con rol 3 (admin) tienen permisos de dueño sobre todos los cursos
            // Rol 2 (docente) solo puede modificar sus propios cursos (controlado por isOwner en el adapter)
            hasAdminRole = SessionManager.getInstance(requireContext()).hasRole(3)
        )
        coursesRecyclerView.adapter = coursesAdapter

        // Resolve current user id once and pass to adapter (no blocking per row)
        if (currentUsername != null) {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = BackendApiService.getUserByUsername(currentUsername!!)
                    val uid = if (result is ApiResult.Success) result.data.id else null
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
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.exploreFragment) return

        val sessionManager = SessionManager.getInstance(requireContext())
        val isCreator = sessionManager.hasRole(3)

        // Solo administracion puede omitir la validacion de matricula.
        if (sessionManager.hasRole(3)) {
            openCourseSubjects(course, isCreator)
            return
        }

        // Cualquier usuario no admin necesita matricula aprobada para entrar.
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (hasCollaboratorAccess(course.id)) {
                    openCourseSubjects(course, false)
                    return@launch
                }

                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getEnrollmentStatus(course.id)
                }
                if (result is ApiResult.Success) {
                    when (result.data.getEnrollmentStatusOrNull()) {
                        "approved" -> {
                            openCourseSubjects(course, false)
                        }
                        "pending" -> {
                            showDarkToast("Tu solicitud de acceso está pendiente de aprobación.")
                        }
                        "rejected" -> {
                            requestEnrollmentOnAccessAttempt(course, isRetry = true)
                        }
                        else -> {
                            requestEnrollmentOnAccessAttempt(course)
                        }
                    }
                } else {
                    requestEnrollmentOnAccessAttempt(course)
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error checking enrollment status", e)
                requestEnrollmentOnAccessAttempt(course)
            }
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
        if (canUserModifyCourse(videoData)) {
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
            Log.w("ExploreFragment", "Update denied: user lacks admin role")
        }
    }

    private fun deleteCourseFromTable(courseId: Long, creatorUsername: String, onDeleted: (() -> Unit)? = null) {
        val ctx = context ?: return
        if (!SessionManager.getInstance(ctx).hasRole(3)) {
            showDarkToast("Solo el administrador puede eliminar cursos")
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            Log.d("ExploreFragment", "Deleting course $courseId as admin. creatorUsername=$creatorUsername")
            try {
                    showDarkToast("Eliminando curso...")

                    withContext(Dispatchers.IO) {
                        courseRepository.deleteCourseById(courseId)

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

                        // Update stats immediately so the header reflects the change
                        if (_totalCourses.value > 0) {
                            _totalCourses.value = _totalCourses.value - 1
                        }

                        // Show success message
                        showDarkToast("Curso eliminado exitosamente")
                        Log.d("ExploreFragment", "Course deleted successfully: $courseId")

                        // Invoke callback after successful deletion (UI thread)
                        try { onDeleted?.invoke() } catch (t: Throwable) { /* ignore */ }

                        // Reload courses to ensure consistency (skip filter-aware guard by forcing remote)
                        val wasFilterActive = isFilterActive
                        isFilterActive = false
                        loadCourses(forceRemote = true)
                        isFilterActive = wasFilterActive
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        showDarkToast("Error al eliminar el curso: ${e.message}")
                        Log.e("ExploreFragment", "Error deleting course from table: $courseId", e)
                    }
                }
            }
    }

    // Method to edit course - Only for course creators
    private fun editCourse(course: VideoData) {
        if (canUserModifyCourse(course)) {
            Log.d("ExploreFragment", "Edit course requested: ${course.title}")

            // Create edit dialog with dark theme
            val ctx = context ?: return
            val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_edit_course, null)
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
                        showDarkToast("✅ Curso actualizado: $newTitle")
                    } else {
                        showDarkToast("❌ El título no puede estar vacío")
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
            showDarkToast("❌ Solo el administrador puede editar el curso")
            Log.w("ExploreFragment", "Edit denied: user lacks admin role")
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
                showDarkToast("Sincronizando cambios...")

                // After local update, attempt to upsert to backend (non-blocking)
                try {
                    val courseEntity = courseRepository.convertVideoDataToCoursePublic(updatedCourse)
                    Log.d("ExploreFragment", "Triggering updateCourse for courseEntity.id=${courseEntity.id} title='${courseEntity.title}'")
                    withContext(Dispatchers.IO) {
                        BackendApiService.updateCourse(courseEntity.id, mapOf(
                            "title" to courseEntity.title,
                            "description" to courseEntity.description
                        ))
                    }
                    com.example.tareamov.util.AppCache.invalidateCourses()
                } catch (e: Exception) {
                    Log.w("ExploreFragment", "Failed to trigger remote upsert for course update", e)
                } finally {
                    // Dismiss the transient toast by showing a quick confirmation toast
                    showDarkToast("Cambio guardado")
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error updating course details", e)
            }
        }
    }

    // Method to check if current user can perform CRUD operations on a course
    private fun canUserModifyCourse(course: VideoData): Boolean {
        val ctx = context ?: return false
        val hasAdminRole = SessionManager.getInstance(ctx).hasRole(3)
        Log.d("ExploreFragment", "Can user modify course '${course.title}'? $hasAdminRole (isAdmin: $hasAdminRole)")
        return hasAdminRole
    }

    // Method to check if current user can perform CRUD operations on a Course entity
    private suspend fun canUserModifyCourse(course: Course): Boolean {
        val ctx = context ?: return false
        val hasAdminRole = SessionManager.getInstance(ctx).hasRole(3)
        Log.d("ExploreFragment", "Can user modify course entity '${course.title}'? $hasAdminRole (creator_user_id: '${course.creatorUserId}', isAdmin: $hasAdminRole)")
        return hasAdminRole
    }

    // Get courses created by current user only
    private suspend fun getUserOwnedCourses(): List<Course> {
        if (currentUsername == null) return emptyList()
        
        val currentUserId = withContext(Dispatchers.IO) {
            val result = BackendApiService.getUserByUsername(currentUsername!!)
            if (result is ApiResult.Success) result.data.id else null
        }
        
        return try {
            if (currentUserId == null) return emptyList()
            // Prefer authoritative server-side list of courses created by this user
            withContext(Dispatchers.IO) {
                val result = BackendApiService.getCoursesByCreatorId(currentUserId)
                if (result is ApiResult.Success) result.data else emptyList()
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
                val result = BackendApiService.getCourses(1, 200)
                if (result is ApiResult.Success) result.data else emptyList()
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
                if (currentUsername != null) {
                    val result = BackendApiService.getUserByUsername(currentUsername!!)
                    if (result is ApiResult.Success) result.data.id else null
                } else null
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
                    // Subir miniatura al backend (que se encarga de almacenarla en la nube)
                    var finalThumbnailUri = imageUri.toString()
                    Toast.makeText(requireContext(), "Subiendo miniatura...", Toast.LENGTH_SHORT).show()
                    try {
                        val contentResolver = requireContext().contentResolver
                        val mimeType = contentResolver.getType(imageUri) ?: "image/jpeg"
                        val inputStream = contentResolver.openInputStream(imageUri)
                        if (inputStream != null) {
                            val bytes = inputStream.readBytes()
                            inputStream.close()
                            val uploadResult = withContext(Dispatchers.IO) {
                                BackendApiService.uploadFile(
                                    fileBytes = bytes,
                                    fileName = "course_${course.id}_${System.currentTimeMillis()}.jpg",
                                    mimeType = mimeType,
                                    folder = "thumbnails/courses"
                                )
                            }
                            if (uploadResult is ApiResult.Success) {
                                val url = uploadResult.data?.get("url")?.asString
                                if (!url.isNullOrBlank()) {
                                    finalThumbnailUri = url
                                    Log.d("ExploreFragment", "☁️ Thumbnail uploaded via backend: $finalThumbnailUri")
                                }
                            } else {
                                Log.w("ExploreFragment", "❌ Failed to upload thumbnail via backend")
                                Toast.makeText(requireContext(), "Error subiendo miniatura, usando local", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("ExploreFragment", "Error uploading thumbnail via backend", e)
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
                        val courseEntity = courseRepository.convertVideoDataToCoursePublic(updatedCourse)
                        Log.d("ExploreFragment", "Triggering updateCourse for thumbnail change courseEntity.id=${courseEntity.id} title='${courseEntity.title}'")
                        BackendApiService.updateCourse(courseEntity.id, mapOf(
                            "thumbnailUri" to courseEntity.thumbnailUri
                        ))
                        com.example.tareamov.util.AppCache.invalidateCourses()
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

    private fun loadCoursesWithCache() {
        val cached = com.example.tareamov.util.AppCache.getCachedCoursesOrStale()
        if (cached != null && cached.isNotEmpty()) {
            val sorted = cached.sortedByDescending { it.timestamp }
            allCoursesList.clear(); allCoursesList.addAll(sorted)
            coursesList.clear(); coursesList.addAll(sorted)
            if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
            stopSkeletonAnimation()
            fetchAndDisplayCourseStats()
            viewLifecycleOwner.lifecycleScope.launch {
                kotlinx.coroutines.delay(200)
                loadCourses(forceRemote = true)
            }
        } else {
            startSkeletonAnimation()
            loadCourses(forceRemote = true)
        }
    }

    /**
     * Load courses with pagination (10 at a time)
     * Uses BackendApiService pagination for better performance
     */
    private fun loadCourses(forceRemote: Boolean = false) {
        if (isLoadingCourses) return

        if (isFilterActive) {
            Log.d("ExploreFragment", "loadCourses: filter active, skipping")
            return
        }

        isLoadingCourses = true
        BackendApiService.initialize(requireContext())

        if (coursesList.isEmpty()) startSkeletonAnimation()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pageResult = withContext(Dispatchers.IO) {
                    BackendApiService.getCoursesPaginated(1, pageSize)
                }

                val firstPage: List<Course>
                val fetchedTotal: Int

                if (pageResult is ApiResult.Success) {
                    firstPage = pageResult.data.data
                    fetchedTotal = pageResult.data.pagination?.total ?: firstPage.size
                } else {
                    firstPage = emptyList()
                    fetchedTotal = 0
                }

                totalCourses = fetchedTotal
                currentPage = 1
                hasTriggeredLoadAtPosition5 = false

                if (firstPage.isNotEmpty()) {
                    com.example.tareamov.util.AppCache.putCourses(firstPage)
                }

                withContext(Dispatchers.Main) {
                    allCoursesList.clear()
                    allCoursesList.addAll(firstPage.sortedByDescending { it.timestamp })

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

                    // Always stop skeleton once loading finishes (success path)
                    stopSkeletonAnimation()

                    // Generate thumbnails for the first page in background
                    generateMissingThumbnails(firstPage)
                }

            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error loading courses", e)
                // Only stop skeleton if we have data to show
                if (coursesList.isNotEmpty()) {
                    stopSkeletonAnimation()
                    context?.let { 
                        Toast.makeText(it, "Error cargando cursos: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
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
        // Skip pagination only when a text search is active (category filters should paginate)
        if (isFilterActive && _searchText.value.isNotEmpty()) {
            Log.d("ExploreFragment", "loadMoreCourses: text search active, skipping load")
            hasTriggeredLoadAtPosition5 = false
            return
        }
        // Quick-filters (popular=8, new=7) load all data at once; no pagination needed
        if (isFilterActive && currentFilterIndex >= 7) {
            Log.d("ExploreFragment", "loadMoreCourses: non-paginated filter (index=$currentFilterIndex), skipping")
            hasTriggeredLoadAtPosition5 = false
            return
        }
        
        // Load next page from BackendApiService when available
        if (isLoadingCourses) return
        
        // Check if we reached the total (if known)
        if (totalCourses > 0 && coursesList.size >= totalCourses) {
             Log.d("ExploreFragment", "No more courses to load: displayed=${coursesList.size}, total=$totalCourses")
             return
        }

        isLoadingCourses = true

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pageNumber = currentPage + 1
                
                val result = withContext(Dispatchers.IO) {
                    val session = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                    val uid = session.getUserId()
                    
                    when (currentFilterIndex) {
                        0 -> BackendApiService.getCoursesPaginated(pageNumber, pageSize)
                        1 -> { // My Created
                             BackendApiService.getCoursesByCreatorIdPaginated(uid, pageNumber, pageSize)
                        }
                        2 -> { // Others - use excludeUserId so backend filters server-side
                             BackendApiService.getCoursesPaginated(pageNumber, pageSize, excludeUserId = if (uid > 0) uid else null)
                        }
                        3 -> BackendApiService.getPremiumCoursesPaginated(pageNumber, pageSize)
                        4 -> BackendApiService.getFreeCoursesPaginated(pageNumber, pageSize)
                        5 -> { // Enrolled (progreso_estudiante)
                            BackendApiService.getEnrolledCoursesPaginated(
                                userId = if (uid > 0) uid else null,
                                page = pageNumber,
                                limit = pageSize
                            )
                        }
                        6 -> { // Purchased (transactions)
                            BackendApiService.getPurchasedCoursesPaginated(
                                userId = if (uid > 0) uid else null,
                                page = pageNumber,
                                limit = pageSize
                            )
                        }
                        else -> BackendApiService.getCoursesPaginated(pageNumber, pageSize)
                    }
                }

                if (result is ApiResult.Success) {
                    val incoming = result.data.data
                    val meta = result.data.pagination
                    
                    if (meta != null) {
                        // Backend now returns correct total for all filters including 'others'
                        totalCourses = meta.total
                        _totalCourses.value = meta.total
                    }

                    if (incoming.isEmpty()) {
                         Log.d("ExploreFragment", "loadMoreCourses: no items returned for page=$pageNumber")
                    } else {
                        // No need for client-side filtering - backend handles excludeUserId
                        
                        // Avoid duplicates in displayed list
                        val existingIds = coursesList.map { it.id }.toSet()
                        val newItems = incoming.filter { !existingIds.contains(it.id) }
                        
                        val sorted = newItems.sortedByDescending { it.timestamp }
                        coursesList.addAll(sorted)
                        
                        withContext(Dispatchers.Main) {
                            if (::coursesAdapter.isInitialized) {
                                coursesAdapter.updateCourses(coursesList)
                            }
                        }
                        currentPage = pageNumber
                        Log.d("ExploreFragment", "Loaded page $pageNumber with ${sorted.size} new courses")
                    }
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error loading next page of courses", e)
            } finally {
                isLoadingCourses = false
                hasTriggeredLoadAtPosition5 = false
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
            // Trigger video preview for the center item after updating the adapter
            previewHandler.removeCallbacks(previewRunnable)
            previewHandler.postDelayed(previewRunnable, 1000)

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
    
    // Helper kept for backward compatibility - no longer uses AppDatabase
    // All callers have been migrated to BackendApiService directly
    // This method is now unused and can be removed in cleanup

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
                // Use improved searchCourses which searches in:
                // - Course title, description, category, tags, creator_username
                // - Users matching the query and their courses
                val repo = BackendApiService
                val results = withContext(Dispatchers.IO) {
                    try {
                        val searchResult = BackendApiService.searchCourses(q)
                        if (searchResult is ApiResult.Success) searchResult.data else emptyList()
                    } catch (e: Exception) {
                        emptyList<Course>()
                    }
                }

                if (results.isNotEmpty()) {
                    displayCourses(results)
                    return@launch
                }

                // Fallback: Local search if remote returns nothing
                val localResults = allCoursesList.filter { course ->
                    course.title.contains(q, ignoreCase = true) ||
                            (course.description?.contains(q, ignoreCase = true) == true) ||
                            (course.category?.contains(q, ignoreCase = true) == true) ||
                            (course.tags?.contains(q, ignoreCase = true) == true)
                }.sortedByDescending { it.timestamp }

                displayCourses(localResults)
                if (localResults.isEmpty()) {
                    showDarkToast("❌ No se encontraron resultados para '$q'")
                }

            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filterCourses", e)
                showDarkToast("❌ Error en la búsqueda")
                // Final fallback: show all courses on error
                displayCourses(allCoursesList.sortedByDescending { it.timestamp })
            }
        }
    }

    // Show filter options dialog with modern BottomSheet    // Show filter options dialog with modern BottomSheet
    @Suppress("DEPRECATION")
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

        try {
            val blurAlgorithm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                RenderEffectBlur()
            } else {
                RenderScriptBlur(requireContext())
            }
            blurView.setupWith(rootView, blurAlgorithm)
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(20f)
                .setBlurAutoUpdate(true)
                .setOverlayColor(android.graphics.Color.parseColor("#CC1E1E1E")) // Match item background color with transparency
        } catch (e: Exception) {
            Log.w("ExploreFragment", "BlurView setup failed in filter sheet", e)
            blurView.setBlurEnabled(false)
        }

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
            FilterOption("", "Cursos comprados", 6) { 
                filterPurchasedCourses()
            },
            FilterOption("", "Mis cursos (Creados)", 1) { 
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
        // Prefer server-side authoritative list for premium courses (is_premium = true)
        currentFilterIndex = 3
        isFilterActive = true
        _searchText.value = ""
        isLoadingCourses = true
        
        lifecycleScope.launch {
            try {
                if (isNetworkAvailable()) {
                    // Backend API fetch for premium courses with pagination
                    // Pass excludeUserId so backend both filters and counts correctly
                    val result = withContext(Dispatchers.IO) {
                        BackendApiService.getPremiumCoursesPaginated(1, pageSize)
                    }

                    if (result is ApiResult.Success) {
                        val incoming = result.data.data
                        val meta = result.data.pagination
                        
                        val premium = incoming.sortedByDescending { it.timestamp }

                        coursesList.clear()
                        coursesList.addAll(premium)
                        
                        // Reset pagination state
                        currentPage = 1
                        totalCourses = meta?.total ?: premium.size
                        hasTriggeredLoadAtPosition5 = false
                        
                        if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                        
                        // Use backend reported total (correctly counts all is_premium=true courses)
                        updateFilteredCourseStats(
                            totalCount = meta?.total ?: premium.size,
                            filterType = "premium"
                        )
                    } else {
                        // Fallback empty
                        coursesList.clear()
                        if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                        updateFilteredCourseStats(0, "premium")
                    }
                    
                    updateActiveFilterUI("Cursos Premium")
                    showDarkToast("Mostrando cursos premium")
                    isLoadingCourses = false
                    return@launch
                }
                
                // Offline fallback: use local list
                val premiumCourses = allCoursesList.filter { it.isPremium == true }.sortedByDescending { it.timestamp }
                coursesList.clear(); coursesList.addAll(premiumCourses)
                if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                
                updateFilteredCourseStats(
                    totalCount = premiumCourses.size,
                    filterType = "premium"
                )
                
                updateActiveFilterUI("Cursos Premium")
                isLoadingCourses = false
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering premium courses", e)
                isLoadingCourses = false
            }
        }
    }

    // Filter free courses
    private fun filterFreeCourses() {
        // Prefer server-side authoritative list for free courses and exclude session user
        currentFilterIndex = 4
        isFilterActive = true
        _searchText.value = ""
        isLoadingCourses = true
        
        lifecycleScope.launch {
            try {
                if (isNetworkAvailable()) {
                    val result = withContext(Dispatchers.IO) {
                         BackendApiService.getFreeCoursesPaginated(1, pageSize)
                    }

                    if (result is ApiResult.Success) {
                        val incoming = result.data.data
                        val meta = result.data.pagination
                        
                        val free = incoming.sortedByDescending { it.timestamp }

                        coursesList.clear()
                        coursesList.addAll(free)
                        
                        currentPage = 1
                        totalCourses = meta?.total ?: free.size
                        hasTriggeredLoadAtPosition5 = false

                        if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                        
                        updateFilteredCourseStats(
                            totalCount = meta?.total ?: free.size,
                            filterType = "free"
                        )
                    } else {
                        coursesList.clear()
                        if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                         updateFilteredCourseStats(0, "free")
                    }
                    
                    updateActiveFilterUI("Cursos Gratis")
                    showDarkToast("Mostrando cursos gratis")
                    isLoadingCourses = false
                    return@launch
                }

                // Offline fallback: use local list
                val freeCourses = allCoursesList.filter { it.isPremium != true }.sortedByDescending { it.timestamp }
                coursesList.clear(); coursesList.addAll(freeCourses)
                if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                
                updateFilteredCourseStats(
                    totalCount = freeCourses.size,
                    filterType = "free"
                )
                
                updateActiveFilterUI("Cursos Gratis")
                isLoadingCourses = false
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering free courses", e)
                isLoadingCourses = false
            }
        }
    }

    // Filter enrolled courses (from progreso_estudiante table)
    private fun filterEnrolledCourses() {
        currentFilterIndex = 5
        isFilterActive = true
        isLoadingCourses = true
        _searchText.value = ""
        currentPage = 0
        hasTriggeredLoadAtPosition5 = false
        
        lifecycleScope.launch {
            try {
                val session = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val uid = session.getUserId()
                
                // Use /courses/enrolled endpoint which queries progreso_estudiante table
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getEnrolledCoursesPaginated(
                        userId = if (uid > 0) uid else null,
                        page = 1,
                        limit = pageSize
                    )
                }
                
                if (result !is ApiResult.Success) {
                    withContext(Dispatchers.Main) {
                        showDarkToast("Error al obtener mis inscripciones")
                        isLoadingCourses = false
                    }
                    return@launch
                }
                
                val enrolledCourses = result.data.data
                val meta = result.data.pagination
                Log.d("ExploreFragment", "Se obtuvieron ${enrolledCourses.size} cursos inscritos (pág 1), total=${meta?.total}")
                
                withContext(Dispatchers.Main) {
                    val sorted = enrolledCourses.sortedByDescending { it.timestamp }
                    coursesList.clear()
                    coursesList.addAll(sorted)
                    
                    totalCourses = meta?.total ?: sorted.size
                    currentPage = 1
                    
                    if (::coursesAdapter.isInitialized) {
                        coursesAdapter.updateCourses(coursesList)
                    }
                    
                    val total = meta?.total ?: sorted.size
                    showDarkToast(if (total == 0) "No tienes inscripciones" else "Mostrando inscripciones ($total)")
                    
                    updateFilteredCourseStats(total, "enrolled")
                    updateActiveFilterUI("Mis Inscripciones")
                    
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
     * Muestra el total real de cursos según el filtro, obtenido del servidor
     */
    private fun updateFilteredCourseStats(totalCount: Int, filterType: String) {
        // Mostrar el conteo total de cursos del filtro (viene del backend)
        _totalCourses.value = totalCount
        
        // Los conteos secondarios (popular, new) se mantienen si ya estaban cargados,
        // o se pueden recalcular del subset actual
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val currentTime = System.currentTimeMillis()
                val thirtyDaysAgo = currentTime - (30L * 24 * 60 * 60 * 1000)
                val newCount = coursesList.count { course ->
                    val courseTime = course.timestamp
                    val creationTime = course.creationDate?.let { parseDate(it) } ?: 0
                    maxOf(courseTime, creationTime) > thirtyDaysAgo
                }
                
                _newCourses.value = newCount
                
                Log.d("ExploreFragment", "Filtered stats ($filterType): Total=$totalCount, New=$newCount, Displayed=${coursesList.size}")
            } catch (e: Exception) {
                Log.w("ExploreFragment", "Error calculating filtered stats", e)
            }
        }
    }

    // Filter purchased courses (courses with successful transactions)
    private fun filterPurchasedCourses() {
        currentFilterIndex = 6
        isFilterActive = true
        isLoadingCourses = true
        _searchText.value = ""
        currentPage = 0
        hasTriggeredLoadAtPosition5 = false
        
        lifecycleScope.launch {
            try {
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val currentUserId = sessionManager.getUserId()
                
                if (currentUserId == -1L) {
                    withContext(Dispatchers.Main) {
                        showDarkToast("Debes iniciar sesión para ver tus cursos comprados")
                        coursesList.clear()
                        if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                        updateFilteredCourseStats(0, "purchased")
                        updateActiveFilterUI("Cursos Comprados")
                        isLoadingCourses = false
                    }
                    return@launch
                }
                
                Log.d("ExploreFragment", "Fetching purchased courses for user ID: $currentUserId")
                
                // Use paginated endpoint for proper scrolling support
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getPurchasedCoursesPaginated(currentUserId, page = 1, limit = pageSize)
                }
                
                if (result !is ApiResult.Success) {
                    withContext(Dispatchers.Main) {
                        showDarkToast("Error al obtener cursos comprados")
                        isLoadingCourses = false
                    }
                    return@launch
                }
                
                val purchasedCourses = result.data.data
                val meta = result.data.pagination
                Log.d("ExploreFragment", "Se obtuvieron ${purchasedCourses.size} cursos comprados (pág 1), total=${meta?.total}")
                
                withContext(Dispatchers.Main) {
                    val sorted = purchasedCourses.sortedByDescending { it.timestamp }
                    coursesList.clear()
                    coursesList.addAll(sorted)
                    
                    totalCourses = meta?.total ?: sorted.size
                    currentPage = 1
                    
                    if (::coursesAdapter.isInitialized) {
                        coursesAdapter.updateCourses(coursesList)
                    }
                    
                    val total = meta?.total ?: sorted.size
                    if (total == 0) {
                        showDarkToast("No has comprado ningún curso aún")
                    } else {
                        showDarkToast("Mostrando $total cursos comprados")
                    }
                    updateFilteredCourseStats(total, "purchased")
                    updateActiveFilterUI("Cursos Comprados")
                    isLoadingCourses = false
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering purchased courses", e)
                withContext(Dispatchers.Main) {
                    showDarkToast("Error al cargar cursos comprados: ${e.message}")
                    isLoadingCourses = false
                }
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
     * Always queries BackendApiService for counts - does NOT use local course data.
     * Only fetches GLOBAL stats when no filter is active (currentFilterIndex == 0).
     */
    private fun fetchAndDisplayCourseStats() {
        // If a filter is active, don't override with global stats
        // Each filter function should call updateFilteredCourseStats() instead
        if (isFilterActive && currentFilterIndex != 0) {
            Log.d("ExploreFragment", "Filter active (index=$currentFilterIndex), skipping global stats fetch")
            return
        }
        
        // Initialize with 0 - will be updated from BackendApiService
        _totalCourses.value = 0
        _popularCourses.value = 0
        _newCourses.value = 0

        // Fetch all stats from BackendApiService in a single coroutine
        viewLifecycleOwner.lifecycleScope.launch {
            if (!isNetworkAvailable()) {
                Log.w("ExploreFragment", "Network not available, cannot fetch stats")
                return@launch
            }
            
            try {
                // Use the new /courses/counts endpoint for all counts in a single call
                val session = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val sessionUserId = session.getUserId()

                val countsResult = withContext(Dispatchers.IO) {
                    BackendApiService.getCourseFilterCounts(if (sessionUserId > 0) sessionUserId else null)
                }

                if (countsResult is ApiResult.Success) {
                    val counts = countsResult.data
                    val serverTotal = counts.get("total")?.asInt ?: 0
                    val premiumCount = counts.get("premium")?.asInt ?: 0
                    val freeCount = counts.get("free")?.asInt ?: 0
                    val enrolledCount = counts.get("enrolled")?.asInt ?: 0
                    val purchasedCount = counts.get("purchased")?.asInt ?: 0

                    totalCourses = serverTotal
                    _totalCourses.value = serverTotal
                    val popularCount = counts.get("popular")?.asInt ?: premiumCount
                    _popularCourses.value = popularCount
                    _purchasedCourses.value = purchasedCount

                    Log.d("ExploreFragment", "Global stats from /courses/counts: total=$serverTotal, premium=$premiumCount, free=$freeCount, enrolled=$enrolledCount, purchased=$purchasedCount")
                } else {
                    Log.w("ExploreFragment", "Failed to fetch /courses/counts, falling back to metadata")
                    // Fallback: use old metadata endpoint
                    val serverTotal = withContext(Dispatchers.IO) {
                        try {
                            val result = BackendApiService.getCoursesMetadata(1, 1)
                            if (result is ApiResult.Success) {
                                result.data.get("total")?.asInt ?: 0
                            } else 0
                        } catch (t: Throwable) { 0 }
                    }
                    if (serverTotal > 0) {
                        totalCourses = serverTotal
                        _totalCourses.value = serverTotal
                    }
                }
                
                // Fetch new courses count (last 30 days) - still needs to check dates
                val newCount = withContext(Dispatchers.IO) {
                    try {
                        val result = BackendApiService.getCourses(1, 200)
                        if (result is ApiResult.Success) {
                            val thirtyDaysAgo = System.currentTimeMillis() - (30L * 24 * 60 * 60 * 1000)
                            result.data.count { course ->
                                val courseTime = course.timestamp
                                val creationTime = course.creationDate?.let { parseDate(it) } ?: 0
                                maxOf(courseTime, creationTime) > thirtyDaysAgo
                            }
                        } else 0
                    } catch (t: Throwable) { 0 }
                }
                _newCourses.value = newCount
                
                Log.d("ExploreFragment", "Global stats: total=${_totalCourses.value}, popular=${_popularCourses.value}, new=$newCount, purchased=${_purchasedCourses.value}")
            } catch (e: Exception) {
                Log.w("ExploreFragment", "Failed to fetch stats", e)
            }
        }
    }
    
    // Helper to parse date string to timestamp
    private fun parseDate(dateString: String): Long {
        return try {
            // Check if it's a numeric timestamp string
            if (dateString.matches(Regex("^\\d+$"))) {
                return dateString.toLong()
            }
            // Try multiple date formats
            val formats = arrayOf(
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss.SSS",
                "yyyy-MM-dd'T'HH:mm:ss",
                "yyyy-MM-dd HH:mm:ss",
                "yyyy-MM-dd"
            )
            for (fmt in formats) {
                try {
                    val format = java.text.SimpleDateFormat(fmt, java.util.Locale.getDefault())
                    val parsed = format.parse(dateString)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) { /* try next format */ }
            }
            Log.w("ExploreFragment", "No format matched for date: $dateString")
            0
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
                _searchText.value = ""
                isLoadingCourses = true
                
                val session = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val sessionUserId = session.getUserId()

                if (isNetworkAvailable() && sessionUserId > 0) {
                     val result = withContext(Dispatchers.IO) {
                         BackendApiService.getCoursesByCreatorIdPaginated(sessionUserId, 1, pageSize)
                     }
                     
                     if (result is ApiResult.Success) {
                        val incoming = result.data.data
                        val meta = result.data.pagination
                        
                        val sorted = incoming.sortedByDescending { it.timestamp }
                        coursesList.clear()
                        coursesList.addAll(sorted)
                        
                        currentPage = 1
                        totalCourses = meta?.total ?: sorted.size
                        
                        if (::coursesAdapter.isInitialized) {
                            coursesAdapter.updateCourses(coursesList)
                        }
                        
                        // Use filtered stats instead of global
                        updateFilteredCourseStats(meta?.total ?: sorted.size, "my_courses")
                     } else {
                         coursesList.clear()
                         if (::coursesAdapter.isInitialized) coursesAdapter.updateCourses(coursesList)
                         updateFilteredCourseStats(0, "my_courses")
                     }
                } else {
                    // Offline fallback
                    val myCoursesOnly = getUserOwnedCourses()
                     val sorted = myCoursesOnly.sortedByDescending { it.timestamp }
                    coursesList.clear()
                    coursesList.addAll(sorted)
                    if (::coursesAdapter.isInitialized) {
                        coursesAdapter.updateCourses(coursesList)
                    }
                    updateFilteredCourseStats(sorted.size, "my_courses")
                }

                updateActiveFilterUI("Mis Cursos Creados")
                isLoadingCourses = false
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering user's courses", e)
                Toast.makeText(context, "Error filtrando cursos", Toast.LENGTH_SHORT).show()
                isLoadingCourses = false
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
                _searchText.value = ""
                isLoadingCourses = true
                
                if (isNetworkAvailable()) {
                     val session = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                     val sessionUserId = session.getUserId()
                     
                     // Use excludeUserId parameter so backend filters AND counts correctly
                     val result = withContext(Dispatchers.IO) {
                         BackendApiService.getCoursesPaginated(1, pageSize, excludeUserId = if (sessionUserId > 0) sessionUserId else null)
                     }

                     if (result is ApiResult.Success) {
                        val incoming = result.data.data
                        val meta = result.data.pagination
                        
                        val sorted = incoming.sortedByDescending { it.timestamp }
                        
                        coursesList.clear()
                        coursesList.addAll(sorted)
                        
                        currentPage = 1
                        hasTriggeredLoadAtPosition5 = false
                        
                        // The backend already excluded the user, so meta.total is the correct count
                        totalCourses = meta?.total ?: sorted.size
                        
                        if (::coursesAdapter.isInitialized) {
                            coursesAdapter.updateCourses(coursesList)
                        }
                        
                        // Use server-provided total which correctly excludes user's own courses
                        updateFilteredCourseStats(meta?.total ?: sorted.size, "other_courses")
                     }
                } else {
                    val otherCourses = getOtherUsersCourses()
                    val sorted = otherCourses.sortedByDescending { it.timestamp }
                    coursesList.clear()
                    coursesList.addAll(sorted)
                    if (::coursesAdapter.isInitialized) {
                        coursesAdapter.updateCourses(coursesList)
                    }
                    updateFilteredCourseStats(sorted.size, "other_courses")
                }
                
                updateActiveFilterUI("Cursos de Otros")
                isLoadingCourses = false
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error filtering other users' courses", e)
                Toast.makeText(context, "Error filtrando cursos", Toast.LENGTH_SHORT).show()
                isLoadingCourses = false
            }
        }
    }

    /**
     * Sync subscription to backend
     */
    private suspend fun syncSubscription(subscriberId: Long, creatorId: Long) {
        try {
            BackendApiService.subscribe(creatorId)
            Log.d("ExploreFragment", "Subscription synced via BackendApiService: $subscriberId -> $creatorId")
        } catch (e: Exception) {
            Log.e("ExploreFragment", "Error syncing subscription", e)
        }
    }

    /**
     * Sync unsubscription to backend
     */
    private suspend fun syncUnsubscription(subscriberId: Long, creatorId: Long) {
        try {
            BackendApiService.unsubscribe(creatorId)
            Log.d("ExploreFragment", "Unsubscription synced via BackendApiService: $subscriberId -> $creatorId")
        } catch (e: Exception) {
            Log.e("ExploreFragment", "Error syncing unsubscription", e)
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
        currentFilterIndex = 8

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

    // Show the supplied courses as an active "purchased" filter
    private fun showPurchasedCourses(purchasedList: List<Course>) {
        if (purchasedList.isEmpty()) {
            showDarkToast("No hay cursos comprados disponibles")
            return
        }

        isFilterActive = true
        currentFilterIndex = 6

        // Replace displayed list with the purchased subset
        coursesList.clear()
        coursesList.addAll(purchasedList)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(purchasedList)
        }

        updateActiveFilterUI("Cursos Comprados")
        updateFilteredCourseStats(purchasedList.size, "purchased")
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
                                // Subir miniatura al backend
                                var finalThumbnailUri = thumbnailUri.toString()
                                
                                try {
                                    val ctx = requireContext()
                                    val inputStream = ctx.contentResolver.openInputStream(thumbnailUri)
                                    if (inputStream != null) {
                                        val bytes = inputStream.readBytes()
                                        inputStream.close()
                                        val uploadResult = BackendApiService.uploadFile(
                                            fileBytes = bytes,
                                            fileName = "auto_thumb_${course.id}_${System.currentTimeMillis()}.jpg",
                                            mimeType = "image/jpeg",
                                            folder = "thumbnails/courses/auto"
                                        )
                                        if (uploadResult is ApiResult.Success) {
                                            val url = uploadResult.data?.get("url")?.asString
                                            if (!url.isNullOrBlank()) {
                                                finalThumbnailUri = url
                                                Log.d("ExploreFragment", "☁️ Miniatura automática subida via backend: $finalThumbnailUri")
                                            }
                                        } else {
                                            Log.w("ExploreFragment", "⚠️ Error subiendo miniatura via backend, usando local")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w("ExploreFragment", "⚠️ Error subiendo miniatura via backend", e)
                                }
                                
                                // Actualizar el curso con la nueva miniatura
                                val updatedCourse = course.copy(thumbnailUri = finalThumbnailUri)
                                
                                // Guardar en backend
                                val success = try {
                                    val updateResult = BackendApiService.updateCourse(
                                        course.id,
                                        mapOf("thumbnailUri" to finalThumbnailUri)
                                    )
                                    updateResult is ApiResult.Success
                                } catch (e: Exception) { false }
                                
                                if (success) {
                                    generatedCount++
                                    com.example.tareamov.util.AppCache.invalidateCourses()
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
    
    /**
     * Handle payment flow - redirect to payment gateway
     */
    private fun handlePaymentFlow(course: Course) {
        lifecycleScope.launch {
            try {
                val currentUser = currentUsername
                if (currentUser == null) {
                    showDarkToast("Debes iniciar sesión para realizar el pago")
                    return@launch
                }
                
                // Show PSE payment options (using extension function from CourseDetailFragmentExtensions)
                showPaymentOptions(
                    courseId = course.id,
                    courseName = course.title,
                    coursePrice = course.price,
                    username = currentUser,
                    onPaymentResult = { success ->
                        if (success) {
                            // Navigate to course detail after successful payment
                            navigateToCourseDetail(course)
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error initiating payment", e)
                showDarkToast("Error al iniciar el proceso de pago")
            }
        }
    }
    
    /**
     * Show a styled payment initiation dialog with cancel option
     */
    private fun showPaymentInitiationDialog(course: Course, onCancel: () -> Unit) {
        // Dismiss any existing dialog
        paymentInitiationDialog?.dismiss()
        
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_initiating_payment, null)
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // Set course information
        val courseNameText = dialogView.findViewById<TextView>(R.id.courseNameText)
        val coursePriceText = dialogView.findViewById<TextView>(R.id.coursePriceText)
        val cancelButton = dialogView.findViewById<TextView>(R.id.cancelPaymentButton)
        
        courseNameText.text = course.title
        
        // Format price
        val formattedPrice = if (course.price > 0) {
            String.format("$%,.0f COP", course.price)
        } else {
            "Precio no disponible"
        }
        coursePriceText.text = formattedPrice
        
        // Set cancel button listener
        cancelButton.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }
        
        paymentInitiationDialog = dialog
        dialog.show()
    }
    
    /**
     * Dismiss payment initiation dialog
     */
    private fun dismissPaymentInitiationDialog() {
        paymentInitiationDialog?.dismiss()
        paymentInitiationDialog = null
    }
}