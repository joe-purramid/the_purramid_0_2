// SpotlightView.kt
package com.example.purramid.thepurramid.spotlight

import android.content.Context
import android.graphics.*
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.util.dpToPx
import kotlin.math.absoluteValue
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.maxOf // Use maxOf for comparing multiple values

class SpotlightView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    // --- Listener Interface (Modified) ---
    interface SpotlightInteractionListener {
        fun requestWindowMove(deltaX: Float, deltaY: Float) // Request window move based on delta
        fun requestWindowMoveFinished() // Notify window move finished
        fun requestUpdateSpotlightState(updatedSpotlight: Spotlight) // Send full state on drag/resize end
        fun requestTapPassThrough()
        fun requestClose(spotlightId: Int) // Pass ID of the spotlight associated with the interaction
        fun requestShapeChange()
        fun requestAddNew()
    }
    var interactionListener: SpotlightInteractionListener? = null

    // --- Paints ---
    private val maskColor = Color.argb(128, 0, 0, 0)
    // private val maskPaint = Paint().apply { color = maskColor }
    private val spotlightPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        isAntiAlias = true
    }

    // --- Data Handling ---
    private var spotlightsToDraw: List<Spotlight> = emptyList()
    private var currentGlobalShape: Spotlight.Shape = Spotlight.Shape.CIRCLE
    var showControls = true // Flag to control visibility, managed locally or by service if needed
    private var canAddMoreSpotlights = true // Track if max is reached, updated by service

    // --- Touch Handling State ---
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
    private var isDraggingSpotlight = false
    private var isDraggingWindow = false
    private var isResizing = false
    private var downX = 0f
    private var downY = 0f
    private var downTime: Long = 0
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    // State for dragging spotlight
    private var currentDraggingSpotlight: Spotlight? = null
    private var initialTouchX = 0f //
    private var initialTouchY = 0f //
    private var dragInitialSpotlightX = 0f // Store initial position for drag delta calc
    private var dragInitialSpotlightY = 0f

    // State for dragging window
    private var initialRawX = 0f
    private var initialRawY = 0f

    // State for resizing spotlight
    private var pointerId1 = MotionEvent.INVALID_POINTER_ID
    private var pointerId2 = MotionEvent.INVALID_POINTER_ID
    private var initialDistance = 0f
    private var initialAngle = 0f
    private var resizingSpotlight: Spotlight? = null
    // Store initial dimensions OF THE SPOTLIGHT being resized
    private var resizeInitialSpotlightRadius = 0f
    private var resizeInitialSpotlightSize = 0f
    private var resizeInitialSpotlightWidth = 0f
    private var resizeInitialSpotlightHeight = 0f
    // Temporary spotlight object to hold visual feedback during resize/drag
    private var visualFeedbackSpotlight: Spotlight? = null

    private val ovalRect = RectF()

    // --- UI Control Elements ---
    private val controlButtonSize = context.dpToPx(48)
    private val controlMargin = context.dpToPx(16)
    private var addRect = Rect()
    private var closeRect = Rect()
    private var shapeRect = Rect()
    private var addDrawable: Drawable? = null
    private var closeDrawable: Drawable? = null
    private var shapeDrawableCircle: Drawable? = null
    private var shapeDrawableSquare: Drawable? = null

    // Minimum size for spotlights
    private val minDimensionPx = context.dpToPx(50).toFloat()

    // Data class to represent a spotlight (moved from outside)
    data class Spotlight(
        val id: Int,
        var centerX: Float,
        var centerY: Float,
        var radius: Float,
        var size: Float = radius * 2,
        var width: Float = radius * 2,
        var height: Float = radius * 2,
        var shape: Shape
    ) {
        enum class Shape {
            CIRCLE, OVAL, SQUARE, RECTANGLE
        }
    }

    init {
        loadControlDrawables()
    }

    private fun loadControlDrawables() {
        addDrawable = ContextCompat.getDrawable(context, R.drawable.ic_add_circle)?.mutate()
        closeDrawable = ContextCompat.getDrawable(context, R.drawable.ic_close)?.mutate()
        shapeDrawableCircle = ContextCompat.getDrawable(context, R.drawable.ic_circle)?.mutate()
        shapeDrawableSquare = ContextCompat.getDrawable(context, R.drawable.ic_square)?.mutate()
    }

    // Called by the service to update the view's data
    fun updateSpotlights(newSpotlights: List<Spotlight>, globalShape: Spotlight.Shape) {
        spotlightsToDraw = newSpotlights
        currentGlobalShape = globalShape
        updateCanAddSpotlights(newSpotlights.size < 4)
        invalidate()
    }

    private fun updateCanAddSpotlights(canAdd: Boolean) {
        if (canAddMoreSpotlights != canAdd) {
            canAddMoreSpotlights = canAdd
            invalidate() // Redraw controls if state changes
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(maskColor)

        // Determine which spotlights to draw (original or temporary feedback)
        val listToDraw = if (isDraggingSpotlight || isResizing) {
            spotlightsToDraw.mapNotNull { original ->
                when {
                    visualFeedbackSpotlight?.id == original.id -> visualFeedbackSpotlight // Draw the feedback version
                    else -> original // Draw the original version
                }
            }
        } else {
            spotlightsToDraw // Draw the normal state list
        }


        listToDraw.forEach { spotlight ->
            val paint = spotlightPaint // Can reuse paint if xfermode is the main property
            when (spotlight.shape) {
                Spotlight.Shape.CIRCLE -> canvas.drawCircle(spotlight.centerX, spotlight.centerY, spotlight.radius, paint)
                Spotlight.Shape.OVAL -> {
                    ovalRect.set(spotlight.centerX - spotlight.width / 2f, spotlight.centerY - spotlight.height / 2f, spotlight.centerX + spotlight.width / 2f, spotlight.centerY + spotlight.height / 2f)
                    canvas.drawOval(ovalRect, paint)
                }
                Spotlight.Shape.SQUARE -> canvas.drawRect(spotlight.centerX - spotlight.size / 2f, spotlight.centerY - spotlight.size / 2f, spotlight.centerX + spotlight.size / 2f, spotlight.centerY + spotlight.size / 2f, paint)
                Spotlight.Shape.RECTANGLE -> canvas.drawRect(spotlight.centerX - spotlight.width / 2f, spotlight.centerY - spotlight.height / 2f, spotlight.centerX + spotlight.width / 2f, spotlight.centerY + spotlight.height / 2f, paint)
            }
        }

        if (showControls) {
            drawControls(canvas)
        }
    }

    private fun drawControls(canvas: Canvas) {
        val bottomY = height - controlMargin - controlButtonSize / 2f
        val totalWidthNeeded = controlButtonSize * 3 + controlMargin * 2
        val startX = (width - totalWidthNeeded) / 2f
        var currentX = startX

        // Add Button
        addRect.set(currentX.toInt(), (bottomY - controlButtonSize / 2f).toInt(), (currentX + controlButtonSize).toInt(), (bottomY + controlButtonSize / 2f).toInt())
        addDrawable?.bounds = addRect
        addDrawable?.alpha = if (canAddMoreSpotlights) 255 else 128
        addDrawable?.draw(canvas)
        currentX += controlButtonSize + controlMargin

        // Close Button
        closeRect.set(currentX.toInt(), (bottomY - controlButtonSize / 2f).toInt(), (currentX + controlButtonSize).toInt(), (bottomY + controlButtonSize / 2f).toInt())
        closeDrawable?.bounds = closeRect
        closeDrawable?.draw(canvas)
        currentX += controlButtonSize + controlMargin

        // Shape Button
        shapeRect.set(currentX.toInt(), (bottomY - controlButtonSize / 2f).toInt(), (currentX + controlButtonSize).toInt(), (bottomY + controlButtonSize / 2f).toInt())
        val shapeDrawable = if (currentGlobalShape == Spotlight.Shape.CIRCLE || currentGlobalShape == Spotlight.Shape.OVAL) shapeDrawableCircle else shapeDrawableSquare
        shapeDrawable?.bounds = shapeRect
        shapeDrawable?.draw(canvas)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val pointerIndex = event.actionIndex

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                downTime = System.currentTimeMillis()
                activePointerId = event.getPointerId(0)
                initialTouchX = event.x
                initialTouchY = event.y
                initialRawX = event.rawX
                initialRawY = event.rawY

                isDraggingSpotlight = false
                isDraggingWindow = false
                isResizing = false
                currentDraggingSpotlight = null
                resizingSpotlight = null
                visualFeedbackSpotlight = null // Clear feedback state
                pointerId1 = MotionEvent.INVALID_POINTER_ID
                pointerId2 = MotionEvent.INVALID_POINTER_ID

                if (showControls && handleControlTouch(downX, downY)) {
                    return true // Consumed by control
                }

                currentDraggingSpotlight = findTouchedSpotlight(initialTouchX, initialTouchY)
                if (currentDraggingSpotlight != null) {
                    // Create a copy for visual feedback during drag
                    visualFeedbackSpotlight = currentDraggingSpotlight?.copy()
                    // Store initial position of the spotlight being dragged
                    dragInitialSpotlightX = currentDraggingSpotlight!!.centerX
                    dragInitialSpotlightY = currentDraggingSpotlight!!.centerY
                }
                return true // Consume event
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2 && !isResizing && !isDraggingSpotlight && !isDraggingWindow) {
                    val index1 = event.findPointerIndex(event.getPointerId(0))
                    val index2 = event.findPointerIndex(pointerIndex) // Use actionIndex for the new pointer
                    if (index1 != -1 && index2 != -1) {
                        val x1 = event.getX(index1); val y1 = event.getY(index1)
                        val x2 = event.getX(index2); val y2 = event.getY(index2)
                        val midX = (x1 + x2) / 2f
                        val midY = (y1 + y2) / 2f
                        resizingSpotlight = findTouchedSpotlight(midX, midY)

                        if (resizingSpotlight != null) {
                            isResizing = true
                            currentDraggingSpotlight = null // Stop potential single-finger drag
                            isDraggingSpotlight = false
                            visualFeedbackSpotlight = resizingSpotlight?.copy() // Create copy for resize feedback

                            pointerId1 = event.getPointerId(0)
                            pointerId2 = event.getPointerId(pointerIndex)
                            initialDistance = hypot(x2 - x1, y2 - y1)
                            initialAngle = atan2(y2 - y1, x2 - x1)
                            // Capture initial dimensions from the ORIGINAL spotlight data
                            resizingSpotlight?.let {
                                resizeInitialSpotlightRadius = it.radius
                                resizeInitialSpotlightSize = it.size
                                resizeInitialSpotlightWidth = it.width
                                resizeInitialSpotlightHeight = it.height
                            }
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                // Handle Resize
                if (isResizing && resizingSpotlight != null && event.pointerCount >= 2) {
                    val idx1 = event.findPointerIndex(pointerId1)
                    val idx2 = event.findPointerIndex(pointerId2)
                    if (idx1 != -1 && idx2 != -1) {
                        val x1 = event.getX(idx1); val y1 = event.getY(idx1)
                        val x2 = event.getX(idx2); val y2 = event.getY(idx2)
                        val currentDistance = hypot(x2 - x1, y2 - y1)

                        if (initialDistance > 0) {
                            val scale = currentDistance / initialDistance
                            // Apply resize to the temporary feedback object
                            visualFeedbackSpotlight?.let { applyResize(it, scale) }
                            invalidate() // Redraw with temporary size
                        }
                    }
                    return true // Consume resize move
                }

                // Handle Spotlight Drag (only if NOT resizing)
                val primaryPointerIndex = event.findPointerIndex(activePointerId)
                if (!isResizing && currentDraggingSpotlight != null && primaryPointerIndex != -1) {
                    val currentTouchX = event.getX(primaryPointerIndex)
                    val currentTouchY = event.getY(primaryPointerIndex)
                    val deltaX = currentTouchX - initialTouchX
                    val deltaY = currentTouchY - initialTouchY

                    if (!isDraggingSpotlight && hypot(deltaX, deltaY) > touchSlop) {
                        isDraggingSpotlight = true
                    }
                    if (isDraggingSpotlight) {
                        // Update the temporary feedback object's position
                        visualFeedbackSpotlight?.centerX = dragInitialSpotlightX + deltaX
                        visualFeedbackSpotlight?.centerY = dragInitialSpotlightY + deltaY
                        invalidate() // Redraw with temporary position
                    }
                    return true // Consume spotlight drag move
                }

                // Handle Window Drag (only if not resizing or dragging a spotlight)
                if (!isResizing && currentDraggingSpotlight == null && primaryPointerIndex != -1) {
                    val currentRawX = event.getRawX(primaryPointerIndex) // Use index with getRawX
                    val currentRawY = event.getRawY(primaryPointerIndex) // Use index with getRawY
                    val deltaRawX = currentRawX - initialRawX
                    val deltaRawY = currentRawY - initialRawY

                    if (!isDraggingWindow && hypot(deltaRawX, deltaRawY) > touchSlop) {
                        isDraggingWindow = true
                    }
                    if (isDraggingWindow) {
                        interactionListener?.requestWindowMove(deltaRawX, deltaRawY)
                        initialRawX = currentRawX // Update base for next delta
                        initialRawY = currentRawY
                    }
                    return true // Consume window drag move
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upPointerId = event.getPointerId(pointerIndex)
                if (isResizing && (upPointerId == pointerId1 || upPointerId == pointerId2)) {
                    // One resizing finger lifted, end resize and report final state
                    visualFeedbackSpotlight?.let {
                        interactionListener?.requestUpdateSpotlightState(it)
                    }
                    isResizing = false
                    resizingSpotlight = null
                    visualFeedbackSpotlight = null // Clear feedback state
                    pointerId1 = MotionEvent.INVALID_POINTER_ID
                    pointerId2 = MotionEvent.INVALID_POINTER_ID
                }
                // Update activePointerId if the primary one lifted? Not strictly necessary here.
                return true
            }

            MotionEvent.ACTION_UP -> {
                val upTime = System.currentTimeMillis()
                val wasDraggingSpotlight = isDraggingSpotlight
                val wasDraggingWindow = isDraggingWindow
                val wasResizing = isResizing // Should be false if POINTER_UP already handled it

                // Report final state if interaction was in progress and not already reported by POINTER_UP
                if (wasDraggingSpotlight && currentDraggingSpotlight != null) {
                    // Calculate final position and report
                    val finalX = dragInitialSpotlightX + (event.x - initialTouchX)
                    val finalY = dragInitialSpotlightY + (event.y - initialTouchY)
                    visualFeedbackSpotlight?.let {
                        it.centerX = finalX
                        it.centerY = finalY
                        interactionListener?.requestUpdateSpotlightState(it)
                    }
                }
                // Resize state should have been reported on POINTER_UP mostly,
                // but handle case where both fingers lift simultaneously.
                if (wasResizing && resizingSpotlight != null) {
                    visualFeedbackSpotlight?.let { interactionListener?.requestUpdateSpotlightState(it) }
                }
                if (wasDraggingWindow) {
                    interactionListener?.requestWindowMoveFinished()
                }

                // Reset flags
                isDraggingSpotlight = false
                isDraggingWindow = false
                isResizing = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                pointerId1 = MotionEvent.INVALID_POINTER_ID
                pointerId2 = MotionEvent.INVALID_POINTER_ID
                currentDraggingSpotlight = null
                resizingSpotlight = null
                visualFeedbackSpotlight = null

                // Check for TAP
                if (!wasDraggingSpotlight && !wasDraggingWindow && !wasResizing) {
                    val deltaTime = upTime - downTime
                    val deltaX = (event.x - downX).absoluteValue
                    val deltaY = (event.y - downY).absoluteValue

                    if (deltaTime < tapTimeout && deltaX < touchSlop && deltaY < touchSlop) {
                        performClick() // Accessibility
                        if (showControls && handleControlTouch(downX, downY, isTap = true)) {
                            // Tap handled by controls
                        } else {
                            val tappedSpotlight = findTouchedSpotlight(downX, downY)
                            if (tappedSpotlight != null) {
                                interactionListener?.requestTapPassThrough()
                            } else {
                                showControls = !showControls // Toggle controls on mask tap
                                invalidate()
                            }
                        }
                        return true // Tap handled
                    }
                }
                // Redraw to ensure final state without feedback object is shown
                invalidate()
                return true // Consume UP after drag/resize
            }

            MotionEvent.ACTION_CANCEL -> {
                // Reset everything on cancel
                isDraggingSpotlight = false
                isDraggingWindow = false
                isResizing = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                pointerId1 = MotionEvent.INVALID_POINTER_ID
                pointerId2 = MotionEvent.INVALID_POINTER_ID
                currentDraggingSpotlight = null
                resizingSpotlight = null
                visualFeedbackSpotlight = null
                invalidate() // Redraw in normal state
                return true
            }
        }
        return super.onTouchEvent(event)
    }


    // Helper to apply resize scaling to the visualFeedbackSpotlight
    private fun applyResize(spotlightToModify: Spotlight, scale: Float) {
        val maxDimension = maxOf(width, height).toFloat().coerceAtLeast(minDimensionPx * 2) // Ensure maxDimension is reasonable

        // Get the shape from the original spotlight being resized
        val originalShape = resizingSpotlight?.shape ?: return // Safety check

        when (originalShape) {
            Spotlight.Shape.CIRCLE -> {
                spotlightToModify.radius = (resizeInitialSpotlightRadius * scale).coerceIn(minDimensionPx / 2f, maxDimension / 2f)
                spotlightToModify.width = spotlightToModify.radius * 2
                spotlightToModify.height = spotlightToModify.radius * 2
                spotlightToModify.size = spotlightToModify.radius * 2
            }
            Spotlight.Shape.SQUARE -> {
                spotlightToModify.size = (resizeInitialSpotlightSize * scale).coerceIn(minDimensionPx, maxDimension)
                spotlightToModify.width = spotlightToModify.size
                spotlightToModify.height = spotlightToModify.size
                spotlightToModify.radius = spotlightToModify.size / 2f
            }
            Spotlight.Shape.OVAL, Spotlight.Shape.RECTANGLE -> {
                val aspectRatio = if (resizeInitialSpotlightHeight > 0) resizeInitialSpotlightWidth / resizeInitialSpotlightHeight else 1f
                var newWidth = (resizeInitialSpotlightWidth * scale)
                var newHeight = (resizeInitialSpotlightHeight * scale)

                // Apply min constraints while trying to maintain aspect ratio
                if (newWidth < minDimensionPx || newHeight < minDimensionPx) {
                    if (aspectRatio >= 1) { // Wider or square
                        newHeight = minDimensionPx
                        newWidth = maxOf(minDimensionPx, newHeight * aspectRatio)
                    } else { // Taller
                        newWidth = minDimensionPx
                        newHeight = maxOf(minDimensionPx, newWidth / aspectRatio)
                    }
                }
                // Apply max constraints
                if (newWidth > maxDimension || newHeight > maxDimension) {
                    if (aspectRatio >= 1) { // Wider or square
                        newWidth = maxDimension
                        newHeight = min(maxDimension, newWidth / aspectRatio)
                    } else { // Taller
                        newHeight = maxDimension
                        newWidth = min(maxDimension, newHeight * aspectRatio)
                    }
                }

                spotlightToModify.width = newWidth
                spotlightToModify.height = newHeight
                spotlightToModify.size = maxOf(newWidth, newHeight)
                spotlightToModify.radius = maxOf(newWidth, newHeight) / 2f
            }
        }
        // Ensure shape remains consistent during visual feedback
        spotlightToModify.shape = originalShape
    }


    // Helper to check and handle taps on control buttons
    private fun handleControlTouch(x: Float, y: Float, isTap: Boolean = false): Boolean {
        if (!showControls) return false

        if (addRect.contains(x.toInt(), y.toInt())) {
            if (isTap && canAddMoreSpotlights) interactionListener?.requestAddNew()
            return true
        }
        // Use the ID of the *first* spotlight for the close action, assuming controls are global
        val spotlightIdToClose = spotlightsToDraw.firstOrNull()?.id ?: -1
        if (closeRect.contains(x.toInt(), y.toInt())) {
            if (isTap && spotlightIdToClose != -1) interactionListener?.requestClose(spotlightIdToClose)
            return true
        }
        if (shapeRect.contains(x.toInt(), y.toInt())) {
            if (isTap) interactionListener?.requestShapeChange()
            return true
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    // Find spotlight based on touch coordinates
    private fun findTouchedSpotlight(x: Float, y: Float): Spotlight? {
        // Check in reverse order so top-most is found first
        for (spotlight in spotlightsToDraw.reversed()) { // Use the data received from service
            val found = when (spotlight.shape) {
                Spotlight.Shape.CIRCLE -> hypot(x - spotlight.centerX, y - spotlight.centerY) < spotlight.radius
                Spotlight.Shape.OVAL -> {
                    val a = spotlight.width / 2f
                    val b = spotlight.height / 2f
                    if (a > 0 && b > 0) {
                        (((x - spotlight.centerX) / a).pow(2) + ((y - spotlight.centerY) / b).pow(2)) <= 1
                    } else false
                }
                Spotlight.Shape.SQUARE -> {
                    val halfSize = spotlight.size / 2f
                    x >= spotlight.centerX - halfSize && x <= spotlight.centerX + halfSize &&
                            y >= spotlight.centerY - halfSize && y <= spotlight.centerY + halfSize
                }
                Spotlight.Shape.RECTANGLE -> {
                    val halfWidth = spotlight.width / 2f
                    val halfHeight = spotlight.height / 2f
                    x >= spotlight.centerX - halfWidth && x <= spotlight.centerX + halfWidth &&
                            y >= spotlight.centerY - halfHeight && y <= spotlight.centerY + halfHeight
                }
            }
            if (found) return spotlight
        }
        return null
    }

    // Simple power function for Float
    private fun Float.pow(exp: Int): Float {
        var result = 1.0f
        repeat(exp) { result *= this }
        return result
    }
}