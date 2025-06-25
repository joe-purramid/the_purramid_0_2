// CoinAdapter.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.databinding.ItemCoinBinding
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinFace
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinFlipViewModel // For FLIP_ANIMATION_DURATION_MS
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinInPool
import com.example.purramid.thepurramid.randomizers.viewmodel.CoinType

class CoinAdapter(
    private var coinColor: Int,
    private var animateFlips: Boolean,
    private val getCoinDrawableResFunction: (CoinType, CoinFace) -> Int
) : ListAdapter<CoinInPool, CoinAdapter.CoinViewHolder>(CoinDiffCallback()) {

    // To keep track of coins that are currently undergoing the flip animation initiated by the adapter
    private val flippingCoins = mutableSetOf<UUID>()

    fun updateCoinAppearanceProperties(newCoinColor: Int, newAnimateFlips: Boolean) {
        val colorChanged = coinColor != newCoinColor
        val animationPrefChanged = animateFlips != newAnimateFlips
        coinColor = newCoinColor
        animateFlips = newAnimateFlips
        if (colorChanged) {
            notifyDataSetChanged() // Rebind all visible items to update color
        }
        // No need to notify for animationPrefChanged unless it immediately affects static appearance
    }

    // Call this method from the Fragment when a flip happens for specific items
    // It updates the item in the list and notifies the adapter to rebind with a payload.
    fun updateItemAndAnimate(coinId: UUID, newFace: CoinFace) {
        val position = currentList.indexOfFirst { it.id == coinId }
        if (position != -1) {
            val oldItem = getItem(position)
            if (oldItem.currentFace != newFace || !flippingCoins.contains(coinId)) { // Animate if face changed or not already flipping
                // The list itself should be updated by the ViewModel that owns the source of truth.
                // This adapter should just react to the already updated list passed via submitList.
                // However, to ensure animation plays for a specific item that *just* changed:
                if (animateFlips) {
                    flippingCoins.add(coinId)
                    // The item in currentList should already have the newFace from ViewModel's update
                    notifyItemChanged(position, "FLIP_ANIMATION_PAYLOAD")
                } else {
                    // If animations are off, a simple notifyItemChanged without payload is enough
                    // if the item's content (drawable) needs to update.
                    // But submitList from fragment should handle this.
                }
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CoinViewHolder {
        val binding = ItemCoinBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CoinViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CoinViewHolder, position: Int) {
        val coin = getItem(position)
        holder.bind(coin, coinColor, getCoinDrawableResFunction)

        // If this coin was just flipped and animation is enabled, and it's not already marked as flipping by adapter
        if (coin.currentFace != CoinFace.HEADS && animateFlips && !flippingCoins.contains(coin.id)) {
           // This simplified animation trigger might need refinement.
           // The ViewModel should ideally tell the adapter *which specific items* just got new results.
           // For now, if a coin is Tails and wasn't previously, we might animate it if it's visible.
        }
    }

    // Call this method from the Fragment when a flip happens for specific items
    fun triggerFlipAnimationForItem(coinId: UUID, finalFace: CoinFace) {
        val position = currentList.indexOfFirst { it.id == coinId }
        if (position != -1 && animateFlips) {
            flippingCoins.add(coinId)
            notifyItemChanged(position, "FLIP_ANIMATION_PAYLOAD") // Trigger onBindViewHolder with payload
        }
    }


    inner class CoinViewHolder(private val binding: ItemCoinBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            coin: CoinInPool,
            colorInt: Int,
            getDrawableRes: (CoinType, CoinFace) -> Int
            isAnimatingPayload: Boolean
        ) {
            val finalDrawableRes = getDrawableRes(coin.type, coin.currentFace)

            if (isAnimatingPayload && animateFlips) {
                // If it's an animation payload, the animateFlip function will set the final image.
                // We might want to show a "spinning" or intermediate state briefly or ensure color is set.
                if (colorInt != Color.TRANSPARENT) {
                    binding.coinImageView.colorFilter = PorterDuffColorFilter(colorInt, PorterDuff.Mode.SRC_IN)
                } else {
                    binding.coinImageView.clearColorFilter()
                }
                // The animateFlip method will be called from onBindViewHolder(..., payloads)
            } else {
                // Standard bind or if animation is off
                binding.coinImageView.setImageResource(finalDrawableRes)
                if (colorInt != Color.TRANSPARENT) {
                    binding.coinImageView.colorFilter = PorterDuffColorFilter(colorInt, PorterDuff.Mode.SRC_IN)
                } else {
                    binding.coinImageView.clearColorFilter()
                }
                binding.coinImageView.scaleY = 1f // Ensure it's not stuck at scale 0
                binding.coinImageView.rotationY = 0f
            }
        }

        fun animateFlip(coin: CoinInPool, finalFace: CoinFace, finalDrawableRes: Int, onEnd: () -> Unit) {
            binding.coinImageView.tag = null // Clear any previous tag

            val scaleDown = ObjectAnimator.ofFloat(binding.coinImageView, View.SCALE_Y, 1f, 0f).apply {
                duration = CoinFlipViewModel.FLIP_ANIMATION_DURATION_MS / 2
                interpolator = AccelerateDecelerateInterpolator()
            }
            val scaleUp = ObjectAnimator.ofFloat(binding.coinImageView, View.SCALE_Y, 0f, 1f).apply {
                duration = CoinFlipViewModel.FLIP_ANIMATION_DURATION_MS / 2
                interpolator = AccelerateDecelerateInterpolator()
            }

            scaleDown.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.coinImageView.setImageResource(finalDrawableRes)
                    // Optional: slight rotation on X for a little 3D wobble before scaling up
                    // binding.coinImageView.rotationX = 10f
                    scaleUp.start()
                }
            })

            scaleUp.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // binding.coinImageView.rotationX = 0f // Reset wobble
                    onEnd() // Call the lambda to remove from flippingCoins set
                }
            })

            // Optionally, add a slight rotationY as well if desired, playing together or sequentially.
            // For simplicity, just scaleY is often effective for a 2D representation of a flip.
            scaleDown.start()
        }
    }

    // DiffUtil for efficient updates
    class CoinDiffCallback : DiffUtil.ItemCallback<CoinInPool>() {
        override fun areItemsTheSame(oldItem: CoinInPool, newItem: CoinInPool): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CoinInPool, newItem: CoinInPool): Boolean {
            return oldItem == newItem // Checks type, currentFace, xPos, yPos
        }
    }

    override fun onBindViewHolder(holder: CoinViewHolder, position: Int) {
        // This is called if there are no payloads.
        val coin = getItem(position)
        holder.bind(coin, coinColor, getCoinDrawableResFunction, false)
    }

    override fun onBindViewHolder(holder: CoinViewHolder, position: Int, payloads: MutableList<Any>) {
        val coin = getItem(position)
        if (payloads.contains("FLIP_ANIMATION_PAYLOAD") && animateFlips && flippingCoins.contains(coin.id)) {
            Log.d("CoinAdapter", "Animating flip for coin: ${coin.id} to ${coin.currentFace}")
            holder.animateFlip(coin, coin.currentFace, getCoinDrawableResFunction(coin.type, coin.currentFace)) {
                flippingCoins.remove(coin.id)
            }
        } else if (payloads.contains("COLOR_UPDATE_PAYLOAD")) {
            holder.bind(coin, coinColor, getCoinDrawableResFunction, false) // Re-bind for color
        }
        else {
            // Fallback to full bind if no specific payload or animation is off
            holder.bind(coin, coinColor, getCoinDrawableResFunction, false)
        }
    }
}