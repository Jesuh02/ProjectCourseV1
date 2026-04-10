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
        val tasks: List<TaskReport>,
        val average: Float?
    )

    data class TaskReport(
        val title: String,
        val grade: Float?
    )

    fun buildReport(
        subjects: List<Subject>,
        allTasks: List<Task>,
        submissions: List<TaskSubmission>,
        topics: List<com.example.tareamov.data.entity.Topic>
    ): List<SubjectReport> {
        val submissionMap = submissions.associateBy { it.taskId }

        return subjects.map { subject ->
            val topicIds = topics.filter { it.subjectId == subject.id || it.courseId == subject.id }
                .map { it.id }.toSet()

            val subjectTasks = allTasks.filter { topicIds.contains(it.topicId) }

            val taskReports = subjectTasks.map { task ->
                TaskReport(
                    title = task.name.ifBlank { "Sin título" },
                    grade = submissionMap[task.id]?.grade
                )
            }

            val graded = taskReports.filter { it.grade != null }
            val avg = if (graded.isNotEmpty()) graded.map { it.grade!! }.average().toFloat() else null

            SubjectReport(subjectName = subject.name, tasks = taskReports, average = avg)
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
                // Subject header
                val headerBg = RectF(margin, y, margin + contentWidth, y + 28f)
                canvas.drawRoundRect(headerBg, 6f, 6f, bgPaint)
                canvas.drawText(group.subjectName, margin + 10f, y + 18f, subjectPaint)
                val avgText = if (group.average != null) String.format("%.1f", group.average) else "—"
                gradePaint.color = gradeColor(group.average)
                canvas.drawText(avgText, margin + contentWidth - 10f, y + 18f, gradePaint)
                y += 34f

                if (group.tasks.isEmpty()) {
                    canvas.drawText("Sin tareas", margin + 20f, y + 12f, subtitlePaint)
                    y += 20f
                }

                for (task in group.tasks) {
                    ensureSpace(20f)
                    canvas.drawText("• ${task.title}", margin + 20f, y + 12f, taskPaint)
                    val gText = if (task.grade != null) String.format("%.1f", task.grade) else "—"
                    gradePaint.color = gradeColor(task.grade)
                    canvas.drawText(gText, margin + contentWidth - 10f, y + 12f, gradePaint)
                    canvas.drawLine(margin + 20f, y + 18f, margin + contentWidth, y + 18f, linePaint)
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
            sb.appendLine("Materia,Tarea,Nota")
            for (group in report) {
                if (group.tasks.isEmpty()) {
                    sb.appendLine("\"${esc(group.subjectName)}\",\"Sin tareas\",\"\"")
                }
                for (task in group.tasks) {
                    sb.appendLine("\"${esc(group.subjectName)}\",\"${esc(task.title)}\",\"${task.grade ?: ""}\"")
                }
            }
            val allGraded = report.flatMap { it.tasks }.mapNotNull { it.grade }
            val avg = if (allGraded.isNotEmpty()) String.format("%.1f", allGraded.average()) else "—"
            sb.appendLine()
            sb.appendLine("\"Promedio general\",\"\",\"$avg\"")

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
        for (group in report) {
            val avgText = if (group.average != null) String.format("%.1f", group.average) else "—"
            sb.appendLine("📘 ${group.subjectName} (Promedio: $avgText)")
            for (task in group.tasks) {
                sb.appendLine("   • ${task.title}: ${if (task.grade != null) String.format("%.1f", task.grade) else "Sin nota"}")
            }
            sb.appendLine()
        }
        val allGraded = report.flatMap { it.tasks }.mapNotNull { it.grade }
        val globalAvg = if (allGraded.isNotEmpty()) String.format("%.1f", allGraded.average()) else "—"
        sb.appendLine("📈 Promedio general: $globalAvg")
        sb.appendLine("📋 Total tareas: ${report.sumOf { it.tasks.size }} | Calificadas: ${report.sumOf { it.tasks.count { t -> t.grade != null } }}")
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
}
