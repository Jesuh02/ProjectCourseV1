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
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.util.VideoManager
import com.example.tareamov.data.entity.Course
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.StorageHelper
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
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getCourseById(courseId)
                }
                if (result is ApiResult.Success) {
                    val course = result.data
                    
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
                                .placeholder(R.drawable.ic_image_placeholder)
                                .into(imageView)
                        }
                    }
                } else if (result is ApiResult.Error) {
                    Log.e("CourseCreationFragment", "Error loading course: ${result.message}")
                    Toast.makeText(context, "Error al cargar datos del curso", Toast.LENGTH_SHORT).show()
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

        if (courseName.length < 3) {
            Toast.makeText(context, "El título debe tener al menos 3 caracteres", Toast.LENGTH_SHORT).show()
            return
        }
        
        val currentUsername = sessionManager.getUsername()
        if (currentUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado. No se puede crear el curso.", Toast.LENGTH_LONG).show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val userResult = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(currentUsername)
                }

                val userId = (userResult as? ApiResult.Success)?.data?.id ?: 0L
                if (userId <= 0) {
                    Toast.makeText(context, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Subir miniatura al backend si está seleccionada
                var thumbnailUriString = selectedThumbnailUri?.toString()
                if (selectedThumbnailUri != null && StorageHelper.isConfigured()) {
                    Toast.makeText(context, "Subiendo miniatura a la nube...", Toast.LENGTH_SHORT).show()
                    val result = withContext(Dispatchers.IO) {
                        StorageHelper.uploadFile(
                            context = requireContext(),
                            fileUri = selectedThumbnailUri!!,
                            folder = "thumbnails/courses",
                            customFileName = "course_${System.currentTimeMillis()}"
                        )
                    }
                    when (result) {
                        is StorageHelper.UploadResult.Success -> {
                            thumbnailUriString = result.url
                            Log.d("CourseCreationFragment", "☁️ Thumbnail uploaded: $thumbnailUriString")
                        }
                        is StorageHelper.UploadResult.Error -> {
                            Log.e("CourseCreationFragment", "❌ Failed to upload thumbnail: ${result.message}")
                            // Continuar con URI local como fallback
                        }
                    }
                }

                if (isEditing) {
                    val updates = mapOf<String, Any?>(
                        "title" to courseName,
                        "description" to courseDescription,
                        "category" to courseCategory,
                        "price" to coursePrice,
                        "isFree" to !isPaidCourse,
                        "thumbnailUri" to (thumbnailUriString)
                    )
                    
                    val updateResult = withContext(Dispatchers.IO) {
                        BackendApiService.updateCourse(currentCourseId, updates)
                    }
                    
                    when (updateResult) {
                        is ApiResult.Success -> {
                            Toast.makeText(context, "Curso actualizado exitosamente", Toast.LENGTH_SHORT).show()
                            findNavController().navigateUp()
                        }
                        is ApiResult.Error -> {
                            Toast.makeText(context, "Error al actualizar el curso: ${updateResult.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    val payload = mapOf(
                        "title" to courseName,
                        "description" to courseDescription,
                        "category" to courseCategory,
                        "price" to coursePrice,
                        "creatorUsername" to currentUsername,
                        "isFree" to !isPaidCourse,
                        "thumbnailUri" to thumbnailUriString
                    )
            
                    // Check if title already exists via search
                    val searchResult = withContext(Dispatchers.IO) {
                        BackendApiService.searchCourses(courseName)
                    }
                    val titleExists = (searchResult as? ApiResult.Success)?.data?.any {
                        it.title.equals(courseName, ignoreCase = true)
                    } == true

                    if (titleExists) {
                        Toast.makeText(context, "Ya existe un curso con este título. Elige otro título.", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    val createResult = withContext(Dispatchers.IO) {
                        BackendApiService.createCourse(payload)
                    }

                    when (createResult) {
                        is ApiResult.Success -> {
                            val createdCourse = createResult.data
                            val remoteId = createdCourse.id
                            currentCourseId = remoteId
                            courseSaved = true
                            Toast.makeText(context, "Curso guardado exitosamente", Toast.LENGTH_SHORT).show()
                            
                            val bundle = Bundle().apply {
                                putLong("courseId", remoteId)
                                putString("courseName", courseName)
                            }
                            findNavController().navigate(R.id.action_courseCreationFragment_to_courseDetailFragment, bundle)
                        }
                        is ApiResult.Error -> {
                            Toast.makeText(context, "Error al guardar el curso: ${createResult.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseCreationFragment", "Error saving course", e)
                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun addNewTopic() {
        val courseName = view?.findViewById<EditText>(R.id.courseNameEditText)?.text.toString()
        if (courseName.length < 3) {
            Toast.makeText(context, "El título debe tener al menos 3 caracteres", Toast.LENGTH_SHORT).show()
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
                val userResult = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(currentUserUsername)
                }

                val userId = (userResult as? ApiResult.Success)?.data?.id ?: 0L
                if (userId <= 0) {
                    Toast.makeText(context, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Subir miniatura al backend si está seleccionada
                var thumbnailUriString = selectedThumbnailUri?.toString()
                if (selectedThumbnailUri != null && StorageHelper.isConfigured()) {
                    Toast.makeText(context, "Subiendo miniatura a la nube...", Toast.LENGTH_SHORT).show()
                    val result = withContext(Dispatchers.IO) {
                        StorageHelper.uploadFile(
                            context = requireContext(),
                            fileUri = selectedThumbnailUri!!,
                            folder = "thumbnails/courses",
                            customFileName = "course_${System.currentTimeMillis()}"
                        )
                    }
                    when (result) {
                        is StorageHelper.UploadResult.Success -> {
                            thumbnailUriString = result.url
                            Log.d("CourseCreationFragment", "☁️ Thumbnail uploaded: $thumbnailUriString")
                        }
                        is StorageHelper.UploadResult.Error -> {
                            Log.e("CourseCreationFragment", "❌ Failed to upload thumbnail: ${result.message}")
                            // Continuar con URI local como fallback
                        }
                    }
                }

                val payload = mapOf(
                    "title" to courseName,
                    "description" to courseDescription,
                    "category" to courseCategory,
                    "price" to coursePrice,
                    "creatorUsername" to currentUserUsername,
                    "isFree" to !isPaidCourse,
                    "thumbnailUri" to thumbnailUriString
                )

                try {
                    // Check if title already exists via search
                    val searchResult = withContext(Dispatchers.IO) {
                        BackendApiService.searchCourses(courseName)
                    }
                    val titleExists = (searchResult as? ApiResult.Success)?.data?.any {
                        it.title.equals(courseName, ignoreCase = true)
                    } == true

                    if (titleExists) {
                        Toast.makeText(context, "Ya existe un curso con este título. Elige otro título.", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    val createResult = withContext(Dispatchers.IO) {
                        BackendApiService.createCourse(payload)
                    }

                    when (createResult) {
                        is ApiResult.Success -> {
                            val createdCourse = createResult.data
                            val savedCourseId = createdCourse.id
                            currentCourseId = savedCourseId
                            courseSaved = true
                            topicCount++

                            // Ensure the creator has the correct role (Docente)
                            withContext(Dispatchers.IO) {
                                BackendApiService.promoteToDocente(userId)
                            }

                            val bundle = Bundle()
                            bundle.putInt("topicNumber", topicCount)
                            bundle.putLong("courseId", savedCourseId)
                            bundle.putString("courseName", courseName)
                            findNavController().navigate(R.id.action_courseCreationFragment_to_courseTopicFragment, bundle)
                        }
                        is ApiResult.Error -> {
                            Toast.makeText(context, "Error al guardar el curso: ${createResult.message}", Toast.LENGTH_SHORT).show()
                        }
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