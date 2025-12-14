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
                var creatorName = withContext(Dispatchers.IO) {
                    val user = db.usuarioDao().getUsuarioByUsername(creatorUsername)
                    if (user != null) {
                        val persona = db.personaDao().getPersonaById(user.personaId)
                        "${persona?.nombres ?: ""} ${persona?.apellidos ?: ""}".trim().ifEmpty { creatorUsername }
                    } else {
                        creatorUsername
                    }
                }

                // Try to get creator name from Supabase if we only have the username
                if (creatorName == creatorUsername) {
                    try {
                        if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                            val remoteUser = withContext(Dispatchers.IO) {
                                com.example.tareamov.service.SupabaseClient.fetchUsuarioByUsername(creatorUsername)
                            }
                            if (remoteUser != null) {
                                val personaId = try { remoteUser.persona_id } catch (e: Exception) { 0L }
                                if (personaId > 0) {
                                    val persona = withContext(Dispatchers.IO) {
                                        com.example.tareamov.service.SupabaseClient.fetchPersonas().firstOrNull { p -> p.id == personaId }
                                    }
                                    if (persona != null) {
                                        val names = listOfNotNull(persona.nombres.takeIf { it.isNotBlank() }, persona.apellidos.takeIf { it.isNotBlank() })
                                        if (names.isNotEmpty()) creatorName = names.joinToString(" ")
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to fetch creator real name from Supabase", e)
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

                // Get student user ID for certificate URL
                val studentUserId = withContext(Dispatchers.IO) {
                    db.usuarioDao().getUsuarioByUsername(studentUsername)?.id ?: 0L
                }

                // Generate certificate URL for the web app (Vercel)
                // This URL can be shared and opened in any browser
                val certificateUrl = CertificateUrlBuilder.buildCertificateUrl(
                    studentName = studentName,
                    courseName = courseName,
                    grade = grade.replace(",", ".").toFloatOrNull() ?: 0f,
                    tasksCompleted = tareasCompletadas,
                    totalTasks = tareasTotales,
                    progress = porcentajeProgreso,
                    instructorName = creatorName,
                    instructorUsername = creatorUsername,
                    userId = studentUserId,
                    courseId = courseId,
                    certId = certificateId
                )
                
                Log.i(TAG, "✅ URL de certificado generada: $certificateUrl")

                // Open the certificate URL in browser (web version)
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(certificateUrl))
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo abrir en navegador, abriendo archivo local", e)
                    openHtmlFile(context, file)
                }

                // Update certificate issued date and URL in Supabase
                CertificateGenerator.updateCertificateIssuedDate(context, studentUsername, courseId, certificateUrl)

                Toast.makeText(context, "🎓 Certificado generado con éxito", Toast.LENGTH_SHORT).show()

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
        // Escape $ for Kotlin string template if needed, but standard HTML/CSS/JS here doesn't use it except for interpolation

        return """
<!DOCTYPE html>
<html lang="es">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Certificado - $courseName</title>
    <!-- Tailwind CSS -->
    <script src="https://cdn.tailwindcss.com"></script>
    <!-- Anime.js -->
    <script src="https://cdnjs.cloudflare.com/ajax/libs/animejs/3.2.1/anime.min.js"></script>
    <!-- Download Libraries -->
    <script src="https://cdnjs.cloudflare.com/ajax/libs/html2canvas/1.4.1/html2canvas.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/dom-to-image/2.6.0/dom-to-image.min.js"></script>

    <!-- Google Fonts -->
    <link href="https://fonts.googleapis.com/css2?family=Orbitron:wght@400;500;600;700&family=Poppins:wght@300;400;500;600;700&display=swap" rel="stylesheet">

    <script>
        tailwind.config = {
            theme: {
                extend: {
                    fontFamily: {
                        orbitron: ['Orbitron', 'sans-serif'],
                        poppins: ['Poppins', 'sans-serif'],
                    },
                    colors: {
                        neon: {
                            pink: '#ff00ff',
                            purple: '#bd00ff',
                            dark: '#0a0014',
                        }
                    },
                    boxShadow: {
                        'neon-pink': '0 0 10px #ff00ff, 0 0 20px #ff00ff',
                        'neon-purple': '0 0 10px #bd00ff, 0 0 20px #bd00ff',
                    }
                }
            }
        }
    </script>
    <style>
        body {
            background-color: #05000a;
            color: white;
            overflow-x: hidden;
        }
        .neon-text-pink {
            text-shadow: 0 0 5px #ff00ff, 0 0 10px #ff00ff, 0 0 20px #ff00ff;
        }
        .neon-text-purple {
            text-shadow: 0 0 5px #bd00ff, 0 0 10px #bd00ff, 0 0 20px #bd00ff;
        }
        .neon-box {
            box-shadow: 0 0 5px #bd00ff, inset 0 0 5px #bd00ff;
            border: 1px solid #bd00ff;
        }
        .glass-panel {
            background: rgba(20, 0, 40, 0.6);
            backdrop-filter: blur(10px);
        }
        .gradient-text {
            background: linear-gradient(to right, #ff00ff, #bd00ff);
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }
    </style>
</head>
<body class="min-h-screen flex flex-col items-center justify-center p-4 relative bg-[url('https://www.transparenttextures.com/patterns/stardust.png')]">

    <!-- Actions Bar -->
    <div class="fixed top-4 right-4 flex gap-2 z-50 no-print">
        <button onclick="downloadPNG()" class="bg-pink-600 hover:bg-pink-700 text-white px-4 py-2 rounded-lg font-poppins transition-all shadow-lg hover:shadow-pink-500/50">
            PNG
        </button>
        <button onclick="downloadPDF()" class="bg-purple-600 hover:bg-purple-700 text-white px-4 py-2 rounded-lg font-poppins transition-all shadow-lg hover:shadow-purple-500/50">
            PDF
        </button>
        <button onclick="downloadSVG()" class="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2 rounded-lg font-poppins transition-all shadow-lg hover:shadow-blue-500/50">
            SVG
        </button>
    </div>

    <!-- Certificate Container -->
    <div id="certificate" class="relative w-full max-w-[600px] aspect-[3/4] bg-[#0a0014] rounded-xl overflow-hidden shadow-2xl flex flex-col items-center p-4 sm:p-8 text-center select-none transform transition-transform duration-300 hover:scale-[1.01]">
        
        <!-- Tech Border (Image Style) -->
        <div class="absolute inset-0 p-[2px] rounded-xl pointer-events-none">
            <!-- Top-Left & Bottom-Right Cyan -->
            <div class="absolute top-0 left-0 w-1/2 h-[2px] bg-cyan-400 shadow-[0_0_10px_#22d3ee]"></div>
            <div class="absolute top-0 left-0 h-1/3 w-[2px] bg-cyan-400 shadow-[0_0_10px_#22d3ee]"></div>
            
            <div class="absolute bottom-0 right-0 w-1/2 h-[2px] bg-cyan-400 shadow-[0_0_10px_#22d3ee]"></div>
            <div class="absolute bottom-0 right-0 h-1/3 w-[2px] bg-cyan-400 shadow-[0_0_10px_#22d3ee]"></div>

            <!-- Top-Right & Bottom-Left Pink -->
            <div class="absolute top-0 right-0 w-1/2 h-[2px] bg-pink-500 shadow-[0_0_10px_#ec4899]"></div>
            <div class="absolute top-0 right-0 h-1/3 w-[2px] bg-pink-500 shadow-[0_0_10px_#ec4899]"></div>

            <div class="absolute bottom-0 left-0 w-1/2 h-[2px] bg-pink-500 shadow-[0_0_10px_#ec4899]"></div>
            <div class="absolute bottom-0 left-0 h-1/3 w-[2px] bg-pink-500 shadow-[0_0_10px_#ec4899]"></div>
            
            <!-- Corner Accents -->
            <div class="absolute top-[-2px] left-[-2px] w-4 h-4 border-t-2 border-l-2 border-cyan-300 rounded-tl-lg"></div>
            <div class="absolute top-[-2px] right-[-2px] w-4 h-4 border-t-2 border-r-2 border-pink-400 rounded-tr-lg"></div>
            <div class="absolute bottom-[-2px] left-[-2px] w-4 h-4 border-b-2 border-l-2 border-pink-400 rounded-bl-lg"></div>
            <div class="absolute bottom-[-2px] right-[-2px] w-4 h-4 border-b-2 border-r-2 border-cyan-300 rounded-br-lg"></div>
        </div>
        
        <!-- Glow effects (Background) -->
        <div class="absolute top-0 left-0 w-full h-full bg-gradient-to-b from-purple-900/10 via-transparent to-purple-900/10 pointer-events-none"></div>

        <!-- Header -->
        <div class="anim-element mt-2 sm:mt-4 flex items-center gap-2 mb-4 sm:mb-6">
            <svg class="w-6 h-6 sm:w-8 sm:h-8 text-pink-500 drop-shadow-[0_0_5px_rgba(255,0,255,0.8)]" fill="currentColor" viewBox="0 0 24 24">
                <path d="M12 3L1 9L12 15L21 10.09V17H23V9M5 13.18V17.18L12 21L19 17.18V13.18L12 17L5 13.18Z"/>
            </svg>
            <h1 class="font-orbitron font-bold text-xl sm:text-2xl tracking-wider text-white">CERTIFICADO</h1>
        </div>

        <!-- Logo -->
        <div class="anim-element mb-4 sm:mb-6 flex flex-col items-center">
            <h2 class="font-orbitron font-bold text-3xl sm:text-4xl neon-text-pink mb-2">CourseV</h2>
            <div class="text-5xl sm:text-6xl font-bold text-purple-500 drop-shadow-[0_0_10px_rgba(189,0,255,0.8)] animate-pulse">
                {&lt;/&gt;}
            </div>
        </div>

        <!-- Body Text -->
        <p class="anim-element font-poppins text-gray-300 text-xs sm:text-sm mb-4">
            Se certifica que<br>ha completado exitosamente el curso
        </p>

        <!-- Student Name -->
        <div class="anim-element w-full mb-4 sm:mb-6">
            <h3 class="font-orbitron font-bold text-xl sm:text-2xl text-pink-500 neon-text-pink uppercase break-words px-2">$studentName</h3>
            <div class="h-[1px] w-3/4 mx-auto bg-pink-500 shadow-[0_0_10px_#ff00ff] mt-2"></div>
        </div>

        <!-- Course Name -->
        <div class="anim-element w-full mb-6 sm:mb-8">
            <h3 class="font-orbitron font-bold text-lg sm:text-xl text-purple-400 neon-text-purple uppercase break-words px-2">$displayTopic</h3>
            <div class="h-[1px] w-2/3 mx-auto bg-purple-500 shadow-[0_0_10px_#bd00ff] mt-2"></div>
        </div>

        <!-- Grade -->
        <div class="anim-element mb-4 sm:mb-6">
            <p class="font-poppins text-gray-300 text-xs sm:text-sm mb-2">con una calificación de</p>
            <div class="flex items-center justify-center gap-2">
                <span class="text-yellow-400 text-xl sm:text-2xl">☆</span>
                <span class="font-orbitron font-bold text-2xl sm:text-3xl text-white">[$grade/10]</span>
                <span class="text-yellow-400 text-xl sm:text-2xl">☆</span>
            </div>
        </div>

        <!-- Stats Box -->
        <div class="anim-element w-full bg-purple-900/20 border border-purple-500/50 rounded-lg p-3 sm:p-4 mb-4 sm:mb-6 shadow-[0_0_15px_rgba(189,0,255,0.2)]">
            <div class="flex flex-col gap-2 text-left text-xs sm:text-sm font-poppins">
                <div class="flex items-center gap-2">
                    <span class="text-pink-500"></span>
                    <span class="text-gray-300">Progreso:</span>
                    <span class="text-white ml-auto">${porcentajeProgreso.toInt()}%</span>
                </div>
                <div class="flex items-center gap-2">
                    <span class="text-pink-500">🏆</span>
                    <span class="text-gray-300">Estado:</span>
                    <span class="text-green-400 font-bold ml-auto">$estado</span>
                </div>
            </div>
        </div>

        <!-- Instructor -->
        <div class="anim-element mt-auto mb-4">
            <p class="font-poppins text-xs text-gray-400">Impartido por:</p>
            <p id="instructorName" class="font-poppins font-bold text-white">$creatorName</p>
            <p id="instructorUsername" class="font-poppins text-xs text-pink-400">@$creatorUsername</p>
        </div>

        <!-- Footer -->
        <div class="anim-element w-full flex justify-between items-end border-t border-purple-800 pt-2 mt-2">
            <div class="text-left">
                <p class="font-poppins text-[10px] text-gray-400">Fecha emisión:</p>
                <p class="font-orbitron text-[10px] text-white">$currentDate</p>
            </div>
            <div class="text-right">
                <p class="font-poppins text-[10px] text-gray-400">ID del certificado:</p>
                <p class="font-orbitron text-[10px] text-white tracking-widest">$certificateId</p>
            </div>
        </div>

    </div>

    <script>
        // Animations using Anime.js
        document.addEventListener('DOMContentLoaded', () => {
            anime({
                targets: '.anim-element',
                translateY: [20, 0],
                opacity: [0, 1],
                delay: anime.stagger(100),
                easing: 'easeOutExpo',
                duration: 1000
            });

            anime({
                targets: '#certificate',
                boxShadow: [
                    '0 0 20px rgba(189, 0, 255, 0.2)',
                    '0 0 40px rgba(189, 0, 255, 0.4)',
                    '0 0 20px rgba(189, 0, 255, 0.2)'
                ],
                loop: true,
                duration: 3000,
                easing: 'easeInOutSine'
            });
        });

        // Download Functions
        function getFileName() {
            return 'certificado_${studentName.replace(Regex("[^a-zA-Z0-9]"), "_")}_${courseName.replace(Regex("[^a-zA-Z0-9]"), "_")}';
        }

        function downloadPNG() {
            const element = document.getElementById('certificate');
            html2canvas(element, {
                backgroundColor: '#0a0014',
                scale: 2
            }).then(canvas => {
                const link = document.createElement('a');
                link.download = getFileName() + '.png';
                link.href = canvas.toDataURL();
                link.click();
            });
        }

        function downloadPDF() {
            const element = document.getElementById('certificate');
            const { jsPDF } = window.jspdf;
            
            html2canvas(element, {
                scale: 2
            }).then(canvas => {
                const imgData = canvas.toDataURL('image/png');
                const pdf = new jsPDF({
                    orientation: 'portrait',
                    unit: 'px',
                    format: [canvas.width, canvas.height]
                });
                
                pdf.addImage(imgData, 'PNG', 0, 0, canvas.width, canvas.height);
                pdf.save(getFileName() + '.pdf');
            });
        }

        function downloadSVG() {
            const element = document.getElementById('certificate');
            domtoimage.toSvg(element)
                .then(function (dataUrl) {
                    const link = document.createElement('a');
                    link.download = getFileName() + '.svg';
                    link.href = dataUrl;
                    link.click();
                });
        }
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
