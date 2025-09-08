package com.example.tareamov.utils

import com.example.tareamov.data.DatabaseInitializer
import com.example.tareamov.data.entity.Recurso
import com.example.tareamov.data.entity.RolRecurso
import com.example.tareamov.repository.RecursoRepository
import com.example.tareamov.repository.RolRepository

/**
 * Clase de utilidad para demostrar cómo poblar las tablas con datos por defecto
 * basados en el companion object de Recurso.kt
 */
class DataInitializationDemo {
    
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
        val estudianteRole = rolRepository.getRolByNombre("estudiante")
        val adminRole = rolRepository.getRolByNombre("admin")
        
        if (estudianteRole != null && adminRole != null) {
            // 3. Inicializar recursos usando DatabaseInitializer
            val databaseInitializer = DatabaseInitializer(recursoRepository)
            databaseInitializer.initializeDefaultData(estudianteRole.id, adminRole.id)
            
            // 4. Verificar la inicialización
            val integrityResult = databaseInitializer.verifyDataIntegrity()
            println("Resultado de inicialización: ${integrityResult.message}")
            println("Total recursos creados: ${integrityResult.totalRecursos}")
            
            // 5. Mostrar ejemplos de consultas
            demonstrateQueries(estudianteRole.id, adminRole.id, recursoRepository)
        }
    }
    
    /**
     * Demuestra cómo consultar los datos inicializados
     */
    private suspend fun demonstrateQueries(
        estudianteRolId: Long, 
        adminRolId: Long, 
        recursoRepository: RecursoRepository
    ) {
        println("\n=== DEMOSTRANDO CONSULTAS ===")
        
        // Recursos disponibles para estudiante
        val recursosEstudiante = recursoRepository.getRecursosByRol(estudianteRolId)
        println("Recursos para estudiante (${recursosEstudiante.size}):")
        recursosEstudiante.forEach { recurso ->
            println("  - ${recurso.nombre} (${recurso.icono}) en ${recurso.interfaz}")
        }
        
        // Recursos disponibles para admin
        val recursosAdmin = recursoRepository.getRecursosByRol(adminRolId)
        println("\nRecursos para admin (${recursosAdmin.size}):")
        recursosAdmin.forEach { recurso ->
            println("  - ${recurso.nombre} (${recurso.icono}) en ${recurso.interfaz}")
        }
        
        // Verificar acceso específico
        val puedeAccederAdminButton = recursoRepository.canAccessAdminButton(estudianteRolId)
        val puedeAccederDatabase = recursoRepository.canAccessDatabaseOrbit(adminRolId)
        
        println("\n=== VERIFICACIONES DE ACCESO ===")
        println("¿Estudiante puede acceder al botón admin? $puedeAccederAdminButton")
        println("¿Admin puede acceder a database orbit? $puedeAccederDatabase")
        
        // Recursos por interfaz específica
        val recursosVideoHome = recursoRepository.getAvailableResourcesForInterface(
            adminRolId, 
            Recurso.INTERFAZ_VIDEO_HOME
        )
        println("\nRecursos en VideoHomeFragment para admin (${recursosVideoHome.size}):")
        recursosVideoHome.forEach { recurso ->
            println("  - ${recurso.nombre} (${recurso.icono})")
        }
    }
    
    /**
     * Ejemplo de cómo crear recursos personalizados usando el companion object
     */
    suspend fun createCustomResources(recursoRepository: RecursoRepository) {
        println("\n=== CREANDO RECURSOS PERSONALIZADOS ===")
        
        // Crear recurso principal personalizado
        val customMainResource = Recurso(
            nombre = "Herramientas",
            icono = "tools_icon",
            orden = 3,
            padreId = null,
            interfaz = "ToolsPanel"
        )
        
        val customMainId = recursoRepository.insertRecurso(customMainResource)
        println("Recurso principal personalizado creado con ID: $customMainId")
        
        // Crear sub-recursos usando métodos del companion object como base
        val customSubResource = Recurso(
            nombre = "Calculadora",
            icono = "calculator_icon",
            orden = 1,
            padreId = customMainId,
            interfaz = Recurso.INTERFAZ_VIDEO_HOME
        )
        
        val customSubId = recursoRepository.insertRecurso(customSubResource)
        println("Sub-recurso personalizado creado con ID: $customSubId")
    }
    
    /**
     * Ejemplo de cómo verificar el estado actual de las tablas
     */
    suspend fun verifyCurrentState(recursoRepository: RecursoRepository) {
        println("\n=== ESTADO ACTUAL DE LAS TABLAS ===")
        
        // Obtener todos los recursos
        val allRecursos = recursoRepository.getAllRecursos()
        println("Total recursos en base de datos: ${allRecursos.size}")
        
        // Mostrar estructura jerárquica
        val recursosPrincipales = recursoRepository.getRecursosPrincipales()
        println("\nEstructura jerárquica:")
        
        recursosPrincipales.forEach { principal ->
            println("📁 ${principal.nombre} (ID: ${principal.id})")
            
            val subRecursos = recursoRepository.getSubRecursos(principal.id)
            subRecursos.forEach { sub ->
                println("  📄 ${sub.nombre} -> ${sub.interfaz}")
            }
        }
        
        // Mostrar constantes disponibles
        println("\n=== CONSTANTES DISPONIBLES ===")
        println("Iconos disponibles:")
        println("  - ${Recurso.ICONO_HOME}")
        println("  - ${Recurso.ICONO_EXPLORE}")
        println("  - ${Recurso.ICONO_PROFILE}")
        println("  - ${Recurso.ICONO_NOTIFICATIONS}")
        println("  - ${Recurso.ICONO_ADMIN_BUTTON}")
        println("  - ${Recurso.ICONO_DATABASE_ORBIT}")
        
        println("\nInterfaces disponibles:")
        println("  - ${Recurso.INTERFAZ_VIDEO_HOME}")
        println("  - ${Recurso.INTERFAZ_EXPLORE}")
        println("  - ${Recurso.INTERFAZ_PROFILE}")
        println("  - ${Recurso.INTERFAZ_USER_PROFILE_VIEW}")
        println("  - ${Recurso.INTERFAZ_NOTIFICACIONES}")
    }
}
