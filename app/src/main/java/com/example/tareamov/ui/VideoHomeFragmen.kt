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


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_video_home, container, false)
    }

    private val videoList = mutableListOf<VideoData>()
    private var currentVideoIndex = 0
    private lateinit var videoAdapter: VideoAdapter
    private var isVideosLoaded = false // Flag para evitar cargas duplicadas

    // In the onViewCreated method, update the goToHomeButton click listener
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize VideoManager
        videoManager = VideoManager(requireContext())
        sessionManager = SessionManager.getInstance(requireContext()) // Initialize SessionManager

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
                // Pass the courseId (or another identifier) as argument
                val bundle = Bundle().apply {
                    putLong("courseId", currentVideo.id) // Adjust if your VideoData has a courseId field
                }
                findNavController().navigate(R.id.action_videoHomeFragment_to_courseDetailFragment, bundle)
            } else {
                Toast.makeText(requireContext(), "No course information available", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up database orbit button click to navigate to DatabaseQueryFragment
        val databaseOrbitButton = view.findViewById<ImageView>(R.id.databaseOrbitButton)

        // Check if the current user is admin to show/hide the database orbit button
        checkAdminStatus { isAdmin ->
            if (isAdmin) {
                databaseOrbitButton?.visibility = View.VISIBLE
                databaseOrbitButton?.setOnClickListener {
                    findNavController().navigate(R.id.action_videoHomeFragment_to_databaseQueryFragment)
                }

                // Start the animated vector drawable for the orbit icon
                val drawable = databaseOrbitButton?.drawable
                if (drawable is android.graphics.drawable.AnimatedVectorDrawable) {
                    drawable.start()
                }
            } else {
                databaseOrbitButton?.visibility = View.INVISIBLE
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
        }        // Mostrar el botón de admin solo si el usuario es admin
        val goToAdminButton = view.findViewById<LinearLayout>(R.id.goToAdminButton)

        // Initially hide the admin button to avoid reflow during async check
        goToAdminButton?.visibility = View.INVISIBLE

        // Check if the current user is admin
        checkAdminStatus { isAdmin ->
            if (isAdmin) {
                goToAdminButton?.visibility = View.VISIBLE
                goToAdminButton?.setOnClickListener {
                    Log.d("VideoHomeFragment", "Admin button clicked, navigating to HomeFragment")
                    findNavController().navigate(R.id.action_videoHomeFragment_to_homeFragment)
                }
            } else {
                goToAdminButton?.visibility = View.INVISIBLE
            }
        }        // Load the current user's avatar
        loadCurrentUserAvatar()

        // Load sample videos or recently uploaded videos
        loadVideos(videoId, videoTitle, videoUsername)

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
        videoAdapter = VideoAdapter(videoList) { username ->
            // Handle profile click
            val bundle = Bundle().apply {
                putString("username", username)
            }
            findNavController().navigate(R.id.userProfileViewFragment, bundle)
        }

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
    }    private fun loadVideos(targetVideoId: Long = -1L, targetVideoTitle: String? = null, targetVideoUsername: String? = null) {
        // Evitar cargas concurrentes
        if (isVideosLoaded && targetVideoId == -1L) {
            Log.d("VideoHomeFragment", "Videos already loaded, skipping reload")
            return
        }

        // Cargar videos desde la base de datos usando VideoManager
        lifecycleScope.launch {
            try {
                // Limpiar la lista en el hilo principal para evitar problemas de concurrencia
                withContext(Dispatchers.Main) {
                    videoList.clear()
                    if (::videoAdapter.isInitialized) {
                        videoAdapter.notifyDataSetChanged()
                    }
                }

                Log.d("VideoHomeFragment", "Starting to load videos from database")

                // Obtener todos los videos de la base de datos
                val savedVideos = withContext(Dispatchers.IO) {
                    videoManager.getAllVideos()
                }
                Log.d("VideoHomeFragment", "Retrieved ${savedVideos.size} videos from database")

                // Log each video for debugging
                savedVideos.forEachIndexed { index, video ->
                    Log.d("VideoHomeFragment", "Video $index: ID=${video.id}, title='${video.title}', username='${video.username}', localPath='${video.localFilePath}', uriString='${video.videoUriString}'")
                }

                // Filtrar videos válidos y que NO sean por defecto
                val playableVideos = savedVideos.filter { video ->
                    val hasLocalFile = video.localFilePath != null && File(video.localFilePath).exists()
                    val isNotDefaultTitle = !video.title.equals("mi video", ignoreCase = true) &&
                            !video.title.equals("movideo", ignoreCase = true)

                    Log.d("VideoHomeFragment", "Video '${video.title}': hasLocalFile=$hasLocalFile, isNotDefaultTitle=$isNotDefaultTitle")

                    // For debugging, let's be less restrictive initially
                    hasLocalFile || !video.videoUriString.isNullOrEmpty()
                }

                // Agregar videos en el hilo principal y notificar al adaptador
                withContext(Dispatchers.Main) {
                    videoList.addAll(playableVideos)
                    Log.d("VideoHomeFragment", "Loaded ${playableVideos.size} playable videos from database")

                    // Notificar al adaptador que los datos han cambiado
                    if (::videoAdapter.isInitialized) {
                        videoAdapter.updateVideos(videoList)
                        Log.d("VideoHomeFragment", "Updated video adapter with ${videoList.size} videos")
                    }

                    // Si hay un video específico solicitado, intentar navegar a él
                    if (targetVideoId != -1L && videoList.isNotEmpty()) {
                        val targetIndex = videoList.indexOfFirst { it.id == targetVideoId }
                        if (targetIndex != -1) {
                            currentVideoIndex = targetIndex
                            navigateToVideoIndex(targetIndex)
                            Log.d("VideoHomeFragment", "Navigated to target video at index $targetIndex")
                        } else {
                            // Si no se encuentra por ID, intentar por título y usuario
                            val fallbackIndex = videoList.indexOfFirst {
                                it.title == targetVideoTitle && it.username == targetVideoUsername
                            }
                            if (fallbackIndex != -1) {
                                currentVideoIndex = fallbackIndex
                                navigateToVideoIndex(fallbackIndex)
                                Log.d("VideoHomeFragment", "Navigated to fallback video at index $fallbackIndex")
                            } else {
                                // Si no se encuentra el video específico, mostrar el primero
                                if (videoList.isNotEmpty()) {
                                    // Video info is now handled by individual video items
                                    // displayVideo(videoList[0]) - Removed 
                                    Log.d("VideoHomeFragment", "Target video not found, first video will display automatically")
                                } else {
                                    Log.w("VideoHomeFragment", "No videos available to display after fallback search")
                                }
                            }
                        }
                    } else if (videoList.isNotEmpty()) {
                        // Display the first video if available and no specific video requested
                        // Video info is now handled by individual video items
                        // displayVideo(videoList[0]) - Removed
                        Log.d("VideoHomeFragment", "First video will display automatically: ${videoList[0].title}")
                    } else {
                        Log.w("VideoHomeFragment", "No videos available to display")
                    }

                    // Marcar que los videos han sido cargados
                    isVideosLoaded = true
                }

            } catch (e: Exception) {
                Log.e("VideoHomeFragment", "Error loading videos", e)
                e.printStackTrace()
                // Incluso en caso de error, marcar como cargado para evitar reintentos infinitos
                isVideosLoaded = true
            }
        }
    }    override fun onResume() {
        super.onResume()
        // Solo recargar videos si ya se habían cargado antes (evita duplicación en primera carga)
        if (isVideosLoaded) {
            Log.d("VideoHomeFragment", "onResume: Reloading videos due to fragment resume")
            forceReloadVideos() // Forzar recarga al volver al fragmento
        } else {
            Log.d("VideoHomeFragment", "onResume: Skipping reload, videos not loaded yet")
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Resetear el flag cuando se destruye la vista
        isVideosLoaded = false
    }

    // Método para forzar la recarga de videos
    private fun forceReloadVideos() {
        isVideosLoaded = false
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