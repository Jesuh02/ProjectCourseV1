package com.example.tareamov.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.data.entity.Course
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import com.example.tareamov.service.BackendApiService
import com.example.tareamov.service.ApiResult

class CourseAdapter(
    private val context: Context,
    private var courses: List<Course>,
    private val onCourseClickListener: (Course) -> Unit,
    private val currentUsername: String? = null, // Current logged-in user for permission checks
    private val onSubscriptionClickListener: ((Course, Boolean) -> Unit)? = null, // Subscription callback
    private val onEditClickListener: ((Course) -> Unit)? = null, // Edit callback
    private val onDeleteClickListener: ((Course) -> Unit)? = null, // Delete callback
    private val onEnrollClickListener: ((Course) -> Unit)? = null, // Enrollment callback
    private val onCreatorClickListener: ((String) -> Unit)? = null, // Creator profile callback
    private val onPaymentClickListener: ((Course) -> Unit)? = null, // Payment callback
    private val subscriptionStatus: Map<Long, Boolean> = emptyMap(), // Subscription status map
    private val showMoreOptions: Boolean = true, // Whether to show the 3-dot menu
    private val hasAdminRole: Boolean = false, // Whether current user has role 3 (admin)
    private val onInfoClickListener: ((Course) -> Unit)? = null, // Info button callback
) : RecyclerView.Adapter<CourseAdapter.CourseViewHolder>() {

    // Cache current user's id to avoid blocking lookups during bind
    private var currentUserIdCached: Long? = null
    private val collaboratorCourseIds = java.util.Collections.synchronizedSet(mutableSetOf<Long>())
    private val collaboratorAccessCache = java.util.concurrent.ConcurrentHashMap<Long, Boolean>()
    private val enrollmentCountCache = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    fun setCurrentUserId(userId: Long?) {
        if (currentUserIdCached == userId) return
        currentUserIdCached = userId
        collaboratorCourseIds.clear()
        collaboratorAccessCache.clear()
        notifyItemRangeChanged(0, courses.size)
    }

    // Cache for creator usernames by userId to reduce repeated network calls
    private val creatorUsernameCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    // Cache for creator avatars by userId
    private val creatorAvatarCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    init {
        setHasStableIds(true)
        // Detach from caller's mutable list so external mutations
        // don't silently corrupt DiffUtil's old-list snapshot.
        courses = ArrayList(courses)
    }

    override fun getItemId(position: Int): Long = courses[position].id

    private fun bindMoreOptionsVisibility(holder: CourseViewHolder, course: Course, canModify: Boolean) {
        holder.moreOptionsButton?.visibility = if (showMoreOptions && canModify) View.VISIBLE else View.GONE
        if (showMoreOptions && canModify) {
            holder.moreOptionsButton?.setOnClickListener { view -> showPopupMenu(view, course) }
        } else {
            holder.moreOptionsButton?.setOnClickListener(null)
        }
    }

    private fun resolveBackendMediaUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (
            trimmed.startsWith("http", ignoreCase = true) ||
            trimmed.startsWith("content://", ignoreCase = true) ||
            trimmed.startsWith("file://", ignoreCase = true)
        ) {
            return trimmed
        }

        val normalized = trimmed.removePrefix("/")
        return when {
            normalized.startsWith("api/v1/public/files/") -> BackendApiService.baseUrl + "/" + normalized
            normalized.startsWith("public/files/") -> BackendApiService.baseUrl + "/api/v1/" + normalized
            else -> BackendApiService.buildProxyFileUrl(normalized)
        }
    }

    private fun bindCreatorAvatar(holder: CourseViewHolder, avatarUrl: String?) {
        val resolvedAvatarUrl = resolveBackendMediaUrl(avatarUrl)
        if (resolvedAvatarUrl.isNullOrEmpty()) {
            holder.creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
            return
        }

        Glide.with(context)
            .load(resolvedAvatarUrl)
            .placeholder(R.drawable.default_avatar)
            .error(R.drawable.default_avatar)
            .fallback(R.drawable.default_avatar)
            .into(holder.creatorAvatarImageView)
    }

    class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var currentJob: Job? = null
        val thumbnailImageView: ImageView = itemView.findViewById(R.id.courseThumbnailImageView)
        val titleTextView: TextView = itemView.findViewById(R.id.courseTitleTextView)
        val descriptionTextView: TextView = itemView.findViewById(R.id.courseDescriptionTextView)
        val creatorTextView: TextView = itemView.findViewById(R.id.courseCreatorTextView)
        val categoryTextView: TextView = itemView.findViewById(R.id.courseCategoryTextView)
        val studentsTextView: TextView? = findOptionalTextView(itemView, "courseStudentsTextView", "courseEnrollmentTextView")
        
        val priceTextView: TextView = itemView.findViewById(R.id.coursePriceTextView)
        val originalPriceTextView: TextView = itemView.findViewById(R.id.originalPriceTextView)
        val premiumBadge: View = itemView.findViewById(R.id.premiumBadge)
        val overlayText: TextView = itemView.findViewById(R.id.overlayText)
        // Video Preview
        val videoPreview: android.widget.VideoView? = itemView.findViewById(R.id.courseVideoPreview)

        // Subscription elements
        val creatorAvatarImageView: de.hdodenhof.circleimageview.CircleImageView = itemView.findViewById(R.id.creatorAvatarImageView)
        val subscriberCountTextView: TextView = itemView.findViewById(R.id.subscriberCountTextView)
        val subscribeButton: Button = itemView.findViewById(R.id.subscribeButton)
        // Creator info container
        val creatorInfoContainer: android.widget.LinearLayout? = itemView.findViewById(R.id.creatorInfoContainer)
        // Enrollment elements
        val enrollButtonContainer: android.widget.LinearLayout? = itemView.findViewById(R.id.enrollButtonContainer)
        val enrollButton: Button? = itemView.findViewById(R.id.enrollButton)
        // Enrolled status elements
        val enrolledStatusContainer: android.widget.LinearLayout? = itemView.findViewById(R.id.enrolledStatusContainer)
        // Optional owner status container (may not exist in this layout)
        val ownerStatusContainer: android.widget.LinearLayout? = itemView.findViewById(R.id.ownerStatusContainer)
        // CRUD action elements - moreOptionsButton is now directly in the layout
        val moreOptionsButton: android.widget.ImageButton? = itemView.findViewById(R.id.moreOptionsButton)
        // Info button
        val infoButton: android.widget.ImageButton? = itemView.findViewById(R.id.infoButton)

        fun playPreview(videoUri: String) {
            if (videoPreview == null) return
            try {
                videoPreview.visibility = View.VISIBLE
                thumbnailImageView.visibility = View.INVISIBLE
                
                val uri = android.net.Uri.parse(videoUri)
                videoPreview.setVideoURI(uri)
                
                videoPreview.setOnPreparedListener { mp ->
                    mp.setVolume(0f, 0f) // Mute
                    mp.isLooping = true
                    videoPreview.start()
                }
                videoPreview.setOnErrorListener { _, _, _ ->
                    stopPreview()
                    true
                }
            } catch (e: Exception) {
                stopPreview()
            }
        }

        fun stopPreview() {
            if (videoPreview == null) return
            try {
                if (videoPreview.isPlaying) {
                    videoPreview.stopPlayback()
                }
                videoPreview.visibility = View.GONE
                thumbnailImageView.visibility = View.VISIBLE
            } catch (e: Exception) {
                // Ignore
            }
        }

        private fun findOptionalTextView(root: View, vararg idNames: String): TextView? {
            for (idName in idNames) {
                val resourceId = root.context.resources.getIdentifier(idName, "id", root.context.packageName)
                if (resourceId != 0) {
                    root.findViewById<TextView?>(resourceId)?.let { return it }
                }
            }
            return null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course_card, parent, false)
        
        // Apply dark mode styling
        applyDarkModeTheme(view)
        
        return CourseViewHolder(view)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        // Cancel all pending network work from a previous bind of this ViewHolder
        holder.currentJob?.cancel()
        val bindJob = SupervisorJob()
        holder.currentJob = bindJob

        val course = courses[position]

        // ---- INSTANT: Static data renders immediately (no network) ----
        holder.titleTextView.text = course.title
        holder.descriptionTextView.text = course.description
        holder.categoryTextView.text = if (course.category.isNullOrBlank()) "Programación" else course.category

        val isOwner = currentUserIdCached != null && currentUserIdCached == course.creatorUserId
        val isCollaborator = collaboratorCourseIds.contains(course.id)
        // Solo rol 3 (admin) puede ver los 3 puntos de modificar; rol 2 no tiene acceso aunque sea propietario
        val canModify = hasAdminRole

        // Reset dynamic UI to defaults
        holder.enrollButtonContainer?.visibility = View.GONE
        holder.enrollButton?.visibility = View.GONE
        holder.enrolledStatusContainer?.visibility = View.GONE
        bindMoreOptionsVisibility(holder, course, canModify)

        // Price (from local course object - no network needed)
        if (course.isPremium && course.price > 0) {
            holder.priceTextView.text = "$${String.format("%.2f", course.price)}"
            holder.originalPriceTextView.visibility = View.GONE
            holder.premiumBadge.visibility = View.VISIBLE
        } else {
            holder.priceTextView.text = "Gratis"
            holder.originalPriceTextView.visibility = View.GONE
            holder.premiumBadge.visibility = View.GONE
        }

        // Thumbnail (Glide handles async loading internally)
        loadCourseThumbnail(holder, course)
        applyDarkModeTextColors(holder)

        // Wire info button
        holder.infoButton?.setOnClickListener { onInfoClickListener?.invoke(course) }

        // Show cached creator info instantly
        if (isOwner) {
            holder.creatorInfoContainer?.visibility = View.GONE
            holder.subscribeButton.visibility = View.GONE
        } else {
             holder.creatorInfoContainer?.visibility = View.VISIBLE
            holder.subscribeButton.visibility = View.VISIBLE
            val embeddedCreatorName = course.creatorUsername?.takeIf { it.isNotBlank() }
            if (!embeddedCreatorName.isNullOrBlank() && creatorUsernameCache[course.creatorUserId] == null) {
                creatorUsernameCache[course.creatorUserId] = embeddedCreatorName
            }
            val embeddedCreatorAvatar = resolveBackendMediaUrl(course.creatorAvatar)
            if (!embeddedCreatorAvatar.isNullOrBlank() && creatorAvatarCache[course.creatorUserId] == null) {
                creatorAvatarCache[course.creatorUserId] = embeddedCreatorAvatar
            }
            val cachedCreatorName = creatorUsernameCache[course.creatorUserId] ?: embeddedCreatorName
            holder.creatorTextView.text = cachedCreatorName ?: "Creador desconocido"
            val cachedAvatar = creatorAvatarCache[course.creatorUserId]
            bindCreatorAvatar(holder, cachedAvatar)
            if (!cachedCreatorName.isNullOrBlank()) {
                holder.creatorTextView.setOnClickListener { onCreatorClickListener?.invoke(cachedCreatorName) }
                holder.creatorAvatarImageView.setOnClickListener { onCreatorClickListener?.invoke(cachedCreatorName) }
                holder.subscriberCountTextView.setOnClickListener { onCreatorClickListener?.invoke(cachedCreatorName) }
                holder.creatorInfoContainer?.setOnClickListener { onCreatorClickListener?.invoke(cachedCreatorName) }
            }
            holder.subscriberCountTextView.text = ""
            holder.subscribeButton.isEnabled = false
            holder.ownerStatusContainer?.visibility = View.GONE
        }

        // Click listener
        holder.itemView.setOnClickListener {
            if (currentUsername == null) {
                android.widget.Toast.makeText(context, "¡Debes iniciar sesión para acceder al curso!", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val isCreator = canUserModifyCourse(course)
            if (!isCreator && course.price > 0) {
                CoroutineScope(Dispatchers.Main + bindJob).launch {
                    val collaboratorAccess = withContext(Dispatchers.IO) {
                        hasCollaboratorAccess(course.id)
                    }
                    if (collaboratorAccess) {
                        onCourseClickListener(course)
                        return@launch
                    }

                    val hasPurchased = withContext(Dispatchers.IO) {
                        checkIfCoursePurchased(course.id, currentUserIdCached ?: 0L)
                    }
                    if (hasPurchased) onCourseClickListener(course) else showPaymentConfirmationDialog(course)
                }
                return@setOnClickListener
            }
            onCourseClickListener(course)
        }

        // ---- DEFERRED: Network calls only run after scroll settles (150ms) ----
        // If user scrolls past quickly, bindJob is cancelled and none of this executes
        CoroutineScope(Dispatchers.Main + bindJob).launch {
            delay(150)

            // 1. Resolve userId once (cached after first resolve)
            if (currentUserIdCached == null && currentUsername != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val userResult = BackendApiService.getUserByUsername(currentUsername!!)
                        (userResult as? ApiResult.Success)?.data?.id?.let { currentUserIdCached = it }
                    } catch (_: Exception) {}
                }
            }

            // 2. Re-evaluate ownership with resolved userId
            val isOwnerNow = currentUserIdCached != null && currentUserIdCached == course.creatorUserId
            val collaboratorAccess = withContext(Dispatchers.IO) {
                hasCollaboratorAccess(course.id)
            }
            // Solo rol 3 (admin) puede ver los 3 puntos de modificar; rol 2 no tiene acceso aunque sea propietario
            bindMoreOptionsVisibility(holder, course, hasAdminRole)

            if (isOwnerNow && !isOwner) {
                holder.creatorInfoContainer?.visibility = View.GONE
                holder.subscribeButton.visibility = View.GONE
                holder.enrollButtonContainer?.visibility = View.GONE
                holder.enrollButton?.visibility = View.GONE
                holder.enrolledStatusContainer?.visibility = View.GONE
                return@launch
            }

            // 4. Creator info + subscription + enrollment (only for non-owners)
            if (!isOwnerNow) {
                // Fetch creator username/avatar if not cached
                if (creatorUsernameCache[course.creatorUserId] == null || creatorAvatarCache[course.creatorUserId] == null) {
                    val (username, avatar) = withContext(Dispatchers.IO) {
                        try {
                            var resolvedUsername = creatorUsernameCache[course.creatorUserId]
                                ?: course.creatorUsername?.takeIf { it.isNotBlank() }
                            if (!resolvedUsername.isNullOrBlank()) {
                                creatorUsernameCache[course.creatorUserId] = resolvedUsername
                            }

                            var resolvedAvatar = creatorAvatarCache[course.creatorUserId]
                                ?: resolveBackendMediaUrl(course.creatorAvatar)?.also {
                                    creatorAvatarCache[course.creatorUserId] = it
                                }

                            if (resolvedUsername == null || resolvedAvatar == null) {
                                val result = BackendApiService.getUserById(course.creatorUserId)
                                val user = (result as? ApiResult.Success)?.data
                                resolvedUsername = resolvedUsername
                                    ?: user?.usuario?.takeIf { it.isNotBlank() }?.also { creatorUsernameCache[course.creatorUserId] = it }
                                resolvedAvatar = resolvedAvatar
                                    ?: resolveBackendMediaUrl(user?.avatar)?.also { creatorAvatarCache[course.creatorUserId] = it }
                            }

                            (resolvedUsername to resolvedAvatar)
                        } catch (_: Exception) { (null to null) }
                    }
                    holder.creatorTextView.text = username ?: "Creador desconocido"
                    if (!username.isNullOrBlank()) {
                        holder.creatorTextView.setOnClickListener { onCreatorClickListener?.invoke(username) }
                        holder.creatorAvatarImageView.setOnClickListener { onCreatorClickListener?.invoke(username) }
                        holder.subscriberCountTextView.setOnClickListener { onCreatorClickListener?.invoke(username) }
                        holder.creatorInfoContainer?.setOnClickListener { onCreatorClickListener?.invoke(username) }
                    }
                    bindCreatorAvatar(holder, avatar)
                }

                // Subscription data (uses its own child coroutine tied to bindJob)
                loadSubscriptionDataWithUserId(holder, course, course.creatorUserId, bindJob)

                // Enrollment status check
                if (collaboratorAccess) {
                    holder.enrollButtonContainer?.visibility = View.GONE
                    holder.enrollButton?.visibility = View.GONE
                    holder.enrolledStatusContainer?.visibility = View.GONE
                } else {
                    checkEnrollmentStatus(holder, course, bindJob)
                }
            }
        }
    }

    override fun onViewRecycled(holder: CourseViewHolder) {
        holder.currentJob?.cancel()
        holder.stopPreview()
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = courses.size

    fun getItem(position: Int): Course? {
        return if (position in courses.indices) courses[position] else null
    }

    fun updateCourses(newCourses: List<Course>) {
        // Cuando llegan cursos nuevos del servidor, invalida el caché de conteos
        // para que se consulte el endpoint actualizado en el próximo bind.
        enrollmentCountCache.clear()
        val sorted = newCourses
            .sortedWith(compareByDescending<Course> { it.timestamp }.thenByDescending { it.creationDate })
        val oldCourses = ArrayList(courses) // snapshot before mutation
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldCourses.size
            override fun getNewListSize() = sorted.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                oldCourses[oldPos].id == sorted[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val o = oldCourses[oldPos]; val n = sorted[newPos]
                return o.title == n.title && o.description == n.description &&
                    o.thumbnailUri == n.thumbnailUri && o.price == n.price &&
                    o.isPremium == n.isPremium && o.timestamp == n.timestamp
            }
        })
        courses = sorted
        diff.dispatchUpdatesTo(this)
    }

    /**
     * Show popup menu with edit and delete options
     */
    private fun showPopupMenu(anchorView: View, course: Course) {
        // Use ContextThemeWrapper to apply dark theme to PopupMenu
        val wrapper = android.view.ContextThemeWrapper(context, R.style.DarkPopupMenuThemeOverlay)
        val popupMenu = PopupMenu(wrapper, anchorView, android.view.Gravity.END)
        popupMenu.menu.add(0, 1, 0, "✏️ Modificar")
        popupMenu.menu.add(0, 2, 1, "🗑️ Eliminar")
        
        // Force icons to show if API level supports it
        try {
            val popup = PopupMenu::class.java.getDeclaredField("mPopup")
            popup.isAccessible = true
            val menuPopupHelper = popup.get(popupMenu)
            menuPopupHelper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(menuPopupHelper, true)
        } catch (e: Exception) {
            // Ignore if reflection fails
        }
        
        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    onEditClickListener?.invoke(course)
                    true
                }
                2 -> {
                    onDeleteClickListener?.invoke(course)
                    true
                }
                else -> false
            }
        }
        
        popupMenu.show()
    }

    /**
     * Check if current user can modify the given course (i.e., is the creator)
     */
    private fun canUserModifyCourse(course: Course): Boolean {
        val uid = currentUserIdCached
        if (uid != null && uid == course.creatorUserId) return true
        if (collaboratorCourseIds.contains(course.id)) return true
        return false
    }
    
    /**
     * Check if current user can modify the given course (suspend version for coroutine contexts)
     */
    private suspend fun canUserModifyCourseSuspend(course: Course): Boolean {
        if (currentUsername == null) return false
        val userResult = BackendApiService.getUserByUsername(currentUsername!!)
        val currentUserId = (userResult as? ApiResult.Success)?.data?.id
        return currentUserId != null && currentUserId == course.creatorUserId
    }

    /**
     * Load real enrollment count from backend
     */
    /**
     * Carga el conteo de estudiantes usando getCourseGuests (misma fuente que el diálogo)
     * para que el número en la tarjeta sea siempre consistente con la vista de detalles.
     */
    private fun loadEnrollmentCount(holder: CourseViewHolder, course: Course, parentJob: Job? = null) {
        if (enrollmentCountCache.containsKey(course.id)) return
        CoroutineScope(Dispatchers.IO + (parentJob ?: SupervisorJob())).launch {
            try {
                val result = BackendApiService.getCourseGuests(course.id)
                val enrolledCount = when (result) {
                    is ApiResult.Success -> {
                        (0 until result.data.size()).mapNotNull { i ->
                            val obj = result.data.get(i)?.asJsonObject ?: return@mapNotNull null
                            obj.get("username")?.let { if (it.isJsonNull) null else it.asString }
                                ?.takeIf { it.isNotBlank() }
                        }.distinct().size
                    }
                    is ApiResult.Error -> 0
                }
                withContext(Dispatchers.Main) {
                    val studentsText = if (enrolledCount == 1) "1 estudiante" else "$enrolledCount estudiantes"
                    holder.studentsTextView?.text = studentsText
                    enrollmentCountCache[course.id] = enrolledCount
                }
            } catch (e: Exception) {
                Log.e("CourseAdapter", "Error loading enrollment count", e)
                withContext(Dispatchers.Main) {
                    holder.studentsTextView?.text = "0 estudiantes"
                }
            }
        }
    }

    /**
     * Load subscription data asynchronously with user IDs
     */
    private fun loadSubscriptionDataWithUserId(holder: CourseViewHolder, course: Course, creatorUserId: Long, parentJob: Job? = null) {
        holder.subscribeButton.text = "Suscribirse"
        holder.subscribeButton.setBackgroundResource(R.drawable.button_premium)
        holder.subscribeButton.setTextColor(ContextCompat.getColor(context, R.color.white))
        holder.subscribeButton.isEnabled = false
        holder.subscribeButton.alpha = 0.65f
        holder.subscribeButton.setOnClickListener(null)

        // Use BackendApiService for subscription data
        
        CoroutineScope(Dispatchers.IO + (parentJob ?: SupervisorJob())).launch {
            try {
                if (currentUserIdCached == null && !currentUsername.isNullOrBlank()) {
                    try {
                        val userResult = BackendApiService.getUserByUsername(currentUsername)
                        (userResult as? ApiResult.Success)?.data?.id?.let { currentUserIdCached = it }
                    } catch (_: Exception) {
                        // Keep going so subscriber count still loads.
                    }
                }

                // Fetch subscriber count from BackendApiService
                val countResult = com.example.tareamov.service.BackendApiService.getSubscriberCount(creatorUserId)
                val subscriberCount = when (countResult) {
                    is com.example.tareamov.service.ApiResult.Success -> countResult.data?.toLong() ?: 0L
                    is com.example.tareamov.service.ApiResult.Error -> 0L
                }
                
                // Check if current user is subscribed to this creator via BackendApiService
                val currentUserId = currentUserIdCached
                val canCheckSubscription = currentUserId != null && currentUserId != creatorUserId
                val isSubscribed = if (canCheckSubscription) {
                    val checkResult = com.example.tareamov.service.BackendApiService.checkSubscription(creatorUserId)
                    when (checkResult) {
                        is com.example.tareamov.service.ApiResult.Success -> checkResult.data ?: false
                        is com.example.tareamov.service.ApiResult.Error -> false
                    }
                } else false
                
                Log.d("CourseAdapter", "Subscription status for creator $creatorUserId: isSubscribed=$isSubscribed, count=$subscriberCount")
                
                withContext(Dispatchers.Main) {
                    // Update subscriber count
                    val countText = if (subscriberCount == 1L) "1 suscriptor" else "$subscriberCount suscriptores"
                    holder.subscriberCountTextView.text = countText
                    
                    // Update subscription button based on real-time status from BackendApiService
                    if (isSubscribed) {
                        holder.subscribeButton.text = "Desuscribirse"
                        holder.subscribeButton.setBackgroundResource(R.drawable.button_subscribed)
                        holder.subscribeButton.setTextColor(ContextCompat.getColor(context, R.color.white))
                    } else {
                        holder.subscribeButton.text = "Suscribirse"
                        holder.subscribeButton.setBackgroundResource(R.drawable.button_premium)
                        holder.subscribeButton.setTextColor(ContextCompat.getColor(context, R.color.white))
                    }
                    
                    // Set button click listener - pass current subscription status
                    holder.subscribeButton.setOnClickListener {
                        if (!holder.subscribeButton.isEnabled) return@setOnClickListener

                        holder.subscribeButton.isEnabled = false
                        holder.subscribeButton.alpha = 0.65f
                        holder.subscribeButton.text = if (isSubscribed) "Desuscribiendo..." else "Suscribiendo..."

                        val listener = onSubscriptionClickListener
                        if (listener == null) {
                            holder.subscribeButton.isEnabled = currentUserIdCached != null && currentUserIdCached != creatorUserId
                            holder.subscribeButton.alpha = if (holder.subscribeButton.isEnabled) 1f else 0.65f
                            holder.subscribeButton.text = if (isSubscribed) "Desuscribirse" else "Suscribirse"
                            return@setOnClickListener
                        }

                        listener.invoke(course, isSubscribed)
                    }
                    
                    holder.subscribeButton.isEnabled = canCheckSubscription
                    holder.subscribeButton.alpha = if (holder.subscribeButton.isEnabled) 1f else 0.65f
                }
            } catch (e: Exception) {
                Log.e("CourseAdapter", "Error loading subscription data from BackendApiService", e)
                withContext(Dispatchers.Main) {
                    holder.subscriberCountTextView.text = holder.subscriberCountTextView.text.takeIf { it.isNotBlank() } ?: "0 suscriptores"
                    holder.subscribeButton.text = "Suscribirse"
                    holder.subscribeButton.setBackgroundResource(R.drawable.button_premium)
                    holder.subscribeButton.setTextColor(ContextCompat.getColor(context, R.color.white))
                    holder.subscribeButton.isEnabled = false
                    holder.subscribeButton.alpha = 0.65f
                }
            }
        }
    }
    
    /**
     * Check if user is enrolled in the course and configure button accordingly.
     * Uses BackendApiService instead of direct Supabase/Room calls.
     */
    private fun checkEnrollmentStatus(holder: CourseViewHolder, course: Course, parentJob: Job? = null) {
        if (currentUsername == null) {
            // Guest mode: Hide enrollment button
            holder.enrollButtonContainer?.visibility = View.GONE
            holder.enrollButton?.visibility = View.GONE
            return
        }

        // Defensive guard: if current user is creator, ensure no enrollment UI appears
        if (currentUserIdCached != null && currentUserIdCached == course.creatorUserId) {
            holder.enrollButtonContainer?.visibility = View.GONE
            holder.enrolledStatusContainer?.visibility = View.GONE
            Log.d("CourseAdapter", "Creator detected; hiding enrollment UI for course ${course.id}")
            return
        }
        
        CoroutineScope(Dispatchers.IO + (parentJob ?: SupervisorJob())).launch {
            try {
                // Get user ID if not cached
                if (currentUserIdCached == null) {
                    val userResult = BackendApiService.getUserByUsername(currentUsername!!)
                    val userId = (userResult as? ApiResult.Success)?.data?.id
                    if (userId == null) {
                        Log.e("CourseAdapter", "Failed to get user ID for username: $currentUsername")
                        return@launch
                    }
                    currentUserIdCached = userId
                }
                val userId = currentUserIdCached!!

                val collaboratorAccess = hasCollaboratorAccess(course.id)
                if (collaboratorAccess) {
                    withContext(Dispatchers.Main) {
                        holder.enrollButtonContainer?.visibility = View.GONE
                        holder.enrollButton?.visibility = View.GONE
                        holder.enrolledStatusContainer?.visibility = View.GONE
                    }
                    return@launch
                }

                // CRITICAL: Double-check if user is the course creator
                if (userId == course.creatorUserId) {
                    Log.d("CourseAdapter", "User $userId is creator of course ${course.id}, hiding all enrollment UI")
                    withContext(Dispatchers.Main) {
                        holder.enrollButtonContainer?.visibility = View.GONE
                        holder.enrollButton?.visibility = View.GONE
                        holder.enrolledStatusContainer?.visibility = View.GONE
                    }
                    return@launch
                }
                
                // Check if user is already enrolled via backend
                val enrolledResult = BackendApiService.isEnrolled(course.id)
                val isEnrolled = enrolledResult is ApiResult.Success && enrolledResult.data == true
                Log.d("CourseAdapter", "Enrollment result for userId=$userId courseId=${course.id}: $isEnrolled")
                
                withContext(Dispatchers.Main) {
                    // FIX: Check if user is creator again
                    val creatorName = creatorUsernameCache[course.creatorUserId]
                        ?: course.creatorUsername?.takeIf { it.isNotBlank() }
                    val isCreatorByUsername = currentUsername != null && creatorName != null && currentUsername == creatorName
                    
                    if (canUserModifyCourse(course) || isCreatorByUsername) {
                        holder.enrollButtonContainer?.visibility = View.GONE
                        holder.enrollButton?.visibility = View.GONE
                        holder.enrolledStatusContainer?.visibility = View.GONE
                        return@withContext
                    }

                    if (isEnrolled) {
                        // Already enrolled - Show enrolled status
                        holder.enrollButtonContainer?.visibility = View.GONE
                        holder.enrollButton?.visibility = View.GONE
                        holder.enrolledStatusContainer?.visibility = View.VISIBLE
                        
                        Log.d("CourseAdapter", "User already enrolled in course ${course.id}, showing enrolled status")
                    } else {
                        // Not enrolled yet - Check if it's a paid course
                        if (course.price > 0) {
                            // Paid course - Check purchase status via backend
                            val hasPurchased = checkIfCoursePurchased(course.id, userId)
                            
                            if (hasPurchased) {
                                holder.enrolledStatusContainer?.visibility = View.VISIBLE
                                holder.enrollButtonContainer?.visibility = View.GONE
                                holder.enrollButton?.visibility = View.GONE
                                Log.d("CourseAdapter", "Course ${course.id} fully purchased")
                            } else {
                                holder.enrolledStatusContainer?.visibility = View.GONE
                                holder.enrollButtonContainer?.visibility = View.VISIBLE
                                holder.enrollButton?.visibility = View.VISIBLE
                                
                                val localeCO = java.util.Locale("es", "CO")
                                val currencyFormat = java.text.NumberFormat.getCurrencyInstance(localeCO)
                                holder.enrollButton?.text = "Comprar ${currencyFormat.format(course.price)}"
                                
                                holder.enrollButton?.isEnabled = true
                                holder.enrollButton?.alpha = 1.0f
                                holder.enrollButton?.setBackgroundResource(R.drawable.button_premium)
                                
                                holder.enrollButton?.setOnClickListener {
                                    // Trigger payment flow instead of course navigation
                                    onPaymentClickListener?.invoke(course) ?: onCourseClickListener(course)
                                }
                                
                                Log.d("CourseAdapter", "Course ${course.id} not purchased yet, showing buy button")
                            }
                        } else {
                            // Free course - Show enrollment section
                            holder.enrolledStatusContainer?.visibility = View.GONE
                            holder.enrollButtonContainer?.visibility = View.VISIBLE
                            holder.enrollButton?.visibility = View.VISIBLE
                            holder.enrollButton?.text = "Inscribirse al curso"
                            holder.enrollButton?.isEnabled = true
                            holder.enrollButton?.alpha = 1.0f
                            holder.enrollButton?.setBackgroundResource(R.drawable.button_premium)
                            
                            holder.enrollButton?.setOnClickListener {
                                if (canUserModifyCourse(course)) {
                                    Log.w("CourseAdapter", "Creator attempted to enroll in own course ${course.id}; action blocked")
                                    holder.enrollButton?.isEnabled = false
                                    holder.enrollButton?.alpha = 0.6f
                                    holder.enrollButton?.text = "No disponible"
                                    return@setOnClickListener
                                }

                                holder.enrollButton?.isEnabled = false
                                holder.enrollButton?.alpha = 0.6f
                                holder.enrollButton?.text = "Inscribiendo..."

                                onEnrollClickListener?.invoke(course)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("CourseAdapter", "Error checking enrollment status", e)
                withContext(Dispatchers.Main) {
                    holder.enrollButton?.text = "Inscribirse al curso"
                    holder.enrollButton?.isEnabled = true
                    holder.enrollButton?.alpha = 1.0f
                }
            }
        }
    }

    private suspend fun hasCollaboratorAccess(courseId: Long): Boolean {
        collaboratorAccessCache[courseId]?.let { cached ->
            if (cached) collaboratorCourseIds.add(courseId)
            return cached
        }

        return try {
            val result = BackendApiService.checkCollaboratorAccess(courseId)
            val hasAccess = result is ApiResult.Success && (result.data.get("hasAccess")?.asBoolean == true)
            collaboratorAccessCache[courseId] = hasAccess
            if (hasAccess) collaboratorCourseIds.add(courseId)
            hasAccess
        } catch (e: Exception) {
            Log.w("CourseAdapter", "Error checking collaborator access for course $courseId", e)
            collaboratorAccessCache[courseId] = false
            false
        }
    }

    private fun applyDarkModeTheme(view: View) {
        // Set dark background for the card using CardView method to preserve corners
        if (view is androidx.cardview.widget.CardView) {
            view.setCardBackgroundColor(ContextCompat.getColor(context, R.color.dark_card_background))
            // Apply rounded corners and elevation
            view.cardElevation = 8f
            // Ensure radius from XML is respected or updated if needed
            // view.radius = dpToPx(32).toFloat() 
        } else {
            view.setBackgroundColor(ContextCompat.getColor(context, R.color.dark_card_background))
            view.elevation = 8f
        }
        // view.clipToOutline = true // Let CardView handle clipping
    }

    private fun applyDarkModeTextColors(holder: CourseViewHolder) {
        // Primary text color (white/light gray)
        // Force white text for contrast where needed (header/gradient overlays)
        val primaryTextColor = android.graphics.Color.WHITE
        val secondaryTextColor = android.graphics.Color.WHITE
        val accentColor = android.graphics.Color.WHITE

        holder.titleTextView.setTextColor(primaryTextColor)
        holder.descriptionTextView.setTextColor(secondaryTextColor)
        holder.creatorTextView.setTextColor(primaryTextColor)
        holder.categoryTextView.setTextColor(accentColor)
        
        holder.studentsTextView?.setTextColor(secondaryTextColor)
        holder.priceTextView.setTextColor(accentColor)
        // Subscription elements also white for consistency in header overlays
        holder.subscriberCountTextView.setTextColor(android.graphics.Color.WHITE)
    }
    
    /**
     * Load course thumbnail image using Glide
     */
    private fun loadCourseThumbnail(holder: CourseViewHolder, course: Course) {
        val requestOptions = RequestOptions()
            .placeholder(R.drawable.bg_course_placeholder_card)
            .error(R.drawable.bg_course_placeholder_card)
            .centerCrop()

        var thumbnailUri = course.thumbnailUri?.trim()

        // Robust URL handling
        if (!thumbnailUri.isNullOrEmpty()) {
            if (!thumbnailUri!!.startsWith("http") && !thumbnailUri!!.startsWith("content://") && !thumbnailUri!!.startsWith("file://")) {
                val key = if (thumbnailUri!!.startsWith("/")) thumbnailUri!!.substring(1) else thumbnailUri!!
                thumbnailUri = com.example.tareamov.service.BackendApiService.buildProxyFileUrl(key)
            }
        }
        
        if (!thumbnailUri.isNullOrEmpty()) {
            Log.d("CourseAdapter", "Loading thumbnail for '${course.title}': $thumbnailUri")
            Glide.with(context)
                .load(thumbnailUri)
                .apply(requestOptions)
                .into(holder.thumbnailImageView)
        } else if (!course.videoUri.isNullOrEmpty()) {
            // Fallback: Attempt to load video frame if thumbnail is missing
            Log.d("CourseAdapter", "No thumbnail for '${course.title}', trying video frame: ${course.videoUri}")
            Glide.with(context)
                .asBitmap()
                .load(course.videoUri)
                .apply(requestOptions)
                .into(holder.thumbnailImageView)
        } else {
            Log.d("CourseAdapter", "No thumbnail or video for '${course.title}', using placeholder")
            holder.thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)
        }
    }
    
    /**
     * Check if course is fully purchased with successful transactions.
     * Uses BackendApiService instead of raw SQL queries.
     */
    private suspend fun checkIfCoursePurchased(courseId: Long, userId: Long): Boolean {
        return try {
            if (userId <= 0) return false
            
            val result = BackendApiService.hasPurchasedCourse(courseId)
            val hasPurchased = result is ApiResult.Success && result.data == true
            Log.d("CourseAdapter", "Course $courseId purchase check via backend: purchased=$hasPurchased")
            hasPurchased
        } catch (e: Exception) {
            Log.e("CourseAdapter", "Error checking course purchase status", e)
            false
        }
    }
    
    /**
     * Convert dp to pixels for Glide rounded corners
     */
    private fun dpToPx(dp: Int): Int {
        val density = context.resources.displayMetrics.density
        return (dp * density).toInt()
    }
    
    /**
     * Show payment confirmation dialog with liquid glass design
     */
    private fun showPaymentConfirmationDialog(course: Course) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_confirm_payment, null)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setView(dialogView)
            .create()
        
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        val title = dialogView.findViewById<TextView>(R.id.confirmPaymentTitle)
        val message = dialogView.findViewById<TextView>(R.id.confirmPaymentMessage)
        val priceText = dialogView.findViewById<TextView>(R.id.paymentPriceText)
        val confirmButton = dialogView.findViewById<TextView>(R.id.confirmPaymentButton)
        val cancelButton = dialogView.findViewById<TextView>(R.id.cancelPaymentButton)
        
        title.text = course.title
        message.text = "Este curso requiere un pago para acceder. ¿Deseas continuar con el proceso de pago?"
        
        // Format price
        val formattedPrice = if (course.price > 0) {
            String.format("$%,.0f COP", course.price)
        } else {
            "Precio no disponible"
        }
        priceText.text = formattedPrice
        
        confirmButton.setOnClickListener {
            dialog.dismiss()
            // Call payment callback
            onPaymentClickListener?.invoke(course)
        }
        
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        
        dialog.show()
    }
}
