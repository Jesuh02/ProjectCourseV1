package com.example.tareamov.util

import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Utility object to build certificate URLs for the web certificate viewer.
 * The certificate web app is deployed at Vercel and reads parameters from the URL.
 */
object CertificateUrlBuilder {
    
    // Base URL of the certificate web app deployed on Vercel
    private const val CERTIFICATE_WEB_BASE_URL = "https://v0-eo-jesuh02s-projects.vercel.app"
    
    /**
     * Builds a complete certificate URL with all required parameters.
     * This URL can be opened in any browser to view and download the certificate.
     *
     * @param studentName Full name of the student
     * @param courseName Name of the course
     * @param grade Final grade (0-10 scale)
     * @param tasksCompleted Number of completed tasks
     * @param totalTasks Total number of tasks
     * @param progress Progress percentage (0-100)
     * @param instructorName Full name of the course instructor
     * @param instructorUsername Username of the instructor
     * @param userId Student's user ID
     * @param courseId Course ID
     * @param certId Optional custom certificate ID (auto-generated if null)
     * @param date Optional date string (uses current date if null)
     * @return Complete URL string for the certificate
     */
    fun buildCertificateUrl(
        studentName: String,
        courseName: String,
        grade: Float,
        tasksCompleted: Int,
        totalTasks: Int,
        progress: Float,
        instructorName: String,
        instructorUsername: String,
        userId: Long,
        courseId: Long,
        certId: String? = null,
        date: String? = null
    ): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES"))
        val certificateDate = date ?: dateFormat.format(Date())
        val certificateId = certId ?: generateCertificateId()
        
        // Build URL with query parameters
        val params = buildString {
            append("?studentName=").append(encode(studentName))
            append("&courseName=").append(encode(courseName))
            append("&grade=").append(String.format(Locale.US, "%.1f", grade))
            append("&tasksCompleted=").append(tasksCompleted)
            append("&totalTasks=").append(totalTasks)
            append("&progress=").append(progress.toInt())
            append("&instructorName=").append(encode(instructorName))
            append("&instructorUsername=").append(encode(instructorUsername.removePrefix("@")))
            append("&userId=").append(userId)
            append("&courseId=").append(courseId)
            append("&certId=").append(encode(certificateId))
            append("&date=").append(encode(certificateDate))
        }
        
        return CERTIFICATE_WEB_BASE_URL + params
    }
    
    /**
     * Generates a unique certificate ID in the format CERT-XXXX-XXXX
     */
    fun generateCertificateId(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val part1 = (1..4).map { chars.random() }.joinToString("")
        val part2 = (1..4).map { chars.random() }.joinToString("")
        return "CERT-$part1-$part2"
    }
    
    /**
     * URL encodes a string for use in query parameters
     */
    private fun encode(value: String): String {
        return try {
            URLEncoder.encode(value, "UTF-8")
        } catch (e: Exception) {
            value.replace(" ", "%20")
        }
    }
    
    /**
     * Extracts the certificate ID from a certificate URL
     */
    fun extractCertificateId(url: String): String? {
        return try {
            val regex = "[?&]certId=([^&]+)".toRegex()
            regex.find(url)?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Validates if a URL is a valid certificate URL from our web app
     */
    fun isValidCertificateUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith(CERTIFICATE_WEB_BASE_URL) && url.contains("studentName=")
    }
    
    /**
     * Gets the base URL of the certificate web app
     */
    fun getBaseUrl(): String = CERTIFICATE_WEB_BASE_URL
}
