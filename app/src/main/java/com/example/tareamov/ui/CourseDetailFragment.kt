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
    private lateinit var topicsContainer: LinearLayout
    private var isCurrentUserCreator: Boolean = false
    private var currentUsername: String? = null
    private var courseCreatorUsername: String? = null
    private lateinit var courseViewModel: CourseViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var bottomNavBinding: ComponentBottomNavigationBinding

    // Views for course creator info
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

        // Initialize creator info views
        creatorInfoContainer = view.findViewById(R.id.creatorInfoContainer)
        creatorAvatarImageView = view.findViewById(R.id.creatorAvatarImageView)
        creatorUsernameTextView = view.findViewById(R.id.creatorUsernameTextView)
        subscriberCountTextView = view.findViewById(R.id.subscriberCountTextView)
        subscribeButton = view.findViewById(R.id.subscribeButton)

        // Initialize payment container and button
        val paymentButtonContainer = view.findViewById<FrameLayout>(R.id.paymentButtonContainer)
        val paymentButton = view.findViewById<Button>(R.id.paymentButton)
        val paymentPSEButton = view.findViewById<Button>(R.id.paymentPSEButton) // Find the new PSE button

        // Initialize tab views
        tabDocumentos = view.findViewById(R.id.tabDocumentos)
        tabTareas = view.findViewById(R.id.tabTareas)
        //  continueWatchingContainer = view.findViewById(R.id.continueWatchingContainer) // Initialization

        // Set up tab click listeners
        tabDocumentos.setOnClickListener {
            currentTab = "documentos"
            updateTabSelection()
            loadCourseDetails() // Reload with filter applied
        }

        tabTareas.setOnClickListener {
            currentTab = "tareas"
            updateTabSelection()
            loadCourseDetails() // Reload with filter applied
        }

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
        }        // Set up subscribe button click listener
        subscribeButton.setOnClickListener {
            handleSubscription()
        }

        // Set up creator username click listener to navigate to user profile view
        creatorUsernameTextView.setOnClickListener {
            val username = creatorUsernameTextView.text.toString()
            if (username.isNotEmpty()) {
                val bundle = Bundle().apply {
                    putString("username", username)
                }
                findNavController().navigate(R.id.action_courseDetailFragment_to_userProfileViewFragment, bundle)
            }
        }

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
                // Show edit button only if current user is the creator
                if (sessionManager.getUsername() == it.username) {
                    editCourseButton.visibility = View.VISIBLE
                } else {
                    editCourseButton.visibility = View.GONE
                }
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
                    // Update in DB and UI
                    lifecycleScope.launch {
                        val db = AppDatabase.getDatabase(context)
                        val course = courseViewModel.course.value
                        if (course != null) {
                            val updatedCourse = course.copy(title = newTitle, description = newDesc)
                            withContext(Dispatchers.IO) {
                                db.videoDao().updateVideo(updatedCourse)
                            }
                            courseViewModel.getCourseById(courseId) // Refresh
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()        }

        // Load course details
        courseViewModel.getCourseById(courseId)

        // Set up subscribe button click listener
        subscribeButton.setOnClickListener {
            if (sessionManager.isLoggedIn()) {
                // User is logged in, proceed with subscription
                lifecycleScope.launch {
                    val db = AppDatabase.getDatabase(requireContext())
                    val username = sessionManager.getUsername() ?: return@launch
                    val course = courseViewModel.course.value ?: return@launch

                    val subscription = Subscription(
                        subscriberUsername = username,
                        creatorUsername = course.username,
                        subscriptionDate = System.currentTimeMillis()
                    )

                    withContext(Dispatchers.IO) {
                        db.subscriptionDao().insertSubscription(subscription)
                    }

                    Toast.makeText(requireContext(), "Te has suscrito al curso exitosamente", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                }
            } else {
                // User is not logged in, show message and navigate to login
                Toast.makeText(requireContext(), "Debes iniciar sesión para suscribirte", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.loginFragment)
            }
        }
        
        // Setup bottom navigation
        setupBottomNavigation(view)
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
        Log.d("CourseDetailFragment", "Navigating to SelectTopicFragment for courseId: $courseId, courseName: $nameOfCourse")
        val bundle = Bundle().apply {
            putLong("courseId", courseId)
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
        val videoDao = db.videoDao()
        val usuarioDao = db.usuarioDao()
        val personaDao = db.personaDao()
        val subscriptionDao = db.subscriptionDao()  // Use the DAO
        val noTopicsTextView = view?.findViewById<TextView>(R.id.noTopicsTextView)
        val courseTitleTextView = view?.findViewById<TextView>(R.id.courseTitleTextView)
        // Add a TextView for when tasks are filtered and none are found
        val noTasksTextView = view?.findViewById<TextView>(R.id.noTasksTextView) // Make sure this ID exists in your layout or create it

        CoroutineScope(Dispatchers.Main).launch {
            try { // Start of the main try block
                // First, get the course details to display the title
                val course = withContext(Dispatchers.IO) {
                    videoDao.getVideoById(courseId)
                }

                // Set the course title
                courseTitleTextView?.text = course?.title ?: "Curso sin título"
                courseName = course?.title ?: "Curso sin título"

                // Get course creator username and check if current user is the creator
                courseCreatorUsername = course?.username
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

                    creatorInfoContainer.visibility = View.VISIBLE

                    // Initialize and load course progress for non-creator users
                    initializeAndLoadCourseProgress(
                        courseId = courseId,
                        username = currentUsername,
                        isCurrentUserCreator = isCurrentUserCreator
                    )
                } else {
                    creatorInfoContainer.visibility = View.GONE
                }

                // Get topics for this course directly
                val topics = withContext(Dispatchers.IO) {
                    topicDao.getTopicsByCourse(courseId)
                }

                Log.d("CourseDetailFragment", "Found ${topics.size} topics for courseId: $courseId")

                if (topics.isEmpty()) {
                    // Handle case with no topics
                    Log.d("CourseDetailFragment", "No topics found for course ID: $courseId")
                    noTopicsTextView?.text = "Este curso aún no tiene temas." // Set specific message
                    noTopicsTextView?.visibility = View.VISIBLE
                    topicsContainer.visibility = View.GONE
                    // Ensure the "no tasks" message is hidden if there are no topics at all
                    noTasksTextView?.visibility = View.GONE
                } else { // Start of the else block (when topics exist)
                    // Don't set visibility for noTopicsTextView or topicsContainer yet

                    // Get all content items and tasks for these topics
                    val topicIds = topics.map { it.id }
                    val contentItems = withContext(Dispatchers.IO) {
                        contentItemDao.getContentItemsByTopicIds(topicIds)
                    }
                    val tasks = withContext(Dispatchers.IO) { // Fetch tasks
                        taskDao.getTasksByTopicIds(topicIds)
                    }

                    // Create maps for efficient lookup
                    val contentItemsMap = contentItems.groupBy { it.topicId }
                    val tasksMap = tasks.groupBy { it.topicId } // Group tasks by topicId

                    // Clear previous views if any
                    topicsContainer.removeAllViews()
                    // Reset visibility of messages initially
                    noTopicsTextView?.visibility = View.GONE
                    noTasksTextView?.visibility = View.GONE

                    // Now iterate and add views using the fetched data
                    val sortedTopics = topics.sortedBy { it.orderIndex }
                    var itemsAdded = false // Flag to track if any relevant items were added

                    for (topic in sortedTopics) {
                        val topicContentItems = contentItemsMap[topic.id]?.filter { it.taskId == null } ?: emptyList() // Filter content for topic only
                        val topicTasks = tasksMap[topic.id] ?: emptyList() // Get tasks for this topic

                        // Conditionally add views based on the current tab
                        if (currentTab == "documentos") {
                            // Only add topic view if it has content items when 'documentos' tab is selected
                            if (topicContentItems.isNotEmpty()) {
                                addTopicView(topic, topicContentItems, emptyList()) // Pass empty task list
                                itemsAdded = true
                            }
                        } else { // currentTab == "tareas"
                            // Only add topic view if it has tasks when 'tareas' tab is selected
                            if (topicTasks.isNotEmpty()) {
                                addTopicView(topic, emptyList(), topicTasks) // Pass empty content list
                                itemsAdded = true
                            }
                        }
                    } // Closes for loop

                    // Handle cases where filtering results in no items OR items were added
                    if (!itemsAdded) {
                        topicsContainer.visibility = View.GONE // Hide the container
                        if (currentTab == "documentos") {
                            noTopicsTextView?.text = "No hay documentos en este curso."
                            noTopicsTextView?.visibility = View.VISIBLE
                            noTasksTextView?.visibility = View.GONE // Hide other message
                        } else { // currentTab == "tareas"
                            noTasksTextView?.text = "No hay tareas en este curso."
                            noTasksTextView?.visibility = View.VISIBLE // Show no tasks message
                            noTopicsTextView?.visibility = View.GONE // Hide other message
                        }
                    } else {
                        // If items were added, make sure the container is visible
                        topicsContainer.visibility = View.VISIBLE
                        // And hide both 'no items' messages
                        noTopicsTextView?.visibility = View.GONE
                        noTasksTextView?.visibility = View.GONE
                    }
                    // Removed the misplaced catch block and extra braces that were here.
                    // The 'else' block correctly ends below.
                } // <<<< This brace correctly closes the 'else' block
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
            // Get the creator's user information
            val usuario = withContext(Dispatchers.IO) {
                usuarioDao.getUsuarioByUsername(creatorUsername)
            }

            if (usuario != null) {
                // Get the creator's persona information
                val persona = withContext(Dispatchers.IO) {
                    personaDao.getPersonaById(usuario.personaId)
                }

                // Update UI with creator info
                if (persona != null) {
                    withContext(Dispatchers.Main) {
                        // Set creator username
                        creatorUsernameTextView.text = creatorUsername

                        // Load avatar image
                        if (!persona.avatar.isNullOrEmpty()) {
                            try {
                                Glide.with(requireContext())
                                    .load(Uri.parse(persona.avatar))
                                    .placeholder(R.drawable.default_avatar)
                                    .error(R.drawable.default_avatar)
                                    .into(creatorAvatarImageView)
                            } catch (e: Exception) {
                                Log.e("CourseDetailFragment", "Error loading avatar", e)
                                creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                            }
                        } else {
                            creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                        }

                        // Set subscriber count
                        subscriberCountTextView.text = formatSubscriberCount(subscriptionCount)

                        // Update subscribe button state based on current subscription status
                        this@CourseDetailFragment.isSubscribed = isSubscribed
                        updateSubscribeButtonState(isSubscribed)
                        
                        // Show/hide subscribe button based on whether user is viewing their own course
                        if (currentUsername == creatorUsername) {
                            subscribeButton.visibility = View.GONE
                        } else {
                            subscribeButton.visibility = View.VISIBLE
                        }

                        // Show creator info container
                        creatorInfoContainer.visibility = View.VISIBLE
                    }
                } else {
                    Log.e("CourseDetailFragment", "Persona not found for user: $creatorUsername")
                    withContext(Dispatchers.Main) {
                        creatorInfoContainer.visibility = View.GONE
                    }
                }
            } else {
                Log.e("CourseDetailFragment", "Usuario not found: $creatorUsername")
                withContext(Dispatchers.Main) {
                    creatorInfoContainer.visibility = View.GONE
                }
            }
        } catch (e: Exception) {
            Log.e("CourseDetailFragment", "Error loading creator info", e)
            withContext(Dispatchers.Main) {
                creatorInfoContainer.visibility = View.GONE
                Toast.makeText(context, "Error al cargar información del creador", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // New method to update subscribe button state
    private fun updateSubscribeButtonState(isSubscribed: Boolean) {
        subscribeButton.apply {
            if (isSubscribed) {
                text = "SUSCRITO"
                setBackgroundResource(R.drawable.rounded_button_subscribed_background)
            } else {
                text = "SUSCRIBIRSE"
                setBackgroundResource(R.drawable.rounded_button_background)
            }
        }
    }

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
                    updateSubscribeButtonState(false)

                    // Actualizar contador de suscriptores
                    val newCount = withContext(Dispatchers.IO) {
                        subscriptionDao.getSubscriptionCountForCreator(creatorUser)
                    }
                    subscriberCountTextView.text = formatSubscriberCount(newCount)

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
                    updateSubscribeButtonState(true)

                    // Actualizar contador de suscriptores
                    val newCount = withContext(Dispatchers.IO) {
                        subscriptionDao.getSubscriptionCountForCreator(creatorUser)
                    }
                    subscriberCountTextView.text = formatSubscriberCount(newCount)

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
        if (currentTab == "documentos") {
            tabDocumentos.setBackgroundResource(R.drawable.tab_selected_background)
            tabDocumentos.setTextColor(resources.getColor(android.R.color.black, null))
            tabTareas.background = null
            tabTareas.setTextColor(resources.getColor(android.R.color.darker_gray, null))
        } else { // currentTab == "tareas"
            tabTareas.setBackgroundResource(R.drawable.tab_selected_background)
            tabTareas.setTextColor(resources.getColor(android.R.color.black, null))
            tabDocumentos.background = null
            tabDocumentos.setTextColor(resources.getColor(android.R.color.darker_gray, null))
        }
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

        taskNameTextView.text = task.name
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

    // Add this helper method to check student submission status
    private fun checkStudentSubmission(taskId: Long, gradeStatusTextView: TextView?) {
        if (gradeStatusTextView == null) return

        val username = sessionManager.getUsername() ?: return

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val submission = withContext(Dispatchers.IO) {
                    db.taskSubmissionDao().getUserSubmissionForTask(taskId, username)
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
        val goToAdminButton = bottomNavBinding.goToAdminButton
        
        // Check if the current user is admin
        checkAdminStatus { isAdmin ->
            if (isAdmin) {
                goToAdminButton.visibility = View.VISIBLE
                goToAdminButton.setOnClickListener {
                    Log.d("CourseDetailFragment", "Admin button clicked, navigating to HomeFragment")
                    findNavController().navigate(R.id.action_courseDetailFragment_to_homeFragment)
                }
            } else {
                goToAdminButton.visibility = View.INVISIBLE
            }
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