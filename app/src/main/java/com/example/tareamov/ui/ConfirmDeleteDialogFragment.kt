package com.example.tareamov.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.example.tareamov.R

class ConfirmDeleteDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_REQUEST_KEY = "arg_request_key"
        private const val ARG_TITLE = "arg_title"
        private const val ARG_MESSAGE = "arg_message"
        private const val ARG_CONFIRM = "arg_confirm"
        private const val ARG_CANCEL = "arg_cancel"

        fun newInstance(
            requestKey: String,
            title: String,
            message: String,
            confirmText: String = "Eliminar",
            cancelText: String = "Cancelar"
        ): ConfirmDeleteDialogFragment {
            val f = ConfirmDeleteDialogFragment()
            val args = Bundle().apply {
                putString(ARG_REQUEST_KEY, requestKey)
                putString(ARG_TITLE, title)
                putString(ARG_MESSAGE, message)
                putString(ARG_CONFIRM, confirmText)
                putString(ARG_CANCEL, cancelText)
            }
            f.arguments = args
            return f
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val requestKey = args.getString(ARG_REQUEST_KEY) ?: "confirm_delete"
        val title = args.getString(ARG_TITLE) ?: ""
        val message = args.getString(ARG_MESSAGE) ?: ""
        val confirmText = args.getString(ARG_CONFIRM) ?: "Eliminar"
        val cancelText = args.getString(ARG_CANCEL) ?: "Cancelar"

        val inflater = LayoutInflater.from(requireContext())
        val view = inflater.inflate(R.layout.dialog_confirm_delete, null)

        val titleTv = view.findViewById<TextView>(R.id.confirmDeleteTitle)
        val messageTv = view.findViewById<TextView>(R.id.confirmDeleteMessage)
        val cancelBtn = view.findViewById<TextView>(R.id.cancelDeleteButton)
        val confirmBtn = view.findViewById<TextView>(R.id.confirmDeleteButton)

        titleTv.text = title
        messageTv.text = message
        cancelBtn.text = cancelText
        confirmBtn.text = confirmText

        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()

        cancelBtn.setOnClickListener {
            parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { putBoolean("confirmed", false) })
            dialog.dismiss()
        }

        confirmBtn.setOnClickListener {
            parentFragmentManager.setFragmentResult(requestKey, Bundle().apply { putBoolean("confirmed", true) })
            dialog.dismiss()
        }

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return dialog
    }
}
