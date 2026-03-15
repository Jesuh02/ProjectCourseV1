package com.example.tareamov.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tareamov.data.entity.Institucion

@Dao
interface InstitucionDao {
    @Query("SELECT * FROM instituciones WHERE is_active = 1 ORDER BY nombre ASC")
    suspend fun getAll(): List<Institucion>

    @Query("SELECT * FROM instituciones WHERE is_active = 1 AND nombre LIKE '%' || :query || '%' ORDER BY nombre ASC LIMIT 20")
    suspend fun search(query: String): List<Institucion>

    @Query("SELECT * FROM instituciones WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): Institucion?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(instituciones: List<Institucion>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(institucion: Institucion): Long
}
