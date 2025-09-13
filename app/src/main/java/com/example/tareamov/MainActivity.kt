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


          // Crea el factory
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
            videoDao // <-- Pasa el videoDao aquí
            // firestore eliminado, ya no se usa
        )


        // Initialize ViewModels
        personaViewModel = ViewModelProvider(this)[PersonaViewModel::class.java]
        authViewModel = ViewModelProvider(this)[AuthViewModel::class.java]

        // Ejemplo de uso del SupabaseViewModel para login
        // Puedes llamar a esta función cuando el usuario intente iniciar sesión
        // supabaseViewModel.loginConEmail("usuario@email.com", "password")

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

        // Set up Navigation - Ensure this is properly initialized
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        // Make sure the navigation graph is properly set
        // This is already done in XML, but we can set it programmatically to be sure
        val navGraph = navController.navInflater.inflate(R.navigation.nav_graph)
        // Change start destination to splashFragment to show loading screen
        navGraph.setStartDestination(R.id.splashFragment)
        navController.graph = navGraph

        // Enviar datos de todas las entidades a Firebase Firestore
        // The following block has been removed to implement a pending sync strategy.
        // You should implement a network connectivity listener or other trigger
        // to call SyncRepository.syncLocalToFirebase() when appropriate.
        /*
        runBlocking {
            val appDb = com.example.tareamov.data.AppDatabase.getDatabase(applicationContext)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Personas
                    val personas = appDb.personaDao().getAllPersonasList()
                    firestore.collection("personas").document("all").set(mapOf("data" to personas))

                    // Usuarios
                    val usuarios = appDb.usuarioDao().getAllUsuarios()
                    firestore.collection("usuarios").document("all").set(mapOf("data" to usuarios))

                    // Videos
                    val videos = appDb.videoDao().getAllVideos()
                    firestore.collection("videos").document("all").set(mapOf("data" to videos))

                    // Topics
                    val topics = appDb.topicDao().getAllTopics()
                    firestore.collection("topics").document("all").set(mapOf("data" to topics))

                    // ContentItems
                    val contentItems = appDb.contentItemDao().getAllContentItems()
                    firestore.collection("contentItems").document("all").set(mapOf("data" to contentItems))

                    // TaskSubmissions
                    val taskSubmissions = appDb.taskSubmissionDao().getAllTaskSubmissions()
                    firestore.collection("taskSubmissions").document("all").set(mapOf("data" to taskSubmissions))

                    // Subscriptions
                    val subscriptions = appDb.subscriptionDao().getAllSubscriptions()
                    firestore.collection("subscriptions").document("all").set(mapOf("data" to subscriptions))

                    // Tasks
                    val tasks = appDb.taskDao().getAllTasks()
                    firestore.collection("tasks").document("all").set(mapOf("data" to tasks))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        */
        //waos
        // Set up navigation listener to enforce flow rules
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