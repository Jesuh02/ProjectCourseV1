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
import com.example.tareamov.service.FileAnalysisService
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
import com.bumptech.glide.Glide
import de.hdodenhof.circleimageview.CircleImageView

class TaskSubmissionsFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SubmissionsAdapter
    private lateinit var sessionManager: SessionManager
    private lateinit var fileAnalysisService: FileAnalysisService
    private lateinit var mcpService: MCPService
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
                view?.findViewById<TextView>(R.id.selectedFileNameTextView)?.text = uri.lastPathSegment ?: "Archivo seleccionado"

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
        mcpService = MCPService(requireContext())
        val currentUsername = sessionManager.getUsername()
        isCourseCreator = (courseCreatorUsername != null && courseCreatorUsername == currentUsername)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_task_submissions, container, false)

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
        val emptyStateTextView = view.findViewById<TextView>(R.id.emptyStateTextView)

        // Configure visibility based on user role
        if (isCourseCreator) {
            // Course creator sees progress of all students
            progressSection.visibility = View.VISIBLE
            uploadSection.visibility = View.GONE
            emptyStateTextView.text = "No hay entregas para esta tarea"
            loadTaskProgress()
        } else {
            // Regular student sees their own progress
            progressSection.visibility = View.VISIBLE
            uploadSection.visibility = View.VISIBLE
            emptyStateTextView.text = "No has entregado esta tarea aún"
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

                val userSubmission = withContext(Dispatchers.IO) {
                    // Fetch submissions from Supabase and find the user's submission for this task
                    val all = try {
                        SupabaseClient.fetchTaskSubmissions()
                    } catch (e: Exception) {
                        emptyList<com.example.tareamov.data.entity.TaskSubmission>()
                    }
                    all.firstOrNull { it.taskId == taskId && it.studentUsername.equals(currentUsername, ignoreCase = true) }
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
                    Toast.makeText(
                        context, 
                        "⚠️ No se encontró tu entrega para aplicar la calificación", 
                        Toast.LENGTH_SHORT
                    ).show()
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
                        val creator = courseCreatorUsername ?: SupabaseClient.fetchCourseById(courseId)?.creatorUsername
                        subs.filter { it.creatorUsername.equals(creator, ignoreCase = true) }.map { it.subscriberUsername }
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
        val username = sessionManager.getUsername() ?: return

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val submission = withContext(Dispatchers.IO) {
                    try {
                        SupabaseClient.fetchTaskSubmissions().firstOrNull { it.taskId == taskId && it.studentUsername.equals(username, ignoreCase = true) }
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
                        if (isCourseCreator) all
                        else {
                            val username = sessionManager.getUsername()
                            if (username != null) all.filter { it.studentUsername.equals(username, ignoreCase = true) } else emptyList()
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
                        Log.i("TaskSubmissionsFragment", "Updated submission pushed to Supabase.")
                        Toast.makeText(context, "Calificación enviada al servidor", Toast.LENGTH_SHORT).show()
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

    private fun submitTaskFile() {
        val uri = selectedFileUri
        if (uri == null) {
            Toast.makeText(context, "Selecciona un archivo primero", Toast.LENGTH_SHORT).show()
            return
        }

        val username = sessionManager.getUsername()
        if (username == null) {
            Toast.makeText(context, "Debes iniciar sesión para enviar tareas", Toast.LENGTH_SHORT).show()
            return
        }

        val fileName = uri.lastPathSegment ?: "archivo_tarea"
        val submission = TaskSubmission(
            taskId = taskId,
            studentUsername = username,
            fileUri = uri.toString(),
            fileName = fileName,
            submissionDate = System.currentTimeMillis(),
            grade = 0.0f, // Nota por defecto 0 en lugar de null
            feedback = null
        )

        CoroutineScope(Dispatchers.Main).launch {
            try {
                try {
                    // Directly insert submission to Supabase
                    val remoteId = withContext(Dispatchers.IO) {
                        SupabaseClient.insertTaskSubmission(submission)
                    }
                    if (remoteId != null) {
                        Log.i("TaskSubmissionsFragment", "Supabase insertTaskSubmission returned remote id=$remoteId")
                        Toast.makeText(context, "Tarea subida a servidor (id=$remoteId)", Toast.LENGTH_SHORT).show()

                        // Poll Supabase for the newly created submission (the backend may be eventually consistent)
                        val created = withContext(Dispatchers.IO) {
                            var found: com.example.tareamov.data.entity.TaskSubmission? = null
                            repeat(6) { attempt ->
                                try {
                                    val all = SupabaseClient.fetchTaskSubmissions()
                                    found = all.firstOrNull { it.id == remoteId }
                                    if (found != null) return@withContext found
                                } catch (e: Exception) {
                                    Log.w("TaskSubmissionsFragment", "Attempt ${'$'}{attempt + 1} fetch created submission failed: ${'$'}{e.message}")
                                }
                                kotlinx.coroutines.delay(500)
                            }
                            found
                        }

                        if (created != null) {
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

                // Update progress after submission
                progressBar.max = 100
                progressBar.progress = 50 // 50% porque está entregado pero no calificado (nota = 0)
                progressTextView.text = "Tarea entregada, pendiente de calificación"

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
                Log.d("TaskSubmissionsFragment", "📄 Tipo de archivo: $fileType")
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
                
                studentNameTextView.text = submission.studentUsername

                val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
                submissionDateTextView.text = "Entregado: ${dateFormat.format(submission.submissionDate)}"
                deliveryDateTextView.text = dateFormat.format(submission.submissionDate)

                fileNameTextView.text = submission.fileName
                
                // Manejar calificación IA - solo mostrar si hay calificación real del usuario (mayor a 0)
                if (submission.grade != null && submission.grade > 0) {
                    val aiGrade = (submission.grade * 10).toInt() // Convertir de 0-10 a 0-100
                    
                    // Mostrar elementos de calificación
                    aiGradeTextView.visibility = View.VISIBLE
                    gradeScaleTextView.visibility = View.VISIBLE
                    gradeProgressBar.visibility = View.VISIBLE
                    noGradeTextView.visibility = View.GONE
                    
                    aiGradeTextView.text = aiGrade.toString()
                    gradeProgressBar.progress = aiGrade
                    
                    // Determinar calidad basada en la calificación
                    val qualityLabel = when {
                        aiGrade >= 90 -> "⭐ Excelente"
                        aiGrade >= 80 -> "⭐ Muy Bueno"
                        aiGrade >= 70 -> "⭐ Bueno"
                        aiGrade >= 60 -> "⭐ Regular"
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

                // Load user avatar
                loadUserAvatar(submission.studentUsername)

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
}