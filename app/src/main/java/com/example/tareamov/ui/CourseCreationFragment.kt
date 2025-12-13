package com.example.tareamov.ui

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.util.VideoManager
import com.example.tareamov.data.entity.Course
import com.example.tareamov.MainActivity
import com.example.tareamov.service.CloudflareR2Service
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseCreationFragment : Fragment() {

    private var topicCount = 0
    private lateinit var videoManager: VideoManager
    private var currentCourseId: Long = -1L
    private var courseSaved = false
    private lateinit var sessionManager: SessionManager
    private var selectedThumbnailUri: Uri? = null
    private var isEditing = false
    private var isPaidCourse = false // Track payment status
    private lateinit var thumbnailExtractor: com.example.tareamov.util.VideoThumbnailExtractor

    companion object {
        private const val REQUEST_THUMBNAIL_PICK = 1001
        private const val KEY_THUMBNAIL_URI = "key_thumbnail_uri"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_course_creation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Check arguments for editing
        arguments?.let {
            val id = it.getLong("courseId", -1L)
            isEditing = it.getBoolean("isEditing", false)
            if (id != -1L && isEditing) {
                currentCourseId = id
                courseSaved = true
                loadCourseData(id)
                // Update UI for editing
                view.findViewById<TextView>(R.id.saveButton)?.text = "Actualizar"
            }
        }

        // Restore thumbnail URI if available
        if (savedInstanceState != null) {
            val uriString = savedInstanceState.getString(KEY_THUMBNAIL_URI)
            if (!uriString.isNullOrEmpty()) {
                selectedThumbnailUri = Uri.parse(uriString)
                view.findViewById<ImageView>(R.id.courseThumbnailImageView).setImageURI(selectedThumbnailUri)
            }
        }

        videoManager = VideoManager(requireContext())
        sessionManager = SessionManager.getInstance(requireContext())
        thumbnailExtractor = com.example.tareamov.util.VideoThumbnailExtractor(requireContext())

        // Set up back button
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Set up save button (Siguiente)
        val saveButton = view.findViewById<TextView>(R.id.saveButton)
        saveButton.setOnClickListener {
            saveCourse()
        }

        // Set up add topic button
        val addTopicButton = view.findViewById<Button>(R.id.addTopicButton)
        addTopicButton.setOnClickListener {
            addNewTopic()
        }

        // Set up select thumbnail button
        val selectThumbnailButton = view.findViewById<Button>(R.id.selectThumbnailButton)
        selectThumbnailButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQUEST_THUMBNAIL_PICK)
        }

        // Toggle Logic for Free/Paid
        setupToggleLogic(view)

        // Character Counter Logic
        setupCharacterCounter(view)
    }

    private fun setupToggleLogic(view: View) {
        val btnFree = view.findViewById<LinearLayout>(R.id.btnFree)
        val btnPaid = view.findViewById<LinearLayout>(R.id.btnPaid)
        
        btnFree.setOnClickListener { updateToggleState(false) }
        btnPaid.setOnClickListener { updateToggleState(true) }

        // Initial state
        updateToggleState(false)
    }

    private fun updateToggleState(paid: Boolean) {
        isPaidCourse = paid
        val view = view ?: return
        
        val btnFree = view.findViewById<LinearLayout>(R.id.btnFree)
        val btnPaid = view.findViewById<LinearLayout>(R.id.btnPaid)
        val priceContainer = view.findViewById<LinearLayout>(R.id.priceContainer)
        val iconFree = view.findViewById<ImageView>(R.id.iconFree)
        val textFree = view.findViewById<TextView>(R.id.textFree)
        val iconPaid = view.findViewById<ImageView>(R.id.iconPaid)
        val textPaid = view.findViewById<TextView>(R.id.textPaid)

        if (paid) {
            btnFree.setBackgroundResource(R.drawable.bg_toggle_card_unselected)
            iconFree.setColorFilter(Color.parseColor("#888888"))
            textFree.setTextColor(Color.parseColor("#888888"))

            btnPaid.setBackgroundResource(R.drawable.bg_toggle_card_selected)
            iconPaid.setColorFilter(Color.parseColor("#3EA6FF"))
            textPaid.setTextColor(Color.parseColor("#FFFFFF"))

            priceContainer.visibility = View.VISIBLE
        } else {
            btnFree.setBackgroundResource(R.drawable.bg_toggle_card_selected)
            iconFree.setColorFilter(Color.parseColor("#3EA6FF"))
            textFree.setTextColor(Color.parseColor("#FFFFFF"))

            btnPaid.setBackgroundResource(R.drawable.bg_toggle_card_unselected)
            iconPaid.setColorFilter(Color.parseColor("#888888"))
            textPaid.setTextColor(Color.parseColor("#888888"))

            priceContainer.visibility = View.GONE
        }
    }

    private fun setupCharacterCounter(view: View) {
        val titleEditText = view.findViewById<EditText>(R.id.courseNameEditText)
        val charCounter = view.findViewById<TextView>(R.id.titleCharCounter)
        
        titleEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val length = s?.length ?: 0
                charCounter.text = "$length/100"
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_THUMBNAIL_PICK && resultCode == Activity.RESULT_OK && data != null) {
            selectedThumbnailUri = data.data
            selectedThumbnailUri?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            view?.findViewById<ImageView>(R.id.courseThumbnailImageView)?.setImageURI(selectedThumbnailUri)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_THUMBNAIL_URI, selectedThumbnailUri?.toString())
    }

    private fun loadCourseData(courseId: Long) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val activity = requireActivity()
                if (activity is MainActivity) {
                    val course = withContext(Dispatchers.IO) {
                        activity.syncRepository.fetchCourseById(courseId)
                    }
                    
                    if (course != null) {
                        view?.findViewById<EditText>(R.id.courseNameEditText)?.setText(course.title)
                        view?.findViewById<EditText>(R.id.courseCategoryEditText)?.setText(course.category)
                        view?.findViewById<EditText>(R.id.courseDescriptionEditText)?.setText(course.description)
                        
                        updateToggleState(course.isPremium)
                        if (course.isPremium) {
                            view?.findViewById<EditText>(R.id.coursePriceEditText)?.setText(course.price.toString())
                        }
                        
                        if (!course.thumbnailUri.isNullOrEmpty()) {
                            selectedThumbnailUri = Uri.parse(course.thumbnailUri)
                            val imageView = view?.findViewById<ImageView>(R.id.courseThumbnailImageView)
                            if (imageView != null) {
                                Glide.with(this@CourseCreationFragment)
                                    .load(course.thumbnailUri)
                                    .placeholder(R.drawable.ic_image_placeholder) // Updated placeholder
                                    .into(imageView)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseCreationFragment", "Error loading course data", e)
                Toast.makeText(context, "Error al cargar datos del curso", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveCourse() {
        val courseName = view?.findViewById<EditText>(R.id.courseNameEditText)?.text.toString()
        val courseCategory = view?.findViewById<EditText>(R.id.courseCategoryEditText)?.text.toString()
        val courseDescription = view?.findViewById<EditText>(R.id.courseDescriptionEditText)?.text.toString()
        
        val coursePrice = if (isPaidCourse) {
            view?.findViewById<EditText>(R.id.coursePriceEditText)?.text.toString().toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }

        if (courseName.isBlank()) {
            Toast.makeText(context, "Por favor ingresa un nombre para el curso", Toast.LENGTH_SHORT).show()
            return
        }
        
        val currentUsername = sessionManager.getUsername()
        if (currentUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado. No se puede crear el curso.", Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername)
                }

                if (userId == null || userId <= 0) {
                    Toast.makeText(context, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Subir miniatura a Cloudflare R2 si está seleccionada
                var thumbnailUriString = selectedThumbnailUri?.toString()
                if (selectedThumbnailUri != null && CloudflareR2Service.isConfigured()) {
                    Toast.makeText(context, "Subiendo miniatura a la nube...", Toast.LENGTH_SHORT).show()
                    val result = withContext(Dispatchers.IO) {
                        CloudflareR2Service.uploadFile(
                            context = requireContext(),
                            fileUri = selectedThumbnailUri!!,
                            folder = "thumbnails/courses",
                            customFileName = "course_${System.currentTimeMillis()}"
                        )
                    }
                    when (result) {
                        is CloudflareR2Service.UploadResult.Success -> {
                            thumbnailUriString = result.url
                            Log.d("CourseCreationFragment", "☁️ Thumbnail uploaded to R2: $thumbnailUriString")
                        }
                        is CloudflareR2Service.UploadResult.Error -> {
                            Log.e("CourseCreationFragment", "❌ Failed to upload thumbnail: ${result.message}")
                            // Continuar con URI local como fallback
                        }
                    }
                }
                
                val activity = requireActivity()
                if (activity !is MainActivity) {
                    Toast.makeText(context, "Error: Contexto inválido", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (isEditing) {
                    val originalCourse = withContext(Dispatchers.IO) {
                        activity.syncRepository.fetchCourseById(currentCourseId)
                    }
                    
                    if (originalCourse != null) {
                        val updatedCourse = originalCourse.copy(
                            title = courseName,
                            description = courseDescription,
                            category = courseCategory,
                            price = coursePrice,
                            isPremium = isPaidCourse,
                            thumbnailUri = thumbnailUriString ?: originalCourse.thumbnailUri,
                            lastModifiedDate = System.currentTimeMillis().toString()
                        )
                        
                        val success = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.updateCourseById(currentCourseId, updatedCourse)
                        }
                        
                        if (success) {
                            Toast.makeText(context, "Curso actualizado exitosamente", Toast.LENGTH_SHORT).show()
                            findNavController().navigateUp()
                        } else {
                            Toast.makeText(context, "Error al actualizar el curso", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Error: No se encontró el curso original", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val courseData = Course(
                        id = 0,
                        title = courseName,
                        description = courseDescription,
                        creatorUserId = userId,
                        thumbnailUri = thumbnailUriString,
                        videoUri = null,
                        localFilePath = null,
                        duration = null,
                        category = courseCategory,
                        price = coursePrice,
                        isPremium = isPaidCourse,
                        isPublished = true,
                        creationDate = System.currentTimeMillis().toString(),
                        lastModifiedDate = System.currentTimeMillis().toString(),
                        enrollmentCount = 0,
                        rating = 0.0f,
                        tags = null,
                        timestamp = System.currentTimeMillis()
                    )
            
                    val titleExists = withContext(Dispatchers.IO) {
                        activity.syncRepository.isTitleExistsInSupabase(courseName)
                    }

                    if (titleExists) {
                        Toast.makeText(context, "Ya existe un curso con este título. Elige otro título.", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    val remoteId = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.insertCourse(courseData)
                    }

                    if (remoteId != null && remoteId > 0) {
                        currentCourseId = remoteId
                        courseSaved = true
                        Toast.makeText(context, "Curso guardado exitosamente", Toast.LENGTH_SHORT).show()
                        
                        // Notify subscribers about the new course
                        val createdCourse = courseData.copy(id = remoteId)
                        activity.syncRepository.notifySubscribersOfNewCourseAsync(createdCourse)
                        
                        val bundle = Bundle().apply {
                            putLong("courseId", remoteId)
                            putString("courseName", courseName)
                        }
                        findNavController().navigate(R.id.action_courseCreationFragment_to_courseDetailFragment, bundle)
                    } else {
                        Toast.makeText(context, "Error al guardar el curso en Supabase", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseCreationFragment", "Error saving course to Supabase", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addNewTopic() {
        val courseName = view?.findViewById<EditText>(R.id.courseNameEditText)?.text.toString()
        if (courseName.isBlank()) {
            Toast.makeText(context, "Por favor ingresa un nombre para el curso antes de añadir temas", Toast.LENGTH_SHORT).show()
            return
        }

        val courseCategory = view?.findViewById<EditText>(R.id.courseCategoryEditText)?.text.toString()
        val courseDescription = view?.findViewById<EditText>(R.id.courseDescriptionEditText)?.text.toString()
        
        val coursePrice = if (isPaidCourse) {
            view?.findViewById<EditText>(R.id.coursePriceEditText)?.text.toString().toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }

        val currentUserUsername = sessionManager.getUsername()
        if (currentUserUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado. No se puede agregar tema.", Toast.LENGTH_LONG).show()
            return
        }

        val thumbnailUriString = selectedThumbnailUri?.toString()
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUserUsername)
                }

                if (userId == null || userId <= 0) {
                    Toast.makeText(context, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Subir miniatura a Cloudflare R2 si está seleccionada
                var thumbnailUriString = selectedThumbnailUri?.toString()
                if (selectedThumbnailUri != null && CloudflareR2Service.isConfigured()) {
                    Toast.makeText(context, "Subiendo miniatura a la nube...", Toast.LENGTH_SHORT).show()
                    val result = withContext(Dispatchers.IO) {
                        CloudflareR2Service.uploadFile(
                            context = requireContext(),
                            fileUri = selectedThumbnailUri!!,
                            folder = "thumbnails/courses",
                            customFileName = "course_${System.currentTimeMillis()}"
                        )
                    }
                    when (result) {
                        is CloudflareR2Service.UploadResult.Success -> {
                            thumbnailUriString = result.url
                            Log.d("CourseCreationFragment", "☁️ Thumbnail uploaded to R2: $thumbnailUriString")
                        }
                        is CloudflareR2Service.UploadResult.Error -> {
                            Log.e("CourseCreationFragment", "❌ Failed to upload thumbnail: ${result.message}")
                            // Continuar con URI local como fallback
                        }
                    }
                }

                val courseData = Course(
                    id = 0,
                    title = courseName,
                    description = courseDescription,
                    creatorUserId = userId,
                    thumbnailUri = thumbnailUriString,
                    videoUri = null,
                    localFilePath = null,
                    duration = null,
                    category = courseCategory,
                    price = coursePrice,
                    isPremium = isPaidCourse,
                    isPublished = true,
                    creationDate = System.currentTimeMillis().toString(),
                    lastModifiedDate = System.currentTimeMillis().toString(),
                    enrollmentCount = 0,
                    rating = 0.0f,
                    tags = null,
                    timestamp = System.currentTimeMillis()
                )

                try {
                    val activity = requireActivity()
                    if (activity !is MainActivity) {
                        Toast.makeText(context, "Error: Contexto inválido", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                    val titleExists = withContext(Dispatchers.IO) {
                        activity.syncRepository.isTitleExistsInSupabase(courseName)
                    }

                    if (titleExists) {
                        Toast.makeText(context, "Ya existe un curso con este título. Elige otro título.", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    val savedCourseId = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.insertCourse(courseData)
                    }

                    if (savedCourseId != null && savedCourseId > 0) {
                        currentCourseId = savedCourseId
                        courseSaved = true
                        topicCount++

                        // Notify subscribers about the new course
                        val createdCourse = courseData.copy(id = savedCourseId)
                        activity.syncRepository.notifySubscribersOfNewCourseAsync(createdCourse)

                        val bundle = Bundle()
                        bundle.putInt("topicNumber", topicCount)
                        bundle.putLong("courseId", savedCourseId)
                        bundle.putString("courseName", courseName)
                        findNavController().navigate(R.id.action_courseCreationFragment_to_courseTopicFragment, bundle)
                    } else {
                        Toast.makeText(context, "Error al guardar el curso en Supabase", Toast.LENGTH_SHORT).show()
                    }
                
                } catch (e: Exception) {
                    Log.e("CourseCreationFragment", "Error al guardar el curso", e)
                    Toast.makeText(context, "Error al guardar el curso: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("CourseCreationFragment", "Error getting user ID", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}