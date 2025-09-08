package com.example.tareamov.repository

import com.example.tareamov.data.dao.RecursoDao
import com.example.tareamov.data.dao.RolRecursoDao
import com.example.tareamov.data.entity.Recurso
import com.example.tareamov.data.entity.RolRecurso

class RecursoRepository(
    private val recursoDao: RecursoDao,
    private val rolRecursoDao: RolRecursoDao
) {
    
    // Métodos para recursos
    suspend fun insertRecurso(recurso: Recurso): Long {
        return recursoDao.insertRecurso(recurso)
    }
    
    suspend fun getAllRecursos(): List<Recurso> {
        return recursoDao.getAllRecursos()
    }
    
    suspend fun getRecursosPrincipales(): List<Recurso> {
        return recursoDao.getRecursosPrincipales()
    }
    
    suspend fun getSubRecursos(padreId: Long): List<Recurso> {
        return recursoDao.getSubRecursos(padreId)
    }
    
    suspend fun getRecursoByIcono(icono: String): Recurso? {
        return recursoDao.getRecursoByIcono(icono)
    }
    
    // Métodos para verificar acceso por rol
    suspend fun getRecursosByRol(rolId: Long): List<Recurso> {
        return rolRecursoDao.getRecursosByRol(rolId)
    }
    
    suspend fun getRecursosPrincipalesByRol(rolId: Long): List<Recurso> {
        return rolRecursoDao.getRecursosPrincipalesByRol(rolId)
    }
    
    suspend fun getSubRecursosByRol(rolId: Long, padreId: Long): List<Recurso> {
        return rolRecursoDao.getSubRecursosByRol(rolId, padreId)
    }
    
    suspend fun hasAccess(rolId: Long, recursoId: Long): Boolean {
        return rolRecursoDao.hasAccess(rolId, recursoId)
    }
    
    suspend fun hasAccessToIcon(rolId: Long, icono: String): Boolean {
        return rolRecursoDao.hasAccessToIcon(rolId, icono)
    }
    
    suspend fun hasAccessToIconInInterface(rolId: Long, icono: String, interfaz: String): Boolean {
        return rolRecursoDao.hasAccessToIconInInterface(rolId, icono, interfaz)
    }
    
    suspend fun getRecursosByRolAndInterfaz(rolId: Long, interfaz: String): List<Recurso> {
        return rolRecursoDao.getRecursosByRolAndInterfaz(rolId, interfaz)
    }
    
    suspend fun getRecursosByInterfaz(interfaz: String): List<Recurso> {
        return recursoDao.getRecursosByInterfaz(interfaz)
    }
    
    // Métodos para asignar recursos a roles
    suspend fun assignRecursoToRol(rolId: Long, recursoId: Long) {
        rolRecursoDao.insertRolRecurso(RolRecurso(rolId, recursoId))
    }
    
    suspend fun insertRolRecursos(rolRecursos: List<RolRecurso>) {
        rolRecursoDao.insertRolRecursos(rolRecursos)
    }
    
    suspend fun removeRecursoFromRol(rolId: Long, recursoId: Long) {
        rolRecursoDao.deleteRolRecurso(RolRecurso(rolId, recursoId))
    }
    
    // Métodos de utilidad para verificar acceso a funcionalidades específicas
    suspend fun canAccessAdminButton(rolId: Long): Boolean {
        return hasAccessToIcon(rolId, Recurso.ICONO_ADMIN_BUTTON)
    }
    
    suspend fun canAccessDatabaseOrbit(rolId: Long): Boolean {
        return hasAccessToIcon(rolId, Recurso.ICONO_DATABASE_ORBIT)
    }
    
    // Método para inicializar recursos por defecto (llamado desde AppDatabase)
    suspend fun initializeDefaultRecursos() {
        // Verificar si ya existen recursos
        val existingRecursos = getAllRecursos()
        if (existingRecursos.isNotEmpty()) {
            return // Ya están inicializados
        }
        
        // Crear recursos principales
        val navegacionId = insertRecurso(Recurso.createNavegacionRecurso())
        val adminPanelId = insertRecurso(Recurso.createAdminPanelRecurso())
        
        // Crear sub-recursos de navegación
        insertRecurso(Recurso.createHomeRecurso(navegacionId))
        insertRecurso(Recurso.createExploreRecurso(navegacionId))
        insertRecurso(Recurso.createPerfilRecurso(navegacionId))
        insertRecurso(Recurso.createNotificacionesRecurso(navegacionId))
        
        // Crear sub-recursos de admin para cada interfaz
        insertRecurso(Recurso.createAdminButtonVideoHomeRecurso(adminPanelId))
        insertRecurso(Recurso.createAdminButtonExploreRecurso(adminPanelId))
        insertRecurso(Recurso.createAdminButtonProfileRecurso(adminPanelId))
        insertRecurso(Recurso.createAdminButtonUserProfileRecurso(adminPanelId))
        insertRecurso(Recurso.createAdminButtonNotificacionesRecurso(adminPanelId))
        insertRecurso(Recurso.createDatabaseOrbitRecurso(adminPanelId))
    }
    
    // Método para inicializar relaciones rol-recurso por defecto
    suspend fun initializeDefaultRolRecursoRelations(rolEstudianteId: Long, rolAdminId: Long) {
        // Verificar si ya existen relaciones
        val existingRelations = getRecursosByRol(rolEstudianteId)
        if (existingRelations.isNotEmpty()) {
            return // Ya están inicializadas
        }
        
        // Obtener todos los recursos
        val allRecursos = getAllRecursos()
        val navegacionRecursos = allRecursos.filter { it.padreId != null && it.interfaz in listOf(
            Recurso.INTERFAZ_VIDEO_HOME, 
            Recurso.INTERFAZ_EXPLORE, 
            Recurso.INTERFAZ_PROFILE, 
            Recurso.INTERFAZ_NOTIFICACIONES
        )}
        
        // Recursos para estudiantes (solo navegación básica)
        val estudianteRelations = mutableListOf<RolRecurso>()
        
        // Dar acceso a la navegación principal
        val navegacionRecurso = allRecursos.find { it.nombre == Recurso.NOMBRE_NAVEGACION }
        navegacionRecurso?.let { 
            estudianteRelations.add(RolRecurso(rolEstudianteId, it.id))
        }
        
        // Dar acceso a todas las opciones de navegación
        navegacionRecursos.forEach { recurso ->
            estudianteRelations.add(RolRecurso(rolEstudianteId, recurso.id))
        }
        
        // Recursos para administradores (acceso completo)
        val adminRelations = mutableListOf<RolRecurso>()
        
        // Los admins tienen acceso a todo
        allRecursos.forEach { recurso ->
            adminRelations.add(RolRecurso(rolAdminId, recurso.id))
        }
        
        // Insertar todas las relaciones
        insertRolRecursos(estudianteRelations)
        insertRolRecursos(adminRelations)
    }
    
    // Método para poblar ambas tablas con datos por defecto
    suspend fun initializeAllDefaultData(rolEstudianteId: Long, rolAdminId: Long) {
        initializeDefaultRecursos()
        initializeDefaultRolRecursoRelations(rolEstudianteId, rolAdminId)
    }
    
    // Método para obtener recursos disponibles por rol e interfaz específica
    suspend fun getAvailableResourcesForInterface(rolId: Long, interfaz: String): List<Recurso> {
        return rolRecursoDao.getRecursosByRolAndInterfaz(rolId, interfaz)
    }
    
    // Método para verificar si un rol puede acceder a un botón específico en una interfaz específica
    suspend fun canAccessButtonInInterface(rolId: Long, icono: String, interfaz: String): Boolean {
        return rolRecursoDao.hasAccessToIconInInterface(rolId, icono, interfaz)
    }
}
