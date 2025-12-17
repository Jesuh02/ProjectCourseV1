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
import com.example.tareamov.service.SupabaseClient
import com.example.tareamov.ui.adapter.NotificationAdapter
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.launch

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

        bottomNavBinding.activityIconImageView.setColorFilter(
            androidx.core.content.ContextCompat.getColor(requireContext(), R.color.purple_500)
        )

        setupRecyclerView()
        setupAdminButton()
        setupNavigation()
        
        loadNotifications()
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
                val notifications = SupabaseClient.fetchNotifications(userId)
                
                // Verificar que el binding aún existe antes de actualizar la UI
                val binding = _binding ?: return@launch
                
                // Ocultar skeleton cuando los datos estén listos
                hideSkeleton()
                
                if (notifications.isNotEmpty()) {
                    binding.notificationsRecyclerView.visibility = View.VISIBLE
                    binding.emptyStateLayout.visibility = View.GONE
                    notificationAdapter.submitList(notifications)
                } else {
                    binding.notificationsRecyclerView.visibility = View.GONE
                    binding.emptyStateLayout.visibility = View.VISIBLE
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
                SupabaseClient.markNotificationAsRead(notification.id)
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
                    // Navigate to course detail
                    val bundle = Bundle().apply {
                        putLong("courseId", courseId)
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
            Notification.TYPE_COMMENT, Notification.TYPE_LIKE -> {
                Log.d("Notificaciones", "Interaction notification clicked: ${notification.relatedId}")
                notification.relatedId?.let { videoId ->
                    val bundle = Bundle().apply {
                        putLong("videoId", videoId)
                    }
                    try {
                        // Navigate to VideoHomeFragment to view the video and comments
                        findNavController().navigate(R.id.action_notificacionesFragment_to_videoHomeFragment, bundle)
                    } catch (e: Exception) {
                        Log.e("NotificacionesFragment", "Error navigating to video home", e)
                        // Fallback to video details
                        try {
                            findNavController().navigate(R.id.action_notificacionesFragment_to_videoDetailsFragment, bundle)
                        } catch (e2: Exception) {
                            Log.e("NotificacionesFragment", "Error navigating to video details", e2)
                        }
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
                    // Navigate to task submissions to see the submission (for course creator)
                    val bundle = Bundle().apply {
                        putLong("taskId", taskId)
                        putString("taskName", notification.message.substringAfter("\"").substringBefore("\""))
                        putString("courseCreatorUsername", sessionManager.getUsername())
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
        }
    }

    private fun setupNavigation() {
        bottomNavBinding.homeNavLayout.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_videoHomeFragment)
        }

        bottomNavBinding.exploreButton.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_exploreFragment)
        }

        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_contentUploadFragment)
        }

        bottomNavBinding.activityButton.setOnClickListener {
            // Ya estamos en la actividad, no hacemos nada
        }

        bottomNavBinding.profileNavButton.setOnClickListener {
            findNavController().navigate(R.id.action_notificacionesFragment_to_profileFragment)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun getSyncRepository(): com.example.tareamov.data.sync.SyncRepository {
        val db = com.example.tareamov.data.AppDatabase.getDatabase(requireContext())
        return com.example.tareamov.data.sync.SyncRepository(
            db.usuarioDao(), db.personaDao(), db.topicDao(), db.contentItemDao(), db.taskDao(),
            db.subscriptionDao(), db.taskSubmissionDao(), db.videoDao(), db.courseDao(), db.rolDao(),
            db.recursoDao(), db.rolRecursoDao(), db.chatMessageDao(), db.fileContextDao(), db.progresoEstudianteDao()
        )
    }

    private fun setupAdminButton() {
        val view = view ?: return
        
        com.example.tareamov.util.BottomNavigationHelper.setupBottomNavigation(
            lifecycleOwner = viewLifecycleOwner,
            navController = findNavController(),
            view = view,
            sessionManager = sessionManager,
            syncRepository = getSyncRepository(),
            onAdminClick = {
                findNavController().navigate(R.id.action_notificacionesFragment_to_homeFragment)
            }
        )
    }
}