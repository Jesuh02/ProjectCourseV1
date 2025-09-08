package com.example.tareamov.util

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CertificateGenerator {
    private const val TAG = "CertificateGenerator"

    fun generateCertificate(
        context: Context,
        studentUsername: String,
        creatorUsername: String,
        courseName: String,
        courseTopic: String,
        grade: String
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
                        // Use the correct field names: nombres and apellidos
                        "${persona?.nombres ?: ""} ${persona?.apellidos ?: ""}"
                    } else {
                        studentUsername
                    }
                }

                val creatorName = withContext(Dispatchers.IO) {
                    val user = db.usuarioDao().getUsuarioByUsername(creatorUsername)
                    if (user != null) {
                        val persona = db.personaDao().getPersonaById(user.personaId)
                        // Use the correct field names: nombres and apellidos
                        "${persona?.nombres ?: ""} ${persona?.apellidos ?: ""}"
                    } else {
                        creatorUsername
                    }
                }

                // Create PDF document
                val pdfDocument = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(842, 595, 1).create() // A4 landscape
                val page = pdfDocument.startPage(pageInfo)
                val canvas = page.canvas

                // Draw certificate background
                drawCertificateBackground(canvas)

                // Draw certificate content
                drawCertificateContent(
                    canvas,
                    studentName,
                    courseName,
                    courseTopic,
                    creatorName,
                    grade
                )

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

                // Share the PDF
                sharePdf(context, file)

                // Show success toast
                Toast.makeText(context, "Certificado generado con éxito", Toast.LENGTH_SHORT).show()

            } catch (e: Exception) {
                Log.e(TAG, "Error generating certificate", e)
                Toast.makeText(
                    context,
                    "Error al generar certificado: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }    private fun drawCertificateBackground(canvas: Canvas) {
        val paint = Paint()
        // Solid dark purple background, similar to the image
        paint.color = Color.parseColor("#3A1F5F") // A deep purple, adjust if needed
        canvas.drawRect(0f, 0f, 842f, 595f, paint)

        // Draw main decorative border
        val borderPaint = Paint().apply {
            color = Color.parseColor("#D4AF37") // Gold border
            style = Paint.Style.STROKE
            strokeWidth = 7f // A prominent border line
            isAntiAlias = true
        }
        // Inset border to make space for corner laurels within the page dimensions
        val borderMargin = 30f
        val borderRect = android.graphics.RectF(borderMargin, borderMargin, 842f - borderMargin, 595f - borderMargin)
        canvas.drawRoundRect(borderRect, 25f, 25f, borderPaint) // Rounded corners for the border

        // Draw corner decorations (laurels and stars)
        drawCornerDecorations(canvas)
    }

    private fun drawCornerLaurelElement(canvas: Canvas, x: Float, y: Float, size: Float, paint: Paint, cornerType: String) {
        canvas.save()
        // x, y is the reference corner point for the laurel's bounding box (size x size)
        // The drawing is done as if for TL, then transformed.
        canvas.translate(x, y);        when (cornerType) {
            "TL" -> { /* Default: draws from (0,0) to (size,size) */ }
            "TR" -> { canvas.scale(-1f, 1f); canvas.translate(-size, 0f) }
            "BL" -> { canvas.scale(1f, -1f); canvas.translate(0f, -size) }
            "BR" -> { canvas.scale(-1f, -1f); canvas.translate(-size, -size) }
        }

        val stemPaint = Paint(paint).apply { style = Paint.Style.STROKE; strokeWidth = size * 0.06f; isAntiAlias = true }
        val leafPaint = Paint(paint).apply { style = Paint.Style.FILL; isAntiAlias = true }

        // Stem: A gentle curve within the 'size x size' box
        val stemPath = android.graphics.Path()
        stemPath.moveTo(size * 0.15f, size * 0.15f) // Start near the corner
        stemPath.quadTo(size * 0.25f, size * 0.6f, size * 0.8f, size * 0.8f) // Curve inwards
        canvas.drawPath(stemPath, stemPaint)

        val numLeaves = 3
        val leafLength = size * 0.30f
        val leafWidth = size * 0.10f

        for (i in 0..numLeaves) {
            val t = (i.toFloat() + 0.5f) / (numLeaves + 1f)

            val p0x = size*0.15f; val p0y = size*0.15f
            val p1x = size*0.25f; val p1y = size*0.6f
            val p2x = size*0.8f; val p2y = size*0.8f
            val omt = 1f - t
            val posX = omt * omt * p0x + 2f * omt * t * p1x + t * t * p2x
            val posY = omt * omt * p0y + 2f * omt * t * p1y + t * t * p2y

            val dx = 2f * omt * (p1x - p0x) + 2f * t * (p2x - p1x)
            val dy = 2f * omt * (p1y - p0y) + 2f * t * (p2y - p1y)
            val angleDegrees = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble())).toFloat()

            canvas.save()
            canvas.translate(posX, posY)
            canvas.rotate(angleDegrees)

            canvas.save()
            canvas.rotate(40f) // Leaf angle from stem
            canvas.drawOval(-leafLength / 2f, -leafWidth / 2f, leafLength / 2f, leafWidth / 2f, leafPaint)
            canvas.restore()

            canvas.save()
            canvas.rotate(-40f) // Mirrored leaf
            canvas.drawOval(-leafLength / 2f, -leafWidth / 2f, leafLength / 2f, leafWidth / 2f, leafPaint)
            canvas.restore()

            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawCornerDecorations(canvas: Canvas) {
        val goldPaint = Paint().apply {
            color = Color.parseColor("#D4AF37") // Gold
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val laurelSize = 70f // Size of the laurel element's bounding box
        val laurelOffset = 35f // Offset from page edge to laurel anchor

        // Draw laurels in each corner
        drawCornerLaurelElement(canvas, laurelOffset, laurelOffset, laurelSize, goldPaint, "TL")
        drawCornerLaurelElement(canvas, 842f - laurelOffset, laurelOffset, laurelSize, goldPaint, "TR")
        drawCornerLaurelElement(canvas, laurelOffset, 595f - laurelOffset, laurelSize, goldPaint, "BL")
        drawCornerLaurelElement(canvas, 842f - laurelOffset, 595f - laurelOffset, laurelSize, goldPaint, "BR")

        val starColor = Color.parseColor("#D4AF37")
        // Stars near corners, adjusted to be pleasing with laurels
        drawStar(canvas, laurelOffset + laurelSize * 0.5f, laurelOffset + laurelSize * 1.2f, 8f, starColor)
        drawStar(canvas, laurelOffset + laurelSize * 1.2f, laurelOffset + laurelSize * 0.5f, 6f, starColor)

        drawStar(canvas, 842f - (laurelOffset + laurelSize * 0.5f), laurelOffset + laurelSize * 1.2f, 8f, starColor)
        drawStar(canvas, 842f - (laurelOffset + laurelSize * 1.2f), laurelOffset + laurelSize * 0.5f, 6f, starColor)

        drawStar(canvas, laurelOffset + laurelSize * 0.5f, 595f - (laurelOffset + laurelSize * 1.2f), 8f, starColor)
        drawStar(canvas, laurelOffset + laurelSize * 1.2f, 595f - (laurelOffset + laurelSize * 0.5f), 6f, starColor)

        drawStar(canvas, 842f - (laurelOffset + laurelSize * 0.5f), 595f - (laurelOffset + laurelSize * 1.2f), 8f, starColor)
        drawStar(canvas, 842f - (laurelOffset + laurelSize * 1.2f), 595f - (laurelOffset + laurelSize * 0.5f), 6f, starColor)

        // Smaller stars/sparkles as seen in the image
        drawStar(canvas, 200f, 275f, 5f, starColor) // Left of student name area
        drawStar(canvas, 842f - 200f, 275f, 5f, starColor) // Right of student name area
        drawStar(canvas, 230f, 430f, 5f, starColor) // Left of topic area
        drawStar(canvas, 842f - 230f, 430f, 5f, starColor) // Right of topic area
    }    private fun drawCertificateContent(
        canvas: Canvas,
        studentName: String,
        courseName: String,
        courseTopic: String,
        creatorName: String,
        grade: String
    ) {
        val width = 842f
        val height = 595f
        val centerX = width / 2
        val contentMargin = 60f // Margin for text content from the border

        // Helper to fit text within a max width by reducing text size
        fun fitText(
            text: String,
            paint: Paint,
            maxWidth: Float,
            minSize: Float = 18f
        ): Float {
            var textSize = paint.textSize
            paint.textSize = textSize
            while (paint.measureText(text) > maxWidth && textSize > minSize) {
                textSize -= 1f // Finer adjustment for better fitting
                paint.textSize = textSize
            }
            return textSize
        }

        // Define colors matching the image
        val goldColor = Color.parseColor("#D4AF37")
        val lightGoldColor = Color.parseColor("#F0E0A8") // A lighter, softer gold for some text

        // Grade text (top left, as per image)
        val gradeTextPaint = Paint().apply {
            color = goldColor
            textSize = 26f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) // Changed font for clarity
            textAlign = Paint.Align.LEFT
            isAntiAlias = true
        }
        canvas.drawText("Calificación: $grade/10", contentMargin + 20f, contentMargin + 40f, gradeTextPaint)

        // Main title - CERTIFICADO
        val titlePaint = Paint().apply {
            color = goldColor
            textSize = 68f // Slightly adjusted size
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            letterSpacing = 0.08f // Adjusted letter spacing
        }
        canvas.drawText("CERTIFICADO", centerX, 150f, titlePaint)

        // Subtitle - DE FINALIZACIÓN
        val subtitlePaint = Paint().apply {
            color = goldColor
            textSize = 40f // Slightly adjusted size
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            letterSpacing = 0.04f // Adjusted letter spacing
        }
        canvas.drawText("DE FINALIZACIÓN", centerX, 200f, subtitlePaint)

        // "Se otorga a" text (in italic, lighter gold)
        val awardTextPaint = Paint().apply {
            color = lightGoldColor
            textSize = 26f // Adjusted size
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("Se otorga a", centerX, 255f, awardTextPaint)

        // Student name (large, prominent, gold)
        val studentNamePaint = Paint().apply {
            color = goldColor
            textSize = 48f // Adjusted size
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        val maxNameWidth = width - (2 * contentMargin) - 40f // Max width for student name
        fitText(studentName.uppercase(), studentNamePaint, maxNameWidth, 30f)
        canvas.drawText(studentName.uppercase(), centerX, 305f, studentNamePaint)

        // "por haber completado con éxito" text (lighter gold, italic)
        val completionTextPaint = Paint().apply {
            color = lightGoldColor
            textSize = 22f // Adjusted size
            typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        canvas.drawText("por haber completado con éxito", centerX, 345f, completionTextPaint)

        // Course name (prominent, gold)
        val courseNamePaint = Paint().apply {
            color = goldColor
            textSize = 40f // Adjusted size
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        fitText(courseName.uppercase(), courseNamePaint, maxNameWidth, 26f)
        canvas.drawText(courseName.uppercase(), centerX, 385f, courseNamePaint)


        // Date format
        val dateFormat = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", Locale("es", "ES"))
        val currentDate = dateFormat.format(Date())

        // Bottom section with date and signature
        val bottomTextY = height - 70f
        val bottomLabelY = height - 50f
        val lineY = height - 85f
        val signatureLineWidth = 150f

        val bottomTextPaint = Paint().apply {
            color = lightGoldColor // Lighter gold for date/signature text
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        val labelPaint = Paint().apply {
            color = goldColor // Gold for labels
            textSize = 14f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            letterSpacing = 0.1f
        }

        val linePaint = Paint().apply {
            color = goldColor
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }

        // Date (left side)
        val dateX = width * 0.30f // Adjusted position
        canvas.drawText(currentDate, dateX, bottomTextY, bottomTextPaint)
        canvas.drawLine(dateX - signatureLineWidth / 2, lineY, dateX + signatureLineWidth / 2, lineY, linePaint)
        canvas.drawText("FECHA", dateX, bottomLabelY, labelPaint)

        // Creator signature (right side)
        val signatureX = width * 0.70f // Adjusted position
        // Fit creator name if too long
        fitText(creatorName, bottomTextPaint, signatureLineWidth, 14f)
        canvas.drawText(creatorName, signatureX, bottomTextY, bottomTextPaint)
        canvas.drawLine(signatureX - signatureLineWidth / 2, lineY, signatureX + signatureLineWidth / 2, lineY, linePaint)
        canvas.drawText("FIRMA", signatureX, bottomLabelY, labelPaint)
    }
      // Add a method to draw a star (keeping the existing implementation)
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
    }private fun sharePdf(context: Context, file: File) {
        try {            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.service.provider",
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