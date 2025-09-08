package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Delete
import com.example.tareamov.data.entity.RolRecurso
import com.example.tareamov.data.entity.Recurso

@Dao
interface RolRecursoDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRolRecurso(rolRecurso: RolRecurso)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRolRecursos(rolRecursos: List<RolRecurso>)
    
    @Delete
    suspend fun deleteRolRecurso(rolRecurso: RolRecurso)
    
    @Query("DELETE FROM rol_recursos WHERE rolId = :rolId")
    suspend fun deleteRecursosByRol(rolId: Long)
    
    @Query("DELETE FROM rol_recursos WHERE recursoId = :recursoId")
    suspend fun deleteRolesByRecurso(recursoId: Long)
    
    @Query("""
        SELECT r.* FROM recursos r 
        INNER JOIN rol_recursos rr ON r.id = rr.recursoId 
        WHERE rr.rolId = :rolId 
        ORDER BY r.orden ASC
    """)
    suspend fun getRecursosByRol(rolId: Long): List<Recurso>
    
    @Query("""
        SELECT r.* FROM recursos r 
        INNER JOIN rol_recursos rr ON r.id = rr.recursoId 
        WHERE rr.rolId = :rolId AND r.padreId IS NULL 
        ORDER BY r.orden ASC
    """)
    suspend fun getRecursosPrincipalesByRol(rolId: Long): List<Recurso>
    
    @Query("""
        SELECT r.* FROM recursos r 
        INNER JOIN rol_recursos rr ON r.id = rr.recursoId 
        WHERE rr.rolId = :rolId AND r.padreId = :padreId 
        ORDER BY r.orden ASC
    """)
    suspend fun getSubRecursosByRol(rolId: Long, padreId: Long): List<Recurso>
    
    @Query("""
        SELECT COUNT(*) > 0 FROM rol_recursos 
        WHERE rolId = :rolId AND recursoId = :recursoId
    """)
    suspend fun hasAccess(rolId: Long, recursoId: Long): Boolean
    
    @Query("""
        SELECT COUNT(*) > 0 FROM recursos r 
        INNER JOIN rol_recursos rr ON r.id = rr.recursoId 
        WHERE rr.rolId = :rolId AND r.icono = :icono
    """)
    suspend fun hasAccessToIcon(rolId: Long, icono: String): Boolean
    
    @Query("""
        SELECT COUNT(*) > 0 FROM recursos r 
        INNER JOIN rol_recursos rr ON r.id = rr.recursoId 
        WHERE rr.rolId = :rolId AND r.icono = :icono AND r.interfaz = :interfaz
    """)
    suspend fun hasAccessToIconInInterface(rolId: Long, icono: String, interfaz: String): Boolean
    
    @Query("""
        SELECT r.* FROM recursos r 
        INNER JOIN rol_recursos rr ON r.id = rr.recursoId 
        WHERE rr.rolId = :rolId AND r.interfaz = :interfaz 
        ORDER BY r.orden ASC
    """)
    suspend fun getRecursosByRolAndInterfaz(rolId: Long, interfaz: String): List<Recurso>
    
    @Query("DELETE FROM rol_recursos")
    suspend fun deleteAllRolRecursos()
    
    @Query("SELECT * FROM rol_recursos")
    suspend fun getAllRolRecursos(): List<RolRecurso>
}
