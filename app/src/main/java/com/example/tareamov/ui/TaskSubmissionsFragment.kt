package com.example.tareamov.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.data.entity.FileContext
import com.example.tareamov.data.entity.Notification
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.util.CalificationManager
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
// Avatar removed from item layout; CircleImageView no longer required here

class TaskSubmissionsFragment : Fragment() {

    companion object {
        private const val R2_PUBLIC_BASE_URL = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SubmissionsAdapter
    private lateinit var sessionManager: SessionManager

    private var taskId: Long = -1
    private var taskName: String = ""
    private var courseCreatorUsername: String? = null
    private var isCourseCreator: Boolean = false
    private var selectedFileUri: Uri? = null
    private var hasUserSubmitted = false
    private var userSubmission: TaskSubmission? = null
    private var isSubmitting: Boolean = false
    private var hasResolvedTaskCreatorAccess: Boolean = false
    private var scrollToSubmissionUsername: String? = null
    private var allSubmissions: List<TaskSubmission> = emptyList()
    private val usernameCache = mutableMapOf<Long, String>()
    private val personaNameCache = mutableMapOf<Long, String>()
    
    // Información de la tarea, tema y curso
    private var taskDescription: String = ""
    private var topicName: String = ""
    private var courseTitle: String = ""
    private var courseDescription: String = ""
    private var taskDueDate: String? = null
    
    // 🔥 Datos de la submission actual para pasar al ChatBotFragment
    private var currentSubmissionTaskId: Long = -1L
    private var currentSubmissionStudentId: Long = -1L
    private var currentSubmissionFileUri: String = ""

    private fun canCurrentUserManageTask(subjectCreatorId: Long, courseCreatorId: Long): Boolean {
        val currentUserId = sessionManager.getUserId()
        if (currentUserId <= 0L) return isCourseCreator
        if (sessionManager.hasRole(3)) return true
        if (isCourseCreator) return true
        return currentUserId == subjectCreatorId || currentUserId == courseCreatorId
    }

    // Progress UI elements removed from layout; access via safe findViewById when needed

    // Helper to find views by resource name to avoid direct references to removed IDs
    private inline fun <reified T : View> findViewByName(name: String): T? {
        val pkg = context?.packageName ?: return null
        val id = context?.resources?.getIdentifier(name, "id", pkg) ?: 0
        return if (id != 0) view?.findViewById(id) as? T else null
    }

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Es un archivo local o remoto (Drive), procesarlo normalmente
            selectedFileUri = uri
            // Usar getFileName() para mostrar el nombre real del archivo
            val displayFileName = getFileName(uri) ?: uri.lastPathSegment ?: "Archivo seleccionado"
            view?.findViewById<TextView>(R.id.selectedFileTextView)?.text = displayFileName
            // Enable submit button
            view?.findViewById<Button>(R.id.submitButton)?.isEnabled = true
            Log.d("TaskSubmissionsFragment", "📎 Archivo seleccionado: $displayFileName")
            Log.d("TaskSubmissionsFragment", "📎 URI: $uri")
            Log.d("TaskSubmissionsFragment", "📎 URI scheme: ${uri.scheme}, authority: ${uri.authority}")

            // Take persistable URI permission for both local and cloud storage
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                requireContext().contentResolver.takePersistableUriPermission(uri, flags)
                Log.d("TaskSubmissionsFragment", "✅ Permisos URI persistidos correctamente")
            } catch (e: SecurityException) {
                // This is expected for some URIs (like Google Drive streaming URIs)
                // The file can still be read, just not persistently
                Log.w("TaskSubmissionsFragment", "⚠️ No se pudieron persistir permisos URI (normal para algunos archivos en la nube): ${e.message}")
            } catch (e: Exception) {
                Log.w("TaskSubmissionsFragment", "⚠️ Error al persistir permisos: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            taskId = it.getLong("taskId", -1)
            taskName = it.getString("taskName", "")
            courseCreatorUsername = it.getString("courseCreatorUsername")
            scrollToSubmissionUsername = it.getString("scrollToSubmissionUsername")
            Log.d("TaskSubmissionsFragment", "Received scrollToSubmissionUsername: $scrollToSubmissionUsername")
        }
        sessionManager = SessionManager.getInstance(requireContext())

        val hasEditAccess = arguments?.getBoolean("hasEditAccess", false) ?: false
        isCourseCreator = hasEditAccess
        hasResolvedTaskCreatorAccess = hasEditAccess
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_task_submissions, container, false)

        val titleTextView = view.findViewById<TextView>(R.id.taskTitleTextView)
        titleTextView.text = taskName

        // Progress and upload UI are managed dynamically; views may be absent after removal

        recyclerView = view.findViewById(R.id.submissionsRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(context)

        adapter = SubmissionsAdapter(emptyList()) { submission, grade, feedback ->
            updateSubmissionGrade(submission, grade, feedback)
        }
        recyclerView.adapter = adapter

        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Configure visibility based on user role
        val searchEditText = view.findViewById<EditText>(R.id.searchEditText)

        if (!hasResolvedTaskCreatorAccess) {
            findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
            view.findViewById<LinearLayout>(R.id.uploadSection)?.visibility = View.GONE
            view.findViewById<View>(R.id.uploadDivider)?.visibility = View.GONE
            searchEditText?.visibility = View.GONE
        } else if (isCourseCreator) {
            findViewByName<LinearLayout>("progressSection")?.visibility = View.VISIBLE
            view.findViewById<LinearLayout>(R.id.uploadSection)?.visibility = View.GONE
            view.findViewById<View>(R.id.uploadDivider)?.visibility = View.GONE
            searchEditText?.visibility = View.VISIBLE
            setupSearchBar(searchEditText)
            loadTaskProgress()
        } else {
            // Regular student: show upload section
            findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
            view.findViewById<LinearLayout>(R.id.uploadSection)?.visibility = View.VISIBLE
            view.findViewById<View>(R.id.uploadDivider)?.visibility = View.VISIBLE
            
            // Setup upload buttons
            setupUploadSection(view)

            // Check if user has already submitted this task
            val statusTextView = view.findViewById<TextView>(R.id.uploadStatusTextView)
            checkUserSubmission(statusTextView)
        }

        loadSubmissions()
        checkAndApplyPendingCalifications()
        loadTaskWithCourseInfo()
        return view
    }
    
    /**
     * Configura los botones y listeners de la sección de subida de archivos
     */
    private fun setupUploadSection(view: View) {
        val selectFileButton = view.findViewById<Button>(R.id.selectFileButton)
        val submitButton = view.findViewById<Button>(R.id.submitButton)
        val selectedFileTextView = view.findViewById<TextView>(R.id.selectedFileTextView)
        val githubUrlEditText = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.githubUrlEditText)
        val submitGitHubButton = view.findViewById<Button>(R.id.submitGitHubButton)
        
        selectFileButton?.setOnClickListener {
            openFilePicker()
        }
        
        submitButton?.setOnClickListener {
            if (selectedFileUri != null && !isSubmitting) {
                submitTaskFile()
            } else {
                Toast.makeText(context, "Por favor selecciona un archivo primero", Toast.LENGTH_SHORT).show()
            }
        }
        
        submitGitHubButton?.setOnClickListener {
            if (githubUrlEditText != null) {
                submitGitHubRepository(githubUrlEditText)
            }
        }
    }

    private fun setupSearchBar(searchEditText: EditText?) {
        searchEditText?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterSubmissions(s?.toString().orEmpty())
            }
        })
    }

    private fun filterSubmissions(query: String) {
        if (query.isBlank()) {
            adapter.updateSubmissions(allSubmissions)
            view?.findViewById<TextView>(R.id.emptyStateTextView)?.visibility =
                if (allSubmissions.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (allSubmissions.isEmpty()) View.GONE else View.VISIBLE
            return
        }
        val lower = query.lowercase(Locale.getDefault())
        val filtered = allSubmissions.filter { sub ->
            val name = usernameCache[sub.studentId]?.lowercase(Locale.getDefault()).orEmpty()
            val personaName = personaNameCache[sub.studentId]?.lowercase(Locale.getDefault()).orEmpty()
            name.contains(lower) || personaName.contains(lower)
        }
        adapter.updateSubmissions(filtered)
        val emptyView = view?.findViewById<TextView>(R.id.emptyStateTextView)
        if (filtered.isEmpty()) {
            emptyView?.text = "No se encontraron entregas para \"$query\""
            emptyView?.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyView?.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }
    }

    private fun resolveUsernames(submissions: List<TaskSubmission>) {
        // First, use enriched studentUsername from backend if available
        for (sub in submissions) {
            if (!sub.studentUsername.isNullOrBlank() && !usernameCache.containsKey(sub.studentId)) {
                usernameCache[sub.studentId] = sub.studentUsername!!
            }
        }
        // For any remaining unresolved, fetch individually
        val unresolvedIds = submissions.map { it.studentId }.distinct()
            .filter { !usernameCache.containsKey(it) }
        if (unresolvedIds.isEmpty()) return
        CoroutineScope(Dispatchers.IO).launch {
            unresolvedIds.forEach { studentId ->
                try {
                    val result = BackendApiService.getUserById(studentId)
                    if (result is ApiResult.Success) {
                        val name = result.data?.usuario?.takeIf { it.isNotBlank() } ?: "Usuario $studentId"
                        usernameCache[studentId] = name
                    }
                } catch (e: Exception) {
                    Log.w("TaskSubmissionsFragment", "Failed to resolve username for $studentId", e)
                }
            }
        }
    }

    /**
     * Verifica si hay calificaciones pendientes del chat y las aplica automáticamente
     */
    private fun checkAndApplyPendingCalifications() {
        val calificationManager = CalificationManager.getInstance(requireContext())
        
        if (calificationManager.hasPendingCalification()) {
            val calificationData = calificationManager.getPendingCalification()
            
            if (calificationData != null) {
                Log.d("TaskSubmissionsFragment", "Calificación pendiente encontrada: Grade=${calificationData.grade}, Feedback=${calificationData.feedback}")
                
                // Si la calificación tiene un submissionId específico, aplicarla solo a esa entrega
                if (calificationData.submissionId != null) {
                    applyCalificationToSpecificSubmission(calificationData)
                } else {
                    // Si no tiene submissionId específico, aplicar a la entrega del usuario actual
                    applyCalificationToCurrentUserSubmission(calificationData)
                }
                
                // Limpiar la calificación pendiente después de aplicarla
                calificationManager.clearPendingCalification()
            }
        }
    }

    /**
     * Aplica la calificación a una entrega específica por submissionId
     */
    private fun applyCalificationToSpecificSubmission(calificationData: CalificationManager.CalificationData) {
        val submissionId = calificationData.submissionId ?: run {
            applyCalificationToCurrentUserSubmission(calificationData)
            return
        }
        lifecycleScope.launch {
            try {
                val gradeFloat = calificationData.grade.replace(",", ".").toFloatOrNull()
                    ?.coerceIn(0f, 10f) ?: return@launch
                val cleanFeedback = sanitizeFeedback(calificationData.feedback)
                val pushed = withContext(Dispatchers.IO) {
                    BackendApiService.gradeSubmission(submissionId, gradeFloat, cleanFeedback) is ApiResult.Success
                }
                if (pushed) {
                    Log.i("TaskSubmissionsFragment", "✅ Pending grade applied to submission $submissionId.")
                    loadSubmissions()
                } else {
                    Log.w("TaskSubmissionsFragment", "Failed to apply pending grade to submission $submissionId.")
                }
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Exception applying grade to specific submission $submissionId", e)
            }
        }
    }

    /**
     * Aplica la calificación a la entrega del usuario actual
     */
    private fun applyCalificationToCurrentUserSubmission(calificationData: CalificationManager.CalificationData) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val currentUsername = sessionManager.getUsername()
                if (currentUsername == null) {
                    Log.e("TaskSubmissionsFragment", "No se pudo obtener el username actual")
                    return@launch
                }

                val currentUserId = sessionManager.getUserId()
                if (currentUserId == null) {
                    Log.e("TaskSubmissionsFragment", "No se pudo obtener el userId actual")
                    return@launch
                }

                val userSubmission = withContext(Dispatchers.IO) {
                    // Fetch user's submission for this task from backend
                    try {
                        val result = BackendApiService.getSubmissionByUserAndTask(taskId, currentUserId)
                        if (result is ApiResult.Success) result.data else null
                    } catch (e: Exception) {
                        null
                    }
                }

                if (userSubmission != null) {
                    // Convertir la calificación de String a Float
                    val gradeFloat = calificationData.grade.replace(",", ".").toFloatOrNull()?.coerceIn(0f, 10f)
                    if (gradeFloat != null) {
                        // Actualizar la entrega con la calificación
                        updateSubmissionGrade(userSubmission, gradeFloat, calificationData.feedback)
                        
                        Toast.makeText(
                            context, 
                            "✅ Calificación aplicada: ${calificationData.grade}/10", 
                            Toast.LENGTH_LONG
                        ).show()
                        
                        // Recargar las entregas para mostrar la calificación actualizada
                        loadSubmissions()
                    } else {
                        Log.e("TaskSubmissionsFragment", "No se pudo convertir la calificación a número: ${calificationData.grade}")
                    }
                } else {
                    Log.w("TaskSubmissionsFragment", "No se encontró entrega del usuario para aplicar la calificación")
                    // Toast suprimido para evitar mensajes molestos si la entrega aún no se ha sincronizado
                    /* Toast.makeText(
                        context, 
                        "⚠️ No se encontró tu entrega para aplicar la calificación", 
                        Toast.LENGTH_SHORT
                    ).show() */
                }

            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error aplicando calificación pendiente", e)
                Toast.makeText(context, "Error al aplicar calificación: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Carga la información completa de la tarea con curso y tema
     */
    private fun loadTaskWithCourseInfo() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val taskInfo = withContext(Dispatchers.IO) {
                    try {
                        val taskResult = BackendApiService.getTaskById(taskId)
                        val task = (taskResult as? ApiResult.Success)?.data ?: return@withContext null
                        val topicResult = if (task.topicId > 0) BackendApiService.getTopicById(task.topicId) else null
                        val topic = (topicResult as? ApiResult.Success)?.data
                        val subjectId = topic?.subjectId ?: 0L
                        val subject = if (subjectId > 0) (BackendApiService.getSubjectById(subjectId) as? ApiResult.Success)?.data else null
                        val realCourseId = subject?.courseId ?: topic?.courseId
                        Log.d("TaskSubmissionsFragment", "Resolution chain: taskId=$taskId -> topicId=${task.topicId} -> subjectId=$subjectId -> courseId=$realCourseId")
                        val courseResult = if (realCourseId != null && realCourseId > 0) BackendApiService.getCourseById(realCourseId) else null
                        val course = (courseResult as? ApiResult.Success)?.data
                        mapOf(
                            "taskName" to (task.name ?: ""),
                            "taskDescription" to (task.description ?: "Sin descripción"),
                            "topicName" to (topic?.name ?: ""),
                            "courseTitle" to (course?.title ?: ""),
                            "courseDescription" to (course?.description ?: ""),
                            "dueDate" to (task.dueDate ?: ""),
                            "subjectCreatorUserId" to (subject?.createdBy ?: 0L),
                            "courseCreatorUserId" to (course?.creatorUserId ?: 0L)
                        )
                    } catch (e: Exception) {
                        Log.w("TaskSubmissionsFragment", "Error fetching task/topic/course: ${e.message}")
                        null
                    }
                }

                if (taskInfo != null) {
                    taskName = taskInfo["taskName"] as String
                    taskDescription = taskInfo["taskDescription"] as String
                    topicName = taskInfo["topicName"] as String
                    courseTitle = taskInfo["courseTitle"] as String
                    courseDescription = taskInfo["courseDescription"] as String
                    taskDueDate = (taskInfo["dueDate"] as? String)?.takeIf { it.isNotBlank() }

                    val subjectCreatorUserId = taskInfo["subjectCreatorUserId"] as Long
                    val courseCreatorUserId = taskInfo["courseCreatorUserId"] as Long
                    val hadResolvedAccess = hasResolvedTaskCreatorAccess
                    val wasCreator = isCourseCreator
                    isCourseCreator = canCurrentUserManageTask(subjectCreatorUserId, courseCreatorUserId)
                    hasResolvedTaskCreatorAccess = true
                    if (!hadResolvedAccess || wasCreator != isCourseCreator) {
                        Log.d(
                            "TaskSubmissionsFragment",
                            "isCourseCreator corrected: $wasCreator -> $isCourseCreator (subjectCreatorId=$subjectCreatorUserId, courseCreatorId=$courseCreatorUserId, userId=${sessionManager.getUserId()})"
                        )
                        applyRoleVisibility()
                    }

                    adapter.notifyDataSetChanged()
                    updateUploadSectionForDeadline()
                }
            } catch (e: Exception) {
                Log.w("TaskSubmissionsFragment", "Error cargando información de tarea: ${e.message}")
            }
        }
    }

    private fun applyRoleVisibility() {
        val v = view ?: return
        val searchEditText = v.findViewById<EditText>(R.id.searchEditText)
        if (!hasResolvedTaskCreatorAccess) {
            findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
            v.findViewById<LinearLayout>(R.id.uploadSection)?.visibility = View.GONE
            v.findViewById<View>(R.id.uploadDivider)?.visibility = View.GONE
            v.findViewById<TextView>(R.id.deadlineMessageTextView)?.visibility = View.GONE
            searchEditText?.visibility = View.GONE
            return
        }
        if (isCourseCreator) {
            findViewByName<LinearLayout>("progressSection")?.visibility = View.VISIBLE
            v.findViewById<LinearLayout>(R.id.uploadSection)?.visibility = View.GONE
            v.findViewById<View>(R.id.uploadDivider)?.visibility = View.GONE
            v.findViewById<TextView>(R.id.deadlineMessageTextView)?.visibility = View.GONE
            searchEditText?.visibility = View.VISIBLE
            setupSearchBar(searchEditText)
            loadTaskProgress()
        } else {
            findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
            v.findViewById<LinearLayout>(R.id.uploadSection)?.visibility = View.VISIBLE
            v.findViewById<View>(R.id.uploadDivider)?.visibility = View.VISIBLE
            v.findViewById<TextView>(R.id.deadlineMessageTextView)?.visibility = View.GONE
            searchEditText?.visibility = View.GONE
            setupUploadSection(v)
            val statusTextView = v.findViewById<TextView>(R.id.uploadStatusTextView)
            checkUserSubmission(statusTextView)
        }
        loadSubmissions()
    }

    private fun isDeadlinePassed(): Boolean {
        val dueDate = taskDueDate ?: return false
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
            )
            var date: java.util.Date? = null
            for (fmt in formats) {
                try { date = fmt.parse(dueDate); if (date != null) break } catch (_: Exception) {}
            }
            date?.before(java.util.Date()) ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun updateUploadSectionForDeadline() {
        if (isCourseCreator || !isDeadlinePassed()) return
        view?.findViewById<LinearLayout>(R.id.uploadSection)?.visibility = View.GONE
        view?.findViewById<View>(R.id.uploadDivider)?.visibility = View.GONE
        val formattedDeadline = formatDeadlineDate()
        val deadlineView = view?.findViewById<TextView>(R.id.deadlineMessageTextView)
        deadlineView?.text = "⏰ Se ha vencido la fecha de entrega, esta era a las $formattedDeadline"
        deadlineView?.visibility = View.VISIBLE
    }

    private fun formatDeadlineDate(): String {
        val dueDate = taskDueDate ?: return ""
        val inputFormats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        )
        var parsedDate: java.util.Date? = null
        for (fmt in inputFormats) {
            try { parsedDate = fmt.parse(dueDate); if (parsedDate != null) break } catch (_: Exception) {}
        }
        if (parsedDate == null) return dueDate
        val outputFormat = SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault())
        return outputFormat.format(parsedDate)
    }

    private fun loadTaskProgress() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Determine courseId, subscribers and submissions via backend
                val courseId = withContext(Dispatchers.IO) {
                    try {
                        val taskResult = BackendApiService.getTaskById(taskId)
                        val task = (taskResult as? ApiResult.Success)?.data
                        val topicResult = task?.topicId?.let { tid -> BackendApiService.getTopicById(tid) }
                        val topic = (topicResult as? ApiResult.Success)?.data
                        topic?.courseId
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching task/topic from backend", e)
                        null
                    }
                }

                if (courseId == null) {
                    return@launch
                }

                val students = withContext(Dispatchers.IO) {
                    try {
                        // Get enrolled students via progress records
                        val progressResult = BackendApiService.getAllProgressByCourse(courseId)
                        val progressList = (progressResult as? ApiResult.Success)?.data ?: emptyList()
                        progressList.mapNotNull { progress ->
                            val userResult = BackendApiService.getUserById(progress.usuarioEstudiante)
                            (userResult as? ApiResult.Success)?.data?.usuario
                        }
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching students from backend", e)
                        emptyList<String>()
                    }
                }

                val submissions = withContext(Dispatchers.IO) {
                    try {
                        val result = BackendApiService.getSubmissionsByTask(taskId)
                        (result as? ApiResult.Success)?.data ?: emptyList()
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching submissions from backend", e)
                        emptyList<com.example.tareamov.data.entity.TaskSubmission>()
                    }
                }

                // Calculate progress
                val totalStudents = students.size
                val submittedCount = submissions.size
                val gradedCount = submissions.count { it.grade != null } // Contar como calificado si tiene nota (incluso 0)

                // Update UI
                    if (totalStudents > 0) {
                    val submissionPercentage = (submittedCount * 100) / totalStudents
                    findViewByName<ProgressBar>("taskProgressBar")?.let {
                        it.max = 100
                        it.progress = submissionPercentage
                    }

                    findViewByName<TextView>("progressTextView")?.text = "$submittedCount de $totalStudents estudiantes han entregado ($gradedCount calificados)"
                } else {
                    findViewByName<ProgressBar>("taskProgressBar")?.progress = 0
                    findViewByName<TextView>("progressTextView")?.text = "No hay estudiantes inscritos en este curso"
                }

            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error loading task progress", e)
            }
        }
    }

    private fun checkUserSubmission(statusTextView: TextView?) {
        val currentUserId = sessionManager.getUserId()
        if (currentUserId == -1L) return

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val submission = withContext(Dispatchers.IO) {
                    try {
                        val result = BackendApiService.getSubmissionByUserAndTask(taskId, currentUserId)
                        if (result is ApiResult.Success) result.data else null
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching user submission from backend", e)
                        null
                    }
                }

                if (submission != null) {
                    // User has already submitted
                    hasUserSubmitted = true
                    userSubmission = submission

                    // Update UI to show submission status
                    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    val dateString = dateFormat.format(submission.submissionDate)

                    val gradeText = if (submission.grade != null) {
                        "Calificación: ${submission.grade}/10"
                    } else {
                        "Pendiente de calificación"
                    }

                    statusTextView?.text = "Enviado el $dateString\n$gradeText"
                    statusTextView?.setTextColor(resources.getColor(android.R.color.holo_green_light, null))

                    // Update progress for student (views may be absent)
                    findViewByName<ProgressBar>("taskProgressBar")?.let {
                        it.max = 100
                        it.progress = if (submission.grade != null) 100 else 50
                    }
                    findViewByName<TextView>("progressTextView")?.text = if (submission.grade != null)
                        "Tarea completada y calificada"
                    else
                        "Tarea entregada, pendiente de calificación"

                    // If the submission is already graded, hide the upload section
                    if (submission.grade != null) {
                        view?.findViewById<LinearLayout>(R.id.uploadSection)?.visibility = View.GONE
                        view?.findViewById<View>(R.id.uploadDivider)?.visibility = View.GONE
                        findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
                    } else if (isDeadlinePassed()) {
                        // Not graded but deadline has passed: hide upload, show deadline message
                        view?.findViewById<LinearLayout>(R.id.uploadSection)?.visibility = View.GONE
                        view?.findViewById<View>(R.id.uploadDivider)?.visibility = View.GONE
                        val formattedDeadline = formatDeadlineDate()
                        val deadlineView = view?.findViewById<TextView>(R.id.deadlineMessageTextView)
                        deadlineView?.text = "⏰ Se ha vencido la fecha de entrega, esta era a las $formattedDeadline"
                        deadlineView?.visibility = View.VISIBLE
                    } else {
                        // Not graded yet: Allow updates
                        view?.findViewById<Button>(R.id.submitButton)?.isEnabled = true
                        view?.findViewById<Button>(R.id.submitButton)?.text = "Actualizar entrega"
                        view?.findViewById<Button>(R.id.submitGitHubButton)?.isEnabled = true
                        view?.findViewById<Button>(R.id.submitGitHubButton)?.text = "Actualizar GitHub"
                        view?.findViewById<Button>(R.id.selectFileButton)?.isEnabled = true
                    }
                } else {
                    // User hasn't submitted yet
                    hasUserSubmitted = false
                    statusTextView?.text = "Sube tu entrega"
                    statusTextView?.setTextColor(resources.getColor(android.R.color.white, null))

                    // Update progress for student (views may be absent)
                    findViewByName<ProgressBar>("taskProgressBar")?.let {
                        it.max = 100
                        it.progress = 0
                    }
                    findViewByName<TextView>("progressTextView")?.text = "Tarea pendiente de entrega"
                }
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error checking user submission", e)
            }
        }
    }

    private fun loadSubmissions() {
        if (taskId == -1L) {
            Toast.makeText(context, "Error: ID de tarea inválido", Toast.LENGTH_SHORT).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val submissions = withContext(Dispatchers.IO) {
                    try {
                        val allResult = BackendApiService.getSubmissionsByTask(taskId)
                        val all = (allResult as? ApiResult.Success)?.data ?: emptyList()
                        android.util.Log.d("TaskSubmissionsFragment", "getSubmissionsByTask returned ${all.size} items for taskId=$taskId")
                        // Log a small JSON sample of returned submissions for debugging
                        try {
                            val gson = com.google.gson.Gson()
                            val sample = all.take(5)
                            android.util.Log.d("TaskSubmissionsFragment", "Sample submissions JSON: ${gson.toJson(sample)}")
                        } catch (e: Exception) {
                            android.util.Log.w("TaskSubmissionsFragment", "Failed to serialize sample submissions to JSON", e)
                        }
                        
                        val creatorUserId = try {
                            if (!courseCreatorUsername.isNullOrBlank()) {
                                val userResult = BackendApiService.getUserByUsername(courseCreatorUsername!!)
                                (userResult as? ApiResult.Success)?.data?.id
                            } else if (isCourseCreator) {
                                sessionManager.getUserId().takeIf { it != -1L }
                            } else null
                        } catch (e: Exception) {
                            android.util.Log.w("TaskSubmissionsFragment", "Could not get creator user ID", e)
                            null
                        }
                        
                        // Filtrar: excluir al creador del curso Y eliminar duplicados por studentId
                        val filtered = if (isCourseCreator) {
                            // Para el creador: mostrar todas las entregas EXCEPTO las propias
                            // Y eliminar duplicados (quedarse con la más reciente por studentId)
                            all.filter { submission -> 
                                creatorUserId == null || submission.studentId != creatorUserId 
                            }.groupBy { it.studentId }.mapValues { entry ->
                                // Quedarse con la entrega más reciente de cada estudiante
                                entry.value.maxByOrNull { it.submissionDate ?: 0L } ?: entry.value.first()
                            }.values.toList()
                        } else {
                            val userId = sessionManager.getUserId()
                            if (userId != -1L) {
                                // Para estudiantes: mostrar solo su propia entrega más reciente
                                all.filter { it.studentId == userId }
                                    .maxByOrNull { it.submissionDate ?: 0L }
                                    ?.let { listOf(it) } ?: emptyList()
                            } else emptyList()
                        }
                        
                        android.util.Log.d("TaskSubmissionsFragment", "After filtering: ${filtered.size} submissions (creatorId=$creatorUserId excluded)")
                        filtered
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching submissions from backend", e)
                        emptyList<com.example.tareamov.data.entity.TaskSubmission>()
                    }
                }

                allSubmissions = submissions

                if (isCourseCreator && submissions.isNotEmpty()) {
                    resolveUsernames(submissions)
                    // Fetch persona real names for name-based search
                    val ids = submissions.map { it.studentId }.distinct().filter { it > 0 }
                    if (ids.isNotEmpty()) {
                        CoroutineScope(Dispatchers.IO).launch {
                            try {
                                val result = BackendApiService.getPersonasByUserIds(ids)
                                if (result is ApiResult.Success) {
                                    for (item in result.data ?: emptyList()) {
                                        if (item.fullName.isNotBlank()) personaNameCache[item.userId] = item.fullName
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("TaskSubmissionsFragment", "Could not fetch persona names", e)
                            }
                        }
                    }
                }

                if (submissions.isEmpty()) {
                    // Show empty state with appropriate message
                    val emptyTextView = view?.findViewById<TextView>(R.id.emptyStateTextView)
                    if (isCourseCreator) {
                        emptyTextView?.text = "Aún no hay entregas de estudiantes para esta tarea."
                    } else {
                        emptyTextView?.text = "Aún no has entregado esta tarea.\nUsa la sección superior para subir tu archivo."
                    }
                    emptyTextView?.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    // Show submissions
                    view?.findViewById<TextView>(R.id.emptyStateTextView)?.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.updateSubmissions(submissions)
                    
                    // Scroll to specific submission if scrollToSubmissionUsername was provided
                    if (!scrollToSubmissionUsername.isNullOrBlank()) {
                        scrollToSubmissionByUsername(submissions, scrollToSubmissionUsername!!)
                    }
                }

            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error loading submissions", e)
                Toast.makeText(context, "Error al cargar entregas: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun scrollToSubmissionByUsername(submissions: List<TaskSubmission>, targetUsername: String) {
        lifecycleScope.launch {
            try {
                // Fetch all users to map studentId to username
                val userIdToUsername = mutableMapOf<Long, String>()
                withContext(Dispatchers.IO) {
                    try {
                        // Get usernames for each submission's studentId
                        val studentIds = submissions.map { it.studentId }.distinct()
                        for (sid in studentIds) {
                            val userResult = BackendApiService.getUserById(sid)
                            val user = (userResult as? ApiResult.Success)?.data
                            if (user != null) userIdToUsername[sid] = user.usuario
                        }
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching usuarios for scroll mapping", e)
                    }
                }
                
                // Find the submission index where student username matches the target
                val targetIndex = submissions.indexOfFirst { submission ->
                    val submissionUsername = userIdToUsername[submission.studentId]
                    submissionUsername?.equals(targetUsername, ignoreCase = true) == true
                }
                
                if (targetIndex >= 0) {
                    Log.d("TaskSubmissionsFragment", "✅ Found submission from $targetUsername at index $targetIndex")
                    recyclerView.smoothScrollToPosition(targetIndex)
                    
                    // Highlight the submission with visual feedback (lighter background)
                    view?.postDelayed({
                        val viewHolder = recyclerView.findViewHolderForAdapterPosition(targetIndex)
                        if (viewHolder != null) {
                            val cardView = viewHolder.itemView as? androidx.cardview.widget.CardView
                            
                            // Color un poco más claro que el fondo (#1E1E2E) -> #3E3E4E
                            val highlightColor = android.graphics.Color.parseColor("#3E3E4E") // Lightened dark blue/grey
                            val originalColor = android.graphics.Color.parseColor("#1E1E2E") // Original card color
                            
                            if (cardView != null) {
                                cardView.setCardBackgroundColor(highlightColor)
                                
                                // Reset after 3 seconds
                                view?.postDelayed({
                                    cardView.setCardBackgroundColor(originalColor)
                                }, 3000)
                            } else {
                                // Fallback for non-CardView root (though XML uses CardView)
                                viewHolder.itemView.setBackgroundColor(highlightColor)
                                view?.postDelayed({
                                    viewHolder.itemView.setBackgroundColor(originalColor)
                                }, 3000)
                            }
                        }
                    }, 500) // Increased delay slightly to ensure scroll finishes
                } else {
                    Log.w("TaskSubmissionsFragment", "⚠️ Could not find submission from $targetUsername in submissions list (checked ${submissions.size} submissions)")
                }
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "❌ Error scrolling to submission by username", e)
            }
        }
    }

    private fun updateSubmissionGrade(submission: TaskSubmission, grade: Float, feedback: String) {
        lifecycleScope.launch {
            try {
                val cleanFeedback = sanitizeFeedback(feedback)
                val updatedSubmission = submission.copy(grade = grade, feedback = cleanFeedback)
                try {
                    val pushed = withContext(Dispatchers.IO) {
                        val result = BackendApiService.gradeSubmission(
                            updatedSubmission.id,
                            grade,
                            cleanFeedback
                        )
                        result is ApiResult.Success
                    }
                    if (pushed) {
                        Log.i("TaskSubmissionsFragment", "✅ Grade submitted via BackendApiService.")
                        com.example.tareamov.util.AppCache.invalidateAdmin()
                        com.example.tareamov.util.AppCache.invalidateNotifications()
                        context?.let { ctx ->
                            Toast.makeText(ctx, "Calificación enviada al servidor", Toast.LENGTH_SHORT).show()
                        }
                        
                        // Enviar notificación al estudiante sobre la calificación
                        sendGradeNotificationToStudent(submission, grade, cleanFeedback)
                        
                        // Trigger progress update event for the graded student
                        triggerProgressUpdateEvent(submission.studentId, taskId)
                        
                        // IMPORTANTE: Recalcular progreso de TODOS los estudiantes del curso
                        // No solo del estudiante actual, para mantener consistencia
                        recalculateAllStudentsProgressForCourse()
                    } else {
                        Log.w("TaskSubmissionsFragment", "Failed to push grade via BackendApiService.")
                        context?.let { ctx ->
                            Toast.makeText(ctx, "Calificación guardada localmente; se reintentará subirla más tarde.", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Exception pushing updated submission to backend", e)
                    context?.let { ctx ->
                        Toast.makeText(ctx, "Error enviando calificación al servidor; se reintentará.", Toast.LENGTH_SHORT).show()
                    }
                }

                // Update UI
                context?.let { ctx ->
                    Toast.makeText(ctx, "Calificación procesada", Toast.LENGTH_SHORT).show()
                }
                loadSubmissions()
                if (isCourseCreator) loadTaskProgress()
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error updating grade", e)
                context?.let { ctx ->
                    Toast.makeText(ctx, "Error al guardar calificación: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Envía una notificación al estudiante cuando su tarea ha sido calificada o modificada
     */
    private fun sendGradeNotificationToStudent(submission: TaskSubmission, grade: Float, feedback: String) {
        lifecycleScope.launch {
            try {
                Log.d("TaskSubmissionsFragment", "📬 Enviando notificación de calificación al estudiante...")
                
                // Detectar si es una modificación de nota (ya tenía calificación previa)
                val isModification = submission.grade != null
                
                // Obtener información de la tarea y curso
                val taskResult = withContext(Dispatchers.IO) {
                    BackendApiService.getTaskById(taskId)
                }
                val task = (taskResult as? ApiResult.Success)?.data
                if (task == null) {
                    Log.w("TaskSubmissionsFragment", "No se pudo obtener información de la tarea")
                    return@launch
                }
                
                // Obtener información del tema y curso
                val topicResult = withContext(Dispatchers.IO) {
                    task.topicId?.let { tid -> BackendApiService.getTopicById(tid) }
                }
                val topic = (topicResult as? ApiResult.Success)?.data
                val courseId = topic?.courseId
                val courseResult = if (courseId != null) {
                    withContext(Dispatchers.IO) { BackendApiService.getCourseById(courseId) }
                } else null
                val course = (courseResult as? ApiResult.Success)?.data
                
                // Obtener el ID del estudiante (destinatario de la notificación)
                val studentUserId = submission.studentId
                if (studentUserId <= 0) {
                    Log.w("TaskSubmissionsFragment", "ID de estudiante inválido: $studentUserId")
                    return@launch
                }
                
                // No enviar notificación si el estudiante es el creador del curso
                val currentUserId = sessionManager.getUserId()
                if (studentUserId == currentUserId) {
                    Log.d("TaskSubmissionsFragment", "El estudiante calificó su propia tarea, no se envía notificación")
                    return@launch
                }
                
                // Obtener avatar del creador (quien califica)
                val creatorUsername = sessionManager.getUsername()
                val creatorAvatarUrl = withContext(Dispatchers.IO) {
                    val userResult = BackendApiService.getUserById(currentUserId)
                    (userResult as? ApiResult.Success)?.data?.avatar
                }
                
                // Formatear la nota para mostrar
                val gradeText = String.format("%.1f", grade)
                val courseName = course?.title ?: "curso"
                
                // Crear título y mensaje según si es nueva calificación o modificación
                val notificationTitle = if (isModification) {
                    "Nota modificada en $courseName"
                } else {
                    "Tarea calificada en $courseName"
                }
                
                val notificationMessage = if (isModification) {
                    if (feedback.isNotBlank()) {
                        "$creatorUsername ha modificado tu nota a $gradeText en \"${task.name ?: "tarea"}\": \"$feedback\""
                    } else {
                        "$creatorUsername ha modificado tu nota a $gradeText en \"${task.name ?: "tarea"}\""
                    }
                } else {
                    if (feedback.isNotBlank()) {
                        "$creatorUsername te ha calificado con $gradeText en \"${task.name ?: "tarea"}\": \"$feedback\""
                    } else {
                        "$creatorUsername te ha calificado con $gradeText en \"${task.name ?: "tarea"}\""
                    }
                }
                
                // Enviar notificación via BackendApiService
                val notifResult = withContext(Dispatchers.IO) {
                    BackendApiService.sendNotification(
                        userId = studentUserId,
                        title = notificationTitle,
                        message = notificationMessage,
                        type = Notification.TYPE_TASK_GRADED,
                        relatedId = taskId,
                        senderUsername = creatorUsername,
                        metadata = """{"taskId":$taskId,"type":"task_graded"}"""
                    )
                }
                
                if (notifResult is ApiResult.Success) {
                    val actionType = if (isModification) "modificación de nota" else "calificación"
                    Log.d("TaskSubmissionsFragment", "✅ Notificación de $actionType enviada al estudiante")
                } else {
                    Log.w("TaskSubmissionsFragment", "⚠️ No se pudo enviar la notificación al estudiante")
                }
                
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error enviando notificación al estudiante", e)
            }
        }
    }

    /**
     * Recalcula el progreso de TODOS los estudiantes del curso.
     * Debe llamarse después de actualizar calificaciones para mantener consistencia.
     */
    private suspend fun recalculateAllStudentsProgressForCourse() {
        try {
            Log.d("TaskSubmissionsFragment", "🔄 Recalculating progress for all students in course")
            
            val courseId = withContext(Dispatchers.IO) {
                try {
                    val taskResult = BackendApiService.getTaskById(taskId)
                    val task = (taskResult as? ApiResult.Success)?.data
                    val topicResult = task?.topicId?.let { tid -> BackendApiService.getTopicById(tid) }
                    val topic = (topicResult as? ApiResult.Success)?.data
                    topic?.courseId
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error getting courseId from backend", e)
                    null
                }
            }

            if (courseId == null) {
                Log.w("TaskSubmissionsFragment", "❌ Could not determine courseId, skipping progress recalculation")
                return
            }

            Log.d("TaskSubmissionsFragment", "📚 Found courseId: $courseId, triggering server-side recalculation")
            
            val result = withContext(Dispatchers.IO) {
                BackendApiService.recalculateProgress(courseId)
            }
            
            if (result is ApiResult.Success) {
                Log.i("TaskSubmissionsFragment", "✅ Server recalculated progress for course $courseId")
            } else {
                Log.w("TaskSubmissionsFragment", "⚠️ Server recalculation failed for course $courseId")
            }
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "❌ Error recalculating all students progress", e)
        }
    }

    /**
     * (DEPRECATED: Usar recalculateAllStudentsProgressForCourse en su lugar)
     * Recalcula el progreso del estudiante y lo sincroniza al backend.
     * Este método se llama cuando:
     * - Se actualiza una calificación de una entrega
     * - Se crea una nueva entrega del estudiante
     */
    private suspend fun recalculateAndSyncStudentProgress(userId: Long) {
        try {
            Log.d("TaskSubmissionsFragment", "🔄 Starting progress recalculation for studentId: $userId")
            
            // Obtener courseId desde la tarea actual
            val courseId = withContext(Dispatchers.IO) {
                try {
                    val taskResult = BackendApiService.getTaskById(taskId)
                    val task = (taskResult as? ApiResult.Success)?.data
                    val topicResult = task?.topicId?.let { tid -> BackendApiService.getTopicById(tid) }
                    val topic = (topicResult as? ApiResult.Success)?.data
                    topic?.courseId
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error getting courseId from backend", e)
                    null
                }
            }

            if (courseId == null) {
                Log.w("TaskSubmissionsFragment", "❌ Could not determine courseId, skipping progress sync")
                return
            }

            Log.d("TaskSubmissionsFragment", "📚 Found courseId: $courseId")

            // Obtener todas las submissions del estudiante en el curso desde backend
            val topicsResult = withContext(Dispatchers.IO) { BackendApiService.getTopicsByCourse(courseId) }
            val topics = (topicsResult as? ApiResult.Success)?.data ?: emptyList()
            
            val allTasks = mutableListOf<com.example.tareamov.data.entity.Task>()
            val submissions = mutableListOf<com.example.tareamov.data.entity.TaskSubmission>()
            
            withContext(Dispatchers.IO) {
                for (topic in topics) {
                    val tasksResult = BackendApiService.getTasksByTopic(topic.id)
                    val tasksForTopic = (tasksResult as? ApiResult.Success)?.data ?: emptyList()
                    allTasks.addAll(tasksForTopic)
                    
                    for (task in tasksForTopic) {
                        val subResult = BackendApiService.getSubmissionByUserAndTask(task.id, userId)
                        val sub = (subResult as? ApiResult.Success)?.data
                        if (sub != null) submissions.add(sub)
                    }
                }
            }

            Log.d("TaskSubmissionsFragment", "📊 Tasks in course: ${allTasks.size}, Submissions: ${submissions.size}")

            // Calcular métricas
            val tareasTotales = allTasks.size
            val tareasCompletadas = submissions.count { it.grade != null }
            val porcentajeProgreso = if (tareasTotales > 0) {
                (tareasCompletadas.toFloat() / tareasTotales.toFloat()) * 100f
            } else {
                0f
            }

            val totalGrade = submissions.mapNotNull { it.grade }.sum()
            val taskCount = submissions.size
            val promedio = if (taskCount > 0) totalGrade / taskCount else 0f

            Log.d("TaskSubmissionsFragment", "📈 Calculated: total=$tareasTotales, completed=$tareasCompletadas, progress=$porcentajeProgreso%, avg=$promedio")

            // Sincronizar progreso al backend
            val synced = withContext(Dispatchers.IO) {
                try {
                    val result = BackendApiService.upsertProgress(mapOf(
                        "userId" to userId,
                        "courseId" to courseId,
                        "totalTasks" to tareasTotales,
                        "completedTasks" to tareasCompletadas,
                        "progressPercentage" to porcentajeProgreso,
                        "averageGrade" to promedio
                    ))
                    result is ApiResult.Success
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error upserting progress", e)
                    false
                }
            }

            if (synced) {
                Log.i("TaskSubmissionsFragment", "✅ Synced progress for studentId=$userId: total=$tareasTotales, completed=$tareasCompletadas, progress=$porcentajeProgreso%, avg=$promedio")
            } else {
                Log.w("TaskSubmissionsFragment", "⚠️ Failed to sync progress for studentId=$userId")
            }
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "❌ Error recalculating/syncing student progress", e)
        }
    }

    private fun submitTaskFile() {
        val uri = selectedFileUri
        if (uri == null) {
            Toast.makeText(context, "Selecciona un archivo primero", Toast.LENGTH_SHORT).show()
            return
        }

        val currentUserId = sessionManager.getUserId()
        if (currentUserId == -1L) {
            Toast.makeText(context, "Debes iniciar sesión para enviar tareas", Toast.LENGTH_SHORT).show()
            return
        }

        if (isDeadlinePassed()) {
            Toast.makeText(context, "⏰ La fecha de entrega ha vencido, no se puede modificar.", Toast.LENGTH_LONG).show()
            return
        }

        // RESTRICCIÓN: Verificar si ya entregó (solo informativo/log, permitimos update si no está calificado)
        if (hasUserSubmitted) {
            val userSub = userSubmission
            if (userSub != null && userSub.grade != null) {
                Toast.makeText(context, "⚠️ Esta tarea ya fue calificada, no se puede modificar.", Toast.LENGTH_LONG).show()
                return
            }
            Log.d("TaskSubmissionsFragment", "ℹ️ Actualizando entrega existente para userId=$currentUserId, taskId=$taskId")
        }

        // Usar getFileName() para obtener el nombre real del archivo con extensión
        val fileName = getFileName(uri) ?: uri.lastPathSegment ?: "archivo_tarea"
        Log.d("TaskSubmissionsFragment", "📎 Nombre del archivo obtenido: $fileName")
        
        // IMPORTANTE: Intentar persistir permisos del URI para evitar errores de seguridad
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            Log.d("TaskSubmissionsFragment", "✅ Permisos URI persistidos correctamente")
        } catch (e: SecurityException) {
            Log.w("TaskSubmissionsFragment", "⚠️ No se pudieron persistir permisos URI: ${e.message}")
            // No es crítico, continuamos
        }
        
        // Copiar archivo a almacenamiento interno si es necesario (para evitar problemas de permisos)
        val finalUri = try {
            copyFileToInternalStorage(uri, fileName)
        } catch (e: Exception) {
            Log.w("TaskSubmissionsFragment", "⚠️ No se pudo copiar a almacenamiento interno, usando URI original", e)
            uri
        }
        
        // Mostrar progreso mientras se procesa (views may be absent)
        findViewByName<LinearLayout>("progressSection")?.visibility = View.VISIBLE
        findViewByName<ProgressBar>("taskProgressBar")?.isIndeterminate = false
        findViewByName<ProgressBar>("taskProgressBar")?.progress = 0
        findViewByName<TextView>("progressTextView")?.text = "Preparando archivo $fileName..."
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Control para evitar doble tap
                if (isSubmitting) {
                    Toast.makeText(context, "Ya se está enviando una entrega, espera por favor...", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                isSubmitting = true
                // disable button to avoid duplicate taps
                findViewByName<Button>("submitFileButton")?.isEnabled = false
                
                // VERIFICACIÓN ADICIONAL: Asegurar que no existe entrega duplicada
                val existingSubmission = withContext(Dispatchers.IO) {
                    try {
                        val result = BackendApiService.getSubmissionByUserAndTask(taskId, currentUserId)
                        if (result is ApiResult.Success) result.data else null
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error verificando entrega existente", e)
                        null
                    }
                }
                
                if (existingSubmission != null) {
                    if (existingSubmission.grade != null) {
                        Log.w("TaskSubmissionsFragment", "🚫 Entrega ya calificada - submissionId=${existingSubmission.id}")
                        Toast.makeText(context, "⚠️ Esta tarea ya fue calificada y no se puede modificar.", Toast.LENGTH_LONG).show()
                        findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
                        isSubmitting = false
                        return@launch
                    }
                    Log.i("TaskSubmissionsFragment", "ℹ️ Actualizando entrega existente - submissionId=${existingSubmission.id}")
                    hasUserSubmitted = true
                    userSubmission = existingSubmission
                }
                

                // PASO 0: Subir archivo al backend (R2 cloud storage) - OBLIGATORIO
                var cloudFileUri: String? = null
                val currentUsername = sessionManager.getUsername() ?: "unknown"
                
                try {
                    findViewByName<TextView>("progressTextView")?.text = "Subiendo archivo al servidor..."
                    findViewByName<ProgressBar>("taskProgressBar")?.progress = 10

                    val contentResolver = requireContext().contentResolver
                    val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
                    val inputStream = contentResolver.openInputStream(uri)
                    
                    if (inputStream != null) {
                        val bytes = withContext(Dispatchers.IO) { inputStream.readBytes() }
                        inputStream.close()

                        findViewByName<ProgressBar>("taskProgressBar")?.progress = 25
                        findViewByName<TextView>("progressTextView")?.text = "Subiendo archivo: 50%"

                        val uploadResult = withContext(Dispatchers.IO) {
                            BackendApiService.uploadSubmissionFile(
                                fileBytes = bytes,
                                fileName = fileName,
                                mimeType = mimeType,
                                folder = "submissions/task_$taskId"
                            )
                        }

                        if (uploadResult is ApiResult.Success) {
                            // Prefer full public URL so backend LLM pipelines can fetch content directly.
                            val uploadedUrl = uploadResult.data?.get("url")?.asString
                            val key = uploadResult.data?.get("key")?.asString
                            cloudFileUri = when {
                                !uploadedUrl.isNullOrBlank() && uploadedUrl.startsWith("http") -> uploadedUrl
                                !key.isNullOrBlank() -> "$R2_PUBLIC_BASE_URL/$key"
                                !uploadedUrl.isNullOrBlank() -> "$R2_PUBLIC_BASE_URL/$uploadedUrl"
                                else -> null
                            }
                            if (cloudFileUri != null) {
                                Log.d("TaskSubmissionsFragment", "✅ Archivo subido via backend, url: $cloudFileUri")
                                findViewByName<TextView>("progressTextView")?.text = "Archivo subido, procesando..."
                            }
                        } else {
                            Log.e("TaskSubmissionsFragment", "❌ Error subiendo archivo al servidor: ${(uploadResult as? ApiResult.Error)?.message}")
                        }
                    } else {
                        Log.e("TaskSubmissionsFragment", "❌ No se pudo leer el archivo seleccionado")
                    }
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "❌ Error subiendo archivo al servidor: ${e.message}", e)
                }

                // Si no se pudo subir a R2, abortar la entrega
                if (cloudFileUri == null) {
                    Toast.makeText(context, "❌ No se pudo subir el archivo al servidor. Verifica tu conexión e intenta de nuevo.", Toast.LENGTH_LONG).show()
                    findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
                    isSubmitting = false
                    findViewByName<Button>("submitFileButton")?.isEnabled = true
                    return@launch
                }
                
                findViewByName<ProgressBar>("taskProgressBar")?.progress = 40

                // Check if user already has a submission for this task; if so, update it
                val existingLocalSubmission = withContext(Dispatchers.IO) {
                    try {
                        val result = BackendApiService.getSubmissionByUserAndTask(taskId, currentUserId)
                        if (result is ApiResult.Success) result.data else null
                    } catch (e: Exception) {
                        Log.w("TaskSubmissionsFragment", "Error checking existing submission: ${e.message}")
                        null
                    }
                }

                val created: com.example.tareamov.data.entity.TaskSubmission? = if (existingLocalSubmission != null) {
                    // Update existing submission via backend
                    val updated = existingLocalSubmission.copy(
                        fileUri = cloudFileUri,
                        fileName = fileName,
                        submissionDate = System.currentTimeMillis(),
                        grade = 0.0f,
                        feedback = null
                    )

                    // Update remote submission via BackendApiService
                    try {
                        val remoteResult = withContext(Dispatchers.IO) {
                            BackendApiService.submitWork(mapOf(
                                "task_id" to updated.taskId,
                                "student_id" to updated.studentId,
                                "file_url" to updated.fileUri,
                                "content" to updated.fileName,
                                "status" to "submitted"
                            ))
                        }
                        val remoteOk = remoteResult is ApiResult.Success
                        Log.i("TaskSubmissionsFragment", "Remote update result for id=${updated.id}: $remoteOk")
                    } catch (e: Exception) {
                        Log.w("TaskSubmissionsFragment", "Error updating remote submission: ${e.message}")
                    }

                    com.example.tareamov.util.AppCache.invalidateNotifications()
                    // Provide feedback and continue using the updated submission as 'created'
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Entrega actualizada", Toast.LENGTH_SHORT).show()
                        findViewByName<Button>("submitFileButton")?.text = "Actualizar entrega"
                    }

                    updated
                } else {
                    // No existing submission: create a new one and insert remotely
                    val submission = com.example.tareamov.data.entity.TaskSubmission(
                        taskId = taskId,
                        studentId = currentUserId,
                        fileUri = cloudFileUri, // Usar URL de R2 o local
                        fileName = fileName,
                        submissionDate = System.currentTimeMillis(),
                        grade = 0.0f,
                        feedback = null
                    )

                    // Insert remotely via BackendApiService
                    try {
                        Log.d("TaskSubmissionsFragment", "📤 Intentando insertar TaskSubmission via BackendApiService...")
                        val remoteResult = withContext(Dispatchers.IO) {
                            BackendApiService.submitWork(mapOf(
                                "task_id" to taskId,
                                "student_id" to currentUserId,
                                "file_url" to cloudFileUri,
                                "content" to fileName,
                                "status" to "submitted"
                            ))
                        }
                        if (remoteResult is ApiResult.Success) {
                            val remoteSubmission = remoteResult.data
                            val remoteId = remoteSubmission?.id
                            Log.i("TaskSubmissionsFragment", "✅ BackendApiService submitWork returned remote id=$remoteId")
                            com.example.tareamov.util.AppCache.invalidateNotifications()
                            Toast.makeText(context, "Tarea subida a servidor (id=$remoteId)", Toast.LENGTH_SHORT).show()

                            // Trigger progress update event and notify creator
                            triggerProgressUpdateEvent(currentUserId, taskId)
                            notifyCourseCreatorOfSubmission(taskId, taskName, currentUsername)

                            remoteSubmission
                        } else {
                            Log.w("TaskSubmissionsFragment", "BackendApiService submitTask failed: ${(remoteResult as? ApiResult.Error)?.message}")
                            null
                        }
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "❌ Exception en submitTask: ${e.message}", e)
                        null
                    }
                }

                // PASO 1: Extraer el contenido del archivo ANTES de subirlo
                findViewByName<TextView>("progressTextView")?.text = "Analizando contenido del archivo..."
                Log.d("TaskSubmissionsFragment", "🔄 Extrayendo contenido del archivo antes de subir...")
                val analysisResult = withContext(Dispatchers.IO) {
                    extractFileContent(uri, fileName)
                }
                Log.d("TaskSubmissionsFragment", "📊 Contenido extraído: ${analysisResult.content.take(100)}...")

                findViewByName<TextView>("progressTextView")?.text = "Generando contexto estructurado..."
                findViewByName<ProgressBar>("taskProgressBar")?.progress = 50
                var structuredFileContext: FileContext? = null
                try {
                    structuredFileContext = createStructuredContextFromAnalysis(fileName, analysisResult)
                    structuredFileContext?.let {
                        Log.d(
                            "TaskSubmissionsFragment",
                            "🧩 Contexto estructurado generado localmente -> tipo=${it.fileType}, longitud=${(it.fileContent ?: "").length}"
                        )
                    }
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "❌ Error generando contexto estructurado", e)
                }

                if ((!analysisResult.success || analysisResult.content.isEmpty()) &&
                    (structuredFileContext?.fileContent.isNullOrBlank())) {
                    Log.w("TaskSubmissionsFragment", "⚠️ No se pudo extraer contenido util del archivo")
                    Toast.makeText(
                        context,
                        "Advertencia: No se pudo leer el contenido del archivo",
                        Toast.LENGTH_LONG
                    ).show()
                }

                // Use the previously computed `created` submission (either updated or newly created)
                findViewByName<TextView>("progressTextView")?.text = "Guardando contexto del archivo..."
                findViewByName<ProgressBar>("taskProgressBar")?.progress = 80

                if (created != null) {
                    val createdSubmissionId = created.id
                    val taskDescription = withContext(Dispatchers.IO) {
                        try {
                            val taskResult = BackendApiService.getTaskById(taskId)
                            val task = (taskResult as? ApiResult.Success)?.data
                            task?.description ?: "Tarea: ${task?.name ?: "Sin nombre"}"
                        } catch (e: Exception) {
                            Log.e("TaskSubmissionsFragment", "Error obteniendo descripción de tarea", e)
                            "Tarea sin descripción"
                        }
                    }

                    val fileContext = buildFileContextForSubmission(
                        submissionId = createdSubmissionId,
                        originalFileName = fileName,
                        structuredContext = structuredFileContext,
                        analysisResult = analysisResult,
                        taskDescription = taskDescription
                    )

                    Log.d(
                        "TaskSubmissionsFragment",
                        "📦 FileContext final -> nombre=${fileContext.fileName}, tipo=${fileContext.fileType}, longitud=${(fileContext.fileContent ?: "").length}"
                    )

                    // FileContext is sent as part of the submission flow
                    // No local database needed - backend handles file context storage
                    withContext(Dispatchers.IO) {
                        try {
                            Log.d("TaskSubmissionsFragment", "📤 FileContext prepared for submission $createdSubmissionId")
                            Log.d("TaskSubmissionsFragment", "📤 Datos: submissionId=$createdSubmissionId, fileName=${fileContext.fileName}, contentLength=${(fileContext.fileContent ?: "").length}")
                            // TODO: Add BackendApiService endpoint for FileContext if needed
                        } catch (e: Exception) {
                            Log.e("TaskSubmissionsFragment", "❌ Error handling FileContext", e)
                        }
                    }

                    findViewByName<TextView>("progressTextView")?.text = "¡Tarea enviada exitosamente!"
                    findViewByName<ProgressBar>("taskProgressBar")?.progress = 100

                    // IMPORTANTE: Recalcular y sincronizar progreso del estudiante
                    recalculateAndSyncStudentProgress(currentUserId)

                    // Refresh the submissions list from backend to ensure frontend data is up to date
                    loadSubmissions()
                } else {
                    Log.w("TaskSubmissionsFragment", "No se pudo crear/actualizar la entrega, refrescando lista")
                    Toast.makeText(context, "Tarea enviada (pendiente de confirmación en servidor)", Toast.LENGTH_SHORT).show()
                    loadSubmissions()
                }

                // Final UI updates
                selectedFileUri = null
                findViewByName<TextView>("selectedFileNameTextView")?.text = "Ningún archivo seleccionado"

                // Update submission status (UI only)
                val statusTextView = findViewByName<TextView>("mySubmissionStatusTextView")
                if (statusTextView != null) {
                    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    val dateString = dateFormat.format(System.currentTimeMillis())
                    statusTextView.text = "Enviado el $dateString\nPendiente de calificación"
                    statusTextView.setTextColor(resources.getColor(android.R.color.holo_green_light, null))
                }

                // Update progress after submission - ocultar barra después de 2 segundos
                kotlinx.coroutines.delay(2000)
                findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE

                // Reset progress bar for next submission (views may be absent)
                findViewByName<ProgressBar>("taskProgressBar")?.let {
                    it.max = 100
                    it.progress = 0
                    it.isIndeterminate = false
                }
                findViewByName<TextView>("progressTextView")?.text = "0% completado"

                // Allow resubmission: keep submit button enabled for updates
                findViewByName<Button>("submitFileButton")?.isEnabled = true
                findViewByName<Button>("submitFileButton")?.text = "Actualizar entrega"

                // Reload submissions to show the new one
                loadSubmissions()
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error submitting task", e)
                Toast.makeText(context, "Error al enviar tarea: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                // Always reset submitting flag and re-enable button
                isSubmitting = false
                findViewByName<Button>("submitFileButton")?.isEnabled = true
                // Ensure progress section hidden if something failed
                try {
                    findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
                } catch (ignored: Exception) {
                }
            }
        }
    }

    private fun buildFileContextForSubmission(
        submissionId: Long,
        originalFileName: String,
        structuredContext: FileContext?,
        analysisResult: LocalFileAnalysisResult,
        taskDescription: String
    ): FileContext {
        val fallbackType = analysisResult.fileType.lowercase(Locale.getDefault())
        val candidateType = structuredContext?.fileType?.takeIf { !it.isNullOrBlank() }?.lowercase(Locale.getDefault())
            ?: fallbackType
        val sanitizedFileName = sanitizeFileName(
            structuredContext?.fileName ?: originalFileName,
            candidateType
        )

        val mergedMetadata = structuredContext?.metadata ?: analysisResult.metadata
        val mergedSummary = when {
            taskDescription.isNotBlank() -> taskDescription
            !structuredContext?.contentSummary.isNullOrBlank() -> structuredContext?.contentSummary ?: ""
            !analysisResult.metadata.isNullOrBlank() -> analysisResult.metadata
            else -> ""
        }

        structuredContext?.let {
            if (!it.fileContent.isNullOrBlank()) {
                return it.copy(
                    submissionId = submissionId,
                    fileName = sanitizedFileName,
                    fileType = candidateType,
                    metadata = mergedMetadata,
                    contentSummary = mergedSummary.ifBlank { null },
                    extractedText = it.extractedText ?: it.fileContent
                )
            }
        }

        // Construir contenido más descriptivo cuando hay errores
        val fallbackContent = when {
            analysisResult.content.isNotBlank() -> analysisResult.content
            !analysisResult.error.isNullOrBlank() -> {
                // Si hay error, crear un mensaje más informativo para el LLM
                buildString {
                    appendLine("INFORMACIÓN DEL ARCHIVO:")
                    appendLine("Nombre: $originalFileName")
                    appendLine("Tipo detectado: $candidateType")
                    if (!mergedMetadata.isNullOrBlank()) {
                        appendLine("Metadata: $mergedMetadata")
                    }
                    appendLine()
                    appendLine("ESTADO DEL ANÁLISIS:")
                    appendLine("⚠️ ${analysisResult.error}")
                    appendLine()
                    appendLine("CONTEXTO DE LA TAREA:")
                    if (taskDescription.isNotBlank()) {
                        appendLine(taskDescription)
                    } else {
                        appendLine("Sin descripción de tarea disponible")
                    }
                    appendLine()
                    appendLine("NOTA: El archivo no pudo ser procesado completamente. Por favor, verifica que el formato del archivo sea compatible o que el archivo no esté corrupto.")
                }
            }
            else -> "Archivo enviado sin contenido extraíble. Nombre: $originalFileName, Tipo: $candidateType"
        }

        return FileContext(
            submissionId = submissionId,
            fileName = sanitizedFileName,
            fileType = candidateType,
            fileContent = fallbackContent,
            extractedText = fallbackContent,
            metadata = mergedMetadata,
            jsonContent = structuredContext?.jsonContent,
            contentSummary = mergedSummary.ifBlank { null }
        )
    }

    private fun sanitizeFileName(rawName: String, forcedExtension: String?): String {
        val cleaned = rawName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .substringAfterLast(':')

        val base = cleaned.substringBeforeLast('.')
        val sanitizedBase = base.ifBlank { cleaned }.ifBlank { "archivo" }
            .replace(Regex("[^A-Za-z0-9_-]"), "_")

        val normalizedExtension = when {
            !forcedExtension.isNullOrBlank() && forcedExtension.lowercase(Locale.getDefault()) != "unknown" ->
                forcedExtension.lowercase(Locale.getDefault()).removePrefix(".")
            cleaned.contains('.') -> cleaned.substringAfterLast('.').lowercase(Locale.getDefault())
            else -> "dat"
        }

        return if (normalizedExtension.isNotBlank()) {
            "${sanitizedBase}.$normalizedExtension"
        } else {
            sanitizedBase
        }
    }

    /**
     * Triggers an asynchronous event to recalculate student progress after submission
     * This replaces the database trigger approach to avoid SQL ambiguity issues (error 42702)
     */
    private fun triggerProgressUpdateEvent(userId: Long, submittedTaskId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("TaskSubmissionsFragment", "🔄 Triggering progress update event for userId=$userId, task $submittedTaskId")
                
                // Get course ID from task via backend
                val taskResult = BackendApiService.getTaskById(submittedTaskId)
                val task = (taskResult as? ApiResult.Success)?.data
                if (task == null) {
                    Log.e("TaskSubmissionsFragment", "❌ Task not found: $submittedTaskId")
                    return@launch
                }
                
                val topicResult = BackendApiService.getTopicById(task.topicId)
                val topic = (topicResult as? ApiResult.Success)?.data
                if (topic == null) {
                    Log.e("TaskSubmissionsFragment", "❌ Topic not found: ${task.topicId}")
                    return@launch
                }
                
                val courseId = topic.courseId
                
                // Get all topics and tasks for this course
                val topicsResult = BackendApiService.getTopicsByCourse(courseId)
                val allTopics = (topicsResult as? ApiResult.Success)?.data ?: emptyList()
                
                val allTasksInCourse = mutableListOf<com.example.tareamov.data.entity.Task>()
                for (t in allTopics) {
                    val tasksResult = BackendApiService.getTasksByTopic(t.id)
                    val tasks = (tasksResult as? ApiResult.Success)?.data ?: emptyList()
                    allTasksInCourse.addAll(tasks)
                }
                val totalTasks = allTasksInCourse.size
                
                // Get submissions for this student in course tasks
                val taskIdsInCourse = allTasksInCourse.map { it.id }.toSet()
                val courseSubmissions = mutableListOf<com.example.tareamov.data.entity.TaskSubmission>()
                for (tsk in allTasksInCourse) {
                    val subResult = BackendApiService.getSubmissionByUserAndTask(tsk.id, userId)
                    val sub = (subResult as? ApiResult.Success)?.data
                    if (sub != null) courseSubmissions.add(sub)
                }
                
                val completedTasks = courseSubmissions.count { it.grade != null }
                
                val progressPct = if (totalTasks > 0) {
                    (completedTasks.toFloat() / totalTasks.toFloat()) * 100
                } else 0f
                
                val gradesOnly = courseSubmissions.mapNotNull { it.grade }
                val avgGrade = if (gradesOnly.isNotEmpty()) {
                    gradesOnly.average().toFloat()
                } else 0f
                
                // Upsert progress via BackendApiService
                // Keys MUST be camelCase — the backend repo maps them to snake_case DB columns
                val upsertResult = BackendApiService.upsertProgress(mapOf(
                    "userId" to userId,
                    "courseId" to courseId,
                    "totalTasks" to totalTasks,
                    "completedTasks" to completedTasks,
                    "progressPercentage" to progressPct,
                    "averageGrade" to avgGrade
                ))
                
                if (upsertResult is ApiResult.Success) {
                    Log.i("TaskSubmissionsFragment", "✅ Progress synced: $completedTasks/$totalTasks tasks, ${progressPct.toInt()}%, avg=$avgGrade")
                } else {
                    Log.w("TaskSubmissionsFragment", "⚠️ Failed to sync progress via backend")
                }
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "❌ Error in progress update event", e)
            }
        }
    }

    /**
     * Notifies the course creator when a student submits a task
     * This logic has been moved to the backend (SubmissionController) to avoid duplicates and ensure consistency.
     */
    private fun notifyCourseCreatorOfSubmission(taskId: Long, taskName: String, studentUsername: String) {
        // Notification is now handled by the backend
        Log.d("TaskSubmissionsFragment", "🔔 Notification delegated to backend for task $taskId")
    }

    private fun openFilePicker() {
        // Lanzar selector de archivos con tipos MIME específicos
        // Esto permite seleccionar archivos desde almacenamiento local, Google Drive, OneDrive, etc.
        val mimeTypes = arrayOf(
            "*/*",                          // Todos los archivos
            "application/pdf",              // PDF
            "application/msword",           // Word
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // DOCX
            "text/plain",                   // TXT
            "text/html",                    // HTML
            "application/vnd.ms-powerpoint", // PPT
            "application/vnd.openxmlformats-officedocument.presentationml.presentation", // PPTX
            "image/*",                      // Imágenes
            "video/*"                       // Videos
        )
        
        try {
            filePickerLauncher.launch(mimeTypes)
            Log.d("TaskSubmissionsFragment", "📂 File picker lanzado con tipos MIME: ${mimeTypes.joinToString()}")
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "❌ Error al abrir file picker: ${e.message}", e)
            Toast.makeText(requireContext(), "Error al abrir selector de archivos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createFileContextAndNavigateToChat(submission: TaskSubmission) {
        // 🔥 Guardar datos de la submission para pasar al ChatBotFragment
        currentSubmissionTaskId = submission.taskId
        currentSubmissionStudentId = submission.studentId
        currentSubmissionFileUri = submission.fileUri
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d("TaskSubmissionsFragment", "🔄 Iniciando análisis de archivo: ${submission.fileName}")
                findViewByName<LinearLayout>("progressSection")?.visibility = View.VISIBLE
                findViewByName<ProgressBar>("taskProgressBar")?.isIndeterminate = true
                findViewByName<TextView>("progressTextView")?.text = "Procesando archivo ${submission.fileName}..."
                val uri = Uri.parse(submission.fileUri)
                Log.d("TaskSubmissionsFragment", "📂 URI del archivo: $uri")
                val analysisResult = withContext(Dispatchers.IO) {
                    extractFileContent(uri, submission.fileName)
                }
                Log.d("TaskSubmissionsFragment", "📊 Resultado del análisis - Éxito: ${analysisResult.success}")
                val fileType = getFileType(submission.fileName)
                Log.d("TaskSubmissionsFragment", "📄 Tipo de archivo detectado: $fileType para archivo: ${submission.fileName}")
                Log.d("TaskSubmissionsFragment", "📝 Extensión del archivo: ${submission.fileName.substringAfterLast('.', "sin extensión")}")
                Log.d("TaskSubmissionsFragment", "📝 Contenido extraído (${analysisResult.content.length} caracteres): ${analysisResult.content.take(100)}...")
                // Obtener la descripción de la tarea desde el backend
                val taskDescription = withContext(Dispatchers.IO) {
                    try {
                        val taskResult = BackendApiService.getTaskById(submission.taskId)
                        Log.d("TaskSubmissionsFragment", "Fetched task from backend for submission ${submission.id}: ${taskResult is ApiResult.Success}")
                        if (taskResult is ApiResult.Success) taskResult.data?.description ?: "" else ""
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching task from backend for submission ${submission.id}", e)
                        ""
                    }
                }
                
                // FALLBACK: Si taskDescription está vacío, usar el nombre de la tarea
                val finalTaskDescription = if (taskDescription.isNotEmpty()) {
                    taskDescription
                } else {
                    withContext(Dispatchers.IO) {
                        try {
                            val taskResult = BackendApiService.getTaskById(submission.taskId)
                            val fallbackDescription = "Tarea: ${if (taskResult is ApiResult.Success) taskResult.data?.name ?: "Sin nombre" else "Sin nombre"}"
                            Log.d("TaskSubmissionsFragment", "Using fallback for taskDescription: '$fallbackDescription'")
                            fallbackDescription
                        } catch (e: Exception) {
                            Log.e("TaskSubmissionsFragment", "Error fetching task for fallback description", e)
                            "Tarea: Sin nombre"
                        }
                    }
                }
                
                val fileContext = FileContext(
                    submissionId = submission.id,
                    fileName = submission.fileName,
                    fileType = fileType,

                    // [MODIFICADO] NO enviar URI como contenido. Dejar vacío para que backend lo busque por ID.
                    fileContent = if (analysisResult.content.isNotBlank() && !analysisResult.content.startsWith("http")) analysisResult.content else "",
                    extractedText = if (analysisResult.content.isNotBlank() && !analysisResult.content.startsWith("http")) analysisResult.content else "",
                    metadata = analysisResult.metadata,
                    contentSummary = finalTaskDescription
                )
                
                // LOGGING DETALLADO PARA DEBUGGING
                Log.d("TaskSubmissionsFragment", "==============================================")
                Log.d("TaskSubmissionsFragment", "📝 CREANDO FILE CONTEXT:")
                Log.d("TaskSubmissionsFragment", "==============================================")
                Log.d("TaskSubmissionsFragment", "submission.id: ${submission.id}")
                Log.d("TaskSubmissionsFragment", "submission.taskId: ${submission.taskId}")
                Log.d("TaskSubmissionsFragment", "taskDescription extraído de DB: '$taskDescription'")
                Log.d("TaskSubmissionsFragment", "finalTaskDescription (con fallback): '$finalTaskDescription'")
                Log.d("TaskSubmissionsFragment", "taskDescription length: ${taskDescription.length}")
                Log.d("TaskSubmissionsFragment", "finalTaskDescription length: ${finalTaskDescription.length}")
                Log.d("TaskSubmissionsFragment", "¿taskDescription vacío?: ${taskDescription.isEmpty()}")
                Log.d("TaskSubmissionsFragment", "fileContext.contentSummary: '${fileContext.contentSummary}'")
                Log.d("TaskSubmissionsFragment", "==============================================")
                
                findViewByName<TextView>("progressTextView")?.text = "Archivo procesado. Preparando interfaz de chat..."
                findViewByName<ProgressBar>("taskProgressBar")?.isIndeterminate = false
                findViewByName<ProgressBar>("taskProgressBar")?.progress = 90
                navigateToChatWithFileContext(fileContext)
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "❌ Error procesando archivo: ${e.message}", e)
                
                // Mostrar error al usuario
                withContext(Dispatchers.Main) {
                    findViewByName<ProgressBar>("taskProgressBar")?.visibility = View.GONE
                    Toast.makeText(
                        context,
                        "Error procesando archivo: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    
                    // Crear contexto de error específico para Google Drive
                    val errorFileContext = FileContext(
                        submissionId = submission.id,
                        fileName = submission.fileName,
                        fileType = "google_drive_error",
                        fileContent = "Este archivo está en Google Drive y no puede ser procesado directamente.",
                        extractedText = "ERROR: Los archivos de Google Drive deben descargarse localmente",
                        metadata = "Error: Archivo de Google Drive detectado - Por favor descarga el archivo localmente"
                    )
                    
                    // Navegar al chat con mensaje de error
                    navigateToChatWithFileContext(errorFileContext, true)
                }
                
                // PRIMERA ESTRATEGIA: Conversión local del archivo
                try {
                    Log.d("TaskSubmissionsFragment", "🌐 Intentando procesar con conversión local")
                    Toast.makeText(context, "Procesando archivo...", Toast.LENGTH_SHORT).show()

                    val conversionResult = withContext(Dispatchers.IO) {
                        extractFileContent(Uri.parse(submission.fileUri), submission.fileName)
                    }
                    val fileContext = createStructuredContextFromAnalysis(submission.fileName, conversionResult)
                    
                    // Actualizar el ID de la entrega
                    val updatedFileContext = fileContext.copy(submissionId = submission.id)
                    
                    // Guardar el contexto en la base de datos y navegar al chat
                    navigateToChatWithFileContext(
                        updatedFileContext,
                        !conversionResult.success
                    )
                    return@launch
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "❌ Error usando conversión local: ${e.message}", e)
                    // Continuamos con extracción local como fallback
                }
                
                // SEGUNDA ESTRATEGIA (Fallback): extracción local
                // Solo si MCP no está disponible o falló
                Log.d("TaskSubmissionsFragment", "🔄 Intentando con extracción local como fallback")
                
                val analysisResult = withContext(Dispatchers.IO) {
                    extractFileContent(Uri.parse(submission.fileUri), submission.fileName)
                }
                
                Log.d("TaskSubmissionsFragment", "📊 Resultado del análisis - Éxito: ${analysisResult.success}")
                
                if (!analysisResult.success) {
                    Log.e("TaskSubmissionsFragment", "❌ Error en análisis: ${analysisResult.error}")
                    
                    // Crear un FileContext con contenido de error para que el usuario pueda ver el error en el chat
                    val errorMsg = if (submission.fileUri.contains("google") || submission.fileUri.contains("docs")) {
                        "Este archivo está en Google Drive y no se puede acceder directamente. " +
                        "Por favor, descárgalo primero a tu dispositivo."
                    } else {
                        "No se pudo acceder al contenido del archivo. ${analysisResult.error ?: ""}"
                    }
                    
                    val errorFileContext = FileContext(
                        submissionId = submission.id,
                        fileName = submission.fileName,
                        fileType = analysisResult.fileType,
                        fileContent = errorMsg,
                        extractedText = "Error de acceso: ${analysisResult.error}",
                        metadata = if (submission.fileUri.contains("google") || submission.fileUri.contains("docs")) 
                                     "Error: Archivo de Google Drive inaccesible - URI: ${submission.fileUri}" 
                                   else 
                                     "Error: Archivo inaccesible - URI: ${submission.fileUri}"
                    )
                    
                    // Navegar al chat con el contexto de error
                    navigateToChatWithFileContext(errorFileContext, true)
                    return@launch
                }
                
                val fileType = getFileType(submission.fileName)
                Log.d("TaskSubmissionsFragment", "📄 Tipo de archivo: $fileType")
                Log.d("TaskSubmissionsFragment", "📝 Contenido extraído (${analysisResult.content.length} caracteres): ${analysisResult.content.take(100)}...")
                
                // Obtener la descripción de la tarea desde el backend
                val taskDescription = withContext(Dispatchers.IO) {
                    try {
                        val taskResult = BackendApiService.getTaskById(submission.taskId)
                        if (taskResult is ApiResult.Success) taskResult.data?.description ?: "" else ""
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching task description from backend", e)
                        ""
                    }
                }
                
                val fileContext = FileContext(
                    submissionId = submission.id,
                    fileName = submission.fileName,
                    fileType = fileType,
                    fileContent = analysisResult.content, // Get content from result
                    extractedText = analysisResult.content, // Use the same content
                    metadata = analysisResult.metadata, // Include metadata from analysis
                    contentSummary = taskDescription
                )
                
                // Guardar el contexto en la base de datos y navegar al chat
                navigateToChatWithFileContext(fileContext)
                
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "❌ Error general procesando archivo: ${e.message}", e)
                
                val errorMessage = when {
                    e.message?.contains("StorageFileLoadException") == true ||
                    e.message?.contains("connection_failure") == true -> {
                        "El archivo no está disponible. Si es de Google Drive, descárgalo localmente primero e inténtalo de nuevo."
                    }
                    e.message?.contains("FileNotFoundException") == true -> {
                        "Archivo no encontrado. Verifica que el archivo sigue estando disponible."
                    }
                    e.message?.contains("failed to connect") == true -> {
                        "Error de conexión con el servicio. Verifica que MCP o Ollama estén ejecutándose."
                    }
                    else -> {
                        "Error procesando el archivo: ${e.message}"
                    }
                }
                
                Toast.makeText(
                    context, 
                    "$errorMessage\n\nAbriendo chat en modo básico.", 
                    Toast.LENGTH_LONG
                ).show()
                
                // Verificar si ya estamos en el ChatBotFragment antes de navegar
                val currentDestination = findNavController().currentDestination?.id
                if (currentDestination != R.id.chatBotFragment) {
                    // Navegar al chat sin contexto de archivo, pero con información del error
                    val bundle = Bundle().apply {
                        putString("errorMessage", errorMessage)
                        putString("fileName", submission.fileName)
                    }
                    try {
                        findNavController().navigate(
                            R.id.action_taskSubmissionFragment_to_chatBotFragment,
                            bundle
                        )
                    } catch (navException: Exception) {
                        Log.e("TaskSubmissionsFragment", "❌ Error navegando al chat desde error handler: ${navException.message}")
                        findNavController().navigate(R.id.chatBotFragment, bundle)
                    }
                } else {
                    Log.d("TaskSubmissionsFragment", "⚠️ Ya estamos en ChatBotFragment, no navegando desde error handler")
                }
            }
        }
    }

    /**
     * Navega al chat con un contexto de archivo, opcionalmente marcando si hay error
     * Esta función es suspendida porque necesita guardar el contexto del archivo en la base de datos,
     * lo cual es una operación de suspensión.
     */
    private suspend fun navigateToChatWithFileContext(fileContext: FileContext, isError: Boolean = false) {
        try {
            // Verificar si ya estamos en el ChatBotFragment
            val currentDestination = findNavController().currentDestination?.id
            if (currentDestination == R.id.chatBotFragment) {
                Log.d("TaskSubmissionsFragment", "⚠️ Ya estamos en ChatBotFragment, no navegando")
                Toast.makeText(context, "Ya estás en el chat", Toast.LENGTH_SHORT).show()
                return
            }
            
            // Send FileContext to backend
            try {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val payload = mapOf(
                        "submissionId" to fileContext.submissionId,
                        "fileName" to fileContext.fileName,
                        "fileType" to fileContext.fileType,
                        "fileSize" to (if (!fileContext.fileContent.isNullOrEmpty()) (fileContext.fileContent ?: "").length.toLong() else 0L),
                        "content" to (fileContext.fileContent ?: ""),
                        "taskId" to taskId,
                        "topicName" to topicName,
                        "courseName" to courseTitle,
                        "taskDescription" to taskDescription,
                        "courseDescription" to courseDescription,
                        "studentId" to sessionManager.getUserId()
                    )
                    val result = BackendApiService.createFileContext(payload)
                    if (result.isSuccess) Log.i("TaskSubmissionsFragment", "FileContext sent to backend.")
                    else Log.w("TaskSubmissionsFragment", "Failed to send FileContext to backend.")
                }
            } catch (e: Exception) {
                Log.w("TaskSubmissionsFragment", "Exception sending FileContext to backend: ${e.message}")
            }
            
            // El resto del código debe ejecutarse en el hilo principal para UI
            withContext(Dispatchers.Main) {
                // Ocultar progreso (view may be absent)
                findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
                
                // Determinar el mensaje según el tipo de archivo
                val message = when {
                    // Error de Google Drive específico
                    fileContext.fileType == "google_drive_error" -> {
                        "📱 Archivo de Google Drive detectado. Se proporcionarán instrucciones en el chat."
                    }
                    // Otros errores genéricos
                    isError -> {
                        "⚠️ No se pudo acceder completamente al archivo. Se iniciará el chat con información limitada."
                    }
                    // Éxito con diferentes tipos de archivo
                    fileContext.fileType.equals("pdf", ignoreCase = true) -> {
                        "📄 Documento PDF procesado. Puedes hacer preguntas sobre su contenido."
                    }
                    fileContext.fileType.equals("docx", ignoreCase = true) || 
                    fileContext.fileType.equals("doc", ignoreCase = true) -> {
                        "📝 Documento Word procesado. Puedes hacer preguntas sobre su contenido."
                    }
                    fileContext.fileType.equals("pptx", ignoreCase = true) || 
                    fileContext.fileType.equals("ppt", ignoreCase = true) -> {
                        "🎮 Presentación PowerPoint procesada. Puedes hacer preguntas sobre su contenido."
                    }
                    fileContext.fileType.equals("xlsx", ignoreCase = true) || 
                    fileContext.fileType.equals("xls", ignoreCase = true) -> {
                        "📊 Hoja de cálculo Excel procesada. Puedes hacer preguntas sobre su contenido."
                    }
                    fileContext.fileType.equals("txt", ignoreCase = true) -> {
                        "📃 Archivo de texto procesado. Puedes hacer preguntas sobre su contenido."
                    }
                    fileContext.fileType.equals("json", ignoreCase = true) -> {
                        "📋 Archivo JSON procesado. Puedes hacer preguntas sobre su estructura y contenido."
                    }
                    else -> {
                        "✅ Archivo analizado correctamente. Iniciando chat con contexto."
                    }
                }
                
                // Mostrar mensaje apropiado
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                
                // Crear argumentos para el fragmento de chat
                val args = Bundle().apply {
                    putLong("submissionId", fileContext.submissionId)
                    if (isError) {
                        // Si es un error de Google Drive, enviamos mensaje especial
                        if (fileContext.fileType == "google_drive_error") {
                            putString("errorMessage", "Este archivo está en Google Drive y requiere ser descargado localmente primero.")
                        } else {
                            putString("errorMessage", "No se pudo acceder al archivo. Por favor descárgalo primero.")
                        }
                    }
                    putString("fileName", fileContext.fileName)
                    putString("taskName", taskName)
                    putString("taskDescription", taskDescription)
                    // 🔥 CRÍTICO: Pasar taskId, studentId, fileUri para que el backend pueda buscar el contenido
                    if (currentSubmissionTaskId > 0) putLong("taskId", currentSubmissionTaskId)
                    if (currentSubmissionStudentId > 0) putLong("studentId", currentSubmissionStudentId)
                    if (currentSubmissionFileUri.isNotEmpty()) {
                        putString("fileUri", currentSubmissionFileUri)
                        Log.d("TaskSubmissionsFragment", "🔥 Pasando fileUri al chat: ${currentSubmissionFileUri.take(60)}")
                    }
                }
                
                // Navegar al chat solo si no estamos ya ahí
                try {
                    findNavController().navigate(R.id.action_taskSubmissionFragment_to_chatBotFragment, args)
                } catch (navException: Exception) {
                    Log.e("TaskSubmissionsFragment", "❌ Error de navegación: ${navException.message}")
                    // Como fallback, intentar navegar directamente al fragmento
                    findNavController().navigate(R.id.chatBotFragment, args)
                }
            }
            
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "❌ Error navegando al chat: ${e.message}", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Error iniciando chat: ${e.message}", Toast.LENGTH_SHORT).show()
                
                // Si falla guardar el contexto, al menos intentamos navegar al chat en modo básico
                val args = Bundle().apply {
                    putString("errorMessage", "Error preparando el contexto del archivo: ${e.message}")
                    putString("fileName", fileContext.fileName)
                }
                
                // Verificar destino actual antes del fallback también
                val currentDestination = findNavController().currentDestination?.id
                if (currentDestination != R.id.chatBotFragment) {
                    try {
                        findNavController().navigate(R.id.action_taskSubmissionFragment_to_chatBotFragment, args)
                    } catch (navException: Exception) {
                        Log.e("TaskSubmissionsFragment", "❌ Error en fallback: ${navException.message}")
                        findNavController().navigate(R.id.chatBotFragment, args)
                    }
                }
            }
        }
    }

    inner class SubmissionsAdapter(
        private var submissions: List<TaskSubmission>,
        private val onGradeSubmitted: (TaskSubmission, Float, String) -> Unit
    ) : RecyclerView.Adapter<SubmissionsAdapter.ViewHolder>() {

        fun updateSubmissions(newSubmissions: List<TaskSubmission>) {
            submissions = newSubmissions
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_submission, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(submissions[position])
        }

        override fun getItemCount() = submissions.size

        inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            // Avatar removed from item layout
            private val studentNameTextView: TextView = itemView.findViewById(R.id.studentNameTextView)
            private val submissionDateTextView: TextView = itemView.findViewById(R.id.submissionDateTextView)
            private val fileNameTextView: TextView = itemView.findViewById(R.id.fileNameTextView)
            private val viewFileButton: Button = itemView.findViewById(R.id.viewFileButton)
            private val chatBotButton: Button = itemView.findViewById(R.id.chatBotButton)
            private val gradeEditText: EditText = itemView.findViewById(R.id.gradeEditText)
            private val feedbackEditText: EditText = itemView.findViewById(R.id.feedbackEditText)
            private val submitGradeButton: Button = itemView.findViewById(R.id.submitGradeButton)
            private val gradeSection: View = itemView.findViewById(R.id.gradeSection)
            private val gradeDisplayTextView: TextView = itemView.findViewById(R.id.gradeDisplayTextView)
            private val feedbackDisplayTextView: TextView = itemView.findViewById(R.id.feedbackDisplayTextView)
            private val gradedByInfoTextView: TextView = itemView.findViewById(R.id.gradedByInfoTextView)
            
            // Nueva información de tarea y curso
            private val taskTitleDisplayTextView: TextView = itemView.findViewById(R.id.taskTitleDisplayTextView)
            private val subjectTextView: TextView = itemView.findViewById(R.id.subjectTextView)
            private val deliveryDateTextView: TextView = itemView.findViewById(R.id.deliveryDateTextView)
            private val taskDescriptionDisplayTextView: TextView = itemView.findViewById(R.id.taskDescriptionDisplayTextView)
            
            // Calificación IA
            private val aiGradeTextView: TextView = itemView.findViewById(R.id.aiGradeTextView)
            private val gradeScaleTextView: TextView = itemView.findViewById(R.id.gradeScaleTextView)
            private val noGradeTextView: TextView = itemView.findViewById(R.id.noGradeTextView)
            private val qualityLabelTextView: TextView = itemView.findViewById(R.id.qualityLabelTextView)
            private val gradeProgressBar: ProgressBar = itemView.findViewById(R.id.gradeProgressBar)

            fun bind(submission: TaskSubmission) {
                // Mostrar información de la tarea y curso
                if (taskName.isNotEmpty()) {
                    taskTitleDisplayTextView.text = taskName
                    subjectTextView.text = "📚 $topicName"
                    taskDescriptionDisplayTextView.text = taskDescription
                } else {
                    // Valores por defecto mientras se carga la información
                    taskTitleDisplayTextView.text = "Cargando información..."
                    subjectTextView.text = "📚 Cargando..."
                    taskDescriptionDisplayTextView.text = "Cargando descripción..."
                }
                
                // Resolve username by studentId (submission now stores studentId)
                CoroutineScope(Dispatchers.Main).launch {
                    val cached = usernameCache[submission.studentId]
                    val usernameResolved = cached ?: withContext(Dispatchers.IO) {
                        try {
                            val userResult = BackendApiService.getUserById(submission.studentId)
                            if (userResult is ApiResult.Success) {
                                val name = userResult.data?.usuario
                                if (name != null) usernameCache[submission.studentId] = name
                                name
                            } else null
                        } catch (e: Exception) {
                            Log.e("TaskSubmissionsFragment", "Error fetching username for id ${submission.studentId}", e)
                            null
                        }
                    }
                    val displayName = usernameResolved ?: "Usuario ${submission.studentId}"
                    val personaName = personaNameCache[submission.studentId]
                    studentNameTextView.text = if (!personaName.isNullOrBlank()) "$displayName\n$personaName" else displayName
                    // Avatar removed from item layout
                }

                val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                submissionDateTextView.text = "Entregado: ${dateFormat.format(submission.submissionDate)}"
                deliveryDateTextView.text = dateFormat.format(submission.submissionDate)

                fileNameTextView.text = submission.fileName
                
                // Manejar calificación IA - mostrar si hay calificación (incluso 0)
                if (submission.grade != null) {
                    // Asegurar que la calificación esté en el rango 0-10
                    val aiGrade = submission.grade.coerceIn(0f, 10f)
                    
                    // Mostrar elementos de calificación
                    aiGradeTextView.visibility = View.VISIBLE
                    gradeScaleTextView.visibility = View.VISIBLE
                    gradeProgressBar.visibility = View.VISIBLE
                    noGradeTextView.visibility = View.GONE
                    
                    // Mostrar calificación con 1 decimal si es necesario
                    aiGradeTextView.text = if (aiGrade % 1 == 0f) {
                        aiGrade.toInt().toString()
                    } else {
                        String.format("%.1f", aiGrade)
                    }
                    
                    // Configurar progress bar (convertir 0-10 a 0-100 para la barra)
                    gradeProgressBar.progress = (aiGrade * 10).toInt()
                    
                    // Determinar calidad basada en la calificación (escala 0-10)
                    val qualityLabel = when {
                        aiGrade >= 9.0 -> "⭐ Excelente"
                        aiGrade >= 8.0 -> "⭐ Muy Bueno"
                        aiGrade >= 7.0 -> "⭐ Bueno"
                        aiGrade >= 6.0 -> "⭐ Regular"
                        else -> "⭐ Necesita Mejora"
                    }
                    qualityLabelTextView.text = qualityLabel
                } else {
                    // No hay calificación real (grade = 0 o null) - mostrar "No calificado"
                    aiGradeTextView.visibility = View.GONE
                    gradeScaleTextView.visibility = View.GONE
                    gradeProgressBar.visibility = View.GONE
                    noGradeTextView.visibility = View.VISIBLE
                    
                    qualityLabelTextView.text = "⏳ Pendiente de calificación"
                }

                // Avatar removed from item layout; no per-item avatar handling needed

                // Show graded-by info
                if (submission.gradedBy != null && submission.gradedBy > 0) {
                    gradedByInfoTextView.visibility = View.VISIBLE
                    gradedByInfoTextView.text = "Calificado por: cargando..."
                    CoroutineScope(Dispatchers.Main).launch {
                        val graderName = withContext(Dispatchers.IO) {
                            try {
                                val result = BackendApiService.getUserById(submission.gradedBy)
                                if (result is ApiResult.Success) result.data?.usuario else null
                            } catch (e: Exception) { null }
                        }
                        val displayGrader = graderName ?: "Usuario ${submission.gradedBy}"
                        val isOwner = courseCreatorUsername != null && displayGrader == courseCreatorUsername
                        val role = if (isOwner) "Propietario" else "Colaborador"
                        val gradedAtFormatted = submission.gradedAt?.let { raw ->
                            try {
                                val inputFormats = listOf(
                                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSS'+00:00'",
                                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                                    "yyyy-MM-dd'T'HH:mm:ss'Z'",
                                    "yyyy-MM-dd'T'HH:mm:ss.SSSSSSXXX"
                                )
                                val date = inputFormats.firstNotNullOfOrNull { fmt ->
                                    try { SimpleDateFormat(fmt, Locale.getDefault()).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.parse(raw) } catch (e: Exception) { null }
                                }
                                date?.let { SimpleDateFormat("dd/MM/yyyy hh:mm a", Locale.getDefault()).format(it) }
                            } catch (e: Exception) { null }
                        } ?: ""
                        val info = "Calificado por: $displayGrader ($role)" +
                            if (gradedAtFormatted.isNotEmpty()) " - $gradedAtFormatted" else ""
                        gradedByInfoTextView.text = info
                    }
                } else {
                    gradedByInfoTextView.visibility = View.GONE
                }

                viewFileButton.setOnClickListener {
                    openSubmissionFile(submission)
                }

                chatBotButton.setOnClickListener {
                    // Create file context and navigate to ChatBot fragment
                    createFileContextAndNavigateToChat(submission)
                }

                // Only show grading controls to course creator
                if (isCourseCreator) {
                    gradeSection.visibility = View.VISIBLE
                    gradeDisplayTextView.visibility = View.GONE
                    feedbackDisplayTextView.visibility = View.GONE

                    // Pre-fill existing grade and feedback if available
                    gradeEditText.setText(submission.grade?.toString() ?: "")
                    feedbackEditText.setText(sanitizeFeedback(submission.feedback))

                    // Verificar si hay calificación pendiente y aplicarla automáticamente a los campos
                    val calificationManager = CalificationManager.getInstance(itemView.context)
                    if (calificationManager.hasPendingCalification()) {
                        val pendingCalification = calificationManager.getPendingCalification()
                        if (pendingCalification != null) {
                            // Aplicar la calificación pendiente a los campos
                            gradeEditText.setText(pendingCalification.grade)
                            feedbackEditText.setText(sanitizeFeedback(pendingCalification.feedback))
                            
                            // Destacar los campos para mostrar que vienen del chat
                            gradeEditText.setBackgroundColor(itemView.context.getColor(android.R.color.holo_green_light))
                            feedbackEditText.setBackgroundColor(itemView.context.getColor(android.R.color.holo_green_light))
                            
                            // Mostrar mensaje temporal
                            Toast.makeText(itemView.context, "📱 Calificación aplicada desde el chat", Toast.LENGTH_SHORT).show()
                        }
                    }

                    submitGradeButton.setOnClickListener {
                        val gradeText = gradeEditText.text.toString()
                        if (gradeText.isBlank()) {
                            Toast.makeText(context, "Ingresa una calificación", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }

                        try {
                            // Normalizar el formato decimal (cambiar coma por punto si es necesario)
                            val normalizedGradeText = gradeText.replace(",", ".")
                            val grade = normalizedGradeText.toFloat()
                            if (grade < 0 || grade > 10) {
                                Toast.makeText(context, "La calificación debe estar entre 0 y 10", Toast.LENGTH_SHORT).show()
                                return@setOnClickListener
                            }

                            val feedback = sanitizeFeedback(feedbackEditText.text.toString())
                            feedbackEditText.setText(feedback)
                            onGradeSubmitted(submission, grade, feedback)
                        } catch (e: NumberFormatException) {
                            Toast.makeText(context, "❌ No se pudo convertir la calificación '$gradeText' a número decimal", Toast.LENGTH_SHORT).show()
                            Log.e("TaskSubmissionsFragment", "❌ No se pudo convertir grade a Float: $gradeText", e)
                        }
                    }
                } else {
                    gradeSection.visibility = View.GONE

                    // For students, show their grade if available
                    if (submission.grade != null) {
                        gradeDisplayTextView.visibility = View.VISIBLE
                        gradeDisplayTextView.text = "Calificación: ${submission.grade}/10"

                        // Show feedback if available
                        if (!submission.feedback.isNullOrBlank()) {
                            feedbackDisplayTextView.visibility = View.VISIBLE
                            feedbackDisplayTextView.text = "Comentarios: ${sanitizeFeedback(submission.feedback)}"
                        } else {
                            feedbackDisplayTextView.visibility = View.GONE
                        }
                    } else {
                        gradeDisplayTextView.visibility = View.VISIBLE
                        gradeDisplayTextView.text = "Pendiente de calificación"
                        feedbackDisplayTextView.visibility = View.GONE
                    }
                }
            }

            // Avatar loading removed

            private fun openSubmissionFile(submission: TaskSubmission) {
                val ctx = context ?: return
                val uriString = submission.fileUri

                if (uriString.isBlank()) {
                    Toast.makeText(ctx, "No hay archivo asociado a esta entrega", Toast.LENGTH_SHORT).show()
                    return
                }

                val isLocalFile = uriString.startsWith("file:///") || uriString.startsWith("content://")
                val isFullUrl = uriString.startsWith("http://") || uriString.startsWith("https://")

                // Case 1: HTTP/HTTPS URL (R2 public URL, GitHub, etc.) → open in browser
                if (isFullUrl) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriString)))
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error opening URL", e)
                        Toast.makeText(ctx, "Error al abrir URL: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                // Case 2: R2 object key (relative path like "submissions/task_168/...") → build public URL and open in browser
                if (!isLocalFile) {
                    val publicUrl = "$R2_PUBLIC_BASE_URL/$uriString"
                    Log.d("TaskSubmissionsFragment", "Opening R2 public URL: $publicUrl")
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(publicUrl)))
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error opening R2 URL", e)
                        Toast.makeText(ctx, "Error al abrir archivo: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                    return
                }

                // Case 3: Local file:/// URI
                if (uriString.startsWith("file:///")) {
                    val parsedUri = Uri.parse(uriString)
                    val file = java.io.File(parsedUri.path ?: return)

                    if (file.exists()) {
                        // File exists locally - open with FileProvider
                        try {
                            val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                ctx, "${ctx.packageName}.fileprovider", file
                            )
                            val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(
                                submission.fileName.replace(" ", "%20")
                            )
                            val mimeType = android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(ext) ?: "*/*"
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(contentUri, mimeType)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("TaskSubmissionsFragment", "Error opening local file with FileProvider", e)
                            Toast.makeText(ctx, "Error al abrir el archivo: ${e.message}", Toast.LENGTH_SHORT).show()
                        }

                        // Background: re-upload to R2 so it becomes publicly accessible
                        reUploadLocalFileToR2(submission, file)
                    } else {
                        // File doesn't exist locally (opened from another device)
                        Toast.makeText(ctx, "Este archivo fue guardado localmente en otro dispositivo y no está disponible desde aquí.", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                // Case 4: content:// URI
                try {
                    val parsedUri = Uri.parse(uriString)
                    val ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(uriString)
                    val mimeType = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(ext) ?: "*/*"
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(parsedUri, mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error opening file", e)
                    Toast.makeText(ctx, "Error al abrir el archivo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Re-uploads a local file to R2 in the background and updates the submission record,
     * so the file becomes publicly accessible from any device.
     */
    private fun reUploadLocalFileToR2(submission: TaskSubmission, file: java.io.File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("TaskSubmissionsFragment", "📤 Re-uploading local file to R2: ${file.name}")
                val bytes = file.readBytes()
                val ext = file.extension.lowercase()
                val mimeType = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(ext) ?: "application/octet-stream"

                val uploadResult = BackendApiService.uploadSubmissionFile(
                    fileBytes = bytes,
                    fileName = submission.fileName.ifBlank { file.name },
                    mimeType = mimeType,
                    folder = "submissions/task_${submission.taskId}"
                )

                if (uploadResult is ApiResult.Success) {
                    val key = uploadResult.data?.get("key")?.asString
                    if (!key.isNullOrBlank()) {
                        // Update the submission record with the R2 key
                        BackendApiService.submitWork(mapOf(
                            "task_id" to submission.taskId,
                            "student_id" to submission.studentId,
                            "file_url" to key,
                            "content" to submission.fileName,
                            "status" to "submitted"
                        ))
                        Log.i("TaskSubmissionsFragment", "✅ Re-uploaded local file to R2 key: $key")
                        com.example.tareamov.util.AppCache.invalidateNotifications()

                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "📤 Archivo subido al servidor para acceso público", Toast.LENGTH_SHORT).show()
                            loadSubmissions()
                        }
                    }
                } else {
                    Log.w("TaskSubmissionsFragment", "⚠️ Failed to re-upload to R2: ${(uploadResult as? ApiResult.Error)?.message}")
                }
            } catch (e: Exception) {
                Log.w("TaskSubmissionsFragment", "⚠️ Error re-uploading local file to R2", e)
            }
        }
    }

    /**
     * Envía un repositorio de GitHub como tarea
     * Solo guarda la URL del repositorio sin análisis
     */
    private fun submitGitHubRepository(githubUrlEditText: com.google.android.material.textfield.TextInputEditText) {
        val repoUrl = githubUrlEditText.text.toString().trim()
        
        if (repoUrl.isEmpty()) {
            Toast.makeText(context, "Por favor ingresa una URL de repositorio de GitHub", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Validar formato de URL de GitHub (más flexible)
        val githubPatterns = listOf(
            Regex("^https?://github\\.com/[\\w.-]+/[\\w.-]+(/(tree|blob)/[\\w.-/]+)?/?$"),
            Regex("^github\\.com/[\\w.-]+/[\\w.-]+/?$"),
            Regex("^[\\w.-]+/[\\w.-]+$") // formato user/repo
        )
        
        if (!githubPatterns.any { it.matches(repoUrl) }) {
            Toast.makeText(context, "❌ URL de GitHub inválida. Usa el formato:\nhttps://github.com/usuario/repositorio", Toast.LENGTH_LONG).show()
            return
        }
        
        val currentUserId = sessionManager.getUserId()
        if (currentUserId == -1L) {
            Toast.makeText(context, "Debes iniciar sesión para enviar tareas", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (isDeadlinePassed()) {
            Toast.makeText(context, "⏰ La fecha de entrega ha vencido, no se puede modificar.", Toast.LENGTH_LONG).show()
            return
        }

        // RESTRICCIÓN: Verificar si ya entregó (permitimos update si no está calificado)
        if (hasUserSubmitted) {
            val userSub = userSubmission
            if (userSub != null && userSub.grade != null) {
                Toast.makeText(context, "⚠️ Esta tarea ya fue calificada, no se puede modificar.", Toast.LENGTH_LONG).show()
                return
            }
            Log.d("TaskSubmissionsFragment", "ℹ️ Actualizando entrega GitHub para userId=$currentUserId, taskId=$taskId")
        }
        
        // Mostrar progreso (views may be absent)
        findViewByName<LinearLayout>("progressSection")?.visibility = View.VISIBLE
        findViewByName<ProgressBar>("taskProgressBar")?.isIndeterminate = true
        findViewByName<TextView>("progressTextView")?.text = "📤 Enviando repositorio de GitHub..."
        
        Log.d("TaskSubmissionsFragment", "🚀 Enviando URL de repositorio: $repoUrl")
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Check for duplicate submission via backend
                val existingSubmission = withContext(Dispatchers.IO) {
                    try {
                        val subResult = BackendApiService.getSubmissionByUserAndTask(taskId, currentUserId)
                        if (subResult is ApiResult.Success) subResult.data else null
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error verificando entrega existente de GitHub", e)
                        null
                    }
                }
                
                if (existingSubmission != null) {
                    if (existingSubmission.grade != null) {
                        Log.w("TaskSubmissionsFragment", "🚫 Entrega de GitHub ya calificada - submissionId=${existingSubmission.id}")
                        Toast.makeText(context, "⚠️ Esta tarea ya fue calificada y no se puede modificar.", Toast.LENGTH_LONG).show()
                        findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
                        return@launch
                    }
                    Log.i("TaskSubmissionsFragment", "ℹ️ Actualizando entrega GitHub existente - submissionId=${existingSubmission.id}")
                    hasUserSubmitted = true
                    userSubmission = existingSubmission
                }
                
                // Crear la entrega de tarea con la URL
                val fileName = "github_${extractRepoName(repoUrl)}"
                val submission = TaskSubmission(
                    taskId = taskId,
                    studentId = currentUserId,
                    fileUri = repoUrl, // Guardamos la URL del repositorio
                    fileName = fileName,
                    submissionDate = System.currentTimeMillis(),
                    grade = 0f, // Sin calificación inicial
                    feedback = null
                )
                
                Log.d("TaskSubmissionsFragment", "📤 Enviando TaskSubmission al backend...")
                Log.d("TaskSubmissionsFragment", "📤 taskId=$taskId, studentId=$currentUserId, fileUri=$repoUrl, fileName=$fileName")
                
                // Submit via backend
                val remoteId = withContext(Dispatchers.IO) {
                    try {
                        val submitResult = BackendApiService.submitWork(mapOf(
                            "task_id" to taskId,
                            "student_id" to currentUserId,
                            "file_url" to repoUrl,
                            "fileName" to fileName,
                            "status" to "submitted"
                        ))
                        if (submitResult is ApiResult.Success) submitResult.data?.id else null
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "❌ Exception en submitTask: ${e.message}", e)
                        null
                    }
                }
                
                if (remoteId != null) {
                    Log.i("TaskSubmissionsFragment", "✅ Repositorio enviado con ID: $remoteId")
                    com.example.tareamov.util.AppCache.invalidateNotifications()
                    
                    // Trigger progress update event
                    triggerProgressUpdateEvent(currentUserId, taskId)
                    
                    // Notify the course creator about the new submission
                    notifyCourseCreatorOfSubmission(taskId, taskName, sessionManager.getUsername() ?: "unknown")
                    
                    // Limpiar el campo de texto
                    githubUrlEditText.text?.clear()
                    
                    // Actualizar UI
                    findViewByName<TextView>("progressTextView")?.text = "✅ ¡Repositorio enviado!"
                    Toast.makeText(
                        context,
                        "✅ Repositorio enviado exitosamente",
                        Toast.LENGTH_SHORT
                    ).show()
                    
                    // Recargar entregas
                    loadSubmissions()
                    
                    // Ocultar barra de progreso después de 1 segundo
                    kotlinx.coroutines.delay(1000)
                    findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE

                    // Deshabilitar botón
                    findViewByName<Button>("submitGitHubButton")?.isEnabled = false
                    findViewByName<Button>("submitGitHubButton")?.text = "Ya enviado"
                    
                } else {
                    Log.w("TaskSubmissionsFragment", "❌ insertTaskSubmission retornó null")
                    Toast.makeText(context, "Error al enviar repositorio al servidor", Toast.LENGTH_SHORT).show()
                    findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
                }
                
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "❌ Error al enviar repositorio de GitHub", e)
                Toast.makeText(
                    context,
                    "Error al enviar el repositorio:\n${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                findViewByName<LinearLayout>("progressSection")?.visibility = View.GONE
            }
        }
    }
    
    /**
     * Extrae el nombre del repositorio de una URL de GitHub
     */
    private fun extractRepoName(url: String): String {
        return try {
            val parts = url.trimEnd('/').split('/')
            if (parts.size >= 2) {
                "${parts[parts.size - 2]}_${parts[parts.size - 1]}"
            } else {
                "repository"
            }
        } catch (e: Exception) {
            "repository"
        }
    }

    /**
     * Limpia formato Markdown básico del feedback para evitar mostrar símbolos como ** y #.
     */
    private fun sanitizeFeedback(feedback: String?): String {
        if (feedback.isNullOrBlank()) return ""

        return feedback
            .lines()
            .map { line -> line.replace(Regex("^\\s{0,3}#{1,6}\\s*"), "") }
            .joinToString("\n")
            .replace("**", "")
            .replace("__", "")
            .trim()
    }
    
    /**
     * Extrae la calificación numérica del metadata del análisis
     */
    private fun extractGradeFromMetadata(metadata: String?): Float {
        return try {
            if (metadata == null) return 0f
            
            // Buscar patrón "Grade: XX/10" o similar
            val gradePattern = Regex("Grade:\\s*(\\d+(?:\\.\\d+)?)/10")
            val match = gradePattern.find(metadata)
            
            if (match != null) {
                val gradeValue = match.groupValues[1].toFloat()
                // Ya está en escala 0-10
                gradeValue.coerceIn(0f, 10f)
            } else {
                0f
            }
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "Error extrayendo calificación: ${e.message}")
            0f
        }
    }
    
    /**
     * Obtiene el nombre real del archivo desde el URI, incluyendo la extensión
     * Esta función consulta el ContentResolver para obtener el DISPLAY_NAME real del archivo
     * Implementa múltiples estrategias para manejar diferentes tipos de URIs
     */
    private fun getFileName(uri: Uri): String? {
        val contentResolver = requireContext().contentResolver
        
        Log.d("TaskSubmissionsFragment", "🔍 Obteniendo nombre para URI: $uri")
        Log.d("TaskSubmissionsFragment", "🔍 URI scheme: ${uri.scheme}, authority: ${uri.authority}")
        
        // Estrategia 1: Query usando OpenableColumns (estándar para URIs de documentos)
        try {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val displayName = cursor.getString(nameIndex)
                        if (!displayName.isNullOrEmpty() && !displayName.startsWith("msf:") && 
                            !displayName.startsWith("content:") && !displayName.matches(Regex("^[0-9]+$"))) {
                            Log.d("TaskSubmissionsFragment", "✅ Nombre obtenido via OpenableColumns.DISPLAY_NAME: $displayName")
                            return displayName
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "Error obteniendo nombre via OpenableColumns: ${e.message}")
        }
        
        // Estrategia 2: Query con todas las columnas para obtener DISPLAY_NAME
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    // Log todas las columnas para debugging
                    Log.d("TaskSubmissionsFragment", "📋 Columnas disponibles: ${cursor.columnNames.joinToString()}")
                    
                    // Intentar diferentes variantes de nombres de columnas
                    val possibleColumns = listOf(
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        "_display_name",
                        "title",
                        "_data"
                    )
                    
                    for (columnName in possibleColumns) {
                        try {
                            val columnIndex = cursor.getColumnIndex(columnName)
                            if (columnIndex != -1) {
                                val value = cursor.getString(columnIndex)
                                if (!value.isNullOrEmpty()) {
                                    // Extraer solo el nombre del archivo si es una ruta completa
                                    val fileName = if (value.contains("/")) {
                                        value.substringAfterLast('/')
                                    } else {
                                        value
                                    }
                                    
                                    // Validar que no sea un ID o esquema raro
                                    if (!fileName.startsWith("msf:") && 
                                        !fileName.startsWith("content:") && 
                                        !fileName.matches(Regex("^[0-9]+$")) &&
                                        fileName.contains('.')) {
                                        Log.d("TaskSubmissionsFragment", "✅ Nombre obtenido via columna '$columnName': $fileName")
                                        return fileName
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d("TaskSubmissionsFragment", "No se pudo leer columna $columnName: ${e.message}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "Error obteniendo nombre via cursor genérico: ${e.message}")
        }
        
        // Estrategia 3: Parsear el path del URI
        val path = uri.path
        if (!path.isNullOrEmpty()) {
            Log.d("TaskSubmissionsFragment", "🔍 URI path: $path")
            
            // Buscar el nombre del archivo en el path
            val segments = path.split('/')
            for (segment in segments.reversed()) {
                if (segment.isNotEmpty() && segment.contains('.') && 
                    !segment.startsWith("msf:") && !segment.matches(Regex("^[0-9]+$"))) {
                    // Decodificar URL encoding si existe
                    val decodedName = try {
                        java.net.URLDecoder.decode(segment, "UTF-8")
                    } catch (e: Exception) {
                        segment
                    }
                    Log.d("TaskSubmissionsFragment", "✅ Nombre obtenido via URI path: $decodedName")
                    return decodedName
                }
            }
        }
        
        // Estrategia 4: lastPathSegment (decodificado)
        val lastSegment = uri.lastPathSegment
        if (!lastSegment.isNullOrEmpty() && !lastSegment.startsWith("msf:") && 
            !lastSegment.matches(Regex("^[0-9]+$"))) {
            
            // Intentar extraer nombre si hay separadores de path en el segment
            val cleanSegment = if (lastSegment.contains('/')) {
                lastSegment.substringAfterLast('/')
            } else if (lastSegment.contains(':')) {
                // Para casos como "msf:1004/document/primary:Download/archivo.pdf"
                lastSegment.substringAfterLast(':')
            } else {
                lastSegment
            }
            
            // Decodificar URL encoding
            val decodedSegment = try {
                java.net.URLDecoder.decode(cleanSegment, "UTF-8")
            } catch (e: Exception) {
                cleanSegment
            }
            
            if (decodedSegment.contains('.')) {
                Log.d("TaskSubmissionsFragment", "✅ Nombre obtenido via lastPathSegment: $decodedSegment")
                return decodedSegment
            }
        }
        
        // Estrategia 5: Generar nombre usando MIME type
        try {
            val mimeType = contentResolver.getType(uri)
            Log.d("TaskSubmissionsFragment", "🔍 MIME type detectado: $mimeType")
            
            if (!mimeType.isNullOrEmpty()) {
                val extension = when {
                    mimeType.contains("pdf") -> "pdf"
                    mimeType.contains("wordprocessingml") || mimeType.contains("msword") -> "docx"
                    mimeType.contains("spreadsheetml") || mimeType.contains("excel") -> "xlsx"
                    mimeType.contains("presentationml") || mimeType.contains("powerpoint") -> "pptx"
                    mimeType.contains("image/jpeg") -> "jpg"
                    mimeType.contains("image/png") -> "png"
                    mimeType.contains("image") -> "jpg"
                    mimeType.contains("text/plain") -> "txt"
                    mimeType.contains("text/sql") || mimeType.contains("sql") -> "sql"
                    mimeType.contains("application/json") || mimeType.contains("json") -> "json"
                    mimeType.contains("application/xml") || mimeType.contains("xml") -> "xml"
                    mimeType.contains("text/x-python") || mimeType.contains("python") -> "py"
                    mimeType.contains("text/x-java") || mimeType.contains("java") -> "java"
                    mimeType.contains("javascript") -> "js"
                    mimeType.contains("text/") -> "txt"
                    else -> mimeType.substringAfterLast('/').takeIf { it.length <= 5 } ?: "dat"
                }
                
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
                val generatedName = "documento_${timestamp}.$extension"
                Log.d("TaskSubmissionsFragment", "⚠️ Nombre generado desde MIME type ($mimeType): $generatedName")
                return generatedName
            }
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "Error obteniendo MIME type: ${e.message}")
        }
        
        // Fallback final: nombre descriptivo con timestamp
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
        val fallbackName = "archivo_${timestamp}.dat"
        Log.w("TaskSubmissionsFragment", "⚠️ No se pudo obtener nombre real, usando fallback: $fallbackName")
        Log.w("TaskSubmissionsFragment", "⚠️ URI completo: $uri")
        return fallbackName
    }

    private data class LocalFileAnalysisResult(
        val success: Boolean,
        val content: String = "",
        val fileType: String,
        val metadata: String = "",
        val error: String? = null
    )

    private fun getFileType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return when (extension) {
            "txt", "md", "readme" -> "text"
            "java", "kt", "py", "js", "ts", "cpp", "c", "h", "cs", "php", "rb", "go", "rs" -> "code"
            "sql" -> "sql"
            "json" -> "json"
            "xml", "html", "htm" -> "xml"
            "pdf" -> "pdf"
            "doc", "docx" -> "word"
            "xls", "xlsx" -> "excel"
            "ppt", "pptx" -> "powerpoint"
            "jpg", "jpeg", "png", "gif", "bmp" -> "image"
            "mp4", "avi", "mov", "wmv" -> "video"
            "mp3", "wav", "flac" -> "audio"
            else -> "unknown"
        }
    }

    private fun extractFileContent(uri: Uri, fileName: String): LocalFileAnalysisResult {
        return try {
            val uriString = uri.toString()
            if (uriString.startsWith("http://") || uriString.startsWith("https://")) {
                return LocalFileAnalysisResult(
                    success = true,
                    content = uriString,
                    fileType = getFileType(fileName),
                    metadata = "type: remote_url, url: $uriString"
                )
            }

            val textContent = requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }.orEmpty()

            if (textContent.isBlank()) {
                return LocalFileAnalysisResult(
                    success = false,
                    fileType = getFileType(fileName),
                    error = "El archivo está vacío o no se pudo leer su contenido."
                )
            }

            LocalFileAnalysisResult(
                success = true,
                content = textContent,
                fileType = getFileType(fileName),
                metadata = "fileName: $fileName, contentLength: ${textContent.length}, lineCount: ${textContent.lines().size}"
            )
        } catch (e: Exception) {
            LocalFileAnalysisResult(
                success = false,
                fileType = getFileType(fileName),
                error = "Error: ${e.message}"
            )
        }
    }

    private fun createStructuredContextFromAnalysis(
        fileName: String,
        analysisResult: LocalFileAnalysisResult
    ): FileContext {
        val fileType = analysisResult.fileType.ifBlank { getFileType(fileName) }
        val safeContent = when {
            analysisResult.content.isNotBlank() -> analysisResult.content
            !analysisResult.error.isNullOrBlank() -> analysisResult.error
            else -> "Archivo sin contenido extraíble"
        }
        return FileContext(
            submissionId = 0,
            fileName = sanitizeFileName(fileName, fileType),
            fileType = fileType,
            fileContent = safeContent,
            extractedText = safeContent,
            metadata = analysisResult.metadata.ifBlank { null },
            contentSummary = if (analysisResult.success) {
                "Archivo procesado (${safeContent.length} caracteres)."
            } else {
                "No se pudo procesar completamente el archivo."
            }
        )
    }
    
    /**
     * Copia un archivo al almacenamiento interno de la app para evitar problemas de permisos
     * Maneja tanto archivos locales como archivos de Google Drive
     * Retorna el URI del archivo copiado
     */
    private fun copyFileToInternalStorage(sourceUri: Uri, fileName: String): Uri {
        try {
            Log.d("TaskSubmissionsFragment", "🔄 Copiando archivo a almacenamiento interno...")
            Log.d("TaskSubmissionsFragment", "📎 Source URI: $sourceUri")
            Log.d("TaskSubmissionsFragment", "📎 Scheme: ${sourceUri.scheme}, Authority: ${sourceUri.authority}")
            
            val contentResolver = requireContext().contentResolver
            
            // Intentar abrir el stream (funciona para archivos locales y Google Drive)
            val inputStream = contentResolver.openInputStream(sourceUri)
                ?: throw IllegalArgumentException("No se pudo abrir el archivo de origen")
            
            // Crear directorio de tareas si no existe
            val tasksDir = File(requireContext().filesDir, "task_submissions")
            if (!tasksDir.exists()) {
                val created = tasksDir.mkdirs()
                Log.d("TaskSubmissionsFragment", "📁 Directorio creado: $created")
            }
            
            // Sanitizar el nombre del archivo
            val sanitizedFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val timestamp = System.currentTimeMillis()
            val destinationFile = File(tasksDir, "${timestamp}_$sanitizedFileName")
            
            Log.d("TaskSubmissionsFragment", "📝 Destino: ${destinationFile.absolutePath}")
            
            // Copiar el archivo con buffer para archivos grandes
            var bytesCopied = 0L
            inputStream.use { input ->
                destinationFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(8192)
                    var bytes = input.read(buffer)
                    while (bytes >= 0) {
                        output.write(buffer, 0, bytes)
                        bytesCopied += bytes
                        bytes = input.read(buffer)
                    }
                }
            }
            
            Log.i("TaskSubmissionsFragment", "✅ Archivo copiado exitosamente: ${destinationFile.absolutePath}")
            Log.i("TaskSubmissionsFragment", "📊 Tamaño: ${bytesCopied / 1024} KB")
            return Uri.fromFile(destinationFile)
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "❌ Error copiando archivo a almacenamiento interno: ${e.message}", e)
            throw e
        }
    }
}