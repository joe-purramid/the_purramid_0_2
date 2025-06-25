// SpinDialView.kt
package com.example.purramid.thepurramid.randomizers.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.toColorInt
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.purramid.thepurramid.data.db.SpinItemEntity
import com.example.purramid.thepurramid.data.db.SpinSettingsEntity
import com.example.purramid.thepurramid.randomizers.SpinItemType
import com.example.purramid.thepurramid.randomizers.SpinList
import com.example.purramid.thepurramid.randomizers.SpinSettings
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class SpinDialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val backgroundPaint = Paint().apply {
        color = Color.WHITE // Default background color
        style = Paint.Style.FILL
    }

    private val wedgePaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f // Default text size
        textAlign = Paint.Align.CENTER
    }

    private val arrowPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { // Enable Anti-aliasing
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f // Adjust stroke width as needed (e.g., 2f, 3f, 4f)
        strokeJoin = Paint.Join.ROUND // Optional: for smoother corners
        strokeCap = Paint.Cap.ROUND   // Optional: for smoother line ends
    }

    private var lists: List<SpinList> = emptyList()
    var currentList: SpinList? = null
    var settings: SpinSettings = SpinSettings()

		// Cache for loaded images <ItemID, Bitmap?> (null if loading/failed) 
		private val imageBitmapCache = ConcurrentHashMap<UUID, Bitmap?>() 
		// Rect for text bounds measurement 
		private val textBounds = Rect()

    private var dialRadius = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var rotation = 0f // Current rotation of the dial

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Calculate the center and radius of the dial
        centerX = w / 2f
        centerY = h / 2f
        dialRadius = min(w, h) * 0.4f // Adjust radius as needed
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background
        canvas.drawCircle(centerX, centerY, dialRadius, backgroundPaint)

        // Draw wedges
        drawWedges(canvas)

        // Draw selection arrow
        drawSelectionArrow(canvas)
    }

    private fun drawWedges(canvas: Canvas) {
        val itemsToDraw = currentList?.items ?: emptyList()
        val numWedges = itemsToDraw.size

        if (numWedges < 2) return // Avoid division by zero
        val wedgeAngle = 360f / numWedges
        var startAngle = 0f

        itemsToDraw.forEachIndexed { index, item ->
            wedgePaint.color = item.backgroundColor ?: getAutoAssignedColor(index, numWedges)

            val sweepAngle = wedgeAngle
            val oval = RectF(centerX - dialRadius, centerY - dialRadius, centerX + dialRadius, centerY + dialRadius)
            canvas.drawArc(oval, startAngle + rotation, sweepAngle, true, wedgePaint)
            // Pass totalWedges (numWedges) to drawItemContent
            drawItemContent(canvas, item, startAngle + rotation + sweepAngle / 2f, sweepAngle, numWedges)

            startAngle += wedgeAngle
        }
    }

    private fun getAutoAssignedColor(index: Int, totalWedges: Int): Int {
        if (totalWedges <= 0) return Color.GRAY // Fallback for safety

        // Define constant Saturation and Lightness
        // Values between 0.0 and 1.0
        val saturation = 0.7f // Adjust for desired vibrancy (e.g., 0.5f - 1.0f)
        val lightness = 0.6f // Adjust for desired brightness (e.g., 0.4f - 0.7f)

        // Calculate the hue step based on the total number of wedges
        val hueStep = 360f / totalWedges

        // Calculate the hue for the current index, wrapping around 360 degrees
        val hue = (index * hueStep) % 360f

        // Convert HSL values to an integer Color using AndroidX ColorUtils
        // HSLToColor expects HSL values in a float array: [hue, saturation, lightness]
        val hsl = floatArrayOf(hue, saturation, lightness)
        return ColorUtils.HSLToColor(hsl)
    }

    private fun drawItemContent(canvas: Canvas, item: SpinItemEntity, middleAngleDegrees: Float, sweepAngleDegrees: Float, totalWedges: Int) {
        val radiusFactor = 0.65f
        val itemCenterX = centerX + (dialRadius * radiusFactor * cos(Math.toRadians(middleAngleDegrees.toDouble()))).toFloat()
        val itemCenterY = centerY + (dialRadius * radiusFactor * sin(Math.toRadians(middleAngleDegrees.toDouble()))).toFloat()
        val itemRotation = middleAngleDegrees - 90f

        // --- Determine background color and check text contrast ---
        val wedgeBackgroundColor = item.backgroundColor ?: getAutoAssignedColor(items.indexOf(item), totalWedges) // Get the background color
        val defaultTextColor = Color.BLACK
        val alternateTextColor = Color.WHITE
			// WCAG 2.2 AA contrast standards
			val contrastThreshold = 4.5
        val contrastWithBlack = ColorUtils.calculateContrast(defaultTextColor, wedgeBackgroundColor)
        val contrastWithWhite = ColorUtils.calculateContrast(alternateTextColor, wedgeBackgroundColor)

        // Set text color to black or white depending on which has better contrast >= 4.5
        // Default to black if neither meets the threshold but black has higher contrast.
        textPaint.color = if (contrastWithBlack >= contrastThreshold) {
            defaultTextColor
        } else if (contrastWithWhite >= contrastThreshold) {
            alternateTextColor
        } else {
            // Neither meets 4.5:1, pick the better of the two (or could default based on luminance)
            if (contrastWithBlack > contrastWithWhite) defaultTextColor else alternateTextColor
        }

        canvas.save() //Save canvas state before rotation
			canvas.withRotation(itemRotation, itemCenterX, itemCenterY) { // Rotate canvas for easier drawing
            
			when (item.itemType) {
       		SpinItemType.TEXT -> {
	          textPaint.textSize = calculateTextSize(item.content, sweepAngleDegrees)
					textPaint.getTextBounds(item.content, 0, item.content.length, textBounds)
					val textY = itemCenterY + textBounds.height() / 2f - textBounds.bottom 
					canvas.drawText(item.content, itemCenterX, textY, textPaint)
       		}
	      	SpinItemType.IMAGE -> {
          	val cachedBitmap = imageBitmapCache[item.id]
                    
					if (cachedBitmap != null) {
						// Image loaded successfully, draw it
						val scaledBitmap = scaleBitmapToFit(cachedBitmap, sweepAngleDegrees)
						// Draw bitmap centered at itemCenterX, itemCenterY
						canvas.drawBitmap( 
							scaledBitmap, 
							itemCenterX - scaledBitmap.width / 2f, 
							itemCenterY - scaledBitmap.height / 2f, 
							null // Use default paint 
						)
						// Recycle the scaled bitmap if it's different from the cached one to save memory
						if (scaledBitmap != cachedBitmap) { 
							// scaledBitmap.recycle() // recycling might be premature if bitmap is needed elsewhere briefly
						}

                    } else {
                        // Image is loading or failed to load
                        val placeholder = context.getString(R.string.item_image_placeholder)
                        textPaint.textSize = calculateTextSize("[Image]", sweepAngleDegrees) // Smaller size for placeholder
                        textPaint.getTextBounds("[Image]", 0, "[Image]".length, textBounds)
                        val textY = itemCenterY + textBounds.height() / 2f - textBounds.bottom
                        canvas.drawText("[Image]", itemCenterX, textY, textPaint)
                    }
                }
               
				SpinItemType.EMOJI -> {
					val emojiString = item.emojiList.joinToString(" ") // Join with space
					textPaint.textSize = calculateTextSize(emojiString, sweepAngleDegrees) // Adjust size 
					textPaint.getTextBounds(emojiString, 0, emojiString.length, textBounds)
					val emojiY = itemCenterY + textBounds.height() / 2f - textBounds.descent()
					canvas.drawText(emojiString, itemCenterX, emojiY, textPaint)
                }
            }
        }
        canvas.restore() // Restore canvas to pre-rotation state
    }

    private fun calculateTextSize(text: String, sweepAngleDegrees: Float): Float { 
			if (text.isBlank() || sweepAngleDegrees <= 0) { 
				return 10f // Return a small default if no text or angle 
	 		} 

			// --- Define Constraints --- 
			val radiusFactor = 0.65f // Where text is drawn radially 
			val maxTextWidthPaddingFactor = 0.9f // Use 90% of calculated width for padding 
			val maxTextHeightFactor = 0.18f // Use ~18% of dial radius for max height 

			// Max height constraint (simple approach based on radius) 
			val maxTextHeight = dialRadius * maxTextHeightFactor 

			// Max width constraint (approximate chord length at radiusFactor) 
			val textRadius = dialRadius * radiusFactor 
			val sweepAngleRadians = Math.toRadians(sweepAngleDegrees.toDouble()) 
			// Chord length = 2 * R * sin(angle / 2) 
			val maxTextWidth = (2.0 * textRadius * sin(sweepAngleRadians / 2.0)).toFloat() * maxTextWidthPaddingFactor 

			// --- Iterative Sizing --- 
			val maxTryTextSize = dialRadius / 4f // Start with a large potential size 
			val minTextSizeSp = 8f // Minimum text size in scaled pixels (sp) 
			val minTextSizePx = minTextSizeSp * context.resources.displayMetrics.scaledDensity 

			var currentTextSize = maxTryTextSize 

			while (currentTextSize > minTextSizePx) { 
				textPaint.textSize = currentTextSize 
				textPaint.getTextBounds(text, 0, text.length, textBounds) 

			// Check if text fits within calculated bounds 
			if (textBounds.width() <= maxTextWidth && textBounds.height() <= maxTextHeight) { 
				return currentTextSize // Found a suitable size 
			} 

			// Reduce text size and try again (e.g., decrease by 1sp equivalent) 
			currentTextSize -= 1f * context.resources.displayMetrics.scaledDensity 
			// Ensure we don't go below minimum 
			currentTextSize = maxOf(currentTextSize, minTextSizePx) 
		}
		// If loop finishes, it means even the minimum size didn't fit (or barely fits) return minTextSizePx 
	}

    private fun drawSelectionArrow(canvas: Canvas) {
        val arrowSize = 40f
        val arrowTipX = centerX + dialRadius + arrowSize * 0.75f
        val arrowBaseX = arrowTipX - arrowSize
        val arrowY = centerY

        val path = android.graphics.Path().apply {
            moveTo(arrowTipX, arrowY)
            lineTo(arrowBaseX, arrowY - arrowSize / 2f)
            lineTo(arrowBaseX, arrowY + arrowSize / 2f)
            lineTo(arrowBaseX + arrowSize / 2f)
            close()
        }
        canvas.drawPath(path, arrowStrokePaint)
        canvas.drawPath(path, arrowPaint)
    }

    fun spin(onAnimationEnd: () -> Unit) {
        val degreesPerItem = 360f / settings.numWedges
        val targetRotation = rotation + (360 * 3) - (degreesPerItem * (Random().nextInt(settings.numWedges)))
        val animation = RotateAnimation(
            rotation,
            targetRotation,
            Animation.RELATIVE_TO_SELF,
            0.5f,
            Animation.RELATIVE_TO_SELF,
            0.5f
        ).apply {
            duration = 2000 // 2 seconds
            interpolator = LinearInterpolator()
            fillAfter = true
            setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationStart(animation: Animation) {}
                override fun onAnimationEnd(animation: Animation) {
                    rotation = targetRotation % 360
                    invalidate()
                    onAnimationEnd()
                }

                override fun onAnimationRepeat(animation: Animation) {}
            })
        }
        startAnimation(animation)
    }

    private fun scaleBitmapToFit(bitmap: Bitmap, sweepAngleDegrees: Float): Bitmap {
        // Define max bounds based on wedge geometry (similar to calculateTextSize)
        val radiusFactor = 0.65f
        val paddingFactor = 0.85f // Use 85% of space for image padding

        // Max height constraint (fraction of radius, minus padding)
        val maxBitmapHeight = (dialRadius * 0.25f) * paddingFactor // Allow slightly larger height than text

        // Max width constraint (chord length at radiusFactor, minus padding)
        val textRadius = dialRadius * radiusFactor
        val sweepAngleRadians = Math.toRadians(sweepAngleDegrees.toDouble())
        val maxBitmapWidth = (2.0 * textRadius * sin(sweepAngleRadians / 2.0)).toFloat() * paddingFactor

        if (maxBitmapWidth <= 0 || maxBitmapHeight <= 0) {
            return bitmap // Cannot scale to zero or negative size
        }

        // Calculate scaling factor
        val widthScale = maxBitmapWidth / bitmap.width
        val heightScale = maxBitmapHeight / bitmap.height
        val scale = min(widthScale, heightScale) // Use minimum scale to fit both dimensions

        // If bitmap is already small enough or scaling factor is negligible/invalid, return original
        if (scale >= 1.0f || scale <= 0f) {
            return bitmap
        }

        // Calculate new dimensions
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        if (newWidth <= 0 || newHeight <= 0) {
            return bitmap // Avoid scaling to zero size
        }

        // Create scaled bitmap
        return try {
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } catch (e: OutOfMemoryError) {
            Log.e("SpinDialView", "OutOfMemoryError scaling bitmap for wedge")
            bitmap // Return original bitmap if scaling fails
        } catch (e: Exception) {
            Log.e("SpinDialView", "Error scaling bitmap", e)
            bitmap // Return original on other errors
        }
    }

    // --- Image Loading Function ---
    private fun loadItemImage(item: SpinItemEntity) {
        if (item.itemType != SpinItemType.IMAGE || item.content.isBlank()) return

        val imageUri = try { Uri.parse(item.content) } catch (e: Exception) { null }
        if (imageUri == null) {
            Log.e("SpinDialView", "Invalid URI string for image item: ${item.content}")
            imageBitmapCache[item.id] = null // Mark as failed
            if (isAttachedToWindow) invalidate()
            return
        }

        // Set null initially to indicate loading
        imageBitmapCache[item.id] = null
        // Request redraw in case previous image was shown
        if (isAttachedToWindow) invalidate()

        Log.d("SpinDialView", "Loading image for item ${item.id} from $imageUri")
        Glide.with(context)
            .asBitmap()
            .load(imageUri) // Use parsed Uri
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    Log.d("SpinDialView", "Image loaded for item ${item.id}")
                    // Cache the loaded bitmap
                    imageBitmapCache[item.id] = resource
                    // Request redraw ONLY if the view is still attached
                    if (isAttachedToWindow) {
                        invalidate()
                    }
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    Log.d("SpinDialView", "Image load cleared for item ${item.id}")
                    // Handle placeholder state if needed
                    imageBitmapCache.remove(item.id) // Remove if cleared, maybe? Or keep null? Let's keep null.
                    imageBitmapCache[item.id] = null
                    if (isAttachedToWindow) invalidate()
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    super.onLoadFailed(errorDrawable)
                    Log.e("SpinDialView", "Image load FAILED for item ${item.id}")
                    // Mark as failed (null bitmap)
                    imageBitmapCache[item.id] = null // Indicate load failed
                    if (isAttachedToWindow) invalidate()
                }
            })
    }

    // --- Updated setData ---
    fun setData(newItems: List<SpinItemEntity>, newSettings: SpinSettingsEntity?) {
        val oldItems = this.items
        this.items = newItems
        this.settings = newSettings

        // Clear cache for items that are no longer present
        val newItemIds = newItems.map { it.id }.toSet()
        imageBitmapCache.keys.retainAll { it in newItemIds }

        // Preload images for new items
        newItems.forEach { newItem ->
            if (newItem.itemType == SpinItemType.IMAGE) {
                if (!imageBitmapCache.containsKey(newItem.id) || imageBitmapCache[newItem.id] == null) {
                    loadItemImage(newItem)
                }
            }
        }

        requestLayout()
        invalidate()
    }

    private fun calculateTextSize(text: String, sweepAngle: Float): Float {
        val availableWidth = (dialRadius * 0.8 * sin(Math.toRadians(sweepAngle / 2.0))).toFloat() * 2
        val baseTextSize = dialRadius / 5 // A reasonable starting point based on dial size
        return baseTextSize
    }
}