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
import android.graphics.drawable.GradientDrawable
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
import com.example.tareamov.ui.showPaymentOptions // Import payment extension

import androidx.lifecycle.ViewModelProvider
import com.example.tareamov.viewmodel.VideoHomeViewModel
import com.example.tareamov.MainActivity
import androidx.media3.common.util.UnstableApi

@UnstableApi
class VideoHomeFragment : Fragment() {
    private lateinit var viewModel: VideoHomeViewModel
    private lateinit var profileAvatars: CircleImageView
    private lateinit var videoManager: VideoManager
    private lateinit var sessionManager: SessionManager // Add SessionManager instance
    private lateinit var skeletonContainer: ShimmerFrameLayout // Skeleton container
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private lateinit var homeIconImageView: ImageView
    private lateinit var exploreIconImageView: ImageView
    private lateinit var activityIconImageView: ImageView
    private lateinit var profileIconImageView: ImageView
    private lateinit var homeIconContainer: android.widget.FrameLayout
    private lateinit var exploreIconContainer: android.widget.FrameLayout
    private lateinit var activityIconContainer: android.widget.FrameLayout
    private lateinit var profileIconContainer: android.widget.FrameLayout
    private lateinit var databaseIconImageView: ImageView // New database icon for admins
    private lateinit var aiAssistantIconImageView: ImageView // New AI Assistant icon
    private lateinit var evaluativeReinforcementIconImageView: ImageView // New Evaluative Reinforcement icon
    private lateinit var moreOptionsButton: ImageButton // More options button (3 dots)
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
    
    // Flag to open comments automatically (e.g. from notification)
    private var shouldOpenComments = false

    // Restore state variables
    private var restorePosition = 0
    private var restorePath: String? = null

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

        // Check for restore arguments
        restorePosition = arguments?.getInt("video_position", 0) ?: 0
        restorePath = arguments?.getString("video_path")

        // Fix for bottom navigation overlapping with system bars
        val bottomNav = view.findViewById<View>(R.id.bottomNavigation)
        if (bottomNav != null) {
            val initialBottomPadding = bottomNav.paddingBottom
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { v, insets ->
                val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, initialBottomPadding + systemBars.bottom)
                insets
            }
        }

        // Initialize Premium Badge
        // NOTE: Premium badge is now per-video in item_video.xml, not top-nav

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
            likeDao = database.likeDao(),
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
        shouldOpenComments = arguments?.getBoolean("openComments", false) ?: false
        val targetCommentId = arguments?.getLong("targetCommentId", -1L) ?: -1L

        if (videoId != -1L) {
            Log.d("VideoHomeFragment", "📹 Video specific navigation requested:")
            Log.d("VideoHomeFragment", "  - videoId: $videoId")
            Log.d("VideoHomeFragment", "  - videoTitle: $videoTitle")
            Log.d("VideoHomeFragment", "  - videoUsername: $videoUsername")
        }

        // Initialize ViewModel
        viewModel = ViewModelProvider(this)[VideoHomeViewModel::class.java]
        
        // Observe loading state
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            isLoadingVideos = isLoading
            if (isLoading && videoList.isEmpty()) {
                startSkeletonAnimation()
            } else if (!isLoading && viewModel.hasError.value != true) {
                // Same logic as ExploreFragment: only show content when network available
                // User requirement: "solo debe aparecer el skeleton" if no connection
                if (videoList.isNotEmpty() && isNetworkAvailable()) {
                    stopSkeletonAnimation()
                } else {
                     // Ensure skeleton is visible if list is empty OR no network
                     startSkeletonAnimation()
                }
            }
        }
        
        // Observe error state - show skeleton with tap to retry when there's an error
        viewModel.hasError.observe(viewLifecycleOwner) { hasError ->
            if (hasError && videoList.isEmpty()) {
                // Show skeleton with animation for error/no connection state
                startSkeletonAnimation()
                
                // Make skeleton tappable to retry
                skeletonContainer.setOnClickListener {
                    context?.let { Toast.makeText(it, "Reintentando conexión...", Toast.LENGTH_SHORT).show() }
                    viewModel.loadVideos(isRefresh = true)
                }
            } else if (!hasError) {
                skeletonContainer.setOnClickListener(null)
            }
        }
        
        // Observe video list
        viewModel.videoList.observe(viewLifecycleOwner) { videos ->
            videoList.clear()
            videoList.addAll(videos)
            allVideosList.clear()
            allVideosList.addAll(videos)

            // Logs requested: show remote_id for videos with course_id = null
            try {
                val noCourse = videos.filter { it.courseId == null || it.courseId <= 0 }
                if (noCourse.isNotEmpty()) {
                    Log.d("VideoHomeFragment", "Videos sin curso detectados: ${noCourse.size}")
                    noCourse.take(25).forEach { v ->
                        Log.d(
                            "VideoHomeFragment",
                            "video_id=${v.id} course_id=${v.courseId} remote_id=${v.remoteId} username='${v.username}'"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w("VideoHomeFragment", "Failed to log remote_id for no-course videos", e)
            }
            
            // Control visibility: skeleton vs ViewPager based on video list AND network
            // Same logic as ExploreFragment: only show content when network is available
            if (videoList.isNotEmpty() && isNetworkAvailable()) {
                // We have videos AND network - hide skeleton and show ViewPager
                stopSkeletonAnimation()
            } else {
                // No videos OR no network - show skeleton and hide ViewPager  
                startSkeletonAnimation()
            }
            
            if (::videoAdapter.isInitialized) {
                videoAdapter.updateVideos(videoList)
                
                val viewPager = view.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)
                
                // Handle auto-opening comments if requested (e.g. from notification)
                if (shouldOpenComments && videoList.isNotEmpty()) {
                    // content is loaded, try to find the video
                    val reqVideoId = arguments?.getLong("videoId", -1L) ?: -1L
                    val targetCommentId = arguments?.getLong("targetCommentId", -1L) ?: -1L
                    val targetIndex = if (reqVideoId != -1L) videoList.indexOfFirst { it.id == reqVideoId } else 0
                    
                    Log.d("VideoHomeFragment", "🎬 Auto-opening comments: videoId=$reqVideoId, commentId=$targetCommentId, videoIndex=$targetIndex")
                    
                    if (targetIndex != -1) {
                         // Post to message queue to ensure view is ready
                         viewPager?.post {
                             if (viewPager.currentItem != targetIndex) {
                                 viewPager.setCurrentItem(targetIndex, false)
                             }
                             
                             // Esperar un poco más para asegurar que el video está completamente cargado
                             viewPager.postDelayed({
                                 Log.d("VideoHomeFragment", "🎯 Opening comments dialog for video ${videoList[targetIndex].id} with target comment $targetCommentId")
                                 showCommentsDialog(videoList[targetIndex], targetCommentId)
                                 shouldOpenComments = false // Reset flag
                             }, 500) // Delay adicional para asegurar que el video está listo
                         }
                    } else {
                        Log.w("VideoHomeFragment", "⚠️ Video not found in list for videoId=$reqVideoId")
                        shouldOpenComments = false
                    }
                }

                // 
                // Check for restore path first
                if (restorePath != null && restorePosition > 0) {
                    val index = videos.indexOfFirst { it.videoUriString == restorePath || it.localFilePath == restorePath }
                    if (index != -1) {
                        viewPager?.setCurrentItem(index, false)
                        videoAdapter.setPendingSeek(restorePath!!, restorePosition)
                        // Clear restore path
                        restorePath = null
                        restorePosition = 0
                    } else {
                         // Fallback to ViewModel index
                         if (viewPager != null && viewModel.currentVideoIndex > 0 && viewModel.currentVideoIndex < videos.size) {
                            if (viewPager.currentItem != viewModel.currentVideoIndex) {
                                viewPager.setCurrentItem(viewModel.currentVideoIndex, false)
                            }
                        }
                    }
                } else {
                    // Restore scroll position from ViewModel
                    if (viewPager != null && viewModel.currentVideoIndex > 0 && viewModel.currentVideoIndex < videos.size) {
                        if (viewPager.currentItem != viewModel.currentVideoIndex) {
                            viewPager.setCurrentItem(viewModel.currentVideoIndex, false)
                        }
                    }
                }
                
                // CRITICAL: Ensure the current video starts playing after data loads
                // This handles the case where onPageSelected(0) was called before videos were loaded
                // or where the video was prepared but never got the play signal
                viewPager?.postDelayed({
                    val currentPosition = viewPager.currentItem
                    val recyclerView = viewPager.getChildAt(0) as? RecyclerView
                    val holder = recyclerView?.findViewHolderForAdapterPosition(currentPosition) as? VideoAdapter.VideoViewHolder
                    if (holder != null) {
                        Log.d("VideoHomeFragment", "Ensuring video plays at position $currentPosition after data load")
                        videoAdapter.setActivePosition(currentPosition)
                        holder.playVideo()
                    }
                }, 100) // Small delay to ensure ViewHolder is bound
            }
        }

        // Initialize views
        profileAvatars = view.findViewById(R.id.profileAvatars)
        skeletonContainer = view.findViewById(R.id.skeletonContainer)
        
        // Start skeleton animation immediately (skeleton is visible by default in XML)
        skeletonContainer.startShimmer()

        // Ensure `profileAvatars` matches the visible size of the live/profile button.
        // Try `enVivoButton` first, then `profileButton`, otherwise fallback to 42dp.
        try {
            val targetCandidate = view.findViewById<View?>(R.id.enVivoButton)
                ?: view.findViewById<View?>(R.id.profileButton)

            val applySize: (Int, Int) -> Unit = { w, h ->
                if (::profileAvatars.isInitialized) {
                    val params = profileAvatars.layoutParams
                    params.width = w
                    params.height = h
                    profileAvatars.layoutParams = params
                }
            }

            if (targetCandidate != null) {
                // Wait for layout to measure the candidate view
                targetCandidate.post {
                    try {
                        val tw = if (targetCandidate.width > 0) targetCandidate.width else targetCandidate.measuredWidth
                        val th = if (targetCandidate.height > 0) targetCandidate.height else targetCandidate.measuredHeight
                        if (tw > 0 && th > 0) {
                            applySize(tw, th)
                        } else {
                            val fallbackPx = (42 * resources.displayMetrics.density).toInt()
                            applySize(fallbackPx, fallbackPx)
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error sizing profileAvatars from candidate view", e)
                    }
                }
            } else {
                val fallbackPx = (42 * resources.displayMetrics.density).toInt()
                applySize(fallbackPx, fallbackPx)
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error applying profileAvatars sizing", e)
        }

        // Initialize bottom navigation icons
        homeIconImageView = view.findViewById(R.id.homeIconImageView)
        exploreIconImageView = view.findViewById(R.id.exploreIconImageView)
        activityIconImageView = view.findViewById(R.id.activityIconImageView)
        profileIconImageView = view.findViewById(R.id.profileIconImageView)
        
        homeIconContainer = view.findViewById(R.id.homeIconContainer)
        exploreIconContainer = view.findViewById(R.id.exploreIconContainer)
        activityIconContainer = view.findViewById(R.id.activityIconContainer)
        profileIconContainer = view.findViewById(R.id.profileIconContainer)

        databaseIconImageView = view.findViewById(R.id.databaseIconImageView) // Initialize new icon
        aiAssistantIconImageView = view.findViewById(R.id.aiAssistantIconImageView) // Initialize AI icon
        evaluativeReinforcementIconImageView = view.findViewById(R.id.evaluativeReinforcementIconImageView) // Initialize Evaluative Reinforcement icon
        moreOptionsButton = view.findViewById(R.id.moreOptionsButton) // Initialize more options button
        notificationBadge = view.findViewById(R.id.notificationBadge) // Badge de notificaciones

        // Setup initial colors for bottom navigation icons
        setupBottomNavigationIconColors()

        // Actualizar badge de notificaciones
        updateNotificationBadge()

        // Setup search functionality
        setupSearchBar(view)
        
        // Setup More Options Button
        setupMoreOptionsButton()

        // Setup AI Assistant Icon with animation and click listener (hidden from main bar but kept for reference or if logic changes)
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

        // Professional Animation for Evaluative Reinforcement Icon (Pulse/Heartbeat)
        val pulseScaleX = ObjectAnimator.ofFloat(evaluativeReinforcementIconImageView, "scaleX", 1f, 1.2f, 1f)
        val pulseScaleY = ObjectAnimator.ofFloat(evaluativeReinforcementIconImageView, "scaleY", 1f, 1.2f, 1f)
        
        pulseScaleX.repeatCount = ObjectAnimator.INFINITE
        pulseScaleY.repeatCount = ObjectAnimator.INFINITE
        
        pulseScaleX.duration = 2000
        pulseScaleY.duration = 2000
        
        pulseScaleX.interpolator = AccelerateDecelerateInterpolator()
        pulseScaleY.interpolator = AccelerateDecelerateInterpolator()
        
        val pulseAnimatorSet = android.animation.AnimatorSet()
        pulseAnimatorSet.playTogether(pulseScaleX, pulseScaleY)
        pulseAnimatorSet.start()

        // Evaluative Reinforcement Click Listener — navigate with zero-duration animations for instant transition
        evaluativeReinforcementIconImageView.setOnClickListener {
            try {
                val opts = androidx.navigation.navOptions {
                    anim {
                        enter = 0
                        exit = 0
                        popEnter = 0
                        popExit = 0
                    }
                }
                findNavController().navigate(R.id.action_videoHomeFragment_to_evaluativeReinforcementFragment, null, opts)
            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error navigating to EvaluativeReinforcementFragment", e)
                // Fallback: try plain navigate without options
                try { findNavController().navigate(R.id.action_videoHomeFragment_to_evaluativeReinforcementFragment) } catch (_: Exception) { }
            }
        }

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

        // Also set up the profile avatars in the top bar to navigate to profile
        profileAvatars.setOnClickListener {
            navigateToProfileSafely()
        }

        // Add this code to handle the bottom navigation profile button click
        val profileNavButton = view.findViewById<LinearLayout>(R.id.profileNavButton)
        profileNavButton?.setOnClickListener {
            // Instant visual feedback
            updateBottomNavSelection("profile")
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
            // Instant visual feedback
            updateBottomNavSelection("explore")
            findNavController().navigate(R.id.action_videoHomeFragment_to_exploreFragment)
        }

        // Set up Activity button to navigate to NotificacionesFragment
        val activityButton = view.findViewById<LinearLayout>(R.id.activityButton)
        activityButton?.setOnClickListener {
            // Instant visual feedback
            updateBottomNavSelection("activity")
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
            // Check if user has role ID 2 (for AI Assistant, Evaluative Reinforcement, and Database)
            val hasRole2 = sess.hasRole(2)
            
            // Hide individual icons from top nav as they are now in the 3-dot menu
            aiAssistantIconImageView.visibility = View.GONE
            evaluativeReinforcementIconImageView.visibility = View.GONE
            databaseIconImageView.visibility = View.GONE
            
            // Show more options button always (it contains tools for various roles)
            moreOptionsButton.visibility = View.VISIBLE

            // Handle admin-specific elements (separate from role 2)
            // Check for Role 3 (Admin) explicitly
            val hasAdminRole = sess.hasRole(3)
            
            if (isAdmin || hasAdminRole) {
                // Admin: Show admin slot and button
                adminSlot?.visibility = View.VISIBLE
                goToAdminButton?.visibility = View.VISIBLE
                goToAdminButton?.setOnClickListener {
                    Log.d("VideoHomeFragment", "Admin button clicked, navigating to HomeFragment")
                    findNavController().navigate(R.id.action_videoHomeFragment_to_homeFragment)
                }
            } else {
                // Non-admin: Hide admin elements
                adminSlot?.visibility = View.GONE
                goToAdminButton?.visibility = View.GONE
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
        
        // Load videos using ViewModel
        viewModel.loadVideos(videoId)
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
    
    private fun setupMoreOptionsButton() {
        moreOptionsButton.setOnClickListener { anchorView ->
            showLLMOptionsMenu(anchorView)
        }
    }
    
    private fun showLLMOptionsMenu(anchorView: View) {
        // Create a custom popup window with better styling
        val popupView = layoutInflater.inflate(R.layout.popup_menu_llm_options, null)
        val popupWindow = android.widget.PopupWindow(
            popupView,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        )
        
        // Set background using bg_header_gradient.xml
        val bgDrawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_header_gradient)
        popupWindow.setBackgroundDrawable(bgDrawable)
        popupWindow.animationStyle = android.R.style.Animation_Dialog
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true
        
        // Get current user roles
        val sess = SessionManager.getInstance(requireContext())
        val hasRole2 = sess.hasRole(2) // Role 2 for all LLM features
        val isAdmin = sess.isAdmin() // Separate admin role
        
        // Configure menu items based on user roles
        val llmDatabaseOption = popupView.findViewById<android.widget.LinearLayout>(R.id.llmDatabaseOption)
        val llmTasksOption = popupView.findViewById<android.widget.LinearLayout>(R.id.llmTasksOption)
        val llmReinforcementOption = popupView.findViewById<android.widget.LinearLayout>(R.id.llmReinforcementOption)
        
        // Get icon ImageViews for animations using correct IDs
        val databaseIcon = popupView.findViewById<android.widget.ImageView>(R.id.databaseMenuIcon)
        val tasksIcon = popupView.findViewById<android.widget.ImageView>(R.id.tasksMenuIcon)
        val reinforcementIcon = popupView.findViewById<android.widget.ImageView>(R.id.reinforcementMenuIcon)
        
        // Show/hide options based on roles
        llmDatabaseOption.visibility = if (isAdmin) View.VISIBLE else View.GONE
        llmTasksOption.visibility = if (hasRole2) View.VISIBLE else View.GONE
        llmReinforcementOption.visibility = View.VISIBLE

        // Removed programmatic background color setting to allow bg_header_gradient to show from XML
        /*
        try {
            val optionBgColor = android.graphics.Color.parseColor("#DD2B303B") // semi-opaque dark
            if (llmDatabaseOption.visibility == View.VISIBLE) llmDatabaseOption.setBackgroundColor(optionBgColor)
            if (llmTasksOption.visibility == View.VISIBLE) llmTasksOption.setBackgroundColor(optionBgColor)
            if (llmReinforcementOption.visibility == View.VISIBLE) llmReinforcementOption.setBackgroundColor(optionBgColor)
        } catch (e: Exception) {
            // Fall back silently if color parsing or setting fails
        }
        */
        
        // Add staggered entrance animation for menu items
        val menuItems = listOf(llmDatabaseOption, llmTasksOption, llmReinforcementOption).filter { it.visibility == View.VISIBLE }
        menuItems.forEachIndexed { index, item ->
            item.alpha = 0f
            item.translationY = 20f
            item.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay((index * 50).toLong()) // Stagger by 50ms
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        
        // Add icon-specific animations after menu items appear
        popupView.postDelayed({
            try {
                // Setup liquid glass icon press animations
                setupIconPressAnimation(databaseIcon.parent as View, databaseIcon)
                setupIconPressAnimation(tasksIcon.parent as View, tasksIcon)
                setupIconPressAnimation(reinforcementIcon.parent as View, reinforcementIcon)
                
                // Database icon particle animation (without movement)
                if (databaseIcon.visibility == View.VISIBLE) {
                    val particleAnimation = android.view.animation.AnimationUtils.loadAnimation(requireContext(), R.anim.particle_float)
                    databaseIcon.startAnimation(particleAnimation)
                }
                
                // Tasks icon pulse animation
                if (tasksIcon.visibility == View.VISIBLE) {
                    val pulseX = ObjectAnimator.ofFloat(tasksIcon, "scaleX", 1f, 1.2f, 1f)
                    val pulseY = ObjectAnimator.ofFloat(tasksIcon, "scaleY", 1f, 1.2f, 1f)
                    
                    // Set repeat count for individual animators
                    pulseX.setRepeatCount(android.animation.ValueAnimator.INFINITE)
                    pulseY.setRepeatCount(android.animation.ValueAnimator.INFINITE)
                    
                    val pulseSet = android.animation.AnimatorSet()
                    pulseSet.playTogether(pulseX, pulseY)
                    pulseSet.duration = 1500
                    pulseSet.start()
                }
                
                // Reinforcement icon glow animation
                if (reinforcementIcon.visibility == View.VISIBLE) {
                    val glowAlpha = ObjectAnimator.ofFloat(reinforcementIcon, "alpha", 1f, 0.6f, 1f)
                    glowAlpha.duration = 2000
                    glowAlpha.setRepeatCount(android.animation.ValueAnimator.INFINITE)
                    glowAlpha.start()
                }
            } catch (e: Exception) {
                Log.d("VideoHomeFragment", "Error starting icon animations: ${e.message}")
            }
        }, 300) // Start icon animations after menu animation
        
        // Add entrance animation
        popupView.alpha = 0f
        popupView.scaleX = 0.8f
        popupView.scaleY = 0.8f
        popupView.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        
        // Set click listeners with animation
        llmDatabaseOption.setOnClickListener {
            // Add click animation
            it.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    it.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                    popupWindow.dismiss()
                    findNavController().navigate(R.id.action_videoHomeFragment_to_databaseQueryFragment)
                }
                .start()
        }
        
        llmTasksOption.setOnClickListener {
            // Add click animation
            it.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(100)
                .withEndAction {
                    it.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(100)
                        .start()
                    popupWindow.dismiss()
                    findNavController().navigate(R.id.action_videoHomeFragment_to_chatBotFragment)
                }
                .start()
        }
        
        llmReinforcementOption.setOnClickListener {
            // Visual feedback (non-blocking)
            it.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(50)
                .start()

            popupWindow.dismiss()
            
            // Navigate IMMEDIATELY with NO animation for instant transition
            try {
                val opts = androidx.navigation.navOptions {
                    anim {
                        enter = 0
                        exit = 0
                        popEnter = 0
                        popExit = 0
                    }
                    launchSingleTop = true
                }
                findNavController().navigate(R.id.action_videoHomeFragment_to_evaluativeReinforcementFragment, null, opts)
            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error navigating to EvaluativeReinforcementFragment", e)
            }
        }
        
        // Show popup below the anchor view with offset
        popupWindow.showAsDropDown(anchorView, 0, 8) // 8dp offset from anchor
        
        // Add exit animation when dismissed
        popupWindow.setOnDismissListener {
            popupView.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(150)
                .start()
        }
    }

    private fun setupIconPressAnimation(iconContainer: View, icon: View) {
        iconContainer.setOnTouchListener { v, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // Scale down animation when pressed
                    icon.animate()
                        .scaleX(0.85f)
                        .scaleY(0.85f)
                        .setDuration(100)
                        .start()
                    
                    // Also animate the container background
                    val background = iconContainer.background
                    if (background is android.graphics.drawable.StateListDrawable) {
                        iconContainer.isPressed = true
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // Scale back up when released
                    icon.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(100)
                        .start()
                    
                    // Reset background state
                    val background = iconContainer.background
                    if (background is android.graphics.drawable.StateListDrawable) {
                        iconContainer.isPressed = false
                    }
                }
            }
            false // Let the click event pass through
        }
    }

    private suspend fun handleSubscriptionToggle(creatorId: Long, isSubscribing: Boolean) {
        val currentUserId = getCurrentUserId()
        if (currentUserId == -1L) {
            withContext(Dispatchers.Main) {
                context?.let { Toast.makeText(it, "Debes iniciar sesión para suscribirte", Toast.LENGTH_SHORT).show() }
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
                context?.let { Toast.makeText(it, "Error al actualizar suscripción en Supabase", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun updateBottomNavSelection(selected: String) {
        val activeBackground = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.nav_item_background_active)
        
        homeIconContainer.background = if (selected == "home") activeBackground else null
        exploreIconContainer.background = if (selected == "explore") activeBackground else null
        activityIconContainer.background = if (selected == "activity") activeBackground else null
        profileIconContainer.background = if (selected == "profile") activeBackground else null
    }

    private fun setupBottomNavigationIconColors() {
        // Active background (Purple Pill)
        val activeBackground = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.nav_item_background_active)
        
        // Set "Inicio" to active
        homeIconContainer.background = activeBackground
        exploreIconContainer.background = null
        activityIconContainer.background = null
        profileIconContainer.background = null

        // Icons always white
        val whiteColor = Color.parseColor("#FFFFFF")
        homeIconImageView.setColorFilter(whiteColor, PorterDuff.Mode.SRC_IN)
        exploreIconImageView.setColorFilter(whiteColor, PorterDuff.Mode.SRC_IN)
        activityIconImageView.setColorFilter(whiteColor, PorterDuff.Mode.SRC_IN)
        profileIconImageView.setColorFilter(whiteColor, PorterDuff.Mode.SRC_IN)
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
        // CRITICAL: Sync user likes FIRST before creating adapter to ensure like state persists
        lifecycleScope.launch {
            val userId = getCurrentUserId()
            
            Log.d("VideoHomeFragment", "======== LIKE SYNC START ========")
            Log.d("VideoHomeFragment", "Current user ID: $userId")
            
            // Sync user likes from Supabase (polymorphic likes table) to ensure persistence
            var likedVideoIds: List<Long> = emptyList()
            if (userId > 0) {
                val syncResult = withContext(Dispatchers.IO) {
                    try {
                        Log.d("VideoHomeFragment", "Fetching user likes from Supabase (polymorphic)...")
                        val likes = syncRepository.syncUserVideoLikesFromSupabase(userId)
                        Log.d("VideoHomeFragment", "User $userId has liked ${likes.size} videos: $likes")
                        likes
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error syncing user likes", e)
                        emptyList()
                    }
                }
                likedVideoIds = syncResult
                Log.d("VideoHomeFragment", "Sync completed: ${likedVideoIds.size} liked videos")
            } else {
                Log.w("VideoHomeFragment", "No valid user ID, skipping like sync")
            }
            
            Log.d("VideoHomeFragment", "======== LIKE SYNC END ========")
            
            // NOW create adapter after sync completes
            withContext(Dispatchers.Main) {
                initializeVideoAdapter(view, userId)
            }
        }
    }
    
    private fun initializeVideoAdapter(view: View, currentUserId: Long) {
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
                // Check if paid content Logic
                // If paid and not enrolled -> Show Payment Options (DON'T navigate to CourseDetail)
                // Else -> Navigate to Course Detail
                lifecycleScope.launch {
                    try {
                        var shouldShowPayment = false
                        var coursePrice = 0.0
                        var courseName = videoData.title ?: "Curso Premium"
                        var courseId = videoData.courseId ?: 0L
                        
                        // ALWAYS check course premium status from course table (not videoData.isPaid)
                        // because videoData.isPaid may not be synced correctly
                        if (videoData.courseId != null && videoData.courseId!! > 0) {
                            val paymentCheckResult = withContext(Dispatchers.IO) {
                                val db = AppDatabase.getDatabase(requireContext())
                                val userId = getCurrentUserId()
                                
                                try {
                                    // First try local DB, then Supabase
                                    var course = db.courseDao().getCourseById(videoData.courseId!!)
                                    
                                    // If not found locally, try Supabase
                                    if (course == null) {
                                        course = com.example.tareamov.service.SupabaseClient.fetchCourseById(videoData.courseId!!)
                                    }
                                    
                                    if (course != null) {
                                        coursePrice = course.price
                                        courseName = course.title ?: courseName
                                        courseId = course.id
                                        
                                        // Check if course is premium (isPremium OR price > 0)
                                        val isPaidCourse = course.isPremium || course.price > 0
                                        Log.d("VideoHomeFragment", "Course ${course.id} isPremium=${course.isPremium}, price=${course.price}, isPaidCourse=$isPaidCourse")
                                        
                                        if (!isPaidCourse) {
                                            // Free course - no payment needed
                                            return@withContext false
                                        }
                                        
                                        // Check if user is the creator (creator always has access)
                                        if (userId > 0 && course.creatorUserId == userId) {
                                            Log.d("VideoHomeFragment", "User is creator, access granted")
                                            return@withContext false
                                        }
                                        
                                        if (userId <= 0) {
                                            // Not logged in -> Must pay/login for premium course
                                            Log.d("VideoHomeFragment", "User not logged in, must pay")
                                            return@withContext true
                                        }
                                        
                                        // Check enrollment via ProgresoEstudianteDao
                                        val isEnrolledLocal = db.progresoEstudianteDao().getProgreso(userId, course.id) != null
                                        Log.d("VideoHomeFragment", "isEnrolledLocal=$isEnrolledLocal")
                                        
                                        if (isEnrolledLocal) {
                                            return@withContext false // Already enrolled locally
                                        }
                                        
                                        // Also check Supabase for enrollment (using fetchProgresosByUsuario)
                                        val isEnrolledRemote = try {
                                            val progresos = com.example.tareamov.service.SupabaseClient.fetchProgresosByUsuario(userId)
                                            progresos.any { it.cursoId == course.id }
                                        } catch (e: Exception) {
                                            Log.e("VideoHomeFragment", "Error checking remote enrollment", e)
                                            false
                                        }
                                        Log.d("VideoHomeFragment", "isEnrolledRemote=$isEnrolledRemote")
                                        
                                        // If not enrolled anywhere, needs payment
                                        !isEnrolledRemote
                                    } else {
                                        // Course not found - don't block access
                                        Log.w("VideoHomeFragment", "Course not found for courseId=${videoData.courseId}")
                                        false
                                    }
                                } catch (e: Exception) {
                                    Log.e("VideoHomeFragment", "Error checking course premium status", e)
                                    false
                                }
                            }
                            shouldShowPayment = paymentCheckResult
                        }

                        if (shouldShowPayment) {
                            // Get userId BEFORE switching to Main context (since getCurrentUserId is suspend)
                            val paymentUserId = getCurrentUserId()
                            val paymentUsername = withContext(Dispatchers.Main) { getCurrentUsername() }
                            Log.d("VideoHomeFragment", "Payment check: username=$paymentUsername, userId=$paymentUserId")
                            
                            withContext(Dispatchers.Main) {
                                showPaymentOptions(
                                    courseId,
                                    courseName,
                                    coursePrice,
                                    paymentUsername,
                                    paymentUserId
                                ) { success -> 
                                    if (success) {
                                        context?.let { Toast.makeText(it, "¡Acceso desbloqueado!", Toast.LENGTH_SHORT).show() }
                                        // Navigate to course detail after successful payment
                                        lifecycleScope.launch {
                                            val bundle = Bundle().apply {
                                                putLong("courseId", courseId)
                                                putString("courseName", courseName)
                                            }
                                            val navController = findNavController()
                                            if (navController.currentDestination?.id == R.id.videoHomeFragment) {
                                                navController.navigate(R.id.action_videoHomeFragment_to_courseDetailFragment, bundle)
                                            }
                                        }
                                    }
                                }
                            }
                            return@launch // CRITICAL: Don't continue to CourseDetail navigation
                        }

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
                            // Video has no course - get username and navigate to profile
                            val username = videoData.username
                            
                            // If username is empty, try to get it from a different source
                            val displayUsername = if (username.isNullOrBlank()) {
                                // Try to get username from video title or other metadata
                                withContext(Dispatchers.IO) {
                                    try {
                                        // Get the creator username from a course with the same title if it exists
                                        val act = requireActivity()
                                        if (act is com.example.tareamov.MainActivity) {
                                            val remoteList = act.syncRepository.fetchCoursesByCreatorFromSupabase("")
                                            val matchingCourse = remoteList.firstOrNull { c ->
                                                (c.title ?: "").equals(videoData.title ?: "", ignoreCase = true)
                                            }
                                            if (matchingCourse != null && matchingCourse.creatorUserId != null) {
                                                com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(matchingCourse.creatorUserId)
                                            } else {
                                                null
                                            }
                                        } else null
                                    } catch (e: Exception) {
                                        Log.w("VideoHomeFragment", "Could not get username: ${e.message}")
                                        null
                                    }
                                }
                            } else {
                                username
                            }

                            // Show message that user hasn't created a course
                            withContext(Dispatchers.Main) {
                                Toast.makeText(
                                    context, 
                                    "Este usuario no ha creado un curso", 
                                    Toast.LENGTH_SHORT
                                ).show()

                                // Navigate to user profile
                                if (!displayUsername.isNullOrBlank()) {
                                    val bundle = Bundle().apply {
                                        putString("username", displayUsername)
                                    }
                                    
                                    val navController = findNavController()
                                    if (navController.currentDestination?.id == R.id.videoHomeFragment) {
                                        navController.navigate(R.id.userProfileViewFragment, bundle)
                                    }
                                } else {
                                    Log.w("VideoHomeFragment", "Could not navigate to profile - no username available")
                                }
                                Unit
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error navigating for video ${videoData.id}", e)
                        context?.let { Toast.makeText(it, "No se pudo abrir el perfil", Toast.LENGTH_SHORT).show() }
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
                lifecycleScope.launch {
                    try {
                        val userId = getCurrentUserId()
                        if (userId > 0) {
                            // Toggle like in background
                            val success = withContext(Dispatchers.IO) {
                                syncRepository.toggleVideoLike(videoData.id, userId, isLiked)
                            }
                            
                            if (success) {
                                Log.d("VideoHomeFragment", "Like toggled successfully for video ${videoData.id}, liked=$isLiked")
                            } else {
                                Log.e("VideoHomeFragment", "Failed to toggle like for video ${videoData.id}")
                                // Revert UI state on failure by refreshing
                                withContext(Dispatchers.Main) {
                                    videoAdapter.notifyDataSetChanged()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error toggling like", e)
                        // Revert UI on error
                        withContext(Dispatchers.Main) {
                            videoAdapter.notifyDataSetChanged()
                        }
                    }
                }
            },
            onCommentClick = { videoData ->
                // Show comment dialog or navigate to comments
                // Verificar si hay un targetCommentId desde la notificación
                val targetId = arguments?.getLong("targetCommentId", -1L) ?: -1L
                Log.d("VideoHomeFragment", "Comment button clicked for video ${videoData.id}, targetCommentId=$targetId")
                showCommentsDialog(videoData, targetId)
                // Limpiar el argumento después de usarlo para que no se reutilice
                arguments?.remove("targetCommentId")
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

        // Set current user ID
        videoAdapter.setCurrentUserId(currentUserId)
        
        // Set initial active position to 0 (first video)
        videoAdapter.setActivePosition(0)

        // Configurar el ViewPager2
        val viewPager = view.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)
        viewPager.adapter = videoAdapter
        
        // CRITICAL: Keep ViewPager hidden until we have actual videos AND network
        // Same logic as ExploreFragment - prevents showing content when offline
        viewPager.visibility = if (videoList.isNotEmpty() && isNetworkAvailable()) View.VISIBLE else View.GONE

        // Configurar orientación vertical para deslizar como TikTok
        viewPager.orientation = androidx.viewpager2.widget.ViewPager2.ORIENTATION_VERTICAL
        
        // OPTIMIZATION: Pre-load adjacent videos for smooth transitions while managing memory
        // offscreenPageLimit = 1 keeps only 3 videos in memory (previous, current, next)
        // This prevents OutOfMemoryError while maintaining smooth playback
        viewPager.offscreenPageLimit = 1

        // Desactivar el overscroll effect (el efecto de rebote al final de la lista)
        viewPager.getChildAt(0).overScrollMode = View.OVER_SCROLL_NEVER        // Listener para cambios de página
        var previousPosition = -1
        viewPager.registerOnPageChangeCallback(object : androidx.viewpager2.widget.ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                
                Log.d("VideoHomeFragment", "Page selected: $position (previous: $previousPosition)")
                
                // CRITICAL: Set the active position FIRST - this determines which video plays audio
                videoAdapter.setActivePosition(position)
                
                // Pausar el video anterior antes de reproducir el nuevo
                if (previousPosition != -1 && previousPosition != position) {
                    val recyclerView = viewPager.getChildAt(0) as? RecyclerView
                    val prevHolder = recyclerView?.findViewHolderForAdapterPosition(previousPosition) as? VideoAdapter.VideoViewHolder
                    prevHolder?.pauseVideo()
                    Log.d("VideoHomeFragment", "Paused previous video at position: $previousPosition")
                }
                previousPosition = position
                
                currentVideoIndex = position
                viewModel.currentVideoIndex = position

                // NOTE: Premium badge is now shown per-video in item_video.xml

                // Cargar más videos cuando se acerque al final
                if (position >= videoList.size - 2 && !isLoadingVideos && videoList.size < viewModel.totalVideos) {
                    Log.d("VideoHomeFragment", "Near end of list (pos $position/${videoList.size}), loading more...")
                    viewModel.loadMoreVideos()
                }

                // Reproducir solo el video actual
                val recyclerView = viewPager.getChildAt(0) as? RecyclerView
                val viewHolder = recyclerView?.findViewHolderForAdapterPosition(position) as? VideoAdapter.VideoViewHolder
                viewHolder?.playVideo()
                viewHolder?.setMuteState(isMuted) // Apply current mute state
                Log.d("VideoHomeFragment", "Playing video at position: $position")

                // Actualizar la información en pantalla (ya no necesario, cada video maneja su propia info)
                // displayVideo(videoList[position]) - Removed as video info is handled by individual items
            }
        })

        // Check for restore path here as well, in case observe happened before init
        if (restorePath != null && restorePosition > 0) {
             val index = videoList.indexOfFirst { it.videoUriString == restorePath || it.localFilePath == restorePath }
             if (index != -1) {
                 viewPager.setCurrentItem(index, false)
                 videoAdapter.setPendingSeek(restorePath!!, restorePosition)
                 restorePath = null
                 restorePosition = 0
             }
        }
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

        val intent = Intent(requireContext(), VideoPlayerActivity::class.java).apply {
            putExtra("video_path", videoData.videoUriString)
            putExtra("video_title", videoData.title)
            putExtra("video_description", videoData.description)
            putExtra("username", videoData.username)
            putExtra("videoId", videoData.id)
            if (videoData.isPaid) {
                putExtra("is_paid", true)
            }
        }
        startActivity(intent)
    }


    private fun startSkeletonAnimation() {
        skeletonContainer.visibility = View.VISIBLE
        skeletonContainer.alpha = 1f
        skeletonContainer.startShimmer()
        
        // HIDE ViewPager when showing skeleton to prevent empty item_video showing underneath
        view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)?.visibility = View.GONE
    }

    /**
     * Check if network is available - same logic as ExploreFragment
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun stopSkeletonAnimation() {
        // If already gone, nothing to do
        if (skeletonContainer.visibility == View.GONE) {
            skeletonContainer.stopShimmer()
            return
        }

        // CRITICAL: Same logic as ExploreFragment
        // Only hide skeleton if there are actual videos AND network is available
        // Per user requirement: "solo debe aparecer el skeleton" when no connection
        val hasVideos = videoList.isNotEmpty()
        val hasNetwork = isNetworkAvailable()
        
        // Must have both videos AND network to show content
        if (!hasVideos || !hasNetwork) {
            // Keep skeleton visible - either no videos or no network
            return
        }
        
        // We have videos AND network - show ViewPager and hide skeleton
        view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)?.visibility = View.VISIBLE

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

    override fun onResume() {
        super.onResume()
        registerNetworkCallback()
        
        // Reload avatar in case it changed
        loadCurrentUserAvatar()
        
        // Enable full screen mode via MainActivity
        (requireActivity() as? MainActivity)?.isFullScreenMode = true
    }

    override fun onPause() {
        super.onPause()
        unregisterNetworkCallback()
        
        // Pause and mute all videos when leaving fragment
        pauseAllVideos()
        
        // Disable full screen mode via MainActivity
        (requireActivity() as? MainActivity)?.isFullScreenMode = false
    }

    override fun onStop() {
        super.onStop()
        // Release videos when fragment is no longer visible to free memory
        releaseAllVideos()
        
        // Force garbage collection to free memory immediately
        System.gc()
        Log.d("VideoHomeFragment", "🗑️ Released all videos and triggered GC to free memory")
    }

    override fun onDestroyView() {
        // Release all video players before destroying view
        releaseAllVideos()
        
        // Clear video list to release references
        videoList.clear()
        allVideosList.clear()
        
        super.onDestroyView()
    }
    
    /**
     * Pause all videos in the ViewPager
     */
    private fun pauseAllVideos() {
        try {
            val viewPager = view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)
            val recyclerView = viewPager?.getChildAt(0) as? RecyclerView
            recyclerView?.let { rv ->
                for (i in 0 until rv.childCount) {
                    val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? VideoAdapter.VideoViewHolder
                    holder?.pauseVideo()
                }
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error pausing all videos", e)
        }
    }
    
    /**
     * Release all video players
     */
    private fun releaseAllVideos() {
        try {
            val viewPager = view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)
            val recyclerView = viewPager?.getChildAt(0) as? RecyclerView
            recyclerView?.let { rv ->
                for (i in 0 until rv.childCount) {
                    val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? VideoAdapter.VideoViewHolder
                    holder?.releasePlayer()
                }
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error releasing all videos", e)
        }
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
                    lifecycleScope.launch(Dispatchers.Main) {
                        // Add delay to ensure network is stable
                        kotlinx.coroutines.delay(1000)
                        
                        // ALWAYS reload from network when connection returns
                        // This ensures fresh data with complete usernames, etc.
                        // Same logic as ExploreFragment
                        if (!isLoadingVideos) {
                            Log.d("VideoHomeFragment", "Network restored - forcing full reload from Supabase")
                            if (::viewModel.isInitialized) {
                                viewModel.loadVideos(isRefresh = true)
                            }
                        }
                    }
                }
                
                override fun onLost(network: Network) {
                    super.onLost(network)
                    Log.d("VideoHomeFragment", "Network lost - showing skeleton")
                    lifecycleScope.launch(Dispatchers.Main) {
                        // Network lost - hide ViewPager and show skeleton
                        startSkeletonAnimation()
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
        // Show skeleton only if list is cleared (full reload)
        if (videoList.isEmpty()) {
            startSkeletonAnimation()
        }
        if (::viewModel.isInitialized) {
            viewModel.loadVideos(isRefresh = true)
        }
    }

    private fun getCurrentUsername(): String? {
        // Use SessionManager for consistent username retrieval
        if (!::sessionManager.isInitialized) {
            sessionManager = SessionManager.getInstance(requireContext())
        }
        val username = sessionManager.getUsername()
        if (!username.isNullOrBlank()) {
            return username
        }
        // Fallback to shared preferences
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
            return
        }

        try {
            if (!::sessionManager.isInitialized) {
                sessionManager = SessionManager.getInstance(requireContext())
            }

            val avatarUriString = sessionManager.getUserAvatar()
            if (!avatarUriString.isNullOrEmpty()) {
                // Load from session cache first
                if (::profileAvatars.isInitialized) {
                    Glide.with(this)
                        .load(avatarUriString)
                        .placeholder(R.drawable.ic_profile_placeholder)
                        .error(R.drawable.ic_profile_placeholder)
                        .into(profileAvatars)
                }
            } else {
                // If no avatar in session, try to fetch from Supabase
                val username = sessionManager.getUsername()
                if (username != null) {
                    lifecycleScope.launch {
                        try {
                            val remoteAvatar = com.example.tareamov.service.SupabaseClient.fetchUsuarioAvatarByUsername(username)
                            if (remoteAvatar != null) {
                                // Save to session for future use
                                sessionManager.saveUserAvatar(remoteAvatar)
                                
                                if (::profileAvatars.isInitialized) {
                                    Glide.with(this@VideoHomeFragment)
                                        .load(remoteAvatar)
                                        .placeholder(R.drawable.ic_profile_placeholder)
                                        .error(R.drawable.ic_profile_placeholder)
                                        .into(profileAvatars)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("VideoHomeFragment", "Error fetching remote avatar", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error in loadCurrentUserAvatar: ${e.message}", e)
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
            context?.let { Toast.makeText(it, "No se pudo navegar al perfil", Toast.LENGTH_SHORT).show() }
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

        // Set chip text color to black
        for (i in 0 until (filterChipGroup?.childCount ?: 0)) {
            val chip = filterChipGroup?.getChildAt(i) as? Chip
            chip?.setTextColor(Color.BLACK)
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

            context?.let {
                Toast.makeText(
                    it,
                    "ℹ️ Se compartió la información del video\n⚠️ El video no está disponible públicamente",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "❌ Error compartiendo video: ${e.message}", e)
            context?.let { Toast.makeText(it, "Error al compartir video: ${e.message}", Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Show comments dialog for a video
     * @param videoData El video actual
     * @param targetCommentId ID del comentario al que hacer scroll automáticamente (-1 si no hay)
     */
    private fun showCommentsDialog(videoData: com.example.tareamov.data.entity.VideoData, targetCommentId: Long = -1L) {
        val context = context ?: return
        Log.d("VideoHome", "Opening comments dialog for video ${videoData.id}, targetCommentId=$targetCommentId")

        // Use BottomSheetDialog for Instagram/TikTok style from bottom
        val bottomSheetDialog = com.google.android.material.bottomsheet.BottomSheetDialog(context, R.style.Theme_TareaMov_BottomSheet)
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_comments, null)
        bottomSheetDialog.setContentView(dialogView)

        // Configure bottom sheet behavior
        val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        var isBottomSheetExpanded = false
        bottomSheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
            behavior.isDraggable = true
            // Set peek height to 60% of screen height
            behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.6).toInt()
            it.setBackgroundResource(android.R.color.transparent)
            
            // Listen for when the bottom sheet is fully expanded
            behavior.addBottomSheetCallback(object : com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback() {
                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    if (newState == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) {
                        isBottomSheetExpanded = true
                        Log.d("VideoHome", "📐 Bottom sheet fully expanded")
                    }
                }
                override fun onSlide(bottomSheet: View, slideOffset: Float) {}
            })
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
                        context?.let { Toast.makeText(it, "Debes iniciar sesión", Toast.LENGTH_SHORT).show() }
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
                skeletonContainer?.visibility = View.VISIBLE
                commentsRecyclerView?.visibility = View.GONE
                
                // Short delay to show skeleton briefly for better UX
                kotlinx.coroutines.delay(300)

                val comments = syncRepository.getVideoComments(videoData.id)

                skeletonContainer?.clearAnimation()
                skeletonContainer?.visibility = View.GONE

                if (comments.isEmpty()) {
                    emptyText?.visibility = View.VISIBLE
                    commentsRecyclerView?.visibility = View.GONE
                } else {
                    emptyText?.visibility = View.GONE
                    commentsRecyclerView?.visibility = View.VISIBLE
                    
                    // Establecer el adapter y lista
                    commentsAdapter.submitList(comments)
                    
                    // Scrollear y destacar el comentario objetivo si existe
                    if (targetCommentId != -1L) {
                        Log.d("VideoHome", "📝 Comments list submitted (${comments.size} items), preparing to scroll to comment $targetCommentId")
                        
                        // Use OnLayoutChangeListener to detect when RecyclerView has finished layout
                        commentsRecyclerView?.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                            override fun onLayoutChange(
                                v: View?, left: Int, top: Int, right: Int, bottom: Int,
                                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                            ) {
                                // Remove listener immediately to avoid multiple triggers
                                commentsRecyclerView?.removeOnLayoutChangeListener(this)
                                
                                Log.d("VideoHome", "📏 RecyclerView layout complete, dimensions: ${right-left}x${bottom-top}")
                                
                                // Wait for bottom sheet animation and item rendering (increased to 900ms)
                                commentsRecyclerView?.postDelayed({
                                    if (isBottomSheetExpanded) {
                                        Log.d("VideoHome", "🎯 Bottom sheet ready, scrolling to comment $targetCommentId")
                                        scrollToAndHighlightComment(commentsRecyclerView, commentsAdapter, targetCommentId)
                                    } else {
                                        // Bottom sheet animation still in progress, wait longer
                                        Log.d("VideoHome", "⏳ Bottom sheet still animating, waiting 700ms more...")
                                        commentsRecyclerView?.postDelayed({
                                            Log.d("VideoHome", "🎯 Forcing scroll to comment $targetCommentId")
                                            scrollToAndHighlightComment(commentsRecyclerView, commentsAdapter, targetCommentId)
                                        }, 700)
                                    }
                                }, 900) // Increased delay for complete bottom sheet expansion + item rendering
                            }
                        })
                    }
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
                                context?.let { Toast.makeText(it, "Comentario agregado", Toast.LENGTH_SHORT).show() }
                            }
                        } else {
                            context?.let { Toast.makeText(it, "Debes iniciar sesión para comentar", Toast.LENGTH_SHORT).show() }
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error adding comment", e)
                        context?.let { Toast.makeText(it, "Error al agregar comentario", Toast.LENGTH_SHORT).show() }
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
     * Scrollea a un comentario específico y lo destaca visualmente
     * Maneja tanto comentarios de nivel superior como respuestas anidadas
     */
    private fun scrollToAndHighlightComment(
        recyclerView: RecyclerView?,
        commentsAdapter: CommentsAdapter,
        targetCommentId: Long
    ) {
        recyclerView ?: return
        
        Log.d("VideoHome", "🎯 Searching for comment ID: $targetCommentId")
        
        // Buscar el comentario en el adapter (puede ser top-level o respuesta)
        val commentResult = commentsAdapter.findCommentById(targetCommentId)
        
        if (commentResult == null) {
            Log.w("VideoHome", "⚠️ Target comment ID $targetCommentId NOT found in any comments")
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(
                    recyclerView.context,
                    "Comentario no encontrado. Es posible que haya sido eliminado.",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        
        val (comment, parentId) = commentResult
        
        if (parentId == null) {
            // Es un comentario de nivel superior - scroll directo
            Log.d("VideoHome", "✅ Found TOP-LEVEL comment: ${comment.comment.take(30)}...")
            val position = commentsAdapter.getParentPosition(comment.id)
            if (position != -1) {
                scrollToPositionAndHighlight(recyclerView, position, targetCommentId)
            }
        } else {
            // Es una respuesta - necesitamos expandir el padre primero
            Log.d("VideoHome", "✅ Found REPLY comment with parentId=$parentId: ${comment.comment.take(30)}...")
            
            val parentPosition = commentsAdapter.getParentPosition(parentId)
            if (parentPosition == -1) {
                Log.w("VideoHome", "⚠️ Parent comment not found for parentId=$parentId")
                return
            }
            
            // Primero scroll al padre
            (recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                ?.scrollToPositionWithOffset(parentPosition, 50)
            
            // Expandir las respuestas del padre
            val wasAlreadyExpanded = commentsAdapter.isParentExpanded(parentId)
            if (!wasAlreadyExpanded) {
                commentsAdapter.expandRepliesForParent(parentId)
                Log.d("VideoHome", "📂 Expanded replies for parent at position $parentPosition")
            }
            
            // Esperar a que se renderice la expansión y luego buscar el reply view
            recyclerView.postDelayed({
                highlightReplyInExpandedSection(recyclerView, parentPosition, parentId, targetCommentId, commentsAdapter)
            }, if (wasAlreadyExpanded) 300 else 600)
        }
    }
    
    /**
     * Hace scroll a una posición específica y destaca el comentario
     */
    private fun scrollToPositionAndHighlight(recyclerView: RecyclerView, position: Int, commentId: Long) {
        Log.d("VideoHome", "📍 Scrolling to position $position for comment $commentId")
        
        // Scroll inmediato a la posición
        (recyclerView.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
            ?.scrollToPositionWithOffset(position, 100)
        
        // Smooth scroll para centrar
        recyclerView.post {
            recyclerView.smoothScrollToPosition(position)
        }
        
        // Highlight después del scroll
        recyclerView.postDelayed({
            highlightViewAtPosition(recyclerView, position, commentId)
        }, 800)
    }
    
    /**
     * Busca y destaca una respuesta dentro de la sección expandida de un comentario padre
     */
    private fun highlightReplyInExpandedSection(
        recyclerView: RecyclerView,
        parentPosition: Int,
        parentId: Long,
        replyCommentId: Long,
        adapter: CommentsAdapter
    ) {
        try {
            val parentViewHolder = recyclerView.findViewHolderForAdapterPosition(parentPosition)
            val parentView = parentViewHolder?.itemView
            
            if (parentView == null) {
                Log.w("VideoHome", "⚠️ Parent view not found at position $parentPosition")
                return
            }
            
            // Buscar el contenedor de respuestas
            val repliesContainer = parentView.findViewById<LinearLayout>(R.id.repliesContainer)
            if (repliesContainer == null || repliesContainer.visibility != View.VISIBLE) {
                Log.w("VideoHome", "⚠️ Replies container not visible, retrying...")
                recyclerView.postDelayed({
                    highlightReplyInExpandedSection(recyclerView, parentPosition, parentId, replyCommentId, adapter)
                }, 300)
                return
            }
            
            // Obtener las respuestas del adapter para encontrar el índice
            val replies = adapter.getRepliesForParent(parentId)
            val replyIndex = replies.indexOfFirst { it.id == replyCommentId }
            
            if (replyIndex == -1 || replyIndex >= repliesContainer.childCount) {
                Log.w("VideoHome", "⚠️ Reply not found in container. Index: $replyIndex, Children: ${repliesContainer.childCount}")
                return
            }
            
            val replyView = repliesContainer.getChildAt(replyIndex)
            if (replyView != null) {
                Log.d("VideoHome", "✨ Found reply view at index $replyIndex, highlighting...")
                
                // Scroll para asegurar que la respuesta sea visible
                replyView.post {
                    // Calcular posición absoluta en la pantalla
                    val location = IntArray(2)
                    replyView.getLocationOnScreen(location)
                    val recyclerLocation = IntArray(2)
                    recyclerView.getLocationOnScreen(recyclerLocation)
                    
                    // Si la respuesta está fuera de la vista, hacer scroll
                    val replyTop = location[1] - recyclerLocation[1]
                    val recyclerHeight = recyclerView.height
                    
                    if (replyTop < 0 || replyTop > recyclerHeight - replyView.height) {
                        // Smooth scroll para mostrar la respuesta
                        val scrollAmount = replyTop - (recyclerHeight / 3)
                        recyclerView.smoothScrollBy(0, scrollAmount)
                    }
                }
                
                // Aplicar highlight visual
                replyView.postDelayed({
                    applyHighlightAnimation(replyView, replyCommentId)
                }, 400)
            }
        } catch (e: Exception) {
            Log.e("VideoHome", "❌ Error highlighting reply", e)
        }
    }
    
    /**
     * Destaca un view en una posición específica del RecyclerView
     */
    private fun highlightViewAtPosition(recyclerView: RecyclerView, position: Int, commentId: Long) {
        try {
            val viewHolder = recyclerView.findViewHolderForAdapterPosition(position)
            viewHolder?.itemView?.let { itemView ->
                applyHighlightAnimation(itemView, commentId)
            } ?: Log.w("VideoHome", "⚠️ ViewHolder not found for position $position")
        } catch (e: Exception) {
            Log.e("VideoHome", "❌ Error highlighting comment", e)
        }
    }
    
    /**
     * Aplica animación de highlight a un view
     */
    private fun applyHighlightAnimation(itemView: View, commentId: Long) {
        // Guardar el background original
        val originalBackground = itemView.background
        
        // Crear un drawable con color sutil (gris claro semitransparente) y border radius
        val highlightDrawable = android.graphics.drawable.GradientDrawable().apply {
            // Color ligeramente más claro que el fondo oscuro (#1A1A1A -> #2A2A2A con alpha)
            setColor(android.graphics.Color.parseColor("#40FFFFFF")) // Blanco con 25% alpha
            cornerRadius = 12f * itemView.context.resources.displayMetrics.density // 12dp border radius
        }
        itemView.background = highlightDrawable
        
        // Animación de pulse suave
        val scaleX = android.animation.ObjectAnimator.ofFloat(itemView, "scaleX", 1f, 1.02f, 1f)
        val scaleY = android.animation.ObjectAnimator.ofFloat(itemView, "scaleY", 1f, 1.02f, 1f)
        scaleX.duration = 350
        scaleY.duration = 350
        
        val animatorSet = android.animation.AnimatorSet()
        animatorSet.playTogether(scaleX, scaleY)
        animatorSet.start()
        
        // Remover highlight después de 3 segundos con fade out
        itemView.postDelayed({
            val alphaAnimator = android.animation.ObjectAnimator.ofFloat(itemView, "alpha", 1f, 0.9f, 1f)
            alphaAnimator.duration = 400
            alphaAnimator.addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    itemView.background = originalBackground
                }
            })
            alphaAnimator.start()
        }, 3000)
        
        Log.d("VideoHome", "✨ Highlighted comment ID: $commentId")
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
        
        // Callback para notificar cuando se expanda un comentario padre (para scroll a respuesta)
        var onParentExpanded: ((parentId: Long, parentPosition: Int) -> Unit)? = null

        fun submitList(newComments: List<com.example.tareamov.data.entity.VideoComment>) {
            allComments = newComments
            // Separate top-level comments (parentId is null or 0) from replies
            topLevelComments = allComments.filter { it.parentId == null || it.parentId == 0L }
            repliesMap = allComments.filter { it.parentId != null && it.parentId != 0L }
                .groupBy { it.parentId!! }

            notifyDataSetChanged()
        }
        
        /**
         * Busca un comentario por ID en todos los comentarios (incluyendo respuestas)
         * @return Pair<comentario, parentId?> o null si no se encuentra
         */
        fun findCommentById(commentId: Long): Pair<com.example.tareamov.data.entity.VideoComment, Long?>? {
            // Buscar en top-level
            val topLevel = topLevelComments.find { it.id == commentId }
            if (topLevel != null) return Pair(topLevel, null)
            
            // Buscar en respuestas
            for ((parentId, replies) in repliesMap) {
                val reply = replies.find { it.id == commentId }
                if (reply != null) return Pair(reply, parentId)
            }
            return null
        }
        
        /**
         * Obtiene el índice de un comentario padre en topLevelComments
         */
        fun getParentPosition(parentId: Long): Int {
            return topLevelComments.indexOfFirst { it.id == parentId }
        }
        
        /**
         * Expande las respuestas de un comentario padre específico
         * @return true si se expandió, false si ya estaba expandido o no existe
         */
        fun expandRepliesForParent(parentId: Long): Boolean {
            val position = getParentPosition(parentId)
            if (position == -1) return false
            
            val wasExpanded = expandedReplies[parentId] ?: false
            if (!wasExpanded) {
                expandedReplies[parentId] = true
                notifyItemChanged(position)
                Log.d("CommentsAdapter", "📂 Expanded replies for parent $parentId at position $position")
                onParentExpanded?.invoke(parentId, position)
                return true
            }
            return false
        }
        
        /**
         * Verifica si las respuestas de un padre están expandidas
         */
        fun isParentExpanded(parentId: Long): Boolean {
            return expandedReplies[parentId] ?: false
        }
        
        /**
         * Obtiene las respuestas de un comentario padre
         */
        fun getRepliesForParent(parentId: Long): List<com.example.tareamov.data.entity.VideoComment> {
            return repliesMap[parentId] ?: emptyList()
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