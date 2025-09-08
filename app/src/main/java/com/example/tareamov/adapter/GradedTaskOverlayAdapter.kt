package com.example.tareamov.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R

data class GradedTaskItem(
    val taskId: Long,
    val taskName: String,
    val taskDescription: String,
    val topicName: String,
    val index: Int,
    val grade: String,
    val feedback: String
)

class GradedTaskOverlayAdapter(
    private var gradedTasks: List<GradedTaskItem>,
    private val onTaskClick: (GradedTaskItem) -> Unit
) : RecyclerView.Adapter<GradedTaskOverlayAdapter.GradedTaskViewHolder>() {

    inner class GradedTaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val topicNameTextView: TextView = itemView.findViewById(R.id.topicNameTextView)
        private val taskNameTextView: TextView = itemView.findViewById(R.id.taskNameTextView)
        private val taskDescriptionTextView: TextView = itemView.findViewById(R.id.taskDescriptionTextView)
        private val taskIndexTextView: TextView = itemView.findViewById(R.id.taskIndexTextView)
        private val gradeTextView: TextView = itemView.findViewById(R.id.gradeTextView)

        fun bind(gradedTask: GradedTaskItem) {
            topicNameTextView.text = gradedTask.topicName
            taskNameTextView.text = gradedTask.taskName
            taskDescriptionTextView.text = if (gradedTask.taskDescription.isNotEmpty() && gradedTask.taskDescription != "Sin descripción") {
                gradedTask.taskDescription
            } else {
                "Sin descripción disponible"
            }
            taskIndexTextView.text = "#${gradedTask.index}"
            gradeTextView.text = "📊 ${gradedTask.grade}"

            itemView.setOnClickListener {
                onTaskClick(gradedTask)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GradedTaskViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_graded_task_overlay, parent, false)
        return GradedTaskViewHolder(view)
    }

    override fun onBindViewHolder(holder: GradedTaskViewHolder, position: Int) {
        holder.bind(gradedTasks[position])
    }

    override fun getItemCount(): Int = gradedTasks.size

    fun updateGradedTasks(newGradedTasks: List<GradedTaskItem>) {
        gradedTasks = newGradedTasks
        notifyDataSetChanged()
    }
}
