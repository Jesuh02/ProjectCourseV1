package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tareamov.data.entity.ProgresoEstudiante
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgresoEstudianteDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgreso(progreso: ProgresoEstudiante)
    
    @Update
    suspend fun updateProgreso(progreso: ProgresoEstudiante)
    
    @Query("SELECT * FROM progreso_estudiante WHERE usuarioEstudiante = :username AND cursoId = :courseId")
    suspend fun getProgreso(username: String, courseId: Long): ProgresoEstudiante?
    
    // Alias for clarity
    @Query("SELECT * FROM progreso_estudiante WHERE usuarioEstudiante = :username AND cursoId = :courseId")
    suspend fun getProgresoByUsuarioAndCurso(username: String, courseId: Long): ProgresoEstudiante?
    
    @Query("SELECT * FROM progreso_estudiante WHERE usuarioEstudiante = :username")
    suspend fun getProgresosByUsuario(username: String): List<ProgresoEstudiante>
    
    @Query("SELECT * FROM progreso_estudiante WHERE cursoId = :courseId")
    suspend fun getProgresosByCurso(courseId: Long): List<ProgresoEstudiante>
    
    @Query("SELECT * FROM progreso_estudiante WHERE usuarioEstudiante = :username AND cursoId = :courseId")
    fun getProgresoFlow(username: String, courseId: Long): Flow<ProgresoEstudiante?>
    
    /**
     * Upsert: Insert or replace
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progreso: ProgresoEstudiante)
    
    @Query("DELETE FROM progreso_estudiante WHERE usuarioEstudiante = :username AND cursoId = :courseId")
    suspend fun deleteProgreso(username: String, courseId: Long)
    
    @Query("SELECT * FROM progreso_estudiante")
    suspend fun getAllProgresos(): List<ProgresoEstudiante>
    
    /**
     * Obtiene estudiantes que han ganado el curso (calificación >= 6.0)
     */
    @Query("SELECT * FROM progreso_estudiante WHERE cursoId = :courseId AND calificacionPonderada >= 6.0")
    suspend fun getEstudiantesGanadores(courseId: Long): List<ProgresoEstudiante>
    
    /**
     * Obtiene estudiantes que han perdido el curso (calificación < 6.0)
     */
    @Query("SELECT * FROM progreso_estudiante WHERE cursoId = :courseId AND calificacionPonderada < 6.0")
    suspend fun getEstudiantesPerdedores(courseId: Long): List<ProgresoEstudiante>
    
    /**
     * Obtiene el progreso promedio de un curso
     */
    @Query("SELECT AVG(porcentajeProgreso) FROM progreso_estudiante WHERE cursoId = :courseId")
    suspend fun getProgresoPromedio(courseId: Long): Float?
    
    /**
     * Cuenta estudiantes inscritos en un curso (con progreso registrado)
     */
    @Query("SELECT COUNT(*) FROM progreso_estudiante WHERE cursoId = :courseId")
    suspend fun contarEstudiantes(courseId: Long): Int
}
