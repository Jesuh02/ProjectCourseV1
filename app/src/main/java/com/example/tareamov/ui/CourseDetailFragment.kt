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
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
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
import com.example.tareamov.util.AppCache
import com.example.tareamov.viewmodel.CourseDetailSnapshot
import com.example.tareamov.viewmodel.CourseViewModel
import com.example.tareamov.viewmodel.CourseTopicData
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import com.example.tareamov.ui.showPaymentOptions // Import the showPaymentOptions extension
import com.example.tareamov.ui.VideoPlayerActivity // Import VideoPlayerActivity
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.flow.collect

class CourseDetailFragment : Fragment() {

    private var courseId: Long = -1
    private var courseName: String = ""
    private var resolvedCourseId: Long = -1
    private var subjectId: Long = -1
    private var subjectName: String? = null
    private var subjectDescription: String? = null
    private var subjectThumbnailUrl: String? = null
    private lateinit var topicsContainer: LinearLayout
    private var isCurrentUserCreator: Boolean = false
    private var hasEditAccess: Boolean = false
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
    private lateinit var tabDocumentos: LinearLayout
    private lateinit var tabTareas: LinearLayout
    private var tabDocumentosLabel: TextView? = null
    private var tabTareasLabel: TextView? = null
    private var tabDocumentosCount: TextView? = null
    private var tabTareasCount: TextView? = null
    private var cachedTopicsCount: Int = -1
    private var cachedTasksCount: Int = -1
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

    private fun populateTopicCache(
        topics: List<Topic>,
        contentByTopic: Map<Long, List<ContentItem>>,
        tasksByTopic: Map<Long, List<Task>>
    ) {
        cachedTopicsData.clear()
        for (topic in topics) {
            cachedTopicsData.add(
                Triple(
                    topic,
                    contentByTopic[topic.id].orEmpty(),
                    tasksByTopic[topic.id].orEmpty()
                )
            )
        }
        cachedTopicsCount = topics.size
        cachedTasksCount = tasksByTopic.values.sumOf { it.size }
        refreshTabBadges()
    }

    private fun filterBySubject(
        topics: List<Topic>,
        contentByTopic: Map<Long, List<ContentItem>>,
        tasksByTopic: Map<Long, List<Task>>
    ): Triple<List<Topic>, Map<Long, List<ContentItem>>, Map<Long, List<Task>>> {
        if (subjectId <= 0) return Triple(topics, contentByTopic, tasksByTopic)
        val filtered = topics.filter { it.subjectId == subjectId }
        val topicIds = filtered.map { it.id }.toSet()
        return Triple(
            filtered,
            contentByTopic.filterKeys { it in topicIds },
            tasksByTopic.filterKeys { it in topicIds }
        )
    }

    private fun renderSnapshot(
        snapshot: CourseDetailSnapshot,
        noTopicsTextView: TextView?,
        noTasksTextView: TextView?,
        renderTree: Boolean = true
    ) {
        resolvedCourseId = snapshot.effectiveCourseId
        snapshot.course?.let { renderCourseMetadata(it) }
        val (topics, content, tasks) = filterBySubject(snapshot.topics, snapshot.contentByTopic, snapshot.tasksByTopic)
        populateTopicCache(topics, content, tasks)
        if (renderTree) {
            renderTopicData(topics, content, tasks, noTopicsTextView, noTasksTextView)
        }
    }

    private fun renderCourseMetadata(course: com.example.tareamov.data.entity.Course) {
        val displayTitle = subjectName ?: course.title
        val displayDescription = subjectDescription ?: course.description
        val displayThumbnail = subjectThumbnailUrl ?: course.thumbnailUri

        courseName = displayTitle
        courseTitleTextView.text = displayTitle
        courseDescriptionTextView.text = displayDescription
        courseThematicTextView.text = "Temática: ${course.category ?: "General"}"
        updatePriceDisplay(course.price)

            creatorUserId = course.creatorUserId
            isCurrentUserCreator = sessionManager.hasRole(3)
            hasEditAccess = isCurrentUserCreator
        applyEditAccessVisibility()

        val heroImageView = view?.findViewById<ImageView>(R.id.courseHeroImageView)
        if (!displayThumbnail.isNullOrBlank() && heroImageView != null) {
            try {
                Glide.with(this)
                    .load(displayThumbnail)
                    .centerCrop()
                    .into(heroImageView)
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Could not load hero thumbnail", e)
            }
        }
    }

    private fun applyEditAccessVisibility() {
        editCourseButton.visibility = if (hasEditAccess) View.VISIBLE else View.GONE
        togglePriceButton.visibility = if (hasEditAccess) View.VISIBLE else View.GONE
        courseActionBar.visibility = if (hasEditAccess) View.VISIBLE else View.GONE
    }

    private fun checkCollaboratorAccess() {
        // Los colaboradores no obtienen permisos de edición en CourseDetailFragment.
        // Solo el creador real del curso puede añadir temas y tareas.
        // Esta función se conserva como stub para compatibilidad con llamadas existentes.
    }

    private fun snapshotChanged(previous: CourseDetailSnapshot?, next: CourseDetailSnapshot): Boolean {
        if (previous == null) return true

        val previousCourse = previous.course
        val nextCourse = next.course
        if (previousCourse?.id != nextCourse?.id) return true
        if (previousCourse?.title != nextCourse?.title) return true
        if (previousCourse?.description != nextCourse?.description) return true
        if (previousCourse?.thumbnailUri != nextCourse?.thumbnailUri) return true
        if (previousCourse?.category != nextCourse?.category) return true
        if (previousCourse?.price != nextCourse?.price) return true
        if (previousCourse?.isPremium != nextCourse?.isPremium) return true
        if (previousCourse?.lastModifiedDate != nextCourse?.lastModifiedDate) return true

        val previousTopics = previous.topics.map { listOf(it.id, it.orderIndex, it.name, it.description) }
        val nextTopics = next.topics.map { listOf(it.id, it.orderIndex, it.name, it.description) }
        if (previousTopics != nextTopics) return true

        val previousTasks = previous.tasksByTopic.values.flatten().sortedBy { it.id }
            .map { listOf(it.id, it.topicId, it.orderIndex, it.name, it.description, it.dueDate) }
        val nextTasks = next.tasksByTopic.values.flatten().sortedBy { it.id }
            .map { listOf(it.id, it.topicId, it.orderIndex, it.name, it.description, it.dueDate) }
        if (previousTasks != nextTasks) return true

        val previousContent = previous.contentByTopic.values.flatten().sortedBy { it.id }
            .map { listOf(it.id, it.topicId, it.taskId, it.name, it.uriString, it.contentType, it.orderIndex) }
        val nextContent = next.contentByTopic.values.flatten().sortedBy { it.id }
            .map { listOf(it.id, it.topicId, it.taskId, it.name, it.uriString, it.contentType, it.orderIndex) }
        return previousContent != nextContent
    }

    private suspend fun resolveCourseSnapshot(requestedCourseId: Long): CourseDetailSnapshot? {
        return courseViewModel.repository.fetchAndCacheSnapshot(
            courseId = requestedCourseId,
            courseName = courseName,
            userId = sessionManager.getUserId(),
            isCreator = isCurrentUserCreator,
            subjectId = subjectId
        )
    }
    
    // BackendApiService is used for all remote operations

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong("courseId", -1)
            courseName = it.getString("courseName", "")
            subjectId = it.getLong("subjectId", -1)
            subjectName = it.getString("subjectName")
            subjectDescription = it.getString("subjectDescription")
            subjectThumbnailUrl = it.getString("subjectThumbnailUrl")
            Log.d("CourseDetailFragment", "Received courseId: $courseId, courseName: $courseName, subjectId: $subjectId")
        }

        // Initialize SessionManager and get current user's username
        sessionManager = SessionManager.getInstance(requireContext())
        currentUsername = sessionManager.getUsername()
        Log.d("CourseDetailFragment", "Current username from session: $currentUsername")
    }

    override fun onResume() {
        super.onResume()
        if (courseId != -1L && !isLoadingCourseDetails) {
            // Always refresh from network on resume to catch cross-device changes
            if (!isCacheValid() || AppCache.isCourseDetailDirty(courseId) || !courseViewModel.isCourseTopicDataFresh(courseId)) {
                AppCache.invalidateCourses()
                AppCache.clearCourseDetailDirty(courseId)
                loadCourseDetails()
            }
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
        tabDocumentosLabel = view.findViewById(R.id.tabDocumentosLabel)
        tabTareasLabel = view.findViewById(R.id.tabTareasLabel)
        tabDocumentosCount = view.findViewById(R.id.tabDocumentosCount)
        tabTareasCount = view.findViewById(R.id.tabTareasCount)
        //  continueWatchingContainer = view.findViewById(R.id.continueWatchingContainer) // Initialization

        // Set up tab click listeners with ultra-fast filtering
        tabDocumentos.setOnClickListener {
            if (currentTab != "documentos") {
                currentTab = "documentos"
                updateTabSelection()
                if (cachedTopicsData.isNotEmpty()) {
                    filterContentUltraFast()
                } else {
                    // Cache is being loaded; wait — loadCourseDetails handles both tabs now
                    Log.d("CourseDetailFragment", "Tab 'documentos' switched while cache is empty, will render when load completes")
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
                    // Cache is being loaded; wait — loadCourseDetails handles both tabs now
                    Log.d("CourseDetailFragment", "Tab 'tareas' switched while cache is empty, will render when load completes")
                }
            }
        }

        // Initialize visual selection to match currentTab and our stateful selectors
        updateTabSelection()

        // Add a button to create new topics
        val addTopicButton = view.findViewById<View>(R.id.addTopicButton)
        addTopicButton.setOnClickListener {
            navigateToAddTopic()
        }

        // *** MODIFIED BLOCK for addTaskButton ***
        val addTaskButton = view.findViewById<View>(R.id.addTaskButton)
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

        // Reset loading flag on each view creation — any prior coroutine will be cancelled
        // by viewLifecycleOwner.lifecycleScope automatically. loadCourseDetails() is called
        // from onViewCreated (after ViewModel is initialized).
        isLoadingCourseDetails = false
        cachedTopicsData.clear()

        return view
    }

    // Add this to the onViewCreated method after initializing other views
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Reset loading flag — any prior lifecycle-scoped coroutine was cancelled when the
        // view was destroyed, so the flag must be cleared for the new view cycle.
        isLoadingCourseDetails = false

        // FIX: Scope ViewModel to the Activity so the in-memory cache survives Fragment
        // destruction and re-creation (pressing Back and re-entering the same course).
        courseViewModel = ViewModelProvider(requireActivity())[CourseViewModel::class.java]

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

                renderCourseMetadata(it)
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

        // Load course metadata into ViewModel (title, description, price, etc.)
        courseViewModel.getCourseById(courseId)

        // Setup bottom navigation
        setupBottomNavigation(view)

        val navBackEntry = findNavController().currentBackStackEntry
        val sh = navBackEntry?.savedStateHandle

        val pendingForceReload = sh?.remove<Boolean>("force_reload_topics") == true
        val pendingTopicCreated = sh?.remove<Long>("topic_created") != null
        val pendingRefreshSupabase = sh?.remove<Boolean>("refresh_from_supabase") == true
        val pendingSwitchTab = sh?.remove<Boolean>("switch_to_tasks_tab") == true
        val hasPendingMutation = pendingForceReload || pendingTopicCreated || pendingRefreshSupabase

        if (hasPendingMutation) {
            cachedTopicsData.clear()
            courseViewModel.markCourseDetailDirty(courseId)
            AppCache.invalidateCourses()
            Log.d("CourseDetailFragment", "Pending mutation — cache invalidated, will do full reload")
        }

        if (pendingSwitchTab) {
            currentTab = "tareas"
            updateTabSelection()
        }

        navBackEntry?.savedStateHandle?.getLiveData<Boolean>("force_reload_topics")?.observe(viewLifecycleOwner) { flag ->
            if (flag == true) {
                navBackEntry.savedStateHandle.remove<Boolean>("force_reload_topics")
                cachedTopicsData.clear()
                courseViewModel.markCourseDetailDirty(courseId)
                AppCache.invalidateCourses()
                loadCourseDetails()
            }
        }

        navBackEntry?.savedStateHandle?.getLiveData<Boolean>("switch_to_tasks_tab")?.observe(viewLifecycleOwner) { flag ->
            if (flag == true) {
                navBackEntry.savedStateHandle.remove<Boolean>("switch_to_tasks_tab")
                currentTab = "tareas"
                updateTabSelection()
                if (cachedTopicsData.isNotEmpty()) filterContentUltraFast()
            }
        }

        // Observe reactive cache invalidation for this course's detail
        viewLifecycleOwner.lifecycleScope.launch {
            AppCache.courseDetailRefresh.collect { dirtyId ->
                if (dirtyId == courseId || dirtyId == 0L) {
                    Log.d("CourseDetailFragment", "courseDetailRefresh received for courseId=$dirtyId, reloading")
                    cachedTopicsContainer = null
                    cachedCourseData = null
                    courseDataLoadTime = 0L
                    loadCourseDetails()
                }
            }
        }

        if (courseId != -1L) {
            loadCourseDetails()
            checkEnrollmentAccess()
        } else {
            showSafeToast("Error: ID de curso inválido")
        }
    }
    private fun checkEnrollmentAccess() {
        // Only role 1 students need enrollment checks
        if (sessionManager.hasRole(2) || sessionManager.hasRole(3)) return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getEnrollmentStatus(courseId)
                }
                if (result is ApiResult.Success) {
                    val status = result.data.get("enrollmentStatus")?.asString
                    updateEnrollmentBanner(status)
                }
            } catch (e: Exception) {
                // If we can't check, assume needs enrollment
                updateEnrollmentBanner(null)
            }
        }
    }

    private fun updateEnrollmentBanner(status: String?) {
        val banner = view?.findViewById<LinearLayout>(R.id.enrollmentBanner) ?: return
        val titleTv = view?.findViewById<TextView>(R.id.enrollmentBannerTitle) ?: return
        val msgTv = view?.findViewById<TextView>(R.id.enrollmentBannerMessage) ?: return
        val requestBtn = view?.findViewById<Button>(R.id.requestEnrollmentButton) ?: return
        val tabStrip = view?.findViewById<LinearLayout>(R.id.courseTabStrip)
        val progressContainer = view?.findViewById<LinearLayout>(R.id.courseProgressContainer)
        val contentSections = view?.findViewById<LinearLayout>(R.id.sectionHeadingRow)

        when (status) {
            "activo" -> {
                // User has approved access, hide banner
                banner.visibility = View.GONE
            }
            "pendiente_aprobacion" -> {
                banner.visibility = View.VISIBLE
                titleTv.text = "Solicitud Pendiente"
                msgTv.text = "Tu solicitud de acceso está siendo revisada. Te notificaremos cuando sea aprobada."
                requestBtn.visibility = View.GONE
                tabStrip?.visibility = View.GONE
                progressContainer?.visibility = View.GONE
                contentSections?.visibility = View.GONE
                view?.findViewById<LinearLayout>(R.id.topicsContainer)?.visibility = View.GONE
            }
            "rechazado" -> {
                banner.visibility = View.VISIBLE
                titleTv.text = "Acceso Rechazado"
                msgTv.text = "Tu solicitud de acceso fue rechazada. Contacta al instructor para más información."
                requestBtn.visibility = View.GONE
                tabStrip?.visibility = View.GONE
                progressContainer?.visibility = View.GONE
                contentSections?.visibility = View.GONE
                view?.findViewById<LinearLayout>(R.id.topicsContainer)?.visibility = View.GONE
            }
            else -> {
                // No enrollment record — show request button
                banner.visibility = View.VISIBLE
                titleTv.text = "Acceso Restringido"
                msgTv.text = "Este curso requiere aprobación del instructor para acceder al contenido."
                requestBtn.visibility = View.VISIBLE
                tabStrip?.visibility = View.GONE
                progressContainer?.visibility = View.GONE
                contentSections?.visibility = View.GONE
                view?.findViewById<LinearLayout>(R.id.topicsContainer)?.visibility = View.GONE

                requestBtn.setOnClickListener {
                    requestBtn.isEnabled = false
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            val res = withContext(Dispatchers.IO) {
                                BackendApiService.requestEnrollment(courseId)
                            }
                            if (res is ApiResult.Success) {
                                updateEnrollmentBanner("pendiente_aprobacion")
                                Toast.makeText(requireContext(), "Solicitud enviada", Toast.LENGTH_SHORT).show()
                            } else {
                                requestBtn.isEnabled = true
                                Toast.makeText(requireContext(), "Error al enviar solicitud", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            requestBtn.isEnabled = true
                        }
                    }
                }
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
        
        // Add/Upload Button (ic_add) only for users with role 2 or 3
        val canUploadContent = sessionManager.hasRole(3)
        val goToHomeContainer = bottomNavBinding.goToHomeButton.parent as? View
        bottomNavBinding.goToHomeButton.visibility = if (canUploadContent) View.VISIBLE else View.GONE
        goToHomeContainer?.visibility = if (canUploadContent) View.VISIBLE else View.GONE
        if (canUploadContent) {
            bottomNavBinding.goToHomeButton.setOnClickListener {
                findNavController().navigate(R.id.action_courseDetailFragment_to_contentUploadFragment)
            }
        } else {
            bottomNavBinding.goToHomeButton.setOnClickListener(null)
        }
        
        // Activity Button (ic_activity)
        bottomNavBinding.activityButton.setOnClickListener {
            findNavController().navigate(R.id.action_courseDetailFragment_to_notificacionesFragment)
        }
        
        // Profile Button (ic_profile)
        bottomNavBinding.profileNavButton.setOnClickListener {
            findNavController().navigate(R.id.action_courseDetailFragment_to_profileFragment)
        }

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
        val nextTopicNumber = getNextTopicNumber()
        val bundle = Bundle().apply {
            putLong("courseId", courseId)
            putLong("subjectId", subjectId)
            putString("courseName", courseName)
            putInt("topicNumber", nextTopicNumber)
            putLong("topicId", -1L)
            putBoolean("isTemporary", false)
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


    private fun navigateToSelectTopic(nameOfCourse: String, isCreatingTask: Boolean = false) {
        val sendCourseId = if (resolvedCourseId > 0) resolvedCourseId else courseId
        val bundle = Bundle().apply {
            putLong("courseId", sendCourseId)
            putString("courseName", nameOfCourse)
            putBoolean("isCreatingTask", isCreatingTask)
            if (subjectId > 0) putLong("subjectId", subjectId)
        }
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

    // ─────────────────────────────────────────────────────────────────────────
    // loadCourseDetails — 3-tier cache: L1 Memory → L2 Room → L3 Network
    //
    // Strategy:
    //   1. L1 ViewModel cache hit (fresh)  → render instantly, background refresh
    //   2. L1 miss → try L2 Room           → render instantly, background refresh
    //   3. L2 miss                          → show skeleton, fetch from network
    // ─────────────────────────────────────────────────────────────────────────
    private fun loadCourseDetails() {
        refreshJob?.cancel()
        isLoadingCourseDetails = true

        val noTopicsTextView = view?.findViewById<TextView>(R.id.noTopicsTextView)
        val courseTitleTextView = view?.findViewById<TextView>(R.id.courseTitleTextView)
        val noTasksTextView = view?.findViewById<TextView>(R.id.noTasksTextView)
        val paymentContainer = view?.findViewById<FrameLayout>(R.id.paymentButtonContainer)

        val cachedSnapshot = courseViewModel.getCourseDetailSnapshot(courseId)
        val canRenderInstantly = courseViewModel.canRenderCourseDetailSnapshot(courseId)

        if (cachedSnapshot != null && canRenderInstantly) {
            Log.d("CourseDetailFragment", "L1 hit — rendering instantly")
            renderSnapshot(cachedSnapshot, noTopicsTextView, noTasksTextView)
            launchNetworkRefresh(noTopicsTextView, noTasksTextView, paymentContainer, courseTitleTextView, cachedSnapshot)
            return
        }

        courseViewModel.loadLocalSnapshot(courseId) { roomSnapshot ->
            if (roomSnapshot != null && roomSnapshot.topics.isNotEmpty()) {
                Log.d("CourseDetailFragment", "L2 Room hit — rendering from disk cache")
                renderSnapshot(roomSnapshot, noTopicsTextView, noTasksTextView)
                launchNetworkRefresh(noTopicsTextView, noTasksTextView, paymentContainer, courseTitleTextView, roomSnapshot)
            } else {
                Log.d("CourseDetailFragment", "L2 miss — loading from network with skeleton")
                topicsContainer.removeAllViews()
                startSkeletonAnimation()
                launchNetworkRefresh(noTopicsTextView, noTasksTextView, paymentContainer, courseTitleTextView, null)
            }
        }
    }

    private fun launchNetworkRefresh(
        noTopicsTextView: TextView?,
        noTasksTextView: TextView?,
        paymentContainer: FrameLayout?,
        courseTitleTextView: TextView?,
        previousSnapshot: CourseDetailSnapshot?
    ) {
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val latestSnapshot = withContext(Dispatchers.IO) { resolveCourseSnapshot(courseId) }
                    ?: throw IllegalStateException("No se pudo resolver el detalle del curso")

                courseViewModel.setCourseDetailSnapshot(latestSnapshot)

                val shouldRerender = previousSnapshot == null || snapshotChanged(previousSnapshot, latestSnapshot)
                if (shouldRerender) {
                    renderSnapshot(latestSnapshot, noTopicsTextView, noTasksTextView)
                } else {
                    latestSnapshot.course?.let { renderCourseMetadata(it) }
                    refreshTabBadges()
                }

                stopSkeletonAnimation()
                animateCourseTitleEntrance()

                val resolvedCourse = latestSnapshot.course
                if (resolvedCourse != null) {
                    creatorUserId = resolvedCourse.creatorUserId
                    isCurrentUserCreator = sessionManager.getUserId() == creatorUserId
                    isCurrentUserCreator = sessionManager.hasRole(3)
                    hasEditAccess = isCurrentUserCreator
                    paymentContainer?.visibility = View.GONE

                    applyEditAccessVisibility()

                    if (courseActionBar.visibility == View.VISIBLE) {
                        animateViewIfVisible(courseActionBar, 200)
                    } else {
                        courseActionBar.alpha = 0f
                        courseActionBar.translationY = resources.getDimensionPixelSize(R.dimen.edit_button_enter_offset).toFloat()
                    }

                    courseCreatorUsername = withContext(Dispatchers.IO) {
                        (BackendApiService.getUserById(creatorUserId) as? ApiResult.Success)?.data?.usuario
                    }

                    if (!isCurrentUserCreator && currentUsername != null) {
                        val currentUserId = sessionManager.getUserId()
                        launch {
                            initializeAndLoadCourseProgress(latestSnapshot.effectiveCourseId, currentUsername, currentUserId, false)
                        }
                        launch {
                            recalculateStudentProgressOnEntry(latestSnapshot.effectiveCourseId)
                        }
                    }
                } else {
                    courseTitleTextView?.text = courseName.ifBlank { "Curso sin título" }
                    stopSkeletonAnimation()
                }
            } catch (e: CancellationException) {
                Log.d("CourseDetailFragment", "Network refresh canceled (expected)")
                throw e
            } catch (e: Exception) {
                stopSkeletonAnimation()
                Log.e("CourseDetailFragment", "Error loading course details", e)
                if (previousSnapshot == null) {
                    noTopicsTextView?.text = "Error al cargar datos."
                    noTopicsTextView?.visibility = View.VISIBLE
                    noTopicsTextView?.alpha = 0f
                    animateViewIfVisible(noTopicsTextView, 320)
                    noTasksTextView?.visibility = View.GONE
                    topicsContainer.removeAllViews()
                    topicsContainer.visibility = View.VISIBLE
                }
            } finally {
                isLoadingCourseDetails = false
            }
        }
    }

    /**
     * Renders the sorted topics into [topicsContainer].
     * This is the Single Render Path used by both the cache-first and network paths.
     * SRP: responsible only for populating the UI from already-fetched data.
     */
    private fun renderTopicData(
        sortedTopics: List<Topic>,
        contentByTopic: Map<Long, List<ContentItem>>,
        tasksByTopic: Map<Long, List<Task>>,
        noTopicsTextView: TextView?,
        noTasksTextView: TextView?
    ) {
        topicsContainer.removeAllViews()
        noTopicsTextView?.visibility = View.GONE
        noTasksTextView?.visibility = View.GONE

        if (sortedTopics.isEmpty()) {
            noTopicsTextView?.text = "Este curso aún no tiene temas."
            noTopicsTextView?.visibility = View.VISIBLE
            noTopicsTextView?.alpha = 0f
            topicsContainer.visibility = View.VISIBLE
            animateViewIfVisible(noTopicsTextView, 320)
            animateViewIfVisible(topicsContainer, 300)
            return
        }

        for (topic in sortedTopics) {
            val contentForTopic = contentByTopic[topic.id] ?: emptyList()
            val tasksForTopic = tasksByTopic[topic.id] ?: emptyList()
            addTopicView(topic, contentForTopic, tasksForTopic)
        }

        topicsContainer.visibility = View.VISIBLE
        animateViewIfVisible(topicsContainer, 300)

        batchCheckSubmissions(tasksByTopic)
    }

    private fun batchCheckSubmissions(tasksByTopic: Map<Long, List<Task>>) {
        if (isCurrentUserCreator) return
        val userId = sessionManager.getUserId()
        if (userId <= 0L) return
        val effectiveId = if (resolvedCourseId > 0) resolvedCourseId else courseId
        if (effectiveId <= 0) return

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val allSubmissions = withContext(Dispatchers.IO) {
                    (BackendApiService.getSubmissionsByCourse(effectiveId) as? ApiResult.Success)?.data.orEmpty()
                }
                val mySubmissions = allSubmissions.filter { it.studentId == userId }.associateBy { it.taskId }

                for (i in 0 until topicsContainer.childCount) {
                    val topicView = topicsContainer.getChildAt(i) ?: continue
                    val tasksDetailContainer = topicView.findViewById<LinearLayout>(R.id.tasksDetailContainer) ?: continue
                    for (j in 0 until tasksDetailContainer.childCount) {
                        val taskCardView = tasksDetailContainer.getChildAt(j) ?: continue
                        val badgeChip = taskCardView.findViewById<TextView>(R.id.taskBadgeChip) ?: continue
                        val gradeStatus = taskCardView.findViewById<TextView>(R.id.gradeStatusTextView)
                        val taskIdTag = taskCardView.getTag(R.id.taskNameTextView) as? Long ?: continue
                        val submission = mySubmissions[taskIdTag]
                        if (submission != null) {
                            if (submission.grade != null) {
                                gradeStatus?.text = "${submission.grade}/10"
                                gradeStatus?.setTextColor(0xFF10B981.toInt())
                                gradeStatus?.visibility = View.VISIBLE
                                badgeChip.text = "Completada"
                                badgeChip.setTextColor(0xFF10B981.toInt())
                                badgeChip.setBackgroundResource(R.drawable.bg_status_completed)
                            } else {
                                gradeStatus?.text = "Entregada"
                                gradeStatus?.setTextColor(0xFF5B8DEF.toInt())
                                gradeStatus?.visibility = View.VISIBLE
                                badgeChip.text = "En proceso"
                                badgeChip.setTextColor(0xFF5B8DEF.toInt())
                                badgeChip.setBackgroundResource(R.drawable.bg_status_in_progress)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("CourseDetailFragment", "Batch submission check failed", e)
            }
        }
    }

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

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (isSubscribed) {
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

    private fun refreshTabBadges() {
        if (cachedTopicsCount >= 0) {
            tabDocumentosCount?.text = cachedTopicsCount.toString()
            tabDocumentosCount?.visibility = View.VISIBLE
        }
        if (cachedTasksCount >= 0) {
            tabTareasCount?.text = cachedTasksCount.toString()
            tabTareasCount?.visibility = View.VISIBLE
        }
    }

    // Add this method to update tab visual selection
    private fun updateTabSelection() {
        val isDocs = currentTab == "documentos"
        tabDocumentos.isSelected = isDocs
        tabTareas.isSelected = !isDocs
        // Update text colors on inner labels: active = white (or dark on cyan), inactive = gray
        tabDocumentosLabel?.setTextColor(if (isDocs) 0xFF080C1A.toInt() else 0xFF8B8FA8.toInt())
        tabTareasLabel?.setTextColor(if (!isDocs) 0xFFFFFFFF.toInt() else 0xFF8B8FA8.toInt())
        refreshTabBadges()
        // Update section heading
        val headingTitle = view?.findViewById<TextView>(R.id.sectionHeadingTitle)
        val headingSubtitle = view?.findViewById<TextView>(R.id.sectionHeadingSubtitle)
        if (isDocs) {
            headingTitle?.text = "Contenido del Tema"
            headingSubtitle?.visibility = View.GONE
        } else {
            headingTitle?.text = "Tareas"
            headingSubtitle?.visibility = View.GONE
        }
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
        val noTasksTextView = view?.findViewById<TextView>(R.id.noTasksTextView)
        var visibleTopicsInTasksTab = 0

        for (i in 0 until topicsContainer.childCount) {
            val topicView = topicsContainer.getChildAt(i)
            val taskCount = topicView.tag as? Int ?: continue
            val contentContainer = topicView.findViewById<LinearLayout>(R.id.topicContentContainer)
            val tasksContainer = topicView.findViewById<LinearLayout>(R.id.tasksDetailContainer)
            val metaChip = topicView.findViewById<TextView>(R.id.topicMetaChip)

            when (currentTab) {
                "documentos" -> {
                    topicView.visibility = View.VISIBLE
                    contentContainer?.visibility = View.VISIBLE
                    tasksContainer?.visibility = View.GONE
                    metaChip?.visibility = View.GONE
                }
                "tareas" -> {
                    if (taskCount == 0) {
                        topicView.visibility = View.GONE
                    } else {
                        topicView.visibility = View.VISIBLE
                        contentContainer?.visibility = View.GONE
                        tasksContainer?.visibility = View.VISIBLE
                        metaChip?.text = "$taskCount ${if (taskCount == 1) "tarea" else "tareas"}"
                        metaChip?.visibility = View.VISIBLE
                        visibleTopicsInTasksTab++
                    }
                }
            }
        }

        if (currentTab == "tareas") {
            noTasksTextView?.visibility = if (visibleTopicsInTasksTab == 0) View.VISIBLE else View.GONE
        } else {
            noTasksTextView?.visibility = View.GONE
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
        topicView.tag = tasks.size

        val metaChipOptimized = topicView.findViewById<TextView>(R.id.topicMetaChip)
        if (currentTab == "tareas") {
            if (tasks.isEmpty()) {
                topicView.visibility = View.GONE
            } else {
                metaChipOptimized?.text = "${tasks.size} ${if (tasks.size == 1) "tarea" else "tareas"}"
                metaChipOptimized?.visibility = View.VISIBLE
            }
        } else {
            metaChipOptimized?.visibility = View.GONE
        }

        // Prepare labels and initial collapsed state
        val chevron = topicView.findViewById<ImageView>(R.id.topicChevron)
        val videosLabel = topicView.findViewById<TextView>(R.id.videosSectionLabel)
        val filesLabel = topicView.findViewById<TextView>(R.id.filesSectionLabel)
        // Determine if there is any video/content to show
        val hasVideos = contentItems.any { it.contentType.equals("video", ignoreCase = true) }
        val hasFiles = contentItems.isNotEmpty()
        videosLabel.visibility = if (hasVideos) View.GONE else View.GONE // keep labels hidden by default, will show when expanded
        filesLabel.visibility = View.GONE

        // Prepare content but keep collapsed by default
        setupContentContainerFast(topicContentContainer, contentItems)
        setupTasksContainerFast(tasksContainer, tasks, topic)
        topicContentContainer.visibility = View.GONE
        tasksContainer.visibility = View.GONE

        // Toggle expansion with animation when header is clicked
        val headerRow = topicView.findViewById<LinearLayout>(R.id.topicHeaderRow)
        headerRow.setOnClickListener {
            val parentGroup = topicView as ViewGroup
            TransitionManager.beginDelayedTransition(parentGroup, AutoTransition())
            val isExpanded = topicContentContainer.visibility == View.VISIBLE || tasksContainer.visibility == View.VISIBLE
            if (isExpanded) {
                topicContentContainer.visibility = View.GONE
                tasksContainer.visibility = View.GONE
                videosLabel.visibility = View.GONE
                filesLabel.visibility = View.GONE
                chevron.animate().rotation(0f).setDuration(200).start()
            } else {
                if (currentTab == "documentos") {
                    topicContentContainer.visibility = View.VISIBLE
                    videosLabel.visibility = if (hasVideos) View.VISIBLE else View.GONE
                    filesLabel.visibility = if (hasFiles) View.VISIBLE else View.GONE
                    tasksContainer.visibility = View.GONE
                } else {
                    tasksContainer.visibility = View.VISIBLE
                    videosLabel.visibility = View.GONE
                    filesLabel.visibility = View.GONE
                    topicContentContainer.visibility = View.GONE
                }
                chevron.animate().rotation(180f).setDuration(200).start()
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
                iconView?.setImageResource(R.drawable.ic_play_circle)
                iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFF9B7EFF.toInt())
                typeView?.text = "VIDEO"
            }
            "pdf" -> {
                iconView?.setImageResource(R.drawable.ic_document)
                iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt())
                typeView?.text = "PDF"
            }
            "image" -> {
                iconView?.setImageResource(R.drawable.ic_image)
                iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFF10B981.toInt())
                typeView?.text = "IMAGEN"
            }
            "code" -> {
                iconView?.setImageResource(R.drawable.ic_code)
                iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFF00D4FF.toInt())
                typeView?.text = "CÓDIGO"
            }
            else -> {
                iconView?.setImageResource(R.drawable.ic_attach_file)
                iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFF9B7EFF.toInt())
                typeView?.text = "ARCHIVO"
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
        val dueSuffix = formatTaskDueDateForChip(task.dueDate)?.let { "  |  Limite: $it" } ?: ""
        val taskView = TextView(context).apply {
            text = "📋 ${task.name ?: "Tarea sin titulo"}$dueSuffix"
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

        // Content section header (text removed)
        val contentHeader = TextView(context).apply {
            text = ""
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(4, 12, 4, 6)
            setTextColor(0xFF9B7EFF.toInt())
            textSize = 11f
            letterSpacing = 0.08f
        }

        // Tasks section header
        val tasksHeader = TextView(context).apply {
            text = "TAREAS DEL TEMA"
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(4, 12, 4, 6)
            setTextColor(0xFF00D4FF.toInt())
            textSize = 11f
            letterSpacing = 0.08f
        }

        topicTitleTextView.text = topic.name
        topicView.tag = tasks.size
        if (topic.description.isNotEmpty()) {
            topicDescriptionTextView.text = topic.description
            topicDescriptionTextView.visibility = View.VISIBLE
        } else {
            topicDescriptionTextView.visibility = View.GONE
        }

        val metaChipFull = topicView.findViewById<TextView>(R.id.topicMetaChip)
        if (currentTab == "tareas") {
            if (tasks.isEmpty()) {
                topicView.visibility = View.GONE
            } else {
                metaChipFull?.text = "${tasks.size} ${if (tasks.size == 1) "tarea" else "tareas"}"
                metaChipFull?.visibility = View.VISIBLE
            }
        } else {
            metaChipFull?.visibility = View.GONE
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

        // --- Build content and tasks containers but start collapsed; expansion toggles visibility with animation ---
        // Build documents content (topic-level only)
        topicContentContainer.removeAllViews()
        topicContentContainer.addView(contentHeader)
        val topicOnlyContent = contentItems.filter { it.taskId == null || it.taskId == 0L }
        val sortedContent = topicOnlyContent.sortedBy { it.orderIndex }
        if (sortedContent.isNotEmpty()) {
            for (item in sortedContent) {
                addContentView(item, topicContentContainer)
            }
        } else {
            val noContentMsg = TextView(context).apply {
                text = "Sin contenido para este tema"
                setTextColor(resources.getColor(android.R.color.darker_gray, null))
                setPadding(0, 8, 0, 8)
            }
            topicContentContainer.addView(noContentMsg)
        }

        // Build tasks container
        tasksContainer.removeAllViews()
        tasksContainer.addView(tasksHeader)
        val sortedTasks = tasks.sortedBy { it.orderIndex }
        if (sortedTasks.isNotEmpty()) {
            for (task in sortedTasks) {
                val taskContent = contentItems.filter { it.taskId == task.id }
                addTaskView(task, tasksContainer, taskContent)
            }
        } else {
            val noTasksMsg = TextView(context).apply { text = "Sin tareas para este tema" }
            tasksContainer.addView(noTasksMsg)
        }
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

        // Prepare chevron and section labels
        val chevron = topicView.findViewById<ImageView>(R.id.topicChevron)
        val videosLabel = topicView.findViewById<TextView>(R.id.videosSectionLabel)
        val filesLabel = topicView.findViewById<TextView>(R.id.filesSectionLabel)
        val hasVideos = contentItems.any { it.contentType.equals("video", ignoreCase = true) }
        val hasFiles = contentItems.isNotEmpty()
        videosLabel.visibility = View.GONE
        filesLabel.visibility = View.GONE

        // Start collapsed
        topicContentContainer.visibility = View.GONE
        tasksContainer.visibility = View.GONE

        // Header click toggles expansion with animated transition
        val headerRow = topicView.findViewById<LinearLayout>(R.id.topicHeaderRow)
        headerRow.setOnClickListener {
            val parentGroup = topicView as ViewGroup
            TransitionManager.beginDelayedTransition(parentGroup, AutoTransition())
            val isExpanded = topicContentContainer.visibility == View.VISIBLE || tasksContainer.visibility == View.VISIBLE
            if (isExpanded) {
                topicContentContainer.visibility = View.GONE
                tasksContainer.visibility = View.GONE
                videosLabel.visibility = View.GONE
                filesLabel.visibility = View.GONE
                chevron.animate().rotation(0f).setDuration(200).start()
            } else {
                if (currentTab == "documentos") {
                    topicContentContainer.visibility = View.VISIBLE
                    videosLabel.visibility = if (hasVideos) View.VISIBLE else View.GONE
                    filesLabel.visibility = if (hasFiles) View.VISIBLE else View.GONE
                    tasksContainer.visibility = View.GONE
                } else {
                    tasksContainer.visibility = View.VISIBLE
                    topicContentContainer.visibility = View.GONE
                    videosLabel.visibility = View.GONE
                    filesLabel.visibility = View.GONE
                }
                chevron.animate().rotation(180f).setDuration(200).start()
            }
        }

        topicsContainer.addView(topicView)

        // No separator between topics — card margins handle spacing
        val separator = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0
            )
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

    private fun addTaskView(task: Task, container: LinearLayout, preloadedContent: List<ContentItem> = emptyList()) {
        val inflater = LayoutInflater.from(context)
        val taskView = inflater.inflate(R.layout.item_course_task_detail, container, false)
        taskView.setTag(R.id.taskNameTextView, task.id)

        val taskNameTextView = taskView.findViewById<TextView>(R.id.taskNameTextView)
        val taskDescriptionTextView = taskView.findViewById<TextView>(R.id.taskDescriptionTextView)
        val editTaskButton = taskView.findViewById<ImageButton>(R.id.editTaskButton)
        val deleteTaskButton = taskView.findViewById<ImageButton>(R.id.deleteTaskButton)
        val submitTaskButton = taskView.findViewById<Button>(R.id.uploadSubmissionButton)
        val gradeStatusTextView = taskView.findViewById<TextView>(R.id.gradeStatusTextView)

        // taskIndicator removed — badge chip handles status colors

    taskNameTextView.text = if (!task.name.isNullOrBlank()) task.name else "(Sin título)"
        if (!task.description.isNullOrBlank()) {
            taskDescriptionTextView.text = task.description
            taskDescriptionTextView.visibility = View.VISIBLE
        } else {
            taskDescriptionTextView.visibility = View.GONE
        }

        // Set initial badge chip to "Pendiente" (updated async after submission check)
        val taskBadgeChip = taskView.findViewById<TextView>(R.id.taskBadgeChip)
        taskBadgeChip?.text = "Pendiente"
        taskBadgeChip?.setTextColor(0xFFF59E0B.toInt())
        taskBadgeChip?.setBackgroundResource(R.drawable.bg_status_pending)

        // Set due date chip
        val taskDueChip = taskView.findViewById<TextView>(R.id.taskDueChip)
        val formattedDue = formatTaskDueDateForChip(task.dueDate)
        if (!formattedDue.isNullOrBlank()) {
            taskDueChip?.text = formattedDue
            taskDueChip?.visibility = View.VISIBLE
        } else {
            taskDueChip?.visibility = View.GONE
        }

        val taskContentContainer = taskView.findViewById<LinearLayout>(R.id.taskContentContainer)
        val taskContentLabel = taskView.findViewById<TextView>(R.id.taskContentLabel)
        val contentSeparator = taskView.findViewById<View>(R.id.contentSeparator)
        taskContentContainer?.removeAllViews()

        contentSeparator?.visibility = View.VISIBLE
        taskContentLabel?.visibility = View.VISIBLE
        taskContentContainer?.visibility = View.VISIBLE

        if (preloadedContent.isNotEmpty()) {
            for (contentItem in preloadedContent) {
                val ctx = context ?: break
                val contentItemView = LayoutInflater.from(ctx).inflate(
                    R.layout.item_content_mini, taskContentContainer, false
                )
                val iconView = contentItemView.findViewById<ImageView>(R.id.contentIconView)
                val nameView = contentItemView.findViewById<TextView>(R.id.contentNameView)
                val typeView = contentItemView.findViewById<TextView>(R.id.contentTypeView)
                val deleteButton = contentItemView.findViewById<ImageButton>(R.id.deleteContentButton)

                nameView?.text = if (isRemoteUrl(contentItem.uriString)) "☁️ ${contentItem.name ?: "Archivo adjunto"}" else contentItem.name ?: "Archivo adjunto"

                when (contentItem.contentType.lowercase()) {
                    "video" -> {
                        iconView?.setImageResource(R.drawable.ic_play_circle)
                        iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFF9B7EFF.toInt())
                        typeView?.text = "VIDEO"
                    }
                    "pdf" -> {
                        iconView?.setImageResource(R.drawable.ic_document)
                        iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFFEF4444.toInt())
                        typeView?.text = "PDF"
                    }
                    "image" -> {
                        iconView?.setImageResource(R.drawable.ic_image)
                        iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFF10B981.toInt())
                        typeView?.text = "IMAGEN"
                    }
                    "code" -> {
                        iconView?.setImageResource(R.drawable.ic_code)
                        iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFF00D4FF.toInt())
                        typeView?.text = "CÓDIGO"
                    }
                    else -> {
                        iconView?.setImageResource(R.drawable.ic_attach_file)
                        iconView?.imageTintList = android.content.res.ColorStateList.valueOf(0xFF9B7EFF.toInt())
                        typeView?.text = "ARCHIVO"
                    }
                }

                deleteButton?.visibility = if (isCurrentUserCreator) View.VISIBLE else View.GONE
                deleteButton?.setOnClickListener { showDeleteContentConfirmation(contentItem, taskContentContainer!!, contentItemView) }
                contentItemView.setOnClickListener { openContent(contentItem) }
                taskContentContainer?.addView(contentItemView)
            }
        } else {
            context?.let { ctx ->
                taskContentContainer?.addView(TextView(ctx).apply {
                    text = "No hay archivos adjuntos"
                    setTextColor(ctx.resources.getColor(android.R.color.darker_gray, null))
                    setPadding(16, 16, 16, 16)
                    textSize = 13f
                })
            }
        }

        editTaskButton?.visibility = if (hasEditAccess) View.VISIBLE else View.GONE
        deleteTaskButton?.visibility = if (hasEditAccess) View.VISIBLE else View.GONE

        editTaskButton?.setOnClickListener {
            navigateToEditTask(task.id, task.topicId)
        }

        deleteTaskButton?.setOnClickListener {
            showDeleteTaskConfirmation(task)
        }

        val submissionBundle = Bundle().apply {
            putLong("taskId", task.id)
            putString("taskName", task.name)
            putString("courseCreatorUsername", courseCreatorUsername ?: "")
            putBoolean("hasEditAccess", hasEditAccess)
        }
        if (hasEditAccess) {
            submitTaskButton.text = "Ver Entregas"
            submitTaskButton.visibility = View.VISIBLE
            gradeStatusTextView?.visibility = View.GONE
        } else {
            submitTaskButton.text = "Subir Tarea"
            submitTaskButton.visibility = View.VISIBLE
        }
        submitTaskButton.setOnClickListener {
            findNavController().navigate(R.id.action_courseDetailFragment_to_taskSubmissionFragment, submissionBundle)
        }

        container.addView(taskView)
    }

    private fun formatTaskDueDateForChip(rawDueDate: String?): String? {
        if (rawDueDate.isNullOrBlank()) return null

        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        )

        var parsedDate: Date? = null
        for (fmt in formats) {
            try {
                parsedDate = fmt.parse(rawDueDate)
                if (parsedDate != null) break
            } catch (_: Exception) {
                // Keep trying supported formats.
            }
        }

        if (parsedDate == null) {
            return rawDueDate.take(16)
        }

        return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(parsedDate)
    }

    // Refresh topics and re-render UI from backend for the current course
    private fun refreshTopicsFromBackend(showSkeleton: Boolean = false) {
        if (courseId == -1L) return

        refreshJob?.cancel()
        isLoadingCourseDetails = true
        courseViewModel.markCourseDetailDirty(courseId)

        if (showSkeleton) {
            startSkeletonAnimation()
        }

        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) { resolveCourseSnapshot(courseId) }
                    ?: throw IllegalStateException("No se pudo refrescar el detalle del curso")
                courseViewModel.setCourseDetailSnapshot(snapshot)

                val noTopicsTextView = view?.findViewById<TextView>(R.id.noTopicsTextView)
                val noTasksTextView = view?.findViewById<TextView>(R.id.noTasksTextView)
                renderSnapshot(snapshot, noTopicsTextView, noTasksTextView)

                recalculateStudentProgress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("CourseDetailFragment", "Error refreshing topics", e)
            } finally {
                isLoadingCourseDetails = false
                if (showSkeleton) {
                    stopSkeletonAnimation()
                }
                Log.d("CourseDetailFragment", "✅ Refresh complete")
            }
        }
    }

    // Add this helper method to check student submission status
    private fun checkStudentSubmission(taskId: Long, gradeStatusTextView: TextView?) {
        if (gradeStatusTextView == null) return

    val username = sessionManager.getUsername()
    val userId = sessionManager.getUserId()
    if (userId <= 0L) return

    viewLifecycleOwner.lifecycleScope.launch {
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

                // Find badge chip in the parent task card
                val badgeChip = (gradeStatusTextView?.parent?.parent as? android.view.ViewGroup)
                    ?.findViewById<TextView>(R.id.taskBadgeChip)

                if (submission != null) {
                    if (submission.grade != null) {
                        gradeStatusTextView.text = "${submission.grade}/10"
                        gradeStatusTextView.setTextColor(0xFF10B981.toInt())
                        gradeStatusTextView.visibility = View.VISIBLE
                        badgeChip?.text = "Completada"
                        badgeChip?.setTextColor(0xFF10B981.toInt())
                        badgeChip?.setBackgroundResource(R.drawable.bg_status_completed)
                    } else {
                        gradeStatusTextView.text = "Entregada"
                        gradeStatusTextView.setTextColor(0xFF5B8DEF.toInt())
                        gradeStatusTextView.visibility = View.VISIBLE
                        badgeChip?.text = "En proceso"
                        badgeChip?.setTextColor(0xFF5B8DEF.toInt())
                        badgeChip?.setBackgroundResource(R.drawable.bg_status_in_progress)
                    }
                } else {
                    gradeStatusTextView.visibility = View.GONE
                    badgeChip?.text = "Pendiente"
                    badgeChip?.setTextColor(0xFFF59E0B.toInt())
                    badgeChip?.setBackgroundResource(R.drawable.bg_status_pending)
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
                    Glide.with(this@CourseDetailFragment)
                        .load(uri)
                        .centerCrop()
                        .placeholder(R.drawable.content_thumbnail_placeholder)
                        .error(R.drawable.content_thumbnail_placeholder)
                        .into(imageView)
                }
                "image" -> {
                    // For images, load the image directly
                    Glide.with(this@CourseDetailFragment)
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
        if (resolvedCourseId <= 0) return
        
        viewLifecycleOwner.lifecycleScope.launch {
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
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                Log.d("CourseDetailFragment", "🔄 Recalculating progress on entry: user=$username, course=$courseIdToUse")
                
                withContext(Dispatchers.IO) {
                    try {
                        val allTasks = BackendApiService.getTasksByCourse(courseIdToUse)
                            .getOrNull()
                            .orEmpty()
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



    // iPhone-style entrance animation for course title and description
    private fun animateCourseTitleEntrance() {
        val root = view ?: return
        val titleContainer = root.findViewById<View>(R.id.courseTitleContainer)
        val metaLabel = root.findViewById<TextView>(R.id.courseMetaLabel)
        val accentDivider = root.findViewById<View>(R.id.courseAccentDivider)

        titleContainer?.animate()?.apply {
            alpha(1f)
            translationY(0f)
            duration = 300
            interpolator = android.view.animation.DecelerateInterpolator(2f)
        }?.start()

        metaLabel?.animate()?.apply {
            startDelay = 60
            alpha(1f)
            translationY(0f)
            duration = 250
            interpolator = android.view.animation.DecelerateInterpolator(2f)
        }?.start()

        courseTitleTextView.animate()
            .setStartDelay(80)
            .alpha(1f)
            .translationY(0f)
            .setDuration(280)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()

        courseDescriptionTextView.animate()
            .setStartDelay(120)
            .alpha(1f)
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator(2f))
            .start()

        accentDivider?.animate()?.apply {
            startDelay = 160
            alpha(0.6f)
            translationY(0f)
            scaleX(1f)
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator(2f)
        }?.start()

        root.postDelayed({ animateContentSections() }, 180)
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
        animateViewIfVisible(root.findViewById(R.id.courseProgressContainer), 40)
        animateViewIfVisible(root.findViewById(R.id.paymentButtonContainer), 60)
        animateViewIfVisible(root.findViewById(R.id.courseTabStrip), 80)
        animateViewIfVisible(root.findViewById(R.id.sectionHeadingRow), 100)
        animateViewIfVisible(topicsContainer, 120)
        animateViewIfVisible(root.findViewById(R.id.noTopicsTextView), 120)
        animateViewIfVisible(root.findViewById(R.id.noTasksTextView), 120)
        animateViewIfVisible(courseActionBar, 140)
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
            .setDuration(280)
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
                
                AppCache.invalidateCourses()
                // Update UI
                updatePriceDisplay(newPrice)
                courseViewModel.updateCachedCourse(courseId, updatedCourse)
                courseViewModel.markCourseDetailDirty(courseId)
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
            putLong("subjectId", subjectId)
            putString("courseName", courseName)
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
        if (!hasEditAccess) {
            Toast.makeText(requireContext(), "Solo el administrador puede eliminar temas", Toast.LENGTH_SHORT).show()
            return
        }

        val noTopicsTextView = view?.findViewById<TextView>(R.id.noTopicsTextView)
        val noTasksTextView = view?.findViewById<TextView>(R.id.noTasksTextView)
        courseViewModel.removeTopicFromSnapshot(courseId, topic.id)?.let { snapshot ->
            renderSnapshot(snapshot, noTopicsTextView, noTasksTextView)
        }
        Toast.makeText(requireContext(), "Tema eliminado exitosamente", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                courseViewModel.repository.deleteTopicRemote(topic.id)
            }
            if (!success) {
                Log.w("CourseDetailFragment", "Server delete failed for topic ${topic.id}, refreshing")
                courseViewModel.markCourseDetailDirty(courseId)
                refreshTopicsFromBackend(showSkeleton = false)
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
        if (!hasEditAccess) {
            Toast.makeText(requireContext(), "Solo el administrador puede eliminar tareas", Toast.LENGTH_SHORT).show()
            return
        }

        val noTopicsTextView = view?.findViewById<TextView>(R.id.noTopicsTextView)
        val noTasksTextView = view?.findViewById<TextView>(R.id.noTasksTextView)
        courseViewModel.removeTaskFromSnapshot(courseId, task.id)?.let { snapshot ->
            renderSnapshot(snapshot, noTopicsTextView, noTasksTextView)
        }
        Toast.makeText(requireContext(), "Tarea eliminada exitosamente", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                courseViewModel.repository.deleteTaskRemote(task.id)
            }
            if (!success) {
                Log.w("CourseDetailFragment", "Server delete failed for task ${task.id}, refreshing")
                courseViewModel.markCourseDetailDirty(courseId)
                refreshTopicsFromBackend(showSkeleton = false)
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
        if (!hasEditAccess) {
            Toast.makeText(requireContext(), "Solo el administrador puede eliminar contenido", Toast.LENGTH_SHORT).show()
            return
        }

        container.removeView(contentView)
        courseViewModel.removeContentFromSnapshot(courseId, contentItem.id)
        Toast.makeText(requireContext(), "Contenido eliminado exitosamente", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            val success = withContext(Dispatchers.IO) {
                courseViewModel.repository.deleteContentRemote(contentItem.id)
            }
            if (!success) {
                Log.w("CourseDetailFragment", "Server delete failed for content ${contentItem.id}, refreshing")
                courseViewModel.markCourseDetailDirty(courseId)
                refreshTopicsFromBackend(showSkeleton = false)
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