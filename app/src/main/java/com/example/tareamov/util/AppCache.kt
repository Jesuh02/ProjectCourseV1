package com.example.tareamov.util

import com.example.tareamov.data.entity.Course
import com.example.tareamov.data.entity.Notification
import com.example.tareamov.data.entity.Rol
import com.example.tareamov.data.entity.Subject
import com.example.tareamov.data.entity.Usuario
import com.example.tareamov.service.BackendApiService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Centralized in-memory cache with TTL and reactive invalidation via SharedFlow.
 *
 * Every data domain exposes a SharedFlow that fragments/ViewModels can collect
 * to react immediately when a CRUD operation invalidates cached data.
 *
 * Pattern: call invalidateX() after any create/update/delete → the matching
 * SharedFlow emits → observers re-fetch from the network.
 */
object AppCache : SessionManager.UserChangeListener {

    /**
     * Register this cache as a SessionManager listener so it auto-clears
     * whenever the user changes or logs out.
     */
    fun init() {
        SessionManager.addUserChangeListener(this)
    }

    override fun onUserChanged(previousUser: String?, newUser: String?) {
        clearAll()
    }

    override fun onUserLoggedOut(previousUser: String?) {
        clearAll()
    }

    /**
     * Wipe ALL cached data. Called on logout and user switch to prevent
     * stale data from one user leaking into another user's session.
     */
    fun clearAll() {
        coursesEntry = null
        notificationsEntry = null
        unreadCountEntry = null
        profileEntry = null
        subscriberCountEntries.clear()
        certificatesEntry = null
        subscriptionsEntry = null
        rolesEntry = null
        subjectsEntries.clear()
        courseDetailDirtyIds.clear()
        _notificationRefresh.tryEmit(Unit)
        _coursesRefresh.tryEmit(Unit)
        _subjectsRefresh.tryEmit(Unit)
        _courseDetailRefresh.tryEmit(0L)
        _videosRefresh.tryEmit(Unit)
        _adminRefresh.tryEmit(Unit)
    }

    // ── TTLs ─────────────────────────────────────────────────────
    private const val COURSES_TTL_MS = 60_000L       // 1 min (was 2 min)
    private const val NOTIFICATIONS_TTL_MS = 15_000L
    private const val UNREAD_COUNT_TTL_MS = 30_000L
    private const val PROFILE_TTL_MS = 120_000L
    private const val SUBSCRIBER_COUNT_TTL_MS = 120_000L
    private const val CERTIFICATES_TTL_MS = 60_000L
    private const val ROLES_TTL_MS = 300_000L
    private const val SUBJECTS_TTL_MS = 60_000L      // 1 min (was 2 min)
    private const val SUBSCRIPTIONS_TTL_MS = 120_000L  // 2 min

    private data class Entry<T>(val data: T, val timestamp: Long = System.currentTimeMillis()) {
        fun isExpired(ttl: Long) = System.currentTimeMillis() - timestamp > ttl
    }

    // ── Reactive event flows ─────────────────────────────────────
    // Fragments collect these to know when to refetch data from network.

    private val _notificationRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val notificationRefresh = _notificationRefresh.asSharedFlow()

    private val _coursesRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when courses list should be reloaded (after create/update/delete). */
    val coursesRefresh = _coursesRefresh.asSharedFlow()

    private val _subjectsRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when subjects for any course should be reloaded. */
    val subjectsRefresh = _subjectsRefresh.asSharedFlow()

    private val _courseDetailRefresh = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Emits the courseId whose detail (topics/tasks/content) should be reloaded. */
    val courseDetailRefresh = _courseDetailRefresh.asSharedFlow()

    private val _videosRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when the video feed should be reloaded. */
    val videosRefresh = _videosRefresh.asSharedFlow()

    private val _adminRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when admin dashboard data should be reloaded (grading, moderation). */
    val adminRefresh = _adminRefresh.asSharedFlow()

    private val _profileRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emits when the user profile should be reloaded. */
    val profileRefresh = _profileRefresh.asSharedFlow()

    // ── Dirty tracking for course detail ─────────────────────────
    private val courseDetailDirtyIds = mutableSetOf<Long>()

    fun markCourseDetailDirty(courseId: Long) {
        courseDetailDirtyIds.add(courseId)
        _courseDetailRefresh.tryEmit(courseId)
    }

    fun isCourseDetailDirty(courseId: Long): Boolean = courseId in courseDetailDirtyIds

    fun clearCourseDetailDirty(courseId: Long) { courseDetailDirtyIds.remove(courseId) }

    // ── Cached data stores ───────────────────────────────────────

    private var coursesEntry: Entry<List<Course>>? = null
    private var notificationsEntry: Entry<List<Notification>>? = null
    private var unreadCountEntry: Entry<Int?>? = null
    private var profileEntry: Entry<Usuario>? = null
    private val subscriberCountEntries = HashMap<Long, Entry<Long>>()
    private var certificatesEntry: Entry<List<BackendApiService.CertificateItem>>? = null
    private var rolesEntry: Entry<List<Rol>>? = null
    private val subjectsEntries = HashMap<Long, Entry<List<Subject>>>()
    private var subscriptionsEntry: Entry<List<Usuario>>? = null

    // ── Courses ──────────────────────────────────────────────────

    fun getCourses(): List<Course>? {
        val e = coursesEntry ?: return null
        return if (e.isExpired(COURSES_TTL_MS)) null else e.data
    }

    fun putCourses(courses: List<Course>) {
        coursesEntry = Entry(courses)
    }

    fun getCachedCoursesOrStale(): List<Course>? = coursesEntry?.data

    fun invalidateCourses() {
        coursesEntry = null
        _coursesRefresh.tryEmit(Unit)
    }

    /** Returns true if the courses cache is still within its TTL. */
    fun isCoursesFresh(): Boolean {
        val e = coursesEntry ?: return false
        return !e.isExpired(COURSES_TTL_MS)
    }

    // ── Notifications ────────────────────────────────────────────

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

    // ── Unread count ─────────────────────────────────────────────

    fun getUnreadCount(): Int? {
        val e = unreadCountEntry ?: return null
        return if (e.isExpired(UNREAD_COUNT_TTL_MS)) null else e.data
    }

    fun putUnreadCount(count: Int?) { unreadCountEntry = Entry(count) }

    // ── Profile ──────────────────────────────────────────────────

    fun getProfile(): Usuario? {
        val e = profileEntry ?: return null
        return if (e.isExpired(PROFILE_TTL_MS)) null else e.data
    }

    fun getProfileOrStale(): Usuario? = profileEntry?.data

    fun putProfile(user: Usuario) { profileEntry = Entry(user) }

    fun invalidateProfile() {
        profileEntry = null
        _profileRefresh.tryEmit(Unit)
    }

    /** Returns true if the profile cache is still within its TTL. */
    fun isProfileFresh(): Boolean {
        val e = profileEntry ?: return false
        return !e.isExpired(PROFILE_TTL_MS)
    }

    // ── Subscriber counts ────────────────────────────────────────

    fun getSubscriberCount(userId: Long): Long? {
        val e = subscriberCountEntries[userId] ?: return null
        return if (e.isExpired(SUBSCRIBER_COUNT_TTL_MS)) null else e.data
    }

    fun getSubscriberCountOrStale(userId: Long): Long? = subscriberCountEntries[userId]?.data

    fun putSubscriberCount(userId: Long, count: Long) {
        subscriberCountEntries[userId] = Entry(count)
    }

    // ── Subscriptions (subscribed creators) ──────────────────────

    fun getSubscriptions(): List<Usuario>? {
        val e = subscriptionsEntry ?: return null
        return if (e.isExpired(SUBSCRIPTIONS_TTL_MS)) null else e.data
    }

    fun putSubscriptions(users: List<Usuario>) {
        subscriptionsEntry = Entry(users)
    }

    fun invalidateSubscriptions() { subscriptionsEntry = null }

    // ── Certificates ─────────────────────────────────────────────

    fun getCertificates(): List<BackendApiService.CertificateItem>? {
        val e = certificatesEntry ?: return null
        return if (e.isExpired(CERTIFICATES_TTL_MS)) null else e.data
    }

    fun getCertificatesOrStale(): List<BackendApiService.CertificateItem>? = certificatesEntry?.data

    fun putCertificates(certs: List<BackendApiService.CertificateItem>) {
        certificatesEntry = Entry(certs)
    }

    fun invalidateCertificates() { certificatesEntry = null }

    // ── Roles ────────────────────────────────────────────────────

    fun getRoles(): List<Rol>? {
        val e = rolesEntry ?: return null
        return if (e.isExpired(ROLES_TTL_MS)) null else e.data
    }

    fun getRolesOrStale(): List<Rol>? = rolesEntry?.data

    fun putRoles(roles: List<Rol>) { rolesEntry = Entry(roles) }

    fun invalidateRoles() { rolesEntry = null }

    // ── Subjects ─────────────────────────────────────────────────

    fun getSubjects(courseId: Long): List<Subject>? {
        val e = subjectsEntries[courseId] ?: return null
        return if (e.isExpired(SUBJECTS_TTL_MS)) null else e.data
    }

    fun getSubjectsOrStale(courseId: Long): List<Subject>? = subjectsEntries[courseId]?.data

    /** Returns true if subjects were fetched very recently (within 20s), so background re-fetch can be skipped. */
    fun isSubjectsVeryFresh(courseId: Long): Boolean {
        val e = subjectsEntries[courseId] ?: return false
        return System.currentTimeMillis() - e.timestamp < 20_000L
    }

    fun putSubjects(courseId: Long, subjects: List<Subject>) {
        subjectsEntries[courseId] = Entry(subjects)
    }

    fun invalidateSubjects(courseId: Long) {
        subjectsEntries.remove(courseId)
        _subjectsRefresh.tryEmit(Unit)
    }

    // ── Convenience: invalidate everything related to a course ───
    /** Call after topic/task/content CRUD to invalidate course detail + subjects. */
    fun invalidateCourseContent(courseId: Long) {
        markCourseDetailDirty(courseId)
        invalidateSubjects(courseId)
        invalidateCourses()
    }

    /** Signal the video feed to refresh (after video create/update/delete). */
    fun invalidateVideos() {
        _videosRefresh.tryEmit(Unit)
    }

    /** Signal the admin dashboard to refresh (after grading, moderation). */
    fun invalidateAdmin() {
        _adminRefresh.tryEmit(Unit)
    }
}
