package com.example.tareamov.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tareamov.R
import com.example.tareamov.databinding.FragmentNotificacionesBinding
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import com.example.tareamov.data.entity.Notification
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.ui.adapter.NotificationAdapter
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class NotificacionesFragment : Fragment() {

    private var _binding: FragmentNotificacionesBinding? = null
    private val binding get() = _binding!!
    private lateinit var bottomNavBinding: ComponentBottomNavigationBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var notificationAdapter: NotificationAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotificacionesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionManager = SessionManager.getInstance(requireContext())

        val bottomNavView: View = view.findViewById(R.id.bottomNavigation)
        bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)

        // Resaltar solo el icono de actividad en morado (ahora con fondo pill)
        val activeBackground = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.nav_item_background_active)
        bottomNavBinding.activityIconContainer.background = activeBackground
        
        // Ensure icons are white
        val whiteColor = android.graphics.Color.WHITE
        bottomNavBinding.homeIconImageView.setColorFilter(whiteColor)
        bottomNavBinding.exploreIconImageView.setColorFilter(whiteColor)
        bottomNavBinding.activityIconImageView.setColorFilter(whiteColor)
        bottomNavBinding.profileIconImageView.setColorFilter(whiteColor)

        setupRecyclerView()
        setupAdminButton()
        setupNavigation()
        
        // loadNotifications() - Moved to onResume for better freshness
    }

    override fun onResume() {
        super.onResume()
        loadNotifications()
        updateBottomNavSelection("activity")
    }

    private fun setupRecyclerView() {
        notificationAdapter = NotificationAdapter { notification ->
            onNotificationClick(notification)
        }
        binding.notificationsRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = notificationAdapter
        }
    }

    private fun showSkeleton() {
        _binding?.let { binding ->
            binding.skeletonLayout.root.visibility = View.VISIBLE
            binding.notificationsRecyclerView.visibility = View.GONE
            binding.emptyStateLayout.visibility = View.GONE
        }
    }

    private fun hideSkeleton() {
        _binding?.skeletonLayout?.root?.visibility = View.GONE
    }

    private fun loadNotifications() {
        val userId = sessionManager.getUserId()
        if (userId == -1L) {
            Log.w("NotificacionesFragment", "No user ID available")
            hideSkeleton()
            _binding?.emptyStateLayout?.visibility = View.VISIBLE
            return
        }

        // Mostrar skeleton mientras se cargan los datos
        showSkeleton()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = BackendApiService.getMyNotifications()
                
                // Verificar que el binding aún existe antes de actualizar la UI
                val binding = _binding ?: return@launch
                
                // Ocultar skeleton cuando los datos estén listos
                hideSkeleton()
                
                when (result) {
                    is ApiResult.Success -> {
                        val notifications = result.data ?: emptyList()
                        if (notifications.isNotEmpty()) {
                            binding.notificationsRecyclerView.visibility = View.VISIBLE
                            binding.emptyStateLayout.visibility = View.GONE
                            notificationAdapter.submitList(notifications)
                        } else {
                            binding.notificationsRecyclerView.visibility = View.GONE
                            binding.emptyStateLayout.visibility = View.VISIBLE
                        }
                    }
                    is ApiResult.Error -> {
                        Log.e("NotificacionesFragment", "Error loading notifications: ${result.message}")
                        binding.notificationsRecyclerView.visibility = View.GONE
                        binding.emptyStateLayout.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                Log.e("NotificacionesFragment", "Error loading notifications", e)
                hideSkeleton()
                _binding?.notificationsRecyclerView?.visibility = View.GONE
                _binding?.emptyStateLayout?.visibility = View.VISIBLE
            }
        }
    }

    private fun onNotificationClick(notification: Notification) {
        if (!notification.isRead) {
            viewLifecycleOwner.lifecycleScope.launch {
                BackendApiService.markNotificationAsRead(notification.id)
                // Solo recargar si el binding aún existe (vista no destruida)
                if (_binding != null) {
                    loadNotifications()
                }
            }
        }

        when (notification.type) {
            Notification.TYPE_NEW_COURSE -> {
                Log.d("Notificaciones", "Course notification clicked: ${notification.relatedId}")
                notification.relatedId?.let { courseId ->
                    // Navigate to course detail with courseName if available in message
                    // Message format: "User ha publicado un nuevo curso: Course Name"
                    val courseName = if (notification.message.contains(":")) {
                        notification.message.substringAfter(":").trim()
                    } else {
                        ""
                    }
                    
                    val bundle = Bundle().apply {
                        putLong("courseId", courseId)
                        putString("courseName", courseName)
                    }
                    try {
                        findNavController().navigate(R.id.action_notificacionesFragment_to_courseDetailFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to course detail", e)
                    }
                }
            }
            Notification.TYPE_NEW_VIDEO -> {
                Log.d("Notificaciones", "Video notification clicked: ${notification.relatedId}")
                notification.relatedId?.let { videoId ->
                    // Navigate to video detail
                    val bundle = Bundle().apply {
                        putLong("videoId", videoId)
                    }
                    try {
                        findNavController().navigate(R.id.action_notificacionesFragment_to_videoDetailsFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to video detail", e)
                    }
                }
            }
            Notification.TYPE_NEW_TASK -> {
                Log.d("Notificaciones", "New task notification clicked: ${notification.relatedId}")
                notification.relatedId?.let { taskId ->
                    // Navigate to task submissions to see/submit the new task
                    val bundle = Bundle().apply {
                        putLong("taskId", taskId)
                        putString("taskName", notification.message.substringAfter("\"").substringBefore("\""))
                    }
                    try {
                        findNavController().navigate(R.id.action_notificacionesFragment_to_taskSubmissionFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to task submissions", e)
                    }
                }
            }
            Notification.TYPE_TASK_SUBMISSION -> {
                Log.d("Notificaciones", "Task submission notification clicked: ${notification.relatedId}")
                notification.relatedId?.let { taskId ->
                    // Navigate to task submissions and highlight the specific submission from senderUsername
                    val bundle = Bundle().apply {
                        putLong("taskId", taskId)
                        putString("taskName", notification.message.substringAfter("\"").substringBefore("\""))
                        putString("courseCreatorUsername", sessionManager.getUsername())
                        // Pass the username of who submitted the task so we can scroll to their submission
                        notification.senderUsername?.let {
                            putString("scrollToSubmissionUsername", it)
                            Log.d("NotificacionesFragment", "📍 Passing submission to scroll: $it for taskId=$taskId")
                        }
                    }
                    try {
                        findNavController().navigate(R.id.action_notificacionesFragment_to_taskSubmissionFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to task submissions", e)
                    }
                }
            }
            Notification.TYPE_TASK_GRADED -> {
                Log.d("Notificaciones", "Task graded notification clicked: ${notification.relatedId}")
                notification.relatedId?.let { taskId ->
                    // Navigate to task submissions for the student to see their grade
                    val bundle = Bundle().apply {
                        putLong("taskId", taskId)
                        putString("taskName", notification.title.substringAfter("en ").trim())
                    }
                    try {
                        findNavController().navigate(R.id.action_notificacionesFragment_to_taskSubmissionFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to task submissions", e)
                    }
                }
            }
            Notification.TYPE_COMMENT, Notification.TYPE_LIKE -> {
                Log.d("Notificaciones", "💬 Activity notification clicked: ${notification.relatedId}")
                Log.d("Notificaciones", "📄 Notification type: ${notification.type}, message: ${notification.message}")
                notification.relatedId?.let { videoId ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            // Navigate to video home to play the video and open comments
                            // Parse metadata to extract comment_id if available
                            var commentId: Long? = null
                            
                            if (notification.metadata != null) {
                                // Metadata exists - parse it
                                val metadataStr = notification.metadata
                                try {
                                    Log.d("NotificacionesFragment", "🔍 Parsing metadata: $metadataStr")
                                    // metadata format: "comment_id:123" or JSON-like
                                    if (metadataStr.contains("comment_id:")) {
                                        commentId = metadataStr.substringAfter("comment_id:").substringBefore(",").trim().toLongOrNull()
                                    } else if (metadataStr.contains("\"comment_id\"")) {
                                        // JSON format: {"comment_id":123}
                                        val regex = "\"comment_id\"\\s*:\\s*(\\d+)".toRegex()
                                        regex.find(metadataStr)?.groupValues?.get(1)?.toLongOrNull()?.let { id: Long ->
                                            commentId = id
                                        }
                                    }
                                    Log.d("NotificacionesFragment", "✅ Extracted commentId: $commentId from metadata")
                                } catch (e: Exception) {
                                    Log.e("NotificacionesFragment", "❌ Error parsing metadata for comment_id", e)
                                }
                            } else {
                                // Metadata is NULL - comment_id cannot be determined without metadata
                                Log.w("NotificacionesFragment", "⚠️ Metadata is NULL, cannot determine comment_id")
                            }
                            
                            val bundle = Bundle().apply {
                                putLong("videoId", videoId)
                                putBoolean("openComments", true)
                                commentId?.let { 
                                    putLong("targetCommentId", it)
                                    Log.d("NotificacionesFragment", "📦 Bundle prepared with targetCommentId: $it")
                                }
                            }
                            
                            try {
                                Log.d("NotificacionesFragment", "🚀 Navigating to VideoHomeFragment with videoId=$videoId, commentId=$commentId")
                                findNavController().navigate(R.id.action_notificacionesFragment_to_videoHomeFragment, bundle)
                            } catch (e: Exception) {
                                Log.e("NotificacionesFragment", "❌ Error navigating to video home from activity", e)
                            }
                        } catch (e: Exception) {
                            Log.e("NotificacionesFragment", "❌ Error processing notification click", e)
                            // Navigate without commentId as fallback
                            val bundle = Bundle().apply {
                                putLong("videoId", videoId)
                                putBoolean("openComments", true)
                            }
                            try {
                                findNavController().navigate(R.id.action_notificacionesFragment_to_videoHomeFragment, bundle)
                            } catch (navError: Exception) {
                                Log.e("NotificacionesFragment", "❌ Error navigating (fallback)", navError)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateBottomNavSelection(selected: String) {
        val activeBackground = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.nav_item_background_active)
        
        bottomNavBinding.homeIconContainer.background = if (selected == "home") activeBackground else null
        bottomNavBinding.exploreIconContainer.background = if (selected == "explore") activeBackground else null
        bottomNavBinding.activityIconContainer.background = if (selected == "activity") activeBackground else null
        bottomNavBinding.profileIconContainer.background = if (selected == "profile") activeBackground else null
    }

    private fun setupNavigation() {
        bottomNavBinding.homeNavLayout.setOnClickListener {
            updateBottomNavSelection("home")
            try {
                if (findNavController().currentDestination?.id == R.id.notificacionesFragment) {
                    findNavController().navigate(R.id.action_notificacionesFragment_to_videoHomeFragment)
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificacionesFragment", "Navigation error:", e)
            }
        }

        bottomNavBinding.exploreButton.setOnClickListener {
            updateBottomNavSelection("explore")
            try {
                if (findNavController().currentDestination?.id == R.id.notificacionesFragment) {
                    findNavController().navigate(R.id.action_notificacionesFragment_to_exploreFragment)
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificacionesFragment", "Navigation error:", e)
            }
        }

        bottomNavBinding.goToHomeButton.setOnClickListener {
            try {
                if (findNavController().currentDestination?.id == R.id.notificacionesFragment) {
                    findNavController().navigate(R.id.action_notificacionesFragment_to_contentUploadFragment)
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificacionesFragment", "Navigation error:", e)
            }
        }

        bottomNavBinding.activityButton.setOnClickListener {
            // Ya estamos en la actividad, no hacemos nada
        }

        bottomNavBinding.profileNavButton.setOnClickListener {
            updateBottomNavSelection("profile")
            try {
                if (findNavController().currentDestination?.id == R.id.notificacionesFragment) {
                    findNavController().navigate(R.id.action_notificacionesFragment_to_profileFragment)
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificacionesFragment", "Navigation error:", e)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupAdminButton() {
        // Usar la propiedad de clase bottomNavBinding inicializada en onViewCreated
        val adminSlot = bottomNavBinding.adminSlot
        val goToAdminButton = bottomNavBinding.goToAdminButton

        // Inicializa como INVISIBLE para evitar salto al inflar
        goToAdminButton.visibility = View.INVISIBLE

        // Si SessionManager ya conoce el rol del usuario, podemos decidir antes del primer render
        val sess = SessionManager.getInstance(requireContext())
        
        // Verificar rol 3
        if (!sess.hasRole(3)) {
            // Ocultar completamente el slot antes de que se dibuje para que no quede hueco
            adminSlot.visibility = View.GONE
            return
        }

        // Si llegó aquí, el usuario tiene rol 3: mostrar y asignar listener
        adminSlot.visibility = View.VISIBLE
        goToAdminButton.visibility = View.VISIBLE
        goToAdminButton.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_homeFragment)
        }
    }
}