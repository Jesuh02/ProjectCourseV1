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

        CoroutineScope(Dispatchers.Main).launch {
            existingTask = withContext(Dispatchers.IO) { taskDao.getTaskById(taskId) }
            if (existingTask == null) {
                Log.e("CourseTaskFragment", "Error: Task with ID $taskId not found.")
                Toast.makeText(context, "Error al cargar la tarea", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
                return@launch
            }

            // Populate UI with existing task data
            taskNameEditText.setText(existingTask!!.name)
            taskDescriptionEditText.setText(existingTask!!.description ?: "")
            topicId = existingTask!!.topicId // Ensure topicId is set from the loaded task

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

        CoroutineScope(Dispatchers.Main).launch {
            try {
                var savedTopicId = topicId
                // If topicId is not set, create a new topic
                if (savedTopicId == -1L) {
                    // Check if there's already a topic for this course with the given number
                    val existingTopic = withContext(Dispatchers.IO) {
                        topicDao.getTopicByCourseIdAndOrderIndex(courseId, topicNumber)
                    }

                    if (existingTopic != null) {
                        savedTopicId = existingTopic.id
                        Log.d("CourseTaskFragment", "Using existing topic with ID: $savedTopicId")
                    } else {
                        // Create a new topic if none exists
                        val newTopic = com.example.tareamov.data.entity.Topic(
                            courseId = courseId,
                            name = "Tema $topicNumber",
                            description = "",
                            orderIndex = topicNumber
                        )
                        savedTopicId = withContext(Dispatchers.IO) { topicDao.insertTopic(newTopic) }
                        Log.d("CourseTaskFragment", "Created new topic with ID: $savedTopicId")
                    }
                }

                var savedTaskId = taskId
                if (existingTask == null) { // Creating a new task
                    val taskCount = withContext(Dispatchers.IO) { taskDao.getTaskCountByTopicId(savedTopicId) }
                    val newTask = Task(
                        topicId = savedTopicId,
                        name = taskName,
                        description = taskDescription.ifBlank { null },
                        orderIndex = taskCount // Append to the end
                    )
                    savedTaskId = withContext(Dispatchers.IO) { taskDao.insertTask(newTask) }
                    Log.d("CourseTaskFragment", "New task created with ID: $savedTaskId")
                } else { // Updating existing task
                    val updatedTask = existingTask!!.copy(
                        name = taskName,
                        description = taskDescription.ifBlank { null }
                    )
                    withContext(Dispatchers.IO) { taskDao.updateTask(updatedTask) }
                    Log.d("CourseTaskFragment", "Task updated with ID: $savedTaskId")
                    withContext(Dispatchers.IO) {
                        val contentItems = contentItemDao.getContentItemsByTaskId(savedTaskId)
                        for (item in contentItems) {
                            contentItemDao.deleteContentItem(item.id)
                        }
                    }
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

                // Navigate to CourseDetailFragment instead of going back
                val bundle = Bundle().apply {
                    putLong("courseId", courseId)
                }
                findNavController().navigate(R.id.action_courseTaskFragment_to_courseDetailFragment, bundle)
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