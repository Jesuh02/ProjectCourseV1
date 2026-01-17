package com.example.tareamov.util

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.Fragment

/**
 * Safe Toast utilities that prevent NullPointerException when showing toasts
 * in Fragments that may have been detached from their Activity.
 */
object SafeToast {
    
    /**
     * Show a toast safely, checking if context is available
     */
    fun show(context: Context?, message: String, duration: Int = Toast.LENGTH_SHORT) {
        context?.let {
            try {
                Toast.makeText(it, message, duration).show()
            } catch (e: Exception) {
                // Silently ignore - fragment was likely detached
            }
        }
    }
    
    /**
     * Show a long toast safely
     */
    fun showLong(context: Context?, message: String) {
        show(context, message, Toast.LENGTH_LONG)
    }
}

/**
 * Extension function for Fragment to show toast safely
 */
fun Fragment.showSafeToast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    if (isAdded && context != null) {
        try {
            Toast.makeText(requireContext(), message, duration).show()
        } catch (e: Exception) {
            // Silently ignore - fragment was likely detached
        }
    }
}

/**
 * Extension function for Fragment to show long toast safely
 */
fun Fragment.showSafeLongToast(message: String) {
    showSafeToast(message, Toast.LENGTH_LONG)
}
