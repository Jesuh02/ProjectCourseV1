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
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import com.example.tareamov.data.entity.Task // Import Task entity
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.StorageHelper
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class CourseTopicFragment : Fragment() {

    private var topicNumber = 0

    // Replace the request codes with ActivityResultLaunchers
    private lateinit var videoPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var documentPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var imagePickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var cameraLauncher: ActivityResultLauncher<Intent>
    private var cameraImageUri: Uri? = null

    // Add this method at the class level, not inside another function
    private suspend fun ensureValidCourseId(courseId: Long): Long {
        return try {
            BackendApiService.initialize(requireContext())
            val result = withContext(Dispatchers.IO) {
                BackendApiService.getCourseById(courseId)
            }
            if (result is ApiResult.Success && result.data != null) {
                Log.d("CourseTopicFragment", "Course id $courseId found")
                courseId
            } else {
                Log.w("CourseTopicFragment", "Course id $courseId not found")
                -1L
            }
        } catch (e: Exception) {
            Log.e("CourseTopicFragment", "Error checking course", e)
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
                val uris = mutableListOf<Uri>()
                result.data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: result.data?.data?.let { uris.add(it) }
                uris.forEach { addContentToList(it, "video") }
            }
        }

        documentPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uris = mutableListOf<Uri>()
                result.data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: result.data?.data?.let { uris.add(it) }
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                uris.forEach { uri ->
                    try { requireContext().contentResolver.takePersistableUriPermission(uri, takeFlags) } catch (_: Exception) {}
                    addContentToList(uri, "document")
                }
            }
        }

        imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uris = mutableListOf<Uri>()
                result.data?.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
                } ?: result.data?.data?.let { uris.add(it) }
                uris.forEach { addContentToList(it, "image") }
            }
        }

        cameraLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = cameraImageUri
                if (uri != null) {
                    addContentToList(uri, "image")
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
    val isEditMode = arguments?.getBoolean("isEditMode", false) ?: false

        // If editing an existing topic, load its data
        if (topicId > 0 && isEditMode) {
            loadTopicData(topicId, view)
        }

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

        // Set up add image button
        val addImageButton = view.findViewById<LinearLayout>(R.id.addImageButton)
        addImageButton.setOnClickListener {
            openGalleryForImage()
        }

        // Set up take photo button
        val takePhotoButton = view.findViewById<LinearLayout>(R.id.takePhotoButton)
        takePhotoButton.setOnClickListener {
            openCameraForPhoto()
        }
    }

    /**
     * Load existing topic data from Supabase when editing
     */
    private fun loadTopicData(topicId: Long, view: View) {
        val topicNameEditText = view.findViewById<EditText>(R.id.topicNameEditText)
        val topicDescriptionEditText = view.findViewById<EditText>(R.id.topicDescriptionEditText)
        val topicTitleTextView = view.findViewById<TextView>(R.id.topicTitleTextView)
        val contentContainer = view.findViewById<LinearLayout>(R.id.contentContainer)
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendApiService.initialize(requireContext())
                
                // Fetch topic from backend
                val topicResult = withContext(Dispatchers.IO) {
                    BackendApiService.getTopicById(topicId)
                }
                val topic = if (topicResult is ApiResult.Success) topicResult.data else null
                
                if (topic != null) {
                    // Update UI with topic data
                    topicNameEditText?.setText(topic.name)
                    topicDescriptionEditText?.setText(topic.description)
                    topicTitleTextView?.text = "Editar: ${topic.name}"
                    topicNumber = topic.orderIndex
                    
                    Log.d("CourseTopicFragment", "✅ Loaded topic data: name='${topic.name}', description='${topic.description}'")
                    
                    // Load content items for this topic directly
                    val contentResult = withContext(Dispatchers.IO) {
                        BackendApiService.getContentItemsByTopic(topicId)
                    }
                    val contentItems = if (contentResult is ApiResult.Success) contentResult.data ?: emptyList() else emptyList()
                    
                    Log.d("CourseTopicFragment", "📄 Found ${contentItems.size} content items for topic $topicId")
                    
                    // Add existing content items to the container
                    for (contentItem in contentItems) {
                        addExistingContentToList(contentItem, contentContainer)
                    }
                } else {
                    Log.w("CourseTopicFragment", "Topic $topicId not found in Supabase")
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "No se pudo cargar el tema", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseTopicFragment", "Error loading topic data", e)
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Error al cargar datos del tema: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Add existing content item to the UI container (for edit mode)
     */
    private fun addExistingContentToList(contentItem: com.example.tareamov.data.entity.ContentItem, contentContainer: LinearLayout?) {
        if (contentContainer == null) return
        
        val inflater = LayoutInflater.from(context)
        val contentView = inflater.inflate(R.layout.item_course_content, contentContainer, false)
        
        val contentNameView = contentView.findViewById<TextView>(R.id.contentNameView)
        val contentIconView = contentView.findViewById<ImageView>(R.id.contentIconView)
        val deleteButton = contentView.findViewById<ImageButton>(R.id.deleteContentButton)
        
        // Show cloud emoji if it's an R2 URL
        val isR2Url = StorageHelper.isR2Url(contentItem.uriString)
        val displayName = if (isR2Url) "☁️ ${contentItem.name ?: "Archivo adjunto"}" else contentItem.name ?: "Archivo adjunto"
        contentNameView.text = displayName
        
        // Set appropriate icon based on content type
        when (contentItem.contentType.lowercase()) {
            "video" -> contentIconView.setImageResource(android.R.drawable.ic_media_play)
            "pdf", "document" -> contentIconView.setImageResource(android.R.drawable.ic_menu_edit)
            else -> contentIconView.setImageResource(android.R.drawable.ic_menu_help)
        }
        
        // Set up delete button
        deleteButton.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val backendDeleted = withContext(Dispatchers.IO) {
                    try {
                        when (val result = BackendApiService.deleteContentItem(contentItem.id)) {
                            is ApiResult.Success -> true
                            is ApiResult.Error -> {
                                Log.e("CourseTopicFragment", "❌ Failed deleting content item ${contentItem.id}: ${result.message}")
                                false
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CourseTopicFragment", "Error deleting content item", e)
                        false
                    }
                }

                if (!backendDeleted) {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "No se pudo eliminar el contenido en el servidor", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                contentContainer.removeView(contentView)
                Log.d("CourseTopicFragment", "✅ Deleted content item ${contentItem.id} from Supabase")

                if (isR2Url) {
                    withContext(Dispatchers.IO) {
                        try {
                            StorageHelper.deleteFile(contentItem.uriString)
                        } catch (e: Exception) {
                            Log.e("CourseTopicFragment", "Error deleting content file from R2", e)
                        }
                    }
                }
            }
        }
        
        // Store content item data as tags (for potential updates)
        contentView.tag = Uri.parse(contentItem.uriString)
        contentView.setTag(R.id.content_type_tag, contentItem.contentType)
        contentView.setTag(R.id.content_id_tag, contentItem.id) // Store ID for existing items
        contentView.setTag(R.id.content_name_tag, contentItem.name ?: "Archivo adjunto")
        
        contentContainer.addView(contentView)
        Log.d("CourseTopicFragment", "📄 Added existing content: ${contentItem.name}, type: ${contentItem.contentType}")
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
        val subjectId = arguments?.getLong("subjectId", -1L) ?: -1L
        val courseName = arguments?.getString("courseName") ?: ""
        val topicNumber = arguments?.getInt("topicNumber", 0) ?: 0

        Log.d("CourseTopicFragment", "Saving topic for subjectId: $subjectId before adding task")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                BackendApiService.initialize(requireContext())

                if (subjectId <= 0) {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Error: Materia no encontrada", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                Log.d("CourseTopicFragment", "Using subjectId: $subjectId")

                // Create topic via backend API
                val remoteTopicId = withContext(Dispatchers.IO) {
                    val topicData = Topic(
                        courseId = subjectId,
                        name = topicName,
                        description = topicDescription,
                        orderIndex = topicNumber
                    )
                    val result = BackendApiService.createTopic(topicData)
                    if (result is ApiResult.Success) {
                        result.data?.id
                    } else null
                }

                if (remoteTopicId != null && remoteTopicId > 0) {
                    Log.d("CourseTopicFragment", "Topic created in Supabase with ID: $remoteTopicId")
                    
                    try {
                        val detailEntry = findNavController().getBackStackEntry(R.id.courseDetailFragment)
                        detailEntry.savedStateHandle["topic_created"] = remoteTopicId
                    } catch (e: Exception) {
                        findNavController().previousBackStackEntry?.savedStateHandle?.set("topic_created", remoteTopicId)
                    }

                    val bundle = Bundle().apply {
                        putLong("topicId", remoteTopicId)
                        putLong("courseId", courseId)
                        putLong("subjectId", subjectId)
                        putString("courseName", courseName)
                        putInt("topicNumber", topicNumber)
                        putLong("taskId", -1L)
                    }
                    findNavController().navigate(R.id.action_courseTopicFragment_to_courseTaskFragment, bundle)
                } else {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Error al crear el tema en Supabase", Toast.LENGTH_SHORT).show()
                    }
                }

            } catch (e: Exception) {
                Log.e("CourseTopicFragment", "Error al guardar el tema para crear tarea", e)
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Error al guardar el tema: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openGalleryForImage() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            if (intent.resolveActivity(requireActivity().packageManager) != null) {
                imagePickerLauncher.launch(intent)
            } else {
                val fallback = Intent(Intent.ACTION_GET_CONTENT)
                fallback.type = "image/*"
                fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                imagePickerLauncher.launch(fallback)
            }
        } catch (e: Exception) {
            Log.e("CourseTopicFragment", "Error opening image picker", e)
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
            Log.e("CourseTopicFragment", "Error opening camera", e)
            Toast.makeText(context, "Error al abrir la cámara", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openGalleryForVideo() {
        try {
            // First try to use the system gallery picker which is more likely to work with local files
            val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            galleryIntent.type = "video/*"
            galleryIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)

            // If the gallery picker isn't available, fall back to ACTION_OPEN_DOCUMENT
            if (galleryIntent.resolveActivity(requireActivity().packageManager) != null) {
                videoPickerLauncher.launch(galleryIntent)
            } else {
                // Fall back to ACTION_OPEN_DOCUMENT
                val documentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                documentIntent.addCategory(Intent.CATEGORY_OPENABLE)
                documentIntent.type = "video/*"
                documentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
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
                "application/vnd.ms-excel",               // .xls
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
                "application/pdf",                        // .pdf
                "text/plain"                              // .txt
            )
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            documentPickerLauncher.launch(intent) // Use the launcher instead of startActivityForResult
        } catch (e: Exception) {
            Log.e("CourseTopicFragment", "Error opening document picker", e)
            Toast.makeText(context, "Error al abrir el selector de documentos", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addContentToList(contentUri: Uri, contentType: String) {
        // Subir a Cloudflare R2 en segundo plano usando lifecycleScope para evitar crashes
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                var finalUri = contentUri
                var r2Url: String? = null
                val contentName = getContentName(contentUri)
                
                // Subir a Cloudflare R2 si está configurado
                if (StorageHelper.isConfigured()) {
                    Log.d("CourseTopicFragment", "☁️ Uploading to backend: $contentUri")
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Subiendo archivo a la nube...", Toast.LENGTH_SHORT).show()
                    }
                    
                    val folder = when (contentType) {
                        "video" -> "videos"
                        "image" -> "images"
                        else -> "documents"
                    }
                    val result = withContext(Dispatchers.IO) {
                        StorageHelper.uploadFile(
                            context = requireContext(),
                            fileUri = contentUri,
                            folder = "topics/$folder",
                            onProgress = { progress ->
                                Log.d("CourseTopicFragment", "Upload progress: $progress%")
                            }
                        )
                    }
                    
                    when (result) {
                        is StorageHelper.UploadResult.Success -> {
                            r2Url = result.url
                            finalUri = Uri.parse(r2Url)
                            Log.d("CourseTopicFragment", "✅ Upload successful: $r2Url")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Archivo subido a la nube ✓", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is StorageHelper.UploadResult.Error -> {
                            Log.e("CourseTopicFragment", "❌ Upload failed: ${result.message}")
                            if (isAdded && context != null) {
                                Toast.makeText(requireContext(), "Error subiendo a nube, usando copia local", Toast.LENGTH_SHORT).show()
                            }
                            // Fallback: guardar localmente para no depender de permisos temporales
                            finalUri = copyContentLocally(contentUri, contentType) ?: contentUri
                        }
                    }
                } else {
                    Log.w("CourseTopicFragment", "⚠️ R2 not configured, saving locally")
                    // Guardar localmente si R2 no está configurado
                    finalUri = copyContentLocally(contentUri, contentType) ?: contentUri
                }

                // Take persistable URI permission for the content if it's a content URI
                if (finalUri.scheme == "content") {
                    try {
                        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        requireContext().contentResolver.takePersistableUriPermission(finalUri, takeFlags)
                        Log.d("CourseTopicFragment", "Took persistable permission for URI: $finalUri")
                    } catch (e: Exception) {
                        Log.e("CourseTopicFragment", "Failed to take persistable permission: ${e.message}", e)
                    }
                }

                val contentContainer = view?.findViewById<LinearLayout>(R.id.contentContainer)
                if (contentContainer != null) {
                    val inflater = LayoutInflater.from(context)
                    val contentView = inflater.inflate(R.layout.item_course_content, contentContainer, false)

                    val contentNameView = contentView.findViewById<TextView>(R.id.contentNameView)
                    contentNameView.text = if (r2Url != null) "☁️ $contentName" else contentName

                    // Set appropriate icon
                    val contentIconView = contentView.findViewById<ImageView>(R.id.contentIconView)
                    when (contentType) {
                        "video" -> contentIconView.setImageResource(android.R.drawable.ic_media_play)
                        "image" -> contentIconView.setImageResource(android.R.drawable.ic_menu_gallery)
                        else -> contentIconView.setImageResource(android.R.drawable.ic_menu_edit)
                    }

                    // Set up delete button
                    val deleteButton = contentView.findViewById<ImageButton>(R.id.deleteContentButton)
                    deleteButton.setOnClickListener {
                        contentContainer.removeView(contentView)
                        // Si es URL de R2, podríamos eliminar del servidor (opcional)
                        if (r2Url != null) {
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                                StorageHelper.deleteFile(r2Url!!)
                            }
                        }
                    }

                    // Store URI and content type as tags
                    contentView.tag = finalUri
                    contentView.setTag(R.id.content_type_tag, contentType)
                    contentView.setTag(R.id.content_name_tag, contentName)

                    // Add to container
                    contentContainer.addView(contentView)

                    Log.d("CourseTopicFragment", "Added content: $contentName, type: $contentType, uri: $finalUri, r2Url: $r2Url")
                }
            } catch (e: Exception) {
                Log.e("CourseTopicFragment", "Error adding content to list", e)
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Error al agregar contenido: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
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

    private fun copyContentLocally(contentUri: Uri, contentType: String): Uri? {
        return if (contentType == "video") {
            saveVideoLocally(contentUri)
        } else {
            copyDocumentLocally(contentUri)
        }
    }

    private fun copyDocumentLocally(documentUri: Uri): Uri? {
        return try {
            val context = requireContext()
            val documentDir = File(context.filesDir, "documents")
            if (!documentDir.exists()) {
                documentDir.mkdirs()
            }

            val fileName = getContentName(documentUri)
                .ifBlank { "documento_${UUID.randomUUID()}" }
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val destFile = File(documentDir, fileName)

            context.contentResolver.openInputStream(documentUri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }

            Uri.fromFile(destFile)
        } catch (e: Exception) {
            Log.e("CourseTopicFragment", "Error saving document locally", e)
            null
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
        val subjectId = arguments?.getLong("subjectId", -1L) ?: -1L
        val courseName = arguments?.getString("courseName") ?: ""

        Log.d("CourseTopicFragment", "Saving topic with subjectId: $subjectId")

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val activity = requireActivity()
                BackendApiService.initialize(requireContext())

                if (subjectId <= 0) {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Error: Materia no encontrada", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                Log.d("CourseTopicFragment", "Using subjectId: $subjectId")

                // Create or update topic via backend API
                val savedTopicId = withContext(Dispatchers.IO) {
                    if (topicId > 0) {
                        // Update existing topic
                        val updates = mapOf(
                            "name" to topicName,
                            "description" to topicDescription,
                            "orderIndex" to this@CourseTopicFragment.topicNumber
                        )
                        val result = BackendApiService.updateTopic(topicId, updates)
                        if (result is ApiResult.Success) {
                            Log.d("CourseTopicFragment", "✅ Topic $topicId updated successfully")
                            topicId
                        } else {
                            val errorMsg = if (result is ApiResult.Error) "${result.message} (Code: ${result.code})" else "Unknown error"
                            Log.e("CourseTopicFragment", "❌ Failed to update topic $topicId: $errorMsg")
                            -1L
                        }
                    } else {
                        // Insert new topic
                        val topicData = Topic(
                            courseId = subjectId,
                            name = topicName,
                            description = topicDescription,
                            orderIndex = this@CourseTopicFragment.topicNumber
                        )
                        val result = BackendApiService.createTopic(topicData)
                        if (result is ApiResult.Success) {
                            result.data?.id ?: -1L
                        } else -1L
                    }
                }

                if (savedTopicId == null || savedTopicId <= 0) {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "Error al guardar el tema en Supabase", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // Save content items via backend API
                val contentContainer = view?.findViewById<LinearLayout>(R.id.contentContainer)
                if (contentContainer != null) {
                    val itemCount = contentContainer.childCount
                    Log.d("CourseTopicFragment", "📤 Preparing to save $itemCount content items for topicId=$savedTopicId (subjectId=$subjectId)")
                    
                    if (itemCount == 0) {
                        Log.w("CourseTopicFragment", "⚠️ No content items in container to save!")
                    }
                    
                    // Save new content items (skip existing ones that have an ID)
                    for (i in 0 until itemCount) {
                        val contentView = contentContainer.getChildAt(i)
                        val contentUri = contentView.tag as? Uri
                        val contentType = contentView.getTag(R.id.content_type_tag) as? String
                        val existingContentId = contentView.getTag(R.id.content_id_tag) as? Long
                        val contentName = (contentView.getTag(R.id.content_name_tag) as? String)
                            ?.removePrefix("☁️ ")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: contentView.findViewById<TextView>(R.id.contentNameView)?.text.toString()
                                .removePrefix("☁️ ")
                                .trim()

                        Log.d("CourseTopicFragment", "📦 Processing content item $i: uri=$contentUri, type=$contentType, name=$contentName, existingId=$existingContentId")

                        // Skip items that already exist in Supabase (have an ID)
                        if (existingContentId != null && existingContentId > 0) {
                            Log.d("CourseTopicFragment", "⏭️ Skipping existing content item with id=$existingContentId")
                            continue
                        }

                        if (contentUri != null && contentType != null) {
                            Log.d("CourseTopicFragment", "📦 Saving NEW ContentItem: topicId=$savedTopicId, name='$contentName', type='$contentType', uri='${contentUri}'")
                            
                            val savedId = withContext(Dispatchers.IO) {
                                val ciData = ContentItem(
                                    topicId = savedTopicId,
                                    taskId = null,
                                    name = contentName,
                                    uriString = contentUri.toString(),
                                    fileUri = contentUri.toString(),
                                    fileName = contentName,
                                    contentType = contentType,
                                    orderIndex = i
                                )
                                val result = BackendApiService.createContentItem(ciData)
                                if (result is ApiResult.Success) {
                                    result.data?.id
                                } else {
                                    if (result is ApiResult.Error) {
                                        Log.e("CourseTopicFragment", "❌ createContentItem failed: ${result.message} (code=${result.code})")
                                    }
                                    null
                                }
                            }
                            
                            if (savedId != null && savedId > 0) {
                                Log.d("CourseTopicFragment", "✅ ContentItem saved successfully with id=$savedId for topicId=$savedTopicId")
                            } else {
                                Log.e("CourseTopicFragment", "❌ Failed to save ContentItem for topicId=$savedTopicId")
                            }
                        } else {
                            Log.w("CourseTopicFragment", "⚠️ Skipping content item $i: uri=$contentUri, type=$contentType (missing data)")
                        }
                    }
                } else {
                    Log.e("CourseTopicFragment", "❌ contentContainer is null! Cannot save content items")
                }

                // Show success message and navigate back
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Tema guardado correctamente en Supabase", Toast.LENGTH_SHORT).show()
                }

                com.example.tareamov.util.AppCache.invalidateCourseContent(courseId)
                try {
                    val detailEntry = findNavController().getBackStackEntry(R.id.courseDetailFragment)
                    detailEntry.savedStateHandle["topic_created"] = savedTopicId
                    detailEntry.savedStateHandle["refresh_from_supabase"] = true
                    detailEntry.savedStateHandle["force_reload_topics"] = true
                } catch (e: Exception) {
                    findNavController().previousBackStackEntry?.savedStateHandle?.set("topic_created", savedTopicId)
                    findNavController().previousBackStackEntry?.savedStateHandle?.set("force_reload_topics", true)
                }

                findNavController().popBackStack(R.id.courseDetailFragment, false)

            } catch (e: Exception) {
                Log.e("CourseTopicFragment", "Error al guardar el tema", e)
                if (isAdded && context != null) {
                    Toast.makeText(requireContext(), "Error al guardar el tema: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}