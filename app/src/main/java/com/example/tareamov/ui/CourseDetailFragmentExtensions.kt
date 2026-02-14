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
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult
import com.example.tareamov.util.CertificateGenerator
import com.example.tareamov.util.AnimatedCertificateGenerator
import com.example.tareamov.util.CourseProgressManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.animation.ObjectAnimator
import androidx.appcompat.app.AlertDialog
import org.json.JSONObject
import android.content.Intent
import android.net.Uri
// BackendApiService replaces SupabaseClient
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
        com.example.tareamov.BuildConfig.BACKEND_URL.let { if (it.endsWith("/")) it else "$it/" } 
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
    if (isCurrentUserCreator || username == null) {
        progressContainer.visibility = View.GONE
        return
    }

    // Initialize progress manager
    val progressManager = CourseProgressManager(requireContext())

    // Calculate and display progress
    viewLifecycleOwner.lifecycleScope.launch {
        val effectiveUserId = if (userId > 0L) {
            userId
        } else {
            val userResult = withContext(Dispatchers.IO) {
                BackendApiService.getUserByUsername(username)
            }
            (userResult as? ApiResult.Success)?.data?.id ?: -1L
        }

        if (effectiveUserId <= 0L) {
            Log.w("CourseDetailExt", "No se pudo resolver userId para progreso. username=$username")
            progressContainer.visibility = View.VISIBLE
            progressBar.progress = 0
            progressPercentTextView.text = "0% completado"
            progressStatusTextView.text = "Calificación: 0/10"
            progressStatusTextView.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            certificateButtonContainer?.visibility = View.GONE
            certificateButtonContainer?.alpha = 0f
            return@launch
        }

        val progressState = progressManager.calculateAndDisplayCourseProgress(
            courseId = courseId,
            userId = effectiveUserId,
            progressContainer = progressContainer,
            progressBar = progressBar,
            progressPercentTextView = progressPercentTextView,
            progressStatusTextView = progressStatusTextView
        )

        val targetProgress = progressState.progressPercent.coerceIn(0, 100)
        val currentProgress = progressBar.progress.coerceIn(0, 100)
        val startProgress = if (targetProgress == currentProgress) 0 else currentProgress
        progressBar.progress = startProgress
        ObjectAnimator.ofInt(progressBar, "progress", startProgress, targetProgress).apply {
            duration = 650L
            start()
        }

        if (progressContainer.visibility != View.VISIBLE) {
            progressContainer.visibility = View.VISIBLE
            progressBar.progress = 0
            progressPercentTextView.text = "0% completado"
            progressStatusTextView.text = "Calificación: 0/10"
            progressStatusTextView.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
        }

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
        if (progressState.canDownloadCertificate) {
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
                showCertificateTypeDialog(requireContext(), courseId.toInt(), username, progressState.averageGrade)
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
 * UPDATED: Always allow content access since if user reached CourseDetailFragment, they have access
 */
fun Fragment.handlePaidCourseAccess(
    courseId: Long,
    username: String?,
    isCurrentUserCreator: Boolean,
    onContentAccess: (Boolean) -> Unit
) {
    // Always allow content access - if user reached this fragment, they have access
    onContentAccess(true)
    
    val view = this.view ?: return
    val paymentButtonContainer = view.findViewById<FrameLayout>(R.id.paymentButtonContainer) ?: return
    val topicsContainer = view.findViewById<LinearLayout>(R.id.topicsContainer)
    val noTopicsTextView = view.findViewById<TextView>(R.id.noTopicsTextView)
    val noTasksTextView = view.findViewById<TextView>(R.id.noTasksTextView)

    // Always hide payment UI and show content
    paymentButtonContainer.visibility = View.GONE
    topicsContainer?.visibility = View.VISIBLE
    noTopicsTextView?.visibility = View.GONE
    noTasksTextView?.visibility = View.GONE
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
            val userResult = BackendApiService.getUserByUsername(username)
            if (userResult is ApiResult.Success) userResult.data?.id else null
        }

        if (actualUserId == null || actualUserId == -1L) {
             Toast.makeText(context, "Error: No se pudo identificar al usuario", Toast.LENGTH_SHORT).show()
             onPaymentResult(false)
             return@launch
        }
        
        // Show custom payment dialog with new style
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_initiating_payment, null)
        val customDialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .setCancelable(false)
            .create()
            
        // Set transparent background
        customDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            
        // Configure dialog views
        val titleView = dialogView.findViewById<TextView>(R.id.paymentStatusTitle)
        val messageView = dialogView.findViewById<TextView>(R.id.paymentStatusMessage)
        val courseNameView = dialogView.findViewById<TextView>(R.id.courseNameText)
        val coursePriceView = dialogView.findViewById<TextView>(R.id.coursePriceText)
        val cancelButton = dialogView.findViewById<TextView>(R.id.cancelPaymentButton)
        val confirmButton = dialogView.findViewById<TextView>(R.id.confirmPaymentButton)
        
        titleView.text = "Iniciando Proceso de Pago"
        courseNameView.text = courseName
        coursePriceView.text = "Obteniendo precio..."
        messageView.text = "Conectando con Wompi..."
        confirmButton.text = "Iniciando..."
        confirmButton.isEnabled = false
        
        // Set cancel button functionality
        var isCancelled = false
        cancelButton.setOnClickListener {
            isCancelled = true
            customDialog.dismiss()
            onPaymentResult(false)
        }
        
        customDialog.show()

        try {
            // 2. Initiate Payment on Backend
            messageView.text = "Procesando información del curso..."
            coursePriceView.text = "Obteniendo precio desde BD..."
            
            // Amount will be fetched from courses table on backend for security
            val request = PaymentInitiationRequest(
                courseId = courseId,
                userId = actualUserId,
                amount = 0.0, // Will be ignored, amount fetched from DB
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

            if (isCancelled) return@launch

            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                val url = body.urlBankPayment
                val reference = body.transactionId // This is the transaction reference

                if (url.isNullOrEmpty() || reference.isNullOrEmpty()) {
                     customDialog.dismiss()
                     Toast.makeText(context, "Error: El servidor no devolvió la URL de pago", Toast.LENGTH_SHORT).show()
                     onPaymentResult(false)
                     return@launch
                }
                
                // 3. Redirect automatically to payment URL and hide confirm button
                confirmButton.visibility = View.GONE
                messageView.text = "Abriendo página de pago...\nRegresa aquí después de completar el pago"
                coursePriceView.text = "Precio verificado desde BD"
                
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                    
                    // Update dialog for polling
                    messageView.text = "Esperando confirmación del pago...\nRegresa aquí después de pagar"
                    
                    // Start polling immediately
                    startPaymentPolling(reference, customDialog, messageView, onPaymentResult) { cancelled ->
                        isCancelled = cancelled
                    }
                    
                } catch (e: Exception) {
                    customDialog.dismiss()
                    Toast.makeText(context, "No se pudo abrir el navegador: ${e.message}", Toast.LENGTH_LONG).show()
                    onPaymentResult(false)
                }
                
            } else {
                 customDialog.dismiss()
                 val msg = response.body()?.message ?: "Error desconocido del servidor"
                 Toast.makeText(context, "Error al iniciar pago: $msg", Toast.LENGTH_SHORT).show()
                 onPaymentResult(false)
            }

        } catch (e: Exception) {
            customDialog.dismiss()
            Log.e("PaymentFlow", "Exception", e)
            Toast.makeText(context, "Error de conexión: ${e.message}", Toast.LENGTH_SHORT).show()
            onPaymentResult(false)
        }
    }
}

/**
 * Separate function to handle payment polling with custom dialog
 * SECURITY: This function only READS the transaction status from backend.
 * The status is only updated to 'successful' by the Wompi webhook after actual payment.
 * This prevents premature approval when user just opens the payment link.
 */
private fun Fragment.startPaymentPolling(
    reference: String,
    dialog: AlertDialog,
    messageView: TextView,
    onPaymentResult: (Boolean) -> Unit,
    onCancelStateChange: (Boolean) -> Unit
) {
    viewLifecycleOwner.lifecycleScope.launch {
        var isApproved = false
        var attempts = 0
        val maxAttempts = 60 // 5 minutes approx (5s interval)
        
        while (attempts < maxAttempts && dialog.isShowing) {
            kotlinx.coroutines.delay(5000) // Wait 5 seconds
            
            if (!dialog.isShowing) {
                onCancelStateChange(true)
                return@launch
            }
            
            // Query transaction status (read-only, doesn't modify anything)
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
                         dialog.dismiss()
                         Toast.makeText(requireContext(), "El pago fue rechazado. Intenta nuevamente.", Toast.LENGTH_LONG).show()
                         onPaymentResult(false)
                     }
                     return@launch
                }
                // If 'pending', continue loop
            }
            
            // Update message with attempt count
            val timeRemaining = (maxAttempts - attempts) * 5 / 60
            messageView.text = "Verificando pago...\nTiempo restante: ${timeRemaining}min"
            attempts++
        }
        
        dialog.dismiss()
        
        if (isApproved) {
             // Payment Success!
             Toast.makeText(requireContext(), "¡Pago exitoso! Acceso desbloqueado.", Toast.LENGTH_LONG).show()
             onPaymentResult(true)
        } else if (attempts >= maxAttempts) {
             Toast.makeText(requireContext(), "No se detectó el pago a tiempo. Si pagaste, contacta soporte.", Toast.LENGTH_LONG).show()
             onPaymentResult(false)
        }
    }
}


/**
 * Muestra un diálogo para seleccionar el tipo de certificado
 */
private fun showCertificateTypeDialog(context: android.content.Context, courseId: Int, username: String, averageGrade: Float) {
    val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_certificate_selection, null)
    
    val dialog = AlertDialog.Builder(context)
        .setView(dialogView)
        .setCancelable(true)
        .create()

    // Transparent background to show the rounded CardView properly
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

    dialogView.findViewById<View>(R.id.btnAnimatedCertificate).setOnClickListener {
        dialog.dismiss()
        generateCertificate(context, courseId, username, averageGrade, animated = true)
    }

    dialogView.findViewById<View>(R.id.btnStaticCertificate).setOnClickListener {
        dialog.dismiss()
        generateCertificate(context, courseId, username, averageGrade, animated = false)
    }

    dialogView.findViewById<View>(R.id.btnCancelCertificate).setOnClickListener {
        dialog.dismiss()
    }

    dialog.show()
}

private fun generateCertificate(context: android.content.Context, courseId: Int, username: String, averageGrade: Float, animated: Boolean) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val courseResult = BackendApiService.getCourseById(courseId.toLong())
            val courseData = if (courseResult is ApiResult.Success) courseResult.data else null
            val creatorUsername = courseData?.creatorUserId?.let { uid ->
                val uResult = BackendApiService.getUserById(uid)
                if (uResult is ApiResult.Success) uResult.data?.usuario ?: "" else ""
            } ?: ""
            val courseName = courseData?.title ?: "Curso"
            val topicsResult = BackendApiService.getTopicsByCourse(courseId.toLong())
            val topics = if (topicsResult is ApiResult.Success) topicsResult.data ?: emptyList() else emptyList()
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