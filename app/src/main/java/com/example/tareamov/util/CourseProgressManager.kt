package com.example.tareamov.util

import android.content.Context
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.tareamov.data.AppDatabase
import com.example.tareamov.service.ApiResult
import com.example.tareamov.service.BackendApiService
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DecimalFormat

class CourseProgressManager(private val context: Context) {

    data class ProgressUiState(
        val progressPercent: Int,
        val completedTasks: Int,
        val totalTasks: Int,
        val averageGrade: Float,
        val status: String,
        val canDownloadCertificate: Boolean
    )

    suspend fun calculateAndDisplayCourseProgress(
        courseId: Long,
        userId: Long,
        progressContainer: LinearLayout,
        progressBar: ProgressBar,
        progressPercentTextView: TextView,
        progressStatusTextView: TextView
    ): ProgressUiState {
        try {
            val backendState = fetchProgressFromBackend(courseId)
            val state = backendState ?: calculateAndSyncFallback(courseId, userId)

            progressContainer.visibility = View.VISIBLE
            progressBar.max = 100
            progressBar.progress = state.progressPercent.coerceIn(0, 100)
            progressPercentTextView.text = "${state.progressPercent}% completado"

            val df = DecimalFormat("#.#")
            val gradeColor = when {
                state.averageGrade >= 6.0f -> android.graphics.Color.parseColor("#4CAF50")
                state.averageGrade > 0f -> android.graphics.Color.parseColor("#F44336")
                else -> android.graphics.Color.parseColor("#AAAAAA")
            }

            val statusLabel = if (state.averageGrade > 0f) {
                if (state.averageGrade >= 6.0f) "Aprobando" else "Reprobando"
            } else {
                state.status
            }

            progressStatusTextView.text = "Calificación: ${df.format(state.averageGrade)}/10 ($statusLabel)"
            progressStatusTextView.setTextColor(gradeColor)

            return state

        } catch (e: Exception) {
            Log.e("CourseProgressManager", "Error calculating progress", e)
            progressContainer.visibility = View.VISIBLE
            progressBar.max = 100
            progressBar.progress = 0
            progressPercentTextView.text = "0% completado"
            progressStatusTextView.text = "Calificación: 0/10"
            progressStatusTextView.setTextColor(android.graphics.Color.parseColor("#AAAAAA"))
            return ProgressUiState(
                progressPercent = 0,
                completedTasks = 0,
                totalTasks = 0,
                averageGrade = 0f,
                status = "Perdido",
                canDownloadCertificate = false
            )
        }
    }

    private suspend fun fetchProgressFromBackend(courseId: Long): ProgressUiState? {
        if (!BackendApiService.isAuthenticated) return null

        withContext(Dispatchers.IO) {
            BackendApiService.recalculateProgress(courseId)
        }

        val progressResult = withContext(Dispatchers.IO) {
            BackendApiService.getProgressByCourseRaw(courseId)
        }

        if (progressResult !is ApiResult.Success || progressResult.data == null) return null

        return mapBackendJsonToUiState(progressResult.data)
    }

    private suspend fun calculateAndSyncFallback(courseId: Long, userId: Long): ProgressUiState {
        val db = AppDatabase.getDatabase(context)

        val topics = withContext(Dispatchers.IO) { db.topicDao().getTopicsByCourse(courseId) }
        val topicIds = topics.map { it.id }
        val tasks = if (topicIds.isNotEmpty()) {
            withContext(Dispatchers.IO) { db.taskDao().getTasksByTopicIds(topicIds) }
        } else {
            emptyList()
        }

        val submissions = withContext(Dispatchers.IO) {
            db.taskSubmissionDao().getStudentSubmissionsForCourse(userId, courseId)
        }

        val totalTasks = tasks.size
        val completedTasks = submissions.distinctBy { it.taskId }.size
        val progressPercent = if (totalTasks > 0) {
            ((completedTasks * 100f) / totalTasks.toFloat()).toInt()
        } else {
            0
        }

        val gradedSubmissions = submissions.mapNotNull { it.grade }
        val averageGrade = if (gradedSubmissions.isNotEmpty()) {
            gradedSubmissions.average().toFloat()
        } else {
            0f
        }

        val status = if (averageGrade >= 6f) "Ganado" else "Perdido"

        if (BackendApiService.isAuthenticated) {
            withContext(Dispatchers.IO) {
                BackendApiService.upsertProgress(
                    mapOf(
                        "courseId" to courseId,
                        "completedTasks" to completedTasks,
                        "totalTasks" to totalTasks,
                        "progressPercentage" to progressPercent,
                        "averageGrade" to averageGrade,
                        "status" to status,
                        "tareasCompletadas" to completedTasks,
                        "tareasTotales" to totalTasks,
                        "porcentajeProgreso" to progressPercent,
                        "calificacionPonderada" to averageGrade,
                        "estado" to status
                    )
                )
            }
        }

        return ProgressUiState(
            progressPercent = progressPercent,
            completedTasks = completedTasks,
            totalTasks = totalTasks,
            averageGrade = averageGrade,
            status = status,
            canDownloadCertificate = averageGrade >= 6f
        )
    }

    private fun mapBackendJsonToUiState(data: JsonObject): ProgressUiState {
        val completedTasks = data.getNumericInt("completedTasks", data.getNumericInt("tareasCompletadas", 0))
        val totalTasks = data.getNumericInt("totalTasks", data.getNumericInt("tareasTotales", 0))

        val progressPercent = data.getNumericFloat(
            "progressPercentage",
            data.getNumericFloat("porcentajeProgreso", 0f)
        ).toInt().coerceIn(0, 100)

        val averageGrade = data.getNumericFloat(
            "averageGrade",
            data.getNumericFloat(
                "calificacionPonderada",
                data.getNumericFloat("promedio", 0f)
            )
        )

        val status = when {
            data.has("status") && !data.get("status").isJsonNull -> data.get("status").asString
            data.has("estado") && !data.get("estado").isJsonNull -> data.get("estado").asString
            averageGrade >= 6f -> "Ganado"
            else -> "Perdido"
        }

        return ProgressUiState(
            progressPercent = progressPercent,
            completedTasks = completedTasks,
            totalTasks = totalTasks,
            averageGrade = averageGrade,
            status = status,
            canDownloadCertificate = averageGrade >= 6f
        )
    }

    private fun JsonObject.getNumericInt(name: String, default: Int): Int {
        return try {
            if (!has(name) || get(name).isJsonNull) default else get(name).asNumber.toInt()
        } catch (_: Exception) {
            default
        }
    }

    private fun JsonObject.getNumericFloat(name: String, default: Float): Float {
        return try {
            if (!has(name) || get(name).isJsonNull) default else get(name).asNumber.toFloat()
        } catch (_: Exception) {
            default
        }
    }
}