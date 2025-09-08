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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.repository.PersonaRepository
import com.example.tareamov.repository.UsuarioRepository
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
    private lateinit var saveButton: Button
    private lateinit var changePhotoButton: Button
    private lateinit var backButton: ImageButton

    private var selectedImageUri: Uri? = null
    private var currentUser: Usuario? = null
    private var currentPersona: Persona? = null

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

        // Initialize views
        profileImageEdit = view.findViewById(R.id.profileImageEdit)
        usernameEditText = view.findViewById(R.id.usernameEditText)
        displayNameEditText = view.findViewById(R.id.displayNameEditText)
        bioEditText = view.findViewById(R.id.bioEditText)
        saveButton = view.findViewById(R.id.saveButton)
        changePhotoButton = view.findViewById(R.id.changePhotoButton)
        backButton = view.findViewById(R.id.backButton)

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
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            try {
                // Get current user ID from SharedPreferences
                val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                val currentUserId = sharedPrefs.getLong("current_user_id", -1L)

                if (currentUserId != -1L) {
                    // Get user data from database
                    val db = AppDatabase.getDatabase(requireContext())
                    currentUser = withContext(Dispatchers.IO) {
                        db.usuarioDao().getUsuarioById(currentUserId)
                    }

                    if (currentUser != null) {
                        // Get persona data
                        currentPersona = withContext(Dispatchers.IO) {
                            db.personaDao().getPersonaById(currentUser!!.personaId)
                        }

                        // Update UI with current data
                        usernameEditText.setText(currentUser?.usuario)
                        displayNameEditText.setText(currentUser?.usuario) // Using same value for display name

                        // Load avatar if available
                        if (currentPersona != null && !currentPersona?.avatar.isNullOrEmpty()) {
                            loadAvatar(currentPersona?.avatar)
                        }
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

        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val usuarioDao = db.usuarioDao()
                val personaDao = db.personaDao()

                // Create repositories
                val usuarioRepository = UsuarioRepository(usuarioDao)
                val personaRepository = PersonaRepository(personaDao, usuarioDao)

                // Check if username is already taken (if changed)
                if (newUsername != currentUser?.usuario) {
                    val existingUser = withContext(Dispatchers.IO) {
                        usuarioRepository.getUsuarioByUsername(newUsername)
                    }

                    if (existingUser != null && existingUser.id != currentUser?.id) {
                        Toast.makeText(requireContext(), "Este nombre de usuario ya está en uso", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                }

                // Save new avatar if selected
                var avatarPath = currentPersona?.avatar
                if (selectedImageUri != null) {
                    avatarPath = saveImageToInternalStorage(selectedImageUri!!)
                }

                // Update persona with new information
                if (currentPersona != null) {
                    withContext(Dispatchers.IO) {
                        personaRepository.updateProfile(
                            currentPersona!!.id,
                            displayName, // Use display name for nombres
                            currentPersona!!.apellidos, // Keep existing apellidos
                            avatarPath
                        )
                    }
                }

                // Update usuario with new username
                if (currentUser != null) {
                    withContext(Dispatchers.IO) {
                        usuarioRepository.updateUserProfile(
                            currentUser!!.id,
                            newUsername
                        )
                    }
                }

                // Update shared preferences to signal profile update
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