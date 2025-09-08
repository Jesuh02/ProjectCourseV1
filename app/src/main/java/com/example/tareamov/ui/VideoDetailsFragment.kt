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
                val videoManager = VideoManager(requireContext())
                
                // If we have a video ID, update the existing record
                if (videoId > 0L) {
                    val existingVideo = withContext(Dispatchers.IO) {
                        videoManager.getVideoById(videoId)
                    }
                    
                    if (existingVideo != null) {
                            // Update the existing video with new details
                            val updatedVideo = existingVideo.copy(
                                title = title,
                                description = description,
                                isPaid = isPaidCourse
                            )
                              withContext(Dispatchers.IO) {
                                // Update the video in the database
                                val db = com.example.tareamov.data.AppDatabase.getDatabase(requireContext())
                                db.videoDao().updateVideo(updatedVideo)
                                Log.d("VideoDetailsFragment", "Database update completed for video ID: $videoId")
                            }
                        
                        // Verify the update was successful
                        val verifiedVideo = withContext(Dispatchers.IO) {
                            videoManager.getVideoById(videoId)
                        }
                        
                        if (verifiedVideo != null && verifiedVideo.title == title) {
                            Log.d("VideoDetailsFragment", "Video update verification successful")
                            Toast.makeText(context, "Video actualizado correctamente", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e("VideoDetailsFragment", "Video update verification failed")
                            Toast.makeText(context, "Advertencia: La actualización puede no haberse guardado correctamente", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(context, "Error: No se encontró el video para actualizar", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                } else {
                    // Fallback: create new video if no ID provided
                    val videoData = VideoData(
                        username = currentUsername,
                        description = description,
                        title = title,
                        videoUriString = videoUri.toString(),
                        isPaid = isPaidCourse
                    )
                    
                    val savedVideo = withContext(Dispatchers.IO) {
                        videoManager.saveVideo(videoData)
                    }
                    
                    Log.d("VideoDetailsFragment", "Created new video with ID: ${savedVideo.id}")
                    Toast.makeText(context, "Video guardado correctamente", Toast.LENGTH_SHORT).show()
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