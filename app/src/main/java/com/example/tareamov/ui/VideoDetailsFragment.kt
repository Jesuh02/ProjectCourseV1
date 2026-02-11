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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import kotlinx.coroutines.withContext
import okio.source

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
                    BackendApiService.getUserByUsername(currentUsername).getOrNull()?.id
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
                            val existingCourses = BackendApiService.searchCourses(title).getOrNull() ?: emptyList()
                            existingCourses.any { it.title.equals(title, ignoreCase = true) }
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
                        val currentVideo = BackendApiService.getVideoById(videoId).getOrNull()
                        if (currentVideo != null) {
                            // Update Video
                            val videoUpdates = mapOf<String, Any?>(
                                "title" to title,
                                "description" to description,
                                "is_paid" to isPaidCourse,
                                "price" to if (isPaidCourse) 9.99 else null,
                                "thumbnail_uri" to (thumbnailUrl ?: currentVideo.thumbnailUri)
                            )
                            val videoUpdateSuccess = BackendApiService.updateVideo(videoId, videoUpdates).getOrNull() != null
                            
                            // Update Course
                            val courseId = currentVideo.courseId
                            if (courseId != null && courseId > 0) {
                                val currentCourse = BackendApiService.getCourseById(courseId).getOrNull()
                                if (currentCourse != null) {
                                    val courseUpdates = mapOf<String, Any?>(
                                        "title" to title,
                                        "description" to description,
                                        "is_premium" to isPaidCourse,
                                        "price" to if (isPaidCourse) 9.99 else 0.0,
                                        "thumbnail_uri" to (thumbnailUrl ?: currentCourse.thumbnailUri)
                                    )
                                    BackendApiService.updateCourse(courseId, courseUpdates)
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
                        val existingCourses = BackendApiService.searchCourses(title).getOrNull() ?: emptyList()
                        existingCourses.any { it.title.equals(title, ignoreCase = true) }
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
                    var uploadedViaBackend = false
                    
                    // Try Backend Presigned URL First
                    val videoCleanName = title.replace(Regex("[^a-zA-Z0-9]"), "_") + ".mp4"
                    val videoUploadData = getUploadUrlFromBackend(videoCleanName, "video/mp4")
                    if (videoUploadData != null) {
                         updateLoadingProgress(15, "Subiendo video (nube)...", true)
                         val uploadUrl = videoUploadData.getString("uploadUrl")
                         val publicUrl = videoUploadData.getString("publicUrl")
                         val success = uploadToPresignedUrl(uploadUrl, videoUri, "video/mp4") { progress ->
                             val mappedProgress = 15 + (progress * 0.55).toInt()
                             viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                 updateLoadingProgress(mappedProgress, "Subiendo video: $progress%", true)
                             }
                         }
                         if (success) {
                             finalVideoUri = publicUrl
                             uploadedViaBackend = true
                             Log.d("VideoDetailsFragment", "✅ Video uploaded to Backend Storage: $finalVideoUri")
                         }
                    }
                    
                    Log.d("VideoDetailsFragment", "🔍 Verificando configuración R2: isConfigured=${com.example.tareamov.service.CloudflareR2Service.isConfigured()}")
                    
                    if (!uploadedViaBackend && com.example.tareamov.service.CloudflareR2Service.isConfigured()) {
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
                    } else if (!uploadedViaBackend) {
                        Log.w("VideoDetailsFragment", "⚠️ R2 no está configurado y backend upload falló")
                    }

                    // GUARD: If video was not uploaded to cloud, block save
                    if (!finalVideoUri.startsWith("http://") && !finalVideoUri.startsWith("https://")) {
                        Log.e("VideoDetailsFragment", "❌ Video no fue subido a la nube. URI local: $finalVideoUri")
                        hideProfessionalLoading()
                        withContext(Dispatchers.Main) {
                            if (isAdded) {
                                Toast.makeText(
                                    requireContext(),
                                    "Error: No se pudo subir el video a la nube. Otros usuarios no podrán verlo. Intenta de nuevo.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        return@launch
                    }

                    // Upload thumbnail to Cloudflare R2 if selected
                    var thumbnailUrl: String? = null
                    var thumbUploadedViaBackend = false
                    
                    if (thumbnailUri != null) {
                        val thumbName = "thumb_${System.currentTimeMillis()}.jpg"
                        val thumbUploadData = getUploadUrlFromBackend(thumbName, "image/jpeg")
                        if (thumbUploadData != null) {
                            updateLoadingProgress(75, "Subiendo miniatura (nube)...", false)
                            val uploadUrl = thumbUploadData.getString("uploadUrl")
                            val publicUrl = thumbUploadData.getString("publicUrl")
                            val success = uploadToPresignedUrl(uploadUrl, thumbnailUri!!, "image/jpeg") {  }
                            if (success) {
                                thumbnailUrl = publicUrl
                                thumbUploadedViaBackend = true
                                Log.d("VideoDetailsFragment", "✅ Thumbnail uploaded to Backend Storage: $thumbnailUrl")
                            }
                        }
                    }

                    if (thumbnailUri != null && !thumbUploadedViaBackend) {
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
                    val nextVideoId = 0L // Backend auto-assigns the real ID on creation
                    
                    Log.d("VideoDetailsFragment", "Creating new video with ID: $nextVideoId")

                    // Variable to hold course ID (null if not creating a course)
                    var courseRemoteId: Long? = null

                    // Only create course if user chose to
                    if (createCourse) {
                        updateLoadingProgress(90, "Creando curso...", false)
                        val payload = mapOf(
                            "title" to title,
                            "description" to description,
                            "creatorUsername" to currentUsername,
                            "thumbnailUri" to thumbnailUrl,
                            "isFree" to !isPaidCourse,
                            "price" to if (isPaidCourse) 9.99 else 0.0
                        )
                        
                        Log.d("VideoDetailsFragment", "Creating course via Map with creator: $currentUsername, title: $title")
                        
                        courseRemoteId = withContext(Dispatchers.IO) {
                            BackendApiService.createCourse(payload).getOrNull()?.id
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
                    updateLoadingProgress(95, "Guardando video en servidor...", false)
                    val videoData = VideoData(
                        id = nextVideoId,
                        username = currentUsername, // Keep username for standalone videos
                        description = description,
                        title = title,
                        videoUriString = finalVideoUri, // Use R2 URL or local URI
                        isPaid = isPaidCourse,
                        price = if (isPaidCourse) 9.99 else null,
                        courseId = courseRemoteId, // null if no course created, otherwise link to the course
                        remoteId = userId, // Store creator ID in remote_id as requested
                        timestamp = System.currentTimeMillis(),
                        thumbnailUri = thumbnailUrl // Include thumbnail URL
                    )
                    
                    Log.d("VideoDetailsFragment", "Attempting to insert video via backend with courseId: ${courseRemoteId ?: "null (standalone video)"}")
                    
                    // Use backend endpoint to insert video (better reliability and centralized logic)
                    var remoteId = withContext(Dispatchers.IO) {
                        insertVideoViaBackend(videoData)
                    }
                    
                    // FALLBACK: If insertVideoViaBackend fails, try via BackendApiService
                    if (remoteId == null || remoteId <= 0) {
                        Log.w("VideoDetailsFragment", "⚠️ Backend insert failed, retrying via BackendApiService...")
                        updateLoadingProgress(97, "Reintentando creación de video...", false)
                        remoteId = withContext(Dispatchers.IO) {
                            BackendApiService.createVideo(videoData).getOrNull()?.id
                        }
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
    
    /**
     * Insert video via backend API endpoint (centralized database operations)
     * This is more reliable than direct Supabase calls from mobile
     */
    private suspend fun insertVideoViaBackend(videoData: VideoData): Long? = withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val baseUrl = com.example.tareamov.service.ServerEndpointResolver.getMcpBaseUrl()
            val url = "$baseUrl/video/insert"
            
            // Build JSON payload
            val jsonPayload = org.json.JSONObject().apply {
                put("title", videoData.title)
                put("description", videoData.description)
                put("videoUriString", videoData.videoUriString)
                put("localFilePath", videoData.localFilePath)
                put("timestamp", videoData.timestamp)
                put("isPaid", videoData.isPaid)
                put("thumbnailUri", videoData.thumbnailUri)
                put("price", videoData.price)
                put("remoteId", videoData.remoteId)
                if (videoData.courseId != null) {
                    put("courseId", videoData.courseId)
                }
            }
            
            Log.d("VideoDetailsFragment", "📤 Sending video insert to backend: $url")
            Log.d("VideoDetailsFragment", "   Payload: $jsonPayload")
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonPayload.toString().toRequestBody(mediaType)
            
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d("VideoDetailsFragment", "📥 Backend response: code=${response.code}, body=$responseBody")
                
                if (response.isSuccessful && responseBody != null) {
                    val json = org.json.JSONObject(responseBody)
                    if (json.optBoolean("success", false)) {
                        val videoId = json.optLong("videoId", -1L)
                        if (videoId > 0) {
                            Log.d("VideoDetailsFragment", "✅ Video inserted via backend: ID=$videoId")
                            return@withContext videoId
                        }
                    }
                    Log.e("VideoDetailsFragment", "❌ Backend returned success=false or missing videoId")
                } else {
                    Log.e("VideoDetailsFragment", "❌ Backend request failed: ${response.code} - $responseBody")
                }
            }
        } catch (e: Exception) {
            Log.e("VideoDetailsFragment", "❌ Error inserting video via backend", e)
        }
        
        // Backend failed - log error and return null (caller will handle fallback)
        Log.w("VideoDetailsFragment", "⚠️ Backend video insert failed - returning null to trigger fallback")
        return@withContext null
    }
    
    // OkHttp extension helpers using modern API
    private fun String.toMediaType(): okhttp3.MediaType = 
        this.toMediaTypeOrNull() ?: throw IllegalArgumentException("Invalid media type")
    private fun String.toRequestBody(mediaType: okhttp3.MediaType): okhttp3.RequestBody = 
        okhttp3.RequestBody.Companion.create(mediaType, this)

    /**
     * Obtains a presigned upload URL from the backend
     */
    private suspend fun getUploadUrlFromBackend(filename: String, contentType: String): org.json.JSONObject? = withContext(Dispatchers.IO) {
        try {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val baseUrl = com.example.tareamov.service.ServerEndpointResolver.getMcpBaseUrl()
            val url = "$baseUrl/video/upload-url"
            
            Log.d("VideoDetailsFragment", "🔗 Requesting presigned upload URL: $url")
            Log.d("VideoDetailsFragment", "   filename=$filename, contentType=$contentType")
            
            val jsonBody = org.json.JSONObject().apply {
                put("filename", filename)
                put("contentType", contentType)
            }
            
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val requestBody = jsonBody.toString().toRequestBody(mediaType)
            
            val request = okhttp3.Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .build()
            
            client.newCall(request).execute().use { response ->
                val respBody = response.body?.string()
                Log.d("VideoDetailsFragment", "🔗 Upload URL response: code=${response.code}, body=${respBody?.take(200)}")
                if (response.isSuccessful && respBody != null) {
                    val json = org.json.JSONObject(respBody)
                    if (json.optBoolean("success")) {
                        val data = json.optJSONObject("data")
                        Log.d("VideoDetailsFragment", "✅ Got presigned upload URL: ${data?.optString("publicUrl")?.take(80)}")
                        return@withContext data
                    } else {
                        Log.e("VideoDetailsFragment", "❌ Upload URL request failed: ${json.optString("error")}")
                    }
                } else {
                    Log.e("VideoDetailsFragment", "❌ Upload URL HTTP error: ${response.code} - ${respBody?.take(200)}")
                }
            }
        } catch (e: Exception) {
            Log.e("VideoDetailsFragment", "❌ Error getting upload URL", e)
        }
        return@withContext null
    }

    /**
     * Uploads file to presigned URL
     */
    private suspend fun uploadToPresignedUrl(uploadUrl: String, uri: Uri, contentType: String, progressCallback: (Int) -> Unit): Boolean = withContext(Dispatchers.IO) {
        try {
            val resolver = requireContext().contentResolver
            val fileSize = resolver.openFileDescriptor(uri, "r")?.statSize ?: 0L
            
            val inputStream = resolver.openInputStream(uri) ?: return@withContext false
            
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            // Create a RequestBody that supports progress tracking
            val requestBody = object : okhttp3.RequestBody() {
                override fun contentType() = contentType.toMediaType()
                override fun contentLength() = fileSize
                override fun writeTo(sink: okio.BufferedSink) {
                    val source = inputStream.source()
                    var totalBytes = 0L
                    val buffer = okio.Buffer()
                    var readCount: Long = 0L
                    
                    while (source.read(buffer, 8192L).also { readCount = it } != -1L) {
                        sink.write(buffer, readCount)
                        totalBytes += readCount
                        if (fileSize > 0) {
                            val progress = ((totalBytes * 100) / fileSize).toInt()
                            progressCallback(progress)
                        }
                    }
                }
            }
            
            val request = okhttp3.Request.Builder()
                .url(uploadUrl)
                .put(requestBody)
                .build()
                
            client.newCall(request).execute().use { response ->
                return@withContext response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e("VideoDetailsFragment", "Error uploading to presigned URL", e)
            return@withContext false
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