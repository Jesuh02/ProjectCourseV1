package com.example.tareamov.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import com.example.tareamov.data.repository.SupabaseRepository
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.data.entity.FileContext
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.service.FileAnalysisService
import com.example.tareamov.service.FileConverterService
import com.example.tareamov.service.MCPService
import com.example.tareamov.service.SupabaseClient
import com.example.tareamov.util.CalificationManager
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.io.File
import com.bumptech.glide.Glide
import de.hdodenhof.circleimageview.CircleImageView

class TaskSubmissionsFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SubmissionsAdapter
    private lateinit var sessionManager: SessionManager
    private lateinit var fileAnalysisService: FileAnalysisService
    private lateinit var fileConverterService: FileConverterService
    private lateinit var mcpService: MCPService
    private lateinit var database: com.example.tareamov.data.AppDatabase
    private lateinit var syncRepository: com.example.tareamov.data.sync.SyncRepository
    private var taskId: Long = -1
    private var taskName: String = ""
    private var courseCreatorUsername: String? = null
    private var isCourseCreator: Boolean = false
    private var selectedFileUri: Uri? = null
    private var hasUserSubmitted = false
    private var userSubmission: TaskSubmission? = null
    
    // Información de la tarea, tema y curso
    private var taskDescription: String = ""
    private var topicName: String = ""
    private var courseTitle: String = ""
    private var courseDescription: String = ""

    // Progress UI elements
    private lateinit var progressBar: ProgressBar
    private lateinit var progressTextView: TextView
    private lateinit var progressSection: LinearLayout

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Verificar si el URI es de Google Drive
            val isGoogleDriveUri = uri.authority?.contains("google") == true || 
                                   uri.authority?.contains("docs") == true ||
                                   uri.toString().contains("google") ||
                                   uri.toString().contains("docs.google.com")
            
            if (isGoogleDriveUri) {
                // Mostrar mensaje de error si es un archivo de Google Drive
                Toast.makeText(
                    context, 
                    "⚠️ Los archivos de Google Drive no son compatibles. Por favor, descarga el archivo a tu almacenamiento local primero.", 
                    Toast.LENGTH_LONG
                ).show()
                
                // No asignar el URI de Google Drive
                selectedFileUri = null
                view?.findViewById<TextView>(R.id.selectedFileNameTextView)?.text = "Ningún archivo seleccionado"
            } else {
                // Es un archivo local, procesarlo normalmente
                selectedFileUri = uri
                // Usar getFileName() para mostrar el nombre real del archivo
                val displayFileName = getFileName(uri) ?: uri.lastPathSegment ?: "Archivo seleccionado"
                view?.findViewById<TextView>(R.id.selectedFileNameTextView)?.text = displayFileName
                Log.d("TaskSubmissionsFragment", "📎 Archivo seleccionado: $displayFileName")

                // Take persistable URI permission
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            taskId = it.getLong("taskId", -1)
            taskName = it.getString("taskName", "")
            courseCreatorUsername = it.getString("courseCreatorUsername")
        }
        sessionManager = SessionManager.getInstance(requireContext())
        fileAnalysisService = FileAnalysisService(requireContext())
    fileConverterService = FileConverterService(requireContext())
        mcpService = MCPService(requireContext())
    val currentUsername = sessionManager.getUsername()
        isCourseCreator = (courseCreatorUsername != null && courseCreatorUsername == currentUsername)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_task_submissions, container, false)

        // Initialize database
        database = com.example.tareamov.data.AppDatabase.getDatabase(requireContext())

        // Initialize SyncRepository with database DAOs
        syncRepository = com.example.tareamov.data.sync.SyncRepository(
            usuarioDao = database.usuarioDao(),
            personaDao = database.personaDao(),
            topicDao = database.topicDao(),
            contentItemDao = database.contentItemDao(),
            taskDao = database.taskDao(),
            subscriptionDao = database.subscriptionDao(),
            taskSubmissionDao = database.taskSubmissionDao(),
            videoDao = database.videoDao(),
            courseDao = database.courseDao(),
            rolDao = database.rolDao(),
            recursoDao = database.recursoDao(),
            rolRecursoDao = database.rolRecursoDao(),
            chatMessageDao = database.chatMessageDao(),
            fileContextDao = database.fileContextDao(),
            progresoEstudianteDao = database.progresoEstudianteDao()
        )

        val titleTextView = view.findViewById<TextView>(R.id.taskTitleTextView)
        titleTextView.text = taskName

        // Initialize progress UI elements
        progressSection = view.findViewById(R.id.progressSection)
        progressBar = view.findViewById(R.id.taskProgressBar)
        progressTextView = view.findViewById(R.id.progressTextView)

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

        // Setup file upload section
        val uploadSection = view.findViewById<LinearLayout>(R.id.uploadSection)
        val selectFileButton = view.findViewById<Button>(R.id.selectFileButton)
        val submitFileButton = view.findViewById<Button>(R.id.submitFileButton)
        val selectedFileNameTextView = view.findViewById<TextView>(R.id.selectedFileNameTextView)
        val mySubmissionStatusTextView = view.findViewById<TextView>(R.id.mySubmissionStatusTextView)
        
        // Configure visibility based on user role
        if (isCourseCreator) {
            // Course creator sees progress of all students
            progressSection.visibility = View.VISIBLE
            uploadSection.visibility = View.GONE
            loadTaskProgress()
        } else {
            // Regular student sees their own progress
            progressSection.visibility = View.VISIBLE
            uploadSection.visibility = View.VISIBLE
            selectFileButton.setOnClickListener { openFilePicker() }
            submitFileButton.setOnClickListener { submitTaskFile() }

            // Check if user has already submitted this task
            checkUserSubmission(mySubmissionStatusTextView)
        }

        loadSubmissions()
        checkAndApplyPendingCalifications()
        loadTaskWithCourseInfo()
        return view
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
        // Implementar lógica para aplicar a entrega específica si es necesario
        // Por ahora, aplicar a la entrega del usuario actual
        applyCalificationToCurrentUserSubmission(calificationData)
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
                    // Fetch submissions from Supabase and find the user's submission for this task
                    val all = try {
                        SupabaseClient.fetchTaskSubmissions()
                    } catch (e: Exception) {
                        emptyList<com.example.tareamov.data.entity.TaskSubmission>()
                    }
                    all.firstOrNull { it.taskId == taskId && it.studentId == currentUserId }
                }

                if (userSubmission != null) {
                    // Convertir la calificación de String a Float
                    val gradeFloat = calificationData.grade.toFloatOrNull()
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
                // Fetchtask, topic and course info from Supabase (remote-first)
                val taskInfo = withContext(Dispatchers.IO) {
                    try {
                        val task = SupabaseClient.fetchTaskById(taskId)
                        if (task != null) {
                            val topic = task.topicId?.let { tid -> SupabaseClient.fetchTopics().firstOrNull { it.id == tid } }
                            val course = topic?.courseId?.let { cid -> SupabaseClient.fetchCourseById(cid) }
                            mapOf(
                                "taskName" to (task.name ?: ""),
                                "taskDescription" to (task.description ?: "Sin descripción"),
                                "topicName" to (topic?.name ?: ""),
                                "courseTitle" to (course?.title ?: ""),
                                "courseDescription" to (course?.description ?: "")
                            )
                        } else null
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching task/topic/course from Supabase", e)
                        null
                    }
                }
                
                if (taskInfo != null) {
                    // Actualizar las variables de instancia
                    taskName = taskInfo["taskName"] as String
                    taskDescription = taskInfo["taskDescription"] as String
                    topicName = taskInfo["topicName"] as String
                    courseTitle = taskInfo["courseTitle"] as String
                    courseDescription = taskInfo["courseDescription"] as String
                    
                    Log.d("TaskSubmissionsFragment", "Información de tarea cargada: $taskName - $topicName - $courseTitle")
                    
                    // Actualizar el adaptador para mostrar la nueva información
                    adapter.notifyDataSetChanged()
                } else {
                    Log.e("TaskSubmissionsFragment", "No se pudo cargar la información completa de la tarea")
                }
                
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error cargando información de tarea", e)
            }
        }
    }

    private fun loadTaskProgress() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Determine courseId, subscribers and submissions via Supabase
                val courseId = withContext(Dispatchers.IO) {
                    try {
                        val task = SupabaseClient.fetchTaskById(taskId)
                        val topic = task?.topicId?.let { tid -> SupabaseClient.fetchTopics().firstOrNull { it.id == tid } }
                        topic?.courseId
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching task/topic from Supabase", e)
                        null
                    }
                }

                if (courseId == null) {
                    Log.e("TaskSubmissionsFragment", "Could not determine course ID from Supabase")
                    return@launch
                }

                val students = withContext(Dispatchers.IO) {
                    try {
                        val subs = SupabaseClient.fetchSubscriptions()
                        val creatorUsername = courseCreatorUsername
                        
                        // Resolve creator ID
                        val creatorId = if (creatorUsername != null) {
                            SupabaseClient.fetchUsuarioWithRoleByUsername(creatorUsername).first?.id ?: -1L
                        } else {
                            val course = SupabaseClient.fetchCourseById(courseId)
                            course?.creatorUserId ?: -1L
                        }

                        if (creatorId != -1L) {
                            val subscriberIds = subs.filter { it.creatorId == creatorId }.map { it.subscriberId }
                            // Resolve usernames from IDs
                            subscriberIds.mapNotNull { id -> SupabaseClient.getUsernameFromUserId(id) }
                        } else {
                            emptyList<String>()
                        }
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching subscriptions from Supabase", e)
                        emptyList<String>()
                    }
                }

                val submissions = withContext(Dispatchers.IO) {
                    try {
                        SupabaseClient.fetchTaskSubmissions().filter { it.taskId == taskId }
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching submissions from Supabase", e)
                        emptyList<com.example.tareamov.data.entity.TaskSubmission>()
                    }
                }

                // Calculate progress
                val totalStudents = students.size
                val submittedCount = submissions.size
                val gradedCount = submissions.count { it.grade != null && it.grade > 0 } // Solo contar como calificado si la nota es mayor a 0

                // Update UI
                if (totalStudents > 0) {
                    val submissionPercentage = (submittedCount * 100) / totalStudents
                    progressBar.max = 100
                    progressBar.progress = submissionPercentage

                    progressTextView.text = "$submittedCount de $totalStudents estudiantes han entregado " +
                            "($gradedCount calificados)"
                } else {
                    progressBar.progress = 0
                    progressTextView.text = "No hay estudiantes inscritos en este curso"
                }

            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error loading task progress", e)
            }
        }
    }

    private fun checkUserSubmission(statusTextView: TextView) {
        val currentUserId = sessionManager.getUserId() ?: return

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val submission = withContext(Dispatchers.IO) {
                    try {
                        SupabaseClient.fetchTaskSubmissions().firstOrNull { it.taskId == taskId && it.studentId == currentUserId }
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching user submission from Supabase", e)
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

                    val gradeText = if (submission.grade != null && submission.grade > 0) {
                        "Calificación: ${submission.grade}/10"
                    } else {
                        "Pendiente de calificación"
                    }

                    statusTextView.text = "Enviado el $dateString\n$gradeText"
                    statusTextView.setTextColor(resources.getColor(android.R.color.holo_green_light, null))

                    // Update progress for student
                    progressBar.max = 100
                    progressBar.progress = if (submission.grade != null && submission.grade > 0) 100 else 50
                    progressTextView.text = if (submission.grade != null && submission.grade > 0)
                        "Tarea completada y calificada"
                    else
                        "Tarea entregada, pendiente de calificación"

                    // Disable submit button
                    view?.findViewById<Button>(R.id.submitFileButton)?.isEnabled = false
                    view?.findViewById<Button>(R.id.submitFileButton)?.text = "Ya enviado"
                } else {
                    // User hasn't submitted yet
                    hasUserSubmitted = false
                    statusTextView.text = "No has enviado ninguna tarea aún"
                    statusTextView.setTextColor(resources.getColor(android.R.color.darker_gray, null))

                    // Update progress for student
                    progressBar.max = 100
                    progressBar.progress = 0
                    progressTextView.text = "Tarea pendiente de entrega"
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
                        val all = SupabaseClient.fetchTaskSubmissions().filter { it.taskId == taskId }
                        android.util.Log.d("TaskSubmissionsFragment", "fetchTaskSubmissions returned ${all.size} total items; filtered by taskId=$taskId -> ${all.count { it.taskId == taskId }}")
                        // Log a small JSON sample of returned submissions for debugging
                        try {
                            val gson = com.google.gson.Gson()
                            val sample = all.take(5)
                            android.util.Log.d("TaskSubmissionsFragment", "Sample submissions JSON: ${gson.toJson(sample)}")
                        } catch (e: Exception) {
                            android.util.Log.w("TaskSubmissionsFragment", "Failed to serialize sample submissions to JSON", e)
                        }
                        if (isCourseCreator) all
                        else {
                            val userId = sessionManager.getUserId()
                                if (userId != null) all.filter { it.studentId == userId } else emptyList()
                        }
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching submissions from Supabase", e)
                        emptyList<com.example.tareamov.data.entity.TaskSubmission>()
                    }
                }

                if (submissions.isEmpty()) {
                    // Show empty state
                    view?.findViewById<TextView>(R.id.emptyStateTextView)?.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    // Show submissions
                    view?.findViewById<TextView>(R.id.emptyStateTextView)?.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    adapter.updateSubmissions(submissions)
                }

            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error loading submissions", e)
                Toast.makeText(context, "Error al cargar entregas: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateSubmissionGrade(submission: TaskSubmission, grade: Float, feedback: String) {
        lifecycleScope.launch {
            try {
                val updatedSubmission = submission.copy(grade = grade, feedback = feedback)
                try {
                    val pushed = withContext(Dispatchers.IO) {
                        SupabaseClient.updateTaskSubmissionRemote(updatedSubmission)
                    }
                    if (pushed) {
                        Log.i("TaskSubmissionsFragment", "✅ Updated submission pushed to Supabase.")
                        Toast.makeText(context, "Calificación enviada al servidor", Toast.LENGTH_SHORT).show()
                        
                        // Trigger progress update event for the graded student
                        triggerProgressUpdateEvent(submission.studentUsername, taskId)
                        
                        // IMPORTANTE: Recalcular progreso de TODOS los estudiantes del curso
                        // No solo del estudiante actual, para mantener consistencia
                        recalculateAllStudentsProgressForCourse()
                    } else {
                        Log.w("TaskSubmissionsFragment", "Failed to push updated submission to Supabase.")
                        Toast.makeText(context, "Calificación guardada localmente; se reintentará subirla más tarde.", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Exception pushing updated submission to Supabase", e)
                    Toast.makeText(context, "Error enviando calificación al servidor; se reintentará.", Toast.LENGTH_SHORT).show()
                }

                // Update UI
                Toast.makeText(context, "Calificación procesada", Toast.LENGTH_SHORT).show()
                loadSubmissions()
                if (isCourseCreator) loadTaskProgress()
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error updating grade", e)
                Toast.makeText(context, "Error al guardar calificación: ${e.message}", Toast.LENGTH_SHORT).show()
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
            
            // Obtener courseId desde la tarea actual
            val courseId = withContext(Dispatchers.IO) {
                try {
                    val task = SupabaseClient.fetchTaskById(taskId)
                    val topic = task?.topicId?.let { tid -> 
                        SupabaseClient.fetchTopics().firstOrNull { it.id == tid } 
                    }
                    topic?.courseId
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error getting courseId from Supabase", e)
                    null
                }
            }

            if (courseId == null) {
                Log.w("TaskSubmissionsFragment", "❌ Could not determine courseId, skipping progress recalculation")
                return
            }

            Log.d("TaskSubmissionsFragment", "📚 Found courseId: $courseId")
            
            // Recalcular progreso de todos los estudiantes
            val updatedCount = withContext(Dispatchers.IO) {
                syncRepository.recalculateAllStudentProgressForCourse(courseId)
            }
            
            Log.i("TaskSubmissionsFragment", "✅ Updated progress for $updatedCount students")
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "❌ Error recalculating all students progress", e)
        }
    }

    /**
     * (DEPRECATED: Usar recalculateAllStudentsProgressForCourse en su lugar)
     * Recalcula el progreso del estudiante y lo sincroniza a Supabase.
     * Este método se llama cuando:
     * - Se actualiza una calificación de una entrega
     * - Se crea una nueva entrega del estudiante
     */
    private suspend fun recalculateAndSyncStudentProgress(studentUsername: String) {
        try {
            Log.d("TaskSubmissionsFragment", "🔄 Starting progress recalculation for student: $studentUsername")
            
            // Obtener courseId desde la tarea actual
            val courseId = withContext(Dispatchers.IO) {
                try {
                    val task = SupabaseClient.fetchTaskById(taskId)
                    val topic = task?.topicId?.let { tid -> 
                        SupabaseClient.fetchTopics().firstOrNull { it.id == tid } 
                    }
                    topic?.courseId
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error getting courseId from Supabase", e)
                    null
                }
            }

            if (courseId == null) {
                Log.w("TaskSubmissionsFragment", "❌ Could not determine courseId, skipping progress sync")
                return
            }

            Log.d("TaskSubmissionsFragment", "📚 Found courseId: $courseId")

            // Obtener todas las submissions del estudiante en el curso DESDE SUPABASE
            val submissions = withContext(Dispatchers.IO) {
                try {
                    val allSubmissions = SupabaseClient.fetchTaskSubmissions()
                    // Filtrar submissions del estudiante en este curso
                    val courseTopics = SupabaseClient.fetchTopicsByCourse(courseId)
                    val topicIds = courseTopics.map { it.id }
                    val courseTasks = if (topicIds.isNotEmpty()) {
                        SupabaseClient.fetchTasksByTopicIds(topicIds)
                    } else {
                        emptyList()
                    }
                    val taskIds = courseTasks.map { it.id }.toSet()
                    
                    allSubmissions.filter { 
                        it.studentUsername.equals(studentUsername, ignoreCase = true) && 
                        it.taskId in taskIds 
                    }
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error fetching submissions from Supabase", e)
                    emptyList()
                }
            }

            // Obtener todas las tareas del curso DESDE SUPABASE
            val allTasks = withContext(Dispatchers.IO) {
                try {
                    val topics = SupabaseClient.fetchTopicsByCourse(courseId)
                    val topicIds = topics.map { it.id }
                    if (topicIds.isNotEmpty()) {
                        SupabaseClient.fetchTasksByTopicIds(topicIds)
                    } else {
                        emptyList()
                    }
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error fetching tasks from Supabase", e)
                    emptyList()
                }
            }

            Log.d("TaskSubmissionsFragment", "📊 Tasks in course: ${allTasks.size}, Submissions: ${submissions.size}")

            // Calcular métricas
            val tareasTotales = allTasks.size
            val tareasCompletadas = submissions.count { (it.grade ?: 0f) > 0f }
            val porcentajeProgreso = if (tareasTotales > 0) {
                (tareasCompletadas.toFloat() / tareasTotales.toFloat()) * 100f
            } else {
                0f
            }

            val totalGrade = submissions.mapNotNull { it.grade }.sum()
            val taskCount = submissions.size
            val promedio = if (taskCount > 0) totalGrade / taskCount else 0f

            Log.d("TaskSubmissionsFragment", "📈 Calculated: total=$tareasTotales, completed=$tareasCompletadas, progress=$porcentajeProgreso%, avg=$promedio")

            // Get user ID from username
            val userId = withContext(Dispatchers.IO) {
                com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(studentUsername)
            }
            if (userId == null) {
                Log.e("TaskSubmissionsFragment", "Failed to get user ID for username: $studentUsername")
                return
            }

            // Crear ProgresoEstudiante actualizado
            val updatedProgreso = com.example.tareamov.data.entity.ProgresoEstudiante(
                usuarioEstudiante = userId,
                cursoId = courseId,
                tareasTotales = tareasTotales,
                tareasCompletadas = tareasCompletadas,
                porcentajeProgreso = porcentajeProgreso,
                promedio = promedio,
                calificacionPonderada = promedio,
                ultimaCalculadaEn = System.currentTimeMillis(),
                certificadoEmitidoEn = null,
                creadoEn = System.currentTimeMillis()
            )

            // Actualizar en base de datos local
            withContext(Dispatchers.IO) {
                try {
                    database.progresoEstudianteDao().upsert(updatedProgreso)
                    Log.d("TaskSubmissionsFragment", "✅ Updated local database")
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error updating local database", e)
                }
            }

            // Sincronizar DIRECTAMENTE a Supabase
            val synced = withContext(Dispatchers.IO) {
                try {
                    SupabaseClient.upsertProgresoEstudiante(updatedProgreso)
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error calling upsertProgresoEstudiante", e)
                    false
                }
            }

            if (synced) {
                Log.i("TaskSubmissionsFragment", "✅ Synced progress to Supabase for student=$studentUsername: total=$tareasTotales, completed=$tareasCompletadas, progress=$porcentajeProgreso%, avg=$promedio")
            } else {
                Log.w("TaskSubmissionsFragment", "⚠️ Failed to sync progress to Supabase for student=$studentUsername")
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
        if (currentUserId == null) {
            Toast.makeText(context, "Debes iniciar sesión para enviar tareas", Toast.LENGTH_SHORT).show()
            return
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
        
        // Mostrar progreso mientras se procesa
        progressSection.visibility = View.VISIBLE
        progressBar.isIndeterminate = true
        progressTextView.text = "Analizando archivo $fileName..."
        
        val submission = TaskSubmission(
            taskId = taskId,

            studentId = currentUserId,
            fileUri = uri.toString(),

            studentUsername = username,
            fileUri = finalUri.toString(),
            fileName = fileName,
            submissionDate = System.currentTimeMillis(),
            grade = 0.0f, // Nota por defecto 0 en lugar de null
            feedback = null
        )

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // PASO 1: Extraer el contenido del archivo ANTES de subirlo
                Log.d("TaskSubmissionsFragment", "🔄 Extrayendo contenido del archivo antes de subir...")
                val analysisResult = withContext(Dispatchers.IO) {
                    fileAnalysisService.extractFileContent(uri, fileName)
                }
                Log.d("TaskSubmissionsFragment", "📊 Contenido extraído: ${analysisResult.content.take(100)}...")

                progressTextView.text = "Generando contexto estructurado..."
                var structuredFileContext: FileContext? = null
                try {
                    structuredFileContext = withContext(Dispatchers.IO) {
                        fileConverterService.convertFileToStructuredJson(uri, fileName)
                    }
                    structuredFileContext?.let {
                        Log.d(
                            "TaskSubmissionsFragment",
                            "🧩 FileConverterService generó contexto -> tipo=${it.fileType}, longitud=${it.fileContent.length}"
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

                progressTextView.text = "Subiendo tarea al servidor..."
                progressBar.progress = 30
                
                try {
                    // Directly insert submission to Supabase
                    Log.d("TaskSubmissionsFragment", "📤 Intentando insertar TaskSubmission en Supabase...")
                    Log.d("TaskSubmissionsFragment", "📤 Datos: taskId=$taskId, studentUsername=$username, fileName=$fileName")
                    
                    val remoteId = withContext(Dispatchers.IO) {
                        SupabaseClient.insertTaskSubmission(submission)
                    }
                    if (remoteId != null) {
                        Log.i("TaskSubmissionsFragment", "✅ Supabase insertTaskSubmission returned remote id=$remoteId")
                        Toast.makeText(context, "Tarea subida a servidor (id=$remoteId)", Toast.LENGTH_SHORT).show()

                        // Trigger progress update event after successful submission
                        triggerProgressUpdateEvent(username, taskId)

                        // Poll Supabase for the newly created submission (the backend may be eventually consistent)
                        val created = withContext(Dispatchers.IO) {
                            var found: com.example.tareamov.data.entity.TaskSubmission? = null
                            repeat(6) { attempt ->
                                try {
                                    val all = SupabaseClient.fetchTaskSubmissions()
                                    found = all.firstOrNull { it.id == remoteId }
                                    if (found != null) return@withContext found
                                } catch (e: Exception) {
                                    val attemptNum = attempt + 1
                                    Log.w("TaskSubmissionsFragment", "Attempt $attemptNum fetch created submission failed: ${e.message}")
                                }
                                kotlinx.coroutines.delay(500)
                            }
                            found
                        }

                        if (created != null) {
                            // PASO 2: Crear el FileContext con el contenido extraído
                            progressTextView.text = "Guardando contexto del archivo..."
                            progressBar.progress = 70
                            
                            val createdSubmissionId = created.id
                            val taskDescription = withContext(Dispatchers.IO) {
                                try {
                                    val task = SupabaseClient.fetchTaskById(taskId)
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
                                "📦 FileContext final -> nombre=${fileContext.fileName}, tipo=${fileContext.fileType}, longitud=${fileContext.fileContent.length}"
                            )
                            
                            // Guardar FileContext en la base de datos local Y en Supabase
                            withContext(Dispatchers.IO) {
                                try {
                                    // 1. Guardar en base de datos local
                                    database.fileContextDao().insertFileContext(fileContext)
                                    Log.d("TaskSubmissionsFragment", "✅ FileContext guardado en BD local para submission $createdSubmissionId")
                                    
                                    // 2. Enviar a Supabase
                                    Log.d("TaskSubmissionsFragment", "📤 Intentando insertar FileContext en Supabase...")
                                    Log.d("TaskSubmissionsFragment", "📤 Datos: submissionId=$createdSubmissionId, fileName=${fileContext.fileName}, contentLength=${fileContext.fileContent.length}")
                                    
                                    val remoteFileContextId = SupabaseClient.insertFileContext(fileContext)
                                    if (remoteFileContextId != null) {
                                        Log.d("TaskSubmissionsFragment", "✅ FileContext enviado a Supabase con ID remoto: ${remoteFileContextId}")
                                    } else {
                                        Log.w("TaskSubmissionsFragment", "⚠️ FileContext no pudo ser enviado a Supabase (quedó solo en BD local)")
                                        Log.w("TaskSubmissionsFragment", "⚠️ Esto puede causar que el contexto no esté disponible al seleccionar la tarea con #")
                                    }
                                } catch (e: Exception) {
                                    Log.e("TaskSubmissionsFragment", "❌ Error guardando/enviando FileContext", e)
                                }
                            }
                            
                            progressTextView.text = "¡Tarea enviada exitosamente!"
                            progressBar.progress = 100
                            
                            // IMPORTANTE: Recalcular y sincronizar progreso del estudiante
                            recalculateAndSyncStudentProgress(username)
                            
                            // Refresh the submissions list from Supabase to ensure frontend data comes from server
                            loadSubmissions()
                        } else {
                            // If not found after retries, still refresh as a best-effort
                            Log.w("TaskSubmissionsFragment", "Created submission not found on Supabase after retries; refreshing list")
                            loadSubmissions()
                        }
                    } else {
                        Log.w("TaskSubmissionsFragment", "Supabase insertTaskSubmission returned null")
                        Toast.makeText(context, "Tarea enviada (pendiente de confirmación en servidor)", Toast.LENGTH_SHORT).show()
                        // Try to reload once in case it appears shortly
                        loadSubmissions()
                    }
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error sending submission to Supabase", e)
                    Toast.makeText(context, "Error al enviar tarea al servidor: ${e.message}", Toast.LENGTH_SHORT).show()
                    progressSection.visibility = View.GONE
                }
                selectedFileUri = null
                view?.findViewById<TextView>(R.id.selectedFileNameTextView)?.text = "Ningún archivo seleccionado"

                // Update submission status (UI only)
                val statusTextView = view?.findViewById<TextView>(R.id.mySubmissionStatusTextView)
                if (statusTextView != null) {
                    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                    val dateString = dateFormat.format(System.currentTimeMillis())
                    statusTextView.text = "Enviado el $dateString\nPendiente de calificación"
                    statusTextView.setTextColor(resources.getColor(android.R.color.holo_green_light, null))
                }

                // Update progress after submission - ocultar barra después de 2 segundos
                kotlinx.coroutines.delay(2000)
                progressSection.visibility = View.GONE
                
                // Reset progress bar for next submission
                progressBar.max = 100
                progressBar.progress = 0
                progressBar.isIndeterminate = false
                progressTextView.text = "0% completado"

                // Disable submit button
                view?.findViewById<Button>(R.id.submitFileButton)?.isEnabled = false
                view?.findViewById<Button>(R.id.submitFileButton)?.text = "Ya enviado"

                // Reload submissions to show the new one
                loadSubmissions()
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "Error submitting task", e)
                Toast.makeText(context, "Error al enviar tarea: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun buildFileContextForSubmission(
        submissionId: Long,
        originalFileName: String,
        structuredContext: FileContext?,
        analysisResult: FileAnalysisService.FileAnalysisResult,
        taskDescription: String
    ): FileContext {
        val fallbackType = analysisResult.fileType.name.lowercase(Locale.getDefault())
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
            if (it.fileContent.isNotBlank()) {
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
    private fun triggerProgressUpdateEvent(username: String, submittedTaskId: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("TaskSubmissionsFragment", "🔄 Triggering progress update event for $username, task $submittedTaskId")
                
                // Get course ID from task
                val task = database.taskDao().getTaskById(submittedTaskId)
                if (task == null) {
                    Log.e("TaskSubmissionsFragment", "❌ Task not found: $submittedTaskId")
                    return@launch
                }
                
                val topic = database.topicDao().getTopicById(task.topicId)
                if (topic == null) {
                    Log.e("TaskSubmissionsFragment", "❌ Topic not found: ${task.topicId}")
                    return@launch
                }
                
                val courseId = topic.courseId
                
                // Recalculate progress for this student
                // Get all topics for this course, then all tasks for those topics
                val allTopics = database.topicDao().getTopicsByCourse(courseId)
                val topicIds = allTopics.map { it.id }
                val allTasksInCourse = database.taskDao().getTasksByTopicIds(topicIds)
                val totalTasks = allTasksInCourse.size
                
                // Get all submissions by this student
                val allSubmissions = database.taskSubmissionDao()
                    .getSubmissionsByStudent(username)
                
                // Filter submissions that belong to tasks in this course
                val taskIdsInCourse = allTasksInCourse.map { it.id }.toSet()
                val courseSubmissions = allSubmissions.filter { it.taskId in taskIdsInCourse }
                
                val completedTasks = courseSubmissions.count { (it.grade ?: 0f) > 0 }
                
                val progressPct = if (totalTasks > 0) {
                    (completedTasks.toFloat() / totalTasks.toFloat()) * 100
                } else 0f
                
                val gradesOnly = courseSubmissions.mapNotNull { it.grade }.filter { it > 0 }
                val avgGrade = if (gradesOnly.isNotEmpty()) {
                    gradesOnly.average().toFloat()
                } else 0f
                
                // Get user ID from username
                val userId = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(username)
                if (userId == null) {
                    Log.e("TaskSubmissionsFragment", "Failed to get user ID for username: $username")
                    return@launch
                }
                
                // Update progress in database and Supabase
                var progreso = database.progresoEstudianteDao()
                    .getProgresoByUsuarioAndCurso(userId, courseId)
                
                if (progreso != null) {
                    // Create updated copy since properties are val
                    val updatedProgreso = progreso.copy(
                        tareasTotales = totalTasks,
                        tareasCompletadas = completedTasks,
                        porcentajeProgreso = progressPct,
                        promedio = avgGrade,
                        calificacionPonderada = avgGrade,
                        ultimaCalculadaEn = System.currentTimeMillis()
                    )
                    
                    database.progresoEstudianteDao().updateProgreso(updatedProgreso)
                    
                    // Sync to Supabase
                    val synced = syncRepository.syncProgresoToSupabase(updatedProgreso)
                    if (synced) {
                        Log.i("TaskSubmissionsFragment", "✅ Progress synced: $completedTasks/$totalTasks tasks, ${progressPct.toInt()}%, avg=$avgGrade")
                    } else {
                        Log.w("TaskSubmissionsFragment", "⚠️ Progress updated locally but failed to sync to Supabase")
                    }
                } else {
                    Log.w("TaskSubmissionsFragment", "⚠️ No progreso record found for $username in course $courseId")
                }
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "❌ Error in progress update event", e)
            }
        }
    }

    private fun openFilePicker() {
        // Mostrar mensaje para aclarar que deben seleccionar archivos locales
        Toast.makeText(
            context, 
            "Selecciona un archivo de tu almacenamiento local. Los archivos de Google Drive no son compatibles.", 
            Toast.LENGTH_LONG
        ).show()
        
        // Lanzar selector de archivos
        filePickerLauncher.launch(arrayOf("*/*"))
    }

    private fun createFileContextAndNavigateToChat(submission: TaskSubmission) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Log.d("TaskSubmissionsFragment", "🔄 Iniciando análisis de archivo: ${submission.fileName}")
                progressSection.visibility = View.VISIBLE
                progressBar.isIndeterminate = true
                progressTextView.text = "Procesando archivo ${submission.fileName}..."
                val uri = Uri.parse(submission.fileUri)
                Log.d("TaskSubmissionsFragment", "📂 URI del archivo: $uri")
                val analysisResult = withContext(Dispatchers.IO) {
                    fileAnalysisService.extractFileContent(uri, submission.fileName)
                }
                Log.d("TaskSubmissionsFragment", "📊 Resultado del análisis - Éxito: ${analysisResult.success}")
                val fileType = fileAnalysisService.getFileType(submission.fileName)
                Log.d("TaskSubmissionsFragment", "📄 Tipo de archivo detectado: $fileType para archivo: ${submission.fileName}")
                Log.d("TaskSubmissionsFragment", "📝 Extensión del archivo: ${submission.fileName.substringAfterLast('.', "sin extensión")}")
                Log.d("TaskSubmissionsFragment", "📝 Contenido extraído (${analysisResult.content.length} caracteres): ${analysisResult.content.take(100)}...")
                // Obtener la descripción de la tarea desde Supabase (remote-first)
                val taskDescription = withContext(Dispatchers.IO) {
                    try {
                        val task = SupabaseClient.fetchTaskById(submission.taskId)
                        Log.d("TaskSubmissionsFragment", "Fetched task from Supabase for submission ${submission.id}: ${task != null}")
                        task?.description ?: ""
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching task from Supabase for submission ${submission.id}", e)
                        ""
                    }
                }
                
                // FALLBACK: Si taskDescription está vacío, usar el nombre de la tarea (fetched from Supabase)
                val finalTaskDescription = if (taskDescription.isNotEmpty()) {
                    taskDescription
                } else {
                    withContext(Dispatchers.IO) {
                        try {
                            val task = SupabaseClient.fetchTaskById(submission.taskId)
                            val fallbackDescription = "Tarea: ${task?.name ?: "Sin nombre"}"
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
                    fileType = fileType.name,
                    fileContent = analysisResult.content,
                    extractedText = analysisResult.content,
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
                
                progressTextView.text = "Archivo procesado. Preparando interfaz de chat..."
                progressBar.isIndeterminate = false
                progressBar.progress = 90
                navigateToChatWithFileContext(fileContext)
            } catch (e: Exception) {
                Log.e("TaskSubmissionsFragment", "❌ Error procesando archivo: ${e.message}", e)
                
                // Mostrar error al usuario
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
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
                
                // PRIMERA ESTRATEGIA: Intentar con el MCP Service para convertir a JSON
                // Esta es la estrategia preferida para cualquier tipo de archivo
                try {
                    Log.d("TaskSubmissionsFragment", "🌐 Intentando procesar con MCP Service")
                    
                    // Verificar si el servidor MCP está disponible
                    val isMcpAvailable = mcpService.testMCPServerConnection()
                    
                    if (isMcpAvailable) {
                        // El servidor MCP está disponible, usar para convertir el archivo a JSON
                        Log.d("TaskSubmissionsFragment", "✅ Servidor MCP disponible, convirtiendo archivo a JSON")
                        Toast.makeText(context, "Convirtiendo archivo con MCP...", Toast.LENGTH_SHORT).show()
                        
                        val fileContext = withContext(Dispatchers.IO) {
                            mcpService.convertFileToJson(Uri.parse(submission.fileUri), submission.fileName)
                        }
                        
                        // Actualizar el ID de la entrega
                        val updatedFileContext = fileContext.copy(submissionId = submission.id)
                        
                        // Guardar el contexto en la base de datos y navegar al chat
                        navigateToChatWithFileContext(
                            updatedFileContext,
                            fileContext.fileType == "google_drive_error" // Es error si es de tipo google_drive_error
                        )
                        return@launch
                    } else {
                        Log.d("TaskSubmissionsFragment", "⚠️ Servidor MCP no disponible, intentando con FileAnalysisService")
                        Toast.makeText(context, "MCP no disponible, usando análisis alternativo...", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "❌ Error usando MCP Service: ${e.message}", e)
                    // Continuamos con FileAnalysisService como fallback
                }
                
                // SEGUNDA ESTRATEGIA (Fallback): Usar FileAnalysisService 
                // Solo si MCP no está disponible o falló
                Log.d("TaskSubmissionsFragment", "🔄 Intentando con FileAnalysisService como fallback")
                
                val analysisResult = withContext(Dispatchers.IO) {
                    fileAnalysisService.extractFileContent(Uri.parse(submission.fileUri), submission.fileName)
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
                        fileType = analysisResult.fileType.name,
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
                
                val fileType = fileAnalysisService.getFileType(submission.fileName)
                Log.d("TaskSubmissionsFragment", "📄 Tipo de archivo: $fileType")
                Log.d("TaskSubmissionsFragment", "📝 Contenido extraído (${analysisResult.content.length} caracteres): ${analysisResult.content.take(100)}...")
                
                // Obtener la descripción de la tarea desde Supabase (remote-first)
                val taskDescription = withContext(Dispatchers.IO) {
                    try {
                        SupabaseClient.fetchTaskById(submission.taskId)?.description ?: ""
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error fetching task description from Supabase", e)
                        ""
                    }
                }
                
                val fileContext = FileContext(
                    submissionId = submission.id,
                    fileName = submission.fileName,
                    fileType = fileType.name, // Convert enum to string
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
            
            // Send FileContext directly to Supabase (do not persist locally)
            try {
                val supabaseRepo = com.example.tareamov.data.repository.SupabaseRepository()
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val ok = supabaseRepo.upsert("file_contexts", fileContext)
                    if (ok) Log.i("TaskSubmissionsFragment", "FileContext upserted to Supabase.")
                    else Log.w("TaskSubmissionsFragment", "Failed to upsert FileContext to Supabase.")
                }
            } catch (e: Exception) {
                Log.w("TaskSubmissionsFragment", "Exception sending FileContext to Supabase: ${e.message}")
            }
            
            // El resto del código debe ejecutarse en el hilo principal para UI
            withContext(Dispatchers.Main) {
                // Ocultar progreso
                progressSection.visibility = View.GONE
                
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
            private val avatarImageView: CircleImageView = itemView.findViewById(R.id.avatarImageView)
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
                    val usernameResolved = withContext(Dispatchers.IO) {
                        try {
                            val usuarios = SupabaseClient.fetchUsuarios()
                            usuarios.firstOrNull { it.id == submission.studentId }?.usuario
                        } catch (e: Exception) {
                            Log.e("TaskSubmissionsFragment", "Error fetching username for id ${submission.studentId}", e)
                            null
                        }
                    }
                    val displayName = usernameResolved ?: "Usuario ${submission.studentId}"
                    studentNameTextView.text = displayName
                    // Load avatar using resolved username (or fallback)
                    loadUserAvatar(usernameResolved ?: "")
                }

                val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                submissionDateTextView.text = "Entregado: ${dateFormat.format(submission.submissionDate)}"
                deliveryDateTextView.text = dateFormat.format(submission.submissionDate)

                fileNameTextView.text = submission.fileName
                
                // Manejar calificación IA - solo mostrar si hay calificación real del usuario (mayor a 0)
                if (submission.grade != null && submission.grade > 0) {
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

                // Avatar is loaded as part of username resolution above

                viewFileButton.setOnClickListener {
                    openSubmissionFile(submission.fileUri)
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
                    feedbackEditText.setText(submission.feedback ?: "")

                    // Verificar si hay calificación pendiente y aplicarla automáticamente a los campos
                    val calificationManager = CalificationManager.getInstance(itemView.context)
                    if (calificationManager.hasPendingCalification()) {
                        val pendingCalification = calificationManager.getPendingCalification()
                        if (pendingCalification != null) {
                            // Aplicar la calificación pendiente a los campos
                            gradeEditText.setText(pendingCalification.grade)
                            feedbackEditText.setText(pendingCalification.feedback)
                            
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

                            val feedback = feedbackEditText.text.toString()
                            onGradeSubmitted(submission, grade, feedback)
                        } catch (e: NumberFormatException) {
                            Toast.makeText(context, "❌ No se pudo convertir la calificación '$gradeText' a número decimal", Toast.LENGTH_SHORT).show()
                            Log.e("TaskSubmissionsFragment", "❌ No se pudo convertir grade a Float: $gradeText", e)
                        }
                    }
                } else {
                    gradeSection.visibility = View.GONE

                    // For students, show their grade if available and greater than 0
                    if (submission.grade != null && submission.grade > 0) {
                        gradeDisplayTextView.visibility = View.VISIBLE
                        gradeDisplayTextView.text = "Calificación: ${submission.grade}/10"

                        // Show feedback if available
                        if (!submission.feedback.isNullOrBlank()) {
                            feedbackDisplayTextView.visibility = View.VISIBLE
                            feedbackDisplayTextView.text = "Comentarios: ${submission.feedback}"
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

            private fun loadUserAvatar(username: String) {
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val avatarUrl = withContext(Dispatchers.IO) {
                            try {
                                // Try find usuario -> persona -> avatar
                                val usuarios = SupabaseClient.fetchUsuarios()
                                val usuario = usuarios.firstOrNull { it.usuario?.equals(username, ignoreCase = true) == true }
                                val personaId = usuario?.persona_id
                                if (personaId != null) {
                                    val personas = SupabaseClient.fetchPersonas()
                                    val persona = personas.firstOrNull { it.id == personaId }
                                    persona?.avatar
                                } else null
                            } catch (e: Exception) {
                                Log.e("TaskSubmissionsFragment", "Error fetching avatar from Supabase", e)
                                null
                            }
                        }

                        if (!avatarUrl.isNullOrEmpty()) {
                            try {
                                Glide.with(requireContext())
                                    .load(Uri.parse(avatarUrl))
                                    .placeholder(R.drawable.default_avatar)
                                    .error(R.drawable.default_avatar)
                                    .into(avatarImageView)
                            } catch (e: Exception) {
                                Log.e("TaskSubmissionsFragment", "Error loading avatar image", e)
                                avatarImageView.setImageResource(R.drawable.default_avatar)
                            }
                        } else {
                            avatarImageView.setImageResource(R.drawable.default_avatar)
                        }
                    } catch (e: Exception) {
                        Log.e("TaskSubmissionsFragment", "Error loading avatar", e)
                        avatarImageView.setImageResource(R.drawable.default_avatar)
                    }
                }
            }

            private fun openSubmissionFile(uriString: String) {
                try {
                    val uri = Uri.parse(uriString)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("TaskSubmissionsFragment", "Error opening file", e)
                    Toast.makeText(context, "Error al abrir el archivo: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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
    
    /**
     * Copia un archivo al almacenamiento interno de la app para evitar problemas de permisos
     * Retorna el URI del archivo copiado
     */
    private fun copyFileToInternalStorage(sourceUri: Uri, fileName: String): Uri {
        try {
            val contentResolver = requireContext().contentResolver
            val inputStream = contentResolver.openInputStream(sourceUri)
                ?: throw IllegalArgumentException("No se pudo abrir el archivo de origen")
            
            // Crear directorio de tareas si no existe
            val tasksDir = File(requireContext().filesDir, "task_submissions")
            if (!tasksDir.exists()) {
                tasksDir.mkdirs()
            }
            
            // Sanitizar el nombre del archivo
            val sanitizedFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val timestamp = System.currentTimeMillis()
            val destinationFile = File(tasksDir, "${timestamp}_$sanitizedFileName")
            
            // Copiar el archivo
            inputStream.use { input ->
                destinationFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            Log.i("TaskSubmissionsFragment", "✅ Archivo copiado a almacenamiento interno: ${destinationFile.absolutePath}")
            return Uri.fromFile(destinationFile)
        } catch (e: Exception) {
            Log.e("TaskSubmissionsFragment", "❌ Error copiando archivo a almacenamiento interno", e)
            throw e
        }
    }
}