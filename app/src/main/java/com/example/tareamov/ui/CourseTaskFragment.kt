package com.example.tareamov.ui

import android.app.Activity
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.VideoManager
import com.example.tareamov.viewmodel.CourseCreationViewModel
import com.example.tareamov.service.StorageHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import android.widget.ImageView

class CourseTaskFragment : Fragment() {

    private var topicId: Long = -1L
    private var taskId: Long = -1L // -1 indicates a new task
    private var existingTask: Task? = null
    private var isTemporary: Boolean = false // Add this variable
    private var isSaving: Boolean = false // Guard against double-tap



    private lateinit var taskNameEditText: EditText
    private lateinit var taskDescriptionEditText: EditText
    private lateinit var dueDateValueTextView: TextView
    private lateinit var dueTimeValueTextView: TextView
    private lateinit var contentContainer: LinearLayout
    private lateinit var videoManager: VideoManager
    private var dueDateCalendar: Calendar? = null

    // Use the shared ViewModel
    private val viewModel: CourseCreationViewModel by activityViewModels()

    private lateinit var videoPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var documentPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    private var cameraImageUri: Uri? = null

    private lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            topicId = it.getLong("topicId", -1L)
            taskId = it.getLong("taskId", -1L)
            isTemporary = it.getBoolean("isTemporary", false)
            Log.d("CourseTaskFragment", "Received topicId: $topicId, taskId: $taskId, isTemporary: $isTemporary")
        }
        videoManager = VideoManager(requireContext())

        sessionManager = SessionManager.getInstance(requireContext())

        videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    Log.d("CourseTaskFragment", "Video selected: $uri")
                    handleSelectedVideoUri(uri)
                }
            }
        }

        documentPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    // Subir documento a Cloudflare R2
                    handleSelectedDocumentUri(uri)
                }
            }
        }

        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    handleSelectedImageUri(uri)
                }
            }
        }

        cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = cameraImageUri
                if (uri != null) {
                    handleSelectedImageUri(uri)
                }
            }
        }
    }

    // Método para manejar documentos y subirlos a R2
    private fun handleSelectedDocumentUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var finalUri: Uri = uri
                var r2Url: String? = null

                // Subir al backend si está configurado
                if (StorageHelper.isConfigured()) {
                    Log.d("CourseTaskFragment", "☁️ Uploading document to backend: $uri")
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Subiendo documento a la nube...", Toast.LENGTH_SHORT).show()
                    }
                    
                    val result = withContext(Dispatchers.IO) {
                        StorageHelper.uploadDocument(
                            context = requireContext(),
                            documentUri = uri,
                            onProgress = { progress ->
                                Log.d("CourseTaskFragment", "Document upload progress: $progress%")
                            }
                        )
                    }
                    
                    when (result) {
                        is StorageHelper.UploadResult.Success -> {
                            r2Url = result.url
                            finalUri = Uri.parse(r2Url)
                            Log.d("CourseTaskFragment", "✅ Document Upload successful: $r2Url")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Documento subido a la nube ✓", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is StorageHelper.UploadResult.Error -> {
                            Log.e("CourseTaskFragment", "❌ Document Upload failed: ${result.message}")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Error subiendo documento a nube, usando copia local", Toast.LENGTH_SHORT).show()
                            }
                            // Fallback: guardar localmente
                            val localUri = withContext(Dispatchers.IO) {
                                copyUriToLocalStorage(uri, "document")
                            }
                            finalUri = localUri ?: uri
                        }
                    }
                } else {
                    Log.w("CourseTaskFragment", "⚠️ R2 not configured for documents, saving locally")
                    // Fallback: guardar localmente
                    val localUri = withContext(Dispatchers.IO) {
                        copyUriToLocalStorage(uri, "document")
                    }
                    finalUri = localUri ?: uri
                }

                Log.d("CourseTaskFragment", "Using URI for document: $finalUri, r2Url: $r2Url")
                addContentItemView(finalUri, "document", r2Url = r2Url)
            } catch (e: Exception) {
                Log.e("CourseTaskFragment", "Error processing document", e)
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Error al procesar el documento", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleSelectedImageUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var finalUri: Uri = uri
                var r2Url: String? = null

                if (StorageHelper.isConfigured()) {
                    Log.d("CourseTaskFragment", "☁️ Uploading image to backend: $uri")
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Subiendo imagen a la nube...", Toast.LENGTH_SHORT).show()
                    }
                    val result = withContext(Dispatchers.IO) {
                        StorageHelper.uploadFile(
                            context = requireContext(),
                            fileUri = uri,
                            folder = "tasks/images",
                            onProgress = { progress ->
                                Log.d("CourseTaskFragment", "Image upload progress: $progress%")
                            }
                        )
                    }
                    when (result) {
                        is StorageHelper.UploadResult.Success -> {
                            r2Url = result.url
                            finalUri = Uri.parse(r2Url)
                            Log.d("CourseTaskFragment", "✅ Image upload successful: $r2Url")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Imagen subida a la nube ✓", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is StorageHelper.UploadResult.Error -> {
                            Log.e("CourseTaskFragment", "❌ Image upload failed: ${result.message}")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Error subiendo imagen, usando copia local", Toast.LENGTH_SHORT).show()
                            }
                            val localUri = withContext(Dispatchers.IO) { copyUriToLocalStorage(uri, "image") }
                            finalUri = localUri ?: uri
                        }
                    }
                } else {
                    val localUri = withContext(Dispatchers.IO) { copyUriToLocalStorage(uri, "image") }
                    finalUri = localUri ?: uri
                }

                addContentItemView(finalUri, "image", r2Url = r2Url)
            } catch (e: Exception) {
                Log.e("CourseTaskFragment", "Error processing image", e)
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Error al procesar la imagen", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_course_task, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        taskNameEditText = view.findViewById(R.id.taskNameEditText)
        taskDescriptionEditText = view.findViewById(R.id.taskDescriptionEditText)
        dueDateValueTextView = view.findViewById(R.id.dueDateValueTextView)
        dueTimeValueTextView = view.findViewById(R.id.dueTimeValueTextView)
        contentContainer = view.findViewById(R.id.contentContainer)
        val taskTitleTextView = view.findViewById<TextView>(R.id.taskTitleTextView)
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        val saveTaskButton = view.findViewById<Button>(R.id.saveTaskButton)
        val addVideoButton = view.findViewById<LinearLayout>(R.id.addVideoButton)
        val addDocumentButton = view.findViewById<LinearLayout>(R.id.addDocumentButton)
        val addImageButton = view.findViewById<LinearLayout>(R.id.addImageButton)
        val takePhotoButton = view.findViewById<LinearLayout>(R.id.takePhotoButton)
        val pickDueDateButton = view.findViewById<ImageButton>(R.id.pickDueDateButton)
        val pickDueTimeButton = view.findViewById<ImageButton>(R.id.pickDueTimeButton)

        backButton.setOnClickListener { findNavController().navigateUp() }
        saveTaskButton.setOnClickListener { saveTask() }

        addVideoButton.setOnClickListener { openGalleryForVideo() }
        addDocumentButton.setOnClickListener { openDocumentPicker() }
        addImageButton.setOnClickListener { openGalleryForImage() }
        takePhotoButton.setOnClickListener { openCameraForPhoto() }
        pickDueDateButton.setOnClickListener { openDueDatePicker() }
        pickDueTimeButton.setOnClickListener { openDueTimePicker() }
        updateDueDateUI()
        // addVideoButton.setOnClickListener { openGalleryForVideo() }
        // addDocumentButton.setOnClickListener { openDocumentPicker() }

        if (taskId != -1L) {
            taskTitleTextView.text = "Editar Tarea"
            loadTaskDetails()
        } else {
            taskTitleTextView.text = "Crear Nueva Tarea"
            if (topicId == -1L) {
                Log.e("CourseTaskFragment", "Error: topicId is required to create a new task.")
                Toast.makeText(context, "Error: Falta ID del tema", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
        }

        // Modify back button to save to ViewModel if temporary
        backButton.setOnClickListener {
            if (isTemporary) {
                saveToViewModel()
            }
            findNavController().navigateUp()
        }

        // Modify save button to save to ViewModel if temporary
        saveTaskButton.setOnClickListener {
            if (isTemporary) {
                saveToViewModel()
                findNavController().navigateUp()
            } else {
                saveTask()
            }
        }
    }

    // Add these methods for ViewModel interaction
    private fun saveToViewModel() {
        val taskName = taskNameEditText.text.toString().trim()
        val taskDescription = taskDescriptionEditText.text.toString().trim()

        if (taskName.isBlank()) {
            Toast.makeText(context, "El nombre de la tarea no puede estar vacío", Toast.LENGTH_SHORT).show()
            return
        }

        val currentTask = viewModel.getCurrentTask() ?: viewModel.createNewTask()
        currentTask.name = taskName
        currentTask.description = taskDescription
        currentTask.dueDate = buildDueDateIsoUtc()
        currentTask.timeLimitMinutes = null

        // Save content items
        currentTask.contentItems.clear()
        for (i in 0 until contentContainer.childCount) {
            val itemView = contentContainer.getChildAt(i)
            val contentUri = itemView.tag as? Uri
            val contentType = itemView.getTag(R.id.content_type_tag) as? String
            if (contentUri != null && contentType != null) {
                val contentItem = CourseCreationViewModel.TemporaryContentItem()
                contentItem.uriString = contentUri.toString()
                contentItem.contentType = contentType
                contentItem.name = (itemView.getTag(R.id.content_name_tag) as? String)
                    ?: getFileName(contentUri)
                    ?: "Contenido sin título"
                currentTask.contentItems.add(contentItem)
            }
        }
    }

    private fun loadFromViewModel() {
        val currentTask = viewModel.getCurrentTask()
        if (currentTask != null) {
            taskNameEditText.setText(currentTask.name)
            taskDescriptionEditText.setText(currentTask.description)
            setDueDateFromApi(currentTask.dueDate)

            // Load content items
            contentContainer.removeAllViews()
            for (contentItem in currentTask.contentItems) {
                val uri = Uri.parse(contentItem.uriString)
                addContentItemView(uri, contentItem.contentType)
            }
        }
    }

    private fun loadTaskDetails() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Fetch task from API
                val taskResult = withContext(Dispatchers.IO) { BackendApiService.getTaskById(taskId) }
                if (taskResult is ApiResult.Success) {
                    existingTask = taskResult.data
                } else {
                    Log.e("CourseTaskFragment", "Error: Task with ID $taskId not found. ${(taskResult as? ApiResult.Error)?.message}")
                    Toast.makeText(context, "Error al cargar la tarea", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                    return@launch
                }

                // Populate UI with existing task data
                val task = existingTask!!
                taskNameEditText.setText(task.name)
                taskDescriptionEditText.setText(task.description ?: "")
                setDueDateFromApi(task.dueDate)
                topicId = task.topicId // Ensure topicId is set from the loaded task

                // Load content items for the task from API
                Log.d("CourseTaskFragment", "📚 Loading content items for taskId=$taskId")
                var contentItems: List<ContentItem> = emptyList()

                val contentResult = withContext(Dispatchers.IO) { BackendApiService.getContentItemsByTask(taskId) }
                if (contentResult is ApiResult.Success) {
                    contentItems = contentResult.data
                    Log.d("CourseTaskFragment", "📦 Loaded ${contentItems.size} content items from API for task")
                } else {
                    Log.w("CourseTaskFragment", "Failed to load content items: ${(contentResult as? ApiResult.Error)?.message}")
                }

                // Add content items to the UI
                for (contentItem in contentItems) {
                    val r2Url = if (StorageHelper.isR2Url(contentItem.uriString)) contentItem.uriString else null
                    addContentItemView(
                        Uri.parse(contentItem.uriString),
                        contentItem.contentType,
                        contentItem.name,
                        contentItem.id,
                        r2Url
                    )
                }

                // Load topic content items for the read-only reference section
                if (task.topicId > 0) {
                    val topicContentResult = withContext(Dispatchers.IO) {
                        BackendApiService.getContentItemsByTopic(task.topicId)
                    }
                    if (topicContentResult is ApiResult.Success && topicContentResult.data.isNotEmpty()) {
                        showTopicContentSection(topicContentResult.data)
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseTaskFragment", "Error loading task details", e)
                Toast.makeText(context, "Error al cargar la tarea", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
        }
    }
    
    // Show topic content items in a separate section
    private fun showTopicContentSection(topicContentItems: List<ContentItem>) {
        val topicContentSection = view?.findViewById<LinearLayout>(R.id.topicContentSection)
        if (topicContentSection == null) {
            Log.w("CourseTaskFragment", "topicContentSection not found in layout")
            return
        }
        
        topicContentSection.visibility = View.VISIBLE
        val topicContentContainer = view?.findViewById<LinearLayout>(R.id.topicContentContainer)
        topicContentContainer?.removeAllViews()
        
        Log.d("CourseTaskFragment", "📄 Displaying ${topicContentItems.size} topic content items")
        for (item in topicContentItems.sortedBy { it.orderIndex }) {
            addTopicContentItemView(item, topicContentContainer)
        }
    }
    
    // Add a read-only view for topic content items
    private fun addTopicContentItemView(item: ContentItem, container: LinearLayout?) {
        if (container == null) return
        
        val inflater = LayoutInflater.from(context)
        val contentView = inflater.inflate(R.layout.item_content_mini, container, false)

        val iconView = contentView.findViewById<ImageView>(R.id.contentIconView)
        val nameView = contentView.findViewById<TextView>(R.id.contentNameView)
        val typeView = contentView.findViewById<TextView>(R.id.contentTypeView)

        // Show cloud icon if it's an R2 URL
        val displayName = if (StorageHelper.isR2Url(item.uriString)) {
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

        // Make the whole item clickable to open content
        contentView.setOnClickListener {
            openContentItem(item)
        }

        container.addView(contentView)
        Log.d("CourseTaskFragment", "📄 Added topic content view: ${item.name}")
    }
    
    // Open a content item (video or document)
    private fun openContentItem(item: ContentItem) {
        try {
            Log.d("CourseTaskFragment", "🎬 Opening content: ${item.name}, Type: ${item.contentType}, URI: ${item.uriString}")
            
            if (item.contentType.lowercase() == "video") {
                // Open video in VideoPlayerActivity
                val intent = Intent(requireContext(), VideoPlayerActivity::class.java)
                intent.putExtra("video_path", item.uriString)
                intent.putExtra("video_title", item.name ?: "Video")
                intent.putExtra("video_description", "")
                intent.putExtra("username", sessionManager.getUsername() ?: "")
                
                if (!item.uriString.startsWith("http://") && !item.uriString.startsWith("https://")) {
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                startActivity(intent)
            } else {
                // For documents and other content types
                val uri = Uri.parse(item.uriString)
                val intent = if (StorageHelper.isR2Url(item.uriString) || 
                             item.uriString.startsWith("http://") || 
                             item.uriString.startsWith("https://")) {
                    Intent(Intent.ACTION_VIEW, uri)
                } else {
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, inferMimeType(item.contentType, item.fileName ?: item.name, item.uriString))
                    }
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("CourseTaskFragment", "Error opening content: ${e.message}", e)
            Toast.makeText(context, "No se puede abrir el contenido: ${item.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDueDatePicker() {
        val calendar = (dueDateCalendar ?: Calendar.getInstance()).clone() as Calendar
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val updated = (dueDateCalendar ?: Calendar.getInstance()).clone() as Calendar
                updated.set(Calendar.YEAR, year)
                updated.set(Calendar.MONTH, month)
                updated.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                dueDateCalendar = updated
                updateDueDateUI()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun openDueTimePicker() {
        val calendar = (dueDateCalendar ?: Calendar.getInstance()).clone() as Calendar
        TimePickerDialog(
            requireContext(),
            { _, hourOfDay, minute ->
                val updated = (dueDateCalendar ?: Calendar.getInstance()).clone() as Calendar
                updated.set(Calendar.HOUR_OF_DAY, hourOfDay)
                updated.set(Calendar.MINUTE, minute)
                updated.set(Calendar.SECOND, 0)
                updated.set(Calendar.MILLISECOND, 0)
                dueDateCalendar = updated
                updateDueDateUI()
            },
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
            true
        ).show()
    }

    private fun updateDueDateUI() {
        val calendar = dueDateCalendar
        if (calendar == null) {
            dueDateValueTextView.text = "Sin fecha"
            dueTimeValueTextView.text = "00:00"
            return
        }

        val dateFmt = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        dueDateValueTextView.text = dateFmt.format(calendar.time)
        dueTimeValueTextView.text = timeFmt.format(calendar.time)
    }

    private fun setDueDateFromApi(rawDueDate: String?) {
        if (rawDueDate.isNullOrBlank()) {
            dueDateCalendar = null
            updateDueDateUI()
            return
        }

        val formats = listOf(
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        )

        var parsedDate: java.util.Date? = null
        for (fmt in formats) {
            try {
                parsedDate = fmt.parse(rawDueDate)
                if (parsedDate != null) break
            } catch (_: Exception) {
                // Try next format.
            }
        }

        if (parsedDate == null) {
            Log.w("CourseTaskFragment", "Could not parse due date: $rawDueDate")
            dueDateCalendar = null
            updateDueDateUI()
            return
        }

        dueDateCalendar = Calendar.getInstance().apply { time = parsedDate }
        updateDueDateUI()
    }

    private fun buildDueDateIsoUtc(): String? {
        val calendar = dueDateCalendar ?: return null
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(calendar.time)
    }

    // In the saveTask method, update the navigation after saving
    private fun saveTask() {
        if (isSaving) {
            Log.w("CourseTaskFragment", "saveTask already in progress, ignoring duplicate tap")
            return
        }

        val taskName = taskNameEditText.text.toString().trim()
        val taskDescription = taskDescriptionEditText.text.toString().trim()
        val dueDateIso = buildDueDateIsoUtc()
        var courseId = arguments?.getLong("courseId", -1L) ?: -1L
        val courseName = arguments?.getString("courseName") ?: "Curso sin nombre"
        val topicNumber = arguments?.getInt("topicNumber", 0) ?: 0

        if (taskName.isBlank()) {
            showToastSafe("El nombre de la tarea no puede estar vacío")
            return
        }

        isSaving = true
        // Disable save button to give visual feedback
        view?.findViewById<Button>(R.id.saveTaskButton)?.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // If courseId is not provided but we have a topicId, fetch the course from the topic
                if (courseId <= 0 && topicId > 0) {
                    Log.d("CourseTaskFragment", "courseId not provided, fetching from topic $topicId")
                    val topicResult = withContext(Dispatchers.IO) { BackendApiService.getTopicById(topicId) }
                    if (topicResult is ApiResult.Success) {
                        courseId = topicResult.data.courseId
                        Log.d("CourseTaskFragment", "Fetched courseId=$courseId from topic $topicId")
                    } else {
                        showToastSafe("Error: No se pudo obtener información del curso")
                        Log.e("CourseTaskFragment", "Could not fetch topic $topicId to get courseId")
                        return@launch
                    }
                }

                // Resolve authoritative course id from API
                val resolvedCourseId = withContext(Dispatchers.IO) {
                    try {
                        if (courseId > 0) {
                            val courseResult = BackendApiService.getCourseById(courseId)
                            if (courseResult is ApiResult.Success) return@withContext courseResult.data.id
                        }
                        if (courseName.isNotBlank()) {
                            val searchResult = BackendApiService.searchCourses(courseName)
                            if (searchResult is ApiResult.Success) {
                                val byName = searchResult.data.firstOrNull { it.title?.trim()?.equals(courseName.trim(), ignoreCase = true) == true }
                                if (byName != null) return@withContext byName.id
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("CourseTaskFragment", "Error resolving remote course id", e)
                    }
                    // fallback to passed courseId or -1 handled below
                    courseId
                }

                // Validate that topicId exists before attempting to save task
                if (topicId <= 0) {
                    showToastSafe("Error: ID de tema inválido")
                    Log.e("CourseTaskFragment", "Invalid topicId: $topicId")
                    return@launch
                }

                // Verify topic exists via API
                val topicExists = withContext(Dispatchers.IO) {
                    try {
                        val topicResult = BackendApiService.getTopicById(topicId)
                        if (topicResult is ApiResult.Success) {
                            Log.d("CourseTaskFragment", "Topic $topicId found via API")
                            return@withContext true
                        }
                        Log.w("CourseTaskFragment", "Topic $topicId not found via API")
                        false
                    } catch (e: Exception) {
                        Log.e("CourseTaskFragment", "Error verifying topic exists", e)
                        false
                    }
                }

                if (!topicExists) {
                    showToastSafe("Error: El tema no existe")
                    Log.e("CourseTaskFragment", "Topic $topicId not found")
                    return@launch
                }

                var savedTopicId = topicId
                var savedTaskId: Long = taskId

                // Build the Task DTO to send to Supabase. For new tasks, id may be 0 or -1.
                val remoteTask = Task(
                    id = if (taskId > 0) taskId else 0,
                    topicId = topicId,
                    name = taskName,
                    description = taskDescription.ifBlank { null },
                    orderIndex = 0,
                    dueDate = dueDateIso,
                    timeLimitMinutes = null
                )

                Log.d("CourseTaskFragment", "Attempting to save task: id=${remoteTask.id}, name=${remoteTask.name}, topicId=${remoteTask.topicId}, isUpdate=${taskId > 0}")

                // Push to backend (insert or update)
                val pushedTaskId = withContext(Dispatchers.IO) {
                    try {
                        if (taskId > 0) {
                            Log.d("CourseTaskFragment", "Updating existing task $taskId")
                            val updateResult = BackendApiService.updateTask(taskId, mapOf(
                                "title" to remoteTask.name,
                                "description" to remoteTask.description,
                                "dueDate" to remoteTask.dueDate,
                                "timeLimitMinutes" to null
                            ))
                            if (updateResult is ApiResult.Success) {
                                Log.d("CourseTaskFragment", "Task updated successfully")
                                updateResult.data?.id ?: remoteTask.id
                            } else {
                                Log.e("CourseTaskFragment", "Task update failed: ${(updateResult as? ApiResult.Error)?.message}")
                                null
                            }
                        } else {
                            Log.d("CourseTaskFragment", "Inserting new task: topicId=${remoteTask.topicId}, title='${remoteTask.name}'")
                            val createResult = BackendApiService.createTask(com.example.tareamov.data.entity.Task(
                                topicId = remoteTask.topicId,
                                name = remoteTask.name,
                                description = remoteTask.description,
                                orderIndex = remoteTask.orderIndex,
                                dueDate = remoteTask.dueDate,
                                timeLimitMinutes = null
                            ))
                            if (createResult is ApiResult.Success && createResult.data != null) {
                                Log.d("CourseTaskFragment", "Task inserted with id=${createResult.data.id}")
                                createResult.data.id
                            } else {
                                Log.e("CourseTaskFragment", "Task insert failed: ${(createResult as? ApiResult.Error)?.message}")
                                null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CourseTaskFragment", "Remote task push exception", e)
                        null
                    }
                }

                if (pushedTaskId != null) {
                    savedTaskId = pushedTaskId
                    savedTopicId = topicId
                    Log.i("CourseTaskFragment", "Task saved remotely with id=$savedTaskId topic=$savedTopicId")
                } else {
                    // Remote push failed - provide more specific error message
                    val errorMsg = "No se pudo guardar la tarea en el servidor. Verifica tu conexión y los logs."
                    showToastSafe(errorMsg, Toast.LENGTH_LONG)
                    Log.e("CourseTaskFragment", "Failed to save task to Supabase - topicId=$topicId, taskName=$taskName, taskId=$taskId")
                    return@launch
                }

                // Save content items for all tasks (new and existing)
                val contentItemsToSave = mutableListOf<ContentItem>()
                val currentUsername = sessionManager.getUsername()
                val currentUserId = withContext(Dispatchers.IO) {
                    currentUsername?.let { username ->
                        val userResult = BackendApiService.getUserByUsername(username)
                        if (userResult is ApiResult.Success) userResult.data?.id else null
                    }
                }
                
                // IMPORTANT: Delete existing content items BEFORE inserting new ones
                // This prevents duplicates when updating a task
                if (taskId > 0 && savedTaskId > 0) {
                    Log.d("CourseTaskFragment", "Deleting existing content items for taskId=$savedTaskId before saving new ones")
                    val deleteOldContentOk = withContext(Dispatchers.IO) {
                        try {
                            val delResult = BackendApiService.deleteContentItemsByTask(savedTaskId)
                            if (delResult is ApiResult.Success) {
                                Log.d("CourseTaskFragment", "Successfully deleted old content items for taskId=$savedTaskId")
                                true
                            } else {
                                Log.w("CourseTaskFragment", "Failed to delete old content items for taskId=$savedTaskId: ${(delResult as? ApiResult.Error)?.message}")
                                false
                            }
                        } catch (e: Exception) {
                            Log.e("CourseTaskFragment", "Error deleting old content items", e)
                            false
                        }
                    }

                    if (!deleteOldContentOk) {
                        showToastSafe("No se pudieron eliminar los contenidos anteriores de la tarea", Toast.LENGTH_LONG)
                        return@launch
                    }
                }
                
                for (i in 0 until contentContainer.childCount) {
                    val itemView = contentContainer.getChildAt(i)
                    val contentUri = itemView.tag as? Uri
                    val contentType = itemView.getTag(R.id.content_type_tag) as? String
                    if (contentUri != null && contentType != null) {
                        val contentName = (itemView.getTag(R.id.content_name_tag) as? String)
                            ?: getFileName(contentUri)
                            ?: "Contenido sin título"
                        contentItemsToSave.add(
                            ContentItem(
                                id = 0,
                                topicId = topicId, // Necesario para que el backend pueda resolver permisos vía tema
                                taskId = savedTaskId,
                                name = contentName,
                                contentType = contentType,
                                uriString = contentUri.toString(),
                                fileUri = contentUri.toString(),
                                fileName = contentName,
                                orderIndex = i,
                                creator_usuario_id = currentUserId,
                                creator_username = currentUsername
                            )
                        )
                    }
                }

                var successCount = 0
                if (contentItemsToSave.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        contentItemsToSave.forEach { item ->
                            try {
                                // Save to Supabase only - no local insertion needed
                                val ciResult = BackendApiService.createContentItem(ContentItem(
                                    topicId = item.topicId,
                                    taskId = item.taskId,
                                    name = item.name,
                                    contentType = item.contentType,
                                    uriString = item.uriString,
                                    fileUri = item.fileUri,
                                    fileName = item.fileName,
                                    orderIndex = item.orderIndex,
                                    creator_usuario_id = item.creator_usuario_id,
                                    creator_username = item.creator_username
                                ))
                                if (ciResult is ApiResult.Success) {
                                    successCount++
                                    Log.d("CourseTaskFragment", "Saved content item: ${item.name} with id=${ciResult.data?.id}")
                                } else {
                                    if (ciResult is ApiResult.Error) {
                                        Log.e("CourseTaskFragment", "Failed to save content item '${item.name}': ${ciResult.message} (code=${ciResult.code})")
                                    }
                                    Log.w("CourseTaskFragment", "Failed to save content item: ${item.name}")
                                }
                            } catch (e: Exception) {
                                Log.e("CourseTaskFragment", "Error saving content item to Supabase: ${item.name}", e)
                            }
                        }
                    }
                    Log.d("CourseTaskFragment", "Saved $successCount/${contentItemsToSave.size} content items to Supabase for task ID: $savedTaskId")
                }
                
                // Show appropriate message
                if (contentItemsToSave.isNotEmpty() && successCount < contentItemsToSave.size) {
                    showToastSafe("Tarea guardada, pero algunos contenidos no se pudieron guardar", Toast.LENGTH_LONG)
                } else {
                    showToastSafe("Tarea guardada exitosamente")
                }

                // NUEVO: Create default submissions via backend for new tasks
                if (taskId <= 0 && courseId > 0) { // Solo para tareas nuevas
                    Log.d("CourseTaskFragment", "Creating default submissions for new task $savedTaskId in course $courseId")
                    // Backend handles default submission creation when a task is created
                    // Notify enrolled students about the new task
                    val currentUsername = sessionManager.getUsername() ?: "Instructor"
                    val currentUserId = sessionManager.getUserId()
                    
                    Log.d("CourseTaskFragment", "📢 Notifying enrolled students about new task '$taskName' in course '$courseName'")
                    withContext(Dispatchers.IO) {
                        try {
                            // Get enrolled students for the course
                            val progressResult = BackendApiService.getAllProgressByCourse(courseId)
                            if (progressResult is ApiResult.Success) {
                                val enrolledUserIds = progressResult.data?.map { it.usuarioEstudiante }?.filter { it != currentUserId } ?: emptyList()
                                for (studentId in enrolledUserIds) {
                                    BackendApiService.sendNotification(
                                        userId = studentId,
                                        title = "Nueva tarea: $taskName",
                                        message = "Se ha añadido una nueva tarea en el curso '${courseName ?: "Curso"}'",
                                        type = "new_task"
                                    )
                                }
                                Log.i("CourseTaskFragment", "Notified ${enrolledUserIds.size} students")
                            }
                            Unit
                        } catch (e: Exception) {
                            Log.w("CourseTaskFragment", "Error notifying students", e)
                        }
                    }
                }
                
                // Recalculate progress via backend
                if (courseId > 0) {
                    Log.d("CourseTaskFragment", "🔄 Recalculating student progress for course $courseId")
                    val progressResult = withContext(Dispatchers.IO) {
                        BackendApiService.getAllProgressByCourse(courseId)
                    }
                    val updatedStudents = if (progressResult is ApiResult.Success) progressResult.data?.size ?: 0 else 0
                    Log.i("CourseTaskFragment", "✅ Progress entries for $updatedStudents students")
                }

                // Notify CourseDetailFragment to refresh and switch to tasks tab
                com.example.tareamov.util.AppCache.invalidateCourseContent(courseId)
                try {
                    val detailEntry = findNavController().getBackStackEntry(R.id.courseDetailFragment)
                    detailEntry.savedStateHandle["switch_to_tasks_tab"] = true
                    detailEntry.savedStateHandle["force_reload_topics"] = true
                } catch (e: Exception) {
                    findNavController().previousBackStackEntry?.savedStateHandle?.set("force_reload_topics", true)
                    findNavController().previousBackStackEntry?.savedStateHandle?.set("switch_to_tasks_tab", true)
                }

                findNavController().popBackStack(R.id.courseDetailFragment, false)
            } catch (e: CancellationException) {
                Log.w("CourseTaskFragment", "saveTask cancelled", e)
            } catch (e: Exception) {
                Log.e("CourseTaskFragment", "Error saving task", e)
                showToastSafe("Error al guardar la tarea")
            } finally {
                isSaving = false
                view?.findViewById<Button>(R.id.saveTaskButton)?.isEnabled = true
            }
        }
    }

    private fun showToastSafe(message: String, duration: Int = Toast.LENGTH_SHORT) {
        val safeContext = context
        if (!isAdded || safeContext == null) {
            Log.w("CourseTaskFragment", "Skipping toast because fragment is not attached: $message")
            return
        }
        Toast.makeText(safeContext, message, duration).show()
    }

    private fun openGalleryForVideo() {
        try {
            // Create an intent that can handle multiple types of video sources
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "video/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }

            videoPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("CourseTaskFragment", "Error opening video picker", e)
            Toast.makeText(context, "Error al abrir el selector de videos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDocumentPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                // Allow all document types including Office files
                type = "*/*"
                // Add common MIME types as an array to better support Office documents
                val mimeTypes = arrayOf(
                    "application/msword", // .doc
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", // .docx
                    "application/vnd.ms-excel", // .xls
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
                    "application/vnd.ms-powerpoint", // .ppt
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation", // .pptx
                    "application/pdf", // .pdf
                    "text/plain" // .txt
                )
                putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            documentPickerLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e("CourseTaskFragment", "Error opening document picker", e)
            Toast.makeText(context, "Error al abrir el selector de documentos", Toast.LENGTH_SHORT).show()
        }
    }

    // Improved method to handle video URIs
    private fun handleSelectedVideoUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var finalUri: Uri = uri
                var r2Url: String? = null

                // Subir al backend si está configurado
                if (StorageHelper.isConfigured()) {
                    Log.d("CourseTaskFragment", "☁️ Uploading video to backend: $uri")
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Subiendo video a la nube...", Toast.LENGTH_SHORT).show()
                    }
                    
                    val result = withContext(Dispatchers.IO) {
                        StorageHelper.uploadVideo(
                            context = requireContext(),
                            videoUri = uri,
                            onProgress = { progress ->
                                Log.d("CourseTaskFragment", "Video upload progress: $progress%")
                            }
                        )
                    }
                    
                    when (result) {
                        is StorageHelper.UploadResult.Success -> {
                            r2Url = result.url
                            finalUri = Uri.parse(r2Url)
                            Log.d("CourseTaskFragment", "✅ Video Upload successful: $r2Url")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Video subido a la nube ✓", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is StorageHelper.UploadResult.Error -> {
                            Log.e("CourseTaskFragment", "❌ Video Upload failed: ${result.message}")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Error subiendo video a nube, usando copia local", Toast.LENGTH_SHORT).show()
                            }
                            // Fallback: guardar localmente
                            val localUri = withContext(Dispatchers.IO) {
                                copyUriToLocalStorage(uri, "video")
                            }
                            finalUri = localUri ?: uri
                        }
                    }
                } else {
                    Log.w("CourseTaskFragment", "⚠️ R2 not configured, saving locally")
                    // Fallback: guardar localmente
                    val localUri = withContext(Dispatchers.IO) {
                        copyUriToLocalStorage(uri, "video")
                    }
                    finalUri = localUri ?: uri
                }

                Log.d("CourseTaskFragment", "Using URI for video: $finalUri, r2Url: $r2Url")

                // Add the content item view with the best URI
                addContentItemView(finalUri, "video", r2Url = r2Url)
            } catch (e: Exception) {
                Log.e("CourseTaskFragment", "Error processing video", e)
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Error al procesar el video", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Improved method to copy files to local storage
    private fun copyUriToLocalStorage(uri: Uri, type: String): Uri? {
        return try {
            val contentResolver = requireContext().contentResolver
            val inputStream = contentResolver.openInputStream(uri) ?: return null

            // Get a more meaningful filename
            val fileName = getFileName(uri) ?: "${System.currentTimeMillis()}_${UUID.randomUUID()}"
            val fileExtension = getFileExtension(uri)

            // Create a dedicated directory for each content type
            val contentDir = File(requireContext().filesDir, type)
            if (!contentDir.exists()) {
                contentDir.mkdirs()
            }

            val outputFile = File(contentDir, "content_${System.currentTimeMillis()}_$fileName$fileExtension")
            val outputStream = FileOutputStream(outputFile)
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            Log.d("CourseTaskFragment", "File copied to: ${outputFile.absolutePath}")

            // Return the URI for the local file
            Uri.fromFile(outputFile)
        } catch (e: Exception) {
            Log.e("CourseTaskFragment", "Error copying file to local storage", e)
            null
        }
    }

    private fun openGalleryForImage() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            if (intent.resolveActivity(requireActivity().packageManager) != null) {
                imagePickerLauncher.launch(intent)
            } else {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT)
                fallbackIntent.type = "image/*"
                fallbackIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                imagePickerLauncher.launch(fallbackIntent)
            }
        } catch (e: Exception) {
            Log.e("CourseTaskFragment", "Error opening image gallery", e)
            Toast.makeText(context, "Error al abrir la galería de imágenes", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openCameraForPhoto() {
        try {
            val imageDir = File(requireContext().filesDir, "images")
            if (!imageDir.exists()) imageDir.mkdirs()

            val imageFile = File(imageDir, "photo_${UUID.randomUUID()}.jpg")
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                imageFile
            )

            cameraImageUri = uri
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            intent.putExtra(MediaStore.EXTRA_OUTPUT, uri)

            if (intent.resolveActivity(requireActivity().packageManager) != null) {
                cameraLauncher.launch(intent)
            } else {
                Toast.makeText(context, "No se encontró aplicación de cámara", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("CourseTaskFragment", "Error opening camera", e)
            Toast.makeText(context, "Error al abrir la cámara", Toast.LENGTH_SHORT).show()
        }
    }

    // Helper method to get filename from URI
    private fun getFileName(uri: Uri): String? {
        return try {
            if (uri.scheme.equals("content", ignoreCase = true)) {
                val contentResolver = requireContext().contentResolver
                val cursor = contentResolver.query(uri, null, null, null, null)

                cursor?.use {
                    if (it.moveToFirst()) {
                        val displayNameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                        if (displayNameIndex != -1) {
                            return@use it.getString(displayNameIndex)
                        }
                    }
                    null
                }
            } else {
                uri.lastPathSegment?.substringAfterLast('/')
            }
        } catch (e: Exception) {
            Log.w("CourseTaskFragment", "Could not resolve file name for $uri", e)
            uri.lastPathSegment?.substringAfterLast('/')
        }
    }

    // Improved helper method to get file extension
    private fun getFileExtension(uri: Uri): String {
        val contentResolver = requireContext().contentResolver
        val mimeType = contentResolver.getType(uri)

        // First try to get the extension from the filename
        val fileName = getFileName(uri)
        if (fileName?.contains(".") == true) {
            return ""  // The extension is already in the filename
        }

        // If no extension in filename, determine from MIME type
        return when {
            mimeType?.contains("image") == true -> ".jpg"
            mimeType?.contains("video") == true -> ".mp4"
            mimeType?.contains("audio") == true -> ".mp3"
            mimeType?.contains("pdf") == true -> ".pdf"
            mimeType?.contains("msword") == true -> ".doc"
            mimeType?.contains("wordprocessingml") == true -> ".docx"
            mimeType?.contains("ms-excel") == true -> ".xls"
            mimeType?.contains("spreadsheetml") == true -> ".xlsx"
            mimeType?.contains("ms-powerpoint") == true -> ".ppt"
            mimeType?.contains("presentationml") == true -> ".pptx"
            mimeType?.contains("text") == true -> ".txt"
            else -> ""
        }
    }

    // Add this companion object with the tag constants
    companion object {
        private val CONTENT_TYPE_TAG = R.id.content_type_tag
        private val CONTENT_ID_TAG = R.id.content_id_tag
    }

    private fun inferMimeType(type: String, fileName: String?, uriString: String): String {
        val extension = fileName
            ?.substringAfterLast('.', "")
            ?.takeIf { it.isNotBlank() }
            ?: Uri.parse(uriString).lastPathSegment?.substringAfterLast('.', "")

        return when (extension?.lowercase()) {
            "pdf" -> "application/pdf"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "txt" -> "text/plain"
            else -> if (type == "video") "video/*" else "*/*"
        }
    }

    private fun addContentItemView(uri: Uri, type: String, name: String? = null, contentId: Long? = null, r2Url: String? = null) {
        val inflater = LayoutInflater.from(context)
        val contentView = inflater.inflate(R.layout.item_course_content, contentContainer, false)

        val iconView = contentView.findViewById<ImageView>(R.id.contentIconView)
        val nameView = contentView.findViewById<TextView>(R.id.contentNameView)
        val deleteButton = contentView.findViewById<ImageButton>(R.id.deleteContentButton)

        // Get a meaningful display name
        val baseName = name ?: getFileName(uri) ?: "Contenido ${contentContainer.childCount + 1}"
        // Mostrar ☁️ si está en la nube
        val displayName = if (r2Url != null) "☁️ $baseName" else baseName
        nameView.text = displayName

        // Set appropriate icon based on content type and file extension
        val iconRes = when {
            type == "video" -> android.R.drawable.ic_media_play
            uri.toString().endsWith(".pdf", ignoreCase = true) -> android.R.drawable.ic_menu_agenda
            uri.toString().endsWith(".doc", ignoreCase = true) ||
                    uri.toString().endsWith(".docx", ignoreCase = true) -> android.R.drawable.ic_menu_edit
            uri.toString().endsWith(".xls", ignoreCase = true) ||
                    uri.toString().endsWith(".xlsx", ignoreCase = true) -> android.R.drawable.ic_menu_sort_by_size
            uri.toString().endsWith(".ppt", ignoreCase = true) ||
                    uri.toString().endsWith(".pptx", ignoreCase = true) -> android.R.drawable.ic_menu_slideshow
            else -> android.R.drawable.ic_menu_help
        }
        iconView.setImageResource(iconRes)

        // Store URI and metadata using the resource IDs
        contentView.tag = uri
        contentView.setTag(CONTENT_TYPE_TAG, type)
        contentView.setTag(R.id.content_name_tag, baseName)
        if (contentId != null) {
            contentView.setTag(CONTENT_ID_TAG, contentId)
        }

        // Handle delete button click
        deleteButton.setOnClickListener {
            val existingContentId = contentView.getTag(CONTENT_ID_TAG) as? Long

            viewLifecycleOwner.lifecycleScope.launch {
                var backendDeleted = true

                if (existingContentId != null && existingContentId > 0) {
                    backendDeleted = withContext(Dispatchers.IO) {
                        try {
                            when (val result = BackendApiService.deleteContentItem(existingContentId)) {
                                is ApiResult.Success -> true
                                is ApiResult.Error -> {
                                    Log.e("CourseTaskFragment", "❌ Failed deleting content item $existingContentId: ${result.message}")
                                    false
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CourseTaskFragment", "Error deleting content item $existingContentId", e)
                            false
                        }
                    }
                }

                if (!backendDeleted) {
                    showToastSafe("No se pudo eliminar el contenido en el servidor", Toast.LENGTH_LONG)
                    return@launch
                }

                contentContainer.removeView(contentView)

                if (r2Url != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            StorageHelper.deleteFile(r2Url)
                            Log.d("CourseTaskFragment", "🗑️ Deleted from R2: $r2Url")
                        } catch (e: Exception) {
                            Log.e("CourseTaskFragment", "Error deleting from R2", e)
                        }
                    }
                }
            }
        }

        // Make the content item clickable to preview
        contentView.setOnClickListener {
            try {
                // Check if it's an R2/HTTP URL
                val uriString = r2Url ?: uri.toString()
                if (StorageHelper.isR2Url(uriString) || 
                    uriString.startsWith("http://") || 
                    uriString.startsWith("https://")) {
                    
                    Log.d("CourseTaskFragment", "🎬 Opening remote content: $uriString, type=$type")
                    
                    if (type == "video") {
                        // Use VideoPlayerActivity for videos
                        val intent = Intent(requireContext(), VideoPlayerActivity::class.java)
                        intent.putExtra("video_path", uriString)
                        intent.putExtra("video_title", baseName)
                        intent.putExtra("video_description", "")
                        intent.putExtra("username", sessionManager.getUsername() ?: "")
                        startActivity(intent)
                    } else {
                        // Open documents/other files in browser
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uriString))
                        startActivity(intent)
                    }
                } else {
                    // Handle local content
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, inferMimeType(type, baseName, uri.toString()))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    if (intent.resolveActivity(requireActivity().packageManager) != null) {
                        startActivity(intent)
                    } else {
                        Toast.makeText(context, "No hay aplicación para abrir este tipo de archivo", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseTaskFragment", "Error opening content", e)
                Toast.makeText(context, "Error al abrir el contenido", Toast.LENGTH_SHORT).show()
            }
        }

        contentContainer.addView(contentView)
    }
}