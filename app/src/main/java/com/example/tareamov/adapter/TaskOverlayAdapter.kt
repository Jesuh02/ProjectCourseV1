package com.example.tareamov.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.view.animation.LinearInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R

data class TaskItem(
    val taskId: Long,
    val taskName: String,
    val taskDescription: String,
    val topicName: String,
    val index: Int,
    val studentUsername: String? = null,
    val averageGrade: String? = null
)

class TaskOverlayAdapter(
    private var tasks: List<TaskItem>,
    private val onTaskClick: (TaskItem) -> Unit
) : RecyclerView.Adapter<TaskOverlayAdapter.TaskViewHolder>() {

    private var isLoading = false

    fun setLoading(loading: Boolean) {
        isLoading = loading
        notifyDataSetChanged()
    }

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val topicNameTextView: TextView = itemView.findViewById(R.id.topicNameTextView)
        private val taskNameTextView: TextView = itemView.findViewById(R.id.taskNameTextView)
        private val taskDescriptionTextView: TextView = itemView.findViewById(R.id.taskDescriptionTextView)
        private val taskIndexTextView: TextView = itemView.findViewById(R.id.taskIndexTextView)
        
        private val defaultTitleColor = taskNameTextView.currentTextColor
        private val defaultTopicColor = topicNameTextView.currentTextColor
        private val defaultDescColor = taskDescriptionTextView.currentTextColor
        private var shimmerAnimator: ValueAnimator? = null

        fun bindSkeleton() {
            // Create dark skeleton backgrounds matching the dark theme
            setupSkeletonBar(topicNameTextView, 120) // Short bar for topic
            setupSkeletonBar(taskNameTextView, 200) // Medium bar for title
            setupSkeletonBar(taskDescriptionTextView, 280) // Long bar for description
            
            taskIndexTextView.text = ""
            taskIndexTextView.background = null
            
            itemView.setOnClickListener(null)
            itemView.isClickable = false
            
            startShimmerAnimation()
        }

        private fun setupSkeletonBar(view: TextView, width: Int) {
            view.text = ""
            val params = view.layoutParams
            params.width = (width * view.context.resources.displayMetrics.density).toInt()
            params.height = (16 * view.context.resources.displayMetrics.density).toInt()
            view.layoutParams = params
            
            // Create rounded dark skeleton bar
            val skeletonDrawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * view.context.resources.displayMetrics.density
                setColor(Color.parseColor("#2A2A3E")) // Dark skeleton color
            }
            view.background = skeletonDrawable
        }

        private fun startShimmerAnimation() {
            shimmerAnimator?.cancel()
            
            val skeletonViews = listOf(topicNameTextView, taskNameTextView, taskDescriptionTextView)
            val baseColor = Color.parseColor("#2A2A3E")
            val highlightColor = Color.parseColor("#3A3A4E")
            
            shimmerAnimator = ValueAnimator.ofFloat(0f, 1f, 0f).apply {
                duration = 1500
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                
                addUpdateListener { animator ->
                    val fraction = animator.animatedValue as Float
                    val currentColor = blendColors(baseColor, highlightColor, fraction)
                    
                    skeletonViews.forEach { view ->
                        (view.background as? GradientDrawable)?.setColor(currentColor)
                    }
                }
                start()
            }
        }
        
        private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
            val inverseRatio = 1f - ratio
            val r = (Color.red(color1) * inverseRatio + Color.red(color2) * ratio).toInt()
            val g = (Color.green(color1) * inverseRatio + Color.green(color2) * ratio).toInt()
            val b = (Color.blue(color1) * inverseRatio + Color.blue(color2) * ratio).toInt()
            return Color.rgb(r, g, b)
        }

        private fun restoreView(view: TextView, color: Int) {
            view.setTextColor(color)
            view.background = null
            val params = view.layoutParams
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            view.layoutParams = params
        }

        fun bind(task: TaskItem) {
            shimmerAnimator?.cancel()
            itemView.alpha = 1.0f
            
            restoreView(taskNameTextView, defaultTitleColor)
            restoreView(topicNameTextView, defaultTopicColor)
            restoreView(taskDescriptionTextView, defaultDescColor)

            // Si tiene studentUsername, es una entrega de estudiante
            if (task.studentUsername != null) {
                // Mostrar: "👤 username" como encabezado
                topicNameTextView.text = "👤 ${task.studentUsername}"
                taskNameTextView.text = task.taskName
                taskDescriptionTextView.text = task.taskDescription
            } else {
                topicNameTextView.text = task.topicName
                taskNameTextView.text = task.taskName
                taskDescriptionTextView.text = if (task.taskDescription.isNotEmpty() && task.taskDescription != "Sin descripción") {
                    task.taskDescription
                } else {
                    "Sin descripción disponible"
                }
            }
            
            taskIndexTextView.text = "#${task.index}"

            itemView.setOnClickListener {
                onTaskClick(task)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_task_overlay, parent, false)
        return TaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: TaskViewHolder, position: Int) {
        if (isLoading) {
            holder.bindSkeleton()
        } else {
            holder.bind(tasks[position])
        }
    }

    override fun getItemCount(): Int = if (isLoading) 4 else tasks.size

    fun updateTasks(newTasks: List<TaskItem>) {
        tasks = newTasks
        notifyDataSetChanged()
    }
}
