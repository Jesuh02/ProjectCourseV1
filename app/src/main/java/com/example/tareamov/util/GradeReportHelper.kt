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
import java.text.SimpleDateFormat
import java.util.*

/**
 * Genera reportes de notas (PDF / CSV) y permite compartirlos.
 */
object GradeReportHelper {

    data class SubjectReport(
        val subjectName: String,
        val teacherName: String?,
        val tasks: List<TaskReport>,
        val average: Float?,
        val studentAverages: Map<String, Float> = emptyMap()
    )

    data class TaskReport(
        val studentName: String,
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

        // Collect all known students across all submissions
        val knownStudents = submissions
            .mapNotNull { it.studentUsername?.takeIf { s -> s.isNotBlank() } }
            .toSet()

        return subjects.map { subject ->
            val topicIds = topics.filter { it.subjectId == subject.id || it.courseId == subject.id }
                .map { it.id }.toSet()

            val subjectTasks = allTasks.filter { topicIds.contains(it.topicId) }

            val taskReports = mutableListOf<TaskReport>()
            for (task in subjectTasks) {
                val taskSubs = submissionsByTask[task.id] ?: emptyList()
                val submittedStudents = taskSubs
                    .mapNotNull { it.studentUsername?.takeIf { s -> s.isNotBlank() } }
                    .toSet()
                for (sub in taskSubs) {
                    taskReports.add(TaskReport(
                        studentName = sub.studentUsername?.takeIf { it.isNotBlank() } ?: "Estudiante #${sub.studentId}",
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
                        taskReports.add(TaskReport(
                            studentName = student,
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
                gradesByStudent.getOrPut(tr.studentName) { mutableListOf() }.add(tr.grade ?: 0f)
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

    fun generatePDF(context: Context, report: List<SubjectReport>): File? {
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

            // Header
            canvas.drawText("Reporte de Notas", margin, y + 22f, titlePaint)
            y += 32f
            val dateStr = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            canvas.drawText("Generado el $dateStr", margin, y + 11f, subtitlePaint)
            y += 24f

            // Summary bar
            val totalTasks = report.sumOf { it.tasks.size }
            val gradedCount = report.sumOf { it.tasks.count { t -> t.grade != null } }
            val allGraded = report.flatMap { it.tasks }.mapNotNull { it.grade }
            val globalAvg = if (allGraded.isNotEmpty()) String.format("%.1f", allGraded.average()) else "—"

            val summaryBg = RectF(margin, y, margin + contentWidth, y + 36f)
            canvas.drawRoundRect(summaryBg, 8f, 8f, bgPaint)
            val summaryPaint = Paint().apply { color = Color.parseColor("#6A1B9A"); textSize = 11f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            val parts = listOf("${report.size} Materias", "$totalTasks Tareas", "$gradedCount Calificadas", "Prom: $globalAvg")
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
                val avgText = if (group.average != null) String.format("%.1f", group.average) else "—"
                gradePaint.color = gradeColor(group.average)
                canvas.drawText(avgText, margin + contentWidth - 10f, y + 18f, gradePaint)
                y += 34f

                if (group.tasks.isEmpty()) {
                    canvas.drawText("Sin entregas", margin + 20f, y + 12f, subtitlePaint)
                    y += 20f
                }

                val colWidths = floatArrayOf(
                    contentWidth * 0.14f, // Estudiante
                    contentWidth * 0.20f, // Tarea
                    contentWidth * 0.08f, // Nota
                    contentWidth * 0.10f, // Cal. Ponderada
                    contentWidth * 0.14f, // Fecha
                    contentWidth * 0.18f, // Retroalimentación
                    contentWidth * 0.16f  // Calificó
                )

                for (task in group.tasks) {
                    ensureSpace(22f)
                    var cx = margin + 10f
                    // Estudiante
                    canvas.drawText(task.studentName.take(16), cx, y + 13f, taskPaint)
                    cx += colWidths[0]
                    // Tarea
                    val tTitle = if (task.notSubmitted) "${task.title.take(20)} ✗" else task.title.take(22)
                    canvas.drawText(tTitle, cx, y + 13f, taskPaint)
                    cx += colWidths[1]
                    // Nota
                    val gText = if (task.grade != null) String.format("%.1f", task.grade) else "—"
                    gradePaint.color = if (task.notSubmitted) Color.parseColor("#FF453A") else gradeColor(task.grade)
                    gradePaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(gText, cx, y + 13f, gradePaint)
                    gradePaint.textAlign = Paint.Align.RIGHT
                    cx += colWidths[2]
                    // Cal. Ponderada
                    val ponderada = group.studentAverages[task.studentName]
                    val ponderadaText = if (ponderada != null) String.format("%.1f", ponderada) else "—"
                    gradePaint.color = gradeColor(ponderada)
                    gradePaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(ponderadaText, cx, y + 13f, gradePaint)
                    gradePaint.textAlign = Paint.Align.RIGHT
                    cx += colWidths[3]
                    // Fecha
                    val dateStr = task.submissionDate?.let {
                        SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(java.util.Date(it))
                    } ?: "—"
                    canvas.drawText(dateStr, cx, y + 13f, subtitlePaint)
                    cx += colWidths[4]
                    // Retroalimentación (truncated)
                    val fb = task.feedback?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(40) ?: "—"
                    canvas.drawText(fb, cx, y + 13f, subtitlePaint)
                    cx += colWidths[5]
                    // Calificó
                    canvas.drawText(task.gradedByUsername?.take(18) ?: "—", cx, y + 13f, subtitlePaint)

                    canvas.drawLine(margin + 10f, y + 19f, margin + contentWidth, y + 19f, linePaint)
                    y += 22f
                }
                y += 10f
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

    // ── Excel (.xls as HTML – opens fully formatted in Microsoft Excel) ─

    fun generateCSV(context: Context, report: List<SubjectReport>): File? {
        return try {
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            val totalSubs = report.sumOf { it.tasks.size }
            val gradedCount = report.sumOf { it.tasks.count { t -> t.grade != null } }
            val avgAll = report.flatMap { it.tasks }.mapNotNull { it.grade }
                .let { if (it.isEmpty()) "—" else String.format("%.1f", it.average()) }
            val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            val sb = StringBuilder()
            sb.append("<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:x=\"urn:schemas-microsoft-com:office:excel\" xmlns=\"http://www.w3.org/TR/REC-html40\">")
            sb.append("<head><meta charset=\"utf-8\"><!--[if gte mso 9]><xml><x:ExcelWorkbook><x:ExcelWorksheets><x:ExcelWorksheet>")
            sb.append("<x:Name>Reporte de Notas</x:Name><x:WorksheetOptions><x:FitToPage/></x:WorksheetOptions>")
            sb.append("</x:ExcelWorksheet></x:ExcelWorksheets></x:ExcelWorkbook></xml><![endif]-->")
            sb.append("<style>* { font-family: Calibri, Arial, sans-serif; font-size: 10pt; } table { border-collapse: collapse; width: 100%; } th { background: #3b1060; color: #fff; font-weight: 700; padding: 9px 12px; text-align: left; border: 1px solid #2a0a45; white-space: nowrap; } td { padding: 6px 12px; border: 1px solid #e0dde8; vertical-align: top; }</style></head>")
            sb.append("<body>")
            sb.append("<h2 style=\"font-family:Calibri,Arial;color:#3b1060;margin:0 0 6px\">Reporte de Notas</h2>")
            sb.append("<p style=\"font-size:9pt;color:#666;margin:0 0 16px\">Generado el $dateStr &nbsp;&middot;&nbsp; ${report.size} materias &nbsp;&middot;&nbsp; $totalSubs entregas &nbsp;&middot;&nbsp; $gradedCount calificadas &nbsp;&middot;&nbsp; Promedio: $avgAll</p>")
            sb.append("<table><colgroup>")
            sb.append("<col style=\"width:140pt\"><col style=\"width:110pt\"><col style=\"width:190pt\"><col style=\"width:55pt\"><col style=\"width:65pt\"><col style=\"width:110pt\"><col style=\"width:130pt\"><col style=\"width:260pt\">")
            sb.append("</colgroup><thead><tr>")
            sb.append("<th>Materia</th><th>Docente</th><th>Estudiante</th><th style=\"text-align:center\">Nota</th><th style=\"text-align:center\">Cal. Ponderada</th><th>Fecha de entrega</th><th>Calificó</th><th>Retroalimentación</th>")
            sb.append("</tr></thead><tbody>")

            for (group in report) {
                val avg = if (group.average != null) String.format("%.1f", group.average) else "—"
                val teacher = group.teacherName ?: "—"
                sb.append("<tr style=\"background:#f3eafe\"><td colspan=\"8\" style=\"font-size:11pt;font-weight:700;color:#4a0e8f;padding:8px 12px;border-bottom:2px solid #c9a0f5;border-left:4px solid #8b5cf6\">")
                sb.append("${escHtml(group.subjectName)}&nbsp;&nbsp;<span style=\"font-weight:400;color:#888;font-size:9pt\">Docente: ${escHtml(teacher)}</span>")
                sb.append("&nbsp;&nbsp;<span style=\"float:right;color:#4a0e8f\">Promedio: $avg</span></td></tr>")
                if (group.tasks.isEmpty()) {
                    sb.append("<tr><td colspan=\"8\" style=\"color:#999;font-style:italic;padding:6px 12px 6px 24px\">Sin entregas registradas</td></tr>")
                }
                group.tasks.forEachIndexed { i, task ->
                    val bg = if (i % 2 == 0) "#ffffff" else "#f9f7fc"
                    val dateStr2 = task.submissionDate?.let { df.format(Date(it)) } ?: "—"
                    val fb = task.feedback?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: "—"
                    val grader = task.gradedByUsername ?: "—"
                    val gradeVal = if (task.grade != null) String.format("%.1f", task.grade) else "—"
                    val gradeColor = when {
                        task.notSubmitted   -> "#b91c1c"
                        task.grade == null -> "#999999"
                        task.grade >= 4f   -> "#1a7f37"
                        task.grade >= 3f   -> "#b45309"
                        else               -> "#b91c1c"
                    }
                    val ponderadaVal = group.studentAverages[task.studentName]
                    val ponderadaStr = if (ponderadaVal != null) String.format("%.1f", ponderadaVal) else "—"
                    val ponderadaColor = when {
                        ponderadaVal == null  -> "#999999"
                        ponderadaVal >= 4f   -> "#1a7f37"
                        ponderadaVal >= 3f   -> "#b45309"
                        else                 -> "#b91c1c"
                    }
                    sb.append("<tr style=\"background:$bg\">")
                    sb.append("<td style=\"font-size:9pt;color:#777\">${escHtml(group.subjectName)}</td>")
                    sb.append("<td style=\"font-size:9pt;color:#777\">${escHtml(teacher)}</td>")
                    sb.append("<td style=\"white-space:nowrap\">${escHtml(task.studentName)}</td>")
                    sb.append("<td style=\"text-align:center;font-weight:700;color:$gradeColor\">$gradeVal${if (task.notSubmitted) " <span style='font-size:8pt;color:#b91c1c'>(N/E)</span>" else ""}</td>")
                    sb.append("<td style=\"text-align:center;font-weight:700;color:$ponderadaColor\">$ponderadaStr</td>")
                    sb.append("<td style=\"white-space:nowrap\">$dateStr2</td>")
                    sb.append("<td>${escHtml(grader)}</td>")
                    sb.append("<td style=\"color:#555\">${escHtml(fb)}</td>")
                    sb.append("</tr>")
                }
            }
            sb.append("</tbody></table></body></html>")

            val file = File(context.cacheDir, "reporte_notas_${System.currentTimeMillis()}.xls")
            file.writeText(sb.toString(), Charsets.UTF_8)
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
            val avgText = if (group.average != null) String.format("%.1f", group.average) else "—"
            val teacher = group.teacherName?.let { " — Docente: $it" } ?: ""
            sb.appendLine("📘 ${group.subjectName}$teacher (Promedio: $avgText)")
            for (task in group.tasks) {
                val gradeStr = when {
                    task.notSubmitted -> "0 (No entregado)"
                    task.grade != null -> String.format("%.1f", task.grade)
                    else -> "Sin nota"
                }
                val ponderada = group.studentAverages[task.studentName]
                val ponderadaStr = if (ponderada != null) String.format("%.1f", ponderada) else "—"
                val dateStr = task.submissionDate?.let { " [${df.format(java.util.Date(it))}]" } ?: ""
                val graderStr = task.gradedByUsername?.let { " (Calificó: $it)" } ?: ""
                sb.appendLine("   • [${task.studentName}] ${task.title}: $gradeStr [Cal. Ponderada: $ponderadaStr]$dateStr$graderStr")
            }
            sb.appendLine()
        }
        val allGraded = report.flatMap { it.tasks }.mapNotNull { it.grade }
        val globalAvg = if (allGraded.isNotEmpty()) String.format("%.1f", allGraded.average()) else "—"
        sb.appendLine("📈 Promedio general: $globalAvg")
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

    fun generatePlatformPDF(context: Context, rows: List<PlatformGradeRow>): File? {        return try {
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

            // Title
            canvas.drawText("Reporte de Notas — Plataforma", margin, y + 20f, titlePaint)
            y += 30f
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            canvas.drawText("Generado el $dateStr", margin, y + 11f, subtitlePaint)
            y += 22f

            // Summary
            val totalSubs = rows.size
            val graded = rows.count { it.grade != null }
            val avgAll = rows.mapNotNull { it.grade }.let { if (it.isEmpty()) "—" else String.format("%.1f", it.average()) }
            val courseCount = rows.map { it.courseName }.toSet().size
            val summaryBg = RectF(margin, y, margin + contentWidth, y + 34f)
            canvas.drawRoundRect(summaryBg, 8f, 8f, bgPaint)
            val parts = listOf("$courseCount Cursos", "$totalSubs Entregas", "$graded Calificadas", "Prom: $avgAll")
            val step = contentWidth / parts.size
            val sp = Paint().apply { color = Color.parseColor("#6A1B9A"); textSize = 10f; isAntiAlias = true; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
            parts.forEachIndexed { i, text ->
                canvas.drawText(text, margin + step * i + step / 2 - sp.measureText(text) / 2, y + 21f, sp)
            }
            y += 44f

            // Column headers
            val col = floatArrayOf(margin, margin + 75f, margin + 165f, margin + 215f, margin + 290f, margin + 380f, margin + 450f)
            val headers = listOf("Estudiante", "Materia", "Tarea", "Nota", "Fecha", "Retroalimentación", "Calificó")
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
                canvas.drawText((row.studentUsername ?: "—").take(16), col[0], y + 11f, cellPaint)
                canvas.drawText((row.subjectName ?: "—").take(18), col[1], y + 11f, cellPaint)
                canvas.drawText((row.taskName ?: "—").take(20), col[2], y + 11f, cellPaint)
                val gText = if (row.grade != null) String.format("%.1f", row.grade) else "—"
                gradePaint.color = gradeColor(row.grade)
                canvas.drawText(gText, col[3] + 16f, y + 11f, gradePaint)
                val dateFmt = if (row.submissionDate > 0)
                    SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(row.submissionDate)) else "—"
                canvas.drawText(dateFmt, col[4], y + 11f, cellPaint)
                canvas.drawText((row.feedback ?: "—").take(20), col[5], y + 11f, cellPaint)
                canvas.drawText((row.gradedByUsername ?: "—").take(14), col[6], y + 11f, cellPaint)
                canvas.drawLine(margin, y + 15f, margin + contentWidth, y + 15f, linePaint)
                y += 18f
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

    fun generatePlatformCSV(context: Context, rows: List<PlatformGradeRow>): File? {
        return try {
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            val totalSubs = rows.size
            val graded = rows.count { it.grade != null }
            val avgAll = rows.mapNotNull { it.grade }.let { if (it.isEmpty()) "—" else String.format("%.1f", it.average()) }
            val courseCount = rows.map { it.courseName }.toSet().size
            val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

            val sb = StringBuilder()
            sb.append("<html xmlns:o=\"urn:schemas-microsoft-com:office:office\" xmlns:x=\"urn:schemas-microsoft-com:office:excel\" xmlns=\"http://www.w3.org/TR/REC-html40\">")
            sb.append("<head><meta charset=\"utf-8\"><!--[if gte mso 9]><xml><x:ExcelWorkbook><x:ExcelWorksheets><x:ExcelWorksheet>")
            sb.append("<x:Name>Reporte Plataforma</x:Name><x:WorksheetOptions><x:FitToPage/></x:WorksheetOptions>")
            sb.append("</x:ExcelWorksheet></x:ExcelWorksheets></x:ExcelWorkbook></xml><![endif]-->")
            sb.append("<style>* { font-family: Calibri, Arial, sans-serif; font-size: 10pt; } table { border-collapse: collapse; width: 100%; } th { background: #3b1060; color: #fff; font-weight: 700; padding: 9px 12px; text-align: left; border: 1px solid #2a0a45; white-space: nowrap; } td { padding: 6px 12px; border: 1px solid #e0dde8; vertical-align: top; }</style></head>")
            sb.append("<body>")
            sb.append("<h2 style=\"font-family:Calibri,Arial;color:#3b1060;margin:0 0 6px\">Reporte de Notas &mdash; Plataforma</h2>")
            sb.append("<p style=\"font-size:9pt;color:#666;margin:0 0 16px\">Generado el $dateStr &nbsp;&middot;&nbsp; $courseCount cursos &nbsp;&middot;&nbsp; $totalSubs entregas &nbsp;&middot;&nbsp; $graded calificadas &nbsp;&middot;&nbsp; Promedio: $avgAll</p>")
            sb.append("<table><colgroup>")
            sb.append("<col style=\"width:130pt\"><col style=\"width:130pt\"><col style=\"width:180pt\"><col style=\"width:55pt\"><col style=\"width:65pt\"><col style=\"width:120pt\"><col style=\"width:130pt\"><col style=\"width:260pt\">")
            sb.append("</colgroup><thead><tr>")
            sb.append("<th>Estudiante</th><th>Materia</th><th>Tarea</th><th style=\"text-align:center\">Nota</th><th style=\"text-align:center\">Cal. Ponderada</th><th>Fecha de entrega</th><th>Docente que calificó</th><th>Retroalimentación</th>")
            sb.append("</tr></thead><tbody>")

            // Compute ponderada per (studentUsername, subjectName)
            val ponderadaMap = mutableMapOf<Pair<String?, String?>, Pair<Float, Int>>()
            for (row in rows) {
                val key = Pair(row.studentUsername, row.subjectName)
                val (sum, count) = ponderadaMap.getOrDefault(key, Pair(0f, 0))
                ponderadaMap[key] = Pair(sum + (row.grade ?: 0f), count + 1)
            }
            val studentSubjectPonderada = ponderadaMap.mapValues { (_, v) -> v.first / v.second.toFloat() }

            var currentCourse = ""
            var rowIndex = 0
            for (row in rows) {
                if (row.courseName != currentCourse) {
                    currentCourse = row.courseName
                    rowIndex = 0
                    sb.append("<tr style=\"background:#f3eafe\"><td colspan=\"8\" style=\"font-size:11pt;font-weight:700;color:#4a0e8f;padding:8px 12px;border-bottom:2px solid #c9a0f5;border-left:4px solid #8b5cf6\">${escHtml(currentCourse)}</td></tr>")
                }
                val bg = if (rowIndex % 2 == 0) "#ffffff" else "#f9f7fc"
                val date = if (row.submissionDate > 0) df.format(Date(row.submissionDate)) else "—"
                val grade = if (row.grade != null) String.format("%.1f", row.grade) else "0"
                val gradeColor = when {
                    row.grade == null -> "#b91c1c"
                    row.grade >= 4f   -> "#1a7f37"
                    row.grade >= 3f   -> "#b45309"
                    else              -> "#b91c1c"
                }
                val ponderada = studentSubjectPonderada[Pair(row.studentUsername, row.subjectName)]
                val ponderadaStr = if (ponderada != null) String.format("%.1f", ponderada) else "—"
                val ponderadaColor = when {
                    ponderada == null  -> "#999999"
                    ponderada >= 4f   -> "#1a7f37"
                    ponderada >= 3f   -> "#b45309"
                    else              -> "#b91c1c"
                }
                val fb = row.feedback?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: "—"
                sb.append("<tr style=\"background:$bg\">")
                sb.append("<td style=\"white-space:nowrap\">${escHtml(row.studentUsername ?: "—")}</td>")
                sb.append("<td style=\"white-space:nowrap\">${escHtml(row.subjectName ?: "—")}</td>")
                sb.append("<td>${escHtml(row.taskName ?: "—")}</td>")
                sb.append("<td style=\"text-align:center;font-weight:700;color:$gradeColor\">$grade</td>")
                sb.append("<td style=\"text-align:center;font-weight:700;color:$ponderadaColor\">$ponderadaStr</td>")
                sb.append("<td style=\"white-space:nowrap\">$date</td>")
                sb.append("<td>${escHtml(row.gradedByUsername ?: "—")}</td>")
                sb.append("<td style=\"color:#555\">${escHtml(fb)}</td>")
                sb.append("</tr>")
                rowIndex++
            }
            sb.append("</tbody></table></body></html>")

            val file = File(context.cacheDir, "reporte_plataforma_${System.currentTimeMillis()}.xls")
            file.writeText(sb.toString(), Charsets.UTF_8)
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ── Platform Word (.doc as HTML) ────────────────────────────────────

    fun generatePlatformWord(context: Context, rows: List<PlatformGradeRow>): File? {
        return try {
            val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
            val totalSubs = rows.size
            val graded = rows.count { it.grade != null }
            val avgAll = rows.mapNotNull { it.grade }.let { if (it.isEmpty()) "—" else String.format("%.1f", it.average()) }
            val courseCount = rows.map { it.courseName }.toSet().size

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
<h1>Reporte de Notas — Plataforma</h1>
<p class="sub">Generado el $dateStr</p>
<div class="summary">Cursos: $courseCount &nbsp;|&nbsp; Entregas: $totalSubs &nbsp;|&nbsp; Calificadas: $graded &nbsp;|&nbsp; Promedio: $avgAll</div>
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
                val ponderadaStr = if (ponderada != null) String.format("%.1f", ponderada) else "—"
                sb.append("<tr>")
                sb.append("<td>${escHtml(row.studentUsername ?: "—")}</td>")
                sb.append("<td>${escHtml(row.subjectName ?: "—")}</td>")
                sb.append("<td>${escHtml(row.taskName ?: "—")}</td>")
                sb.append("<td style=\"text-align:center\">$grade</td>")
                sb.append("<td style=\"text-align:center\">$ponderadaStr</td>")
                sb.append("<td>$date</td>")
                sb.append("<td>${escHtml(row.feedback ?: "—")}</td>")
                sb.append("<td>${escHtml(row.gradedByUsername ?: "—")}</td>")
                sb.append("</tr>")
            }
            sb.append("</tbody></table></body></html>")

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
            val ponderadaStr = if (ponderada != null) String.format("%.1f", ponderada) else "—"
            val subj = row.subjectName?.let { " [$it]" } ?: ""
            val grader = row.gradedByUsername?.let { " (Calificó: $it)" } ?: ""
            sb.appendLine("   • ${row.studentUsername ?: "—"} — ${row.taskName ?: "—"}: $g [Cal. Ponderada: $ponderadaStr]$subj$grader")
        }
        val avgAll = rows.mapNotNull { it.grade }.let { if (it.isEmpty()) "—" else String.format("%.1f", it.average()) }
        sb.appendLine("\n📈 Promedio general: $avgAll")
        sb.appendLine("📋 Total entregas: ${rows.size} | Calificadas: ${rows.count { it.grade != null }}")
        return sb.toString()
    }

    private fun escHtml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

