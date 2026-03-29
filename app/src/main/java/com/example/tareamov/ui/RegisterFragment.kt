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
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.util.SessionManager
import android.content.Context
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.example.tareamov.config.TenantManager
import com.example.tareamov.data.entity.Institucion
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
    private lateinit var cedulaLayout: TextInputLayout
    private lateinit var generoLayout: TextInputLayout
    private lateinit var emailLayout: TextInputLayout
    private lateinit var telefonoLayout: TextInputLayout
    private lateinit var fechaNacimientoLayout: TextInputLayout
    private lateinit var usernameLayout: TextInputLayout
    private lateinit var passwordLayout: TextInputLayout
    private lateinit var confirmPasswordLayout: TextInputLayout
    private lateinit var institucionLayout: TextInputLayout

    // EditTexts
    private lateinit var nombresEditText: TextInputEditText
    private lateinit var apellidosEditText: TextInputEditText
    private lateinit var cedulaEditText: TextInputEditText
    private lateinit var emailEditText: TextInputEditText
    private lateinit var telefonoEditText: TextInputEditText
    private lateinit var fechaNacimientoEditText: TextInputEditText
    private lateinit var usernameEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var confirmPasswordEditText: TextInputEditText
    private lateinit var generoAutoComplete: AutoCompleteTextView
    private lateinit var institucionAutoComplete: AutoCompleteTextView

    private var instituciones: List<Institucion> = emptyList()
    private var selectedInstituciones: MutableList<Institucion> = mutableListOf()
    private var selectedInstitucionId: Long? = null
    private var selectedGenero: String? = null

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
        cedulaLayout = view.findViewById(R.id.cedulaLayout)
        generoLayout = view.findViewById(R.id.generoLayout)
        emailLayout = view.findViewById(R.id.emailLayout)
        telefonoLayout = view.findViewById(R.id.telefonoLayout)
        fechaNacimientoLayout = view.findViewById(R.id.fechaNacimientoLayout)
        usernameLayout = view.findViewById(R.id.usernameLayout)
        passwordLayout = view.findViewById(R.id.passwordLayout)
        confirmPasswordLayout = view.findViewById(R.id.confirmPasswordLayout)
        institucionLayout = view.findViewById(R.id.institucionLayout)

        // Inicializar EditTexts
        nombresEditText = view.findViewById(R.id.nombresEditText)
        apellidosEditText = view.findViewById(R.id.apellidosEditText)
        cedulaEditText = view.findViewById(R.id.cedulaEditText)
        emailEditText = view.findViewById(R.id.emailEditText)
        telefonoEditText = view.findViewById(R.id.telefonoEditText)
        fechaNacimientoEditText = view.findViewById(R.id.fechaNacimientoEditText)
        usernameEditText = view.findViewById(R.id.usernameEditText)
        passwordEditText = view.findViewById(R.id.passwordEditText)
        confirmPasswordEditText = view.findViewById(R.id.confirmPasswordEditText)
        generoAutoComplete = view.findViewById(R.id.generoAutoComplete)
        institucionAutoComplete = view.findViewById(R.id.institucionAutoComplete)

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

        setupInstitucionAutoComplete()
    setupGeneroAutoComplete()

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

        val instAnimator1 = ObjectAnimator.ofFloat(institucionLayout, "alpha", 0f, 1f).apply {
            duration = 400
            startDelay = 2000
        }
        val instAnimator2 = ObjectAnimator.ofFloat(institucionLayout, "translationX", -30f, 0f).apply {
            duration = 400
            startDelay = 2000
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
                instAnimator1, instAnimator2,
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

    private fun setupInstitucionAutoComplete() {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                BackendApiService.getInstitucionesCrossTenant()
                    .let { if (it is ApiResult.Error) BackendApiService.getInstituciones() else it }
            }
            if (result is ApiResult.Success) {
                instituciones = result.data
            }
        }

        // Tap the field → open multi-select dialog
        institucionAutoComplete.setOnClickListener { showInstitucionMultiSelectDialog() }
        institucionAutoComplete.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) showInstitucionMultiSelectDialog()
        }
    }

    private fun showInstitucionMultiSelectDialog() {
        if (instituciones.isEmpty()) {
            Toast.makeText(requireContext(), "Cargando instituciones...", Toast.LENGTH_SHORT).show()
            return
        }
        val nombres = instituciones.map { it.nombre }.toTypedArray()
        val checked = BooleanArray(instituciones.size) { i -> selectedInstituciones.any { s -> s.id == instituciones[i].id } }

        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Seleccionar institución(es)")
            .setMultiChoiceItems(nombres, checked) { _, which, isChecked ->
                val inst = instituciones[which]
                if (isChecked) {
                    if (selectedInstituciones.none { it.id == inst.id }) selectedInstituciones.add(inst)
                } else {
                    selectedInstituciones.removeAll { it.id == inst.id }
                }
            }
            .setPositiveButton("Aceptar") { _, _ ->
                updateInstitucionField()
                institucionLayout.error = null
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun updateInstitucionField() {
        institucionAutoComplete.setText(
            when (selectedInstituciones.size) {
                0 -> ""
                1 -> selectedInstituciones[0].nombre
                else -> "${selectedInstituciones.size} instituciones seleccionadas"
            },
            false
        )
    }

    private fun setupGeneroAutoComplete() {
        val generos = listOf("Masculino", "Femenino", "Otro", "Prefiero no decirlo")
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            generos
        )
        generoAutoComplete.setAdapter(adapter)
        generoAutoComplete.setOnItemClickListener { _, _, position, _ ->
            selectedGenero = generos[position]
            generoLayout.error = null
        }
        generoAutoComplete.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                selectedGenero = normalizeGenero(s?.toString())
            }
        })
    }

    private fun normalizeGenero(rawValue: String?): String? {
        val value = rawValue?.trim()?.lowercase(Locale.getDefault()) ?: return null
        return when (value) {
            "masculino" -> "Masculino"
            "femenino" -> "Femenino"
            "otro" -> "Otro"
            "prefiero no decirlo" -> "Prefiero no decirlo"
            else -> null
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
        cedulaLayout.error = null
        generoLayout.error = null
        emailLayout.error = null
        telefonoLayout.error = null
        fechaNacimientoLayout.error = null
        usernameLayout.error = null
        passwordLayout.error = null
        confirmPasswordLayout.error = null
        institucionLayout.error = null
    }

    private fun validateAllFields(): Boolean {
        // Limpiar errores previos
        clearErrors()

        val nombres = nombresEditText.text.toString().trim()
        val apellidos = apellidosEditText.text.toString().trim()
        val cedula = cedulaEditText.text.toString().trim()
        val genero = normalizeGenero(generoAutoComplete.text.toString())
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

        if (cedula.isEmpty()) {
            cedulaLayout.error = "Campo requerido"
            hasError = true
        } else if (!cedula.matches(Regex("^\\d{6,20}$"))) {
            cedulaLayout.error = "Ingresa una cédula válida"
            hasError = true
        }

        if (genero == null) {
            generoLayout.error = "Selecciona un género"
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

        if (selectedInstituciones.isEmpty()) {
            institucionLayout.error = "Selecciona al menos una institución"
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
        val cedula = cedulaEditText.text.toString().trim()
        val genero = normalizeGenero(generoAutoComplete.text.toString())
        val email = emailEditText.text.toString().trim()
        val telefono = telefonoEditText.text.toString().trim()
        val fechaNacimiento = fechaNacimientoEditText.text.toString()
        val username = usernameEditText.text.toString().trim()
        val password = passwordEditText.text.toString()
        val avatarUri = selectedAvatarUri?.toString()

        // Proceder con el registro
        lifecycleScope.launch {
            try {
                // Verificar si el username ya existe en el backend
                val usernameCheck = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(username)
                }
                if (usernameCheck is ApiResult.Success) {
                    withContext(Dispatchers.Main) {
                        usernameLayout.error = "El nombre de usuario ya existe"
                    }
                    return@launch
                }

                // Verificar si el email ya existe en el backend
                val emailCheck = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByEmail(email)
                }
                if (emailCheck is ApiResult.Success) {
                    withContext(Dispatchers.Main) {
                        emailLayout.error = "Este correo electrónico ya está registrado"
                        Toast.makeText(requireContext(), "El correo electrónico ya está en uso. Por favor usa otro.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // Verificar unicidad de contraseña en TODAS las bases de datos antes de crear nada
                if (!isEditMode && !isGoogleSignIn && password.isNotEmpty()) {
                    val passwordCheck = withContext(Dispatchers.IO) {
                        BackendApiService.checkPasswordUniqueness(password)
                    }
                    if (passwordCheck is ApiResult.Success) {
                        val available = passwordCheck.data.get("available")?.asBoolean ?: true
                        if (!available) {
                            withContext(Dispatchers.Main) {
                                passwordLayout.error = "Esta contraseña ya está en uso. Elige una diferente."
                                confirmPasswordLayout.error = null
                                Toast.makeText(requireContext(), "Esa contraseña ya la tiene otro usuario. Por favor elige una diferente.", Toast.LENGTH_LONG).show()
                            }
                            return@launch
                        }
                    }
                }

                // Collect tenantIds from all selected institutions
                val tenantIds = selectedInstituciones.mapNotNull { it.tenantId }.distinct()

                // Select the primary tenant BEFORE registration so BackendApiService.baseUrl
                // resolves to the correct server (e.g. INCAT server for INCAT institution)
                val primaryTenantId = tenantIds.firstOrNull()
                if (!primaryTenantId.isNullOrBlank()) {
                    TenantManager.selectTenant(requireContext(), primaryTenantId)
                }

                // Build persona inline — backend creates it in the correct tenant DB
                val personaInline = BackendApiService.PersonaInlineRequest(
                    nombres = nombres,
                    apellidos = apellidos,
                    cedula = cedula,
                    genero = genero,
                    telefono = telefono,
                    fecha_nacimiento = fechaNacimiento,
                    institucion_id = selectedInstituciones.firstOrNull()?.id
                )

                // Registrar usuario en el backend (password hashing is handled server-side)
                val registerResult = withContext(Dispatchers.IO) {
                    if (tenantIds.size > 1) {
                        BackendApiService.register(
                            username = username,
                            password = password,
                            email = email,
                            persona = personaInline,
                            tenantIds = tenantIds
                        )
                    } else {
                        BackendApiService.register(
                            username = username,
                            password = password,
                            email = email,
                            persona = personaInline,
                            tenantId = tenantIds.firstOrNull()
                        )
                    }
                }

                when (registerResult) {
                    is ApiResult.Success -> {
                        Log.d("RegisterFragment", "Usuario registrado correctamente")

                        // Token is automatically stored by BackendApiService.register()

                        // Assign default role (Rol 1)
                        if (BackendApiService.currentUserId > 0) {
                            withContext(Dispatchers.IO) {
                                val roleResult = BackendApiService.assignRole(BackendApiService.currentUserId, 1)
                                if (roleResult is ApiResult.Success) {
                                    Log.d("RegisterFragment", "Rol por defecto asignado correctamente")
                                } else {
                                    Log.w("RegisterFragment", "No se pudo asignar el rol por defecto")
                                }
                            }
                        }

                        withContext(Dispatchers.Main) {
                                val userId = BackendApiService.currentUserId
                                val personaIdFromResponse = registerResult.data.user?.get("persona_id")?.asLong ?: 0L
                                val sessionManager = SessionManager.getInstance(requireContext())

                                sessionManager.createLoginSession(
                                    username = username,
                                    userId = userId,
                                    personaId = personaIdFromResponse,
                                    roleName = "user",
                                    avatarUri = avatarUri
                                )
                                sessionManager.addRole(1)
                                sessionManager.setAdminStatus(false)

                                val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                sharedPrefs.edit().putLong("current_user_id", userId).apply()

                            Toast.makeText(requireContext(), "¡Cuenta creada exitosamente!", Toast.LENGTH_SHORT).show()
                                findNavController().navigate(R.id.action_registerFragment_to_videoHomeFragment)
                        }
                    }
                    is ApiResult.Error -> {
                        Log.e("RegisterFragment", "Error al registrar el usuario: ${registerResult.message}")
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Error al registrar usuario: ${registerResult.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("RegisterFragment", "Error inesperado al registrar el usuario", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al registrar: ${e.message}", Toast.LENGTH_SHORT).show()
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
                Log.e("RegisterFragment", "Error al cargar los datos existentes", e)
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
            cedulaEditText.setText((persona.cedula ?: persona.identificacion).toString())
            telefonoEditText.setText(persona.telefono)
            persona.genero?.let {
                selectedGenero = normalizeGenero(it)
                generoAutoComplete.setText(selectedGenero ?: it, false)
            }
            
            if (!persona.fechaNacimiento.isNullOrEmpty()) {
                fechaNacimientoEditText.setText(persona.fechaNacimiento)
            }

            persona.institucionId?.let { instId ->
                selectedInstitucionId = instId
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        BackendApiService.getInstituciones()
                    }
                    if (result is ApiResult.Success) {
                        val inst = result.data.find { it.id == instId }
                        inst?.let { institucionAutoComplete.setText(it.nombre, false) }
                    }
                }
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
                val cedula = cedulaEditText.text.toString().trim()
                val genero = normalizeGenero(generoAutoComplete.text.toString())
                val email = emailEditText.text.toString().trim()
                val telefono = telefonoEditText.text.toString().trim()
                val fechaNacimiento = fechaNacimientoEditText.text.toString()
                val avatarUri = selectedAvatarUri?.toString() ?: usuarioToEdit?.avatar

                // Update persona via backend
                personaToEdit?.let { currentPersona ->
                    val cedulaValue = cedula.toLongOrNull()
                    if (cedulaValue == null) {
                        withContext(Dispatchers.Main) {
                            cedulaLayout.error = "Ingresa una cédula válida"
                        }
                        return@launch
                    }

                    val personaUpdates = mutableMapOf<String, Any?>(
                        "nombres" to nombres,
                        "apellidos" to apellidos,
                        "cedula" to cedulaValue,
                        "telefono" to telefono,
                        "fechaNacimiento" to fechaNacimiento,
                        "genero" to genero
                    )
                    if (selectedInstitucionId != null) {
                        personaUpdates["institucionId"] = selectedInstitucionId
                    }

                    val personaResult = withContext(Dispatchers.IO) {
                        BackendApiService.updatePersona(currentPersona.id, personaUpdates)
                    }
                    if (personaResult is ApiResult.Error) {
                        Log.w("RegisterFragment", "Error al actualizar la persona: ${personaResult.message}")
                    }
                }

                // Update usuario via backend
                if (usuarioToEdit != null) {
                    val userUpdates = mutableMapOf<String, Any?>(
                        "email" to email,
                        "avatar" to avatarUri
                    )

                    val newPassword = passwordEditText.text.toString().trim()
                    if (newPassword.isNotEmpty()) {
                        userUpdates["contrasena"] = newPassword
                    }

                    val userResult = withContext(Dispatchers.IO) {
                        BackendApiService.updateMyProfile(userUpdates)
                    }
                    if (userResult is ApiResult.Error) {
                        Log.w("RegisterFragment", "Error al actualizar el usuario: ${userResult.message}")
                    }
                }

                com.example.tareamov.util.AppCache.invalidateProfile()
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Información actualizada exitosamente", Toast.LENGTH_SHORT).show()
                    findNavController().navigateUp()
                }
            } catch (e: Exception) {
                Log.e("RegisterFragment", "Error al actualizar la información del usuario", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al actualizar: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}