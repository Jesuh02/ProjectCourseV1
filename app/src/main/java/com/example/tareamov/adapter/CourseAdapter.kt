package com.example.tareamov.adapter

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.data.entity.Course
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions

class CourseAdapter(
    private val context: Context,
    private var courses: List<Course>,
    private val onCourseClickListener: (Course) -> Unit
) : RecyclerView.Adapter<CourseAdapter.CourseViewHolder>() {

    class CourseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnailImageView: ImageView = itemView.findViewById(R.id.courseThumbnailImageView)
        val titleTextView: TextView = itemView.findViewById(R.id.courseTitleTextView)
        val descriptionTextView: TextView = itemView.findViewById(R.id.courseDescriptionTextView)
        val creatorTextView: TextView = itemView.findViewById(R.id.courseCreatorTextView)
        val categoryTextView: TextView = itemView.findViewById(R.id.courseCategoryTextView)
        val ratingTextView: TextView = itemView.findViewById(R.id.courseRatingTextView)
        val priceTextView: TextView = itemView.findViewById(R.id.coursePriceTextView)
        val enrollmentTextView: TextView = itemView.findViewById(R.id.courseEnrollmentTextView)
        val premiumBadge: View = itemView.findViewById(R.id.premiumBadge)
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
        holder.creatorTextView.text = "Por: ${course.creatorUsername}"
        holder.categoryTextView.text = course.category ?: "General"
        holder.ratingTextView.text = "★ ${String.format("%.1f", course.rating)}"
        holder.enrollmentTextView.text = "${course.enrollmentCount} estudiantes"

        // Set price
        if (course.isPremium && course.price > 0) {
            holder.priceTextView.text = "$${String.format("%.2f", course.price)}"
            holder.priceTextView.visibility = View.VISIBLE
            holder.premiumBadge.visibility = View.VISIBLE
        } else {
            holder.priceTextView.text = "Gratis"
            holder.priceTextView.visibility = View.VISIBLE
            holder.premiumBadge.visibility = View.GONE
        }

        // Load thumbnail image
        if (!course.thumbnailUri.isNullOrEmpty()) {
            Glide.with(context)
                .load(course.thumbnailUri)
                .apply(RequestOptions().transform(RoundedCorners(16)))
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.ic_menu_gallery)
                .into(holder.thumbnailImageView)
        } else {
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
        holder.creatorTextView.setTextColor(secondaryTextColor)
        holder.categoryTextView.setTextColor(accentColor)
        holder.ratingTextView.setTextColor(ContextCompat.getColor(context, R.color.rating_color))
        holder.enrollmentTextView.setTextColor(secondaryTextColor)
        holder.priceTextView.setTextColor(accentColor)
    }
}
