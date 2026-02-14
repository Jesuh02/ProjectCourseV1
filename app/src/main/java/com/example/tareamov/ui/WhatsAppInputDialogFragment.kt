package com.example.tareamov.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.tareamov.R

class WhatsAppInputDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_REQUEST_KEY = "arg_request_key"
        private const val ARG_IS_VERIFY = "arg_is_verify" // True for OTP, False for Phone

        fun newInstance(requestKey: String, isVerify: Boolean = false): WhatsAppInputDialogFragment {
            val f = WhatsAppInputDialogFragment()
            val args = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putBoolean(ARG_IS_VERIFY, isVerify)
            }
            f.arguments = args
            return f
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY) ?: "whatsapp_input"
        val isVerify = args.getBoolean(ARG_IS_VERIFY)

        val inflater = LayoutInflater.from(requireContext())
        val layoutId = if (isVerify) R.layout.dialog_whatsapp_verify else R.layout.dialog_whatsapp_input
        val view = inflater.inflate(layoutId, null)

        val inputEditText = view.findViewById<EditText>(R.id.inputEditText)
        val cancelBtn = view.findViewById<TextView>(R.id.cancelButton)
        val confirmBtn = view.findViewById<TextView>(R.id.confirmButton)

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        cancelBtn.setOnClickListener {
            dialog.dismiss()
        }

        confirmBtn.setOnClickListener {
            val input = inputEditText.text.toString().trim()
            if (input.isNotEmpty()) {
                parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { 
                    putString("input", input) 
                })
                dialog.dismiss()
            } else {
                inputEditText.error = "Campo requerido"
            }
        }

        return dialog
    }
}
