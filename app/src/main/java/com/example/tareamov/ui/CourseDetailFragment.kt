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
    private lateinit var editCourseButton: ImageButton
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
        AppDatabase.getDatabase(requireContext()).fileContextDao()
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
        editCourseButton = view.findViewById(R.id.editCourseButton)

    // Initially hide edit controls until we verify creator ownership remotely
    editCourseButton.visibility = View.GONE
    courseTitleTextView.isClickable = false

        val courseTitle = view.findViewById<TextView>(R.id.courseTitleTextView)
        val courseDescription = view.findViewById<TextView>(R.id.courseDescriptionTextView)
        val subscribeButton = view.findViewById<Button>(R.id.subscribeButton)

        // Observe course details
        courseViewModel.course.observe(viewLifecycleOwner) { course ->
            course?.let {
                courseTitle.text = it.title
                courseDescription.text = it.description
                courseTitleTextView = view.findViewById(R.id.courseTitleTextView)
                courseDescriptionTextView = view.findViewById(R.id.courseDescriptionTextView)
                editCourseButton = view.findViewById(R.id.editCourseButton)
                // Decide edit button visibility using Supabase when possible
                val localUsername = sessionManager.getUsername()
                editCourseButton.visibility = View.GONE
                if (localUsername != null) {
                    lifecycleScope.launch {
                        var showEdit = false
                        try {
                            val act = requireActivity()
                            if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured()) {
                                val remoteCourse = withContext(Dispatchers.IO) { act.syncRepository.fetchCourseById(courseId) }
                                if (remoteCourse != null) {
                                    showEdit = (remoteCourse.creatorUsername ?: "") == localUsername
                                } else {
                                    // fallback to local course data if remote missing
                                    showEdit = (localUsername == it.creatorUsername)
                                }
                            } else {
                                // Supabase not configured, fallback to local check
                                showEdit = (localUsername == it.creatorUsername)
                            }
                        } catch (e: Exception) {
                            Log.w("CourseDetailFragment", "Error checking remote creator", e)
                            showEdit = (localUsername == it.creatorUsername)
                        }
                        editCourseButton.visibility = if (showEdit) View.VISIBLE else View.GONE
                    }
                }
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

        // Edit button click: show dialog to edit title/description
        editCourseButton.setOnClickListener {
            val context = requireContext()
            val inflater = LayoutInflater.from(context)
            // Asegúrate de inflar SIEMPRE el layout correcto
            val dialogView = inflater.inflate(R.layout.dialog_edit_course, null)
            val titleEdit = dialogView.findViewById<EditText>(R.id.editCourseTitle)
            val descEdit = dialogView.findViewById<EditText>(R.id.editCourseDescription)
            // Setea el texto actual
            titleEdit.setText(courseTitleTextView.text)
            descEdit.setText(courseDescriptionTextView.text)

            AlertDialog.Builder(context)
                .setTitle("Editar Curso")
                .setView(dialogView)
                .setPositiveButton("Guardar") { _, _ ->
                    val newTitle = titleEdit.text.toString().trim()
                    val newDesc = descEdit.text.toString().trim()
                    // Update Course and related VideoData, then sync to Supabase
                    lifecycleScope.launch {
                        try {
                            val repo = com.example.tareamov.repository.CourseRepository(requireContext())
                            val db = AppDatabase.getDatabase(requireContext())
                            val course = courseViewModel.course.value
                            if (course != null) {
                                val updatedCourse = course.copy(title = newTitle, description = newDesc)
                                withContext(Dispatchers.IO) {
                                    // Update Course table (if available)
                                    try {
                                        repo.updateCourse(updatedCourse)
                                    } catch (e: Exception) {
                                        // Fallback: update CourseDao directly if repo failed
                                        try {
                                            db.courseDao()?.updateCourse(updatedCourse)
                                        } catch (ignored: Exception) { }
                                    }

                                    // No longer update VideoData as course is stored in Course table
                                    // If there's additional media metadata tied to a Course, it should be handled separately.
                                }

                                // Request Supabase upsert for the updated course
                                try {
                                    val act = requireActivity()
                                    if (act is com.example.tareamov.MainActivity) {
                                        // Ensure values match Course fields and handle nullables
                                        val courseToUpsert = updatedCourse.copy(
                                            // Keep existing course fields; ensure price and flags are valid
                                            price = updatedCourse.price ?: 0.0,
                                            isPremium = updatedCourse.isPremium
                                        )
                                        withContext(Dispatchers.IO) {
                                            act.syncRepository.upsertCourseToSupabase(courseToUpsert)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w("CourseDetailFragment", "Failed to upsert updated course to Supabase: ${e.message}", e)
                                }

                                // Refresh UI
                                courseViewModel.getCourseById(courseId)
                            }
                        } catch (e: Exception) {
                            Log.e("CourseDetailFragment", "Error updating course and videos", e)
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()        }

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
        
        // Observe task creation notifications
        navBackEntry?.savedStateHandle?.getLiveData<Long>("task_created")?.observe(viewLifecycleOwner) { taskId ->
            try {
                Log.d("CourseDetailFragment", "Task created event received, refreshing from Supabase...")
                refreshTopicsFromSupabase()
                navBackEntry.savedStateHandle.remove<Long>("task_created")
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Error handling task_created event", e)
            }
        }
        
        // Observe general refresh flag
        navBackEntry?.savedStateHandle?.getLiveData<Boolean>("refresh_from_supabase")?.observe(viewLifecycleOwner) { shouldRefresh ->
            if (shouldRefresh == true) {
                Log.d("CourseDetailFragment", "Refresh flag received, reloading from Supabase...")
                refreshTopicsFromSupabase()
                navBackEntry.savedStateHandle.remove<Boolean>("refresh_from_supabase")
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

                    // Map creator username and flags
                    courseCreatorUsername = remoteCourse.creatorUsername
                    isCurrentUserCreator = courseCreatorUsername == currentUsername
                    courseActionBar.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE

                    // Show payment container if course is premium and viewer is not the creator
                    val paymentContainer = view?.findViewById<FrameLayout>(R.id.paymentButtonContainer)
                    if (remoteCourse.isPremium == true && !isCurrentUserCreator) {
                        paymentContainer?.visibility = View.VISIBLE
                    } else {
                        paymentContainer?.visibility = View.GONE
                    }

                    // Load creator info if the current user is not the creator
                    if (!isCurrentUserCreator && !courseCreatorUsername.isNullOrEmpty()) {
                        // Prefer remote subscription state when available
                        var subscriptionCount = withContext(Dispatchers.IO) {
                            subscriptionDao.getSubscriptionCountForCreator(courseCreatorUsername!!)
                        }
                        var isSubscribedLocal = withContext(Dispatchers.IO) {
                            currentUsername?.let { username -> subscriptionDao.isSubscribed(username, courseCreatorUsername!!) } ?: false
                        }
                        var isSubscribedRemote = false
                        try {
                            val act = requireActivity()
                            if (act is MainActivity && com.example.tareamov.service.SupabaseClient.isConfigured() && currentUsername != null) {
                                isSubscribedRemote = withContext(Dispatchers.IO) { act.syncRepository.isSubscribedRemote(currentUsername!!, courseCreatorUsername!!) }
                                // If remote is true but local count doesn't include this subscriber, adjust
                                if (isSubscribedRemote && !isSubscribedLocal) {
                                    // persist locally
                                    withContext(Dispatchers.IO) {
                                        subscriptionDao.insertSubscription(Subscription(subscriberUsername = currentUsername!!, creatorUsername = courseCreatorUsername!!, subscriptionDate = System.currentTimeMillis()))
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

                    // Map creator username from local course record
                    courseCreatorUsername = course?.creatorUsername
                    isCurrentUserCreator = courseCreatorUsername == currentUsername

                    // Control visibility of the bottom action bar based on creator status
                    courseActionBar.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE

                    // Load creator info if the current user is not the creator
                    if (!isCurrentUserCreator && courseCreatorUsername != null) {
                        // Get subscription count using SubscriptionDao
                        val subscriptionCount = withContext(Dispatchers.IO) {
                            subscriptionDao.getSubscriptionCountForCreator(courseCreatorUsername!!)
                        }

                        // Check if current user is subscribed using SubscriptionDao
                        val isSubscribed = withContext(Dispatchers.IO) {
                            currentUsername?.let { username ->
                                subscriptionDao.isSubscribed(username, courseCreatorUsername!!)
                            } ?: false
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
                    topicsContainer.removeAllViews()
                    topicsContainer.visibility = View.VISIBLE
                    noTasksTextView?.visibility = View.GONE
                } else {
                    // Clear previous views and reset messages
                    topicsContainer.removeAllViews()
                    noTopicsTextView?.visibility = View.GONE
                    noTasksTextView?.visibility = View.GONE

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
                        } else {
                            noTasksTextView?.text = "No hay tareas en este curso."
                            noTasksTextView?.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) { // This is the correct catch block for the main try
                Log.e("CourseDetailFragment", "Error loading course details", e)
                Toast.makeText(context, "Error al cargar detalles del curso", Toast.LENGTH_SHORT).show()
                noTopicsTextView?.text = "Error al cargar datos." // Generic error message
                noTopicsTextView?.visibility = View.VISIBLE
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

        if (currentUser == null) {
            Toast.makeText(context, "Debes iniciar sesión para suscribirte", Toast.LENGTH_SHORT).show()
            return
        }

        if (creatorUser == null) {
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
                        subscriptionDao.deleteSubscription(currentUser, creatorUser)
                    }
                    isSubscribed = false

                    // Actualizar UI del botón
                    // updateSubscribeButtonState(false) // Moved to ExploreFragment cards

                    // Actualizar contador de suscriptores
                    // val newCount = withContext(Dispatchers.IO) {
                    //     subscriptionDao.getSubscriptionCountForCreator(creatorUser)
                    // }
                    // subscriberCountTextView.text = formatSubscriberCount(newCount) // Moved to ExploreFragment cards

                    Toast.makeText(context, "Te has desuscrito de $creatorUser", Toast.LENGTH_SHORT).show()
                } else {
                    // Suscribirse
                    val subscription = Subscription(
                        subscriberUsername = currentUser,
                        creatorUsername = creatorUser,
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
                    //     subscriptionDao.getSubscriptionCountForCreator(creatorUser)
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
        val contentView = TextView(context).apply {
            text = "📄 ${item.name ?: "Contenido sin título"}"
            textSize = 14f
            setPadding(16, 8, 16, 8)
            setTextColor(resources.getColor(android.R.color.white, null))
            setOnClickListener { openContent(item) }
            background = resources.getDrawable(R.drawable.bg_card_premium, null)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            layoutParams = params
        }
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
        val taskContentContainer = taskView.findViewById<LinearLayout>(R.id.taskContentContainer)
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
                android.util.Log.w("CourseDetailFragment", "Failed to fetch remote task title", e)
            }
        }
    }
        if (!task.description.isNullOrBlank()) {
            taskDescriptionTextView.text = task.description
            taskDescriptionTextView.visibility = View.VISIBLE
        } else {
            taskDescriptionTextView.visibility = View.GONE
        }

        // Load and display content items associated with this task
        loadTaskContentItems(task.id, taskContentContainer)

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

        CoroutineScope(Dispatchers.Main).launch {
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
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "refreshTopicsFromSupabase failed", e)
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
        val contentItemDao = AppDatabase.getDatabase(requireContext()).contentItemDao()
        CoroutineScope(Dispatchers.Main).launch {
            val taskContentItems = withContext(Dispatchers.IO) {
                contentItemDao.getContentItemsByTaskId(taskId)
            }
            container.removeAllViews()
            val sortedContent = taskContentItems.sortedBy { it.orderIndex }
            
            if (sortedContent.isNotEmpty()) {
                // Find and show the content label
                val parentView = container.parent as? ViewGroup
                val taskContentLabel = parentView?.findViewById<TextView>(R.id.taskContentLabel)
                taskContentLabel?.visibility = View.VISIBLE
                
                for (item in sortedContent) {
                    addContentView(item, container, isTaskContent = true)
                }
            } else {
                // Hide the content label if no content
                val parentView = container.parent as? ViewGroup
                val taskContentLabel = parentView?.findViewById<TextView>(R.id.taskContentLabel)
                taskContentLabel?.visibility = View.GONE
            }
        }
    }// Modify addContentView to handle task content layout and clicks
    private fun addContentView(item: ContentItem, container: LinearLayout, isTaskContent: Boolean = false) {
        Log.d("CourseDetailFragment", "Adding content view - Name: ${item.name}, Type: ${item.contentType}, URI: ${item.uriString}, isTaskContent: $isTaskContent")
        
        val inflater = LayoutInflater.from(context)
        val layoutRes = if (isTaskContent) {
            R.layout.item_content_detail // Use detail layout for task content
        } else {
            R.layout.item_course_content_detail // Use course content layout for topics
        }
        val contentView = inflater.inflate(layoutRes, container, false)

        if (isTaskContent) {
            // Handle task content display (item_content_detail.xml)
            val contentTitleTextView = contentView.findViewById<TextView>(R.id.contentTitleTextView)
            val contentDescriptionTextView = contentView.findViewById<TextView>(R.id.contentDescriptionTextView)
            val contentDurationTextView = contentView.findViewById<TextView>(R.id.contentDurationTextView)
            val contentThumbnailImageView = contentView.findViewById<ImageView>(R.id.contentThumbnailImageView)
            val contentTypeIconView = contentView.findViewById<ImageView>(R.id.contentTypeIconView)

            contentTitleTextView.text = item.name ?: "Contenido sin nombre"
            contentDescriptionTextView.text = getContentTypeDescription(item.contentType)
            contentDurationTextView.text = if (item.contentType == "video") "Video" else "Documento"

            // Load thumbnail and set type icon
            loadContentThumbnail(item, contentThumbnailImageView)
            setContentTypeIcon(item.contentType, contentTypeIconView)

        } else {
            // Handle course content display (item_course_content_detail.xml)
            val contentNameTextView = contentView.findViewById<TextView>(R.id.contentNameTextView)
            val contentDurationTextView = contentView.findViewById<TextView>(R.id.contentDurationTextView)
            val contentTypeTextView = contentView.findViewById<TextView>(R.id.contentTypeTextView)
            val contentThumbnailImageView = contentView.findViewById<ImageView>(R.id.contentThumbnailImageView)
            val contentIconView = contentView.findViewById<ImageView>(R.id.contentIconView)

            contentNameTextView.text = item.name ?: "Contenido sin nombre"
            contentDurationTextView.text = if (item.contentType == "video") "Video" else "Documento"
            contentTypeTextView.text = item.contentType.uppercase()

            // Load thumbnail and set type icon
            loadContentThumbnail(item, contentThumbnailImageView)
            setContentTypeIcon(item.contentType, contentIconView)
        }

        contentView.setOnClickListener {
            openContent(item)
        }

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
}