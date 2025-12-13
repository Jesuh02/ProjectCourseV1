package com.example.tareamov.ui

import android.app.Activity
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
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.VideoManager
import com.example.tareamov.viewmodel.CourseCreationViewModel
import com.example.tareamov.service.CloudflareR2Service
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File
import java.io.FileOutputStream
import android.widget.ImageView

class CourseTaskFragment : Fragment() {

    private var topicId: Long = -1L
    private var taskId: Long = -1L // -1 indicates a new task
    private var existingTask: Task? = null
    private var isTemporary: Boolean = false // Add this variable

    // Repository for remote checks (optional Supabase fallback)
    private val syncRepository by lazy {
        com.example.tareamov.data.sync.SyncRepository(
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
            AppDatabase.getDatabase(requireContext()).fileContextDao(),
            AppDatabase.getDatabase(requireContext()).progresoEstudianteDao()
        )
    }

    private lateinit var taskNameEditText: EditText
    private lateinit var taskDescriptionEditText: EditText
    private lateinit var contentContainer: LinearLayout
    private lateinit var videoManager: VideoManager

    // Use the shared ViewModel
    private val viewModel: CourseCreationViewModel by activityViewModels()

    private lateinit var videoPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var documentPickerLauncher: ActivityResultLauncher<Intent>

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
    }

    // Método para manejar documentos y subirlos a R2
    private fun handleSelectedDocumentUri(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var finalUri: Uri = uri
                var r2Url: String? = null

                // Subir a Cloudflare R2 si está configurado
                if (CloudflareR2Service.isConfigured()) {
                    Log.d("CourseTaskFragment", "☁️ Uploading document to Cloudflare R2: $uri")
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Subiendo documento a la nube...", Toast.LENGTH_SHORT).show()
                    }
                    
                    val result = withContext(Dispatchers.IO) {
                        CloudflareR2Service.uploadDocument(
                            context = requireContext(),
                            documentUri = uri,
                            onProgress = { progress ->
                                Log.d("CourseTaskFragment", "Document upload progress: $progress%")
                            }
                        )
                    }
                    
                    when (result) {
                        is CloudflareR2Service.UploadResult.Success -> {
                            r2Url = result.url
                            finalUri = Uri.parse(r2Url)
                            Log.d("CourseTaskFragment", "✅ R2 Document Upload successful: $r2Url")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Documento subido a la nube ✓", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is CloudflareR2Service.UploadResult.Error -> {
                            Log.e("CourseTaskFragment", "❌ R2 Document Upload failed: ${result.message}")
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
        contentContainer = view.findViewById(R.id.contentContainer)
        val taskTitleTextView = view.findViewById<TextView>(R.id.taskTitleTextView)
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        val saveTaskButton = view.findViewById<Button>(R.id.saveTaskButton)
        val addVideoButton = view.findViewById<LinearLayout>(R.id.addVideoButton)
        val addDocumentButton = view.findViewById<LinearLayout>(R.id.addDocumentButton)

        backButton.setOnClickListener { findNavController().navigateUp() }
        saveTaskButton.setOnClickListener { saveTask() }

        addVideoButton.setOnClickListener { openGalleryForVideo() }
        addDocumentButton.setOnClickListener { openDocumentPicker() }
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
                contentItem.name = getFileName(contentUri) ?: "Contenido sin título"
                currentTask.contentItems.add(contentItem)
            }
        }
    }

    private fun loadFromViewModel() {
        val currentTask = viewModel.getCurrentTask()
        if (currentTask != null) {
            taskNameEditText.setText(currentTask.name)
            taskDescriptionEditText.setText(currentTask.description)

            // Load content items
            contentContainer.removeAllViews()
            for (contentItem in currentTask.contentItems) {
                val uri = Uri.parse(contentItem.uriString)
                addContentItemView(uri, contentItem.contentType)
            }
        }
    }

    private fun loadTaskDetails() {
    val taskDao = AppDatabase.getDatabase(requireContext()).taskDao()
    val contentItemDao = AppDatabase.getDatabase(requireContext()).contentItemDao()
    val topicDao = AppDatabase.getDatabase(requireContext()).topicDao()

        viewLifecycleOwner.lifecycleScope.launch {
            existingTask = withContext(Dispatchers.IO) { taskDao.getTaskById(taskId) }
                if (existingTask == null) {
                Log.w("CourseTaskFragment", "Local task with ID $taskId not found, attempting Supabase fallback.")
                // Try remote fetch via SyncRepository wrapper for single task
                try {
                    val syncRepo = com.example.tareamov.data.sync.SyncRepository(
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
                        AppDatabase.getDatabase(requireContext()).fileContextDao(),
                        AppDatabase.getDatabase(requireContext()).progresoEstudianteDao()
                    )
                    if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                        val remote = withContext(Dispatchers.IO) { syncRepo.fetchTaskByIdFromSupabase(taskId) }
                        if (remote != null) {
                            // Map the remote topicId to a local topic id. If the local topic
                            // doesn't exist, create a placeholder local Topic tied to the
                            // local courseId argument so Room foreign keys remain valid.
                            var mappedTopicId = remote.topicId
                            try {
                                val argCourseId = arguments?.getLong("courseId", -1L) ?: -1L
                                val localTopic = withContext(Dispatchers.IO) { topicDao.getTopicById(remote.topicId) }
                                if (localTopic != null) {
                                    mappedTopicId = localTopic.id
                                } else if (argCourseId > 0) {
                                    // Try to find a topic with the same orderIndex under the local course
                                    val byOrder = withContext(Dispatchers.IO) { topicDao.getTopicByCourseIdAndOrderIndex(argCourseId, remote.orderIndex ?: 0) }
                                    if (byOrder != null) {
                                        mappedTopicId = byOrder.id
                                    } else {
                                        // Create a new local Topic tied to the local course id
                                        val newTopic = com.example.tareamov.data.entity.Topic(
                                            courseId = argCourseId,
                                            name = "Tema (migrado)",
                                            description = "",
                                            orderIndex = remote.orderIndex ?: 0
                                        )
                                        mappedTopicId = withContext(Dispatchers.IO) { topicDao.insertTopic(newTopic) }
                                        Log.i("CourseTaskFragment", "Created placeholder local topic id=$mappedTopicId for remote topic ${remote.topicId}")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("CourseTaskFragment", "Failed to map remote topic id to local", e)
                                mappedTopicId = remote.topicId
                            }

                            existingTask = Task(
                                id = remote.id,
                                topicId = mappedTopicId,
                                name = remote.name ?: "",
                                description = remote.description ?: null,
                                orderIndex = remote.orderIndex ?: 0
                            )
                            val tmpTask = existingTask
                            Log.i("CourseTaskFragment", "Loaded task $taskId from Supabase fallback. Mapped topicId=${tmpTask?.topicId}")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("CourseTaskFragment", "Supabase fallback for task failed", e)
                }

                if (existingTask == null) {
                    Log.e("CourseTaskFragment", "Error: Task with ID $taskId not found.")
                    Toast.makeText(context, "Error al cargar la tarea", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                    return@launch
                }
            }

            // Populate UI with existing task data
            val task = existingTask!! // capture to local val to avoid smart-cast after suspend
            taskNameEditText.setText(task.name)
            taskDescriptionEditText.setText(task.description ?: "")
            topicId = task.topicId // Ensure topicId is set from the loaded task

            // Load content items for the task - try Supabase first, then local DB
            Log.d("CourseTaskFragment", "📚 Loading content items for taskId=$taskId")
            var contentItems: List<ContentItem> = emptyList()
            
            try {
                if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                    // Fetch from Supabase
                    contentItems = withContext(Dispatchers.IO) { 
                        syncRepository.fetchContentItemsByTaskIdFromSupabase(taskId) 
                    }
                    Log.d("CourseTaskFragment", "📦 Loaded ${contentItems.size} content items from Supabase for task")
                }
            } catch (e: Exception) {
                Log.w("CourseTaskFragment", "Failed to load content from Supabase, falling back to local DB", e)
            }
            
            // Fallback to local DB if Supabase returned empty or failed
            if (contentItems.isEmpty()) {
                contentItems = withContext(Dispatchers.IO) { contentItemDao.getContentItemsByTaskId(taskId) }
                Log.d("CourseTaskFragment", "📦 Loaded ${contentItems.size} content items from local DB for task")
            }
            
            // Add content items to the UI
            for (contentItem in contentItems) {
                val r2Url = if (CloudflareR2Service.isR2Url(contentItem.uriString)) contentItem.uriString else null
                addContentItemView(
                    Uri.parse(contentItem.uriString), 
                    contentItem.contentType, 
                    contentItem.name,
                    contentItem.id,
                    r2Url
                )
            }
            
            // Also load Topic content items if we have a valid topicId
            if (topicId > 0) {
                Log.d("CourseTaskFragment", "📖 Loading Topic content items for topicId=$topicId")
                var topicContentItems: List<ContentItem> = emptyList()
                
                try {
                    if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                        val allTopicItems = withContext(Dispatchers.IO) { 
                            syncRepository.fetchContentItemsByTopicIdsFromSupabase(listOf(topicId)) 
                        }
                        // Filter: only include topic-level content (taskId == null), not task content
                        topicContentItems = allTopicItems.filter { it.taskId == null || it.taskId == 0L }
                        Log.d("CourseTaskFragment", "📚 Loaded ${allTopicItems.size} content items from Supabase for topic, ${topicContentItems.size} topic-level")
                    }
                } catch (e: Exception) {
                    Log.w("CourseTaskFragment", "Failed to load topic content from Supabase", e)
                }
                
                // Fallback to local DB
                if (topicContentItems.isEmpty()) {
                    val localItems = withContext(Dispatchers.IO) { contentItemDao.getContentItemsByTopicId(topicId) }
                    // Filter: only include topic-level content
                    topicContentItems = localItems.filter { it.taskId == null || it.taskId == 0L }
                    Log.d("CourseTaskFragment", "📚 Loaded ${localItems.size} content items from local DB for topic, ${topicContentItems.size} topic-level")
                }
                
                // Show topic content in a separate section if available
                if (topicContentItems.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        showTopicContentSection(topicContentItems)
                    }
                }
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
        val displayName = if (CloudflareR2Service.isR2Url(item.uriString)) {
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
                val uri = if (CloudflareR2Service.isR2Url(item.uriString) || 
                             item.uriString.startsWith("http://") || 
                             item.uriString.startsWith("https://")) {
                    Uri.parse(item.uriString)
                } else {
                    Uri.parse(item.uriString)
                }
                
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("CourseTaskFragment", "Error opening content: ${e.message}", e)
            Toast.makeText(context, "No se puede abrir el contenido: ${item.name}", Toast.LENGTH_SHORT).show()
        }
    }

    // In the saveTask method, update the navigation after saving
    private fun saveTask() {
        val taskName = taskNameEditText.text.toString().trim()
        val taskDescription = taskDescriptionEditText.text.toString().trim()
        var courseId = arguments?.getLong("courseId", -1L) ?: -1L
        val courseName = arguments?.getString("courseName") ?: "Curso sin nombre"
        val topicNumber = arguments?.getInt("topicNumber", 0) ?: 0

        if (taskName.isBlank()) {
            Toast.makeText(context, "El nombre de la tarea no puede estar vacío", Toast.LENGTH_SHORT).show()
            return
        }

        val taskDao = AppDatabase.getDatabase(requireContext()).taskDao()
        val topicDao = AppDatabase.getDatabase(requireContext()).topicDao()
        val contentItemDao = AppDatabase.getDatabase(requireContext()).contentItemDao()
        val videoDao = AppDatabase.getDatabase(requireContext()).videoDao()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // If courseId is not provided but we have a topicId, fetch the course from the topic
                if (courseId <= 0 && topicId > 0) {
                    Log.d("CourseTaskFragment", "courseId not provided, fetching from topic $topicId")
                    val appDatabase = AppDatabase.getDatabase(requireContext())
                    val syncRepo = (activity as? com.example.tareamov.MainActivity)?.syncRepository
                        ?: com.example.tareamov.data.sync.SyncRepository(
                            appDatabase.usuarioDao(), appDatabase.personaDao(), appDatabase.topicDao(),
                            appDatabase.contentItemDao(), appDatabase.taskDao(), appDatabase.subscriptionDao(),
                            appDatabase.taskSubmissionDao(), appDatabase.videoDao(), appDatabase.courseDao(),
                            appDatabase.rolDao(), appDatabase.recursoDao(), appDatabase.rolRecursoDao(),
                            appDatabase.chatMessageDao(), appDatabase.fileContextDao(), appDatabase.progresoEstudianteDao()
                        )
                    
                    // Fetch the topic to get the courseId
                    val topic = withContext(Dispatchers.IO) {
                        try {
                            // First try local DB
                            topicDao.getTopicById(topicId) ?: 
                            // Then try Supabase
                            com.example.tareamov.service.SupabaseClient.fetchTopicById(topicId)
                        } catch (e: Exception) {
                            Log.w("CourseTaskFragment", "Error fetching topic", e)
                            null
                        }
                    }
                    
                    if (topic != null) {
                        courseId = topic.courseId
                        Log.d("CourseTaskFragment", "Fetched courseId=$courseId from topic $topicId")
                    } else {
                        Toast.makeText(context, "Error: No se pudo obtener información del curso", Toast.LENGTH_SHORT).show()
                        Log.e("CourseTaskFragment", "Could not fetch topic $topicId to get courseId")
                        return@launch
                    }
                }

                // Resolve authoritative course id from Supabase first
                val appDatabase = AppDatabase.getDatabase(requireContext())
                val syncRepo = (activity as? com.example.tareamov.MainActivity)?.syncRepository
                    ?: com.example.tareamov.data.sync.SyncRepository(
                        appDatabase.usuarioDao(), appDatabase.personaDao(), appDatabase.topicDao(),
                        appDatabase.contentItemDao(), appDatabase.taskDao(), appDatabase.subscriptionDao(),
                        appDatabase.taskSubmissionDao(), appDatabase.videoDao(), appDatabase.courseDao(),
                        appDatabase.rolDao(), appDatabase.recursoDao(), appDatabase.rolRecursoDao(),
                        appDatabase.chatMessageDao(), appDatabase.fileContextDao(), appDatabase.progresoEstudianteDao()
                    )

                val resolvedCourseId = withContext(Dispatchers.IO) {
                    try {
                        if (courseId > 0) {
                            val remote = syncRepo.fetchCourseById(courseId)
                            if (remote != null) return@withContext remote.id
                        }
                        if (courseName.isNotBlank()) {
                            val all = syncRepo.fetchCoursesFromSupabase()
                            val byName = all.firstOrNull { it.title?.trim()?.equals(courseName.trim(), ignoreCase = true) == true }
                            if (byName != null) return@withContext byName.id
                        }
                    } catch (e: Exception) {
                        Log.w("CourseTaskFragment", "Error resolving remote course id", e)
                    }
                    // fallback to passed courseId or -1 handled below
                    courseId
                }

                // Validate that topicId exists before attempting to save task
                if (topicId <= 0) {
                    Toast.makeText(context, "Error: ID de tema inválido", Toast.LENGTH_SHORT).show()
                    Log.e("CourseTaskFragment", "Invalid topicId: $topicId")
                    return@launch
                }

                // Verify topic exists (either in local DB or Supabase)
                val topicExists = withContext(Dispatchers.IO) {
                    try {
                        // First check local DB
                        val localTopic = topicDao.getTopicById(topicId)
                        if (localTopic != null) {
                            Log.d("CourseTaskFragment", "Topic $topicId found in local DB")
                            return@withContext true
                        }
                        
                        // If not in local DB, check Supabase
                        val remoteTopic = com.example.tareamov.service.SupabaseClient.fetchTopicById(topicId)
                        if (remoteTopic != null) {
                            Log.d("CourseTaskFragment", "Topic $topicId found in Supabase")
                            return@withContext true
                        }
                        
                        Log.w("CourseTaskFragment", "Topic $topicId not found in local DB or Supabase")
                        false
                    } catch (e: Exception) {
                        Log.e("CourseTaskFragment", "Error verifying topic exists", e)
                        false
                    }
                }

                if (!topicExists) {
                    Toast.makeText(context, "Error: El tema no existe", Toast.LENGTH_SHORT).show()
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
                    orderIndex = 0
                )

                Log.d("CourseTaskFragment", "Attempting to save task: id=${remoteTask.id}, name=${remoteTask.name}, topicId=${remoteTask.topicId}, isUpdate=${taskId > 0}")

                // Push to Supabase (insert or update)
                val pushedTaskId = withContext(Dispatchers.IO) {
                    try {
                        if (taskId > 0) {
                            Log.d("CourseTaskFragment", "Updating existing task $taskId")
                            val ok = syncRepo.updateTaskRemote(remoteTask)
                            if (ok) {
                                Log.d("CourseTaskFragment", "Task updated successfully")
                                remoteTask.id
                            } else {
                                Log.e("CourseTaskFragment", "Task update returned false")
                                null
                            }
                        } else {
                            Log.d("CourseTaskFragment", "Inserting new task: topicId=${remoteTask.topicId}, title='${remoteTask.name}'")
                            
                            // Insert task (creator metadata not needed since tasks table doesn't have those columns)
                            val result = syncRepo.insertTaskRemote(remoteTask)
                            if (result != null) {
                                Log.d("CourseTaskFragment", "Task inserted with id=$result")
                            } else {
                                Log.e("CourseTaskFragment", "Task insert returned null")
                            }
                            result
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
                    Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                    Log.e("CourseTaskFragment", "Failed to save task to Supabase - topicId=$topicId, taskName=$taskName, taskId=$taskId")
                    return@launch
                }

                // Save content items for all tasks (new and existing)
                val contentItemsToSave = mutableListOf<ContentItem>()
                val currentUsername = sessionManager.getUsername()
                val currentUserId = withContext(Dispatchers.IO) {
                    currentUsername?.let { username ->
                        AppDatabase.getDatabase(requireContext()).usuarioDao()
                            .getUsuarioByUsername(username)?.id
                    }
                }
                
                for (i in 0 until contentContainer.childCount) {
                    val itemView = contentContainer.getChildAt(i)
                    val contentUri = itemView.tag as? Uri
                    val contentType = itemView.getTag(R.id.content_type_tag) as? String
                    if (contentUri != null && contentType != null) {
                        contentItemsToSave.add(
                            ContentItem(
                                id = 0,
                                topicId = 0, // Se deja en 0 porque pertenece a una tarea
                                taskId = savedTaskId,
                                name = getFileName(contentUri) ?: "Contenido sin título",
                                contentType = contentType,
                                uriString = contentUri.toString(),
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
                                val remoteId = syncRepo.insertContentItemRemote(item)
                                if (remoteId != null) {
                                    successCount++
                                    Log.d("CourseTaskFragment", "Saved content item to Supabase: ${item.name} with id=$remoteId")
                                } else {
                                    Log.w("CourseTaskFragment", "Failed to save content item to Supabase: ${item.name}")
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
                    Toast.makeText(context, "Tarea guardada, pero algunos contenidos no se pudieron guardar", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Tarea guardada exitosamente", Toast.LENGTH_SHORT).show()
                }

                // NUEVO: Crear submissions automáticas con calificación 0 para todos los estudiantes inscritos
                if (taskId <= 0 && courseId > 0) { // Solo para tareas nuevas
                    Log.d("CourseTaskFragment", "Creating default submissions for new task $savedTaskId in course $courseId")
                    val createdCount = withContext(Dispatchers.IO) {
                        syncRepo.createDefaultSubmissionsForTask(savedTaskId, courseId)
                    }
                    Log.i("CourseTaskFragment", "Created $createdCount default submissions with grade 0")
                    
                    // NOTIFICAR a los estudiantes inscritos sobre la nueva tarea
                    val currentUsername = sessionManager.getUsername() ?: "Instructor"
                    val currentUserId = sessionManager.getUserId()
                    val currentAvatarUrl = sessionManager.getUserAvatar()
                    
                    Log.d("CourseTaskFragment", "📢 Notifying enrolled students about new task '$taskName' in course '$courseName'")
                    (requireActivity() as? com.example.tareamov.MainActivity)?.syncRepository?.notifyEnrolledStudentsOfNewTaskAsync(
                        taskId = savedTaskId,
                        taskName = taskName,
                        courseId = courseId,
                        courseName = courseName ?: "Curso",
                        creatorUserId = currentUserId,
                        creatorUsername = currentUsername,
                        creatorAvatarUrl = currentAvatarUrl
                    )
                }
                
                // IMPORTANTE: Recalcular progreso de todos los estudiantes después de agregar/editar tarea
                if (courseId > 0) {
                    Log.d("CourseTaskFragment", "🔄 Recalculating student progress for course $courseId")
                    val updatedStudents = withContext(Dispatchers.IO) {
                        syncRepo.recalculateAllStudentProgressForCourse(courseId)
                    }
                    Log.i("CourseTaskFragment", "✅ Updated progress for $updatedStudents students")
                }

                // Notify CourseDetailFragment to refresh from Supabase and switch to tasks tab
                // Only set force_reload_topics to avoid duplicate refresh calls
                findNavController().previousBackStackEntry?.savedStateHandle?.set("switch_to_tasks_tab", true)
                findNavController().previousBackStackEntry?.savedStateHandle?.set("force_reload_topics", true)

                // Navigate back to CourseDetailFragment specifically, clearing this fragment
                findNavController().popBackStack(R.id.courseDetailFragment, false)
            } catch (e: Exception) {
                Log.e("CourseTaskFragment", "Error saving task", e)
                Toast.makeText(context, "Error al guardar la tarea", Toast.LENGTH_SHORT).show()
            }
        }
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

                // Subir a Cloudflare R2 si está configurado
                if (CloudflareR2Service.isConfigured()) {
                    Log.d("CourseTaskFragment", "☁️ Uploading video to Cloudflare R2: $uri")
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Subiendo video a la nube...", Toast.LENGTH_SHORT).show()
                    }
                    
                    val result = withContext(Dispatchers.IO) {
                        CloudflareR2Service.uploadVideo(
                            context = requireContext(),
                            videoUri = uri,
                            onProgress = { progress ->
                                Log.d("CourseTaskFragment", "Video upload progress: $progress%")
                            }
                        )
                    }
                    
                    when (result) {
                        is CloudflareR2Service.UploadResult.Success -> {
                            r2Url = result.url
                            finalUri = Uri.parse(r2Url)
                            Log.d("CourseTaskFragment", "✅ R2 Video Upload successful: $r2Url")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Video subido a la nube ✓", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is CloudflareR2Service.UploadResult.Error -> {
                            Log.e("CourseTaskFragment", "❌ R2 Video Upload failed: ${result.message}")
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

    // Helper method to get filename from URI
    private fun getFileName(uri: Uri): String? {
        val contentResolver = requireContext().contentResolver
        val cursor = contentResolver.query(uri, null, null, null, null)

        return cursor?.use {
            if (it.moveToFirst()) {
                val displayNameIndex = it.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (displayNameIndex != -1) {
                    return@use it.getString(displayNameIndex)
                }
            }
            null
        } ?: uri.lastPathSegment
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
        if (contentId != null) {
            contentView.setTag(CONTENT_ID_TAG, contentId)
        }

        // Handle delete button click
        deleteButton.setOnClickListener {
            contentContainer.removeView(contentView)
            // Si es URL de R2, eliminar del servidor
            if (r2Url != null) {
                viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        CloudflareR2Service.deleteFile(r2Url)
                        Log.d("CourseTaskFragment", "🗑️ Deleted from R2: $r2Url")
                    } catch (e: Exception) {
                        Log.e("CourseTaskFragment", "Error deleting from R2", e)
                    }
                }
            }
        }

        // Make the content item clickable to preview
        contentView.setOnClickListener {
            try {
                // Check if it's an R2/HTTP URL
                val uriString = r2Url ?: uri.toString()
                if (CloudflareR2Service.isR2Url(uriString) || 
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
                        setDataAndType(uri, when (type) {
                            "video" -> "video/*"
                            else -> requireContext().contentResolver.getType(uri) ?: "*/*"
                        })
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