package com.example.tareamov.data.dao

import androidx.room.*
import com.example.tareamov.data.entity.Usuario

@Dao
interface UsuarioDao {
    // Cambia este método para recibir la contraseña ya encriptada
    @Query("SELECT * FROM usuarios WHERE username = :username AND contrasena = :passwordHash")
    suspend fun login(username: String, passwordHash: String): Usuario?

    @Query("SELECT * FROM usuarios")
    suspend fun getAllUsuarios(): List<Usuario>

    @Query("""
        SELECT u.* FROM usuarios u 
        INNER JOIN roles r ON u.rol_id = r.id 
        WHERE r.nombre = :roleName
    """)
    suspend fun getUsuariosByRoleName(roleName: String): List<Usuario>

    @Query("SELECT * FROM usuarios WHERE rol_id = :rolId")
    suspend fun getUsuariosByRolId(rolId: Long): List<Usuario>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUsuario(usuario: Usuario): Long

    @Update
    suspend fun updateUsuario(usuario: Usuario)

    @Delete
    suspend fun deleteUsuario(usuario: Usuario)

    @Query("SELECT * FROM usuarios WHERE persona_id = :personaId")
    suspend fun getUsuarioByPersonaId(personaId: Long): Usuario?

    @Query("SELECT COUNT(*) FROM usuarios")
    suspend fun getUserCount(): Int

    @Query("""
        SELECT COUNT(*) FROM usuarios u 
        INNER JOIN roles r ON u.rol_id = r.id 
        WHERE r.nombre = :roleName
    """)
    suspend fun getUserCountByRoleName(roleName: String): Int

    @Query("SELECT COUNT(*) FROM usuarios WHERE rol_id = :rolId")
    suspend fun getUserCountByRolId(rolId: Long): Int

    @Query("SELECT * FROM usuarios WHERE username = :username LIMIT 1")
    suspend fun getUsuarioByUsername(username: String): Usuario?

    @Query("SELECT persona_id FROM usuarios")
    suspend fun getAllUserPersonaIds(): List<Long>

    @Query("SELECT * FROM usuarios WHERE id = :id LIMIT 1")
    suspend fun getUsuarioById(id: Long): Usuario?
    
    @Query("SELECT * FROM usuarios WHERE email = :email LIMIT 1")
    suspend fun getUsuarioByEmail(email: String): Usuario?

    @Query("UPDATE usuarios SET rol_id = :rolId WHERE id = :userId")
    suspend fun updateUserRolId(userId: Long, rolId: Long)

    // Method to get user with role information
    @Query("""
        SELECT u.*, r.nombre as rolNombre, r.nivel as rolNivel 
        FROM usuarios u 
        INNER JOIN roles r ON u.rol_id = r.id 
        WHERE u.username = :username 
        LIMIT 1
    """)
    suspend fun getUsuarioWithRoleByUsername(username: String): UsuarioWithRole?

    @Query("""
        SELECT u.*, r.nombre as rolNombre, r.nivel as rolNivel 
        FROM usuarios u 
        INNER JOIN roles r ON u.rol_id = r.id 
        WHERE u.id = :userId 
        LIMIT 1
    """)
    suspend fun getUsuarioWithRoleById(userId: Long): UsuarioWithRole?

    // Deprecated methods for backward compatibility
    @Deprecated("Use getUsuariosByRoleName instead")
    @Query("""
        SELECT u.* FROM usuarios u 
        INNER JOIN roles r ON u.rol_id = r.id 
        WHERE r.nombre = :role
    """)
    suspend fun getUsuariosByRole(role: String): List<Usuario>

    @Deprecated("Use getUserCountByRoleName instead")
    @Query("""
        SELECT COUNT(*) FROM usuarios u 
        INNER JOIN roles r ON u.rol_id = r.id 
        WHERE r.nombre = :role
    """)
    suspend fun getUserCountByRole(role: String): Int

    @Deprecated("Use updateUserRolId instead")
    @Query("""
        UPDATE usuarios SET rol_id = (
            SELECT id FROM roles WHERE nombre = :role LIMIT 1
        ) WHERE id = :userId
    """)
    suspend fun updateUserRole(userId: Long, role: String)
}