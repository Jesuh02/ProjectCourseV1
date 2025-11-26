package com.example.tareamov.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private var allUserVideos = mutableListOf<VideoData>() // All videos from the user for search purposes
    private var filteredContent = mutableListOf<VideoData>() // For search results
    private var currentFilter = ContentType.COURSE
    private var isSearchMode = false
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
            content.description.contains(currentSearchQuery, ignoreCase = true) ||
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
                    handleContentClick(course)
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
                }
            )

            // Configurar adaptador para videos con estilo YouTube
            videoAdapter = YouTubeStyleVideoAdapter(
                requireContext(),
                mutableListOf(),
                onVideoClickListener = { video ->
                    handleVideoClick(video)
                }
            )

            contentRecyclerView.apply {
                layoutManager = LinearLayoutManager(context)
                adapter = contentAdapter // Iniciar con adaptador de cursos

                // Agregar ScrollListener para optimizar la reproducción (similar a ExploreFragment)
                addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                        super.onScrollStateChanged(recyclerView, newState)
                        if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                            // Cuando el scroll se detiene, asegurar que las miniaturas estén cargadas
                            Log.d("UserProfileView", "Scroll stopped, refreshing thumbnails")
                            ensureThumbnailsLoaded()
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
    }private fun updateFilterButtonsUI() {
        when (currentFilter) {
            ContentType.COURSE -> {
                coursesFilterButton.setBackgroundResource(R.drawable.filter_button_selected)
                videosFilterButton.setBackgroundResource(R.drawable.filter_button_unselected)
                
                // Update text colors and icon tints for courses filter
                val coursesTextView = coursesFilterButton.getChildAt(1) as? TextView
                val coursesIcon = coursesFilterButton.getChildAt(0) as? ImageView
                coursesTextView?.setTextColor(requireContext().getColor(android.R.color.black))
                coursesIcon?.setColorFilter(requireContext().getColor(android.R.color.black))
                
                // Update text colors and icon tints for videos filter
                val videosTextView = videosFilterButton.getChildAt(1) as? TextView
                val videosIcon = videosFilterButton.getChildAt(0) as? ImageView
                videosTextView?.setTextColor(requireContext().getColor(R.color.light_purple))
                videosIcon?.setColorFilter(requireContext().getColor(R.color.light_purple))
            }
            ContentType.VIDEO -> {
                videosFilterButton.setBackgroundResource(R.drawable.filter_button_selected)
                coursesFilterButton.setBackgroundResource(R.drawable.filter_button_unselected)
                
                // Update text colors and icon tints for videos filter
                val videosTextView = videosFilterButton.getChildAt(1) as? TextView
                val videosIcon = videosFilterButton.getChildAt(0) as? ImageView
                videosTextView?.setTextColor(requireContext().getColor(android.R.color.black))
                videosIcon?.setColorFilter(requireContext().getColor(android.R.color.black))
                
                // Update text colors and icon tints for courses filter
                val coursesTextView = coursesFilterButton.getChildAt(1) as? TextView
                val coursesIcon = coursesFilterButton.getChildAt(0) as? ImageView
                coursesTextView?.setTextColor(requireContext().getColor(R.color.light_purple))
                coursesIcon?.setColorFilter(requireContext().getColor(R.color.light_purple))
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

                // Use async to load user data and subscribers count in parallel
                val userDeferred = async(Dispatchers.IO) {
                    database.usuarioDao().getUsuarioByUsername(username)
                }
                
                val subscribersDeferred = async(Dispatchers.IO) {
                    try {
                        val u = database.usuarioDao().getUsuarioByUsername(username)
                        if (u != null) {
                            database.subscriptionDao().getSubscriptionCountForCreator(u.id)
                        } else 0
                    } catch (e: Exception) { 0 }
                }

                val user = userDeferred.await()
                val subscribersCount = subscribersDeferred.await()

                val persona = if (user != null) {
                    withContext(Dispatchers.IO) {
                        database.personaDao().getPersonaById(user.personaId)
                    }
                } else null

                // Actualizar UI con información del usuario
                withContext(Dispatchers.Main) {
                    // Mostrar el nombre completo de la persona si existe, sino el username
                    if (persona != null && persona.nombres.isNotEmpty()) {
                        usernameTextView.text = "${persona.nombres} ${persona.apellidos}".trim()
                    } else {
                        usernameTextView.text = username
                    }
                    
                    subscribersCountTextView.text = subscribersCount.toString()

                    // Cargar avatar del usuario
                    loadUserAvatar(persona)
                }

                // Cargar contenido del usuario (this is already optimized with async internally)
                loadUserContent(username)
                
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

    private fun loadUserAvatar(persona: Persona?) {
        try {
            if (persona?.avatar != null && persona.avatar.isNotEmpty()) {
                val avatarPath = persona.avatar
                // Verificar si es una ruta de archivo válida
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
                        // Si el archivo no existe, usar imagen por defecto
                        userAvatarImageView.setImageResource(R.drawable.ic_profile)
                    }
                } else {
                    // Si es una URI o URL, cargar directamente
                    Glide.with(this@UserProfileViewFragment)
                        .load(avatarPath)
                        .placeholder(R.drawable.ic_profile)
                        .error(R.drawable.ic_profile)
                        .circleCrop()
                        .into(userAvatarImageView)
                }
            } else {
                // Si no hay avatar, usar imagen por defecto
                userAvatarImageView.setImageResource(R.drawable.ic_profile)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // En caso de error, usar imagen por defecto
            userAvatarImageView.setImageResource(R.drawable.ic_profile)
        }
    }    private suspend fun loadUserContent(username: String) {
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
                            val userId = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(username)
                            if (userId != null) {
                                val videos = act.syncRepository.fetchVideosByCreatorUserIdFromSupabase(userId)
                                if (videos.isNotEmpty()) videos else act.syncRepository.fetchVideosByUsernameFromSupabase(username)
                            } else {
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
                val (courses, _) = filterContentLikeExploreFragment(allVideosData, username)
                userCoursesList = courses.map { course -> com.example.tareamov.repository.CourseRepository(requireContext()).convertVideoDataToCoursePublic(course) }
            }
            if (userVideosList.isEmpty()) {
                val allVideosData = getAllContentLikeExploreFragment()
                val (_, videos) = filterContentLikeExploreFragment(allVideosData, username)
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

                // Asegurar que las miniaturas se carguen correctamente
                ensureThumbnailsLoaded()
                
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
    }    private fun handleContentClick(content: VideoData) {
        // Verificar si el click es en un curso o video basándose en el filtro actual
        when (currentFilter) {
            ContentType.COURSE -> {
                // Navegar al detalle del curso
                val bundle = Bundle().apply {
                    putLong("courseId", content.id)
                    putString("courseName", content.title)
                }
                findNavController().navigate(R.id.action_userProfileViewFragment_to_courseDetailFragment, bundle)
            }
            ContentType.VIDEO -> {
                // Navegar al VideoHomeFragment con el video específico
                val bundle = Bundle().apply {
                    putLong("videoId", content.id)
                    putString("videoTitle", content.title)
                    putString("videoUsername", content.username)
                }
                findNavController().navigate(R.id.action_userProfileViewFragment_to_videoHomeFragment, bundle)
            }
        }
    }

    private fun handleVideoClick(video: VideoData) {
        // Navegar al VideoHomeFragment con el video específico
        val bundle = Bundle().apply {
            putLong("videoId", video.id)
            putString("videoTitle", video.title)
            putString("videoUsername", video.username)
        }
        findNavController().navigate(R.id.action_userProfileViewFragment_to_videoHomeFragment, bundle)
    }

    override fun onResume() {
        super.onResume()
        // Cargar datos del usuario
        username?.let { loadUserData(it) }
    }    override fun onPause() {
        super.onPause()
        // Stop any video playback when fragment is paused
        if (::contentAdapter.isInitialized) {
            contentAdapter.stopAllVideos()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
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
        targetUsername: String
    ): Pair<List<VideoData>, List<VideoData>> {
        // Filtrar por el usuario específico
        val userContent = allContent.filter { video ->
            video.username.equals(targetUsername, ignoreCase = true)
        }
        
        Log.d("UserProfileView", "User content filtered for $targetUsername: ${userContent.size} items")
        
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
        
        // Los videos pueden ser un subconjunto de los cursos o tener lógica diferente
        val videos = userContent.filter { video ->
            // Criterio para determinar qué es un video vs curso
            // Ajusta esta lógica según las necesidades de tu aplicación
            video.localFilePath?.isNotEmpty() == true &&
            !video.description.contains("curso", ignoreCase = true)
        }
        
        return Pair(courses, videos)
    }

    // Método público para recargar el contenido del usuario (puede ser llamado externamente)
    fun refreshUserContent() {
        username?.let { loadUserData(it) }
    }
    
    // Método para asegurar que se carguen las miniaturas correctamente en ambos adaptadores
    private fun ensureThumbnailsLoaded() {
        Log.d("UserProfileView", "Ensuring thumbnails are loaded correctly")
        
        // Actualizar el adapter si está inicializado y hay contenido disponible
        if (::contentAdapter.isInitialized && allContent.isNotEmpty()) {
            contentAdapter.notifyDataSetChanged()
            contentRecyclerView.adapter?.notifyDataSetChanged()
        }
        
        if (::videoAdapter.isInitialized && allVideos.isNotEmpty()) {
            videoAdapter.notifyDataSetChanged()
        }
    }
    
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
        // Check if current user is the creator before allowing edit
        if (getCurrentUsername() != null && getCurrentUsername() == course.username) {
            Log.d("UserProfileView", "Edit course requested: ${course.title}")

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
                .setView(dialogView)
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
            Log.w("UserProfileView", "Edit denied: User is not the course creator")
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
     * Eliminar curso con confirmación - Identical to ExploreFragment deleteCourseFromTable
     */
    private fun deleteCourse(course: VideoData) {
        // Check if current user is the creator before allowing deletion
        if (getCurrentUsername() != null && getCurrentUsername() == course.username) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    // Show loading indicator
                    Toast.makeText(requireContext(), "Eliminando curso...", Toast.LENGTH_SHORT).show()

                    withContext(Dispatchers.IO) {
                        // Delete from both Course table and VideoData table for complete cleanup
                        courseRepository.deleteCourseById(course.id)

                        // Also delete from VideoData table to ensure complete removal
                        val videoData = courseRepository.getVideoById(course.id)
                        if (videoData != null) {
                            courseRepository.deleteVideo(videoData)
                            Log.d("UserProfileView", "Course also deleted from VideoData table: ${course.id}")
                        }

                        // Clean up any related thumbnails
                        val thumbnailManager = com.example.tareamov.util.ThumbnailManager(requireContext())
                        thumbnailManager.deleteThumbnail(course.id)
                    }

                    // Update UI on main thread
                    withContext(Dispatchers.Main) {
                        // Remove from local lists immediately for faster UI response
                        val courseToRemove = allContent.find { it.id == course.id }
                        if (courseToRemove != null) {
                            allContent.remove(courseToRemove)
                            allCourses.remove(courseToRemove)
                            contentAdapter.removeCourse(course.id)
                            Log.d("UserProfileView", "Course removed from local lists and adapter: ${course.title}")
                        }

                        // Update counts
                        coursesCountTextView.text = allCourses.size.toString()

                        // Refresh the content display
                        filterContent()

                        Toast.makeText(requireContext(), "✅ Curso eliminado: ${course.title}", Toast.LENGTH_SHORT).show()
                        Log.d("UserProfileView", "Course successfully deleted: ${course.title}")
                    }

                } catch (e: Exception) {
                    Log.e("UserProfileView", "Error deleting course: ${course.title}", e)
                    Toast.makeText(requireContext(), "❌ Error al eliminar el curso", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            Toast.makeText(requireContext(), "Solo el creador puede eliminar el curso", Toast.LENGTH_SHORT).show()
            Log.w("UserProfileView", "Deletion denied: User '${getCurrentUsername()}' is not the course creator '${course.username}'")
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

                Log.d("UserProfileView", "Course updated: $newTitle")
                // Reload user content to show updated data
                username?.let { loadUserData(it) }
            } catch (e: Exception) {
                Log.e("UserProfileView", "Error updating course details", e)
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
                    // Update the course with new thumbnail
                    val updatedCourse = course.copy(thumbnailUri = imageUri.toString())

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
    private fun regenerateThumbnailFromVideo(course: VideoData) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val thumbnailManager = com.example.tareamov.util.ThumbnailManager(requireContext())

                val newThumbnailPath = withContext(Dispatchers.IO) {
                    val videoUri = course.getBestVideoUri()?.toString() ?: course.videoUriString
                    if (!videoUri.isNullOrEmpty()) {
                        // Delete existing thumbnail first
                        thumbnailManager.deleteThumbnail(course.id)

                        // Generate new thumbnail
                        thumbnailManager.generateAndSaveThumbnail(videoUri, course.id)
                    } else {
                        Log.w("UserProfileView", "No video URI available for thumbnail generation: ${course.title}")
                        null
                    }
                }

                if (newThumbnailPath != null) {
                    // Update course with new thumbnail path
                    val updatedCourse = course.copy(thumbnailUri = "file://$newThumbnailPath")

                    withContext(Dispatchers.IO) {
                        videoManager.updateVideo(updatedCourse)
                        updateCourseInTable(updatedCourse)
                    }

                    Toast.makeText(requireContext(), "Miniatura regenerada desde video", Toast.LENGTH_SHORT).show()
                    Log.d("UserProfileView", "Thumbnail regenerated for: ${course.title}")

                    // Reload user content to show updated thumbnail
                    username?.let { loadUserData(it) }
                } else {
                    Toast.makeText(requireContext(), "No se pudo regenerar la miniatura", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("UserProfileView", "Error regenerating thumbnail", e)
                Toast.makeText(requireContext(), "Error al regenerar miniatura", Toast.LENGTH_SHORT).show()
            } finally {
                currentCourseForThumbnailChange = null
            }
        }
    }
    
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
        if (!sess.isAdmin()) {
            // Ocultar slot completo antes del render para que no quede hueco
            adminSlot.visibility = View.GONE
            Log.d("UserProfileViewFragment", "Admin slot hidden (user not admin)")
            return
        }

        // Usuario admin: mostrar botón y asignar listener
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
}