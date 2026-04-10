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
        isCreator = SessionManager.getInstance(requireContext()).hasRole(3)
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

            if (!sessionManager.hasRole(3) && !collaboratorAccess) {
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
        if (!sessionManager.hasRole(2) || sessionManager.hasRole(3)) return false
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
                    val canModifyThis = sessionManager.hasRole(3) || (subject.createdBy == sessionManager.getUserId())
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

        // Botón de reporte de notas
        val reportButtonContainer = view.findViewById<FrameLayout>(R.id.reportButtonContainer)
        val reportButton = view.findViewById<ImageButton>(R.id.reportButton)
        reportButtonContainer.visibility = View.VISIBLE
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
        reportButton.setOnClickListener { showReportBottomSheet() }
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
        if (sessionManager.hasRole(3)) return // Admins are not restricted
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
        if (sessionManager.hasRole(3)) return // Admins are not restricted

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
            hasAccess = sessionManager.hasRole(3)
            isCollaboratorOnly = false
            if (hasAccess) {
                subjectAdapter.isAdmin = sessionManager.hasRole(3)
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

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (topics, tasks, submissions) = withContext(Dispatchers.IO) {
                    val t1 = async { BackendApiService.getTopicsByCourse(courseId) }
                    val t2 = async { BackendApiService.getTasksByCourse(courseId) }
                    val t3 = async { BackendApiService.getMySubmissions(1, 500) }
                    Triple(t1.await(), t2.await(), t3.await())
                }

                val topicList = if (topics is ApiResult.Success) topics.data else emptyList()
                val taskList = if (tasks is ApiResult.Success) tasks.data else emptyList()
                val subList = if (submissions is ApiResult.Success) submissions.data else emptyList()

                val report = GradeReportHelper.buildReport(allSubjects, taskList, subList, topicList)

                tvLoading.visibility = View.GONE
                contentLayout.visibility = View.VISIBLE

                // Summary
                tvSubjectCount.text = "${allSubjects.size}"
                tvTaskCount.text = "${report.sumOf { it.tasks.size }}"
                tvGradedCount.text = "${report.sumOf { it.tasks.count { t -> t.grade != null } }}"
                val allGraded = report.flatMap { it.tasks }.mapNotNull { it.grade }
                val avg = if (allGraded.isNotEmpty()) String.format("%.1f", allGraded.average()) else "—"
                tvAverage.text = avg
                if (allGraded.isNotEmpty()) {
                    val a = allGraded.average().toFloat()
                    tvAverage.setTextColor(android.graphics.Color.parseColor(
                        if (a >= 4f) "#34C759" else if (a >= 3f) "#FF9500" else "#FF453A"
                    ))
                }

                // Subject list
                reportListContainer.removeAllViews()
                for (group in report) {
                    val dp = resources.displayMetrics.density
                    val subjectTitle = TextView(ctx).apply {
                        text = "${group.subjectName}  —  ${if (group.average != null) String.format("%.1f", group.average) else "—"}"
                        setTextColor(android.graphics.Color.parseColor("#BF5AF2"))
                        textSize = 14f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, (12 * dp).toInt(), 0, (4 * dp).toInt())
                    }
                    reportListContainer.addView(subjectTitle)

                    if (group.tasks.isEmpty()) {
                        val noTask = TextView(ctx).apply {
                            text = "   Sin tareas"
                            setTextColor(android.graphics.Color.parseColor("#636366"))
                            textSize = 13f
                        }
                        reportListContainer.addView(noTask)
                    }
                    for (task in group.tasks) {
                        val gradeStr = if (task.grade != null) String.format("%.1f", task.grade) else "—"
                        val color = if (task.grade != null) {
                            if (task.grade >= 4f) "#34C759" else if (task.grade >= 3f) "#FF9500" else "#FF453A"
                        } else "#636366"
                        val row = TextView(ctx).apply {
                            text = "   • ${task.title}:  $gradeStr"
                            setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                            textSize = 13f
                            setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                        }
                        reportListContainer.addView(row)
                    }
                }

                // Export buttons
                btnPdf.setOnClickListener {
                    val file = GradeReportHelper.generatePDF(ctx, report)
                    if (file != null) GradeReportHelper.shareFile(ctx, file, "application/pdf")
                    else showSafeToast("Error al generar PDF")
                }
                btnCsv.setOnClickListener {
                    val file = GradeReportHelper.generateCSV(ctx, report)
                    if (file != null) GradeReportHelper.shareFile(ctx, file, "text/csv")
                    else showSafeToast("Error al generar CSV")
                }
                btnShare.setOnClickListener {
                    val text = GradeReportHelper.buildShareText(report)
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
