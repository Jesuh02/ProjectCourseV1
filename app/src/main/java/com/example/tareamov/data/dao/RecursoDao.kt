package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Delete
import com.example.tareamov.data.entity.Recurso

@Dao
interface RecursoDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecurso(recurso: Recurso): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecursos(recursos: List<Recurso>)
    
    @Update
    suspend fun updateRecurso(recurso: Recurso)
    
    @Delete
    suspend fun deleteRecurso(recurso: Recurso)
    
    @Query("SELECT * FROM recursos ORDER BY orden ASC")
    suspend fun getAllRecursos(): List<Recurso>
    
    @Query("SELECT * FROM recursos WHERE id = :id")
    suspend fun getRecursoById(id: Long): Recurso?
    
    @Query("SELECT * FROM recursos WHERE padreId IS NULL ORDER BY orden ASC")
    suspend fun getRecursosPrincipales(): List<Recurso>
    
    @Query("SELECT * FROM recursos WHERE padreId = :padreId ORDER BY orden ASC")
    suspend fun getSubRecursos(padreId: Long): List<Recurso>
    
    @Query("SELECT * FROM recursos WHERE icono = :icono")
    suspend fun getRecursoByIcono(icono: String): Recurso?
    
    @Query("SELECT * FROM recursos WHERE interfaz = :interfaz ORDER BY orden ASC")
    suspend fun getRecursosByInterfaz(interfaz: String): List<Recurso>
    
    @Query("SELECT * FROM recursos WHERE icono = :icono AND interfaz = :interfaz")
    suspend fun getRecursoByIconoAndInterfaz(icono: String, interfaz: String): Recurso?
    
    @Query("DELETE FROM recursos")
    suspend fun deleteAllRecursos()
}
