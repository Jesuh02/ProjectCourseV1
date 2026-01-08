package com.example.tareamov.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.network.PaymentApi
import com.example.tareamov.network.PaymentInitiationRequest
import com.example.tareamov.service.SupabaseClient
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Payment Form Fragment - Minimalist PSE Payment interface
 * Features: Searchable bank list, smooth animations, clean UI
 */
class PaymentFormFragment : Fragment() {

    // Navigation arguments (from Bundle)
    private var argCourseId: Long = -1L
    private var argCourseName: String = ""
    private var argCoursePrice: Float = 0f
    private var argUsername: String = ""
    
    // UI Elements
    private lateinit var btnBack: ImageButton
    private lateinit var tvCourseName: TextView
    private lateinit var tvCoursePrice: TextView
    private lateinit var inputBank: AutoCompleteTextView
    private lateinit var inputDocType: AutoCompleteTextView
    private lateinit var inputDocNumber: TextInputEditText
    private lateinit var inputPhone: TextInputEditText
    private lateinit var btnPay: MaterialButton
    private lateinit var btnCancel: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var paymentCard: MaterialCardView
    
    // Header elements for animation
    private lateinit var paymentIcon: View
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    
    // Section containers for animations
    private lateinit var courseSummaryContainer: View
    private lateinit var bankSectionTitle: TextView
    private lateinit var bankInputLayout: TextInputLayout
    private lateinit var docSectionTitle: TextView
    private lateinit var documentContainer: View
    private lateinit var phoneLayout: TextInputLayout
    private lateinit var securityNotice: View
    
    // Particles
    private lateinit var particle1: View
    private lateinit var particle2: View
    private lateinit var particle3: View

    // Payment API
    private val paymentApi by lazy {
        val baseUrl = "https://mcp-backenddeploy-production.up.railway.app/"
        PaymentApi.create(baseUrl)
    }

    // Complete Bank List (Colombia PSE)
    data class Bank(val code: String, val name: String)
    
    private val allBanks = listOf(
        Bank("1001", "Banco de Bogotá"),
        Bank("1002", "Banco Popular"),
        Bank("1006", "Itaú"),
        Bank("1014", "Itaú (antes Corpbanca)"),
        Bank("1007", "Bancolombia"),
        Bank("1009", "Citibank"),
        Bank("1012", "Banco GNB Sudameris"),
        Bank("1013", "BBVA Colombia"),
        Bank("1019", "Scotiabank Colpatria"),
        Bank("1023", "Banco de Occidente"),
        Bank("1031", "Bancoldex S.A."),
        Bank("1032", "Banco Caja Social"),
        Bank("1040", "Banco Agrario"),
        Bank("1047", "Banco Mundo Mujer"),
        Bank("1051", "Davivienda"),
        Bank("1052", "Banco AV Villas"),
        Bank("1053", "Banco W S.A"),
        Bank("1059", "Bancamía S.A."),
        Bank("1060", "Banco Pichincha"),
        Bank("1061", "Bancoomeva"),
        Bank("1062", "Banco Falabella"),
        Bank("1063", "Banco Finandina"),
        Bank("1065", "Banco Santander de Negocios Colombia"),
        Bank("1066", "CoopCentral"),
        Bank("1067", "MiBanco S.A."),
        Bank("1069", "Banco Serfinanza"),
        Bank("1070", "Lulo Bank S.A."),
        Bank("1071", "Banco J.P. Morgan Colombia"),
        Bank("1507", "Nequi"),
        Bank("1551", "Daviplata"),
        Bank("1558", "Banco Credifinanciera"),
        Bank("1560", "Pibank"),
        Bank("1637", "IRIS"),
        Bank("1801", "Movii"),
        Bank("1802", "Ding Tecnipagos"),
        Bank("1803", "Powwi"),
        Bank("1804", "Ualá"),
        Bank("1805", "Banco BTG Pactual"),
        Bank("1808", "Bold CF"),
        Bank("1809", "NU"),
        Bank("1811", "Rappipay"),
        Bank("1812", "Coink"),
        Bank("1814", "Global66"),
        Bank("1819", "Banco Contactar S.A.")
    )
    
    private val docTypes = listOf("CC", "TI", "CE", "NIT", "PP")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Read arguments from Bundle
        arguments?.let { bundle ->
            argCourseId = bundle.getLong("courseId", -1L)
            argCourseName = bundle.getString("courseName", "")
            argCoursePrice = bundle.getFloat("coursePrice", 0f)
            argUsername = bundle.getString("username", "")
        }
        return inflater.inflate(R.layout.fragment_payment_form, container, false)
    }

    @Suppress("DEPRECATION")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Set soft input mode to adjustResize for this fragment
        // This ensures the keyboard doesn't cover input fields
        activity?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        
        initViews(view)
        setupData()
        setupAdapters()
        setupListeners()
        startEntryAnimations()
    }
    
    @Suppress("DEPRECATION")
    override fun onDestroyView() {
        super.onDestroyView()
        // Restore the original soft input mode when leaving
        activity?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }
    
    private fun initViews(view: View) {
        // Header
        btnBack = view.findViewById(R.id.btnBack)
        paymentIcon = view.findViewById(R.id.paymentIcon)
        titleText = view.findViewById(R.id.titleText)
        subtitleText = view.findViewById(R.id.subtitleText)
        
        // Card
        paymentCard = view.findViewById(R.id.paymentCard)
        
        // Course Summary
        courseSummaryContainer = view.findViewById(R.id.courseSummaryContainer)
        tvCourseName = view.findViewById(R.id.tvCourseName)
        tvCoursePrice = view.findViewById(R.id.tvCoursePrice)
        
        // Bank Section
        bankSectionTitle = view.findViewById(R.id.bankSectionTitle)
        bankInputLayout = view.findViewById(R.id.bankInputLayout)
        inputBank = view.findViewById(R.id.inputBank)
        
        // Document Section
        docSectionTitle = view.findViewById(R.id.docSectionTitle)
        documentContainer = view.findViewById(R.id.documentContainer)
        inputDocType = view.findViewById(R.id.inputDocType)
        inputDocNumber = view.findViewById(R.id.inputDocNumber)
        
        // Phone
        phoneLayout = view.findViewById(R.id.phoneLayout)
        inputPhone = view.findViewById(R.id.inputPhone)
        
        // Security & Buttons
        securityNotice = view.findViewById(R.id.securityNotice)
        btnPay = view.findViewById(R.id.btnPay)
        btnCancel = view.findViewById(R.id.btnCancel)
        progressBar = view.findViewById(R.id.progressBar)
        
        // Particles
        particle1 = view.findViewById(R.id.particle1)
        particle2 = view.findViewById(R.id.particle2)
        particle3 = view.findViewById(R.id.particle3)
    }
    
    private fun setupData() {
        tvCourseName.text = argCourseName
        tvCoursePrice.text = "$${String.format("%,.0f", argCoursePrice)} COP"
        
        // Update pay button with amount
        btnPay.text = "Pagar $${String.format("%,.0f", argCoursePrice)} COP"
    }
    
    private fun setupAdapters() {
        // Bank Adapter - Searchable
        val bankNames = allBanks.map { "${it.name} (${it.code})" }
        val bankAdapter = ArrayAdapter(
            requireContext(),
            R.layout.item_dropdown_bank,
            bankNames
        )
        inputBank.setAdapter(bankAdapter)
        inputBank.threshold = 1 // Start filtering from first character
        
        // Document Type Adapter
        val docAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            docTypes
        )
        inputDocType.setAdapter(docAdapter)
        inputDocType.setText("CC", false) // Default value
    }
    
    private fun setupListeners() {
        btnBack.setOnClickListener {
            findNavController().navigateUp()
        }
        
        btnCancel.setOnClickListener {
            findNavController().navigateUp()
        }
        
        btnPay.setOnClickListener {
            validateAndPay()
        }
    }
    
    private fun startEntryAnimations() {
        // Load particle animation
        val particleAnim = AnimationUtils.loadAnimation(context, R.anim.particle_float)
        particle1.startAnimation(particleAnim)
        particle2.startAnimation(particleAnim)
        particle3.startAnimation(particleAnim)
        
        // Header elements are now visible by default (no animation needed)
        // They have shadow for better visibility over gradient background
        
        // Card animation
        paymentCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setStartDelay(250)
            .setInterpolator(DecelerateInterpolator(1.5f))
            .start()
        
        // Content animations (staggered)
        animateViewIn(courseSummaryContainer, 400, 0f, 20f)
        animateViewIn(bankSectionTitle, 500, -20f, 0f)
        animateViewIn(bankInputLayout, 550, -30f, 0f)
        animateViewIn(docSectionTitle, 600, -20f, 0f)
        animateViewIn(documentContainer, 650, -30f, 0f)
        animateViewIn(phoneLayout, 700, 30f, 0f)
        animateViewIn(securityNotice, 750, 0f, 0f)
        animateViewIn(btnPay, 800, 0f, 20f)
        animateViewIn(btnCancel, 850, 0f, 0f)
    }
    
    private fun animateViewIn(view: View, delay: Long, translateX: Float, translateY: Float) {
        view.alpha = 0f
        view.translationX = translateX
        view.translationY = translateY
        
        view.animate()
            .alpha(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(delay)
            .setInterpolator(DecelerateInterpolator(1.3f))
            .start()
    }
    
    private fun validateAndPay() {
        val bankText = inputBank.text.toString()
        val docType = inputDocType.text.toString()
        val docNumber = inputDocNumber.text?.toString() ?: ""
        val phone = inputPhone.text?.toString() ?: ""
        
        // Wompi minimum amount validation: $1,500 COP
        val WOMPI_MIN_AMOUNT = 1500.0
        if (argCoursePrice < WOMPI_MIN_AMOUNT) {
            Toast.makeText(
                context, 
                "El monto mínimo de pago es $${String.format("%,.0f", WOMPI_MIN_AMOUNT)} COP", 
                Toast.LENGTH_LONG
            ).show()
            return
        }
        
        // Validation
        if (bankText.isBlank()) {
            Toast.makeText(context, "Selecciona un banco", Toast.LENGTH_SHORT).show()
            inputBank.requestFocus()
            return
        }
        
        if (docNumber.isBlank()) {
            Toast.makeText(context, "Ingresa tu número de documento", Toast.LENGTH_SHORT).show()
            inputDocNumber.requestFocus()
            return
        }
        
        if (docNumber.length < 5) {
            Toast.makeText(context, "Número de documento inválido", Toast.LENGTH_SHORT).show()
            inputDocNumber.requestFocus()
            return
        }
        
        // Find bank code
        val selectedBank = allBanks.find { "${it.name} (${it.code})" == bankText || it.name == bankText }
        val bankCode = selectedBank?.code ?: run {
            Toast.makeText(context, "Banco no válido, selecciona de la lista", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Disable UI
        setLoadingState(true)
        
        // Execute payment
        initiatePSEPayment(bankCode, docType, docNumber)
    }
    
    private fun setLoadingState(loading: Boolean) {
        btnPay.isEnabled = !loading
        btnCancel.isEnabled = !loading
        inputBank.isEnabled = !loading
        inputDocType.isEnabled = !loading
        inputDocNumber.isEnabled = !loading
        inputPhone.isEnabled = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        
        if (loading) {
            btnPay.text = "Procesando..."
        } else {
            btnPay.text = "Pagar $${String.format("%,.0f", argCoursePrice)} COP"
        }
    }
    
    private fun initiatePSEPayment(bankCode: String, docType: String, docNumber: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Get user data
                val user = SupabaseClient.fetchUsuarioByUsername(argUsername)
                val userId = user?.id ?: -1L
                val userEmail = user?.email ?: ""
                
                if (userId == -1L) {
                    withContext(Dispatchers.Main) {
                        setLoadingState(false)
                        Toast.makeText(context, "Error: Usuario no encontrado", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // Build request
                val request = PaymentInitiationRequest(
                    courseId = argCourseId,
                    userId = userId,
                    amount = argCoursePrice.toDouble(),
                    bankCode = bankCode,
                    payerEmail = userEmail,
                    payerName = argUsername,
                    payerDocType = docType,
                    payerDocNumber = docNumber,
                    ipAddress = "127.0.0.1",
                    userAgent = "AndroidApp"
                )
                
                val response = paymentApi.initiatePayment(request)
                
                withContext(Dispatchers.Main) {
                    setLoadingState(false)
                    
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        if (body.success && !body.urlBankPayment.isNullOrBlank()) {
                            // Open bank URL
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(body.urlBankPayment))
                            startActivity(intent)
                            Toast.makeText(context, "Redirigiendo a tu banco...", Toast.LENGTH_LONG).show()
                            
                            // Navigate back after a delay
                            view?.postDelayed({
                                if (isAdded) {
                                    findNavController().navigateUp()
                                }
                            }, 1500)
                        } else {
                            val errorMsg = body.message ?: "Error desconocido"
                            Toast.makeText(context, "Error: $errorMsg", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(context, "Error del servidor: ${response.code()}", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("PaymentForm", "Payment error", e)
                withContext(Dispatchers.Main) {
                    setLoadingState(false)
                    Toast.makeText(context, "Error de conexión: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
