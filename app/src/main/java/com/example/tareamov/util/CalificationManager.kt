package com.example.tareamov.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Gestor de calificaciones temporales para comunicación entre fragmentos
 */
class CalificationManager private constructor(context: Context) {
    
    companion object {
        private const val PREF_NAME = "calification_prefs"
        private const val KEY_PENDING_GRADE = "pending_grade"
        private const val KEY_PENDING_FEEDBACK = "pending_feedback"
        private const val KEY_SUBMISSION_ID = "submission_id"
        private const val KEY_HAS_PENDING_CALIFICATION = "has_pending_calification"
        
        @Volatile
        private var INSTANCE: CalificationManager? = null
        
        fun getInstance(context: Context): CalificationManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CalificationManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    private val sharedPreferences: SharedPreferences = 
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val editor: SharedPreferences.Editor = sharedPreferences.edit()
    
    /**
     * Almacena una calificación pendiente que debe ser enviada al sistema de entregas
     */
    fun storePendingCalification(grade: String, feedback: String, submissionId: Long? = null) {
        editor.apply {
            putString(KEY_PENDING_GRADE, grade)
            putString(KEY_PENDING_FEEDBACK, feedback)
            putLong(KEY_SUBMISSION_ID, submissionId ?: -1L)
            putBoolean(KEY_HAS_PENDING_CALIFICATION, true)
            apply()
        }
    }
    
    /**
     * Recupera la calificación pendiente
     */
    fun getPendingCalification(): CalificationData? {
        if (!hasPendingCalification()) return null
        
        val grade = sharedPreferences.getString(KEY_PENDING_GRADE, null)
        val feedback = sharedPreferences.getString(KEY_PENDING_FEEDBACK, null)
        val submissionId = sharedPreferences.getLong(KEY_SUBMISSION_ID, -1L)
        
        return if (grade != null && feedback != null) {
            CalificationData(
                grade = grade,
                feedback = feedback,
                submissionId = if (submissionId != -1L) submissionId else null
            )
        } else null
    }
    
    /**
     * Verifica si hay una calificación pendiente
     */
    fun hasPendingCalification(): Boolean {
        return sharedPreferences.getBoolean(KEY_HAS_PENDING_CALIFICATION, false)
    }
    
    /**
     * Limpia la calificación pendiente después de ser procesada
     */
    fun clearPendingCalification() {
        editor.apply {
            remove(KEY_PENDING_GRADE)
            remove(KEY_PENDING_FEEDBACK)
            remove(KEY_SUBMISSION_ID)
            putBoolean(KEY_HAS_PENDING_CALIFICATION, false)
            apply()
        }
    }
    
    /**
     * Clase de datos para almacenar información de calificación
     */
    data class CalificationData(
        val grade: String,
        val feedback: String,
        val submissionId: Long? = null
    )
}
