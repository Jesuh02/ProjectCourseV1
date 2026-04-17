package com.example.tareamov.util

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.tareamov.data.entity.Subject
import com.example.tareamov.data.entity.Task
import com.example.tareamov.data.entity.TaskSubmission
import com.example.tareamov.data.entity.Course
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Genera reportes de notas (PDF / CSV) y permite compartirlos.
 */
object GradeReportHelper {

    // ── INCAT institution header constants ──────────────────────────────
    private const val INCAT_LOGO_URL = "https://pub-9f393625246c4018b5613be60b01bda1.r2.dev/incat.jpg"
    private val INCAT_HEADER_LINES = arrayOf(
        "POLITECNICO INSTITUCIONAL DEL CARIBE \"INCAT\"",
        "Licencia de funcionamiento Resolución No 439 del 26 /10/ 2010. Emanada de S. E. M",
        "Licencia de funcionamiento resolución Nº1952 del 17/12/2010. Emanada de S. E. D.",
        "Institución Educativa De Formación para el trabajo y el desarrollo humano",
        "NIT: 900391687-0"
    )
    private const val INCAT_FOOTER_SLOGAN = "Politécnico \"INCAT\", forjando líderes para triunfar!"
    private const val INCAT_FOOTER_ADDRESS = "SEDE PRINCIPAL CALLE 11ª # 11-85  TEL. 3106357993-3156824740"
    private const val INCAT_FOOTER_EMAIL = "E-mail: politecnicoincat@gmail.com"
    private const val INCAT_FOOTER_CITY = "RIOHACHA- LA GUAJIRA"
    private const val INCAT_SIGNATURE_NAME = "AQUILES AMAYA IGUARAN"
    private const val INCAT_SIGNATURE_TITLE = "RECOR"

    private var cachedLogoBitmap: Bitmap? = null

    /** Downloads and caches the INCAT logo bitmap. Must be called from a background thread. */
    private fun getIncatLogo(): Bitmap? {
        cachedLogoBitmap?.let { return it }
        return try {
            val conn = URL(INCAT_LOGO_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.doInput = true
            conn.connect()
            val bmp = BitmapFactory.decodeStream(conn.inputStream)
            conn.disconnect()
            cachedLogoBitmap = bmp
            bmp
        } catch (_: Exception) { null }
    }

    /**
     * Draws the INCAT institution header on a PDF canvas.
     * Returns the new Y position after the header.
     */
    private fun drawIncatHeader(canvas: android.graphics.Canvas, margin: Float, contentWidth: Float, startY: Float): Float {
        var y = startY
        val logo = getIncatLogo()
        val logoSize = 60f
        val textStartX: Float

        if (logo != null) {
            val dest = RectF(margin, y, margin + logoSize, y + logoSize)
            canvas.drawBitmap(logo, null, dest, null)
            textStartX = margin + logoSize + 12f
        } else {
            textStartX = margin
        }

        val textWidth = contentWidth - (textStartX - margin)
        val titlePaint = Paint().apply { color = Color.parseColor("#8B0000"); textSize = 11f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val linePaint = Paint().apply { color = Color.parseColor("#333333"); textSize = 8f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val nitPaint = Paint().apply { color = Color.parseColor("#8B0000"); textSize = 9f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val centerX = textStartX + textWidth / 2

        canvas.drawText(INCAT_HEADER_LINES[0], centerX, y + 12f, titlePaint)
        canvas.drawText(INCAT_HEADER_LINES[1], centerX, y + 24f, linePaint)
        canvas.drawText(INCAT_HEADER_LINES[2], centerX, y + 34f, linePaint)
        canvas.drawText(INCAT_HEADER_LINES[3], centerX, y + 46f, linePaint.apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); textSize = 9f })
        canvas.drawText(INCAT_HEADER_LINES[4], centerX, y + 58f, nitPaint)

        y += maxOf(logoSize, 62f) + 8f
        val dividerPaint = Paint().apply { color = Color.parseColor("#8B0000"); strokeWidth = 2f }
        canvas.drawLine(margin, y, margin + contentWidth, y, dividerPaint)
        y += 12f
        return y
    }

    /**
     * Draws the INCAT signature block and footer on a PDF canvas.
     * Call this after all content is drawn, before finishPage().
     */
    private fun drawIncatSignatureAndFooter(canvas: android.graphics.Canvas, margin: Float, contentWidth: Float, y: Float, pageHeight: Float = 842f): Float {
        // Calculate footer total height: signature(20+14+12+20) + divider(10) + slogan(11) + address(10) + email(10) + city = ~107f
        val footerBlockHeight = 107f
        // Position footer at the bottom of the page
        var currentY = maxOf(y + 20f, pageHeight - margin - footerBlockHeight)
        // Signature line
        val linePaint = Paint().apply { color = Color.parseColor("#8B0000"); strokeWidth = 1f }
        canvas.drawLine(margin, currentY, margin + 160f, currentY, linePaint)
        currentY += 14f
        val namePaint = Paint().apply { color = Color.parseColor("#000000"); textSize = 10f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
        canvas.drawText(INCAT_SIGNATURE_NAME, margin, currentY, namePaint)
        currentY += 12f
        val titlePaint = Paint().apply { color = Color.parseColor("#333333"); textSize = 9f; isAntiAlias = true }
        canvas.drawText(INCAT_SIGNATURE_TITLE, margin, currentY, titlePaint)
        currentY += 20f
        // Footer divider
        canvas.drawLine(margin, currentY, margin + contentWidth, currentY, linePaint)
        currentY += 10f
        val sloganPaint = Paint().apply { color = Color.parseColor("#8B0000"); textSize = 8f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC); isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val centerX = margin + contentWidth / 2
        canvas.drawText(INCAT_FOOTER_SLOGAN, centerX, currentY, sloganPaint)
        currentY += 11f
        val footerPaint = Paint().apply { color = Color.parseColor("#333333"); textSize = 8f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        canvas.drawText(INCAT_FOOTER_ADDRESS, centerX, currentY, footerPaint)
        currentY += 10f
        val emailPaint = Paint().apply { color = Color.parseColor("#8B0000"); textSize = 8f; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        canvas.drawText(INCAT_FOOTER_EMAIL, centerX, currentY, emailPaint)
        currentY += 10f
        canvas.drawText(INCAT_FOOTER_CITY, centerX, currentY, footerPaint)
        return currentY
    }

    /**
     * Returns the INCAT HTML footer for Word/HTML documents.
     */
    private fun buildIncatHtmlFooter(): String {
        return """<div style="margin-top:40px">
  <div style="display:inline-block;min-width:180px">
    <div style="border-top:1px solid #8B0000;margin-bottom:6px"></div>
    <div style="font-size:11px;font-weight:700;text-transform:uppercase;color:#000">$INCAT_SIGNATURE_NAME</div>
    <div style="font-size:10px;color:#555;margin-top:2px">$INCAT_SIGNATURE_TITLE</div>
  </div>
  <div style="position:fixed;bottom:0;left:0;right:0;padding:10px 20px;border-top:2px solid #8B0000;text-align:center;background:#fff">
    <div style="font-size:10px;font-style:italic;color:#8B0000;margin-bottom:3px">$INCAT_FOOTER_SLOGAN</div>
    <div style="font-size:9px;font-weight:700;color:#000;margin:2px 0">$INCAT_FOOTER_ADDRESS</div>
    <div style="font-size:9px;color:#8B0000;margin:2px 0">$INCAT_FOOTER_EMAIL</div>
    <div style="font-size:9px;font-weight:700;color:#000;margin:2px 0">$INCAT_FOOTER_CITY</div>
  </div>
</div>"""
    }

    /**
     * Returns the INCAT HTML header for Word/HTML documents.
     */
    private fun buildIncatHtmlHeader(): String {        return """<div style="display:flex;align-items:center;gap:16px;justify-content:center;margin-bottom:20px;border-bottom:2px solid #8B0000;padding-bottom:16px">
  <img src="$INCAT_LOGO_URL" alt="Escudo INCAT" style="width:80px;height:auto;object-fit:contain" />
  <div style="text-align:center;flex:1">
    <div style="font-size:16px;font-weight:800;color:#8B0000;text-transform:uppercase;letter-spacing:1px">${INCAT_HEADER_LINES[0]}</div>
    <div style="font-size:10px;color:#333;margin-top:3px">${INCAT_HEADER_LINES[1]}</div>
    <div style="font-size:10px;color:#333">${INCAT_HEADER_LINES[2]}</div>
    <div style="font-size:11px;color:#333;margin-top:4px;font-weight:600">${INCAT_HEADER_LINES[3]}</div>
    <div style="font-size:12px;color:#8B0000;font-weight:700;margin-top:2px">${INCAT_HEADER_LINES[4]}</div>
  </div>
</div>"""
    }

    data class SubjectReport(
        val subjectName: String,
        val teacherName: String?,
        val tasks: List<TaskReport>,
        val average: Float?,
        val studentAverages: Map<String, Float> = emptyMap()
    )

    data class TaskReport(
        val studentKey: String,
        val studentName: String,
        val studentFullName: String? = null,
        val studentCedula: String? = null,
        val title: String,
        val grade: Float?,
        val submissionDate: Long?,
        val feedback: String?,
        val gradedByUsername: String? = null,
        val notSubmitted: Boolean = false
    )

    fun buildReport(
        subjects: List<Subject>,
        allTasks: List<Task>,
        submissions: List<TaskSubmission>,
        topics: List<com.example.tareamov.data.entity.Topic>,
        teachers: Map<Long, String> = emptyMap()
    ): List<SubjectReport> {
        // Map taskId → all submissions for that task
        val submissionsByTask = submissions.groupBy { it.taskId }

        val knownStudents = submissions
            .map { resolveStudentKey(it) }
            .toSet()

        val studentInfoByKey = mutableMapOf<String, Pair<String, String?>>()
        for (sub in submissions) {
            val studentKey = resolveStudentKey(sub)
            studentInfoByKey[studentKey] = Pair(
                displayStudentName(sub.studentFullName),
                sub.studentCedula
            )
        }

        return subjects.map { subject ->
            val topicIds = topics.filter { it.subjectId == subject.id || it.courseId == subject.id }
                .map { it.id }.toSet()

            val subjectTasks = allTasks.filter { topicIds.contains(it.topicId) }

            val taskReports = mutableListOf<TaskReport>()
            for (task in subjectTasks) {
                val taskSubs = submissionsByTask[task.id] ?: emptyList()
                val submittedStudents = taskSubs
                    .map { resolveStudentKey(it) }
                    .toSet()
                for (sub in taskSubs) {
                    val studentKey = resolveStudentKey(sub)
                    taskReports.add(TaskReport(
                        studentKey = studentKey,
                        studentName = displayStudentName(sub.studentFullName),
                        studentFullName = normalizePersonName(sub.studentFullName),
                        studentCedula = sub.studentCedula,
                        title = task.name.ifBlank { sub.taskName ?: "Sin título" },
                        grade = sub.grade,
                        submissionDate = sub.submissionDate.takeIf { it > 0 },
                        feedback = sub.feedback,
                        gradedByUsername = sub.gradedByUsername
                    ))
                }
                // Add 0-grade row for each known student who didn't submit this task
                for (student in knownStudents) {
                    if (student !in submittedStudents) {
                        val studentInfo = studentInfoByKey[student]
                        taskReports.add(TaskReport(
                            studentKey = student,
                            studentName = studentInfo?.first ?: "Sin nombre registrado",
                            studentFullName = studentInfo?.first,
                            studentCedula = studentInfo?.second,
                            title = task.name.ifBlank { "Sin título" },
                            grade = 0f,
                            submissionDate = null,
                            feedback = null,
                            gradedByUsername = null,
                            notSubmitted = true
                        ))
                    }
                }
            }

            // Compute calificacion_ponderada per student (avg over all tasks in subject, 0 for non-submissions)
            val taskCount = subjectTasks.size.coerceAtLeast(1)
            val gradesByStudent = mutableMapOf<String, MutableList<Float>>()
            for (tr in taskReports) {
                gradesByStudent.getOrPut(tr.studentKey) { mutableListOf() }.add(tr.grade ?: 0f)
            }
            val studentAverages = gradesByStudent.mapValues { (_, grades) ->
                grades.sum() / taskCount.toFloat()
            }

            val graded = taskReports.filter { !it.notSubmitted && it.grade != null }
            val avg = if (graded.isNotEmpty()) graded.map { it.grade!! }.average().toFloat() else null

            SubjectReport(
                subjectName = subject.name,
                teacherName = subject.createdBy?.let { teachers[it] ?: "Docente #$it" },
                tasks = taskReports,
                average = avg,
                studentAverages = studentAverages
            )
        }
    }

    // ── PDF ──────────────────────────────────────────────────────────────

    fun generatePDF(context: Context, report: List<SubjectReport>, isIncat: Boolean = false): File? {
        return try {
            val doc = PdfDocument()
            val pageWidth = 595  // A4
            val pageHeight = 842
            val margin = 40f
            val contentWidth = pageWidth - margin * 2

            var pageNum = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            var page = doc.startPage(pageInfo)
            var canvas = page.canvas
            var y = margin

            val titlePaint = Paint().apply { color = Color.parseColor("#1E1E1E"); textSize = 22f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
            val subtitlePaint = Paint().apply { color = Color.parseColor("#888888"); textSize = 11f; isAntiAlias = true }
            val subjectPaint = Paint().apply { color = Color.parseColor("#6A1B9A"); textSize = 14f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
            val taskPaint = Paint().apply { color = Color.parseColor("#333333"); textSize = 12f; isAntiAlias = true }
            val gradePaint = Paint().apply { textSize = 12f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true; textAlign = Paint.Align.RIGHT }
            val linePaint = Paint().apply { color = Color.parseColor("#EEEEEE"); strokeWidth = 1f }
            val bgPaint = Paint().apply { color = Color.parseColor("#F8F5FF") }

            fun newPage(): Canvas {
                doc.finishPage(page)
                pageNum++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                page = doc.startPage(pageInfo)
                y = margin
                return page.canvas
            }

            fun ensureSpace(needed: Float) {
                if (y + needed > pageHeight - margin) {
                    canvas = newPage()
                }
            }

            // INCAT Institution header
            if (isIncat) {
                y = drawIncatHeader(canvas, margin, contentWidth, y)
            }

            // Header
            canvas.drawText("Reporte de Notas", margin, y + 22f, titlePaint)
            y += 32f
            val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            canvas.drawText("Generado el $dateStr", margin, y + 11f, subtitlePaint)
            y += 24f

            // Summary bar
            val totalTasks = report.sumOf { it.tasks.size }
            val gradedCount = report.sumOf { it.tasks.count { t -> t.grade != null } }

            val summaryBg = RectF(margin, y, margin + contentWidth, y + 36f)
            canvas.drawRoundRect(summaryBg, 8f, 8f, bgPaint)
            val summaryPaint = Paint().apply { color = Color.parseColor("#6A1B9A"); textSize = 11f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val parts = listOf("${report.size} Materias", "$totalTasks Tareas", "$gradedCount Calificadas")
            val step = contentWidth / parts.size
            parts.forEachIndexed { i, text ->
                canvas.drawText(text, margin + step * i + step / 2 - summaryPaint.measureText(text) / 2, y + 22f, summaryPaint)
            }
            y += 50f

            // Table
            for (group in report) {
                ensureSpace(40f)
                // Subject header with teacher name
                val headerBg = RectF(margin, y, margin + contentWidth, y + 28f)
                canvas.drawRoundRect(headerBg, 6f, 6f, bgPaint)
                val headerText = group.subjectName + if (group.teacherName != null) "  —  Docente: ${group.teacherName}" else ""
                canvas.drawText(headerText, margin + 10f, y + 18f, subjectPaint)
                val avgText = if (group.average != null) String.format("%.1f", group.average) else "0.0"
                gradePaint.color = gradeColor(group.average)
                canvas.drawText(avgText, margin + contentWidth - 10f, y + 18f, gradePaint)
                y += 34f

                if (group.tasks.isEmpty()) {
                    canvas.drawText("Sin entregas", margin + 20f, y + 12f, subtitlePaint)
                    y += 20f
                }

                val colWidths = floatArrayOf(
                    contentWidth * 0.12f, // Materia
                    contentWidth * 0.12f, // Estudiante
                    contentWidth * 0.17f, // Tarea
                    contentWidth * 0.07f, // Nota
                    contentWidth * 0.09f, // Cal. Ponderada
                    contentWidth * 0.12f, // Fecha
                    contentWidth * 0.17f, // Retroalimentación
                    contentWidth * 0.14f  // Calificó
                )

                for (task in group.tasks) {
                    ensureSpace(22f)
                    var cx = margin + 10f
                    // Materia
                    canvas.drawText(group.subjectName.take(14), cx, y + 13f, taskPaint)
                    cx += colWidths[0]
                    // Estudiante
                    canvas.drawText((task.studentFullName?.takeIf { it.isNotBlank() } ?: task.studentName).take(16), cx, y + 13f, taskPaint)
                    cx += colWidths[1]
                    // Tarea
                    val tTitle = if (task.notSubmitted) "${task.title.take(18)} ✗" else task.title.take(20)
                    canvas.drawText(tTitle, cx, y + 13f, taskPaint)
                    cx += colWidths[2]
                    // Nota
                    val gText = if (task.grade != null) String.format("%.1f", task.grade) else "0.0"
                    gradePaint.color = if (task.notSubmitted) Color.parseColor("#FF453A") else gradeColor(task.grade)
                    gradePaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(gText, cx, y + 13f, gradePaint)
                    gradePaint.textAlign = Paint.Align.RIGHT
                    cx += colWidths[3]
                    // Cal. Ponderada
                    val ponderada = group.studentAverages[task.studentKey]
                    val ponderadaText = if (ponderada != null) String.format("%.1f", ponderada) else "0.0"
                    gradePaint.color = gradeColor(ponderada)
                    gradePaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(ponderadaText, cx, y + 13f, gradePaint)
                    gradePaint.textAlign = Paint.Align.RIGHT
                    cx += colWidths[4]
                    // Fecha
                    val dateStr = task.submissionDate?.let {
                        SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(java.util.Date(it))
                    } ?: "—"
                    canvas.drawText(dateStr, cx, y + 13f, subtitlePaint)
                    cx += colWidths[5]
                    // Retroalimentación (truncated)
                    val fb = task.feedback?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(35) ?: "—"
                    canvas.drawText(fb, cx, y + 13f, subtitlePaint)
                    cx += colWidths[6]
                    // Calificó
                    canvas.drawText(task.gradedByUsername?.take(16) ?: "—", cx, y + 13f, subtitlePaint)

                    canvas.drawLine(margin + 10f, y + 19f, margin + contentWidth, y + 19f, linePaint)
                    y += 22f
                }
                y += 10f
            }

            // INCAT signature and footer
            if (isIncat) {
                drawIncatSignatureAndFooter(canvas, margin, contentWidth, y, pageHeight.toFloat())
            }

            doc.finishPage(page)

            val file = File(context.cacheDir, "reporte_notas_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Excel (SpreadsheetML XML – abre sin advertencia de formato en Microsoft Excel) ─

    fun generateCSV(context: Context, report: List<SubjectReport>, courseName: String = "", isIncat: Boolean = false): File? {
        return try {
            val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            fun escXml(s: String?): String = (s ?: "")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;")

            fun cell(v: String?, style: String? = null) =
                "<Cell${if (style != null) " ss:StyleID=\"$style\"" else ""}>" +
                    "<Data ss:Type=\"String\">${escXml(v)}</Data></Cell>"

            fun brd(c: String = "#E0DDE8") =
                "<Borders>" +
                    "<Border ss:Position=\"Bottom\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"$c\"/>" +
                    "<Border ss:Position=\"Left\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"$c\"/>" +
                    "<Border ss:Position=\"Right\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"$c\"/>" +
                    "<Border ss:Position=\"Top\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"$c\"/>" +
                    "</Borders>"

            val styles = "<Styles>" +
                "<Style ss:ID=\"Default\"/>" +
                "<Style ss:ID=\"incatTitle\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#8B0000\" ss:Size=\"14\" ss:Name=\"Calibri\"/></Style>" +
                "<Style ss:ID=\"incatLine\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Color=\"#333333\" ss:Size=\"9\" ss:Name=\"Calibri\"/></Style>" +
                "<Style ss:ID=\"incatDesc\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#333333\" ss:Size=\"10\" ss:Name=\"Calibri\"/></Style>" +
                "<Style ss:ID=\"incatNit\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#8B0000\" ss:Size=\"11\" ss:Name=\"Calibri\"/></Style>" +
                "<Style ss:ID=\"hdr\"><Alignment ss:Horizontal=\"Left\"/><Font ss:Bold=\"1\" ss:Color=\"#FFFFFF\" ss:Size=\"10\" ss:Name=\"Calibri\"/><Interior ss:Color=\"#3B1060\" ss:Pattern=\"Solid\"/>${brd("#2A0A45")}</Style>" +
                "<Style ss:ID=\"hdrC\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#FFFFFF\" ss:Size=\"10\" ss:Name=\"Calibri\"/><Interior ss:Color=\"#3B1060\" ss:Pattern=\"Solid\"/>${brd("#2A0A45")}</Style>" +
                "<Style ss:ID=\"grpHdr\"><Font ss:Bold=\"1\" ss:Color=\"#4A0E8F\" ss:Size=\"11\" ss:Name=\"Calibri\"/><Interior ss:Color=\"#F3EAFE\" ss:Pattern=\"Solid\"/>${brd("#C9A0F5")}</Style>" +
                "<Style ss:ID=\"empty\"><Font ss:Italic=\"1\" ss:Color=\"#999999\" ss:Name=\"Calibri\"/></Style>" +
                "<Style ss:ID=\"r0\"><Font ss:Name=\"Calibri\"/><Interior ss:Color=\"#FFFFFF\" ss:Pattern=\"Solid\"/>${brd()}</Style>" +
                "<Style ss:ID=\"r1\"><Font ss:Name=\"Calibri\"/><Interior ss:Color=\"#F9F7FC\" ss:Pattern=\"Solid\"/>${brd()}</Style>" +
                "<Style ss:ID=\"gR\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#B91C1C\" ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "<Style ss:ID=\"gG\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#1A7F37\" ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "<Style ss:ID=\"gY\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#B45309\" ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "<Style ss:ID=\"gN\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#999999\" ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "<Style ss:ID=\"numC\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "</Styles>"

            val sb = StringBuilder()

            // INCAT institution header rows
            if (isIncat) {
                sb.append("<Row><Cell ss:StyleID=\"incatTitle\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[0]}</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatLine\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[1]}</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatLine\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[2]}</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatDesc\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[3]}</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatNit\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[4]}</Data></Cell></Row>")
                sb.append("<Row/>") // blank separator row
            }

            sb.append("<Row>")
            sb.append(cell("Curso", "hdr")).append(cell("Materia", "hdr")).append(cell("Docente", "hdr"))
            sb.append(cell("Estudiante", "hdr")).append(cell("Nombre Completo", "hdr")).append(cell("Cédula", "hdr"))
            sb.append(cell("Nota", "hdrC"))
            sb.append(cell("Cal. Ponderada", "hdrC")).append(cell("Fecha de entrega", "hdr"))
            sb.append(cell("Calificó", "hdr")).append(cell("Retroalimentación", "hdr"))
            sb.append("</Row>")

            for (group in report) {
                val avg = if (group.average != null) String.format("%.1f", group.average) else "0.0"
                val teacher = group.teacherName ?: "—"
                sb.append("<Row><Cell ss:StyleID=\"grpHdr\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">")
                sb.append("${escXml(group.subjectName)} — Docente: ${escXml(teacher)} — Promedio: $avg")
                sb.append("</Data></Cell></Row>")
                if (group.tasks.isEmpty()) {
                    sb.append("<Row><Cell ss:StyleID=\"empty\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">Sin entregas registradas</Data></Cell></Row>")
                }
                group.tasks.forEachIndexed { i, task ->
                    val rs = if (i % 2 == 0) "r0" else "r1"
                    val dateStr2 = task.submissionDate?.let { df.format(Date(it)) } ?: "—"
                    val fb = task.feedback?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: "—"
                    val grader = task.gradedByUsername ?: "—"
                    val gradeVal = if (task.notSubmitted) "0" else if (task.grade != null) String.format("%.1f", task.grade) else "0.0"
                    val gs = when {
                        task.notSubmitted   -> "gR"
                        task.grade == null -> "gN"
                        task.grade >= 4f   -> "gG"
                        task.grade >= 3f   -> "gY"
                        else               -> "gR"
                    }
                    val ponderadaVal = group.studentAverages[task.studentKey]
                    val ponderadaStr = if (ponderadaVal != null) String.format("%.1f", ponderadaVal) else "0.0"
                    val taskLabel = if (task.notSubmitted) "${task.title} (No entregado)" else task.title
                    sb.append("<Row>")
                    sb.append(cell(courseName.ifBlank { "—" }, rs))
                    sb.append(cell(group.subjectName, rs))
                    sb.append(cell(teacher, rs))
                    sb.append(cell(task.studentFullName?.takeIf { it.isNotBlank() } ?: task.studentName, rs))
                    sb.append(cell(task.studentFullName ?: "—", rs))
                    sb.append(cell(task.studentCedula ?: "—", rs))
                    sb.append(cell(gradeVal, gs))
                    sb.append(cell(ponderadaStr, "numC"))
                    sb.append(cell(dateStr2, rs))
                    sb.append(cell(grader, rs))
                    sb.append(cell(fb, rs))
                    sb.append("</Row>")
                }
            }

            // INCAT signature and footer rows
            if (isIncat) {
                sb.append("<Row/>")
                sb.append("<Row><Cell ss:StyleID=\"incatDesc\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">$INCAT_SIGNATURE_NAME — $INCAT_SIGNATURE_TITLE</Data></Cell></Row>")
                sb.append("<Row/>")
                sb.append("<Row><Cell ss:StyleID=\"incatTitle\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">$INCAT_FOOTER_SLOGAN</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatDesc\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">$INCAT_FOOTER_ADDRESS</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatNit\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">$INCAT_FOOTER_EMAIL</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatDesc\" ss:MergeAcross=\"10\"><Data ss:Type=\"String\">$INCAT_FOOTER_CITY</Data></Cell></Row>")
            }

            val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" " +
                "xmlns:o=\"urn:schemas-microsoft-com:office:office\" " +
                "xmlns:x=\"urn:schemas-microsoft-com:office:excel\" " +
                "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">" +
                "$styles" +
                "<Worksheet ss:Name=\"Reporte de Notas\"><Table>" +
                "<Column ss:Width=\"160\"/><Column ss:Width=\"140\"/><Column ss:Width=\"110\"/><Column ss:Width=\"140\"/>" +
                "<Column ss:Width=\"160\"/><Column ss:Width=\"90\"/>" +
                "<Column ss:Width=\"55\"/><Column ss:Width=\"65\"/><Column ss:Width=\"110\"/>" +
                "<Column ss:Width=\"130\"/><Column ss:Width=\"220\"/>" +
                "$sb" +
                "</Table></Worksheet></Workbook>"

            val file = File(context.cacheDir, "reporte_notas_${System.currentTimeMillis()}.xml")
            file.writeText(xml, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }


    // ── Share text ──────────────────────────────────────────────────────

    fun buildShareText(report: List<SubjectReport>): String {
        val sb = StringBuilder("📊 REPORTE DE NOTAS\n\n")
        val df = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        for (group in report) {
            val avgText = if (group.average != null) String.format("%.1f", group.average) else "0.0"
            val teacher = group.teacherName?.let { " — Docente: $it" } ?: ""
            sb.appendLine("📘 ${group.subjectName}$teacher (Promedio: $avgText)")
            for (task in group.tasks) {
                val gradeStr = when {
                    task.notSubmitted -> "0 (No entregado)"
                    task.grade != null -> String.format("%.1f", task.grade)
                    else -> "Sin nota"
                }
                val ponderada = group.studentAverages[task.studentKey]
                val ponderadaStr = if (ponderada != null) String.format("%.1f", ponderada) else "0.0"
                val dateStr = task.submissionDate?.let { " [${df.format(java.util.Date(it))}]" } ?: ""
                val graderStr = task.gradedByUsername?.let { " (Calificó: $it)" } ?: ""
                sb.appendLine("   • [${task.studentFullName?.takeIf { it.isNotBlank() } ?: task.studentName}] ${task.title}: $gradeStr [Cal. Ponderada: $ponderadaStr]$dateStr$graderStr")
            }
            sb.appendLine()
        }
        sb.appendLine("📋 Total entregas: ${report.sumOf { it.tasks.size }} | Calificadas: ${report.sumOf { it.tasks.count { t -> t.grade != null } }}")
        return sb.toString()
    }

    // ── Share file ──────────────────────────────────────────────────────

    fun shareFile(context: Context, file: File, mimeType: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Reporte de Notas")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartir reporte"))
        } catch (e: Exception) {
            Toast.makeText(context, "Error al compartir", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, "Reporte de Notas")
        }
        context.startActivity(Intent.createChooser(intent, "Compartir reporte"))
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun esc(s: String) = s.replace("\"", "\"\"")

    private fun gradeColor(grade: Float?): Int {
        if (grade == null) return Color.parseColor("#999999")
        return when {
            grade >= 4f -> Color.parseColor("#34C759")
            grade >= 3f -> Color.parseColor("#FF9500")
            else -> Color.parseColor("#FF453A")
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // Platform-wide report (all courses, all submissions, role 3 only)
    // ════════════════════════════════════════════════════════════════════

    data class PlatformGradeRow(
        val courseName: String,
        val studentUsername: String?,
        val studentFullName: String? = null,
        val studentCedula: String? = null,
        val taskName: String?,
        val grade: Float?,
        val submissionDate: Long,
        val feedback: String?,
        val subjectName: String? = null,
        val gradedByUsername: String? = null
    )

    fun buildPlatformReport(
        courses: List<Course>,
        submissionsByCourse: Map<Long, List<TaskSubmission>>
    ): List<PlatformGradeRow> {
        val rows = mutableListOf<PlatformGradeRow>()
        for (course in courses) {
            val subs = submissionsByCourse[course.id] ?: continue
            for (sub in subs) {
                rows.add(
                    PlatformGradeRow(
                        courseName = course.title.ifBlank { "Curso ${course.id}" },
                        studentUsername = sub.studentUsername,
                        studentFullName = sub.studentFullName,
                        studentCedula = sub.studentCedula,
                        taskName = sub.taskName,
                        grade = sub.grade,
                        submissionDate = sub.submissionDate,
                        feedback = sub.feedback,
                        subjectName = sub.subjectName,
                        gradedByUsername = sub.gradedByUsername
                    )
                )
            }
        }
        return rows
    }

    // ── Platform PDF ──────────────────────────────────────────────────────

    fun generatePlatformPDF(context: Context, rows: List<PlatformGradeRow>, isIncat: Boolean = false): File? {        return try {
            val doc = PdfDocument()
            val pageWidth = 595
            val pageHeight = 842
            val margin = 36f
            val contentWidth = pageWidth - margin * 2

            var pageNum = 1
            var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
            var page = doc.startPage(pageInfo)
            var canvas = page.canvas
            var y = margin

            val titlePaint = Paint().apply { color = Color.parseColor("#1E1E1E"); textSize = 20f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
            val subtitlePaint = Paint().apply { color = Color.parseColor("#888888"); textSize = 11f; isAntiAlias = true }
            val coursePaint = Paint().apply { color = Color.parseColor("#6A1B9A"); textSize = 13f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true }
            val cellPaint = Paint().apply { color = Color.parseColor("#333333"); textSize = 11f; isAntiAlias = true }
            val gradePaint = Paint().apply { textSize = 11f; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD); isAntiAlias = true; textAlign = Paint.Align.CENTER }
            val headerPaint = Paint().apply { color = Color.parseColor("#666666"); textSize = 10f; isAntiAlias = true }
            val linePaint = Paint().apply { color = Color.parseColor("#EEEEEE"); strokeWidth = 1f }
            val bgPaint = Paint().apply { color = Color.parseColor("#F8F5FF") }

            fun newPage(): android.graphics.Canvas {
                doc.finishPage(page)
                pageNum++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNum).create()
                page = doc.startPage(pageInfo)
                y = margin
                return page.canvas
            }

            fun ensureSpace(needed: Float) {
                if (y + needed > pageHeight - margin) { canvas = newPage() }
            }

            // INCAT Institution header
            if (isIncat) {
                y = drawIncatHeader(canvas, margin, contentWidth, y)
            }

            // Title
            canvas.drawText("Reporte de Notas — Plataforma", margin, y + 20f, titlePaint)
            y += 30f
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            canvas.drawText("Generado el $dateStr", margin, y + 11f, subtitlePaint)
            y += 22f

            // Summary
            val courseCount = rows.map { it.courseName }.toSet().size
            val summaryBg = RectF(margin, y, margin + contentWidth, y + 34f)
            canvas.drawRoundRect(summaryBg, 8f, 8f, bgPaint)
            val parts = listOf("$courseCount Cursos")
            val step = contentWidth / parts.size
            val sp = Paint().apply { color = Color.parseColor("#6A1B9A"); textSize = 10f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            parts.forEachIndexed { i, text ->
                canvas.drawText(text, margin + step * i + step / 2 - sp.measureText(text) / 2, y + 21f, sp)
            }
            y += 44f

            // Column headers
            val col = floatArrayOf(margin, margin + 60f, margin + 120f, margin + 180f, margin + 230f, margin + 260f, margin + 315f, margin + 425f)
            val headers = listOf("Curso", "Estudiante", "Materia", "Tarea", "Nota", "Fecha", "Retroalim.", "Calificó")
            headers.forEachIndexed { i, h -> canvas.drawText(h, col[i], y + 11f, headerPaint) }
            canvas.drawLine(margin, y + 15f, margin + contentWidth, y + 15f, linePaint)
            y += 20f

            // Rows grouped by course
            var currentCourse = ""
            for (row in rows) {
                if (row.courseName != currentCourse) {
                    ensureSpace(22f)
                    currentCourse = row.courseName
                    val cb = RectF(margin, y, margin + contentWidth, y + 20f)
                    canvas.drawRoundRect(cb, 4f, 4f, bgPaint)
                    canvas.drawText(currentCourse, margin + 6f, y + 13f, coursePaint)
                    y += 24f
                }
                ensureSpace(18f)
                canvas.drawText(row.courseName.take(12), col[0], y + 11f, cellPaint)
                canvas.drawText(displayStudentName(row.studentFullName).take(16), col[1], y + 11f, cellPaint)
                canvas.drawText((row.subjectName ?: "—").take(16), col[2], y + 11f, cellPaint)
                canvas.drawText((row.taskName ?: "—").take(16), col[3], y + 11f, cellPaint)
                val gText = if (row.grade != null) String.format("%.1f", row.grade) else "—"
                gradePaint.color = gradeColor(row.grade)
                canvas.drawText(gText, col[4] + 12f, y + 11f, gradePaint)
                val dateFmt = if (row.submissionDate > 0)
                    SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(row.submissionDate)) else "—"
                canvas.drawText(dateFmt, col[5], y + 11f, cellPaint)
                canvas.drawText((row.feedback ?: "—").take(22), col[6], y + 11f, cellPaint)
                canvas.drawText((row.gradedByUsername ?: "—").take(12), col[7], y + 11f, cellPaint)
                canvas.drawLine(margin, y + 15f, margin + contentWidth, y + 15f, linePaint)
                y += 18f
            }

            // INCAT signature and footer
            if (isIncat) {
                drawIncatSignatureAndFooter(canvas, margin, contentWidth, y, pageHeight.toFloat())
            }

            doc.finishPage(page)
            val file = File(context.cacheDir, "reporte_plataforma_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Platform Excel (.xls as HTML) ────────────────────────────────────

    fun generatePlatformCSV(context: Context, rows: List<PlatformGradeRow>, isIncat: Boolean = false): File? {
        return try {
            val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            fun escXml(s: String?): String = (s ?: "")
                .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;")

            fun cell(v: String?, style: String? = null) =
                "<Cell${if (style != null) " ss:StyleID=\"$style\"" else ""}>" +
                    "<Data ss:Type=\"String\">${escXml(v)}</Data></Cell>"

            fun brd(c: String = "#E0DDE8") =
                "<Borders>" +
                    "<Border ss:Position=\"Bottom\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"$c\"/>" +
                    "<Border ss:Position=\"Left\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"$c\"/>" +
                    "<Border ss:Position=\"Right\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"$c\"/>" +
                    "<Border ss:Position=\"Top\" ss:LineStyle=\"Continuous\" ss:Weight=\"1\" ss:Color=\"$c\"/>" +
                    "</Borders>"

            val styles = "<Styles>" +
                "<Style ss:ID=\"Default\"/>" +
                "<Style ss:ID=\"incatTitle\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#8B0000\" ss:Size=\"14\" ss:Name=\"Calibri\"/></Style>" +
                "<Style ss:ID=\"incatLine\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Color=\"#333333\" ss:Size=\"9\" ss:Name=\"Calibri\"/></Style>" +
                "<Style ss:ID=\"incatDesc\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#333333\" ss:Size=\"10\" ss:Name=\"Calibri\"/></Style>" +
                "<Style ss:ID=\"incatNit\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#8B0000\" ss:Size=\"11\" ss:Name=\"Calibri\"/></Style>" +
                "<Style ss:ID=\"hdr\"><Alignment ss:Horizontal=\"Left\"/><Font ss:Bold=\"1\" ss:Color=\"#FFFFFF\" ss:Size=\"10\" ss:Name=\"Calibri\"/><Interior ss:Color=\"#3B1060\" ss:Pattern=\"Solid\"/>${brd("#2A0A45")}</Style>" +
                "<Style ss:ID=\"hdrC\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#FFFFFF\" ss:Size=\"10\" ss:Name=\"Calibri\"/><Interior ss:Color=\"#3B1060\" ss:Pattern=\"Solid\"/>${brd("#2A0A45")}</Style>" +
                "<Style ss:ID=\"courseHdr\"><Font ss:Bold=\"1\" ss:Color=\"#4A0E8F\" ss:Size=\"11\" ss:Name=\"Calibri\"/><Interior ss:Color=\"#F3EAFE\" ss:Pattern=\"Solid\"/>${brd("#C9A0F5")}</Style>" +
                "<Style ss:ID=\"r0\"><Font ss:Name=\"Calibri\"/><Interior ss:Color=\"#FFFFFF\" ss:Pattern=\"Solid\"/>${brd()}</Style>" +
                "<Style ss:ID=\"r1\"><Font ss:Name=\"Calibri\"/><Interior ss:Color=\"#F9F7FC\" ss:Pattern=\"Solid\"/>${brd()}</Style>" +
                "<Style ss:ID=\"gR\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#B91C1C\" ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "<Style ss:ID=\"gG\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#1A7F37\" ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "<Style ss:ID=\"gY\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#B45309\" ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "<Style ss:ID=\"gN\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Bold=\"1\" ss:Color=\"#999999\" ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "<Style ss:ID=\"numC\"><Alignment ss:Horizontal=\"Center\"/><Font ss:Name=\"Calibri\"/>${brd()}</Style>" +
                "</Styles>"

            // Compute ponderada per (studentUsername, subjectName)
            val ponderadaMap = mutableMapOf<Pair<String?, String?>, Pair<Float, Int>>()
            for (row in rows) {
                val key = Pair(row.studentUsername, row.subjectName)
                val (sum, count) = ponderadaMap.getOrDefault(key, Pair(0f, 0))
                ponderadaMap[key] = Pair(sum + (row.grade ?: 0f), count + 1)
            }
            val studentSubjectPonderada = ponderadaMap.mapValues { (_, v) -> v.first / v.second.toFloat() }

            val sb = StringBuilder()

            // INCAT institution header rows
            if (isIncat) {
                sb.append("<Row><Cell ss:StyleID=\"incatTitle\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[0]}</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatLine\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[1]}</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatLine\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[2]}</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatDesc\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[3]}</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatNit\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">${INCAT_HEADER_LINES[4]}</Data></Cell></Row>")
                sb.append("<Row/>") // blank separator row
            }

            sb.append("<Row>")
            sb.append(cell("Curso", "hdr")).append(cell("Estudiante", "hdr"))
            sb.append(cell("Cédula", "hdr"))
            sb.append(cell("Materia", "hdr"))
            sb.append(cell("Tarea", "hdr")).append(cell("Nota", "hdrC"))
            sb.append(cell("Cal. Ponderada", "hdrC")).append(cell("Fecha de entrega", "hdr"))
            sb.append(cell("Docente que calificó", "hdr")).append(cell("Retroalimentación", "hdr"))
            sb.append("</Row>")

            var currentCourse = ""
            var rowIndex = 0
            for (row in rows) {
                if (row.courseName != currentCourse) {
                    currentCourse = row.courseName
                    rowIndex = 0
                    sb.append("<Row><Cell ss:StyleID=\"courseHdr\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">${escXml(currentCourse)}</Data></Cell></Row>")
                }
                val rs = if (rowIndex % 2 == 0) "r0" else "r1"
                val date = if (row.submissionDate > 0) df.format(Date(row.submissionDate)) else "—"
                val grade = if (row.grade != null) String.format("%.1f", row.grade) else "0"
                val gs = when {
                    row.grade == null -> "gR"
                    row.grade >= 4f   -> "gG"
                    row.grade >= 3f   -> "gY"
                    else              -> "gR"
                }
                val ponderada = studentSubjectPonderada[Pair(row.studentUsername, row.subjectName)]
                val ponderadaStr = if (ponderada != null) String.format("%.1f", ponderada) else "0.0"
                val ps = when {
                    ponderada == null  -> "gN"
                    ponderada >= 4f   -> "gG"
                    ponderada >= 3f   -> "gY"
                    else              -> "gR"
                }
                val fb = row.feedback?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: "—"
                sb.append("<Row>")
                sb.append(cell(row.courseName, rs))
                sb.append(cell(displayStudentName(row.studentFullName), rs))
                sb.append(cell(row.studentCedula ?: "—", rs))
                sb.append(cell(row.subjectName ?: "—", rs))
                sb.append(cell(row.taskName ?: "—", rs))
                sb.append(cell(grade, gs))
                sb.append(cell(ponderadaStr, ps))
                sb.append(cell(date, rs))
                sb.append(cell(row.gradedByUsername ?: "—", rs))
                sb.append(cell(fb, rs))
                sb.append("</Row>")
                rowIndex++
            }

            // INCAT signature and footer rows
            if (isIncat) {
                sb.append("<Row/>")
                sb.append("<Row><Cell ss:StyleID=\"incatDesc\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">$INCAT_SIGNATURE_NAME — $INCAT_SIGNATURE_TITLE</Data></Cell></Row>")
                sb.append("<Row/>")
                sb.append("<Row><Cell ss:StyleID=\"incatTitle\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">$INCAT_FOOTER_SLOGAN</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatDesc\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">$INCAT_FOOTER_ADDRESS</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatNit\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">$INCAT_FOOTER_EMAIL</Data></Cell></Row>")
                sb.append("<Row><Cell ss:StyleID=\"incatDesc\" ss:MergeAcross=\"9\"><Data ss:Type=\"String\">$INCAT_FOOTER_CITY</Data></Cell></Row>")
            }

            val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\" " +
                "xmlns:o=\"urn:schemas-microsoft-com:office:office\" " +
                "xmlns:x=\"urn:schemas-microsoft-com:office:excel\" " +
                "xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">" +
                "$styles" +
                "<Worksheet ss:Name=\"Reporte Plataforma\"><Table>" +
                "<Column ss:Width=\"160\"/><Column ss:Width=\"160\"/><Column ss:Width=\"90\"/>" +
                "<Column ss:Width=\"130\"/><Column ss:Width=\"180\"/>" +
                "<Column ss:Width=\"55\"/><Column ss:Width=\"65\"/><Column ss:Width=\"120\"/>" +
                "<Column ss:Width=\"130\"/><Column ss:Width=\"220\"/>" +
                "$sb" +
                "</Table></Worksheet></Workbook>"

            val file = File(context.cacheDir, "reporte_plataforma_${System.currentTimeMillis()}.xml")
            file.writeText(xml, Charsets.UTF_8)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Platform Word (.doc as HTML) ────────────────────────────────────

    fun generatePlatformWord(context: Context, rows: List<PlatformGradeRow>, isIncat: Boolean = false): File? {
        return try {
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            val courseCount = rows.map { it.courseName }.toSet().size

            val incatHeader = if (isIncat) buildIncatHtmlHeader() else ""

            val sb = StringBuilder()
            sb.append("""
<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:w="urn:schemas-microsoft-com:office:word">
<head><meta charset="utf-8"/><title>Reporte de Notas — Plataforma</title>
<style>
body{font-family:Calibri,Arial,sans-serif;margin:40px;color:#222;font-size:12pt}
h1{font-size:16pt;color:#1A1A1A}p.sub{font-size:10pt;color:#666;margin-bottom:16px}
.summary{margin-bottom:16px;padding:10px;background:#f8f5ff;border:1px solid #d9baf5}
table{width:100%;border-collapse:collapse}
th{background:#fafafa;border:1px solid #ccc;padding:6px 8px;font-size:10pt;text-align:left}
td{border:1px solid #e0e0e0;padding:5px 8px;font-size:10pt;vertical-align:top}
.ch{background:#f2e8ff;color:#6A1B9A;font-weight:bold}
</style></head><body>
$incatHeader
<h1>Reporte de Notas — Plataforma</h1>
<p class="sub">Generado el $dateStr</p>
<div class="summary">Cursos: $courseCount</div>
<table><thead><tr><th>Estudiante</th><th>Materia</th><th>Tarea</th><th>Nota</th><th>Cal. Ponderada</th><th>Fecha de entrega</th><th>Retroalimentación</th><th>Calificó</th></tr></thead><tbody>""".trimIndent())

            // Compute ponderada per (studentUsername, subjectName)
            val wordPonderadaMap = mutableMapOf<Pair<String?, String?>, Pair<Float, Int>>()
            for (row in rows) {
                val key = Pair(row.studentUsername, row.subjectName)
                val (sum, count) = wordPonderadaMap.getOrDefault(key, Pair(0f, 0))
                wordPonderadaMap[key] = Pair(sum + (row.grade ?: 0f), count + 1)
            }
            val wordStudentPonderada = wordPonderadaMap.mapValues { (_, v) -> v.first / v.second.toFloat() }

            var currentCourse = ""
            for (row in rows) {
                if (row.courseName != currentCourse) {
                    currentCourse = row.courseName
                    sb.append("<tr class=\"ch\"><td colspan=\"8\">${escHtml(currentCourse)}</td></tr>")
                }
                val date = if (row.submissionDate > 0)
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(row.submissionDate)) else "—"
                val grade = if (row.grade != null) String.format("%.1f", row.grade) else "0"
                val ponderada = wordStudentPonderada[Pair(row.studentUsername, row.subjectName)]
                val ponderadaStr = if (ponderada != null) String.format("%.1f", ponderada) else "0.0"
                sb.append("<tr>")
                sb.append("<td>${escHtml(displayStudentName(row.studentFullName))}</td>")
                sb.append("<td>${escHtml(row.subjectName ?: "—")}</td>")
                sb.append("<td>${escHtml(row.taskName ?: "—")}</td>")
                sb.append("<td style=\"text-align:center\">$grade</td>")
                sb.append("<td style=\"text-align:center\">$ponderadaStr</td>")
                sb.append("<td>$date</td>")
                sb.append("<td>${escHtml(row.feedback ?: "—")}</td>")
                sb.append("<td>${escHtml(row.gradedByUsername ?: "—")}</td>")
                sb.append("</tr>")
            }
            sb.append("</tbody></table>")
            if (isIncat) sb.append(buildIncatHtmlFooter())
            sb.append("</body></html>")

            val file = File(context.cacheDir, "reporte_plataforma_${System.currentTimeMillis()}.doc")
            file.writeText(sb.toString(), Charsets.UTF_8)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Platform share text ──────────────────────────────────────────────

    fun buildPlatformShareText(rows: List<PlatformGradeRow>): String {
        val sb = StringBuilder("📊 REPORTE DE NOTAS — PLATAFORMA\n\n")
        // Compute ponderada per (studentUsername, subjectName)
        val ponderadaMap = mutableMapOf<Pair<String?, String?>, Pair<Float, Int>>()
        for (row in rows) {
            val key = Pair(row.studentUsername, row.subjectName)
            val (sum, count) = ponderadaMap.getOrDefault(key, Pair(0f, 0))
            ponderadaMap[key] = Pair(sum + (row.grade ?: 0f), count + 1)
        }
        val studentSubjectPonderada = ponderadaMap.mapValues { (_, v) -> v.first / v.second.toFloat() }

        var currentCourse = ""
        for (row in rows) {
            if (row.courseName != currentCourse) {
                currentCourse = row.courseName
                sb.appendLine("📘 $currentCourse")
            }
            val g = if (row.grade != null) String.format("%.1f", row.grade) else "0"
            val ponderada = studentSubjectPonderada[Pair(row.studentUsername, row.subjectName)]
            val ponderadaStr = if (ponderada != null) String.format("%.1f", ponderada) else "0.0"
            val subj = row.subjectName?.let { " [$it]" } ?: ""
            val grader = row.gradedByUsername?.let { " (Calificó: $it)" } ?: ""
            sb.appendLine("   • ${displayStudentName(row.studentFullName)} — ${row.taskName ?: "—"}: $g [Cal. Ponderada: $ponderadaStr]$subj$grader")
        }
        return sb.toString()
    }

    private fun escHtml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun normalizePersonName(name: String?): String? = name?.trim()?.takeIf { it.isNotBlank() }

    private fun displayStudentName(name: String?): String = normalizePersonName(name) ?: "Sin nombre registrado"

    private fun resolveStudentKey(submission: TaskSubmission): String {
        submission.studentUsername?.trim()?.takeIf { it.isNotBlank() }?.let { return "username:$it" }
        if (submission.studentId > 0) return "id:${submission.studentId}"
        normalizePersonName(submission.studentFullName)?.let { return "name:${it.lowercase(Locale.ROOT)}" }
        return "unknown:${submission.taskId}:${submission.submissionDate}"
    }
}

