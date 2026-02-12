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
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.data.entity.Course
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    private val subscriptionStatus: Map<Long, Boolean> = emptyMap() // Subscription status map
) : RecyclerView.Adapter<CourseAdapter.CourseViewHolder>() {

    // Cache current user's id to avoid blocking lookups during bind
    private var currentUserIdCached: Long? = null
    fun setCurrentUserId(userId: Long?) {
        currentUserIdCached = userId
        notifyDataSetChanged()
    }

    // Cache for creator usernames by userId to reduce repeated network calls
    private val creatorUsernameCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    // Cache for creator avatars by userId
    private val creatorAvatarCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnailImageView: ImageView = itemView.findViewById(R.id.courseThumbnailImageView)
        val titleTextView: TextView = itemView.findViewById(R.id.courseTitleTextView)
        val descriptionTextView: TextView = itemView.findViewById(R.id.courseDescriptionTextView)
        val creatorTextView: TextView = itemView.findViewById(R.id.courseCreatorTextView)
        val categoryTextView: TextView = itemView.findViewById(R.id.courseCategoryTextView)
        
        val priceTextView: TextView = itemView.findViewById(R.id.coursePriceTextView)
        val originalPriceTextView: TextView = itemView.findViewById(R.id.originalPriceTextView)
        val enrollmentTextView: TextView = itemView.findViewById(R.id.courseEnrollmentTextView)
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
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course_card, parent, false)
        
        // Apply dark mode styling
        applyDarkModeTheme(view)
        
        return CourseViewHolder(view)
    }

    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        val course = courses[position]

        // Set course data
        holder.titleTextView.text = course.title
        holder.descriptionTextView.text = course.description
        // Don't set creatorTextView here - it will be set based on user permissions below
        holder.categoryTextView.text = if (course.category.isNullOrBlank()) "Programación" else course.category
        
        
        Log.d("CourseAdapter", "Binding course: ${course.title}, creatorUserId: ${course.creatorUserId}, currentUsername: $currentUsername")
        
        // CRITICAL: Check if user is creator FIRST before showing any UI
        var isCreator = canUserModifyCourse(course)
        
        // Default: hide enrollment-related UI to avoid brief flashes before ownership check completes
        holder.enrollButtonContainer?.visibility = View.GONE
        holder.enrollButton?.visibility = View.GONE
        holder.enrolledStatusContainer?.visibility = View.GONE
        // CRITICAL: Hide 3-dot menu by default - only show for course owners
        holder.moreOptionsButton?.visibility = View.GONE

        // If currentUserIdCached is null but we have a username, fetch the user ID asynchronously
        // and update the UI accordingly
        if (currentUserIdCached == null && currentUsername != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val userResult = BackendApiService.getUserByUsername(currentUsername!!)
                    val userId = (userResult as? ApiResult.Success)?.data?.id
                    if (userId != null) {
                        currentUserIdCached = userId
                        val isOwner = userId == course.creatorUserId
                        withContext(Dispatchers.Main) {
                            if (isOwner) {
                                // HIDE creatorInfoContainer completely for course owners
                                holder.creatorInfoContainer?.visibility = View.GONE
                                holder.subscribeButton.visibility = View.GONE
                                holder.moreOptionsButton?.visibility = View.VISIBLE
                                holder.enrollButtonContainer?.visibility = View.GONE
                                holder.enrolledStatusContainer?.visibility = View.GONE
                                
                                // Set up 3-dot menu click listener
                                holder.moreOptionsButton?.setOnClickListener { view ->
                                    showPopupMenu(view, course)
                                }
                                Log.d("CourseAdapter", "Async check: User IS creator of course: ${course.title}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CourseAdapter", "Error fetching user ID for ownership check", e)
                }
            }
        }

        // Load real enrollment count from progreso_estudiante table
        loadEnrollmentCount(holder, course)
        
        Log.d("CourseAdapter", "Is creator: $isCreator for course: ${course.title}")
        
        if (isCreator) {
            // HIDE subscription/creator info container completely for course owners
            holder.creatorInfoContainer?.visibility = View.GONE
            
            // Hide subscribe button (it's inside creatorInfoContainer but just in case)
            holder.subscribeButton.visibility = View.GONE
            
            // Show 3-dot menu for creators (now in Course Info Row)
            holder.moreOptionsButton?.visibility = View.VISIBLE
            
            // CRITICAL: Hide ALL enrollment UI for creators (both button and enrolled status)
            // This ensures course owners never see enrollment options
            holder.enrollButtonContainer?.visibility = View.GONE
            holder.enrollButton?.visibility = View.GONE
            holder.enrolledStatusContainer?.visibility = View.GONE

            // Owner badge removed from layout
            
            // Set up 3-dot menu click listener
            holder.moreOptionsButton?.setOnClickListener { view ->
                showPopupMenu(view, course)
            }
        } else {
            // Hide CRUD actions for non-creators
            holder.moreOptionsButton?.visibility = View.GONE
            
            // Show subscription info for other users' courses, show subscribe button
            holder.creatorInfoContainer?.visibility = View.VISIBLE
            holder.subscribeButton.visibility = View.VISIBLE
            
            // Set creator info - fetch username from user_id
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val creatorUsername = creatorUsernameCache[course.creatorUserId]
                        ?: run {
                            val result = com.example.tareamov.service.BackendApiService.getUserById(course.creatorUserId)
                            val user = (result as? com.example.tareamov.service.ApiResult.Success)?.data
                            user?.usuario?.also { creatorUsernameCache[course.creatorUserId] = it }
                        }
                    
                    // Get creator avatar
                    val creatorAvatar = creatorAvatarCache[course.creatorUserId]
                        ?: run {
                            val result = com.example.tareamov.service.BackendApiService.getUserById(course.creatorUserId)
                            val user = (result as? com.example.tareamov.service.ApiResult.Success)?.data
                            user?.avatar?.also { creatorAvatarCache[course.creatorUserId] = it }
                        }

                    withContext(Dispatchers.Main) {
                        if (currentUsername != null && creatorUsername == currentUsername) {
                            holder.creatorTextView.text = "Por: $creatorUsername"
                            
                            // Enable click on creator name/avatar to view profile
                            if (!creatorUsername.isNullOrBlank()) {
                                holder.creatorTextView.setOnClickListener { onCreatorClickListener?.invoke(creatorUsername) }
                                holder.creatorAvatarImageView.setOnClickListener { onCreatorClickListener?.invoke(creatorUsername) }
                                holder.subscriberCountTextView.setOnClickListener { onCreatorClickListener?.invoke(creatorUsername) }
                                holder.creatorInfoContainer?.setOnClickListener { onCreatorClickListener?.invoke(creatorUsername) }
                            }
                            
                            holder.enrollButtonContainer?.visibility = View.GONE
                            holder.enrollButton?.visibility = View.GONE
                            holder.enrolledStatusContainer?.visibility = View.GONE
                            holder.moreOptionsButton?.visibility = View.VISIBLE
                            holder.creatorInfoContainer?.visibility = View.VISIBLE
                            
                            // Set up 3-dot menu click listener for this case too
                            holder.moreOptionsButton?.setOnClickListener { view ->
                                showPopupMenu(view, course)
                            }
                        } else {
                            holder.creatorTextView.text = creatorUsername ?: "Creador desconocido"
                            Log.d("CourseAdapter", "Creator username loaded: $creatorUsername for course: ${course.title}")
                            
                            // Enable click on creator name/avatar/subscriber count to view creator's profile
                            if (!creatorUsername.isNullOrBlank()) {
                                holder.creatorTextView.setOnClickListener { onCreatorClickListener?.invoke(creatorUsername) }
                                holder.creatorAvatarImageView.setOnClickListener { onCreatorClickListener?.invoke(creatorUsername) }
                                holder.subscriberCountTextView.setOnClickListener { onCreatorClickListener?.invoke(creatorUsername) }
                                holder.creatorInfoContainer?.setOnClickListener { onCreatorClickListener?.invoke(creatorUsername) }
                            }
                            
                            // Update Avatar
                            if (!creatorAvatar.isNullOrEmpty()) {
                                 Glide.with(context)
                                    .load(creatorAvatar)
                                    .placeholder(R.drawable.default_avatar)
                                    .error(R.drawable.default_avatar)
                                    .into(holder.creatorAvatarImageView)
                            } else {
                                 holder.creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                            }

                            // Load subscription data with user IDs
                            loadSubscriptionDataWithUserId(holder, course, course.creatorUserId)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CourseAdapter", "Error loading creator username for course ${course.id}", e)
                    withContext(Dispatchers.Main) {
                        holder.creatorTextView.text = "Creador desconocido"
                        holder.creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                    }
                }
            }
            
            // Initial placeholder while loading
            if (holder.creatorAvatarImageView.drawable == null) {
                holder.creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
            }
            
            // ONLY check enrollment status for non-creators
            // This prevents any enrollment UI from appearing on creator's own courses
            if (!isCreator) {
                checkEnrollmentStatus(holder, course)
            }
            
            // Ensure owner badge hidden for non-creators
            holder.ownerStatusContainer?.visibility = View.GONE
        }

        // Set price without discount logic
        if (course.isPremium && course.price > 0) {
            holder.priceTextView.text = "$${String.format("%.2f", course.price)}"
            holder.originalPriceTextView.visibility = View.GONE
            holder.premiumBadge.visibility = View.VISIBLE
        } else {
            holder.priceTextView.text = "Gratis"
            holder.originalPriceTextView.visibility = View.GONE
            holder.premiumBadge.visibility = View.GONE
        }

        // Load thumbnail image
        loadCourseThumbnail(holder, course)

        // Apply dark mode colors to text views
        applyDarkModeTextColors(holder)

        // Set click listener - OPTIMIZED: Navigate immediately, enroll in background
        holder.itemView.setOnClickListener {
            // Check if user is logged in
            if (currentUsername == null) {
                android.widget.Toast.makeText(context, "¡Debes iniciar sesión para acceder al curso!", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            // CRITICAL: Check if user is the creator - creators don't need enrollment and have full access
            val isCreator = canUserModifyCourse(course)
            
            if (!isCreator && course.price > 0) {
                // Check if course is already purchased with successful transaction
                CoroutineScope(Dispatchers.Main).launch {
                    val hasPurchased = withContext(Dispatchers.IO) {
                        checkIfCoursePurchased(course.id, currentUserIdCached ?: 0L)
                    }
                    
                    if (hasPurchased) {
                        // Course already purchased - allow direct access
                        onCourseClickListener(course)
                        backgroundEnroll(course)
                    } else {
                        // Course not purchased - show payment dialog
                        showPaymentConfirmationDialog(course)
                    }
                }
                return@setOnClickListener
            }
            
            // OPTIMIZED: Navigate IMMEDIATELY, enroll in background if needed
            onCourseClickListener(course)
            
            // If it's a free course and user is NOT the creator, auto-enroll in background
            if (!course.isPremium && course.price == 0.0 && !isCreator) {
                backgroundEnroll(course)
            }
        }
    }
    
    /**
     * Enroll user in background without blocking navigation.
     * Uses BackendApiService to handle enrollment server-side.
     */
    private fun backgroundEnroll(course: Course) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Guard: don't auto-enroll the course creator into their own course
                if (currentUserIdCached != null && currentUserIdCached == course.creatorUserId) {
                    Log.d("CourseAdapter", "Skipping background enrollment: user is course creator for course ${course.id}")
                    return@launch
                }

                // Check if already enrolled via backend
                val enrolledResult = BackendApiService.isEnrolled(course.id)
                if (enrolledResult is ApiResult.Success && enrolledResult.data == true) {
                    Log.d("CourseAdapter", "Already enrolled in course ${course.id}")
                    return@launch
                }

                // Enroll via backend upsert
                val data = mapOf<String, Any?>(
                    "courseId" to course.id,
                    "completedTasks" to 0,
                    "progressPercentage" to 0f,
                    "status" to "Perdido"
                )
                val result = BackendApiService.upsertProgress(data)
                if (result is ApiResult.Success) {
                    Log.d("CourseAdapter", "✅ Background enrolled in course ${course.id}")
                } else {
                    Log.e("CourseAdapter", "Background enrollment failed: ${(result as? ApiResult.Error)?.message}")
                }
            } catch (e: Exception) {
                Log.e("CourseAdapter", "Background enrollment failed", e)
            }
        }
    }
    
    /**
     * Auto-enroll user in a free course and navigate to course detail
     */
    private fun autoEnrollAndNavigate(course: Course) {
        onCourseClickListener(course)
        backgroundEnroll(course)
    }

    override fun getItemCount(): Int = courses.size

    fun getItem(position: Int): Course? {
        return if (position in courses.indices) courses[position] else null
    }

    fun updateCourses(newCourses: List<Course>) {
        // Always show newest courses first to match Supabase ordering
        courses = newCourses
            .sortedWith(compareByDescending<Course> { it.timestamp }.thenByDescending { it.creationDate })
        notifyDataSetChanged()
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
        val can = uid != null && uid == course.creatorUserId
        Log.d("CourseAdapter", "canUserModifyCourse: currentUserId=$uid, course.creatorUserId=${course.creatorUserId}, canModify=$can")
        return can
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
    private fun loadEnrollmentCount(holder: CourseViewHolder, course: Course) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val result = BackendApiService.getEnrolledCount(course.id)
                val enrolledCount = when (result) {
                    is ApiResult.Success -> result.data ?: 0
                    is ApiResult.Error -> 0
                }
                withContext(Dispatchers.Main) {
                    val studentsText = if (enrolledCount == 1) "1 estudiante" else "$enrolledCount estudiantes"
                    holder.enrollmentTextView.text = studentsText
                }
            } catch (e: Exception) {
                Log.e("CourseAdapter", "Error loading enrollment count", e)
                withContext(Dispatchers.Main) {
                    holder.enrollmentTextView.text = "0 estudiantes"
                }
            }
        }
    }

    /**
     * Load subscription data asynchronously with user IDs
     */
    private fun loadSubscriptionDataWithUserId(holder: CourseViewHolder, course: Course, creatorUserId: Long) {
        if (currentUserIdCached == null) {
            // No user logged in
            holder.subscriberCountTextView.text = "0 suscriptores"
            holder.subscribeButton.text = "Suscribirse"
            holder.subscribeButton.isEnabled = false
            return
        }

        // Use BackendApiService for subscription data
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fetch subscriber count from BackendApiService
                val countResult = com.example.tareamov.service.BackendApiService.getSubscriberCount(creatorUserId)
                val subscriberCount = when (countResult) {
                    is com.example.tareamov.service.ApiResult.Success -> countResult.data?.toLong() ?: 0L
                    is com.example.tareamov.service.ApiResult.Error -> 0L
                }
                
                // Check if current user is subscribed to this creator via BackendApiService
                val isSubscribed = if (currentUserIdCached != null && currentUserIdCached != creatorUserId) {
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
                        onSubscriptionClickListener?.invoke(course, isSubscribed)
                    }
                    
                    holder.subscribeButton.isEnabled = currentUserIdCached != null && currentUserIdCached != creatorUserId
                }
            } catch (e: Exception) {
                Log.e("CourseAdapter", "Error loading subscription data from BackendApiService", e)
                withContext(Dispatchers.Main) {
                    holder.subscriberCountTextView.text = "0 suscriptores"
                    holder.subscribeButton.text = "Suscribirse"
                    holder.subscribeButton.setBackgroundResource(R.drawable.button_premium)
                    holder.subscribeButton.setTextColor(ContextCompat.getColor(context, R.color.white))
                    holder.subscribeButton.isEnabled = true
                }
            }
        }
    }
    
    /**
     * Check if user is enrolled in the course and configure button accordingly.
     * Uses BackendApiService instead of direct Supabase/Room calls.
     */
    private fun checkEnrollmentStatus(holder: CourseViewHolder, course: Course) {
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
        
        CoroutineScope(Dispatchers.IO).launch {
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
        
        holder.enrollmentTextView.setTextColor(secondaryTextColor)
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
            if (thumbnailUri!!.startsWith("/")) {
                thumbnailUri = "${com.example.tareamov.service.BackendApiService.baseUrl}$thumbnailUri"
            } else if (!thumbnailUri!!.startsWith("http") && !thumbnailUri!!.startsWith("content://") && !thumbnailUri!!.startsWith("file://")) {
                // Assume relative path missing leading slash
                thumbnailUri = "${com.example.tareamov.service.BackendApiService.baseUrl}/$thumbnailUri"
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
