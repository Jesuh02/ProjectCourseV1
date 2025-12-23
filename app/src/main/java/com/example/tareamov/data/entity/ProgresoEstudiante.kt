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
    primaryKeys = ["usuario_estudiante", "curso_id"],
    foreignKeys = [
        androidx.room.ForeignKey(
            entity = Course::class,
            parentColumns = ["id"],
            childColumns = ["curso_id"],
            onDelete = androidx.room.ForeignKey.CASCADE
        )
    ],
    indices = [
        androidx.room.Index("curso_id"),
        androidx.room.Index("usuario_estudiante")
    ]
)
data class ProgresoEstudiante(
    @androidx.room.ColumnInfo(name = "usuario_estudiante")
    val usuarioEstudiante: Long = 0, // Changed from String to Long to match Supabase bigint
    @androidx.room.ColumnInfo(name = "curso_id")
    val cursoId: Long = 0,
    @androidx.room.ColumnInfo(name = "tareas_completadas")
    val tareasCompletadas: Int = 0,
    @androidx.room.ColumnInfo(name = "tareas_totales")
    val tareasTotales: Int = 0,
    @androidx.room.ColumnInfo(name = "porcentaje_progreso")
    val porcentajeProgreso: Float = 0f,
    @androidx.room.ColumnInfo(name = "calificacion_ponderada")
    val calificacionPonderada: Float? = null,
    @androidx.room.ColumnInfo(name = "promedio")
    val promedio: Float? = null, // Promedio de calificaciones (alias de calificacionPonderada)
    // Estado se genera automáticamente en Supabase, pero lo guardamos localmente
    val estado: String? = null, // "Ganado" o "Perdido"
    @androidx.room.ColumnInfo(name = "ultima_calculada_en")
    val ultimaCalculadaEn: Long = System.currentTimeMillis(),
    @androidx.room.ColumnInfo(name = "certificado_emitido_en")
    val certificadoEmitidoEn: Long? = null,
    @androidx.room.ColumnInfo(name = "certificado_url")
    val certificadoUrl: String? = null, // URL del certificado en Cloudflare R2
    @androidx.room.ColumnInfo(name = "creado_en")
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
