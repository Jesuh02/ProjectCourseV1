package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tareamov.data.entity.Rol

@Dao
interface RolDao {
    
    @Query("SELECT * FROM roles WHERE id = :id")
    suspend fun getRolById(id: Long): Rol?
    
    @Query("SELECT * FROM roles WHERE nombre = :nombre")
    suspend fun getRolByNombre(nombre: String): Rol?
    
    @Query("SELECT * FROM roles WHERE `default` = 1 LIMIT 1")
    suspend fun getDefaultRol(): Rol?
    
    @Query("SELECT * FROM roles ORDER BY nivel ASC")
    suspend fun getAllRoles(): List<Rol>
    
    @Query("SELECT * FROM roles WHERE nivel >= :minLevel ORDER BY nivel ASC")
    suspend fun getRolesByMinLevel(minLevel: Float): List<Rol>
    
    @Query("SELECT * FROM roles WHERE nombre = 'docente' LIMIT 1")
    suspend fun getDocenteRole(): Rol?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRol(rol: Rol): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoles(roles: List<Rol>)
    
    @Update
    suspend fun updateRol(rol: Rol)
    
    @Query("DELETE FROM roles WHERE id = :id")
    suspend fun deleteRol(id: Long)
    
    @Query("SELECT COUNT(*) FROM roles")
    suspend fun getRoleCount(): Int
    
    @Query("SELECT EXISTS(SELECT 1 FROM roles WHERE nombre = :nombre)")
    suspend fun roleExists(nombre: String): Boolean
}
