package com.example.tareamov.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import java.text.Normalizer
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
    
    private lateinit var backButton: ImageButton
    private lateinit var titleTextView: TextView
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var scrollView: androidx.core.widget.NestedScrollView
    private var loadingView: View? = null
    
    private val TOP_ITEMS_LIMIT = 5
    private val MAX_SUBMISSIONS_PER_COURSE = 50
    
    private var currentSection: DashboardSection = DashboardSection.ANALYTICS
    private var studentProgressListContainer: LinearLayout? = null
    private var coursesToFinishListContainer: LinearLayout? = null
    private var reinforcementListContainer: LinearLayout? = null
    private var pendingTasksListContainer: LinearLayout? = null
    private var pendingTasksCountView: TextView? = null
    private var pendingTaskCourseFilter: String = ""
    private var pendingTaskSubjectFilter: String = ""

    companion object {
        private const val CACHE_DURATION_MS = 60_000L
        private var cachedMetrics: GlobalMetrics? = null
        private var metricsLastUpdated: Long = 0
        private var cachedCertificates: List<BackendApiService.CertificateItem> = emptyList()
        private var cachedCourseProgressData: List<Pair<com.example.tareamov.data.entity.Course, List<com.example.tareamov.data.entity.ProgresoEstudiante>>> = emptyList()
        private var cachedStudentUsersData: Map<Long, com.example.tareamov.data.entity.Usuario> = emptyMap()
        private var cachedSubjectsData: Map<Long, List<com.example.tareamov.data.entity.Subject>> = emptyMap()
        private var cachedPendingSubmissionDetails: List<SubmissionDetail> = emptyList()
        private var cachedCoursesToFinish: List<CourseProgressInfo> = emptyList()
        private var cachedRoleCards: List<RoleCardData> = emptyList()
        private var cachedReinforcementResults: List<ReinforcementDetail> = emptyList()
        private var cachedPendingTasks: List<PendingTaskDetail> = emptyList()
        private var cachedSubjectProgressData: List<com.google.gson.JsonObject> = emptyList()
    }

    enum class DashboardSection {
        ANALYTICS,          // Métricas globales
        USER_MANAGEMENT,    // Gestión de usuarios y roles
        MODERATION,         // Moderación de contenido
        PROGRESS_TRACKING,  // Progreso y certificados
        PERMISSIONS,        // Sistema de permisos
        CERTIFICATES,       // Mis certificados
        ACTIVATION,         // Activar / desactivar usuarios
        ENROLLMENT          // Solicitudes de matrícula (solo rol 3)
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

        // Handle deep-link arguments from notifications
        arguments?.let { args ->
            val section = args.getString("navigateToSection")
            if (section == "ENROLLMENT") {
                pendingSection = DashboardSection.ENROLLMENT
                args.getString("filterUsername")?.takeIf { it.isNotBlank() }?.let {
                    enrollmentUserFilter = it
                }
                args.getString("filterCourse")?.takeIf { it.isNotBlank() }?.let {
                    enrollmentCourseFilter = it
                }
            }
        }

        initializeViews(view)
        checkAdminAccess()

        // Observe reactive cache invalidation — auto-reload current section
        viewLifecycleOwner.lifecycleScope.launch {
            com.example.tareamov.util.AppCache.adminRefresh.collect {
                Log.d("AdminDashboard", "adminRefresh event received, reloading section: $currentSection")
                switchSection(currentSection)
            }
        }
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
            val tabCertificates: LinearLayout = view.findViewById(R.id.tabCertificates)

            if (tabAnalytics == null || tabModeration == null || tabProgress == null || tabCertificates == null) {
                Log.e("AdminDashboard", "One or more section tabs not found")
                return
            }

            // —— Programmatically created tabs ——
            val tabsScrollContainer = view.findViewById<android.widget.HorizontalScrollView>(R.id.tabsScrollContainer)
            val tabsRow = tabsScrollContainer?.getChildAt(0) as? LinearLayout

            val role1OnlyTabs = hasOnlyRoleOne()
            val adminTabsOnly = hasAdminRole()
            if (role1OnlyTabs) {
                tabAnalytics.visibility = View.GONE
                tabCertificates.visibility = View.GONE
            }

            if (!adminTabsOnly) {
                tabCertificates.visibility = View.GONE
            }

            val tabActivation: LinearLayout? = if (role1OnlyTabs || !adminTabsOnly) null else createExtraTab("Activación")
            val tabEnrollment: LinearLayout? = if (!role1OnlyTabs && adminTabsOnly) createExtraTab("Matrícula") else null

            if (tabActivation != null) tabsRow?.addView(tabActivation)
            if (tabEnrollment != null) tabsRow?.addView(tabEnrollment)

            val allTabs = listOfNotNull(
                if (role1OnlyTabs) null else tabAnalytics,
                tabModeration,
                tabProgress,
                if (role1OnlyTabs || !adminTabsOnly) null else tabCertificates,
                tabActivation,
                tabEnrollment
            )

            fun selectTab(selected: LinearLayout) {
                allTabs.forEach {
                    it.setBackgroundResource(
                        if (it === selected) R.drawable.tab_selected_background
                        else R.drawable.tab_unselected_background
                    )
                }
            }

            if (!role1OnlyTabs) {
                tabAnalytics.setOnClickListener {
                    selectTab(tabAnalytics)
                    switchSection(DashboardSection.ANALYTICS)
                }
            }
            tabModeration.setOnClickListener {
                selectTab(tabModeration)
                switchSection(DashboardSection.MODERATION)
            }
            tabProgress.setOnClickListener {
                selectTab(tabProgress)
                switchSection(DashboardSection.PERMISSIONS)
            }
            if (!role1OnlyTabs && adminTabsOnly) {
                tabCertificates.setOnClickListener {
                    selectTab(tabCertificates)
                    switchSection(DashboardSection.CERTIFICATES)
                }
            }
            tabActivation?.setOnClickListener {
                selectTab(tabActivation)
                switchSection(DashboardSection.ACTIVATION)
            }
            tabEnrollment?.setOnClickListener {
                selectTab(tabEnrollment)
                switchSection(DashboardSection.ENROLLMENT)
            }

            if (role1OnlyTabs) {
                switchSection(DashboardSection.MODERATION)
                selectTab(tabModeration)
            } else if (pendingSection == DashboardSection.ENROLLMENT && tabEnrollment != null) {
                selectTab(tabEnrollment)
                switchSection(DashboardSection.ENROLLMENT)
                pendingSection = null
            } else {
                switchSection(DashboardSection.ANALYTICS)
                selectTab(tabAnalytics)
            }
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error setting up section tabs", e)
            Toast.makeText(requireContext(), "Error al configurar pestañas", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createExtraTab(label: String): LinearLayout {
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.tab_unselected_background)
            val dp12 = 12.dpToPx()
            val dp32 = 32.dpToPx()
            setPadding(dp32, dp12, dp32, dp12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = 8.dpToPx() }
            addView(TextView(context).apply {
                text = label
                setTextColor(android.graphics.Color.parseColor("#8E8E93"))
                textSize = 13f
            })
        }
    }

    private fun hasAdminRole(): Boolean {
        return sessionManager.hasRole(3) || sessionManager.isAdmin()
    }

    private fun hasOnlyRoleOne(): Boolean {
        return sessionManager.hasRole(1) &&
            !sessionManager.hasRole(2) &&
            !hasAdminRole()
    }

    private fun hasFullModerationAccess(): Boolean {
        return hasAdminRole() || sessionManager.hasRole(2)
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
            DashboardSection.CERTIFICATES -> loadCertificatesSection()
            DashboardSection.ACTIVATION -> loadActivationSection()
            DashboardSection.ENROLLMENT -> loadEnrollmentSection()
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

                        // Paralelizar las 3 llamadas iniciales independientes
                        coroutineScope {
                        val creatorMetricsDeferred = async { BackendApiService.getMyCreatorDashboardMetrics() }
                        val enrollmentAnalyticsDeferred = async {
                            BackendApiService.getCreatorEnrollmentAnalytics(weeklyDays = 7, monthlyMonths = 6).getOrNull()
                        }
                        val creatorCoursesDeferred = async {
                            BackendApiService.getCoursesByCreatorId(creatorUserId).getOrNull() ?: emptyList()
                        }

                        val creatorMetricsResult = creatorMetricsDeferred.await()
                        val creatorMetrics = if (creatorMetricsResult is ApiResult.Success) {
                            creatorMetricsResult.data
                        } else {
                            null
                        }
                        val enrollmentAnalytics = enrollmentAnalyticsDeferred.await()
                        val creatorCourses = creatorCoursesDeferred.await()

                        val totalSubmissions = creatorCourses
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
                        } // end coroutineScope
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
            
            com.example.tareamov.util.AppCache.invalidateRoles()
            cachedRoleCards = emptyList()
            loadUserManagementSection()
        }
    }

    private fun toggleUserActiveStatus(user: com.example.tareamov.data.entity.Usuario) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val newStatus = !user.isActive
                BackendApiService.updateMyProfile(mapOf("isActive" to newStatus))
            }
            
            com.example.tareamov.util.AppCache.invalidateAdmin()
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

    data class ReinforcementDetail(
        val username: String,
        val avatarUrl: String?,
        val courseName: String,
        val subjectName: String,
        val topicName: String,
        val taskName: String,
        val totalQuestions: Int,
        val correctAnswers: Int,
        val grade: Float,
        val difficulty: String,
        val createdAt: String
    )

    data class PendingTaskDetail(
        val task: com.example.tareamov.data.entity.Task,
        val course: com.example.tareamov.data.entity.Course?,
        val subjectName: String = "",
        val topicName: String = ""
    )

    private suspend fun getManageableCourses(userId: Long): List<com.example.tareamov.data.entity.Course> {
        if (userId <= 0L) return emptyList()
        return BackendApiService.getManageableCourses(userId).getOrNull().orEmpty()
    }
    
    private fun loadModerationSection() {
        val hasFullAccess = hasFullModerationAccess()
        titleTextView.text = "Moderación"
        
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
        
        if (!hasFullAccess) {
            // Ocultar secciones adicionales cuando el usuario no tiene acceso completo a moderación
            moderationView.findViewById<View>(R.id.headerSubmissions)?.apply {
                (parent as? View)?.visibility = View.GONE
            }
            moderationView.findViewById<View>(R.id.headerStudents)?.apply {
                (parent as? View)?.visibility = View.GONE
            }
            moderationView.findViewById<View>(R.id.headerReinforcement)?.apply {
                (parent as? View)?.visibility = View.GONE
            }
        }
        
        // Insertar headers colapsables antes de que lleguen los datos
        setupAllModerationCollapsibles(moderationView)

        // Cargar métricas, cursos por terminar y tareas pendientes (para todos)
        loadModerationMetrics(moderationView)
        loadCoursesToFinish(moderationView)
        loadPendingTasksToSubmit(moderationView)

        // Con acceso completo, cargar también submissions, progreso de estudiantes y refuerzo
        if (hasFullAccess) {
            loadPendingSubmissions(moderationView)
            loadStudentProgress(moderationView)
            loadReinforcementResults(moderationView)
            loadSubjectProgressSection(moderationView)
        }
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
                val pendingTasksDeferred = async(Dispatchers.IO) {
                    fetchPendingTasksForCurrentUser().size
                }
                
                val myProgress = progressDeferred.await()
                val allSubmissions = submissionsDeferred.await()
                val totalTareasHoy = pendingTasksDeferred.await()
                
                // Calculate metrics
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
                val container = parentView.findViewById<LinearLayout>(R.id.pendingSubmissionsContainer)
                val countBadge = parentView.findViewById<TextView>(R.id.pendingSubmissionsCount)

                fun renderSubmissions(details: List<SubmissionDetail>) {
                    container.removeAllViews()
                    countBadge.text = "${details.size} pendientes"
                    if (details.isEmpty()) {
                        container.addView(createEmptyStateView("No hay tareas pendientes por calificar", "✅"))
                    } else {
                        details.forEachIndexed { index, detail ->
                            val itemView = createSubmissionItemView(detail, container)
                            itemView.alpha = 0f
                            itemView.translationY = 28f
                            itemView.scaleX = 0.98f
                            itemView.scaleY = 0.98f
                            container.addView(itemView)
                            itemView.animate()
                                .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                                .setDuration(360).setStartDelay((index * 90).toLong())
                                .setInterpolator(FastOutSlowInInterpolator()).start()
                        }
                    }
                    parentView.findViewById<TextView>(R.id.viewAllSubmissionsLink)?.setOnClickListener {
                        navigateToTaskSubmissionFromModeration(details.firstOrNull() ?: return@setOnClickListener)
                    }
                }

                if (cachedPendingSubmissionDetails.isNotEmpty()) {
                    renderSubmissions(cachedPendingSubmissionDetails)
                } else {
                    showLoadingIndicator()
                }

                val userId = sessionManager.getUserId()
                val maxCards = TOP_ITEMS_LIMIT

                val submissionDetails = withContext(Dispatchers.IO) {
                    val creatorCourses = getManageableCourses(userId)
                    val courseTitleById = creatorCourses.associateBy({ it.id }, { it.title })

                    fun isSubmissionGraded(s: com.example.tareamov.data.entity.TaskSubmission) =
                        s.grade != null && s.grade.isFinite()

                    val ungradedByCourse = coroutineScope {
                        creatorCourses.map { course ->
                            async {
                                val all = BackendApiService
                                    .getSubmissionsByCourse(course.id, 1, MAX_SUBMISSIONS_PER_COURSE, ungradedOnly = false)
                                    .getOrNull().orEmpty()
                                val ungraded = all
                                    .groupBy { "${it.studentId}_${it.taskId}" }
                                    .mapNotNull { (_, group) ->
                                        if (group.any { isSubmissionGraded(it) }) null
                                        else group.maxByOrNull { it.submissionDate } ?: group.firstOrNull()
                                    }
                                    .filter { !isSubmissionGraded(it) }
                                course.id to ungraded
                            }
                        }.awaitAll()
                    }

                    val selectedPairs = ungradedByCourse.asSequence()
                        .flatMap { (courseId, submissions) ->
                            submissions.asSequence().filter { it.studentId != userId }.map { courseId to it }
                        }
                        .take(maxCards).toList()

                    val taskMap = coroutineScope {
                        selectedPairs.map { it.second.taskId }.distinct()
                            .map { taskId -> async { taskId to BackendApiService.getTaskById(taskId).getOrNull() } }
                            .awaitAll().toMap()
                    }
                    val studentMap = coroutineScope {
                        selectedPairs.map { it.second.studentId }.distinct()
                            .map { sid -> async { sid to BackendApiService.getUserById(sid).getOrNull() } }
                            .awaitAll().toMap()
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

                if (currentSection != DashboardSection.MODERATION) { hideLoadingIndicator(); return@launch }

                cachedPendingSubmissionDetails = submissionDetails
                hideLoadingIndicator()
                renderSubmissions(submissionDetails)

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
                        // Invalidate cache so other devices/fragments see the grading change
                        com.example.tareamov.util.AppCache.invalidateAdmin()
                        com.example.tareamov.util.AppCache.invalidateNotifications()
                        cachedPendingSubmissionDetails = emptyList()
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
    
    private fun loadStudentProgress(parentView: View) {
        lifecycleScope.launch {
            try {
                val userId = sessionManager.getUserId()
                val container = parentView.findViewById<LinearLayout>(R.id.studentProgressContainer)
                val countBadge = parentView.findViewById<TextView>(R.id.studentProgressCount)

                val courseFilterEt = android.widget.EditText(requireContext()).apply {
                    hint = "Filtrar por título de materia..."
                    setHintTextColor(Color.parseColor("#6B6B7A"))
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setBackgroundResource(R.drawable.message_input_background)
                    setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
                    maxLines = 1
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 8.dpToPx() }
                }

                val usernameFilterEt = android.widget.EditText(requireContext()).apply {
                    hint = "Buscar estudiante por usuario, nombre o cédula..."
                    setHintTextColor(Color.parseColor("#6B6B7A"))
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setBackgroundResource(R.drawable.message_input_background)
                    setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
                    maxLines = 1
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 12.dpToPx() }
                }

                val innerList = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                studentProgressListContainer = innerList

                container.removeAllViews()
                container.addView(courseFilterEt)
                container.addView(usernameFilterEt)
                container.addView(innerList)

                val filterWatcher = object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        renderStudentProgressFiltered(courseFilterEt.text.toString(), usernameFilterEt.text.toString())
                    }
                }
                courseFilterEt.addTextChangedListener(filterWatcher)
                usernameFilterEt.addTextChangedListener(filterWatcher)

                if (cachedCourseProgressData.isNotEmpty()) {
                    val totalCached = cachedCourseProgressData.sumOf { (_, progs) -> progs.count { it.usuarioEstudiante != userId } }
                    countBadge.text = "$totalCached estudiantes"
                    renderStudentProgressFiltered("", "")
                }

                val (courseProgress, users, subjectsByCourse) = withContext(Dispatchers.IO) {
                    val courses = getManageableCourses(userId)
                    val progress = coroutineScope {
                        courses.map { course ->
                            async { course to BackendApiService.getAllProgressByCourse(course.id).getOrNull().orEmpty() }
                        }.awaitAll()
                    }
                    val subjects = coroutineScope {
                        courses.map { course ->
                            async { course.id to BackendApiService.getSubjectsByCourse(course.id).getOrNull().orEmpty() }
                        }.awaitAll()
                    }.toMap()
                    val allStudentIds = progress.flatMap { (_, progs) -> progs.map { it.usuarioEstudiante } }.distinct()
                    val usersMap = if (allStudentIds.isNotEmpty())
                        BackendApiService.getUsersByIds(allStudentIds).getOrNull().orEmpty().associateBy { it.id }
                    else emptyMap()
                    Triple(progress, usersMap, subjects)
                }

                cachedCourseProgressData = courseProgress
                cachedStudentUsersData = users
                cachedSubjectsData = subjectsByCourse

                val totalStudents = courseProgress.sumOf { (_, progs) -> progs.count { it.usuarioEstudiante != userId } }
                countBadge.text = "$totalStudents estudiantes"
                renderStudentProgressFiltered(courseFilterEt.text.toString(), usernameFilterEt.text.toString())

            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading student progress", e)
            }
        }
    }

    private fun renderStudentProgressFiltered(courseQuery: String, usernameQuery: String) {
        val innerList = studentProgressListContainer ?: return
        innerList.removeAllViews()

        if (cachedCourseProgressData.isEmpty()) {
            innerList.addView(createEmptyStateView("Sin estudiantes inscritos aún", "📚"))
            return
        }

        val currentUserId = sessionManager.getUserId()

        var globalIndex = 0
        var hasResults = false

        cachedCourseProgressData.forEach { (course, progs) ->
            val progsWithoutCurrentUser = progs.filter { it.usuarioEstudiante != currentUserId }
            if (progsWithoutCurrentUser.isEmpty()) return@forEach

            val subjects = cachedSubjectsData[course.id].orEmpty()

            val subjectGroups = mutableMapOf<Long?, MutableList<com.example.tareamov.data.entity.ProgresoEstudiante>>()
            progsWithoutCurrentUser.forEach { prog ->
                subjectGroups.getOrPut(prog.materiaId) { mutableListOf() }.add(prog)
            }

            val filteredSubjectGroups = if (courseQuery.isBlank()) {
                subjectGroups
            } else {
                subjectGroups.filter { (subjectId, _) ->
                    val subject = subjects.find { it.id == subjectId }
                    val subjectName = subject?.name ?: "Sin materia"
                    subjectName.contains(courseQuery, ignoreCase = true)
                }
            }

            val finalGroups = filteredSubjectGroups.mapValues { (_, groupProgs) ->
                if (usernameQuery.isBlank()) groupProgs
                else groupProgs.filter { prog ->
                    val user = cachedStudentUsersData[prog.usuarioEstudiante]
                    matchesFlexibleUserQuery(
                        usernameQuery,
                        user?.usuario,
                        user?.personas?.nombres,
                        user?.personas?.apellidos,
                        user?.personas?.identificacion?.toString(),
                        user?.personas?.cedula?.toString(),
                        "#${prog.usuarioEstudiante}"
                    )
                }
            }.filterValues { it.isNotEmpty() }

            if (finalGroups.isEmpty()) return@forEach

            hasResults = true

            innerList.addView(TextView(requireContext()).apply {
                text = course.title
                setTextColor(Color.WHITE)
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(4, if (globalIndex == 0) 0 else 24.dpToPx(), 4, 4)
            })

            finalGroups.forEach { (subjectId, subjectProgs) ->
                val subject = subjects.find { it.id == subjectId }
                val subjectName = subject?.name ?: "Sin materia asignada"

                innerList.addView(TextView(requireContext()).apply {
                    text = "📘  $subjectName  ·  ${subjectProgs.size} estudiantes"
                    setTextColor(Color.parseColor("#64B5F6"))
                    textSize = 12f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(8.dpToPx(), 8.dpToPx(), 4, 6.dpToPx())
                })

                subjectProgs.forEach { prog ->
                    val user = cachedStudentUsersData[prog.usuarioEstudiante]
                    val username = user?.usuario ?: "#${prog.usuarioEstudiante}"
                    val itemView = createStudentProgressRow(username, user?.avatar, prog, innerList)
                    itemView.alpha = 0f
                    itemView.translationY = 32f
                    innerList.addView(itemView)
                    itemView.animate()
                        .alpha(1f).translationY(0f).setDuration(350)
                        .setStartDelay((globalIndex * 60).toLong())
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                    globalIndex++
                }
            }
        }

        if (!hasResults) {
            innerList.addView(createEmptyStateView(
                if (courseQuery.isBlank() && usernameQuery.isBlank())
                    "Sin estudiantes inscritos aún"
                else "Sin resultados para los filtros aplicados",
                "🔍"
            ))
        }
    }

    private fun createStudentProgressRow(
        username: String,
        avatarUrl: String?,
        prog: com.example.tareamov.data.entity.ProgresoEstudiante,
        container: LinearLayout
    ): View {
        val itemView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_student_progress, container, false)

        val progressPct = if (prog.tareasTotales > 0)
            ((prog.tareasCompletadas.toFloat() / prog.tareasTotales) * 100).toInt()
        else prog.porcentajeProgreso.toInt()

        val grade = prog.promedio ?: prog.calificacionPonderada

        // Avatar con Glide
        val avatarView = itemView.findViewById<ImageView>(R.id.studentAvatar)
        Glide.with(this)
            .load(avatarUrl)
            .placeholder(R.drawable.placeholder_avatar)
            .error(R.drawable.placeholder_avatar)
            .circleCrop()
            .into(avatarView)

        // Navegar a perfil al tocar el avatar
        avatarView.setOnClickListener { navigateToUserProfileByUsername(username) }

        itemView.findViewById<TextView>(R.id.studentUsername).text = username
        itemView.findViewById<TextView>(R.id.studentProgressPercent).text = "$progressPct%"
        itemView.findViewById<TextView>(R.id.studentTasksText).text =
            "${prog.tareasCompletadas}/${prog.tareasTotales} tareas completadas"
        itemView.findViewById<TextView>(R.id.studentGrade).text =
            grade?.let { String.format(Locale.getDefault(), "%.1f", it) } ?: "—"

        // Status badge con color semántico
        val statusBadge = itemView.findViewById<TextView>(R.id.studentStatusBadge)
        val estado = prog.estado ?: prog.calcularEstado()
        when {
            progressPct == 0 -> {
                statusBadge.text = "Sin iniciar"
                statusBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#FF9800"))
            }
            estado == "Ganado" -> {
                statusBadge.text = "Ganado"
                statusBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#4ADE80"))
            }
            progressPct >= 50 -> {
                statusBadge.text = "En progreso"
                statusBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#8B7FFF"))
            }
            else -> {
                statusBadge.text = "Iniciado"
                statusBadge.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#64B5F6"))
            }
        }

        // Progress bar animada
        val progressBar = itemView.findViewById<ProgressBar>(R.id.studentProgressBar)
        progressBar.progress = 0
        progressBar.postDelayed({
            android.animation.ObjectAnimator.ofInt(progressBar, "progress", 0, progressPct).apply {
                duration = 600
                interpolator = android.view.animation.DecelerateInterpolator()
                start()
            }
        }, 200)

        return itemView
    }


    private fun loadSubjectProgressSection(parentView: View) {
        lifecycleScope.launch {
            try {
                val container = parentView.findViewById<LinearLayout>(R.id.subjectProgressContainer) ?: return@launch
                val countBadge = parentView.findViewById<android.widget.TextView>(R.id.subjectProgressCount)
                val subjectFilterEt = parentView.findViewById<android.widget.EditText>(R.id.filterSpSubject)
                val userFilterEt = parentView.findViewById<android.widget.EditText>(R.id.filterSpUser)

                // Render from cache immediately if available
                if (cachedSubjectProgressData.isNotEmpty()) {
                    countBadge?.text = "${cachedSubjectProgressData.size}"
                    renderSubjectProgressFiltered(container, "", "")
                }

                val data = withContext(Dispatchers.IO) {
                    BackendApiService.getAdminSubjectProgress().getOrNull() ?: emptyList()
                }
                cachedSubjectProgressData = data
                countBadge?.text = "${data.size}"
                renderSubjectProgressFiltered(container, subjectFilterEt?.text?.toString() ?: "", userFilterEt?.text?.toString() ?: "")

                val watcher = object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) {
                        renderSubjectProgressFiltered(
                            container,
                            subjectFilterEt?.text?.toString() ?: "",
                            userFilterEt?.text?.toString() ?: ""
                        )
                    }
                }
                subjectFilterEt?.addTextChangedListener(watcher)
                userFilterEt?.addTextChangedListener(watcher)

            } catch (e: Exception) {
                android.util.Log.e("AdminDashboard", "Error loading subject progress", e)
            }
        }
    }

    private fun renderSubjectProgressFiltered(container: LinearLayout, subjectQuery: String, userQuery: String) {
        container.removeAllViews()

        val data = cachedSubjectProgressData
        if (data.isEmpty()) {
            container.addView(createEmptyStateView("Sin datos de progreso por materia", "📚"))
            return
        }

        val filtered = data.filter { item ->
            val subjectName = item.get("subjectName")?.asString ?: ""
            val username    = item.get("username")?.asString ?: ""
            val nombres     = item.get("nombres")?.asString ?: ""
            val apellidos   = item.get("apellidos")?.asString ?: ""
            val identificacion = item.get("identificacion")?.asString ?: ""

            val matchSubject = subjectQuery.isBlank() || subjectName.contains(subjectQuery, ignoreCase = true)
            val matchUser    = userQuery.isBlank() || matchesFlexibleUserQuery(userQuery, username, nombres, apellidos, identificacion)
            matchSubject && matchUser
        }

        if (filtered.isEmpty()) {
            container.addView(createEmptyStateView("Sin resultados para los filtros aplicados", "🔍"))
            return
        }

        filtered.forEachIndexed { index, item ->
            val username     = item.get("username")?.asString ?: "—"
            val nombres      = item.get("nombres")?.asString ?: ""
            val apellidos    = item.get("apellidos")?.asString ?: ""
            val identificacion = item.get("identificacion")?.asString ?: "—"
            val courseName   = item.get("courseName")?.asString ?: "—"
            val subjectName  = item.get("subjectName")?.asString ?: "—"
            val completed    = item.get("completedTasks")?.asInt ?: 0
            val total        = item.get("totalTasks")?.asInt ?: 0
            val pct          = item.get("progressPercentage")?.asDouble?.toInt() ?: if (total > 0) (completed * 100 / total) else 0
            val grade        = item.get("averageGrade")?.let { if (!it.isJsonNull) it.asDouble else null }
            val enrollmentStatus = item.get("enrollmentStatus")?.asString
                ?: item.get("enrollment_status")?.asString ?: "activo"
            val isInactive = enrollmentStatus == "inactivo"

            // Card container
            val card = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.bg_card_section_glass)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8.dpToPx() }
                layoutParams = lp
                setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
                alpha = if (isInactive) 0.55f else 1.0f
            }

            // Row 1: username + fullname + identificacion
            val row1 = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 6.dpToPx() }
            }

            val nameBlock = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameBlock.addView(android.widget.TextView(requireContext()).apply {
                text = username
                setTextColor(Color.WHITE)
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            if (nombres.isNotBlank() || apellidos.isNotBlank()) {
                nameBlock.addView(android.widget.TextView(requireContext()).apply {
                    text = "$nombres $apellidos".trim()
                    setTextColor(Color.parseColor("#9CA3AF"))
                    textSize = 12f
                })
            }
            row1.addView(nameBlock)

            row1.addView(android.widget.TextView(requireContext()).apply {
                text = identificacion
                setTextColor(Color.parseColor("#A5B4FC"))
                textSize = 11f
                setBackgroundResource(R.drawable.bg_tab_count_purple)
                setPadding(8.dpToPx(), 3.dpToPx(), 8.dpToPx(), 3.dpToPx())
            })
            card.addView(row1)

            // Row 2: course · subject · tasks
            val row2 = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 8.dpToPx() }
            }

            row2.addView(android.widget.TextView(requireContext()).apply {
                text = courseName
                setTextColor(Color.parseColor("#D1D5DB"))
                textSize = 12f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            row2.addView(android.widget.TextView(requireContext()).apply {
                text = subjectName
                setTextColor(if (isInactive) Color.parseColor("#FBBF24") else Color.parseColor("#C4B5FD"))
                textSize = 12f
                setBackgroundColor(if (isInactive) Color.parseColor("#1A1704") else Color.parseColor("#1F1B4E"))
                setPadding(8.dpToPx(), 3.dpToPx(), 8.dpToPx(), 3.dpToPx())
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                if (isInactive) paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.marginStart = 8.dpToPx() }
            })

            row2.addView(android.widget.TextView(requireContext()).apply {
                text = "$completed/$total"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.marginStart = 10.dpToPx() }
            })
            card.addView(row2)

            // Row 2.5: enrollment status badge with toggle
            val userId = item.get("userId")?.asLong ?: 0L
            val courseId = item.get("courseId")?.asLong ?: 0L
            val subjectId = item.get("subjectId")?.asLong ?: 0L
            val statusRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 6.dpToPx() }
            }

            val statusBadge = android.widget.TextView(requireContext()).apply {
                text = if (isInactive) "⏸ INACTIVA" else "✓ ACTIVA"
                setTextColor(if (isInactive) Color.parseColor("#FBBF24") else Color.parseColor("#4ADE80"))
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                val bgColor = if (isInactive) Color.parseColor("#1A1704") else Color.parseColor("#0A2010")
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    setColor(bgColor)
                    cornerRadius = 10.dpToPx().toFloat()
                    setStroke(1.dpToPx(), if (isInactive) Color.parseColor("#44FBBF24") else Color.parseColor("#444ADE80"))
                }
                background = gd
                setPadding(10.dpToPx(), 4.dpToPx(), 10.dpToPx(), 4.dpToPx())
            }
            statusRow.addView(statusBadge)

            val toggleBtn = android.widget.TextView(requireContext()).apply {
                text = if (isInactive) "Reactivar acceso" else "Inactivar acceso"
                setTextColor(if (isInactive) Color.parseColor("#4ADE80") else Color.parseColor("#FBBF24"))
                textSize = 11f
                val gd = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 10.dpToPx().toFloat()
                    setStroke(1.dpToPx(), if (isInactive) Color.parseColor("#334ADE80") else Color.parseColor("#33FBBF24"))
                }
                background = gd
                setPadding(10.dpToPx(), 4.dpToPx(), 10.dpToPx(), 4.dpToPx())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.marginStart = 8.dpToPx() }
                setOnClickListener {
                    val newStatus = if (isInactive) "activo" else "inactivo"
                    lifecycleScope.launch {
                        try {
                            isEnabled = false
                            text = "..."
                            val result = withContext(Dispatchers.IO) {
                                BackendApiService.setSubjectEnrollmentStatus(userId, courseId, subjectId, newStatus)
                            }
                            if (result.isSuccess) {
                                // Update the cached data and re-render
                                item.addProperty("enrollmentStatus", newStatus)
                                item.addProperty("enrollment_status", newStatus)
                                renderSubjectProgressFiltered(
                                    container,
                                    subjectQuery,
                                    userQuery
                                )
                                Toast.makeText(requireContext(),
                                    if (newStatus == "activo") "Materia reactivada" else "Materia inactivada",
                                    Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Error al cambiar estado", Toast.LENGTH_SHORT).show()
                                isEnabled = true
                                text = if (isInactive) "Reactivar acceso" else "Inactivar acceso"
                            }
                        } catch (e: Exception) {
                            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            isEnabled = true
                            text = if (isInactive) "Reactivar acceso" else "Inactivar acceso"
                        }
                    }
                }
            }
            statusRow.addView(toggleBtn)
            card.addView(statusRow)
            val row3 = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val progressBar = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = 0
                layoutParams = LinearLayout.LayoutParams(0, 10.dpToPx(), 1f)
                    .also { it.marginEnd = 8.dpToPx() }
                val barColor = when {
                    pct >= 70 -> Color.parseColor("#4ADE80")
                    pct >= 40 -> Color.parseColor("#8B7FFF")
                    else      -> Color.parseColor("#FB923C")
                }
                progressDrawable = android.graphics.drawable.LayerDrawable(
                    arrayOf(
                        android.graphics.drawable.ColorDrawable(Color.parseColor("#2D2D4E")),
                        android.graphics.drawable.GradientDrawable().also { gd ->
                            gd.setColor(barColor)
                            gd.cornerRadius = 4f
                        }
                    )
                ).also { ld ->
                    ld.setId(0, android.R.id.background)
                    ld.setId(1, android.R.id.progress)
                }
            }
            progressBar.postDelayed({
                android.animation.ObjectAnimator.ofInt(progressBar, "progress", 0, pct).apply {
                    duration = 500
                    interpolator = android.view.animation.DecelerateInterpolator()
                    start()
                }
            }, (index * 50 + 200).toLong())

            row3.addView(progressBar)

            row3.addView(android.widget.TextView(requireContext()).apply {
                text = "$pct%"
                setTextColor(Color.parseColor("#9CA3AF"))
                textSize = 12f
            })

            val gradeColor = when {
                grade == null -> Color.parseColor("#9CA3AF")
                grade >= 7.0  -> Color.parseColor("#4ADE80")
                grade >= 5.0  -> Color.parseColor("#FB923C")
                else          -> Color.parseColor("#F87171")
            }
            row3.addView(android.widget.TextView(requireContext()).apply {
                text = grade?.let { String.format(java.util.Locale.getDefault(), "%.1f", it) } ?: "—"
                setTextColor(gradeColor)
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(10.dpToPx(), 3.dpToPx(), 10.dpToPx(), 3.dpToPx())
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.marginStart = 8.dpToPx() }
            })
            card.addView(row3)

            card.alpha = 0f
            card.translationY = 24f
            container.addView(card)
            card.animate()
                .alpha(1f).translationY(0f).setDuration(320)
                .setStartDelay((index * 40).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun setupAllModerationCollapsibles(parentView: View) {
        listOf(
            Triple(R.id.headerSubmissions, R.id.chevronSubmissions, R.id.collapsibleSubmissions),
            Triple(R.id.headerStudents,    R.id.chevronStudents,    R.id.collapsibleStudents),
            Triple(R.id.headerCourses,     R.id.chevronCourses,     R.id.collapsibleCourses),
            Triple(R.id.headerReinforcement, R.id.chevronReinforcement, R.id.collapsibleReinforcement),
            Triple(R.id.headerPendingTasks, R.id.chevronPendingTasks, R.id.collapsiblePendingTasks),
            Triple(R.id.headerSubjectProgress, R.id.chevronSubjectProgress, R.id.collapsibleSubjectProgress),
        ).forEach { (headerId, chevronId, collapsibleId) ->
            val header      = parentView.findViewById<LinearLayout>(headerId)      ?: return@forEach
            val chevron     = parentView.findViewById<TextView>(chevronId)         ?: return@forEach
            val collapsible = parentView.findViewById<LinearLayout>(collapsibleId) ?: return@forEach
            var expanded = false
            header.setOnClickListener {
                expanded = !expanded
                chevron.animate()
                    .rotation(if (expanded) 0f else -90f)
                    .setDuration(250)
                    .setInterpolator(FastOutSlowInInterpolator())
                    .start()
                animateSectionHeight(collapsible, expanded)
            }
        }
    }

    /** Anima la altura del contenido de 0→medido (expand) o medido→0 (collapse). */
    private fun animateSectionHeight(content: View, expand: Boolean) {
        if (expand) {
            content.visibility = View.VISIBLE
            content.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(
                    (content.parent as? android.view.View)?.width ?: 0,
                    android.view.View.MeasureSpec.EXACTLY
                ),
                android.view.View.MeasureSpec.makeMeasureSpec(
                    0, android.view.View.MeasureSpec.UNSPECIFIED
                )
            )
            val targetH = content.measuredHeight
            content.layoutParams.height = 0
            content.requestLayout()
            android.animation.ValueAnimator.ofInt(0, targetH).apply {
                duration = 300
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener {
                    content.layoutParams.height = it.animatedValue as Int
                    content.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        content.layoutParams.height =
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        content.requestLayout()
                    }
                })
                start()
            }
        } else {
            val startH = content.measuredHeight
            android.animation.ValueAnimator.ofInt(startH, 0).apply {
                duration = 250
                interpolator = FastOutSlowInInterpolator()
                addUpdateListener {
                    content.layoutParams.height = it.animatedValue as Int
                    content.requestLayout()
                }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        content.visibility = View.GONE
                        content.layoutParams.height =
                            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        content.requestLayout()
                    }
                })
                start()
            }
        }
    }

    private fun navigateToUserProfileByUsername(username: String) {
        if (username.startsWith("#")) return
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.adminDashboardFragment) return
        navController.navigate(
            R.id.action_adminDashboardFragment_to_userProfileViewFragment,
            Bundle().apply { putString("username", username) }
        )
    }

    private fun loadCoursesToFinish(parentView: View) {
        lifecycleScope.launch {
            try {
                val currentUserId = sessionManager.getUserId()
                val container = parentView.findViewById<LinearLayout>(R.id.pendingCoursesContainer)
                val countBadge = parentView.findViewById<TextView>(R.id.pendingCoursesCount)

                val courseFilterEt = android.widget.EditText(requireContext()).apply {
                    hint = "Buscar curso por título..."
                    setHintTextColor(Color.parseColor("#6B6B7A"))
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setBackgroundResource(R.drawable.message_input_background)
                    setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
                    maxLines = 1
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.bottomMargin = 12.dpToPx() }
                }

                val innerList = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                coursesToFinishListContainer = innerList

                container.removeAllViews()
                container.addView(courseFilterEt)
                container.addView(innerList)

                courseFilterEt.addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) { renderCoursesToFinishFiltered(s?.toString() ?: "") }
                })

                if (cachedCoursesToFinish.isNotEmpty()) {
                    countBadge.text = "${cachedCoursesToFinish.size} cursos"
                    renderCoursesToFinishFiltered("")
                }

                val courseDetails = withContext(Dispatchers.IO) {
                    val myProgress = BackendApiService.getMyProgress().getOrNull() ?: emptyList()
                    val incompleteCourses = myProgress.filter {
                        val pct = if (it.tareasTotales > 0) (it.tareasCompletadas.toFloat() / it.tareasTotales.toFloat()) * 100f
                        else it.porcentajeProgreso
                        pct < 90f
                    }
                    coroutineScope {
                        incompleteCourses.mapNotNull { progress ->
                            val courseId = progress.cursoId
                            if (courseId <= 0L) null
                            else async {
                                val courseDeferred = async { BackendApiService.getCourseById(courseId).getOrNull() }
                                val enrolledCountDeferred = async { BackendApiService.getEnrolledCount(courseId).getOrNull() ?: 0 }
                                val course = courseDeferred.await()
                                val enrolledCount = enrolledCountDeferred.await()
                                course?.takeIf { it.creatorUserId != currentUserId }
                                    ?.let { CourseProgressInfo(it, progress, enrolledCount) }
                            }
                        }.awaitAll().filterNotNull()
                    }
                }

                if (currentSection != DashboardSection.MODERATION) return@launch

                cachedCoursesToFinish = courseDetails
                countBadge.text = "${courseDetails.size} cursos"
                renderCoursesToFinishFiltered(courseFilterEt.text.toString())

            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading courses to finish", e)
            }
        }
    }

    private fun renderCoursesToFinishFiltered(query: String) {
        val innerList = coursesToFinishListContainer ?: return
        innerList.removeAllViews()

        val filtered = if (query.isBlank()) cachedCoursesToFinish
        else cachedCoursesToFinish.filter { it.course.title.contains(query, ignoreCase = true) }

        if (filtered.isEmpty()) {
            innerList.addView(createEmptyStateView(
                if (query.isBlank()) "¡Todos tus cursos están al día!" else "Sin resultados para \"$query\"",
                if (query.isBlank()) "🎉" else "🔍"
            ))
            return
        }

        filtered.forEachIndexed { index, info ->
            val itemView = createCourseProgressItemView(info, innerList)
            itemView.alpha = 0f
            itemView.translationY = 40f
            innerList.addView(itemView)
            itemView.animate()
                .alpha(1f).translationY(0f).setDuration(400)
                .setStartDelay((index * 100 + 200).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun loadPendingTasksToSubmit(parentView: View) {
        lifecycleScope.launch {
            try {
                val container = parentView.findViewById<LinearLayout>(R.id.pendingTasksContainer) ?: return@launch
                val countBadge = parentView.findViewById<TextView>(R.id.pendingTasksCount)
                val filterCourse = parentView.findViewById<EditText>(R.id.filterPendingTaskCourse)
                val filterSubject = parentView.findViewById<EditText>(R.id.filterPendingTaskSubject)
                
                pendingTasksListContainer = container
                pendingTasksCountView = countBadge

                // Configurar filtros
                val textWatcher = object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: android.text.Editable?) {
                        pendingTaskCourseFilter = filterCourse?.text?.toString()?.trim() ?: ""
                        pendingTaskSubjectFilter = filterSubject?.text?.toString()?.trim() ?: ""
                        renderPendingTasksFiltered()
                    }
                }
                filterCourse?.addTextChangedListener(textWatcher)
                filterSubject?.addTextChangedListener(textWatcher)
                
                // Mostrar caché si existe
                if (cachedPendingTasks.isNotEmpty()) {
                    countBadge?.text = cachedPendingTasks.size.toString()
                    renderPendingTasksFiltered()
                }

                val pendingTasks = withContext(Dispatchers.IO) { fetchPendingTasksForCurrentUser() }

                if (currentSection != DashboardSection.MODERATION) return@launch
                
                cachedPendingTasks = pendingTasks
                countBadge?.text = pendingTasks.size.toString()
                renderPendingTasksFiltered()
                
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading pending tasks", e)
            }
        }
    }

    private suspend fun fetchPendingTasksForCurrentUser(): List<PendingTaskDetail> {
        // Fetch progress and submissions in parallel
        val (myProgress, mySubmissions) = coroutineScope {
            val progressDef = async { BackendApiService.getMyProgress().getOrNull() ?: emptyList() }
            val submissionsDef = async { BackendApiService.getMySubmissions(1, 500).getOrNull() ?: emptyList() }
            progressDef.await() to submissionsDef.await()
        }
        val enrolledCourseIds = myProgress.map { it.cursoId }.filter { it > 0 }.distinct()

        if (enrolledCourseIds.isEmpty()) return emptyList()

        val submittedTaskIds = mySubmissions.map { it.taskId }.filter { it > 0 }.toSet()

        return coroutineScope {
            enrolledCourseIds.map { courseId ->
                async {
                    // Fetch all course data in parallel
                    val courseDef = async { BackendApiService.getCourseById(courseId).getOrNull() }
                    val tasksDef = async { BackendApiService.getTasksByCourse(courseId).getOrNull() ?: emptyList() }
                    val subjectsDef = async { BackendApiService.getSubjectsByCourse(courseId).getOrNull() ?: emptyList() }
                    val topicsDef = async { BackendApiService.getTopicsByCourse(courseId).getOrNull() ?: emptyList() }

                    val course = courseDef.await()
                    val tasks = tasksDef.await()
                    val subjectMap = subjectsDef.await().associateBy { it.id }
                    val topicMap = topicsDef.await().associateBy { it.id }

                    tasks
                        .filter { task -> task.id > 0 && task.id !in submittedTaskIds }
                        .map { task ->
                            val topic = topicMap[task.topicId]
                            val subjectId = topic?.subjectId ?: 0L
                            val subject = subjectMap[subjectId]
                            PendingTaskDetail(
                                task = task,
                                course = course,
                                subjectName = subject?.name ?: "",
                                topicName = topic?.name ?: ""
                            )
                        }
                }
            }.awaitAll().flatten().distinctBy { it.task.id }
        }
    }

    private fun renderPendingTasksFiltered() {
        val container = pendingTasksListContainer ?: return
        container.removeAllViews()

        val filtered = cachedPendingTasks.filter { detail ->
            val matchesCourse = pendingTaskCourseFilter.isEmpty() ||
                (detail.course?.title ?: "").lowercase(Locale.getDefault())
                    .contains(pendingTaskCourseFilter.lowercase(Locale.getDefault()))
            val matchesSubject = pendingTaskSubjectFilter.isEmpty() ||
                detail.subjectName.lowercase(Locale.getDefault())
                    .contains(pendingTaskSubjectFilter.lowercase(Locale.getDefault()))
            matchesCourse && matchesSubject
        }

        pendingTasksCountView?.text = filtered.size.toString()

        if (filtered.isEmpty()) {
            container.addView(createEmptyStateView(
                if (cachedPendingTasks.isEmpty()) "¡No tienes tareas pendientes!" else "Sin resultados para este filtro",
                if (cachedPendingTasks.isEmpty()) "✅" else "🔍"
            ))
            return
        }

        filtered.forEachIndexed { index, detail ->
            val itemView = createPendingTaskItemView(detail, container)
            itemView.alpha = 0f
            itemView.translationY = 40f
            container.addView(itemView)
            itemView.animate()
                .alpha(1f).translationY(0f).setDuration(400)
                .setStartDelay((index * 80 + 150).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun createPendingTaskItemView(detail: PendingTaskDetail, container: LinearLayout): View {
        val itemView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_pending_task, container, false)

        val taskTitle = itemView.findViewById<TextView>(R.id.taskTitle)
        val courseName = itemView.findViewById<TextView>(R.id.courseName)
        val subjectNameView = itemView.findViewById<TextView>(R.id.subjectName)
        val subjectSeparator = itemView.findViewById<TextView>(R.id.subjectSeparator)
        val taskType = itemView.findViewById<TextView>(R.id.taskType)
        val taskIcon = itemView.findViewById<ImageView>(R.id.taskIcon)

        taskTitle?.text = detail.task.name.ifBlank { "Tarea sin nombre" }
        courseName?.text = detail.course?.title ?: "Curso"
        
        // Mostrar materia si está disponible
        if (detail.subjectName.isNotBlank()) {
            subjectSeparator?.visibility = View.VISIBLE
            subjectNameView?.visibility = View.VISIBLE
            subjectNameView?.text = detail.subjectName
        }
        
        taskType?.text = if (detail.subjectName.isNotBlank()) detail.subjectName else "Tarea"
        
        val iconRes = R.drawable.ic_assignment
        taskIcon?.setImageResource(iconRes)

        itemView.setOnClickListener {
            navigateToTask(detail)
        }
        
        applyPendingItemPressAnimation(itemView)

        return itemView
    }

    private fun navigateToTask(detail: PendingTaskDetail) {
        val navController = findNavController()
        if (navController.currentDestination?.id != R.id.adminDashboardFragment) return

        val bundle = Bundle().apply {
            putLong("taskId", detail.task.id)
            putLong("courseId", detail.course?.id ?: 0L)
            putString("taskTitle", detail.task.name)
            putString("taskType", "")
        }

        try {
            navController.navigate(R.id.action_adminDashboardFragment_to_taskSubmissionFragment, bundle)
        } catch (e: Exception) {
            Log.e("AdminDashboard", "Error navigating to task submission", e)
            Toast.makeText(requireContext(), "No se pudo abrir la tarea", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadReinforcementResults(parentView: View) {
        lifecycleScope.launch {
            try {
                val container = parentView.findViewById<LinearLayout>(R.id.reinforcementResultsContainer) ?: return@launch
                val countBadge = parentView.findViewById<TextView>(R.id.reinforcementResultsCount)
                val filterCourse = parentView.findViewById<EditText>(R.id.filterReinforcementCourse)
                val filterSubject = parentView.findViewById<EditText>(R.id.filterReinforcementSubject)

                reinforcementListContainer = container

                fun renderFiltered() {
                    val cq = filterCourse?.text?.toString().orEmpty()
                    val sq = filterSubject?.text?.toString().orEmpty()
                    renderReinforcementFiltered(cq, sq)
                }

                val watcher = object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: android.text.Editable?) { renderFiltered() }
                }
                filterCourse?.addTextChangedListener(watcher)
                filterSubject?.addTextChangedListener(watcher)

                if (cachedReinforcementResults.isNotEmpty()) {
                    countBadge?.text = "${cachedReinforcementResults.size}"
                    renderFiltered()
                }

                val userId = sessionManager.getUserId()
                val details = withContext(Dispatchers.IO) {
                    if (hasAdminRole()) {
                        // Rol 3/Admin: obtener todos los resultados globales.
                        val results = BackendApiService.getAllReinforcementResults().getOrNull() ?: emptyList()
                        results.mapNotNull { json -> parseReinforcementDetail(json, null) }
                    } else {
                        // Profesor/Moderador: obtener por cursos gestionados
                        val creatorCourses = getManageableCourses(userId)
                        coroutineScope {
                            creatorCourses.map { course ->
                                async {
                                    val results = BackendApiService.getReinforcementResultsByCourse(course.id).getOrNull() ?: emptyList()
                                    results.mapNotNull { json -> parseReinforcementDetail(json, course.title) }
                                }
                            }.awaitAll().flatten()
                        }
                    }
                }

                if (currentSection != DashboardSection.MODERATION) return@launch

                val sortedDetails = details.sortedByDescending { parseReinforcementTimestamp(it.createdAt) }
                cachedReinforcementResults = sortedDetails
                countBadge?.text = "${sortedDetails.size}"
                renderFiltered()
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading reinforcement results", e)
            }
        }
    }

    private fun parseReinforcementDetail(json: JsonObject, fallbackCourseName: String?): ReinforcementDetail? {
        return try {
            val user = safeJsonObject(json, "usuarios")
            val topics = safeJsonObject(json, "topics")
            val tasks = safeJsonObject(json, "tasks")
            val subjects = safeJsonObject(topics, "subjects")
            val courseObj = safeJsonObject(json, "courses")

            ReinforcementDetail(
                username = safeJsonString(user, "username")
                    ?: safeJsonString(json, "username")
                    ?: safeJsonString(json, "usuario")
                    ?: "Estudiante",
                avatarUrl = safeJsonString(user, "avatar")
                    ?: safeJsonString(json, "avatar"),
                courseName = safeJsonString(courseObj, "title")
                    ?: safeJsonString(json, "course_name")
                    ?: fallbackCourseName.orEmpty(),
                subjectName = safeJsonString(subjects, "name")
                    ?: safeJsonString(json, "subject_name")
                    ?: "",
                topicName = safeJsonString(topics, "name")
                    ?: safeJsonString(json, "topic_name")
                    ?: "",
                taskName = safeJsonString(tasks, "title")
                    ?: safeJsonString(json, "task_name")
                    ?: "",
                totalQuestions = json.get("total_questions")?.let { if (it.isJsonNull) 0 else it.asInt }
                    ?: json.get("totalQuestions")?.let { if (it.isJsonNull) 0 else it.asInt }
                    ?: 0,
                correctAnswers = json.get("correct_answers")?.let { if (it.isJsonNull) 0 else it.asInt }
                    ?: json.get("correctAnswers")?.let { if (it.isJsonNull) 0 else it.asInt }
                    ?: 0,
                grade = json.get("grade")?.let { if (it.isJsonNull) 0f else it.asFloat } ?: 0f,
                difficulty = (safeJsonString(json, "difficulty") ?: "HARD").uppercase(Locale.US),
                createdAt = safeJsonString(json, "created_at")
                    ?: safeJsonString(json, "createdAt")
                    ?: ""
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun safeJsonObject(obj: JsonObject?, key: String): JsonObject? {
        val value = obj?.get(key) ?: return null
        if (!value.isJsonObject) return null
        return value.asJsonObject
    }

    private fun safeJsonString(obj: JsonObject?, key: String): String? {
        val value = obj?.get(key) ?: return null
        if (value.isJsonNull) return null
        return value.asString
    }

    private fun parseReinforcementTimestamp(value: String): Long {
        if (value.isBlank()) return 0L
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun renderReinforcementFiltered(courseQuery: String, subjectQuery: String) {
        val container = reinforcementListContainer ?: return
        container.removeAllViews()

        val filtered = cachedReinforcementResults.filter { detail ->
            (courseQuery.isBlank() || detail.courseName.contains(courseQuery, ignoreCase = true)) &&
            (subjectQuery.isBlank() || detail.subjectName.contains(subjectQuery, ignoreCase = true))
        }

        if (filtered.isEmpty()) {
            container.addView(createEmptyStateView(
                if (cachedReinforcementResults.isEmpty()) "Sin resultados de refuerzo aún"
                else "Sin coincidencias",
                if (cachedReinforcementResults.isEmpty()) "📝" else "🔍"
            ))
            return
        }

        filtered.forEachIndexed { index, detail ->
            val itemView = createReinforcementItemView(detail, container)
            itemView.alpha = 0f
            itemView.translationY = 28f
            itemView.scaleX = 0.985f
            itemView.scaleY = 0.985f
            container.addView(itemView)
            itemView.animate()
                .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f).setDuration(360)
                .setStartDelay((index * 80 + 100).toLong())
                .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun createReinforcementItemView(detail: ReinforcementDetail, container: LinearLayout): View {
        val itemView = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_reinforcement_result, container, false)

        val avatarView = itemView.findViewById<ImageView>(R.id.studentAvatar)
        Glide.with(this)
            .load(detail.avatarUrl)
            .placeholder(R.drawable.placeholder_avatar)
            .error(R.drawable.placeholder_avatar)
            .circleCrop()
            .into(avatarView)
        avatarView.setOnClickListener { navigateToUserProfileByUsername(detail.username) }

        itemView.findViewById<TextView>(R.id.studentUsername).text = detail.username
        itemView.findViewById<TextView>(R.id.courseName).text = detail.courseName

        val subjectView = itemView.findViewById<TextView>(R.id.subjectName)
        if (detail.subjectName.isNotBlank()) {
            subjectView.text = detail.subjectName
        } else {
            subjectView.visibility = View.GONE
        }

        val topicView = itemView.findViewById<TextView>(R.id.topicName)
        topicView.text = detail.topicName.ifBlank { "—" }

        val taskView = itemView.findViewById<TextView>(R.id.taskName)
        taskView.text = detail.taskName.ifBlank { "—" }

        val safeTotal = if (detail.totalQuestions > 0) detail.totalQuestions else 1
        val scorePercent = ((detail.correctAnswers.toFloat() / safeTotal.toFloat()) * 100f).toInt().coerceIn(0, 100)
        itemView.findViewById<TextView>(R.id.scoreText).text =
            "${detail.correctAnswers}/${detail.totalQuestions} correctas · ${scorePercent}%"

        val timeDiff = try {
            val instant = java.time.Instant.parse(detail.createdAt)
            System.currentTimeMillis() - instant.toEpochMilli()
        } catch (_: Exception) { 0L }
        itemView.findViewById<TextView>(R.id.dateText).text = when {
            timeDiff <= 0 -> detail.createdAt.take(10)
            timeDiff < 3600000 -> "hace ${timeDiff / 60000}m"
            timeDiff < 86400000 -> "hace ${timeDiff / 3600000}h"
            timeDiff < 604800000 -> "hace ${timeDiff / 86400000}d"
            else -> detail.createdAt.take(10)
        }

        val diffBadge = itemView.findViewById<TextView>(R.id.difficultyBadge)
        when (detail.difficulty.uppercase(Locale.US)) {
            "EASY" -> {
                diffBadge.text = "Fácil"
                diffBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4ADE80"))
            }
            "MEDIUM" -> {
                diffBadge.text = "Medio"
                diffBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F59E0B"))
            }
            else -> {
                diffBadge.text = "Difícil"
                diffBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#F44336"))
            }
        }

        val gradeView = itemView.findViewById<TextView>(R.id.gradeText)
        gradeView.text = String.format(Locale.US, "%.1f", detail.grade)
        gradeView.setTextColor(
            if (detail.grade >= 6f) Color.parseColor("#4ADE80") else Color.parseColor("#F44336")
        )

        return itemView
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
        if (!course.lastModifiedDate.isNullOrEmpty()) {
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

    // ==================== SECCIÓN 6: MIS CERTIFICADOS ====================

    private fun loadCertificatesSection() {
        if (!hasAdminRole()) return

        titleTextView.text = "Gestión de Certificados"

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val countLabel = TextView(requireContext()).apply {
            text = if (cachedCertificates.isNotEmpty()) "${cachedCertificates.size} candidato${if (cachedCertificates.size != 1) "s" else ""}" else "Cargando candidatos..."
            setTextColor(android.graphics.Color.parseColor("#6B6B7A"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 12.dpToPx() }
        }

        val bulkButton = TextView(requireContext()).apply {
            text = "🎓 Generar certificados para todos"
            setTextColor(android.graphics.Color.parseColor("#1A1A1A"))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            setBackgroundResource(R.drawable.certificate_button_background)
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 16.dpToPx() }
        }

        val searchBar = android.widget.EditText(requireContext()).apply {
            hint = "Buscar por curso o estudiante..."
            setHintTextColor(android.graphics.Color.parseColor("#6B6B7A"))
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setBackgroundResource(R.drawable.message_input_background)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 16.dpToPx() }
        }

        val issueAllBtn = Button(requireContext()).apply {
            text = "Emitir pendientes"
            setTextColor(android.graphics.Color.parseColor("#FF9500"))
            textSize = 12f
            isAllCaps = false
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().also {
                it.cornerRadius = 12.dpToPx().toFloat()
                it.setColor(android.graphics.Color.parseColor("#FF950020"))
                it.setStroke(1.dpToPx(), android.graphics.Color.parseColor("#FF950040"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 16.dpToPx() }
            setPadding(16.dpToPx(), 10.dpToPx(), 16.dpToPx(), 10.dpToPx())
        }

        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        var isBulkIssuing = false

        val updateSummary: (List<BackendApiService.CertificateItem>) -> Unit = { certs ->
            val pendingCount = certs.count { it.status.equals("Ganado", ignoreCase = true) && it.certificateIssuedAt.isNullOrBlank() }
            countLabel.text = "${certs.size} candidato${if (certs.size != 1) "s" else ""} · $pendingCount pendiente${if (pendingCount != 1) "s" else ""}"
            issueAllBtn.text = if (isBulkIssuing) "Emitiendo..." else if (pendingCount > 0) "Emitir pendientes ($pendingCount)" else "Sin pendientes"
            issueAllBtn.isEnabled = !isBulkIssuing && pendingCount > 0
        }

        val renderList: (String) -> Unit = { query ->
            listContainer.removeAllViews()
            val filtered = if (query.isBlank()) cachedCertificates
                else cachedCertificates.filter {
                    it.courseName.contains(query, ignoreCase = true) ||
                        (it.username?.contains(query, ignoreCase = true) == true)
                }

            if (filtered.isEmpty()) {
                listContainer.addView(createEmptyStateView(
                    if (query.isBlank()) "No hay estudiantes con progreso Ganado" else "Sin resultados para \"$query\"",
                    "🎓"
                ))
            } else {
                filtered.forEachIndexed { index, cert ->
                    val card = createCertificateCardView(cert)
                    card.alpha = 0f
                    card.translationY = 20f
                    listContainer.addView(card)
                    card.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(280)
                        .setStartDelay((index * 60).toLong())
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
        }
        root.addView(countLabel)
        root.addView(searchBar)
        root.addView(issueAllBtn)
        root.addView(listContainer)
        sectionsContainer.addView(root)

        searchBar.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) { renderList(s?.toString() ?: "") }
        })

        val stale = com.example.tareamov.util.AppCache.getCertificatesOrStale()
            ?.filter { it.status?.equals("Ganado", ignoreCase = true) == true }
        if (stale != null) {
            cachedCertificates = stale
            updateSummary(stale)
            renderList("")
        }

        issueAllBtn.setOnClickListener {
            if (isBulkIssuing) return@setOnClickListener
            isBulkIssuing = true
            updateSummary(cachedCertificates)
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        BackendApiService.bulkIssueCertificatesAll()
                    }
                    com.example.tareamov.util.AppCache.invalidateCertificates()
                    if (currentSection == DashboardSection.CERTIFICATES) {
                        switchSection(DashboardSection.CERTIFICATES)
                    }
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error issuing all certificates", e)
                    isBulkIssuing = false
                    updateSummary(cachedCertificates)
                }
            }
        }

        lifecycleScope.launch {
            try {
                val certs = withContext(Dispatchers.IO) {
                    BackendApiService.getAllCertificates().getOrNull() ?: emptyList()
                }.filter { it.status?.equals("Ganado", ignoreCase = true) == true }

                if (currentSection != DashboardSection.CERTIFICATES) return@launch

                com.example.tareamov.util.AppCache.putCertificates(certs)
                cachedCertificates = certs
                isBulkIssuing = false
                updateSummary(certs)
                renderList(searchBar.text.toString())
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading certificates", e)
                isBulkIssuing = false
                if (cachedCertificates.isEmpty()) countLabel.text = "Error al cargar certificados"
                updateSummary(cachedCertificates)
            }
        }
    }

    private fun resolveCertificateUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }

        val normalizedKey = trimmed
            .replace(Regex("^/+api/+v1/+public/+files/+?", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^/+public/+files/+?", RegexOption.IGNORE_CASE), "")
            .trimStart('/')

        return if (normalizedKey.isBlank()) null else BackendApiService.buildProxyFileUrl(normalizedKey)
    }

    private fun buildFallbackCertificateUrl(cert: BackendApiService.CertificateItem): String? {
        if (cert.certificateIssuedAt.isNullOrBlank()) return null

        val dateIso = cert.certificateIssuedAt.take(10)
        val parts = dateIso.split("-")
        val formattedDate = if (parts.size == 3) {
            "${parts[2]}/${parts[1]}/${parts[0]}"
        } else {
            java.text.SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES")).format(java.util.Date())
        }

        val query = android.net.Uri.Builder()
            .appendQueryParameter("studentName", cert.username?.takeIf { it.isNotBlank() } ?: "Usuario ${cert.userId}")
            .appendQueryParameter("courseName", cert.courseName.ifBlank { "Curso ${cert.courseId}" })
            .appendQueryParameter("grade", String.format(Locale.US, "%.1f", cert.averageGrade ?: 0f))
            .appendQueryParameter("tasksCompleted", cert.completedTasks.toString())
            .appendQueryParameter("totalTasks", cert.totalTasks.toString())
            .appendQueryParameter("progress", (cert.progressPercentage ?: 0f).toInt().toString())
            .appendQueryParameter("instructorName", "CourseV")
            .appendQueryParameter("instructorUsername", "coursev")
            .appendQueryParameter("userId", cert.userId.toString())
            .appendQueryParameter("courseId", cert.courseId.toString())
            .appendQueryParameter("certId", cert.verificationCode?.takeIf { it.isNotBlank() } ?: "CERT-${cert.courseId}-${cert.userId}")
            .appendQueryParameter("date", formattedDate)
            .build()
            .encodedQuery

        return if (query.isNullOrBlank()) null else "https://v0-eo-jesuh02s-projects.vercel.app?$query"
    }

    private fun createCertificateCardView(cert: BackendApiService.CertificateItem): View {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 12.dpToPx() }
            setBackgroundResource(R.drawable.profile_card_background)
            setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 14.dpToPx())
        }

        val avatarView = ImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx()).also {
                it.marginEnd = 12.dpToPx()
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = android.graphics.drawable.GradientDrawable().also {
                it.cornerRadius = 24.dpToPx().toFloat()
                it.setColor(android.graphics.Color.parseColor("#2D1F5E"))
            }
        }

        Glide.with(this)
            .load(cert.avatar.takeIf { !it.isNullOrBlank() } ?: R.drawable.placeholder_image)
            .placeholder(R.drawable.placeholder_image)
            .error(R.drawable.placeholder_image)
            .transform(CenterCrop(), RoundedCorners(24.dpToPx()))
            .into(avatarView)

        val studentName = cert.username ?: "Estudiante"
        avatarView.setOnClickListener { navigateToUserProfileByUsername(studentName) }

        val info = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        info.addView(TextView(requireContext()).apply {
            text = studentName
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 2.dpToPx() }
        })

        info.addView(TextView(requireContext()).apply {
            text = cert.courseName
            setTextColor(android.graphics.Color.parseColor("#B8B3FF"))
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 4.dpToPx() }
        })

        cert.username?.takeIf { it.isNotBlank() }?.let { username ->
            info.addView(TextView(requireContext()).apply {
                text = "Estudiante: $username"
                setTextColor(android.graphics.Color.parseColor("#8E8E93"))
                textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 6.dpToPx() }
            })
        }

        val metaRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 6.dpToPx() }
        }

        cert.averageGrade?.let { grade ->
            metaRow.addView(TextView(requireContext()).apply {
                text = "★ ${String.format(Locale.getDefault(), "%.1f", grade)}"
                setTextColor(if (grade >= 6f) android.graphics.Color.parseColor("#4ADE80") else android.graphics.Color.parseColor("#FF6B6B"))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(7.dpToPx(), 3.dpToPx(), 7.dpToPx(), 3.dpToPx())
                background = android.graphics.drawable.GradientDrawable().also {
                    it.setColor(android.graphics.Color.parseColor("#2D1F5E"))
                    it.cornerRadius = 20f
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 8.dpToPx() }
            })
        }

        cert.certificateIssuedAt?.let { issuedAt ->
            metaRow.addView(TextView(requireContext()).apply {
                text = issuedAt.take(10)
                setTextColor(android.graphics.Color.parseColor("#6B6B7A"))
                textSize = 11f
            })
        } ?: metaRow.addView(TextView(requireContext()).apply {
            text = "Pendiente de emisión"
            setTextColor(android.graphics.Color.parseColor("#FF9500"))
            textSize = 11f
        })

        if (cert.totalTasks > 0) {
            metaRow.addView(TextView(requireContext()).apply {
                text = "  •  ${cert.completedTasks}/${cert.totalTasks}"
                setTextColor(android.graphics.Color.parseColor("#6B6B7A"))
                textSize = 11f
            })
        }

        info.addView(metaRow)

        cert.verificationCode?.let { code ->
            info.addView(TextView(requireContext()).apply {
                text = "Código: $code"
                setTextColor(android.graphics.Color.parseColor("#6C63FF"))
                textSize = 10f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.bottomMargin = 6.dpToPx() }
            })
        }

        val actionsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val resolvedCertificateUrl = resolveCertificateUrl(cert.certificateUrl) ?: buildFallbackCertificateUrl(cert)

        if (!resolvedCertificateUrl.isNullOrBlank()) {
            actionsRow.addView(TextView(requireContext()).apply {
                text = "Ver certificado →"
                setTextColor(android.graphics.Color.parseColor("#1A1A1A"))
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(10.dpToPx(), 5.dpToPx(), 10.dpToPx(), 5.dpToPx())
                setBackgroundResource(R.drawable.certificate_button_background)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 8.dpToPx() }
                setOnClickListener {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(resolvedCertificateUrl)
                    )
                    startActivity(intent)
                }
            })
        }

        val needsIssuance = cert.certificateIssuedAt.isNullOrBlank()
        if (needsIssuance && cert.status?.equals("Ganado", ignoreCase = true) == true && cert.userId > 0L) {
            actionsRow.addView(TextView(requireContext()).apply {
                text = "Emitir certificado"
                setTextColor(android.graphics.Color.parseColor("#FF9500"))
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(10.dpToPx(), 5.dpToPx(), 10.dpToPx(), 5.dpToPx())
                background = android.graphics.drawable.GradientDrawable().also {
                    it.cornerRadius = 16.dpToPx().toFloat()
                    it.setColor(android.graphics.Color.parseColor("#FF950020"))
                    it.setStroke(1.dpToPx(), android.graphics.Color.parseColor("#FF950040"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    isEnabled = false
                    lifecycleScope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) {
                                BackendApiService.issueCertificate(cert.courseId, cert.userId)
                            }
                            if (result is ApiResult.Success) {
                                com.example.tareamov.util.AppCache.invalidateCertificates()
                                if (currentSection == DashboardSection.CERTIFICATES) {
                                    switchSection(DashboardSection.CERTIFICATES)
                                }
                            } else {
                                isEnabled = true
                            }
                        } catch (e: Exception) {
                            Log.e("AdminDashboard", "Error issuing certificate", e)
                            isEnabled = true
                        }
                    }
                }
            })
        }

        if (actionsRow.childCount > 0) {
            info.addView(actionsRow)
        }

        card.addView(avatarView)
        card.addView(info)
        return card
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

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

        fun renderRoleCards(cards: List<RoleCardData>) {
            val ctx = context ?: return
            rolesContainer.removeAllViews()
            if (cards.isEmpty()) { showEmptyRolesState(rolesContainer); return }
            cards.forEachIndexed { index, roleCard ->
                val roleView = LayoutInflater.from(ctx)
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
        }

        if (cachedRoleCards.isNotEmpty()) renderRoleCards(cachedRoleCards)
        else rolesContainer.removeAllViews()

        lifecycleScope.launch {
            try {
                val currentUserId = sessionManager.getUserId()
                if (currentUserId <= 0L) { showEmptyRolesState(rolesContainer); return@launch }

                val roleCards = withContext(Dispatchers.IO) {
                    val allRoles = com.example.tareamov.util.AppCache.getRoles()
                        ?: BackendApiService.getRoles().getOrNull()?.also { com.example.tareamov.util.AppCache.putRoles(it) }
                        ?: emptyList()
                    val userRoleIds = BackendApiService.getUserRoles(currentUserId).getOrNull()?.toSet() ?: emptySet()

                    val assignedRoles = if (userRoleIds.isNotEmpty()) allRoles.filter { it.id in userRoleIds }
                        else allRoles.filter { sessionManager.hasRole(it.id.toInt()) }

                    coroutineScope {
                        assignedRoles.map { role ->
                            async {
                                val permissionCount = BackendApiService.getRecursosByRol(role.id).getOrNull()?.size ?: 0
                                RoleCardData(role, permissionCount)
                            }
                        }.map { it.await() }.sortedByDescending { it.role.nivel }
                    }
                }

                if (currentSection != DashboardSection.PERMISSIONS) return@launch

                cachedRoleCards = roleCards
                renderRoleCards(roleCards)
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error cargando roles del usuario", e)
                if (cachedRoleCards.isEmpty()) showEmptyRolesState(rolesContainer)
            }
        }
    }

    private fun showEmptyRolesState(container: LinearLayout) {
        val ctx = context ?: return
        container.removeAllViews()
        val emptyState = TextView(ctx).apply {
            text = "No se encontraron roles activos para esta cuenta"
            setTextColor(ContextCompat.getColor(ctx, R.color.light_purple))
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

    private fun normalizeSearchText(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
        return normalized.replace("\\p{M}+".toRegex(), "").lowercase(Locale.getDefault())
    }

    private fun normalizeSearchDigits(value: String?): String =
        value.orEmpty().filter { it.isDigit() }

    private fun matchesFlexibleUserQuery(query: String, vararg candidates: String?): Boolean {
        if (query.isBlank()) return true

        val normalizedQuery = normalizeSearchText(query)
        val digitsQuery = normalizeSearchDigits(query)

        return candidates.any { candidate ->
            val normalizedCandidate = normalizeSearchText(candidate)
            normalizedCandidate.contains(normalizedQuery) ||
                (digitsQuery.isNotEmpty() && normalizeSearchDigits(candidate).contains(digitsQuery))
        }
    }

    // ==================== SECCIÓN 7: ACTIVACIÓN ====================

    private var activationSearchQuery = ""
    private var allUsersCache: List<com.example.tareamov.data.entity.Usuario> = emptyList()

    private fun loadActivationSection() {
        titleTextView.text = "Activación"

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // ── Header descriptivo ──
        val headerCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 20.dpToPx().toFloat()
                setColor(Color.parseColor("#1C1C1E"))
            }
            background = bg
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 20.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 16.dpToPx() }
        }

        val headerTitle = TextView(requireContext()).apply {
            text = "Gestión de Acceso"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        val headerSubtitle = TextView(requireContext()).apply {
            text = "Activa o desactiva el acceso de usuarios a la plataforma"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 4.dpToPx() }
        }

        // ── Contadores de estadísticas (activos/inactivos) ──
        val statsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 16.dpToPx() }
        }

        val activeCountView = createActivationStatPill("0", "Activos", "#30D158")
        val inactiveCountView = createActivationStatPill("0", "Inactivos", "#FF453A")
        val totalCountView = createActivationStatPill("0", "Total", "#8B7FFF")

        statsRow.addView(activeCountView.first)
        statsRow.addView(inactiveCountView.first)
        statsRow.addView(totalCountView.first)

        headerCard.addView(headerTitle)
        headerCard.addView(headerSubtitle)
        headerCard.addView(statsRow)
        root.addView(headerCard)

        // ── Barra de búsqueda ──
        val searchRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 14.dpToPx().toFloat()
                setColor(Color.parseColor("#1C1C1E"))
            }
            background = bg
            setPadding(14.dpToPx(), 0, 8.dpToPx(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                48.dpToPx()
            ).also { it.bottomMargin = 12.dpToPx() }
        }

        val searchIcon = TextView(requireContext()).apply {
            text = "🔍"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = 10.dpToPx() }
        }

        val searchEditText = android.widget.EditText(requireContext()).apply {
            hint = "Buscar por usuario, nombre o cédula..."
            setHintTextColor(Color.parseColor("#636366"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = null
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        searchRow.addView(searchIcon)
        searchRow.addView(searchEditText)
        root.addView(searchRow)

        // ── Botón procesar expirados (discreto) ──
        val processExpiredBtn = TextView(requireContext()).apply {
            text = "⏱  Procesar cursos expirados"
            setTextColor(Color.parseColor("#FF9500"))
            textSize = 13f
            gravity = android.view.Gravity.CENTER
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12.dpToPx().toFloat()
                setColor(Color.parseColor("#FF950015"))
            }
            background = bg
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 16.dpToPx() }
        }

        root.addView(processExpiredBtn)

        // ── Loading y lista ──
        val loadingText = TextView(requireContext()).apply {
            text = "Cargando usuarios..."
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 24.dpToPx() }
        }

        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Búsqueda en tiempo real
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                activationSearchQuery = s?.toString()?.trim() ?: ""
                renderFilteredActivationUsers(listContainer, activeCountView.second, inactiveCountView.second, totalCountView.second)
            }
        })

        processExpiredBtn.setOnClickListener {
            processExpiredBtn.isEnabled = false
            processExpiredBtn.text = "⏱  Procesando..."
            processExpiredBtn.alpha = 0.5f
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        BackendApiService.processExpiredCourseUsers(emptyList())
                    }
                    loadUsersIntoContainer(listContainer, loadingText, activeCountView.second, inactiveCountView.second, totalCountView.second)
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error processing expired courses", e)
                } finally {
                    processExpiredBtn.isEnabled = true
                    processExpiredBtn.text = "⏱  Procesar cursos expirados"
                    processExpiredBtn.alpha = 1f
                }
            }
        }

        root.addView(loadingText)
        root.addView(listContainer)
        sectionsContainer.addView(root)

        loadUsersIntoContainer(listContainer, loadingText, activeCountView.second, inactiveCountView.second, totalCountView.second)
    }

    private fun createActivationStatPill(
        value: String,
        label: String,
        color: String
    ): Pair<LinearLayout, TextView> {
        val valueView = TextView(requireContext())
        val pill = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12.dpToPx().toFloat()
                setColor(Color.parseColor(color + "15"))
            }
            background = bg
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginEnd = 8.dpToPx()
            }
        }

        valueView.apply {
            text = value
            setTextColor(Color.parseColor(color))
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
        }

        val labelView = TextView(requireContext()).apply {
            text = label
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 2.dpToPx() }
        }

        pill.addView(valueView)
        pill.addView(labelView)
        return Pair(pill, valueView)
    }

    private fun renderFilteredActivationUsers(
        listContainer: LinearLayout,
        activeCountText: TextView,
        inactiveCountText: TextView,
        totalCountText: TextView
    ) {
        val query = activationSearchQuery.lowercase(Locale.getDefault())
        val filtered = if (query.isEmpty()) allUsersCache
        else allUsersCache.filter { user ->
            matchesFlexibleUserQuery(
                query,
                user.usuario,
                user.personas?.identificacion?.toString(),
                user.personas?.cedula?.toString(),
                user.personas?.nombres,
                user.personas?.apellidos
            )
        }

        updateActivationStats(activeCountText, inactiveCountText, totalCountText, filtered)
        listContainer.removeAllViews()

        if (filtered.isEmpty()) {
            listContainer.addView(createEmptyStateView(
                if (allUsersCache.isEmpty()) "No hay usuarios disponibles" else "Sin resultados para \"$activationSearchQuery\"",
                if (allUsersCache.isEmpty()) "👥" else "🔍"
            ))
            return
        }

        filtered.forEachIndexed { index, user ->
            val card = createActivationUserCard(user, listContainer, activeCountText, inactiveCountText, totalCountText)
            card.alpha = 0f
            card.translationY = 16f
            listContainer.addView(card)
            card.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setStartDelay((index * 30).toLong().coerceAtMost(300))
                .setInterpolator(FastOutSlowInInterpolator())
                .start()
        }
    }

    private fun updateActivationStats(
        activeText: TextView,
        inactiveText: TextView,
        totalText: TextView,
        users: List<com.example.tareamov.data.entity.Usuario>
    ) {
        val active = users.count { it.isActive }
        val inactive = users.size - active
        activeText.text = active.toString()
        inactiveText.text = inactive.toString()
        totalText.text = users.size.toString()
    }

    private fun createActivationUserCard(
        user: com.example.tareamov.data.entity.Usuario,
        listContainer: LinearLayout,
        activeCountText: TextView,
        inactiveCountText: TextView,
        totalCountText: TextView
    ): LinearLayout {
        val card = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16.dpToPx().toFloat()
                setColor(Color.parseColor("#1C1C1E"))
            }
            background = bg
            setPadding(14.dpToPx(), 14.dpToPx(), 14.dpToPx(), 14.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.bottomMargin = 10.dpToPx() }
            if (!user.isActive) alpha = 0.65f
        }

        // ── Top row: Avatar + Info + Toggle ──
        val topRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Avatar
        val avatarView = de.hdodenhof.circleimageview.CircleImageView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
                .also { it.marginEnd = 14.dpToPx() }
            borderWidth = 2.dpToPx()
            borderColor = if (user.isActive) Color.parseColor("#30D158") else Color.parseColor("#3C3C3E")
            setImageResource(R.drawable.placeholder_avatar)
        }
        if (!user.avatar.isNullOrBlank()) {
            Glide.with(this@AdminDashboardFragment)
                .load(user.avatar)
                .placeholder(R.drawable.placeholder_avatar)
                .into(avatarView)
        }

        // Info columna central
        val infoColumn = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(requireContext()).apply {
            text = user.usuario ?: "Usuario"
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        val statusRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 3.dpToPx() }
        }

        val statusDot = View(requireContext()).apply {
            val size = 8.dpToPx()
            layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = 6.dpToPx() }
            val dotBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(if (user.isActive) Color.parseColor("#30D158") else Color.parseColor("#FF453A"))
            }
            background = dotBg
        }

        val statusText = TextView(requireContext()).apply {
            text = if (user.isActive) "Activo" else "Inactivo"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 12f
        }

        statusRow.addView(statusDot)
        statusRow.addView(statusText)
        infoColumn.addView(nameView)
        infoColumn.addView(statusRow)

        // Toggle switch visual
        val toggleContainer = LinearLayout(requireContext()).apply {
            gravity = android.view.Gravity.CENTER
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10.dpToPx().toFloat()
                setColor(
                    if (user.isActive) Color.parseColor("#FF453A18")
                    else Color.parseColor("#30D15818")
                )
            }
            background = bg
            setPadding(14.dpToPx(), 8.dpToPx(), 14.dpToPx(), 8.dpToPx())
        }

        val toggleLabel = TextView(requireContext()).apply {
            text = if (user.isActive) "Desactivar" else "Activar"
            setTextColor(if (user.isActive) Color.parseColor("#FF453A") else Color.parseColor("#30D158"))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        toggleContainer.addView(toggleLabel)

        toggleContainer.setOnClickListener {
            toggleLabel.text = "..."
            toggleContainer.alpha = 0.5f
            lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        if (user.isActive) BackendApiService.deactivateUser(user.id)
                        else BackendApiService.activateUser(user.id)
                    }
                    val idx = allUsersCache.indexOf(user)
                    if (idx >= 0) {
                        val updated = user.copy(isActive = !user.isActive)
                        allUsersCache = allUsersCache.toMutableList().also { it[idx] = updated }
                    }
                    renderFilteredActivationUsers(listContainer, activeCountText, inactiveCountText, totalCountText)
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error toggling user activation", e)
                    toggleLabel.text = if (user.isActive) "Desactivar" else "Activar"
                    toggleContainer.alpha = 1f
                }
            }
        }

        topRow.addView(avatarView)
        topRow.addView(infoColumn)
        topRow.addView(toggleContainer)
        card.addView(topRow)

        // ── Role chips row ──
        val roleChipsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = 10.dpToPx() }
        }

        val roleLabel = TextView(requireContext()).apply {
            text = "Roles"
            setTextColor(Color.parseColor("#636366"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.marginEnd = 10.dpToPx() }
        }
        roleChipsRow.addView(roleLabel)

        data class RoleDef(val id: Long, val label: String, val color: String, val bgColor: String)
        val roles = listOf(
            RoleDef(1L, "Usuario", "#007AFF", "#007AFF"),
            RoleDef(2L, "Docente", "#BF5AF2", "#BF5AF2"),
            RoleDef(3L, "Admin", "#FF9500", "#FF9500")
        )

        // Cargar roles del usuario asíncronamente
        val chipViews = mutableMapOf<Long, LinearLayout>()
        val chipLabels = mutableMapOf<Long, TextView>()
        val chipDots = mutableMapOf<Long, View>()

        for (role in roles) {
            val chip = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                val chipBg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8.dpToPx().toFloat()
                    setColor(Color.parseColor("#2C2C2E"))
                    setStroke(0, Color.TRANSPARENT)
                }
                background = chipBg
                setPadding(10.dpToPx(), 5.dpToPx(), 10.dpToPx(), 5.dpToPx())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginEnd = 6.dpToPx() }
            }

            val chipDot = View(requireContext()).apply {
                val size = 6.dpToPx()
                layoutParams = LinearLayout.LayoutParams(size, size).also { it.marginEnd = 5.dpToPx() }
                val dotBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#636366"))
                }
                background = dotBg
            }

            val chipText = TextView(requireContext()).apply {
                text = role.label
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }

            chip.addView(chipDot)
            chip.addView(chipText)
            roleChipsRow.addView(chip)
            chipViews[role.id] = chip
            chipLabels[role.id] = chipText
            chipDots[role.id] = chipDot
        }

        card.addView(roleChipsRow)

        // Función para actualizar el estilo visual de un chip
        fun updateChipStyle(roleId: Long, active: Boolean) {
            val roleDef = roles.find { it.id == roleId } ?: return
            val chip = chipViews[roleId] ?: return
            val label = chipLabels[roleId] ?: return
            val dot = chipDots[roleId] ?: return

            val chipBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 8.dpToPx().toFloat()
                if (active) {
                    setColor(Color.parseColor(roleDef.bgColor + "18"))
                    setStroke(2.dpToPx(), Color.parseColor(roleDef.color + "60"))
                } else {
                    setColor(Color.parseColor("#2C2C2E"))
                    setStroke(0, Color.TRANSPARENT)
                }
            }
            chip.background = chipBg

            label.setTextColor(
                if (active) Color.parseColor(roleDef.color)
                else Color.parseColor("#8E8E93")
            )

            val dotBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(
                    if (active) Color.parseColor(roleDef.color)
                    else Color.parseColor("#636366")
                )
            }
            dot.background = dotBg
        }

        // Estado local de roles activos para este usuario
        val userRoles = mutableSetOf<Long>()

        // Cargar roles actuales del usuario
        lifecycleScope.launch {
            try {
                val roleIds = withContext(Dispatchers.IO) {
                    BackendApiService.getUserRoles(user.id).getOrNull() ?: emptyList()
                }
                userRoles.addAll(roleIds)
                roleIds.forEach { roleId -> updateChipStyle(roleId, true) }
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading roles for user ${user.id}", e)
            }
        }

        // Click handlers para cada chip
        for (role in roles) {
            chipViews[role.id]?.setOnClickListener {
                val chip = chipViews[role.id] ?: return@setOnClickListener
                chip.alpha = 0.5f

                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            BackendApiService.assignRole(user.id, role.id)
                        }
                        if (!userRoles.contains(role.id)) {
                            userRoles.add(role.id)
                        }
                        updateChipStyle(role.id, true)
                        chip.alpha = 1f

                        // Animación sutil de confirmación
                        chip.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100)
                            .withEndAction {
                                chip.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                            }.start()
                    } catch (e: Exception) {
                        Log.e("AdminDashboard", "Error assigning role ${role.id} to user ${user.id}", e)
                        chip.alpha = 1f
                    }
                }
            }
        }

        return card
    }

    private fun loadUsersIntoContainer(
        listContainer: LinearLayout,
        loadingText: TextView,
        activeCountText: TextView,
        inactiveCountText: TextView,
        totalCountText: TextView
    ) {
        lifecycleScope.launch {
            try {
                val users = withContext(Dispatchers.IO) {
                    BackendApiService.listAllUsers().getOrNull() ?: emptyList()
                }
                if (currentSection != DashboardSection.ACTIVATION) return@launch
                loadingText.visibility = View.GONE
                allUsersCache = users
                renderFilteredActivationUsers(listContainer, activeCountText, inactiveCountText, totalCountText)
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading users for activation", e)
                loadingText.text = "Error al cargar usuarios"
            }
        }
    }

    // ==================== SECCIÓN 8: MATRÍCULA ====================

    private var enrollmentUserFilter = ""
    private var enrollmentCourseFilter = ""
    private var pendingSection: DashboardSection? = null
    private var allEnrollmentCards: List<EnrollmentRequestCardData> = emptyList()
    private var enrollmentListContainer: LinearLayout? = null
    private var enrollmentPendingCountTv: TextView? = null
    private var enrollmentLocalApproved = 0
    private var enrollmentLocalRejected = 0
    private var enrollmentApprovedCountTv: TextView? = null
    private var enrollmentRejectedCountTv: TextView? = null

    private var enrollmentCurrentSubTab = "pre" // "pre" or "matricula"
    private var approvedEnrollmentsList: List<ApprovedEnrollmentData> = emptyList()
    private var approvedListContainer: LinearLayout? = null
    private var approvedUserFilterStr = ""
    private var approvedCourseFilterStr = ""
    private var approvedSubjectFilterStr = ""
    private var approvedLoaded = false
    private val blockedSubjectKeys = mutableSetOf<String>() // "userId-courseId-subjectId"

    data class ApprovedEnrollmentData(
        val userId: Long,
        val courseId: Long,
        val username: String?,
        val fullName: String?,
        val avatar: String?,
        val documentId: String?,
        val courseName: String?,
        val subjects: List<SubjectInfo>,
        val courseEnrollmentStatus: String = "activo",
        val status: String = "approved"
    )

    data class SubjectInfo(val id: Long, val name: String, val enrollmentStatus: String = "activo")

    private fun loadEnrollmentSection() {
        if (!hasAdminRole()) {
            titleTextView.text = "Matrícula"
            sectionsContainer.addView(TextView(requireContext()).apply {
                text = "Se requiere rol de administrador"
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 40.dpToPx(), 0, 0)
            })
            return
        }

        titleTextView.text = "Matrícula"

        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // ── Sub-tabs: Prematrícula / Matrícula ──
        val subTabsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
        }

        val subContentContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        fun createSubTab(label: String): TextView {
            return TextView(requireContext()).apply {
                text = label
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
                setPadding(16.dpToPx(), 10.dpToPx(), 16.dpToPx(), 10.dpToPx())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    .also { it.marginEnd = 8.dpToPx() }
            }
        }

        val preTab = createSubTab("Prematrícula")
        val matTab = createSubTab("Matrícula")
        (matTab.layoutParams as LinearLayout.LayoutParams).marginEnd = 0

        fun styleSubTab(tab: TextView, active: Boolean) {
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f.dpToPxF()
                if (active) {
                    setColor(Color.parseColor("#6366F126"))
                    setStroke(1.dpToPx(), Color.parseColor("#6366F14D"))
                } else {
                    setColor(Color.parseColor("#1C1C1E"))
                    setStroke(1.dpToPx(), Color.parseColor("#2C2C2E"))
                }
            }
            tab.background = bg
            tab.setTextColor(if (active) Color.parseColor("#818CF8") else Color.parseColor("#8E8E93"))
        }

        fun switchSubTab(tab: String) {
            enrollmentCurrentSubTab = tab
            styleSubTab(preTab, tab == "pre")
            styleSubTab(matTab, tab == "matricula")
            subContentContainer.removeAllViews()
            if (tab == "pre") {
                loadPreEnrollmentContent(subContentContainer)
            } else {
                loadMatriculaContent(subContentContainer)
            }
        }

        preTab.setOnClickListener { switchSubTab("pre") }
        matTab.setOnClickListener { switchSubTab("matricula") }

        subTabsRow.addView(preTab)
        subTabsRow.addView(matTab)
        root.addView(subTabsRow)
        root.addView(subContentContainer)
        sectionsContainer.addView(root)

        switchSubTab("pre")
    }

    private fun loadPreEnrollmentContent(container: LinearLayout) {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 0.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // ── Header card with gradient background ──
        val headerCard = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val gd = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f.dpToPxF()
                colors = intArrayOf(Color.parseColor("#1A1042"), Color.parseColor("#0F172A"))
                setStroke(1.dpToPx(), Color.parseColor("#2E1065"))
            }
            background = gd
            setPadding(20.dpToPx(), 18.dpToPx(), 20.dpToPx(), 18.dpToPx())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = 16.dpToPx() }
        }

        headerCard.addView(TextView(requireContext()).apply {
            text = "Gestión de Matrículas"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        headerCard.addView(TextView(requireContext()).apply {
            text = "Aprueba o rechaza solicitudes de acceso a cursos para usuarios pendientes"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            setPadding(0, 4.dpToPx(), 0, 0)
        })

        // ── Stats row ──
        val statsRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.topMargin = 14.dpToPx() }
        }
        val pendingCountTv = TextView(requireContext())
        val approvedCountTv = TextView(requireContext())
        val rejectedCountTv = TextView(requireContext())

        fun createStatPill(label: String, color: String, countTv: TextView): LinearLayout {
            return LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val pillBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = 10f.dpToPxF()
                    setColor(Color.parseColor(color + "1A"))
                    setStroke(1.dpToPx(), Color.parseColor(color + "33"))
                }
                background = pillBg
                setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    .also { it.marginEnd = 8.dpToPx() }
                countTv.text = "0"
                countTv.setTextColor(Color.parseColor(color))
                countTv.textSize = 18f
                countTv.typeface = android.graphics.Typeface.DEFAULT_BOLD
                addView(countTv)
                addView(TextView(requireContext()).apply {
                    text = label
                    setTextColor(Color.parseColor(color))
                    textSize = 11f
                    setPadding(6.dpToPx(), 0, 0, 0)
                })
            }
        }

        statsRow.addView(createStatPill("Pendientes", "#FF9500", pendingCountTv))
        statsRow.addView(createStatPill("Aprobadas", "#30D158", approvedCountTv))
        val rejPill = createStatPill("Rechazadas", "#FF453A", rejectedCountTv)
        (rejPill.layoutParams as LinearLayout.LayoutParams).marginEnd = 0
        statsRow.addView(rejPill)
        headerCard.addView(statsRow)
        root.addView(headerCard)

        // ── Filter inputs ──
        val filtersRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = 12.dpToPx() }
        }

        fun createFilterInput(hint: String): EditText {
            return EditText(requireContext()).apply {
                this.hint = hint
                setHintTextColor(Color.parseColor("#636366"))
                setTextColor(Color.WHITE)
                textSize = 13f
                isSingleLine = true
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10f.dpToPxF()
                    setColor(Color.parseColor("#1C1C1E"))
                    setStroke(1.dpToPx(), Color.parseColor("#2C2C2E"))
                }
                background = bg
                setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    .also { it.marginEnd = 8.dpToPx() }
            }
        }

        // Capture any deep-link pre-filter before the reset that happens later in this function
        val deepLinkUserFilter = enrollmentUserFilter
        val deepLinkCourseFilter = enrollmentCourseFilter

        val userFilterInput = createFilterInput("Buscar por usuario, nombre o cédula...")
        val courseFilterInput = createFilterInput("Buscar por curso...")
        (courseFilterInput.layoutParams as LinearLayout.LayoutParams).marginEnd = 0

        val filterTextWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                enrollmentUserFilter = userFilterInput.text.toString()
                enrollmentCourseFilter = courseFilterInput.text.toString()
                renderFilteredEnrollmentCards()
            }
        }
        userFilterInput.addTextChangedListener(filterTextWatcher)
        courseFilterInput.addTextChangedListener(filterTextWatcher)

        filtersRow.addView(userFilterInput)
        filtersRow.addView(courseFilterInput)
        root.addView(filtersRow)

        // ── Loading skeleton ──
        val skeletonContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        repeat(3) {
            val skeleton = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f.dpToPxF()
                    setColor(Color.parseColor("#1C1C1E"))
                }
                background = bg
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .also { it.bottomMargin = 10.dpToPx() }
            }
            val circle = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
                    .also { it.marginEnd = 14.dpToPx() }
                val bgShape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#2C2C2E"))
                }
                background = bgShape
            }
            val lines = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val line1 = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams((160.dpToPx()), 12.dpToPx())
                    .also { it.bottomMargin = 8.dpToPx() }
                val bg2 = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 6f.dpToPxF()
                    setColor(Color.parseColor("#2C2C2E"))
                }
                background = bg2
            }
            val line2 = View(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams((100.dpToPx()), 12.dpToPx())
                val bg3 = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 6f.dpToPxF()
                    setColor(Color.parseColor("#2C2C2E"))
                }
                background = bg3
            }
            lines.addView(line1)
            lines.addView(line2)
            skeleton.addView(circle)
            skeleton.addView(lines)
            skeletonContainer.addView(skeleton)
        }
        root.addView(skeletonContainer)

        // ── List container ──
        val listContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        enrollmentListContainer = listContainer
        enrollmentPendingCountTv = pendingCountTv
        enrollmentApprovedCountTv = approvedCountTv
        enrollmentRejectedCountTv = rejectedCountTv
        enrollmentLocalApproved = 0
        enrollmentLocalRejected = 0
        enrollmentUserFilter = ""
        enrollmentCourseFilter = ""
        // Re-apply deep-link filters after the reset (TextWatcher is attached so class vars update)
        if (deepLinkUserFilter.isNotBlank()) userFilterInput.setText(deepLinkUserFilter)
        if (deepLinkCourseFilter.isNotBlank()) courseFilterInput.setText(deepLinkCourseFilter)
        root.addView(listContainer)
        container.addView(root)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val enrollmentCards = withContext(Dispatchers.IO) {
                    val enrollments = BackendApiService.getPendingEnrollments().getOrNull() ?: emptyList()
                    val roleDefinitions = BackendApiService.getRoles().getOrNull().orEmpty()
                    val roleNameById = roleDefinitions.associate { role ->
                        role.id to formatEnrollmentRoleName(role.nombre)
                    }

                    val parsedRows = enrollments.mapNotNull { req ->
                        val userId = req.getLongValue("userId", "user_id") ?: return@mapNotNull null
                        val courseId = req.getLongValue("courseId", "course_id", "curso_id") ?: return@mapNotNull null
                        val username = req.getStringValue("username", "usuario") ?: "Usuario #$userId"
                        val firstName = req.getStringValue("nombres")
                        val lastName = req.getStringValue("apellidos")
                        val fullName = req.getStringValue("fullName")
                            ?: listOfNotNull(firstName, lastName).joinToString(" ").trim().ifBlank { null }
                        val courseName = req.getStringValue("courseName", "course_name") ?: "Curso #$courseId"
                        val avatarUrl = req.getStringValue("avatar")
                        val requestedAt = req.getStringValue("requestedAt", "requested_at")
                        val documentId = req.getStringValue("cedula", "identificacion_original", "identificacion")
                            ?: req.getLongValue("cedula", "identificacion")?.toString()

                        EnrollmentRequestCardData(
                            userId = userId,
                            courseId = courseId,
                            fullName = fullName,
                            username = username,
                            courseName = courseName,
                            avatarUrl = avatarUrl,
                            requestedAt = requestedAt,
                            roleChips = emptyList(),
                            payloadRoleIds = req.extractEnrollmentRoleIds(),
                            payloadRoleLabels = req.extractEnrollmentRoleLabels(),
                            documentId = documentId
                        )
                    }

                    val fallbackRoleMap = coroutineScope {
                        parsedRows
                            .filter { it.payloadRoleIds.isEmpty() && it.payloadRoleLabels.isEmpty() }
                            .map { it.userId }
                            .distinct()
                            .map { userId ->
                                async {
                                    userId to (BackendApiService.getUserRoles(userId).getOrNull() ?: emptyList())
                                }
                            }
                            .awaitAll()
                            .toMap()
                    }

                    val fallbackUsersById = if (parsedRows.isEmpty()) {
                        emptyMap()
                    } else {
                        BackendApiService.getUsersByIds(parsedRows.map { it.userId }.distinct())
                            .getOrNull()
                            .orEmpty()
                            .associateBy { it.id }
                    }

                    parsedRows.map { row ->
                        val fallbackUser = fallbackUsersById[row.userId]
                        val fallbackFullName = listOfNotNull(
                            fallbackUser?.personas?.nombres,
                            fallbackUser?.personas?.apellidos
                        ).joinToString(" ").trim().ifBlank { null }

                        row.copy(
                            fullName = row.fullName ?: fallbackFullName,
                            avatarUrl = row.avatarUrl ?: fallbackUser?.avatar,
                            documentId = row.documentId
                                ?: fallbackUser?.personas?.cedula?.toString()
                                ?: fallbackUser?.personas?.identificacion?.toString(),
                            roleChips = buildEnrollmentRoleChips(
                                roleIds = if (row.payloadRoleIds.isNotEmpty()) row.payloadRoleIds else fallbackRoleMap[row.userId].orEmpty(),
                                roleLabels = row.payloadRoleLabels,
                                roleNameById = roleNameById
                            )
                        )
                    }
                }
                val uiContext = context ?: return@launch
                if (currentSection != DashboardSection.ENROLLMENT) return@launch
                skeletonContainer.visibility = View.GONE

                allEnrollmentCards = enrollmentCards
                renderFilteredEnrollmentCards()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading pending enrollments", e)
                val uiContext = context ?: return@launch
                skeletonContainer.visibility = View.GONE
                listContainer.addView(TextView(uiContext).apply {
                    text = "Error al cargar solicitudes"
                    setTextColor(Color.parseColor("#FF453A"))
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 30.dpToPx(), 0, 0)
                })
            }
        }
    }

    private fun loadMatriculaContent(container: LinearLayout) {
        val uiContext = requireContext()
        val root = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 0.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        // ── Header card ──
        val headerCard = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            val gd = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 16f.dpToPxF()
                colors = intArrayOf(Color.parseColor("#1A1042"), Color.parseColor("#0F172A"))
                setStroke(1.dpToPx(), Color.parseColor("#2E1065"))
            }
            background = gd
            setPadding(20.dpToPx(), 18.dpToPx(), 20.dpToPx(), 18.dpToPx())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = 16.dpToPx() }
        }

        headerCard.addView(TextView(uiContext).apply {
            text = "Usuarios Matriculados"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        headerCard.addView(TextView(uiContext).apply {
            text = "Gestiona el acceso de usuarios matriculados a cursos y materias"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            setPadding(0, 4.dpToPx(), 0, 0)
        })
        root.addView(headerCard)

        // ── Filter inputs ──
        val filtersRow = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = 12.dpToPx() }
        }

        fun createFilterInput(hint: String): EditText {
            return EditText(uiContext).apply {
                this.hint = hint
                setHintTextColor(Color.parseColor("#636366"))
                setTextColor(Color.WHITE)
                textSize = 13f
                isSingleLine = true
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 10f.dpToPxF()
                    setColor(Color.parseColor("#1C1C1E"))
                    setStroke(1.dpToPx(), Color.parseColor("#2C2C2E"))
                }
                background = bg
                setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                    .also { it.marginEnd = 8.dpToPx() }
            }
        }

        val userFilter = createFilterInput("Buscar por usuario, nombre o cédula...")
        val courseFilter = createFilterInput("Buscar curso...")
        val subjectFilter = createFilterInput("Buscar materia...")
        (subjectFilter.layoutParams as LinearLayout.LayoutParams).marginEnd = 0

        val filterWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                approvedUserFilterStr = userFilter.text.toString()
                approvedCourseFilterStr = courseFilter.text.toString()
                approvedSubjectFilterStr = subjectFilter.text.toString()
                renderFilteredApprovedEnrollments()
            }
        }
        userFilter.addTextChangedListener(filterWatcher)
        courseFilter.addTextChangedListener(filterWatcher)
        subjectFilter.addTextChangedListener(filterWatcher)

        filtersRow.addView(userFilter)
        filtersRow.addView(courseFilter)
        filtersRow.addView(subjectFilter)
        root.addView(filtersRow)

        // ── Loading skeleton ──
        val skeletonContainer = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        repeat(3) {
            val skeleton = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f.dpToPxF()
                    setColor(Color.parseColor("#1C1C1E"))
                }
                background = bg
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .also { it.bottomMargin = 10.dpToPx() }
            }
            val circle = View(uiContext).apply {
                layoutParams = LinearLayout.LayoutParams(48.dpToPx(), 48.dpToPx())
                    .also { it.marginEnd = 14.dpToPx() }
                val bgShape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#2C2C2E"))
                }
                background = bgShape
            }
            val lines = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            lines.addView(View(uiContext).apply {
                layoutParams = LinearLayout.LayoutParams(160.dpToPx(), 12.dpToPx())
                    .also { it.bottomMargin = 8.dpToPx() }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 6f.dpToPxF(); setColor(Color.parseColor("#2C2C2E"))
                }
            })
            lines.addView(View(uiContext).apply {
                layoutParams = LinearLayout.LayoutParams(100.dpToPx(), 12.dpToPx())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 6f.dpToPxF(); setColor(Color.parseColor("#2C2C2E"))
                }
            })
            skeleton.addView(circle)
            skeleton.addView(lines)
            skeletonContainer.addView(skeleton)
        }
        root.addView(skeletonContainer)

        // ── List container ──
        val listContainer = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        approvedListContainer = listContainer
        approvedUserFilterStr = ""
        approvedCourseFilterStr = ""
        approvedSubjectFilterStr = ""
        root.addView(listContainer)
        container.addView(root)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (enrolled, allBlocks) = withContext(Dispatchers.IO) {
                    val dataDeferred = async { BackendApiService.getApprovedEnrollments().getOrNull() ?: emptyList() }
                    val blocksDeferred = async { BackendApiService.getAllSubjectAccessBlocks().getOrNull() ?: emptyList() }
                    val data = dataDeferred.await()
                    val blocks = blocksDeferred.await()

                    val userIds = data.mapNotNull { obj -> obj.getLongValue("userId", "user_id") }.distinct()
                    val usersById = if (userIds.isEmpty()) {
                        emptyMap()
                    } else {
                        BackendApiService.getUsersByIds(userIds).getOrNull().orEmpty().associateBy { it.id }
                    }

                    val enrolledList = data.mapNotNull { obj ->
                        val userId = obj.getLongValue("userId", "user_id") ?: return@mapNotNull null
                        val courseId = obj.getLongValue("courseId", "course_id") ?: return@mapNotNull null
                        val user = usersById[userId]
                        val subjectsArr = try {
                            val arr = obj.getAsJsonArray("subjects")
                            arr?.map { elem ->
                                val so = elem.asJsonObject
                                SubjectInfo(
                                    id = so.get("id")?.asLong ?: 0L,
                                    name = so.get("name")?.asString ?: "",
                                    enrollmentStatus = so.get("enrollmentStatus")?.asString
                                        ?: so.get("enrollment_status")?.asString ?: "activo"
                                )
                            } ?: emptyList()
                        } catch (_: Exception) { emptyList() }

                        val fallbackFullName = listOfNotNull(
                            user?.personas?.nombres,
                            user?.personas?.apellidos
                        ).joinToString(" ").trim().ifBlank { null }

                        ApprovedEnrollmentData(
                            userId = userId,
                            courseId = courseId,
                            username = obj.getStringValue("username") ?: user?.usuario,
                            fullName = obj.getStringValue("fullName") ?: fallbackFullName,
                            avatar = obj.getStringValue("avatar") ?: user?.avatar,
                            documentId = obj.getStringValue("cedula", "identificacion_original", "identificacion")
                                ?: obj.getLongValue("cedula", "identificacion")?.toString()
                                ?: user?.personas?.cedula?.toString()
                                ?: user?.personas?.identificacion?.toString(),
                            courseName = obj.getStringValue("courseName", "course_name") ?: "Curso #$courseId",
                            subjects = subjectsArr,
                            courseEnrollmentStatus = obj.getStringValue("courseEnrollmentStatus", "course_enrollment_status") ?: "activo",
                            status = obj.getStringValue("status", "enrollmentStatus", "enrollment_status") ?: "approved"
                        )
                    }
                    Pair(enrolledList, blocks)
                }
                if (context == null || currentSection != DashboardSection.ENROLLMENT) return@launch

                // Pre-populate blocked subject keys from persisted DB records
                for (block in allBlocks) {
                    val uid = block.getLongValue("userId", "user_id") ?: continue
                    val cid = block.getLongValue("courseId", "course_id") ?: continue
                    val sid = block.getLongValue("subjectId", "subject_id") ?: continue
                    blockedSubjectKeys.add("${uid}-${cid}-${sid}")
                }

                skeletonContainer.visibility = View.GONE
                approvedEnrollmentsList = enrolled
                approvedLoaded = true
                renderFilteredApprovedEnrollments()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("AdminDashboard", "Error loading approved enrollments", e)
                if (context == null) return@launch
                skeletonContainer.visibility = View.GONE
                listContainer.addView(TextView(uiContext).apply {
                    text = "Error al cargar matrículas"
                    setTextColor(Color.parseColor("#FF453A"))
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 30.dpToPx(), 0, 0)
                })
            }
        }
    }

    private fun renderFilteredApprovedEnrollments() {
        val container = approvedListContainer ?: return
        val uiContext = context ?: return
        container.removeAllViews()

        val uq = approvedUserFilterStr.lowercase()
        val cq = approvedCourseFilterStr.lowercase()
        val sq = approvedSubjectFilterStr.lowercase()

        val filtered = approvedEnrollmentsList.filter { e ->
            val matchUser = matchesFlexibleUserQuery(uq, e.fullName, e.username, e.documentId)
            val matchCourse = cq.isBlank() || (e.courseName ?: "").lowercase().contains(cq)
            val matchSubject = sq.isBlank() || e.subjects.any { it.name.lowercase().contains(sq) }
            matchUser && matchCourse && matchSubject
        }

        val activeFiltered = filtered.filter { it.status != "revoked" }
        val revokedFiltered = filtered.filter { it.status == "revoked" }

        if (activeFiltered.isEmpty() && revokedFiltered.isEmpty()) {
            container.addView(createEnrollmentEmptyStateView(uiContext))
            return
        }

        // Group by course
        val grouped = activeFiltered.groupBy { it.courseId }
        grouped.forEach { (courseId, users) ->
            val courseName = users.firstOrNull()?.courseName ?: "Curso #$courseId"
            val subjects = users.firstOrNull()?.subjects ?: emptyList()

            // Course group card
            val groupCard = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 18f.dpToPxF()
                    colors = intArrayOf(Color.parseColor("#1E1F25"), Color.parseColor("#18181B"))
                    orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                    setStroke(1.dpToPx(), Color.parseColor("#1F2937"))
                }
                background = bg
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .also { it.bottomMargin = 14.dpToPx() }
            }

            // Course header
            val courseHeader = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18.dpToPx(), 16.dpToPx(), 18.dpToPx(), 12.dpToPx())
            }

            val courseInfoRow = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            courseInfoRow.addView(TextView(uiContext).apply {
                text = courseName
                setTextColor(Color.parseColor("#F8FAFC"))
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            courseInfoRow.addView(TextView(uiContext).apply {
                text = "${users.size} usuario${if (users.size != 1) "s" else ""}"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 12f
                val pillBg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(Color.parseColor("#94A3B81A"))
                }
                background = pillBg
                setPadding(10.dpToPx(), 3.dpToPx(), 10.dpToPx(), 3.dpToPx())
            })
            courseHeader.addView(courseInfoRow)

            // Subject chips
            if (subjects.isNotEmpty()) {
                val subjectsRow = android.widget.HorizontalScrollView(uiContext).apply {
                    isHorizontalScrollBarEnabled = false
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        .also { it.topMargin = 10.dpToPx() }
                }
                val chipsContainer = LinearLayout(uiContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                subjects.forEach { subj ->
                    chipsContainer.addView(TextView(uiContext).apply {
                        text = subj.name
                        setTextColor(Color.parseColor("#818CF8"))
                        textSize = 11f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        val chipBg = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 999f
                            setColor(Color.parseColor("#6366F11A"))
                            setStroke(1.dpToPx(), Color.parseColor("#6366F12E"))
                        }
                        background = chipBg
                        setPadding(10.dpToPx(), 4.dpToPx(), 10.dpToPx(), 4.dpToPx())
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                            .also { it.marginEnd = 6.dpToPx() }
                    })
                }
                subjectsRow.addView(chipsContainer)
                courseHeader.addView(subjectsRow)
            }

            groupCard.addView(courseHeader)

            // Divider
            groupCard.addView(View(uiContext).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
                setBackgroundColor(Color.parseColor("#ffffff0D"))
            })

            // User list
            val usersContainer = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(12.dpToPx(), 8.dpToPx(), 12.dpToPx(), 8.dpToPx())
            }

            users.forEach { userData ->
                val isInactiveCourse = userData.courseEnrollmentStatus == "inactivo"

                // Outer card for each user (vertical layout)
                val userCard = LinearLayout(uiContext).apply {
                    orientation = LinearLayout.VERTICAL
                    val cardBg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14f.dpToPxF()
                        if (isInactiveCourse) {
                            setColor(Color.parseColor("#FFC10708"))
                            setStroke(1.dpToPx(), Color.parseColor("#FFC10740"))
                        }
                    }
                    background = cardBg
                    setPadding(10.dpToPx(), 12.dpToPx(), 10.dpToPx(), 12.dpToPx())
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        .also { it.bottomMargin = 6.dpToPx() }
                    alpha = if (isInactiveCourse) 0.55f else 1f
                }

                // Top row: avatar + info + revoke button
                val topRow = LinearLayout(uiContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                }

                val avatarView = de.hdodenhof.circleimageview.CircleImageView(uiContext).apply {
                    layoutParams = LinearLayout.LayoutParams(42.dpToPx(), 42.dpToPx())
                        .also { it.marginEnd = 12.dpToPx() }
                    borderWidth = 1.dpToPx()
                    borderColor = Color.parseColor("#334155")
                    setImageResource(R.drawable.placeholder_avatar)
                }
                if (!userData.avatar.isNullOrBlank()) {
                    Glide.with(this@AdminDashboardFragment).load(userData.avatar)
                        .placeholder(R.drawable.placeholder_avatar).into(avatarView)
                }

                val infoCol = LinearLayout(uiContext).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                }
                infoCol.addView(TextView(uiContext).apply {
                    text = userData.fullName ?: userData.username ?: "Usuario #${userData.userId}"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                infoCol.addView(TextView(uiContext).apply {
                    text = "@${userData.username ?: userData.userId}"
                    setTextColor(Color.parseColor(if (isInactiveCourse) "#71717A" else "#94A3B8"))
                    textSize = 12f
                })
                if (isInactiveCourse) {
                    infoCol.addView(TextView(uiContext).apply {
                        text = "⏸ Curso inactivo"
                        setTextColor(Color.parseColor("#FBBF24"))
                        textSize = 10f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 6f.dpToPxF()
                            setColor(Color.parseColor("#FBBF241E"))
                        }
                        background = badgeBg
                        setPadding(6.dpToPx(), 1.dpToPx(), 6.dpToPx(), 1.dpToPx())
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                            .also { it.topMargin = 2.dpToPx() }
                    })
                }

                // Activate/Deactivate course button
                val toggleCourseBtn = android.widget.Button(uiContext).apply {
                    text = if (isInactiveCourse) "✓ Activar curso" else "⏸ Inactivar curso"
                    setTextColor(Color.parseColor(if (isInactiveCourse) "#34D399" else "#FBBF24"))
                    isAllCaps = false
                    textSize = 11f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val btnBg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 10f.dpToPxF()
                        if (isInactiveCourse) {
                            setColor(Color.parseColor("#34D39914"))
                            setStroke(1.dpToPx(), Color.parseColor("#34D39930"))
                        } else {
                            setColor(Color.parseColor("#FBBF2414"))
                            setStroke(1.dpToPx(), Color.parseColor("#FBBF2424"))
                        }
                    }
                    background = btnBg
                    setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    minHeight = 0
                    minimumHeight = 0
                }
                toggleCourseBtn.setOnClickListener {
                    toggleCourseBtn.isEnabled = false
                    val newStatus = if (isInactiveCourse) "activo" else "inactivo"
                    toggleCourseBtn.text = if (isInactiveCourse) "Activando..." else "Inactivando..."
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                BackendApiService.setCourseEnrollmentStatus(userData.userId, courseId, newStatus)
                            }
                            approvedEnrollmentsList = approvedEnrollmentsList.map {
                                if (it.userId == userData.userId && it.courseId == courseId)
                                    it.copy(courseEnrollmentStatus = newStatus)
                                else it
                            }
                            renderFilteredApprovedEnrollments()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("AdminDashboard", "Error toggling course enrollment status", e)
                            toggleCourseBtn.isEnabled = true
                            toggleCourseBtn.text = if (isInactiveCourse) "✓ Activar curso" else "⏸ Inactivar curso"
                        }
                    }
                }

                // Buttons column
                val btnsCol = LinearLayout(uiContext).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = android.view.Gravity.END
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                }
                btnsCol.addView(toggleCourseBtn)

                if (!isInactiveCourse) {
                    val revokeBtn = android.widget.Button(uiContext).apply {
                        text = "✕ Quitar curso"
                        setTextColor(Color.parseColor("#FF453A"))
                        isAllCaps = false
                        textSize = 11f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        val btnBg = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 10f.dpToPxF()
                            setColor(Color.parseColor("#FF453A19"))
                            setStroke(1.dpToPx(), Color.parseColor("#FF453A26"))
                        }
                        background = btnBg
                        setPadding(10.dpToPx(), 6.dpToPx(), 10.dpToPx(), 6.dpToPx())
                        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                            .also { it.topMargin = 4.dpToPx() }
                        minHeight = 0
                        minimumHeight = 0
                    }
                    revokeBtn.setOnClickListener {
                        revokeBtn.isEnabled = false
                        revokeBtn.text = "Quitando..."
                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    BackendApiService.revokeEnrollment(userData.userId, courseId)
                                }
                                approvedEnrollmentsList = approvedEnrollmentsList.map {
                                    if (it.userId == userData.userId && it.courseId == courseId)
                                        it.copy(status = "revoked")
                                    else it
                                }
                                // Rebuild immediately so the visual change is instant
                                renderFilteredApprovedEnrollments()
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("AdminDashboard", "Error revoking enrollment", e)
                                revokeBtn.isEnabled = true
                                revokeBtn.text = "✕ Quitar curso"
                            }
                        }
                    }
                    btnsCol.addView(revokeBtn)
                }

                topRow.addView(avatarView)
                topRow.addView(infoCol)
                topRow.addView(btnsCol)
                userCard.addView(topRow)

                // Bottom row: subject chips with individual block buttons
                if (subjects.isNotEmpty()) {
                    val subjectsDivider = View(uiContext).apply {
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1)
                            .also { it.topMargin = 10.dpToPx(); it.bottomMargin = 10.dpToPx() }
                        setBackgroundColor(Color.parseColor("#ffffff0A"))
                    }
                    userCard.addView(subjectsDivider)

                    val subjectsSection = LinearLayout(uiContext).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    }

                    // Label
                    subjectsSection.addView(TextView(uiContext).apply {
                        text = "📚 Materias — toca: bloquear · mantén: inactivar"
                        setTextColor(Color.parseColor("#94A3B8"))
                        textSize = 11f
                        setPadding(0, 0, 0, 8.dpToPx())
                    })

                    // FlowLayout-style: HorizontalScrollView with chips
                    val chipsScrollView = android.widget.HorizontalScrollView(uiContext).apply {
                        isHorizontalScrollBarEnabled = false
                        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    }
                    val chipsRow = LinearLayout(uiContext).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }

                    subjects.forEach { subj ->
                        val isBlocked = blockedSubjectKeys.contains("${userData.userId}-${courseId}-${subj.id}")
                        val isInactiveSubj = subj.enrollmentStatus == "inactivo"

                        val chip = LinearLayout(uiContext).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            val chipBg = android.graphics.drawable.GradientDrawable().apply {
                                cornerRadius = 999f
                                when {
                                    isBlocked -> {
                                        setColor(Color.parseColor("#8E8E9312"))
                                        setStroke(1.dpToPx(), Color.parseColor("#8E8E9338"))
                                    }
                                    isInactiveSubj -> {
                                        setColor(Color.parseColor("#FBBF2410"))
                                        setStroke(1.dpToPx(), Color.parseColor("#FBBF2430"))
                                    }
                                    else -> {
                                        setColor(Color.parseColor("#6366F114"))
                                        setStroke(1.dpToPx(), Color.parseColor("#6366F12E"))
                                    }
                                }
                            }
                            background = chipBg
                            setPadding(10.dpToPx(), 6.dpToPx(), 8.dpToPx(), 6.dpToPx())
                            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                                .also { it.marginEnd = 8.dpToPx() }
                            isClickable = !isBlocked
                            isFocusable = !isBlocked
                            alpha = when {
                                isBlocked -> 0.82f
                                isInactiveSubj -> 0.65f
                                else -> 1f
                            }
                        }

                        // Lock icon (only when blocked)
                        if (isBlocked) {
                            chip.addView(TextView(uiContext).apply {
                                text = "🔒 "
                                textSize = 11f
                            })
                        }
                        // Pause icon (only when inactive, not blocked)
                        if (isInactiveSubj && !isBlocked) {
                            chip.addView(TextView(uiContext).apply {
                                text = "⏸ "
                                textSize = 11f
                            })
                        }

                        val nameView = TextView(uiContext).apply {
                            text = subj.name
                            textSize = 12f
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            when {
                                isBlocked -> {
                                    setTextColor(Color.parseColor("#8E8E93"))
                                    paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                                }
                                isInactiveSubj -> {
                                    setTextColor(Color.parseColor("#FBBF24"))
                                }
                                else -> {
                                    setTextColor(Color.parseColor("#818CF8"))
                                }
                            }
                        }
                        chip.addView(nameView)

                        if (isBlocked) {
                            // "bloqueada" badge
                            chip.addView(TextView(uiContext).apply {
                                text = "  BLOQUEADA"
                                setTextColor(Color.parseColor("#8E8E93"))
                                textSize = 9f
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                letterSpacing = 0.04f
                                val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                                    cornerRadius = 4f.dpToPxF()
                                    setColor(Color.parseColor("#8E8E9322"))
                                }
                                background = badgeBg
                                setPadding(4.dpToPx(), 1.dpToPx(), 4.dpToPx(), 1.dpToPx())
                                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                                    .also { it.marginStart = 4.dpToPx() }
                            })
                            // Make blocked chip clickable to unblock
                            chip.isClickable = true
                            chip.isFocusable = true
                            chip.setOnClickListener {
                                val userName = userData.fullName ?: userData.username ?: "Usuario #${userData.userId}"
                                showUnblockSubjectDialog(userData.userId, courseId, subj.id, userName, subj.name)
                            }
                        } else if (isInactiveSubj) {
                            // "INACTIVA" badge
                            chip.addView(TextView(uiContext).apply {
                                text = "  INACTIVA"
                                setTextColor(Color.parseColor("#FBBF24"))
                                textSize = 9f
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                                letterSpacing = 0.04f
                                val badgeBg = android.graphics.drawable.GradientDrawable().apply {
                                    cornerRadius = 4f.dpToPxF()
                                    setColor(Color.parseColor("#FBBF241E"))
                                }
                                background = badgeBg
                                setPadding(4.dpToPx(), 1.dpToPx(), 4.dpToPx(), 1.dpToPx())
                                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                                    .also { it.marginStart = 4.dpToPx() }
                            })
                            // Click to activate subject
                            chip.setOnClickListener {
                                chip.alpha = 0.4f
                                viewLifecycleOwner.lifecycleScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            BackendApiService.setSubjectEnrollmentStatus(userData.userId, courseId, subj.id, "activo")
                                        }
                                        approvedEnrollmentsList = approvedEnrollmentsList.map { enr ->
                                            if (enr.userId == userData.userId && enr.courseId == courseId) {
                                                enr.copy(subjects = enr.subjects.map { s ->
                                                    if (s.id == subj.id) s.copy(enrollmentStatus = "activo") else s
                                                })
                                            } else enr
                                        }
                                        renderFilteredApprovedEnrollments()
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Log.e("AdminDashboard", "Error activating subject", e)
                                        chip.alpha = 0.65f
                                    }
                                }
                            }
                        } else {
                            // "×" remove icon
                            val removeIcon = TextView(uiContext).apply {
                                text = "  ✕"
                                setTextColor(Color.parseColor("#818CF855"))
                                textSize = 11f
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                            }
                            chip.addView(removeIcon)

                            // Pause icon for deactivation
                            val pauseIcon = TextView(uiContext).apply {
                                text = "  ⏸"
                                setTextColor(Color.parseColor("#FBBF2466"))
                                textSize = 10f
                            }
                            chip.addView(pauseIcon)

                            // Gesture handling: tap to block, long press to deactivate
                            val gestureDetector = android.view.GestureDetector(uiContext, object : android.view.GestureDetector.SimpleOnGestureListener() {
                                override fun onSingleTapUp(e: MotionEvent): Boolean {
                                    val userName = userData.fullName ?: userData.username ?: "Usuario #${userData.userId}"
                                    showBlockSubjectDialog(userData.userId, courseId, userName, subjects, subj.id)
                                    return true
                                }
                                override fun onLongPress(e: MotionEvent) {
                                    chip.alpha = 0.4f
                                    viewLifecycleOwner.lifecycleScope.launch {
                                        try {
                                            withContext(Dispatchers.IO) {
                                                BackendApiService.setSubjectEnrollmentStatus(userData.userId, courseId, subj.id, "inactivo")
                                            }
                                            approvedEnrollmentsList = approvedEnrollmentsList.map { enr ->
                                                if (enr.userId == userData.userId && enr.courseId == courseId) {
                                                    enr.copy(subjects = enr.subjects.map { s ->
                                                        if (s.id == subj.id) s.copy(enrollmentStatus = "inactivo") else s
                                                    })
                                                } else enr
                                            }
                                            renderFilteredApprovedEnrollments()
                                        } catch (e2: kotlinx.coroutines.CancellationException) {
                                            throw e2
                                        } catch (e2: Exception) {
                                            Log.e("AdminDashboard", "Error deactivating subject", e2)
                                            chip.alpha = 1f
                                        }
                                    }
                                }
                            })

                            chip.setOnTouchListener { v, event ->
                                gestureDetector.onTouchEvent(event)
                                val chipBgDrawable = v.background as? android.graphics.drawable.GradientDrawable
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> {
                                        chipBgDrawable?.setColor(Color.parseColor("#FF9F0A22"))
                                        chipBgDrawable?.setStroke(1.dpToPx(), Color.parseColor("#FF9F0A55"))
                                        nameView.setTextColor(Color.parseColor("#FF9F0A"))
                                        removeIcon.setTextColor(Color.parseColor("#FF9F0A"))
                                        v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(80).start()
                                    }
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                        chipBgDrawable?.setColor(Color.parseColor("#6366F114"))
                                        chipBgDrawable?.setStroke(1.dpToPx(), Color.parseColor("#6366F12E"))
                                        nameView.setTextColor(Color.parseColor("#818CF8"))
                                        removeIcon.setTextColor(Color.parseColor("#818CF855"))
                                        v.animate().scaleX(1f).scaleY(1f).setDuration(80).start()
                                    }
                                }
                                true
                            }
                        }

                        chipsRow.addView(chip)
                    }

                    chipsScrollView.addView(chipsRow)
                    subjectsSection.addView(chipsScrollView)
                    userCard.addView(subjectsSection)
                }

                usersContainer.addView(userCard)
            }

            groupCard.addView(usersContainer)
            container.addView(groupCard)
        }

        // ── Render revoked enrollments section ──
        if (revokedFiltered.isNotEmpty()) {
            // Section header
            val revokedHeader = LinearLayout(uiContext).apply {
                orientation = LinearLayout.VERTICAL
                val hdrBg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 16f.dpToPxF()
                    colors = intArrayOf(Color.parseColor("#2D1215"), Color.parseColor("#1A0A0C"))
                    orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                    setStroke(1.dpToPx(), Color.parseColor("#FF453A33"))
                }
                background = hdrBg
                setPadding(20.dpToPx(), 16.dpToPx(), 20.dpToPx(), 16.dpToPx())
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .also { it.topMargin = 20.dpToPx(); it.bottomMargin = 12.dpToPx() }
            }
            revokedHeader.addView(TextView(uiContext).apply {
                text = "Usuarios sin acceso (revocados)"
                setTextColor(Color.parseColor("#FF6B6B"))
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            revokedHeader.addView(TextView(uiContext).apply {
                text = "Usuarios a los que se les quit\u00f3 el acceso. Puedes restaurarlo."
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 12f
                setPadding(0, 4.dpToPx(), 0, 0)
            })
            val revokedCountRow = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .also { it.topMargin = 10.dpToPx() }
            }
            revokedCountRow.addView(TextView(uiContext).apply {
                text = "${revokedFiltered.size} revocado${if (revokedFiltered.size != 1) "s" else ""}"
                setTextColor(Color.parseColor("#FF6B6B"))
                textSize = 12f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                val pillBg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 999f
                    setColor(Color.parseColor("#FF453A1A"))
                    setStroke(1.dpToPx(), Color.parseColor("#FF453A33"))
                }
                background = pillBg
                setPadding(12.dpToPx(), 4.dpToPx(), 12.dpToPx(), 4.dpToPx())
            })
            revokedHeader.addView(revokedCountRow)
            container.addView(revokedHeader)

            // Revoked user cards
            revokedFiltered.forEach { userData ->
                val revokedCard = LinearLayout(uiContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val cardBg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14f.dpToPxF()
                        setColor(Color.parseColor("#1C1C1E"))
                        setStroke(1.dpToPx(), Color.parseColor("#FF453A26"))
                    }
                    background = cardBg
                    setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 12.dpToPx())
                    layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                        .also { it.bottomMargin = 8.dpToPx() }
                    alpha = 0.75f
                }

                // Avatar
                val revokedAvatar = de.hdodenhof.circleimageview.CircleImageView(uiContext).apply {
                    layoutParams = LinearLayout.LayoutParams(40.dpToPx(), 40.dpToPx())
                        .also { it.marginEnd = 12.dpToPx() }
                    borderWidth = 1.dpToPx()
                    borderColor = Color.parseColor("#FF453A44")
                    setImageResource(R.drawable.placeholder_avatar)
                }
                if (!userData.avatar.isNullOrBlank()) {
                    Glide.with(this@AdminDashboardFragment).load(userData.avatar)
                        .placeholder(R.drawable.placeholder_avatar).into(revokedAvatar)
                }

                // Info
                val revokedInfo = LinearLayout(uiContext).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                }
                revokedInfo.addView(TextView(uiContext).apply {
                    text = userData.fullName ?: userData.username ?: "Usuario #${userData.userId}"
                    setTextColor(Color.parseColor("#A1A1AA"))
                    textSize = 14f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                revokedInfo.addView(TextView(uiContext).apply {
                    text = userData.courseName ?: "Curso #${userData.courseId}"
                    setTextColor(Color.parseColor("#71717A"))
                    textSize = 12f
                })
                revokedInfo.addView(TextView(uiContext).apply {
                    text = "\u26d4 Acceso revocado"
                    setTextColor(Color.parseColor("#FF6B6B"))
                    textSize = 10f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, 2.dpToPx(), 0, 0)
                })

                // Restore button
                val restoreBtn = android.widget.Button(uiContext).apply {
                    text = "\u21a9 Restaurar"
                    setTextColor(Color.parseColor("#34D399"))
                    isAllCaps = false
                    textSize = 12f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    val btnBg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 10f.dpToPxF()
                        setColor(Color.parseColor("#34D39914"))
                        setStroke(1.dpToPx(), Color.parseColor("#34D39930"))
                    }
                    background = btnBg
                    setPadding(14.dpToPx(), 8.dpToPx(), 14.dpToPx(), 8.dpToPx())
                    layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    minHeight = 0
                    minimumHeight = 0
                }
                restoreBtn.setOnClickListener {
                    restoreBtn.isEnabled = false
                    restoreBtn.text = "Restaurando..."
                    viewLifecycleOwner.lifecycleScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                BackendApiService.restoreEnrollment(userData.userId, userData.courseId)
                            }
                            approvedEnrollmentsList = approvedEnrollmentsList.map {
                                if (it.userId == userData.userId && it.courseId == userData.courseId)
                                    it.copy(status = "approved")
                                else it
                            }
                            revokedCard.animate().alpha(0f).setDuration(200).withEndAction {
                                renderFilteredApprovedEnrollments()
                            }.start()
                            Toast.makeText(uiContext, "Acceso restaurado \u2713", Toast.LENGTH_SHORT).show()
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("AdminDashboard", "Error restoring enrollment", e)
                            restoreBtn.isEnabled = true
                            restoreBtn.text = "\u21a9 Restaurar"
                            Toast.makeText(uiContext, "Error al restaurar acceso", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                revokedCard.addView(revokedAvatar)
                revokedCard.addView(revokedInfo)
                revokedCard.addView(restoreBtn)
                container.addView(revokedCard)
            }
        }
    }

    private fun showBlockSubjectDialog(userId: Long, courseId: Long, userName: String, subjects: List<SubjectInfo>, preSelectedId: Long = 0L) {
        val ctx = context ?: return
        var selectedSubjectId: Long = preSelectedId

        val dialogLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass_dark)
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
        }

        // Header with icon
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = 16.dpToPx() }
        }
        val iconWrap = FrameLayout(ctx).apply {
            val iconBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f.dpToPxF()
                setColor(Color.parseColor("#FF9F0A1F"))
            }
            background = iconBg
            layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
                .also { it.marginEnd = 14.dpToPx() }
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
        }
        iconWrap.addView(TextView(ctx).apply {
            text = "📚"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        })
        headerRow.addView(iconWrap)

        val headerText = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        headerText.addView(TextView(ctx).apply {
            text = "Quitar acceso a materia"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        headerText.addView(TextView(ctx).apply {
            text = "Se bloqueará el acceso de $userName a la materia seleccionada."
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            setPadding(0, 4.dpToPx(), 0, 0)
        })
        headerRow.addView(headerText)
        dialogLayout.addView(headerRow)

        // Section label: "Materia seleccionada"
        dialogLayout.addView(TextView(ctx).apply {
            text = "MATERIA SELECCIONADA"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(0, 0, 0, 8.dpToPx())
        })

        // Subject chip selector (scrollable)
        val chipsScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = 16.dpToPx() }
        }
        val chipsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 4.dpToPx(), 0, 4.dpToPx())
        }

        val chipViews = mutableMapOf<Long, LinearLayout>()

        fun updateChipSelection() {
            chipViews.forEach { (id, chipView) ->
                val isSelected = id == selectedSubjectId
                val bg = chipView.background as android.graphics.drawable.GradientDrawable
                if (isSelected) {
                    bg.setColor(Color.parseColor("#3D2800"))
                    bg.setStroke(2.dpToPx(), Color.parseColor("#FF9F0A"))
                    (chipView.getChildAt(0) as? TextView)?.setTextColor(Color.parseColor("#FF9F0A"))
                    if (chipView.childCount > 1) chipView.getChildAt(1).visibility = View.VISIBLE
                } else {
                    bg.setColor(Color.parseColor("#2A2A30"))
                    bg.setStroke(1.dpToPx(), Color.parseColor("#55555E"))
                    (chipView.getChildAt(0) as? TextView)?.setTextColor(Color.parseColor("#F0F0F5"))
                    if (chipView.childCount > 1) chipView.getChildAt(1).visibility = View.GONE
                }
            }
        }

        subjects.forEach { subj ->
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 20f.dpToPxF()
                    setColor(Color.parseColor("#2A2A30"))
                    setStroke(1.dpToPx(), Color.parseColor("#55555E"))
                }
                background = bg
                setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
                val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                lp.marginEnd = 8.dpToPx()
                layoutParams = lp
                isClickable = true
                isFocusable = true
            }

            chip.addView(TextView(ctx).apply {
                text = subj.name
                setTextColor(Color.parseColor("#F0F0F5"))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })

            // Checkmark icon (hidden by default)
            chip.addView(TextView(ctx).apply {
                text = " ✓"
                setTextColor(Color.parseColor("#FF9F0A"))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                visibility = View.GONE
            })

            chipViews[subj.id] = chip
            chipsContainer.addView(chip)
        }

        chipsScroll.addView(chipsContainer)
        dialogLayout.addView(chipsScroll)

        // Section label: "Razón del bloqueo"
        dialogLayout.addView(TextView(ctx).apply {
            text = "RAZÓN DEL BLOQUEO"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.05f
            setPadding(0, 0, 0, 6.dpToPx())
        })

        val reasonInput = android.widget.EditText(ctx).apply {
            hint = "Ej: No cumplió con los requisitos previos..."
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#636366"))
            val inputBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f.dpToPxF()
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(1.dpToPx(), Color.parseColor("#333336"))
            }
            background = inputBg
            setPadding(14.dpToPx(), 10.dpToPx(), 14.dpToPx(), 10.dpToPx())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        dialogLayout.addView(reasonInput)

        // Divisor
        dialogLayout.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).also {
                it.topMargin = 20.dpToPx()
                it.bottomMargin = 4.dpToPx()
            }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        })

        // Botones inline estilo liquid glass
        val rippleAttrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val ripple = rippleAttrs.getDrawable(0)
        rippleAttrs.recycle()

        val negBtn = TextView(ctx).apply {
            text = "Cancelar"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                .also { it.marginEnd = 8.dpToPx() }
            background = ripple
        }

        val posBtn = TextView(ctx).apply {
            text = "Quitar acceso"
            setTextColor(Color.parseColor("#FF9F0A"))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                .also { it.marginStart = 8.dpToPx() }
            isEnabled = false
            alpha = 0.45f
        }

        val btnsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        btnsRow.addView(negBtn)
        btnsRow.addView(posBtn)
        dialogLayout.addView(btnsRow)

        // Diálogo transparente para que bg_liquid_glass_dark sea visible
        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogLayout)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.92).toInt()
        dialog.window?.setLayout(dialogWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)

        fun updatePosBtnState() {
            val canSubmit = selectedSubjectId > 0 && reasonInput.text.toString().trim().isNotEmpty()
            posBtn.isEnabled = canSubmit
            posBtn.alpha = if (canSubmit) 1f else 0.45f
        }

        updateChipSelection()
        updatePosBtnState()

        chipViews.forEach { (id, chipView) ->
            chipView.setOnClickListener {
                selectedSubjectId = id
                updateChipSelection()
                updatePosBtnState()
            }
        }

        reasonInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updatePosBtnState() }
        })

        negBtn.setOnClickListener { dialog.dismiss() }

        posBtn.setOnClickListener {
            val reason = reasonInput.text.toString().trim()
            if (selectedSubjectId <= 0 || reason.isEmpty()) return@setOnClickListener
            posBtn.isEnabled = false
            posBtn.alpha = 0.45f
            posBtn.text = "Bloqueando..."
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        BackendApiService.blockSubjectAccess(userId, courseId, selectedSubjectId, reason)
                    }
                    Toast.makeText(ctx, "Acceso a materia bloqueado ✓", Toast.LENGTH_SHORT).show()
                    blockedSubjectKeys.add("${userId}-${courseId}-${selectedSubjectId}")
                    dialog.dismiss()
                    renderFilteredApprovedEnrollments()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error blocking subject access", e)
                    Toast.makeText(ctx, "Error al bloquear acceso", Toast.LENGTH_SHORT).show()
                    posBtn.isEnabled = true
                    posBtn.alpha = 1f
                    posBtn.text = "Quitar acceso"
                }
            }
        }

        dialog.show()
    }

    private fun showUnblockSubjectDialog(userId: Long, courseId: Long, subjectId: Long, userName: String, subjectName: String) {
        val ctx = context ?: return

        val dialogLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_liquid_glass_dark)
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
        }

        // Header with icon
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = 16.dpToPx() }
        }
        val iconWrap = FrameLayout(ctx).apply {
            val iconBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f.dpToPxF()
                setColor(Color.parseColor("#34D3991F"))
            }
            background = iconBg
            layoutParams = LinearLayout.LayoutParams(44.dpToPx(), 44.dpToPx())
                .also { it.marginEnd = 14.dpToPx() }
            setPadding(10.dpToPx(), 10.dpToPx(), 10.dpToPx(), 10.dpToPx())
        }
        iconWrap.addView(TextView(ctx).apply {
            text = "\uD83D\uDD13"
            textSize = 18f
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        })
        headerRow.addView(iconWrap)

        val headerText = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        headerText.addView(TextView(ctx).apply {
            text = "Restaurar acceso a materia"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        headerText.addView(TextView(ctx).apply {
            text = "\u00bfDesea restaurar el acceso de $userName a la materia \"$subjectName\"?"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = 13f
            setPadding(0, 4.dpToPx(), 0, 0)
        })
        headerRow.addView(headerText)
        dialogLayout.addView(headerRow)

        // Divider
        dialogLayout.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).also {
                it.topMargin = 8.dpToPx()
                it.bottomMargin = 4.dpToPx()
            }
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
        })

        // Buttons row
        val rippleAttrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val ripple = rippleAttrs.getDrawable(0)
        rippleAttrs.recycle()

        val negBtn = TextView(ctx).apply {
            text = "Cancelar"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                .also { it.marginEnd = 8.dpToPx() }
            background = ripple
        }

        val posBtn = TextView(ctx).apply {
            text = "Restaurar acceso"
            setTextColor(Color.parseColor("#34D399"))
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                .also { it.marginStart = 8.dpToPx() }
        }

        val btnsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        btnsRow.addView(negBtn)
        btnsRow.addView(posBtn)
        dialogLayout.addView(btnsRow)

        val dialog = android.app.Dialog(requireContext())
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogLayout)
        dialog.window?.setBackgroundDrawable(
            android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        )
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.92).toInt()
        dialog.window?.setLayout(dialogWidth, android.view.WindowManager.LayoutParams.WRAP_CONTENT)

        negBtn.setOnClickListener { dialog.dismiss() }

        posBtn.setOnClickListener {
            posBtn.isEnabled = false
            posBtn.alpha = 0.45f
            posBtn.text = "Restaurando..."
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        BackendApiService.unblockSubjectAccess(userId, subjectId)
                    }
                    Toast.makeText(ctx, "Acceso a materia restaurado \u2713", Toast.LENGTH_SHORT).show()
                    blockedSubjectKeys.remove("${userId}-${courseId}-${subjectId}")
                    dialog.dismiss()
                    renderFilteredApprovedEnrollments()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error unblocking subject access", e)
                    Toast.makeText(ctx, "Error al restaurar acceso", Toast.LENGTH_SHORT).show()
                    posBtn.isEnabled = true
                    posBtn.alpha = 1f
                    posBtn.text = "Restaurar acceso"
                }
            }
        }

        dialog.show()
    }

    private fun renderFilteredEnrollmentCards() {
        val container = enrollmentListContainer ?: return
        val uiContext = context ?: return
        container.removeAllViews()

        val uq = enrollmentUserFilter.lowercase()
        val cq = enrollmentCourseFilter.lowercase()
        val filtered = allEnrollmentCards.filter { data ->
            val matchUser = matchesFlexibleUserQuery(uq, data.fullName, data.username, data.documentId)
            val matchCourse = cq.isBlank()
                    || data.courseName.lowercase().contains(cq)
            matchUser && matchCourse
        }

        enrollmentPendingCountTv?.text = "${filtered.size}"

        if (filtered.isEmpty()) {
            container.addView(createEnrollmentEmptyStateView(uiContext))
            return
        }

        filtered.forEach { data ->
            container.addView(createEnrollmentCardView(uiContext, data))
        }
    }

    private fun createEnrollmentCardView(uiContext: android.content.Context, data: EnrollmentRequestCardData): View {
        val card = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            val cardBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f.dpToPxF()
                colors = intArrayOf(Color.parseColor("#1E1F25"), Color.parseColor("#18181B"))
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                setStroke(1.dpToPx(), Color.parseColor("#1F2937"))
            }
            background = cardBg
            setPadding(16.dpToPx(), 16.dpToPx(), 16.dpToPx(), 16.dpToPx())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.bottomMargin = 10.dpToPx() }
        }

        val topRow = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        val avatarView = de.hdodenhof.circleimageview.CircleImageView(uiContext).apply {
            layoutParams = LinearLayout.LayoutParams(50.dpToPx(), 50.dpToPx())
                .also { it.marginEnd = 14.dpToPx() }
            borderWidth = 1.dpToPx()
            borderColor = Color.parseColor("#334155")
            setImageResource(R.drawable.placeholder_avatar)
        }
        if (!data.avatarUrl.isNullOrBlank()) {
            Glide.with(this@AdminDashboardFragment).load(data.avatarUrl)
                .placeholder(R.drawable.placeholder_avatar).into(avatarView)
        }

        val infoCol = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }

        infoCol.addView(TextView(uiContext).apply {
            text = data.fullName ?: data.username
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        infoCol.addView(TextView(uiContext).apply {
            text = "@${data.username}"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 12f
            setPadding(0, 2.dpToPx(), 0, 0)
        })

        topRow.addView(avatarView)
        topRow.addView(infoCol)
        card.addView(topRow)

        val coursePanel = LinearLayout(uiContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.topMargin = 12.dpToPx() }
            val panelBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12f.dpToPxF()
                setColor(Color.parseColor("#121826"))
                setStroke(1.dpToPx(), Color.parseColor("#1E293B"))
            }
            background = panelBg
            setPadding(12.dpToPx(), 10.dpToPx(), 12.dpToPx(), 10.dpToPx())
        }
        coursePanel.addView(TextView(uiContext).apply {
            text = "CURSO SOLICITADO"
            setTextColor(Color.parseColor("#94A3B8"))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        coursePanel.addView(TextView(uiContext).apply {
            text = data.courseName
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, 4.dpToPx(), 0, 0)
        })
        card.addView(coursePanel)

        if (data.roleChips.isNotEmpty()) {
            val chipsRow = LinearLayout(uiContext).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                    .also { it.topMargin = 10.dpToPx() }
            }
            data.roleChips.forEachIndexed { index, chip ->
                chipsRow.addView(createEnrollmentRoleChip(uiContext, chip).apply {
                    val params = layoutParams as LinearLayout.LayoutParams
                    if (index < data.roleChips.lastIndex) {
                        params.marginEnd = 8.dpToPx()
                    }
                })
            }
            card.addView(chipsRow)
        }

        card.addView(TextView(uiContext).apply {
            text = data.requestedAt?.let(::formatEnrollmentTimeAgo) ?: "Fecha no disponible"
            setTextColor(Color.parseColor("#6B7280"))
            textSize = 11f
            setPadding(0, 10.dpToPx(), 0, 0)
        })

        val btnsRow = LinearLayout(uiContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                .also { it.topMargin = 12.dpToPx() }
        }

        val approveBtn = android.widget.Button(uiContext).apply {
            text = "✓ Aprobar"
            setTextColor(Color.parseColor("#30D158"))
            isAllCaps = false
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f.dpToPxF()
                setColor(Color.parseColor("#30D1581F"))
                setStroke(1.dpToPx(), Color.parseColor("#30D15833"))
            }
            background = btnBg
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .also { it.marginEnd = 8.dpToPx() }
            minHeight = 0
            minimumHeight = 0
        }

        val rejectBtn = android.widget.Button(uiContext).apply {
            text = "✕ Rechazar"
            setTextColor(Color.parseColor("#FF453A"))
            isAllCaps = false
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val btnBg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f.dpToPxF()
                setColor(Color.parseColor("#FF453A19"))
                setStroke(1.dpToPx(), Color.parseColor("#FF453A26"))
            }
            background = btnBg
            setPadding(16.dpToPx(), 8.dpToPx(), 16.dpToPx(), 8.dpToPx())
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            minHeight = 0
            minimumHeight = 0
        }

        val listContainer = enrollmentListContainer
        approveBtn.setOnClickListener {
            approveBtn.isEnabled = false
            rejectBtn.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        BackendApiService.approveEnrollment(data.userId, data.courseId)
                    }
                    com.example.tareamov.util.AppCache.invalidateCourses()
                    com.example.tareamov.util.AppCache.invalidateNotifications()
                    allEnrollmentCards = allEnrollmentCards.filter { it.userId != data.userId || it.courseId != data.courseId }
                    card.animate().alpha(0f).translationX(-card.width.toFloat()).setDuration(250).withEndAction {
                        renderFilteredEnrollmentCards()
                    }.start()
                    enrollmentLocalApproved++
                    enrollmentApprovedCountTv?.text = "$enrollmentLocalApproved"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error approving enrollment", e)
                    approveBtn.isEnabled = true
                    rejectBtn.isEnabled = true
                }
            }
        }

        rejectBtn.setOnClickListener {
            approveBtn.isEnabled = false
            rejectBtn.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        BackendApiService.rejectEnrollment(data.userId, data.courseId)
                    }
                    com.example.tareamov.util.AppCache.invalidateCourses()
                    com.example.tareamov.util.AppCache.invalidateNotifications()
                    allEnrollmentCards = allEnrollmentCards.filter { it.userId != data.userId || it.courseId != data.courseId }
                    card.animate().alpha(0f).translationX(card.width.toFloat()).setDuration(250).withEndAction {
                        renderFilteredEnrollmentCards()
                    }.start()
                    enrollmentLocalRejected++
                    enrollmentRejectedCountTv?.text = "$enrollmentLocalRejected"
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AdminDashboard", "Error rejecting enrollment", e)
                    approveBtn.isEnabled = true
                    rejectBtn.isEnabled = true
                }
            }
        }

        btnsRow.addView(approveBtn)
        btnsRow.addView(rejectBtn)
        card.addView(btnsRow)
        return card
    }

    private fun formatEnrollmentTimeAgo(isoDate: String): String {
        return try {
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val date = sdf.parse(isoDate) ?: return ""
            val diffMs = System.currentTimeMillis() - date.time
            val minutes = diffMs / 60000
            if (minutes < 1) return "Ahora mismo"
            if (minutes < 60) return "Hace ${minutes}m"
            val hours = minutes / 60
            if (hours < 24) return "Hace ${hours}h"
            val days = hours / 24
            "Hace ${days}d"
        } catch (_: Exception) { "" }
    }

    private fun JsonObject.getStringValue(vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            get(key)?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { value -> value.isNotBlank() }
        }
    }

    private fun JsonObject.getLongValue(vararg keys: String): Long? {
        return keys.firstNotNullOfOrNull { key ->
            get(key)?.takeIf { !it.isJsonNull }?.let { element ->
                runCatching {
                    when {
                        element.isJsonPrimitive && element.asJsonPrimitive.isNumber -> element.asLong
                        element.isJsonPrimitive -> element.asString.toLong()
                        else -> null
                    }
                }.getOrNull()
            }
        }
    }

    private fun JsonObject.extractEnrollmentRoleIds(): List<Long> {
        val ids = linkedSetOf<Long>()

        fun collect(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            when {
                element.isJsonArray -> element.asJsonArray.forEach { collect(it) }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    collect(obj.get("id"))
                    collect(obj.get("roleId"))
                    collect(obj.get("role_id"))
                    collect(obj.get("rolId"))
                    collect(obj.get("rol_id"))
                }
                element.isJsonPrimitive -> {
                    val parsed = runCatching {
                        if (element.asJsonPrimitive.isNumber) element.asLong else element.asString.toLong()
                    }.getOrNull()
                    if (parsed != null && parsed > 0L) {
                        ids += parsed
                    }
                }
            }
        }

        collect(get("roles"))
        collect(get("roleIds"))
        collect(get("role_ids"))
        collect(get("roleId"))
        collect(get("role_id"))
        return ids.toList()
    }

    private fun JsonObject.extractEnrollmentRoleLabels(): List<String> {
        val labels = linkedSetOf<String>()

        fun normalize(raw: String?): String? {
            val value = raw?.trim()?.lowercase(Locale.getDefault()) ?: return null
            if (value.isBlank()) return null
            return when {
                value.contains("admin") -> "Admin"
                value.contains("docente") || value.contains("teacher") || value.contains("profesor") -> "Docente"
                value.contains("estudiante") || value.contains("student") || value.contains("usuario") -> "Estudiante"
                else -> value.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
        }

        fun collect(element: JsonElement?) {
            if (element == null || element.isJsonNull) return
            when {
                element.isJsonArray -> element.asJsonArray.forEach { collect(it) }
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    normalize(obj.get("nombre")?.takeIf { !it.isJsonNull }?.asString)?.let(labels::add)
                    normalize(obj.get("name")?.takeIf { !it.isJsonNull }?.asString)?.let(labels::add)
                    normalize(obj.get("label")?.takeIf { !it.isJsonNull }?.asString)?.let(labels::add)
                }
                element.isJsonPrimitive && !element.asJsonPrimitive.isNumber -> {
                    normalize(element.asString)?.let(labels::add)
                }
            }
        }

        collect(get("roles"))
        return labels.toList()
    }

    private fun formatEnrollmentRoleName(raw: String): String {
        val normalized = raw.trim().lowercase(Locale.getDefault())
        return when {
            normalized.contains("admin") -> "Admin"
            normalized.contains("docente") || normalized.contains("teacher") || normalized.contains("profesor") -> "Docente"
            normalized.contains("estudiante") || normalized.contains("student") || normalized.contains("usuario") -> "Estudiante"
            else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
    }

    private fun buildEnrollmentRoleChips(
        roleIds: List<Long>,
        roleLabels: List<String>,
        roleNameById: Map<Long, String>
    ): List<EnrollmentRoleChipData> {
        val chips = linkedMapOf<String, EnrollmentRoleChipData>()

        roleIds.forEach { roleId ->
            val label = roleNameById[roleId] ?: when (roleId) {
                1L -> "Estudiante"
                2L -> "Docente"
                3L -> "Admin"
                else -> "Rol $roleId"
            }
            val tone = when (label) {
                "Admin" -> EnrollmentRoleTone.ADMIN
                "Docente" -> EnrollmentRoleTone.TEACHER
                "Estudiante" -> EnrollmentRoleTone.STUDENT
                else -> EnrollmentRoleTone.NEUTRAL
            }
            chips.putIfAbsent("id:$roleId", EnrollmentRoleChipData("id:$roleId", label, tone))
        }

        roleLabels.forEach { label ->
            val tone = when (label) {
                "Admin" -> EnrollmentRoleTone.ADMIN
                "Docente" -> EnrollmentRoleTone.TEACHER
                "Estudiante" -> EnrollmentRoleTone.STUDENT
                else -> EnrollmentRoleTone.NEUTRAL
            }
            val key = "label:${label.lowercase(Locale.getDefault())}"
            chips.putIfAbsent(key, EnrollmentRoleChipData(key, label, tone))
        }

        return if (chips.isNotEmpty()) {
            chips.values.toList()
        } else {
            listOf(EnrollmentRoleChipData("label:sin-rol", "Sin rol", EnrollmentRoleTone.NEUTRAL))
        }
    }

    private fun createEnrollmentRoleChip(context: android.content.Context, chip: EnrollmentRoleChipData): TextView {
        val (textColor, fillColor, strokeColor) = when (chip.tone) {
            EnrollmentRoleTone.STUDENT -> Triple("#F59E0B", "#F59E0B1F", "#F59E0B33")
            EnrollmentRoleTone.TEACHER -> Triple("#38BDF8", "#38BDF81F", "#38BDF833")
            EnrollmentRoleTone.ADMIN -> Triple("#A78BFA", "#A78BFA1F", "#A78BFA33")
            EnrollmentRoleTone.NEUTRAL -> Triple("#CBD5E1", "#CBD5E11A", "#CBD5E133")
        }

        return TextView(context).apply {
            text = chip.label
            setTextColor(Color.parseColor(textColor))
            textSize = 11f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val bg = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 999f
                setColor(Color.parseColor(fillColor))
                setStroke(1.dpToPx(), Color.parseColor(strokeColor))
            }
            background = bg
            setPadding(10.dpToPx(), 5.dpToPx(), 10.dpToPx(), 5.dpToPx())
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
    }

    private fun createEnrollmentEmptyStateView(context: android.content.Context): View {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 40.dpToPx(), 0, 40.dpToPx())
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)

            val iconBg = FrameLayout(context).apply {
                val bgShape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.parseColor("#30D1580A"))
                }
                background = bgShape
                layoutParams = LinearLayout.LayoutParams(80.dpToPx(), 80.dpToPx()).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    bottomMargin = 14.dpToPx()
                }
            }
            val checkIcon = ImageView(context).apply {
                setImageResource(android.R.drawable.checkbox_on_background)
                setColorFilter(Color.parseColor("#30D158"))
                layoutParams = FrameLayout.LayoutParams(40.dpToPx(), 40.dpToPx()).apply {
                    gravity = android.view.Gravity.CENTER
                }
            }
            iconBg.addView(checkIcon)
            addView(iconBg)
            addView(TextView(context).apply {
                text = "Todo en orden"
                setTextColor(Color.WHITE)
                textSize = 17f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "No hay solicitudes de matrícula\npendientes en este momento."
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = 13f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 6.dpToPx(), 0, 0)
            })
        }
    }

    private fun Float.dpToPxF(): Float = this * resources.displayMetrics.density

    // ==================== DATA CLASSES ====================
    
    data class EnrollmentRequestCardData(
        val userId: Long,
        val courseId: Long,
        val fullName: String?,
        val username: String,
        val courseName: String,
        val avatarUrl: String?,
        val requestedAt: String?,
        val roleChips: List<EnrollmentRoleChipData>,
        val payloadRoleIds: List<Long>,
        val payloadRoleLabels: List<String>,
        val documentId: String? = null
    )

    enum class EnrollmentRoleTone {
        STUDENT,
        TEACHER,
        ADMIN,
        NEUTRAL
    }

    data class EnrollmentRoleChipData(
        val key: String,
        val label: String,
        val tone: EnrollmentRoleTone
    )

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
