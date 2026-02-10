package com.example.tareamov.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView // Import added
import android.widget.Toast
import android.util.Log
import com.example.tareamov.service.CloudflareR2Service
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class EditProfileFragment : Fragment() {
    private lateinit var profileImageEdit: CircleImageView
    private lateinit var usernameEditText: EditText
    private lateinit var displayNameEditText: EditText
    private lateinit var bioEditText: EditText
    private lateinit var saveButton: TextView // Changed from Button
    private lateinit var changePhotoButton: TextView // Changed from Button
    private lateinit var backButton: TextView // Changed from ImageButton

    private var selectedImageUri: Uri? = null
    private var currentUser: Usuario? = null
    private var currentPersona: Persona? = null
    private var uploadProgressBar: ProgressBar? = null
    private var isUploading = false

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

        // Initialize views matching the new XML layout
        profileImageEdit = view.findViewById(R.id.profileImageEdit)
        usernameEditText = view.findViewById(R.id.usernameEditText)
        displayNameEditText = view.findViewById(R.id.displayNameEditText)
        bioEditText = view.findViewById(R.id.bioEditText)
        
        // Mapping to new IDs in fragment_edit_profile.xml
        saveButton = view.findViewById(R.id.doneButton) // Was saveButton
        changePhotoButton = view.findViewById(R.id.changePhotoButton)
        backButton = view.findViewById(R.id.cancelButton) // Was backButton
        uploadProgressBar = view.findViewById(R.id.uploadProgressBar)

        // Load current user data
        loadUserData()

        // Set up click listeners
        changePhotoButton.setOnClickListener {
            openImagePicker()
        }

        saveButton.setOnClickListener {
            saveUserData()
        }

        backButton.setOnClickListener {
            findNavController().navigateUp()
        }
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

    private fun loadUserData() {
        lifecycleScope.launch {
            try {
                // Prefer SessionManager to find current user
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                val sessionUsername = sessionManager.getUsername()
                if (!sessionUsername.isNullOrEmpty()) {
                    val result = withContext(Dispatchers.IO) {
                        BackendApiService.getUserByUsername(sessionUsername)
                    }
                    if (result is ApiResult.Success) {
                        currentUser = result.data
                    }
                }

                // Fallback to stored user id in prefs
                if (currentUser == null) {
                    val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    val currentUserId = sharedPrefs.getLong("current_user_id", -1L)
                    if (currentUserId != -1L) {
                        val result = withContext(Dispatchers.IO) { BackendApiService.getUserById(currentUserId) }
                        if (result is ApiResult.Success) {
                            currentUser = result.data
                        }
                    }
                }

                if (currentUser != null) {
                    // Get persona data
                    val personaResult = withContext(Dispatchers.IO) {
                        BackendApiService.getPersonaById(currentUser!!.persona_id)
                    }
                    if (personaResult is ApiResult.Success) {
                        currentPersona = personaResult.data
                    }

                    // Update UI with current data
                    usernameEditText.setText(currentUser?.usuario)
                    displayNameEditText.setText(currentPersona?.nombres ?: currentUser?.usuario)

                    // Load avatar if available
                    if (currentUser != null && !currentUser?.avatar.isNullOrEmpty()) {
                        loadAvatar(currentUser?.avatar)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "Error al cargar datos: ${e.message}", Toast.LENGTH_SHORT).show()
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
        val newUsername = usernameEditText.text.toString().trim()
        val displayName = displayNameEditText.text.toString().trim()
        val bio = bioEditText.text.toString().trim()

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
                // Check if username is already taken (if changed)
                if (newUsername != currentUser?.usuario) {
                    val existingResult = withContext(Dispatchers.IO) {
                        BackendApiService.getUserByUsername(newUsername)
                    }

                    if (existingResult is ApiResult.Success && existingResult.data.id != currentUser?.id) {
                        Toast.makeText(requireContext(), "Este nombre de usuario ya está en uso", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }

                // Upload avatar to Cloudflare R2 if selected
                var avatarUrl: String? = currentUser?.avatar // Keep existing avatar if no new one selected
                if (selectedImageUri != null) {
                    avatarUrl = uploadAvatarToR2(selectedImageUri!!)
                    if (avatarUrl == null) {
                        Toast.makeText(requireContext(), "Error al subir la imagen. Intenta de nuevo.", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }

                // Update persona with new information
                if (currentPersona != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val personaUpdates = mapOf<String, Any?>(
                                "nombres" to displayName,
                                "apellidos" to currentPersona!!.apellidos
                            )
                            val personaResult = BackendApiService.updatePersona(currentPersona!!.id, personaUpdates)
                            when (personaResult) {
                                is ApiResult.Success -> Log.d("EditProfileFragment", "Persona updated successfully")
                                is ApiResult.Error -> Log.w("EditProfileFragment", "Failed to update persona: ${personaResult.message}")
                            }
                        } catch (e: Exception) {
                            Log.w("EditProfileFragment", "Failed to update persona", e)
                        }
                    }
                }

                // Update usuario with new username and avatar URL
                if (currentUser != null) {
                    withContext(Dispatchers.IO) {
                        try {
                            val profileUpdates = mapOf<String, Any?>(
                                "usuario" to newUsername,
                                "avatar" to avatarUrl
                            )
                            val profileResult = BackendApiService.updateMyProfile(profileUpdates)
                            when (profileResult) {
                                is ApiResult.Success -> Log.d("EditProfileFragment", "Profile updated successfully, avatar: $avatarUrl")
                                is ApiResult.Error -> Log.w("EditProfileFragment", "Failed to update profile: ${profileResult.message}")
                            }
                        } catch (e: Exception) {
                            Log.w("EditProfileFragment", "Failed to update usuario", e)
                        }
                    }
                }

                // Update session and shared preferences to signal profile update
                val sessionManager = com.example.tareamov.util.SessionManager.getInstance(requireContext())
                // If username changed or avatar changed, update session stored values
                sessionManager.createLoginSession(
                    newUsername,
                    sessionManager.getUserId(),
                    sessionManager.getPersonaId(),
                    sessionManager.getUserRole() ?: "user",
                    avatarUrl // Use the R2 URL
                )

                val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putBoolean("profile_updated", true).apply()

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
                if (!CloudflareR2Service.isConfigured()) {
                    Log.e("EditProfileFragment", "Cloudflare R2 not configured")
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
                
                // Upload image to R2 using the existing uploadImage function
                val result = CloudflareR2Service.uploadImage(
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
                    is CloudflareR2Service.UploadResult.Success -> {
                        Log.d("EditProfileFragment", "Avatar uploaded successfully: ${result.url}")
                        result.url
                    }
                    is CloudflareR2Service.UploadResult.Error -> {
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