package com.example.tareamov.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Button
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
import com.example.tareamov.data.AppDatabase
import android.util.Log

class CourseAdapter(
    private val context: Context,
    private var courses: List<Course>,
    private val onCourseClickListener: (Course) -> Unit,
    private val currentUsername: String? = null, // Current logged-in user for permission checks
    private val onSubscriptionClickListener: ((Course, Boolean) -> Unit)? = null, // Subscription callback
    private val onEditClickListener: ((Course) -> Unit)? = null, // Edit callback
    private val onDeleteClickListener: ((Course) -> Unit)? = null, // Delete callback
    private val onThumbnailChangeClickListener: ((Course) -> Unit)? = null // Thumbnail change callback
) : RecyclerView.Adapter<CourseAdapter.CourseViewHolder>() {

    class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnailImageView: ImageView = itemView.findViewById(R.id.courseThumbnailImageView)
        val titleTextView: TextView = itemView.findViewById(R.id.courseTitleTextView)
        val descriptionTextView: TextView = itemView.findViewById(R.id.courseDescriptionTextView)
        val creatorTextView: TextView = itemView.findViewById(R.id.courseCreatorTextView)
        val categoryTextView: TextView = itemView.findViewById(R.id.courseCategoryTextView)
        val ratingTextView: TextView = itemView.findViewById(R.id.courseRatingTextView)
        val priceTextView: TextView = itemView.findViewById(R.id.coursePriceTextView)
        val originalPriceTextView: TextView = itemView.findViewById(R.id.originalPriceTextView)
        val enrollmentTextView: TextView = itemView.findViewById(R.id.courseEnrollmentTextView)
        val premiumBadge: View = itemView.findViewById(R.id.premiumBadge)
        val overlayText: TextView = itemView.findViewById(R.id.overlayText)
        // Subscription elements
        val creatorAvatarImageView: de.hdodenhof.circleimageview.CircleImageView = itemView.findViewById(R.id.creatorAvatarImageView)
        val subscriberCountTextView: TextView = itemView.findViewById(R.id.subscriberCountTextView)
        val subscribeButton: Button = itemView.findViewById(R.id.subscribeButton)
        // CRUD action elements
        val actionButtonsContainer: android.widget.LinearLayout? = itemView.findViewById(R.id.actionButtonsContainer)
        val editButton: android.widget.ImageButton? = itemView.findViewById(R.id.editButton)
        val deleteButton: android.widget.ImageButton? = itemView.findViewById(R.id.deleteButton)
        val changeThumbnailButton: android.widget.ImageButton? = itemView.findViewById(R.id.changeThumbnailButton)
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
        holder.creatorTextView.text = course.creatorUsername
        holder.categoryTextView.text = course.category ?: "General"
        holder.ratingTextView.text = String.format("%.1f", course.rating)
        holder.enrollmentTextView.text = "${course.enrollmentCount} estudiantes"

        // Handle subscription elements and CRUD actions based on user permissions
        val isCreator = canUserModifyCourse(course)
        val creatorInfoContainer = holder.itemView.findViewById<android.widget.LinearLayout>(R.id.creatorInfoContainer)
        
        if (isCreator) {
            // Hide subscription info for course creators
            creatorInfoContainer?.visibility = View.GONE
            
            // Show CRUD action buttons for creators
            holder.actionButtonsContainer?.visibility = View.VISIBLE
            
            // Set up CRUD button click listeners
            holder.editButton?.setOnClickListener {
                onEditClickListener?.invoke(course)
            }
            
            holder.deleteButton?.setOnClickListener {
                onDeleteClickListener?.invoke(course)
            }
            
            holder.changeThumbnailButton?.setOnClickListener {
                onThumbnailChangeClickListener?.invoke(course)
            }
        } else {
            // Hide CRUD actions for non-creators
            holder.actionButtonsContainer?.visibility = View.GONE
            
            // Show subscription info for other users' courses
            creatorInfoContainer?.visibility = View.VISIBLE
            
            // Set creator info
            holder.creatorTextView.text = course.creatorUsername ?: "Creador desconocido"
            
            // Load creator avatar (default for now)
            holder.creatorAvatarImageView.setImageResource(R.drawable.default_avatar)
            
            // Load subscription data asynchronously
            loadSubscriptionData(holder, course)
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
        if (!course.thumbnailUri.isNullOrEmpty()) {
            // Hide overlay text when real thumbnail is available
            holder.overlayText.visibility = View.GONE
            Glide.with(context)
                .load(course.thumbnailUri)
                .apply(RequestOptions().transform(RoundedCorners(16)))
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(holder.thumbnailImageView)
        } else {
            // Show overlay text when using default placeholder
            holder.overlayText.visibility = View.VISIBLE
            holder.thumbnailImageView.setImageResource(android.R.drawable.ic_menu_gallery)
        }

        // Apply dark mode colors to text views
        applyDarkModeTextColors(holder)

        // Set click listener
        holder.itemView.setOnClickListener {
            onCourseClickListener(course)
        }
    }

    override fun getItemCount(): Int = courses.size

    fun updateCourses(newCourses: List<Course>) {
        courses = newCourses
        notifyDataSetChanged()
    }

    /**
     * Check if current user can modify the given course (i.e., is the creator)
     */
    private fun canUserModifyCourse(course: Course): Boolean {
        val canModify = currentUsername != null && currentUsername == course.creatorUsername
        return canModify
    }

    /**
     * Load subscription data asynchronously
     */
    private fun loadSubscriptionData(holder: CourseViewHolder, course: Course) {
        if (currentUsername == null) {
            // No user logged in
            holder.subscriberCountTextView.text = "0 suscriptores"
            holder.subscribeButton.text = "Iniciar sesión"
            holder.subscribeButton.isEnabled = false
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getDatabase(context)
                
                // Get subscriber count for this creator
                val subscriberCount = db.subscriptionDao().getSubscriptionCountForCreator(course.creatorUsername ?: "")
                
                // Check if current user is subscribed to this creator
                val isSubscribed = db.subscriptionDao().isUserSubscribedToCreator(currentUsername!!, course.creatorUsername ?: "")
                
                withContext(Dispatchers.Main) {
                    // Update subscriber count
                    val countText = if (subscriberCount == 1) "1 suscriptor" else "$subscriberCount suscriptores"
                    holder.subscriberCountTextView.text = countText
                    
                    // Update subscription button
                    if (isSubscribed) {
                        holder.subscribeButton.text = "Suscrito"
                        holder.subscribeButton.setBackgroundResource(R.drawable.button_subscribed)
                    } else {
                        holder.subscribeButton.text = "Suscribirse"
                        holder.subscribeButton.setBackgroundResource(R.drawable.button_premium)
                    }
                    
                    // Set button click listener
                    holder.subscribeButton.setOnClickListener {
                        onSubscriptionClickListener?.invoke(course, isSubscribed)
                    }
                    
                    holder.subscribeButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e("CourseAdapter", "Error loading subscription data", e)
                withContext(Dispatchers.Main) {
                    holder.subscriberCountTextView.text = "0 suscriptores"
                    holder.subscribeButton.text = "Suscribirse"
                    holder.subscribeButton.isEnabled = true
                }
            }
        }
    }

    private fun applyDarkModeTheme(view: View) {
        // Set dark background for the card
        view.setBackgroundColor(ContextCompat.getColor(context, R.color.dark_card_background))
        
        // Apply rounded corners and elevation
        view.elevation = 8f
        view.clipToOutline = true
    }

    private fun applyDarkModeTextColors(holder: CourseViewHolder) {
        // Primary text color (white/light gray)
        val primaryTextColor = ContextCompat.getColor(context, R.color.dark_primary_text)
        val secondaryTextColor = ContextCompat.getColor(context, R.color.dark_secondary_text)
        val accentColor = ContextCompat.getColor(context, R.color.purple_500)

        holder.titleTextView.setTextColor(primaryTextColor)
        holder.descriptionTextView.setTextColor(secondaryTextColor)
        holder.creatorTextView.setTextColor(primaryTextColor)
        holder.categoryTextView.setTextColor(accentColor)
        holder.ratingTextView.setTextColor(ContextCompat.getColor(context, R.color.rating_color))
        holder.enrollmentTextView.setTextColor(secondaryTextColor)
        holder.priceTextView.setTextColor(accentColor)
        // Subscription elements already have colors defined in layout
        holder.subscriberCountTextView.setTextColor(ContextCompat.getColor(context, R.color.purple_500))
    }
}
