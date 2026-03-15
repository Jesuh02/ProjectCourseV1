package com.example.tareamov.util

import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.Notification
import com.example.tareamov.data.entity.Rol
import com.example.tareamov.data.entity.Subject
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AppCache {

    private const val COURSES_TTL_MS = 120_000L
    private const val NOTIFICATIONS_TTL_MS = 15_000L
    private const val UNREAD_COUNT_TTL_MS = 30_000L
    private const val PROFILE_TTL_MS = 120_000L
    private const val SUBSCRIBER_COUNT_TTL_MS = 120_000L
    private const val CERTIFICATES_TTL_MS = 60_000L
    private const val ROLES_TTL_MS = 300_000L
    private const val SUBJECTS_TTL_MS = 120_000L

    private data class Entry<T>(val data: T, val timestamp: Long = System.currentTimeMillis()) {
        fun isExpired(ttl: Long) = System.currentTimeMillis() - timestamp > ttl
    }

    private val _notificationRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notificationRefresh = _notificationRefresh.asSharedFlow()

    private var coursesEntry: Entry<List<Course>>? = null
    private var notificationsEntry: Entry<List<Notification>>? = null
    private var unreadCountEntry: Entry<Int?>? = null
    private var profileEntry: Entry<Usuario>? = null
    private val subscriberCountEntries = HashMap<Long, Entry<Long>>()
    private var certificatesEntry: Entry<List<BackendApiService.CertificateItem>>? = null
    private var rolesEntry: Entry<List<Rol>>? = null
    private val subjectsEntries = HashMap<Long, Entry<List<Subject>>>()

    fun getCourses(): List<Course>? {
        val e = coursesEntry ?: return null
        return if (e.isExpired(COURSES_TTL_MS)) null else e.data
    }

    fun putCourses(courses: List<Course>) {
        coursesEntry = Entry(courses)
    }

    fun getCachedCoursesOrStale(): List<Course>? = coursesEntry?.data

    fun invalidateCourses() { coursesEntry = null }

    fun getNotifications(): List<Notification>? {
        val e = notificationsEntry ?: return null
        return if (e.isExpired(NOTIFICATIONS_TTL_MS)) null else e.data
    }

    fun putNotifications(notifications: List<Notification>) {
        notificationsEntry = Entry(notifications)
        _notificationRefresh.tryEmit(Unit)
    }

    fun getCachedNotificationsOrStale(): List<Notification>? = notificationsEntry?.data

    fun markNotificationRead(id: Long) {
        notificationsEntry = notificationsEntry?.let { e ->
            Entry(e.data.map { if (it.id == id) it.copy(isRead = true) else it }, e.timestamp)
        }
        unreadCountEntry = unreadCountEntry?.let { e ->
            Entry(((e.data ?: 1) - 1).coerceAtLeast(0), e.timestamp)
        }
    }

    fun invalidateNotifications() {
        notificationsEntry = null
        _notificationRefresh.tryEmit(Unit)
    }

    fun requestNotificationRefresh() {
        notificationsEntry = null
        _notificationRefresh.tryEmit(Unit)
    }

    fun getUnreadCount(): Int? {
        val e = unreadCountEntry ?: return null
        return if (e.isExpired(UNREAD_COUNT_TTL_MS)) null else e.data
    }

    fun putUnreadCount(count: Int?) { unreadCountEntry = Entry(count) }

    fun getProfile(): Usuario? {
        val e = profileEntry ?: return null
        return if (e.isExpired(PROFILE_TTL_MS)) null else e.data
    }

    fun getProfileOrStale(): Usuario? = profileEntry?.data

    fun putProfile(user: Usuario) { profileEntry = Entry(user) }

    fun invalidateProfile() { profileEntry = null }

    fun getSubscriberCount(userId: Long): Long? {
        val e = subscriberCountEntries[userId] ?: return null
        return if (e.isExpired(SUBSCRIBER_COUNT_TTL_MS)) null else e.data
    }

    fun getSubscriberCountOrStale(userId: Long): Long? = subscriberCountEntries[userId]?.data

    fun putSubscriberCount(userId: Long, count: Long) {
        subscriberCountEntries[userId] = Entry(count)
    }

    fun getCertificates(): List<BackendApiService.CertificateItem>? {
        val e = certificatesEntry ?: return null
        return if (e.isExpired(CERTIFICATES_TTL_MS)) null else e.data
    }

    fun getCertificatesOrStale(): List<BackendApiService.CertificateItem>? = certificatesEntry?.data

    fun putCertificates(certs: List<BackendApiService.CertificateItem>) {
        certificatesEntry = Entry(certs)
    }

    fun invalidateCertificates() { certificatesEntry = null }

    fun getRoles(): List<Rol>? {
        val e = rolesEntry ?: return null
        return if (e.isExpired(ROLES_TTL_MS)) null else e.data
    }

    fun getRolesOrStale(): List<Rol>? = rolesEntry?.data

    fun putRoles(roles: List<Rol>) { rolesEntry = Entry(roles) }

    fun invalidateRoles() { rolesEntry = null }

    fun getSubjects(courseId: Long): List<Subject>? {
        val e = subjectsEntries[courseId] ?: return null
        return if (e.isExpired(SUBJECTS_TTL_MS)) null else e.data
    }

    fun getSubjectsOrStale(courseId: Long): List<Subject>? = subjectsEntries[courseId]?.data

    fun putSubjects(courseId: Long, subjects: List<Subject>) {
        subjectsEntries[courseId] = Entry(subjects)
    }

    fun invalidateSubjects(courseId: Long) { subjectsEntries.remove(courseId) }
}
