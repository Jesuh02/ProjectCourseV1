package com.example.tareamov.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.data.entity.Rol
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.util.SessionManager
import com.example.tareamov.viewmodel.AuthViewModel
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.FirebaseApp
import com.example.tareamov.service.SupabaseClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LoginFragment : Fragment() {
    private lateinit var usernameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var registerButton: Button
    private lateinit var authViewModel: AuthViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var courseTitleText: TextView
    private lateinit var profileIcon: ImageView
    private lateinit var codeIcon: ImageView
    private lateinit var particle1: View
    private lateinit var particle2: View
    private lateinit var particle3: View
    
    // Google Sign-In (Legacy API - more compatible)
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var googleSignInButton: View
    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>
    
    companion object {
        private const val TAG = "LoginFragment"
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize Google Sign-In launcher
        googleSignInLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.d(TAG, "Google Sign-In result: resultCode=${result.resultCode}, data=${result.data}")
            
            when (result.resultCode) {
                Activity.RESULT_OK -> {
                    val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    handleGoogleSignInResult(task)
                }
                Activity.RESULT_CANCELED -> {
                    // Try to get more info from the intent
                    val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    try {
                        task.getResult(ApiException::class.java)
                    } catch (e: ApiException) {
                        Log.e(TAG, "Google Sign-In ApiException: statusCode=${e.statusCode}, message=${e.message}")
                        handleGoogleSignInError(e.statusCode)
                    }
                }
                else -> {
                    Log.e(TAG, "Google Sign-In failed with unknown resultCode: ${result.resultCode}")
                    // Still try to parse the result
                    if (result.data != null) {
                        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                        handleGoogleSignInResult(task)
                    } else {
                        Toast.makeText(requireContext(), "Error al iniciar sesión con Google", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
    
    private fun handleGoogleSignInError(statusCode: Int) {
        val errorMessage = when (statusCode) {
            10 -> "Error de configuración (código 10). El SHA-1 o Client ID no coinciden."
            12500 -> "Error de Google Play Services. Actualiza Google Play Services."
            12501 -> "Inicio de sesión cancelado por el usuario"
            12502 -> {
                Log.w(TAG, "Network error 12502 - attempting local account creation")
                // For network errors, we can still try to proceed with cached account info
                "Error de red temporal. Intentando crear cuenta local..."
            }
            7 -> "Error de conexión de red"
            8 -> "Error interno de Google"
            else -> "Error de Google Sign-In (código: $statusCode)"
        }
        Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
        
        // For network errors during sign-in, the account might still be available locally
        if (statusCode == 12502) {
            try {
                val lastSignedInAccount = GoogleSignIn.getLastSignedInAccount(requireContext())
                if (lastSignedInAccount != null && lastSignedInAccount.email != null) {
                    Log.d(TAG, "Found cached Google account: ${lastSignedInAccount.email}")
                    lifecycleScope.launch {
                        createOrLoginLocalUser(
                            lastSignedInAccount.email!!,
                            lastSignedInAccount.displayName ?: lastSignedInAccount.email!!.substringBefore("@"),
                            lastSignedInAccount.photoUrl?.toString()
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recovering from network issue", e)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_login, container, false)

        usernameEditText = view.findViewById(R.id.usernameEditText)
        passwordEditText = view.findViewById(R.id.passwordEditText)
        loginButton = view.findViewById(R.id.loginButton)
        registerButton = view.findViewById(R.id.registerButton)
        //goToRegisterPersonaTextView = view.findViewById(R.idgoToRegisterPersonaTextView)
        
        // Initialize new views
        courseTitleText = view.findViewById(R.id.courseTitleText)
        profileIcon = view.findViewById(R.id.profileIcon)
        codeIcon = view.findViewById(R.id.codeIcon)
        particle1 = view.findViewById(R.id.particle1)
        particle2 = view.findViewById(R.id.particle2)
        particle3 = view.findViewById(R.id.particle3)
        
        // Initialize Google Sign-In button
        googleSignInButton = view.findViewById(R.id.googleLoginButton)
        
        // Configure Google Sign-In
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .requestProfile()
            .build()
        
        googleSignInClient = GoogleSignIn.getClient(requireActivity(), gso)

        // Initialize SessionManager
        sessionManager = SessionManager.getInstance(requireContext())

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
    
        // Initialize ViewModel
        authViewModel = ViewModelProvider(this)[AuthViewModel::class.java]
        
        // Start animations
        startAnimations()
        
        // Set up Google Sign-In button
        googleSignInButton.setOnClickListener {
            signInWithGoogle()
        }
    
        // Set up click listener for the register button
        registerButton.setOnClickListener {
            // If the user didn't type a desired username, navigate directly to RegisterFragment.
            // If they did type a username, keep the existing remote/local availability checks.
            val desiredUsername = usernameEditText.text.toString().trim()
            if (desiredUsername.isEmpty()) {
                findNavController().navigate(R.id.registerFragment)
                return@setOnClickListener
            }

            lifecycleScope.launch {
                var existsOnSupabase = false
                try {
                    val db = AppDatabase.getDatabase(requireContext())
                    val syncRepo = com.example.tareamov.data.sync.SyncRepository(
                        db.usuarioDao(), db.personaDao(), db.topicDao(), db.contentItemDao(), db.taskDao(),
                        db.subscriptionDao(), db.taskSubmissionDao(), db.videoDao(), db.courseDao(), db.rolDao(),
                        db.recursoDao(), db.rolRecursoDao(), db.chatMessageDao(), db.fileContextDao(), db.progresoEstudianteDao()
                    )

                    existsOnSupabase = withContext(Dispatchers.IO) {
                        try {
                            syncRepo.isUsuarioExistsInSupabase(desiredUsername)
                        } catch (e: Exception) {
                            false
                        }
                    }
                } catch (e: Exception) {
                    existsOnSupabase = false
                }

                if (existsOnSupabase) {
                    Toast.makeText(requireContext(), "El usuario ya existe en Supabase. Por favor elija otro usuario.", Toast.LENGTH_SHORT).show()
                } else {
                    // Double-check local DB just in case
                    val db = AppDatabase.getDatabase(requireContext())
                    val localExists = withContext(Dispatchers.IO) {
                        db.usuarioDao().getUsuarioByUsername(desiredUsername) != null
                    }
                    if (localExists) {
                        Toast.makeText(requireContext(), "El usuario ya existe localmente. Por favor elija otro usuario.", Toast.LENGTH_SHORT).show()
                    } else {
                        findNavController().navigate(R.id.registerFragment)
                    }
                }
            }
        }

        // Observe login result
        authViewModel.loginResult.observe(viewLifecycleOwner) { result ->
            if (result.success) {
                // The session is now correctly created by AuthViewModel, including the avatar.
                // Remove the redundant and incorrect session creation logic from here.

                // Store userId in SharedPreferences for ProfileFragment (if still needed,
                // SessionManager.getUserId() could also be used in ProfileFragment)
                val userId = result.userId ?: -1L
                if (userId != -1L) {
                    val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    sharedPrefs.edit().putLong("current_user_id", userId).apply()
                    
                    // Register FCM Token safely
                    try {
                        if (FirebaseApp.getApps(requireContext()).isNotEmpty()) {
                            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                                if (!task.isSuccessful) {
                                    Log.w("LoginFragment", "Fetching FCM registration token failed", task.exception)
                                    return@addOnCompleteListener
                                }
                                val token = task.result
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        SupabaseClient.registerFcmToken(userId, token)
                                        Log.d("LoginFragment", "FCM Token registered for user $userId")
                                    } catch (e: Exception) {
                                        Log.e("LoginFragment", "Error registering FCM token", e)
                                    }
                                }
                            }
                        } else {
                            Log.w("LoginFragment", "FirebaseApp is not initialized. Missing google-services.json? Skipping FCM.")
                        }
                    } catch (e: Exception) {
                        Log.e("LoginFragment", "Error accessing FirebaseMessaging", e)
                    }
                }

                findNavController().navigate(R.id.videoHomeFragment)
            } else {
                Toast.makeText(requireContext(), "Usuario o contraseña incorrectos", Toast.LENGTH_SHORT).show()
            }
        }

        // Set up login button click listener
        loginButton.setOnClickListener {
            val username = usernameEditText.text.toString().trim()
            val password = passwordEditText.text.toString()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(requireContext(), "Por favor ingrese usuario y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // First try to check Supabase for the user; fallback to local DB if not configured or not found
            lifecycleScope.launch {
                var foundOnSupabase = false

                fun maskSecret(s: String?): String {
                    if (s == null) return "null"
                    val len = s.length
                    return when {
                        len <= 2 -> "*".repeat(len)
                        else -> s.first() + "*".repeat(len - 2) + s.last()
                    }
                }

                android.util.Log.d("LoginFragment", "Login attempt for user=$username password_mask=${maskSecret(password)}")

                try {
                    val db = AppDatabase.getDatabase(requireContext())
                    val syncRepo = com.example.tareamov.data.sync.SyncRepository(
                        db.usuarioDao(), db.personaDao(), db.topicDao(), db.contentItemDao(), db.taskDao(),
                        db.subscriptionDao(), db.taskSubmissionDao(), db.videoDao(), db.courseDao(), db.rolDao(),
                        db.recursoDao(), db.rolRecursoDao(), db.chatMessageDao(), db.fileContextDao(), db.progresoEstudianteDao()
                    )

                    // Attempt to fetch remote user (this will try even if isConfigured() previously returned false)
                    val remoteUserWithRole = withContext(Dispatchers.IO) {
                        try {
                            syncRepo.fetchUsuarioWithRoleFromSupabase(username)
                        } catch (e: Exception) {
                            android.util.Log.w("LoginFragment", "Remote user fetch failed: ${e.message}")
                            null
                        }
                    }

                    foundOnSupabase = remoteUserWithRole != null
                    android.util.Log.d("LoginFragment", "Supabase existence check for user=$username -> $foundOnSupabase")
                } catch (e: Exception) {
                    android.util.Log.w("LoginFragment", "Error preparing SyncRepository: ${e.message}")
                    foundOnSupabase = false
                }

                if (foundOnSupabase) {
                    // Proceed to login; AuthViewModel should handle remote/local credential verification.
                    authViewModel.login(username, password)
                } else {
                    // Fallback to local DB check
                    val usuarioWithRole = withContext(Dispatchers.IO) {
                        try {
                            authViewModel.getUsuarioWithRoleByUsername(username)
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (usuarioWithRole == null) {
                        android.util.Log.d("LoginFragment", "Local user not found for username=$username")
                        Toast.makeText(requireContext(), "Usuario no encontrado", Toast.LENGTH_SHORT).show()
                    } else {
                        // Proceed to login (AuthViewModel will verify password/hash)
                        authViewModel.login(username, password)
                    }
                }
            }
        }

        // Añade este código en el método onViewCreated:
        val togglePasswordVisibility = view.findViewById<ImageView>(R.id.togglePasswordVisibility)
        togglePasswordVisibility.setOnClickListener {
            if (passwordEditText.transformationMethod is PasswordTransformationMethod) {
                // Mostrar contraseña
                passwordEditText.transformationMethod = HideReturnsTransformationMethod.getInstance()
                togglePasswordVisibility.setImageResource(R.drawable.ic_visibility_off)
            } else {
                // Ocultar contraseña
                passwordEditText.transformationMethod = PasswordTransformationMethod.getInstance()
                togglePasswordVisibility.setImageResource(R.drawable.ic_visibility)
            }
            // Mover el cursor al final del texto
            passwordEditText.setSelection(passwordEditText.text.length)
        }
    }
    
    private fun startAnimations() {
        // Animación del título con efecto de aparición
        val titleFadeIn = ObjectAnimator.ofFloat(courseTitleText, "alpha", 0f, 1f)
        titleFadeIn.duration = 1500
        
        val titleSlide = ObjectAnimator.ofFloat(courseTitleText, "translationY", -50f, 0f)
        titleSlide.duration = 1500
        
        val titleAnimatorSet = AnimatorSet()
        titleAnimatorSet.playTogether(titleFadeIn, titleSlide)
        titleAnimatorSet.interpolator = AccelerateDecelerateInterpolator()
        titleAnimatorSet.start()
        
        // Animación del icono de código con rotación sutil
        val codeIconRotation = ObjectAnimator.ofFloat(codeIcon, "rotation", 0f, 360f)
        codeIconRotation.duration = 10000
        codeIconRotation.repeatCount = ObjectAnimator.INFINITE
        codeIconRotation.start()
        
        // Efecto de pulso en el icono de perfil
        startGlowEffect()
        
        // Animaciones de partículas flotantes
        startParticleAnimations()
        
        // Animación de entrada de la tarjeta de login
        val loginCard = view?.findViewById<View>(R.id.loginCard)
        loginCard?.let { card ->
            val slideUpAnimation = AnimationUtils.loadAnimation(context, R.anim.slide_up_login)
            card.startAnimation(slideUpAnimation)
        }
    }
    
    private fun startGlowEffect() {
        val scaleX = ObjectAnimator.ofFloat(profileIcon, "scaleX", 1f, 1.1f, 1f)
        val scaleY = ObjectAnimator.ofFloat(profileIcon, "scaleY", 1f, 1.1f, 1f)
        val alpha = ObjectAnimator.ofFloat(profileIcon, "alpha", 0.8f, 1f, 0.8f)
        
        scaleX.duration = 2000
        scaleX.repeatCount = ObjectAnimator.INFINITE
        scaleX.repeatMode = ObjectAnimator.REVERSE
        
        scaleY.duration = 2000
        scaleY.repeatCount = ObjectAnimator.INFINITE
        scaleY.repeatMode = ObjectAnimator.REVERSE
        
        alpha.duration = 2000
        alpha.repeatCount = ObjectAnimator.INFINITE
        alpha.repeatMode = ObjectAnimator.REVERSE
        
        val glowSet = AnimatorSet()
        glowSet.playTogether(scaleX, scaleY, alpha)
        glowSet.interpolator = AccelerateDecelerateInterpolator()
        glowSet.start()
    }
    
    private fun startParticleAnimations() {
        // Animación de partícula 1
        val particle1Float = ObjectAnimator.ofFloat(particle1, "translationY", 0f, -40f, 0f)
        particle1Float.duration = 6000
        particle1Float.repeatCount = ObjectAnimator.INFINITE
        particle1Float.start()
        
        // Animación de partícula 2
        val particle2Float = ObjectAnimator.ofFloat(particle2, "translationY", 0f, -30f, 0f)
        particle2Float.duration = 8000
        particle2Float.repeatCount = ObjectAnimator.INFINITE
        particle2Float.startDelay = 2000L
        particle2Float.start()
        
        // Animación de partícula 3
        val particle3Float = ObjectAnimator.ofFloat(particle3, "translationY", 0f, -50f, 0f)
        particle3Float.duration = 7000
        particle3Float.repeatCount = ObjectAnimator.INFINITE
        particle3Float.startDelay = 4000L
        particle3Float.start()
        
        // Efecto de fade in/out para las partículas
        listOf(particle1, particle2, particle3).forEachIndexed { index, particle ->
            val alphaAnimation = ObjectAnimator.ofFloat(particle, "alpha", 0.3f, 0.8f, 0.3f)
            alphaAnimation.duration = (4000 + (index * 1000)).toLong()
            alphaAnimation.repeatCount = ObjectAnimator.INFINITE
            alphaAnimation.startDelay = (index * 1500).toLong()
            alphaAnimation.start()
        }
    }

    private fun checkExistingUser() {
        lifecycleScope.launch {
            val db = AppDatabase.getDatabase(requireContext())
            val userCount = withContext(Dispatchers.IO) {
                db.usuarioDao().getUserCount()
            }

            // If at least one user exists, navigate directly to home screen
            // This navigation logic might need review based on app flow,
            // for now, focusing on the password hashing.
            if (userCount > 0) {
                // Consider if this navigation is always desired or if it should
                // depend on whether a user is already logged in via SessionManager.
                // findNavController().navigate(R.id.action_loginFragment_to_homeFragment)
            }
        }
    }
    
    // ==================== GOOGLE SIGN-IN (Legacy API) ====================
    
    private fun signInWithGoogle() {
        Log.d(TAG, "Starting Google Sign-In...")
        Log.d(TAG, "Web Client ID: ${getString(R.string.default_web_client_id).take(30)}...")
        
        // Check if Google Play Services is available
        val googleApiAvailability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
        val resultCode = googleApiAvailability.isGooglePlayServicesAvailable(requireContext())
        
        if (resultCode != com.google.android.gms.common.ConnectionResult.SUCCESS) {
            Log.e(TAG, "Google Play Services not available: $resultCode")
            if (googleApiAvailability.isUserResolvableError(resultCode)) {
                googleApiAvailability.getErrorDialog(requireActivity(), resultCode, 9000)?.show()
            } else {
                Toast.makeText(requireContext(), "Google Play Services no disponible", Toast.LENGTH_LONG).show()
            }
            return
        }
        
        // Sign out first to allow account selection, then sign in
        googleSignInClient.signOut().addOnCompleteListener {
            Log.d(TAG, "Signed out, now launching sign in intent...")
            try {
                val signInIntent = googleSignInClient.signInIntent
                googleSignInLauncher.launch(signInIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Error launching sign in intent", e)
                Toast.makeText(requireContext(), "Error al iniciar Google Sign-In: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun handleGoogleSignInResult(task: Task<GoogleSignInAccount>) {
        try {
            val account = task.getResult(ApiException::class.java)
            
            val email = account.email ?: ""
            val displayName = account.displayName ?: email.substringBefore("@")
            val profilePictureUri = account.photoUrl?.toString()
            
            Log.d(TAG, "Google Sign-In successful for: $email")
            
            // Separar nombre y apellido del displayName
            val nameParts = displayName.split(" ", limit = 2)
            val nombres = nameParts.getOrElse(0) { email.substringBefore("@") }
            val apellidos = nameParts.getOrElse(1) { "" }
            
            // Verificar si el usuario ya existe en Supabase antes de redirigir al registro
            lifecycleScope.launch {
                try {
                    // Buscar usuario por email en Supabase (ahora el email está en usuarios)
                    val existingUser = withContext(Dispatchers.IO) {
                        com.example.tareamov.service.SupabaseClient.fetchUsuarios()
                            .find { it.email.equals(email, ignoreCase = true) }
                    }
                    
                    if (existingUser != null) {
                        // Usuario ya existe en Supabase - iniciar sesión directamente
                        Log.d(TAG, "Usuario encontrado en Supabase: ${existingUser.usuario}")
                        // Need to fetch persona to pass to loginExistingGoogleUser?
                        // loginExistingGoogleUser signature: (user, persona, displayName, avatarUrl)
                        // We need the persona.
                        val persona = withContext(Dispatchers.IO) {
                            com.example.tareamov.service.SupabaseClient.fetchPersonas()
                                .find { it.id == existingUser.persona_id }
                        }
                        
                        if (persona != null) {
                            loginExistingGoogleUser(existingUser, persona, displayName, profilePictureUri)
                        } else {
                            Log.e(TAG, "Usuario encontrado pero sin persona asociada")
                            // Fallback or error?
                            // Maybe try to login just with user info if possible, but loginExistingGoogleUser needs persona.
                            // Let's try to create a dummy persona or handle error.
                            Toast.makeText(requireContext(), "Error: Usuario sin datos personales", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        // Usuario no existe - redirigir al formulario de registro
                        Log.d(TAG, "Usuario no encontrado en Supabase, redirigiendo a registro")
                        navigateToRegisterWithGoogleData(email, nombres, apellidos, profilePictureUri)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error verificando usuario en Supabase: ${e.message}")
                    // En caso de error de conexión, intentar verificar localmente
                    val localUser = withContext(Dispatchers.IO) {
                        AppDatabase.getDatabase(requireContext()).usuarioDao().getUsuarioByEmail(email)
                    }
                    
                    if (localUser != null) {
                        Log.d(TAG, "Usuario encontrado localmente: ${localUser.usuario}")
                        loginExistingGoogleUserByEmail(localUser, displayName, profilePictureUri)
                    } else {
                        // No se pudo verificar, redirigir al registro
                        navigateToRegisterWithGoogleData(email, nombres, apellidos, profilePictureUri)
                    }
                }
            }
            
        } catch (e: ApiException) {
            Log.e(TAG, "Google Sign-In failed with code: ${e.statusCode}", e)
            val errorMessage = when (e.statusCode) {
                12501 -> "Inicio de sesión cancelado"
                12502 -> "Error de conexión. Verifica tu internet."
                10 -> "Error de configuración. Verifica el SHA-1 y Client ID."
                7 -> "Error de red. Verifica tu conexión a internet."
                else -> "Error de Google Sign-In: ${e.statusCode}"
            }
            Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
        }
    }
    
    private fun authenticateWithSupabase(idToken: String, email: String, displayName: String, avatarUrl: String?) {
        lifecycleScope.launch {
            try {
                val supabaseUrl = com.example.tareamov.BuildConfig.SUPABASE_URL
                val supabaseKey = com.example.tareamov.BuildConfig.SUPABASE_KEY
                
                if (supabaseUrl.isBlank() || supabaseKey.isBlank()) {
                    Log.w(TAG, "Supabase not configured, creating local account")
                    createOrLoginLocalUser(email, displayName, avatarUrl)
                    return@launch
                }
                
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                
                // Call Supabase auth endpoint with Google ID token
                val jsonBody = JSONObject().apply {
                    put("provider", "google")
                    put("id_token", idToken)
                }
                
                val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())
                
                val request = Request.Builder()
                    .url("$supabaseUrl/auth/v1/token?grant_type=id_token")
                    .addHeader("apikey", supabaseKey)
                    .addHeader("Content-Type", "application/json")
                    .post(requestBody)
                    .build()
                
                val response = withContext(Dispatchers.IO) {
                    client.newCall(request).execute()
                }
                
                val responseBody = response.body?.string()
                
                if (response.isSuccessful && responseBody != null) {
                    val jsonResponse = JSONObject(responseBody)
                    Log.d(TAG, "Supabase auth successful")
                    
                    // Extract user info from Supabase response
                    val user = jsonResponse.optJSONObject("user")
                    val supabaseUserId = user?.optString("id") ?: ""
                    
                    // Create or update local user and session
                    createOrLoginLocalUser(email, displayName, avatarUrl, supabaseUserId)
                    
                } else {
                    Log.e(TAG, "Supabase auth failed: ${response.code} - $responseBody")
                    // Fallback to local account
                    createOrLoginLocalUser(email, displayName, avatarUrl)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error authenticating with Supabase", e)
                // Fallback to local account
                createOrLoginLocalUser(email, displayName, avatarUrl)
            }
        }
    }
    
    private suspend fun createOrLoginLocalUser(email: String, displayName: String, avatarUrl: String?, supabaseUserId: String? = null) {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val usuarioDao = db.usuarioDao()
                val rolDao = db.rolDao()
                val personaDao = db.personaDao()
                
                // Check if user already exists by email
                var existingUser = usuarioDao.getUsuarioByEmail(email)
                
                if (existingUser == null) {
                    // Ensure default roles exist
                    var usuarioRole = rolDao.getRolByNombre("usuario")
                    if (usuarioRole == null) {
                        // Initialize roles if they don't exist
                        val usuarioRoleId = rolDao.insertRol(Rol.createUsuarioRole())
                        val adminRoleId = rolDao.insertRol(Rol.createAdminRole())
                        usuarioRole = rolDao.getRolById(usuarioRoleId)
                        Log.d(TAG, "Initialized default roles: usuario=$usuarioRoleId, admin=$adminRoleId")
                    }
                    
                    if (usuarioRole == null) {
                        throw Exception("Failed to initialize default roles")
                    }
                    
                    // Create Persona record first (required for foreign key constraint)
                    val nameParts = displayName.split(" ", limit = 2)
                    val nombres = nameParts.getOrElse(0) { email.substringBefore("@") }
                    val apellidos = nameParts.getOrElse(1) { "" }
                    
                    val newPersona = com.example.tareamov.data.entity.Persona(
                        identificacion = "",
                        nombres = nombres,
                        apellidos = apellidos,
                        telefono = "",
                        direccion = "",
                        fechaNacimiento = ""
                    )
                    
                    // Insert persona locally first
                    val personaId = personaDao.insertPersona(newPersona)
                    Log.d(TAG, "Created local persona with ID: $personaId")
                    
                    // Try to sync persona to Supabase
                    var supabasePersonaId: Long? = null
                    try {
                        val personaWithId = newPersona.copy(id = personaId)
                        supabasePersonaId = com.example.tareamov.service.SupabaseClient.insertPersona(personaWithId)
                        Log.d(TAG, "Synced persona to Supabase with ID: $supabasePersonaId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to sync persona to Supabase: ${e.message}")
                    }
                    
                    // Create new user with valid persona_id
                    val username = email.substringBefore("@")
                    val newUser = Usuario(
                        usuario = username,
                        email = email,
                        contrasena = "", // No password for Google users
                        avatar = avatarUrl,
                        persona_id = personaId // Use the created persona ID
                    )
                    
                    // Insert user locally
                    val userId = usuarioDao.insertUsuario(newUser)
                    usuarioDao.updateUserRolId(userId, usuarioRole.id)
                    existingUser = usuarioDao.getUsuarioById(userId)
                    Log.d(TAG, "Created local user: $username with ID: $userId")
                    
                    // Try to sync user to Supabase
                    try {
                        val userWithId = newUser.copy(id = userId)
                        val supabaseUserId = com.example.tareamov.service.SupabaseClient.insertUsuario(userWithId)
                        Log.d(TAG, "Synced user to Supabase with ID: $supabaseUserId")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to sync user to Supabase: ${e.message}")
                    }
                    
                } else {
                    // Update avatar if changed
                    if (avatarUrl != null && existingUser.avatar != avatarUrl) {
                        val updatedUser = existingUser.copy(avatar = avatarUrl)
                        usuarioDao.updateUsuario(updatedUser)
                        
                        // Try to sync updated user to Supabase
                        try {
                            com.example.tareamov.service.SupabaseClient.updateUsuario(updatedUser)
                            Log.d(TAG, "Updated user avatar in Supabase")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update user in Supabase: ${e.message}")
                        }
                    }
                    Log.d(TAG, "Found existing user: ${existingUser.usuario}")
                }
                
                existingUser?.let { user ->
                    // Create session
                    withContext(Dispatchers.Main) {
                        sessionManager.createLoginSession(
                            username = user.usuario,
                            userId = user.id,
                            personaId = user.persona_id ?: user.id,
                            roleName = "user",
                            avatarUri = user.avatar
                        )
                        
                        // Store userId in SharedPreferences
                        val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit().putLong("current_user_id", user.id).apply()
                        
                        Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                        
                        // Navigate to home
                        findNavController().navigate(R.id.videoHomeFragment)
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error creating/logging in local user", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error al crear cuenta: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Navega a RegisterFragment con los datos del usuario de Google pre-llenados
     */
    private fun navigateToRegisterWithGoogleData(email: String, nombres: String, apellidos: String, avatarUrl: String?) {
        val bundle = Bundle().apply {
            putString("googleEmail", email)
            putString("googleNombres", nombres)
            putString("googleApellidos", apellidos)
            putString("googleAvatar", avatarUrl)
            putBoolean("isGoogleSignIn", true)
        }
        
        findNavController().navigate(R.id.action_loginFragment_to_registerFragment, bundle)
    }
    
    /**
     * Inicia sesión para un usuario de Google que ya existe en la base de datos.
     * Usa la información de la Persona de Supabase para sincronizar.
     */
    private fun loginExistingGoogleUser(user: Usuario, persona: com.example.tareamov.data.entity.Persona, displayName: String, avatarUrl: String?) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val usuarioDao = db.usuarioDao()
                val personaDao = db.personaDao()
                val rolDao = db.rolDao()
                
                // Buscar usuario local por email de la persona
                var localUser = withContext(Dispatchers.IO) {
                    usuarioDao.getUsuarioByEmail(user.email)
                }
                
                if (localUser == null) {
                    // El usuario existe en Supabase pero no localmente, sincronizar
                    Log.d(TAG, "Usuario no existe localmente, sincronizando desde Supabase...")
                    
                    withContext(Dispatchers.IO) {
                        // 1. Asegurar que existe el rol
                        var usuarioRole = rolDao.getRolByNombre("usuario")
                        if (usuarioRole == null) {
                            val roleId = rolDao.insertRol(Rol.createUsuarioRole())
                            rolDao.insertRol(Rol.createAdminRole())
                            usuarioRole = rolDao.getRolById(roleId)
                            Log.d(TAG, "Roles creados, usuarioRole id: ${usuarioRole?.id}")
                        }
                        
                        val rolId = usuarioRole?.id ?: 1L
                        
                        // 2. Crear o sincronizar persona local desde Supabase
                        // Note: Persona no longer has email in local DB
                        // We need to find persona by identification or some other means, or just create new.
                        // Since we don't have email in Persona, we can't search by it easily unless we search Usuario.
                        // But we are creating a new user here.
                        
                        val newPersona = com.example.tareamov.data.entity.Persona(
                            id = 0, // Room generará nuevo ID local
                            identificacion = persona.identificacion,
                            nombres = persona.nombres,
                            apellidos = persona.apellidos,
                            // email removed
                            telefono = persona.telefono,
                            direccion = persona.direccion,
                            fechaNacimiento = persona.fechaNacimiento
                            // avatar removed
                            // esUsuario removed
                        )
                        val personaId = personaDao.insertPersona(newPersona)
                        Log.d(TAG, "Persona creada con id: $personaId")
                        
                        // 3. Crear usuario local con los IDs correctos
                        val newLocalUser = Usuario(
                            id = 0, // Room generará el ID
                            usuario = user.usuario,
                            contrasena = "", // Sin contraseña para usuarios de Google
                            persona_id = personaId,
                            email = user.email, // Email is now here
                            avatar = avatarUrl ?: user.avatar // Avatar is now here
                        )
                        
                        val newUserId = usuarioDao.insertUsuario(newLocalUser)
                        usuarioDao.updateUserRolId(newUserId, rolId)
                        Log.d(TAG, "Usuario local creado con id: $newUserId")
                        
                        localUser = usuarioDao.getUsuarioById(newUserId)
                    }
                    Log.d(TAG, "Usuario sincronizado desde Supabase a local")
                } else {
                    // Usuario existe localmente, actualizar avatar si cambió
                    val currentUser = localUser
                    if (currentUser != null && avatarUrl != null && currentUser.avatar != avatarUrl) {
                        withContext(Dispatchers.IO) {
                            // Create a copy with updated avatar
                            val updatedUser = currentUser.copy(avatar = avatarUrl)
                            usuarioDao.updateUsuario(updatedUser)
                            
                            // Sincronizar cambio a Supabase
                            try {
                                com.example.tareamov.service.SupabaseClient.updateUsuario(updatedUser)
                            } catch (e: Exception) {
                                Log.w(TAG, "No se pudo actualizar avatar en Supabase: ${e.message}")
                            }
                        }
                    }
                    Log.d(TAG, "Usuario ya existe localmente: ${currentUser?.usuario}")
                }
                
                // Crear sesión con el usuario local
                localUser?.let { currentUser ->
                    sessionManager.createLoginSession(
                        username = currentUser.usuario,
                        userId = currentUser.id,
                        personaId = currentUser.persona_id ?: currentUser.id,
                        roleName = "user",
                        avatarUri = currentUser.avatar
                    )
                    
                    val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    sharedPrefs.edit().putLong("current_user_id", currentUser.id).apply()
                    
                    Toast.makeText(requireContext(), "¡Bienvenido de nuevo, $displayName!", Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.videoHomeFragment)
                } ?: run {
                    Log.e(TAG, "Error: localUser es null después de la sincronización")
                    Toast.makeText(requireContext(), "Error al crear sesión local", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error al iniciar sesión con usuario existente: ${e.message}", e)
                Toast.makeText(requireContext(), "Error al iniciar sesión: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Inicia sesión para un usuario que se encontró solo por email (sin persona asociada conocida).
     */
    private fun loginExistingGoogleUserByEmail(user: Usuario, displayName: String, avatarUrl: String?) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val usuarioDao = db.usuarioDao()
                val personaDao = db.personaDao()
                val rolDao = db.rolDao()
                
                var localUser = withContext(Dispatchers.IO) {
                    usuarioDao.getUsuarioByEmail(user.email)
                }
                
                if (localUser == null) {
                    Log.d(TAG, "Usuario no existe localmente, sincronizando...")
                    
                    withContext(Dispatchers.IO) {
                        var usuarioRole = rolDao.getRolByNombre("usuario")
                        if (usuarioRole == null) {
                            val roleId = rolDao.insertRol(Rol.createUsuarioRole())
                            rolDao.insertRol(Rol.createAdminRole())
                            usuarioRole = rolDao.getRolById(roleId)
                        }
                        
                        val rolId = usuarioRole?.id ?: 1L
                        
                        val nameParts = displayName.split(" ", limit = 2)
                        val newPersona = com.example.tareamov.data.entity.Persona(
                            id = 0,
                            identificacion = "",
                            nombres = nameParts.getOrElse(0) { user.usuario },
                            apellidos = nameParts.getOrElse(1) { "" },
                            telefono = "",
                            direccion = "",
                            fechaNacimiento = ""
                        )
                        val personaId = personaDao.insertPersona(newPersona)
                        
                        val newLocalUser = Usuario(
                            id = 0,
                            usuario = user.usuario,
                            contrasena = "",
                            persona_id = personaId,
                            email = user.email,
                            avatar = avatarUrl ?: user.avatar
                        )
                        
                        val newUserId = usuarioDao.insertUsuario(newLocalUser)
                        usuarioDao.updateUserRolId(newUserId, rolId)
                        localUser = usuarioDao.getUsuarioById(newUserId)
                    }
                } else {
                    val currentUser = localUser
                    if (currentUser != null && avatarUrl != null && currentUser.avatar != avatarUrl) {
                        val updatedUser = currentUser.copy(avatar = avatarUrl)
                        usuarioDao.updateUsuario(updatedUser)
                        localUser = updatedUser
                    }
                }
                
                localUser?.let { currentUser ->
                    sessionManager.createLoginSession(
                        username = currentUser.usuario,
                        userId = currentUser.id,
                        personaId = currentUser.persona_id ?: currentUser.id,
                        roleName = "user",
                        avatarUri = currentUser.avatar
                    )
                    
                    val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    sharedPrefs.edit().putLong("current_user_id", currentUser.id).apply()
                    
                    Toast.makeText(requireContext(), "¡Bienvenido de nuevo, $displayName!", Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.videoHomeFragment)
                } ?: run {
                    Toast.makeText(requireContext(), "Error al crear sesión local", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error al iniciar sesión: ${e.message}", e)
                Toast.makeText(requireContext(), "Error al iniciar sesión: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Crea un usuario para una Persona que ya existe en Supabase pero no tiene Usuario asociado.
     */
    private fun createUserForExistingPersona(persona: com.example.tareamov.data.entity.Persona, email: String, displayName: String, avatarUrl: String?) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.getDatabase(requireContext())
                val usuarioDao = db.usuarioDao()
                val personaDao = db.personaDao()
                val rolDao = db.rolDao()
                
                withContext(Dispatchers.IO) {
                    // Asegurar que existe el rol
                    var usuarioRole = rolDao.getRolByNombre("usuario")
                    if (usuarioRole == null) {
                        val roleId = rolDao.insertRol(Rol.createUsuarioRole())
                        rolDao.insertRol(Rol.createAdminRole())
                        usuarioRole = rolDao.getRolById(roleId)
                    }
                    
                    val rolId = usuarioRole?.id ?: 1L
                    
                    // Crear persona local
                    val newPersona = com.example.tareamov.data.entity.Persona(
                        id = 0,
                        identificacion = persona.identificacion,
                        nombres = persona.nombres,
                        apellidos = persona.apellidos,
                        // email removed
                        telefono = persona.telefono,
                        direccion = persona.direccion,
                        fechaNacimiento = persona.fechaNacimiento
                        // avatar removed
                    )
                    val personaId = personaDao.insertPersona(newPersona)
                    
                    // Generar username basado en el email
                    val username = email.substringBefore("@")
                    
                    // Crear usuario local
                    val newLocalUser = Usuario(
                        id = 0,
                        usuario = username,
                        contrasena = "",
                        persona_id = personaId,
                        email = email,
                        avatar = avatarUrl
                    )
                    // Wait, persona.avatar is gone from class.
                    // We should use avatarUrl passed to function.
                    
                    val newUserId = usuarioDao.insertUsuario(newLocalUser)
                    usuarioDao.updateUserRolId(newUserId, rolId)
                    val localUser = usuarioDao.getUsuarioById(newUserId)
                    
                    // También crear el usuario en Supabase
                    try {
                        val supabaseUser = Usuario(
                            id = 0,
                            usuario = username,
                            contrasena = "",
                            persona_id = persona.id, // Usar el ID de Supabase para la persona
                            email = email,
                            avatar = avatarUrl
                        )
                        com.example.tareamov.service.SupabaseClient.insertUsuario(supabaseUser)
                        Log.d(TAG, "Usuario creado en Supabase")
                    } catch (e: Exception) {
                        Log.w(TAG, "No se pudo crear usuario en Supabase: ${e.message}")
                    }
                    
                    localUser?.let { currentUser ->
                        withContext(Dispatchers.Main) {
                            sessionManager.createLoginSession(
                                username = currentUser.usuario,
                                userId = currentUser.id,
                                personaId = currentUser.persona_id ?: currentUser.id,
                                roleName = "user",
                                avatarUri = currentUser.avatar
                            )
                            
                            val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                            sharedPrefs.edit().putLong("current_user_id", currentUser.id).apply()
                            
                            Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                            findNavController().navigate(R.id.videoHomeFragment)
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error al crear usuario para persona existente: ${e.message}", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
