package com.example.tareamov.ui

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import com.example.tareamov.util.AppCache
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest

class NotificacionesFragment : Fragment() {

    private var _binding: FragmentNotificacionesBinding? = null
    private val binding get() = _binding!!
    private lateinit var bottomNavBinding: ComponentBottomNavigationBinding
    private lateinit var sessionManager: SessionManager
    private lateinit var notificationAdapter: NotificationAdapter
    /** Prevents concurrent notification fetches that cause duplicates. */
    private var isFetchingNotifications = false

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

        val canUploadContent = sessionManager.hasRole(2) || sessionManager.hasRole(3)
        val goToHomeContainer = bottomNavBinding.goToHomeButton.parent as? View
        bottomNavBinding.goToHomeButton.visibility = if (canUploadContent) View.VISIBLE else View.GONE
        goToHomeContainer?.visibility = if (canUploadContent) View.VISIBLE else View.GONE

        setupRecyclerView()
        setupNavigation()
        observeNotificationRefresh()
    }

    override fun onResume() {
        super.onResume()
        loadNotifications()
        updateBottomNavSelection("activity")
    }

    private fun observeNotificationRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            AppCache.notificationRefresh.collectLatest {
                // Only fetch from network when cache was explicitly invalidated.
                // Do NOT call fetchNotificationsFromNetwork if a fetch is already in progress.
                if (!isFetchingNotifications) {
                    fetchNotificationsFromNetwork()
                }
            }
        }
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
            hideSkeleton()
            _binding?.emptyStateLayout?.visibility = View.VISIBLE
            return
        }

        // Show cached data immediately if available
        val cached = AppCache.getNotifications() // Use TTL-aware version
        if (!cached.isNullOrEmpty()) {
            hideSkeleton()
            _binding?.notificationsRecyclerView?.visibility = View.VISIBLE
            _binding?.emptyStateLayout?.visibility = View.GONE
            notificationAdapter.submitList(cached)
            return // Cache is still fresh, skip network fetch
        }

        // Show stale data as placeholder while fetching
        val stale = AppCache.getCachedNotificationsOrStale()
        if (!stale.isNullOrEmpty()) {
            hideSkeleton()
            _binding?.notificationsRecyclerView?.visibility = View.VISIBLE
            _binding?.emptyStateLayout?.visibility = View.GONE
            notificationAdapter.submitList(stale)
        } else {
            showSkeleton()
        }

        fetchNotificationsFromNetwork()
    }

    private fun fetchNotificationsFromNetwork() {
        if (isFetchingNotifications) return
        isFetchingNotifications = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { BackendApiService.getMyNotifications() }
                val binding = _binding ?: return@launch
                hideSkeleton()
                when (result) {
                    is ApiResult.Success -> {
                        val notifications = result.data ?: emptyList()
                        AppCache.putNotifications(notifications)
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
                        val cached = AppCache.getCachedNotificationsOrStale()
                        if (cached.isNullOrEmpty()) {
                            binding.notificationsRecyclerView.visibility = View.GONE
                            binding.emptyStateLayout.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (e: Exception) {
                hideSkeleton()
                val cached = AppCache.getCachedNotificationsOrStale()
                if (cached.isNullOrEmpty()) {
                    _binding?.notificationsRecyclerView?.visibility = View.GONE
                    _binding?.emptyStateLayout?.visibility = View.VISIBLE
                }
            } finally {
                isFetchingNotifications = false
            }
        }
    }

    private fun onNotificationClick(notification: Notification) {
        if (!notification.isRead) {
            AppCache.markNotificationRead(notification.id)
            AppCache.getCachedNotificationsOrStale()?.let { notificationAdapter.submitList(it) }
            viewLifecycleOwner.lifecycleScope.launch {
                try { withContext(Dispatchers.IO) { BackendApiService.markNotificationAsRead(notification.id) } }
                catch (e: Exception) { Log.e("NotificacionesFragment", "Error marking read", e) }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            when (notification.type) {
                Notification.TYPE_NEW_COURSE -> {
                    val courseId = notification.relatedId ?: return@launch
                    if (!verifyContent { BackendApiService.getCourseById(courseId) }) return@launch
                    val courseName = if (notification.message.contains(":")) notification.message.substringAfter(":").trim() else ""
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
                Notification.TYPE_NEW_VIDEO -> {
                    val videoId = notification.relatedId ?: return@launch
                    val videoResult = withContext(Dispatchers.IO) { BackendApiService.getVideoById(videoId) }
                    val video = (videoResult as? ApiResult.Success)?.data
                    if (video == null) { showContentDeleted(); return@launch }

                    val courseId = video.courseId
                        ?: extractCourseIdFromMetadata(notification.metadata)

                    if (courseId != null && courseId > 0) {
                        val bundle = Bundle().apply {
                            putLong("courseId", courseId)
                            putString("courseName", video.title ?: "")
                        }
                        try {
                            findNavController().navigate(R.id.action_notificacionesFragment_to_courseDetailFragment, bundle)
                        } catch (e: Exception) {
                            Log.e("NotificacionesFragment", "Error navigating to course detail from video", e)
                        }
                    } else {
                        val bundle = Bundle().apply { putLong("videoId", videoId) }
                        try {
                            findNavController().navigate(R.id.action_notificacionesFragment_to_videoDetailsFragment, bundle)
                        } catch (e: Exception) {
                            Log.e("NotificacionesFragment", "Error navigating to video detail", e)
                        }
                    }
                }
                Notification.TYPE_NEW_TASK -> {
                    val taskId = notification.relatedId ?: return@launch
                    if (!verifyContent { BackendApiService.getTaskById(taskId) }) return@launch
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
                Notification.TYPE_TASK_SUBMISSION -> {
                    val taskId = notification.relatedId ?: return@launch
                    if (!verifyContent { BackendApiService.getTaskById(taskId) }) return@launch
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
                        }
                    }
                    try {
                        findNavController().navigate(R.id.action_notificacionesFragment_to_taskSubmissionFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to task submissions", e)
                    }
                }
                Notification.TYPE_TASK_GRADED -> {
                    val resolvedTaskId = withContext(Dispatchers.IO) {
                        suspend fun isValidTask(id: Long): Boolean {
                            val r = BackendApiService.getTaskById(id)
                            return r is ApiResult.Success && r.data != null
                        }
                        val relatedId = notification.relatedId
                        if (relatedId != null && isValidTask(relatedId)) return@withContext relatedId
                        val metaTaskId = extractTaskIdFromMetadata(notification.metadata)
                        if (metaTaskId != null && isValidTask(metaTaskId)) return@withContext metaTaskId
                        if (relatedId != null) {
                            val subResult = BackendApiService.getSubmissionById(relatedId)
                            if (subResult is ApiResult.Success && subResult.data != null) {
                                val taskIdFromSub = subResult.data.taskId
                                if (taskIdFromSub > 0 && isValidTask(taskIdFromSub)) return@withContext taskIdFromSub
                            }
                        }
                        relatedId ?: metaTaskId ?: -1L
                    }
                    if (resolvedTaskId <= 0L) { showContentDeleted(); return@launch }
                    val bundle = Bundle().apply {
                        putLong("taskId", resolvedTaskId)
                        putString("taskName", notification.title.substringAfter("en ").trim())
                    }
                    try {
                        findNavController().navigate(R.id.action_notificacionesFragment_to_taskSubmissionFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to task submissions", e)
                    }
                }
                Notification.TYPE_VIDEO_LIKE -> {
                    val videoId = notification.relatedId ?: extractVideoIdFromMetadata(notification.metadata)
                    if (videoId == null || !verifyContent { BackendApiService.getVideoById(videoId) }) return@launch
                    navigateToVideoInHome(notification)
                }
                Notification.TYPE_COMMENT, Notification.TYPE_LIKE,
                Notification.TYPE_VIDEO_COMMENT, Notification.TYPE_COMMENT_REPLY,
                Notification.TYPE_COMMENT_LIKE -> {
                    val videoId = notification.relatedId ?: extractVideoIdFromMetadata(notification.metadata)
                    if (videoId == null || !verifyContent { BackendApiService.getVideoById(videoId) }) return@launch
                    navigateToCommentInVideo(notification)
                }
                Notification.TYPE_CHAT_RESPONSE -> navigateToChat(notification)
                Notification.TYPE_COLLABORATOR_ADDED, Notification.TYPE_ENROLLMENT_APPROVED,
                Notification.TYPE_COURSE_INVITATION -> {
                    val courseId = notification.relatedId ?: extractCourseIdFromMetadata(notification.metadata)
                    if (courseId != null && courseId > 0) {
                        if (!verifyContent { BackendApiService.getCourseById(courseId) }) return@launch
                        val bundle = Bundle().apply {
                            putLong("courseId", courseId)
                            putString("courseName", "")
                        }
                        try {
                            findNavController().navigate(R.id.action_notificacionesFragment_to_subjectsListFragment, bundle)
                        } catch (e: Exception) {
                            Log.e("NotificacionesFragment", "Error navigating to subjects list from collaborator/enrollment/invitation notification", e)
                        }
                    }
                }
                Notification.TYPE_ENROLLMENT_REQUEST -> {
                    val studentUsername = notification.senderUsername ?: ""
                    val courseName = extractCourseNameFromMetadata(notification.metadata) ?: ""
                    val bundle = Bundle().apply {
                        putString("navigateToSection", "ENROLLMENT")
                        if (studentUsername.isNotBlank()) putString("filterUsername", studentUsername)
                        if (courseName.isNotBlank()) putString("filterCourse", courseName)
                    }
                    try {
                        findNavController().navigate(
                            R.id.action_notificacionesFragment_to_adminDashboardFragment,
                            bundle
                        )
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to admin dashboard for enrollment request", e)
                    }
                }
                Notification.TYPE_SUBJECT_ACCESS_BLOCKED -> {
                    // Show informational dialog — do NOT navigate into the blocked subject
                    val ctx = context ?: return@launch
                    val reason = run {
                        try {
                            val meta = org.json.JSONObject(notification.metadata ?: "{}")
                            meta.optString("reason", "").ifBlank { null }
                        } catch (_: Exception) { null }
                    } ?: notification.message.substringAfter("Motivo:").trim().ifBlank { "Sin motivo especificado" }

                    val dlgLayout = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass_dark)
                        val p = (20 * resources.displayMetrics.density).toInt()
                        setPadding(p, p, p, (16 * resources.displayMetrics.density).toInt())
                    }
                    dlgLayout.addView(android.widget.TextView(ctx).apply {
                        text = "\uD83D\uDEAB Acceso bloqueado"
                        textSize = 18f
                        setTextColor(android.graphics.Color.WHITE)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
                    })
                    dlgLayout.addView(android.widget.TextView(ctx).apply {
                        text = "Has sido bloqueado debido a: $reason"
                        textSize = 15f
                        setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (20 * resources.displayMetrics.density).toInt() }
                    })
                    dlgLayout.addView(android.view.View(ctx).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * resources.displayMetrics.density).toInt()
                        ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
                    })
                    val okBtn = android.widget.TextView(ctx).apply {
                        text = "Entendido"
                        textSize = 16f
                        setTextColor(android.graphics.Color.parseColor("#FF9F0A"))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        gravity = android.view.Gravity.CENTER
                        val pad = (12 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    dlgLayout.addView(okBtn)
                    val blockDlg = android.app.Dialog(ctx)
                    blockDlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                    blockDlg.setContentView(dlgLayout)
                    blockDlg.window?.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    )
                    val dlgW = (resources.displayMetrics.widthPixels * 0.88).toInt()
                    blockDlg.window?.setLayout(dlgW, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
                    okBtn.setOnClickListener { blockDlg.dismiss() }
                    blockDlg.show()
                }
                Notification.TYPE_ENROLLMENT_REVOKED -> {
                    // Show informational dialog about course access revocation
                    val ctx = context ?: return@launch
                    val reason = run {
                        try {
                            val meta = org.json.JSONObject(notification.metadata ?: "{}")
                            meta.optString("reason", "").ifBlank { null }
                        } catch (_: Exception) { null }
                    } ?: notification.message.substringAfter("Motivo:").trim().ifBlank { "Sin motivo especificado" }

                    val courseName = run {
                        try {
                            val meta = org.json.JSONObject(notification.metadata ?: "{}")
                            meta.optString("courseName", "").ifBlank { null }
                        } catch (_: Exception) { null }
                    } ?: "un curso"

                    val dlgLayout = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        background = androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass_dark)
                        val p = (20 * resources.displayMetrics.density).toInt()
                        setPadding(p, p, p, (16 * resources.displayMetrics.density).toInt())
                    }
                    dlgLayout.addView(android.widget.TextView(ctx).apply {
                        text = "\uD83D\uDEAB Acceso al curso revocado"
                        textSize = 18f
                        setTextColor(android.graphics.Color.WHITE)
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
                    })
                    dlgLayout.addView(android.widget.TextView(ctx).apply {
                        text = "Tu acceso a \"$courseName\" ha sido revocado.\n\nMotivo: $reason"
                        textSize = 15f
                        setTextColor(android.graphics.Color.parseColor("#CCCCCC"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (20 * resources.displayMetrics.density).toInt() }
                    })
                    dlgLayout.addView(android.view.View(ctx).apply {
                        setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            (1 * resources.displayMetrics.density).toInt()
                        ).apply { bottomMargin = (12 * resources.displayMetrics.density).toInt() }
                    })
                    val revokeOkBtn = android.widget.TextView(ctx).apply {
                        text = "Entendido"
                        textSize = 16f
                        setTextColor(android.graphics.Color.parseColor("#FF453A"))
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        gravity = android.view.Gravity.CENTER
                        val pad = (12 * resources.displayMetrics.density).toInt()
                        setPadding(pad, pad, pad, pad)
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    dlgLayout.addView(revokeOkBtn)
                    val revokeDlg = android.app.Dialog(ctx)
                    revokeDlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                    revokeDlg.setContentView(dlgLayout)
                    revokeDlg.window?.setBackgroundDrawable(
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                    )
                    val revokeDlgW = (resources.displayMetrics.widthPixels * 0.88).toInt()
                    revokeDlg.window?.setLayout(revokeDlgW, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
                    revokeOkBtn.setOnClickListener { revokeDlg.dismiss() }
                    revokeDlg.show()
                }
                else -> Log.w("NotificacionesFragment", "Unhandled notification type: '${notification.type}'")
            }
        }
    }

    private suspend fun <T> verifyContent(apiCall: suspend () -> ApiResult<T>): Boolean {
        val result = withContext(Dispatchers.IO) { apiCall() }
        if (result.isSuccess) return true
        showContentDeleted()
        return false
    }

    private fun showContentDeleted() {
        Toast.makeText(requireContext(), "Este contenido ha sido eliminado", Toast.LENGTH_SHORT).show()
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

    private fun extractCourseIdFromMetadata(metadata: String?): Long? {
        if (metadata.isNullOrBlank()) return null
        return try {
            val normalized = normalizeMetadata(metadata)
            if (normalized.trim().startsWith("{")) {
                val json = org.json.JSONObject(normalized)
                for (key in listOf("courseId", "course_id")) {
                    if (json.has(key)) {
                        val v = json.get(key)
                        val id = if (v is Number) v.toLong() else v.toString().toLongOrNull()
                        if (id != null && id > 0) return id
                    }
                }
            }
            Regex("""(?:courseId|course_id)\s*[":=]\s*"?(\d+)"?""")
                .find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()
        } catch (_: Exception) { null }
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

    private fun extractCourseNameFromMetadata(metadata: String?): String? {
        if (metadata.isNullOrBlank()) return null
        return try {
            val normalized = normalizeMetadata(metadata)
            if (normalized.trim().startsWith("{")) {
                val json = org.json.JSONObject(normalized)
                for (key in listOf("courseName", "course_name")) {
                    if (json.has(key)) return json.getString(key).ifBlank { null }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun extractTaskIdFromMetadata(metadata: String?): Long? {
        if (metadata.isNullOrBlank()) return null
        return try {
            val normalized = normalizeMetadata(metadata)
            if (normalized.trim().startsWith("{")) {
                val json = org.json.JSONObject(normalized)
                for (key in listOf("taskId", "task_id")) {
                    if (json.has(key)) {
                        val v = json.get(key)
                        val id = if (v is Number) v.toLong() else v.toString().toLongOrNull()
                        if (id != null && id > 0) return id
                    }
                }
            }
            Regex("""(?:taskId|task_id)\s*[":=]\s*"?(\d+)"?""")
                .find(normalized)?.groupValues?.getOrNull(1)?.toLongOrNull()
        } catch (e: Exception) { null }
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

        if (sessionManager.hasRole(2) || sessionManager.hasRole(3)) {
            bottomNavBinding.goToHomeButton.setOnClickListener {
                try {
                    if (findNavController().currentDestination?.id == R.id.notificacionesFragment) {
                        findNavController().navigate(R.id.action_notificacionesFragment_to_contentUploadFragment)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("NotificacionesFragment", "Navigation error:", e)
                }
            }
        } else {
            bottomNavBinding.goToHomeButton.setOnClickListener(null)
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

}