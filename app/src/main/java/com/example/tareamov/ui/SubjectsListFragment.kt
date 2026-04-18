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
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.getEnrollmentStatusOrNull
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import androidx.lifecycle.ViewModelProvider
import com.example.tareamov.viewmodel.CourseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import com.google.gson.JsonArray
import com.google.gson.JsonObject

class SubjectsListFragment : Fragment() {

    private var courseId: Long = -1
    private var courseName: String = ""
    private var isCreator: Boolean = false
    private var hasAccess: Boolean = false
    private var isCollaboratorOnly: Boolean = false
    private lateinit var subjectAdapter: SubjectAdapter
    private var allSubjects: List<Subject> = emptyList()
    private var subjectStats: Map<Long, SubjectWithStats> = emptyMap()
    private var subjectsDataObserverAttached: Boolean = false
    private var subjectsDataBound: Boolean = false
    private var subjectBlocks: Map<Long, String> = emptyMap()

    // Bulletin state
    private var openAllBulletinsOnLoad: Boolean = false

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
        }
        isCreator = SessionManager.getInstance(requireContext()).run { hasRole(3) || hasRole(4) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_subjects_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupSubjectsView(view)

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

        headerTitle.text = "Mis Materias"

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
                if (isCollaboratorOnly) {
                    val userId = SessionManager.getInstance(requireContext()).getUserId()
                    if (subject.createdBy != userId) {
                        Toast.makeText(requireContext(), "Solo puedes acceder a las materias que creaste", Toast.LENGTH_SHORT).show()
                        return@SubjectAdapter
                    }
                }
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
        super.onDestroyView()
        dragCallback?.stopAutoScroll()
        itemTouchHelper = null
        dragCallback = null
        isDragMode = false
        subjectsDataObserverAttached = false
    }

    private fun bindSubjectsData(view: View) {
        subjectsDataBound = true
        checkCourseDeadline(view)
        loadSubjectBlocks()
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

        // ── Pre-compute period and all students' grades (for export) ──
        val now = java.util.Calendar.getInstance()
        val semester = if (now.get(java.util.Calendar.MONTH) < 6) "I" else "II"
        val period = "$semester PERIODO ${now.get(java.util.Calendar.YEAR)}-${if (semester == "I") "A" else "B"}"
        val isIncat = SessionManager.getInstance(ctx).isIncatInstitution()
        fun computeStudentGradesAll(sUserId: Long): List<Pair<String, Float>> {
            val result = mutableListOf<Pair<String, Float>>()
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
                result.add(subjectName to Math.round(nota * 10) / 10f)
            }
            return result
        }
        val studentsGradesList = mutableListOf<Triple<String, String, List<Pair<String, Float>>>>()
        for (studentObj in students) {
            try {
                val cls = studentObj::class.java
                val sUserId = cls.getDeclaredField("userId").apply { isAccessible = true }.getLong(studentObj)
                val sFullName = cls.getDeclaredField("fullName").apply { isAccessible = true }.get(studentObj) as? String ?: ""
                val sUsername = cls.getDeclaredField("username").apply { isAccessible = true }.get(studentObj) as? String ?: ""
                val sCedula = cls.getDeclaredField("cedula").apply { isAccessible = true }.get(studentObj) as? String ?: ""
                studentsGradesList.add(Triple(sFullName.ifBlank { sUsername }, sCedula, computeStudentGradesAll(sUserId)))
            } catch (_: Exception) { /* skip */ }
        }

        // Back button
        rootLayout.addView(TextView(ctx).apply {
            text = "← Volver a la lista"
            setTextColor(android.graphics.Color.parseColor("#0A84FF"))
            textSize = 14f
            setPadding(0, 0, 0, 24)
            setOnClickListener { dialog.dismiss() }
        })

        // ── Export buttons row (PDF, Excel, Word) ──
        val dpAll = ctx.resources.displayMetrics.density
        val exportRowAll = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, (16 * dpAll).toInt())
        }
        fun makeBtnBgAll(colorHex: String) = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 10f * dpAll; setColor(android.graphics.Color.parseColor(colorHex))
        }
        exportRowAll.addView(TextView(ctx).apply {
            text = "📄 PDF"
            setTextColor(android.graphics.Color.parseColor("#FF453A"))
            textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding((10 * dpAll).toInt(), (8 * dpAll).toInt(), (10 * dpAll).toInt(), (8 * dpAll).toInt())
            background = makeBtnBgAll("#1AFF453A")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (6 * dpAll).toInt() }
            setOnClickListener { shareAllBulletinsPdf(ctx, courseName, period, studentsGradesList, isIncat) }
        })
        exportRowAll.addView(TextView(ctx).apply {
            text = "📊 Excel"
            setTextColor(android.graphics.Color.parseColor("#30D158"))
            textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding((10 * dpAll).toInt(), (8 * dpAll).toInt(), (10 * dpAll).toInt(), (8 * dpAll).toInt())
            background = makeBtnBgAll("#1A30D158")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (6 * dpAll).toInt() }
            setOnClickListener { shareAllBulletinsCsv(ctx, courseName, period, studentsGradesList) }
        })
        exportRowAll.addView(TextView(ctx).apply {
            text = "📝 Word"
            setTextColor(android.graphics.Color.parseColor("#5AC8FA"))
            textSize = 13f; setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding((10 * dpAll).toInt(), (8 * dpAll).toInt(), (10 * dpAll).toInt(), (8 * dpAll).toInt())
            background = makeBtnBgAll("#1A5AC8FA")
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { shareAllBulletinsWord(ctx, courseName, period, studentsGradesList, isIncat) }
        })
        rootLayout.addView(exportRowAll)

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
                text = "RECTOR"
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
                for ((name, _, sGrades) in studentsGradesList) {
                    sb.appendLine("\n👤 $name")
                    sb.appendLine("─".repeat(32))
                    for ((subjectName, nota) in sGrades) {
                        sb.appendLine("${subjectName.padEnd(26)}$nota")
                    }
                    val prom = if (sGrades.isNotEmpty()) sGrades.map { it.second }.sum() / sGrades.size else 0f
                    sb.appendLine("PROMEDIO: ${String.format("%.1f", prom)}")
                }
                if (isIncat) {
                    sb.appendLine()
                    sb.appendLine("AQUILES AMAYA IGUARAN — RECTOR")
                    sb.appendLine()
                    sb.appendLine("Politécnico \"INCAT\", forjando líderes para triunfar!")
                    sb.appendLine("SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740")
                    sb.appendLine("E-mail: politecnicoincat@gmail.com")
                    sb.appendLine("RIOHACHA- LA GUAJIRA")
                }
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
                    }
                    ctx.startActivity(android.content.Intent.createChooser(intent, "Compartir"))
                } catch (_: Exception) { showSafeToast("Error al compartir") }
            }
        })
    }

    // ── Bulletin export helpers ─────────────────────────────────────────────────

    private fun escapeHtml(text: String) = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun shareBulletinPdf(
        ctx: android.content.Context,
        courseName: String,
        studentName: String,
        cedula: String,
        period: String,
        grades: List<Pair<String, Float>>,
        isIncat: Boolean
    ) {
        try {
            val doc = android.graphics.pdf.PdfDocument()
            val pageWidth = 595; val pageHeight = 842
            val margin = 40f
            val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            var y = margin
            val contentWidth = pageWidth - margin * 2

            val titlePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#1E1E1E"); textSize = 20f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); isAntiAlias = true
            }
            val normalPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#333333"); textSize = 12f; isAntiAlias = true }
            val mutedPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#888888"); textSize = 10f; isAntiAlias = true }
            val linePaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#CCCCCC"); strokeWidth = 1f }

            if (isIncat) {
                canvas.drawText("POLITECNICO INSTITUCIONAL DEL CARIBE \"INCAT\"", margin, y + 12f, mutedPaint)
                y += 18f
            }
            canvas.drawText("BOLETÍN DE NOTAS", margin, y + 20f, titlePaint); y += 30f
            canvas.drawText("Programa: $courseName", margin, y + 12f, normalPaint); y += 20f
            canvas.drawText("Estudiante: $studentName", margin, y + 12f, normalPaint); y += 20f
            if (cedula.isNotBlank()) { canvas.drawText("Identificación: $cedula", margin, y + 12f, normalPaint); y += 20f }
            canvas.drawText("Periodo: $period", margin, y + 12f, normalPaint); y += 28f
            canvas.drawLine(margin, y, margin + contentWidth, y, linePaint); y += 16f

            val gradeRight = margin + contentWidth
            for ((subject, nota) in grades) {
                val noteColor = when { nota >= 4f -> android.graphics.Color.parseColor("#34C759"); nota >= 3f -> android.graphics.Color.parseColor("#FF9500"); else -> android.graphics.Color.parseColor("#FF453A") }
                canvas.drawText(subject.take(55), margin, y + 12f, normalPaint)
                canvas.drawText(String.format("%.1f", nota), gradeRight, y + 12f, android.graphics.Paint().apply {
                    textSize = 12f; color = noteColor
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    isAntiAlias = true; textAlign = android.graphics.Paint.Align.RIGHT
                })
                y += 20f
            }
            y += 10f; canvas.drawLine(margin, y, margin + contentWidth, y, linePaint); y += 20f
            val avg = if (grades.isNotEmpty()) grades.map { it.second }.sum() / grades.size else 0f
            val avgColor = when { avg >= 4f -> android.graphics.Color.parseColor("#34C759"); avg >= 3f -> android.graphics.Color.parseColor("#FF9500"); else -> android.graphics.Color.parseColor("#FF453A") }
            canvas.drawText("PROMEDIO:", margin, y + 12f, android.graphics.Paint().apply {
                textSize = 13f; color = android.graphics.Color.parseColor("#333333")
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); isAntiAlias = true
            })
            canvas.drawText(String.format("%.1f", avg), gradeRight, y + 12f, android.graphics.Paint().apply {
                textSize = 14f; color = avgColor
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                isAntiAlias = true; textAlign = android.graphics.Paint.Align.RIGHT
            })
            if (isIncat) {
                y += 60f
                canvas.drawText("AQUILES AMAYA IGUARAN — RECTOR", margin, y + 12f, mutedPaint); y += 20f
                canvas.drawLine(margin, y, margin + 200f, y, linePaint); y += 28f
                canvas.drawText("Politécnico \"INCAT\", forjando líderes para triunfar!", margin, y + 12f, mutedPaint); y += 14f
                canvas.drawText("SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740", margin, y + 12f, mutedPaint); y += 14f
                canvas.drawText("politecnicoincat@gmail.com | RIOHACHA- LA GUAJIRA", margin, y + 12f, mutedPaint)
            }
            doc.finishPage(page)
            val file = java.io.File(ctx.cacheDir, "boletin_${System.currentTimeMillis()}.pdf")
            java.io.FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(intent, "Compartir"))
        } catch (_: Exception) { showSafeToast("Error al compartir") }
        } catch (_: Exception) { showSafeToast("Error al generar PDF") }
    }

    private fun shareBulletinCsv(
        ctx: android.content.Context,
        courseName: String,
        studentName: String,
        cedula: String,
        period: String,
        grades: List<Pair<String, Float>>
    ) {
        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\n")
            sb.append("<Worksheet ss:Name=\"Boletín\"><Table>\n")
            fun cell(v: String) = "<Cell><Data ss:Type=\"String\">${v.replace("&", "&amp;").replace("<", "&lt;")}</Data></Cell>"
            fun row(vararg cells: String) { sb.append("<Row>"); cells.forEach { sb.append(cell(it)) }; sb.append("</Row>\n") }
            row("BOLETÍN DE NOTAS"); row("Programa", courseName)
            row("Estudiante", studentName)
            if (cedula.isNotBlank()) row("Identificación", cedula)
            row("Periodo", period); row(""); row("Materia", "Nota")
            for ((subj, nota) in grades) row(subj, String.format("%.1f", nota))
            row("")
            val avg = if (grades.isNotEmpty()) grades.map { it.second }.sum() / grades.size else 0f
            row("PROMEDIO", String.format("%.1f", avg))
            sb.append("</Table></Worksheet></Workbook>")
            val file = java.io.File(ctx.cacheDir, "boletin_${System.currentTimeMillis()}.xls")
            file.writeText(sb.toString(), Charsets.UTF_8)
            try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/vnd.ms-excel"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(intent, "Compartir"))
        } catch (_: Exception) { showSafeToast("Error al compartir") }
        } catch (_: Exception) { showSafeToast("Error al generar Excel") }
    }

    private fun shareBulletinWord(
        ctx: android.content.Context,
        courseName: String,
        studentName: String,
        cedula: String,
        period: String,
        grades: List<Pair<String, Float>>,
        isIncat: Boolean
    ) {
        try {
            val sb = StringBuilder()
            sb.append("<html><head><meta charset=\"utf-8\"><style>body{font-family:Arial;margin:40px}h2{color:#1E1E1E}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ccc;padding:6px}</style></head><body>")
            if (isIncat) sb.append("<p style='text-align:center;color:#8B0000;font-weight:bold'>POLITECNICO INSTITUCIONAL DEL CARIBE \"INCAT\"</p>")
            sb.append("<h2>BOLETÍN DE NOTAS</h2>")
            sb.append("<p><b>Programa:</b> $courseName</p><p><b>Estudiante:</b> $studentName</p>")
            if (cedula.isNotBlank()) sb.append("<p><b>Identificación:</b> $cedula</p>")
            sb.append("<p><b>Periodo:</b> $period</p><hr>")
            sb.append("<table><tr><th align='left'>Materia</th><th align='right'>Nota</th></tr>")
            for ((subj, nota) in grades) {
                val c = when { nota >= 4f -> "#34C759"; nota >= 3f -> "#FF9500"; else -> "#FF0000" }
                sb.append("<tr><td>$subj</td><td align='right'><b style='color:$c'>${String.format("%.1f", nota)}</b></td></tr>")
            }
            val avg = if (grades.isNotEmpty()) grades.map { it.second }.sum() / grades.size else 0f
            val ac = when { avg >= 4f -> "#34C759"; avg >= 3f -> "#FF9500"; else -> "#FF0000" }
            sb.append("<tr><td><b>PROMEDIO</b></td><td align='right'><b style='color:$ac'>${String.format("%.1f", avg)}</b></td></tr></table>")
            if (isIncat) {
                sb.append("<br><p><b>AQUILES AMAYA IGUARAN</b> — RECTOR</p>")
                sb.append("<p style='color:#8B0000;font-style:italic'>Politécnico \"INCAT\", forjando líderes para triunfar!</p>")
                sb.append("<p>SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740</p>")
                sb.append("<p>politecnicoincat@gmail.com | RIOHACHA- LA GUAJIRA</p>")
            }
            sb.append("</body></html>")
            val file = java.io.File(ctx.cacheDir, "boletin_${System.currentTimeMillis()}.doc")
            file.writeText(sb.toString(), Charsets.UTF_8)
            try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/msword"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(intent, "Compartir"))
        } catch (_: Exception) { showSafeToast("Error al compartir") }
        } catch (_: Exception) { showSafeToast("Error al generar Word") }
    }

    private fun shareAllBulletinsPdf(
        ctx: android.content.Context,
        courseName: String,
        period: String,
        studentsGradesList: List<Triple<String, String, List<Pair<String, Float>>>>,
        isIncat: Boolean
    ) {
        try {
            val doc = android.graphics.pdf.PdfDocument()
            val pageWidth = 595; val pageHeight = 842
            val margin = 40f
            val contentWidth = pageWidth - margin * 2
            var pageNum = 0

            val titlePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#1E1E1E"); textSize = 18f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); isAntiAlias = true
            }
            val normalPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#333333"); textSize = 12f; isAntiAlias = true }
            val mutedPaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#888888"); textSize = 10f; isAntiAlias = true }
            val linePaint = android.graphics.Paint().apply { color = android.graphics.Color.parseColor("#CCCCCC"); strokeWidth = 1f }

            for ((sName, sCedula, grades) in studentsGradesList) {
                pageNum++
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                val page = doc.startPage(pageInfo)
                val canvas = page.canvas
                var y = margin
                val gradeRight = margin + contentWidth

                if (isIncat) { canvas.drawText("POLITECNICO INSTITUCIONAL DEL CARIBE \"INCAT\"", margin, y + 12f, mutedPaint); y += 18f }
                canvas.drawText("BOLETÍN DE NOTAS", margin, y + 18f, titlePaint); y += 28f
                canvas.drawText("Programa: $courseName", margin, y + 12f, normalPaint); y += 20f
                canvas.drawText("Estudiante: $sName", margin, y + 12f, normalPaint); y += 20f
                if (sCedula.isNotBlank()) { canvas.drawText("Identificación: $sCedula", margin, y + 12f, normalPaint); y += 20f }
                canvas.drawText("Periodo: $period", margin, y + 12f, normalPaint); y += 26f
                canvas.drawLine(margin, y, margin + contentWidth, y, linePaint); y += 16f

                for ((subj, nota) in grades) {
                    val noteColor = when { nota >= 4f -> android.graphics.Color.parseColor("#34C759"); nota >= 3f -> android.graphics.Color.parseColor("#FF9500"); else -> android.graphics.Color.parseColor("#FF453A") }
                    canvas.drawText(subj.take(55), margin, y + 12f, normalPaint)
                    canvas.drawText(String.format("%.1f", nota), gradeRight, y + 12f, android.graphics.Paint().apply {
                        textSize = 12f; color = noteColor
                        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                        isAntiAlias = true; textAlign = android.graphics.Paint.Align.RIGHT
                    }); y += 20f
                }
                y += 10f; canvas.drawLine(margin, y, margin + contentWidth, y, linePaint); y += 20f
                val avg = if (grades.isNotEmpty()) grades.map { it.second }.sum() / grades.size else 0f
                val avgColor = when { avg >= 4f -> android.graphics.Color.parseColor("#34C759"); avg >= 3f -> android.graphics.Color.parseColor("#FF9500"); else -> android.graphics.Color.parseColor("#FF453A") }
                canvas.drawText("PROMEDIO:", margin, y + 12f, android.graphics.Paint().apply {
                    textSize = 13f; color = android.graphics.Color.parseColor("#333333")
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD); isAntiAlias = true
                })
                canvas.drawText(String.format("%.1f", avg), gradeRight, y + 12f, android.graphics.Paint().apply {
                    textSize = 14f; color = avgColor
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                    isAntiAlias = true; textAlign = android.graphics.Paint.Align.RIGHT
                })
                if (isIncat) {
                    y += 60f
                    canvas.drawText("AQUILES AMAYA IGUARAN — RECTOR", margin, y + 12f, mutedPaint); y += 20f
                    canvas.drawLine(margin, y, margin + 200f, y, linePaint); y += 28f
                    canvas.drawText("Politécnico \"INCAT\", forjando líderes para triunfar!", margin, y + 12f, mutedPaint); y += 14f
                    canvas.drawText("SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740", margin, y + 12f, mutedPaint); y += 14f
                    canvas.drawText("politecnicoincat@gmail.com | RIOHACHA- LA GUAJIRA", margin, y + 12f, mutedPaint)
                }
                doc.finishPage(page)
            }
            val file = java.io.File(ctx.cacheDir, "boletines_${System.currentTimeMillis()}.pdf")
            java.io.FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(intent, "Compartir"))
        } catch (_: Exception) { showSafeToast("Error al compartir") }
        } catch (_: Exception) { showSafeToast("Error al generar PDF") }
    }

    private fun shareAllBulletinsCsv(
        ctx: android.content.Context,
        courseName: String,
        period: String,
        studentsGradesList: List<Triple<String, String, List<Pair<String, Float>>>>
    ) {
        try {
            val sb = StringBuilder()
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            sb.append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">\n")
            fun cell(v: String) = "<Cell><Data ss:Type=\"String\">${v.replace("&", "&amp;").replace("<", "&lt;")}</Data></Cell>"
            fun row(vararg cells: String) { sb.append("<Row>"); cells.forEach { sb.append(cell(it)) }; sb.append("</Row>\n") }
            for ((sName, sCedula, grades) in studentsGradesList) {
                val sheetName = sName.take(28).replace(Regex("[\\[\\]\\*/?:\\\\]"), "")
                sb.append("<Worksheet ss:Name=\"${sheetName.replace("&","&amp;").replace("\"","")}\"><Table>\n")
                row("Programa", courseName); row("Estudiante", sName)
                if (sCedula.isNotBlank()) row("Identificación", sCedula)
                row("Periodo", period); row(""); row("Materia", "Nota")
                for ((subj, nota) in grades) row(subj, String.format("%.1f", nota))
                row("")
                val avg = if (grades.isNotEmpty()) grades.map { it.second }.sum() / grades.size else 0f
                row("PROMEDIO", String.format("%.1f", avg))
                sb.append("</Table></Worksheet>\n")
            }
            sb.append("</Workbook>")
            val file = java.io.File(ctx.cacheDir, "boletines_${System.currentTimeMillis()}.xls")
            file.writeText(sb.toString(), Charsets.UTF_8)
            try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/vnd.ms-excel"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(intent, "Compartir"))
        } catch (_: Exception) { showSafeToast("Error al compartir") }
        } catch (_: Exception) { showSafeToast("Error al generar Excel") }
    }

    private fun shareAllBulletinsWord(
        ctx: android.content.Context,
        courseName: String,
        period: String,
        studentsGradesList: List<Triple<String, String, List<Pair<String, Float>>>>,
        isIncat: Boolean
    ) {
        try {
            val sb = StringBuilder()
            sb.append("<html><head><meta charset=\"utf-8\"><style>body{font-family:Arial;margin:40px}h2{color:#1E1E1E}table{border-collapse:collapse;width:100%;margin-bottom:20px}td,th{border:1px solid #ccc;padding:6px}.page-break{page-break-after:always}</style></head><body>")
            if (isIncat) sb.append("<p style='text-align:center;color:#8B0000;font-weight:bold'>POLITECNICO INSTITUCIONAL DEL CARIBE \"INCAT\"</p>")
            sb.append("<h2>BOLETINES DE NOTAS — Programa: $courseName</h2><p><b>Periodo:</b> $period</p>")
            for ((idx, entry) in studentsGradesList.withIndex()) {
                val (sName, sCedula, grades) = entry
                if (idx > 0) sb.append("<div class='page-break'></div>")
                sb.append("<h3>$sName</h3>")
                if (sCedula.isNotBlank()) sb.append("<p><b>Identificación:</b> $sCedula</p>")
                sb.append("<table><tr><th align='left'>Materia</th><th align='right'>Nota</th></tr>")
                for ((subj, nota) in grades) {
                    val c = when { nota >= 4f -> "#34C759"; nota >= 3f -> "#FF9500"; else -> "#FF0000" }
                    sb.append("<tr><td>$subj</td><td align='right'><b style='color:$c'>${String.format("%.1f", nota)}</b></td></tr>")
                }
                val avg = if (grades.isNotEmpty()) grades.map { it.second }.sum() / grades.size else 0f
                val ac = when { avg >= 4f -> "#34C759"; avg >= 3f -> "#FF9500"; else -> "#FF0000" }
                sb.append("<tr><td><b>PROMEDIO</b></td><td align='right'><b style='color:$ac'>${String.format("%.1f", avg)}</b></td></tr></table>")
                if (isIncat) sb.append("<p><b>AQUILES AMAYA IGUARAN</b> — RECTOR</p>")
            }
            if (isIncat) {
                sb.append("<p style='color:#8B0000;font-style:italic'>Politécnico \"INCAT\", forjando líderes para triunfar!</p>")
                sb.append("<p>SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740</p>")
                sb.append("<p>politecnicoincat@gmail.com | RIOHACHA- LA GUAJIRA</p>")
            }
            sb.append("</body></html>")
            val file = java.io.File(ctx.cacheDir, "boletines_${System.currentTimeMillis()}.doc")
            file.writeText(sb.toString(), Charsets.UTF_8)
            try {
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/msword"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(android.content.Intent.createChooser(intent, "Compartir"))
        } catch (_: Exception) { showSafeToast("Error al compartir") }
        } catch (_: Exception) { showSafeToast("Error al generar Word") }
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
        // Client-side filter: collaborators only see subjects they created
        val displaySubjects = (if (isCollaboratorOnly) {
            val userId = SessionManager.getInstance(requireContext()).getUserId()
            subjects.filter { it.createdBy == userId }
        } else {
            subjects
        }).sortedWith(compareBy({ it.orderIndex }, { it.createdAt }))

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
