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
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import android.widget.VideoView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import eightbitlab.com.blurview.BlurView
import eightbitlab.com.blurview.RenderScriptBlur
import android.view.ViewOutlineProvider
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.VideoManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VideoDetailsFragment : Fragment() {
    private lateinit var videoUri: Uri
    private lateinit var sessionManager: SessionManager
    private var videoId: Long = 0L // Store the video ID from the previous fragment
    private var thumbnailUri: Uri? = null // URI de la miniatura seleccionada
    private lateinit var thumbnailPickerLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>
    
    // Edit mode variables
    private var isEditMode = false
    private var initialTitle = ""
    private var initialDescription = ""
    private var initialIsPaid = false
    
    // Brain loading animator
    private var brainLoadingAnimator: com.example.tareamov.util.BrainLoadingAnimator? = null
    private var uploadStartTime: Long = 0L
    
    // Thumbnail extractor
    private lateinit var thumbnailExtractor: com.example.tareamov.util.VideoThumbnailExtractor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            videoUri = it.getParcelable("videoUri") ?: Uri.EMPTY
            videoId = it.getLong("videoId", 0L) // Get the video ID
            
            // Check for edit mode
            isEditMode = it.getBoolean("isEditMode", false)
            if (isEditMode) {
                initialTitle = it.getString("title", "")
                initialDescription = it.getString("description", "")
                initialIsPaid = it.getBoolean("isPaid", false)
            }
        }
        // Initialize SessionManager
        sessionManager = SessionManager.getInstance(requireContext())
        
        // Initialize thumbnail extractor
        thumbnailExtractor = com.example.tareamov.util.VideoThumbnailExtractor(requireContext())
        
        // Initialize thumbnail picker launcher using OpenDocument to obtain proper URI permissions
        thumbnailPickerLauncher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri != null) {
                // Copy selected SAF URI into app cache and use the copied file for preview and upload.
                lifecycleScope.launch(Dispatchers.IO) {
                    val copied = try {
                        copyUriToCacheFile(uri)
                    } catch (e: Exception) {
                        Log.e("VideoDetailsFragment", "Error copiando URI a cache", e)
                        null
                    }

                    if (copied != null) {
                        thumbnailUri = androidx.core.content.FileProvider.getUriForFile(
                            requireContext(),
                            requireContext().packageName + ".fileprovider",
                            copied
                        )

                        withContext(Dispatchers.Main) {
                            try {
                                val thumbnailPreview = view?.findViewById<android.widget.ImageView>(R.id.thumbnailPreview)
                                val thumbnailPlaceholder = view?.findViewById<android.view.ViewGroup>(R.id.thumbnailPlaceholder)
                                val thumbnailSelectedText = view?.findViewById<android.widget.TextView>(R.id.thumbnailSelectedText)

                                if (thumbnailPreview != null) {
                                    com.bumptech.glide.Glide.with(requireContext())
                                        .load(thumbnailUri)
                                        .override(1280, 720)
                                        .centerCrop()
                                        .into(thumbnailPreview)

                                    thumbnailPreview.visibility = View.VISIBLE
                                    thumbnailPlaceholder?.visibility = View.GONE
                                    thumbnailSelectedText?.visibility = View.VISIBLE
                                }

                                Log.d("VideoDetailsFragment", "✅ Thumbnail copied and preview loaded: $thumbnailUri")
                            } catch (e: Exception) {
                                Log.e("VideoDetailsFragment", "Error mostrando miniatura copiada", e)
                                Toast.makeText(requireContext(), "Error cargando vista previa", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "No se pudo copiar la miniatura seleccionada", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_video_details, container, false)
    }

    private var isPaidCourse = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Setup BlurView for header
        val headerLayout = view.findViewById<BlurView>(R.id.headerLayout)
        val radius = 20f
        val decorView = requireActivity().window.decorView
        val rootView = view as ViewGroup
        // val windowBackground = decorView.background // Not used to avoid blocking video

        headerLayout.setupWith(rootView, RenderScriptBlur(requireContext()))
            //.setFrameClearDrawable(windowBackground)
            .setBlurRadius(radius)
            .setBlurAutoUpdate(true)
            .setOverlayColor(android.graphics.Color.parseColor("#33000000")) // Match ExploreFragment
            
        headerLayout.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                val cornerRadius = view.context.resources.displayMetrics.density * 24 // 24dp
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
            }
        }
        headerLayout.clipToOutline = true

        // Set up video preview
        val videoPreview = view.findViewById<VideoView>(R.id.videoPreview)
        videoPreview.setVideoURI(videoUri)
        videoPreview.start()

        // Set up back button
        view.findViewById<View>(R.id.backButton).setOnClickListener {
            findNavController().navigateUp()
        }

        // Set up next button
        view.findViewById<View>(R.id.nextButton).setOnClickListener {
            saveVideoDetails()
        }

        // Set up course type selection
        setupCourseTypeSelection(view)
        
        // Set up thumbnail selection button
        view.findViewById<View>(R.id.selectThumbnailButton)?.setOnClickListener {
            openThumbnailPicker()
        }
        
        // Initialize fields if in edit mode
        if (isEditMode) {
            view.findViewById<EditText>(R.id.titleEditText).setText(initialTitle)
            view.findViewById<EditText>(R.id.descriptionEditText).setText(initialDescription)
            
            // Update selection state
            isPaidCourse = initialIsPaid
            val optionFree = view.findViewById<View>(R.id.optionFree)
            val optionPaid = view.findViewById<View>(R.id.optionPaid)
            val iconFree = view.findViewById<android.widget.ImageView>(R.id.iconFree)
            val iconPaid = view.findViewById<android.widget.ImageView>(R.id.iconPaid)
            val textFree = view.findViewById<android.widget.TextView>(R.id.textFree)
            val textPaid = view.findViewById<android.widget.TextView>(R.id.textPaid)
            
            updateSelectionState(isPaidCourse, optionFree, optionPaid, iconFree, iconPaid, textFree, textPaid)
            
            // Update button text
            val nextButton = view.findViewById<View>(R.id.nextButton)
            if (nextButton is android.widget.TextView) {
                nextButton.text = "Guardar Cambios"
            } else if (nextButton is ViewGroup) {
                // Try to find a TextView inside
                for (i in 0 until nextButton.childCount) {
                    val child = nextButton.getChildAt(i)
                    if (child is android.widget.TextView) {
                        child.text = "Guardar Cambios"
                        break
                    }
                }
            }
        }
    }
    
    /**
     * Abre el selector de imágenes para elegir una miniatura
     */
    private fun openThumbnailPicker() {
        // Launch system file picker using Storage Access Framework for images
        try {
            thumbnailPickerLauncher.launch(arrayOf("image/*"))
        } catch (e: Exception) {
            Log.e("VideoDetailsFragment", "Error launching thumbnail picker", e)
            Toast.makeText(requireContext(), "No se pudo abrir el selector de miniaturas", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * Muestra la pantalla de carga profesional con animación de cerebro
     */
    private fun showProfessionalLoading(title: String = "Procesando Video", status: String = "Preparando...") {
        view?.let { rootView ->
            val loadingOverlay = rootView.findViewById<View>(R.id.loadingOverlay)
            val loadingTitle = rootView.findViewById<android.widget.TextView>(R.id.loadingTitle)
            val loadingStatus = rootView.findViewById<android.widget.TextView>(R.id.loadingStatus)
            val brainIcon = rootView.findViewById<android.widget.ImageView>(R.id.brainIcon)
            val pulsingCircle = rootView.findViewById<View>(R.id.pulsingCircle)
            val particle1 = rootView.findViewById<View>(R.id.particle1)
            val particle2 = rootView.findViewById<View>(R.id.particle2)
            val particle3 = rootView.findViewById<View>(R.id.particle3)
            val particle4 = rootView.findViewById<View>(R.id.particle4)
            
            loadingOverlay?.visibility = View.VISIBLE
            loadingTitle?.text = title
            loadingStatus?.text = status
            
            uploadStartTime = System.currentTimeMillis()
            
            // Iniciar animaciones
            if (brainIcon != null && pulsingCircle != null && 
                particle1 != null && particle2 != null && 
                particle3 != null && particle4 != null) {
                
                brainLoadingAnimator = com.example.tareamov.util.BrainLoadingAnimator(
                    brainIcon, pulsingCircle, particle1, particle2, particle3, particle4
                )
                brainLoadingAnimator?.startAnimations()
            }
        }
    }
    
    /**
     * Oculta la pantalla de carga profesional
     */
    private fun hideProfessionalLoading() {
        view?.let { rootView ->
            val loadingOverlay = rootView.findViewById<View>(R.id.loadingOverlay)
            loadingOverlay?.visibility = View.GONE
            
            // Detener animaciones
            brainLoadingAnimator?.stopAnimations()
            brainLoadingAnimator = null
        }
    }
    
    /**
     * Actualiza el progreso de la carga profesional
     */
    private fun updateLoadingProgress(
        progress: Int,
        status: String = "Subiendo video...",
        showSpeed: Boolean = true
    ) {
        view?.let { rootView ->
            val loadingProgressBar = rootView.findViewById<android.widget.ProgressBar>(R.id.loadingProgressBar)
            val loadingPercentage = rootView.findViewById<android.widget.TextView>(R.id.loadingPercentage)
            val loadingStatus = rootView.findViewById<android.widget.TextView>(R.id.loadingStatus)
            val loadingSpeed = rootView.findViewById<android.widget.TextView>(R.id.loadingSpeed)
            
            loadingProgressBar?.progress = progress
            loadingPercentage?.text = "$progress%"
            loadingStatus?.text = status
            
            if (showSpeed && uploadStartTime > 0) {
                val elapsedSeconds = (System.currentTimeMillis() - uploadStartTime) / 1000
                if (elapsedSeconds > 0 && progress > 0) {
                    val speedMbps = (progress.toFloat() / elapsedSeconds).toInt()
                    loadingSpeed?.text = "${speedMbps}% /seg"
                }
            }
        }
    }

    private fun setupCourseTypeSelection(view: View) {
        val optionFree = view.findViewById<View>(R.id.optionFree)
        val optionPaid = view.findViewById<View>(R.id.optionPaid)
        val iconFree = view.findViewById<android.widget.ImageView>(R.id.iconFree)
        val iconPaid = view.findViewById<android.widget.ImageView>(R.id.iconPaid)
        val textFree = view.findViewById<android.widget.TextView>(R.id.textFree)
        val textPaid = view.findViewById<android.widget.TextView>(R.id.textPaid)

        // Default state: Free selected
        updateSelectionState(false, optionFree, optionPaid, iconFree, iconPaid, textFree, textPaid)

        optionFree.setOnClickListener {
            isPaidCourse = false
            updateSelectionState(false, optionFree, optionPaid, iconFree, iconPaid, textFree, textPaid)
        }

        optionPaid.setOnClickListener {
            isPaidCourse = true
            updateSelectionState(true, optionFree, optionPaid, iconFree, iconPaid, textFree, textPaid)
        }
    }

    private fun updateSelectionState(
        isPaid: Boolean,
        optionFree: View,
        optionPaid: View,
        iconFree: android.widget.ImageView,
        iconPaid: android.widget.ImageView,
        textFree: android.widget.TextView,
        textPaid: android.widget.TextView
    ) {
        val selectedBg = R.drawable.bg_rounded_card_selected
        val unselectedBg = R.drawable.bg_rounded_input
        val selectedColor = android.graphics.Color.parseColor("#3b82f6") // Blue
        val unselectedColor = android.graphics.Color.parseColor("#71717a") // Gray
        val whiteColor = android.graphics.Color.parseColor("#FFFFFF")

        if (!isPaid) {
            // Free selected
            optionFree.setBackgroundResource(selectedBg)
            optionPaid.setBackgroundResource(unselectedBg)
            
            iconFree.setColorFilter(selectedColor)
            textFree.setTextColor(whiteColor)
            
            iconPaid.setColorFilter(unselectedColor)
            textPaid.setTextColor(unselectedColor)
        } else {
            // Paid selected
            optionFree.setBackgroundResource(unselectedBg)
            optionPaid.setBackgroundResource(selectedBg)
            
            iconFree.setColorFilter(unselectedColor)
            textFree.setTextColor(unselectedColor)
            
            iconPaid.setColorFilter(selectedColor)
            textPaid.setTextColor(whiteColor)
        }
    }

    private fun saveVideoDetails() {
        val title = view?.findViewById<EditText>(R.id.titleEditText)?.text.toString()
        val description = view?.findViewById<EditText>(R.id.descriptionEditText)?.text.toString()

        // isPaidCourse is already updated by click listeners

        if (title.isBlank()) {
            context?.let { Toast.makeText(it, "Por favor ingresa un título", Toast.LENGTH_SHORT).show() }
            return
        }

        // Get current username from SessionManager
        val currentUsername = sessionManager.getUsername()
        if (currentUsername == null) {
            context?.let { Toast.makeText(it, "Error: Usuario no autenticado", Toast.LENGTH_LONG).show() }
            return
        }

        // If in edit mode, proceed directly
        if (isEditMode) {
            proceedWithVideoSave(title, description, currentUsername, createCourse = true)
            return
        }

        // Show dialog asking if user wants to create a course
        showCreateCourseDialog(title, description, currentUsername)
    }

    /**
     * Shows a dialog asking if the user wants to create a course with this video
     */
    private fun showCreateCourseDialog(title: String, description: String, currentUsername: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_course, null)
        
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setCancelable(false)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val confirmButton = dialogView.findViewById<android.widget.TextView>(R.id.confirmCreateCourseButton)
        val cancelButton = dialogView.findViewById<android.widget.TextView>(R.id.cancelCreateCourseButton)
        
        confirmButton.setOnClickListener {
            dialog.dismiss()
            proceedWithVideoSave(title, description, currentUsername, createCourse = true)
        }
        
        cancelButton.setOnClickListener {
            dialog.dismiss()
            proceedWithVideoSave(title, description, currentUsername, createCourse = false)
        }
        
        dialog.show()
    }

    /**
     * Proceeds with video save, optionally creating a course
     */
    private fun proceedWithVideoSave(title: String, description: String, currentUsername: String, createCourse: Boolean) {
        // Show professional loading screen
        showProfessionalLoading(
            if (isEditMode) "Actualizando Curso" else if (createCourse) "Creando tu Curso" else "Subiendo Video",
            if (isEditMode) "Guardando cambios..." else "Preparando archivos..."
        )

        // Update the existing video record instead of creating a new one
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get user ID for foreign key
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername)
                }

                if (userId == null || userId <= 0) {
                    hideProfessionalLoading()
                    if (isAdded) context?.let { Toast.makeText(it, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show() }
                    return@launch
                }

                val activity = activity as? com.example.tareamov.MainActivity
                if (activity == null) {
                    hideProfessionalLoading()
                    if (isAdded) context?.let { Toast.makeText(it, "Error: Contexto inválido", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                
                if (isEditMode) {
                    // === UPDATE LOGIC ===
                    updateLoadingProgress(10, "Verificando cambios...", false)
                    
                    // Only check for duplicates if title changed
                    if (title != initialTitle) {
                        val duplicateNew = withContext(Dispatchers.IO) {
                            activity.syncRepository.isTitleExistsInSupabase(title)
                        }
                        if (duplicateNew) {
                            hideProfessionalLoading()
                            if (isAdded) context?.let { Toast.makeText(it, "Ya existe un video/curso con este título. Elige otro título.", Toast.LENGTH_LONG).show() }
                            return@launch
                        }
                    }
                    
                    // Upload NEW thumbnail if selected
                    var thumbnailUrl: String? = null
                    if (thumbnailUri != null) {
                        updateLoadingProgress(30, "Subiendo nueva miniatura...", false)
                        val thumbnailResult = withContext(Dispatchers.IO) {
                            try {
                                com.example.tareamov.service.CloudflareR2Service.uploadThumbnail(
                                    context = requireContext(),
                                    thumbnailUri = thumbnailUri!!,
                                    courseId = null
                                ) { progress ->
                                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                        updateLoadingProgress(30 + (progress * 0.4).toInt(), "Subiendo miniatura: $progress%", false)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("VideoDetailsFragment", "Error uploading thumbnail", e)
                                com.example.tareamov.service.CloudflareR2Service.UploadResult.Error("Error: ${e.message}")
                            }
                        }
                        
                        if (thumbnailResult is com.example.tareamov.service.CloudflareR2Service.UploadResult.Success) {
                            thumbnailUrl = thumbnailResult.url
                        }
                    }
                    
                    updateLoadingProgress(80, "Actualizando base de datos...", false)
                    
                    // Update VideoData and Course in Supabase
                    // We need to fetch the current video data to get the courseId if we don't have it
                    // But we have videoId.
                    
                    val success = withContext(Dispatchers.IO) {
                        // Fetch current video to get courseId
                        val currentVideo = com.example.tareamov.service.SupabaseClient.fetchVideoById(videoId)
                        if (currentVideo != null) {
                            // Update Video
                            val updatedVideo = currentVideo.copy(
                                title = title,
                                description = description,
                                isPaid = isPaidCourse,
                                price = if (isPaidCourse) 9.99 else null,
                                thumbnailUri = thumbnailUrl ?: currentVideo.thumbnailUri
                            )
                            val videoUpdateSuccess = com.example.tareamov.service.SupabaseClient.updateVideo(updatedVideo)
                            
                            // Update Course
                            val courseId = currentVideo.courseId
                            if (courseId != null && courseId > 0) {
                                val currentCourse = com.example.tareamov.service.SupabaseClient.fetchCourseById(courseId)
                                if (currentCourse != null) {
                                    val updatedCourse = currentCourse.copy(
                                        title = title,
                                        description = description,
                                        isPremium = isPaidCourse,
                                        price = if (isPaidCourse) 9.99 else 0.0,
                                        thumbnailUri = thumbnailUrl ?: currentCourse.thumbnailUri
                                    )
                                    com.example.tareamov.service.SupabaseClient.updateCourseById(updatedCourse.id, updatedCourse)
                                }
                            }
                            videoUpdateSuccess
                        } else {
                            false
                        }
                    }
                    
                    if (success) {
                        updateLoadingProgress(100, "¡Actualizado exitosamente! ✓", false)
                        
                        kotlinx.coroutines.delay(800)
                        hideProfessionalLoading()
                        
                        if (isAdded) {
                            val navController = findNavController()
                            
                            // Send result via NavBackStackEntry for more reliable delivery
                            try {
                                val backStackEntry = navController.previousBackStackEntry
                                if (backStackEntry != null) {
                                    backStackEntry.savedStateHandle["videoUpdated"] = true
                                    backStackEntry.savedStateHandle["updatedVideoId"] = videoId
                                    backStackEntry.savedStateHandle["updatedTitle"] = title
                                    backStackEntry.savedStateHandle["updatedDescription"] = description
                                    backStackEntry.savedStateHandle["updatedIsPaid"] = isPaidCourse
                                    backStackEntry.savedStateHandle["updatedThumbnailUri"] = thumbnailUrl
                                    Log.d("VideoDetailsFragment", "SavedStateHandle updated for video ID: $videoId, title: $title")
                                } else {
                                    Log.w("VideoDetailsFragment", "No previousBackStackEntry found")
                                }
                            } catch (e: Exception) {
                                Log.e("VideoDetailsFragment", "Error setting savedStateHandle", e)
                            }
                            
                            // Also send via FragmentManager as backup
                            try {
                                val resultBundle = Bundle().apply {
                                    putLong("updatedVideoId", videoId)
                                    putString("updatedTitle", title)
                                    putString("updatedDescription", description)
                                    putBoolean("updatedIsPaid", isPaidCourse)
                                    putString("updatedThumbnailUri", thumbnailUrl)
                                }
                                requireActivity().supportFragmentManager.setFragmentResult("videoUpdated", resultBundle)
                                Log.d("VideoDetailsFragment", "Fragment result sent via FragmentManager for videoId: $videoId")
                            } catch (e: Exception) {
                                Log.e("VideoDetailsFragment", "Error sending fragment result", e)
                            }
                            
                            Toast.makeText(context, "Video actualizado correctamente", Toast.LENGTH_SHORT).show()
                            navController.navigateUp()
                        }
                    } else {
                        hideProfessionalLoading()
                        if (isAdded) Toast.makeText(context, "Error al actualizar el video", Toast.LENGTH_SHORT).show()
                    }
                    
                } else {
                    // === CREATE LOGIC (Existing) ===
                    
                    // Verificar título único
                    updateLoadingProgress(5, "Verificando título...", false)
                    val duplicateNew = withContext(Dispatchers.IO) {
                        activity.syncRepository.isTitleExistsInSupabase(title)
                    }

                    if (duplicateNew) {
                        hideProfessionalLoading()
                        if (isAdded) context?.let { Toast.makeText(it, "Ya existe un video/curso con este título. Elige otro título.", Toast.LENGTH_LONG).show() }
                        return@launch
                    }

                    // 🎬 GENERAR MINIATURA AUTOMÁTICAMENTE si no se seleccionó una
                    if (thumbnailUri == null) {
                        updateLoadingProgress(8, "Generando miniatura del video...", false)
                        Log.d("VideoDetailsFragment", "🎨 No se seleccionó miniatura, generando automáticamente...")
                        
                        thumbnailUri = withContext(Dispatchers.IO) {
                            try {
                                thumbnailExtractor.extractThumbnailFromVideo(videoUri)
                            } catch (e: Exception) {
                                Log.e("VideoDetailsFragment", "Error extrayendo miniatura automática", e)
                                null
                            }
                        }
                        
                        if (thumbnailUri != null) {
                            Log.d("VideoDetailsFragment", "✅ Miniatura generada automáticamente: $thumbnailUri")
                            
                            // Mostrar la miniatura generada en la vista previa
                            withContext(Dispatchers.Main) {
                                try {
                                    val thumbnailPreview = view?.findViewById<android.widget.ImageView>(R.id.thumbnailPreview)
                                    val thumbnailPlaceholder = view?.findViewById<android.view.ViewGroup>(R.id.thumbnailPlaceholder)
                                    val thumbnailSelectedText = view?.findViewById<android.widget.TextView>(R.id.thumbnailSelectedText)
                                    
                                    if (thumbnailPreview != null) {
                                        com.bumptech.glide.Glide.with(requireContext())
                                            .load(thumbnailUri)
                                            .override(1280, 720)
                                            .centerCrop()
                                            .into(thumbnailPreview)
                                        
                                        thumbnailPreview.visibility = View.VISIBLE
                                        thumbnailPlaceholder?.visibility = View.GONE
                                        thumbnailSelectedText?.visibility = View.VISIBLE
                                        thumbnailSelectedText?.text = "✓ Miniatura generada automáticamente"
                                    }
                                    
                                    Toast.makeText(
                                        requireContext(),
                                        "📸 Miniatura generada desde el video",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } catch (e: Exception) {
                                    Log.e("VideoDetailsFragment", "Error mostrando miniatura generada", e)
                                }
                            }
                        } else {
                            Log.w("VideoDetailsFragment", "⚠️ No se pudo generar miniatura automática")
                        }
                    }

                    // Upload video to Cloudflare R2 if configured
                    var finalVideoUri = videoUri.toString()
                    
                    Log.d("VideoDetailsFragment", "🔍 Verificando configuración R2: isConfigured=${com.example.tareamov.service.CloudflareR2Service.isConfigured()}")
                    
                    if (com.example.tareamov.service.CloudflareR2Service.isConfigured()) {
                        updateLoadingProgress(10, "Verificando conexión con la nube...", false)
                        
                        // Primero probar la conexión
                        val connectionOk = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.CloudflareR2Service.testConnection()
                        }
                        
                        if (!connectionOk) {
                            Log.e("VideoDetailsFragment", "❌ No se pudo conectar a R2")
                            withContext(Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    requireContext(),
                                    "No se pudo conectar a Cloudflare R2. Usando almacenamiento local.",
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        } else {
                            updateLoadingProgress(15, "Conectado a la nube, iniciando subida...", false)
                            
                            Log.d("VideoDetailsFragment", "📤 Iniciando subida a R2...")
                            Log.d("VideoDetailsFragment", "   Video URI: $videoUri")
                            Log.d("VideoDetailsFragment", "   Custom filename: ${title.replace(Regex("[^a-zA-Z0-9]"), "_")}")
                            
                            val uploadResult = withContext(Dispatchers.IO) {
                                try {
                                    com.example.tareamov.service.CloudflareR2Service.uploadVideo(
                                        context = requireContext(),
                                        videoUri = videoUri,
                                        customFileName = title.replace(Regex("[^a-zA-Z0-9]"), "_")
                                    ) { progress ->
                                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                            // Mapear progreso de 20% a 70% (reservar 70-100% para guardado)
                                            val mappedProgress = 20 + (progress * 0.5).toInt()
                                            updateLoadingProgress(mappedProgress, "Subiendo video: $progress%", true)
                                            Log.d("VideoDetailsFragment", "📊 Upload progress: $progress%")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("VideoDetailsFragment", "❌ Exception en uploadVideo", e)
                                    com.example.tareamov.service.CloudflareR2Service.UploadResult.Error("Exception: ${e.message}")
                                }
                            }
                            
                            when (uploadResult) {
                                is com.example.tareamov.service.CloudflareR2Service.UploadResult.Success -> {
                                    finalVideoUri = uploadResult.url
                                    Log.d("VideoDetailsFragment", "✅ Video uploaded to R2: $finalVideoUri")
                                    Log.d("VideoDetailsFragment", "   Object Key: ${uploadResult.objectKey}")
                                    Log.d("VideoDetailsFragment", "   File Size: ${uploadResult.fileSize} bytes")
                                    Log.d("VideoDetailsFragment", "   MIME Type: ${uploadResult.mimeType}")
                                    updateLoadingProgress(70, "Video subido exitosamente ✓", false)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            requireContext(),
                                            "✅ Video subido a la nube",
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                                is com.example.tareamov.service.CloudflareR2Service.UploadResult.Error -> {
                                    Log.e("VideoDetailsFragment", "❌ R2 upload failed: ${uploadResult.message}")
                                    Log.w("VideoDetailsFragment", "⚠️ Usando URI local como fallback")
                                    updateLoadingProgress(70, "Usando almacenamiento local...", false)
                                    withContext(Dispatchers.Main) {
                                        android.widget.Toast.makeText(
                                            requireContext(),
                                            "Error subiendo a nube: ${uploadResult.message}. Usando almacenamiento local.",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    } else {
                        Log.w("VideoDetailsFragment", "⚠️ R2 no está configurado!")
                        Log.w("VideoDetailsFragment", "   Verifica local.properties contiene:")
                        Log.w("VideoDetailsFragment", "   - R2_ACCOUNT_ID")
                        Log.w("VideoDetailsFragment", "   - R2_ACCESS_KEY_ID")
                        Log.w("VideoDetailsFragment", "   - R2_SECRET_ACCESS_KEY")
                    }

                    // Upload thumbnail to Cloudflare R2 if selected
                    var thumbnailUrl: String? = null
                    if (thumbnailUri != null) {
                        updateLoadingProgress(75, "Subiendo miniatura...", false)
                        
                        val thumbnailResult = withContext(Dispatchers.IO) {
                            try {
                                com.example.tareamov.service.CloudflareR2Service.uploadThumbnail(
                                    context = requireContext(),
                                    thumbnailUri = thumbnailUri!!,
                                    courseId = null // Will be set after course creation
                                ) { progress ->
                                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                        val mappedProgress = 75 + (progress * 0.1).toInt()
                                        updateLoadingProgress(mappedProgress, "Subiendo miniatura: $progress%", false)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("VideoDetailsFragment", "Error uploading thumbnail", e)
                                com.example.tareamov.service.CloudflareR2Service.UploadResult.Error("Error: ${e.message}")
                            }
                        }
                        
                        when (thumbnailResult) {
                            is com.example.tareamov.service.CloudflareR2Service.UploadResult.Success -> {
                                thumbnailUrl = thumbnailResult.url
                                Log.d("VideoDetailsFragment", "✅ Thumbnail uploaded: $thumbnailUrl")
                                updateLoadingProgress(85, "Miniatura subida ✓", false)
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        requireContext(),
                                        "✅ Miniatura subida exitosamente",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            is com.example.tareamov.service.CloudflareR2Service.UploadResult.Error -> {
                                Log.e("VideoDetailsFragment", "❌ Thumbnail upload failed: ${thumbnailResult.message}")
                                updateLoadingProgress(85, "Error en miniatura, continuando...", false)
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(
                                        requireContext(),
                                        "⚠️ Error subiendo miniatura: ${thumbnailResult.message}",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    
                    // Get next available video ID (> 82)
                    updateLoadingProgress(87, "Generando identificadores...", false)
                    val nextVideoId = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.getNextVideoId()
                    }
                    
                    Log.d("VideoDetailsFragment", "Creating new video with ID: $nextVideoId")

                    // Variable to hold course ID (null if not creating a course)
                    var courseRemoteId: Long? = null

                    // Only create course if user chose to
                    if (createCourse) {
                        updateLoadingProgress(90, "Creando curso...", false)
                        val newCourse = com.example.tareamov.data.entity.Course(
                            id = 0, // Supabase auto-generates
                            title = title,
                            description = description,
                            creatorUserId = userId, // Foreign key to usuarios.id
                            thumbnailUri = thumbnailUrl, // Miniatura subida a R2
                            videoUri = finalVideoUri, // Use R2 URL or local URI
                            isPremium = isPaidCourse,
                            price = if (isPaidCourse) 9.99 else 0.0,
                            creationDate = System.currentTimeMillis().toString(),
                            timestamp = System.currentTimeMillis()
                        )
                        
                        Log.d("VideoDetailsFragment", "Creating course with creatorUserId: $userId, title: $title")
                        
                        courseRemoteId = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.insertCourse(newCourse)
                        }
                        
                        if (courseRemoteId == null || courseRemoteId <= 0) {
                            hideProfessionalLoading()
                            if (isAdded) context?.let { Toast.makeText(it, "Error creando el curso asociado", Toast.LENGTH_SHORT).show() }
                            Log.e("VideoDetailsFragment", "Failed to create course - courseRemoteId: $courseRemoteId")
                            return@launch
                        }
                        
                        Log.d("VideoDetailsFragment", "Course created with ID: $courseRemoteId")
                    } else {
                        Log.d("VideoDetailsFragment", "User chose not to create a course - video will be standalone")
                        updateLoadingProgress(90, "Preparando video...", false)
                    }

                    // Now create video with the specific ID and optional courseId reference
                    updateLoadingProgress(95, "Guardando video...", false)
                    val videoData = VideoData(
                        id = nextVideoId,
                        username = currentUsername, // Keep username for standalone videos
                        description = description,
                        title = title,
                        videoUriString = finalVideoUri, // Use R2 URL or local URI
                        isPaid = isPaidCourse,
                        price = if (isPaidCourse) 9.99 else null,
                        courseId = courseRemoteId, // null if no course created, otherwise link to the course
                        timestamp = System.currentTimeMillis()
                    )
                    
                    Log.d("VideoDetailsFragment", "Attempting to insert video with ID: $nextVideoId, courseId: ${courseRemoteId ?: "null (standalone video)"}")
                    
                    val remoteId = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.insertVideo(videoData)
                    }
                    
                    if (remoteId != null && remoteId > 0) {
                        updateLoadingProgress(100, "¡Completado exitosamente! ✓", false)
                        
                        // Esperar un momento para que el usuario vea el 100%
                        kotlinx.coroutines.delay(800)
                        
                        hideProfessionalLoading()
                        
                        if (isAdded) {
                            val successMessage = if (createCourse) {
                                "✅ Video guardado con ID $remoteId, Curso ID $courseRemoteId"
                            } else {
                                "✅ Video guardado con ID $remoteId (sin curso)"
                            }
                            context?.let { Toast.makeText(it, successMessage, Toast.LENGTH_LONG).show() }
                            Log.d("VideoDetailsFragment", "Video saved successfully with ID: $remoteId, courseId: ${courseRemoteId ?: "none"}")
                            
                            // Navigate to VideoHomeFragment after creating video
                            try {
                                findNavController().navigate(R.id.action_videoDetailsFragment_to_videoHomeFragment)
                            } catch (navException: Exception) {
                                Log.e("VideoDetailsFragment", "Navigation failed: ${navException.message}")
                            }
                        }
                    } else {
                        hideProfessionalLoading()
                        if (isAdded) context?.let { Toast.makeText(it, "Error guardando video en Supabase", Toast.LENGTH_SHORT).show() }
                        Log.e("VideoDetailsFragment", "Failed to insert video - remoteId: $remoteId")
                        return@launch
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoDetailsFragment", "Error saving video details", e)
                hideProfessionalLoading()
                if (isAdded) context?.let { Toast.makeText(it, "Error guardando video: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // Limpiar animaciones
        brainLoadingAnimator?.cleanup()
        brainLoadingAnimator = null
        
        // Limpiar miniaturas antiguas del caché (async, no bloqueante)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                thumbnailExtractor.cleanOldThumbnails()
            } catch (e: Exception) {
                Log.w("VideoDetailsFragment", "Error limpiando miniaturas antiguas", e)
            }
        }
    }

    // Copia el contenido de un URI (Storage Access Framework) a un archivo en cache interno
    @Throws(Exception::class)
    private fun copyUriToCacheFile(uri: Uri): java.io.File? {
        val resolver = requireContext().contentResolver
        val input = resolver.openInputStream(uri) ?: return null
        val cacheDir = java.io.File(requireContext().cacheDir, "thumbnails")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val outFile = java.io.File(cacheDir, "thumb_${System.currentTimeMillis()}.jpg")
        input.use { inputStream ->
            outFile.outputStream().use { output ->
                inputStream.copyTo(output)
            }
        }
        return outFile
    }
}