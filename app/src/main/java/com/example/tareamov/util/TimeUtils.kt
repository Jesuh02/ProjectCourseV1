package com.example.tareamov.util

import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import android.util.Log

object TimeUtils {
    fun formatTime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60))

        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
        }
    }

    fun formatTime(ms: Int): String {
        return formatTime(ms.toLong())
    }

    /**
     * Convert an ISO 8601 timestamp string (like from Supabase) to a relative "time ago" string.
     */
    fun getTimeAgo(isoTimestamp: String?): String {
        if (isoTimestamp == null) return "ahora"
        
        try {
            // Supabase format: 2024-03-24T12:00:00.123456+00:00 or 2024-03-24T12:00:00Z
            
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            
            // Clean the string (remove nanoseconds part if present before parsing)
            val cleanTimestamp = if (isoTimestamp.contains(".")) {
                isoTimestamp.substring(0, isoTimestamp.indexOf("."))
            } else if (isoTimestamp.contains("+")) {
                isoTimestamp.substring(0, isoTimestamp.indexOf("+"))
            } else if (isoTimestamp.endsWith("Z")) {
                isoTimestamp.substring(0, isoTimestamp.length - 1)
            } else {
                isoTimestamp
            }
            
            val date = sdf.parse(cleanTimestamp)
            val time = date?.time ?: System.currentTimeMillis()
            val now = System.currentTimeMillis()
            
            val diff = now - time
            
            if (diff < 0) return "ahora" // Handle future dates gracefully
            
            val seconds = diff / 1000
            val minutes = seconds / 60
            val hours = minutes / 60
            val days = hours / 24

            return when {
                seconds < 60 -> "ahora"
                minutes < 60 -> "hace $minutes min"
                hours < 24 -> "hace $hours h"
                days < 7 -> "hace $days d"
                days < 30 -> "hace ${days / 7} sem"
                days < 365 -> {
                    val m = days / 30
                    if (m < 1) "hace 4 sem" else "hace $m m"
                }
                else -> "hace ${days / 365} a"
            }
        } catch (e: Exception) {
            Log.e("TimeUtils", "Error parsing timestamp: $isoTimestamp", e)
            return "hace poco"
        }
    }
}
