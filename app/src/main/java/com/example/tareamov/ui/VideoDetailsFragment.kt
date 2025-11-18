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
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.VideoData
import com.example.tareamov.util.SessionManager
import com.example.tareamov.util.VideoManager
import kotlinx.coroutines.CoroutineScope
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Set up video preview
        val videoPreview = view.findViewById<VideoView>(R.id.videoPreview)
        videoPreview.setVideoURI(videoUri)
        videoPreview.start()

        // Set up back button
        view.findViewById<View>(R.id.backButton).setOnClickListener {
            findNavController().navigateUp()
        }

        // Set up next button
        view.findViewById<Button>(R.id.nextButton).setOnClickListener {
            saveVideoDetails()
        }
    }    private fun saveVideoDetails() {
        val title = view?.findViewById<EditText>(R.id.titleEditText)?.text.toString()
        val description = view?.findViewById<EditText>(R.id.descriptionEditText)?.text.toString()

        // Get course type selection
        val courseTypeRadioGroup = view?.findViewById<RadioGroup>(R.id.courseTypeRadioGroup)
        val selectedTypeId = courseTypeRadioGroup?.checkedRadioButtonId
        val isPaidCourse = selectedTypeId == R.id.paidRadioButton

        if (title.isBlank()) {
            Toast.makeText(context, "Por favor ingresa un título", Toast.LENGTH_SHORT).show()
            return
        }

        // Get current username from SessionManager
        val currentUsername = sessionManager.getUsername()
        if (currentUsername == null) {
            Toast.makeText(context, "Error: Usuario no autenticado", Toast.LENGTH_LONG).show()
            return
        }

        // Update the existing video record instead of creating a new one
        CoroutineScope(Dispatchers.Main).launch {
            try {
                // Get user ID for foreign key
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername)
                }

                if (userId == null || userId <= 0) {
                    Toast.makeText(context, "Error: No se pudo obtener el ID del usuario", Toast.LENGTH_LONG).show()
                    return@launch
                }

                val activity = requireActivity()
                if (activity !is com.example.tareamov.MainActivity) {
                    Toast.makeText(context, "Error: Contexto inválido", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // SIEMPRE crear un nuevo video con ID > 82 (nunca actualizar videos existentes)
                // Verificar título único
                val duplicateNew = withContext(Dispatchers.IO) {
                    activity.syncRepository.isTitleExistsInSupabase(title)
                }

                if (duplicateNew) {
                    Toast.makeText(context, "Ya existe un video/curso con este título. Elige otro título.", Toast.LENGTH_LONG).show()
                    return@launch
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
                    videoUri = videoUri.toString(),
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
                    Toast.makeText(context, "Error creando el curso asociado", Toast.LENGTH_SHORT).show()
                    Log.e("VideoDetailsFragment", "Failed to create course - courseRemoteId: $courseRemoteId")
                    return@launch
                }
                
                Log.d("VideoDetailsFragment", "Course created with ID: $courseRemoteId")

                // Now create video with the specific ID and courseId reference
                val videoData = VideoData(
                    id = nextVideoId,
                    username = currentUsername,
                    description = description,
                    title = title,
                    videoUriString = videoUri.toString(),
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
                    Toast.makeText(context, "✅ Video guardado con ID $remoteId, Curso ID $courseRemoteId", Toast.LENGTH_LONG).show()
                    Log.d("VideoDetailsFragment", "Video saved successfully with ID: $remoteId, linked to course: $courseRemoteId")
                } else {
                    Toast.makeText(context, "Error guardando video en Supabase", Toast.LENGTH_SHORT).show()
                    Log.e("VideoDetailsFragment", "Failed to insert video - remoteId: $remoteId")
                    return@launch
                }

                // Navigate back to VideoHomeFragment to show the updated video
                findNavController().navigate(R.id.action_videoDetailsFragment_to_videoHomeFragment)
            } catch (e: Exception) {
                Log.e("VideoDetailsFragment", "Error saving video details", e)
                Toast.makeText(context, "Error guardando video: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}