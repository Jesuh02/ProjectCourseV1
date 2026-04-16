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
import androidx.recyclerview.widget.DividerItemDecoration
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.example.tareamov.util.GradeReportHelper
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
    private var courseFilterJob: Job? = null
    private var courseFilterRequestId = 0
    private val courseFilterDebounceMs = 250L
    
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
    
    // Current filter index (0=All, 1=My Created, 2=Other, 5=Enrolled)
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

        // FAB ⋮ reportes plataforma: solo visible para rol 3 (admin)
        val fabPlatformReport = view.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabPlatformReport)
        val sessionManagerForReport = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        if (sessionManagerForReport.hasRole(3) || sessionManagerForReport.hasRole(4)) {
            fabPlatformReport.visibility = View.VISIBLE
            fabPlatformReport.setImageResource(android.R.drawable.ic_menu_more)
            fabPlatformReport.setOnClickListener { v ->
                val popup = android.widget.PopupMenu(requireContext(), v)
                popup.menuInflater.inflate(R.menu.menu_report_options, popup.menu)
                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        R.id.action_report -> { showPlatformGradeReportBottomSheet(); true }
                        R.id.action_bulletin -> { showPlatformBulletinBottomSheet(); true }
                        else -> false
                    }
                }
                popup.show()
            }
        }
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
            .run { hasRole(3) || hasRole(4) || hasRole(2) }
        _canAddCourse.value = com.example.tareamov.util.SessionManager
            .getInstance(requireContext()).run { hasRole(3) || hasRole(4) || hasRole(2) }
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

    // ── Boletín de toda la plataforma: selección de curso (solo rol 3) ────
    private fun showPlatformBulletinBottomSheet() {
        val ctx = context ?: return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.DarkBottomSheetDialogTheme)
        val rootLayout = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
        }
        dialog.setContentView(rootLayout)
        dialog.window?.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        rootLayout.addView(android.widget.TextView(ctx).apply {
            text = "Generar Boletín"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })
        rootLayout.addView(android.widget.TextView(ctx).apply {
            text = "Selecciona un curso o abre el boletín de todos"
            setTextColor(android.graphics.Color.parseColor("#8E8E93"))
            textSize = 14f
            setPadding(0, 0, 0, 20)
        })

        val courses = allCoursesList.takeIf { it.isNotEmpty() } ?: coursesList
        if (courses.isEmpty()) {
            rootLayout.addView(android.widget.TextView(ctx).apply {
                text = "No hay cursos disponibles"
                setTextColor(android.graphics.Color.parseColor("#636366"))
                textSize = 14f
            })
            dialog.show()
            return
        }

        // Search field
        val searchEt = android.widget.EditText(ctx).apply {
            hint = "Buscar curso..."
            setHintTextColor(android.graphics.Color.parseColor("#8E8E93"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setBackgroundColor(android.graphics.Color.parseColor("#2C2C2E"))
            setPadding(24, 16, 24, 16)
        }
        rootLayout.addView(searchEt)

        val listContainer = android.widget.LinearLayout(ctx).apply { orientation = android.widget.LinearLayout.VERTICAL }
        rootLayout.addView(listContainer)

        // Mutable holder so the API callback can update the course list
        var bulletinCourses = courses.toList()

        fun renderCourseList(filter: String = "") {
            listContainer.removeAllViews()
            val q = filter.lowercase()
            val filtered = if (q.isBlank()) bulletinCourses else bulletinCourses.filter { it.title.lowercase().contains(q) }
            for (course in filtered) {
                listContainer.addView(android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(16, 12, 16, 12)

                    addView(android.widget.TextView(ctx).apply {
                        text = course.title
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 15f
                        layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        setPadding(0, 12, 12, 12)
                        setOnClickListener {
                            dialog.dismiss()
                            navigateToCourseBulletin(course)
                        }
                    })

                    addView(android.widget.TextView(ctx).apply {
                        text = "Todos"
                        setTextColor(android.graphics.Color.parseColor("#30D158"))
                        textSize = 13f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setPadding(20, 10, 20, 10)
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 12f * resources.displayMetrics.density
                            setColor(android.graphics.Color.parseColor("#1A30D158"))
                            setStroke((1 * resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#4D30D158"))
                        }
                        setOnClickListener {
                            dialog.dismiss()
                            navigateToCourseBulletin(course, openAllBulletins = true)
                        }
                    })
                })
                listContainer.addView(android.view.View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(android.graphics.Color.parseColor("#333333"))
                })
            }
        }

        renderCourseList()
        searchEt.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { renderCourseList(s?.toString() ?: "") }
        })

        // Load ALL courses from API to ensure none are missing due to pagination
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendApiService.initialize(ctx)
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getCoursesPaginated(1, 300)
                }
                if (result is ApiResult.Success && result.data.data.isNotEmpty()) {
                    bulletinCourses = result.data.data
                    withContext(Dispatchers.Main) {
                        renderCourseList(searchEt.text?.toString() ?: "")
                    }
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error loading all courses for bulletin", e)
            }
        }

        dialog.show()
    }

    private fun navigateToCourseBulletin(course: com.example.tareamov.data.entity.Course, openAllBulletins: Boolean = false) {
        val bundle = android.os.Bundle().apply {
            putLong("courseId", course.id)
            putString("courseName", course.title)
            putBoolean("openBulletin", true)
            putBoolean("openAllBulletins", openAllBulletins)
        }
        try {
            findNavController().navigate(R.id.action_exploreFragment_to_subjectsListFragment, bundle)
        } catch (e: Exception) {
            Log.e("ExploreFragment", "Navigation to bulletin failed", e)
        }
    }

    // ── Reporte de notas de toda la plataforma (solo rol 3) ──────────────
    private fun showPlatformGradeReportBottomSheet() {
        val ctx = context ?: return
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(ctx, R.style.DarkBottomSheetDialogTheme)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_platform_grade_report, null)
        dialog.setContentView(sheetView)
        dialog.window?.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val loadingLayout = sheetView.findViewById<LinearLayout>(R.id.platformReportLoading)
        val contentLayout = sheetView.findViewById<LinearLayout>(R.id.platformReportContent)
        val tvLoadingText = sheetView.findViewById<TextView>(R.id.tvPlatformLoadingText)
        val tvLoadingProgress = sheetView.findViewById<TextView>(R.id.tvPlatformLoadingProgress)
        val tvCourseCount = sheetView.findViewById<TextView>(R.id.tvPlatformCourseCount)
        val tvSubCount = sheetView.findViewById<TextView>(R.id.tvPlatformSubCount)
        val tvGradedCount = sheetView.findViewById<TextView>(R.id.tvPlatformGradedCount)
        val tvAverage = sheetView.findViewById<TextView>(R.id.tvPlatformAverage)
        val reportListContainer = sheetView.findViewById<LinearLayout>(R.id.platformReportListContainer)
        val btnPdf = sheetView.findViewById<TextView>(R.id.btnPlatformExportPdf)
        val btnCsv = sheetView.findViewById<TextView>(R.id.btnPlatformExportCsv)
        val btnWord = sheetView.findViewById<TextView>(R.id.btnPlatformExportWord)
        val btnShare = sheetView.findViewById<TextView>(R.id.btnPlatformShare)

        dialog.show()

        // ── Fullscreen toggle ──────────────────────────────────────────────
        val bottomSheetFrame = dialog.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        bottomSheetFrame?.layoutParams?.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        val reportBehavior = bottomSheetFrame?.let { BottomSheetBehavior.from(it) }
        val screenH = resources.displayMetrics.heightPixels
        reportBehavior?.peekHeight = (screenH * 0.65).toInt()
        reportBehavior?.skipCollapsed = false
        reportBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED

        val btnPlatformFullscreen = sheetView.findViewById<android.widget.ImageButton>(R.id.btnPlatformFullscreen)
        var isPlatformFullscreen = false
        btnPlatformFullscreen?.setOnClickListener {
            isPlatformFullscreen = !isPlatformFullscreen
            if (isPlatformFullscreen) {
                reportBehavior?.state = BottomSheetBehavior.STATE_EXPANDED
                btnPlatformFullscreen.imageTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#BF5AF2"))
            } else {
                reportBehavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                btnPlatformFullscreen.imageTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#8E8E93"))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 1. Fetch all courses (up to 300)
                tvLoadingText.text = "Cargando cursos..."
                val coursesResult = withContext(Dispatchers.IO) {
                    BackendApiService.getCourses(1, 300)
                }
                val courses = if (coursesResult is ApiResult.Success) coursesResult.data else emptyList()

                if (courses.isEmpty()) {
                    tvLoadingText.text = "No se encontraron cursos"
                    return@launch
                }

                // 2. Fetch submissions + progress for each course in parallel
                tvLoadingText.text = "Cargando entregas..."
                var loaded = 0
                tvLoadingProgress.text = "0 / ${courses.size} cursos"

                val submissionsByCourse = mutableMapOf<Long, List<com.example.tareamov.data.entity.TaskSubmission>>()
                // username → averageGrade per course (from progreso_curso table)
                val progressByCourse = mutableMapOf<Long, Map<String, Float>>()
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.coroutineScope {
                    val deferreds = courses.map { course ->
                        async {
                            val subsResult = BackendApiService.getSubmissionsByCourse(course.id, 1, 300)
                            val subs = if (subsResult is ApiResult.Success) subsResult.data else emptyList()
                            val progResult = BackendApiService.getProgressAllByCourse(course.id)
                            val progMap = if (progResult is ApiResult.Success) {
                                progResult.data.associate { p ->
                                    val username = p.username ?: return@associate "" to 0f
                                    username to (p.averageGrade ?: 0f)
                                }.filterKeys { it.isNotEmpty() }
                            } else emptyMap<String, Float>()
                            Triple(course.id, subs, progMap)
                        }
                    }
                    for (deferred in deferreds) {
                        val (cId, subs, progMap) = deferred.await()
                        submissionsByCourse[cId] = subs
                        progressByCourse[cId] = progMap
                        loaded++
                        withContext(Dispatchers.Main) {
                            tvLoadingProgress.text = "$loaded / ${courses.size} cursos"
                        }
                    }
                    }
                }

                // 3. Add notSubmitted rows (grade=0) for ALL enrolled students who missed tasks
                val augmentedSubmissionsByCourse = submissionsByCourse.mapValues { (cId, subs) ->
                    val enrolledStudents = progressByCourse[cId]?.keys?.toSet() ?: emptySet()
                    addPlatformNotSubmittedRows(subs, enrolledStudents)
                }

                // 4. Build platform report
                val rows = GradeReportHelper.buildPlatformReport(courses, augmentedSubmissionsByCourse)

                // 5a. Fetch grade sheets per course-subject
                tvLoadingText.text = "Cargando notas por materia..."
                data class PlatformGradeSummary(
                    val studentName: String,
                    val taskAvg: Float?,
                    val participacionAvg: Float?,
                    val examenesAvg: Float?,
                    val comportamientoAvg: Float?,
                    val notaPonderada: Float?
                )
                fun computePlatformGradeSummaries(sheet: com.google.gson.JsonObject): List<PlatformGradeSummary> {
                    val studentsArr = sheet.getAsJsonArray("students") ?: return emptyList()
                    val taskGradesArr = sheet.getAsJsonArray("taskGrades") ?: com.google.gson.JsonArray()
                    val manualGradesArr = sheet.getAsJsonArray("manualGrades") ?: com.google.gson.JsonArray()
                    // Build username → fullName map from students array
                    val fullNameByUsername = mutableMapOf<String, String?>()
                    val students = studentsArr.mapNotNull { elem ->
                        val obj = elem.asJsonObject
                        val uname = obj.get("username")?.asString?.takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
                        val fullName = obj.get("fullName")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                        fullNameByUsername[uname] = fullName
                        uname
                    }
                    val taskSums = mutableMapOf<String, MutableList<Float>>()
                    val participacionSums = mutableMapOf<String, MutableList<Float>>()
                    val examenSums = mutableMapOf<String, MutableList<Float>>()
                    val comportamientoSums = mutableMapOf<String, MutableList<Float>>()
                    for (tg in taskGradesArr) {
                        val obj = tg.asJsonObject
                        val uname = obj.get("studentUsername")?.asString ?: continue
                        val grade = obj.get("grade")?.takeIf { !it.isJsonNull }?.asFloat ?: continue
                        taskSums.getOrPut(uname) { mutableListOf() }.add(grade)
                    }
                    for (mg in manualGradesArr) {
                        val obj = mg.asJsonObject
                        val uname = obj.get("studentUsername")?.asString ?: continue
                        val grade = obj.get("grade")?.takeIf { !it.isJsonNull }?.asFloat ?: continue
                        val rawType = obj.get("gradeType")?.asString ?: continue
                        val gradeType = rawType.replace(Regex("_\\d+$"), "")
                        when (gradeType) {
                            "participacion" -> participacionSums.getOrPut(uname) { mutableListOf() }.add(grade)
                            "examenes" -> examenSums.getOrPut(uname) { mutableListOf() }.add(grade)
                            "comportamiento" -> comportamientoSums.getOrPut(uname) { mutableListOf() }.add(grade)
                        }
                    }
                    return students.map { uname ->
                        val tAvg = taskSums[uname]?.average()?.toFloat()
                        val pAvg = participacionSums[uname]?.average()?.toFloat()
                        val eAvg = examenSums[uname]?.average()?.toFloat()
                        val cAvg = comportamientoSums[uname]?.average()?.toFloat()
                        val avgs = listOfNotNull(tAvg, pAvg, eAvg, cAvg)
                        val nota = if (avgs.isNotEmpty()) avgs.average().toFloat() else null
                        PlatformGradeSummary(fullNameByUsername[uname]?.takeIf { it.isNotBlank() } ?: "Sin nombre registrado", tAvg, pAvg, eAvg, cAvg, nota)
                    }
                }
                // courseId → subjectName → gradeSheet
                val gradeSheetsByCourse = mutableMapOf<Long, MutableMap<String, com.google.gson.JsonObject>>()
                withContext(Dispatchers.IO) {
                    kotlinx.coroutines.coroutineScope {
                        val deferreds = courses.map { course ->
                            async {
                                val subjectsResult = BackendApiService.getSubjectsByCourse(course.id)
                                val subjects = if (subjectsResult is ApiResult.Success) subjectsResult.data else emptyList()
                                val sheetMap = mutableMapOf<String, com.google.gson.JsonObject>()
                                val sheetJobs = subjects.map { s ->
                                    s.name to async {
                                        try {
                                            val r = BackendApiService.getGradeSheet(s.id)
                                            if (r is ApiResult.Success) (r.data as? com.google.gson.JsonObject) else null
                                        } catch (_: Exception) { null }
                                    }
                                }
                                for ((name, job) in sheetJobs) {
                                    val sheet = job.await()
                                    if (sheet != null) sheetMap[name] = sheet
                                }
                                course.id to sheetMap
                            }
                        }
                        for (d in deferreds) {
                            val (cId, sheetMap) = d.await()
                            gradeSheetsByCourse[cId] = sheetMap.toMutableMap()
                        }
                    }
                }

                // 5b. Build row list with course + subject filters
                loadingLayout.visibility = View.GONE
                contentLayout.visibility = View.VISIBLE

                tvCourseCount.text = "${augmentedSubmissionsByCourse.keys.count { (augmentedSubmissionsByCourse[it]?.isNotEmpty()) == true }}"
                tvSubCount.text = "${rows.size}"
                tvGradedCount.text = "${rows.count { it.grade != null }}"
                val allGraded = rows.mapNotNull { it.grade }
                val avg = if (allGraded.isNotEmpty()) String.format("%.1f", allGraded.average()) else "0.0"
                tvAverage.text = avg
                if (allGraded.isNotEmpty()) {
                    val a = allGraded.average().toFloat()
                    tvAverage.setTextColor(android.graphics.Color.parseColor(
                        if (a >= 4f) "#34C759" else if (a >= 3f) "#FF9500" else "#FF453A"
                    ))
                }

                val dp = resources.displayMetrics.density
                val courseNameToId = courses.associate { c ->
                    c.title.ifBlank { "Curso ${c.id}" } to c.id
                }
                val byCourse = rows.groupBy { it.courseName }
                val allCourseNames = byCourse.keys.toList()

                fun gradeColorForPlatform(v: Float?) = when {
                    v == null -> "#636366"; v >= 4f -> "#34C759"; v >= 3f -> "#FF9500"; else -> "#FF453A"
                }

                // Course search bar + dropdown
                val courseSearchContainer = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, (4 * dp).toInt(), 0, (4 * dp).toInt()) }
                }
                val courseEditText = android.widget.EditText(ctx).apply {
                    hint = "Filtrar por curso..."
                    setHintTextColor(android.graphics.Color.parseColor("#636366"))
                    setTextColor(android.graphics.Color.WHITE)
                    background = android.graphics.drawable.GradientDrawable().also { d ->
                        d.setColor(android.graphics.Color.parseColor("#18FFFFFF"))
                        d.cornerRadius = (10 * dp)
                        d.setStroke((1 * dp).toInt(), android.graphics.Color.parseColor("#30FFFFFF"))
                    }
                    setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                    textSize = 13f
                    maxLines = 1
                    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                val courseDropdownLayout = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    background = android.graphics.drawable.GradientDrawable().also { d ->
                        d.setColor(android.graphics.Color.parseColor("#2C2C2E"))
                        d.cornerRadius = (10 * dp)
                        d.setStroke((1 * dp).toInt(), android.graphics.Color.parseColor("#30FFFFFF"))
                    }
                    visibility = android.view.View.GONE
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, (4 * dp).toInt(), 0, 0) }
                }
                courseSearchContainer.addView(courseEditText)
                courseSearchContainer.addView(courseDropdownLayout)

                // Subject filter pills
                val subjectFilterScrollView = android.widget.HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, 0, 0, (6 * dp).toInt()) }
                }
                val subjectPillsRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                subjectFilterScrollView.addView(subjectPillsRow)

                var selectedCourseName: String? = null
                var selectedSubjectName: String? = null
                val filterViewCount = 2

                fun populateCourseDropdown(query: String) {
                    courseDropdownLayout.removeAllViews()
                    val filtered = if (query.isEmpty()) allCourseNames
                    else allCourseNames.filter { it.lowercase().contains(query.lowercase()) }
                    if (filtered.isEmpty() && query.isNotEmpty()) {
                        courseDropdownLayout.visibility = android.view.View.GONE
                        return
                    }
                    fun addDropdownItem(label: String, cName: String?) {
                        val isActive = cName == selectedCourseName
                        courseDropdownLayout.addView(TextView(ctx).apply {
                            text = label
                            textSize = 13f
                            setTextColor(if (isActive) android.graphics.Color.parseColor("#BF5AF2") else android.graphics.Color.WHITE)
                            setPadding((14 * dp).toInt(), (11 * dp).toInt(), (14 * dp).toInt(), (11 * dp).toInt())
                            setOnClickListener {
                                selectedCourseName = cName
                                selectedSubjectName = null
                                courseEditText.setText(cName ?: "")
                                courseEditText.clearFocus()
                                courseDropdownLayout.visibility = android.view.View.GONE
                                rebuildSubjectPills(cName)
                                renderPlatformReport(cName, null)
                                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                                    ?.hideSoftInputFromWindow(courseEditText.windowToken, 0)
                            }
                        })
                    }
                    addDropdownItem("Todos los cursos", null)
                    for (cName in filtered) addDropdownItem(cName, cName)
                    courseDropdownLayout.visibility = android.view.View.VISIBLE
                }
                fun updateSubjectPillStates(active: String?) {
                    for (i in 0 until subjectPillsRow.childCount) {
                        val pill = subjectPillsRow.getChildAt(i) as? TextView ?: continue
                        val isActive = pill.tag == active
                        (pill.background as? android.graphics.drawable.GradientDrawable)?.also { d ->
                            d.setColor(if (isActive) android.graphics.Color.parseColor("#33BF5AF2") else android.graphics.Color.parseColor("#18FFFFFF"))
                            d.setStroke((1 * dp).toInt(), if (isActive) android.graphics.Color.parseColor("#80BF5AF2") else android.graphics.Color.parseColor("#18FFFFFF"))
                        }
                        pill.setTextColor(if (isActive) android.graphics.Color.parseColor("#BF5AF2") else android.graphics.Color.parseColor("#8E8E93"))
                    }
                }

                fun renderPlatformReport(filterCourse: String?, filterSubject: String?) {
                    while (reportListContainer.childCount > filterViewCount) {
                        reportListContainer.removeViewAt(filterViewCount)
                    }

                    // ── INCAT Institution Header (only for INCAT users) ──
                    if (SessionManager.getInstance(ctx).isIncatInstitution()) {
                        val incatHeader = android.widget.LinearLayout(ctx).apply {
                            orientation = android.widget.LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                            setBackgroundColor(android.graphics.Color.parseColor("#0D8B0000"))
                            val drawable = android.graphics.drawable.GradientDrawable().also { d ->
                                d.setColor(android.graphics.Color.parseColor("#0D8B0000"))
                                d.cornerRadius = (8 * dp)
                                d.setStroke((2 * dp).toInt(), android.graphics.Color.parseColor("#8B0000"))
                            }
                            background = drawable
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.setMargins(0, 0, 0, (10 * dp).toInt()) }
                        }
                        val logoView = android.widget.ImageView(ctx).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams((52 * dp).toInt(), (52 * dp).toInt()).apply {
                                marginEnd = (12 * dp).toInt()
                            }
                            adjustViewBounds = true
                            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        }
                        com.bumptech.glide.Glide.with(ctx)
                            .load("https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/incat.jpg")
                            .into(logoView)
                        incatHeader.addView(logoView)
                        val textBlock = android.widget.LinearLayout(ctx).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            gravity = android.view.Gravity.CENTER_HORIZONTAL
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        val incatLines = arrayOf(
                            "POLITECNICO INSTITUCIONAL DEL CARIBE \"INCAT\"",
                            "Licencia de funcionamiento Resolución No 439 del 26 /10/ 2010. Emanada de S. E. M",
                            "Licencia de funcionamiento resolución Nº1952 del 17/12/2010. Emanada de S. E. D.",
                            "Institución Educativa De Formación para el trabajo y el desarrollo humano",
                            "NIT: 900391687-0"
                        )
                        for ((i, line) in incatLines.withIndex()) {
                            textBlock.addView(android.widget.TextView(ctx).apply {
                                text = line
                                gravity = android.view.Gravity.CENTER
                                when (i) {
                                    0 -> { setTextColor(android.graphics.Color.parseColor("#8B0000")); textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD) }
                                    4 -> { setTextColor(android.graphics.Color.parseColor("#8B0000")); textSize = 10f; setTypeface(null, android.graphics.Typeface.BOLD) }
                                    3 -> { setTextColor(android.graphics.Color.parseColor("#CCCCCC")); textSize = 9f; setTypeface(null, android.graphics.Typeface.BOLD) }
                                    else -> { setTextColor(android.graphics.Color.parseColor("#AAAAAA")); textSize = 8f }
                                }
                                setPadding(0, if (i == 0) 0 else (1 * dp).toInt(), 0, 0)
                            })
                        }
                        incatHeader.addView(textBlock)
                        reportListContainer.addView(incatHeader)
                    }

                    val sumColWeights = floatArrayOf(1.5f, 0.9f, 0.7f, 0.7f, 1.1f, 0.9f)
                    val sumHeaders = arrayOf("Estudiante", "Comportamiento", "Tareas", "Examen", "Participación", "Nota Final")
                    val filteredByCourse = if (filterCourse == null) byCourse else byCourse.filterKeys { it == filterCourse }
                    for ((courseName, courseRows) in filteredByCourse) {
                        val courseId = courseNameToId[courseName]
                        reportListContainer.addView(TextView(ctx).apply {
                            text = courseName
                            setTextColor(android.graphics.Color.parseColor("#BF5AF2"))
                            textSize = 14f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setPadding(0, (14 * dp).toInt(), 0, (4 * dp).toInt())
                        })
                        val bySubject = if (filterSubject == null) courseRows.groupBy { it.subjectName ?: "Sin materia" }
                        else courseRows.groupBy { it.subjectName ?: "Sin materia" }.filterKeys { it == filterSubject }
                        for ((subjectName, subjectRows) in bySubject) {
                            val subjectCard = android.widget.LinearLayout(ctx).apply {
                                orientation = android.widget.LinearLayout.VERTICAL
                                background = android.graphics.drawable.GradientDrawable().also { d ->
                                    d.setColor(android.graphics.Color.parseColor("#12BF5AF2")); d.cornerRadius = (6 * dp)
                                }
                                setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
                                layoutParams = android.widget.LinearLayout.LayoutParams(
                                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                                ).also { it.setMargins(0, (6 * dp).toInt(), 0, (3 * dp).toInt()) }
                            }
                            subjectCard.addView(TextView(ctx).apply {
                                text = subjectName; setTextColor(android.graphics.Color.parseColor("#C3A4E8"))
                                textSize = 12f; setTypeface(typeface, android.graphics.Typeface.BOLD)
                            })
                            reportListContainer.addView(subjectCard)

                            // Grade breakdown section
                            val sheet = if (courseId != null) gradeSheetsByCourse[courseId]?.get(subjectName) else null
                            if (sheet != null) {
                                val summaries = computePlatformGradeSummaries(sheet)
                                if (summaries.isNotEmpty()) {
                                    reportListContainer.addView(TextView(ctx).apply {
                                        text = "PROMEDIOS POR CATEGORÍA"; textSize = 9f
                                        setTextColor(android.graphics.Color.parseColor("#BF5AF2"))
                                        setPadding((8 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt(), (2 * dp).toInt())
                                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                                    })
                                    val summaryHdr = android.widget.LinearLayout(ctx).apply {
                                        orientation = android.widget.LinearLayout.HORIZONTAL
                                        setBackgroundColor(android.graphics.Color.parseColor("#0CBF5AF2"))
                                        setPadding((8 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                                    }
                                    val sumHdrLp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                                    for (i in sumHeaders.indices) {
                                        summaryHdr.addView(TextView(ctx).apply {
                                            text = sumHeaders[i]; textSize = 9f
                                            setTextColor(android.graphics.Color.parseColor("#8E8E93"))
                                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                                            layoutParams = sumHdrLp.also { it.weight = sumColWeights[i] }
                                        })
                                    }
                                    reportListContainer.addView(summaryHdr)
                                    for (s in summaries) {
                                        val summaryRow = android.widget.LinearLayout(ctx).apply {
                                            orientation = android.widget.LinearLayout.HORIZONTAL
                                            setPadding((8 * dp).toInt(), (5 * dp).toInt(), (4 * dp).toInt(), (5 * dp).toInt())
                                        }
                                        val sumVals = arrayOf(s.studentName,
                                            if (s.comportamientoAvg != null) String.format("%.1f", s.comportamientoAvg) else "0.0",
                                            if (s.taskAvg != null) String.format("%.1f", s.taskAvg) else "0.0",
                                            if (s.examenesAvg != null) String.format("%.1f", s.examenesAvg) else "0.0",
                                            if (s.participacionAvg != null) String.format("%.1f", s.participacionAvg) else "0.0",
                                            if (s.notaPonderada != null) String.format("%.1f", s.notaPonderada) else "0.0")
                                        val sumColors = intArrayOf(android.graphics.Color.WHITE,
                                            android.graphics.Color.parseColor(gradeColorForPlatform(s.comportamientoAvg)),
                                            android.graphics.Color.parseColor(gradeColorForPlatform(s.taskAvg)),
                                            android.graphics.Color.parseColor(gradeColorForPlatform(s.examenesAvg)),
                                            android.graphics.Color.parseColor(gradeColorForPlatform(s.participacionAvg)),
                                            android.graphics.Color.parseColor(gradeColorForPlatform(s.notaPonderada)))
                                        val sumRowLp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                                        for (i in sumVals.indices) {
                                            summaryRow.addView(TextView(ctx).apply {
                                                text = sumVals[i]; textSize = 11f; setTextColor(sumColors[i])
                                                if (i == 0 || i == 5) setTypeface(typeface, android.graphics.Typeface.BOLD)
                                                layoutParams = sumRowLp.also { it.weight = sumColWeights[i] }
                                                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                                            })
                                        }
                                        reportListContainer.addView(summaryRow)
                                    }
                                    reportListContainer.addView(android.view.View(ctx).apply {
                                        layoutParams = android.widget.LinearLayout.LayoutParams(
                                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                                        ).also { it.setMargins(0, (4 * dp).toInt(), 0, (6 * dp).toInt()) }
                                        setBackgroundColor(android.graphics.Color.parseColor("#1ABF5AF2"))
                                    })
                                }
                            }

                        }
                        reportListContainer.addView(android.view.View(ctx).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                            ).also { it.setMargins(0, (8 * dp).toInt(), 0, 0) }
                            setBackgroundColor(android.graphics.Color.parseColor("#1AFFFFFF"))
                        })
                    }

                    // ── INCAT Signature & Footer ──
                    if (SessionManager.getInstance(ctx).isIncatInstitution()) {
                        // Signature line
                        reportListContainer.addView(android.view.View(ctx).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                (160 * dp).toInt(), (1 * dp).toInt()
                            ).also { it.setMargins(0, (24 * dp).toInt(), 0, 0) }
                            setBackgroundColor(android.graphics.Color.parseColor("#8B0000"))
                        })
                        reportListContainer.addView(TextView(ctx).apply {
                            text = "AQUILES AMAYA IGUARAN"
                            textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD)
                            setTextColor(android.graphics.Color.WHITE)
                            setPadding(0, (6 * dp).toInt(), 0, 0)
                        })
                        reportListContainer.addView(TextView(ctx).apply {
                            text = "RECOR"
                            textSize = 10f; setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                            setPadding(0, (2 * dp).toInt(), 0, 0)
                        })
                        // Footer divider
                        reportListContainer.addView(android.view.View(ctx).apply {
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (2 * dp).toInt()
                            ).also { it.setMargins(0, (16 * dp).toInt(), 0, 0) }
                            setBackgroundColor(android.graphics.Color.parseColor("#8B0000"))
                        })
                        val footerLines = arrayOf(
                            "Politécnico \"INCAT\", forjando líderes para triunfar!",
                            "SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740",
                            "E-mail: politecnicoincat@gmail.com",
                            "RIOHACHA- LA GUAJIRA"
                        )
                        for ((i, line) in footerLines.withIndex()) {
                            reportListContainer.addView(TextView(ctx).apply {
                                text = line; textSize = 10f
                                gravity = android.view.Gravity.CENTER
                                setTextColor(if (i == 0 || i == 2) android.graphics.Color.parseColor("#8B0000") else android.graphics.Color.parseColor("#CCCCCC"))
                                if (i == 0) { setTypeface(null, android.graphics.Typeface.ITALIC) }
                                if (i == 1 || i == 3) { setTypeface(null, android.graphics.Typeface.BOLD) }
                                setPadding(0, (2 * dp).toInt(), 0, 0)
                            })
                        }
                    }
                }

                fun rebuildSubjectPills(forCourseName: String?) {
                    subjectPillsRow.removeAllViews()
                    val courseId = if (forCourseName != null) courseNameToId[forCourseName] else null
                    val subjectNames = if (courseId != null) gradeSheetsByCourse[courseId]?.keys?.sorted() ?: emptyList() else emptyList()
                    subjectFilterScrollView.visibility = if (subjectNames.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
                    if (subjectNames.isEmpty()) return
                    fun makeSubjectPill(label: String, sName: String?) {
                        subjectPillsRow.addView(TextView(ctx).apply {
                            text = label; textSize = 11f
                            setTextColor(android.graphics.Color.parseColor("#8E8E93"))
                            background = android.graphics.drawable.GradientDrawable().also { d ->
                                d.setColor(android.graphics.Color.parseColor("#18FFFFFF"))
                                d.cornerRadius = (20 * dp)
                                d.setStroke((1 * dp).toInt(), android.graphics.Color.parseColor("#18FFFFFF"))
                            }
                            setPadding((10 * dp).toInt(), (5 * dp).toInt(), (10 * dp).toInt(), (5 * dp).toInt())
                            tag = sName
                            layoutParams = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.setMargins(0, 0, (5 * dp).toInt(), 0) }
                            setOnClickListener {
                                selectedSubjectName = sName
                                updateSubjectPillStates(sName)
                                renderPlatformReport(selectedCourseName, sName)
                            }
                        })
                    }
                    makeSubjectPill("Todas", null)
                    for (sn in subjectNames) makeSubjectPill(sn, sn)
                    updateSubjectPillStates(null)
                }

                courseEditText.setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) populateCourseDropdown(courseEditText.text?.toString()?.trim() ?: "")
                    else courseDropdownLayout.postDelayed({ courseDropdownLayout.visibility = android.view.View.GONE }, 150)
                }
                courseEditText.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val query = s?.toString()?.trim() ?: ""
                        populateCourseDropdown(query)
                        if (query.isEmpty() && selectedCourseName != null) {
                            selectedCourseName = null
                            selectedSubjectName = null
                            rebuildSubjectPills(null)
                            renderPlatformReport(null, null)
                        }
                    }
                })
                subjectFilterScrollView.visibility = android.view.View.GONE

                reportListContainer.removeAllViews()
                reportListContainer.addView(courseSearchContainer)
                reportListContainer.addView(subjectFilterScrollView)
                renderPlatformReport(null, null)

                // 6. Export buttons
                btnPdf.setOnClickListener {
                    val isIncat = SessionManager.getInstance(requireContext()).isIncatInstitution()
                    val file = GradeReportHelper.generatePlatformPDF(ctx, rows, isIncat)
                    if (file != null) GradeReportHelper.shareFile(ctx, file, "application/pdf")
                    else showSafeToast("Error al generar PDF")
                }
                btnCsv.setOnClickListener {
                    val isIncatCsv = SessionManager.getInstance(requireContext()).isIncatInstitution()
                    val file = GradeReportHelper.generatePlatformCSV(ctx, rows, isIncatCsv)
                    if (file != null) GradeReportHelper.shareFile(ctx, file, "application/vnd.ms-excel")
                    else showSafeToast("Error al generar Excel")
                }
                btnWord.setOnClickListener {
                    val isIncatW = SessionManager.getInstance(requireContext()).isIncatInstitution()
                    val file = GradeReportHelper.generatePlatformWord(ctx, rows, isIncatW)
                    if (file != null) GradeReportHelper.shareFile(ctx, file, "application/msword")
                    else showSafeToast("Error al generar Word")
                }
                btnShare.setOnClickListener {
                    GradeReportHelper.shareText(ctx, GradeReportHelper.buildPlatformShareText(rows))
                }

            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error loading platform grade report", e)
                loadingLayout.visibility = View.GONE
                showSafeToast("Error al cargar el reporte")
                dialog.dismiss()
            }
        }
    }

    private fun showSafeToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val ctx = context ?: return
        try { Toast.makeText(ctx, message, duration).show() } catch (_: Exception) {}
    }

    /**
     * Agrega filas sintéticas con grade=0 y notSubmitted=true para TODOS los estudiantes
     * inscritos (de la tabla progreso_curso) que no entregaron una tarea específica.
     * enrolledStudents: conjunto de usuarios inscritos en el curso (desde progreso_curso).
     */
    private fun addPlatformNotSubmittedRows(
        subs: List<com.example.tareamov.data.entity.TaskSubmission>,
        enrolledStudents: Set<String> = emptySet()
    ): List<com.example.tareamov.data.entity.TaskSubmission> {
        // subjectName → taskName → Set<studentUsername>
        val subjectTaskStudents = mutableMapOf<String, MutableMap<String, MutableSet<String>>>()
        // Seed with all enrolled students so even non-submitters appear in the report
        val knownStudents = enrolledStudents.toMutableSet()

        for (sub in subs) {
            val subjectName = sub.subjectName?.takeIf { it.isNotBlank() } ?: continue
            val taskName    = sub.taskName?.takeIf    { it.isNotBlank() } ?: continue
            val student     = sub.studentUsername?.takeIf { it.isNotBlank() } ?: continue
            knownStudents.add(student)
            subjectTaskStudents
                .getOrPut(subjectName) { mutableMapOf() }
                .getOrPut(taskName) { mutableSetOf() }
                .add(student)
        }
        if (knownStudents.isEmpty() || subjectTaskStudents.isEmpty()) return subs

        val extra = mutableListOf<com.example.tareamov.data.entity.TaskSubmission>()
        for ((subjectName, taskMap) in subjectTaskStudents) {
            for ((taskName, submitters) in taskMap) {
                for (student in knownStudents) {
                    if (student !in submitters) {
                        extra.add(com.example.tareamov.data.entity.TaskSubmission(
                            grade = 0f,
                            submissionDate = 0L
                        ).also {
                            it.studentUsername = student
                            it.taskName        = taskName
                            it.subjectName     = subjectName
                            it.notSubmitted    = true
                        })
                    }
                }
            }
        }
        return subs + extra
    }

    override fun onResume() {
        super.onResume()
        
        // Re-register network callback if it was unregistered or null
        if (networkCallback == null) {
            setupNetworkMonitoring()
        }
        
        // Re-evaluate role-based UI in case the user switched accounts
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val canUpload = sessionManager.hasRole(3) || sessionManager.hasRole(4) || sessionManager.hasRole(2)
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
        courseFilterJob?.cancel()
        courseFilterJob = null

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
        val creatorUsername = resolveCreatorUsername(course)
        
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

    private suspend fun resolveCreatorUsername(course: Course): String {
        val embeddedUsername = course.creatorUsername?.takeIf { it.isNotBlank() }
        if (!embeddedUsername.isNullOrBlank()) return embeddedUsername

        return withContext(Dispatchers.IO) {
            val result = BackendApiService.getUserById(course.creatorUserId)
            if (result is ApiResult.Success) result.data.usuario.orEmpty() else ""
        }
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
     * Muestra un diálogo minimalista con los docentes y estudiantes del curso.
     */
    private fun showCoursePersonsDialog(course: Course) {
        val ctx = context ?: return
        val sheet = BottomSheetDialog(ctx, R.style.Theme_TareaMov_BottomSheet)
        val sheetView = layoutInflater.inflate(R.layout.dialog_course_persons, null)
        sheet.setContentView(sheetView)
        sheet.window?.setDimAmount(0.5f)

        val titleTv = sheetView.findViewById<TextView>(R.id.personsDialogTitle)
        val teachersList = sheetView.findViewById<LinearLayout>(R.id.teachersListContainer)
        val studentsListContainer = sheetView.findViewById<LinearLayout>(R.id.studentsListContainer)
        val loadingView = sheetView.findViewById<View>(R.id.personsLoadingView)
        val contentView = sheetView.findViewById<View>(R.id.personsContentView)
        val teachersCountTv = sheetView.findViewById<TextView>(R.id.teachersCountBadge)
        val studentsCountTv = sheetView.findViewById<TextView>(R.id.studentsCountBadge)

        titleTv.text = course.title
            teachersCountTv.visibility = View.GONE
            studentsCountTv.visibility = View.GONE
        loadingView.visibility = View.VISIBLE
        contentView.visibility = View.GONE
        sheet.show()

        fun buildParticipantRow(primaryText: String, secondaryText: String? = null): View {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 8)
                addView(TextView(ctx).apply {
                    text = primaryText
                    textSize = 14f
                    setTextColor(0xDDFFFFFF.toInt())
                })
                if (!secondaryText.isNullOrBlank()) {
                    addView(TextView(ctx).apply {
                        text = "@$secondaryText"
                        textSize = 12f
                        setTextColor(0xFF8E8E93.toInt())
                        setPadding(0, 2, 0, 0)
                    })
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Fetch collaborators (teachers) and enrolled students in parallel
                val teachersDeferred = async(Dispatchers.IO) {
                    try {
                        when (val r = BackendApiService.getCollaboratorsByCourse(course.id)) {
                            is ApiResult.Success -> {
                                (0 until r.data.size()).mapNotNull { i ->
                                    val obj = r.data.get(i)?.asJsonObject ?: return@mapNotNull null
                                    val userObj = obj.getAsJsonObject("user")
                                    val userId = obj.get("userId")?.asLong
                                        ?: obj.get("user_id")?.asLong
                                        ?: userObj?.get("id")?.asLong
                                        ?: return@mapNotNull null
                                    val username = userObj?.get("username")?.let { if (it.isJsonNull) null else it.asString }
                                    userId to username
                                }
                            }
                            else -> emptyList()
                        }
                    } catch (_: Exception) { emptyList() }
                }

                // Fetch subject collaborators (docentes asignados a materias del curso)
                val subjectCollabsDeferred = async(Dispatchers.IO) {
                    try {
                        val subjectsResult = BackendApiService.getSubjectsByCourse(course.id)
                        if (subjectsResult is ApiResult.Success && subjectsResult.data.isNotEmpty()) {
                            val allSubjectCollabs = mutableListOf<Pair<Long, String?>>()
                            for (subject in subjectsResult.data) {
                                val scResult = BackendApiService.getSubjectCollaborators(subject.id)
                                if (scResult is ApiResult.Success) {
                                    (0 until scResult.data.size()).mapNotNull { i ->
                                        val obj = scResult.data.get(i)?.asJsonObject ?: return@mapNotNull null
                                        val userObj = obj.getAsJsonObject("user")
                                        val uid = obj.get("userId")?.asLong
                                            ?: obj.get("user_id")?.asLong
                                            ?: userObj?.get("id")?.asLong
                                            ?: return@mapNotNull null
                                        val uname = userObj?.get("username")?.let { if (it.isJsonNull) null else it.asString }
                                        allSubjectCollabs.add(uid to uname)
                                    }
                                }
                            }
                            allSubjectCollabs.distinctBy { it.first }
                        } else emptyList()
                    } catch (_: Exception) { emptyList() }
                }

                val studentsDeferred = async(Dispatchers.IO) {
                    try {
                        when (val r = BackendApiService.getCourseGuests(course.id)) {
                            is ApiResult.Success -> {
                                (0 until r.data.size()).mapNotNull { i ->
                                    val obj = r.data.get(i)?.asJsonObject ?: return@mapNotNull null
                                    val userId = obj.get("userId")?.asLong
                                        ?: obj.get("user_id")?.asLong
                                        ?: return@mapNotNull null
                                    val username = obj.get("username")?.let { if (it.isJsonNull) null else it.asString }
                                        ?.takeIf { it.isNotBlank() }
                                    userId to username
                                }.distinctBy { it.first }
                            }
                            else -> emptyList()
                        }
                    } catch (_: Exception) { emptyList() }
                }

                val teachers = teachersDeferred.await()
                val subjectCollabs = subjectCollabsDeferred.await()
                val students = studentsDeferred.await()

                // Merge course collaborators + subject collaborators as teachers
                val teacherIds = teachers.map { it.first }.toMutableSet()
                val allTeachers = teachers.toMutableList()
                for (sc in subjectCollabs) {
                    if (teacherIds.add(sc.first)) {
                        allTeachers.add(sc)
                    }
                }

                val participantIds = (allTeachers.map { it.first } + students.map { it.first }).distinct()
                val personaNames = if (participantIds.isNotEmpty()) {
                    when (val result = withContext(Dispatchers.IO) { BackendApiService.getPersonasByUserIds(participantIds) }) {
                        is ApiResult.Success -> result.data.associate { item ->
                            item.userId to item.fullName.trim().ifBlank {
                                listOf(item.nombres.trim(), item.apellidos.trim()).filter { it.isNotBlank() }.joinToString(" ")
                            }
                        }
                        else -> emptyMap()
                    }
                } else {
                    emptyMap()
                }

                fun resolvePrimaryName(userId: Long, username: String?): Pair<String, String?> {
                    val fullName = personaNames[userId]?.trim().orEmpty()
                    val fallbackUsername = username?.trim().orEmpty()
                    val primary = fullName.ifBlank { fallbackUsername.ifBlank { "Usuario #$userId" } }
                    val secondary = fallbackUsername.takeIf { it.isNotBlank() && it != primary }
                    return primary to secondary
                }

                withContext(Dispatchers.Main) {
                    loadingView.visibility = View.GONE
                    contentView.visibility = View.VISIBLE

                    teachersList.removeAllViews()
                    if (allTeachers.isEmpty()) {
                        val emptyTv = TextView(ctx).apply {
                            text = "Sin docentes asignados"
                            textSize = 13f
                            setTextColor(0x4DFFFFFF.toInt())
                            setPadding(0, 4, 0, 4)
                            setTypeface(null, android.graphics.Typeface.ITALIC)
                        }
                        teachersList.addView(emptyTv)
                    } else {
                        allTeachers.forEach { (userId, username) ->
                            val (primary, secondary) = resolvePrimaryName(userId, username)
                            teachersList.addView(buildParticipantRow(primary, secondary))
                        }
                    }

                    studentsListContainer.removeAllViews()
                    if (students.isEmpty()) {
                        val emptyTv = TextView(ctx).apply {
                            text = "Sin estudiantes registrados"
                            textSize = 13f
                            setTextColor(0x4DFFFFFF.toInt())
                            setPadding(0, 4, 0, 4)
                            setTypeface(null, android.graphics.Typeface.ITALIC)
                        }
                        studentsListContainer.addView(emptyTv)
                    } else {
                        students.forEach { (userId, username) ->
                            val (primary, secondary) = resolvePrimaryName(userId, username)
                            studentsListContainer.addView(buildParticipantRow(primary, secondary))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error loading course persons", e)
                withContext(Dispatchers.Main) {
                    loadingView.visibility = View.GONE
                    contentView.visibility = View.VISIBLE
                }
            }
        }
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
        if (sessionManager.hasRole(3) || sessionManager.hasRole(4) || !sessionManager.hasRole(2)) return false

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

    private suspend fun requestEnrollmentOnAccessAttempt(course: Course, isRetry: Boolean = false, requestedRole: String = "student") {
        val enrollResult = withContext(Dispatchers.IO) {
            BackendApiService.requestEnrollment(course.id, requestedRole)
        }

        if (enrollResult is ApiResult.Success) {
            Log.d("ExploreFragment", "✅ Enrollment requested for $currentUsername in course ${course.id} as $requestedRole")
            val roleSuffix = if (requestedRole == "docente") " como docente" else ""
            showDarkToast(
                if (isRetry) {
                    "✅ Se envió una nueva solicitud$roleSuffix. Un administrador debe aprobar tu acceso."
                } else {
                    "✅ Solicitud enviada$roleSuffix. Un administrador debe aprobar tu acceso."
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
            hasAdminRole = SessionManager.getInstance(requireContext()).run { hasRole(3) || hasRole(4) },
            onInfoClickListener = { course -> showCoursePersonsDialog(course) }
        )
        coursesRecyclerView.adapter = coursesAdapter

        // Separadores visuales entre filas
        val rowDivider = DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL)
        coursesRecyclerView.addItemDecoration(rowDivider)

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
        val isCreator = sessionManager.hasRole(3) || sessionManager.hasRole(4)

        // Solo administracion puede omitir la validacion de matricula.
        if (sessionManager.hasRole(3) || sessionManager.hasRole(4)) {
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
                            // Verificar si el usuario está matriculado (invitado) antes de pedir nueva solicitud
                            val enrolled = withContext(Dispatchers.IO) {
                                try {
                                    val enrollResult = BackendApiService.isEnrolled(course.id)
                                    enrollResult is ApiResult.Success && enrollResult.data
                                } catch (e: Exception) { false }
                            }
                            if (enrolled) {
                                openCourseSubjects(course, false)
                            } else {
                                showRoleSelectionOrEnroll(course, isRetry = true)
                            }
                        }
                        else -> {
                            // null/unknown: verificar si el usuario está matriculado (invitado)
                            val enrolled = withContext(Dispatchers.IO) {
                                try {
                                    val enrollResult = BackendApiService.isEnrolled(course.id)
                                    enrollResult is ApiResult.Success && enrollResult.data
                                } catch (e: Exception) { false }
                            }
                            if (enrolled) {
                                openCourseSubjects(course, false)
                            } else {
                                showRoleSelectionOrEnroll(course)
                            }
                        }
                    }
                } else {
                    // Fallback: verificar isEnrolled si getEnrollmentStatus falla
                    val enrolled = withContext(Dispatchers.IO) {
                        try {
                            val enrollResult = BackendApiService.isEnrolled(course.id)
                            enrollResult is ApiResult.Success && enrollResult.data
                        } catch (e: Exception) { false }
                    }
                    if (enrolled) {
                        openCourseSubjects(course, false)
                    } else {
                        showRoleSelectionOrEnroll(course)
                    }
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error checking enrollment status", e)
                // Fallback: verificar isEnrolled
                try {
                    val enrolled = withContext(Dispatchers.IO) {
                        val enrollResult = BackendApiService.isEnrolled(course.id)
                        enrollResult is ApiResult.Success && enrollResult.data
                    }
                    if (enrolled) {
                        openCourseSubjects(course, false)
                        return@launch
                    }
                } catch (ex: Exception) {
                    Log.w("ExploreFragment", "isEnrolled fallback also failed", ex)
                }
                showRoleSelectionOrEnroll(course)
            }
        }
    }

    /**
     * Shows a role selection dialog if the user is a docente (role 2),
     * otherwise directly requests enrollment as student.
     */
    private fun showRoleSelectionOrEnroll(course: Course, isRetry: Boolean = false) {
        val sessionManager = SessionManager.getInstance(requireContext())
        if (!sessionManager.hasRole(2)) {
            // Not a docente, request as student directly
            viewLifecycleOwner.lifecycleScope.launch {
                requestEnrollmentOnAccessAttempt(course, isRetry, "student")
            }
            return
        }

        // Show role selection dialog for docente users
        val dialog = AlertDialog.Builder(requireContext(), R.style.DarkAlertDialogTheme)
            .setTitle("¿Cómo deseas ingresar?")
            .setMessage("Selecciona el rol con el que deseas solicitar acceso a este curso.")
            .setPositiveButton("Docente") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    requestEnrollmentOnAccessAttempt(course, isRetry, "docente")
                }
            }
            .setNegativeButton("Estudiante") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    requestEnrollmentOnAccessAttempt(course, isRetry, "student")
                }
            }
            .setNeutralButton("Cancelar", null)
            .create()
        dialog.show()
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
        if (!SessionManager.getInstance(ctx).run { hasRole(3) || hasRole(4) }) {
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
        val hasAdminRole = SessionManager.getInstance(ctx).run { hasRole(3) || hasRole(4) }
        Log.d("ExploreFragment", "Can user modify course '${course.title}'? $hasAdminRole (isAdmin: $hasAdminRole)")
        return hasAdminRole
    }

    // Method to check if current user can perform CRUD operations on a Course entity
    private suspend fun canUserModifyCourse(course: Course): Boolean {
        val ctx = context ?: return false
        val hasAdminRole = SessionManager.getInstance(ctx).run { hasRole(3) || hasRole(4) }
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

    private suspend fun searchCoursesByCreatorIdentity(query: String): List<Course> {
        val users = when (val result = BackendApiService.searchUsers(query)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> emptyList()
        }

        val creatorIds = users.map { it.id }.filter { it > 0 }.distinct()
        if (creatorIds.isEmpty()) return emptyList()

        return creatorIds.flatMap { creatorId ->
            when (val result = BackendApiService.getCoursesByCreatorId(creatorId)) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> emptyList()
            }
        }
    }

    private fun mergeUniqueCourses(vararg lists: List<Course>): List<Course> {
        return lists
            .asSequence()
            .flatMap { it.asSequence() }
            .distinctBy { it.id }
            .sortedByDescending { it.timestamp }
            .toList()
    }

    private fun cancelPendingCourseFilter() {
        courseFilterJob?.cancel()
        courseFilterJob = null
        courseFilterRequestId += 1
    }

    // Filter courses by name, category, creator username, or creator cédula.
    private fun filterCourses(query: String) {
        val q = query.trim()
        courseFilterJob?.cancel()

        // If query is empty, clear filter and show all
        if (q.isEmpty()) {
            courseFilterRequestId += 1
            isFilterActive = false
            val sorted = allCoursesList.sortedByDescending { it.timestamp }
            displayCourses(sorted)
            // Restore original stats when filter is cleared
            fetchAndDisplayCourseStats()
            return
        }

        // Mark filter active to prevent further pagination
        isFilterActive = true
        val requestId = ++courseFilterRequestId

        courseFilterJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                delay(courseFilterDebounceMs)

                val results = withContext(Dispatchers.IO) {
                    try {
                        val directMatches = when (val searchResult = BackendApiService.searchCourses(q)) {
                            is ApiResult.Success -> searchResult.data
                            is ApiResult.Error -> emptyList()
                        }

                        val creatorMatches = if (q.all { it.isDigit() } || directMatches.isEmpty()) {
                            searchCoursesByCreatorIdentity(q)
                        } else {
                            emptyList()
                        }

                        mergeUniqueCourses(directMatches, creatorMatches)
                    } catch (e: Exception) {
                        emptyList<Course>()
                    }
                }

                if (requestId != courseFilterRequestId) return@launch

                if (results.isNotEmpty()) {
                    displayCourses(results)
                    return@launch
                }

                // Fallback: Local search if remote returns nothing
                val localResults = allCoursesList.filter { course ->
                    course.title.contains(q, ignoreCase = true) ||
                            (course.description?.contains(q, ignoreCase = true) == true) ||
                            (course.category?.contains(q, ignoreCase = true) == true) ||
                            (course.tags?.contains(q, ignoreCase = true) == true) ||
                            (course.creatorUsername?.contains(q, ignoreCase = true) == true)
                }.sortedByDescending { it.timestamp }

                if (requestId != courseFilterRequestId) return@launch

                displayCourses(localResults)

            } catch (e: Exception) {
                if (requestId != courseFilterRequestId) return@launch
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

        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val isAdmin = sessionManager.hasRole(3) || sessionManager.hasRole(4)

        val baseOptions = listOf(
            FilterOption("📚", "Todos los cursos", 0) { 
                showAllCourses()
            },
            FilterOption("🎓", "Mis inscripciones", 5) { 
                filterEnrolledCourses()
                updateActiveFilterUI("Mis Inscripciones")
            }
        )

        val adminOptions = listOf(
            FilterOption("📚", "Mis Cursos", 1) { 
                filterMyCoursesOnly()
            },
            FilterOption("🌟", "Cursos de otros", 2) { 
                filterOtherCoursesOnly()
            }
        )

        val options = if (isAdmin) baseOptions + adminOptions else baseOptions

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

    // Filter enrolled courses (from progreso_estudiante table)
    private fun filterEnrolledCourses() {
        currentFilterIndex = 5
        isFilterActive = true
        isLoadingCourses = true
        cancelPendingCourseFilter()
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
        cancelPendingCourseFilter()
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
                cancelPendingCourseFilter()
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
                cancelPendingCourseFilter()
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
        cancelPendingCourseFilter()
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