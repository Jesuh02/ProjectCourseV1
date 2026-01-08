package com.example.tareamov.ui
import kotlinx.coroutines.CoroutineExceptionHandler
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.tareamov.R
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.util.CertificateGenerator
import com.example.tareamov.util.AnimatedCertificateGenerator
import com.example.tareamov.util.CourseProgressManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import android.content.Intent
import android.net.Uri
import com.example.tareamov.service.SupabaseClient
import android.view.LayoutInflater
import com.example.tareamov.network.PSEBank
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.example.tareamov.network.PaymentApi
import com.example.tareamov.network.PaymentInitiationRequest
import com.google.android.material.textfield.TextInputEditText
import androidx.navigation.fragment.findNavController
import android.os.Bundle

// Instance of PaymentApi pointing to Backend
// CHANGE THIS TO TRUE IF RUNNING LOCALLY (Emulator), FALSE FOR PRODUCTION
private const val USE_LOCAL_ENV = false

private val paymentApi by lazy {
    // If running in Emulator, use http://10.0.2.2:3001/
    // If running on Device, use production URL.
    val baseUrl = if (USE_LOCAL_ENV) {
        "http://10.0.2.2:3001/"
    } else {
        "https://mcp-backenddeploy-production.up.railway.app/" 
    }
    android.util.Log.d("PaymentSetup", "Payment API initialized with URL: $baseUrl")
    PaymentApi.create(baseUrl)
}

/**
 * Extension function to initialize and load course progress for students
 * Call this from CourseDetailFragment's onCreateView or loadCourseDetails method
 */
fun Fragment.initializeAndLoadCourseProgress(
    courseId: Long,
    username: String?,
    userId: Long,
    isCurrentUserCreator: Boolean
) {
    // Find views
    val view = this.view ?: return
    val progressContainer = view.findViewById<LinearLayout>(R.id.courseProgressContainer) ?: return
    val progressBar = view.findViewById<ProgressBar>(R.id.courseProgressBar) ?: return
    // Support two layout variants: component_student_progress.xml and course_progress_view.xml
    val progressPercentTextView = view.findViewById<TextView>(R.id.progressPercentTextView)
        ?: view.findViewById<TextView>(R.id.courseProgressTextView)
        ?: run { android.util.Log.w("CourseDetailExt", "No progress percent TextView found in layout"); return }
    val progressStatusTextView = view.findViewById<TextView>(R.id.progressStatusTextView)
        ?: view.findViewById<TextView>(R.id.courseStatusTextView)
        ?: run { android.util.Log.w("CourseDetailExt", "No progress status TextView found in layout"); return }
    val certificateButtonContainer = view.findViewById<FrameLayout>(R.id.certificateButtonContainer)
    val certificateButton = view.findViewById<Button>(R.id.certificateButton)

    // Only show progress for students (non-creators) who are logged in
    if (isCurrentUserCreator || username == null || userId == -1L) {
        progressContainer.visibility = View.GONE
        return
    }

    // Initialize progress manager
    val progressManager = CourseProgressManager(requireContext())

    // Calculate and display progress
    viewLifecycleOwner.lifecycleScope.launch {
        val averageGrade = progressManager.calculateAndDisplayCourseProgress(
            courseId = courseId,
            userId = userId,
            progressContainer = progressContainer,
            progressBar = progressBar,
            progressPercentTextView = progressPercentTextView,
            progressStatusTextView = progressStatusTextView
        )

        if (!isAdded) return@launch

        if (progressContainer.visibility == View.VISIBLE) {
            val offset = resources.getDimensionPixelSize(R.dimen.edit_button_enter_offset).toFloat()
            progressContainer.alpha = 0f
            progressContainer.translationY = offset
            progressContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(520)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f))
                .start()
        }

        // Show certificate button if student passed the course (grade >= 6)
        if (averageGrade >= 6.0f) {
            certificateButtonContainer?.visibility = View.VISIBLE
            certificateButtonContainer?.alpha = 0f
            certificateButtonContainer?.translationY = resources.getDimensionPixelSize(R.dimen.edit_button_enter_offset).toFloat()
            certificateButtonContainer?.animate()
                ?.alpha(1f)
                ?.translationY(0f)
                ?.setDuration(480)
                ?.setInterpolator(android.view.animation.DecelerateInterpolator(1.6f))
                ?.start()
            certificateButton?.setOnClickListener {
                // Show dialog to choose certificate type
                showCertificateTypeDialog(requireContext(), courseId.toInt(), username, averageGrade)
            }
        } else {
            certificateButtonContainer?.visibility = View.GONE
            certificateButtonContainer?.alpha = 0f
        }
    }
}


/**
 * Extension function to check if a course is paid and handle payment functionality
 * This should be called from CourseDetailFragment after loading course details
 */
fun Fragment.handlePaidCourseAccess(
    courseId: Long,
    username: String?,
    isCurrentUserCreator: Boolean,
    onContentAccess: (Boolean) -> Unit
) {
    if (isCurrentUserCreator || username == null) {
        onContentAccess(true)
        return
    }

    val view = this.view ?: return
    val paymentButtonContainer = view.findViewById<FrameLayout>(R.id.paymentButtonContainer) ?: return
    val paymentButton = view.findViewById<Button>(R.id.paymentButton) ?: return
    val paymentDescriptionTextView = view.findViewById<TextView>(R.id.paymentDescriptionTextView)
    val topicsContainer = view.findViewById<LinearLayout>(R.id.topicsContainer)
    val noTopicsTextView = view.findViewById<TextView>(R.id.noTopicsTextView)
    val noTasksTextView = view.findViewById<TextView>(R.id.noTasksTextView)

    CoroutineScope(Dispatchers.Main).launch {
        try {
            val db = AppDatabase.getDatabase(requireContext())
            val courseDetails = withContext(Dispatchers.IO) {
                db.videoDao().getVideoById(courseId)
            }
            val isPaidCourse = courseDetails?.isPaid ?: false
            val coursePrice = courseDetails?.price ?: 0.0
            val courseName = courseDetails?.title ?: "Curso"

            if (!isPaidCourse) {
                paymentButtonContainer.visibility = View.GONE
                topicsContainer?.visibility = View.VISIBLE
                noTopicsTextView?.visibility = View.GONE
                noTasksTextView?.visibility = View.GONE
                onContentAccess(true)
                return@launch
            }

            // Update payment description with price
            paymentDescriptionTextView?.text = "Para acceder al contenido completo de este curso, es necesario realizar un pago de $${coursePrice}."

            // Show payment interface
            paymentButtonContainer.visibility = View.VISIBLE
            topicsContainer?.visibility = View.GONE
            noTopicsTextView?.visibility = View.GONE
            noTasksTextView?.visibility = View.GONE
            onContentAccess(false)

            paymentButton.setOnClickListener {
                showPaymentOptions(courseId, courseName, coursePrice, username) { result -> 
                       // Handle result if needed
                }
            }

        } catch (e: Exception) {
            Log.e("CourseDetail", "Error checking payment status: ${e.message}")
            Toast.makeText(requireContext(), "Error verificando el estado de pago: ${e.message}", Toast.LENGTH_LONG).show()
            paymentButtonContainer.visibility = View.GONE
            onContentAccess(true) // Allow access on error? Or keep restricted? Decide based on desired behavior.
        }
    }
}

/**
 * Navigate to Payment Form Fragment - PSE payment interface
 * This replaces the old dialog-based payment flow with a full-screen fragment
 */
fun Fragment.showPaymentOptions(
    courseId: Long,
    courseName: String,
    coursePrice: Double,
    username: String?,
    onPaymentResult: (Boolean) -> Unit
) {
    if (username == null) {
        Toast.makeText(requireContext(), "Debes iniciar sesión para pagar", Toast.LENGTH_SHORT).show()
        onPaymentResult(false)
        return
    }

    // Navigate to the Payment Form Fragment with arguments
    try {
        val bundle = Bundle().apply {
            putLong("courseId", courseId)
            putString("courseName", courseName)
            putFloat("coursePrice", coursePrice.toFloat())
            putString("username", username)
        }
        
        // Determine the correct action based on current destination
        val currentDestinationId = findNavController().currentDestination?.id
        val actionId = when (currentDestinationId) {
            R.id.exploreFragment -> R.id.action_exploreFragment_to_paymentFormFragment
            R.id.courseDetailFragment -> R.id.action_courseDetailFragment_to_paymentFormFragment
            else -> R.id.action_courseDetailFragment_to_paymentFormFragment // fallback
        }
        
        findNavController().navigate(actionId, bundle)
        // Note: onPaymentResult will be handled via navigation back stack or saved state
    } catch (e: Exception) {
        Log.e("PaymentNav", "Error navigating to payment form", e)
        Toast.makeText(requireContext(), "Error al abrir formulario de pago", Toast.LENGTH_SHORT).show()
        onPaymentResult(false)
    }
}


/**
 * Muestra un diálogo para seleccionar el tipo de certificado
 */
private fun showCertificateTypeDialog(context: android.content.Context, courseId: Int, username: String, averageGrade: Float) {
    AlertDialog.Builder(context)
        .setTitle("Tipo de Certificado")
        .setMessage("Selecciona el tipo de certificado que deseas generar:")
        .setPositiveButton("🎨 Certificado Animado (HTML)") { _, _ ->
            generateCertificate(context, courseId, username, averageGrade, animated = true)
        }
        .setNegativeButton("📄 Certificado PDF Estático") { _, _ ->
            generateCertificate(context, courseId, username, averageGrade, animated = false)
        }
        .setNeutralButton("Cancelar", null)
        .show()
}

private fun generateCertificate(context: android.content.Context, courseId: Int, username: String, averageGrade: Float, animated: Boolean) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val db = AppDatabase.getDatabase(context)
            val courseDetails = db.videoDao().getVideoById(courseId.toLong())
            val creatorUsername = courseDetails?.username ?: ""
            val courseName = courseDetails?.title ?: "Curso"
            val topics = db.topicDao().getTopicsByCourse(courseId.toLong())
            val courseTopic = if (topics.isNotEmpty()) topics[0].name else "General"
            withContext(Dispatchers.Main) {
                if (animated) {
                    AnimatedCertificateGenerator.generateAnimatedCertificate(
                        context,
                        username,
                        creatorUsername,
                        courseName,
                        courseTopic,
                        String.format("%.1f", averageGrade),
                        courseId.toLong()
                    )
                } else {
                    CertificateGenerator.generateCertificate(
                        context,
                        username,
                        creatorUsername,
                        courseName,
                        courseTopic,
                        String.format("%.1f", averageGrade),
                        courseId.toLong()
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("CourseDetail", "Error generating certificate", e)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Error al generar certificado: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}