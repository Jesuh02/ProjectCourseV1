package com.example.tareamov.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
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
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.data.entity.Persona
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.config.TenantResolver

import com.example.tareamov.util.SessionManager
import com.example.tareamov.viewmodel.AuthViewModel
import com.example.tareamov.viewmodel.SuspendedInfo
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.FirebaseApp
import com.google.gson.JsonObject
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
    private lateinit var loginProgressBar: ProgressBar

    // Cédula disambiguation field (hidden until two users share the same credentials)
    private var cedulaEditText: EditText? = null
    private var cedulaContainer: View? = null
    private var cedulaLabel: View? = null
    private var cedulaHintText: View? = null

    
    // Google Sign-In (Legacy API - more compatible)
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var googleSignInButton: View
    private lateinit var googleSignInLauncher: ActivityResultLauncher<Intent>

    // Pending Google login data for multi-tenant selection
    private var pendingGoogleEmail: String? = null
    private var pendingGoogleDisplayName: String? = null
    private var pendingGoogleAvatarUrl: String? = null
    private var pendingGoogleUsernameHint: String? = null
    
    companion object {
        private const val TAG = "LoginFragment"
        private const val GOOGLE_USER_NOT_FOUND_MESSAGE = "Usuario no encontrado"
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
        
        // Initialize login progress bar
        loginProgressBar = view.findViewById(R.id.loginProgressBar)
        
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

        // Wire up cedula disambiguation views
        cedulaEditText = view.findViewById(R.id.cedulaEditText)
        cedulaContainer = view.findViewById(R.id.cedulaContainer)
        cedulaLabel = view.findViewById(R.id.cedulaLabel)
        cedulaHintText = view.findViewById(R.id.cedulaHintText)

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (sessionManager.isLoggedIn()) {
            Log.d(TAG, "Active session detected in LoginFragment, redirecting to videoHomeFragment")
            navigateToVideoHomeSafely()
            return
        }
    
        // Initialize ViewModel
        authViewModel = ViewModelProvider(this)[AuthViewModel::class.java]
        
        // Start animations
        startAnimations()

        // El botón de registro está permanentemente oculto
        registerButton.visibility = View.GONE

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

        // Observe tenant selection (user exists on multiple institutions)
        authViewModel.pendingTenantSelection.observe(viewLifecycleOwner) { matches ->
            if (matches != null && matches.size > 1) {
                hideLoginLoading()
                showTenantSelectionDialog(matches)
            }
        }

        // Observe cedula required (two distinct users share the same credentials)
        authViewModel.needsCedula.observe(viewLifecycleOwner) { required ->
            if (required == true) {
                hideLoginLoading()
                cedulaContainer?.visibility = View.VISIBLE
                cedulaLabel?.visibility = View.VISIBLE
                cedulaHintText?.visibility = View.VISIBLE
                cedulaEditText?.requestFocus()
            }
        }

        // Observe institution suspended (payment required)
        authViewModel.suspendedInfo.observe(viewLifecycleOwner) { info ->
            if (info != null) {
                hideLoginLoading()
                showSuspendedPaymentDialog(info)
                authViewModel.dismissSuspended()
            }
        }

        // Observe login result
        authViewModel.loginResult.observe(viewLifecycleOwner) { result ->
            hideLoginLoading()
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

                navigateToVideoHomeSafely()
            } else {
                val msg = result.errorMessage ?: "Usuario o contrase\u00f1a incorrectos"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            }
        }

        // Set up login button click listener
        loginButton.setOnClickListener {
            val username = usernameEditText.text.toString().trim()
            val password = passwordEditText.text.toString()
            val cedula = cedulaEditText?.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

            // If we have pending Google credentials and a cédula, retry Google login with cédula
            if (pendingGoogleEmail != null && cedula != null) {
                showLoginLoading()
                val email = pendingGoogleEmail!!
                val displayName = pendingGoogleDisplayName ?: email.substringBefore("@")
                val avatarUrl = pendingGoogleAvatarUrl
                val usernameHint = pendingGoogleUsernameHint

                lifecycleScope.launch {
                    try {
                        val probeResult = withContext(Dispatchers.IO) {
                            TenantResolver.probeGoogleLogin(
                                requireContext(), email, displayName, avatarUrl, usernameHint, cedula
                            )
                        }
                        when (probeResult) {
                            is TenantResolver.ResolveResult.Single -> {
                                clearPendingGoogleData()
                                completeGoogleLoginWithTenant(
                                    probeResult.resolved, email, displayName,
                                    avatarUrl, usernameHint
                                )
                            }
                            is TenantResolver.ResolveResult.Multiple -> {
                                showTenantSelectionDialog(probeResult.matches)
                            }
                            is TenantResolver.ResolveResult.None -> {
                                hideLoginLoading()
                                Toast.makeText(requireContext(), probeResult.message, Toast.LENGTH_LONG).show()
                            }
                            TenantResolver.ResolveResult.NeedsCedula -> {
                                hideLoginLoading()
                                Toast.makeText(requireContext(), "Cédula no válida. Intenta de nuevo.", Toast.LENGTH_LONG).show()
                            }
                        }
                    } catch (e: Exception) {
                        hideLoginLoading()
                        Log.e(TAG, "Error retrying Google login with cedula: ${e.message}")
                        Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
                return@setOnClickListener
            }

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(requireContext(), "Por favor ingrese usuario y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            showLoginLoading()

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

                // Pass cedula when the disambiguation field is visible

                // Simply delegate to AuthViewModel which uses BackendApiService
                authViewModel.login(username, password, cedula)
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

        view.findViewById<TextView>(R.id.forgotPasswordTextView).setOnClickListener {
            findNavController().navigate(R.id.action_loginFragment_to_forgotPasswordFragment)
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
    
    // ==================== LOADING STATE HELPERS ====================
    
    private fun showLoginLoading() {
        loginButton.isEnabled = false
        loginButton.text = ""
        loginProgressBar.visibility = View.VISIBLE
    }
    
    private fun hideLoginLoading(buttonText: String = "Ingresar") {
        loginButton.isEnabled = true
        loginButton.text = buttonText
        loginProgressBar.visibility = View.GONE
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

            // Authenticate with backend using the new Google login endpoint
            // This properly authenticates the user and returns JWT tokens for the correct account
            // Pass any username typed in the login field as a hint to link Google email to existing account
            val usernameHint = usernameEditText.text.toString().trim().takeIf { it.length >= 3 }
            lifecycleScope.launch {
                try {
                    val probeResult = withContext(Dispatchers.IO) {
                        TenantResolver.probeGoogleLogin(
                            requireContext(), email, displayName, profilePictureUri, usernameHint
                        )
                    }

                    when (probeResult) {
                        is TenantResolver.ResolveResult.Multiple -> {
                            Log.d(TAG, "Google user found on ${probeResult.matches.size} tenants")
                            pendingGoogleEmail = email
                            pendingGoogleDisplayName = displayName
                            pendingGoogleAvatarUrl = profilePictureUri
                            pendingGoogleUsernameHint = usernameHint
                            showTenantSelectionDialog(probeResult.matches)
                        }
                        is TenantResolver.ResolveResult.Single -> {
                            completeGoogleLoginWithTenant(
                                probeResult.resolved, email, displayName,
                                profilePictureUri, usernameHint
                            )
                        }
                        is TenantResolver.ResolveResult.None -> {
                            Log.d(TAG, "Google user not found on any tenant: ${probeResult.message}")
                            hideLoginLoading()
                            Toast.makeText(requireContext(), probeResult.message, Toast.LENGTH_LONG).show()
                        }
                        TenantResolver.ResolveResult.NeedsCedula -> {
                            // Show cedula field so the user can disambiguate
                            Log.d(TAG, "Google user needs cedula to disambiguate")
                            pendingGoogleEmail = email
                            pendingGoogleDisplayName = displayName
                            pendingGoogleAvatarUrl = profilePictureUri
                            pendingGoogleUsernameHint = usernameHint
                            cedulaContainer?.visibility = View.VISIBLE
                            cedulaLabel?.visibility = View.VISIBLE
                            cedulaHintText?.visibility = View.VISIBLE
                            cedulaEditText?.requestFocus()
                            Toast.makeText(requireContext(), "Tu correo existe en varias instituciones. Ingresa tu cédula para continuar.", Toast.LENGTH_LONG).show()
                        }
                        is TenantResolver.ResolveResult.Suspended -> {
                            Log.d(TAG, "Institution suspended: ${probeResult.institutionName}")
                            hideLoginLoading()
                            showSuspendedPaymentDialog(SuspendedInfo(
                                institutionId = probeResult.institutionId,
                                institutionName = probeResult.institutionName,
                                monthlyPrice = probeResult.monthlyPrice,
                                serverUrl = probeResult.serverUrl,
                                message = probeResult.message
                            ))
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en autenticación Google: ${e.message}")
                    Toast.makeText(requireContext(), e.message ?: "Error en autenticación Google", Toast.LENGTH_LONG).show()
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
                    // Check if user is deactivated
                    if (!existingUser.isActive) {
                        Toast.makeText(requireContext(), "Tu usuario ha sido desactivado, por favor contactar a soporte", Toast.LENGTH_LONG).show()
                        return@launch
                    }
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
                            
                            com.example.tareamov.util.AppCache.clearAll()

                            sessionManager.createLoginSession(
                                username = username,
                                userId = userId,
                                personaId = personaId,
                                roleName = roleName,
                                avatarUri = avatarUrl
                            )
                            
                            sessionManager.addRole(roleId)
                            // Explicitly set admin status based on actual role
                            sessionManager.setAdminStatus(roleName.equals("admin", ignoreCase = true) || roleId == 3)
                            
                            val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                            sharedPrefs.edit().putLong("current_user_id", userId).apply()
                            
                            Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                            navigateToVideoHomeSafely()
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
                    com.example.tareamov.util.AppCache.invalidateProfile()
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
                
                withContext(Dispatchers.Main) {
                    com.example.tareamov.util.AppCache.clearAll()
                    sessionManager.createLoginSession(
                        username = existingUser.usuario,
                        userId = existingUser.id,
                        personaId = existingUser.persona_id,
                        roleName = roleName,
                        avatarUri = avatarUrl ?: existingUser.avatar
                    )
                    // Add ALL roles from backend
                    for (rid in roleIds) {
                        sessionManager.addRole(rid.toInt())
                    }
                    // Explicitly set admin status
                    sessionManager.setAdminStatus(roleIds.contains(3L))
                    
                    val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                    sharedPrefs.edit().putLong("current_user_id", existingUser.id).apply()
                    
                    Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                    navigateToVideoHomeSafely()
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
                            com.example.tareamov.util.AppCache.clearAll()
                            sessionManager.createLoginSession(
                                username = username,
                                userId = userId,
                                personaId = personaId,
                                roleName = "user",
                                avatarUri = avatarUrl
                            )
                            sessionManager.addRole(roleId)
                            // New registration is never admin
                            sessionManager.setAdminStatus(false)
                            
                            val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                            sharedPrefs.edit().putLong("current_user_id", userId).apply()
                            
                            Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                            navigateToVideoHomeSafely()
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
                    com.example.tareamov.util.AppCache.invalidateProfile()
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
                
                // Create session immediately
                com.example.tareamov.util.AppCache.clearAll()
                sessionManager.createLoginSession(
                    username = user.usuario,
                    userId = user.id,
                    personaId = user.persona_id,
                    roleName = roleName,
                    avatarUri = avatarUrl ?: user.avatar
                )
                
                // Add ALL roles from backend
                for (rid in roleIds) {
                    sessionManager.addRole(rid.toInt())
                }
                // Explicitly set admin status based on actual roles
                sessionManager.setAdminStatus(roleIds.contains(3L))
                
                val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                sharedPrefs.edit().putLong("current_user_id", user.id).apply()
                
                Log.d(TAG, "Login rápido exitoso: ${user.usuario}, rol: $roleName (ids: $roleIds)")
                Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                navigateToVideoHomeSafely()
                
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
                
                com.example.tareamov.util.AppCache.clearAll()
                sessionManager.createLoginSession(
                    username = user.usuario,
                    userId = user.id,
                    personaId = user.persona_id,
                    roleName = roleName,
                    avatarUri = avatarUrl ?: user.avatar
                )
                
                // Add ALL roles from backend
                for (rid in roleIds) {
                    sessionManager.addRole(rid.toInt())
                }
                // Explicitly set admin status based on actual roles
                sessionManager.setAdminStatus(roleIds.contains(3L))
                
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
                    com.example.tareamov.util.AppCache.invalidateProfile()
                }
                
                Log.d(TAG, "Login rápido por email exitoso: ${user.usuario}, rol: $roleName")
                Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                navigateToVideoHomeSafely()
                
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
                        
                        com.example.tareamov.util.AppCache.clearAll()
                        sessionManager.createLoginSession(
                            username = username,
                            userId = userId,
                            personaId = personaId,
                            roleName = "user",
                            avatarUri = avatarUrl
                        )
                        
                        sessionManager.addRole(roleId)
                        // New registration is never admin
                        sessionManager.setAdminStatus(false)
                        
                        val sharedPrefs = requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
                        sharedPrefs.edit().putLong("current_user_id", userId).apply()
                        
                        Toast.makeText(requireContext(), "¡Bienvenido, $displayName!", Toast.LENGTH_SHORT).show()
                        navigateToVideoHomeSafely()
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

    private fun showTenantSelectionDialog(matches: List<TenantResolver.ResolvedLogin>) {
        if (!isAdded) return
        val hasPendingGoogle = pendingGoogleEmail != null
        var shouldCleanupSelectionState = true
        val dialogView = layoutInflater.inflate(R.layout.dialog_tenant_selection, null)
        val institutionListContainer = dialogView.findViewById<LinearLayout>(R.id.institutionListContainer)
        val cancelButton = dialogView.findViewById<View>(R.id.cancelTenantSelectionButton)

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_TareaMov_Dialog)
            .setView(dialogView)
            .create()

        matches.forEachIndexed { index, match ->
            val itemView = layoutInflater.inflate(R.layout.item_tenant_selection, institutionListContainer, false)
            val titleView = itemView.findViewById<TextView>(R.id.institutionNameText)
            titleView.text = getInstitutionDisplayName(match)

            if (index == matches.lastIndex) {
                (itemView.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = 0
            }

            itemView.setOnClickListener {
                shouldCleanupSelectionState = false
                dialog.dismiss()
                if (hasPendingGoogle) {
                    completeGoogleLoginWithTenant(match)
                } else {
                    authViewModel.commitLogin(match)
                }
            }

            institutionListContainer.addView(itemView)
        }

        cancelButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (shouldCleanupSelectionState) {
                if (!hasPendingGoogle) {
                    authViewModel.dismissTenantSelection()
                }
                clearPendingGoogleData()
            }
        }

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun showSuspendedPaymentDialog(info: SuspendedInfo) {
        if (!isAdded) return
        val dialogView = layoutInflater.inflate(R.layout.dialog_suspended_payment, null)
        val institutionNameText = dialogView.findViewById<TextView>(R.id.suspendedInstitutionName)
        val priceText = dialogView.findViewById<TextView>(R.id.suspendedPrice)
        val payButton = dialogView.findViewById<View>(R.id.payButton)
        val cancelButton = dialogView.findViewById<View>(R.id.cancelPaymentButton)
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.paymentProgressBar)

        institutionNameText.text = info.institutionName
        val priceFormatted = String.format("%,.0f", info.monthlyPrice)
        priceText.text = "\$$priceFormatted COP"

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_TareaMov_Dialog)
            .setView(dialogView)
            .create()

        payButton.setOnClickListener {
            payButton.isEnabled = false
            progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    val checkoutUrl = withContext(Dispatchers.IO) {
                        initiatePublicBillingPayment(info.institutionId, info.serverUrl)
                    }
                    if (checkoutUrl != null) {
                        dialog.dismiss()
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(checkoutUrl)))
                    } else {
                        Toast.makeText(requireContext(), "No se pudo iniciar el pago", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error initiating billing payment: ${e.message}", e)
                    Toast.makeText(requireContext(), "Error al iniciar pago: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    payButton.isEnabled = true
                    progressBar.visibility = View.GONE
                }
            }
        }

        cancelButton.setOnClickListener { dialog.dismiss() }

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private suspend fun initiatePublicBillingPayment(institutionId: String, serverUrl: String): String? {
        val url = "${serverUrl.trimEnd('/')}/api/v1/instituciones/billing/public-initiate"
        val jsonBody = okhttp3.RequestBody.create(
            okhttp3.MediaType.parse("application/json; charset=utf-8"),
            """{"institutionId":"$institutionId"}"""
        )
        val request = okhttp3.Request.Builder()
            .url(url)
            .post(jsonBody)
            .build()

        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "Public billing payment failed: ${response.code()}")
            return null
        }
        val body = response.body()?.string() ?: return null
        val json = com.google.gson.JsonParser.parseString(body).asJsonObject
        return json.get("checkoutUrl")?.asString
    }

    private fun getInstitutionDisplayName(match: TenantResolver.ResolvedLogin): String {
        val userObject = match.authJson.getAsJsonObject("user")

        return extractInstitutionName(userObject?.getAsJsonObject("institucion"))
            ?: extractInstitutionName(userObject?.getAsJsonObject("institution"))
            ?: extractInstitutionName(userObject?.getAsJsonObject("persona")?.getAsJsonObject("institucion"))
            ?: extractInstitutionName(match.authJson.getAsJsonObject("institucion"))
            ?: extractInstitutionName(match.authJson.getAsJsonObject("institution"))
            ?: sanitizeInstitutionDisplayName(match.tenant.name)
    }

    private fun extractInstitutionName(institutionObject: JsonObject?): String? {
        if (institutionObject == null) return null

        val candidate = listOf("nombre", "name", "institutionName")
            .firstNotNullOfOrNull { key ->
                institutionObject.get(key)
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }

        return candidate?.let(::sanitizeInstitutionDisplayName)
    }

    private fun sanitizeInstitutionDisplayName(rawName: String): String {
        val withoutUrl = rawName
            .replace(Regex("""\s*[-|:]\s*https?://\S+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\(https?://[^)]+\)""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\([^)]+\.(railway\.app|vercel\.app|onrender\.com|herokuapp\.com)[^)]*\)""", RegexOption.IGNORE_CASE), "")
            .trim()

        val parts = withoutUrl.split(" - ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val firstPartLooksLikeServer = parts.first().contains(Regex("""\b(qa|dev|develop|prod|production|staging|server|backend)\b""", RegexOption.IGNORE_CASE))
            if (firstPartLooksLikeServer) {
                return parts.drop(1).joinToString(" - ")
            }
        }

        return withoutUrl
    }

    /**
     * Completes Google login after the user picks a specific tenant from the multi-tenant dialog.
     */
    private fun completeGoogleLoginWithTenant(
        resolved: TenantResolver.ResolvedLogin
    ) {
        val email = pendingGoogleEmail ?: return
        val displayName = pendingGoogleDisplayName
        val avatarUrl = pendingGoogleAvatarUrl
        val usernameHint = pendingGoogleUsernameHint
        clearPendingGoogleData()

        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    TenantResolver.commitAndLoginWithGoogle(
                        requireContext(), resolved, email, displayName, avatarUrl, usernameHint
                    )
                }
                when (result) {
                    is ApiResult.Success -> handleGoogleLoginSuccess(result, displayName, avatarUrl)
                    is ApiResult.Error -> {
                        Log.e(TAG, "Google login after tenant selection failed: ${result.message}")
                        Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error completing Google login: ${e.message}", e)
                Toast.makeText(requireContext(), "Error de conexión", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Completes Google login after tenant resolution finds a single match.
     */
    private fun completeGoogleLoginWithTenant(
        resolved: TenantResolver.ResolvedLogin,
        email: String,
        displayName: String?,
        avatarUrl: String?,
        usernameHint: String?
    ) {
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    TenantResolver.commitAndLoginWithGoogle(
                        requireContext(), resolved, email, displayName, avatarUrl, usernameHint
                    )
                }
                when (result) {
                    is ApiResult.Success -> handleGoogleLoginSuccess(result, displayName, avatarUrl)
                    is ApiResult.Error -> {
                        if (result.code == 404) {
                            showGoogleUserNotFound()
                        } else {
                            Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error completing Google login: ${e.message}", e)
                Toast.makeText(requireContext(), e.message ?: "Error al completar autenticación con Google", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showGoogleUserNotFound() {
        if (!isAdded) return
        Toast.makeText(requireContext(), GOOGLE_USER_NOT_FOUND_MESSAGE, Toast.LENGTH_LONG).show()
    }

    private suspend fun handleGoogleLoginSuccess(
        result: ApiResult.Success<BackendApiService.AuthResponse>,
        displayName: String?,
        fallbackAvatarUrl: String?
    ) {
        val authResponse = result.data
        if (authResponse?.effectiveToken() == null || authResponse.user == null) {
            Toast.makeText(requireContext(), "Error de autenticación", Toast.LENGTH_SHORT).show()
            return
        }
        val user = authResponse.user
        val userId = user.get("id")?.asLong ?: -1L
        val personaId = user.get("persona_id")?.asLong ?: -1L
        val username = user.get("username")?.asString ?: displayName?.split("@")?.first() ?: ""
        val avatarUri = user.get("avatar")?.let {
            if (it.isJsonNull) fallbackAvatarUrl else it.asString
        } ?: fallbackAvatarUrl

        Log.d(TAG, "Google login successful. UserId=$userId, Username=$username")

        val roleIds = withContext(Dispatchers.IO) {
            (BackendApiService.getUserRoles(userId) as? ApiResult.Success)?.data ?: emptyList()
        }
        val actualRoleName = when {
            roleIds.contains(3L) -> "admin"
            roleIds.contains(2L) -> "docente"
            else -> "user"
        }

        com.example.tareamov.util.AppCache.clearAll()
        sessionManager.createLoginSession(username, userId, personaId, actualRoleName, avatarUri)
        for (rid in roleIds) { sessionManager.addRole(rid.toInt()) }
        sessionManager.setAdminStatus(roleIds.contains(3L))

        requireActivity().getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            .edit().putLong("current_user_id", userId).apply()

        Toast.makeText(requireContext(), "¡Bienvenido, ${displayName ?: username}!", Toast.LENGTH_SHORT).show()
        navigateToVideoHomeSafely()
    }

    private fun clearPendingGoogleData() {
        pendingGoogleEmail = null
        pendingGoogleDisplayName = null
        pendingGoogleAvatarUrl = null
        pendingGoogleUsernameHint = null
    }

    private fun navigateToVideoHomeSafely() {
        if (!isAdded) return

        val navController = findNavController()
        val currentDestinationId = navController.currentDestination?.id

        if (currentDestinationId == R.id.videoHomeFragment) {
            Log.d(TAG, "Navigation to videoHomeFragment ignored because it is already the current destination")
            return
        }

        try {
            when (currentDestinationId) {
                R.id.loginFragment -> navController.navigate(R.id.action_loginFragment_to_videoHomeFragment)
                R.id.splashFragment -> navController.navigate(R.id.action_splashFragment_to_videoHomeFragment)
                else -> {
                    Log.w(TAG, "Unexpected current destination while navigating to videoHomeFragment: $currentDestinationId")
                    navController.navigate(
                        R.id.videoHomeFragment,
                        null,
                        androidx.navigation.navOptions {
                            launchSingleTop = true
                            popUpTo(R.id.nav_graph) {
                                inclusive = false
                            }
                        }
                    )
                }
            }
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Safe navigation to videoHomeFragment failed from destination=$currentDestinationId", e)
        }
    }
}