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

            // Check if user has access (is enrolled/pain)
            val userId = withContext(Dispatchers.IO) {
                com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(username)
            }

            val hasAccess = if (userId != null) {
                val localAccess = db.progresoEstudianteDao().getProgreso(userId, courseId) != null
                localAccess // Ideally we would check remote too, but local is cache
            } else false

            if (hasAccess) {
                paymentButtonContainer.visibility = View.GONE
                topicsContainer?.visibility = View.VISIBLE
                noTopicsTextView?.visibility = View.GONE
                noTasksTextView?.visibility = View.GONE
                onContentAccess(true)
            } else {
                paymentButtonContainer.visibility = View.VISIBLE
                topicsContainer?.visibility = View.GONE
                // Hide content
                onContentAccess(false)
                
                paymentButton.setOnClickListener {
                    // Pass userId if available, else standard fallback
                    showPaymentOptions(courseId, courseName, coursePrice, username, userId ?: -1L) { success ->
                        if (success) {
                            // On success, hide payment button and show content immediately
                            paymentButtonContainer.visibility = View.GONE
                            topicsContainer?.visibility = View.VISIBLE
                            noTopicsTextView?.visibility = View.GONE // Reset this
                            onContentAccess(true)
                            
                            // Also refresh activity/fragment state using Supabase check if possible
                            // For now, UI update is enough (optimistic)
                        }
                    }
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
 * Navigate to Wompi Payment URL and poll for status
 * This replaces the old dialog/fragment flow with a direct URL + Polling approach
 */
fun Fragment.showPaymentOptions(
    courseId: Long,
    courseName: String,
    coursePrice: Double,
    username: String?,
    userId: Long = -1L,
    onPaymentResult: (Boolean) -> Unit
) {
    if (username == null) {
        Toast.makeText(requireContext(), "Debes iniciar sesión para pagar", Toast.LENGTH_SHORT).show()
        onPaymentResult(false)
        return
    }

    val context = requireContext()
    
    // Launch within lifecycle scope
    viewLifecycleOwner.lifecycleScope.launch {
        
        // 1. Resolve User ID if not provided
        val actualUserId = if (userId != -1L) userId else withContext(Dispatchers.IO) {
            com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(username)
        }

        if (actualUserId == null || actualUserId == -1L) {
             Toast.makeText(context, "Error: No se pudo identificar al usuario", Toast.LENGTH_SHORT).show()
             onPaymentResult(false)
             return@launch
        }
        
        // Show loading dialog
        val progressDialog = AlertDialog.Builder(context)
            .setTitle("Iniciando pago")
            .setMessage("Conectando con Wompi...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        try {
            // 2. Initiate Payment on Backend
            // We use dummy values for personal info as Wompi Web Checkout collects them
            val request = PaymentInitiationRequest(
                courseId = courseId,
                userId = actualUserId,
                amount = coursePrice,
                bankCode = "0", 
                payerEmail = "user@example.com", // Wompi will ask for this
                payerName = username,
                payerDocType = "CC",
                payerDocNumber = "0",
                ipAddress = "127.0.0.1", // Backend handles real IP
                userAgent = "AndroidApp"
            )
            
            val response = withContext(Dispatchers.IO) {
                paymentApi.initiatePayment(request)
            }

            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                val url = body.urlBankPayment
                val reference = body.transactionId // This is the transaction reference

                if (url.isNullOrEmpty() || reference.isNullOrEmpty()) {
                     progressDialog.dismiss()
                     Toast.makeText(context, "Error: El servidor no devolvió la URL de pago", Toast.LENGTH_SHORT).show()
                     onPaymentResult(false)
                     return@launch
                }
                
                // 3. Open Wompi in Browser
                progressDialog.setMessage("Abriendo navegador...\nPor favor completa el pago y regresa aquí.")
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: Exception) {
                    progressDialog.dismiss()
                    Toast.makeText(context, "No se pudo abrir el navegador: ${e.message}", Toast.LENGTH_LONG).show()
                    onPaymentResult(false)
                    return@launch
                }
                
                // 4. Polling Loop
                progressDialog.setMessage("Esperando confirmación del pago...\nNo cierres esta ventana.")
                progressDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancelar") { d, _ -> 
                    d.dismiss()
                    onPaymentResult(false) // User cancelled waiting
                }
                // Update dialog to show cancellation option
                progressDialog.show() 
                
                var isApproved = false
                var attempts = 0
                val maxAttempts = 60 // 5 minutes approx (5s interval)
                
                while (attempts < maxAttempts && progressDialog.isShowing) {
                    kotlinx.coroutines.delay(5000) // Wait 5 seconds
                    
                    val statusCheck = withContext(Dispatchers.IO) {
                        try {
                            paymentApi.getTransactionStatus(reference)
                        } catch(e: Exception) { null }
                    }
                    
                    if (statusCheck?.isSuccessful == true) {
                        val status = statusCheck.body()?.status?.lowercase()
                        Log.d("PaymentPoll", "Reference: $reference, Status: $status")
                        
                        if (status == "successful" || status == "approved") {
                            isApproved = true
                            break
                        } else if (status == "failed" || status == "rejected" || status == "declined" || status == "voided") {
                             withContext(Dispatchers.Main) {
                                 Toast.makeText(context, "El pago fue rechazado. Intenta nuevamente.", Toast.LENGTH_LONG).show()
                             }
                             break
                        }
                        // If 'pending', continue loop
                    }
                    attempts++
                }
                
                progressDialog.dismiss()
                
                if (isApproved) {
                     // Payment Success!
                     // Ideally, refresh permissions or database here if not handled by webhook latency
                     Toast.makeText(context, "¡Pago exitoso! Acceso desbloqueado.", Toast.LENGTH_LONG).show()
                     onPaymentResult(true)
                } else if (attempts >= maxAttempts) {
                     Toast.makeText(context, "No se detectó el pago a tiempo. Si pagaste, contacta soporte.", Toast.LENGTH_LONG).show()
                     onPaymentResult(false)
                }
                
            } else {
                 progressDialog.dismiss()
                 val msg = response.body()?.message ?: "Error desconocido del servidor"
                 Toast.makeText(context, "Error al iniciar pago: $msg", Toast.LENGTH_SHORT).show()
                 onPaymentResult(false)
            }

        } catch (e: Exception) {
            progressDialog.dismiss()
            Log.e("PaymentFlow", "Exception", e)
            Toast.makeText(context, "Error de conexión: ${e.message}", Toast.LENGTH_SHORT).show()
            onPaymentResult(false)
        }
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