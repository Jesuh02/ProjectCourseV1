package com.example.tareamov.ui
import com.example.tareamov.databinding.ComponentBottomNavigationBinding

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

    // Setup real-time observation of course changes
    private fun setupCourseObservation() {
        viewLifecycleOwner.lifecycleScope.launch {
            // Observe courses directly from Course table
            courseRepository.getAllCoursesFlow().collect { courses ->
                Log.d("ExploreFragment", "Observed ${courses.size} courses from Course table")
                // Keep Course table list sorted newest -> oldest
                val sortedCourses = courses.sortedByDescending { it.timestamp }
                allCoursesList.clear()
                allCoursesList.addAll(sortedCourses)

                // Filter courses and update stats
                filterCourses(searchEditText.text.toString())
                
                // Force update stats even if filter didn't change anything
                updateCourseStats()
            }
        }
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
    private fun convertCourseToVideoData(course: Course): VideoData {
        val usernameSafe = course.creatorUsername ?: ""
        if (course.creatorUsername == null) {
            Log.w("ExploreFragment", "convertCourseToVideoData: course.id=${course.id} has null creatorUsername, using empty username")
        }
        return VideoData(
            id = course.id,
            username = usernameSafe,
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
     * Handle subscription/unsubscription clicks
     */
    private fun handleSubscriptionClick(course: Course, isCurrentlySubscribed: Boolean) {
        if (currentUsername == null) {
            showDarkToast("⚠️ Debes iniciar sesión para suscribirte")
            return
        }

        if (currentUsername == course.creatorUsername) {
            showDarkToast("❌ No puedes suscribirte a tu propio curso")
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                
                if (isCurrentlySubscribed) {
                    // Unsubscribe
                    db.subscriptionDao().unsubscribeFromCreator(currentUsername!!, course.creatorUsername ?: "")
                    
                    // Also sync to Supabase
                    syncUnsubscriptionToSupabase(currentUsername!!, course.creatorUsername ?: "")
                    
                    showDarkToast("✅ Te has desuscrito de ${course.creatorUsername}")
                    Log.d("ExploreFragment", "User $currentUsername unsubscribed from ${course.creatorUsername}")
                } else {
                    // Subscribe
                    db.subscriptionDao().subscribeToCreator(currentUsername!!, course.creatorUsername ?: "")
                    
                    // Also sync to Supabase
                    syncSubscriptionToSupabase(currentUsername!!, course.creatorUsername ?: "")
                    
                    showDarkToast("🎉 Te has suscrito a ${course.creatorUsername}")
                    Log.d("ExploreFragment", "User $currentUsername subscribed to ${course.creatorUsername}")
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
        
        if (currentUsername == course.creatorUsername) {
            showDarkToast("No puedes inscribirte en tu propio curso")
            return
        }
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                
                // Check if already enrolled
                val existingProgreso = withContext(Dispatchers.IO) {
                    db.progresoEstudianteDao().getProgreso(currentUsername!!, course.id)
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
                    usuarioEstudiante = currentUsername!!,
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

        Log.d("ExploreFragment", "Setting up adapter with currentUsername: $currentUsername")

        coursesAdapter = CourseAdapter(
            requireContext(),
            coursesList,
            onCourseClickListener = { course ->
                Log.d("ExploreFragment", "Course clicked: ${course.title} by ${course.creatorUsername}")
                navigateToCourseDetail(course)
            },
            currentUsername = currentUsername, // Pass current username for subscription logic
            onSubscriptionClickListener = { course, isCurrentlySubscribed ->
                handleSubscriptionClick(course, isCurrentlySubscribed)
            },
            onEditClickListener = { course ->
                editCourse(convertCourseToVideoData(course))
            },
            onDeleteClickListener = { course ->
                deleteCourseFromTable(course.id, course.creatorUsername ?: "")
            },
            onThumbnailChangeClickListener = { course ->
                showThumbnailChangeOptions(convertCourseToVideoData(course))
            },
            onEnrollClickListener = { course ->
                handleEnrollmentClick(course)
            }
        )
        coursesRecyclerView.adapter = coursesAdapter

        // Agregar ScrollListener para paginación
        coursesRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
                if (layoutManager != null && dy > 0) { // Solo cuando se hace scroll hacia abajo
                    val lastVisibleItemPosition = layoutManager.findLastVisibleItemPosition()

                    // Cargar más cuando llegue al curso 5 (posición 4, porque empieza en 0)
                    if (!isLoadingCourses && 
                        coursesList.size < totalCourses &&
                        lastVisibleItemPosition >= 4 &&
                        !hasTriggeredLoadAtPosition5) {
                        Log.d("ExploreFragment", "User scrolled to course 5 (position $lastVisibleItemPosition), loading more courses...")
                        hasTriggeredLoadAtPosition5 = true
                        loadMoreCourses()
                    }
                    
                    // Después de cargar más, resetear el flag cuando pase la posición 10
                    if (hasTriggeredLoadAtPosition5 && lastVisibleItemPosition >= 14) {
                        hasTriggeredLoadAtPosition5 = false
                        Log.d("ExploreFragment", "Reset load trigger for next batch")
                    }
                }
            }
            
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    // Cuando el scroll se detiene, permitir que el video visible se reproduzca
                    Log.d("ExploreFragment", "Scroll stopped, allowing video playback")
                }
            }
        })
    }

    private fun navigateToCourseDetail(course: Course) {
        // Check if current user is the course creator
        val isCreator = currentUsername != null && currentUsername == course.creatorUsername

        val bundle = Bundle().apply {
            putLong("courseId", course.id)
            putString("courseName", course.title)
            putBoolean("isCreator", isCreator)
        }
        findNavController().navigate(R.id.action_exploreFragment_to_courseDetailFragment, bundle)
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
    private fun canUserModifyCourse(course: Course): Boolean {
        val canModify = currentUsername != null && currentUsername == course.creatorUsername
        Log.d("ExploreFragment", "Can user modify course entity '${course.title}'? $canModify (Current: '$currentUsername', Course Creator: '${course.creatorUsername}')")
        return canModify
    }

    // Get courses created by current user only
    private fun getUserOwnedCourses(): List<Course> {
        return if (currentUsername != null) {
            allCoursesList.filter { it.creatorUsername == currentUsername }
        } else {
            emptyList()
        }
    }

    // Get courses NOT created by current user (for viewing only)
    private fun getOtherUsersCourses(): List<Course> {
        return if (currentUsername != null) {
            allCoursesList.filter { it.creatorUsername != currentUsername }
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
     * OPTIMIZED: Uses Supabase pagination endpoint directly
     */
    private fun loadCourses(forceRemote: Boolean = false) {
        if (isLoadingCourses) {
            Log.d("ExploreFragment", "Already loading courses, skipping")
            return
        }

        isLoadingCourses = true
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d("ExploreFragment", "loadCourses: Loading initial courses (page 0)")
                
                val (courses, total) = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.fetchCoursesSummary(
                        limit = pageSize,
                        offset = 0,
                        orderBy = "timestamp",
                        direction = "desc"
                    )
                }

                totalCourses = total
                currentPage = 0
                hasTriggeredLoadAtPosition5 = false // Resetear flag al cargar inicial
                
                withContext(Dispatchers.Main) {
                    allCoursesList.clear()
                    allCoursesList.addAll(courses)
                    
                    coursesList.clear()
                    coursesList.addAll(courses)
                    
                    if (::coursesAdapter.isInitialized) {
                        coursesAdapter.updateCourses(coursesList)
                    }
                    
                    updateCourseStats()
                    Log.d("ExploreFragment", "Loaded ${courses.size} courses (total: $totalCourses)")
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
     * Load more courses (next page)
     */
    private fun loadMoreCourses() {
        if (isLoadingCourses) {
            Log.d("ExploreFragment", "Already loading courses, skipping")
            return
        }

        isLoadingCourses = true
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val nextPage = currentPage + 1
                val offset = nextPage * pageSize
                
                Log.d("ExploreFragment", "loadMoreCourses: Loading page $nextPage (offset $offset)")

                val (courses, _) = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.fetchCoursesSummary(
                        limit = pageSize,
                        offset = offset,
                        orderBy = "timestamp",
                        direction = "desc"
                    )
                }

                if (courses.isNotEmpty()) {
                    currentPage = nextPage
                    
                    withContext(Dispatchers.Main) {
                        allCoursesList.addAll(courses)
                        
                        val oldSize = coursesList.size
                        coursesList.addAll(courses)
                        
                        if (::coursesAdapter.isInitialized) {
                            coursesAdapter.notifyItemRangeInserted(oldSize, courses.size)
                        }
                        
                        Log.d("ExploreFragment", "Loaded ${courses.size} more courses (total now: ${coursesList.size}/$totalCourses)")
                    }
                } else {
                    Log.d("ExploreFragment", "No more courses to load")
                }

            } catch (e: Exception) {
                Log.e("ExploreFragment", "Error loading more courses", e)
            } finally {
                isLoadingCourses = false
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
        
        // Store ALL courses for search
        allCoursesList.clear()
        allCoursesList.addAll(sortedCourses)
        
        // Show ALL loaded courses (no limit)
        coursesList.clear()
        coursesList.addAll(sortedCourses)
        
        // Update adapter
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(sortedCourses)
            Log.d("ExploreFragment", "displayCourses: Updated adapter with ${sortedCourses.size} courses (Total in Supabase: $totalCourses)")
        }
        
        // Update stats
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
        val filtered = if (query.isBlank()) {
            // When search is empty, show the most recent 10 courses
            allCoursesList.sortedByDescending { it.timestamp }.take(10)
        } else {
            // When searching, search in ALL courses (not just top 10)
            allCoursesList.filter { course ->
                course.title.contains(query, ignoreCase = true) ||
                        course.description?.contains(query, ignoreCase = true) == true ||
                        course.creatorUsername?.contains(query, ignoreCase = true) == true
            }
        }
        coursesList.clear()
        coursesList.addAll(filtered)
        Log.d("ExploreFragment", "filterCourses -> query='$query' filteredSize=${filtered.size} allCourses=${allCoursesList.size} coursesList=${coursesList.size}")
        if (::coursesAdapter.isInitialized) {
            Log.d("ExploreFragment", "filterCourses -> updating adapter with ${coursesList.size} items")
            coursesAdapter.updateCourses(coursesList)
        } else {
            Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
        }
        updateCourseStats()
    }

    // Show filter options dialog
    private fun showFilterOptions() {
        val options = arrayOf(
            "📚 Todos los cursos",
            "👤 Mis cursos",
            "🌟 Cursos de otros",
            "💰 Cursos premium",
            "🆓 Cursos gratuitos"
        )

        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(
            androidx.appcompat.view.ContextThemeWrapper(requireContext(), R.style.DarkAlertDialogTheme)
        )

        dialogBuilder
            .setTitle("🔍 Filtrar Cursos")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showAllCourses()
                    1 -> filterMyCoursesOnly()
                    2 -> filterOtherCoursesOnly()
                    3 -> filterPremiumCourses()
                    4 -> filterFreeCourses()
                }
            }
            .setNegativeButton("❌ Cancelar", null)

        val dialog = dialogBuilder.create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.dark_dialog_background)
        }
        dialog.show()
    }

    // Filter premium courses
    private fun filterPremiumCourses() {
        val premiumCourses = allCoursesList.filter { it.isPremium == true }
        coursesList.clear()
        coursesList.addAll(premiumCourses)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(coursesList)
        } else {
            Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
        }
        updateCourseStats()
        Log.d("ExploreFragment", "Filtered to show premium courses: ${premiumCourses.size} courses")
    }

    // Filter free courses
    private fun filterFreeCourses() {
        val freeCourses = allCoursesList.filter { it.isPremium != true }
        coursesList.clear()
        coursesList.addAll(freeCourses)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(coursesList)
        } else {
            Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
        }
        updateCourseStats()
        Log.d("ExploreFragment", "Filtered to show free courses: ${freeCourses.size} courses")
    }

    // Update course statistics in header
    private fun updateCourseStats() {
        view?.let { v ->
            val totalCoursesCount = v.findViewById<TextView>(R.id.totalCoursesCount)
            val popularCoursesCount = v.findViewById<TextView>(R.id.popularCoursesCount)
            val newCoursesCount = v.findViewById<TextView>(R.id.newCoursesCount)

            Log.d("ExploreFragment", "Updating course stats - Loaded courses: ${coursesList.size}, Total in Supabase: $totalCourses")

            // Show TOTAL courses from Supabase (not just loaded ones)
            totalCoursesCount?.text = totalCourses.toString()
            
            // Count premium courses from loaded courses as "popular"
            val premiumCount = allCoursesList.count { it.isPremium == true }
            popularCoursesCount?.text = premiumCount.toString()
            Log.d("ExploreFragment", "Premium courses count: $premiumCount")
            
            // Count recent courses (last 7 days) from loaded courses as "new"
            val currentTime = System.currentTimeMillis()
            val sevenDaysAgo = currentTime - (7 * 24 * 60 * 60 * 1000)
            val newCount = allCoursesList.count { 
                val courseTime = it.timestamp
                val isNew = courseTime > sevenDaysAgo
                Log.d("ExploreFragment", "Course '${it.title}' timestamp: $courseTime, current: $currentTime, isNew: $isNew")
                isNew
            }
            newCoursesCount?.text = newCount.toString()
            Log.d("ExploreFragment", "New courses count (last 7 days): $newCount")

            // Additional debug info
            if (allCoursesList.isNotEmpty()) {
                Log.d("ExploreFragment", "Sample course timestamps:")
                allCoursesList.take(3).forEach { course ->
                    val daysDiff = (currentTime - course.timestamp) / (24 * 60 * 60 * 1000)
                    Log.d("ExploreFragment", "- '${course.title}': ${course.timestamp} (${daysDiff} days ago)")
                }
            }

            Log.d("ExploreFragment", "Stats updated - Total in Supabase: $totalCourses, Loaded: ${coursesList.size}, Premium: $premiumCount, New: $newCount")
        } ?: run {
            Log.w("ExploreFragment", "View is null, cannot update course stats")
        }
    }

    // Filter courses to show only user's own courses
    private fun filterMyCoursesOnly() {
        val myCoursesOnly = getUserOwnedCourses()
        coursesList.clear()
        coursesList.addAll(myCoursesOnly)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(coursesList)
        } else {
            Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
        }
        updateCourseStats()
        Log.d("ExploreFragment", "Filtered to show only user's courses: ${myCoursesOnly.size} courses")
    }

    // Filter courses to show only other users' courses
    private fun filterOtherCoursesOnly() {
        val otherCourses = getOtherUsersCourses()
        coursesList.clear()
        coursesList.addAll(otherCourses)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(coursesList)
        } else {
            Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
        }
        updateCourseStats()
        Log.d("ExploreFragment", "Filtered to show only other users' courses: ${otherCourses.size} courses")
    }

    // Show all courses (reset filter)
    private fun showAllCourses() {
        coursesList.clear()
        coursesList.addAll(allCoursesList)
        if (::coursesAdapter.isInitialized) {
            coursesAdapter.updateCourses(coursesList)
        } else {
            Log.w("ExploreFragment", "coursesAdapter not initialized yet; skipping updateCourses")
        }
        updateCourseStats()
        Log.d("ExploreFragment", "Showing all courses: ${allCoursesList.size} courses")
    }    override fun onResume() {
        super.onResume()
    // Reload courses when returning to this fragment (force remote refresh)
    loadCourses(forceRemote = true)
        // Sync any changes from RecyclerView to Course table
        syncCoursesToTable()
        // Update stats when resuming
        updateCourseStats()
    }

    override fun onPause() {
        super.onPause()
        // No video playback to stop in course cards
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Clean up any resources - no video playback to stop in course cards
    }

    /**
     * Sync subscription to Supabase
     */
    private suspend fun syncSubscriptionToSupabase(subscriberUsername: String, creatorUsername: String) {
        try {
            val supabaseClient = com.example.tareamov.service.SupabaseClient
            supabaseClient.subscribeToCreator(subscriberUsername, creatorUsername)
            Log.d("ExploreFragment", "Subscription synced to Supabase: $subscriberUsername -> $creatorUsername")
        } catch (e: Exception) {
            Log.e("ExploreFragment", "Error syncing subscription to Supabase", e)
            // Don't throw - local subscription is more important
        }
    }

    /**
     * Sync unsubscription to Supabase
     */
    private suspend fun syncUnsubscriptionToSupabase(subscriberUsername: String, creatorUsername: String) {
        try {
            val supabaseClient = com.example.tareamov.service.SupabaseClient
            supabaseClient.unsubscribeFromCreator(subscriberUsername, creatorUsername)
            Log.d("ExploreFragment", "Unsubscription synced to Supabase: $subscriberUsername -> $creatorUsername")
        } catch (e: Exception) {
            Log.e("ExploreFragment", "Error syncing unsubscription to Supabase", e)
            // Don't throw - local unsubscription is more important
        }
    }
    
    // Debug function to show detailed stats info - remove in production
    private fun showDebugStatsInfo() {
        val currentTime = System.currentTimeMillis()
        val sevenDaysAgo = currentTime - (7 * 24 * 60 * 60 * 1000)
        
        val debugInfo = StringBuilder()
        debugInfo.append("📊 ESTADÍSTICAS DE CURSOS\n\n")
        debugInfo.append("🔢 Total de cursos: ${allCoursesList.size}\n")
        debugInfo.append("💎 Cursos premium: ${allCoursesList.count { it.isPremium }}\n")
        debugInfo.append("🆓 Cursos gratuitos: ${allCoursesList.count { !it.isPremium }}\n")
        debugInfo.append("🆕 Cursos nuevos (7 días): ${allCoursesList.count { it.timestamp > sevenDaysAgo }}\n\n")
        
        if (allCoursesList.isNotEmpty()) {
            debugInfo.append("📋 DETALLES DE CURSOS:\n")
            allCoursesList.take(5).forEach { course ->
                val daysDiff = (currentTime - course.timestamp) / (24 * 60 * 60 * 1000)
                val isPremium = if (course.isPremium) "💎" else "🆓"
                debugInfo.append("$isPremium ${course.title} (${daysDiff} días)\n")
            }
        } else {
            debugInfo.append("❌ No hay cursos cargados\n")
        }
        
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(
            androidx.appcompat.view.ContextThemeWrapper(requireContext(), R.style.DarkAlertDialogTheme)
        )

        dialogBuilder
            .setTitle("🔍 Debug - Estadísticas")
            .setMessage(debugInfo.toString())
            .setPositiveButton("✅ OK", null)
            .setNeutralButton("🔄 Actualizar") { _, _ ->
                updateCourseStats()
                loadCourses()
                showDarkToast("Estadísticas actualizadas")
            }

        val dialog = dialogBuilder.create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.dark_dialog_background)
        }
        dialog.show()
    }
}