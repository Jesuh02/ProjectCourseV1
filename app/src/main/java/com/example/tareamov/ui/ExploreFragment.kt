package com.example.tareamov.ui
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderScriptBlur
import android.view.ViewOutlineProvider

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
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
import com.example.tareamov.util.VideoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.ImageView

class ExploreFragment : Fragment() {
    private lateinit var videoManager: VideoManager
    private lateinit var coursesAdapter: CourseAdapter
    private val coursesList = mutableListOf<Course>()
    private var currentUsername: String? = null
    private lateinit var searchEditText: EditText
    private lateinit var courseRepository: com.example.tareamov.repository.CourseRepository

    // Store all courses for filtering and search
    private var allCoursesList = mutableListOf<Course>()
    
    // Paginación
    private var currentPage = 0
    private val pageSize = 10
    private var totalCourses = 0
    private var isLoadingCourses = false
    private var hasTriggeredLoadAtPosition5 = false // Evita cargar múltiples veces al pasar curso 5

    // Variables for thumbnail change functionality
    private var currentCourseForThumbnailChange: VideoData? = null
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>
    
    // Current filter index (0=All, 1=My Created, 2=Other, 3=Premium, 4=Free, 5=Enrolled)
    private var currentFilterIndex = 0

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

        // Set up navigation back to VideoHomeFragment
        view.findViewById<ImageButton>(R.id.backButton)?.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_videoHomeFragment)
        }

        // Get current username from shared preferences
        currentUsername = getCurrentUsername()
        Log.d("ExploreFragment", "Initialized with currentUsername: $currentUsername")

        // Configurar RecyclerViews para cursos
        setupRecyclerViews(view)

        // Initialize searchEditText
        searchEditText = view.findViewById(R.id.searchEditText)

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

        // Add TextWatcher to search bar
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterCourses(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Setup filter button
        view.findViewById<View>(R.id.filterButton)?.setOnClickListener {
            showFilterOptions()
        }
        
        // Setup clear filter button (both the container and the X icon)
        view.findViewById<View>(R.id.clearFilterButton)?.setOnClickListener {
            clearActiveFilter()
        }
        
        view.findViewById<View>(R.id.activeFilterContainer)?.setOnClickListener {
            clearActiveFilter()
        }

        // Setup course observation
        setupCourseObservation()

    // Cargar los cursos (forzar fetch remoto al entrar en el fragment)
    loadCourses(forceRemote = true)

        // Initialize stats with 0 values initially
        updateCourseStats()

        // Add debug functionality - remove this in production
        view.findViewById<View>(R.id.welcomeTitle)?.setOnLongClickListener {
            showDebugStatsInfo()
            true
        }

        // Panel de navegación inferior usando ComponentBottomNavigationBinding
        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        val bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        // Resaltar solo el icono de explorar en morado
        bottomNavBinding.exploreIconImageView.setColorFilter(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.purple_500)
        )

        setupAdminButton()
        setupBottomNavigation(bottomNavBinding)
    }

    private fun setupAdminButton() {
        val bottomNavView: View = view?.findViewById(R.id.bottomNavigation) ?: return
        val bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        val adminSlot = bottomNavBinding.adminSlot
        val goToAdminButton = bottomNavBinding.goToAdminButton

        // Inicializa como INVISIBLE para evitar salto al inflar
        goToAdminButton.visibility = View.INVISIBLE

        val sess = com.example.tareamov.util.SessionManager.getInstance(requireContext())
        if (!sess.isAdmin()) {
            // Ocultar el slot antes del render para que no quede hueco visible
            adminSlot.visibility = View.GONE
            return
        }

        // Usuario admin: mostrar botón y asignar listener
        goToAdminButton.visibility = View.VISIBLE
        goToAdminButton.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_homeFragment)
        }
    }

    private fun setupBottomNavigation(bottomNavBinding: ComponentBottomNavigationBinding) {
        bottomNavBinding.homeNavLayout.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_videoHomeFragment)
        }
        bottomNavBinding.exploreButton.setOnClickListener {
            // Ya estás en Explorar, puedes dejarlo vacío o recargar
        }
        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_contentUploadFragment)
        }
        bottomNavBinding.activityButton.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_notificacionesFragment)
        }
        bottomNavBinding.profileNavButton.setOnClickListener {
            findNavController().navigate(R.id.action_exploreFragment_to_profileFragment)
        }
    }

    // Setup real-time observation of course changes - DISABLED to force Supabase loading
    private fun setupCourseObservation() {
        // Observation disabled - we now load all courses directly from Supabase
        // This avoids showing stale Room data (only 7 courses) instead of full Supabase data (104+ courses)
        Log.d("ExploreFragment", "Course observation from Room DISABLED - using Supabase direct fetch")
    }

    // Generate thumbnails preventively for videos without them
    private fun generatePreventiveThumbnails(videoDataList: List<VideoData>) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val thumbnailManager = com.example.tareamov.util.ThumbnailManager(requireContext())
                videoDataList.forEach { videoData ->
                    try {
                        // Only generate thumbnails if no thumbnail exists and user owns the course
                        if ((videoData.thumbnailUri.isNullOrEmpty() || !thumbnailManager.thumbnailExists(videoData.id)) &&
                            canUserModifyCourse(videoData)) {
                            val videoUri = videoData.getBestVideoUri()?.toString() ?: videoData.videoUriString
                            if (!videoUri.isNullOrEmpty()) {
                                val thumbnailPath = thumbnailManager.ensureThumbnailExists(videoUri, videoData.id)
                                if (thumbnailPath != null) {
                                    // Update the corresponding course with thumbnail
                                    val correspondingCourse = allCoursesList.find { it.id == videoData.id }
                                    if (correspondingCourse != null && canUserModifyCourse(correspondingCourse)) {
                                        val updatedCourse = correspondingCourse.copy(thumbnailUri = "file://$thumbnailPath")
                                        courseRepository.updateCourse(updatedCourse)
                                        Log.d("ExploreFragment", "Generated preventive thumbnail for owned course: ${videoData.title}")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ExploreFragment", "Error generating preventive thumbnail for: ${videoData.title}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error in generatePreventiveThumbnails", e)
            }
        }
    }

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
                    // Unsubscribe
                    db.subscriptionDao().unsubscribeFromCreator(currentUserId, creatorUserId)
                    
                    // Also sync to Supabase
                    syncUnsubscriptionToSupabase(currentUserId, creatorUserId)
                    
                    showDarkToast("✅ Te has desuscrito")
                    Log.d("ExploreFragment", "User $currentUserId unsubscribed from $creatorUserId")
                } else {
                    // Subscribe
                    db.subscriptionDao().subscribeToCreator(currentUserId, creatorUserId)
                    
                    // Also sync to Supabase
                    syncSubscriptionToSupabase(currentUserId, creatorUserId)
                    
                    showDarkToast("🎉 Te has suscrito")
                    Log.d("ExploreFragment", "User $currentUserId subscribed to $creatorUserId")
                }
                
                // Refresh the adapter to update subscription states
                coursesAdapter.notifyDataSetChanged()
                
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error handling subscription", e)
                showDarkToast("❌ Error al procesar la suscripción")
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
        coursesRecyclerView.setHasFixedSize(true)
        coursesRecyclerView.setItemViewCacheSize(100)

        Log.d("ExploreFragment", "Setting up adapter with currentUsername: $currentUsername")

        coursesAdapter = CourseAdapter(
            requireContext(),
            coursesList,
            onCourseClickListener = { course ->
                lifecycleScope.launch {
                    val creatorUsername = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId)
                    }
                    Log.d("ExploreFragment", "Course clicked: ${course.title} by $creatorUsername")
                    navigateToCourseDetail(course)
                }
            },
            currentUsername = currentUsername, // Pass current username for subscription logic
            onSubscriptionClickListener = { course, isCurrentlySubscribed ->
                handleSubscriptionClick(course, isCurrentlySubscribed)
            },
            onEditClickListener = { course ->
                lifecycleScope.launch {
                    val videoData = convertCourseToVideoData(course)
                    editCourse(videoData)
                }
            },
            onDeleteClickListener = { course ->
                lifecycleScope.launch {
                    val creatorUsername = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId)
                    }
                    deleteCourseFromTable(course.id, creatorUsername ?: "")
                }
            },
            onThumbnailChangeClickListener = { course ->
                lifecycleScope.launch {
                    val videoData = convertCourseToVideoData(course)
                    showThumbnailChangeOptions(videoData)
                }
            },
            onEnrollClickListener = { course ->
                handleEnrollmentClick(course)
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
                        loadMoreCourses()
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
    private fun deleteCourseFromTable(courseId: Long, creatorUsername: String) {
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

                        // Clean up any related thumbnails
                        val thumbnailManager = com.example.tareamov.util.ThumbnailManager(requireContext())
                        thumbnailManager.deleteThumbnail(courseId)
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
        
        return if (currentUserId != null) {
            allCoursesList.filter { it.creatorUserId == currentUserId }
        } else {
            emptyList()
        }
    }

    // Get courses NOT created by current user (for viewing only)
    private suspend fun getOtherUsersCourses(): List<Course> {
        if (currentUsername == null) return allCoursesList
        
        val currentUserId = withContext(Dispatchers.IO) {
            com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
        }
        
        return if (currentUserId != null) {
            allCoursesList.filter { it.creatorUserId != currentUserId }
        } else {
            allCoursesList
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

    // Public method to force thumbnail generation for all courses
    fun forceRegenerateThumbnails() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    allCoursesList.forEach { course ->
                        try {
                            // Only regenerate thumbnails for courses owned by current user
                            if (canUserModifyCourse(course)) {
                                val videoData = convertCourseToVideoData(course)
                                val videoUri = videoData.getBestVideoUri()?.toString() ?: videoData.videoUriString
                                if (!videoUri.isNullOrEmpty()) {
                                    val thumbnailManager = com.example.tareamov.util.ThumbnailManager(requireContext())
                                    val thumbnailPath = thumbnailManager.generateAndSaveThumbnail(videoUri, course.id)
                                    if (thumbnailPath != null) {
                                        val updatedCourse = course.copy(thumbnailUri = "file://$thumbnailPath")
                                        courseRepository.updateCourse(updatedCourse)
                                        Log.d("ExploreFragment", "Regenerated thumbnail for owned course: ${course.title}")
                                    }
                                }
                            } else {
                                Log.d("ExploreFragment", "Skipped thumbnail regeneration for non-owned course: ${course.title}")
                            }
                        } catch (e: Exception) {
                            Log.e("ExploreFragment", "Error regenerating thumbnail for: ${course.title}", e)
                        }
                    }
                }
                // Reload courses to show updated thumbnails
                loadCourses()
                Log.d("ExploreFragment", "Thumbnail regeneration completed for user's courses")
            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error during thumbnail regeneration", e)
            }
        }
    }

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
                    // Update the course with new thumbnail
                    val updatedCourse = course.copy(thumbnailUri = imageUri.toString())

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

            dialogBuilder
                .setTitle("🖼️ Cambiar Miniatura")
                .setMessage("¿Qué deseas hacer con la miniatura del curso \"${course.title}\"?\n\n" +
                        "📱 Seleccionar: Elige una imagen de tu galería")
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

    // Open image picker for thumbnail selection
    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        imagePickerLauncher.launch(intent)
    }

    // Regenerate thumbnail from video
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
                    } else null
                }

                if (newThumbnailPath != null) {
                    val updatedCourse = course.copy(thumbnailUri = "file://$newThumbnailPath")

                    withContext(Dispatchers.IO) {
                        // Update in VideoData table
                        videoManager.updateVideo(updatedCourse)

                        // Update in Course table
                        updateCourseInTable(updatedCourse)
                    }

                    Log.d("ExploreFragment", "Thumbnail regenerated for course: ${course.title}")
                    Toast.makeText(requireContext(), "Miniatura regenerada", Toast.LENGTH_SHORT).show()

                    // Reload courses to show updated thumbnail
                    loadCourses()
                } else {
                    Toast.makeText(requireContext(), "Error al regenerar miniatura", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error regenerating thumbnail", e)
                Toast.makeText(requireContext(), "Error al regenerar miniatura", Toast.LENGTH_SHORT).show()
            } finally {
                currentCourseForThumbnailChange = null
            }
        }
    }    /**
     * Load courses with pagination (10 at a time)
     * Uses Supabase pagination for better performance
     */
    private fun loadCourses(forceRemote: Boolean = false) {
        if (isLoadingCourses) {
            Log.d("ExploreFragment", "Already loading courses, skipping")
            return
        }

        isLoadingCourses = true
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d("ExploreFragment", "loadCourses: Starting to load courses from Supabase (forceRemote=$forceRemote)")
                
                // Load ALL courses from Supabase for filtering (but display paginated)
                val allCourses = withContext(Dispatchers.IO) {
                    Log.d("ExploreFragment", "loadCourses: Calling SupabaseClient.fetchCourses()")
                    val courses = com.example.tareamov.service.SupabaseClient.fetchCourses()
                    Log.d("ExploreFragment", "loadCourses: Received ${courses.size} courses from Supabase")
                    
                    // Log unique course titles to verify diversity
                    val uniqueTitles = courses.map { it.title }.distinct()
                    Log.d("ExploreFragment", "loadCourses: Unique course titles: ${uniqueTitles.size}")
                    uniqueTitles.take(10).forEachIndexed { index, title ->
                        Log.d("ExploreFragment", "  #$index: $title")
                    }
                    
                    // Log creator IDs to check diversity
                    val uniqueCreators = courses.map { it.creatorUserId }.distinct()
                    Log.d("ExploreFragment", "loadCourses: Unique creator IDs: ${uniqueCreators.joinToString(", ")}")
                    
                    courses
                }
                
                totalCourses = allCourses.size
                currentPage = 0
                hasTriggeredLoadAtPosition5 = false
                
                Log.d("ExploreFragment", "loadCourses: Total courses fetched = $totalCourses")
                
                withContext(Dispatchers.Main) {
                    // Store ALL courses sorted by most recent for filtering
                    val sortedCourses = allCourses.sortedByDescending { it.timestamp }
                    allCoursesList.clear()
                    allCoursesList.addAll(sortedCourses)
                    
                    Log.d("ExploreFragment", "loadCourses: Stored ${allCoursesList.size} courses in allCoursesList")
                    
                    // Display ALL courses immediately (no pagination needed for small lists)
                    coursesList.clear()
                    coursesList.addAll(sortedCourses)
                    
                    Log.d("ExploreFragment", "loadCourses: Displaying ${coursesList.size} courses in RecyclerView")
                    
                    // Log first few courses for verification
                    coursesList.take(5).forEachIndexed { index, course ->
                        Log.d("ExploreFragment", "  Course #$index: ${course.title} (ID: ${course.id}, creatorUserId: ${course.creatorUserId})")
                    }
                    
                    if (::coursesAdapter.isInitialized) {
                        coursesAdapter.updateCourses(coursesList)
                        Log.d("ExploreFragment", "loadCourses: Adapter updated with ${coursesList.size} courses")
                    } else {
                        Log.w("ExploreFragment", "loadCourses: coursesAdapter not initialized yet!")
                    }
                    
                    updateCourseStats()
                    Log.d("ExploreFragment", "Loaded and displaying all ${allCourses.size} courses")
                }

            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error loading courses", e)
                Toast.makeText(context, "Error cargando cursos: ${e.message}", Toast.LENGTH_SHORT).show()
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
        // All courses are already loaded and displayed, no pagination needed
        Log.d("ExploreFragment", "All courses already loaded (${coursesList.size} courses displayed)")
        return
    }

    /**
     * Helper function to display courses in the UI
     * Shows all loaded courses and updates adapter
     */
    private fun displayCourses(courses: List<Course>) {
        // Sort by timestamp DESC (most recent first)
        val sortedCourses = courses.sortedByDescending { it.timestamp }
        
        // Store ALL courses for search (only when showing all, not when filtering)
        if (currentFilterIndex == 0) {
            allCoursesList.clear()
            allCoursesList.addAll(sortedCourses)
        }
        
        // Show loaded courses in UI
        coursesList.clear()
        coursesList.addAll(sortedCourses)
        
        // Update adapter
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(sortedCourses)
            Log.d("ExploreFragment", "displayCourses: Updated adapter with ${sortedCourses.size} courses")
        }
        
        // Update stats based on currently displayed courses
        updateCourseStats()
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

    // Filter courses by name or category
    private fun filterCourses(query: String) {
        if (query.isBlank()) {
            // When search is empty, show all loaded courses sorted by most recent
            val sorted = allCoursesList.sortedByDescending { it.timestamp }
            coursesList.clear()
            coursesList.addAll(sorted)
            Log.d("ExploreFragment", "filterCourses -> query empty, showing all ${sorted.size} courses")
            if (::coursesAdapter.isInitialized) {
                coursesAdapter.updateCourses(coursesList)
            }
            updateCourseStats()
        } else {
            // When searching, search remotely in Supabase
            Log.d("ExploreFragment", "filterCourses -> query='$query', searching remotely...")
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val searchResults = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.searchCourses(query)
                    }
                    
                    withContext(Dispatchers.Main) {
                        coursesList.clear()
                        coursesList.addAll(searchResults.sortedByDescending { it.timestamp })
                        Log.d("ExploreFragment", "filterCourses -> found ${searchResults.size} results")
                        if (::coursesAdapter.isInitialized) {
                            coursesAdapter.updateCourses(coursesList)
                        }
                        updateCourseStats()
                    }
                } catch (e: Exception) {
                    Log.e("ExploreFragment", "Error searching courses remotely", e)
                    // Fallback to local search
                    val filtered = allCoursesList.filter { course ->
                        course.title.contains(query, ignoreCase = true) ||
                                course.description?.contains(query, ignoreCase = true) == true ||
                                course.category?.contains(query, ignoreCase = true) == true
                    }.sortedByDescending { it.timestamp }
                    coursesList.clear()
                    coursesList.addAll(filtered)
                    if (::coursesAdapter.isInitialized) {
                        coursesAdapter.updateCourses(coursesList)
                    }
                    updateCourseStats()
                }
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
        val premiumCourses = allCoursesList
            .filter { it.isPremium == true }
            .sortedByDescending { it.timestamp }
        coursesList.clear()
        coursesList.addAll(premiumCourses)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(coursesList)
        } else {
            Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
        }
        updateCourseStats()
        updateActiveFilterUI("Cursos Premium")
        
        if (premiumCourses.isEmpty()) {
            showDarkToast("No hay cursos premium disponibles")
        } else {
            showDarkToast("Mostrando ${premiumCourses.size} cursos premium")
        }
        
        Log.d("ExploreFragment", "Filtered to show premium courses: ${premiumCourses.size} courses")
    }

    // Filter free courses
    private fun filterFreeCourses() {
        val freeCourses = allCoursesList
            .filter { it.isPremium != true }
            .sortedByDescending { it.timestamp }
        coursesList.clear()
        coursesList.addAll(freeCourses)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(coursesList)
        } else {
            Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
        }
        updateCourseStats()
        updateActiveFilterUI("Cursos Gratis")
        
        if (freeCourses.isEmpty()) {
            showDarkToast("No hay cursos gratuitos disponibles")
        } else {
            showDarkToast("Mostrando ${freeCourses.size} cursos gratis")
        }
        
        Log.d("ExploreFragment", "Filtered to show free courses: ${freeCourses.size} courses")
    }

    // Filter enrolled courses
    private fun filterEnrolledCourses() {
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

                val enrolledIds = com.example.tareamov.service.SupabaseClient.fetchEnrolledCourseIds(userId)
                
                withContext(Dispatchers.Main) {
                    val filtered = allCoursesList.filter { course ->
                        enrolledIds.contains(course.id)
                    }
                    displayCourses(filtered)
                    
                    val count = filtered.size
                    showDarkToast("Mostrando $count cursos inscritos")
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

    // Update course statistics in header based on currently displayed courses
    private fun updateCourseStats() {
        view?.let { v ->
            val totalCoursesCount = v.findViewById<TextView>(R.id.totalCoursesCount)
            val popularCoursesCount = v.findViewById<TextView>(R.id.popularCoursesCount)
            val newCoursesCount = v.findViewById<TextView>(R.id.newCoursesCount)

            Log.d("ExploreFragment", "Updating course stats - Displayed courses: ${coursesList.size}")

            // Show count of currently displayed courses
            totalCoursesCount?.text = coursesList.size.toString()
            
            // Count popular courses (rating >= 4.5 or enrollments >= 10) from displayed courses
            val popularCount = coursesList.count { course ->
                course.rating >= 4.5 || (course.enrollmentCount ?: 0) >= 10
            }
            popularCoursesCount?.text = popularCount.toString()
            Log.d("ExploreFragment", "Popular courses count: $popularCount")
            
            // Count recent courses (last 30 days) from displayed courses as "new"
            val currentTime = System.currentTimeMillis()
            val thirtyDaysAgo = currentTime - (30L * 24 * 60 * 60 * 1000)
            val newCount = coursesList.count { course ->
                val courseTime = course.timestamp
                val creationTime = course.creationDate?.let { parseDate(it) } ?: 0
                val mostRecentTime = maxOf(courseTime, creationTime)
                val isNew = mostRecentTime > thirtyDaysAgo
                isNew
            }
            newCoursesCount?.text = newCount.toString()
            Log.d("ExploreFragment", "New courses count (last 30 days): $newCount")

            Log.d("ExploreFragment", "Stats updated - Displayed: ${coursesList.size}, Popular: $popularCount, New: $newCount")
        } ?: run {
            Log.w("ExploreFragment", "View is null, cannot update course stats")
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

    // Filter courses to show only user's own courses
    private fun filterMyCoursesOnly() {
        lifecycleScope.launch {
            try {
                val myCoursesOnly = getUserOwnedCourses()
                val sorted = myCoursesOnly.sortedByDescending { it.timestamp }
                coursesList.clear()
                coursesList.addAll(sorted)
                if (::coursesAdapter.isInitialized) {
                    coursesAdapter.updateCourses(coursesList)
                } else {
                    Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
                }
                updateCourseStats()
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
                val otherCourses = getOtherUsersCourses()
                val sorted = otherCourses.sortedByDescending { it.timestamp }
                coursesList.clear()
                coursesList.addAll(sorted)
                if (::coursesAdapter.isInitialized) {
                    coursesAdapter.updateCourses(coursesList)
                } else {
                    Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
                }
                updateCourseStats()
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
        showAllCourses()
        updateActiveFilterUI(null)
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
        val activeFilterContainer = view?.findViewById<View>(R.id.activeFilterContainer)
        val activeFilterText = view?.findViewById<TextView>(R.id.activeFilterText)
        
        if (filterName != null) {
            activeFilterContainer?.visibility = View.VISIBLE
            activeFilterText?.text = filterName
        } else {
            activeFilterContainer?.visibility = View.GONE
        }
    }
}