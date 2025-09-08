package com.example.tareamov.util

import com.example.tareamov.data.entity.Recurso
import com.example.tareamov.repository.RecursoRepository
import com.example.tareamov.repository.RolRepository

class PermissionHelper(
    private val recursoRepository: RecursoRepository,
    private val rolRepository: RolRepository
) {
    
    /**
     * Verifica si un usuario tiene acceso a un icono específico en una interfaz específica
     */
    suspend fun canUserAccessIconInInterface(
        username: String, 
        icono: String, 
        interfaz: String
    ): Boolean {
        return try {
            // Obtener el rol del usuario
            val usuario = rolRepository.getUsuarioByUsername(username) ?: return false
            val rolId = usuario.rol_id
            
            // Verificar acceso
            recursoRepository.hasAccessToIconInInterface(rolId, icono, interfaz)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Verifica si un usuario puede ver el botón de admin en una interfaz específica
     */
    suspend fun canUserAccessAdminButtonInInterface(username: String, interfaz: String): Boolean {
        return canUserAccessIconInInterface(username, Recurso.ICONO_ADMIN_BUTTON, interfaz)
    }
    
    /**
     * Verifica si un usuario puede ver el botón de database orbit en VideoHomeFragment
     */
    suspend fun canUserAccessDatabaseOrbitButton(username: String): Boolean {
        return canUserAccessIconInInterface(
            username, 
            Recurso.ICONO_DATABASE_ORBIT, 
            Recurso.INTERFAZ_VIDEO_HOME
        )
    }
    
    /**
     * Obtiene todos los recursos disponibles para un usuario en una interfaz específica
     */
    suspend fun getUserResourcesForInterface(username: String, interfaz: String): List<Recurso> {
        return try {
            val usuario = rolRepository.getUsuarioByUsername(username) ?: return emptyList()
            val rolId = usuario.rol_id
            
            recursoRepository.getRecursosByRolAndInterfaz(rolId, interfaz)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    /**
     * Método de utilidad para verificar permisos por interfaz específica
     */
    companion object {
        
        suspend fun checkAdminButtonVisibility(
            permissionHelper: PermissionHelper,
            username: String,
            fragment: String
        ): Boolean {
            return when (fragment) {
                Recurso.INTERFAZ_VIDEO_HOME,
                Recurso.INTERFAZ_EXPLORE,
                Recurso.INTERFAZ_PROFILE,
                Recurso.INTERFAZ_USER_PROFILE_VIEW,
                Recurso.INTERFAZ_NOTIFICACIONES -> {
                    permissionHelper.canUserAccessAdminButtonInInterface(username, fragment)
                }
                else -> false
            }
        }
        
        suspend fun checkDatabaseOrbitVisibility(
            permissionHelper: PermissionHelper,
            username: String
        ): Boolean {
            return permissionHelper.canUserAccessDatabaseOrbitButton(username)
        }
    }
}
