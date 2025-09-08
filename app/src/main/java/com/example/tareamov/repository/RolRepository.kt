package com.example.tareamov.repository

import com.example.tareamov.data.dao.RolDao
import com.example.tareamov.data.dao.UsuarioDao
import com.example.tareamov.data.entity.Rol
import com.example.tareamov.data.entity.Usuario

class RolRepository(
    private val rolDao: RolDao,
    private val usuarioDao: UsuarioDao
) {
    
    suspend fun getRolById(id: Long): Rol? {
        return rolDao.getRolById(id)
    }
    
    suspend fun getRolByNombre(nombre: String): Rol? {
        return rolDao.getRolByNombre(nombre)
    }
    
    suspend fun getDefaultRol(): Rol? {
        return rolDao.getDefaultRol()
    }
    
    suspend fun getAllRoles(): List<Rol> {
        return rolDao.getAllRoles()
    }
    
    suspend fun insertRol(rol: Rol): Long {
        return rolDao.insertRol(rol)
    }
    
    suspend fun insertRoles(roles: List<Rol>) {
        rolDao.insertRoles(roles)
    }
    
    suspend fun updateRol(rol: Rol) {
        rolDao.updateRol(rol)
    }
    
    suspend fun deleteRol(id: Long) {
        rolDao.deleteRol(id)
    }
    
    suspend fun getRoleCount(): Int {
        return rolDao.getRoleCount()
    }
    
    // Métodos para usuarios
    suspend fun getUsuarioByUsername(username: String): Usuario? {
        return usuarioDao.getUsuarioByUsername(username)
    }

    suspend fun initializeDefaultRoles() {
        val count = getRoleCount()
        if (count == 0) {
            val defaultRoles = listOf(
                Rol.createUsuarioRole(),
                Rol.createAdminRole()
            )
            insertRoles(defaultRoles)
        }
    }
}
