package com.example.tareamov.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.Usuario

class CollaboratorSearchAdapter(
    private val onUserSelected: (Usuario) -> Unit
) : ListAdapter<Usuario, CollaboratorSearchAdapter.ViewHolder>(UserDiffCallback()) {

    private val selectedIds = mutableSetOf<Long>()

    fun setSelectedIds(ids: Set<Long>) {
        selectedIds.clear()
        selectedIds.addAll(ids)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_user_search_result, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val user = getItem(position)
        holder.bind(user, selectedIds.contains(user.id))
        holder.itemView.setOnClickListener { onUserSelected(user) }
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: ImageView = itemView.findViewById(R.id.userAvatar)
        private val name: TextView = itemView.findViewById(R.id.userName)
        private val email: TextView = itemView.findViewById(R.id.userEmail)
        private val indicator: ImageView = itemView.findViewById(R.id.selectedIndicator)

        fun bind(user: Usuario, isSelected: Boolean) {
            name.text = user.usuario
            email.text = user.email
            indicator.visibility = if (isSelected) View.VISIBLE else View.GONE

            if (!user.avatar.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(user.avatar)
                    .placeholder(R.drawable.default_avatar)
                    .circleCrop()
                    .into(avatar)
            } else {
                avatar.setImageResource(R.drawable.default_avatar)
            }
        }
    }

    private class UserDiffCallback : DiffUtil.ItemCallback<Usuario>() {
        override fun areItemsTheSame(oldItem: Usuario, newItem: Usuario) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Usuario, newItem: Usuario) = oldItem == newItem
    }
}
