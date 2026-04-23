package com.example.smarthomedashboard

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView

class GroupButtonsAdapter(
    private val items: MutableList<GroupButtonItem>,
    private val onItemClick: (GroupButtonItem) -> Unit
) : RecyclerView.Adapter<GroupButtonsAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_group_button, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        holder.tvState.text = if (item.state == "on") "ON" else "OFF"
        holder.tvState.setTextColor(if (item.state == "on") Color.parseColor("#4CAF50") else Color.parseColor("#FF5555"))

        val bgColor = if (item.state == "on") Color.parseColor("#8033CC33") else Color.parseColor("#80333333")
        holder.cardView.setCardBackgroundColor(bgColor)

        holder.cardView.alpha = if (item.isAvailable) 1.0f else 0.5f

        holder.itemView.setOnClickListener {
            if (item.isAvailable) {
                onItemClick(item)
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val cardView: CardView = view as CardView
        val tvName: TextView = view.findViewById(R.id.tvGroupButtonName)
        val tvState: TextView = view.findViewById(R.id.tvGroupButtonState)
    }
}