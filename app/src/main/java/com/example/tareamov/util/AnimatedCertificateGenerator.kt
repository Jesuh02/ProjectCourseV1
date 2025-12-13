package com.example.tareamov.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.tareamov.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object AnimatedCertificateGenerator {
    private const val TAG = "AnimatedCertificateGenerator"

    /**
     * Generates an animated certificate with all course progress data
     */
    fun generateAnimatedCertificate(
        context: Context,
        studentUsername: String,
        creatorUsername: String,
        courseName: String,
        courseTopic: String,
        grade: String,
        courseId: Long
    ) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                Toast.makeText(context, "Generando certificado animado...", Toast.LENGTH_SHORT).show()

                val db = AppDatabase.getDatabase(context)

                // Get student full name from Persona table
                val studentName = withContext(Dispatchers.IO) {
                    val user = db.usuarioDao().getUsuarioByUsername(studentUsername)
                    if (user != null) {
                        val persona = db.personaDao().getPersonaById(user.personaId)
                        "${persona?.nombres ?: ""} ${persona?.apellidos ?: ""}".trim().ifEmpty { studentUsername }
                    } else {
                        studentUsername
                    }
                }

                // Get creator full name from Persona table
                val creatorName = withContext(Dispatchers.IO) {
                    val user = db.usuarioDao().getUsuarioByUsername(creatorUsername)
                    if (user != null) {
                        val persona = db.personaDao().getPersonaById(user.personaId)
                        "${persona?.nombres ?: ""} ${persona?.apellidos ?: ""}".trim().ifEmpty { creatorUsername }
                    } else {
                        creatorUsername
                    }
                }

                // Get progress data from ProgresoEstudiante
                val progressData = withContext(Dispatchers.IO) {
                    val user = db.usuarioDao().getUsuarioByUsername(studentUsername)
                    if (user != null) {
                        db.progresoEstudianteDao().getProgresoByUsuarioAndCurso(user.id, courseId)
                    } else {
                        null
                    }
                }

                val tareasCompletadas = progressData?.tareasCompletadas ?: 0
                val tareasTotales = progressData?.tareasTotales ?: 0
                val porcentajeProgreso = progressData?.porcentajeProgreso ?: 100f
                val estado = progressData?.calcularEstado() ?: "APROBADO"

                // Generate unique certificate ID
                val certificateId = "CERT-${UUID.randomUUID().toString().take(8).uppercase()}"

                // Generate HTML certificate
                val htmlContent = generateAnimatedHtml(
                    studentName = studentName,
                    courseName = courseName,
                    courseTopic = courseTopic,
                    creatorName = creatorName,
                    creatorUsername = creatorUsername,
                    grade = grade,
                    tareasCompletadas = tareasCompletadas,
                    tareasTotales = tareasTotales,
                    porcentajeProgreso = porcentajeProgreso,
                    estado = estado,
                    certificateId = certificateId
                )

                // Save HTML file
                val fileName = "Certificado_${courseName.replace(" ", "_")}_${System.currentTimeMillis()}.html"
                val file = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    fileName
                )

                withContext(Dispatchers.IO) {
                    FileWriter(file).use { writer ->
                        writer.write(htmlContent)
                    }
                }

                // Open HTML file in browser
                openHtmlFile(context, file)

                // Update certificate issued date
                CertificateGenerator.updateCertificateIssuedDate(context, studentUsername, courseId)

                Toast.makeText(context, "Certificado generado con éxito", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error generating animated certificate", e)
                Toast.makeText(
                    context,
                    "Error al generar certificado: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun generateAnimatedHtml(
        studentName: String,
        courseName: String,
        courseTopic: String,
        creatorName: String,
        creatorUsername: String,
        grade: String,
        tareasCompletadas: Int,
        tareasTotales: Int,
        porcentajeProgreso: Float,
        estado: String,
        certificateId: String
    ): String {
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale("es", "ES"))
        val currentDate = dateFormat.format(Date())
        val displayTopic = courseTopic.ifEmpty { courseName }
        // Escape $ for Kotlin string template
        val dollarSign = "$"

        return """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Certificado - $courseName</title>
    <link href="https://fonts.googleapis.com/css2?family=Poppins:wght@300;400;500;600;700;800&family=Orbitron:wght@400;500;600;700&display=swap" rel="stylesheet">
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }

        :root {
            --neon-purple: #a855f7;
            --neon-pink: #ec4899;
            --neon-magenta: #ff00ff;
            --dark-bg: #0d0015;
            --card-bg: #1a0a2e;
            --text-light: #ffffff;
            --text-muted: #a78bfa;
        }

        body {
            font-family: 'Poppins', sans-serif;
            background: linear-gradient(180deg, #0d0015 0%, #1a0a2e 50%, #0d0015 100%);
            min-height: 100vh;
            display: flex;
            justify-content: center;
            align-items: center;
            padding: 20px;
            position: relative;
            overflow-x: hidden;
        }

        /* Animated stars background */
        .stars {
            position: fixed;
            top: 0;
            left: 0;
            width: 100%;
            height: 100%;
            pointer-events: none;
            overflow: hidden;
            z-index: 0;
        }

        .star {
            position: absolute;
            width: 2px;
            height: 2px;
            background: white;
            border-radius: 50%;
            animation: twinkle 3s infinite;
        }

        @keyframes twinkle {
            0%, 100% { opacity: 0.3; transform: scale(1); }
            50% { opacity: 1; transform: scale(1.5); }
        }

        /* Certificate container */
        .certificate-container {
            width: 100%;
            max-width: 420px;
            background: linear-gradient(145deg, #1a0a2e 0%, #2d1b4e 50%, #1a0a2e 100%);
            border-radius: 24px;
            padding: 32px 24px;
            position: relative;
            z-index: 10;
            box-shadow: 
                0 0 40px rgba(168, 85, 247, 0.3),
                0 0 80px rgba(236, 72, 153, 0.2),
                inset 0 1px 0 rgba(255, 255, 255, 0.1);
            border: 1px solid rgba(168, 85, 247, 0.3);
            animation: certificateEntry 1s ease-out;
        }

        @keyframes certificateEntry {
            0% {
                opacity: 0;
                transform: translateY(30px) scale(0.95);
            }
            100% {
                opacity: 1;
                transform: translateY(0) scale(1);
            }
        }

        /* Glowing border effect */
        .certificate-container::before {
            content: '';
            position: absolute;
            top: -2px;
            left: -2px;
            right: -2px;
            bottom: -2px;
            background: linear-gradient(45deg, #a855f7, #ec4899, #a855f7, #ec4899);
            border-radius: 26px;
            z-index: -1;
            animation: borderGlow 3s linear infinite;
            background-size: 400% 400%;
        }

        @keyframes borderGlow {
            0% { background-position: 0% 50%; }
            50% { background-position: 100% 50%; }
            100% { background-position: 0% 50%; }
        }

        /* Header section */
        .header {
            text-align: center;
            margin-bottom: 24px;
            animation: fadeInDown 0.8s ease-out 0.2s both;
        }

        @keyframes fadeInDown {
            0% {
                opacity: 0;
                transform: translateY(-20px);
            }
            100% {
                opacity: 1;
                transform: translateY(0);
            }
        }

        .certificate-badge {
            display: inline-flex;
            align-items: center;
            gap: 8px;
            background: linear-gradient(135deg, rgba(168, 85, 247, 0.2), rgba(236, 72, 153, 0.2));
            padding: 8px 20px;
            border-radius: 50px;
            margin-bottom: 20px;
            border: 1px solid rgba(168, 85, 247, 0.4);
        }

        .certificate-badge svg {
            width: 20px;
            height: 20px;
            fill: #a855f7;
        }

        .certificate-badge span {
            font-family: 'Orbitron', sans-serif;
            font-size: 14px;
            font-weight: 600;
            color: #e9d5ff;
            letter-spacing: 2px;
        }

        /* Logo section */
        .logo-section {
            margin-bottom: 24px;
            animation: fadeIn 0.8s ease-out 0.4s both;
        }

        @keyframes fadeIn {
            0% { opacity: 0; }
            100% { opacity: 1; }
        }

        .logo-text {
            font-family: 'Orbitron', sans-serif;
            font-size: 36px;
            font-weight: 700;
            background: linear-gradient(135deg, #a855f7, #ec4899);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
            text-shadow: 0 0 30px rgba(168, 85, 247, 0.5);
        }

        .code-icon {
            font-family: 'Orbitron', sans-serif;
            font-size: 48px;
            color: #ec4899;
            margin: 8px 0;
            text-shadow: 0 0 20px rgba(236, 72, 153, 0.7);
            animation: pulse 2s ease-in-out infinite;
        }

        @keyframes pulse {
            0%, 100% { transform: scale(1); }
            50% { transform: scale(1.05); }
        }

        /* Body text */
        .body-text {
            color: #c4b5fd;
            font-size: 14px;
            margin-bottom: 16px;
            animation: fadeIn 0.8s ease-out 0.5s both;
        }

        /* Student name */
        .student-name {
            font-family: 'Orbitron', sans-serif;
            font-size: 22px;
            font-weight: 600;
            color: #ec4899;
            text-shadow: 0 0 20px rgba(236, 72, 153, 0.5);
            margin-bottom: 20px;
            padding: 12px 0;
            border-top: 1px solid rgba(168, 85, 247, 0.3);
            border-bottom: 1px solid rgba(168, 85, 247, 0.3);
            animation: nameGlow 2s ease-in-out infinite, fadeIn 0.8s ease-out 0.6s both;
        }

        @keyframes nameGlow {
            0%, 100% { text-shadow: 0 0 20px rgba(236, 72, 153, 0.5); }
            50% { text-shadow: 0 0 30px rgba(236, 72, 153, 0.8), 0 0 40px rgba(236, 72, 153, 0.4); }
        }

        /* Course name */
        .course-name {
            font-family: 'Orbitron', sans-serif;
            font-size: 18px;
            font-weight: 500;
            color: #a855f7;
            text-shadow: 0 0 15px rgba(168, 85, 247, 0.5);
            margin-bottom: 24px;
            animation: fadeIn 0.8s ease-out 0.7s both;
        }

        /* Grade section */
        .grade-section {
            margin-bottom: 24px;
            animation: fadeIn 0.8s ease-out 0.8s both;
        }

        .grade-label {
            color: #c4b5fd;
            font-size: 14px;
            margin-bottom: 8px;
        }

        .grade-display {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: 12px;
        }

        .star-icon {
            font-size: 24px;
            color: #fbbf24;
            text-shadow: 0 0 10px rgba(251, 191, 36, 0.7);
            animation: starPulse 1.5s ease-in-out infinite;
        }

        .star-icon:nth-child(1) { animation-delay: 0s; }
        .star-icon:nth-child(3) { animation-delay: 0.3s; }

        @keyframes starPulse {
            0%, 100% { transform: scale(1); opacity: 0.8; }
            50% { transform: scale(1.2); opacity: 1; }
        }

        .grade-value {
            font-family: 'Orbitron', sans-serif;
            font-size: 28px;
            font-weight: 700;
            color: #ffffff;
            background: linear-gradient(135deg, #fbbf24, #f59e0b);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
            background-clip: text;
        }

        /* Stats box */
        .stats-box {
            background: rgba(168, 85, 247, 0.1);
            border: 1px solid rgba(168, 85, 247, 0.3);
            border-radius: 12px;
            padding: 16px;
            margin-bottom: 24px;
            animation: fadeIn 0.8s ease-out 0.9s both;
        }

        .stat-item {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 8px 0;
            color: #e9d5ff;
            font-size: 14px;
        }

        .stat-item:not(:last-child) {
            border-bottom: 1px solid rgba(168, 85, 247, 0.2);
        }

        .stat-icon {
            font-size: 16px;
        }

        .stat-label {
            flex: 1;
        }

        .stat-value {
            font-weight: 600;
            color: #a855f7;
        }

        .status-approved {
            color: #4ade80 !important;
            font-weight: 700;
        }

        /* Creator section */
        .creator-section {
            margin-bottom: 24px;
            padding-top: 16px;
            border-top: 1px solid rgba(168, 85, 247, 0.2);
            animation: fadeIn 0.8s ease-out 1s both;
        }

        .creator-label {
            color: #a78bfa;
            font-size: 12px;
            margin-bottom: 4px;
        }

        .creator-name {
            color: #ffffff;
            font-size: 16px;
            font-weight: 600;
        }

        .creator-username {
            color: #ec4899;
            font-size: 14px;
        }

        /* Footer */
        .footer {
            display: flex;
            justify-content: space-between;
            padding-top: 16px;
            border-top: 1px solid rgba(168, 85, 247, 0.2);
            animation: fadeIn 0.8s ease-out 1.1s both;
        }

        .footer-item {
            text-align: center;
        }

        .footer-label {
            color: #a78bfa;
            font-size: 10px;
            margin-bottom: 2px;
        }

        .footer-value {
            color: #e9d5ff;
            font-size: 12px;
            font-weight: 500;
        }

        /* Floating particles */
        .particle {
            position: absolute;
            width: 4px;
            height: 4px;
            background: #a855f7;
            border-radius: 50%;
            animation: float 6s ease-in-out infinite;
            opacity: 0.6;
        }

        @keyframes float {
            0%, 100% { transform: translateY(0) translateX(0); opacity: 0.6; }
            25% { transform: translateY(-20px) translateX(10px); opacity: 1; }
            50% { transform: translateY(-10px) translateX(-10px); opacity: 0.8; }
            75% { transform: translateY(-30px) translateX(5px); opacity: 1; }
        }

        /* Responsive */
        @media (max-width: 480px) {
            .certificate-container {
                padding: 24px 16px;
            }
            .logo-text {
                font-size: 28px;
            }
            .code-icon {
                font-size: 36px;
            }
            .student-name {
                font-size: 18px;
            }
            .course-name {
                font-size: 16px;
            }
        }
    </style>
</head>
<body>
    <!-- Stars background -->
    <div class="stars" id="stars"></div>

    <!-- Floating particles -->
    <div class="particle" style="top: 10%; left: 10%; animation-delay: 0s;"></div>
    <div class="particle" style="top: 20%; right: 15%; animation-delay: 1s;"></div>
    <div class="particle" style="top: 60%; left: 5%; animation-delay: 2s;"></div>
    <div class="particle" style="top: 80%; right: 10%; animation-delay: 3s;"></div>
    <div class="particle" style="top: 40%; left: 85%; animation-delay: 4s;"></div>

    <!-- Certificate -->
    <div class="certificate-container">
        <!-- Header -->
        <div class="header">
            <div class="certificate-badge">
                <svg viewBox="0 0 24 24">
                    <path d="M12 3L1 9L12 15L21 10.09V17H23V9M5 13.18V17.18L12 21L19 17.18V13.18L12 17L5 13.18Z"/>
                </svg>
                <span>CERTIFICADO</span>
            </div>

            <!-- Logo -->
            <div class="logo-section">
                <div class="logo-text">CourseV</div>
                <div class="code-icon">{&lt;/&gt;}</div>
            </div>
        </div>

        <!-- Body -->
        <div class="body-text">
            Se certifica que<br>ha completado exitosamente el curso
        </div>

        <!-- Student Name -->
        <div class="student-name">$studentName</div>

        <!-- Course Name -->
        <div class="course-name">$displayTopic</div>

        <!-- Grade -->
        <div class="grade-section">
            <div class="grade-label">con una calificación de</div>
            <div class="grade-display">
                <span class="star-icon">☆</span>
                <span class="grade-value">[$grade/10]</span>
                <span class="star-icon">☆</span>
            </div>
        </div>

        <!-- Stats -->
        <div class="stats-box">
            <div class="stat-item">
                <span class="stat-icon">✓</span>
                <span class="stat-label">Tareas completadas:</span>
                <span class="stat-value">$tareasCompletadas/$tareasTotales</span>
            </div>
            <div class="stat-item">
                <span class="stat-icon">📊</span>
                <span class="stat-label">Progreso:</span>
                <span class="stat-value">${porcentajeProgreso.toInt()}%</span>
            </div>
            <div class="stat-item">
                <span class="stat-icon">🏆</span>
                <span class="stat-label">Estado:</span>
                <span class="stat-value status-approved">$estado</span>
            </div>
        </div>

        <!-- Creator -->
        <div class="creator-section">
            <div class="creator-label">Impartido por:</div>
            <div class="creator-name">$creatorName</div>
            <div class="creator-username">@$creatorUsername</div>
        </div>

        <!-- Footer -->
        <div class="footer">
            <div class="footer-item">
                <div class="footer-label">Fecha emisión:</div>
                <div class="footer-value">$currentDate</div>
            </div>
            <div class="footer-item">
                <div class="footer-label">ID del certificado:</div>
                <div class="footer-value">$certificateId</div>
            </div>
        </div>
    </div>

    <script>
        // Generate random stars
        const starsContainer = document.getElementById('stars');
        for (let i = 0; i < 100; i++) {
            const star = document.createElement('div');
            star.className = 'star';
            star.style.left = Math.random() * 100 + '%';
            star.style.top = Math.random() * 100 + '%';
            star.style.animationDelay = Math.random() * 3 + 's';
            star.style.animationDuration = (2 + Math.random() * 2) + 's';
            starsContainer.appendChild(star);
        }

        // Add interactive hover effects
        const container = document.querySelector('.certificate-container');
        container.addEventListener('mousemove', (e) => {
            const rect = container.getBoundingClientRect();
            const x = (e.clientX - rect.left) / rect.width - 0.5;
            const y = (e.clientY - rect.top) / rect.height - 0.5;
            container.style.transform = 'perspective(1000px) rotateY(' + (x * 5) + 'deg) rotateX(' + (-y * 5) + 'deg)';
        });

        container.addEventListener('mouseleave', () => {
            container.style.transform = 'perspective(1000px) rotateY(0deg) rotateX(0deg)';
        });
    </script>
</body>
</html>
        """.trimIndent()
    }

    private fun openHtmlFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "text/html")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Try to open with browser
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("http://")).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
            }

            val resolvedActivity = context.packageManager.resolveActivity(
                browserIntent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )

            if (resolvedActivity != null) {
                intent.setPackage(resolvedActivity.activityInfo.packageName)
            }

            context.startActivity(Intent.createChooser(intent, "Abrir certificado con"))

        } catch (e: Exception) {
            Log.e(TAG, "Error opening HTML file", e)
            Toast.makeText(
                context,
                "No se pudo abrir el certificado. Archivo guardado en: ${file.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
