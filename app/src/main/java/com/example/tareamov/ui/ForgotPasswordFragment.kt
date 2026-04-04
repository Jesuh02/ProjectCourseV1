package com.example.tareamov.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.fragment.findNavController
import com.example.tareamov.R
import com.example.tareamov.viewmodel.ForgotPasswordViewModel

class ForgotPasswordFragment : Fragment() {

    private lateinit var viewModel: ForgotPasswordViewModel

    // Step containers
    private lateinit var step1Layout: LinearLayout
    private lateinit var step2Layout: LinearLayout
    private lateinit var step3Layout: LinearLayout
    private lateinit var step4Layout: LinearLayout

    // Step 1
    private lateinit var etEmail: EditText
    private lateinit var btnSendCode: Button

    // Step 2
    private lateinit var etCode1: EditText
    private lateinit var etCode2: EditText
    private lateinit var etCode3: EditText
    private lateinit var etCode4: EditText
    private lateinit var btnVerifyCode: Button
    private lateinit var tvResendCode: TextView

    // Step 3
    private lateinit var etNewPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var toggleNewPassword: ImageView
    private lateinit var toggleConfirmPassword: ImageView
    private lateinit var btnResetPassword: Button

    // Password hint dots & labels
    private lateinit var dotLength: View
    private lateinit var tvHintLength: TextView
    private lateinit var dotNumber: View
    private lateinit var tvHintNumber: TextView
    private lateinit var dotUpper: View
    private lateinit var tvHintUpper: TextView
    private lateinit var dotLower: View
    private lateinit var tvHintLower: TextView
    private lateinit var dotSymbol: View
    private lateinit var tvHintSymbol: TextView

    // Step 4
    private lateinit var btnGoToLogin: Button

    // Shared
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvError: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnBack: ImageView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_forgot_password, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(this)[ForgotPasswordViewModel::class.java]

        bindViews(view)
        setupListeners()
        observeViewModel()
    }

    private fun bindViews(view: View) {
        tvTitle = view.findViewById(R.id.tvTitle)
        tvSubtitle = view.findViewById(R.id.tvSubtitle)
        tvError = view.findViewById(R.id.tvError)
        progressBar = view.findViewById(R.id.progressBar)
        btnBack = view.findViewById(R.id.btnBack)

        step1Layout = view.findViewById(R.id.step1Layout)
        step2Layout = view.findViewById(R.id.step2Layout)
        step3Layout = view.findViewById(R.id.step3Layout)
        step4Layout = view.findViewById(R.id.step4Layout)

        etEmail = view.findViewById(R.id.etEmail)
        btnSendCode = view.findViewById(R.id.btnSendCode)

        etCode1 = view.findViewById(R.id.etCode1)
        etCode2 = view.findViewById(R.id.etCode2)
        etCode3 = view.findViewById(R.id.etCode3)
        etCode4 = view.findViewById(R.id.etCode4)
        btnVerifyCode = view.findViewById(R.id.btnVerifyCode)
        tvResendCode = view.findViewById(R.id.tvResendCode)

        etNewPassword = view.findViewById(R.id.etNewPassword)
        etConfirmPassword = view.findViewById(R.id.etConfirmPassword)
        toggleNewPassword = view.findViewById(R.id.toggleNewPassword)
        toggleConfirmPassword = view.findViewById(R.id.toggleConfirmPassword)
        btnResetPassword = view.findViewById(R.id.btnResetPassword)

        dotLength = view.findViewById(R.id.dotLength)
        tvHintLength = view.findViewById(R.id.tvHintLength)
        dotNumber = view.findViewById(R.id.dotNumber)
        tvHintNumber = view.findViewById(R.id.tvHintNumber)
        dotUpper = view.findViewById(R.id.dotUpper)
        tvHintUpper = view.findViewById(R.id.tvHintUpper)
        dotLower = view.findViewById(R.id.dotLower)
        tvHintLower = view.findViewById(R.id.tvHintLower)
        dotSymbol = view.findViewById(R.id.dotSymbol)
        tvHintSymbol = view.findViewById(R.id.tvHintSymbol)

        btnGoToLogin = view.findViewById(R.id.btnGoToLogin)
    }

    private fun setupListeners() {
        // Back button: navigate up or go back a step
        btnBack.setOnClickListener {
            val currentStep = viewModel.step.value ?: 1
            if (currentStep <= 1) {
                findNavController().navigateUp()
            } else {
                viewModel.goBack()
            }
        }

        // Step 1
        btnSendCode.setOnClickListener {
            viewModel.sendCode(etEmail.text.toString())
        }

        // Step 2: auto-advance between digit boxes
        setupDigitInputs()
        btnVerifyCode.setOnClickListener {
            val code = "${etCode1.text}${etCode2.text}${etCode3.text}${etCode4.text}"
            viewModel.verifyCode(code)
        }
        tvResendCode.setOnClickListener {
            viewModel.sendCode(viewModel.confirmedEmail)
        }

        // Step 3: password strength real-time feedback
        etNewPassword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updatePasswordHints(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        toggleNewPassword.setOnClickListener {
            if (etNewPassword.transformationMethod is PasswordTransformationMethod) {
                etNewPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                toggleNewPassword.setImageResource(R.drawable.ic_visibility_off)
            } else {
                etNewPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                toggleNewPassword.setImageResource(R.drawable.ic_visibility)
            }
            etNewPassword.setSelection(etNewPassword.text.length)
        }

        toggleConfirmPassword.setOnClickListener {
            if (etConfirmPassword.transformationMethod is PasswordTransformationMethod) {
                etConfirmPassword.transformationMethod = HideReturnsTransformationMethod.getInstance()
                toggleConfirmPassword.setImageResource(R.drawable.ic_visibility_off)
            } else {
                etConfirmPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                toggleConfirmPassword.setImageResource(R.drawable.ic_visibility)
            }
            etConfirmPassword.setSelection(etConfirmPassword.text.length)
        }

        btnResetPassword.setOnClickListener {
            viewModel.resetPassword(
                etNewPassword.text.toString(),
                etConfirmPassword.text.toString()
            )
        }

        // Step 4
        btnGoToLogin.setOnClickListener {
            findNavController().navigate(R.id.action_forgotPasswordFragment_to_loginFragment)
        }
    }

    private fun setupDigitInputs() {
        val digits = listOf(etCode1, etCode2, etCode3, etCode4)
        digits.forEachIndexed { index, editText ->
            editText.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    if (s?.length == 1 && index < digits.size - 1) {
                        digits[index + 1].requestFocus()
                    }
                }
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            editText.setOnKeyListener { _, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_DEL &&
                    event.action == android.view.KeyEvent.ACTION_DOWN &&
                    editText.text.isEmpty() && index > 0) {
                    digits[index - 1].requestFocus()
                    digits[index - 1].text.clear()
                    true
                } else false
            }
        }
    }

    private fun updatePasswordHints(password: String) {
        val colorOk  = resources.getColor(android.R.color.holo_green_dark, null)
        val colorFail = resources.getColor(android.R.color.holo_red_dark, null)
        val textOk   = 0xFFFFFFFF.toInt()
        val textFail = 0xFF94A3B8.toInt()
        val textColorOk = android.graphics.Color.WHITE

        fun applyHint(dot: View, label: TextView, passes: Boolean) {
            dot.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (passes) colorOk else colorFail
            )
            label.setTextColor(if (passes) textColorOk else 0xFF94A3B8.toInt())
        }

        applyHint(dotLength, tvHintLength, password.length >= 6)
        applyHint(dotNumber, tvHintNumber, password.any { it.isDigit() })
        applyHint(dotUpper, tvHintUpper, password.any { it.isUpperCase() })
        applyHint(dotLower, tvHintLower, password.any { it.isLowerCase() })
        applyHint(dotSymbol, tvHintSymbol, password.any { !it.isLetterOrDigit() })
    }

    private fun observeViewModel() {
        viewModel.step.observe(viewLifecycleOwner) { step ->
            showStep(step)
        }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            btnSendCode.isEnabled = !loading
            btnVerifyCode.isEnabled = !loading
            btnResetPassword.isEnabled = !loading
        }

        viewModel.errorMsg.observe(viewLifecycleOwner) { msg ->
            if (msg.isNullOrEmpty()) {
                tvError.visibility = View.GONE
            } else {
                tvError.text = msg
                tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun showStep(step: Int) {
        tvError.visibility = View.GONE

        step1Layout.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2Layout.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3Layout.visibility = if (step == 3) View.VISIBLE else View.GONE
        step4Layout.visibility = if (step == 4) View.VISIBLE else View.GONE

        when (step) {
            1 -> {
                tvTitle.text = "Recuperar contraseña"
                tvSubtitle.text = "Ingresa tu correo electrónico y te enviaremos un código de 4 dígitos para restablecer tu contraseña."
            }
            2 -> {
                tvTitle.text = "Ingresa el código"
                tvSubtitle.text = "Hemos enviado un código de 4 dígitos a ${viewModel.confirmedEmail}. El código expira en 30 minutos."
                etCode1.text.clear()
                etCode2.text.clear()
                etCode3.text.clear()
                etCode4.text.clear()
                etCode1.requestFocus()
            }
            3 -> {
                tvTitle.text = "Nueva contraseña"
                tvSubtitle.text = "Crea una contraseña segura para tu cuenta."
                etNewPassword.text.clear()
                etConfirmPassword.text.clear()
                updatePasswordHints("")
            }
            4 -> {
                tvTitle.text = "¡Listo!"
                tvSubtitle.text = ""
            }
        }
    }
}
