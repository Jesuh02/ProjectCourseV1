package com.example.tareamov.repository

import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.data.dao.UsuarioWithRole
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.data.entity.Rol

class UsuarioRepository(
    private val usuarioDao: UsuarioDao
) {
    suspend fun login(username: String, password: String): Usuario? {
        // Instead of using the DAO's login method which checks both username and password,
        // we'll just get the user by username and check the password in the ViewModel
        return usuarioDao.getUsuarioByUsername(username)
    }

    suspend fun insert(usuario: Usuario): Long {
        // Ensure password is bcrypt-hashed before saving
        val passwordToStore = if (usuario.contrasena.startsWith("$2a$") || usuario.contrasena.startsWith("$2b$") || usuario.contrasena.startsWith("$2y$")) {
            usuario.contrasena
        } else {
            at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, usuario.contrasena.toCharArray())
        }
        val usuarioToSave = usuario.copy(contrasena = passwordToStore)
        return usuarioDao.insertUsuario(usuarioToSave)
    }

    suspend fun update(usuario: Usuario) {
        // Ensure password is hashed if it looks like plain text
        val passwordToStore = if (usuario.contrasena.startsWith("$2a$") || usuario.contrasena.startsWith("$2b$") || usuario.contrasena.startsWith("$2y$")) {
            usuario.contrasena
        } else {
            at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, usuario.contrasena.toCharArray())
        }
        val usuarioToSave = usuario.copy(contrasena = passwordToStore)
        usuarioDao.updateUsuario(usuarioToSave)
    }

    suspend fun delete(usuario: Usuario) {
        usuarioDao.deleteUsuario(usuario)
    }

    suspend fun getUsuarioByPersonaId(personaId: Long): Usuario? {
        return usuarioDao.getUsuarioByPersonaId(personaId)
    }

    suspend fun getAllUserPersonaIds(): List<Long> {
        return usuarioDao.getAllUserPersonaIds()
    }

    suspend fun getUsuarioById(id: Long): Usuario? {
        return usuarioDao.getUsuarioById(id)
    }

    suspend fun getUsuarioByUsername(username: String): Usuario? {
        return usuarioDao.getUsuarioByUsername(username)
    }

    // Add the missing findByUsername method
    suspend fun findByUsername(username: String): Usuario? {
        return usuarioDao.getUsuarioByUsername(username)
    }

    suspend fun getAllUsuarios(): List<Usuario> {
        return usuarioDao.getAllUsuarios()
    }

    // New methods using rol_id
    suspend fun getUsuariosByRolId(rolId: Long): List<Usuario> {
        return usuarioDao.getUsuariosByRolId(rolId)
    }

    suspend fun getUsuariosByRoleName(roleName: String): List<Usuario> {
        return usuarioDao.getUsuariosByRoleName(roleName)
    }

    suspend fun getUserCountByRolId(rolId: Long): Int {
        return usuarioDao.getUserCountByRolId(rolId)
    }

    suspend fun getUserCountByRoleName(roleName: String): Int {
        return usuarioDao.getUserCountByRoleName(roleName)
    }

    suspend fun updateUserRolId(userId: Long, rolId: Long): Boolean {
        val usuario = getUsuarioById(userId)
        return if (usuario != null) {
            usuarioDao.updateUserRolId(userId, rolId)
            true
        } else {
            false
        }
    }

    // Methods with role information
    suspend fun getUsuarioWithRoleByUsername(username: String): UsuarioWithRole? {
        return usuarioDao.getUsuarioWithRoleByUsername(username)
    }

    suspend fun getUsuarioWithRoleById(userId: Long): UsuarioWithRole? {
        return usuarioDao.getUsuarioWithRoleById(userId)
    }

    // Backward compatibility methods (deprecated)
    @Deprecated("Use getUsuariosByRoleName instead")
    suspend fun getUsuariosByRole(role: String): List<Usuario> {
        return usuarioDao.getUsuariosByRole(role)
    }

    @Deprecated("Use getUserCountByRoleName instead")
    suspend fun getUserCountByRole(role: String): Int {
        return usuarioDao.getUserCountByRole(role)
    }

    @Deprecated("Use updateUserRolId instead")
    suspend fun updateUserRole(userId: Long, role: String): Boolean {
        val usuario = getUsuarioById(userId)
        return if (usuario != null) {
            usuarioDao.updateUserRole(userId, role)
            true
        } else {
            false
        }
    }

    // Update method to update user profile with more comprehensive information
    suspend fun updateUserProfile(userId: Long, newUsername: String, displayName: String? = null, bio: String? = null, rolId: Long? = null): Boolean {
        val usuario = getUsuarioById(userId)
        return if (usuario != null) {
            val updatedUsuario = usuario.copy(
                usuario = newUsername,
                rol_id = rolId ?: usuario.rol_id
            )
            update(updatedUsuario)
            true
        } else {
            false
        }
    }

    suspend fun isStudent(userId: Long): Boolean {
        val usuarioWithRole = getUsuarioWithRoleById(userId)
        return usuarioWithRole?.isEstudiante == true
    }

    suspend fun isAdmin(userId: Long): Boolean {
        val usuarioWithRole = getUsuarioWithRoleById(userId)
        return usuarioWithRole?.isAdmin == true
    }

    // Helper method to check if user is admin by username
    suspend fun isAdminByUsername(username: String): Boolean {
        val usuarioWithRole = getUsuarioWithRoleByUsername(username)
        return usuarioWithRole?.isAdmin == true
    }

    // Add a method to update just the password
    suspend fun updatePassword(userId: Long, password: String): Boolean {
        val usuario = getUsuarioById(userId)
        return if (usuario != null) {
            val passwordToStore = if (password.startsWith("$2a$") || password.startsWith("$2b$") || password.startsWith("$2y$")) {
                password
            } else {
                at.favre.lib.crypto.bcrypt.BCrypt.withDefaults().hashToString(12, password.toCharArray())
            }
            val updatedUsuario = usuario.copy(contrasena = passwordToStore)
            usuarioDao.updateUsuario(updatedUsuario)
            true
        } else {
            false
        }
    }
}