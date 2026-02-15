package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

/**
 * Entidad que representa el progreso de un estudiante en un curso.
 * Se sincroniza con la tabla progreso_estudiante en Supabase.
 * 
 * NOTA: No usa Foreign Key constraint porque los cursos pueden existir en Supabase
 * sin estar sincronizados localmente. La integridad referencial se maneja en Supabase.
 */
@Entity(
    tableName = "progreso_estudiante",
    primaryKeys = ["usuarioEstudiante", "cursoId"],
    indices = [
        Index("cursoId"),
        Index("usuarioEstudiante")
    ]
)
data class ProgresoEstudiante(
    @SerializedName(value = "usuarioEstudiante", alternate = ["userId", "usuario_estudiante"])
    val usuarioEstudiante: Long = 0, // Changed from String to Long to match Supabase bigint
    @SerializedName(value = "cursoId", alternate = ["courseId", "curso_id"])
    val cursoId: Long = 0,
    @SerializedName(value = "tareasCompletadas", alternate = ["completedTasks", "tareas_completadas"])
    val tareasCompletadas: Int = 0,
    @SerializedName(value = "tareasTotales", alternate = ["totalTasks", "tareas_totales"])
    val tareasTotales: Int = 0,
    @SerializedName(value = "porcentajeProgreso", alternate = ["progressPercentage", "porcentaje_progreso"])
    val porcentajeProgreso: Float = 0f,
    val calificacionPonderada: Float? = null,
    @SerializedName(value = "promedio", alternate = ["averageGrade"])
    val promedio: Float? = null, // Promedio de calificaciones (alias de calificacionPonderada)
    // Estado se genera automáticamente en Supabase, pero lo guardamos localmente
    @SerializedName(value = "estado", alternate = ["status"])
    val estado: String? = null, // "Ganado" o "Perdido"
    val ultimaCalculadaEn: Long = System.currentTimeMillis(),
    val certificadoEmitidoEn: Long? = null,
    val certificadoUrl: String? = null, // URL del certificado en Cloudflare R2
    val creadoEn: Long = System.currentTimeMillis()
) {
    /**
     * Calcula el estado basado en la calificación ponderada o promedio
     * Ganado si calificación >= 6.0, Perdido en caso contrario
     */
    fun calcularEstado(): String {
        val grade = promedio ?: calificacionPonderada ?: 0f
        return if (grade >= 6f) "Ganado" else "Perdido"
    }
}
