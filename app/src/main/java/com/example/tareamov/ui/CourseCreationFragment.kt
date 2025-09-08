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
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.data.entity.Topic
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.ContentItem
import com.example.tareamov.util.VideoManager
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
        }        // Get current username from SessionManager
        val currentUsername = sessionManager.getUsername()
        if (currentUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado. No se puede crear el curso.", Toast.LENGTH_LONG).show()
            return
        }



        // Create VideoData with course information and current user as creator
        val thumbnailUriString = selectedThumbnailUri?.toString()
        val courseData = VideoData(
            username = currentUsername,
            description = courseDescription,
            title = courseName,
            isPaid = isPaid,
            thumbnailUri = thumbnailUriString,
            price = coursePrice
        )        // Save course to database
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).videoDao().insertVideo(courseData)
            }

            Toast.makeText(context, "Curso creado exitosamente", Toast.LENGTH_SHORT).show()

            // Navigate back to previous screen, we don't have a direct action to ExploreFragment
            findNavController().navigateUp()
        }
    }

    private fun addNewTopic() {
        // Check if course is already saved
        val courseName = view?.findViewById<EditText>(R.id.courseNameEditText)?.text.toString()
        if (courseName.isBlank()) {
            Toast.makeText(context, "Por favor ingresa un nombre para el curso antes de añadir temas", Toast.LENGTH_SHORT).show()
            return
        }

        val courseCategory = view?.findViewById<EditText>(R.id.courseCategoryEditText)?.text.toString()
        val courseDescription = view?.findViewById<EditText>(R.id.courseDescriptionEditText)?.text.toString()

        // Crear un video dummy para representar el curso
        val dummyVideoUri = "content://media/external/video/dummy_${System.currentTimeMillis()}"

        val currentUserUsername = sessionManager.getUsername()
        if (currentUserUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado. No se puede agregar tema.", Toast.LENGTH_LONG).show()
            return
        }

        // Crear el objeto VideoData con la información del curso
        val courseData = VideoData(
            username = currentUserUsername, // Use actual username
            title = courseName,
            description = "$courseDescription\nCategoría: $courseCategory",
            videoUriString = dummyVideoUri
        )

        // Guardar el curso en la base de datos y luego navegar al tema
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val savedCourse = withContext(Dispatchers.IO) {
                    videoManager.saveVideo(courseData)
                }

                Log.d("CourseCreationFragment", "Curso guardado con ID: ${savedCourse.id}")

                // Notify database changed to update JSON
                val appDatabase = AppDatabase.getDatabase(requireContext())
                appDatabase.notifyDatabaseChanged()

                // Increment topic count
                topicCount++

                // Navigate to CourseTopicFragment with topic number and course ID
                val bundle = Bundle()
                bundle.putInt("topicNumber", topicCount)
                bundle.putLong("courseId", savedCourse.id)
                bundle.putString("courseName", savedCourse.title)
                findNavController().navigate(R.id.action_courseCreationFragment_to_courseTopicFragment, bundle)
            } catch (e: Exception) {
                Log.e("CourseCreationFragment", "Error al guardar el curso", e)
                Toast.makeText(context, "Error al guardar el curso", Toast.LENGTH_SHORT).show()
            }
        }
    }
}