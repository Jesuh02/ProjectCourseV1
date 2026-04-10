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
        val average: Float?
    )

    data class TaskReport(
        val studentName: String,
        val title: String,
        val grade: Float?,
        val submissionDate: Long?,
        val feedback: String?,
        val gradedByUsername: String? = null
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

        return subjects.map { subject ->
            val topicIds = topics.filter { it.subjectId == subject.id || it.courseId == subject.id }
                .map { it.id }.toSet()

            val subjectTasks = allTasks.filter { topicIds.contains(it.topicId) }

            val taskReports = mutableListOf<TaskReport>()
            for (task in subjectTasks) {
                val taskSubs = submissionsByTask[task.id] ?: continue
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
            }

            val graded = taskReports.filter { it.grade != null }
            val avg = if (graded.isNotEmpty()) graded.map { it.grade!! }.average().toFloat() else null

            SubjectReport(
                subjectName = subject.name,
                teacherName = subject.createdBy?.let { teachers[it] ?: "Docente #$it" },
                tasks = taskReports,
                average = avg
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
                    contentWidth * 0.16f, // Estudiante
                    contentWidth * 0.22f, // Tarea
                    contentWidth * 0.08f, // Nota
                    contentWidth * 0.16f, // Fecha
                    contentWidth * 0.22f, // Retroalimentación
                    contentWidth * 0.16f  // Calificó
                )

                for (task in group.tasks) {
                    ensureSpace(22f)
                    var cx = margin + 10f
                    // Estudiante
                    canvas.drawText(task.studentName.take(16), cx, y + 13f, taskPaint)
                    cx += colWidths[0]
                    // Tarea
                    canvas.drawText(task.title.take(22), cx, y + 13f, taskPaint)
                    cx += colWidths[1]
                    // Nota
                    val gText = if (task.grade != null) String.format("%.1f", task.grade) else "—"
                    gradePaint.color = gradeColor(task.grade)
                    gradePaint.textAlign = Paint.Align.LEFT
                    canvas.drawText(gText, cx, y + 13f, gradePaint)
                    gradePaint.textAlign = Paint.Align.RIGHT
                    cx += colWidths[2]
                    // Fecha
                    val dateStr = task.submissionDate?.let {
                        SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(java.util.Date(it))
                    } ?: "—"
                    canvas.drawText(dateStr, cx, y + 13f, subtitlePaint)
                    cx += colWidths[3]
                    // Retroalimentación (truncated)
                    val fb = task.feedback?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.take(40) ?: "—"
                    canvas.drawText(fb, cx, y + 13f, subtitlePaint)
                    cx += colWidths[4]
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

    // ── CSV ─────────────────────────────────────────────────────────────

    fun generateCSV(context: Context, report: List<SubjectReport>): File? {
        return try {
            val sb = StringBuilder()
            sb.appendLine("Materia,Docente,Estudiante,Tarea,Nota,Fecha de entrega,Retroalimentación,Calificó")
            val df = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            for (group in report) {
                val teacher = group.teacherName ?: ""
                if (group.tasks.isEmpty()) {
                    sb.appendLine("\"${esc(group.subjectName)}\",\"${esc(teacher)}\",\"\",\"Sin entregas\",\"\",\"\",\"\",\"\"")
                }
                for (task in group.tasks) {
                    val dateStr = task.submissionDate?.let { df.format(java.util.Date(it)) } ?: ""
                    val fb = task.feedback?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim() ?: ""
                    val grader = task.gradedByUsername ?: ""
                    sb.appendLine("\"${esc(group.subjectName)}\",\"${esc(teacher)}\",\"${esc(task.studentName)}\",\"${esc(task.title)}\",\"${task.grade ?: ""}\",\"$dateStr\",\"${esc(fb)}\",\"${esc(grader)}\"")
                }
            }
            val allGraded = report.flatMap { it.tasks }.mapNotNull { it.grade }
            val avg = if (allGraded.isNotEmpty()) String.format("%.1f", allGraded.average()) else "—"
            sb.appendLine()
            sb.appendLine("\"Promedio general\",\"\",\"\",\"\",\"$avg\",\"\",\"\"")

            val file = File(context.cacheDir, "reporte_notas_${System.currentTimeMillis()}.csv")
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
                val gradeStr = if (task.grade != null) String.format("%.1f", task.grade) else "Sin nota"
                val dateStr = task.submissionDate?.let { " [${df.format(java.util.Date(it))}]" } ?: ""
                val graderStr = task.gradedByUsername?.let { " (Calificó: $it)" } ?: ""
                sb.appendLine("   • [${task.studentName}] ${task.title}: $gradeStr$dateStr$graderStr")
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

    // ── Platform CSV ──────────────────────────────────────────────────────

    fun generatePlatformCSV(context: Context, rows: List<PlatformGradeRow>): File? {
        return try {
            val sb = StringBuilder()
            sb.appendLine("Curso,Materia,Estudiante,Tarea,Nota,Fecha de entrega,Retroalimentación,Calificó")
            for (row in rows) {
                val date = if (row.submissionDate > 0)
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(row.submissionDate)) else ""
                sb.appendLine("\"${esc(row.courseName)}\",\"${esc(row.subjectName ?: "")}\",\"${esc(row.studentUsername ?: "")}\",\"${esc(row.taskName ?: "")}\",\"${row.grade ?: ""}\",\"$date\",\"${esc(row.feedback ?: "")}\",\"${esc(row.gradedByUsername ?: "")}\"")
            }
            val file = File(context.cacheDir, "reporte_plataforma_${System.currentTimeMillis()}.csv")
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
<table><thead><tr><th>Estudiante</th><th>Materia</th><th>Tarea</th><th>Nota</th><th>Fecha de entrega</th><th>Retroalimentación</th><th>Calificó</th></tr></thead><tbody>""".trimIndent())

            var currentCourse = ""
            for (row in rows) {
                if (row.courseName != currentCourse) {
                    currentCourse = row.courseName
                    sb.append("<tr class=\"ch\"><td colspan=\"7\">${escHtml(currentCourse)}</td></tr>")
                }
                val date = if (row.submissionDate > 0)
                    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(row.submissionDate)) else "—"
                val grade = if (row.grade != null) String.format("%.1f", row.grade) else "—"
                sb.append("<tr>")
                sb.append("<td>${escHtml(row.studentUsername ?: "—")}</td>")
                sb.append("<td>${escHtml(row.subjectName ?: "—")}</td>")
                sb.append("<td>${escHtml(row.taskName ?: "—")}</td>")
                sb.append("<td style=\"text-align:center\">$grade</td>")
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
        var currentCourse = ""
        for (row in rows) {
            if (row.courseName != currentCourse) {
                currentCourse = row.courseName
                sb.appendLine("📘 $currentCourse")
            }
            val g = if (row.grade != null) String.format("%.1f", row.grade) else "Sin nota"
                val subj = row.subjectName?.let { " [$it]" } ?: ""
                val grader = row.gradedByUsername?.let { " (Calificó: $it)" } ?: ""
                sb.appendLine("   • ${row.studentUsername ?: "—"} — ${row.taskName ?: "—"}: $g$subj$grader")
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

