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
import com.example.tareamov.util.UriPermissionManager
import com.example.tareamov.util.VideoManager
import com.example.tareamov.viewmodel.CourseCreationViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File
import java.io.FileOutputStream

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
            AppDatabase.getDatabase(requireContext()).fileContextDao()
        )
    }

    private lateinit var taskNameEditText: EditText
    private lateinit var taskDescriptionEditText: EditText
    private lateinit var contentContainer: LinearLayout
    private lateinit var uriPermissionManager: UriPermissionManager
    private lateinit var videoManager: VideoManager

    // Use the shared ViewModel
    private val viewModel: CourseCreationViewModel by activityViewModels()

    private lateinit var videoPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var documentPickerLauncher: ActivityResultLauncher<Intent>

    private lateinit var sessionManager: SessionManager
    private var isCourseCreator: Boolean = false
    private var courseCreatorUsername: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            topicId = it.getLong("topicId", -1L)
            taskId = it.getLong("taskId", -1L)
            isTemporary = it.getBoolean("isTemporary", false)
            Log.d("CourseTaskFragment", "Received topicId: $topicId, taskId: $taskId, isTemporary: $isTemporary")
        }
        uriPermissionManager = UriPermissionManager(requireContext())
        videoManager = VideoManager(requireContext())

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
                    try {
                        // Take persistable permission if possible
                        uriPermissionManager.takePersistablePermission(uri)
                    } catch (e: SecurityException) {
                        Log.e("CourseTaskFragment", "Could not take persistable permission: ${e.message}")
                    }

                    // Create a local copy of the file if needed
                    val localUri = copyUriToLocalStorage(uri, "document")
                    addContentItemView(localUri ?: uri, "document")
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
            val contentView = contentContainer.getChildAt(i)
            val uri = contentView.tag as? Uri
            val type = contentView.getTag(R.id.content_type_tag) as? String
            val nameView = contentView.findViewById<TextView>(R.id.contentNameView)
            val name = nameView?.text?.toString() ?: "Contenido ${i + 1}"

            if (uri != null && type != null) {
                val tempContentItem = CourseCreationViewModel.TemporaryContentItem().apply {
                    this.name = name
                    this.uriString = uri.toString()
                    this.contentType = type
                    this.orderIndex = i
                }
                currentTask.contentItems.add(tempContentItem)
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
            for (tempContentItem in currentTask.contentItems) {
                val uri = Uri.parse(tempContentItem.uriString)
                addContentItemView(uri, tempContentItem.contentType, tempContentItem.name)
            }
        }
    }

    private fun loadTaskDetails() {
    val taskDao = AppDatabase.getDatabase(requireContext()).taskDao()
    val contentItemDao = AppDatabase.getDatabase(requireContext()).contentItemDao()
    val topicDao = AppDatabase.getDatabase(requireContext()).topicDao()

        CoroutineScope(Dispatchers.Main).launch {
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
                        AppDatabase.getDatabase(requireContext()).fileContextDao()
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

            // Load associated content items
            val contentItems = withContext(Dispatchers.IO) { contentItemDao.getContentItemsByTaskId(taskId) }
            contentContainer.removeAllViews()
            contentItems.sortedBy { it.orderIndex }.forEach { item ->
                addContentItemView(Uri.parse(item.uriString), item.contentType, item.name, item.id)
            }
        }
    }

    // In the saveTask method, update the navigation after saving
    private fun saveTask() {
        val taskName = taskNameEditText.text.toString().trim()
        val taskDescription = taskDescriptionEditText.text.toString().trim()
        val courseId = arguments?.getLong("courseId", -1L) ?: -1L
        val courseName = arguments?.getString("courseName") ?: "Curso sin nombre" // Default value if not provided
        val topicNumber = arguments?.getInt("topicNumber", 0) ?: 0

        if (taskName.isBlank()) {
            Toast.makeText(context, "El nombre de la tarea no puede estar vacío", Toast.LENGTH_SHORT).show()
            return
        }

        // Remove the course name validation since we're providing a default
        // if (courseId == -1L || courseName.isBlank()) {
        //     Toast.makeText(context, "Error: Falta el nombre del curso", Toast.LENGTH_SHORT).show()
        //     return
        // }

        val taskDao = AppDatabase.getDatabase(requireContext()).taskDao()
    val topicDao = AppDatabase.getDatabase(requireContext()).topicDao()
    val contentItemDao = AppDatabase.getDatabase(requireContext()).contentItemDao()
    val videoDao = AppDatabase.getDatabase(requireContext()).videoDao()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Resolve authoritative course id from Supabase first
                val appDatabase = AppDatabase.getDatabase(requireContext())
                val syncRepo = (activity as? com.example.tareamov.MainActivity)?.syncRepository
                    ?: com.example.tareamov.data.sync.SyncRepository(
                        appDatabase.usuarioDao(), appDatabase.personaDao(), appDatabase.topicDao(),
                        appDatabase.contentItemDao(), appDatabase.taskDao(), appDatabase.subscriptionDao(),
                        appDatabase.taskSubmissionDao(), appDatabase.videoDao(), appDatabase.courseDao(),
                        appDatabase.rolDao(), appDatabase.recursoDao(), appDatabase.rolRecursoDao(),
                        appDatabase.chatMessageDao(), appDatabase.fileContextDao()
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

                // Instead of inserting into local Room (which causes FK issues when parents
                // are missing), make Supabase the authoritative source for creating/updating
                // tasks and content items. We'll call syncRepo to perform remote inserts/updates
                // and avoid creating placeholder VideoData/Topic rows locally.
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

                // Push to Supabase (insert or update)
                val pushedTaskId = withContext(Dispatchers.IO) {
                    try {
                        if (taskId > 0) {
                            val ok = syncRepo.updateTaskRemote(remoteTask)
                            if (ok) remoteTask.id else null
                        } else {
                            syncRepo.insertTaskRemote(remoteTask)
                        }
                    } catch (e: Exception) {
                        Log.w("CourseTaskFragment", "Remote task push failed", e)
                        null
                    }
                }

                if (pushedTaskId != null) {
                    savedTaskId = pushedTaskId
                    // If the remote returned a task id and topicId may represent a remote topic id
                    // keep using the topicId argument (it's expected to be a Supabase id).
                    savedTopicId = topicId
                    Log.i("CourseTaskFragment", "Task saved remotely with id=$savedTaskId topic=$savedTopicId")
                } else {
                    // Remote push failed; abort to avoid local FK operations that previously failed.
                    throw IllegalStateException("Failed to save task to Supabase")
                }

                // In the saveTask method, when creating content items:

                // Save content items (same as before)
                val contentItemsToSave = mutableListOf<ContentItem>()
                for (i in 0 until contentContainer.childCount) {
                    val contentView = contentContainer.getChildAt(i)
                    val uri = contentView.tag as? Uri
                    val type = contentView.getTag(R.id.content_type_tag) as? String
                    val nameView = contentView.findViewById<TextView>(R.id.contentNameView)
                    val name = nameView?.text?.toString() ?: "Contenido ${i + 1}"

                    if (uri != null && type != null) {
                        contentItemsToSave.add(
                            ContentItem(
                                topicId = savedTopicId, // Change from -1 to the actual topicId
                                taskId = savedTaskId,
                                name = name,
                                uriString = uri.toString(),
                                contentType = type,
                                orderIndex = i
                            )
                        )
                    }
                }

                if (contentItemsToSave.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        contentItemsToSave.forEach { contentItemDao.insertContentItem(it) }
                    }
                    Log.d("CourseTaskFragment", "Saved ${contentItemsToSave.size} content items for task ID: $savedTaskId")
                }

                Toast.makeText(context, "Tarea guardada exitosamente", Toast.LENGTH_SHORT).show()

                // Notify CourseDetailFragment to refresh from Supabase
                findNavController().previousBackStackEntry?.savedStateHandle?.set("task_created", savedTaskId)
                findNavController().previousBackStackEntry?.savedStateHandle?.set("refresh_from_supabase", true)

                // Navigate back
                findNavController().navigateUp()
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
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // First try to take persistable permission
                try {
                    uriPermissionManager.takePersistablePermission(uri)
                    Log.d("CourseTaskFragment", "Successfully took persistable permission for: $uri")
                } catch (e: SecurityException) {
                    Log.w("CourseTaskFragment", "Could not take persistable permission: ${e.message}")
                }

                // Always make a local copy to ensure we can access it later
                val localUri = withContext(Dispatchers.IO) {
                    copyUriToLocalStorage(uri, "video")
                }

                // Use the local URI if available, otherwise fall back to the original
                val bestUri = localUri ?: uri
                Log.d("CourseTaskFragment", "Using URI for video: $bestUri")

                // Add the content item view with the best URI
                addContentItemView(bestUri, "video")
            } catch (e: Exception) {
                Log.e("CourseTaskFragment", "Error processing video", e)
                Toast.makeText(context, "Error al procesar el video", Toast.LENGTH_SHORT).show()
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

    private fun addContentItemView(uri: Uri, type: String, name: String? = null, contentId: Long? = null) {
        val inflater = LayoutInflater.from(context)
        val contentView = inflater.inflate(R.layout.item_course_content, contentContainer, false)

        val iconView = contentView.findViewById<ImageView>(R.id.contentIconView)
        val nameView = contentView.findViewById<TextView>(R.id.contentNameView)
        val deleteButton = contentView.findViewById<ImageButton>(R.id.deleteContentButton)

        // Get a meaningful display name
        val displayName = name ?: getFileName(uri) ?: "Contenido ${contentContainer.childCount + 1}"
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
        }

        // Make the content item clickable to preview
        contentView.setOnClickListener {
            try {
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
            } catch (e: Exception) {
                Log.e("CourseTaskFragment", "Error opening content", e)
                Toast.makeText(context, "Error al abrir el contenido", Toast.LENGTH_SHORT).show()
            }
        }

        contentContainer.addView(contentView)
    }
}