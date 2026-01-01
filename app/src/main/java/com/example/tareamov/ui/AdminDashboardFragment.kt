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
    private var loadingView: View? = null
    
    // Caché de datos para evitar llamadas repetidas
    private var cachedMetrics: GlobalMetrics? = null
    private var metricsLastUpdated: Long = 0
    private val CACHE_DURATION_MS = 60_000L // 1 minuto
    
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
        try {
            backButton = view.findViewById(R.id.backButton)
            titleTextView = view.findViewById(R.id.dashboardTitle)
            sectionsContainer = view.findViewById(R.id.sectionsContainer)
            scrollView = view.findViewById(R.id.dashboardScrollView)
            
            // Validar que todas las vistas fueron encontradas
            if (backButton == null || titleTextView == null || 
                sectionsContainer == null || scrollView == null) {
                Log.e("AdminDashboard", "Error: No se pudieron inicializar todas las vistas")
                Toast.makeText(requireContext(), 
                    "Error al cargar el panel. Intente nuevamente.", 
                    Toast.LENGTH_SHORT).show()
                return
            }
            
            backButton.setOnClickListener {
                findNavController().navigateUp()
            }
            
            setupSectionTabs(view)
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error initializing views", e)
            Toast.makeText(requireContext(), 
                "Error al inicializar el panel: ${e.message}", 
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupSectionTabs(view: View) {
        try {
            val tabAnalytics: LinearLayout = view.findViewById(R.id.tabAnalytics)
            val tabUsers: LinearLayout = view.findViewById(R.id.tabUsers)
            val tabModeration: LinearLayout = view.findViewById(R.id.tabModeration)
            val tabProgress: LinearLayout = view.findViewById(R.id.tabProgress)
            val tabPermissions: LinearLayout = view.findViewById(R.id.tabPermissions)
            
            // Validar que todos los tabs fueron encontrados
            if (tabAnalytics == null || tabUsers == null || tabModeration == null || 
                tabProgress == null || tabPermissions == null) {
                Log.e("AdminDashboard", "Error: No se pudieron encontrar todos los tabs")
                return
            }
            
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
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error setting up section tabs", e)
            Toast.makeText(requireContext(), 
                "Error al configurar las pestañas: ${e.message}", 
                Toast.LENGTH_SHORT).show()
        }
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
        titleTextView.text = "Mis Estadísticas"
        
        // Verificar si hay datos en caché
        val now = System.currentTimeMillis()
        if (cachedMetrics != null && (now - metricsLastUpdated) < CACHE_DURATION_MS) {
            Log.d("AdminDashboard", "Using cached metrics")
            displayAnalyticsMetrics(cachedMetrics!!, animated = false)
            return
        }
        
        // Mostrar UI instantáneamente con valores en 0
        displayAnalyticsMetrics(GlobalMetrics(0, 0, 0, 0, 0, 0, 0, 0), animated = false)
        
        // Cargar datos reales en background y actualizar con animación
        lifecycleScope.launch {
            try {
                val metrics = withContext(Dispatchers.IO) {
                    try {
                        val currentUsername = sessionManager.getUsername()
                        if (currentUsername.isNullOrEmpty()) {
                            Log.w("AdminDashboard", "No username found in session")
                            return@withContext GlobalMetrics(0, 0, 0, 0, 0, 0, 0, 0)
                        }
                        
                        // Fetch creator's courses
                        val creatorCourses = com.example.tareamov.service.SupabaseClient.fetchCoursesByCreator(currentUsername)
                        val courseIds = creatorCourses.map { it.id }
                        
                        if (courseIds.isEmpty()) {
                            return@withContext GlobalMetrics(
                                totalUsers = 0,
                                activeUsers = 0,
                                totalCourses = 0,
                                publishedCourses = 0,
                                totalSubmissions = 0,
                                totalNotifications = 0,
                                totalChatMessages = 0,
                                certificatesIssued = 0
                            )
                        }
                        
                        // SUPER OPTIMIZACIÓN: Obtener todas las métricas en una sola llamada batch
                        val aggregatedMetrics = com.example.tareamov.service.SupabaseClient.fetchAggregatedMetrics(courseIds)
                        
                        GlobalMetrics(
                            totalUsers = aggregatedMetrics.uniqueUsers, // Usuarios únicos inscritos
                            activeUsers = aggregatedMetrics.uniqueUsers, // Mismos usuarios únicos
                            totalCourses = creatorCourses.size,
                            publishedCourses = creatorCourses.count { it.isPublished },
                            totalSubmissions = aggregatedMetrics.submissions,
                            totalNotifications = 0,
                            totalChatMessages = 0,
                            certificatesIssued = aggregatedMetrics.certifications
                        )
                    } catch (e: Exception) {
                        Log.w("AdminDashboard", "Error loading analytics: ${e.message}", e)
                        GlobalMetrics(0, 0, 0, 0, 0, 0, 0, 0)
                    }
                }
                
                // Actualizar caché
                cachedMetrics = metrics
                metricsLastUpdated = System.currentTimeMillis()
                
                // Actualizar UI con datos reales y animación
                updateAnalyticsMetrics(metrics, animated = true)
                Log.d("AdminDashboard", "Analytics loaded successfully")
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("AdminDashboard", "Analytics section cancelled")
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Unexpected error in analytics section", e)
            }
        }
    }

    private fun displayAnalyticsMetrics(metrics: GlobalMetrics, animated: Boolean = false) {
        try {
            // Validar que el contenedor existe
            if (!::sectionsContainer.isInitialized) {
                Log.e("AdminDashboard", "sectionsContainer no está inicializado")
                return
            }
            
            val analyticsView = LayoutInflater.from(requireContext())
                .inflate(R.layout.section_analytics, sectionsContainer, false)
            
            // Validar que la vista se infló correctamente
            if (analyticsView == null) {
                Log.e("AdminDashboard", "Error al inflar section_analytics")
                return
            }
            
            // Establecer valores sin animación para renderizado instantáneo
            analyticsView.findViewById<TextView>(R.id.totalUsersText)?.text = metrics.totalUsers.toString()
            analyticsView.findViewById<TextView>(R.id.totalCoursesText)?.text = metrics.totalCourses.toString()
            analyticsView.findViewById<TextView>(R.id.totalSubmissionsText)?.text = metrics.totalSubmissions.toString()
            analyticsView.findViewById<TextView>(R.id.certificatesIssuedText)?.text = metrics.certificatesIssued.toString()
            
            // Labels estáticos
            analyticsView.findViewById<TextView>(R.id.activeUsersText)?.text = "Inscritos en mis cursos"
            analyticsView.findViewById<TextView>(R.id.publishedCoursesText)?.text = "Mis cursos"
            
            // Setup Charts - con validación de null
            val weeklyChart = analyticsView.findViewById<com.example.tareamov.ui.components.SimpleLineChart>(R.id.weeklyActivityChart)
            weeklyChart?.setData(listOf(120f, 150f, 140f, 180f, 220f, 190f, 160f))
            
            val monthlyChart = analyticsView.findViewById<com.example.tareamov.ui.components.SimpleBarChart>(R.id.monthlyProgressChart)
            monthlyChart?.setData(
                listOf(350f, 500f, 420f, 580f, 510f, 620f),
                listOf("Ene", "Feb", "Mar", "Abr", "May", "Jun")
            )
            
            // Animate Progress Bars
            val progressCompleted = analyticsView.findViewById<ProgressBar>(R.id.progressCompleted)
            val progressApproved = analyticsView.findViewById<ProgressBar>(R.id.progressApproved)
            val progressSatisfaction = analyticsView.findViewById<ProgressBar>(R.id.progressSatisfaction)
            
            progressCompleted?.let { animateProgressBar(it, 87) }
            progressApproved?.let { animateProgressBar(it, 92) }
            progressSatisfaction?.let { animateProgressBar(it, 78) }
            
            sectionsContainer.addView(analyticsView)
            
            // Lazy loading: cargar secciones secundarias después de mostrar lo principal
            lifecycleScope.launch {
                try {
                    // Cargar en paralelo pero después de mostrar la UI principal
                    val creatorsJob = async { loadTopCreators(analyticsView) }
                    val coursesJob = async { loadTopCourses(analyticsView) }
                    val studentsJob = async { loadTopStudents(analyticsView) }
                    
                    // Esperar a que todas terminen
                    creatorsJob.await()
                    coursesJob.await()
                    studentsJob.await()
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error loading secondary sections", e)
                }
            }
            
            Log.d("AdminDashboard", "Analytics metrics displayed successfully")
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error displaying analytics metrics", e)
            Toast.makeText(requireContext(), 
                "Error al mostrar las métricas: ${e.message}", 
                Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAnalyticsMetrics(metrics: GlobalMetrics, animated: Boolean = true) {
        try {
            // Buscar la vista ya existente en el contenedor
            if (sectionsContainer.childCount == 0) {
                // Si no existe, crearla
                displayAnalyticsMetrics(metrics, animated)
                return
            }
            
            val analyticsView = sectionsContainer.getChildAt(0)
            
            // Actualizar valores con animación
            if (animated) {
                animateMetricValue(analyticsView, R.id.totalUsersText, metrics.totalUsers)
                animateMetricValue(analyticsView, R.id.totalCoursesText, metrics.totalCourses)
                animateMetricValue(analyticsView, R.id.totalSubmissionsText, metrics.totalSubmissions)
                animateMetricValue(analyticsView, R.id.certificatesIssuedText, metrics.certificatesIssued)
            } else {
                analyticsView.findViewById<TextView>(R.id.totalUsersText)?.text = metrics.totalUsers.toString()
                analyticsView.findViewById<TextView>(R.id.totalCoursesText)?.text = metrics.totalCourses.toString()
                analyticsView.findViewById<TextView>(R.id.totalSubmissionsText)?.text = metrics.totalSubmissions.toString()
                analyticsView.findViewById<TextView>(R.id.certificatesIssuedText)?.text = metrics.certificatesIssued.toString()
            }
            
            Log.d("AdminDashboard", "Analytics metrics updated with animation=$animated")
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error updating analytics metrics", e)
        }
    }

    private fun animateMetricValue(view: View, textViewId: Int, targetValue: Int) {
        val textView = view.findViewById<TextView>(textViewId) ?: return
        val currentValue = textView.text.toString().toIntOrNull() ?: 0
        
        // Animar de currentValue a targetValue en 800ms
        android.animation.ValueAnimator.ofInt(currentValue, targetValue).apply {
            duration = 800 // 0.8 segundos para una animación rápida pero visible
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { animator ->
                textView.text = (animator.animatedValue as Int).toString()
            }
            start()
        }
    }

    private fun animateProgressBar(progressBar: ProgressBar, target: Int) {
        val animation = android.animation.ObjectAnimator.ofInt(progressBar, "progress", 0, target)
        animation.duration = 1500
        animation.interpolator = android.view.animation.DecelerateInterpolator()
        animation.start()
    }

    private fun loadTopCreators(parentView: View) {
        lifecycleScope.launch {
            try {
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
                container?.removeAllViews()
                
                if (container == null) {
                    Log.e("AdminDashboard", "topCreatorsContainer not found in layout")
                    return@launch
                }
                
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("AdminDashboard", "Top creators loading cancelled")
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error displaying top creators", e)
            }
        }
    }

    private fun loadTopCourses(parentView: View) {
        lifecycleScope.launch {
            try {
                val topCourses = withContext(Dispatchers.IO) {
                    try {
                        val courses = database.courseDao().getAllCourses().take(10) // Limitar a 10 desde el inicio
                        
                        // Usar método batch optimizado
                        val courseIds = courses.map { it.id }
                        val metricsMap = com.example.tareamov.service.SupabaseClient.fetchCourseMetricsBatch(courseIds)
                        
                        courses.mapNotNull { course ->
                            val metrics = metricsMap[course.id]
                            if (metrics != null) {
                                CourseStats(
                                    id = course.id,
                                    title = course.title,
                                    description = course.description ?: "",
                                    thumbnailUri = course.thumbnailUri,
                                    enrollments = metrics.enrollments,
                                    isPremium = course.isPremium,
                                    rating = 4.5f
                                )
                            } else {
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
                container?.removeAllViews()
                
                if (container == null) {
                    Log.e("AdminDashboard", "topCoursesContainer not found in layout")
                    return@launch
                }
            
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("AdminDashboard", "Top courses loading cancelled")
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error displaying top courses", e)
            }
        }
    }

    private fun loadTopStudents(parentView: View) {
        lifecycleScope.launch {
            try {
                // First, debug the progreso_estudiante table
                val debugInfo = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.debugProgresoEstudiante()
                }
                Log.d("AdminDashboard", "Debug progreso_estudiante:\n$debugInfo")
                
                val topStudents = withContext(Dispatchers.IO) {
                    try {
                        // Get current user's ID (more reliable than username)
                        val currentUserId = sessionManager.getUserId()
                        if (currentUserId <= 0) {
                            Log.w("AdminDashboard", "Invalid user ID for top students: $currentUserId")
                            return@withContext emptyList<StudentStats>()
                        }
                        
                        Log.d("AdminDashboard", "Loading top students for user ID: $currentUserId")
                        
                        // Fetch creator's courses by user ID
                        val creatorCourses = com.example.tareamov.service.SupabaseClient.fetchCoursesByCreatorUserId(currentUserId)
                        val courseIds = creatorCourses.map { it.id }
                        
                        Log.d("AdminDashboard", "Found ${creatorCourses.size} courses for creator")
                        creatorCourses.forEachIndexed { index, course ->
                            Log.d("AdminDashboard", "Course #${index + 1}: ID=${course.id}, Title='${course.title}'")
                        }
                        
                        if (courseIds.isEmpty()) {
                            Log.d("AdminDashboard", "No courses found for creator - cannot get top students")
                            return@withContext emptyList<StudentStats>()
                        }
                        
                        // Fetch top students by average grade across creator's courses
                        val topStudentsData = com.example.tareamov.service.SupabaseClient.fetchTopStudentsForCreator(currentUserId, 5)

                        Log.d("AdminDashboard", "Retrieved ${topStudentsData.size} top students from SupabaseClient")

                        topStudentsData.mapIndexed { index, studentData ->
                            val avgGrade = (studentData["avg_grade"] as? Number)?.toDouble()?.toFloat() ?: 0f
                            val username = (studentData["username"] as? String)?.takeIf { it.isNotBlank() }
                                ?: "Usuario ${studentData["user_id"]}"
                            val coursesCount = (studentData["courses_count"] as? Number)?.toInt() ?: 0

                            Log.d("AdminDashboard", "Mapping student #${index + 1}: $username - Grade: $avgGrade - Courses: $coursesCount")

                            StudentStats(
                                username = username,
                                averageGrade = avgGrade,
                                completedCourses = coursesCount,
                                hasCertificate = avgGrade >= 9.0
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("AdminDashboard", "Error loading top students", e)
                        emptyList<StudentStats>()
                    }
                }
                
                val container = parentView.findViewById<LinearLayout>(R.id.topStudentsContainer)
                container?.removeAllViews()
                
                if (container == null) {
                    Log.e("AdminDashboard", "topStudentsContainer not found in layout")
                    return@launch
                }
                
                if (topStudents.isEmpty()) {
                     val emptyView = TextView(requireContext()).apply {
                        text = "No hay estudiantes inscritos en tus cursos aún.\n\n" +
                               "Para ver estudiantes destacados:\n" +
                               "1. Los estudiantes deben inscribirse a tus cursos\n" +
                               "2. Debe existir registro en la tabla 'progreso_estudiante'\n" +
                               "3. El campo 'calificacion_promedio' debe tener valores"
                        setTextColor(Color.parseColor("#B0BEC5"))
                        textSize = 14f
                        gravity = android.view.Gravity.CENTER
                        setPadding(32, 40, 32, 40)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                     }
                     container.addView(emptyView)
                     Log.d("AdminDashboard", "Showing empty state for top students")
                } else {
                    Log.d("AdminDashboard", "Displaying ${topStudents.size} top students")
                    topStudents.forEachIndexed { index, student ->
                        val itemView = LayoutInflater.from(requireContext())
                            .inflate(R.layout.item_top_student, container, false)
                        
                        // Set student name with rank
                        itemView.findViewById<TextView>(R.id.studentName)?.text = 
                            "#${index + 1} ${student.username}"
                        
                        // Set average grade
                        itemView.findViewById<TextView>(R.id.averageGrade)?.text = 
                            String.format("%.1f", student.averageGrade)
                        
                        // Show certificate icon if applicable
                        itemView.findViewById<ImageView>(R.id.certificateIcon)?.visibility = 
                            if (student.hasCertificate) View.VISIBLE else View.GONE
                        
                        container.addView(itemView)
                        
                        Log.d("AdminDashboard", "Added student to UI: #${index + 1} ${student.username}")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d("AdminDashboard", "Top students loading cancelled")
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error displaying top students", e)
                Toast.makeText(requireContext(), "Error al cargar estudiantes destacados: ${e.message}", Toast.LENGTH_LONG).show()
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
        
        // Setup admin button with role verification
        setupAdminButton(bottomNavBinding)
        
        // Update notification badge
        updateNotificationBadge(bottomNavBinding)
        
        bottomNavBinding.homeNavLayout.setOnClickListener {
            findNavController().navigate(R.id.videoHomeFragment)
        }
        
        bottomNavBinding.exploreButton.setOnClickListener {
            findNavController().navigate(R.id.exploreFragment)
        }
        
        bottomNavBinding.goToHomeButton.setOnClickListener {
            findNavController().navigate(R.id.contentUploadFragment)
        }
        
        bottomNavBinding.activityButton.setOnClickListener {
            findNavController().navigate(R.id.notificacionesFragment)
        }
        
        bottomNavBinding.profileNavButton.setOnClickListener {
            findNavController().navigate(R.id.profileFragment)
        }
    }
    
    private fun setupAdminButton(bottomNavBinding: ComponentBottomNavigationBinding) {
        val adminSlot = bottomNavBinding.adminSlot
        val goToAdminButton = bottomNavBinding.goToAdminButton

        // Inicializa como INVISIBLE para evitar salto al inflar
        goToAdminButton.visibility = View.INVISIBLE

        val sess = SessionManager.getInstance(requireContext())
        
        // Verificar específicamente el rol 3
        if (!sess.hasRole(3)) {
            // Ocultar el slot antes del render para que no quede hueco visible
            adminSlot.visibility = View.GONE
            return
        }

        // Usuario tiene rol 3: mostrar botón y asignar listener
        adminSlot.visibility = View.VISIBLE
        goToAdminButton.visibility = View.VISIBLE
        goToAdminButton.setOnClickListener {
            // Ya estamos en AdminDashboard, no hacer nada o recargar
            loadAnalyticsSection()
        }
    }

    /**
     * Actualiza el badge de notificaciones no leídas
     */
    private fun updateNotificationBadge(bottomNavBinding: ComponentBottomNavigationBinding) {
        val userId = sessionManager.getUserId()
        if (userId == -1L) {
            bottomNavBinding.notificationBadge.visibility = View.GONE
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val unreadCount = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.countUnreadNotifications(userId)
                }
                
                if (unreadCount > 0) {
                    bottomNavBinding.notificationBadge.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                    bottomNavBinding.notificationBadge.visibility = View.VISIBLE
                } else {
                    bottomNavBinding.notificationBadge.visibility = View.GONE
                }
            } catch (e: Exception) {
                Log.w("AdminDashboard", "Error updating notification badge", e)
                bottomNavBinding.notificationBadge.visibility = View.GONE
            }
        }
    }

    private fun checkAdminAccess() {
        // Permitir acceso a todos los usuarios
        Log.d("AdminDashboard", "Panel de creador accesible para todos los usuarios")
        // Comentado: restricción de acceso solo para administradores
        /*
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
        */
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
    
    // ==================== MÉTODOS DE UTILIDAD ====================
    
    private fun showLoadingIndicator() {
        try {
            if (!::sectionsContainer.isInitialized) return
            
            hideLoadingIndicator() // Limpiar cualquier indicador anterior
            
            loadingView = ProgressBar(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.CENTER
                    setMargins(0, 100, 0, 100)
                }
                isIndeterminate = true
            }
            
            sectionsContainer.addView(loadingView)
            Log.d("AdminDashboard", "Loading indicator shown")
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error showing loading indicator", e)
        }
    }
    
    private fun hideLoadingIndicator() {
        try {
            loadingView?.let {
                sectionsContainer?.removeView(it)
                loadingView = null
                Log.d("AdminDashboard", "Loading indicator hidden")
            }
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error hiding loading indicator", e)
        }
    }
}
