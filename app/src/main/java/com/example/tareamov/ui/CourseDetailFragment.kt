package com.example.tareamov.ui
import com.example.tareamov.ui.initiatePSEPayment
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
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.dao.PersonaDao
import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.data.dao.SubscriptionDao
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
    // Resolved course id after checking Supabase (may differ from local courseId)
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
    // Repository for remote checks
    private val syncRepository by lazy { com.example.tareamov.data.sync.SyncRepository(
        AppDatabase.getDatabase(requireContext()).usuarioDao(),
        AppDatabase.getDatabase(requireContext()).personaDao(),
        AppDatabase.getDatabase(requireContext()).topicDao(),
        AppDatabase.getDatabase(requireContext()).contentItemDao(),
        AppDatabase.getDatabase(requireContext()).taskDao(),
        AppDatabase.getDatabase(requireContext()).subscriptionDao(),
        AppDatabase.getDatabase(requireContext()).taskSubmissionDao(),
        AppDatabase.getDatabase(requireContext()).videoDao(),
        AppDatabase.getDatabase(requireContext()).courseDao(),
        AppDatabase.getDatabase(requireContext()).rolDao(),
        AppDatabase.getDatabase(requireContext()).recursoDao(),
        AppDatabase.getDatabase(requireContext()).rolRecursoDao(),
        AppDatabase.getDatabase(requireContext()).chatMessageDao(),
        AppDatabase.getDatabase(requireContext()).fileContextDao(),
        AppDatabase.getDatabase(requireContext()).progresoEstudianteDao()
    ) }

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
            Log.d("CourseDetailFragment", "onResume: Reloading course details for courseId: $courseId")
            loadCourseDetails()
            // Also refresh topics from Supabase to reflect recent remote creations
            refreshTopicsFromSupabase()
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
                    Toast.makeText(context, "Nombre del curso no cargado aún. Intenta de nuevo.", Toast.LENGTH_SHORT).show()
                } else {
                    // Course name is available, proceed with navigation
                    navigateToSelectTopic(currentCourseName) // Pass the confirmed name
                }
            } else {
                Log.e("CourseDetailFragment", "Invalid courseId (-1) when trying to add task.")
                Toast.makeText(context, "ID de curso inválido.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(context, "Error: ID de curso inválido", Toast.LENGTH_SHORT).show()
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

        // Animate title and description with iPhone-style entrance
        animateCourseTitleEntrance()

        // Initially hide edit controls until we verify creator ownership remotely
        editCourseButton.visibility = View.GONE
        togglePriceButton.visibility = View.GONE
        courseTitleTextView.isClickable = false

        val courseTitle = view.findViewById<TextView>(R.id.courseTitleTextView)
        val courseDescription = view.findViewById<TextView>(R.id.courseDescriptionTextView)
        val subscribeButton = view.findViewById<Button>(R.id.subscribeButton)

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
                Log.d("CourseDetailFragment", "🔍 Local username: $localUsername, courseId: $courseId, creatorUserId: ${it.creatorUserId}")
                editCourseButton.visibility = View.GONE
                if (localUsername != null) {
                    lifecycleScope.launch {
                        var showEdit = false
                        try {
                            val act = requireActivity()
                            if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                                val remoteCourse = withContext(Dispatchers.IO) { act.syncRepository.fetchCourseById(courseId) }
                                if (remoteCourse != null) {
                                    // Fetch username from remote course's creator_user_id
                                    val remoteCreatorUsername = withContext(Dispatchers.IO) {
                                        com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(remoteCourse.creatorUserId)
                                    }
                                    Log.d("CourseDetailFragment", "✅ Remote creator: $remoteCreatorUsername, local: $localUsername, match: ${remoteCreatorUsername == localUsername}")
                                    showEdit = remoteCreatorUsername != null && remoteCreatorUsername == localUsername
                                } else {
                                    // fallback to local course data if remote missing
                                    val localCreatorUsername = withContext(Dispatchers.IO) {
                                        com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(it.creatorUserId)
                                    }
                                    Log.d("CourseDetailFragment", "⚠️ Local creator (fallback): $localCreatorUsername, match: ${localCreatorUsername == localUsername}")
                                    showEdit = localCreatorUsername != null && localCreatorUsername == localUsername
                                }
                            } else {
                                // Supabase not configured, fallback to local check
                                val localCreatorUsername = withContext(Dispatchers.IO) {
                                    com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(it.creatorUserId)
                                }
                                Log.d("CourseDetailFragment", "📱 Local creator (no Supabase): $localCreatorUsername, match: ${localCreatorUsername == localUsername}")
                                showEdit = localCreatorUsername != null && localCreatorUsername == localUsername
                            }
                        } catch (e: Exception) {
                            Log.e("CourseDetailFragment", "❌ Error checking remote creator: ${e.message}", e)
                            val localCreatorUsername = withContext(Dispatchers.IO) {
                                com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(it.creatorUserId)
                            }
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

        // Set up subscribe button click listener - Moved to ExploreFragment cards
        // subscribeButton.setOnClickListener {
        // lifecycleScope.launch {
        //     try {
        //         val remoteCourse = syncRepository.fetchCourseById(courseId)
        //         val remoteCreator = remoteCourse?.creatorUsername?.trim()
        //         val currentUser = sessionManager.getUsername()?.trim()
        //         val isOwner = !remoteCreator.isNullOrBlank() && !currentUser.isNullOrBlank() && remoteCreator.equals(currentUser, ignoreCase = true)
        //
        //         withContext(Dispatchers.Main) {
        //             if (isOwner) {
        //                 editCourseButton.visibility = View.VISIBLE
        //                 courseTitleTextView.isClickable = true
        //             } else {
        //                 editCourseButton.visibility = View.GONE
        //                 courseTitleTextView.isClickable = false
        //             }
        //         }
        //     } catch (e: Exception) {
        //         // On error, fall back to local check: show edit only if session username equals local course creator
        //         try {
        //             val localCourse = AppDatabase.getDatabase(requireContext()).courseDao().getCourseById(courseId)
        //             val localCreator = localCourse?.creatorUsername?.trim()
        //             val currentUser = sessionManager.getUsername()?.trim()
        //             val isOwnerLocal = !localCreator.isNullOrBlank() && !currentUser.isNullOrBlank() && localCreator.equals(currentUser, ignoreCase = true)
        //             withContext(Dispatchers.Main) {
        //                 if (isOwnerLocal) {
        //                     editCourseButton.visibility = View.VISIBLE
        //                     courseTitleTextView.isClickable = true
        //                 } else {
        //                     editCourseButton.visibility = View.GONE
        //                     courseTitleTextView.isClickable = false
        //                 }
        //             }
        //         } catch (ex: Exception) {
        //             // If even local check fails, keep edit hidden
        //             withContext(Dispatchers.Main) {
        //                 editCourseButton.visibility = View.GONE
        //                 courseTitleTextView.isClickable = false
        //             }
        //         }
        //     }
        // }
        //     if (!sessionManager.isLoggedIn()) {
        //         Toast.makeText(requireContext(), "Debes iniciar sesión para suscribirte", Toast.LENGTH_SHORT).show()
        //         findNavController().navigate(R.id.loginFragment)
        //         return@setOnClickListener
        //     }
        //
        //     lifecycleScope.launch {
        //         val username = sessionManager.getUsername() ?: return@launch
        //         val creator = courseCreatorUsername ?: return@launch
        //
        //         // Check remote subscription state first
        //         var remoteSubscribed = false
        //         try {
        //             val act = requireActivity()
        //             if (act is MainActivity) {
        //                 remoteSubscribed = withContext(Dispatchers.IO) { act.syncRepository.isSubscribedRemote(username, creator) }
        //             }
        //         } catch (e: Exception) {
        //             Log.w("CourseDetailFragment", "Remote isSubscribed check failed", e)
        //         }
        //
        //         if (!remoteSubscribed) {
        //             // Subscribe remotely
        //             val sub = Subscription(subscriberUsername = username, creatorUsername = creator, subscriptionDate = System.currentTimeMillis())
        //             var ok = false
        //             try {
        //                 val act = requireActivity()
        //                 if (act is MainActivity) {
        //                     ok = withContext(Dispatchers.IO) { act.syncRepository.insertSubscriptionRemote(sub) }
        //                 }
        //             } catch (e: Exception) {
        //                 Log.w("CourseDetailFragment", "Remote subscribe failed", e)
        //             }
        //
        //             if (ok) {
        //                 // Persist locally as well
        //                 withContext(Dispatchers.IO) { AppDatabase.getDatabase(requireContext()).subscriptionDao().insertSubscription(sub) }
        //                 isSubscribed = true
        //                 // Increase UI count by 1
        //                 // val currentCount = try { Integer.parseInt(subscriberCountTextView.text.toString().filter { it.isDigit() }) } catch (t: Exception) { -1 }
        //                 // We will re-fetch accurate count below; update UI state
        //                 // updateSubscribeButtonState(true)
        //                 Toast.makeText(requireContext(), "Te has suscrito al curso exitosamente", Toast.LENGTH_SHORT).show()
        //             } else {
        //                 Toast.makeText(requireContext(), "No se pudo suscribir (error de red)", Toast.LENGTH_SHORT).show()
        //             }
        //         } else {
        //             // Already subscribed remotely -> unsubscribe
        //             var ok = false
        //             try {
        //                 val act = requireActivity()
        //                 if (act is MainActivity) {
        //                     ok = withContext(Dispatchers.IO) { act.syncRepository.deleteSubscriptionRemote(username, creator) }
        //                 }
        //             } catch (e: Exception) {
        //                 Log.w("CourseDetailFragment", "Remote unsubscribe failed", e)
        //             }
        //
        //             if (ok) {
        //                 // Remove local record
        //                 withContext(Dispatchers.IO) { AppDatabase.getDatabase(requireContext()).subscriptionDao().deleteSubscription(username, creator) }
        //                 isSubscribed = false
        //                 // updateSubscribeButtonState(false)
        //                 Toast.makeText(requireContext(), "Se ha desuscrito del curso", Toast.LENGTH_SHORT).show()
        //             } else {
        //                 Toast.makeText(requireContext(), "No se pudo desuscribir (error de red)", Toast.LENGTH_SHORT).show()
        //             }
        //         }
        //
        //         // Refresh subscriber count from local DAO (or optionally from Supabase)
        //         // val newCount = withContext(Dispatchers.IO) { AppDatabase.getDatabase(requireContext()).subscriptionDao().getSubscriptionCountForCreator(creator) }
        //         // subscriberCountTextView.text = formatSubscriberCount(newCount)
        //     }
        // }
        
        // Setup bottom navigation
        setupBottomNavigation(view)

        // Observe back stack savedStateHandle for topic creation notifications
        val navBackEntry = findNavController().currentBackStackEntry
        navBackEntry?.savedStateHandle?.getLiveData<Long>("topic_created")?.observe(viewLifecycleOwner) { topicId ->
            try {
                Log.d("CourseDetailFragment", "Detected topic_created=$topicId, refreshing topics")
                refreshTopicsFromSupabase()
                // Clear the flag so subsequent returns don't re-trigger unless set again
                navBackEntry.savedStateHandle.remove<Long>("topic_created")
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Error handling topic_created event", e)
            }
        }
        
        // Observe general refresh flag (usado por tareas y otros cambios)
        navBackEntry?.savedStateHandle?.getLiveData<Boolean>("refresh_from_supabase")?.observe(viewLifecycleOwner) { shouldRefresh ->
            if (shouldRefresh == true) {
                Log.d("CourseDetailFragment", "Refresh flag received, reloading from Supabase...")
                refreshTopicsFromSupabase()
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
                refreshTopicsFromSupabase()
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
        
        // Check enrollment status for non-creators
        if (!isCurrentUserCreator && currentUsername != null) {
            checkEnrollmentBeforeAccess()
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


    private fun navigateToSelectTopic(nameOfCourse: String) { // Accept course name as parameter
        // Prefer resolvedCourseId (may have been remapped to Supabase id); fallback to original courseId
        val sendCourseId = if (resolvedCourseId > 0) resolvedCourseId else courseId
        Log.d("CourseDetailFragment", "Navigating to SelectTopicFragment for courseId: $sendCourseId, courseName: $nameOfCourse")
        val bundle = Bundle().apply {
            putLong("courseId", sendCourseId)
            putString("courseName", nameOfCourse) // Pass the confirmed course name
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
     * For premium courses, redirect back if not enrolled
     */
    private fun checkEnrollmentBeforeAccess() {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                
                // Get user ID from username
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
                }
                
                if (userId == null) {
                    android.widget.Toast.makeText(requireContext(), "Error: Usuario no encontrado", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val progreso = withContext(Dispatchers.IO) {
                    db.progresoEstudianteDao().getProgreso(userId, courseId)
                }
                
                if (progreso == null) {
                    // User is not enrolled
                    // Check if course is premium or paid
                    val course = withContext(Dispatchers.IO) {
                        db.courseDao().getCourseById(courseId)
                    }
                    
                    // Block access to paid courses (price > 0)
                    if (course != null && course.price > 0) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                "❌ Este es un curso de pago. Debes realizar el pago para acceder.",
                                Toast.LENGTH_LONG
                            ).show()
                            findNavController().navigateUp()
                        }
                        return@launch
                    }
                    
                    if (course?.isPremium == true) {
                        // Premium course without enrollment - deny access
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                "❌ Debes inscribirte en este curso premium para acceder",
                                Toast.LENGTH_LONG
                            ).show()
                            findNavController().navigateUp()
                        }
                    } else {
                        // Free course - auto-enroll
                        Log.d("CourseDetailFragment", "Auto-enrolling user in free course $courseId")
                        autoEnrollInFreeCourse(course)
                    }
                } else {
                    Log.d("CourseDetailFragment", "User already enrolled in course $courseId")
                }
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error checking enrollment status", e)
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
                val db = AppDatabase.getDatabase(requireContext())
                
                // Ensure course exists in local DB
                withContext(Dispatchers.IO) {
                    val existingCourse = db.courseDao().getCourseById(course.id)
                    if (existingCourse == null) {
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
                
                // Get user ID from username
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername!!)
                }
                
                if (userId == null) {
                    android.widget.Toast.makeText(requireContext(), "Error: Usuario no encontrado", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
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
                
                Log.d("CourseDetailFragment", "✅ Auto-enrolled $currentUsername in free course ${course.id}")
                
                // Sync to Supabase
                val syncSuccess = withContext(Dispatchers.IO) {
                    syncRepository.syncProgresoToSupabase(progreso)
                }
                
                withContext(Dispatchers.Main) {
                    if (syncSuccess) {
                        Log.d("CourseDetailFragment", "✅ Enrollment synced to Supabase")
                        Toast.makeText(
                            requireContext(),
                            "✅ ¡Inscrito automáticamente en ${course.title}!",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Log.w("CourseDetailFragment", "⚠️ Failed to sync enrollment to Supabase")
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
        val db = AppDatabase.getDatabase(requireContext())
        val topicDao = db.topicDao()
        val contentItemDao = db.contentItemDao()
        val taskDao = db.taskDao()
        val courseDao = db.courseDao()
        val usuarioDao = db.usuarioDao()
        val personaDao = db.personaDao()
        val subscriptionDao = db.subscriptionDao()  // Use the DAO
        val noTopicsTextView = view?.findViewById<TextView>(R.id.noTopicsTextView)
        val courseTitleTextView = view?.findViewById<TextView>(R.id.courseTitleTextView)
        // Add a TextView for when tasks are filtered and none are found
        val noTasksTextView = view?.findViewById<TextView>(R.id.noTasksTextView) // Make sure this ID exists in your layout or create it
    val paymentContainer = view?.findViewById<FrameLayout>(R.id.paymentButtonContainer)

        CoroutineScope(Dispatchers.Main).launch {
            try { // Start of the main try block
                // Try to fetch the Course from Supabase first (via MainActivity.syncRepository)
                var remoteCourse: com.example.tareamov.data.entity.Course? = null
                // Use an effectiveCourseId for subsequent topic/task lookups; may be remapped if we find
                // a matching course by title on Supabase when the numeric id is not present there.
                var effectiveCourseId: Long = courseId
                try {
                    val act = requireActivity()
                    if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                        // First try exact id lookup
                        remoteCourse = withContext(Dispatchers.IO) { act.syncRepository.fetchCourseById(courseId) }
                        Log.d("CourseDetailFragment", "fetchCourseById returned: ${remoteCourse?.id}")

                        // If no course found by id, try to resolve by the passed courseName (common case when
                        // local DB uses different ids). This handles maps where Supabase courses range 1..43
                        // but the local DB has created records with different ids (e.g., 70).
                        if (remoteCourse == null && courseName.isNotBlank()) {
                            try {
                                val courses = withContext(Dispatchers.IO) { act.syncRepository.fetchCoursesFromSupabase() }
                                val match = courses.firstOrNull { c ->
                                    val remoteTitle = (c.title ?: "").trim()
                                    remoteTitle.equals(courseName.trim(), ignoreCase = true)
                                }
                                if (match != null) {
                                    remoteCourse = match
                                    effectiveCourseId = match.id
                                    Log.d("CourseDetailFragment", "Resolved course by title -> remote id=${match.id} title=${match.title}")
                                } else {
                                    Log.d("CourseDetailFragment", "No Supabase course matched title='$courseName'")
                                }
                            } catch (t: Exception) {
                                Log.w("CourseDetailFragment", "Title-based Supabase lookup failed", t)
                            }
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
                        com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(remoteCourse.creatorUserId)
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
                        paymentContainer?.visibility = View.VISIBLE
                        animateViewIfVisible(paymentContainer, 180)
                    } else {
                        paymentContainer?.alpha = 0f
                        paymentContainer?.translationY = resources.getDimensionPixelSize(R.dimen.edit_button_enter_offset).toFloat()
                        paymentContainer?.visibility = View.GONE
                    }

                    // Load creator info if the current user is not the creator
                    if (!isCurrentUserCreator && remoteCourse != null) {
                        val creatorId = remoteCourse.creatorUserId
                        val currentUserId = sessionManager.getUserId()

                        // Prefer remote subscription state when available
                        var subscriptionCount = withContext(Dispatchers.IO) {
                            subscriptionDao.getSubscriptionCountForCreator(creatorId)
                        }
                        var isSubscribedLocal = withContext(Dispatchers.IO) {
                            if (currentUserId != -1L) subscriptionDao.isSubscribed(currentUserId, creatorId) else false
                        }
                        var isSubscribedRemote = false
                        try {
                            val act = requireActivity()
                            if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured() && currentUserId != -1L) {
                                isSubscribedRemote = withContext(Dispatchers.IO) { act.syncRepository.isSubscribedRemote(currentUserId, creatorId) }
                                // If remote is true but local count doesn't include this subscriber, adjust
                                if (isSubscribedRemote && !isSubscribedLocal) {
                                    // persist locally
                                    withContext(Dispatchers.IO) {
                                        subscriptionDao.insertSubscription(com.example.tareamov.data.entity.Subscription(subscriberId = currentUserId, creatorId = creatorId, subscriptionDate = System.currentTimeMillis()))
                                    }
                                    isSubscribedLocal = true
                                    subscriptionCount += 1
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("CourseDetailFragment", "Remote subscription check failed", e)
                        }
                        val isSubscribed = isSubscribedLocal

                        loadCreatorInfo(
                            creatorUsername = courseCreatorUsername!!,
                            personaDao = personaDao,
                            usuarioDao = usuarioDao,
                            subscriptionCount = subscriptionCount,
                            isSubscribed = isSubscribed
                        )

                        // creatorInfoContainer.visibility = View.VISIBLE // Moved to ExploreFragment cards
                        Log.d("CourseDetailFragment", "Initializing student progress: courseId=$effectiveCourseId username=$currentUsername isCreator=$isCurrentUserCreator")
                        initializeAndLoadCourseProgress(
                            courseId = effectiveCourseId,
                            username = currentUsername,
                            isCurrentUserCreator = isCurrentUserCreator
                        )
                    } else {
                        // creatorInfoContainer.visibility = View.GONE // Moved to ExploreFragment cards
                    }
                } else {
                    // Fallback: load local course info from courseDao
                    val course = withContext(Dispatchers.IO) { courseDao.getCourseById(courseId) }

                    // Set the course title
                    courseTitleTextView?.text = course?.title ?: "Curso sin título"
                    courseName = course?.title ?: "Curso sin título"

                    // Map creator username from local course's creator_user_id
                    courseCreatorUsername = if (course != null) {
                        withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.getUsernameFromUserId(course.creatorUserId)
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
                        paymentContainer?.visibility = View.VISIBLE
                        animateViewIfVisible(paymentContainer, 180)
                    } else {
                        paymentContainer?.alpha = 0f
                        paymentContainer?.translationY = resources.getDimensionPixelSize(R.dimen.edit_button_enter_offset).toFloat()
                        paymentContainer?.visibility = View.GONE
                    }

                    // Load creator info if the current user is not the creator
                    if (!isCurrentUserCreator && courseCreatorUsername != null) {
                        val currentUserId = sessionManager.getUserId()
                        // Get subscription count using SubscriptionDao
                        var subscriptionCount = withContext(Dispatchers.IO) {
                            if (creatorUserId != -1L) subscriptionDao.getSubscriptionCountForCreator(creatorUserId) else 0
                        }

                        // Check if current user is subscribed using SubscriptionDao
                        var isSubscribed = withContext(Dispatchers.IO) {
                            if (currentUserId != -1L && creatorUserId != -1L) {
                                subscriptionDao.isSubscribed(currentUserId, creatorUserId)
                            } else false
                        }

                        // Remote check and sync
                        try {
                            val act = requireActivity()
                            if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured() && currentUserId != -1L && creatorUserId != -1L) {
                                val isSubscribedRemote = withContext(Dispatchers.IO) { act.syncRepository.isSubscribedRemote(currentUserId, creatorUserId) }
                                if (isSubscribedRemote && !isSubscribed) {
                                    withContext(Dispatchers.IO) {
                                        subscriptionDao.insertSubscription(com.example.tareamov.data.entity.Subscription(subscriberId = currentUserId, creatorId = creatorUserId, subscriptionDate = System.currentTimeMillis()))
                                    }
                                    isSubscribed = true
                                    subscriptionCount += 1
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("CourseDetailFragment", "Remote subscription check failed", e)
                        }

                        loadCreatorInfo(
                            creatorUsername = courseCreatorUsername!!,
                            personaDao = personaDao,
                            usuarioDao = usuarioDao,
                            subscriptionCount = subscriptionCount,
                            isSubscribed = isSubscribed
                        )

                        // creatorInfoContainer.visibility = View.VISIBLE // Moved to ExploreFragment cards

                        // Initialize and load course progress for non-creator users
                        Log.d("CourseDetailFragment", "Initializing student progress (local fallback): courseId=$courseId username=$currentUsername isCreator=$isCurrentUserCreator")
                        initializeAndLoadCourseProgress(
                            courseId = courseId,
                            username = currentUsername,
                            isCurrentUserCreator = isCurrentUserCreator
                        )
                    } else {
                        // creatorInfoContainer.visibility = View.GONE // Moved to ExploreFragment cards
                    }
                }

                // Attempt to fetch only topics from Supabase when configured (tabs act as filters over topics)
                var topics: List<Topic> = emptyList()

                if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                    try {
                        // Use effectiveCourseId (may have been remapped by title lookup)
                        val lookupId = effectiveCourseId
                        topics = withContext(Dispatchers.IO) { syncRepository.fetchTopicsByCourseFromSupabase(lookupId) }
                        Log.d("CourseDetailFragment", "Loaded remote topics=${topics.size} for courseId=$lookupId via SyncRepository")
                    } catch (e: Exception) {
                        Log.w("CourseDetailFragment", "Remote topics fetch failed, falling back to local DAO", e)
                        topics = withContext(Dispatchers.IO) { topicDao.getTopicsByCourse(courseId) }
                    }
                } else {
                    topics = withContext(Dispatchers.IO) { topicDao.getTopicsByCourse(courseId) }
                }

                Log.d("CourseDetailFragment", "Found ${topics.size} topics for courseId: $courseId")

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
                    if (currentTab == "tareas") {
                        try {
                            val topicIds = sortedTopics.map { it.id }
                            if (topicIds.isNotEmpty()) {
                                if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                                    val fetched = withContext(Dispatchers.IO) { syncRepository.fetchTasksByTopicIdsFromSupabase(topicIds) }
                                    tasksByTopic = (fetched ?: emptyList()).groupBy { it.topicId }
                                } else {
                                    val localTasks = withContext(Dispatchers.IO) { taskDao.getTasksByTopicIds(sortedTopics.map { it.id }) }
                                    tasksByTopic = localTasks.groupBy { it.topicId }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("CourseDetailFragment", "Failed to fetch tasks for topics; falling back to per-topic DAO", e)
                            // Best-effort fallback: fetch per-topic from DAO
                            val map = mutableMapOf<Long, List<Task>>()
                            for (t in sortedTopics) {
                                try {
                                    val list = withContext(Dispatchers.IO) { taskDao.getTasksByTopicId(t.id) }
                                    map[t.id] = list
                                } catch (_: Exception) { map[t.id] = emptyList() }
                            }
                            tasksByTopic = map
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
                        // content items are intentionally not fetched here for performance
                        addTopicView(topic, emptyList(), tasksForTopic)
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

                animateContentSections()
            } catch (e: Exception) { // This is the correct catch block for the main try
                Log.e("CourseDetailFragment", "Error loading course details", e)
                Toast.makeText(context, "Error al cargar detalles del curso", Toast.LENGTH_SHORT).show()
                noTopicsTextView?.text = "Error al cargar datos." // Generic error message
                noTopicsTextView?.visibility = View.VISIBLE
                noTopicsTextView?.alpha = 0f
                animateViewIfVisible(noTopicsTextView, 320)
                noTasksTextView?.visibility = View.GONE // Ensure no tasks message is hidden on error
                topicsContainer.visibility = View.GONE
            } // Closes the main catch block
        } // Closes CoroutineScope
    } // Closes loadCourseDetails function

    // New method to load creator information with updated parameters
    private suspend fun loadCreatorInfo(
        creatorUsername: String,
        personaDao: PersonaDao,
        usuarioDao: UsuarioDao,
        subscriptionCount: Int,
        isSubscribed: Boolean
    ) {
        try {
            // Prefer Supabase: try to fetch usuario and persona via SyncRepository/SupabaseClient
            var personaFromRemote: Persona? = null
            var usuarioFromRemote: com.example.tareamov.data.dao.UsuarioWithRole? = null

            try {
                val act = requireActivity()
                if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                    usuarioFromRemote = withContext(Dispatchers.IO) { act.syncRepository.fetchUsuarioWithRoleFromSupabase(creatorUsername) }
                    if (usuarioFromRemote != null) {
                        val personas = withContext(Dispatchers.IO) { com.example.tareamov.service.SupabaseClient.fetchPersonas() }
                        personaFromRemote = personas.firstOrNull { p -> p.id == usuarioFromRemote.persona_id }
                        Log.d("CourseDetailFragment", "Remote usuario found for $creatorUsername persona_id=${usuarioFromRemote.persona_id}")
                    }
                }
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Supabase remote creator fetch failed", e)
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

            // Fallback to local DAOs if remote lookup failed
            val usuario = withContext(Dispatchers.IO) {
                usuarioDao.getUsuarioByUsername(creatorUsername)
            }

            if (usuario != null) {
                val persona = withContext(Dispatchers.IO) {
                    personaDao.getPersonaById(usuario.personaId)
                }

                if (persona != null) {
                    withContext(Dispatchers.Main) {
                        // creatorUsernameTextView.text = creatorUsername - Moved to ExploreFragment cards
                        // if (!persona.avatar.isNullOrEmpty()) {
                        //     try {
                        //         Glide.with(requireContext())
                        //             .load(Uri.parse(persona.avatar))
                        //             .placeholder(R.drawable.default_avatar)
                        //             .error(R.drawable.default_avatar)
                        //             .into(creatorAvatarImageView)
                        //     } catch (e: Exception) {
                        //         Log.e("CourseDetailFragment", "Error loading avatar", e)
                        //         creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                        //     }
                        // } else {
                        //     creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                        // }

                        // subscriberCountTextView.text = formatSubscriberCount(subscriptionCount) - Moved to ExploreFragment cards
                        // this@CourseDetailFragment.isSubscribed = isSubscribed
                        // updateSubscribeButtonState(isSubscribed)

                        // subscribeButton.visibility = if (currentUsername == creatorUsername) View.GONE else View.VISIBLE
                        // creatorInfoContainer.visibility = View.VISIBLE // Moved to ExploreFragment cards
                    }
                } else {
                    Log.e("CourseDetailFragment", "Persona not found for user: $creatorUsername")
                    // withContext(Dispatchers.Main) { creatorInfoContainer.visibility = View.GONE } // Moved to ExploreFragment cards
                    // Resolved id remains the local id in fallback case
                    resolvedCourseId = courseId
                }
            } else {
                Log.e("CourseDetailFragment", "Usuario not found locally: $creatorUsername")
                // withContext(Dispatchers.Main) { creatorInfoContainer.visibility = View.GONE } // Moved to ExploreFragment cards
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
            Toast.makeText(context, "Debes iniciar sesión para suscribirte", Toast.LENGTH_SHORT).show()
            return
        }

        if (creatorUser == null || creatorId == -1L) {
            Toast.makeText(context, "Error: No se puede identificar al creador del curso", Toast.LENGTH_SHORT).show()
            return
        }

        // Get the database and DAO
        val db = AppDatabase.getDatabase(requireContext())
        val subscriptionDao = db.subscriptionDao()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                if (isSubscribed) {
                    // Desuscribirse
                    withContext(Dispatchers.IO) {
                        subscriptionDao.deleteSubscription(currentUserId, creatorId)
                    }
                    isSubscribed = false

                    // Actualizar UI del botón
                    // updateSubscribeButtonState(false) // Moved to ExploreFragment cards

                    // Actualizar contador de suscriptores
                    // val newCount = withContext(Dispatchers.IO) {
                    //     subscriptionDao.getSubscriptionCountForCreator(creatorId)
                    // }
                    // subscriberCountTextView.text = formatSubscriberCount(newCount) // Moved to ExploreFragment cards

                    Toast.makeText(context, "Te has desuscrito de $creatorUser", Toast.LENGTH_SHORT).show()
                } else {
                    // Suscribirse
                    val subscription = Subscription(
                        subscriberId = currentUserId,
                        creatorId = creatorId,
                        subscriptionDate = System.currentTimeMillis()
                    )

                    withContext(Dispatchers.IO) {
                        subscriptionDao.insertSubscription(subscription)
                    }
                    isSubscribed = true

                    // Actualizar UI del botón
                    // updateSubscribeButtonState(true) // Moved to ExploreFragment cards

                    // Actualizar contador de suscriptores
                    // val newCount = withContext(Dispatchers.IO) {
                    //     subscriptionDao.getSubscriptionCountForCreator(creatorId)
                    // }
                    // subscriberCountTextView.text = formatSubscriberCount(newCount) // Moved to ExploreFragment cards

                    Toast.makeText(context, "Te has suscrito a $creatorUser", Toast.LENGTH_SHORT).show()
                }

            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error processing subscription", e)
                Toast.makeText(context, "Error al procesar la suscripción: ${e.message}", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "Tarea: ${task.name}", Toast.LENGTH_SHORT).show()
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

        // --- Filtering Logic ---
        if (currentTab == "documentos") {
            // Show Content, Hide Tasks
            tasksContainer.visibility = View.GONE

            topicContentContainer.visibility = View.VISIBLE
            topicContentContainer.removeAllViews() // Clear before adding
            topicContentContainer.addView(contentHeader) // Add header (Now defined)

            val sortedContent = contentItems.sortedBy { it.orderIndex }
            if (sortedContent.isNotEmpty()) {
                for (item in sortedContent) {
                    addContentView(item, topicContentContainer)
                }
            } else {
                val noContentMsg = TextView(context).apply { text = "Sin contenido para este tema" }
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
                        navigateToAddTask(topic.id, topic.courseId)
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
                    syncRepository.fetchTaskByIdFromSupabase(task.id)
                }
                if (remote != null && !remote.name.isNullOrBlank()) {
                    taskNameTextView.text = remote.name
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
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("CourseDetailFragment", "Loading content items for taskId=${task.id}")
                
                // First, try to sync from Supabase
                val remoteContentItems = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    syncRepository.fetchContentItemsByTaskIdFromSupabase(task.id)
                }
                
                // Save remote items to local database
                if (remoteContentItems.isNotEmpty()) {
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val contentItemDao = AppDatabase.getDatabase(requireContext()).contentItemDao()
                        remoteContentItems.forEach { item ->
                            try {
                                contentItemDao.insertContentItem(item)
                                Log.d("CourseDetailFragment", "Saved content item to Room: ${item.name}")
                            } catch (e: Exception) {
                                Log.w("CourseDetailFragment", "Failed to save content item", e)
                            }
                        }
                    }
                }
                
                // Now load from local database (which should have the synced data)
                val contentItems = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppDatabase.getDatabase(requireContext()).contentItemDao().getContentItemsByTaskId(task.id)
                }
                
                Log.d("CourseDetailFragment", "Found ${contentItems.size} content items for taskId=${task.id} (${remoteContentItems.size} from Supabase)")
                
                // Always show the container section for better UX
                taskContentContainer?.removeAllViews()
                contentSeparator?.visibility = View.VISIBLE
                taskContentLabel?.visibility = View.VISIBLE
                taskContentContainer?.visibility = View.VISIBLE
                
                if (contentItems.isNotEmpty()) {
                    for (contentItem in contentItems) {
                        Log.d("CourseDetailFragment", "Adding content item: name=${contentItem.name}, type=${contentItem.contentType}, uri=${contentItem.uriString}")
                        
                        val contentItemView = LayoutInflater.from(context).inflate(
                            R.layout.item_content_mini,
                            taskContentContainer,
                            false
                        )
                        
                        val iconView = contentItemView.findViewById<ImageView>(R.id.contentIconView)
                        val nameView = contentItemView.findViewById<TextView>(R.id.contentNameView)
                        val typeView = contentItemView.findViewById<TextView>(R.id.contentTypeView)
                        
                        nameView?.text = contentItem.name ?: "Archivo adjunto"
                        
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
                        
                        // Make the whole item clickable
                        contentItemView.setOnClickListener {
                            openContent(contentItem)
                        }
                        
                        taskContentContainer?.addView(contentItemView)
                    }
                } else {
                    // Show a message when no content is available
                    val noContentView = TextView(context).apply {
                        text = "No hay archivos adjuntos"
                        setTextColor(resources.getColor(android.R.color.darker_gray, null))
                        setPadding(16, 16, 16, 16)
                        textSize = 13f
                    }
                    taskContentContainer?.addView(noContentView)
                }
            } catch (e: Exception) {
                android.util.Log.e("CourseDetailFragment", "Error loading content items for taskId=${task.id}", e)
                // Show error message in container
                taskContentContainer?.removeAllViews()
                val errorView = TextView(context).apply {
                    text = "Error al cargar archivos"
                    setTextColor(resources.getColor(android.R.color.holo_red_light, null))
                    setPadding(16, 16, 16, 16)
                    textSize = 13f
                }
                taskContentContainer?.addView(errorView)
            }
        }

        // Only show edit button for course creators
        editTaskButton?.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE

        // Set click listener for the edit button (only if visible)
        editTaskButton?.setOnClickListener {
            navigateToEditTask(task.id)
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
                    putString("courseCreatorUsername", courseCreatorUsername)
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
                    putString("courseCreatorUsername", courseCreatorUsername)
                }
                findNavController().navigate(R.id.action_courseDetailFragment_to_taskSubmissionFragment, bundle)
            }
        }

        container.addView(taskView)
    }

    // Refresh topics and re-render UI from Supabase for the current course
    private fun refreshTopicsFromSupabase() {
        if (courseId == -1L) return

        // Cancel previous job if active to prevent race conditions and duplication
        refreshJob?.cancel()

        refreshJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val act = requireActivity()
                val lookupId = if (resolvedCourseId > 0) resolvedCourseId else courseId
                val topics: List<Topic> = if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                    withContext(Dispatchers.IO) { act.syncRepository.fetchTopicsByCourseFromSupabase(lookupId) } ?: emptyList()
                } else {
                    withContext(Dispatchers.IO) { AppDatabase.getDatabase(requireContext()).topicDao().getTopicsByCourse(lookupId) }
                }

                topicsContainer.removeAllViews()

                val sortedTopics = topics.sortedBy { it.orderIndex }

                // If the tasks tab is selected, fetch tasks for these topics from Supabase (or local DAO)
                var tasksByTopic: Map<Long, List<Task>> = emptyMap()
                if (currentTab == "tareas") {
                    try {
                        val topicIds = sortedTopics.map { it.id }
                        if (topicIds.isNotEmpty()) {
                            if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                                val fetched = withContext(Dispatchers.IO) { act.syncRepository.fetchTasksByTopicIdsFromSupabase(topicIds) }
                                tasksByTopic = (fetched ?: emptyList()).groupBy { it.topicId }
                            } else {
                                val localTasks = withContext(Dispatchers.IO) { AppDatabase.getDatabase(requireContext()).taskDao().getTasksByTopicIds(topicIds) }
                                tasksByTopic = localTasks.groupBy { it.topicId }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("CourseDetailFragment", "refreshTopics: failed fetching tasks for topics", e)
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
                    addTopicView(topic, emptyList(), tasks)
                }
                
                // IMPORTANTE: Recalcular progreso de estudiantes después de refrescar
                recalculateStudentProgress()
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e("CourseDetailFragment", "Error refreshing topics", e)
                }
            }
        }
    }

    // Add this helper method to check student submission status
    private fun checkStudentSubmission(taskId: Long, gradeStatusTextView: TextView?) {
        if (gradeStatusTextView == null) return

        val username = sessionManager.getUsername() ?: return

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Always fetch submission from Supabase (task_submissions are remote-authoritative)
                var submission: com.example.tareamov.data.entity.TaskSubmission? = null
                try {
                    val act = requireActivity()
                    if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                        submission = withContext(Dispatchers.IO) { act.syncRepository.fetchUserSubmissionForTaskFromSupabase(taskId, username) }
                        Log.d("CourseDetailFragment", "Supabase fetch for taskId=$taskId username=$username -> submission=${submission}")
                    } else {
                        // If Supabase not configured or MainActivity not available, attempt the local DAO as a last resort
                        val db = AppDatabase.getDatabase(requireContext())
                        submission = withContext(Dispatchers.IO) { db.taskSubmissionDao().getUserSubmissionForTask(taskId, username) }
                        Log.d("CourseDetailFragment", "Local fallback fetch for taskId=$taskId username=$username -> submission=${submission}")
                    }
                } catch (e: Exception) {
                    Log.w("CourseDetailFragment", "Error fetching submission for taskId=$taskId username=$username", e)
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
        Log.d("CourseDetailFragment", "Adding content view - Name: ${item.name}, Type: ${item.contentType}, URI: ${item.uriString}")
        
        val inflater = LayoutInflater.from(context)
        val contentView = inflater.inflate(R.layout.item_content_mini, container, false)

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
    private fun navigateToEditTask(taskId: Long) {
        val bundle = Bundle().apply {
            putLong("taskId", taskId)
            // We might need topicId as well, depending on CourseTaskFragment's logic
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
                val updatedCount = withContext(Dispatchers.IO) {
                    syncRepository.recalculateAllStudentProgressForCourse(resolvedCourseId)
                }
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
                        // Obtener todas las tareas del curso desde Supabase
                        val topics = syncRepository.fetchTopicsByCourseFromSupabase(courseIdToUse)
                        val topicIds = topics.map { it.id }
                        
                        if (topicIds.isEmpty()) {
                            Log.d("CourseDetailFragment", "⚠️ No topics found for course $courseIdToUse")
                            return@withContext
                        }
                        
                        val allTasks = syncRepository.fetchTasksByTopicIdsFromSupabase(topicIds)
                        Log.d("CourseDetailFragment", "📚 Found ${allTasks.size} tasks in course")
                        
                        if (allTasks.isEmpty()) {
                            Log.d("CourseDetailFragment", "⚠️ No tasks found for course $courseIdToUse")
                            return@withContext
                        }
                        
                        // Obtener todas las entregas del estudiante para este curso
                        val allSubmissions = com.example.tareamov.service.SupabaseClient.fetchTaskSubmissions()
                        val studentSubmissions = allSubmissions.filter { submission -> 
                            submission.studentUsername.equals(username, ignoreCase = true) && 
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
                        
                        // Get user ID from username
                        val userId = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(username)
                        if (userId == null) {
                            Log.e("CourseDetailFragment", "Failed to get user ID for username: $username")
                            return@withContext
                        }
                        
                        // Actualizar en Supabase
                        val progreso = com.example.tareamov.data.entity.ProgresoEstudiante(
                            usuarioEstudiante = userId,
                            cursoId = courseIdToUse,
                            tareasTotales = tareasTotales,
                            tareasCompletadas = tareasCompletadas,
                            porcentajeProgreso = porcentajeProgreso,
                            promedio = promedio,
                            calificacionPonderada = promedio,
                            ultimaCalculadaEn = System.currentTimeMillis(),
                            certificadoEmitidoEn = null,
                            creadoEn = System.currentTimeMillis()
                        )
                        
                        val success = com.example.tareamov.service.SupabaseClient.upsertProgresoEstudiante(progreso)
                        
                        if (success) {
                            Log.i("CourseDetailFragment", "✅ Progress updated successfully for $username")
                            
                            // Actualizar también en base de datos local
                            val db = AppDatabase.getDatabase(requireContext())
                            db.progresoEstudianteDao().insertProgreso(progreso)
                        } else {
                            Log.w("CourseDetailFragment", "⚠️ Failed to update progress in Supabase")
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
            // For videos, use our custom VideoPlayerActivity
            if (item.contentType == "video") {
                Log.d("CourseDetailFragment", "Opening video content: ${item.name}, URI: ${item.uriString}")

                // Validate and process the URI
                var processedUri = item.uriString
                
                // Handle different URI formats
                if (processedUri.isNotEmpty()) {
                    // If it's a file path without scheme, add file:// prefix
                    if (!processedUri.startsWith("content://") && !processedUri.startsWith("file://") && !processedUri.startsWith("android.resource://")) {
                        val file = File(processedUri)
                        if (file.exists()) {
                            try {
                                // Try to get a content URI using FileProvider
                                val contentUri = FileProvider.getUriForFile(
                                    requireContext(),
                                    "${requireContext().packageName}.service.fileprovider",
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
                    Toast.makeText(context, "URI del video no válida", Toast.LENGTH_SHORT).show()
                    return
                }

                // Create intent for our custom video player
                val intent = Intent(requireContext(), VideoPlayerActivity::class.java)

                // Pass all necessary information with the correct keys that VideoPlayerActivity expects
                intent.putExtra("video_path", processedUri)  // Use processed URI
                intent.putExtra("video_title", item.name ?: "Video")
                intent.putExtra("video_description", "")  // ContentItem doesn't have description
                intent.putExtra("username", currentUsername ?: "")

                // Add flags to grant permissions
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                try {
                    startActivity(intent)
                    Log.d("CourseDetailFragment", "Successfully started VideoPlayerActivity with URI: $processedUri")
                } catch (e: Exception) {
                    Log.e("CourseDetailFragment", "Error starting VideoPlayerActivity: ${e.message}", e)
                    Toast.makeText(context, "Error al abrir el reproductor de video", Toast.LENGTH_SHORT).show()
                }
                return
            }

            // For other content types, use the standard approach
            val contentUri = Uri.parse(item.uriString)
            val file = File(contentUri.path ?: "")

            // Create a content URI using FileProvider
            val contentUriForSharing = if (contentUri.scheme == "file") {                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.service.fileprovider",
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
                Toast.makeText(context, "No se puede abrir el contenido: ${item.name}", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("CourseDetailFragment", "Error opening content URI: ${item.uriString}", e)
            Toast.makeText(context, "No se puede abrir el contenido: ${item.name}", Toast.LENGTH_SHORT).show()
        }
    }

    // Open a video in the in-app floating player (MainActivity.showFloatingPlayer)
    private fun openFloatingPlayer(item: ContentItem) {
        try {
            var processedUri = item.uriString ?: ""
            if (processedUri.isNotEmpty()) {
                if (!processedUri.startsWith("content://") && !processedUri.startsWith("file://") && !processedUri.startsWith("android.resource://")) {
                    val file = java.io.File(processedUri)
                    if (file.exists()) {
                        try {
                            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                requireContext(),
                                "${requireContext().packageName}.service.fileprovider",
                                file
                            )
                            processedUri = contentUri.toString()
                        } catch (e: Exception) {
                            processedUri = "file://$processedUri"
                        }
                    } else {
                        // leave as-is (may be a remote URL)
                    }
                }
            } else {
                android.widget.Toast.makeText(context, "URI del video no válida", android.widget.Toast.LENGTH_SHORT).show()
                return
            }

            // Call MainActivity API to show floating player
            (activity as? com.example.tareamov.MainActivity)?.showFloatingPlayer(processedUri)
        } catch (e: Exception) {
            android.util.Log.e("CourseDetailFragment", "Error opening floating player", e)
            android.widget.Toast.makeText(context, "No se pudo abrir el reproductor flotante", android.widget.Toast.LENGTH_SHORT).show()
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
        if (!sess.isAdmin()) {
            // Hide the entire slot before drawing to prevent any gap for non-admin users
            adminSlot.visibility = View.GONE
            return
            }
        // User is admin according to SessionManager: show button and wire listener
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
                val db = AppDatabase.getDatabase(requireContext())
                val usuarioWithRole = withContext(Dispatchers.IO) {
                    db.usuarioDao().getUsuarioWithRoleByUsername(username)
                }
                
                val isAdmin = usuarioWithRole?.isAdmin == true
                Log.d("CourseDetailFragment", "User $username is admin: $isAdmin (role: ${usuarioWithRole?.rolNombre})")
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
                    Toast.makeText(context, "Precio inválido. Debe ser un número positivo.", Toast.LENGTH_SHORT).show()
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
                
                // Update locally
                withContext(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(requireContext())
                    db.courseDao()?.updateCourse(updatedCourse)
                }
                
                // Sync to Supabase
                withContext(Dispatchers.IO) {
                    syncRepository.upsertCourseToSupabase(updatedCourse)
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
}