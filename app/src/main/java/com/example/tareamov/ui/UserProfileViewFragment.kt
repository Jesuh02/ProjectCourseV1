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
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.adapter.CreatedCourseAdapter
import com.example.tareamov.adapter.YouTubeStyleVideoAdapter
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.dao.PersonaDao
import com.example.tareamov.data.dao.SubscriptionDao
import com.example.tareamov.data.entity.ContentType
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.data.entity.UserContent
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.VideoManager
import com.example.tareamov.service.CloudflareR2Service
import com.example.tareamov.service.SupabaseClient
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
    private lateinit var coursesCountBadge: TextView
    private lateinit var videosCountBadge: TextView
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
    private lateinit var contentRecyclerView: RecyclerView
    private lateinit var emptyStateTextView: TextView
    private lateinit var contentAdapter: CreatedCourseAdapter
    private lateinit var videoAdapter: YouTubeStyleVideoAdapter
    private lateinit var videoManager: VideoManager
    private lateinit var courseRepository: com.example.tareamov.repository.CourseRepository
    private lateinit var syncRepository: com.example.tareamov.data.sync.SyncRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var bottomNavBinding: ComponentBottomNavigationBinding

    private var allContent = mutableListOf<VideoData>()
    private var allCourses = mutableListOf<VideoData>()
    private var allVideos = mutableListOf<VideoData>()
    private var allUserVideos = mutableListOf<VideoData>() // All videos from the user for search purposes
    private var filteredContent = mutableListOf<VideoData>() // For search results
    private var currentFilter = ContentType.COURSE
    private var isSearchMode = false
    
    // Video preview handling
    private val previewHandler = Handler(Looper.getMainLooper())
    private var previewRunnable: Runnable? = null
    private var currentPreviewPosition = -1
    private var currentSearchQuery = ""
    
    // Variable para el usuario cuyo perfil se está viendo
    private var username: String? = null

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
            
            // Initialize SyncRepository
            val db = AppDatabase.getDatabase(requireContext())
            syncRepository = com.example.tareamov.data.sync.SyncRepository(
                db.usuarioDao(),
                db.personaDao(),
                db.topicDao(),
                db.contentItemDao(),
                db.taskDao(),
                db.subscriptionDao(),
                db.taskSubmissionDao(),
                db.videoDao(),
                db.courseDao(),
                db.rolDao(),
                db.recursoDao(),
                db.rolRecursoDao(),
                db.chatMessageDao(),
                db.fileContextDao(),
                db.progresoEstudianteDao(),
                db.likeDao(),
                db.videoCommentDao()
            )
            syncRepository.initWithContext(requireContext())
            
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
        
        // Add/Upload Button (ic_add)
        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.action_userProfileViewFragment_to_contentUploadFragment)
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

        // Setup admin button visibility and functionality
        setupAdminButton()
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
                        val creatorId = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(course.username)
                        }
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
                        val creatorId = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(course.username)
                        }
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
                    // Show ConfirmDeleteDialogFragment and call performDeleteCourse(video) on confirm
                    val requestKey = "confirm_delete_video_${'$'}{video.id}"
                    parentFragmentManager.setFragmentResultListener(requestKey, viewLifecycleOwner) { _, bundle ->
                        val confirmed = bundle.getBoolean("confirmed", false)
                        if (confirmed) {
                            performDeleteCourse(video)
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
            val videoUri: String? = if (currentFilter == ContentType.COURSE) {
                val course = contentAdapter.getItem(centerPos)
                course?.localFilePath ?: course?.videoUriString
            } else {
                val video = videoAdapter.getItem(centerPos)
                video?.localFilePath ?: video?.videoUriString
            }
            
            if (!videoUri.isNullOrEmpty()) {
                if (currentFilter == ContentType.COURSE) {
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
            if (currentFilter == ContentType.COURSE) {
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

        videosFilterButton.setOnClickListener {
            setFilter(ContentType.VIDEO)
        }
    }    private fun setFilter(filterType: ContentType) {
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
        
        when (currentFilter) {
            ContentType.COURSE -> {
                // Courses selected - glass selected style
                coursesFilterButton.setBackgroundResource(R.drawable.bg_glass_filter_button_selected)
                videosFilterButton.setBackgroundResource(R.drawable.bg_glass_filter_button_unselected)
                
                // Update courses filter to dark text (selected)
                coursesFilterIcon?.setColorFilter(darkColor)
                coursesFilterText?.setTextColor(darkColor)
                
                // Update videos filter to white text (unselected)
                videosFilterIcon?.setColorFilter(whiteColor)
                videosFilterText?.setTextColor(whiteColor)
            }
            ContentType.VIDEO -> {
                // Videos selected - glass selected style
                videosFilterButton.setBackgroundResource(R.drawable.bg_glass_filter_button_selected)
                coursesFilterButton.setBackgroundResource(R.drawable.bg_glass_filter_button_unselected)
                
                // Update videos filter to dark text (selected)
                videosFilterIcon?.setColorFilter(darkColor)
                videosFilterText?.setTextColor(darkColor)
                
                // Update courses filter to white text (unselected)
                coursesFilterIcon?.setColorFilter(whiteColor)
                coursesFilterText?.setTextColor(whiteColor)
            }
        }
        
        // Update count badges
        updateCountBadges()
    }

    private fun updateCountBadges() {
        coursesCountBadge.text = allCourses.size.toString()
        videosCountBadge.text = allVideos.size.toString()
        
        // Also update the main statistics
        coursesCountTextView.text = allCourses.size.toString()
        videosCountTextView.text = allVideos.size.toString()
        
        Log.d("UserProfileView", "Updated count badges - Courses: ${allCourses.size}, Videos: ${allVideos.size}")
    }    private fun filterContent() {
        // If in search mode, don't change the search results view
        if (isSearchMode) {
            return
        }

        // Use a local reference to avoid repeated access to properties
        val currentList = when (currentFilter) {
            ContentType.COURSE -> allCourses
            ContentType.VIDEO -> allVideos
        }

        Log.d("UserProfileView", "Filtering content - Type: $currentFilter, Count: ${currentList.size}")

        // Optimize adapter updates by checking if the data has actually changed or if we can use more efficient update methods
        // Also, avoid recreating the adapter if possible, just swap the data
        
        if (currentList.isEmpty()) {
            emptyStateTextView.visibility = View.VISIBLE
            emptyStateTextView.text = when (currentFilter) {
                ContentType.COURSE -> "Este usuario no tiene cursos disponibles"
                ContentType.VIDEO -> "Este usuario no tiene videos disponibles"
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
                    // Only set adapter if it's different to avoid layout reset
                    if (contentRecyclerView.adapter != contentAdapter) {
                        contentRecyclerView.adapter = contentAdapter
                    }
                    
                    // Update data efficiently
                    // We don't need to clear and add all to allContent if the adapter handles its own list
                    // But since CreatedCourseAdapter takes a list in constructor, we might need to update it
                    // Assuming updateCourses handles diffing or efficient updates internally
                    contentAdapter.updateCourses(currentList)
                    // notifyDataSetChanged is heavy, prefer specific updates if possible, but for filter switch it's okay
                    // contentAdapter.notifyDataSetChanged() // updateCourses likely calls this or DiffUtil
                }
            }
            ContentType.VIDEO -> {
                if (::videoAdapter.isInitialized) {
                    if (contentRecyclerView.adapter != videoAdapter) {
                        contentRecyclerView.adapter = videoAdapter
                    }
                    videoAdapter.updateVideos(currentList)
                    // videoAdapter.notifyDataSetChanged() // updateVideos likely calls this
                }
            }
        }
    }private fun loadUserData(username: String) {
        lifecycleScope.launch {
            try {
                val database = AppDatabase.getDatabase(requireContext())

                // Use async to load user data, avatar and subscribers count in parallel
                val userDeferred = async(Dispatchers.IO) {
                    // Try Supabase first for fresh data (avatar, etc.)
                    var user: Usuario? = null
                    try {
                        if (SupabaseClient.isConfigured()) {
                            user = SupabaseClient.fetchUsuarioByUsername(username)
                        }
                    } catch (e: Exception) {
                        Log.e("UserProfileView", "Error fetching user from Supabase", e)
                    }
                    
                    // Fallback to local if Supabase failed or returned null
                    if (user == null) {
                        user = database.usuarioDao().getUsuarioByUsername(username)
                    }
                    user
                }
                
                // Fetch avatar URL directly from Supabase as a fallback
                val avatarDeferred = async(Dispatchers.IO) {
                    try {
                        SupabaseClient.fetchUsuarioAvatarByUsername(username)
                    } catch (e: Exception) {
                        Log.e("UserProfileView", "Error fetching avatar from Supabase", e)
                        null
                    }
                }
                
                val subscribersDeferred = async(Dispatchers.IO) {
                    try {
                        // Get userId from Supabase directly
                        val userId = SupabaseClient.getUserIdFromUsername(username)
                        if (userId != null) {
                            // Get subscriber count from Supabase only
                            SupabaseClient.fetchSubscriberCount(userId)
                        } else {
                            Log.w("UserProfileView", "Could not find userId for username: $username")
                            0L
                        }
                    } catch (e: Exception) {
                        Log.e("UserProfileView", "Error fetching subscriber count from Supabase", e)
                        0L
                    }
                }

                val user = userDeferred.await()
                val avatarUrl = avatarDeferred.await()
                val subscribersCount = subscribersDeferred.await()
                
                Log.d("UserProfileView", "Loaded data for username: $username")
                Log.d("UserProfileView", "User found: ${user != null}, avatar from user: ${user?.avatar}")
                Log.d("UserProfileView", "Avatar URL from Supabase: $avatarUrl")
                Log.d("UserProfileView", "Subscribers count from Supabase: $subscribersCount")

                // Actualizar UI con información del usuario
                withContext(Dispatchers.Main) {
                    // Mostrar el nombre de usuario (username) como solicitado
                    if (user != null && user.usuario.isNotEmpty()) {
                        usernameTextView.text = user.usuario
                    } else {
                        usernameTextView.text = username
                    }
                    
                    subscribersCountTextView.text = subscribersCount.toString()
                    Log.d("UserProfileView", "UI updated - Displaying subscribers count: $subscribersCount")

                    // Cargar avatar del usuario - prefer direct avatar URL if user's avatar is empty
                    val effectiveAvatarUrl = if (!user?.avatar.isNullOrEmpty()) user?.avatar else avatarUrl
                    if (user != null && effectiveAvatarUrl != null) {
                        loadUserAvatarFromUrl(effectiveAvatarUrl)
                    } else if (user != null) {
                        loadUserAvatar(user)
                    } else if (avatarUrl != null) {
                        loadUserAvatarFromUrl(avatarUrl)
                    } else {
                        userAvatarImageView.setImageResource(R.drawable.ic_profile)
                    }
                }

                // Cargar contenido del usuario (this is already optimized with async internally)
                loadUserContent(username, user?.id)
                
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
            // Prefer Supabase server-side filtered fetch for this specific user
            val act = activity as? com.example.tareamov.MainActivity
            var userCoursesList: List<com.example.tareamov.data.entity.Course> = emptyList()
            var userVideosList: List<VideoData> = emptyList()

            // Use async to fetch courses and videos in parallel
            if (act != null) {
                try {
                    withContext(Dispatchers.IO) {
                        val coursesDeferred = async {
                            act.syncRepository.fetchCoursesByCreatorFromSupabase(username)
                        }
                        
                        val videosDeferred = async {
                            // Two ways: by remote_id (userId) AND by username. Merge results and dedupe.
                            val targetUserId = userId ?: com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(username)

                            try {
                                if (targetUserId != null) {
                                    val byIdDeferred = async { act.syncRepository.fetchVideosByCreatorUserIdFromSupabase(targetUserId) }
                                    val byUsernameDeferred = async { act.syncRepository.fetchVideosByUsernameFromSupabase(username) }

                                    val byId = byIdDeferred.await()
                                    val byUsername = byUsernameDeferred.await()

                                    // Merge, deduplicate by id and prefer the provided username for display
                                    val merged = (byId + byUsername)
                                        .distinctBy { it.id }
                                        .map { it.copy(username = username) }
                                        .sortedByDescending { it.timestamp }

                                    merged
                                } else {
                                    act.syncRepository.fetchVideosByUsernameFromSupabase(username)
                                }
                            } catch (e: Exception) {
                                Log.w("UserProfileView", "Error fetching videos by both remote_id and username, falling back to username-only", e)
                                act.syncRepository.fetchVideosByUsernameFromSupabase(username)
                            }
                        }

                        val remoteCourses = coursesDeferred.await()
                        if (!remoteCourses.isNullOrEmpty()) {
                            userCoursesList = remoteCourses
                        }

                        val remoteVideos = videosDeferred.await()
                        if (!remoteVideos.isNullOrEmpty()) {
                            userVideosList = remoteVideos
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("UserProfileView", "Supabase fetch by creator/username failed, will fallback to local DB", e)
                }
            }

            // If Supabase didn't return results for one of the lists, fallback to local DB for that list
            if (userCoursesList.isEmpty()) {
                val allVideosData = getAllContentLikeExploreFragment()
                val (courses, _) = filterContentLikeExploreFragment(allVideosData, username, userId)
                userCoursesList = courses.map { course -> com.example.tareamov.repository.CourseRepository(requireContext()).convertVideoDataToCoursePublic(course) }
            }
            if (userVideosList.isEmpty()) {
                val allVideosData = getAllContentLikeExploreFragment()
                val (_, videos) = filterContentLikeExploreFragment(allVideosData, username, userId)
                userVideosList = videos
            }

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
                        path.startsWith("/") -> "file://$path"
                        // Windows-style absolute paths (may contain backslashes or drive letter)
                        path.matches(Regex("^[a-zA-Z]:\\.*")) || path.contains('\\') -> "file://$path"
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

                // Actualizar contadores en la UI
                coursesCountTextView.text = userCoursesList.size.toString()
                videosCountTextView.text = userVideosList.size.toString()
                
                // Update count badges
                updateCountBadges()
                
                // Aplicar el filtro actual para mostrar el contenido correcto
                filterContent()

                // Forzar actualización del adaptador con los datos normalizados
                if (::contentAdapter.isInitialized) {
                    contentAdapter.updateCourses(allCourses)
                }
                
                Log.d("UserProfileView", "Loaded content for user: $username - Courses: ${userCoursesList.size}, Videos: ${userVideosList.size}")
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
                val currentUserId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUserUsername)
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
                    // Unsubscribe
                    db.subscriptionDao().unsubscribeFromCreator(currentUserId, creatorUserId)
                    
                    // Also sync to Supabase
                    syncUnsubscriptionToSupabase(currentUserId, creatorUserId)
                    
                    showDarkToast("✅ Te has desuscrito")
                    Log.d("UserProfileView", "User $currentUserId unsubscribed from $creatorUserId")
                } else {
                    // Subscribe
                    db.subscriptionDao().subscribeToCreator(currentUserId, creatorUserId)
                    
                    // Also sync to Supabase
                    syncSubscriptionToSupabase(currentUserId, creatorUserId)
                    
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
                    com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId)
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
        
                val db = AppDatabase.getDatabase(requireContext())
                
                // Get user ID from username
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUserUsername)
                }
                
                if (userId == null) {
                    showDarkToast("Error: Usuario no encontrado")
                    Log.e("UserProfileView", "Failed to get user ID for username: $currentUserUsername")
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
                        Log.d("UserProfileView", "Course not in local DB, inserting: ${course.title}")
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
                    calificacionPonderada = 0f,
                    promedio = 0f,
                    estado = "Pendiente",
                    ultimaCalculadaEn = System.currentTimeMillis(),
                    certificadoEmitidoEn = null,
                    creadoEn = System.currentTimeMillis()
                )
                
                // Save locally
                withContext(Dispatchers.IO) {
                    db.progresoEstudianteDao().insertProgreso(progreso)
                }
                
                Log.d("UserProfileView", "✅ Progress record created locally for $currentUserUsername in course ${course.id}")
                
                // Sync to Supabase
                val syncRepo = getSyncRepository()
                val syncSuccess = withContext(Dispatchers.IO) {
                    syncRepo.syncProgresoToSupabase(progreso)
                }
                
                if (syncSuccess) {
                    showDarkToast("✅ Inscripción exitosa. ¡Comienza tu aprendizaje!")
                    Log.d("UserProfileView", "✅ Progress synced to Supabase for user $userId in course ${course.id}")
                    
                    // Refresh content to show enrollment status
                    username?.let { loadUserData(it) }
                } else {
                    showDarkToast("✅ Inscrito localmente. Se sincronizará con el servidor más tarde.")
                    Log.w("UserProfileView", "⚠️ Progress created locally but not synced to Supabase")
                }
                
            } catch (e: Exception) {
                Log.e("UserProfileView", "Error enrolling in course", e)
                showDarkToast("❌ Error al inscribirse en el curso")
            }
        }
    }

    /**
     * Sync subscription to Supabase (from ExploreFragment)
     */
    private suspend fun syncSubscriptionToSupabase(subscriberId: Long, creatorId: Long) {
        try {
            val sub = com.example.tareamov.data.entity.Subscription(
                subscriberId = subscriberId,
                creatorId = creatorId,
                subscriptionDate = System.currentTimeMillis()
            )
            com.example.tareamov.service.SupabaseClient.insertSubscriptionToSupabase(sub)
            Log.d("UserProfileView", "Subscription synced to Supabase: $subscriberId -> $creatorId")
        } catch (e: Exception) {
            Log.e("UserProfileView", "Error syncing subscription to Supabase", e)
        }
    }

    /**
     * Sync unsubscription to Supabase (from ExploreFragment)
     */
    private suspend fun syncUnsubscriptionToSupabase(subscriberId: Long, creatorId: Long) {
        try {
            com.example.tareamov.service.SupabaseClient.deleteSubscriptionFromSupabase(subscriberId, creatorId)
            Log.d("UserProfileView", "Unsubscription synced to Supabase: $subscriberId -> $creatorId")
        } catch (e: Exception) {
            Log.e("UserProfileView", "Error syncing unsubscription to Supabase", e)
        }
    }

    // Helper to obtain a SyncRepository instance (from ExploreFragment)
    private fun getSyncRepository(): com.example.tareamov.data.sync.SyncRepository {
        val db = AppDatabase.getDatabase(requireContext())
        return com.example.tareamov.data.sync.SyncRepository(
            usuarioDao = db.usuarioDao(),
            personaDao = db.personaDao(),
            topicDao = db.topicDao(),
            contentItemDao = db.contentItemDao(),
            taskDao = db.taskDao(),
            subscriptionDao = db.subscriptionDao(),
            taskSubmissionDao = db.taskSubmissionDao(),
            videoDao = db.videoDao(),
            courseDao = db.courseDao(),
            rolDao = db.rolDao(),
            recursoDao = db.recursoDao(),
            rolRecursoDao = db.rolRecursoDao(),
            chatMessageDao = db.chatMessageDao(),
            fileContextDao = db.fileContextDao(),
            progresoEstudianteDao = db.progresoEstudianteDao()
        )
    }

    private fun handleCourseClickWithEnrollment(course: VideoData) {
        val currentUserUsername = getCurrentUsername()
        
        // If not logged in, just navigate (or show login prompt)
        if (currentUserUsername == null) {
            showDarkToast("¡Debes iniciar sesión para acceder al curso!")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Check if user is creator
                val isCreator = if (currentUserUsername == course.username) {
                    true
                } else {
                    val currentUserId = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUserUsername)
                    }
                    val creatorUserId = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(course.username)
                    }
                    currentUserId != null && currentUserId == creatorUserId
                }

                if (isCreator) {
                    handleContentClick(course)
                    return@launch
                }

                // Check if paid
                if (course.isPaid && (course.price ?: 0.0) > 0) {
                    // Check if enrolled
                     val db = AppDatabase.getDatabase(requireContext())
                     val currentUserId = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUserUsername)
                    }
                    
                    if (currentUserId != null) {
                        val isEnrolled = withContext(Dispatchers.IO) {
                            db.progresoEstudianteDao().estaInscrito(currentUserId, course.id)
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

                // Free course: Auto-enroll if needed
                val db = AppDatabase.getDatabase(requireContext())
                val currentUserId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUserUsername)
                }

                if (currentUserId != null) {
                    // CRITICAL: Prevent course creator from enrolling in their own course
                    // Get actual Course entity to check creatorUserId
                    val actualCourse = withContext(Dispatchers.IO) {
                        db.courseDao().getCourseById(course.id)
                    }
                    
                    if (actualCourse != null && currentUserId == actualCourse.creatorUserId) {
                        Log.d("UserProfileView", "⚠️ Creator cannot enroll in own course ${course.id}")
                        handleContentClick(course)
                        return@launch
                    }
                    
                    val isEnrolled = withContext(Dispatchers.IO) {
                        db.progresoEstudianteDao().estaInscrito(currentUserId, course.id)
                    }

                    if (!isEnrolled) {
                        Log.d("UserProfileView", "Auto-enrolling user $currentUserUsername in course ${course.title}")
                        
                        // Get total tasks
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

                        // Create progress
                        val progreso = com.example.tareamov.data.entity.ProgresoEstudiante(
                            usuarioEstudiante = currentUserId,
                            cursoId = course.id,
                            tareasCompletadas = 0,
                            tareasTotales = totalTasks,
                            porcentajeProgreso = 0f,
                            calificacionPonderada = 0f,
                            promedio = 0f,
                            estado = "Pendiente",
                            ultimaCalculadaEn = System.currentTimeMillis(),
                            certificadoEmitidoEn = null,
                            creadoEn = System.currentTimeMillis()
                        )

                        // Save locally
                        withContext(Dispatchers.IO) {
                            db.progresoEstudianteDao().insertProgreso(progreso)
                        }

                        // Sync
                        val syncRepo = getSyncRepository()
                        withContext(Dispatchers.IO) {
                            syncRepo.syncProgresoToSupabase(progreso)
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
                // Navegar al detalle del curso
                val bundle = Bundle().apply {
                    putLong("courseId", content.id)
                    putString("courseName", content.title)
                }
                try {
                    findNavController().navigate(R.id.action_userProfileViewFragment_to_courseDetailFragment, bundle)
                } catch (e: Exception) {
                    Log.e("UserProfileView", "Navigation to courseDetailFragment failed: ${e.message}")
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
        }
    }

    private fun navigateToCourseDetail(course: VideoData) {
        lifecycleScope.launch {
            // Check if current user is the course creator by comparing user IDs
            val currentUserUsername = getCurrentUsername()
            val isCreator = if (currentUserUsername != null) {
                val currentUserId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUserUsername)
                }
                val creatorUserId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(course.username)
                }
                currentUserId != null && currentUserId == creatorUserId
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
                putString("courseTitle", course.title)
                putBoolean("isCreator", isCreator)
            }
            try {
                findNavController().navigate(R.id.action_userProfileViewFragment_to_courseDetailFragment, bundle)
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
        // Cargar datos del usuario
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
            val database = AppDatabase.getDatabase(requireContext())
            // Usar exactamente el mismo método que ExploreFragment
            database.videoDao().getAllVideos()
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
                performDeleteCourse(course)
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
    private fun performDeleteCourse(course: VideoData) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Show loading indicator
                Toast.makeText(requireContext(), "Eliminando curso...", Toast.LENGTH_SHORT).show()
                val deleteSuccess = withContext(Dispatchers.IO) {
                    // Resolve a likely remote Supabase course ID, preferring known courseId
                    var remoteCourseId: Long? = null
                    try {
                        if (course.courseId != null && course.courseId!! > 0) {
                            remoteCourseId = course.courseId
                        }

                        // If we don't have a good remoteCourseId yet, try to find it by title
                        if (remoteCourseId == null) {
                            try {
                                val found = com.example.tareamov.service.SupabaseClient.fetchCourseByTitle(course.title)
                                if (found != null) remoteCourseId = found.id
                            } catch (e: Exception) {
                                Log.w("UserProfileView", "Could not resolve remote course id by title: ${e.message}")
                            }
                        }

                        // Prefer calling backend to perform cascade deletion securely when we have a remote id
                        if (remoteCourseId != null) {
                            try {
                                val base = try {
                                    ServerEndpointResolver.getMcpBaseUrl()
                                } catch (e: Exception) {
                                    ServerEndpointResolver.fastResolveMcpBaseUrl()
                                }

                                val client = OkHttpClient()
                                val gson = Gson()
                                val jsonMedia = "application/json; charset=utf-8".toMediaType()

                                val payload = mapOf("courseId" to remoteCourseId)
                                val body = gson.toJson(payload).toRequestBody(jsonMedia)
                                val url = "${base.trimEnd('/')}/course/delete"

                                val req = Request.Builder()
                                    .url(url)
                                    .post(body)
                                    .addHeader("X-API-Key", "tareamov-mcp-api-key-2025-secure")
                                    .addHeader("Content-Type", "application/json")
                                    .build()

                                client.newCall(req).execute().use { resp ->
                                    val respBody = resp.body?.string() ?: ""
                                    if (resp.isSuccessful) {
                                        try {
                                            val tree: Map<*, *> = gson.fromJson(respBody, Map::class.java)
                                            val ok = tree["success"] as? Boolean ?: true
                                            if (ok) {
                                                // Backend deleted successfully; remove local rows and return
                                                try { courseRepository.deleteCourseById(remoteCourseId) } catch (_: Exception) {}

                                                try {
                                                    val remoteVideos = SupabaseClient.fetchVideosByCourseIds(listOf(remoteCourseId))
                                                    for (v in remoteVideos) {
                                                        try { SupabaseClient.deleteVideoById(v.id) } catch (_: Exception) {}
                                                    }
                                                } catch (_: Exception) {}

                                                return@withContext true
                                            }
                                        } catch (e: Exception) {
                                            Log.w("UserProfileView", "Could not parse backend response: $respBody")
                                        }
                                    }
                                    Log.w("UserProfileView", "Backend /course/delete failed: code=${resp.code} body=$respBody")
                                    // Fall through to Supabase fallback below
                                }
                            } catch (e: Exception) {
                                Log.w("UserProfileView", "Backend delete attempt failed: ${e.message}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("UserProfileView", "Error resolving remote course id: ${e.message}")
                    }

                    // Fallback: attempt to delete via SupabaseClient using multiple strategies
                    try {
                        // 1) If we resolved a remoteCourseId, delete videos for that course first
                        if (remoteCourseId != null) {
                            try {
                                val remoteVideos = SupabaseClient.fetchVideosByCourseIds(listOf(remoteCourseId))
                                for (v in remoteVideos) {
                                    try { SupabaseClient.deleteVideoById(v.id) } catch (_: Exception) {}
                                }
                            } catch (e: Exception) {
                                Log.w("UserProfileView", "Error deleting videos by remote course id: ${e.message}")
                            }

                            val courseDeleted = SupabaseClient.deleteCourseById(remoteCourseId)
                            if (courseDeleted) {
                                try { courseRepository.deleteCourseById(remoteCourseId) } catch (_: Exception) {}
                                return@withContext true
                            }
                        }

                        // 2) If remoteCourseId was not available, try to delete by local course.courseId (if present)
                        if (course.courseId != null && course.courseId!! > 0) {
                            val okCourse = SupabaseClient.deleteCourseById(course.courseId!!)
                            val okVideo = SupabaseClient.deleteVideoById(course.id)
                            if (okCourse || okVideo) {
                                try { courseRepository.deleteCourseById(course.courseId!!) } catch (_: Exception) {}
                                try {
                                    val localVideo = courseRepository.getVideoById(course.id)
                                    if (localVideo != null) courseRepository.deleteVideo(localVideo)
                                } catch (_: Exception) {}
                                return@withContext true
                            }
                        }

                        // 3) Last-resort: try deleting video using its id (may be remote id if synced)
                        val supabaseVideoDeleted = SupabaseClient.deleteVideoById(course.id)
                        if (supabaseVideoDeleted) {
                            try { courseRepository.deleteCourseById(course.id) } catch (_: Exception) {}
                            try {
                                val videoData = courseRepository.getVideoById(course.id)
                                if (videoData != null) courseRepository.deleteVideo(videoData)
                            } catch (_: Exception) {}
                            return@withContext true
                        }

                        Log.w("UserProfileView", "Failed to delete course/video via Supabase fallback")
                        false
                    } catch (e: Exception) {
                        Log.e("UserProfileView", "Exception in fallback Supabase delete: ${e.message}", e)
                        false
                    }
                }

                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    if (deleteSuccess) {
                        // Remove from local lists immediately for faster UI response using ID to ensure correct removal
                        val idToRemove = course.id
                        
                        // Remove from all lists
                        allContent.removeAll { it.id == idToRemove }
                        allCourses.removeAll { it.id == idToRemove }
                        allVideos.removeAll { it.id == idToRemove }
                        
                        // Update adapters
                        if (::contentAdapter.isInitialized) {
                            contentAdapter.removeCourse(idToRemove)
                        }
                        
                        if (::videoAdapter.isInitialized) {
                            videoAdapter.updateVideos(allVideos)
                        }
                        
                        Log.d("UserProfileView", "Course removed from local lists and adapter: ${course.title}")

                        // Update counts
                        coursesCountTextView.text = allCourses.size.toString()
                        videosCountTextView.text = allVideos.size.toString()
                        updateCountBadges()

                        // Refresh the content display
                        filterContent()
                        
                        Toast.makeText(requireContext(), "✅ Curso eliminado: ${course.title}", Toast.LENGTH_SHORT).show()
                        Log.d("UserProfileView", "Course successfully deleted: ${course.title}")
                    } else {
                        Toast.makeText(requireContext(), "❌ Error al eliminar el curso de Supabase", Toast.LENGTH_LONG).show()
                    }
                }

            } catch (e: Exception) {
                Log.e("UserProfileView", "Error deleting course: ${course.title}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "❌ Error al eliminar el curso", Toast.LENGTH_SHORT).show()
                }
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
                    // Update in VideoData table
                    videoManager.updateVideo(updatedCourse)

                    // Update in Course table
                    updateCourseInTable(updatedCourse)
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
                    val course = courseRepository.convertVideoDataToCoursePublic(videoData)
                    withContext(Dispatchers.IO) {
                        courseRepository.updateCourse(course)
                    }
                    Log.d("UserProfileView", "Course updated in Course table: ${videoData.title}")
                } catch (e: Exception) {
                    Log.e("UserProfileView", "Error updating course in table", e)
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
                                Log.d("UserProfileView", "☁️ Thumbnail uploaded to R2: $finalThumbnailUri")
                            }
                            is CloudflareR2Service.UploadResult.Error -> {
                                Log.e("UserProfileView", "❌ Failed to upload thumbnail: ${result.message}")
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

    private fun setupAdminButton() {
        val adminSlot = bottomNavBinding.adminSlot
        val goToAdminButton = bottomNavBinding.goToAdminButton
        Log.d("UserProfileViewFragment", "setupAdminButton called, button found: ${goToAdminButton != null}")

        // Inicializa como INVISIBLE para evitar salto al inflar
        goToAdminButton.visibility = View.INVISIBLE

        // Decidir con SessionManager antes del primer render para evitar hueco
        val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        val isAdmin = sess.isAdmin()
        val hasAdminRole = sess.hasRole(3)

        if (!isAdmin && !hasAdminRole) {
            // Ocultar slot completo antes del render para que no quede hueco
            adminSlot.visibility = View.GONE
            Log.d("UserProfileViewFragment", "Admin slot hidden (user not admin)")
            return
        }
        // Usuario admin: mostrar slot y botón, y asignar listener
        adminSlot.visibility = View.VISIBLE
        goToAdminButton.visibility = View.VISIBLE
        goToAdminButton.setOnClickListener {
            Log.d("UserProfileViewFragment", "Admin button clicked, navigating to HomeFragment")
            findNavController().navigate(R.id.action_userProfileViewFragment_to_homeFragment)
        }
        Log.d("UserProfileViewFragment", "Admin button made visible and click listener set")
    }

    private fun checkAdminStatus(callback: (Boolean) -> Unit) {
        val username = sessionManager.getUsername()
        if (username == null) {
            Log.d("UserProfileViewFragment", "Username is null, user is not admin")
            callback(false)
            return
        }

        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val usuarioWithRole = withContext(Dispatchers.IO) {
                    db.usuarioDao().getUsuarioWithRoleByUsername(username)
                }
                
                val isAdmin = usuarioWithRole?.isAdmin == true
                Log.d("UserProfileViewFragment", "User $username is admin: $isAdmin (role: ${usuarioWithRole?.rolNombre})")
                
                // Ensure UI update happens on main thread
                withContext(Dispatchers.Main) {
                    callback(isAdmin)
                }
            } catch (e: Exception) {
                Log.e("UserProfileViewFragment", "Error checking admin status", e)
                withContext(Dispatchers.Main) {
                    callback(false)
                }
            }
        }
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
                        Log.d("UserProfileView", "🚀 Starting Supabase fetch for video ID: $updatedVideoId")
                        // Fetch updated data from Supabase as requested
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val updatedVideo = syncRepository.fetchVideoByIdFromSupabase(updatedVideoId)
                                if (updatedVideo != null) {
                                    Log.d("UserProfileView", "✅ Fetched updated video from Supabase: ${updatedVideo.title}")
                                    updateLocalVideoWithData(updatedVideo)
                                } else {
                                    Log.w("UserProfileView", "⚠️ Failed to fetch updated video from Supabase, using local data")
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
                    
                    // Fetch updated data from Supabase as requested
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val updatedVideo = syncRepository.fetchVideoByIdFromSupabase(updatedVideoId)
                            if (updatedVideo != null) {
                                Log.d("UserProfileView", "✅ Fetched updated video from Supabase: ${updatedVideo.title}")
                                updateLocalVideoWithData(updatedVideo)
                            } else {
                                Log.w("UserProfileView", "⚠️ Failed to fetch updated video from Supabase, using local data")
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