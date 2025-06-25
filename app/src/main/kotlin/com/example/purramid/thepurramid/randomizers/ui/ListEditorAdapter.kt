// ListEditorAdapter.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.purramid.thepurramid.data.db.SpinListEntity
import com.example.purramid.thepurramid.databinding.ItemListEditorBinding // Import generated binding class

// Data class combining entity and count for easier adapter use
data class ListDisplayItem(
    val entity: SpinListEntity,
    val count: Int
)

class ListEditorAdapter(
    private val onDeleteClick: (SpinListEntity) -> Unit,
    private val onListClick: (SpinListEntity) -> Unit
) : ListAdapter<ListDisplayItem, ListEditorAdapter.ListEditorViewHolder>(ListDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ListEditorViewHolder {
        val binding = ItemListEditorBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ListEditorViewHolder(binding, onDeleteClick, onListClick)
    }

    override fun onBindViewHolder(holder: ListEditorViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ListEditorViewHolder(
        private val binding: ItemListEditorBinding,
        private val onDeleteClick: (SpinListEntity) -> Unit,
        private val onListClick: (SpinListEntity) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(listDisplayItem: ListDisplayItem) {
            val listEntity = listDisplayItem.entity
            binding.textViewListTitle.text = "${listEntity.title} (${listDisplayItem.count})" // Display title and count
            binding.buttonDeleteList.setOnClickListener {
                onDeleteClick(listEntity)
            }
            binding.root.setOnClickListener {
                onListClick(listEntity)
            }
        }
    }

    class ListDiffCallback : DiffUtil.ItemCallback<ListDisplayItem>() {
        override fun areItemsTheSame(oldItem: ListDisplayItem, newItem: ListDisplayItem): Boolean {
            return oldItem.entity.id == newItem.entity.id
        }

        override fun areContentsTheSame(oldItem: ListDisplayItem, newItem: ListDisplayItem): Boolean {
            // Compare count as well for potential updates
            return oldItem.entity == newItem.entity && oldItem.count == newItem.count
        }
    }
}