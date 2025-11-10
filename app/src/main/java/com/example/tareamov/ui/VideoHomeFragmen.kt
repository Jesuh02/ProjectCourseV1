package com.example.tareamov.ui

import android.content.Context
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
// Import SessionManager
import com.example.tareamov.util.SessionManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.media.MediaPlayer // Required for MediaPlayer interactions if direct

class VideoHomeFragment : Fragment() {
    private lateinit var profileAvatars: CircleImageView
    private lateinit var videoManager: VideoManager
    private lateinit var sessionManager: SessionManager // Add SessionManager instance

    private lateinit var homeIconImageView: ImageView
    private lateinit var exploreIconImageView: ImageView
    private lateinit var activityIconImageView: ImageView
    private lateinit var profileIconImageView: ImageView

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

        // Initialize views
        profileAvatars = view.findViewById(R.id.profileAvatars)

        // Initialize bottom navigation icons
        homeIconImageView = view.findViewById(R.id.homeIconImageView)
        exploreIconImageView = view.findViewById(R.id.exploreIconImageView)
        activityIconImageView = view.findViewById(R.id.activityIconImageView)
        profileIconImageView = view.findViewById(R.id.profileIconImageView)

        // Setup initial colors for bottom navigation icons
        setupBottomNavigationIconColors()


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

       // Set up database orbit button click to navigate to DatabaseQueryFragment
        val databaseOrbitButton = view.findViewById<ImageView>(R.id.databaseOrbitButton)

        // Decide visibility synchronously to avoid leaving a gap for non-admin users.
        // Default to GONE so the initial layout does not reserve space for the button.
        databaseOrbitButton?.visibility = View.GONE

        // Use the sessionManager initialized above to check admin synchronously
        try {
            if (sessionManager.isAdmin()) {
                databaseOrbitButton?.visibility = View.VISIBLE
                databaseOrbitButton?.setOnClickListener {
                    findNavController().navigate(R.id.action_videoHomeFragment_to_databaseQueryFragment)
                }

                // Start the animated vector drawable for the orbit icon if present
                val drawable = databaseOrbitButton?.drawable
                if (drawable is android.graphics.drawable.AnimatedVectorDrawable) {
                    drawable.start()
                }
            } else {
                // non-admin: keep GONE to remove any visual gap
                databaseOrbitButton?.visibility = View.GONE
            }
        } catch (e: Exception) {
            // If anything goes wrong, ensure the button does not leave a gap
            databaseOrbitButton?.visibility = View.GONE
            Log.e("VideoHomeFragment", "Error checking admin for databaseOrbitButton: ${e.message}", e)
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
        if (!sess.isAdmin()) {
            // Ocultar por completo el slot antes del primer render para que no quede hueco
            adminSlot?.visibility = View.GONE
        } else {
            // Usuario admin: mostrar y asignar listener
            goToAdminButton?.visibility = View.VISIBLE
            goToAdminButton?.setOnClickListener {
                Log.d("VideoHomeFragment", "Admin button clicked, navigating to HomeFragment")
                findNavController().navigate(R.id.action_videoHomeFragment_to_homeFragment)
            }
        }   // Load the current user's avatar
        loadCurrentUserAvatar()

        // Load videos directly from Supabase (ordered newest -> oldest) and display
        // This will show videos from all users and bypass Room for this fragment's feed
        setupVideoViewPager(view)
        lifecycleScope.launch {
            try {
                val act = requireActivity()
                if (act is com.example.tareamov.MainActivity) {
                    val repo = act.syncRepository
                    try {
                        val supaVideos = repo.fetchVideosFromSupabase()
                        // Replace adapter data on main thread
                        withContext(Dispatchers.Main) {
                            videoAdapter.updateVideos(supaVideos)
                            isVideosLoaded = true
                            // If caller requested a specific video, navigate to it
                            if (videoId != -1L) {
                                val idx = videoList.indexOfFirst { it.id == videoId }
                                if (idx >= 0) navigateToVideoIndex(idx)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("VideoHomeFragment", "Error fetching videos from Supabase", e)
                        // Fallback: attempt to load from local DB
                        loadVideos(videoId, videoTitle, videoUsername)
                    }
                } else {
                    loadVideos(videoId, videoTitle, videoUsername)
                }
            } catch (e: Exception) {
                Log.w("VideoHomeFragment", "Could not access SyncRepository to fetch videos", e)
                loadVideos(videoId, videoTitle, videoUsername)
            }
        }

        // Inicializar el adaptador de videos y configurar el ViewPager2
        setupVideoViewPager(view)
    }

    // REMOVE the checkCurrentUserAdminStatus() function as it's no longer needed
    // private suspend fun checkCurrentUserAdminStatus(): Boolean { ... }

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
                // Navigate to CourseDetailFragment using Supabase as authoritative source.
                lifecycleScope.launch {
                    try {
                        var matchingCourse: com.example.tareamov.data.entity.Course? = null
                        val act = requireActivity()

                        if (act is com.example.tareamov.MainActivity) {
                            try {
                                // Try server-side: fetch courses by this creator and match title
                                val remoteList = withContext(Dispatchers.IO) {
                                    act.syncRepository.fetchCoursesByCreatorFromSupabase(videoData.username ?: "")
                                }
                                matchingCourse = remoteList.firstOrNull { c -> (c.title ?: "").equals(videoData.title ?: "", ignoreCase = true) }

                                // If not found among creator's courses, try searching all remote courses as fallback
                                if (matchingCourse == null) {
                                    val all = withContext(Dispatchers.IO) { act.syncRepository.fetchCoursesFromSupabase() }
                                    matchingCourse = all.firstOrNull { c -> (c.title ?: "").equals(videoData.title ?: "", ignoreCase = true) && (c.creatorUsername ?: "").equals(videoData.username ?: "", ignoreCase = true) }
                                }
                            } catch (e: Exception) {
                                Log.w("VideoHomeFragment", "Supabase course lookup failed, falling back to local: ${e.message}", e)
                            }
                        }

                        val bundle = Bundle().apply {
                            if (matchingCourse != null) {
                                putLong("courseId", matchingCourse.id ?: -1L)
                                putString("courseName", matchingCourse.title ?: videoData.title)
                            } else {
                                // No remote course found — navigate with video title only (CourseDetail will handle fallback)
                                putLong("courseId", -1L)
                                putString("courseName", videoData.title)
                            }
                        }

                        // Check if current destination is still VideoHomeFragment before navigating
                        val navController = findNavController()
                        if (navController.currentDestination?.id == R.id.videoHomeFragment) {
                            navController.navigate(R.id.action_videoHomeFragment_to_courseDetailFragment, bundle)
                        }
                    } catch (e: Exception) {
                        Log.e("VideoHomeFragment", "Error navigating to CourseDetailFragment for video ${videoData.id}", e)
                        val bundle = Bundle().apply {
                            putLong("courseId", -1L)
                            putString("courseName", videoData.title)
                        }
                        // Check if current destination is still VideoHomeFragment before navigating
                        val navController = findNavController()
                        if (navController.currentDestination?.id == R.id.videoHomeFragment) {
                            navController.navigate(R.id.action_videoHomeFragment_to_courseDetailFragment, bundle)
                        }
                    }
                }
            }
        )

        // Configurar el ViewPager2
        val viewPager = view.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)
        viewPager.adapter = videoAdapter

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
                val db = AppDatabase.getDatabase(requireContext())
                val persona = withContext(Dispatchers.IO) {
                    db.personaDao().getPersonaByUsername(videoData.username)
                }
                // Avatar loading is now handled by VideoAdapter
                Log.d("VideoHomeFragment", "Avatar lookup completed for user: ${videoData.username}")
            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error loading video uploader avatar", e)
            }
        }
        // --- FIN DEL BLOQUE NUEVO ---
    }
    
    /**
     * Carga los primeros 10 videos desde Supabase (más recientes primero)
     */
    private fun loadVideos(targetVideoId: Long = -1L, targetVideoTitle: String? = null, targetVideoUsername: String? = null) {
        if (isLoadingVideos) {
            Log.d("VideoHomeFragment", "Already loading videos, skipping")
            return
        }

        isLoadingVideos = true
        lifecycleScope.launch {
            try {
                Log.d("VideoHomeFragment", "Loading initial videos from Supabase (page 0)")

                val (videos, total) = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.fetchVideosPaginated(
                        limit = pageSize,
                        offset = 0
                    )
                }

                totalVideos = total
                currentPage = 0
                
                withContext(Dispatchers.Main) {
                    videoList.clear()
                    videoList.addAll(videos)
                    
                    if (::videoAdapter.isInitialized) {
                        videoAdapter.updateVideos(videoList)
                    }
                    
                    Log.d("VideoHomeFragment", "Loaded ${videos.size} videos (total: $totalVideos)")
                    isVideosLoaded = true
                }

            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error loading videos", e)
                Toast.makeText(context, "Error cargando videos: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                isLoadingVideos = false
            }
        }
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

                val (videos, _) = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.fetchVideosPaginated(
                        limit = pageSize,
                        offset = offset
                    )
                }

                if (videos.isNotEmpty()) {
                    currentPage = nextPage
                    
                    withContext(Dispatchers.Main) {
                        val oldSize = videoList.size
                        videoList.addAll(videos)
                        
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
        // Recargar videos desde Supabase al volver al fragmento
        if (isVideosLoaded) {
            Log.d("VideoHomeFragment", "onResume: Reloading videos from Supabase")
            forceReloadVideos()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        isVideosLoaded = false
    }

    // Método para forzar la recarga de videos desde Supabase
    private fun forceReloadVideos() {
        isVideosLoaded = false
        currentPage = 0
        totalVideos = 0
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
            val viewPager = view?.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.videoViewPager)
            viewPager?.setCurrentItem(index, false) // false para navegación inmediata sin animación
            displayVideo(videoList[index])
        } catch (e: Exception) {
            Log.e("VideoHomeFragment", "Error navigating to video index $index", e)
        }
    }
}