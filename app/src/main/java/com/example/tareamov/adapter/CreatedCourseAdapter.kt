package com.example.tareamov.adapter

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.VideoView
import android.widget.Button
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.example.tareamov.R
import com.example.tareamov.data.entity.VideoData
// import com.example.tareamov.util.ThumbnailManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Adaptador para mostrar los cursos creados en el fragmento de exploración
 */
class CreatedCourseAdapter(
    private val context: Context,
    private var courses: List<VideoData>,
    private val onCourseClickListener: (VideoData) -> Unit,
    private val currentUsername: String? = null, // Current logged-in user for permission checks
    private val onEditCourseListener: ((VideoData) -> Unit)? = null, // Callback for editing course
    private val onDeleteCourseListener: ((VideoData) -> Unit)? = null, // Callback for deleting course
    private val onChangeThumbnailListener: ((VideoData) -> Unit)? = null, // Callback for changing thumbnail
    private val onSubscriptionClickListener: ((VideoData, Boolean) -> Unit)? = null, // Subscription callback
    private val onEnrollClickListener: ((VideoData) -> Unit)? = null, // Enrollment callback
    private val showOnlyEditDelete: Boolean = false // When true, popup shows only Edit and Delete
) : RecyclerView.Adapter<CreatedCourseAdapter.CourseViewHolder>() {

    private var currentPlayingHolder: CourseViewHolder? = null
    // private val thumbnailManager = ThumbnailManager(context)
    
    // Cache for creator usernames by userId to reduce repeated network calls
    private val creatorUsernameCache = java.util.concurrent.ConcurrentHashMap<Long, String>()
    
    // Cache for creator avatar URLs by username to reduce repeated network calls
    private val creatorAvatarCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    
    // Cache current user's id to avoid blocking lookups during bind
    private var currentUserIdCached: Long? = null
    
    init {
        // Initialize currentUserIdCached if username is provided
        if (currentUsername != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    currentUserIdCached = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername)
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error fetching current user ID", e)
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CourseViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_course_card, parent, false)
        return CourseViewHolder(view)
    }    override fun onBindViewHolder(holder: CourseViewHolder, position: Int) {
        holder.bind(courses[position])
    }    override fun onViewAttachedToWindow(holder: CourseViewHolder) {
        super.onViewAttachedToWindow(holder)
        Log.d("CreatedCourseAdapter", "View attached to window for position: ${holder.bindingAdapterPosition}")
        // Iniciar reproducción automática con un pequeño delay
        holder.startAutoPlayWithDelay()
    }

    override fun onViewDetachedFromWindow(holder: CourseViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.stopAutoPlay()
        holder.stopPreview()
    }

    override fun getItemCount(): Int = courses.size

    fun getItem(position: Int): VideoData? {
        return if (position in 0 until courses.size) courses[position] else null
    }

    /**
     * Actualiza la lista de cursos y notifica al adaptador
     */
    fun updateCourses(newCourses: List<VideoData>) {
        // Detener cualquier reproducción actual
        currentPlayingHolder?.stopAutoPlay()
        currentPlayingHolder = null

        val oldSize = courses.size
        // Ensure newest items appear first in the feed
        courses = newCourses
            .sortedWith(compareByDescending<VideoData> { it.timestamp }.thenByDescending { it.id })

        // Simpler and more reliable: always refresh entire dataset to avoid
        // subtle RecyclerView notification edge-cases that can hide items.
        notifyDataSetChanged()

        Log.d("CreatedCourseAdapter", "Courses updated: ${courses.size} items (oldSize=$oldSize)")
    }

    /**
     * Remove a specific course from the adapter
     */
    fun removeCourse(courseId: Long) {
        val index = courses.indexOfFirst { it.id == courseId }
        if (index != -1) {
            val mutableCourses = courses.toMutableList()
            mutableCourses.removeAt(index)
            courses = mutableCourses
            notifyItemRemoved(index)
            Log.d("CreatedCourseAdapter", "Course removed from adapter at position $index: $courseId")
        }
    }

    /**
     * Force regenerate thumbnail for a specific course - REMOVED (Obsolete)
     */
    fun forceRegenerateThumbnail(courseId: Long) {
        // Functionality removed as ThumbnailManager is obsolete
        Log.d("CreatedCourseAdapter", "forceRegenerateThumbnail called but functionality is removed")
    }

    /**
     * Detiene todos los videos que se están reproduciendo
     */
    fun stopAllVideos() {
        currentPlayingHolder?.stopAutoPlay()
        currentPlayingHolder = null
    }

    /**
     * Check if current user can modify the given course
     */
    private fun canUserModifyCourse(course: VideoData): Boolean {
        val canModify = currentUsername != null && currentUsername == course.username
        Log.d("CreatedCourseAdapter", "Permission check - Current user: '$currentUsername', Course creator: '${course.username}', Can modify: $canModify")
        return canModify
    }

    /**
     * Show context menu for course actions (only for course creators)
     */
    private fun showCourseContextMenu(course: VideoData, view: View) {
        if (!canUserModifyCourse(course)) {
            Log.d("CreatedCourseAdapter", "User cannot modify course: ${course.title}")
            return // Only show context menu to course creators
        }

        // Build a visually-matching popup like the video options (emoji + dark theme)
        val wrapper = android.view.ContextThemeWrapper(context, R.style.DarkPopupMenuThemeOverlay)
        val popupMenu = android.widget.PopupMenu(wrapper, view, android.view.Gravity.END)

        // Add Edit and Delete always
        popupMenu.menu.add(0, 1, 0, "✏️ Modificar")
        popupMenu.menu.add(0, 2, 1, "🗑️ Eliminar")

        // Optionally add Change Thumbnail when allowed
        if (!showOnlyEditDelete) {
            popupMenu.menu.add(0, 3, 2, "🖼️ Cambiar miniatura")
        }

        // Force icons if possible (reflection fallback)
        try {
            val field = android.widget.PopupMenu::class.java.getDeclaredField("mPopup")
            field.isAccessible = true
            val menuPopupHelper = field.get(popupMenu)
            menuPopupHelper.javaClass.getDeclaredMethod("setForceShowIcon", Boolean::class.java)
                .invoke(menuPopupHelper, true)
        } catch (e: Exception) {
            // ignore
        }

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                1 -> {
                    editCourse(course)
                    true
                }
                2 -> {
                    deleteCourse(course)
                    true
                }
                3 -> {
                    changeThumbnail(course)
                    true
                }
                else -> false
            }
        }

        popupMenu.show()
        Log.d("CreatedCourseAdapter", "Styled popup menu shown for creator: ${course.title}")
    }
    /**
     * Delete course request - delegate to hosting fragment/activity.
     * The hosting component is responsible for showing confirmation UI
     * (e.g., ConfirmDeleteDialogFragment) and performing the actual delete.
     */
    private fun deleteCourse(course: VideoData) {
        Log.d("CreatedCourseAdapter", "Delete course requested: ${course.title}")
        onDeleteCourseListener?.invoke(course)
    }

    /**
     * Invoke edit callback provided by the hosting fragment/activity.
     */
    private fun editCourse(course: VideoData) {
        Log.d("CreatedCourseAdapter", "Edit course requested: ${course.title}")
        onEditCourseListener?.invoke(course)
    }

    /**
     * Invoke change-thumbnail callback provided by the hosting fragment/activity.
     */
    private fun changeThumbnail(course: VideoData) {
        Log.d("CreatedCourseAdapter", "Change thumbnail requested: ${course.title}")
        onChangeThumbnailListener?.invoke(course)
    }
    /**
     * ViewHolder para mostrar un curso individual
     */
    inner class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnailImageView: ImageView = itemView.findViewById(R.id.courseThumbnailImageView)
        private val videoPreview: VideoView? = itemView.findViewById(R.id.courseVideoPreview)
        private val titleTextView: TextView = itemView.findViewById(R.id.courseTitleTextView)
        private val studentsTextView: TextView = itemView.findViewById(R.id.courseStudentsTextView)
        private val categoryTextView: TextView = itemView.findViewById(R.id.courseCategoryTextView)
        private val authorTextView: TextView = itemView.findViewById(R.id.courseCreatorTextView)
        private val priceTextView: TextView = itemView.findViewById(R.id.coursePriceTextView)
        
        // Subscription elements
        private val creatorInfoContainer: LinearLayout = itemView.findViewById(R.id.creatorInfoContainer)
        private val creatorAvatarImageView: de.hdodenhof.circleimageview.CircleImageView = itemView.findViewById(R.id.creatorAvatarImageView)
        private val subscriberCountTextView: TextView = itemView.findViewById(R.id.subscriberCountTextView)
        private val subscribeButton: Button = itemView.findViewById(R.id.subscribeButton)

        // Enrollment elements
        private val enrollButtonContainer: LinearLayout? = itemView.findViewById(R.id.enrollButtonContainer)
        private val enrollButton: Button? = itemView.findViewById(R.id.enrollButton)
        private val enrolledStatusContainer: LinearLayout? = itemView.findViewById(R.id.enrolledStatusContainer)
        private val ownerStatusContainer: LinearLayout? = itemView.findViewById(R.id.ownerStatusContainer)

        // New menu button next to category
        // private val optionsMenuButton: ImageView? = itemView.findViewById(R.id.courseOptionsMenuButton)

        private val handler = Handler(Looper.getMainLooper())
        private var thumbnailRunnable: Runnable? = null
        private var videoRunnable: Runnable? = null
        private var stopVideoRunnable: Runnable? = null
        private var currentCourse: VideoData? = null

        // Helper to find options menu button by known ids
        private fun findOptionsMenuButton(root: View): ImageView? {
            val names = listOf("courseOptionsMenuButton", "moreOptionsButton")
            for (name in names) {
                try {
                    val resourceId = root.context.resources.getIdentifier(name, "id", root.context.packageName)
                    if (resourceId != 0) {
                        val v = root.findViewById<ImageView>(resourceId)
                        if (v != null) return v
                    }
                } catch (_: Exception) {
                }
            }
            return null
        }

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val course = courses[position]
                    Log.d("CreatedCourseAdapter", "Course clicked: ID=${course.id}, Title=${course.title}")
                    onCourseClickListener(course)
                }
            }

            // Add click listener for the options menu button (supports multiple layout IDs)
            try {
                val optionsMenuButton = findOptionsMenuButton(itemView)
                if (optionsMenuButton != null) {
                    optionsMenuButton.setOnClickListener {
                        val position = bindingAdapterPosition
                        if (position != RecyclerView.NO_POSITION) {
                            val course = courses[position]
                            if (canUserModifyCourse(course)) {
                                showCourseContextMenu(course, it)
                            }
                        }
                    }
                    Log.d("CreatedCourseAdapter", "Successfully set up options menu button click listener")
                } else {
                    Log.w("CreatedCourseAdapter", "Could not find options menu button resource ID")
                }
            } catch (e: Exception) {
                Log.e("CreatedCourseAdapter", "Error setting up options menu button click listener", e)
            }

            // Add long click listener for course creators to show edit options
            itemView.setOnLongClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val course = courses[position]
                    if (canUserModifyCourse(course)) {
                        showCourseContextMenu(course, itemView)
                        true // Consume the long click
                    } else {
                        false // Don't consume if user can't modify
                    }
                } else {
                    false
                }
            }
        }        fun bind(course: VideoData) {
            currentCourse = course

            // FAST: Bind essential text data immediately (no async operations)
            titleTextView.text = course.title.takeIf { !it.isNullOrEmpty() } ?: "Curso sin título"
            titleTextView.maxLines = 2
            titleTextView.ellipsize = android.text.TextUtils.TruncateAt.END
            authorTextView.text = course.username
            priceTextView.text = if (course.price != null && course.price > 0.0) {
                String.format("$%.2f", course.price)
            } else {
                "Gratis"
            }

            // FAST: Show/hide options menu button and subscription UI based on permissions
            val isCreator = canUserModifyCourse(course)
            
                if (isCreator) {
                categoryTextView.text = "Mis Cursos"
                categoryTextView.setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))

                // Show options menu button IMMEDIATELY (no animation delay for faster UX)
                    try {
                        val optionsMenuButton = findOptionsMenuButton(itemView)
                        optionsMenuButton?.visibility = View.VISIBLE
                        optionsMenuButton?.alpha = 1f
                    } catch (e: Exception) {
                        Log.e("CreatedCourseAdapter", "Error showing options menu button", e)
                    }
                
                // Hide subscription container for course creators
                creatorInfoContainer.visibility = View.GONE
                
                // Show owner status, hide enrollment stuff
                this.ownerStatusContainer?.visibility = View.VISIBLE
                enrollButtonContainer?.visibility = View.GONE
                enrolledStatusContainer?.visibility = View.GONE
                
                Log.d("CreatedCourseAdapter", "Creator view: Hiding subscription UI for course: ${course.title}")
            } else {
                categoryTextView.text = "Tecnología"
                categoryTextView.setBackgroundColor(android.graphics.Color.parseColor("#333333"))

                // Hide menu button IMMEDIATELY for non-creators
                try {
                    val optionsMenuButton = findOptionsMenuButton(itemView)
                    optionsMenuButton?.visibility = View.GONE
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error hiding options menu button", e)
                }
                
                // Show subscription container for other users' courses
                creatorInfoContainer.visibility = View.VISIBLE
                
                // Hide owner status
                this.ownerStatusContainer?.visibility = View.GONE
                
                // Setup subscription data
                creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                
                // Load subscription data asynchronously
                loadSubscriptionData(course)
                
                // Check enrollment status asynchronously
                checkEnrollmentStatus(course)
                
                Log.d("CreatedCourseAdapter", "Non-creator view: Showing subscription UI for course: ${course.title} by ${course.username}")
            }

            // ASYNC: Fetch student count in background (non-blocking)
            studentsTextView.text = "..."
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    // Fetch enrolled count from progreso_estudiante table in Supabase
                    val enrolledCount = com.example.tareamov.service.SupabaseClient.countStudentsInCourse(course.id)
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        studentsTextView.text = if (enrolledCount == 1) "1 estudiante" else "$enrolledCount estudiantes"
                    }
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error fetching student count from Supabase", e)
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        studentsTextView.text = "0 estudiantes"
                    }
                }
            }

            Log.d("CreatedCourseAdapter", "Binding course: ${course.title} with URI: ${course.videoUriString ?: "null"}")

            // Reset views
            // videoView is not available in item_course_card layout
            thumbnailImageView.visibility = View.VISIBLE
            // stopAutoPlay() // No video playback in card view            // ASYNC: Load thumbnail in background (non-blocking)
            var thumbnailUri = course.thumbnailUri?.trim()
            if (!thumbnailUri.isNullOrEmpty()) {
                // Robust URL handling
                if (thumbnailUri!!.startsWith("/")) {
                    thumbnailUri = "${com.example.tareamov.service.BackendApiService.baseUrl}$thumbnailUri"
                } else if (!thumbnailUri!!.startsWith("http") && !thumbnailUri!!.startsWith("content://") && !thumbnailUri!!.startsWith("file://")) {
                     // Assume relative path missing leading slash
                     thumbnailUri = "${com.example.tareamov.service.BackendApiService.baseUrl}/$thumbnailUri"
                }

                try {
                    Log.d("CreatedCourseAdapter", "Loading thumbnail: $thumbnailUri")
                    Glide.with(context)
                        .load(thumbnailUri)
                        .placeholder(R.drawable.bg_course_placeholder_card)
                        .error(R.drawable.bg_course_placeholder_card)
                        .centerCrop()
                        .into(thumbnailImageView)
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error loading custom thumbnail", e)
                    thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)
                }
            } else {
                // Generate thumbnail asynchronously
                loadOrGenerateVideoThumbnail(course)
            }
        }
        
        /**
         * Check enrollment status and update UI
         */
        private fun checkEnrollmentStatus(course: VideoData) {
            // Default state: show enroll button, hide enrolled status
            enrollButtonContainer?.visibility = View.VISIBLE
            enrolledStatusContainer?.visibility = View.GONE
            
            enrollButton?.setOnClickListener {
                onEnrollClickListener?.invoke(course)
            }

            if (currentUsername == null) return

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = com.example.tareamov.data.AppDatabase.getDatabase(context)
                    val currentUserId = currentUserIdCached ?: com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername)
                    
                    if (currentUserId != null) {
                        val isEnrolled = db.progresoEstudianteDao().estaInscrito(currentUserId, course.id)
                        
                        withContext(Dispatchers.Main) {
                            if (isEnrolled) {
                                enrollButtonContainer?.visibility = View.GONE
                                enrolledStatusContainer?.visibility = View.VISIBLE
                            } else {
                                enrollButtonContainer?.visibility = View.VISIBLE
                                enrolledStatusContainer?.visibility = View.GONE
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error checking enrollment status", e)
                }
            }
        }
        
        /**
         * Load subscription data asynchronously
         */
        private fun loadSubscriptionData(course: VideoData) {
            if (currentUsername == null) {
                subscriberCountTextView.text = "0 suscriptores"
                subscribeButton.text = "Iniciar sesión"
                subscribeButton.isEnabled = false
                return
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = com.example.tareamov.data.AppDatabase.getDatabase(context)
                    
                    // Resolve creator ID from username
                    // Try local DB first, then Supabase
                    var creatorUser = db.usuarioDao().getUsuarioByUsername(course.username)
                    var creatorId = creatorUser?.id ?: -1L
                    
                    if (creatorId == -1L) {
                         creatorId = com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(course.username) ?: -1L
                    }
                    
                    // Fetch creator avatar from cache or Supabase
                    val creatorUsername = course.username
                    val creatorAvatarUrl = if (!creatorUsername.isNullOrEmpty()) {
                        creatorAvatarCache[creatorUsername]
                            ?: com.example.tareamov.service.SupabaseClient.fetchUsuarioAvatarByUsername(creatorUsername)?.also {
                                creatorAvatarCache[creatorUsername] = it
                            }
                    } else null
                    
                    if (creatorId != -1L) {
                        // Get subscriber count from Supabase (Priority)
                        val subscriberCount = try {
                            com.example.tareamov.service.SupabaseClient.fetchSubscriberCount(creatorId)
                        } catch (e: Exception) {
                            Log.e("CreatedCourseAdapter", "Error fetching subscriber count from Supabase, falling back to local", e)
                            db.subscriptionDao().getSubscriptionCountForCreator(creatorId)
                        }
                        
                        // Check if current user is subscribed to this creator
                        val currentUserId = currentUserIdCached ?: com.example.tareamov.service.SupabaseClient.getUserIdFromUsername(currentUsername)
                        
                        val isSubscribed = if (currentUserId != null) {
                            db.subscriptionDao().isUserSubscribedToCreator(currentUserId, creatorId)
                        } else false
                        
                        withContext(Dispatchers.Main) {
                            // Update creator avatar
                            if (!creatorAvatarUrl.isNullOrEmpty()) {
                                Glide.with(context)
                                    .load(creatorAvatarUrl)
                                    .placeholder(R.drawable.default_avatar)
                                    .error(R.drawable.default_avatar)
                                    .into(creatorAvatarImageView)
                                Log.d("CreatedCourseAdapter", "Loaded avatar for $creatorUsername: $creatorAvatarUrl")
                            } else {
                                creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                                Log.d("CreatedCourseAdapter", "No avatar found for $creatorUsername, using default")
                            }
                            
                            // Update subscriber count
                            val countText = if (subscriberCount == 1L) "1 suscriptor" else "$subscriberCount suscriptores"
                            subscriberCountTextView.text = countText
                            
                            // Update subscription button
                            if (isSubscribed) {
                                subscribeButton.text = "Suscrito"
                                subscribeButton.setBackgroundResource(R.drawable.button_subscribed)
                            } else {
                                subscribeButton.text = "Suscribirse"
                                subscribeButton.setBackgroundResource(R.drawable.button_premium)
                            }
                            
                            // Set button click listener
                            subscribeButton.setOnClickListener {
                                onSubscriptionClickListener?.invoke(course, isSubscribed)
                            }
                            
                            subscribeButton.isEnabled = true
                        }
                    } else {
                        // Even if creatorId is not found, still try to load avatar
                        withContext(Dispatchers.Main) {
                            if (!creatorAvatarUrl.isNullOrEmpty()) {
                                Glide.with(context)
                                    .load(creatorAvatarUrl)
                                    .placeholder(R.drawable.default_avatar)
                                    .error(R.drawable.default_avatar)
                                    .into(creatorAvatarImageView)
                            } else {
                                creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error loading subscription data", e)
                    withContext(Dispatchers.Main) {
                        subscriberCountTextView.text = "0 suscriptores"
                        subscribeButton.text = "Suscribirse"
                        subscribeButton.isEnabled = true
                        creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                    }
                }
            }
        }

        /**
         * Load existing thumbnail or generate new one - MODIFIED (No local generation)
         */
        private fun loadOrGenerateVideoThumbnail(course: VideoData) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // First, set YouTube-style gray placeholder
                    thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)

                    // Just try to load the thumbnail URI if it exists
                    if (!course.thumbnailUri.isNullOrEmpty()) {
                        loadThumbnailWithGlide(course.thumbnailUri, course)
                    } else {
                        // If no thumbnail, just keep placeholder
                        Log.d("CreatedCourseAdapter", "No thumbnail URI for: ${course.title}")
                    }
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error loading thumbnail", e)
                }
            }
        }

        /**
         * Load thumbnail using Glide with enhanced error handling
         */
        private fun loadThumbnailWithGlide(thumbnailUri: String, course: VideoData) {
            try {
                val requestOptions = RequestOptions()
                    .placeholder(R.drawable.bg_course_placeholder_card)
                    .error(R.drawable.bg_course_placeholder_card)
                    .centerCrop()
                    .diskCacheStrategy(DiskCacheStrategy.ALL)

                Glide.with(context)
                    .load(Uri.parse(thumbnailUri))
                    .apply(requestOptions)
                    .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                        override fun onLoadFailed(
                            e: com.bumptech.glide.load.engine.GlideException?,
                            model: Any?,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.e("CreatedCourseAdapter", "Glide failed to load thumbnail for: ${course.title}", e)
                            // Try to regenerate thumbnail if file-based URI failed
                            if (thumbnailUri.startsWith("file://")) {
                                regenerateThumbnailAsync(course)
                            }
                            return false // Let Glide handle the error (show error drawable)
                        }

                        override fun onResourceReady(
                            resource: android.graphics.drawable.Drawable,
                            model: Any,
                            target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                            dataSource: com.bumptech.glide.load.DataSource,
                            isFirstResource: Boolean
                        ): Boolean {
                            Log.d("CreatedCourseAdapter", "Successfully loaded thumbnail for: ${course.title}")
                            return false // Let Glide handle the success
                        }
                    })
                    .into(thumbnailImageView)

            } catch (e: Exception) {
                Log.e("CreatedCourseAdapter", "Error loading thumbnail with Glide", e)
                // Fallback to placeholder
                thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)
            }
        }

        /**
         * Regenerate thumbnail asynchronously when loading fails - REMOVED (Obsolete)
         */
        private fun regenerateThumbnailAsync(course: VideoData) {
            // Functionality removed as ThumbnailManager is obsolete
            Log.d("CreatedCourseAdapter", "regenerateThumbnailAsync called but functionality is removed")
        }

        /**
         * Check if current user can modify the given course (ViewHolder version)
         */
        private fun canUserModifyCourse(course: VideoData): Boolean {
            val canModify = currentUsername != null && currentUsername == course.username
            Log.d("CreatedCourseAdapter", "ViewHolder permission check for '${course.title}' - Current: '$currentUsername', Creator: '${course.username}', Can modify: $canModify")
            return canModify
        }

        /**
         * Play muted video preview in the thumbnail area
         */
        fun playPreview(videoUri: String) {
            if (videoPreview == null) return
            try {
                videoPreview.visibility = View.VISIBLE
                thumbnailImageView.visibility = View.INVISIBLE
                
                val uri = Uri.parse(videoUri)
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
                Log.e("CreatedCourseAdapter", "Error playing preview", e)
                stopPreview()
            }
        }

        /**
         * Stop video preview and show thumbnail
         */
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

        fun startAutoPlay() {
            currentCourse?.let { course ->
                Log.d("CreatedCourseAdapter", "Attempting to start autoplay for: ${course.title}")
                Log.d("CreatedCourseAdapter", "Video URI: ${course.videoUriString ?: "null"}")

                // Solo reproducir si hay un video válido
                if (!course.videoUriString.isNullOrEmpty() &&
                    !course.videoUriString!!.startsWith("content://media/external/video/dummy_")) {

                    Log.d("CreatedCourseAdapter", "Video URI is valid, starting playback sequence")

                    // Esperar 2 segundos antes de empezar la reproducción
                    thumbnailRunnable = Runnable {
                        startVideoPlayback(course)
                    }
                    handler.postDelayed(thumbnailRunnable!!, 2000)
                } else {
                    Log.d("CreatedCourseAdapter", "Video URI is invalid or dummy: ${course.videoUriString ?: "null"}")
                }
            }
        }

        fun startAutoPlayWithDelay() {
            // Detener cualquier reproducción anterior antes de iniciar nueva
            currentPlayingHolder?.stopAutoPlay()

            // Esperar un poco antes de iniciar para asegurar que la vista esté completamente cargada
            handler.postDelayed({
                if (currentPlayingHolder == null) {
                    startAutoPlay()
                }
            }, 500)
        }

        fun stopAutoPlay() {
            // Cancelar todos los runnables
            thumbnailRunnable?.let { handler.removeCallbacks(it) }
            videoRunnable?.let { handler.removeCallbacks(it) }
            stopVideoRunnable?.let { handler.removeCallbacks(it) }

            // No video view in card layout
            // Resetear vistas
            thumbnailImageView.visibility = View.VISIBLE

            if (currentPlayingHolder == this) {
                currentPlayingHolder = null
            }
        }        private fun startVideoPlayback(course: VideoData) {
            try {
                Log.d("CreatedCourseAdapter", "Starting video playback for: ${course.title}")

                // Detener cualquier reproducción anterior
                currentPlayingHolder?.stopAutoPlay()
                currentPlayingHolder = this@CourseViewHolder

                val videoUriString = course.videoUriString ?: return
                val uri = Uri.parse(videoUriString)
                Log.d("CreatedCourseAdapter", "Parsed URI: $uri")

                // Verificar si el archivo existe para URIs de archivos
                if (videoUriString.startsWith("file://")) {
                    val path = videoUriString.replace("file://", "")
                    val file = File(path)
                    if (!file.exists()) {
                        Log.e("CreatedCourseAdapter", "Video file does not exist: $path")
                        return
                    }
                    Log.d("CreatedCourseAdapter", "File exists: $path")
                }

                // Video playback disabled for card layout
                Log.d("CreatedCourseAdapter", "Video playback disabled in card layout")
            } catch (e: Exception) {
                Log.e("CreatedCourseAdapter", "Video playback disabled", e)
            }
        }

        private fun stopVideoAndShowThumbnail() {
            try {
                Log.d("CreatedCourseAdapter", "Video functionality disabled in card layout")
                thumbnailImageView.visibility = View.VISIBLE

                if (currentPlayingHolder == this@CourseViewHolder) {
                    currentPlayingHolder = null
                }

                // Limpiar callbacks
                stopVideoRunnable?.let { handler.removeCallbacks(it) }
            } catch (e: Exception) {
                Log.e("CreatedCourseAdapter", "Error in stopVideoAndShowThumbnail", e)
            }
        }

        private fun loadVideoThumbnail(course: VideoData) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // Intentar diferentes fuentes para el thumbnail
                    when {
                        // Si hay un thumbnail asociado con el curso (que puede provenir de un video)
                        !course.thumbnailUri.isNullOrEmpty() && isValidUri(course.thumbnailUri) -> {
                            Log.d("CreatedCourseAdapter", "Using course thumbnailUri: ${course.thumbnailUri}")
                            Glide.with(context)
                                .load(course.thumbnailUri)
                                .placeholder(R.drawable.bg_course_placeholder_card)
                                .error(R.drawable.bg_course_placeholder_card)
                                .centerCrop()
                                .into(thumbnailImageView)
                        }
                        // Si hay un video asociado, intentar extraer un frame
                        course.videoUriString != null && !course.videoUriString!!.startsWith("content://media/external/video/dummy_") && isValidUri(course.videoUriString!!) -> {
                            val uri = Uri.parse(course.videoUriString!!)
                            val thumbnail = withContext(Dispatchers.IO) {
                                extractThumbnailFromVideo(uri)
                            }

                            if (thumbnail != null) {
                                thumbnailImageView.setImageBitmap(thumbnail)
                                Log.d("CreatedCourseAdapter", "Thumbnail extracted from video: ${course.videoUriString ?: "null"}")
                            } else {
                                // Si no se pudo extraer un frame, usar Glide para cargar directamente el video como thumbnail
                                Glide.with(context)
                                    .load(uri)
                                    .placeholder(R.drawable.bg_course_placeholder_card)
                                    .error(R.drawable.bg_course_placeholder_card)
                                    .centerCrop()
                                    .into(thumbnailImageView)

                                Log.d("CreatedCourseAdapter", "Using Glide to load video as thumbnail: ${course.videoUriString ?: "null"}")
                            }
                        }
                        // Si hay una ruta de archivo local, intentar cargar el archivo
                        !course.localFilePath.isNullOrEmpty() -> {
                            val file = File(course.localFilePath)
                            if (file.exists() && file.canRead()) {
                                Glide.with(context)
                                    .load(file)
                                    .placeholder(R.drawable.bg_course_placeholder_card)
                                    .error(R.drawable.bg_course_placeholder_card)
                                    .centerCrop()
                                    .into(thumbnailImageView)

                                Log.d("CreatedCourseAdapter", "Using localFilePath as thumbnail: ${course.localFilePath}")
                            } else {
                                thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)
                                Log.d("CreatedCourseAdapter", "Local file not found or not readable, using placeholder: ${course.localFilePath}")
                            }
                        }
                        // Si todo lo demás falla, usar el placeholder
                        else -> {
                            thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)
                            Log.d("CreatedCourseAdapter", "Using placeholder for course: ${course.title}")
                        }
                    }                } catch (e: SecurityException) {
                    Log.w("CreatedCourseAdapter", "Permission denied when loading thumbnail for course ${course.title}: ${e.message}")
                    thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error loading thumbnail for course ${course.title}: ${e.message}")
                    thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)
                }
            }
        }

        private fun extractThumbnailFromVideo(uri: Uri): Bitmap? {
            val retriever = MediaMetadataRetriever()
            try {
                if (uri.toString().startsWith("file://")) {
                    val path = uri.toString().replace("file://", "")
                    val file = File(path)
                    if (file.exists()) {
                        retriever.setDataSource(path)
                    } else {
                        Log.e("CreatedCourseAdapter", "File does not exist: $path")
                        return null
                    }
                } else {
                    retriever.setDataSource(context, uri)
                }

                return retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (e: Exception) {
                Log.e("CreatedCourseAdapter", "Error extracting thumbnail", e)
                return null
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error releasing retriever", e)
                }
            }
        }

        private fun isValidUri(uriString: String?): Boolean {
            if (uriString.isNullOrEmpty()) return false

            return try {
                val uri = Uri.parse(uriString)
                when (uri.scheme?.lowercase()) {
                    "file" -> {
                        // Check if file exists and is readable
                        val file = File(uri.path ?: "")
                        file.exists() && file.canRead()
                    }
                    "content" -> {
                        // Only allow specific content providers, avoid Google Drive URIs
                        val authority = uri.authority
                        authority != null &&
                                !authority.contains("com.google.android.apps.docs") &&
                                !authority.contains("com.google.android.apps.drive")
                    }
                    "android.resource" -> true
                    "http", "https" -> true
                    else -> false
                }
            } catch (e: Exception) {
                false
            }
        }
    }
}
