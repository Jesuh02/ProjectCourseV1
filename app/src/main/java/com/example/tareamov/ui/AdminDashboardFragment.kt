package com.example.tareamov.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.example.tareamov.R
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.util.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

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
    private val TOP_ITEMS_LIMIT = 5
    private val MAX_SUBMISSIONS_PER_COURSE = 50
    
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
        BackendApiService.initialize(requireContext())
        
        initializeViews(view)
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
            val tabModeration: LinearLayout = view.findViewById(R.id.tabModeration)
            val tabProgress: LinearLayout = view.findViewById(R.id.tabProgress)

            // Validar que los tabs necesarios fueron encontrados
            if (tabAnalytics == null || tabModeration == null || tabProgress == null) {
                Log.e("AdminDashboard", "One or more section tabs not found")
                return
            }

            tabAnalytics.setOnClickListener {
                updateTabSelection(tabAnalytics, tabModeration, tabProgress)
                switchSection(DashboardSection.ANALYTICS)
            }

            tabModeration.setOnClickListener {
                updateTabSelection(tabModeration, tabAnalytics, tabProgress)
                switchSection(DashboardSection.MODERATION)
            }

            tabProgress.setOnClickListener {
                updateTabSelection(tabProgress, tabAnalytics, tabModeration)
                switchSection(DashboardSection.PERMISSIONS)
            }

            // Iniciar con Analytics
            switchSection(DashboardSection.ANALYTICS)
            updateTabSelection(tabAnalytics, tabModeration, tabProgress)
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error setting up section tabs", e)
            Toast.makeText(requireContext(), "Error al configurar pestañas", Toast.LENGTH_SHORT).show()
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
        displayAnalyticsMetrics(GlobalMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), animated = false)
        
        // Cargar datos reales en background y actualizar con animación
        lifecycleScope.launch {
            try {
                val metrics = withContext(Dispatchers.IO) {
                    try {
                        val creatorUserId = sessionManager.getUserId()
                        if (creatorUserId <= 0L) {
                            Log.w("AdminDashboard", "No userId found in session")
                            return@withContext GlobalMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                        }

                        val creatorMetricsResult = BackendApiService.getMyCreatorDashboardMetrics()
                        val creatorMetrics = if (creatorMetricsResult is ApiResult.Success) {
                            creatorMetricsResult.data
                        } else {
                            null
                        }
                        val enrollmentAnalytics = BackendApiService
                            .getCreatorEnrollmentAnalytics(weeklyDays = 7, monthlyMonths = 6)
                            .getOrNull()

                        val creatorCourses = BackendApiService.getCoursesByCreatorId(creatorUserId).getOrNull() ?: emptyList()
                        val totalSubmissions = coroutineScope {
                            creatorCourses
                                .map { course ->
                                    async {
                                        BackendApiService
                                            .getSubmissionsByCourse(course.id)
                                            .getOrNull()
                                            ?.size ?: 0
                                    }
                                }
                                .awaitAll()
                                .sum()
                        }

                        GlobalMetrics(
                            totalUsers = creatorMetrics?.enrolledUsersCount ?: 0,
                            activeUsers = creatorMetrics?.enrolledUsersCount ?: 0,
                            totalCourses = creatorMetrics?.totalCourses ?: creatorCourses.size,
                            publishedCourses = creatorCourses.count { it.isPublished },
                            totalSubmissions = totalSubmissions,
                            totalNotifications = 0,
                            totalChatMessages = 0,
                            certificatesIssued = creatorMetrics?.certifiedUsersCount ?: 0,
                            completionRate = creatorMetrics?.completionRate ?: 0,
                            approvalRate = creatorMetrics?.approvalRate ?: 0,
                            satisfactionRate = creatorMetrics?.satisfactionRate ?: 0,
                            weeklyEnrollmentSeries = enrollmentAnalytics?.weekly?.values?.map { it.toFloat() }
                                ?: List(7) { 0f },
                            monthlyEnrollmentSeries = enrollmentAnalytics?.monthly?.values?.map { it.toFloat() }
                                ?: List(6) { 0f },
                            monthlyEnrollmentLabels = enrollmentAnalytics?.monthly?.labels
                                ?.takeIf { it.isNotEmpty() }
                                ?: listOf("Ene", "Feb", "Mar", "Abr", "May", "Jun")
                        )
                    } catch (e: Exception) {
                        Log.w("AdminDashboard", "Error loading analytics: ${e.message}", e)
                        GlobalMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                    }
                }
                
                // Actualizar caché
                cachedMetrics = metrics
                metricsLastUpdated = System.currentTimeMillis()
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
            weeklyChart?.setData(metrics.weeklyEnrollmentSeries)
            
            val monthlyChart = analyticsView.findViewById<com.example.tareamov.ui.components.SimpleBarChart>(R.id.monthlyProgressChart)
            monthlyChart?.setData(
                metrics.monthlyEnrollmentSeries,
                metrics.monthlyEnrollmentLabels
            )
            
            setGlobalPerformanceMetrics(analyticsView, metrics, animated)
            
            sectionsContainer.addView(analyticsView)
            
            // Lazy loading: cargar secciones secundarias después de mostrar lo principal
            lifecycleScope.launch {
                try {
                    // Cargar en paralelo real pero después de mostrar la UI principal
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
            if (currentSection != DashboardSection.ANALYTICS) return

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

            val weeklyChart = analyticsView.findViewById<com.example.tareamov.ui.components.SimpleLineChart>(R.id.weeklyActivityChart)
            weeklyChart?.setData(metrics.weeklyEnrollmentSeries)

            val monthlyChart = analyticsView.findViewById<com.example.tareamov.ui.components.SimpleBarChart>(R.id.monthlyProgressChart)
            monthlyChart?.setData(metrics.monthlyEnrollmentSeries, metrics.monthlyEnrollmentLabels)

            setGlobalPerformanceMetrics(analyticsView, metrics, animated)
            
            Log.d("AdminDashboard", "Analytics metrics updated with animation=$animated")
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error updating analytics metrics", e)
        }
    }

    private fun animateMetricValue(view: View, textViewId: Int, targetValue: Int) {
        val textView = view.findViewById<TextView>(textViewId) ?: return
        val currentValue = textView.text.toString().toIntOrNull() ?: 0
        if (currentValue == targetValue) return
        
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
        val safeTarget = target.coerceIn(0, 100)
        if (progressBar.progress == safeTarget) return
        val animation = android.animation.ObjectAnimator.ofInt(progressBar, "progress", progressBar.progress, safeTarget)
        animation.duration = 1500
        animation.interpolator = android.view.animation.DecelerateInterpolator()
        animation.start()
    }

    private fun setGlobalPerformanceMetrics(view: View, metrics: GlobalMetrics, animated: Boolean) {
        val completionRate = metrics.completionRate.coerceIn(0, 100)
        val approvalRate = metrics.approvalRate.coerceIn(0, 100)
        val satisfactionRate = metrics.satisfactionRate.coerceIn(0, 100)

        val completedBar = view.findViewById<ProgressBar>(R.id.progressCompleted)
        val approvedBar = view.findViewById<ProgressBar>(R.id.progressApproved)
        val satisfactionBar = view.findViewById<ProgressBar>(R.id.progressSatisfaction)

        val completedText = view.findViewById<TextView>(R.id.completedPercentText)
        val approvedText = view.findViewById<TextView>(R.id.approvedPercentText)
        val satisfactionText = view.findViewById<TextView>(R.id.satisfactionPercentText)

        if (animated) {
            completedBar?.let { animateProgressBar(it, completionRate) }
            approvedBar?.let { animateProgressBar(it, approvalRate) }
            satisfactionBar?.let { animateProgressBar(it, satisfactionRate) }

            completedText?.let { animatePercentageText(it, completionRate) }
            approvedText?.let { animatePercentageText(it, approvalRate) }
            satisfactionText?.let { animatePercentageText(it, satisfactionRate) }
        } else {
            completedBar?.progress = completionRate
            approvedBar?.progress = approvalRate
            satisfactionBar?.progress = satisfactionRate

            completedText?.text = "$completionRate%"
            approvedText?.text = "$approvalRate%"
            satisfactionText?.text = "$satisfactionRate%"
        }
    }

    private fun animatePercentageText(textView: TextView, targetValue: Int) {
        val currentValue = textView.text.toString().replace("%", "").toIntOrNull() ?: 0
        if (currentValue == targetValue) return
        android.animation.ValueAnimator.ofInt(currentValue, targetValue).apply {
            duration = 800
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { animator ->
                textView.text = "${animator.animatedValue as Int}%"
            }
            start()
        }
    }

    private suspend fun loadTopCreators(parentView: View) {
        try {
            val creators = withContext(Dispatchers.IO) {
                try {
                    val allCourses = BackendApiService.getCourses().getOrNull() ?: emptyList()
                    val coursesByCreator = allCourses
                        .groupBy { it.creatorUserId }
                        .filterKeys { it > 0L }

                    coroutineScope {
                        coursesByCreator
                            .map { (creatorUserId, courses) ->
                                async {
                                    val creatorUser = BackendApiService.getUserById(creatorUserId).getOrNull()
                                    val creatorUsername = creatorUser?.usuario.orEmpty()
                                    val subscriberCount = creatorUser?.let {
                                        BackendApiService.getSubscriberCount(it.id).getOrNull() ?: 0
                                    } ?: 0

                                    CreatorStats(
                                        username = creatorUsername,
                                        coursesCount = courses.size,
                                        subscribersCount = subscriberCount,
                                        certificationsCount = 0,
                                        avatarUrl = creatorUser?.avatar
                                    )
                                }
                            }
                            .awaitAll()
                            .sortedByDescending { it.coursesCount }
                            .take(TOP_ITEMS_LIMIT)
                    }
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error loading top creators", e)
                    emptyList()
                }
            }

            if (currentSection != DashboardSection.ANALYTICS) return

            val container = parentView.findViewById<LinearLayout>(R.id.topCreatorsContainer)
            container?.removeAllViews()

            if (container == null) {
                Log.e("AdminDashboard", "topCreatorsContainer not found in layout")
                return
            }

            creators.forEachIndexed { index, creator ->
                val itemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_top_creator, container, false)

                val avatarView = itemView.findViewById<de.hdodenhof.circleimageview.CircleImageView>(R.id.creatorAvatar)

                if (!creator.avatarUrl.isNullOrBlank()) {
                    try {
                        Glide.with(requireContext())
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

    private suspend fun loadTopCourses(parentView: View) {
        try {
            val topCourses = withContext(Dispatchers.IO) {
                try {
                    val allCourses = BackendApiService.getCourses().getOrNull() ?: emptyList()

                    val coursesWithCounts = coroutineScope {
                        allCourses
                            .map { course ->
                                async {
                                    val enrolledCount = BackendApiService.getEnrolledCount(course.id).getOrNull() ?: 0
                                    course to enrolledCount
                                }
                            }
                            .awaitAll()
                            .sortedByDescending { it.second }
                            .take(TOP_ITEMS_LIMIT)
                    }

                    coursesWithCounts.map { (course, count) ->
                        CourseStats(
                            id = course.id,
                            title = course.title,
                            description = course.description ?: "",
                            thumbnailUri = course.thumbnailUri,
                            enrollments = count,
                            isPremium = course.isPremium,
                            rating = 4.5f
                        )
                    }
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error loading top courses", e)
                    emptyList()
                }
            }

            if (currentSection != DashboardSection.ANALYTICS) return

            val container = parentView.findViewById<LinearLayout>(R.id.topCoursesContainer)
            container?.removeAllViews()

            if (container == null) {
                Log.e("AdminDashboard", "topCoursesContainer not found in layout")
                return
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

                val thumbnail = itemView.findViewById<ImageView>(R.id.courseThumbnail)
                loadRoundedCourseThumbnail(thumbnail, course.thumbnailUri)

                itemView.setOnClickListener {
                    navigateToCourseDetail(course)
                }

                container.addView(itemView)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.d("AdminDashboard", "Top courses loading cancelled")
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error displaying top courses", e)
        }
    }

    private fun navigateToCourseDetail(course: CourseStats) {
        if (course.id <= 0L) {
            Log.w("AdminDashboard", "Ignoring navigation to invalid course id=${course.id}")
            return
        }

        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.adminDashboardFragment) {
            return
        }

        val bundle = Bundle().apply {
            putLong("courseId", course.id)
            putString("courseName", course.title)
        }

        navController.navigate(R.id.action_adminDashboardFragment_to_courseDetailFragment, bundle)
    }

    private suspend fun loadTopStudents(parentView: View) {
        try {
            val topStudents = withContext(Dispatchers.IO) {
                try {
                    Log.d("AdminDashboard", "Loading top students associated with creator courses")
                    val apiResult = BackendApiService
                        .getTopStudentsByCreatorCourses(TOP_ITEMS_LIMIT)

                    when (apiResult) {
                        is ApiResult.Success -> {
                            val topStudentsData = apiResult.data ?: emptyList()
                            Log.d("AdminDashboard", "Retrieved ${topStudentsData.size} top students from BackendApiService")

                            topStudentsData.mapIndexed { index, studentData ->
                                val userId = studentData.get("user_id")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
                                val username = studentData.get("username")
                                    ?.takeIf { !it.isJsonNull }
                                    ?.asString
                                    ?.takeIf { it.isNotBlank() }
                                    ?: "Usuario $userId"
                                val approvedCourses = studentData.get("approved_courses")
                                    ?.takeIf { !it.isJsonNull }
                                    ?.asInt
                                    ?: 0
                                val averageGrade = studentData.get("average_grade")
                                    ?.takeIf { !it.isJsonNull }
                                    ?.asFloat
                                    ?: 0f
                                val avatarUrl = studentData.get("avatar")
                                    ?.takeIf { !it.isJsonNull }
                                    ?.asString
                                    ?.takeIf { it.isNotBlank() }

                                Log.d("AdminDashboard", "Mapping student #${index + 1}: $username - Approved: $approvedCourses - Grade: $averageGrade")

                                StudentStats(
                                    userId = userId,
                                    username = username,
                                    approvedCourses = approvedCourses,
                                    averageGrade = averageGrade,
                                    avatarUrl = avatarUrl
                                )
                            }
                        }
                        is ApiResult.Error -> {
                            Log.e("AdminDashboard", "API error loading top students: ${apiResult.message} (code: ${apiResult.code})")
                            emptyList<StudentStats>()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error loading top students", e)
                    emptyList<StudentStats>()
                }
            }

            if (currentSection != DashboardSection.ANALYTICS) return

            val container = parentView.findViewById<LinearLayout>(R.id.topStudentsContainer)
            container?.removeAllViews()

            if (container == null) {
                Log.e("AdminDashboard", "topStudentsContainer not found in layout")
                return
            }

            if (topStudents.isEmpty()) {
                val emptyView = TextView(requireContext()).apply {
                    text = "No hay estudiantes inscritos en tus cursos aún.\n\n" +
                        "Para ver estudiantes destacados:\n" +
                        "1. Los estudiantes deben inscribirse a tus cursos\n" +
                        "2. Debe existir registro en la tabla 'progreso_estudiante'\n" +
                        "3. El estado debe estar en 'Ganado'"
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

                    itemView.findViewById<TextView>(R.id.studentName)?.text =
                        "#${index + 1} ${student.username}"
                    itemView.findViewById<TextView>(R.id.coursesCompletedText)?.text =
                        "${student.approvedCourses} cursos aprobados"

                    itemView.findViewById<TextView>(R.id.averageGrade)?.text = String.format("%.2f", student.averageGrade)
                    itemView.findViewById<ImageView>(R.id.certificateIcon)?.visibility = View.GONE

                    val avatarView = itemView.findViewById<ImageView>(R.id.studentAvatar)
                    Glide.with(this@AdminDashboardFragment)
                        .load(student.avatarUrl)
                        .placeholder(R.drawable.placeholder_avatar)
                        .error(R.drawable.placeholder_avatar)
                        .circleCrop()
                        .into(avatarView)

                    itemView.setOnClickListener {
                        navigateToUserProfile(student)
                    }

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

    private fun navigateToUserProfile(student: StudentStats) {
        if (student.userId <= 0L) return
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.adminDashboardFragment) return

        val bundle = Bundle().apply {
            putString("username", student.username)
        }

        navController.navigate(R.id.action_adminDashboardFragment_to_userProfileViewFragment, bundle)
    }

    // ==================== SECCIÓN 2: GESTIÓN DE USUARIOS ====================
    
    private fun loadUserManagementSection() {
        titleTextView.text = "Gestión de Usuarios"
        
        val userManagementView = LayoutInflater.from(requireContext())
            .inflate(R.layout.section_user_management, sectionsContainer, false)
        
        sectionsContainer.addView(userManagementView)
        
        lifecycleScope.launch {
            val users = withContext(Dispatchers.IO) {
                BackendApiService.searchUsers("").getOrNull() ?: emptyList()
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
                user.persona_id?.let { BackendApiService.getPersonaById(it).getOrNull() }
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
                BackendApiService.getRoles().getOrNull() ?: emptyList()
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
                // Assign role via BackendApiService
                try {
                    val result = BackendApiService.assignRole(user.id, role.id)
                    if (result.isSuccess) {
                        Log.d("AdminDashboard", "Rol ${role.nombre} asignado a ${user.usuario}")
                    } else {
                        Log.e("AdminDashboard", "Error asignando rol: ${result.errorMessage()}")
                    }
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
                val newStatus = !user.isActive
                BackendApiService.updateMyProfile(mapOf("isActive" to newStatus))
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
    
    // Cache for moderation data
    private var cachedPendingSubmissions: List<com.example.tareamov.data.entity.TaskSubmission> = emptyList()
    private var cachedCoursesToFinish: List<CourseProgressInfo> = emptyList()
    
    data class CourseProgressInfo(
        val course: com.example.tareamov.data.entity.Course,
        val progress: com.example.tareamov.data.entity.ProgresoEstudiante,
        val enrolledCount: Int = 0
    )
    
    data class SubmissionDetail(
        val submission: com.example.tareamov.data.entity.TaskSubmission,
        val task: com.example.tareamov.data.entity.Task?,
        val student: com.example.tareamov.data.entity.Usuario?,
        val courseName: String = ""
    )
    
    private fun loadModerationSection() {
        titleTextView.text = "Moderación de Contenido"
        
        val moderationView = LayoutInflater.from(requireContext())
            .inflate(R.layout.section_moderation, sectionsContainer, false)
        
        // Start with alpha 0 for fade-in animation
        moderationView.alpha = 0f
        sectionsContainer.addView(moderationView)
        
        // Animate the whole section in
        moderationView.animate()
            .alpha(1f)
            .setDuration(400)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
        
        // Load all data in parallel
        loadModerationMetrics(moderationView)
        loadPendingSubmissions(moderationView)
        loadCoursesToFinish(moderationView)
    }
    
    private fun loadModerationMetrics(parentView: View) {
        lifecycleScope.launch {
            try {
                // Fetch progress and submissions data in parallel
                val progressDeferred = async(Dispatchers.IO) {
                    BackendApiService.getMyProgress().getOrNull() ?: emptyList()
                }
                val submissionsDeferred = async(Dispatchers.IO) {
                    BackendApiService.getMySubmissions(1, 200).getOrNull() ?: emptyList()
                }
                
                val myProgress = progressDeferred.await()
                val allSubmissions = submissionsDeferred.await()
                
                // Calculate metrics
                val totalTareasHoy = myProgress.sumOf { 
                    (it.tareasTotales - it.tareasCompletadas).coerceAtLeast(0) 
                }
                
                val promedio = if (myProgress.isNotEmpty()) {
                    val grades = myProgress.mapNotNull { it.promedio ?: it.calificacionPonderada }
                    if (grades.isNotEmpty()) grades.average().toFloat() else 0f
                } else 0f
                
                // Estimate time: ~30 min per pending task
                val tiempoEstHours = (totalTareasHoy * 0.5f)
                
                // This week submissions count
                val oneWeekAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                val estaSemana = allSubmissions.count { it.submissionDate > oneWeekAgo }
                
                // Animate metric values
                animateModerationMetric(parentView.findViewById(R.id.metricTareasHoy), totalTareasHoy.toString())
                animateModerationMetric(parentView.findViewById(R.id.metricPromedio), String.format("%.1f", promedio))
                animateModerationMetric(parentView.findViewById(R.id.metricTiempoEst), String.format("%.1fh", tiempoEstHours))
                animateModerationMetric(parentView.findViewById(R.id.metricEstaSemana), estaSemana.toString())
                
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading moderation metrics", e)
            }
        }
    }
    
    private fun animateModerationMetric(textView: TextView, targetText: String) {
        // Slide up + fade in animation
        textView.translationY = 20f
        textView.alpha = 0f
        textView.text = targetText
        textView.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(500)
            .setStartDelay(100)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
            .start()
    }

    private fun loadPendingSubmissions(parentView: View) {
        lifecycleScope.launch {
            try {
                showLoadingIndicator()
                
                val userId = sessionManager.getUserId()
                val maxCards = TOP_ITEMS_LIMIT
                
                val submissionDetails = withContext(Dispatchers.IO) {
                    val creatorCourses = BackendApiService.getCoursesByCreatorId(userId).getOrNull() ?: emptyList()
                    val courseTitleById = creatorCourses.associateBy({ it.id }, { it.title })

                    fun isSubmissionGraded(submission: com.example.tareamov.data.entity.TaskSubmission): Boolean {
                        val gradeValue = submission.grade
                        return gradeValue != null && gradeValue.isFinite()
                    }

                    val ungradedByCourse = coroutineScope {
                        creatorCourses
                            .map { course ->
                                async {
                                    // Fetch all submissions to apply strict local filtering by student+task history
                                    // Rule: if any submission of a pair already has grade, do not show it as pending.
                                    val allSubmissions = BackendApiService
                                        .getSubmissionsByCourse(course.id, 1, MAX_SUBMISSIONS_PER_COURSE, ungradedOnly = false)
                                        .getOrNull()
                                        .orEmpty()

                                    // Group by student+task and keep only truly pending pairs
                                    val ungraded = allSubmissions
                                        .groupBy { submission -> "${submission.studentId}_${submission.taskId}" }
                                        .mapNotNull { (_, submissionsByStudentTask) ->
                                            val hasAnyGradeInPair = submissionsByStudentTask.any { isSubmissionGraded(it) }
                                            if (hasAnyGradeInPair) {
                                                null
                                            } else {
                                                submissionsByStudentTask
                                                    .maxByOrNull { submission -> submission.submissionDate }
                                                    ?: submissionsByStudentTask.firstOrNull()
                                            }
                                        }
                                        .filter { submission ->
                                            !isSubmissionGraded(submission)
                                        }

                                    Log.d("AdminDashboard", "Course ${course.id}: ${allSubmissions.size} total → ${ungraded.size} strictly pending")
                                    course.id to ungraded
                                }
                            }
                            .awaitAll()
                    }

                    val selectedPairs = ungradedByCourse
                        .asSequence()
                        .flatMap { (courseId, submissions) -> submissions.asSequence().map { courseId to it } }
                        .take(maxCards)
                        .toList()

                    val taskIds = selectedPairs.map { it.second.taskId }.distinct()
                    val studentIds = selectedPairs.map { it.second.studentId }.distinct()

                    val taskMap = coroutineScope {
                        taskIds
                            .map { taskId ->
                                async { taskId to BackendApiService.getTaskById(taskId).getOrNull() }
                            }
                            .awaitAll()
                            .toMap()
                    }

                    val studentMap = coroutineScope {
                        studentIds
                            .map { studentId ->
                                async { studentId to BackendApiService.getUserById(studentId).getOrNull() }
                            }
                            .awaitAll()
                            .toMap()
                    }

                    selectedPairs.map { (courseId, submission) ->
                        SubmissionDetail(
                            submission = submission,
                            task = taskMap[submission.taskId],
                            student = studentMap[submission.studentId],
                            courseName = courseTitleById[courseId].orEmpty()
                        )
                    }
                }

                if (currentSection != DashboardSection.MODERATION) {
                    hideLoadingIndicator()
                    return@launch
                }
                
                cachedPendingSubmissions = submissionDetails.map { it.submission }
                
                val container = parentView.findViewById<LinearLayout>(R.id.pendingSubmissionsContainer)
                container.removeAllViews()
                
                parentView.findViewById<TextView>(R.id.pendingSubmissionsCount).text = 
                    "${submissionDetails.size} pendientes"
                
                if (submissionDetails.isEmpty()) {
                    val emptyView = createEmptyStateView("No hay tareas pendientes por calificar", "✅")
                    container.addView(emptyView)
                } else {
                    submissionDetails.forEachIndexed { index, detail ->
                        val itemView = createSubmissionItemView(detail, container)
                        
                        // Staggered animation (fade + slide + slight scale)
                        itemView.alpha = 0f
                        itemView.translationY = 28f
                        itemView.scaleX = 0.98f
                        itemView.scaleY = 0.98f
                        container.addView(itemView)
                        
                        itemView.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(360)
                            .setStartDelay((index * 90).toLong())
                            .setInterpolator(FastOutSlowInInterpolator())
                            .start()
                    }
                }

                parentView.findViewById<TextView>(R.id.viewAllSubmissionsLink)?.setOnClickListener {
                    val firstPending = submissionDetails.firstOrNull() ?: return@setOnClickListener
                    navigateToTaskSubmissionFromModeration(firstPending)
                }
                
                hideLoadingIndicator()
                
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading pending submissions", e)
                hideLoadingIndicator()
            }
        }
    }
    
    private fun createSubmissionItemView(
        detail: SubmissionDetail,
        container: LinearLayout
    ): View {
        val itemView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_pending_submission, container, false)
        
        val submission = detail.submission
        val task = detail.task
        val student = detail.student
        
        // Task title
        itemView.findViewById<TextView>(R.id.taskTitle).text = task?.name ?: "Tarea desconocida"
        
        // Course name
        itemView.findViewById<TextView>(R.id.courseName).text = detail.courseName
        
        // Student name
        itemView.findViewById<TextView>(R.id.studentName).text = student?.usuario ?: "Estudiante"
        
        // Student avatar
        val avatarView = itemView.findViewById<ImageView>(R.id.studentAvatar)
        val avatarUrl = student?.avatar
        Glide.with(this)
            .load(avatarUrl)
            .placeholder(R.drawable.placeholder_avatar)
            .error(R.drawable.placeholder_avatar)
            .fallback(R.drawable.placeholder_avatar)
            .circleCrop()
            .into(avatarView)
        
        // Relative time
        val timeDiff = System.currentTimeMillis() - submission.submissionDate
        val timeText = when {
            timeDiff < 3600000 -> "hace ${timeDiff / 60000}m"
            timeDiff < 86400000 -> "hace ${timeDiff / 3600000}h"
            timeDiff < 604800000 -> "hace ${timeDiff / 86400000}d"
            else -> android.text.format.DateFormat.format("dd/MM", submission.submissionDate).toString()
        }
        itemView.findViewById<TextView>(R.id.submissionDate).text = timeText
        
        // Priority badge based on time
        val priorityBadge = itemView.findViewById<TextView>(R.id.priorityBadge)
        when {
            timeDiff < 7200000 -> { // < 2 hours
                priorityBadge.text = "alta"
                priorityBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF5252"))
            }
            timeDiff < 21600000 -> { // < 6 hours
                priorityBadge.text = "media"
                priorityBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
            }
            else -> {
                priorityBadge.text = "baja"
                priorityBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4ADE80"))
            }
        }
        
        // Points (estimated from task context)
        val points = when {
            task?.name?.contains("Final", true) == true -> 100
            task?.name?.contains("Quiz", true) == true -> 25
            task?.name?.contains("Práctica", true) == true -> 40
            task?.name?.contains("Ensayo", true) == true -> 75
            task?.name?.contains("Ejercicio", true) == true -> 50
            else -> 30
        }
        itemView.findViewById<TextView>(R.id.submissionPoints).text = "$points pts"
        
        applyPendingItemPressAnimation(itemView)

        // Approve button (quick grade)
        itemView.findViewById<ImageView>(R.id.btnApprove).setOnClickListener {
            showGradeDialog(submission, task, student)
        }
        
        // Open task submissions directly on selected pending task
        itemView.findViewById<ImageView>(R.id.btnReject).setOnClickListener {
            navigateToTaskSubmissionFromModeration(detail)
        }
        
        // Click on whole card opens the task to grade
        itemView.setOnClickListener {
            navigateToTaskSubmissionFromModeration(detail)
        }
        
        return itemView
    }

    private fun navigateToTaskSubmissionFromModeration(detail: SubmissionDetail) {
        val task = detail.task
        val studentUsername = detail.student?.usuario

        if (task == null || task.id <= 0L) {
            Toast.makeText(requireContext(), "No se pudo abrir la tarea pendiente", Toast.LENGTH_SHORT).show()
            return
        }

        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.adminDashboardFragment) return

        val creatorUsername = sessionManager.getUsername().orEmpty()
        val bundle = Bundle().apply {
            putLong("taskId", task.id)
            putString("taskName", task.name)
            putString("courseCreatorUsername", creatorUsername)
            putString("scrollToSubmissionUsername", studentUsername)
        }

        try {
            navController.navigate(R.id.action_adminDashboardFragment_to_taskSubmissionFragment, bundle)
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error navigating to pending task submissions", e)
            Toast.makeText(requireContext(), "No se pudo abrir la tarea", Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyPendingItemPressAnimation(itemView: View) {
        itemView.setOnTouchListener { view, motionEvent ->
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate()
                        .scaleX(0.985f)
                        .scaleY(0.985f)
                        .setDuration(120)
                        .setInterpolator(FastOutSlowInInterpolator())
                        .start()
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(FastOutSlowInInterpolator())
                        .start()
                }
            }
            false
        }
    }
    
    private fun showGradeDialog(
        submission: com.example.tareamov.data.entity.TaskSubmission,
        task: com.example.tareamov.data.entity.Task?,
        student: com.example.tareamov.data.entity.Usuario?
    ) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(android.R.layout.simple_list_item_1, null)
        
        val editText = android.widget.EditText(requireContext()).apply {
            hint = "Calificación (0-10)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 32, 48, 32)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#9B9BB3"))
        }
        
        val feedbackEdit = android.widget.EditText(requireContext()).apply {
            hint = "Retroalimentación (opcional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setPadding(48, 16, 48, 32)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#9B9BB3"))
            minLines = 2
        }
        
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(editText)
            addView(feedbackEdit)
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.Theme_TareaMov_Dialog)
            .setTitle("Calificar: ${task?.name ?: "Tarea"}")
            .setView(container)
            .setPositiveButton("Calificar") { _, _ ->
                val grade = editText.text.toString().toFloatOrNull() ?: return@setPositiveButton
                val feedback = feedbackEdit.text.toString().takeIf { it.isNotBlank() }
                
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            BackendApiService.gradeSubmission(submission.id, grade, feedback ?: "")
                        }
                        Toast.makeText(requireContext(), "Calificación enviada ✓", Toast.LENGTH_SHORT).show()
                        // Refresh section
                        sectionsContainer.removeAllViews()
                        loadModerationSection()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Error al calificar", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun loadCoursesToFinish(parentView: View) {
        lifecycleScope.launch {
            try {
                val currentUserId = sessionManager.getUserId()
                val courseDetails = withContext(Dispatchers.IO) {
                    val myProgress = BackendApiService.getMyProgress().getOrNull() ?: emptyList()

                    val incompleteCourses = myProgress.filter {
                        val progress = if (it.tareasTotales > 0) {
                            (it.tareasCompletadas.toFloat() / it.tareasTotales.toFloat()) * 100f
                        } else {
                            it.porcentajeProgreso
                        }
                        progress < 90f
                    }

                    coroutineScope {
                        incompleteCourses
                            .mapNotNull { progress ->
                                val courseId = progress.cursoId
                                if (courseId <= 0L) {
                                    Log.w("AdminDashboard", "Skipping invalid progress record with courseId=$courseId")
                                    null
                                } else {
                                    async {
                                        val courseDeferred = async { BackendApiService.getCourseById(courseId).getOrNull() }
                                        val enrolledCountDeferred = async {
                                            BackendApiService.getEnrolledCount(courseId).getOrNull() ?: 0
                                        }
                                        val course = courseDeferred.await()
                                        val enrolledCount = enrolledCountDeferred.await()

                                        course
                                            ?.takeIf { it.creatorUserId != currentUserId }
                                            ?.let { CourseProgressInfo(it, progress, enrolledCount) }
                                    }
                                }
                            }
                            .awaitAll()
                            .filterNotNull()
                    }
                }

                if (currentSection != DashboardSection.MODERATION) return@launch
                
                cachedCoursesToFinish = courseDetails
                
                val container = parentView.findViewById<LinearLayout>(R.id.pendingCoursesContainer)
                container.removeAllViews()
                
                parentView.findViewById<TextView>(R.id.pendingCoursesCount).text = 
                    "${courseDetails.size} cursos"
                
                if (courseDetails.isEmpty()) {
                    val emptyView = createEmptyStateView("¡Todos tus cursos están al día!", "🎉")
                    container.addView(emptyView)
                } else {
                    courseDetails.forEachIndexed { index, info ->
                        val itemView = createCourseProgressItemView(info, container)
                        
                        // Staggered slide-up animation
                        itemView.alpha = 0f
                        itemView.translationY = 40f
                        container.addView(itemView)
                        
                        itemView.animate()
                            .alpha(1f)
                            .translationY(0f)
                            .setDuration(400)
                            .setStartDelay((index * 100 + 200).toLong())
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .start()
                    }
                }
                
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading courses to finish", e)
            }
        }
    }
    
    private fun createCourseProgressItemView(
        info: CourseProgressInfo,
        container: LinearLayout
    ): View {
        val itemView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_course_progress, container, false)
        
        val course = info.course
        val progress = info.progress

        val thumbnailView = itemView.findViewById<ImageView>(R.id.courseThumbnail)
        loadRoundedCourseThumbnail(thumbnailView, course.thumbnailUri)
        
        // Title
        itemView.findViewById<TextView>(R.id.courseTitle).text = course.title
        
        // Calculate actual progress percentage
        val progressPercent = if (progress.tareasTotales > 0) {
            ((progress.tareasCompletadas.toFloat() / progress.tareasTotales.toFloat()) * 100f).toInt()
        } else {
            progress.porcentajeProgreso.toInt()
        }
        
        // Status badge
        val statusBadge = itemView.findViewById<TextView>(R.id.statusBadge)
        when {
            progressPercent >= 80 -> {
                statusBadge.text = "Casi listo"
                statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4ADE80"))
            }
            progressPercent >= 40 -> {
                statusBadge.text = "En progreso"
                statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#8B7FFF"))
            }
            else -> {
                statusBadge.text = "Pendiente"
                statusBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
            }
        }
        
        // Lessons text
        itemView.findViewById<TextView>(R.id.lessonsText).text = 
            "${progress.tareasCompletadas}/${progress.tareasTotales} lecciones"
        
        // Students text
        itemView.findViewById<TextView>(R.id.studentsText).text = 
            "${info.enrolledCount} estudiantes"
        
        // Progress percentage
        itemView.findViewById<TextView>(R.id.progressPercentText).text = "$progressPercent%"
        
        // Progress bar with animation
        val progressBar = itemView.findViewById<ProgressBar>(R.id.courseProgressBar)
        progressBar.progress = 0
        progressBar.postDelayed({
            val animator = android.animation.ObjectAnimator.ofInt(progressBar, "progress", 0, progressPercent)
            animator.duration = 800
            animator.interpolator = android.view.animation.DecelerateInterpolator()
            animator.start()
        }, 300)
        
        // Due date (estimate based on creation)
        val dueDateText = itemView.findViewById<TextView>(R.id.dueDateText)
        if (course.lastModifiedDate.isNotEmpty()) {
            dueDateText.text = "Fecha límite: ${course.lastModifiedDate}"
        } else {
            dueDateText.text = "Sin fecha límite"
        }
        
        // Continue button
        itemView.findViewById<TextView>(R.id.btnContinue).setOnClickListener {
            navigateToCourseDetail(CourseStats(
                id = course.id,
                title = course.title,
                description = course.description,
                thumbnailUri = course.thumbnailUri,
                enrollments = info.enrolledCount,
                isPremium = course.isPremium,
                rating = course.rating
            ))
        }
        
        return itemView
    }
    
    private fun createEmptyStateView(message: String, emoji: String): View {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(32, 48, 32, 48)
            
            addView(TextView(requireContext()).apply {
                text = emoji
                textSize = 36f
                gravity = android.view.Gravity.CENTER
            })
            
            addView(TextView(requireContext()).apply {
                text = message
                setTextColor(Color.parseColor("#B8B3FF"))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 12, 0, 0)
            })
        }
    }

    private fun showSubmissionDetails(
        submission: com.example.tareamov.data.entity.TaskSubmission,
        task: com.example.tareamov.data.entity.Task?,
        student: com.example.tareamov.data.entity.Usuario?
    ) {
        val message = buildString {
            append("📋 Tarea: ${task?.name ?: "Desconocida"}\n\n")
            append("👤 Estudiante: ${student?.usuario ?: "Desconocido"}\n")
            append("📎 Archivo: ${submission.fileName}\n")
            append("📅 Fecha: ${android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", submission.submissionDate)}\n\n")
            append("📊 Calificación: ${submission.grade ?: "Sin calificar"}\n")
            append("💬 Retroalimentación: ${submission.feedback ?: "Sin retroalimentación"}\n")
        }
        
        androidx.appcompat.app.AlertDialog.Builder(requireContext(), R.style.Theme_TareaMov_Dialog)
            .setTitle("Detalles de Envío")
            .setMessage(message)
            .setPositiveButton("Calificar") { _, _ ->
                showGradeDialog(submission, task, student)
            }
            .setNegativeButton("Cerrar", null)
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
        titleTextView.text = "Gestión de Roles"
        
        val permissionsView = LayoutInflater.from(requireContext())
            .inflate(R.layout.section_permissions, sectionsContainer, false)
        
        sectionsContainer.addView(permissionsView)

        val rolesContainer = permissionsView.findViewById<LinearLayout>(R.id.rolesContainer)
        permissionsView.findViewById<TextView>(R.id.configureRolesButton).visibility = View.GONE

        rolesContainer.removeAllViews()
        
        lifecycleScope.launch {
            try {
                val currentUserId = sessionManager.getUserId()
                if (currentUserId <= 0L) {
                    showEmptyRolesState(rolesContainer)
                    return@launch
                }

                val roleCards = withContext(Dispatchers.IO) {
                    val allRoles = BackendApiService.getRoles().getOrNull() ?: emptyList()
                    val userRoleIds = BackendApiService.getUserRoles(currentUserId).getOrNull()?.toSet() ?: emptySet()

                    val assignedRoles = if (userRoleIds.isNotEmpty()) {
                        allRoles.filter { it.id in userRoleIds }
                    } else {
                        allRoles.filter { sessionManager.hasRole(it.id.toInt()) }
                    }

                    coroutineScope {
                        assignedRoles
                            .map { role ->
                                async {
                                    val permissionCount = BackendApiService
                                        .getRecursosByRol(role.id)
                                        .getOrNull()
                                        ?.size ?: 0
                                    RoleCardData(role, permissionCount)
                                }
                            }
                            .map { it.await() }
                            .sortedByDescending { it.role.nivel }
                    }
                }

                if (roleCards.isEmpty()) {
                    showEmptyRolesState(rolesContainer)
                    return@launch
                }

                roleCards.forEachIndexed { index, roleCard ->
                    val roleView = LayoutInflater.from(requireContext())
                        .inflate(R.layout.item_role, rolesContainer, false)

                    roleView.findViewById<ImageView>(R.id.roleIcon)
                        .setImageResource(getRoleIconResource(roleCard.role.nombre))
                    roleView.findViewById<TextView>(R.id.roleName).text = formatRoleName(roleCard.role.nombre)
                    roleView.findViewById<TextView>(R.id.roleMeta).text =
                        "Asignado a tu cuenta · ${roleCard.permissionsCount} permisos"
                    roleView.findViewById<TextView>(R.id.roleStatusChip).text =
                        if (roleCard.role.default) "Predeterminado" else "Activo"

                    applyRoleTouchAnimation(roleView)
                    roleView.setOnClickListener { showRolePermissions(roleCard.role) }

                    rolesContainer.addView(roleView)
                    animateRoleItemEntry(roleView, index)
                }
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error cargando roles del usuario", e)
                showEmptyRolesState(rolesContainer)
            }
        }
    }

    private fun showEmptyRolesState(container: LinearLayout) {
        container.removeAllViews()
        val emptyState = TextView(requireContext()).apply {
            text = "No se encontraron roles activos para esta cuenta"
            setTextColor(ContextCompat.getColor(requireContext(), R.color.light_purple))
            textSize = 14f
            setPadding(8, 14, 8, 14)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        container.addView(emptyState)
    }

    private fun animateRoleItemEntry(roleView: View, index: Int) {
        roleView.alpha = 0f
        roleView.translationY = 24f
        roleView.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(index * 65L)
            .setDuration(280)
            .setInterpolator(FastOutSlowInInterpolator())
            .start()
    }

    private fun applyRoleTouchAnimation(roleView: View) {
        roleView.setOnTouchListener { view, motionEvent ->
            when (motionEvent.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.98f).scaleY(0.98f).setDuration(90).start()
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
                }
            }
            false
        }
    }

    private fun formatRoleName(roleName: String): String {
        val cleaned = roleName.trim().replace("_", " ")
        return cleaned.lowercase(Locale.getDefault()).replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
    }

    private fun getRoleIconResource(roleName: String): Int {
        val normalized = roleName.trim().lowercase(Locale.getDefault())
        return when {
            normalized.contains("admin") -> R.drawable.ic_admin
            normalized.contains("docente") || normalized.contains("profesor") -> R.drawable.ic_school
            normalized.contains("estudiante") || normalized.contains("usuario") -> R.drawable.ic_person
            else -> R.drawable.ic_profile
        }
    }

    private data class RoleCardData(
        val role: com.example.tareamov.data.entity.Rol,
        val permissionsCount: Int
    )

    private fun showRolePermissions(role: com.example.tareamov.data.entity.Rol) {
        lifecycleScope.launch {
            val recursos = withContext(Dispatchers.IO) {
                BackendApiService.getRecursosByRol(role.id).getOrNull() ?: emptyList()
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
                .show()
        }
    }

    private fun loadResourcesTree(parentView: View) {
        lifecycleScope.launch {
            val recursos = withContext(Dispatchers.IO) {
                BackendApiService.getRecursos().getOrNull() ?: emptyList()
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

    private fun loadRoundedCourseThumbnail(imageView: ImageView, imageUrl: String?) {
        val cornerRadiusPx = (12 * resources.displayMetrics.density).toInt()
        val source: Any = imageUrl?.takeIf { it.isNotBlank() } ?: R.drawable.placeholder_image

        Glide.with(this)
            .load(source)
            .placeholder(R.drawable.placeholder_image)
            .error(R.drawable.placeholder_image)
            .transform(CenterCrop(), RoundedCorners(cornerRadiusPx))
            .into(imageView)
    }

    // ==================== UTILIDADES ====================

    private fun checkAdminAccess() {
        // Permitir acceso a todos los usuarios
        Log.d("AdminDashboard", "Panel de creador accesible para todos los usuarios")
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
        val certificatesIssued: Int,
        val completionRate: Int,
        val approvalRate: Int,
        val satisfactionRate: Int,
        val weeklyEnrollmentSeries: List<Float> = List(7) { 0f },
        val monthlyEnrollmentSeries: List<Float> = List(6) { 0f },
        val monthlyEnrollmentLabels: List<String> = listOf("Ene", "Feb", "Mar", "Abr", "May", "Jun")
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
        val userId: Long,
        val username: String,
        val approvedCourses: Int,
        val averageGrade: Float,
        val avatarUrl: String?
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
