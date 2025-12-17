package com.example.tareamov.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.content.Intent // Add this import for Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView  // Add this import for ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.adapter.VideoAdapter
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.util.VideoManager
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import java.io.File
// Add this import if it's missing, for Usuario.ROL_ADMIN
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.data.entity.Subscription
// Import SessionManager
import com.example.tareamov.util.SessionManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.MediaPlayer // Required for MediaPlayer interactions if direct
import android.widget.EditText
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.example.tareamov.data.sync.SyncRepository
import android.text.Editable
import android.text.TextWatcher
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderScriptBlur
import android.view.ViewOutlineProvider
import android.animation.ObjectAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.tareamov.ui.ShimmerFrameLayout

class VideoHomeFragment : Fragment() {
    private lateinit var profileAvatars: CircleImageView
    private lateinit var videoManager: VideoManager
    private lateinit var sessionManager: SessionManager // Add SessionManager instance
    private lateinit var skeletonContainer: ShimmerFrameLayout // Skeleton container
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var homeIconImageView: ImageView
    private lateinit var exploreIconImageView: ImageView
    private lateinit var activityIconImageView: ImageView
    private lateinit var profileIconImageView: ImageView
    private lateinit var databaseIconImageView: ImageView // New database icon for admins
    private lateinit var aiAssistantIconImageView: ImageView // New AI Assistant icon
    private var notificationBadge: TextView? = null // Badge de notificaciones

    private var isLiked = false
    private var isMuted = false
    
    // Paginación
    private val videoList = mutableListOf<VideoData>()
    private var currentVideoIndex = 0
    private lateinit var videoAdapter: VideoAdapter
    private var currentPage = 0
    private val pageSize = 10
    private var totalVideos = 0
    private var isLoadingVideos = false
    
    // Search variables
    private var isSearchMode = false
    private var currentSearchQuery = ""
    private var currentSearchType = "all"
    private lateinit var syncRepository: SyncRepository
    
    // Store all videos for fast local filtering
    private val allVideosList = mutableListOf<VideoData>()
    
    private var searchJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_video_home, container, false)
    }

    private var isVideosLoaded = false // Flag para evitar cargas duplicadas

    // In the onViewCreated method, update the goToHomeButton click listener
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize VideoManager
        videoManager = VideoManager(requireContext())
        sessionManager = SessionManager.getInstance(requireContext()) // Initialize SessionManager
        
        // Initialize SyncRepository
        val database = AppDatabase.getDatabase(requireContext())
        syncRepository = SyncRepository(
            usuarioDao = database.usuarioDao(),
            personaDao = database.personaDao(),
            topicDao = database.topicDao(),
            contentItemDao = database.contentItemDao(),
            taskDao = database.taskDao(),
            subscriptionDao = database.subscriptionDao(),
            taskSubmissionDao = database.taskSubmissionDao(),
            videoDao = database.videoDao(),
            courseDao = database.courseDao(),
            rolDao = database.rolDao(),
            recursoDao = database.recursoDao(),
            rolRecursoDao = database.rolRecursoDao(),
            chatMessageDao = database.chatMessageDao(),
            fileContextDao = database.fileContextDao(),
            progresoEstudianteDao = database.progresoEstudianteDao(),
            videoLikeDao = database.videoLikeDao(),
            videoCommentDao = database.videoCommentDao()
        )

        // Refresh session info from Supabase in background so role checks are current
        lifecycleScope.launch {
            try {
                val refreshed = sessionManager.refreshFromSupabase()
                android.util.Log.d("VideoHomeFragment", "Session refreshFromSupabase returned: $refreshed")
            } catch (e: Exception) {
                android.util.Log.w("VideoHomeFragment", "Failed to refresh session from Supabase", e)
            }
        }

        // Obtener parámetros de navegación para video específico
        val videoId = arguments?.getLong("videoId", -1L) ?: -1L
        val videoTitle = arguments?.getString("videoTitle")
        val videoUsername = arguments?.getString("videoUsername")
        
        if (videoId != -1L) {
            Log.d("VideoHomeFragment", "📹 Video specific navigation requested:")
            Log.d("VideoHomeFragment", "  - videoId: $videoId")
            Log.d("VideoHomeFragment", "  - videoTitle: $videoTitle")
            Log.d("VideoHomeFragment", "  - videoUsername: $videoUsername")
        }

        // Initialize views
        profileAvatars = view.findViewById(R.id.profileAvatars)
        skeletonContainer = view.findViewById(R.id.skeletonContainer)

        // Initialize bottom navigation icons
        homeIconImageView = view.findViewById(R.id.homeIconImageView)
        exploreIconImageView = view.findViewById(R.id.exploreIconImageView)
        activityIconImageView = view.findViewById(R.id.activityIconImageView)
        profileIconImageView = view.findViewById(R.id.profileIconImageView)
        databaseIconImageView = view.findViewById(R.id.databaseIconImageView) // Initialize new icon
        aiAssistantIconImageView = view.findViewById(R.id.aiAssistantIconImageView) // Initialize AI icon
        notificationBadge = view.findViewById(R.id.notificationBadge) // Badge de notificaciones

        // Setup initial colors for bottom navigation icons
        setupBottomNavigationIconColors()
        
        // Actualizar badge de notificaciones
        updateNotificationBadge()
        
        // Setup search functionality
        setupSearchBar(view)

        // Setup AI Assistant Icon with animation and click listener
        aiAssistantIconImageView.setOnClickListener {
            try {
                findNavController().navigate(R.id.action_videoHomeFragment_to_chatBotFragment)
            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error navigating to ChatBotFragment", e)
            }
        }
        
        // Professional animation for AI icon (Subtle Rotate + Scale)
        val scaleX = ObjectAnimator.ofFloat(aiAssistantIconImageView, "scaleX", 1f, 1.15f, 1f)
        val scaleY = ObjectAnimator.ofFloat(aiAssistantIconImageView, "scaleY", 1f, 1.15f, 1f)
        val rotate = ObjectAnimator.ofFloat(aiAssistantIconImageView, "rotation", 0f, 5f, -5f, 0f)
        
        scaleX.repeatCount = ObjectAnimator.INFINITE
        scaleY.repeatCount = ObjectAnimator.INFINITE
        rotate.repeatCount = ObjectAnimator.INFINITE
        
        scaleX.duration = 3000
        scaleY.duration = 3000
        rotate.duration = 3000
        
        scaleX.interpolator = AccelerateDecelerateInterpolator()
        scaleY.interpolator = AccelerateDecelerateInterpolator()
        rotate.interpolator = AccelerateDecelerateInterpolator()
        
        val animatorSet = android.animation.AnimatorSet()
        animatorSet.playTogether(scaleX, scaleY, rotate)
        animatorSet.start()

        // Initial setup for database icon (will be updated by updateAdminUi)
        databaseIconImageView.visibility = View.GONE

        // Enhanced Courses Button with improved animations and interactions
        val coursesButton = view.findViewById<ImageView>(R.id.coursesButton)
        coursesButton?.setOnClickListener {
            // Add subtle scale animation on click
            it.animate()
                .scaleX(0.9f)
                .scaleY(0.9f)
                .setDuration(100)
                .withEndAction {
                    it.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                }
                .start()

            // Navigate to login with a slight delay for animation to complete
            it.postDelayed({
                findNavController().navigate(R.id.loginFragment)
            }, 150)
        }

        // Add this block to navigate to CourseDetailFragment when profile is clicked
        profileAvatars.setOnClickListener {
            // Get the current video (or course) data
            val currentVideo = videoList.getOrNull(currentVideoIndex)
            if (currentVideo != null) {
                lifecycleScope.launch {
                    try {
                        val courseRepo = com.example.tareamov.repository.CourseRepository(requireContext())
                        val course = withContext(Dispatchers.IO) { courseRepo.getCourseById(currentVideo.id) }
                        val targetId = course?.id ?: currentVideo.id
                        val bundle = Bundle().apply {
                            putLong("courseId", targetId)
                            putString("courseName", course?.title ?: currentVideo.title)
                        }
                        // Check if current destination is still VideoHomeFragment before navigating
                        val navController = findNavController()
                        if (navController.currentDestination?.id == R.id.videoHomeFragment) {
                            navController.navigate(R.id.action_videoHomeFragment_to_courseDetailFragment, bundle)
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error resolving course for profile click", e)
                        val bundle = Bundle().apply {
                            putLong("courseId", currentVideo.id)
                            putString("courseName", currentVideo.title)
                        }
                        // Check if current destination is still VideoHomeFragment before navigating
                        val navController = findNavController()
                        if (navController.currentDestination?.id == R.id.videoHomeFragment) {
                            navController.navigate(R.id.action_videoHomeFragment_to_courseDetailFragment, bundle)
                        }
                    }
                }
            } else {
                Toast.makeText(requireContext(), "No course information available", Toast.LENGTH_SHORT).show()
            }
        }

        // Also set up the profile avatars in the top bar to navigate to profile
        profileAvatars.setOnClickListener {
            navigateToProfileSafely()
        }

        // Add this code to handle the bottom navigation profile button click
        val profileNavButton = view.findViewById<LinearLayout>(R.id.profileNavButton)
        profileNavButton?.setOnClickListener {
            navigateToProfileSafely()
        }

        // Set up button to navigate to the content upload screen
        view.findViewById<ImageButton>(R.id.goToHomeButton)?.setOnClickListener {
            // Navigate to ContentUploadFragment first to select a video
            findNavController().navigate(R.id.action_videoHomeFragment_to_contentUploadFragment)
        }

        // Set up Explorar button to navigate to ExploreFragment
        val exploreButton = view.findViewById<LinearLayout>(R.id.exploreButton)
        exploreButton?.setOnClickListener {
            findNavController().navigate(R.id.action_videoHomeFragment_to_exploreFragment)
        }

        // Set up Activity button to navigate to NotificacionesFragment
        val activityButton = view.findViewById<LinearLayout>(R.id.activityButton)
        activityButton?.setOnClickListener {
            findNavController().navigate(R.id.action_videoHomeFragment_to_notificacionesFragment)
        }

        // Mostrar/ocultar slot admin según rol (evita hueco para no-admins)
        val adminSlot = view.findViewById<android.widget.FrameLayout>(R.id.adminSlot)
        val goToAdminButton = view.findViewById<LinearLayout>(R.id.goToAdminButton)

        // Initially hide the admin button to avoid reflow during async check
        goToAdminButton?.visibility = View.INVISIBLE

        // Check if the current user is admin
        val sess = SessionManager.getInstance(requireContext())
        
        // Function to update admin UI elements
        fun updateAdminUi(isAdmin: Boolean) {
            // New Logic: Check if user has explicit role ID 2 (AiAssistant access)
            val hasAiRole = sess.hasRole(2)
            
            if (hasAiRole) {
                // Show AI Assistant icon for Role 2
                aiAssistantIconImageView.visibility = View.VISIBLE
                aiAssistantIconImageView.setOnClickListener {
                     // Navigate to AI Assistant or ChatBot
                     findNavController().navigate(R.id.action_videoHomeFragment_to_chatBotFragment)
                }
            } else {
                 aiAssistantIconImageView.visibility = View.GONE
            }

            if (isAdmin) {
                // Admin: Show database icon with animated drawable
                databaseIconImageView.visibility = View.VISIBLE
                databaseIconImageView.setImageResource(R.drawable.ic_database_orbit_animated_anim)
                
                // Start animation automatically
                val drawable = databaseIconImageView.drawable
                if (drawable is android.graphics.drawable.AnimatedVectorDrawable) {
                    drawable.start()
                }
                
                databaseIconImageView.setOnClickListener {
                    findNavController().navigate(R.id.action_videoHomeFragment_to_databaseQueryFragment)
                }
                
                // Admin: Show admin slot and button
                adminSlot?.visibility = View.VISIBLE
                goToAdminButton?.visibility = View.VISIBLE
                goToAdminButton?.setOnClickListener {
                    Log.d("VideoHomeFragment", "Admin button clicked, navigating to HomeFragment")
                    findNavController().navigate(R.id.action_videoHomeFragment_to_homeFragment)
                }
            } else {
                // Non-admin: Hide elements
                databaseIconImageView.visibility = View.GONE
                adminSlot?.visibility = View.GONE
            }
        }

        // Initial synchronous check using SessionManager (fast)
        updateAdminUi(sess.isAdmin())

        // Async check using SyncRepository (robust, checks ID 3)
        lifecycleScope.launch {
            val userId = getCurrentUserId()
            if (userId > 0) {
                // Check if admin (Role 3)
                val isAdmin = withContext(Dispatchers.IO) {
                    syncRepository.isUserAdmin(userId)
                }
                
                // Check if user has AI role (Role 2)
                val hasAiRole = withContext(Dispatchers.IO) {
                    syncRepository.hasUserRole(userId, 2)
                }
                
                // Update SessionManager
                sess.setAdminStatus(isAdmin)
                if (hasAiRole) sess.addRole(2) else sess.removeRole(2)
                
                withContext(Dispatchers.Main) {
                    updateAdminUi(isAdmin)
                }
            }
        }   // Load the current user's avatar
        loadCurrentUserAvatar()

        // Load videos directly from Supabase (ordered newest -> oldest) and display
        // This will show videos from all users and bypass Room for this fragment's feed
        setupVideoViewPager(view)
        lifecycleScope.launch {
            // Show skeleton only on initial load (empty list)
            if (videoList.isEmpty()) {
                startSkeletonAnimation()
            } else {
                // If we already have data, ensure skeleton is hidden immediately
                skeletonContainer.visibility = View.GONE
            }
            
            try {
                val act = requireActivity()
                if (act is com.example.tareamov.MainActivity) {
                    val repo = act.syncRepository
                    try {
                        // First fetch all videos
                        val supaVideos = repo.fetchVideosFromSupabase()
                        
                        // If a specific video is requested, try to find it
                        var targetVideo: VideoData? = null
                        var targetIndex = -1
                        
                        if (videoId != -1L) {
                            Log.d("VideoHomeFragment", "🔍 Looking for video ID: $videoId")
                            
                            // First try to find in the fetched list
                            targetIndex = supaVideos.indexOfFirst { it.id == videoId }
                            if (targetIndex >= 0) {
                                targetVideo = supaVideos[targetIndex]
                                Log.d("VideoHomeFragment", "✅ Video found in list at index $targetIndex: ${targetVideo.title}")
                            } else {
                                // Not in list, try to fetch directly from Supabase
                                Log.d("VideoHomeFragment", "⚠️ Video not in list, fetching directly...")
                                targetVideo = withContext(Dispatchers.IO) {
                                    com.example.tareamov.service.SupabaseClient.fetchVideoById(videoId)
                                }
                                if (targetVideo != null) {
                                    Log.d("VideoHomeFragment", "📹 Video fetched directly: ${targetVideo.title}")
                                } else {
                                    Log.w("VideoHomeFragment", "❌ Video ID $videoId not found anywhere")
                                }
                            }
                        }
                        
                        // Replace adapter data on main thread
                        withContext(Dispatchers.Main) {
                            // Update videoList with fetched videos
                            videoList.clear()
                            
                            // If we have a target video that was NOT in the original list, add it first
                            if (targetVideo != null && targetIndex < 0) {
                                videoList.add(targetVideo)
                                videoList.addAll(supaVideos)
                                Log.d("VideoHomeFragment", "✅ Target video added at index 0 (was not in list)")
                            } else {
                                videoList.addAll(supaVideos)
                            }
                            
                            videoAdapter.updateVideos(videoList)
                            isVideosLoaded = true
                            
                            // Navigate to the target video position
                            if (videoId != -1L && targetVideo != null) {
                                val finalIndex = if (targetIndex >= 0) targetIndex else 0
                                Log.d("VideoHomeFragment", "🎯 Scrolling to video at index $finalIndex")
                                view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)?.setCurrentItem(finalIndex, false)
                            } else if (videoId != -1L) {
                                Log.w("VideoHomeFragment", "⚠️ Target video ID $videoId could not be found")
                                Toast.makeText(requireContext(), "Video no encontrado", Toast.LENGTH_SHORT).show()
                            }
                            // Hide skeleton after successful load
                            stopSkeletonAnimation()
                        }
                    } catch (e: Exception) {
                        Log.w("VideoHomeFragment", "Error fetching videos from Supabase", e)
                        // Fallback: attempt to load from local DB
                        loadVideos(videoId, videoTitle, videoUsername, true)
                    }
                } else {
                    loadVideos(videoId, videoTitle, videoUsername)
                }
            } catch (e: Exception) {
                Log.w("VideoHomeFragment", "Could not access SyncRepository to fetch videos", e)
                loadVideos(videoId, videoTitle, videoUsername, true)
            }
        }

        // Inicializar el adaptador de videos y configurar el ViewPager2
        setupVideoViewPager(view)
    }

    // REMOVE the checkCurrentUserAdminStatus() function as it's no longer needed
    // private suspend fun checkCurrentUserAdminStatus(): Boolean { ... }

    private suspend fun getCurrentUserId(): Long {
        var userId = sessionManager.getUserId()
        if (userId == -1L) {
            val username = getCurrentUsername()
            if (username != null) {
                val user = syncRepository.getUsuarioByUsernameLocal(username)
                if (user != null) {
                    userId = user.id
                }
            }
        }
        return userId
    }

    private suspend fun checkIfSubscribed(creatorId: Long): Boolean {
        val currentUserId = getCurrentUserId()
        if (currentUserId == -1L) return false
        
        return try {
            // Use SupabaseClient for validation as requested
            withContext(Dispatchers.IO) {
                com.example.tareamov.service.SupabaseClient.isSubscribedRemote(currentUserId, creatorId)
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error checking remote subscription", e)
            // Fallback to local if remote fails
            syncRepository.isSubscribedLocal(currentUserId, creatorId)
        }
    }

    private suspend fun handleSubscriptionToggle(creatorId: Long, isSubscribing: Boolean) {
        val currentUserId = getCurrentUserId()
        if (currentUserId == -1L) {
             withContext(Dispatchers.Main) {
                 Toast.makeText(context, "Debes iniciar sesión para suscribirte", Toast.LENGTH_SHORT).show()
             }
             return
        }
        
        try {
            if (isSubscribing) {
                val subscription = Subscription(
                    subscriberId = currentUserId,
                    creatorId = creatorId,
                    subscriptionDate = System.currentTimeMillis()
                )
                // Update local immediately for UI responsiveness
                syncRepository.insertSubscriptionLocal(subscription)
                
                // Update Supabase
                withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.insertSubscriptionToSupabase(subscription)
                }
            } else {
                // Update local immediately
                syncRepository.deleteSubscriptionLocal(currentUserId, creatorId)
                
                // Update Supabase
                withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.deleteSubscriptionFromSupabase(currentUserId, creatorId)
                }
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error toggling subscription", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error al actualizar suscripción en Supabase", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupBottomNavigationIconColors() {
        // Active color (Purple)
        val activeColor = Color.parseColor("#9C27B0")
        // Inactive color (White)
        val inactiveColor = Color.parseColor("#FFFFFF")

        // Set "Inicio" to active (purple), others to inactive (white)
        homeIconImageView.setColorFilter(activeColor, PorterDuff.Mode.SRC_IN)
        exploreIconImageView.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN)
        activityIconImageView.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN)
        profileIconImageView.setColorFilter(inactiveColor, PorterDuff.Mode.SRC_IN)
    }

    /**
     * Actualiza el badge de notificaciones no leídas
     */
    private fun updateNotificationBadge() {
        val userId = sessionManager.getUserId()
        if (userId == -1L) {
            notificationBadge?.visibility = View.GONE
            return
        }

        lifecycleScope.launch {
            try {
                val unreadCount = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.countUnreadNotifications(userId)
                }
                
                notificationBadge?.let { badge ->
                    if (unreadCount > 0) {
                        badge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                        badge.visibility = View.VISIBLE
                    } else {
                        badge.visibility = View.GONE
                    }
                }
            } catch (e: Exception) {
                Log.w("VideoHomeFragment", "Error updating notification badge", e)
                notificationBadge?.visibility = View.GONE
            }
        }
    }

    private fun setupVideoViewPager(view: View) {
        // Inicializar el adaptador con la lista de videos y callback para profile clicks
        videoAdapter = VideoAdapter(
            videoList,
            onProfileClick = { username ->
                // Handle profile click
                val bundle = Bundle().apply {
                    putString("username", username)
                }
                findNavController().navigate(R.id.userProfileViewFragment, bundle)
            },
            onUsernameClick = { videoData ->
                // Navigate to CourseDetailFragment using course_id from video
                lifecycleScope.launch {
                    try {
                        // Use courseId directly from video if available
                        if (videoData.courseId != null && videoData.courseId!! > 0) {
                            val bundle = Bundle().apply {
                                putLong("courseId", videoData.courseId!!)
                                putString("courseName", videoData.title)
                            }
                            
                            // Check if current destination is still VideoHomeFragment before navigating
                            val navController = findNavController()
                            if (navController.currentDestination?.id == R.id.videoHomeFragment) {
                                navController.navigate(R.id.action_videoHomeFragment_to_courseDetailFragment, bundle)
                            }
                        } else {
                            // Fallback: search for course by title and username
                            val act = requireActivity()
                            var matchingCourse: com.example.tareamov.data.entity.Course? = null

                            if (act is com.example.tareamov.MainActivity) {
                                try {
                                    // Try fetching by creator and matching title
                                    val remoteList = withContext(Dispatchers.IO) {
                                        act.syncRepository.fetchCoursesByCreatorFromSupabase(videoData.username ?: "")
                                    }
                                    matchingCourse = remoteList.firstOrNull { c -> 
                                        (c.title ?: "").equals(videoData.title ?: "", ignoreCase = true) 
                                    }
                                } catch (e: Exception) {
                                    Log.w("VideoHomeFragment", "Supabase course lookup failed: ${e.message}", e)
                                }
                            }

                            val bundle = Bundle().apply {
                                if (matchingCourse != null) {
                                    putLong("courseId", matchingCourse.id ?: -1L)
                                    putString("courseName", matchingCourse.title ?: videoData.title)
                                } else {
                                    putLong("courseId", -1L)
                                    putString("courseName", videoData.title)
                                }
                            }

                            // Check if current destination is still VideoHomeFragment before navigating
                            val navController = findNavController()
                            if (navController.currentDestination?.id == R.id.videoHomeFragment) {
                                navController.navigate(R.id.action_videoHomeFragment_to_courseDetailFragment, bundle)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error navigating to CourseDetailFragment for video ${videoData.id}", e)
                        Toast.makeText(context, "No se pudo abrir el curso", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onSubscribeToggle = { creatorId, isSubscribed ->
                lifecycleScope.launch(Dispatchers.IO) {
                    handleSubscriptionToggle(creatorId, isSubscribed)
                }
            },
            checkSubscriptionStatus = { creatorId ->
                checkIfSubscribed(creatorId)
            },
            onLikeToggle = { videoData, isLiked ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val userId = getCurrentUserId()
                        if (userId > 0) {
                            syncRepository.toggleVideoLike(videoData.id, userId, isLiked)
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error toggling like", e)
                    }
                }
            },
            onCommentClick = { videoData ->
                // Show comment dialog or navigate to comments
                showCommentsDialog(videoData)
            },
            checkUserLikedVideo = { videoId ->
                val userId = getCurrentUserId()
                if (userId > 0) {
                    syncRepository.hasUserLikedVideo(videoId, userId)
                } else {
                    false
                }
            },
            getLikeCount = { videoId ->
                syncRepository.getVideoLikeCount(videoId)
            },
            getCommentCount = { videoId ->
                syncRepository.getVideoCommentCount(videoId)
            }
        )

        // Configurar el ViewPager2
        val viewPager = view.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)
        viewPager.adapter = videoAdapter

        // Load current user ID and pass to adapter
        lifecycleScope.launch {
            val userId = getCurrentUserId()
            videoAdapter.setCurrentUserId(userId)
        }

        // Configurar orientación vertical para deslizar como TikTok
        viewPager.orientation = androidx.viewpager2.widget.ViewPager2.ORIENTATION_VERTICAL

        // Desactivar el overscroll effect (el efecto de rebote al final de la lista)
        viewPager.getChildAt(0).overScrollMode = View.OVER_SCROLL_NEVER        // Listener para cambios de página
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                currentVideoIndex = position

                // Cargar más videos cuando se acerque al final
                if (position >= videoList.size - 2 && !isLoadingVideos && videoList.size < totalVideos) {
                    Log.d("VideoHomeFragment", "Near end of list (pos $position/${videoList.size}), loading more...")
                    loadMoreVideos()
                }

                // Pausar todos los videos y reproducir solo el actual
                val viewHolder = (viewPager.getChildAt(0) as RecyclerView)
                    .findViewHolderForAdapterPosition(position) as? VideoAdapter.VideoViewHolder
                viewHolder?.playVideo()
                viewHolder?.setMuteState(isMuted) // Apply current mute state

                // Actualizar la información en pantalla (ya no necesario, cada video maneja su propia info)
                // displayVideo(videoList[position]) - Removed as video info is handled by individual items
            }
        })
    }

    // Update these methods to work with ViewPager2
    private fun showNextVideo() {
        if (currentVideoIndex < videoList.size - 1) {
            currentVideoIndex++
            view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)?.currentItem = currentVideoIndex
        }
    }

    private fun showPreviousVideo() {
        if (currentVideoIndex > 0) {
            currentVideoIndex--
            view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)?.currentItem = currentVideoIndex
        }
    }

    private fun displayVideo(videoData: VideoData) {
        // Video info display is now handled by individual video items in ViewPager2
        // This method is kept for potential future use but functionality moved to VideoAdapter

        // Always use the local file path if available
        val videoPath = videoData.localFilePath
        if (videoPath != null && File(videoPath).exists()) {
            // Use this path for playback (e.g., setVideoPath or ExoPlayer)
            Log.d("VideoHomeFragment", "Playing video from local file: $videoPath")
        } else {
            Log.w("VideoHomeFragment", "No local file for video, cannot play after restart: ${videoData.videoUriString}")
        }

        // --- NUEVO BLOQUE: Cargar avatar de la persona asociada al usuario del video ---
        lifecycleScope.launch {
            try {
                // Obtener username desde course_id
                val videoUsername = if (videoData.courseId != null && videoData.courseId!! > 0) {
                    withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.getUsernameFromCourseId(videoData.courseId!!)
                    }
                } else {
                    videoData.username // Fallback por compatibilidad
                }
                
                if (videoUsername != null) {
                    val db = AppDatabase.getDatabase(requireContext())
                    val persona = withContext(Dispatchers.IO) {
                        db.personaDao().getPersonaByUsername(videoUsername)
                    }
                    // Avatar loading is now handled by VideoAdapter
                    Log.d("VideoHomeFragment", "Avatar lookup completed for user: $videoUsername")
                } else {
                    Log.w("VideoHomeFragment", "Could not resolve username for video ${videoData.id}")
                }
            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error loading video uploader avatar", e)
            }
        }
        // --- FIN DEL BLOQUE NUEVO ---
    }
    
    /**
     * Carga los primeros 10 videos desde Supabase (más recientes primero)
     */
    private fun loadVideos(
        targetVideoId: Long = -1L, 
        targetVideoTitle: String? = null, 
        targetVideoUsername: String? = null,
        keepSkeletonIfEmpty: Boolean = false
    ) {
        if (isLoadingVideos) {
            Log.d("VideoHomeFragment", "Already loading videos, skipping")
            return
        }

        isLoadingVideos = true
        lifecycleScope.launch {
            try {
                Log.d("VideoHomeFragment", "Loading initial videos from Supabase (page 0)")

                // If a specific video is requested, try to fetch it first
                var targetVideo: VideoData? = null
                if (targetVideoId != -1L) {
                    targetVideo = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.fetchVideoById(targetVideoId)
                    }
                }

                val result = withContext(Dispatchers.IO) {
                    syncRepository.fetchVideosPaginated(
                        limit = pageSize,
                        offset = 0
                    )
                }
                val videos = result.first
                val total = result.second

                totalVideos = total
                currentPage = 0
                
                withContext(Dispatchers.Main) {
                    videoList.clear()
                    
                    // If we have a target video, add it first
                    if (targetVideo != null) {
                        videoList.add(targetVideo)
                        // Add other videos, excluding the target if it's already in the list
                        val others = videos.filter { it.id != targetVideoId }
                        videoList.addAll(others)
                    } else {
                        videoList.addAll(videos)
                    }
                    
                    // Store all videos for fast local filtering
                    allVideosList.clear()
                    allVideosList.addAll(videoList)
                    
                    if (::videoAdapter.isInitialized) {
                        videoAdapter.updateVideos(videoList)
                        // Scroll to top to show the target video
                        if (targetVideo != null) {
                            view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)?.setCurrentItem(0, false)
                        }
                    }
                    
                    Log.d("VideoHomeFragment", "Loaded ${videoList.size} videos (total: $totalVideos)")
                    isVideosLoaded = true
                    
                    // Stop skeleton animation with fade out
                    if (videoList.isNotEmpty() || !keepSkeletonIfEmpty) {
                        stopSkeletonAnimation()
                    } else {
                        Log.d("VideoHomeFragment", "Keeping skeleton animation (empty list + keepSkeletonIfEmpty=true)")
                        startSkeletonAnimation()
                    }
                }

            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error loading videos", e)
                // On error (e.g. no connection), keep skeleton visible if we have no content
                withContext(Dispatchers.Main) {
                    if (videoList.isEmpty()) {
                        startSkeletonAnimation()
                    } else {
                        stopSkeletonAnimation()
                    }
                }
            } finally {
                isLoadingVideos = false
            }
        }
    }

    private fun startSkeletonAnimation() {
        skeletonContainer.visibility = View.VISIBLE
        skeletonContainer.alpha = 1f
        skeletonContainer.startShimmer()
    }

    private fun stopSkeletonAnimation() {
        // If already gone, nothing to do
        if (skeletonContainer.visibility == View.GONE) {
            skeletonContainer.stopShimmer()
            return
        }

        // Animate alpha to 0 while keeping shimmer running for smoothness
        skeletonContainer.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                skeletonContainer.stopShimmer()
                skeletonContainer.visibility = View.GONE
                skeletonContainer.alpha = 1f // Reset alpha for next usage
            }
            .start()
    }
    
    /**
     * Carga 10 videos más desde Supabase (siguiente página)
     */
    private fun loadMoreVideos() {
        if (isLoadingVideos) {
            Log.d("VideoHomeFragment", "Already loading videos, skipping")
            return
        }

        isLoadingVideos = true
        lifecycleScope.launch {
            try {
                val nextPage = currentPage + 1
                val offset = nextPage * pageSize
                
                Log.d("VideoHomeFragment", "Loading more videos from Supabase (page $nextPage, offset $offset)")

                val result = withContext(Dispatchers.IO) {
                    syncRepository.fetchVideosPaginated(
                        limit = pageSize,
                        offset = offset
                    )
                }
                val videos = result.first

                if (videos.isNotEmpty()) {
                    currentPage = nextPage
                    
                    withContext(Dispatchers.Main) {
                        val oldSize = videoList.size
                        videoList.addAll(videos)
                        
                        // Add to all videos list for filtering
                        allVideosList.addAll(videos)
                        
                        if (::videoAdapter.isInitialized) {
                            videoAdapter.notifyItemRangeInserted(oldSize, videos.size)
                        }
                        
                        Log.d("VideoHomeFragment", "Loaded ${videos.size} more videos (total now: ${videoList.size}/$totalVideos)")
                    }
                } else {
                    Log.d("VideoHomeFragment", "No more videos to load")
                }

            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error loading more videos", e)
            } finally {
                isLoadingVideos = false
            }
        }
    }    override fun onResume() {
        super.onResume()
        registerNetworkCallback()
        // Recargar videos desde Supabase al volver al fragmento
        if (isVideosLoaded) {
            Log.d("VideoHomeFragment", "onResume: Reloading videos from Supabase")
            forceReloadVideos()
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterNetworkCallback()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isVideosLoaded = false
    }

    private fun registerNetworkCallback() {
        try {
            val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Log.d("VideoHomeFragment", "Network available detected")
                    // If we have no videos or we are in a state where we want to retry
                    if (videoList.isEmpty() && !isLoadingVideos) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            // Add delay to ensure network is stable
                            kotlinx.coroutines.delay(1500)
                            Log.d("VideoHomeFragment", "Auto-reloading videos on network available")
                            loadVideos()
                        }
                    }
                }
            }
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback!!)
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error registering network callback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error unregistering network callback", e)
            }
            networkCallback = null
        }
    }

    // Método para forzar la recarga de videos desde Supabase
    private fun forceReloadVideos() {
        isVideosLoaded = false
        currentPage = 0
        totalVideos = 0
        // Show skeleton only if list is cleared (full reload)
        if (videoList.isEmpty()) {
            startSkeletonAnimation()
        }
        loadVideos()
    }

    private fun getCurrentUsername(): String? {
        val sharedPreferences = requireActivity().getSharedPreferences(
            "auth_prefs", Context.MODE_PRIVATE
        )
        return sharedPreferences.getString("current_username", null)
    }

    // Add this missing method if it doesn't exist
    // This method loads the avatar of the session user into profileAvatars
    private fun loadCurrentUserAvatar() {
        // Ensure fragment is added and context is available before proceeding
        if (!isAdded || context == null) {
            Log.w("VideoHomeFragment", "loadCurrentUserAvatar: Fragment not added or context is null.")
            if (::profileAvatars.isInitialized) {
                profileAvatars.setImageResource(R.drawable.ic_profile_avatars)
            }
            return
        }

        try {
            if (!::sessionManager.isInitialized) {
                Log.e("VideoHomeFragment", "SessionManager not initialized in loadCurrentUserAvatar")
                if (::profileAvatars.isInitialized) {
                    profileAvatars.setImageResource(R.drawable.ic_profile_avatars)
                }
                return
            }

            val avatarUriString = sessionManager.getUserAvatar()
            if (!avatarUriString.isNullOrEmpty()) {
                val avatarUri = Uri.parse(avatarUriString)
                if (::profileAvatars.isInitialized) {
                    Glide.with(requireContext())
                        .load(avatarUri)
                        .placeholder(R.drawable.ic_profile_avatars)
                        .error(R.drawable.ic_profile_avatars)
                        .into(profileAvatars)
                    Log.d("VideoHomeFragment", "Current user avatar loaded from session: $avatarUriString")
                } else {
                    Log.e("VideoHomeFragment", "profileAvatars not initialized in loadCurrentUserAvatar")
                }
            } else {
                if (::profileAvatars.isInitialized) {
                    profileAvatars.setImageResource(R.drawable.ic_profile_avatars)
                }
                Log.d("VideoHomeFragment", "Current user avatar not found in session or URI is empty, using default.")
            }
        } catch (e: IllegalArgumentException) {
            Log.e("VideoHomeFragment", "Error parsing avatar URI in loadCurrentUserAvatar: ${e.message}", e)
            if (::profileAvatars.isInitialized) {
                profileAvatars.setImageResource(R.drawable.ic_profile_avatars)
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error in loadCurrentUserAvatar: ${e.message}", e)
            if (::profileAvatars.isInitialized) {
                profileAvatars.setImageResource(R.drawable.ic_profile_avatars)
            }
        }
    }

    private fun loadAvatarIntoViews(persona: Persona) {
        // This method is no longer needed since avatar loading
        // is now handled in VideoAdapter for each video item
        Log.d("VideoHomeFragment", "loadAvatarIntoViews called - avatar handling moved to VideoAdapter")
    }

    // Add this method to handle content URIs
    private fun getFilePathFromUri(uri: Uri): String? {
        try {
            if (uri.scheme == "content") {
                val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val columnIndex = it.getColumnIndexOrThrow("_data")
                        if (columnIndex >= 0) {
                            return it.getString(columnIndex)
                        }
                    }
                }

                // If we couldn't get the path from the cursor, try to copy the file to app's cache
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val fileName = "video_${System.currentTimeMillis()}.mp4"
                    val cacheFile = File(requireContext().cacheDir, fileName)

                    inputStream.use { input ->
                        cacheFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }

                    Log.d("VideoHomeFragment", "Copied content URI to cache: ${cacheFile.absolutePath}")
                    return cacheFile.absolutePath
                }
            } else if (uri.scheme == "file") {
                return uri.path
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error getting file path from URI", e)
        }
        return null
    }

    // Add this method to modify the VideoData to use file paths instead of URIs when possible
    // Update the prepareVideoForPlayback method to better handle file paths
    private fun prepareVideoForPlayback(videoData: VideoData): VideoData {
        if (videoData.videoUriString != null) {
            try {
                val uri = Uri.parse(videoData.videoUriString)

                // If it's already a file URI and the file exists, use it as is
                if (uri.scheme == "file") {
                    val path = uri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            Log.d("VideoHomeFragment", "Using existing file path: $path")
                            return videoData
                        } else {
                            Log.e("VideoHomeFragment", "File does not exist: $path")
                        }
                    }
                }

                // For content URIs, try to get a persistent file path
                val filePath = getFilePathFromUri(uri)

                if (filePath != null) {
                    val file = File(filePath)
                    if (file.exists()) {
                        Log.d("VideoHomeFragment", "Using file path from URI: $filePath")
                        // Create a new VideoData with the file path
                        return VideoData(
                            id = videoData.id,
                            username = videoData.username,
                            description = videoData.description,
                            title = videoData.title,
                            videoUriString = "file://$filePath",
                            timestamp = videoData.timestamp
                        )
                    } else {
                        Log.e("VideoHomeFragment", "File does not exist after conversion: $filePath")
                    }
                } else {
                    Log.e("VideoHomeFragment", "Could not get file path from URI: ${videoData.videoUriString}")
                }
            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error preparing video for playback", e)
            }
        }
        return videoData
    }

    // Add this method to check if current user is admin and invoke callback with result
    private fun checkAdminStatus(callback: (Boolean) -> Unit) {
        val username = sessionManager.getUsername()
        if (username == null) {
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
                Log.d("VideoHomeFragment", "User $username is admin: $isAdmin (role: ${usuarioWithRole?.rolNombre})")
                callback(isAdmin)
            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error checking admin status", e)
                callback(false)
            }
        }
    }

    // Add this method to safely navigate to the profile fragment
    private fun navigateToProfileSafely() {
        try {
            // Try to navigate directly to the destination ID
            findNavController().navigate(R.id.profileFragment)
            Log.d("VideoHomeFragment", "Navigated to profile fragment successfully")
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error navigating to profile fragment: ${e.message}")
            // Show a toast to inform the user
            Toast.makeText(context, "No se pudo navegar al perfil", Toast.LENGTH_SHORT).show()
        }    }

    private fun navigateToVideoIndex(index: Int) {
        try {
            if (index < 0 || index >= videoList.size) {
                Log.e("VideoHomeFragment", "❌ Invalid video index: $index (list size: ${videoList.size})")
                return
            }
            
            val videoData = videoList[index]
            Log.d("VideoHomeFragment", "🎯 Navigating to video at index $index:")
            Log.d("VideoHomeFragment", "  - Title: ${videoData.title}")
            Log.d("VideoHomeFragment", "  - ID: ${videoData.id}")
            Log.d("VideoHomeFragment", "  - Username: ${videoData.username}")
            
            val viewPager = view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)
            if (viewPager != null) {
                viewPager.setCurrentItem(index, false) // false para navegación inmediata sin animación
                currentVideoIndex = index
                Log.d("VideoHomeFragment", "✅ ViewPager updated to position $index")
                
                // Forzar actualización del video
                viewPager.post {
                    val recyclerView = viewPager.getChildAt(0) as? RecyclerView
                    val viewHolder = recyclerView?.findViewHolderForAdapterPosition(index) as? VideoAdapter.VideoViewHolder
                    viewHolder?.playVideo()
                    viewHolder?.setMuteState(isMuted)
                }
            } else {
                Log.e("VideoHomeFragment", "❌ ViewPager is null, cannot navigate to video")
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "❌ Error navigating to video index $index: ${e.message}", e)
        }
    }
    
    private fun setupSearchBar(view: View) {
        val toggleSearchButton = view.findViewById<ImageButton>(R.id.toggleSearchButton)
        val topNavTabs = view.findViewById<ViewGroup>(R.id.topNavTabs)
        val searchBarContainer = view.findViewById<BlurView>(R.id.searchBarContainer)
        val searchEditText = view.findViewById<EditText>(R.id.searchEditText)
        // searchButton removed as per design
        val closeSearchButton = view.findViewById<ImageButton>(R.id.closeSearchButton)
        val filterChipGroup = view.findViewById<ChipGroup>(R.id.searchFilterChipGroup)
        
        // Active Filter Indicator Views
        val activeFilterIndicator = view.findViewById<LinearLayout>(R.id.activeFilterIndicator)
        val activeFilterText = view.findViewById<TextView>(R.id.activeFilterText)
        val clearActiveFilterButton = view.findViewById<ImageButton>(R.id.clearActiveFilterButton)
        val centerPillContainer = view.findViewById<View>(R.id.centerPillContainer)

        // Setup BlurView
        val radius = 20f
        val decorView = requireActivity().window.decorView
        // Use the fragment's root view (ConstraintLayout) as the blur source since it contains the video background
        val rootView = view as ViewGroup
        val windowBackground = decorView.background

        searchBarContainer.setupWith(rootView, RenderScriptBlur(requireContext()))
            //.setFrameClearDrawable(windowBackground) // Removed to allow video to show through
            .setBlurRadius(radius)
            .setBlurAutoUpdate(true)
            .setOverlayColor(Color.parseColor("#33000000")) // Match ExploreFragment overlay
        
        searchBarContainer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val cornerRadius = view.context.resources.displayMetrics.density * 16 // 16dp
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        searchBarContainer.clipToOutline = true
        
        // Add TextWatcher for instant search (like ExploreFragment)
        searchEditText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                filterVideos(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // Toggle search bar visibility
        toggleSearchButton?.setOnClickListener {
            // Animate the transition with a fluid custom transition
            val container = view.findViewById<ViewGroup>(R.id.topNavContainer)
            val transition = androidx.transition.TransitionSet()
                .addTransition(androidx.transition.ChangeBounds())
                .addTransition(androidx.transition.Fade())
                .addTransition(androidx.transition.ChangeTransform())
                .setDuration(400)
                .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())
            
            androidx.transition.TransitionManager.beginDelayedTransition(container, transition)
            
            if (searchBarContainer.visibility == View.GONE) {
                // OPEN SEARCH
                // Hide active filter indicator while searching (full bar takes over)
                activeFilterIndicator.visibility = View.GONE
                centerPillContainer.visibility = View.GONE // Hide pills to avoid clutter
                
                searchBarContainer.visibility = View.VISIBLE
                searchEditText.requestFocus()
                showKeyboard(searchEditText)
            } else {
                // CLOSE/MINIMIZE SEARCH
                searchBarContainer.visibility = View.GONE
                centerPillContainer.visibility = View.VISIBLE
                hideKeyboard(searchEditText)
                
                // If there is active search text, show the minimalist indicator
                if (isSearchMode && currentSearchQuery.isNotEmpty()) {
                    activeFilterIndicator.visibility = View.VISIBLE
                    activeFilterText.text = "$currentSearchQuery"
                } else {
                    // No search active, ensure indicator is gone and reset
                    activeFilterIndicator.visibility = View.GONE
                    if (isSearchMode) {
                        clearSearch()
                    }
                }
            }
        }
        
        // Close search button
        closeSearchButton?.setOnClickListener {
            // Si hay texto, solo limpiarlo (eliminar filtro)
            if (searchEditText.text.isNotEmpty()) {
                searchEditText.setText("")
                return@setOnClickListener
            }

            // Si no hay texto, cerrar la barra de búsqueda
            // Animate the transition
            val container = view.findViewById<ViewGroup>(R.id.topNavContainer)
            val transition = androidx.transition.TransitionSet()
                .addTransition(androidx.transition.ChangeBounds())
                .addTransition(androidx.transition.Fade())
                .addTransition(androidx.transition.ChangeTransform())
                .setDuration(300)
                .setInterpolator(androidx.interpolator.view.animation.FastOutSlowInInterpolator())

            androidx.transition.TransitionManager.beginDelayedTransition(container, transition)

            // Ocultar barra de búsqueda y mostrar botones normales
            searchBarContainer.visibility = View.GONE
            centerPillContainer.visibility = View.VISIBLE
            hideKeyboard(searchEditText)
            
            // Ensure indicator is gone (since we cleared or it was empty)
            activeFilterIndicator.visibility = View.GONE
            if (isSearchMode) {
                clearSearch()
            }
        }
        
        // Clear Active Filter Button (The 'X' on the minimalist indicator)
        clearActiveFilterButton?.setOnClickListener {
            // Animate removal
            val container = view.findViewById<ViewGroup>(R.id.topNavContainer)
            val transition = androidx.transition.TransitionSet()
                .addTransition(androidx.transition.Fade())
                .addTransition(androidx.transition.ChangeBounds())
                .setDuration(300)
            androidx.transition.TransitionManager.beginDelayedTransition(container, transition)
            
            activeFilterIndicator.visibility = View.GONE
            searchEditText.setText("") // Clear text
            clearSearch() // Reset videos
        }
        
        // Search on Enter key
        searchEditText?.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard(searchEditText)
                // Minimize search bar (persist filter)
                toggleSearchButton.performClick()
                true
            } else {
                false
            }
        }
        
        // Filter chip selection
        filterChipGroup?.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isEmpty()) {
                currentSearchType = "all"
                return@setOnCheckedStateChangeListener
            }
            
            val checkedChip = view.findViewById<Chip>(checkedIds[0])
            currentSearchType = when (checkedChip?.id) {
                R.id.filterTitleChip -> "title"
                R.id.filterUsernameChip -> "username"
                R.id.filterCategoryChip -> "category"
                else -> "all"
            }
            
            // Re-filter with new type if in search mode
            if (isSearchMode && currentSearchQuery.isNotEmpty()) {
                filterVideos(currentSearchQuery)
            }
        }
    }
    
    // Fast local filtering with remote fallback (like ExploreFragment)
    private fun filterVideos(query: String) {
        if (query.isBlank()) {
            // When search is empty, reload videos from Supabase to ensure fresh state
            isSearchMode = false
            currentSearchQuery = ""
            Log.d("VideoHomeFragment", "filterVideos -> query empty, reloading videos from Supabase")
            forceReloadVideos()
            return
        }

        isSearchMode = true
        currentSearchQuery = query
        val lowerQuery = query.lowercase()
        val usernameQuery = if (query.startsWith("@")) query.removePrefix("@").trim() else null
        
        // 1. Instant local filter for immediate feedback
        // Filter AND Sort by relevance: Exact > StartsWith > Contains
        val filtered = when (currentSearchType) {
            "title" -> allVideosList.filter { 
                it.title?.contains(query, ignoreCase = true) == true
            }
            "username" -> allVideosList.filter {
                it.username?.contains(query.removePrefix("@"), ignoreCase = true) == true
            }
            "category" -> allVideosList.filter { 
                it.description?.contains(query, ignoreCase = true) == true
            }
            else -> allVideosList.filter { video ->
                video.title?.contains(query, ignoreCase = true) == true ||
                video.description?.contains(query, ignoreCase = true) == true
            }
        }.sortedWith(compareBy<VideoData> { video ->
            val title = video.title?.lowercase() ?: ""
            when {
                title == lowerQuery -> 0 // Exact match first
                title.startsWith(lowerQuery) -> 1 // Starts with second
                title.contains(lowerQuery) -> 2 // Contains third
                else -> 3 // Description match last
            }
        }.thenByDescending { it.id }) // Then by newest
        
        videoList.clear()
        videoList.addAll(filtered)
        // Use updateVideos to ensure adapter refreshes correctly with a new list reference
        if (::videoAdapter.isInitialized) {
            videoAdapter.updateVideos(videoList.toList())
        }
        view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)?.currentItem = 0
        
        // 2. Debounced remote search (Supabase)
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(500) // 500ms debounce
            searchRemoteVideos(query)
        }
    }
    
    // Remote search for additional results
    private fun searchRemoteVideos(query: String) {
        // Note: This is called from a coroutine scope already
        try {
            Log.d("VideoHomeFragment", "Searching remote videos: query='$query', type='$currentSearchType'")

            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    // Decide remote strategy: if explicit username filter OR query looks like a username
                    val explicitUsername = currentSearchType == "username"
                    val looksLikeUsername = query.startsWith("@") || (!query.contains(" ") && query.length <= 40)

                    val usernameTerm = query.removePrefix("@").trim()

                    val resultsByUsername: List<com.example.tareamov.data.entity.VideoData> = if (explicitUsername || looksLikeUsername) {
                        try {
                            syncRepository.fetchVideosByUsernameFromSupabase(usernameTerm)
                        } catch (e: Exception) {
                            Log.w("VideoHomeFragment", "SyncRepository fetch by username failed, falling back to SupabaseClient: ${e.message}")
                            try {
                                com.example.tareamov.service.SupabaseClient.fetchVideosByUsername(usernameTerm)
                            } catch (e2: Exception) {
                                Log.e("VideoHomeFragment", "SupabaseClient.fetchVideosByUsername failed", e2)
                                emptyList()
                            }
                        }
                    } else {
                        emptyList()
                    }

                    val resultsFallback: List<com.example.tareamov.data.entity.VideoData> = try {
                        syncRepository.searchVideos(query, currentSearchType, 50)
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Remote search via SyncRepository failed", e)
                        emptyList()
                    }

                    // Merge results: username results first, then other results, deduplicated by id
                    val merged = LinkedHashMap<Long, com.example.tareamov.data.entity.VideoData>()
                    for (v in resultsByUsername) merged[v.id] = v
                    for (v in resultsFallback) merged.putIfAbsent(v.id, v)
                    val results = merged.values.toList()

                    withContext(Dispatchers.Main) {
                        // Only update if the query hasn't changed since we started
                        if (currentSearchQuery == query) {
                            if (results.isNotEmpty()) {
                                // Update with authoritative remote results
                                videoList.clear()
                                videoList.addAll(results)
                                // Use updateVideos to ensure adapter refreshes correctly with a new list reference
                                if (::videoAdapter.isInitialized) {
                                    videoAdapter.updateVideos(videoList.toList())
                                }
                                view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)?.currentItem = 0
                                Log.d("VideoHomeFragment", "Updated with ${results.size} remote results")
                            } else {
                                Log.d("VideoHomeFragment", "No remote results found for query='$query' type='$currentSearchType'")
                                // If no remote results and local list is empty, clear adapter
                                if (videoList.isEmpty() && ::videoAdapter.isInitialized) {
                                    videoAdapter.updateVideos(emptyList())
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VideoHomeFragment", "Error in searchRemoteVideos coroutine", e)
                }
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error initiating remote search", e)
        }
    }
    
    private fun performSearch(query: String) {
        // This method is kept for compatibility but now just calls filterVideos
        filterVideos(query)
    }
    
    private fun clearSearch() {
        isSearchMode = false
        currentSearchQuery = ""
        currentSearchType = "all"
        
        view?.findViewById<EditText>(R.id.searchEditText)?.setText("")
        view?.findViewById<Chip>(R.id.filterAllChip)?.isChecked = true
        
        Log.d("VideoHomeFragment", "clearSearch -> reloading videos from Supabase")
        forceReloadVideos()
    }
    
    private fun showKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }
    
    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }
    
    /**
     * Comparte un video usando la URL pública de Cloudflare R2 si está disponible
     * Esta función puede ser llamada desde cualquier parte del fragmento
     */
    private fun shareVideoFromFragment(videoData: VideoData) {
        try {
            val context = requireContext()
            
            // ESTRATEGIA 1: Intentar obtener URL pública de Cloudflare R2
            val r2PublicUrl = com.example.tareamov.service.CloudflareR2Service.getVideoStreamUrl(videoData.videoUriString)
            
            if (r2PublicUrl != null && (r2PublicUrl.startsWith("http://") || r2PublicUrl.startsWith("https://"))) {
                // Compartir usando URL pública - MÉTODO PREFERIDO
                Log.d("VideoHomeFragment", "📤 Compartiendo video usando URL pública de R2: $r2PublicUrl")
                
                // Copiar URL al portapapeles
                try {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    val clip = android.content.ClipData.newPlainText("URL del video", r2PublicUrl)
                    clipboard.setPrimaryClip(clip)
                    Log.d("VideoHomeFragment", "📋 URL copiada al portapapeles")
                } catch (e: Exception) {
                    Log.w("VideoHomeFragment", "⚠️ No se pudo copiar al portapapeles: ${e.message}")
                }
                
                val shareText = buildString {
                    appendLine("🎥 ${videoData.title}")
                    appendLine()
                    if (!videoData.description.isNullOrEmpty()) {
                        appendLine(videoData.description)
                        appendLine()
                    }
                    appendLine("👤 Por: ${videoData.username ?: "Anónimo"}")
                    appendLine()
                    appendLine("🔗 Ver video:")
                    appendLine(r2PublicUrl)
                    appendLine()
                    appendLine("📱 Compartido desde TareaMov")
                }
                
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    putExtra(Intent.EXTRA_SUBJECT, "🎥 ${videoData.title}")
                    type = "text/plain"
                }
                
                val shareIntent = Intent.createChooser(sendIntent, "Compartir video vía")
                startActivity(shareIntent)
                
                Toast.makeText(
                    context,
                    "✅ Enlace listo para compartir\n📋 URL copiada al portapapeles",
                    Toast.LENGTH_SHORT
                ).show()
                
                Log.d("VideoHomeFragment", "✅ Video compartido con URL pública")
                return
            }
            
            // ESTRATEGIA 2: Compartir información del video si no hay URL pública
            Log.d("VideoHomeFragment", "⚠️ No hay URL pública disponible, compartiendo información")
            
            val shareText = buildString {
                appendLine("🎥 ${videoData.title}")
                appendLine()
                if (!videoData.description.isNullOrEmpty()) {
                    appendLine(videoData.description)
                    appendLine()
                }
                appendLine("👤 Por: ${videoData.username ?: "Anónimo"}")
                appendLine()
                appendLine("📱 Video de TareaMov")
                appendLine()
                appendLine("⚠️ Para ver el video completo, descarga la app TareaMov")
            }
            
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, shareText)
                putExtra(Intent.EXTRA_SUBJECT, videoData.title)
                type = "text/plain"
            }
            
            val shareIntent = Intent.createChooser(sendIntent, "Compartir información del video")
            startActivity(shareIntent)
            
            Toast.makeText(
                context,
                "ℹ️ Se compartió la información del video\n⚠️ El video no está disponible públicamente",
                Toast.LENGTH_LONG
            ).show()
            
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "❌ Error compartiendo video: ${e.message}", e)
            Toast.makeText(context, "Error al compartir video: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Show comments dialog for a video
     */
    private fun showCommentsDialog(videoData: com.example.tareamov.data.entity.VideoData) {
        val context = context ?: return
        
        // Use BottomSheetDialog for Instagram/TikTok style from bottom
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(context, R.style.Theme_TareaMov_BottomSheet)
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_comments, null)
        bottomSheetDialog.setContentView(dialogView)
        
        // Configure bottom sheet behavior
        val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isDraggable = true
            // Set peek height to 60% of screen height
            behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
            it.setBackgroundResource(android.R.color.transparent)
        }
        
        val commentsRecyclerView = dialogView.findViewById<RecyclerView>(R.id.commentsRecyclerView)
        val commentInput = dialogView.findViewById<EditText>(R.id.commentInput)
        val sendButton = dialogView.findViewById<ImageButton>(R.id.sendCommentButton)
        val closeButton = dialogView.findViewById<ImageButton>(R.id.closeCommentsButton)
        val titleText = dialogView.findViewById<TextView>(R.id.commentsTitleText)
        val emptyText = dialogView.findViewById<View>(R.id.emptyCommentsText)
        val skeletonContainer = dialogView.findViewById<LinearLayout>(R.id.skeletonContainer)
        val currentUserAvatar = dialogView.findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.currentUserAvatar)
        
        // Replying UI elements
        val replyingToBanner = dialogView.findViewById<LinearLayout>(R.id.replyingToBanner)
        val replyingToText = dialogView.findViewById<TextView>(R.id.replyingToText)
        val cancelReplyButton = dialogView.findViewById<ImageView>(R.id.cancelReplyButton)
        
        // State for replying
        var replyingToUsername: String? = null
        var replyingToCommentId: Long? = null
        
        titleText?.text = "Comentarios"
        
        // Cancel reply logic
        cancelReplyButton?.setOnClickListener {
            replyingToUsername = null
            replyingToCommentId = null
            replyingToBanner?.visibility = View.GONE
            commentInput?.hint = "Agrega un comentario..."
        }
        
        // Setup RecyclerView
        val commentsAdapter = CommentsAdapter(
            onReplyClick = { comment ->
                // Handle reply logic:
                // If the comment is already a reply (has parentId), we reply to the parent (flattened structure).
                // If it's a top-level comment, we reply to it directly.
                val targetParentId = if (comment.parentId != null && comment.parentId != 0L) {
                    comment.parentId
                } else {
                    comment.id
                }
                
                replyingToCommentId = targetParentId
                
                // Fetch username asynchronously
                lifecycleScope.launch {
                    val db = AppDatabase.getDatabase(context)
                    val user = db.usuarioDao().getUsuarioById(comment.usuarioId)
                    val username = user?.usuario ?: "Usuario"
                    
                    replyingToUsername = username
                    replyingToBanner?.visibility = View.VISIBLE
                    replyingToText?.text = "Respondiendo a $username"
                    
                    commentInput?.hint = "Responder a $username..."
                    commentInput?.requestFocus()
                    showKeyboard(commentInput!!)
                }
            },
            onLikeClick = { comment ->
                lifecycleScope.launch {
                    val userId = getCurrentUserId()
                    if (userId > 0) {
                        // Optimistic UI update already happened in Adapter
                        // Now sync with backend
                        syncRepository.likeVideoComment(comment.id, comment.videoId, userId, comment.usuarioId)
                    } else {
                        Toast.makeText(context, "Debes iniciar sesión", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onProfileClick = { username ->
                // Dismiss dialog and navigate to user profile
                bottomSheetDialog.dismiss()
                val bundle = Bundle().apply {
                    putString("username", username)
                }
                findNavController().navigate(R.id.userProfileViewFragment, bundle)
            }
        )
        commentsRecyclerView?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
        commentsRecyclerView?.adapter = commentsAdapter
        
        // Initial State: Show Skeleton, Hide List/Empty
        skeletonContainer?.visibility = View.VISIBLE
        commentsRecyclerView?.visibility = View.GONE
        emptyText?.visibility = View.GONE

        // Start pulse animation on skeleton for professional look
        val pulseAnimation = android.view.animation.AlphaAnimation(0.4f, 1.0f).apply {
            duration = 800
            repeatMode = android.view.animation.Animation.REVERSE
            repeatCount = android.view.animation.Animation.INFINITE
        }
        skeletonContainer?.startAnimation(pulseAnimation)

        // Load current user avatar
        lifecycleScope.launch {
            val userId = getCurrentUserId()
            if (userId > 0) {
                val user = syncRepository.getUsuarioByIdLocal(userId)
                if (user != null && !user.avatar.isNullOrEmpty()) {
                    com.bumptech.glide.Glide.with(context)
                        .load(user.avatar)
                        .placeholder(R.drawable.ic_profile)
                        .into(currentUserAvatar!!)
                }
            }
        }

        // Show/Hide send button based on input
        commentInput?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                sendButton?.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        
        // Load comments with delay to show skeleton animation
        lifecycleScope.launch {
            try {
                // Simulate network delay for skeleton effect (remove in production if not needed)
                kotlinx.coroutines.delay(1000) 
                
                val comments = syncRepository.getVideoComments(videoData.id)
                
                skeletonContainer?.clearAnimation()
                skeletonContainer?.visibility = View.GONE
                
                if (comments.isEmpty()) {
                    emptyText?.visibility = View.VISIBLE
                    commentsRecyclerView?.visibility = View.GONE
                } else {
                    emptyText?.visibility = View.GONE
                    commentsRecyclerView?.visibility = View.VISIBLE
                    commentsAdapter.submitList(comments)
                }
            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error loading comments", e)
                skeletonContainer?.visibility = View.GONE
                emptyText?.visibility = View.VISIBLE
            }
        }
        
        // Send comment
        sendButton?.setOnClickListener {
            val commentText = commentInput?.text?.toString()?.trim()
            if (!commentText.isNullOrEmpty()) {
                lifecycleScope.launch {
                    try {
                        val userId = getCurrentUserId()
                        if (userId > 0) {
                            // Logic to handle reply vs top-level comment
                            // If replyingToUsername is set, we might want to prepend @username or handle parentId if DB supports it
                            // For now, prepending @username if not present is a good visual fallback
                            val finalCommentText = if (replyingToUsername != null && !commentText.startsWith("@$replyingToUsername")) {
                                "@$replyingToUsername $commentText"
                            } else {
                                commentText
                            }
                            
                            val commentId = syncRepository.addVideoComment(videoData.id, userId, finalCommentText, replyingToCommentId)
                            if (commentId != null) {
                                commentInput.setText("")
                                // Reset reply state
                                replyingToUsername = null
                                replyingToCommentId = null
                                replyingToBanner?.visibility = View.GONE
                                commentInput.hint = "Agrega un comentario..."
                                
                                // Reload comments
                                val comments = syncRepository.getVideoComments(videoData.id)
                                emptyText?.visibility = View.GONE
                                commentsRecyclerView?.visibility = View.VISIBLE
                                commentsAdapter.submitList(comments)
                                Toast.makeText(context, "Comentario agregado", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Debes iniciar sesión para comentar", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error adding comment", e)
                        Toast.makeText(context, "Error al agregar comentario", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        closeButton?.setOnClickListener {
            bottomSheetDialog.dismiss()
        }
        
        bottomSheetDialog.show()
    }
    
    /**
     * Simple adapter for comments
     */
    inner class CommentsAdapter(
        private val onReplyClick: (com.example.tareamov.data.entity.VideoComment) -> Unit,
        private val onLikeClick: (com.example.tareamov.data.entity.VideoComment) -> Unit,
        private val onProfileClick: (String) -> Unit // New callback for profile navigation
    ) : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {
        private var allComments: List<com.example.tareamov.data.entity.VideoComment> = emptyList()
        private var topLevelComments: List<com.example.tareamov.data.entity.VideoComment> = emptyList()
        private var repliesMap: Map<Long, List<com.example.tareamov.data.entity.VideoComment>> = emptyMap()
        
        // Map to track local like state: commentId -> isLiked
        private val likedComments = mutableMapOf<Long, Boolean>()
        // Map to track local like count: commentId -> count
        private val likeCounts = mutableMapOf<Long, Int>()
        // Map to track expanded replies state
        private val expandedReplies = mutableMapOf<Long, Boolean>()
        
        fun submitList(newComments: List<com.example.tareamov.data.entity.VideoComment>) {
            allComments = newComments
            // Separate top-level comments (parentId is null or 0) from replies
            topLevelComments = allComments.filter { it.parentId == null || it.parentId == 0L }
            repliesMap = allComments.filter { it.parentId != null && it.parentId != 0L }
                .groupBy { it.parentId!! }
            
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_comment, parent, false)
            return CommentViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
            val comment = topLevelComments[position]
            val replies = repliesMap[comment.id] ?: emptyList()
            holder.bind(comment, replies)
        }
        
        override fun getItemCount() = topLevelComments.size
        
        inner class CommentViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val usernameText: TextView = itemView.findViewById(R.id.commentUsername)
            private val commentText: TextView = itemView.findViewById(R.id.commentText)
            private val timestampText: TextView = itemView.findViewById(R.id.commentTimestamp)
            private val avatar: de.hdodenhof.circleimageview.CircleImageView = itemView.findViewById(R.id.commentAvatar)
            private val likeCount: TextView = itemView.findViewById(R.id.commentLikeCount)
            private val replyButton: TextView = itemView.findViewById(R.id.replyButton)
            private val likeIcon: ImageView = itemView.findViewById(R.id.commentLikeIcon)
            
            // New UI elements for replies
            private val repliesSection: LinearLayout = itemView.findViewById(R.id.repliesSection)
            private val viewRepliesContainer: LinearLayout = itemView.findViewById(R.id.viewRepliesContainer)
            private val viewRepliesText: TextView = itemView.findViewById(R.id.viewRepliesText)
            private val repliesContainer: LinearLayout = itemView.findViewById(R.id.repliesContainer)
            
            fun bind(comment: com.example.tareamov.data.entity.VideoComment, replies: List<com.example.tareamov.data.entity.VideoComment>) {
                commentText.text = comment.comment
                // Simple timestamp formatting
                timestampText.text = "Hace un momento" // Placeholder, ideally parse createdAt
                
                // Initialize local state if not present
                if (!likeCounts.containsKey(comment.id)) {
                    // Fetch real count asynchronously
                    likeCounts[comment.id] = 0 // Placeholder
                    lifecycleScope.launch {
                        val count = syncRepository.getCommentLikeCount(comment.id)
                        likeCounts[comment.id] = count
                        // Only update if visible/bound
                        if (comment.id == topLevelComments.getOrNull(adapterPosition)?.id) {
                            likeCount.text = count.toString()
                        }
                    }
                }
                
                if (!likedComments.containsKey(comment.id)) {
                    likedComments[comment.id] = false
                    lifecycleScope.launch {
                        val userId = getCurrentUserId()
                        if (userId > 0) {
                            val liked = syncRepository.hasUserLikedComment(comment.id, userId)
                            likedComments[comment.id] = liked
                            // Only update if visible
                             if (comment.id == topLevelComments.getOrNull(adapterPosition)?.id) {
                                if (liked) {
                                    likeIcon.setColorFilter(android.graphics.Color.RED)
                                    likeIcon.setImageResource(R.drawable.ic_heart_filled)
                                } else {
                                    likeIcon.setColorFilter(android.graphics.Color.parseColor("#888888"))
                                    likeIcon.setImageResource(R.drawable.ic_heart_outline)
                                }
                            }
                        }
                    }
                }
                
                val currentCount = likeCounts[comment.id] ?: 0
                val isLiked = likedComments[comment.id] ?: false
                
                likeCount.text = currentCount.toString()
                
                if (isLiked) {
                    likeIcon.setColorFilter(android.graphics.Color.RED)
                    likeIcon.setImageResource(R.drawable.ic_heart_filled)
                } else {
                    likeIcon.setColorFilter(android.graphics.Color.parseColor("#888888"))
                    likeIcon.setImageResource(R.drawable.ic_heart_outline)
                }
                
                // Load username and avatar
                lifecycleScope.launch {
                    try {
                        val db = AppDatabase.getDatabase(itemView.context)
                        val user = db.usuarioDao().getUsuarioById(comment.usuarioId)
                        val username = user?.usuario ?: "Usuario"
                        usernameText.text = username
                        
                        // Setup reply click
                        replyButton.setOnClickListener {
                            onReplyClick(comment)
                        }

                        // Setup profile navigation on username click
                        usernameText.setOnClickListener {
                            onProfileClick(username)
                        }
                        
                        if (user != null && !user.avatar.isNullOrEmpty()) {
                             com.bumptech.glide.Glide.with(itemView.context)
                                .load(user.avatar)
                                .placeholder(R.drawable.ic_profile)
                                .into(avatar)
                        } else {
                            avatar.setImageResource(R.drawable.ic_profile)
                        }

                        // Setup profile navigation on avatar click
                        avatar.setOnClickListener {
                            onProfileClick(username)
                        }

                    } catch (e: Exception) {
                        usernameText.text = "Usuario"
                        avatar.setImageResource(R.drawable.ic_profile)
                        replyButton.setOnClickListener { onReplyClick(comment) }
                    }
                }
                
                // Handle Like Click directly on the icon or find parent safely
                likeIcon.setOnClickListener {
                    val newLikedState = !isLiked
                    likedComments[comment.id] = newLikedState
                    
                    val newCount = if (newLikedState) currentCount + 1 else maxOf(0, currentCount - 1)
                    likeCounts[comment.id] = newCount
                    
                    // Update UI immediately
                    likeCount.text = newCount.toString()
                    if (newLikedState) {
                        likeIcon.setColorFilter(android.graphics.Color.RED)
                        likeIcon.setImageResource(R.drawable.ic_heart_filled)
                    } else {
                        likeIcon.setColorFilter(android.graphics.Color.parseColor("#888888"))
                        likeIcon.setImageResource(R.drawable.ic_heart_outline)
                    }
                    
                    // Notify parent
                    if (newLikedState) {
                        onLikeClick(comment)
                    }
                }
                
                // --- Replies Logic ---
                repliesContainer.removeAllViews() // Clear previous views
                
                if (replies.isNotEmpty()) {
                    repliesSection.visibility = View.VISIBLE
                    
                    val isExpanded = expandedReplies[comment.id] ?: false
                    
                    if (isExpanded) {
                        viewRepliesText.text = "Ocultar respuestas"
                        repliesContainer.visibility = View.VISIBLE
                        addRealReplies(replies)
                    } else {
                        viewRepliesText.text = "Ver ${replies.size} respuestas más"
                        repliesContainer.visibility = View.GONE
                    }
                    
                    viewRepliesContainer.setOnClickListener {
                        val newState = !isExpanded
                        expandedReplies[comment.id] = newState
                        // Refresh just this item to update view
                        notifyItemChanged(adapterPosition)
                    }
                } else {
                    repliesSection.visibility = View.GONE
                }
            }

            private fun addRealReplies(replies: List<com.example.tareamov.data.entity.VideoComment>) {
                val context = itemView.context
                val inflater = LayoutInflater.from(context)
                
                for (reply in replies) {
                    val replyView = inflater.inflate(R.layout.item_comment, repliesContainer, false)
                    
                    // Adjust padding/layout for nested reply to look like the image (indentation)
                    // But since we are adding it to a container that is already indented by layout_marginStart in XML, 
                    // we might need to adjust or just let it be.
                    // The 'repliesContainer' is inside a vertical LinearLayout which is inside the main LinearLayout.
                    // Let's check item_comment.xml again. 
                    // repliesSection is inside the main text container (layout_weight=1).
                    // So it is already indented relative to the main avatar.
                    
                    // However, we want the reply to look like a full comment row but smaller or indented?
                    // The provided image shows replies aligned with the text of the parent.
                    // Since 'repliesContainer' is inside the text column, it aligns with text.
                    // But 'item_comment' has its own avatar and padding.
                    // We might need to reduce padding for nested items or scale down avatar.
                    
                    val avatar = replyView.findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.commentAvatar)
                    val usernameText = replyView.findViewById<TextView>(R.id.commentUsername)
                    val commentText = replyView.findViewById<TextView>(R.id.commentText)
                    val timestampText = replyView.findViewById<TextView>(R.id.commentTimestamp)
                    val likeCount = replyView.findViewById<TextView>(R.id.commentLikeCount)
                    val replyButton = replyView.findViewById<TextView>(R.id.replyButton)
                    val likeIcon = replyView.findViewById<ImageView>(R.id.commentLikeIcon)
                    val nestedRepliesSection = replyView.findViewById<View>(R.id.repliesSection) // Should be hidden
                    
                    // Hide nested replies section for replies (1 level depth only)
                    nestedRepliesSection.visibility = View.GONE
                    
                    // Scale down avatar for replies
                    val params = avatar.layoutParams
                    params.width = (30 * context.resources.displayMetrics.density).toInt()
                    params.height = (30 * context.resources.displayMetrics.density).toInt()
                    avatar.layoutParams = params
                    
                    // Bind data
                    commentText.text = reply.comment
                    timestampText.text = "Hace un momento"
                    
                    // Local state for reply likes
                     if (!likeCounts.containsKey(reply.id)) {
                        likeCounts[reply.id] = 0
                    }
                    if (!likedComments.containsKey(reply.id)) {
                        likedComments[reply.id] = false
                    }
                    
                    val currentCount = likeCounts[reply.id] ?: 0
                    val isLiked = likedComments[reply.id] ?: false
                    
                    likeCount.text = currentCount.toString()
                    
                    if (isLiked) {
                        likeIcon.setColorFilter(android.graphics.Color.RED)
                        likeIcon.setImageResource(R.drawable.ic_heart_filled)
                    } else {
                        likeIcon.setColorFilter(android.graphics.Color.parseColor("#888888"))
                        likeIcon.setImageResource(R.drawable.ic_heart_outline)
                    }
                    
                    // Async load user
                    lifecycleScope.launch {
                        try {
                            val db = AppDatabase.getDatabase(context)
                            val user = db.usuarioDao().getUsuarioById(reply.usuarioId)
                            val username = user?.usuario ?: "Usuario"
                            usernameText.text = username
                            
                             if (user != null && !user.avatar.isNullOrEmpty()) {
                                 com.bumptech.glide.Glide.with(context)
                                    .load(user.avatar)
                                    .placeholder(R.drawable.ic_profile)
                                    .into(avatar)
                            } else {
                                avatar.setImageResource(R.drawable.ic_profile)
                            }
                            
                            // Navigations
                            usernameText.setOnClickListener { onProfileClick(username) }
                            avatar.setOnClickListener { onProfileClick(username) }
                            
                        } catch (e: Exception) {
                            usernameText.text = "Usuario"
                        }
                    }
                    
                    // Actions
                    replyButton.setOnClickListener { onReplyClick(reply) }
                    
                    likeIcon.setOnClickListener {
                        val newLikedState = !isLiked
                        likedComments[reply.id] = newLikedState
                        val newCount = if (newLikedState) currentCount + 1 else maxOf(0, currentCount - 1)
                        likeCounts[reply.id] = newCount
                        
                        likeCount.text = newCount.toString()
                         if (newLikedState) {
                            likeIcon.setColorFilter(android.graphics.Color.RED)
                            likeIcon.setImageResource(R.drawable.ic_heart_filled)
                        } else {
                            likeIcon.setColorFilter(android.graphics.Color.parseColor("#888888"))
                            likeIcon.setImageResource(R.drawable.ic_heart_outline)
                        }
                        onLikeClick(reply)
                    }
                    
                    repliesContainer.addView(replyView)
                }
            }
            
            // Function ready for when we have real reply data
            // private fun addRealReplies(replies: List<com.example.tareamov.data.entity.VideoComment>) {
            //    // Method removed to avoid duplicate definition
            // }
        }
    }
}