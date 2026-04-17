package com.example.tareamov.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.NestedScrollView
import com.example.tareamov.R
import com.example.tareamov.adapter.SubjectAdapter
import com.example.tareamov.adapter.SubjectDragCallback
import com.example.tareamov.adapter.SubjectWithStats
import com.example.tareamov.data.entity.Subject
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.util.AppCache
import com.example.tareamov.util.GradeReportHelper
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.getEnrollmentStatusOrNull
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import androidx.lifecycle.ViewModelProvider
import com.example.tareamov.viewmodel.CourseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect

class SubjectsListFragment : Fragment() {

    private var courseId: Long = -1
    private var courseName: String = ""
    private var isCreator: Boolean = false
    private var hasAccess: Boolean = false
    private var isCollaboratorOnly: Boolean = false
    private var openBulletinOnLoad: Boolean = false
    private var openAllBulletinsOnLoad: Boolean = false
    private lateinit var subjectAdapter: SubjectAdapter
    private var allSubjects: List<Subject> = emptyList()
    private var subjectStats: Map<Long, SubjectWithStats> = emptyMap()
    private var subjectsDataObserverAttached: Boolean = false
    private var subjectsDataBound: Boolean = false
    private var subjectBlocks: Map<Long, String> = emptyMap()

    // Drag-to-reorder
    private var itemTouchHelper: ItemTouchHelper? = null
    private var dragCallback: SubjectDragCallback? = null
    private var isDragMode: Boolean = false

    // Safe Toast helper - prevents crash when fragment is detached
    private fun showSafeToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val ctx = context ?: return
        try { Toast.makeText(ctx, message, duration).show() } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong("courseId", -1)
            courseName = it.getString("courseName", "")
            openBulletinOnLoad = it.getBoolean("openBulletin", false)
            openAllBulletinsOnLoad = it.getBoolean("openAllBulletins", false)
        }
        isCreator = SessionManager.getInstance(requireContext()).run { hasRole(3) || hasRole(4) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_subjects_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSubjectsView(view)

        if (openBulletinOnLoad) {
            openBulletinOnLoad = false
            view.post { showBulletinBottomSheet() }
        }

        val sessionManager = SessionManager.getInstance(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            val collaboratorAccess = resolveCollaboratorCourseAccess(sessionManager)

            if (!(sessionManager.hasRole(3) || sessionManager.hasRole(4)) && !collaboratorAccess) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        BackendApiService.getEnrollmentStatus(courseId)
                    }
                    if (!isAdded) return@launch
                    if (result is ApiResult.Success) {
                        val status = result.data.getEnrollmentStatusOrNull()
                        if (status != "approved") {
                            val msg = when (status) {
                                "pending" -> "Tu solicitud de acceso está pendiente de aprobación."
                                "rejected" -> "Tu solicitud fue rechazada. Contacta al administrador."
                                else -> "No tienes acceso a este curso. Solicita acceso desde Explorar."
                            }
                            showSafeToast(msg, Toast.LENGTH_LONG)
                            if (isAdded) findNavController().popBackStack()
                            return@launch
                        }
                    } else {
                        showSafeToast("No tienes acceso a este curso. Solicita acceso desde Explorar.", Toast.LENGTH_LONG)
                        if (isAdded) findNavController().popBackStack()
                        return@launch
                    }
                } catch (e: Exception) {
                    showSafeToast("Error al verificar acceso. Intenta de nuevo.", Toast.LENGTH_SHORT)
                    if (isAdded) findNavController().popBackStack()
                    return@launch
                }
            }

            bindSubjectsData(view)
        }
    }

    private suspend fun resolveCollaboratorCourseAccess(sessionManager: SessionManager): Boolean {
        if (!sessionManager.hasRole(2) || sessionManager.hasRole(3) || sessionManager.hasRole(4)) return false
        return try {
            val result = withContext(Dispatchers.IO) {
                BackendApiService.checkCollaboratorAccess(courseId)
            }
            result is ApiResult.Success && result.data.get("hasAccess")?.asBoolean == true
        } catch (_: Exception) {
            false
        }
    }

    private fun setupSubjectsView(view: View) {
        val headerTitle = view.findViewById<TextView>(R.id.headerTitle)
        val headerSubtitle = view.findViewById<TextView>(R.id.headerSubtitle)
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        val backButtonContainer = view.findViewById<FrameLayout>(R.id.backButtonContainer)
        val recyclerView = view.findViewById<RecyclerView>(R.id.subjectsRecyclerView)
        val emptyStateContainer = view.findViewById<LinearLayout>(R.id.emptyStateContainer)
        val emptyStateAddButton = view.findViewById<MaterialButton>(R.id.emptyStateAddButton)
        val loading = view.findViewById<ProgressBar>(R.id.loadingProgressBar)
        val fabAddSubject = view.findViewById<FloatingActionButton>(R.id.fabAddSubject)
        val fabDone = view.findViewById<ExtendedFloatingActionButton>(R.id.fabDone)
        val sortButtonContainer = view.findViewById<FrameLayout>(R.id.sortButtonContainer)
        val sortButton = view.findViewById<ImageButton>(R.id.sortButton)
        val nestedScrollView = view.findViewById<NestedScrollView>(R.id.nestedScrollView)
        val searchEditText = view.findViewById<android.widget.EditText>(R.id.searchEditText)
        val gradeBadge = view.findViewById<LinearLayout>(R.id.gradeBadge)

        headerTitle.text = "Mis Materias"
        gradeBadge.visibility = View.GONE

        animateHeader(view)

        backButton.setOnClickListener {
            findNavController().navigate(R.id.action_subjectsListFragment_to_exploreFragment)
        }

        fabAddSubject.visibility = View.GONE
        emptyStateAddButton.visibility = View.GONE

        val navigateToCreate = {
            val bundle = Bundle().apply {
                putLong("courseId", courseId)
                putString("courseName", courseName)
            }
            findNavController().navigate(R.id.action_subjectsListFragment_to_subjectCreationFragment, bundle)
        }
        fabAddSubject.setOnClickListener { navigateToCreate() }
        emptyStateAddButton.setOnClickListener { navigateToCreate() }

        subjectAdapter = SubjectAdapter(requireContext(), emptyList(),
            onSubjectClick = { subject ->
                // Check if subject is blocked for this user
                val blockReason = subjectBlocks[subject.id]
                if (blockReason != null) {
                    val ctx = requireContext()
                    val dlgLayout = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass_dark)
                        val p = (20 * resources.displayMetrics.density).toInt()
                        setPadding(p, p, p, (16 * resources.displayMetrics.density).toInt())
                    }
                    val titleView = android.widget.TextView(ctx).apply {
                        text = "\uD83D\uDEAB Acceso bloqueado"
                        textSize = 18f
                        setTextColor(android.graphics.Color.WHITE)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
                    }
                    val msgView = android.widget.TextView(ctx).apply {
                        text = "Has sido bloqueado debido a: $blockReason"
                        textSize = 15f
                        setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (20 * resources.displayMetrics.density).toInt() }
                    }
                    val divider = android.view.View(ctx).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * resources.displayMetrics.density).toInt()
                        ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
                    }
                    val okBtn = android.widget.TextView(ctx).apply {
                        text = "Entendido"
                        textSize = 16f
                        setTextColor(android.graphics.Color.parseColor("#FF9F0A"))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        gravity = android.view.Gravity.CENTER
                        val pad = (12 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (4 * resources.displayMetrics.density).toInt() }
                    }
                    dlgLayout.addView(titleView)
                    dlgLayout.addView(msgView)
                    dlgLayout.addView(divider)
                    dlgLayout.addView(okBtn)
                    val blockDlg = android.app.Dialog(ctx)
                    blockDlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                    blockDlg.setContentView(dlgLayout)
                    blockDlg.window?.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    )
                    val dlgW = (resources.displayMetrics.widthPixels * 0.88).toInt()
                    blockDlg.window?.setLayout(dlgW, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
                    okBtn.setOnClickListener { blockDlg.dismiss() }
                    blockDlg.show()
                    return@SubjectAdapter
                }
                // Block collaborators from entering subjects they didn't create
                // (Backend already filters subjects; this is a safety check for cached data)
                // Removed: collaborators can now access subjects where they are subject collaborators
                val sessionManager = SessionManager.getInstance(requireContext())
                val vm = ViewModelProvider(requireActivity())[CourseViewModel::class.java]
                vm.prefetchCourseDetail(
                    courseId, subject.name,
                    sessionManager.getUserId(), isCreator
                )
                val bundle = Bundle().apply {
                    putLong("courseId", courseId)
                    putString("courseName", subject.name)
                    putLong("subjectId", subject.id)
                    putString("subjectName", subject.name)
                    putString("subjectDescription", subject.description)
                    putString("subjectThumbnailUrl", subject.thumbnailUrl)
                }
                findNavController().navigate(R.id.action_subjectsListFragment_to_courseDetailFragment, bundle)
            },
            onSubjectLongClick = { subject ->
                if (hasAccess) {
                    val sessionManager = SessionManager.getInstance(requireContext())
                    val canModifyThis = sessionManager.hasRole(3) || sessionManager.hasRole(4) || (subject.createdBy == sessionManager.getUserId())
                    if (canModifyThis) showSubjectOptions(subject)
                }
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = subjectAdapter

        // Conectar drag handle con ItemTouchHelper
        subjectAdapter.onStartDrag = { viewHolder -> itemTouchHelper?.startDrag(viewHolder) }

        // Configurar drag callback con auto-scroll sobre el NestedScrollView
        dragCallback = SubjectDragCallback(
            adapter = subjectAdapter,
            scrollContainer = nestedScrollView,
            onDragReleased = { if (isDragMode) saveSubjectOrder() }
        )
        itemTouchHelper = ItemTouchHelper(dragCallback!!).also { it.attachToRecyclerView(recyclerView) }

        // Botón ordenar en header
        sortButton?.setOnClickListener { toggleDragMode(fabAddSubject, fabDone, sortButtonContainer) }
        fabDone.setOnClickListener { toggleDragMode(fabAddSubject, fabDone, sortButtonContainer) }

        setupSearchBar(searchEditText, recyclerView, emptyStateContainer, headerSubtitle)
        checkAccessAndSetup(fabAddSubject, emptyStateAddButton, sortButtonContainer)

        // Botón de opciones (tres puntos verticales)
        val reportButtonContainer = view.findViewById<FrameLayout>(R.id.reportButtonContainer)
        val reportButton = view.findViewById<ImageButton>(R.id.reportButton)
        val reportSessionManager = SessionManager.getInstance(requireContext())
        reportButtonContainer.visibility = if (reportSessionManager.hasRole(2) || reportSessionManager.hasRole(3) || reportSessionManager.hasRole(4)) View.VISIBLE else View.GONE
        reportButton.setImageResource(R.drawable.ic_more_vert)
        // Animación de entrada con bounce
        reportButtonContainer.alpha = 0f
        reportButtonContainer.scaleX = 0.6f
        reportButtonContainer.scaleY = 0.6f
        reportButtonContainer.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(400)
            .setStartDelay(300)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
        reportButton.setOnClickListener { v ->
            val popup = android.widget.PopupMenu(requireContext(), v)
            popup.menuInflater.inflate(R.menu.menu_report_options, popup.menu)
            popup.setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_report -> { showReportBottomSheet(); true }
                    R.id.action_bulletin -> { showBulletinBottomSheet(); true }
                    else -> false
                }
            }
            popup.show()
        }
    }

    private fun bindSubjectsData(view: View) {
        subjectsDataBound = true
        val recyclerView = view.findViewById<RecyclerView>(R.id.subjectsRecyclerView)
        val emptyStateContainer = view.findViewById<LinearLayout>(R.id.emptyStateContainer)
        val loading = view.findViewById<ProgressBar>(R.id.loadingProgressBar)
        val headerSubtitle = view.findViewById<TextView>(R.id.headerSubtitle)

        // Check course deadline for non-admin users
        checkCourseDeadline(view)
        loadSubjectBlocks()

        val cached = AppCache.getSubjectsOrStale(courseId)
        if (cached != null) {
            allSubjects = cached
            showSubjects(cached, recyclerView, emptyStateContainer, headerSubtitle)
            loadSubjectStats(cached)
        } else {
            loading.visibility = View.VISIBLE
        }
        loadSubjects(recyclerView, emptyStateContainer, loading, headerSubtitle)

        // Observe reactive cache invalidation — auto-reload when subjects change on any device
        if (!subjectsDataObserverAttached) {
            subjectsDataObserverAttached = true
            viewLifecycleOwner.lifecycleScope.launch {
                AppCache.subjectsRefresh.collect {
                    loadSubjectsFromNetwork(recyclerView, emptyStateContainer, loading, headerSubtitle)
                }
            }
        }
    }

    private fun loadSubjectBlocks() {
        val sessionManager = SessionManager.getInstance(requireContext())
        if (sessionManager.hasRole(3) || sessionManager.hasRole(4)) return // Admins are not restricted
        // Use block info already annotated by backend on each subject
        val fromSubjects = mutableMapOf<Long, String>()
        for (s in allSubjects) {
            if (s.blocked) {
                fromSubjects[s.id] = s.blockReason ?: "Sin motivo especificado"
            }
        }
        if (fromSubjects.isNotEmpty()) {
            subjectBlocks = fromSubjects
            return
        }
        // Fallback: fetch blocks separately
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getMySubjectAccessBlocks(courseId)
                }
                if (result is ApiResult.Success) {
                    val blocks = mutableMapOf<Long, String>()
                    for (item in result.data) {
                        val subjectId = item.get("subject_id")?.asLong ?: continue
                        val reason = item.get("reason")?.asString ?: "Sin motivo especificado"
                        blocks[subjectId] = reason
                    }
                    subjectBlocks = blocks
                }
            } catch (_: Exception) { /* silent */ }
        }
    }

    private fun animateHeader(view: View) {
        val headerTop = view.findViewById<LinearLayout>(R.id.headerTopRow)
        val subtitleRow = view.findViewById<LinearLayout>(R.id.subtitleRow)
        val searchBar = view.findViewById<LinearLayout>(R.id.searchBarContainer)

        headerTop.alpha = 0f
        headerTop.translationY = -30f
        subtitleRow.alpha = 0f
        subtitleRow.translationY = -20f
        searchBar.alpha = 0f
        searchBar.translationY = -20f
        searchBar.scaleX = 0.9f
        searchBar.scaleY = 0.9f

        headerTop.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        subtitleRow.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(200)
            .setStartDelay(50)
            .setInterpolator(OvershootInterpolator(1.2f))
            .start()

        searchBar.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(220)
            .setStartDelay(100)
            .setInterpolator(OvershootInterpolator(1.5f))
            .start()
    }

    private fun setupSearchBar(
        searchEditText: EditText,
        recyclerView: RecyclerView,
        emptyStateContainer: View,
        headerSubtitle: TextView
    ) {
        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.lowercase()?.trim() ?: ""
                val filtered = if (query.isEmpty()) {
                    allSubjects
                } else {
                    allSubjects.filter {
                        it.name.lowercase().contains(query) ||
                        it.description.lowercase().contains(query)
                    }
                }
                showSubjects(filtered, recyclerView, emptyStateContainer, headerSubtitle)
            }
        })
    }

    private fun loadSubjectStats(subjects: List<Subject>) {
        viewLifecycleOwner.lifecycleScope.launch {
            val stats = mutableMapOf<Long, SubjectWithStats>()

            val (tasksResult, topicsResult, submissionsResult) = withContext(Dispatchers.IO) {
                Triple(
                    async { BackendApiService.getTasksByCourse(courseId) },
                    async { BackendApiService.getTopicsByCourse(courseId) },
                    async { BackendApiService.getMySubmissions(page = 1, limit = 500) }
                ).let { Triple(it.first.await(), it.second.await(), it.third.await()) }
            }

            val allTasks = if (tasksResult is ApiResult.Success) tasksResult.data else emptyList()
            val allTopics = if (topicsResult is ApiResult.Success) topicsResult.data else emptyList()
            val mySubmissions = if (submissionsResult is ApiResult.Success) submissionsResult.data else emptyList()

            val submittedTaskIds = mySubmissions.map { it.taskId }.toSet()

            subjects.forEach { subject ->
                val subjectTopicIds = allTopics
                    .filter { it.subjectId == subject.id }
                    .map { it.id }
                    .toSet()

                val subjectTasks = allTasks.filter { it.topicId in subjectTopicIds }
                val taskCount = subjectTasks.size
                val completedCount = subjectTasks.count { it.id in submittedTaskIds }
                val progress = if (taskCount > 0) (completedCount * 100) / taskCount else 0

                stats[subject.id] = SubjectWithStats(subject, taskCount, progress)
            }

            // Load period counts per subject
            try {
                val periodsResult = withContext(Dispatchers.IO) {
                    BackendApiService.listPeriodsByCourse(courseId)
                }
                if (periodsResult is ApiResult.Success) {
                    val data = periodsResult.data
                    val countMap = mutableMapOf<Long, Int>()
                    for (p in data) {
                        val sid = p.get("subject_id")?.takeIf { !it.isJsonNull }?.asLong ?: continue
                        countMap[sid] = (countMap[sid] ?: 0) + 1
                    }
                    for ((sid, count) in countMap) {
                        val existing = stats[sid]
                        if (existing != null) {
                            stats[sid] = existing.copy(periodCount = count)
                        }
                    }
                }
            } catch (_: Exception) { }

            subjectStats = stats
            subjectAdapter.updateStats(stats)
        }
    }

    private fun checkCourseDeadline(view: View) {
        val sessionManager = SessionManager.getInstance(requireContext())
        if (sessionManager.hasRole(3) || sessionManager.hasRole(4)) return // Admins are not restricted

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getCourseById(courseId)
                }
                if (result is ApiResult.Success) {
                    if (!result.data.isActive) {
                        val banner = view.findViewById<LinearLayout>(R.id.expiredCourseBanner)
                        val recyclerView = view.findViewById<RecyclerView>(R.id.subjectsRecyclerView)
                        val emptyState = view.findViewById<LinearLayout>(R.id.emptyStateContainer)
                        banner?.visibility = View.VISIBLE
                        recyclerView?.visibility = View.GONE
                        emptyState?.visibility = View.GONE
                        return@launch
                    }
                    val deadlineStr = result.data.deadline
                    if (!deadlineStr.isNullOrEmpty()) {
                        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).also {
                            it.timeZone = java.util.TimeZone.getTimeZone("UTC")
                        }
                        val deadline = sdf.parse(deadlineStr.take(19))
                        if (deadline != null && deadline.before(java.util.Date())) {
                            val banner = view.findViewById<LinearLayout>(R.id.expiredCourseBanner)
                            val recyclerView = view.findViewById<RecyclerView>(R.id.subjectsRecyclerView)
                            val emptyState = view.findViewById<LinearLayout>(R.id.emptyStateContainer)
                            banner?.visibility = View.VISIBLE
                            recyclerView?.visibility = View.GONE
                            emptyState?.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("SubjectsListFragment", "Could not check course deadline", e)
            }
        }
    }

    private fun checkAccessAndSetup(fab: FloatingActionButton, emptyBtn: MaterialButton, sortContainer: FrameLayout? = null) {
        subjectAdapter.onEditClick = { subject -> navigateToEdit(subject) }
        subjectAdapter.onDeleteClick = { subject -> confirmDelete(subject) }

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val sessionManager = SessionManager.getInstance(ctx)
            hasAccess = sessionManager.hasRole(3) || sessionManager.hasRole(4)
            isCollaboratorOnly = false
            if (hasAccess) {
                subjectAdapter.isAdmin = sessionManager.hasRole(3) || sessionManager.hasRole(4)
                subjectAdapter.currentUserId = sessionManager.getUserId()

                if (isAdded) grantModifyAccess(fab, emptyBtn, sortContainer)
            }

            val collaboratorAccess = resolveCollaboratorCourseAccess(sessionManager)
            if (!isAdded) return@launch
            if (collaboratorAccess) {
                hasAccess = true
                isCollaboratorOnly = true
                subjectAdapter.isAdmin = false
                subjectAdapter.currentUserId = sessionManager.getUserId()
                grantModifyAccess(fab, emptyBtn, sortContainer)
            }
        }
    }

    private fun grantModifyAccess(fab: FloatingActionButton, emptyBtn: MaterialButton, sortContainer: FrameLayout? = null) {
        val ctx = context ?: return
        fab.visibility = View.VISIBLE
        val fabAnim = AnimationUtils.loadAnimation(ctx, R.anim.fab_bounce_in)
        fab.startAnimation(fabAnim)

        emptyBtn.visibility = View.VISIBLE
        subjectAdapter.canModify = true
        subjectAdapter.notifyDataSetChanged()

        // Mostrar botón de ordenar en el header
        sortContainer?.visibility = View.VISIBLE
    }

    private fun showSubjectOptions(subject: Subject) {
        val dialog = BottomSheetDialog(requireContext(), R.style.DarkBottomSheetDialogTheme)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_subject_options, null)

        sheetView.findViewById<TextView>(R.id.tvSubjectName).text = subject.name

        sheetView.findViewById<TextView>(R.id.tvEditSubject).setOnClickListener {
            dialog.dismiss()
            navigateToEdit(subject)
        }

        sheetView.findViewById<TextView>(R.id.tvDeleteSubject).setOnClickListener {
            dialog.dismiss()
            confirmDelete(subject)
        }

        sheetView.findViewById<TextView>(R.id.tvCancel).setOnClickListener {
            dialog.dismiss()
        }

        dialog.setContentView(sheetView)
        dialog.window?.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        dialog.show()
    }

    private fun navigateToEdit(subject: Subject) {
        val bundle = Bundle().apply {
            putLong("courseId", courseId)
            putString("courseName", courseName)
            putLong("subjectId", subject.id)
            putString("subjectName", subject.name)
            putString("subjectDescription", subject.description)
            putString("subjectCode", subject.code ?: "")
            putString("subjectThumbnailUrl", subject.thumbnailUrl ?: "")
        }
        findNavController().navigate(R.id.action_subjectsListFragment_to_subjectCreationFragment, bundle)
    }

    private fun confirmDelete(subject: Subject) {
        AlertDialog.Builder(requireContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Eliminar materia")
            .setMessage("¿Estás seguro de eliminar \"${subject.name}\"? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ -> deleteSubject(subject) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteSubject(subject: Subject) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackendApiService.deleteSubject(subject.id)
            }
            when (result) {
                is ApiResult.Success -> {
                    AppCache.invalidateSubjects(courseId)
                    showSafeToast("Materia eliminada")
                    val rv = view?.findViewById<RecyclerView>(R.id.subjectsRecyclerView) ?: return@launch
                    val empty = view?.findViewById<LinearLayout>(R.id.emptyStateContainer) ?: return@launch
                    val loading = view?.findViewById<ProgressBar>(R.id.loadingProgressBar) ?: return@launch
                    val subtitle = view?.findViewById<TextView>(R.id.headerSubtitle) ?: return@launch
                    loadSubjects(rv, empty, loading, subtitle)
                }
                is ApiResult.Error -> {
                    showSafeToast("Error: ${result.message}", Toast.LENGTH_LONG)
                }
            }
        }
    }

    // ── Grade summary helpers ──────────────────────────────────────────

    private data class StudentGradeSummary(
        val studentName: String,
        val taskAvg: Float?,
        val participacionAvg: Float?,
        val examenesAvg: Float?,
        val comportamientoAvg: Float?,
        val notaPonderada: Float?,
        val gradedBy: String?,
        val gradedAt: String?
    )

    private fun computeSubjectGradeSummaries(gradeSheet: JsonObject, teacherName: String): List<StudentGradeSummary> {
        val students = gradeSheet.getAsJsonArray("students") ?: return emptyList()
        val manualGrades = gradeSheet.getAsJsonArray("manualGrades") ?: JsonArray()
        val taskGrades = gradeSheet.getAsJsonArray("taskGrades") ?: JsonArray()
        val tasks = gradeSheet.getAsJsonArray("tasks") ?: JsonArray()
        val totalTasks = tasks.size()

        // Detect which manual grade categories are active (used for any student in this subject)
        val activeCategories = mutableSetOf<String>()
        for (mge in manualGrades) {
            val rawType = mge.asJsonObject.get("gradeType")?.asString ?: continue
            activeCategories.add(rawType.replace(Regex("_\\d+$"), ""))
        }

        return students.mapNotNull { se ->
            val s = se.asJsonObject
            val studentId = s.get("userId")?.asLong ?: return@mapNotNull null
            val fullName = s.get("fullName")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            val username = s.get("username")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
            val studentName = fullName ?: "Sin nombre registrado"

            val stTaskGrades = taskGrades.filter {
                it.asJsonObject.get("studentId")?.asLong == studentId
            }
            val taskGradeVals = stTaskGrades.mapNotNull { it.asJsonObject.get("grade")?.let { g -> if (g.isJsonNull) null else g.asFloat } }
            val taskAvg = if (totalTasks > 0) taskGradeVals.sum() / totalTasks.toFloat() else null

            val byType = mutableMapOf<String, MutableList<Float>>()
            for (mge in manualGrades) {
                val mg = mge.asJsonObject
                if (mg.get("studentId")?.asLong == studentId) {
                    val rawType = mg.get("gradeType")?.asString ?: continue
                    val baseType = rawType.replace(Regex("_\\d+$"), "")
                    val gradeVal = mg.get("grade")?.let { if (it.isJsonNull) null else it.asFloat } ?: continue
                    byType.getOrPut(baseType) { mutableListOf() }.add(gradeVal)
                }
            }
            fun avgList(list: List<Float>?): Float? = if (!list.isNullOrEmpty()) list.sum() / list.size else null
            // Treat empty slots as 0 when the category is active (used by any student in the subject)
            val participacionAvg = if (!byType["participacion"].isNullOrEmpty()) avgList(byType["participacion"])
                                   else if ("participacion" in activeCategories) 0f else null
            val examenesAvg = if (!byType["examenes"].isNullOrEmpty()) avgList(byType["examenes"])
                              else if ("examenes" in activeCategories) 0f else null
            val comportamientoAvg = if (!byType["comportamiento"].isNullOrEmpty()) avgList(byType["comportamiento"])
                                    else if ("comportamiento" in activeCategories) 0f else null

            val available = listOfNotNull(taskAvg, participacionAvg, examenesAvg, comportamientoAvg)
            val notaPonderada = if (available.isNotEmpty()) available.sum() / available.size else null

            val latestGradedAt = stTaskGrades
                .mapNotNull { it.asJsonObject.get("gradedAt")?.takeIf { g -> !g.isJsonNull }?.asString }
                .maxOrNull()
                ?.let { raw ->
                    try {
                        val d = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).parse(raw.take(10))
                        if (d != null) java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale.getDefault()).format(d) else null
                    } catch (_: Exception) { null }
                }

            fun fmt(v: Float?): Float? = if (v != null) (Math.round(v * 10) / 10f) else null
            StudentGradeSummary(
                studentName = studentName,
                taskAvg = fmt(taskAvg),
                participacionAvg = fmt(participacionAvg),
                examenesAvg = fmt(examenesAvg),
                comportamientoAvg = fmt(comportamientoAvg),
                notaPonderada = fmt(notaPonderada),
                gradedBy = teacherName,
                gradedAt = latestGradedAt
            )
        }
    }

    // ── Reporte de notas ──────────────────────────────────────────────

    private fun showReportBottomSheet() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx, R.style.DarkBottomSheetDialogTheme)
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_grade_report, null)
        dialog.setContentView(sheetView)
        dialog.window?.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        val tvLoading = sheetView.findViewById<LinearLayout>(R.id.reportLoading)
        val contentLayout = sheetView.findViewById<LinearLayout>(R.id.reportContent)
        val tvSubjectCount = sheetView.findViewById<TextView>(R.id.tvSubjectCount)
        val tvTaskCount = sheetView.findViewById<TextView>(R.id.tvTaskCount)
        val tvGradedCount = sheetView.findViewById<TextView>(R.id.tvGradedCount)
        val tvAverage = sheetView.findViewById<TextView>(R.id.tvAverage)
        val reportListContainer = sheetView.findViewById<LinearLayout>(R.id.reportListContainer)
        val btnPdf = sheetView.findViewById<TextView>(R.id.btnExportPdf)
        val btnCsv = sheetView.findViewById<TextView>(R.id.btnExportCsv)
        val btnShare = sheetView.findViewById<TextView>(R.id.btnShare)

        dialog.show()

        // ── Fullscreen toggle ──────────────────────────────────────────────
        val bottomSheetFrame = dialog.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        bottomSheetFrame?.layoutParams?.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        val behavior = bottomSheetFrame?.let { BottomSheetBehavior.from(it) }
        val screenHeight = resources.displayMetrics.heightPixels
        behavior?.peekHeight = (screenHeight * 0.65).toInt()
        behavior?.skipCollapsed = false
        behavior?.state = BottomSheetBehavior.STATE_COLLAPSED

        val btnFullscreen = sheetView.findViewById<android.widget.ImageButton>(R.id.btnFullscreen)
        var isFullscreen = false
        btnFullscreen?.setOnClickListener {
            isFullscreen = !isFullscreen
            if (isFullscreen) {
                behavior?.state = BottomSheetBehavior.STATE_EXPANDED
                btnFullscreen.setImageResource(R.drawable.ic_fullscreen_minimal)
                btnFullscreen.imageTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#BF5AF2"))
            } else {
                behavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                btnFullscreen.imageTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#8E8E93"))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val topicsResult: ApiResult<List<com.example.tareamov.data.entity.Topic>>
                val tasksResult: ApiResult<List<com.example.tareamov.data.entity.Task>>
                val submissionsResult: ApiResult<List<com.example.tareamov.data.entity.TaskSubmission>>
                val teachersResult: ApiResult<List<com.example.tareamov.data.entity.Usuario>>

                val gradeSheetMap = mutableMapOf<Long, JsonObject>()

                withContext(Dispatchers.IO) {
                    val t1 = async { BackendApiService.getTopicsByCourse(courseId) }
                    val t2 = async { BackendApiService.getTasksByCourse(courseId) }
                    val t3 = if (isCreator) {
                        async { BackendApiService.getSubmissionsByCourse(courseId, limit = 500) }
                    } else {
                        async { BackendApiService.getMySubmissions(1, 500) }
                    }
                    val creatorIds = allSubjects.mapNotNull { it.createdBy }.distinct()
                    val t4 = if (creatorIds.isNotEmpty()) {
                        async { BackendApiService.getUsersByIds(creatorIds) }
                    } else {
                        async<ApiResult<List<com.example.tareamov.data.entity.Usuario>>> { ApiResult.Success(emptyList()) }
                    }
                    topicsResult = t1.await()
                    tasksResult = t2.await()
                    submissionsResult = t3.await()
                    teachersResult = t4.await()
                    // Fetch grade sheets for each subject in parallel
                    val sheetJobs = allSubjects.map { s ->
                        s.id to async {
                            try {
                                val r = BackendApiService.getGradeSheet(s.id)
                                if (r is ApiResult.Success) (r.data as? JsonObject) else null
                            } catch (_: Exception) { null }
                        }
                    }
                    for ((sid, job) in sheetJobs) {
                        val data = job.await()
                        if (data != null) gradeSheetMap[sid] = data
                    }
                }

                val topicList = if (topicsResult is ApiResult.Success) topicsResult.data else emptyList()
                val taskList = if (tasksResult is ApiResult.Success) tasksResult.data else emptyList()
                val subList = if (submissionsResult is ApiResult.Success) submissionsResult.data else emptyList()
                val teacherList = if (teachersResult is ApiResult.Success) teachersResult.data else emptyList()

                val teacherMap: Map<Long, String> = teacherList.associate {
                    it.id to (it.usuario.takeIf { u -> u.isNotBlank() } ?: "Docente #${it.id}")
                }

                val report = GradeReportHelper.buildReport(allSubjects, taskList, subList, topicList, teacherMap)
                var currentFilteredReport = report

                tvLoading.visibility = View.GONE
                contentLayout.visibility = View.VISIBLE

                // Summary
                tvSubjectCount.text = "${allSubjects.size}"
                tvTaskCount.text = "${report.sumOf { it.tasks.size }}"
                tvGradedCount.text = "${report.sumOf { it.tasks.count { t -> t.grade != null } }}"
                val allGraded = report.flatMap { it.tasks }.mapNotNull { it.grade }
                val avg = if (allGraded.isNotEmpty()) String.format("%.1f", allGraded.average()) else "0.0"
                tvAverage.text = avg
                if (allGraded.isNotEmpty()) {
                    val a = allGraded.average().toFloat()
                    tvAverage.setTextColor(android.graphics.Color.parseColor(
                        if (a >= 4f) "#34C759" else if (a >= 3f) "#FF9500" else "#FF453A"
                    ))
                }

                // Subject list — table format
                val dp = resources.displayMetrics.density

                // ── Filter pills (filtrar por materia) ───────────────────────────
                val filterScrollView = android.widget.HorizontalScrollView(ctx).apply {
                    isHorizontalScrollBarEnabled = false
                    val lp = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, 0, 0, (8 * dp).toInt()) }
                    layoutParams = lp
                }
                val filterPillsRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                filterScrollView.addView(filterPillsRow)
                val filterViewCount = 1

                fun updatePillStates(activeId: Long?) {
                    for (i in 0 until filterPillsRow.childCount) {
                        val pill = filterPillsRow.getChildAt(i) as? TextView ?: continue
                        val isActive = pill.tag == activeId
                        (pill.background as? android.graphics.drawable.GradientDrawable)?.also { d ->
                            d.setColor(if (isActive) android.graphics.Color.parseColor("#33BF5AF2") else android.graphics.Color.parseColor("#18FFFFFF"))
                            d.setStroke((1 * dp).toInt(), if (isActive) android.graphics.Color.parseColor("#80BF5AF2") else android.graphics.Color.parseColor("#18FFFFFF"))
                        }
                        pill.setTextColor(if (isActive) android.graphics.Color.parseColor("#BF5AF2") else android.graphics.Color.parseColor("#8E8E93"))
                    }
                }

                fun renderSubjectReport(selectedSubjectId: Long?) {
                    while (reportListContainer.childCount > filterViewCount) {
                        reportListContainer.removeViewAt(filterViewCount)
                    }
                    val filteredReport = if (selectedSubjectId == null) report else {
                        val sName = allSubjects.find { it.id == selectedSubjectId }?.name ?: ""
                        report.filter { it.subjectName == sName }
                    }
                    currentFilteredReport = filteredReport
                    for (group in filteredReport) {
                        val avgLabel = if (group.average != null) String.format("%.1f", group.average) else "0.0"
                        val subjectCard = android.widget.LinearLayout(ctx).apply {
                            orientation = android.widget.LinearLayout.VERTICAL
                            background = android.graphics.drawable.GradientDrawable().also { d ->
                                d.setColor(android.graphics.Color.parseColor("#1ABF5AF2"))
                                d.cornerRadius = (8 * dp)
                            }
                            setPadding((10 * dp).toInt(), (8 * dp).toInt(), (10 * dp).toInt(), (8 * dp).toInt())
                            val lp = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.setMargins(0, (12 * dp).toInt(), 0, (4 * dp).toInt()) }
                            layoutParams = lp
                        }
                        subjectCard.addView(TextView(ctx).apply {
                            text = group.subjectName
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 13f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        })
                        val teacher = group.teacherName ?: "—"
                        subjectCard.addView(TextView(ctx).apply {
                            text = "Docente: $teacher  ·  Promedio: $avgLabel"
                            setTextColor(android.graphics.Color.parseColor("#BF5AF2"))
                            textSize = 11f
                            val lp2 = android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                            ).also { it.topMargin = (2 * dp).toInt() }
                            layoutParams = lp2
                        })
                        reportListContainer.addView(subjectCard)

                        // Grade breakdown: categorías promedio por estudiante
                        val subjectSheetId = allSubjects.find { it.name == group.subjectName }?.id
                        val sheetData = if (subjectSheetId != null) gradeSheetMap[subjectSheetId] else null
                        if (sheetData != null) {
                            val summaries = computeSubjectGradeSummaries(sheetData, teacher)
                            if (summaries.isNotEmpty()) {
                                val sumColWeights = floatArrayOf(1.6f, 0.7f, 0.8f, 0.7f, 1.0f, 0.8f, 1.1f, 0.8f)
                                val sumHeaders = arrayOf("Estudiante", "Tareas", "Participación", "Examen", "Comportamiento", "Nota Pond.", "Docente", "Fecha Cal.")
                                val summaryHdr = android.widget.LinearLayout(ctx).apply {
                                    orientation = android.widget.LinearLayout.HORIZONTAL
                                    setBackgroundColor(android.graphics.Color.parseColor("#0CBF5AF2"))
                                    setPadding((8 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
                                }
                                val sumHdrParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                                for (i in sumHeaders.indices) {
                                    summaryHdr.addView(TextView(ctx).apply {
                                        text = sumHeaders[i]
                                        textSize = 9f
                                        setTextColor(android.graphics.Color.parseColor("#8E8E93"))
                                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                                        layoutParams = sumHdrParams.also { it.weight = sumColWeights[i] }
                                    })
                                }
                                reportListContainer.addView(summaryHdr)
                                for (summary in summaries) {
                                    fun gradeColorFor(v: Float?) = when {
                                        v == null -> "#636366"; v >= 4f -> "#34C759"; v >= 3f -> "#FF9500"; else -> "#FF453A"
                                    }
                                    fun fmtGrade(v: Float?) = if (v != null) String.format("%.1f", v) else "0.0"
                                    val summaryRow = android.widget.LinearLayout(ctx).apply {
                                        orientation = android.widget.LinearLayout.HORIZONTAL
                                        setPadding((8 * dp).toInt(), (5 * dp).toInt(), (4 * dp).toInt(), (5 * dp).toInt())
                                    }
                                    val sumVals = arrayOf(
                                        summary.studentName,
                                        fmtGrade(summary.taskAvg),
                                        fmtGrade(summary.participacionAvg),
                                        fmtGrade(summary.examenesAvg),
                                        fmtGrade(summary.comportamientoAvg),
                                        fmtGrade(summary.notaPonderada),
                                        summary.gradedBy ?: "—",
                                        summary.gradedAt ?: "—"
                                    )
                                    val sumColors = intArrayOf(
                                        android.graphics.Color.WHITE,
                                        android.graphics.Color.parseColor(gradeColorFor(summary.taskAvg)),
                                        android.graphics.Color.parseColor(gradeColorFor(summary.participacionAvg)),
                                        android.graphics.Color.parseColor(gradeColorFor(summary.examenesAvg)),
                                        android.graphics.Color.parseColor(gradeColorFor(summary.comportamientoAvg)),
                                        android.graphics.Color.parseColor(gradeColorFor(summary.notaPonderada)),
                                        android.graphics.Color.parseColor("#8E8E93"),
                                        android.graphics.Color.parseColor("#636366")
                                    )
                                    val rowParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
                                    for (i in sumVals.indices) {
                                        summaryRow.addView(TextView(ctx).apply {
                                            text = sumVals[i]
                                            textSize = 11f
                                            setTextColor(sumColors[i])
                                            if (i == 0 || i == 5) setTypeface(typeface, android.graphics.Typeface.BOLD)
                                            layoutParams = rowParams.also { it.weight = sumColWeights[i] }
                                            maxLines = 1
                                            ellipsize = android.text.TextUtils.TruncateAt.END
                                        })
                                    }
                                    reportListContainer.addView(summaryRow)
                                }
                                reportListContainer.addView(android.view.View(ctx).apply {
                                    layoutParams = android.widget.LinearLayout.LayoutParams(
                                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
                                    ).also { it.setMargins(0, (4 * dp).toInt(), 0, (6 * dp).toInt()) }
                                    setBackgroundColor(android.graphics.Color.parseColor("#1ABF5AF2"))
                                })
                            } else {
                                reportListContainer.addView(TextView(ctx).apply {
                                    text = "   Sin entregas"
                                    setTextColor(android.graphics.Color.parseColor("#636366"))
                                    textSize = 12f
                                })
                            }
                        }
                    }
                }

                // Add filter pills to row
                fun addPill(label: String, subjectId: Long?) {
                    filterPillsRow.addView(TextView(ctx).apply {
                        text = label; textSize = 12f
                        setTextColor(android.graphics.Color.parseColor("#8E8E93"))
                        background = android.graphics.drawable.GradientDrawable().also { d ->
                            d.setColor(android.graphics.Color.parseColor("#18FFFFFF"))
                            d.cornerRadius = (20 * dp)
                            d.setStroke((1 * dp).toInt(), android.graphics.Color.parseColor("#18FFFFFF"))
                        }
                        setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
                        tag = subjectId
                        val lp = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.setMargins(0, 0, (6 * dp).toInt(), 0) }
                        layoutParams = lp
                        setOnClickListener {
                            updatePillStates(subjectId)
                            renderSubjectReport(subjectId)
                        }
                    })
                }
                addPill("Todas", null)
                for (subject in allSubjects) { addPill(subject.name, subject.id) }
                updatePillStates(null)

                reportListContainer.removeAllViews()
                reportListContainer.addView(filterScrollView)
                renderSubjectReport(null)

                // Export buttons
                btnPdf.setOnClickListener {
                    val isIncat = SessionManager.getInstance(requireContext()).isIncatInstitution()
                    val file = GradeReportHelper.generatePDF(ctx, currentFilteredReport, isIncat)
                    if (file != null) GradeReportHelper.shareFile(ctx, file, "application/pdf")
                    else showSafeToast("Error al generar PDF")
                }
                btnCsv.setOnClickListener {
                    val isIncatCsv = SessionManager.getInstance(requireContext()).isIncatInstitution()
                    val file = GradeReportHelper.generateCSV(ctx, currentFilteredReport, courseName, isIncatCsv)
                    if (file != null) GradeReportHelper.shareFile(ctx, file, "application/vnd.ms-excel")
                    else showSafeToast("Error al generar Excel")
                }
                btnShare.setOnClickListener {
                    val text = GradeReportHelper.buildShareText(currentFilteredReport)
                    GradeReportHelper.shareText(ctx, text)
                }

            } catch (e: Exception) {
                tvLoading.visibility = View.GONE
                showSafeToast("Error al cargar el reporte")
                dialog.dismiss()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::subjectAdapter.isInitialized || !subjectsDataBound) return
        val recyclerView = view?.findViewById<RecyclerView>(R.id.subjectsRecyclerView) ?: return
        val emptyState = view?.findViewById<LinearLayout>(R.id.emptyStateContainer) ?: return
        val loading = view?.findViewById<ProgressBar>(R.id.loadingProgressBar) ?: return
        val subtitle = view?.findViewById<TextView>(R.id.headerSubtitle) ?: return
        loadSubjects(recyclerView, emptyState, loading, subtitle)
    }

    override fun onDestroyView() {
        dragCallback?.stopAutoScroll()
        itemTouchHelper = null
        dragCallback = null
        isDragMode = false
        subjectsDataObserverAttached = false
        subjectsDataBound = false
        super.onDestroyView()
    }

    // ── Boletín de notas ──────────────────────────────────────────────
    private fun showBulletinBottomSheet() {
        val ctx = context ?: return
        val dialog = BottomSheetDialog(ctx, R.style.DarkBottomSheetDialogTheme)
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(android.graphics.Color.parseColor("#1C1C1E"))
        }
        dialog.setContentView(rootLayout)
        dialog.window?.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )?.setBackgroundColor(android.graphics.Color.TRANSPARENT)

        // ── Fullscreen toggle ──────────────────────────────────────────────
        val bottomSheetFrame = dialog.window?.findViewById<android.widget.FrameLayout>(
            com.google.android.material.R.id.design_bottom_sheet
        )
        bottomSheetFrame?.layoutParams?.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        val behavior = bottomSheetFrame?.let { BottomSheetBehavior.from(it) }
        val screenHeight = resources.displayMetrics.heightPixels
        behavior?.peekHeight = (screenHeight * 0.65).toInt()
        behavior?.skipCollapsed = false
        behavior?.state = BottomSheetBehavior.STATE_COLLAPSED

        var isBulletinFullscreen = false

        // Title row with fullscreen button
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 24)
        }
        val titleTv = TextView(ctx).apply {
            text = "Generar Boletín"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleRow.addView(titleTv)
        val btnFullscreen = ImageButton(ctx).apply {
            setImageResource(R.drawable.ic_fullscreen)
            imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#8E8E93"))
            setBackgroundResource(android.R.color.transparent)
            setPadding(16, 16, 16, 16)
            setOnClickListener {
                isBulletinFullscreen = !isBulletinFullscreen
                if (isBulletinFullscreen) {
                    behavior?.state = BottomSheetBehavior.STATE_EXPANDED
                    setImageResource(R.drawable.ic_fullscreen_minimal)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#30D158"))
                } else {
                    behavior?.state = BottomSheetBehavior.STATE_COLLAPSED
                    setImageResource(R.drawable.ic_fullscreen)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        android.graphics.Color.parseColor("#8E8E93"))
                }
            }
        }
        titleRow.addView(btnFullscreen)
        rootLayout.addView(titleRow)

        // Loading
        val loadingTv = TextView(ctx).apply {
            text = "Cargando estudiantes…"
            setTextColor(android.graphics.Color.parseColor("#8E8E93"))
            textSize = 14f
        }
        rootLayout.addView(loadingTv)

        dialog.show()

        BackendApiService.initialize(ctx)
        lifecycleScope.launch {
            try {
                // Fetch grade sheets for all subjects
                data class BulletinSubject(val subjectId: Long, val subjectName: String, val gradeSheet: JsonObject)
                val bulletinSubjects = mutableListOf<BulletinSubject>()

                for (subject in allSubjects) {
                    try {
                        val result = withContext(Dispatchers.IO) { BackendApiService.getGradeSheet(subject.id) }
                        if (result is ApiResult.Success<*>) {
                            @Suppress("UNCHECKED_CAST")
                            val sheet = (result as ApiResult.Success<com.google.gson.JsonObject>).data
                            bulletinSubjects.add(BulletinSubject(subject.id, subject.name, sheet))
                        }
                    } catch (_: Exception) {}
                }

                // Collect unique students
                data class StudentInfo(val userId: Long, val fullName: String, val username: String, val cedula: String)
                val studentMap = mutableMapOf<Long, StudentInfo>()
                for (bs in bulletinSubjects) {
                    val students = bs.gradeSheet.getAsJsonArray("students") ?: continue
                    for (se in students) {
                        val s = se.asJsonObject
                        val uid = s.get("userId")?.asLong ?: continue
                        if (!studentMap.containsKey(uid)) {
                            studentMap[uid] = StudentInfo(
                                userId = uid,
                                fullName = s.get("fullName")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                username = s.get("username")?.takeIf { !it.isJsonNull }?.asString ?: "",
                                cedula = s.get("cedula")?.takeIf { !it.isJsonNull }?.asString ?: ""
                            )
                        }
                    }
                }

                val sortedStudents = studentMap.values.sortedBy { it.fullName.ifBlank { it.username } }

                rootLayout.removeView(loadingTv)

                if (sortedStudents.isEmpty()) {
                    rootLayout.addView(TextView(ctx).apply {
                        text = "No se encontraron estudiantes"
                        setTextColor(android.graphics.Color.parseColor("#636366"))
                        textSize = 14f
                        setPadding(0, 32, 0, 32)
                    })
                    return@launch
                }

                if (openAllBulletinsOnLoad) {
                    openAllBulletinsOnLoad = false
                    showAllStudentsBulletin(dialog, rootLayout, sortedStudents, bulletinSubjects.map { bs ->
                        Triple(bs.subjectId, bs.subjectName, bs.gradeSheet)
                    })
                    return@launch
                }

                // "Ver boletín de todos" button
                val viewAllBtn = TextView(ctx).apply {
                    text = "📋 Ver boletín de todos (${sortedStudents.size})"
                    setTextColor(android.graphics.Color.parseColor("#30D158"))
                    textSize = 15f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(24, 20, 24, 20)
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14f * resources.displayMetrics.density
                        setColor(android.graphics.Color.parseColor("#1A30D158"))
                        setStroke((1 * resources.displayMetrics.density).toInt(), android.graphics.Color.parseColor("#4D30D158"))
                    }
                    background = bg
                    gravity = android.view.Gravity.CENTER
                    setOnClickListener {
                        showAllStudentsBulletin(dialog, rootLayout, sortedStudents, bulletinSubjects.map { bs ->
                            Triple(bs.subjectId, bs.subjectName, bs.gradeSheet)
                        })
                    }
                }
                rootLayout.addView(viewAllBtn)

                // Spacer
                rootLayout.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (12 * resources.displayMetrics.density).toInt())
                })

                // Search field
                val searchEt = EditText(ctx).apply {
                    hint = "Buscar estudiante..."
                    setHintTextColor(android.graphics.Color.parseColor("#8E8E93"))
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 14f
                    setBackgroundColor(android.graphics.Color.parseColor("#2C2C2E"))
                    setPadding(24, 16, 24, 16)
                }
                rootLayout.addView(searchEt)

                val studentListContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                rootLayout.addView(studentListContainer)

                fun renderStudentList(filter: String = "") {
                    studentListContainer.removeAllViews()
                    val q = filter.lowercase()
                    val filtered = if (q.isBlank()) sortedStudents else sortedStudents.filter {
                        it.fullName.lowercase().contains(q) || it.username.lowercase().contains(q)
                    }
                    for (student in filtered) {
                        val btn = TextView(ctx).apply {
                            text = student.fullName.ifBlank { student.username.ifBlank { "#${student.userId}" } }
                            setTextColor(android.graphics.Color.WHITE)
                            textSize = 15f
                            setPadding(16, 24, 16, 24)
                            setBackgroundResource(android.R.color.transparent)
                            setOnClickListener {
                                showStudentBulletin(dialog, rootLayout, student.userId, student.fullName.ifBlank { student.username }, student.cedula, bulletinSubjects.map { bs ->
                                    Triple(bs.subjectId, bs.subjectName, bs.gradeSheet)
                                })
                            }
                        }
                        studentListContainer.addView(btn)
                        // Divider
                        studentListContainer.addView(View(ctx).apply {
                            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
                        })
                    }
                }

                renderStudentList()

                searchEt.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) { renderStudentList(s?.toString() ?: "") }
                })

            } catch (e: Exception) {
                loadingTv.text = "Error al cargar datos"
            }
        }
    }

    private fun showStudentBulletin(
        dialog: BottomSheetDialog,
        rootLayout: LinearLayout,
        studentId: Long,
        studentName: String,
        cedula: String,
        subjectSheets: List<Triple<Long, String, JsonObject>>
    ) {
        val ctx = context ?: return
        rootLayout.removeAllViews()

        // Back button
        val backBtn = TextView(ctx).apply {
            text = "← Otro estudiante"
            setTextColor(android.graphics.Color.parseColor("#0A84FF"))
            textSize = 14f
            setPadding(0, 0, 0, 24)
            setOnClickListener { showBulletinBottomSheet(); dialog.dismiss() }
        }
        rootLayout.addView(backBtn)

        // INCAT Institution header (only for INCAT users)
        val sessionMgr = SessionManager.getInstance(ctx)
        if (sessionMgr.isIncatInstitution()) {
            val incatHeader = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 16)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            val logoView = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(160, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            com.bumptech.glide.Glide.with(ctx)
                .load("https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/incat.jpg")
                .into(logoView)
            incatHeader.addView(logoView)
            val incatLines = arrayOf(
                "POLITECNICO INSTITUCIONAL DEL CARIBE \"INCAT\"",
                "Licencia de funcionamiento Resolución No 439 del 26 /10/ 2010. Emanada de S. E. M",
                "Licencia de funcionamiento resolución Nº1952 del 17/12/2010. Emanada de S. E. D.",
                "Institución Educativa De Formación para el trabajo y el desarrollo humano",
                "NIT: 900391687-0"
            )
            for ((i, line) in incatLines.withIndex()) {
                incatHeader.addView(TextView(ctx).apply {
                    text = line
                    gravity = android.view.Gravity.CENTER
                    when (i) {
                        0 -> { setTextColor(android.graphics.Color.parseColor("#8B0000")); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD) }
                        4 -> { setTextColor(android.graphics.Color.parseColor("#8B0000")); textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD) }
                        else -> { setTextColor(android.graphics.Color.parseColor("#CCCCCC")); textSize = 9f }
                    }
                    setPadding(0, if (i == 0) 8 else 2, 0, 0)
                })
            }
            incatHeader.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { topMargin = 12 }
                setBackgroundColor(android.graphics.Color.parseColor("#8B0000"))
            })
            rootLayout.addView(incatHeader)
        }

        // Header
        rootLayout.addView(TextView(ctx).apply {
            text = "BOLETÍN DE NOTAS"
            setTextColor(android.graphics.Color.parseColor("#BF5AF2"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 8)
        })

        rootLayout.addView(TextView(ctx).apply {
            text = "Programa: $courseName"
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            textSize = 13f
        })
        rootLayout.addView(TextView(ctx).apply {
            text = "Estudiante: $studentName"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 4, 0, 0)
        })
        if (cedula.isNotBlank()) {
            rootLayout.addView(TextView(ctx).apply {
                text = "Identificación: $cedula"
                setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                textSize = 13f
                setPadding(0, 2, 0, 0)
            })
        }
        val now = java.util.Calendar.getInstance()
        val semester = if (now.get(java.util.Calendar.MONTH) < 6) "I" else "II"
        val period = "$semester PERIODO ${now.get(java.util.Calendar.YEAR)}-${if (semester == "I") "A" else "B"}"
        rootLayout.addView(TextView(ctx).apply {
            text = "Periodo: $period"
            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
            textSize = 13f
            setPadding(0, 2, 0, 16)
        })

        // Divider
        rootLayout.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
            setBackgroundColor(android.graphics.Color.parseColor("#6A1B9A"))
        })

        // Table header
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 8)
        }
        headerRow.addView(TextView(ctx).apply {
            text = "MÓDULO"
            setTextColor(android.graphics.Color.parseColor("#BF5AF2"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        })
        headerRow.addView(TextView(ctx).apply {
            text = "NOTA"
            setTextColor(android.graphics.Color.parseColor("#BF5AF2"))
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        rootLayout.addView(headerRow)

        // Compute grades per subject
        val grades = mutableListOf<Pair<String, Float>>()
        for ((_, subjectName, gradeSheet) in subjectSheets) {
            val tasks = gradeSheet.getAsJsonArray("tasks") ?: JsonArray()
            val taskGrades = gradeSheet.getAsJsonArray("taskGrades") ?: JsonArray()
            val manualGrades = gradeSheet.getAsJsonArray("manualGrades") ?: JsonArray()
            val totalTasks = tasks.size()

            val stTaskGrades = taskGrades.filter { it.asJsonObject.get("studentId")?.asLong == studentId }
            val taskVals = stTaskGrades.mapNotNull { it.asJsonObject.get("grade")?.let { g -> if (g.isJsonNull) null else g.asFloat } }
            val taskAvg = if (totalTasks > 0) taskVals.sum() / totalTasks.toFloat() else null

            val byType = mutableMapOf<String, MutableList<Float>>()
            for (mge in manualGrades) {
                val mg = mge.asJsonObject
                if (mg.get("studentId")?.asLong == studentId) {
                    val rawType = mg.get("gradeType")?.asString ?: continue
                    val baseType = rawType.replace(Regex("_\\d+$"), "")
                    val gradeVal = mg.get("grade")?.let { if (it.isJsonNull) null else it.asFloat } ?: continue
                    byType.getOrPut(baseType) { mutableListOf() }.add(gradeVal)
                }
            }
            fun avgList(list: List<Float>?): Float? = if (!list.isNullOrEmpty()) list.sum() / list.size else null
            val available = listOfNotNull(taskAvg, avgList(byType["participacion"]), avgList(byType["examenes"]), avgList(byType["comportamiento"]))
            val nota = if (available.isNotEmpty()) available.sum() / available.size else 0f
            grades.add(subjectName to Math.round(nota * 10) / 10f)
        }

        for ((subjectName, nota) in grades) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 12, 0, 12)
            }
            row.addView(TextView(ctx).apply {
                text = subjectName.uppercase()
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })
            row.addView(TextView(ctx).apply {
                text = nota.toString()
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(when {
                    nota >= 4f -> android.graphics.Color.parseColor("#30D158")
                    nota >= 3f -> android.graphics.Color.parseColor("#FF9F0A")
                    else -> android.graphics.Color.parseColor("#FF453A")
                })
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            rootLayout.addView(row)
            rootLayout.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
                setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            })
        }

        // ── INCAT Signature & Footer ──
        if (SessionManager.getInstance(ctx).isIncatInstitution()) {
            val dp = ctx.resources.displayMetrics.density
            rootLayout.addView(android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((160 * dp).toInt(), (1 * dp).toInt()).also {
                    it.setMargins(0, (24 * dp).toInt(), 0, 0)
                }
                setBackgroundColor(android.graphics.Color.parseColor("#8B0000"))
            })
            rootLayout.addView(TextView(ctx).apply {
                text = "AQUILES AMAYA IGUARAN"
                textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, (6 * dp).toInt(), 0, 0)
            })
            rootLayout.addView(TextView(ctx).apply {
                text = "RECOR"
                textSize = 10f; setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
            rootLayout.addView(android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (2 * dp).toInt()
                ).also { it.setMargins(0, (16 * dp).toInt(), 0, 0) }
                setBackgroundColor(android.graphics.Color.parseColor("#8B0000"))
            })
            val footerLines = arrayOf(
                "Politécnico \"INCAT\", forjando líderes para triunfar!",
                "SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740",
                "E-mail: politecnicoincat@gmail.com",
                "RIOHACHA- LA GUAJIRA"
            )
            for ((i, line) in footerLines.withIndex()) {
                rootLayout.addView(TextView(ctx).apply {
                    text = line; textSize = 10f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(if (i == 0 || i == 2) android.graphics.Color.parseColor("#8B0000") else android.graphics.Color.parseColor("#CCCCCC"))
                    if (i == 0) setTypeface(null, android.graphics.Typeface.ITALIC)
                    if (i == 1 || i == 3) setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }
        }

        // Share button
        rootLayout.addView(TextView(ctx).apply {
            text = "📤 Compartir boletín"
            setTextColor(android.graphics.Color.parseColor("#0A84FF"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                val sb = StringBuilder()
                sb.appendLine("📋 BOLETÍN DE NOTAS")
                sb.appendLine("📌 Programa: $courseName")
                sb.appendLine("👤 Estudiante: $studentName")
                sb.appendLine("📅 Periodo: $period")
                sb.appendLine("─".repeat(32))
                for ((subjectName, nota) in grades) {
                    sb.appendLine("${subjectName.padEnd(26)}${nota}")
                }
                sb.appendLine("─".repeat(32))
                if (SessionManager.getInstance(ctx).isIncatInstitution()) {
                    sb.appendLine()
                    sb.appendLine("AQUILES AMAYA IGUARAN — RECOR")
                    sb.appendLine()
                    sb.appendLine("Politécnico \"INCAT\", forjando líderes para triunfar!")
                    sb.appendLine("SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740")
                    sb.appendLine("E-mail: politecnicoincat@gmail.com")
                    sb.appendLine("RIOHACHA- LA GUAJIRA")
                }
                GradeReportHelper.shareText(ctx, sb.toString())
            }
        })
    }

    private data class BulletinStudentInfo(val userId: Long, val fullName: String, val username: String, val cedula: String)

    private fun showAllStudentsBulletin(
        dialog: BottomSheetDialog,
        rootLayout: LinearLayout,
        students: List<Any>,
        subjectSheets: List<Triple<Long, String, JsonObject>>
    ) {
        val ctx = context ?: return
        rootLayout.removeAllViews()

        // Back button
        rootLayout.addView(TextView(ctx).apply {
            text = "← Volver a la lista"
            setTextColor(android.graphics.Color.parseColor("#0A84FF"))
            textSize = 14f
            setPadding(0, 0, 0, 24)
            setOnClickListener { showBulletinBottomSheet(); dialog.dismiss() }
        })

        // INCAT Institution header (only for INCAT users)
        val sessionMgr = SessionManager.getInstance(ctx)
        if (sessionMgr.isIncatInstitution()) {
            val incatHeader = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 16)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            val logoView = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(160, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                }
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            com.bumptech.glide.Glide.with(ctx)
                .load("https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/incat.jpg")
                .into(logoView)
            incatHeader.addView(logoView)
            val incatLines = arrayOf(
                "POLITECNICO INSTITUCIONAL DEL CARIBE \"INCAT\"",
                "Licencia de funcionamiento Resolución No 439 del 26 /10/ 2010. Emanada de S. E. M",
                "Licencia de funcionamiento resolución Nº1952 del 17/12/2010. Emanada de S. E. D.",
                "Institución Educativa De Formación para el trabajo y el desarrollo humano",
                "NIT: 900391687-0"
            )
            for ((i, line) in incatLines.withIndex()) {
                incatHeader.addView(TextView(ctx).apply {
                    text = line
                    gravity = android.view.Gravity.CENTER
                    when (i) {
                        0 -> { setTextColor(android.graphics.Color.parseColor("#8B0000")); textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD) }
                        4 -> { setTextColor(android.graphics.Color.parseColor("#8B0000")); textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD) }
                        else -> { setTextColor(android.graphics.Color.parseColor("#CCCCCC")); textSize = 9f }
                    }
                    setPadding(0, if (i == 0) 8 else 2, 0, 0)
                })
            }
            incatHeader.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2).apply { topMargin = 12 }
                setBackgroundColor(android.graphics.Color.parseColor("#8B0000"))
            })
            rootLayout.addView(incatHeader)
        }

        rootLayout.addView(TextView(ctx).apply {
            text = "BOLETÍN DE TODOS LOS ESTUDIANTES"
            setTextColor(android.graphics.Color.parseColor("#BF5AF2"))
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        })

        val now = java.util.Calendar.getInstance()
        val semester = if (now.get(java.util.Calendar.MONTH) < 6) "I" else "II"
        val period = "$semester PERIODO ${now.get(java.util.Calendar.YEAR)}-${if (semester == "I") "A" else "B"}"

        for (studentObj in students) {
            // Extract fields via reflection since StudentInfo is a local class in caller
            val studentUserId: Long
            val studentFullName: String
            val studentUsername: String
            val studentCedula: String
            try {
                val cls = studentObj::class.java
                studentUserId = cls.getDeclaredField("userId").apply { isAccessible = true }.getLong(studentObj)
                studentFullName = cls.getDeclaredField("fullName").apply { isAccessible = true }.get(studentObj) as? String ?: ""
                studentUsername = cls.getDeclaredField("username").apply { isAccessible = true }.get(studentObj) as? String ?: ""
                studentCedula = cls.getDeclaredField("cedula").apply { isAccessible = true }.get(studentObj) as? String ?: ""
            } catch (_: Exception) { continue }

            val displayName = studentFullName.ifBlank { studentUsername }

            // Divider before each student
            rootLayout.addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 3)
                setBackgroundColor(android.graphics.Color.parseColor("#6A1B9A"))
            })

            // Student header
            rootLayout.addView(TextView(ctx).apply {
                text = "📌 $courseName"
                setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                textSize = 13f
                setPadding(0, 16, 0, 0)
            })
            rootLayout.addView(TextView(ctx).apply {
                text = "👤 $displayName"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 4, 0, 0)
            })
            if (studentCedula.isNotBlank()) {
                rootLayout.addView(TextView(ctx).apply {
                    text = "Identificación: $studentCedula"
                    setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                    textSize = 13f
                    setPadding(0, 2, 0, 0)
                })
            }
            rootLayout.addView(TextView(ctx).apply {
                text = "Periodo: $period"
                setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                textSize = 13f
                setPadding(0, 2, 0, 12)
            })

            // Compute grades
            val grades = mutableListOf<Pair<String, Float>>()
            for ((_, subjectName, gradeSheet) in subjectSheets) {
                val tasks = gradeSheet.getAsJsonArray("tasks") ?: JsonArray()
                val taskGrades = gradeSheet.getAsJsonArray("taskGrades") ?: JsonArray()
                val manualGrades = gradeSheet.getAsJsonArray("manualGrades") ?: JsonArray()
                val totalTasks = tasks.size()

                val stTaskGrades = taskGrades.filter { it.asJsonObject.get("studentId")?.asLong == studentUserId }
                val taskVals = stTaskGrades.mapNotNull { it.asJsonObject.get("grade")?.let { g -> if (g.isJsonNull) null else g.asFloat } }
                val taskAvg = if (totalTasks > 0) taskVals.sum() / totalTasks.toFloat() else null

                val byType = mutableMapOf<String, MutableList<Float>>()
                for (mge in manualGrades) {
                    val mg = mge.asJsonObject
                    if (mg.get("studentId")?.asLong == studentUserId) {
                        val rawType = mg.get("gradeType")?.asString ?: continue
                        val baseType = rawType.replace(Regex("_\\d+$"), "")
                        val gradeVal = mg.get("grade")?.let { if (it.isJsonNull) null else it.asFloat } ?: continue
                        byType.getOrPut(baseType) { mutableListOf() }.add(gradeVal)
                    }
                }
                fun avgList(list: List<Float>?): Float? = if (!list.isNullOrEmpty()) list.sum() / list.size else null
                val available = listOfNotNull(taskAvg, avgList(byType["participacion"]), avgList(byType["examenes"]), avgList(byType["comportamiento"]))
                val nota = if (available.isNotEmpty()) available.sum() / available.size else 0f
                grades.add(subjectName to Math.round(nota * 10) / 10f)
            }

            // Display grades
            for ((subjectName, nota) in grades) {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 8, 0, 8)
                }
                row.addView(TextView(ctx).apply {
                    text = subjectName.uppercase()
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 13f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
                })
                row.addView(TextView(ctx).apply {
                    text = nota.toString()
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    gravity = android.view.Gravity.CENTER
                    setTextColor(when {
                        nota >= 4f -> android.graphics.Color.parseColor("#30D158")
                        nota >= 3f -> android.graphics.Color.parseColor("#FF9F0A")
                        else -> android.graphics.Color.parseColor("#FF453A")
                    })
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                rootLayout.addView(row)
            }

            // Promedio
            val promedio = if (grades.isNotEmpty()) grades.map { it.second }.sum() / grades.size else 0f
            val promRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 16)
            }
            promRow.addView(TextView(ctx).apply {
                text = "PROMEDIO"
                setTextColor(android.graphics.Color.parseColor("#8E8E93"))
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
            })
            promRow.addView(TextView(ctx).apply {
                text = String.format("%.1f", promedio)
                textSize = 18f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setTextColor(when {
                    promedio >= 4f -> android.graphics.Color.parseColor("#30D158")
                    promedio >= 3f -> android.graphics.Color.parseColor("#FF9F0A")
                    else -> android.graphics.Color.parseColor("#FF453A")
                })
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            rootLayout.addView(promRow)
        }

        // ── INCAT Signature & Footer ──
        if (SessionManager.getInstance(ctx).isIncatInstitution()) {
            val dp = ctx.resources.displayMetrics.density
            rootLayout.addView(android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((160 * dp).toInt(), (1 * dp).toInt()).also {
                    it.setMargins(0, (24 * dp).toInt(), 0, 0)
                }
                setBackgroundColor(android.graphics.Color.parseColor("#8B0000"))
            })
            rootLayout.addView(TextView(ctx).apply {
                text = "AQUILES AMAYA IGUARAN"
                textSize = 11f; setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.WHITE)
                setPadding(0, (6 * dp).toInt(), 0, 0)
            })
            rootLayout.addView(TextView(ctx).apply {
                text = "RECOR"
                textSize = 10f; setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
                setPadding(0, (2 * dp).toInt(), 0, 0)
            })
            rootLayout.addView(android.view.View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (2 * dp).toInt()
                ).also { it.setMargins(0, (16 * dp).toInt(), 0, 0) }
                setBackgroundColor(android.graphics.Color.parseColor("#8B0000"))
            })
            val footerLines = arrayOf(
                "Politécnico \"INCAT\", forjando líderes para triunfar!",
                "SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740",
                "E-mail: politecnicoincat@gmail.com",
                "RIOHACHA- LA GUAJIRA"
            )
            for ((i, line) in footerLines.withIndex()) {
                rootLayout.addView(TextView(ctx).apply {
                    text = line; textSize = 10f
                    gravity = android.view.Gravity.CENTER
                    setTextColor(if (i == 0 || i == 2) android.graphics.Color.parseColor("#8B0000") else android.graphics.Color.parseColor("#CCCCCC"))
                    if (i == 0) setTypeface(null, android.graphics.Typeface.ITALIC)
                    if (i == 1 || i == 3) setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(0, (2 * dp).toInt(), 0, 0)
                })
            }
        }

        // Share all button
        rootLayout.addView(TextView(ctx).apply {
            text = "📤 Compartir todos los boletines"
            setTextColor(android.graphics.Color.parseColor("#0A84FF"))
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                val sb = StringBuilder()
                sb.appendLine("📋 BOLETÍN DE TODOS LOS ESTUDIANTES")
                sb.appendLine("📌 Programa: $courseName")
                sb.appendLine("📅 Periodo: $period")
                sb.appendLine("═".repeat(32))
                for (studentObj in students) {
                    val sUserId: Long
                    val sFullName: String
                    val sUsername: String
                    try {
                        val cls = studentObj::class.java
                        sUserId = cls.getDeclaredField("userId").apply { isAccessible = true }.getLong(studentObj)
                        sFullName = cls.getDeclaredField("fullName").apply { isAccessible = true }.get(studentObj) as? String ?: ""
                        sUsername = cls.getDeclaredField("username").apply { isAccessible = true }.get(studentObj) as? String ?: ""
                    } catch (_: Exception) { continue }
                    val name = sFullName.ifBlank { sUsername }
                    sb.appendLine("\n👤 $name")
                    sb.appendLine("─".repeat(32))
                    val stGrades = mutableListOf<Pair<String, Float>>()
                    for ((_, subjectName, gradeSheet) in subjectSheets) {
                        val tasks = gradeSheet.getAsJsonArray("tasks") ?: JsonArray()
                        val taskGrades = gradeSheet.getAsJsonArray("taskGrades") ?: JsonArray()
                        val manualGrades = gradeSheet.getAsJsonArray("manualGrades") ?: JsonArray()
                        val totalTasks = tasks.size()
                        val stTG = taskGrades.filter { it.asJsonObject.get("studentId")?.asLong == sUserId }
                        val taskVals = stTG.mapNotNull { it.asJsonObject.get("grade")?.let { g -> if (g.isJsonNull) null else g.asFloat } }
                        val taskAvg = if (totalTasks > 0) taskVals.sum() / totalTasks.toFloat() else null
                        val byType = mutableMapOf<String, MutableList<Float>>()
                        for (mge in manualGrades) {
                            val mg = mge.asJsonObject
                            if (mg.get("studentId")?.asLong == sUserId) {
                                val rawType = mg.get("gradeType")?.asString ?: continue
                                val baseType = rawType.replace(Regex("_\\d+$"), "")
                                val gradeVal = mg.get("grade")?.let { if (it.isJsonNull) null else it.asFloat } ?: continue
                                byType.getOrPut(baseType) { mutableListOf() }.add(gradeVal)
                            }
                        }
                        fun avgList(list: List<Float>?): Float? = if (!list.isNullOrEmpty()) list.sum() / list.size else null
                        val available = listOfNotNull(taskAvg, avgList(byType["participacion"]), avgList(byType["examenes"]), avgList(byType["comportamiento"]))
                        val nota = if (available.isNotEmpty()) available.sum() / available.size else 0f
                        stGrades.add(subjectName to Math.round(nota * 10) / 10f)
                    }
                    for ((subjectName, nota) in stGrades) {
                        sb.appendLine("${subjectName.padEnd(26)}$nota")
                    }
                    val prom = if (stGrades.isNotEmpty()) stGrades.map { it.second }.sum() / stGrades.size else 0f
                    sb.appendLine("PROMEDIO: ${String.format("%.1f", prom)}")
                }
                if (SessionManager.getInstance(ctx).isIncatInstitution()) {
                    sb.appendLine()
                    sb.appendLine("AQUILES AMAYA IGUARAN — RECOR")
                    sb.appendLine()
                    sb.appendLine("Politécnico \"INCAT\", forjando líderes para triunfar!")
                    sb.appendLine("SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740")
                    sb.appendLine("E-mail: politecnicoincat@gmail.com")
                    sb.appendLine("RIOHACHA- LA GUAJIRA")
                }
                GradeReportHelper.shareText(ctx, sb.toString())
            }
        })
    }

    private fun toggleDragMode(
        fabAdd: FloatingActionButton,
        fabDone: ExtendedFloatingActionButton,
        sortContainer: FrameLayout?
    ) {
        isDragMode = !isDragMode
        subjectAdapter.isDragMode = isDragMode
        dragCallback?.dragModeActive = isDragMode

        if (isDragMode) {
            fabAdd.hide()
            fabDone.show()
        } else {
            dragCallback?.stopAutoScroll()
            fabDone.hide()
            fabAdd.show()
        }
    }

    private fun saveSubjectOrder() {
        val subjects = subjectAdapter.getSubjects()
        viewLifecycleOwner.lifecycleScope.launch {
            subjects.forEachIndexed { index, subject ->
                try {
                    withContext(Dispatchers.IO) {
                        BackendApiService.updateSubject(subject.id, mapOf("orderIndex" to index))
                    }
                } catch (_: Exception) { /* silent */ }
            }
        }
    }

    private fun showSubjects(
        subjects: List<Subject>,
        recyclerView: RecyclerView,
        emptyStateContainer: View,
        subtitle: TextView
    ) {
        // Backend already filters subjects for collaborators (created + subject collaborator)
        val displaySubjects = subjects
            .sortedWith(compareBy({ it.orderIndex }, { it.createdAt }))

        if (displaySubjects.isEmpty()) {
            emptyStateContainer.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
            subtitle.visibility = View.GONE
        } else {
            emptyStateContainer.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            subtitle.text = "• ${displaySubjects.size} materia${if (displaySubjects.size != 1) "s" else ""}"
            subtitle.visibility = View.VISIBLE
            val isFirstLoad = recyclerView.visibility != View.VISIBLE
            subjectAdapter.updateSubjectsWithStats(displaySubjects, subjectStats)
            if (isFirstLoad) recyclerView.scheduleLayoutAnimation()
        }
    }

    private fun loadSubjects(
        recyclerView: RecyclerView,
        emptyStateContainer: View,
        loading: ProgressBar,
        subtitle: TextView
    ) {
        val fresh = AppCache.getSubjects(courseId)
        if (fresh != null) {
            loading.visibility = View.GONE
            allSubjects = fresh
            showSubjects(fresh, recyclerView, emptyStateContainer, subtitle)
            loadSubjectStats(fresh)
            // Only fetch from network if data is older than 20s; reactive SharedFlow handles
            // cross-device changes, so a background refresh on every resume is unnecessary.
            if (!AppCache.isSubjectsVeryFresh(courseId)) {
                loadSubjectsFromNetwork(recyclerView, emptyStateContainer, loading, subtitle)
            }
            return
        }

        loadSubjectsFromNetwork(recyclerView, emptyStateContainer, loading, subtitle)
    }

    /** Always fetches from network (bypasses cache). */
    private fun loadSubjectsFromNetwork(
        recyclerView: RecyclerView,
        emptyStateContainer: View,
        loading: ProgressBar,
        subtitle: TextView
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackendApiService.getSubjectsByCourse(courseId)
            }
            loading.visibility = View.GONE
            when (result) {
                is ApiResult.Success -> {
                    AppCache.putSubjects(courseId, result.data)
                    allSubjects = result.data
                    showSubjects(result.data, recyclerView, emptyStateContainer, subtitle)
                    loadSubjectStats(result.data)
                }
                is ApiResult.Error -> {
                    if (AppCache.getSubjectsOrStale(courseId) == null) {
                        emptyStateContainer.findViewById<TextView>(R.id.emptyStateTextView)?.text =
                            "Error al cargar materias"
                        emptyStateContainer.visibility = View.VISIBLE
                        recyclerView.visibility = View.GONE
                    }
                }
            }
        }
    }
}
