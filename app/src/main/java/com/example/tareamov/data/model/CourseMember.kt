package com.example.tareamov.data.model

/**
 * Representa un miembro del curso (docente o estudiante) en la pestaña Personas.
 * Sigue el principio de responsabilidad única: solo contiene datos del miembro.
 */
data class CourseMember(
    val userId: Long,
    val username: String,
    val avatar: String?,
    val role: Role
) {
    enum class Role { DOCENTE, ESTUDIANTE }
}
