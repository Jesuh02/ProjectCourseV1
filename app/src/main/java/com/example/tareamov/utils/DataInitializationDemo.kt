package com.example.tareamov.utils

import android.util.Log
import com.example.tareamov.data.DatabaseInitializer
import com.example.tareamov.data.entity.Recurso
import com.example.tareamov.data.entity.RolRecurso
import com.example.tareamov.repository.RecursoRepository
import com.example.tareamov.repository.RolRepository

/**
 * Demo class for initializing database data in a controlled manner
 */
class DataInitializationDemo {
    companion object {
    suspend fun initializeDefaultData(
            rolRepository: RolRepository,
            recursoRepository: RecursoRepository
        ) {
            // 1. Primero inicializar roles si no existen
            rolRepository.initializeDefaultRoles()
            
            // 2. Obtener los roles por defecto
            val usuarioRole = rolRepository.getRolByNombre("usuario")
            val adminRole = rolRepository.getRolByNombre("admin")
            
            if (usuarioRole != null && adminRole != null) {
                // 3. Inicializar recursos usando DatabaseInitializer
                val databaseInitializer = DatabaseInitializer(recursoRepository)
                databaseInitializer.initializeDefaultData(usuarioRole.id, adminRole.id)
                
                // 4. Verificar la inicialización
                val integrityResult = databaseInitializer.verifyDataIntegrity()
                Log.i("DataInitializationDemo", "Database recursos initialization: ${integrityResult.message}")
                Log.i("DataInitializationDemo", "Total recursos created: ${integrityResult.totalRecursos}")
            } else {
                Log.w("DataInitializationDemo", "Could not find default roles for recursos initialization")
            }
        }
    }
    
    /**
     * Ejemplo de uso del sistema de inicialización de datos
     */
    suspend fun demonstrateDataInitialization(
        rolRepository: RolRepository,
        recursoRepository: RecursoRepository
    ) {
        // 1. Primero inicializar roles si no existen
        rolRepository.initializeDefaultRoles()
        
        // 2. Obtener los roles por defecto
        val usuarioRole = rolRepository.getRolByNombre("usuario")
        val adminRole = rolRepository.getRolByNombre("admin")
        
        if (usuarioRole != null && adminRole != null) {
            // 3. Inicializar recursos usando DatabaseInitializer
            val databaseInitializer = DatabaseInitializer(recursoRepository)
            databaseInitializer.initializeDefaultData(usuarioRole.id, adminRole.id)
            
            // 4. Verificar la inicialización
            val integrityResult = databaseInitializer.verifyDataIntegrity()
            Log.i("DataInitializationDemo", "Database recursos initialization: ${integrityResult.message}")
            Log.i("DataInitializationDemo", "Total recursos created: ${integrityResult.totalRecursos}")
        } else {
            Log.w("DataInitializationDemo", "Could not find default roles for recursos initialization")
        }
    }
    
    /**
     * Demuestra cómo crear recursos personalizados usando las factory methods
     */
    suspend fun demonstrateCustomResourceCreation(
        rolRepository: RolRepository,
        recursoRepository: RecursoRepository
    ) {
        // Obtener roles
        val usuarioRole = rolRepository.getRolByNombre("usuario")
        val adminRole = rolRepository.getRolByNombre("admin")
        
        if (usuarioRole == null || adminRole == null) {
            Log.e("DataInitializationDemo", "Roles not found")
            return
        }
        
        // Crear algunos recursos personalizados
        val customNavegacion = Recurso.createNavegacionRecurso()
        val navegacionId = recursoRepository.insertRecurso(customNavegacion)
        
        // Crear sub-recursos de navegación
        val homeRecurso = Recurso.createHomeRecurso(navegacionId)
        val exploreRecurso = Recurso.createExploreRecurso(navegacionId)
        val perfilRecurso = Recurso.createPerfilRecurso(navegacionId)
        
        val homeId = recursoRepository.insertRecurso(homeRecurso)
        val exploreId = recursoRepository.insertRecurso(exploreRecurso)
        val perfilId = recursoRepository.insertRecurso(perfilRecurso)
        
        // Asignar estos recursos al rol usuario
        val rolRecursos = listOf(
            RolRecurso(usuarioRole.id, navegacionId),
            RolRecurso(usuarioRole.id, homeId),
            RolRecurso(usuarioRole.id, exploreId),
            RolRecurso(usuarioRole.id, perfilId)
        )
        
    // Insertar las relaciones rol-recurso en lote
    recursoRepository.insertRolRecursos(rolRecursos)
        
        Log.i("DataInitializationDemo", "Custom resources created and assigned to usuario role")
    }
    
    /**
     * Demuestra cómo crear recursos de administración
     */
    suspend fun demonstrateAdminResourceCreation(
        rolRepository: RolRepository,
        recursoRepository: RecursoRepository
    ) {
        // Obtener rol admin
        val adminRole = rolRepository.getRolByNombre("admin")
        
        if (adminRole == null) {
            Log.e("DataInitializationDemo", "Admin role not found")
            return
        }
        
        // Crear panel de administración
        val adminPanel = Recurso.createAdminPanelRecurso()
        val adminPanelId = recursoRepository.insertRecurso(adminPanel)
        
        // Crear recursos específicos de admin
        val adminButtons = Recurso.getAdminSubRecursos(adminPanelId)
        val adminButtonIds = mutableListOf<Long>()
        
        adminButtons.forEach { adminButton ->
            val id = recursoRepository.insertRecurso(adminButton)
            adminButtonIds.add(id)
        }
        
        // Asignar panel de admin y sus sub-recursos al rol admin
        val adminRolRecursos = mutableListOf<RolRecurso>()
        adminRolRecursos.add(RolRecurso(adminRole.id, adminPanelId))
        
        adminButtonIds.forEach { buttonId ->
            adminRolRecursos.add(RolRecurso(adminRole.id, buttonId))
        }
        
    // Insertar las relaciones rol-recurso en lote
    recursoRepository.insertRolRecursos(adminRolRecursos)
        
        Log.i("DataInitializationDemo", "Admin resources created and assigned to admin role")
    }
    
    /**
     * Demuestra cómo verificar la integridad de los datos
     */
    suspend fun demonstrateDataIntegrityCheck(
        recursoRepository: RecursoRepository
    ) {
        val databaseInitializer = DatabaseInitializer(recursoRepository)
        val integrityResult = databaseInitializer.verifyDataIntegrity()
        
    Log.i("DataInitializationDemo", "=== DATA INTEGRITY CHECK ===")
    Log.i("DataInitializationDemo", "Status: ${integrityResult.message}")
    Log.i("DataInitializationDemo", "Total recursos: ${integrityResult.totalRecursos}")
    Log.i("DataInitializationDemo", "Has navigation: ${integrityResult.hasNavigation}")
    Log.i("DataInitializationDemo", "Has admin panel: ${integrityResult.hasAdminPanel}")
    Log.i("DataInitializationDemo", "Has sub-recursos: ${integrityResult.hasSubRecursos}")
    Log.i("DataInitializationDemo", "==========================================")
    }
}
