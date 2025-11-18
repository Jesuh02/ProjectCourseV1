package com.example.tareamov.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
    val usuarioEstudiante: Long = 0, // Changed from String to Long to match Supabase bigint
    val cursoId: Long = 0,
    val tareasCompletadas: Int = 0,
    val tareasTotales: Int = 0,
    val porcentajeProgreso: Float = 0f,
    val calificacionPonderada: Float? = null,
    val promedio: Float? = null, // Promedio de calificaciones (alias de calificacionPonderada)
    // Estado se genera automáticamente en Supabase, pero lo guardamos localmente
    val estado: String? = null, // "Ganado" o "Perdido"
    val ultimaCalculadaEn: Long = System.currentTimeMillis(),
    val certificadoEmitidoEn: Long? = null,
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
