package com.example.tareamov
//
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.tareamov.viewmodel.AuthViewModel
import com.example.tareamov.viewmodel.PersonaViewModel
// import com.example.tareamov.viewmodel.SupabaseViewModel
import com.example.tareamov.data.AppDatabase // Added
import com.example.tareamov.data.sync.SyncRepository // Added
import kotlinx.coroutines.launch
import com.example.tareamov.service.SupabaseClient


class MainActivity : AppCompatActivity() {
    lateinit var navController: NavController
    lateinit var personaViewModel: PersonaViewModel
    lateinit var authViewModel: AuthViewModel
    lateinit var syncRepository: SyncRepository // Added

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        createNotificationChannel()

        // Set system bars to black
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK

        // Adjust icons to be light (visible on black background)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize Database and DAOs
        val appDb = AppDatabase.getDatabase(applicationContext)
        val usuarioDao = appDb.usuarioDao()
        val personaDao = appDb.personaDao()
        val topicDao = appDb.topicDao()
        val contentItemDao = appDb.contentItemDao()
        val taskDao = appDb.taskDao()
        val subscriptionDao = appDb.subscriptionDao()
        val taskSubmissionDao = appDb.taskSubmissionDao()
        val videoDao = appDb.videoDao() // <-- Agrega esto
        val rolDao = appDb.rolDao()
        val recursoDao = appDb.recursoDao()
        val rolRecursoDao = appDb.rolRecursoDao()

        // Initialize SyncRepository
        syncRepository = SyncRepository(
            usuarioDao,
            personaDao,
            topicDao,
            contentItemDao,
            taskDao,
            subscriptionDao,
            taskSubmissionDao,
            videoDao,
            appDb.courseDao(),
            rolDao,
            recursoDao,
            rolRecursoDao,
            appDb.chatMessageDao(),
            appDb.fileContextDao(),
            appDb.progresoEstudianteDao()
        )

        // Initialize SyncRepository cache helpers
        try {
            syncRepository.initWithContext(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Inject Supabase API key at runtime if possible so requests include the apikey header.
        try {
            // Prefer build-time value if present
            val bcKey = com.example.tareamov.BuildConfig.SUPABASE_KEY?.trim()
            if (!bcKey.isNullOrEmpty()) {
                SupabaseClient.setApiKeyAtRuntime(bcKey)
                println("MainActivity: injected Supabase API key from BuildConfig at runtime")
            } else {
                // Fallback: try to read an assets file named 'supabase_key.txt' (debug convenience)
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
                println("MainActivity: Supabase configured, starting initial syncLocalToSupabase()")
                syncRepository.syncLocalToSupabase()
                // Also pull remote data to local DB on startup
                try {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        syncRepository.syncSupabaseToLocal()
                    }
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
                
                // MIGRACIÓN DE PROGRESO DE ESTUDIANTES
                // Esta migración calcula y sincroniza el progreso histórico de todos los estudiantes
                // Solo se ejecuta si hay una preferencia para indicar que es necesario
                val prefs = getSharedPreferences("app_migration", MODE_PRIVATE)
                val progressMigrated = prefs.getBoolean("student_progress_migrated", false)
                if (!progressMigrated) {
                    println("MainActivity: Starting student progress migration...")
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val count = syncRepository.migrateAllStudentProgressToSupabase()
                            println("MainActivity: Student progress migration completed: $count records migrated")
                            // Marcar como completado
                            prefs.edit().putBoolean("student_progress_migrated", true).apply()
                        } catch (e: Exception) {
                            println("MainActivity: Error during student progress migration: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                } else {
                    println("MainActivity: Student progress already migrated, skipping")
                }
            } else {
                println("MainActivity: Supabase NOT configured (check local.properties). Skipping immediate sync.")
                // Helpful debug: print masked BuildConfig values so developer can verify props
                try {
                    val supUrl = com.example.tareamov.BuildConfig.SUPABASE_URL
                    val supKey = com.example.tareamov.BuildConfig.SUPABASE_KEY
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

        
        // 📱 NUEVO: Handle notification deep links to open specific fragments
        try {
            val openFragment = intent?.getStringExtra("openFragment")
            if (openFragment == "DatabaseQueryFragment") {
                // Navigate to DatabaseQueryFragment when notification is tapped
                navController.navigate(R.id.databaseQueryFragment)
                println("MainActivity: Opened DatabaseQueryFragment from notification")
            }
        } catch (t: Throwable) {
            println("MainActivity: Error opening fragment from notification: ${t.message}")
        }

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
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent) // Important: update the intent
        
        try {
            val path = intent.getStringExtra("floating_video_path")
            if (!path.isNullOrEmpty()) {
                showFloatingPlayer(path)
            }
            // handle navigation back to VideoHomeFragment requested by other activities
            val openHome = intent.getBooleanExtra("open_video_home", false)
            if (openHome) {
                try {
                    navController.navigate(R.id.videoHomeFragment)
                } catch (t: Throwable) { t.printStackTrace() }
            }
            
            // 📱 NUEVO: Handle notification deep links when app is already running
            val openFragment = intent.getStringExtra("openFragment")
            if (openFragment == "DatabaseQueryFragment") {
                try {
                    navController.navigate(R.id.databaseQueryFragment)
                    println("MainActivity: Opened DatabaseQueryFragment from notification (onNewIntent)")
                } catch (t: Throwable) { 
                    println("MainActivity: Error navigating from notification: ${t.message}")
                    t.printStackTrace() 
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }

    // Public API to show an in-app floating video player. Pass a valid video URI string (file://, http://, content://)
    fun showFloatingPlayer(videoUri: String) {
        try {
            val container = findViewById<android.view.ViewGroup>(R.id.floating_video_container)
            // make container visible
            container.visibility = android.view.View.VISIBLE
            // add fragment into container
            val frag = com.example.tareamov.ui.FloatingVideoPlayerFragment.newInstance(videoUri)
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
}