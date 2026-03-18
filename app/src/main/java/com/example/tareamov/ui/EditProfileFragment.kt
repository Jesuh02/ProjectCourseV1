package com.example.tareamov.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.util.Log
import com.example.tareamov.service.StorageHelper
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.Institucion
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.MaterialDatePicker
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class EditProfileFragment : Fragment() {
    private lateinit var profileImageEdit: CircleImageView
    private lateinit var usernameEditText: EditText
    private lateinit var displayNameEditText: EditText
    private lateinit var apellidosEditText: EditText
    private lateinit var bioEditText: EditText
    private lateinit var emailEditText: EditText
    private lateinit var phoneEditText: EditText
    private lateinit var fechaNacimientoEditText: EditText
    private lateinit var institucionAutoComplete: AutoCompleteTextView
    private lateinit var saveButton: TextView
    private lateinit var changePhotoButton: TextView
    private lateinit var backButton: TextView

    private var selectedImageUri: Uri? = null
    private var currentUser: Usuario? = null
    private var currentPersona: Persona? = null
    private var uploadProgressBar: ProgressBar? = null
    private var isUploading = false
    private var instituciones: List<Institucion> = emptyList()
    private var selectedInstitucionId: Long? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedImageUri = uri
                Glide.with(requireContext())
                    .load(uri)
                    .circleCrop()
                    .into(profileImageEdit)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_edit_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profileImageEdit = view.findViewById(R.id.profileImageEdit)
        usernameEditText = view.findViewById(R.id.usernameEditText)
        displayNameEditText = view.findViewById(R.id.displayNameEditText)
        apellidosEditText = view.findViewById(R.id.apellidosEditText)
        bioEditText = view.findViewById(R.id.bioEditText)
        emailEditText = view.findViewById(R.id.emailEditText)
        phoneEditText = view.findViewById(R.id.phoneEditText)
        fechaNacimientoEditText = view.findViewById(R.id.fechaNacimientoEditText)
        institucionAutoComplete = view.findViewById(R.id.institucionAutoComplete)

        saveButton = view.findViewById(R.id.doneButton)
        changePhotoButton = view.findViewById(R.id.changePhotoButton)
        backButton = view.findViewById(R.id.cancelButton)
        uploadProgressBar = view.findViewById(R.id.uploadProgressBar)

        setupDatePicker()
        setupInstitucionAutoComplete()
        loadUserData()

        changePhotoButton.setOnClickListener { openImagePicker() }
        saveButton.setOnClickListener { saveUserData() }
        backButton.setOnClickListener { findNavController().navigateUp() }
    }

    private fun openImagePicker() {
        // Use ACTION_GET_CONTENT to allow selecting from any content provider (Gallery, Drive, Photos, etc.)
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*"
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        
        // Create a chooser to ensure the user sees all available options
        val chooser = Intent.createChooser(intent, "Seleccionar imagen de perfil")
        pickImageLauncher.launch(chooser)
    }

    private fun setupDatePicker() {
        fechaNacimientoEditText.setOnClickListener {
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.YEAR, -5)
            val maxDate = calendar.timeInMillis
            calendar.add(Calendar.YEAR, -115)
            val minDate = calendar.timeInMillis

            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Selecciona fecha de nacimiento")
                .setSelection(maxDate)
                .setCalendarConstraints(
                    CalendarConstraints.Builder()
                        .setStart(minDate)
                        .setEnd(maxDate)
                        .build()
                )
                .build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                fechaNacimientoEditText.setText(dateFormat.format(Date(selection)))
            }

            datePicker.show(parentFragmentManager, "DATE_PICKER")
        }
    }

    private fun setupInstitucionAutoComplete() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { BackendApiService.getInstituciones() }
            if (result is ApiResult.Success) {
                instituciones = result.data
                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    instituciones.map { it.nombre }
                )
                institucionAutoComplete.setAdapter(adapter)
            }
        }

        institucionAutoComplete.setOnItemClickListener { _, _, _, _ ->
            val nombre = institucionAutoComplete.text.toString()
            selectedInstitucionId = instituciones.find { it.nombre == nombre }?.id
        }

        institucionAutoComplete.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                selectedInstitucionId = null
            }
            override fun afterTextChanged(s: android.text.Editable?) {
                val query = s?.toString()?.trim() ?: return
                if (query.length < 2) return
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { BackendApiService.searchInstituciones(query) }
                    if (result is ApiResult.Success) {
                        instituciones = result.data
                        val adapter = ArrayAdapter(
                            requireContext(),
                            android.R.layout.simple_dropdown_item_1line,
                            instituciones.map { it.nombre }
                        )
                        institucionAutoComplete.setAdapter(adapter)
                        if (instituciones.isNotEmpty()) adapter.filter.filter(query)
                    }
                }
            }
        })
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            try {
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val sessionUsername = sessionManager.getUsername()
                if (!sessionUsername.isNullOrEmpty()) {
                    val result = withContext(Dispatchers.IO) {
                        BackendApiService.getUserByUsername(sessionUsername)
                    }
                    if (result is ApiResult.Success) currentUser = result.data
                }

                if (currentUser == null) {
                    val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    val currentUserId = sharedPrefs.getLong("current_user_id", -1L)
                    if (currentUserId != -1L) {
                        val result = withContext(Dispatchers.IO) { BackendApiService.getUserById(currentUserId) }
                        if (result is ApiResult.Success) currentUser = result.data
                    }
                }

                if (currentUser != null) {
                    val personaResult = withContext(Dispatchers.IO) {
                        BackendApiService.getPersonaById(currentUser!!.persona_id)
                    }
                    if (personaResult is ApiResult.Success) currentPersona = personaResult.data

                    populateFields()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Error al cargar datos: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun populateFields() {
        currentUser?.let { user ->
            usernameEditText.setText(user.usuario)
            emailEditText.setText(user.email)
            if (!user.avatar.isNullOrEmpty()) loadAvatar(user.avatar)
        }

        currentPersona?.let { persona ->
            displayNameEditText.setText(persona.nombres)
            apellidosEditText.setText(persona.apellidos)
            phoneEditText.setText(persona.telefono ?: "")
            fechaNacimientoEditText.setText(persona.fechaNacimiento ?: "")

            persona.institucionId?.let { instId ->
                selectedInstitucionId = instId
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { BackendApiService.getInstitucionById(instId) }
                    if (result is ApiResult.Success) {
                        institucionAutoComplete.setText(result.data.nombre, false)
                    }
                }
            }
        }
    }

    private fun loadAvatar(avatarPath: String?) {
        if (avatarPath == null) return

        try {
            when {
                avatarPath.startsWith("http") -> {
                    // Load from URL
                    Glide.with(requireContext())
                        .load(avatarPath)
                        .circleCrop()
                        .into(profileImageEdit)
                }
                avatarPath.startsWith("file:") -> {
                    // Load from file URI
                    val fileUri = Uri.parse(avatarPath)
                    Glide.with(requireContext())
                        .load(fileUri)
                        .circleCrop()
                        .into(profileImageEdit)
                }
                avatarPath.startsWith("/") -> {
                    // Load from file path
                    val file = File(avatarPath)
                    Glide.with(requireContext())
                        .load(file)
                        .circleCrop()
                        .into(profileImageEdit)
                }
                else -> {
                    // Try to load as resource ID or use default image
                    try {
                        val resourceId = avatarPath.toInt()
                        Glide.with(requireContext())
                            .load(resourceId)
                            .circleCrop()
                            .into(profileImageEdit)
                    } catch (e: NumberFormatException) {
                        // Use default profile image instead of resource reflection
                        Glide.with(requireContext())
                            .load(R.drawable.ic_profile)
                            .circleCrop()
                            .into(profileImageEdit)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveUserData() {
        val nombres = displayNameEditText.text.toString().trim()
        val apellidos = apellidosEditText.text.toString().trim()
        val newUsername = usernameEditText.text.toString().trim()
        val email = emailEditText.text.toString().trim()
        val telefono = phoneEditText.text.toString().trim()
        val fechaNacimiento = fechaNacimientoEditText.text.toString().trim()

        if (nombres.isEmpty()) {
            Toast.makeText(requireContext(), "El nombre no puede estar vacío", Toast.LENGTH_SHORT).show()
            return
        }
        if (newUsername.isEmpty()) {
            Toast.makeText(requireContext(), "El nombre de usuario no puede estar vacío", Toast.LENGTH_SHORT).show()
            return
        }
        if (isUploading) {
            Toast.makeText(requireContext(), "Espera a que termine la subida", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                if (newUsername != currentUser?.usuario) {
                    val existingResult = withContext(Dispatchers.IO) {
                        BackendApiService.getUserByUsername(newUsername)
                    }
                    if (existingResult is ApiResult.Success && existingResult.data.id != currentUser?.id) {
                        Toast.makeText(requireContext(), "Este nombre de usuario ya está en uso", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }

                var avatarUrl: String? = currentUser?.avatar
                if (selectedImageUri != null) {
                    avatarUrl = uploadAvatarToR2(selectedImageUri!!)
                    if (avatarUrl == null) {
                        Toast.makeText(requireContext(), "Error al subir la imagen. Intenta de nuevo.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }

                if (currentPersona != null) {
                    val personaUpdates = mutableMapOf<String, Any?>(
                        "nombres" to nombres,
                        "apellidos" to apellidos,
                        "telefono" to telefono,
                        "fechaNacimiento" to fechaNacimiento
                    )
                    if (selectedInstitucionId != null) {
                        personaUpdates["institucionId"] = selectedInstitucionId
                    }
                    withContext(Dispatchers.IO) {
                        val personaResult = BackendApiService.updatePersona(currentPersona!!.id, personaUpdates)
                        if (personaResult is ApiResult.Error) {
                            Log.w("EditProfileFragment", "Failed to update persona: ${personaResult.message}")
                        }
                    }
                }

                if (currentUser != null) {
                    val profileUpdates = mutableMapOf<String, Any?>(
                        "usuario" to newUsername,
                        "avatar" to avatarUrl
                    )
                    if (email != currentUser?.email) profileUpdates["email"] = email

                    withContext(Dispatchers.IO) {
                        val profileResult = BackendApiService.updateMyProfile(profileUpdates)
                        if (profileResult is ApiResult.Error) {
                            Log.w("EditProfileFragment", "Failed to update profile: ${profileResult.message}")
                        }
                    }
                }

                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                sessionManager.createLoginSession(
                    newUsername,
                    sessionManager.getUserId(),
                    sessionManager.getPersonaId(),
                    sessionManager.getUserRole() ?: "user",
                    avatarUrl
                )

                val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putBoolean("profile_updated", true).apply()

                com.example.tareamov.util.AppCache.invalidateProfile()
                Toast.makeText(requireContext(), "Perfil actualizado correctamente", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()

            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Error al guardar: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Upload avatar image to Cloudflare R2 and return the public URL
     */
    private suspend fun uploadAvatarToR2(uri: Uri): String? {
        return withContext(Dispatchers.IO) {
            try {
                // Check if R2 is configured
                if (!StorageHelper.isConfigured()) {
                    Log.e("EditProfileFragment", "Storage service not configured")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Servicio de almacenamiento no configurado", Toast.LENGTH_SHORT).show()
                    }
                    return@withContext null
                }

                // Show progress
                withContext(Dispatchers.Main) {
                    isUploading = true
                    uploadProgressBar?.visibility = View.VISIBLE
                    saveButton.isEnabled = false
                }

                // Generate unique filename for avatar
                val userId = currentUser?.id ?: System.currentTimeMillis()
                val fileName = "avatar_${userId}_${System.currentTimeMillis()}.jpg"
                
                Log.d("EditProfileFragment", "Uploading avatar to R2: $fileName")
                
                // Upload image via backend
                val result = StorageHelper.uploadImage(
                    context = requireContext(),
                    imageUri = uri,
                    customFileName = "avatars/$fileName"
                ) { progress ->
                    // Update progress on main thread
                    lifecycleScope.launch(Dispatchers.Main) {
                        uploadProgressBar?.progress = progress
                    }
                }

                // Hide progress
                withContext(Dispatchers.Main) {
                    isUploading = false
                    uploadProgressBar?.visibility = View.GONE
                    saveButton.isEnabled = true
                }

                when (result) {
                    is StorageHelper.UploadResult.Success -> {
                        Log.d("EditProfileFragment", "Avatar uploaded successfully: ${result.url}")
                        result.url
                    }
                    is StorageHelper.UploadResult.Error -> {
                        Log.e("EditProfileFragment", "Avatar upload failed: ${result.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e("EditProfileFragment", "Exception uploading avatar", e)
                withContext(Dispatchers.Main) {
                    isUploading = false
                    uploadProgressBar?.visibility = View.GONE
                    saveButton.isEnabled = true
                }
                null
            }
        }
    }

    /**
     * Fallback: Save image to internal storage (used if R2 upload fails)
     */
    private fun saveImageToInternalStorage(uri: Uri): String {
        val inputStream = requireContext().contentResolver.openInputStream(uri)
        val fileName = "avatar_${UUID.randomUUID()}.jpg"
        val file = File(requireContext().filesDir, fileName)

        try {
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            return "file:${file.absolutePath}"
        } catch (e: Exception) {
            e.printStackTrace()
            return ""
        }
    }
}