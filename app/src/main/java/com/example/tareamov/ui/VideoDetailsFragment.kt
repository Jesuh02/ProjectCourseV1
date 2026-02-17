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
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
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
        // Initialize ServerEndpointResolver to ensure backend connectivity
        com.example.tareamov.service.ServerEndpointResolver.initialize(requireContext())

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
        if (videoUri != Uri.EMPTY) {
            try {
                val mediaController = android.widget.MediaController(requireContext())
                mediaController.setAnchorView(videoPreview)
                videoPreview.setMediaController(mediaController)

                videoPreview.setVideoURI(videoUri)
                videoPreview.requestFocus()
                videoPreview.setOnPreparedListener { mp ->
                    try {
                        mp.isLooping = true
                    } catch (_: Exception) {}
                    videoPreview.start()
                }
                videoPreview.setOnErrorListener { _, what, extra ->
                    Log.e("VideoDetailsFragment", "VideoView error: what=$what extra=$extra")
                    try {
                        Toast.makeText(requireContext(), "No se pudo reproducir la vista previa del video", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                    true
                }
            } catch (e: Exception) {
                Log.e("VideoDetailsFragment", "Error configurando la vista previa del video", e)
            }
        } else {
            Log.w("VideoDetailsFragment", "No video URI provided for preview")
        }

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

        // Show create course dialog to let user decide
        showCreateCourseDialog(title, description, currentUsername)
    }

    /**
     * Muestra un diálogo preguntando si se desea crear un curso o solo guardar el video
     */
    private fun showCreateCourseDialog(title: String, description: String, currentUsername: String) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_create_course_minimal, null)
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
            
        // Set transparent background for rounded corners
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        
        // Setup buttons
        dialogView.findViewById<View>(R.id.confirmCreateCourseButton).setOnClickListener {
            dialog.dismiss()
            proceedWithVideoSave(title, description, currentUsername, createCourse = true)
        }
        
        dialogView.findViewById<View>(R.id.cancelCreateCourseButton).setOnClickListener {
            dialog.dismiss()
            proceedWithVideoSave(title, description, currentUsername, createCourse = false)
        }
        
        dialog.show()
    }

    /**
     * Proceeds with video save, optionally creating a course.
     * All file uploads are delegated to the backend — the client never touches R2 directly.
     */
    private fun proceedWithVideoSave(title: String, description: String, currentUsername: String, createCourse: Boolean) {
        showProfessionalLoading(
            if (isEditMode) "Actualizando Curso" else if (createCourse) "Creando tu Curso" else "Subiendo Video",
            if (isEditMode) "Guardando cambios..." else "Preparando archivos..."
        )

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val userId = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByUsername(currentUsername).getOrNull()?.id
                }

                if (userId == null || userId <= 0) {
                    hideProfessionalLoading()
                    if (isAdded) context?.let { Toast.makeText(it, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show() }
                    return@launch
                }

                if (isEditMode) {
                    handleEditMode(title, description, userId)
                } else {
                    handleCreateMode(title, description, currentUsername, createCourse, userId)
                }
            } catch (e: Exception) {
                Log.e("VideoDetailsFragment", "Error saving video details", e)
                hideProfessionalLoading()
                if (isAdded) context?.let { Toast.makeText(it, "Error guardando video: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    /**
     * Handles video update (edit mode). Only metadata + optional new thumbnail via backend.
     */
    private suspend fun handleEditMode(title: String, description: String, userId: Long) {
        updateLoadingProgress(10, "Verificando cambios...", false)

        if (title != initialTitle) {
            val duplicateNew = withContext(Dispatchers.IO) {
                val existing = BackendApiService.searchCourses(title).getOrNull() ?: emptyList()
                existing.any { it.title.equals(title, ignoreCase = true) }
            }
            if (duplicateNew) {
                hideProfessionalLoading()
                if (isAdded) context?.let { Toast.makeText(it, "Ya existe un video/curso con este título. Elige otro título.", Toast.LENGTH_LONG).show() }
                return
            }
        }

        // Upload new thumbnail via backend if selected
        var thumbnailUrl: String? = null
        if (thumbnailUri != null) {
            updateLoadingProgress(30, "Subiendo nueva miniatura...", false)
            thumbnailUrl = withContext(Dispatchers.IO) {
                uploadThumbnailViaBackend(thumbnailUri!!)
            }
        }

        updateLoadingProgress(80, "Actualizando base de datos...", false)

        val success = withContext(Dispatchers.IO) {
            val currentVideo = BackendApiService.getVideoById(videoId).getOrNull() ?: return@withContext false
            val videoUpdates = mapOf<String, Any?>(
                "title" to title,
                "description" to description,
                "is_paid" to isPaidCourse,
                "price" to if (isPaidCourse) 9.99 else null,
                "thumbnail_uri" to (thumbnailUrl ?: currentVideo.thumbnailUri)
            )
            val videoOk = BackendApiService.updateVideo(videoId, videoUpdates).getOrNull() != null

            val courseId = currentVideo.courseId
            if (courseId != null && courseId > 0) {
                val currentCourse = BackendApiService.getCourseById(courseId).getOrNull()
                if (currentCourse != null) {
                    BackendApiService.updateCourse(courseId, mapOf(
                        "title" to title,
                        "description" to description,
                        "is_premium" to isPaidCourse,
                        "price" to if (isPaidCourse) 9.99 else 0.0,
                        "thumbnail_uri" to (thumbnailUrl ?: currentCourse.thumbnailUri)
                    ))
                }
            }
            videoOk
        }

        if (success) {
            updateLoadingProgress(100, "¡Actualizado exitosamente! ✓", false)
            kotlinx.coroutines.delay(800)
            hideProfessionalLoading()
            if (isAdded) {
                notifyVideoUpdated(title, description)
                Toast.makeText(context, "Video actualizado correctamente", Toast.LENGTH_SHORT).show()
                findNavController().navigateUp()
            }
        } else {
            hideProfessionalLoading()
            if (isAdded) Toast.makeText(context, "Error al actualizar el video", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Handles new video creation. Uploads video + thumbnail to the backend in one request.
     */
    private suspend fun handleCreateMode(
        title: String,
        description: String,
        currentUsername: String,
        createCourse: Boolean,
        userId: Long
    ) {
        // Check for duplicate title
        updateLoadingProgress(5, "Verificando título...", false)
        val duplicate = withContext(Dispatchers.IO) {
            val existing = BackendApiService.searchCourses(title).getOrNull() ?: emptyList()
            existing.any { it.title.equals(title, ignoreCase = true) }
        }
        if (duplicate) {
            hideProfessionalLoading()
            if (isAdded) context?.let { Toast.makeText(it, "Ya existe un video/curso con este título. Elige otro título.", Toast.LENGTH_LONG).show() }
            return
        }

        // Auto-generate thumbnail if not selected
        if (thumbnailUri == null) {
            updateLoadingProgress(8, "Generando miniatura del video...", false)
            thumbnailUri = withContext(Dispatchers.IO) {
                try { thumbnailExtractor.extractThumbnailFromVideo(videoUri) } catch (_: Exception) { null }
            }
            if (thumbnailUri != null) {
                withContext(Dispatchers.Main) {
                    try {
                        val thumbnailPreview = view?.findViewById<android.widget.ImageView>(R.id.thumbnailPreview)
                        val thumbnailPlaceholder = view?.findViewById<android.view.ViewGroup>(R.id.thumbnailPlaceholder)
                        val thumbnailSelectedText = view?.findViewById<android.widget.TextView>(R.id.thumbnailSelectedText)
                        if (thumbnailPreview != null) {
                            com.bumptech.glide.Glide.with(requireContext()).load(thumbnailUri).override(1280, 720).centerCrop().into(thumbnailPreview)
                            thumbnailPreview.visibility = View.VISIBLE
                            thumbnailPlaceholder?.visibility = View.GONE
                            thumbnailSelectedText?.visibility = View.VISIBLE
                            thumbnailSelectedText?.text = "✓ Miniatura generada automáticamente"
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Optionally create course first
        var courseRemoteId: Long? = null
        if (createCourse) {
            updateLoadingProgress(10, "Creando curso...", false)
            courseRemoteId = withContext(Dispatchers.IO) {
                BackendApiService.createCourse(mapOf(
                    "title" to title,
                    "description" to description,
                    "creatorUsername" to currentUsername,
                    "isFree" to !isPaidCourse,
                    "price" to if (isPaidCourse) 9.99 else 0.0
                )).getOrNull()?.id
            }
            if (courseRemoteId == null || courseRemoteId <= 0) {
                hideProfessionalLoading()
                if (isAdded) context?.let { Toast.makeText(it, "Error creando el curso asociado", Toast.LENGTH_SHORT).show() }
                return
            }
        }

        // Upload video + thumbnail to backend in one multipart request
        updateLoadingProgress(15, "Subiendo video al servidor...", true)

        val uploadResult = withContext(Dispatchers.IO) {
            BackendApiService.uploadVideoWithFiles(
                context = requireContext(),
                videoUri = videoUri,
                thumbnailUri = thumbnailUri,
                title = title,
                description = description,
                isPaid = isPaidCourse,
                price = if (isPaidCourse) 9.99 else null,
                courseId = courseRemoteId,
                onProgress = { progress ->
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                        val mapped = 15 + (progress * 0.8).toInt()
                        updateLoadingProgress(mapped, "Subiendo video: $progress%", true)
                    }
                }
            )
        }

        when (uploadResult) {
            is ApiResult.Success -> {
                val createdVideo = uploadResult.data
                updateLoadingProgress(100, "¡Completado exitosamente! ✓", false)
                kotlinx.coroutines.delay(800)
                hideProfessionalLoading()

                if (isAdded) {
                    val msg = if (createCourse) "✅ Video guardado con ID ${createdVideo.id}, Curso ID $courseRemoteId"
                              else "✅ Video guardado con ID ${createdVideo.id} (sin curso)"
                    context?.let { Toast.makeText(it, msg, Toast.LENGTH_LONG).show() }
                    try { findNavController().navigate(R.id.action_videoDetailsFragment_to_videoHomeFragment) }
                    catch (e: Exception) { Log.e("VideoDetailsFragment", "Navigation failed: ${e.message}") }
                }
            }
            is ApiResult.Error -> {
                hideProfessionalLoading()
                if (isAdded) context?.let {
                    Toast.makeText(it, "Error subiendo video: ${uploadResult.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Upload a thumbnail image via the backend storage API.
     * Returns the public URL on success, null on failure.
     */
    private suspend fun uploadThumbnailViaBackend(uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        try {
            val resolver = requireContext().contentResolver
            val mime = resolver.getType(uri) ?: "image/jpeg"
            val stream = resolver.openInputStream(uri) ?: return@withContext null
            val bytes = stream.readBytes()
            stream.close()

            val result = BackendApiService.uploadFile(
                fileBytes = bytes,
                fileName = "thumb_${System.currentTimeMillis()}.jpg",
                mimeType = mime,
                folder = "thumbnails/courses"
            )
            if (result is ApiResult.Success) {
                result.data?.get("publicUrl")?.asString
            } else null
        } catch (e: Exception) {
            Log.e("VideoDetailsFragment", "Error uploading thumbnail via backend", e)
            null
        }
    }

    /**
     * Sends navigation results about the updated video to previous fragments.
     */
    private fun notifyVideoUpdated(title: String, description: String) {
        try {
            val navController = findNavController()
            navController.previousBackStackEntry?.savedStateHandle?.apply {
                set("videoUpdated", true)
                set("updatedVideoId", videoId)
                set("updatedTitle", title)
                set("updatedDescription", description)
                set("updatedIsPaid", isPaidCourse)
            }
        } catch (e: Exception) {
            Log.e("VideoDetailsFragment", "Error setting savedStateHandle", e)
        }
        try {
            requireActivity().supportFragmentManager.setFragmentResult("videoUpdated", Bundle().apply {
                putLong("updatedVideoId", videoId)
                putString("updatedTitle", title)
                putString("updatedDescription", description)
                putBoolean("updatedIsPaid", isPaidCourse)
            })
        } catch (e: Exception) {
            Log.e("VideoDetailsFragment", "Error sending fragment result", e)
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