package com.example.tareamov.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil // ++ Add import ++
import androidx.recyclerview.widget.ListAdapter // ++ Add import ++
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R
import com.example.tareamov.data.entity.Topic

// ++ Change inheritance to ListAdapter and remove 'topics' from constructor ++
class TopicSelectionAdapter(
    private val onItemClickListener: (Topic) -> Unit
) : ListAdapter<Topic, TopicSelectionAdapter.TopicViewHolder>(TopicDiffCallback()) { // ++ Use ListAdapter and DiffCallback ++

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TopicViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_topic_selection, parent, false)
        return TopicViewHolder(view)
    }

    override fun onBindViewHolder(holder: TopicViewHolder, position: Int) {
        // ++ Get item using getItem() from ListAdapter ++
        val topic = getItem(position)
        holder.bind(topic)
        holder.itemView.setOnClickListener {
            onItemClickListener(topic)
        }
    }

    // ++ Remove getItemCount() - ListAdapter handles this ++
    // override fun getItemCount(): Int = topics.size

    // ++ Remove updateTopics() - Use submitList() in the Fragment ++
    /*
    fun updateTopics(newTopics: List<Topic>) {
        topics = newTopics
        notifyDataSetChanged() // Consider using DiffUtil for better performance
    }
    */

    class TopicViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val topicNameTextView: TextView = itemView.findViewById(R.id.topicNameTextView) // Ensure this ID exists in item_topic_selection.xml

        fun bind(topic: Topic) {
            topicNameTextView.text = topic.name ?: "Tema sin nombre"
        }
    }

    // ++ Add DiffUtil.ItemCallback implementation ++
    class TopicDiffCallback : DiffUtil.ItemCallback<Topic>() {
        override fun areItemsTheSame(oldItem: Topic, newItem: Topic): Boolean {
            return oldItem.id == newItem.id // Compare unique IDs
        }

        override fun areContentsTheSame(oldItem: Topic, newItem: Topic): Boolean {
            return oldItem == newItem // Compare all fields (if Topic is a data class)
        }
    }
}