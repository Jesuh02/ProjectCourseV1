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
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.tareamov.data.entity.Task // Import Task entity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import com.example.tareamov.util.UriPermissionManager

class CourseTopicFragment : Fragment() {

    private var topicNumber = 0

    // Replace the request codes with ActivityResultLaunchers
    private lateinit var videoPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var documentPickerLauncher: ActivityResultLauncher<Intent>

    // Add this method at the class level, not inside another function
    private suspend fun ensureValidCourseId(videoDao: com.example.tareamov.data.dao.VideoDao, courseId: Long): Long {
        // First check if the provided courseId exists locally
        val courseExists = videoDao.videoExistsById(courseId)

        if (courseExists) {
            return courseId
        }

        // If not found locally, return -1L so callers can decide fallback behavior
        Log.w("CourseTopicFragment", "Course id $courseId not found locally")
        return -1L
    }

    private suspend fun ensurePlaceholderVideoExists(videoDao: com.example.tareamov.data.dao.VideoDao, validCourseId: Long, courseName: String) {
        try {
            val exists = videoDao.videoExistsById(validCourseId)
            if (!exists) {
                var placeholderTitle = courseName
                if (placeholderTitle.isBlank()) {
                    placeholderTitle = "Curso $validCourseId"
                }
                val placeholder = com.example.tareamov.data.entity.VideoData(
                    id = validCourseId,
                    username = "remote_owner",
                    description = "", // neutral description instead of verbose placeholder text
                    title = placeholderTitle,
                    videoUriString = null,
                    timestamp = System.currentTimeMillis(),
                    localFilePath = null
                )
                try {
                    videoDao.insertVideo(placeholder)
                    Log.d("CourseTopicFragment", "Inserted placeholder VideoData for courseId: $validCourseId")
                } catch (ie: Exception) {
                    Log.w("CourseTopicFragment", "Failed to insert placeholder video for courseId: $validCourseId", ie)
                }
            }
        } catch (e: Exception) {
            Log.w("CourseTopicFragment", "Error checking/inserting placeholder video", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize the ActivityResultLaunchers
        videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val selectedVideoUri = result.data?.data
                if (selectedVideoUri != null) {
                    addContentToList(selectedVideoUri, "video")
                }
            }
        }

        documentPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val selectedDocumentUri = result.data?.data
                if (selectedDocumentUri != null) {
                    // Take persistable URI permission for the document
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(selectedDocumentUri, takeFlags)

                    addContentToList(selectedDocumentUri, "document")
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_course_topic, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Get topic number from arguments
        arguments?.let {
            topicNumber = it.getInt("topicNumber", 0)
            val topicTitle = view.findViewById<TextView>(R.id.topicTitleTextView)
            topicTitle.text = "Editar Tema $topicNumber"
        }

        // Set up back button
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Get topicId from arguments (default to -1L if not present)
    val topicId = arguments?.getLong("topicId", -1L) ?: -1L
    val courseId = arguments?.getLong("courseId", -1L) ?: -1L
    val courseName = arguments?.getString("courseName") ?: ""
    val argTopicNumber = arguments?.getInt("topicNumber", 0) ?: 0

        val addTaskButton = view.findViewById<LinearLayout>(R.id.addTaskButton)
        addTaskButton.setOnClickListener {
            // Check if we have a valid topicId
            if (topicId != -1L) {
                // If we have a valid topicId, navigate directly to task creation
                val bundle = Bundle().apply {
                    putLong("topicId", topicId)
                    putLong("courseId", courseId)
                    putString("courseName", courseName)
                    putInt("topicNumber", topicNumber)
                    putLong("taskId", -1L) // Nueva tarea
                }
                findNavController().navigate(R.id.action_courseTopicFragment_to_courseTaskFragment, bundle)
            } else {
                // If we don't have a valid topicId, we need to save the topic first
                // Get the topic name and description
                val topicName = view.findViewById<EditText>(R.id.topicNameEditText)?.text.toString()

                if (topicName.isBlank()) {
                    Toast.makeText(context, "Por favor ingresa un nombre para el tema antes de agregar tareas", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Save the topic and then navigate
                saveTopicAndNavigateToTask()
            }
        }

        // Set up save button
        val saveButton = view.findViewById<Button>(R.id.saveTopicButton)
        saveButton.setOnClickListener {
            saveTopic()
        }

        // Set up add video button
        val addVideoButton = view.findViewById<LinearLayout>(R.id.addVideoButton)
        addVideoButton.setOnClickListener {
            openGalleryForVideo()
        }

        // Set up add document button
        val addDocumentButton = view.findViewById<LinearLayout>(R.id.addDocumentButton)
        addDocumentButton.setOnClickListener {
            openDocumentPicker()
        }
    }

    // Update this function to actually save the topic and navigate
    private fun saveTopicAndNavigateToTask() {
        val topicName = view?.findViewById<EditText>(R.id.topicNameEditText)?.text.toString()
        val topicDescription = view?.findViewById<EditText>(R.id.topicDescriptionEditText)?.text.toString()

        if (topicName.isBlank()) {
            Toast.makeText(context, "Por favor ingresa un nombre para el tema", Toast.LENGTH_SHORT).show()
            return
        }

        // Get courseId from arguments
        val courseId = arguments?.getLong("courseId", -1L) ?: -1L
        val courseName = arguments?.getString("courseName") ?: ""
        val topicNumber = arguments?.getInt("topicNumber", 0) ?: 0

        Log.d("CourseTopicFragment", "Saving topic for courseId: $courseId before adding task")

        // Save topic to database
        val appDatabase = com.example.tareamov.data.AppDatabase.getDatabase(requireContext())
        val videoDao = appDatabase.videoDao()
        val topicDao = appDatabase.topicDao()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Resolve authoritative course id from Supabase first (prefer by id, then by name)
                val syncRepo = (activity as? com.example.tareamov.MainActivity)?.syncRepository
                    ?: com.example.tareamov.data.sync.SyncRepository(
                        appDatabase.usuarioDao(), appDatabase.personaDao(), appDatabase.topicDao(),
                        appDatabase.contentItemDao(), appDatabase.taskDao(), appDatabase.subscriptionDao(),
                        appDatabase.taskSubmissionDao(), appDatabase.videoDao(), appDatabase.courseDao(),
                        appDatabase.rolDao(), appDatabase.recursoDao(), appDatabase.rolRecursoDao(),
                        appDatabase.chatMessageDao(), appDatabase.fileContextDao()
                    )

                val validCourseId = withContext(Dispatchers.IO) {
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
                        Log.w("CourseTopicFragment", "Error resolving remote course id for add-task path", e)
                    }
                    // fallback to local check
                    ensureValidCourseId(videoDao, courseId)
                }

                Log.d("CourseTopicFragment", "Using validated courseId: $validCourseId")

                // Prefer creating the Topic remotely and avoid creating local Topic/Video rows
                // that can cause foreign key violations. Attempt to create the topic in Supabase
                // and use the returned remote id for navigation to the task editor.
                var remoteTopicId: Long? = null
                try {
                    remoteTopicId = withContext(Dispatchers.IO) {
                        val sr = (activity as? com.example.tareamov.MainActivity)?.syncRepository
                            ?: com.example.tareamov.data.sync.SyncRepository(
                                appDatabase.usuarioDao(), appDatabase.personaDao(), appDatabase.topicDao(),
                                appDatabase.contentItemDao(), appDatabase.taskDao(), appDatabase.subscriptionDao(),
                                appDatabase.taskSubmissionDao(), appDatabase.videoDao(), appDatabase.courseDao(),
                                appDatabase.rolDao(), appDatabase.recursoDao(), appDatabase.rolRecursoDao(),
                                appDatabase.chatMessageDao(), appDatabase.fileContextDao()
                            )

                        val topicToPush = com.example.tareamov.data.entity.Topic(
                            id = 0,
                            courseId = validCourseId,
                            name = topicName,
                            description = topicDescription,
                            orderIndex = topicNumber
                        )

                        sr.insertTopicRemote(topicToPush)
                    }
                } catch (e: Exception) {
                    Log.w("CourseTopicFragment", "Failed to create topic remotely; falling back to local", e)
                }

                // If remote creation succeeded, navigate using the remote id. Otherwise
                // fall back to creating a local topic (best-effort) and use that id.
                if (remoteTopicId != null) {
                    // Notify previous fragment that a topic was created so it can refresh
                    val prev = findNavController().previousBackStackEntry
                    prev?.savedStateHandle?.set("topic_created", remoteTopicId)

                    val bundle = Bundle().apply {
                        putLong("topicId", remoteTopicId)
                        putLong("courseId", validCourseId)
                        putString("courseName", courseName)
                        putInt("topicNumber", topicNumber)
                        putLong("taskId", -1L)
                    }
                    findNavController().navigate(R.id.action_courseTopicFragment_to_courseTaskFragment, bundle)
                } else {
                    // Fallback: create local topic to preserve UX but may cause FK issues
                    val localTopicId = withContext(Dispatchers.IO) {
                        val topic = com.example.tareamov.data.entity.Topic(
                            courseId = validCourseId,
                            name = topicName,
                            description = topicDescription,
                            orderIndex = topicNumber
                        )
                        topicDao.insertTopic(topic)
                    }

                    // Notify previous fragment that a topic was created locally so it can refresh
                    val prev = findNavController().previousBackStackEntry
                    prev?.savedStateHandle?.set("topic_created", localTopicId)

                    val bundle = Bundle().apply {
                        putLong("topicId", localTopicId)
                        putLong("courseId", validCourseId)
                        putString("courseName", courseName)
                        putInt("topicNumber", topicNumber)
                        putLong("taskId", -1L)
                    }
                    findNavController().navigate(R.id.action_courseTopicFragment_to_courseTaskFragment, bundle)
                }

            } catch (e: Exception) {
                Log.e("CourseTopicFragment", "Error al guardar el tema para crear tarea", e)
                Toast.makeText(context, "Error al guardar el tema: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openGalleryForVideo() {
        try {
            // First try to use the system gallery picker which is more likely to work with local files
            val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            galleryIntent.type = "video/*"

            // If the gallery picker isn't available, fall back to ACTION_OPEN_DOCUMENT
            if (galleryIntent.resolveActivity(requireActivity().packageManager) != null) {
                videoPickerLauncher.launch(galleryIntent)
            } else {
                // Fall back to ACTION_OPEN_DOCUMENT
                val documentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                documentIntent.addCategory(Intent.CATEGORY_OPENABLE)
                documentIntent.type = "video/*"
                documentIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                documentIntent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                videoPickerLauncher.launch(documentIntent)
            }
        } catch (e: Exception) {
            Log.e("CourseTopicFragment", "Error opening video picker", e)
            Toast.makeText(context, "Error al abrir el selector de videos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openDocumentPicker() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            intent.addCategory(Intent.CATEGORY_OPENABLE)
            intent.type = "*/*"
            val mimeTypes = arrayOf(
                "application/msword",                     // .doc
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",  // .docx
                "application/pdf",                        // .pdf
                "text/plain"                              // .txt
            )
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            documentPickerLauncher.launch(intent) // Use the launcher instead of startActivityForResult
        } catch (e: Exception) {
            Log.e("CourseTopicFragment", "Error opening document picker", e)
            Toast.makeText(context, "Error al abrir el selector de documentos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addContentToList(contentUri: Uri, contentType: String) {
        try {
            // For videos, we'll make a local copy to ensure persistence
            var finalUri = contentUri
            if (contentType == "video") {
                finalUri = saveVideoLocally(contentUri) ?: contentUri
            }

            // Take persistable URI permission for the content if it's a content URI
            if (finalUri.scheme == "content") {
                try {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(finalUri, takeFlags)
                    Log.d("CourseTopicFragment", "Took persistable permission for URI: $finalUri")
                } catch (e: Exception) {
                    Log.e("CourseTopicFragment", "Failed to take persistable permission: ${e.message}", e)
                    // Continue anyway as we'll try to handle this when opening the content
                }
            }

            val contentContainer = view?.findViewById<LinearLayout>(R.id.contentContainer)
            if (contentContainer != null) {
                val inflater = LayoutInflater.from(context)
                val contentView = inflater.inflate(R.layout.item_course_content, contentContainer, false)

                // Set content name based on URI
                val contentName = getContentName(finalUri)
                val contentNameView = contentView.findViewById<TextView>(R.id.contentNameView)
                contentNameView.text = contentName

                // Set appropriate icon
                val contentIconView = contentView.findViewById<ImageView>(R.id.contentIconView)
                if (contentType == "video") {
                    contentIconView.setImageResource(android.R.drawable.ic_media_play)
                } else {
                    contentIconView.setImageResource(android.R.drawable.ic_menu_edit)
                }

                // Set up delete button
                val deleteButton = contentView.findViewById<ImageButton>(R.id.deleteContentButton)
                deleteButton.setOnClickListener {
                    contentContainer.removeView(contentView)
                }

                // Store URI and content type as tags
                contentView.tag = finalUri
                contentView.setTag(R.id.content_type_tag, contentType)

                // Add to container
                contentContainer.addView(contentView)

                // Log for debugging
                Log.d("CourseTopicFragment", "Added content: $contentName, type: $contentType, uri: $finalUri")
            }
        } catch (e: Exception) {
            // Add error handling for the entire method
            Log.e("CourseTopicFragment", "Error adding content to list", e)
            Toast.makeText(context, "Error al agregar contenido: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Saves a video from a content URI to a local file in the app's private storage
     * This ensures the video remains accessible even if the original URI becomes invalid
     */
    private fun saveVideoLocally(videoUri: Uri): Uri? {
        try {
            val context = requireContext()
            val contentResolver = context.contentResolver

            // Create a directory for videos if it doesn't exist
            val videoDir = File(context.filesDir, "videos")
            if (!videoDir.exists()) {
                videoDir.mkdirs()
            }

            // Create a unique filename for the video
            val filename = "video_${UUID.randomUUID()}.mp4"
            val destFile = File(videoDir, filename)

            // Copy the content from the URI to our local file
            contentResolver.openInputStream(videoUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(4 * 1024) // 4k buffer
                    var read: Int = 0
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }

            Log.d("CourseTopicFragment", "Saved video to local file: ${destFile.absolutePath}")

            // Return a Uri for the local file
            return Uri.fromFile(destFile)
        } catch (e: Exception) {
            Log.e("CourseTopicFragment", "Error saving video locally", e)
            return null
        }
    }

    private fun getContentName(uri: Uri): String {
        val context = requireContext()
        var displayName = "Contenido"

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        displayName = cursor.getString(nameIndex)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CourseTopicFragment", "Error getting content name", e)
            // If we can't get the name, use the last path segment
            uri.lastPathSegment?.let {
                displayName = it
            }
        }

        return displayName
    }

    // Add this method to fix the unresolved reference error
    // In the saveTopic method, update the navigation after saving
    private fun saveTopic() {
        val topicName = view?.findViewById<EditText>(R.id.topicNameEditText)?.text.toString()
        val topicDescription = view?.findViewById<EditText>(R.id.topicDescriptionEditText)?.text.toString()

        if (topicName.isBlank()) {
            Toast.makeText(context, "Por favor ingresa un nombre para el tema", Toast.LENGTH_SHORT).show()
            return
        }

    // Get courseId and courseName from arguments
    val courseId = arguments?.getLong("courseId", -1L) ?: -1L
    val topicId = arguments?.getLong("topicId", -1L) ?: -1L
    val courseName = arguments?.getString("courseName") ?: ""

        Log.d("CourseTopicFragment", "Saving topic with initial courseId: $courseId")

        // Save topic to database
        val appDatabase = com.example.tareamov.data.AppDatabase.getDatabase(requireContext())
        val videoDao = appDatabase.videoDao()
        val topicDao = appDatabase.topicDao()
        val contentItemDao = appDatabase.contentItemDao()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Resolve authoritative course id from Supabase. Prefer remote by id, then by name.
                val syncRepo = (activity as? com.example.tareamov.MainActivity)?.syncRepository
                    ?: com.example.tareamov.data.sync.SyncRepository(
                        appDatabase.usuarioDao(), appDatabase.personaDao(), appDatabase.topicDao(),
                        appDatabase.contentItemDao(), appDatabase.taskDao(), appDatabase.subscriptionDao(),
                        appDatabase.taskSubmissionDao(), appDatabase.videoDao(), appDatabase.courseDao(),
                        appDatabase.rolDao(), appDatabase.recursoDao(), appDatabase.rolRecursoDao(),
                        appDatabase.chatMessageDao(), appDatabase.fileContextDao()
                    )

                val validCourseId = withContext(Dispatchers.IO) {
                    try {
                        if (courseId > 0) {
                            val remoteCourse = syncRepo.fetchCourseById(courseId)
                            if (remoteCourse != null) return@withContext remoteCourse.id
                        }

                        // If not found by id, try by courseName
                        if (courseName.isNotBlank()) {
                            val all = syncRepo.fetchCoursesFromSupabase()
                            val byName = all.firstOrNull { it.title?.trim()?.equals(courseName.trim(), ignoreCase = true) == true }
                            if (byName != null) return@withContext byName.id
                        }

                        // Fallback: try to ensure a local course id (existing app behavior)
                        ensureValidCourseId(videoDao, courseId)
                    } catch (e: Exception) {
                        Log.w("CourseTopicFragment", "Error resolving remote course id, falling back", e)
                        ensureValidCourseId(videoDao, courseId)
                    }
                }

                Log.d("CourseTopicFragment", "Using validated courseId: $validCourseId")

                // Ensure local parent exists to satisfy Room FK
                ensurePlaceholderVideoExists(videoDao, validCourseId, courseName)

                // Create or update Topic entity with the validated courseId from Supabase/local fallback
                val topic = com.example.tareamov.data.entity.Topic(
                    id = if (topicId > 0) topicId else 0,
                    courseId = validCourseId,
                    name = topicName,
                    description = topicDescription,
                    orderIndex = this@CourseTopicFragment.topicNumber
                )

                // Insert or update topic
                val savedTopicId = withContext(Dispatchers.IO) {
                    if (topicId > 0) {
                        // Update existing topic
                        topicDao.updateTopic(topic)
                        topicId
                    } else {
                        // Insert new topic
                        topicDao.insertTopic(topic)
                    }
                }

                // Save content items
                val contentContainer = view?.findViewById<LinearLayout>(R.id.contentContainer)
                if (contentContainer != null) {
                    // First, delete existing content items for this topic
                    if (topicId > 0) {
                        withContext(Dispatchers.IO) {
                            contentItemDao.deleteContentItemsByTopicId(topicId)
                        }
                    }

                    // Then add new content items
                    for (i in 0 until contentContainer.childCount) {
                        val contentView = contentContainer.getChildAt(i)
                        val contentUri = contentView.tag as? Uri
                        val contentType = contentView.getTag(R.id.content_type_tag) as? String
                        val contentName = contentView.findViewById<TextView>(R.id.contentNameView)?.text.toString()

                        if (contentUri != null && contentType != null) {
                            val contentItem = com.example.tareamov.data.entity.ContentItem(
                                id = 0, // New content item
                                topicId = savedTopicId,
                                taskId = null, // Not associated with a task
                                name = contentName,
                                uriString = contentUri.toString(),
                                contentType = contentType,
                                orderIndex = i
                            )

                            withContext(Dispatchers.IO) {
                                contentItemDao.insertContentItem(contentItem)
                            }
                        }
                    }
                }

                // Show success message and navigate back
                Toast.makeText(context, "Tema guardado correctamente", Toast.LENGTH_SHORT).show()

                // Fire-and-forget: push topic to Supabase to keep remote authoritative
                try {
                    val syncRepo = (activity as? com.example.tareamov.MainActivity)?.syncRepository
                        ?: com.example.tareamov.data.sync.SyncRepository(
                            appDatabase.usuarioDao(), appDatabase.personaDao(), appDatabase.topicDao(),
                            appDatabase.contentItemDao(), appDatabase.taskDao(), appDatabase.subscriptionDao(),
                            appDatabase.taskSubmissionDao(), appDatabase.videoDao(), appDatabase.courseDao(),
                            appDatabase.rolDao(), appDatabase.recursoDao(), appDatabase.rolRecursoDao(),
                            appDatabase.chatMessageDao(), appDatabase.fileContextDao()
                        )

                    val topicToPush = com.example.tareamov.data.entity.Topic(
                        id = savedTopicId,
                        courseId = validCourseId,
                        name = topicName,
                        description = topicDescription,
                        orderIndex = this@CourseTopicFragment.topicNumber
                    )

                    CoroutineScope(Dispatchers.IO).launch {
                        val remoteId = syncRepo.insertTopicRemote(topicToPush)
                        if (remoteId != null) {
                            android.util.Log.i("CourseTopicFragment", "Pushed topic remote id=$remoteId for local id=$savedTopicId")
                        } else {
                            android.util.Log.w("CourseTopicFragment", "Failed to push topic for local id=$savedTopicId")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CourseTopicFragment", "Failed to push topic to Supabase", e)
                }

                // Return to existing CourseDetailFragment on the back stack so it resumes and reloads data
                // If the CourseDetailFragment is not on the back stack, navigate with the courseId bundle as fallback
                try {
                    val nav = findNavController()
                    val popped = nav.popBackStack(R.id.courseDetailFragment, false)
                    if (!popped) {
                        val bundle = Bundle().apply { putLong("courseId", validCourseId) }
                        nav.navigate(R.id.courseDetailFragment, bundle)
                    }
                } catch (navEx: Exception) {
                    // Fallback to simple navigate if popBackStack fails
                    val bundle = Bundle().apply { putLong("courseId", validCourseId) }
                    findNavController().navigate(R.id.action_courseTopicFragment_to_courseDetailFragment, bundle)
                }

            } catch (e: Exception) {
                Log.e("CourseTopicFragment", "Error al guardar el tema", e)
                Toast.makeText(context, "Error al guardar el tema: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}