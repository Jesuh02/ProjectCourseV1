package com.example.tareamov.ui
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.tareamov.MainActivity
import com.example.tareamov.R // Make sure this import is correct
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.data.entity.Subscription
import com.example.tareamov.util.SessionManager
import com.example.tareamov.viewmodel.CourseViewModel
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import com.example.tareamov.ui.showPaymentOptions // Import the showPaymentOptions extension
import com.example.tareamov.ui.VideoPlayerActivity // Import VideoPlayerActivity
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

class CourseDetailFragment : Fragment() {

    private var courseId: Long = -1
    private var courseName: String = "" // Ensure this is populated correctly
    // Resolved course id after checking backend (may differ from local courseId)
    private var resolvedCourseId: Long = -1
    private lateinit var topicsContainer: LinearLayout
    private var isCurrentUserCreator: Boolean = false
    private var currentUsername: String? = null
    private var courseCreatorUsername: String? = null
    private var creatorUserId: Long = -1
    private lateinit var courseViewModel: CourseViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var bottomNavBinding: ComponentBottomNavigationBinding

    // Views for course creator info - Moved to ExploreFragment cards (keeping as placeholders to prevent compilation errors)
    private lateinit var creatorInfoContainer: View
    private lateinit var creatorAvatarImageView: CircleImageView
    private lateinit var creatorUsernameTextView: TextView
    private lateinit var subscriberCountTextView: TextView
    private lateinit var subscribeButton: Button
    private lateinit var tabDocumentos: TextView
    private lateinit var tabTareas: TextView
    private lateinit var continueWatchingContainer: LinearLayout
    private var currentTab = "documentos" // Add this property for tab tracking
    private lateinit var courseActionBar: LinearLayout // To control visibility of the whole bar
    private lateinit var skeletonLayout: FrameLayout

    // Add subscription state variable
    private var isSubscribed = false

    // Add missing view references for editing course
    private lateinit var courseTitleTextView: TextView
    private lateinit var courseDescriptionTextView: TextView
    private lateinit var courseThematicTextView: TextView
    private lateinit var coursePriceTextView: TextView
    private lateinit var coursePriceIcon: ImageView
    private lateinit var togglePriceButton: Button
    private lateinit var editCourseButton: ImageButton
    private var refreshJob: kotlinx.coroutines.Job? = null // Job to handle refresh cancellation
    private var isLoadingCourseDetails = false // Flag to prevent multiple simultaneous loads
    
    // 🔄 CONTENT CACHING: Prevent re-loading when returning from other fragments
    private var cachedTopicsContainer: List<Pair<Topic, List<Task>>>? = null
    private var cachedCreatorInfo: Triple<String?, Long, Boolean>? = null // username, userId, isSubscribed
    private var cachedCourseData: com.example.tareamov.data.entity.Course? = null
    private var courseDataLoadTime: Long = 0L
    private val CACHE_VALIDITY_MS = 30000L // Cache valid for 30 seconds
    
    // 🛡️ Safe Toast helper - prevents NPE when fragment is detached
    private fun showSafeToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        try {
            val ctx = context ?: return // Silently fail if context is null (fragment detached)
            Toast.makeText(ctx, message, duration).show()
        } catch (e: Exception) {
            Log.w("CourseDetailFragment", "Toast failed (fragment detached?): $message")
        }
    }
    
    // 🔄 Cache validity check
    private fun isCacheValid(): Boolean {
        return System.currentTimeMillis() - courseDataLoadTime < CACHE_VALIDITY_MS
    }

    // Helper: check if a URL is remote (cloud-hosted)
    private fun isRemoteUrl(url: String?): Boolean {
        if (url.isNullOrEmpty()) return false
        return url.startsWith("http://") || url.startsWith("https://")
    }
    
    // BackendApiService is used for all remote operations

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong("courseId", -1)
            // Make sure courseName is retrieved if passed via arguments,
            // otherwise load it in loadCourseDetails
            courseName = it.getString("courseName", "")
            Log.d("CourseDetailFragment", "Received courseId: $courseId, courseName: $courseName")
        }

        // Initialize SessionManager and get current user's username
        sessionManager = SessionManager.getInstance(requireContext())
        currentUsername = sessionManager.getUsername()
        Log.d("CourseDetailFragment", "Current username from session: $currentUsername")
    }

    override fun onResume() {
        super.onResume()
        if (courseId != -1L) {
            Log.d("CourseDetailFragment", "🔄 onResume: Reloading course details for courseId: $courseId")
            
            // Check for pending transactions to auto-unlock course
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val userId = sessionManager.getUserId()
                    if (userId != -1L) {
                        val purchaseResult = BackendApiService.hasPurchasedCourse(courseId)
                        if (purchaseResult is ApiResult.Success && purchaseResult.data == true) {
                            withContext(Dispatchers.Main) {
                                showSafeToast("¡Pago verificado! Curso desbloqueado.", Toast.LENGTH_LONG)
                                loadCourseDetails()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CourseDetailFragment", "Error checking transaction status", e)
                }
            }

            // Also reload standard details
            loadCourseDetails()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_course_detail, container, false)

        // Initialize SessionManager
        sessionManager = SessionManager.getInstance(requireContext())

        topicsContainer = view.findViewById(R.id.topicsContainer)
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        courseActionBar = view.findViewById(R.id.courseActionBar) // Initialize courseActionBar
        skeletonLayout = view.findViewById(R.id.skeletonLayout)

        // Initialize creator info views - Creating placeholder views to prevent compilation errors (functionality moved to ExploreFragment cards)
        creatorInfoContainer = View(requireContext())
        creatorAvatarImageView = de.hdodenhof.circleimageview.CircleImageView(requireContext())
        creatorUsernameTextView = TextView(requireContext())
        subscriberCountTextView = TextView(requireContext())
        subscribeButton = Button(requireContext())

        // Initialize payment container and button
        val paymentButtonContainer = view.findViewById<FrameLayout>(R.id.paymentButtonContainer)
        val paymentButton = view.findViewById<Button>(R.id.paymentButton)
        val paymentPSEButton = view.findViewById<Button>(R.id.paymentPSEButton) // Find the new PSE button

        // Configure payment buttons to navigate to PaymentFormFragment
        paymentButton?.setOnClickListener {
            navigateToPaymentForm()
        }
        paymentPSEButton?.setOnClickListener {
            navigateToPaymentForm()
        }

        // Initialize tab views
        tabDocumentos = view.findViewById(R.id.tabDocumentos)
        tabTareas = view.findViewById(R.id.tabTareas)
        //  continueWatchingContainer = view.findViewById(R.id.continueWatchingContainer) // Initialization

        // Set up tab click listeners with ultra-fast filtering
        tabDocumentos.setOnClickListener {
            if (currentTab != "documentos") {
                currentTab = "documentos"
                updateTabSelection()
                if (cachedTopicsData.isNotEmpty()) {
                    filterContentUltraFast()
                } else {
                    loadCourseDetails()
                }
            }
        }

        tabTareas.setOnClickListener {
            if (currentTab != "tareas") {
                currentTab = "tareas"
                updateTabSelection()
                if (cachedTopicsData.isNotEmpty()) {
                    filterContentUltraFast()
                } else {
                    loadCourseDetails()
                }
            }
        }

        // Initialize visual selection to match currentTab and our stateful selectors
        updateTabSelection()

        // Add a button to create new topics
        val addTopicButton = view.findViewById<Button>(R.id.addTopicButton)
        addTopicButton.setOnClickListener {
            navigateToAddTopic()
        }

        // *** MODIFIED BLOCK for addTaskButton ***
        val addTaskButton = view.findViewById<Button>(R.id.addTaskButton)
        addTaskButton.setOnClickListener {
            if (courseId != -1L) {
                // Try to get the course name from the ViewModel first, then the member variable
                val currentCourseName = courseViewModel.course.value?.title ?: this.courseName

                if (currentCourseName.isNullOrBlank()) {
                    // If the name is still blank after checking both sources
                    Log.w("CourseDetailFragment", "Course name is blank when trying to add task.")
                    showSafeToast("Nombre del curso no cargado aún. Intenta de nuevo.")
                } else {
                    // Course name is available, proceed with navigation
                    navigateToSelectTopic(currentCourseName, isCreatingTask = true) // Pass the confirmed name and creation flag
                }
            } else {
                Log.e("CourseDetailFragment", "Invalid courseId (-1) when trying to add task.")
                showSafeToast("ID de curso inválido.")
            }
        }
        // *** END OF MODIFIED BLOCK ***

        backButton.setOnClickListener {
            findNavController().navigateUp()
        }        // Set up subscribe button click listener - Moved to ExploreFragment cards
        // subscribeButton.setOnClickListener {
        //     handleSubscription()
        // }

        // Set up creator username click listener to navigate to user profile view - Moved to ExploreFragment cards  
        // creatorUsernameTextView.setOnClickListener {
        //     val username = creatorUsernameTextView.text.toString()
        //     if (username.isNotEmpty()) {
        //         val bundle = Bundle().apply {
        //             putString("username", username)
        //         }
        //         findNavController().navigate(R.id.action_courseDetailFragment_to_userProfileViewFragment, bundle)
        //     }
        // }

        if (courseId != -1L) {
            loadCourseDetails()
        } else {
            showSafeToast("Error: ID de curso inválido")
            // Consider navigating back if courseId is invalid from the start
            // findNavController().navigateUp()
        }

        return view
    }

    // Add this to the onViewCreated method after initializing other views
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize ViewModel
        courseViewModel = ViewModelProvider(this)[CourseViewModel::class.java]

        // Initialize view references for editing course
        courseTitleTextView = view.findViewById(R.id.courseTitleTextView)
        courseDescriptionTextView = view.findViewById(R.id.courseDescriptionTextView)
        courseThematicTextView = view.findViewById(R.id.courseThematicTextView)
        coursePriceTextView = view.findViewById(R.id.coursePriceTextView)
        coursePriceIcon = view.findViewById(R.id.coursePriceIcon)
        togglePriceButton = view.findViewById(R.id.togglePriceButton)
        editCourseButton = view.findViewById(R.id.editCourseButton)

        // Initially hide edit controls until we verify creator ownership remotely
        editCourseButton.visibility = View.GONE
        togglePriceButton.visibility = View.GONE
        courseTitleTextView.isClickable = false

        val courseTitle = view.findViewById<TextView>(R.id.courseTitleTextView)
        val courseDescription = view.findViewById<TextView>(R.id.courseDescriptionTextView)
        val subscribeButton = view.findViewById<Button>(R.id.subscribeButton)
        // Optional: button to start reinforcement directly from course detail (may not exist in all layouts)
        val startReinforceBtn: Button? = run {
            val resId = resources.getIdentifier("startReinforcementButton", "id", requireContext().packageName)
            if (resId != 0) view.findViewById<Button>(resId) else null
        }
        startReinforceBtn?.setOnClickListener {
            if (courseId == -1L) {
                Toast.makeText(requireContext(), "ID de curso inválido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                Toast.makeText(requireContext(), "Generando preguntas...", Toast.LENGTH_SHORT).show()
                val titleText = courseTitle.text.toString().ifBlank { courseName }
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val currentUserId = sessionManager.getUserId()
                val questions = withContext(Dispatchers.IO) {
                    // TODO: Implement reinforcement quiz via BackendApiService endpoint
                    emptyList<com.example.tareamov.ui.compose.QuizQuestion>()
                }

                if (questions.isEmpty()) {
                    Toast.makeText(requireContext(), "No hay suficiente contenido o hubo un error generando preguntas.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Pass preloaded questions JSON via savedStateHandle and navigate
                val json = com.google.gson.Gson().toJson(questions)
                val navEntry = findNavController().currentBackStackEntry
                navEntry?.savedStateHandle?.set("preloaded_questions_json", json)

                val bundle = Bundle().apply {
                    putLong("courseId", courseId)
                    putString("courseName", titleText)
                    putString("instructorName", courseCreatorUsername ?: "Docente no especificado")
                }
                // nav action from CourseDetailFragment to reinforcement may not exist in nav graph;
                // navigate directly to the destination fragment id instead
                findNavController().navigate(R.id.reinforcementLearningFragment, bundle)
            }
        }

        // Observe course details
        courseViewModel.course.observe(viewLifecycleOwner) { course ->
            course?.let {
                // Animate title and description updates
                if (courseTitle.text.toString() != it.title) {
                    courseTitle.text = it.title
                    animateTitleUpdate()
                }
                if (courseDescription.text.toString() != it.description) {
                    courseDescription.text = it.description
                    animateDescriptionUpdate()
                }
                
                courseTitleTextView = view.findViewById(R.id.courseTitleTextView)
                courseDescriptionTextView = view.findViewById(R.id.courseDescriptionTextView)
                editCourseButton = view.findViewById(R.id.editCourseButton)
                // Decide edit button visibility using Supabase when possible
                val localUsername = sessionManager.getUsername()
                val sessionUserId = sessionManager.getUserId()
                Log.d("CourseDetailFragment", "🔍 Local username: $localUsername, courseId: $courseId, creatorUserId: ${it.creatorUserId}")
                val fastOwnerById = sessionUserId > 0 && it.creatorUserId == sessionUserId
                if (fastOwnerById) {
                    Log.d("CourseDetailFragment", "✅ Owner detected by id match: sessionUserId=$sessionUserId creatorUserId=${it.creatorUserId}")
                    editCourseButton.visibility = View.VISIBLE
                    togglePriceButton.visibility = View.VISIBLE
                    isCurrentUserCreator = true
                } else {
                    editCourseButton.visibility = View.GONE
                    togglePriceButton.visibility = View.GONE
                    isCurrentUserCreator = false
                }

                // If not confirmed owner by id, fall back to username/remote checks
                if (!fastOwnerById && !localUsername.isNullOrBlank()) {
                    lifecycleScope.launch {
                        var showEdit = false
                        try {
                            // Use BackendApiService to check course creator
                            val remoteCourseResult = withContext(Dispatchers.IO) { BackendApiService.getCourseById(courseId) }
                            if (remoteCourseResult is ApiResult.Success && remoteCourseResult.data != null) {
                                val remoteCourse = remoteCourseResult.data
                                // Fetch username from remote course's creator_user_id
                                val creatorResult = withContext(Dispatchers.IO) {
                                    BackendApiService.getUserById(remoteCourse.creatorUserId)
                                }
                                val remoteCreatorUsername = (creatorResult as? ApiResult.Success)?.data?.usuario
                                Log.d("CourseDetailFragment", "✅ Remote creator: $remoteCreatorUsername, local: $localUsername, match: ${remoteCreatorUsername == localUsername}")
                                showEdit = remoteCreatorUsername != null && remoteCreatorUsername == localUsername
                            } else {
                                // fallback to local course data if remote missing
                                val localCreatorResult = withContext(Dispatchers.IO) {
                                    BackendApiService.getUserById(it.creatorUserId)
                                }
                                val localCreatorUsername = (localCreatorResult as? ApiResult.Success)?.data?.usuario
                                Log.d("CourseDetailFragment", "⚠️ Local creator (fallback): $localCreatorUsername, match: ${localCreatorUsername == localUsername}")
                                showEdit = localCreatorUsername != null && localCreatorUsername == localUsername
                            }
                        } catch (e: Exception) {
                            Log.e("CourseDetailFragment", "❌ Error checking remote creator: ${e.message}", e)
                            val localCreatorResult = withContext(Dispatchers.IO) {
                                BackendApiService.getUserById(it.creatorUserId)
                            }
                            val localCreatorUsername = (localCreatorResult as? ApiResult.Success)?.data?.usuario
                            Log.d("CourseDetailFragment", "🔧 Local creator (error): $localCreatorUsername, match: ${localCreatorUsername == localUsername}")
                            showEdit = localCreatorUsername != null && localCreatorUsername == localUsername
                        }
                        
                        Log.d("CourseDetailFragment", "🎯 FINAL showEdit decision: $showEdit")
                        
                        withContext(Dispatchers.Main) {
                            editCourseButton.visibility = if (showEdit) View.VISIBLE else View.GONE
                            togglePriceButton.visibility = if (showEdit) View.VISIBLE else View.GONE
                            isCurrentUserCreator = showEdit

                            // Animate edit button entrance if it becomes visible
                            if (showEdit) {
                                editCourseButton.alpha = 0f
                                editCourseButton.scaleX = 0.8f
                                editCourseButton.scaleY = 0.8f
                                editCourseButton.translationY = 20f
                                editCourseButton.animate()
                                    .alpha(1f)
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .translationY(0f)
                                    .setDuration(350)
                                    .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                                    .start()
                                
                                Log.d("CourseDetailFragment", "✨ Edit button should be VISIBLE now")
                            } else {
                                Log.d("CourseDetailFragment", "🚫 Edit button hidden - user is not creator")
                            }
                        }
                    }
                }
                
                // Update thematic/category
                val thematic = it.category ?: "General"
                courseThematicTextView.text = "Temática: $thematic"
                
                // Update price information
                updatePriceDisplay(it.price)
            }
        }

        // If a courseName was passed via arguments, prefer it for the displayed title
        if (courseName.isNotBlank()) {
            try {
                courseTitleTextView.text = courseName
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Could not set courseTitleTextView from argument: ${e.message}")
            }
        }

        // Edit button click: navigate to CourseCreationFragment for editing
        editCourseButton.setOnClickListener {
            val bundle = Bundle().apply {
                putLong("courseId", courseId)
                putBoolean("isEditing", true)
            }
            try {
                findNavController().navigate(R.id.action_courseDetailFragment_to_courseCreationFragment, bundle)
            } catch (e: Exception) {
                // Fallback if action not found, try direct navigation by ID
                try {
                    findNavController().navigate(R.id.courseCreationFragment, bundle)
                } catch (e2: Exception) {
                    Toast.makeText(requireContext(), "Error de navegación: " + e2.message, Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // Setup toggle price button
        togglePriceButton.setOnClickListener {
            showPriceConfigurationDialog()
        }

        // Load course details
        courseViewModel.getCourseById(courseId)
        
        // Setup bottom navigation
        setupBottomNavigation(view)

        // Observe back stack savedStateHandle for topic creation notifications
        val navBackEntry = findNavController().currentBackStackEntry
        navBackEntry?.savedStateHandle?.getLiveData<Long>("topic_created")?.observe(viewLifecycleOwner) { topicId ->
            try {
                Log.d("CourseDetailFragment", "Detected topic_created=$topicId, refreshing topics")
                refreshTopicsFromBackend()
                // Clear the flag so subsequent returns don't re-trigger unless set again
                navBackEntry.savedStateHandle.remove<Long>("topic_created")
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Error handling topic_created event", e)
            }
        }
        
        // Observe general refresh flag (usado por tareas y otros cambios)
        navBackEntry?.savedStateHandle?.getLiveData<Boolean>("refresh_from_supabase")?.observe(viewLifecycleOwner) { shouldRefresh ->
            if (shouldRefresh == true) {
                Log.d("CourseDetailFragment", "Refresh flag received, reloading from backend...")
                refreshTopicsFromBackend()
                navBackEntry.savedStateHandle.remove<Boolean>("refresh_from_supabase")
            }
        }
        
        // Observe flag to switch to tasks tab after creating a task
        navBackEntry?.savedStateHandle?.getLiveData<Boolean>("switch_to_tasks_tab")?.observe(viewLifecycleOwner) { shouldSwitch ->
            if (shouldSwitch == true) {
                Log.d("CourseDetailFragment", "Switching to tasks tab after task creation")
                currentTab = "tareas"
                updateTabSelection()
                filterContentUltraFast()
                navBackEntry.savedStateHandle.remove<Boolean>("switch_to_tasks_tab")
            }
        }
        
        // Observe flag to force complete reload (clears cache to avoid duplicates)
        navBackEntry?.savedStateHandle?.getLiveData<Boolean>("force_reload_topics")?.observe(viewLifecycleOwner) { shouldForceReload ->
            if (shouldForceReload == true) {
                Log.d("CourseDetailFragment", "Force reload requested - clearing cache and reloading")
                cachedTopicsData.clear()
                refreshTopicsFromBackend()
                navBackEntry.savedStateHandle.remove<Boolean>("force_reload_topics")
            }
        }
    }
      private fun setupBottomNavigation(view: View) {
        // Initialize the bottom navigation binding
        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        // Home Button - Navigate to VideoHome
        bottomNavBinding.homeNavLayout.setOnClickListener {
            findNavController().navigate(R.id.action_courseDetailFragment_to_videoHomeFragment)
        }
        
        // Explore Button
        bottomNavBinding.exploreButton.setOnClickListener {
            findNavController().navigate(R.id.action_courseDetailFragment_to_exploreFragment)
        }
        
        // Add/Upload Button (ic_add)
        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.action_courseDetailFragment_to_contentUploadFragment)
        }
        
        // Activity Button (ic_activity)
        bottomNavBinding.activityButton.setOnClickListener {
            findNavController().navigate(R.id.action_courseDetailFragment_to_notificacionesFragment)
        }
        
        // Profile Button (ic_profile)
        bottomNavBinding.profileNavButton.setOnClickListener {
            findNavController().navigate(R.id.action_courseDetailFragment_to_profileFragment)
        }

        // Setup admin button visibility and functionality
        setupAdminButton()
        
        // Actualizar badge de notificaciones
        updateNotificationBadge()
        
        // Check enrollment status for non-creators
        if (!isCurrentUserCreator && currentUsername != null) {
            // Allow access - enrollment check is disabled to prevent blocking content
            Log.d("CourseDetailFragment", "Skipping enrollment check - user has access to course")
        }
    }

    /**
     * Actualiza el badge de notificaciones no leídas
     */
    private fun updateNotificationBadge() {
        val userId = sessionManager.getUserId()
        if (userId == -1L) {
            bottomNavBinding.notificationBadge.visibility = View.GONE
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val unreadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    BackendApiService.getUnreadNotificationCount()
                }
                
                val unreadCount = if (unreadResult is ApiResult.Success) {
                    unreadResult.data ?: 0
                } else 0
                
                if (unreadCount > 0) {
                    bottomNavBinding.notificationBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                    bottomNavBinding.notificationBadge.visibility = View.VISIBLE
                } else {
                    bottomNavBinding.notificationBadge.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Error updating notification badge", e)
                bottomNavBinding.notificationBadge.visibility = View.GONE
            }
        }
    }

    // Add this function to navigate to CourseTopicFragment
    private fun navigateToAddTopic() {
        // This function likely navigates to CourseTopicFragment for adding a *topic*
        val nextTopicNumber = getNextTopicNumber()
        val bundle = Bundle().apply {
            putLong("courseId", courseId)
            putString("courseName", courseName) // Pass course name here
            putInt("topicNumber", nextTopicNumber)
            putLong("topicId", -1L) // Indicate new topic
            putBoolean("isTemporary", false) // Or true if it's a temporary topic creation step
        }
        // Keep the original navigation for adding a topic if needed elsewhere
        // !! IMPORTANT: Ensure 'action_courseDetailFragment_to_courseTopicFragment' exists in your nav_graph.xml !!
        findNavController().navigate(R.id.action_courseDetailFragment_to_courseTopicFragment, bundle)
    }

    /**
     * Navigate to the Payment Form Fragment for PSE payment
     */
    private fun navigateToPaymentForm() {
        if (currentUsername == null) {
            Toast.makeText(requireContext(), "Debes iniciar sesión para pagar", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Get current course price from ViewModel or cached data
        lifecycleScope.launch {
            try {
                val course = courseViewModel.course.value 
                    ?: withContext(Dispatchers.IO) { 
                        (BackendApiService.getCourseById(courseId) as? ApiResult.Success)?.data
                    }
                
                val price = course?.price ?: 0.0
                
                if (price <= 0) {
                    Toast.makeText(requireContext(), "Este curso es gratuito", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Navigate to PaymentFormFragment using the extension function
                showPaymentOptions(
                    courseId = courseId,
                    courseName = courseName,
                    coursePrice = price,
                    username = currentUsername
                ) { success ->
                    // Callback can be used for result handling if needed
                    if (success) {
                        Log.d("CourseDetail", "Payment initiated successfully")
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseDetail", "Error navigating to payment", e)
                Toast.makeText(requireContext(), "Error al abrir pago: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun navigateToSelectTopic(nameOfCourse: String, isCreatingTask: Boolean = false) { // Accept course name and creating flag
        // Prefer resolvedCourseId (may have been remapped to Supabase id); fallback to original courseId
        val sendCourseId = if (resolvedCourseId > 0) resolvedCourseId else courseId
        Log.d("CourseDetailFragment", "Navigating to SelectTopicFragment for courseId: $sendCourseId, courseName: $nameOfCourse, isCreatingTask: $isCreatingTask")
        val bundle = Bundle().apply {
            putLong("courseId", sendCourseId)
            putString("courseName", nameOfCourse) // Pass the confirmed course name
            putBoolean("isCreatingTask", isCreatingTask)
        }
        // Ensure the action ID matches the one defined in nav_graph.xml
        // Make sure R.id.action_courseDetailFragment_to_selectTopicFragment exists in your nav_graph
        findNavController().navigate(R.id.action_courseDetailFragment_to_selectTopicFragment, bundle)
    }


    private fun getNextTopicNumber(): Int {
        // Implement logic to determine the next topic number if needed
        // For example, count existing topics + 1
        // Placeholder implementation:
        return (topicsContainer.childCount / 2) + 1 // Assuming pairs of topic view + divider
    }
    
    /**
     * Check if user is enrolled in the course before allowing access
     * NOTE: This method no longer blocks access - if the user got here, they have access
     * (either paid, free course, or is the creator)
     */
    private fun checkEnrollmentBeforeAccess() {
        // REMOVED RESTRICTION: If the user accessed this screen, they have access
        // The payment verification is handled in ExploreFragment before navigation
        Log.d("CourseDetailFragment", "checkEnrollmentBeforeAccess: No restrictions - user has access to course $courseId")
        
        // Auto-enroll in free courses if needed (non-blocking)
        lifecycleScope.launch {
            try {
                val userResult = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(currentUsername ?: return@withContext null)
                }
                
                val userId = (userResult as? ApiResult.Success)?.data?.id
                
                if (userId != null) {
                    val progressResult = withContext(Dispatchers.IO) {
                        BackendApiService.getProgressByCourse(courseId)
                    }
                    
                    if (progressResult is ApiResult.Error) {
                        val courseResult = withContext(Dispatchers.IO) {
                            BackendApiService.getCourseById(courseId)
                        }
                        val course = (courseResult as? ApiResult.Success)?.data
                        // Auto-enroll if it's a free course
                        if (course != null && course.price <= 0 && course.isPremium != true) {
                            Log.d("CourseDetailFragment", "Auto-enrolling user in free course $courseId")
                            autoEnrollInFreeCourse(course)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error in enrollment check (non-blocking)", e)
            }
        }
    }
    
    /**
     * Auto-enroll user in a free course
     */
    private fun autoEnrollInFreeCourse(course: com.example.tareamov.data.entity.Course?) {
        if (course == null || currentUsername == null) return
        
        lifecycleScope.launch {
            try {
                // CRITICAL: Prevent course creator from enrolling in their own course
                val userResult = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(currentUsername!!)
                }
                val userId = (userResult as? ApiResult.Success)?.data?.id
                
                if (userId == null) {
                    android.widget.Toast.makeText(requireContext(), "Error: Usuario no encontrado", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                if (userId == course.creatorUserId) {
                    Log.d("CourseDetailFragment", "⚠️ Creator cannot enroll in own course ${course.id}")
                    return@launch
                }
                
                // Double-check with username comparison as fallback
                val creatorResult = withContext(Dispatchers.IO) {
                    BackendApiService.getUserById(course.creatorUserId)
                }
                val creatorUsername = (creatorResult as? ApiResult.Success)?.data?.usuario
                
                if (currentUsername == creatorUsername) {
                    Log.d("CourseDetailFragment", "⚠️ Creator (by username) cannot enroll in own course ${course.id}")
                    return@launch
                }
                
                // Get total tasks for this course
                val topicsResult = withContext(Dispatchers.IO) {
                    BackendApiService.getTopicsByCourse(course.id)
                }
                val topics = (topicsResult as? ApiResult.Success)?.data ?: emptyList()
                
                var totalTasks = 0
                if (topics.isNotEmpty()) {
                    for (topic in topics) {
                        val tasksResult = withContext(Dispatchers.IO) {
                            BackendApiService.getTasksByTopic(topic.id)
                        }
                        totalTasks += ((tasksResult as? ApiResult.Success)?.data?.size ?: 0)
                    }
                }
                
                // Create initial progress record via BackendApiService
                val progressData = mapOf(
                    "cursoId" to course.id,
                    "tareasCompletadas" to 0,
                    "tareasTotales" to totalTasks,
                    "porcentajeProgreso" to 0f,
                    "estado" to "Perdido"
                )
                
                val progressResult = withContext(Dispatchers.IO) {
                    BackendApiService.upsertProgress(progressData)
                }
                
                withContext(Dispatchers.Main) {
                    if (progressResult is ApiResult.Success) {
                        Log.d("CourseDetailFragment", "✅ Auto-enrolled $currentUsername in free course ${course.id}")
                        Toast.makeText(
                            requireContext(),
                            "✅ ¡Inscrito automáticamente en ${course.title}!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.w("CourseDetailFragment", "⚠️ Failed to enroll: ${(progressResult as? ApiResult.Error)?.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error auto-enrolling in free course", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        "❌ Error al inscribirse: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // In the loadCourseDetails() method, update to use SubscriptionDao
    private fun loadCourseDetails() {
        // Prevent multiple simultaneous loads
        if (isLoadingCourseDetails) {
            Log.w("CourseDetailFragment", "⚠️ Load already in progress, skipping duplicate call")
            return
        }
        
        isLoadingCourseDetails = true
        
        // Cancel any previous refresh job
        refreshJob?.cancel()
        
        // CRITICAL: Clear container IMMEDIATELY to prevent duplication
        topicsContainer.removeAllViews()
        Log.d("CourseDetailFragment", "🧹 Topics container cleared at start of loadCourseDetails")
        
        startSkeletonAnimation()
        val noTopicsTextView = view?.findViewById<TextView>(R.id.noTopicsTextView)
        val courseTitleTextView = view?.findViewById<TextView>(R.id.courseTitleTextView)
        // Add a TextView for when tasks are filtered and none are found
        val noTasksTextView = view?.findViewById<TextView>(R.id.noTasksTextView) // Make sure this ID exists in your layout or create it
    val paymentContainer = view?.findViewById<FrameLayout>(R.id.paymentButtonContainer)

        refreshJob = CoroutineScope(Dispatchers.Main).launch {
            try { // Start of the main try block
                // Try to fetch the Course from Supabase first (via MainActivity.syncRepository)
                var remoteCourse: com.example.tareamov.data.entity.Course? = null
                // Use an effectiveCourseId for subsequent topic/task lookups; may be remapped if we find
                // a matching course by title when the numeric id is not present there.
                var effectiveCourseId: Long = courseId
                try {
                    // Fetch course from BackendApiService
                    val courseResult = withContext(Dispatchers.IO) { BackendApiService.getCourseById(courseId) }
                    if (courseResult is ApiResult.Success) {
                        remoteCourse = courseResult.data
                    }
                    Log.d("CourseDetailFragment", "getCourseById returned: ${remoteCourse?.id}")

                    // If no course found by id, try to resolve by the passed courseName
                    if (remoteCourse == null && courseName.isNotBlank()) {
                        try {
                            val searchResult = withContext(Dispatchers.IO) { BackendApiService.searchCourses(courseName) }
                            if (searchResult is ApiResult.Success) {
                                val match = searchResult.data?.firstOrNull { c ->
                                    val remoteTitle = (c.title ?: "").trim()
                                    remoteTitle.equals(courseName.trim(), ignoreCase = true)
                                }
                                if (match != null) {
                                    remoteCourse = match
                                    effectiveCourseId = match.id
                                    Log.d("CourseDetailFragment", "Resolved course by title -> remote id=${match.id} title=${match.title}")
                                } else {
                                    Log.d("CourseDetailFragment", "No course matched title='$courseName'")
                                }
                            }
                        } catch (t: Exception) {
                            Log.w("CourseDetailFragment", "Title-based lookup failed", t)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("CourseDetailFragment", "Remote fetch failed", e)
                }

                if (remoteCourse != null) {
                    // Populate UI from remote Course
                    val title = remoteCourse.title ?: "Curso sin título"
                    courseTitleTextView?.text = title
                    courseName = title

                    // Map creator username from creator_user_id
                    creatorUserId = remoteCourse.creatorUserId
                    courseCreatorUsername = withContext(Dispatchers.IO) {
                        (BackendApiService.getUserById(remoteCourse.creatorUserId) as? ApiResult.Success)?.data?.usuario
                    }
                    isCurrentUserCreator = courseCreatorUsername == currentUsername
                    courseActionBar.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE
                    
                    // IMPORTANTE: Actualizar progreso del estudiante al ingresar al curso
                    // Esto asegura que la información esté sincronizada sin usar triggers
                    if (!isCurrentUserCreator && currentUsername != null) {
                        Log.d("CourseDetailFragment", "🔄 Updating student progress on course entry: user=$currentUsername, course=$effectiveCourseId")
                        recalculateStudentProgressOnEntry(effectiveCourseId)
                    }
                    
                    // IMPORTANTE: Actualizar progreso del estudiante al ingresar al curso
                    // Esto asegura que la información esté sincronizada sin usar triggers
                    if (!isCurrentUserCreator && currentUsername != null) {
                        Log.d("CourseDetailFragment", "🔄 Updating student progress on course entry: user=$currentUsername, course=$effectiveCourseId")
                        recalculateStudentProgressOnEntry(effectiveCourseId)
                    }
                    if (courseActionBar.visibility == View.VISIBLE) {
                        animateViewIfVisible(courseActionBar, 360)
                    } else {
                        courseActionBar.alpha = 0f
                        courseActionBar.translationY = resources.getDimensionPixelSize(R.dimen.edit_button_enter_offset).toFloat()
                    }

                    // Show payment container if course is premium and viewer is not the creator
                    if (remoteCourse.isPremium == true && !isCurrentUserCreator) {
                        // Always hide payment UI since user already accessed the course detail
                        // If they got here, they have access (either paid or should have access)
                        paymentContainer?.visibility = View.GONE
                        Log.d("CourseDetailFragment", "Premium course accessed - hiding payment UI (user has access)")
                    } else {
                        paymentContainer?.alpha = 0f
                        paymentContainer?.translationY = resources.getDimensionPixelSize(R.dimen.edit_button_enter_offset).toFloat()
                        paymentContainer?.visibility = View.GONE
                    }

                    // Load creator info if the current user is not the creator
                    if (!isCurrentUserCreator && remoteCourse != null) {
                        val creatorId = remoteCourse.creatorUserId
                        val currentUserId = sessionManager.getUserId()

                        // Fetch subscription info via BackendApiService
                        var subscriptionCount = 0
                        var isSubscribedRemote = false
                        try {
                            if (currentUserId != -1L) {
                                val subResult = withContext(Dispatchers.IO) { BackendApiService.checkSubscription(creatorId) }
                                isSubscribedRemote = (subResult as? ApiResult.Success)?.data ?: false
                            }
                            val countResult = withContext(Dispatchers.IO) { BackendApiService.getSubscriberCount(creatorId) }
                            subscriptionCount = (countResult as? ApiResult.Success)?.data ?: 0
                        } catch (e: Exception) {
                            Log.w("CourseDetailFragment", "Subscription check failed", e)
                        }
                        val isSubscribed = isSubscribedRemote

                        loadCreatorInfo(
                            creatorUsername = courseCreatorUsername!!,
                            subscriptionCount = subscriptionCount,
                            isSubscribed = isSubscribed
                        )

                        // creatorInfoContainer.visibility = View.VISIBLE // Moved to ExploreFragment cards
                        Log.d("CourseDetailFragment", "Initializing student progress: courseId=$effectiveCourseId username=$currentUsername isCreator=$isCurrentUserCreator")
                        initializeAndLoadCourseProgress(
                            courseId = effectiveCourseId,
                            username = currentUsername,
                            userId = currentUserId,
                            isCurrentUserCreator = isCurrentUserCreator
                        )
                    } else {
                        // creatorInfoContainer.visibility = View.GONE // Moved to ExploreFragment cards
                    }
                } else {
                    // Fallback: load course info from BackendApiService by courseId
                    val courseResult = withContext(Dispatchers.IO) { BackendApiService.getCourseById(courseId) }
                    val course = (courseResult as? ApiResult.Success)?.data

                    // Set the course title
                    courseTitleTextView?.text = course?.title ?: "Curso sin título"
                    courseName = course?.title ?: "Curso sin título"

                    // Map creator username from course's creator_user_id
                    courseCreatorUsername = if (course != null) {
                        withContext(Dispatchers.IO) {
                            (BackendApiService.getUserById(course.creatorUserId) as? ApiResult.Success)?.data?.usuario
                        }
                    } else {
                        null
                    }
                    isCurrentUserCreator = courseCreatorUsername == currentUsername

                    // Control visibility of the bottom action bar based on creator status
                    courseActionBar.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE
                    if (courseActionBar.visibility == View.VISIBLE) {
                        animateViewIfVisible(courseActionBar, 360)
                    } else {
                        courseActionBar.alpha = 0f
                        courseActionBar.translationY = resources.getDimensionPixelSize(R.dimen.edit_button_enter_offset).toFloat()
                    }

                    if (course?.isPremium == true && !isCurrentUserCreator) {
                        // Always hide payment UI since user already accessed the course detail
                        // If they got here, they have access (either paid or should have access)
                        paymentContainer?.visibility = View.GONE
                        Log.d("CourseDetailFragment", "Premium course accessed - hiding payment UI (user has access)")
                    } else {
                        paymentContainer?.alpha = 0f
                        paymentContainer?.translationY = resources.getDimensionPixelSize(R.dimen.edit_button_enter_offset).toFloat()
                        paymentContainer?.visibility = View.GONE
                    }

                    // Load creator info if the current user is not the creator
                    if (!isCurrentUserCreator && courseCreatorUsername != null) {
                        val currentUserId = sessionManager.getUserId()
                        // Get subscription count using BackendApiService
                        var subscriptionCount = 0
                        try {
                            val countResult = withContext(Dispatchers.IO) {
                                if (creatorUserId != -1L) BackendApiService.getSubscriberCount(creatorUserId) else null
                            }
                            subscriptionCount = (countResult as? ApiResult.Success)?.data ?: 0
                        } catch (e: Exception) {
                            Log.w("CourseDetailFragment", "Subscriber count fetch failed", e)
                        }

                        // Check if current user is subscribed using BackendApiService
                        var isSubscribed = false
                        try {
                            if (currentUserId != -1L && creatorUserId != -1L) {
                                val subResult = withContext(Dispatchers.IO) { BackendApiService.checkSubscription(creatorUserId) }
                                isSubscribed = (subResult as? ApiResult.Success)?.data ?: false
                            }
                        } catch (e: Exception) {
                            Log.w("CourseDetailFragment", "Subscription check failed", e)
                        }

                        loadCreatorInfo(
                            creatorUsername = courseCreatorUsername!!,
                            subscriptionCount = subscriptionCount,
                            isSubscribed = isSubscribed
                        )

                        // creatorInfoContainer.visibility = View.VISIBLE // Moved to ExploreFragment cards

                        // Initialize and load course progress for non-creator users
                        Log.d("CourseDetailFragment", "Initializing student progress (local fallback): courseId=$courseId username=$currentUsername isCreator=$isCurrentUserCreator")
                        initializeAndLoadCourseProgress(
                            courseId = courseId,
                            username = currentUsername,
                            userId = currentUserId,
                            isCurrentUserCreator = isCurrentUserCreator
                        )
                    } else {
                        // creatorInfoContainer.visibility = View.GONE // Moved to ExploreFragment cards
                    }
                }

                // Fetch topics from BackendApiService
                var topics: List<Topic> = emptyList()

                try {
                    // Use effectiveCourseId (may have been remapped by title lookup)
                    val lookupId = effectiveCourseId
                    Log.d("CourseDetailFragment", "🔍 Fetching topics for courseId=$lookupId from backend...")
                    val topicsResult = withContext(Dispatchers.IO) { BackendApiService.getTopicsByCourse(lookupId) }
                    if (topicsResult is ApiResult.Success) {
                        topics = topicsResult.data ?: emptyList()
                    } else {
                        Log.w("CourseDetailFragment", "Topics fetch failed: ${(topicsResult as? ApiResult.Error)?.message}")
                    }
                    Log.d("CourseDetailFragment", "✅ Loaded ${topics.size} remote topics for courseId=$lookupId")
                    // Log each topic for debugging
                    topics.forEachIndexed { index, topic ->
                        Log.d("CourseDetailFragment", "   📘 Topic[$index]: id=${topic.id}, name='${topic.name}', courseId=${topic.courseId}")
                    }
                } catch (e: Exception) {
                    Log.w("CourseDetailFragment", "Topics fetch failed", e)
                }

                Log.d("CourseDetailFragment", "📚 Total topics found: ${topics.size} for courseId: $courseId")
                
                // DEBUG: skipped (debug code removed during migration)

                if (topics.isEmpty()) {
                    Log.d("CourseDetailFragment", "No topics found for course ID: $courseId")
                    // When Supabase is configured we still show the container (empty) so newly created remote
                    // topics become visible without requiring local Room inserts. Show a friendly message.
                    noTopicsTextView?.text = "Este curso aún no tiene temas." // Set specific message
                    noTopicsTextView?.visibility = View.VISIBLE
                    noTopicsTextView?.alpha = 0f
                    topicsContainer.removeAllViews()
                    topicsContainer.visibility = View.VISIBLE
                    animateViewIfVisible(noTopicsTextView, 320)
                    animateViewIfVisible(topicsContainer, 300)
                    noTasksTextView?.visibility = View.GONE
                    noTasksTextView?.alpha = 0f
                } else {
                    // Clear previous views and reset messages
                    topicsContainer.removeAllViews()
                    noTopicsTextView?.visibility = View.GONE
                    noTopicsTextView?.alpha = 0f
                    noTasksTextView?.visibility = View.GONE
                    noTasksTextView?.alpha = 0f

                    // Debug: log topic list before rendering
                    Log.d("CourseDetailFragment", "Debug: topics.size=${topics.size}, currentTab=$currentTab")

                    // Iterate and add topic views. For tab filters we fetch only topics,
                    // but when the user selected the "tareas" tab we must also fetch
                    // tasks for those topics from Supabase (or fallback to local DAO).
                    val sortedTopics = topics.sortedBy { it.orderIndex }

                    // Prepare tasks grouped by topicId when viewing tasks
                    var tasksByTopic: Map<Long, List<Task>> = emptyMap()
                    // Prepare content items grouped by topicId when viewing documentos
                    var contentByTopic: Map<Long, List<ContentItem>> = emptyMap()
                    
                    if (currentTab == "tareas") {
                        try {
                            val topicIds = sortedTopics.map { it.id }
                            if (topicIds.isNotEmpty()) {
                                // Fetch tasks per topic from BackendApiService
                                val allTasks = mutableListOf<Task>()
                                for (topicId in topicIds) {
                                    val tasksResult = withContext(Dispatchers.IO) { BackendApiService.getTasksByTopic(topicId) }
                                    if (tasksResult is ApiResult.Success) {
                                        allTasks.addAll(tasksResult.data ?: emptyList())
                                    }
                                }
                                tasksByTopic = allTasks.groupBy { it.topicId }
                            }
                        } catch (e: Exception) {
                            Log.w("CourseDetailFragment", "Failed to fetch tasks for topics", e)
                        }
                    } else {
                        // currentTab == "documentos" - fetch content items for topics
                        try {
                            val topicIds = sortedTopics.map { it.id }
                            Log.d("CourseDetailFragment", "")
                            Log.d("CourseDetailFragment", "📚 ╔══════════════════════════════════════════════════╗")
                            Log.d("CourseDetailFragment", "📚 ║        CONTENT LOADING DEBUG                    ║")
                            Log.d("CourseDetailFragment", "📚 ╠══════════════════════════════════════════════════╣")
                            Log.d("CourseDetailFragment", "📚 ║ Course ID: $resolvedCourseId")
                            Log.d("CourseDetailFragment", "📚 ║ Course Name: $courseName")
                            Log.d("CourseDetailFragment", "📚 ║ Total topics to render: ${sortedTopics.size}")
                            Log.d("CourseDetailFragment", "📚 ║ Topic IDs: $topicIds")
                            Log.d("CourseDetailFragment", "📚 ╚══════════════════════════════════════════════════╝")
                            
                            if (topicIds.isNotEmpty()) {
                                Log.d("CourseDetailFragment", "📚 Querying backend for content with topic_ids IN (${topicIds.joinToString(",")})")
                                
                                val allContent = mutableListOf<ContentItem>()
                                for (topicId in topicIds) {
                                    val contentResult = withContext(Dispatchers.IO) { BackendApiService.getContentItemsByTopic(topicId) }
                                    if (contentResult is ApiResult.Success) {
                                        val items = contentResult.data ?: emptyList()
                                        Log.d("CourseDetailFragment", "📦 Loaded ${items.size} content items for topic $topicId")
                                        allContent.addAll(items)
                                    } else {
                                        Log.w("CourseDetailFragment", "⚠️ Failed to load content for topic $topicId")
                                    }
                                }
                                
                                contentByTopic = allContent.groupBy { it.topicId }
                                Log.d("CourseDetailFragment", "📦 Top level content items loaded: ${contentByTopic.size} topics with content")
                            } else {
                                Log.w("CourseDetailFragment", "⚠️ No topicIds to fetch content for!")
                            }
                        } catch (e: Exception) {
                            Log.e("CourseDetailFragment", "❌ Failed to fetch content items for topics", e)
                        }
                    }

                    // If viewing tasks, filter out topics that have no tasks
                    val topicsToRender = if (currentTab == "tareas") {
                        sortedTopics.filter { t -> (tasksByTopic[t.id] ?: emptyList()).isNotEmpty() }
                    } else {
                        sortedTopics
                    }

                    for (topic in topicsToRender) {
                        val tasksForTopic = tasksByTopic[topic.id] ?: emptyList()
                        val contentForTopic = contentByTopic[topic.id] ?: emptyList()
                        Log.d("CourseDetailFragment", "📖 Topic '${topic.name}' (id=${topic.id}): ${contentForTopic.size} content items, ${tasksForTopic.size} tasks")
                        addTopicView(topic, contentForTopic, tasksForTopic)
                    }

                    // Ensure container visible even if empty; show a top-level message per tab
                    topicsContainer.visibility = View.VISIBLE
                    if (sortedTopics.isEmpty()) {
                        if (currentTab == "documentos") {
                            noTopicsTextView?.text = "No hay documentos en este curso."
                            noTopicsTextView?.visibility = View.VISIBLE
                            noTopicsTextView?.alpha = 0f
                            animateViewIfVisible(noTopicsTextView, 320)
                        } else {
                            noTasksTextView?.text = "No hay tareas en este curso."
                            noTasksTextView?.visibility = View.VISIBLE
                            noTasksTextView?.alpha = 0f
                            animateViewIfVisible(noTasksTextView, 320)
                        }
                    }
                    animateViewIfVisible(topicsContainer, 300)
                }

                stopSkeletonAnimation()
                animateCourseTitleEntrance()
            } catch (e: Exception) { // This is the correct catch block for the main try
                stopSkeletonAnimation()
                Log.e("CourseDetailFragment", "Error loading course details", e)
                showSafeToast("Error al cargar detalles del curso")
                noTopicsTextView?.text = "Error al cargar datos." // Generic error message
                noTopicsTextView?.visibility = View.VISIBLE
                noTopicsTextView?.alpha = 0f
                animateViewIfVisible(noTopicsTextView, 320)
                noTasksTextView?.visibility = View.GONE // Ensure no tasks message is hidden on error
                topicsContainer.removeAllViews() // Clear on error
                topicsContainer.visibility = View.GONE
            } finally {
                isLoadingCourseDetails = false
                Log.d("CourseDetailFragment", "✅ Load complete, flag reset")
            } // Closes the main catch block
        } // Closes CoroutineScope
    } // Closes loadCourseDetails function

    // New method to load creator information with updated parameters
    private suspend fun loadCreatorInfo(
        creatorUsername: String,
        subscriptionCount: Int,
        isSubscribed: Boolean
    ) {
        try {
            // Fetch creator info via BackendApiService
            var personaFromRemote: Persona? = null
            var usuarioFromRemote: Usuario? = null

            try {
                val userResult = withContext(Dispatchers.IO) { BackendApiService.getUserByUsername(creatorUsername) }
                if (userResult is ApiResult.Success && userResult.data != null) {
                    usuarioFromRemote = userResult.data
                    val personaResult = withContext(Dispatchers.IO) { BackendApiService.getPersonaById(usuarioFromRemote.persona_id) }
                    personaFromRemote = (personaResult as? ApiResult.Success)?.data
                    Log.d("CourseDetailFragment", "Remote usuario found for $creatorUsername persona_id=${usuarioFromRemote.persona_id}")
                }
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Backend remote creator fetch failed", e)
            }

            if (personaFromRemote != null || usuarioFromRemote != null) {
                // Build UI using remote data (persona preferred for avatar) - Moved to ExploreFragment cards
                withContext(Dispatchers.Main) {
                    // creatorUsernameTextView.text = creatorUsername

                    // if (personaFromRemote?.avatar.isNullOrEmpty()) {
                    //     creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                    // } else {
                    //     try {
                    //         Glide.with(requireContext())
                    //             .load(Uri.parse(personaFromRemote!!.avatar))
                    //             .placeholder(R.drawable.default_avatar)
                    //             .error(R.drawable.default_avatar)
                    //             .into(creatorAvatarImageView)
                    //     } catch (e: Exception) {
                    //         Log.e("CourseDetailFragment", "Error loading remote avatar", e)
                    //         creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                    //     }
                    // }

                    // subscriberCountTextView.text = formatSubscriberCount(subscriptionCount) - Moved to ExploreFragment cards
                    // this@CourseDetailFragment.isSubscribed = isSubscribed
                    // updateSubscribeButtonState(isSubscribed)

                    // subscribeButton.visibility = if (currentUsername == creatorUsername) View.GONE else View.VISIBLE // Moved to ExploreFragment cards
                    // creatorInfoContainer.visibility = View.VISIBLE // Moved to ExploreFragment cards
                }
                return
            }

            // Fallback if remote lookup failed
            if (personaFromRemote == null && usuarioFromRemote == null) {
                Log.e("CourseDetailFragment", "Usuario not found: $creatorUsername")
            }
        } catch (e: Exception) {
            Log.e("CourseDetailFragment", "Error loading creator info", e)
            // withContext(Dispatchers.Main) {
            //     creatorInfoContainer.visibility = View.GONE
            //     Toast.makeText(context, "Error al cargar información del creador", Toast.LENGTH_SHORT).show()
            // }
        }
    }

    // New method to update subscribe button state
    // Subscription button state management - Moved to ExploreFragment cards
    // private fun updateSubscribeButtonState(isSubscribed: Boolean) {
    //     subscribeButton.apply {
    //         if (isSubscribed) {
    //             text = "SUSCRITO"
    //             setBackgroundResource(R.drawable.rounded_button_subscribed_background)
    //         } else {
    //             text = "SUSCRIBIRSE"
    //             setBackgroundResource(R.drawable.rounded_button_background)
    //         }
    //     }
    // }

    // Format subscriber count (e.g., 1.25M, 450K, etc.)
    private fun formatSubscriberCount(count: Int): String {
        return when {
            count >= 1000000 -> {
                val millions = count / 1000000.0
                String.format("%.2f M", millions)
            }
            count >= 1000 -> {
                val thousands = count / 1000.0
                String.format("%.1f K", thousands)
            }
            else -> "$count suscriptores"
        }
    }

    // Add this method to handle subscription functionality
    private fun handleSubscription() {
        val currentUser = currentUsername
        val creatorUser = courseCreatorUsername
        val currentUserId = sessionManager.getUserId()
        val creatorId = creatorUserId

        if (currentUser == null || currentUserId == -1L) {
            showSafeToast("Debes iniciar sesión para suscribirte")
            return
        }

        if (creatorUser == null || creatorId == -1L) {
            showSafeToast("Error: No se puede identificar al creador del curso")
            return
        }

        // Get the subscription state

        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (isSubscribed) {
                    // Desuscribirse
                    val result = withContext(Dispatchers.IO) {
                        BackendApiService.unsubscribe(creatorId)
                    }
                    if (result is ApiResult.Success) {
                        isSubscribed = false
                        showSafeToast("Te has desuscrito de $creatorUser")
                    } else {
                        showSafeToast("Error al desuscribir: ${(result as? ApiResult.Error)?.message}")
                    }
                } else {
                    // Suscribirse
                    val result = withContext(Dispatchers.IO) {
                        BackendApiService.subscribe(creatorId)
                    }
                    if (result is ApiResult.Success) {
                        isSubscribed = true
                        showSafeToast("Te has suscrito a $creatorUser")
                    } else {
                        showSafeToast("Error al suscribir: ${(result as? ApiResult.Error)?.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error processing subscription", e)
                showSafeToast("Error al procesar la suscripción: ${e.message}")
            }
        }
    }

    // Add this method to update tab visual selection
    private fun updateTabSelection() {
        val isDocs = currentTab == "documentos"
        // Rely on stateful backgrounds and text color selectors from XML
        tabDocumentos.isSelected = isDocs
        tabTareas.isSelected = !isDocs
    }

    // Fast content filtering without full reload
    private fun filterContentQuickly() {
        // Animate out existing content first (fast fade)
        topicsContainer.animate()
            .alpha(0f)
            .setDuration(100) // Very fast fade out
            .withEndAction {
                // Filter and show content immediately
                for (i in 0 until topicsContainer.childCount) {
                    val topicView = topicsContainer.getChildAt(i)
                    val contentContainer = topicView.findViewById<LinearLayout>(R.id.topicContentContainer)
                    val tasksContainer = topicView.findViewById<LinearLayout>(R.id.tasksDetailContainer)
                    
                    when (currentTab) {
                        "documentos" -> {
                            contentContainer?.visibility = View.VISIBLE
                            tasksContainer?.visibility = View.GONE
                        }
                        "tareas" -> {
                            contentContainer?.visibility = View.GONE
                            tasksContainer?.visibility = View.VISIBLE
                        }
                    }
                }
                
                // Fast fade in
                topicsContainer.animate()
                    .alpha(1f)
                    .setDuration(150) // Quick fade in
                    .start()
            }
            .start()
    }

    // Ultra-fast content filtering using cached data
    private fun filterContentUltraFast() {
        // No fade animation - instant switching
        for (i in 0 until topicsContainer.childCount) {
            val topicView = topicsContainer.getChildAt(i)
            val contentContainer = topicView.findViewById<LinearLayout>(R.id.topicContentContainer)
            val tasksContainer = topicView.findViewById<LinearLayout>(R.id.tasksDetailContainer)
            
            // Instant visibility switching
            when (currentTab) {
                "documentos" -> {
                    contentContainer?.visibility = View.VISIBLE
                    tasksContainer?.visibility = View.GONE
                }
                "tareas" -> {
                    contentContainer?.visibility = View.GONE
                    tasksContainer?.visibility = View.VISIBLE
                }
            }
        }
    }

    // Cache data structures for faster filtering
    private var cachedTopicsData: MutableList<Triple<Topic, List<ContentItem>, List<Task>>> = mutableListOf()

    // Pre-load and cache all content for ultra-fast filtering
    private fun cacheTopicData(topics: List<Topic>, allContentItems: List<ContentItem>, allTasks: List<Task>) {
        cachedTopicsData.clear()
        for (topic in topics) {
            val topicContent = allContentItems.filter { it.topicId == topic.id }
            val topicTasks = allTasks.filter { it.topicId == topic.id }
            cachedTopicsData.add(Triple(topic, topicContent, topicTasks))
        }
    }

    // Render topics with pre-cached data
    private fun renderCachedTopics() {
        topicsContainer.removeAllViews()
        for ((topic, contentItems, tasks) in cachedTopicsData) {
            addOptimizedTopicView(topic, contentItems, tasks)
        }
    }

    // Optimized topic view creation with immediate rendering
    private fun addOptimizedTopicView(topic: Topic, contentItems: List<ContentItem>, tasks: List<Task>) {
        val inflater = LayoutInflater.from(context)
        val topicView = inflater.inflate(R.layout.item_course_topic_detail, topicsContainer, false)

        val topicTitleTextView = topicView.findViewById<TextView>(R.id.topicNameTextView)
        val topicDescriptionTextView = topicView.findViewById<TextView>(R.id.topicDescriptionTextView)
        val topicContentContainer = topicView.findViewById<LinearLayout>(R.id.topicContentContainer)
        val tasksContainer = topicView.findViewById<LinearLayout>(R.id.tasksDetailContainer)

        // Set basic info immediately
        topicTitleTextView.text = topic.name
        topicDescriptionTextView.text = topic.description

        // Setup containers based on current filter
        when (currentTab) {
            "documentos" -> {
                setupContentContainerFast(topicContentContainer, contentItems)
                tasksContainer.visibility = View.GONE
                topicContentContainer.visibility = View.VISIBLE
            }
            "tareas" -> {
                setupTasksContainerFast(tasksContainer, tasks, topic)
                topicContentContainer.visibility = View.GONE
                tasksContainer.visibility = View.VISIBLE
            }
        }

        topicsContainer.addView(topicView)
    }

    // Fast content container setup
    private fun setupContentContainerFast(container: LinearLayout, contentItems: List<ContentItem>) {
        container.removeAllViews()
        
        if (contentItems.isNotEmpty()) {
            val sortedItems = contentItems.sortedBy { it.orderIndex }
            for (item in sortedItems) {
                addContentViewFast(item, container)
            }
        } else {
            val noContentMsg = TextView(context).apply { 
                text = "Sin contenido para este tema"
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                setPadding(16, 16, 16, 16)
                textSize = 14f
            }
            container.addView(noContentMsg)
        }
    }

    // Fast tasks container setup
    private fun setupTasksContainerFast(container: LinearLayout, tasks: List<Task>, topic: Topic) {
        container.removeAllViews()
        
        if (tasks.isNotEmpty()) {
            val sortedTasks = tasks.sortedBy { it.orderIndex }
            for (task in sortedTasks) {
                addTaskViewFast(task, container)
            }
        } else {
            val noTasksMsg = TextView(context).apply { 
                text = "Sin tareas para este tema"
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                setPadding(16, 16, 16, 16)
                textSize = 14f
            }
            container.addView(noTasksMsg)
        }

        // Add "Agregar Tarea" button for creators
        if (isCurrentUserCreator) {
            val addTaskBtn = Button(context).apply {
                text = "Agregar Tarea"
                textSize = 12f
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
                layoutParams = params
                setBackgroundResource(R.drawable.button_premium)
                setPadding(24, 12, 24, 12)
                setOnClickListener {
                    navigateToAddTask(topic.id, topic.courseId)
                }
            }
            container.addView(addTaskBtn)
        }
    }

    // Fast content view creation (simplified)
    private fun addContentViewFast(item: ContentItem, container: LinearLayout) {
        Log.d("CourseDetailFragment", "addContentViewFast - Name: ${item.name}, Type: ${item.contentType}")
        
        val contentView = LayoutInflater.from(context).inflate(
            R.layout.item_content_mini,
            container,
            false
        )
        
        val iconView = contentView.findViewById<ImageView>(R.id.contentIconView)
        val nameView = contentView.findViewById<TextView>(R.id.contentNameView)
        val typeView = contentView.findViewById<TextView>(R.id.contentTypeView)
        
        nameView?.text = item.name ?: "Archivo adjunto"
        
        // Set icon and type based on content type
        when (item.contentType.lowercase()) {
            "video" -> {
                iconView?.setImageResource(android.R.drawable.ic_media_play)
                typeView?.text = "Video"
            }
            "pdf" -> {
                iconView?.setImageResource(android.R.drawable.ic_menu_agenda)
                typeView?.text = "PDF"
            }
            else -> {
                iconView?.setImageResource(android.R.drawable.ic_menu_help)
                typeView?.text = "Documento"
            }
        }
        
        contentView.setOnClickListener { openContent(item) }
        
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 8
        }
        contentView.layoutParams = params
        container.addView(contentView)
    }

    // Fast task view creation (simplified)
    private fun addTaskViewFast(task: Task, container: LinearLayout) {
        val taskView = TextView(context).apply {
            text = "📋 ${task.name ?: "Tarea sin título"}"
            textSize = 14f
            setPadding(16, 12, 16, 12)
            setTextColor(resources.getColor(android.R.color.white, null))
            setOnClickListener { 
                // Simple task interaction
                showSafeToast("Tarea: ${task.name}")
            }
            background = resources.getDrawable(R.drawable.bg_card_premium, null)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            layoutParams = params
        }
        container.addView(taskView)
    }

    // Modify addTopicView to include tasks with better visual distinction and handle filtering
    private fun addTopicView(topic: Topic, contentItems: List<ContentItem>, tasks: List<Task>) {
        val inflater = LayoutInflater.from(context)
        val topicView = inflater.inflate(R.layout.item_course_topic_detail, topicsContainer, false)

        val topicTitleTextView = topicView.findViewById<TextView>(R.id.topicNameTextView)
        val topicDescriptionTextView = topicView.findViewById<TextView>(R.id.topicDescriptionTextView)
        val topicContentContainer = topicView.findViewById<LinearLayout>(R.id.topicContentContainer)
        val tasksContainer = topicView.findViewById<LinearLayout>(R.id.tasksDetailContainer)
        val editTopicButton = topicView.findViewById<ImageButton>(R.id.editTopicButton)
        val deleteTopicButton = topicView.findViewById<ImageButton>(R.id.deleteTopicButton)

        // Headers (keep them for context, but they might be inside hidden containers)
        // Define contentHeader here, similar to tasksHeader
        val contentHeader = TextView(context).apply {
            text = "CONTENIDO DEL TEMA"
            setTextAppearance(android.R.style.TextAppearance_Medium)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 8)
            setTextColor(resources.getColor(android.R.color.holo_blue_dark, null))
        }

        // Add a header for tasks
        val tasksHeader = TextView(context).apply {
            text = "TAREAS"
            setTextAppearance(android.R.style.TextAppearance_Medium)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
            setTextColor(resources.getColor(android.R.color.holo_green_dark, null))
        }

        topicTitleTextView.text = topic.name
        if (topic.description.isNotEmpty()) {
            topicDescriptionTextView.text = topic.description
            topicDescriptionTextView.visibility = View.VISIBLE
        } else {
            topicDescriptionTextView.visibility = View.GONE
        }

        // Configure edit/delete buttons - only visible for course creator AND only in documents tab
        val showTopicButtons = isCurrentUserCreator && currentTab == "documentos"
        editTopicButton?.visibility = if (showTopicButtons) View.VISIBLE else View.GONE
        deleteTopicButton?.visibility = if (showTopicButtons) View.VISIBLE else View.GONE

        editTopicButton?.setOnClickListener {
            navigateToEditTopic(topic.id)
        }

        deleteTopicButton?.setOnClickListener {
            showDeleteTopicConfirmation(topic)
        }

        // --- Filtering Logic ---
        if (currentTab == "documentos") {
            // Show Content, Hide Tasks
            tasksContainer.visibility = View.GONE

            topicContentContainer.visibility = View.VISIBLE
            topicContentContainer.removeAllViews() // Clear before adding
            topicContentContainer.addView(contentHeader) // Add header (Now defined)

            Log.d("CourseDetailFragment", "📄 addTopicView for topic '${topic.name}' (id=${topic.id}): received ${contentItems.size} content items")
            
            // Double-check: filter to ensure only topic-level content (taskId null/0)
            val topicOnlyContent = contentItems.filter { it.taskId == null || it.taskId == 0L }
            Log.d("CourseDetailFragment", "📄 After filtering taskId: ${topicOnlyContent.size} topic-level content items")
            
            val sortedContent = topicOnlyContent.sortedBy { it.orderIndex }
            if (sortedContent.isNotEmpty()) {
                Log.d("CourseDetailFragment", "📄 ✅ Showing ${sortedContent.size} content items for topic ${topic.id}")
                for (item in sortedContent) {
                    Log.d("CourseDetailFragment", "   📦 Content: id=${item.id}, name='${item.name}', uri='${item.uriString.take(60)}...', type=${item.contentType}")
                    addContentView(item, topicContentContainer)
                }
            } else {
                Log.w("CourseDetailFragment", "⚠️ No topic-level content items for topic ${topic.id} ('${topic.name}')")
                val noContentMsg = TextView(context).apply { 
                    text = "Sin contenido para este tema" 
                    setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    setPadding(0, 8, 0, 8)
                }
                topicContentContainer.addView(noContentMsg)
            }

        } else { // currentTab == "tareas"
            // Show Tasks, Hide Content
            topicContentContainer.visibility = View.GONE

            tasksContainer.visibility = View.VISIBLE
            tasksContainer.removeAllViews() // Clear container before adding header/tasks/button
            tasksContainer.addView(tasksHeader)

            val sortedTasks = tasks.sortedBy { it.orderIndex }
            if (sortedTasks.isNotEmpty()) {
                for (task in sortedTasks) {
                    addTaskView(task, tasksContainer) // Add the task view
                }
            } else {
                val noTasksMsg = TextView(context).apply { text = "Sin tareas para este tema" }
                tasksContainer.addView(noTasksMsg)
            }

            // Add the "Agregar Tarea" button directly to tasksContainer only when viewing tasks and if user is creator
            if (isCurrentUserCreator) {
                val addTaskBtn = Button(context).apply {
                    text = "Agregar Tarea a este Tema"
                    val params = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = 16
                        gravity = android.view.Gravity.CENTER_HORIZONTAL
                    }
                    layoutParams = params
                    setBackgroundResource(R.drawable.button_background)
                    setPadding(32, 16, 32, 16)
                    setOnClickListener {
                        // Navigate to CourseTaskFragment to add a new task for this topic
                        // Use fragment's courseId instead of topic.courseId (which might be null)
                        if (courseId != -1L) {
                            navigateToAddTask(topic.id, courseId)
                        } else {
                            Log.e("CourseDetailFragment", "Cannot add task: courseId is invalid (-1)")
                            showSafeToast("Error: ID del curso no válido")
                        }
                    }
                }
                tasksContainer.addView(addTaskBtn)
            }
        }

        topicsContainer.addView(topicView)

        // Add a visual separator between topics
        val separator = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2 // Height of the separator line
            )
            setBackgroundColor(resources.getColor(android.R.color.darker_gray))
        }
        topicsContainer.addView(separator)
    }

    // Add this method to navigate to CourseTaskFragment for adding a new task
    private fun navigateToAddTask(topicId: Long, courseId: Long) {
        val bundle = Bundle().apply {
            putLong("topicId", topicId)
            putLong("courseId", courseId)
            putLong("taskId", -1L) // Required argument, -1 for new task creation
        }
        findNavController().navigate(R.id.action_courseDetailFragment_to_courseTaskFragment, bundle)
    }

    // Modify addTaskView to handle null submitTaskButton
    private fun addTaskView(task: Task, container: LinearLayout) {
        val inflater = LayoutInflater.from(context)
        val taskView = inflater.inflate(R.layout.item_course_task_detail, container, false)

        val taskNameTextView = taskView.findViewById<TextView>(R.id.taskNameTextView)
        val taskDescriptionTextView = taskView.findViewById<TextView>(R.id.taskDescriptionTextView)
        val editTaskButton = taskView.findViewById<ImageButton>(R.id.editTaskButton)
        val deleteTaskButton = taskView.findViewById<ImageButton>(R.id.deleteTaskButton)
        val submitTaskButton = taskView.findViewById<Button>(R.id.uploadSubmissionButton)
        val gradeStatusTextView = taskView.findViewById<TextView>(R.id.gradeStatusTextView)

        // Add a visual indicator that this is a task
        val taskIndicator = taskView.findViewById<View>(R.id.taskIndicator)
        taskIndicator?.setBackgroundColor(resources.getColor(android.R.color.holo_green_light))

    // Show local name if available; otherwise fetch from Supabase by id
    if (!task.name.isNullOrBlank()) {
        taskNameTextView.text = task.name
    } else {
        taskNameTextView.text = "(Sin título)"
        // Try to fetch remote title asynchronously
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val remote = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    BackendApiService.getTaskById(task.id)
                }
                if (remote is ApiResult.Success && !remote.data?.name.isNullOrBlank()) {
                    taskNameTextView.text = remote.data?.name
                }
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Failed to fetch remote task title", e)
            }
        }
    }
        if (!task.description.isNullOrBlank()) {
            taskDescriptionTextView.text = task.description
            taskDescriptionTextView.visibility = View.VISIBLE
        } else {
            taskDescriptionTextView.visibility = View.GONE
        }

        // Load and display content items
        val taskContentContainer = taskView.findViewById<LinearLayout>(R.id.taskContentContainer)
        val taskContentLabel = taskView.findViewById<TextView>(R.id.taskContentLabel)
        val contentSeparator = taskView.findViewById<View>(R.id.contentSeparator)
        
        // Hacer que el contenedor sea clicable para abrir archivos
        taskContentContainer?.setOnClickListener {
            // El click se maneja en los items individuales de contenido
        }
        
        // CRITICAL: Clear container IMMEDIATELY before async load to prevent duplicates
        taskContentContainer?.removeAllViews()
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("CourseDetailFragment", "Loading content items for taskId=${task.id}")
                
                // Clear again at start of async block to ensure no race conditions
                taskContentContainer?.removeAllViews()
                
                // Fetch content items from BackendApiService
                var contentItems = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val result = BackendApiService.getContentItemsByTask(task.id)
                    (result as? ApiResult.Success)?.data ?: emptyList()
                }
                
                Log.d("CourseDetailFragment", "Loaded ${contentItems.size} content items from backend for taskId=${task.id}")
                
                // Only fallback if backend returned empty
                if (contentItems.isEmpty()) {
                    Log.d("CourseDetailFragment", "No content items from backend for taskId=${task.id}")
                }
                
                Log.d("CourseDetailFragment", "Found ${contentItems.size} content items for taskId=${task.id}")
                
                // NO DEDUPLICATION: We must show all items from DB (even duplicates) so user can delete them
                // The user explicitly requires seeing all records to manage the "content_items" table in Supabase
                val uniqueContentItems = contentItems
                
                Log.d("CourseDetailFragment", "Displaying all ${uniqueContentItems.size} content items (duplicates included)")
                
                // Always show the container section for better UX
                contentSeparator?.visibility = View.VISIBLE
                taskContentLabel?.visibility = View.VISIBLE
                taskContentContainer?.visibility = View.VISIBLE
                
                if (uniqueContentItems.isNotEmpty()) {
                    for (contentItem in uniqueContentItems) {
                        Log.d("CourseDetailFragment", "Adding content item: name=${contentItem.name}, type=${contentItem.contentType}, uri=${contentItem.uriString}, isRemote=${isRemoteUrl(contentItem.uriString)}")
                        
                        val ctx = context ?: break
                        val contentItemView = LayoutInflater.from(ctx).inflate(
                            R.layout.item_content_mini,
                            taskContentContainer,
                            false
                        )
                        
                        val iconView = contentItemView.findViewById<ImageView>(R.id.contentIconView)
                        val nameView = contentItemView.findViewById<TextView>(R.id.contentNameView)
                        val typeView = contentItemView.findViewById<TextView>(R.id.contentTypeView)
                        val deleteButton = contentItemView.findViewById<ImageButton>(R.id.deleteContentButton)
                        
                        // Show cloud emoji if it's a remote URL
                        val displayName = if (isRemoteUrl(contentItem.uriString)) {
                            "☁️ ${contentItem.name ?: "Archivo adjunto"}"
                        } else {
                            contentItem.name ?: "Archivo adjunto"
                        }
                        nameView?.text = displayName
                        
                        // Set icon and type based on content type
                        when (contentItem.contentType.lowercase()) {
                            "video" -> {
                                iconView?.setImageResource(android.R.drawable.ic_media_play)
                                typeView?.text = "Video"
                            }
                            "pdf" -> {
                                iconView?.setImageResource(android.R.drawable.ic_menu_agenda)
                                typeView?.text = "PDF"
                            }
                            else -> {
                                iconView?.setImageResource(android.R.drawable.ic_menu_help)
                                typeView?.text = "Documento"
                            }
                        }
                        
                        // Configure delete button - only visible for course creator
                        deleteButton?.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE
                        deleteButton?.setOnClickListener {
                            showDeleteContentConfirmation(contentItem, taskContentContainer!!, contentItemView)
                        }
                        
                        // Make the whole item clickable
                        contentItemView.setOnClickListener {
                            openContent(contentItem)
                        }
                        
                        taskContentContainer?.addView(contentItemView)
                    }
                } else {
                    // Show a message when no content is available
                    context?.let { ctx ->
                        val noContentView = TextView(ctx).apply {
                            text = "No hay archivos adjuntos"
                            setTextColor(ctx.resources.getColor(android.R.color.darker_gray, null))
                            setPadding(16, 16, 16, 16)
                            textSize = 13f
                        }
                        taskContentContainer?.addView(noContentView)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CourseDetailFragment", "Error loading content items for taskId=${task.id}", e)
                // Show error message in container
                taskContentContainer?.removeAllViews()
                context?.let { ctx ->
                    val errorView = TextView(ctx).apply {
                        text = "Error al cargar archivos"
                        setTextColor(ctx.resources.getColor(android.R.color.holo_red_light, null))
                        setPadding(16, 16, 16, 16)
                        textSize = 13f
                    }
                    taskContentContainer?.addView(errorView)
                }
            }
        }

        // Only show edit button for course creators
        editTaskButton?.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE
        deleteTaskButton?.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE

        // Set click listener for the edit button (only if visible)
        editTaskButton?.setOnClickListener {
            navigateToEditTask(task.id, task.topicId)
        }

        // Set click listener for the delete button (only if visible)
        deleteTaskButton?.setOnClickListener {
            showDeleteTaskConfirmation(task)
        }

        // For course creator: show view submissions button
        // For students: show submit task button and grade status
        if (isCurrentUserCreator) {
            submitTaskButton.text = "Ver Entregas"
            submitTaskButton.visibility = View.VISIBLE
            gradeStatusTextView?.visibility = View.GONE
                submitTaskButton.setOnClickListener {
                val bundle = Bundle().apply {
                    putLong("taskId", task.id)
                    putString("taskName", task.name)
                    // Ensure we pass a String (navigation expects a string). Use empty string as fallback.
                    putString("courseCreatorUsername", courseCreatorUsername ?: "")
                }
                findNavController().navigate(R.id.action_courseDetailFragment_to_taskSubmissionFragment, bundle)
            }
        } else {
            // For students: check if they have a submission and show grade if available
            submitTaskButton.text = "Subir Tarea"
            submitTaskButton.visibility = View.VISIBLE
            checkStudentSubmission(task.id, gradeStatusTextView)
                submitTaskButton.setOnClickListener {
                val bundle = Bundle().apply {
                    putLong("taskId", task.id)
                    putString("taskName", task.name)
                    // Ensure we pass a String (navigation expects a string). Use empty string as fallback.
                    putString("courseCreatorUsername", courseCreatorUsername ?: "")
                }
                findNavController().navigate(R.id.action_courseDetailFragment_to_taskSubmissionFragment, bundle)
            }
        }

        container.addView(taskView)
    }

    // Refresh topics and re-render UI from backend for the current course
    private fun refreshTopicsFromBackend() {
        if (courseId == -1L) return

        // Cancel previous job if active to prevent race conditions and duplication
        refreshJob?.cancel()
        
        // Reset loading flag to prevent blocking - this is a lightweight refresh, not full load
        isLoadingCourseDetails = false
        
        // Show skeleton during refresh
        startSkeletonAnimation()

        refreshJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val act = requireActivity()
                val lookupId = if (resolvedCourseId > 0) resolvedCourseId else courseId
                val topicsResult = withContext(Dispatchers.IO) { BackendApiService.getTopicsByCourse(lookupId) }
                val topics: List<Topic> = if (topicsResult is ApiResult.Success) topicsResult.data ?: emptyList() else emptyList()

                topicsContainer.removeAllViews()

                val sortedTopics = topics.sortedBy { it.orderIndex }

                // If the tasks tab is selected, fetch tasks for these topics from backend
                // Prepare content items grouped by topicId when viewing documentos
                var contentByTopic: Map<Long, List<ContentItem>> = emptyMap()
                var tasksByTopic: Map<Long, List<Task>> = emptyMap()
                
                if (currentTab == "tareas") {
                    try {
                        val topicIds = sortedTopics.map { it.id }
                        if (topicIds.isNotEmpty()) {
                            val allTasks = mutableListOf<Task>()
                            for (tid in topicIds) {
                                val tasksResult = withContext(Dispatchers.IO) { BackendApiService.getTasksByTopic(tid) }
                                if (tasksResult is ApiResult.Success) allTasks.addAll(tasksResult.data ?: emptyList())
                            }
                            tasksByTopic = allTasks.groupBy { it.topicId }
                        }
                    } catch (e: Exception) {
                        Log.w("CourseDetailFragment", "refreshTopics: failed fetching tasks for topics", e)
                    }
                } else {
                    // currentTab == "documentos" - fetch content items for topics via tasks
                    try {
                        val topicIds = sortedTopics.map { it.id }
                        if (topicIds.isNotEmpty()) {
                            Log.d("CourseDetailFragment", "📚 [Refresh] Fetching content items for ${topicIds.size} topics")
                            val allContentItems = mutableListOf<ContentItem>()
                            for (tid in topicIds) {
                                val tasksResult = withContext(Dispatchers.IO) { BackendApiService.getTasksByTopic(tid) }
                                val tasks = if (tasksResult is ApiResult.Success) tasksResult.data ?: emptyList() else emptyList()
                                for (task in tasks) {
                                    val ciResult = withContext(Dispatchers.IO) { BackendApiService.getContentItemsByTask(task.id) }
                                    if (ciResult is ApiResult.Success) allContentItems.addAll(ciResult.data ?: emptyList())
                                }
                            }
                            // Filter: only include topic-level content (taskId == null)
                            val topicLevelContent = allContentItems.filter { it.taskId == null || it.taskId == 0L }
                            contentByTopic = topicLevelContent.groupBy { it.topicId }
                            Log.d("CourseDetailFragment", "📚 [Refresh] Fetched ${allContentItems.size} content items, ${topicLevelContent.size} topic-level")
                        }
                    } catch (e: Exception) {
                        Log.w("CourseDetailFragment", "refreshTopics: failed fetching content items for topics", e)
                    }
                }

                // If viewing tasks, filter out topics without tasks
                val topicsToRender = if (currentTab == "tareas") {
                    sortedTopics.filter { t -> (tasksByTopic[t.id] ?: emptyList()).isNotEmpty() }
                } else {
                    sortedTopics
                }

                for (topic in topicsToRender) {
                    val tasks = tasksByTopic[topic.id] ?: emptyList()
                    val contentForTopic = contentByTopic[topic.id] ?: emptyList()
                    Log.d("CourseDetailFragment", "📖 [Refresh] Topic '${topic.name}' (id=${topic.id}): ${contentForTopic.size} content items, ${tasks.size} tasks")
                    addTopicView(topic, contentForTopic, tasks)
                }
                
                // IMPORTANTE: Recalcular progreso de estudiantes después de refrescar
                recalculateStudentProgress()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e("CourseDetailFragment", "Error refreshing topics", e)
                }
            } finally {
                // Always stop skeleton animation when refresh completes
                stopSkeletonAnimation()
                Log.d("CourseDetailFragment", "✅ Refresh complete, skeleton hidden")
            }
        }
    }

    // Add this helper method to check student submission status
    private fun checkStudentSubmission(taskId: Long, gradeStatusTextView: TextView?) {
        if (gradeStatusTextView == null) return

    val username = sessionManager.getUsername()
    val userId = sessionManager.getUserId()
    if (userId <= 0L) return

    CoroutineScope(Dispatchers.Main).launch {
        try {
            // Fetch submission from backend
            var submission: com.example.tareamov.data.entity.TaskSubmission? = null
            try {
                val subResult = withContext(Dispatchers.IO) { BackendApiService.getSubmissionsByTask(taskId) }
                if (subResult is ApiResult.Success) {
                    submission = (subResult.data ?: emptyList()).firstOrNull { it.studentId == userId }
                }
                Log.d("CourseDetailFragment", "Backend fetch for taskId=$taskId userId=$userId -> submission=${submission}")
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Error fetching submission for taskId=$taskId", e)
            }

                if (submission != null) {
                    if (submission.grade != null) {
                        gradeStatusTextView.text = "Calificación: ${submission.grade}/10"
                        gradeStatusTextView.setTextColor(resources.getColor(android.R.color.holo_green_light, null))
                    } else {
                        gradeStatusTextView.text = "Entregada - Pendiente de calificación"
                        gradeStatusTextView.setTextColor(resources.getColor(android.R.color.holo_blue_light, null))
                    }
                    gradeStatusTextView.visibility = View.VISIBLE
                } else {
                    gradeStatusTextView.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error checking submission", e)
                gradeStatusTextView.visibility = View.GONE
            }
        }
    }    // Add this method to load content items for a specific task
    private fun loadTaskContentItems(taskId: Long, container: LinearLayout) {
        // Método obsoleto - el contenedor de contenido de tareas fue eliminado del layout
        // Mantener solo para evitar errores de compilación si hay referencias restantes
        Log.d("CourseDetailFragment", "loadTaskContentItems: Container removed from layout")
    }// Modify addContentView to use item_content_mini.xml for consistent display
    private fun addContentView(item: ContentItem, container: LinearLayout, isTaskContent: Boolean = false) {
        Log.d("CourseDetailFragment", "📄 Adding content view - Name: ${item.name}, Type: ${item.contentType}, URI: ${item.uriString}")
        
        val inflater = LayoutInflater.from(context)
        val contentView = inflater.inflate(R.layout.item_content_mini, container, false)

        val iconView = contentView.findViewById<ImageView>(R.id.contentIconView)
        val nameView = contentView.findViewById<TextView>(R.id.contentNameView)
        val typeView = contentView.findViewById<TextView>(R.id.contentTypeView)
        val deleteButton = contentView.findViewById<ImageButton>(R.id.deleteContentButton)

        // Show cloud icon if it's a remote URL
        val displayName = if (isRemoteUrl(item.uriString)) {
            "☁️ ${item.name ?: "Archivo adjunto"}"
        } else {
            item.name ?: "Archivo adjunto"
        }
        nameView?.text = displayName

        // Set icon and type based on content type
        when (item.contentType.lowercase()) {
            "video" -> {
                iconView?.setImageResource(android.R.drawable.ic_media_play)
                typeView?.text = "Video"
            }
            "pdf" -> {
                iconView?.setImageResource(android.R.drawable.ic_menu_agenda)
                typeView?.text = "PDF"
            }
            "document" -> {
                iconView?.setImageResource(android.R.drawable.ic_menu_edit)
                typeView?.text = "Documento"
            }
            else -> {
                iconView?.setImageResource(android.R.drawable.ic_menu_help)
                typeView?.text = "Archivo"
            }
        }

        // Configure delete button - only visible for course creator
        deleteButton?.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE
        deleteButton?.setOnClickListener {
            showDeleteContentConfirmation(item, container, contentView)
        }

        // Make the whole item clickable to open content
        contentView.setOnClickListener {
            openContent(item)
        }

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = 8
        }
        contentView.layoutParams = params
        container.addView(contentView)
        
        Log.d("CourseDetailFragment", "✅ Content view added successfully for: ${item.name}")
    }

    // Helper method to get content type description
    private fun getContentTypeDescription(contentType: String): String {
        return when (contentType.lowercase()) {
            "video" -> "Archivo de video"
            "document" -> "Documento"
            "pdf" -> "Documento PDF"
            "image" -> "Imagen"
            else -> "Archivo adjunto"
        }
    }

    // Helper method to load content thumbnail
    private fun loadContentThumbnail(item: ContentItem, imageView: ImageView) {
        try {
            val uri = Uri.parse(item.uriString)
            
            when (item.contentType.lowercase()) {
                "video" -> {
                    // For videos, try to load video thumbnail
                    Glide.with(this)
                        .load(uri)
                        .centerCrop()
                        .placeholder(R.drawable.content_thumbnail_placeholder)
                        .error(R.drawable.content_thumbnail_placeholder)
                        .into(imageView)
                }
                "image" -> {
                    // For images, load the image directly
                    Glide.with(this)
                        .load(uri)
                        .centerCrop()
                        .placeholder(R.drawable.content_thumbnail_placeholder)
                        .error(R.drawable.content_thumbnail_placeholder)
                        .into(imageView)
                }
                else -> {
                    // For documents and other files, use placeholder
                    imageView.setImageResource(R.drawable.content_thumbnail_placeholder)
                }
            }
        } catch (e: Exception) {
            Log.e("CourseDetailFragment", "Error loading thumbnail for content: ${item.name}", e)
            imageView.setImageResource(R.drawable.content_thumbnail_placeholder)
        }
    }    // Helper method to set content type icon
    private fun setContentTypeIcon(contentType: String, iconView: ImageView) {
        val iconRes = when (contentType.lowercase()) {
            "video" -> R.drawable.ic_play_circle
            "document", "pdf" -> R.drawable.ic_document
            "image" -> R.drawable.ic_image
            else -> android.R.drawable.ic_menu_info_details
        }
        iconView.setImageResource(iconRes)
    }

    // Add this function to navigate to CourseTaskFragment for editing
    private fun navigateToEditTask(taskId: Long, topicId: Long) {
        val bundle = Bundle().apply {
            putLong("taskId", taskId)
            putLong("topicId", topicId)
            putLong("courseId", if (resolvedCourseId > 0) resolvedCourseId else courseId)
            putString("courseName", courseName)
        }
        // Use the correct action ID from nav_graph.xml
        findNavController().navigate(R.id.action_courseDetailFragment_to_courseTaskFragment, bundle)
    }

    /**
     * Recalcula el progreso de todos los estudiantes del curso.
     * Llamar después de cualquier CRUD en tareas.
     */
    private fun recalculateStudentProgress() {
        if (resolvedCourseId <= 0) {
            Log.w("CourseDetailFragment", "Cannot recalculate progress: invalid courseId")
            return
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d("CourseDetailFragment", "🔄 Recalculating student progress for course $resolvedCourseId")
                // Backend handles progress recalculation server-side
                val progressResult = withContext(Dispatchers.IO) {
                    BackendApiService.getAllProgressByCourse(resolvedCourseId)
                }
                val updatedCount = if (progressResult is ApiResult.Success) progressResult.data?.size ?: 0 else 0
                Log.i("CourseDetailFragment", "✅ Updated progress for $updatedCount students")
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error recalculating student progress", e)
            }
        }
    }
    
    /**
     * Recalcula el progreso del estudiante actual al entrar al curso.
     * Solo actualiza el progreso del estudiante que está viendo el curso, no de todos.
     * Esto evita sobrecarga innecesaria al abrir un curso.
     */
    private fun recalculateStudentProgressOnEntry(courseIdToUse: Long) {
        val username = currentUsername ?: return
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d("CourseDetailFragment", "🔄 Recalculating progress on entry: user=$username, course=$courseIdToUse")
                
                withContext(Dispatchers.IO) {
                    try {
                        // Obtener todas las tareas del curso via BackendApiService
                        val topicsResult = BackendApiService.getTopicsByCourse(courseIdToUse)
                        val topics = if (topicsResult is ApiResult.Success) topicsResult.data ?: emptyList() else emptyList()
                        val topicIds = topics.map { it.id }
                        
                        if (topicIds.isEmpty()) {
                            Log.d("CourseDetailFragment", "⚠️ No topics found for course $courseIdToUse")
                            return@withContext
                        }
                        
                        val allTasks = mutableListOf<com.example.tareamov.data.entity.Task>()
                        for (topicId in topicIds) {
                            val tasksResult = BackendApiService.getTasksByTopic(topicId)
                            if (tasksResult is ApiResult.Success) {
                                tasksResult.data?.let { allTasks.addAll(it) }
                            }
                        }
                        Log.d("CourseDetailFragment", "📚 Found ${allTasks.size} tasks in course")
                        
                        if (allTasks.isEmpty()) {
                            Log.d("CourseDetailFragment", "⚠️ No tasks found for course $courseIdToUse")
                            return@withContext
                        }
                        
                        // Get user ID from username
                        val userResult = BackendApiService.getUserByUsername(username)
                        val userId = if (userResult is ApiResult.Success) userResult.data?.id else null
                        if (userId == null) {
                            Log.e("CourseDetailFragment", "Failed to get user ID for username: $username")
                            return@withContext
                        }

                        // Obtener todas las entregas del estudiante para este curso
                        val submissionsResult = BackendApiService.getSubmissionsByCourse(courseIdToUse)
                        val allSubmissions = if (submissionsResult is ApiResult.Success) submissionsResult.data ?: emptyList() else emptyList()
                        val studentSubmissions = allSubmissions.filter { submission -> 
                            submission.studentId == userId && 
                            allTasks.any { task -> task.id == submission.taskId }
                        }
                        
                        Log.d("CourseDetailFragment", "📊 Student has ${studentSubmissions.size} submissions")
                        
                        // Crear mapa de entregas por taskId
                        val submissionMap = studentSubmissions.associateBy { it.taskId }
                        
                        // Calcular métricas
                        val tareasTotales = allTasks.size
                        var tareasCompletadas = 0
                        var totalGrade = 0f
                        
                        for (task in allTasks) {
                            val submission = submissionMap[task.id]
                            val grade = submission?.grade ?: 0f
                            
                            if (grade > 0) {
                                tareasCompletadas++
                            }
                            
                            totalGrade += grade
                        }
                        
                        val porcentajeProgreso = if (tareasTotales > 0) {
                            (tareasCompletadas.toFloat() / tareasTotales.toFloat()) * 100f
                        } else {
                            0f
                        }
                        
                        val promedio = if (tareasTotales > 0) {
                            totalGrade / tareasTotales.toFloat()
                        } else {
                            0f
                        }
                        
                        Log.d("CourseDetailFragment", "📈 Calculated metrics: totales=$tareasTotales, completadas=$tareasCompletadas, progreso=$porcentajeProgreso%, promedio=$promedio")
                        
                        // Upsert progress via BackendApiService
                        val progressData = mapOf<String, Any?>(
                            "curso_id" to courseIdToUse,
                            "usuario_estudiante" to userId,
                            "tareas_totales" to tareasTotales,
                            "tareas_completadas" to tareasCompletadas,
                            "porcentaje_progreso" to porcentajeProgreso,
                            "promedio" to promedio,
                            "calificacion_ponderada" to promedio
                        )
                        
                        val upsertResult = BackendApiService.upsertProgress(progressData)
                        
                        if (upsertResult is ApiResult.Success) {
                            Log.i("CourseDetailFragment", "✅ Progress updated successfully for $username")
                        } else {
                            Log.w("CourseDetailFragment", "⚠️ Failed to update progress via backend")
                        }
                        
                    } catch (e: Exception) {
                        Log.e("CourseDetailFragment", "❌ Error calculating student progress", e)
                    }
                }
                
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "❌ Error in recalculateStudentProgressOnEntry", e)
            }
        }
    }

    // Make sure we're properly handling content item clicks
    private fun openContent(item: ContentItem) {
        try {
            Log.d("CourseDetailFragment", "🎬 openContent called:")
            Log.d("CourseDetailFragment", "   - Name: ${item.name}")
            Log.d("CourseDetailFragment", "   - Type: ${item.contentType}")
            Log.d("CourseDetailFragment", "   - URI: ${item.uriString}")
            Log.d("CourseDetailFragment", "   - Is Remote URL: ${isRemoteUrl(item.uriString)}")
            
            // For videos, use our custom VideoPlayerActivity
            if (item.contentType == "video") {
                Log.d("CourseDetailFragment", "Opening video content: ${item.name}, URI: ${item.uriString}")

                // Validate and process the URI
                var processedUri = item.uriString
                
                // Handle different URI formats
                if (processedUri.isNotEmpty()) {
                    // Check if it's a remote URL (HTTP/HTTPS)
                    if (isRemoteUrl(processedUri) || 
                        processedUri.startsWith("http://") || 
                        processedUri.startsWith("https://")) {
                        // Remote URL - use directly
                        Log.d("CourseDetailFragment", "☁️ Opening remote video: $processedUri")
                    }
                    // If it's a file path without scheme, add file:// prefix
                    else if (!processedUri.startsWith("content://") && !processedUri.startsWith("file://") && !processedUri.startsWith("android.resource://")) {
                        val file = File(processedUri)
                        if (file.exists()) {
                            try {
                                // Try to get a content URI using FileProvider
                                val contentUri = FileProvider.getUriForFile(
                                    requireContext(),
                                    "${requireContext().packageName}.fileprovider",
                                    file
                                )
                                processedUri = contentUri.toString()
                                Log.d("CourseDetailFragment", "Converted file path to content URI: $processedUri")
                            } catch (e: Exception) {
                                // Fallback to file:// URI
                                processedUri = "file://$processedUri"
                                Log.d("CourseDetailFragment", "Using file URI as fallback: $processedUri")
                            }
                        } else {
                            Log.e("CourseDetailFragment", "File does not exist: $processedUri")
                        }
                    }
                } else {
                    Log.e("CourseDetailFragment", "Empty URI string for content item: ${item.name}")
                    showSafeToast("URI del video no válida")
                    return
                }

                // Create intent for our custom video player
                val intent = Intent(requireContext(), VideoPlayerActivity::class.java)

                // Pass all necessary information with the correct keys that VideoPlayerActivity expects
                intent.putExtra("video_path", processedUri)  // Use processed URI
                intent.putExtra("video_title", item.name ?: "Video")
                intent.putExtra("video_description", "")  // ContentItem doesn't have description
                intent.putExtra("username", currentUsername ?: "")

                // Add flags to grant permissions (only needed for local files)
                if (!processedUri.startsWith("http://") && !processedUri.startsWith("https://")) {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }

                try {
                    startActivity(intent)
                    Log.d("CourseDetailFragment", "Successfully started VideoPlayerActivity with URI: $processedUri")
                } catch (e: Exception) {
                    Log.e("CourseDetailFragment", "Error starting VideoPlayerActivity: ${e.message}", e)
                    showSafeToast("Error al abrir el reproductor de video")
                }
                return
            }

            // For other content types (documents), handle remote URLs
            val uriString = item.uriString
            
            // Check if it's a remote URL (HTTP/HTTPS)
            if (isRemoteUrl(uriString) || 
                uriString.startsWith("http://") || 
                uriString.startsWith("https://")) {
                Log.d("CourseDetailFragment", "☁️ Opening remote document: $uriString")
                
                // Open remote URL in browser or appropriate app
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("CourseDetailFragment", "Error opening remote document: ${e.message}", e)
                    showSafeToast("No se puede abrir el documento: ${item.name}")
                }
                return
            }
            
            // For local content, use the standard approach
            val contentUri = Uri.parse(uriString)
            val file = File(contentUri.path ?: "")

            // Create a content URI using FileProvider
            val contentUriForSharing = if (contentUri.scheme == "file") {                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
            } else {
                contentUri
            }

            // Create intent for viewing content
            val intent = Intent(Intent.ACTION_VIEW)

            // Set the correct MIME type based on contentType
            when (item.contentType) {
                "document" -> intent.setDataAndType(contentUriForSharing, "application/pdf")
                else -> intent.setDataAndType(contentUriForSharing,
                    requireContext().contentResolver.getType(contentUriForSharing) ?: "*/*")
            }

            // Add necessary flags
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            try {
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error opening content: ${e.message}", e)
                showSafeToast("No se puede abrir el contenido: ${item.name}")
            }
        } catch (e: Exception) {
            Log.e("CourseDetailFragment", "Error opening content URI: ${item.uriString}", e)
            showSafeToast("No se puede abrir el contenido: ${item.name}")
        }
    }

    // Open a video in the in-app floating player (MainActivity.showFloatingPlayer)
    private fun openFloatingPlayer(item: ContentItem) {
        try {
            var processedUri = item.uriString ?: ""
            if (processedUri.isNotEmpty()) {
                // Check if it's a remote URL (HTTP/HTTPS) - use directly
                if (isRemoteUrl(processedUri) || 
                    processedUri.startsWith("http://") || 
                    processedUri.startsWith("https://")) {
                    Log.d("CourseDetailFragment", "☁️ Opening remote video in floating player: $processedUri")
                    // Remote URL - use directly, no processing needed
                }
                else if (!processedUri.startsWith("content://") && !processedUri.startsWith("file://") && !processedUri.startsWith("android.resource://")) {
                    val file = java.io.File(processedUri)
                    if (file.exists()) {
                        try {
                            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.fileprovider",
                                file
                            )
                            processedUri = contentUri.toString()
                        } catch (e: Exception) {
                            processedUri = "file://$processedUri"
                        }
                    } else {
                        // leave as-is (may be a remote URL)
                        Log.d("CourseDetailFragment", "File not found locally, assuming remote URL: $processedUri")
                    }
                }
            } else {
                showSafeToast("URI del video no válida")
                return
            }

            // Call MainActivity API to show floating player
            (activity as? com.example.tareamov.MainActivity)?.showFloatingPlayer(processedUri)
        } catch (e: Exception) {
            android.util.Log.e("CourseDetailFragment", "Error opening floating player", e)
            showSafeToast("No se pudo abrir el reproductor flotante")
        }
    }

    // Helper method to find a video in MediaStore by its file path
    private fun findVideoInMediaStore(filePath: String): Uri? {
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID
            )

            val selection = "${MediaStore.Video.Media.DATA} = ?"
            val selectionArgs = arrayOf(filePath)

            val cursor = requireContext().contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val id = it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                    // Use withAppendedPath instead of getContentUri for API level 27 compatibility
                    return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                }
            }

            return null
        } catch (e: Exception) {
            Log.e("CourseDetailFragment", "Error finding video in MediaStore: ${e.message}")
            return null
        }
    }

    private fun setupAdminButton() {
        // Use the ComponentBottomNavigationBinding slot pattern to avoid layout jumps/huecos
        val adminSlot = bottomNavBinding.adminSlot
        val goToAdminButton = bottomNavBinding.goToAdminButton

        // Initialize as INVISIBLE while we decide to avoid visual jumps
        goToAdminButton.visibility = View.INVISIBLE

        // Prefer synchronous SessionManager check so the slot can be hidden before first render
        val sess = SessionManager.getInstance(requireContext())
        
        // Verificar rol 3
        if (!sess.hasRole(3)) {
            // Hide the entire slot before drawing to prevent any gap for non-admin users
            adminSlot.visibility = View.GONE
            return
        }
        
        // User has role 3: show button and wire listener
        adminSlot.visibility = View.VISIBLE
        goToAdminButton.visibility = View.VISIBLE
        goToAdminButton.setOnClickListener {
            Log.d("CourseDetailFragment", "Admin button clicked, navigating to HomeFragment")
            findNavController().navigate(R.id.action_courseDetailFragment_to_homeFragment)
        }
    }

    private fun checkAdminStatus(callback: (Boolean) -> Unit) {
        val username = sessionManager.getUsername()
        if (username == null) {
            callback(false)
            return
        }

        lifecycleScope.launch {
            try {
                val userResult = withContext(Dispatchers.IO) { BackendApiService.getMyProfile() }
                val isAdmin = if (userResult is ApiResult.Success) {
                    val user = userResult.data
                    user?.rol_id == 3L // admin role id
                } else false
                Log.d("CourseDetailFragment", "User $username is admin: $isAdmin")
                callback(isAdmin)
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error checking admin status", e)
                callback(false)
            }
        }
    }

    // iPhone-style entrance animation for course title and description
    private fun animateCourseTitleEntrance() {
        val root = view ?: return
        val titleContainer = root.findViewById<View>(R.id.courseTitleContainer)
        val metaLabel = root.findViewById<TextView>(R.id.courseMetaLabel)
        val insightRow: View? = null
        val accentDivider = root.findViewById<View>(R.id.courseAccentDivider)

        titleContainer?.animate()?.apply {
            alpha(1f)
            translationY(0f)
            duration = 620
            interpolator = android.view.animation.DecelerateInterpolator(2.1f)
        }?.start()

        metaLabel?.animate()?.apply {
            startDelay = 160
            alpha(1f)
            translationY(0f)
            duration = 520
            interpolator = android.view.animation.DecelerateInterpolator(2f)
        }?.start()

        courseTitleTextView.animate()
            .setStartDelay(200)
            .alpha(1f)
            .translationY(0f)
            .setDuration(540)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.2f))
            .start()

        // Animate description slightly after title for cascading effect
        courseDescriptionTextView.animate()
            .setStartDelay(280)
            .alpha(1f)
            .translationY(0f)
            .setDuration(520)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2.0f))
            .start()

        accentDivider?.animate()?.apply {
            startDelay = 380
            alpha(0.6f)
            translationY(0f)
            scaleX(1f)
            duration = 520
            interpolator = android.view.animation.DecelerateInterpolator(2.1f)
        }?.start()

        // Begin animating secondary sections a moment later
        root.postDelayed({ animateContentSections() }, 420)
    }

    // Add subtle bounce animation when title is updated
    private fun animateTitleUpdate() {
        courseTitleTextView.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(150)
            .withEndAction {
                courseTitleTextView.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    // Add subtle fade animation when description is updated
    private fun animateDescriptionUpdate() {
        courseDescriptionTextView.animate()
            .alpha(0.7f)
            .setDuration(100)
            .withEndAction {
                courseDescriptionTextView.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }

    private fun animateContentSections() {
        val root = view ?: return
        // Containers that should float in once data arrives
        animateViewIfVisible(root.findViewById(R.id.courseProgressContainer), 120)
        animateViewIfVisible(root.findViewById(R.id.paymentButtonContainer), 180)
        animateViewIfVisible(root.findViewById(R.id.courseTabStrip), 220)
        animateViewIfVisible(root.findViewById(R.id.sectionHeadingRow), 260)
        animateViewIfVisible(topicsContainer, 300)
        animateViewIfVisible(root.findViewById(R.id.noTopicsTextView), 300)
        animateViewIfVisible(root.findViewById(R.id.noTasksTextView), 300)
        animateViewIfVisible(courseActionBar, 360)
    }

    private fun animateViewIfVisible(target: View?, delay: Long = 0L) {
        target ?: return
        if (target.visibility != View.VISIBLE) return
        if (target.alpha >= 0.95f && target.translationY == 0f) return
        target.alpha = target.alpha.coerceAtMost(0f)
        target.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(520)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
            .start()
    }
    
    /**
     * Update the price display UI based on the course price
     */
    private fun updatePriceDisplay(price: Double) {
        val isFree = price <= 0.0
        
        if (isFree) {
            coursePriceTextView.text = "Curso Gratuito"
            coursePriceIcon.setImageResource(android.R.drawable.ic_menu_info_details)
            coursePriceIcon.setColorFilter(resources.getColor(android.R.color.holo_green_light, null))
        } else {
            coursePriceTextView.text = String.format("$%.2f USD", price)
            coursePriceIcon.setImageResource(android.R.drawable.ic_secure)
            coursePriceIcon.setColorFilter(resources.getColor(android.R.color.holo_orange_light, null))
        }
    }
    
    /**
     * Show dialog to configure course price
     */
    private fun showPriceConfigurationDialog() {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, null)
        
        val currentCourse = courseViewModel.course.value ?: return
        val currentPrice = currentCourse.price
        
        val options = arrayOf(
            "Curso Gratuito (0.00 USD)",
            "Curso de Pago (Ingresar monto)"
        )
        
        AlertDialog.Builder(context)
            .setTitle("Configurar Precio del Curso")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        // Set as free course
                        updateCoursePrice(0.0)
                    }
                    1 -> {
                        // Show input dialog for custom price
                        showPriceInputDialog(currentPrice)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Show input dialog for entering custom price
     */
    private fun showPriceInputDialog(currentPrice: Double) {
        val context = requireContext()
        val input = EditText(context)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
        input.hint = "Ejemplo: 49.99"
        if (currentPrice > 0) {
            input.setText(String.format("%.2f", currentPrice))
        }
        
        AlertDialog.Builder(context)
            .setTitle("Ingresar Precio")
            .setMessage("Ingresa el precio del curso en USD:")
            .setView(input)
            .setPositiveButton("Guardar") { _, _ ->
                val priceText = input.text.toString().trim()
                val price = priceText.toDoubleOrNull()
                
                if (price != null && price >= 0) {
                    updateCoursePrice(price)
                } else {
                    showSafeToast("Precio inválido. Debe ser un número positivo.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    /**
     * Update course price in database and Supabase
     */
    private fun updateCoursePrice(newPrice: Double) {
        val course = courseViewModel.course.value ?: return
        
        lifecycleScope.launch {
            try {
                val updatedCourse = course.copy(
                    price = newPrice,
                    isPremium = newPrice > 0.0
                )
                
                // Update via backend API
                withContext(Dispatchers.IO) {
                    BackendApiService.updateCourse(course.id, mapOf(
                        "price" to newPrice,
                        "is_premium" to (newPrice > 0.0)
                    ))
                }
                
                // Update UI
                updatePriceDisplay(newPrice)
                courseViewModel.getCourseById(courseId)
                
                val message = if (newPrice > 0) {
                    "Curso configurado como de pago: $${"%.2f".format(newPrice)} USD"
                } else {
                    "Curso configurado como gratuito"
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error updating course price", e)
                Toast.makeText(requireContext(), "Error al actualizar el precio", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startSkeletonAnimation() {
        skeletonLayout.visibility = View.VISIBLE
        skeletonLayout.alpha = 1f
        
        // Iniciar animación shimmer continua
        val shimmerAnimation = android.animation.ObjectAnimator.ofFloat(
            skeletonLayout, 
            "alpha", 
            0.3f, 
            1.0f
        ).apply {
            duration = 1200
            repeatCount = android.animation.ObjectAnimator.INFINITE
            repeatMode = android.animation.ObjectAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        }
        shimmerAnimation.start()
        
        // Guardar referencia para detenerla después
        skeletonLayout.setTag(R.id.skeletonLayout, shimmerAnimation)
    }

    private fun stopSkeletonAnimation() {
        // Detener la animación shimmer
        val shimmerAnimation = skeletonLayout.getTag(R.id.skeletonLayout) as? android.animation.ObjectAnimator
        shimmerAnimation?.cancel()
        
        // Animar salida con fade out
        skeletonLayout.animate()
            .alpha(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                skeletonLayout.visibility = View.GONE
            }
            .start()
    }

    private fun isNetworkAvailable(context: android.content.Context): Boolean {
        val connectivityManager = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        return when {
            activeNetwork.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> true
            activeNetwork.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    /**
     * Navigate to edit topic screen
     */
    private fun navigateToEditTopic(topicId: Long) {
        val bundle = Bundle().apply {
            putLong("topicId", topicId)
            putLong("courseId", courseId)
            putString("courseName", courseName) // Pass course name to edit screen
            putBoolean("isEditMode", true)
        }
        findNavController().navigate(R.id.action_courseDetailFragment_to_courseTopicFragment, bundle)
    }

    /**
     * Show confirmation dialog before deleting a topic
     */
    private fun showDeleteTopicConfirmation(topic: Topic) {
        val requestKey = "confirm_delete_topic_${topic.id}"
        parentFragmentManager.setFragmentResultListener(requestKey, viewLifecycleOwner) { _, bundle ->
            parentFragmentManager.clearFragmentResultListener(requestKey)
            val confirmed = bundle.getBoolean("confirmed", false)
            if (confirmed) deleteTopic(topic)
        }

        val title = "Eliminar Tema"
        val message = "¿Estás seguro de que deseas eliminar el tema '${topic.name}'? Esta acción no se puede deshacer y eliminará todo el contenido y tareas asociadas."
        val dialog = ConfirmDeleteDialogFragment.newInstance(requestKey, title, message)
        dialog.show(parentFragmentManager, requestKey)
    }

    /**
     * Delete a topic and all its associated content and tasks
     */
    private fun deleteTopic(topic: Topic) {
        if (!isCurrentUserCreator) {
            Toast.makeText(requireContext(), "Solo el creador puede eliminar temas", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var deleteSuccess = false
                withContext(Dispatchers.IO) {
                    // Delete topic via backend (handles cascade deletion of content and tasks)
                    val result = BackendApiService.deleteTopic(topic.id)
                    deleteSuccess = result is ApiResult.Success
                }
                
                withContext(Dispatchers.Main) {
                    if (deleteSuccess) {
                        Toast.makeText(requireContext(), "Tema eliminado exitosamente", Toast.LENGTH_SHORT).show()
                        // Reload the course details to refresh the UI
                        loadCourseDetails()
                    } else {
                        Toast.makeText(requireContext(), "Error: No se pudo eliminar el tema. Verifique su conexión.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error deleting topic", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al eliminar el tema: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Show confirmation dialog before deleting a task
     */
    private fun showDeleteTaskConfirmation(task: Task) {
        val requestKey = "confirm_delete_task_${task.id}"
        parentFragmentManager.setFragmentResultListener(requestKey, viewLifecycleOwner) { _, bundle ->
            parentFragmentManager.clearFragmentResultListener(requestKey)
            val confirmed = bundle.getBoolean("confirmed", false)
            if (confirmed) deleteTask(task)
        }

        val title = "Eliminar Tarea"
        val message = "¿Estás seguro de que deseas eliminar la tarea '${task.name}'? Esta acción no se puede deshacer y eliminará todas las entregas de los estudiantes."
        val dialog = ConfirmDeleteDialogFragment.newInstance(requestKey, title, message)
        dialog.show(parentFragmentManager, requestKey)
    }

    /**
     * Delete a task and all its associated submissions
     */
    private fun deleteTask(task: Task) {
        if (!isCurrentUserCreator) {
            Toast.makeText(requireContext(), "Solo el creador puede eliminar tareas", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var deleteSuccess = false
                withContext(Dispatchers.IO) {
                    // Delete task via backend (handles cascade deletion of submissions and content)
                    val result = BackendApiService.deleteTask(task.id)
                    deleteSuccess = result is ApiResult.Success
                }
                
                withContext(Dispatchers.Main) {
                    if (deleteSuccess) {
                        Toast.makeText(requireContext(), "Tarea eliminada exitosamente", Toast.LENGTH_SHORT).show()
                        // Reload the course details to refresh the UI
                        loadCourseDetails()
                    } else {
                        Toast.makeText(requireContext(), "Error: No se pudo eliminar la tarea. Verifique su conexión.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error deleting task", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al eliminar la tarea: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Show confirmation dialog before deleting content item
     */
    private fun showDeleteContentConfirmation(contentItem: ContentItem, container: LinearLayout, contentView: View) {
        val requestKey = "confirm_delete_content_${contentItem.id}"
        parentFragmentManager.setFragmentResultListener(requestKey, viewLifecycleOwner) { _, bundle ->
            parentFragmentManager.clearFragmentResultListener(requestKey)
            val confirmed = bundle.getBoolean("confirmed", false)
            if (confirmed) deleteContent(contentItem, container, contentView)
        }

        val title = "Eliminar Contenido"
        val message = "¿Estás seguro de que deseas eliminar '${contentItem.name}'?"
        val dialog = ConfirmDeleteDialogFragment.newInstance(requestKey, title, message)
        dialog.show(parentFragmentManager, requestKey)
    }

    /**
     * Delete a content item
     */
    private fun deleteContent(contentItem: ContentItem, container: LinearLayout, contentView: View) {
        if (!isCurrentUserCreator) {
            Toast.makeText(requireContext(), "Solo el creador puede eliminar contenido", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var deleteSuccess = false
                withContext(Dispatchers.IO) {
                    // Delete content item via backend
                    val result = BackendApiService.deleteContentItem(contentItem.id)
                    deleteSuccess = result is ApiResult.Success
                }
                
                withContext(Dispatchers.Main) {
                    if (deleteSuccess) {
                        // Remove the view from the container
                        container.removeView(contentView)
                        Toast.makeText(requireContext(), "Contenido eliminado exitosamente", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Error: No se pudo eliminar el contenido. Verifique su conexión e intente nuevamente.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error deleting content", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al eliminar el contenido: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * Check if course is fully purchased with successful transactions
     */
    private suspend fun checkIfCoursePurchased(courseId: Long): Boolean {
        return try {
            val result = withContext(Dispatchers.IO) { BackendApiService.hasPurchasedCourse(courseId) }
            if (result is ApiResult.Success) result.data ?: false else false
        } catch (e: Exception) {
            Log.e("CourseDetailFragment", "Error checking course purchase status", e)
            false
        }
    }
}