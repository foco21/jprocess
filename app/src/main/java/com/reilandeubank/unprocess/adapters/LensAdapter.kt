package com.reilandeubank.unprocess.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import com.reilandeubank.unprocess.R

class LensAdapter(private val lenses: List<String>, private val onClick: (String) -> Unit) :
    RecyclerView.Adapter<LensAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val button: Button = itemView.findViewById(R.id.lens_button)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lens, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val lens = lenses[position]
        holder.button.text = lens
        holder.button.setOnClickListener { onClick(lens) }
    }

    override fun getItemCount() = lenses.size
}
