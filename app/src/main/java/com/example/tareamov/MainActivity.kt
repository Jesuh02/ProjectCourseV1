package com.example.tareamov
//
import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.tareamov.viewmodel.AuthViewModel
import com.example.tareamov.viewmodel.PersonaViewModel
// import com.example.tareamov.viewmodel.SupabaseViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.SupabaseClient
import com.example.tareamov.util.AppCache
import com.example.tareamov.util.SessionManager


class MainActivity : AppCompatActivity() {
    lateinit var navController: NavController
    lateinit var personaViewModel: PersonaViewModel
    lateinit var authViewModel: AuthViewModel

    private var billingPollJob: kotlinx.coroutines.Job? = null
    private var billingCountdownJob: kotlinx.coroutines.Job? = null

    var isFullScreenMode = false
        set(value) {
            field = value
            if (value) {
                // Full screen (Video): Transparent status and nav bars
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            } else {
                // Normal mode: Transparent status bar (shows black bg), Black nav bar
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.BLACK
            }
            ViewCompat.requestApplyInsets(findViewById(R.id.main))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        BackendApiService.initialize(applicationContext)
        AppCache.init()
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        syncFcmTokenIfNeeded()

        // Set system bars to transparent/black
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.BLACK

        // Adjust icons to be light (visible on black background)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (isFullScreenMode) {
                v.setPadding(0, 0, 0, 0)
            } else {
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            }
            insets
        }

        // Inject Supabase API key at runtime if possible so requests include the apikey header.
        try {
            // Prefer build-time value if present
            val bcKey = com.example.tareamov.BuildConfig.SUPABASE_ANON_KEY?.trim()
            if (!bcKey.isNullOrEmpty()) {
                SupabaseClient.setApiKeyAtRuntime(bcKey)
                println("MainActivity: injected Supabase API key from BuildConfig at runtime")
            } else {
                // Fallback: try to read an assets file named 'supabase_key.txt' (debug convenience)
                // DONE ASYNC to avoid I/O on main thread
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val am = assets
                        if (am != null) {
                            am.open("supabase_key.txt").bufferedReader().use { r ->
                                val txt = r.readText().trim()
                                if (txt.isNotEmpty()) {
                                    SupabaseClient.setApiKeyAtRuntime(txt)
                                    println("MainActivity: injected Supabase API key from assets/supabase_key.txt at runtime")
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        // ignore missing asset - developer can provide local.properties instead
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        // Log configured status after attempting to inject runtime key (mask presence for safety)
        try {
            val configured = com.example.tareamov.service.SupabaseClient.isConfigured()
            println("MainActivity: SupabaseClient.isConfigured()=$configured")
        } catch (t: Throwable) {
            // ignore
        }

        try {
            val configured = com.example.tareamov.service.SupabaseClient.isConfigured()
            if (configured) {
                println("MainActivity: Supabase configured")
            } else {
                println("MainActivity: Supabase NOT configured (check local.properties). Skipping immediate sync.")
                // Helpful debug: print masked BuildConfig values so developer can verify props
                try {
                    val supUrl = com.example.tareamov.BuildConfig.SUPABASE_URL
                    val supKey = com.example.tareamov.BuildConfig.SUPABASE_ANON_KEY
                    val hostIp = com.example.tareamov.BuildConfig.HOST_IP
                    val maskedUrl = if (supUrl.length > 20) supUrl.take(12) + "..." else supUrl
                    val maskedKey = if (supKey.length > 8) supKey.take(6) + "..." + supKey.takeLast(4) else "(hidden)"
                    println("BuildConfig SUPABASE_URL=$maskedUrl SUPABASE_KEY=$maskedKey HOST_IP=$hostIp")
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }


        // Initialize ViewModels
        personaViewModel = ViewModelProvider(this)[PersonaViewModel::class.java]
        authViewModel = ViewModelProvider(this)[AuthViewModel::class.java]

        // Set up Navigation - Ensure this is properly initialized
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Only setup the graph if we're not restoring state (e.g. from a theme change)
        if (savedInstanceState == null) {
            // Make sure the navigation graph is properly set
            // This is already done in XML, but we can set it programmatically to be sure
            val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
            // Change start destination to splashFragment to show loading screen
            navGraph.setStartDestination(R.id.splashFragment)
            navController.graph = navGraph

            // If the activity was launched with an intent asking to open VideoHomeFragment, navigate now
            try {
                val openHome = intent?.getBooleanExtra("open_video_home", false) ?: false
                if (openHome) {
                    navController.navigate(R.id.videoHomeFragment)
                }
            } catch (t: Throwable) {
                // ignore
            }
        }

        
        handlePushNotificationIntent(intent)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            // If we're coming from RegisterFragment and going to HomeFragment, redirect to LoginFragment
            if (destination.id == R.id.homeFragment) {
                val previousDestination = navController.previousBackStackEntry?.destination?.id
                if (previousDestination == R.id.registerFragment) {
                    // Navigate to LoginFragment instead
                    navController.navigate(R.id.action_registerFragment_to_loginFragment)
                }
            }

            // Add debug logging to track navigation
            println("Navigation: Navigated to ${destination.label}")
        }

        // Optional: prepare floating player container (hidden by default)
        // The floating player can be shown from anywhere via (activity as MainActivity).showFloatingPlayer(uri)

        // Start billing status check for role 3 users
        startBillingCheck()
    }

    // ═══════════════════════════════════════════════════════════
    // BILLING PAYMENT BANNER
    // ═══════════════════════════════════════════════════════════

    fun startBillingCheck() {
        val session = SessionManager.getInstance(applicationContext)
        // Only check for role 3 (admin institución) without role 4 (super admin)
        if (!session.hasRole(3) || session.hasRole(4)) return

        checkBillingStatusOnce()
        // Poll every 30 seconds
        billingPollJob?.cancel()
        billingPollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(30_000)
                checkBillingStatusOnce()
            }
        }
    }

    private var billingInstitutionId: Long? = null

    private fun checkBillingStatusOnce() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = BackendApiService.getMyBillingStatus()
                if (result is com.example.tareamov.service.ApiResult.Success) {
                    val status = result.data
                    billingInstitutionId = status.institutionId
                    SessionManager.getInstance(this@MainActivity).setInstitutionName(status.institutionName)
                    withContext(Dispatchers.Main) {
                        if (status.paymentOverdue) {
                            showBillingBanner(status.paymentDueDate)
                        } else if (status.paymentDueDate != null) {
                            // Schedule local countdown if due date is in near future
                            scheduleBillingCountdown(status.paymentDueDate)
                            hideBillingBanner()
                        } else {
                            hideBillingBanner()
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Billing check failed", e)
            }
        }
    }

    private fun scheduleBillingCountdown(dueDateIso: String) {
        billingCountdownJob?.cancel()
        try {
            val dueMs = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(dueDateIso.replace("Z", "").split(".")[0])?.time ?: return
            val diff = dueMs - System.currentTimeMillis()
            if (diff <= 0) {
                showBillingBanner(dueDateIso)
                return
            }
            if (diff <= 30 * 60_000) {
                billingCountdownJob = lifecycleScope.launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(diff)
                    withContext(Dispatchers.Main) {
                        showBillingBanner(dueDateIso)
                    }
                    // Re-check from server
                    checkBillingStatusOnce()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error scheduling billing countdown", e)
        }
    }

    private fun showBillingBanner(dueDate: String?) {
        try {
            val banner = findViewById<android.widget.LinearLayout>(R.id.billing_payment_banner) ?: return
            val textView = findViewById<android.widget.TextView>(R.id.billing_banner_text)
            val payBtn = findViewById<android.widget.TextView>(R.id.billing_banner_pay_btn)

            var label = "⚠️ Se ha vencido el plazo para pagar el servicio"
            if (dueDate != null) {
                try {
                    val d = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .parse(dueDate.replace("Z", "").split(".")[0])
                    if (d != null) {
                        val fmt = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("es"))
                        label += " — Venció: ${fmt.format(d)}"
                    }
                } catch (_: Exception) {}
            }
            textView?.text = label

            payBtn?.setOnClickListener {
                val instId = billingInstitutionId
                if (instId == null || instId <= 0L) {
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        intent.data = android.net.Uri.parse("https://coursev.com/contacto")
                        startActivity(intent)
                    } catch (_: Exception) {}
                    return@setOnClickListener
                }
                payBtn.isEnabled = false
                payBtn.text = "Procesando..."
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val result = BackendApiService.initiateBillingPayment(instId, 100000)
                        withContext(Dispatchers.Main) {
                            if (result is com.example.tareamov.service.ApiResult.Success) {
                                val data = result.data
                                val checkoutUrl = data.get("checkoutUrl")?.asString
                                if (!checkoutUrl.isNullOrBlank()) {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    intent.data = android.net.Uri.parse(checkoutUrl)
                                    startActivity(intent)
                                } else {
                                    android.widget.Toast.makeText(this@MainActivity, "No se pudo obtener la URL de pago", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                val err = (result as? com.example.tareamov.service.ApiResult.Error)?.message ?: "Error desconocido"
                                android.widget.Toast.makeText(this@MainActivity, "Error: $err", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            payBtn.isEnabled = true
                            payBtn.text = "Pagar"
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(this@MainActivity, "Error: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                            payBtn.isEnabled = true
                            payBtn.text = "Pagar"
                        }
                    }
                }
            }

            if (banner.visibility != android.view.View.VISIBLE) {
                banner.visibility = android.view.View.VISIBLE
                banner.alpha = 0f
                banner.animate().alpha(1f).setDuration(300).start()
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error showing billing banner", e)
        }
    }

    private fun hideBillingBanner() {
        try {
            val banner = findViewById<android.widget.LinearLayout>(R.id.billing_payment_banner)
            banner?.visibility = android.view.View.GONE
        } catch (_: Exception) {}
    }

    fun stopBillingCheck() {
        billingPollJob?.cancel()
        billingCountdownJob?.cancel()
    }

    override fun onDestroy() {
        stopBillingCheck()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Important: update the intent
        
        try {
            val path = intent.getStringExtra("floating_video_path")
            val position = intent.getIntExtra("video_position", 0)
            if (!path.isNullOrEmpty()) {
                showFloatingPlayer(path, position)
            }
            // handle navigation back to VideoHomeFragment requested by other activities
            val openHome = intent.getBooleanExtra("open_video_home", false)
            if (openHome) {
                try {
                    val bundle = Bundle()
                    if (position > 0) bundle.putInt("video_position", position)
                    val videoPath = intent.getStringExtra("video_path")
                    if (!videoPath.isNullOrEmpty()) bundle.putString("video_path", videoPath)
                    
                    navController.navigate(R.id.videoHomeFragment, bundle)
                } catch (t: Throwable) { t.printStackTrace() }
            }
            
            handlePushNotificationIntent(intent)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    private fun handlePushNotificationIntent(intent: Intent?) {
        if (intent == null) return

        val notificationType = intent.getStringExtra("notification_type")
        if (notificationType.isNullOrEmpty()) {
            val openFragment = intent.getStringExtra("openFragment")
            if (openFragment == "DatabaseQueryFragment") {
                try {
                    navController.navigate(R.id.databaseQueryFragment)
                } catch (t: Throwable) { t.printStackTrace() }
            }
            return
        }

        try {
            val bundle = Bundle()
            when (notificationType) {
                "new_video", "video_like" -> {
                    val videoId = intent.getLongExtra("video_id", -1L)
                        .takeIf { it > 0 } ?: intent.getLongExtra("related_id", -1L)
                    if (videoId > 0) {
                        bundle.putLong("videoId", videoId)
                        bundle.putBoolean("openComments", false)
                        navController.navigate(R.id.videoHomeFragment, bundle)
                    }
                }
                "new_course" -> {
                    val courseId = intent.getLongExtra("course_id", -1L)
                        .takeIf { it > 0 } ?: intent.getLongExtra("related_id", -1L)
                    if (courseId > 0) {
                        bundle.putLong("courseId", courseId)
                        navController.navigate(R.id.courseDetailFragment, bundle)
                    }
                }
                "new_task", "task_graded" -> {
                    val taskId = intent.getLongExtra("task_id", -1L)
                        .takeIf { it > 0 } ?: intent.getLongExtra("related_id", -1L)
                    val courseId = intent.getLongExtra("course_id", -1L)
                    if (courseId > 0) {
                        bundle.putLong("courseId", courseId)
                        if (taskId > 0) bundle.putLong("highlightTaskId", taskId)
                        navController.navigate(R.id.courseDetailFragment, bundle)
                    }
                }
                "task_submission" -> {
                    val taskId = intent.getLongExtra("task_id", -1L)
                        .takeIf { it > 0 } ?: intent.getLongExtra("related_id", -1L)
                    if (taskId > 0) {
                        bundle.putLong("taskId", taskId)
                        navController.navigate(R.id.taskSubmissionFragment, bundle)
                    }
                }
                "comment", "video_comment", "comment_reply", "comment_like" -> {
                    val videoId = intent.getLongExtra("video_id", -1L)
                        .takeIf { it > 0 } ?: intent.getLongExtra("related_id", -1L)
                    if (videoId > 0) {
                        bundle.putLong("videoId", videoId)
                        bundle.putBoolean("openComments", true)
                        navController.navigate(R.id.videoHomeFragment, bundle)
                    }
                }
                else -> {
                    navController.navigate(R.id.notificacionesFragment)
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    // Public API to show an in-app floating video player. Pass a valid video URI string (file://, http://, content://)
    fun showFloatingPlayer(videoUri: String, startPosition: Int = 0) {
        try {
            val container = findViewById<android.view.ViewGroup>(R.id.floating_video_container)
            // make container visible
            container.visibility = android.view.View.VISIBLE
            // add fragment into container
            val frag = com.example.tareamov.ui.FloatingVideoPlayerFragment.newInstance(videoUri, startPosition)
            val tx = supportFragmentManager.beginTransaction()
            tx.replace(R.id.floating_video_container, frag, "floating_player")
            tx.commitAllowingStateLoss()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    // Called by FloatingVideoPlayerFragment when closed to hide container
    fun onFloatingPlayerClosed() {
        try {
            val container = findViewById<android.view.ViewGroup>(R.id.floating_video_container)
            container.visibility = android.view.View.GONE
        } catch (t: Throwable) { /* ignore */ }
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Log the configuration change instead of recreating the activity
        println("MainActivity: Configuration changed - Orientation: ${if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "Landscape" else "Portrait"}")
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        
        // Check if we're currently on VideoHomeFragment
        val currentDestination = navController.currentDestination?.id
        if (currentDestination == R.id.videoHomeFragment) {
            // Enter PIP mode regardless of video playback state
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val rational = Rational(16, 9) // Standard video aspect ratio
                    val params = PictureInPictureParams.Builder()
                        .setAspectRatio(rational)
                        .build()
                    enterPictureInPictureMode(params)
                    println("MainActivity: Entered PIP mode from VideoHomeFragment")
                } catch (e: Exception) {
                    println("MainActivity: Failed to enter PIP mode: ${e.message}")
                }
            }
        }
    }

    /**
     * Handle PIP mode changes
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        if (isInPictureInPictureMode) {
            // Entered PIP mode - keep videos playing
            println("MainActivity: Now in PIP mode")
        } else {
            // Exited PIP mode - restore normal UI
            println("MainActivity: Exited PIP mode")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "DeepSeek Updates"
            val descriptionText = "Notificaciones de respuestas del asistente"
            val importance = android.app.NotificationManager.IMPORTANCE_HIGH
            val channel = android.app.NotificationChannel("deepseek_updates", name, importance).apply {
                description = descriptionText
            }
            val notificationManager: android.app.NotificationManager =
                getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
    }

    private fun syncFcmTokenIfNeeded() {
        if (!BackendApiService.isAuthenticated) return
        lifecycleScope.launch {
            BackendApiService.syncCurrentFcmToken()
        }
    }
}