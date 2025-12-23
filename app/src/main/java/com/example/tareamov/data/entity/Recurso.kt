package com.example.tareamov.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "recursos",
    foreignKeys = [
        ForeignKey(
            entity = Recurso::class,
            parentColumns = ["id"],
            childColumns = ["padre_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("padre_id")] // Agregar índice para padreId
)
data class Recurso(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val nombre: String,           // Nombre del recurso/funcionalidad
    val icono: String,           // Nombre del icono o identificador
    val orden: Int,              // Orden de aparición en el menú
    @ColumnInfo(name = "padre_id")
    val padreId: Long? = null,   // ID del recurso padre (null = menú principal)
    val interfaz: String? = null, // Nombre de la interfaz/fragment donde aparece
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Constantes para los iconos que mencionaste
        const val ICONO_DATABASE_ORBIT = "databaseOrbitButton"
        const val ICONO_ADMIN_BUTTON = "goToAdminButton"
        const val ICONO_HOME = "homeButton"
        const val ICONO_EXPLORE = "exploreButton"
        const val ICONO_PROFILE = "profileButton"
        const val ICONO_NOTIFICATIONS = "notificationsButton"
        
        // Constantes para las interfaces/fragments
        const val INTERFAZ_VIDEO_HOME = "VideoHomeFragment"
        const val INTERFAZ_EXPLORE = "ExploreFragment"
        const val INTERFAZ_PROFILE = "ProfileFragment"
        const val INTERFAZ_USER_PROFILE_VIEW = "UserProfileViewFragment"
        const val INTERFAZ_NOTIFICACIONES = "NotificacionesFragment"
        
        // Nombres de recursos
        const val NOMBRE_ADMIN_PANEL = "Panel de Administración"
        const val NOMBRE_DATABASE_ORBIT = "Base de Datos"
        const val NOMBRE_NAVEGACION = "Navegación"
        const val NOMBRE_HOME = "Inicio"
        const val NOMBRE_EXPLORAR = "Explorar"
        const val NOMBRE_PERFIL = "Perfil"
        const val NOMBRE_NOTIFICACIONES = "Notificaciones"
        
        // Métodos para crear recursos por defecto
        fun createNavegacionRecurso(): Recurso {
            return Recurso(
                nombre = NOMBRE_NAVEGACION,
                icono = "navigation_menu",
                orden = 1,
                padreId = null, // Es un menú principal
                interfaz = "BottomNavigation" // Aparece en la navegación inferior
            )
        }
        
        fun createHomeRecurso(navegacionId: Long): Recurso {
            return Recurso(
                nombre = NOMBRE_HOME,
                icono = ICONO_HOME,
                orden = 1,
                padreId = navegacionId, // Es submenú de navegación
                interfaz = INTERFAZ_VIDEO_HOME
            )
        }
        
        fun createExploreRecurso(navegacionId: Long): Recurso {
            return Recurso(
                nombre = NOMBRE_EXPLORAR,
                icono = ICONO_EXPLORE,
                orden = 2,
                padreId = navegacionId,
                interfaz = INTERFAZ_EXPLORE
            )
        }
        
        fun createPerfilRecurso(navegacionId: Long): Recurso {
            return Recurso(
                nombre = NOMBRE_PERFIL,
                icono = ICONO_PROFILE,
                orden = 3,
                padreId = navegacionId,
                interfaz = INTERFAZ_PROFILE
            )
        }
        
        fun createNotificacionesRecurso(navegacionId: Long): Recurso {
            return Recurso(
                nombre = NOMBRE_NOTIFICACIONES,
                icono = ICONO_NOTIFICATIONS,
                orden = 4,
                padreId = navegacionId,
                interfaz = INTERFAZ_NOTIFICACIONES
            )
        }
        
        fun createAdminPanelRecurso(): Recurso {
            return Recurso(
                nombre = NOMBRE_ADMIN_PANEL,
                icono = "admin_panel",
                orden = 2,
                padreId = null, // Es un menú principal
                interfaz = "AdminPanel"
            )
        }
        
        // Recursos específicos para cada interfaz donde aparece goToAdminButton
        fun createAdminButtonVideoHomeRecurso(adminPanelId: Long): Recurso {
            return Recurso(
                nombre = "Botón Admin - Home",
                icono = ICONO_ADMIN_BUTTON,
                orden = 1,
                padreId = adminPanelId,
                interfaz = INTERFAZ_VIDEO_HOME
            )
        }
        
        fun createAdminButtonExploreRecurso(adminPanelId: Long): Recurso {
            return Recurso(
                nombre = "Botón Admin - Explore",
                icono = ICONO_ADMIN_BUTTON,
                orden = 2,
                padreId = adminPanelId,
                interfaz = INTERFAZ_EXPLORE
            )
        }
        
        fun createAdminButtonProfileRecurso(adminPanelId: Long): Recurso {
            return Recurso(
                nombre = "Botón Admin - Profile",
                icono = ICONO_ADMIN_BUTTON,
                orden = 3,
                padreId = adminPanelId,
                interfaz = INTERFAZ_PROFILE
            )
        }
        
        fun createAdminButtonUserProfileRecurso(adminPanelId: Long): Recurso {
            return Recurso(
                nombre = "Botón Admin - User Profile",
                icono = ICONO_ADMIN_BUTTON,
                orden = 4,
                padreId = adminPanelId,
                interfaz = INTERFAZ_USER_PROFILE_VIEW
            )
        }
        
        fun createAdminButtonNotificacionesRecurso(adminPanelId: Long): Recurso {
            return Recurso(
                nombre = "Botón Admin - Notificaciones",
                icono = ICONO_ADMIN_BUTTON,
                orden = 5,
                padreId = adminPanelId,
                interfaz = INTERFAZ_NOTIFICACIONES
            )
        }
        
        fun createDatabaseOrbitRecurso(adminPanelId: Long): Recurso {
            return Recurso(
                nombre = NOMBRE_DATABASE_ORBIT,
                icono = ICONO_DATABASE_ORBIT,
                orden = 6,
                padreId = adminPanelId, // Es submenú del panel de admin
                interfaz = INTERFAZ_VIDEO_HOME // Solo aparece en VideoHomeFragment
            )
        }
        
        // Método para obtener todos los recursos por defecto como lista
        fun getAllDefaultRecursos(): List<() -> Recurso> {
            return listOf(
                { createNavegacionRecurso() },
                { createAdminPanelRecurso() }
            )
        }
        
        // Método para obtener todos los sub-recursos de navegación
        fun getNavegacionSubRecursos(navegacionId: Long): List<Recurso> {
            return listOf(
                createHomeRecurso(navegacionId),
                createExploreRecurso(navegacionId),
                createPerfilRecurso(navegacionId),
                createNotificacionesRecurso(navegacionId)
            )
        }
        
        // Método para obtener todos los sub-recursos de admin
        fun getAdminSubRecursos(adminPanelId: Long): List<Recurso> {
            return listOf(
                createAdminButtonVideoHomeRecurso(adminPanelId),
                createAdminButtonExploreRecurso(adminPanelId),
                createAdminButtonProfileRecurso(adminPanelId),
                createAdminButtonUserProfileRecurso(adminPanelId),
                createAdminButtonNotificacionesRecurso(adminPanelId),
                createDatabaseOrbitRecurso(adminPanelId)
            )
        }
    }
}
