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
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        
        // Initialize BackendApiService
        BackendApiService.initialize(requireContext())

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
                var userExists = false
                try {
                    userExists = withContext(Dispatchers.IO) {
                        val result = BackendApiService.getUserByUsername(desiredUsername)
                        result is ApiResult.Success && result.data != null
                    }
                } catch (e: Exception) {
                    userExists = false
                }

                if (userExists) {
                    Toast.makeText(requireContext(), "El usuario ya existe. Por favor elija otro usuario.", Toast.LENGTH_SHORT).show()
                } else {
                    findNavController().navigate(R.id.registerFragment)
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
                                        BackendApiService.registerFCMToken(token)
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

            // Use BackendApiService for login
            lifecycleScope.launch {
                fun maskSecret(s: String?): String {
                    if (s == null) return "null"
                    val len = s.length
                    return when {
                        len <= 2 -> "*".repeat(len)
                        else -> s.first() + "*".repeat(len - 2) + s.last()
                    }
                }

                android.util.Log.d("LoginFragment", "Login attempt for user=$username password_mask=${maskSecret(password)}")

                // Simply delegate to AuthViewModel which uses BackendApiService
                authViewModel.login(username, password)
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
        // With BackendApiService, session check is handled via SessionManager
        // No need to query local DB for user count
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

            // Authenticate with backend using the new Google login endpoint
            // This properly authenticates the user and returns JWT tokens for the correct account
            // Pass any username typed in the login field as a hint to link Google email to existing account
            val usernameHint = usernameEditText.text.toString().trim().takeIf { it.length >= 3 }
            lifecycleScope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        BackendApiService.loginWithGoogle(email, displayName, profilePictureUri, usernameHint)
                    }

                    when (result) {
                        is ApiResult.Success -> {
                            val authResponse = result.data
                            if (authResponse?.effectiveToken() != null && authResponse.user != null) {
                                val user = authResponse.user
                                val userId = user.get("id")?.asLong ?: -1L
                                val personaId = user.get("persona_id")?.asLong ?: -1L
                                val username = user.get("username")?.asString ?: email.substringBefore("@")
                                val avatarUri = user.get("avatar")?.let {
                                    if (it.isJsonNull) profilePictureUri else it.asString
                                } ?: profilePictureUri
                                val roleName = user.get("rolNombre")?.let {
                                    if (it.isJsonNull) "user" else it.asString
                                } ?: "user"

                                Log.d(TAG, "Google login successful. UserId=$userId, Username=$username")

                                // Fetch roles from backend
                                val roleIds = withContext(Dispatchers.IO) {
                                    (BackendApiService.getUserRoles(userId) as? ApiResult.Success)?.data ?: emptyList()
                                }
                                val actualRoleName = when {
                                    roleIds.contains(3L) -> "admin"
                                    roleIds.contains(2L) -> "docente"
                                    else -> "user"
                                }
                                val roleId = roleIds.firstOrNull()?.toInt() ?: 1

                                // Create session with correct user data
                                sessionManager.createLoginSession(
                                    username = username,
                                    userId = userId,
                                    personaId = personaId,
                                    roleName = actualRoleName,
                                    avatarUri = avatarUri
                                )

                                sessionManager.addRole(roleId)
                                if (actualRoleName.equals("admin", ignoreCase = true) || roleId == 3) {
                                    sessionManager.setAdminStatus(true)
                                }

                                val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                                sharedPrefs.edit().putLong("current_user_id", userId).apply()

                                Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                                findNavController().navigate(R.id.videoHomeFragment)
                            } else {
                                Log.e(TAG, "Google login response missing token or user data")
                                Toast.makeText(requireContext(), "Error de autenticación", Toast.LENGTH_SHORT).show()
                            }
                        }
                        is ApiResult.Error -> {
                            Log.e(TAG, "Google login failed: ${result.message}")
                            // If user doesn't exist, redirect to registration
                            if (result.code == 404) {
                                Log.d(TAG, "Usuario no encontrado, redirigiendo a registro")
                                navigateToRegisterWithGoogleData(email, nombres, apellidos, profilePictureUri)
                            } else {
                                Toast.makeText(requireContext(), "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en autenticación Google: ${e.message}")
                    // En caso de error de conexión, redirigir al registro
                    navigateToRegisterWithGoogleData(email, nombres, apellidos, profilePictureUri)
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
    
    /**
     * Authenticate Google user via BackendApiService.
     * The backend handles Google auth and returns a JWT token + user data.
     */
    private fun authenticateWithBackend(email: String, displayName: String, avatarUrl: String?) {
        lifecycleScope.launch {
            try {
                // Try to find existing user by email via backend
                val existingUser = withContext(Dispatchers.IO) {
                    BackendApiService.getUserByEmail(email).getOrNull()
                }
                
                if (existingUser != null) {
                    // User exists, create session
                    loginExistingGoogleUserFast(existingUser, displayName, avatarUrl)
                } else {
                    // Register new user via backend
                    val username = email.substringBefore("@")
                    val nameParts = displayName.split(" ", limit = 2)
                    val nombres = nameParts.getOrElse(0) { username }
                    val apellidos = nameParts.getOrElse(1) { "" }
                    
                    val registerResult = withContext(Dispatchers.IO) {
                        BackendApiService.register(username, "", email)
                    }
                    
                    when (registerResult) {
                        is ApiResult.Success -> {
                            val authResponse = registerResult.data
                            val userId = authResponse?.user?.get("id")?.asLong ?: -1L
                            val personaId = authResponse?.user?.get("persona_id")?.asLong ?: -1L
                            val roleName = authResponse?.user?.get("rolNombre")?.let {
                                if (it.isJsonNull) "user" else it.asString
                            } ?: "user"
                            val roleId = authResponse?.user?.get("rol_id")?.asInt ?: 1
                            
                            sessionManager.createLoginSession(
                                username = username,
                                userId = userId,
                                personaId = personaId,
                                roleName = roleName,
                                avatarUri = avatarUrl
                            )
                            
                            sessionManager.addRole(roleId)
                            if (roleName.equals("admin", ignoreCase = true) || roleId == 3) {
                                sessionManager.setAdminStatus(true)
                            }
                            
                            val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                            sharedPrefs.edit().putLong("current_user_id", userId).apply()
                            
                            Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                            findNavController().navigate(R.id.videoHomeFragment)
                        }
                        is ApiResult.Error -> {
                            Log.e(TAG, "Backend register failed: ${registerResult.message}")
                            Toast.makeText(requireContext(), "Error al crear cuenta: ${registerResult.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error authenticating with backend", e)
                Toast.makeText(requireContext(), "Error al crear cuenta: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Creates or logs in a user via BackendApiService for Google Sign-In.
     */
    private suspend fun createOrLoginLocalUser(email: String, displayName: String, avatarUrl: String?, supabaseUserId: String? = null) {
        try {
            // Check if user already exists by email via backend
            val existingUser = withContext(Dispatchers.IO) {
                BackendApiService.getUserByEmail(email).getOrNull()
            }
            
            if (existingUser != null) {
                // User exists, update avatar if needed
                if (avatarUrl != null && existingUser.avatar != avatarUrl) {
                    withContext(Dispatchers.IO) {
                        BackendApiService.updateMyProfile(mapOf("avatar" to avatarUrl))
                    }
                }
                
                // Fetch roles
                val roleIds = withContext(Dispatchers.IO) {
                    (BackendApiService.getUserRoles(existingUser.id) as? ApiResult.Success)?.data ?: emptyList()
                }
                val roleName = when {
                    roleIds.contains(3L) -> "admin"
                    roleIds.contains(2L) -> "docente"
                    else -> "user"
                }
                val roleId = roleIds.firstOrNull()?.toInt() ?: 1
                
                withContext(Dispatchers.Main) {
                    sessionManager.createLoginSession(
                        username = existingUser.usuario,
                        userId = existingUser.id,
                        personaId = existingUser.persona_id,
                        roleName = roleName,
                        avatarUri = avatarUrl ?: existingUser.avatar
                    )
                    sessionManager.addRole(roleId)
                    if (roleName == "admin" || roleId == 3) {
                        sessionManager.setAdminStatus(true)
                    }
                    
                    val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    sharedPrefs.edit().putLong("current_user_id", existingUser.id).apply()
                    
                    Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                    findNavController().navigate(R.id.videoHomeFragment)
                }
            } else {
                // Register new user via backend
                val username = email.substringBefore("@")
                val registerResult = withContext(Dispatchers.IO) {
                    BackendApiService.register(username, "", email)
                }
                
                when (registerResult) {
                    is ApiResult.Success -> {
                        val authResponse = registerResult.data
                        val userId = authResponse?.user?.get("id")?.asLong ?: -1L
                        val personaId = authResponse?.user?.get("persona_id")?.asLong ?: -1L
                        val roleId = authResponse?.user?.get("rol_id")?.asInt ?: 1
                        
                        withContext(Dispatchers.Main) {
                            sessionManager.createLoginSession(
                                username = username,
                                userId = userId,
                                personaId = personaId,
                                roleName = "user",
                                avatarUri = avatarUrl
                            )
                            sessionManager.addRole(roleId)
                            
                            val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                            sharedPrefs.edit().putLong("current_user_id", userId).apply()
                            
                            Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                            findNavController().navigate(R.id.videoHomeFragment)
                        }
                    }
                    is ApiResult.Error -> {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Error al crear cuenta: ${registerResult.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating/logging in user via backend", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Error al crear cuenta: ${e.message}", Toast.LENGTH_SHORT).show()
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
     * Login rápido para usuario de Google existente usando BackendApiService.
     */
    private fun loginExistingGoogleUserFast(
        user: Usuario, 
        displayName: String, 
        avatarUrl: String?
    ) {
        lifecycleScope.launch {
            try {
                // Update avatar if changed
                if (avatarUrl != null && user.avatar != avatarUrl) {
                    withContext(Dispatchers.IO) {
                        try {
                            BackendApiService.updateMyProfile(mapOf("avatar" to avatarUrl))
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo actualizar avatar: ${e.message}")
                        }
                    }
                }
                
                // Fetch roles from backend
                val roleIds = withContext(Dispatchers.IO) {
                    (BackendApiService.getUserRoles(user.id) as? ApiResult.Success)?.data ?: emptyList()
                }
                val roleName = when {
                    roleIds.contains(3L) -> "admin"
                    roleIds.contains(2L) -> "docente"
                    else -> "user"
                }
                val roleId = roleIds.firstOrNull()?.toInt() ?: 1
                
                // Create session immediately
                sessionManager.createLoginSession(
                    username = user.usuario,
                    userId = user.id,
                    personaId = user.persona_id,
                    roleName = roleName,
                    avatarUri = avatarUrl ?: user.avatar
                )
                
                sessionManager.addRole(roleId)
                if (roleName.equals("admin", ignoreCase = true) || roleId == 3) {
                    sessionManager.setAdminStatus(true)
                }
                
                val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putLong("current_user_id", user.id).apply()
                
                Log.d(TAG, "Login rápido exitoso: ${user.usuario}, rol: $roleName (id: $roleId)")
                Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.videoHomeFragment)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error en login rápido: ${e.message}", e)
                Toast.makeText(requireContext(), "Error al iniciar sesión: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Login rápido por email usando BackendApiService (sin persona local).
     */
    private fun loginExistingGoogleUserByEmailFast(
        user: Usuario, 
        displayName: String, 
        avatarUrl: String?
    ) {
        lifecycleScope.launch {
            try {
                // Fetch roles from backend
                val roleIds = withContext(Dispatchers.IO) {
                    (BackendApiService.getUserRoles(user.id) as? ApiResult.Success)?.data ?: emptyList()
                }
                val roleName = when {
                    roleIds.contains(3L) -> "admin"
                    roleIds.contains(2L) -> "docente"
                    else -> "user"
                }
                val roleId = roleIds.firstOrNull()?.toInt() ?: 1
                
                sessionManager.createLoginSession(
                    username = user.usuario,
                    userId = user.id,
                    personaId = user.persona_id,
                    roleName = roleName,
                    avatarUri = avatarUrl ?: user.avatar
                )
                
                sessionManager.addRole(roleId)
                if (roleName.equals("admin", ignoreCase = true) || roleId == 3) {
                    sessionManager.setAdminStatus(true)
                }
                
                val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putLong("current_user_id", user.id).apply()
                
                // Update avatar in background if changed
                if (avatarUrl != null && user.avatar != avatarUrl) {
                    withContext(Dispatchers.IO) {
                        try {
                            BackendApiService.updateMyProfile(mapOf("avatar" to avatarUrl))
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo actualizar avatar: ${e.message}")
                        }
                    }
                }
                
                Log.d(TAG, "Login rápido por email exitoso: ${user.usuario}, rol: $roleName")
                Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                findNavController().navigate(R.id.videoHomeFragment)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error en login rápido por email: ${e.message}", e)
                Toast.makeText(requireContext(), "Error al iniciar sesión: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * Inicia sesión para un usuario de Google que ya existe en el backend.
     * Usa BackendApiService para obtener datos del usuario.
     */
    private fun loginExistingGoogleUser(user: Usuario, displayName: String, avatarUrl: String?) {
        // Delegate to the fast version
        loginExistingGoogleUserFast(user, displayName, avatarUrl)
    }
    
    /**
     * Inicia sesión para un usuario que se encontró solo por email.
     * Usa BackendApiService.
     */
    private fun loginExistingGoogleUserByEmail(user: Usuario, displayName: String, avatarUrl: String?) {
        // Delegate to the fast version
        loginExistingGoogleUserByEmailFast(user, displayName, avatarUrl)
    }
    
    /**
     * Crea un usuario para una Persona que ya existe en el backend pero no tiene Usuario asociado.
     * Usa BackendApiService.register() para crear el usuario.
     */
    private fun createUserForExistingPersona(persona: Persona, email: String, displayName: String, avatarUrl: String?) {
        lifecycleScope.launch {
            try {
                val username = email.substringBefore("@")
                
                val registerResult = withContext(Dispatchers.IO) {
                    BackendApiService.register(username, "", email, persona.id)
                }
                
                when (registerResult) {
                    is ApiResult.Success -> {
                        val authResponse = registerResult.data
                        val userId = authResponse?.user?.get("id")?.asLong ?: -1L
                        val personaId = authResponse?.user?.get("persona_id")?.asLong ?: persona.id
                        val roleId = authResponse?.user?.get("rol_id")?.asInt ?: 1
                        
                        sessionManager.createLoginSession(
                            username = username,
                            userId = userId,
                            personaId = personaId,
                            roleName = "user",
                            avatarUri = avatarUrl
                        )
                        
                        sessionManager.addRole(roleId)
                        if (roleId == 3) {
                            sessionManager.setAdminStatus(true)
                        }
                        
                        val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit().putLong("current_user_id", userId).apply()
                        
                        Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                        findNavController().navigate(R.id.videoHomeFragment)
                    }
                    is ApiResult.Error -> {
                        Log.e(TAG, "Error al crear usuario: ${registerResult.message}")
                        Toast.makeText(requireContext(), "Error: ${registerResult.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al crear usuario para persona existente: ${e.message}", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}