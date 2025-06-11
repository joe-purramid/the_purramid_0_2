// SlotColumnView.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.net.Uri // Import Uri
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView // Import ImageView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.data.db.SpinItemEntity
import com.example.purramid.thepurramid.databinding.ViewSlotColumnBinding // Use ViewBinding
import com.example.purramid.thepurramid.randomizers.SpinItemType

class SlotColumnView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ConstraintLayout(context, attrs, defStyleAttr) {

    private val binding: ViewSlotColumnBinding

    private var onTitleClickListener: (() -> Unit)? = null
    private var onLockClickListener: (() -> Unit)? = null

    private var isLocked: Boolean = false
    private var currentFullList: List<SpinItemEntity> = emptyList()
    private var currentCenterIndex: Int = -1

    // Animation related properties
    private var spinAnimator: ValueAnimator? = null
    private val animationDuration = 150L // Duration for each item step during spin

    init {
        binding = ViewSlotColumnBinding.inflate(LayoutInflater.from(context), this, true)
        setupInternalListeners()
        // Initially hide item views until data is set
        clearItemViews()
    }

    private fun setupInternalListeners() {
        binding.slotTitleMarqueeLayout.setOnClickListener {
            onTitleClickListener?.invoke()
        }
        binding.slotLockButton.setOnClickListener {
            onLockClickListener?.invoke()
        }
    }

    // --- Public API ---

    fun setOnTitleClickListener(listener: () -> Unit) {
        this.onTitleClickListener = listener
    }

    fun setOnLockClickListener(listener: () -> Unit) {
        this.onLockClickListener = listener
    }

    fun setTitle(title: String?) {
        binding.slotTitleTextView.text = title ?: context.getString(R.string.select_list)
        binding.slotTitleTextView.isSelected = true
    }

    fun setLockedState(locked: Boolean) {
        if (this.isLocked == locked) return // No change needed
        this.isLocked = locked
        if (locked) {
            // *** Assumes R.drawable.ic_lock and R.string.unlock_column exist ***
            binding.slotLockButton.setImageResource(R.drawable.ic_lock)
            binding.slotLockButton.contentDescription = context.getString(R.string.unlock_column)
        } else {
            // *** Assumes R.drawable.ic_lock_open and R.string.lock_column exist ***
            binding.slotLockButton.setImageResource(R.drawable.ic_lock_open)
            binding.slotLockButton.contentDescription = context.getString(R.string.lock_column)
        }
    }

    /**
     * Sets the data source (the full list) and the currently displayed center item.
     * Calculates and displays adjacent items.
     */
    fun setData(fullList: List<SpinItemEntity>, centerItemId: UUID?) {
        cancelSpinAnimation() // Stop animation if running
        currentFullList = fullList
        currentCenterIndex = fullList.indexOfFirst { it.id == centerItemId }

        displayCurrentState()
    }

    /** Displays items based on currentFullList and currentCenterIndex */
    private fun displayCurrentState() {
        if (currentCenterIndex < 0 || currentFullList.isEmpty()) {
            clearItemViews()
            return
        }

        val centerItem = currentFullList.getOrNull(currentCenterIndex)
        // Calculate wrapped indices for above/below items
        val aboveIndex = if (currentCenterIndex > 0) currentCenterIndex - 1 else currentFullList.size - 1
        val belowIndex = if (currentCenterIndex < currentFullList.size - 1) currentCenterIndex + 1 else 0

        val itemAbove = if (currentFullList.size > 1) currentFullList.getOrNull(aboveIndex) else null
        val itemBelow = if (currentFullList.size > 1) currentFullList.getOrNull(belowIndex) else null

        updateItemView(
            binding.slotItemTextViewCenter,
            // *** Assumes binding.slotItemImageViewCenter exists ***
             binding.slotItemImageViewCenter,
            centerItem
        )
        updateItemView(
            binding.slotItemTextViewAbove,
            // *** Assumes binding.slotItemImageViewAbove exists ***
             binding.slotItemImageViewAbove,
            itemAbove
        )
        updateItemView(
            binding.slotItemTextViewBelow,
            // *** Assumes binding.slotItemImageViewBelow exists ***
             binding.slotItemImageViewBelow,
            itemBelow
        )

        // Apply fading effect/alpha
        binding.slotItemTextViewAbove.alpha = if (itemAbove != null) 0.5f else 0f
        binding.slotItemImageViewAbove.alpha = if (itemAbove != null) 0.5f else 0f // Assumes ImageView exists
        binding.slotItemTextViewBelow.alpha = if (itemBelow != null) 0.5f else 0f
        binding.slotItemImageViewBelow.alpha = if (itemBelow != null) 0.5f else 0f // Assumes ImageView exists
    }

    /** Helper to update a single item view set (TextView/ImageView) */
    private fun updateItemView(textView: TextView, imageView: ImageView?, item: SpinItemEntity?) {
         // Clear previous state
         textView.text = ""
         textView.isVisible = false
         imageView?.let { // Only proceed if ImageView is not null
            it.setImageDrawable(null)
            it.isVisible = false
            // Glide.with(context).clear(it) // Clear Glide requests
         }


         if (item == null) return

         when (item.itemType) {
            SpinItemType.TEXT -> {
                textView.text = item.content
                textView.isVisible = true
            }
            SpinItemType.IMAGE -> {
                 if (imageView != null) {
                     imageView.isVisible = true
                     try {
                         // Use Glide for loading images
                         Glide.with(context)
                              .load(Uri.parse(item.content)) // Assuming content is parseable URI string
                              // .placeholder(R.drawable.placeholder) // Optional
                              // .error(R.drawable.error_placeholder) // Optional
                              .into(imageView)
                     } catch (e: Exception) {
                         Log.e("SlotColumnView", "Error parsing URI or loading image: ${item.content}", e)
                         imageView.isVisible = false // Hide if error
                         textView.text = context.getString(R.string.item_image_placeholder) // Placeholder text // *** Assumes R.string.item_image_placeholder exists ***
                         textView.isVisible = true
                     }
                 } else {
                      // Fallback if ImageView doesn't exist in layout yet
                      textView.text = context.getString(R.string.item_image_placeholder) // *** Assumes R.string.item_image_placeholder exists ***
                      textView.isVisible = true
                 }
            }
            SpinItemType.EMOJI -> {
                // Ensure EmojiCompat is initialized in Application class for proper rendering
                textView.text = item.emojiList.joinToString(" ")
                textView.isVisible = true
            }
        }
        // Background color is likely not needed for slots items
        // textView.setBackgroundColor(item.backgroundColor ?: Color.TRANSPARENT)
    }


    /** Starts the spinning animation */
    fun startSpinAnimation() {
        if (isLocked || currentFullList.isEmpty()) return

        cancelSpinAnimation() // Cancel any existing animation

        // Simple animation: cycle through items quickly
        var animationIndex = currentCenterIndex
        spinAnimator = ValueAnimator.ofInt(0, currentFullList.size * 3).apply { // Spin a few cycles
            duration = animationDuration * currentFullList.size * 3 // Adjust total duration
            interpolator = AccelerateDecelerateInterpolator() // Or LinearInterpolator

            addUpdateListener {
                // Cycle through items - this just updates the index quickly
                animationIndex = (animationIndex + 1) % currentFullList.size
                // Update displayed items based on the fast-changing index
                 val centerItem = currentFullList.getOrNull(animationIndex)
                 val aboveIndex = if (animationIndex > 0) animationIndex - 1 else currentFullList.size - 1
                 val belowIndex = if (animationIndex < currentFullList.size - 1) animationIndex + 1 else 0
                 val itemAbove = if (currentFullList.size > 1) currentFullList.getOrNull(aboveIndex) else null
                 val itemBelow = if (currentFullList.size > 1) currentFullList.getOrNull(belowIndex) else null

                 updateItemView(binding.slotItemTextViewCenter, binding.slotItemImageViewCenter, centerItem)
                 updateItemView(binding.slotItemTextViewAbove, binding.slotItemImageViewAbove, itemAbove)
                 updateItemView(binding.slotItemTextViewBelow, binding.slotItemImageViewBelow, itemBelow)

                 // Keep fades during spin for visual effect
                 binding.slotItemTextViewAbove.alpha = if (itemAbove != null) 0.5f else 0f
                 binding.slotItemImageViewAbove.alpha = if (itemAbove != null) 0.5f else 0f
                 binding.slotItemTextViewBelow.alpha = if (itemBelow != null) 0.5f else 0f
                 binding.slotItemImageViewBelow.alpha = if (itemBelow != null) 0.5f else 0f
            }
            // Note: This basic animation just flashes items. A smoother vertical
            // scroll effect would involve animating translationY property of the views.
            start()
        }
    }

    /** Stops the spinning animation and displays the final items */
    fun stopSpinAnimation(finalCenterItemId: UUID?) {
         cancelSpinAnimation() // Stop the value animator

         // Update index and display final state
         currentCenterIndex = currentFullList.indexOfFirst { it.id == finalCenterItemId }
         displayCurrentState()
    }

    /** Cancels any ongoing spin animation */
    private fun cancelSpinAnimation() {
        spinAnimator?.cancel()
        spinAnimator = null
    }

     /** Clears all item views */
     private fun clearItemViews() {
         updateItemView(binding.slotItemTextViewCenter, binding.slotItemImageViewCenter, null)
         updateItemView(binding.slotItemTextViewAbove, binding.slotItemImageViewAbove, null)
         updateItemView(binding.slotItemTextViewBelow, binding.slotItemImageViewBelow, null)
         binding.slotItemTextViewAbove.alpha = 0f
         binding.slotItemImageViewAbove.alpha = 0f
         binding.slotItemTextViewBelow.alpha = 0f
         binding.slotItemImageViewBelow.alpha = 0f
     }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelSpinAnimation() // Ensure animation stops when view is detached
    }
}