package com.example.tareamov.adapter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.tareamov.R

data class LessonCard(val title: String, val duration: String, val thumbnailRes: Int)

class ContinueWatchingAdapter(
    private val lessons: List<LessonCard>,
    private val onClick: (LessonCard) -> Unit
) : RecyclerView.Adapter<ContinueWatchingAdapter.LessonViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LessonViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_continue_watching_card, parent, false)
        return LessonViewHolder(view)
    }

    override fun onBindViewHolder(holder: LessonViewHolder, position: Int) {
        val lesson = lessons[position]
        holder.title.text = lesson.title
        holder.duration.text = lesson.duration
        holder.thumbnail.setImageResource(lesson.thumbnailRes)
        holder.itemView.setOnClickListener { onClick(lesson) }
    }

    override fun getItemCount() = lessons.size

    class LessonViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.lessonTitle)
        val duration: TextView = view.findViewById(R.id.lessonDuration)
        val thumbnail: ImageView = view.findViewById(R.id.lessonThumbnail)
    }
}