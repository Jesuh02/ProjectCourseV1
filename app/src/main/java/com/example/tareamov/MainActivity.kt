package com.example.tareamov
//
import android.content.res.Configuration
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.example.tareamov.viewmodel.AuthViewModel
import com.example.tareamov.viewmodel.PersonaViewModel
import com.example.tareamov.viewmodel.SupabaseViewModel
import com.example.tareamov.data.AppDatabase // Added
import com.example.tareamov.data.sync.SyncRepository // Added
import kotlinx.coroutines.launch


class MainActivity : AppCompatActivity() {
    lateinit var navController: NavController
    lateinit var personaViewModel: PersonaViewModel
    lateinit var authViewModel: AuthViewModel
    lateinit var syncRepository: SyncRepository // Added
    private lateinit var supabaseViewModel: SupabaseViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        val factory = com.example.tareamov.viewmodel.SupabaseViewModelFactory(usuarioDao, personaDao)

        // Obtén el ViewModel usando el factory
        val supabaseViewModel = androidx.lifecycle.ViewModelProvider(this, factory)
        .get(com.example.tareamov.viewmodel.SupabaseViewModel::class.java)


        // Initialize SyncRepository
        syncRepository = SyncRepository(
            usuarioDao,
            personaDao,
            topicDao,
            contentItemDao,
            taskDao,
            subscriptionDao,
            taskSubmissionDao,
            videoDao, // <-- Pasa el videoDao aquí
            rolDao,
            recursoDao,
            rolRecursoDao,
            appDb.chatMessageDao(),
            appDb.fileContextDao()
            // firestore eliminado, ya no se usa
        )

        // Initialize SyncRepository cache helpers
        try {
            syncRepository.initWithContext(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

    // Trigger an initial sync to Supabase if configured. In production, call this on connectivity changes
    // and avoid syncing large payloads on main startup. Credentials are loaded from
    // BuildConfig which reads `local.properties` in the Gradle script; keep that file out of VCS.
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


        personaViewModel = ViewModelProvider(this)[PersonaViewModel::class.java]
        authViewModel = ViewModelProvider(this)[AuthViewModel::class.java]

        // Observa el resultado del login
        supabaseViewModel.loginResult.observe(this) { token ->
            if (token != null) {
                // Login exitoso, puedes guardar el token o navegar a otra pantalla
                println("Login Supabase exitoso. Token: $token")
            } else {
                // Mostrar error de login
                println("Error en login Supabase")
            }
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

       val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        // Change start destination to splashFragment to show loading screen
        navGraph.setStartDestination(R.id.splashFragment)
        navController.graph = navGraph

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
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Log the configuration change instead of recreating the activity
        println("MainActivity: Configuration changed - Orientation: ${if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "Landscape" else "Portrait"}")
    }
}