package com.example.tareamov.adapter

import android.annotation.SuppressLint
import android.content.Context
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.Subject

data class SubjectWithStats(
    val subject: Subject,
    val taskCount: Int = 0,
    val progress: Int = 0
)

class SubjectAdapter(
    private val context: Context,
    private var subjects: List<Subject>,
    private val onSubjectClick: (Subject) -> Unit,
    private val onSubjectLongClick: ((Subject) -> Unit)? = null
) : RecyclerView.Adapter<SubjectAdapter.SubjectViewHolder>() {

    var canModify: Boolean = false
    var onEditClick: ((Subject) -> Unit)? = null
    var onDeleteClick: ((Subject) -> Unit)? = null

    private var subjectStats: Map<Long, SubjectWithStats> = emptyMap()
    private val categoryColors = listOf(
        R.drawable.bg_category_icon_purple,
        R.drawable.bg_category_icon_blue,
        R.drawable.bg_category_icon_green,
        R.drawable.bg_category_icon_orange
    )
    private val categoryIcons = listOf(
        R.drawable.ic_code,
        R.drawable.ic_chart_bar,
        R.drawable.ic_book,
        R.drawable.ic_brain
    )

    class SubjectViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val cardContainer: View = itemView.findViewById(R.id.cardContainer)
        val thumbnailImageView: ImageView = itemView.findViewById(R.id.subjectThumbnailImageView)
        val titleTextView: TextView = itemView.findViewById(R.id.subjectTitleTextView)
        val descriptionTextView: TextView = itemView.findViewById(R.id.subjectDescriptionTextView)
        val moreOptionsContainer: FrameLayout = itemView.findViewById(R.id.moreOptionsContainer)
        val moreOptionsButton: ImageButton = itemView.findViewById(R.id.subjectMoreOptionsButton)
        val categoryIconContainer: FrameLayout = itemView.findViewById(R.id.categoryIconContainer)
        val categoryIconImageView: ImageView = itemView.findViewById(R.id.categoryIconImageView)
        val progressTrack: View = itemView.findViewById(R.id.progressTrack)
        val progressFill: View = itemView.findViewById(R.id.progressFill)
        val progressTextView: TextView = itemView.findViewById(R.id.progressTextView)
        val taskCountContainer: LinearLayout = itemView.findViewById(R.id.taskCountContainer)
        val taskCountTextView: TextView = itemView.findViewById(R.id.taskCountTextView)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SubjectViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_subject_card, parent, false)
        return SubjectViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: SubjectViewHolder, position: Int) {
        val subject = subjects[position]
        val stats = subjectStats[subject.id]

        holder.titleTextView.text = subject.name
        holder.descriptionTextView.text = subject.description

        if (!subject.thumbnailUrl.isNullOrBlank()) {
            Glide.with(context)
                .load(subject.thumbnailUrl)
                .centerCrop()
                .placeholder(R.drawable.bg_course_placeholder_card)
                .into(holder.thumbnailImageView)
        } else {
            holder.thumbnailImageView.setImageResource(R.drawable.bg_course_placeholder_card)
        }

        val colorIndex = position % categoryColors.size
        holder.categoryIconContainer.setBackgroundResource(categoryColors[colorIndex])
        holder.categoryIconImageView.setImageResource(categoryIcons[colorIndex])
        animateCategoryIcon(holder.categoryIconContainer)

        val taskCount = stats?.taskCount ?: 0
        holder.taskCountTextView.text = "$taskCount tarea${if (taskCount != 1) "s" else ""}"

        val progress = stats?.progress ?: 0
        holder.progressTextView.text = "$progress%"
        animateProgressBar(holder.progressFill, progress)

        if (canModify) {
            holder.moreOptionsContainer.visibility = View.VISIBLE
            holder.moreOptionsButton.setOnClickListener { showPopupMenu(it, subject) }
        } else {
            holder.moreOptionsContainer.visibility = View.GONE
        }

        setupTouchAnimation(holder)

        holder.itemView.setOnClickListener { onSubjectClick(subject) }
        holder.itemView.setOnLongClickListener {
            onSubjectLongClick?.invoke(subject)
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchAnimation(holder: SubjectViewHolder) {
        val pressAnim = AnimationUtils.loadAnimation(context, R.anim.card_press_down)
        val releaseAnim = AnimationUtils.loadAnimation(context, R.anim.card_press_up)

        holder.cardContainer.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.startAnimation(pressAnim)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.startAnimation(releaseAnim)
                }
            }
            false
        }
    }

    private fun animateCategoryIcon(container: View) {
        container.alpha = 0f
        container.scaleX = 0f
        container.scaleY = 0f
        container.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(400)
            .setStartDelay(200)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
    }

    private fun animateProgressBar(progressFill: View, progress: Int) {
        val params = progressFill.layoutParams
        val trackWidth = 80 * context.resources.displayMetrics.density
        val targetWidth = (trackWidth * progress / 100).toInt()

        progressFill.post {
            params.width = 0
            progressFill.layoutParams = params

            progressFill.animate()
                .setDuration(800)
                .setStartDelay(300)
                .setInterpolator(OvershootInterpolator(0.5f))
                .setUpdateListener { animation ->
                    params.width = (targetWidth * animation.animatedFraction).toInt()
                    progressFill.layoutParams = params
                }
                .start()
        }
    }

    private fun showPopupMenu(anchor: View, subject: Subject) {
        val popup = PopupMenu(ContextThemeWrapper(context, R.style.DarkPopupMenuThemeOverlay), anchor, Gravity.END)
        popup.menu.add(0, 1, 0, "✏️ Modificar")
        popup.menu.add(0, 2, 1, "🗑️ Eliminar")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { onEditClick?.invoke(subject); true }
                2 -> { onDeleteClick?.invoke(subject); true }
                else -> false
            }
        }
        popup.show()
    }

    override fun getItemCount(): Int = subjects.size

    fun updateSubjects(newSubjects: List<Subject>) {
        subjects = newSubjects
        notifyDataSetChanged()
    }

    fun updateSubjectsWithStats(newSubjects: List<Subject>, stats: Map<Long, SubjectWithStats>) {
        subjects = newSubjects
        subjectStats = stats
        notifyDataSetChanged()
    }

    fun updateStats(stats: Map<Long, SubjectWithStats>) {
        subjectStats = stats
        notifyDataSetChanged()
    }
}
