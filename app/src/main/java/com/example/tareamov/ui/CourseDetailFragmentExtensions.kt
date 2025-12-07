package com.example.tareamov.ui
// ... existing imports ...
import kotlinx.coroutines.CoroutineExceptionHandler
import com.example.tareamov.network.PaymentApi
import com.example.tareamov.network.PseTransactionRequest
import com.example.tareamov.network.Payer
import com.example.tareamov.network.Payment
import com.example.tareamov.network.Amount
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import com.example.tareamov.util.SessionManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.create
import com.google.gson.Gson
import java.io.IOException
import androidx.appcompat.app.AlertDialog

// Add Retrofit service instance at the top level of the file
private val paymentApi by lazy {
  // Replace with your actual PayU Latam Sandbox/Production credentials
  val baseUrl = "https://sandbox.api.payulatam.com/" // Use production URL for production
  val apiKey = "TU_API_KEY_PSE" // Replace with your PayU API Key
  val apiLogin = "TU_API_LOGIN_PSE" // Replace with your PayU API Login
  val merchantId = "TU_MERCHANT_ID" // Replace with your PayU Merchant ID
  PaymentApi.create(baseUrl, apiKey, apiLogin, merchantId)
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

            // Since purchase functionality is disabled, show content directly
            paymentButtonContainer.visibility = View.GONE
            topicsContainer?.visibility = View.VISIBLE
            noTopicsTextView?.visibility = View.GONE
            noTasksTextView?.visibility = View.GONE
            onContentAccess(true)

        } catch (e: Exception) {
            Log.e("CourseDetail", "Error checking payment status: ${e.message}")
            Toast.makeText(requireContext(), "Error verificando el estado de pago: ${e.message}", Toast.LENGTH_LONG).show()
            paymentButtonContainer.visibility = View.GONE
            onContentAccess(true) // Allow access on error? Or keep restricted? Decide based on desired behavior.
        }
    }
}

/**
 * Muestra un diálogo con opciones de pago (PSE o Nequi)
 */
// Change visibility from private to public
fun Fragment.showPaymentOptions(
    courseId: Long,
    courseName: String,
    username: String?,
    onPaymentResult: (Boolean) -> Unit
) {
    if (username == null) {
        Toast.makeText(requireContext(), "Debes iniciar sesión para pagar.", Toast.LENGTH_SHORT).show()
        onPaymentResult(false)
        return
    }

    val options = arrayOf("Pagar con NEQUI", "Pagar con PSE")

    androidx.appcompat.app.AlertDialog.Builder(requireContext())
        .setTitle("Selecciona un método de pago")
        .setItems(options) { dialog, which ->
            when (which) {
                0 -> initiateNequiPayment(courseId, courseName, username, onPaymentResult) // Correct call to initiateNequiPayment
                1 -> initiatePSEPayment(courseId, courseName, username, onPaymentResult) // Correct call to initiatePSEPayment
            }
            dialog.dismiss()
        }
        .setNegativeButton("Cancelar") { dialog, _ ->
            dialog.dismiss()
            onPaymentResult(false)
        }
        .show()
}

// NEQUI payment integration (simplified for demo) - Keep this as is if it's working
private fun Fragment.initiateNequiPayment(
    courseId: Long,
    courseName: String,
    username: String,
    onPaymentResult: (Boolean) -> Unit
) {
    val context = requireContext()
    val phoneNumber = "3053048316" // Example phone number
    val amount = "5000" // Example amount
    val clientId = "TU_CLIENT_ID" // Replace with your NEQUI Client ID
    val clientSecret = "TU_CLIENT_SECRET" // Replace with your NEQUI Client Secret
    val subscriptionKey = "TU_SUBSCRIPTION_KEY" // Replace with your NEQUI Subscription Key
    val transactionId = "TXN${System.currentTimeMillis()}"

    val client = OkHttpClient()
    val requestBody = """
        {
          "phoneNumber": "$phoneNumber",
          "value": "$amount",
          "message": "Pago desde la app móvil",
          "transactionId": "$transactionId"
        }
    """.trimIndent()

    val mediaType = "application/json".toMediaType()
    val request = Request.Builder()
        .url("https://api.nequi.com.co/payment/transaction") // Replace with actual NEQUI API URL
        .addHeader("Content-Type", "application/json")
        .addHeader("Authorization", "Bearer TU_TOKEN_DE_AUTENTICACION") // Replace with actual token
        .addHeader("x-api-key", subscriptionKey)
        .post(create(mediaType, requestBody))
        .build()

    // Show loading dialog
    val loadingDialog = androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("Procesando pago")
        .setMessage("Conectando con NEQUI...")
        .setCancelable(false)
        .create()
    loadingDialog.show()

    client.newCall(request).enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            loadingDialog.dismiss()
            requireActivity().runOnUiThread {
                Toast.makeText(context, "Error en la solicitud NEQUI: ${e.message}", Toast.LENGTH_LONG).show()
                onPaymentResult(false)
            }
        }

        override fun onResponse(call: Call, response: Response) {
            loadingDialog.dismiss()
            if (response.isSuccessful) {
                // Assuming NEQUI response indicates success and provides necessary info
                // You would parse the response body here to confirm success
                // For demo, we'll assume success and record purchase
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Purchase functionality removed - just show success
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "¡Pago NEQUI exitoso! Ahora tienes acceso completo al curso.", Toast.LENGTH_LONG).show()
                            onPaymentResult(true)
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(context, "Error al procesar el pago NEQUI: ${e.message}", Toast.LENGTH_LONG).show()
                            onPaymentResult(false)
                        }
                    }
                }
            } else {
                requireActivity().runOnUiThread {
                    // Parse error body for more details if available
                    val errorBody = response.body?.string() ?: response.message
                    Toast.makeText(context, "Error en la respuesta NEQUI: ${response.code} - $errorBody", Toast.LENGTH_LONG).show()
                    onPaymentResult(false)
                }
            }
        }
    })
}


/**
 * PSE payment integration (This is the correct one called by showPaymentOptions)
 */
fun Fragment.initiatePSEPayment( // Keep this public
    courseId: Long,
    courseName: String,
    username: String,
    onPaymentResult: (Boolean) -> Unit
) {
    val context = requireContext()
    val amount = "5000" // Use actual course price if available
    // Credentials are now handled by the paymentApi lazy delegate

    val transactionId = "PSE${System.currentTimeMillis()}"

    // Mostrar diálogo para recopilar información bancaria
    val bankOptions = arrayOf(
        "Bancolombia", "Banco de Bogotá", "Davivienda",
        "BBVA", "Banco de Occidente", "Banco Popular"
        // Add more banks as needed
    )

    val documentTypeOptions = arrayOf(
        "Cédula de Ciudadanía", "Cédula de Extranjería",
        "Pasaporte", "NIT"
    )

    // Primero seleccionar el banco
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("Selecciona tu banco")
        .setItems(bankOptions) { _, bankIndex ->
            // Luego seleccionar el tipo de documento
            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle("Tipo de documento")
                .setItems(documentTypeOptions) { _, docTypeIndex ->
                    // Mostrar formulario para datos adicionales
                    showPSEDataForm(
                        context,
                        bankOptions[bankIndex],
                        documentTypeOptions[docTypeIndex],
                        courseId,
                        courseName,
                        username,
                        amount,
                        transactionId,
                        onPaymentResult
                    )
                }
                .setNegativeButton("Cancelar") { dialog, _ ->
                    dialog.dismiss()
                    onPaymentResult(false)
                }
                .show()
        }
        .setNegativeButton("Cancelar") { dialog, _ ->
            dialog.dismiss()
            onPaymentResult(false)
        }
        .show()
}

/**
 * Muestra un formulario para recopilar datos adicionales para el pago PSE
 */
private fun Fragment.showPSEDataForm(
    context: android.content.Context,
    bankName: String,
    documentType: String,
    courseId: Long,
    courseName: String,
    username: String,
    amount: String,
    transactionId: String,
    onPaymentResult: (Boolean) -> Unit
) {
    // Crear un formulario dinámico para los datos del usuario
    val layout = LinearLayout(context)
    layout.orientation = LinearLayout.VERTICAL
    layout.setPadding(50, 30, 50, 30)

    // Campo para el número de documento
    val documentNumberInput = EditText(context)
    documentNumberInput.hint = "Número de documento"
    documentNumberInput.inputType = android.text.InputType.TYPE_CLASS_NUMBER
    layout.addView(documentNumberInput)

    // Campo para el correo electrónico
    val emailInput = EditText(context)
    emailInput.hint = "Correo electrónico"
    emailInput.inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
    layout.addView(emailInput)

    // Mostrar el formulario
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("Datos para pago PSE")
        .setView(layout)
        .setPositiveButton("Continuar") { _, _ ->
            val documentNumber = documentNumberInput.text.toString()
            val email = emailInput.text.toString()

            if (documentNumber.isBlank() || email.isBlank()) {
                Toast.makeText(context, "Por favor completa todos los campos", Toast.LENGTH_SHORT).show()
                onPaymentResult(false)
                return@setPositiveButton
            }

            // Procesar el pago PSE con los datos recopilados
            processPSEPayment(
                context,
                bankName,
                documentType,
                documentNumber,
                email,
                courseId,
                courseName,
                username,
                amount,
                transactionId,
                onPaymentResult
            )
        }
        .setNegativeButton("Cancelar") { dialog, _ ->
            dialog.dismiss()
            onPaymentResult(false)
        }
        .show()
}

/**
 * Procesa el pago PSE con los datos recopilados usando Retrofit
 */
private fun Fragment.processPSEPayment(
    context: android.content.Context,
    bankName: String,
    documentType: String,
    documentNumber: String,
    email: String,
    courseId: Long,
    courseName: String,
    username: String,
    amount: String,
    transactionId: String,
    onPaymentResult: (Boolean) -> Unit
) {
    val loading = androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("Procesando pago PSE")
        .setMessage("Por favor espera…")
        .setCancelable(false)
        .create().also { it.show() }

    val handler = CoroutineExceptionHandler { _, throwable ->
        loading.dismiss()
        Log.e("PSEPayment", "Coroutine Error", throwable)
        Toast.makeText(context, "Error en el proceso de pago: ${throwable.localizedMessage}", Toast.LENGTH_LONG).show()
        onPaymentResult(false)
    }

    CoroutineScope(Dispatchers.IO).launch(handler) {
        try {
            val req = PseTransactionRequest(
                bankCode = bankCodeMapping(bankName),
                returnURL = "https://tudominio.com/pse/return", // Replace with your actual return URL
                reference = "curso_$courseId",
                description = "Acceso al curso $courseName",
                payer = Payer(
                    documentType = mapDocType(documentType),
                    document = documentNumber,
                    name = username, // Assuming username is the payer's name
                    surname = "", // You might need to collect surname
                    emailAddress = email
                ),
                payment = Payment(
                    reference = "curso_$courseId",
                    description = "Acceso al curso $courseName",
                    amount = Amount(total = amount.toDoubleOrNull() ?: 0.0) // Convert amount to Double
                ),
                ipAddress = "127.0.0.1", // Replace with real IP if possible
                userAgent = "AndroidApp"
            )

            // Replace with your actual Authorization token and Merchant ID
            val authorizationToken = "Bearer TU_TOKEN_DE_AUTENTICACION_PSE" // Replace with your actual token
            val merchantId = "TU_MERCHANT_ID" // Replace with your actual Merchant ID

            val response = paymentApi.createPseTransaction(
                authorization = authorizationToken,
                merchantId = merchantId,
                request = req
            )

            withContext(Dispatchers.Main) {
                loading.dismiss()
                if (response.isSuccessful) {
                    val url = response.body()?.transactionResponse?.urlBankPayment
                    if (!url.isNullOrBlank()) {
                        // Open the bank payment URL in a browser or WebView
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                        context.startActivity(intent)

                        // Purchase functionality removed - just show success after redirect
                         CoroutineScope(Dispatchers.IO).launch {
                            try {
                                withContext(Dispatchers.Main) {
                                     Toast.makeText(context, "Redirigiendo a tu banco para completar el pago. La compra se registrará si el pago es exitoso.", Toast.LENGTH_LONG).show()
                                    onPaymentResult(true) // Indicate success for UI update (like hiding payment button)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Error al procesar el pago PSE: ${e.message}", Toast.LENGTH_LONG).show()
                                    // Still indicate success for UI update, but log the error
                                    Log.e("PSEPayment", "Error processing payment", e)
                                    onPaymentResult(true)
                                }
                            }
                        }

                    } else {
                        Toast.makeText(context, "No se pudo obtener la URL de pago de PSE.", Toast.LENGTH_LONG).show()
                        onPaymentResult(false)
                    }
                } else {
                    // Handle API errors (e.g., invalid credentials, invalid request)
                    val errorBody = response.errorBody()?.string() ?: response.message()
                    Log.e("PSEPayment", "API Error: ${response.code()} - $errorBody")
                    Toast.makeText(context, "Error en la respuesta de PSE: ${response.code()} - $errorBody", Toast.LENGTH_LONG).show()
                    onPaymentResult(false)
                }
            }
        } catch (e: Exception) {
            // Handle network errors or other exceptions during the process
            withContext(Dispatchers.Main) {
                loading.dismiss()
                Log.e("PSEPayment", "Processing Error", e)
                Toast.makeText(context, "Error al procesar el pago PSE: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                onPaymentResult(false)
            }
        }
    }
}

// Helper functions for mapping
private fun bankCodeMapping(bankName: String): String {
    return when (bankName) {
        "Bancolombia" -> "1022"
        "Banco de Bogotá" -> "1001"
        "Davivienda" -> "1051"
        "BBVA" -> "1013"
        "Banco de Occidente" -> "1007"
        "Banco Popular" -> "1006"
        // Add more bank mappings based on PayU documentation
        else -> "1022" // Default or error code
    }
}

private fun mapDocType(docType: String): String {
    return when (docType) {
        "Cédula de Ciudadanía" -> "CC"
        "Cédula de Extranjería" -> "CE"
        "Pasaporte" -> "PP"
        "NIT" -> "NIT"
        // Add more document type mappings
        else -> "CC" // Default or error code
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