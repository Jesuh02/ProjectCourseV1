package com.example.tareamov.ui

import android.net.Uri
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            videoUri = it.getParcelable("videoUri") ?: Uri.EMPTY
            videoId = it.getLong("videoId", 0L) // Get the video ID
        }
        // Initialize SessionManager
        sessionManager = SessionManager.getInstance(requireContext())
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

        // Show upload progress
        val progressBar = view?.findViewById<android.widget.ProgressBar>(R.id.uploadProgressBar)
        val progressText = view?.findViewById<android.widget.TextView>(R.id.uploadProgressText)
        progressBar?.visibility = View.VISIBLE
        progressText?.visibility = View.VISIBLE
        progressText?.text = "Preparando..."

        // Update the existing video record instead of creating a new one
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Get user ID for foreign key
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername)
                }

                if (userId == null || userId <= 0) {
                    progressBar?.visibility = View.GONE
                    progressText?.visibility = View.GONE
                    if (isAdded) context?.let { Toast.makeText(it, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show() }
                    return@launch
                }

                val activity = activity as? com.example.tareamov.MainActivity
                if (activity == null) {
                    progressBar?.visibility = View.GONE
                    progressText?.visibility = View.GONE
                    if (isAdded) context?.let { Toast.makeText(it, "Error: Contexto inválido", Toast.LENGTH_SHORT).show() }
                    return@launch
                }
                
                // Verificar título único
                val duplicateNew = withContext(Dispatchers.IO) {
                    activity.syncRepository.isTitleExistsInSupabase(title)
                }

                if (duplicateNew) {
                    progressBar?.visibility = View.GONE
                    progressText?.visibility = View.GONE
                    if (isAdded) context?.let { Toast.makeText(it, "Ya existe un video/curso con este título. Elige otro título.", Toast.LENGTH_LONG).show() }
                    return@launch
                }

                // Upload video to Cloudflare R2 if configured
                var finalVideoUri = videoUri.toString()
                
                if (com.example.tareamov.service.CloudflareR2Service.isConfigured()) {
                    withContext(Dispatchers.Main) {
                        progressText?.text = "Subiendo video a la nube..."
                    }
                    
                    val uploadResult = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.CloudflareR2Service.uploadVideo(
                            context = requireContext(),
                            videoUri = videoUri,
                            customFileName = title.replace(Regex("[^a-zA-Z0-9]"), "_")
                        ) { progress ->
                            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
                                progressBar?.progress = progress
                                progressText?.text = "Subiendo video: $progress%"
                            }
                        }
                    }
                    
                    when (uploadResult) {
                        is com.example.tareamov.service.CloudflareR2Service.UploadResult.Success -> {
                            finalVideoUri = uploadResult.url
                            Log.d("VideoDetailsFragment", "✅ Video uploaded to R2: $finalVideoUri")
                            withContext(Dispatchers.Main) {
                                progressText?.text = "Video subido, guardando datos..."
                            }
                        }
                        is com.example.tareamov.service.CloudflareR2Service.UploadResult.Error -> {
                            Log.w("VideoDetailsFragment", "⚠️ R2 upload failed: ${uploadResult.message}, using local URI")
                            // Continue with local URI if R2 upload fails
                        }
                    }
                } else {
                    Log.d("VideoDetailsFragment", "R2 not configured, using local URI")
                }

                // Get next available video ID (> 82)
                val nextVideoId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getNextVideoId()
                }
                
                Log.d("VideoDetailsFragment", "Creating new video with ID: $nextVideoId")

                // First, create the course (will get its own auto-generated ID)
                val newCourse = com.example.tareamov.data.entity.Course(
                    id = 0, // Supabase auto-generates
                    title = title,
                    description = description,
                    creatorUserId = userId, // Foreign key to usuarios.id
                    videoUri = finalVideoUri, // Use R2 URL or local URI
                    isPremium = isPaidCourse,
                    price = if (isPaidCourse) 9.99 else 0.0,
                    creationDate = System.currentTimeMillis().toString(),
                    timestamp = System.currentTimeMillis()
                )
                
                Log.d("VideoDetailsFragment", "Creating course with creatorUserId: $userId, title: $title")
                
                val courseRemoteId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.insertCourse(newCourse)
                }
                
                if (courseRemoteId == null || courseRemoteId <= 0) {
                    progressBar?.visibility = View.GONE
                    progressText?.visibility = View.GONE
                    if (isAdded) context?.let { Toast.makeText(it, "Error creando el curso asociado", Toast.LENGTH_SHORT).show() }
                    Log.e("VideoDetailsFragment", "Failed to create course - courseRemoteId: $courseRemoteId")
                    return@launch
                }
                
                Log.d("VideoDetailsFragment", "Course created with ID: $courseRemoteId")

                // Now create video with the specific ID and courseId reference
                // NO incluir username - se obtiene desde course_id en el backend/app
                val videoData = VideoData(
                    id = nextVideoId,
                    username = "", // NO se envía a Supabase, se deriva desde course_id
                    description = description,
                    title = title,
                    videoUriString = finalVideoUri, // Use R2 URL or local URI
                    isPaid = isPaidCourse,
                    price = if (isPaidCourse) 9.99 else null,
                    courseId = courseRemoteId, // Link to the course
                    timestamp = System.currentTimeMillis()
                )
                
                Log.d("VideoDetailsFragment", "Attempting to insert video with ID: $nextVideoId, courseId: $courseRemoteId")
                
                val remoteId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.insertVideo(videoData)
                }
                
                if (remoteId != null && remoteId > 0) {
                    progressBar?.visibility = View.GONE
                    progressText?.visibility = View.GONE
                    if (isAdded) {
                        context?.let { Toast.makeText(it, "✅ Video guardado con ID $remoteId, Curso ID $courseRemoteId", Toast.LENGTH_LONG).show() }
                        Log.d("VideoDetailsFragment", "Video saved successfully with ID: $remoteId, linked to course: $courseRemoteId")
                        
                        // Navigate to VideoHomeFragment after creating video
                        try {
                            findNavController().navigate(R.id.action_videoDetailsFragment_to_videoHomeFragment)
                        } catch (navException: Exception) {
                            Log.e("VideoDetailsFragment", "Navigation failed: ${navException.message}")
                        }
                    }
                } else {
                    progressBar?.visibility = View.GONE
                    progressText?.visibility = View.GONE
                    if (isAdded) context?.let { Toast.makeText(it, "Error guardando video en Supabase", Toast.LENGTH_SHORT).show() }
                    Log.e("VideoDetailsFragment", "Failed to insert video - remoteId: $remoteId")
                    return@launch
                }
            } catch (e: Exception) {
                Log.e("VideoDetailsFragment", "Error saving video details", e)
                progressBar?.visibility = View.GONE
                progressText?.visibility = View.GONE
                if (isAdded) context?.let { Toast.makeText(it, "Error guardando video: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }
}