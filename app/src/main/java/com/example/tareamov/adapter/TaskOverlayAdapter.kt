package com.example.tareamov.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R

data class TaskItem(
    val taskId: Long,
    val taskName: String,
    val taskDescription: String,
    val topicName: String,
    val index: Int
)

class TaskOverlayAdapter(
    private var tasks: List<TaskItem>,
    private val onTaskClick: (TaskItem) -> Unit
) : RecyclerView.Adapter<TaskOverlayAdapter.TaskViewHolder>() {

    inner class TaskViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val topicNameTextView: TextView = itemView.findViewById(R.id.topicNameTextView)
        private val taskNameTextView: TextView = itemView.findViewById(R.id.taskNameTextView)
        private val taskDescriptionTextView: TextView = itemView.findViewById(R.id.taskDescriptionTextView)
        private val taskIndexTextView: TextView = itemView.findViewById(R.id.taskIndexTextView)

        fun bind(task: TaskItem) {
            topicNameTextView.text = task.topicName
            taskNameTextView.text = task.taskName
            taskDescriptionTextView.text = if (task.taskDescription.isNotEmpty() && task.taskDescription != "Sin descripción") {
                task.taskDescription
            } else {
                "Sin descripción disponible"
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
        holder.bind(tasks[position])
    }

    override fun getItemCount(): Int = tasks.size

    fun updateTasks(newTasks: List<TaskItem>) {
        tasks = newTasks
        notifyDataSetChanged()
    }
}
