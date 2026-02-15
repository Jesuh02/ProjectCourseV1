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
                val result = withContext(Dispatchers.IO) {
                    BackendApiService.getMyNotifications()
                }
                
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
        Log.d("NotificacionesFragment", "🔔 Notification clicked: id=${notification.id}, type='${notification.type}', relatedId=${notification.relatedId}, metadata=${notification.metadata}")

        // Mark as read in background (fire-and-forget). 
        // onResume() already calls loadNotifications() when user returns.
        if (!notification.isRead) {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        BackendApiService.markNotificationAsRead(notification.id)
                    }
                } catch (e: Exception) {
                    Log.e("NotificacionesFragment", "Error marking notification as read", e)
                }
            }
        }

        when (notification.type) {
            Notification.TYPE_NEW_COURSE -> {
                Log.d("Notificaciones", "Course notification clicked: ${notification.relatedId}")
                notification.relatedId?.let { courseId ->
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
                    // Extraer taskName del título "Nueva tarea: <taskName>"
                    val taskName = when {
                        notification.title.contains(":") -> notification.title.substringAfter(":").trim()
                        notification.message.contains("'") -> notification.message.substringAfter("'").substringBefore("'")
                        else -> ""
                    }
                    val bundle = Bundle().apply {
                        putLong("taskId", taskId)
                        putString("taskName", taskName)
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
                    // Extraer taskName del título "Nueva entrega: <taskName>" o del mensaje "'<taskName>'"
                    val taskName = when {
                        notification.title.contains(":") -> notification.title.substringAfter(":").trim()
                        notification.message.contains("'") -> notification.message.substringAfter("'").substringBefore("'")
                        else -> ""
                    }
                    val bundle = Bundle().apply {
                        putLong("taskId", taskId)
                        putString("taskName", taskName)
                        putString("courseCreatorUsername", sessionManager.getUsername())
                        notification.senderUsername?.let {
                            putString("scrollToSubmissionUsername", it)
                            Log.d("NotificacionesFragment", "📍 Passing submission to scroll: $it for taskId=$taskId")
                        }
                    }
                    try {
                        findNavController().navigate(R.id.action_notificacionesFragment_to_taskSubmissionFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to task submissions", e)
                        try {
                            // Fallback: intentar navegar solo con taskId
                             val bundleFallback = Bundle().apply { putLong("taskId", taskId) }
                             findNavController().navigate(R.id.action_notificacionesFragment_to_taskSubmissionFragment, bundleFallback)
                        } catch (e2: Exception) {
                            Log.e("NotificacionesFragment", "Fatal navigation error", e2)
                        }
                    }
                }
            }
            Notification.TYPE_TASK_GRADED -> {
                Log.d("Notificaciones", "Task graded notification clicked: ${notification.relatedId}")
                notification.relatedId?.let { taskId ->
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
            Notification.TYPE_VIDEO_LIKE -> {
                navigateToVideoInHome(notification)
            }
            Notification.TYPE_COMMENT, Notification.TYPE_LIKE,
            Notification.TYPE_VIDEO_COMMENT, Notification.TYPE_COMMENT_REPLY,
            Notification.TYPE_COMMENT_LIKE -> {
                // Navigate SYNCHRONOUSLY — no coroutine needed since metadata parsing is synchronous
                navigateToCommentInVideo(notification)
            }
            Notification.TYPE_CHAT_RESPONSE -> {
                navigateToChat(notification)
            }
            else -> {
                Log.w("NotificacionesFragment", "⚠️ Unhandled notification type: '${notification.type}' for notification id=${notification.id}")
            }
        }
    }

    /**
     * Navigates to VideoHomeFragment focusing the video from relatedId.
     * Used for video_like notifications where metadata can be null.
     */
    private fun navigateToVideoInHome(notification: Notification) {
        val videoId = notification.relatedId ?: extractVideoIdFromMetadata(notification.metadata)
        if (videoId == null) {
            Log.w("NotificacionesFragment", "⚠️ relatedId is null for video_like notification id=${notification.id}")
            return
        }

        val bundle = Bundle().apply {
            putLong("videoId", videoId)
            putBoolean("openComments", false)
        }

        Log.d("NotificacionesFragment", "🎬 Navigating to VideoHomeFragment for video_like: videoId=$videoId")

        try {
            findNavController().navigate(R.id.action_notificacionesFragment_to_videoHomeFragment, bundle)
        } catch (e: Exception) {
            Log.e("NotificacionesFragment", "❌ Navigation failed for video_like: ${e.message}", e)
            try {
                findNavController().navigate(R.id.videoHomeFragment, bundle)
            } catch (e2: Exception) {
                Log.e("NotificacionesFragment", "❌ Fallback navigation failed for video_like: ${e2.message}", e2)
            }
        }
    }

    private fun navigateToChat(notification: Notification) {
        val metadataStr = notification.metadata
        if (metadataStr.isNullOrEmpty()) {
             // Fallback default
             try {
                findNavController().navigate(R.id.databaseQueryFragment)
             } catch (e: Exception) { Log.e("NotificacionesFragment", "Nav error", e) }
             return
        }

        try {
            val metadata = org.json.JSONObject(metadataStr)
            val messageId = metadata.optString("messageId")
            val fragment = metadata.optString("fragment")
            
            val bundle = Bundle().apply {
                putString("messageId", messageId)
            }
            
            Log.d("NotificacionesFragment", "Navigating to chat: fragment=$fragment, messageId=$messageId")

            if (fragment == "database_query") {
                 findNavController().navigate(R.id.databaseQueryFragment, bundle)
            } else {
                 // Even if fragment is unknown, default to DatabaseQueryFragment or ChatBotFragment
                 // But since DatabaseQueryFragment is the one sending it currently:
                 findNavController().navigate(R.id.databaseQueryFragment, bundle)
            }
        } catch (e: Exception) {
            Log.e("NotificacionesFragment", "Error navigating to chat: ${e.message}")
            try {
                findNavController().navigate(R.id.databaseQueryFragment)
            } catch (ignore: Exception) {}
        }
    }

    /**
     * Navigates to VideoHomeFragment and opens the comments dialog scrolled to the specific comment.
        * Handles comment, like, comment_reply, video_comment and comment_like notification types.
     * Navigation is synchronous (no coroutine) to avoid race conditions with loadNotifications().
     */
    private fun navigateToCommentInVideo(notification: Notification) {
        val videoId = notification.relatedId ?: extractVideoIdFromMetadata(notification.metadata)
        if (videoId == null) {
            Log.w("NotificacionesFragment", "⚠️ relatedId is null for comment/like notification id=${notification.id}")
            return
        }

        Log.d("NotificacionesFragment", "💬 Processing comment/like notification: type='${notification.type}', videoId=$videoId")

        // Parse metadata synchronously to extract comment_id
        val commentId = extractCommentIdFromMetadata(notification.metadata)
        if (notification.metadata == null) {
            Log.w("NotificacionesFragment", "⚠️ Metadata is NULL, navigating without targetCommentId")
        }

        val bundle = Bundle().apply {
            putLong("videoId", videoId)
            putBoolean("openComments", true)
            commentId?.let {
                putLong("targetCommentId", it)
            }
        }

        Log.d("NotificacionesFragment", "🚀 Navigating to VideoHomeFragment: videoId=$videoId, openComments=true, targetCommentId=${commentId ?: "none"}")

        try {
            findNavController().navigate(R.id.action_notificacionesFragment_to_videoHomeFragment, bundle)
        } catch (e: Exception) {
            Log.e("NotificacionesFragment", "❌ Navigation failed: ${e.message}", e)
            // Fallback: try navigating directly to the destination by ID
            try {
                findNavController().navigate(R.id.videoHomeFragment, bundle)
            } catch (e2: Exception) {
                Log.e("NotificacionesFragment", "❌ Fallback navigation also failed: ${e2.message}", e2)
            }
        }
    }

    private fun extractCommentIdFromMetadata(metadata: String?): Long? {
        if (metadata.isNullOrBlank()) return null

        return try {
            val normalized = normalizeMetadata(metadata)
            if (normalized.trim().startsWith("{")) {
                val jsonObject = org.json.JSONObject(normalized)
                if (jsonObject.has("comment_id")) {
                    val idVal = jsonObject.get("comment_id")
                    return if (idVal is Number) idVal.toLong() else idVal.toString().toLongOrNull()
                }
                if (jsonObject.has("commentId")) {
                    val idVal = jsonObject.get("commentId")
                    return if (idVal is Number) idVal.toLong() else idVal.toString().toLongOrNull()
                }
            }

            val regex = Regex("""(?:comment_id|commentId)\s*[:=]\s*\"?(\d+)\"?""")
            regex.find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()
        } catch (e: Exception) {
            Log.e("NotificacionesFragment", "❌ Error parsing metadata for comment_id", e)
            null
        }
    }

    private fun extractVideoIdFromMetadata(metadata: String?): Long? {
        if (metadata.isNullOrBlank()) return null

        return try {
            val normalized = normalizeMetadata(metadata)
            if (normalized.trim().startsWith("{")) {
                val jsonObject = org.json.JSONObject(normalized)
                if (jsonObject.has("video_id")) {
                    val idVal = jsonObject.get("video_id")
                    return if (idVal is Number) idVal.toLong() else idVal.toString().toLongOrNull()
                }
                if (jsonObject.has("videoId")) {
                    val idVal = jsonObject.get("videoId")
                    return if (idVal is Number) idVal.toLong() else idVal.toString().toLongOrNull()
                }
            }

            val regex = Regex("""(?:video_id|videoId)\s*[:=]\s*\"?(\d+)\"?""")
            regex.find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()
        } catch (e: Exception) {
            Log.e("NotificacionesFragment", "❌ Error parsing metadata for video_id", e)
            null
        }
    }

    private fun normalizeMetadata(metadata: String): String {
        var normalized = metadata.trim()
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length > 1) {
            normalized = normalized.substring(1, normalized.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return normalized
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