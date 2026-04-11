package com.example.tareamov.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.adapter.CreatedCourseAdapter
import com.example.tareamov.adapter.YouTubeStyleVideoAdapter
import com.example.tareamov.data.entity.ContentType
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.data.entity.Subject
import com.example.tareamov.data.entity.UserContent
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.VideoManager
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.StorageHelper
import com.example.tareamov.service.ServerEndpointResolver
import de.hdodenhof.circleimageview.CircleImageView
import android.view.ViewOutlineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import java.io.File
import java.util.Date

class UserProfileViewFragment : Fragment() {

    private lateinit var userAvatarImageView: CircleImageView
    private lateinit var usernameTextView: TextView
    private lateinit var userBadgeTextView: TextView
    private lateinit var coursesCountTextView: TextView
    private lateinit var videosCountTextView: TextView
    private lateinit var subscribersCountTextView: TextView
    private lateinit var coursesFilterButton: LinearLayout
    private lateinit var videosFilterButton: LinearLayout
    private var materiasFilterButton: LinearLayout? = null
    private lateinit var coursesCountBadge: TextView
    private lateinit var videosCountBadge: TextView
    private var materiasCountBadge: TextView? = null
    private lateinit var searchEditText: EditText
    private lateinit var clearSearchButton: ImageView
    
    // FrameLayout references (replaced BlurView to fix white-out on scroll)
    private var searchBarBlurView: View? = null
    private var filterBlurView: View? = null
    
    // Filter button elements for glass effect styling
    private var coursesFilterIcon: ImageView? = null
    private var coursesFilterText: TextView? = null
    private var videosFilterIcon: ImageView? = null
    private var videosFilterText: TextView? = null
    private var materiasFilterIcon: ImageView? = null
    private var materiasFilterText: TextView? = null
    private lateinit var contentRecyclerView: RecyclerView
    private lateinit var emptyStateTextView: TextView
    private lateinit var contentAdapter: CreatedCourseAdapter
    private lateinit var videoAdapter: YouTubeStyleVideoAdapter
    private lateinit var videoManager: VideoManager
    private lateinit var courseRepository: com.example.tareamov.repository.CourseRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var bottomNavBinding: ComponentBottomNavigationBinding

    private var allContent = mutableListOf<VideoData>()
    private var allCourses = mutableListOf<VideoData>()
    private var allVideos = mutableListOf<VideoData>()
    private var allSubjects = mutableListOf<Subject>()
    private var allUserVideos = mutableListOf<VideoData>() // All videos from the user for search purposes
    private var filteredContent = mutableListOf<VideoData>() // For search results
    private var currentFilter = ContentType.COURSE
    private var isSearchMode = false
    
    // Role of the viewed user's profile
    private var viewedUserRolId: Long = 1L
    
    // Video preview handling
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewRunnable: Runnable? = null
    private var currentPreviewPosition = -1
    private var currentSearchQuery = ""
    
    // Variable para el usuario cuyo perfil se está viendo
    private var username: String? = null
    private var isInitialLoad = true // Prevent duplicate loadUserData on first creation

    // Variables for thumbnail change functionality (similar to ExploreFragment)
    private var currentCourseForThumbnailChange: VideoData? = null
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_user_profile_view, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Setup video update listeners
        setupVideoUpdateListeners()
        
        try {
            // Initialize SessionManager
            sessionManager = SessionManager.getInstance(requireContext())
            
            // Initialize image picker launcher for thumbnail change (like ExploreFragment)
            initializeImagePickerLauncher()

            // Inicializar VideoManager y CourseRepository
            videoManager = VideoManager(requireContext())
            courseRepository = com.example.tareamov.repository.CourseRepository(requireContext())
            
            // Obtener el nombre de usuario pasado como argumento (perfil que se está viendo)
            username = arguments?.getString("username")
            
            // Obtener el nombre de usuario actual (usuario que está usando la app)
            val currentUserUsername = getCurrentUsername()
            
            // Inicializar vistas
            initializeViews(view)

            // Configurar RecyclerView
            setupRecyclerView()

            // Configurar botones de filtro
            setupFilterButtons()

            // Configurar el botón de volver
            view.findViewById<ImageView>(R.id.backButton)?.setOnClickListener {
                findNavController().navigateUp()
            }

            // Cargar datos del usuario
            username?.let {
                loadUserData(it)
            }

            // Setup bottom navigation
            setupBottomNavigation(view)
        } catch (e: Exception) {
            Log.e("UserProfileViewFragment", "Error in onViewCreated: ${e.message}")
            Toast.makeText(context, "Error al cargar el perfil de usuario", Toast.LENGTH_SHORT).show()
            // Navigate back if there's a critical error
            findNavController().navigateUp()
        }
    }

    private fun setupBottomNavigation(view: View) {
        // Initialize the bottom navigation binding
        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        // Ensure bottom navigation is always visible and static
        bottomNavView.visibility = View.VISIBLE
        bottomNavView.translationY = 0f
        bottomNavView.alpha = 1f
        
        // Disable any layout animations to keep it static
        (bottomNavView as? android.view.ViewGroup)?.layoutTransition = null

        // Home Button - Navigate to VideoHome
        bottomNavBinding.homeNavLayout.setOnClickListener {
            findNavController().navigate(R.id.action_userProfileViewFragment_to_videoHomeFragment)
        }
        
        // Explore Button
        bottomNavBinding.exploreButton.setOnClickListener {
            findNavController().navigate(R.id.action_userProfileViewFragment_to_exploreFragment)
        }
        
        // Add/Upload Button (ic_add) only for users with role 2 or 3
        val canUploadContent = sessionManager.hasRole(2) || sessionManager.hasRole(3) || sessionManager.hasRole(4)
        val goToHomeContainer = bottomNavBinding.goToHomeButton.parent as? View
        bottomNavBinding.goToHomeButton.visibility = if (canUploadContent) View.VISIBLE else View.GONE
        goToHomeContainer?.visibility = if (canUploadContent) View.VISIBLE else View.GONE
        if (canUploadContent) {
            bottomNavBinding.goToHomeButton.setOnClickListener {
                findNavController().navigate(R.id.action_userProfileViewFragment_to_contentUploadFragment)
            }
        } else {
            bottomNavBinding.goToHomeButton.setOnClickListener(null)
        }
        
        // Activity Button (ic_activity)
        bottomNavBinding.activityButton.setOnClickListener {
            findNavController().navigate(R.id.action_userProfileViewFragment_to_notificacionesFragment)
        }
        
        // Profile Button (ic_profile) - navegar al perfil propio cuando se pulsa
        bottomNavBinding.profileNavButton.setOnClickListener {
            // Navegar al fragmento `ProfileFragment` (perfil propio)
            findNavController().navigate(R.id.action_userProfileViewFragment_to_profileFragment)
        }

    }

    private fun initializeViews(view: View) {
        try {
            // Use safe property assignment with null checks
            userAvatarImageView = view.findViewById(R.id.userAvatarImageView) ?: throw NullPointerException("userAvatarImageView not found")
            usernameTextView = view.findViewById(R.id.usernameTextView) ?: throw NullPointerException("usernameTextView not found")
            coursesCountTextView = view.findViewById(R.id.coursesCountTextView) ?: throw NullPointerException("coursesCountTextView not found")
            videosCountTextView = view.findViewById(R.id.videosCountTextView) ?: throw NullPointerException("videosCountTextView not found")
            subscribersCountTextView = view.findViewById(R.id.subscribersCountTextView) ?: throw NullPointerException("subscribersCountTextView not found")
            coursesFilterButton = view.findViewById(R.id.coursesFilterButton) ?: throw NullPointerException("coursesFilterButton not found")
            videosFilterButton = view.findViewById(R.id.videosFilterButton) ?: throw NullPointerException("videosFilterButton not found")
            coursesCountBadge = view.findViewById(R.id.coursesCountBadge) ?: throw NullPointerException("coursesCountBadge not found")
            videosCountBadge = view.findViewById(R.id.videosCountBadge) ?: throw NullPointerException("videosCountBadge not found")
            searchEditText = view.findViewById(R.id.searchEditText) ?: throw NullPointerException("searchEditText not found")
            clearSearchButton = view.findViewById(R.id.clearSearchButton) ?: throw NullPointerException("clearSearchButton not found")
            contentRecyclerView = view.findViewById(R.id.contentRecyclerView) ?: throw NullPointerException("contentRecyclerView not found")
            emptyStateTextView = view.findViewById(R.id.emptyStateTextView) ?: throw NullPointerException("emptyStateTextView not found")

            // Initialize BlurViews for glass effect
            searchBarBlurView = view.findViewById(R.id.searchBarBlurView)
            filterBlurView = view.findViewById(R.id.filterBlurView)
            
            // Initialize filter button elements
            coursesFilterIcon = view.findViewById(R.id.coursesFilterIcon)
            coursesFilterText = view.findViewById(R.id.coursesFilterText)
            videosFilterIcon = view.findViewById(R.id.videosFilterIcon)
            videosFilterText = view.findViewById(R.id.videosFilterText)
            materiasFilterButton = view.findViewById(R.id.materiasFilterButton)
            materiasFilterIcon = view.findViewById(R.id.materiasFilterIcon)
            materiasFilterText = view.findViewById(R.id.materiasFilterText)
            materiasCountBadge = view.findViewById(R.id.materiasCountBadge)
            
            // Setup BlurViews
            setupBlurViews(view)

            // Setup search functionality
            setupSearchFunctionality()

        } catch (e: Exception) {
            // Log the error and handle it gracefully
            Log.e("UserProfileViewFragment", "Error initializing views: ${e.message}")
            Toast.makeText(context, "Error al cargar la interfaz del perfil", Toast.LENGTH_SHORT).show()
            // If in a critical error state, navigate back
            findNavController().navigateUp()
        }
    }
    
    /**
     * Setup views for search bar and filters
     * NOTE: BlurView was removed and replaced with FrameLayout to fix white-out issue on scroll.
     * The blur effect caused white backgrounds when RecyclerView content scrolled behind.
     */
    private fun setupBlurViews(view: View) {
        // BlurView setup removed - now using simple FrameLayout with solid dark background
        // This prevents the white-out issue that occurred when content scrolled behind the blur
        Log.d("UserProfileView", "Using solid dark backgrounds instead of BlurView to prevent white-out on scroll")
    }

    private fun setupSearchFunctionality() {
        // Add TextWatcher to search bar
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                performSearch(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Clear search button
        clearSearchButton.setOnClickListener {
            searchEditText.text.clear()
            isSearchMode = false
            currentSearchQuery = ""
            clearSearchButton.visibility = View.GONE
            filterContent()
        }
    }

    private fun performSearch(query: String) {
        currentSearchQuery = query.trim()
        
        if (currentSearchQuery.isEmpty()) {
            isSearchMode = false
            clearSearchButton.visibility = View.GONE
            filterContent()
            return
        }

        isSearchMode = true
        clearSearchButton.visibility = View.VISIBLE

        // Search in both courses and videos based on current filter
        val searchSource = when (currentFilter) {
            ContentType.COURSE -> allCourses
            ContentType.VIDEO -> allVideos
            ContentType.SUBJECT -> allCourses // Subjects handled separately below
        }

        if (currentFilter == ContentType.SUBJECT) {
            // Search in subjects
            val searchResults = allSubjects.filter { subject ->
                subject.name.contains(currentSearchQuery, ignoreCase = true) ||
                subject.description.contains(currentSearchQuery, ignoreCase = true)
            }
            Log.d("UserProfileView", "Search performed for '$currentSearchQuery' in SUBJECT - Found ${searchResults.size} results")
            val subjectAsVideoData = searchResults.map { subject ->
                VideoData(
                    id = subject.id,
                    username = username ?: "",
                    description = subject.description,
                    title = subject.name,
                    videoUriString = "",
                    localFilePath = null,
                    timestamp = 0L,
                    isPaid = false,
                    thumbnailUri = subject.thumbnailUrl,
                    price = null
                )
            }
            contentRecyclerView.adapter = contentAdapter
            contentAdapter.updateCourses(subjectAsVideoData)
            if (searchResults.isEmpty()) {
                emptyStateTextView.visibility = View.VISIBLE
                emptyStateTextView.text = "No se encontraron resultados para '$currentSearchQuery'"
                contentRecyclerView.visibility = View.GONE
            } else {
                emptyStateTextView.visibility = View.GONE
                contentRecyclerView.visibility = View.VISIBLE
            }
            return
        }

        val searchResults = searchSource.filter { content ->
            content.title.contains(currentSearchQuery, ignoreCase = true) ||
            (content.description?.contains(currentSearchQuery, ignoreCase = true) ?: false) ||
            content.username.contains(currentSearchQuery, ignoreCase = true)
        }

        Log.d("UserProfileView", "Search performed for '$currentSearchQuery' in ${currentFilter} - Found ${searchResults.size} results")

        // Update the adapter with search results
        when (currentFilter) {
            ContentType.COURSE -> {
                filteredContent.clear()
                filteredContent.addAll(searchResults)
                contentRecyclerView.adapter = contentAdapter
                contentAdapter.updateCourses(filteredContent)
            }
            ContentType.VIDEO -> {
                contentRecyclerView.adapter = videoAdapter
                videoAdapter.updateVideos(searchResults)
            }
            ContentType.SUBJECT -> {
                // SUBJECT searches return earlier and are handled separately.
            }
        }

        // Show empty state if no results
        if (searchResults.isEmpty()) {
            emptyStateTextView.visibility = View.VISIBLE
            emptyStateTextView.text = "No se encontraron resultados para '$currentSearchQuery'"
            contentRecyclerView.visibility = View.GONE
        } else {
            emptyStateTextView.visibility = View.GONE
            contentRecyclerView.visibility = View.VISIBLE
        }
    }

    private fun setupRecyclerView() {
        try {
            // Obtener el nombre de usuario actual para los permisos
            val currentUserUsername = getCurrentUsername()
            Log.d("UserProfileViewFragment", "Setting up adapter with currentUsername: '$currentUserUsername', viewing profile of: '$username'")
            
            // Configurar adaptador para cursos con funcionalidad CRUD completa (identical to ExploreFragment)
            contentAdapter = CreatedCourseAdapter(
                requireContext(),
                allContent,
                onCourseClickListener = { course ->
                    Log.d("UserProfileView", "Course clicked: ${course.title} by ${course.username}")
                    handleCourseClickWithEnrollment(course)
                },
                currentUsername = currentUserUsername, // Para verificar permisos de edición
                onEditCourseListener = { course ->
                    Log.d("UserProfileView", "Edit course requested: ${course.title}")
                    editCourse(course)
                },
                onDeleteCourseListener = { course ->
                    Log.d("UserProfileView", "Delete course requested: ${course.title}")
                    deleteCourse(course)
                },
                onChangeThumbnailListener = { course ->
                    Log.d("UserProfileView", "Change thumbnail requested: ${course.title}")
                    changeThumbnail(course)
                },
                onSubscriptionClickListener = { course, isSubscribed ->
                    // Convert VideoData to Course entity for subscription logic
                    val courseEntity = com.example.tareamov.data.entity.Course(
                        id = course.id,
                        title = course.title,
                        description = course.description ?: "",
                        creatorUserId = -1L, // Will be resolved in handleSubscriptionClick
                        category = "Programación",
                        thumbnailUri = course.thumbnailUri,
                        videoUri = course.videoUriString,
                        price = course.price ?: 0.0,
                        isPremium = course.isPaid,
                        rating = 0.0f,
                        creationDate = course.timestamp.toString(),
                        timestamp = course.timestamp,
                        localFilePath = course.localFilePath
                    )
                    
                    // We need to resolve creatorUserId first
                    viewLifecycleOwner.lifecycleScope.launch {
                        val creatorIdResult = withContext(Dispatchers.IO) {
                            BackendApiService.getUserByUsername(course.username)
                        }
                        val creatorId = creatorIdResult.getOrNull()?.id
                        if (creatorId != null) {
                            val updatedCourse = courseEntity.copy(creatorUserId = creatorId)
                            handleSubscriptionClick(updatedCourse, isSubscribed)
                        } else {
                            showDarkToast("Error al procesar suscripción: Usuario no encontrado")
                        }
                    }
                },
                onEnrollClickListener = { course ->
                    // Convert VideoData to Course entity for enrollment logic
                    val courseEntity = com.example.tareamov.data.entity.Course(
                        id = course.id,
                        title = course.title,
                        description = course.description ?: "",
                        creatorUserId = -1L, // Will be resolved in handleEnrollmentClick
                        category = "Programación",
                        thumbnailUri = course.thumbnailUri,
                        videoUri = course.videoUriString,
                        price = course.price ?: 0.0,
                        isPremium = course.isPaid,
                        rating = 0.0f,
                        creationDate = course.timestamp.toString(),
                        timestamp = course.timestamp,
                        localFilePath = course.localFilePath
                    )
                    
                    // We need to resolve creatorUserId first
                    viewLifecycleOwner.lifecycleScope.launch {
                        val creatorIdResult = withContext(Dispatchers.IO) {
                            BackendApiService.getUserByUsername(course.username)
                        }
                        val creatorId = creatorIdResult.getOrNull()?.id
                        if (creatorId != null) {
                            val updatedCourse = courseEntity.copy(creatorUserId = creatorId)
                            handleEnrollmentClick(updatedCourse)
                        } else {
                            showDarkToast("Error al procesar inscripción: Usuario no encontrado")
                        }
                    }
                }
            ,
                showOnlyEditDelete = true
            )

            // Configurar adaptador para videos con estilo YouTube
            videoAdapter = YouTubeStyleVideoAdapter(
                requireContext(),
                mutableListOf(),
                onVideoClickListener = { video ->
                    handleVideoClick(video)
                },
                onEditClickListener = { video ->
                    // Navigate to VideoDetailsFragment for editing
                    try {
                        val bundle = Bundle().apply {
                            putLong("videoId", video.id)
                            putParcelable("videoUri", Uri.parse(video.videoUriString))
                            putString("title", video.title)
                            putString("description", video.description)
                            putBoolean("isPaid", video.isPaid)
                            putBoolean("isEditMode", true)
                        }
                        // Use the ID of the fragment directly if action is not defined, or try to find the action
                        // Assuming standard navigation, we try to navigate to the fragment
                        findNavController().navigate(R.id.videoDetailsFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("UserProfileViewFragment", "Error navigating to edit video", e)
                        Toast.makeText(context, "Error al abrir editor", Toast.LENGTH_SHORT).show()
                    }
                },
                onDeleteClickListener = { video ->
                    // Show ConfirmDeleteDialogFragment and call performDeleteContent(video, false) on confirm
                    val requestKey = "confirm_delete_video_${'$'}{video.id}"
                    parentFragmentManager.setFragmentResultListener(requestKey, viewLifecycleOwner) { _, bundle ->
                        val confirmed = bundle.getBoolean("confirmed", false)
                        if (confirmed) {
                            performDeleteContent(video, isCourse = false)
                        }
                    }

                    val title = "Eliminar video"
                    val message = "¿Estás seguro de que deseas eliminar el video '${'$'}{video.title}'? Esta acción no se puede deshacer."
                    val dialog = ConfirmDeleteDialogFragment.newInstance(requestKey, title, message, confirmText = "Eliminar", cancelText = "Cancelar")
                    dialog.show(parentFragmentManager, requestKey)
                }
            )

            contentRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = contentAdapter // Iniciar con adaptador de cursos
                
                // RecyclerView optimizations
                setHasFixedSize(true)
                setItemViewCacheSize(10)
                
                // Video preview scroll listener
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        when (newState) {
                            RecyclerView.SCROLL_STATE_IDLE -> {
                                // Start preview after 1 second of idle
                                previewRunnable = Runnable { startPreviewForCenterItem() }
                                previewHandler.postDelayed(previewRunnable!!, 1000)
                            }
                            else -> {
                                // Stop preview when scrolling
                                previewHandler.removeCallbacks(previewRunnable ?: return)
                                stopCurrentPreview()
                            }
                        }
                    }
                    
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        if (Math.abs(dy) > 5) {
                            previewHandler.removeCallbacks(previewRunnable ?: return)
                            stopCurrentPreview()
                        }
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("UserProfileViewFragment", "Error setting up RecyclerView: ${e.message}")
            // Handle the error gracefully, possibly showing a message
            try {
                emptyStateTextView.text = "Error al cargar contenido"
                emptyStateTextView.visibility = View.VISIBLE
                contentRecyclerView.visibility = View.GONE
            } catch (e2: Exception) {
                Log.e("UserProfileViewFragment", "Could not show error state: ${e2.message}")
            }
        }
    }

    /**
     * Start video preview for the center item in RecyclerView
     */
    private fun startPreviewForCenterItem() {
        val layoutManager = contentRecyclerView.layoutManager as? LinearLayoutManager ?: return
        val firstPos = layoutManager.findFirstVisibleItemPosition()
        val lastPos = layoutManager.findLastVisibleItemPosition()
        
        if (firstPos == RecyclerView.NO_POSITION || lastPos == RecyclerView.NO_POSITION) return
        
        val centerY = contentRecyclerView.height / 2
        var minDistance = Int.MAX_VALUE
        var centerPos = -1
        
        for (i in firstPos..lastPos) {
            val view = layoutManager.findViewByPosition(i) ?: continue
            val viewCenterY = (view.top + view.bottom) / 2
            val distance = Math.abs(centerY - viewCenterY)
            if (distance < minDistance) {
                minDistance = distance
                centerPos = i
            }
        }
        
        if (centerPos != -1 && centerPos != currentPreviewPosition) {
            stopCurrentPreview()
            
            // Get the video URI based on current adapter type
            val videoUri: String? = if (currentFilter == ContentType.COURSE || currentFilter == ContentType.SUBJECT) {
                val course = contentAdapter.getItem(centerPos)
                course?.localFilePath ?: course?.videoUriString
            } else {
                val video = videoAdapter.getItem(centerPos)
                video?.localFilePath ?: video?.videoUriString
            }
            
            if (!videoUri.isNullOrEmpty()) {
                if (currentFilter == ContentType.COURSE || currentFilter == ContentType.SUBJECT) {
                    val holder = contentRecyclerView.findViewHolderForAdapterPosition(centerPos) as? CreatedCourseAdapter.CourseViewHolder
                    holder?.playPreview(videoUri)
                } else {
                    val holder = contentRecyclerView.findViewHolderForAdapterPosition(centerPos) as? YouTubeStyleVideoAdapter.VideoViewHolder
                    holder?.playPreview(videoUri)
                }
                currentPreviewPosition = centerPos
            }
        }
    }

    /**
     * Stop current video preview
     */
    private fun stopCurrentPreview() {
        if (currentPreviewPosition != -1) {
            if (currentFilter == ContentType.COURSE || currentFilter == ContentType.SUBJECT) {
                val holder = contentRecyclerView.findViewHolderForAdapterPosition(currentPreviewPosition) as? CreatedCourseAdapter.CourseViewHolder
                holder?.stopPreview()
            } else {
                val holder = contentRecyclerView.findViewHolderForAdapterPosition(currentPreviewPosition) as? YouTubeStyleVideoAdapter.VideoViewHolder
                holder?.stopPreview()
            }
            currentPreviewPosition = -1
        }
    }

    private fun setupFilterButtons() {
        coursesFilterButton.setOnClickListener {
            setFilter(ContentType.COURSE)
        }

        materiasFilterButton?.setOnClickListener {
            setFilter(ContentType.SUBJECT)
        }

        videosFilterButton.setOnClickListener {
            setFilter(ContentType.VIDEO)
        }
    }

    /**
     * Muestra u oculta los botones de filtro según el rol del usuario visto.
     * Rol 2 (docente): Materias + Videos (sin Cursos)
     * Rol 3 (admin): Cursos + Materias + Videos
     * Otros: Cursos + Videos (default)
     */
    private fun applyFilterVisibilityForRole(rolId: Long) {
        when (rolId) {
            2L -> {
                // Docente: hide Cursos, show Materias + Videos
                coursesFilterButton.visibility = View.GONE
                materiasFilterButton?.visibility = View.VISIBLE
                videosFilterButton.visibility = View.VISIBLE
                if (currentFilter == ContentType.COURSE) {
                    currentFilter = ContentType.SUBJECT
                    updateFilterButtonsUI()
                    filterContent()
                }
            }
            3L -> {
                // Admin: show all three
                coursesFilterButton.visibility = View.VISIBLE
                materiasFilterButton?.visibility = View.VISIBLE
                videosFilterButton.visibility = View.VISIBLE
            }
            else -> {
                // Student/other: Cursos + Videos (default)
                coursesFilterButton.visibility = View.VISIBLE
                materiasFilterButton?.visibility = View.GONE
                videosFilterButton.visibility = View.VISIBLE
            }
        }
    }
    
    /**
     * Configure filter button visibility based on the viewed user's role (called once on load).
     * Role 2 (Docente): Materias + Videos (no Cursos)
     * Role 3 (Admin): Cursos + Materias + Videos
     * Other: Cursos + Videos (default)
     */
    private fun configureFiltersByRole(rolId: Long) {
        when (rolId) {
            2L -> {
                // Docente: hide Cursos, show Materias + Videos
                coursesFilterButton.visibility = View.GONE
                materiasFilterButton?.visibility = View.VISIBLE
                videosFilterButton.visibility = View.VISIBLE
                // Default to Materias tab
                setFilter(ContentType.SUBJECT)
            }
            3L -> {
                // Admin: show all three
                coursesFilterButton.visibility = View.VISIBLE
                materiasFilterButton?.visibility = View.VISIBLE
                videosFilterButton.visibility = View.VISIBLE
            }
            else -> {
                // Student/other: Cursos + Videos (default)
                coursesFilterButton.visibility = View.VISIBLE
                materiasFilterButton?.visibility = View.GONE
                videosFilterButton.visibility = View.VISIBLE
            }
        }
    }

    private fun setFilter(filterType: ContentType) {
        // Exit search mode if active
        if (isSearchMode) {
            isSearchMode = false
        }

        currentFilter = filterType
        Log.d("UserProfileView", "Filter changed to: $filterType")
        updateFilterButtonsUI()
        filterContent()
    }
    
    private fun updateFilterButtonsUI() {
        val darkColor = android.graphics.Color.parseColor("#1A1A1A")
        val whiteColor = android.graphics.Color.WHITE
        
        // Reset all to unselected
        coursesFilterButton.setBackgroundResource(R.drawable.bg_glass_filter_button_unselected)
        coursesFilterIcon?.setColorFilter(whiteColor)
        coursesFilterText?.setTextColor(whiteColor)
        
        materiasFilterButton?.setBackgroundResource(R.drawable.bg_glass_filter_button_unselected)
        materiasFilterIcon?.setColorFilter(whiteColor)
        materiasFilterText?.setTextColor(whiteColor)
        
        videosFilterButton.setBackgroundResource(R.drawable.bg_glass_filter_button_unselected)
        videosFilterIcon?.setColorFilter(whiteColor)
        videosFilterText?.setTextColor(whiteColor)
        
        // Set selected
        when (currentFilter) {
            ContentType.COURSE -> {
                coursesFilterButton.setBackgroundResource(R.drawable.bg_glass_filter_button_selected)
                coursesFilterIcon?.setColorFilter(darkColor)
                coursesFilterText?.setTextColor(darkColor)
            }
            ContentType.SUBJECT -> {
                materiasFilterButton?.setBackgroundResource(R.drawable.bg_glass_filter_button_selected)
                materiasFilterIcon?.setColorFilter(darkColor)
                materiasFilterText?.setTextColor(darkColor)
            }
            ContentType.VIDEO -> {
                videosFilterButton.setBackgroundResource(R.drawable.bg_glass_filter_button_selected)
                videosFilterIcon?.setColorFilter(darkColor)
                videosFilterText?.setTextColor(darkColor)
            }
        }
        
        // Update count badges
        updateCountBadges()
    }

    private fun updateCountBadges() {
        coursesCountBadge.text = allCourses.size.toString()
        videosCountBadge.text = allVideos.size.toString()
        materiasCountBadge?.text = allSubjects.size.toString()
        
        // Also update the main statistics
        coursesCountTextView.text = allCourses.size.toString()
        videosCountTextView.text = allVideos.size.toString()
        
        Log.d("UserProfileView", "Updated count badges - Courses: ${allCourses.size}, Materias: ${allSubjects.size}, Videos: ${allVideos.size}")
    }    private fun filterContent() {
        // If in search mode, don't change the search results view
        if (isSearchMode) {
            return
        }

        // Handle SUBJECT filter separately since it uses Subject objects
        if (currentFilter == ContentType.SUBJECT) {
            Log.d("UserProfileView", "Filtering content - Type: SUBJECT, Count: ${allSubjects.size}")
            if (allSubjects.isEmpty()) {
                emptyStateTextView.visibility = View.VISIBLE
                emptyStateTextView.text = "Este usuario no tiene materias disponibles"
                contentRecyclerView.visibility = View.GONE
                return
            } else {
                emptyStateTextView.visibility = View.GONE
                contentRecyclerView.visibility = View.VISIBLE
            }
            // Convert subjects to VideoData for display in contentAdapter
            val subjectAsVideoData = allSubjects.map { subject ->
                VideoData(
                    id = subject.id,
                    username = username ?: "",
                    description = subject.description,
                    title = subject.name,
                    videoUriString = "",
                    localFilePath = null,
                    timestamp = 0L,
                    isPaid = false,
                    thumbnailUri = subject.thumbnailUrl,
                    price = null
                )
            }
            if (::contentAdapter.isInitialized) {
                if (contentRecyclerView.adapter != contentAdapter) {
                    contentRecyclerView.adapter = contentAdapter
                }
                contentAdapter.updateCourses(subjectAsVideoData)
            }
            return
        }

        // Use a local reference to avoid repeated access to properties
        val currentList = when (currentFilter) {
            ContentType.COURSE -> allCourses
            ContentType.VIDEO -> allVideos
            ContentType.SUBJECT -> allCourses // handled above
        }

        Log.d("UserProfileView", "Filtering content - Type: $currentFilter, Count: ${currentList.size}")

        if (currentList.isEmpty()) {
            emptyStateTextView.visibility = View.VISIBLE
            emptyStateTextView.text = when (currentFilter) {
                ContentType.COURSE -> "Este usuario no tiene cursos disponibles"
                ContentType.VIDEO -> "Este usuario no tiene videos disponibles"
                ContentType.SUBJECT -> "Este usuario no tiene materias disponibles"
            }
            contentRecyclerView.visibility = View.GONE
            return
        } else {
            emptyStateTextView.visibility = View.GONE
            contentRecyclerView.visibility = View.VISIBLE
        }

        when (currentFilter) {
            ContentType.COURSE -> {
                if (::contentAdapter.isInitialized) {
                    if (contentRecyclerView.adapter != contentAdapter) {
                        contentRecyclerView.adapter = contentAdapter
                    }
                    contentAdapter.updateCourses(currentList)
                }
            }
            ContentType.VIDEO -> {
                if (::videoAdapter.isInitialized) {
                    if (contentRecyclerView.adapter != videoAdapter) {
                        contentRecyclerView.adapter = videoAdapter
                    }
                    videoAdapter.updateVideos(currentList)
                }
            }
            ContentType.SUBJECT -> { /* handled above */ }
        }
    }private fun loadUserData(username: String) {
        lifecycleScope.launch {
            try {
                // Ensure BackendApiService is initialized
                BackendApiService.initialize(requireContext())
                
                // Fetch user data once (avoid duplicate API calls - was 3x before)
                val user = withContext(Dispatchers.IO) {
                    try {
                        val result = BackendApiService.getUserByUsername(username)
                        result.getOrNull()
                    } catch (e: Exception) {
                        Log.e("UserProfileView", "Error fetching user from backend", e)
                        null
                    }
                }
                
                // Fetch subscriber count using the user ID we already have
                val subscribersCount = if (user != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val countResult = BackendApiService.getSubscriberCount(user.id)
                            countResult.getOrNull()?.toLong() ?: 0L
                        } catch (e: Exception) {
                            Log.e("UserProfileView", "Error fetching subscriber count", e)
                            0L
                        }
                    }
                } else {
                    Log.w("UserProfileView", "Could not find userId for username: $username")
                    0L
                }
                
                val avatarUrl = user?.avatar
                
                Log.d("UserProfileView", "Loaded data for username: $username")
                Log.d("UserProfileView", "User found: ${user != null}, avatar: $avatarUrl")
                Log.d("UserProfileView", "Subscribers count from backend: $subscribersCount")

                // Guardar el rol del usuario cuyo perfil se está viendo
                if (user != null) {
                    viewedUserRolId = user.rol_id
                }

                // Actualizar UI con información del usuario
                withContext(Dispatchers.Main) {
                    // Capture the viewed user's role
                    if (user != null) {
                        viewedUserRolId = user.rol_id
                        configureFiltersByRole(viewedUserRolId)
                    }
                    
                    // Mostrar el nombre de usuario (username) como solicitado
                    if (user != null && user.usuario.isNotEmpty()) {
                        usernameTextView.text = user.usuario
                    } else {
                        usernameTextView.text = username
                    }
                    
                    subscribersCountTextView.text = subscribersCount.toString()
                    Log.d("UserProfileView", "UI updated - Displaying subscribers count: $subscribersCount")

                    // Cargar avatar del usuario desde el backend
                    if (!avatarUrl.isNullOrEmpty()) {
                        loadUserAvatarFromUrl(avatarUrl)
                    } else if (user != null) {
                        loadUserAvatar(user)
                    } else {
                        userAvatarImageView.setImageResource(R.drawable.ic_profile)
                    }

                    // Ajustar visibilidad de filtros según el rol del usuario visto:
                    // Rol 2 (docente) → solo mostrar materias, ocultar cursos
                    applyFilterVisibilityForRole(viewedUserRolId)
                }

                // Cargar contenido del usuario (this is already optimized with async internally)
                // Use the canonical username from the backend user object if available, otherwise fallback to the requested username
                val targetUsername = if (user != null && user.usuario.isNotEmpty()) user.usuario else username
                loadUserContent(targetUsername, user?.id)
                
            } catch (e: Exception) {
                e.printStackTrace()
                // Mostrar error o datos por defecto
                withContext(Dispatchers.Main) {
                    usernameTextView.text = username
                    userAvatarImageView.setImageResource(R.drawable.ic_profile)
                    subscribersCountTextView.text = "0"
                    showEmptyState()
                }
            }
        }
    }
    
    /**
     * Load user avatar from a URL string
     */
    private fun loadUserAvatarFromUrl(avatarUrl: String) {
        if (!isAdded || context == null) {
            Log.w("UserProfileView", "Fragment not added or context null, skipping avatar load")
            return
        }
        
        if (!::userAvatarImageView.isInitialized) {
            Log.w("UserProfileView", "userAvatarImageView not initialized, skipping avatar load")
            return
        }
        
        try {
            Log.d("UserProfileView", "Loading avatar from URL: $avatarUrl")
            Glide.with(this@UserProfileViewFragment)
                .load(avatarUrl)
                .placeholder(R.drawable.ic_profile)
                .error(R.drawable.ic_profile)
                .circleCrop()
                .into(userAvatarImageView)
        } catch (e: Exception) {
            Log.e("UserProfileView", "Error loading avatar from URL", e)
            userAvatarImageView.setImageResource(R.drawable.ic_profile)
        }
    }

    private fun loadUserAvatar(usuario: Usuario?) {
        // Safety checks like in VideoHomeFragment
        if (!isAdded || context == null) {
            Log.w("UserProfileView", "Fragment not added or context null, skipping avatar load")
            return
        }
        
        if (!::userAvatarImageView.isInitialized) {
            Log.w("UserProfileView", "userAvatarImageView not initialized, skipping avatar load")
            return
        }

        try {
            val avatarPath = usuario?.avatar
            if (!avatarPath.isNullOrEmpty()) {
                Log.d("UserProfileView", "Loading avatar: $avatarPath")
                
                // Verificar si es una ruta de archivo válida (local)
                if (avatarPath.startsWith("/") || avatarPath.startsWith("file://")) {
                    val file: File = if (avatarPath.startsWith("file://")) {
                        File(avatarPath.removePrefix("file://"))
                    } else {
                        File(avatarPath)
                    }
                    
                    if (file.exists() && file.canRead()) {
                        Glide.with(this@UserProfileViewFragment)
                            .load(file)
                            .placeholder(R.drawable.ic_profile)
                            .error(R.drawable.ic_profile)
                            .circleCrop()
                            .into(userAvatarImageView)
                    } else {
                        Log.w("UserProfileView", "Local avatar file not found: $avatarPath")
                        userAvatarImageView.setImageResource(R.drawable.ic_profile)
                    }
                } else {
                    // Si es una URI o URL (Supabase), cargar directamente
                    Glide.with(this@UserProfileViewFragment)
                        .load(avatarPath)
                        .placeholder(R.drawable.ic_profile)
                        .error(R.drawable.ic_profile)
                        .circleCrop()
                        .into(userAvatarImageView)
                }
            } else {
                Log.d("UserProfileView", "No avatar path, using default")
                userAvatarImageView.setImageResource(R.drawable.ic_profile)
            }
        } catch (e: Exception) {
            Log.e("UserProfileView", "Error loading avatar", e)
            userAvatarImageView.setImageResource(R.drawable.ic_profile)
        }
    }    private suspend fun loadUserContent(username: String, userId: Long? = null) {
        try {
            var userCoursesList: List<com.example.tareamov.data.entity.Course> = emptyList()
            var userVideosList: List<VideoData> = emptyList()
            var userSubjectsList: List<Subject> = emptyList()

            // Use BackendApiService to fetch courses and videos in parallel
            try {
                withContext(Dispatchers.IO) {
                    val coursesDeferred = async {
                        if (userId != null && userId > 0) {
                            // Prefer fetching by ID if available and valid as it's more reliable
                            Log.d("UserProfileView", "Fetching courses by userId: $userId")
                            val result = BackendApiService.getCoursesByCreatorId(userId)
                            result.getOrNull() ?: emptyList() 
                        } else {
                            // Fallback to username
                            Log.d("UserProfileView", "Fetching courses by username: $username")
                            val result = BackendApiService.getCoursesByCreator(username)
                            result.getOrNull() ?: emptyList()
                        }
                    }
                    
                    val videosDeferred = async {
                        Log.d("UserProfileView", "Fetching videos by username: $username")
                        val result = BackendApiService.getVideosByCreator(username)
                        val videos = result.getOrNull() ?: emptyList()
                        videos.map { it.copy(username = username) }
                            .sortedByDescending { it.timestamp }
                    }

                    val remoteCourses = coursesDeferred.await()
                    if (remoteCourses.isNotEmpty()) {
                        userCoursesList = remoteCourses
                    }

                    val remoteVideos = videosDeferred.await()
                    if (remoteVideos.isNotEmpty()) {
                        userVideosList = remoteVideos
                    }

                    // Para usuarios con rol 2 (docente) o rol 3 (admin): cargar materias.
                    if ((viewedUserRolId == 2L || viewedUserRolId == 3L || viewedUserRolId == 4L) && userId != null && userId > 0) {
                        Log.d("UserProfileView", "User is rol $viewedUserRolId, loading subjects for userId: $userId")
                        val allCoursesForSubjects = userCoursesList
                        val subjectsCreatedByUser = mutableListOf<Subject>()
                        for (course in allCoursesForSubjects) {
                            try {
                                val result = BackendApiService.getSubjectsByCourse(course.id)
                                val subjects = result.getOrNull() ?: emptyList()
                                // Filtrar solo las materias creadas por este usuario (created_by == userId)
                                val ownSubjects = subjects.filter { it.createdBy == userId }
                                subjectsCreatedByUser.addAll(ownSubjects)
                            } catch (e: Exception) {
                                Log.w("UserProfileView", "Error fetching subjects for course ${course.id}", e)
                            }
                        }
                        userSubjectsList = subjectsCreatedByUser
                        Log.d("UserProfileView", "Total subjects created by userId=$userId: ${userSubjectsList.size}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("UserProfileView", "Backend fetch by creator/username failed, will fallback to local", e)
            }

            // Backend is the source of truth — if empty, user simply has no content
            Log.d("UserProfileView", "Backend returned ${userCoursesList.size} courses and ${userVideosList.size} videos for $username")

            // Ensure both lists are sorted newest first by timestamp (remote should already be ordered)
            userCoursesList = userCoursesList.sortedWith(compareByDescending<com.example.tareamov.data.entity.Course> { it.timestamp }.thenByDescending { it.creationDate })
            userVideosList = userVideosList.sortedByDescending { it.timestamp }

            withContext(Dispatchers.Main) {
                // Actualizar las listas con los datos filtrados del usuario
                // Normalizar URIs de miniaturas y paths locales para que Glide/adapter puedan cargarlas
                fun normalizePath(path: String?): String? {
                    if (path.isNullOrEmpty()) return null
                    val lower = path.lowercase()
                    return when {
                        lower.startsWith("file://") -> path
                        lower.startsWith("http://") || lower.startsWith("https://") -> path
                        lower.startsWith("content://") -> path
                        lower.startsWith("android.resource://") -> path
                        // For paths starting with /, distinguish between local storage and backend URLs
                        path.startsWith("/storage") || path.startsWith("/data") -> "file://$path"
                        path.startsWith("/") -> "${BackendApiService.baseUrl}$path"
                        // Windows-style absolute paths (may contain backslashes or drive letter)
                        path.matches(Regex("^[a-zA-Z]:\\.*")) || path.contains('\\') -> "file://$path"
                        // Assume relative path missing slash is backend
                        !path.contains(":") -> "${BackendApiService.baseUrl}/$path"
                        else -> path
                    }
                }

                // Normalize and map remote Course entities to VideoData
                // Use map instead of loop for better performance
                val normalizedCourses = userCoursesList.map { course ->
                    // We can optimize this by passing the username directly if we know it matches
                    // or fetching it only if needed. For now, let's assume the username passed to the function is correct
                    // to avoid an extra network call per course.
                    val v = VideoData(
                        id = course.id,
                        username = username, // Use the username we already have
                        description = course.description ?: "",
                        title = course.title ?: "",
                        videoUriString = course.videoUri ?: "",
                        localFilePath = course.localFilePath,
                        timestamp = course.timestamp,
                        isPaid = course.isPremium,
                        thumbnailUri = course.thumbnailUri,
                        price = if (course.price > 0.0) course.price else null
                    )
                    val thumb = normalizePath(v.thumbnailUri)
                    val local = normalizePath(v.localFilePath)
                    v.copy(thumbnailUri = thumb, localFilePath = local)
                }

                val normalizedVideos = userVideosList.map { video ->
                    val thumb = normalizePath(video.thumbnailUri)
                    val local = normalizePath(video.localFilePath)
                    video.copy(thumbnailUri = thumb, localFilePath = local)
                }

                // Replace local lists with remote-normalized lists (prefer remote freshness)
                allCourses.clear()
                allCourses.addAll(normalizedCourses)
                allVideos.clear()
                allVideos.addAll(normalizedVideos)

                // Actualizar lista de materias si el usuario es docente (rol 2) o admin (rol 3)
                if (viewedUserRolId == 2L || viewedUserRolId == 3L || viewedUserRolId == 4L) {
                    allSubjects.clear()
                    allSubjects.addAll(userSubjectsList)
                    Log.d("UserProfileView", "Subjects loaded for rol $viewedUserRolId: ${allSubjects.size}")
                }

                // Actualizar contadores en la UI
                coursesCountTextView.text = userCoursesList.size.toString()
                videosCountTextView.text = userVideosList.size.toString()
                
                // Update count badges
                updateCountBadges()

                // Re-aplicar la lógica de visibilidad de filtros ahora que tenemos los datos
                applyFilterVisibilityForRole(viewedUserRolId)
                
                // Aplicar el filtro actual para mostrar el contenido correcto
                filterContent()

                // Forzar actualización del adaptador con los datos normalizados
                if (::contentAdapter.isInitialized && currentFilter == ContentType.COURSE) {
                    contentAdapter.updateCourses(allCourses)
                }
                
                Log.d("UserProfileView", "Loaded content for user: $username - Courses: ${userCoursesList.size}, Videos: ${userVideosList.size}, Subjects: ${userSubjectsList.size}")
            }
        } catch (e: Exception) {
            Log.e("UserProfileView", "Error loading user content for: $username", e)
            withContext(Dispatchers.Main) {
                showEmptyState()
            }
        }
    }    private fun showEmptyState() {
        allContent.clear()
        allCourses.clear()
        allVideos.clear()
        allSubjects.clear()
        coursesCountTextView.text = "0"
        videosCountTextView.text = "0"
        updateCountBadges()
        filterContent()
    }

    /**
     * Show custom dark themed Toast message (from ExploreFragment)
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
     * Handle subscription button click (from ExploreFragment)
     */
    private fun handleSubscriptionClick(course: com.example.tareamov.data.entity.Course, isCurrentlySubscribed: Boolean) {
        val currentUserUsername = getCurrentUsername()
        if (currentUserUsername == null) {
            showDarkToast("⚠️ Debes iniciar sesión para suscribirte")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get current user ID
                val userResult = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(currentUserUsername)
                }
                val currentUserId = userResult.getOrNull()?.id

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
                    // Unsubscribe via backend
                    withContext(Dispatchers.IO) {
                        BackendApiService.unsubscribe(creatorUserId)
                    }
                    
                    showDarkToast("✅ Te has desuscrito")
                    Log.d("UserProfileView", "User $currentUserId unsubscribed from $creatorUserId")
                } else {
                    // Subscribe via backend
                    withContext(Dispatchers.IO) {
                        BackendApiService.subscribe(creatorUserId)
                    }
                    
                    showDarkToast("🎉 Te has suscrito")
                    Log.d("UserProfileView", "User $currentUserId subscribed to $creatorUserId")
                }
                
                // Refresh the adapter to update subscription states
                contentAdapter.notifyDataSetChanged()
                
                // Also refresh user data to update subscriber count in header
                username?.let { loadUserData(it) }
                
            } catch (e: Exception) {
                Log.e("UserProfileView", "Error handling subscription", e)
                showDarkToast("❌ Error al procesar la suscripción")
            }
        }
    }
    
    /**
     * Handle enrollment click - Create initial progress record when student enrolls (from ExploreFragment)
     */
    private fun handleEnrollmentClick(course: com.example.tareamov.data.entity.Course) {
        val currentUserUsername = getCurrentUsername()
        if (currentUserUsername == null) {
            showDarkToast("¡Debes iniciar sesión para inscribirte!")
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get creator username from user_id
                val creatorUsername = withContext(Dispatchers.IO) {
                    val result = BackendApiService.getUserById(course.creatorUserId)
                    result.getOrNull()?.usuario
                }
                
                if (creatorUsername == null) {
                    showDarkToast("❌ Error: No se pudo obtener el nombre del creador")
                    return@launch
                }
                
                if (currentUserUsername == creatorUsername) {
                    showDarkToast("No puedes inscribirte en tu propio curso")
                    return@launch
                }
        
                // Block enrollment for paid courses (price > 0)
                if (course.isPremium && course.price > 0) {
                    showDarkToast("❌ Este es un curso de pago. Debes realizar el pago para acceder.", Toast.LENGTH_LONG)
                    return@launch
                }
        
                // Get user ID from username
                val userId = withContext(Dispatchers.IO) {
                    val result = BackendApiService.getUserByUsername(currentUserUsername)
                    result.getOrNull()?.id
                }
                
                if (userId == null) {
                    showDarkToast("Error: Usuario no encontrado")
                    Log.e("UserProfileView", "Failed to get user ID for username: $currentUserUsername")
                    return@launch
                }
                
                // Check if already enrolled
                val isAlreadyEnrolled = withContext(Dispatchers.IO) {
                    val result = BackendApiService.isEnrolled(course.id)
                    result.getOrNull() == true
                }
                
                if (isAlreadyEnrolled) {
                    showDarkToast("Ya estás inscrito en este curso")
                    return@launch
                }
                
                // Get total tasks for this course
                val topics = withContext(Dispatchers.IO) {
                    val result = BackendApiService.getTopicsByCourse(course.id)
                    result.getOrNull() ?: emptyList()
                }
                
                var totalTasks = 0
                for (topic in topics) {
                    val tasksResult = withContext(Dispatchers.IO) {
                        BackendApiService.getTasksByTopic(topic.id)
                    }
                    totalTasks += tasksResult.getOrNull()?.size ?: 0
                }
                
                // Create progress via backend
                val progressData = mapOf<String, Any?>(
                    "usuarioEstudiante" to userId,
                    "cursoId" to course.id,
                    "tareasCompletadas" to 0,
                    "tareasTotales" to totalTasks,
                    "porcentajeProgreso" to 0f,
                    "calificacionPonderada" to 0f,
                    "promedio" to 0f,
                    "estado" to "Pendiente"
                )
                
                val upsertResult = withContext(Dispatchers.IO) {
                    BackendApiService.upsertProgress(progressData)
                }
                
                if (upsertResult.isSuccess) {
                    showDarkToast("✅ Inscripción exitosa. ¡Comienza tu aprendizaje!")
                    Log.d("UserProfileView", "✅ Progress created for user $userId in course ${course.id}")
                    
                    // Refresh content to show enrollment status
                    username?.let { loadUserData(it) }
                } else {
                    showDarkToast("❌ Error al inscribirse: ${upsertResult.errorMessage()}")
                    Log.w("UserProfileView", "⚠️ Progress creation failed: ${upsertResult.errorMessage()}")
                }
                
            } catch (e: Exception) {
                Log.e("UserProfileView", "Error enrolling in course", e)
                showDarkToast("❌ Error al inscribirse en el curso")
            }
        }
    }

    /**
     * Sync subscription to backend
     */
    private suspend fun syncSubscription(subscriberId: Long, creatorId: Long) {
        try {
            BackendApiService.subscribe(creatorId)
            Log.d("UserProfileView", "Subscription synced to backend: $subscriberId -> $creatorId")
        } catch (e: Exception) {
            Log.e("UserProfileView", "Error syncing subscription to backend", e)
        }
    }

    /**
     * Sync unsubscription to backend
     */
    private suspend fun syncUnsubscription(subscriberId: Long, creatorId: Long) {
        try {
            BackendApiService.unsubscribe(creatorId)
            Log.d("UserProfileView", "Unsubscription synced to backend: $subscriberId -> $creatorId")
        } catch (e: Exception) {
            Log.e("UserProfileView", "Error syncing unsubscription to backend", e)
        }
    }

    private fun handleCourseClickWithEnrollment(course: VideoData) {
        if (currentFilter == ContentType.SUBJECT) {
            handleContentClick(course)
            return
        }

        val currentUserUsername = getCurrentUsername()
        
        // If not logged in, just navigate (or show login prompt)
        if (currentUserUsername == null) {
            showDarkToast("¡Debes iniciar sesión para acceder al curso!")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Fetch current user ONCE from backend (avoid redundant calls)
                val currentUser = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(currentUserUsername)
                }.getOrNull()
                val currentUserId = currentUser?.id

                // Check if user is creator (by username match or by fetching course from backend)
                val isCreator = if (currentUserUsername == course.username) {
                    true
                } else if (currentUserId != null) {
                    val actualCourse = withContext(Dispatchers.IO) {
                        BackendApiService.getCourseById(course.id)
                    }.getOrNull()
                    actualCourse != null && currentUserId == actualCourse.creatorUserId
                } else {
                    false
                }

                if (isCreator) {
                    handleContentClick(course)
                    return@launch
                }

                // Check if paid
                if (course.isPaid && (course.price ?: 0.0) > 0) {
                    if (currentUserId != null) {
                        val isEnrolled = withContext(Dispatchers.IO) {
                            val result = BackendApiService.isEnrolled(course.id)
                            result.getOrNull() == true
                        }
                        
                        if (isEnrolled) {
                            handleContentClick(course)
                        } else {
                            // Integrate Payment Options
                            Log.d("UserProfileView", "User not enrolled in paid course, triggering payment flow")
                            showPaymentOptions(
                                courseId = course.id,
                                courseName = course.title,
                                coursePrice = course.price ?: 0.0,
                                username = currentUserUsername,
                                userId = currentUserId,
                                onPaymentResult = { success ->
                                    if (success) {
                                        showDarkToast("✅ Pago exitoso. ¡Bienvenido al curso!", Toast.LENGTH_LONG)
                                        handleContentClick(course)
                                    } else {
                                        showDarkToast("El proceso de pago fue cancelado o no completado.", Toast.LENGTH_SHORT)
                                    }
                                }
                            )
                        }
                    }
                    return@launch
                }

                // Free course: Auto-enroll if needed (use currentUserId already fetched)
                if (currentUserId != null) {
                    val isEnrolled = withContext(Dispatchers.IO) {
                        val result = BackendApiService.isEnrolled(course.id)
                        result.getOrNull() == true
                    }

                    if (!isEnrolled) {
                        Log.d("UserProfileView", "Auto-enrolling user $currentUserUsername in course ${course.title}")
                        
                        // Get total tasks from backend
                        val topics = withContext(Dispatchers.IO) {
                            val result = BackendApiService.getTopicsByCourse(course.id)
                            result.getOrNull() ?: emptyList()
                        }
                        var totalTasks = 0
                        for (topic in topics) {
                            val tasksResult = withContext(Dispatchers.IO) {
                                BackendApiService.getTasksByTopic(topic.id)
                            }
                            totalTasks += tasksResult.getOrNull()?.size ?: 0
                        }

                        // Create progress via backend
                        val progressData = mapOf<String, Any?>(
                            "usuarioEstudiante" to currentUserId,
                            "cursoId" to course.id,
                            "tareasCompletadas" to 0,
                            "tareasTotales" to totalTasks,
                            "porcentajeProgreso" to 0f,
                            "calificacionPonderada" to 0f,
                            "promedio" to 0f,
                            "estado" to "Pendiente"
                        )

                        withContext(Dispatchers.IO) {
                            BackendApiService.upsertProgress(progressData)
                        }
                        
                        showDarkToast("✅ ¡Inscrito automáticamente en ${course.title}!")
                    }
                }
                
                // Navigate
                handleContentClick(course)

            } catch (e: Exception) {
                Log.e("UserProfileView", "Error in auto-enrollment", e)
                handleContentClick(course) // Fallback to navigation
            }
        }
    }

    private fun handleContentClick(content: VideoData) {
        // 🛡️ Safety check: Ensure we're still on UserProfileViewFragment before navigating
        try {
            val currentDestination = findNavController().currentDestination
            if (currentDestination?.id != R.id.userProfileViewFragment) {
                Log.w("UserProfileView", "⚠️ Navigation ignored - not on UserProfileViewFragment (currently on: ${currentDestination?.label})")
                return
            }
        } catch (e: Exception) {
            Log.e("UserProfileView", "❌ Navigation check failed", e)
            return
        }
        
        // Verificar si el click es en un curso o video basándose en el filtro actual
        when (currentFilter) {
            ContentType.COURSE -> {
                val bundle = Bundle().apply {
                    putLong("courseId", content.id)
                    putString("courseName", content.title)
                }
                try {
                    findNavController().navigate(R.id.action_userProfileViewFragment_to_subjectsListFragment, bundle)
                } catch (e: Exception) {
                    Log.e("UserProfileView", "Navigation to subjectsListFragment failed: ${e.message}")
                }
            }
            ContentType.VIDEO -> {
                // Navegar al VideoHomeFragment con el video específico
                val bundle = Bundle().apply {
                    putLong("videoId", content.id)
                    putString("videoTitle", content.title)
                    putString("videoUsername", content.username)
                }
                try {
                    findNavController().navigate(R.id.action_userProfileViewFragment_to_videoHomeFragment, bundle)
                } catch (e: Exception) {
                    Log.e("UserProfileView", "Navigation to videoHomeFragment failed: ${e.message}")
                }
            }
            ContentType.SUBJECT -> {
                val subject = allSubjects.firstOrNull { it.id == content.id }
                if (subject == null) {
                    Log.w("UserProfileView", "Subject not found for clicked content id=${content.id}")
                    showDarkToast("No se pudo abrir la materia seleccionada")
                    return
                }

                val bundle = Bundle().apply {
                    putLong("courseId", subject.courseId)
                    putString("courseName", subject.name)
                    putLong("subjectId", subject.id)
                    putString("subjectName", subject.name)
                    putString("subjectDescription", subject.description)
                    putString("subjectThumbnailUrl", subject.thumbnailUrl)
                    // Indicates that this subject is being accessed from a docente's public profile.
                    // Guests, collaborators, and admins belonging to the course can access without enrollment verification.
                    putBoolean("fromDocenteProfile", true)
                }
                try {
                    findNavController().navigate(R.id.action_userProfileViewFragment_to_courseDetailFragment, bundle)
                } catch (e: Exception) {
                    Log.e("UserProfileView", "Navigation to courseDetailFragment failed: ${e.message}")
                }
            }
        }
    }

    private fun navigateToCourseDetail(course: VideoData) {
        lifecycleScope.launch {
            // Check if current user is the course creator via backend
            val currentUserUsername = getCurrentUsername()
            val isCreator = if (currentUserUsername != null && currentUserUsername == course.username) {
                true
            } else if (currentUserUsername != null) {
                // Fetch the course from backend to get creatorUserId, then compare
                val actualCourse = withContext(Dispatchers.IO) {
                    BackendApiService.getCourseById(course.id)
                }.getOrNull()
                val currentUser = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(currentUserUsername)
                }.getOrNull()
                actualCourse != null && currentUser != null && currentUser.id == actualCourse.creatorUserId
            } else {
                false
            }

            // 🛡️ Safety check: Ensure we're still on UserProfileViewFragment before navigating
            try {
                val currentDestination = findNavController().currentDestination
                if (currentDestination?.id != R.id.userProfileViewFragment) {
                    Log.w("UserProfileView", "⚠️ navigateToCourseDetail cancelled - not on UserProfileViewFragment (currently on: ${currentDestination?.label})")
                    return@launch
                }
            } catch (e: Exception) {
                Log.e("UserProfileView", "❌ navigateToCourseDetail check failed", e)
                return@launch
            }

            val bundle = Bundle().apply {
                putLong("courseId", course.id)
                putString("courseName", course.title)
                putBoolean("isCreator", isCreator)
            }
            try {
                findNavController().navigate(R.id.action_userProfileViewFragment_to_subjectsListFragment, bundle)
            } catch (e: Exception) {
                Log.e("UserProfileView", "navigateToCourseDetail navigation failed: ${e.message}")
            }
        }
    }

    private fun handleVideoClick(video: VideoData) {
        try {
            Log.d("UserProfileView", "📹 Video clicked: ${video.title} (ID: ${video.id})")
            Log.d("UserProfileView", "  - Navigating to VideoHomeFragment with videoId: ${video.id}")
            Log.d("UserProfileView", "  - Username: ${video.username}")
            
            // Navegar al VideoHomeFragment con el video específico
            val bundle = Bundle().apply {
                putLong("videoId", video.id)
                putString("videoTitle", video.title)
                putString("videoUsername", video.username)
            }
            
            // Verificar que estamos en el destino correcto antes de navegar
            val navController = findNavController()
            if (navController.currentDestination?.id == R.id.userProfileViewFragment) {
                navController.navigate(R.id.action_userProfileViewFragment_to_videoHomeFragment, bundle)
                Log.d("UserProfileView", "✅ Navigation to VideoHomeFragment initiated")
            } else {
                Log.w("UserProfileView", "⚠️ Not in userProfileViewFragment, current destination: ${navController.currentDestination?.id}")
                Toast.makeText(requireContext(), "No se pudo navegar al video", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("UserProfileView", "❌ Error navigating to video: ${e.message}", e)
            Toast.makeText(requireContext(), "Error al abrir el video", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Avoid duplicate load on first creation (onViewCreated already calls loadUserData)
        if (isInitialLoad) {
            isInitialLoad = false
            return
        }
        // Reload data when returning from another fragment
        username?.let { loadUserData(it) }
        
        // Ensure bottom navigation remains visible and static
        if (::bottomNavBinding.isInitialized) {
            bottomNavBinding.root.visibility = View.VISIBLE
            bottomNavBinding.root.translationY = 0f
            bottomNavBinding.root.alpha = 1f
        }
    }    override fun onPause() {
        super.onPause()
        // Stop any video playback when fragment is paused
        if (::contentAdapter.isInitialized) {
            contentAdapter.stopAllVideos()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up video preview
        previewHandler.removeCallbacks(previewRunnable ?: Runnable {})
        stopCurrentPreview()
        
        // Clean up any resources
        if (::contentAdapter.isInitialized) {
            contentAdapter.stopAllVideos()
        }
    }
    
    // Método auxiliar que replica la lógica de ExploreFragment para obtener contenido
    private suspend fun getAllContentLikeExploreFragment(): List<VideoData> {
        return withContext(Dispatchers.IO) {
            val result = BackendApiService.getVideos(limit = 500)
            if (result is ApiResult.Success) result.data ?: emptyList() else emptyList()
        }
    }
    
    // Método auxiliar para filtrar contenido como lo hace ExploreFragment
    private fun filterContentLikeExploreFragment(
        allContent: List<VideoData>, 
        targetUsername: String,
        targetUserId: Long? = null
    ): Pair<List<VideoData>, List<VideoData>> {
        // Filtrar por el usuario específico (username OR remoteId)
        val userContent = allContent.filter { video ->
            video.username.equals(targetUsername, ignoreCase = true) || 
            (targetUserId != null && video.remoteId != null && video.remoteId == targetUserId)
        }
        
        Log.d("UserProfileView", "User content filtered for $targetUsername (id=$targetUserId): ${userContent.size} items")
        
        // Log de miniaturas disponibles para debugging
        userContent.forEach { video ->
            val hasThumbnail = !video.thumbnailUri.isNullOrEmpty()
            val hasLocalFile = !video.localFilePath.isNullOrEmpty()
            val hasVideoUri = !video.videoUriString.isNullOrEmpty()
            
            Log.d("UserProfileView", "Video ${video.id} - '${video.title}': " +
                    "Has Thumbnail: $hasThumbnail, " +
                    "Has LocalFile: $hasLocalFile, " +
                    "Has VideoUri: $hasVideoUri")
        }
        
        // Separar cursos y videos usando lógica similar a ExploreFragment
        // En ExploreFragment, todos los elementos se consideran cursos para el RecyclerView principal
        val courses = userContent
        
        // Los videos son aquellos que tienen videoUriString (son videos reales, no solo cursos)
        // Incluye videos con remote_id que coincide con el usuario
        val videos = userContent.filter { video ->
            // Un video real tiene video_uri_string poblado O localFilePath poblado
            // Excluir items que claramente son cursos sin contenido de video
            !video.videoUriString.isNullOrEmpty() || !video.localFilePath.isNullOrEmpty()
        }
        
        return Pair(courses, videos)
    }

    // Método público para recargar el contenido del usuario (puede ser llamado externamente)
    fun refreshUserContent() {
        username?.let { loadUserData(it) }
    }
    
    // ensureThumbnailsLoaded removed - DiffUtil in adapters handles efficient updates
    // Calling notifyDataSetChanged() on scroll was causing performance issues
    
    // Método auxiliar para verificar la validez de las URIs (como en CreatedCourseAdapter)
    private fun isValidUri(uriString: String?): Boolean {
        if (uriString.isNullOrEmpty()) return false
        
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme?.lowercase()) {
                "file" -> {
                    // Check if file exists and is readable
                    val file = File(uri.path ?: "")
                    file.exists() && file.canRead()
                }
                "content" -> {
                    // Only allow specific content providers, avoid Google Drive URIs
                    val authority = uri.authority
                    authority != null && 
                    !authority.contains("com.google.android.apps.docs") &&
                    !authority.contains("com.google.android.apps.drive")
                }
                "android.resource" -> true
                "http", "https" -> true
                else -> false
            }
        } catch (e: Exception) {
            Log.e("UserProfileView", "Invalid URI: $uriString", e)
            false
        }
    }
    
    // === FUNCIONES CRUD PARA CURSOS (Identical to ExploreFragment) ===
    
    /**
     * Editar curso - Identical to ExploreFragment implementation
     */
    private fun editCourse(course: VideoData) {
        // Only the creator can edit the course
        val current = getCurrentUsername()
        if (current == null || current != course.username) {
            Toast.makeText(requireContext(), "❌ Solo el creador puede editar el curso", Toast.LENGTH_SHORT).show()
            Log.w("UserProfileView", "Edit denied: User is not the course creator")
            return
        }

        Log.d("UserProfileView", "Navigate to CourseCreationFragment for edit: ${course.title} (id=${course.id})")

        // Navigate to CourseCreationFragment and pass courseId and isEditing=true
        val bundle = android.os.Bundle().apply {
            putLong("courseId", course.id)
            putBoolean("isEditing", true)
        }

        try {
            findNavController().navigate(com.example.tareamov.R.id.courseCreationFragment, bundle)
        } catch (e: Exception) {
            Log.e("UserProfileView", "Navigation to CourseCreationFragment failed", e)
            Toast.makeText(requireContext(), "No se pudo abrir el editor de curso", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Cambiar miniatura del curso - Solo seleccionar imagen de galería
     */
    private fun changeThumbnail(course: VideoData) {
        // Check if current user is the creator before allowing thumbnail change
        if (getCurrentUsername() != null && getCurrentUsername() == course.username) {
            currentCourseForThumbnailChange = course

            // Create dialog with dark theme
            val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(
                androidx.appcompat.view.ContextThemeWrapper(requireContext(), R.style.DarkAlertDialogTheme)
            )

            dialogBuilder
                .setTitle("🖼️ Cambiar Miniatura")
                .setMessage("Selecciona una nueva miniatura para el curso \"${course.title}\" desde tu galería de imágenes.")
                .setPositiveButton("📱 Seleccionar Imagen") { _, _ ->
                    openImagePicker()
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
    
    /**
     * Update local video data immediately after return from edit screen
     */
    private fun updateLocalVideoData(
        videoId: Long, 
        title: String?, 
        description: String?, 
        isPaid: Boolean, 
        thumbnailUri: String?
    ) {
        // Helper to update a list
        fun updateList(list: MutableList<VideoData>) {
            val index = list.indexOfFirst { it.id == videoId }
            if (index != -1) {
                val current = list[index]
                val updated = current.copy(
                    title = title ?: current.title,
                    description = description ?: current.description,
                    isPaid = isPaid,
                    thumbnailUri = thumbnailUri ?: current.thumbnailUri
                )
                list[index] = updated
            }
        }

        // Update all lists
        updateList(allContent)
        updateList(allCourses)
        updateList(allVideos)
        
        // Update adapters
        if (::contentAdapter.isInitialized) {
            contentAdapter.updateCourses(allCourses)
        }
        
        if (::videoAdapter.isInitialized) {
            videoAdapter.updateVideos(allVideos)
        }
        
        // Refresh filter view
        filterContent()
    }

    private fun updateLocalVideoWithData(video: VideoData) {
        Log.d("UserProfileView", "🔄 updateLocalVideoWithData called for video ID: ${video.id}, title: ${video.title}")
        
        // Helper to update a list
        fun updateList(list: MutableList<VideoData>, listName: String) {
            val index = list.indexOfFirst { it.id == video.id }
            if (index != -1) {
                Log.d("UserProfileView", "✅ Found video in $listName at index $index. Updating...")
                list[index] = video
            } else {
                Log.d("UserProfileView", "⚠️ Video ID ${video.id} not found in $listName")
            }
        }

        // Update all lists
        updateList(allContent, "allContent")
        updateList(allCourses, "allCourses")
        updateList(allVideos, "allVideos")
        
        // Update adapters
        if (::contentAdapter.isInitialized) {
            contentAdapter.updateCourses(allCourses)
            contentAdapter.notifyDataSetChanged() // Force update
        }
        
        if (::videoAdapter.isInitialized) {
            videoAdapter.updateVideos(allVideos)
            videoAdapter.notifyDataSetChanged() // Force update
        }
        
        // Refresh filter view
        filterContent()
    }

    /**
     * Eliminar curso con confirmación - Identical to ExploreFragment deleteCourseFromTable
     */
    private fun deleteCourse(course: VideoData) {
        // Check if current user is the creator before allowing deletion
        if (getCurrentUsername() != null && getCurrentUsername() == course.username) {
            // Show confirmation dialog
            showDeleteConfirmationDialog(course)
        } else {
            Toast.makeText(requireContext(), "Solo el creador puede eliminar el curso", Toast.LENGTH_SHORT).show()
            Log.w("UserProfileView", "Deletion denied: User '${getCurrentUsername()}' is not the course creator '${course.username}'")
        }
    }

    /**
     * Show confirmation dialog for deletion
     */
    private fun showDeleteConfirmationDialog(course: VideoData) {
        // Use ConfirmDeleteDialogFragment and FragmentResult API to centralize confirmation UI
        val requestKey = "confirm_delete_${course.id}"
        parentFragmentManager.setFragmentResultListener(requestKey, viewLifecycleOwner) { _, bundle ->
            val confirmed = bundle.getBoolean("confirmed", false)
            if (confirmed) {
                performDeleteContent(course, isCourse = true)
            }
        }

        val title = "Eliminar curso"
        val message = "¿Estás seguro de que deseas eliminar '${course.title}'? Esta acción no se puede deshacer."
        val dialog = ConfirmDeleteDialogFragment.newInstance(requestKey, title, message, confirmText = "Eliminar", cancelText = "Cancelar")
        dialog.show(parentFragmentManager, requestKey)
    }

    /**
     * Actually perform the course/video deletion
     */
    private fun performDeleteContent(content: VideoData, isCourse: Boolean) {
        // --- OPTIMISTIC UI UPDATE for "Fast" feel ---
        val idToRemove = content.id
        
        // Remove from local lists immediately
        allContent.removeAll { it.id == idToRemove }
        allCourses.removeAll { it.id == idToRemove }
        allVideos.removeAll { it.id == idToRemove }
        
        // Update adapters immediately
        if (::contentAdapter.isInitialized) {
            contentAdapter.removeCourse(idToRemove)
        }
        
        if (::videoAdapter.isInitialized) {
            videoAdapter.updateVideos(allVideos)
        }
        
        // Update counts
        coursesCountTextView.text = allCourses.size.toString()
        videosCountTextView.text = allVideos.size.toString()
        updateCountBadges()

        // Refresh views
        filterContent()
        
        // Notify user immediately that we are working on it
        Toast.makeText(requireContext(), "Eliminando...", Toast.LENGTH_SHORT).show()
        Log.d("UserProfileView", "Optimistic delete applied locally for: ${content.title} (isCourse=$isCourse)")

        try {
            val vm = ViewModelProvider(requireActivity())[com.example.tareamov.viewmodel.VideoHomeViewModel::class.java]
            if (!isCourse) vm.removeVideoOptimistic(idToRemove) else vm.markFeedDirty()
        } catch (_: Exception) {}

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    if (isCourse) {
                        val deleteResult = BackendApiService.deleteCourse(content.id)
                        if (deleteResult is ApiResult.Success) {
                            Log.i("UserProfileView", "Course ${content.id} deleted successfully via backend")
                            com.example.tareamov.util.AppCache.invalidateCourses()
                        } else {
                            Log.w("UserProfileView", "Failed to delete course ${content.id}: ${(deleteResult as? ApiResult.Error)?.message}")
                        }
                    } else {
                        val deleteResult = BackendApiService.deleteVideo(content.id)
                        if (deleteResult is ApiResult.Success) {
                            Log.i("UserProfileView", "Video ${content.id} deleted successfully via backend")
                            com.example.tareamov.util.AppCache.invalidateVideos()
                            com.example.tareamov.util.AppCache.invalidateCourses()
                            try {
                                val vm = ViewModelProvider(requireActivity())[com.example.tareamov.viewmodel.VideoHomeViewModel::class.java]
                                vm.confirmDeletion(content.id)
                            } catch (_: Exception) {}
                        } else {
                            Log.w("UserProfileView", "Failed to delete video ${content.id}: ${(deleteResult as? ApiResult.Error)?.message}")
                        }
                    }
                } 
            } catch (e: Exception) {
                Log.e("UserProfileView", "Error in deleting process: ${e.message}")
            }
        }
    }

    /**
     * Method to update course details - Identical to ExploreFragment
     */
    private fun updateCourseDetails(course: VideoData, newTitle: String, newDescription: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updatedCourse = course.copy(title = newTitle, description = newDescription)

                withContext(Dispatchers.IO) {
                    // Update course via backend API
                    val updates = mapOf<String, Any?>(
                        "title" to newTitle,
                        "description" to newDescription
                    )
                    val result = BackendApiService.updateCourse(course.id, updates)
                    if (result.isSuccess) {
                        Log.d("UserProfileView", "Course ${course.id} updated on backend")
                        com.example.tareamov.util.AppCache.invalidateCourses()
                    } else {
                        Log.w("UserProfileView", "Backend update failed: ${result.errorMessage()}, falling back to local")
                        videoManager.updateVideo(updatedCourse)
                    }
                }
                
                // Actualizar las listas locales inmediatamente
                withContext(Dispatchers.Main) {
                    val contentIndex = allContent.indexOfFirst { it.id == course.id }
                    if (contentIndex != -1) {
                        allContent[contentIndex] = updatedCourse
                    }
                    val coursesIndex = allCourses.indexOfFirst { it.id == course.id }
                    if (coursesIndex != -1) {
                        allCourses[coursesIndex] = updatedCourse
                    }
                    val videosIndex = allVideos.indexOfFirst { it.id == course.id }
                    if (videosIndex != -1) {
                        allVideos[videosIndex] = updatedCourse
                    }
                    
                    // Actualizar ambos adaptadores
                    contentAdapter.notifyDataSetChanged()
                    videoAdapter.updateVideos(allVideos)
                    
                    // Actualizar contadores
                    updateCountBadges()
                    
                    // Refrescar la vista actual
                    filterContent()
                    
                    Toast.makeText(requireContext(), "✅ Curso actualizado", Toast.LENGTH_SHORT).show()
                }

                Log.d("UserProfileView", "Course updated: $newTitle")
                // Reload user content to show updated data
                username?.let { loadUserData(it) }
            } catch (e: Exception) {
                Log.e("UserProfileView", "Error updating course details", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "❌ Error al actualizar el curso", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Method to update course in Course table - Only for course creators
     */
    private fun updateCourseInTable(videoData: VideoData) {
        // Check if current user is the creator before allowing update
        if (getCurrentUsername() != null && getCurrentUsername() == videoData.username) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Update via backend API
                    val updates = mapOf<String, Any?>(
                        "title" to videoData.title,
                        "description" to videoData.description,
                        "thumbnailUri" to videoData.thumbnailUri,
                        "price" to (videoData.price ?: 0.0),
                        "isFree" to !videoData.isPaid
                    )
                    withContext(Dispatchers.IO) {
                        val result = BackendApiService.updateCourse(videoData.id, updates)
                        if (result.isSuccess) {
                            Log.d("UserProfileView", "Course updated via backend: ${videoData.title}")
                            com.example.tareamov.util.AppCache.invalidateCourses()
                        } else {
                            Log.w("UserProfileView", "Backend update failed: ${result.errorMessage()}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("UserProfileView", "Error updating course via backend", e)
                }
            }
        } else {
            Log.w("UserProfileView", "Update denied: User is not the course creator")
        }
    }

    /**
     * Initialize image picker launcher for thumbnail change
     */
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

    /**
     * Handle thumbnail image selection
     */
    private fun handleThumbnailSelection(imageUri: Uri) {
        currentCourseForThumbnailChange?.let { course ->
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Subir miniatura al backend si está configurado
                    var finalThumbnailUri = imageUri.toString()
                    if (StorageHelper.isConfigured()) {
                        Toast.makeText(requireContext(), "Subiendo miniatura a la nube...", Toast.LENGTH_SHORT).show()
                        val result = withContext(Dispatchers.IO) {
                            StorageHelper.uploadFile(
                                context = requireContext(),
                                fileUri = imageUri,
                                folder = "thumbnails/courses",
                                customFileName = "course_${course.id}_${System.currentTimeMillis()}"
                            )
                        }
                        when (result) {
                            is StorageHelper.UploadResult.Success -> {
                                finalThumbnailUri = result.url
                                Log.d("UserProfileView", "☁️ Thumbnail uploaded: $finalThumbnailUri")
                            }
                            is StorageHelper.UploadResult.Error -> {
                                Log.e("UserProfileView", "❌ Failed to upload thumbnail: ${result.message}")
                                Toast.makeText(requireContext(), "Error subiendo a nube, usando local", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    // Update the course with new thumbnail
                    val updatedCourse = course.copy(thumbnailUri = finalThumbnailUri)

                    // Update thumbnail via backend API
                    withContext(Dispatchers.IO) {
                        val updates = mapOf<String, Any?>("thumbnailUri" to finalThumbnailUri)
                        val result = BackendApiService.updateCourse(course.id, updates)
                        if (result.isSuccess) {
                            Log.d("UserProfileView", "Thumbnail updated on backend for course ${course.id}")
                            com.example.tareamov.util.AppCache.invalidateCourses()
                        } else {
                            Log.w("UserProfileView", "Backend thumbnail update failed: ${result.errorMessage()}")
                        }
                    }

                    Log.d("UserProfileView", "Thumbnail updated for course: ${course.title}")
                    Toast.makeText(requireContext(), "Miniatura actualizada", Toast.LENGTH_SHORT).show()

                    // Reload user content to show updated thumbnail
                    username?.let { loadUserData(it) }

                } catch (e: Exception) {
                    Log.e("UserProfileView", "Error updating thumbnail", e)
                    Toast.makeText(requireContext(), "Error al actualizar miniatura", Toast.LENGTH_SHORT).show()
                } finally {
                    currentCourseForThumbnailChange = null
                }
            }
        }
    }

    /**
     * Open image picker for thumbnail selection
     */
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        imagePickerLauncher.launch(intent)
    }

    /**
     * Regenerate thumbnail from video
     */
    // Regenerate thumbnail from video - REMOVED (Obsolete)
    // private fun regenerateThumbnailFromVideo(course: VideoData) { ... }
    
    // === FIN FUNCIONES CRUD ===
    
    // Obtener nombre de usuario actual usando SessionManager (consistente con ExploreFragment)
    private fun getCurrentUsername(): String? {
        val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val username = sessionManager.getUsername()
        Log.d("UserProfileViewFragment", "Current username from SessionManager: $username")
        return username
    }



    /**
     * Setup listeners for video updates from VideoDetailsFragment
     */
    private fun setupVideoUpdateListeners() {
        try {
            val navController = findNavController()
            val currentBackStackEntry = navController.currentBackStackEntry
            
            // Listen for video updates via NavBackStackEntry savedStateHandle (more reliable)
            currentBackStackEntry?.savedStateHandle?.getLiveData<Boolean>("videoUpdated")?.observe(viewLifecycleOwner) { updated ->
                if (updated == true) {
                    Log.d("UserProfileView", "🔔 Video update detected via SavedStateHandle!")
                    
                    val savedState = currentBackStackEntry.savedStateHandle
                    val updatedVideoId = savedState.get<Long>("updatedVideoId") ?: 0L
                    val updatedTitle = savedState.get<String>("updatedTitle") ?: ""
                    val updatedDescription = savedState.get<String>("updatedDescription") ?: ""
                    val updatedIsPaid = savedState.get<Boolean>("updatedIsPaid") ?: false
                    val updatedThumbnailUri = savedState.get<String>("updatedThumbnailUri")
                    
                    Log.d("UserProfileView", "📝 Updating video - ID: $updatedVideoId, title: $updatedTitle")
                    
                    if (updatedVideoId > 0) {
                        Log.d("UserProfileView", "🚀 Starting backend fetch for video ID: $updatedVideoId")
                        // Fetch updated data from backend
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val videoResult = withContext(Dispatchers.IO) { BackendApiService.getVideoById(updatedVideoId) }
                                val updatedVideo = if (videoResult is ApiResult.Success) videoResult.data else null
                                if (updatedVideo != null) {
                                    Log.d("UserProfileView", "✅ Fetched updated video from backend: ${updatedVideo.title}")
                                    updateLocalVideoWithData(updatedVideo)
                                } else {
                                    Log.w("UserProfileView", "⚠️ Failed to fetch updated video from backend, using local data")
                                    updateLocalVideoData(updatedVideoId, updatedTitle, updatedDescription, updatedIsPaid, updatedThumbnailUri)
                                }
                            } catch (e: Exception) {
                                Log.e("UserProfileView", "Error fetching updated video", e)
                                updateLocalVideoData(updatedVideoId, updatedTitle, updatedDescription, updatedIsPaid, updatedThumbnailUri)
                            }
                            
                            // Reload full user data as backup to ensure consistency
                            username?.let { 
                                Log.d("UserProfileView", "🔄 Reloading full user data for: $it")
                                loadUserData(it) 
                            }
                        }
                    }
                    
                    // Clear the state to avoid duplicate handling
                    savedState.remove<Boolean>("videoUpdated")
                    savedState.remove<Long>("updatedVideoId")
                    savedState.remove<String>("updatedTitle")
                    savedState.remove<String>("updatedDescription")
                    savedState.remove<Boolean>("updatedIsPaid")
                    savedState.remove<String>("updatedThumbnailUri")
                }
            }
            
            // Listen for COURSE updates or creations
            currentBackStackEntry?.savedStateHandle?.getLiveData<Boolean>("courseUpdated")?.observe(viewLifecycleOwner) { updated ->
                if (updated == true) {
                    Log.d("UserProfileView", "🔔 Course update detected via SavedStateHandle!")
                    // Reload user data to reflect changes
                    username?.let { 
                        Log.d("UserProfileView", "🔄 Reloading user data after course update")
                        loadUserData(it) 
                    }
                    currentBackStackEntry.savedStateHandle.remove<Boolean>("courseUpdated")
                }
            }
        } catch (e: Exception) {
            Log.e("UserProfileView", "Error setting up SavedStateHandle listener", e)
        }
        
        // Also listen via FragmentManager as backup
        try {
            requireActivity().supportFragmentManager.setFragmentResultListener("videoUpdated", viewLifecycleOwner) { key, bundle ->
                Log.d("UserProfileView", "🔔 Fragment result received via FragmentManager! Key: $key")
                
                val updatedVideoId = bundle.getLong("updatedVideoId", 0L)
                val updatedTitle = bundle.getString("updatedTitle", "")
                val updatedDescription = bundle.getString("updatedDescription", "")
                val updatedIsPaid = bundle.getBoolean("updatedIsPaid", false)
                val updatedThumbnailUri = bundle.getString("updatedThumbnailUri")
                
                Log.d("UserProfileView", "📝 Received update - videoId: $updatedVideoId, title: $updatedTitle")
                
                if (updatedVideoId > 0) {
                    Log.d("UserProfileView", "✅ Updating local data for video ID: $updatedVideoId")
                    
                    // Fetch updated data from backend
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val videoResult = withContext(Dispatchers.IO) { BackendApiService.getVideoById(updatedVideoId) }
                            val updatedVideo = if (videoResult is ApiResult.Success) videoResult.data else null
                            if (updatedVideo != null) {
                                Log.d("UserProfileView", "✅ Fetched updated video from backend: ${updatedVideo.title}")
                                updateLocalVideoWithData(updatedVideo)
                            } else {
                                Log.w("UserProfileView", "⚠️ Failed to fetch updated video from backend, using local data")
                                updateLocalVideoData(updatedVideoId, updatedTitle, updatedDescription, updatedIsPaid, updatedThumbnailUri)
                            }
                        } catch (e: Exception) {
                            Log.e("UserProfileView", "Error fetching updated video", e)
                            updateLocalVideoData(updatedVideoId, updatedTitle, updatedDescription, updatedIsPaid, updatedThumbnailUri)
                        }
                    }
                } else {
                    Log.w("UserProfileView", "⚠️ Invalid video ID received: $updatedVideoId")
                }
            }
        } catch (e: Exception) {
            Log.e("UserProfileView", "Error setting up FragmentResultListener", e)
        }
    }
}