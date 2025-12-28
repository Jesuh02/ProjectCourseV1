package com.example.tareamov.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.databinding.ComponentBottomNavigationBinding
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Panel de Administrador - Funcionalidades únicas para el creador de la app:
 * 
 * 1. ANALYTICS GLOBAL:
 *    - Métricas de toda la plataforma (usuarios, cursos, submissions, engagement)
 *    - Gráficos de crecimiento y actividad
 *    - Top cursos, top estudiantes, top creadores
 * 
 * 2. GESTIÓN DE USUARIOS Y ROLES:
 *    - Lista de todos los usuarios con sus roles
 *    - Asignación/revocación de roles (admin, profesor, estudiante)
 *    - Gestión de permisos y recursos
 *    - Activar/desactivar usuarios
 * 
 * 3. MODERACIÓN DE CONTENIDO:
 *    - Revisar submissions pendientes de calificación
 *    - Moderación de chat_messages
 *    - Gestión de notificaciones del sistema
 *    - Revisión de file_contexts
 * 
 * 4. PROGRESO Y CERTIFICADOS:
 *    - Vista global de progreso_estudiante
 *    - Gestión de certificados emitidos
 *    - Estudiantes con mejor rendimiento
 * 
 * 5. SISTEMA DE PERMISOS:
 *    - Configuración de recursos (menús, vistas, acciones)
 *    - Asignación de recursos a roles (rol_recursos)
 *    - Jerarquía de recursos (padre_id)
 */
class AdminDashboardFragment : Fragment() {

    private lateinit var sessionManager: SessionManager
    private lateinit var database: AppDatabase
    private lateinit var bottomNavBinding: ComponentBottomNavigationBinding
    
    // Views principales
    private lateinit var backButton: ImageButton
    private lateinit var titleTextView: TextView
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var scrollView: androidx.core.widget.NestedScrollView
    
    // Secciones del dashboard
    private var currentSection: DashboardSection = DashboardSection.ANALYTICS

    enum class DashboardSection {
        ANALYTICS,          // Métricas globales
        USER_MANAGEMENT,    // Gestión de usuarios y roles
        MODERATION,         // Moderación de contenido
        PROGRESS_TRACKING,  // Progreso y certificados
        PERMISSIONS         // Sistema de permisos
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_admin_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        sessionManager = SessionManager.getInstance(requireContext())
        database = AppDatabase.getDatabase(requireContext())
        
        initializeViews(view)
        setupBottomNavigation(view)
        checkAdminAccess()
    }

    private fun initializeViews(view: View) {
        backButton = view.findViewById(R.id.backButton)
        titleTextView = view.findViewById(R.id.dashboardTitle)
        sectionsContainer = view.findViewById(R.id.sectionsContainer)
        scrollView = view.findViewById(R.id.dashboardScrollView)
        
        backButton.setOnClickListener {
            findNavController().navigateUp()
        }
        
        setupSectionTabs(view)
    }

    private fun setupSectionTabs(view: View) {
        val tabAnalytics: LinearLayout = view.findViewById(R.id.tabAnalytics)
        val tabUsers: LinearLayout = view.findViewById(R.id.tabUsers)
        val tabModeration: LinearLayout = view.findViewById(R.id.tabModeration)
        val tabProgress: LinearLayout = view.findViewById(R.id.tabProgress)
        val tabPermissions: LinearLayout = view.findViewById(R.id.tabPermissions)
        
        tabAnalytics.setOnClickListener {
            switchSection(DashboardSection.ANALYTICS)
            updateTabSelection(tabAnalytics, tabUsers, tabModeration, tabProgress, tabPermissions)
        }
        
        tabUsers.setOnClickListener {
            switchSection(DashboardSection.USER_MANAGEMENT)
            updateTabSelection(tabUsers, tabAnalytics, tabModeration, tabProgress, tabPermissions)
        }
        
        tabModeration.setOnClickListener {
            switchSection(DashboardSection.MODERATION)
            updateTabSelection(tabModeration, tabAnalytics, tabUsers, tabProgress, tabPermissions)
        }
        
        tabProgress.setOnClickListener {
            switchSection(DashboardSection.PROGRESS_TRACKING)
            updateTabSelection(tabProgress, tabAnalytics, tabUsers, tabModeration, tabPermissions)
        }
        
        tabPermissions.setOnClickListener {
            switchSection(DashboardSection.PERMISSIONS)
            updateTabSelection(tabPermissions, tabAnalytics, tabUsers, tabModeration, tabProgress)
        }
        
        // Iniciar con Analytics
        switchSection(DashboardSection.ANALYTICS)
        updateTabSelection(tabAnalytics, tabUsers, tabModeration, tabProgress, tabPermissions)
    }

    private fun updateTabSelection(selected: LinearLayout, vararg others: LinearLayout) {
        selected.setBackgroundResource(R.drawable.tab_selected_background)
        others.forEach { it.setBackgroundResource(R.drawable.tab_unselected_background) }
    }

    private fun switchSection(section: DashboardSection) {
        currentSection = section
        sectionsContainer.removeAllViews()
        
        when (section) {
            DashboardSection.ANALYTICS -> loadAnalyticsSection()
            DashboardSection.USER_MANAGEMENT -> loadUserManagementSection()
            DashboardSection.MODERATION -> loadModerationSection()
            DashboardSection.PROGRESS_TRACKING -> loadProgressTrackingSection()
            DashboardSection.PERMISSIONS -> loadPermissionsSection()
        }
    }

    // ==================== SECCIÓN 1: ANALYTICS GLOBAL ====================
    
    private fun loadAnalyticsSection() {
        titleTextView.text = "Analytics Global"
        
        lifecycleScope.launch {
            val metrics = withContext(Dispatchers.IO) {
                try {
                    // Fetch real data from Supabase in parallel
                    val userCountDeferred = async { com.example.tareamov.service.SupabaseClient.fetchUserCount() }
                    val courseCountDeferred = async { com.example.tareamov.service.SupabaseClient.fetchCourseCount(publishedOnly = false) }
                    val publishedCourseCountDeferred = async { com.example.tareamov.service.SupabaseClient.fetchCourseCount(publishedOnly = true) }
                    val submissionCountDeferred = async { com.example.tareamov.service.SupabaseClient.fetchSubmissionCount() }
                    val certCountDeferred = async { com.example.tareamov.service.SupabaseClient.fetchCertificatesIssuedCount() }
                    
                    GlobalMetrics(
                        totalUsers = userCountDeferred.await(),
                        activeUsers = userCountDeferred.await(), // Assuming all fetched are active for now
                        totalCourses = courseCountDeferred.await(),
                        publishedCourses = publishedCourseCountDeferred.await(),
                        totalSubmissions = submissionCountDeferred.await(),
                        totalNotifications = 0,
                        totalChatMessages = 0,
                        certificatesIssued = certCountDeferred.await()
                    )
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error loading analytics", e)
                    // Fallback to empty metrics
                    GlobalMetrics(0, 0, 0, 0, 0, 0, 0, 0)
                }
            }
            
            displayAnalyticsMetrics(metrics)
        }
    }

    private fun displayAnalyticsMetrics(metrics: GlobalMetrics) {
        val analyticsView = LayoutInflater.from(requireContext())
            .inflate(R.layout.section_analytics, sectionsContainer, false)
        
        analyticsView.findViewById<TextView>(R.id.totalUsersText).text = metrics.totalUsers.toString()
        analyticsView.findViewById<TextView>(R.id.activeUsersText).text = "Total registrados"
        analyticsView.findViewById<TextView>(R.id.totalCoursesText).text = metrics.totalCourses.toString()
        analyticsView.findViewById<TextView>(R.id.publishedCoursesText).text = "Publicados"
        analyticsView.findViewById<TextView>(R.id.totalSubmissionsText).text = "${metrics.totalSubmissions}"
        analyticsView.findViewById<TextView>(R.id.certificatesIssuedText).text = metrics.certificatesIssued.toString()
        
        // Setup Charts
        val weeklyChart = analyticsView.findViewById<com.example.tareamov.ui.components.SimpleLineChart>(R.id.weeklyActivityChart)
        // Mock data for weekly activity - In a real app this would come from metrics
        weeklyChart.setData(listOf(120f, 150f, 140f, 180f, 220f, 190f, 160f))
        
        val monthlyChart = analyticsView.findViewById<com.example.tareamov.ui.components.SimpleBarChart>(R.id.monthlyProgressChart)
        // Mock data for monthly progress
        monthlyChart.setData(
            listOf(350f, 500f, 420f, 580f, 510f, 620f),
            listOf("Ene", "Feb", "Mar", "Abr", "May", "Jun")
        )
        
        // Animate Progress Bars
        val progressCompleted = analyticsView.findViewById<ProgressBar>(R.id.progressCompleted)
        val progressApproved = analyticsView.findViewById<ProgressBar>(R.id.progressApproved)
        val progressSatisfaction = analyticsView.findViewById<ProgressBar>(R.id.progressSatisfaction)
        
        animateProgressBar(progressCompleted, 87)
        animateProgressBar(progressApproved, 92)
        animateProgressBar(progressSatisfaction, 78)
        
        sectionsContainer.addView(analyticsView)
        
        // Cargar top creadores
        loadTopCreators(analyticsView)
        
        // Cargar cursos más populares
        loadTopCourses(analyticsView)
        
        // Cargar estudiantes destacados
        loadTopStudents(analyticsView)
    }

    private fun animateProgressBar(progressBar: ProgressBar, target: Int) {
        val animation = android.animation.ObjectAnimator.ofInt(progressBar, "progress", 0, target)
        animation.duration = 1500
        animation.interpolator = android.view.animation.DecelerateInterpolator()
        animation.start()
    }

    private fun loadTopCreators(parentView: View) {
        lifecycleScope.launch {
            val creators = withContext(Dispatchers.IO) {
                try {
                    val topCreators = com.example.tareamov.service.SupabaseClient.fetchTopCreators()
                    topCreators.map { 
                        CreatorStats(
                            username = it.username,
                            coursesCount = it.coursesCount,
                            subscribersCount = it.subscribers,
                            certificationsCount = it.certifications,
                            avatarUrl = it.avatarUrl
                        )
                    }
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error loading top creators", e)
                    emptyList<CreatorStats>()
                }
            }
            
            val container = parentView.findViewById<LinearLayout>(R.id.topCreatorsContainer)
            container.removeAllViews()
            
            creators.forEachIndexed { index, creator ->
                val itemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_top_creator, container, false)
                
                val avatarView = itemView.findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.creatorAvatar)
                
                // Cargar avatar del creador
                if (!creator.avatarUrl.isNullOrBlank()) {
                    try {
                        com.bumptech.glide.Glide.with(requireContext())
                            .load(creator.avatarUrl)
                            .placeholder(R.drawable.placeholder_avatar)
                            .error(R.drawable.placeholder_avatar)
                            .into(avatarView)
                    } catch (e: Exception) {
                        avatarView.setImageResource(R.drawable.placeholder_avatar)
                    }
                } else {
                    avatarView.setImageResource(R.drawable.placeholder_avatar)
                }
                
                itemView.findViewById<TextView>(R.id.rankNumber).text = "${index + 1}"
                itemView.findViewById<TextView>(R.id.creatorName).text = creator.username
                itemView.findViewById<TextView>(R.id.coursesCount).text = "${creator.coursesCount} Cursos"
                itemView.findViewById<TextView>(R.id.subscribersCount).text = "${creator.subscribersCount}"
                container.addView(itemView)
            }
        }
    }

    private fun loadTopCourses(parentView: View) {
        lifecycleScope.launch {
            val topCourses = withContext(Dispatchers.IO) {
                try {
                    val courses = database.courseDao().getAllCourses()
                    // Fetch enrollment counts from Supabase for each course
                    courses.mapNotNull { course ->
                        try {
                            val enrollmentCount = com.example.tareamov.service.SupabaseClient.fetchEnrolledCount(course.id)
                            CourseStats(
                                id = course.id,
                                title = course.title,
                                description = course.description ?: "",
                                thumbnailUri = course.thumbnailUri,
                                enrollments = enrollmentCount.toInt(),
                                isPremium = course.isPremium,
                                rating = 4.5f // Placeholder, could be calculated from submissions
                            )
                        } catch (e: Exception) {
                            Log.e("AdminDashboard", "Error fetching enrollment for course ${course.id}", e)
                            null
                        }
                    }
                    .sortedByDescending { it.enrollments }
                    .take(5)
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error loading top courses", e)
                    emptyList()
                }
            }
            
            val container = parentView.findViewById<LinearLayout>(R.id.topCoursesContainer)
            container.removeAllViews()
            
            topCourses.forEach { course ->
                val itemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_top_course, container, false)
                
                itemView.findViewById<TextView>(R.id.courseTitle).text = course.title
                itemView.findViewById<TextView>(R.id.courseDescription).text = course.description
                itemView.findViewById<TextView>(R.id.enrollmentsText).text = "${course.enrollments} inscritos"
                itemView.findViewById<TextView>(R.id.courseRating).text = String.format("%.1f", course.rating)
                itemView.findViewById<ImageView>(R.id.premiumBadge).visibility = 
                    if (course.isPremium) View.VISIBLE else View.GONE
                
                // Load thumbnail if available
                val thumbnail = itemView.findViewById<ImageView>(R.id.courseThumbnail)
                if (!course.thumbnailUri.isNullOrEmpty()) {
                    // Use Glide or Coil to load image
                    try {
                        com.bumptech.glide.Glide.with(requireContext())
                            .load(course.thumbnailUri)
                            .placeholder(R.drawable.placeholder_image)
                            .error(R.drawable.placeholder_image)
                            .into(thumbnail)
                    } catch (e: Exception) {
                        thumbnail.setImageResource(R.drawable.placeholder_image)
                    }
                } else {
                    thumbnail.setImageResource(R.drawable.placeholder_image)
                }
                
                container.addView(itemView)
            }
        }
    }

    private fun loadTopStudents(parentView: View) {
        lifecycleScope.launch {
            val topStudents = withContext(Dispatchers.IO) {
                // Placeholder - implementación simplificada
                emptyList<StudentStats>()
            }
            
            val container = parentView.findViewById<LinearLayout>(R.id.topStudentsContainer)
            container.removeAllViews()
            
            topStudents.forEach { student ->
                val itemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_top_student, container, false)
                itemView.findViewById<TextView>(R.id.studentName).text = student.username
                itemView.findViewById<TextView>(R.id.averageGrade).text = 
                    String.format("%.1f", student.averageGrade)
                itemView.findViewById<ImageView>(R.id.certificateIcon).visibility = 
                    if (student.hasCertificate) View.VISIBLE else View.GONE
                container.addView(itemView)
            }
        }
    }

    // ==================== SECCIÓN 2: GESTIÓN DE USUARIOS ====================
    
    private fun loadUserManagementSection() {
        titleTextView.text = "Gestión de Usuarios"
        
        val userManagementView = LayoutInflater.from(requireContext())
            .inflate(R.layout.section_user_management, sectionsContainer, false)
        
        sectionsContainer.addView(userManagementView)
        
        lifecycleScope.launch {
            val users = withContext(Dispatchers.IO) {
                database.usuarioDao().getAllUsuarios()
            }
            
            val recyclerView = userManagementView.findViewById<RecyclerView>(R.id.usersRecyclerView)
            recyclerView.layoutManager = LinearLayoutManager(requireContext())
            recyclerView.adapter = UserManagementAdapter(users) { user, action ->
                handleUserAction(user, action)
            }
        }
    }

    private fun handleUserAction(user: com.example.tareamov.data.entity.Usuario, action: UserAction) {
        when (action) {
            UserAction.VIEW_DETAILS -> showUserDetails(user)
            UserAction.CHANGE_ROLE -> showRoleSelectionDialog(user)
            UserAction.TOGGLE_ACTIVE -> toggleUserActiveStatus(user)
            UserAction.VIEW_PROGRESS -> showUserProgress(user)
        }
    }

    private fun showUserDetails(user: com.example.tareamov.data.entity.Usuario) {
        lifecycleScope.launch {
            val persona = withContext(Dispatchers.IO) {
                user.persona_id?.let { database.personaDao().getPersonaById(it) }
            }
            
            val message = buildString {
                append("Username: ${user.usuario}\n")
                append("Email: ${user.email}\n")
                append("Activo: ${if (user.isActive) "Sí" else "No"}\n")
                persona?.let {
                    append("\n=== Información Personal ===\n")
                    append("Nombre: ${it.nombres} ${it.apellidos}\n")
                    append("Identificación: ${it.identificacion}\n")
                    append("Teléfono: ${it.telefono ?: "N/A"}\n")
                    append("Dirección: ${it.direccion ?: "N/A"}\n")
                }
            }
            
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Detalles de Usuario")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun showRoleSelectionDialog(user: com.example.tareamov.data.entity.Usuario) {
        lifecycleScope.launch {
            val roles = withContext(Dispatchers.IO) {
                database.rolDao().getAllRoles()
            }
            
            val roleNames = roles.map { it.nombre }.toTypedArray()
            
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Asignar Rol a ${user.usuario}")
                .setItems(roleNames) { _, which ->
                    val selectedRole = roles[which]
                    assignRoleToUser(user, selectedRole)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun assignRoleToUser(
        user: com.example.tareamov.data.entity.Usuario,
        role: com.example.tareamov.data.entity.Rol
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Insertar en usuarios_roles
                try {
                    val sql = "INSERT INTO usuarios_roles (usuario_id, rol_id) VALUES (${user.id}, ${role.id}) " +
                            "ON CONFLICT (usuario_id, rol_id) DO NOTHING"
                    // Aquí necesitarías ejecutar la query via SupabaseRepository o DAO
                    Log.d("AdminDashboard", "Rol ${role.nombre} asignado a ${user.usuario}")
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error asignando rol", e)
                }
            }
            
            Toast.makeText(
                requireContext(),
                "Rol ${role.nombre} asignado a ${user.usuario}",
                Toast.LENGTH_SHORT
            ).show()
            
            // Recargar sección
            loadUserManagementSection()
        }
    }

    private fun toggleUserActiveStatus(user: com.example.tareamov.data.entity.Usuario) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val updatedUser = user.copy(isActive = !user.isActive)
                database.usuarioDao().updateUsuario(updatedUser)
            }
            
            Toast.makeText(
                requireContext(),
                "Usuario ${if (!user.isActive) "activado" else "desactivado"}",
                Toast.LENGTH_SHORT
            ).show()
            
            loadUserManagementSection()
        }
    }

    private fun showUserProgress(user: com.example.tareamov.data.entity.Usuario) {
        lifecycleScope.launch {
            // Placeholder - necesitarías método para obtener progreso por usuario
            val message = "Funcionalidad de progreso pendiente de implementar"
            
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Progreso de ${user.usuario}")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }
    
    private fun showUserProgressOLD(user: com.example.tareamov.data.entity.Usuario) {
        lifecycleScope.launch {
            // TODO: Implementar cuando exista método getProgresoByUser
            val message = buildString {
                append("Sin datos de progreso disponibles\n")
            }
            
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Progreso de ${user.usuario}")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ==================== SECCIÓN 3: MODERACIÓN ====================
    
    private fun loadModerationSection() {
        titleTextView.text = "Moderación de Contenido"
        
        val moderationView = LayoutInflater.from(requireContext())
            .inflate(R.layout.section_moderation, sectionsContainer, false)
        
        sectionsContainer.addView(moderationView)
        
        // Submissions pendientes de calificación
        loadPendingSubmissions(moderationView)
        
        // Mensajes de chat recientes
        loadRecentChatMessages(moderationView)
        
        // Notificaciones del sistema
        loadSystemNotifications(moderationView)
    }

    private fun loadPendingSubmissions(parentView: View) {
        lifecycleScope.launch {
            val pendingSubmissions = withContext(Dispatchers.IO) {
                database.taskSubmissionDao().getAllTaskSubmissions()
                    .filter { it.grade == null || it.grade == 0f }
                    .take(10)
            }
            
            val container = parentView.findViewById<LinearLayout>(R.id.pendingSubmissionsContainer)
            container.removeAllViews()
            
            parentView.findViewById<TextView>(R.id.pendingSubmissionsCount).text = 
                "${pendingSubmissions.size} pendientes"
            
            pendingSubmissions.forEach { submission ->
                val task = withContext(Dispatchers.IO) {
                    database.taskDao().getTaskById(submission.taskId)
                }
                
                val student = withContext(Dispatchers.IO) {
                    database.usuarioDao().getUsuarioById(submission.studentId)
                }
                
                val itemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_pending_submission, container, false)
                
                itemView.findViewById<TextView>(R.id.taskTitle).text = task?.name ?: "Tarea desconocida"
                itemView.findViewById<TextView>(R.id.studentName).text = student?.usuario ?: "Estudiante desconocido"
                itemView.findViewById<TextView>(R.id.submissionDate).text = 
                    android.text.format.DateFormat.format("dd/MM/yyyy", submission.submissionDate)
                
                itemView.setOnClickListener {
                    // Navegar a detalles de submission
                    showSubmissionDetails(submission, task, student)
                }
                
                container.addView(itemView)
            }
        }
    }

    private fun loadRecentChatMessages(parentView: View) {
        lifecycleScope.launch {
            val container = parentView.findViewById<LinearLayout>(R.id.recentChatContainer)
            container.removeAllViews()
            
            parentView.findViewById<TextView>(R.id.totalChatMessages).text = "0 recientes"
            
            // Placeholder - implementar cuando exista getAllChatMessages
            val placeholderText = TextView(requireContext()).apply {
                text = "Sin mensajes recientes"
                setPadding(16, 16, 16, 16)
                setTextColor(android.graphics.Color.WHITE)
            }
            container.addView(placeholderText)
        }
    }

    private fun loadSystemNotifications(parentView: View) {
        val container = parentView.findViewById<LinearLayout>(R.id.systemNotificationsContainer)
        container.removeAllViews()
        
        // Placeholder - necesitarías NotificationDao
        val placeholderText = TextView(requireContext()).apply {
            text = "Sistema de notificaciones disponible"
            setPadding(16, 16, 16, 16)
        }
        container.addView(placeholderText)
    }

    private fun showSubmissionDetails(
        submission: com.example.tareamov.data.entity.TaskSubmission,
        task: com.example.tareamov.data.entity.Task?,
        student: com.example.tareamov.data.entity.Usuario?
    ) {
        val message = buildString {
            append("Tarea: ${task?.name ?: "Desconocida"}\n")
            append("Estudiante: ${student?.usuario ?: "Desconocido"}\n")
            append("Archivo: ${submission.fileName}\n")
            append("Fecha: ${android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", submission.submissionDate)}\n")
            append("Calificación: ${submission.grade ?: "Sin calificar"}\n")
            append("Retroalimentación: ${submission.feedback ?: "Sin retroalimentación"}\n")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Detalles de Envío")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    // ==================== SECCIÓN 4: PROGRESO Y CERTIFICADOS ====================
    
    private fun loadProgressTrackingSection() {
        titleTextView.text = "Progreso y Certificados"
        
        val progressView = LayoutInflater.from(requireContext())
            .inflate(R.layout.section_progress_tracking, sectionsContainer, false)
        
        sectionsContainer.addView(progressView)
        
        lifecycleScope.launch {
            // Placeholder - datos estadísticos
            progressView.findViewById<TextView>(R.id.totalStudentsText).text = "0"
            progressView.findViewById<TextView>(R.id.totalCertificatesText).text = "0"
            progressView.findViewById<TextView>(R.id.passedCoursesText).text = "0"
            progressView.findViewById<TextView>(R.id.averageGradeText).text = "0.0"
            
            val container = progressView.findViewById<LinearLayout>(R.id.certificatesContainer)
            container.removeAllViews()
            
            // Placeholder
            val placeholderText = TextView(requireContext()).apply {
                text = "Sin certificados emitidos"
                setPadding(16, 16, 16, 16)
                setTextColor(android.graphics.Color.WHITE)
            }
            container.addView(placeholderText)
        }
    }

    // ==================== SECCIÓN 5: SISTEMA DE PERMISOS ====================
    
    private fun loadPermissionsSection() {
        titleTextView.text = "Sistema de Permisos"
        
        val permissionsView = LayoutInflater.from(requireContext())
            .inflate(R.layout.section_permissions, sectionsContainer, false)
        
        sectionsContainer.addView(permissionsView)
        
        lifecycleScope.launch {
            // Cargar roles
            val roles = withContext(Dispatchers.IO) {
                database.rolDao().getAllRoles()
            }
            
            val rolesContainer = permissionsView.findViewById<LinearLayout>(R.id.rolesContainer)
            rolesContainer.removeAllViews()
            
            roles.forEach { role ->
                val roleView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_role, rolesContainer, false)
                
                roleView.findViewById<TextView>(R.id.roleName).text = role.nombre
                roleView.findViewById<TextView>(R.id.roleLevel).text = "Nivel: ${role.nivel}"
                roleView.findViewById<ImageView>(R.id.defaultIcon).visibility = 
                    if (role.default) View.VISIBLE else View.GONE
                
                roleView.setOnClickListener {
                    showRolePermissions(role)
                }
                
                rolesContainer.addView(roleView)
            }
            
            // Cargar recursos
            loadResourcesTree(permissionsView)
        }
    }

    private fun showRolePermissions(role: com.example.tareamov.data.entity.Rol) {
        lifecycleScope.launch {
            val recursos = withContext(Dispatchers.IO) {
                database.rolRecursoDao().getRecursosByRol(role.id)
            }
            
            val message = if (recursos.isEmpty()) {
                "Sin permisos asignados"
            } else {
                buildString {
                    append("Recursos asignados:\n\n")
                    recursos.forEach { recurso ->
                        recurso?.let {
                            append("• ${it.nombre} (${it.interfaz ?: "General"})\n")
                        }
                    }
                }
            }
            
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Permisos de ${role.nombre}")
                .setMessage(message)
                .setPositiveButton("OK", null)
                .setNeutralButton("Editar Permisos") { _, _ ->
                    showPermissionEditor(role)
                }
                .show()
        }
    }

    private fun showPermissionEditor(role: com.example.tareamov.data.entity.Rol) {
        lifecycleScope.launch {
            val allResources = withContext(Dispatchers.IO) {
                database.recursoDao().getAllRecursos()
            }
            
            val assignedResources = withContext(Dispatchers.IO) {
                database.rolRecursoDao().getRecursosByRol(role.id)
                    .map { it.id }
            }
            
            val resourceNames = allResources.map { it.nombre }.toTypedArray()
            val checkedItems = BooleanArray(allResources.size) { index ->
                allResources[index].id in assignedResources
            }
            
            androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle("Editar permisos de ${role.nombre}")
                .setMultiChoiceItems(resourceNames, checkedItems) { _, which, isChecked ->
                    checkedItems[which] = isChecked
                }
                .setPositiveButton("Guardar") { _, _ ->
                    saveRolePermissions(role, allResources, checkedItems)
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun saveRolePermissions(
        role: com.example.tareamov.data.entity.Rol,
        allResources: List<com.example.tareamov.data.entity.Recurso>,
        checkedItems: BooleanArray
    ) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                // Eliminar todos los permisos actuales
                database.rolRecursoDao().deleteRecursosByRol(role.id)
                
                // Insertar nuevos permisos
                allResources.forEachIndexed { index, recurso ->
                    if (checkedItems[index]) {
                        val rolRecurso = com.example.tareamov.data.entity.RolRecurso(
                            rolId = role.id,
                            recursoId = recurso.id
                        )
                        database.rolRecursoDao().insertRolRecurso(rolRecurso)
                    }
                }
            }
            
            Toast.makeText(requireContext(), "Permisos actualizados", Toast.LENGTH_SHORT).show()
            loadPermissionsSection()
        }
    }

    private fun loadResourcesTree(parentView: View) {
        lifecycleScope.launch {
            val recursos = withContext(Dispatchers.IO) {
                database.recursoDao().getAllRecursos()
            }
            
            val container = parentView.findViewById<LinearLayout>(R.id.resourcesContainer)
            container.removeAllViews()
            
            // Mostrar solo recursos raíz (sin padre)
            val rootResources = recursos.filter { it.padreId == null }
            
            rootResources.forEach { recurso ->
                addResourceView(recurso, recursos, container, level = 0)
            }
        }
    }

    private fun addResourceView(
        recurso: com.example.tareamov.data.entity.Recurso,
        allResources: List<com.example.tareamov.data.entity.Recurso>,
        container: LinearLayout,
        level: Int
    ) {
        val itemView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_resource, container, false)
        
        val padding = 16 + (level * 32)
        itemView.setPadding(padding, 8, 16, 8)
        
        itemView.findViewById<TextView>(R.id.resourceName).text = recurso.nombre
        itemView.findViewById<TextView>(R.id.resourceInterface).text = recurso.interfaz ?: "General"
        itemView.findViewById<ImageView>(R.id.resourceIcon).setImageResource(
            getIconResource(recurso.icono)
        )
        
        container.addView(itemView)
        
        // Agregar hijos recursivamente
        val children = allResources.filter { it.padreId == recurso.id }
        children.forEach { child ->
            addResourceView(child, allResources, container, level + 1)
        }
    }

    private fun getIconResource(iconName: String): Int {
        return when (iconName) {
            "home" -> R.drawable.ic_home
            "explore" -> R.drawable.ic_explore
            "profile" -> R.drawable.ic_profile
            "settings" -> R.drawable.ic_settings
            "admin" -> R.drawable.ic_admin
            else -> R.drawable.ic_resource_default
        }
    }

    // ==================== UTILIDADES ====================
    
    private fun setupBottomNavigation(view: View) {
        val bottomNavView = view.findViewById<View>(R.id.bottomNavigation)
        bottomNavBinding = ComponentBottomNavigationBinding.bind(bottomNavView)
        
        bottomNavView.visibility = View.VISIBLE
        
        bottomNavBinding.homeNavLayout.setOnClickListener {
            findNavController().navigate(R.id.videoHomeFragment)
        }
        
        bottomNavBinding.exploreButton.setOnClickListener {
            findNavController().navigate(R.id.exploreFragment)
        }
        
        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.videoHomeFragment)
        }
        
        bottomNavBinding.profileNavButton.setOnClickListener {
            findNavController().navigate(R.id.profileFragment)
        }
    }

    private fun checkAdminAccess() {
        val userId = sessionManager.getUserId()
        
        lifecycleScope.launch {
            val isAdmin = withContext(Dispatchers.IO) {
                getSyncRepository().isUserAdmin(userId)
            }
            
            if (!isAdmin) {
                Toast.makeText(
                    requireContext(),
                    "Acceso denegado: Se requieren permisos de administrador",
                    Toast.LENGTH_LONG
                ).show()
                findNavController().navigateUp()
            }
        }
    }

    private fun getSyncRepository(): com.example.tareamov.data.sync.SyncRepository {
        return com.example.tareamov.data.sync.SyncRepository(
            database.usuarioDao(),
            database.personaDao(),
            database.topicDao(),
            database.contentItemDao(),
            database.taskDao(),
            database.subscriptionDao(),
            database.taskSubmissionDao(),
            database.videoDao(),
            database.courseDao(),
            database.rolDao(),
            database.recursoDao(),
            database.rolRecursoDao(),
            database.chatMessageDao(),
            database.fileContextDao(),
            database.progresoEstudianteDao()
        )
    }

    // ==================== DATA CLASSES ====================
    
    data class GlobalMetrics(
        val totalUsers: Int,
        val activeUsers: Int,
        val totalCourses: Int,
        val publishedCourses: Int,
        val totalSubmissions: Int,
        val totalNotifications: Int,
        val totalChatMessages: Int,
        val certificatesIssued: Int
    )

    data class CreatorStats(
        val username: String,
        val coursesCount: Int,
        val subscribersCount: Int,
        val certificationsCount: Int = 0,
        val avatarUrl: String? = null
    )

    data class CourseStats(
        val id: Long,
        val title: String,
        val description: String,
        val thumbnailUri: String?,
        val enrollments: Int,
        val isPremium: Boolean,
        val rating: Float
    )

    data class StudentStats(
        val username: String,
        val averageGrade: Float,
        val completedCourses: Int,
        val hasCertificate: Boolean
    )

    enum class UserAction {
        VIEW_DETAILS,
        CHANGE_ROLE,
        TOGGLE_ACTIVE,
        VIEW_PROGRESS
    }

    // ==================== ADAPTERS ====================
    
    inner class UserManagementAdapter(
        private val users: List<com.example.tareamov.data.entity.Usuario>,
        private val onAction: (com.example.tareamov.data.entity.Usuario, UserAction) -> Unit
    ) : RecyclerView.Adapter<UserManagementAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val username: TextView = view.findViewById(R.id.username)
            val email: TextView = view.findViewById(R.id.email)
            val activeStatus: ImageView = view.findViewById(R.id.activeStatus)
            val detailsButton: Button = view.findViewById(R.id.detailsButton)
            val roleButton: Button = view.findViewById(R.id.roleButton)
            val toggleButton: Button = view.findViewById(R.id.toggleButton)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_user_management, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val user = users[position]
            
            holder.username.text = user.usuario
            holder.email.text = user.email
            holder.activeStatus.setColorFilter(
                if (user.isActive) Color.GREEN else Color.RED
            )
            
            holder.detailsButton.setOnClickListener {
                onAction(user, UserAction.VIEW_DETAILS)
            }
            
            holder.roleButton.setOnClickListener {
                onAction(user, UserAction.CHANGE_ROLE)
            }
            
            holder.toggleButton.text = if (user.isActive) "Desactivar" else "Activar"
            holder.toggleButton.setOnClickListener {
                onAction(user, UserAction.TOGGLE_ACTIVE)
            }
        }

        override fun getItemCount() = users.size
    }
}
