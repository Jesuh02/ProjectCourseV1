package com.example.tareamov.adapter

import android.net.Uri
import android.view.LayoutInflater
import android.view.MotionEvent  // Add this import
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tareamov.R
import com.example.tareamov.data.entity.Persona
import de.hdodenhof.circleimageview.CircleImageView

class PersonaAdapter : ListAdapter<Persona, PersonaAdapter.PersonaViewHolder>(PersonaDiffCallback()) {

    // Interface for handling item clicks
    interface OnItemClickListener {
        fun onItemClick(persona: Persona)
        fun onEditClick(persona: Persona)
        fun onDeleteClick(persona: Persona)
    }

    private var listener: OnItemClickListener? = null

    fun setOnItemClickListener(listener: OnItemClickListener) {
        this.listener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PersonaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_persona, parent, false)
        return PersonaViewHolder(view)
    }

    override fun onBindViewHolder(holder: PersonaViewHolder, position: Int) {
        val persona = getItem(position)
        holder.bind(persona)

        // Set click listeners
        holder.itemView.setOnClickListener {
            listener?.onItemClick(persona)
        }

        // Find edit and delete buttons in the item layout and set listeners with animations
        val editButton = holder.itemView.findViewById<ImageButton>(R.id.editButton)
        val deleteButton = holder.itemView.findViewById<ImageButton>(R.id.deleteButton)

        // Apply button press animation
        val buttonPress = AnimationUtils.loadAnimation(holder.itemView.context, R.anim.button_press)

        editButton?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.startAnimation(buttonPress)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    listener?.onEditClick(persona)
                    true
                }
                else -> false
            }
        }

        deleteButton?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.startAnimation(buttonPress)
                    false
                }
                MotionEvent.ACTION_UP -> {
                    listener?.onDeleteClick(persona)
                    true
                }
                else -> false
            }
        }
    }

    class PersonaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Using the correct IDs as specified
        private val avatarImageView: CircleImageView = itemView.findViewById(R.id.avatarImageView)
        private val nombreTextView: TextView = itemView.findViewById(R.id.nombreTextView)
        private val apellidoTextView: TextView = itemView.findViewById(R.id.apellidoTextView)
        private val emailTextView: TextView = itemView.findViewById(R.id.emailTextView)
        private val telefonoTextView: TextView = itemView.findViewById(R.id.telefonoTextView)

        // The PersonaAdapter.kt file already has the correct implementation for displaying avatars
        // in the bind method of the PersonaViewHolder class:

        fun bind(persona: Persona) {
            val context = itemView.context
            nombreTextView.text = persona.nombres
            apellidoTextView.text = persona.apellidos
            emailTextView.text = persona.email
            telefonoTextView.text = persona.telefono

            // Load avatar image
            if (!persona.avatar.isNullOrEmpty()) {
                try {
                    Glide.with(context)
                        .load(Uri.parse(persona.avatar))
                        .placeholder(R.drawable.default_avatar)
                        .error(R.drawable.default_avatar)
                        .into(avatarImageView)
                } catch (e: Exception) {
                    // If there's an error loading the image, use the default avatar
                    avatarImageView.setImageResource(R.drawable.default_avatar)
                }
            } else {
                // If no avatar is set, use the default avatar
                avatarImageView.setImageResource(R.drawable.default_avatar)
            }
        }
    }

    private class PersonaDiffCallback : DiffUtil.ItemCallback<Persona>() {
        override fun areItemsTheSame(oldItem: Persona, newItem: Persona): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Persona, newItem: Persona): Boolean {
            return oldItem == newItem
        }
    }
}