package com.example.tareamov.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.os.Bundle
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
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
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.tareamov.MainActivity
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
// Add this import for Usuario
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.util.SessionManager
import com.example.tareamov.viewmodel.AuthViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginFragment : Fragment() {
    private lateinit var usernameEditText: EditText
    private lateinit var passwordEditText: EditText
    private lateinit var loginButton: Button
    private lateinit var registerButton: Button
    private lateinit var goToRegisterPersonaTextView: TextView
    private lateinit var authViewModel: AuthViewModel
    private lateinit var sessionManager: SessionManager
    private lateinit var courseTitleText: TextView
    private lateinit var profileIcon: ImageView
    private lateinit var codeIcon: ImageView
    private lateinit var particle1: View
    private lateinit var particle2: View
    private lateinit var particle3: View

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_login, container, false)

        usernameEditText = view.findViewById(R.id.usernameEditText)
        passwordEditText = view.findViewById(R.id.passwordEditText)
        loginButton = view.findViewById(R.id.loginButton)
        registerButton = view.findViewById(R.id.registerButton)
        goToRegisterPersonaTextView = view.findViewById(R.id.goToRegisterPersonaTextView)
        
        // Initialize new views
        courseTitleText = view.findViewById(R.id.courseTitleText)
        profileIcon = view.findViewById(R.id.profileIcon)
        codeIcon = view.findViewById(R.id.codeIcon)
        particle1 = view.findViewById(R.id.particle1)
        particle2 = view.findViewById(R.id.particle2)
        particle3 = view.findViewById(R.id.particle3)

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
}