// ui/adapter/VolumeAdapter.kt - UPDATED
package com.example.novel_summary.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.novel_summary.R
import com.example.novel_summary.data.model.VolumeWithStats

class VolumeAdapter(
    private val onItemClick: (VolumeWithStats) -> Unit,
    private val onItemLongClick: (VolumeWithStats) -> Boolean
) : RecyclerView.Adapter<VolumeAdapter.VolumeViewHolder>() {

    private val volumeList = mutableListOf<VolumeWithStats>()

    class VolumeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val volumeNameTextView: TextView = view.findViewById(R.id.tvNovelName)
        val chapterCountTextView: TextView = view.findViewById(R.id.tvNovelVolumeCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VolumeViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_novel, parent, false)
        return VolumeViewHolder(view)
    }

    override fun onBindViewHolder(holder: VolumeViewHolder, position: Int) {
        val volume = volumeList[position]

        holder.volumeNameTextView.text = volume.volumeName
        holder.chapterCountTextView.text = "${volume.chapterCount} chapter${if (volume.chapterCount != 1) "s" else ""}"

        holder.itemView.setOnClickListener { onItemClick(volume) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(volume)
            true
        }
    }

    override fun getItemCount() = volumeList.size

    fun submitList(volumes: List<VolumeWithStats>) {
        volumeList.clear()
        volumeList.addAll(volumes)
        notifyDataSetChanged()
    }

    fun clearList() {
        volumeList.clear()
        notifyDataSetChanged()
    }

    fun getItem(position: Int): VolumeWithStats = volumeList[position]
}