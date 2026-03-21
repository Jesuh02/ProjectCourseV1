package com.example.tareamov.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.util.AppCache
import com.example.tareamov.util.SessionManager
import com.example.tareamov.service.StorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SubjectCreationFragment : Fragment() {

    private var courseId: Long = -1
    private var courseName: String = ""
    private var selectedThumbnailUri: Uri? = null
    private var subjectId: Long = -1
    private var subjectName: String = ""
    private var subjectDescription: String = ""
    private var subjectCode: String = ""
    private var subjectThumbnailUrl: String = ""
    private val isEditMode get() = subjectId > 0

    companion object {
        private const val REQUEST_THUMBNAIL_PICK = 3001
        private const val KEY_THUMBNAIL_URI = "subject_thumbnail_uri"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            courseId = it.getLong("courseId", -1)
            courseName = it.getString("courseName", "")
            subjectId = it.getLong("subjectId", -1)
            subjectName = it.getString("subjectName", "")
            subjectDescription = it.getString("subjectDescription", "")
            subjectCode = it.getString("subjectCode", "")
            subjectThumbnailUrl = it.getString("subjectThumbnailUrl", "")
        }
        savedInstanceState?.getString(KEY_THUMBNAIL_URI)?.let {
            selectedThumbnailUri = Uri.parse(it)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_subject_creation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Verificar permisos: usuarios con rol 2 (docente) o rol 3 (admin) pueden crear/editar materias
        val sessionManager = SessionManager.getInstance(requireContext())
        if (!sessionManager.hasRole(2) && !sessionManager.hasRole(3)) {
            Toast.makeText(requireContext(), "No tienes permisos para crear materias", Toast.LENGTH_SHORT).show()
            findNavController().navigateUp()
            return
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                findNavController().navigate(R.id.action_subjectCreationFragment_to_exploreFragment)
            }
        })

        val backButton = view.findViewById<ImageButton>(R.id.backButton)
        val saveButton = view.findViewById<TextView>(R.id.saveButton)
        val headerTitle = view.findViewById<TextView>(R.id.headerTitle)
        val nameEditText = view.findViewById<EditText>(R.id.subjectNameEditText)
        val descriptionEditText = view.findViewById<EditText>(R.id.subjectDescriptionEditText)
        val codeEditText = view.findViewById<EditText>(R.id.subjectCodeEditText)
        val charCounter = view.findViewById<TextView>(R.id.nameCharCounter)
        val selectThumbnailButton = view.findViewById<Button>(R.id.selectThumbnailButton)
        val thumbnailImageView = view.findViewById<ImageView>(R.id.subjectThumbnailImageView)

        saveButton.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            if (!validateSubjectEditorAccess(sessionManager)) {
                Toast.makeText(requireContext(), "No tienes permisos para gestionar esta materia", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
                return@launch
            }
            saveButton.isEnabled = true
        }

        if (isEditMode) {
            headerTitle.text = "Editar Materia"
            nameEditText.setText(subjectName)
            descriptionEditText.setText(subjectDescription)
            codeEditText.setText(subjectCode)
            charCounter.text = "${subjectName.length}/200"
            if (subjectThumbnailUrl.isNotBlank()) {
                Glide.with(this).load(subjectThumbnailUrl).centerCrop().into(thumbnailImageView)
            }
        }

        backButton.setOnClickListener { findNavController().navigateUp() }

        selectedThumbnailUri?.let { thumbnailImageView.setImageURI(it) }

        nameEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                charCounter.text = "${s?.length ?: 0}/200"
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        selectThumbnailButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_THUMBNAIL_PICK)
        }

        saveButton.setOnClickListener { saveSubject() }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_THUMBNAIL_PICK && resultCode == Activity.RESULT_OK && data != null) {
            selectedThumbnailUri = data.data
            selectedThumbnailUri?.let { uri ->
                requireContext().contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            view?.findViewById<ImageView>(R.id.subjectThumbnailImageView)?.setImageURI(selectedThumbnailUri)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_THUMBNAIL_URI, selectedThumbnailUri?.toString())
    }

    private suspend fun validateSubjectEditorAccess(sessionManager: SessionManager): Boolean {
        if (sessionManager.hasRole(3)) return true

        val userId = sessionManager.getUserId()
        if (userId <= 0 || courseId <= 0) return false

        val courseResult = withContext(Dispatchers.IO) {
            BackendApiService.getCourseById(courseId)
        }
        val isCourseCreator = courseResult is ApiResult.Success && courseResult.data.creatorUserId == userId
        if (isCourseCreator) return true

        val collabResult = withContext(Dispatchers.IO) {
            BackendApiService.checkCollaboratorAccess(courseId)
        }
        val hasCourseAccess = collabResult is ApiResult.Success && collabResult.data.get("hasAccess")?.asBoolean == true
        if (!hasCourseAccess) return false
        if (!isEditMode) return true

        val subjectResult = withContext(Dispatchers.IO) {
            BackendApiService.getSubjectById(subjectId)
        }
        if (subjectResult !is ApiResult.Success) return false

        val subjectCreatedBy = subjectResult.data.createdBy
        return subjectCreatedBy == null || subjectCreatedBy == userId
    }

    private fun saveSubject() {
        val view = view ?: return
        val name = view.findViewById<EditText>(R.id.subjectNameEditText).text.toString().trim()
        val description = view.findViewById<EditText>(R.id.subjectDescriptionEditText).text.toString().trim()
        val code = view.findViewById<EditText>(R.id.subjectCodeEditText).text.toString().trim()
        val savingProgress = view.findViewById<ProgressBar>(R.id.savingProgressBar)

        if (name.isEmpty()) {
            Toast.makeText(requireContext(), "El nombre es obligatorio", Toast.LENGTH_SHORT).show()
            return
        }
        if (courseId <= 0) {
            Toast.makeText(requireContext(), "Curso no válido", Toast.LENGTH_SHORT).show()
            return
        }

        savingProgress.visibility = View.VISIBLE
        view.findViewById<TextView>(R.id.saveButton).isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            val sessionManager = SessionManager.getInstance(requireContext())
            val userId = sessionManager.getUserId()

            if (!validateSubjectEditorAccess(sessionManager)) {
                savingProgress.visibility = View.GONE
                view.findViewById<TextView>(R.id.saveButton).isEnabled = true
                Toast.makeText(requireContext(), "No tienes permisos para gestionar esta materia", Toast.LENGTH_SHORT).show()
                return@launch
            }

            var thumbnailUrl: String? = null
            if (selectedThumbnailUri != null && StorageHelper.isConfigured()) {
                val uploadResult = withContext(Dispatchers.IO) {
                    StorageHelper.uploadFile(
                        context = requireContext(),
                        fileUri = selectedThumbnailUri!!,
                        folder = "thumbnails/subjects",
                        customFileName = "subject_${System.currentTimeMillis()}"
                    )
                }
                if (uploadResult is StorageHelper.UploadResult.Success) {
                    thumbnailUrl = uploadResult.url
                }
            }

            val payload = mutableMapOf<String, Any?>(
                "name" to name,
                "description" to description,
            )
            if (code.isNotEmpty()) payload["code"] = code
            if (thumbnailUrl != null) payload["thumbnailUrl"] = thumbnailUrl

            val result = if (isEditMode) {
                if (userId > 0) payload["updatedBy"] = userId
                withContext(Dispatchers.IO) {
                    BackendApiService.updateSubject(subjectId, payload)
                }
            } else {
                payload["courseId"] = courseId
                if (userId > 0) payload["createdBy"] = userId
                withContext(Dispatchers.IO) {
                    BackendApiService.createSubject(payload)
                }
            }

            savingProgress.visibility = View.GONE
            view.findViewById<TextView>(R.id.saveButton).isEnabled = true

            when (result) {
                is ApiResult.Success -> {
                    AppCache.invalidateSubjects(courseId)
                    val msg = if (isEditMode) "Materia actualizada" else "Materia creada"
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    val bundle = Bundle().apply {
                        putLong("courseId", courseId)
                        putString("courseName", courseName)
                        putBoolean("isCreator", true)
                    }
                    findNavController().navigate(R.id.action_subjectCreationFragment_to_subjectsListFragment, bundle)
                }
                is ApiResult.Error -> {
                    Toast.makeText(requireContext(), "Error: ${result.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
