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
import com.example.tareamov.data.AppDatabase
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
    private suspend fun ensureValidCourseId(courseId: Long): Long {
        // Check if the provided courseId exists in Supabase
        return try {
            val activity = requireActivity()
            if (activity is com.example.tareamov.MainActivity) {
                val course = withContext(Dispatchers.IO) {
                    activity.syncRepository.fetchCourseById(courseId)
                }
                if (course != null) {
                    Log.d("CourseTopicFragment", "Course id $courseId found in Supabase")
                    courseId
                } else {
                    Log.w("CourseTopicFragment", "Course id $courseId not found in Supabase")
                    -1L
                }
            } else {
                Log.w("CourseTopicFragment", "Invalid activity context")
                -1L
            }
        } catch (e: Exception) {
            Log.e("CourseTopicFragment", "Error checking course in Supabase", e)
            -1L
        }
    }

    private suspend fun ensurePlaceholderVideoExists(validCourseId: Long, courseName: String) {
        // Not needed anymore - we're using Supabase directly
        // Courses are already in Supabase when we reach this fragment
        Log.d("CourseTopicFragment", "Course $validCourseId ($courseName) exists in Supabase")
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

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val activity = requireActivity()
                if (activity !is com.example.tareamov.MainActivity) {
                    Toast.makeText(context, "Error: Contexto inválido", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Validate course exists in Supabase
                val validCourseId = withContext(Dispatchers.IO) {
                    val course = activity.syncRepository.fetchCourseById(courseId)
                    if (course != null) {
                        course.id
                    } else {
                        Log.e("CourseTopicFragment", "Course $courseId not found in Supabase")
                        -1L
                    }
                }

                if (validCourseId <= 0) {
                    Toast.makeText(context, "Error: Curso no encontrado", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                Log.d("CourseTopicFragment", "Using validated courseId: $validCourseId")

                // Create topic directly in Supabase
                val remoteTopicId = withContext(Dispatchers.IO) {
                    val topicToPush = com.example.tareamov.data.entity.Topic(
                        id = 0,
                        courseId = validCourseId,
                        name = topicName,
                        description = topicDescription,
                        orderIndex = topicNumber
                    )
                    // Use the regular insert method since we already have the correct courseId
                    activity.syncRepository.insertTopicRemote(topicToPush)
                }

                if (remoteTopicId != null && remoteTopicId > 0) {
                    Log.d("CourseTopicFragment", "Topic created in Supabase with ID: $remoteTopicId")
                    
                    // Notify previous fragment that a topic was created
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
                    Toast.makeText(context, "Error al crear el tema en Supabase", Toast.LENGTH_SHORT).show()
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

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val activity = requireActivity()
                if (activity !is com.example.tareamov.MainActivity) {
                    Toast.makeText(context, "Error: Contexto inválido", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Validate course exists in Supabase
                val validCourseId = withContext(Dispatchers.IO) {
                    val course = activity.syncRepository.fetchCourseById(courseId)
                    if (course != null) {
                        course.id
                    } else {
                        Log.e("CourseTopicFragment", "Course $courseId not found in Supabase")
                        -1L
                    }
                }

                if (validCourseId <= 0) {
                    Toast.makeText(context, "Error: Curso no encontrado", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                Log.d("CourseTopicFragment", "Using validated courseId: $validCourseId")

                // Create or update topic in Supabase
                val topicToSave = com.example.tareamov.data.entity.Topic(
                    id = if (topicId > 0) topicId else 0,
                    courseId = validCourseId,
                    name = topicName,
                    description = topicDescription,
                    orderIndex = this@CourseTopicFragment.topicNumber
                )

                val savedTopicId = withContext(Dispatchers.IO) {
                    if (topicId > 0) {
                        // Update would require a SupabaseClient.updateTopic method
                        // For now, we'll just use the existing ID
                        Log.d("CourseTopicFragment", "Updating topic $topicId (update not yet implemented)")
                        topicId
                    } else {
                        // Insert new topic to Supabase using regular insert (we already have correct courseId)
                        activity.syncRepository.insertTopicRemote(topicToSave)
                    }
                }

                if (savedTopicId == null || savedTopicId <= 0) {
                    Toast.makeText(context, "Error al guardar el tema en Supabase", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                    // Ensure local DB stores the topic with the remote id
                    try {
                        val db = AppDatabase.getDatabase(requireContext())
                        val localTopic = com.example.tareamov.data.entity.Topic(
                            id = savedTopicId,
                            courseId = validCourseId,
                            name = topicName,
                            description = topicDescription,
                            orderIndex = this@CourseTopicFragment.topicNumber
                        )
                        withContext(Dispatchers.IO) {
                            db.topicDao().insertTopic(localTopic)
                        }
                    } catch (e: Exception) {
                        Log.w("CourseTopicFragment", "Could not insert topic locally after remote save: $savedTopicId", e)
                    }

                // Save content items to Supabase
                val contentContainer = view?.findViewById<LinearLayout>(R.id.contentContainer)
                if (contentContainer != null) {
                    // Save new content items
                    for (i in 0 until contentContainer.childCount) {
                        val contentView = contentContainer.getChildAt(i)
                        val contentUri = contentView.tag as? Uri
                        val contentType = contentView.getTag(R.id.content_type_tag) as? String
                        val contentName = contentView.findViewById<TextView>(R.id.contentNameView)?.text.toString()

                        if (contentUri != null && contentType != null) {
                            val contentItem = com.example.tareamov.data.entity.ContentItem(
                                id = 0, // Supabase will auto-generate
                                topicId = savedTopicId,
                                taskId = null, // Not associated with a task
                                name = contentName,
                                uriString = contentUri.toString(),
                                contentType = contentType,
                                orderIndex = i
                            )

                            withContext(Dispatchers.IO) {
                                activity.syncRepository.insertContentItemRemote(contentItem)
                            }
                        }
                    }
                }

                // Show success message and navigate back
                Toast.makeText(context, "Tema guardado correctamente en Supabase", Toast.LENGTH_SHORT).show()

                // Notify CourseDetailFragment to refresh from Supabase and force reload
                findNavController().previousBackStackEntry?.savedStateHandle?.set("topic_created", savedTopicId)
                findNavController().previousBackStackEntry?.savedStateHandle?.set("refresh_from_supabase", true)
                findNavController().previousBackStackEntry?.savedStateHandle?.set("force_reload_topics", true)
                
                // Navigate back to CourseDetailFragment specifically
                findNavController().popBackStack(R.id.courseDetailFragment, false)

            } catch (e: Exception) {
                Log.e("CourseTopicFragment", "Error al guardar el tema", e)
                Toast.makeText(context, "Error al guardar el tema: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}