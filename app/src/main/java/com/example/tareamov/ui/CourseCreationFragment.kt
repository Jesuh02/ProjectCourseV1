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
import com.example.tareamov.util.SessionManager // Added import
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CourseCreationFragment : Fragment() {

    private var topicCount = 0
    private lateinit var videoManager: VideoManager
    private var currentCourseId: Long = -1L
    private var courseSaved = false
    private lateinit var sessionManager: SessionManager // Added SessionManager instance
    private var selectedThumbnailUri: Uri? = null
    private var isEditing = false // Added flag for editing mode

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
                courseSaved = true // Course exists
                loadCourseData(id)
                // Update UI for editing
                view.findViewById<Button>(R.id.saveButton)?.text = "Actualizar Curso"
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

        // Inicializar VideoManager
        videoManager = VideoManager(requireContext())
        sessionManager = SessionManager.getInstance(requireContext()) // Initialize SessionManager

        // Set up back button
        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }

        // Set up save button
        val saveButton = view.findViewById<Button>(R.id.saveButton)
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
        val courseThumbnailImageView = view.findViewById<ImageView>(R.id.courseThumbnailImageView)
        selectThumbnailButton.setOnClickListener {
            // Use ACTION_OPEN_DOCUMENT to allow Drive and other providers
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, REQUEST_THUMBNAIL_PICK)
        }

        // If you want to persist the image even after process death, load from SharedPreferences here
        // val prefs = requireContext().getSharedPreferences("course_creation", Context.MODE_PRIVATE)
        // val uriString = prefs.getString(KEY_THUMBNAIL_URI, null)
        // if (!uriString.isNullOrEmpty()) {
        //     selectedThumbnailUri = Uri.parse(uriString)
        //     courseThumbnailImageView.setImageURI(selectedThumbnailUri)
        // }
        val courseTypeRadioGroup = view.findViewById<RadioGroup>(R.id.courseTypeRadioGroup)
        val priceContainer = view.findViewById<LinearLayout>(R.id.priceContainer)
        val coursePriceEditText = view.findViewById<EditText>(R.id.coursePriceEditText)

        courseTypeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            priceContainer.visibility = if (checkedId == R.id.paidCourseRadioButton) View.VISIBLE else View.GONE
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_THUMBNAIL_PICK && resultCode == Activity.RESULT_OK && data != null) {
            selectedThumbnailUri = data.data
            // Persist permission for future access
            selectedThumbnailUri?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            view?.findViewById<ImageView>(R.id.courseThumbnailImageView)?.setImageURI(selectedThumbnailUri)

            // Optionally, persist in SharedPreferences for long-term persistence
            // val prefs = requireContext().getSharedPreferences("course_creation", Context.MODE_PRIVATE)
            // prefs.edit().putString(KEY_THUMBNAIL_URI, selectedThumbnailUri.toString()).apply()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Save the URI string for persistence across configuration changes
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
                        // Populate fields
                        view?.findViewById<EditText>(R.id.courseNameEditText)?.setText(course.title)
                        view?.findViewById<EditText>(R.id.courseCategoryEditText)?.setText(course.category)
                        view?.findViewById<EditText>(R.id.courseDescriptionEditText)?.setText(course.description)
                        
                        if (course.isPremium) {
                            view?.findViewById<RadioButton>(R.id.paidCourseRadioButton)?.isChecked = true
                            view?.findViewById<EditText>(R.id.coursePriceEditText)?.setText(course.price.toString())
                            view?.findViewById<LinearLayout>(R.id.priceContainer)?.visibility = View.VISIBLE
                        } else {
                            view?.findViewById<RadioButton>(R.id.freeCourseRadioButton)?.isChecked = true
                            view?.findViewById<LinearLayout>(R.id.priceContainer)?.visibility = View.GONE
                        }
                        
                        // Load thumbnail
                        if (!course.thumbnailUri.isNullOrEmpty()) {
                            selectedThumbnailUri = Uri.parse(course.thumbnailUri)
                            val imageView = view?.findViewById<ImageView>(R.id.courseThumbnailImageView)
                            if (imageView != null) {
                                Glide.with(this@CourseCreationFragment)
                                    .load(course.thumbnailUri)
                                    .placeholder(R.drawable.placeholder_image) // Assuming placeholder exists or just default
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
        val courseTypeRadioGroup = view?.findViewById<RadioGroup>(R.id.courseTypeRadioGroup)
        val isPaid = courseTypeRadioGroup?.checkedRadioButtonId == R.id.paidCourseRadioButton
        val coursePrice = if (isPaid) {
            view?.findViewById<EditText>(R.id.coursePriceEditText)?.text.toString().toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }

        if (courseName.isBlank()) {
            Toast.makeText(context, "Por favor ingresa un nombre para el curso", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Get current username from SessionManager
        val currentUsername = sessionManager.getUsername()
        if (currentUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado. No se puede crear el curso.", Toast.LENGTH_LONG).show()
            return
        }

        // Get user ID from username for foreign key
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername)
                }

                if (userId == null || userId <= 0) {
                    Toast.makeText(context, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val thumbnailUriString = selectedThumbnailUri?.toString()
                
                val activity = requireActivity()
                if (activity !is MainActivity) {
                    Toast.makeText(context, "Error: Contexto inválido", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                if (isEditing) {
                    // Update existing course
                    val originalCourse = withContext(Dispatchers.IO) {
                        activity.syncRepository.fetchCourseById(currentCourseId)
                    }
                    
                    if (originalCourse != null) {
                        val updatedCourse = originalCourse.copy(
                            title = courseName,
                            description = courseDescription,
                            category = courseCategory,
                            price = coursePrice,
                            isPremium = isPaid,
                            thumbnailUri = thumbnailUriString ?: originalCourse.thumbnailUri,
                            lastModifiedDate = System.currentTimeMillis().toString()
                        )
                        
                        val success = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.updateCourseById(currentCourseId, updatedCourse)
                        }
                        
                        if (success) {
                            Toast.makeText(context, "Curso actualizado exitosamente", Toast.LENGTH_SHORT).show()
                            // Navigate back to details
                            findNavController().navigateUp()
                        } else {
                            Toast.makeText(context, "Error al actualizar el curso", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "Error: No se encontró el curso original", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // Create new course
                    val courseData = Course(
                        id = 0, // Supabase will auto-generate
                        title = courseName,
                        description = courseDescription,
                        creatorUserId = userId, // Foreign key to usuarios.id
                        thumbnailUri = thumbnailUriString,
                        videoUri = null,
                        localFilePath = null,
                        duration = null,
                        category = courseCategory,
                        price = coursePrice,
                        isPremium = isPaid,
                        isPublished = true,
                        creationDate = System.currentTimeMillis().toString(),
                        lastModifiedDate = System.currentTimeMillis().toString(),
                        enrollmentCount = 0,
                        rating = 0.0f,
                        tags = null,
                        timestamp = System.currentTimeMillis()
                    )
            
                    // Check for duplicate title in Supabase
                    val titleExists = withContext(Dispatchers.IO) {
                        activity.syncRepository.isTitleExistsInSupabase(courseName)
                    }

                    if (titleExists) {
                        Toast.makeText(context, "Ya existe un curso con este título. Elige otro título.", Toast.LENGTH_LONG).show()
                        return@launch
                    }

                    // Insert course to Supabase and get the remote ID
                    val remoteId = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.insertCourse(courseData)
                    }

                    if (remoteId != null && remoteId > 0) {
                        currentCourseId = remoteId
                        courseSaved = true
                        Toast.makeText(context, "Curso guardado exitosamente en Supabase con ID: $remoteId", Toast.LENGTH_SHORT).show()
                        Log.d("CourseCreationFragment", "Course saved to Supabase with ID: $remoteId")
                        
                        // Navigate to CourseDetailFragment with the created course
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
        } // Close launch
    } // Close saveCourse

    private fun addNewTopic() {
        // Check if course is already saved
        val courseName = view?.findViewById<EditText>(R.id.courseNameEditText)?.text.toString()
        if (courseName.isBlank()) {
            Toast.makeText(context, "Por favor ingresa un nombre para el curso antes de añadir temas", Toast.LENGTH_SHORT).show()
            return
        }

        val courseCategory = view?.findViewById<EditText>(R.id.courseCategoryEditText)?.text.toString()
        val courseDescription = view?.findViewById<EditText>(R.id.courseDescriptionEditText)?.text.toString()
        val courseTypeRadioGroup = view?.findViewById<RadioGroup>(R.id.courseTypeRadioGroup)
        val isPaid = courseTypeRadioGroup?.checkedRadioButtonId == R.id.paidCourseRadioButton
        val coursePrice = if (isPaid) {
            view?.findViewById<EditText>(R.id.coursePriceEditText)?.text.toString().toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }

        val currentUserUsername = sessionManager.getUsername()
        if (currentUserUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado. No se puede agregar tema.", Toast.LENGTH_LONG).show()
            return
        }

        // Crear el objeto Course con la información del curso
        val thumbnailUriString = selectedThumbnailUri?.toString()
        
        // Get user ID for foreign key
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUserUsername)
                }

                if (userId == null || userId <= 0) {
                    Toast.makeText(context, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val courseData = Course(
                    id = 0, // Supabase will auto-generate
                    title = courseName,
                    description = courseDescription,
                    creatorUserId = userId, // Foreign key to usuarios.id
                    thumbnailUri = thumbnailUriString,
                    videoUri = null,
                    localFilePath = null,
                    duration = null,
                    category = courseCategory,
                    price = coursePrice,
                    isPremium = isPaid,
                    isPublished = true,
                    creationDate = System.currentTimeMillis().toString(),
                    lastModifiedDate = System.currentTimeMillis().toString(),
                    enrollmentCount = 0,
                    rating = 0.0f,
                    tags = null,
                    timestamp = System.currentTimeMillis()
                )

                // Guardar el curso directamente en Supabase y luego navegar al tema
                try {
                    val activity = requireActivity()
                    if (activity !is MainActivity) {
                        Toast.makeText(context, "Error: Contexto inválido", Toast.LENGTH_SHORT).show()
                        return@launch
                    }

                // Check for duplicate title in Supabase
                val titleExists = withContext(Dispatchers.IO) {
                    activity.syncRepository.isTitleExistsInSupabase(courseName)
                }

                if (titleExists) {
                    Toast.makeText(context, "Ya existe un curso con este título. Elige otro título.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Insert course to Supabase and get the remote ID
                val savedCourseId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.insertCourse(courseData)
                }

                if (savedCourseId != null && savedCourseId > 0) {
                    currentCourseId = savedCourseId
                    courseSaved = true
                    Log.d("CourseCreationFragment", "Curso guardado en Supabase con ID: $savedCourseId")

                    // Increment topic count
                    topicCount++

                    // Navigate to CourseTopicFragment with topic number and course ID from Supabase
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
        } // Close launch for userId retrieval
    } // Close addNewTopic
}