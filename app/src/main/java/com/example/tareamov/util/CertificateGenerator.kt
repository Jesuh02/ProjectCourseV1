package com.example.tareamov.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.service.CloudflareR2Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

object CertificateGenerator {
    private const val TAG = "CertificateGenerator"
    
    // Colores del tema CourseV (neón rosa/púrpura)
    private val NEON_PINK = Color.parseColor("#FF69B4")
    private val NEON_MAGENTA = Color.parseColor("#FF00FF")
    private val DARK_PURPLE = Color.parseColor("#1A0A2E")
    private val MEDIUM_PURPLE = Color.parseColor("#2D1B4E")
    private val LIGHT_PURPLE = Color.parseColor("#4A2C7A")
    private val WHITE = Color.WHITE
    private val GRAY_TEXT = Color.parseColor("#B0B0B0")

    /**
     * Datos del certificado para generar
     */
    data class CertificateData(
        val studentName: String,
        val studentUsername: String,
        val courseName: String,
        val creatorName: String,
        val creatorUsername: String,
        val grade: Float,
        val tasksCompleted: Int,
        val totalTasks: Int,
        val progress: Float,
        val status: String,
        val courseId: Long
    )

    fun generateCertificate(
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
                // Show loading toast
                Toast.makeText(context, "Generando certificado...", Toast.LENGTH_SHORT).show()

                // Get student and creator names from database
                val db = AppDatabase.getDatabase(context)

                val studentName = withContext(Dispatchers.IO) {
                    val user = db.usuarioDao().getUsuarioByUsername(studentUsername)
                    if (user != null) {
                        val persona = db.personaDao().getPersonaById(user.personaId)
                        "${persona?.nombres ?: ""} ${persona?.apellidos ?: ""}".trim().ifEmpty { studentUsername }
                    } else {
                        studentUsername
                    }
                }

                var creatorName = withContext(Dispatchers.IO) {
                    val user = db.usuarioDao().getUsuarioByUsername(creatorUsername)
                    if (user != null) {
                        val persona = db.personaDao().getPersonaById(user.personaId)
                        "${persona?.nombres ?: ""} ${persona?.apellidos ?: ""}".trim().ifEmpty { creatorUsername }
                    } else {
                        creatorUsername
                    }
                }

                // Get progress data for stats
                val userId = withContext(Dispatchers.IO) {
                    com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(studentUsername)
                }
                
                val progreso = if (userId != null) {
                    // Prefer remote progreso from Supabase when available, otherwise fallback to local Room
                    var remotePro: com.example.tareamov.data.entity.ProgresoEstudiante? = null
                    if (com.example.tareamov.service.SupabaseClient.isConfigured()) {
                        try {
                            remotePro = withContext(Dispatchers.IO) {
                                com.example.tareamov.service.SupabaseClient.fetchProgresoEstudiante(userId, courseId)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to fetch progreso from Supabase", e)
                        }
                    }

                    if (remotePro != null) remotePro
                    else withContext(Dispatchers.IO) { db.progresoEstudianteDao().getProgresoByUsuarioAndCurso(userId, courseId) }
                } else null

                // Try to get creator name from Supabase
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

                // Build certificate data
                val gradeFloat = grade.toFloatOrNull() ?: 0f
                val certData = CertificateData(
                    studentName = studentName,
                    studentUsername = studentUsername,
                    courseName = courseName,
                    creatorName = creatorName,
                    creatorUsername = creatorUsername,
                    grade = gradeFloat,
                    tasksCompleted = progreso?.tareasCompletadas ?: 0,
                    totalTasks = progreso?.tareasTotales ?: 0,
                    progress = progreso?.porcentajeProgreso ?: 100f,
                    status = progreso?.estado ?: "APROBADO",
                    courseId = courseId
                )

                // Generate certificate ID
                val certificateId = "CERT-${UUID.randomUUID().toString().take(5).uppercase()}-${UUID.randomUUID().toString().take(5).uppercase()}"

                // Create PDF document (portrait orientation like the image)
                val pdfDocument = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 portrait
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Draw certificate
                drawCertificateBackground(canvas)
                drawCertificateContent(canvas, certData, certificateId)

                pdfDocument.finishPage(page)

                // Save PDF to file
                val fileName = "Certificado_${courseName.replace(" ", "_")}_${System.currentTimeMillis()}.pdf"
                val file = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                    fileName
                )

                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { out ->
                        pdfDocument.writeTo(out)
                    }
                }

                pdfDocument.close()

                // Generate certificate URL for the web app (Vercel) instead of uploading to R2
                val certificateUrl = CertificateUrlBuilder.buildCertificateUrl(
                    studentName = studentName,
                    courseName = courseName,
                    grade = gradeFloat,
                    tasksCompleted = certData.tasksCompleted,
                    totalTasks = certData.totalTasks,
                    progress = certData.progress,
                    instructorName = creatorName,
                    instructorUsername = creatorUsername,
                    userId = userId ?: 0L,
                    courseId = courseId,
                    certId = certificateId
                )
                
                Log.i(TAG, "✅ URL de certificado web generada: $certificateUrl")

                // Update certificate issued date and URL in Supabase
                updateCertificateIssuedDate(context, studentUsername, courseId, certificateUrl)

                // Share the PDF
                sharePdf(context, file)

                // Show success toast
                Toast.makeText(context, "🎓 Certificado generado con éxito", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error generating certificate", e)
                Toast.makeText(
                    context,
                    "Error al generar certificado: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    
    /**
     * Actualiza la fecha de emisión del certificado y la URL en Supabase y localmente
     */
    fun updateCertificateIssuedDate(
        context: Context,
        studentUsername: String,
        courseId: Long,
        certificateUrl: String? = null
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                
                Log.d(TAG, "📝 Actualizando certificado para usuario=$studentUsername curso=$courseId")
                
                // Get user ID from username
                val userId = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(studentUsername)
                if (userId == null) {
                    Log.e(TAG, "❌ Failed to get user ID for username: $studentUsername")
                    return@launch
                }
                
                // Primero verificar si existe el registro de progreso
                var progreso = db.progresoEstudianteDao().getProgresoByUsuarioAndCurso(
                    userId,
                    courseId
                )
                
                // Si no existe, crear uno básico (esto no debería pasar si el estudiante aprobó)
                if (progreso == null) {
                    Log.w(TAG, "⚠️ No existe progreso para $studentUsername en curso $courseId, creando...")
                    
                    // Crear progreso básico
                    progreso = com.example.tareamov.data.entity.ProgresoEstudiante(
                        usuarioEstudiante = userId,
                        cursoId = courseId,
                        tareasCompletadas = 0,
                        tareasTotales = 0,
                        porcentajeProgreso = 0f,
                        calificacionPonderada = null,
                        promedio = null,
                        estado = "Ganado",
                        ultimaCalculadaEn = System.currentTimeMillis(),
                        certificadoEmitidoEn = null,
                        creadoEn = System.currentTimeMillis()
                    )
                    
                    // Guardar localmente
                    db.progresoEstudianteDao().upsert(progreso)
                    Log.d(TAG, "✅ Progreso creado localmente")
                }
                
                // ✨ IMPORTANTE: Solo actualizar la fecha si es la primera vez (certificadoEmitidoEn es null)
                if (progreso.certificadoEmitidoEn != null && certificateUrl == null) {
                    Log.i(TAG, "ℹ️ El certificado ya fue emitido previamente para $studentUsername en curso $courseId")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            context,
                            "Este certificado ya fue emitido el ${java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault()).format(progreso.certificadoEmitidoEn)}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@launch
                }
                
                // Actualizar localmente con la fecha del certificado (primera vez)
                val timestamp = System.currentTimeMillis()
                val updated = progreso.copy(certificadoEmitidoEn = timestamp)
                db.progresoEstudianteDao().upsert(updated)
                Log.d(TAG, "✅ Certificado guardado localmente (primera vez)")
                
                // Sincronizar a Supabase usando upsert completo
                val syncSuccess = com.example.tareamov.service.SupabaseClient.upsertProgresoEstudiante(updated)
                
                if (syncSuccess) {
                    Log.i(TAG, "✅ Certificado sincronizado a Supabase para $studentUsername en curso $courseId")
                } else {
                    Log.w(TAG, "⚠️ No se pudo sincronizar certificado a Supabase")
                }

                // Actualizar fecha remota
                val remoteUpdated = com.example.tareamov.service.SupabaseClient.updateCertificateIssuedDate(
                    userId,
                    courseId
                )
                if (remoteUpdated) {
                    Log.i(TAG, "✅ Fecha de certificado actualizada en Supabase")
                }
                
                // Si hay URL del certificado, guardarla en Supabase
                if (certificateUrl != null) {
                    val urlUpdated = com.example.tareamov.service.SupabaseClient.updateCertificateUrl(
                        userId,
                        courseId,
                        certificateUrl
                    )
                    if (urlUpdated) {
                        Log.i(TAG, "✅ URL del certificado guardada en Supabase: $certificateUrl")
                    } else {
                        Log.w(TAG, "⚠️ No se pudo guardar la URL del certificado en Supabase")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error actualizando fecha de certificado", e)
            }
        }
    }
    
    private fun drawCertificateBackground(canvas: Canvas) {
        val width = 595f
        val height = 842f
        
        // Fondo degradado oscuro púrpura (como la imagen)
        val backgroundPaint = Paint()
        backgroundPaint.shader = LinearGradient(
            0f, 0f, 0f, height,
            DARK_PURPLE, MEDIUM_PURPLE,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width, height, backgroundPaint)
        
        // Borde neón rosa/magenta con glow effect
        val borderPaint = Paint().apply {
            color = NEON_PINK
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
        }
        
        // Borde exterior con esquinas redondeadas
        val margin = 20f
        val borderRect = RectF(margin, margin, width - margin, height - margin)
        canvas.drawRoundRect(borderRect, 15f, 15f, borderPaint)
        
        // Segundo borde interior más sutil
        val innerBorderPaint = Paint().apply {
            color = Color.parseColor("#80FF69B4") // Rosa semi-transparente
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        val innerMargin = 25f
        val innerRect = RectF(innerMargin, innerMargin, width - innerMargin, height - innerMargin)
        canvas.drawRoundRect(innerRect, 12f, 12f, innerBorderPaint)
        
        // Estrellas decorativas en las esquinas
        drawDecorativeStars(canvas, width, height)
    }
    
    private fun drawDecorativeStars(canvas: Canvas, width: Float, height: Float) {
        val starColor = NEON_PINK
        
        // Esquinas superiores
        drawStar(canvas, 45f, 45f, 6f, starColor)
        drawStar(canvas, width - 45f, 45f, 6f, starColor)
        
        // Esquinas inferiores
        drawStar(canvas, 45f, height - 45f, 6f, starColor)
        drawStar(canvas, width - 45f, height - 45f, 6f, starColor)
        
        // Estrellas pequeñas adicionales
        drawStar(canvas, 70f, 70f, 3f, starColor)
        drawStar(canvas, width - 70f, 70f, 3f, starColor)
        drawStar(canvas, 70f, height - 70f, 3f, starColor)
        drawStar(canvas, width - 70f, height - 70f, 3f, starColor)
    }

    private fun drawCornerLaurelElement(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint, cornerType: String) {
        // Simplificado - no se usa en el nuevo diseño
    }

    private fun drawCornerDecorations(canvas: Canvas) {
        // Simplificado - reemplazado por drawDecorativeStars
    }
    
    private fun drawCertificateContent(
        canvas: Canvas,
        data: CertificateData,
        certificateId: String
    ) {
        val width = 595f
        val height = 842f
        val centerX = width / 2
        
        var yPos = 55f
        
        // ===== HEADER: Icono de certificado + "CERTIFICADO" =====
        // Dibujar icono de diploma/certificado más elaborado
        drawCertificateIcon(canvas, centerX - 80f, yPos + 5f, 20f)
        
        // Título "CERTIFICADO" con efecto neón rosa brillante
        val titlePaint = Paint().apply {
            color = NEON_PINK
            textSize = 32f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            letterSpacing = 0.12f
        }
        canvas.drawText("CERTIFICADO", centerX + 15f, yPos + 20f, titlePaint)
        
        yPos += 65f
        
        // ===== LOGO: "CourseV" grande con estilo neón magenta =====
        val logoPaint = Paint().apply {
            color = NEON_MAGENTA
            textSize = 52f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("CourseV", centerX, yPos, logoPaint)
        
        yPos += 50f
        
        // ===== Símbolo { </> } estilizado con llaves grandes =====
        // Dibujar las llaves y el símbolo de código como en la imagen
        drawCodeSymbol(canvas, centerX, yPos)
        
        yPos += 70f
        
        // ===== TEXTO: "Se certifica que" =====
        val labelPaint = Paint().apply {
            color = GRAY_TEXT
            textSize = 15f
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("Se certifica que", centerX, yPos, labelPaint)
        canvas.drawText("ha completado exitosamente el curso", centerX, yPos + 20f, labelPaint)
        
        yPos += 55f
        
        // ===== NOMBRE DEL ESTUDIANTE =====
        // Línea decorativa superior magenta
        drawNeonLine(canvas, centerX - 160f, yPos - 5f, centerX + 160f, yPos - 5f)
        
        val studentNamePaint = Paint().apply {
            color = NEON_MAGENTA
            textSize = 26f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        fitTextToWidth(data.studentName, studentNamePaint, 420f)
        canvas.drawText(data.studentName, centerX, yPos + 25f, studentNamePaint)
        
        // Línea decorativa inferior
        drawNeonLine(canvas, centerX - 160f, yPos + 38f, centerX + 160f, yPos + 38f)
        
        yPos += 75f
        
        // ===== NOMBRE DEL CURSO =====
        drawNeonLine(canvas, centerX - 180f, yPos - 5f, centerX + 180f, yPos - 5f)
        
        val courseNamePaint = Paint().apply {
            color = NEON_MAGENTA
            textSize = 22f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        fitTextToWidth(data.courseName, courseNamePaint, 450f)
        canvas.drawText(data.courseName, centerX, yPos + 22f, courseNamePaint)
        
        drawNeonLine(canvas, centerX - 180f, yPos + 35f, centerX + 180f, yPos + 35f)
        
        yPos += 65f
        
        // ===== CALIFICACIÓN =====
        val gradeLabel = Paint().apply {
            color = WHITE
            textSize = 15f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("con una calificación de", centerX, yPos, gradeLabel)
        
        yPos += 40f
        
        // Estrellas y calificación en formato [ X.X/10 ]
        val gradeText = String.format("%.1f/10", data.grade)
        
        // Estrella izquierda (outline)
        drawStarOutline(canvas, centerX - 85f, yPos - 8f, 14f, NEON_PINK)
        
        val gradePaint = Paint().apply {
            color = WHITE
            textSize = 32f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("[$gradeText]", centerX, yPos + 5f, gradePaint)
        
        // Estrella derecha (outline)
        drawStarOutline(canvas, centerX + 85f, yPos - 8f, 14f, NEON_PINK)
        
        yPos += 45f
        
        // ===== CAJA DE ESTADÍSTICAS =====
        drawStatsBox(canvas, centerX, yPos, data)
        
        yPos += 115f
        
        // ===== INFORMACIÓN DEL CREADOR =====
        val creatorLabelPaint = Paint().apply {
            color = GRAY_TEXT
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("Impartido por: ${data.creatorName}", centerX, yPos, creatorLabelPaint)
        
        val usernamePaint = Paint().apply {
            color = NEON_PINK
            textSize = 13f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("@${data.creatorUsername}", centerX, yPos + 18f, usernamePaint)
        
        // ===== FOOTER: Fecha e ID =====
        val footerY = height - 45f
        
        val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        
        val footerPaint = Paint().apply {
            color = GRAY_TEXT
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
        }
        canvas.drawText("Fecha emisión: $currentDate", 35f, footerY, footerPaint)
        
        footerPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("ID del certificado: $certificateId", width - 35f, footerY, footerPaint)
    }
    
    /**
     * Dibuja el símbolo { </> } estilizado como en la imagen
     */
    private fun drawCodeSymbol(canvas: Canvas, centerX: Float, y: Float) {
        val symbolPaint = Paint().apply {
            color = NEON_MAGENTA
            textSize = 55f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            strokeWidth = 2f
        }
        
        // Dibujar { </> } con estilo neón
        // Las llaves más grandes y separadas
        val bracePaint = Paint().apply {
            color = NEON_MAGENTA
            textSize = 70f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        
        // Llave izquierda {
        canvas.drawText("{", centerX - 65f, y + 10f, bracePaint)
        
        // Símbolo </> en el centro
        val codePaint = Paint().apply {
            color = NEON_MAGENTA
            textSize = 45f
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("</>", centerX, y + 5f, codePaint)
        
        // Llave derecha }
        canvas.drawText("}", centerX + 65f, y + 10f, bracePaint)
    }
    
    private fun drawCertificateIcon(canvas: Canvas, x: Float, y: Float, size: Float) {
        val paint = Paint().apply {
            color = NEON_PINK
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        
        // Dibujar un diploma/scroll más elaborado
        // Rectángulo principal del diploma
        val rect = RectF(x - size/2, y - size/2, x + size/2, y + size/2)
        canvas.drawRoundRect(rect, 3f, 3f, paint)
        
        // Líneas decorativas dentro del diploma
        canvas.drawLine(x - size/3, y - size/4, x + size/3, y - size/4, paint)
        canvas.drawLine(x - size/3, y, x + size/3, y, paint)
        canvas.drawLine(x - size/3, y + size/4, x + size/3, y + size/4, paint)
    }
    
    private fun drawNeonLine(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val linePaint = Paint().apply {
            color = NEON_MAGENTA
            strokeWidth = 1.5f
            isAntiAlias = true
        }
        canvas.drawLine(x1, y1, x2, y2, linePaint)
    }
    
    private fun drawStatsBox(canvas: Canvas, centerX: Float, y: Float, data: CertificateData) {
        val boxWidth = 280f
        val boxHeight = 85f
        val boxLeft = centerX - boxWidth / 2
        val boxTop = y
        
        // Fondo de la caja (más oscuro con borde neón)
        val boxBgPaint = Paint().apply {
            color = Color.parseColor("#15081F")
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val boxRect = RectF(boxLeft, boxTop, boxLeft + boxWidth, boxTop + boxHeight)
        canvas.drawRoundRect(boxRect, 10f, 10f, boxBgPaint)
        
        // Borde neón de la caja
        val boxBorderPaint = Paint().apply {
            color = NEON_MAGENTA
            style = Paint.Style.STROKE
            strokeWidth = 2f
            isAntiAlias = true
        }
        canvas.drawRoundRect(boxRect, 10f, 10f, boxBorderPaint)
        
        // Contenido de la caja
        val statsPaint = Paint().apply {
            color = WHITE
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
        }
        
        val iconPaint = Paint().apply {
            color = NEON_PINK
            textSize = 14f
            isAntiAlias = true
        }
        
        var statY = boxTop + 25f
        val statX = boxLeft + 20f
        
        // Tareas completadas
        canvas.drawText("📊", statX, statY, iconPaint)
        canvas.drawText("Tareas completadas: ${data.tasksCompleted}/${data.totalTasks}", statX + 25f, statY, statsPaint)
        
        statY += 25f
        
        // Progreso
        canvas.drawText("📈", statX, statY, iconPaint)
        canvas.drawText("Progreso: ${String.format("%.0f", data.progress)}%", statX + 25f, statY, statsPaint)
        
        statY += 25f
        
        // Estado
        val statusText = if (data.status.equals("Ganado", ignoreCase = true)) "APROBADO" else data.status.uppercase()
        canvas.drawText("🏆", statX, statY, iconPaint)
        canvas.drawText("Estado: $statusText", statX + 25f, statY, statsPaint)
    }
    
    private fun fitTextToWidth(text: String, paint: Paint, maxWidth: Float) {
        while (paint.measureText(text) > maxWidth && paint.textSize > 12f) {
            paint.textSize = paint.textSize - 1f
        }
    }
    
    // Dibujar estrella con solo contorno (outline) para calificación
    private fun drawStarOutline(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            isAntiAlias = true
        }

        val path = android.graphics.Path()
        val outerRadius = radius
        val innerRadius = radius * 0.4f

        var currentAngle = -Math.PI / 2
        val angleIncrement = Math.PI / 5

        path.moveTo(
            cx + (outerRadius * Math.cos(currentAngle)).toFloat(),
            cy + (outerRadius * Math.sin(currentAngle)).toFloat()
        )

        for (i in 0 until 5) {
            currentAngle += angleIncrement
            path.lineTo(
                cx + (innerRadius * Math.cos(currentAngle)).toFloat(),
                cy + (innerRadius * Math.sin(currentAngle)).toFloat()
            )

            currentAngle += angleIncrement
            path.lineTo(
                cx + (outerRadius * Math.cos(currentAngle)).toFloat(),
                cy + (outerRadius * Math.sin(currentAngle)).toFloat()
            )
        }

        path.close()
        canvas.drawPath(path, paint)
    }
    
    // Dibujar estrella rellena
    private fun drawStar(canvas: Canvas, cx: Float, cy: Float, radius: Float, color: Int) {
        val paint = Paint().apply {
            this.color = color
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        val path = android.graphics.Path()
        val outerRadius = radius
        val innerRadius = radius * 0.4f

        var currentAngle = -Math.PI / 2
        val angleIncrement = Math.PI / 5

        path.moveTo(
            cx + (outerRadius * Math.cos(currentAngle)).toFloat(),
            cy + (outerRadius * Math.sin(currentAngle)).toFloat()
        )

        for (i in 0 until 5) {
            currentAngle += angleIncrement
            path.lineTo(
                cx + (innerRadius * Math.cos(currentAngle)).toFloat(),
                cy + (innerRadius * Math.sin(currentAngle)).toFloat()
            )

            currentAngle += angleIncrement
            path.lineTo(
                cx + (outerRadius * Math.cos(currentAngle)).toFloat(),
                cy + (outerRadius * Math.sin(currentAngle)).toFloat()
            )
        }

        path.close()
        canvas.drawPath(path, paint)
    }
    
    private fun sharePdf(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            // Check if there's an app that can handle this intent
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // If no PDF viewer is available, try to share the file
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Compartir certificado"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing PDF", e)
            Toast.makeText(
                context,
                "Error al compartir el certificado: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}