// MaskView.kt
package com.example.purramid.thepurramid.screen_mask.ui

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.example.purramid.thepurramid.R
import com.example.purramid.thepurramid.screen_mask.ScreenMaskState
import com.example.purramid.thepurramid.util.dpToPx
import kotlin.math.abs
import kotlin.math.max

class MaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val instanceId: Int // Each MaskView needs to know its ID
) : FrameLayout(context, attrs, defStyleAttr) {

    // Define the desired minimum dimension in dp
    private val minDimensionDp = 50
    // Calculate the equivalent minimum dimension in pixels, using lazy initialization
    private val minDimensionPx: Int by lazy { dpToPx(minDimensionDp) }

    interface InteractionListener {
        fun onMaskMoved(instanceId: Int, x: Int, y: Int)
        fun onMaskResized(instanceId: Int, width: Int, height: Int)
        fun onLockToggled(instanceId: Int)
        fun onCloseRequested(instanceId: Int)
        fun onBillboardTapped(instanceId: Int) // To request image change
        fun onColorChangeRequested(instanceId: Int) // Placeholder for color picker
        fun onControlsToggled(instanceId: Int) // To toggle controls visibility
    }

    var interactionListener: InteractionListener? = null
    private var currentState: ScreenMaskState

    private var billboardImageView: ImageView
    private var closeButton: ImageView
    // Add other control buttons if they are part of the mask itself (e.g., lock, color)

    private var yellowBorder: GradientDrawable
    private var borderFadeAnimator: ObjectAnimator? = null

    // Touch handling for move and resize
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var initialMaskX: Int = 0
    private var initialMaskY: Int = 0
    private var initialMaskWidth: Int = 0
    private var initialMaskHeight: Int = 0
    private var isMoving = false
    private var isResizing = false // More sophisticated resize later
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private enum class ResizeDirection { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var currentResizeDirection = ResizeDirection.NONE
    private val resizeHandleSize = context.dpToPx(24) // Increased for easier touch

    // Scale Gesture Detector for pinch-to-resize
    private var scaleGestureDetector: ScaleGestureDetector
    private var scaleFactor = 1f

    // Properties to store dimensions at the start of a scale gesture
    private var initialWidthOnScale = 0
    private var initialHeightOnScale = 0

    // --- Click detection ---
    private var potentialClick = false
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong()
    private var downEventTimestamp: Long = 0

    init {
        // Set initial background (will be updated by state)
        setBackgroundColor(Color.BLACK) // Or any other opaque default you prefer

        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
        setupMaskTouchListener()

        // Billboard ImageView
        billboardImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = GONE // Initially hidden
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                val padding = (width * 0.05f).toInt() // Example padding
                setMargins(padding, padding, padding, padding)
            }
            setOnClickListener {
                if (!currentState.isLocked) { // Only allow changing image if not locked
                    interactionListener?.onBillboardTapped(instanceId)
                }
            }
        }
        addView(billboardImageView)

        // Close Button
        closeButton = ImageView(context).apply {
            setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_close))
            val buttonSize = context.dpToPx(32)
            setPadding(context.dpToPx(4), context.dpToPx(4), context.dpToPx(4), context.dpToPx(4))
            layoutParams = LayoutParams(buttonSize, buttonSize).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(context.dpToPx(4), context.dpToPx(4), context.dpToPx(4), context.dpToPx(4))
            }
            setOnClickListener {
                if (!currentState.isLocked) {
                    interactionListener?.onCloseRequested(instanceId)
                } else {
                    // Consider Snackbar if Activity context is available, or log for now
                    Toast.makeText(context, "Unlock mask to close", Toast.LENGTH_SHORT).show()
                }
            }
        }
        addView(closeButton)

        // Yellow border for locked state
        yellowBorder = GradientDrawable().apply {
            setStroke(context.dpToPx(3), Color.YELLOW)
            setColor(Color.TRANSPARENT) // Transparent fill
        }

        // Initialize with a default state (will be overwritten)
        this.currentState = ScreenMaskState(instanceId = instanceId)

        // Setup scale gesture detector
        scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())

        // Set up touch listener on the MaskView itself for moving and resizing
        setupMaskTouchListener()
    }

    fun updateState(newState: ScreenMaskState) {
        this.currentState = newState

        // Update border visibility based on lock state
        if (newState.isLocked) {
            // Start fade-in then fade-out animation
            foreground = yellowBorder // Set border as foreground to overlay content
            yellowBorder.alpha = 255
            borderFadeAnimator?.cancel()
            borderFadeAnimator = ObjectAnimator.ofInt(yellowBorder, "alpha", 255, 0).apply {
                duration = 1000 // Fade out over 1 second
                startDelay = 500 // Wait 0.5s before starting fade
                addUpdateListener { invalidate() } // Invalidate to redraw border alpha
                start()
            }
        } else {
            borderFadeAnimator?.cancel()
            foreground = null // Remove border
        }
        invalidate() // Redraw view
        // Show/hide controls based on state
        closeButton.visibility = if (newState.isControlsVisible) VISIBLE else GONE
        // Add other control button visibility updates here
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupMaskTouchListener() {
        this.setOnTouchListener { _, event ->
            if (currentState.isLocked) return@setOnTouchListener true // Consume touch if locked

            // --- Scaling Logic (priority) ---
            val wasScaling = scaleGestureDetector.isInProgress
            scaleGestureDetector.onTouchEvent(event)
            val isScaling = scaleGestureDetector.isInProgress
            if (isScaling || wasScaling) {
                potentialClick = false // Scaling cancels click
                isMoving = false
                isResizing = false
                currentResizeDirection = ResizeDirection.NONE
                return@setOnTouchListener true
            }

            val currentTime = System.currentTimeMillis()
            val action = event.actionMasked
            scaleGestureDetector.onTouchEvent(event) // Pass to scale detector first

            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialMaskX = currentState.x
                    initialMaskY = currentState.y
                    initialMaskWidth = currentState.width
                    initialMaskHeight = currentState.height

                    isMoving = false
                    isResizing = false
                    currentResizeDirection = getResizeDirection(event.x, event.y)

                    if (currentResizeDirection != ResizeDirection.NONE && currentResizeDirection != ResizeDirection.MOVE) {
                        isResizing = true
                    }

                    // --- Start tracking potential click ---
                    potentialClick = true
                    downEventTimestamp = currentTime
                    // --- End tracking ---

                    return@setOnTouchListener true // Consume DOWN event
                }

                MotionEvent.ACTION_MOVE -> {
                    // If significant movement or resizing occurs, it's not a click
                    if (isResizing || isMoving || abs(event.rawX - initialTouchX) > touchSlop || abs(event.rawY - initialTouchY) > touchSlop) {
                        potentialClick = false
                    }

                    // --- Handle move/resize (only if not scaling) ---
                    if (isResizing && currentResizeDirection != ResizeDirection.NONE && currentResizeDirection != ResizeDirection.MOVE) {
                        // potentialClick already false if resizing started
                        val deltaX = event.rawX - initialTouchX
                        val deltaY = event.rawY - initialTouchY
                        handleResize(deltaX, deltaY)
                        return@setOnTouchListener true
                    } else if (!isResizing && (currentResizeDirection == ResizeDirection.MOVE || (abs(event.rawX - initialTouchX) > touchSlop || abs(event.rawY - initialTouchY) > touchSlop))) {
                        // Start moving if needed (and if slop exceeded)
                        if (!isMoving) {
                            if (abs(event.rawX - initialTouchX) > touchSlop || abs(event.rawY - initialTouchY) > touchSlop) {
                                isMoving = true
                                potentialClick = false // Definitely not a click if moving starts
                                // Adjust initial touch point to avoid jump
                                initialTouchX = event.rawX - (currentState.x - initialMaskX)
                                initialTouchY = event.rawY - (currentState.y - initialMaskY)
                            }
                        }
                        // If moving, update position
                        if (isMoving) {
                            val newX = initialMaskX + (event.rawX - initialTouchX).toInt()
                            val newY = initialMaskY + (event.rawY - initialTouchY).toInt()
                            interactionListener?.onMaskMoved(instanceId, newX, newY)
                            return@setOnTouchListener true
                        }
                    }
                    // --- End Handle move/resize ---
                }

                MotionEvent.ACTION_UP -> { // Only handle click on UP, not CANCEL
                    val wasPotentiallyClicking = potentialClick // Store flag before reset

                    // Reset state machine
                    isMoving = false
                    isResizing = false
                    currentResizeDirection = ResizeDirection.NONE
                    potentialClick = false // Reset click flag

                    // --- Check for and handle click ---
                    if (wasPotentiallyClicking && (currentTime - downEventTimestamp) < tapTimeout) {
                        if (!isEventConsumedByChild(event)) {
                            // Perform the click action!
                            Log.d("MaskView", "Tap detected, calling performClick for $instanceId")
                            performClick()
                            return@setOnTouchListener true // Click handled
                        }
                    }
                    // --- End Check for click ---

                    // If it wasn't a click, it was the end of move/resize/etc.
                    // Final state reported continuously, so nothing specific needed here usually.
                    return@setOnTouchListener true // Consume UP if we consumed DOWN
                }

                MotionEvent.ACTION_CANCEL -> {
                    // Reset everything on cancel
                    isMoving = false
                    isResizing = false
                    currentResizeDirection = ResizeDirection.NONE
                    potentialClick = false
                    return@setOnTouchListener true
                }
            }
            // Default fallback (shouldn't be reached often if DOWN is consumed)
            return@setOnTouchListener false
        }
    }

    // Check if touch event is within bounds of interactive child elements
    private fun isEventConsumedByChild(event: MotionEvent): Boolean {
        val x = event.x.toInt()
        val y = event.y.toInt()
        val hitRect = android.graphics.Rect()

        // Check close button
        closeButton.getHitRect(hitRect)
        if (closeButton.isVisible && hitRect.contains(x, y)) {
            return true // Optimization: return early if found
        }

        // Check billboard image view
        billboardImageView.getHitRect(hitRect)
        if (billboardImageView.isVisible && hitRect.contains(x, y)) {
            return true // Optimization: return early if found
        }
        // Add checks for other elements here...
        // If none of the above returned true
        return false
    }

    private fun getResizeDirection(localX: Float, localY: Float): ResizeDirection {
        val viewWidth = this.width.toFloat()
        val viewHeight = this.height.toFloat()

        val onLeftEdge = localX < resizeHandleSize
        val onRightEdge = localX > viewWidth - resizeHandleSize
        val onTopEdge = localY < resizeHandleSize
        val onBottomEdge = localY > viewHeight - resizeHandleSize

        return when {
            onTopEdge && onLeftEdge -> ResizeDirection.TOP_LEFT
            onTopEdge && onRightEdge -> ResizeDirection.TOP_RIGHT
            onBottomEdge && onLeftEdge -> ResizeDirection.BOTTOM_LEFT
            onBottomEdge && onRightEdge -> ResizeDirection.BOTTOM_RIGHT
            onLeftEdge -> ResizeDirection.LEFT
            onRightEdge -> ResizeDirection.RIGHT
            onTopEdge -> ResizeDirection.TOP
            onBottomEdge -> ResizeDirection.BOTTOM
            else -> ResizeDirection.MOVE // If not on an edge, it's a move
        }
    }

    private fun handleResize(rawDeltaX: Float, rawDeltaY: Float) {
        var newX = currentState.x
        var newY = currentState.y
        var newWidth = currentState.width
        var newHeight = currentState.height

        when (currentResizeDirection) {
            ResizeDirection.LEFT -> {
                newWidth = (initialMaskWidth - rawDeltaX).toInt()
                newX = (initialMaskX + rawDeltaX).toInt()
            }
            ResizeDirection.RIGHT -> newWidth = (initialMaskWidth + rawDeltaX).toInt()
            ResizeDirection.TOP -> {
                newHeight = (initialMaskHeight - rawDeltaY).toInt()
                newY = (initialMaskY + rawDeltaY).toInt()
            }
            ResizeDirection.BOTTOM -> newHeight = (initialMaskHeight + rawDeltaY).toInt()
            ResizeDirection.TOP_LEFT -> {
                newWidth = (initialMaskWidth - rawDeltaX).toInt()
                newX = (initialMaskX + rawDeltaX).toInt()
                newHeight = (initialMaskHeight - rawDeltaY).toInt()
                newY = (initialMaskY + rawDeltaY).toInt()
            }
            ResizeDirection.TOP_RIGHT -> {
                newWidth = (initialMaskWidth + rawDeltaX).toInt()
                newHeight = (initialMaskHeight - rawDeltaY).toInt()
                newY = (initialMaskY + rawDeltaY).toInt()
            }
            ResizeDirection.BOTTOM_LEFT -> {
                newWidth = (initialMaskWidth - rawDeltaX).toInt()
                newX = (initialMaskX + rawDeltaX).toInt()
                newHeight = (initialMaskHeight + rawDeltaY).toInt()
            }
            ResizeDirection.BOTTOM_RIGHT -> {
                newWidth = (initialMaskWidth + rawDeltaX).toInt()
                newHeight = (initialMaskHeight + rawDeltaY).toInt()
            }
            else -> return
        }

        // Apply min size constraints
        newWidth = max(newWidth, minDimensionPx.toInt())
        newHeight = max(newHeight, minDimensionPx.toInt())

        // To maintain position correctly during edge drags, adjust X/Y only if size changed
        if (newWidth != currentState.width) currentState.x = newX
        if (newHeight != currentState.height) currentState.y = newY
        currentState.width = newWidth
        currentState.height = newHeight

        // For visual feedback during resize:
        // Directly tell service to update WindowManager params
        interactionListener?.onMaskMoved(instanceId, currentState.x, currentState.y) // For position
        interactionListener?.onMaskResized(instanceId, currentState.width, currentState.height) // For size
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (currentState.isLocked || isMoving || currentResizeDirection != ResizeDirection.MOVE) {
                // Don't start scale if explicitly resizing an edge or moving or locked
                return false
            }
            isResizing = true // Indicate general resize operation

            // --- Use OnScale variables ---
            initialWidthOnScale = currentState.width
            initialHeightOnScale = currentState.height
            // Handle cases where dimensions might be MATCH_PARENT or invalid
            if (initialWidthOnScale <= 0) initialWidthOnScale = width
            if (initialHeightOnScale <= 0) initialHeightOnScale = height

            // Reset scale factor at the beginning of the gesture
            scaleFactor = 1.0f

            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (!isResizing || currentState.isLocked) return false
            scaleFactor *= detector.scaleFactor

            var newWidth = (initialMaskWidth * scaleFactor).toInt()
            var newHeight = (initialMaskHeight * scaleFactor).toInt()

            newWidth = max(newWidth, minDimensionPx.toInt())
            newHeight = max(newHeight, minDimensionPx.toInt())

            // For visual feedback:
            interactionListener?.onMaskResized(instanceId, newWidth, newHeight)
            // Update currentState temporarily for consistent feedback IF other interactions also update it.
            // However, the final update will come from the ViewModel.
            // Better: just pass newWidth/newHeight to listener.
            // currentState.width = newWidth (visual only)
            // currentState.height = newHeight (visual only)
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (isResizing) {
                // Final calculation for safety, though onScale should be accurate
                var finalWidth = (initialMaskWidth * scaleFactor).toInt()
                var finalHeight = (initialMaskHeight * scaleFactor).toInt()
                finalWidth = max(finalWidth, minDimensionPx.toInt())
                finalHeight = max(finalHeight, minDimensionPx.toInt())

                interactionListener?.onMaskResized(instanceId, finalWidth, finalHeight)
            }
            isResizing = false
            scaleFactor = 1.0f
        }
    }

    @SuppressLint("ClickableViewAccessibility") // performClick is meant to be called programmatically
    override fun performClick(): Boolean {
        // Always call super first - it might handle accessibility actions.
        val handledBySuper = super.performClick()
        if (handledBySuper) {
            Log.d("MaskView", "performClick handled by super for $instanceId")
            return true // Let super handle it
        }

        // Perform the action associated with a click: toggle controls.
        Log.d("MaskView", "performClick executing action for $instanceId")
        interactionListener?.onControlsToggled(instanceId)

        // Optional: Play standard click sound
        // playSoundEffect(android.view.SoundEffectConstants.CLICK)

        // Return true to indicate the click action was handled by this view.
        return true
    }

    private fun applyImagePadding() {
        val currentImageView = billboardImageView
        val currentLayoutParams = currentImageView.layoutParams as? LayoutParams
        currentLayoutParams?.let {
            val parentWidth = this.width // MaskView width
            val padding = (parentWidth * 0.05f).toInt().coerceAtLeast(0)
            if (it.leftMargin != padding || it.topMargin != padding) { // Avoid redundant updates
                it.setMargins(padding, padding, padding, padding)
                currentImageView.layoutParams = it
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (currentState.isBillboardVisible && currentState.billboardImageUri != null) {
            applyImagePadding()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()
    }
}