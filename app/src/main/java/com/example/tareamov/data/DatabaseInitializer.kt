package com.example.tareamov.data

import com.example.tareamov.data.entity.Recurso
import com.example.tareamov.data.entity.RolRecurso
import com.example.tareamov.repository.RecursoRepository

/**
 * Clase utilitaria para inicializar la base de datos con datos por defecto
 */
class DatabaseInitializer(
    private val recursoRepository: RecursoRepository
) {
    
    /**
     * Inicializa todos los datos por defecto en las tablas recursos y rol_recursos
     */
    suspend fun initializeDefaultData(rolEstudianteId: Long, rolAdminId: Long) {
        initializeRecursos()
        initializeRolRecursoRelations(rolEstudianteId, rolAdminId)
    }
    
    /**
     * Inicializa la tabla recursos con datos por defecto basados en el companion object
     */
    private suspend fun initializeRecursos() {
        // Verificar si ya existen recursos
        val existingRecursos = recursoRepository.getAllRecursos()
        if (existingRecursos.isNotEmpty()) {
            return // Ya están inicializados
        }
        
        // Crear recursos principales
        val navegacionId = recursoRepository.insertRecurso(Recurso.createNavegacionRecurso())
        val adminPanelId = recursoRepository.insertRecurso(Recurso.createAdminPanelRecurso())
        
        // Crear sub-recursos de navegación usando el método helper
        val navegacionSubRecursos = Recurso.getNavegacionSubRecursos(navegacionId)
        navegacionSubRecursos.forEach { recurso ->
            recursoRepository.insertRecurso(recurso)
        }
        
        // Crear sub-recursos de admin usando el método helper
        val adminSubRecursos = Recurso.getAdminSubRecursos(adminPanelId)
        adminSubRecursos.forEach { recurso ->
            recursoRepository.insertRecurso(recurso)
        }
    }
    
    /**
     * Inicializa la tabla rol_recursos con relaciones por defecto
     */
    private suspend fun initializeRolRecursoRelations(rolEstudianteId: Long, rolAdminId: Long) {
        // Verificar si ya existen relaciones
        val existingRelations = recursoRepository.getRecursosByRol(rolEstudianteId)
        if (existingRelations.isNotEmpty()) {
            return // Ya están inicializadas
        }
        
        // Obtener todos los recursos después de la inicialización
        val allRecursos = recursoRepository.getAllRecursos()
        
        // Configurar permisos para estudiantes
        initializeEstudiantePermissions(rolEstudianteId, allRecursos)
        
        // Configurar permisos para administradores
        initializeAdminPermissions(rolAdminId, allRecursos)
    }
    
    /**
     * Configura permisos para el rol estudiante
     */
    private suspend fun initializeEstudiantePermissions(rolEstudianteId: Long, allRecursos: List<Recurso>) {
        val estudianteRelations = mutableListOf<RolRecurso>()
        
        // Dar acceso a la navegación principal
        val navegacionRecurso = allRecursos.find { it.nombre == Recurso.NOMBRE_NAVEGACION }
        navegacionRecurso?.let { 
            estudianteRelations.add(RolRecurso(rolEstudianteId, it.id))
        }
        
        // Dar acceso a todas las opciones de navegación básica
        val navegacionBasica = allRecursos.filter { recurso ->
            recurso.interfaz in listOf(
                Recurso.INTERFAZ_VIDEO_HOME, 
                Recurso.INTERFAZ_EXPLORE, 
                Recurso.INTERFAZ_PROFILE, 
                Recurso.INTERFAZ_NOTIFICACIONES
            ) && !recurso.icono.contains("admin", ignoreCase = true)
        }
        
        navegacionBasica.forEach { recurso ->
            estudianteRelations.add(RolRecurso(rolEstudianteId, recurso.id))
        }
        
        // Insertar permisos de estudiante
        if (estudianteRelations.isNotEmpty()) {
            recursoRepository.insertRolRecursos(estudianteRelations)
        }
    }
    
    /**
     * Configura permisos para el rol administrador (acceso completo)
     */
    private suspend fun initializeAdminPermissions(rolAdminId: Long, allRecursos: List<Recurso>) {
        val adminRelations = allRecursos.map { recurso ->
            RolRecurso(rolAdminId, recurso.id)
        }
        
        // Insertar permisos de administrador
        if (adminRelations.isNotEmpty()) {
            recursoRepository.insertRolRecursos(adminRelations)
        }
    }
    
    /**
     * Método para verificar si las tablas están correctamente pobladas
     */
    suspend fun verifyDataIntegrity(): DatabaseIntegrityResult {
        val recursos = recursoRepository.getAllRecursos()
        val recursosNavegacion = recursos.filter { it.nombre == Recurso.NOMBRE_NAVEGACION }
        val recursosAdmin = recursos.filter { it.nombre == Recurso.NOMBRE_ADMIN_PANEL }
        
        val hasNavigation = recursosNavegacion.isNotEmpty()
        val hasAdminPanel = recursosAdmin.isNotEmpty()
        val hasSubRecursos = recursos.size > 2 // Debe tener más que solo los recursos principales
        
        return DatabaseIntegrityResult(
            hasRecursos = recursos.isNotEmpty(),
            hasNavigation = hasNavigation,
            hasAdminPanel = hasAdminPanel,
            hasSubRecursos = hasSubRecursos,
            totalRecursos = recursos.size,
            message = when {
                !hasNavigation -> "Falta recurso de navegación"
                !hasAdminPanel -> "Falta recurso de panel admin"
                !hasSubRecursos -> "Faltan sub-recursos"
                else -> "Base de datos inicializada correctamente"
            }
        )
    }
}

/**
 * Resultado de la verificación de integridad de la base de datos
 */
data class DatabaseIntegrityResult(
    val hasRecursos: Boolean,
    val hasNavigation: Boolean,
    val hasAdminPanel: Boolean,
    val hasSubRecursos: Boolean,
    val totalRecursos: Int,
    val message: String
)
