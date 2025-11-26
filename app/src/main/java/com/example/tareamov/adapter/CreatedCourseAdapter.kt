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
import com.example.tareamov.util.ThumbnailManager
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
    private val onChangeThumbnailListener: ((VideoData) -> Unit)? = null // Callback for changing thumbnail
) : RecyclerView.Adapter<CreatedCourseAdapter.CourseViewHolder>() {

    private var currentPlayingHolder: CourseViewHolder? = null
    private val thumbnailManager = ThumbnailManager(context)

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
    }

    override fun getItemCount(): Int = courses.size    /**
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
     * Force regenerate thumbnail for a specific course
     */
    fun forceRegenerateThumbnail(courseId: Long) {
        val course = courses.find { it.id == courseId }
        if (course != null) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val videoUri = course.getBestVideoUri()?.toString() ?: course.videoUriString
                    if (!videoUri.isNullOrEmpty()) {
                        // Delete existing thumbnail first
                        thumbnailManager.deleteThumbnail(courseId)
                        // Generate new thumbnail
                        thumbnailManager.generateAndSaveThumbnail(videoUri, courseId)

                        withContext(Dispatchers.Main) {
                            notifyDataSetChanged()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error regenerating thumbnail for course $courseId", e)
                }
            }
        }
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

        // Create PopupMenu with dark theme wrapper
        val wrapper = androidx.appcompat.view.ContextThemeWrapper(context, R.style.DarkPopupMenuTheme)
        val popup = androidx.appcompat.widget.PopupMenu(wrapper, view)
        popup.inflate(R.menu.course_options_menu)

        // Apply dark theme to popup menu
        try {
            val fieldMPopup = androidx.appcompat.widget.PopupMenu::class.java.getDeclaredField("mPopup")
            fieldMPopup.isAccessible = true
            val mPopup = fieldMPopup.get(popup)
            mPopup.javaClass
                .getDeclaredMethod("setForceShowIcon", Boolean::class.javaPrimitiveType)
                .invoke(mPopup, true)
        } catch (e: Exception) {
            Log.e("CreatedCourseAdapter", "Error showing menu icons", e)
        }

        popup.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.menu_edit_course -> {
                    editCourse(course)
                    true
                }
                R.id.menu_change_thumbnail -> {
                    changeThumbnail(course)
                    true
                }
                R.id.menu_delete_course -> {
                    deleteCourse(course)
                    true
                }
                else -> false
            }
        }

        popup.show()
        Log.d("CreatedCourseAdapter", "Dark theme course context menu shown for creator: ${course.title}")
    }

    /**
     * Edit course details
     */
    private fun editCourse(course: VideoData) {
        Log.d("CreatedCourseAdapter", "Edit course requested: ${course.title}")
        onEditCourseListener?.invoke(course)
    }

    /**
     * Change course thumbnail
     */
    private fun changeThumbnail(course: VideoData) {
        Log.d("CreatedCourseAdapter", "Change thumbnail requested: ${course.title}")
        onChangeThumbnailListener?.invoke(course)
    }

    /**
     * Delete course after confirmation
     */
    private fun deleteCourse(course: VideoData) {
        Log.d("CreatedCourseAdapter", "Delete course requested: ${course.title}")

        // Create AlertDialog with dark theme
        val dialogBuilder = androidx.appcompat.app.AlertDialog.Builder(
            androidx.appcompat.view.ContextThemeWrapper(context, R.style.DarkAlertDialogTheme)
        )

        dialogBuilder
            .setTitle("⚠️ Eliminar Curso")
            .setMessage("¿Estás seguro de que quieres eliminar permanentemente el curso \"${course.title}\"?\n\n" +
                    "🚨 Esta acción no se puede deshacer y se eliminarán:\n\n" +
                    "• 📚 El curso completo\n" +
                    "• 🎥 Los videos asociados\n" +
                    "• 🖼️ Las miniaturas\n" +
                    "• 📊 Todos los datos relacionados")
            .setPositiveButton("🗑️ Eliminar") { _, _ ->
                onDeleteCourseListener?.invoke(course)
                Log.d("CreatedCourseAdapter", "Course deletion confirmed: ${course.title}")
            }
            .setNegativeButton("❌ Cancelar", null)
            .setCancelable(true)

        val dialog = dialogBuilder.create()

        // Apply additional dark theme styling
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(R.drawable.dark_dialog_background)
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)?.apply {
                setTextColor(android.graphics.Color.parseColor("#FF4444")) // Red for delete
                textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE)?.apply {
                setTextColor(android.graphics.Color.parseColor("#A259FF")) // Purple for cancel
                textSize = 16f
            }
        }

        dialog.show()
    }

    /**
     * ViewHolder para mostrar un curso individual
     */
    inner class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnailImageView: ImageView = itemView.findViewById(R.id.courseThumbnailImageView)
        private val videoView: VideoView? = null // VideoView is not in item_course_card.xml
        private val titleTextView: TextView = itemView.findViewById(R.id.courseTitleTextView)
        private val studentsTextView: TextView = itemView.findViewById(R.id.courseEnrollmentTextView)
        private val categoryTextView: TextView = itemView.findViewById(R.id.courseCategoryTextView)
        private val authorTextView: TextView = itemView.findViewById(R.id.courseCreatorTextView)
        private val priceTextView: TextView = itemView.findViewById(R.id.coursePriceTextView)
        
        // Subscription elements
        private val creatorInfoContainer: LinearLayout = itemView.findViewById(R.id.creatorInfoContainer)
        private val creatorAvatarImageView: de.hdodenhof.circleimageview.CircleImageView = itemView.findViewById(R.id.creatorAvatarImageView)
        private val subscriberCountTextView: TextView = itemView.findViewById(R.id.subscriberCountTextView)
        private val subscribeButton: Button = itemView.findViewById(R.id.subscribeButton)

        // New menu button next to category
        // private val optionsMenuButton: ImageView? = itemView.findViewById(R.id.courseOptionsMenuButton)

        private val handler = Handler(Looper.getMainLooper())
        private var thumbnailRunnable: Runnable? = null
        private var videoRunnable: Runnable? = null
        private var stopVideoRunnable: Runnable? = null
        private var currentCourse: VideoData? = null

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    Log.d("CreatedCourseAdapter", "Course clicked: ID=${courses[position].id}, Title=${courses[position].title}")
                    onCourseClickListener(courses[position])
                }
            }

            // Add click listener for the new options menu button next to category
            try {
                // Get the courseOptionsMenuButton using its ID from the current itemView
                val resourceId = itemView.context.resources.getIdentifier(
                    "courseOptionsMenuButton",
                    "id",
                    itemView.context.packageName
                )
                if (resourceId != 0) {
                    val optionsMenuButton = itemView.findViewById<ImageView>(resourceId)
                    optionsMenuButton?.setOnClickListener {
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
                    Log.w("CreatedCourseAdapter", "Could not find courseOptionsMenuButton resource ID")
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
                    val resourceId = itemView.context.resources.getIdentifier(
                        "courseOptionsMenuButton",
                        "id",
                        itemView.context.packageName
                    )
                    if (resourceId != 0) {
                        val optionsMenuButton = itemView.findViewById<ImageView>(resourceId)
                        optionsMenuButton?.visibility = View.VISIBLE
                        // Remove animation for instant display
                        optionsMenuButton?.alpha = 1f
                    }
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error showing options menu button", e)
                }
                
                // Hide subscription container for course creators
                creatorInfoContainer.visibility = View.GONE
                Log.d("CreatedCourseAdapter", "Creator view: Hiding subscription UI for course: ${course.title}")
            } else {
                categoryTextView.text = "Tecnología"
                categoryTextView.setBackgroundColor(android.graphics.Color.parseColor("#333333"))

                // Hide menu button IMMEDIATELY for non-creators
                try {
                    val resourceId = itemView.context.resources.getIdentifier(
                        "courseOptionsMenuButton",
                        "id",
                        itemView.context.packageName
                    )
                    if (resourceId != 0) {
                        val optionsMenuButton = itemView.findViewById<ImageView>(resourceId)
                        optionsMenuButton?.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error hiding options menu button", e)
                }
                
                // Show subscription container for other users' courses
                creatorInfoContainer.visibility = View.VISIBLE
                
                // Setup subscription data
                creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
                subscriberCountTextView.text = "0 suscriptores" // Placeholder
                subscribeButton.text = "Suscribirse"
                subscribeButton.isEnabled = true
                
                Log.d("CreatedCourseAdapter", "Non-creator view: Showing subscription UI for course: ${course.title} by ${course.username}")
            }

            // ASYNC: Fetch student count in background (non-blocking)
            studentsTextView.text = "..."
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val db = com.example.tareamov.data.AppDatabase.getDatabase(itemView.context)
                    // Resolve creator ID from username since VideoData doesn't have creatorUserId
                    val creatorUser = db.usuarioDao().getUsuarioByUsername(course.username)
                    val creatorId = creatorUser?.id ?: -1L
                    
                    val count = if (creatorId > 0) db.subscriptionDao().getSubscriptionCountForCreator(creatorId) else 0
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        studentsTextView.text = if (count == 1) "1 estudiante" else "${count} estudiantes"
                    }
                } catch (e: Exception) {
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
            if (!course.thumbnailUri.isNullOrEmpty()) {
                try {
                    Glide.with(context)
                        .load(Uri.parse(course.thumbnailUri))
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
         * Load existing thumbnail or generate new one
         */
        private fun loadOrGenerateVideoThumbnail(course: VideoData) {
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    // First, set YouTube-style gray placeholder
                    thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)

                    // Check if thumbnail already exists
                    val existingThumbnailPath = withContext(Dispatchers.IO) {
                        if (thumbnailManager.thumbnailExists(course.id)) {
                            thumbnailManager.getThumbnailPath(course.id)
                        } else {
                            null
                        }
                    }

                    if (existingThumbnailPath != null) {
                        // Load existing thumbnail
                        loadThumbnailWithGlide("file://$existingThumbnailPath", course)
                        Log.d("CreatedCourseAdapter", "Loaded existing thumbnail for: ${course.title}")
                    } else {
                        // Generate new thumbnail
                        val videoUri = course.getBestVideoUri()?.toString() ?: course.videoUriString
                        if (!videoUri.isNullOrEmpty()) {
                            val generatedThumbnailPath = withContext(Dispatchers.IO) {
                                thumbnailManager.ensureThumbnailExists(videoUri, course.id)
                            }

                            if (generatedThumbnailPath != null) {
                                loadThumbnailWithGlide("file://$generatedThumbnailPath", course)
                                Log.d("CreatedCourseAdapter", "Generated and loaded new thumbnail for: ${course.title}")
                            } else {
                                Log.w("CreatedCourseAdapter", "Could not generate thumbnail for: ${course.title}")
                                // Keep purple book placeholder
                            }
                        } else {
                            Log.w("CreatedCourseAdapter", "No valid video URI for thumbnail generation: ${course.title}")
                            // Keep purple book placeholder
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error in loadOrGenerateVideoThumbnail for ${course.title}", e)
                    // Keep purple book placeholder image
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
         * Regenerate thumbnail asynchronously when loading fails
         */
        private fun regenerateThumbnailAsync(course: VideoData) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Log.d("CreatedCourseAdapter", "Attempting to regenerate thumbnail for: ${course.title}")
                    val videoUri = course.getBestVideoUri()?.toString() ?: course.videoUriString
                    if (!videoUri.isNullOrEmpty()) {
                        // Delete the broken thumbnail first
                        thumbnailManager.deleteThumbnail(course.id)
                        // Generate new thumbnail
                        val newThumbnailPath = thumbnailManager.generateAndSaveThumbnail(videoUri, course.id)
                        if (newThumbnailPath != null) {
                            withContext(Dispatchers.Main) {
                                loadThumbnailWithGlide("file://$newThumbnailPath", course)
                                Log.d("CreatedCourseAdapter", "Successfully regenerated thumbnail for: ${course.title}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CreatedCourseAdapter", "Error regenerating thumbnail for: ${course.title}", e)
                }
            }
        }

        /**
         * Check if current user can modify the given course (ViewHolder version)
         */
        private fun canUserModifyCourse(course: VideoData): Boolean {
            val canModify = currentUsername != null && currentUsername == course.username
            Log.d("CreatedCourseAdapter", "ViewHolder permission check for '${course.title}' - Current: '$currentUsername', Creator: '${course.username}', Can modify: $canModify")
            return canModify
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
                return null            } finally {
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
