package com.example.tareamov.ui

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.viewmodel.PersonaViewModel
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import de.hdodenhof.circleimageview.CircleImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import at.favre.lib.crypto.bcrypt.BCrypt
import com.example.tareamov.service.SupabaseClient
import com.example.tareamov.util.SessionManager
import com.example.tareamov.data.AppDatabase
import android.content.Context
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class RegisterFragment : Fragment() {
    private lateinit var viewModel: PersonaViewModel

    // TextInputLayouts para mejor manejo de errores
    private lateinit var nombresLayout: TextInputLayout
    private lateinit var apellidosLayout: TextInputLayout
    private lateinit var emailLayout: TextInputLayout
    private lateinit var telefonoLayout: TextInputLayout
    private lateinit var fechaNacimientoLayout: TextInputLayout
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var confirmPasswordLayout: TextInputLayout

    // EditTexts
    private lateinit var nombresEditText: TextInputEditText
    private lateinit var apellidosEditText: TextInputEditText
    private lateinit var emailEditText: TextInputEditText
    private lateinit var telefonoEditText: TextInputEditText
    private lateinit var fechaNacimientoEditText: TextInputEditText
    private lateinit var usernameEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var confirmPasswordEditText: TextInputEditText

    // Avatar components
    private lateinit var avatarImageView: CircleImageView
    private lateinit var selectAvatarFab: FloatingActionButton

    // Header components
    private lateinit var welcomeText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var courseIcon: ImageView
    private lateinit var personalInfoTitle: TextView
    private lateinit var credentialsTitle: TextView
    private lateinit var platformInfo: TextView

    // Animation components
    private lateinit var avatarGlow: View
    private lateinit var particle1: View
    private lateinit var particle2: View
    private lateinit var particle3: View

    private lateinit var registerButton: Button

    // Avatar handling
    private var selectedAvatarUri: Uri? = null
    private var currentPhotoPath: String? = null
    
    // Edit mode variables
    private var isEditMode = false
    private var personaToEdit: Persona? = null
    private var usuarioToEdit: Usuario? = null
    
    // Google Sign-In mode
    private var isGoogleSignIn = false

    // Activity result launchers
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            currentPhotoPath?.let { path ->
                val file = File(path)
                selectedAvatarUri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    file
                )
                loadImageIntoAvatar(selectedAvatarUri)
            }
        }
    }

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                selectedAvatarUri = uri
                loadImageIntoAvatar(uri)
            }
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            openImagePicker()
        } else {
            Toast.makeText(
                requireContext(),
                "Se requiere permiso de cámara para tomar fotos",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_register, container, false)

        // Inicializar header components
        welcomeText = view.findViewById(R.id.welcomeText)
        subtitleText = view.findViewById(R.id.subtitleText)
        courseIcon = view.findViewById(R.id.courseIcon)
        personalInfoTitle = view.findViewById(R.id.personalInfoTitle)
        credentialsTitle = view.findViewById(R.id.credentialsTitle)
        platformInfo = view.findViewById(R.id.platformInfo)

        // Inicializar elementos de animación
        avatarGlow = view.findViewById(R.id.avatarGlow)
        particle1 = view.findViewById(R.id.particle1)
        particle2 = view.findViewById(R.id.particle2)
        particle3 = view.findViewById(R.id.particle3)

        // Inicializar TextInputLayouts
        nombresLayout = view.findViewById(R.id.nombresLayout)
        apellidosLayout = view.findViewById(R.id.apellidosLayout)
        emailLayout = view.findViewById(R.id.emailLayout)
        telefonoLayout = view.findViewById(R.id.telefonoLayout)
        fechaNacimientoLayout = view.findViewById(R.id.fechaNacimientoLayout)
        usernameLayout = view.findViewById(R.id.usernameLayout)
        passwordLayout = view.findViewById(R.id.passwordLayout)
        confirmPasswordLayout = view.findViewById(R.id.confirmPasswordLayout)

        // Inicializar EditTexts
        nombresEditText = view.findViewById(R.id.nombresEditText)
        apellidosEditText = view.findViewById(R.id.apellidosEditText)
        emailEditText = view.findViewById(R.id.emailEditText)
        telefonoEditText = view.findViewById(R.id.telefonoEditText)
        fechaNacimientoEditText = view.findViewById(R.id.fechaNacimientoEditText)
        usernameEditText = view.findViewById(R.id.usernameEditText)
        passwordEditText = view.findViewById(R.id.passwordEditText)
        confirmPasswordEditText = view.findViewById(R.id.confirmPasswordEditText)

        // Inicializar componentes de avatar
        avatarImageView = view.findViewById(R.id.avatarImageView)
        selectAvatarFab = view.findViewById(R.id.selectAvatarFab)

        registerButton = view.findViewById(R.id.registerButton)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel = ViewModelProvider(requireActivity())[PersonaViewModel::class.java]

        // Check if we're in edit mode
        isEditMode = arguments?.getBoolean("isEditMode", false) ?: false
        val personaId = arguments?.getLong("personaId", -1L) ?: -1L
        
        // Check for Google Sign-In data
        val googleEmail = arguments?.getString("googleEmail", "") ?: ""
        val googleNombres = arguments?.getString("googleNombres", "") ?: ""
        val googleApellidos = arguments?.getString("googleApellidos", "") ?: ""
        val googleAvatar = arguments?.getString("googleAvatar")
        isGoogleSignIn = arguments?.getBoolean("isGoogleSignIn", false) ?: false

        // Configurar el selector de fecha
        setupDatePicker()

        // Configurar listeners para validación en tiempo real
        setupTextChangeListeners()

        // Configurar selector de avatar
        setupAvatarSelection()

        // Iniciar animaciones y efectos
        startAnimations()
        startGlowEffects()
        startParticleAnimations()

        // Load existing data if in edit mode
        if (isEditMode && personaId != -1L) {
            loadExistingData(personaId)
            updateUIForEditMode()
        }
        
        // Pre-fill Google Sign-In data if available
        if (googleEmail.isNotEmpty()) {
            prefillGoogleData(googleEmail, googleNombres, googleApellidos, googleAvatar)
        }

        // Set up register button click listener
        registerButton.setOnClickListener {
            if (isEditMode) {
                updateUser()
            } else {
                registerUser()
            }
        }
    }
    
    private fun prefillGoogleData(email: String, nombres: String, apellidos: String, avatarUrl: String?) {
        // Pre-fill email field (read-only since it comes from Google)
        emailEditText.setText(email)
        emailEditText.isEnabled = false
        emailLayout.helperText = "Correo de Google (no editable)"
        
        // Pre-fill names if available
        if (nombres.isNotEmpty()) {
            nombresEditText.setText(nombres)
        }
        
        if (apellidos.isNotEmpty()) {
            apellidosEditText.setText(apellidos)
        }
        
        // Pre-fill avatar if available
        if (!avatarUrl.isNullOrEmpty()) {
            selectedAvatarUri = Uri.parse(avatarUrl)
            loadImageIntoAvatar(selectedAvatarUri)
        }
        
        // Update welcome text for Google users
        welcomeText.text = "¡Casi listo!"
        subtitleText.text = "Completa tu perfil para continuar"
        
        // Hacer los campos de contraseña opcionales visualmente
        passwordLayout.helperText = "Opcional - puedes iniciar sesión con Google"
        confirmPasswordLayout.helperText = "Opcional"
        
        // Cambiar el texto del botón
        registerButton.text = "Completar registro"
    }
    private fun startAnimations() {
        // Animación del ícono de curso con efecto de entrada espectacular
        val courseIconAnimator1 = ObjectAnimator.ofFloat(courseIcon, "alpha", 0f, 1f).apply {
            duration = 800
            startDelay = 200
        }
        val courseIconAnimator2 = ObjectAnimator.ofFloat(courseIcon, "scaleX", 0f, 1.2f, 1f).apply {
            duration = 800
            startDelay = 200
        }
        val courseIconAnimator3 = ObjectAnimator.ofFloat(courseIcon, "scaleY", 0f, 1.2f, 1f).apply {
            duration = 800
            startDelay = 200
        }

        // Animar header con efecto de typewriter
        val headerAnimator1 = ObjectAnimator.ofFloat(welcomeText, "alpha", 0f, 1f).apply {
            duration = 1000
            startDelay = 600
        }
        val headerAnimator2 = ObjectAnimator.ofFloat(subtitleText, "alpha", 0f, 1f).apply {
            duration = 800
            startDelay = 1200
        }

        // Animar card principal con bounce effect
        val cardAnimator1 = ObjectAnimator.ofFloat(view?.findViewById(R.id.registerCard), "alpha", 0f, 1f).apply {
            duration = 700
            startDelay = 400
        }
        val cardAnimator2 = ObjectAnimator.ofFloat(view?.findViewById(R.id.registerCard), "translationY", 50f, -10f, 0f).apply {
            duration = 700
            startDelay = 400
        }

        // Animar avatar con efecto de zoom elegante
        val avatarContainer = view?.findViewById<View>(R.id.avatarContainer)
        val avatarAnimator1 = ObjectAnimator.ofFloat(avatarContainer, "alpha", 0f, 1f).apply {
            duration = 600
            startDelay = 1000
        }
        val avatarAnimator2 = ObjectAnimator.ofFloat(avatarContainer, "translationY", 30f, 0f).apply {
            duration = 600
            startDelay = 1000
        }
        val avatarAnimator3 = ObjectAnimator.ofFloat(avatarContainer, "scaleX", 0.8f, 1.05f, 1f).apply {
            duration = 600
            startDelay = 1000
        }
        val avatarAnimator4 = ObjectAnimator.ofFloat(avatarContainer, "scaleY", 0.8f, 1.05f, 1f).apply {
            duration = 600
            startDelay = 1000
        }

        // Animar títulos de secciones
        val personalTitleAnimator1 = ObjectAnimator.ofFloat(personalInfoTitle, "alpha", 0f, 1f).apply {
            duration = 500
            startDelay = 1200
        }
        val personalTitleAnimator2 = ObjectAnimator.ofFloat(personalInfoTitle, "translationX", -20f, 0f).apply {
            duration = 500
            startDelay = 1200
        }

        val credentialsTitleAnimator1 = ObjectAnimator.ofFloat(credentialsTitle, "alpha", 0f, 1f).apply {
            duration = 500
            startDelay = 2200
        }
        val credentialsTitleAnimator2 = ObjectAnimator.ofFloat(credentialsTitle, "translationX", 20f, 0f).apply {
            duration = 500
            startDelay = 2200
        }

        // Animar campos de entrada con stagger effect
        val nameContainer = view?.findViewById<View>(R.id.nameContainer)
        val nameAnimator1 = ObjectAnimator.ofFloat(nameContainer, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 1400
        }
        val nameAnimator2 = ObjectAnimator.ofFloat(nameContainer, "translationX", -30f, 0f).apply {
            duration = 400
            startDelay = 1400
        }

        val emailAnimator1 = ObjectAnimator.ofFloat(emailLayout, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 1600
        }
        val emailAnimator2 = ObjectAnimator.ofFloat(emailLayout, "translationX", -30f, 0f).apply {
            duration = 400
            startDelay = 1600
        }

        val contactContainer = view?.findViewById<View>(R.id.contactContainer)
        val contactAnimator1 = ObjectAnimator.ofFloat(contactContainer, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 1800
        }
        val contactAnimator2 = ObjectAnimator.ofFloat(contactContainer, "translationX", 30f, 0f).apply {
            duration = 400
            startDelay = 1800
        }

        val userAnimator1 = ObjectAnimator.ofFloat(usernameLayout, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 2400
        }
        val userAnimator2 = ObjectAnimator.ofFloat(usernameLayout, "translationX", 30f, 0f).apply {
            duration = 400
            startDelay = 2400
        }

        val passAnimator1 = ObjectAnimator.ofFloat(passwordLayout, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 2600
        }
        val passAnimator2 = ObjectAnimator.ofFloat(passwordLayout, "translationX", -30f, 0f).apply {
            duration = 400
            startDelay = 2600
        }

        val confirmAnimator1 = ObjectAnimator.ofFloat(confirmPasswordLayout, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 2800
        }
        val confirmAnimator2 = ObjectAnimator.ofFloat(confirmPasswordLayout, "translationX", 30f, 0f).apply {
            duration = 400
            startDelay = 2800
        }

        // Animar botón con efecto heroico
        val buttonAnimator1 = ObjectAnimator.ofFloat(registerButton, "alpha", 0f, 1f).apply {
            duration = 600
            startDelay = 3000
        }
        val buttonAnimator2 = ObjectAnimator.ofFloat(registerButton, "translationY", 30f, -5f, 0f).apply {
            duration = 600
            startDelay = 3000
        }
        val buttonAnimator3 = ObjectAnimator.ofFloat(registerButton, "scaleX", 0.9f, 1.05f, 1f).apply {
            duration = 600
            startDelay = 3000
        }
        val buttonAnimator4 = ObjectAnimator.ofFloat(registerButton, "scaleY", 0.9f, 1.05f, 1f).apply {
            duration = 600
            startDelay = 3000
        }

        // Animar información final
        val infoAnimator = ObjectAnimator.ofFloat(platformInfo, "alpha", 0f, 1f).apply {
            duration = 500
            startDelay = 3400
        }

        // Ejecutar todas las animaciones
        AnimatorSet().apply {
            playTogether(
                courseIconAnimator1, courseIconAnimator2, courseIconAnimator3,
                headerAnimator1, headerAnimator2,
                cardAnimator1, cardAnimator2,
                avatarAnimator1, avatarAnimator2, avatarAnimator3, avatarAnimator4,
                personalTitleAnimator1, personalTitleAnimator2,
                nameAnimator1, nameAnimator2,
                emailAnimator1, emailAnimator2,
                contactAnimator1, contactAnimator2,
                credentialsTitleAnimator1, credentialsTitleAnimator2,
                userAnimator1, userAnimator2,
                passAnimator1, passAnimator2,
                confirmAnimator1, confirmAnimator2,
                buttonAnimator1, buttonAnimator2, buttonAnimator3, buttonAnimator4,
                infoAnimator
            )
            start()
        }
    }

    private fun startGlowEffects() {
        // Efecto de respiración para el glow del avatar
        val glowPulse = ObjectAnimator.ofFloat(avatarGlow, "alpha", 0.3f, 0.8f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        glowPulse.start()

        // Efecto de escala pulsante para el borde
        val glowScale = ObjectAnimator.ofFloat(avatarGlow, "scaleX", 1f, 1.1f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        val glowScaleY = ObjectAnimator.ofFloat(avatarGlow, "scaleY", 1f, 1.1f).apply {
            duration = 2000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
        
        AnimatorSet().apply {
            playTogether(glowScale, glowScaleY)
            start()
        }
    }

    private fun startParticleAnimations() {
        // Animaciones de partículas flotantes
        val particle1Animation = AnimationUtils.loadAnimation(requireContext(), R.anim.particle_float)
        val particle2Animation = AnimationUtils.loadAnimation(requireContext(), R.anim.particle_float)
        val particle3Animation = AnimationUtils.loadAnimation(requireContext(), R.anim.particle_float)

        // Delays escalonados para efecto más natural
        particle1.postDelayed({ particle1.startAnimation(particle1Animation) }, 500)
        particle2.postDelayed({ particle2.startAnimation(particle2Animation) }, 1500)
        particle3.postDelayed({ particle3.startAnimation(particle3Animation) }, 2500)
    }

    private fun setupAvatarSelection() {
        selectAvatarFab.setOnClickListener {
            checkCameraPermission()
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                openImagePicker()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Toast.makeText(
                    requireContext(),
                    "Se requiere permiso de cámara para tomar fotos",
                    Toast.LENGTH_SHORT
                ).show()
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun openImagePicker() {
        val options = arrayOf("Tomar foto", "Elegir de la galería", "Cancelar")
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar avatar")
            .setItems(options) { dialog, which ->
                when (which) {
                    0 -> takePictureFromCamera()
                    1 -> pickImageFromGallery()
                    2 -> dialog.dismiss()
                }
            }
            .show()
    }

    private fun takePictureFromCamera() {
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).also { intent ->
            intent.resolveActivity(requireActivity().packageManager)?.also {
                val photoFile: File? = try {
                    createImageFile()
                } catch (ex: IOException) {
                    Toast.makeText(
                        requireContext(),
                        "Error al crear el archivo de imagen",
                        Toast.LENGTH_SHORT
                    ).show()
                    null
                }

                photoFile?.also {
                    val photoURI = FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        it
                    )
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI)
                    takePictureLauncher.launch(intent)
                }
            }
        }
    }

    private fun pickImageFromGallery() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        pickImageLauncher.launch(intent)
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = requireContext().getExternalFilesDir(null)
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        ).apply {
            currentPhotoPath = absolutePath
        }
    }

    private fun loadImageIntoAvatar(uri: Uri?) {
        uri?.let {
            Glide.with(this)
                .load(it)
                .centerCrop()
                .placeholder(R.drawable.default_avatar)
                .error(R.drawable.default_avatar)
                .into(avatarImageView)
        }
    }

    private fun setupDatePicker() {
        fechaNacimientoEditText.setOnClickListener {
            // Calcular la fecha máxima (5 años atrás)
            val calendar = Calendar.getInstance()
            calendar.add(Calendar.YEAR, -5)
            val maxDate = calendar.timeInMillis
            
            // Calcular la fecha mínima (120 años atrás)
            calendar.add(Calendar.YEAR, -115) // -5 + (-115) = -120
            val minDate = calendar.timeInMillis

            val datePicker = MaterialDatePicker.Builder.datePicker()
                .setTitleText("Selecciona fecha de nacimiento")
                .setSelection(maxDate) // Fecha por defecto: hace 5 años
                .setCalendarConstraints(
                    com.google.android.material.datepicker.CalendarConstraints.Builder()
                        .setStart(minDate)
                        .setEnd(maxDate)
                        .build()
                )
                .build()

            datePicker.addOnPositiveButtonClickListener { selection ->
                // Use ISO 8601 format (yyyy-MM-dd) for compatibility with Supabase/PostgreSQL
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val selectedDate = Date(selection)
                fechaNacimientoEditText.setText(dateFormat.format(selectedDate))
                
                // Validar edad
                validateAge(selectedDate)
            }

            datePicker.show(parentFragmentManager, "DATE_PICKER")
        }
    }

    private fun validateAge(birthDate: Date): Boolean {
        val calendar = Calendar.getInstance()
        val today = calendar.time
        
        // Calcular años de diferencia
        calendar.time = birthDate
        val birthYear = calendar.get(Calendar.YEAR)
        val birthMonth = calendar.get(Calendar.MONTH)
        val birthDay = calendar.get(Calendar.DAY_OF_MONTH)
        
        calendar.time = today
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)
        
        var age = currentYear - birthYear
        
        // Ajustar si no ha cumplido años este año
        if (currentMonth < birthMonth || (currentMonth == birthMonth && currentDay < birthDay)) {
            age--
        }
        
        return when {
            age < 5 -> {
                fechaNacimientoLayout.error = "Debes ser mayor de 5 años para registrarte"
                false
            }
            age > 120 -> {
                fechaNacimientoLayout.error = "Por favor, ingresa una fecha de nacimiento válida"
                false
            }
            else -> {
                fechaNacimientoLayout.error = null
                true
            }
        }
    }

    private fun setupTextChangeListeners() {
        // Implement real-time validation if needed
        // For example:
        /*
        emailEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (s != null && !android.util.Patterns.EMAIL_ADDRESS.matcher(s).matches()) {
                    emailLayout.error = "Email inválido"
                } else {
                    emailLayout.error = null
                }
            }
        })
        */
    }

    private fun clearErrors() {
        nombresLayout.error = null
        apellidosLayout.error = null
        emailLayout.error = null
        telefonoLayout.error = null
        fechaNacimientoLayout.error = null
        usernameLayout.error = null
        passwordLayout.error = null
        confirmPasswordLayout.error = null
    }

    private fun validateAllFields(): Boolean {
        // Limpiar errores previos
        clearErrors()

        val nombres = nombresEditText.text.toString().trim()
        val apellidos = apellidosEditText.text.toString().trim()
        val email = emailEditText.text.toString().trim()
        val telefono = telefonoEditText.text.toString().trim()
        val fechaNacimiento = fechaNacimientoEditText.text.toString()
        val username = usernameEditText.text.toString().trim()
        val password = passwordEditText.text.toString()
        val confirmPassword = confirmPasswordEditText.text.toString()

        // Validar campos vacíos
        var hasError = false

        if (nombres.isEmpty()) {
            nombresLayout.error = "Campo requerido"
            hasError = true
        }

        if (apellidos.isEmpty()) {
            apellidosLayout.error = "Campo requerido"
            hasError = true
        }

        if (email.isEmpty()) {
            emailLayout.error = "Campo requerido"
            hasError = true
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailLayout.error = "Email inválido"
            hasError = true
        }

        if (telefono.isEmpty()) {
            telefonoLayout.error = "Campo requerido"
            hasError = true
        } else if (telefono.length < 10) {
            telefonoLayout.error = "Mínimo 10 dígitos"
            hasError = true
        }

        if (fechaNacimiento.isEmpty() || fechaNacimiento == "Seleccionar fecha") {
            fechaNacimientoLayout.error = "Campo requerido"
            hasError = true
        } else {
            // Validar edad
            try {
                // Use ISO 8601 format (yyyy-MM-dd)
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val birthDate = dateFormat.parse(fechaNacimiento)
                if (birthDate != null && !validateAge(birthDate)) {
                    hasError = true
                }
            } catch (e: Exception) {
                fechaNacimientoLayout.error = "Fecha inválida"
                hasError = true
            }
        }

        if (username.isEmpty()) {
            usernameLayout.error = "Campo requerido"
            hasError = true
        } else if (username.length < 4) {
            usernameLayout.error = "Mínimo 4 caracteres"
            hasError = true
        } else if (!username.matches(Regex("^[a-zA-Z0-9_]+$"))) {
            usernameLayout.error = "Solo letras, números y guion bajo"
            hasError = true
        }

        // En modo edición, la contraseña es opcional
        // En modo Google Sign-In, la contraseña también es opcional
        if (!isEditMode && !isGoogleSignIn) {
            if (password.isEmpty()) {
                passwordLayout.error = "Campo requerido"
                hasError = true
            } else if (password.length < 6) {
                passwordLayout.error = "Mínimo 6 caracteres"
                hasError = true
            }

            if (confirmPassword.isEmpty()) {
                confirmPasswordLayout.error = "Campo requerido"
                hasError = true
            } else if (password != confirmPassword) {
                confirmPasswordLayout.error = "Las contraseñas no coinciden"
                hasError = true
            }
        } else if (isGoogleSignIn) {
            // En modo Google, validar solo si se proporciona una contraseña opcional
            if (password.isNotEmpty()) {
                if (password.length < 6) {
                    passwordLayout.error = "Mínimo 6 caracteres"
                    hasError = true
                }
                
                if (confirmPassword.isEmpty()) {
                    confirmPasswordLayout.error = "Confirma la contraseña"
                    hasError = true
                } else if (password != confirmPassword) {
                    confirmPasswordLayout.error = "Las contraseñas no coinciden"
                    hasError = true
                }
            }
        } else {
            // En modo edición, validar solo si se proporciona una nueva contraseña
            if (password.isNotEmpty()) {
                if (password.length < 6) {
                    passwordLayout.error = "Mínimo 6 caracteres"
                    hasError = true
                }
                
                if (confirmPassword.isEmpty()) {
                    confirmPasswordLayout.error = "Confirma la nueva contraseña"
                    hasError = true
                } else if (password != confirmPassword) {
                    confirmPasswordLayout.error = "Las contraseñas no coinciden"
                    hasError = true
                }
            }
        }

        return !hasError
    }

    private fun registerUser() {
        if (!validateAllFields()) {
            return
        }

        val nombres = nombresEditText.text.toString().trim()
        val apellidos = apellidosEditText.text.toString().trim()
        val email = emailEditText.text.toString().trim()
        val telefono = telefonoEditText.text.toString().trim()
        val fechaNacimiento = fechaNacimientoEditText.text.toString()
        val username = usernameEditText.text.toString().trim()
        val password = passwordEditText.text.toString()
        val avatarUri = selectedAvatarUri?.toString()

        // Proceder con el registro
        lifecycleScope.launch {
            try {
                // Verificar si el nombre de usuario ya existe localmente
                val usernameExistsLocal = viewModel.checkUsernameExists(username)
                if (usernameExistsLocal) {
                    withContext(Dispatchers.Main) {
                        usernameLayout.error = "El nombre de usuario ya existe"
                    }
                    return@launch
                }

                // Verificar en Supabase si está configurado
                if (SupabaseClient.isConfigured()) {
                    // Verificar si el username ya existe en Supabase
                    val remoteUserByUsername = withContext(Dispatchers.IO) {
                        try {
                            SupabaseClient.fetchUsuarioByUsername(username)
                        } catch (e: Exception) {
                            Log.w("RegisterFragment", "Error checking username in Supabase: ${e.message}")
                            null
                        }
                    }
                    if (remoteUserByUsername != null) {
                        withContext(Dispatchers.Main) {
                            usernameLayout.error = "El nombre de usuario ya existe en el servidor"
                        }
                        return@launch
                    }

                    // Verificar si el email ya existe en Supabase
                    val remoteUserByEmail = withContext(Dispatchers.IO) {
                        try {
                            SupabaseClient.fetchUsuarioByEmail(email)
                        } catch (e: Exception) {
                            Log.w("RegisterFragment", "Error checking email in Supabase: ${e.message}")
                            null
                        }
                    }
                    if (remoteUserByEmail != null) {
                        withContext(Dispatchers.Main) {
                            emailLayout.error = "Este correo electrónico ya está registrado"
                            Toast.makeText(requireContext(), "El correo electrónico ya está en uso. Por favor usa otro.", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                }

                // Crear entidad Persona con el avatar
                val persona = Persona(
                    identificacion = username, // Usar username como identificacion
                    nombres = nombres,
                    apellidos = apellidos,
                    // email removed from Persona
                    telefono = telefono,
                    direccion = "", // Campo no requerido
                    fechaNacimiento = fechaNacimiento // Ya es String, no necesita parsear
                    // avatar removed from Persona
                    // esUsuario removed from Persona
                )

                // Hash the password once for both local and remote
                val hashedPassword = BCrypt.withDefaults().hashToString(12, password.toCharArray())

                // First, try to sync with Supabase to get remote IDs
                var remotePersonaId: Long? = null
                var remoteUserId: Long? = null
                
                if (SupabaseClient.isConfigured()) {
                    try {
                        // Check if a persona with this identificacion already exists
                        val existingPersona = withContext(Dispatchers.IO) {
                            try {
                                SupabaseClient.fetchPersonaByIdentificacion(username)
                            } catch (e: Exception) {
                                Log.w("RegisterFragment", "Error checking existing persona: ${e.message}")
                                null
                            }
                        }
                        
                        if (existingPersona != null) {
                            // Persona already exists, use the existing ID
                            remotePersonaId = existingPersona.id
                            Log.d("RegisterFragment", "Persona ya existe en Supabase con id: $remotePersonaId, reutilizando...")
                        } else {
                            // Insert persona to Supabase
                            remotePersonaId = withContext(Dispatchers.IO) { 
                                SupabaseClient.insertPersona(persona) 
                            }
                            Log.d("RegisterFragment", "Persona insertada en Supabase con id: $remotePersonaId")
                        }

                        if (remotePersonaId != null) {
                            // Create usuario with the remote persona_id
                            val usuarioForSupabase = Usuario(
                                usuario = username,
                                contrasena = hashedPassword,
                                persona_id = remotePersonaId,
                                email = email,
                                avatar = avatarUri
                            )
                            
                            remoteUserId = withContext(Dispatchers.IO) { 
                                SupabaseClient.insertUsuario(usuarioForSupabase) 
                            }
                            
                            if (remoteUserId != null) {
                                Log.d("RegisterFragment", "Usuario sincronizado con Supabase exitosamente, id: $remoteUserId")
                            } else {
                                Log.e("RegisterFragment", "Error: insertUsuario returned null")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(requireContext(), "Error al crear usuario en el servidor", Toast.LENGTH_LONG).show()
                                }
                                return@launch
                            }
                        } else {
                            Log.e("RegisterFragment", "Error: insertPersona returned null")
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Error al crear persona en el servidor", Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        Log.e("RegisterFragment", "Error al sincronizar con Supabase", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Error al sincronizar con Supabase: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        return@launch
                    }
                }

                // Now insert locally with the same IDs if available, or generate new ones
                val localPersonaId = withContext(Dispatchers.IO) {
                    val personaToInsert = if (remotePersonaId != null) {
                        persona.copy(id = remotePersonaId)
                    } else {
                        persona
                    }
                    viewModel.insertAndGetId(personaToInsert)
                }

                // Create usuario for local database
                val usuarioLocal = Usuario(
                    id = remoteUserId ?: 0, // Use remote ID if available
                    usuario = username,
                    contrasena = hashedPassword, // Already hashed
                    persona_id = remotePersonaId ?: localPersonaId,
                    email = email,
                    avatar = avatarUri
                )

                withContext(Dispatchers.IO) {
                    // Insert directly without re-hashing
                    val db = AppDatabase.getDatabase(requireContext())
                    db.usuarioDao().insertUsuario(usuarioLocal)
                }

                // Navegar según el modo de registro
                withContext(Dispatchers.Main) {
                    // Show success message
                    Toast.makeText(requireContext(), "¡Cuenta creada exitosamente!", Toast.LENGTH_SHORT).show()

                    if (isGoogleSignIn) {
                        // Si es Google Sign-In, obtener el usuario creado y crear sesión
                        val db = AppDatabase.getDatabase(requireContext())
                        val createdUser = withContext(Dispatchers.IO) {
                            db.usuarioDao().getUsuarioByUsername(username)
                        }
                        
                        createdUser?.let { user ->
                            // Crear sesión
                            val sessionManager = SessionManager.getInstance(requireContext())
                            sessionManager.createLoginSession(
                                username = user.usuario,
                                userId = user.id,
                                personaId = user.persona_id,
                                roleName = "user",
                                avatarUri = user.avatar
                            )
                            
                            // Guardar userId en SharedPreferences
                            val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                            sharedPrefs.edit().putLong("current_user_id", user.id).apply()
                        }
                        
                        // Navegar directamente a videoHomeFragment
                        findNavController().navigate(R.id.action_registerFragment_to_videoHomeFragment)
                    } else {
                        // Registro normal - navegar a login
                        findNavController().navigate(R.id.action_registerFragment_to_loginFragment)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al registrar: ${e.message}", Toast.LENGTH_SHORT).show()
                    e.printStackTrace() // Log the full stack trace for debugging
                }
            }
        }
    }

    // Helper method to parse date from input string
    private fun parseDateFromInput(dateString: String): Date? {
        return try {
            // Use ISO 8601 format (yyyy-MM-dd)
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            dateFormat.parse(dateString)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadExistingData(personaId: Long) {
        lifecycleScope.launch {
            try {
                // Load persona data
                personaToEdit = withContext(Dispatchers.IO) {
                    viewModel.getPersonaById(personaId)
                }

                // Load usuario data if exists
                usuarioToEdit = withContext(Dispatchers.IO) {
                    viewModel.getUsuarioByPersonaId(personaId)
                }

                withContext(Dispatchers.Main) {
                    populateFormWithExistingData()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al cargar datos: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun populateFormWithExistingData() {
        personaToEdit?.let { persona ->
            nombresEditText.setText(persona.nombres)
            apellidosEditText.setText(persona.apellidos)
            // email moved to Usuario
            telefonoEditText.setText(persona.telefono)
            
            // Set birth date (already a String in dd/MM/yyyy format)
            if (!persona.fechaNacimiento.isNullOrEmpty()) {
                fechaNacimientoEditText.setText(persona.fechaNacimiento)
            }
        }

        usuarioToEdit?.let { usuario ->
            usernameEditText.setText(usuario.usuario)
            emailEditText.setText(usuario.email) // Email is now in Usuario
            
            // Load avatar if exists (now in Usuario)
            usuario.avatar?.let { avatarUri ->
                try {
                    Glide.with(this)
                        .load(avatarUri)
                        .placeholder(R.drawable.default_avatar)
                        .error(R.drawable.default_avatar)
                        .into(avatarImageView)
                    selectedAvatarUri = Uri.parse(avatarUri)
                } catch (e: Exception) {
                    // Use default avatar if loading fails
                }
            }
            // Don't populate password fields for security reasons
        }
    }

    private fun updateUIForEditMode() {
        // Change button text
        registerButton.text = "ACTUALIZAR INFORMACIÓN"
        
        // Change title
        welcomeText.text = "Editar Información"
        subtitleText.text = "Actualiza tus datos personales"
        
        // Update platform info
        platformInfo.text = "Mantén tu información actualizada para una mejor experiencia"
        
        // Make username field non-editable if user exists
        usuarioToEdit?.let {
            usernameEditText.isEnabled = false
            usernameLayout.hint = "Nombre de Usuario (No editable)"
            usernameLayout.helperText = "El nombre de usuario no se puede modificar"
        }
    }

    private fun updateUser() {
        if (!validateAllFields()) {
            return
        }

        lifecycleScope.launch {
            try {
                val nombres = nombresEditText.text.toString().trim()
                val apellidos = apellidosEditText.text.toString().trim()
                val email = emailEditText.text.toString().trim()
                val telefono = telefonoEditText.text.toString().trim()
                val fechaNacimiento = fechaNacimientoEditText.text.toString() // Keep as String
                val avatarUri = selectedAvatarUri?.toString() ?: usuarioToEdit?.avatar // Avatar from Usuario

                // Update persona
                personaToEdit?.let { currentPersona ->
                    val updatedPersona = currentPersona.copy(
                        nombres = nombres,
                        apellidos = apellidos,
                        // email removed
                        telefono = telefono,
                        fechaNacimiento = fechaNacimiento
                        // avatar removed
                    )

                    withContext(Dispatchers.IO) {
                        viewModel.updatePersona(updatedPersona)
                    }
                }

                // Update usuario
                if (usuarioToEdit != null) {
                    val newPassword = passwordEditText.text.toString().trim()
                    var updatedUsuario = usuarioToEdit!!.copy(
                        email = email,
                        avatar = avatarUri
                    )
                    
                    if (newPassword.isNotEmpty()) {
                        updatedUsuario = updatedUsuario.copy(contrasena = newPassword)
                    }
                    
                    withContext(Dispatchers.IO) {
                        viewModel.updateUsuario(updatedUsuario)
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Información actualizada exitosamente", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al actualizar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}