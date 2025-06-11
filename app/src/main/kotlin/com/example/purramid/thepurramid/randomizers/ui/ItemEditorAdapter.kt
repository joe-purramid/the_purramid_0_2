// ItemEditorAdapter.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.purramid.thepurramid.data.db.SpinItemEntity
import com.example.purramid.thepurramid.databinding.ItemEditorRowBinding // Use generated binding for the row layout

class ItemEditorAdapter(
    private val listener: ItemEditorListener
) : ListAdapter<SpinItemEntity, ItemEditorAdapter.ItemViewHolder>(ItemDiffCallback()) {

    // Interface for callbacks to the Fragment/ViewModel
    interface ItemEditorListener {
        fun onDeleteItemClicked(item: SpinItemEntity)
        fun onItemTextChanged(item: SpinItemEntity, newText: String)
        fun onColorClicked(item: SpinItemEntity, view: View) // Pass view for anchoring popups if needed
        fun onImageClicked(item: SpinItemEntity)
        fun onEmojiClicked(item: SpinItemEntity)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val binding = ItemEditorRowBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ItemViewHolder(binding, listener)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    // --- ViewHolder ---
    inner class ItemViewHolder(
        private val binding: ItemEditorRowBinding,
        private val listener: ItemEditorListener
    ) : RecyclerView.ViewHolder(binding.root) {

        private var currentItem: SpinItemEntity? = null
        private var isBinding = false // Flag to prevent listener loops

        init {
            // Listener for text changes - triggers callback *after* text changes
            binding.editTextItemContent.doAfterTextChanged { editable ->
                 if (!isBinding && bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    currentItem?.let { item ->
                        // Apply 27 char limit locally for immediate feedback (ViewModel will also apply)
                        val newText = editable?.toString() ?: ""
                        val limitedText = if (newText.length > 27) newText.substring(0, 27) else newText
                        if (newText.length > 27) {
                            // Prevent cursor moving unexpectedly if text was truncated
                             isBinding = true
                             binding.editTextItemContent.setText(limitedText)
                             binding.editTextItemContent.setSelection(limitedText.length)
                             isBinding = false
                        }
                         listener.onItemTextChanged(item, limitedText)
                    }
                 }
            }

             // Alternative: Save text only when focus is lost
             /*
             binding.editTextItemContent.setOnFocusChangeListener { view, hasFocus ->
                 if (!hasFocus && adapterPosition != RecyclerView.NO_POSITION) {
                     currentItem?.let { item ->
                         listener.onItemTextChanged(item, (view as EditText).text.toString())
                     }
                 }
             }
             */

            // Click listeners for buttons/views
            binding.buttonItemDelete.setOnClickListener {
                 if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                     currentItem?.let { listener.onDeleteItemClicked(it) }
                 }
            }
            binding.viewItemColorSquare.setOnClickListener {
                 if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                     currentItem?.let { listener.onColorClicked(it, binding.viewItemColorSquare) }
                 }
            }
            binding.buttonItemAddImage.setOnClickListener {
                 if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                     currentItem?.let { listener.onImageClicked(it) }
                 }
            }
            binding.buttonItemAddEmoji.setOnClickListener {
                if (bindingAdapterPosition != RecyclerView.NO_POSITION) {
                    // Pass the item and the button view for anchoring the popup
                    currentItem?.let { listener.onEmojiClicked(it, binding.buttonItemAddEmoji) }
                }
            }
        }

        fun bind(item: SpinItemEntity) {
             isBinding = true // Prevent text change listener during binding
            currentItem = item
            binding.editTextItemContent.setText(item.content)
            binding.viewItemColorSquare.background =
                ColorDrawable(item.backgroundColor ?: Color.LTGRAY) // Use item color or default
             isBinding = false // Re-enable listener
        }
    }

    // --- DiffUtil Callback ---
    class ItemDiffCallback : DiffUtil.ItemCallback<SpinItemEntity>() {
        override fun areItemsTheSame(oldItem: SpinItemEntity, newItem: SpinItemEntity): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: SpinItemEntity, newItem: SpinItemEntity): Boolean {
            // Compare all relevant fields that affect the UI
            return oldItem == newItem
        }
    }
}